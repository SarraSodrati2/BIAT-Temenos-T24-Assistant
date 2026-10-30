package backend.exception;

/** Échec métier du pipeline chatbot (recherche Oracle ou construction du contexte/prompt). */
public class ChatbotException extends RuntimeException {

    public ChatbotException(String message) {
        super(message);
    }

    public ChatbotException(String message, Throwable cause) {
        super(message, cause);
    }
}
