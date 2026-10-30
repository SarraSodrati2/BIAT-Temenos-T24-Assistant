package backend.runner;

import backend.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Construit (ou charge) l'index vectoriel RAG au démarrage.
 *
 * <p>{@code @Order(3)} : s'exécute après les runners d'import Oracle
 * ({@code backend.config.HelpTextImportRunner} (1), {@code HelpFieldImportRunner} (2)), dont il
 * dépend pour lire HELP_TEXT/HELP_FIELD — nécessaire depuis que l'index persisté est réconcilié à
 * chaque démarrage avec la base (voir {@code RagServiceImpl.buildIndexIfNeeded}) : sans ordre garanti,
 * un fichier XML ajouté pouvait être importé en base APRÈS la synchronisation de l'index et n'y
 * apparaître qu'au démarrage suivant. {@code RagServiceImpl} gère toujours explicitement le cas
 * "aucun document trouvé" (rien n'est construit ni persisté).
 *
 * <p>Désactivable via {@code rag.enabled=false} : ce runner appelle Ollama (embeddings), un
 * service externe que le profil {@code dev}/H2 (utilisé par {@code mvn test}) n'a pas forcément
 * démarré — voir {@code application-dev.properties}.
 */
@Slf4j
@Component
@Order(3)
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class VectorIndexRunner implements CommandLineRunner {

    private final RagService ragService;

    @Override
    public void run(String... args) {
        ragService.buildIndexIfNeeded();
    }
}
