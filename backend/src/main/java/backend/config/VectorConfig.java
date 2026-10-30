package backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration de la couche RAG (index vectoriel). Propriétés sous {@code rag.vector-store.*}
 * dans {@code application.properties} :
 * <ul>
 *   <li>{@code rag.vector-store.path} — fichier JSON où l'index (documents + embeddings) est
 *   persisté sur disque ;</li>
 *   <li>{@code rag.vector-store.top-k} — nombre de documents les plus proches renvoyés par une
 *   recherche sémantique ;</li>
 *   <li>{@code rag.vector-store.min-score} — score de similarité cosinus minimal (0 à 1) pour
 *   qu'un document sémantique soit retenu dans le contexte. Une correspondance exacte de nom de
 *   table (voir {@code RagServiceImpl}) n'est jamais filtrée par ce seuil.</li>
 *   <li>{@code rag.vector-store.context-top-k} — nombre de documents effectivement envoyés à
 *   Llama3 après fusion/reranking, distinct de {@code top-k} (le rappel de la recherche elle-même
 *   reste large pour ne pas manquer un résultat pertinent ; seul ce qui est réellement injecté
 *   dans le prompt est réduit, ce qui est le principal levier de temps de réponse identifié en
 *   conditions réelles — voir {@code RagServiceImpl}).</li>
 *   <li>{@code rag.vector-store.sync-on-startup} — réconciliation incrémentale de l'index au démarrage.</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "rag.vector-store")
@Getter
@Setter
public class VectorConfig {

    private String path = "data/vector/index.json";

    private int topK = 8;

    private double minScore = 0.58;

    /**
     * Score cosinus minimal d'un résultat vectoriel quand la recherche est restreinte à un module
     * (voir {@code RagServiceImpl.buildModuleScopedContext}). Distinct de {@link #minScore}, calibré
     * pour la recherche sur tout l'index : mesuré sur l'index réel (questions conceptuelles en
     * français, 7 modules), les passages pertinents d'un module obtiennent typiquement 0.53-0.63 —
     * sous 0.58, ils étaient écartés même une fois le module correctement filtré. Le filtre de module
     * borne déjà le bruit à un seul module, ce qui justifie un seuil plus bas.
     */
    private double moduleMinScore = 0.50;

    /**
     * Poids du reranking lexical (termes anglais du glossaire, pondérés par IDF) ajouté au cosinus — voir
     * {@code VectorStoreServiceImpl.similaritySearch}. 0.20 mesuré sur l'index réel (2026-10-02), 7 questions
     * conceptuelles + 7 questions témoins non utilisées pour régler le glossaire : table attendue dans le top-4
     * pour 7/7 et 6/7 (contre 3/7 et 3/7 avant) ; 0.10 laissait ARCHIVE (EB) et MD.BALANCES hors du top-4,
     * 0.30 dégradait une question témoin EB. 0 = cosinus seul (comportement antérieur).
     */
    private double lexicalWeight = 0.20;

    private int contextTopK = 4;

    /**
     * Au démarrage, réconcilie l'index persisté avec HELP_TEXT/HELP_FIELD (nouveaux fichiers XML,
     * fichiers modifiés, tables supprimées) en ne ré-embeddant que les documents concernés — voir
     * {@code RagServiceImpl.buildIndexIfNeeded}. Désactivable ({@code rag.vector-store.sync-on-startup=false})
     * pour un démarrage plus rapide quand le référentiel XML est figé.
     */
    private boolean syncOnStartup = true;
}
