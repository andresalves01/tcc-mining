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
 * @param tags every tag of every repository, read only for the release dates (ticket 19)
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
    Map<String, int[]> files,
    List<Tag> tags) {

  /** A corpus without tags, as every stage before ticket 19 read it. */
  public Corpus(final List<Issue> issues, final List<Event> events, final List<Comment> comments,
      final List<Mention> mentions, final List<Commit> commits,
      final Map<String, java.util.Set<String>> homeKeys, final Map<String, Long> prMerge,
      final Map<String, Person> people, final Map<String, Contribution> contributions,
      final Map<String, Double> complexity, final Map<String, Label> labels,
      final Map<String, int[]> files) {
    this(issues, events, comments, mentions, commits, homeKeys, prMerge, people, contributions,
        complexity, labels, files, List.of());
  }

  /**
   * One issue document. The current values are what the changelog is reversed from.
   *
   * @param links the target of each current link, as every column before ticket 19 counts them
   * @param typedLinks the same links with their type and effect (ticket 19)
   * @param fixVersions the current fix versions, by name
   */
  public record Issue(String id, String key, String project, Long created, String reporter,
      String creator, String assignee, String type, String priority, String parent,
      List<String> labels, List<String> links, List<Link> typedLinks, List<String> fixVersions) {

    /** An issue without typed links or fix versions, as every stage before ticket 19 read it. */
    public Issue(final String id, final String key, final String project, final Long created,
        final String reporter, final String creator, final String assignee, final String type,
        final String priority, final String parent, final List<String> labels,
        final List<String> links) {
      this(id, key, project, created, reporter, creator, assignee, type, priority, parent,
          labels, links, List.of(), List.of());
    }
  }

  /**
   * One issue link as Jira stores it on one side: the issue at the other end, the link's type
   * ({@code Blocker}, {@code Dependent}, ...) and its effect read from this side ({@code is blocked
   * by}, {@code blocks}, ...), which gives the direction.
   */
  public record Link(String target, String type, String effect) {}

  /**
   * One changelog entry.
   *
   * @param external the Jira changelog id and index ({@code "<changelog>%%<index>"}); orders events
   *     that share an instant
   * @param oldValue for {@code issue_links}, the id of the issue the removed link pointed to; for
   *     every other field, the value as text
   * @param oldLink for {@code issue_links}, the removed link with its type and effect; null for
   *     every other field
   * @param newLink for {@code issue_links}, the added link with its type and effect
   */
  public record Event(String issue, Long at, String field, String author, String oldValue,
      String newValue, String external, Link oldLink, Link newLink) {

    /** An event without link details, as every field but {@code issue_links} has. */
    public Event(final String issue, final Long at, final String field, final String author,
        final String oldValue, final String newValue, final String external) {
      this(issue, at, field, author, oldValue, newValue, external, null, null);
    }
  }

  /**
   * @param templated whether its text opens like a bot's template ({@link
   *     DayBoundaries#templated}); read only to recognise bot accounts
   */
  public record Comment(String issue, String author, Long at, int words, boolean templated) {}

  /** A Jira key named in the text of a comment on another issue. */
  public record Mention(String sourceIssue, Long at, String key) {}

  /** A commit and the issues its message links it to. */
  public record Commit(String id, String project, String hash, String author, Long authoredAt,
      Long committedAt, int parents, List<String> linked) {}

  /**
   * One tag of a project's repository.
   *
   * @param taggedAt the tag's own date, null for a lightweight tag
   * @param commit the id of the tagged commit
   */
  public record Tag(String project, String name, Long taggedAt, String commit) {}

  /** The thesis's canonical identity of a person record, and whether it is an automated account. */
  public record Person(String canonical, boolean bot) {}

  /** The ledger's Java volume of one commit. */
  public record Contribution(boolean bot, int javaFiles, long javaAdded, long javaDeleted) {}

  /** One label of the labelled funnel ({@code issue_label}); its issue joins the history. */
  public record Label(String project, long created, long resolved, String canonical,
      long firstCommit, double meanMccc, double meanNl) {}

  /** The text an issue carries now and the changes to it, read only for the population. */
  public record Text(String title, String description, List<Event> changes) {}
}
