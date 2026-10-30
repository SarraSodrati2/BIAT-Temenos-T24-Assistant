package backend.service.impl;

import backend.dto.ChatContext;
import backend.entity.HelpField;
import backend.repository.HelpFieldRepository;
import backend.service.FieldSearchService;
import backend.service.SynonymDictionary;
import backend.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

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
 * Voir {@link FieldSearchService}. Périmètre : la table du contexte de conversation, sinon le module
 * cité dans la question (« dans TT ») ou fixé dans le contexte, sinon toute la documentation.
 */
@Slf4j
@Service
public class FieldSearchServiceImpl implements FieldSearchService {

    /** « quel(s) champ(s)/colonne(s) … contien(nen)t/mentionne(nt)/… », « dans quelle table … », etc. */
    private static final Pattern INTENT_FR = Pattern.compile(
            "(?iu)\\b(?:quel(?:le)?s?|dans\\s+quel(?:le)?s?|où)\\b.{0,80}?\\b(?:champs?|colonnes?|tables?)\\b.{0,120}?"
                    + "\\b(?:contien\\w*|mention\\w*|renferm\\w*|stock\\w*|correspond\\w*|concern\\w*|référenc\\w*|referenc\\w*|utilis\\w*)\\b"
                    + "|\\b(?:cherche|recherche|trouve)\\w*\\b.{0,40}?\\b(?:champs?|colonnes?)\\b");
    private static final Pattern INTENT_EN = Pattern.compile(
            "(?iu)\\bwhich\\s+(?:fields?|columns?|tables?)\\b.{0,100}?\\b(?:contain\\w*|mention\\w*|store\\w*|hold\\w*|use\\w*|relat\\w*)\\b"
                    + "|\\b(?:find|search)\\s+(?:for\\s+)?(?:the\\s+)?(?:fields?|columns?)\\b");

    private static final Pattern QUOTED_TERM = Pattern.compile("[\"«“”`]\\s*([^\"»“”`]{2,60}?)\\s*[\"»“”`]");
    /** Mot en majuscules (SWIFT, MD.DEAL, AC.ENTRY.PARAM) : la convention T24, même dans une question tapée en minuscules. */
    private static final Pattern UPPER_TERM = Pattern.compile("\\b[A-Z][A-Z0-9]{2,}(?:\\.[A-Z0-9]+)*\\b");
    private static final Pattern TERM_AFTER_LABEL = Pattern.compile(
            "(?iu)\\b(?:code|mot|terme|valeur|word|term|value|mention)\\s+(?:de\\s+|du\\s+|d'|des\\s+|the\\s+|of\\s+)?"
                    + "([\\p{L}0-9][\\p{L}0-9._-]{2,40})");
    private static final Pattern MODULE_IN_QUESTION =
            Pattern.compile("\\b(?:dans|in|du|de|module|pour|for)\\s+(?:le\\s+|the\\s+)?(?:module\\s+)?([A-Z]{2,5})\\b");

    /** Mots courants en majuscules qui ne sont jamais le terme recherché. */
    private static final Set<String> NOT_TERMS = Set.of("QUEL", "QUELS", "QUELLE", "QUELLES", "DANS", "LES", "DES", "LE", "LA",
            "CHAMP", "CHAMPS", "TABLE", "TABLES", "MODULE", "MODULES", "CODE", "WHICH", "FIELD", "FIELDS", "THE", "AND", "ET");

    /** En dessous de cette longueur, un terme (ex. BIC) est cherché comme mot entier pour éviter « public », « fabric »… */
    private static final int WHOLE_WORD_MAX_LENGTH = 4;
    private static final int MAX_NAME_HITS = 40;
    private static final int MAX_DESCRIPTION_HITS = 30;
    private static final int MAX_ELSEWHERE_HITS = 10;
    private static final int EXCERPT_RADIUS = 110;
    private static final int MAX_VARIANTS = 6;

    private final HelpFieldRepository helpFieldRepository;
    private final VectorStoreService vectorStoreService;
    private final SynonymDictionary synonymDictionary;
    private final DocumentOverviewBuilder overviewBuilder;
    /** Injecté par Spring (facultatif : les tests unitaires existants construisent ce service sans lui). */
    private TableFinder tableFinder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTableFinder(TableFinder tableFinder) {
        this.tableFinder = tableFinder;
    }

