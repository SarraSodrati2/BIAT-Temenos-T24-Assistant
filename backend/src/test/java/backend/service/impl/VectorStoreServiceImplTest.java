package backend.service.impl;

import backend.config.VectorConfig;
import backend.service.EmbeddingService;
import backend.service.VectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorStoreServiceImplTest {

    @Mock
    private EmbeddingService embeddingService;

    @TempDir
    Path tempDir;

    private VectorStoreServiceImpl vectorStoreService;

    private final Document chargeoff = new Document("t1", "Table : AA.ARR.CHARGEOFF", Map.of("tableName", "AA.ARR.CHARGEOFF"));
    private final Document customer = new Document("t2", "Table : CUSTOMER", Map.of("tableName", "CUSTOMER"));
    private final Document account = new Document("t3", "Table : ACCOUNT", Map.of("tableName", "ACCOUNT"));

    private VectorConfig vectorConfig;

    @BeforeEach
    void setUp() {
        vectorConfig = new VectorConfig();
        vectorConfig.setPath(tempDir.resolve("index.json").toString());
        vectorStoreService = new VectorStoreServiceImpl(embeddingService, vectorConfig, new ObjectMapper());
    }

    @Test
    void isIndexPersisted_falseUntilSaved() {
        assertThat(vectorStoreService.isIndexPersisted()).isFalse();

        when(embeddingService.embedAll(List.of(chargeoff.getText()))).thenReturn(List.of(new float[]{1f, 0f, 0f}));
        vectorStoreService.add(List.of(chargeoff));
        vectorStoreService.save();

        assertThat(vectorStoreService.isIndexPersisted()).isTrue();
    }

    @Test
    void saveThenLoad_roundTripsDocumentsAndEmbeddingsAcrossANewInstance() {
        when(embeddingService.embedAll(List.of(chargeoff.getText(), customer.getText())))
                .thenReturn(List.of(new float[]{1f, 0f, 0f}, new float[]{0f, 1f, 0f}));
        vectorStoreService.add(List.of(chargeoff, customer));
        vectorStoreService.save();

        // Nouvelle instance sans état en mémoire : simule un redémarrage de l'application.
        VectorStoreServiceImpl reloaded = new VectorStoreServiceImpl(embeddingService, vectorConfig, new ObjectMapper());
        reloaded.load();

        when(embeddingService.embed("CHARGEOFF")).thenReturn(new float[]{1f, 0f, 0f});
        List<Document> results = reloaded.similaritySearch("CHARGEOFF", 2);

        assertThat(results).extracting(Document::getId).containsExactly("t1", "t2");
        assertThat(results.get(0).getMetadata()).containsEntry("tableName", "AA.ARR.CHARGEOFF");
    }

    @Test
    void similaritySearch_ranksClosestVectorFirst() {
        when(embeddingService.embedAll(List.of(chargeoff.getText(), customer.getText(), account.getText())))
                .thenReturn(List.of(
                        new float[]{1f, 0f, 0f},
                        new float[]{0f, 1f, 0f},
                        new float[]{0.9f, 0.1f, 0f}));
        vectorStoreService.add(List.of(chargeoff, customer, account));

        when(embeddingService.embed("query")).thenReturn(new float[]{1f, 0f, 0f});
        List<Document> results = vectorStoreService.similaritySearch("query", 2);

        assertThat(results).extracting(Document::getId).containsExactly("t1", "t3");
    }

    @Test
    void similaritySearch_respectsTopKLimit() {
        when(embeddingService.embedAll(List.of(chargeoff.getText(), customer.getText(), account.getText())))
                .thenReturn(List.of(new float[]{1f, 0f, 0f}, new float[]{0f, 1f, 0f}, new float[]{0f, 0f, 1f}));
        vectorStoreService.add(List.of(chargeoff, customer, account));

        when(embeddingService.embed("query")).thenReturn(new float[]{1f, 0f, 0f});

        assertThat(vectorStoreService.similaritySearch("query", 1)).hasSize(1);
        assertThat(vectorStoreService.similaritySearch("query", 3)).hasSize(3);
    }

    @Test
    void similaritySearch_attachesTheCosineSimilarityScoreToEachResult() {
        when(embeddingService.embedAll(List.of(chargeoff.getText()))).thenReturn(List.of(new float[]{1f, 0f, 0f}));
        vectorStoreService.add(List.of(chargeoff));

        when(embeddingService.embed("query")).thenReturn(new float[]{1f, 0f, 0f});
        List<Document> results = vectorStoreService.similaritySearch("query", 1);

        assertThat(results.get(0).getScore()).isEqualTo(1.0);
    }

    @Test
    void findByExactTableName_caseInsensitiveMatch() {
        when(embeddingService.embedAll(List.of(chargeoff.getText(), customer.getText())))
                .thenReturn(List.of(new float[]{1f, 0f, 0f}, new float[]{0f, 1f, 0f}));
        vectorStoreService.add(List.of(chargeoff, customer));

        assertThat(vectorStoreService.findByExactTableName("customer")).isPresent()
                .get().extracting(Document::getId).isEqualTo("t2");
    }

    // --- Recherche vectorielle restreinte à un module -------------------------------------------

    private final Document ebArchive = new Document("eb1", "Table : EB.ARCHIVE", Map.of("tableName", "EB.ARCHIVE", "product", "EB"));
    private final Document ebOther = new Document("eb2", "Table : EB.OTHER", Map.of("tableName", "EB.OTHER", "product", "EB"));
    private final Document stkJp = new Document("x1", "Table : STK.EXC.JP", Map.of("tableName", "STK.EXC.JP", "product", "SC"));
    private final Document stkFr = new Document("x2", "Table : STK.EXC.FR", Map.of("tableName", "STK.EXC.FR", "product", "SC"));
    private final Document limitReview = new Document("x3", "Table : LIMIT.REVIEW.FQU", Map.of("tableName", "LIMIT.REVIEW.FQU", "product", "LI"));

    /**
     * Reproduit le cas réel EB/ARCHIVE : trois documents d'autres modules sont PLUS proches de la
     * question que le passage pertinent du module EB. Un top-3 global ne contient donc que des
     * documents hors module (qu'un filtre a posteriori viderait entièrement).
     */
    private void indexArchiveScenario() {
        when(embeddingService.embedAll(List.of(stkJp.getText(), stkFr.getText(), limitReview.getText(), ebArchive.getText(), ebOther.getText())))
                .thenReturn(List.of(
                        new float[]{0.99f, 0.14f, 0f},
                        new float[]{0.98f, 0.20f, 0f},
                        new float[]{0.97f, 0.24f, 0f},
                        new float[]{0.80f, 0.60f, 0f},
                        new float[]{0f, 0f, 1f}));
        vectorStoreService.add(List.of(stkJp, stkFr, limitReview, ebArchive, ebOther));
        lenient().when(embeddingService.embed("archivage")).thenReturn(new float[]{1f, 0f, 0f});
    }

    @Test
    void similaritySearch_globalTopK_isFilledByOtherModules_whichIsTheBugTheScopedSearchFixes() {
        indexArchiveScenario();

        List<Document> global = vectorStoreService.similaritySearch("archivage", 3);

        // Constat du problème d'origine : aucun document EB dans le top-3 global.
        assertThat(global).extracting(document -> document.getMetadata().get("product")).doesNotContain("EB");
    }

    @Test
    void similaritySearch_scopedToModule_relevantPassageIsNotEvictedByCloserDocumentsOfOtherModules() {
        indexArchiveScenario();

        List<Document> scoped = vectorStoreService.similaritySearch("archivage", 3, "EB");

        assertThat(scoped).extracting(Document::getId).containsExactly("eb1", "eb2");
        assertThat(scoped.get(0).getScore()).isGreaterThan(scoped.get(1).getScore());
    }

    @Test
    void similaritySearch_scopedToModule_neverReturnsADocumentOfAnotherModule() {
        indexArchiveScenario();

        List<Document> scoped = vectorStoreService.similaritySearch("archivage", 10, "EB");

        assertThat(scoped).isNotEmpty()
                .allSatisfy(document -> assertThat(document.getMetadata()).containsEntry("product", "EB"));
    }

    @Test
    void similaritySearch_scopedToModule_isCaseInsensitiveOnTheModuleCode() {
        indexArchiveScenario();

        assertThat(vectorStoreService.similaritySearch("archivage", 3, "eb")).extracting(Document::getId).containsExactly("eb1", "eb2");
    }

    @Test
    void similaritySearch_unknownModule_returnsEmptyWithoutCallingTheEmbeddingModel() {
        when(embeddingService.embedAll(List.of(ebArchive.getText()))).thenReturn(List.of(new float[]{1f, 0f, 0f}));
        vectorStoreService.add(List.of(ebArchive));

        assertThat(vectorStoreService.similaritySearch("archivage", 3, "ZZ")).isEmpty();
        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    void findAllByProduct_returnsOnlyThatModulesDocuments_caseInsensitive() {
        indexArchiveScenario();

        assertThat(vectorStoreService.findAllByProduct("eb")).extracting(Document::getId).containsExactlyInAnyOrder("eb1", "eb2");
    }

    @Test
    void findTablesWithField_matchesOnlyAWholeFieldBlock_inAnyModule_caseInsensitive() {
        Document shared = new Document("po1", "Table : AC.SHARED.ACCOUNT\nProduit : PO\n\nChamps (2) :\n\nMAIN.ACCT\n\nDescription :\nLead.\n\n"
                + "----------------------------------\n\nCP.ACCT\n\nDescription :\nSystem field which contains accounts used in AC.CASH.POOL.",
                Map.of("tableName", "AC.SHARED.ACCOUNT", "product", "PO"));
        // Mentionne CP.ACCT dans une description, sans avoir de champ de ce nom : ne doit pas compter.
        Document mention = new Document("ac1", "Table : ACCOUNT\nProduit : AC\n\nChamps (1) :\n\nPOOL\n\nDescription :\nSee CP.ACCT.",
                Map.of("tableName", "ACCOUNT", "product", "AC"));
        when(embeddingService.embedAll(List.of(shared.getText(), mention.getText()))).thenReturn(List.of(new float[]{1f, 0f}, new float[]{0f, 1f}));
        vectorStoreService.add(List.of(shared, mention));

        assertThat(vectorStoreService.findTablesWithField("cp.acct")).extracting(Document::getId).containsExactly("po1");
        assertThat(vectorStoreService.findTablesWithField("ACCT")).isEmpty();
    }

    @Test
    void scopedSearch_stillWorksAfterReloadingThePersistedIndex() {
        indexArchiveScenario();
        vectorStoreService.save();

        VectorStoreServiceImpl reloaded = new VectorStoreServiceImpl(embeddingService, vectorConfig, new ObjectMapper());
        reloaded.load();

        assertThat(reloaded.similaritySearch("archivage", 3, "EB")).extracting(Document::getId).containsExactly("eb1", "eb2");
    }

    // --- Reranking lexical (termes anglais du glossaire, pondérés par IDF) ------------------------------

    private final Document formType = new Document("de1",
            "Table : DE.FORM.TYPE\nProduit : DE\n\nDescription générale :\nThe Form Type Table defines the width and depth of each printed form.\n\nChamps (1) :\n\nFORM.WIDTH",
            Map.of("tableName", "DE.FORM.TYPE", "product", "DE"));
    private final Document printMarkers = new Document("de2",
            "Table : DE.PRINT.MARKERS\nProduit : DE\n\nDescription générale :\nMarkers used when printing delivery messages.\n\nChamps (1) :\n\nTEXT",
            Map.of("tableName", "DE.PRINT.MARKERS", "product", "DE"));
    private final Document widthOnlyInFields = new Document("de3",
            "Table : DE.OTHER\nProduit : DE\n\nDescription générale :\nAnother delivery table.\n\nChamps (1) :\n\nFIELD\n\nDescription :\nThe width of the column.",
            Map.of("tableName", "DE.OTHER", "product", "DE"));

    /** Cas réel DE : DE.PRINT.MARKERS est un peu plus proche en cosinus, mais seul DE.FORM.TYPE parle de largeur/profondeur. */
    private void indexDeliveryScenario() {
        when(embeddingService.embedAll(List.of(formType.getText(), printMarkers.getText(), widthOnlyInFields.getText())))
                .thenReturn(List.of(new float[]{0.80f, 0.60f, 0f}, new float[]{0.86f, 0.51f, 0f}, new float[]{0.85f, 0.527f, 0f}));
        vectorStoreService.add(List.of(formType, printMarkers, widthOnlyInFields));
        lenient().when(embeddingService.embed("largeur")).thenReturn(new float[]{1f, 0f, 0f});
    }

    @Test
    void similaritySearch_withoutLexicalTerms_ranksByCosineOnly() {
        indexDeliveryScenario();

        assertThat(vectorStoreService.similaritySearch("largeur", 3, "DE"))
                .extracting(Document::getId).containsExactly("de2", "de3", "de1");
    }

    @Test
    void similaritySearch_lexicalTermsInTheOverview_liftTheDocumentThatReallyDescribesTheConcept() {
        indexDeliveryScenario();

        List<Document> results = vectorStoreService.similaritySearch("largeur", 3, "DE", List.of("width", "depth"));

        // DE.FORM.TYPE (width + depth dans la description générale) passe devant ; DE.OTHER (width seulement dans
        // un champ) compte à moitié et reste devant DE.PRINT.MARKERS, qui ne contient aucun des deux termes.
        assertThat(results).extracting(Document::getId).containsExactly("de1", "de3", "de2");
    }

    @Test
    void similaritySearch_lexicalBonus_neverLiftsAResultToTheKeywordChannelScore() {
        indexDeliveryScenario();

        List<Document> results = vectorStoreService.similaritySearch("largeur", 3, "DE", List.of("width", "depth"));

        // DE.FORM.TYPE (cosinus 0.80 + bonus 0.20) est plafonné à 0.84 : jamais au niveau du canal mots-clés (0.85)
        // de HybridSearchServiceImpl, ni a fortiori des correspondances exactes.
        assertThat(results.get(0).getId()).isEqualTo("de1");
        assertThat(results.get(0).getScore()).isEqualTo(0.84);
    }

    @Test
    void similaritySearch_lexicalRerank_staysWithinTheModuleFilter() {
        indexDeliveryScenario();
        Document widthElsewhere = new Document("x9", "Table : X\nProduit : ST\n\nDescription générale :\nwidth and depth everywhere.",
                Map.of("tableName", "X", "product", "ST"));
        when(embeddingService.embedAll(List.of(formType.getText(), printMarkers.getText(), widthOnlyInFields.getText(), widthElsewhere.getText())))
                .thenReturn(List.of(new float[]{0.80f, 0.60f, 0f}, new float[]{0.86f, 0.51f, 0f}, new float[]{0.85f, 0.527f, 0f}, new float[]{1f, 0f, 0f}));
        vectorStoreService.add(List.of(formType, printMarkers, widthOnlyInFields, widthElsewhere));

        assertThat(vectorStoreService.similaritySearch("largeur", 10, "DE", List.of("width", "depth")))
                .extracting(doc -> doc.getMetadata().get("product")).containsOnly("DE");
    }

    @Test
    void meanPairwiseSimilarity_isHighForSameTopicDocuments_andLowForUnrelatedOnes() {
        indexArchiveScenario(); // x1/x2/x3 presque colinéaires, eb2 orthogonal

        assertThat(vectorStoreService.meanPairwiseSimilarity(List.of("x1", "x2", "x3"))).isGreaterThan(0.99);
        assertThat(vectorStoreService.meanPairwiseSimilarity(List.of("x1", "eb2"))).isLessThan(0.01);
        assertThat(vectorStoreService.meanPairwiseSimilarity(List.of("x1", "unknown-id"))).isEqualTo(1.0);
    }

    @Test
    void documentFrequency_countsDocumentsContainingTheWordStart() {
        indexDeliveryScenario();

        assertThat(vectorStoreService.documentFrequency("width")).isEqualTo(2);
        assertThat(vectorStoreService.documentFrequency("print")).isEqualTo(2); // "printed", "printing"
        assertThat(vectorStoreService.documentFrequency("absentterm")).isZero();
        assertThat(vectorStoreService.size()).isEqualTo(3);
    }

    // --- Synchronisation incrémentale avec HELP_TEXT/HELP_FIELD ----------------------------------

    @Test
    void synchronize_embedsOnlyNewAndChangedDocuments_andRemovesDeletedOnes() {
        when(embeddingService.embedAll(List.of(chargeoff.getText(), customer.getText())))
                .thenReturn(List.of(new float[]{1f, 0f, 0f}, new float[]{0f, 1f, 0f}));
        vectorStoreService.add(List.of(chargeoff, customer));

        Document customerModified = new Document("t2", "Table : CUSTOMER\nNouveau champ", Map.of("tableName", "CUSTOMER"));
        Document newTable = new Document("t4", "Table : EB.NEW", Map.of("tableName", "EB.NEW", "product", "EB"));
        when(embeddingService.embedAll(List.of(customerModified.getText(), newTable.getText())))
                .thenReturn(List.of(new float[]{0f, 1f, 0f}, new float[]{0f, 0f, 1f}));

        // chargeoff (t1) supprimé, customer (t2) modifié, EB.NEW (t4) ajouté.
        VectorStoreService.SyncResult result = vectorStoreService.synchronize(List.of(customerModified, newTable));

        assertThat(result).isEqualTo(new VectorStoreService.SyncResult(1, 1, 1, 0));
        assertThat(vectorStoreService.findByExactTableName("AA.ARR.CHARGEOFF")).isEmpty();
        assertThat(vectorStoreService.findByExactTableName("CUSTOMER")).get().extracting(Document::getText).isEqualTo("Table : CUSTOMER\nNouveau champ");
        // La nouvelle table est aussitôt visible dans la recherche restreinte à son module.
        when(embeddingService.embed("q")).thenReturn(new float[]{0f, 0f, 1f});
        assertThat(vectorStoreService.similaritySearch("q", 3, "EB")).extracting(Document::getId).containsExactly("t4");
    }

    @Test
    void synchronize_nothingChanged_embedsNothing() {
        when(embeddingService.embedAll(List.of(chargeoff.getText()))).thenReturn(List.of(new float[]{1f, 0f, 0f}));
        vectorStoreService.add(List.of(chargeoff));

        VectorStoreService.SyncResult result = vectorStoreService.synchronize(List.of(chargeoff));

        assertThat(result.hasChanges()).isFalse();
        assertThat(result.unchanged()).isEqualTo(1);
        verify(embeddingService, times(1)).embedAll(anyList());
    }

    @Test
    void findByExactTableName_noMatch_returnsEmpty() {
        when(embeddingService.embedAll(List.of(chargeoff.getText()))).thenReturn(List.of(new float[]{1f, 0f, 0f}));
        vectorStoreService.add(List.of(chargeoff));

        assertThat(vectorStoreService.findByExactTableName("UNKNOWN.TABLE")).isEmpty();
    }
}
