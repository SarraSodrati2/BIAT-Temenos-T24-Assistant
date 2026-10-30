package backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ChatAskRequest {

    @NotBlank
    @Size(max = 2000)
    private String question;

    /**
     * Conversation active côté client (optionnel, rétro-compatible). Sans lui, le backend rattachait
     * la question à la conversation la plus récemment modifiée de l'utilisateur — la mauvaise dès
     * qu'une conversation plus ancienne était rouverte. Ignoré (retour à l'ancien comportement) si la
     * conversation n'appartient pas à l'utilisateur authentifié.
     */
    private Long chatId;

    public ChatAskRequest(String question) {
        this.question = question;
    }

    public ChatAskRequest(String question, Long chatId) {
        this.question = question;
        this.chatId = chatId;
    }
}
