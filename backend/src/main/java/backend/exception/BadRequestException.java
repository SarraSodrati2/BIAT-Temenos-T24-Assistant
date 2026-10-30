package backend.exception;

/** Requête syntaxiquement valide mais rejetée pour une raison métier (400 Bad Request). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
