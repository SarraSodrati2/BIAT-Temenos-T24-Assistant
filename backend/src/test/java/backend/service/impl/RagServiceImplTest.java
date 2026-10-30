package backend.service.impl;

import backend.config.VectorConfig;
import backend.dto.ChatContext;
import backend.dto.QueryAnalysis;
import backend.service.ConceptGlossary;
import backend.service.DocumentBuilderService;
import backend.service.HybridSearchService;
import backend.service.QueryAnalyzer;
import backend.service.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceImplTest {

    @Mock
    private DocumentBuilderService documentBuilderService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private QueryAnalyzer queryAnalyzer;
    @Mock
    private HybridSearchService hybridSearchService;
    @Mock
    private ConceptGlossary conceptGlossary;

    private VectorConfig vectorConfig;
    private RagServiceImpl ragService;

    @BeforeEach
    void setUp() {
        vectorConfig = new VectorConfig();
        vectorConfig.setTopK(5);
        vectorConfig.setMinScore(0.5);
        ragService = new RagServiceImpl(documentBuilderService, vectorStoreService, queryAnalyzer, hybridSearchService, vectorConfig, conceptGlossary);
    }

    @Test
    void indexLoading_isTrueUntilBuildIndexIfNeededEnds_evenWhenLoadingFails() {
        // Avec rag.enabled=true, Spring injecte indexLoading=true avant que Tomcat n'accepte les requêtes.
        org.springframework.test.util.ReflectionTestUtils.setField(ragService, "indexLoading", true);
        assertThat(ragService.isIndexLoading()).isTrue();
        when(vectorStoreService.isIndexPersisted()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("index illisible")).when(vectorStoreService).load();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ragService.buildIndexIfNeeded()).isInstanceOf(IllegalStateException.class);

        assertThat(ragService.isIndexLoading()).isFalse(); // jamais bloqué sur « chargement en cours »
    }

    @Test
    void indexLoading_endsAsSoonAsThePersistedIndexIsLoaded_beforeTheDatabaseReconciliation() {
        org.springframework.test.util.ReflectionTestUtils.setField(ragService, "indexLoading", true);
        when(vectorStoreService.isIndexPersisted()).thenReturn(true);
        boolean[] loadingDuringSync = {true};
        when(documentBuilderService.buildDocuments()).thenAnswer(invocation -> {
            loadingDuringSync[0] = ragService.isIndexLoading();
            return List.of();
        });

        ragService.buildIndexIfNeeded();

        assertThat(loadingDuringSync[0]).isFalse();
    }

    @Test
    void buildIndexIfNeeded_indexAlreadyPersisted_onlyLoadsNeverRebuilds() {
        // Comportement historique, conservé quand la synchronisation au démarrage est désactivée.
        vectorConfig.setSyncOnStartup(false);
        when(vectorStoreService.isIndexPersisted()).thenReturn(true);

        ragService.buildIndexIfNeeded();

        verify(vectorStoreService).load();
        verify(vectorStoreService, never()).add(any());
        verify(vectorStoreService, never()).save();
        verify(documentBuilderService, never()).buildDocuments();
    }

    @Test
    void buildIndexIfNeeded_indexAlreadyPersisted_reconcilesWithTheDatabaseAndSavesOnlyWhenSomethingChanged() {
        // Un fichier XML ajouté/modifié depuis la construction de l'index doit y apparaître au démarrage
        // suivant, sans reconstruire tout l'index (voir VectorStoreService.synchronize).
        Document doc = new Document("helptext-1", "Table : EB.NEW", Map.of("tableName", "EB.NEW", "product", "EB"));
        when(vectorStoreService.isIndexPersisted()).thenReturn(true);
        when(documentBuilderService.buildDocuments()).thenReturn(List.of(doc));
        when(vectorStoreService.synchronize(List.of(doc))).thenReturn(new VectorStoreService.SyncResult(1, 0, 0, 0));

        ragService.buildIndexIfNeeded();

        verify(vectorStoreService).load();
        verify(vectorStoreService).synchronize(List.of(doc));
        verify(vectorStoreService).save();
        verify(vectorStoreService, never()).add(any());
    }

    @Test
    void buildIndexIfNeeded_indexAlreadyPersisted_nothingChanged_doesNotRewriteTheIndex() {
        Document doc = new Document("helptext-1", "Table : EB.NEW", Map.of());
        when(vectorStoreService.isIndexPersisted()).thenReturn(true);
        when(documentBuilderService.buildDocuments()).thenReturn(List.of(doc));
        when(vectorStoreService.synchronize(List.of(doc))).thenReturn(new VectorStoreService.SyncResult(0, 0, 0, 1));

        ragService.buildIndexIfNeeded();

        verify(vectorStoreService, never()).save();
    }

    @Test
    void buildIndexIfNeeded_indexMissing_buildsDocumentsThenEmbedsThenPersists() {
        Document doc = new Document("helptext-1", "Table : AA.ARR.CHARGEOFF", Map.of());
        when(vectorStoreService.isIndexPersisted()).thenReturn(false);
        when(documentBuilderService.buildDocuments()).thenReturn(List.of(doc));

        ragService.buildIndexIfNeeded();

        verify(vectorStoreService).add(List.of(doc));
        verify(vectorStoreService).save();
        verify(vectorStoreService, never()).load();
    }

    @Test
    void buildIndexIfNeeded_noDocumentsInOracle_doesNotIndexOrPersist() {
        when(vectorStoreService.isIndexPersisted()).thenReturn(false);
        when(documentBuilderService.buildDocuments()).thenReturn(List.of());

        ragService.buildIndexIfNeeded();

        verify(vectorStoreService, never()).add(any());
        verify(vectorStoreService, never()).save();
    }

    @Test
    void buildContext_delegatesToQueryAnalyzerThenHybridSearch_withConfiguredTopKAndMinScore() {
        QueryAnalysis analysis = new QueryAnalysis("Résume CUSTOMER", List.of("CUSTOMER"), List.of());
        Document d1 = new Document("t1", "Table : CUSTOMER\nContenu A", Map.of());
        when(queryAnalyzer.analyze("Résume CUSTOMER")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(List.of(d1));

        Optional<String> context = ragService.buildContext("Résume CUSTOMER", ChatContext.EMPTY);

        assertThat(context).isPresent();
        assertThat(context.get()).contains("Contenu A");
    }

    @Test
    void buildContext_noResults_returnsEmpty() {
        QueryAnalysis analysis = new QueryAnalysis("question hors sujet", List.of(), List.of());
        when(queryAnalyzer.analyze("question hors sujet")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(List.of());

        Optional<String> context = ragService.buildContext("question hors sujet", ChatContext.EMPTY);

        assertThat(context).isEmpty();
    }

    @Test
    void buildContext_multipleDocuments_areAllIncludedInTheContext() {
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());
        Document d1 = new Document("t1", "Table : A\nContenu A", Map.of());
        Document d2 = new Document("t2", "Table : B\nContenu B", Map.of());
        when(queryAnalyzer.analyze("question")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(List.of(d1, d2));

        Optional<String> context = ragService.buildContext("question", ChatContext.EMPTY);

        assertThat(context).isPresent();
        assertThat(context.get()).contains("Contenu A").contains("Contenu B");
    }

    @Test
    void buildContext_veryLargeDocument_isTruncatedInsteadOfSentInFull() {
        // Constaté en conditions réelles : CUSTOMER a 490 champs, un document de plus de 50 000
        // caractères envoyé tel quel à Llama3 dépasse le timeout HTTP configuré.
        String huge = "Table : CUSTOMER\nProduit : ST\n\n" + "X".repeat(10_000);
        Document exact = new Document("helptext-1", huge, Map.of("tableName", "CUSTOMER", "fieldCount", 490));
        QueryAnalysis analysis = new QueryAnalysis("Résume CUSTOMER", List.of("CUSTOMER"), List.of());
        when(queryAnalyzer.analyze("Résume CUSTOMER")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(List.of(exact));

        Optional<String> context = ragService.buildContext("Résume CUSTOMER", ChatContext.EMPTY);

        assertThat(context).isPresent();
        assertThat(context.get().length()).isLessThan(huge.length());
        // Le nombre de champs doit rester lisible même quand la description générale, à elle seule,
        // dépasse le budget de troncature.
        assertThat(context.get()).contains("490 champ(s) au total");
    }

    @Test
    void buildContext_smallDocument_isNotTruncated() {
        Document small = new Document("helptext-1", "Table : ACCOUNT\nContenu court", Map.of("tableName", "ACCOUNT"));
        QueryAnalysis analysis = new QueryAnalysis("Décris ACCOUNT", List.of("ACCOUNT"), List.of());
        when(queryAnalyzer.analyze("Décris ACCOUNT")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(List.of(small));

        Optional<String> context = ragService.buildContext("Décris ACCOUNT", ChatContext.EMPTY);

        assertThat(context).isPresent();
        assertThat(context.get()).isEqualTo("Table : ACCOUNT\nContenu court");
    }

    @Test
    void buildContext_moreResultsThanContextTopK_onlyIncludesTheBestRanked() {
        // Distinct du top-k de recherche (5 ici) : seuls les meilleurs documents, déjà triés par
        // HybridSearchService, sont réellement envoyés à Llama3 (contextTopK par défaut = 4) — c'est
        // le principal levier de réduction du temps de réponse (taille du prompt).
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());
        List<Document> fiveResults = List.of(
                new Document("t1", "Table : A\nContenu A", Map.of()),
                new Document("t2", "Table : B\nContenu B", Map.of()),
                new Document("t3", "Table : C\nContenu C", Map.of()),
                new Document("t4", "Table : D\nContenu D", Map.of()),
                new Document("t5", "Table : E\nContenu E", Map.of()));
        when(queryAnalyzer.analyze("question")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(fiveResults);

        Optional<String> context = ragService.buildContext("question", ChatContext.EMPTY);

        assertThat(context).isPresent();
        assertThat(context.get())
                .contains("Contenu A").contains("Contenu B").contains("Contenu C").contains("Contenu D")
                .doesNotContain("Contenu E");
    }

    @Test
    void buildContext_targetedFieldBeyondTruncationCutoff_isPrioritizedToSurviveTruncation() {
        // Reproduit le bug constaté en conditions réelles : "À quoi sert CUSTOMER.CIN ?" répondait
        // parfois "non trouvé" car le champ CIN, loin dans l'ordre brut des ~490 champs de CUSTOMER,
        // n'atteignait jamais le contexte tronqué à 2500 caractères.
        StringBuilder text = new StringBuilder("Table : CUSTOMER\nProduit : ST\n\n");
        text.append("Description générale :\n").append("X".repeat(2000)).append("\n\n");
        text.append("Résumé fonctionnel :\nRésumé\n\n");
        text.append("Champs (2) :\n\n");
        text.append("ADDRESS\n\nDescription :\nAdresse du client\n\nType :\nText\n\nInput :\nOptionnel\n\n");
        text.append("----------------------------------\n\n");
        text.append("CIN\n\nDescription :\nCarte d'identité nationale\n\nType :\nText\n\nInput :\nObligatoire\n\n");

        Document customer = new Document("helptext-1", text.toString(),
                Map.of("tableName", "CUSTOMER", "fieldCount", 2));
        QueryAnalysis analysis = new QueryAnalysis("À quoi sert CUSTOMER.CIN ?",
                List.of("CUSTOMER.CIN", "CUSTOMER"), List.of());
        when(queryAnalyzer.analyze("À quoi sert CUSTOMER.CIN ?")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(List.of(customer));

        Optional<String> context = ragService.buildContext("À quoi sert CUSTOMER.CIN ?", ChatContext.EMPTY);

        assertThat(context).isPresent();
        assertThat(context.get()).contains("Carte d'identité nationale");
    }

    @Test
    void buildContext_contextWithTableOnly_bypassesHybridSearch_andReturnsTheWholeTableDocument() {
        // Le parcours à bulles de /chatbot a fixé une table sans champ, et la question ne nomme
        // aucune colonne : la recherche hybride ne doit même pas être sollicitée, seule la table
        // exacte compte (limite la recherche aux HELP_TEXT/HELP_FIELD de cette table).
        Document advice = new Document("helptext-1", "Table : AA.ADVICE\nProduit : AA\n\nContenu complet",
                Map.of("tableName", "AA.ADVICE"));
        when(vectorStoreService.findByExactTableName("AA.ADVICE")).thenReturn(Optional.of(advice));
        when(queryAnalyzer.analyze("une question")).thenReturn(new QueryAnalysis("une question", List.of(), List.of()));

        Optional<String> context = ragService.buildContext("une question", new ChatContext("AA", "AA.ADVICE", null));

        assertThat(context).contains("Table : AA.ADVICE\nProduit : AA\n\nContenu complet");
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_contextWithTableAndField_isolatesOnlyThatFieldBlock() {
        // Sélection explicite du champ (ex. futur appel API direct) : toujours prioritaire sur toute
        // détection automatique dans la question.
        String text = "Table : AA.ADVICE\nProduit : AA\n\n"
                + "Description générale :\nGère les avis d'arrangement.\n\n"
                + "Résumé fonctionnel :\nRésumé\n\n"
                + "Champs (2) :\n\n"
                + "FULL.DESC\n\nDescription :\nLibellé long\n\nType :\nText\n\nInput :\nOptionnel\n\n"
                + "----------------------------------\n\n"
                + "DESCRIPTION\n\nDescription :\nLibellé court de l'avis\n\nType :\nText\n\nInput :\nObligatoire\n\n";
        Document advice = new Document("helptext-1", text, Map.of("tableName", "AA.ADVICE"));
        when(vectorStoreService.findByExactTableName("AA.ADVICE")).thenReturn(Optional.of(advice));
        when(queryAnalyzer.analyze("question")).thenReturn(new QueryAnalysis("question", List.of(), List.of()));

        Optional<String> context = ragService.buildContext("question",
                new ChatContext("AA", "AA.ADVICE", "DESCRIPTION"));

        assertThat(context).isPresent();
        assertThat(context.get())
                .contains("Gère les avis d'arrangement")
                .contains("Libellé court de l'avis")
                .doesNotContain("Libellé long");
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_fieldDescriptionIsOnlyAnInputPrompt_isReplacedWithANeutralNotice() {
        // Cas réel observé : DEAL.SUB.TYP (table MD.PARAMETER) n'a pour toute "Description" que
        // "Please specify the guarantee type" — une invite de saisie d'écran, pas une définition.
        // Ni le prompt système seul, ni une simple annotation à côté du texte d'origine, n'ont
        // suffi à empêcher Llama3 de la citer comme fonction du champ (vérifié en conditions
        // réelles à plusieurs reprises) : le texte d'origine est donc entièrement remplacé, pour
        // qu'il n'y ait plus rien à citer par erreur.
        String text = "Table : MD.PARAMETER\nProduit : MD\n\n"
                + "Description générale :\nParamètres du module MD.\n\n"
                + "Résumé fonctionnel :\nRésumé\n\n"
                + "Champs (1) :\n\n"
                + "DEAL.SUB.TYP\n\nDescription :\nPlease specify the guarantee type\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document param = new Document("helptext-1", text, Map.of("tableName", "MD.PARAMETER"));
        when(vectorStoreService.findByExactTableName("MD.PARAMETER")).thenReturn(Optional.of(param));
        when(queryAnalyzer.analyze("question")).thenReturn(new QueryAnalysis("question", List.of(), List.of()));

        Optional<String> context = ragService.buildContext("question",
                new ChatContext("MD", "MD.PARAMETER", "DEAL.SUB.TYP"));

        assertThat(context).isPresent();
        assertThat(context.get())
                .doesNotContain("Please specify the guarantee type")
                .contains("Aucune description fonctionnelle disponible")
                .contains("non exploitable comme définition");
    }

    @Test
    void buildContext_fieldDescriptionIsARealDefinition_isNotAnnotated() {
        String text = "Table : MD.PARAMETER\nProduit : MD\n\n"
                + "Description générale :\nParamètres du module MD.\n\n"
                + "Résumé fonctionnel :\nRésumé\n\n"
                + "Champs (1) :\n\n"
                + "DEAL.SUB.TYP\n\nDescription :\nUser defined identifier for the type of deal.\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document param = new Document("helptext-1", text, Map.of("tableName", "MD.PARAMETER"));
        when(vectorStoreService.findByExactTableName("MD.PARAMETER")).thenReturn(Optional.of(param));
        when(queryAnalyzer.analyze("question")).thenReturn(new QueryAnalysis("question", List.of(), List.of()));

        Optional<String> context = ragService.buildContext("question",
                new ChatContext("MD", "MD.PARAMETER", "DEAL.SUB.TYP"));

        assertThat(context).isPresent();
        assertThat(context.get())
                .contains("User defined identifier for the type of deal.")
                .doesNotContain("invite de saisie d'écran");
    }

    @Test
    void buildContext_contextWithUnknownField_fallsBackToTheWholeTableDocument() {
        String text = "Table : AA.ADVICE\nProduit : AA\n\nChamps (1) :\n\n"
                + "DESCRIPTION\n\nDescription :\nLibellé\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document advice = new Document("helptext-1", text, Map.of("tableName", "AA.ADVICE"));
        when(vectorStoreService.findByExactTableName("AA.ADVICE")).thenReturn(Optional.of(advice));
        when(queryAnalyzer.analyze("question")).thenReturn(new QueryAnalysis("question", List.of(), List.of()));

        Optional<String> context = ragService.buildContext("question",
                new ChatContext("AA", "AA.ADVICE", "CHAMP.OBSOLETE"));

        assertThat(context).isPresent();
        assertThat(context.get()).isEqualTo(text);
    }

    @Test
    void buildContext_tableContextWithoutExplicitField_detectsColumnNameMentionedInQuestion() {
        // Plus d'étape "sélection de colonne" à bulles côté frontend : l'utilisateur tape directement
        // sa question ("à quoi sert DESCRIPTION ?") après avoir choisi la table, et le nom de colonne
        // qu'il mentionne doit être détecté automatiquement pour isoler le contexte à ce seul champ.
        String text = "Table : AA.ADVICE\nProduit : AA\n\nChamps (2) :\n\n"
                + "FULL.DESC\n\nDescription :\nLibellé long\n\nType :\nText\n\nInput :\nOptionnel\n\n"
                + "----------------------------------\n\n"
                + "DESCRIPTION\n\nDescription :\nLibellé court de l'avis\n\nType :\nText\n\nInput :\nObligatoire\n\n";
        Document advice = new Document("helptext-1", text, Map.of("tableName", "AA.ADVICE"));
        when(vectorStoreService.findByExactTableName("AA.ADVICE")).thenReturn(Optional.of(advice));
        when(queryAnalyzer.analyze("À quoi sert DESCRIPTION ?"))
                .thenReturn(new QueryAnalysis("À quoi sert DESCRIPTION ?", List.of("DESCRIPTION"), List.of()));

        Optional<String> context = ragService.buildContext("À quoi sert DESCRIPTION ?",
                new ChatContext("AA", "AA.ADVICE", null));

        assertThat(context).isPresent();
        assertThat(context.get())
                .contains("Libellé court de l'avis")
                .doesNotContain("Libellé long");
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_tableContextWithoutExplicitField_questionMentionsNoKnownColumn_returnsTheWholeTable() {
        String text = "Table : AA.ADVICE\nProduit : AA\n\nChamps (1) :\n\n"
                + "DESCRIPTION\n\nDescription :\nLibellé\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document advice = new Document("helptext-1", text, Map.of("tableName", "AA.ADVICE"));
        when(vectorStoreService.findByExactTableName("AA.ADVICE")).thenReturn(Optional.of(advice));
        when(queryAnalyzer.analyze("Que fait cette table ?"))
                .thenReturn(new QueryAnalysis("Que fait cette table ?", List.of("XYZ"), List.of()));

        Optional<String> context = ragService.buildContext("Que fait cette table ?",
                new ChatContext("AA", "AA.ADVICE", null));

        assertThat(context).isPresent();
        assertThat(context.get()).isEqualTo(text);
    }

    @Test
    void buildContext_contextTableNotFoundInIndex_degradesGracefullyToUnscopedHybridSearch() {
        // Table renommée/supprimée depuis que le contexte a été fixé sur la conversation : ne bloque
        // jamais l'utilisateur, retombe sur la recherche habituelle plutôt que de renvoyer un vide.
        when(vectorStoreService.findByExactTableName("AA.OLDTABLE")).thenReturn(Optional.empty());
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());
        Document fallback = new Document("t1", "Table : AUTRE\nContenu", Map.of());
        when(queryAnalyzer.analyze("question")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(List.of(fallback));

        Optional<String> context = ragService.buildContext("question", new ChatContext("AA", "AA.OLDTABLE", null));

        assertThat(context).isPresent();
        assertThat(context.get()).contains("Contenu");
    }

    @Test
    void buildContext_moduleOnlyContext_retainsOnlyDocumentsOfThatModuleAmongHybridResults() {
        // Le parcours à bulles ne fixe plus que le module ("je veux seulement les modules en bulles") :
        // la recherche hybride tourne normalement, mais un document d'un autre module ne doit jamais
        // atteindre le contexte envoyé à Llama3.
        Document inModule = new Document("t1", "Table : AA.ADVICE\nContenu AA",
                Map.of("tableName", "AA.ADVICE", "product", "AA"));
        Document otherModule = new Document("t2", "Table : CUSTOMER\nContenu CUSTOMER",
                Map.of("tableName", "CUSTOMER", "product", "CU"));
        when(vectorStoreService.findAllByProduct("AA")).thenReturn(List.of(inModule));
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());
        when(queryAnalyzer.analyze("question")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5, "AA")).thenReturn(List.of(otherModule, inModule));

        Optional<String> context = ragService.buildContext("question", new ChatContext("AA", null, null));

        assertThat(context).isPresent();
        assertThat(context.get()).contains("Contenu AA").doesNotContain("Contenu CUSTOMER");
    }

    @Test
    void buildContext_moduleOnlyContext_detectsColumnNameMentionedInQuestion() {
        String text = "Table : AA.ADVICE\nProduit : AA\n\nChamps (1) :\n\n"
                + "DESCRIPTION\n\nDescription :\nLibellé court\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document advice = new Document("t1", text, Map.of("tableName", "AA.ADVICE", "product", "AA"));
        when(vectorStoreService.findAllByProduct("AA")).thenReturn(List.of(advice));
        QueryAnalysis analysis = new QueryAnalysis("À quoi sert DESCRIPTION ?", List.of("DESCRIPTION"), List.of());
        when(queryAnalyzer.analyze("À quoi sert DESCRIPTION ?")).thenReturn(analysis);

        Optional<String> context = ragService.buildContext("À quoi sert DESCRIPTION ?", new ChatContext("AA", null, null));

        assertThat(context).isPresent();
        assertThat(context.get()).contains("Libellé court");
        // Correspondance exacte trouvée directement dans les tables du module : jamais besoin de la
        // recherche hybride globale (voir la Javadoc de buildModuleScopedContext).
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_moduleOnlyContext_nothingRelevantWithinModule_suggestsTheModuleTablesInstead() {
        // Ne jamais répondre avec une table d'un autre module que celui explicitement choisi : plutôt
        // qu'un "non trouvé" sec, propose les tables du module actif (voir buildModuleIndex), pour que
        // Llama3 indique clairement que la doc est incomplète et suggère les tables les plus proches.
        Document otherModule = new Document("t2", "Table : CUSTOMER\nContenu", Map.of("tableName", "CUSTOMER", "product", "CU"));
        when(vectorStoreService.findAllByProduct("AA")).thenReturn(
                List.of(new Document("t1", "Table : AA.ADVICE\nContenu AA", Map.of("tableName", "AA.ADVICE", "product", "AA"))));
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());
        when(queryAnalyzer.analyze("question")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5, "AA")).thenReturn(List.of(otherModule));

        Optional<String> context = ragService.buildContext("question", new ChatContext("AA", null, null));

        assertThat(context).isPresent();
        assertThat(context.get())
                .contains("Tables du module AA")
                .contains("AA.ADVICE")
                .doesNotContain("CUSTOMER");
    }

    @Test
    void buildContext_moduleOnlyContext_nothingRelevantWithinModule_includesEachTableShortOverview() {
        // buildModuleIndex ne doit plus renvoyer que des noms de table nus (voir la Javadoc de la
        // méthode) : Llama3 a besoin d'un court extrait réel par table pour pouvoir répondre à une
        // question générale sur le module lui-même (règle "MODULES T24" du SYSTEM_PROMPT) sans
        // inventer une "description du module" qui n'existe dans aucune source.
        Document withOverview = new Document("t1",
                "Table : AA.ADVICE\nProduit : AA\n\nDescription générale :\nGère les avis envoyés au client.\n\nRésumé fonctionnel :\n...",
                Map.of("tableName", "AA.ADVICE", "product", "AA"));
        Document withoutOverview = new Document("t2",
                "Table : AA.OTHER\nProduit : AA\n\nDescription générale :\nNon renseignée dans HELP_TEXT.\n\nRésumé fonctionnel :\n...",
                Map.of("tableName", "AA.OTHER", "product", "AA"));
        when(vectorStoreService.findAllByProduct("AA")).thenReturn(List.of(withOverview, withoutOverview));
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());
        when(queryAnalyzer.analyze("question")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5, "AA")).thenReturn(List.of());

        Optional<String> context = ragService.buildContext("question", new ChatContext("AA", null, null));

        assertThat(context).isPresent();
        assertThat(context.get())
                .contains("AA.ADVICE : Gère les avis envoyés au client.")
                .contains("AA.OTHER : Non renseignée dans HELP_TEXT.");
    }

    @Test
    void buildContext_moduleOnlyContext_moduleUnknownInIndex_degradesGracefullyToUnscopedHybridSearch() {
        when(vectorStoreService.findAllByProduct("ZZ")).thenReturn(List.of());
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());
        Document fallback = new Document("t1", "Table : AUTRE\nContenu", Map.of());
        when(queryAnalyzer.analyze("question")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5)).thenReturn(List.of(fallback));

        Optional<String> context = ragService.buildContext("question", new ChatContext("ZZ", null, null));

        assertThat(context).isPresent();
        assertThat(context.get()).contains("Contenu");
    }

    @Test
    void buildContext_moduleOnlyContext_currentQuestionHasNoKeyword_fallsBackToPreviousQuestionsTopic() {
        // Reproduit le cas signalé : "c'est un champ pas une table" (correction en langage familier,
        // aucun mot-clé T24) doit quand même retrouver DAYS.PRIOR, sujet de la question précédente.
        String text = "Table : AA.ADVICE\nProduit : AA\n\nChamps (1) :\n\n"
                + "DAYS.PRIOR\n\nDescription :\nNombre de jours précédant la date de fréquence.\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document advice = new Document("t1", text, Map.of("tableName", "AA.ADVICE", "product", "AA"));
        when(vectorStoreService.findAllByProduct("AA")).thenReturn(List.of(advice));

        QueryAnalysis emptyAnalysis = new QueryAnalysis("c'est un champ pas une table", List.of(), List.of());
        QueryAnalysis previousAnalysis = new QueryAnalysis("c quoi la table de DAYS.PRIOR ?", List.of("DAYS.PRIOR"), List.of());
        when(queryAnalyzer.analyze("c'est un champ pas une table")).thenReturn(emptyAnalysis);
        when(queryAnalyzer.analyze("c quoi la table de DAYS.PRIOR ?")).thenReturn(previousAnalysis);

        ChatContext context = new ChatContext("AA", null, null, "c quoi la table de DAYS.PRIOR ?");
        Optional<String> result = ragService.buildContext("c'est un champ pas une table", context);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Nombre de jours précédant la date de fréquence");
        // Correspondance exacte de DAYS.PRIOR trouvée directement dans les tables du module : jamais
        // besoin de la recherche hybride globale, ni avec l'analyse vide ni avec celle de repli.
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_moduleOnlyContext_currentQuestionHasItsOwnKeyword_ignoresThePreviousQuestion() {
        QueryAnalysis analysis = new QueryAnalysis("Résume CUSTOMER", List.of("CUSTOMER"), List.of());
        Document customer = new Document("t1", "Table : CUSTOMER\nContenu", Map.of("tableName", "CUSTOMER", "product", "AA"));
        when(vectorStoreService.findAllByProduct("AA")).thenReturn(List.of(customer));
        when(queryAnalyzer.analyze("Résume CUSTOMER")).thenReturn(analysis);
        when(hybridSearchService.search(analysis, 5, 0.5, "AA")).thenReturn(List.of(customer));

        ChatContext context = new ChatContext("AA", null, null, "question précédente sans rapport");
        ragService.buildContext("Résume CUSTOMER", context);

        // La question courante a déjà un mot-clé exploitable : jamais besoin de retomber sur la précédente.
        verify(queryAnalyzer, never()).analyze("question précédente sans rapport");
    }

    @Test
    void buildContext_moduleOnlyContext_sameFieldNameInSeveralTables_asksToPreciseTheTableInstead() {
        // "Si plusieurs colonnes portent le même nom dans des tables différentes, demande seulement
        // quelle table est concernée" : MATURITY.DATE existe dans deux tables LD -> le contexte signale
        // l'ambiguïté (pour que le prompt demande une précision), mais inclut quand même le VRAI bloc de
        // chaque table : sans texte réel à citer, Llama3 n'a rien de légitime dans le Contexte pour
        // satisfaire l'exigence d'un Extrait mot pour mot (voir la Javadoc de buildAmbiguousFieldContext,
        // bug réel corrigé — un Extrait plausible mais absent du Contexte était systématiquement rejeté).
        String textA = "Table : LD.LOANS.MASTER\nProduit : LD\n\nChamps (1) :\n\n"
                + "MATURITY.DATE\n\nDescription :\nDate d'echeance du pret.\n\nType :\nDate\n\nInput :\nObligatoire\n\n";
        String textB = "Table : LD.INTEREST\nProduit : LD\n\nChamps (1) :\n\n"
                + "MATURITY.DATE\n\nDescription :\nDate d'echeance des interets.\n\nType :\nDate\n\nInput :\nObligatoire\n\n";
        Document tableA = new Document("t1", textA, Map.of("tableName", "LD.LOANS.MASTER", "product", "LD"));
        Document tableB = new Document("t2", textB, Map.of("tableName", "LD.INTEREST", "product", "LD"));
        when(vectorStoreService.findAllByProduct("LD")).thenReturn(List.of(tableA, tableB));
        QueryAnalysis analysis = new QueryAnalysis("MATURITY.DATE", List.of("MATURITY.DATE"), List.of());
        when(queryAnalyzer.analyze("MATURITY.DATE")).thenReturn(analysis);

        Optional<String> result = ragService.buildContext("MATURITY.DATE", new ChatContext("LD", null, null));

        assertThat(result).isPresent();
        assertThat(result.get())
                .contains("MATURITY.DATE")
                .contains("LD.LOANS.MASTER")
                .contains("LD.INTEREST")
                .contains("Ambigu")
                .contains("Date d'echeance du pret")
                .contains("Date d'echeance des interets");
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_moduleOnlyContext_fieldNameInOnlyOneTable_answersDirectlyWithoutAskingToPrecise() {
        String text = "Table : LD.LOANS.MASTER\nProduit : LD\n\nChamps (1) :\n\n"
                + "MATURITY.DATE\n\nDescription :\nDate d'echeance du pret.\n\nType :\nDate\n\nInput :\nObligatoire\n\n";
        Document table = new Document("t1", text, Map.of("tableName", "LD.LOANS.MASTER", "product", "LD"));
        when(vectorStoreService.findAllByProduct("LD")).thenReturn(List.of(table));
        QueryAnalysis analysis = new QueryAnalysis("MATURITY.DATE", List.of("MATURITY.DATE"), List.of());
        when(queryAnalyzer.analyze("MATURITY.DATE")).thenReturn(analysis);

        Optional<String> result = ragService.buildContext("MATURITY.DATE", new ChatContext("LD", null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Date d'echeance du pret").doesNotContain("Ambigu");
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_moduleOnlyContext_fieldNameAlsoExistsInManyOtherModules_stillFoundInTheScopedModule() {
        // Bug réel corrigé : DELIVERY.REF existe tel quel dans plus de dix modules T24 (AA, AC, IS...).
        // Avant le correctif, buildModuleScopedContext filtrait par module APRÈS le top-K global de la
        // recherche hybride : les occurrences des autres modules pouvaient noyer celles du module actif
        // hors de ce top-K, et le champ — pourtant bien documenté dans MD.DEAL — n'atteignait jamais le
        // contexte ("non confirmé" alors que la documentation répond). La correspondance exacte de nom
        // doit être cherchée directement dans les tables du module actif, jamais seulement dans le
        // sous-ensemble qu'une recherche hybride non filtrée par module aurait remonté.
        String text = "Table : MD.DEAL\nProduit : MD\n\nChamps (1) :\n\n"
                + "DELIVERY.REF\n\nDescription :\nIdentifies each separate delivery produced by T24.\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document mdDeal = new Document("t1", text, Map.of("tableName", "MD.DEAL", "product", "MD"));
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(mdDeal));
        QueryAnalysis analysis = new QueryAnalysis("A quoi sert le champ DELIVERY.REF ?", List.of("DELIVERY.REF"), List.of());
        when(queryAnalyzer.analyze("A quoi sert le champ DELIVERY.REF ?")).thenReturn(analysis);

        Optional<String> result = ragService.buildContext("A quoi sert le champ DELIVERY.REF ?", new ChatContext("MD", null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Identifies each separate delivery produced by T24.");
        // Jamais besoin de la recherche hybride globale : la correspondance exacte dans les tables du
        // module actif suffit, sans risque qu'elle soit noyée par d'autres modules.
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_moduleOnlyContext_bareKeywordSameAsTableNameLastSegment_isExcludedFromExactFieldMatch() {
        // Bug réel corrigé : la table DE.BIC contient un champ nommé BIC. Pour une question conceptuelle
        // ("Comment sont gérés les codes BIC des banques ?", jamais "le champ BIC"), le mot-clé bare "BIC"
        // correspondait quand même exactement au champ BIC de DE.BIC, verrouillant le Contexte sur cette
        // seule fiche technique de champ (souvent trop étroite pour une question conceptuelle) : Llama3
        // produisait alors une réponse plausible mais non vérifiable mot pour mot contre ce Contexte trop
        // restreint, rejetée par ChatbotServiceImpl.rejectIfExtraitIsFabricated, alors que la table DE.BIC
        // documente bien le sujet plus largement. Un mot-clé bare dont le nom coïncide avec le dernier
        // segment du nom de SA PROPRE table doit donc être exclu de la correspondance exacte de champ et
        // laisser la recherche hybride (qui peut retenir le document DE.BIC dans son ensemble) décider.
        String text = "Table : DE.BIC\nProduit : DE\n\nChamps (1) :\n\n"
                + "BIC\n\nDescription :\nBank Identifier Code used to route SWIFT payments.\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document deBic = new Document("t1", text, Map.of("tableName", "DE.BIC", "product", "DE"));
        when(vectorStoreService.findAllByProduct("DE")).thenReturn(List.of(deBic));
        QueryAnalysis analysis = new QueryAnalysis("Comment sont gérés les codes BIC des banques ?", List.of("BIC"), List.of());
        when(queryAnalyzer.analyze("Comment sont gérés les codes BIC des banques ?")).thenReturn(analysis);
        when(hybridSearchService.search(eq(analysis), anyInt(), anyDouble(), eq("DE"))).thenReturn(List.of(deBic));

        ragService.buildContext("Comment sont gérés les codes BIC des banques ?", new ChatContext("DE", null, null));

        // La correspondance exacte de champ ne doit pas court-circuiter la recherche : "BIC" coïncide avec
        // le dernier segment du nom de sa propre table (DE.BIC), donc la recherche hybride est bien appelée.
        verify(hybridSearchService).search(eq(analysis), anyInt(), anyDouble(), eq("DE"));
    }

    @Test
    void buildContext_moduleOnlyContext_restrictsTheHybridSearchToTheModule_neverAnUnscopedGlobalSearch() {
        // Correctif de la recherche sémantique : le module est transmis À la recherche (filtre appliqué
        // avant le top-K), jamais une recherche globale filtrée après coup.
        Document ebArchive = new Document("eb1", "Table : EB.ARCHIVE\nContenu ARCHIVE",
                Map.of("tableName", "EB.ARCHIVE", "product", "EB"));
        when(vectorStoreService.findAllByProduct("EB")).thenReturn(List.of(ebArchive));
        QueryAnalysis analysis = new QueryAnalysis("Pourquoi supprime-t-on les vieilles données ?", List.of(), List.of());
        when(queryAnalyzer.analyze("Pourquoi supprime-t-on les vieilles données ?")).thenReturn(analysis);
        // Seuil propre à la recherche restreinte (rag.vector-store.module-min-score), pas le seuil global.
        vectorConfig.setModuleMinScore(0.42);
        when(hybridSearchService.search(analysis, 5, 0.42, "EB")).thenReturn(List.of(ebArchive));

        Optional<String> context = ragService.buildContext("Pourquoi supprime-t-on les vieilles données ?",
                new ChatContext("EB", null, null));

        assertThat(context).isPresent();
        assertThat(context.get()).contains("Contenu ARCHIVE");
        verify(hybridSearchService, never()).search(any(), anyInt(), anyDouble());
    }

    @Test
    void buildContext_moduleOnlyContext_dottedFieldIdentifier_isNotMadeAmbiguousByShorterFieldNamesItEndsWith() {
        // Bug réel (index AC) : INT.ACC.TYPE n'existe que dans AC.AUTO.ACCOUNT, mais la correspondance par
        // suffixe le faisait aussi correspondre aux champs "TYPE" d'autres tables (BULK.STO, STANDING.ORDER...)
        // -> fausse demande de précision au lieu de la réponse directe.
        String autoAccount = "Table : AC.AUTO.ACCOUNT\nProduit : AC\n\nChamps (1) :\n\n"
                + "INT.ACC.TYPE\n\nDescription :\nDetermines the type of sub account key.\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        String bulkSto = "Table : BULK.STO\nProduit : AC\n\nChamps (1) :\n\n"
                + "TYPE\n\nDescription :\nStanding order type.\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        when(vectorStoreService.findAllByProduct("AC")).thenReturn(List.of(
                new Document("a1", autoAccount, Map.of("tableName", "AC.AUTO.ACCOUNT", "product", "AC")),
                new Document("a2", bulkSto, Map.of("tableName", "BULK.STO", "product", "AC"))));
        QueryAnalysis analysis = new QueryAnalysis("À quoi sert le champ INT.ACC.TYPE ?", List.of("INT.ACC.TYPE"), List.of());
        when(queryAnalyzer.analyze("À quoi sert le champ INT.ACC.TYPE ?")).thenReturn(analysis);

        Optional<String> context = ragService.buildContext("À quoi sert le champ INT.ACC.TYPE ?", new ChatContext("AC", null, null));

        assertThat(context).get().asString()
                .contains("Determines the type of sub account key.")
                .doesNotContain("Ambigu")
                .doesNotContain("Standing order type.");
        verifyNoInteractions(hybridSearchService);
    }

    // --- Table nommée exactement, documentée dans un AUTRE module que celui de la conversation (données réelles) ------

    /** Description générale réelle de AC.EXPECTED.RECS (produit ER), recopiée de l'index le 2026-10-06. */
    static final String AC_EXPECTED_RECS_OVERVIEW = "Banks are often advised of details of incoming funds by other institutions or "
            + "clients. This information can be sufficient enough for the bank to decide to allow a client an intra day overdraft since "
            + "the funds advised could be 'guaranteed', or the bank may take a business risk and allow the payment. In order to do this the "
            + "'expected' receipt information needs to be recorded in T24 and used to provide balance projection information for ad-hoc "
            + "Enquiries or reports. This module allows the user to enter the expected receipts manually; to have them created by incoming "
            + "SWIFT MT210s and to match them, where possible automatically, or manually. Funds Transfers can be set to create an automatic "
            + "'receipt' record being the opposite to the 'expected' record. Where the receipt is the one 'expected' and providing the "
            + "details are all correct then an automatch is made when the FT is authorised. Similar can be done for records that are within "
            + "defined tolerances, others need to be matched manually. The updates are stored on fields on the ACCOUNT file and users can "
            + "create information enquiries based on these fields; the expected/received records (AC.EXPECTED.RECS) or a combination of both.";

    /** Document au format de l'index (DocumentBuilderServiceImpl) : en-tête, description générale, résumé, un champ. */
    static Document realTable(String id, String table, String product, String overview, String field, String fieldDescription) {
        String summary = overview.startsWith("Non renseignée") ? "Aucune description générale disponible."
                : overview.substring(0, overview.indexOf('.') + 1);
        String text = "Table : " + table + "\nProduit : " + product + "\n\nDescription générale :\n" + overview
                + "\n\nRésumé fonctionnel :\n" + summary + "\n\nChamps (1) :\n\n" + field + "\n\nDescription :\n" + fieldDescription
                + "\n\nType :\nNon renseigné.\n\nInput :\nNon renseigné.\n\n";
        return new Document(id, text, Map.of("tableName", table, "product", product, "fieldCount", 1));
    }

    @Test
    void buildContext_acExpectedRecsNamedInAnAcConversation_isFoundAlthoughDocumentedUnderModuleEr() {
        // Bug réel (2026-10-06) : « À quoi sert AC.EXPECTED.RECS ? » (conversation AC) -> « n'a pas permis de confirmer ».
        Document acExpectedRecs = realTable("helptext-3219", "AC.EXPECTED.RECS", "ER", AC_EXPECTED_RECS_OVERVIEW,
                "ACCOUNT.NUMBER", "The account number for which the funds are expected.");
        when(vectorStoreService.findAllByProduct("AC")).thenReturn(List.of(new Document("a1", "Table : ACCOUNT\nProduit : AC",
                Map.of("tableName", "ACCOUNT", "product", "AC"))));
        QueryAnalysis analysis = new QueryAnalysis("À quoi sert AC.EXPECTED.RECS ?", List.of("AC.EXPECTED.RECS", "AC"), List.of());
        when(queryAnalyzer.analyze("À quoi sert AC.EXPECTED.RECS ?")).thenReturn(analysis);
        when(vectorStoreService.findByExactTableName("AC.EXPECTED.RECS")).thenReturn(Optional.of(acExpectedRecs));

        Optional<String> context = ragService.buildContext("À quoi sert AC.EXPECTED.RECS ?", new ChatContext("AC", null, null));

        assertThat(context).get().asString()
                .contains("Table : AC.EXPECTED.RECS")
                .contains("Produit : ER")
                // la description générale arrive ENTIÈRE au LLM, fin comprise (coupée avant la correction)
                .contains("used to provide balance projection information for ad-hoc Enquiries or reports")
                .contains("the expected/received records (AC.EXPECTED.RECS) or a combination of both.")
                // doublon de la première phrase, retiré du contexte (jamais de l'index)
                .doesNotContain("Résumé fonctionnel");
        verifyNoInteractions(hybridSearchService);
        // « AC », simple préfixe du nom, n'est jamais pris pour une table nommée
        verify(vectorStoreService, never()).findByExactTableName("AC");
    }

    /** Tables réelles des 7 modules affichés, documentées sous un AUTRE produit que le préfixe de leur nom. */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> tablesDocumentedOutsideTheirPrefixModule() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("AC", "AC.EXPECTED.RECS", "ER", AC_EXPECTED_RECS_OVERVIEW,
                        "ACCOUNT.NUMBER", "The account number for which the funds are expected."),
                org.junit.jupiter.params.provider.Arguments.of("LD", "LD.COLLATERAL", "CO", "Non renseignée dans HELP_TEXT.",
                        "COLLATERAL", "Standard T24 alphanumeric field. Validation Rules: A maximum of 13 characters may be entered. "
                                + "Must be the key to a valid entry on the COLLATERAL file."),
                org.junit.jupiter.params.provider.Arguments.of("LC", "LC.COLLATERAL", "CO", "Non renseignée dans HELP_TEXT.",
                        "COLLATERAL.ID", "Help Text for this field is unavailable. Please refer to the T24 User Guides for further information."),
                org.junit.jupiter.params.provider.Arguments.of("TT", "TT.CASH.DENOM", "ESTELL",
                        "This is a live table which will record all the deposit and withdrawal denominations.", "ID", "Record id."),
                org.junit.jupiter.params.provider.Arguments.of("EB", "EB.API.ATTRIBUTE", "AA", "Non renseignée dans HELP_TEXT.",
                        "ID", "Help Text for this field is unavailable. Please refer to the T24 User Guides for further information."),
                org.junit.jupiter.params.provider.Arguments.of("DE", "DE.MT942.HIST", "AC", "Non renseignée dans HELP_TEXT.",
                        "ACCT.DATE.TIME", "Id of the record Identifies the date and time on which the record created for the account"),
                org.junit.jupiter.params.provider.Arguments.of("MD", "MD.CR.DR.TXN.PARAM", "ST", "Non renseignée dans HELP_TEXT.",
                        "MD.TXN.TYPE", "Possible Member Direct Transaction Types"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "conversation {0} : {1} (produit {2})")
    @org.junit.jupiter.params.provider.MethodSource("tablesDocumentedOutsideTheirPrefixModule")
    void buildContext_exactTableNamedInAnyDisplayedModule_isAlwaysFound(String selectedModule, String table, String product,
                                                                         String overview, String field, String fieldDescription) {
        Document document = realTable("t-" + table, table, product, overview, field, fieldDescription);
        when(vectorStoreService.findAllByProduct(selectedModule)).thenReturn(List.of(new Document("m1", "Table : OTHER\nProduit : "
                + selectedModule, Map.of("tableName", "OTHER", "product", selectedModule))));
        String question = "À quoi sert " + table + " ?";
        String prefix = table.substring(0, table.indexOf('.'));
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of(table, prefix), List.of()));
        when(vectorStoreService.findByExactTableName(table)).thenReturn(Optional.of(document));

        Optional<String> context = ragService.buildContext(question, new ChatContext(selectedModule, null, null));

        assertThat(context).get().asString().contains("Table : " + table).contains(fieldDescription);
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_namedTableAndOneOfItsFields_isolatesThatField() {
        Document mt942 = realTable("t1", "DE.MT942.HIST", "AC", "Non renseignée dans HELP_TEXT.", "ACCT.DATE.TIME",
                "Id of the record Identifies the date and time on which the record created for the account");
        when(vectorStoreService.findAllByProduct("DE")).thenReturn(List.of(new Document("m1", "Table : DE.BIC\nProduit : DE",
                Map.of("tableName", "DE.BIC", "product", "DE"))));
        String question = "À quoi sert le champ ACCT.DATE.TIME de DE.MT942.HIST ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("ACCT.DATE.TIME", "ACCT", "DE.MT942.HIST", "DE"), List.of()));
        when(vectorStoreService.findByExactTableName(anyString())).thenReturn(Optional.empty());
        when(vectorStoreService.findByExactTableName("DE.MT942.HIST")).thenReturn(Optional.of(mt942));

        Optional<String> context = ragService.buildContext(question, new ChatContext("DE", null, null));

        assertThat(context).get().asString().contains("Champ sélectionné : ACCT.DATE.TIME")
                .contains("Identifies the date and time on which the record created");
    }

    // --- Champ cité tel quel mais documenté hors du module sélectionné (bug réel du 2026-10-06 : CP.ACCT) ---------

    private static final String CP_ACCT_DESCRIPTION =
            "System field which contains accounts used in AC.CASH.POOL records linked to this group Validation Rules: System field. No input";

    @Test
    void buildContext_cpAcctAskedInAnAcConversation_isFoundInAcSharedAccountDocumentedUnderModulePo() {
        // Avant : « c quoi CP.ACCT ? » (conversation AC) -> « n'a pas permis de confirmer ».
        Document sharedAccount = realTable("helptext-po-1", "AC.SHARED.ACCOUNT", "PO", "Non renseignée dans HELP_TEXT.",
                "CP.ACCT", CP_ACCT_DESCRIPTION);
        when(vectorStoreService.findAllByProduct("AC")).thenReturn(List.of(realTable("a1", "ACCOUNT", "AC",
                "The ACCOUNT application holds the accounts.", "CATEGORY", "Account category.")));
        String question = "c quoi CP.ACCT ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("CP.ACCT", "CP", "QUOI"), List.of()));
        when(vectorStoreService.findTablesWithField("CP.ACCT")).thenReturn(List.of(sharedAccount));

        Optional<String> context = ragService.buildContext(question, new ChatContext("AC", null, null));

        assertThat(context).get().asString()
                .contains("Table : AC.SHARED.ACCOUNT")
                .contains("Champ sélectionné : CP.ACCT")
                .contains("accounts used in AC.CASH.POOL records");
        verifyNoInteractions(hybridSearchService);
    }

    // --- Documents réels du référentiel (data/vector/index.json, 2026-10-06) --------------------------------------------

    static final String MD_DEAL_DELIVERY_REF = "This field identifies each separate delivery produced by T24 in connection with the "
            + "miscellaneous deal. This field will hold all the identies of any advices produced by the delivery system in connection "
            + "with the deal. Multi-valued field associated with the ADVICE.TYPE field which holds the key to the MD.ADVICES record for "
            + "each delivery advice the identity of which is recorded here. Validation Rules: Internally generated multi valued field "
            + "Multi value field associated with field 30.1 Advice Type. (No input allowed)";
    static final String MD_FEE_SETTLEMENT_DELIVERY_REF = "Holds the key of all advices produced by the delivery system. System maintained field.";
    static final String HELP_TEXT_UNAVAILABLE =
            "Help Text for this field is unavailable. Please refer to the T24 User Guides for further information.";

    private static Document mdDeal() {
        return realTable("helptext-md-deal", "MD.DEAL", "MD", "OVERVIEW OF MISCELLANEOUS DEALS - MAIN FILE The Miscellaneous Deals "
                + "module provides the facility to record deals which are off balance sheet.", "DELIVERY.REF", MD_DEAL_DELIVERY_REF);
    }

    private static Document mdFeeSettlement() {
        return realTable("helptext-md-fee", "MD.FEE.SETTLEMENT", "MD", "OVERVIEW Table used to settle the claimed commission as well as "
                + "refund of charges and commission.", "DELIVERY.REF", MD_FEE_SETTLEMENT_DELIVERY_REF);
    }

    @Test
    void buildContext_deliveryRefInAnMdConversation_twoRealTablesOfTheModule_isDetectedAsAmbiguousBeforeGeneration() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(mdDeal(), mdFeeSettlement()));
        String question = "À quoi sert DELIVERY.REF ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("DELIVERY.REF", "DELIVERY"), List.of()));

        Optional<String> context = ragService.buildContext(question, new ChatContext("MD", null, null));

        assertThat(context).get().asString()
                .startsWith("Le champ DELIVERY.REF existe dans plusieurs tables du module MD : MD.DEAL, MD.FEE.SETTLEMENT.")
                .contains("Ambigu :")
                .doesNotContain(RagServiceImpl.COMMON_DESCRIPTION_LABEL); // descriptions réelles différentes
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_deliveryRefWithItsTableNamedInTheQuestion_usesOnlyThatTable() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(mdDeal(), mdFeeSettlement()));
        String question = "À quoi sert DELIVERY.REF dans MD.FEE.SETTLEMENT ?";
        when(queryAnalyzer.analyze(question)).thenReturn(
                new QueryAnalysis(question, List.of("DELIVERY.REF", "DELIVERY", "MD.FEE.SETTLEMENT", "MD"), List.of()));
        when(vectorStoreService.findByExactTableName(anyString())).thenReturn(Optional.empty());
        when(vectorStoreService.findByExactTableName("MD.FEE.SETTLEMENT")).thenReturn(Optional.of(mdFeeSettlement()));

        Optional<String> context = ragService.buildContext(question, new ChatContext("MD", null, null));

        assertThat(context).get().asString()
                .contains("Table : MD.FEE.SETTLEMENT")
                .contains("Champ sélectionné : DELIVERY.REF")
                .contains(MD_FEE_SETTLEMENT_DELIVERY_REF)
                .doesNotContain("miscellaneous deal")
                .doesNotContain("Ambigu :");
    }

    @Test
    void buildContext_deliveryRefWithoutModule_isAmbiguous_neverLeftToTheHybridSearch() {
        // Sans module, les tables arrivaient à Llama3 comme documents distincts et il en choisissait une.
        String question = "c quoi DELIVERY.REF ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("DELIVERY.REF", "DELIVERY"), List.of()));
        when(vectorStoreService.findByExactTableName(anyString())).thenReturn(Optional.empty());
        when(vectorStoreService.findTablesWithField("DELIVERY.REF")).thenReturn(List.of(mdDeal(), mdFeeSettlement()));

        Optional<String> context = ragService.buildContext(question, ChatContext.EMPTY);

        assertThat(context).get().asString()
                .startsWith("Le champ DELIVERY.REF existe dans plusieurs tables de la documentation : MD.DEAL, MD.FEE.SETTLEMENT.");
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_deliveryRefWithItsTableNamed_withoutModule_usesOnlyThatTable() {
        String question = "DELIVERY.REF de MD.DEAL ?";
        when(queryAnalyzer.analyze(question)).thenReturn(
                new QueryAnalysis(question, List.of("DELIVERY.REF", "DELIVERY", "MD.DEAL", "MD"), List.of()));
        when(vectorStoreService.findByExactTableName(anyString())).thenReturn(Optional.empty());
        when(vectorStoreService.findByExactTableName("MD.DEAL")).thenReturn(Optional.of(mdDeal()));

        Optional<String> context = ragService.buildContext(question, ChatContext.EMPTY);

        assertThat(context).get().asString().contains("Table : MD.DEAL").contains("Champ sélectionné : DELIVERY.REF")
                .contains("identifies each separate delivery").doesNotContain(MD_FEE_SETTLEMENT_DELIVERY_REF);
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_fieldWithTheSameRealDescriptionInEveryTable_carriesThatCommonDescription() {
        String common = "Denotes the Ending/ closing balance of the migrated transaction Validation: Standard T24 amount field "
                + "with a length of 20 characters";
        String question = "c quoi ENDING.BALANCE ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("ENDING.BALANCE", "ENDING"), List.of()));
        when(vectorStoreService.findByExactTableName(anyString())).thenReturn(Optional.empty());
        when(vectorStoreService.findTablesWithField("ENDING.BALANCE")).thenReturn(List.of(
                realTable("u1", "USRETL.STMT.ENTRY.HIS", "USRETL", "Non renseignée dans HELP_TEXT.", "ENDING.BALANCE", common),
                realTable("u2", "USRETL.STMT.ENTRY", "USRETL", "Non renseignée dans HELP_TEXT.", "ENDING.BALANCE", common)));

        Optional<String> context = ragService.buildContext(question, ChatContext.EMPTY);

        assertThat(context).get().asString().contains(RagServiceImpl.COMMON_DESCRIPTION_LABEL + common);
    }

    @Test
    void buildContext_tableNameAskedWithoutModule_isNeverTakenForAHomonymousField() {
        // Règle table > champ (2026-10-02) conservée : « À quoi sert MD.DEAL ? » reste une question sur la table.
        String question = "À quoi sert MD.DEAL ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("MD.DEAL", "MD"), List.of()));
        when(vectorStoreService.findByExactTableName(anyString())).thenReturn(Optional.empty());
        when(vectorStoreService.findByExactTableName("MD.DEAL")).thenReturn(Optional.of(mdDeal()));
        when(hybridSearchService.search(any(), anyInt(), anyDouble())).thenReturn(List.of(mdDeal()));

        ragService.buildContext(question, ChatContext.EMPTY);

        verify(vectorStoreService, never()).findTablesWithField("MD.DEAL");
        verify(hybridSearchService).search(any(), anyInt(), anyDouble());
    }

    @Test
    void buildContext_dottedFieldOnlyDocumentedOutsideTheModule_inSeveralTables_asksWhichTable() {
        // DELIVERY.REF demandé dans une conversation TT (la table TT utilisée ici ne le documente pas).
        when(vectorStoreService.findAllByProduct("TT")).thenReturn(List.of(realTable("t1", "TT.CASH.DENOM", "ESTELL",
                "This is a live table which will record all the deposit and withdrawal denominations.", "CURRENCY",
                "This field captures the Currency from the @ID of CURRENCY table")));
        String question = "À quoi sert DELIVERY.REF ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("DELIVERY.REF", "DELIVERY"), List.of()));
        when(vectorStoreService.findTablesWithField("DELIVERY.REF")).thenReturn(List.of(mdDeal(), mdFeeSettlement()));

        Optional<String> context = ragService.buildContext(question, new ChatContext("TT", null, null));

        assertThat(context).get().asString().startsWith(
                "Le champ DELIVERY.REF existe dans plusieurs tables de la documentation (hors module TT) : MD.DEAL, MD.FEE.SETTLEMENT.");
    }

    @Test
    void buildContext_prefixOfTheAskedDottedField_isNeverTakenForAFieldOfTheModule() {
        // Bug réel (test live EB, 2026-10-06) : « c quoi DATE.TO.USE ? » -> réponse sur le champ DATE de
        // CANNEX.EXCEPTION.LOG (EB), « DATE » n'étant que le préfixe extrait de DATE.TO.USE. DATE.TO.USE est documenté
        // dans deux tables hors EB (INT.MOVEMENT.PARAM / ST, CNTELL.DAILY.MATCHING.PARAM / CNTELL).
        Document cannex = realTable("eb1", "CANNEX.EXCEPTION.LOG", "EB", "Non renseignée dans HELP_TEXT.", "DATE", "Date when the error occurs");
        Document intMovement = realTable("st1", "INT.MOVEMENT.PARAM", "ST", "Non renseignée dans HELP_TEXT.", "DATE.TO.USE",
                "Defines if the Booking Date ot the Value date is to be used as the key of the extracted info");
        Document cntell = realTable("cn1", "CNTELL.DAILY.MATCHING.PARAM", "CNTELL", "Non renseignée dans HELP_TEXT.", "DATE.TO.USE",
                "Defines if the Booking Date or the Value date is to be used as the entry date of the extract");
        when(vectorStoreService.findAllByProduct("EB")).thenReturn(List.of(cannex));
        String question = "c quoi DATE.TO.USE ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("DATE.TO.USE", "DATE"), List.of()));
        when(vectorStoreService.findTablesWithField("DATE.TO.USE")).thenReturn(List.of(intMovement, cntell));

        Optional<String> context = ragService.buildContext(question, new ChatContext("EB", null, null));

        assertThat(context).get().asString()
                .doesNotContain("Date when the error occurs")
                .contains("CNTELL.DAILY.MATCHING.PARAM, INT.MOVEMENT.PARAM")
                .contains("used as the key of the extracted info")
                .contains("used as the entry date of the extract");
    }

    @Test
    void buildContext_ordinaryFrenchWordInLowercase_isNeverLookedUpAsAFieldOutsideTheModule() {
        when(vectorStoreService.findAllByProduct("LD")).thenReturn(List.of(realTable("l1", "LD.LOANS.AND.DEPOSITS", "LD", "Loans.", "VALUE.DATE", "Value date.")));
        String question = "quelle date est utilisée ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("DATE"), List.of()));
        when(hybridSearchService.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of());

        ragService.buildContext(question, new ChatContext("LD", null, null));

        verify(vectorStoreService, never()).findTablesWithField(anyString());
    }

    @Test
    void buildContext_capitalisedWordThatIsAVeryCommonFieldName_keepsTheModuleSearch() {
        // « CURRENCY » est un champ de 561 tables : sans point, au-delà de 5 tables, ce n'est pas un champ précis.
        List<Document> currencyTables = List.of(
                realTable("c1", "USRETL.CHECK.COLLECTION", "USRETL", "Non renseignée dans HELP_TEXT.", "CURRENCY",
                        "Check Currency. This is currently defaulted to USD, other currency checks are currently out of scope."),
                realTable("c2", "AA.ACTIVITY.BALANCES", "AA", "Non renseignée dans HELP_TEXT.", "CURRENCY",
                        "This field stores the Currency of the Arrangement contract"),
                realTable("c3", "AA.ARR.STATEMENT", "AA", "Non renseignée dans HELP_TEXT.", "CURRENCY",
                        "Holds the currency of the account. Validation Rules: 3 alphabetic characters. This is a NOINPUT field."),
                realTable("c4", "AA.BILL.DETAILS.HIST", "AA", "Non renseignée dans HELP_TEXT.", "CURRENCY",
                        "This field represents the currency of the arrangement."),
                realTable("c5", "AA.BILL.DETAILS", "AA", "Non renseignée dans HELP_TEXT.", "CURRENCY",
                        "This field represents the currency of the arrangement."),
                realTable("c6", "AA.CUSTOMER.ARRANGEMENT.HIST", "AA", "Non renseignée dans HELP_TEXT.", "CURRENCY",
                        "Indicates the currency of the arrangement"));
        when(vectorStoreService.findAllByProduct("LD")).thenReturn(List.of(realTable("l1", "BILL.REGISTER", "LD",
                "Non renseignée dans HELP_TEXT.", "MATURITY.DATE", HELP_TEXT_UNAVAILABLE)));
        String question = "Comment est géré le CURRENCY ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("CURRENCY"), List.of()));
        when(vectorStoreService.findTablesWithField("CURRENCY")).thenReturn(currencyTables);
        when(hybridSearchService.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of());

        ragService.buildContext(question, new ChatContext("LD", null, null));

        verify(hybridSearchService).search(any(), anyInt(), anyDouble(), eq("LD"));
    }

    @Test
    void buildContext_prefixOfADottedTableName_isNeverCountedAsASecondNamedTable() {
        // « ACCOUNT » n'est ici que le préfixe extrait de « ACCOUNT.CLOSURE » : une seule table est nommée.
        Document closure = realTable("t1", "ACCOUNT.CLOSURE", "AC", "This file contains details such as outstanding interest and charges, "
                + "and is used to indicate that an account is flagged for closure.", "CLOSURE.DATE", "Date of closure.");
        when(vectorStoreService.findAllByProduct("AC")).thenReturn(List.of(closure));
        String question = "À quoi sert ACCOUNT.CLOSURE ?";
        when(queryAnalyzer.analyze(question)).thenReturn(new QueryAnalysis(question, List.of("ACCOUNT.CLOSURE", "ACCOUNT"), List.of()));
        when(vectorStoreService.findByExactTableName("ACCOUNT.CLOSURE")).thenReturn(Optional.of(closure));

        Optional<String> context = ragService.buildContext(question, new ChatContext("AC", null, null));

        assertThat(context).get().asString().contains("flagged for closure");
        verify(vectorStoreService, never()).findByExactTableName("ACCOUNT");
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_moduleOnlyContext_keywordIsAlsoATableOfTheModule_tableWinsOverAHomonymousField() {
        // Bug réel (AC, 2026-10-02) : « Quel est le rôle de ACCOUNT ? » répondait sur le champ ACCOUNT d'une table de
        // cartes au lieu de la table ACCOUNT : la correspondance exacte de table doit primer.
        Document account = new Document("a1", "Table : ACCOUNT\nProduit : AC\n\nDescription générale :\nThe Account Module caters for accounts.",
                Map.of("tableName", "ACCOUNT", "product", "AC"));
        Document card = new Document("a2", "Table : CARD.ISSUE\nProduit : AC\n\nChamps (1) :\n\n"
                + "ACCOUNT\n\nDescription :\nThe account to which the card is issued.\n\nType :\nText\n\nInput :\nOptionnel\n\n",
                Map.of("tableName", "CARD.ISSUE", "product", "AC"));
        when(vectorStoreService.findAllByProduct("AC")).thenReturn(List.of(account, card));
        QueryAnalysis analysis = new QueryAnalysis("Quel est le rôle de ACCOUNT ?", List.of("ACCOUNT"), List.of());
        when(queryAnalyzer.analyze("Quel est le rôle de ACCOUNT ?")).thenReturn(analysis);
        when(hybridSearchService.search(eq(analysis), anyInt(), anyDouble(), eq("AC"))).thenReturn(List.of(account));

        Optional<String> context = ragService.buildContext("Quel est le rôle de ACCOUNT ?", new ChatContext("AC", null, null));

        assertThat(context).get().asString().contains("The Account Module caters for accounts.")
                .doesNotContain("card is issued");
    }

    @Test
    void buildContext_moduleOnlyContext_tableQualifiedFieldName_stillMatchesTheFieldOfThatTable() {
        String customer = "Table : CUSTOMER\nProduit : ST\n\nChamps (1) :\n\n"
                + "CIN\n\nDescription :\nCarte d'identité nationale\n\nType :\nText\n\nInput :\nObligatoire\n\n";
        when(vectorStoreService.findAllByProduct("ST")).thenReturn(List.of(
                new Document("c1", customer, Map.of("tableName", "CUSTOMER", "product", "ST"))));
        QueryAnalysis analysis = new QueryAnalysis("À quoi sert CUSTOMER.CIN ?", List.of("CUSTOMER.CIN"), List.of());
        when(queryAnalyzer.analyze("À quoi sert CUSTOMER.CIN ?")).thenReturn(analysis);

        Optional<String> context = ragService.buildContext("À quoi sert CUSTOMER.CIN ?", new ChatContext("ST", null, null));

        assertThat(context).get().asString().contains("Carte d'identité nationale");
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void buildContext_moduleOnlyContext_exactFieldIdentifierStillTakesPriorityOverTheScopedSemanticSearch() {
        String text = "Table : LC.TYPES\nProduit : LC\n\nChamps (1) :\n\n"
                + "DOC.CLASS\n\nDescription :\nClasse documentaire.\n\nType :\nText\n\nInput :\nOptionnel\n\n";
        Document lcTypes = new Document("lc1", text, Map.of("tableName", "LC.TYPES", "product", "LC"));
        when(vectorStoreService.findAllByProduct("LC")).thenReturn(List.of(lcTypes));
        QueryAnalysis analysis = new QueryAnalysis("A quoi sert DOC.CLASS ?", List.of("DOC.CLASS"), List.of());
        when(queryAnalyzer.analyze("A quoi sert DOC.CLASS ?")).thenReturn(analysis);

        Optional<String> context = ragService.buildContext("A quoi sert DOC.CLASS ?", new ChatContext("LC", null, null));

        assertThat(context).get().asString().contains("Classe documentaire.");
        verifyNoInteractions(hybridSearchService);
    }
}
