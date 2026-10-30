package backend.service;

import backend.entity.HelpField;

import java.util.List;

/**
 * Consultation et alimentation des champs T24 stockés en base (table HELP_FIELD).
 */
public interface HelpFieldService {

    /**
     * @return le nombre de lignes actuellement présentes dans HELP_FIELD (équivalent à
     * {@code SELECT COUNT(*) FROM HELP_FIELD}), utilisé par le loader pour éviter un double import.
     */
    long count();

    List<HelpField> saveAll(List<HelpField> helpFields);

    /** Supprime les champs extraits d'une ligne HELP_TEXT (avant ré-extraction d'un fichier XML modifié). */
    int deleteByHelpTextId(Long helpTextId);

    List<HelpField> findByTableName(String tableName);
}
