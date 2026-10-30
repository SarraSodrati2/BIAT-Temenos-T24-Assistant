package backend.service.impl;

import backend.dto.QueryAnalysis;
import backend.entity.HelpField;
import backend.service.HelpSearchService;
import backend.service.HybridSearchService;
import backend.service.VectorStoreService;
import backend.perf.Perf;
import backend.perf.Perf.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recherche hybride : 3 canaux non vectoriels — table exacte, colonne exacte (résolue vers sa
 * table via {@link HelpSearchService}, déjà utilisé et mis en cache par l'ancien pipeline par
 * mots-clés), mots-clés flous — puis un canal vectoriel sémantique ({@link VectorStoreService}).
 *
 * <p>Reranking : chaque canal se voit attribuer un score de confiance fixe et décroissant
 * (table exacte {@code 1.0} &gt; colonne exacte {@code 0.95} &gt; mots-clés {@code 0.85}) tandis que
 * le canal vectoriel garde son vrai score cosinus (typiquement 0.55-0.80 en pratique) ; un simple
 * tri par score décroissant suffit donc à faire remonter toute correspondance exacte au-dessus
 * d'un résultat purement sémantique, sans comparateur dédié.
 *
 * <p>Restriction à un module ({@code product} non vide) : appliquée DANS chaque canal, avant la fusion
 * et le top-K — table exacte (document hors module ignoré), colonne exacte (champs HELP_FIELD filtrés
 * par module AVANT le garde-fou {@link #MAX_TABLES_PER_TERM}, qui mesure donc la sélectivité du terme
 * dans le module et non dans tout T24), mots-clés (requête SQL filtrée par module avant son plafond de
 * lignes) et vectoriel ({@link VectorStoreService#similaritySearch(String, int, String)}, pré-filtre).
 * Avant ce correctif, le top-K était calculé sur tout l'index (400+ modules) puis filtré par
 * {@code RagServiceImpl} : des documents d'autres modules pouvaient occuper tout le top-K et évincer
 * les passages pertinents du module choisi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchServiceImpl implements HybridSearchService {

    private static final double EXACT_TABLE_SCORE = 1.0;
    private static final double EXACT_COLUMN_SCORE = 0.95;
    private static final double KEYWORD_SCORE = 0.85;

    /**
     * Un terme dont le nom de champ/mot-clé correspond à plus de {@code MAX_TABLES_PER_TERM} tables
     * distinctes n'est pas un signal fiable — constaté en conditions réelles : le synonyme "KYC"
     * s'étend vers des mots métier génériques (IDENTIFICATION, VERIFICATION) qui sont aussi des noms
     * de champ dans des dizaines de tables sans rapport (FOREX, BATCH, ...). Sans ce garde-fou, ces
     * faux positifs recevaient le même score de confiance (0.95/0.85) qu'une vraie correspondance
     * précise et noyaient CUSTOMER (pourtant classé en tête) sous du bruit dans le contexte envoyé à
     * Llama3, qui répondait alors "non trouvé". Un nom de colonne/mot-clé réellement discriminant ne
     * doit appartenir qu'à une poignée de tables, pas à des dizaines.
     */
    private static final int MAX_TABLES_PER_TERM = 3;

    private final VectorStoreService vectorStoreService;
    private final HelpSearchService helpSearchService;

    @Override
    public List<Document> search(QueryAnalysis analysis, int topK, double minScore, String product) {
        String scope = isScoped(product) ? product.trim() : null;
        log.info("Recherche hybride — filtre module : {} | top-K : {} | score vectoriel minimal : {} | mots-clés : {} | "
                        + "termes étendus : {}.",
                scope != null ? "product=" + scope : "aucun (index complet)", topK, minScore,
                analysis.getExtractedKeywords(), analysis.allSearchTerms().size());

        Set<String> seenIds = new LinkedHashSet<>();
        List<Document> ranked = new ArrayList<>();

        // Recherche par table : sûre pour TOUS les termes (mots-clés syntaxiques + synonymes
        // étendus) car c'est une correspondance exacte — un terme sans rapport n'y trouve
        // simplement rien.
        List<Document> tableMatches = Perf.time(Category.EXACT_SEARCH, "recherche exacte de table (hybride)",
                () -> searchByTableName(analysis.allSearchTerms(), seenIds, scope));

        // Recherche par colonne/mots-clés : restreinte aux SEULS mots-clés syntaxiques (les mots de
        // la question elle-même), pas aux synonymes métier étendus. Constaté en conditions réelles :
        // des synonymes volontairement larges (KYC -> IDENTIFICATION, VERIFICATION, CLIENT...)
        // trouvent chacun quelques tables différentes par simple coïncidence de nom de champ (FOREX,
        // BATCH...) ; le garde-fou MAX_TABLES_PER_TERM filtre un terme individuellement trop
        // générique, mais plusieurs termes seulement modérément génériques peuvent encore, mis bout à
        // bout, noyer le contexte. Les synonymes étendus jouent déjà leur rôle via la recherche par
        // table ci-dessus (ex. KYC -> CUSTOMER, une correspondance exacte) ; les canaux plus risqués
        // n'ont pas besoin d'eux en plus.
        List<String> syntacticTerms = analysis.getExtractedKeywords();
        List<Document> columnMatches = Perf.time(Category.EXACT_SEARCH, "recherche exacte de colonne (hybride, Oracle)",
                () -> searchByColumnName(syntacticTerms, seenIds, scope));
        List<Document> keywordMatches = Perf.time(Category.KEYWORD_SEARCH, "recherche par mots-clés (hybride, Oracle)",
                () -> searchByKeyword(syntacticTerms, seenIds, scope));
        ranked.addAll(tableMatches);
        ranked.addAll(columnMatches);
        ranked.addAll(keywordMatches);
        log.info("Canaux exacts : {} table(s) exacte(s), {} via colonne exacte, {} via mots-clés :\n{}",
                tableMatches.size(), columnMatches.size(), keywordMatches.size(),
                format(concat(tableMatches, columnMatches, keywordMatches)));

        List<Document> vectorMatches = vectorStoreService.similaritySearch(analysis.getEnrichedQueryForEmbedding(), topK, scope,
                analysis.getConceptTerms());
        log.info("Recherche vectorielle ({} résultat(s)) :\n{}", vectorMatches.size(), format(vectorMatches));

        for (Document doc : vectorMatches) {
            Double score = doc.getScore();
            if (score != null && score < minScore) {
                break; // trié par score décroissant : tout le reste est encore plus bas.
            }
            if (seenIds.add(doc.getId())) {
                ranked.add(doc);
            }
        }
        log.info("Résultats fusionnés : {} document(s) avant reranking.", ranked.size());

        long rerankStart = System.nanoTime();
        ranked.sort(Comparator.comparingDouble(HybridSearchServiceImpl::scoreOf).reversed());
        Perf.record(Category.RERANKING, "fusion + tri des résultats (reranking)", System.nanoTime() - rerankStart);
        List<Document> retained = ranked.size() > topK ? ranked.subList(0, topK) : ranked;
        if (scope != null) {
            // Garde-fou : chaque canal est déjà filtré, ceci ne doit jamais rien retirer (sinon un
            // canal a une fuite de module, signalée en WARN pour être corrigée à la source).
            List<Document> inScope = retained.stream().filter(doc -> inScope(doc, scope)).toList();
            if (inScope.size() != retained.size()) {
                log.warn("{} document(s) hors module {} retiré(s) après fusion : un canal de recherche n'a pas "
                        + "appliqué le filtre de module.", retained.size() - inScope.size(), scope);
            }
            retained = inScope;
        }

        log.info("Documents retenus :\n{}", format(retained));
        log.info("Score final :\n{}", formatScoresOnly(retained));

        return retained;
    }

    private List<Document> searchByTableName(List<String> terms, Set<String> seenIds, String scope) {
        List<Document> matches = new ArrayList<>();
        for (String term : terms) {
            vectorStoreService.findByExactTableName(term)
                    .filter(doc -> inScope(doc, scope))
                    .map(doc -> doc.mutate().score(EXACT_TABLE_SCORE).build())
                    .filter(doc -> seenIds.add(doc.getId()))
                    .ifPresent(matches::add);
        }
        return matches;
    }

    /** Résout un nom de colonne/champ vers sa table propriétaire (réutilise {@link HelpSearchService}, déjà en cache). */
    private List<Document> searchByColumnName(List<String> terms, Set<String> seenIds, String scope) {
        List<Document> matches = new ArrayList<>();
        for (String term : terms) {
            List<HelpField> fields = helpSearchService.findByFieldName(term).stream()
                    .filter(field -> scope == null || scope.equalsIgnoreCase(trimmed(field.getProduct())))
                    .toList();
            for (String tableName : selectiveTableNames(fields)) {
                vectorStoreService.findByExactTableName(tableName)
                        .filter(doc -> inScope(doc, scope))
                        .map(doc -> doc.mutate().score(EXACT_COLUMN_SCORE).build())
                        .filter(doc -> seenIds.add(doc.getId()))
                        .ifPresent(matches::add);
            }
        }
        return matches;
    }

    /** Recherche floue multi-colonnes (FIELD_NAME/DESCRIPTION/TABLE_NAME/PRODUCT), résolue vers les tables concernées. */
    private List<Document> searchByKeyword(List<String> terms, Set<String> seenIds, String scope) {
        List<Document> matches = new ArrayList<>();
        for (String term : terms) {
            List<HelpField> fields = scope == null ? helpSearchService.search(term) : helpSearchService.searchInProduct(term, scope);
            for (String tableName : selectiveTableNames(fields)) {
                vectorStoreService.findByExactTableName(tableName)
                        .filter(doc -> inScope(doc, scope))
                        .map(doc -> doc.mutate().score(KEYWORD_SCORE).build())
                        .filter(doc -> seenIds.add(doc.getId()))
                        .ifPresent(matches::add);
            }
        }
        return matches;
    }

    /**
     * @return les tables distinctes de {@code fields}, ou un ensemble vide si le terme est trop
     * générique (plus de {@link #MAX_TABLES_PER_TERM} tables différentes concernées) pour être un
     * signal exploitable.
     */
    private static Set<String> selectiveTableNames(List<HelpField> fields) {
        Set<String> names = new LinkedHashSet<>();
        for (HelpField field : fields) {
            if (field.getTableName() != null && !field.getTableName().isBlank()) {
                names.add(field.getTableName());
            }
        }
        return names.size() > MAX_TABLES_PER_TERM ? Set.of() : names;
    }

    private static boolean isScoped(String product) {
        return product != null && !product.isBlank();
    }

    /** {@code scope == null} : aucun filtre. Sinon, compare la métadonnée {@code product} du document. */
    private static boolean inScope(Document document, String scope) {
        return scope == null || scope.equalsIgnoreCase(trimmed(document.getMetadata().get("product")));
    }

    private static String trimmed(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double scoreOf(Document document) {
        Double score = document.getScore();
        return score != null ? score : 0.0;
    }

    @SafeVarargs
    private static List<Document> concat(List<Document>... lists) {
        List<Document> all = new ArrayList<>();
        for (List<Document> list : lists) {
            all.addAll(list);
        }
        return all;
    }

    private static String format(List<Document> documents) {
        if (documents.isEmpty()) {
            return "(aucun)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String tableName = String.valueOf(doc.getMetadata().getOrDefault("tableName", doc.getId()));
            sb.append(i + 1).append(") Nom : ").append(tableName)
                    .append(" [").append(doc.getMetadata().getOrDefault("product", "?")).append("] (").append(doc.getId()).append(")\n");
            sb.append("   Score : ").append(formatScore(doc.getScore())).append('\n');
        }
        return sb.toString();
    }

    private static String formatScoresOnly(List<Document> documents) {
        if (documents.isEmpty()) {
            return "(aucun)";
        }
        StringBuilder sb = new StringBuilder();
        for (Document doc : documents) {
            String tableName = String.valueOf(doc.getMetadata().getOrDefault("tableName", doc.getId()));
            sb.append(tableName).append(" : ").append(formatScore(doc.getScore())).append('\n');
        }
        return sb.toString();
    }

    private static String formatScore(Double score) {
        return score != null ? String.format(Locale.ROOT, "%.4f", score) : "n/a";
    }
}
