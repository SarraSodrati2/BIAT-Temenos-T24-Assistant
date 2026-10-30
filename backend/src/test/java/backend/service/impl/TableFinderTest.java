package backend.service.impl;

import backend.dto.ChatContext;
import backend.service.ConceptGlossary;
import backend.service.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Recherche de tables par concept — documents réels du référentiel (data/vector/index.json, descriptions générales
 * recopiées telles quelles). Bug réel du 2026-10-07 : « quelle table est responsable de balances ? » -&gt; une table
 * arbitraire choisie par Llama3, ou « n'a pas permis de confirmer ». Puis (même jour, demande de qualité) : une question
 * au singulier ne doit pas produire d'inventaire, ni des tables d'autres modules quand le module sélectionné répond.
 */
@ExtendWith(MockitoExtension.class)
class TableFinderTest {

    static final String MD_BALANCES = "MD BALANCES The MD.BALANCES file records all the principal movements of a particular MD.DEAL "
            + "together with future charges to be deducted, and a summary of those charges that are associated with the MD.DEAL.";
    static final String MD_PROV_BALANCES = "MD.PROV.BALANCES As Cash Margin (Provision) can be taken for a MD.DEAL, the details "
            + "regarding the same are stored in this file.";
    static final String MD_PART_CSN_BALANCES = "MD.PART.CSN.BALANCES When a Deal is input with Participation and Commission is "
            + "recovered as END type, this file gets built.";
    static final String MD_DEAL = "OVERVIEW OF MISCELLANEOUS DEALS - MAIN FILE The Miscellaneous Deals module provides the facility to "
            + "record deals which are off balance sheet.";
    static final String AC_BALANCE_TYPE = "Balance Types represent the financial components of a product; the definition of the product "
            + "specifies which balance types the product should be comprised of.";
    static final String AC_BALANCE_COMPONENT = "AC.BALANCE.COMPONENT defines different credit checking components.The User may not "
            + "update, add or reverse records on this table.";
    static final String DE_BIC = "This application contains BIC data updated from SWIFT.";
    static final String TELLER_ID = "TELLER.ID The TELLER.ID application is used to open tills, assign users to tills and close and balance tills.";

    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private ConceptGlossary conceptGlossary;

    private TableFinder finder;
    private List<Document> all;

    @BeforeEach
    void setUp() {
        finder = new TableFinder(vectorStoreService, conceptGlossary);
        lenient().when(conceptGlossary.englishTermsFor(anyString())).thenReturn(List.of());
        all = new ArrayList<>(List.of(
                table("MD.BALANCES", "MD", MD_BALANCES), table("MD.PROV.BALANCES", "MD", MD_PROV_BALANCES),
                table("MD.PART.CSN.BALANCES", "MD", MD_PART_CSN_BALANCES), table("MD.DEAL", "MD", MD_DEAL),
                table("AC.BALANCE.TYPE", "AC", AC_BALANCE_TYPE), table("AC.BALANCE.COMPONENT", "AC", AC_BALANCE_COMPONENT),
                table("DE.BIC", "DE", DE_BIC), table("TELLER.ID", "TT", TELLER_ID)));
        lenient().when(vectorStoreService.findAll()).thenReturn(all);
        for (String module : List.of("MD", "AC", "DE", "TT")) {
            lenient().when(vectorStoreService.findAllByProduct(module))
                    .thenReturn(all.stream().filter(d -> module.equals(d.getMetadata().get("product"))).toList());
        }
    }

    private static Document table(String name, String product, String overview) {
        return RagServiceImplTest.realTable("id-" + name, name, product, overview, "ID", "Id of the record.");
    }

