package backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Corps de {@code PUT /api/chats/{id}/context} : fixe ou change le module/table/champ T24 associé
 * à une conversation (voir le parcours à bulles de la page /chatbot). Distinct de {@link ChatDTO}
 * pour ne pas exiger {@code title}/{@code userId} sur une simple mise à jour de contexte.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatContextUpdateDTO {

    @NotBlank
    @Size(max = 50)
    private String module;

    /** Optionnelle : absente tant que l'étape 2 (sélection de table) n'est pas encore confirmée. */
    @Size(max = 255)
    private String table;

    /** Optionnelle : absente si l'utilisateur a choisi "Ignorer" à l'étape 3. */
    @Size(max = 255)
    private String field;
}
