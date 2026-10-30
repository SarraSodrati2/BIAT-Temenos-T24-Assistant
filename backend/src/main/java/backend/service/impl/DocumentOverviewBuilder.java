package backend.service.impl;

import backend.dto.ChatContext;
import backend.repository.HelpFieldRepository;
import backend.service.VectorStoreService;
import backend.utils.TableKeywordExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fiches structurées pour les questions générales sur une TABLE (« décris la table X », « quels
 * champs contient X ? ») ou sur un MODULE (« à quoi sert le module MD ? », « donne-moi les tables du
 * module MD »).
 *
 * <p>Table, liste des tables d'un module : entièrement déterministe (aucun appel Llama3), tout le
 * contenu est lu tel quel dans l'index documentaire. {@link #tableSheet} répond en
 * Table/Rôle/Informations principales/Exemple/Source-Extrait documentaire ; {@link #moduleTableList}
 * reste la liste complète des tables (demande explicite uniquement).
 *
 * <p>Rôle général d'un module (« à quoi sert... ») : {@link #modulePurposeAnswer} répond en
 * Module/Rôle/Exemple/Source-Extrait documentaire, jamais en énumérant chaque table comme si elle
 * était un module à part entière — les tables ne sont que les sources de la synthèse, citées en
 * `code inline` dans la prose quand c'est naturel, et la table dont l'Extrait est réellement tiré est
 * toujours nommée dans la ligne "Source". Llama3 n'est utilisé que pour la seule tâche où il est
 * réellement nécessaire — rédiger cette synthèse dans la langue de la question à partir d'extraits
 * anglais réels, sans lui laisser choisir les tables ni la structure de la réponse. Sa réponse est
 * vérifiée après coup : un identifiant absent des extraits fournis, une phrase visiblement coupée, une
 * synthèse évoquant un concept (garantie/commission/provision/marge) non étayé, ou un Extrait qui ne se
 * retrouve pas mot pour mot dans les extraits transmis sont tous rejetés — voir {@link
 * #sanitizeRole}/{@link #sanitizeExample}/{@link #sanitizeQuote} — et remplacés par un repli sûr basé
 * uniquement sur les extraits réels, jamais par une réponse tronquée, inventée ou par un aveu
 * d'échec quand des informations utiles existent pourtant.
 */
@Slf4j
@Component
class DocumentOverviewBuilder {

    private static final String FIELDS_HEADER = "Champs (";
    private static final String FIELD_SEPARATOR = "\n\n----------------------------------\n\n";
    private static final String NO_OVERVIEW_MARKER = "Non renseignée";
    /** Demande portant explicitement sur les tables elles-mêmes (« donne-moi les tables du module... »). */
    private static final int MAX_TABLES_LISTED = 60;
    private static final int FIELD_DESCRIPTION_CHARS = 170;
    private static final int TABLE_DESCRIPTION_CHARS = 230;
    /** Nombre de tables (les plus renseignées) dont l'extrait sert de matière à la synthèse de rôle. */
    private static final int MAX_SOURCE_TABLES = 5;

    /** Verbes/formulations d'une demande de vue d'ensemble (français + anglais). */
    private static final Pattern OVERVIEW_INTENT = Pattern.compile(
            "(?iu)\\b(?:à\\s+quoi\\s+sert|a\\s+quoi\\s+sert|décri\\w*|decri\\w*|présent\\w*|present\\w*|résum\\w*|resum\\w*|explique\\w*|"
                    + "que\\s+(?:contient|fait|couvre|comprend)|qu'est-ce\\s+que|c'est\\s+quoi|quel(?:le)?s?\\s+(?:sont\\s+)?(?:les\\s+)?(?:tables?|champs)|"
                    + "(?:donne|montre|affiche|liste)\\w*(?:-moi)?\\s+(?:les\\s+|la\\s+liste\\s+(?:des\\s+)?)?(?:tables?|champs)|"
                    + "vue\\s+d'ensemble|contenu|overview|what\\s+(?:is|does)|describe|show\\s+(?:me\\s+)?(?:the\\s+)?(?:tables|fields)|list\\s+(?:the\\s+)?(?:tables|fields))\\b");
    private static final Pattern TABLE_WORD = Pattern.compile("(?iu)\\btables?\\b");
    private static final Pattern MODULE_WORD = Pattern.compile("(?iu)\\bmodules?\\b");
    private static final Pattern MODULE_CODE = Pattern.compile("\\bmodules?\\s+([A-Z]{2,6})\\b");
    /** « le champ DEAL.SUB.TYP » : question sur UN champ précis, pas sur une vue d'ensemble. */
    private static final Pattern SPECIFIC_FIELD = Pattern.compile("(?u)\\b(?:champ|field|colonne|column)\\s+[A-Za-z@][A-Za-z0-9._@-]{2,}");
    /** Question sur le RÔLE du module (« à quoi sert... », « décris le module... »), par opposition à
     * une demande portant explicitement sur ses tables (« donne-moi les tables... ») — voir {@link #tryModuleOverview}. */
    private static final Pattern MODULE_PURPOSE_WORDS = Pattern.compile(
            "(?iu)\\b(?:à\\s+quoi\\s+sert|a\\s+quoi\\s+sert|que\\s+fait|qu'est-ce\\s+que|c'est\\s+quoi|décri\\w*|decri\\w*|"
                    + "présent\\w*|present\\w*|résum\\w*|resum\\w*|explique\\w*|vue\\s+d'ensemble|overview|what\\s+(?:is|does)|describe)\\b");

    private final VectorStoreService vectorStoreService;
    private final HelpFieldRepository helpFieldRepository;
    private final ChatClient chatClient;

    DocumentOverviewBuilder(VectorStoreService vectorStoreService, HelpFieldRepository helpFieldRepository,
                            ChatClient.Builder chatClientBuilder) {
        this.vectorStoreService = vectorStoreService;
        this.helpFieldRepository = helpFieldRepository;
        this.chatClient = chatClientBuilder.build();
    }

    // --- Table ---------------------------------------------------------------------------------------------

    Optional<String> tryTableOverview(String question) {
        if (!OVERVIEW_INTENT.matcher(question).find() || SPECIFIC_FIELD.matcher(question).find()) {
            return Optional.empty();
        }
        Optional<Document> table = exactTableIn(question);
        if (table.isEmpty()) {
            return Optional.empty();
        }
        // Sans le mot « table », « à quoi sert X ? » reste une question générale (X peut être un champ) :
        // la vue d'ensemble n'est proposée que si la demande vise explicitement une table ou une liste de champs.
        boolean explicitTable = TABLE_WORD.matcher(question).find()
                || Pattern.compile("(?iu)\\bchamps\\b|\\bfields\\b|décri\\w*|decri\\w*|présent\\w*|résum\\w*|describe|overview|vue\\s+d'ensemble")
                        .matcher(question).find();
        // Table SANS description générale (« Non renseignée dans HELP_TEXT », 177 des 527 tables des 7 modules affichés
        // au 2026-10-06) : son rôle n'est pas documenté. Constaté en test réel ce jour-là (LD.COLLATERAL, EB.API.ATTRIBUTE,
        // LC.COLLATERAL, DE.MT942.HIST) : Llama3 présentait alors la table comme « le champ X » en lui prêtant la
        // description de son premier champ, ou devinait un rôle (« contrat de prêt », « virement »). La fiche déterministe
        // dit honnêtement que le rôle n'est pas décrit et recopie ce que la documentation dit de ses champs.
        boolean undocumentedRole = !hasRealOverview(table.get().getText());
        if (!explicitTable && !undocumentedRole) {
            return Optional.empty();
        }
        log.info("Vue d'ensemble de table détectée ({}{}) : fiche construite depuis l'index documentaire, Llama3 n'est pas appelé.",
                table.get().getMetadata().get("tableName"), undocumentedRole ? ", sans description générale" : "");
        return Optional.of(tableSheet(table.get()));
    }

    /** Première table de la question qui existe réellement dans l'index (jamais un nom deviné). */
    private Optional<Document> exactTableIn(String question) {
        for (String candidate : TableKeywordExtractor.extractCandidates(question)) {
            Optional<Document> doc = vectorStoreService.findByExactTableName(candidate);
            if (doc.isPresent()) {
                return doc;
            }
        }
        return Optional.empty();
    }

    /** Nombre de champs cités par leur nom dans « Informations principales » — un aperçu, jamais
     * l'inventaire complet (réservé à une demande explicite, voir {@code ChatbotServiceImpl.buildExhaustiveDump}). */
    private static final int MAIN_INFO_PREVIEW_COUNT = 6;

    /**
     * Fiche de table : Table/Rôle/Informations principales/Exemple/Source-Extrait documentaire,
     * entièrement déterministe (aucun appel Llama3, tout le contenu vient tel quel de l'index).
     */
    private String tableSheet(Document document) {
        String table = String.valueOf(document.getMetadata().get("tableName"));
        String text = document.getText();
        StringBuilder sb = new StringBuilder();

        sb.append("**Table :** `").append(table).append("`\n\n");

        String overview = section(text, "Description générale :\n");
        boolean hasOverview = !overview.isBlank() && !overview.startsWith(NO_OVERVIEW_MARKER);
        String flatOverview = hasOverview ? flat(overview) : null;
        Object product = document.getMetadata().get("product");
        sb.append("**Rôle :** ").append(hasOverview
                ? firstSentences(flatOverview, TABLE_DESCRIPTION_CHARS)
                : "la documentation T24 indexée ne fournit pas de description générale pour cette table"
                        + (product != null ? " (module " + product + ")" : "") + " : son rôle n'y est pas décrit.").append("\n\n");

        List<FieldEntry> fields = fieldsOf(text);
        if (!hasOverview && !fields.isEmpty()) {
            appendWhatTheFieldsSay(sb, fields);
        } else if (fields.isEmpty()) {
            sb.append("**Informations principales :** aucun champ n'est documenté pour cette table.");
        } else {
            sb.append("**Informations principales :** ");
            String preview = fields.stream().limit(MAIN_INFO_PREVIEW_COUNT)
                    .map(f -> "`" + f.name() + "`")
                    .collect(Collectors.joining(", "));
            sb.append(preview);
            sb.append(fields.size() > MAIN_INFO_PREVIEW_COUNT
                    ? ", … (" + fields.size() + " champs documentés au total)"
                    : " (" + fields.size() + (fields.size() > 1 ? " champs documentés)" : " champ documenté)"));
        }
        sb.append("\n\n");

        // Section Exemple conditionnelle (2026-10-05) : affichée seulement si un exemple est réellement documenté.
        findDeterministicExample(text).ifPresent(example -> sb.append("**Exemple :** ").append(example).append("\n\n"));

        sb.append("**Source / Extrait documentaire :** `").append(table).append("`\n");
        sb.append(hasOverview ? "> « " + flatOverview + " »" : NO_EXTRACT_FALLBACK);
        return sb.toString();
    }

    /** Nombre maximal de champs dont la description est recopiée quand la table n'a pas de description générale. */
    private static final int UNDOCUMENTED_TABLE_FIELDS_SHOWN = 8;
    private static final Pattern HELP_TEXT_UNAVAILABLE = Pattern.compile("(?i)^help text for this field is unavailable");

    /**
     * Table sans description générale : seule matière documentée = ses champs. Leurs descriptions sont recopiées telles
     * quelles (anglais d'origine, aucune reformulation) ; les champs sans aide (« Help Text ... unavailable ») sont
     * seulement nommés, pour ne jamais présenter cette mention comme une information.
     */
    private static void appendWhatTheFieldsSay(StringBuilder sb, List<FieldEntry> fields) {
        List<FieldEntry> described = fields.stream()
                .filter(f -> !f.description().isEmpty() && !HELP_TEXT_UNAVAILABLE.matcher(f.description()).find())
                .toList();
        List<String> withoutHelp = fields.stream().filter(f -> !described.contains(f)).map(f -> "`" + f.name() + "`").toList();

        sb.append("**Ce que la documentation dit de ses champs** (").append(fields.size())
                .append(fields.size() > 1 ? " champs" : " champ").append(") :");
        described.stream().limit(UNDOCUMENTED_TABLE_FIELDS_SHOWN)
                .forEach(f -> sb.append("\n- `").append(f.name()).append("` : « ")
                        .append(firstSentences(f.description(), UNDOCUMENTED_FIELD_DESCRIPTION_CHARS)).append(" »"));
        if (described.size() > UNDOCUMENTED_TABLE_FIELDS_SHOWN) {
            sb.append("\n- … et ").append(described.size() - UNDOCUMENTED_TABLE_FIELDS_SHOWN).append(" autre(s) champ(s) décrit(s)");
        }
        if (!withoutHelp.isEmpty()) {
            sb.append(described.isEmpty() ? "\n- aucun champ n'a de description d'aide : " : "\n- sans description d'aide : ")
                    .append(String.join(", ", withoutHelp.subList(0, Math.min(withoutHelp.size(), MAX_UNHELPED_NAMES))))
                    .append(withoutHelp.size() > MAX_UNHELPED_NAMES ? ", … (" + withoutHelp.size() + " au total)" : "");
        }
    }

    private static final int MAX_UNHELPED_NAMES = 12;
    /** Règles de validation incluses : pour une table sans description, « Must be the key to a valid entry on the X
     * file » est souvent la seule indication de son rôle (cas réel : LD.COLLATERAL). */
    private static final int UNDOCUMENTED_FIELD_DESCRIPTION_CHARS = 260;

    /** Structure "where CODE is/corresponds to MEANING" — la seule assez régulière pour extraire
     * fiablement un exemple concret sans jamais en deviner ni en altérer les valeurs (même principe
     * que {@code ChatbotServiceImpl.EXAMPLE_FACT}, dupliqué ici pour garder cette classe autonome). */
    private static final Pattern TABLE_EXAMPLE_FACT = Pattern.compile(
            "(?i)where\\s+([A-Z][\\w.]*)\\s+(?:is|corresponds?\\s+to)\\s+(?:an?\\s+|the\\s+)?([^.\\n]+)");

    private static Optional<String> findDeterministicExample(String text) {
        Matcher m = TABLE_EXAMPLE_FACT.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of("`" + m.group(1) + "` correspond à **" + m.group(2).trim() + "**.");
    }

    // --- Module --------------------------------------------------------------------------------------------

    Optional<String> tryModuleOverview(String question, ChatContext chatContext) {
        if (!OVERVIEW_INTENT.matcher(question).find() || !MODULE_WORD.matcher(question).find() || SPECIFIC_FIELD.matcher(question).find()) {
            return Optional.empty();
        }
        Optional<String> module = moduleIn(question, chatContext);
        if (module.isEmpty()) {
            return Optional.empty();
        }
        List<Document> documents = vectorStoreService.findAllByProduct(module.get());
        if (documents.isEmpty()) {
            return Optional.empty();
        }
        boolean purposeQuestion = MODULE_PURPOSE_WORDS.matcher(question).find();
        if (purposeQuestion) {
            log.info("Question de rôle sur le module {} détectée : synthèse courte à partir des tables documentées "
                    + "(sélection déterministe, Llama3 utilisé seulement pour la rédaction).", module.get());
            return Optional.of(modulePurposeAnswer(module.get(), documents, question));
        }
        log.info("Demande de liste des tables du module {} détectée : liste construite depuis l'index documentaire, "
                + "Llama3 n'est pas appelé.", module.get());
        return Optional.of(moduleTableList(module.get(), documents));
    }

    private Optional<String> moduleIn(String question, ChatContext chatContext) {
        Matcher m = MODULE_CODE.matcher(question);
        while (m.find()) {
            String code = m.group(1).toUpperCase(Locale.ROOT);
            if (helpFieldRepository.countByProductIgnoreCase(code) > 0) {
                return Optional.of(code);
            }
        }
        // « ce module », « le module » sans code : le module de la conversation.
        return chatContext.hasModule() ? Optional.of(chatContext.module().toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    private static Map<String, Document> dedupeByTableName(List<Document> documents) {
        Map<String, Document> byTable = new LinkedHashMap<>();
        documents.stream()
                .sorted(Comparator.comparing((Document d) -> String.valueOf(d.getMetadata().get("tableName"))))
                .forEach(d -> byTable.putIfAbsent(String.valueOf(d.getMetadata().get("tableName")).toUpperCase(Locale.ROOT), d));
        return byTable;
    }

    /**
     * Demande explicite portant sur les tables elles-mêmes (« donne-moi les tables du module MD ») :
     * liste complète (jusqu'à {@link #MAX_TABLES_LISTED}), inchangée par rapport à la fiche de rôle —
     * ici l'utilisateur a explicitement demandé l'inventaire, ce n'est pas une question générale.
     */
    private String moduleTableList(String module, List<Document> documents) {
        Map<String, Document> byTable = dedupeByTableName(documents);
        int fieldCount = byTable.values().stream().mapToInt(d -> fieldsOf(d.getText()).size()).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("**Module :** `").append(module).append("`\n\n");
        sb.append("Aucune description officielle du module `").append(module)
                .append("` n'existe dans la documentation importée : seules ses tables sont documentées. ")
                .append("Le module compte ").append(byTable.size()).append(" table(s) et ").append(fieldCount)
                .append(" champ(s) documentés ; voici ces tables avec le début de leur description générale.\n\n");

        sb.append("**Tables du module (").append(byTable.size()).append(")**\n\n");
        int shown = 0;
        for (Document doc : byTable.values()) {
            if (shown++ >= MAX_TABLES_LISTED) {
                break;
            }
            String overview = section(doc.getText(), "Description générale :\n");
            String summary = overview.isBlank() || overview.startsWith(NO_OVERVIEW_MARKER)
                    ? "description générale non renseignée"
                    : firstSentences(flat(overview), TABLE_DESCRIPTION_CHARS);
            int tableFields = fieldsOf(doc.getText()).size();
            sb.append("- `").append(doc.getMetadata().get("tableName")).append("` — ").append(summary)
                    .append(" (").append(tableFields).append(tableFields > 1 ? " champs" : " champ").append(")\n");
        }
        if (byTable.size() > MAX_TABLES_LISTED) {
            sb.append("- … et ").append(byTable.size() - MAX_TABLES_LISTED)
                    .append(" autre(s) table(s) : parcourez le module dans le référentiel (menu Modules).\n");
        } else {
            sb.append("\nPour le détail d'une table, demandez par exemple « Décris la table ")
                    .append(byTable.values().iterator().next().getMetadata().get("tableName")).append(" ».");
        }
        return sb.toString();
    }

    /**
     * Question générale sur le rôle du module (« à quoi sert le module MD ? ») : répond DIRECTEMENT, en
     * 4-5 lignes, jamais en énumérant chaque table comme si elle était un module à part entière — jamais
     * de dump complet des tables/champs (réservé à une demande explicite, voir {@link #moduleTableList}
     * et le listage exhaustif de {@code ChatbotServiceImpl}). Structure fixe, imposée par le code (jamais
     * par Llama3) :
     * <pre>
     * **Module :** `MD`
     * **Rôle :** {synthèse concise, tables sources citées en `code inline` dans la prose}
     * **Exemple :** {exemple court tiré des extraits, ou la phrase de repli}
     * **Source / Extrait documentaire :** `TABLE` (celle dont l'Extrait est réellement tiré)
     * > « {citation exacte} » (ou « non disponible dans les passages récupérés », sans guillemets,
     * si et seulement si aucune table n'a d'extrait exploitable du tout)
     * </pre>
     * Si aucune table n'a de description exploitable, la réponse le dit précisément (jamais de
     * connaissance générale ni de supposition en repli, voir {@link #modulePurposeAnswerWithoutAnyOverview}).
     * Dès qu'au moins une table a une description réelle, un Extrait est TOUJOURS produit (par Llama3 si
     * valide, sinon par repli déterministe sur une phrase réelle de la table la plus renseignée — voir
     * {@link #sanitizeQuote}) : ne jamais dire l'Extrait indisponible quand des extraits exploitables existent.
     */
    private String modulePurposeAnswer(String module, List<Document> documents, String question) {
        Map<String, Document> byTable = dedupeByTableName(documents);
        List<Document> withOverview = byTable.values().stream()
                .filter(d -> hasRealOverview(d.getText()))
                .toList();

        if (withOverview.isEmpty()) {
            return modulePurposeAnswerWithoutAnyOverview(module, byTable.size());
        }

        List<Document> selected = mostRepresentative(withOverview).stream().limit(MAX_SOURCE_TABLES).toList();
        List<TableExtract> extracts = selected.stream()
                .map(d -> new TableExtract(String.valueOf(d.getMetadata().get("tableName")),
                        flat(section(d.getText(), "Description générale :\n"))))
                .toList();
        double cohesion = vectorStoreService.meanPairwiseSimilarity(selected.stream().map(Document::getId).toList());
        boolean dispersed = cohesion < MIN_MODULE_COHESION;
        log.info("Synthèse du module {} : tables sélectionnées {}, cohésion thématique {} ({}).", module,
                extracts.stream().map(TableExtract::table).toList(), String.format(Locale.ROOT, "%.3f", cohesion),
                dispersed ? "module hétérogène : au moins deux tables doivent être citées" : "module homogène");
        ModuleRoleAnswer role = synthesizeModuleRole(module, extracts, question, dispersed);

        StringBuilder sb = new StringBuilder();
        sb.append("**Module :** `").append(module).append("`\n\n");
        sb.append("**Rôle :** ").append(role.role()).append("\n\n");
        if (!NO_EXAMPLE_FALLBACK.equals(role.example())) {
            sb.append("**Exemple :** ").append(role.example()).append("\n\n");
        }
        sb.append("**Source / Extrait documentaire :** `").append(role.sourceTable()).append("`\n");
        sb.append("> ").append(role.quote());
        return sb.toString();
    }

    /**
     * Aucune table du module n'a de description générale exploitable : le dit précisément (nombre de
     * tables concernées) plutôt que de compléter avec des connaissances générales ou une supposition —
     * jamais d'appel Llama3 ici, il n'y a rien de réel à en tirer. Seul cas où l'Extrait est absent.
     */
    private String modulePurposeAnswerWithoutAnyOverview(String module, int tableCount) {
        return "**Module :** `" + module + "`\n\n"
                + "**Rôle :** non précisé dans la documentation fournie : aucune des " + tableCount
                + " tables de ce module n'a de description générale renseignée dans les fichiers XML importés.\n\n"
                + "**Source / Extrait documentaire :** " + NO_EXTRACT_FALLBACK;
    }

    /** Constante de la fusion de rangs (Reciprocal Rank Fusion) de {@link #mostRepresentative}. */
    private static final int RANK_FUSION_K = 10;

    /**
     * Tables les plus représentatives du module, par fusion de deux rangs : longueur de la description
     * générale (table bien décrite) et nombre de champs documentés (application principale du module).
     * Auparavant seule la longueur comptait, ce qui choisissait des tables périphériques mais verbeuses —
     * mesuré sur l'index réel (2026-10-02) : la fusion retient ACCOUNT, LD.LOANS.AND.DEPOSITS,
     * LETTER.OF.CREDIT, TELLER, MD.DEAL en tête de leur module, et pour EB (module transverse sans table
     * principale) un éventail de fonctions différentes (REPGEN.CREATE, ARCHIVE, USER...) au lieu d'un seul thème.
     * Rangs à égalité de valeur partagés : un signal identique pour toutes les tables reste neutre.
     */
    private static List<Document> mostRepresentative(List<Document> documents) {
        Map<Document, Integer> lengthRank = competitionRanks(documents,
                d -> section(d.getText(), "Description générale :\n").length());
        Map<Document, Integer> fieldRank = competitionRanks(documents, DocumentOverviewBuilder::documentedFieldCount);
        return documents.stream()
                .sorted(Comparator.comparingDouble((Document d) ->
                                1.0 / (RANK_FUSION_K + lengthRank.get(d)) + 1.0 / (RANK_FUSION_K + fieldRank.get(d))).reversed()
                        .thenComparing(Comparator.comparingInt((Document d) -> section(d.getText(), "Description générale :\n").length()).reversed())
                        .thenComparing(d -> String.valueOf(d.getMetadata().get("tableName"))))
                .toList();
    }

    /** Rang (0 = plus grande valeur) ; deux valeurs égales partagent le même rang. */
    private static Map<Document, Integer> competitionRanks(List<Document> documents, java.util.function.ToIntFunction<Document> value) {
        List<Document> sorted = documents.stream().sorted(Comparator.comparingInt(value).reversed()).toList();
        Map<Document, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            int rank = i > 0 && value.applyAsInt(sorted.get(i)) == value.applyAsInt(sorted.get(i - 1))
                    ? ranks.get(sorted.get(i - 1)) : i;
            ranks.put(sorted.get(i), rank);
        }
        return ranks;
    }

    private static int documentedFieldCount(Document document) {
        Object count = document.getMetadata().get("fieldCount");
        if (count instanceof Number number) {
            return number.intValue();
        }
        return fieldsOf(document.getText()).size();
    }

    private static boolean hasRealOverview(String documentText) {
        String overview = section(documentText, "Description générale :\n");
        return !overview.isBlank() && !overview.startsWith(NO_OVERVIEW_MARKER);
    }

    // --- Synthèse du rôle d'un module à partir des extraits sélectionnés --------------------------------------

    private record TableExtract(String table, String englishOverview) {
    }

    private record ModuleRoleAnswer(String role, String example, String quote, String sourceTable) {
    }

    private static final String NONE_MARKER = "AUCUN";
    private static final String NO_EXAMPLE_FALLBACK = "Aucun exemple explicite n'est fourni dans la documentation.";
    /** Réservé au seul cas où AUCUNE table du module n'a d'extrait exploitable du tout (voir
     * {@link #modulePurposeAnswerWithoutAnyOverview}) — dès qu'un extrait existe, {@link #sanitizeQuote} garantit
     * toujours une citation réelle (Llama3 ou repli déterministe), jamais ce message. */
    private static final String NO_EXTRACT_FALLBACK = "non disponible dans les passages récupérés.";
    /** Balises reconnues dans la réponse (voir {@link #ROLE_SYSTEM_PROMPT}) — la présence d'EXTRAIT sert aussi à
     * détecter une réponse tronquée (voir {@link #synthesizeModuleRole}). */
    private static final String FIELD_MARKERS_ALTERNATION = "ROLE|EXEMPLE|EXTRAIT";
    /** Capture le contenu d'une balise jusqu'à la balise suivante ou la fin du texte — jamais jusqu'à la fin de la
     * SEULE ligne courante : une réponse étalée sur plusieurs lignes était auparavant silencieusement tronquée à
     * sa première ligne (constaté en conditions réelles, cause exacte d'une réponse finissant en phrase coupée). */
    private static final String NEXT_MARKER_LOOKAHEAD = "(?=\\r?\\n\\s*(?:" + FIELD_MARKERS_ALTERNATION + ")\\s*:|\\z)";
    /** Nom de table/champ T24 potentiel (majuscules à points, ou mot isolé de 3+ majuscules) : sert à détecter
     * qu'une réponse cite un identifiant qui ne faisait pas partie des extraits fournis. */
    private static final Pattern IDENTIFIER_LIKE = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+\\b|\\b[A-Z]{3,}\\b");
    /** Ponctuation de fin de phrase plausible — voir {@link #looksTruncated}. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?»”\"]$");

    /**
     * Concepts bancaires génériques que le Rôle pourrait évoquer sans qu'ils soient réellement étayés par les
     * extraits fournis (risque de généralisation non étayée, voir la demande explicite de vérification). Chaque
     * mot français n'est accepté que si au moins une de ses racines anglaises associées apparaît réellement dans
     * le texte des extraits transmis au modèle.
     */
    private static final Map<String, List<String>> CONCEPT_GROUNDING = Map.ofEntries(
            Map.entry("garantie", List.of("guarantee")),
            Map.entry("garanties", List.of("guarantee")),
            Map.entry("commission", List.of("commission")),
            Map.entry("commissions", List.of("commission")),
            Map.entry("provision", List.of("provision")),
            Map.entry("provisions", List.of("provision")),
            Map.entry("provisionnement", List.of("provision")),
            Map.entry("marge", List.of("margin")),
            Map.entry("marges", List.of("margin")),
            Map.entry("risque", List.of("risk")),
            Map.entry("risques", List.of("risk")),
            Map.entry("conformité", List.of("compliance")),
            Map.entry("réglementaire", List.of("regulat")));

    /**
     * Corrections déterministes de terminologie bancaire T24, appliquées après coup — filet de sécurité en plus
     * des consignes du prompt (constaté en conditions réelles : "off balance sheet" a été rendu "non équilibré"
     * au lieu de "hors bilan", ou laissé tel quel en anglais au milieu d'une phrase française).
     */
    private static final Map<Pattern, String> TERMINOLOGY_FIXES = Map.of(
            Pattern.compile("(?i)\\bdeals?\\s+non[\\s-]?équilibrés?\\b"), "deals hors bilan",
            Pattern.compile("(?i)\\bhors[\\s-]?solde\\b"), "hors bilan",
            Pattern.compile("(?i)\\ben\\s+dehors\\s+du\\s+bilan\\b"), "hors bilan",
            Pattern.compile("(?i)\\bnon[\\s-]?soldé(e|s|es)?\\b"), "hors bilan",
            Pattern.compile("(?i)\\boff[\\s-]?balance[\\s-]?sheet\\b"), "hors bilan");

    private static final String ROLE_SYSTEM_PROMPT =
            """
            Tu es un rédacteur bancaire spécialisé Temenos T24. On te donne le code d'un module, la question
            posée par l'utilisateur, et des extraits ANGLAIS réels de la description générale de quelques-unes
            de ses tables.

            Ta tâche, à partir de CES extraits UNIQUEMENT :
            - Rédige une synthèse concise (2 phrases maximum) du rôle du module, dans la langue de la question
              (français si elle est en français, anglais si elle est en anglais, etc.), en français ou anglais
              bancaire PROFESSIONNEL ET NATUREL — jamais une traduction mot à mot. Utilise la terminologie
              bancaire standard, pas une traduction littérale (ex. "off balance sheet" -> "hors bilan", jamais
              "non équilibré" ni laissé en anglais).
            - Fonde-toi UNIQUEMENT sur le texte de description de chaque extrait, jamais sur le simple NOM d'une
              table ou d'un champ : par exemple, ne déduis jamais qu'une table gère des "échéanciers" seulement
              parce que son nom contient SCHEDULES si ce n'est pas écrit noir sur blanc dans son texte fourni.
            - Cite les tables qui étayent la synthèse par leur nom exact en style code (ex. `MD.DEAL`), intégrées
              naturellement dans la phrase — ne présente JAMAIS une table comme si elle était un module ou une
              entité séparée, et n'écris jamais une liste, une table est juste la source d'un fait.
            - Quand plusieurs extraits sont fournis, la synthèse doit refléter l'ENSEMBLE du module : appuie-toi
              sur au moins deux extraits et cite au moins deux tables. Si les extraits décrivent des fonctions
              différentes, dis que le module regroupe ces fonctions ; ne réduis jamais le module entier à la
              fonction d'une seule table (ex. un module ne "sert pas à l'archivage" parce qu'un extrait parle
              d'archivage).
            - Ne mentionne un concept (garantie, commission, provision, marge, risque, conformité réglementaire...)
              QUE s'il est réellement présent ou clairement induit par au moins un des extraits fournis — jamais
              par généralisation.
            - N'ajoute AUCUNE information absente des extraits donnés. Ne devine rien.
            - Conserve tel quel tout nom de module, de table ou de champ : ne les traduis ni ne les modifie jamais.
            - Si un aspect de la question n'est pas couvert par les extraits alors que d'autres aspects le sont,
              dis en une courte incise ce qui n'est pas couvert (ex. "les modalités de calcul ne sont pas
              précisées ici") plutôt que de bloquer toute la réponse à cause de cette seule lacune.
            - Si, et seulement si, aucun des extraits ne permet une synthèse fiable du rôle du module, écris
              exactement le mot AUCUN (rien d'autre) — mais n'écris JAMAIS cela si au moins un extrait contient
              une information utile, même partielle ou incomplète : dans ce cas, synthétise cette information
              plutôt que de t'arrêter à une absence. Une réponse partielle mais fondée est toujours préférable à
              un refus de répondre.

            Donne aussi, toujours à partir des mêmes extraits :
            - Un exemple concret UNIQUEMENT s'il est explicitement présent dans un extrait (tournures "for
              example", "such as", "where X is/corresponds to Y"...) : reformule-le brièvement (code/valeur ->
              signification), dans la langue de la question, en conservant le code exact. Sinon écris exactement
              AUCUN.
            - Un extrait : une portion de texte COURTE (une phrase, ou moins), recopiée CARACTÈRE PAR CARACTÈRE
              depuis l'un des extraits numérotés ci-dessous — mêmes mots, même ponctuation, mêmes espaces, sans
              en changer, ajouter ou omettre un seul — celle qui illustre le mieux le rôle du module. Ne combine
              jamais des mots venant de deux extraits différents. En cas de doute sur l'exactitude du copié-collé,
              préfère une citation plus courte mais sûre à une citation plus longue mais approximative. Sinon
              écris exactement AUCUN — mais seulement si vraiment aucun passage ne convient, pas par prudence
              excessive : un extrait existant et pertinent doit toujours être préféré à AUCUN.

            Termine TOUJOURS tes phrases : n'écris jamais une phrase coupée ou un mot manquant. Si tu manques de
            place, raccourcis plutôt que de t'arrêter au milieu d'une idée.

            Réponds STRICTEMENT dans ce format, une balise par ligne, dans cet ordre, rien avant ni après, sans
            markdown ni guillemets superflus (le texte de chaque balise peut occuper plusieurs lignes, mais ne
            commence jamais une nouvelle balise avant d'avoir terminé la phrase précédente) :
            ROLE: <synthèse ou AUCUN>
            EXEMPLE: <exemple ou AUCUN>
            EXTRAIT: <citation mot pour mot ou AUCUN>
            """;

    /**
     * Seul appel à Llama3 de cette classe : rédige, à partir des {@code extracts} donnés (jamais plus de
     * {@link #MAX_SOURCE_TABLES}), la synthèse de rôle en 3 champs (Rôle/Exemple/Extrait), sans laisser le
     * modèle choisir les tables ni la structure de la réponse. Budget de génération augmenté ({@code numPredict})
     * par rapport au plafond global du chatbot (350 tokens) : une marge de raisonnement est nécessaire même
     * pour une réponse finale courte.
     */
    private ModuleRoleAnswer synthesizeModuleRole(String module, List<TableExtract> extracts, String question, boolean dispersed) {
        // Identifiants T24 déjà réellement présents dans ce qui a été fourni au modèle : le module, les tables
        // elles-mêmes, et tout identifiant (table/champ/code) mentionné dans l'un des extraits ANGLAIS originaux.
        // Un identifiant hors de cet ensemble n'a pu venir que de l'invention du modèle — voir la garde
        // {@link #containsUnknownIdentifier}.
        Set<String> allowedIdentifiers = new HashSet<>();
        allowedIdentifiers.add(module.toUpperCase(Locale.ROOT));
        StringBuilder combinedExtracts = new StringBuilder();
        for (TableExtract extract : extracts) {
            allowedIdentifiers.add(extract.table().toUpperCase(Locale.ROOT));
            combinedExtracts.append(' ').append(extract.englishOverview());
            Matcher identifiers = IDENTIFIER_LIKE.matcher(extract.englishOverview());
            while (identifiers.find()) {
                allowedIdentifiers.add(identifiers.group().toUpperCase(Locale.ROOT));
            }
        }
        String combinedExtractsFlat = combinedExtracts.toString();
        String combinedExtractsLower = combinedExtractsFlat.toLowerCase(Locale.ROOT);

        String userPrompt = buildRoleUserPrompt(module, question, extracts);
        String raw;
        try {
            ChatResponse response = chatClient.prompt().system(ROLE_SYSTEM_PROMPT).user(userPrompt)
                    .options(OllamaOptions.builder().numPredict(500).temperature(0.2).build())
                    .call().chatResponse();
            raw = response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
        } catch (Exception e) {
            log.warn("Échec de l'appel Llama3 pour synthétiser le rôle du module {} : repli sur l'extrait le plus renseigné.",
                    module, e);
            raw = null;
        }
        log.debug("Réponse Llama3 (rôle du module {}) :\n{}", module, raw);

        if (raw == null || raw.isBlank() || !Pattern.compile("(?im)(?:^|\\r?\\n)\\s*EXTRAIT\\s*:").matcher(raw).find()) {
            // Balise finale absente : la génération s'est arrêtée avant la fin (plafond de tokens atteint malgré
            // la marge prise ci-dessus, ou réponse mal formée) — jamais de contenu partiel, repli déterministe.
            if (raw != null && !raw.isBlank()) {
                log.warn("Réponse Llama3 (module {}) incomplète (balise EXTRAIT absente) : repli déterministe.", module);
            }
            return deterministicRoleFallback(extracts);
        }

        String role = sanitizeRole(extractField(raw, "ROLE"), allowedIdentifiers, combinedExtractsLower, extracts, dispersed);
        String example = sanitizeExample(extractField(raw, "EXEMPLE"), allowedIdentifiers);
        QuoteResult quote = sanitizeQuote(extractField(raw, "EXTRAIT"), extracts);
        return new ModuleRoleAnswer(role, example, quote.quote(), quote.sourceTable());
    }

    private static String buildRoleUserPrompt(String module, String question, List<TableExtract> extracts) {
        StringBuilder sb = new StringBuilder("Module : ").append(module)
                .append("\n\nQuestion de l'utilisateur (sert uniquement à détecter la langue de réponse attendue) : ")
                .append(question).append("\n\nExtraits :\n");
        for (int i = 0; i < extracts.size(); i++) {
            sb.append(i + 1).append(". Table ").append(extracts.get(i).table()).append(" : \"")
                    .append(extracts.get(i).englishOverview()).append("\"\n");
        }
        return sb.toString();
    }

    /** Contenu de la balise {@code label} jusqu'à la balise suivante ou la fin du texte (jamais seulement la fin
     * de la ligne courante, voir {@link #NEXT_MARKER_LOOKAHEAD}), espaces/retours à la ligne internes aplatis. */
    private static String extractField(String raw, String label) {
        Matcher m = Pattern.compile("(?is)(?:^|\\r?\\n)\\s*" + label + "\\s*:\\s*(.*?)" + NEXT_MARKER_LOOKAHEAD).matcher(raw);
        return m.find() ? flat(m.group(1)) : null;
    }

    /** Une phrase qui ne se termine pas par une ponctuation de fin plausible est probablement coupée en milieu de
     * mot/idée (troncature) plutôt que réellement finie — voir la demande explicite de ne jamais rien envoyer de
     * tronqué. */
    private static boolean looksTruncated(String text) {
        return !SENTENCE_END.matcher(text.strip()).find();
    }

    /**
     * Rejette un Rôle vide, tronqué, qui cite un identifiant absent des extraits fournis, ou qui évoque un
     * concept non étayé — repli déterministe sur l'extrait le plus renseigné (jamais un aveu d'échec quand des
     * informations utiles existent réellement, voir {@link #deterministicRoleFallback}).
     */
    private String sanitizeRole(String candidate, Set<String> allowedIdentifiers, String combinedExtractsLower,
                                List<TableExtract> extracts, boolean dispersed) {
        String rejection = null;
        if (candidate == null || candidate.isBlank() || NONE_MARKER.equalsIgnoreCase(candidate.strip())) {
            rejection = "absent";
        } else if (looksTruncated(candidate)) {
            rejection = "phrase tronquée";
        } else if (containsUnknownIdentifier(candidate, allowedIdentifiers)) {
            rejection = "identifiant non fourni cité";
        } else if (mentionsUngroundedConcept(candidate, combinedExtractsLower)) {
            rejection = "concept non étayé par les extraits";
        } else if (dispersed && reducesModuleToOneTable(candidate, extracts)) {
            rejection = "module réduit à une seule des tables fournies";
        }
        if (rejection != null) {
            if (!"absent".equals(rejection)) {
                log.warn("Rôle rejeté pour le module ({}) : repli sur les extraits réels.", rejection);
            }
            return deterministicRoleFallback(extracts).role();
        }
        return applyTerminologyFixes(candidate);
    }

    private static String sanitizeExample(String candidate, Set<String> allowedIdentifiers) {
        if (candidate == null || candidate.isBlank() || NONE_MARKER.equalsIgnoreCase(candidate.strip())
                || looksTruncated(candidate) || containsUnknownIdentifier(candidate, allowedIdentifiers)) {
            return NO_EXAMPLE_FALLBACK;
        }
        return applyTerminologyFixes(candidate);
    }

    /** Citation retenue (jamais reformulée, guillemets compris) et la table dont elle est réellement
     * tirée — affichée en "Source / Extrait documentaire :** `TABLE`" (voir {@link #modulePurposeAnswer}). */
    private record QuoteResult(String quote, String sourceTable) {
    }

    /** L'Extrait n'est jamais reformulé ni traduit : accepté seulement s'il existe mot pour mot (espaces normalisés)
     * dans L'UN des extraits réellement transmis — même principe que {@code ChatbotServiceImpl.rejectIfExtraitIsFabricated}
     * — ce qui identifie du même coup sa table source (une citation authentique appartient entièrement à un seul
     * extrait, jamais à cheval sur deux). Rejeté (citation absente/mal formée/non trouvée mot pour mot), il ne
     * faut JAMAIS conclure qu'aucun extrait n'est disponible puisque {@code extracts} est ici toujours non vide :
     * repli sur {@link #deterministicQuote}, une phrase réellement copiée depuis la table la plus renseignée —
     * jamais le message "non disponible", qui ne doit apparaître que quand aucune table du module n'a la moindre
     * description (voir {@link #modulePurposeAnswerWithoutAnyOverview}). */
    private static QuoteResult sanitizeQuote(String candidate, List<TableExtract> extracts) {
        if (candidate != null && !candidate.isBlank() && !NONE_MARKER.equalsIgnoreCase(candidate.strip())) {
            String normalizedCandidate = normalizeForComparison(candidate);
            if (!normalizedCandidate.isBlank()) {
                for (TableExtract extract : extracts) {
                    if (normalizeForComparison(extract.englishOverview()).contains(normalizedCandidate)) {
                        return new QuoteResult("« " + candidate + " »", extract.table());
                    }
                }
            }
            log.warn("Extrait cité introuvable mot pour mot dans les extraits fournis pour la synthèse de module : "
                    + "repli sur une phrase réelle garantie plutôt que sur un aveu d'indisponibilité.");
        }
        return deterministicQuote(extracts);
    }

    private static QuoteResult deterministicQuote(List<TableExtract> extracts) {
        TableExtract best = extracts.get(0);
        return new QuoteResult("« " + bestExtractSentence(extracts) + " »", best.table());
    }

    private static String bestExtractSentence(List<TableExtract> extracts) {
        return firstSentences(extracts.get(0).englishOverview(), TABLE_DESCRIPTION_CHARS);
    }

    private static String normalizeForComparison(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean containsUnknownIdentifier(String text, Set<String> allowedIdentifiers) {
        Matcher m = IDENTIFIER_LIKE.matcher(text);
        while (m.find()) {
            if (!allowedIdentifiers.contains(m.group().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Un concept bancaire générique (garantie, commission, provision, marge...) mentionné dans le Rôle doit être
     * étayé par au moins une racine anglaise correspondante dans les extraits réellement fournis — sinon c'est
     * une généralisation non étayée (voir {@link #CONCEPT_GROUNDING}). */
    private static boolean mentionsUngroundedConcept(String text, String combinedExtractsLower) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : CONCEPT_GROUNDING.entrySet()) {
            if (lower.contains(entry.getKey()) && entry.getValue().stream().noneMatch(combinedExtractsLower::contains)) {
                return true;
            }
        }
        return false;
    }

    private static String applyTerminologyFixes(String text) {
        String fixed = text;
        for (Map.Entry<Pattern, String> fix : TERMINOLOGY_FIXES.entrySet()) {
            fixed = fix.getKey().matcher(fixed).replaceAll(fix.getValue());
        }
        return fixed;
    }

    /**
     * Llama3 indisponible/erreur/réponse tronquée ou rejetée : jamais d'invention, jamais un aveu d'échec quand
     * des informations utiles existent réellement — utilise directement la première phrase de l'extrait le plus
     * renseigné (déjà trié par taille décroissante par l'appelant), citée telle quelle en anglais et étiquetée
     * comme telle. Le même passage sert aussi d'Extrait : c'est une phrase réelle, jamais une citation absente.
     */
    private static ModuleRoleAnswer deterministicRoleFallback(List<TableExtract> extracts) {
        TableExtract best = extracts.get(0);
        String sentence = bestExtractSentence(extracts);
        StringBuilder role = new StringBuilder("D'après la documentation de `").append(best.table())
                .append("` (non traduite automatiquement) : ").append(sentence);
        // Plusieurs tables sélectionnées : le repli reflète aussi les suivantes, jamais une seule table présentée
        // comme le rôle de tout le module (cas réel EB, résumé à l'archivage par sa première table).
        for (TableExtract other : extracts.subList(1, Math.min(extracts.size(), FALLBACK_TABLES))) {
            role.append(" Voir aussi `").append(other.table()).append("` : ")
                    .append(firstSentences(other.englishOverview(), TABLE_DESCRIPTION_CHARS));
        }
        return new ModuleRoleAnswer(role.toString(), NO_EXAMPLE_FALLBACK, "« " + sentence + " »", best.table());
    }

    /** Nombre maximal de tables reprises dans le repli déterministe (voir {@link #deterministicRoleFallback}). */
    private static final int FALLBACK_TABLES = 3;

    /**
     * Cohésion thématique minimale (moyenne des similarités cosinus, deux à deux, des tables sélectionnées) en
     * dessous de laquelle un module est jugé hétérogène. Mesurée sur l'index réel (2026-10-02) : EB 0.657, DE 0.653
     * (fonctions transverses sans rapport entre elles) contre 0.734 à 0.821 pour AC, LD, LC, TT, MD. Seuil choisi
     * entre les deux groupes, à partir de ces 7 modules seulement.
     */
    private static final double MIN_MODULE_COHESION = 0.70;

    /**
     * Module hétérogène (voir {@link #MIN_MODULE_COHESION}) dont la synthèse cite moins de deux des tables fournies :
     * elle ramène tout le module à la fonction d'une seule table. Bugs réels : « Le module EB est un outil
     * d'archivage » (2026-09-30, ARCHIVE seul), puis « Le module EB (Report Generator) est un outil... » (2026-10-02,
     * REPGEN.CREATE seul, sans même la citer). Pour un module homogène, une synthèse fondée sur sa table principale
     * reste légitime et n'est pas concernée.
     */
    private static boolean reducesModuleToOneTable(String role, List<TableExtract> extracts) {
        if (extracts.size() < 2) {
            return false;
        }
        String upper = role.toUpperCase(Locale.ROOT);
        long cited = extracts.stream()
                .filter(e -> Pattern.compile("(?<![A-Z0-9.])" + Pattern.quote(e.table().toUpperCase(Locale.ROOT)) + "(?![A-Z0-9.])")
                        .matcher(upper).find())
                .count();
        return cited < 2;
    }

    // --- Lecture du document indexé --------------------------------------------------------------------------

    /** {@code description} : description complète aplatie (règles de validation comprises), vide si non renseignée. */
    record FieldEntry(String name, String summary, String description) {
    }

    /** Champs d'un document (voir DocumentBuilderServiceImpl#toDocument) : nom, description courte, type/saisie si renseignés. */
    static List<FieldEntry> fieldsOf(String documentText) {
        int header = documentText.indexOf(FIELDS_HEADER);
        if (header < 0) {
            return List.of();
        }
        int start = documentText.indexOf("\n\n", header);
        if (start < 0) {
            return List.of();
        }
        List<FieldEntry> entries = new ArrayList<>();
        for (String block : documentText.substring(start + 2).split(Pattern.quote(FIELD_SEPARATOR))) {
            String name = block.contains("\n") ? block.substring(0, block.indexOf('\n')).trim() : block.trim();
            if (name.isEmpty()) {
                continue;
            }
            String description = section(block, "Description :\n");
            String type = section(block, "Type :\n");
            String input = section(block, "Input :\n");

            StringBuilder summary = new StringBuilder();
            if (description.isBlank() || description.startsWith("Non renseignée")) {
                summary.append("description non renseignée");
            } else {
                summary.append(firstSentences(stripValidationRules(flat(description)), FIELD_DESCRIPTION_CHARS));
            }
            List<String> attributes = new ArrayList<>();
            if (!type.isBlank() && !type.startsWith("Non renseign")) {
                attributes.add("type : " + flat(type));
            }
            if (!input.isBlank() && !input.startsWith("Non renseign")) {
                attributes.add("saisie : " + flat(input));
            }
            if (!attributes.isEmpty()) {
                summary.append(" (").append(String.join(" ; ", attributes)).append(')');
            }
            entries.add(new FieldEntry(name, summary.toString(),
                    description.isBlank() || description.startsWith("Non renseignée") ? "" : flat(description)));
        }
        return entries;
    }

    /** Texte d'une section « Titre :\n… » jusqu'à la ligne vide suivante (vide si la section est absente). */
    static String section(String text, String title) {
        int idx = text.indexOf(title);
        if (idx < 0) {
            return "";
        }
        int start = idx + title.length();
        int end = text.indexOf("\n\n", start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }

    private static String stripValidationRules(String description) {
        int idx = description.indexOf("Validation Rules");
        return idx > 0 ? description.substring(0, idx).trim() : description;
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Première(s) phrase(s) jusqu'à ~{@code max} caractères, coupées sur une frontière de phrase quand c'est possible. */
    static String firstSentences(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(". ", max);
        if (cut > max / 3) {
            return text.substring(0, cut + 1);
        }
        return text.substring(0, max).trim() + "…";
    }
}
