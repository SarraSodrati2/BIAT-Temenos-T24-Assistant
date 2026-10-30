package backend.service;

/**
 * Construit, à partir d'une question libre, le contexte texte (issu de HELP_FIELD/HELP_TEXT)
 * destiné à être injecté dans le prompt envoyé au modèle Ollama. Aucun appel LLM ici.
 */
public interface HelpContextBuilderService {

    String buildContext(String question);
}
