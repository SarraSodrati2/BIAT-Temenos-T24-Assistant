package backend.dto;

import lombok.Getter;

/**
 * Résultat de {@link backend.service.ConversationRecorder#recordUserMessage}. Distingue
 * explicitement deux causes de non-persistance : une authentification absente/invalide/expirée
 * ({@code authenticationRequired = true}, le frontend doit inviter l'utilisateur à se reconnecter)
 * d'une erreur interne (Oracle indisponible, etc. — {@code authenticationRequired = false},
 * persistance best-effort silencieuse comme avant, le pipeline RAG n'est jamais affecté).
 */
@Getter
public class ConversationRecordingResult {

    private final Long chatId;
    /** Id CHAT_HISTORY de la question persistée, pour permettre au frontend de la modifier/supprimer
     * plus tard (voir ChatAskResponse) ; null si non persistée. */
    private final Long userMessageId;
    private final boolean authenticationRequired;

    private ConversationRecordingResult(Long chatId, Long userMessageId, boolean authenticationRequired) {
        this.chatId = chatId;
        this.userMessageId = userMessageId;
        this.authenticationRequired = authenticationRequired;
    }

    public static ConversationRecordingResult persisted(Long chatId, Long userMessageId) {
        return new ConversationRecordingResult(chatId, userMessageId, false);
    }

    /** Non authentifié (ou JWT invalide/expiré) : rien n'a été tenté côté persistance. */
    public static ConversationRecordingResult authenticationRequired() {
        return new ConversationRecordingResult(null, null, true);
    }

    /** Authentifié mais échec interne (ex. Oracle indisponible) : ce n'est pas un problème d'authentification. */
    public static ConversationRecordingResult notPersistedDueToInternalError() {
        return new ConversationRecordingResult(null, null, false);
    }

    public boolean isPersisted() {
        return chatId != null;
    }
}
