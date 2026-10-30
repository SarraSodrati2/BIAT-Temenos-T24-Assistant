package backend.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Descriptions RÉELLES, recopiées mot pour mot depuis l'index documentaire (HELP_FIELD) — aucun document fabriqué :
 * le contexte est construit comme le fait RagServiceImpl.extractFieldOnly.
 */
class DocumentedFieldFactsTest {

    static final String TRANSACTION_TYPE = "The key to the file, the value here will be defined as the LC.TYPE followed by either the "
            + "LC.OPERATION for Letters of Credit, or the DRAWING.TYPE for Drawings. Whenever a transaction is input via the Letter of "
            + "Credit/Documentary Collections or Drawings applications, a key is built up of the current LC.TYPE and the current operation "
            + "(For L/Cs), or the Drawing type (for drawings). This key is then used to retrieve a record from this file in order to set up "
            + "all the default charges for the current transaction. For Letters of Credit, the key is defined as: [LC.TYPE]'-'[OPERATION] "
            + "For Drawings, the key is defined as: [LC.TYPE]'-'[DRAWING.TYPE] Where LC.TYPE is from the parent Letter of Credit record. "
            + "Validation Rules: 10 type A (Alphanumeric) characters. Mandatory input for new records. Should relate to a valid L/C type and Operation.";
    static final String CCY_DENOMINATION = "Specifies the currency and the denomination identifier. The first 3 characters of the key "
            + "denote the currency and the remaining 9 characters identify the denomination. Validation Rules: 12 Alphanumeric Consisting of "
            + "CCCxxxxxxxxx The first 3 characters must be a valid entry on the CURRENCY file.";
    static final String FORM_WIDTH = "Specifies the number of characters across a print line. Validation Rules: 2-3 numeric characters. (Mandatory input)";
    static final String DATE_SELECTED = "Maintains a history of the purge date selected for each time archiving was run. Field range "
            + "COMPANY.RUN.IN to TIME.ENDED are related multi-value fields, which are system populated when archiving is run. They maintain a "
            + "history of each time archiving was run. Validation Rules: Internal field.";
    static final String PRIN_PART_BAL = "PRIN.PART.BAL The portion of the Principal amount that is shared by the Participants. This is a "
            + "part of the PRIN.BALANCE multi-value set. In other words this represents the Participants' share in the Deal at that point of "
            + "time. Validation Rules: Standard AMT format no input field.";
    static final String SCH_TYPE_START = "This field indicates the schedule type that can be created by the user. The user can input a "
            + "single schedule or a valid combination of schedules eg PIC except the A schedule. Validation Rules: A combination of up to 3 "
            + "schedule types each represented by a single alphabetic character of the form: A = Annuity (principal + interest) P = principal "
            + "B = Principal Increase I = interest C = commission R = revision M = margin F = fees N = capitalised charge D = diary K = Asset "
            + "Leasing schedule.";
    /** EB, SEAT.APPLICATION.MANAGER — typographie d'origine conservée (’ et –). */
    static final String UPDATE_TYPE = "Accepts one of these values 'ABSOLUTE’,’INCREMENT’,’LOCATE’ ABSOLUTE – Gets "
            + "the Value in the Field as it is INCREMENT – Compares the BEFORE and AFTER image and updates the difference. LOCATE – "
            + "This option is used for MULTIVALUE fields in the application";

    /** Contexte tel que construit par RagServiceImpl.extractFieldOnly : en-tête de table + champ sélectionné + son bloc. */
    static String fieldContext(String table, String product, String field, String description) {
        return "Table : " + table + "\nProduit : " + product + "\n\nDescription générale :\nOverview.\n\n"
                + "Champ sélectionné : " + field + "\n\n" + field + "\n\nDescription :\n" + description
                + "\n\nType :\nNon renseigné.\n\nInput :\nNon renseigné.";
    }

    private static DocumentedFieldFacts facts(String table, String product, String field, String description) {
        return DocumentedFieldFacts.fromContext(fieldContext(table, product, field, description)).orElseThrow();
    }

    private static DocumentedFieldFacts updateType() {
        return facts("SEAT.APPLICATION.MANAGER", "EB", "UPDATE.TYPE", UPDATE_TYPE);
    }

    // --- TRANSACTION.TYPE (LC) : relations, formats, règles -------------------------------------------------------

    @Test
    void transactionType_relationsFormatsAndRules_areAllExtracted() {
        String checklist = facts("LC.TXN.TYPE.CONDITION", "LC", "TRANSACTION.TYPE", TRANSACTION_TYPE).asPromptChecklist();

        assertThat(checklist)
                .contains("LC.TYPE, LC.OPERATION, DRAWING.TYPE")
                .contains("[LC.TYPE]'-'[OPERATION]")
                .contains("[LC.TYPE]'-'[DRAWING.TYPE]")
                .contains("10 type A (Alphanumeric) characters");
    }

