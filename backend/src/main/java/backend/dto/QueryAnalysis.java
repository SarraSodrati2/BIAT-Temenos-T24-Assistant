package backend.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Résultat de l'analyse d'une question par {@code QueryAnalyzer} : mots-clés extraits
 * syntaxiquement, synonymes métier ajoutés, et requête enrichie prête pour l'embedding.
 */
public class QueryAnalysis {

    private final String originalQuestion;
    private final List<String> extractedKeywords;
    private final List<String> expandedTerms;
    private final List<String> conceptTerms;
    private final String enrichedQueryForEmbedding;

    public QueryAnalysis(String originalQuestion, List<String> extractedKeywords, List<String> expandedTerms) {
        this(originalQuestion, extractedKeywords, expandedTerms, List.of());
    }

    /**
     * @param conceptTerms termes anglais de la documentation correspondant aux mots français de la question
     * (voir {@code ConceptGlossary}) : ajoutés à la requête d'embedding et au reranking lexical, mais JAMAIS à
     * {@link #allSearchTerms()} — "caisse" -&gt; "till" ne doit pas déclencher une correspondance exacte de table.
     */
    public QueryAnalysis(String originalQuestion, List<String> extractedKeywords, List<String> expandedTerms,
                         List<String> conceptTerms) {
        this.originalQuestion = originalQuestion;
        this.extractedKeywords = List.copyOf(extractedKeywords);
        this.expandedTerms = List.copyOf(expandedTerms);
        this.conceptTerms = List.copyOf(conceptTerms);
        List<String> enrichment = new ArrayList<>(this.expandedTerms);
        enrichment.addAll(this.conceptTerms);
        this.enrichedQueryForEmbedding = buildEnrichedQuery(originalQuestion, enrichment);
    }

    public String getOriginalQuestion() {
        return originalQuestion;
    }

    public List<String> getExtractedKeywords() {
        return extractedKeywords;
    }

    public List<String> getExpandedTerms() {
        return expandedTerms;
    }

    /** Termes anglais (glossaire) des concepts français de la question — recherche vectorielle/lexicale uniquement. */
    public List<String> getConceptTerms() {
        return conceptTerms;
    }

    /** Question d'origine complétée des termes métier ajoutés : utilisée pour l'embedding (recherche vectorielle). */
    public String getEnrichedQueryForEmbedding() {
        return enrichedQueryForEmbedding;
    }

    /** Mots-clés extraits + termes métier ajoutés, dédupliqués (insensible à la casse) : base des recherches exactes/mots-clés. */
    public List<String> allSearchTerms() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> all = new ArrayList<>();
        for (String term : extractedKeywords) {
            if (seen.add(term.toUpperCase())) {
                all.add(term);
            }
        }
        for (String term : expandedTerms) {
            if (seen.add(term.toUpperCase())) {
                all.add(term);
            }
        }
        return all;
    }

    private static String buildEnrichedQuery(String originalQuestion, List<String> expandedTerms) {
        return expandedTerms.isEmpty() ? originalQuestion : originalQuestion + " " + String.join(" ", expandedTerms);
    }
}
