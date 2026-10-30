package backend.service.impl;

import backend.entity.HelpField;
import backend.exception.ChatbotException;
import backend.service.HelpContextBuilderService;
import backend.service.HelpSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Construit le contexte HELP_FIELD/HELP_TEXT envoyé à Ollama, à partir d'une question libre.
 *
 * <p>Résolution en 3 temps pour chaque mot-clé extrait de la question :
 * <ol>
 *   <li>correspondance exacte de champ ({@code FIELD_NAME}) — répond à "que signifie/fait X ?"
 *       en listant X dans chacune des tables où il apparaît ;</li>
 *   <li>à défaut, correspondance exacte de table ({@code TABLE_NAME}) — répond à "décris X" /
 *       "quels champs contient X ?" en listant tous les champs de la table X ;</li>
 *   <li>à défaut, recherche floue multi-colonnes ({@link HelpSearchService#findTopFields}).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HelpContextBuilderServiceImpl implements HelpContextBuilderService {

    private static final Pattern DOTTED_KEYWORD = Pattern.compile("[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+");
    private static final Pattern WORD_KEYWORD = Pattern.compile("\\b[A-Z]{3,}\\b");
    private static final int MAX_CONTEXT_LENGTH = 4000;
    private static final String BLOCK_SEPARATOR = "---------------------------------";
    private static final String NO_RESULT_MESSAGE =
            "(Aucune information trouvée dans HELP_TEXT/HELP_FIELD pour cette question.)";

    private final HelpSearchService helpSearchService;

    @Override
    @Transactional(readOnly = true)
    public String buildContext(String question) {
        List<String> keywords = extractKeywords(question);

        List<HelpField> fields = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        try {
            for (String keyword : keywords) {
                for (HelpField field : resolveFields(keyword)) {
                    if (seenIds.add(field.getId())) {
                        fields.add(field);
                    }
                }
            }
        } catch (DataAccessException e) {
            throw new ChatbotException("Échec de la recherche Oracle pour la question : \"" + question + "\"", e);
        }

        log.info("Recherche Oracle : mot(s)-clé(s) {} -> {} résultat(s).", keywords, fields.size());

        return fields.isEmpty() ? NO_RESULT_MESSAGE : formatContext(fields);
    }

    private List<HelpField> resolveFields(String keyword) {
        List<HelpField> byField = helpSearchService.findByFieldName(keyword);
        if (!byField.isEmpty()) {
            return byField;
        }

        List<HelpField> byTable = helpSearchService.findByTableName(keyword);
        if (!byTable.isEmpty()) {
            return byTable;
        }

        return helpSearchService.findTopFields(keyword);
    }

    private static List<String> extractKeywords(String question) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();

        Matcher dotted = DOTTED_KEYWORD.matcher(question);
        while (dotted.find()) {
            keywords.add(dotted.group());
        }

        Matcher word = WORD_KEYWORD.matcher(question);
        while (word.find()) {
            String candidate = word.group();
            boolean partOfDottedKeyword = keywords.stream().anyMatch(k -> k.contains(".") && k.contains(candidate));
            if (!partOfDottedKeyword) {
                keywords.add(candidate);
            }
        }

        if (keywords.isEmpty()) {
            for (String token : question.split("\\s+")) {
                String cleaned = token.replaceAll("[^A-Za-zÀ-ÿ.]", "");
                if (cleaned.length() >= 4) {
                    keywords.add(cleaned.toUpperCase());
                }
            }
        }

        return new ArrayList<>(keywords);
    }

    private static String formatContext(List<HelpField> fields) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (HelpField field : fields) {
            String block = formatBlock(field);
            int extraLength = block.length() + (first ? 0 : BLOCK_SEPARATOR.length() + 2);
            if (!first && sb.length() + extraLength > MAX_CONTEXT_LENGTH) {
                break;
            }
            if (!first) {
                sb.append(BLOCK_SEPARATOR).append("\n\n");
            }
            sb.append(block);
            first = false;
        }
        return sb.toString();
    }

    private static String formatBlock(HelpField field) {
        return "Table :\n" + field.getTableName() + "\n\n"
                + "Champ :\n" + field.getFieldName() + "\n\n"
                + "Description :\n" + orDefault(field.getDescription()) + "\n\n";
    }

    private static String orDefault(String value) {
        return (value == null || value.isBlank()) ? "Non documentée." : value;
    }
}
