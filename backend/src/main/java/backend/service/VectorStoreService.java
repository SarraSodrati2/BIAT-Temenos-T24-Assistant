package backend.service;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Optional;

/**
 * Magasin vectoriel "compatible Spring AI" : opère sur des {@link Document} Spring AI (mêmes
 * types que ceux qu'accepterait un {@code org.springframework.ai.vectorstore.VectorStore} du
 * framework), ce qui permet de basculer plus tard vers une implémentation Spring AI standard
 * (pgvector, Redis, ...) sans changer les appelants ({@code RagService}).
 *
 * <p>Implémentation actuelle : index "flat" en mémoire (recherche Top-K par similarité cosinus,
 * force brute), persisté en JSON sur disque — voir {@code VectorStoreServiceImpl}.
 */
public interface VectorStoreService {

    /** @return true si un index a déjà été persisté sur disque. */
    boolean isIndexPersisted();

    /** Charge l'index persisté en mémoire (à appeler seulement si {@link #isIndexPersisted()}). */
    void load();

    /** Embedde {@code documents} (via {@link EmbeddingService}, modèle mxbai-embed-large) et les indexe en mémoire. */
    void add(List<Document> documents);

    /** Persiste l'index actuellement en mémoire sur disque. */
    void save();

    /** Embedde {@code query} puis renvoie les {@code topK} documents les plus proches (similarité cosinus), tous modules confondus. */
    default List<Document> similaritySearch(String query, int topK) {
        return similaritySearch(query, topK, null);
    }

    /**
     * Comme {@link #similaritySearch(String, int)}, mais le filtre de module est appliqué PENDANT la
     * recherche : seuls les documents dont la métadonnée {@code product} vaut {@code product}
     * (insensible à la casse) sont comparés au vecteur de la question, et le top-K est calculé sur ce
     * seul sous-ensemble. Équivalent d'un {@code SearchRequest.filterExpression("product == 'X'")} de
     * Spring AI : un document d'un autre module ne peut donc jamais occuper une place du top-K.
     *
     * @param product code du module/produit T24 (ex. "EB") ; {@code null} ou vide = aucun filtre.
     */
    default List<Document> similaritySearch(String query, int topK, String product) {
        return similaritySearch(query, topK, product, List.of());
    }

    /**
     * Comme {@link #similaritySearch(String, int, String)}, avec un reranking lexical : chaque document
     * candidat (TOUS ceux du module, pas seulement le top-K vectoriel) reçoit, en plus de sa similarité
     * cosinus, un bonus proportionnel à la part des {@code lexicalTerms} qu'il contient, chaque terme pondéré
     * par sa rareté dans l'index (IDF) et compté double s'il figure dans la description générale.
     *
     * @param lexicalTerms termes anglais de la documentation (voir {@code ConceptGlossary}) ; vide = cosinus seul.
     */
    List<Document> similaritySearch(String query, int topK, String product, List<String> lexicalTerms);

    /** Nombre de documents de l'index contenant {@code term} (début de mot, insensible à la casse). */
    int documentFrequency(String term);

    /** Nombre de documents actuellement indexés. */
    int size();

    /**
     * Cohésion thématique d'un ensemble de documents : moyenne des similarités cosinus de leurs embeddings pris
     * deux à deux (1 = même sujet). Ids inconnus ignorés ; moins de deux documents connus -&gt; 1.0.
     */
    double meanPairwiseSimilarity(List<String> documentIds);

    /**
     * Correspondance exacte (insensible à la casse) sur la métadonnée {@code tableName}, sans
     * embedding ni similarité — utilisé par {@code RagService} pour garantir qu'une question
     * nommant explicitement une table (ex. "Que fait ACCOUNT ?") retrouve bien cette table, même
     * si la recherche sémantique pure la classerait plus bas que des tables au vocabulaire proche.
     */
    Optional<Document> findByExactTableName(String tableName);

    /**
     * Tous les documents (une entrée par table) appartenant au module/produit T24 {@code product}
     * (correspondance exacte insensible à la casse sur la métadonnée {@code product}), sans
     * embedding ni score — utilisé par {@code RagService} pour restreindre la recherche à un module
     * choisi (parcours à bulles de /chatbot) sans filtrer un par un des milliers de documents.
     */
    List<Document> findAllByProduct(String product);

    /**
     * Tables (tous modules confondus) qui documentent un champ nommé exactement {@code fieldName} (insensible à la
     * casse), sans embedding — pour qu'un champ cité tel quel dans une question soit retrouvé même s'il est documenté
     * hors du module sélectionné.
     */
    List<Document> findTablesWithField(String fieldName);

    /** Tous les documents de l'index (une entrée par table), sans embedding ni score. */
    List<Document> findAll();

    /**
     * Réconcilie l'index en mémoire avec {@code documents} (état courant de HELP_TEXT/HELP_FIELD,
     * source de vérité) SANS reconstruire tout l'index : seuls les documents nouveaux ou dont le texte
     * ou les métadonnées ont changé sont (ré)embeddés, ceux qui n'existent plus sont retirés, les autres
     * gardent leur embedding existant. N'écrit rien sur disque (voir {@link #save()}).
     */
    SyncResult synchronize(List<Document> documents);

    /** Bilan de {@link #synchronize} : nombre de documents ajoutés, modifiés, supprimés et inchangés. */
    record SyncResult(int added, int updated, int removed, int unchanged) {

        public boolean hasChanges() {
            return added + updated + removed > 0;
        }
    }
}
