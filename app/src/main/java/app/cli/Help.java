package app.cli;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The {@code --help} text.
 *
 * <p>The commands are listed in the order a run takes them, from {@link StudySettings}, each with
 * the description written in {@link Command}. Only the header and the settings block are written
 * out here: they describe the process, not any single stage.
 */
public final class Help {
  private static final Set<String> ALIASES = Set.of("help", "--help", "-h");
  private static final int INDENT = 2;
  private static final int DESCRIPTION_INDENT = 6;

  public static boolean requestedBy(final String token) {
    return ALIASES.contains(token);
  }

  public static void print() {
    System.out.println("""
        Issue duration from the first commit, over the SmartSHARK 2.2 corpus.

        Estimates how many business days an issue of an Apache Java project takes from its first
        human commit to its first resolution. The app runs one design only: the first-commit
        population under the revised temporal split. The preparation commands build the prepared
        collections in smartshark_tcc once; the study's stages then run in the order below, in one
        run folder (RUN_DIR), each reading what the previous one wrote there.

        The descriptions below also mention settings of designs the app no longer runs
        (POPULATION, SPLIT_RULE, the file and database settings): those are fixed, as listed at
        the end, and printed at the top of every run.

        Usage: app <command>
        """);
    System.out.println("Preparing the corpus:\n");
    print(StudySettings.PREPARATION);
    System.out.println("The study, in run order:\n");
    print(StudySettings.stages());
    System.out.println("""
        Settings (environment, or -D system properties). A run sets only these:
          RUN_DIR           (none)              the run's folder: every file the study's stages
                                                read or write has a fixed name there
          ISSUES_DB         (none)              the database where the run's records go; one per
                                                run, since the test opens only once in each
          WAIT_FENCE_K      1.5                 the sensitivity of the wait cap, Q3 + k IQR: empty
                                                is the official 1.5, a positive number is that k,
                                                none is no cap
          MONGO_URI         mongodb://127.0.0.1:27017
          SOURCE_DB         smartshark_2_2      the corpus, read only
          MODEL_DIR         ~/.cache/tcc-minilm-java   where the embedding model is kept
          WORK_DIR          build/identity-work        reconstruct-people's working copy
          JIRA_CACHE_DIR    build/jira-cache           harvest-jira's answers
          JIRA_DELAY_MS     250                 pause between issue-tracker requests
          EMBEDDING_THREADS, ELASTIC_NET_THREADS, BOOTSTRAP_THREADS
                                                threads; the same numbers, bit for bit, on any
                                                count
          REQUIRE_MODEL     (unset)             tests only: 1 makes the model tests fail, not skip

        Fixed by the study (setting one is refused):
          the preparation   TARGET_DB=smartshark_tcc, every project, identities merged by e-mail
                            and full name
          the study         POPULATION=FIRST_COMMIT, SPLIT_RULE=revised,
                            TARGET_DB=smartshark_first_commit, PREPARED_DB=smartshark_tcc, and
                            every file inside RUN_DIR
        """);
  }

  private static void print(final List<Command> commands) {
    for (final Command command : commands) {
      System.out.println(command.token().indent(INDENT).stripTrailing());
      System.out.print(command.help().indent(DESCRIPTION_INDENT));
      System.out.println();
    }
  }

  /** Every command, the preparation first and then the study in run order. */
  static List<Command> listed() {
    return Stream.concat(StudySettings.PREPARATION.stream(), StudySettings.stages().stream())
        .toList();
  }

  private Help() {
  }
}
