package app.pipeline.mongo;

import app.issues.Corpus;
import app.issues.DayBoundaries;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Reads the corpus for the issue-duration study. Read-only: every call here is a {@code find}.
 *
 * <p>Field for field what the feasibility study's Mongo probes exported, so
 * the extraction can be checked against the Python specification. Two details follow the probes'
 * JavaScript on purpose: a comment's words are counted with JavaScript's notion of space, and the
 * Jira keys a comment names are found with ASCII word boundaries and counted once per comment.
 */
public final class MongoStudySource implements StudySource {

  private static final Set<String> TEXT_FIELDS = Set.of("title", "desc");
  private static final String LINKS = "issue_links";
  private static final Pattern KEY =
      Pattern.compile("(?<![A-Za-z0-9_])([A-Z][A-Z0-9]+-[0-9]+)(?![A-Za-z0-9_])");
  private static final int BATCH = 2_000;

  private final MongoDatabase source;
  private final MongoDatabase thesis;
  private final String sourceName;
  private final String thesisName;
  private final Progress progress;

  public MongoStudySource(final MongoClient client, final String sourceDb, final String thesisDb,
      final Progress progress) {
    this.source = client.getDatabase(sourceDb);
    this.thesis = client.getDatabase(thesisDb);
    this.sourceName = sourceDb;
    this.thesisName = thesisDb;
    this.progress = progress;
  }

  @Override
  public Set<String> reads() {
    return Set.of("issue", "issue_system", "project", "vcs_system", "event", "issue_comment",
        "commit", "pull_request", "pull_request_commit", "file_action", "tag");
  }

  @Override
  public String describe() {
    return sourceName + " (corpus) + " + thesisName
        + " (person_canonical, developer_contribution, commit_complexity, issue_label)";
  }

  static String id(final Object value) {
    return value instanceof ObjectId o ? o.toHexString() : null;
  }

  static Long ms(final Date date) {
    return date == null ? null : date.getTime();
  }

