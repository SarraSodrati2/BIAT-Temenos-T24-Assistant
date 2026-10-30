-- ============================================================================
-- BIAT Data Assistant — index Oracle pour les recherches insensibles à la casse
-- ============================================================================
-- Script manuel, à exécuter UNE FOIS (SQL*Plus / SQL Developer) par un compte
-- disposant des droits CREATE INDEX sur le schéma biat_app (une fois les tables
-- HELP_FIELD / HELP_TEXT / TABLE_T24 déjà créées par Hibernate au premier
-- démarrage de l'application). Spring Boot / Hibernate (ddl-auto=update) ne
-- sait générer que des index B-tree simples sur une colonne : il ne peut pas
-- créer d'index sur expression (LOWER(colonne)), ce script comble ce manque.
--
-- Pourquoi LOWER() et pas UPPER() : Spring Data JPA traduit toutes les
-- méthodes dérivées "...IgnoreCase" (findByFieldNameIgnoreCase,
-- findByTableNameIgnoreCase, findFirstByTableNameIgnoreCase,
-- findFirstByNomTableIgnoreCase, ...) en LOWER(colonne) = LOWER(:param).
-- La requête native HelpFieldRepository.searchByKeyword a été alignée sur la
-- même fonction. Un index sur UPPER(colonne) ne serait PAS utilisé par le CBO
-- Oracle pour une prédicat écrit avec LOWER() : les deux doivent correspondre
-- exactement à l'expression réellement exécutée.
--
-- Portée : ces index accélèrent les recherches EXACTES insensibles à la casse
-- (WHERE LOWER(col) = LOWER(:x)). Ils n'accélèrent PAS les recherches par
-- sous-chaîne avec joker en tête (LIKE '%...%'), utilisées par
-- HelpFieldRepository.searchByKeyword et
-- TableT24Repository.findByNomTableContainingIgnoreCase : un index B-tree,
-- même sur expression, ne peut pas servir un LIKE '%motif%'. Pour ces
-- recherches plein-texte, la solution Oracle est un index Oracle Text
-- (CTX_DDL.CREATE_INDEX, type CONTEXT ou CTXCAT) — hors périmètre de ce
-- script car cela ajoute une dépendance fonctionnelle supplémentaire
-- (synchronisation de l'index Text) à valider séparément.
-- ============================================================================

-- HELP_FIELD (161 564 lignes) : recherche exacte par nom de champ / table / produit
CREATE INDEX idx_help_field_field_name_ci ON HELP_FIELD (LOWER(FIELD_NAME));
CREATE INDEX idx_help_field_table_name_ci ON HELP_FIELD (LOWER(TABLE_NAME));
CREATE INDEX idx_help_field_product_ci    ON HELP_FIELD (LOWER(PRODUCT));

-- HELP_TEXT (6 917 lignes) : recherche exacte par nom de table (findFirstByTableNameIgnoreCase)
CREATE INDEX idx_help_text_table_name_ci  ON HELP_TEXT (LOWER(TABLE_NAME));

-- TABLE_T24 : recherche exacte par nom de table (findFirstByNomTableIgnoreCase)
CREATE INDEX idx_table_t24_nom_table_ci   ON TABLE_T24 (LOWER(NOM_TABLE));

-- Rafraîchit les statistiques du CBO pour que les nouveaux index soient pris
-- en compte immédiatement (sans attendre le job de collecte automatique).
EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'HELP_FIELD');
EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'HELP_TEXT');
EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'TABLE_T24');

-- Vérification (facultative) : confirme la présence des 5 index ci-dessus.
-- SELECT index_name, table_name FROM user_indexes
-- WHERE index_name IN (
--   'IDX_HELP_FIELD_FIELD_NAME_CI', 'IDX_HELP_FIELD_TABLE_NAME_CI', 'IDX_HELP_FIELD_PRODUCT_CI',
--   'IDX_HELP_TEXT_TABLE_NAME_CI', 'IDX_TABLE_T24_NOM_TABLE_CI'
-- );
