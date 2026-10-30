package backend.service.impl;

import backend.dto.ChatContext;
import backend.entity.HelpField;
import backend.repository.HelpFieldRepository;
import backend.service.SynonymDictionary;
import backend.service.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FieldSearchServiceImplTest {

    @Mock
    private HelpFieldRepository helpFieldRepository;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private SynonymDictionary synonymDictionary;
    @Mock
    private ChatClient.Builder chatClientBuilder;

    private FieldSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        // Le générateur de fiche table/module (DocumentOverviewBuilder) n'a besoin d'un vrai ChatClient que pour
        // la synthèse de rôle d'un module, non exercée par ces tests (dédiés à la recherche de champ) : un
        // constructeur mocké suffit, jamais invoqué ici.
        service = new FieldSearchServiceImpl(helpFieldRepository, vectorStoreService, synonymDictionary,
                new DocumentOverviewBuilder(vectorStoreService, helpFieldRepository, chatClientBuilder));
        lenient().when(synonymDictionary.expand(anyString())).thenReturn(List.of());
        lenient().when(helpFieldRepository.countByProductIgnoreCase("TT")).thenReturn(371L);
        lenient().when(helpFieldRepository.countDistinctTablesByProduct("TT")).thenReturn(20L);
        lenient().when(helpFieldRepository.countDistinctFieldsByProduct("TT")).thenReturn(371L);
    }

    private static HelpField field(long id, String product, String table, String name, String description) {
        HelpField f = new HelpField();
        f.setId(id);
        f.setProduct(product);
        f.setTableName(table);
        f.setFieldName(name);
        f.setDescription(description);
        return f;
    }

    // --- détection ----------------------------------------------------------------------------------------

    @Test
    void detectsReverseFieldSearchQuestions_inFrenchAndEnglish() {
        assertThat(FieldSearchServiceImpl.isFieldSearchQuestion("quel champ contient le code SWIFT dans TT ?")).isTrue();
        assertThat(FieldSearchServiceImpl.isFieldSearchQuestion("Quels champs mentionnent le BIC ?")).isTrue();
        assertThat(FieldSearchServiceImpl.isFieldSearchQuestion("dans quelle table se trouve le champ SWIFT.CODE, quelle table contient ce champ ?")).isTrue();
        assertThat(FieldSearchServiceImpl.isFieldSearchQuestion("which field contains the SWIFT code in TT?")).isTrue();
    }

    @Test
    void doesNotHijackOrdinaryQuestions() {
        assertThat(FieldSearchServiceImpl.isFieldSearchQuestion("À quoi sert DEAL.SUB.TYP ?")).isFalse();
        assertThat(FieldSearchServiceImpl.isFieldSearchQuestion("Décris la table CUSTOMER")).isFalse();
        assertThat(FieldSearchServiceImpl.isFieldSearchQuestion("Que fait le module MD ?")).isFalse();
    }

    @Test
    void extractsTheSearchedTerm_quotedUppercaseOrAfterALabel() {
        assertThat(FieldSearchServiceImpl.extractTerm("quel champ contient le code SWIFT dans TT ?", "TT")).contains("SWIFT");
        assertThat(FieldSearchServiceImpl.extractTerm("quel champ contient « numéro de compte » ?", null)).contains("numéro de compte");
        assertThat(FieldSearchServiceImpl.extractTerm("quel champ contient le mot beneficiary ?", null)).contains("beneficiary");
        assertThat(FieldSearchServiceImpl.extractTerm("quel champ contient ça ?", null)).isEmpty();
    }

    @Test
    void shortTermsMatchWholeWordsOnly() {
        assertThat(FieldSearchServiceImpl.mentions("Enter the BIC of the bank", "BIC")).isTrue();
        assertThat(FieldSearchServiceImpl.mentions("A public reference", "BIC")).isFalse();
        assertThat(FieldSearchServiceImpl.mentions("SWIFT.STMT.TYPE", "SWIFT")).isTrue();
        assertThat(FieldSearchServiceImpl.mentions("SWIFTCODE", "SWIFT")).isTrue();
    }

    // --- réponse ------------------------------------------------------------------------------------------

    @Test
    void moduleScopeWithMentionsButNoFieldName_listsRealExcerpts_andExplainsTheScope() {
        when(helpFieldRepository.searchByTermInProduct("TT", "SWIFT")).thenReturn(List.of(
                field(1, "TT", "TELLER", "THEIR.REFERENCE",
                        "Specifies the reference given to the entry. Validation Rules: 16 SWIFT characters (Alphanumeric) (Optional)")));
        when(helpFieldRepository.searchFieldNamesAnywhere("SWIFT")).thenReturn(List.of(
                field(2, "DE", "DE.FORMAT.SWIFT", "SWIFT.CODE", "x")));
        when(vectorStoreService.findAllByProduct("TT")).thenReturn(List.of(
                new Document("t1", "Table : TELLER.FINANCIAL.SERVICES\nProduit : TT\n\nDescription générale :\nEx, SWIFT transfers\n\nChamps (2) :\n\nA", Map.of("tableName", "TELLER.FINANCIAL.SERVICES"))));

        Optional<String> answer = service.tryAnswer("quel champ contient le code SWIFT dans TT ?", ChatContext.EMPTY);

        assertThat(answer).isPresent();
        String text = answer.get();
        assertThat(text)
                .contains("Aucun nom de champ du module `TT` ne contient « SWIFT »")
                .contains("`TELLER` › `THEIR.REFERENCE`")
                .contains("16 SWIFT characters (Alphanumeric)")
                .contains("`TELLER.FINANCIAL.SERVICES`")
                .contains("371 champs documentés dans les 20 tables du module `TT`")
                .contains("[DE] `DE.FORMAT.SWIFT` › `SWIFT.CODE`")
                .contains("une mention dans une description ne signifie pas");
        assertThat(text).doesNotContain("n'existe pas dans T24 ;");
    }

    @Test
    void noMatchAtAll_saysTheSearchFoundNothing_withoutClaimingNonExistence() {
        when(helpFieldRepository.searchByTermInProduct("TT", "ZZZTERM")).thenReturn(List.of());
        when(helpFieldRepository.searchFieldNamesAnywhere("ZZZTERM")).thenReturn(List.of());
        when(vectorStoreService.findAllByProduct("TT")).thenReturn(List.of());

        String text = service.tryAnswer("quel champ contient le code ZZZTERM dans TT ?", ChatContext.EMPTY).orElseThrow();

        assertThat(text)
                .contains("La recherche n'a retrouvé aucun champ du module `TT`")
                .contains("Cela ne suffit pas à affirmer que l'information n'existe pas dans T24")
                .contains("peut être incomplète")
                .doesNotContain("**Source**");
    }

    @Test
    void variantsFromTheSynonymDictionaryAreSearchedToo() {
        when(synonymDictionary.expand("SWIFT")).thenReturn(List.of("BIC", "BANK IDENTIFIER CODE"));
        when(helpFieldRepository.searchByTermInProduct("TT", "SWIFT")).thenReturn(List.of());
        when(helpFieldRepository.searchByTermInProduct("TT", "BIC")).thenReturn(List.of(
                field(3, "TT", "TELLER", "BENEF.BIC", "Bank BIC of the beneficiary")));
        when(helpFieldRepository.searchByTermInProduct("TT", "BANK IDENTIFIER CODE")).thenReturn(List.of());
        when(helpFieldRepository.searchFieldNamesAnywhere(anyString())).thenReturn(List.of());
        when(vectorStoreService.findAllByProduct("TT")).thenReturn(List.of());

        String text = service.tryAnswer("quel champ contient le code SWIFT dans TT ?", ChatContext.EMPTY).orElseThrow();

        assertThat(text).contains("`TELLER` › `BENEF.BIC` — nom approchant").contains("`SWIFT`, `BIC`, `BANK IDENTIFIER CODE`");
        verify(helpFieldRepository).searchByTermInProduct("TT", "BIC");
    }

    @Test
    void exactFieldNameIsFlaggedAsExact_andSortedBeforeApproximateOnes() {
        when(helpFieldRepository.searchByTermInProduct("TT", "SWIFT")).thenReturn(List.of(
                field(4, "TT", "A.TABLE", "SWIFT.CODE", "d1"),
                field(5, "TT", "B.TABLE", "SWIFT", "d2")));
        when(helpFieldRepository.searchFieldNamesAnywhere("SWIFT")).thenReturn(List.of());
        when(vectorStoreService.findAllByProduct("TT")).thenReturn(List.of());

        String text = service.tryAnswer("quel champ contient SWIFT dans TT ?", ChatContext.EMPTY).orElseThrow();

        assertThat(text.indexOf("`B.TABLE` › `SWIFT` — nom exact")).isPositive();
        assertThat(text.indexOf("`B.TABLE` › `SWIFT` — nom exact")).isLessThan(text.indexOf("`A.TABLE` › `SWIFT.CODE` — nom approchant"));
    }

    @Test
    void questionNamingAnExistingTable_isATableSheet_notAReverseSearch() {
        String block = "FIELD.ONE\n\nDescription :\nA field.\n\nType :\nNon renseigné.\n\nInput :\nNon renseigné.\n\n";
        when(vectorStoreService.findByExactTableName("TELLER.ID")).thenReturn(Optional.of(new Document("t", "Table : TELLER.ID\nProduit : TT\n\n"
                + "Description générale :\nTeller ids.\n\nRésumé fonctionnel :\nr\n\nChamps (1) :\n\n" + block,
                Map.of("tableName", "TELLER.ID", "product", "TT"))));

        String text = service.tryAnswer("Quels champs contient la table TELLER.ID ?", ChatContext.EMPTY).orElseThrow();

        assertThat(text).contains("**Table :** `TELLER.ID`").contains("**Informations principales :** `FIELD.ONE`").doesNotContain("nom exact");
    }

    @Test
    void notAReverseSearchQuestion_returnsEmpty_soTheNormalRagPipelineApplies() {
        assertThat(service.tryAnswer("À quoi sert DEAL.SUB.TYP ?", ChatContext.EMPTY)).isEmpty();
    }

    @Test
    void sameTableAndFieldImportedTwice_isListedOnce() {
        when(helpFieldRepository.searchByTermInProduct("TT", "SWIFT")).thenReturn(List.of(
                field(10, "TT", "T.ONE", "MY.SWIFT", "d"), field(11, "TT", "T.ONE", "MY.SWIFT", "d")));
        when(helpFieldRepository.searchFieldNamesAnywhere("SWIFT")).thenReturn(List.of());
        when(vectorStoreService.findAllByProduct("TT")).thenReturn(List.of());

        String text = service.tryAnswer("quel champ contient SWIFT dans TT ?", ChatContext.EMPTY).orElseThrow();

        assertThat(text.split("`T.ONE` › `MY.SWIFT`", -1).length - 1).isEqualTo(1);
        assertThat(text).contains("1 champ du module `TT` a un nom qui correspond");
    }

    @Test
    void whenTheDatabaseCapIsReached_theAnswerSaysThereMayBeMore() {
        List<HelpField> many = java.util.stream.IntStream.range(0, 400)
                .mapToObj(i -> field(1000 + i, "TT", "T.TABLE" + i, "F.SWIFT." + i, "d")).toList();
        when(helpFieldRepository.searchByTermInProduct("TT", "SWIFT")).thenReturn(many);
        when(helpFieldRepository.searchFieldNamesAnywhere("SWIFT")).thenReturn(List.of());
        when(vectorStoreService.findAllByProduct("TT")).thenReturn(List.of());

        String text = service.tryAnswer("quel champ contient SWIFT dans TT ?", ChatContext.EMPTY).orElseThrow();

        assertThat(text).contains("Au moins 400 champs du module `TT`").contains("il peut exister d'autres résultats").contains("… et 360 autre(s) résultat(s) non affiché(s).");
    }
}
