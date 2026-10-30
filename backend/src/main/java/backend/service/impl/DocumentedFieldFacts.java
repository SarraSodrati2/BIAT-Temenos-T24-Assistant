package backend.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faits documentés, repérés de façon déterministe dans la Description du champ sélectionné (bloc
 * "Champ sélectionné : X" du Contexte, voir {@code RagServiceImpl.extractFieldOnly}) : champs liés, formats,
 * règles de validation, exemples. Rien n'est déduit : chaque fait est un fragment recopié du texte source.
 *
 * <p>Cause réelle (2026-10-03) : le bon champ et le bon extrait étaient retrouvés, mais Llama3 résumait le passage
 * en une phrase générique — pour TRANSACTION.TYPE (LC.TXN.TYPE.CONDITION), la construction de la clé, son usage
 * pour retrouver les charges par défaut et les formats {@code [LC.TYPE]'-'[OPERATION]} / {@code [LC.TYPE]'-'[DRAWING.TYPE]}
 * disparaissaient. Après correction du prompt, Llama3 omettait encore systématiquement règles de validation, formats
 * et champs liés (mesuré sur CCY.DENOMINATION, SCH.TYPE, FORM.WIDTH, DATE.SELECTED) : ces faits sont donc listés
 * dans le prompt, puis réintégrés tels quels dans la réponse s'ils y manquent encore.
 */
final class DocumentedFieldFacts {

    private static final Pattern SELECTED_FIELD = Pattern.compile("(?m)^Champ sélectionné\\s*:\\s*(\\S+)\\s*$");
    private static final Pattern TABLE_LINE = Pattern.compile("(?m)^Table\\s*:\\s*(\\S+)\\s*$");
    private static final Pattern DESCRIPTION = Pattern.compile("(?s)Description :\\n(.*?)(?=\\n\\n(?:Type|Input) :|\\z)");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+\\b");
    /** Composition entre crochets : [LC.TYPE]'-'[OPERATION], [A]-[B]... */
    private static final Pattern BRACKET_FORMAT = Pattern.compile("\\[[^\\[\\]\\n]{1,40}](?:\\s*'?[-./_ ]'?\\s*\\[[^\\[\\]\\n]{1,40}])+");
    private static final Pattern VALIDATION_RULES = Pattern.compile("(?is)Validation Rules\\s*:+\\s*(.+)$");
    /** Déclencheur insensible à la casse, mais exemple capturé en MAJUSCULES seulement (un code, jamais "the"). */
    private static final Pattern DOCUMENTED_EXAMPLE = Pattern.compile(
            "\\b(?:[Ee]\\.[Gg]\\.?|[Ee][Gg]|[Ff]or example|[Ss]uch as)\\s*[,:]?\\s*'?([A-Z0-9][A-Z0-9.\\-]{1,30})\\b'?");
    /** Jetons distinctifs d'une règle (nombres, codes, gabarits) qui doivent se retrouver dans une réponse qui la couvre. */
    private static final Pattern RULE_TOKEN = Pattern.compile("\\b\\d+(?:-\\d+)?\\b|\\b[A-Z]{2,}[a-z]{2,}\\b|\\b[A-Z]{3,}\\b");
    private static final int MAX_RULES_CHARS = 400;
    /**
     * Énumération « CODE – comportement » (tiret long ou court entouré d'espaces), ex. UPDATE.TYPE (EB,
     * SEAT.APPLICATION.MANAGER) : "ABSOLUTE – Gets the Value in the Field as it is INCREMENT – Compares the BEFORE and
     * AFTER image and updates the difference. LOCATE – This option is used for MULTIVALUE fields". La description
     * s'arrête au code suivant de l'énumération (le texte source n'a pas toujours de point entre deux valeurs).
     */
    private static final Pattern VALUE_ITEM = Pattern.compile(
            "(?<![A-Za-z0-9.'’])([A-Z][A-Z0-9._]{1,40})\\s+[–—-]\\s+(.+?)(?=\\s+[A-Z][A-Z0-9._]{1,40}\\s+[–—-]\\s+|$)");
    /** Découpe une réponse en clauses (fin de phrase, point-virgule, retour à la ligne, virgule) — jamais au point
     * interne d'un code à points comme NO.UPDATE. */
    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile("[;\\n]|\\.(?=\\s|$)|,\\s");
    private static final int MIN_EXPLANATION_WORDS = 3;

    private final String field;
    private final List<String> relatedFields;
    private final List<String> formats;
    private final String validationRules;
    private final List<String> examples;
    private final List<DocumentedValue> values;

    /** Une valeur documentée du champ et son comportement, recopiés du texte source. */
    record DocumentedValue(String code, String behaviour) {
    }

    private DocumentedFieldFacts(String field, List<String> relatedFields, List<String> formats, String validationRules,
                                 List<String> examples, List<DocumentedValue> values) {
        this.field = field;
        this.relatedFields = relatedFields;
        this.formats = formats;
        this.validationRules = validationRules;
        this.examples = examples;
        this.values = values;
    }

