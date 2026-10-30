package backend.repository;

import backend.entity.HelpField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Accès Oracle à la table HELP_FIELD (champs T24 extraits des documents HELP_TEXT).
 */
public interface HelpFieldRepository extends JpaRepository<HelpField, Long> {

    List<HelpField> findByTableNameIgnoreCase(String tableName);

    List<HelpField> findByFieldNameIgnoreCase(String fieldName);

    List<HelpField> findByHelpTextId(Long helpTextId);

    @Modifying
    @Query("DELETE FROM HelpField f WHERE f.helpTextId = :helpTextId")
    int deleteByHelpTextId(@Param("helpTextId") Long helpTextId);

    /**
     * Recherche {@code keyword} (insensible à la casse) dans FIELD_NAME, DESCRIPTION, TABLE_NAME
     * et PRODUCT, 20 premiers résultats.
     *
     * <p>Requête SQL native plutôt que JPQL : Hibernate 6 rejette {@code LOWER()}/{@code UPPER()}
     * sur une colonne mappée en CLOB (DESCRIPTION) dès la validation sémantique de la requête HQL
     * ({@code FunctionArgumentException: has type 'STRING', but argument is ... mapped to 'CLOB'}),
     * alors qu'Oracle supporte nativement ces fonctions sur un CLOB. Le SQL natif contourne ce
     * contrôle HQL trop strict sans changer le comportement recherché.
     *
     * <p>{@code LOWER()} plutôt que {@code UPPER()} : c'est la fonction que Spring Data JPA génère
     * pour les méthodes dérivées {@code IgnoreCase} (ex. {@link #findByFieldNameIgnoreCase}) ;
     * garder la même fonction ici permet aux index Oracle basés sur {@code LOWER(colonne)} de
     * potentiellement servir les deux types de requêtes.
     */
    @Query(value = "SELECT * FROM help_field hf WHERE "
            + "LOWER(hf.field_name) LIKE LOWER('%' || :keyword || '%') OR "
            + "LOWER(hf.description) LIKE LOWER('%' || :keyword || '%') OR "
            + "LOWER(hf.table_name) LIKE LOWER('%' || :keyword || '%') OR "
            + "LOWER(hf.product) LIKE LOWER('%' || :keyword || '%') "
            + "FETCH FIRST 20 ROWS ONLY",
            nativeQuery = true)
    List<HelpField> searchByKeyword(@Param("keyword") String keyword);

    /**
     * Même recherche que {@link #searchByKeyword} (FIELD_NAME, DESCRIPTION, TABLE_NAME), limitée aux
     * champs du module {@code product} AVANT le plafond de 20 lignes : sans ce filtre en base, les 20
     * premières lignes d'un terme courant appartiennent presque toujours à d'autres modules et le canal
     * mots-clés ne remontait jamais rien du module actif.
     */
    @Query(value = "SELECT * FROM help_field hf WHERE LOWER(hf.product) = LOWER(:product) AND ("
            + "LOWER(hf.field_name) LIKE LOWER('%' || :keyword || '%') OR "
            + "LOWER(hf.description) LIKE LOWER('%' || :keyword || '%') OR "
            + "LOWER(hf.table_name) LIKE LOWER('%' || :keyword || '%')) "
            + "FETCH FIRST 20 ROWS ONLY",
            nativeQuery = true)
    List<HelpField> searchByKeywordInProduct(@Param("keyword") String keyword, @Param("product") String product);

    /** Nombre de champs documentés d'un module (PRODUCT = code du module, ex. "TT"). */
    long countByProductIgnoreCase(String product);

    /** Champs distincts (table + nom) : une table importée deux fois (fichiers "X,ISB.xml") n'est comptée qu'une fois. */
    @Query(value = "SELECT COUNT(DISTINCT hf.table_name || '|' || hf.field_name) FROM help_field hf WHERE LOWER(hf.product) = LOWER(:product)",
            nativeQuery = true)
    long countDistinctFieldsByProduct(@Param("product") String product);

    @Query(value = "SELECT COUNT(DISTINCT hf.table_name) FROM help_field hf WHERE LOWER(hf.product) = LOWER(:product)",
            nativeQuery = true)
    long countDistinctTablesByProduct(@Param("product") String product);

    /**
     * Champs d'un module dont le NOM ou la DESCRIPTION (règles de validation comprises) contient
     * {@code term} (insensible à la casse) — recherche de sous-chaîne exhaustive sur le module, sans le
     * plafond de "20 premiers résultats" de {@link #searchByKeyword} : {@code FETCH FIRST 400} n'est
     * qu'un garde-fou contre un terme trivial, et l'appelant signale explicitement un dépassement.
     * SQL natif pour la même raison que {@link #searchByKeyword} (LOWER sur un CLOB).
     */
    @Query(value = "SELECT * FROM help_field hf WHERE LOWER(hf.product) = LOWER(:product) AND ("
            + "LOWER(hf.field_name) LIKE LOWER('%' || :term || '%') OR "
            + "LOWER(hf.description) LIKE LOWER('%' || :term || '%')) "
            + "ORDER BY hf.table_name, hf.field_name FETCH FIRST 400 ROWS ONLY",
            nativeQuery = true)
    List<HelpField> searchByTermInProduct(@Param("product") String product, @Param("term") String term);

    /** Même recherche que {@link #searchByTermInProduct} sur les seuls NOMS de champ, tous modules confondus. */
    @Query(value = "SELECT * FROM help_field hf WHERE LOWER(hf.field_name) LIKE LOWER('%' || :term || '%') "
            + "ORDER BY hf.product, hf.table_name, hf.field_name FETCH FIRST 200 ROWS ONLY",
            nativeQuery = true)
    List<HelpField> searchFieldNamesAnywhere(@Param("term") String term);

    /** Même recherche que {@link #searchByTermInProduct} sur les seules DESCRIPTIONS, tous modules confondus. */
    @Query(value = "SELECT * FROM help_field hf WHERE LOWER(hf.description) LIKE LOWER('%' || :term || '%') "
            + "ORDER BY hf.product, hf.table_name, hf.field_name FETCH FIRST 400 ROWS ONLY",
            nativeQuery = true)
    List<HelpField> searchDescriptionsAnywhere(@Param("term") String term);
}
