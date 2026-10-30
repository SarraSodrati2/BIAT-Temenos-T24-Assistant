package backend.service;

import backend.dto.ChatContext;

import java.util.Optional;

/**
 * Orchestration RAG (Retrieval-Augmented Generation) : construction de l'index vectoriel à partir
 * de {@code HELP_TEXT}/{@code HELP_FIELD}, puis récupération du contexte le plus pertinent pour
 * une question. Ne fait jamais elle-même appel au modèle de génération (Llama3) — c'est le rôle
 * de {@code ChatbotService}, qui utilise le contexte renvoyé par {@link #buildContext(String)}.
 */
public interface RagService {

    /**
     * Construit l'index vectoriel (lecture Oracle HELP_TEXT+HELP_FIELD, embeddings, sauvegarde
     * disque) s'il n'existe pas déjà sur disque ; sinon le charge simplement en mémoire. A appeler
     * une fois au démarrage (voir {@code VectorIndexRunner}).
     */
    void buildIndexIfNeeded();

    /**
     * Vrai tant que l'index vectoriel n'est pas encore chargé au démarrage : une recherche faite pendant ce temps
     * ne trouverait rien, et l'absence de résultat ne dirait alors rien de la documentation.
     */
    boolean isIndexLoading();

    /**
     * Pipeline de récupération pour une question : embedding de la question, recherche sémantique
     * Top-K dans l'index vectoriel, mise en forme du contexte prêt à être injecté dans le prompt.
     *
     * @param context contexte T24 (module/table/champ) choisi via le parcours à bulles de /chatbot
     * ({@link ChatContext#EMPTY} si aucun n'a été fixé) : quand {@code context.table()} est renseigné,
     * la recherche est restreinte à cette seule table (et à ce seul champ si renseigné) au lieu de
     * porter sur tout l'index vectoriel.
     * @return le contexte formaté, ou {@link Optional#empty()} si aucun document (correspondance
     * exacte de table ou résultat sémantique au-dessus de {@code rag.vector-store.min-score}) n'a
     * été jugé pertinent — dans ce cas l'appelant doit répondre directement sans appeler Llama3.
     */
    Optional<String> buildContext(String question, ChatContext context);
}
