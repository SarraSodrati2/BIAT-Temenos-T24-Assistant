package backend.service;

/**
 * Construit le contexte texte (à partir des données Oracle) qui sera plus tard
 * envoyé à un modèle IA. Ne fait aucun appel LLM lui-même.
 */
public interface ContextBuilderService {

    String buildContext(String nomTable);

    String buildFieldContext(String nomTable, String fieldName);
}
