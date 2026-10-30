package backend.service.impl;

import backend.service.ChatResponseCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChatResponseCacheImpl implements ChatResponseCache {

    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public ChatResponseCacheImpl(@Value("${rag.cache.ttl-ms:600000}") long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Optional<String> get(String question) {
        String key = normalize(question);
        CachedAnswer cached = cache.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() > cached.expiresAtMillis()) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached.answer());
    }

    @Override
    public void put(String question, String answer) {
        cache.put(normalize(question), new CachedAnswer(answer, System.currentTimeMillis() + ttlMillis));
    }

    private static String normalize(String question) {
        return question.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record CachedAnswer(String answer, long expiresAtMillis) {
    }
}
