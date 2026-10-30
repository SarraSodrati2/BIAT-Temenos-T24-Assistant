package backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Un champ T24 (balise {@code <t>} d'un document HELP.TEXT), une ligne par champ extrait
 * depuis {@code HELP_TEXT.DATA}. Mappe telle quelle la table Oracle HELP_FIELD (colonnes ID,
 * HELP_TEXT_ID, PRODUCT, TABLE_NAME, FIELD_NAME, DESCRIPTION, MANDATORY, FIELD_TYPE,
 * DEFAULT_VALUE, CREATED_AT). {@code help_text_id} est stocké comme simple clé étrangère
 * scalaire (pas de relation JPA) : ce loader ne fait qu'écrire, jamais de navigation objet
 * HelpField → HelpText n'est nécessaire côté application.
 */
@Entity
@Table(name = "help_field", indexes = {
        @Index(name = "idx_help_field_table_name", columnList = "table_name"),
        @Index(name = "idx_help_field_field_name", columnList = "field_name"),
        @Index(name = "idx_help_field_help_text_id", columnList = "help_text_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HelpField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "help_text_id", nullable = false)
    private Long helpTextId;

    @Column(name = "product", length = 100)
    private String product;

    @Column(name = "table_name", nullable = false, length = 255)
    private String tableName;

    @Column(name = "field_name", nullable = false, length = 255)
    private String fieldName;

    @Lob
    @Column(name = "description")
    private String description;

    @Column(name = "mandatory", length = 10)
    private String mandatory;

    @Column(name = "field_type", length = 100)
    private String fieldType;

    @Column(name = "default_value", length = 255)
    private String defaultValue;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