    public FieldSearchServiceImpl(HelpFieldRepository helpFieldRepository, VectorStoreService vectorStoreService,
                                  SynonymDictionary synonymDictionary, DocumentOverviewBuilder overviewBuilder) {
        this.helpFieldRepository = helpFieldRepository;
        this.vectorStoreService = vectorStoreService;
        this.synonymDictionary = synonymDictionary;
        this.overviewBuilder = overviewBuilder;
    }

    @Override
    public Optional<String> tryAnswer(String question, ChatContext chatContext) {
        // « Quels champs contient la table TELLER.ID ? » ressemble à une recherche inversée mais nomme une table
        // qui existe : c'est une demande de fiche de table, prioritaire.
        Optional<String> tableSheet = overviewBuilder.tryTableOverview(question);
        if (tableSheet.isPresent()) {
            return tableSheet;
        }
        // « Quelle table est responsable des balances ? » : recherche de TABLES par concept (voir TableFinder).
        if (tableFinder != null) {
            Optional<String> tables = tableFinder.tryAnswer(question, chatContext);
            if (tables.isPresent()) {
                return tables;
            }
        }
        if (!isFieldSearchQuestion(question)) {
            return overviewBuilder.tryModuleOverview(question, chatContext);
        }
        Scope scope = resolveScope(question, chatContext);
        Optional<String> term = extractTerm(question, scope.module());
        if (term.isEmpty()) {
            log.info("Recherche inversée de champ détectée mais aucun terme exploitable dans la question : pipeline RAG habituel.");
            return Optional.empty();
        }

        List<String> variants = variantsOf(term.get());
        log.info("Recherche inversée de champ : terme='{}', variantes={}, périmètre={}.", term.get(), variants, scope);
        return Optional.of(buildAnswer(term.get(), variants, scope));
    }

    // --- Détection ---------------------------------------------------------------------------------------

    static boolean isFieldSearchQuestion(String question) {
        return INTENT_FR.matcher(question).find() || INTENT_EN.matcher(question).find();
    }

