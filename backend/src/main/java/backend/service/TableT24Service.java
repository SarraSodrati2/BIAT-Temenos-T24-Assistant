package backend.service;

import backend.dto.TableT24DTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TableT24Service {

    Page<TableT24DTO> findAll(Pageable pageable);

    Page<TableT24DTO> findByModule(Long moduleId, Pageable pageable);

    Page<TableT24DTO> findByModuleCode(String moduleCode, Pageable pageable);

    Page<TableT24DTO> search(String keyword, Pageable pageable);

    TableT24DTO findById(Long id);

    TableT24DTO findByNomTable(String nomTable);

    TableT24DTO create(TableT24DTO dto);

    TableT24DTO update(Long id, TableT24DTO dto);

    void delete(Long id);
}
