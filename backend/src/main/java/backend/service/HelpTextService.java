package backend.service;

import backend.entity.HelpText;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consultation et alimentation de la documentation HELP.TEXT stockée en base (table HELP_TEXT).
 */
public interface HelpTextService {

    /**
     * @return le nombre de lignes actuellement présentes dans HELP_TEXT (équivalent à
     * {@code SELECT COUNT(*) FROM HELP_TEXT}), utilisé par le loader pour éviter un double import.
     */
    long count();

    HelpText save(HelpText helpText);

    List<HelpText> findAll();

    /**
     * @return true si un fichier de ce nom a déjà été importé (utilisé par le loader pour
     * reprendre un import interrompu sans dupliquer les lignes déjà en base).
     */
    boolean existsByFileName(String fileName);

    /**
     * @return FILE_NAME -&gt; empreinte SHA-256 du XML stocké (valeur {@code null} pour une ligne importée avant
     * l'ajout de l'empreinte), pour détecter un fichier modifié sans charger les CLOB.
     */
    Map<String, String> findContentHashesByFileName();

    Optional<HelpText> findByFileName(String fileName);

    /** Lignes HELP_TEXT sans aucun champ HELP_FIELD extrait (voir {@code HelpFieldImportRunner}). */
    List<HelpText> findAllWithoutFields();

    List<HelpText> findByProduct(String product);

    Optional<HelpText> findByTableName(String tableName);
}
