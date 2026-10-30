package backend.service;

import backend.dto.ConversationRecordingResult;

/**
 * Persiste une conversation dans {@code CHATS}/{@code CHAT_HISTORY} (via {@link ChatService} et
 * {@link ChatHistoryService}, inchangés) pour l'utilisateur actuellement authentifié
 * (Spring Security). Best-effort : un échec de persistance ne doit jamais empêcher le pipeline RAG
 * de répondre. Distingue explicitement le cas "authentification absente/invalide/expirée" (signal
 * exploitable par le frontend, voir {@link ConversationRecordingResult#isAuthenticationRequired()})
 * d'un simple échec interne (silencieux, comme avant).
 */
public interface ConversationRecorder {

    /**
     * Résout/crée le chat de l'utilisateur connecté et y enregistre {@code question} avec
     * {@code MESSAGE_TYPE = USER}.
     *
     * @return le résultat de la tentative de persistance ; si {@link ConversationRecordingResult#isPersisted()}
     * est faux, {@link #recordAssistantMessage} ne doit pas être appelé.
     */
    ConversationRecordingResult recordUserMessage(String question);

    /**
     * Comme {@link #recordUserMessage(String)}, mais rattache la question à la conversation
     * {@code chatId} (si elle appartient à l'utilisateur) au lieu de la plus récente. Si le dernier
     * message de cette conversation est déjà cette même question, restée sans réponse (tentative
     * précédente interrompue, expirée ou relancée), il est réutilisé au lieu d'être dupliqué.
     */
    default ConversationRecordingResult recordUserMessage(String question, Long chatId) {
        return recordUserMessage(question);
    }

    /**
     * Enregistre {@code answer} dans le chat {@code chatId} avec {@code MESSAGE_TYPE = ASSISTANT}.
     *
     * @return l'id CHAT_HISTORY du message créé (voir ChatAskResponse), ou {@code null} si la
     * persistance a échoué (best-effort, jamais propagé comme erreur).
     */
    Long recordAssistantMessage(Long chatId, String answer);
}
