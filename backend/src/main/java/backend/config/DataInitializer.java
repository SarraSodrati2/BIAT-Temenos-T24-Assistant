package backend.config;

import backend.entity.User;
import backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crée automatiquement, au démarrage, le compte administrateur par défaut si la table USERS est
 * vide (premier démarrage / base fraîchement créée) — sans quoi {@code POST /login} ne peut
 * fonctionner : {@code AppUserDetailsService} ne trouverait jamais aucun utilisateur.
 *
 * <p>Idempotent au niveau table (comme {@code HelpFieldImportRunner}) : dès que USERS contient
 * au moins une ligne (cet admin, ou un utilisateur créé/importé autrement), plus rien n'est créé.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String ADMIN_EMAIL = "sodratisarra2@gmail.com";
    private static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("USERS contient déjà des données, création de l'administrateur par défaut ignorée.");
            return;
        }

        User admin = User.builder()
                .username(ADMIN_USERNAME)
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(ADMIN_PASSWORD))
                .role(ADMIN_ROLE)
                .isActive(true)
                .build();
        userRepository.save(admin);

        log.info("Administrateur par défaut créé : username={}, email={}, role={}",
                ADMIN_USERNAME, ADMIN_EMAIL, ADMIN_ROLE);
    }
}
