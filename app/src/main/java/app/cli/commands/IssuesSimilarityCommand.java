package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.DescriptionCleaner;
import app.issues.Embedder;
import app.issues.Extractor;
import app.issues.FirstCommit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.TeamColumns;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.model.Parallel;
import app.pipeline.IssuesSimilarityTask;
import app.pipeline.clustering.SmileKMeans;
import app.pipeline.embedding.EmbeddingCache;
import app.pipeline.embedding.EmbeddingModel;
import app.pipeline.embedding.MiniLmEmbedder;
import app.pipeline.embedding.ReferenceCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The similarity stage, after the extraction. It reads the extracted matrix and writes the
 * augmented matrix separately, without changing its input.
 *
 * <p>Before anything else of the model is used, it must reproduce the sentence-transformers
 * reference ({@link ReferenceCheck}); otherwise the run is refused.
 *
 * <p>Reads CACHE, the revised team columns {@code issues-in-progress-team} wrote to TEAM_COLUMNS,
 * and the corpus; refuses, writing and recording nothing, unless every row's split is the one the
 * revised rule draws ({@link SplitRule}) and the team file
 * holds exactly the matrix's issues. Embeds the descriptions with the pinned model in MODEL_DIR,
 * verified on load, through the {@link EmbeddingCache} in
 * EMBEDDING_CACHE, which it saves whenever the model computed anything, even when the stage then
 * fails; the descriptions the cache misses are embedded on the threads EMBEDDING_THREADS asks
 * for, every processor when unset, with the same vectors as on one. The clusters are Smile's
 * K-means ({@link SmileKMeans}). Every history and median reads the target the extraction wrote,
 * point in time. Writes the augmented matrix -- the extracted columns, the four team columns and
 * the similarity columns -- to MATRIX, the diagnostics as tab-separated files
 * to REPORT_DIR, and records the run, with the SHA-256 of every file it read or wrote, the model,
 * its revision, the cache's hits and misses, the embedding threads, the clusterer, its seed, runs
 * and the distortion of every fit, and the time spent, in ISSUES_DB.
 */
