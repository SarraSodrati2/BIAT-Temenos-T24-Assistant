package backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lit l'en-tête {@code Authorization: Bearer <token>}, valide le JWT et peuple le
 * {@link SecurityContextHolder} si le token est valide. Volontairement <strong>pas</strong>
 * annoté {@code @Component} : Spring Boot enregistrerait automatiquement tout bean
 * {@link jakarta.servlet.Filter} sur toutes les requêtes (en plus de son ajout explicite dans
 * la chaîne Spring Security via {@code SecurityConfig}), ce qui l'exécuterait deux fois par
 * requête. Il est instancié comme {@code @Bean} par {@code SecurityConfig}.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            // [DIAG-TEMP] Logs de diagnostic temporaires — à retirer une fois la cause confirmée.
            String header = request.getHeader("Authorization");
            log.info("[DIAG] {} {} - en-tête Authorization reçu : {}", request.getMethod(), request.getRequestURI(),
                    header != null ? "présent (" + header.length() + " caractères)" : "ABSENT");

            if (header == null || !header.startsWith(BEARER_PREFIX)) {
                log.info("[DIAG] Pas d'en-tête Authorization Bearer valide sur {} : SecurityContext non peuplé.", request.getRequestURI());
            } else if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String token = header.substring(BEARER_PREFIX.length());
                boolean valid = jwtService.isValid(token);
                log.info("[DIAG] Token JWT valide ? {}", valid);
                if (valid) {
                    String username = jwtService.extractUsername(token);
                    log.info("[DIAG] Username extrait du JWT : '{}'", username);
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    log.info("[DIAG] UserDetails chargé : username='{}', authorities={}, enabled={}",
                            userDetails.getUsername(), userDetails.getAuthorities(), userDetails.isEnabled());

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.info("[DIAG] SecurityContext créé et peuplé sur {} pour l'utilisateur '{}'.",
                            request.getRequestURI(), username);
                } else {
                    // Ni exception, ni log jusqu'ici : un token expiré/malformé/à mauvaise signature ne
                    // levait rien, donc rien n'était tracé nulle part. Sur une route publique
                    // (ex. /api/chat/ask), la requête continue quand même sans authentification,
                    // silencieusement — c'est ce qui rendait la non-persistance des conversations
                    // impossible à diagnostiquer.
                    log.info("[DIAG] Token JWT présent mais invalide/expiré sur {} : SecurityContext non peuplé.", request.getRequestURI());
                }
            } else {
                log.info("[DIAG] SecurityContext déjà peuplé avant ce filtre sur {} (authentication={}).",
                        request.getRequestURI(), SecurityContextHolder.getContext().getAuthentication());
            }
        } catch (Exception e) {
            log.info("[DIAG] Exception pendant l'authentification JWT sur {} : {}", request.getRequestURI(), e.toString());
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }
}
