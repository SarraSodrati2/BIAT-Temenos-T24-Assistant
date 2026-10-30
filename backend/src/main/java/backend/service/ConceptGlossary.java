package backend.service;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Pont entre le vocabulaire français d'une question et les termes anglais réellement employés par la
 * documentation T24 ({@code rag/fr-en-glossary.properties}). Sert uniquement à la RECHERCHE (embedding,
 * reranking lexical) et à la vérification des réponses — jamais affiché, jamais ajouté au Contexte du LLM.
 */
public interface ConceptGlossary {

    /** Termes anglais correspondant aux mots français reconnus dans {@code text} (dédoublonnés, dans l'ordre). */
    List<String> englishTermsFor(String text);

    /**
     * Concepts « spécifiques » reconnus dans {@code text} : mot français (forme normalisée du glossaire) -&gt;
     * ses termes anglais. Un concept est spécifique quand au moins un de ses termes anglais est rare dans
     * l'index (voir {@link #calibrate}) — "largeur"/width l'est, "définir"/define ne l'est pas. Seuls ces
     * concepts servent à vérifier qu'une réponse est réellement étayée par sa source.
     */
    Map<String, List<String>> specificConceptsIn(String text);

    /**
     * Confronte le glossaire à l'index documentaire réellement chargé : retire tout terme anglais absent
     * du référentiel ({@code documentFrequency == 0}) et détermine quels concepts sont spécifiques.
     */
    void calibrate(ToIntFunction<String> documentFrequency, int corpusSize);
}
