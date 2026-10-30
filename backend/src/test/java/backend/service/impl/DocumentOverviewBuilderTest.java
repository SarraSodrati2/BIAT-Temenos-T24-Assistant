package backend.service.impl;

import backend.dto.ChatContext;
import backend.repository.HelpFieldRepository;
import backend.service.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentOverviewBuilderTest {

    private static final String SEP = "\n\n----------------------------------\n\n";

    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private HelpFieldRepository helpFieldRepository;
    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private DocumentOverviewBuilder builder;

    @BeforeEach
    void setUp() {
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
        builder = new DocumentOverviewBuilder(vectorStoreService, helpFieldRepository, chatClientBuilder);
        lenient().when(vectorStoreService.findByExactTableName(anyString())).thenReturn(Optional.empty());
    }

    /** Prépare la seule réponse Llama3 utilisée par cette classe : la synthèse ROLE/EXEMPLE/EXTRAIT du rôle
     * d'un module, à partir des extraits sélectionnés déterministiquement par le code. */
    private void stubRoleResponse(String raw) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(raw)))));
    }

    private static Document table(String name, String product, String overview, String... fieldBlocks) {
        StringBuilder text = new StringBuilder("Table : " + name + "\nProduit : " + product + "\n\nDescription générale :\n" + overview
                + "\n\nRésumé fonctionnel :\nrésumé\n\nChamps (" + fieldBlocks.length + ") :\n\n");
        text.append(String.join(SEP, fieldBlocks));
        return new Document("id-" + name, text.toString(), Map.of("tableName", name, "product", product, "fieldCount", fieldBlocks.length));
    }

    private static String field(String name, String description, String type, String input) {
        return name + "\n\nDescription :\n" + description + "\n\nType :\n" + type + "\n\nInput :\n" + input + "\n\n";
    }

    // --- Table -----------------------------------------------------------------------------------------------

    @Test
    void tableOverview_answersWithTableRoleMainInfoExampleSourceExtraitFormat_entirelyDeterministic() {
        Document doc = table("MD.PARAMETER", "MD", "This table holds one record per company.",
                field("DEAL.SUB.TYP", "This is a user defined identifier for the type of MD.DEAL. Validation Rules: 1 - 5 alphanumeric characters.", "Non renseigné.", "Mandatory"),
                field("DESCRIPT", "Please specify the guarantee description", "Non renseigné.", "Non renseigné."),
                field("EMPTY.ONE", "Non renseignée.", "A", "Non renseigné."));
        when(vectorStoreService.findByExactTableName("MD.PARAMETER")).thenReturn(Optional.of(doc));

        String text = builder.tryTableOverview("Décris la table MD.PARAMETER").orElseThrow();

        assertThat(text)
                .contains("**Table :** `MD.PARAMETER`")
                .contains("**Rôle :** This table holds one record per company.")
                .contains("**Informations principales :** `DEAL.SUB.TYP`, `DESCRIPT`, `EMPTY.ONE` (3 champs documentés)")
                .doesNotContain("**Exemple :**") // section Exemple conditionnelle (2026-10-05) : absente sans exemple documenté
                .contains("**Source / Extrait documentaire :** `MD.PARAMETER`")
                .contains("> « This table holds one record per company. »");
        assertThat(text).doesNotContain("Validation Rules");
        verifyNoInteractions(chatClient);
    }

    @Test
    void tableOverview_withoutOverviewText_saysSoInsteadOfInventingOne() {
        Document doc = table("X.TABLE", "XX", "Non renseignée dans HELP_TEXT.", field("F1", "Une description", "Non renseigné.", "Non renseigné."));
        when(vectorStoreService.findByExactTableName("X.TABLE")).thenReturn(Optional.of(doc));

        String text = builder.tryTableOverview("Décris la table X.TABLE").orElseThrow();

        // Libellé précisé le 2026-10-06 (« non précisé » → absence de description générale nommée explicitement, avec le
        // module) : l'assertion vérifie toujours qu'aucun rôle n'est inventé.
        assertThat(text)
                .contains("**Rôle :** la documentation T24 indexée ne fournit pas de description générale pour cette table "
                        + "(module XX) : son rôle n'y est pas décrit.")
                .contains("- `F1` : « Une description »")
                .contains("**Source / Extrait documentaire :** `X.TABLE`\nnon disponible dans les passages récupérés.");
    }

    // --- Tables réelles sans description générale (test live du 2026-10-06) -------------------------------------

    @Test
    void roleQuestion_realTableWithoutOverview_ldCollateral_quotesItsFieldsVerbatim_neverCallsItAField_noLlama() {
        // Avant : Llama3 répondait « Le champ LD.COLLATERAL est un champ standard T24 alphanumérique ... 13 caractères ».
        Document doc = table("LD.COLLATERAL", "CO", "Non renseignée dans HELP_TEXT.",
                field("COLLATERAL", "Standard T24 alphanumeric field. Validation Rules: A maximum of 13 characters may be entered. "
                        + "Must be the key to a valid entry on the COLLATERAL file.", "Non renseigné.", "Non renseigné."),
                field("LOANS.AND.DEPOSITS", "Standard T24 alphanumeric field. Validation Rules: Mandatory input. A maximum of 14 "
                        + "characters may be entered. Must be the key to a valid entry on the LD.LOANS.AND.DEPOSITS file.",
                        "Non renseigné.", "Non renseigné."));
        when(vectorStoreService.findByExactTableName("LD.COLLATERAL")).thenReturn(Optional.of(doc));

        String text = builder.tryTableOverview("À quoi sert LD.COLLATERAL ?").orElseThrow();

        assertThat(text)
                .contains("**Table :** `LD.COLLATERAL`")
                .contains("ne fournit pas de description générale pour cette table (module CO)")
                .contains("**Ce que la documentation dit de ses champs** (2 champs) :")
                .contains("- `COLLATERAL` : « Standard T24 alphanumeric field. Validation Rules: A maximum of 13 characters may be "
                        + "entered. Must be the key to a valid entry on the COLLATERAL file. »")
                .contains("Must be the key to a valid entry on the LD.LOANS.AND.DEPOSITS file.")
                .doesNotContain("Le champ LD.COLLATERAL")
                .doesNotContain("**Exemple");
        verifyNoInteractions(chatClient);
    }

    @Test
    void roleQuestion_realTableWhoseFieldsHaveNoHelpText_lcCollateral_namesThemWithoutPresentingTheMentionAsInformation() {
        String unavailable = "Help Text for this field is unavailable. Please refer to the T24 User Guides for further information.";
        Document doc = table("LC.COLLATERAL", "CO", "Non renseignée dans HELP_TEXT.",
                field("COLLATERAL.ID", unavailable, "Non renseigné.", "Non renseigné."),
                field("LETTER.OF.CREDIT", unavailable, "Non renseigné.", "Non renseigné."));
        when(vectorStoreService.findByExactTableName("LC.COLLATERAL")).thenReturn(Optional.of(doc));

        String text = builder.tryTableOverview("À quoi sert LC.COLLATERAL ?").orElseThrow();

        assertThat(text)
                .contains("son rôle n'y est pas décrit")
                .contains("- aucun champ n'a de description d'aide : `COLLATERAL.ID`, `LETTER.OF.CREDIT`")
                .doesNotContain("Help Text for this field")
                .doesNotContain("prêt"); // rôle deviné constaté en test réel (« contrat de prêt ») : jamais
        verifyNoInteractions(chatClient);
    }

    @Test
    void roleQuestion_tableWithARealOverview_withoutTheWordTable_stillGoesToTheRagPipeline() {
        // Garde-fou de non-régression : seules les tables SANS description générale court-circuitent Llama3 sur
        // « À quoi sert X ? » — une table documentée (ex. AC.EXPECTED.RECS) garde la réponse rédigée depuis son contexte.
        Document doc = table("AC.EXPECTED.RECS", "ER", RagServiceImplTest.AC_EXPECTED_RECS_OVERVIEW,
                field("ACCOUNT.NUMBER", "The account number for which the funds are expected.", "Non renseigné.", "Non renseigné."));
        lenient().when(vectorStoreService.findByExactTableName("AC.EXPECTED.RECS")).thenReturn(Optional.of(doc));

        assertThat(builder.tryTableOverview("À quoi sert AC.EXPECTED.RECS ?")).isEmpty();
    }

    @Test
    void tableOverview_isNotUsedForOneSpecificField_orWhenTheTableDoesNotExist() {
        Document doc = table("MD.PARAMETER", "MD", "o", field("DEAL.SUB.TYP", "d", "Non renseigné.", "Non renseigné."));
        lenient().when(vectorStoreService.findByExactTableName("MD.PARAMETER")).thenReturn(Optional.of(doc));

        assertThat(builder.tryTableOverview("Décris le champ DEAL.SUB.TYP de la table MD.PARAMETER")).isEmpty();
        assertThat(builder.tryTableOverview("À quoi sert MD.PARAMETER ?")).isEmpty(); // pas explicitement une table
        assertThat(builder.tryTableOverview("Décris la table INCONNUE.TABLE")).isEmpty();
    }

    @Test
    void tableOverview_mainInformationIsAPreviewOnly_neverTheFullFieldList() {
        // "Informations principales" est un aperçu (quelques noms de champs), jamais l'inventaire complet :
        // celui-ci reste réservé à une demande explicite (ChatbotServiceImpl.buildExhaustiveDump).
        String[] blocks = new String[75];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = field("F" + i, "desc " + i, "Non renseigné.", "Non renseigné.");
        }
        when(vectorStoreService.findByExactTableName("BIG.TABLE")).thenReturn(Optional.of(table("BIG.TABLE", "BG", "o", blocks)));

        String text = builder.tryTableOverview("Quels champs contient la table BIG.TABLE ?").orElseThrow();

        assertThat(text)
                .contains("**Informations principales :** `F0`, `F1`, `F2`, `F3`, `F4`, `F5`, … (75 champs documentés au total)")
                .doesNotContain("`F6`")
                .doesNotContain("`F74`");
    }

    // --- Module : demande explicite portant sur les tables (pas de synthèse de rôle) -------------------------

    @Test
    void moduleOverview_listTablesRequest_listsAllTablesWithRealDescriptions_noRoleSynthesis_noLlamaCall() {
        when(helpFieldRepository.countByProductIgnoreCase("MD")).thenReturn(1L);
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "Parameter table for MD.", field("A", "d", "Non renseigné.", "Non renseigné.")),
                table("MD.DEAL", "MD", "Non renseignée dans HELP_TEXT.", field("B", "d", "Non renseigné.", "Non renseigné."),
                        field("C", "d", "Non renseigné.", "Non renseigné."))));

        String text = builder.tryModuleOverview("Donne-moi les tables du module MD", ChatContext.EMPTY).orElseThrow();

        assertThat(text)
                .contains("**Module :** `MD`")
                .contains("Aucune description officielle du module `MD` n'existe dans la documentation importée")
                .contains("compte 2 table(s) et 3 champ(s) documentés")
                .contains("- `MD.DEAL` — description générale non renseignée (2 champs)")
                .contains("- `MD.PARAMETER` — Parameter table for MD. (1 champ)");
        assertThat(text).doesNotContain("**Description officielle :**").doesNotContain("Synthèse indicative");
        assertThat(text.indexOf("`MD.DEAL`")).isLessThan(text.indexOf("`MD.PARAMETER` —"));
        verifyNoInteractions(chatClient);
    }

    // --- Module : question générale sur le rôle (Rôle/Exemple/Extrait, jamais de bullets par table) -----------

    @Test
    void moduleOverview_purposeQuestion_answersDirectly_withRoleExempleExtraitFormat_noTableBullets() {
        when(helpFieldRepository.countByProductIgnoreCase("MD")).thenReturn(1L);
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD",
                        "This table holds parameters for miscellaneous deals, for example DEAL.SUB.TYP where TYP identifies the type of deal.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module centralise les paramètres et opérations liés aux deals divers (Miscellaneous Deals).
                EXEMPLE: Le champ DEAL.SUB.TYP identifie le type de deal.
                EXTRAIT: This table holds parameters for miscellaneous deals, for example DEAL.SUB.TYP where TYP identifies the type of deal.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", ChatContext.EMPTY).orElseThrow();

        assertThat(text)
                .contains("**Module :** `MD`")
                .contains("**Rôle :** Ce module centralise les paramètres et opérations liés aux deals divers (Miscellaneous Deals).")
                .contains("**Exemple :** Le champ DEAL.SUB.TYP identifie le type de deal.")
                .contains("**Source / Extrait documentaire :** `MD.PARAMETER`\n"
                        + "> « This table holds parameters for miscellaneous deals, for example DEAL.SUB.TYP "
                        + "where TYP identifies the type of deal. »");
        assertThat(text)
                .doesNotContain("Description officielle")
                .doesNotContain("non disponible")
                .doesNotContain("Tables illustratives")
                .doesNotContain("À retenir")
                .doesNotContain("compte 1 table");
    }

    @Test
    void moduleOverview_purposeQuestion_multiLineRole_isCapturedInFull_neverCutToItsFirstLine() {
        // Bug réel corrigé : une réponse étalée sur plusieurs lignes (retour à la ligne naturel du modèle, pas
        // une nouvelle balise) était auparavant tronquée à sa première ligne par une extraction ligne à ligne.
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.DEAL", "MD", "The Miscellaneous Deals module provides the facility to record deals which are off balance sheet.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module permet de saisir des deals hors bilan.
                Il couvre aussi le suivi des montants associés,
                sur plusieurs lignes de texte naturel.
                EXEMPLE: AUCUN
                EXTRAIT: The Miscellaneous Deals module provides the facility to record deals which are off balance sheet.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text).contains("Ce module permet de saisir des deals hors bilan. Il couvre aussi le suivi des montants "
                + "associés, sur plusieurs lignes de texte naturel.");
    }

    @Test
    void moduleOverview_purposeQuestion_fixesAwkwardOffBalanceSheetTranslation() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.DEAL", "MD", "The Miscellaneous Deals module provides the facility to record deals which are off balance sheet.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        // Mauvaise traduction observée en conditions réelles ("deals non équilibrés") : doit être corrigée.
        stubRoleResponse("""
                ROLE: Ce module gère des deals non équilibrés.
                EXEMPLE: AUCUN
                EXTRAIT: The Miscellaneous Deals module provides the facility to record deals which are off balance sheet.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text).doesNotContain("non équilibré").contains("deals hors bilan");
    }

    @Test
    void moduleOverview_purposeQuestion_fixesEnglishTermLeftInsideAFrenchSentence() {
        // Constaté en conditions réelles : "les engagements off balance sheet" (terme anglais non traduit,
        // laissé tel quel au milieu d'une phrase française) plutôt qu'une vraie traduction.
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.DEAL", "MD", "The Miscellaneous Deals module provides the facility to record deals which are off balance sheet.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module gère les engagements off balance sheet.
                EXEMPLE: AUCUN
                EXTRAIT: The Miscellaneous Deals module provides the facility to record deals which are off balance sheet.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        // Le Rôle est corrigé ; l'Extrait, lui, cite le texte anglais source mot pour mot (jamais traduit) —
        // seule la ligne Rôle est concernée par la garde terminologique.
        String roleLine = text.substring(text.indexOf("**Rôle :**"), text.indexOf("**Source"));
        assertThat(roleLine).doesNotContain("off balance sheet").doesNotContain("off-balance-sheet").contains("les engagements hors bilan");
    }

    @Test
    void moduleOverview_purposeQuestion_roleCitesAnUnknownIdentifier_fallsBackToDeterministicSynthesis() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "This table holds parameters for miscellaneous deals.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        // ROLE cite une table jamais fournie (MD.UNKNOWN.TABLE) : doit être rejeté, jamais affiché tel quel.
        stubRoleResponse("""
                ROLE: Voir aussi la table MD.UNKNOWN.TABLE pour plus de détails.
                EXEMPLE: AUCUN
                EXTRAIT: This table holds parameters for miscellaneous deals.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text)
                .doesNotContain("MD.UNKNOWN.TABLE")
                .contains("D'après la documentation de `MD.PARAMETER`")
                .contains("This table holds parameters for miscellaneous deals.");
    }

    @Test
    void moduleOverview_purposeQuestion_truncatedRole_fallsBackToDeterministicSynthesis() {
        // ROLE n'a pas de ponctuation de fin plausible : traité comme une phrase coupée, jamais affiché tel quel.
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "This table holds parameters for miscellaneous deals.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module gère des paramètres pour divers deals et
                EXEMPLE: AUCUN
                EXTRAIT: This table holds parameters for miscellaneous deals.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text)
                .doesNotContain("Ce module gère des paramètres pour divers deals et\n")
                .contains("D'après la documentation de `MD.PARAMETER`");
    }

    @Test
    void moduleOverview_purposeQuestion_responseMissingTheExtraitMarker_isTreatedAsTruncated_fullFallback() {
        // Aucune balise EXTRAIT du tout : la génération s'est arrêtée avant la fin (plafond de tokens atteint) —
        // jamais de contenu partiel affiché, repli complet et déterministe. Même en repli complet, un Extrait
        // réel (une phrase authentique de la table) est toujours produit — jamais un aveu d'indisponibilité,
        // puisqu'une table avec une vraie description existe bel et bien ici.
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "This table holds parameters for miscellaneous deals.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("ROLE: Une synthèse.\nEXEMPLE: Un exemple qui s'arrête bruta");

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text)
                .contains("D'après la documentation de `MD.PARAMETER`")
                .contains("This table holds parameters for miscellaneous deals.")
                .doesNotContain("Aucun exemple explicite") // section Exemple conditionnelle (2026-10-05) : absente sans exemple documenté
                .contains("**Source / Extrait documentaire :** `MD.PARAMETER`\n> « This table holds parameters for miscellaneous deals. »")
                .doesNotContain("s'arrête bruta")
                .doesNotContain("non disponible dans les passages récupérés");
    }

    @Test
    void moduleOverview_purposeQuestion_roleMentionsAnUngroundedConcept_isRejected_fallsBackToDeterministic() {
        // "garantie"/"guarantee" n'apparaît dans aucun extrait fourni : généralisation non étayée, rejetée.
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "This table holds parameters for miscellaneous deals.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module gère principalement des garanties bancaires.
                EXEMPLE: AUCUN
                EXTRAIT: This table holds parameters for miscellaneous deals.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text)
                .doesNotContain("garanties bancaires")
                .contains("D'après la documentation de `MD.PARAMETER`");
    }

    @Test
    void moduleOverview_purposeQuestion_roleMentionsAGroundedConcept_isAccepted() {
        // "guarantee" apparaît réellement dans l'extrait fourni : le rôle peut légitimement en parler.
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "This table is used for the guarantee terms and conditions.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module concerne les garanties bancaires.
                EXEMPLE: AUCUN
                EXTRAIT: This table is used for the guarantee terms and conditions.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text).contains("Ce module concerne les garanties bancaires.");
    }

    @Test
    void moduleOverview_purposeQuestion_extraitNotVerbatimInSource_fallsBackToARealSentence_neverClaimsUnavailable() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "This table holds parameters for miscellaneous deals.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module gère des paramètres pour divers deals.
                EXEMPLE: AUCUN
                EXTRAIT: Cette phrase n'existe pas du tout dans les extraits fournis.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        // Une table a bien une description réelle ici : l'Extrait ne doit JAMAIS être déclaré indisponible,
        // seulement remplacé par une phrase authentique de cette table (voir sanitizeQuote/deterministicQuote).
        assertThat(text)
                .doesNotContain("Cette phrase n'existe pas du tout")
                .doesNotContain("non disponible dans les passages récupérés")
                .contains("**Source / Extrait documentaire :** `MD.PARAMETER`\n> « This table holds parameters for miscellaneous deals. »");
    }

    @Test
    void moduleOverview_purposeQuestion_exempleCitesUnknownIdentifier_isRejected() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "This table holds parameters for miscellaneous deals.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module gère des paramètres pour divers deals.
                EXEMPLE: Voir la table MD.UNKNOWN.TABLE pour un exemple.
                EXTRAIT: This table holds parameters for miscellaneous deals.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text)
                .doesNotContain("MD.UNKNOWN.TABLE")
                .doesNotContain("**Exemple :**"); // section Exemple conditionnelle (2026-10-05) : absente sans exemple documenté
    }

    @Test
    void moduleOverview_purposeQuestion_noTableHasADescription_statesPreciselyWhatIsMissing_neverGeneralKnowledge() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.ONE", "MD", "Non renseignée dans HELP_TEXT.", field("A", "d", "Non renseigné.", "Non renseigné.")),
                table("MD.TWO", "MD", "", field("B", "d", "Non renseigné.", "Non renseigné."))));

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text)
                .contains("**Module :** `MD`")
                .contains("non précisé dans la documentation fournie")
                .contains("aucune des 2 tables de ce module n'a de description générale renseignée")
                .doesNotContain("Aucun exemple explicite") // section Exemple conditionnelle (2026-10-05) : absente sans exemple documenté
                .contains("**Source / Extrait documentaire :** non disponible dans les passages récupérés.");
        verifyNoInteractions(chatClient);
    }

    @Test
    void moduleOverview_purposeQuestion_llamaCallFails_fallsBackToDeterministicSynthesis_neverFabricates() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.PARAMETER", "MD", "This table holds parameters for miscellaneous deals.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        when(chatClient.prompt()).thenThrow(new RuntimeException("Ollama indisponible"));

        String text = builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null)).orElseThrow();

        assertThat(text)
                .contains("D'après la documentation de `MD.PARAMETER`")
                .contains("This table holds parameters for miscellaneous deals.");
    }

    @Test
    void moduleOverview_purposeQuestion_selectsUpToFiveTables_mostDocumentedFirst_sentToLlamaInThatOrder() {
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.T1", "MD", "Short.", field("F", "d", "Non renseigné.", "Non renseigné.")),
                table("MD.T2", "MD", "A bit longer overview here than the first one.", field("F", "d", "Non renseigné.", "Non renseigné.")),
                table("MD.T3", "MD", "An even longer overview describing more detail than the second one.",
                        field("F", "d", "Non renseigné.", "Non renseigné.")),
                table("MD.T4", "MD", "This overview is longer still, describing this table quite thoroughly indeed.",
                        field("F", "d", "Non renseigné.", "Non renseigné.")),
                table("MD.T5", "MD", "This overview goes into even more depth than the fourth one, covering additional aspects.",
                        field("F", "d", "Non renseigné.", "Non renseigné.")),
                table("MD.T6", "MD", "This is by far the longest and most detailed overview of all six tables provided in this test scenario.",
                        field("F", "d", "Non renseigné.", "Non renseigné."))));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        org.mockito.ArgumentCaptor<String> userPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(requestSpec.user(userPromptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ROLE: Synthèse.\nEXEMPLE: AUCUN\nEXTRAIT: AUCUN\n")))));

        builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null));

        String userPrompt = userPromptCaptor.getValue();
        assertThat(userPrompt).doesNotContain("MD.T1");
        assertThat(userPrompt.indexOf("MD.T6")).isLessThan(userPrompt.indexOf("MD.T5"));
        assertThat(userPrompt.indexOf("MD.T5")).isLessThan(userPrompt.indexOf("MD.T4"));
        assertThat(userPrompt.indexOf("MD.T4")).isLessThan(userPrompt.indexOf("MD.T3"));
        assertThat(userPrompt.indexOf("MD.T3")).isLessThan(userPrompt.indexOf("MD.T2"));
    }

    @Test
    void moduleOverview_purposeQuestion_mainApplicationTableWithManyFields_isSelectedEvenIfItsOverviewIsShorter() {
        // Sélection par fusion de rangs (longueur de description + nombre de champs) : la table principale du
        // module (beaucoup de champs) ne doit plus être écartée au profit de tables périphériques plus verbeuses.
        String[] manyFields = {field("F1", "d", "t", "i"), field("F2", "d", "t", "i"), field("F3", "d", "t", "i"),
                field("F4", "d", "t", "i"), field("F5", "d", "t", "i"), field("F6", "d", "t", "i")};
        when(vectorStoreService.findAllByProduct("MD")).thenReturn(List.of(
                table("MD.DEAL", "MD", "Main deal file of the module.", manyFields),
                peripheral(1), peripheral(2), peripheral(3), peripheral(4), peripheral(5)));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        org.mockito.ArgumentCaptor<String> userPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(requestSpec.user(userPromptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ROLE: Synthèse.\nEXEMPLE: AUCUN\nEXTRAIT: AUCUN\n")))));

        builder.tryModuleOverview("À quoi sert le module MD ?", new ChatContext("MD", null, null));

        assertThat(userPromptCaptor.getValue()).contains("MD.DEAL");
    }

    /** Table périphérique : un seul champ, description plus longue que celle de MD.DEAL, longueurs toutes distinctes. */
    private static Document peripheral(int i) {
        return table("MD.P" + i, "MD", "Peripheral parameter table " + i + " overview, " + "with more technical detail".repeat(7 - i) + ".",
                field("A", "d", "t", "i"));
    }

    @Test
    void moduleOverview_purposeQuestion_roleReducingTheModuleToOneOfSeveralTables_isRejected_multiTableFallback() {
        // Bug réel (EB, 2026-09-30) : « Le module EB est un outil d'archivage », tiré de la seule table ARCHIVE alors
        // que les autres extraits fournis décrivaient d'autres fonctions.
        when(vectorStoreService.findAllByProduct("EB")).thenReturn(List.of(
                table("ARCHIVE", "EB", "T24 Archiving has been designed to reclaim space by moving historic data that is no longer required.",
                        field("A", "d", "t", "i"), field("B", "d", "t", "i")),
                table("REPGEN.CREATE", "EB", "This application is used to create report definitions for the report generator.",
                        field("A", "d", "t", "i"), field("B", "d", "t", "i"))));
        stubRoleResponse("""
                ROLE: Le module EB est un outil d'archivage qui libère de l'espace en déplaçant les données historiques (`ARCHIVE`).
                EXEMPLE: AUCUN
                EXTRAIT: T24 Archiving has been designed to reclaim space by moving historic data that is no longer required.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module EB ?", new ChatContext("EB", null, null)).orElseThrow();

        assertThat(text)
                .doesNotContain("est un outil d'archivage")
                .contains("`ARCHIVE`")
                .contains("`REPGEN.CREATE`");
    }

    @Test
    void moduleOverview_purposeQuestion_homogeneousModule_roleBasedOnItsMainTable_isAccepted() {
        // Constaté en test réel (2026-10-02) : une synthèse fondée sur ACCOUNT seul était rejetée, alors que AC est un
        // module homogène dont ACCOUNT est la table principale.
        when(vectorStoreService.findAllByProduct("AC")).thenReturn(List.of(
                table("ACCOUNT", "AC", "The Account Module caters for the creation, maintenance and control of all types of accounts.",
                        field("A", "d", "t", "i"), field("B", "d", "t", "i")),
                table("STANDING.ORDER", "AC", "Standing orders transfer funds at regular intervals.", field("A", "d", "t", "i"))));
        when(vectorStoreService.meanPairwiseSimilarity(anyList())).thenReturn(0.73); // AC réel : 0.734, module homogène
        stubRoleResponse("""
                ROLE: Le module AC gère la création, la maintenance et le contrôle de tous les types de comptes (`ACCOUNT`).
                EXEMPLE: AUCUN
                EXTRAIT: The Account Module caters for the creation, maintenance and control of all types of accounts.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module AC ?", new ChatContext("AC", null, null)).orElseThrow();

        assertThat(text).contains("Le module AC gère la création").doesNotContain("D'après la documentation de");
    }

    @Test
    void moduleOverview_purposeQuestion_roleCitingNoTableAtAll_isNotTreatedAsAOneTableReduction() {
        // Constaté en test réel (2026-10-02) : les synthèses AC/LD/LC sans aucune table citée étaient rejetées à tort.
        when(vectorStoreService.findAllByProduct("LD")).thenReturn(List.of(
                table("LD.LOANS.AND.DEPOSITS", "LD", "The Commercial Loans and Deposits Module automates the control of loans.",
                        field("A", "d", "t", "i"), field("B", "d", "t", "i")),
                table("LD.SCHEDULE.DEFINE", "LD", "This facility enables the user to specify scheduled events.", field("A", "d", "t", "i"))));
        when(vectorStoreService.meanPairwiseSimilarity(anyList())).thenReturn(0.79); // LD réel : 0.792, module homogène
        stubRoleResponse("""
                ROLE: Le module LD automatise la gestion des prêts et dépôts commerciaux, y compris la planification de leurs événements.
                EXEMPLE: AUCUN
                EXTRAIT: The Commercial Loans and Deposits Module automates the control of loans.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module LD ?", new ChatContext("LD", null, null)).orElseThrow();

        assertThat(text).contains("Le module LD automatise la gestion des prêts").doesNotContain("D'après la documentation de");
    }

    @Test
    void moduleOverview_purposeQuestion_heterogeneousModule_roleCitingNoTable_isRejected() {
        // Bug réel (EB, 2026-10-02) : « Le module EB (Report Generator) est un outil qui permet de définir et produire
        // des rapports », sans citer aucune table : EB est hétérogène (cohésion réelle 0.657).
        when(vectorStoreService.findAllByProduct("EB")).thenReturn(List.of(
                table("REPGEN.CREATE", "EB", "The T24 Report Generator utility provides the means to define and produce reports.",
                        field("A", "d", "t", "i"), field("B", "d", "t", "i")),
                table("ARCHIVE", "EB", "T24 Archiving has been designed to reclaim space by moving historic data that is no longer required.",
                        field("A", "d", "t", "i"), field("B", "d", "t", "i"))));
        when(vectorStoreService.meanPairwiseSimilarity(anyList())).thenReturn(0.657);
        stubRoleResponse("""
                ROLE: Le module EB (Report Generator) est un outil qui permet de définir et de produire des rapports.
                EXEMPLE: AUCUN
                EXTRAIT: The T24 Report Generator utility provides the means to define and produce reports.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module EB ?", new ChatContext("EB", null, null)).orElseThrow();

        assertThat(text).doesNotContain("(Report Generator) est un outil").contains("`REPGEN.CREATE`").contains("`ARCHIVE`");
    }

    @Test
    void moduleOverview_purposeQuestion_roleCitingSeveralOfTheProvidedTables_isAccepted() {
        when(vectorStoreService.findAllByProduct("EB")).thenReturn(List.of(
                table("ARCHIVE", "EB", "T24 Archiving has been designed to reclaim space by moving historic data that is no longer required.",
                        field("A", "d", "t", "i"), field("B", "d", "t", "i")),
                table("REPGEN.CREATE", "EB", "This application is used to create report definitions for the report generator.",
                        field("A", "d", "t", "i"), field("B", "d", "t", "i"))));
        stubRoleResponse("""
                ROLE: Le module EB regroupe des fonctions transverses, comme l'archivage des données historiques (`ARCHIVE`) et la définition de rapports (`REPGEN.CREATE`).
                EXEMPLE: AUCUN
                EXTRAIT: This application is used to create report definitions for the report generator.
                """);

        String text = builder.tryModuleOverview("À quoi sert le module EB ?", new ChatContext("EB", null, null)).orElseThrow();

        assertThat(text).contains("regroupe des fonctions transverses").doesNotContain("D'après la documentation de");
    }

    @Test
    void moduleOverview_usesTheConversationModule_whenTheQuestionNamesNone() {
        when(vectorStoreService.findAllByProduct("TT")).thenReturn(List.of(
                table("TELLER", "TT", "Teller transactions processing for over the counter operations.",
                        field("A", "d", "Non renseigné.", "Non renseigné."))));
        stubRoleResponse("""
                ROLE: Ce module concerne les opérations de guichet.
                EXEMPLE: AUCUN
                EXTRAIT: Teller transactions processing for over the counter operations.
                """);

        String text = builder.tryModuleOverview("Décris ce module", new ChatContext("TT", null, null)).orElseThrow();

        assertThat(text).contains("Ce module concerne les opérations de guichet.");
    }

    @Test
    void moduleOverview_ignoresOrdinaryQuestions_andUnknownModules() {
        assertThat(builder.tryModuleOverview("Quelle est la capitale de la Tunisie ?", ChatContext.EMPTY)).isEmpty();
        lenient().when(helpFieldRepository.countByProductIgnoreCase("ZZ")).thenReturn(0L);
        assertThat(builder.tryModuleOverview("À quoi sert le module ZZ ?", ChatContext.EMPTY)).isEmpty();
        // « module de » ne doit jamais être lu comme le module « DE »
        assertThat(builder.tryModuleOverview("À quoi sert le module de paiement ?", ChatContext.EMPTY)).isEmpty();
        verifyNoInteractions(chatClient);
    }
}
