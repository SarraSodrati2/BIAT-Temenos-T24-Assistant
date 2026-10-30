package backend.service.impl;

import backend.dto.ChatContext;
import backend.service.ConceptGlossary;
import backend.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Recherche déterministe de TABLES par concept : « Quelle table est responsable des balances ? », « Quelles tables
 * gèrent les soldes ? », « Which table stores limits? ». Pendant de la recherche inversée de CHAMP (FieldSearchService).
 *
 * <p>Bug réel (2026-10-07) : posée telle quelle, cette question partait vers Llama3, qui choisissait UNE table au hasard
 * parmi plusieurs (AC : OD.ACCT.ACTIVITY en ignorant AC.BALANCE.TYPE ; DE : DE.WORDS ; sans module : texte sur
 * SL.ACCOUNT.BALANCES avec la source BALANCE.REQUIREMENT), ou répondait « n'a pas permis de confirmer » (constaté sur le
 * backend de l'utilisateur, module AC). Ici, le concept (traduit en anglais par le glossaire : soldes -&gt; BALANCE) est
 * cherché dans les NOMS de tables puis dans leurs descriptions générales, dans le module de la conversation, et les tables
 * correspondantes d'autres modules sont signalées. Tout le contenu affiché est recopié de l'index ; rien n'est écrit en dur
 * pour un concept, une table ou un module.
 */
@Slf4j
@Component
class TableFinder {

