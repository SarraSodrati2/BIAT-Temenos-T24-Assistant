package backend.service.impl;

import backend.entity.HelpField;
import backend.entity.HelpText;
import backend.repository.HelpFieldRepository;
import backend.repository.HelpTextRepository;
import backend.service.DocumentBuilderService;
import backend.service.XmlParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Construit un {@link Document} Spring AI par table T24, en fusionnant {@code HELP_TEXT}
 * (nom de table, produit, description générale extraite du bloc XML {@code <overview><ovdesc>}
 * — voir {@link XmlParserService#getOverviewDescription}) avec TOUS ses champs {@code HELP_FIELD}
 * (nom, description, type, obligatoire/optionnel). Oracle reste la source officielle : ce service
 * ne fait que lire, jamais écrire.
 *
 * <p>Chaque champ est un bloc séparé (nom, description, type, input) plutôt qu'une simple ligne
 * "nom : description" : ceci donne au modèle une structure exploitable pour répondre précisément à
 * une question sur un champ précis (ex. "à quoi sert CUSTOMER.CIN ?") sans se mélanger avec les
 * champs voisins, et le nombre total de champs est indiqué explicitement (les LLM comptent mal les
 * éléments d'une liste) pour répondre correctement à "combien de champs contient X ?".
 *
 * <p><b>Fusion des fichiers HELP_TEXT partageant la même table</b> — Certaines tables T24 sont
 * documentées par plusieurs fichiers XML distincts déclarant la même balise {@code <table>} (ex. un
 * fichier de base et une variante d'overlay comme {@code MD.PARAMETER,ISB.xml}, convention de nom
 * de fichier à suffixe virgule). {@link backend.config.HelpTextImportRunner} important un fichier =
 * une ligne HELP_TEXT, une table peut donc avoir plusieurs lignes HELP_TEXT, et
 * {@link backend.config.HelpFieldImportRunner} extrait alors les champs de CHAQUE ligne, produisant
 * potentiellement plusieurs lignes HELP_FIELD
 * pour le même (table, champ) avec des descriptions de qualité très différente (ex. une variante
 * d'overlay ne contenant qu'une invite de saisie d'écran comme "Please specify..." là où le fichier
 * de base contient la définition fonctionnelle complète). Construire un {@code Document} par ligne
 * HELP_TEXT créerait alors plusieurs documents distincts pour la même table dans l'index vectoriel ;
 * or {@link backend.service.VectorStoreService#findByExactTableName} ne renvoie que le premier
 * trouvé, sans aucune préférence de qualité — le document le plus riche pouvait donc n'être tout
 * simplement jamais sélectionné. Ce service regroupe donc toutes les lignes HELP_TEXT d'une même
 * table en un seul {@code Document}, en fusionnant leurs champs par nom et en conservant, pour
 * chaque champ en doublon, la description la plus complète (voir {@link #preferField}). Ce
 * comportement est générique : aucun nom de table ni de champ n'est codé en dur, et il est
 * rétrocompatible avec le cas très majoritaire d'une table documentée par un seul fichier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentBuilderServiceImpl implements DocumentBuilderService {

    private static final String NO_OVERVIEW = "Non renseignée dans HELP_TEXT.";
    private static final String NO_FIELD_DESCRIPTION = "Non renseignée.";
    private static final String NO_FIELD_TYPE = "Non renseigné.";
    private static final String NO_FIELD_INPUT = "Non renseigné.";
    private static final String FIELD_SEPARATOR = "----------------------------------";
    private static final int SUMMARY_MAX_LENGTH = 300;

    /**
     * Détecte une description qui n'est en réalité qu'une invite de saisie d'écran (ex. "Please
     * specify...", "Specify...", "Enter...") plutôt qu'une véritable définition fonctionnelle du
     * champ. Générique par construction : ce motif ne référence aucun champ ni aucune table
     * particulière, il caractérise une CATÉGORIE de texte pauvre, quel que soit le champ concerné.
     */
    private static final Pattern INPUT_PROMPT_ONLY_PATTERN =
            Pattern.compile("(?i)^\\s*(please\\s+specify|specify|enter)\\b.*");

    private final HelpTextRepository helpTextRepository;
    private final HelpFieldRepository helpFieldRepository;
    private final XmlParserService xmlParserService;

    @Override
    public List<Document> buildDocuments() {
        // Tri par id : findAll() n'a aucun ordre garanti (Oracle peut le changer après des mises à jour), or les
        // égalités de pickRepresentative/preferField sont départagées par l'ordre de la liste. Sans ce tri, les
        // mêmes données produisaient des documents différents d'un démarrage à l'autre — constaté le 2026-10-02 :
        // après l'écriture des empreintes CONTENT_HASH, la synchronisation a ré-embeddé 13 documents dont le
        // contenu XML n'avait pas changé. Un id croissant reproduit aussi l'ordre d'import (ordre du XML).
        List<HelpText> helpTexts = helpTextRepository.findAll().stream()
                .sorted(Comparator.comparing(HelpText::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<Long, List<HelpField>> fieldsByHelpTextId = helpFieldRepository.findAll().stream()
                .sorted(Comparator.comparing(HelpField::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.groupingBy(HelpField::getHelpTextId));

        Map<String, List<HelpText>> helpTextsByTable = helpTexts.stream()
                .collect(Collectors.groupingBy(DocumentBuilderServiceImpl::tableKey, LinkedHashMap::new, Collectors.toList()));

        List<Document> documents = new ArrayList<>(helpTextsByTable.size());
        for (List<HelpText> group : helpTextsByTable.values()) {
            documents.add(toDocument(group, fieldsByHelpTextId));
        }
        return documents;
    }

    private static String tableKey(HelpText helpText) {
        String tableName = helpText.getTableName();
        return tableName == null ? "" : tableName.trim().toUpperCase(Locale.ROOT);
    }

    private Document toDocument(List<HelpText> group, Map<Long, List<HelpField>> fieldsByHelpTextId) {
        HelpText representative = pickRepresentative(group);
        String overview = extractOverview(representative);
        List<HelpField> fields = mergeFields(group, fieldsByHelpTextId);

        StringBuilder sb = new StringBuilder();
        sb.append("Table : ").append(representative.getTableName()).append("\n");
        sb.append("Produit : ").append(representative.getProduct()).append("\n\n");

        sb.append("Description générale :\n");
        sb.append(overview != null ? overview : NO_OVERVIEW).append("\n\n");

        sb.append("Résumé fonctionnel :\n");
        sb.append(functionalSummary(overview, representative.getProduct(), fields.size())).append("\n\n");

        sb.append("Champs (").append(fields.size()).append(") :\n\n");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(FIELD_SEPARATOR).append("\n\n");
            }
            sb.append(formatField(fields.get(i)));
        }

        Map<String, Object> metadata = Map.of(
                "tableName", representative.getTableName() != null ? representative.getTableName() : "",
                "product", representative.getProduct() != null ? representative.getProduct() : "",
                "fieldCount", fields.size());
        return new Document("helptext-" + representative.getId(), sb.toString(), metadata);
    }

    /**
     * Choisit, parmi toutes les lignes HELP_TEXT d'une même table, celle qui sert de "tête" du
     * document (produit, description générale) : celle dont la description générale extraite est la
     * plus longue, un proxy générique et déterministe de "la plus complète". En l'absence de toute
     * description générale, la première ligne (ordre d'import) est conservée.
     */
    private HelpText pickRepresentative(List<HelpText> group) {
        HelpText best = group.get(0);
        String bestOverview = extractOverview(best);
        for (int i = 1; i < group.size(); i++) {
            HelpText candidate = group.get(i);
            String candidateOverview = extractOverview(candidate);
            if (length(candidateOverview) > length(bestOverview)) {
                best = candidate;
                bestOverview = candidateOverview;
            }
        }
        return best;
    }

    /**
     * Fusionne les champs de toutes les lignes HELP_TEXT d'une même table, dédupliqués par nom de
     * champ (insensible à la casse) : en cas de doublon, {@link #preferField} conserve la
     * description la plus complète plutôt que la première/dernière rencontrée.
     */
    private static List<HelpField> mergeFields(List<HelpText> group, Map<Long, List<HelpField>> fieldsByHelpTextId) {
        Map<String, HelpField> merged = new LinkedHashMap<>();
        for (HelpText helpText : group) {
            for (HelpField field : fieldsByHelpTextId.getOrDefault(helpText.getId(), List.of())) {
                String key = field.getFieldName() == null ? "" : field.getFieldName().trim().toUpperCase(Locale.ROOT);
                merged.merge(key, field, DocumentBuilderServiceImpl::preferField);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Retient la meilleure des deux descriptions d'un même champ (une vraie définition l'emporte
     * toujours sur une simple invite de saisie d'écran ; à qualité égale, la description la plus
     * longue l'emporte, à titre de proxy générique de complétude). Ne référence aucun nom de champ
     * ni de table particulier.
     */
    private static HelpField preferField(HelpField current, HelpField candidate) {
        int currentRank = descriptionQualityRank(current.getDescription());
        int candidateRank = descriptionQualityRank(candidate.getDescription());
        if (candidateRank != currentRank) {
            return candidateRank > currentRank ? candidate : current;
        }
        return length(candidate.getDescription()) > length(current.getDescription()) ? candidate : current;
    }

    private static int descriptionQualityRank(String description) {
        if (description == null || description.isBlank()) {
            return 0;
        }
        return INPUT_PROMPT_ONLY_PATTERN.matcher(description).matches() ? 1 : 2;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static String formatField(HelpField field) {
        StringBuilder sb = new StringBuilder();
        sb.append(field.getFieldName()).append("\n\n");
        sb.append("Description :\n").append(orDefault(field.getDescription(), NO_FIELD_DESCRIPTION)).append("\n\n");
        sb.append("Type :\n").append(orDefault(field.getFieldType(), NO_FIELD_TYPE)).append("\n\n");
        sb.append("Input :\n").append(orDefault(field.getMandatory(), NO_FIELD_INPUT)).append("\n\n");
        return sb.toString();
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String extractOverview(HelpText helpText) {
        try {
            return xmlParserService.getOverviewDescription(helpText.getData()).orElse(null);
        } catch (Exception e) {
            log.warn("Impossible d'extraire la description générale de HELP_TEXT id={} ({}) : {}",
                    helpText.getId(), helpText.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * Un résumé court et déterministe (première phrase de la description générale), distinct du
     * texte complet : donne au modèle un point d'entrée rapide sans avoir à parcourir tout le
     * contexte. Quand aucune description générale n'existe, indique explicitement qu'il faut
     * déduire le rôle de la table à partir des champs — le prompt (voir ChatbotServiceImpl) demande
     * justement cette déduction au modèle dans ce cas.
     */
    private static String functionalSummary(String overview, String product, int fieldCount) {
        if (overview == null || overview.isBlank()) {
            return "Aucune description générale disponible ; à déduire des " + fieldCount
                    + " champ(s) ci-dessous et du module " + product + ".";
        }
        int end = overview.indexOf(". ");
        if (end < 0 || end > SUMMARY_MAX_LENGTH) {
            return overview.length() > SUMMARY_MAX_LENGTH ? overview.substring(0, SUMMARY_MAX_LENGTH) + "…" : overview;
        }
        return overview.substring(0, end + 1);
    }
}
