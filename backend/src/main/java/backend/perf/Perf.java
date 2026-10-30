package backend.perf;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Profilage d'une question de bout en bout (demande du 2026-10-08 : « mesurer précisément où le temps est consommé »).
 * Une trace par thread de requête, ouverte par {@link PerfFilter} dès la réception de la requête HTTP (avant même
 * l'authentification) et fermée après l'écriture de la réponse : chaque étape écrit {@code [PERF] étape = X ms} et le
 * total par catégorie est résumé dans {@code [PERF SUMMARY]}. Hors requête profilée (tests, tâches de démarrage), les
 * appels ne font que chronométrer, sans rien journaliser ni conserver.
 */
@Slf4j
public final class Perf {

    /** Catégories du résumé, dans l'ordre du pipeline. */
    public enum Category {
        RECEPTION("Réception (filtres, authentification)"),
        DATABASE("Base Oracle (conversation, historique)"),
        PREPROCESSING("Preprocessing (analyse de la question)"),
        MODULE_DETECTION("Détection module / contexte"),
        DETERMINISTIC("Réponses déterministes (fiches, recherche de tables/champs)"),
        EXACT_SEARCH("Exact Search"),
        KEYWORD_SEARCH("Keyword Search"),
        VECTOR_SEARCH("Vector Search"),
        RERANKING("Reranking"),
        CONTEXT_BUILDING("Context Building"),
        LLM("LLM"),
        POST_PROCESSING("Post-processing"),
        RESPONSE("Envoi de la réponse");

        private final String label;

        Category(String label) {
            this.label = label;
        }
    }

    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    private Perf() {
    }

    private static final class Trace {
        final long startNanos = System.nanoTime();
        final Map<Category, Long> totals = new LinkedHashMap<>();
        final Map<String, String> facts = new LinkedHashMap<>();
    }

    /** Ouvre la trace de la requête courante (appelé une seule fois, au tout début). */
    public static void begin() {
        CURRENT.set(new Trace());
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** Millisecondes écoulées depuis la réception de la requête (0 hors requête profilée). */
    public static long sinceStartMs() {
        Trace trace = CURRENT.get();
        return trace == null ? 0 : (System.nanoTime() - trace.startNanos) / 1_000_000;
    }

    /** Enregistre une étape déjà chronométrée. */
    public static void record(Category category, String step, long elapsedNanos) {
        Trace trace = CURRENT.get();
        if (trace == null) {
            return;
        }
        long ms = elapsedNanos / 1_000_000;
        trace.totals.merge(category, ms, Long::sum);
        log.info("[PERF] {} = {} ms", step, ms);
    }

    /** Fait mesuré qui n'est pas une durée (taille du prompt, tokens, TTFT...), repris dans le résumé. */
    public static void fact(String name, Object value) {
        Trace trace = CURRENT.get();
        if (trace == null) {
            return;
        }
        trace.facts.put(name, String.valueOf(value));
        log.info("[PERF] {} = {}", name, value);
    }

    public static <T> T time(Category category, String step, Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            record(category, step, System.nanoTime() - start);
        }
    }

    public static void time(Category category, String step, Runnable work) {
        long start = System.nanoTime();
        try {
            work.run();
        } finally {
            record(category, step, System.nanoTime() - start);
        }
    }

    /** Ferme la trace et écrit le résumé. */
    public static void end(String label) {
        Trace trace = CURRENT.get();
        if (trace == null) {
            return;
        }
        CURRENT.remove();
        long total = (System.nanoTime() - trace.startNanos) / 1_000_000;
        StringBuilder sb = new StringBuilder("[PERF SUMMARY] ").append(label).append("\n  Total = ").append(total).append(" ms");
        long measured = 0;
        for (Category category : Category.values()) {
            Long ms = trace.totals.get(category);
            if (ms != null) {
                measured += ms;
                sb.append(String.format(Locale.ROOT, "%n  %s = %d ms", category.label, ms));
            }
        }
        sb.append(String.format(Locale.ROOT, "%n  Non attribué (code entre les étapes) = %d ms", Math.max(0, total - measured)));
        trace.facts.forEach((name, value) -> sb.append("\n  ").append(name).append(" = ").append(value));
        log.info(sb.toString());
    }
}