    @Test
    void transactionType_vagueAnswer_formatsGoToTheExample_relationsAndRulesToTheOtherFacts() {
        // Réponse réellement produite avant correction (rapport utilisateur, 2026-10-03).
        String vague = "**Fonction :** Le champ TRANSACTION.TYPE définit la clé pour récupérer les informations de défaut pour les "
                + "transactions de lettres de crédit ou de collecte documentaire.\n\n**Exemple :** Le champ est utilisé pour définir les "
                + "types de lettres de crédit et les opérations associées.\n\n";
        DocumentedFieldFacts facts = facts("LC.TXN.TYPE.CONDITION", "LC", "TRANSACTION.TYPE", TRANSACTION_TYPE);

        // Un format concret documenté est un exemple légitime (demande explicite du 2026-10-05).
        assertThat(facts.missingExamples(vague)).containsExactly("`[LC.TYPE]'-'[OPERATION]`", "`[LC.TYPE]'-'[DRAWING.TYPE]`");
        assertThat(String.join("\n", facts.missingFacts(vague, "TRANSACTION.TYPE à quoi sert ce champ ?")))
                .contains("`LC.OPERATION`")
                .contains("« 10 type A (Alphanumeric) characters. Mandatory input for new records. Should relate to a valid L/C type and Operation. »");
    }

    @Test
    void transactionType_completeAnswer_missesNothing() {
        String complete = "**Fonction :** La clé est construite à partir de LC.TYPE et LC.OPERATION, ou de LC.TYPE et DRAWING.TYPE ; "
                + "10 caractères alphanumériques (type A), saisie obligatoire.\n\n**Exemple :**\n- `[LC.TYPE]'-'[OPERATION]`\n"
                + "- `[LC.TYPE]'-'[DRAWING.TYPE]`\n\n";
        DocumentedFieldFacts facts = facts("LC.TXN.TYPE.CONDITION", "LC", "TRANSACTION.TYPE", TRANSACTION_TYPE);

        assertThat(facts.missingExamples(complete)).isEmpty();
        assertThat(facts.missingFacts(complete, "TRANSACTION.TYPE à quoi sert ce champ ?")).isEmpty();
    }

    // --- Règles, relations, documentation courte (TT, DE, EB, MD) --------------------------------------------------

    @Test
    void ccyDenomination_formatRule_isReportedWhenTheAnswerOmitsIt_andIsNotAnExample() {
        // Réponse réellement produite par Llama3 (2026-10-03) : le gabarit CCCxxxxxxxxx et le lien CURRENCY disparaissaient.
        String answer = "**Fonction :** Le champ CCY.DENOMINATION définit la devise et l'identifiant de la dénomination. Les trois "
                + "premiers caractères de la clé dénotent la devise, tandis que les neuf caractères restants identifient la dénomination.\n\n";
        DocumentedFieldFacts facts = facts("TELLER.DENOMINATION", "TT", "CCY.DENOMINATION", CCY_DENOMINATION);

        assertThat(facts.missingFacts(answer, "CCY.DENOMINATION à quoi sert ce champ ?"))
                .containsExactly("Règles de validation (texte d'origine) : « 12 Alphanumeric Consisting of CCCxxxxxxxxx The first 3 "
                        + "characters must be a valid entry on the CURRENCY file. »");
        assertThat(facts.missingExamples(answer)).isEmpty();
    }

    @Test
    void formWidth_shortDocumentation_onlyItsRuleCanBeMissing_andNoExampleExists() {
        DocumentedFieldFacts facts = facts("DE.FORM.TYPE", "DE", "FORM.WIDTH", FORM_WIDTH);

        assertThat(facts.missingFacts("**Fonction :** Nombre de caractères par ligne d'impression (2-3 caractères numériques).", "q")).isEmpty();
        assertThat(facts.missingFacts("**Fonction :** Nombre de caractères par ligne d'impression.", "q")).hasSize(1);
        assertThat(facts.missingExamples("**Fonction :** Nombre de caractères par ligne d'impression.")).isEmpty();
    }

    @Test
    void dateSelected_relatedFieldRange_isReported_butARuleWithoutDistinctiveTokenIsNot() {
        List<String> missing = facts("ARCHIVE", "EB", "DATE.SELECTED", DATE_SELECTED)
                .missingFacts("**Fonction :** Historique des dates de purge choisies à chaque archivage.", "q");

        assertThat(missing).containsExactly("Identifiants cités : `COMPANY.RUN.IN`, `TIME.ENDED`");
    }

    @Test
    void prinPartBal_relationToItsMultiValueSet_isExtracted_withoutTheFieldItself() {
        String checklist = facts("MD.BALANCES", "MD", "PRIN.PART.BAL", PRIN_PART_BAL).asPromptChecklist();

        assertThat(checklist).contains("identifiants cités : PRIN.BALANCE").doesNotContain("PRIN.PART.BAL,");
    }

