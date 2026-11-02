package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.DayBoundaries;
import app.issues.DescriptionCleaner;
import app.issues.Embedder;
import app.issues.Extractor;
import app.issues.FirstCommit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.TeamColumns;
import app.issues.Universe.Population;
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
 * rule the run declares draws ({@link SplitRule}, setting {@code SPLIT_RULE}) and the team file
 * holds exactly the matrix's issues. Embeds the descriptions with the pinned model in MODEL_DIR,
 * verified on load, through the {@link EmbeddingCache} in
 * EMBEDDING_CACHE, which it saves whenever the model computed anything, even when the stage then
 * fails; the descriptions the cache misses are embedded on the threads EMBEDDING_THREADS asks
 * for, every processor when unset, with the same vectors as on one. The clusters are Smile's
 * K-means ({@link SmileKMeans}). Each issue's day boundary is read from the tracker activity
 * before its first In Progress ({@link DayBoundaries}); the similarity medians are of past issues'
 * business days at their own boundaries ({@link BusinessDays}), each without the time its changelog
 * shows it back in Open or Reopened. Writes the augmented matrix -- the extracted columns, the four
 * team columns, the similarity columns, then the day boundary and the active business days, the
 * target -- to MATRIX, the diagnostics as tab-separated files
 * to REPORT_DIR, and records the run, with the SHA-256 of every file it read or wrote, the model,
 * its revision, the cache's hits and misses, the embedding threads, the clusterer, its seed, runs
 * and the distortion of every fit, and the time spent, in ISSUES_DB.
 */
