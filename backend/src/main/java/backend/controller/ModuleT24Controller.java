package backend.controller;

import backend.dto.ModuleT24DTO;
import backend.service.ModuleT24Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/modules")
@RequiredArgsConstructor
public class ModuleT24Controller {

    private final ModuleT24Service moduleT24Service;

    @GetMapping
    public ResponseEntity<Page<ModuleT24DTO>> findAll(@PageableDefault(size = 20, sort = "nomModule") Pageable pageable) {
        return ResponseEntity.ok(moduleT24Service.findAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ModuleT24DTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(moduleT24Service.findById(id));
    }

    @PostMapping
    public ResponseEntity<ModuleT24DTO> create(@Valid @RequestBody ModuleT24DTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(moduleT24Service.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ModuleT24DTO> update(@PathVariable Long id, @Valid @RequestBody ModuleT24DTO dto) {
        return ResponseEntity.ok(moduleT24Service.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        moduleT24Service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
