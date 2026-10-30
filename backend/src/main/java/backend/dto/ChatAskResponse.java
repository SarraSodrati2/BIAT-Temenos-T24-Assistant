package backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatAskResponse {

    private String answer;

    /**
     * {@code true} si le JWT était absent/invalide/expiré : l'historique de cette conversation n'a
     * pas été enregistré et le frontend doit inviter l'utilisateur à se reconnecter. Le pipeline RAG
     * répond normalement dans tous les cas — ce champ ne concerne que la persistance.
     */
    private boolean authenticationRequired;

    /** Id CHAT_HISTORY de la question/réponse persistées (null si non authentifié ou échec de
     * persistance) — permet au frontend de proposer modifier/supprimer/copier sur ce message précis. */
    private Long userMessageId;
    private Long assistantMessageId;
}
