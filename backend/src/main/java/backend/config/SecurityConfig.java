package backend.config;

import backend.security.AppUserDetailsService;
import backend.security.JwtAuthenticationFilter;
import backend.security.JwtService;
import backend.security.RestSecurityErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Authentification JWT stateless : {@code POST /login} (voir {@code AuthController}) échange
 * username/password contre un token, {@link JwtAuthenticationFilter} valide ce token sur chaque
 * requête suivante et peuple le {@code SecurityContext}. Seules les routes listées dans
 * {@link #PUBLIC_ROUTES} restent accessibles sans authentification ; toutes les autres exigent un
 * JWT valide (401 JSON via {@link RestSecurityErrorHandler} si absent/invalide).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Routes volontairement laissées ouvertes : le chatbot (comportement métier à ne pas casser),
     * la connexion elle-même, et la documentation/monitoring (Swagger, Actuator health).
     */
    private static final String[] PUBLIC_ROUTES = {
            "/login",
            "/register",
            "/api/chat/ask",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter,
                                            RestSecurityErrorHandler restSecurityErrorHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restSecurityErrorHandler)
                        .accessDeniedHandler(restSecurityErrorHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ROUTES).permitAll()
                        // Gestion des utilisateurs (créer/modifier/supprimer) réservée à ADMIN/SUPER_ADMIN,
                        // comme déjà affiché côté Angular par adminGuard. La lecture (GET /api/users) reste
                        // ouverte à tout utilisateur authentifié : HistoryPageComponent et
                        // StatsDashboardComponent (accessibles à CONSULTANT) en dépendent pour leurs filtres.
                        .requestMatchers(HttpMethod.POST, "/api/users/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/users/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/users/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        // Modules/tables T24 (imports XML inclus, via TableT24Controller) : lecture ouverte
                        // aux 3 rôles (CONSULTANT y compris — accès en lecture seule demandé explicitement),
                        // écriture réservée à ADMIN/SUPER_ADMIN.
                        .requestMatchers(HttpMethod.POST, "/api/modules/**", "/api/table-t24/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/modules/**", "/api/table-t24/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/modules/**", "/api/table-t24/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Non annoté {@code @Component} (voir sa Javadoc) : instancié ici comme {@code @Bean}.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService,
                                                             AppUserDetailsService userDetailsService) {
        return new JwtAuthenticationFilter(jwtService, userDetailsService);
    }

    /**
     * Exposé pour {@code AuthController} : authentifie username/password via
     * {@code AppUserDetailsService} + {@link PasswordEncoder}.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:4200"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
