package backend.service.impl;

import backend.dto.QueryAnalysis;
import backend.service.ConceptGlossary;
import backend.service.QueryAnalyzer;
import backend.service.SynonymDictionary;
import backend.utils.TableKeywordExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Combine l'extraction syntaxique ({@link TableKeywordExtractor}) et l'enrichissement métier
 * ({@link SynonymDictionary}) : chaque mot-clé syntaxique est étendu via le dictionnaire, et le
 * texte complet de la question est en plus scanné pour les termes métier en minuscules (ex.
 * "client", "coordonnées") qu'aucune extraction en MAJUSCULES ne peut voir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryAnalyzerImpl implements QueryAnalyzer {

    private final SynonymDictionary synonymDictionary;
    private final ConceptGlossary conceptGlossary;

    @Override
    public QueryAnalysis analyze(String question) {
        List<String> extractedKeywords = TableKeywordExtractor.extractCandidates(question);

        // LinkedHashSet : un mot-clé syntaxique déjà présent dans la question (ex. "KYC") est aussi
        // détecté par findMatchingKeys (scan du texte complet) — dédoublonne sans perdre l'ordre.
        LinkedHashSet<String> expandedTerms = new LinkedHashSet<>();
        for (String keyword : extractedKeywords) {
            expandedTerms.addAll(synonymDictionary.expand(keyword));
        }
        expandedTerms.addAll(synonymDictionary.findMatchingKeys(question));

        List<String> conceptTerms = conceptGlossary.englishTermsFor(question);
        QueryAnalysis analysis = new QueryAnalysis(question, extractedKeywords, new ArrayList<>(expandedTerms), conceptTerms);

        log.info("Mots-clés extraits :\n{}", extractedKeywords.isEmpty() ? "(aucun)" : String.join(", ", extractedKeywords));
        log.info("Synonymes ajoutés :\n{}", expandedTerms.isEmpty() ? "(aucun)" : String.join(", ", expandedTerms));
        log.info("Concepts français -> termes anglais de la documentation : {}",
                conceptTerms.isEmpty() ? "(aucun)" : String.join(", ", conceptTerms));

        return analysis;
    }
}