  /** An event value as text: ObjectIds in hex, a link document as its target issue. */
  static String text(final Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof ObjectId o) {
      return o.toHexString();
    }
    if (value instanceof Document d) {
      return id(d.get("issue_id"));
    }
    return value.toString();
  }

  /** JavaScript's {@code \s}: the characters {@code s.trim().split(/\s+/)} splits on. */
  static boolean jsSpace(final char c) {
    return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' '
        || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028
        || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
  }

  /** {@code s.trim() === "" ? 0 : s.trim().split(/\s+/).length}. */
  static int jsWords(final String s) {
    int n = 0;
    boolean in = false;
    for (int i = 0; i < s.length(); i++) {
      if (jsSpace(s.charAt(i))) {
        in = false;
      } else if (!in) {
        in = true;
        n++;
      }
    }
    return n;
  }

  @Override
  public Corpus load() {
    // -- projects of trackers and repositories ------------------------------------------------
    final Map<Object, String> projectName = new HashMap<>();
    for (final Document p : source.getCollection("project").find()
        .projection(Projections.include("name"))) {
      projectName.put(p.get("_id"), p.getString("name"));
    }
    final Map<String, String> projectOfSystem = new HashMap<>();
    for (final Document s : source.getCollection("issue_system").find()
        .projection(Projections.include("project_id"))) {
      projectOfSystem.put(id(s.get("_id")), projectName.get(s.get("project_id")));
    }
    final Map<String, String> projectOfVcs = new HashMap<>();
    for (final Document v : source.getCollection("vcs_system").find()
        .projection(Projections.include("project_id"))) {
      projectOfVcs.put(id(v.get("_id")), projectName.get(v.get("project_id")));
    }

    // -- issues, and each tracker's dominant key ------------------------------------------------
    progress.detail("reading %s", "issue");
    final List<Corpus.Issue> issues = new ArrayList<>(210_000);
    final Map<String, Map<String, Integer>> prefixes = new HashMap<>();
    for (final Document d : source.getCollection("issue").find().projection(Projections.include(
        "external_id", "issue_system_id", "created_at", "reporter_id", "creator_id",
        "assignee_id", "issue_type", "priority", "parent_issue_id", "labels", "issue_links",
        "fix_versions"))) {
      final String system = id(d.get("issue_system_id"));
      final String key = d.getString("external_id");
      final int dash = key == null ? -1 : key.indexOf('-');
      final String prefix = key == null ? "" : dash < 0 ? key : key.substring(0, dash);
      prefixes.computeIfAbsent(system, k -> new HashMap<>()).merge(prefix, 1, Integer::sum);
      final List<String> labels = new ArrayList<>();
      final List<?> rawLabels = d.getList("labels", Object.class);
      if (rawLabels != null) {
        rawLabels.forEach(l -> labels.add(l == null ? null : l.toString()));
      }
      final List<String> links = new ArrayList<>();
      final List<Corpus.Link> typed = new ArrayList<>();
      final List<?> rawLinks = d.getList("issue_links", Object.class);
      if (rawLinks != null) {
        rawLinks.forEach(l -> links.add(l instanceof Document doc ? id(doc.get("issue_id")) : null));
        rawLinks.forEach(l -> typed.add(link(l)));
      }
      final List<String> fixVersions = new ArrayList<>();
      final List<?> rawFix = d.getList("fix_versions", Object.class);
      if (rawFix != null) {
        rawFix.forEach(v -> fixVersions.add(v == null ? null : v.toString()));
      }
      issues.add(new Corpus.Issue(id(d.get("_id")), key, projectOfSystem.get(system),
          ms(d.getDate("created_at")), id(d.get("reporter_id")), id(d.get("creator_id")),
          id(d.get("assignee_id")), d.getString("issue_type"), d.getString("priority"),
          id(d.get("parent_issue_id")), rawLabels == null ? null : labels,
          rawLinks == null ? null : links, List.copyOf(typed.stream()
              .filter(java.util.Objects::nonNull).toList()), fixVersions));
    }
    final Map<String, Set<String>> homeKeys = new HashMap<>();
    prefixes.forEach((system, counts) -> {
      String dominant = null;
      int most = -1;
      for (final Map.Entry<String, Integer> c : counts.entrySet()) {
        if (c.getValue() > most || (c.getValue() == most && c.getKey().compareTo(dominant) < 0)) {
          dominant = c.getKey();
          most = c.getValue();
        }
      }
      homeKeys.computeIfAbsent(projectOfSystem.get(system), k -> new HashSet<>()).add(dominant);
    });
    progress.detail("%,d issues", issues.size());

    // -- changelog, without the text values ------------------------------------------------------
    progress.detail("reading %s", "event");
    final List<Corpus.Event> events = new ArrayList<>(1_600_000);
    for (final Document e : source.getCollection("event").find().projection(Projections.include(
        "issue_id", "created_at", "status", "author_id", "old_value", "new_value",
        "external_id"))) {
      final String field = e.getString("status");
      final boolean text = TEXT_FIELDS.contains(field);
      final boolean link = LINKS.equals(field);
      events.add(new Corpus.Event(id(e.get("issue_id")), ms(e.getDate("created_at")), field,
          id(e.get("author_id")), text ? null : text(e.get("old_value")),
          text ? null : text(e.get("new_value")), e.getString("external_id"),
          link ? link(e.get("old_value")) : null, link ? link(e.get("new_value")) : null));
    }
    progress.detail("%,d events", events.size());

    // -- tags: name, own date and tagged commit, for the release dates -------------------------
    progress.detail("reading %s", "tag");
    final List<Corpus.Tag> tags = new ArrayList<>();
    for (final Document t : source.getCollection("tag").find().projection(
        Projections.include("vcs_system_id", "name", "date", "commit_id"))) {
      tags.add(new Corpus.Tag(projectOfVcs.get(id(t.get("vcs_system_id"))), t.getString("name"),
          ms(t.getDate("date")), id(t.get("commit_id"))));
    }
    progress.detail("%,d tags", tags.size());

    // -- comments: words and the keys they name --------------------------------------------------
    progress.detail("reading %s", "issue_comment");
    final List<Corpus.Comment> comments = new ArrayList<>(910_000);
    final List<Corpus.Mention> mentions = new ArrayList<>(220_000);
    for (final Document c : source.getCollection("issue_comment").find().projection(
        Projections.include("issue_id", "author_id", "created_at", "comment"))) {
      final String issue = id(c.get("issue_id"));
      final String body = c.getString("comment") == null ? "" : c.getString("comment");
      final Long at = ms(c.getDate("created_at"));
      comments.add(new Corpus.Comment(issue, id(c.get("author_id")), at, jsWords(body),
          DayBoundaries.templated(body)));
      if (issue != null && !body.isEmpty()) {
        final Set<String> seen = new LinkedHashSet<>();
        final Matcher m = KEY.matcher(body);
        while (m.find()) {
          seen.add(m.group(1));
        }
        seen.forEach(k -> mentions.add(new Corpus.Mention(issue, at, k)));
      }
    }
    progress.detail("%,d comments, %,d mentions", comments.size(), mentions.size());

    // -- commits: the long scan -------------------------------------------------------------------
    progress.detail("reading %s (the whole collection, a few minutes)", "commit");
    final List<Corpus.Commit> commits = new ArrayList<>(460_000);
    for (final Document c : source.getCollection("commit").find().projection(Projections.include(
        "vcs_system_id", "revision_hash", "author_id", "author_date", "committer_date",
        "parents", "linked_issue_ids"))) {
      final List<?> parents = c.getList("parents", Object.class);
      final List<String> linked = new ArrayList<>();
      final List<?> raw = c.getList("linked_issue_ids", Object.class);
      if (raw != null) {
        raw.forEach(x -> linked.add(id(x)));
      }
      commits.add(new Corpus.Commit(id(c.get("_id")), projectOfVcs.get(id(c.get("vcs_system_id"))),
          c.getString("revision_hash"), id(c.get("author_id")), ms(c.getDate("author_date")),
          ms(c.getDate("committer_date")), parents == null ? 0 : parents.size(),
          List.copyOf(linked)));
    }
    progress.detail("%,d commits", commits.size());

    // -- first merge of each sha brought by a merged pull request ------------------------------
    final Map<Object, Long> merged = new HashMap<>();
    for (final Document p : source.getCollection("pull_request")
        .find(Filters.ne("merged_at", null)).projection(Projections.include("merged_at"))) {
      merged.put(p.get("_id"), ms(p.getDate("merged_at")));
    }
    final Map<String, Long> prMerge = new HashMap<>();
    for (final Document c : source.getCollection("pull_request_commit").find()
        .projection(Projections.include("commit_sha", "pull_request_id"))) {
      final Long t = merged.get(c.get("pull_request_id"));
      final String sha = c.getString("commit_sha");
      if (t != null && sha != null) {
        prMerge.merge(sha, t, Math::min);
      }
    }

    // -- files of each commit --------------------------------------------------------------------
    progress.detail("reading %s", "file_action");
    final Map<String, Integer> fileIndex = new HashMap<>(1 << 20);
    final Map<String, List<Integer>> filesOf = new HashMap<>(1 << 19);
    for (final Document a : source.getCollection("file_action").find()
        .projection(Projections.include("commit_id", "file_id"))) {
      final String commit = id(a.get("commit_id"));
      final String file = id(a.get("file_id"));
      if (commit == null || file == null) {
        continue;
      }
      final int f = fileIndex.computeIfAbsent(file, k -> fileIndex.size());
      filesOf.computeIfAbsent(commit, k -> new ArrayList<>(4)).add(f);
    }
    final Map<String, int[]> files = new HashMap<>(filesOf.size() * 2);
    filesOf.forEach((k, v) -> files.put(k, v.stream().mapToInt(Integer::intValue).toArray()));

    // -- the thesis's tables ----------------------------------------------------------------------
    final Map<String, Corpus.Person> people = new HashMap<>();
    for (final Document p : thesis.getCollection("person_canonical").find()) {
      people.put(id(p.get("_id")), new Corpus.Person(id(p.get("canonical_id")),
          Boolean.TRUE.equals(p.getBoolean("is_bot"))));
    }
    final Map<String, Corpus.Contribution> contributions = new HashMap<>(1 << 19);
    for (final Document c : thesis.getCollection("developer_contribution").find().projection(
        Projections.include("is_bot", "java_files_touched", "java_lines_added",
            "java_lines_deleted"))) {
      contributions.put(id(c.get("_id")), new Corpus.Contribution(
          Boolean.TRUE.equals(c.getBoolean("is_bot")), number(c.get("java_files_touched")),
          number(c.get("java_lines_added")), number(c.get("java_lines_deleted"))));
    }
    final Map<String, Double> complexity = new HashMap<>(1 << 19);
    for (final Document c : thesis.getCollection("commit_complexity").find()
        .projection(Projections.include("mccc"))) {
      complexity.put(id(c.get("_id")), ((Number) c.get("mccc")).doubleValue());
    }
    final Map<String, Corpus.Label> labels = new HashMap<>();
    for (final Document l : thesis.getCollection("issue_label").find()
        .projection(Projections.exclude("methods"))) {
      labels.put(id(l.get("_id")), new Corpus.Label(l.getString("project"),
          l.getDate("created_at").getTime(), l.getDate("resolved_at").getTime(),
          id(l.get("canonical_id")), l.getDate("first_commit_at").getTime(),
          ((Number) l.get("mean_mccc")).doubleValue(), ((Number) l.get("mean_nl")).doubleValue(),
          l.getString("split")));
    }
    return new Corpus(issues, events, comments, mentions, commits, homeKeys, prMerge, people,
        contributions, complexity, labels, files, tags);
  }

  /**
   * A link document ({@code issue_id}, {@code type}, {@code effect}) as a link with its type and
   * the effect read from the side that stores it; null for anything else.
   */
  static Corpus.Link link(final Object value) {
    return value instanceof Document d
        ? new Corpus.Link(id(d.get("issue_id")), d.getString("type"), d.getString("effect"))
        : null;
  }

  private static int number(final Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  @Override
  public Map<String, Corpus.Text> texts(final Collection<String> issueIds) {
    final Map<String, Corpus.Text> out = new HashMap<>(issueIds.size() * 2);
    final Map<String, List<Corpus.Event>> changes = new HashMap<>();
    final List<String> all = new ArrayList<>(issueIds);
    for (int from = 0; from < all.size(); from += BATCH) {
      final List<ObjectId> batch = all.subList(from, Math.min(from + BATCH, all.size())).stream()
          .map(ObjectId::new).toList();
      for (final Document e : source.getCollection("event")
          .find(Filters.and(Filters.in("issue_id", batch), Filters.in("status", TEXT_FIELDS)))
          .projection(Projections.include("issue_id", "created_at", "status", "author_id",
              "old_value", "new_value", "external_id"))) {
        final String issue = id(e.get("issue_id"));
        changes.computeIfAbsent(issue, k -> new ArrayList<>()).add(new Corpus.Event(issue,
            ms(e.getDate("created_at")), e.getString("status"), id(e.get("author_id")),
            text(e.get("old_value")), text(e.get("new_value")), e.getString("external_id")));
      }
      for (final Document d : source.getCollection("issue").find(Filters.in("_id", batch))
          .projection(Projections.include("title", "desc"))) {
        final String issue = id(d.get("_id"));
        out.put(issue, new Corpus.Text(d.getString("title"), d.getString("desc"), List.of()));
      }
    }
    final Map<String, Corpus.Text> result = new HashMap<>(out.size() * 2);
    out.forEach((issue, t) -> {
      final List<Corpus.Event> ordered = new ArrayList<>(changes.getOrDefault(issue, List.of()));
      result.put(issue, new Corpus.Text(t.title(), t.description(),
          app.issues.Initial.ordered(ordered)));
    });
    return result;
  }
}
