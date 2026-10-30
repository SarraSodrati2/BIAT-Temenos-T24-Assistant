package backend.service;

import backend.dto.ChatContext;

import java.util.Optional;

/**
 * Répond de façon déterministe (aucun appel LLM) aux questions de recherche inversée du type « quel
 * champ contient le code SWIFT dans TT ? » : la recherche vectorielle top-k ne peut pas y répondre
 * de façon fiable (elle ramène les tables les plus proches sémantiquement, pas la liste des champs
 * qui mentionnent un terme), et laisser un LLM conclure « aucun champ ne contient X » à partir d'un
 * top-k arbitraire présente l'absence de résultat comme une preuve. Ici, le terme est cherché sur
 * TOUS les champs du périmètre (module, table ou toute la documentation) et la réponse ne contient
 * que des noms, extraits et comptages réellement lus dans HELP_FIELD / dans l'index documentaire.
 */
public interface FieldSearchService {

    /**
     * Couvre aussi les demandes de vue d'ensemble d'une table (« décris la table X », « quels champs
     * contient X ? ») ou d'un module (« à quoi sert le module MD ? »), présentées comme fiches lues
     * telles quelles dans l'index documentaire.
     *
     * @return la réponse Markdown, ou {@link Optional#empty()} si {@code question} n'est pas de ce type
     * (le pipeline RAG habituel s'applique alors).
     */
    Optional<String> tryAnswer(String question, ChatContext chatContext);
}
