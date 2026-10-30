package backend.service;

import java.util.List;

/**
 * Génère des embeddings via Ollama (modèle {@code mxbai-embed-large}) — jamais OpenAI ni aucun
 * autre fournisseur cloud.
 */
public interface EmbeddingService {

    float[] embed(String text);

    List<float[]> embedAll(List<String> texts);
}
