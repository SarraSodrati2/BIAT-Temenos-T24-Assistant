package backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;

/**
 * NonTransientAiException / TransientAiException viennent de Spring AI (org.springframework.ai.retry) :
 * ce sont les exceptions réellement levées par le client Ollama selon que l'erreur HTTP amont est
 * 4xx (non réessayable, ex. 404 modèle non trouvé) ou 5xx (réessayable, ex. 500/503 côté serveur Ollama).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ChatbotException.class)
    public ResponseEntity<ErrorResponse> handleChatbotException(ChatbotException ex, HttpServletRequest request) {
        log.error("Échec du pipeline chatbot : {}", ex.getMessage(), ex);
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.BAD_REQUEST.value(), "Bad Request", message, request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * {@link AccessDeniedException} levée manuellement dans un service (ex. {@code UserServiceImpl},
     * {@code ChatServiceImpl} — un ADMIN qui tente d'agir sur un autre ADMIN/SUPER_ADMIN, un
     * CONSULTANT qui tente de lire les conversations d'un autre utilisateur) est interceptée ici,
     * PAS par {@code RestSecurityErrorHandler} : cette dernière ne couvre que les refus décidés dans
     * la chaîne de filtres Spring Security elle-même (ex. {@code hasAnyRole(...)} dans
     * {@code SecurityConfig}), avant d'atteindre le {@code DispatcherServlet}. Une exception levée
     * depuis un contrôleur/service est résolue ici en premier ; sans ce handler explicite, elle
     * tomberait dans {@link #handleGeneric} (500 générique) au lieu d'un 403 propre.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Accès refusé sur {} : {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Couvre {@code BadCredentialsException}/{@code DisabledException}, etc. levées par
     * {@code AuthenticationManager.authenticate(...)} dans {@code AuthController.login} : c'est un
     * appel explicite dans un contrôleur, donc — contrairement aux échecs d'authentification/
     * d'autorisation dans la chaîne de filtres Spring Security (voir {@code RestSecurityErrorHandler})
     * — cette exception passe bien par {@code @RestControllerAdvice}.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Échec d'authentification sur {} : {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                "Identifiants invalides.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<ErrorResponse> handleAiClientError(NonTransientAiException ex, HttpServletRequest request) {
        log.warn("Erreur non réessayable côté fournisseur IA : {}", ex.getMessage());
        String raw = ex.getMessage() != null ? ex.getMessage() : "";

        HttpStatus status;
        String message;
        if (raw.contains("404")) {
            status = HttpStatus.BAD_GATEWAY;
            message = "Modèle Ollama introuvable en local. Lancez : ollama pull llama3";
        } else if (raw.contains("Connection refused") || raw.contains("connect")) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Impossible de joindre Ollama. Vérifiez qu'il est démarré : ollama serve";
        } else {
            status = HttpStatus.BAD_GATEWAY;
            message = "La requête envoyée à Ollama a été rejetée.";
        }

        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<ErrorResponse> handleAiServerError(TransientAiException ex, HttpServletRequest request) {
        log.warn("Erreur réessayable côté fournisseur IA : {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.BAD_GATEWAY.value(), "Bad Gateway",
                "Le service Ollama est temporairement indisponible. Réessayez.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleAiTimeout(ResourceAccessException ex, HttpServletRequest request) {
        log.warn("Timeout / erreur réseau vers le fournisseur IA : {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.GATEWAY_TIMEOUT.value(), "Gateway Timeout",
                "Le service Ollama n'a pas répondu à temps (vérifiez qu'il est lancé : ollama serve).", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Erreur non gérée sur {} : {}", request.getRequestURI(), ex.getMessage(), ex);
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
