package app.pipeline.port;

import java.util.List;
import java.util.Set;

/**
 * The source corpus, asked whether it can serve a stage before that stage spends an hour finding
 * out.
 *
 * <p>A port, so the CLI names a stage and never a collection. Which collections a stage costs, and
 * what their absence means, belongs to the adapter that does the reading -- that is the whole point
 * of asking through here rather than querying a database from the command layer.
 */
public interface Corpus {

  /** The stages that read the corpus. The others read only what an earlier stage wrote. */
  enum Stage {
    RECONSTRUCT_PEOPLE,
    HARVEST_JIRA,
    BUILD_LEDGER,
    FUNNEL,
    COMPLEXITY
  }

  /**
   * Why {@code stage} cannot run yet, ready to print, or empty when it can.
   *
   * <p>Sentences rather than names: the caller decides that an answer here is a refusal, not what
   * the gap was.
   */
  List<String> whyCannotRun(Stage stage);

  /** Everything every stage reads, for a report that covers the whole corpus. */
  Survey survey();

  /**
   * One thing a stage depends on, and whether it is there to depend on.
   *
   * @param state what to print in a report: a count, or why there is none
   * @param usable whether a stage reading it would find anything
   */
  record Entry(String name, String state, boolean usable, Set<Stage> neededBy) {}

  /** What the corpus holds, described rather than queried. */
  record Survey(String server, String documentedServer, String database, List<Entry> entries,
      List<String> diagnosis) {

    public Survey {
      entries = List.copyOf(entries);
      diagnosis = List.copyOf(diagnosis);
    }

    /** Whether everything {@code stage} reads is present and populated. */
    public boolean satisfies(final Stage stage) {
      return entries.stream().filter(entry -> entry.neededBy().contains(stage))
          .allMatch(entry -> entry.usable());
    }
  }
}
