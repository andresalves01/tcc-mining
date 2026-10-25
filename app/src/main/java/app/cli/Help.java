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
        Issue duration from In Progress, over the SmartSHARK 2.2 corpus.

        Estimates how many active business days an issue of an Apache Java project takes from the
        start of the work (its first In Progress) to its first resolution. The stages run in the
        order listed below, each writing what the next one reads: identity first, because every
        later stage keys on the canonical author; then the contribution ledger, the labelled
        funnel and the per-commit complexity the extraction reads; then the issue stages.

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
          ISSUES_DB         smartshark_issues   the issue stages' run records: written to; its own
          CACHE             (unset)             issues-in-progress-extract: the matrix written;
                                                issues-in-progress-similarity: the one it reads
          WORK_DIR          build/identity-work
          JIRA_CACHE_DIR    build/jira-cache
          JIRA_DELAY_MS     250                 pause between issue-tracker requests
          MERGE_STRICTNESS  EMAIL_AND_FULL_NAME | EMAIL_ONLY
          PILOT             (unset)             ledger and funnel: restrict to matching projects
          REPORT_DIR        (unset)             issues-in-progress-similarity,
                                                issues-in-progress-similarity-test and
                                                -inner-report: where their files go
          MATRIX            (unset)             issues-in-progress-similarity: the augmented matrix
                                                written; issues-in-progress-retrospective,
                                                issues-in-progress-similarity-elastic-net, -test
                                                and -inner-report: the one they read (for the
                                                last three, the retrospective matrix, which has
                                                every model's columns)
          RETROSPECTIVE_MATRIX (unset)          issues-in-progress-retrospective: the matrix with
                                                the nine retrospective columns written
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
          EMBEDDING_THREADS (processors)        issues-in-progress-similarity: threads that embed
                                                the descriptions the cache misses; 1 embeds them
                                                one by one. Same vectors, bit for bit, on any
                                                number of threads
          ELASTIC_NET_THREADS 1                 threads for the Elastic Net fits: the sweep's
                                                points and the similarity test's refits; 1 fits
                                                them in sequence. Same numbers, bit for bit, on
                                                any number of threads
          BOOTSTRAP_THREADS (processors)        threads for the draws of the project-resampled
                                                intervals; 1 runs them in sequence. Same
                                                intervals, bit for bit, on any number of threads
          ELASTIC_NET_PARAMETERS (unset)        issues-in-progress-similarity-elastic-net: where
                                                each model's alpha and lambda go, and the Jaccard
                                                n, embedding n and cluster k of the three
                                                models with the similarity medians, and the tail
                                                cut's k, chosen on the own start model, both
                                                fences and each k's error;
                                                issues-in-progress-similarity-test and
                                                -inner-report read it
          AUDIT_DIR         (unset)             issues-first-commit-audit: where audit.md and
                                                per-issue.tsv go
        """);
  }

  private Help() {
  }
}
