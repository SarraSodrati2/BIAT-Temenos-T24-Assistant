-- ============================================================================
-- BIAT Data Assistant — contexte de conversation (module/table/colonne T24)
-- ============================================================================
-- Script manuel, à exécuter UNE FOIS (SQL*Plus / SQL Developer) par un compte
-- disposant des droits ALTER TABLE sur le schéma biat_app (une fois la table
-- CHATS déjà créée par Hibernate au premier démarrage de l'application).
-- ddl-auto=validate : Hibernate ne créera jamais ces colonnes lui-même, il ne
-- fait que valider que le schéma existant correspond à l'entité Chat.
--
-- Portée : mémorise le module/table/champ T24 choisi via le parcours à bulles
-- de la page /chatbot (voir ChatContextUpdateDTO), pour que le contexte
-- survive à un rechargement de page et limite la recherche RAG (RagServiceImpl)
-- à la table/au champ concernés.
-- ============================================================================

ALTER TABLE CHATS ADD (
  SELECTED_MODULE VARCHAR2(50),
  SELECTED_TABLE  VARCHAR2(255),
  SELECTED_FIELD  VARCHAR2(255)
);
