package backend.service;

import backend.dto.FieldInfo;
import org.w3c.dom.Document;

import java.util.List;
import java.util.Optional;

/**
 * Parse le XML T24 HELP.TEXT (colonne TABLE_T24.DATA, lue depuis Oracle en amont).
 * Ce service ne lit jamais de fichier : il opère uniquement sur la chaîne XML fournie.
 */
public interface XmlParserService {

    Document parseXml(String xml);

    List<FieldInfo> getFields(String xml);

    Optional<FieldInfo> findField(String xml, String fieldName);

    /**
     * Description générale de la table (bloc {@code <overview><ovdesc>}, paragraphes {@code <p>}
     * concaténés), quand l'export T24 la renseigne. Distincte des descriptions par champ.
     */
    Optional<String> getOverviewDescription(String xml);
}
