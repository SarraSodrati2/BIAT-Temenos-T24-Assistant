package backend.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Construit un {@link Document} Spring AI par table T24, en fusionnant {@code HELP_TEXT}
 * (nom de table + produit) avec tous ses champs {@code HELP_FIELD} (nom + description). Oracle
 * reste la source officielle : ce service ne fait que lire, jamais écrire.
 */
public interface DocumentBuilderService {

    List<Document> buildDocuments();
}