    /** Terme recherché : texte entre guillemets, sinon mot en majuscules (hors module), sinon mot suivant « code/mot/valeur ». */
    static Optional<String> extractTerm(String question, String moduleToExclude) {
        Matcher quoted = QUOTED_TERM.matcher(question);
        if (quoted.find()) {
            return Optional.of(quoted.group(1).trim());
        }
        Matcher upper = UPPER_TERM.matcher(question);
        while (upper.find()) {
            String candidate = upper.group();
            if (NOT_TERMS.contains(candidate) || candidate.equalsIgnoreCase(moduleToExclude)) {
                continue;
            }
            return Optional.of(candidate);
        }
        Matcher afterLabel = TERM_AFTER_LABEL.matcher(question);
        if (afterLabel.find()) {
            String candidate = afterLabel.group(1);
            if (!NOT_TERMS.contains(candidate.toUpperCase(Locale.ROOT))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private enum ScopeKind { TABLE, MODULE, ALL }

    private record Scope(ScopeKind kind, String module, String table) {
        @Override
        public String toString() {
            return kind + (table != null ? ":" + table : module != null ? ":" + module : "");
        }
    }

    /** Un module explicitement cité dans la question (« dans TT ») prime sur le contexte de conversation. */
    private Scope resolveScope(String question, ChatContext chatContext) {
        Matcher m = MODULE_IN_QUESTION.matcher(question);
        while (m.find()) {
            String code = m.group(1);
            if (!NOT_TERMS.contains(code) && helpFieldRepository.countByProductIgnoreCase(code) > 0) {
                return new Scope(ScopeKind.MODULE, code.toUpperCase(Locale.ROOT), null);
            }
        }
        if (chatContext.hasTable()) {
            return new Scope(ScopeKind.TABLE, chatContext.hasModule() ? chatContext.module() : null, chatContext.table());
        }
        if (chatContext.hasModule()) {
            return new Scope(ScopeKind.MODULE, chatContext.module().toUpperCase(Locale.ROOT), null);
        }
        return new Scope(ScopeKind.ALL, null, null);
    }

    /** Terme + variantes du dictionnaire de synonymes (ex. SWIFT -> BIC) ; jamais un nom de champ deviné. */
    private List<String> variantsOf(String term) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(term);
        for (String synonym : synonymDictionary.expand(term)) {
            if (synonym.length() >= 3 && synonym.length() <= 40) {
                variants.add(synonym);
            }
        }
        return variants.stream().limit(MAX_VARIANTS).toList();
    }

    // --- Recherche ---------------------------------------------------------------------------------------

    private enum HitKind { EXACT_NAME, NAME, DESCRIPTION }

    private record Hit(HelpField field, HitKind kind, String excerpt) {
    }

    private String buildAnswer(String term, List<String> variants, Scope scope) {
        Candidates candidates = candidatesFor(scope, variants);
        List<Hit> hits = classify(candidates.fields(), variants);
        List<Hit> nameHits = hits.stream().filter(h -> h.kind() != HitKind.DESCRIPTION).toList();
        List<Hit> descriptionHits = hits.stream().filter(h -> h.kind() == HitKind.DESCRIPTION).toList();
        List<Hit> overviewHits = scope.kind() == ScopeKind.ALL ? List.of() : overviewMentions(scope, variants);

        StringBuilder sb = new StringBuilder();
        String quotedTerm = "« " + term + " »";

        sb.append("**Réponse**\n\n");
        sb.append(directAnswer(quotedTerm, describeScope(scope), nameHits, descriptionHits, overviewHits, variants, candidates.capped()))
                .append("\n\n");

        boolean showModule = scope.kind() == ScopeKind.ALL;
        if (!nameHits.isEmpty()) {
            sb.append("**Champs dont le nom correspond**\n\n");
            appendHits(sb, nameHits, MAX_NAME_HITS, showModule);
        }
        if (!descriptionHits.isEmpty()) {
            sb.append("**Champs dont la description mentionne ").append(quotedTerm).append("**\n\n");
            appendHits(sb, descriptionHits, MAX_DESCRIPTION_HITS, showModule);
        }
        if (!overviewHits.isEmpty()) {
            sb.append("**Tables dont la description générale mentionne ").append(quotedTerm).append("**\n\n");
            for (Hit hit : overviewHits) {
                sb.append("- `").append(hit.field().getTableName()).append("` — « ").append(hit.excerpt()).append(" »\n");
            }
            sb.append('\n');
        }

        appendScopeNote(sb, scope, variants, !hits.isEmpty() || !overviewHits.isEmpty(), candidates.capped());
        if (scope.kind() != ScopeKind.ALL) {
            appendElsewhere(sb, scope, variants);
        }

        Set<String> sources = new LinkedHashSet<>();
        hits.stream().limit(MAX_NAME_HITS).forEach(h -> sources.add(h.field().getTableName()));
        overviewHits.forEach(h -> sources.add(h.field().getTableName()));
        if (!sources.isEmpty()) {
            sb.append("**Source**\n\n");
            sb.append(sources.stream().limit(8).map(t -> "`" + t + "`").collect(Collectors.joining(" ")));
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Résultats bruts de la base ; {@code capped} : au moins une requête a atteint son plafond (il peut y en avoir davantage). */
    private record Candidates(List<HelpField> fields, boolean capped) {
    }

    private static final int MODULE_QUERY_CAP = 400;
    private static final int NAME_QUERY_CAP = 200;
    private static final int DESCRIPTION_QUERY_CAP = 400;

    private Candidates candidatesFor(Scope scope, List<String> variants) {
        List<HelpField> candidates = new ArrayList<>();
        boolean capped = false;
        switch (scope.kind()) {
            case TABLE -> candidates.addAll(helpFieldRepository.findByTableNameIgnoreCase(scope.table()));
            case MODULE -> {
                for (String variant : variants) {
                    List<HelpField> found = helpFieldRepository.searchByTermInProduct(scope.module(), sanitize(variant));
                    capped |= found.size() >= MODULE_QUERY_CAP;
                    candidates.addAll(found);
                }
            }
            case ALL -> {
                for (String variant : variants) {
                    List<HelpField> byName = helpFieldRepository.searchFieldNamesAnywhere(sanitize(variant));
                    List<HelpField> byDescription = helpFieldRepository.searchDescriptionsAnywhere(sanitize(variant));
                    capped |= byName.size() >= NAME_QUERY_CAP || byDescription.size() >= DESCRIPTION_QUERY_CAP;
                    candidates.addAll(byName);
                    candidates.addAll(byDescription);
                }
            }
        }
        return new Candidates(candidates, capped);
    }

    private static String directAnswer(String quotedTerm, String scopeLabel, List<Hit> nameHits, List<Hit> descriptionHits,
                                       List<Hit> overviewHits, List<String> variants, boolean capped) {
        String atLeast = capped ? "Au moins " : "";
        if (nameHits.isEmpty() && descriptionHits.isEmpty() && overviewHits.isEmpty()) {
            return "La recherche n'a retrouvé aucun champ " + scopeLabel + " dont le nom ou la description contienne "
                    + quotedTerm + (variants.size() > 1 ? " ou l'une de ses variantes" : "")
                    + ". Cela ne suffit pas à affirmer que l'information n'existe pas dans T24 (voir l'étendue de la recherche ci-dessous).";
        }
        StringBuilder sb = new StringBuilder();
        if (nameHits.isEmpty()) {
            sb.append("Aucun nom de champ ").append(scopeLabel).append(" ne contient ").append(quotedTerm).append(". ");
        } else {
            sb.append(atLeast.isEmpty() ? "" : atLeast).append(nameHits.size()).append(nameHits.size() > 1 ? " champs " : " champ ").append(scopeLabel)
                    .append(nameHits.size() > 1 ? " ont" : " a").append(" un nom qui correspond à ").append(quotedTerm).append(". ");
        }
        if (!descriptionHits.isEmpty()) {
            sb.append(nameHits.isEmpty() ? atLeast : (capped ? "Au moins " : "")).append(descriptionHits.size())
                    .append(descriptionHits.size() > 1 ? " champs mentionnent " : " champ mentionne ")
                    .append(quotedTerm).append(" dans leur description ou leurs règles de validation. ");
        }
        if (!overviewHits.isEmpty()) {
            sb.append(overviewHits.size() > 1 ? overviewHits.size() + " tables le mentionnent dans leur description générale. "
                    : "1 table le mentionne dans sa description générale. ");
        }
        if (nameHits.isEmpty()) {
            sb.append("Lisez les extraits ci-dessous : une mention dans une description ne signifie pas que le champ "
                    + "contient ce code, elle peut par exemple décrire un format de saisie.");
        }
        return sb.toString().trim();
    }

    private List<Hit> classify(List<HelpField> candidates, List<String> variants) {
        // Une même table peut être importée deux fois (variantes de fichier "X,ISB.xml") : dédoublonné sur
        // table + champ pour ne jamais afficher deux fois le même résultat.
        Map<String, Hit> byTableAndField = new LinkedHashMap<>();
        for (HelpField field : candidates) {
            Hit hit = classify(field, variants);
            if (hit != null) {
                byTableAndField.putIfAbsent(identityOf(field), hit);
            }
        }
        return byTableAndField.values().stream()
                .sorted(Comparator.comparing((Hit h) -> h.kind().ordinal())
                        .thenComparing(h -> String.valueOf(h.field().getTableName()))
                        .thenComparing(h -> String.valueOf(h.field().getFieldName())))
                .toList();
    }

    private static Hit classify(HelpField field, List<String> variants) {
        String name = field.getFieldName() == null ? "" : field.getFieldName();
        String description = field.getDescription() == null ? "" : field.getDescription();
        for (String variant : variants) {
            if (name.equalsIgnoreCase(variant)) {
                return new Hit(field, HitKind.EXACT_NAME, excerptOf(description, variants));
            }
        }
        for (String variant : variants) {
            if (mentions(name, variant)) {
                return new Hit(field, HitKind.NAME, excerptOf(description, variants));
            }
        }
        for (String variant : variants) {
            if (mentions(description, variant)) {
                return new Hit(field, HitKind.DESCRIPTION, excerptOf(description, variants));
            }
        }
        return null;
    }

    private static String identityOf(HelpField field) {
        return String.valueOf(field.getTableName()).toLowerCase(Locale.ROOT) + "|"
                + String.valueOf(field.getFieldName()).toLowerCase(Locale.ROOT);
    }

    /** Sous-chaîne insensible à la casse ; mot entier pour un terme court (voir {@link #WHOLE_WORD_MAX_LENGTH}). */
    static boolean mentions(String text, String term) {
        if (text == null || text.isEmpty() || term == null || term.isBlank()) {
            return false;
        }
        if (term.length() <= WHOLE_WORD_MAX_LENGTH) {
            return Pattern.compile("(?<![\\p{L}0-9])" + Pattern.quote(term) + "(?![\\p{L}0-9])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
        }
        return text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    /** Fenêtre de texte autour de la première occurrence — recopiée telle quelle (espaces normalisés), jamais reformulée. */
    static String excerptOf(String text, List<String> variants) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        String lower = flat.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String variant : variants) {
            int idx = lower.indexOf(variant.toLowerCase(Locale.ROOT));
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        if (best < 0) {
            return flat.length() > 2 * EXCERPT_RADIUS ? flat.substring(0, 2 * EXCERPT_RADIUS).trim() + "…" : flat;
        }
        int start = Math.max(0, best - EXCERPT_RADIUS);
        int end = Math.min(flat.length(), best + EXCERPT_RADIUS);
        return (start > 0 ? "…" : "") + flat.substring(start, end).trim() + (end < flat.length() ? "…" : "");
    }

    /** Description générale (avant la section « Champs ») des tables du périmètre qui mentionnent une variante. */
    private List<Hit> overviewMentions(Scope scope, List<String> variants) {
        List<Document> documents = new ArrayList<>();
        if (scope.kind() == ScopeKind.TABLE) {
            vectorStoreService.findByExactTableName(scope.table()).ifPresent(documents::add);
        } else {
            documents.addAll(vectorStoreService.findAllByProduct(scope.module()));
        }
        List<Hit> hits = new ArrayList<>();
        for (Document document : documents) {
            String text = document.getText();
            int fieldsIdx = text.indexOf("Champs (");
            String head = fieldsIdx > 0 ? text.substring(0, fieldsIdx) : text;
            if (variants.stream().anyMatch(v -> mentions(head, v))) {
                HelpField overview = new HelpField();
                overview.setTableName(String.valueOf(document.getMetadata().get("tableName")));
                hits.add(new Hit(overview, HitKind.DESCRIPTION, excerptOf(head, variants)));
            }
        }
        hits.sort(Comparator.comparing(h -> h.field().getTableName()));
        return hits;
    }

    private static void appendHits(StringBuilder sb, List<Hit> hits, int max, boolean showModule) {
        for (Hit hit : hits.stream().limit(max).toList()) {
            HelpField f = hit.field();
            sb.append("- ");
            if (showModule && f.getProduct() != null) {
                sb.append("[").append(f.getProduct()).append("] ");
            }
            sb.append('`').append(f.getTableName()).append("` › `").append(f.getFieldName()).append('`');
            if (hit.kind() == HitKind.EXACT_NAME) {
                sb.append(" — nom exact");
            } else if (hit.kind() == HitKind.NAME) {
                sb.append(" — nom approchant");
            }
            if (!hit.excerpt().isBlank()) {
                sb.append(" — « ").append(hit.excerpt()).append(" »");
            }
            sb.append('\n');
        }
        if (hits.size() > max) {
            sb.append("- … et ").append(hits.size() - max).append(" autre(s) résultat(s) non affiché(s).\n");
        }
        sb.append('\n');
    }

    private void appendScopeNote(StringBuilder sb, Scope scope, List<String> variants, boolean foundSomething, boolean capped) {
        sb.append("**Étendue de la recherche**\n\n");
        String variantsText = variants.stream().map(v -> "`" + v + "`").collect(Collectors.joining(", "));
        switch (scope.kind()) {
            case MODULE -> sb.append("Noms et descriptions des ")
                    .append(helpFieldRepository.countDistinctFieldsByProduct(scope.module())).append(" champs documentés dans les ")
                    .append(helpFieldRepository.countDistinctTablesByProduct(scope.module())).append(" tables du module `")
                    .append(scope.module()).append('`');
            case TABLE -> sb.append("Noms et descriptions des champs documentés de la table `").append(scope.table()).append('`');
            case ALL -> sb.append("Noms et descriptions des champs de toute la documentation indexée (résultats plafonnés)");
        }
        sb.append(". Termes recherchés : ").append(variantsText).append(". ");
        if (capped) {
            sb.append("La base renvoie au plus quelques centaines de résultats par terme : la liste ci-dessous est un extrait, "
                    + "il peut exister d'autres résultats. ");
        }
        sb.append(foundSomething
                ? "Seuls les champs et descriptions présents dans la documentation T24 importée sont examinés."
                : "Cette recherche porte uniquement sur la documentation T24 importée : elle peut être incomplète, "
                        + "et un terme formulé autrement (autre nom, abréviation) ne serait pas retrouvé.");
        sb.append("\n\n");
    }

    /** Mêmes termes dans les autres modules : évite qu'un « rien dans TT » soit pris pour « rien dans T24 ». */
    private void appendElsewhere(StringBuilder sb, Scope scope, List<String> variants) {
        String excludedModule = scope.module();
        Map<String, HelpField> uniqueMatches = new LinkedHashMap<>();
        for (String variant : variants) {
            for (HelpField f : helpFieldRepository.searchFieldNamesAnywhere(sanitize(variant))) {
                boolean outsideScope = excludedModule == null || f.getProduct() == null || !f.getProduct().equalsIgnoreCase(excludedModule);
                if (outsideScope && variants.stream().anyMatch(v -> mentions(f.getFieldName(), v))) {
                    uniqueMatches.putIfAbsent(identityOf(f), f);
                }
            }
        }
        // Noms exacts d'abord, puis approchants ; à égalité, ordre alphabétique module/table/champ.
        List<HelpField> nameMatches = uniqueMatches.values().stream()
                .sorted(Comparator
                        .comparing((HelpField f) -> variants.stream().anyMatch(v -> v.equalsIgnoreCase(f.getFieldName())) ? 0 : 1)
                        .thenComparing(f -> String.valueOf(f.getProduct()))
                        .thenComparing(f -> String.valueOf(f.getTableName()))
                        .thenComparing(f -> String.valueOf(f.getFieldName())))
                .toList();
        if (nameMatches.isEmpty()) {
            return;
        }
        Map<String, Long> perModule = nameMatches.stream()
                .collect(Collectors.groupingBy(f -> String.valueOf(f.getProduct()), LinkedHashMap::new, Collectors.counting()));
        sb.append("**Hors périmètre : champs dont le nom correspond dans d'autres modules**\n\n");
        sb.append("Modules concernés : ")
                .append(perModule.entrySet().stream().limit(10).map(e -> "`" + e.getKey() + "` (" + e.getValue() + ")")
                        .collect(Collectors.joining(", ")))
                .append(".\n\n");
        Set<Long> seen = new LinkedHashSet<>();
        int shown = 0;
        for (HelpField f : nameMatches) {
            if (shown >= MAX_ELSEWHERE_HITS) {
                break;
            }
            if (seen.add(f.getId())) {
                sb.append("- [").append(f.getProduct()).append("] `").append(f.getTableName()).append("` › `")
                        .append(f.getFieldName()).append("`\n");
                shown++;
            }
        }
        sb.append('\n');
    }

    private static String describeScope(Scope scope) {
        return switch (scope.kind()) {
            case TABLE -> "de la table `" + scope.table() + "`";
            case MODULE -> "du module `" + scope.module() + "`";
            case ALL -> "de la documentation";
        };
    }

    /** Retire jokers LIKE, antislash et apostrophes : le terme provient d'une saisie libre (il est de toute façon lié en paramètre). */
    private static String sanitize(String term) {
        return term.replaceAll("[%_\\\\']", "").trim();
    }
}
