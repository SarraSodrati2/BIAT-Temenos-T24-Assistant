package backend.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie que EmbeddingServiceImpl délègue correctement à l'EmbeddingModel injecté par Spring, et
 * découpe les gros volumes en lots (voir sa Javadoc : envoyer ~6900 documents en un seul appel HTTP
 * dépasse le timeout HTTP configuré sur une inférence locale Ollama, reproduit une première fois en
 * conditions réelles avec RagPipelineManualIT avant correction).
 *
 * <p>Le modèle réellement utilisé (mxbai-embed-large via Ollama) est une garantie de configuration
 * (spring.ai.ollama.embedding.options.model, voir application.properties), pas quelque chose
 * qu'un test unitaire mocké peut vérifier : voir RagPipelineManualIT pour la vérification bout-en-bout.
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingServiceImplTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private EmbeddingServiceImpl embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingServiceImpl(embeddingModel, 2);
    }

    @Test
    void embed_delegatesToEmbeddingModel() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed("STATUS")).thenReturn(vector);

        assertThat(embeddingService.embed("STATUS")).isEqualTo(vector);
    }

    @Test
    void embedAll_belowBatchSize_makesASingleCall() {
        List<String> texts = List.of("STATUS", "AMOUNT");
        List<float[]> vectors = List.of(new float[]{1f, 0f}, new float[]{0f, 1f});
        when(embeddingModel.embed(texts)).thenReturn(vectors);

        assertThat(embeddingService.embedAll(texts)).isEqualTo(vectors);
    }

    @Test
    void embedAll_aboveBatchSize_splitsIntoSeveralCallsButPreservesOrder() {
        List<String> texts = List.of("A", "B", "C", "D", "E");
        when(embeddingModel.embed(List.of("A", "B"))).thenReturn(List.of(new float[]{1f}, new float[]{2f}));
        when(embeddingModel.embed(List.of("C", "D"))).thenReturn(List.of(new float[]{3f}, new float[]{4f}));
        when(embeddingModel.embed(List.of("E"))).thenReturn(List.of(new float[]{5f}));

        List<float[]> result = embeddingService.embedAll(texts);

        assertThat(result).hasSize(5);
        assertThat(result).extracting(v -> v[0]).containsExactly(1f, 2f, 3f, 4f, 5f);
        verify(embeddingModel, never()).embed(texts);
    }
}
