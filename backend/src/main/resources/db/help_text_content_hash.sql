-- ============================================================================
-- BIAT Data Assistant — empreinte SHA-256 du XML importé dans HELP_TEXT
-- ============================================================================
-- Script manuel, à exécuter UNE FOIS (SQL*Plus / SQL Developer) sur le schéma biat_app,
-- AVANT de démarrer une version de l'application qui contient HelpText.contentHash
-- (ddl-auto=validate : Hibernate ne crée jamais cette colonne lui-même).
--
-- Portée : HelpTextImportRunner compare l'empreinte SHA-256 de chaque fichier XML à celle
-- stockée ici pour détecter un fichier modifié — y compris une modification qui garde
-- exactement la même longueur, que l'ancienne comparaison par longueur ne voyait pas.
-- Les lignes existantes (CONTENT_HASH nul) sont complétées automatiquement au premier
-- démarrage suivant : leur contenu est comparé une seule fois au fichier, sans ré-import
-- ni ré-embedding s'il est identique.
-- ============================================================================

ALTER TABLE HELP_TEXT ADD (
  CONTENT_HASH VARCHAR2(64)
);