    @Test
    void singularQuestionInMd_givesTheMostDirectTableFirst_thenAFewOthers_neverOtherModules() {
        String answer = finder.tryAnswer("quelle table est responsable de balances ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(answer)
                .startsWith("**Réponse :** la table du module MD dont le nom correspond le plus directement à « balances » est `MD.BALANCES`.")
                .contains("- `MD.BALANCES` — « MD BALANCES The MD.BALANCES file records all the principal movements")
                .contains("**Autres tables du module MD dont le nom contient « balances » :**\n- `MD.PROV.BALANCES`")
                .contains("`MD.PART.CSN.BALANCES`")
                .doesNotContain("AC.BALANCE.TYPE")      // autre module : écarté, le module sélectionné répond
                .doesNotContain("`MD.DEAL`")            // simple mention dans une description : pas listée
                .doesNotContain("principale")           // aucune hiérarchie non documentée
                .contains("**Source :** `MD.BALANCES` `MD.PROV.BALANCES` `MD.PART.CSN.BALANCES`");
        // MD.PROV.BALANCES (1 qualificatif) avant MD.PART.CSN.BALANCES (2)
        assertThat(answer.indexOf("MD.PROV.BALANCES")).isLessThan(answer.indexOf("MD.PART.CSN.BALANCES"));
    }

    @Test
    void equivalentTablesInAc_areListedTogether_andTheAnswerAsksWhichOne() {
        String answer = finder.tryAnswer("quelle table gère les balances ?", new ChatContext("AC", null, null)).orElseThrow();

        assertThat(answer)
                .startsWith("**Réponse :** 2 tables du module AC correspondent au même degré à « balances » :")
                .contains("- `AC.BALANCE.COMPONENT` — « AC.BALANCE.COMPONENT defines different credit checking components.")
                .contains("- `AC.BALANCE.TYPE` — « Balance Types represent the financial components of a product;")
                .contains("Laquelle vous intéresse ?")
                .doesNotContain("MD.BALANCES");
    }

    @Test
    void frenchConceptTranslatedByTheGlossary_soldes_showsTheTermReallyFoundInTheNames() {
        when(conceptGlossary.englishTermsFor("soldes")).thenReturn(List.of("balance"));

        String answer = finder.tryAnswer("Quelle table gère les soldes ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(answer).startsWith("**Réponse :** la table du module MD dont le nom correspond le plus directement à « BALANCE » "
                + "(concept demandé : « soldes ») est `MD.BALANCES`.");
    }

    @Test
    void moduleWithoutAnyMatchingTable_saysSoHonestly_thenOnlyTheMostDirectTablesOfOtherModules() {
        String answer = finder.tryAnswer("quelle table est responsable de balances ?", new ChatContext("DE", null, null)).orElseThrow();

        assertThat(answer)
                .startsWith("**Réponse :** aucune table du module DE ne correspond à « balances ». Dans d'autres modules, les tables "
                        + "dont le nom correspond le plus directement sont :\n- `MD.BALANCES` (module MD)")
                .contains("5 tables correspondent au total ; pour la liste complète");
    }

    @Test
    void moduleWhereOnlyADescriptionMentionsIt_saysNoTableIsNamedAfterIt_andShowsThatTable() {
        String answer = finder.tryAnswer("quelle table est responsable de balances ?", new ChatContext("TT", null, null)).orElseThrow();

        assertThat(answer).startsWith("**Réponse :** aucune table du module TT ne porte « balances » dans son nom ; 1 table en parle dans "
                        + "leur description générale :\n- `TELLER.ID` — « TELLER.ID The TELLER.ID application")
                .doesNotContain("MD.BALANCES");
    }

    @Test
    void otherModulesExplicitlyRequested_areAdded() {
        String answer = finder.tryAnswer("quelle table gère les balances, y compris dans les autres modules ?",
                new ChatContext("MD", null, null)).orElseThrow();

        assertThat(answer).contains("`MD.BALANCES`").contains("**Dans d'autres modules :**").contains("`AC.BALANCE.TYPE` (module AC)");
    }

    @Test
    void explicitRequestForAllTables_givesTheInventory() {
        String answer = finder.tryAnswer("Quelles sont toutes les tables liées aux balances ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(answer).startsWith("**Réponse :** 3 tables du module MD portent « balances » dans leur nom :")
                .contains("`MD.BALANCES`").contains("`MD.PROV.BALANCES`").contains("`MD.PART.CSN.BALANCES`")
                .contains("**Tables du module MD dont la description générale le mentionne :**\n- `MD.DEAL`")
                .doesNotContain("Laquelle");
    }

    @Test
    void withoutModule_theMostDirectNameComesFirst() {
        String answer = finder.tryAnswer("quelle table est responsable de balances ?", ChatContext.EMPTY).orElseThrow();

        assertThat(answer).startsWith("**Réponse :** la table de la documentation dont le nom correspond le plus directement à "
                + "« balances » est `MD.BALANCES`.");
    }

    @Test
    void singleMatchingTable_withoutOverview_saysTheDescriptionIsNotFilled() {
        all.add(table("RE.LMM.BALANCES", "LD", "Non renseignée dans HELP_TEXT."));
        when(vectorStoreService.findAllByProduct("LD")).thenReturn(List.of(all.get(all.size() - 1)));

        String answer = finder.tryAnswer("quelle table est responsable de balances ?", new ChatContext("LD", null, null)).orElseThrow();

        assertThat(answer).startsWith("**Réponse :** la table du module LD dont le nom correspond le plus directement à « balances » "
                        + "est `RE.LMM.BALANCES`.\n- `RE.LMM.BALANCES` — description générale non renseignée dans la documentation.")
                .doesNotContain("Dans d'autres modules");
    }

    // --- Couverture de plusieurs concepts discriminants (bug réel du 2026-10-07, MD.PRESENTATION.HIST) -------------------

    static final String MD_PRESENTATION_HIST = "MD PRESENTATION HIST MD.PRESENTATION.HIST is a system updated dynamic file that "
            + "records all the presentations, thier status, discrepancies, sender details made under a STBY type of undertaking in "
            + "an MD contract. It takes @ID of MD.DEAL record as input for each contract. . It is used along with MD.INVOCATION.HIST "
            + "to record documents received under SBLC and settle payment against each set of documents presented/received from "
            + "presentor under the same.";
    static final String MD_TXN_TYPE_CONDITION = "MD.TXN.TYPE.CONDITION This table facilitates defaulting Provision percentage, "
            + "Commission percentage and ensuring collection of Minimum Commission amount for Guarantees issued.";

    /** Le glossaire réel associe « statut » à « status » (rag/fr-en-glossary.properties). */
    private void realGlossary() {
        lenient().when(conceptGlossary.englishTermsFor("statut")).thenReturn(List.of("status"));
        lenient().when(conceptGlossary.englishTermsFor("avis")).thenReturn(List.of("advice"));
        lenient().when(conceptGlossary.englishTermsFor("lettre")).thenReturn(List.of("letter"));
    }

    @Test
    void mdPresentationHist_question_coveringSeveralRareConcepts_isAnsweredDirectly_genericTypeNeverDecides() {
        // Avant : « 2 tables correspondent au même degré : MD.TXN.TYPE.CONDITION, MD.DEAL.TYPE.PARAMETER. Laquelle ? » —
        // seul le mot générique « type » (présent dans 6 503 documents sur 6 540) les reliait à la question.
        realGlossary();
        all.add(table("MD.PRESENTATION.HIST", "MD", MD_PRESENTATION_HIST));
        all.add(table("MD.INVOCATION.HIST", "MD", "Non renseignée dans HELP_TEXT."));
        all.add(table("MD.TXN.TYPE.CONDITION", "MD", MD_TXN_TYPE_CONDITION));
        all.add(table("MD.DEAL.TYPE.PARAMETER", "MD", "Non renseignée dans HELP_TEXT."));
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(all.stream().filter(d -> "MD".equals(d.getMetadata().get("product"))).toList());

        String answer = finder.tryAnswer("Quelle table du module MD enregistre les présentations, leur statut, les discrepancies et les "
                + "informations sur le présentateur pour les engagements de type STBY ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(answer)
                .startsWith("**Réponse :** la table du module MD dont la documentation correspond le mieux à votre question est "
                        + "`MD.PRESENTATION.HIST`")
                .contains("présentations").contains("discrepancies").contains("stby")
                .contains("> « MD PRESENTATION HIST MD.PRESENTATION.HIST is a system updated dynamic file that records all the presentations")
                .contains("**Source :** `MD.PRESENTATION.HIST`")
                .doesNotContain("MD.TXN.TYPE.CONDITION")
                .doesNotContain("MD.DEAL.TYPE.PARAMETER")
                .doesNotContain("Laquelle");
    }

    static final String LC_ADVICE_TEXT = "The LC.ADVICE.TEXT file holds static narrative information data for use in the mapping of "
            + "the construction of L/C Advices/Documents generated as a result of L/C transactions, within the T24 Delivery Application.";
    static final String DRAWINGS = "Drawings is an application under the Letter of Credit system that is used to effect the transfer of "
            + "funds and the acceptance of documents under Letters of Credit and Documentary Collection transaction.";
    static final String LETTER_OF_CREDIT = "Letter of Credit controls the administration and processing of both Letters of Credit and "
            + "Documentary Collections. It is fully integrated within T24.";

    @Test
    void frenchCognatesOfTheOverview_narratifsStatiquesConstruire_beatTablesSharingOnlyLetterOfCredit() {
        // Mesuré en conditions réelles le 2026-10-07 : sans apparentés, DRAWINGS et LETTER.OF.CREDIT (« Letter of Credit »)
        // passaient devant LC.ADVICE.TEXT, qui dit « static narrative … construction of L/C Advices ».
        realGlossary();
        all.add(table("LC.ADVICE.TEXT", "LC", LC_ADVICE_TEXT));
        all.add(table("DRAWINGS", "LC", DRAWINGS));
        all.add(table("LETTER.OF.CREDIT", "LC", LETTER_OF_CREDIT));
        when(vectorStoreService.findAllByProduct("LC")).thenReturn(all.stream().filter(d -> "LC".equals(d.getMetadata().get("product"))).toList());

        String answer = finder.tryAnswer("Quelle table contient les textes narratifs statiques utilisés pour construire les avis de "
                + "lettre de crédit ?", new ChatContext("LC", null, null)).orElseThrow();

        assertThat(answer).startsWith("**Réponse :** la table du module LC dont la documentation correspond le mieux à votre question est "
                + "`LC.ADVICE.TEXT`").contains("narratifs").contains("statiques").contains("construire").doesNotContain("Laquelle");
    }

    @Test
    void cognates_requireALongSharedPrefix() {
        assertThat(TableFinder.cognates("narratifs", "narrative")).isTrue();
        assertThat(TableFinder.cognates("construire", "construction")).isTrue();
        assertThat(TableFinder.cognates("presentateur", "presentor")).isTrue();
        assertThat(TableFinder.cognates("statiques", "static")).isTrue();
        assertThat(TableFinder.cognates("statiques", "status")).isFalse();
        assertThat(TableFinder.cognates("textes", "text")).isFalse();
    }

    @Test
    void questionAboutAField_orWithoutAnyConcept_orWithAnUnknownConcept_isLeftToTheOtherPipelines() {
        assertThat(finder.tryAnswer("Dans quelle table se trouve le champ DELIVERY.REF ?", ChatContext.EMPTY)).isEmpty();
        assertThat(finder.tryAnswer("Quelles sont toutes les tables ?", new ChatContext("MD", null, null))).isEmpty();
        assertThat(finder.tryAnswer("Quelle table est responsable des hippopotames ?", ChatContext.EMPTY)).isEmpty();
        assertThat(finder.tryAnswer("À quoi sert MD.BALANCES ?", ChatContext.EMPTY)).isEmpty(); // ne demande pas « quelle table »
    }
}
