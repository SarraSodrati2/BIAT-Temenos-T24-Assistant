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
 * Documentation HELP.TEXT T24, une ligne par fichier XML importé depuis
 * {@code src/main/resources/helptext/}. Mappe telle quelle la table Oracle HELP_TEXT
 * (colonnes ID, PRODUCT, TABLE_NAME, FILE_NAME, DATA, CREATED_AT).
 */
@Entity
@Table(name = "help_text", indexes = {
        @Index(name = "idx_help_text_table_name", columnList = "table_name"),
        @Index(name = "idx_help_text_product", columnList = "product"),
        @Index(name = "idx_help_text_file_name", columnList = "file_name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HelpText {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product", length = 100)
    private String product;

    @Column(name = "table_name", length = 255)
    private String tableName;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Lob
    @Column(name = "data")
    private String data;

    /**
     * SHA-256 (hexadécimal) du XML importé : permet à {@code HelpTextImportRunner} de détecter un fichier
     * modifié sans relire les CLOB, y compris une modification qui garde exactement la même longueur.
     * Colonne ajoutée par {@code db/help_text_content_hash.sql} ; nulle pour les lignes importées avant.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
