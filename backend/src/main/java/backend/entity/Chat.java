package backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "chats", indexes = {
        @Index(name = "idx_chats_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Module T24 choisi via le parcours à bulles (ex. "AA"), null tant qu'aucun contexte n'a été fixé. */
    @Column(name = "selected_module", length = 50)
    private String selectedModule;

    /** Table T24 choisie (ex. "AA.ADVICE"), null tant qu'aucune table n'a été sélectionnée. */
    @Column(name = "selected_table", length = 255)
    private String selectedTable;

    /** Colonne/champ optionnel choisi (ex. "DESCRIPTION"), null si l'étape a été ignorée. */
    @Column(name = "selected_field", length = 255)
    private String selectedField;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Builder.Default
    @OneToMany(mappedBy = "chat", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<ChatHistory> chatHistories = new ArrayList<>();
}
