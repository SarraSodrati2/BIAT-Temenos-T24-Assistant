package backend.service;

import backend.dto.ModuleT24DTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ModuleT24Service {

    Page<ModuleT24DTO> findAll(Pageable pageable);

    ModuleT24DTO findById(Long id);

    ModuleT24DTO create(ModuleT24DTO dto);

    ModuleT24DTO update(Long id, ModuleT24DTO dto);

    void delete(Long id);
}
