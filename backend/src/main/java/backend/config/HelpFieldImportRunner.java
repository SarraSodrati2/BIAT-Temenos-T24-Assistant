package backend.config;

import backend.entity.HelpField;
import backend.entity.HelpText;
import backend.service.HelpFieldService;
import backend.service.HelpTextService;
import backend.service.XmlParserService;
import backend.utils.TextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * Importe automatiquement, au démarrage de Spring Boot, tous les champs T24 (balises
 * {@code <t>}) présents dans les documents déjà stockés en base dans HELP_TEXT vers la table
 * Oracle HELP_FIELD.
 *
 * <p>Pour chaque ligne HELP_TEXT, le XML (colonne {@code DATA}) est parsé via
 * {@link XmlParserService#parseXml(String)} (parseur déjà durci contre le BOM UTF-8 et le DOCTYPE).
 * Chaque balise {@code <t>} donne une ligne HELP_FIELD : {@code field}/{@code desc}/
 * {@code mandatory}/{@code type}/{@code default} sont lus tels quels depuis le XML, tandis que
 * {@code HELP_TEXT_ID}/{@code PRODUCT}/{@code TABLE_NAME} sont recopiés depuis la ligne HELP_TEXT
 * parente (pas depuis le XML).
 *
 * <p>Incrémental : seules les lignes HELP_TEXT qui n'ont encore AUCUN champ dans HELP_FIELD sont
 * traitées (premier import, fichiers XML ajoutés, ou fichiers modifiés dont
 * {@link HelpTextImportRunner} a supprimé les champs). Avant, l'import entier était ignoré dès que
 * HELP_FIELD contenait une seule ligne : les champs d'un fichier XML ajouté plus tard n'étaient jamais
 * extraits, donc jamais indexés ni retrouvables.
 *
 * <p>{@code @Order(2)} : après {@link HelpTextImportRunner} (1), avant {@code VectorIndexRunner} (3).
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class HelpFieldImportRunner implements CommandLineRunner {

    /** Longueurs des colonnes Oracle HELP_FIELD, pour éviter un ORA-12899 sur une valeur trop longue. */
    private static final int MANDATORY_MAX_LENGTH = 10;
    private static final int FIELD_TYPE_MAX_LENGTH = 100;
    private static final int DEFAULT_VALUE_MAX_LENGTH = 255;

    private final HelpTextService helpTextService;
    private final HelpFieldService helpFieldService;
    private final XmlParserService xmlParserService;

    @Override
    public void run(String... args) {
        long start = System.currentTimeMillis();
        List<HelpText> documents = helpTextService.findAllWithoutFields();
        if (documents.isEmpty()) {
            log.info("HELP_FIELD à jour : aucun document HELP_TEXT sans champs extraits, import HELP_FIELD ignoré.");
            return;
        }
        log.info("Import HELP_FIELD : {} document(s) HELP_TEXT sans champs extraits à traiter.", documents.size());

        int fieldsExtracted = 0;
        int rowsInserted = 0;
        int errors = 0;

        for (HelpText document : documents) {
            try {
                List<HelpField> fields = extractFields(document);
                fieldsExtracted += fields.size();
                if (!fields.isEmpty()) {
                    rowsInserted += helpFieldService.saveAll(fields).size();
                }
            } catch (Exception e) {
                errors++;
                log.warn("Échec de l'extraction des champs pour HELP_TEXT id={} ({}) : {}",
                        document.getId(), document.getFileName(), e.getMessage());
            }
        }

        long elapsedMs = System.currentTimeMillis() - start;
        log.info("Import HELP_FIELD terminé : {} document(s) HELP_TEXT lu(s), {} champ(s) extrait(s), "
                        + "{} ligne(s) insérée(s), {} erreur(s), {} ms.",
                documents.size(), fieldsExtracted, rowsInserted, errors, elapsedMs);
    }

    private List<HelpField> extractFields(HelpText document) {
        Document xml = xmlParserService.parseXml(document.getData());
        NodeList tNodes = xml.getElementsByTagName("t");

        List<HelpField> fields = new ArrayList<>();
        for (int i = 0; i < tNodes.getLength(); i++) {
            Element t = (Element) tNodes.item(i);
            String fieldName = firstChildText(t, "field");
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }

            fields.add(HelpField.builder()
                    .helpTextId(document.getId())
                    .product(document.getProduct())
                    .tableName(document.getTableName())
                    .fieldName(fieldName)
                    .description(firstChildText(t, "desc"))
                    .mandatory(truncate(firstChildText(t, "mandatory"), MANDATORY_MAX_LENGTH))
                    .fieldType(truncate(firstChildText(t, "type"), FIELD_TYPE_MAX_LENGTH))
                    .defaultValue(truncate(firstChildText(t, "default"), DEFAULT_VALUE_MAX_LENGTH))
                    .build());
        }
        return fields;
    }

    private static String firstChildText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        return TextNormalizer.clean(nodes.item(0).getTextContent());
    }

    private static String truncate(String value, int maxLength) {
        return (value != null && value.length() > maxLength) ? value.substring(0, maxLength) : value;
    }
}
