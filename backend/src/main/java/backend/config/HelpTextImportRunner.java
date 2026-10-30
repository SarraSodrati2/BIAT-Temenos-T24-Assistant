package backend.config;

import backend.entity.HelpText;
import backend.service.HelpFieldService;
import backend.service.HelpTextService;
import backend.service.XmlParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Importe automatiquement, au démarrage de Spring Boot, tous les fichiers XML HELP.TEXT
 * placés sous {@code src/main/resources/helptext/} (parcours récursif via {@code classpath*:helptext/**&#47;*.xml},
 * quelle que soit la profondeur, sans filtre de dossier ni d'extension autre que {@code .xml})
 * dans la table Oracle HELP_TEXT.
 *
 * <p>Le XML complet de chaque fichier est stocké tel quel dans {@code HelpText.data} (CLOB) ;
 * {@code product} et {@code table} sont extraits des balises {@code <product>}/{@code <table>}
 * du fichier (mêmes balises que les exports HELP.TEXT déjà utilisés par
 * {@link HelpTextCatalogLoader}), avec repli sur l'arborescence de dossiers/le nom de fichier
 * si ces balises sont absentes ou vides.
 *
 * <p>Idempotent au niveau fichier : chaque fichier est ignoré s'il existe déjà en base
 * ({@code HelpTextRepository.existsByFileName}), pas au niveau table. Ce choix est délibéré :
 * avec Spring Boot DevTools actif, un redémarrage automatique peut se déclencher alors que
 * {@code target/classes/helptext} n'a reçu qu'une partie des ~6900 fichiers (copie Maven encore
 * en cours) ; un simple garde-fou {@code COUNT(*) > 0 ⇒ skip} figerait alors l'import de façon
 * permanente dès la première exécution partielle. Ici, chaque redémarrage complète l'import des
 * fichiers manquants sans dupliquer ceux déjà importés, jusqu'à couverture complète du dossier.
 *
 * <p>Fichier modifié : détecté par empreinte SHA-256 du contenu (colonne CONTENT_HASH, lue sans charger
 * les CLOB) — y compris une modification qui garde exactement la même longueur, que l'ancienne comparaison
 * par longueur ne voyait pas. Voir {@link #importResources}.
 *
 * <p>{@code @Order(1)} : s'exécute avant {@link HelpFieldImportRunner} (2) et
 * {@code VectorIndexRunner} (3), qui dépendent de ce qu'il écrit.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class HelpTextImportRunner implements CommandLineRunner {

    private static final String PATTERN = "classpath*:helptext/**/*.xml";
    private static final String ROOT_FOLDER = "helptext/";

    private final HelpTextService helpTextService;
    private final HelpFieldService helpFieldService;
    private final XmlParserService xmlParserService;

    @Override
    public void run(String... args) throws Exception {
        long start = System.currentTimeMillis();
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        ImportReport report = importResources(resources);
        log.info("Import HELP.TEXT terminé : {} dossier(s) parcouru(s), {} fichier(s) XML trouvé(s), {} importé(s), "
                        + "{} mis à jour (contenu modifié), {} empreinte(s) complétée(s), {} ignoré(s) (inchangés), "
                        + "{} erreur(s), {} ms.",
                report.folders(), resources.length, report.imported(), report.updated(), report.hashBackfilled(),
                report.unchanged(), report.errors(), System.currentTimeMillis() - start);
    }

    /** Bilan d'un passage d'import (voir {@link #importResources}). */
    record ImportReport(int folders, int imported, int updated, int hashBackfilled, int unchanged, int errors,
                        List<String> missingOnDisk) {
    }

    /**
     * Compare chaque fichier à la base par empreinte SHA-256 de son contenu (jamais par sa seule longueur) :
     * <ul>
     *   <li>fichier inconnu en base -&gt; importé, avec son empreinte ;</li>
     *   <li>même empreinte -&gt; rien n'est relu ni réécrit (aucun ré-embedding en aval) ;</li>
     *   <li>empreinte différente -&gt; ligne mise à jour et champs HELP_FIELD supprimés (ré-extraits par
     *   {@link HelpFieldImportRunner}, puis ré-embeddés par la synchronisation de l'index) ;</li>
     *   <li>ligne importée avant l'ajout de l'empreinte (CONTENT_HASH nul) -&gt; son contenu est comparé UNE fois
     *   au fichier : identique, seule l'empreinte est enregistrée ; différent, ré-import comme ci-dessus ;</li>
     *   <li>ligne en base dont le fichier n'existe plus -&gt; conservée et signalée (WARN), jamais supprimée
     *   automatiquement : une copie Maven partielle de {@code target/classes} (voir plus haut) ferait sinon
     *   disparaître des milliers de documents légitimes.</li>
     * </ul>
     */
    ImportReport importResources(Resource[] resources) {
        Set<String> foldersSeen = new HashSet<>();
        int imported = 0;
        int updated = 0;
        int backfilled = 0;
        int unchanged = 0;
        int errors = 0;
        Map<String, String> storedHashes = helpTextService.findContentHashesByFileName();
        Set<String> fileNamesSeen = new HashSet<>();

        for (Resource resource : resources) {
            foldersSeen.add(parentFolder(resource));
            try {
                if (!fileNamesSeen.add(resource.getFilename())) {
                    unchanged++; // même nom de fichier dans deux dossiers : seul le premier est importé (comportement historique).
                    continue;
                }
                String rawXml = readXml(resource);
                String hash = sha256(rawXml);
                if (!storedHashes.containsKey(resource.getFilename())) {
                    importResource(resource, rawXml, hash);
                    imported++;
                    continue;
                }
                String storedHash = storedHashes.get(resource.getFilename());
                if (hash.equals(storedHash)) {
                    unchanged++;
                    continue;
                }
                if (storedHash == null && backfillHashIfUnchanged(resource, rawXml, hash)) {
                    backfilled++;
                    continue;
                }
                reimportModified(resource, rawXml, hash);
                updated++;
            } catch (Exception e) {
                errors++;
                log.warn("Échec de l'import du fichier {} : {}", resource.getFilename(), e.getMessage());
            }
        }

        List<String> missingOnDisk = storedHashes.keySet().stream().filter(name -> !fileNamesSeen.contains(name)).sorted().toList();
        if (!missingOnDisk.isEmpty()) {
            log.warn("{} ligne(s) HELP_TEXT sans fichier XML correspondant sur le disque (ex. {}) : conservée(s), "
                    + "jamais supprimée(s) automatiquement.", missingOnDisk.size(), missingOnDisk.subList(0, Math.min(5, missingOnDisk.size())));
        }
        return new ImportReport(foldersSeen.size(), imported, updated, backfilled, unchanged, errors, missingOnDisk);
    }

    private void importResource(Resource resource, String rawXml, String hash) throws IOException {
        Document document = xmlParserService.parseXml(rawXml);
        String[] fallback = fallbackFromPath(resource);

        HelpText helpText = HelpText.builder()
                .product(firstNonBlank(firstTagText(document, "product"), fallback[0]))
                .tableName(firstNonBlank(firstTagText(document, "table"), fallback[1]))
                .fileName(resource.getFilename())
                .data(rawXml)
                .contentHash(hash)
                .build();
        helpTextService.save(helpText);
    }

    /** Ligne importée avant l'empreinte : enregistre seulement celle-ci si le contenu stocké est identique au fichier. */
    private boolean backfillHashIfUnchanged(Resource resource, String rawXml, String hash) {
        Optional<HelpText> existing = helpTextService.findByFileName(resource.getFilename());
        if (existing.isEmpty() || !rawXml.equals(existing.get().getData())) {
            return false;
        }
        existing.get().setContentHash(hash);
        helpTextService.save(existing.get());
        return true;
    }

    /** Met à jour la ligne HELP_TEXT existante et supprime ses champs, ré-extraits ensuite par {@link HelpFieldImportRunner}. */
    private void reimportModified(Resource resource, String rawXml, String hash) throws IOException {
        Optional<HelpText> existing = helpTextService.findByFileName(resource.getFilename());
        if (existing.isEmpty()) {
            importResource(resource, rawXml, hash);
            return;
        }
        Document document = xmlParserService.parseXml(rawXml);
        String[] fallback = fallbackFromPath(resource);
        HelpText helpText = existing.get();
        helpText.setProduct(firstNonBlank(firstTagText(document, "product"), fallback[0]));
        helpText.setTableName(firstNonBlank(firstTagText(document, "table"), fallback[1]));
        helpText.setData(rawXml);
        helpText.setContentHash(hash);
        helpTextService.save(helpText);
        int deleted = helpFieldService.deleteByHelpTextId(helpText.getId());
        log.info("Fichier XML modifié depuis son import : {} (table {}, module {}) mis à jour, {} champ(s) à ré-extraire.",
                resource.getFilename(), helpText.getTableName(), helpText.getProduct(), deleted);
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible dans cette JVM", e);
        }
    }

    private static String readXml(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String parentFolder(Resource resource) {
        try {
            String path = resource.getURI().toString().replace('\\', '/');
            int lastSlash = path.lastIndexOf('/');
            return lastSlash >= 0 ? path.substring(0, lastSlash) : path;
        } catch (IOException e) {
            return "?";
        }
    }

    private static String firstTagText(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    /**
     * Dérive un product/table de repli à partir du chemin du fichier
     * ({@code helptext/<PRODUCT>/.../<TABLE>.xml}), utilisé quand les balises XML
     * {@code <product>}/{@code <table>} sont absentes ou vides.
     */
    private static String[] fallbackFromPath(Resource resource) throws IOException {
        String path = resource.getURI().toString().replace('\\', '/');
        int rootIndex = path.lastIndexOf(ROOT_FOLDER);
        String afterRoot = rootIndex >= 0 ? path.substring(rootIndex + ROOT_FOLDER.length()) : resource.getFilename();

        String[] parts = afterRoot.split("/");
        String fileName = parts[parts.length - 1];
        String tableName = fileName.endsWith(".xml") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String product = parts.length > 1 ? parts[0] : "";
        return new String[]{product, tableName};
    }

    private static String firstNonBlank(String primary, String fallback) {
        return (primary != null && !primary.isBlank()) ? primary : fallback;
    }
}
