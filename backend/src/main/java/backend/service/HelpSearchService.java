package backend.service;

import backend.entity.HelpField;

import java.util.List;

/**
 * Recherche de champs T24 dans HELP_FIELD (FIELD_NAME, TABLE_NAME, DESCRIPTION, PRODUCT),
 * insensible à la casse.
 */
public interface HelpSearchService {

    List<HelpField> findByFieldName(String fieldName);

    List<HelpField> findByTableName(String tableName);

    /** Recherche floue multi-colonnes (FIELD_NAME, DESCRIPTION, TABLE_NAME, PRODUCT), 20 résultats max. */
    List<HelpField> search(String keyword);

    /** Comme {@link #search}, restreinte aux champs du module/produit {@code product} avant le plafond de résultats. */
    List<HelpField> searchInProduct(String keyword, String product);

    /** Meilleur résultat pour {@code keyword} : correspondance exacte de champ si possible, sinon repli sur {@link #search}. */
    List<HelpField> findTopFields(String keyword);
}
