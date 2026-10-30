package backend.service;

import backend.dto.ChatAskResponse;

/**
 * Répond à une question utilisateur en s'appuyant uniquement sur le contexte
 * construit par {@link ContextBuilderService} (jamais d'accès direct à Oracle ou au disque ici).
 */
public interface ChatbotService {

    ChatAskResponse ask(String question);

    /**
     * @param chatId conversation active du client (voir {@code ChatAskRequest#chatId}), ou {@code null}
     * pour l'ancien comportement (conversation la plus récente de l'utilisateur).
     */
    default ChatAskResponse ask(String question, Long chatId) {
        return ask(question);
    }
}
