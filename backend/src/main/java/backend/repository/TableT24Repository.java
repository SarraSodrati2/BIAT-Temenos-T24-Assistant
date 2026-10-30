package backend.repository;

import backend.entity.TableT24;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TableT24Repository extends JpaRepository<TableT24, Long> {

    Page<TableT24> findByModuleId(Long moduleId, Pageable pageable);

    Page<TableT24> findByNomTableContainingIgnoreCase(String nomTable, Pageable pageable);

    Optional<TableT24> findFirstByNomTableIgnoreCase(String nomTable);

    Page<TableT24> findByModuleNomModuleIgnoreCase(String nomModule, Pageable pageable);
}