    /** Faits du champ sélectionné, ou vide si le Contexte ne cible pas un champ précis ou ne contient aucun fait. */
    static Optional<DocumentedFieldFacts> fromContext(String context) {
        Matcher selected = SELECTED_FIELD.matcher(context);
        if (!selected.find()) {
            return Optional.empty();
        }
        String field = selected.group(1);
        Matcher description = DESCRIPTION.matcher(context.substring(selected.end()));
        if (!description.find()) {
            return Optional.empty();
        }
        String text = description.group(1).replaceAll("\\s+", " ").trim();
        Matcher table = TABLE_LINE.matcher(context);
        String tableName = table.find() ? table.group(1) : "";

        String rules = null;
        String prose = text;
        Matcher rulesMatcher = VALIDATION_RULES.matcher(text);
        if (rulesMatcher.find()) {
            rules = rulesMatcher.group(1).trim();
            prose = text.substring(0, rulesMatcher.start()).trim();
            if (rules.length() > MAX_RULES_CHARS) {
                rules = rules.substring(0, MAX_RULES_CHARS).trim() + "…";
            }
        }

        // Identifiants de la partie descriptive seulement : dans les règles de validation, les noms à points sont
        // souvent des VALEURS (ex. PERIODIC.AUTOMATIC pour SCH.TYPE), pas des champs liés ; les règles sont traitées en bloc.
        Set<String> related = new LinkedHashSet<>();
        Matcher ids = IDENTIFIER.matcher(prose);
        while (ids.find()) {
            String id = ids.group();
            if (!id.equalsIgnoreCase(field) && !id.equalsIgnoreCase(tableName)) {
                related.add(id);
            }
        }
        Set<String> formats = new LinkedHashSet<>();
        Matcher formatMatcher = BRACKET_FORMAT.matcher(text);
        while (formatMatcher.find()) {
            formats.add(formatMatcher.group().trim());
        }
        Set<String> examples = new LinkedHashSet<>();
        Matcher exampleMatcher = DOCUMENTED_EXAMPLE.matcher(prose);
        while (exampleMatcher.find()) {
            examples.add(exampleMatcher.group(1));
        }

        List<DocumentedValue> values = new ArrayList<>();
        Matcher valueMatcher = VALUE_ITEM.matcher(prose);
        while (valueMatcher.find()) {
            String code = valueMatcher.group(1);
            if (!code.equalsIgnoreCase(field) && values.stream().noneMatch(v -> v.code().equals(code))) {
                values.add(new DocumentedValue(code, valueMatcher.group(2).trim().replaceAll("[.\\s]+$", "")));
            }
        }
        // Une seule occurrence « X – ... » n'est pas une énumération de valeurs (ex. un tiret dans une phrase).
        if (values.size() < 2) {
            values.clear();
        }
        related.removeIf(id -> values.stream().anyMatch(v -> v.code().equals(id)));

        DocumentedFieldFacts facts = new DocumentedFieldFacts(field, List.copyOf(related), List.copyOf(formats), rules,
                List.copyOf(examples), List.copyOf(values));
        return facts.isEmpty() ? Optional.empty() : Optional.of(facts);
    }

    boolean isEmpty() {
        return relatedFields.isEmpty() && formats.isEmpty() && validationRules == null && examples.isEmpty() && values.isEmpty();
    }

