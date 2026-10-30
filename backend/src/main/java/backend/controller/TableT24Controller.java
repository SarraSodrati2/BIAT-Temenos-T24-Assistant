package backend.controller;

import backend.dto.FieldInfo;
import backend.dto.TableT24DTO;
import backend.exception.ResourceNotFoundException;
import backend.service.TableT24Service;
import backend.service.XmlParserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/table-t24")
@RequiredArgsConstructor
public class TableT24Controller {

    private final TableT24Service tableT24Service;
    private final XmlParserService xmlParserService;

    @GetMapping
    public ResponseEntity<Page<TableT24DTO>> findAll(@PageableDefault(size = 20, sort = "nomTable") Pageable pageable) {
        return ResponseEntity.ok(tableT24Service.findAll(pageable));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<TableT24DTO>> search(@RequestParam String keyword,
                                                      @PageableDefault(size = 20, sort = "nomTable") Pageable pageable) {
        return ResponseEntity.ok(tableT24Service.search(keyword, pageable));
    }

    @GetMapping("/module/{module}")
    public ResponseEntity<Page<TableT24DTO>> findByModule(@PathVariable String module,
                                                            @PageableDefault(size = 20, sort = "nomTable") Pageable pageable) {
        return ResponseEntity.ok(tableT24Service.findByModuleCode(module, pageable));
    }

    @GetMapping("/{nomTable}")
    public ResponseEntity<TableT24DTO> findByNomTable(@PathVariable String nomTable) {
        return ResponseEntity.ok(tableT24Service.findByNomTable(nomTable));
    }

    @GetMapping("/{nomTable}/fields")
    public ResponseEntity<List<FieldInfo>> getFields(@PathVariable String nomTable) {
        TableT24DTO table = tableT24Service.findByNomTable(nomTable);
        return ResponseEntity.ok(xmlParserService.getFields(table.getData()));
    }

    @GetMapping("/{nomTable}/field/{fieldName}")
    public ResponseEntity<FieldInfo> getField(@PathVariable String nomTable, @PathVariable String fieldName) {
        TableT24DTO table = tableT24Service.findByNomTable(nomTable);
        FieldInfo field = xmlParserService.findField(table.getData(), fieldName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Field not found: " + fieldName + " in table " + nomTable));
        return ResponseEntity.ok(field);
    }

    @PostMapping
    public ResponseEntity<TableT24DTO> create(@Valid @RequestBody TableT24DTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tableT24Service.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TableT24DTO> update(@PathVariable Long id, @Valid @RequestBody TableT24DTO dto) {
        return ResponseEntity.ok(tableT24Service.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        tableT24Service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
