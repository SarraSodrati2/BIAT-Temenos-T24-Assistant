package backend.service.impl;

import backend.dto.QueryAnalysis;
import backend.entity.HelpField;
import backend.service.HelpSearchService;
import backend.service.VectorStoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridSearchServiceImplTest {

    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private HelpSearchService helpSearchService;

    @InjectMocks
    private HybridSearchServiceImpl hybridSearchService;

    @Test
    void search_exactTableMatch_isRankedAboveAHigherScoringVectorResult() {
        Document exact = new Document("t1", "Table : CUSTOMER", Map.of("tableName", "CUSTOMER"));
        // Résultat vectoriel avec un score cosinus élevé, mais pas une correspondance exacte.
        Document semantic = new Document("t2", "Table : NOISE", Map.of("tableName", "NOISE")).mutate().score(0.99).build();

        QueryAnalysis analysis = new QueryAnalysis("Résume CUSTOMER", List.of("CUSTOMER"), List.of());
        when(vectorStoreService.findByExactTableName("CUSTOMER")).thenReturn(Optional.of(exact));
        when(helpSearchService.findByFieldName(anyString())).thenReturn(List.of());
        when(helpSearchService.search(anyString())).thenReturn(List.of());
        when(vectorStoreService.similaritySearch(analysis.getEnrichedQueryForEmbedding(), 5, null, List.of())).thenReturn(List.of(semantic));

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5);

        assertThat(results).extracting(Document::getId).containsExactly("t1", "t2");
    }

    @Test
    void search_columnNameMatch_resolvesToOwningTable() {
        HelpField field = HelpField.builder().tableName("CUSTOMER").fieldName("CIN").build();
        Document customerDoc = new Document("t1", "Table : CUSTOMER", Map.of("tableName", "CUSTOMER"));

        QueryAnalysis analysis = new QueryAnalysis("À quoi sert CIN ?", List.of("CIN"), List.of());
        when(vectorStoreService.findByExactTableName("CIN")).thenReturn(Optional.empty());
        when(helpSearchService.findByFieldName("CIN")).thenReturn(List.of(field));
        when(vectorStoreService.findByExactTableName("CUSTOMER")).thenReturn(Optional.of(customerDoc));
        when(helpSearchService.search(anyString())).thenReturn(List.of());
        when(vectorStoreService.similaritySearch(anyString(), org.mockito.ArgumentMatchers.eq(5), isNull(), anyList())).thenReturn(List.of());

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5);

        assertThat(results).extracting(Document::getId).containsExactly("t1");
    }

    @Test
    void search_columnNameMatchingTooManyTables_isIgnoredAsNoise() {
        // Un mot-clé syntaxique trop générique (ex. présent comme nom de champ dans des dizaines de
        // tables sans rapport) ne doit pas produire de faux positifs à 0.95 : ce n'est pas un signal
        // exploitable, contrairement à un nom de champ réellement discriminant.
        List<HelpField> tooManyMatches = List.of(
                HelpField.builder().tableName("FOREX").fieldName("STATUS").build(),
                HelpField.builder().tableName("BATCH").fieldName("STATUS").build(),
                HelpField.builder().tableName("DM.SERVICE.SCHEDULER").fieldName("STATUS").build(),
                HelpField.builder().tableName("FT.TAPE.PARAMS").fieldName("STATUS").build());
        Document customerDoc = new Document("t1", "Table : CUSTOMER", Map.of("tableName", "CUSTOMER"));

        QueryAnalysis analysis = new QueryAnalysis("STATUS CUSTOMER ?", List.of("STATUS", "CUSTOMER"), List.of());
        when(vectorStoreService.findByExactTableName("STATUS")).thenReturn(Optional.empty());
        when(vectorStoreService.findByExactTableName("CUSTOMER")).thenReturn(Optional.of(customerDoc));
        when(helpSearchService.findByFieldName("STATUS")).thenReturn(tooManyMatches);
        when(helpSearchService.findByFieldName("CUSTOMER")).thenReturn(List.of());
        when(helpSearchService.search(anyString())).thenReturn(List.of());
        when(vectorStoreService.similaritySearch(anyString(), org.mockito.ArgumentMatchers.eq(5), isNull(), anyList())).thenReturn(List.of());

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5);

        // Seul CUSTOMER (correspondance exacte de table) est retenu ; FOREX/BATCH/... jamais ajoutés.
        assertThat(results).extracting(Document::getId).containsExactly("t1");
    }

    @Test
    void search_keywordFuzzyMatch_resolvesToOwningTable_belowExactMatchesButAboveVector() {
        HelpField field = HelpField.builder().tableName("EB.CUSTOMER.ONBOARDING").fieldName("SOMETHING").build();
        Document fuzzyDoc = new Document("t2", "Table : EB.CUSTOMER.ONBOARDING", Map.of("tableName", "EB.CUSTOMER.ONBOARDING"));
        Document exactDoc = new Document("t1", "Table : CUSTOMER", Map.of("tableName", "CUSTOMER"));

        QueryAnalysis analysis = new QueryAnalysis("question", List.of("CUSTOMER"), List.of());
        when(vectorStoreService.findByExactTableName("CUSTOMER")).thenReturn(Optional.of(exactDoc));
        when(helpSearchService.findByFieldName(anyString())).thenReturn(List.of());
        when(helpSearchService.search("CUSTOMER")).thenReturn(List.of(field));
        when(vectorStoreService.findByExactTableName("EB.CUSTOMER.ONBOARDING")).thenReturn(Optional.of(fuzzyDoc));
        when(vectorStoreService.similaritySearch(anyString(), org.mockito.ArgumentMatchers.eq(5), isNull(), anyList())).thenReturn(List.of());

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5);

        assertThat(results).extracting(Document::getId).containsExactly("t1", "t2");
    }

    @Test
    void search_vectorResultsBelowMinScore_areExcluded() {
        Document weak = new Document("t1", "Table : NOISE", Map.of("tableName", "NOISE")).mutate().score(0.3).build();
        // Aucun mot-clé/terme métier : les canaux table/colonne/mots-clés ne sont même pas
        // interrogés (allSearchTerms() vide), seul le canal vectoriel est utilisé.
        QueryAnalysis analysis = new QueryAnalysis("question hors sujet", List.of(), List.of());

        when(vectorStoreService.similaritySearch(anyString(), org.mockito.ArgumentMatchers.eq(5), isNull(), anyList())).thenReturn(List.of(weak));

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5);

        assertThat(results).isEmpty();
    }

    @Test
    void search_sameTableFoundByMultipleChannels_isNotDuplicated() {
        Document exact = new Document("t1", "Table : CUSTOMER", Map.of("tableName", "CUSTOMER"));
        Document sameAsVectorResult = new Document("t1", "Table : CUSTOMER", Map.of("tableName", "CUSTOMER")).mutate().score(0.9).build();

        QueryAnalysis analysis = new QueryAnalysis("Résume CUSTOMER", List.of("CUSTOMER"), List.of());
        when(vectorStoreService.findByExactTableName("CUSTOMER")).thenReturn(Optional.of(exact));
        when(helpSearchService.findByFieldName(anyString())).thenReturn(List.of());
        when(helpSearchService.search(anyString())).thenReturn(List.of());
        when(vectorStoreService.similaritySearch(anyString(), org.mockito.ArgumentMatchers.eq(5), isNull(), anyList())).thenReturn(List.of(sameAsVectorResult));

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5);

        assertThat(results).hasSize(1);
    }

    @Test
    void search_respectsTopKAfterFusion() {
        List<Document> manyVectorMatches = List.of(
                doc("v1", 0.9), doc("v2", 0.8), doc("v3", 0.7), doc("v4", 0.6), doc("v5", 0.55));
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());

        when(vectorStoreService.similaritySearch(anyString(), org.mockito.ArgumentMatchers.eq(3), isNull(), anyList())).thenReturn(manyVectorMatches);

        List<Document> results = hybridSearchService.search(analysis, 3, 0.5);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(Document::getId).containsExactly("v1", "v2", "v3");
    }

    @Test
    void search_scopedToModule_passesTheModuleToTheVectorSearchInsteadOfFilteringAGlobalTopK() {
        Document inModule = scopedDoc("eb1", "EB.ARCHIVE", "EB", 0.70);
        QueryAnalysis analysis = new QueryAnalysis("Pourquoi supprime-t-on les vieilles données ?", List.of(), List.of());
        when(vectorStoreService.similaritySearch(analysis.getEnrichedQueryForEmbedding(), 5, "EB", List.of())).thenReturn(List.of(inModule));

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5, "EB");

        assertThat(results).extracting(Document::getId).containsExactly("eb1");
        // Jamais d'appel non filtré : le top-K est calculé dans le module, pas sur tout l'index.
        verify(vectorStoreService, never()).similaritySearch(anyString(), anyInt(), isNull(), anyList());
    }

    @Test
    void search_scopedToModule_exactTableOfAnotherModule_isIgnored() {
        Document otherModuleTable = scopedDoc("t1", "CUSTOMER", "ST", null);
        QueryAnalysis analysis = new QueryAnalysis("Résume CUSTOMER", List.of("CUSTOMER"), List.of());
        when(vectorStoreService.findByExactTableName("CUSTOMER")).thenReturn(Optional.of(otherModuleTable));
        when(helpSearchService.findByFieldName(anyString())).thenReturn(List.of());
        when(helpSearchService.searchInProduct(anyString(), eq("EB"))).thenReturn(List.of());
        when(vectorStoreService.similaritySearch(anyString(), eq(5), eq("EB"), anyList())).thenReturn(List.of());

        assertThat(hybridSearchService.search(analysis, 5, 0.5, "EB")).isEmpty();
    }

    @Test
    void search_scopedToModule_columnSelectivityIsMeasuredWithinTheModuleNotAcrossAllOfT24() {
        // Un nom de champ présent dans 5 tables T24 (> MAX_TABLES_PER_TERM) mais dans UNE SEULE table du
        // module actif est un signal précis pour ce module : il ne doit pas être écarté comme "trop générique"
        // à cause des occurrences des autres modules.
        List<HelpField> fields = List.of(
                HelpField.builder().product("DE").tableName("DE.MESSAGE").fieldName("MSG.TYPE").build(),
                HelpField.builder().product("AC").tableName("AC.A").fieldName("MSG.TYPE").build(),
                HelpField.builder().product("FT").tableName("FT.B").fieldName("MSG.TYPE").build(),
                HelpField.builder().product("SC").tableName("SC.C").fieldName("MSG.TYPE").build(),
                HelpField.builder().product("LD").tableName("LD.D").fieldName("MSG.TYPE").build());
        Document deMessage = scopedDoc("de1", "DE.MESSAGE", "DE", null);
        QueryAnalysis analysis = new QueryAnalysis("MSG.TYPE", List.of("MSG.TYPE"), List.of());
        when(vectorStoreService.findByExactTableName("MSG.TYPE")).thenReturn(Optional.empty());
        when(helpSearchService.findByFieldName("MSG.TYPE")).thenReturn(fields);
        when(vectorStoreService.findByExactTableName("DE.MESSAGE")).thenReturn(Optional.of(deMessage));
        when(helpSearchService.searchInProduct("MSG.TYPE", "DE")).thenReturn(List.of());
        when(vectorStoreService.similaritySearch(anyString(), eq(5), eq("DE"), anyList())).thenReturn(List.of());

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5, "DE");

        assertThat(results).extracting(Document::getId).containsExactly("de1");
        assertThat(results.get(0).getScore()).isEqualTo(0.95);
    }

    @Test
    void search_scopedToModule_keywordChannelQueriesTheModuleNotTheGlobalTop20() {
        HelpField field = HelpField.builder().product("LC").tableName("LC.TYPES").fieldName("X").build();
        Document lcTypes = scopedDoc("lc1", "LC.TYPES", "LC", null);
        QueryAnalysis analysis = new QueryAnalysis("question", List.of("DOCUMENTARY"), List.of());
        when(vectorStoreService.findByExactTableName("DOCUMENTARY")).thenReturn(Optional.empty());
        when(helpSearchService.findByFieldName("DOCUMENTARY")).thenReturn(List.of());
        when(helpSearchService.searchInProduct("DOCUMENTARY", "LC")).thenReturn(List.of(field));
        when(vectorStoreService.findByExactTableName("LC.TYPES")).thenReturn(Optional.of(lcTypes));
        when(vectorStoreService.similaritySearch(anyString(), eq(5), eq("LC"), anyList())).thenReturn(List.of());

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5, "LC");

        assertThat(results).extracting(Document::getId).containsExactly("lc1");
        verify(helpSearchService, never()).search(anyString());
    }

    @Test
    void search_scopedToModule_exactMatchStillRanksAboveVectorResultsOfTheModule() {
        Document exact = scopedDoc("tt1", "TT.TELLER", "TT", null);
        Document semantic = scopedDoc("tt2", "TT.OTHER", "TT", 0.99);
        QueryAnalysis analysis = new QueryAnalysis("Résume TT.TELLER", List.of("TT.TELLER"), List.of());
        when(vectorStoreService.findByExactTableName("TT.TELLER")).thenReturn(Optional.of(exact));
        when(helpSearchService.findByFieldName(anyString())).thenReturn(List.of());
        when(helpSearchService.searchInProduct(anyString(), eq("TT"))).thenReturn(List.of());
        when(vectorStoreService.similaritySearch(anyString(), eq(5), eq("TT"), anyList())).thenReturn(List.of(semantic));

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5, "TT");

        assertThat(results).extracting(Document::getId).containsExactly("tt1", "tt2");
    }

    @Test
    void search_scopedToModule_neverReturnsADocumentOfAnotherModule_evenIfAChannelLeaks() {
        // Garde-fou final : même si un canal renvoyait par erreur un document d'un autre module, il ne
        // doit jamais sortir de la recherche restreinte.
        Document leaked = scopedDoc("x1", "STK.EXC.JP", "SC", 0.95);
        Document inModule = scopedDoc("eb1", "EB.ARCHIVE", "EB", 0.70);
        QueryAnalysis analysis = new QueryAnalysis("question", List.of(), List.of());
        when(vectorStoreService.similaritySearch(anyString(), eq(5), eq("EB"), anyList())).thenReturn(List.of(leaked, inModule));

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5, "EB");

        assertThat(results).extracting(Document::getId).containsExactly("eb1");
    }

    @Test
    void search_conceptTerms_arePassedToTheVectorRerank_butNeverUsedAsExactTableNames() {
        Document tellerId = scopedDoc("tt1", "TELLER.ID", "TT", 0.80);
        QueryAnalysis analysis = new QueryAnalysis("Comment ouvrir une caisse ?", List.of(), List.of(), List.of("open", "till"));
        when(vectorStoreService.similaritySearch(analysis.getEnrichedQueryForEmbedding(), 5, "TT", List.of("open", "till")))
                .thenReturn(List.of(tellerId));

        List<Document> results = hybridSearchService.search(analysis, 5, 0.5, "TT");

        assertThat(results).extracting(Document::getId).containsExactly("tt1");
        verify(vectorStoreService, never()).findByExactTableName(anyString());
    }

    private static Document scopedDoc(String id, String tableName, String product, Double score) {
        Document document = new Document(id, "Table : " + tableName, Map.of("tableName", tableName, "product", product));
        return score == null ? document : document.mutate().score(score).build();
    }

    private static Document doc(String id, double score) {
        return new Document(id, "Table : " + id, Map.of("tableName", id)).mutate().score(score).build();
    }
}
