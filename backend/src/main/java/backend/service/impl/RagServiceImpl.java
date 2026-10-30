package backend.service.impl;

import backend.config.VectorConfig;
import backend.dto.ChatContext;
import backend.dto.QueryAnalysis;
import backend.service.ConceptGlossary;
import backend.service.DocumentBuilderService;
import backend.service.HybridSearchService;
import backend.service.QueryAnalyzer;
import backend.service.RagService;
import backend.service.VectorStoreService;
import backend.perf.Perf;
import backend.perf.Perf.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestre la couche RAG : {@link DocumentBuilderService} (HELP_TEXT+HELP_FIELD -&gt; Document)
 * et {@link VectorStoreService} (embeddings + recherche Top-K) au démarrage, puis {@link QueryAnalyzer}
 * + {@link HybridSearchService} pour récupérer le contexte le plus pertinent à chaque question.
 *
 * <p>La recherche elle-même (mots-clés métier, correspondance exacte de table/colonne, recherche
 * vectorielle, fusion et reranking) est entièrement déléguée à {@link HybridSearchService} — cette
 * classe ne s'occupe plus que de l'orchestration au démarrage et de la mise en forme finale du
 * contexte (troncature des documents trop volumineux).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private static final String BLOCK_SEPARATOR = "---------------------------------";

    private final DocumentBuilderService documentBuilderService;
    private final VectorStoreService vectorStoreService;
    private final QueryAnalyzer queryAnalyzer;
    private final HybridSearchService hybridSearchService;
    private final VectorConfig vectorConfig;
    private final ConceptGlossary conceptGlossary;

    /**
     * Vrai tant que l'index n'est pas chargé, quand le RAG est actif ({@code rag.enabled}, voir VectorIndexRunner).
     * Bug réel du 2026-10-06 : Tomcat accepte les requêtes AVANT les CommandLineRunner ; le chargement de l'index
     * (~48 s sur ce poste, et à chaque rechargement DevTools) laissait une fenêtre où « À quoi sert AC.EXPECTED.RECS ? »
     * recevait la réponse « n'a pas permis de confirmer » faute de document — fausse, et sans rapport avec la question.
     * Injecté par champ (hors constructeur) : un RagServiceImpl construit à la main dans un test n'est jamais « en chargement ».
     */
    @org.springframework.beans.factory.annotation.Value("${rag.enabled:true}")
    private volatile boolean indexLoading;

    @Override
    public boolean isIndexLoading() {
        return indexLoading;
    }

    /**
     * Charge l'index persisté puis, si {@code rag.vector-store.sync-on-startup} (par défaut), le
     * réconcilie avec l'état courant de HELP_TEXT/HELP_FIELD : un fichier XML ajouté ou modifié (importé
     * en base par {@code HelpTextImportRunner}/{@code HelpFieldImportRunner}, qui s'exécutent avant ce
     * code, voir {@code VectorIndexRunner}) est ainsi embeddé et indexé au démarrage suivant, sans
     * reconstruire les ~6 500 autres documents. Avant ce correctif, un index déjà présent sur disque
     * était chargé tel quel et ne voyait jamais les nouveaux documents.
     */
    @Override
    public void buildIndexIfNeeded() {
        try {
            loadOrBuildIndex();
            // Le glossaire français -> anglais n'est fiable qu'une fois confronté à l'index réellement chargé :
            // tout terme anglais absent du référentiel en est retiré (voir ConceptGlossary.calibrate).
            if (vectorStoreService.size() > 0) {
                conceptGlossary.calibrate(vectorStoreService::documentFrequency, vectorStoreService.size());
            }
        } finally {
            // Même en cas d'échec : l'application ne doit pas rester bloquée sur « chargement en cours ».
            indexLoading = false;
        }
    }

    private void loadOrBuildIndex() {
        if (vectorStoreService.isIndexPersisted()) {
            long start = System.currentTimeMillis();
            vectorStoreService.load();
            log.info("Index chargé depuis {} en {} ms.", vectorConfig.getPath(), System.currentTimeMillis() - start);
            // L'index persisté est exploitable dès maintenant : la réconciliation avec la base (plusieurs minutes sur ce
            // poste, mesuré le 2026-10-06) ne doit pas bloquer les questions ; elle remplace l'index d'un bloc à la fin.
            indexLoading = false;
            if (vectorConfig.isSyncOnStartup()) {
                synchronizeWithDatabase();
            }
            return;
        }

        log.info("Construction de l'index...");
        List<Document> documents = documentBuilderService.buildDocuments();
        log.info("{} document(s) lu(s) depuis HELP_TEXT/HELP_FIELD.", documents.size());

        if (documents.isEmpty()) {
            log.warn("Aucun document HELP_TEXT/HELP_FIELD en base : index vectoriel non construit. "
                    + "Il sera retenté au prochain démarrage, une fois l'import Oracle terminé.");
            return;
        }

        long embedStart = System.currentTimeMillis();
        vectorStoreService.add(documents);
        log.info("{} embedding(s) généré(s) en {} ms.", documents.size(), System.currentTimeMillis() - embedStart);

        vectorStoreService.save();
        log.info("Index sauvegardé sur {}.", vectorConfig.getPath());
    }

    private void synchronizeWithDatabase() {
        long start = System.currentTimeMillis();
        List<Document> documents = documentBuilderService.buildDocuments();
        if (documents.isEmpty()) {
            log.warn("Synchronisation de l'index ignorée : aucun document HELP_TEXT/HELP_FIELD en base "
                    + "(l'index chargé est conservé tel quel).");
            return;
        }
        VectorStoreService.SyncResult result = vectorStoreService.synchronize(documents);
        log.info("Synchronisation index/base : {} ajouté(s), {} modifié(s), {} supprimé(s), {} inchangé(s) — {} ms.",
                result.added(), result.updated(), result.removed(), result.unchanged(), System.currentTimeMillis() - start);
        if (result.hasChanges()) {
            vectorStoreService.save();
            log.info("Index synchronisé sauvegardé sur {}.", vectorConfig.getPath());
        }
    }

    @Override
    public Optional<String> buildContext(String question, ChatContext context) {
        // Le texte de la question peut contenir des données saisies par l'utilisateur : jamais en INFO.
        log.info("Construction du contexte — module : {} | table : {} | champ : {} | question de {} caractère(s).",
                context.hasModule() ? context.module() : "-", context.hasTable() ? context.table() : "-",
                context.hasField() ? context.field() : "-", question.length());
        log.debug("Question :\n{}", question);

        if (context.hasTable()) {
            Optional<String> scoped = buildScopedContext(question, context);
            if (scoped.isPresent()) {
                return scoped;
            }
            log.warn("Table '{}' du contexte actif introuvable dans l'index vectoriel (renommée/supprimée "
                    + "depuis la sélection ?) : recherche non restreinte pour cette question.", context.table());
            return buildUnscopedContext(question, context);
        }

        if (context.hasModule()) {
            return buildModuleScopedContext(question, context);
        }

        return buildUnscopedContext(question, context);
    }

    /**
     * Reprend l'analyse de la question précédente ({@link ChatContext#previousUserQuestion()}) quand
     * la question courante ne fournit elle-même aucun mot-clé T24 exploitable — cas typique d'une
     * question de suivi/correction en langage familier ("c'est un champ pas une table") qui ne
     * contient par nature aucun nom de table/colonne, mais porte implicitement sur le même sujet que
     * le tour précédent. Sans ce repli, une telle question ne retrouve jamais rien (recherche hybride
     * vide) et le pipeline renvoie le message de repli générique sans même appeler Llama3.
     */
    private QueryAnalysis analyzeWithFallback(String question, ChatContext context) {
        QueryAnalysis analysis = Perf.time(Category.PREPROCESSING,
                "preprocessing + extraction des identifiants (mots-clés, synonymes, glossaire)", () -> queryAnalyzer.analyze(question));
        if (!analysis.getExtractedKeywords().isEmpty() || !context.hasPreviousUserQuestion()) {
            return analysis;
        }
        log.info("Aucun mot-clé exploitable dans la question courante : reprise du sujet de la question précédente ('{}').",
                context.previousUserQuestion());
        return queryAnalyzer.analyze(context.previousUserQuestion());
    }

    /**
     * Contexte restreint aux seules tables du module choisi via le parcours à bulles de /chatbot
     * (aucune table précise sélectionnée : seul le module l'est, voir ChatContextService côté
     * frontend) : la recherche hybride est restreinte au module DÈS la recherche (paramètre
     * {@code product} de {@link HybridSearchService#search(QueryAnalysis, int, double, String)}, appliqué
     * à chaque canal dont le vectoriel) — jamais un top-K global filtré après coup, où des documents
     * d'autres modules pouvaient occuper toutes les places et évincer les passages pertinents du
     * module (constaté en conditions réelles : EB/ARCHIVE évincé par STK.EXC.* et LIMIT.REVIEW.FQU).
     *
     * <p>Si rien de suffisamment pertinent n'est trouvé dans ce module pour cette question précise,
     * renvoie tout de même un "index" (noms de table du module, sans détail) plutôt que
     * {@link Optional#empty()} : Llama3 est alors invité (voir {@code ChatbotServiceImpl.buildPrompt})
     * à indiquer clairement que la documentation ne répond pas précisément, puis à proposer les tables
     * les plus pertinentes de cette liste — jamais une table d'un autre module.
     *
     * <p>La correspondance exacte de champ (ex. « DELIVERY.REF ») est cherchée directement dans TOUTES
     * les tables du module ({@code moduleDocuments}), AVANT tout appel à {@link HybridSearchService} —
     * pas seulement dans le sous-ensemble {@code withinModule} qu'une recherche hybride globale (non
     * filtrée par module avant son propre top-K) aurait remonté. Un nom de champ très réutilisé dans
     * T24 (DELIVERY.REF existe tel quel dans plus de dix autres modules) peut être noyé hors du top-K
     * global par les occurrences d'autres modules avant même que le filtre de module n'ait sa chance de
     * le garder — constaté en conditions réelles : le champ, pourtant bien documenté dans MD.DEAL et
     * MD.FEE.SETTLEMENT, ne survivait jamais au filtrage et l'assistant répondait "non confirmé".
     *
     * <p>Un mot-clé SANS point (ex. "BIC", par opposition à "DELIVERY.REF") dont le nom coïncide avec le
     * dernier segment du nom d'UNE table (ex. le champ "BIC" dans la table "DE.BIC") est exclu de cette
     * correspondance exacte — voir {@link #isSameAsLastTableSegment} — bug réel constaté : "Comment sont
     * gérés les codes BIC des banques ?" (question conceptuelle, jamais "le champ BIC") verrouillait le
     * Contexte sur la seule description technique du champ BIC (souvent trop étroite), Llama3 produisait
     * alors une réponse plausible mais non vérifiable mot pour mot contre ce Contexte trop restreint, et
     * la réponse entière était rejetée alors que la table DE.BIC documente bien le sujet plus largement.
     * Un identifiant à points reste, lui, toujours pris en compte : sa forme même (T24 ne nomme jamais
     * une table ainsi) signale sans ambiguïté une intention de champ précis, jamais un terme de domaine
     * général coïncidant avec un nom de table.
     */
    private Optional<String> buildModuleScopedContext(String question, ChatContext context) {
        List<Document> moduleDocuments = Perf.time(Category.MODULE_DETECTION, "documents du module sélectionné",
                () -> vectorStoreService.findAllByProduct(context.module()));
        if (moduleDocuments.isEmpty()) {
            log.warn("Module '{}' du contexte actif introuvable dans l'index vectoriel (renommé/supprimé "
                    + "depuis la sélection ?) : recherche non restreinte pour cette question.", context.module());
            return buildUnscopedContext(question, context);
        }

        QueryAnalysis analysis = analyzeWithFallback(question, context);
        log.info("Filtre module : product={} — {} document(s) candidat(s) dans le module.", context.module(), moduleDocuments.size());

        // Table nommée EXACTEMENT dans la question : recherchée dans TOUT l'index, quel que soit le module sélectionné.
        // Bug réel (2026-10-06) : « À quoi sert AC.EXPECTED.RECS ? » dans une conversation AC répondait « n'a pas permis
        // de confirmer » — la table existe, mais elle est documentée sous le produit ER (balise <product> de son XML) :
        // le filtre de module l'écartait avant le contexte. 112 tables des 7 modules affichés sont dans ce cas
        // (préfixe de nom différent du produit documenté : DE.MT942.HIST -> AC, EB.API.ATTRIBUTE -> AA...).
        List<Document> namedTables = Perf.time(Category.EXACT_SEARCH, "recherche exacte de table nommée", () -> tablesNamedIn(analysis));
        if (namedTables.size() == 1) {
            return Optional.of(Perf.time(Category.CONTEXT_BUILDING, "construction du contexte (table nommée)",
                    () -> namedTableContext(namedTables.get(0), analysis, context.module())));
        }
        long exactFieldStart = System.nanoTime();

        for (String keyword : analysis.getExtractedKeywords()) {
            // Préfixe d'un identifiant à points (« DATE » extrait de « DATE.TO.USE », candidat de TABLE seulement, voir
            // TableKeywordExtractor) : jamais un nom de champ. Bug réel (2026-10-06) : « c quoi DATE.TO.USE ? » (EB)
            // répondait sur le champ DATE de CANNEX.EXCEPTION.LOG au lieu de DATE.TO.USE.
            if (!isWholeTokenOf(analysis.getOriginalQuestion(), keyword)) {
                continue;
            }
            // Le mot-clé est le nom exact d'une TABLE du module : la correspondance exacte de table (recherche
            // hybride, score 1.0) doit primer sur un champ homonyme d'une autre table. Bug réel (2026-10-02) :
            // « Quel est le rôle de ACCOUNT ? » répondait sur le champ ACCOUNT d'une table de cartes, pas sur la
            // table ACCOUNT elle-même.
            if (moduleDocuments.stream().anyMatch(d -> keyword.equalsIgnoreCase(String.valueOf(d.getMetadata().get("tableName"))))) {
                log.info("'{}' est le nom exact d'une table du module {} : priorité à la table sur les champs homonymes.",
                        keyword, context.module());
                continue;
            }
            List<Document> matchingDocuments = new ArrayList<>();
            String matchedFieldContent = null;
            for (Document document : moduleDocuments) {
                String tableName = String.valueOf(document.getMetadata().get("tableName"));
                if (!keyword.contains(".") && isSameAsLastTableSegment(tableName, keyword)) {
                    continue;
                }
                Optional<String> fieldOnly = extractExactFieldOnly(document, keyword);
                if (fieldOnly.isPresent()) {
                    matchingDocuments.add(document);
                    matchedFieldContent = fieldOnly.get();
                }
            }

            if (matchingDocuments.size() == 1) {
                log.info("Colonne '{}' détectée dans la question : correspondance exacte dans le module {} ({}.{}), "
                        + "trouvée directement sans recherche hybride.",
                        keyword, context.module(), matchingDocuments.get(0).getMetadata().get("tableName"), keyword);
                return Optional.of(matchedFieldContent);
            }
            if (matchingDocuments.size() > 1) {
                log.info("Colonne '{}' ambiguë : présente dans {} tables du module {}, demande de précision.",
                        keyword, matchingDocuments.size(), context.module());
                return Optional.of(buildAmbiguousFieldContext(keyword, "du module " + context.module(), matchingDocuments));
            }
        }

        Perf.record(Category.EXACT_SEARCH, "recherche exacte de champ dans le module", System.nanoTime() - exactFieldStart);
        Optional<String> fieldElsewhere = Perf.time(Category.EXACT_SEARCH, "recherche exacte de champ hors module",
                () -> fieldNamedInQuestionOutsideModule(analysis, context.module()));
        if (fieldElsewhere.isPresent()) {
            return fieldElsewhere;
        }

        log.info("Aucune correspondance exacte de champ dans le module {} : recherche hybride restreinte au module.", context.module());
        List<Document> results = hybridSearchService.search(analysis, vectorConfig.getTopK(), vectorConfig.getModuleMinScore(),
                context.module());
        // Déjà filtré par HybridSearchService ; conservé comme garde-fou pour ne JAMAIS envoyer à Llama3
        // une table d'un autre module, même si une implémentation future oubliait le filtre.
        // Plusieurs tables nommées exactement : elles passent en tête, même hors du module sélectionné.
        List<Document> withinModule = new ArrayList<>(namedTables.stream()
                .map(doc -> doc.mutate().score(1.0).build()).toList());
        results.stream()
                .filter(doc -> context.module().equalsIgnoreCase(String.valueOf(doc.getMetadata().get("product")).trim()))
                .filter(doc -> namedTables.stream().noneMatch(named -> named.getId().equals(doc.getId())))
                .forEach(withinModule::add);

        if (withinModule.isEmpty()) {
            log.info("Aucun document du module '{}' parmi les résultats pertinents pour cette question : "
                    + "contexte réduit à l'index des tables du module.", context.module());
            return Optional.of(buildModuleIndex(context.module(), moduleDocuments));
        }

        List<Document> retained = withinModule.size() > vectorConfig.getContextTopK()
                ? withinModule.subList(0, vectorConfig.getContextTopK())
                : withinModule;
        String formatted = Perf.time(Category.CONTEXT_BUILDING, "construction du contexte (module)",
                () -> formatContext(retained, analysis.getExtractedKeywords()));
        log.info("Contexte restreint au module {} ({} document(s) retenu(s)) :\n{}", context.module(), retained.size(),
                describePassages(retained, formatted));
        log.debug("Contexte construit :\n{}", formatted);
        return Optional.of(formatted);
    }

    /** Le mot-clé figure dans la question comme jeton entier (ni préfixe ni segment d'un identifiant à points). */
    private static Pattern wholeToken(String keyword) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9.])" + Pattern.quote(keyword) + "(?![A-Za-z0-9.])");
    }

    private static boolean isWholeTokenOf(String question, String keyword) {
        return question != null && wholeToken(keyword).matcher(question).find();
    }

    /** Au-delà, un nom de champ est trop répandu pour désigner un champ précis hors du module (ex. CURRENCY, STATUS). */
    private static final int MAX_TABLES_FOR_FIELD_OUTSIDE_MODULE = 5;
    /** Identifiant T24 écrit tel quel : majuscules/chiffres, avec au moins un point (CP.ACCT) ou au moins 4 caractères. */
    private static final Pattern IDENTIFIER_LIKE = Pattern.compile("[A-Z][A-Z0-9]*(?:[._][A-Z0-9]+)+|[A-Z][A-Z0-9]{3,}");

    /**
     * Champ cité TEL QUEL dans la question mais absent du module sélectionné : cherché dans tout l'index.
     * Bug réel (2026-10-06) : « c quoi CP.ACCT ? » dans une conversation AC répondait « n'a pas permis de confirmer » —
     * le champ est documenté dans AC.SHARED.ACCOUNT, dont le produit est PO : le filtre de module l'écartait (même
     * cause que les tables nommées exactement, voir {@link #tablesNamedIn}). Garde-fous : jeton entier de la question,
     * forme d'identifiant T24, et au plus {@value #MAX_TABLES_FOR_FIELD_OUTSIDE_MODULE} tables (sinon nom trop générique,
     * la recherche du module s'applique comme avant).
     */
    private Optional<String> fieldNamedInQuestionOutsideModule(QueryAnalysis analysis, String selectedModule) {
        return exactFieldAcrossIndex(analysis, "de la documentation (hors module " + selectedModule + ")");
    }

    /**
     * Champ nommé TEL QUEL dans la question, cherché dans tout l'index (sans filtre de module) : une seule table → bloc
     * de ce champ ; plusieurs → contexte d'ambiguïté listant les tables réelles (la réponse demande alors laquelle, voir
     * ChatbotServiceImpl — jamais une table choisie arbitrairement). Un nom de TABLE existant n'est jamais traité comme
     * un champ homonyme (règle table > champ du 2026-10-02). Sans point, au plus
     * {@value #MAX_TABLES_FOR_FIELD_OUTSIDE_MODULE} tables (au-delà, mot trop générique : la recherche habituelle s'applique).
     */
    private Optional<String> exactFieldAcrossIndex(QueryAnalysis analysis, String scope) {
        String question = analysis.getOriginalQuestion();
        for (String keyword : analysis.getExtractedKeywords()) {
            String upper = keyword.toUpperCase(Locale.ROOT);
            java.util.regex.Matcher token = wholeToken(keyword).matcher(question == null ? "" : question);
            // Sans point, le mot doit être écrit en MAJUSCULES par l'utilisateur (« DATE », pas « quelle date ») :
            // un mot français courant ne doit jamais être pris pour un nom de champ.
            if (!token.find() || !IDENTIFIER_LIKE.matcher(upper).matches()
                    || (!upper.contains(".") && !token.group().equals(upper))
                    || vectorStoreService.findByExactTableName(upper).isPresent()) {
                continue;
            }
            List<Document> tables = vectorStoreService.findTablesWithField(upper).stream()
                    .filter(doc -> upper.contains(".") || !isSameAsLastTableSegment(String.valueOf(doc.getMetadata().get("tableName")), upper))
                    .toList();
            if (tables.isEmpty() || (!upper.contains(".") && tables.size() > MAX_TABLES_FOR_FIELD_OUTSIDE_MODULE)) {
                continue;
            }
            if (tables.size() == 1) {
                Document table = tables.get(0);
                Optional<String> field = extractExactFieldOnly(table, upper);
                if (field.isPresent()) {
                    log.info("Champ '{}' : correspondance exacte unique dans {} (module {}), {}.", upper,
                            table.getMetadata().get("tableName"), table.getMetadata().get("product"), scope);
                    return field;
                }
                continue;
            }
            log.info("Champ '{}' présent dans {} tables {} : ambiguïté détectée avant génération, demande de précision.",
                    upper, tables.size(), scope);
            return Optional.of(buildAmbiguousFieldContext(upper, scope, tables));
        }
        return Optional.empty();
    }

    /** Bloc du champ de la table nommée que la question cite aussi (jeton entier), s'il en est un. */
    private static Optional<String> fieldOfNamedTable(Document table, QueryAnalysis analysis) {
        String tableName = String.valueOf(table.getMetadata().get("tableName"));
        for (String keyword : analysis.getExtractedKeywords()) {
            if (keyword.equalsIgnoreCase(tableName) || !isWholeTokenOf(analysis.getOriginalQuestion(), keyword)) {
                continue;
            }
            Optional<String> fieldOnly = extractExactFieldOnly(table, keyword);
            if (fieldOnly.isPresent()) {
                log.info("Champ '{}' de la table nommée '{}' : contexte restreint à ce champ.", keyword, tableName);
                return fieldOnly;
            }
        }
        return Optional.empty();
    }

    /**
     * Tables de l'index dont le nom figure TEL QUEL dans la question (jeton entier : "AC" extrait comme préfixe de
     * "AC.EXPECTED.RECS" ne compte pas), tous modules confondus — jamais un synonyme ni une expansion.
     */
    private List<Document> tablesNamedIn(QueryAnalysis analysis) {
        String question = analysis.getOriginalQuestion();
        List<Document> named = new ArrayList<>();
        for (String keyword : analysis.getExtractedKeywords()) {
            if (!isWholeTokenOf(question, keyword)) {
                continue;
            }
            vectorStoreService.findByExactTableName(keyword)
                    .filter(doc -> named.stream().noneMatch(n -> n.getId().equals(doc.getId())))
                    .ifPresent(named::add);
        }
        return named;
    }

    /**
     * Contexte d'une table nommée exactement : son bloc de champ si la question cite aussi un champ de CETTE table,
     * sinon le document de la table (description générale en tête). Le module réel de la table est journalisé
     * lorsqu'il diffère du module sélectionné.
     */
    private String namedTableContext(Document table, QueryAnalysis analysis, String selectedModule) {
        String tableName = String.valueOf(table.getMetadata().get("tableName"));
        String product = String.valueOf(table.getMetadata().get("product"));
        if (!product.equalsIgnoreCase(selectedModule)) {
            log.info("Table '{}' nommée exactement dans la question : documentée dans le module {} (module sélectionné : {}) "
                    + "— utilisée quand même, la correspondance exacte prime sur le filtre de module.", tableName, product, selectedModule);
        } else {
            log.info("Table '{}' nommée exactement dans la question : contexte restreint à cette table.", tableName);
        }
        Optional<String> fieldOnly = fieldOfNamedTable(table, analysis);
        if (fieldOnly.isPresent()) {
            return fieldOnly.get();
        }
        String formatted = truncateForContext(table, analysis.getExtractedKeywords());
        log.info("Contexte transmis pour la table '{}' ({} caractère(s), description générale incluse).", tableName, formatted.length());
        return formatted;
    }

    /**
     * Contexte signalant qu'un même nom de champ existe dans plusieurs tables du module actif (ex.
     * MATURITY.DATE dans plusieurs tables LD) : pour que Llama3 (voir {@code ChatbotServiceImpl.buildPrompt})
     * demande à l'utilisateur de préciser la table plutôt que de deviner/mélanger les réponses.
     *
     * <p>Inclut le bloc réel de chaque table (via {@link #extractFieldOnly}), pas seulement les noms
     * de table : sans texte réel à citer, Llama3 n'avait rien de légitime dans le Contexte pour
     * satisfaire l'exigence d'un Extrait mot pour mot — constaté en conditions réelles, il citait alors
     * un extrait plausible mais absent du Contexte (probablement puisé dans l'historique de la
     * conversation), systématiquement rejeté par {@code ChatbotServiceImpl.rejectIfExtraitIsFabricated}
     * et remplacé par le repli générique, alors même que l'information demandée était bien documentée.
     */
    private static final int MAX_AMBIGUOUS_EXCERPTS = 8;
    /** Ligne lue par ChatbotServiceImpl : description strictement identique dans TOUTES les tables (jamais une seule). */
    static final String COMMON_DESCRIPTION_LABEL = "Description commune à toutes ces tables : ";
    private static final Pattern HELP_TEXT_UNAVAILABLE = Pattern.compile("(?i)^help text for this field is unavailable");

    /** Description du champ si elle est documentée et strictement la même dans chaque table, sinon vide. */
    private static Optional<String> commonDescription(String fieldName, List<Document> tables) {
        java.util.Set<String> descriptions = new java.util.HashSet<>();
        for (Document table : tables) {
            String description = DocumentOverviewBuilder.fieldsOf(table.getText()).stream()
                    .filter(f -> f.name().equalsIgnoreCase(fieldName))
                    .map(DocumentOverviewBuilder.FieldEntry::description)
                    .findFirst().orElse("");
            if (description.isBlank() || HELP_TEXT_UNAVAILABLE.matcher(description).find()) {
                return Optional.empty();
            }
            descriptions.add(description);
        }
        return descriptions.size() == 1 ? Optional.of(descriptions.iterator().next()) : Optional.empty();
    }

    /** @param scope portée nommée dans le message, ex. « du module MD » ou « de la documentation (hors module AC) ». */
    private static String buildAmbiguousFieldContext(String fieldName, String scope, List<Document> matchingDocuments) {
        String tableNames = matchingDocuments.stream()
                .map(document -> String.valueOf(document.getMetadata().get("tableName")))
                .sorted()
                .collect(Collectors.joining(", "));
        String perTableExcerpts = matchingDocuments.stream()
                .sorted(Comparator.comparing(document -> String.valueOf(document.getMetadata().get("tableName"))))
                .limit(MAX_AMBIGUOUS_EXCERPTS)
                .map(document -> extractExactFieldOnly(document, fieldName).orElse(""))
                .collect(Collectors.joining("\n\n" + BLOCK_SEPARATOR + "\n\n"));
        return "Le champ " + fieldName + " existe dans plusieurs tables " + scope + " : " + tableNames + ".\n\n"
                + commonDescription(fieldName, matchingDocuments).map(d -> COMMON_DESCRIPTION_LABEL + d + "\n\n").orElse("")
                + "Ambigu : ne réponds pas encore sur le contenu précis de ce champ pour une seule de ces "
                + "tables, demande d'abord à l'utilisateur de préciser de quelle table il s'agit parmi celles "
                + "listées ci-dessus. Les extraits ci-dessous (un par table) peuvent t'aider à l'orienter, "
                + "mais ne mélange jamais leur contenu et ne réponds pas comme si une seule table était "
                + "concernée.\n\n" + perTableExcerpts;
    }

    private static final String MODULE_INDEX_NO_OVERVIEW = "Non renseignée dans HELP_TEXT.";
    private static final int MODULE_INDEX_OVERVIEW_MAX_CHARS = 180;

    /**
     * Liste des tables d'un module, chacune avec un court extrait de sa "Description générale"
     * (voir {@link DocumentBuilderServiceImpl#toDocument}) — contexte de repli quand aucun document
     * du module ne répond précisément à la question (voir {@link #buildModuleScopedContext}).
     *
     * <p>Sert à une question générale sur le module lui-même (ex. "À quoi sert le module MD ?") :
     * aucune description de module n'existe dans les données (seules les tables sont documentées,
     * voir {@code ModuleT24}, qui n'a pas de champ description), donc {@link #SYSTEM_PROMPT}
     * demande à Llama3 de synthétiser une vue d'ensemble à partir de ces VRAIS extraits par table —
     * jamais une "description du module" qui n'existerait dans aucune source. Avant ce correctif,
     * seuls les noms de table étaient renvoyés (sans aucun détail), ce qui ne laissait à Llama3
     * aucune matière réelle pour répondre autrement que par le repli générique "non précisé".
     */
    private static String buildModuleIndex(String module, List<Document> moduleDocuments) {
        String tableOverviews = moduleDocuments.stream()
                .sorted(Comparator.comparing(document -> String.valueOf(document.getMetadata().get("tableName"))))
                .map(document -> "- " + document.getMetadata().get("tableName") + " : " + shortOverview(document))
                .collect(Collectors.joining("\n"));
        return "La recherche automatique n'a retrouvé aucune table du module " + module + " qui réponde de façon "
                + "exacte à cette question précise. Cette recherche n'est PAS exhaustive : ne conclus jamais que "
                + "l'information, le champ ou la table n'existe pas ; dis que les documents retrouvés ne permettent "
                + "pas de la confirmer.\n\n"
                + "Tables du module " + module + ", avec un court extrait de leur description "
                + "générale (source réelle — si la question porte sur le module dans son ensemble, "
                + "voir la règle \"MODULES T24\" pour en faire une synthèse) :\n" + tableOverviews;
    }

    private static String shortOverview(Document document) {
        String text = document.getText();
        int start = text.indexOf("Description générale :\n");
        if (start < 0) {
            return MODULE_INDEX_NO_OVERVIEW;
        }
        start += "Description générale :\n".length();
        int end = text.indexOf("\n\n", start);
        String overview = (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
        if (overview.isEmpty() || overview.equals(MODULE_INDEX_NO_OVERVIEW)) {
            return MODULE_INDEX_NO_OVERVIEW;
        }
        return overview.length() > MODULE_INDEX_OVERVIEW_MAX_CHARS
                ? overview.substring(0, MODULE_INDEX_OVERVIEW_MAX_CHARS).trim() + "…"
                : overview;
    }

    /**
     * Contexte restreint à la table choisie via le parcours à bulles de /chatbot (module + table) :
     * contourne entièrement {@link HybridSearchService} pour garantir qu'aucune autre table de
     * l'index ne pollue le contexte envoyé à Llama3, conformément à la demande de limiter la
     * recherche aux HELP_TEXT/HELP_FIELD correspondants.
     *
     * <p>La sélection d'une colonne n'est plus une étape à bulles séparée (voir ChatContextService
     * côté frontend) : si la question mentionne le nom d'une colonne de cette table (ex. "à quoi sert
     * DESCRIPTION ?"), ce nom est détecté via {@link QueryAnalyzer} et le contexte isolé au seul bloc
     * HELP_FIELD correspondant, sans action supplémentaire de l'utilisateur. {@link ChatContext#field()}
     * reste pris en charge en priorité s'il est renseigné (ex. via un futur appel API direct), mais le
     * parcours à bulles actuel ne le fixe plus jamais.
     */
    private Optional<String> buildScopedContext(String question, ChatContext context) {
        Optional<Document> tableDocument = vectorStoreService.findByExactTableName(context.table());
        if (tableDocument.isEmpty()) {
            return Optional.empty();
        }
        Document document = tableDocument.get();
        QueryAnalysis analysis = analyzeWithFallback(question, context);

        if (context.hasField()) {
            Optional<String> fieldOnly = extractFieldOnly(document, context.field());
            if (fieldOnly.isPresent()) {
                log.info("Contexte restreint au champ {}.{} (sélection explicite).", context.table(), context.field());
                return fieldOnly;
            }
            log.warn("Champ '{}' introuvable dans la table '{}' : contexte restreint à la table entière.",
                    context.field(), context.table());
        } else {
            for (String keyword : analysis.getExtractedKeywords()) {
                Optional<String> fieldOnly = extractFieldOnly(document, keyword);
                if (fieldOnly.isPresent()) {
                    log.info("Colonne '{}' détectée dans la question : contexte restreint à {}.{}.",
                            keyword, context.table(), keyword);
                    return fieldOnly;
                }
            }
        }

        log.info("Contexte restreint à la table {}.", context.table());
        return Optional.of(truncateForContext(document, analysis.getExtractedKeywords()));
    }

    private Optional<String> buildUnscopedContext(String question, ChatContext context) {
        long analyzeStart = System.currentTimeMillis();
        QueryAnalysis analysis = analyzeWithFallback(question, context);
        long analyzeMs = System.currentTimeMillis() - analyzeStart;

        // Champ nommé exactement (avec, le cas échéant, la table qui le précise) : décidé AVANT la recherche hybride.
        // Sans cela, un champ présent dans plusieurs tables arrivait à Llama3 sous forme de plusieurs documents et il en
        // choisissait un arbitrairement (constaté le 2026-10-06 sur DATE.TO.USE / DELIVERY.REF).
        List<Document> namedTables = Perf.time(Category.EXACT_SEARCH, "recherche exacte de table nommée", () -> tablesNamedIn(analysis));
        if (namedTables.size() == 1) {
            Optional<String> fieldOfTable = fieldOfNamedTable(namedTables.get(0), analysis);
            if (fieldOfTable.isPresent()) {
                return fieldOfTable;
            }
        }
        Optional<String> exactField = Perf.time(Category.EXACT_SEARCH, "recherche exacte de champ (tout l'index)",
                () -> exactFieldAcrossIndex(analysis, "de la documentation"));
        if (exactField.isPresent()) {
            return exactField;
        }

        long searchStart = System.currentTimeMillis();
        List<Document> results = hybridSearchService.search(analysis, vectorConfig.getTopK(), vectorConfig.getMinScore());
        long searchMs = System.currentTimeMillis() - searchStart;

        if (results.isEmpty()) {
            log.info("Aucun document au-dessus du seuil de pertinence ({}) : pas de contexte exploitable.",
                    vectorConfig.getMinScore());
            log.info("Temps par étape : analyse question {} ms | recherche hybride {} ms.", analyzeMs, searchMs);
            return Optional.empty();
        }

        long contextStart = System.currentTimeMillis();
        // Distinct du top-k de recherche : seuls les meilleurs documents (déjà triés par score par
        // HybridSearchService) sont réellement envoyés à Llama3, pour réduire la taille du prompt —
        // principal levier de temps de réponse identifié en conditions réelles (voir VectorConfig).
        List<Document> retained = results.size() > vectorConfig.getContextTopK()
                ? results.subList(0, vectorConfig.getContextTopK())
                : results;
        String formatted = Perf.time(Category.CONTEXT_BUILDING, "construction du contexte",
                () -> formatContext(retained, analysis.getExtractedKeywords()));
        long contextMs = System.currentTimeMillis() - contextStart;

        log.info("Temps par étape : analyse question {} ms | recherche hybride (embedding+FAISS+mots-clés) {} ms | "
                + "construction contexte {} ms.", analyzeMs, searchMs, contextMs);
        log.info("Passages sélectionnés pour le contexte (recherche non restreinte) :\n{}", describePassages(retained, formatted));
        log.debug("Contexte construit :\n{}", formatted);
        return Optional.of(formatted);
    }

    /** Résumé non sensible des passages retenus (table, module, score, identifiant) pour les logs. */
    private static String describePassages(List<Document> retained, String formatted) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < retained.size(); i++) {
            Document doc = retained.get(i);
            sb.append("  #").append(i + 1).append(' ').append(doc.getMetadata().get("tableName"))
                    .append(" [").append(doc.getMetadata().get("product")).append("] score=")
                    .append(doc.getScore() != null ? String.format(Locale.ROOT, "%.4f", doc.getScore()) : "n/a")
                    .append(" id=").append(doc.getId()).append('\n');
        }
        sb.append("  Taille totale du contexte : ").append(formatted.length()).append(" caractère(s).");
        return sb.toString();
    }

    /**
     * Certaines tables T24 comptent plusieurs centaines de champs (ex. CUSTOMER : 490 ; ACCOUNT :
     * 375) : leur document complet peut dépasser 50 000 caractères. Envoyé tel quel à Llama3, un tel
     * contexte pousse le prompt au-delà de la fenêtre {@code spring.ai.ollama.chat.options.num-ctx}
     * (8192 tokens) et fait exploser le temps de génération sur une inférence CPU locale — constaté
     * en conditions réelles avec RagPipelineManualIT : la question "Résume CUSTOMER" a dépassé
     * {@code spring.http.client.read-timeout} (600 s) avant que Llama3 ne réponde. Chaque document
     * est donc tronqué à {@link #MAX_DOCUMENT_CHARS_IN_CONTEXT} caractères avant d'être injecté dans
     * le contexte. Le document stocké dans l'index (voir DocumentBuilderService) n'est lui jamais
     * tronqué : ceci n'affecte que ce qui est effectivement envoyé au LLM, pas l'index ni la
     * recherche.
     */
    private static final int MAX_DOCUMENT_CHARS_IN_CONTEXT = 2500;
    /**
     * Pour une table comme CUSTOMER, la seule "Description générale" dépasse à elle seule
     * {@link #MAX_DOCUMENT_CHARS_IN_CONTEXT} caractères : sans plafond dédié, elle consommait tout
     * le budget de troncature et l'en-tête "Champs (490) :" — donc tout champ, y compris celui remonté
     * en tête par {@link #prioritizeTargetField} — n'était jamais atteint. Constaté en conditions
     * réelles : Llama3 répondait "non trouvé"/"non spécifié" pour un champ pourtant bien présent dans
     * le document source, et même les questions générales sur la table ("Résume CUSTOMER") ne
     * voyaient jamais aucun champ. Ce plafond réserve donc toujours de la place pour la section
     * Champs, quel que soit le poids de la description générale.
     */
    // 1500 (au lieu de 1200, 2026-10-06) : une description générale d'environ 1200 caractères (AC.EXPECTED.RECS : 1188)
    // doit tenir en entière avec l'en-tête Table/Produit ; il reste 1000 caractères pour les champs.
    private static final int MAX_HEAD_CHARS_IN_CONTEXT = 1500;
    private static final String FIELDS_HEADER_PREFIX = "Champs (";
    private static final String FIELD_SEPARATOR = "----------------------------------";
    private static final String FIELD_BLOCK_SEPARATOR = "\n\n" + FIELD_SEPARATOR + "\n\n";
    /** Voir {@link #annotateIfInputPromptOnly} : détecte une invite de saisie d'écran plutôt qu'une définition. */
    private static final Pattern INPUT_PROMPT_ONLY_PATTERN =
            Pattern.compile("(?i)^\\s*(please\\s+specify|specify|enter)\\b.*");

    private static String formatContext(List<Document> documents, List<String> targetFieldNames) {
        return documents.stream()
                .map(document -> truncateForContext(document, targetFieldNames))
                .collect(Collectors.joining("\n\n" + BLOCK_SEPARATOR + "\n\n"));
    }

    private static final Pattern REDUNDANT_SUMMARY = Pattern.compile("Résumé fonctionnel :\\n[^\\n]*\\n\\n");

    /**
     * Retire du CONTEXTE (jamais de l'index) la section « Résumé fonctionnel » quand la table a une vraie description
     * générale : ce n'est alors que la première phrase de cette description, recopiée (voir
     * DocumentBuilderServiceImpl.functionalSummary). Constaté le 2026-10-06 sur AC.EXPECTED.RECS : ce doublon faisait
     * dépasser {@link #MAX_HEAD_CHARS_IN_CONTEXT} et coupait la FIN de la description générale avant le LLM (stockage
     * dans ACCOUNT, requêtes sur les enregistrements attendus/reçus) — et alourdissait chaque prompt pour rien.
     */
    private static String withoutRedundantSummary(String text) {
        boolean realOverview = text.contains("Description générale :\n") && !text.contains("Description générale :\n" + MODULE_INDEX_NO_OVERVIEW);
        return realOverview ? REDUNDANT_SUMMARY.matcher(text).replaceFirst("") : text;
    }

    private static String truncateForContext(Document document, List<String> targetFieldNames) {
        String text = withoutRedundantSummary(document.getText());

        // annotateIfInputPromptOnly (via orderedFieldBlocks) doit s'appliquer à TOUS les documents,
        // pas seulement ceux assez volumineux pour être tronqués : un document court (peu de champs,
        // ex. MD.PARAMETER) sautait entièrement ce traitement via ce retour anticipé — constaté en
        // conditions réelles, c'est la cause exacte pour laquelle DEAL.SUB.TYP n'était jamais annoté/
        // remplacé malgré le code déjà en place plus bas dans cette méthode.
        int headerIdx = text.indexOf(FIELDS_HEADER_PREFIX);
        if (headerIdx >= 0 && !targetFieldNames.isEmpty()) {
            int fieldsStartIdx = text.indexOf("\n\n", headerIdx);
            if (fieldsStartIdx >= 0) {
                fieldsStartIdx += 2;
                List<String> processedBlocks = orderedFieldBlocks(text.substring(fieldsStartIdx), targetFieldNames);
                text = text.substring(0, fieldsStartIdx) + String.join(FIELD_BLOCK_SEPARATOR, processedBlocks);
            }
        }

        if (text.length() <= MAX_DOCUMENT_CHARS_IN_CONTEXT) {
            return text;
        }
        Object fieldCount = document.getMetadata().get("fieldCount");
        String notice = fieldCount != null
                ? "\n\n(... document tronqué, trop volumineux pour être inclus intégralement ; "
                        + "cette table compte " + fieldCount + " champ(s) au total.)"
                : "\n\n(... document tronqué, trop volumineux pour être inclus intégralement.)";

        headerIdx = text.indexOf(FIELDS_HEADER_PREFIX);
        if (headerIdx < 0) {
            // Pas de section Champs identifiable (ne devrait pas arriver pour un document HELP_TEXT) :
            // repli sur l'ancien comportement, simple coupure brute.
            return text.substring(0, MAX_DOCUMENT_CHARS_IN_CONTEXT) + notice;
        }
        int fieldsStart = text.indexOf("\n\n", headerIdx);
        if (fieldsStart < 0) {
            return text.substring(0, MAX_DOCUMENT_CHARS_IN_CONTEXT) + notice;
        }
        fieldsStart += 2;

        String head = text.substring(0, fieldsStart);
        String truncatedHead = head.length() > MAX_HEAD_CHARS_IN_CONTEXT
                ? head.substring(0, MAX_HEAD_CHARS_IN_CONTEXT) + "…\n\n"
                : head;

        // Déjà réordonnés/annotés ci-dessus : un simple découpage suffit ici, pas un second passage
        // par orderedFieldBlocks (qui redéplacerait inutilement le champ ciblé, déjà en tête).
        List<String> blocks = List.of(text.substring(fieldsStart).split(Pattern.quote(FIELD_BLOCK_SEPARATOR)));
        int remaining = MAX_DOCUMENT_CHARS_IN_CONTEXT - truncatedHead.length() - notice.length();
        StringBuilder fieldsOut = new StringBuilder();
        for (String block : blocks) {
            String candidate = (fieldsOut.length() == 0 ? "" : FIELD_BLOCK_SEPARATOR) + block;
            if (candidate.length() > remaining) {
                break;
            }
            fieldsOut.append(candidate);
            remaining -= candidate.length();
        }

        return truncatedHead + fieldsOut + notice;
    }

    /**
     * Isole, dans le document d'une table, uniquement l'en-tête (table/produit/description générale)
     * et le bloc HELP_FIELD du champ {@code fieldName} — utilisé quand le contexte actif restreint
     * explicitement la question à une colonne précise (parcours à bulles de /chatbot), plutôt que de
     * renvoyer tous les champs de la table. Réutilise le même découpage par blocs (séparés par
     * {@link #FIELD_BLOCK_SEPARATOR}) que {@link #prioritizeTargetField}.
     *
     * @return {@link Optional#empty()} si {@code fieldName} ne correspond à aucun bloc du document
     * (nom de champ obsolète depuis la sélection) : l'appelant retombe alors sur la table entière.
     */
    private static Optional<String> extractFieldOnly(Document document, String fieldName) {
        return extractField(document, fieldName, name -> matchesTarget(name, List.of(fieldName)));
    }

    /**
     * Variante stricte de {@link #extractFieldOnly}, utilisée pour la correspondance exacte dans TOUT un
     * module ({@link #buildModuleScopedContext}) : le champ doit porter exactement le nom demandé, ou
     * {@code keyword} doit être "TABLE.CHAMP" pour CE document (ex. "CUSTOMER.CIN" dans CUSTOMER).
     *
     * <p>{@link #matchesTarget} accepte aussi tout champ dont le nom termine l'identifiant (utile dans une
     * table déjà choisie) ; appliqué à tout un module, cela transformait un identifiant précis en fausse
     * ambiguïté — constaté sur l'index réel : "INT.ACC.TYPE" (un seul champ, dans AC.AUTO.ACCOUNT)
     * correspondait aussi aux champs "TYPE" de 5 autres tables AC, idem DEC.CR.AMOUNT/"AMOUNT" (LC) et
     * CCY.DENOMINATION/"DENOMINATION" (TT) : demande de précision injustifiée et contexte de 22 000
     * caractères au lieu du seul bloc du champ.
     */
    private static Optional<String> extractExactFieldOnly(Document document, String keyword) {
        String tableName = String.valueOf(document.getMetadata().get("tableName"));
        return extractField(document, keyword,
                name -> name.equalsIgnoreCase(keyword) || keyword.equalsIgnoreCase(tableName + "." + name));
    }

    private static Optional<String> extractField(Document document, String fieldName, Predicate<String> nameMatches) {
        String text = document.getText();
        int headerIdx = text.indexOf(FIELDS_HEADER_PREFIX);
        if (headerIdx < 0) {
            return Optional.empty();
        }
        int fieldsStart = text.indexOf("\n\n", headerIdx);
        if (fieldsStart < 0) {
            return Optional.empty();
        }
        fieldsStart += 2;

        String head = withoutRedundantSummary(text.substring(0, headerIdx));
        String fieldsSection = text.substring(fieldsStart);
        String[] blocks = fieldsSection.split(Pattern.quote(FIELD_BLOCK_SEPARATOR));

        for (String block : blocks) {
            if (nameMatches.test(firstLine(block))) {
                String stripped = block.strip();
                return Optional.of(head + "Champ sélectionné : " + fieldName + "\n\n" + annotateIfInputPromptOnly(stripped));
            }
        }
        return Optional.empty();
    }

    /**
     * Un champ HELP_FIELD n'a parfois pour toute "Description" qu'une invite de saisie d'écran
     * ("Please specify...", "Enter...") plutôt qu'une véritable définition métier — constaté en
     * conditions réelles sur DEAL.SUB.TYP (table MD.PARAMETER : "Please specify the guarantee
     * type"). Le prompt système ({@code ChatbotServiceImpl}) demande de ne jamais présenter une
     * telle invite comme une définition, mais un modèle de cette taille ne suit pas cette consigne
     * de façon fiable même avec une mise en garde explicite juste à côté du texte (vérifié en
     * conditions réelles : prompt renforcé + avertissement injecté dans le contexte, toujours
     * cité comme "Fonction"). Cette méthode va donc plus loin qu'annoter : elle REMPLACE le texte
     * d'invite par un texte neutre, pour qu'il n'y ait plus rien à citer par erreur — plus fiable
     * qu'une consigne ou qu'un avertissement, quelle que soit la formulation.
     */
    private static String annotateIfInputPromptOnly(String fieldBlock) {
        String description = extractDescriptionLine(fieldBlock);
        if (description != null && INPUT_PROMPT_ONLY_PATTERN.matcher(description).matches()) {
            return fieldBlock.replace(description,
                    "(Aucune description fonctionnelle disponible dans la documentation pour ce champ — "
                            + "seule une invite de saisie d'écran existe, non exploitable comme définition.)");
        }
        return fieldBlock;
    }

    private static String extractDescriptionLine(String fieldBlock) {
        String marker = "Description :\n";
        int idx = fieldBlock.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = fieldBlock.indexOf("\n\n", start);
        return (end < 0 ? fieldBlock.substring(start) : fieldBlock.substring(start, end)).strip();
    }

    /**
     * Découpe la section Champs en blocs et remonte en tête celui du champ ciblé par la question
     * (ex. "CUSTOMER.CIN" -&gt; mot-clé syntaxique "CIN"), pour qu'il soit retenu en priorité par
     * {@link #truncateForContext} tant qu'il reste de la place dans le budget restant — sinon son
     * ordre brut dans le document (rarement en tête pour une table de plusieurs centaines de champs)
     * l'exclurait le premier. Constaté en conditions réelles : "À quoi sert CUSTOMER.CIN ?" répondait
     * "non trouvé" alors que CUSTOMER était bien le document retenu — le champ ciblé n'atteignait
     * simplement jamais le contexte envoyé à Llama3.
     */
    private static List<String> orderedFieldBlocks(String fieldsSection, List<String> targetFieldNames) {
        List<String> blocks = new ArrayList<>(List.of(fieldsSection.split(Pattern.quote(FIELD_BLOCK_SEPARATOR))));
        // Remplace le texte d'invite de saisie (voir annotateIfInputPromptOnly) sur TOUS les champs,
        // pas seulement celui ciblé par la question : un champ voisin non traité (ex. DESCRIPT :
        // "Please specify the guarantee description" juste à côté de DEAL.SUB.TYP) suffisait à Llama3
        // pour fabriquer par analogie un texte plausible mais inventé pour le champ ciblé, MÊME après
        // que son propre texte d'origine ait été correctement retiré du contexte — constaté en
        // conditions réelles (le champ ciblé seul ne suffit pas, il faut traiter toute la table).
        blocks.replaceAll(RagServiceImpl::annotateIfInputPromptOnly);
        if (targetFieldNames.isEmpty()) {
            return blocks;
        }

        int matchIndex = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (matchesTarget(firstLine(blocks.get(i)), targetFieldNames)) {
                matchIndex = i;
                break;
            }
        }
        if (matchIndex >= 0) {
            blocks.add(0, blocks.remove(matchIndex));
        }
        return blocks;
    }

    private static String firstLine(String block) {
        int idx = block.indexOf('\n');
        return (idx >= 0 ? block.substring(0, idx) : block).trim();
    }

    private static boolean matchesTarget(String fieldName, List<String> targetFieldNames) {
        String normalized = fieldName.toUpperCase(Locale.ROOT);
        for (String target : targetFieldNames) {
            String upperTarget = target.toUpperCase(Locale.ROOT);
            if (normalized.equals(upperTarget) || upperTarget.endsWith("." + normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Voir la Javadoc de {@link #buildModuleScopedContext} sur le cas "BIC" dans la table "DE.BIC". */
    private static boolean isSameAsLastTableSegment(String tableName, String keyword) {
        int lastDot = tableName.lastIndexOf('.');
        String lastSegment = lastDot >= 0 ? tableName.substring(lastDot + 1) : tableName;
        return lastSegment.equalsIgnoreCase(keyword);
    }
}
