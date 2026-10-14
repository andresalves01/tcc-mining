package app.issues;

import java.util.List;
import java.util.Map;

/**
 * Everything the issue-duration study reads from the corpus, as plain records and with no rule
 * applied yet.
 *
 * <p>Which issue counts, when a commit became visible, what a field said at creation -- every one of
 * those rules lives in the pure classes that read this ({@link Universe}, {@link World},
 * {@link Initial}, {@link Extractor}), so each can be tested on a hand-built corpus. The adapter
 * only copies fields. It is the Java twin of the feasibility study's Mongo exports, which is what lets the extraction be checked against the
 * Python specification column by column.
 *
 * <p>Instants are epoch milliseconds, {@code null} when the corpus has none.
 *
 * @param issues every issue of every tracker, the foreign copies included; the universe filters
 * @param events every changelog event, in corpus order; values of the text fields ({@code title},
 *     {@code desc}) are left out here and read only for the population ({@link Text})
 * @param homeKeys per project, the dominant Jira key of each of its trackers
 * @param prMerge per commit hash, the first instant a merged pull request containing it was merged
 * @param files per commit id, the ids of the files it touched ({@code file_action})
 */
public record Corpus(
    List<Issue> issues,
    List<Event> events,
    List<Comment> comments,
    List<Mention> mentions,
    List<Commit> commits,
    Map<String, java.util.Set<String>> homeKeys,
    Map<String, Long> prMerge,
    Map<String, Person> people,
    Map<String, Contribution> contributions,
    Map<String, Double> complexity,
    Map<String, Label> labels,
    Map<String, int[]> files) {

  /** One issue document. The current values are what the changelog is reversed from. */
  public record Issue(String id, String key, String project, Long created, String reporter,
      String creator, String assignee, String type, String priority, String parent,
      List<String> labels, List<String> links) {}

  /**
   * One changelog entry.
   *
   * @param external the Jira changelog id and index ({@code "<changelog>%%<index>"}); orders events
   *     that share an instant
   * @param oldValue for {@code issue_links}, the id of the issue the removed link pointed to; for
   *     every other field, the value as text
   */
  public record Event(String issue, Long at, String field, String author, String oldValue,
      String newValue, String external) {}

  public record Comment(String issue, String author, Long at, int words) {}

  /** A Jira key named in the text of a comment on another issue. */
  public record Mention(String sourceIssue, Long at, String key) {}

  /** A commit and the issues its message links it to. */
  public record Commit(String id, String project, String hash, String author, Long authoredAt,
      Long committedAt, int parents, List<String> linked) {}

  /** The thesis's canonical identity of a person record, and whether it is an automated account. */
  public record Person(String canonical, boolean bot) {}

  /** The ledger's Java volume of one commit. */
  public record Contribution(boolean bot, int javaFiles, long javaAdded, long javaDeleted) {}

  /** One label of the previous study's population, for the inherited population. */
  public record Label(String project, long created, long resolved, String canonical,
      long firstCommit, double meanMccc, double meanNl, String split) {}

  /** The text an issue carries now and the changes to it, read only for the population. */
  public record Text(String title, String description, List<Event> changes) {}
}
