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
import backend.service.ConceptGlossary;
import backend.service.ChatResponseCache;
import backend.service.ChatService;
import backend.service.ChatbotService;
import backend.service.ConversationRecorder;
import backend.service.FieldSearchService;
import backend.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import backend.perf.Perf;
import backend.perf.Perf.Category;
import backend.perf.PerfMarks;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pipeline RAG : Question -&gt; {@link RagService} (embedding + recherche sémantique Top-K dans
 * l'index vectoriel HELP_TEXT/HELP_FIELD) -&gt; contexte -&gt; prompt -&gt; Llama3 (Ollama) -&gt; réponse.
 *
 * <p><strong>RAG strict, réponses courtes et précises.</strong> Quand {@link RagService#buildContext}
 * ne trouve aucun document, Llama3 n'est même pas appelé : {@link #NO_INFO_FALLBACK} est renvoyé
 * directement. {@link #SYSTEM_PROMPT} impose un format fixe Champ/Fonction/Exemple/Source/Extrait
 * documentaire et interdit toute connaissance générale absente du Contexte. Les lignes "Champ"/
 * "Source" ne sont JAMAIS écrites par Llama3 (le prompt le lui interdit explicitement) : elles sont
 * injectées en code par {@link #injectIdentityHeaders}, directement à partir du Contexte réellement
 * envoyé (jamais devinées), pour éliminer tout risque d'invention ou de coquille sur un identifiant
 * T24 — décision du 2026-09-29, suite à l'introduction de cette structure à quatre/cinq sections (avant
 * quoi le format était Réponse/Exemple/Extrait, sans Champ ni Source distincts). Les demandes
 * d'inventaire strict (liste complète des tables/champs d'un module ou d'une table) restent en dehors
 * de cette structure (voir {@link #isExhaustiveDumpRequest}), puisqu'elles ne passent jamais par ce
 * prompt (dump déterministe direct depuis HELP_FIELD).
 *
 * <p>Remplace l'ancienne recherche par mots-clés dans Oracle ({@code HelpContextBuilderService})
 * par une recherche sémantique vectorielle. {@code HelpContextBuilderService}/{@code HelpSearchService}
 * restent intacts et fonctionnels dans le projet (non supprimés, juste plus référencés ici) :
 * pour revenir à l'ancien comportement, il suffit de réinjecter {@code HelpContextBuilderService}
 * ici à la place de {@link RagService} et de restaurer l'ancien prompt.
 */
@Slf4j
@Service
public class ChatbotServiceImpl implements ChatbotService {

    private static final String EMPTY_RESPONSE_FALLBACK = "Le modèle Ollama n'a renvoyé aucune réponse. Réessayez.";
    /** Renvoyé directement, sans appeler Llama3, quand {@link RagService#buildContext} ne trouve
     * aucun document pertinent, et repli de sûreté quand la réponse de Llama3 est rejetée après coup
     * comme non fiable (Extrait fabriqué, voir {@link #rejectIfExtraitIsFabricated}). Doit rester
     * reconnaissable par {@link #collapseToFallbackIfAnswerIsTheFallback}. */
    public static final String NO_INFO_FALLBACK = "La recherche dans la documentation T24 indexée n'a pas permis de confirmer cette "
            + "information. Cela ne prouve pas qu'elle n'existe pas : essayez avec le nom exact d'un module, d'une table ou d'un champ.";
    /** Réponse pendant le chargement de l'index au démarrage (voir {@link RagService#isIndexLoading}) : jamais mise en cache. */
    public static final String INDEX_LOADING_MESSAGE = "La documentation T24 est en cours de chargement (démarrage de l'application). "
            + "Réessayez votre question dans une minute.";
    private static final String MESSAGE_TYPE_USER = "USER";

    /**
     * Trois formats reconnus pour "Extrait" (tolérance volontaire aux réponses déjà en cache et aux
     * variations mineures du modèle) : guillemets droits ou chevrons français « » juste après le
     * libellé sur la même ligne (formats hérités), ou — format actuel depuis l'introduction de la
     * structure Champ/Fonction/Exemple/Source/Extrait documentaire — une ligne de citation « > ... »
     * SOUS un titre "**Extrait documentaire :**" autonome. Les trois tolèrent un éventuel "**" de
     * fermeture Markdown juste après le libellé et le mot "documentaire" en option.
     */
    private static final Pattern EXTRAIT_STRAIGHT_QUOTES =
            Pattern.compile("Extrait(?:\\s+documentaire)?\\s*:?\\**\\s*>?\\s*\"([^\"]+)\"");
    private static final Pattern EXTRAIT_GUILLEMETS =
            Pattern.compile("Extrait(?:\\s+documentaire)?\\s*:?\\**\\s*>?\\s*«\\s*([^»]+?)\\s*»");
    private static final Pattern EXTRAIT_BLOCKQUOTE_LINE =
            Pattern.compile("(?m)^>\\s*«?\\s*([^»\\n]+?)\\s*»?\\s*$");

    /** Lignes d'identité (Champ/Table/Module/Source) que Llama3 ne doit jamais produire lui-même —
     * {@link #SYSTEM_PROMPT} le lui interdit, mais une consigne de prompt n'est pas fiable à 100% avec
     * un modèle de cette taille : filet de sécurité déterministe qui les retire si elles apparaissent
     * quand même, pour ne jamais les dupliquer avec celles injectées par {@link #injectIdentityHeaders}. */
    private static final Pattern STRAY_IDENTITY_LINE =
            Pattern.compile("(?im)^\\*\\*(?:Champ|Table|Module|Source)\\s*:?\\**[^\\n]*\\n*");

    /** Repère déterministe, jamais fourni par Llama3 : le nom exact du champ auquel le Contexte a été
     * restreint (voir {@code RagServiceImpl.extractFieldOnly}, seule source de cette ligne). Utilisé
     * pour injecter la ligne "**Champ :**" en code, jamais demandé au modèle — élimine tout risque
     * d'invention ou de coquille sur un nom d'identifiant T24, contrairement à une simple consigne de
     * prompt (voir la Javadoc de {@link #rejectIfExtraitIsFabricated} sur les limites d'une consigne
     * seule face à un modèle de cette taille).
     */
    private static final Pattern CONTEXT_FIELD_SELECTED_LINE = Pattern.compile("(?m)^Champ sélectionné\\s*:\\s*(.+)$");
    /** Première table citée dans le Contexte (systématiquement en tête de chaque document, voir
     * {@code DocumentBuilderServiceImpl}) : source déterministe de la ligne "**Source :**". */
    private static final Pattern CONTEXT_TABLE_LINE = Pattern.compile("(?m)^Table\\s*:\\s*(.+)$");
    /** Le Contexte signale une ambiguïté de champ (voir {@code RagServiceImpl.buildAmbiguousFieldContext}) :
     * Llama3 doit alors seulement demander une précision, jamais produire le format Champ/Fonction/
     * Exemple/Source/Extrait documentaire — aucune ligne d'identité n'est donc injectée dans ce cas
     * (ni Champ, ni Source), le champ/la table exacts n'étant justement pas encore connus. */
    private static final String AMBIGUOUS_FIELD_MARKER = "Ambigu :";

    /** Ancienne phrase de repli "aucun exemple" (le prompt ne la demande plus : section Exemple conditionnelle depuis le
     * 2026-10-05) — encore reconnue, comme toute formulation équivalente, voir {@link #NO_EXAMPLE_BODY}
     * (section "EXEMPLES"), utilisée par {@link #recoverMissedExample} pour détecter qu'une réponse
     * affirme à tort l'absence d'exemple alors que le Contexte en contient bien un. */
    private static final String NO_EXAMPLE_FALLBACK = "Aucun exemple explicite n'est fourni dans la documentation.";

    /** Formulations génériques (aucun champ ni table T24 codé en dur) signalant qu'une phrase du
     * Contexte introduit un exemple concret — voir {@link #recoverMissedExample}. */
    private static final Pattern EXAMPLE_MARKER = Pattern.compile(
            "(?i)\\b(for example|as an example|e\\.g\\.|such as|could be entered where|"
                    + "where\\s+[A-Z][\\w.]*\\s+is\\b|where\\s+[A-Z][\\w.]*\\s+corresponds to)");

    /** Corps de la section "Exemple"/"Example" d'une réponse déjà générée (groupe 2), depuis son
     * titre (groupe 1) jusqu'à la ligne vide qui précède le prochain titre, ou la fin de la réponse —
     * voir {@link #recoverMissedExample}. Ancien libellé avec ":" ("**Exemple :**") accepté aussi. */
    private static final Pattern EXEMPLE_SECTION_BODY =
            Pattern.compile("(?is)\\*\\*(Exemple|Example)\\s*:?\\*\\*\\s*\\n+(.*?)(?=\\n\\s*\\n\\*\\*|$)");

    /** Structure "where CODE is/corresponds to MEANING" d'une phrase d'exemple T24 — la seule assez
     * régulière pour en extraire fiablement un couple (code, signification) sans deviner. Le code
     * (ex. "GTREC") est capturé tel quel, jamais reformaté, pour ne jamais risquer de l'altérer (voir
     * {@link #formatExampleFact}). */
    private static final Pattern EXAMPLE_FACT = Pattern.compile(
            // Jamais « where X is from/in/of... » (provenance, pas un exemple) : constaté le 2026-10-05 sur TRANSACTION.TYPE,
            // « Where LC.TYPE is from the parent Letter of Credit record » devenait « `LC.TYPE` correspond à from the parent... ».
            "(?i)where\\s+([A-Z][\\w.]*)\\s+(?:is|corresponds?\\s+to)\\s+(?:an?\\s+|the\\s+)?"
                    + "(?!(?:from|in|on|at|of|for|by|to|not|used|defined|set|taken|derived)\\b)([^.\\n]+)");

    /** Vocabulaire de notre propre prompt-engineering (jamais employé par une vraie documentation
     * T24) trahissant une phrase méta du type "La réponse est basée sur la description du champ X
     * dans le Contexte." — voir {@link #stripInternalMetaCommentary}. "Contexte" reste sensible à la
     * casse : c'est ainsi qu'il est systématiquement capitalisé dans {@link #SYSTEM_PROMPT} quand il
     * désigne le Contexte RAG (jamais le sens courant du mot français "contexte"). */
    private static final Pattern META_COMMENTARY_MARKER =
            Pattern.compile("\\bContexte\\b|(?i:\\bRAG\\b|système interne|base de connaissances|\\bpipeline\\b|réponse\\s+est\\s+basée)");

    /** Frontière de phrase fiable même en présence d'identifiants T24 en pointillé (ex. "MD.DEAL") :
     * ponctuation suivie d'un espace puis d'une majuscule/guillemet, jamais un simple ".". */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+(?=[\"«A-ZÀ-ÝŸ])");

    /** Question courante + jusqu'à 3 échanges précédents (7 messages), pour retrouver le dernier
     * tour utilisateur (repli RAG) sans charger tout l'historique de la conversation. */
    private static final int HISTORY_FETCH_SIZE = 7;
    /** Nombre d'échanges (question + réponse) inclus dans le prompt envoyé à Llama3. */
    private static final int PROMPT_HISTORY_TURNS = 2;

    /** Demande explicite d'un listage complet (module ou table), voir {@link #isExhaustiveDumpRequest}
     * et {@link #buildExhaustiveDump} — déclenche un dump déterministe depuis HELP_FIELD au lieu du
     * pipeline RAG/Llama3 habituel. Volontairement large (français + anglais) : mieux vaut déclencher
     * un peu trop souvent ce dump complet (sans risque, toujours fidèle aux données) qu'un mot-clé
     * manqué qui renverrait une simple synthèse là où l'utilisateur voulait l'exhaustivité. */
    private static final Pattern EXHAUSTIVE_DUMP_PATTERN = Pattern.compile(
            "(?i)\\b(toutes?\\s+les\\s+informations|tous\\s+les\\s+champs|toute\\s+la\\s+documentation|"
                    + "documentation\\s+complète|liste\\s+complète|détails?\\s+complets?|toutes\\s+les\\s+tables|"
                    + "all\\s+(the\\s+)?information|all\\s+(the\\s+)?fields|full\\s+documentation|complete\\s+list)\\b");

    /**
     * "Décris la table CUSTOMER" (sans mentionner explicitement "tous les champs") doit, elle aussi,
     * renvoyer le dump complet plutôt que le résumé en 1-2 phrases de {@code SYSTEM_PROMPT} (section
     * "TABLES T24") — demande explicite : la description courte ne doit plus être le comportement par
     * défaut d'une table quand plus d'informations existent. Ne se déclenche QUE quand un champ n'est
     * pas explicitement visé ({@link #MENTIONS_SPECIFIC_FIELD_PATTERN}) : "décris le champ X" doit
     * rester une réponse ciblée sur ce champ, pas un dump de toute la table.
     */
    private static final Pattern TABLE_OVERVIEW_PATTERN = Pattern.compile(
            "(?i)\\b(décris|décrire|describe|présente[rz]?|vue\\s+d'ensemble|informations?\\s+(sur|de|du|complètes?)|"
                    + "détails?\\s+(sur|de|du)|overview)\\b");
    private static final Pattern MENTIONS_SPECIFIC_FIELD_PATTERN = Pattern.compile("(?i)\\bchamps?\\b|\\bfields?\\b|\\bcolonnes?\\b");

    private static final int MAX_TABLES_PER_MODULE_DUMP = 300;

    private final ChatClient chatClient;
    private final RagService ragService;
    private final ConversationRecorder conversationRecorder;
    private final ChatResponseCache responseCache;
    private final ChatService chatService;
    private final ChatHistoryService chatHistoryService;
    private final TableT24Repository tableT24Repository;
    private final HelpFieldRepository helpFieldRepository;
    private final FieldSearchService fieldSearchService;
    private final ConceptGlossary conceptGlossary;

    /** Questions en cours de traitement, par utilisateur + conversation + question normalisée : voir {@link #ask(String, Long)}. */
    private final ConcurrentMap<String, CompletableFuture<ChatAskResponse>> inFlight = new ConcurrentHashMap<>();

    public ChatbotServiceImpl(ChatClient.Builder chatClientBuilder, RagService ragService,
                               ConversationRecorder conversationRecorder, ChatResponseCache responseCache,
                               ChatService chatService, ChatHistoryService chatHistoryService,
                               TableT24Repository tableT24Repository, HelpFieldRepository helpFieldRepository,
                               FieldSearchService fieldSearchService, ConceptGlossary conceptGlossary) {
        this.conceptGlossary = conceptGlossary;
        this.chatClient = chatClientBuilder.build();
        this.ragService = ragService;
        this.conversationRecorder = conversationRecorder;
        this.responseCache = responseCache;
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
        this.tableT24Repository = tableT24Repository;
        this.helpFieldRepository = helpFieldRepository;
        this.fieldSearchService = fieldSearchService;
    }

    @Override
    public ChatAskResponse ask(String question) {
        return ask(question, null);
    }

    /**
     * Une seule exécution à la fois pour une même question d'un même utilisateur dans une même
     * conversation : un double envoi (double clic, Entrée + bouton, « Réessayer » ou renvoi après un
     * « Stop » alors que le serveur travaille encore) rejoint la requête déjà en cours et reçoit sa
     * réponse, au lieu de déclencher un second traitement RAG/LLM et d'enregistrer la question et la
     * réponse une seconde fois dans l'historique.
     */
    @Override
    public ChatAskResponse ask(String question, Long requestedChatId) {
        String key = inFlightKey(question, requestedChatId);
        CompletableFuture<ChatAskResponse> mine = new CompletableFuture<>();
        CompletableFuture<ChatAskResponse> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            log.info("Question identique déjà en cours de traitement pour cet utilisateur/cette conversation : "
                    + "la requête en double attend la réponse en cours (aucun second traitement ni enregistrement).");
            return awaitRunning(running);
        }
        try {
            ChatAskResponse response = doAsk(question, requestedChatId);
            mine.complete(response);
            return response;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private Long recordAssistant(Long chatId, String answer) {
        return Perf.time(Category.DATABASE, "enregistrement de la réponse (Oracle)",
                () -> conversationRecorder.recordAssistantMessage(chatId, answer));
    }

    /** Détail du temps Ollama (chargement du modèle, lecture du prompt, génération) et débit, depuis les métadonnées de réponse. */
    private static void recordOllamaMetrics(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        var metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        Object load = metadata.get("load-duration");
        Object promptEval = metadata.get("prompt-eval-duration");
        Object eval = metadata.get("eval-duration");
        if (load != null) {
            Perf.fact("Ollama : chargement du modèle", load);
        }
        if (promptEval != null) {
            Perf.fact("Ollama : lecture du prompt (prompt eval)", promptEval);
        }
        if (eval != null) {
            Perf.fact("Ollama : génération (eval)", eval);
        }
        if (usage != null) {
            Perf.fact("Tokens prompt / générés", usage.getPromptTokens() + " / " + usage.getCompletionTokens());
        }
    }

    private static String inFlightKey(String question, Long chatId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth != null && auth.getName() != null ? auth.getName() : "anonymous";
        return user + "|" + chatId + "|" + question.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static ChatAskResponse awaitRunning(CompletableFuture<ChatAskResponse> running) {
        try {
            return running.get(15, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Attente interrompue.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Délai dépassé en attendant la réponse en cours.", e);
        }
    }

    private ChatAskResponse doAsk(String question, Long requestedChatId) {
        PerfMarks.serviceStart();
        Perf.record(Category.RECEPTION, "réception de la requête (filtres HTTP, authentification JWT, désérialisation)",
                PerfMarks.serviceStartMs() * 1_000_000);
        try {
            return doAskProfiled(question, requestedChatId);
        } finally {
            PerfMarks.serviceEnd();
        }
    }

    private ChatAskResponse doAskProfiled(String question, Long requestedChatId) {
        long totalStart = System.currentTimeMillis();
        // Texte saisi par l'utilisateur : jamais journalisé en entier hors DEBUG.
        log.info("Nouvelle question ({} caractère(s)).", question.length());
        log.debug("Question :\n{}", question);

        ConversationRecordingResult recording = Perf.time(Category.DATABASE, "enregistrement de la question (Oracle)",
                () -> requestedChatId == null
                        ? conversationRecorder.recordUserMessage(question)
                        : conversationRecorder.recordUserMessage(question, requestedChatId));
        Optional<Long> chatId = Optional.ofNullable(recording.getChatId());

        // Lu avant le cache : une même phrase ("Quels sont les champs ?") n'a pas la même réponse selon le
        // module/la table de la conversation, donc le cache ne peut pas être indexé sur la seule question.
        ChatContext contextOfChat = Perf.time(Category.MODULE_DETECTION, "détection du module / contexte de conversation (Oracle)",
                () -> chatId.map(this::resolveChatContext).orElse(ChatContext.EMPTY));

        // Lu AVANT le cache lui aussi : une question sans mot-clé T24 est interprétée comme la suite de la
        // question précédente (voir RagServiceImpl.analyzeWithFallback), donc sa réponse dépend de celle-ci.
        List<ChatHistoryDTO> recentHistory = Perf.time(Category.DATABASE, "lecture de l'historique récent (Oracle)",
                () -> chatId.map(this::fetchRecentHistoryExcludingCurrent).orElse(List.<ChatHistoryDTO>of()));
        Optional<String> previousUserQuestion = firstUserMessage(recentHistory);

        ChatContext chatContext = contextOfChat.withPreviousUserQuestion(previousUserQuestion.orElse(null));

        if (ragService.isIndexLoading()) {
            // Index pas encore chargé : toute recherche (fiche de table, RAG) ne trouverait rien — répondre « la
            // recherche n'a pas permis de confirmer » serait faux. Bug réel du 2026-10-06, voir RagServiceImpl.indexLoading.
            log.info("Index documentaire en cours de chargement : question non traitée pour l'instant (pas de cache).");
            Long assistantId = chatId.map(id -> recordAssistant(id, INDEX_LOADING_MESSAGE)).orElse(null);
            return new ChatAskResponse(INDEX_LOADING_MESSAGE, recording.isAuthenticationRequired(), recording.getUserMessageId(), assistantId);
        }

        Optional<String> cachedAnswer = Perf.time(Category.PREPROCESSING, "consultation du cache de réponses",
                () -> responseCache.get(cacheKey(question, chatContext)));
        if (cachedAnswer.isPresent()) {
            log.info("Réponse trouvée en cache (question déjà posée récemment) : appel RAG/Ollama évité.");
            log.info("Temps total : {} ms", System.currentTimeMillis() - totalStart);
            Long assistantId = chatId.map(id -> recordAssistant(id, cachedAnswer.get())).orElse(null);
            return new ChatAskResponse(cachedAnswer.get(), recording.isAuthenticationRequired(), recording.getUserMessageId(), assistantId);
        }

        boolean wantsExhaustiveDump = isExhaustiveDumpRequest(question)
                || (chatContext.hasTable() && TABLE_OVERVIEW_PATTERN.matcher(question).find()
                        && !MENTIONS_SPECIFIC_FIELD_PATTERN.matcher(question).find());
        if (wantsExhaustiveDump && (chatContext.hasModule() || chatContext.hasTable())) {
            // Demande explicite de listage complet (ex. "Donne-moi toutes les informations du module
            // MD") : construite directement depuis HELP_FIELD, sans passer par Llama3. Deux raisons :
            // 1) une table comme CUSTOMER compte à elle seule 490 champs, un module peut en contenir
            //    bien plus — hors de portée de la fenêtre de contexte d'Ollama (voir RagServiceImpl,
            //    MAX_HEAD_CHARS_IN_CONTEXT/MAX_DOCUMENT_CHARS_IN_CONTEXT) ; 2) l'utilisateur demande
            //    une reproduction fidèle et complète des données documentées, pas une reformulation :
            //    un dump déterministe garantit zéro troncature et zéro invention, ce qu'une synthèse
            //    par LLM ne peut pas garantir à 100%.
            log.info("Demande de listage exhaustif détectée (module={}, table={}) : réponse construite "
                    + "directement depuis HELP_FIELD, Llama3 n'est pas appelé.", chatContext.module(), chatContext.table());
            String dump = Perf.time(Category.DETERMINISTIC, "listage exhaustif (HELP_FIELD)", () -> buildExhaustiveDump(chatContext));
            log.info("Temps total : {} ms", System.currentTimeMillis() - totalStart);
            Long assistantId = chatId.map(id -> recordAssistant(id, dump)).orElse(null);
            return new ChatAskResponse(dump, recording.isAuthenticationRequired(), recording.getUserMessageId(), assistantId);
        }

        Optional<String> fieldSearchAnswer = Perf.time(Category.DETERMINISTIC,
                "réponses déterministes (fiche de table, recherche de tables/champs, vue module)",
                () -> fieldSearchService.tryAnswer(question, chatContext));
        if (fieldSearchAnswer.isPresent()) {
            // Recherche inversée (« quel champ contient X ? ») : réponse construite par balayage complet du
            // périmètre dans HELP_FIELD, sans LLM — voir FieldSearchService. Jamais mise en cache : elle est
            // rapide à recalculer et doit refléter l'état réel des données.
            log.info("Recherche inversée de champ : réponse déterministe, Llama3 n'est pas appelé.");
            String answer = fieldSearchAnswer.get();
            log.info("Temps total : {} ms", System.currentTimeMillis() - totalStart);
            Long assistantId = chatId.map(id -> recordAssistant(id, answer)).orElse(null);
            return new ChatAskResponse(answer, recording.isAuthenticationRequired(), recording.getUserMessageId(), assistantId);
        }

        long searchStart = System.currentTimeMillis();
        Optional<String> context = ragService.buildContext(question, chatContext);
        log.info("Temps de recherche vectorielle (RAG) : {} ms", System.currentTimeMillis() - searchStart);
        Perf.fact("Recherche RAG complète (buildContext)", (System.currentTimeMillis() - searchStart) + " ms");

        if (context.isEmpty()) {
            // RAG strict : aucun document pertinent -> pas d'appel Llama3, pas de repli sur les
            // connaissances générales (voir la Javadoc de la classe). Évite aussi un appel Ollama
            // inutile.
            log.info("Aucun document pertinent : Llama3 n'est pas appelé.");
            log.info("Temps total : {} ms", System.currentTimeMillis() - totalStart);
            Long assistantId = chatId.map(id -> recordAssistant(id, NO_INFO_FALLBACK)).orElse(null);
            return new ChatAskResponse(NO_INFO_FALLBACK, recording.isAuthenticationRequired(), recording.getUserMessageId(), assistantId);
        }

        // Champ ambigu (plusieurs tables) ou sans aide documentée : tranché ici, AVANT la génération — Llama3 choisissait
        // sinon une table arbitrairement, ou déduisait une fonction du seul nom du champ (voir DeterministicFieldAnswers).
        Optional<String> fieldAnswer = Perf.time(Category.POST_PROCESSING, "décision champ ambigu / sans aide (avant LLM)",
                () -> DeterministicFieldAnswers.ambiguousField(context.get())
                        .or(() -> DeterministicFieldAnswers.undocumentedField(context.get())));
        if (fieldAnswer.isPresent()) {
            log.info("Réponse sur le champ décidée avant génération ({}) : Llama3 n'est pas appelé.",
                    fieldAnswer.get().contains("Pouvez-vous préciser la table") ? "champ présent dans plusieurs tables" : "aucune aide documentée");
            String answer = fieldAnswer.get();
            log.info("Temps total : {} ms", System.currentTimeMillis() - totalStart);
            Long assistantId = chatId.map(id -> recordAssistant(id, answer)).orElse(null);
            responseCache.put(cacheKey(question, chatContext), answer);
            return new ChatAskResponse(answer, recording.isAuthenticationRequired(), recording.getUserMessageId(), assistantId);
        }

        String userPrompt = Perf.time(Category.CONTEXT_BUILDING, "construction du prompt",
                () -> buildUserPrompt(context.get(), question, formatHistoryForPrompt(recentHistory)));
        Perf.fact("Taille du contexte envoyé au LLM", context.get().length() + " caractères");
        Perf.fact("Taille du prompt (system + user)", (SYSTEM_PROMPT.length() + userPrompt.length()) + " caractères");
        log.info("Contexte transmis à Llama3 : {} caractère(s) de contexte documentaire inclus dans un prompt "
                + "utilisateur de {} caractère(s) (module={}, table={}).",
                context.get().length(), userPrompt.length(), chatContext.module(), chatContext.table());
        log.debug("Prompt envoyé à Ollama (system) :\n{}", SYSTEM_PROMPT);
        log.debug("Prompt envoyé à Ollama (user) :\n{}", userPrompt);

        long ollamaStart = System.currentTimeMillis();
        Perf.fact("Temps avant l'appel Ollama (depuis la réception)", Perf.sinceStartMs() + " ms");
        ChatResponse response = Perf.time(Category.LLM, "appel Ollama / génération LLM",
                () -> chatClient.prompt().system(SYSTEM_PROMPT).user(userPrompt).call().chatResponse());
        long ollamaElapsedMs = System.currentTimeMillis() - ollamaStart;
        recordOllamaMetrics(response);

        Usage usage = response != null && response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        log.info("Temps de réponse Ollama : {} ms | Tokens (prompt/completion/total) : {}/{}/{}",
                ollamaElapsedMs,
                usage != null ? usage.getPromptTokens() : "n/a",
                usage != null ? usage.getCompletionTokens() : "n/a",
                usage != null ? usage.getTotalTokens() : "n/a");

        String rawAnswer = extractAnswer(response);
        log.info("Réponse Llama3 reçue ({} caractère(s)).", rawAnswer.length());
        log.debug("Réponse Llama3 :\n{}", rawAnswer);
        long postStart = System.nanoTime();
        String verifiedAnswer = keepOnlySupportedClaims(quoteTheSelectedFieldNotItsTable(
                rejectIfExtraitIsFabricated(rawAnswer, context.get()), context.get()), context.get(), question);
        String recoveredAnswer = dropExampleSectionWithoutExample(keepOnlyDocumentedExample(
                appendMissingDocumentedFacts(recoverMissedExample(verifiedAnswer, context.get()), context.get(), question),
                context.get()));
        String cleaned = collapseToFallbackIfAnswerIsTheFallback(
                stripInternalMetaCommentary(stripStrayIdentityLines(recoveredAnswer)));
        String answer = NO_INFO_FALLBACK.equals(cleaned) ? cleaned : injectIdentityHeaders(cleaned, context.get());
        Perf.record(Category.POST_PROCESSING, "post-processing / validation de la réponse (garde-fous)", System.nanoTime() - postStart);
        log.info("Temps total : {} ms", System.currentTimeMillis() - totalStart);
        Long assistantId = chatId.map(id -> recordAssistant(id, answer)).orElse(null);
        // Jamais une réponse vide/erreur : ce serait servir un échec transitoire d'Ollama comme
        // réponse définitive à toute question identique future, y compris après son rétablissement.
        if (!EMPTY_RESPONSE_FALLBACK.equals(answer)) {
            responseCache.put(cacheKey(question, chatContext), answer);
        }
        return new ChatAskResponse(answer, recording.isAuthenticationRequired(), recording.getUserMessageId(), assistantId);
    }

    /**
     * La question seule quand la conversation n'a aucun contexte T24 ; sinon question + module/table/champ ;
     * plus la question précédente quand il y en a une. Bug réel corrigé : "Comment le système crée-t-il
     * automatiquement des sous-comptes ?" (aucun mot-clé T24) posée juste après une question sur INT.ACC.TYPE
     * était traitée comme sa suite et répondait sur INT.ACC.TYPE — puis cette réponse, mise en cache sous la
     * seule clé question+module, était resservie à une NOUVELLE conversation sans aucun historique.
     */
    private static String cacheKey(String question, ChatContext context) {
        String key = (!context.hasModule() && !context.hasTable() && !context.hasField())
                ? question
                : question + " [contexte:" + context.module() + "/" + context.table() + "/" + context.field() + "]";
        return context.hasPreviousUserQuestion() ? key + " [suite de:" + context.previousUserQuestion() + "]" : key;
    }

    /**
     * Le prompt exige que la phrase de repli soit la réponse COMPLÈTE ("rien avant, rien après") ; le modèle
     * l'enveloppe pourtant parfois ("La réponse est : « … »"). Dans ce cas, seule la phrase de repli est renvoyée.
     */
    private static String collapseToFallbackIfAnswerIsTheFallback(String answer) {
        return answer.contains(NO_INFO_FALLBACK) ? NO_INFO_FALLBACK : answer;
    }

    private static boolean isExhaustiveDumpRequest(String question) {
        return EXHAUSTIVE_DUMP_PATTERN.matcher(question).find() && !FILTERED_TABLE_LIST_PATTERN.matcher(question).find();
    }

    /**
     * « Toutes les tables LIÉES AUX BALANCES » est une liste filtrée par un concept (voir TableFinder), pas l'inventaire
     * complet du module. Bug réel (2026-10-07, module MD) : cette question renvoyait les 25 tables du module avec leurs champs.
     */
    private static final Pattern FILTERED_TABLE_LIST_PATTERN = Pattern.compile(
            "(?iu)\\btoutes\\s+les\\s+tables\\s+(?:liée?s?|relatifs?|relatives?|concernant|qui|dont|associée?s?|traitant|sur|pour|"
                    + "avec|contenant|ayant|de\\s+(?:balances?|soldes?)|des\\s+\\p{L}{4,})\\b");

    /**
     * Dump déterministe (aucun appel Llama3) de tous les champs d'une table, ou de toutes les tables
     * d'un module — voir l'appel dans {@link #ask} pour le contexte et la justification. La table
     * prime sur le module si les deux sont fixés (cohérent avec {@code RagServiceImpl.buildContext}).
     */
    private String buildExhaustiveDump(ChatContext context) {
        if (context.hasTable()) {
            List<HelpField> fields = helpFieldRepository.findByTableNameIgnoreCase(context.table());
            if (fields.isEmpty()) {
                return "Aucun champ documenté n'a été trouvé pour la table `" + context.table() + "`.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("**Table :** `").append(context.table()).append("`\n\n");
            appendFieldList(sb, fields);
            return sb.toString();
        }

        List<TableT24> tables = tableT24Repository
                .findByModuleNomModuleIgnoreCase(context.module(), PageRequest.of(0, MAX_TABLES_PER_MODULE_DUMP))
                .getContent();
        if (tables.isEmpty()) {
            return "Aucune table documentée n'a été trouvée pour le module `" + context.module() + "`.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Module :** `").append(context.module()).append("` — ")
                .append(tables.size()).append(" table(s)\n\n");
        for (TableT24 table : tables.stream().sorted(Comparator.comparing(TableT24::getNomTable)).toList()) {
            List<HelpField> fields = helpFieldRepository.findByTableNameIgnoreCase(table.getNomTable());
            sb.append("---\n\n**Table :** `").append(table.getNomTable()).append("`");
            if (fields.isEmpty()) {
                sb.append(" — aucun champ documenté.\n\n");
                continue;
            }
            sb.append(" (").append(fields.size()).append(" champ(s))\n\n");
            appendFieldList(sb, fields);
        }
        return sb.toString();
    }

    private static void appendFieldList(StringBuilder sb, List<HelpField> fields) {
        for (HelpField field : fields.stream().sorted(Comparator.comparing(HelpField::getFieldName)).toList()) {
            sb.append("- **`").append(field.getFieldName()).append("`**\n");
            sb.append("  - Description : ")
                    .append(blankToNonRenseigne(field.getDescription())).append("\n");
            sb.append("  - Type : ").append(blankToNonRenseigne(field.getFieldType())).append("\n");
            sb.append("  - Obligatoire : ").append(blankToNonRenseigne(field.getMandatory())).append("\n");
            if (field.getDefaultValue() != null && !field.getDefaultValue().isBlank()) {
                sb.append("  - Valeur par défaut : `").append(field.getDefaultValue()).append("`\n");
            }
            sb.append("\n");
        }
    }

    private static String blankToNonRenseigne(String value) {
        return value == null || value.isBlank() ? "Non renseigné dans la documentation." : value;
    }

    /** Best-effort : une conversation sans contexte fixé (ou une erreur de lecture) dégrade simplement
     * vers {@link ChatContext#EMPTY} (recherche non restreinte), jamais vers une erreur pour l'utilisateur. */
    private ChatContext resolveChatContext(Long chatId) {
        try {
            ChatDTO chat = chatService.findById(chatId);
            return new ChatContext(chat.getSelectedModule(), chat.getSelectedTable(), chat.getSelectedField());
        } catch (Exception e) {
            log.warn("Impossible de lire le contexte T24 de la conversation {} : recherche non restreinte.", chatId, e);
            return ChatContext.EMPTY;
        }
    }

    /**
     * Derniers messages de la conversation (hors la question qu'on vient d'enregistrer via
     * {@code recordUserMessage}), du plus récent au plus ancien. Best-effort : toute erreur dégrade
     * silencieusement vers une liste vide (même philosophie que {@link #resolveChatContext}) — la
     * mémoire conversationnelle est un confort, jamais un blocage du pipeline RAG.
     */
    private List<ChatHistoryDTO> fetchRecentHistoryExcludingCurrent(Long chatId) {
        try {
            List<ChatHistoryDTO> page = chatHistoryService
                    .findByChat(chatId, PageRequest.of(0, HISTORY_FETCH_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")))
                    .getContent();
            return page.isEmpty() ? page : page.subList(1, page.size());
        } catch (Exception e) {
            log.warn("Impossible de lire l'historique récent de la conversation {} : mémoire conversationnelle "
                    + "désactivée pour cette question.", chatId, e);
            return List.of();
        }
    }

    /** @param recentHistoryDesc historique du plus récent au plus ancien (voir {@link #fetchRecentHistoryExcludingCurrent}) */
    private static Optional<String> firstUserMessage(List<ChatHistoryDTO> recentHistoryDesc) {
        return recentHistoryDesc.stream()
                .filter(h -> MESSAGE_TYPE_USER.equals(h.getMessageType()))
                .findFirst()
                .map(ChatHistoryDTO::getContent);
    }

    /**
     * Formate les {@link #PROMPT_HISTORY_TURNS} derniers échanges (question + réponse), du plus
     * ancien au plus récent, pour insertion dans le prompt Llama3 — vide si aucun historique.
     *
     * @param recentHistoryDesc historique du plus récent au plus ancien (voir {@link #fetchRecentHistoryExcludingCurrent})
     */
    private static String formatHistoryForPrompt(List<ChatHistoryDTO> recentHistoryDesc) {
        if (recentHistoryDesc.isEmpty()) {
            return "";
        }
        List<ChatHistoryDTO> chronological = new ArrayList<>(recentHistoryDesc);
        Collections.reverse(chronological);

        int maxMessages = PROMPT_HISTORY_TURNS * 2;
        List<ChatHistoryDTO> limited = chronological.size() > maxMessages
                ? chronological.subList(chronological.size() - maxMessages, chronological.size())
                : chronological;

        StringBuilder sb = new StringBuilder();
        for (ChatHistoryDTO turn : limited) {
            String role = MESSAGE_TYPE_USER.equals(turn.getMessageType()) ? "Utilisateur" : "Assistant";
            sb.append(role).append(" : ").append(turn.getContent()).append('\n');
        }
        return sb.toString();
    }

    private static String extractAnswer(ChatResponse response) {
        String text = (response != null && response.getResult() != null && response.getResult().getOutput() != null)
                ? response.getResult().getOutput().getText()
                : null;
        return (text == null || text.isBlank()) ? EMPTY_RESPONSE_FALLBACK : text;
    }

    /**
     * Garde-fou déterministe contre une hallucination du modèle : {@link #SYSTEM_PROMPT} interdit
     * de citer un "Extrait" qui ne vient pas mot pour mot du Contexte, mais un modèle de cette
     * taille ne suit pas cette consigne de façon fiable quand il "connaît" par ailleurs le sujet
     * (constaté en conditions réelles sur DEAL.SUB.TYP/T24, un produit réel dont Llama3 a
     * manifestement mémorisé de la documentation publique à l'entraînement : la réponse restait
     * identique et fausse même après que le texte trompeur ait été entièrement retiré du Contexte
     * envoyé). Une instruction de prompt ne peut pas empêcher un modèle de citer un souvenir
     * d'entraînement qu'il croit exact ; seule une vérification en code, après coup, contre le
     * Contexte réellement envoyé, le peut. Ne vérifie que si un "Extrait" est effectivement cité
     * (réponses table/module sans citation : rien à vérifier, réponse inchangée).
     *
     * <p><strong>Bug réel corrigé (2026-09-29)</strong> : pour DELIVERY.REF (table MD.DEAL), le
     * Contexte contenait bien la réponse et la Fonction générée par Llama3 était correcte, mais la
     * comparaison EXACTE rejetait tout de même la réponse ENTIÈRE — Llama3 avait "corrigé" une coquille
     * de la documentation source en citant ("identies" -&gt; "identities"), un cas de reformulation
     * quasi imperceptible, pas une invention. Rejeter l'ensemble de la réponse dans ce cas contredisait
     * l'information réellement trouvée. Avant de conclure à une hallucination, une correspondance
     * proche (mais non strictement identique) est maintenant cherchée phrase par phrase dans le
     * Contexte ({@link #findCloseMatchInContext}, distance de Levenshtein) : si une phrase du Contexte
     * est très proche de la citation revendiquée (coquille, casse, ponctuation mineure...), l'Extrait
     * affiché est remplacé par cette phrase RÉELLEMENT présente dans le Contexte (jamais la version de
     * Llama3, potentiellement altérée) et le reste de la réponse (Fonction/Exemple, déjà correctement
     * générés) est conservé tel quel — seul un écart trop important (aucune phrase suffisamment
     * proche : véritable invention, pas une simple coquille) fait encore basculer vers
     * {@link #NO_INFO_FALLBACK}.
     */
    private String rejectIfExtraitIsFabricated(String answer, String context) {
        Optional<String> claimedExtrait = extractClaimedExtrait(answer);
        if (claimedExtrait.isEmpty()) {
            return answer;
        }
        String normalizedContext = normalizeForComparison(context);
        if (normalizedContext.contains(normalizeForComparison(claimedExtrait.get()))) {
            return answer;
        }
        // Citation mot pour mot d'un DÉBUT de phrase, terminée par Llama3 avec sa propre ponctuation — bug réel
        // (test live LD, 2026-09-30) : "...Rate revisions Diary entries." alors que la documentation poursuit
        // sans point ("Diary entries Updates to this file..."). Sans ponctuation finale, la citation est une
        // sous-chaîne exacte du Contexte : aucune invention possible, jamais un motif de rejet.
        String withoutFinalPunctuation = TRAILING_QUOTE_PUNCTUATION.matcher(normalizeForComparison(claimedExtrait.get())).replaceFirst("");
        if (!withoutFinalPunctuation.isEmpty() && normalizedContext.contains(withoutFinalPunctuation)) {
            return answer;
        }
        Optional<String> closeMatch = findCloseMatchInContext(claimedExtrait.get(), context);
        if (closeMatch.isPresent()) {
            log.info("Extrait cité par Llama3 proche mais pas identique au Contexte (probable reformulation "
                    + "mineure ou correction orthographique d'une coquille de la documentation source, pas une "
                    + "invention) : remplacé par le passage réellement présent dans le Contexte plutôt que "
                    + "rejeté. Citation de Llama3 :\n{}\nPassage réel utilisé à la place :\n{}",
                    claimedExtrait.get(), closeMatch.get());
            return answer.replace(claimedExtrait.get(), closeMatch.get());
        }
        log.warn("Extrait cité par Llama3 introuvable (même approximativement) dans le Contexte envoyé — probable "
                + "hallucination, réponse rejetée et remplacée par le repli. Extrait cité :\n{}", claimedExtrait.get());
        return NO_INFO_FALLBACK;
    }

    /** Similarité minimale (0-1, voir {@link #similarity}) en dessous de laquelle une citation n'est plus
     * considérée comme une simple reformulation/coquille du Contexte, mais comme une probable invention. */
    private static final double EXTRAIT_SIMILARITY_THRESHOLD = 0.85;

    /** Ponctuation/points de suspension ajoutés par Llama3 en fin de citation (voir {@link #rejectIfExtraitIsFabricated}). */
    private static final Pattern TRAILING_QUOTE_PUNCTUATION = Pattern.compile("[\\s.…!?;:,]+$");

    /**
     * Cherche, parmi les phrases du Contexte (et les paires de phrases consécutives, une citation pouvant
     * chevaucher deux phrases courtes), celle la plus proche de {@code claimedExtrait} au sens de la
     * distance de Levenshtein — jamais une recherche de sous-chaîne glissante caractère par caractère sur
     * tout le Contexte (trop coûteux et sans intérêt : une citation authentique correspond toujours à une
     * ou deux phrases réelles, jamais à un fragment arbitraire).
     *
     * @return la phrase RÉELLE (jamais celle de Llama3) si sa similarité atteint {@link
     * #EXTRAIT_SIMILARITY_THRESHOLD}, sinon {@link Optional#empty()} — dans ce dernier cas, l'appelant
     * doit traiter la citation comme une probable invention, pas comme une coquille.
     */
    /** Repère un préfixe "Libellé :" (ex. "Description :", "Champ sélectionné :") en tête d'un bloc du
     * Contexte — jamais rattaché au premier vrai candidat, sinon il dilue sa similarité avec la citation
     * (constaté en conditions réelles : ce bruit à lui seul suffisait à repasser sous {@link
     * #EXTRAIT_SIMILARITY_THRESHOLD} malgré une seule coquille dans le reste du texte). */
    private static final Pattern PARAGRAPH_LABEL_PREFIX = Pattern.compile("^\\p{L}[\\p{L} ]{0,38}:\\s*");

    private static Optional<String> findCloseMatchInContext(String claimedExtrait, String context) {
        String normalizedClaim = normalizeForComparison(claimedExtrait);
        // Découpe par bloc (ligne vide) AVANT de découper en phrases : chaque en-tête du Contexte ("Table :
        // X", "Champ sélectionné : Y"...) reste dans son propre bloc plutôt que d'être fusionné avec la
        // première vraie phrase de la description qui le suit (aucune ponctuation de fin entre les deux) —
        // voir la Javadoc de PARAGRAPH_LABEL_PREFIX.
        List<String> sentenceList = new ArrayList<>();
        for (String paragraph : context.split("\\r?\\n\\s*\\r?\\n")) {
            String flat = paragraph.replaceAll("\\s+", " ").trim();
            flat = PARAGRAPH_LABEL_PREFIX.matcher(flat).replaceFirst("");
            if (flat.isEmpty()) {
                continue;
            }
            if (isWordSubsequenceOf(claimedExtrait, flat)) {
                log.info("Extrait cité par Llama3 : sous-séquence exacte (mots dans le bon ordre) d'un paragraphe "
                        + "du Contexte, mais avec un segment interne omis (résumé/ellipse, pas une invention) — "
                        + "paragraphe complet réellement présent dans le Contexte utilisé à la place. Citation de "
                        + "Llama3 :\n{}\nParagraphe réel utilisé à la place :\n{}", claimedExtrait, flat);
                return Optional.of(flat);
            }
            for (String sentence : flat.split("(?<=[.!?])\\s+")) {
                if (!sentence.isBlank()) {
                    sentenceList.add(sentence.trim());
                }
            }
        }

        String bestMatch = null;
        double bestScore = 0;
        for (String sentence : sentenceList) {
            double score = similarity(normalizedClaim, normalizeForComparison(sentence));
            if (score > bestScore) {
                bestScore = score;
                bestMatch = sentence;
            }
        }
        for (int i = 0; i < sentenceList.size() - 1; i++) {
            String combined = (sentenceList.get(i) + " " + sentenceList.get(i + 1)).trim();
            double score = similarity(normalizedClaim, normalizeForComparison(combined));
            if (score > bestScore) {
                bestScore = score;
                bestMatch = combined;
            }
        }
        return bestScore >= EXTRAIT_SIMILARITY_THRESHOLD ? Optional.ofNullable(bestMatch) : Optional.empty();
    }

    /**
     * Un extrait "cité" par Llama3 peut condenser un paragraphe source plus long en omettant une phrase
     * intermédiaire tout en gardant intactes celles qui l'entourent — bug réel constaté sur DE.BIC : la
     * citation gardait la 1ère et les deux dernières phrases d'un paragraphe de quatre, sans la 2e. Un tel
     * "trou" interne dépasse toujours {@link #EXTRAIT_SIMILARITY_THRESHOLD} (la comparaison phrase par
     * phrase / paire de phrases de {@link #findCloseMatchInContext} ne teste que des fenêtres d'une ou deux
     * phrases CONSÉCUTIVES, jamais trois phrases avec un trou), alors même qu'aucun mot cité n'est inventé —
     * chacun est réellement présent, dans le bon ordre, dans le Contexte. On le détecte ici indépendamment
     * de la similarité globale : si TOUS les mots de la citation apparaissent, dans le même ordre (sous-
     * séquence), parmi les mots du paragraphe, alors la citation est un résumé fidèle (juste raccourci) de
     * ce paragraphe, jamais une invention — see {@link #findCloseMatchInContext}, qui substitue alors le
     * paragraphe RÉEL complet à la version tronquée de Llama3.
     *
     * <p>Un extrait trop court (mots &lt; 8) est exclu : sur si peu de mots, un alignement dans le bon ordre
     * peut se produire par pur hasard dans un paragraphe non lié. Le paragraphe candidat est aussi limité à
     * trois fois la longueur de la citation : au-delà, la contrainte d'ordre devient trop peu discriminante
     * pour rester une preuve fiable d'authenticité.
     */
    private static boolean isWordSubsequenceOf(String claimedExtrait, String paragraph) {
        String[] claimWords = tokenizeForSubsequenceMatch(claimedExtrait);
        String[] paragraphWords = tokenizeForSubsequenceMatch(paragraph);
        if (claimWords.length < 8 || paragraphWords.length > claimWords.length * 3) {
            return false;
        }
        int paragraphIndex = 0;
        for (String claimWord : claimWords) {
            while (paragraphIndex < paragraphWords.length && !paragraphWords[paragraphIndex].equals(claimWord)) {
                paragraphIndex++;
            }
            if (paragraphIndex == paragraphWords.length) {
                return false;
            }
            paragraphIndex++;
        }
        return true;
    }

    /** Mots normalisés (casse, ponctuation de bord) d'un texte, pour {@link #isWordSubsequenceOf} — la
     * ponctuation de bord (ex. le point final de "SWIFT." quand une phrase citée est tronquée juste après)
     * ne doit jamais, à elle seule, faire échouer un rapprochement de mots par ailleurs identiques. */
    private static String[] tokenizeForSubsequenceMatch(String text) {
        return Arrays.stream(normalizeForComparison(text).split("\\s+"))
                .map(word -> word.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", ""))
                .filter(word -> !word.isEmpty())
                .toArray(String[]::new);
    }

    /** Similarité de deux chaînes déjà normalisées, entre 0 (rien en commun) et 1 (identiques) : 1 moins la
     * distance de Levenshtein rapportée à la longueur de la plus longue des deux. */
    private static double similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        return maxLen == 0 ? 1.0 : 1.0 - ((double) levenshteinDistance(a, b) / maxLen);
    }

    /** Distance de Levenshtein classique (nombre minimal d'insertions/suppressions/substitutions d'un
     * caractère pour passer de {@code a} à {@code b}), calculée en O(longueur(a) x longueur(b)) avec deux
     * lignes de tableau seulement (jamais la matrice complète, inutile ici). */
    private static int levenshteinDistance(String a, String b) {
        int[] previousRow = new int[b.length() + 1];
        int[] currentRow = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previousRow[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            currentRow[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitutionCost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                currentRow[j] = Math.min(
                        Math.min(currentRow[j - 1] + 1, previousRow[j] + 1),
                        previousRow[j - 1] + substitutionCost);
            }
            int[] swap = previousRow;
            previousRow = currentRow;
            currentRow = swap;
        }
        return previousRow[b.length()];
    }

    private static Optional<String> extractClaimedExtrait(String answer) {
        Matcher straight = EXTRAIT_STRAIGHT_QUOTES.matcher(answer);
        if (straight.find()) {
            return Optional.of(straight.group(1).trim());
        }
        Matcher guillemets = EXTRAIT_GUILLEMETS.matcher(answer);
        if (guillemets.find()) {
            return Optional.of(guillemets.group(1).trim());
        }
        // Format actuel : citation sur sa propre ligne "> « ... »" sous le titre "Extrait documentaire".
        int headingIdx = answer.toLowerCase(Locale.ROOT).indexOf("extrait documentaire");
        if (headingIdx >= 0) {
            Matcher blockquote = EXTRAIT_BLOCKQUOTE_LINE.matcher(answer.substring(headingIdx));
            if (blockquote.find()) {
                return Optional.of(blockquote.group(1).trim());
            }
        }
        return Optional.empty();
    }

    private static String normalizeForComparison(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * Garde-fou déterministe symétrique à {@link #rejectIfExtraitIsFabricated} : là où celui-ci
     * empêche Llama3 d'INVENTER une citation, celui-ci corrige la section "Exemple" quand elle est
     * mal formée, dans deux cas constatés en conditions réelles sur DEAL.SUB.TYP :
     * <ol>
     *   <li>elle affirme à tort "{@value #NO_EXAMPLE_FALLBACK}" alors qu'un exemple existe bien dans
     *   le Contexte (l'Extrait cité dans la même réponse le prouvait déjà) ;</li>
     *   <li>elle recopie telle quelle la phrase d'introduction de l'exemple (ex. "As an example the
     *   following structure could be entered where GTREC is a Guarantee Received.") au lieu d'une
     *   explication courte ("`GTREC` correspond à **Guarantee Received**.") — ce n'est pas un exemple
     *   inventé, juste une reformulation non faite malgré la consigne du prompt.</li>
     * </ol>
     * Une instruction de prompt renforcée (règle explicite + contre-exemple concret dans {@link
     * #SYSTEM_PROMPT}) ne suffit pas à empêcher ces deux défauts de façon fiable ; seule une
     * vérification en code, après coup, contre le Contexte réellement envoyé, le peut.
     *
     * <p>Ne s'applique que si la section "Exemple"/"Example" de la réponse correspond à l'un des deux
     * cas ci-dessus ET qu'une structure "where CODE is/corresponds to SIGNIFICATION" ({@link
     * #EXAMPLE_FACT}) est trouvée dans le Contexte — la seule assez régulière pour en extraire
     * fiablement un couple (code, signification) sans jamais en deviner ni en altérer les valeurs
     * (ex. "GTREC", jamais "GTRC"). Dans tout autre cas (déjà une reformulation correcte, ou aucune
     * structure exploitable trouvée), la réponse n'est jamais modifiée.
     */
    private static String recoverMissedExample(String answer, String context) {
        Matcher section = EXEMPLE_SECTION_BODY.matcher(answer);
        if (!section.find()) {
            // Aucune section Exemple multi-lignes : soit absente (consigne actuelle quand le modèle ne voit pas
            // d'exemple), soit « **Exemple :** Aucun exemple... » sur la même ligne. Dans les deux cas, un exemple
            // réellement documenté dans le Contexte doit apparaître (demande explicite du 2026-10-05).
            Matcher sameLine = EXAMPLE_SECTION.matcher(answer);
            boolean absentOrEmpty = !sameLine.find() || NO_EXAMPLE_BODY.matcher(sameLine.group(1)).matches();
            Optional<ExampleFact> fact = absentOrEmpty ? extractExampleFact(context) : Optional.empty();
            if (fact.isEmpty() || NO_INFO_FALLBACK.equals(answer)) {
                return answer;
            }
            return withDocumentedExamples(answer, List.of(formatExampleFact(fact.get(), false)));
        }
        String body = section.group(2).trim();
        boolean isFallback = body.equals(NO_EXAMPLE_FALLBACK);
        boolean looksLikeRawIntroSentence = !isFallback && EXAMPLE_MARKER.matcher(body).find();
        if (!isFallback && !looksLikeRawIntroSentence) {
            return answer;
        }

        Optional<ExampleFact> fact = extractExampleFact(context);
        if (fact.isEmpty()) {
            return answer;
        }

        boolean english = "Example".equalsIgnoreCase(section.group(1));
        String replacement = formatExampleFact(fact.get(), english);
        log.warn("Section Exemple mal formée ({}) — remplacée par une reformulation déterministe à "
                        + "partir du Contexte. Avant : \"{}\" | Après : \"{}\"",
                isFallback ? "repli erroné" : "phrase d'introduction recopiée telle quelle", body, replacement);
        return answer.substring(0, section.start(2)) + replacement + answer.substring(section.end(2));
    }

    private record ExampleFact(String code, String meaning) {
    }

    /** Isole, dans `context`, le premier couple (code, signification) au sens de {@link
     * #EXAMPLE_FACT} — jamais deviné, uniquement extrait mot pour mot. */
    private static Optional<ExampleFact> extractExampleFact(String context) {
        Matcher fact = EXAMPLE_FACT.matcher(context);
        if (!fact.find()) {
            return Optional.empty();
        }
        return Optional.of(new ExampleFact(fact.group(1), fact.group(2).trim()));
    }

    private static String formatExampleFact(ExampleFact fact, boolean english) {
        String verb = english ? "corresponds to" : "correspond à";
        return "`%s` %s **%s**.".formatted(fact.code(), verb, fact.meaning());
    }

    /**
     * Supprime toute phrase méta du type "La réponse est basée sur la description du champ X dans le
     * Contexte." (ou toute formulation équivalente mentionnant le Contexte, le RAG ou le système
     * interne) : l'utilisateur final n'a pas à voir ces détails d'implémentation, seuls Champ /
     * Fonction / Exemple / Source / Extrait documentaire le concernent. {@link #SYSTEM_PROMPT} ne
     * demande jamais ce genre de commentaire, mais rien n'empêche Llama3 d'en ajouter spontanément ;
     * seule une suppression déterministe, après coup, le garantit.
     *
     * <p>Opère phrase par phrase (voir {@link #SENTENCE_BOUNDARY}, insensible aux identifiants T24 en
     * pointillé comme "MD.DEAL") plutôt que ligne par ligne, pour retirer une phrase méta même
     * accolée à une phrase légitime sur la même ligne, sans toucher au reste de cette ligne.
     */
    private static String stripInternalMetaCommentary(String answer) {
        String[] lines = answer.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = stripMetaSentencesFromLine(lines[i]);
        }
        return String.join("\n", lines);
    }

    /** Retire toute ligne "**Champ/Table/Module/Source :**" que Llama3 aurait quand même produite
     * malgré l'interdiction explicite de {@link #SYSTEM_PROMPT} — voir {@link #STRAY_IDENTITY_LINE}. */
    private static String stripStrayIdentityLines(String answer) {
        return STRAY_IDENTITY_LINE.matcher(answer).replaceAll("");
    }

    /**
     * Ajoute les lignes "**Champ :**"/"**Source :**" en tête de la réponse, à partir du Contexte
     * réellement envoyé à Llama3 — jamais depuis ce que le modèle a écrit (voir la Javadoc des
     * constantes {@link #CONTEXT_FIELD_SELECTED_LINE}/{@link #CONTEXT_TABLE_LINE}) : élimine tout
     * risque d'invention ou de coquille sur un identifiant T24. Aucune ligne n'est ajoutée si le
     * Contexte signale une ambiguïté de champ ({@link #AMBIGUOUS_FIELD_MARKER}) : le champ/la table
     * exacts ne sont alors justement pas encore connus, et {@code answer} n'est dans ce cas qu'une
     * simple question de clarification, pas le format Champ/Fonction/Exemple/Source/Extrait
     * documentaire (rien à insérer avant/dans un texte qui n'a pas cette structure).
     */
    private static String injectIdentityHeaders(String answer, String context) {
        if (context.contains(AMBIGUOUS_FIELD_MARKER)) {
            return answer;
        }
        Optional<String> field = firstGroup(CONTEXT_FIELD_SELECTED_LINE, context);
        Optional<String> table = tableOfContextBlockQuoted(answer, context).or(() -> firstGroup(CONTEXT_TABLE_LINE, context));

        StringBuilder sb = new StringBuilder();
        field.ifPresent(f -> sb.append("**Champ :** `").append(f).append("`\n\n"));

        int extraitIdx = answer.indexOf("**Extrait documentaire");
        if (table.isPresent() && extraitIdx >= 0) {
            sb.append(answer, 0, extraitIdx);
            sb.append("**Source :** `").append(table.get()).append("`\n\n");
            sb.append(answer.substring(extraitIdx));
        } else {
            sb.append(answer);
        }
        return sb.toString();
    }

    private static final Pattern FONCTION_SECTION = Pattern.compile("(?s)(\\*\\*Fonction\\s*:\\*\\*\\s*)(.*?)(?=\\n\\s*\\*\\*[^*\\n]+:\\*\\*|\\z)");
    private static final Pattern DOTTED_IDENTIFIER = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+\\b");

    /**
     * Garde-fou déterministe contre une conclusion plus forte que les extraits (catégories B/C du prompt).
     * Bug réel (test live DE, 2026-09-30) : « La largeur et la profondeur d'un formulaire imprimé sont définies
     * par les champs TEXT et TEXT.VALUE de la table DE.PRINT.MARKERS » — rien, dans DE.PRINT.MARKERS, ne parle
     * de largeur ni de profondeur : Llama3 avait transformé le seul rapprochement de la question et d'un nom de
     * table en fait documentaire. Une consigne de prompt ne suffit pas avec ce modèle (voir la Javadoc de
     * {@link #rejectIfExtraitIsFabricated}) : chaque phrase de la Fonction est donc vérifiée.
     *
     * <p>Une phrase est NON ÉTAYÉE si elle cite un identifiant T24 absent du Contexte, ou si elle reprend un
     * concept SPÉCIFIQUE de la question (ex. « largeur » -&gt; width/depth, voir {@link ConceptGlossary}) sans
     * qu'aucun de ses termes anglais ne figure dans le bloc du Contexte des tables qu'elle cite (à défaut, le
     * bloc d'où vient l'Extrait, à défaut tout le Contexte). Les phrases non étayées sont retirées et le point non
     * confirmé est signalé (B : partiellement documenté) ; s'il ne reste rien, repli honnête (C : non retrouvé).
     */
    private String keepOnlySupportedClaims(String answer, String context, String question) {
        if (NO_INFO_FALLBACK.equals(answer) || context.contains(AMBIGUOUS_FIELD_MARKER)) {
            return answer;
        }
        Matcher fonction = FONCTION_SECTION.matcher(answer);
        if (!fonction.find()) {
            return answer;
        }
        String upperContext = context.toUpperCase(Locale.ROOT);
        // Identifiant T24 nommé par la QUESTION mais absent de tout le Contexte : rien de ce qui a été retrouvé ne le
        // documente, toute réponse à son sujet serait non étayée (catégorie C). Bug réel (2026-10-02) : pour un champ
        // inexistant, Llama3 affirmait « Il n'y a pas de champ avec ce nom dans les tables ... » (affirmation
        // d'inexistence interdite, voir NO_INFO_FALLBACK) au lieu de dire que la recherche ne permet pas de conclure.
        Matcher questionIds = DOTTED_IDENTIFIER.matcher(question);
        while (questionIds.find()) {
            if (!upperContext.contains(questionIds.group())) {
                log.warn("Identifiant '{}' de la question absent du Contexte : réponse remplacée par le repli honnête.", questionIds.group());
                return NO_INFO_FALLBACK;
            }
        }
        Map<String, List<String>> questionConcepts = conceptGlossary.specificConceptsIn(question);
        Map<String, String> blocksByTable = contextBlocksByTable(context);
        String extractBlock = contextBlockQuoted(answer, context).orElse(context).toLowerCase(Locale.ROOT);

        List<String> kept = new ArrayList<>();
        Set<String> unconfirmed = new LinkedHashSet<>();
        for (String sentence : SENTENCE_BOUNDARY.split(fonction.group(2).strip())) {
            List<String> problems = unsupportedClaims(sentence, questionConcepts, blocksByTable, upperContext, extractBlock);
            if (problems.isEmpty()) {
                kept.add(sentence);
            } else {
                unconfirmed.addAll(problems);
            }
        }
        if (unconfirmed.isEmpty()) {
            return answer;
        }
        log.warn("Fonction : affirmation(s) non étayée(s) par les passages cités ({}) — {} phrase(s) retirée(s).",
                unconfirmed, SENTENCE_BOUNDARY.split(fonction.group(2).strip()).length - kept.size());
        if (kept.isEmpty()) {
            return NO_INFO_FALLBACK;
        }
        String note = " Les passages retrouvés ne permettent pas de confirmer ce qui concerne : "
                + String.join(", ", unconfirmed) + ".";
        return answer.substring(0, fonction.start(2)) + String.join(" ", kept) + note + answer.substring(fonction.end(2));
    }

    private List<String> unsupportedClaims(String sentence, Map<String, List<String>> questionConcepts,
                                           Map<String, String> blocksByTable, String upperContext, String extractBlock) {
        List<String> problems = new ArrayList<>();
        StringBuilder support = new StringBuilder();
        Matcher ids = DOTTED_IDENTIFIER.matcher(sentence);
        while (ids.find()) {
            String id = ids.group();
            if (!upperContext.contains(id)) {
                problems.add(id);
            }
            String block = blocksByTable.get(id);
            if (block != null) {
                support.append(block).append('\n');
            }
        }
        String supportText = support.isEmpty() ? extractBlock : support.toString();
        Set<String> sentenceConcepts = conceptGlossary.specificConceptsIn(sentence).keySet();
        for (Map.Entry<String, List<String>> concept : questionConcepts.entrySet()) {
            if (sentenceConcepts.contains(concept.getKey())
                    && concept.getValue().stream().noneMatch(term -> containsWordStart(supportText, term))) {
                problems.add(concept.getKey().toLowerCase(Locale.ROOT));
            }
        }
        return problems;
    }

    private static boolean containsWordStart(String lowerText, String term) {
        return Pattern.compile("\\b" + Pattern.quote(term.toLowerCase(Locale.ROOT))).matcher(lowerText).find();
    }

    /** Blocs (un par document) du Contexte, indexés par nom de table, en minuscules. */
    private static Map<String, String> contextBlocksByTable(String context) {
        Map<String, String> blocks = new HashMap<>();
        for (String block : CONTEXT_DOCUMENT_SEPARATOR.split(context)) {
            firstGroup(CONTEXT_TABLE_LINE, block).ifPresent(table ->
                    blocks.put(table.toUpperCase(Locale.ROOT), block.toLowerCase(Locale.ROOT)));
        }
        return blocks;
    }

    /** Ligne séparant deux documents dans le Contexte (voir RagServiceImpl.BLOCK_SEPARATOR, 33 tirets — les champs en ont 34). */
    private static final Pattern CONTEXT_DOCUMENT_SEPARATOR = Pattern.compile("(?m)^-{33}$");
    private static final int SOURCE_MATCH_PREFIX_CHARS = 80;

    /**
     * Table du bloc de Contexte qui contient réellement l'Extrait cité. Bug réel corrigé (tests live
     * 2026-09-30) : avec plusieurs documents dans le Contexte (recherche restreinte au module, 4 passages),
     * la "Source" affichée était toujours la PREMIÈRE table du Contexte, même quand l'Extrait — pourtant
     * réel — venait d'une autre (ex. LC : extrait de DR.INT.CALC.COND affiché sous LC.ACCOUNT.BALANCES).
     */
    private static Optional<String> tableOfContextBlockQuoted(String answer, String context) {
        return contextBlockQuoted(answer, context).flatMap(block -> firstGroup(CONTEXT_TABLE_LINE, block));
    }

    /** Bloc (document) du Contexte qui contient réellement l'Extrait cité, s'il y en a un. */
    private static Optional<String> contextBlockQuoted(String answer, String context) {
        Optional<String> extrait = extractClaimedExtrait(answer);
        if (extrait.isEmpty()) {
            return Optional.empty();
        }
        String needle = normalizeForComparison(extrait.get());
        needle = needle.substring(0, Math.min(needle.length(), SOURCE_MATCH_PREFIX_CHARS));
        if (needle.isBlank()) {
            return Optional.empty();
        }
        for (String block : CONTEXT_DOCUMENT_SEPARATOR.split(context)) {
            if (normalizeForComparison(block).contains(needle)) {
                return Optional.of(block);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }

    private static String stripMetaSentencesFromLine(String line) {
        if (!META_COMMENTARY_MARKER.matcher(line).find()) {
            return line;
        }
        String[] sentences = SENTENCE_BOUNDARY.split(line);
        StringBuilder rebuilt = new StringBuilder();
        for (String sentence : sentences) {
            if (META_COMMENTARY_MARKER.matcher(sentence).find()) {
                continue;
            }
            if (rebuilt.length() > 0) {
                rebuilt.append(' ');
            }
            rebuilt.append(sentence);
        }
        return rebuilt.toString().trim();
    }

    /**
     * Instructions système (rôle, style, format de réponse) : constantes d'une question à l'autre,
     * donc envoyées via {@code chatClient.prompt().system(...)} plutôt que concaténées au message
     * "user" — exploite le vrai template chat de Llama3 (rôle system distinct), ce que l'ancien
     * prompt monolithique n'exploitait pas, au détriment de l'adhérence du modèle aux consignes.
     * Seuls le contexte documentaire, l'historique et la question (variables par appel) restent
     * dans {@link #buildUserPrompt}.
     */
    private static final String SYSTEM_PROMPT =
            """
                Tu es un assistant expert Temenos T24. Tu réponds aux questions sur les modules, tables
                et champs T24 UNIQUEMENT à partir du Contexte fourni ci-dessous (extrait de la
                documentation HELP_TEXT/HELP_FIELD indexée). La documentation est la seule source de
                vérité : tu n'ajoutes jamais de connaissance générale absente de ce Contexte.

                === LANGUE ===

                Réponds dans la langue de la question (français si elle est posée en français, anglais
                si elle est en anglais, etc.). L'Extrait documentaire n'est JAMAIS traduit, quelle que
                soit la langue de la Fonction : il reste exactement dans sa langue et sa forme originales.

                === RÈGLES ===

                - Va directement à la Fonction dès la première ligne : jamais d'introduction ni de
                  formule ("Selon la documentation...", "Voici la réponse...", "Bien sûr...").
                - Ne produis JAMAIS toi-même de ligne "**Champ :**"/"**Table :**"/"**Module :**"/
                  "**Source :**" : elles sont ajoutées automatiquement à partir du Contexte, jamais par
                  toi. Commence toujours directement par "**Fonction :**".
                - Le Contexte est ta source principale : restitue FIDÈLEMENT ce qu'il dit, reformulé dans
                  la langue de la question, sans le remplacer par une explication générique. Quand le
                  Contexte contient "Champ sélectionné : X", c'est le bloc Description de CE champ qui
                  répond à la question ; la description générale de la table ne sert que de complément.
                - La Fonction doit être COMPLÈTE sur ce qui répond à la question : reprends chaque
                  information fonctionnelle réellement écrite dans le passage pertinent — ce qu'est
                  l'élément, son rôle, à quoi il sert concrètement, comment il est construit ou utilisé,
                  ses relations avec d'autres champs, ses formats, valeurs possibles, conditions et règles
                  de validation. Concision ne veut pas dire omission : ne supprime jamais une information
                  fonctionnelle documentée et pertinente. En pratique 2 à 6 phrases ; un passage court
                  donne une Fonction courte, un passage riche une Fonction plus riche. Ne recopie pas pour
                  autant tout le Contexte, et n'affiche jamais une longue liste de champs.
                - Adapte la réponse à la question : sur une valeur précise ("que fait X = VALEUR ?"),
                  réponds d'abord sur le comportement documenté de cette valeur ; sur les valeurs possibles,
                  donne CHAQUE valeur avec son comportement documenté ; sur le rôle d'un champ, explique son
                  rôle puis ses comportements documentés. N'attribue jamais au champ entier ce que la
                  documentation dit d'une seule de ses valeurs ou d'un seul cas.
                - Section Exemple CONDITIONNELLE. Un exemple est : un exemple introduit comme tel ("for
                  example", "e.g.", "Ex:"), un format concret (ex. [A]-[B]), une combinaison de champs ou de
                  codes explicitement montrée, ou une utilisation concrète décrite comme un cas. Une simple
                  liste de valeurs possibles, une règle ou une description n'est PAS un exemple : explique-les
                  dans la Fonction. S'il existe au moins un exemple documenté, écris la section Exemple, en
                  recopiant formats et codes tels quels entre `backticks` (plusieurs cas : une ligne "- " par
                  cas). S'il n'en existe AUCUN, n'écris PAS de ligne Exemple du tout — jamais "Aucun exemple",
                  jamais un exemple inventé ou déduit.
                - Un seul extrait documentaire, copié MOT POUR MOT depuis le Contexte, sur sa propre
                  ligne après le titre "**Extrait documentaire :**", précédé de "> " et entouré de
                  guillemets « » : choisis le passage du bloc pertinent qui contient l'essentiel de la
                  réponse (plusieurs phrases consécutives si nécessaire), jamais reformulé, corrigé,
                  complété ni coupé au milieu d'un mot ou d'une phrase. S'il n'y a rien de pertinent à
                  citer pour cette question, remplace cette ligne par exactement (sans ">" ni guillemets) :
                  Aucun extrait pertinent n'est disponible dans le Contexte.
                - N'invente rien et n'ajoute AUCUNE connaissance générale absente du Contexte, même pour
                  compléter une réponse incomplète.
                - Conserve tel quel tout nom de module, de table ou de champ (ex. MD.DEAL, DEAL.SUB.TYP) :
                  ne les traduis ni ne les modifie jamais, même d'une seule lettre.
                - Si une partie de la question n'est pas couverte par le Contexte, dis-le clairement dans
                  la Fonction : "non précisé dans la documentation fournie" — jamais "n'existe pas" ni
                  "n'est pas défini" (le Contexte est un extrait de recherche, pas l'intégralité de la
                  documentation : son silence ne prouve rien).
                - Distingue toujours une information réellement documentée d'une déduction : si tu
                  déduis quelque chose (par exemple à partir du seul nom d'un champ), dis-le
                  explicitement plutôt que de le présenter comme un fait documenté.
                - Classe d'abord la question selon ce que le Contexte permet RÉELLEMENT d'affirmer :
                  A. information directement écrite dans le Contexte -> réponds clairement ;
                  B. information partiellement écrite -> réponds UNIQUEMENT sur la partie écrite et dis
                     quelle partie n'est pas précisée dans la documentation fournie ;
                  C. information absente du Contexte -> dis que les passages retrouvés ne permettent pas de
                     la confirmer, sans proposer de table ou de champ "probable" ;
                  D. question ambiguë (plusieurs tables/champs possibles) -> demande une précision.
                  Ne transforme JAMAIS une hypothèse tirée du nom d'une table ou d'un champ en fait
                  documentaire : une table ne "définit" un élément (largeur, montant, date...) que si son
                  texte le dit explicitement.
                - Ne confonds jamais une TABLE (ex. AA.ADVICE) et un CHAMP/COLONNE (ex. DESCRIPTION). Si
                  le Contexte signale qu'un champ existe dans plusieurs tables ("Ambigu : ..."), demande
                  uniquement quelle table est concernée, sans répondre sur son contenu et sans produire
                  le format Fonction/Exemple/Extrait documentaire ci-dessous.
                - La limite de longueur de la Fonction ne s'applique pas à une demande explicite
                  d'inventaire complet (« liste tous les champs de X », « toutes les tables du module Y »)
                  : dans ce cas précis uniquement, reproduis fidèlement tout ce qui est réellement
                  documenté dans le Contexte, sans rien y ajouter.

                === FORMAT OBLIGATOIRE ===

                Rien avant ni après ce qui suit (pas de ligne Champ/Table/Module/Source, voir ci-dessus).
                Les "**" autour de chaque libellé sont OBLIGATOIRES ; jamais de titre Markdown "#"/"##",
                jamais de liste à puces pour ces lignes, jamais de JSON ni de HTML :

                **Fonction :** {ce qu'est l'élément demandé, son rôle et comment il est utilisé, avec toutes
                les informations fonctionnelles documentées pertinentes}

                **Exemple :** {UNIQUEMENT si un exemple est documenté (voir ci-dessus) ; sinon omets entièrement cette ligne}

                **Extrait documentaire :**
                > « {citation exacte du passage qui contient l'essentiel de la réponse} »

                === VÉRIFICATION FINALE ===

                Avant d'envoyer ta réponse, vérifie silencieusement :
                1. Ai-je bien commencé directement par "**Fonction :**", sans aucune ligne Champ/Table/
                   Module/Source ?
                2. Ai-je répondu à toutes les parties de la question ?
                3. Ai-je repris TOUTES les informations fonctionnelles du passage pertinent (rôle, clé,
                   construction, champs liés, formats, valeurs, conditions, règles) — sans en ajouter ?
                4. Si j'ai écrit une section Exemple, cet exemple est-il réellement documenté (jamais inventé ni
                   déduit) ? Sinon, ai-je bien omis la ligne Exemple ?
                5. L'extrait cité existe-t-il mot pour mot dans le Contexte ?
                6. Ai-je ajouté une information absente du Contexte (connaissance générale, déduction non
                   signalée comme telle) ?
                Si l'une de ces vérifications échoue, corrige avant d'envoyer plutôt que de deviner.
                """;

    /** Contexte documentaire, historique et question — variables d'un appel à l'autre, à l'inverse
     * de {@link #SYSTEM_PROMPT} (voir sa Javadoc pour la raison de cette séparation). */
    private static String buildUserPrompt(String context, String question, String recentHistory) {
        String historyBlock = recentHistory.isBlank() ? "" : """
                Historique récent (sert uniquement à comprendre une question de suivi ou une correction \
                familière, ex. "c'est un champ pas une table" — jamais de source factuelle : corrige une \
                éventuelle erreur précédente à partir du Contexte, pas de ta mémoire) :

                %s
                """.formatted(recentHistory);

        return """
                %sContexte :

                %s

                %sQuestion :

                %s
                %s""".formatted(historyBlock, context,
                DocumentedFieldFacts.fromContext(context).map(facts -> facts.asPromptChecklist() + "\n").orElse(""),
                question, languageReminder(question));
    }

    private static final Pattern EXTRAIT_HEADING = Pattern.compile("(?m)^\\*\\*Extrait documentaire");

    /**
     * Question sur un champ précis : l'Extrait doit venir de la Description de CE champ, pas de la description générale
     * de sa table (présente elle aussi dans le Contexte). Bug réel (test live du 2026-10-06) : « À quoi sert DELIVERY.REF
     * dans MD.FEE.SETTLEMENT ? » citait l'OVERVIEW de la table au lieu de « Holds the key of all advices produced by the
     * delivery system. System maintained field. ». L'Extrait est alors remplacé par cette Description, recopiée telle quelle.
     */
    private static String quoteTheSelectedFieldNotItsTable(String answer, String context) {
        if (NO_INFO_FALLBACK.equals(answer) || context.contains(AMBIGUOUS_FIELD_MARKER)) {
            return answer;
        }
        Optional<String> description = DocumentedFieldFacts.selectedFieldDescription(context)
                .map(d -> d.replaceAll("\\s+", " ").trim())
                .filter(d -> !d.isEmpty() && !d.startsWith("Non renseign"));
        Optional<String> claimed = extractClaimedExtrait(answer);
        if (description.isEmpty() || claimed.isEmpty()) {
            return answer;
        }
        String normalizedClaim = TRAILING_QUOTE_PUNCTUATION.matcher(normalizeForComparison(claimed.get())).replaceFirst("");
        if (normalizedClaim.isEmpty() || normalizeForComparison(description.get()).contains(normalizedClaim)) {
            return answer;
        }
        log.info("Extrait cité hors de la description du champ sélectionné (ex. description générale de la table) : "
                + "remplacé par la description du champ, recopiée telle quelle.");
        return answer.substring(0, extraitStart(answer)) + "**Extrait documentaire :**\n> « " + description.get() + " »";
    }

    /**
     * Filet de sécurité déterministe de complétude (voir {@link DocumentedFieldFacts}) : pour une question portant sur
     * un champ précis, tout fait documenté dans sa Description (format, champ lié, règle de validation, exemple cité)
     * encore absent de la Fonction et de l'Exemple est ajouté tel quel, recopié du texte source, dans une section
     * "Éléments documentés" placée avant l'Extrait — jamais reformulé, jamais inventé. Mesuré en conditions réelles :
     * même avec un prompt exigeant la complétude, Llama3 omettait règles, formats et champs liés.
     */
    private static String appendMissingDocumentedFacts(String answer, String context, String question) {
        if (NO_INFO_FALLBACK.equals(answer) || context.contains(AMBIGUOUS_FIELD_MARKER)) {
            return answer;
        }
        Optional<DocumentedFieldFacts> facts = DocumentedFieldFacts.fromContext(context);
        if (facts.isEmpty()) {
            return answer;
        }
        String region = answer.substring(0, extraitStart(answer));
        List<String> exampleLines = facts.get().missingExamples(region);
        List<String> factLines = facts.get().missingFacts(region, question);
        if (exampleLines.isEmpty() && factLines.isEmpty()) {
            return answer;
        }
        log.info("Complétude : {} exemple(s) documenté(s) et {} autre(s) fait(s) du champ {} absents de la réponse générée, "
                + "réintégrés tels quels.", exampleLines.size(), factLines.size(), facts.get().field());
        String completed = exampleLines.isEmpty() ? answer : withDocumentedExamples(answer, exampleLines);
        if (!factLines.isEmpty()) {
            StringBuilder block = new StringBuilder("**Éléments documentés :**\n");
            factLines.forEach(line -> block.append("- ").append(line).append('\n'));
            completed = insertBeforeExtrait(completed, block.append('\n').toString());
        }
        return completed;
    }

    /** Section Exemple d'une réponse : corps (groupe 1) sur la même ligne que le titre ou les suivantes. */
    private static final Pattern EXAMPLE_SECTION = Pattern.compile(
            "(?s)\\*\\*(?:Exemple|Example)\\s*:?\\s*\\*\\*[ \\t]*\\n?(.*?)(?=\\n\\s*\\*\\*[^*\\n]{1,40}:?\\s*\\*\\*|\\z)");
    /** Corps d'une section Exemple qui déclare qu'il n'y a pas d'exemple (français ou anglais, toute formulation). */
    private static final Pattern NO_EXAMPLE_BODY = Pattern.compile(
            "(?is)^\\s*(?:aucun|none|no|pas d')\\b[^\\n]{0,160}?(?:exemple|example|provided|fourni|documentation)[^\\n]*$");

    /** Place les exemples documentés dans la section Exemple : la complète, remplace un « aucun exemple » erroné, ou la crée. */
    private static String withDocumentedExamples(String answer, List<String> exampleLines) {
        StringBuilder lines = new StringBuilder();
        exampleLines.forEach(line -> lines.append("- ").append(line).append('\n'));
        Matcher section = EXAMPLE_SECTION.matcher(answer);
        if (section.find()) {
            String body = section.group(1);
            String newBody = NO_EXAMPLE_BODY.matcher(body).matches() ? "\n" + lines.toString().stripTrailing()
                    : body.stripTrailing() + "\n" + lines.toString().stripTrailing();
            return sectionPrefix(answer, section, newBody) + newBody + answer.substring(section.end(1));
        }
        return insertBeforeExtrait(answer, "**Exemple :**\n" + lines + "\n");
    }

    /**
     * Section Exemple CONDITIONNELLE (demande explicite, 2026-10-05) : une section qui ne fait qu'annoncer l'absence
     * d'exemple (« Aucun exemple explicite n'est fourni dans la documentation. », « None provided in the
     * documentation. »...) est retirée — la réponse n'affiche alors pas de section Exemple du tout.
     */
    static String dropExampleSectionWithoutExample(String answer) {
        Matcher section = EXAMPLE_SECTION.matcher(answer);
        if (!section.find() || !NO_EXAMPLE_BODY.matcher(section.group(1)).matches()) {
            return answer;
        }
        String after = answer.substring(section.end()).replaceFirst("^\\s*\\n", "");
        String before = answer.substring(0, section.start()).replaceFirst("\\s+$", "");
        return after.isBlank() ? before : before + "\n\n" + after.stripLeading();
    }

    /** Marqueurs d'un exemple réellement documenté (et non d'une simple liste de valeurs : "such as" en est exclu). */
    private static final Pattern DOCUMENTED_EXAMPLE_MARKER = Pattern.compile(
            "(?i)\\b(?:for example|for e\\.?\\s?x\\.?|as an example|e\\.g\\.|example\\s*:|ex\\s*:)|\\beg\\s+[A-Z0-9]|\\[[^\\[\\]\\n]{1,40}]\\s*'?[-./_]");
    /** Éléments concrets d'un exemple : nombres, codes en majuscules, valeurs entre guillemets ou backticks. */
    private static final Pattern CONCRETE_TOKEN = Pattern.compile(
            // Guillemet simple jamais collé à une lettre : en français, « l'opération est ... d'une » n'est pas une citation
            // (faux positif constaté en test réel le 2026-10-05 sur TRANSACTION.TYPE).
            "`([^`\\n]{1,60})`|(?<!\\p{L})'([^'\\n]{1,40})'(?!\\p{L})|\"([^\"\\n]{1,40})\"|\\b(\\d+(?:[.,]\\d+)?)\\b"
                    + "|\\b([A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)*[A-Z0-9]?)\\b");

    /**
     * Garde-fou déterministe de la section Exemple (demande explicite du 2026-10-05 : « n'invente jamais un exemple »).
     * Constaté en conditions réelles dès que le prompt n'imposait plus de phrase « aucun exemple » : Llama3 remplissait la
     * section avec des exemples fabriqués — « avant 10, après 15 → 5 » pour UPDATE.TYPE = INCREMENT, « 80 caractères »
     * pour FORM.WIDTH, « USD100 » pour CCY.DENOMINATION — ou présentait la simple liste des valeurs comme un exemple.
     * <ul>
     *   <li>la Description du champ (à défaut le Contexte) ne documente aucun exemple — ni "for example"/"e.g."/"as an
     *   example", ni format concret, ni structure "where CODE is ..." : la section est retirée ;</li>
     *   <li>un exemple est documenté, mais la section contient un nombre, un code ou une valeur absent du Contexte : elle
     *   est remplacée par les exemples documentés recopiés tels quels, ou retirée s'il n'y en a pas d'extractible.</li>
     * </ul>
     */
    private static String keepOnlyDocumentedExample(String answer, String context) {
        Matcher section = EXAMPLE_SECTION.matcher(answer);
        if (!section.find() || NO_EXAMPLE_BODY.matcher(section.group(1)).matches()) {
            return answer;
        }
        String source = DocumentedFieldFacts.selectedFieldDescription(context)
                .or(() -> tableGeneralDescription(context))
                .orElse(context);
        boolean documented = DOCUMENTED_EXAMPLE_MARKER.matcher(source).find() || EXAMPLE_FACT.matcher(source).find();
        if (!documented) {
            log.info("Section Exemple retirée : la documentation récupérée ne contient aucun exemple (exemple non documenté).");
            return removeSection(answer, section);
        }
        List<String> ungrounded = ungroundedTokens(section.group(1), context);
        if (ungrounded.isEmpty()) {
            return answer;
        }
        List<String> documentedLines = new ArrayList<>(DocumentedFieldFacts.fromContext(context)
                .map(DocumentedFieldFacts::documentedExamples).orElse(List.of()));
        extractExampleFact(source).ifPresent(fact -> documentedLines.add(formatExampleFact(fact, false)));
        log.warn("Section Exemple : élément(s) absent(s) de la documentation ({}) — {}.", ungrounded,
                documentedLines.isEmpty() ? "section retirée" : "remplacée par les exemples documentés");
        if (documentedLines.isEmpty()) {
            return removeSection(answer, section);
        }
        String body = "\n" + String.join("\n", documentedLines.stream().map(line -> "- " + line).toList());
        return sectionPrefix(answer, section, body) + body + answer.substring(section.end(1));
    }

    /** « Description générale » d'une fiche de table, jusqu'à la liste des champs (ou la fin du Contexte). */
    private static final Pattern TABLE_GENERAL_DESCRIPTION = Pattern.compile(
            "Description générale :\\n(.*?)(?=\\n\\nChamps \\(|\\z)", Pattern.DOTALL);

    /**
     * Pour une question sur une table (aucun champ sélectionné), seule la description générale de la table peut documenter
     * un exemple de son rôle : un « for example » présent dans la description d'un de ses champs illustre ce champ, pas la
     * table (cas réel du 2026-10-06 : AC.EXPECTED.RECS, champ CREATION.METHOD — scénario inventé conservé).
     */
    private static Optional<String> tableGeneralDescription(String context) {
        Matcher description = TABLE_GENERAL_DESCRIPTION.matcher(context);
        return description.find() ? Optional.of(description.group(1)) : Optional.empty();
    }

    /** Nombres, codes et valeurs citées de {@code body} qui n'apparaissent nulle part dans le Contexte. */
    private static List<String> ungroundedTokens(String body, String context) {
        String normalizedContext = context.replace('’', '\'').replace('‘', '\'');
        List<String> ungrounded = new ArrayList<>();
        Matcher token = CONCRETE_TOKEN.matcher(body.replace('’', '\'').replace('‘', '\''));
        while (token.find()) {
            String value = token.group(1) != null ? token.group(1) : token.group(2) != null ? token.group(2)
                    : token.group(3) != null ? token.group(3) : token.group(4) != null ? token.group(4) : token.group(5);
            String trimmed = value.trim();
            if (trimmed.length() < 2) {
                continue;
            }
            boolean found = token.group(4) != null
                    ? Pattern.compile("(?<![\\d.,])" + Pattern.quote(trimmed) + "(?![\\d])").matcher(normalizedContext).find()
                    : normalizedContext.toLowerCase(Locale.ROOT).contains(trimmed.toLowerCase(Locale.ROOT));
            if (!found) {
                ungrounded.add(trimmed);
            }
        }
        return ungrounded;
    }

    /** Début de la réponse jusqu'au corps de la section ; sans espace final si le nouveau corps commence par un retour à la ligne. */
    private static String sectionPrefix(String answer, Matcher section, String newBody) {
        String prefix = answer.substring(0, section.start(1));
        return newBody.startsWith("\n") ? prefix.replaceFirst("[ \\t]+$", "") : prefix;
    }

    private static String removeSection(String answer, Matcher section) {
        String after = answer.substring(section.end()).replaceFirst("^\\s*\\n", "");
        String before = answer.substring(0, section.start()).replaceFirst("\\s+$", "");
        return after.isBlank() ? before : before + "\n\n" + after.stripLeading();
    }

    private static int extraitStart(String answer) {
        Matcher extrait = EXTRAIT_HEADING.matcher(answer);
        return extrait.find() ? extrait.start() : answer.length();
    }

    private static String insertBeforeExtrait(String answer, String block) {
        int insertAt = extraitStart(answer);
        String before = answer.substring(0, insertAt);
        return before + (before.endsWith("\n\n") || before.isEmpty() ? "" : before.endsWith("\n") ? "\n" : "\n\n")
                + block + answer.substring(insertAt);
    }

    private static final Pattern FRENCH_QUESTION = Pattern.compile(
            "(?iu)\\b(?:le|la|les|des|du|une?|est|sont|quel(?:le)?s?|comment|pourquoi|où|qu'|à|sert|champ|que|quoi|fait|valeurs?|possibles?)\\b"
                    + "|[àâçéèêëîïôûù]");

    /**
     * Rappel de langue placé en FIN de prompt, au plus près de la génération : constaté en test réel (2026-10-02),
     * avec un Contexte entièrement anglais Llama3 répondait parfois en anglais à une question française malgré la
     * règle LANGUE du prompt système (AC « Quel est le rôle de ACCOUNT ? », LC tirage escompté).
     */
    private static String languageReminder(String question) {
        return FRENCH_QUESTION.matcher(question).find()
                ? "\nRéponds en français (Fonction et Exemple) ; l'Extrait documentaire reste copié mot pour mot dans sa langue d'origine.\n"
                : "";
    }
}
