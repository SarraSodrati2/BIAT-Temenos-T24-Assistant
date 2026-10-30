package backend.service;

import java.util.Optional;

/**
 * Cache mémoire des réponses complètes du chatbot, clé = question normalisée. Évite de refaire un
 * appel RAG+Ollama pour une question identique/répétée (ex. questions suggérées cliquées plusieurs
 * fois) — c'est le seul cas où le temps de réponse peut réellement descendre sous la seconde, la
 * génération Llama3 elle-même restant incompressible pour une question nouvelle.
 */
public interface ChatResponseCache {

    Optional<String> get(String question);

    void put(String question, String answer);
}
