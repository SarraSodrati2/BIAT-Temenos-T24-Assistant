package backend.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Réponses sur un champ décidées AVANT toute génération, quand le Contexte récupéré suffit à trancher (demande explicite
 * du 2026-10-06 : « si l'ambiguïté peut être déterminée avant l'appel LLM, la traiter avant la génération ») :
 * <ul>
 *   <li><b>champ présent dans plusieurs tables</b> sans que la question précise laquelle : la réponse liste les tables
 *   réelles et demande laquelle — Llama3 en choisissait une arbitrairement (constaté sur DATE.TO.USE, DELIVERY.REF) ;</li>
 *   <li><b>champ sans aide</b> (« Help Text for this field is unavailable... » ou description non renseignée) : la réponse
 *   le dit, avec la source et l'extrait réels — Llama3 déduisait sinon une fonction du seul nom du champ (constaté sur
 *   MATURITY.DATE de BILL.REGISTER : « représente la date de maturité d'un produit »).</li>
 * </ul>
 * Tout le contenu vient du Contexte (noms de tables, extrait) : rien n'est écrit en dur pour un champ ou une table.
 */
final class DeterministicFieldAnswers {

    /** En-tête du contexte d'ambiguïté (voir RagServiceImpl.buildAmbiguousFieldContext). */
    private static final Pattern AMBIGUOUS_HEADER = Pattern.compile("(?m)^Le champ (\\S+) existe dans plusieurs tables [^:\\n]*: (.+)\\.$");
    private static final String AMBIGUOUS_MARKER = "Ambigu :";
    private static final Pattern COMMON_DESCRIPTION =
            Pattern.compile("(?m)^" + Pattern.quote(RagServiceImpl.COMMON_DESCRIPTION_LABEL) + "(.+)$");
    private static final int MAX_TABLES_LISTED = 15;

    private static final Pattern SELECTED_FIELD = Pattern.compile("(?m)^Champ sélectionné\\s*:\\s*(\\S+)\\s*$");
    private static final Pattern TABLE_LINE = Pattern.compile("(?m)^Table\\s*:\\s*(\\S+)\\s*$");
    private static final Pattern FIELD_ATTRIBUTE = Pattern.compile("(?m)^(Type|Input) :\\n(.+)$");
    /** Phrase standard de T24 quand l'aide d'un champ n'existe pas (avec ou sans ponctuation finale). */
    private static final Pattern HELP_TEXT_UNAVAILABLE = Pattern.compile(
            "(?i)help text for this field is unavailable\\.?\\s*(?:please refer to the t24 user guides for further information\\.?)?");
    static final String NO_DESCRIPTION_FUNCTION = "La documentation HelpText disponible ne fournit pas de description pour ce champ.";

    private DeterministicFieldAnswers() {
    }

    /** Le Contexte signale un champ présent dans plusieurs tables : demande de précision, sans source unique. */
    static Optional<String> ambiguousField(String context) {
        Matcher header = AMBIGUOUS_HEADER.matcher(context);
        if (!context.contains(AMBIGUOUS_MARKER) || !header.find()) {
            return Optional.empty();
        }
        List<String> tables = Arrays.stream(header.group(2).split(",\\s*")).map(String::trim).filter(t -> !t.isEmpty()).toList();
        StringBuilder sb = new StringBuilder("**Champ :** `").append(header.group(1)).append("`\n\n")
                .append("Ce champ est documenté dans plusieurs tables :\n");
        tables.stream().limit(MAX_TABLES_LISTED).forEach(table -> sb.append("- `").append(table).append("`\n"));
        if (tables.size() > MAX_TABLES_LISTED) {
            sb.append("- … et ").append(tables.size() - MAX_TABLES_LISTED).append(" autre(s) table(s)\n");
        }
        Matcher common = COMMON_DESCRIPTION.matcher(context);
        if (common.find()) {
            sb.append("\nDescription identique dans toutes ces tables :\n> « ").append(common.group(1).trim()).append(" »\n");
        }
        sb.append("\nPouvez-vous préciser la table recherchée ?");
        return Optional.of(sb.toString());
    }

    /** Le champ sélectionné n'a aucune description d'aide : réponse honnête, jamais une fonction déduite de son nom. */
    static Optional<String> undocumentedField(String context) {
        if (context.contains(AMBIGUOUS_MARKER)) {
            return Optional.empty();
        }
        Matcher field = SELECTED_FIELD.matcher(context);
        Matcher table = TABLE_LINE.matcher(context);
        if (!field.find() || !table.find()) {
            return Optional.empty();
        }
        Optional<String> description = DocumentedFieldFacts.selectedFieldDescription(context).map(String::trim);
        if (description.isEmpty()) {
            return Optional.empty();
        }
        String text = description.get();
        boolean notFilled = text.isEmpty() || text.startsWith("Non renseignée");
        Matcher unavailable = HELP_TEXT_UNAVAILABLE.matcher(text);
        // Seule la phrase standard (rien d'autre, ex. des règles de validation) : sinon le champ est partiellement documenté.
        boolean helpUnavailable = unavailable.lookingAt() && text.substring(unavailable.end()).isBlank();
        if (!notFilled && !helpUnavailable) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder("**Champ :** `").append(field.group(1)).append("`\n\n")
                .append("**Fonction :** ").append(NO_DESCRIPTION_FUNCTION).append("\n\n");
        String attributes = documentedAttributes(context.substring(field.end()));
        if (!attributes.isEmpty()) {
            sb.append("**Éléments documentés :** ").append(attributes).append("\n\n");
        }
        sb.append("**Source :** `").append(table.group(1)).append("`\n\n");
        sb.append(helpUnavailable
                ? "**Extrait documentaire :**\n> « " + text + " »"
                : "**Extrait documentaire :** aucun texte d'aide n'est renseigné pour ce champ.");
        return Optional.of(sb.toString());
    }

    /** Type et saisie du champ quand ils sont réellement renseignés (recopiés tels quels). */
    private static String documentedAttributes(String fieldBlock) {
        StringBuilder sb = new StringBuilder();
        Matcher attribute = FIELD_ATTRIBUTE.matcher(fieldBlock);
        while (attribute.find()) {
            String value = attribute.group(2).trim();
            if (value.isEmpty() || value.startsWith("Non renseign")) {
                continue;
            }
            sb.append(sb.isEmpty() ? "" : " ; ").append("Type".equals(attribute.group(1)) ? "type : " : "saisie : ").append(value);
        }
        return sb.toString();
    }
}
