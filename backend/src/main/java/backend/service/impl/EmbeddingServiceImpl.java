package backend.service.impl;

import backend.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Enveloppe Spring AI {@link EmbeddingModel}, auto-configuré par le starter Ollama
 * ({@code spring.ai.ollama.embedding.options.model=mxbai-embed-large}) — même mécanisme
 * d'auto-configuration que {@code ChatClient.Builder} déjà utilisé par {@code ChatbotServiceImpl}.
 *
 * <p>{@link #embedAll(List)} découpe en lots ({@code rag.embedding.batch-size}, 100 par défaut) :
 * envoyer les ~6900 documents HELP_TEXT en un seul appel HTTP à {@code POST /api/embed} dépasse le
 * timeout HTTP configuré ({@code spring.http.client.read-timeout}) sur une inférence locale Ollama
 * (constaté en conditions réelles : {@code HttpTimeoutException: Request cancelled}) ; des lots
 * plus petits restent sous ce timeout et permettent de voir la progression dans les logs.
 */
@Slf4j
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final int batchSize;

    public EmbeddingServiceImpl(EmbeddingModel embeddingModel,
                                 @Value("${rag.embedding.batch-size:100}") int batchSize) {
        this.embeddingModel = embeddingModel;
        this.batchSize = batchSize;
    }

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.size() <= batchSize) {
            return embeddingModel.embed(texts);
        }

        int totalBatches = (texts.size() + batchSize - 1) / batchSize;
        List<float[]> allEmbeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            allEmbeddings.addAll(embeddingModel.embed(batch));
            log.info("Embeddings : lot {}/{} traité ({} document(s), {} au total).",
                    (i / batchSize) + 1, totalBatches, batch.size(), allEmbeddings.size());
        }
        return allEmbeddings;
    }
}
