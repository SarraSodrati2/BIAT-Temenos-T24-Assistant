package backend.service.impl;

import backend.config.VectorConfig;
import backend.service.EmbeddingService;
import backend.service.VectorStoreService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import backend.perf.Perf;
import backend.perf.Perf.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Index vectoriel "flat" en mémoire (recherche Top-K par similarité cosinus, force brute),
 * persisté en JSON sur disque. Opère sur des {@link Document} Spring AI ({@link Document} ne
 * porte pas lui-même d'embedding : cette classe associe chaque {@code Document} à son
 * {@code float[]} en interne).
 *
 * <p>Pour ~6 900 documents à 1024 dimensions (mxbai-embed-large), une recherche exhaustive prend
 * quelques millisecondes (voir logs) : un index approximatif (HNSW, IVF...) n'apporterait aucun
 * gain mesurable à cette échelle.
 *
 * <p>Filtre par module : en plus de la liste complète, les entrées sont partitionnées par
 * métadonnée {@code product} (voir {@link #entriesByProduct}), recalculée à chaque changement de
 * l'index. Une recherche restreinte à un module ({@link #similaritySearch(String, int, String)}) ne
 * calcule donc la similarité QUE sur les vecteurs de ce module : c'est un vrai pré-filtre (même
 * sémantique qu'un {@code filterExpression} Spring AI), pas un filtrage a posteriori d'un top-K
 * global. Un seul index, une seule source de vérité : la partition n'est qu'une vue dessus.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreServiceImpl implements VectorStoreService {

    private final EmbeddingService embeddingService;
    private final VectorConfig vectorConfig;
    private final ObjectMapper objectMapper;

    private volatile List<Entry> entries = new ArrayList<>();
    /** Vue de {@link #entries} partitionnée par {@code product} (clé en majuscules), toujours remplacée en même temps. */
    private volatile Map<String, List<Entry>> entriesByProduct = Map.of();
    /** Texte et description générale de chaque document, en minuscules (reranking lexical), par id de document. */
    private volatile Map<String, LexicalView> lexicalById = Map.of();
    /** IDF par terme, recalculé à la demande et vidé à chaque changement de l'index. */
    private final Map<String, Double> idfCache = new ConcurrentHashMap<>();
    /** Fréquences documentaires déjà calculées (un balayage complet de l'index par terme), vidé à chaque changement de l'index. */
    private final Map<String, Integer> documentFrequencyCache = new ConcurrentHashMap<>();

    /**
     * Plafond du BONUS lexical : il ne peut porter un résultat qu'à 0.84, SOUS le score fixe du canal mots-clés
     * de {@code HybridSearchServiceImpl} (0.85), donc sous les correspondances exactes de colonne (0.95) et de
     * table (1.0) — le reranking lexical ne fait jamais passer un résultat sémantique devant une correspondance
     * exacte (un cosinus déjà supérieur n'est, lui, jamais abaissé). L'ordre entre résultats plafonnés reste
     * celui du score non plafonné (tri stable).
     */
    static final double MAX_SEMANTIC_SCORE = 0.84;

    @Override
    public boolean isIndexPersisted() {
        return Files.exists(Path.of(vectorConfig.getPath()));
    }

    @Override
    public void load() {
        File file = Path.of(vectorConfig.getPath()).toFile();
        try {
            List<PersistedEntry> persisted = objectMapper.readValue(file, new TypeReference<List<PersistedEntry>>() {
            });
            replaceEntries(persisted.stream().map(PersistedEntry::toEntry).toList());
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger l'index vectoriel : " + file, e);
        }
    }

    @Override
    public void add(List<Document> documents) {
        List<String> texts = documents.stream().map(Document::getText).toList();
        List<float[]> embeddings = embeddingService.embedAll(texts);

        List<Entry> newEntries = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            newEntries.add(new Entry(documents.get(i), embeddings.get(i)));
        }
        replaceEntries(newEntries);
    }

    @Override
    public SyncResult synchronize(List<Document> documents) {
        Map<String, Entry> currentById = new HashMap<>();
        for (Entry entry : entries) {
            currentById.put(entry.document().getId(), entry);
        }

        List<Document> toEmbed = new ArrayList<>();
        int added = 0;
        int updated = 0;
        for (Document document : documents) {
            Entry current = currentById.get(document.getId());
            if (current == null) {
                added++;
                toEmbed.add(document);
            } else if (!Objects.equals(current.document().getText(), document.getText())
                    || !Objects.equals(current.document().getMetadata(), document.getMetadata())) {
                updated++;
                toEmbed.add(document);
            }
        }
        Map<String, Document> wantedById = documents.stream()
                .collect(Collectors.toMap(Document::getId, d -> d, (a, b) -> a, LinkedHashMap::new));
        int removed = (int) currentById.keySet().stream().filter(id -> !wantedById.containsKey(id)).count();
        int unchanged = documents.size() - added - updated;

        if (added + updated + removed == 0) {
            return new SyncResult(0, 0, 0, unchanged);
        }

        List<float[]> embeddings = toEmbed.isEmpty()
                ? List.of()
                : embeddingService.embedAll(toEmbed.stream().map(Document::getText).toList());
        Map<String, float[]> freshEmbeddings = new HashMap<>();
        for (int i = 0; i < toEmbed.size(); i++) {
            freshEmbeddings.put(toEmbed.get(i).getId(), embeddings.get(i));
        }

        List<Entry> synced = new ArrayList<>(wantedById.size());
        for (Document document : wantedById.values()) {
            float[] embedding = freshEmbeddings.containsKey(document.getId())
                    ? freshEmbeddings.get(document.getId())
                    : currentById.get(document.getId()).embedding();
            synced.add(new Entry(document, embedding));
        }
        replaceEntries(synced);
        return new SyncResult(added, updated, removed, unchanged);
    }

    private void replaceEntries(List<Entry> newEntries) {
        Map<String, List<Entry>> byProduct = newEntries.stream()
                .collect(Collectors.groupingBy(entry -> productKey(entry.document().getMetadata().get("product"))));
        Map<String, LexicalView> lexical = new HashMap<>();
        for (Entry entry : newEntries) {
            lexical.put(entry.document().getId(), LexicalView.of(entry.document().getText()));
        }
        this.entries = newEntries;
        this.entriesByProduct = byProduct;
        this.lexicalById = lexical;
        idfCache.clear();
        documentFrequencyCache.clear();
    }

    private static String productKey(Object product) {
        return product == null ? "" : String.valueOf(product).trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isScoped(String product) {
        return product != null && !product.isBlank();
    }

    @Override
    public void save() {
        Path path = Path.of(vectorConfig.getPath());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<PersistedEntry> persisted = entries.stream().map(PersistedEntry::from).toList();
            objectMapper.writeValue(path.toFile(), persisted);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de sauvegarder l'index vectoriel : " + path, e);
        }
    }

    @Override
    public List<Document> similaritySearch(String query, int topK, String product, List<String> lexicalTerms) {
        // Candidats résolus AVANT l'embedding : un module inconnu n'appelle même pas Ollama.
        List<Entry> candidates = isScoped(product)
                ? entriesByProduct.getOrDefault(productKey(product), List.of())
                : entries;
        if (candidates.isEmpty()) {
            log.info("Recherche vectorielle [filtre product={}] : 0 document candidat, aucune similarité calculée.",
                    isScoped(product) ? product : "aucun");
            return List.of();
        }

        long embedStart = System.currentTimeMillis();
        long embedNanos = System.nanoTime();
        float[] queryEmbedding = embeddingService.embed(query);
        Perf.record(Category.VECTOR_SEARCH, "embedding de la question (Ollama mxbai-embed-large)", System.nanoTime() - embedNanos);
        long embedMs = System.currentTimeMillis() - embedStart;

        long searchStart = System.currentTimeMillis();
        long cosineNanos = System.nanoTime();
        List<WeightedTerm> terms = weightedTerms(lexicalTerms);
        double lexicalWeight = terms.isEmpty() ? 0 : vectorConfig.getLexicalWeight();
        List<Scored> ranked = candidates.stream()
                .map(entry -> {
                    double cosine = cosineSimilarity(queryEmbedding, entry.embedding());
                    double lexical = terms.isEmpty() ? 0 : lexicalCoverage(lexicalById.get(entry.document().getId()), terms);
                    return new Scored(entry.document(), cosine, lexical, cosine + lexicalWeight * lexical);
                })
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .toList();
        Perf.record(Category.VECTOR_SEARCH, "similarité cosinus + bonus lexical sur " + candidates.size() + " document(s)",
                System.nanoTime() - cosineNanos);
        // Document ne porte pas d'embedding (voir Entry) mais porte bien un score de pertinence natif Spring AI
        // (Document.getScore()) : on l'attache ici, comme le ferait un VectorStore Spring AI standard.
        // Le bonus lexical ne fait jamais dépasser max(MAX_SEMANTIC_SCORE, cosinus) ; sans terme lexical, le score
        // reste le cosinus brut (comportement antérieur inchangé).
        List<Document> results = ranked.stream()
                .map(scored -> scored.document().mutate()
                        .score(Math.min(scored.score(), Math.max(MAX_SEMANTIC_SCORE, scored.cosine()))).build())
                .toList();
        long searchMs = System.currentTimeMillis() - searchStart;
        if (!terms.isEmpty()) {
            log.info("Reranking lexical (poids {}, termes {}) :\n{}", lexicalWeight,
                    terms.stream().map(t -> t.term() + String.format(Locale.ROOT, "[idf=%.2f]", t.idf())).toList(),
                    ranked.stream().map(sc -> String.format(Locale.ROOT, "  %s cosinus=%.4f lexical=%.2f total=%.4f",
                            sc.document().getMetadata().get("tableName"), sc.cosine(), sc.lexical(), sc.score()))
                            .collect(Collectors.joining("\n")));
        }

        log.info("Recherche vectorielle Top {} [filtre product={}] : {} document(s) candidat(s) sur {} dans l'index, "
                        + "{} renvoyé(s) — embedding : {} ms, similarité cosinus : {} ms.",
                topK, isScoped(product) ? product : "aucun", candidates.size(), entries.size(),
                results.size(), embedMs, searchMs);
        return results;
    }

    @Override
    public int documentFrequency(String term) {
        if (term == null || term.isBlank()) {
            return 0;
        }
        return documentFrequencyCache.computeIfAbsent(term, this::countDocumentsContaining);
    }

    private int countDocumentsContaining(String term) {
        Pattern pattern = termPattern(term);
        int count = 0;
        for (LexicalView view : lexicalById.values()) {
            if (pattern.matcher(view.text()).find()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public double meanPairwiseSimilarity(List<String> documentIds) {
        Set<String> wanted = new HashSet<>(documentIds);
        List<float[]> embeddings = entries.stream()
                .filter(entry -> wanted.contains(entry.document().getId()))
                .map(Entry::embedding)
                .toList();
        if (embeddings.size() < 2) {
            return 1.0;
        }
        double sum = 0;
        int pairs = 0;
        for (int i = 0; i < embeddings.size(); i++) {
            for (int j = i + 1; j < embeddings.size(); j++) {
                sum += cosineSimilarity(embeddings.get(i), embeddings.get(j));
                pairs++;
            }
        }
        return sum / pairs;
    }

    private List<WeightedTerm> weightedTerms(List<String> lexicalTerms) {
        if (lexicalTerms == null || lexicalTerms.isEmpty() || lexicalById.isEmpty()) {
            return List.of();
        }
        int corpus = entries.size();
        List<WeightedTerm> weighted = new ArrayList<>();
        for (String term : new LinkedHashSet<>(lexicalTerms)) {
            double idf = idfCache.computeIfAbsent(term.toLowerCase(Locale.ROOT),
                    t -> Math.log((corpus + 1.0) / (documentFrequency(t) + 1.0)));
            if (idf > 0) {
                weighted.add(new WeightedTerm(term, termPattern(term), idf));
            }
        }
        return weighted;
    }

    /**
     * Part (0 à 1) du poids IDF des termes présents dans le document : un terme trouvé dans la description
     * générale compte entièrement, ailleurs (champs) à moitié — une description fonctionnelle qui parle du
     * concept vaut mieux qu'une simple occurrence technique dans un nom ou une règle de validation de champ.
     */
    private static double lexicalCoverage(LexicalView view, List<WeightedTerm> terms) {
        if (view == null) {
            return 0;
        }
        double found = 0;
        double total = 0;
        for (WeightedTerm term : terms) {
            total += term.idf();
            if (term.pattern().matcher(view.overview()).find()) {
                found += term.idf();
            } else if (term.pattern().matcher(view.text()).find()) {
                found += 0.5 * term.idf();
            }
        }
        return total == 0 ? 0 : found / total;
    }

    /** Début de mot, insensible à la casse ("print" trouve aussi "printed", jamais "reprint"). */
    private static Pattern termPattern(String term) {
        return Pattern.compile("\\b" + Pattern.quote(term.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Optional<Document> findByExactTableName(String tableName) {
        return entries.stream()
                .map(Entry::document)
                .filter(document -> tableName.equalsIgnoreCase(String.valueOf(document.getMetadata().get("tableName"))))
                .findFirst();
    }

    @Override
    public List<Document> findAllByProduct(String product) {
        return entriesByProduct.getOrDefault(productKey(product), List.of()).stream()
                .map(Entry::document)
                .toList();
    }

    @Override
    public List<Document> findTablesWithField(String fieldName) {
        // Bloc de champ (voir DocumentBuilderServiceImpl) : le nom seul sur sa ligne, puis « Description : ».
        String block = "\n" + fieldName.trim().toUpperCase(Locale.ROOT) + "\n\nDescription :";
        return entries.stream()
                .map(Entry::document)
                .filter(document -> document.getText().contains(block))
                .toList();
    }

    @Override
    public List<Document> findAll() {
        return entries.stream().map(Entry::document).toList();
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Entry(Document document, float[] embedding) {
    }

    private record Scored(Document document, double cosine, double lexical, double score) {
    }

    private record WeightedTerm(String term, Pattern pattern, double idf) {
    }

    /** Texte complet et description générale ("Description générale :" du document), en minuscules. */
    private record LexicalView(String text, String overview) {

        private static final String OVERVIEW_HEADER = "Description générale :\n";

        static LexicalView of(String documentText) {
            String text = documentText == null ? "" : documentText;
            int start = text.indexOf(OVERVIEW_HEADER);
            String overview = "";
            if (start >= 0) {
                start += OVERVIEW_HEADER.length();
                int end = text.indexOf("\n\n", start);
                overview = end < 0 ? text.substring(start) : text.substring(start, end);
            }
            return new LexicalView(text.toLowerCase(Locale.ROOT), overview.toLowerCase(Locale.ROOT));
        }
    }

    private record PersistedEntry(String id, String text, Map<String, Object> metadata, float[] embedding) {

        static PersistedEntry from(Entry entry) {
            Document doc = entry.document();
            return new PersistedEntry(doc.getId(), doc.getText(), doc.getMetadata(), entry.embedding());
        }

        Entry toEntry() {
            return new Entry(new Document(id, text, metadata), embedding);
        }
    }
}
