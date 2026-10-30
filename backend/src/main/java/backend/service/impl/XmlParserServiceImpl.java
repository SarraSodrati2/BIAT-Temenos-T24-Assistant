package backend.service.impl;

import backend.dto.FieldInfo;
import backend.service.XmlParserService;
import backend.utils.TextNormalizer;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class XmlParserServiceImpl implements XmlParserService {

    @Override
    public Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(stripBom(xml))));
            document.getDocumentElement().normalize();
            return document;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse T24 HELP.TEXT XML: " + e.getMessage(), e);
        }
    }

    /**
     * Certains exports T24 commencent par un BOM UTF-8 (U+FEFF) avant {@code <?xml ...?>},
     * ce qui fait échouer le parseur SAX avec "Content is not allowed in prolog".
     */
    private static final char BOM = '\uFEFF';

    private static String stripBom(String xml) {
        return (xml != null && !xml.isEmpty() && xml.charAt(0) == BOM) ? xml.substring(1) : xml;
    }

    @Override
    public List<FieldInfo> getFields(String xml) {
        Document document = parseXml(xml);
        List<FieldInfo> fields = new ArrayList<>();

        NodeList fieldNodes = document.getElementsByTagName("t");
        for (int i = 0; i < fieldNodes.getLength(); i++) {
            Element fieldEl = (Element) fieldNodes.item(i);
            String name = firstChildText(fieldEl, "field");
            if (name == null || name.isBlank()) {
                continue;
            }
            String description = firstChildText(fieldEl, "desc");
            fields.add(new FieldInfo(name, null, description, null, null, null, null));
        }
        return fields;
    }

    @Override
    public Optional<FieldInfo> findField(String xml, String fieldName) {
        return getFields(xml).stream()
                .filter(field -> field.getName().equalsIgnoreCase(fieldName))
                .findFirst();
    }

    @Override
    public Optional<String> getOverviewDescription(String xml) {
        Document document = parseXml(xml);
        NodeList ovdescNodes = document.getElementsByTagName("ovdesc");
        if (ovdescNodes.getLength() == 0) {
            return Optional.empty();
        }

        NodeList paragraphs = ((Element) ovdescNodes.item(0)).getElementsByTagName("p");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            String text = TextNormalizer.clean(paragraphs.item(i).getTextContent());
            if (text != null && !text.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text);
            }
        }

        String result = sb.toString();
        return result.isBlank() ? Optional.empty() : Optional.of(result);
    }

    private static String firstChildText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        return TextNormalizer.clean(nodes.item(0).getTextContent());
    }
}
