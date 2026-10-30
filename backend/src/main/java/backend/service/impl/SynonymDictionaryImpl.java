package backend.service.impl;

import backend.service.SynonymDictionary;
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
import java.util.regex.Pattern;

/**
 * Charge {@code rag/business-synonyms.properties} au démarrage (une seule fois, fichier statique)
 * et expose la correspondance clé -&gt; termes métier/noms de table T24, normalisée (majuscules,
 * accents supprimés) pour une comparaison robuste face à la saisie libre d'un utilisateur.
 */
@Slf4j
@Service
public class SynonymDictionaryImpl implements SynonymDictionary {

    private static final String RESOURCE_PATH = "rag/business-synonyms.properties";

    /** Clé normalisée -&gt; valeurs telles que déclarées dans le fichier (non normalisées, utilisables tel quel). */
    private final Map<String, List<String>> synonyms = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        Properties properties = new Properties();
        try {
            properties.putAll(PropertiesLoaderUtils.loadProperties(new ClassPathResource(RESOURCE_PATH)));
        } catch (IOException e) {
            log.warn("Impossible de charger {} : aucun synonyme métier ne sera appliqué ({}).", RESOURCE_PATH, e.getMessage());
            return;
        }

        for (String key : properties.stringPropertyNames()) {
            List<String> values = List.of(properties.getProperty(key).split(",")).stream()
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .toList();
            if (!values.isEmpty()) {
                synonyms.put(normalize(key), values);
            }
        }
        log.info("Dictionnaire de synonymes métier chargé : {} entrée(s) depuis {}.", synonyms.size(), RESOURCE_PATH);
    }

    @Override
    public List<String> expand(String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        return synonyms.getOrDefault(normalize(term), List.of());
    }

    @Override
    public List<String> findMatchingKeys(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalizedText = normalize(text);
        Set<String> results = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : synonyms.entrySet()) {
            Pattern wordBoundary = Pattern.compile("\\b" + Pattern.quote(entry.getKey()) + "\\b");
            if (!wordBoundary.matcher(normalizedText).find()) {
                continue;
            }
            // Clé qui est aussi un mot-outil français (le code module "DE" et la préposition "de") : comptée
            // seulement si elle est écrite en MAJUSCULES dans la question. Bug réel corrigé (2026-10-02) :
            // "les mouvements de principal" ajoutait DATA EXCHANGE/ECHANGE DE DONNEES/INTERFACE à la requête
            // d'embedding de toute question française contenant "de", quel que soit le module.
            if (FRENCH_FUNCTION_WORDS.contains(entry.getKey())
                    && !Pattern.compile("\\b" + Pattern.quote(entry.getKey()) + "\\b").matcher(text).find()) {
                continue;
            }
            results.addAll(entry.getValue());
        }
        return new ArrayList<>(results);
    }

    /** Mots-outils français qui coïncident (une fois normalisés en majuscules) avec un code module T24. */
    private static final Set<String> FRENCH_FUNCTION_WORDS = Set.of("DE", "DU", "LA", "LE", "LES", "UN", "UNE", "ET", "OU",
            "EN", "AU", "AUX", "SA", "SE", "CE", "ES", "A");

    /** Majuscules + suppression des diacritiques (accents), pour que "client"/"Client"/"CLIENT" et "identité"/"IDENTITE" soient équivalents. */
    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }
}
