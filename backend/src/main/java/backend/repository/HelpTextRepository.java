package backend.repository;

import backend.entity.HelpText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Accès Oracle à la table HELP_TEXT (documentation HELP.TEXT T24 importée en local).
 */
public interface HelpTextRepository extends JpaRepository<HelpText, Long> {

    List<HelpText> findByProductIgnoreCase(String product);

    Optional<HelpText> findFirstByTableNameIgnoreCase(String tableName);

    boolean existsByFileName(String fileName);

    Optional<HelpText> findFirstByFileName(String fileName);

    /**
     * (FILE_NAME, CONTENT_HASH) de chaque ligne, sans charger les CLOB : utilisé par {@code HelpTextImportRunner}
     * pour détecter un fichier XML modifié depuis son import (empreinte SHA-256, nulle pour une ligne importée
     * avant l'ajout de la colonne).
     */
    @Query("SELECT h.fileName, h.contentHash FROM HelpText h")
    List<Object[]> findFileNamesAndContentHashes();

    /** Lignes HELP_TEXT dont aucun champ n'a encore été extrait dans HELP_FIELD (nouveaux fichiers, fichiers modifiés). */
    @Query("SELECT h FROM HelpText h WHERE h.id NOT IN (SELECT DISTINCT f.helpTextId FROM HelpField f)")
    List<HelpText> findAllWithoutFields();
}
