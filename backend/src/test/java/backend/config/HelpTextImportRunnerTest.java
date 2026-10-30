package backend.config;

import backend.entity.HelpText;
import backend.service.HelpFieldService;
import backend.service.HelpTextService;
import backend.service.impl.XmlParserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpTextImportRunnerTest {

    private static final String XML = "<HELP.TEXT><product>DE</product><table>DE.FORM.TYPE</table>"
            + "<t><field>FORM.WIDTH</field><desc>Specifies the number of characters across a print line.</desc></t></HELP.TEXT>";

    @Mock
    private HelpTextService helpTextService;
    @Mock
    private HelpFieldService helpFieldService;

    @TempDir
    Path tempDir;

    private HelpTextImportRunner runner;

    @BeforeEach
    void setUp() {
        runner = new HelpTextImportRunner(helpTextService, helpFieldService, new XmlParserServiceImpl());
    }

    private Resource xmlFile(String name, String content) throws IOException {
        Path file = tempDir.resolve("helptext").resolve("DE").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new FileSystemResource(file);
    }

    private static HelpText row(String content, String hash) {
        HelpText row = HelpText.builder().id(7L).product("DE").tableName("DE.FORM.TYPE").fileName("DE.FORM.TYPE.xml")
                .data(content).contentHash(hash).build();
        return row;
    }

    @Test
    void unchangedXml_sameHash_isNeitherReadFromTheDatabaseNorRewritten() throws IOException {
        when(helpTextService.findContentHashesByFileName()).thenReturn(Map.of("DE.FORM.TYPE.xml", HelpTextImportRunner.sha256(XML)));

        HelpTextImportRunner.ImportReport report = runner.importResources(new Resource[]{xmlFile("DE.FORM.TYPE.xml", XML)});

        assertThat(report.unchanged()).isEqualTo(1);
        assertThat(report.updated()).isZero();
        verify(helpTextService, never()).save(any());
        verify(helpTextService, never()).findByFileName(any());
        verify(helpFieldService, never()).deleteByHelpTextId(anyLong());
    }

    @Test
    void modifiedXml_withExactlyTheSameLength_isDetectedAndReimported() throws IOException {
        String modified = XML.replace("print line", "PRINT LINE"); // même longueur, contenu différent
        assertThat(modified).hasSameSizeAs(XML).isNotEqualTo(XML);
        when(helpTextService.findContentHashesByFileName()).thenReturn(Map.of("DE.FORM.TYPE.xml", HelpTextImportRunner.sha256(XML)));
        when(helpTextService.findByFileName("DE.FORM.TYPE.xml")).thenReturn(Optional.of(row(XML, HelpTextImportRunner.sha256(XML))));

        HelpTextImportRunner.ImportReport report = runner.importResources(new Resource[]{xmlFile("DE.FORM.TYPE.xml", modified)});

        assertThat(report.updated()).isEqualTo(1);
        ArgumentCaptor<HelpText> saved = ArgumentCaptor.forClass(HelpText.class);
        verify(helpTextService).save(saved.capture());
        assertThat(saved.getValue().getData()).isEqualTo(modified);
        assertThat(saved.getValue().getContentHash()).isEqualTo(HelpTextImportRunner.sha256(modified));
        // Champs supprimés : ré-extraits par HelpFieldImportRunner, puis ré-embeddés par la synchronisation d'index.
        verify(helpFieldService).deleteByHelpTextId(7L);
    }

    @Test
    void newXml_isImportedWithItsHash() throws IOException {
        when(helpTextService.findContentHashesByFileName()).thenReturn(Map.of());

        HelpTextImportRunner.ImportReport report = runner.importResources(new Resource[]{xmlFile("DE.FORM.TYPE.xml", XML)});

        assertThat(report.imported()).isEqualTo(1);
        ArgumentCaptor<HelpText> saved = ArgumentCaptor.forClass(HelpText.class);
        verify(helpTextService).save(saved.capture());
        assertThat(saved.getValue().getProduct()).isEqualTo("DE");
        assertThat(saved.getValue().getTableName()).isEqualTo("DE.FORM.TYPE");
        assertThat(saved.getValue().getContentHash()).isEqualTo(HelpTextImportRunner.sha256(XML));
    }

    @Test
    void xmlDeletedFromDisk_isReported_butItsRowIsNeverDeletedAutomatically() throws IOException {
        Map<String, String> stored = new HashMap<>();
        stored.put("DE.FORM.TYPE.xml", HelpTextImportRunner.sha256(XML));
        stored.put("DE.GONE.xml", "abc");
        when(helpTextService.findContentHashesByFileName()).thenReturn(stored);

        HelpTextImportRunner.ImportReport report = runner.importResources(new Resource[]{xmlFile("DE.FORM.TYPE.xml", XML)});

        assertThat(report.missingOnDisk()).containsExactly("DE.GONE.xml");
        verify(helpTextService, never()).save(any());
        verify(helpFieldService, never()).deleteByHelpTextId(anyLong());
    }

    @Test
    void rowImportedBeforeHashing_withIdenticalContent_onlyGetsItsHash_noReimport() throws IOException {
        Map<String, String> stored = new HashMap<>();
        stored.put("DE.FORM.TYPE.xml", null);
        when(helpTextService.findContentHashesByFileName()).thenReturn(stored);
        when(helpTextService.findByFileName("DE.FORM.TYPE.xml")).thenReturn(Optional.of(row(XML, null)));

        HelpTextImportRunner.ImportReport report = runner.importResources(new Resource[]{xmlFile("DE.FORM.TYPE.xml", XML)});

        assertThat(report.hashBackfilled()).isEqualTo(1);
        assertThat(report.updated()).isZero();
        ArgumentCaptor<HelpText> saved = ArgumentCaptor.forClass(HelpText.class);
        verify(helpTextService).save(saved.capture());
        assertThat(saved.getValue().getContentHash()).isEqualTo(HelpTextImportRunner.sha256(XML));
        verify(helpFieldService, never()).deleteByHelpTextId(anyLong());
    }

    @Test
    void rowImportedBeforeHashing_withDifferentContent_isReimported() throws IOException {
        Map<String, String> stored = new HashMap<>();
        stored.put("DE.FORM.TYPE.xml", null);
        when(helpTextService.findContentHashesByFileName()).thenReturn(stored);
        when(helpTextService.findByFileName("DE.FORM.TYPE.xml")).thenReturn(Optional.of(row("<old/>", null)));

        HelpTextImportRunner.ImportReport report = runner.importResources(new Resource[]{xmlFile("DE.FORM.TYPE.xml", XML)});

        assertThat(report.updated()).isEqualTo(1);
        verify(helpFieldService).deleteByHelpTextId(7L);
    }
}