    @Test
    void schType_documentedExample_goesToTheExampleSection_whenTheAnswerClaimsThereIsNone() {
        // Llama3 a répondu « Aucun exemple explicite » alors que la description cite « eg PIC ».
        List<String> missing = facts("LD.SCHEDULE.DEFINE", "LD", "SCH.TYPE", SCH_TYPE_START)
                .missingExamples("**Fonction :** Type de planning.\n\n**Exemple :** Aucun exemple explicite n'est fourni dans la documentation.");

        assertThat(missing).containsExactly("`PIC` (exemple cité par la documentation)");
    }

    // --- UPDATE.TYPE (EB) : valeurs documentées et leur comportement -----------------------------------------------

    @Test
    void updateType_eachDocumentedValueAndItsBehaviour_isExtracted_despiteTheMissingSentenceBreaks() {
        assertThat(updateType().values()).containsExactly(
                new DocumentedFieldFacts.DocumentedValue("ABSOLUTE", "Gets the Value in the Field as it is"),
                new DocumentedFieldFacts.DocumentedValue("INCREMENT", "Compares the BEFORE and AFTER image and updates the difference"),
                new DocumentedFieldFacts.DocumentedValue("LOCATE", "This option is used for MULTIVALUE fields in the application"));
    }

    @Test
    void updateType_valuesAreGivenToTheModelAsFunctionalFacts_neverAsExamples() {
        assertThat(updateType().asPromptChecklist())
                .contains("valeurs documentées et leur comportement (à expliquer dans la Fonction, ce ne sont pas des exemples)")
                .contains("ABSOLUTE = Gets the Value in the Field as it is")
                .contains("INCREMENT = Compares the BEFORE and AFTER image and updates the difference")
                .contains("LOCATE = This option is used for MULTIVALUE fields in the application");
        // Une liste de valeurs possibles n'est pas un exemple : aucune section Exemple n'est imposée.
        assertThat(updateType().missingExamples("**Fonction :** Type de mise à jour.")).isEmpty();
    }

    @Test
    void updateType_answerThatOnlyListsTheValues_getsEveryValueWithItsDocumentedBehaviour() {
        // Réponse réellement produite (2026-10-05) à « Quelles sont les valeurs possibles de UPDATE.TYPE ? ».
        String bareList = "**Fonction :** Les valeurs possibles de UPDATE.TYPE sont 'ABSOLUTE', 'INCREMENT', 'LOCATE'. Ces valeurs "
                + "définissent les règles d'actualisation du champ sélectionné.\n\n";

        assertThat(updateType().missingFacts(bareList, "Quelles sont les valeurs possibles de UPDATE.TYPE ?")).containsExactly(
                "Valeurs documentées (texte d'origine) : `ABSOLUTE` – Gets the Value in the Field as it is ; `INCREMENT` – Compares "
                        + "the BEFORE and AFTER image and updates the difference ; `LOCATE` – This option is used for MULTIVALUE fields "
                        + "in the application");
    }

    @Test
    void updateType_answerExplainingEachValue_isConsideredComplete() {
        // Réponse réellement produite (2026-10-05) à « UPDATE.TYPE à quoi sert ? » : chaque valeur a sa propre explication.
        String explained = "**Fonction :** Il accepte trois valeurs : 'ABSOLUTE', 'INCREMENT' ou 'LOCATE'. Chacune de ces valeurs a une "
                + "fonction spécifique : 'ABSOLUTE' récupère la valeur du champ telle qu'elle est, 'INCREMENT' met à jour la différence "
                + "entre les images avant et après, tandis que 'LOCATE' est utilisé pour les champs multivalués.\n\n";

        assertThat(updateType().missingFacts(explained, "UPDATE.TYPE à quoi sert ?")).isEmpty();
    }

    @Test
    void updateType_questionAboutOneValue_onlyRequiresThatValue() {
        String answer = "**Fonction :** Avec INCREMENT, le système compare les images avant et après et met à jour la différence.\n\n";

        assertThat(updateType().missingFacts(answer, "Que fait UPDATE.TYPE = INCREMENT ?")).isEmpty();
        assertThat(updateType().missingFacts("**Fonction :** Valeur de mise à jour.", "Que fait UPDATE.TYPE = INCREMENT ?"))
                .containsExactly("Valeurs documentées (texte d'origine) : `INCREMENT` – Compares the BEFORE and AFTER image and "
                        + "updates the difference");
    }

    @Test
    void contextWithoutASelectedField_hasNoFacts() {
        Optional<DocumentedFieldFacts> facts = DocumentedFieldFacts.fromContext(
                "Table : LC.TXN.TYPE.CONDITION\nProduit : LC\n\nDescription générale :\nThis file is used to set up the default charges.");

        assertThat(facts).isEmpty();
    }
}
