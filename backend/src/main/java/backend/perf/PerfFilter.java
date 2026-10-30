package backend.perf;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ouvre la trace {@link Perf} dès la réception d'une question ({@code POST /api/chat/ask}), AVANT les filtres de sécurité
 * (ordre le plus prioritaire), et la ferme après l'écriture de la réponse JSON : le total couvre donc toute la requête.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PerfFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().endsWith("/api/chat/ask"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Perf.begin();
        try {
            chain.doFilter(request, response);
            Perf.record(Perf.Category.RESPONSE, "envoi de la réponse (sérialisation JSON + écriture)",
                    Math.max(0, Perf.sinceStartMs() - PerfMarks.serviceEndMs()) * 1_000_000);
        } finally {
            Perf.end(request.getMethod() + " " + request.getRequestURI() + " (HTTP " + response.getStatus() + ")");
            PerfMarks.clear();
        }
    }
}