public final class IssuesSimilarityCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity";

  /**
   * What every split-dependent column reads under the revised temporal design, in words for the
   * run record (ticket 01): point-in-time histories, the outer test answering at the inner level
   * (ticket 06).
   */
  static final String POINT_IN_TIME_HISTORY = "point-in-time: for each row, the issues of its "
      + "split's training side that teach there (the inner fitting set for the _inner columns, "
      + "the outer training for the _outer ones, never an issue crossing the level's cut) whose "
      + "first resolution comes strictly before the row's first commit, never the row itself; "
      + "the closest n neighbours chosen among those; the cluster centres fitted, per k, on "
      + "those issues' vectors alone, and the median read from their members; an empty history "
      + "leaves the value missing; the outer test also answers in the _inner columns, from the "
      + "inner fitting history alone, and never enters a history or a fit";

  /** Where each setting is read from: the environment, then the system properties. */
  private final Function<String, String> settings;

  public IssuesSimilarityCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  /**
   * @param settings the settings by name; the tests hand their own, so a real CACHE in the
   *     environment of whoever runs them is never read
   */
  IssuesSimilarityCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path cache = path("CACHE");
    final Path matrix = path("MATRIX");
    final Path reports = path("REPORT_DIR");
    final Path teamFile = path("TEAM_COLUMNS");
    final Path modelDir = path(MiniLmEmbedder.MODEL_DIR);
    final Path cacheDir = path(EmbeddingCache.ENV);
    final int embeddingThreads;
    try {
      embeddingThreads = Parallel.threads(Parallel.EMBEDDING_THREADS);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }
    final long began = System.nanoTime();
    final SmileKMeans clusterer = new SmileKMeans();

    final String cacheSha;
    final String teamSha;
    final List<RowCache.Entry> extracted;
    final Map<String, Map<String, Double>> team;
    final List<String> columns;
    if (same(cache, matrix) || same(teamFile, matrix) || same(cache, teamFile)) {
      throw new CommandFailedException(List.of("  REFUSED: CACHE, TEAM_COLUMNS and MATRIX must be "
          + "three different files; the inputs are never overwritten"));
    }
    try {
      cacheSha = Sha256.of(cache);
      extracted = RowCache.read(cache);
      columns = new ArrayList<>(RowCache.columns(cache));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read CACHE " + cache + ": " + e));
    }
    try {
      teamSha = Sha256.of(teamFile);
      team = TeamColumns.read(teamFile);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read TEAM_COLUMNS " + teamFile
          + " (written by issues-in-progress-team): " + e));
    }
    final List<String> already = java.util.stream.Stream.of(Extractor.TEAM_REVISED,
        IssuesSimilarityTask.columns()).flatMap(List::stream)
        .filter(columns::contains).toList();
    if (!already.isEmpty()) {
      throw new CommandFailedException(List.of("  REFUSED: " + cache + " already has "
          + String.join(", ", already) + "; give the matrix the extraction wrote"));
    }
    final Population population;
    try {
      population = RowCache.population(extracted);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }
    final SplitRule rule = SplitRule.REVISED;
    final ResultStore store = IssuesInProgressExtractCommand.runStore(context);
    System.out.printf("%n  DURATION FROM THE FIRST COMMIT -- DESCRIPTION SIMILARITY%n");
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", cache, cacheSha,
        extracted.size());
    System.out.printf("  team columns read %s  sha256 %s%n", teamFile, teamSha);
    final List<RowCache.Entry> entries;
    try {
      entries = TeamColumns.join(extracted, team);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }
    columns.addAll(Extractor.TEAM_REVISED);

    final IssuesSimilarityTask.Report r;
    final EmbeddingCache embeddings;
    final double augmentationSeconds;
    final ReferenceCheck.Result check;
    final MiniLmEmbedder model;
    try {
      model = MiniLmEmbedder.load(modelDir);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not load the model in MODEL_DIR "
          + modelDir + ": " + e.getMessage()));
    }
    check = selfCheck(model, model::chunks);
    System.out.printf("  model %s at %s from %s, run by %s%n", EmbeddingModel.REPOSITORY,
        EmbeddingModel.REVISION, modelDir, MiniLmEmbedder.IMPLEMENTATION);
    System.out.printf("  model reproduces the sentence-transformers reference: %d sentences, "
        + "largest difference %.2e (tolerance %.0e)%n", ReferenceCheck.items().size(),
        check.maxDifference(), ReferenceCheck.TOLERANCE);
    try {
      embeddings = EmbeddingCache.open(cacheDir, EmbeddingCache.pinned(), model,
          embeddingThreads);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read EMBEDDING_CACHE " + cacheDir
          + ": " + e.getMessage()));
    }
    System.out.printf("  embedding cache %s; the model runs on %d thread%s%n", embeddings.file(),
        embeddingThreads, embeddingThreads == 1 ? "" : "s");
    final long augmenting = System.nanoTime();
    try {
      r = new IssuesSimilarityTask(context.study(), context.progress(), embeddings, clusterer,
          population, rule).run(entries);
    } catch (final RuntimeException e) {
      throw new CommandFailedException(List.of("  the similarity stage failed, nothing was "
          + "written or recorded but the embeddings already computed: " + e));
    } finally {
      // what the model computed is kept even when the stage fails, so a rerun need not redo it
      if (embeddings.misses() > 0) {
        try {
          embeddings.save();
        } catch (final IOException e) {
          throw new CommandFailedException(List.of("  could not save EMBEDDING_CACHE "
              + embeddings.file() + ": " + e.getMessage()));
        }
      }
    }
    augmentationSeconds = (System.nanoTime() - augmenting) / 1e9;
    final var s = r.sizes();
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    if (!r.refused().isEmpty()) {
      final List<String> lines = new ArrayList<>();
      lines.add("  REFUSED, nothing was written or recorded: the matrix's split is not its "
          + "rule's, or the corpus does not hold its issues as they are");
      r.refused().forEach(x -> lines.add("    " + x));
      throw new CommandFailedException(lines);
    }

    final Map<String, String> written = new LinkedHashMap<>();
    try {
      writeMatrix(matrix, r, columns, population);
      written.put("matrix", Sha256.of(matrix));
      for (final Map.Entry<String, List<String>> f : diagnostics(r, population).entrySet()) {
        Files.createDirectories(reports);
        final Path file = reports.resolve(f.getKey());
        Files.write(file, f.getValue());
        written.put(f.getKey(), Sha256.of(file));
      }
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write the outputs: " + e));
    }

    System.out.printf("%n  %,d of %,d issues have a description at their %s (cleaner %s)%n",
        r.described(), entries.size(), "first commit",
        DescriptionCleaner.VERSION);
    final List<Augmentation.Embedded> embedded = r.result().embedded();
    final long invalid = embedded.stream().filter(e -> e.problem() != null).count();
    final int maxChunks = embedded.stream().mapToInt(Augmentation.Embedded::chunks).max()
        .orElse(0);
    final long multiChunk = embedded.stream().filter(e -> e.chunks() > 1).count();
    System.out.printf("  %,d descriptions embedded: %,d from the cache, %,d by the model in "
        + "%.1f s; %,d in more than one chunk (at most %d); %,d invalid vectors left missing%n",
        embedded.size(), embeddings.hits(), embeddings.misses(), embeddings.modelSeconds(),
        multiChunk, maxChunks, invalid);
    final app.issues.Initial.Fidelity fixVersions = r.fixVersions();
    System.out.printf("%n  CHANGELOG (outer training rows): fix versions, issues changed, chain "
        + "and count consistent, initial value != current%n");
    System.out.printf("    %-12s changed %6.2f%%  consistent %6.2f%%  initial!=current %6.2f%%%n",
        fixVersions.field(), 100.0 * fixVersions.changed() / fixVersions.issues(),
        fixVersions.changed() == 0 ? 100.0
            : 100.0 * fixVersions.consistent() / fixVersions.changed(),
        100.0 * fixVersions.differs() / fixVersions.issues());
    System.out.printf("%n  TARGET READ BY EVERY HISTORY: %s, the extraction's (%s)%n",
        FirstCommit.TARGET_COLUMN, FirstCommit.rule(store.waitFenceK()));

    System.out.printf("%n  COVERAGE BY SPLIT%n");
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Augmentation.Coverage c : r.result().coverage()) {
      if (c.by().equals("split")) {
        System.out.printf("    %-26s %-18s %,6d of %,6d%n", c.column(), c.group(), c.observed(),
            c.rows());
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("column", c.column());
        row.put("split", c.group());
        row.put("rows", c.rows());
        row.put("observed", c.observed());
        rows.add(row);
      }
    }
    System.out.printf("%n  K-MEANS FITS (%s, seed %d)%n", clusterer.describe(),
        Augmentation.CLUSTER_SEED);
    System.out.println("    point in time: each row's centres fitted on the members its history"
        + " admits; the widest fit, the one the scored rows read, shown");
    for (final Map<String, Object> f : fits(r.result().clusterFits())) {
      System.out.printf("    %-6s k %2d  %,6d fits (%,6d fitted)  widest %,6d vectors  %,6d "
          + "distinct  %s%n", f.get("variant"), f.get("k"), f.get("fits"), f.get("fitted_fits"),
          f.get("vectors"), f.get("distinct"), f.get("distortion") == null ? "not fitted"
              : String.format("distortion %.6f", (Double) f.get("distortion")));
    }
    System.out.printf("%n  HISTORIES: %s%n", "point-in-time, only the training side's issues "
        + "that teach there, first resolved strictly before each row's first commit; the outer "
        + "test also answers at the inner level");
    for (final Augmentation.History h : r.result().histories()) {
      System.out.printf("    %-6s %,6d rows read it  history %,6d%n", name(h.variant()),
          h.rows(), h.history());
    }
    System.out.printf("%n  matrix written to %s (%d new columns)%n", matrix,
        IssuesSimilarityTask.columns().size());
    System.out.printf("  diagnostics written to %s: %s%n", reports,
        String.join(", ", written.keySet().stream().filter(k -> !k.equals("matrix")).toList()));

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    run.put("cache_sha256", cacheSha);
    run.put("team_columns_sha256", teamSha);
    run.put("written_sha256", written);
    run.put("cleaner", DescriptionCleaner.VERSION);
    run.put("jaccard_n", Augmentation.JACCARD_N);
    run.put("embedding_n", Augmentation.EMBEDDING_N);
    run.put("cluster_k", Augmentation.CLUSTER_K);
    run.put("clusterer", clusterer.describe());
    run.put("cluster_seed", Augmentation.CLUSTER_SEED);
    run.put("cluster_runs", SmileKMeans.RUNS);
    run.put("cluster_max_iterations", SmileKMeans.MAX_ITERATIONS);
    run.put("cluster_tolerance", SmileKMeans.TOLERANCE);
    run.put("cluster_fits", fits(r.result().clusterFits()));
    run.put("model", EmbeddingModel.REPOSITORY);
    run.put("model_revision", EmbeddingModel.REVISION);
    run.put("model_weights_sha256", EmbeddingModel.FILES.get(0).sha256());
    run.put("embedder", MiniLmEmbedder.IMPLEMENTATION);
    run.put("chunking", EmbeddingModel.CHUNKING);
    run.put("reference_max_difference", check.maxDifference());
    run.put("reference_tolerance", ReferenceCheck.TOLERANCE);
    run.put("dimension", EmbeddingModel.DIMENSION);
    run.put("unit_tolerance", Augmentation.UNIT_TOLERANCE);
    run.put("embedding_cache", embeddings.file().toString());
    run.put("embedding_cache_format", EmbeddingCache.FORMAT);
    run.put("embedding_cache_hits", embeddings.hits());
    run.put("embedding_cache_misses", embeddings.misses());
    run.put("embedded", embedded.size());
    run.put("invalid_vectors", invalid);
    run.put("multi_chunk_descriptions", multiChunk);
    run.put("max_chunks", maxChunks);
    run.put("model_seconds", embeddings.modelSeconds());
    run.put("embedding_threads", embeddingThreads);
    run.put("augmentation_seconds", augmentationSeconds);
    run.put("history", POINT_IN_TIME_HISTORY);
    run.put("split_rule", rule.rule());
    run.put(SplitRule.DESIGN_FIELD, rule.setting());
    run.put("study_population", population.name());
    run.put("target", FirstCommit.rule(store.waitFenceK()));
    run.put("target_column", FirstCommit.TARGET_COLUMN);
    run.put("population", FirstCommit.POPULATION);
    run.put("description_at", "the first commit that counts");
    run.put("rows", entries.size());
    run.put("described", r.described());
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    run.put("processors", Runtime.getRuntime().availableProcessors());
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        store.record(run, rows));
  }

  /**
   * The model's run on the six reference sentences, refused unless it reproduces the
   * sentence-transformers token ids and vectors; run before any description is embedded.
   */
  static ReferenceCheck.Result selfCheck(final Embedder model,
      final Function<String, List<long[]>> chunks) throws CommandFailedException {
    final ReferenceCheck.Result check;
    try {
      check = ReferenceCheck.compare(model, chunks);
    } catch (final IllegalStateException | java.io.UncheckedIOException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: the "
          + "sentence-transformers reference could not be read: " + e.getMessage()));
    }
    if (!check.passed()) {
      final List<String> lines = new ArrayList<>();
      lines.add("  REFUSED, nothing was written or recorded: the model in MODEL_DIR does not "
          + "reproduce the sentence-transformers reference (tolerance " + ReferenceCheck.TOLERANCE
          + ")");
      check.problems().forEach(p -> lines.add("    " + p));
      throw new CommandFailedException(lines);
    }
    return check;
  }

  /**
   * The K-means fits for the run record, per variant and k: how many fits the point-in-time
   * histories took and how many were fitted, and the widest fit's vector counts and distortion,
   * which is null when not fitted.
   */
  static List<Map<String, Object>> fits(final List<Augmentation.ClusterFit> fits) {
    final Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    for (final Augmentation.ClusterFit f : fits) {
      final Map<String, Object> fit = out.computeIfAbsent(name(f.variant()) + "\t" + f.k(),
          key -> {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("variant", name(f.variant()));
            m.put("k", f.k());
            m.put("fits", 0);
            m.put("fitted_fits", 0);
            m.put("vectors", -1);
            return m;
          });
      fit.merge("fits", 1, (a, b) -> (Integer) a + (Integer) b);
      fit.merge("fitted_fits", f.fitted() ? 1 : 0, (a, b) -> (Integer) a + (Integer) b);
      if (f.vectors() > (Integer) fit.get("vectors")) {
        fit.put("vectors", f.vectors());
        fit.put("distinct", f.distinct());
        fit.put("fitted", f.fitted());
        fit.put("distortion", f.fitted() ? f.distortion() : null);
      }
    }
    return List.copyOf(out.values());
  }

  /**
   * The matrix as read, every column and header name kept, with the new columns after them; the
   * target is already in the matrix.
   */
  static void writeMatrix(final Path out, final IssuesSimilarityTask.Report r,
      final List<String> columns, final Population population) throws IOException {
    final List<String> all = new ArrayList<>(columns);
    all.addAll(IssuesSimilarityTask.columns());
    RowCache.write(out, r.entries(), all, List.of(population));
  }

  /** Each diagnostic file by name, as lines with a header. */
  static Map<String, List<String>> diagnostics(final IssuesSimilarityTask.Report r,
      final Population population) {
    final Map<String, String> project = new HashMap<>();
    r.entries().forEach(e -> project.put(e.unit().issueId(), e.unit().project()));
    final Map<String, List<String>> out = new LinkedHashMap<>();

    final List<String> queries = new ArrayList<>();
    final List<String> neighbours = new ArrayList<>();
    final StringBuilder head = new StringBuilder("id\tvariant\tsplit\ttokens\tcandidates");
    Augmentation.JACCARD_N.forEach(n -> head.append("\tused_").append(n));
    queries.add(head.toString());
    neighbours.add("id\tvariant\trank\tneighbour\tneighbour_project\tdistance");
    for (final Augmentation.Query q : r.result().queries()) {
      final StringBuilder line = new StringBuilder(String.join("\t", q.id(), name(q.variant()),
          q.split(), Integer.toString(q.tokens()), Integer.toString(q.candidates())));
      Augmentation.JACCARD_N.forEach(n -> line.append('\t').append(q.used(n)));
      queries.add(line.toString());
      for (int k = 0; k < q.nearest().size(); k++) {
        final Augmentation.Neighbour n = q.nearest().get(k);
        neighbours.add(String.join("\t", q.id(), name(q.variant()), Integer.toString(k + 1),
            n.id(), project.get(n.id()), Double.toString(n.distance())));
      }
    }
    out.put("jaccard-queries.tsv", queries);
    out.put("jaccard-neighbours.tsv", neighbours);

    final List<String> searches = new ArrayList<>();
    final List<String> near = new ArrayList<>();
    final StringBuilder top = new StringBuilder("id\tvariant\tsplit\tcandidates");
    Augmentation.EMBEDDING_N.forEach(n -> top.append("\tused_").append(n));
    searches.add(top.toString());
    near.add("id\tvariant\trank\tneighbour\tneighbour_project\tdistance");
    for (final Augmentation.EmbeddingQuery q : r.result().embeddingQueries()) {
      final StringBuilder line = new StringBuilder(String.join("\t", q.id(), name(q.variant()),
          q.split(), Integer.toString(q.candidates())));
      Augmentation.EMBEDDING_N.forEach(n -> line.append('\t').append(q.used(n)));
      searches.add(line.toString());
      for (int k = 0; k < q.nearest().size(); k++) {
        final Augmentation.Neighbour n = q.nearest().get(k);
        near.add(String.join("\t", q.id(), name(q.variant()), Integer.toString(k + 1),
            n.id(), project.get(n.id()), Double.toString(n.distance())));
      }
    }
    out.put("embedding-queries.tsv", searches);
    out.put("embedding-neighbours.tsv", near);

    final List<String> chunks = new ArrayList<>(List.of("id\tproject\tchunks\tvalid\tproblem"));
    r.result().embedded().forEach(e -> chunks.add(String.join("\t", e.id(), project.get(e.id()),
        Integer.toString(e.chunks()), Boolean.toString(e.problem() == null),
        e.problem() == null ? "" : e.problem())));
    out.put("embedding-chunks.tsv", chunks);

    final List<String> fits = new ArrayList<>(List.of("variant\tk\tvectors\tdistinct\tfitted"
        + "\tdistortion\trows"));
    final List<String> sizes = new ArrayList<>(List.of("variant\tk\tcluster\tmembers"));
    final List<String> makeup = new ArrayList<>(List.of("variant\tk\tcluster\tproject\tmembers"));
    for (final Augmentation.ClusterFit f : r.result().clusterFits()) {
      final String at = String.join("\t", name(f.variant()), Integer.toString(f.k()));
      fits.add(String.join("\t", at, Integer.toString(f.vectors()),
          Integer.toString(f.distinct()), Boolean.toString(f.fitted()),
          f.fitted() ? Double.toString(f.distortion()) : "", Integer.toString(f.rows())));
      for (int c = 0; c < f.members().size(); c++) {
        final List<String> members = f.members().get(c);
        sizes.add(String.join("\t", at, Integer.toString(c), Integer.toString(members.size())));
        final Map<String, Integer> by = new TreeMap<>();
        members.forEach(m -> by.merge(project.get(m), 1, Integer::sum));
        final int cluster = c;
        by.forEach((p, n) -> makeup.add(String.join("\t", at, Integer.toString(cluster), p,
            Integer.toString(n))));
      }
    }
    out.put("cluster-fits.tsv", fits);
    out.put("cluster-sizes.tsv", sizes);
    out.put("cluster-projects.tsv", makeup);
    final List<String> assigned = new ArrayList<>(List.of("id\tvariant\tsplit\tk\tcluster"
        + "\tdistance\tmembers\thistory"));
    for (final Augmentation.ClusterQuery q : r.result().clusterQueries()) {
      final boolean none = q.cluster() < 0;
      assigned.add(String.join("\t", q.id(), name(q.variant()), q.split(),
          Integer.toString(q.k()), none ? "" : Integer.toString(q.cluster()),
          none ? "" : Double.toString(q.distance()), Integer.toString(q.members()),
          Integer.toString(q.history())));
    }
    out.put("cluster-queries.tsv", assigned);

    final List<String> coverage = new ArrayList<>(List.of("column\tby\tgroup\trows\tobserved"));
    r.result().coverage().forEach(c -> coverage.add(String.join("\t", c.column(), c.by(),
        c.group(), Integer.toString(c.rows()), Integer.toString(c.observed()))));
    out.put("coverage.tsv", coverage);

    final List<String> histories = new ArrayList<>(List.of("variant\trows\thistory"));
    r.result().histories().forEach(h -> histories.add(String.join("\t", name(h.variant()),
        Integer.toString(h.rows()), Integer.toString(h.history()))));
    out.put("histories.tsv", histories);
    return out;
  }

  private static boolean same(final Path a, final Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  private static String name(final Augmentation.Variant v) {
    return v.name().toLowerCase(Locale.ROOT);
  }

  private Path path(final String name) throws CommandFailedException {
    final String value = settings.apply(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE, TEAM_COLUMNS, MATRIX, REPORT_DIR, "
          + "MODEL_DIR and EMBEDDING_CACHE are required; missing " + name));
    }
    return Path.of(value);
  }
}
