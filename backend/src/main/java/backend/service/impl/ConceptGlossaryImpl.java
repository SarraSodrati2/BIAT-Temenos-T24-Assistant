package backend.service.impl;

import backend.service.ConceptGlossary;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Charge {@code rag/fr-en-glossary.properties} et relie les mots français d'une question aux termes
 * anglais de la documentation T24.
 *
 * <p>Cause réelle corrigée (mesurée sur l'index, 2026-10-02) : la question française n'était embeddée que
 * telle quelle par un modèle d'embedding anglais (mxbai-embed-large) face à une documentation anglaise —
 * "Comment ouvrir, attribuer et clôturer une caisse ?" classait TELLER.ID 9ᵉ dans le module TT, "largeur et
 * profondeur d'un formulaire imprimé" classait DE.FORM.TYPE 40ᵉ dans DE. Avec les termes anglais du glossaire
 * ajoutés à la requête (open, assign, close, till / width, depth, form, print) les deux passent 1ᵉʳ.
 *
 * <p>Flexions françaises courantes reconnues sans les lister dans le fichier (pluriels, féminins, participes
 * et formes conjuguées simples : "historiques" -&gt; historique, "supprime" -&gt; supprimer, "enregistrées" -&gt;
 * enregistrer) — voir {@link #candidateKeys}.
 */
@Slf4j
@Service
public class ConceptGlossaryImpl implements ConceptGlossary {

    private static final String RESOURCE_PATH = "rag/fr-en-glossary.properties";
    /** Un concept dont au moins un terme anglais apparaît dans au plus 5 % des documents est jugé spécifique. */
    private static final double SPECIFIC_MAX_DOCUMENT_RATIO = 0.05;
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}-]*");
    /** Suffixes retirés/remplacés pour retrouver la forme du glossaire (paires suffixe -&gt; remplacement). */
    private static final String[][] INFLECTIONS = {
            {"S", ""}, {"X", ""}, {"ES", ""}, {"E", ""}, {"EES", "ER"}, {"EE", "ER"}, {"ES", "ER"}, {"E", "ER"},
            {"", "R"}, {"ENT", "ER"}, {"S", "R"}};

    /**
     * Mots qui décrivent la FORME de la question (« quel est le rôle de X ? ») et non un fait bancaire : utiles à la
     * recherche (purpose/overview), jamais vérifiés comme affirmations. Bug réel (2026-10-02) : « Le rôle de ACCOUNT
     * est... » était rejeté parce que le texte de ACCOUNT ne contient pas le mot "role".
     */
    private static final Set<String> QUESTION_FORM_KEYS = Set.of("ROLE", "OBJECTIF", "FONCTION", "DESCRIPTION");

    private final Map<String, List<String>> glossary = new LinkedHashMap<>();
    private volatile Set<String> specificKeys = Set.of();

    @PostConstruct
    void load() {
        Properties properties = new Properties();
        try {
            properties.putAll(PropertiesLoaderUtils.loadProperties(new ClassPathResource(RESOURCE_PATH)));
        } catch (IOException e) {
            log.warn("Impossible de charger {} : aucun pont français -> anglais pour la recherche ({}).", RESOURCE_PATH, e.getMessage());
            return;
        }
        for (String key : properties.stringPropertyNames()) {
            List<String> values = List.of(properties.getProperty(key).split(",")).stream()
                    .map(String::trim).filter(v -> !v.isEmpty()).toList();
            if (!values.isEmpty()) {
                glossary.put(normalize(key), values);
            }
        }
        log.info("Glossaire français -> anglais chargé : {} entrée(s) depuis {}.", glossary.size(), RESOURCE_PATH);
    }

    @Override
    public List<String> englishTermsFor(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String key : keysIn(text)) {
            terms.addAll(glossary.get(key));
        }
        return new ArrayList<>(terms);
    }

    @Override
    public Map<String, List<String>> specificConceptsIn(String text) {
        Map<String, List<String>> concepts = new LinkedHashMap<>();
        Set<String> specific = specificKeys;
        for (String key : keysIn(text)) {
            if (specific.contains(key) && !QUESTION_FORM_KEYS.contains(key) && !glossary.get(key).isEmpty()) {
                concepts.put(key, glossary.get(key));
            }
        }
        return concepts;
    }

    @Override
    public synchronized void calibrate(ToIntFunction<String> documentFrequency, int corpusSize) {
        if (corpusSize <= 0) {
            return;
        }
        int removed = 0;
        Set<String> specific = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : glossary.entrySet()) {
            List<String> kept = new ArrayList<>();
            int minFrequency = Integer.MAX_VALUE;
            for (String term : entry.getValue()) {
                int df = documentFrequency.applyAsInt(term);
                if (df == 0) {
                    removed++;
                    log.warn("Glossaire : terme anglais '{}' (pour '{}') absent de l'index documentaire, ignoré.", term, entry.getKey());
                    continue;
                }
                kept.add(term);
                minFrequency = Math.min(minFrequency, df);
            }
            entry.setValue(List.copyOf(kept));
            if (!kept.isEmpty() && minFrequency <= SPECIFIC_MAX_DOCUMENT_RATIO * corpusSize) {
                specific.add(entry.getKey());
            }
        }
        specificKeys = Set.copyOf(specific);
        log.info("Glossaire calibré sur l'index ({} document(s)) : {} terme(s) absent(s) retiré(s), {} concept(s) spécifique(s) sur {}.",
                corpusSize, removed, specific.size(), glossary.size());
    }

    /** Clés du glossaire reconnues dans {@code text}, dans l'ordre d'apparition, sans doublon. */
    private Set<String> keysIn(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null || text.isBlank() || glossary.isEmpty()) {
            return keys;
        }
        Matcher m = WORD.matcher(normalize(text));
        while (m.find()) {
            // Mot composé connu tel quel ("sous-comptes"), sinon chacune de ses parties ("supprime-t-on" -> supprime).
            if (!addFirstKnownForm(m.group(), keys) && m.group().contains("-")) {
                for (String part : m.group().split("-")) {
                    addFirstKnownForm(part, keys);
                }
            }
        }
        return keys;
    }

    private boolean addFirstKnownForm(String word, Set<String> keys) {
        for (String candidate : candidateKeys(word)) {
            if (glossary.containsKey(candidate)) {
                keys.add(candidate);
                return true;
            }
        }
        return false;
    }

    /** Le mot tel quel, puis ses formes de base plausibles (voir {@link #INFLECTIONS}), mot de 3 lettres minimum. */
    static List<String> candidateKeys(String word) {
        List<String> candidates = new ArrayList<>();
        candidates.add(word);
        for (String[] inflection : INFLECTIONS) {
            String suffix = inflection[0];
            if (word.endsWith(suffix) && word.length() - suffix.length() >= 3) {
                candidates.add(word.substring(0, word.length() - suffix.length()) + inflection[1]);
            }
        }
        return candidates;
    }

    static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }
}
