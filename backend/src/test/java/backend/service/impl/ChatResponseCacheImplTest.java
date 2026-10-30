package backend.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResponseCacheImplTest {

    @Test
    void get_afterPut_returnsTheCachedAnswer() {
        ChatResponseCacheImpl cache = new ChatResponseCacheImpl(600_000);

        cache.put("Résume CUSTOMER", "réponse");

        assertThat(cache.get("Résume CUSTOMER")).contains("réponse");
    }

    @Test
    void get_normalizesCaseAndWhitespace_soRephrasedQuestionsStillHit() {
        ChatResponseCacheImpl cache = new ChatResponseCacheImpl(600_000);

        cache.put("Résume   CUSTOMER", "réponse");

        assertThat(cache.get("  résume customer  ")).contains("réponse");
    }

    @Test
    void get_unknownQuestion_returnsEmpty() {
        ChatResponseCacheImpl cache = new ChatResponseCacheImpl(600_000);

        assertThat(cache.get("question jamais posée")).isEmpty();
    }

    @Test
    void get_expiredEntry_returnsEmpty() throws InterruptedException {
        ChatResponseCacheImpl cache = new ChatResponseCacheImpl(1);

        cache.put("question", "réponse");
        Thread.sleep(20);

        Optional<String> result = cache.get("question");

        assertThat(result).isEmpty();
    }
}
