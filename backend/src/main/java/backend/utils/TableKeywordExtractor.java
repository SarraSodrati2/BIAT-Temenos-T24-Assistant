package backend.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrait, depuis une question libre, les motifs qui ressemblent à un nom de table T24
 * (ex. {@code AA.ARR.CHARGEOFF}, {@code CUSTOMER}) : mot en majuscules à points, ou mot isolé de
 * 3 lettres majuscules ou plus. Utilisé pour tenter une correspondance exacte de table avant/en
 * plus de la recherche sémantique (voir {@code RagServiceImpl}) : une recherche vectorielle pure
 * dilue le signal "nom de table" dans le volume de texte des descriptions de champs — une
 * correspondance exacte reste plus fiable quand la question nomme la table explicitement.
 *
 * <p>Pour un motif à points, le préfixe avant le premier point est aussi ajouté comme candidat
 * secondaire (ex. {@code CUSTOMER.CIN} -&gt; candidats {@code CUSTOMER.CIN} puis {@code CUSTOMER}) :
 * en convention de nommage T24, un champ qualifié appartient souvent à la table de même préfixe.
 * Sans repli, une question sur un champ précis (ex. "à quoi sert CUSTOMER.CIN ?") ne retrouvait
 * jamais sa table exacte, alors que "CUSTOMER" seul y arrivait très bien.
 */
public final class TableKeywordExtractor {

    private static final Pattern DOTTED_KEYWORD = Pattern.compile("[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+");
    private static final Pattern WORD_KEYWORD = Pattern.compile("\\b[A-Z]{3,}\\b");

    private TableKeywordExtractor() {
    }

    public static List<String> extractCandidates(String question) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();

        Matcher dotted = DOTTED_KEYWORD.matcher(question);
        while (dotted.find()) {
            String match = dotted.group();
            keywords.add(match);
            int firstDot = match.indexOf('.');
            if (firstDot > 0) {
                keywords.add(match.substring(0, firstDot));
            }
        }

        Matcher word = WORD_KEYWORD.matcher(question);
        while (word.find()) {
            String candidate = word.group();
            boolean partOfDottedKeyword = keywords.stream().anyMatch(k -> k.contains(".") && k.contains(candidate));
            if (!partOfDottedKeyword) {
                keywords.add(candidate);
            }
        }

        return new ArrayList<>(keywords);
    }
}
