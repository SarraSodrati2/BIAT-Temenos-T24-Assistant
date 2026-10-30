package backend.service;

import backend.dto.QueryAnalysis;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Recherche hybride : combine correspondance exacte de nom de table, correspondance exacte de nom
 * de colonne (résolue vers sa table), recherche mots-clés floue, et recherche vectorielle
 * sémantique — puis fusionne et classe (reranking) le tout par score, une correspondance exacte
 * étant toujours prioritaire sur un simple score vectoriel.
 */
public interface HybridSearchService {

    /**
     * @param analysis résultat de {@link QueryAnalyzer#analyze(String)}
     * @param topK nombre maximal de documents renvoyés
     * @param minScore score minimal pour qu'un résultat purement vectoriel soit retenu (les
     * correspondances exactes/mots-clés ne sont jamais filtrées par ce seuil)
     */
    default List<Document> search(QueryAnalysis analysis, int topK, double minScore) {
        return search(analysis, topK, minScore, null);
    }

    /**
     * Comme {@link #search(QueryAnalysis, int, double)}, mais restreinte au module/produit T24
     * {@code product} sur CHAQUE canal (table exacte, colonne exacte, mots-clés, vectoriel) avant la
     * fusion et le top-K : un document d'un autre module ne peut ni être renvoyé, ni évincer un
     * document du module actif.
     *
     * @param product code du module (métadonnée {@code product} des documents) ; {@code null} ou vide =
     * recherche sur tout l'index.
     */
    List<Document> search(QueryAnalysis analysis, int topK, double minScore, String product);
}
