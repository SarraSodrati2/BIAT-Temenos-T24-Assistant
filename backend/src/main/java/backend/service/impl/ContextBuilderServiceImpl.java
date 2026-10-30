package backend.service.impl;

import backend.dto.FieldInfo;
import backend.dto.ModuleT24DTO;
import backend.dto.TableT24DTO;
import backend.exception.ResourceNotFoundException;
import backend.service.ContextBuilderService;
import backend.service.ModuleT24Service;
import backend.service.TableT24Service;
import backend.service.XmlParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContextBuilderServiceImpl implements ContextBuilderService {

    private static final String SEPARATOR = "==================================";
    private static final String FIELD_SEPARATOR = "----------------------------------";

    private final TableT24Service tableT24Service;
    private final ModuleT24Service moduleT24Service;
    private final XmlParserService xmlParserService;

    @Override
    public String buildContext(String nomTable) {
        TableT24DTO table = tableT24Service.findByNomTable(nomTable);
        ModuleT24DTO module = moduleT24Service.findById(table.getModuleId());
        List<FieldInfo> fields = xmlParserService.getFields(table.getData());

        StringBuilder sb = new StringBuilder();
        sb.append(SEPARATOR).append("\n\n");
        sb.append("Module : ").append(module.getNomModule()).append("\n\n");
        sb.append("Table : ").append(table.getNomTable()).append("\n\n");
        sb.append("Nombre de champs : ").append(fields.size()).append("\n\n");
        sb.append("Liste des champs :\n\n");

        for (FieldInfo field : fields) {
            sb.append("- ").append(field.getName()).append("\n\n");
            sb.append("Description :\n\n");
            sb.append(orEmpty(field.getDescription())).append("\n\n");
            sb.append(FIELD_SEPARATOR).append("\n\n");
        }

        sb.append(SEPARATOR);
        return sb.toString();
    }

    @Override
    public String buildFieldContext(String nomTable, String fieldName) {
        TableT24DTO table = tableT24Service.findByNomTable(nomTable);
        FieldInfo field = xmlParserService.findField(table.getData(), fieldName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Field not found: " + fieldName + " in table " + nomTable));

        StringBuilder sb = new StringBuilder();
        sb.append("Table :\n\n").append(table.getNomTable()).append("\n\n");
        sb.append("Champ :\n\n").append(field.getName()).append("\n\n");
        sb.append("Description :\n\n").append(orEmpty(field.getDescription()));
        return sb.toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
