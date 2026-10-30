package backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatDTO {

    private Long id;

    @NotBlank
    @Size(max = 255)
    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @NotNull
    private Long userId;

    /** Nombre de messages (USER + ASSISTANT) — pour l'affichage dans la liste des conversations. */
    private Long messageCount;

    /** Contexte T24 choisi via le parcours à bulles de /chatbot (module/table/champ), tous nullable
     * tant qu'aucune sélection n'a été faite ou confirmée (voir ChatContextUpdateDTO). */
    private String selectedModule;
    private String selectedTable;
    private String selectedField;
}