    /** Liste ajoutée au prompt utilisateur : les points documentés que la réponse doit couvrir s'ils sont pertinents. */
    String asPromptChecklist() {
        StringBuilder sb = new StringBuilder("Points documentés dans la Description du champ ").append(field)
                .append(" (recopiés du Contexte ; intègre-les à la réponse lorsqu'ils répondent à la question) :\n");
        if (!values.isEmpty()) {
            sb.append("- valeurs documentées et leur comportement (à expliquer dans la Fonction, ce ne sont pas des exemples) : ");
            sb.append(String.join(" ; ", values.stream().map(v -> v.code() + " = " + v.behaviour()).toList())).append('\n');
        }
        if (!relatedFields.isEmpty()) {
            sb.append("- identifiants cités : ").append(String.join(", ", relatedFields)).append('\n');
        }
        if (!formats.isEmpty()) {
            sb.append("- formats (utilisables comme exemple) : ").append(String.join(" ; ", formats)).append('\n');
        }
        if (validationRules != null) {
            sb.append("- règles de validation : ").append(validationRules).append('\n');
        }
        if (!examples.isEmpty()) {
            sb.append("- exemples cités par la documentation : ").append(String.join(", ", examples)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Exemples DOCUMENTÉS (formats concrets, exemples introduits par "e.g."/"for example") encore absents de
     * {@code answerBody}, prêts à figurer dans la section Exemple — recopiés, jamais reformulés.
     */
    List<String> missingExamples(String answerBody) {
        String normalized = normalize(answerBody);
        List<String> lines = new ArrayList<>();
        formats.stream().filter(f -> !normalized.contains(normalize(f))).forEach(f -> lines.add("`" + f + "`"));
        examples.stream().filter(e -> !normalized.contains(normalize(e))).forEach(e -> lines.add("`" + e + "` (exemple cité par la documentation)"));
        return lines;
    }

    /**
     * Autres faits documentés encore absents de {@code answerBody} (valeurs et leur comportement, identifiants cités,
     * règles de validation), sous forme de lignes prêtes à afficher, recopiées du texte source.
     *
     * @param question pour une question portant sur une valeur précise ("Que fait X = INCREMENT ?"), seule cette valeur
     * est exigée ; sinon toutes les valeurs documentées doivent être expliquées.
     */
    List<String> missingFacts(String answerBody, String question) {
        String normalized = normalize(answerBody);
        List<String> lines = new ArrayList<>();
        List<DocumentedValue> required = valuesAskedIn(question);
        if (required.stream().anyMatch(v -> !explained(v.code(), answerBody))) {
            lines.add("Valeurs documentées (texte d'origine) : " + String.join(" ; ",
                    required.stream().map(v -> "`" + v.code() + "` – " + v.behaviour()).toList()));
        }
        List<String> missingFields = relatedFields.stream()
                .filter(f -> !normalized.contains(normalize(f)))
                .filter(f -> formats.stream().noneMatch(format -> format.contains(f)))
                .toList();
        if (!missingFields.isEmpty()) {
            lines.add("Identifiants cités : " + String.join(", ", missingFields.stream().map(f -> "`" + f + "`").toList()));
        }
        if (validationRules != null && !rulesCovered(normalized)) {
            lines.add("Règles de validation (texte d'origine) : « " + validationRules + " »");
        }
        return lines;
    }

    private List<DocumentedValue> valuesAskedIn(String question) {
        List<DocumentedValue> asked = values.stream()
                .filter(v -> Pattern.compile("(?<![A-Z0-9._])" + Pattern.quote(v.code()) + "(?![A-Z0-9._])").matcher(question).find())
                .toList();
        return asked.isEmpty() ? values : asked;
    }

    /**
     * Une valeur est expliquée si une clause de la réponse la cite SEULE (aucune autre valeur documentée) avec au moins
     * {@link #MIN_EXPLANATION_WORDS} autres mots — une simple énumération « 'ABSOLUTE', 'INCREMENT', 'LOCATE' » ne
     * l'explique pas.
     */
    private boolean explained(String code, String answerBody) {
        // Deux granularités : la phrase (« Avec INCREMENT, le système compare... » explique INCREMENT après la virgule),
        // puis la clause (« 'ABSOLUTE' récupère..., 'INCREMENT' met à jour... » : plusieurs valeurs dans une même phrase).
        return explainedIn(code, SENTENCE_SEPARATOR.split(answerBody)) || explainedIn(code, CLAUSE_SEPARATOR.split(answerBody));
    }

    private static final Pattern SENTENCE_SEPARATOR = Pattern.compile("[;\\n]|\\.(?=\\s|$)");

    private boolean explainedIn(String code, String[] clauses) {
        Pattern own = Pattern.compile("(?<![A-Z0-9._])" + Pattern.quote(code) + "(?![A-Z0-9._])");
        for (String clause : clauses) {
            if (!own.matcher(clause).find()) {
                continue;
            }
            boolean otherValue = values.stream().filter(v -> !v.code().equals(code))
                    .anyMatch(v -> Pattern.compile("(?<![A-Z0-9._])" + Pattern.quote(v.code()) + "(?![A-Z0-9._])").matcher(clause).find());
            long words = List.of(own.matcher(clause).replaceAll(" ").trim().split("[^\\p{L}\\p{N}]+")).stream()
                    .filter(w -> !w.isBlank()).count();
            if (!otherValue && words >= MIN_EXPLANATION_WORDS) {
                return true;
            }
        }
        return false;
    }

    List<DocumentedValue> values() {
        return values;
    }

    /** Exemples documentés recopiés tels quels (formats concrets, exemples introduits par "e.g."...), au format d'une ligne. */
    List<String> documentedExamples() {
        List<String> lines = new ArrayList<>();
        formats.forEach(f -> lines.add("`" + f + "`"));
        examples.forEach(e -> lines.add("`" + e + "` (exemple cité par la documentation)"));
        return lines;
    }

    /** Description (texte brut) du champ sélectionné dans le Contexte, si le Contexte cible un champ précis. */
    static Optional<String> selectedFieldDescription(String context) {
        Matcher selected = SELECTED_FIELD.matcher(context);
        if (!selected.find()) {
            return Optional.empty();
        }
        Matcher description = DESCRIPTION.matcher(context.substring(selected.end()));
        return description.find() ? Optional.of(description.group(1)) : Optional.empty();
    }

    /** Une règle est couverte si tous ses jetons distinctifs (nombres, codes, gabarits) figurent dans la réponse ;
     * une règle sans jeton distinctif (ex. "Internal field.") n'est jamais réintégrée d'office. */
    private boolean rulesCovered(String normalizedAnswer) {
        Matcher tokens = RULE_TOKEN.matcher(validationRules);
        while (tokens.find()) {
            if (!normalizedAnswer.contains(normalize(tokens.group()))) {
                return false;
            }
        }
        return true;
    }

    String field() {
        return field;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace("`", "").replaceAll("[«»\"“”]", "").replaceAll("\\s+", " ");
    }
}
