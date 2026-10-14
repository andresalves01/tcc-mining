package app.cli;

import java.util.Set;

/**
 * The {@code --help} text.
 *
 * <p>
 * The command list comes from {@link Command}, so a command added to the enum
 * documents itself
 * and one removed from it stops being advertised. Each description is printed
 * as it is written
 * there, indented. Only the header and the environment block are written out
 * here: they describe
 * the process, not any single stage.
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
        Non-intrusive mining for team formation, over the SmartSHARK 2.2 corpus.

        Builds the labelled sample that team compositions are compared on: one label per issue,
        tying it to the developer who resolved it, to its creation and resolution, to the
        time-to-code, and to the methods its change touched. The stages run in the order listed
        below, each writing what the next one reads -- identity first, because every later stage
        keys on the canonical author.

        Usage: app <command>
        """);
    for (final Command command : Command.values()) {
      System.out.println(command.token().indent(INDENT).stripTrailing());
      System.out.print(command.help().indent(DESCRIPTION_INDENT));
      System.out.println();
    }
    System.out.println("""
        Environment (or -D system properties):
          MONGO_URI         mongodb://127.0.0.1:27017
          SOURCE_DB         smartshark_2_2      read-only
          TARGET_DB         smartshark_tcc      written to; must differ from SOURCE_DB
          ISSUES_DB         smartshark_issues   duracao de issues: written to; its own
          WORK_DIR          build/identity-work
          JIRA_CACHE_DIR    build/jira-cache
          JIRA_DELAY_MS     250                 pause between issue-tracker requests
          MERGE_STRICTNESS  EMAIL_AND_FULL_NAME | EMAIL_ONLY
          VOLUME            JAVA | ALL          which lines count as developer volume
          COST_MIN          1.0                 C_min, reporting only -- never stored
          COST_RATIO        1.7283              C_max / C_min, reporting only
          TRAIN_SHARE       0.70                split: the quantile of issue creation to cut at
          MIN_RESOLVED      5                   split: resolutions a (dev, project) needs first
                                                0 turns the rule off -- every pair is eligible
          TEAM_WINDOW_DAYS  90                  features: how far back the active team is read
          PILOT             (unset)             ledger and funnel: restrict to matching projects
          REPORT_DIR        (unset)             issues-description-language and
                                                issues-in-progress-similarity: where their files go
          MATRIX            (unset)             issues-in-progress-similarity: the augmented matrix
                                                written; issues-in-progress-similarity-selection:
                                                the one it reads
          TEAM_COLUMNS      (unset)             issues-in-progress-team writes it,
                                                issues-in-progress-similarity reads it
          MODEL_DIR         (unset)             issues-download-model: where the embedding model goes;
                                                the embedder reads and verifies it there
                                                (issues-in-progress-similarity)
          REQUIRE_MODEL     (unset)             tests: 1 makes the model tests fail, not skip,
                                                without a valid model in MODEL_DIR
          EMBEDDING_CACHE   (unset)             issues-in-progress-similarity: directory of the
                                                embedding cache, one file per model, revision,
                                                chunking policy and cleaner version
          SIMILARITY_PARAMETERS (unset)         issues-in-progress-similarity-selection: where the
                                                chosen values go; never the registered PARAMETERS
        """);
  }

  private Help() {
  }
}
