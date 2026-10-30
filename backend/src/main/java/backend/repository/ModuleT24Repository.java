package backend.repository;

import backend.entity.ModuleT24;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModuleT24Repository extends JpaRepository<ModuleT24, Long> {

    Optional<ModuleT24> findByNomModule(String nomModule);
}
