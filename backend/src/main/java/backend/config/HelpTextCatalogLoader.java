package backend.config;

import backend.entity.ModuleT24;
import backend.entity.TableT24;
import backend.repository.ModuleT24Repository;
import backend.repository.TableT24Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Populates ModuleT24/TableT24 from the real T24 HELP.TEXT export bundled under
 * src/main/resources/repository/&lt;MODULE&gt;/&lt;TABLE&gt;.xml, once, on first startup.
 * The raw XML content of each file is stored as-is in TableT24.data (CLOB); only the
 * module code and table name are extracted (from the &lt;product&gt;/&lt;table&gt; header
 * tags, falling back to the folder/file name) to file it under the right module.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelpTextCatalogLoader implements CommandLineRunner {

    private static final String PATTERN = "classpath*:repository/*/*.xml";

    private final ModuleT24Repository moduleT24Repository;
    private final TableT24Repository tableT24Repository;
    private final Map<String, ModuleT24> moduleCache = new HashMap<>();

    @Override
    public void run(String... args) throws Exception {
        if (moduleT24Repository.count() > 0) {
            log.info("Catalog already populated, skipping HELP.TEXT import.");
            return;
        }

        long start = System.currentTimeMillis();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        int loaded = 0;
        for (Resource resource : resources) {
            String[] moduleAndTable = moduleAndFallbackTable(resource);
            if (importResource(resource, factory, moduleAndTable[0], moduleAndTable[1])) {
                loaded++;
            }
        }
        log.info("Loaded {} T24 tables from HELP.TEXT export in {} ms", loaded, System.currentTimeMillis() - start);
    }

    private boolean importResource(Resource resource, DocumentBuilderFactory factory,
                                    String fallbackModuleCode, String fallbackTableName) {
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readAllBytes();
            String rawXml = new String(bytes, StandardCharsets.UTF_8);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(resource.getInputStream());
            String product = firstTagText(document, "product");
            String tableName = firstTagText(document, "table");

            String moduleCode = (product != null && !product.isBlank()) ? product : fallbackModuleCode;
            String finalTableName = (tableName != null && !tableName.isBlank()) ? tableName : fallbackTableName;

            ModuleT24 module = moduleCache.computeIfAbsent(moduleCode, code ->
                    moduleT24Repository.findByNomModule(code).orElseGet(() -> {
                        ModuleT24 m = new ModuleT24();
                        m.setNomModule(code);
                        return moduleT24Repository.save(m);
                    }));

            TableT24 table = new TableT24();
            table.setNomTable(finalTableName);
            table.setData(rawXml);
            table.setModule(module);
            tableT24Repository.save(table);
            return true;
        } catch (Exception e) {
            log.warn("Skipped unparsable HELP.TEXT file {}: {}", resource.getFilename(), e.getMessage());
            return false;
        }
    }

    private static String firstTagText(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    private static String[] moduleAndFallbackTable(Resource resource) throws Exception {
        String path = resource.getURI().toString().replace('\\', '/');
        String afterRepository = path.substring(path.lastIndexOf("repository/") + "repository/".length());
        String[] parts = afterRepository.split("/");
        String moduleCode = parts[0];
        String fileName = parts[parts.length - 1];
        String tableName = fileName.endsWith(".xml") ? fileName.substring(0, fileName.length() - 4) : fileName;
        return new String[]{moduleCode, tableName};
    }
}
