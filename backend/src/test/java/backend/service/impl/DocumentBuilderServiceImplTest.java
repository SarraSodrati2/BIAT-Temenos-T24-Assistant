package backend.service.impl;

import backend.entity.HelpField;
import backend.entity.HelpText;
import backend.repository.HelpFieldRepository;
import backend.repository.HelpTextRepository;
import backend.service.XmlParserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentBuilderServiceImplTest {

    @Mock
    private HelpTextRepository helpTextRepository;
    @Mock
    private HelpFieldRepository helpFieldRepository;
    @Mock
    private XmlParserService xmlParserService;

    @InjectMocks
    private DocumentBuilderServiceImpl documentBuilderService;

    @Test
    void buildDocuments_mergesHelpTextAndItsFieldsIntoOneDocumentPerTable() {
        HelpText helpText = HelpText.builder()
                .id(1L)
                .tableName("AA.ARR.CHARGEOFF")
                .product("AA")
                .build();

        HelpField status = HelpField.builder()
                .helpTextId(1L)
                .fieldName("STATUS")
                .description("Statut de l'arrangement")
                .fieldType("Alpha")
                .mandatory("MAN")
                .build();
        HelpField amount = HelpField.builder()
                .helpTextId(1L)
                .fieldName("AMOUNT")
                .description("Montant")
                .build();

        when(helpTextRepository.findAll()).thenReturn(List.of(helpText));
        when(helpFieldRepository.findAll()).thenReturn(List.of(status, amount));

        List<Document> documents = documentBuilderService.buildDocuments();

        assertThat(documents).hasSize(1);
        Document document = documents.get(0);
        assertThat(document.getId()).isEqualTo("helptext-1");
        assertThat(document.getMetadata())
                .containsEntry("tableName", "AA.ARR.CHARGEOFF")
                .containsEntry("product", "AA")
                .containsEntry("fieldCount", 2);
        assertThat(document.getText())
                .contains("Table : AA.ARR.CHARGEOFF")
                .contains("Produit : AA")
                .contains("Champs (2) :")
                .contains("STATUS")
                .contains("Description :\nStatut de l'arrangement")
                .contains("Type :\nAlpha")
                .contains("Input :\nMAN")
                .contains("AMOUNT")
                .contains("Description :\nMontant");
    }

    @Test
    void buildDocuments_fieldWithoutTypeOrMandatory_fallsBackToPlaceholders() {
        HelpText helpText = HelpText.builder().id(6L).tableName("T").product("XX").build();
        HelpField field = HelpField.builder().helpTextId(6L).fieldName("F1").description("d1").build();

        when(helpTextRepository.findAll()).thenReturn(List.of(helpText));
        when(helpFieldRepository.findAll()).thenReturn(List.of(field));

        String text = documentBuilderService.buildDocuments().get(0).getText();

        assertThat(text).contains("Type :\nNon renseigné.").contains("Input :\nNon renseigné.");
    }

    @Test
    void buildDocuments_tableWithoutFields_stillProducesADocument() {
        HelpText helpText = HelpText.builder().id(2L).tableName("EMPTY.TABLE").product("XX").build();

        when(helpTextRepository.findAll()).thenReturn(List.of(helpText));
        when(helpFieldRepository.findAll()).thenReturn(List.of());

        List<Document> documents = documentBuilderService.buildDocuments();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText())
                .contains("Table : EMPTY.TABLE")
                .contains("Champs (0) :");
    }

    @Test
    void buildDocuments_fieldsFromOtherTables_areNotMixedIn() {
        HelpText chargeoff = HelpText.builder().id(1L).tableName("AA.ARR.CHARGEOFF").product("AA").build();
        HelpText customer = HelpText.builder().id(2L).tableName("CUSTOMER").product("CU").build();

        HelpField chargeoffField = HelpField.builder().helpTextId(1L).fieldName("STATUS").description("x").build();
        HelpField customerField = HelpField.builder().helpTextId(2L).fieldName("NAME").description("y").build();

        when(helpTextRepository.findAll()).thenReturn(List.of(chargeoff, customer));
        when(helpFieldRepository.findAll()).thenReturn(List.of(chargeoffField, customerField));

        List<Document> documents = documentBuilderService.buildDocuments();

        Document chargeoffDoc = documents.stream().filter(d -> d.getId().equals("helptext-1")).findFirst().orElseThrow();
        assertThat(chargeoffDoc.getText()).contains("STATUS").doesNotContain("NAME");
    }

    @Test
    void buildDocuments_overviewPresent_includesFullDescriptionAndAFirstSentenceSummary() {
        HelpText helpText = HelpText.builder().id(3L).tableName("AA.ARR.CHARGEOFF").product("AA")
                .data("<t24help>...</t24help>").build();
        String overview = "For a variety of reasons, a Bank may charge-off a loan. This can be partial or total.";

        when(helpTextRepository.findAll()).thenReturn(List.of(helpText));
        when(helpFieldRepository.findAll()).thenReturn(List.of());
        when(xmlParserService.getOverviewDescription("<t24help>...</t24help>")).thenReturn(Optional.of(overview));

        String text = documentBuilderService.buildDocuments().get(0).getText();

        assertThat(text).contains("Description générale :\n" + overview);
        assertThat(text).contains("Résumé fonctionnel :\nFor a variety of reasons, a Bank may charge-off a loan.");
    }

    @Test
    void buildDocuments_overviewAbsent_fallsBackToADeductionHintInsteadOfFabricatingText() {
        HelpText helpText = HelpText.builder().id(4L).tableName("NO.OVERVIEW.TABLE").product("XX")
                .data("<t24help>...</t24help>").build();
        HelpField field = HelpField.builder().helpTextId(4L).fieldName("F1").description("d1").build();

        when(helpTextRepository.findAll()).thenReturn(List.of(helpText));
        when(helpFieldRepository.findAll()).thenReturn(List.of(field));
        when(xmlParserService.getOverviewDescription(anyString())).thenReturn(Optional.empty());

        String text = documentBuilderService.buildDocuments().get(0).getText();

        assertThat(text).contains("Description générale :\nNon renseignée dans HELP_TEXT.");
        assertThat(text).contains("Résumé fonctionnel :\nAucune description générale disponible ; à déduire des 1 champ(s)");
    }

    @Test
    void buildDocuments_sameTableDocumentedByTwoFiles_mergesIntoOneDocumentKeepingTheRicherFieldDescription() {
        // Reproduit la collision réelle MD.PARAMETER.xml (fichier de base) / MD.PARAMETER,ISB.xml
        // (variante d'overlay) : deux lignes HELP_TEXT distinctes déclarant la même table, chacune
        // avec sa propre ligne HELP_FIELD pour DEAL.SUB.TYP — l'une avec la définition fonctionnelle
        // complète (+ l'exemple GTREC), l'autre avec une simple invite de saisie d'écran.
        HelpText baseFile = HelpText.builder().id(10L).tableName("MD.PARAMETER").product("MD").build();
        HelpText overlayFile = HelpText.builder().id(11L).tableName("MD.PARAMETER").product("MD").build();

        String richDescription = "This is a user defined identifier for the type of MD.DEAL that is to be entered. "
                + "As an example the following structure could be entered where GTREC is a Guarantee Received.";
        HelpField richDealSubTyp = HelpField.builder()
                .helpTextId(10L).fieldName("DEAL.SUB.TYP").description(richDescription).build();
        HelpField poorDealSubTyp = HelpField.builder()
                .helpTextId(11L).fieldName("DEAL.SUB.TYP").description("Please specify the guarantee type").build();
        HelpField poorDescript = HelpField.builder()
                .helpTextId(11L).fieldName("DESCRIPT").description("Please specify the guarantee description").build();

        when(helpTextRepository.findAll()).thenReturn(List.of(baseFile, overlayFile));
        when(helpFieldRepository.findAll()).thenReturn(List.of(richDealSubTyp, poorDealSubTyp, poorDescript));

        List<Document> documents = documentBuilderService.buildDocuments();

        assertThat(documents).hasSize(1);
        Document document = documents.get(0);
        assertThat(document.getMetadata()).containsEntry("tableName", "MD.PARAMETER").containsEntry("fieldCount", 2);
        assertThat(document.getText())
                .contains("DEAL.SUB.TYP")
                .contains("Description :\n" + richDescription)
                .contains("GTREC is a Guarantee Received")
                .doesNotContain("Please specify the guarantee type")
                .contains("DESCRIPT")
                .contains("Description :\nPlease specify the guarantee description");
    }

    @Test
    void buildDocuments_xmlParsingFails_stillProducesADocumentInsteadOfAbortingTheWholeBatch() {
        HelpText helpText = HelpText.builder().id(5L).tableName("BROKEN.XML").product("XX")
                .data("<invalid").build();

        when(helpTextRepository.findAll()).thenReturn(List.of(helpText));
        when(helpFieldRepository.findAll()).thenReturn(List.of());
        when(xmlParserService.getOverviewDescription("<invalid")).thenThrow(new IllegalStateException("malformed XML"));

        List<Document> documents = documentBuilderService.buildDocuments();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).contains("Description générale :\nNon renseignée dans HELP_TEXT.");
    }

    @Test
    void buildDocuments_sameRowsInAnyOrder_produceTheSameDocument() {
        // Bug réel (2026-10-02) : findAll() sans ordre garanti + égalités départagées par l'ordre de la liste =
        // documents différents d'un démarrage à l'autre, donc ré-embeddings inutiles à la synchronisation.
        HelpText first = HelpText.builder().id(5L).tableName("DE.FORM.TYPE").product("DE").build();
        HelpText second = HelpText.builder().id(9L).tableName("DE.FORM.TYPE").product("DE").build();
        HelpField widthA = HelpField.builder().id(10L).helpTextId(5L).fieldName("FORM.WIDTH").description("Width A.").build();
        HelpField widthB = HelpField.builder().id(20L).helpTextId(9L).fieldName("FORM.WIDTH").description("Width B.").build();

        when(helpTextRepository.findAll()).thenReturn(List.of(first, second), List.of(second, first));
        when(helpFieldRepository.findAll()).thenReturn(List.of(widthA, widthB), List.of(widthB, widthA));

        Document inOrder = documentBuilderService.buildDocuments().get(0);
        Document reversed = documentBuilderService.buildDocuments().get(0);

        assertThat(reversed.getId()).isEqualTo(inOrder.getId()).isEqualTo("helptext-5");
        assertThat(reversed.getText()).isEqualTo(inOrder.getText());
    }
}
