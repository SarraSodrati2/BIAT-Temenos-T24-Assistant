package backend.service.impl;

import backend.dto.TableT24DTO;
import backend.entity.ModuleT24;
import backend.entity.TableT24;
import backend.exception.ResourceNotFoundException;
import backend.repository.ModuleT24Repository;
import backend.repository.TableT24Repository;
import backend.service.TableT24Service;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TableT24ServiceImpl implements TableT24Service {

    private final TableT24Repository tableT24Repository;
    private final ModuleT24Repository moduleT24Repository;

    @Override
    public Page<TableT24DTO> findAll(Pageable pageable) {
        return tableT24Repository.findAll(pageable).map(this::toDto);
    }

    @Override
    public Page<TableT24DTO> findByModule(Long moduleId, Pageable pageable) {
        return tableT24Repository.findByModuleId(moduleId, pageable).map(this::toDto);
    }

    @Override
    public Page<TableT24DTO> findByModuleCode(String moduleCode, Pageable pageable) {
        return tableT24Repository.findByModuleNomModuleIgnoreCase(moduleCode, pageable).map(this::toDto);
    }

    @Override
    public Page<TableT24DTO> search(String keyword, Pageable pageable) {
        return tableT24Repository.findByNomTableContainingIgnoreCase(keyword, pageable).map(this::toDto);
    }

    @Override
    public TableT24DTO findById(Long id) {
        return toDto(getEntity(id));
    }

    @Override
    public TableT24DTO findByNomTable(String nomTable) {
        return tableT24Repository.findFirstByNomTableIgnoreCase(nomTable)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("TableT24 not found: " + nomTable));
    }

    @Override
    @Transactional
    public TableT24DTO create(TableT24DTO dto) {
        ModuleT24 module = moduleT24Repository.findById(dto.getModuleId())
                .orElseThrow(() -> new ResourceNotFoundException("ModuleT24 not found: " + dto.getModuleId()));
        TableT24 table = new TableT24();
        table.setNomTable(dto.getNomTable());
        table.setData(dto.getData());
        table.setModule(module);
        return toDto(tableT24Repository.save(table));
    }

    @Override
    @Transactional
    public TableT24DTO update(Long id, TableT24DTO dto) {
        TableT24 table = getEntity(id);
        table.setNomTable(dto.getNomTable());
        table.setData(dto.getData());
        return toDto(tableT24Repository.save(table));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        tableT24Repository.delete(getEntity(id));
    }

    private TableT24 getEntity(Long id) {
        return tableT24Repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TableT24 not found: " + id));
    }

    private TableT24DTO toDto(TableT24 table) {
        return new TableT24DTO(table.getId(), table.getNomTable(), table.getData(),
                table.getCreatedAt(), table.getUpdatedAt(), table.getModule().getId());
    }
}
