package backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi documente automatiquement tous les {@code @RestController} existants
 * (chemins, verbes, DTO, contraintes Bean Validation) sans annotation supplémentaire requise.
 * UI : /swagger-ui.html — spec brute : /v3/api-docs.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI biatDataAssistantOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BIAT Data Assistant API")
                .description("Catalogue de données T24 (modules, tables, documentation HELP.TEXT) "
                        + "et chatbot Ollama contextualisé sur ces données.")
                .version("v1"));
    }
}
