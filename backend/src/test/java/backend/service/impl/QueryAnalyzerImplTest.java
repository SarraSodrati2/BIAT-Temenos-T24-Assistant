package backend.service.impl;

import backend.dto.QueryAnalysis;
import backend.service.ConceptGlossary;
import backend.service.SynonymDictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryAnalyzerImplTest {

    @Mock
    private SynonymDictionary synonymDictionary;
    @Mock
    private ConceptGlossary conceptGlossary;

    @InjectMocks
    private QueryAnalyzerImpl queryAnalyzer;

    @Test
    void analyze_syntacticKeyword_isExpandedViaSynonymDictionary() {
        when(synonymDictionary.expand("KYC")).thenReturn(List.of("IDENTITE", "CUSTOMER"));
        when(synonymDictionary.findMatchingKeys("Quelle table contient les informations KYC ?")).thenReturn(List.of());

        QueryAnalysis analysis = queryAnalyzer.analyze("Quelle table contient les informations KYC ?");

        assertThat(analysis.getExtractedKeywords()).contains("KYC");
        assertThat(analysis.getExpandedTerms()).contains("IDENTITE", "CUSTOMER");
        assertThat(analysis.allSearchTerms()).contains("KYC", "IDENTITE", "CUSTOMER");
    }

    @Test
    void analyze_lowercaseBusinessWord_isCapturedViaFindMatchingKeys() {
        String question = "Quelle table contient les coordonnées du client ?";
        when(synonymDictionary.findMatchingKeys(question)).thenReturn(List.of("CUSTOMER"));

        QueryAnalysis analysis = queryAnalyzer.analyze(question);

        // "client"/"coordonnées" sont en minuscules : TableKeywordExtractor (majuscules) n'extrait rien,
        // seul SynonymDictionary.findMatchingKeys les détecte.
        assertThat(analysis.getExtractedKeywords()).isEmpty();
        assertThat(analysis.getExpandedTerms()).contains("CUSTOMER");
    }

    @Test
    void analyze_noKeywordNoSynonym_producesAnEmptyAnalysis() {
        String question = "Quelle est la recette de la tarte tatin ?";
        when(synonymDictionary.findMatchingKeys(question)).thenReturn(List.of());

        QueryAnalysis analysis = queryAnalyzer.analyze(question);

        assertThat(analysis.getExtractedKeywords()).isEmpty();
        assertThat(analysis.getExpandedTerms()).isEmpty();
        assertThat(analysis.getEnrichedQueryForEmbedding()).isEqualTo(question);
    }

    @Test
    void analyze_frenchConcepts_enrichTheEmbeddingQuery_butNeverTheExactSearchTerms() {
        String question = "Comment ouvrir, attribuer et clôturer une caisse ?";
        when(synonymDictionary.findMatchingKeys(question)).thenReturn(List.of());
        when(conceptGlossary.englishTermsFor(question)).thenReturn(List.of("open", "assign", "close", "till"));

        QueryAnalysis analysis = queryAnalyzer.analyze(question);

        assertThat(analysis.getConceptTerms()).containsExactly("open", "assign", "close", "till");
        assertThat(analysis.getEnrichedQueryForEmbedding()).isEqualTo(question + " open assign close till");
        // "till" ne doit jamais déclencher une correspondance exacte de table (voir QueryAnalysis).
        assertThat(analysis.allSearchTerms()).doesNotContain("open", "assign", "close", "till");
    }
}
