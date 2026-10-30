package backend.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableKeywordExtractorTest {

    @Test
    void extractCandidates_dottedTableName_isExtractedAsIs() {
        List<String> candidates = TableKeywordExtractor.extractCandidates("Que fait la table AA.ARR.CHARGEOFF ?");

        assertThat(candidates).contains("AA.ARR.CHARGEOFF");
    }

    @Test
    void extractCandidates_dottedFieldName_alsoAddsItsTablePrefixAsFallback() {
        List<String> candidates = TableKeywordExtractor.extractCandidates("À quoi sert CUSTOMER.CIN ?");

        assertThat(candidates).contains("CUSTOMER.CIN", "CUSTOMER");
    }

    @Test
    void extractCandidates_plainUppercaseWord_isExtracted() {
        List<String> candidates = TableKeywordExtractor.extractCandidates("Décris la table CUSTOMER.");

        assertThat(candidates).contains("CUSTOMER");
    }

    @Test
    void extractCandidates_noUppercaseKeyword_returnsEmpty() {
        List<String> candidates = TableKeywordExtractor.extractCandidates("quelle est la recette de la tarte tatin ?");

        assertThat(candidates).isEmpty();
    }

    @Test
    void extractCandidates_noDuplicates() {
        List<String> candidates = TableKeywordExtractor.extractCandidates("CUSTOMER CUSTOMER.CIN CUSTOMER");

        assertThat(candidates).containsExactly("CUSTOMER.CIN", "CUSTOMER");
    }
}