public final class IssuesSimilarityCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity";

  /** What every split-dependent column reads, in words for the run record (ticket 21). */
  static final String HISTORY = "the whole training side of the split, whatever the dates: the "
      + "inner fitting set for the _inner columns, the outer training for the _outer ones; one "
      + "neighbour index and one cluster fit per k per variant, read by its training rows and its "
      + "scored rows alike; no label of the other side, and never the row's own";

  private static final Population P = Population.IN_PROGRESS;

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
        IssuesSimilarityTask.columns(), BusinessDays.COLUMNS).flatMap(List::stream)
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
    final SplitRule rule;
    try {
      rule = SplitRule.declared(settings.apply(SplitRule.SETTING), population);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }
    final boolean firstCommit = population == Population.FIRST_COMMIT;
    if (firstCommit) {
      IssuesInProgressExtractCommand.requireOwnDatabases(context.config());
    }
    System.out.printf("%n  DURATION FROM %s -- DESCRIPTION SIMILARITY%n",
        firstCommit ? "THE FIRST COMMIT" : "IN PROGRESS");
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
        r.described(), entries.size(), firstCommit ? "first commit" : "first In Progress",
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
    final Map<String, Map<String, Integer>> sources = firstCommit ? Map.of() : sources(r);
    final Map<String, Integer> changed = firstCommit ? Map.of() : changedByActiveTime(r);
    if (firstCommit) {
      System.out.printf("%n  TARGET READ BY EVERY HISTORY: %s, the extraction's (%s)%n",
          FirstCommit.TARGET_COLUMN, FirstCommit.RULE);
    } else {
      System.out.printf("%n  DAY BOUNDARIES: %s; %s%n", BusinessDays.TARGET,
          BusinessDays.HOLIDAYS);
      System.out.printf("  %,d bot accounts left out of the activity (at least %d comments, at "
          + "least %.0f%% opening like a bot's template)%n", r.bots().size(),
          DayBoundaries.BOT_MIN_COMMENTS, 100 * DayBoundaries.BOT_TEMPLATED_SHARE);
      sources.forEach((split, by) -> System.out.printf("    %-18s %s%n", split, by));
      System.out.printf("  issues whose business days the time back in Open or Reopened changed: "
          + "%s%n", changed);
    }

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
    for (final Augmentation.ClusterFit f : r.result().clusterFits()) {
      System.out.printf("    %-6s k %2d  %,6d vectors  %,6d distinct  %s%n",
          name(f.variant()), f.k(), f.vectors(), f.distinct(),
          f.fitted() ? String.format("distortion %.6f", f.distortion()) : "not fitted");
    }
    System.out.printf("%n  HISTORIES: each split's whole training side, whatever the dates%n");
    for (final Augmentation.History h : r.result().histories()) {
      System.out.printf("    %-6s %,6d rows read it  history %,6d%n", name(h.variant()),
          h.rows(), h.history());
    }
    System.out.printf("%n  matrix written to %s (%d new columns)%n", matrix,
        IssuesSimilarityTask.columns().size() + (firstCommit ? 0 : BusinessDays.COLUMNS.size()));
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
    run.put("history", HISTORY);
    run.put("split_rule", rule.rule());
    if (rule == SplitRule.RANDOM) {
      run.put("split_seed", ProjectSplit.SEED);
    }
    if (firstCommit) {
      run.put("study_population", population.name());
      run.put("target", FirstCommit.RULE);
      run.put("target_column", FirstCommit.TARGET_COLUMN);
      run.put("population", FirstCommit.POPULATION);
      run.put("description_at", "the first commit that counts");
    } else {
      run.put("target", BusinessDays.TARGET);
      run.put("population", BusinessDays.POPULATION);
      run.put("holidays", BusinessDays.HOLIDAYS);
      run.put("stopped_statuses", BusinessDays.STOPPED.stream().sorted().toList());
      run.put("changed_by_active_time", changed);
      run.put("day_boundary", boundaryRule());
      run.put("day_boundary_sources", sources);
      run.put("bot_accounts", r.bots().stream().sorted().toList());
    }
    run.put("rows", entries.size());
    run.put("described", r.described());
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    run.put("processors", Runtime.getRuntime().availableProcessors());
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
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
   * Each K-means fit for the run record: its history, k, vector counts and distortion, which is
   * null when not fitted.
   */
  static List<Map<String, Object>> fits(final List<Augmentation.ClusterFit> fits) {
    final List<Map<String, Object>> out = new ArrayList<>();
    for (final Augmentation.ClusterFit f : fits) {
      final Map<String, Object> fit = new LinkedHashMap<>();
      fit.put("variant", name(f.variant()));
      fit.put("k", f.k());
      fit.put("vectors", f.vectors());
      fit.put("distinct", f.distinct());
      fit.put("fitted", f.fitted());
      fit.put("distortion", f.fitted() ? f.distortion() : null);
      out.add(fit);
    }
    return out;
  }

  /** The matrix as read, every column and header name kept, with the new columns after them. */
  static void writeMatrix(final Path out, final IssuesSimilarityTask.Report r,
      final List<String> columns) throws IOException {
    writeMatrix(out, r, columns, P);
  }

  /**
   * The same for the matrix of {@code population}: of the first-commit population, without the
   * day boundary and the active business days, its target already in the matrix.
   */
  static void writeMatrix(final Path out, final IssuesSimilarityTask.Report r,
      final List<String> columns, final Population population) throws IOException {
    final List<String> all = new ArrayList<>(columns);
    all.removeAll(BusinessDays.LEGACY_RECORDED_COLUMNS);
    all.addAll(IssuesSimilarityTask.columns());
    if (population == Population.IN_PROGRESS) {
      all.addAll(BusinessDays.COLUMNS);
    }
    RowCache.write(out, r.entries(), all, List.of(population));
  }

  /** The day boundary's rule as a document, for the run record. */
  static Map<String, Object> boundaryRule() {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("history", "tracker events and comments on any home, unique issue, strictly before "
        + "the first In Progress; one unit per person per clock hour; ProjectImport events, "
        + "authorless entries and bot accounts dropped; no commit and no outcome read");
    out.put("person", "middle of the clicker's quietest " + DayBoundaries.WINDOW_HOURS
        + "-hour window, with at least " + DayBoundaries.MIN_PERSON_HOURS + " active hours");
    out.put("project", "start of the project's quietest " + DayBoundaries.WINDOW_HOURS
        + "-hour window, with at least " + DayBoundaries.MIN_PROJECT_HOURS + " person-hours");
    out.put("fallback_hour_utc", DayBoundaries.FALLBACK_HOUR);
    out.put("ties", "the boundary closest to " + DayBoundaries.FALLBACK_HOUR
        + ":00 UTC, then the earlier hour");
    out.put("bots", DayBoundaries.BOT_RULE);
    return out;
  }

  /**
   * Per side of the outer training, how many issues have active business days other than the
   * whole interval's: those the time back in Open or Reopened changed. The outer test is not
   * counted: its outcomes are not summarised before it is opened.
   */
  static Map<String, Integer> changedByActiveTime(final IssuesSimilarityTask.Report r) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    for (final String split : SPLITS.subList(0, 2)) {
      out.put(split, 0);
    }
    for (final RowCache.Entry e : r.entries()) {
      final String split = split(e);
      if (split == null || !out.containsKey(split)) {
        continue;
      }
      final int whole = BusinessDays.whole(e.unit(), r.boundaries().get(e.unit().issueId()).hour());
      if (e.row().values().get(BusinessDays.ACTIVE).intValue() != whole) {
        out.merge(split, 1, Integer::sum);
      }
    }
    return out;
  }

  /**
   * The splits the boundaries are reported by, the outer test last; the outer training is the
   * inner fitting and the inner validation, nothing set aside.
   */
  private static final List<String> SPLITS = List.of("inner_fitting", "inner_validation",
      "outer_test");

  /** Per split, how many issues took their boundary from the person, the project or 04:00. */
  static Map<String, Map<String, Integer>> sources(final IssuesSimilarityTask.Report r) {
    final Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
    for (final String split : SPLITS) {
      final Map<String, Integer> by = new LinkedHashMap<>();
      for (final DayBoundaries.Source s : DayBoundaries.Source.values()) {
        by.put(s.name().toLowerCase(Locale.ROOT), 0);
      }
      out.put(split, by);
    }
    for (final RowCache.Entry e : r.entries()) {
      final String split = split(e);
      if (split != null) {
        out.get(split).merge(r.boundaries().get(e.unit().issueId()).source().name()
            .toLowerCase(Locale.ROOT), 1, Integer::sum);
      }
    }
    return out;
  }

  /**
   * The issue's split by the outer variant, or by the inner one inside the outer training; null
   * for verdicts the split never gives, which the stage refused before.
   */
  private static String split(final RowCache.Entry e) {
    if ("TEST".equals(e.outer(P))) {
      return "outer_test";
    }
    if (!"TRAIN".equals(e.outer(P))) {
      return null;
    }
    return "TRAIN".equals(e.inner(P)) ? "inner_fitting"
        : "TEST".equals(e.inner(P)) ? "inner_validation" : null;
  }

  /** Each diagnostic file by name, as lines with a header. */
  static Map<String, List<String>> diagnostics(final IssuesSimilarityTask.Report r) {
    return diagnostics(r, P);
  }

  /**
   * The same for the matrix of {@code population}; of the first-commit population without the
   * day boundaries, which this stage does not read there.
   */
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
        + "\tdistortion"));
    final List<String> sizes = new ArrayList<>(List.of("variant\tk\tcluster\tmembers"));
    final List<String> makeup = new ArrayList<>(List.of("variant\tk\tcluster\tproject\tmembers"));
    for (final Augmentation.ClusterFit f : r.result().clusterFits()) {
      final String at = String.join("\t", name(f.variant()), Integer.toString(f.k()));
      fits.add(String.join("\t", at, Integer.toString(f.vectors()),
          Integer.toString(f.distinct()), Boolean.toString(f.fitted()),
          f.fitted() ? Double.toString(f.distortion()) : ""));
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
        + "\tdistance\tmembers"));
    for (final Augmentation.ClusterQuery q : r.result().clusterQueries()) {
      final boolean none = q.cluster() < 0;
      assigned.add(String.join("\t", q.id(), name(q.variant()), q.split(),
          Integer.toString(q.k()), none ? "" : Integer.toString(q.cluster()),
          none ? "" : Double.toString(q.distance()), Integer.toString(q.members())));
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
    if (population != Population.IN_PROGRESS) {
      return out;
    }

    // the business days of the whole interval beside the active ones the matrix holds, so the
    // issues whose count the time back in Open or Reopened changed can be found; left blank on
    // the outer test, whose outcomes are not laid out before it is opened
    final List<String> days = new ArrayList<>(List.of("id\touter\tinner\tsource\tperson_hours"
        + "\tproject_hours\tboundary_hour_utc\tbusiness_days_whole\t" + BusinessDays.ACTIVE));
    for (final RowCache.Entry e : r.entries()) {
      final DayBoundaries.Boundary b = r.boundaries().get(e.unit().issueId());
      final boolean sealed = "TEST".equals(e.outer(P));
      days.add(String.join("\t", e.unit().issueId(), e.outer(P), e.inner(P),
          b.source().name().toLowerCase(Locale.ROOT), Integer.toString(b.personHours()),
          Integer.toString(b.projectHours()), Integer.toString(b.hour()),
          sealed ? "" : Integer.toString(BusinessDays.whole(e.unit(), b.hour())),
          sealed ? "" : Integer.toString(e.row().values().get(BusinessDays.ACTIVE).intValue())));
    }
    out.put("day-boundaries.tsv", days);
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
