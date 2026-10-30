package backend.service;

import backend.dto.QueryAnalysis;

/**
 * Analyse une question libre avant toute recherche : extraction syntaxique des mots-clés
 * ressemblant à un nom de table/champ ({@code TableKeywordExtractor}), puis enrichissement par
 * synonymes métier ({@link SynonymDictionary}) — acronymes (KYC), mots métier français
 * (client, compte, coordonnées), alias de champ (CIN).
 */
public interface QueryAnalyzer {

    QueryAnalysis analyze(String question);
}
