package backend.service.impl;

import backend.dto.ModuleT24DTO;
import backend.entity.ModuleT24;
import backend.exception.ResourceNotFoundException;
import backend.repository.ModuleT24Repository;
import backend.service.ModuleT24Service;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModuleT24ServiceImpl implements ModuleT24Service {

    private final ModuleT24Repository moduleT24Repository;

    @Override
    public Page<ModuleT24DTO> findAll(Pageable pageable) {
        return moduleT24Repository.findAll(pageable).map(this::toDto);
    }

    @Override
    public ModuleT24DTO findById(Long id) {
        return toDto(getEntity(id));
    }

    @Override
    @Transactional
    public ModuleT24DTO create(ModuleT24DTO dto) {
        ModuleT24 module = new ModuleT24();
        module.setNomModule(dto.getNomModule());
        return toDto(moduleT24Repository.save(module));
    }

    @Override
    @Transactional
    public ModuleT24DTO update(Long id, ModuleT24DTO dto) {
        ModuleT24 module = getEntity(id);
        module.setNomModule(dto.getNomModule());
        return toDto(moduleT24Repository.save(module));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        moduleT24Repository.delete(getEntity(id));
    }

    private ModuleT24 getEntity(Long id) {
        return moduleT24Repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ModuleT24 not found: " + id));
    }

    private ModuleT24DTO toDto(ModuleT24 module) {
        return new ModuleT24DTO(module.getId(), module.getNomModule());
    }
}
