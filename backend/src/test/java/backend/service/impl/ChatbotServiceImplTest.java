package backend.service.impl;

import backend.dto.ChatAskResponse;
import backend.dto.ChatContext;
import backend.dto.ChatDTO;
import backend.dto.ChatHistoryDTO;
import backend.dto.ConversationRecordingResult;
import backend.entity.HelpField;
import backend.entity.TableT24;
import backend.repository.HelpFieldRepository;
import backend.repository.TableT24Repository;
import backend.service.ChatHistoryService;
import backend.service.ChatResponseCache;
import backend.service.ConceptGlossary;
import backend.service.ChatService;
import backend.service.ConversationRecorder;
import backend.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Preuve que le pipeline du chatbot est bien "Question -&gt; RagService -&gt; Llama3" et non plus
 * "Question -&gt; recherche Oracle -&gt; Llama3" : {@link ChatbotServiceImpl} ne référence même plus
 * {@code HelpContextBuilderService} (voir son constructeur), donc toute exécution de {@code ask()}
 * passe forcément par {@link RagService#buildContext(String, backend.dto.ChatContext)}. La persistance de la conversation
 * ({@link ConversationRecorder}) est un canal additif : elle ne doit jamais modifier la réponse du
 * pipeline RAG lui-même (voir {@code ask_persistenceFailure_*}/{@code ask_authenticationRequired_*}) ;
 * seul le champ {@code authenticationRequired} de {@link ChatAskResponse} reflète son état.
 */
@ExtendWith(MockitoExtension.class)
class ChatbotServiceImplTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;
    @Mock
    private RagService ragService;
    @Mock
    private ConversationRecorder conversationRecorder;
    @Mock
    private ChatResponseCache responseCache;
    @Mock
    private ConceptGlossary conceptGlossary;
    @Mock
    private ChatService chatService;
    @Mock
    private ChatHistoryService chatHistoryService;
    @Mock
    private TableT24Repository tableT24Repository;
    @Mock
    private HelpFieldRepository helpFieldRepository;
    @Mock
    private backend.service.FieldSearchService fieldSearchService;

    private ChatbotServiceImpl chatbotService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        chatbotService = new ChatbotServiceImpl(chatClientBuilder, ragService, conversationRecorder, responseCache, chatService,
                chatHistoryService, tableT24Repository, helpFieldRepository, fieldSearchService, conceptGlossary);
        lenient().when(conversationRecorder.recordUserMessage(anyString())).thenReturn(ConversationRecordingResult.persisted(42L, 100L));
        lenient().when(responseCache.get(anyString())).thenReturn(Optional.empty());
        // Chat 42 n'a aucun contexte T24 fixé : les tests ci-dessous exercent le pipeline non restreint
        // (ChatContext.EMPTY) sauf mention contraire explicite dans un test dédié au scoping.
        lenient().when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, null, null, null));
        // Pas d'historique antérieur par défaut : les tests ci-dessous exercent le pipeline sans
        // mémoire conversationnelle, sauf mention contraire explicite dans un test dédié.
        lenient().when(chatHistoryService.findByChat(eq(42L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void ask_exhaustiveDumpRequestWithModuleContext_bypassesRagAndLlama3_buildsDumpFromHelpField() {
        // "Donne-moi toutes les informations du module MD" (contexte module actif, aucune table
        // précise) : doit construire la réponse directement depuis HELP_FIELD, jamais appeler
        // ragService/Llama3 (voir la Javadoc de ChatbotServiceImpl.ask sur ce court-circuit).
        lenient().when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, "MD", null, null));
        TableT24 table = TableT24.builder().nomTable("MD.PARAMETER").build();
        when(tableT24Repository.findByModuleNomModuleIgnoreCase(eq("MD"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(table)));
        HelpField field = new HelpField();
        field.setFieldName("GTREC");
        field.setDescription("Guarantee Received");
        field.setFieldType("Alphanumeric");
        field.setMandatory("Oui");
        when(helpFieldRepository.findByTableNameIgnoreCase("MD.PARAMETER")).thenReturn(List.of(field));

        ChatAskResponse response = chatbotService.ask("Donne-moi toutes les informations du module MD");

        assertThat(response.getAnswer())
                .contains("MD.PARAMETER")
                .contains("GTREC")
                .contains("Guarantee Received");
        verify(ragService, never()).buildContext(anyString(), any()); // aucune recherche (isIndexLoading() seul est consulté)
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_allTablesFilteredByAConcept_isNotTheModuleInventory_goesToTheConceptSearch() {
        // Bug réel (2026-10-07, module MD) : « Quelles sont toutes les tables liées aux balances ? » renvoyait les 25 tables du
        // module avec leurs champs au lieu des seules tables de balances.
        lenient().when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, "MD", null, null));
        when(fieldSearchService.tryAnswer(eq("Quelles sont toutes les tables liées aux balances ?"), any()))
                .thenReturn(Optional.of("**Réponse :** 3 tables du module MD portent « balances » dans leur nom :"));

        String answer = chatbotService.ask("Quelles sont toutes les tables liées aux balances ?").getAnswer();

        assertThat(answer).startsWith("**Réponse :** 3 tables du module MD");
        verifyNoInteractions(tableT24Repository);
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_exhaustiveDumpRequestWithTableContext_bypassesRagAndLlama3_listsAllFieldsOfThatTable() {
        lenient().when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, "AA", "AA.ARR.CHARGEOFF", null));
        HelpField field = new HelpField();
        field.setFieldName("STATUS");
        field.setDescription("Statut de l'arrangement");
        when(helpFieldRepository.findByTableNameIgnoreCase("AA.ARR.CHARGEOFF")).thenReturn(List.of(field));

        ChatAskResponse response = chatbotService.ask("Liste tous les champs de cette table");

        assertThat(response.getAnswer()).contains("STATUS").contains("Statut de l'arrangement");
        verify(ragService, never()).buildContext(anyString(), any()); // aucune recherche (isIndexLoading() seul est consulté)
        verify(tableT24Repository, never()).findByModuleNomModuleIgnoreCase(anyString(), any(Pageable.class));
    }

    @Test
    void ask_describeTableWithoutMentioningFields_alsoTriggersTheFullDump_notJustAShortSummary() {
        // "Décris la table X" doit renvoyer toutes les infos disponibles, pas seulement le résumé en
        // 1-2 phrases de la section "TABLES T24" du prompt — demande explicite (voir TABLE_OVERVIEW_PATTERN).
        lenient().when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, "AA", "AA.ARR.CHARGEOFF", null));
        HelpField field = new HelpField();
        field.setFieldName("STATUS");
        field.setDescription("Statut de l'arrangement");
        when(helpFieldRepository.findByTableNameIgnoreCase("AA.ARR.CHARGEOFF")).thenReturn(List.of(field));

        ChatAskResponse response = chatbotService.ask("Décris la table AA.ARR.CHARGEOFF");

        assertThat(response.getAnswer()).contains("STATUS").contains("Statut de l'arrangement");
        verify(ragService, never()).buildContext(anyString(), any()); // aucune recherche (isIndexLoading() seul est consulté)
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_describeASpecificFieldOfTheActiveTable_doesNotTriggerTheFullTableDump() {
        // "Décris le champ STATUS" mentionne explicitement un champ : doit rester une réponse ciblée
        // via le pipeline RAG/Llama3 habituel, jamais le dump complet de toute la table (voir
        // MENTIONS_SPECIFIC_FIELD_PATTERN, l'exclusion qui distingue ce cas de celui ci-dessus).
        lenient().when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, "AA", "AA.ARR.CHARGEOFF", null));
        when(ragService.buildContext(eq("Décris le champ STATUS"), any(ChatContext.class)))
                .thenReturn(Optional.of("Table : AA.ARR.CHARGEOFF\nChamps (1) :\n\nSTATUS\n\nDescription :\nStatut"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("**Champ :** `STATUS`\n\n**Fonction :**\n\nStatut de l'arrangement.")))));

        ChatAskResponse response = chatbotService.ask("Décris le champ STATUS");

        assertThat(response.getAnswer()).contains("Statut de l'arrangement.");
        verify(ragService).buildContext(eq("Décris le champ STATUS"), any(ChatContext.class));
        // Le dump complet de table (buildExhaustiveDump) n'est jamais déclenché ici : seul le pipeline
        // RAG/Llama3 habituel répond, sans jamais toucher à TableT24Repository (réservé au dump).
        verifyNoInteractions(tableT24Repository);
    }

    @Test
    void ask_buildsContextThroughRagService_thenSendsItToLlama3InTheRequiredT24Prompt() {
        when(ragService.buildContext("Que fait AA.ARR.CHARGEOFF ?", ChatContext.EMPTY))
                .thenReturn(Optional.of("Table : AA.ARR.CHARGEOFF\nProduit : AA\n\nChamps (1) :\n\nSTATUS\n\nDescription :\nStatut de l'arrangement"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("Le module CHARGEOFF gère les créances irrécouvrables.")))));

        ChatAskResponse response = chatbotService.ask("Que fait AA.ARR.CHARGEOFF ?");

        assertThat(response.getAnswer()).isEqualTo("Le module CHARGEOFF gère les créances irrécouvrables.");
        assertThat(response.isAuthenticationRequired()).isFalse();
        verify(ragService).buildContext("Que fait AA.ARR.CHARGEOFF ?", ChatContext.EMPTY);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        assertThat(systemCaptor.getValue())
                .contains("Tu es un assistant expert Temenos T24")
                .contains("=== LANGUE ===")
                .contains("=== RÈGLES ===")
                .contains("Ne produis JAMAIS toi-même de ligne \"**Champ :**\"")
                .contains("N'invente rien et n'ajoute AUCUNE connaissance générale absente du Contexte")
                .contains("=== FORMAT OBLIGATOIRE ===")
                .contains("**Fonction :**")
                .contains("**Exemple :**")
                .contains("**Extrait documentaire :**")
                .contains("=== VÉRIFICATION FINALE ===");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Table : AA.ARR.CHARGEOFF")
                .contains("Que fait AA.ARR.CHARGEOFF ?");
    }

    @Test
    void ask_chatHasAContextSelected_passesItToRagServiceInsteadOfTheEmptyContext() {
        // Le parcours à bulles de /chatbot fixe module/table/champ sur le Chat (voir ChatController
        // PUT /api/chats/{id}/context) : ask() doit lire ce contexte et le transmettre à RagService,
        // pas seulement l'ignorer.
        when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, "AA", "AA.ADVICE", "DESCRIPTION"));
        ChatContext expected = new ChatContext("AA", "AA.ADVICE", "DESCRIPTION");
        when(ragService.buildContext("question", expected)).thenReturn(Optional.of("contexte restreint"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        chatbotService.ask("question");

        verify(ragService).buildContext("question", expected);
    }

    @Test
    void ask_priorUserQuestionExists_isPassedToRagServiceAsThePreviousUserQuestion() {
        // "c'est un champ pas une table" (langage familier, sans mot-clé T24) doit pouvoir reprendre
        // le sujet du tour précédent : voir RagServiceImpl.analyzeWithFallback. L'historique est
        // renvoyé du plus récent au plus ancien : id 3 = la question courante déjà enregistrée par
        // recordUserMessage juste avant cet appel (voir fetchRecentHistoryExcludingCurrent, qui la retire).
        List<ChatHistoryDTO> history = List.of(
                new ChatHistoryDTO(3L, "c'est un champ pas une table", "USER", null, 42L),
                new ChatHistoryDTO(2L, "réponse précédente", "ASSISTANT", null, 42L),
                new ChatHistoryDTO(1L, "c quoi la table de DAYS.PRIOR ?", "USER", null, 42L));
        when(chatHistoryService.findByChat(eq(42L), any(Pageable.class))).thenReturn(new PageImpl<>(history));
        ChatContext expected = ChatContext.EMPTY.withPreviousUserQuestion("c quoi la table de DAYS.PRIOR ?");
        when(ragService.buildContext("c'est un champ pas une table", expected)).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        chatbotService.ask("c'est un champ pas une table");

        verify(ragService).buildContext("c'est un champ pas une table", expected);
    }

    @Test
    void ask_recentHistoryExists_isIncludedInThePromptSentToLlama3() {
        // id 3 = la question courante déjà enregistrée par recordUserMessage juste avant cet appel
        // (voir fetchRecentHistoryExcludingCurrent, qui la retire de l'historique renvoyé au prompt).
        List<ChatHistoryDTO> history = List.of(
                new ChatHistoryDTO(3L, "et le format ?", "USER", null, 42L),
                new ChatHistoryDTO(2L, "Le champ DAYS.PRIOR sert à...", "ASSISTANT", null, 42L),
                new ChatHistoryDTO(1L, "c quoi DAYS.PRIOR ?", "USER", null, 42L));
        when(chatHistoryService.findByChat(eq(42L), any(Pageable.class))).thenReturn(new PageImpl<>(history));
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        chatbotService.ask("et le format ?");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Historique récent")
                .contains("Utilisateur : c quoi DAYS.PRIOR ?")
                .contains("Assistant : Le champ DAYS.PRIOR sert à...")
                .contains("jamais de source factuelle");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        assertThat(systemCaptor.getValue()).contains("Ne confonds jamais une TABLE");
    }

    @Test
    void ask_neverTouchesOracleDirectly_onlyGoesThroughRagService() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        chatbotService.ask("question");

        // Le seul point d'entrée vers le contexte est RagService ; aucune autre dépendance
        // Oracle/recherche n'est injectée dans ChatbotServiceImpl (voir son constructeur).
        verify(ragService).buildContext("question", ChatContext.EMPTY);
    }

    @Test
    void ask_emptyOllamaResponse_returnsTheFallbackMessage_notNullOrException() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(null);

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).isEqualTo("Le modèle Ollama n'a renvoyé aucune réponse. Réessayez.");
    }

    @Test
    void ask_noRelevantContext_returnsFallbackDirectlyWithoutCallingLlama3() {
        // RAG strict (voir la Javadoc de la classe) : aucun document pertinent -> pas d'appel Ollama,
        // un seul message fixe et documenté, jamais de repli sur des connaissances générales.
        when(ragService.buildContext("question hors sujet", ChatContext.EMPTY)).thenReturn(Optional.empty());

        ChatAskResponse response = chatbotService.ask("question hors sujet");

        assertThat(response.getAnswer()).isEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK);
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_recordsTheQuestionThenTheAnswerInTheSameChat() {
        when(ragService.buildContext("question", ChatContext.EMPTY)).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        ChatAskResponse response = chatbotService.ask("question");

        verify(conversationRecorder).recordUserMessage("question");
        verify(conversationRecorder).recordAssistantMessage(42L, response.getAnswer());
    }

    @Test
    void ask_successfulAnswer_exposesUserAndAssistantMessageIdsInTheResponse() {
        // Le frontend a besoin de ces ids pour proposer modifier/supprimer/copier sur un message précis.
        when(ragService.buildContext("question", ChatContext.EMPTY)).thenReturn(Optional.of("contexte"));
        when(conversationRecorder.recordAssistantMessage(42L, "réponse")).thenReturn(200L);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getUserMessageId()).isEqualTo(100L);
        assertThat(response.getAssistantMessageId()).isEqualTo(200L);
    }

    @Test
    void ask_noRelevantContext_recordsTheFixedFallbackAsTheAssistantMessage() {
        when(ragService.buildContext("question hors sujet", ChatContext.EMPTY)).thenReturn(Optional.empty());

        chatbotService.ask("question hors sujet");

        verify(conversationRecorder).recordUserMessage("question hors sujet");
        verify(conversationRecorder).recordAssistantMessage(42L, ChatbotServiceImpl.NO_INFO_FALLBACK);
    }

    @Test
    void ask_noAuthenticatedUser_stillAnswersNormally_flagsAuthenticationRequired_andNeverRecordsTheAssistantMessage() {
        // recordUserMessage renvoie authenticationRequired() quand aucun utilisateur n'est authentifié :
        // le pipeline RAG doit continuer à répondre normalement, sans jamais tenter d'enregistrer une
        // réponse dans un chat qui n'a pas été résolu, et le frontend doit être informé via la réponse.
        when(conversationRecorder.recordUserMessage(anyString())).thenReturn(ConversationRecordingResult.authenticationRequired());
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).isEqualTo("réponse");
        assertThat(response.isAuthenticationRequired()).isTrue();
        verify(conversationRecorder, never()).recordAssistantMessage(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void ask_internalPersistenceError_stillAnswersNormally_andDoesNotFlagAuthenticationRequired() {
        // Une erreur interne (ex. Oracle indisponible) n'est PAS un problème d'authentification :
        // authenticationRequired doit rester à false pour ne pas induire l'utilisateur en erreur en
        // lui demandant de se reconnecter alors que ce n'est pas la cause.
        when(conversationRecorder.recordUserMessage(anyString()))
                .thenReturn(ConversationRecordingResult.notPersistedDueToInternalError());
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).isEqualTo("réponse");
        assertThat(response.isAuthenticationRequired()).isFalse();
        verify(conversationRecorder, never()).recordAssistantMessage(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void ask_cacheHit_skipsRagAndOllama_butStillRecordsTheAnswerInHistory() {
        when(responseCache.get("question déjà posée")).thenReturn(Optional.of("réponse en cache"));

        ChatAskResponse response = chatbotService.ask("question déjà posée");

        assertThat(response.getAnswer()).isEqualTo("réponse en cache");
        verify(ragService, never()).buildContext(anyString(), any()); // aucune recherche (isIndexLoading() seul est consulté)
        verifyNoInteractions(chatClient);
        verify(conversationRecorder).recordAssistantMessage(42L, "réponse en cache");
    }

    @Test
    void ask_cacheMiss_successfulAnswer_isStoredInCache() {
        when(ragService.buildContext("question", ChatContext.EMPTY)).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("réponse")))));

        chatbotService.ask("question");

        verify(responseCache).put("question", "réponse");
    }

    @Test
    void ask_emptyOllamaResponse_isNotCached_toAvoidServingATransientFailureIndefinitely() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(null);

        chatbotService.ask("question");

        verify(responseCache, never()).put(anyString(), anyString());
    }

    /**
     * Garde-fou déterministe contre l'hallucination : constaté en conditions réelles sur DEAL.SUB.TYP
     * (Temenos T24, produit réel) — Llama3 citait un "Extrait" qui n'existait nulle part dans le
     * Contexte envoyé, manifestement mémorisé de son entraînement, MÊME après plusieurs renforcements
     * du prompt système. Seule une vérification en code après coup peut fiablement l'empêcher.
     */
    @Test
    void ask_extraitNotFoundVerbatimInContext_rejectsTheAnswer_replacesWithFallback() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("Contexte réel ne mentionnant rien de tel."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** DEAL.SUB.TYP\n\n**Fonction :** Please specify the guarantee type\n\n"
                        + "**Source :** table MD.PARAMETER.\n\n**Extrait :**\n\"Please specify the guarantee type\"")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).isEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK);
        verify(conversationRecorder).recordAssistantMessage(42L, ChatbotServiceImpl.NO_INFO_FALLBACK);
        // Le repli est une réponse sûre et délibérée (pas un échec transitoire comme
        // EMPTY_RESPONSE_FALLBACK) : le mettre en cache est correct, évite de re-solliciter Ollama
        // pour retomber probablement sur la même hallucination à chaque répétition de la question.
        verify(responseCache).put("question", ChatbotServiceImpl.NO_INFO_FALLBACK);
    }

    @Test
    void ask_extraitFoundVerbatimInContext_isAccepted() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "DEAL.SUB.TYP\n\nDescription :\nUser defined identifier for the type of deal.\n\n"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** DEAL.SUB.TYP\n\n**Fonction :** Identifiant du type de deal.\n\n"
                        + "**Source :** table MD.PARAMETER.\n\n**Extrait :**\n\"User defined identifier for the type of deal.\"")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).contains("Identifiant du type de deal.");
        verify(responseCache).put(eq("question"), anyString());
    }

    /**
     * Bug réel corrigé (2026-09-29) : DELIVERY.REF (table MD.DEAL) était réellement documenté, la
     * Fonction générée par Llama3 était correcte, mais la citation qu'il en a faite "corrigeait" une
     * coquille de la documentation source ("identies" -> "identities") en la recopiant — un écart d'un
     * seul caractère, pas une invention. Avant le correctif, ce simple écart faisait rejeter TOUTE la
     * réponse (y compris la Fonction pourtant correcte) et la remplaçait par le repli générique,
     * contredisant l'information réellement trouvée. Le garde-fou doit maintenant reconnaître cette
     * quasi-identité (recherche par phrase, distance de Levenshtein) et remplacer l'Extrait affiché par
     * le passage RÉELLEMENT présent dans le Contexte (jamais la version « corrigée » de Llama3), sans
     * toucher au reste de la réponse.
     */
    // --- Affirmations non étayées par la source citée (catégories A/B/C) ----------------------------------------

    private static final String DE_QUESTION = "Comment définir la largeur et la profondeur d'un formulaire imprimé ?";

    /** Glossaire simulé : "largeur" -> width, "profondeur" -> depth (concepts spécifiques, voir ConceptGlossary). */
    private void stubDeliveryConcepts() {
        lenient().when(conceptGlossary.specificConceptsIn(anyString())).thenAnswer(invocation -> {
            String text = invocation.<String>getArgument(0).toLowerCase(java.util.Locale.ROOT);
            java.util.Map<String, List<String>> concepts = new java.util.LinkedHashMap<>();
            if (text.contains("largeur")) {
                concepts.put("LARGEUR", List.of("width"));
            }
            if (text.contains("profondeur")) {
                concepts.put("PROFONDEUR", List.of("depth"));
            }
            return concepts;
        });
    }

    private void stubLlamaAnswer(String answer) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
    }

    @Test
    void ask_fonctionAttributesTheQuestionsConceptToATableWhoseTextNeverMentionsIt_isNotPresentedAsAFact() {
        // Bug réel (test live DE, 2026-09-30) : rien dans DE.PRINT.MARKERS ne parle de largeur ni de profondeur ;
        // la conclusion venait du seul rapprochement entre la question et des noms de champs.
        stubDeliveryConcepts();
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : DE.PRINT.MARKERS\nProduit : DE\n\nDescription générale :\nMarkers used when printing delivery messages.\n\n"
                        + "Champs (2) :\n\nTEXT.DEP\n\nDescription :\nFields TEXT.DEP to TEXT.PAGES form a related multi value set.\n\n"
                        + "----------------------------------\n\nTEXT.VALUE\n\nDescription :\nText value printed."));
        stubLlamaAnswer("**Fonction :** La largeur et la profondeur d'un formulaire imprimé sont définies par les champs TEXT et "
                + "TEXT.VALUE de la table DE.PRINT.MARKERS.\n\n"
                + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                + "**Extrait documentaire :**\n> « Fields TEXT.DEP to TEXT.PAGES form a related multi value set. »");

        String answer = chatbotService.ask(DE_QUESTION).getAnswer();

        assertThat(answer).isEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK).doesNotContain("sont définies par");
    }

    @Test
    void ask_partiallySupportedFonction_keepsOnlyTheConfirmedPart_andSaysWhatIsNotConfirmed() {
        stubDeliveryConcepts();
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : DE.FORM.TYPE\nProduit : DE\n\nDescription générale :\nThe Form Type Table defines the width of each printed form."));
        stubLlamaAnswer("**Fonction :** La table DE.FORM.TYPE définit la largeur de chaque formulaire imprimé. "
                + "Elle définit aussi la profondeur du formulaire.\n\n"
                + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                + "**Extrait documentaire :**\n> « The Form Type Table defines the width of each printed form. »");

        String answer = chatbotService.ask(DE_QUESTION).getAnswer();

        assertThat(answer)
                .contains("La table DE.FORM.TYPE définit la largeur de chaque formulaire imprimé.")
                .doesNotContain("Elle définit aussi la profondeur")
                .contains("ne permettent pas de confirmer ce qui concerne : profondeur")
                .contains("The Form Type Table defines the width of each printed form.");
    }

    @Test
    void ask_fonctionFullySupportedBySource_isLeftUnchanged() {
        stubDeliveryConcepts();
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : DE.FORM.TYPE\nProduit : DE\n\nDescription générale :\n"
                        + "The Form Type Table defines the width and depth of each printed form."));
        stubLlamaAnswer("**Fonction :** La table DE.FORM.TYPE définit la largeur et la profondeur de chaque formulaire imprimé.\n\n"
                + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                + "**Extrait documentaire :**\n> « The Form Type Table defines the width and depth of each printed form. »");

        String answer = chatbotService.ask(DE_QUESTION).getAnswer();

        assertThat(answer)
                .contains("La table DE.FORM.TYPE définit la largeur et la profondeur de chaque formulaire imprimé.")
                .doesNotContain("ne permettent pas de confirmer");
    }

    // --- Fidélité de la génération : rien d'important du passage récupéré ne doit disparaître ----------------------

    private static final String TRANSACTION_TYPE_QUESTION = "TRANSACTION.TYPE à quoi sert ce champ ?";
    /** Contexte réel, tel que construit par RagServiceImpl.extractFieldOnly pour LC.TXN.TYPE.CONDITION. */
    private static final String TRANSACTION_TYPE_CONTEXT = DocumentedFieldFactsTest.fieldContext("LC.TXN.TYPE.CONDITION", "LC",
            "TRANSACTION.TYPE", DocumentedFieldFactsTest.TRANSACTION_TYPE)
            .replace("Overview.", "This file is used to set up the default charges for all Letter of Credit/ Documentary "
                    + "Collections and Drawing types.");
    private static final String TRANSACTION_TYPE_EXTRACT = "The key to the file, the value here will be defined as the LC.TYPE "
            + "followed by either the LC.OPERATION for Letters of Credit, or the DRAWING.TYPE for Drawings. Whenever a transaction is "
            + "input via the Letter of Credit/Documentary Collections or Drawings applications, a key is built up of the current LC.TYPE "
            + "and the current operation (For L/Cs), or the Drawing type (for drawings). This key is then used to retrieve a record from "
            + "this file in order to set up all the default charges for the current transaction.";

    @Test
    void ask_transactionType_vagueGeneration_finalAnswerStillContainsEveryDocumentedKeyFact() {
        // Cas réel signalé (2026-10-03) : bon champ, bonne table, bon extrait, mais Fonction/Exemple génériques.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(TRANSACTION_TYPE_CONTEXT));
        stubLlamaAnswer("**Fonction :** Le champ TRANSACTION.TYPE définit la clé pour récupérer les informations de défaut pour "
                + "les transactions de lettres de crédit ou de collecte documentaire.\n\n"
                + "**Exemple :** Le champ est utilisé pour définir les types de lettres de crédit et les opérations associées, ainsi "
                + "que les types de tirages pour les collectes documentaires.\n\n"
                + "**Extrait documentaire :**\n> « " + TRANSACTION_TYPE_EXTRACT + " »");

        String answer = chatbotService.ask(TRANSACTION_TYPE_QUESTION).getAnswer();

        assertThat(answer)
                .contains("**Champ :** `TRANSACTION.TYPE`")
                .contains("**Source :** `LC.TXN.TYPE.CONDITION`")
                // la clé, la récupération de l'enregistrement, les charges par défaut (passage réel conservé)
                .contains("The key to the file")
                .contains("used to retrieve a record from this file")
                .contains("set up all the default charges for the current transaction")
                // LC.TYPE + LC.OPERATION et LC.TYPE + DRAWING.TYPE, avec leurs deux formats documentés
                .contains("`LC.OPERATION`")
                .contains("`[LC.TYPE]'-'[OPERATION]`")
                .contains("`[LC.TYPE]'-'[DRAWING.TYPE]`")
                .contains("10 type A (Alphanumeric) characters");
        assertThat(answer.indexOf("**Éléments documentés :**")).isPositive().isLessThan(answer.indexOf("**Source :**"));
    }

    @Test
    void ask_transactionType_completeGeneration_isLeftAsIs_noDuplicatedFacts() {
        // Réponse réellement générée après correction du prompt (2026-10-03), règles de validation comprises.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(TRANSACTION_TYPE_CONTEXT));
        stubLlamaAnswer("**Fonction :** Le champ TRANSACTION.TYPE est la clé de recherche dans le fichier LC.TXN.TYPE.CONDITION. "
                + "Cette clé est construite à partir du type de lettre de crédit (LC.TYPE) et de l'opération (LC.OPERATION) pour les "
                + "lettres de crédit, ou du type de tirage (DRAWING.TYPE) pour les tirages. Elle sert à récupérer un enregistrement de "
                + "ce fichier pour définir les charges par défaut de la transaction en cours. Saisie obligatoire, 10 caractères "
                + "alphanumériques.\n\n"
                + "**Exemple :**\n- Lettre de crédit : `[LC.TYPE]'-'[OPERATION]`\n- Tirage : `[LC.TYPE]'-'[DRAWING.TYPE]`\n\n"
                + "**Extrait documentaire :**\n> « " + TRANSACTION_TYPE_EXTRACT + " »");

        String answer = chatbotService.ask(TRANSACTION_TYPE_QUESTION).getAnswer();

        assertThat(answer).doesNotContain("Éléments documentés").contains("`[LC.TYPE]'-'[DRAWING.TYPE]`");
    }

    @Test
    void ask_fieldQuestion_promptAsksForCompleteness_andListsTheDocumentedFactsToCover() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(TRANSACTION_TYPE_CONTEXT));
        when(chatClient.prompt()).thenReturn(requestSpec);
        org.mockito.ArgumentCaptor<String> system = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        when(requestSpec.system(system.capture())).thenReturn(requestSpec);
        when(requestSpec.user(user.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Fonction :** x.\n\n**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                        + "**Extrait documentaire :**\nAucun extrait pertinent n'est disponible dans le Contexte.")))));

        chatbotService.ask(TRANSACTION_TYPE_QUESTION);

        // Cause racine : le prompt imposait « 1 à 2 phrases courtes », « un seul exemple » et une citation « courte ».
        assertThat(system.getValue()).doesNotContain("1 à 2 phrases courtes").contains("COMPLÈTE").contains("Concision ne veut pas dire omission");
        assertThat(user.getValue())
                .contains("Points documentés dans la Description du champ TRANSACTION.TYPE")
                .contains("LC.TYPE, LC.OPERATION, DRAWING.TYPE")
                .contains("[LC.TYPE]'-'[OPERATION] ; [LC.TYPE]'-'[DRAWING.TYPE]")
                .contains("10 type A (Alphanumeric) characters");
    }

    // --- UPDATE.TYPE (EB, SEAT.APPLICATION.MANAGER) : valeurs, comportements, section Exemple conditionnelle --------

    private static final String UPDATE_TYPE_CONTEXT = DocumentedFieldFactsTest.fieldContext("SEAT.APPLICATION.MANAGER", "EB",
            "UPDATE.TYPE", DocumentedFieldFactsTest.UPDATE_TYPE);
    private static final String UPDATE_TYPE_EXTRACT_LINE = "**Extrait documentaire :**\n> « " + DocumentedFieldFactsTest.UPDATE_TYPE + " »";

    @Test
    void ask_updateType_generalQuestion_keepsTheThreeBehaviours_andShowsNoEmptyExampleSection() {
        // Réponse réellement générée le 2026-10-05 (« UPDATE.TYPE à quoi sert ? »), section Exemple « aucun exemple » comprise.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(UPDATE_TYPE_CONTEXT));
        stubLlamaAnswer("**Fonction :** Il accepte trois valeurs : 'ABSOLUTE', 'INCREMENT' ou 'LOCATE'. Chacune de ces valeurs a une "
                + "fonction spécifique : 'ABSOLUTE' récupère la valeur du champ telle qu'elle est, 'INCREMENT' met à jour la différence "
                + "entre les images avant et après, tandis que 'LOCATE' est utilisé pour les champs multivalués.\n\n"
                + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n" + UPDATE_TYPE_EXTRACT_LINE);

        String answer = chatbotService.ask("UPDATE.TYPE à quoi sert ?").getAnswer();

        assertThat(answer)
                .contains("**Champ :** `UPDATE.TYPE`")
                .contains("'ABSOLUTE' récupère la valeur du champ telle qu'elle est")
                .contains("'INCREMENT' met à jour la différence entre les images avant et après")
                .contains("'LOCATE' est utilisé pour les champs multivalués")
                .contains("**Source :** `SEAT.APPLICATION.MANAGER`")
                // extrait complet : les trois comportements y figurent
                .contains("Compares the BEFORE and AFTER image and updates the difference")
                .contains("This option is used for MULTIVALUE fields in the application")
                // pas de section Exemple vide, pas de bloc de rattrapage inutile (tout est déjà expliqué)
                .doesNotContain("Exemple")
                .doesNotContain("Éléments documentés");
    }

    @Test
    void ask_updateType_valuesQuestion_bareListAnswer_getsEveryValueWithItsDocumentedBehaviour() {
        // Réponse réellement générée le 2026-10-05 à « Quelles sont les valeurs possibles de UPDATE.TYPE ? ».
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(UPDATE_TYPE_CONTEXT));
        stubLlamaAnswer("**Fonction :** Les valeurs possibles de UPDATE.TYPE sont 'ABSOLUTE', 'INCREMENT', 'LOCATE'. Ces valeurs "
                + "définissent les règles d'actualisation du champ sélectionné.\n\n"
                + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n" + UPDATE_TYPE_EXTRACT_LINE);

        String answer = chatbotService.ask("Quelles sont les valeurs possibles de UPDATE.TYPE ?").getAnswer();

        assertThat(answer)
                .contains("**Éléments documentés :**")
                .contains("`ABSOLUTE` – Gets the Value in the Field as it is")
                .contains("`INCREMENT` – Compares the BEFORE and AFTER image and updates the difference")
                .contains("`LOCATE` – This option is used for MULTIVALUE fields in the application")
                .doesNotContain("**Exemple");
        assertThat(answer.indexOf("**Éléments documentés :**")).isLessThan(answer.indexOf("**Source :**"));
    }

    @Test
    void ask_updateType_questionOnOneValue_requiresOnlyThatValue_andAsksForAFrenchAnswer() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(UPDATE_TYPE_CONTEXT));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        when(requestSpec.user(user.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Fonction :** Avec la valeur INCREMENT, le système compare les images BEFORE et AFTER et met à jour la différence.\n\n"
                        + UPDATE_TYPE_EXTRACT_LINE)))));

        String answer = chatbotService.ask("Que fait UPDATE.TYPE = INCREMENT ?").getAnswer();

        // Bug réel (2026-10-05) : « Que fait ... ? » n'était pas reconnue comme française, réponse générée en anglais.
        assertThat(user.getValue()).contains("Réponds en français");
        assertThat(answer).contains("compare les images BEFORE et AFTER").doesNotContain("Éléments documentés").doesNotContain("Exemple");
    }

    @Test
    void ask_documentedExampleExists_butTheModelOmitsTheExampleLine_theDocumentedExampleIsStillShown() {
        // Description réelle de DEAL.SUB.TYP (MD.PARAMETER) : « As an example ... where GTREC is a Guarantee Received ».
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(DocumentedFieldFactsTest.fieldContext("MD.PARAMETER", "MD",
                "DEAL.SUB.TYP", "This is a user defined identifier for the type of MD.DEAL that is to be entered. As an example the "
                        + "following structure could be entered where GTREC is a Guarantee Received. It allows just the one CATEGORY of 28110.")));
        stubLlamaAnswer("**Fonction :** Identifiant défini par l'utilisateur pour le type de MD.DEAL à saisir.\n\n"
                + "**Extrait documentaire :**\n> « This is a user defined identifier for the type of MD.DEAL that is to be entered. »");

        String answer = chatbotService.ask("DEAL.SUB.TYP à quoi sert ce champ ?").getAnswer();

        assertThat(answer).contains("**Exemple :**").contains("GTREC").contains("Guarantee Received");
        assertThat(answer.indexOf("**Exemple :**")).isLessThan(answer.indexOf("**Source :**"));
    }

    @Test
    void ask_noDocumentedExample_andTheModelInventsNone_hasNoExampleSection() {
        // Description réelle de FORM.WIDTH (DE.FORM.TYPE) : aucun exemple documenté.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(DocumentedFieldFactsTest.fieldContext("DE.FORM.TYPE", "DE",
                "FORM.WIDTH", DocumentedFieldFactsTest.FORM_WIDTH)));
        stubLlamaAnswer("**Fonction :** Nombre de caractères sur une ligne d'impression, saisi sur 2-3 caractères numériques ; "
                + "saisie obligatoire.\n\n**Exemple :** None provided in the documentation.\n\n"
                + "**Extrait documentaire :**\n> « Specifies the number of characters across a print line. »");

        String answer = chatbotService.ask("FORM.WIDTH à quoi sert ce champ ?").getAnswer();

        assertThat(answer).doesNotContain("Exemple").doesNotContain("None provided").contains("**Source :** `DE.FORM.TYPE`");
    }

    // --- Exemples inventés : réponses réellement générées le 2026-10-05, une fois la phrase « aucun exemple » abandonnée ---

    private String askWithFieldContext(String table, String product, String field, String description, String llamaAnswer,
                                       String question) {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                DocumentedFieldFactsTest.fieldContext(table, product, field, description)));
        stubLlamaAnswer(llamaAnswer);
        return chatbotService.ask(question).getAnswer();
    }

    @Test
    void ask_inventedNumericExample_forAFieldWithoutDocumentedExample_isRemoved() {
        String answer = askWithFieldContext("SEAT.APPLICATION.MANAGER", "EB", "UPDATE.TYPE", DocumentedFieldFactsTest.UPDATE_TYPE,
                "**Fonction :** Lorsque UPDATE.TYPE vaut INCREMENT, ce champ compare l'image avant et après et met à jour la différence.\n\n"
                        + "**Exemple :** Par exemple, si le champ avant contient la valeur 10 et le champ après contient la valeur 15, le "
                        + "champ UPDATE.TYPE INCREMENT mettra à jour la valeur du champ en fonction de la différence (15 - 10 = 5).\n\n"
                        + UPDATE_TYPE_EXTRACT_LINE, "Que fait UPDATE.TYPE = INCREMENT ?");

        assertThat(answer).doesNotContain("Exemple").doesNotContain("15 - 10").contains("compare l'image avant et après");
    }

    @Test
    void ask_possibleValuesPresentedAsAnExample_areNotShownAsAnExample() {
        String answer = askWithFieldContext("SEAT.APPLICATION.MANAGER", "EB", "UPDATE.TYPE", DocumentedFieldFactsTest.UPDATE_TYPE,
                "**Fonction :** UPDATE.TYPE accepte ABSOLUTE, INCREMENT et LOCATE. ABSOLUTE récupère la valeur du champ telle quelle, "
                        + "INCREMENT compare les images avant et après et met à jour la différence, LOCATE sert aux champs multivalués.\n\n"
                        + "**Exemple :** Par exemple, si vous utilisez la valeur 'ABSOLUTE', le champ récupère la valeur actuelle.\n\n"
                        + UPDATE_TYPE_EXTRACT_LINE, "UPDATE.TYPE à quoi sert ?");

        assertThat(answer).doesNotContain("**Exemple").contains("LOCATE sert aux champs multivalués");
    }

    @Test
    void ask_inventedValues_forShortDocumentation_areRemoved() {
        String formWidth = askWithFieldContext("DE.FORM.TYPE", "DE", "FORM.WIDTH", DocumentedFieldFactsTest.FORM_WIDTH,
                "**Fonction :** Nombre de caractères sur une ligne d'impression, 2-3 caractères numériques.\n\n"
                        + "**Exemple :** Par exemple, si le formulaire a une largeur de 80 caractères, le champ FORM.WIDTH sera défini comme 80.\n\n"
                        + "**Extrait documentaire :**\n> « Specifies the number of characters across a print line. »", "FORM.WIDTH à quoi sert ce champ ?");
        assertThat(formWidth).doesNotContain("80").doesNotContain("Exemple");
    }

    @Test
    void ask_inventedCurrencyCode_isRemoved() {
        String answer = askWithFieldContext("TELLER.DENOMINATION", "TT", "CCY.DENOMINATION", DocumentedFieldFactsTest.CCY_DENOMINATION,
                "**Fonction :** Identifiant de la devise (3 premiers caractères) et de la dénomination (9 caractères suivants).\n\n"
                        + "**Exemple :** Par exemple, si le champ CCY.DENOMINATION vaut \"USD100\", la dénomination de 100 unités de devise "
                        + "américaine (USD) est définie.\n\n**Extrait documentaire :**\n> « Specifies the currency and the denomination identifier. »",
                "CCY.DENOMINATION à quoi sert ce champ ?");

        assertThat(answer).doesNotContain("USD100").doesNotContain("**Exemple");
    }

    @Test
    void ask_documentedExample_isKept() {
        // LD.SCHEDULE.DEFINE : « eg PIC » est réellement documenté.
        String answer = askWithFieldContext("LD.SCHEDULE.DEFINE", "LD", "SCH.TYPE", DocumentedFieldFactsTest.SCH_TYPE_START,
                "**Fonction :** Type de planning : un type ou une combinaison valide de types (jusqu'à 3), sauf le planning A.\n\n"
                        + "**Exemple :** `PIC` est une combinaison valide de types de planning.\n\n"
                        + "**Extrait documentaire :**\n> « The user can input a single schedule or a valid combination of schedules eg PIC except the A schedule. »",
                "SCH.TYPE à quoi sert ce champ ?");

        assertThat(answer).contains("**Exemple :** `PIC` est une combinaison valide");
    }

    @Test
    void ask_documentedExampleWithRealFigures_isKept() {
        // MD.PARAMETER / DEAL.SUB.TYP : chiffres et codes réellement présents dans la documentation.
        String description = "This is a user defined identifier for the type of MD.DEAL that is to be entered. As an example the following "
                + "structure could be entered where GTREC is a Guarantee Received. It allows just the one CATEGORY of 28110 which falls "
                + "within the overall range 28100-28199 defined for the CL type.";
        String answer = askWithFieldContext("MD.PARAMETER", "MD", "DEAL.SUB.TYP", description,
                "**Fonction :** Identifiant défini par l'utilisateur pour le type de MD.DEAL.\n\n"
                        + "**Exemple :** GTREC (Guarantee Received) n'autorise que la CATEGORY 28110, comprise dans la plage 28100-28199 du type CL.\n\n"
                        + "**Extrait documentaire :**\n> « This is a user defined identifier for the type of MD.DEAL that is to be entered. »",
                "DEAL.SUB.TYP à quoi sert ce champ ?");

        assertThat(answer).contains("GTREC (Guarantee Received) n'autorise que la CATEGORY 28110");
    }

    @Test
    void ask_documentedExampleWrittenWithFrenchElisions_isNotMistakenForAnInventedQuotedValue() {
        // Faux positif constaté en test réel (2026-10-05) : « l'opération est ... d'une » lu comme une valeur citée.
        String answer = askWithFieldContext("LC.TXN.TYPE.CONDITION", "LC", "TRANSACTION.TYPE", DocumentedFieldFactsTest.TRANSACTION_TYPE,
                "**Fonction :** Clé construite à partir de LC.TYPE et de LC.OPERATION ou de DRAWING.TYPE, utilisée pour retrouver les "
                        + "charges par défaut ; 10 caractères alphanumériques.\n\n"
                        + "**Exemple :** Pour une lettre de crédit, l'opération est ajoutée au type d'une façon documentée : "
                        + "`[LC.TYPE]'-'[OPERATION]` ; pour un tirage : `[LC.TYPE]'-'[DRAWING.TYPE]`.\n\n"
                        + "**Extrait documentaire :**\n> « " + TRANSACTION_TYPE_EXTRACT + " »", TRANSACTION_TYPE_QUESTION);

        assertThat(answer).contains("l'opération est ajoutée au type d'une façon documentée");
    }

    @Test
    void ask_documentedExampleExists_butTheModelAddsAnInventedCode_sectionIsReplacedByTheDocumentedFormats() {
        String answer = askWithFieldContext("LC.TXN.TYPE.CONDITION", "LC", "TRANSACTION.TYPE", DocumentedFieldFactsTest.TRANSACTION_TYPE,
                "**Fonction :** Clé construite à partir de LC.TYPE et de LC.OPERATION ou de DRAWING.TYPE, utilisée pour retrouver les "
                        + "charges par défaut ; 10 caractères alphanumériques.\n\n"
                        + "**Exemple :** Par exemple `IMPORT-ZZ9`.\n\n" + "**Extrait documentaire :**\n> « " + TRANSACTION_TYPE_EXTRACT + " »",
                TRANSACTION_TYPE_QUESTION);

        assertThat(answer).doesNotContain("IMPORT-ZZ9")
                .contains("**Exemple :**\n- `[LC.TYPE]'-'[OPERATION]`\n- `[LC.TYPE]'-'[DRAWING.TYPE]`");
    }

    // --- AC.EXPECTED.RECS (produit ER) demandée depuis une conversation AC : contexte -> réponse -> source -------------

    @Test
    void ask_whileTheIndexIsStillLoadingAtStartup_saysSo_neverTheNoInfoFallback_neverCached() {
        // Bug réel (test live du 2026-10-06) : « À quoi sert AC.EXPECTED.RECS ? » posée pendant le chargement de l'index
        // (~48 s) recevait « n'a pas permis de confirmer » — fausse, et elle aurait pu être servie depuis le cache ensuite.
        when(ragService.isIndexLoading()).thenReturn(true);

        String answer = chatbotService.ask("À quoi sert AC.EXPECTED.RECS ?").getAnswer();

        assertThat(answer).isEqualTo(ChatbotServiceImpl.INDEX_LOADING_MESSAGE).doesNotContain("n'a pas permis de confirmer");
        verify(ragService, never()).buildContext(anyString(), any());
        verify(responseCache, never()).put(anyString(), anyString());
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_acExpectedRecs_answerIsGroundedOnItsOverview_noFallback_correctSource_noInventedExample() {
        String context = "Table : AC.EXPECTED.RECS\nProduit : ER\n\nDescription générale :\n" + RagServiceImplTest.AC_EXPECTED_RECS_OVERVIEW
                + "\n\nChamps (62) :\n\nACCOUNT.NUMBER\n\nDescription :\nThe account number for which the funds are expected.";
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(context));
        stubLlamaAnswer("**Fonction :** AC.EXPECTED.RECS sert à enregistrer dans T24 les fonds attendus (expected receipts) annoncés à la "
                + "banque, afin de fournir des projections de solde pour des requêtes ou rapports ad hoc. Les fonds attendus peuvent être "
                + "saisis manuellement ou créés à partir de messages SWIFT MT210 entrants, puis rapprochés automatiquement ou manuellement.\n\n"
                + "**Exemple :** Par exemple, une banque saisit un encaissement attendu de 5000 EUR pour le client X.\n\n"
                + "**Extrait documentaire :**\n> « In order to do this the 'expected' receipt information needs to be recorded in T24 and used "
                + "to provide balance projection information for ad-hoc Enquiries or reports. »");

        String answer = chatbotService.ask("À quoi sert AC.EXPECTED.RECS ?").getAnswer();

        assertThat(answer)
                .isNotEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK)
                .doesNotContain("n'a pas permis de confirmer")
                .contains("projections de solde")
                .contains("**Source :** `AC.EXPECTED.RECS`")
                .contains("balance projection information")
                // aucun exemple n'est documenté dans cette description : l'exemple inventé (5000 EUR) est retiré
                .doesNotContain("5000")
                .doesNotContain("**Exemple");
    }

    @Test
    void ask_tableQuestion_exampleMarkerOnlyInAFieldDescription_isNotADocumentedExampleOfTheTable() {
        // Cas réel (test live du 2026-10-06, conversation AC) : la section Exemple — un scénario illustratif sans nombre ni
        // code — restait affichée parce que la description d'un CHAMP de la table contient « for example ». Pour une question
        // sur la table, seule sa description générale peut documenter un exemple.
        String context = "Table : AC.EXPECTED.RECS\nProduit : ER\n\nDescription générale :\n" + RagServiceImplTest.AC_EXPECTED_RECS_OVERVIEW
                + "\n\nChamps (62) :\n\nCREATION.METHOD\n\nDescription :\nThe creation Method of the record. Ones generated from "
                + "incoming SWIFT for example will be set as SWIFT.";
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(context));
        stubLlamaAnswer("**Fonction :** AC.EXPECTED.RECS permet de saisir les fonds attendus (expected receipts) afin de fournir des "
                + "projections de solde pour les enquêtes ou les rapports ad hoc.\n\n"
                + "**Exemple :** Par exemple, si un client attend un versement de fonds, le module AC.EXPECTED.RECS permet de saisir "
                + "les informations attendues sur ce versement.\n\n"
                + "**Extrait documentaire :**\n> « This module allows the user to enter the expected receipts manually; to have them "
                + "created by incoming SWIFT MT210s and to match them, where possible automatically, or manually. »");

        String answer = chatbotService.ask("À quoi sert AC.EXPECTED.RECS ?").getAnswer();

        assertThat(answer)
                .contains("projections de solde")
                .contains("**Source :** `AC.EXPECTED.RECS`")
                .doesNotContain("**Exemple")
                .doesNotContain("si un client attend un versement");
    }

    // --- Champ ambigu / champ sans aide : décidés AVANT la génération (demande du 2026-10-06) ----------------------------

    /** Contexte d'ambiguïté réel, tel que construit par RagServiceImpl pour DELIVERY.REF dans une conversation MD. */
    private static String deliveryRefAmbiguousContext() {
        return "Le champ DELIVERY.REF existe dans plusieurs tables du module MD : MD.DEAL, MD.FEE.SETTLEMENT.\n\n"
                + "Ambigu : ne réponds pas encore sur le contenu précis de ce champ pour une seule de ces tables, demande d'abord "
                + "à l'utilisateur de préciser de quelle table il s'agit parmi celles listées ci-dessus.\n\n"
                + DocumentedFieldFactsTest.fieldContext("MD.DEAL", "MD", "DELIVERY.REF", RagServiceImplTest.MD_DEAL_DELIVERY_REF)
                + "\n\n---------------------------------\n\n"
                + DocumentedFieldFactsTest.fieldContext("MD.FEE.SETTLEMENT", "MD", "DELIVERY.REF", RagServiceImplTest.MD_FEE_SETTLEMENT_DELIVERY_REF);
    }

    @Test
    void ask_deliveryRefInSeveralTables_asksWhichTable_listsTheRealTables_noSingleSource_noLlama() {
        // Avant : Llama3 répondait sur MD.DEAL seul (« identifier chaque livraison... »), sans le dire.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(deliveryRefAmbiguousContext()));

        String answer = chatbotService.ask("À quoi sert DELIVERY.REF ?").getAnswer();

        assertThat(answer).isEqualTo("**Champ :** `DELIVERY.REF`\n\n"
                + "Ce champ est documenté dans plusieurs tables :\n- `MD.DEAL`\n- `MD.FEE.SETTLEMENT`\n\n"
                + "Pouvez-vous préciser la table recherchée ?");
        assertThat(answer).doesNotContain("**Source").doesNotContain("**Fonction");
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_ambiguousFieldWhoseRealDescriptionIsIdenticalEverywhere_showsItAsCommon_stillAsksWhichTable() {
        String common = "Denotes the Ending/ closing balance of the migrated transaction Validation: Standard T24 amount field "
                + "with a length of 20 characters";
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Le champ ENDING.BALANCE existe dans plusieurs tables de la documentation : USRETL.STMT.ENTRY, USRETL.STMT.ENTRY.HIS.\n\n"
                        + RagServiceImpl.COMMON_DESCRIPTION_LABEL + common + "\n\nAmbigu : demande d'abord quelle table.\n\n"));

        String answer = chatbotService.ask("c quoi ENDING.BALANCE ?").getAnswer();

        assertThat(answer).contains("- `USRETL.STMT.ENTRY`\n- `USRETL.STMT.ENTRY.HIS`")
                .contains("Description identique dans toutes ces tables :\n> « " + common + " »")
                .endsWith("Pouvez-vous préciser la table recherchée ?");
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_maturityDateOfBillRegister_helpTextUnavailable_neverDeducesAFunctionFromTheName_noLlama() {
        // Avant : « Le champ MATURITY.DATE représente la date de maturité d'un produit... » (déduit du seul nom).
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(DocumentedFieldFactsTest.fieldContext(
                "BILL.REGISTER", "LD", "MATURITY.DATE", RagServiceImplTest.HELP_TEXT_UNAVAILABLE)));

        String answer = chatbotService.ask("c quoi MATURITY.DATE ?").getAnswer();

        assertThat(answer).isEqualTo("**Champ :** `MATURITY.DATE`\n\n"
                + "**Fonction :** La documentation HelpText disponible ne fournit pas de description pour ce champ.\n\n"
                + "**Source :** `BILL.REGISTER`\n\n"
                + "**Extrait documentaire :**\n> « " + RagServiceImplTest.HELP_TEXT_UNAVAILABLE + " »");
        assertThat(answer).doesNotContain("maturité").doesNotContain("**Exemple");
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_explicitFunctionQuestionOnAFieldWithoutHelpText_getsTheSameHonestAnswer() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(DocumentedFieldFactsTest.fieldContext(
                "BILL.REGISTER", "LD", "MATURITY.DATE", RagServiceImplTest.HELP_TEXT_UNAVAILABLE)));

        String answer = chatbotService.ask("Quelle est la fonction exacte du champ MATURITY.DATE de BILL.REGISTER ?").getAnswer();

        assertThat(answer).contains(DeterministicFieldAnswers.NO_DESCRIPTION_FUNCTION).contains("**Source :** `BILL.REGISTER`");
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_maturityDateOfMdDeal_helpTextAvailable_isAnsweredNormallyFromTheDocumentation() {
        String mdDealMaturity = "This field represents either the final maturity date of the deal or the number of days notice before "
                + "maturity. If not defined, maturity date will be calculated from ADVICE.EXPIRY.DATE + EXPIRY.DAYS defined in MD.PARAMETER.";
        String answer = askWithFieldContext("MD.DEAL", "MD", "MATURITY.DATE", mdDealMaturity,
                "**Fonction :** Ce champ représente soit la date d'échéance finale du deal, soit le nombre de jours de préavis avant "
                        + "l'échéance.\n\n**Extrait documentaire :**\n> « This field represents either the final maturity date of the deal "
                        + "or the number of days notice before maturity. »", "c quoi MATURITY.DATE de MD.DEAL ?");

        assertThat(answer).contains("date d'échéance finale du deal").contains("**Source :** `MD.DEAL`")
                .doesNotContain(DeterministicFieldAnswers.NO_DESCRIPTION_FUNCTION);
        verify(chatClient).prompt();
    }

    @Test
    void ask_helpTextUnavailableSentenceFollowedByARealDescription_isPartlyDocumented_soNotShortCircuited() {
        // Cas réel (AA.SIM.AGENT.COMMISSION / AMORT.END) : la phrase standard est tronquée puis suivie d'une vraie description.
        String amortEnd = "Help Text for this field is unavailable. Please refer to the T24 User This field indicates if commission "
                + "amortization has to be stopped when the financial activity takes place. Allowed value is Yes and is mutually exclusive "
                + "with ONLINE.CHARGE. It is defaulted from Product Commission definition";
        String answer = askWithFieldContext("AA.SIM.AGENT.COMMISSION", "AA", "AMORT.END", amortEnd,
                "**Fonction :** Indique si l'amortissement de la commission doit être arrêté lors de l'activité financière ; seule "
                        + "valeur autorisée : Yes, exclusive avec ONLINE.CHARGE.\n\n**Extrait documentaire :**\n> « This field indicates if "
                        + "commission amortization has to be stopped when the financial activity takes place. »", "c quoi AMORT.END ?");

        assertThat(answer).doesNotContain(DeterministicFieldAnswers.NO_DESCRIPTION_FUNCTION);
        verify(chatClient).prompt();
    }

    @Test
    void ask_deliveryRefOfMdFeeSettlement_extraitQuotesTheTableOverview_isReplacedByTheFieldsOwnDescription() {
        // Bug réel (test live du 2026-10-06) : bonne table, bonne source, mais Extrait = description générale de la table.
        String context = "Table : MD.FEE.SETTLEMENT\nProduit : MD\n\nDescription générale :\nOVERVIEW Table used to settle the claimed "
                + "commission as well as refund of charges and commission.\n\nChamp sélectionné : DELIVERY.REF\n\nDELIVERY.REF\n\n"
                + "Description :\n" + RagServiceImplTest.MD_FEE_SETTLEMENT_DELIVERY_REF + "\n\nType :\nNon renseigné.\n\nInput :\nNon renseigné.";
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(context));
        stubLlamaAnswer("**Fonction :** Le champ DELIVERY.REF conserve la clé de tous les avis produits par le système de livraison ; "
                + "c'est un champ maintenu par le système.\n\n**Extrait documentaire :**\n> OVERVIEW Table used to settle the claimed "
                + "commission as well as refund of charges and commission.");

        String answer = chatbotService.ask("À quoi sert DELIVERY.REF dans MD.FEE.SETTLEMENT ?").getAnswer();

        assertThat(answer).contains("**Source :** `MD.FEE.SETTLEMENT`")
                .endsWith("**Extrait documentaire :**\n> « " + RagServiceImplTest.MD_FEE_SETTLEMENT_DELIVERY_REF + " »")
                .doesNotContain("OVERVIEW Table used");
    }

    @Test
    void ask_fieldWithoutAnyDescription_saysSo_withoutQuotingAnything() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(DocumentedFieldFactsTest.fieldContext(
                "BILL.REGISTER", "LD", "MATURITY.DATE", "Non renseignée.")));

        String answer = chatbotService.ask("c quoi MATURITY.DATE ?").getAnswer();

        assertThat(answer).contains(DeterministicFieldAnswers.NO_DESCRIPTION_FUNCTION)
                .contains("**Extrait documentaire :** aucun texte d'aide n'est renseigné pour ce champ.");
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_ambiguousOrNonFieldContext_getsNoDocumentedFactsBlock() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : LC.TXN.TYPE.CONDITION\nProduit : LC\n\nDescription générale :\n"
                        + "This file is used to set up the default charges for all Letter of Credit/ Documentary Collections and Drawing types."));
        stubLlamaAnswer("**Fonction :** Cette table sert à définir les charges par défaut des lettres de crédit.\n\n"
                + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                + "**Extrait documentaire :**\n> « This file is used to set up the default charges for all Letter of Credit/ "
                + "Documentary Collections and Drawing types. »");

        String answer = chatbotService.ask("À quoi sert LC.TXN.TYPE.CONDITION ?").getAnswer();

        assertThat(answer).doesNotContain("Éléments documentés");
    }

    @Test
    void ask_questionAboutAnIdentifierAbsentFromTheWholeContext_givesTheHonestFallback_neverANonExistenceClaim() {
        // Bug réel (test live LD/LC, 2026-10-02) : « Il n'y a pas de champ avec ce nom dans les tables ... ».
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : BILL.REGISTER\nProduit : LD\n\nDescription générale :\nBills register."));
        stubLlamaAnswer("**Fonction :** Il n'y a pas de champ avec ce nom dans la table BILL.REGISTER.\n\n"
                + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                + "**Extrait documentaire :**\n> « Bills register. »");

        String answer = chatbotService.ask("À quoi sert le champ CHAMP.FICTIF.XYZ ?").getAnswer();

        assertThat(answer).isEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK);
    }

    @Test
    void ask_fonctionCitingAnIdentifierAbsentFromTheContext_dropsThatClaim() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : MD.BALANCES\nProduit : MD\n\nDescription générale :\n"
                        + "The MD.BALANCES file records all the principal movements of a particular MD.DEAL."));
        stubLlamaAnswer("**Fonction :** Les mouvements de principal sont enregistrés dans MD.BALANCES. "
                + "Ils sont aussi copiés dans MD.PRINCIPAL.LOG.\n\n"
                + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                + "**Extrait documentaire :**\n> « The MD.BALANCES file records all the principal movements of a particular MD.DEAL. »");

        String answer = chatbotService.ask("Où sont enregistrés les mouvements de principal ?").getAnswer();

        assertThat(answer)
                .contains("Les mouvements de principal sont enregistrés dans MD.BALANCES.")
                .doesNotContain("copiés dans MD.PRINCIPAL.LOG")
                .contains("ne permettent pas de confirmer ce qui concerne : MD.PRINCIPAL.LOG");
    }

    @Test
    void ask_extraitIsAVerbatimPrefixOfARunOnSentenceEndedWithAPeriod_isAccepted_notRejectedAsFabricated() {
        // Bug réel (test live LD, 2026-09-30) : la documentation LD.SCHEDULE.DEFINE poursuit sans point
        // ("...Diary entries Updates to this file..."), Llama3 cite mot pour mot jusqu'à "Diary entries." —
        // une citation exacte, rejetée à tort à cause du seul point final ajouté.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : LD.SCHEDULE.DEFINE\nProduit : LD\n\nDescription générale :\n"
                        + "This facility enables the user to specify scheduled events which are to occur during the life of "
                        + "the contract. Such events can include the definition and processing of some or all of the "
                        + "following components: Principal Interest Commission Fees Margin revisions Rate revisions Diary "
                        + "entries Updates to this file are effected solely by the user and the only functions users are "
                        + "allowed to perform are (L)IST, (E)XCEPTION, (S)EE."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Fonction :** La table LD.SCHEDULE.DEFINE permet de définir les événements planifiés du contrat.\n\n"
                        + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                        + "**Extrait documentaire :**\n> « This facility enables the user to specify scheduled events which are "
                        + "to occur during the life of the contract. Such events can include the definition and processing of "
                        + "some or all of the following components: Principal Interest Commission Fees Margin revisions Rate "
                        + "revisions Diary entries. »")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .isNotEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK)
                .contains("LD.SCHEDULE.DEFINE permet de définir les événements planifiés");
    }

    @Test
    void ask_severalDocumentsInContext_sourceIsTheTableWhoseBlockContainsTheQuotedExtrait_notTheFirstOne() {
        // Bug réel (test live LC, 2026-09-30) : extrait réel de DR.INT.CALC.COND affiché sous la Source
        // LC.ACCOUNT.BALANCES, simplement parce que celle-ci était le premier document du Contexte.
        String separator = "\n\n---------------------------------\n\n";
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : LC.ACCOUNT.BALANCES\nProduit : LC\n\nDescription générale :\n"
                        + "LC.ACCOUNT.BALANCES This file contains all the authorised charges under a single Letter of Credit transaction."
                        + separator
                        + "Table : DR.INT.CALC.COND\nProduit : LC\n\nDescription générale :\n"
                        + "In a Discounted Drawing, the method of calculation of Discount component can now be specified using this table."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Fonction :** La remise est calculée selon la méthode définie dans DR.INT.CALC.COND.\n\n"
                        + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                        + "**Extrait documentaire :**\n> « In a Discounted Drawing, the method of calculation of Discount "
                        + "component can now be specified using this table. »")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .contains("**Source :** `DR.INT.CALC.COND`")
                .doesNotContain("**Source :** `LC.ACCOUNT.BALANCES`");
    }

    @Test
    void ask_extraitDiffersFromContextByOneCharacter_isAcceptedAndReplacedWithTheRealText_neverRejectsTheWholeAnswer() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : MD.DEAL\nProduit : MD\n\nChamp sélectionné : DELIVERY.REF\n\nDELIVERY.REF\n\nDescription :\n"
                        + "This field identifies each separate delivery produced by T24 in connection with the miscellaneous "
                        + "deal. This field will hold all the identies of any advices produced by the delivery system in "
                        + "connection with the deal."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Fonction :** Identifie chaque livraison produite par T24 en lien avec le deal MD. Il conserve les "
                        + "identifiants des avis générés par le système de livraison pour ce deal.\n\n"
                        + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                        + "**Extrait documentaire :**\n> « This field identifies each separate delivery produced by T24 in "
                        + "connection with the miscellaneous deal. This field will hold all the identities of any advices "
                        + "produced by the delivery system in connection with the deal. »")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .isNotEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK)
                .doesNotContain("n'a pas permis de confirmer")
                // Champ/Source injectés depuis le Contexte, Fonction/Exemple de Llama3 conservés tels quels.
                .contains("**Champ :** `DELIVERY.REF`")
                .contains("Identifie chaque livraison produite par T24 en lien avec le deal MD.")
                .doesNotContain("Aucun exemple explicite") // section Exemple conditionnelle (2026-10-05)
                .contains("**Source :** `MD.DEAL`")
                // L'Extrait affiché est le texte RÉEL du Contexte (avec la coquille d'origine "identies"),
                // jamais la version « corrigée » par Llama3.
                .contains("identies of any advices")
                .doesNotContain("identities of any advices");
    }

    /**
     * Bug réel corrigé (2026-09-29) : question conceptuelle sur les codes BIC (module DE, table DE.BIC).
     * Le Contexte contenait bien un paragraphe réel de quatre phrases sur la gestion des codes BIC, et
     * Llama3 a correctement identifié le sujet — mais sa citation a sauté la 2e phrase, recopiant sans
     * erreur la 1ère et les deux dernières bout à bout. Aucun mot cité n'est inventé (chacun est réellement
     * présent, dans le bon ordre, dans le Contexte), mais l'écart (une phrase entière manquante) dépasse le
     * seuil de similarité Levenshtein phrase par phrase / paire de phrases consécutives de {@link
     * ChatbotServiceImpl#findCloseMatchInContext} : avant le correctif, toute la réponse (Fonction pourtant
     * correcte incluse) était rejetée et remplacée par le repli générique. Le garde-fou doit désormais
     * reconnaître qu'une citation est une sous-séquence exacte (mots dans le bon ordre) d'un paragraphe du
     * Contexte et substituer ce paragraphe RÉEL complet à la version tronquée de Llama3.
     */
    @Test
    void ask_extraitOmitsAnInternalSentenceFromALongerRealParagraph_isAcceptedAndReplacedWithTheFullParagraph() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : DE.BIC\nProduit : DE\n\nThis application contains BIC data updated from SWIFT. BIC database "
                        + "is a SWIFT directory that lists institution identifiers recognised by the financial industry "
                        + "(for example, BICs, CHIPS UIDs, and national clearing codes). BIC Database Plus is expanded to "
                        + "include SEPA identifiers, such as national bank identifiers used in IBANs, IBAN formats, and "
                        + "BICs issued with IBANs. The expanded database evolves into a new directory called BICPlusIBAN "
                        + "Directory."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Fonction :** Le module DE gère les codes BIC des banques en stockant leurs informations dans la "
                        + "table DE.BIC.\n\n**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                        + "**Extrait documentaire :**\n> « This application contains BIC data updated from SWIFT. BIC "
                        + "Database Plus is expanded to include SEPA identifiers, such as national bank identifiers used "
                        + "in IBANs, IBAN formats, and BICs issued with IBANs. The expanded database evolves into a new "
                        + "directory called BICPlusIBAN Directory. »")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .isNotEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK)
                .doesNotContain("n'a pas permis de confirmer")
                .contains("Le module DE gère les codes BIC des banques")
                // L'Extrait affiché est le paragraphe RÉEL complet du Contexte (avec la phrase omise par
                // Llama3 réintégrée), jamais la version tronquée qu'il a citée.
                .contains("BIC database is a SWIFT directory that lists institution identifiers recognised by the "
                        + "financial industry");
    }

    /**
     * Garde-fou de non-régression du correctif ci-dessus : une citation qui évoque un thème proche mais
     * ajoute un fait précis absent du Contexte (ici un montant inventé) doit rester en dessous du seuil
     * de similarité et continuer à être traitée comme une invention, pas comme une coquille.
     */
    @Test
    void ask_extraitIsSubstantiallyDifferentFromContext_stillRejectsTheAnswer_notJustATypo() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "DEAL.SUB.TYP\n\nDescription :\nUser defined identifier for the type of deal."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Fonction :** Identifiant du type de deal.\n\n**Exemple :** Aucun exemple explicite n'est fourni "
                        + "dans la documentation.\n\n**Extrait documentaire :**\n> « The maximum value allowed for this "
                        + "field is 999999 as defined in the system parameters table. »")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).isEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK);
    }

    @Test
    void ask_answerWithoutAnyExtrait_isNeverRejected() {
        // Réponse table/module (pas de citation "Extrait") : rien à vérifier, ne doit jamais être
        // rejetée par le garde-fou anti-hallucination.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("Ce module gère les prêts et dépôts.")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).isEqualTo("Ce module gère les prêts et dépôts.");
    }

    @Test
    void ask_extraitWithGuillemets_matchingContext_isAccepted() {
        // Format générique de traçabilité (table/module), pas le format Champ/Fonction/Exemple :
        // guillemets français « » plutôt que des guillemets droits.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "Table : AA.ARR.CHARGEOFF\nGère les créances irrécouvrables."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "Cette table gère les créances irrécouvrables.\n\n"
                        + "Source : documentation T24 — table AA.ARR.CHARGEOFF.\n"
                        + "Extrait : « Gère les créances irrécouvrables. »")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).contains("Cette table gère les créances irrécouvrables.");
    }

    /**
     * Reformulation multilingue : la Fonction/l'Exemple sont rédigés dans la langue de la question,
     * mais l'Extrait reste tel quel dans sa langue d'origine
     * (ici l'anglais du Contexte HELP_FIELD réel). Le garde-fou anti-hallucination ne compare que
     * l'Extrait au Contexte : il ne doit jamais rejeter une réponse à cause d'une Fonction traduite
     * dans une autre langue que l'Extrait — sinon toute réponse française sur une documentation
     * source anglaise serait rejetée à tort.
     */
    @Test
    void ask_frenchQuestion_frenchFunctionWithOriginalEnglishExtrait_isAcceptedByTheGuard() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "DEAL.SUB.TYP\n\nDescription :\nThis is a user defined identifier for the type of "
                        + "MD.DEAL that is to be entered."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** DEAL.SUB.TYP\n\n**Fonction :**\nCe champ est un identifiant défini par "
                        + "l'utilisateur qui permet de préciser le type de MD.DEAL à saisir.\n\n"
                        + "**Source :** MD.PARAMETER\n\n**Extrait :**\n"
                        + "\"This is a user defined identifier for the type of MD.DEAL that is to be entered.\"")))));

        ChatAskResponse response = chatbotService.ask("DEAL.SUB.TYP à quoi sert ?");

        assertThat(response.getAnswer())
                .contains("identifiant défini par l'utilisateur")
                .contains("This is a user defined identifier for the type of MD.DEAL that is to be entered.")
                .isNotEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK);
    }

    @Test
    void ask_englishQuestion_englishFunctionAndExampleWithOriginalExtrait_isAcceptedByTheGuard() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "DEAL.SUB.TYP\n\nDescription :\nThis is a user defined identifier for the type of "
                        + "MD.DEAL that is to be entered. As an example, GTREC is a Guarantee Received."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** DEAL.SUB.TYP\n\n**Fonction :**\nThis field is a user-defined identifier "
                        + "for the type of MD.DEAL to be entered.\n\n**Exemple :**\nGTREC stands for "
                        + "Guarantee Received.\n\n**Source :** MD.PARAMETER\n\n**Extrait :**\n"
                        + "\"This is a user defined identifier for the type of MD.DEAL that is to be entered.\"")))));

        ChatAskResponse response = chatbotService.ask("What is DEAL.SUB.TYP used for?");

        assertThat(response.getAnswer())
                .contains("GTREC stands for Guarantee Received")
                .isNotEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK);
    }

    @Test
    void ask_exampleExplicitlyPresentInContext_isNeverReplacedWithNonFourni() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "DEAL.SUB.TYP\n\nDescription :\nAs an example, GTREC is a Guarantee Received."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** DEAL.SUB.TYP\n\n**Fonction :**\nIdentifiant du type de deal.\n\n"
                        + "**Exemple :**\nGTREC correspond à \"Guarantee Received\".\n\n"
                        + "**Source :** MD.PARAMETER\n\n**Extrait :**\n"
                        + "\"As an example, GTREC is a Guarantee Received.\"")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .contains("GTREC correspond à")
                .doesNotContain("Non fourni");
    }

    @Test
    void ask_noExampleInContext_showsNoExampleSectionAtAll() {
        // Renommé et attente inversée le 2026-10-05 (demande explicite) : sans exemple documenté, la section Exemple
        // est absente — plus de phrase « Aucun exemple explicite... ». Ni exemple inventé, ni section vide.
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "DEAL.SUB.TYP\n\nDescription :\nUser defined identifier for the type of deal."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** DEAL.SUB.TYP\n\n**Fonction :**\nIdentifiant du type de deal.\n\n"
                        + "**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                        + "**Source :** MD.PARAMETER\n\n**Extrait :**\n"
                        + "\"User defined identifier for the type of deal.\"")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .doesNotContain("Aucun exemple explicite")
                .doesNotContain("**Exemple")
                .contains("Identifiant du type de deal.")
                .doesNotContain("Non fourni");
    }

    /**
     * Garde-fou {@code recoverMissedExample} (symétrique à {@code rejectIfExtraitIsFabricated}) :
     * constaté en conditions réelles sur DEAL.SUB.TYP — Llama3 citait déjà, dans son propre Extrait,
     * la phrase "As an example ... GTREC is a Guarantee Received." présente dans le Contexte, mais
     * affirmait pourtant dans la section Exemple de la MÊME réponse qu'aucun exemple n'existait. La
     * correction déterministe doit remplacer cette affirmation erronée par la phrase repérée dans le
     * Contexte (jamais inventée), avec GTREC et "Guarantee Received" exacts (jamais GTRC).
     */
    @Test
    void ask_answerWronglyClaimsNoExampleButContextContainsOne_isCorrectedWithTheRealExample() {
        String context = "DEAL.SUB.TYP\n\nDescription :\nThis is a user defined identifier for the type of "
                + "MD.DEAL that is to be entered. Given that the name of the application is Miscellaneous "
                + "Deals it can be envisaged that the types of use this application will see will vary "
                + "according to the individual banks that use it. As an example the following structure "
                + "could be entered where GTREC is a Guarantee Received. It allows just the one CATEGORY "
                + "of 28110 which falls within the overall range 28100-28199 defined for the CL type.";
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(context));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** `DEAL.SUB.TYP`\n\n**Fonction**\n\nCe champ est un identifiant défini par "
                        + "l'utilisateur qui permet de préciser le type de MD.DEAL à saisir.\n\n**Exemple**\n\n"
                        + "Aucun exemple explicite n'est fourni dans la documentation.\n\n**Source**\n\n"
                        + "`MD.PARAMETER`\n\n**Extrait documentaire**\n\n> \"This is a user defined identifier "
                        + "for the type of MD.DEAL that is to be entered.\"")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .doesNotContain(ChatbotServiceImpl.NO_INFO_FALLBACK)
                .doesNotContain("Aucun exemple explicite n'est fourni dans la documentation.")
                // Reformulation courte, pas la phrase d'introduction recopiée telle quelle.
                .contains("`GTREC` correspond à **Guarantee Received**.")
                .doesNotContain("As an example the following structure")
                .doesNotContain("GTRC ")
                // La Fonction n'est jamais remplacée par l'exemple : elle reste intacte.
                .contains("Ce champ est un identifiant défini par l'utilisateur qui permet de préciser");
    }

    /**
     * Deuxième cas corrigé par {@code recoverMissedExample} : Llama3 détecte bien qu'un exemple
     * existe (il ne prétend pas son absence) mais se contente de recopier la phrase d'introduction
     * ("As an example ... could be entered where GTREC is a Guarantee Received.") au lieu de la
     * reformuler en une explication courte. La correction déterministe doit produire exactement
     * "`GTREC` correspond à **Guarantee Received**." à partir de la structure "where CODE is
     * SIGNIFICATION" repérée dans le Contexte — jamais inventée, GTREC jamais altéré en GTRC.
     */
    @Test
    void ask_exampleSectionJustCopiesTheIntroSentence_isReformulatedIntoAShortExplanation() {
        String context = "DEAL.SUB.TYP\n\nDescription :\nThis is a user defined identifier for the type of "
                + "MD.DEAL that is to be entered. As an example the following structure could be entered "
                + "where GTREC is a Guarantee Received. It allows just the one CATEGORY of 28110.";
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(context));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** `DEAL.SUB.TYP`\n\n**Fonction**\n\nCe champ est un identifiant défini par "
                        + "l'utilisateur qui permet de préciser le type de MD.DEAL à saisir.\n\n**Exemple**\n\n"
                        + "As an example the following structure could be entered where GTREC is a "
                        + "Guarantee Received.\n\n**Source**\n\n`MD.PARAMETER`\n\n**Extrait documentaire**\n\n"
                        + "> \"This is a user defined identifier for the type of MD.DEAL that is to be "
                        + "entered.\"")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .contains("`GTREC` correspond à **Guarantee Received**.")
                .doesNotContain("As an example the following structure")
                .doesNotContain("GTRC ")
                .contains("Ce champ est un identifiant défini par l'utilisateur qui permet de préciser");
    }

    /** Question anglaise : la reformulation utilise "corresponds to", jamais "correspond à" (la
     * section Exemple reste dans la langue de la question, comme la Fonction). */
    @Test
    void ask_exampleSectionInEnglish_isReformulatedInEnglish() {
        String context = "DEAL.SUB.TYP\n\nDescription :\nThis is a user defined identifier for the type of "
                + "MD.DEAL that is to be entered. As an example the following structure could be entered "
                + "where GTREC is a Guarantee Received.";
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(context));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** `DEAL.SUB.TYP`\n\n**Function**\n\nThis field is a user-defined identifier for "
                        + "the type of MD.DEAL.\n\n**Example**\n\nAs an example the following structure could "
                        + "be entered where GTREC is a Guarantee Received.\n\n**Source**\n\n`MD.PARAMETER`\n\n"
                        + "**Extrait documentaire**\n\n> \"This is a user defined identifier for the type of "
                        + "MD.DEAL that is to be entered.\"")))));

        ChatAskResponse response = chatbotService.ask("What is DEAL.SUB.TYP used for?");

        assertThat(response.getAnswer())
                .contains("`GTREC` corresponds to **Guarantee Received**.")
                .doesNotContain("correspond à")
                .doesNotContain("As an example the following structure");
    }

    /** Symétrique du test précédent : quand le Contexte ne contient réellement aucun marqueur
     * d'exemple, la phrase de repli n'est jamais altérée par {@code recoverMissedExample}. */
    @Test
    void ask_answerCorrectlyClaimsNoExample_andContextHasNoExampleMarker_isNeverAltered() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(
                "DEAL.SUB.TYP\n\nDescription :\nUser defined identifier for the type of deal."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Champ :** `DEAL.SUB.TYP`\n\n**Fonction**\n\nIdentifiant du type de deal.\n\n**Exemple**\n\n"
                        + "Aucun exemple explicite n'est fourni dans la documentation.\n\n**Source**\n\n"
                        + "`MD.PARAMETER`\n\n**Extrait documentaire**\n\n> \"User defined identifier for the "
                        + "type of deal.\"")))));

        ChatAskResponse response = chatbotService.ask("question");

        // Attente mise à jour le 2026-10-05 : section Exemple CONDITIONNELLE (demande explicite) — une section qui annonce
        // seulement l'absence d'exemple n'est plus affichée.
        assertThat(response.getAnswer())
                .doesNotContain("Aucun exemple explicite")
                .doesNotContain("**Exemple**")
                .contains("Identifiant du type de deal.")
                .doesNotContain(ChatbotServiceImpl.NO_INFO_FALLBACK);
    }

    /**
     * Garde-fou {@code stripInternalMetaCommentary} : supprime toute phrase méta ("La réponse est
     * basée sur la description du champ X dans le Contexte.") que l'utilisateur final n'a pas à voir
     * — {@link ChatbotServiceImpl#SYSTEM_PROMPT} ne la demande jamais, mais rien n'empêche Llama3 de
     * l'ajouter spontanément. Le reste de la réponse (Champ/Fonction/Exemple/Source/Extrait
     * documentaire) doit rester parfaitement intact.
     */
    /** Contexte réaliste tel que produit par {@code RagServiceImpl.extractFieldOnly} pour un champ
     * résolu sans ambiguïté : "Table :" et "Champ sélectionné :" sont la SEULE source des lignes
     * "**Champ :**"/"**Source :**" injectées par {@code injectIdentityHeaders} — jamais Llama3. */
    private static final String DEAL_SUB_TYP_CONTEXT = "Table : MD.PARAMETER\nProduit : MD\n\n"
            + "Champ sélectionné : DEAL.SUB.TYP\n\nDEAL.SUB.TYP\n\nDescription :\nUser defined identifier for the type of deal.";

    @Test
    void ask_answerContainsAStrayMetaSentenceAboutTheContext_isStrippedFromTheFinalAnswer() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(DEAL_SUB_TYP_CONTEXT));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "**Fonction**\n\nIdentifiant du type de deal. La réponse est "
                        + "basée sur la description du champ DEAL.SUB.TYP dans le Contexte.\n\n**Exemple**\n\n"
                        + "Aucun exemple explicite n'est fourni dans la documentation.\n\n"
                        + "**Extrait documentaire**\n\n> \"User defined identifier for the "
                        + "type of deal.\"\n\nCette information provient du système RAG interne.")))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer())
                .doesNotContain("Contexte")
                .doesNotContain("RAG")
                .doesNotContain("système interne")
                // Le reste de la réponse (Champ/Fonction/Exemple/Source/Extrait) reste intact ;
                // Champ/Source sont injectés depuis le Contexte, jamais laissés à Llama3.
                .contains("**Champ :** `DEAL.SUB.TYP`")
                .contains("Identifiant du type de deal.")
                .doesNotContain("Aucun exemple explicite") // section Exemple conditionnelle (2026-10-05)
                .contains("**Source :** `MD.PARAMETER`")
                .contains("\"User defined identifier for the type of deal.\"");
    }

    /** Aucune phrase méta présente : le corps Fonction/Exemple/Extrait documentaire de Llama3 ne doit
     * jamais être altéré (seules les lignes Champ/Source, injectées depuis le Contexte, s'ajoutent). */
    @Test
    void ask_answerHasNoMetaCommentary_isNeverAltered() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of(DEAL_SUB_TYP_CONTEXT));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        // Format actuel du prompt (2026-10-05) : sans exemple documenté, pas de section Exemple du tout.
        String llamaAnswer = "**Fonction**\n\nIdentifiant du type de deal.\n\n"
                + "**Extrait documentaire**\n\n> \"User defined identifier for the type of deal.\"";
        String cleanAnswer = "**Champ :** `DEAL.SUB.TYP`\n\n" + llamaAnswer.substring(0, llamaAnswer.indexOf("**Extrait documentaire**"))
                + "**Source :** `MD.PARAMETER`\n\n" + llamaAnswer.substring(llamaAnswer.indexOf("**Extrait documentaire**"));
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(llamaAnswer)))));

        ChatAskResponse response = chatbotService.ask("question");

        assertThat(response.getAnswer()).isEqualTo(cleanAnswer);
    }

    @Test
    void ask_systemPromptForbidsFillerOpenersAndRequiresTheFonctionExempleExtraitFormat() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("contexte"));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("réponse")))));

        chatbotService.ask("question");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        assertThat(systemCaptor.getValue())
                .contains("jamais d'introduction ni de")
                .contains("\"Selon la documentation...\"")
                .contains("**Fonction :**")
                .contains("**Exemple :**")
                .contains("**Extrait documentaire :**");
    }

    // ---------------------------------------------------------------------------------------------
    // Recherche inversée de champ, doublons d'envoi, clé de cache contextuelle
    // ---------------------------------------------------------------------------------------------

    @Test
    void ask_reverseFieldSearch_isAnsweredDeterministically_withoutRagNorLlama3_andIsNotCached() {
        when(fieldSearchService.tryAnswer(eq("quel champ contient le code SWIFT dans TT ?"), any()))
                .thenReturn(Optional.of("**Réponse**\n\nRésultat construit par balayage complet."));

        ChatAskResponse response = chatbotService.ask("quel champ contient le code SWIFT dans TT ?");

        assertThat(response.getAnswer()).contains("balayage complet");
        verify(ragService, never()).buildContext(anyString(), any()); // aucune recherche (isIndexLoading() seul est consulté)
        verifyNoInteractions(chatClient);
        verify(conversationRecorder).recordAssistantMessage(eq(42L), anyString());
        verify(responseCache, never()).put(anyString(), anyString());
    }

    @Test
    void ask_sameQuestionSentTwiceWhileRunning_isProcessedOnce_andRecordedOnce() throws Exception {
        CountDownLatch insideRag = new CountDownLatch(1);
        CountDownLatch releaseRag = new CountDownLatch(1);
        when(ragService.buildContext(anyString(), any())).thenAnswer(invocation -> {
            insideRag.countDown();
            releaseRag.await(10, TimeUnit.SECONDS);
            return Optional.empty();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ChatAskResponse> first = pool.submit(() -> chatbotService.ask("question"));
            assertThat(insideRag.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ChatAskResponse> duplicate = pool.submit(() -> chatbotService.ask("  Question  "));
            Thread.sleep(300); // laisse la requête en double atteindre l'attente de la première
            releaseRag.countDown();

            ChatAskResponse a = first.get(10, TimeUnit.SECONDS);
            ChatAskResponse b = duplicate.get(10, TimeUnit.SECONDS);

            assertThat(b.getAnswer()).isEqualTo(a.getAnswer());
            verify(conversationRecorder, times(1)).recordUserMessage(anyString());
            verify(ragService, times(1)).buildContext(anyString(), any());
            verify(conversationRecorder, times(1)).recordAssistantMessage(eq(42L), anyString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ask_sameQuestionAskedAgainAfterTheFirstFinished_isProcessedAgain() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.empty());

        chatbotService.ask("question");
        chatbotService.ask("question");

        verify(ragService, times(2)).buildContext(anyString(), any());
    }

    @Test
    void ask_withClientChatId_recordsTheQuestionInThatConversation() {
        when(conversationRecorder.recordUserMessage("question", 7L)).thenReturn(ConversationRecordingResult.persisted(7L, 500L));
        lenient().when(chatService.findById(7L)).thenReturn(new ChatDTO(7L, "autre", null, null, 1L, 0L, null, null, null));
        lenient().when(chatHistoryService.findByChat(eq(7L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.empty());

        chatbotService.ask("question", 7L);

        verify(conversationRecorder).recordUserMessage("question", 7L);
        verify(conversationRecorder, never()).recordUserMessage("question");
        verify(conversationRecorder).recordAssistantMessage(eq(7L), anyString());
    }

    @Test
    void ask_cacheKeyIncludesTheModuleContext_soAnotherModuleNeverGetsTheSameCachedAnswer() {
        lenient().when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, "TT", null, null));
        when(responseCache.get("quels sont les champs ? [contexte:TT/null/null]")).thenReturn(Optional.of("réponse TT en cache"));

        ChatAskResponse response = chatbotService.ask("quels sont les champs ?");

        assertThat(response.getAnswer()).isEqualTo("réponse TT en cache");
        verify(responseCache).get("quels sont les champs ? [contexte:TT/null/null]");
        verify(responseCache, never()).get("quels sont les champs ?");
        verify(ragService, never()).buildContext(anyString(), any()); // aucune recherche (isIndexLoading() seul est consulté)
    }

    @Test
    void ask_followUpQuestion_isCachedUnderAKeyIncludingThePreviousQuestion_neverServedToAFreshConversation() {
        // Bug réel (test live AC, 2026-09-30) : une question sans mot-clé T24 posée après une question sur
        // INT.ACC.TYPE est traitée comme sa suite ; sa réponse ne doit pas être resservie, depuis le cache,
        // à une nouvelle conversation où la même phrase n'a aucun historique.
        lenient().when(chatService.findById(42L)).thenReturn(
                new ChatDTO(42L, "conversation", null, null, 1L, 0L, "AC", null, null));
        List<ChatHistoryDTO> history = List.of(
                new ChatHistoryDTO(3L, "Comment crée-t-on des sous-comptes ?", "USER", null, 42L),
                new ChatHistoryDTO(2L, "réponse précédente", "ASSISTANT", null, 42L),
                new ChatHistoryDTO(1L, "À quoi sert le champ INT.ACC.TYPE ?", "USER", null, 42L));
        when(chatHistoryService.findByChat(eq(42L), any(Pageable.class))).thenReturn(new PageImpl<>(history));
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.empty());

        chatbotService.ask("Comment crée-t-on des sous-comptes ?");

        verify(responseCache).get("Comment crée-t-on des sous-comptes ? [contexte:AC/null/null] [suite de:À quoi sert le champ INT.ACC.TYPE ?]");
        verify(responseCache, never()).get("Comment crée-t-on des sous-comptes ? [contexte:AC/null/null]");
    }

    @Test
    void ask_noDocumentFound_answersThatTheSearchCouldNotConfirm_neverThatItDoesNotExist() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.empty());

        String answer = chatbotService.ask("question").getAnswer();

        assertThat(answer).contains("n'a pas permis de confirmer").contains("ne prouve pas");
        assertThat(answer).doesNotContain("n'existe pas dans").doesNotContain("Aucun champ");
        verifyNoInteractions(chatClient);
    }

    @Test
    void ask_modelWrapsTheFallbackSentence_onlyTheFallbackSentenceIsReturned() {
        when(ragService.buildContext(anyString(), any())).thenReturn(Optional.of("Table : X\n\nContenu."));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "La réponse est : \"" + ChatbotServiceImpl.NO_INFO_FALLBACK + "\"")))));

        assertThat(chatbotService.ask("question").getAnswer()).isEqualTo(ChatbotServiceImpl.NO_INFO_FALLBACK);
    }

}
