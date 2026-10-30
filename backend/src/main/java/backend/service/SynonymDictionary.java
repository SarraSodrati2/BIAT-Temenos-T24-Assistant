package backend.service;

import java.util.List;

/**
 * Dictionnaire de synonymes métier T24 (acronymes, mots métier français, alias) configurable via
 * {@code src/main/resources/rag/business-synonyms.properties} — pas de recompilation nécessaire
 * pour ajouter une entrée.
 */
public interface SynonymDictionary {

    /** Termes vers lesquels enrichir la recherche pour {@code term} (vide si aucune entrée ne correspond). */
    List<String> expand(String term);

    /**
     * Recherche, dans un texte libre (une question entière), toute clé du dictionnaire qui y
     * apparaît comme mot entier (insensible à la casse et aux accents) — capture les termes métier
     * en minuscules (ex. "client", "coordonnées") qu'une extraction purement syntaxique de mots en
     * MAJUSCULES ne verrait jamais. Renvoie l'union des expansions de toutes les clés trouvées.
     */
    List<String> findMatchingKeys(String text);
}
