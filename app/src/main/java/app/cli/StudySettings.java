package app.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The only way the app runs: the revised first-commit study, from one run folder.
 *
 * <p>The design is fixed. The population, the split rule, the databases and every file a stage
 * reads or writes are set here, the files at fixed names inside {@code RUN_DIR}, so each stage
 * finds what the previous one wrote. A run chooses only what {@link #OPEN} lists: where the corpus
 * and the run's records are, the wait fence of the sensitivity, and where local caches go and how
 * many threads to use, which change no number. Any other setting is refused before anything is
 * read, so no run can take a route the study does not report.
 *
 * <p>The values are handed to the stages as system properties, which every stage already reads
 * when the environment has no value, so no stage computes anything differently.
 */
public final class StudySettings {

  /** The setting that names the run folder. */
  public static final String RUN_DIR = "RUN_DIR";

  /** What a run may set. Everything else a stage reads is fixed. */
  public static final Set<String> OPEN = Set.of(
      "MONGO_URI", "SOURCE_DB", RUN_DIR, "ISSUES_DB", "WAIT_FENCE_K",
      "MODEL_DIR", "WORK_DIR", "JIRA_CACHE_DIR", "JIRA_DELAY_MS",
      "EMBEDDING_THREADS", "ELASTIC_NET_THREADS", "BOOTSTRAP_THREADS");

  /** Every fixed setting a stage reads; giving one is refused. */
  static final Set<String> FIXED = Set.of(
      "POPULATION", "SPLIT_RULE", "TARGET_DB", "PREPARED_DB", "MERGE_STRICTNESS", "PILOT",
      "CACHE", "TEAM_COLUMNS", "MATRIX", "RETROSPECTIVE_MATRIX", "ELASTIC_NET_PARAMETERS",
      "REPORT_DIR", "TEST_PREDICTIONS", "RUN_LOG", "OUT_DIR", "AUDIT_DIR", "SUPPORTING_DIR",
      "EMBEDDING_CACHE", "BAND_TOPS", "USUAL_FENCE", "SPLIT_REFERENCE_MATRIX",
      "ANALOGY_DIR", "ADJUSTED_DIR", "ADJUSTED_STEP", "BOT_ACCOUNTS", "START_DESCRIPTIONS",
      "FROZEN_TRAIN_MATRIX");

  /** The commands that prepare the corpus, into smartshark_tcc, before any run. */
  public static final List<Command> PREPARATION = List.of(Command.CHECK,
      Command.RECONSTRUCT_PEOPLE, Command.HARVEST_JIRA, Command.BUILD_LEDGER, Command.FUNNEL,
      Command.COMPLEXITY);

  /** The design of the revised first-commit study, the one the paper reports. */
  private static final Map<String, String> DESIGN = ordered(
      "POPULATION", "FIRST_COMMIT",
      "SPLIT_RULE", "revised",
      "TARGET_DB", "smartshark_first_commit",
      "PREPARED_DB", "smartshark_tcc");

  /** Each stage of the study and its files, relative to the run folder, in run order. */
  private static final Map<Command, Map<String, String>> STAGES = layout();

  private static Map<Command, Map<String, String>> layout() {
    final String matrix = "retrospective-matrix.tsv";
    final String parameters = "elastic-net-parameters.txt";
    final Map<Command, Map<String, String>> s = new LinkedHashMap<>();
    s.put(Command.ISSUES_DOWNLOAD_MODEL, Map.of());
    s.put(Command.ISSUES_FIRST_COMMIT_AUDIT, ordered("AUDIT_DIR", "audit"));
    s.put(Command.ISSUES_FIRST_COMMIT_SUPPORTING_NUMBERS,
        ordered("SUPPORTING_DIR", "supporting-numbers"));
    s.put(Command.ISSUES_IN_PROGRESS_EXTRACT, ordered("CACHE", "first-commit-matrix.tsv"));
    s.put(Command.ISSUES_IN_PROGRESS_TEAM, ordered(
        "CACHE", "first-commit-matrix.tsv",
        "TEAM_COLUMNS", "team-columns.tsv"));
    s.put(Command.ISSUES_SIMILARITY, ordered(
        "CACHE", "first-commit-matrix.tsv",
        "TEAM_COLUMNS", "team-columns.tsv",
        "MATRIX", "augmented-matrix.tsv",
        "REPORT_DIR", "similarity-reports",
        "EMBEDDING_CACHE", "embedding-cache"));
    s.put(Command.ISSUES_RETROSPECTIVE, ordered(
        "MATRIX", "augmented-matrix.tsv",
        "RETROSPECTIVE_MATRIX", matrix));
    s.put(Command.ISSUES_SIMILARITY_ELASTIC_NET, ordered(
        "MATRIX", matrix,
        "ELASTIC_NET_PARAMETERS", parameters));
    s.put(Command.ISSUES_SIMILARITY_INNER_REPORT, ordered(
        "MATRIX", matrix,
        "ELASTIC_NET_PARAMETERS", parameters,
        "REPORT_DIR", "inner-report"));
    s.put(Command.ISSUES_FIRST_COMMIT_SHARE_ABLATION, ordered(
        "MATRIX", matrix,
        "ELASTIC_NET_PARAMETERS", parameters,
        "REPORT_DIR", "share-ablation"));
    s.put(Command.ISSUES_FIRST_COMMIT_TEST, ordered(
        "MATRIX", matrix,
        "ELASTIC_NET_PARAMETERS", parameters,
        "REPORT_DIR", "test-report"));
    s.put(Command.ISSUES_FIRST_COMMIT_SAME_MODEL_CALIBRATION, ordered(
        "MATRIX", matrix,
        "ELASTIC_NET_PARAMETERS", parameters,
        "TEST_PREDICTIONS", "test-report/similarity-test-predictions.tsv",
        "REPORT_DIR", "same-model-calibration"));
    s.put(Command.ISSUES_FIRST_COMMIT_PREDICTED_BAND, ordered(
        "REPORT_DIR", "test-report",
        "RUN_LOG", "test.log",
        "OUT_DIR", "predicted-band"));
    return s;
  }

  /** Where the embedding model is kept unless MODEL_DIR says otherwise; the tests look there too. */
  private static final Path MODEL_DIR =
      Path.of(System.getProperty("user.home"), ".cache", "tcc-minilm-java");

  /**
   * Refuses a run that sets anything fixed, then fills in, for {@code command}, the study's
   * settings through {@code set} and returns them in order.
   *
   * @param lookup a setting's value, or null or blank when it is unset
   * @throws IllegalArgumentException when a fixed setting is given, or a stage of the study runs
   *     without {@code RUN_DIR} or {@code ISSUES_DB}
   */
  public static Map<String, String> apply(final Command command,
      final Function<String, String> lookup, final BiConsumer<String, String> set) {
    final List<String> given = new ArrayList<>();
    for (final String key : FIXED) {
      if (!blank(lookup.apply(key))) {
        given.add(key);
      }
    }
    if (!given.isEmpty()) {
      given.sort(null);
      throw new IllegalArgumentException(String.join(", ", given) + (given.size() == 1
          ? " is" : " are") + " fixed by the study and cannot be set; unset "
          + (given.size() == 1 ? "it" : "them") + ". A run sets only RUN_DIR, ISSUES_DB, "
          + "WAIT_FENCE_K, MONGO_URI and SOURCE_DB (see app help)");
    }
    final Map<String, String> files = STAGES.get(command);
    if (files == null) {
      return Map.of();
    }
    final Map<String, String> wanted = new LinkedHashMap<>();
    if (command != Command.ISSUES_DOWNLOAD_MODEL) {
      final String runDir = lookup.apply(RUN_DIR);
      if (blank(runDir)) {
        throw new IllegalArgumentException(command.token() + " needs RUN_DIR, the run's folder: "
            + "every file of the run is read and written there");
      }
      final Path dir = Path.of(runDir).toAbsolutePath().normalize();
      if (blank(lookup.apply("ISSUES_DB"))) {
        throw new IllegalArgumentException(command.token() + " needs ISSUES_DB, the database "
            + "where this run's records go, one per run (for example "
            + "ISSUES_DB=smartshark_first_commit_issues_" + dir.getFileName() + ")");
      }
      wanted.putAll(DESIGN);
      files.forEach((key, file) -> wanted.put(key, dir.resolve(file).toString()));
    }
    if (List.of(Command.ISSUES_DOWNLOAD_MODEL, Command.ISSUES_SIMILARITY).contains(command)
        && blank(lookup.apply("MODEL_DIR"))) {
      wanted.put("MODEL_DIR", MODEL_DIR.toString());
    }
    wanted.forEach(set);
    return wanted;
  }

  /** The stages of the study, in the order a run takes them. */
  public static List<Command> stages() {
    return List.copyOf(STAGES.keySet());
  }

  private static boolean blank(final String value) {
    return value == null || value.isBlank();
  }

  private static Map<String, String> ordered(final String... pairs) {
    final Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private StudySettings() {}
}
