package backend.perf;

/**
 * Repères temporels de la requête courante : début et fin du traitement métier ({@code ChatbotServiceImpl.doAsk}), pour
 * isoler la réception (filtres + authentification, avant) et l'envoi de la réponse (après).
 */
public final class PerfMarks {

    private static final ThreadLocal<long[]> MARKS = ThreadLocal.withInitial(() -> new long[]{-1, -1});

    private PerfMarks() {
    }

    public static void serviceStart() {
        MARKS.get()[0] = Perf.sinceStartMs();
    }

    public static void serviceEnd() {
        MARKS.get()[1] = Perf.sinceStartMs();
    }

    public static long serviceStartMs() {
        return Math.max(0, MARKS.get()[0]);
    }

    /** Fin du traitement métier ; à défaut (exception), l'instant présent. */
    public static long serviceEndMs() {
        long end = MARKS.get()[1];
        return end < 0 ? Perf.sinceStartMs() : end;
    }

    static void clear() {
        MARKS.remove();
    }
}