    /** La question demande une ou des TABLES (et pas un champ). */
    private static final Pattern ASKS_FOR_TABLE = Pattern.compile(
            "(?iu)\\b(?:quel(?:le)?s?|lesquelles|laquelle|which|où)\\b.{0,40}?\\btables?\\b"
                    + "|\\btables?\\b.{0,30}?\\b(?:responsables?|gère\\w*|gere\\w*|stock\\w*|contien\\w*|enregistr\\w*|conserv\\w*)\\b");
    private static final Pattern MENTIONS_FIELD = Pattern.compile("(?iu)\\b(?:champs?|colonnes?|fields?|columns?)\\b");
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}0-9._-]*");
    private static final Set<String> STOP_WORDS = Set.of(
            "quel", "quelle", "quels", "quelles", "lesquelles", "laquelle", "table", "tables", "est", "sont", "responsable",
            "responsables", "gere", "gère", "gèrent", "gerent", "gérer", "gerer", "gestion", "contient", "contiennent", "stocke",
            "stockent", "stockée", "stockées", "stockés", "enregistre", "enregistrent", "conserve", "conservent", "utilise",
            "utilisée", "utilisées", "pour", "dans", "les", "des", "une", "qui", "que", "quoi", "ces", "cette", "cet", "module",
            "modules", "sur", "avec", "par", "aux", "est-ce", "elle", "elles", "the", "which", "is", "are", "responsible",
            "for", "store", "stores", "hold", "holds", "contain", "contains", "with", "manage", "manages", "where", "où",
            "t24", "temenos", "donne", "donner", "moi", "quelles-sont", "sert", "servent", "utiliser", "toutes", "tous", "liste",
            "lister", "listez", "inventaire", "all", "every", "autres", "autre", "liées", "liés", "lié", "liée", "related", "compare",
            "comparer", "comparez", "entre", "concernant", "relatives", "relatifs", "compris", "inclus", "notamment", "aussi",
            "également", "egalement", "leur", "leurs", "son", "ses");
    /** « toutes les tables », « liste des tables », « all tables » : inventaire demandé explicitement. */
    private static final Pattern WANTS_ALL = Pattern.compile("(?iu)\\b(?:toutes|tous|liste[rz]?|inventaire|all|every)\\b");
    /** Comparaison ou autres modules demandés explicitement. */
    private static final Pattern OTHER_MODULES = Pattern.compile(
            "(?iu)\\b(?:autres?\\s+modules?|tous\\s+les\\s+modules|d'autres\\s+modules|compar\\w*|other\\s+modules?|all\\s+modules)\\b");
    private static final int MAX_EQUIVALENT = 4;
    /** Poids IDF minimal d'un concept discriminant (≈ présent dans moins d'un tiers de l'index). */
    private static final double MIN_DISCRIMINANT_WEIGHT = 1.0;
    /** Score ≥ 90 % du meilleur : tables considérées à égalité (demande de précision). */
    private static final double TIE_RATIO = 0.9;
    /** Score ≥ 50 % du meilleur : autre table citée ; en dessous, simple coïncidence de vocabulaire. */
    private static final double OTHERS_RATIO = 0.5;
    private static final int BEST_OVERVIEW_CHARS = 600;
    /** Sans module : similarité calculée sur les tables les plus proches seulement (les autres ont 0). */
    private static final int SEMANTIC_TOP_K_ALL = 60;
    private static final double SEMANTIC_WEIGHT = 0.35;
    private static final int COGNATE_MIN_PREFIX = 5;
    /** Mot français d'au moins 6 lettres : « comme » (5) n'est pas un apparenté de « commission » (mesuré sur LD). */
    private static final int COGNATE_MIN_STEM = 6;
    private static final double COGNATE_MIN_SHARE = 0.7;
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]{2,}");
    private static final int MAX_OTHERS = 3;
    private static final int MAX_FULL_LIST = 40;
    private static final int OVERVIEW_CHARS = 200;

    private final VectorStoreService vectorStoreService;
    private final ConceptGlossary conceptGlossary;

    TableFinder(VectorStoreService vectorStoreService, ConceptGlossary conceptGlossary) {
        this.vectorStoreService = vectorStoreService;
        this.conceptGlossary = conceptGlossary;
    }

    static boolean asksForTables(String question) {
        return ASKS_FOR_TABLE.matcher(question).find() && !MENTIONS_FIELD.matcher(question).find();
    }

    Optional<String> tryAnswer(String question, ChatContext chatContext) {
        if (!asksForTables(question)) {
            return Optional.empty();
        }
        String module = chatContext.hasModule() ? chatContext.module().toUpperCase(Locale.ROOT) : null;
        Map<String, Set<String>> variantsByTerm = conceptVariants(question, module);
        if (variantsByTerm.isEmpty()) {
            return Optional.empty();
        }
        List<Document> inScope = dedupe(module != null ? vectorStoreService.findAllByProduct(module) : vectorStoreService.findAll());
        boolean wantsAll = WANTS_ALL.matcher(question).find();

        // Poids de chaque concept selon sa rareté dans l'index (IDF) : un mot présent presque partout (« type » : 6 503
        // documents sur 6 540) n'est pas un concept discriminant et ne doit jamais décider du résultat. Bug réel (2026-10-07) :
        // « …engagements de type STBY ? » renvoyait MD.TXN.TYPE.CONDITION et MD.DEAL.TYPE.PARAMETER au lieu de
        // MD.PRESENTATION.HIST, dont la description générale couvre presentations, status, discrepancies et STBY.
        Map<String, Double> weights = weightsOf(variantsByTerm);
        Map<String, Set<String>> discriminant = new LinkedHashMap<>();
        variantsByTerm.forEach((term, termVariants) -> {
            if (weights.get(term) >= MIN_DISCRIMINANT_WEIGHT) {
                discriminant.put(term, termVariants);
            }
        });
        if (discriminant.isEmpty()) {
            discriminant.putAll(variantsByTerm);
        }
        log.info("Recherche de tables : concepts {} (poids IDF {}), discriminants retenus {}.", variantsByTerm.keySet(), weights,
                discriminant.keySet());
        if (discriminant.size() >= 2 && !wantsAll) {
            Optional<String> covered = coverageAnswer(question, module, inScope, discriminant, weights);
            if (covered.isPresent()) {
                return covered;
            }
        }
        variantsByTerm = discriminant;
        Set<String> variants = variantsByTerm.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(LinkedHashSet::new));

        List<Document> nameHits = rank(inScope.stream().filter(d -> nameMatches(tableName(d), variants)).toList(), variants, module);
        List<Document> overviewHits = inScope.stream()
                .filter(d -> !nameHits.contains(d))
                .filter(d -> overviewMentions(overviewOf(d), variants))
                .toList();
        // Autres modules : seulement si la question le demande, ou si rien ne correspond dans le module sélectionné.
        boolean lookElsewhere = module != null
                && (OTHER_MODULES.matcher(question).find() || (nameHits.isEmpty() && overviewHits.isEmpty()));
        List<Document> elsewhere = !lookElsewhere ? List.of() : rank(dedupe(vectorStoreService.findAll()).stream()
                .filter(d -> !module.equalsIgnoreCase(String.valueOf(d.getMetadata().get("product"))))
                .filter(d -> nameMatches(tableName(d), variants))
                .toList(), variants, null);
        if (nameHits.isEmpty() && overviewHits.isEmpty() && elsewhere.isEmpty()) {
            log.info("Recherche de tables par concept {} : aucune table correspondante, pipeline RAG habituel.", variantsByTerm.keySet());
            return Optional.empty();
        }
        log.info("Recherche de tables par concept {} (variantes {}) dans {} : {} par nom, {} par description générale, {} hors module "
                        + "(liste complète demandée : {}). Réponse déterministe, Llama3 n'est pas appelé.", variantsByTerm.keySet(), variants,
                module != null ? "le module " + module : "toute la documentation", nameHits.size(), overviewHits.size(), elsewhere.size(),
                wantsAll);
        String asked = String.join(", ", variantsByTerm.keySet());
        Set<String> found = matchedNameTerms(nameHits.isEmpty() ? elsewhere : nameHits, variants);
        // Le terme affiché est celui RÉELLEMENT présent dans les noms (BALANCE), pas le mot français de la question (soldes).
        String label = found.isEmpty() || found.stream().allMatch(f -> f.equalsIgnoreCase(asked) || (f + "S").equalsIgnoreCase(asked))
                ? asked : String.join(", ", found);
        String note = label.equals(asked) ? "" : " (concept demandé : « " + asked + " »)";
        return Optional.of(wantsAll
                ? fullList(label, note, module, nameHits, overviewHits, elsewhere)
                : focusedAnswer(label, note, module, variants, nameHits, overviewHits, elsewhere));
    }

    /**
     * Poids IDF de chaque concept : ln((N+1)/(df+1)), calculé sur le corpus RÉELLEMENT interrogé (noms de tables et descriptions
     * générales de tout l'index), df = nombre de tables où le concept est reconnu (mot exact, forme anglaise du glossaire ou
     * apparenté). Un concept reconnu nulle part (« permet », « depuis ») pèse 0 : ce n'est pas un concept recherchable.
     */
    private Map<String, Double> weightsOf(Map<String, Set<String>> variantsByTerm) {
        List<TableTokens> corpus = corpus();
        Map<String, Double> weights = new LinkedHashMap<>();
        variantsByTerm.forEach((term, termVariants) -> {
            if (corpus.isEmpty()) {
                weights.put(term, 1.0);
                return;
            }
            long df = corpus.stream().filter(t -> t.matches(term, termVariants)).count();
            weights.put(term, df == 0 ? 0.0 : Math.log((corpus.size() + 1.0) / (df + 1.0)));
        });
        return weights;
    }

    /** Mots du nom et de la description générale d'une table (minuscules, sans accents). */
    private record TableTokens(String table, Set<String> tokens) {

        boolean matches(String term, Set<String> variants) {
            for (String variant : variants) {
                String lower = variant.toLowerCase(Locale.ROOT);
                if (tokens.contains(lower) || tokens.contains(lower + "s")) {
                    return true;
                }
            }
            String stem = withoutAccents(term).toLowerCase(Locale.ROOT);
            if (stem.length() >= COGNATE_MIN_STEM) {
                for (String token : tokens) {
                    if (cognates(stem, token)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Mots apparentés français/anglais sans dictionnaire : long préfixe commun (narratifs/narrative, construire/construction,
     * présentateur/presentor, statiques/static). Au moins {@value #COGNATE_MIN_PREFIX} lettres et 70 % du mot le plus court.
     */
    static boolean cognates(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int common = 0;
        while (common < max && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common >= COGNATE_MIN_PREFIX && common >= COGNATE_MIN_SHARE * max;
    }

    private volatile List<TableTokens> corpusCache = List.of();
    private volatile int corpusCacheSize = -1;

    /** Corpus noms + descriptions générales de tout l'index, reconstruit seulement quand l'index change de taille. */
    private List<TableTokens> corpus() {
        List<Document> all = vectorStoreService.findAll();
        if (all.size() != corpusCacheSize) {
            List<TableTokens> built = new ArrayList<>();
            for (Document document : dedupe(all)) {
                built.add(tokensOf(document));
            }
            corpusCache = built;
            corpusCacheSize = all.size();
        }
        return corpusCache;
    }

    private static TableTokens tokensOf(Document document) {
        Set<String> tokens = new java.util.HashSet<>();
        String text = withoutAccents(tableName(document).replace('.', ' ') + " " + overviewOf(document)).toLowerCase(Locale.ROOT);
        Matcher word = TOKEN.matcher(text);
        while (word.find()) {
            tokens.add(word.group());
        }
        return new TableTokens(tableName(document), tokens);
    }

    /** Somme des poids des concepts présents dans le nom OU la description générale de la table. */
    private static double coverage(Document table, Map<String, Set<String>> concepts, Map<String, Double> weights, Set<String> covered) {
        TableTokens tokens = tokensOf(table);
        double score = 0;
        for (Map.Entry<String, Set<String>> concept : concepts.entrySet()) {
            if (tokens.matches(concept.getKey(), concept.getValue())) {
                score += weights.get(concept.getKey());
                covered.add(concept.getKey());
            }
        }
        return score;
    }

    private record Covered(Document table, double score, Set<String> concepts) {
    }

    /**
     * Question décrivant plusieurs concepts discriminants : la table dont le nom et la description générale couvrent le plus de
     * poids de la question l'emporte. Réponse directe quand elle se détache nettement ; demande de précision seulement si
     * plusieurs tables sont réellement à égalité. Les autres tables ne sont citées que si elles couvrent au moins la moitié du
     * poids de la meilleure (jamais une table qui ne partage qu'un mot avec la question).
     */
    private Optional<String> coverageAnswer(String question, String module, List<Document> inScope, Map<String, Set<String>> concepts,
                                            Map<String, Double> weights) {
        double totalWeight = concepts.keySet().stream().mapToDouble(weights::get).sum();
        boolean elsewhere = false;
        List<Covered> ranked = rankByCoverage(inScope, concepts, weights, semanticScores(question, module, inScope.size()), totalWeight);
        if (ranked.isEmpty() && module != null) {
            List<Document> all = dedupe(vectorStoreService.findAll());
            ranked = rankByCoverage(all, concepts, weights, semanticScores(question, null, SEMANTIC_TOP_K_ALL), totalWeight);
            elsewhere = true;
        }
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        Covered best = ranked.get(0);
        List<Covered> tied = ranked.stream().filter(c -> c.score() >= best.score() * TIE_RATIO).toList();
        List<Covered> others = ranked.stream().filter(c -> !tied.contains(c) && c.score() >= best.score() * OTHERS_RATIO)
                .limit(MAX_OTHERS).toList();
        String scope = module != null && !elsewhere ? "du module " + module : "de la documentation";
        log.info("Recherche de tables par couverture des concepts : meilleure {} ({} / {}), {} à égalité, {} autre(s) retenue(s).",
                tableName(best.table()), String.format(Locale.ROOT, "%.2f", best.score()),
                String.format(Locale.ROOT, "%.2f", totalWeight), tied.size(), others.size());

        StringBuilder sb = new StringBuilder("**Réponse :** ");
        if (elsewhere) {
            sb.append("aucune table du module ").append(module).append(" ne correspond à votre question. ");
        }
        List<Document> shown = new ArrayList<>();
        if (tied.size() == 1) {
            sb.append("la table ").append(scope).append(" dont la documentation correspond le mieux à votre question est `")
                    .append(tableName(best.table())).append("`").append(elsewhere ? " (module " + best.table().getMetadata().get("product") + ")" : "")
                    .append(" — elle couvre : ").append(String.join(", ", best.concepts())).append('.');
            String overview = overviewOf(best.table());
            sb.append("\n\n**Description générale :**\n> « ").append(overview.isEmpty() ? "non renseignée dans la documentation"
                    : DocumentOverviewBuilder.firstSentences(overview, BEST_OVERVIEW_CHARS)).append(" »");
            shown.add(best.table());
        } else {
            sb.append(tied.size()).append(" tables ").append(scope).append(" correspondent au même degré à votre question :");
            appendTables(sb, tied.stream().map(Covered::table).toList(), MAX_EQUIVALENT);
            sb.append("\n\nLaquelle vous intéresse ?");
            tied.stream().limit(MAX_EQUIVALENT).forEach(c -> shown.add(c.table()));
        }
        if (!others.isEmpty()) {
            sb.append("\n\n**Autres tables qui couvrent une partie de la question :**");
            others.forEach(c -> sb.append("\n- `").append(tableName(c.table())).append("` (").append(String.join(", ", c.concepts())).append(')'));
        }
        sb.append("\n\nPour le détail d'une table, demandez par exemple « Décris la table ").append(tableName(shown.get(0))).append(" ».");
        sb.append("\n\n**Source :** ").append(shown.stream().map(d -> "`" + tableName(d) + "`").collect(Collectors.joining(" ")));
        return Optional.of(sb.toString());
    }

    /**
     * Score final = part du poids des concepts couverte (0..1) + {@value #SEMANTIC_WEIGHT} x similarité sémantique normalisée
     * dans le périmètre (0..1) : la couverture des concepts décide, la similarité départage (mesuré le 2026-10-07 : à poids
     * égal, elle faisait passer LC.TYPES devant LC.ADVICE.TEXT).
     * La similarité comble l'écart français/anglais que le glossaire ne couvre pas (« textes narratifs statiques » /
     * « static narrative ») ; une table n'est jamais candidate sur la seule similarité : elle doit couvrir au moins un concept
     * réellement documenté de la question.
     */
    /** Similarité sémantique (cosinus de l'index vectoriel) de la question avec chaque table du périmètre, normalisée min-max. */
    private Map<String, Double> semanticScores(String question, String module, int topK) {
        List<Document> similar;
        try {
            similar = vectorStoreService.similaritySearch(question, Math.max(topK, 1), module, List.of());
        } catch (RuntimeException e) {
            log.warn("Similarité sémantique indisponible pour la recherche de tables ({}) : couverture lexicale seule.", e.getMessage());
            return Map.of();
        }
        if (similar == null || similar.isEmpty()) {
            return Map.of();
        }
        double max = similar.stream().mapToDouble(d -> d.getScore() == null ? 0 : d.getScore()).max().orElse(0);
        double min = similar.stream().mapToDouble(d -> d.getScore() == null ? 0 : d.getScore()).min().orElse(0);
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Document d : similar) {
            double score = d.getScore() == null ? 0 : d.getScore();
            normalized.putIfAbsent(tableName(d).toUpperCase(Locale.ROOT), max - min < 1e-9 ? 1.0 : (score - min) / (max - min));
        }
        return normalized;
    }

    private static List<Covered> rankByCoverage(List<Document> tables, Map<String, Set<String>> concepts, Map<String, Double> weights,
                                                Map<String, Double> semantic, double totalWeight) {
        return tables.stream()
                .map(table -> {
                    Set<String> covered = new LinkedHashSet<>();
                    double lexical = coverage(table, concepts, weights, covered);
                    double score = lexical <= 0 ? 0 : lexical / Math.max(totalWeight, 1e-9)
                            + SEMANTIC_WEIGHT * semantic.getOrDefault(tableName(table).toUpperCase(Locale.ROOT), 0.0);
                    return new Covered(table, score, covered);
                })
                .filter(c -> c.score() > 0)
                .sorted(Comparator.comparingDouble(Covered::score).reversed()
                        .thenComparing(c -> overviewOf(c.table()).isEmpty())
                        .thenComparing(c -> tableName(c.table())))
                .toList();
    }

    /**
     * Ordre de pertinence documentaire, sans jamais affirmer de hiérarchie métier : d'abord les tables dont le nom contient le
     * moins de qualificatifs en plus du concept (MD.BALANCES avant MD.PROV.BALANCES, propre aux provisions), le préfixe de
     * module n'étant pas un qualificatif ; puis celles qui ont une description générale réelle ; puis l'ordre alphabétique.
     */
    private static List<Document> rank(List<Document> tables, Set<String> variants, String module) {
        return tables.stream()
                .sorted(Comparator.comparingInt((Document d) -> qualifiers(d, variants, module))
                        .thenComparing(d -> overviewOf(d).isEmpty())
                        .thenComparing(TableFinder::tableName))
                .toList();
    }

    /** Segments du nom qui ne sont ni le concept ni le préfixe de module (de la conversation, à défaut celui de la table). */
    private static int qualifiers(Document table, Set<String> variants, String module) {
        String prefix = module != null ? module : String.valueOf(table.getMetadata().get("product")).toUpperCase(Locale.ROOT);
        String[] segments = tableName(table).toUpperCase(Locale.ROOT).split("\\.");
        int count = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            String singular = segment.length() > 4 && segment.endsWith("S") ? segment.substring(0, segment.length() - 1) : segment;
            boolean concept = variants.contains(segment) || variants.contains(singular);
            if (!concept && !(i == 0 && segment.equals(prefix))) {
                count++;
            }
        }
        return count;
    }

    /** Segments de noms de tables qui correspondent au concept, au singulier (BALANCES -&gt; BALANCE). */
    private static Set<String> matchedNameTerms(List<Document> tables, Set<String> variants) {
        Set<String> found = new LinkedHashSet<>();
        for (Document table : tables) {
            for (String segment : tableName(table).toUpperCase(Locale.ROOT).split("\\.")) {
                String singular = segment.length() > 4 && segment.endsWith("S") ? segment.substring(0, segment.length() - 1) : segment;
                if (variants.contains(segment) || variants.contains(singular)) {
                    found.add(singular);
                }
            }
        }
        return found;
    }

    /** Mots porteurs du concept (hors mots de question et code du module) -&gt; leurs variantes en majuscules (pluriel, anglais). */
    private Map<String, Set<String>> conceptVariants(String question, String module) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Matcher word = WORD.matcher(question.replaceAll("(?iu)\\b[ldj]'", ""));
        while (word.find()) {
            String raw = word.group();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.length() < 3 || STOP_WORDS.contains(lower) || raw.equalsIgnoreCase(module)) {
                continue;
            }
            Set<String> variants = new LinkedHashSet<>();
            addWithSingular(variants, withoutAccents(raw).toUpperCase(Locale.ROOT));
            conceptGlossary.englishTermsFor(lower).forEach(en -> addWithSingular(variants, en.toUpperCase(Locale.ROOT)));
            result.put(lower, variants);
        }
        return result;
    }

    private static String withoutAccents(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static void addWithSingular(Set<String> variants, String term) {
        variants.add(term);
        if (term.length() > 4 && term.endsWith("S")) {
            variants.add(term.substring(0, term.length() - 1));
        }
    }

    /** Un segment du nom (MD.BALANCES -&gt; MD, BALANCES) égal à une variante, au pluriel près. */
    private static boolean nameMatches(String tableName, Set<String> variants) {
        for (String segment : tableName.toUpperCase(Locale.ROOT).split("\\.")) {
            String singular = segment.length() > 4 && segment.endsWith("S") ? segment.substring(0, segment.length() - 1) : segment;
            if (variants.contains(segment) || variants.contains(singular)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overviewMentions(String overview, Set<String> variants) {
        if (overview.isEmpty()) {
            return false;
        }
        for (String variant : variants) {
            if (variant.length() >= 4 && Pattern.compile("(?i)\\b" + Pattern.quote(variant) + "s?\\b").matcher(overview).find()) {
                return true;
            }
        }
        return false;
    }

    /** Question ciblée (« Quelle table… ? ») : la ou les tables les plus directes d'abord, quelques autres, jamais un inventaire. */
    private String focusedAnswer(String concept, String note, String module, Set<String> variants, List<Document> nameHits,
                                 List<Document> overviewHits, List<Document> elsewhere) {
        String scope = module != null ? "du module " + module : "de la documentation";
        StringBuilder sb = new StringBuilder("**Réponse :** ");
        List<Document> shown = new ArrayList<>();
        int total;
        if (!nameHits.isEmpty()) {
            int best = qualifiers(nameHits.get(0), variants, module);
            List<Document> top = nameHits.stream().filter(d -> qualifiers(d, variants, module) == best).toList();
            List<Document> others = nameHits.stream().filter(d -> !top.contains(d)).toList();
            if (top.size() == 1) {
                sb.append("la table ").append(scope).append(" dont le nom correspond le plus directement à « ").append(concept).append(" »")
                        .append(note).append(" est `").append(tableName(top.get(0))).append("`.");
                appendTables(sb, top, 1);
            } else {
                sb.append(top.size()).append(" tables ").append(scope).append(" correspondent au même degré à « ").append(concept)
                        .append(" »").append(note).append(" :");
                appendTables(sb, top, MAX_EQUIVALENT);
                sb.append("\n\nLaquelle vous intéresse ?");
            }
            shown.addAll(top.stream().limit(MAX_EQUIVALENT).toList());
            if (!others.isEmpty()) {
                sb.append("\n\n**Autres tables ").append(scope).append(" dont le nom contient « ").append(concept).append(" » :**");
                appendTables(sb, others, MAX_OTHERS);
                shown.addAll(others.stream().limit(MAX_OTHERS).toList());
            }
            total = nameHits.size();
        } else if (!overviewHits.isEmpty()) {
            sb.append("aucune table ").append(scope).append(" ne porte « ").append(concept).append(" » dans son nom").append(note)
                    .append(" ; ").append(overviewHits.size() > 1 ? overviewHits.size() + " tables en parlent" : "1 table en parle")
                    .append(" dans leur description générale :");
            appendTables(sb, overviewHits, MAX_OTHERS);
            shown.addAll(overviewHits.stream().limit(MAX_OTHERS).toList());
            total = overviewHits.size();
        } else {
            sb.append("aucune table ").append(scope).append(" ne correspond à « ").append(concept).append(" »").append(note)
                    .append(". Dans d'autres modules, les tables dont le nom correspond le plus directement sont :");
            appendTablesWithModule(sb, elsewhere, MAX_OTHERS);
            shown.addAll(elsewhere.stream().limit(MAX_OTHERS).toList());
            total = elsewhere.size();
        }
        if (!elsewhere.isEmpty() && !nameHits.isEmpty()) {
            sb.append("\n\n**Dans d'autres modules :**");
            appendTablesWithModule(sb, elsewhere, MAX_OTHERS);
        }
        if (total > shown.size()) {
            sb.append("\n\n").append(total).append(" tables correspondent au total ; pour la liste complète, demandez « Quelles sont toutes "
                    + "les tables liées à ").append(concept.toLowerCase(Locale.ROOT)).append(" ? ».");
        }
        sb.append("\n\nPour le détail d'une table, demandez par exemple « Décris la table ").append(tableName(shown.get(0))).append(" ».");
        sb.append("\n\n**Source :** ").append(shown.stream().map(d -> "`" + tableName(d) + "`").collect(Collectors.joining(" ")));
        return sb.toString();
    }

    /** Demande explicite (« toutes les tables… ») : l'inventaire, classé de la même façon. */
    private String fullList(String concept, String note, String module, List<Document> nameHits, List<Document> overviewHits,
                            List<Document> elsewhere) {
        String scope = module != null ? "du module " + module : "de la documentation";
        StringBuilder sb = new StringBuilder("**Réponse :** ");
        if (nameHits.isEmpty()) {
            sb.append("aucune table ").append(scope).append(" ne porte « ").append(concept).append(" » dans son nom").append(note).append('.');
        } else {
            boolean plural = nameHits.size() > 1;
            sb.append(nameHits.size()).append(plural ? " tables " : " table ").append(scope)
                    .append(plural ? " portent" : " porte").append(" « ").append(concept).append(" » dans ")
                    .append(plural ? "leur" : "son").append(" nom").append(note).append(" :");
            appendTables(sb, nameHits, MAX_FULL_LIST);
        }
        if (!overviewHits.isEmpty()) {
            sb.append("\n\n**Tables ").append(scope).append(" dont la description générale le mentionne :**");
            appendTables(sb, overviewHits, MAX_FULL_LIST);
        }
        if (!elsewhere.isEmpty()) {
            sb.append("\n\n**Dans d'autres modules :**");
            appendTablesWithModule(sb, elsewhere, MAX_FULL_LIST);
        }
        List<Document> sources = new ArrayList<>(nameHits);
        sources.addAll(overviewHits);
        if (sources.isEmpty()) {
            sources.addAll(elsewhere);
        }
        sb.append("\n\n**Source :** ").append(sources.stream().limit(8).map(d -> "`" + tableName(d) + "`").collect(Collectors.joining(" ")));
        if (sources.size() > 8) {
            sb.append(" …");
        }
        return sb.toString();
    }

    private static void appendTablesWithModule(StringBuilder sb, List<Document> tables, int max) {
        tables.stream().limit(max).forEach(d -> {
            String overview = overviewOf(d);
            sb.append("\n- `").append(tableName(d)).append("` (module ").append(d.getMetadata().get("product")).append(") — ")
                    .append(overview.isEmpty() ? "description générale non renseignée dans la documentation."
                            : "« " + DocumentOverviewBuilder.firstSentences(overview, OVERVIEW_CHARS) + " »");
        });
        if (tables.size() > max) {
            sb.append("\n- … et ").append(tables.size() - max).append(" autre(s)");
        }
    }

    private static void appendTables(StringBuilder sb, List<Document> tables, int max) {
        tables.stream().limit(max).forEach(d -> {
            String overview = overviewOf(d);
            sb.append("\n- `").append(tableName(d)).append("` — ").append(overview.isEmpty()
                    ? "description générale non renseignée dans la documentation."
                    : "« " + DocumentOverviewBuilder.firstSentences(overview, OVERVIEW_CHARS) + " »");
        });
        if (tables.size() > max) {
            sb.append("\n- … et ").append(tables.size() - max).append(" autre(s)");
        }
    }

    /** Description générale réelle (vide si « Non renseignée »), aplatie. */
    private static String overviewOf(Document document) {
        String overview = DocumentOverviewBuilder.section(document.getText(), "Description générale :\n");
        return overview.isBlank() || overview.startsWith("Non renseignée") ? "" : overview.replaceAll("\\s+", " ").trim();
    }

    private static String tableName(Document document) {
        return String.valueOf(document.getMetadata().get("tableName"));
    }

    private static List<Document> dedupe(List<Document> documents) {
        Map<String, Document> byName = new LinkedHashMap<>();
        documents.stream().sorted(Comparator.comparing(TableFinder::tableName))
                .forEach(d -> byName.putIfAbsent(tableName(d).toUpperCase(Locale.ROOT), d));
        return new ArrayList<>(byName.values());
    }
}
