package backend.security;

import backend.entity.User;
import backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Résout l'utilisateur actuellement authentifié (peuplé par {@link JwtAuthenticationFilter} dans le
 * {@code SecurityContext}) vers l'entité {@link User} Oracle correspondante. Centralise une logique
 * auparavant dupliquée dans {@code ConversationRecorderImpl} — réutilisée ici par les vérifications
 * d'autorisation métier ({@code UserServiceImpl}, {@code ChatServiceImpl}, {@code
 * ChatHistoryServiceImpl}) qu'un simple {@code hasAnyRole(...)} au niveau route ne peut pas
 * exprimer (ex. "un ADMIN ne peut agir que sur un CONSULTANT", "un CONSULTANT ne voit que ses
 * propres conversations").
 *
 * <p>Les logs {@code [DIAG]} sont conservés tels quels (temporaires, à retirer une fois validés en
 * conditions réelles — voir la session de debug qui a motivé leur ajout dans {@code
 * ConversationRecorderImpl}, dont ils sont issus).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrentUserResolver {

    private final UserRepository userRepository;

    public Optional<User> resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        log.info("[DIAG] authentication = {}", authentication);
        if (authentication != null) {
            log.info("[DIAG] authentication.getClass()={}, getName()='{}', isAuthenticated()={}, getPrincipal()={}",
                    authentication.getClass().getName(), authentication.getName(),
                    authentication.isAuthenticated(), authentication.getPrincipal());
        }

        if (authentication == null) {
            log.warn("SecurityContext vide : aucun en-tête Authorization Bearer valide sur cette requête.");
            return Optional.empty();
        }
        if (!authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            log.warn("Authentification anonyme/non authentifiée ({}).", authentication.getClass().getSimpleName());
            return Optional.empty();
        }

        String username = authentication.getName();
        Optional<User> user = userRepository.findByUsername(username);
        if (user.isEmpty()) {
            log.warn("Utilisateur authentifié '{}' introuvable dans la table USERS.", username);
        }
        return user;
    }
}
