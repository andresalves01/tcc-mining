package app.pipeline.mongo;

import app.labels.ChangedMethod;
import app.labels.CommitMethods;
import app.labels.FunnelStage;
import app.labels.IssueLabel;
import app.labels.LabelScan;
import app.labels.MethodDeduplication;
import app.labels.port.LabelSource;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Builds the global funnel from the corpus.
 *
 * <p>The order of the stages is the order of the exclusions, and it is not interchangeable. Bots
 * are dropped before distinct authors are counted, because a robot has no skill to share and an
 * issue worked by one human and one robot is not multi-developer work. The Java restriction comes
 * next, so that "the first eligible commit" means the first commit that touched the language the
 * metrics are measured in, rather than the first commit of any kind.
 *
 * <p>Method extraction is the last stage because it is the expensive one: it reads three
 * collections per commit and only pays off on issues that already survived everything cheaper.
 */
public final class MongoLabelSource implements LabelSource {

  private static final String ISSUE = "issue";
  private static final String ISSUE_SYSTEM = "issue_system";
  private static final String EVENT = "event";
  private static final String COMMIT = "commit";
  private static final String FILE = "file";
  private static final String PROJECT = "project";

  /** The collections this source opens. Declared here so it cannot drift from
   * the queries above it; {@link app.pipeline.mongo.MongoCorpusCheck} verifies it. The method
   * reader's three come from {@link ChangedMethods} rather than being repeated. */
  public static final Set<String> READS = Stream.concat(
      Stream.of(ISSUE, ISSUE_SYSTEM, EVENT, COMMIT, FILE, PROJECT),
      ChangedMethods.READS.stream()).collect(Collectors.toUnmodifiableSet());
  private static final Set<String> RESOLVED = Set.of("Resolved", "Closed");
  private static final int LINK_BATCH = 2_000;

  private final MongoDatabase source;
  private final MongoDatabase target;
  private final Progress progress;

  public MongoLabelSource(final MongoClient client, final String sourceDb, final String targetDb,
      final Progress progress) {
    this.source = client.getDatabase(sourceDb);
    this.target = client.getDatabase(targetDb);
    this.progress = progress;
  }

  /** One linked commit, already carrying everything the issue-level rules need. */
  private record Linked(ObjectId id, String revisionHash, String canonical, Instant authoredAt,
      boolean merge, boolean touchesJava, long javaAdded, long javaDeleted, String project) {}

  /** The three ledger fields the funnel needs, kept instead of the whole row: there are 423k. */
  private record Volume(boolean touchesJava, int javaAdded, int javaDeleted, String project) {}

  @Override
  public LabelScan scan(final String pilot, final Map<String, String> canonicalByPerson,
      final Set<String> bots) {
    progress.phase(1, 6, "projects and issues");
    final Map<ObjectId, String> projectByIssueSystem = projectByIssueSystem();
    final Map<ObjectId, Instant> created = new HashMap<>(1 << 18);
    final Map<ObjectId, String> issueProject = new HashMap<>(1 << 18);
    for (final Document issue : source.getCollection(ISSUE)
        .find().projection(Projections.include("created_at", "issue_system_id"))) {
      final Date at = issue.getDate("created_at");
      final String project = projectByIssueSystem.get(issue.getObjectId("issue_system_id"));
      if (project == null || (pilot != null && !project.contains(pilot))) {
        continue;
      }
      // An issue with no creation instant still enters the funnel and is excluded by the stage
      // that asks for one. Dropping it here instead would shrink the first stage's denominator and
      // hide the exclusion inside a number nobody can audit.
      issueProject.put(issue.getObjectId("_id"), project);
      if (at != null) {
        created.put(issue.getObjectId("_id"), at.toInstant());
      }
    }
    progress.detail("%,d issues, %,d with a creation instant",
        issueProject.size(), created.size());

    progress.phase(2, 6, "first resolution");
    final Map<ObjectId, Instant> resolved = firstResolution();
    progress.detail("%,d issues reach Resolved or Closed", resolved.size());

    progress.phase(3, 6, "contribution ledger");
    final Map<ObjectId, Volume> ledger = new HashMap<>(1 << 20);
    for (final Document row : target.getCollection(MongoContributionLedger.DEVELOPER_CONTRIBUTION)
        .find().projection(Projections.include(
            "java_files_touched", "java_lines_added", "java_lines_deleted", "project"))) {
      ledger.put(row.getObjectId("_id"), new Volume(
          row.getInteger("java_files_touched", 0) > 0,
          row.getInteger("java_lines_added", 0),
          row.getInteger("java_lines_deleted", 0),
          row.getString("project")));
    }
    progress.detail("%,d contributions", ledger.size());

    progress.phase(4, 6, "issue to commit linkage");
    final Map<ObjectId, List<Linked>> byIssue =
        linkage(issueProject.keySet(), ledger, issueProject, canonicalByPerson);
    progress.detail("%,d issues carry at least one linked commit", byIssue.size());

    return funnel(byIssue, created, resolved, issueProject, bots);
  }

  /** Stages 1 to 7, in the order the exclusions apply. */
  private LabelScan funnel(final Map<ObjectId, List<Linked>> byIssue,
      final Map<ObjectId, Instant> created, final Map<ObjectId, Instant> resolved,
      final Map<ObjectId, String> issueProject, final Set<String> bots) {

    final List<FunnelStage> stages = new ArrayList<>();
    Map<ObjectId, List<Linked>> current = new LinkedHashMap<>();
    for (final Map.Entry<ObjectId, List<Linked>> entry : byIssue.entrySet()) {
      final List<Linked> keep = entry.getValue().stream().filter(c -> !c.merge()).toList();
      if (!keep.isEmpty()) {
        current.put(entry.getKey(), keep);
      }
    }
    stages.add(new FunnelStage("issues com >=1 commit ligado nao-merge",
        current.size(), 0, current.size()));

    current = keepIf(current, stages, "apos remocao de bots",
        c -> c.canonical() != null && !bots.contains(c.canonical()));
    current = keepIf(current, stages, "apos recorte Java", Linked::touchesJava);

    Map<ObjectId, List<Linked>> next = new LinkedHashMap<>();
    for (final Map.Entry<ObjectId, List<Linked>> e : current.entrySet()) {
      final Instant ti = created.get(e.getKey());
      final Instant tr = resolved.get(e.getKey());
      if (ti != null && tr != null && tr.isAfter(ti)) {
        next.put(e.getKey(), e.getValue());
      }
    }
    stages.add(stage("apos criacao e resolucao recuperaveis e TTC positivo", next, current));
    current = next;

    next = new LinkedHashMap<>();
    for (final Map.Entry<ObjectId, List<Linked>> e : current.entrySet()) {
      if (e.getValue().stream().map(Linked::canonical).distinct().count() == 1) {
        next.put(e.getKey(), e.getValue());
      }
    }
    stages.add(stage("apos exigir um unico autor humano", next, current));
    current = next;

    next = new LinkedHashMap<>();
    for (final Map.Entry<ObjectId, List<Linked>> e : current.entrySet()) {
      if (!first(e.getValue()).authoredAt().isBefore(created.get(e.getKey()))) {
        next.put(e.getKey(), e.getValue());
      }
    }
    stages.add(stage("apos excluir 1o commit elegivel anterior a criacao", next, current));
    current = next;

    progress.phase(5, 6, "java file ids");
    final Set<ObjectId> javaFiles = javaFileIds();
    progress.detail("%,d .java files", javaFiles.size());

    progress.phase(6, 6, "changed methods over %,d issues", current.size());
    final List<IssueLabel> labels = new ArrayList<>(current.size());
    long extracted = 0;
    int seen = 0;
    for (final Map.Entry<ObjectId, List<Linked>> e : current.entrySet()) {
      final List<CommitMethods> perCommit = new ArrayList<>();
      for (final Linked c : e.getValue()) {
        final Map<String, ChangedMethod> methods = methodsOf(c.id(), javaFiles);
        if (!methods.isEmpty()) {
          perCommit.add(new CommitMethods(c.revisionHash(), c.authoredAt(), methods));
        }
      }
      final List<ChangedMethod> deduplicated = MethodDeduplication.byLatestChange(perCommit);
      extracted += deduplicated.size();
      if (!deduplicated.isEmpty()) {
        final Linked head = first(e.getValue());
        labels.add(new IssueLabel(e.getKey().toHexString(), issueProject.get(e.getKey()),
            created.get(e.getKey()), resolved.get(e.getKey()), head.canonical(),
            head.authoredAt(), e.getValue().size(),
            e.getValue().stream().mapToLong(Linked::javaAdded).sum(),
            e.getValue().stream().mapToLong(Linked::javaDeleted).sum(),
            deduplicated));
      }
      if (++seen % 5_000 == 0) {
        progress.count(seen, current.size(), "issues, %,d labels", labels.size());
      }
    }
    stages.add(new FunnelStage("apos exigir >=1 metodo alterado com McCC e NL",
        labels.size(), current.size() - labels.size(), current.size()));
    return new LabelScan(List.copyOf(labels), List.copyOf(stages), extracted);
  }

  /** Delegates to the shared reader, keeping only what an issue-level label needs of it. */
  private Map<String, ChangedMethod> methodsOf(final ObjectId commitId,
      final Set<ObjectId> javaFiles) {
    return new ChangedMethods(source).of(commitId, javaFiles).recovered();
  }

  private Map<ObjectId, List<Linked>> linkage(final Set<ObjectId> issues,
      final Map<ObjectId, Volume> ledger, final Map<ObjectId, String> issueProject,
      final Map<String, String> canonicalByPerson) {

    final Map<ObjectId, List<Linked>> byIssue = new HashMap<>(1 << 18);
    final List<ObjectId> all = new ArrayList<>(issues);
    for (int from = 0; from < all.size(); from += LINK_BATCH) {
      final List<ObjectId> batch = all.subList(from, Math.min(from + LINK_BATCH, all.size()));
      final Set<ObjectId> wanted = new HashSet<>(batch);
      for (final Document commit : source.getCollection(COMMIT)
          .find(Filters.in("linked_issue_ids", batch))
          .projection(Projections.include(
              "linked_issue_ids", "author_id", "author_date", "parents", "revision_hash"))) {

        final Date at = commit.getDate("author_date");
        final ObjectId authorId = commit.getObjectId("author_id");
        if (at == null || authorId == null) {
          continue;
        }
        final ObjectId commitId = commit.getObjectId("_id");
        final Volume row = ledger.get(commitId);
        final List<?> parents = commit.getList("parents", Object.class);
        final Linked linked = new Linked(commitId, commit.getString("revision_hash"),
            canonicalByPerson.get(authorId.toHexString()), at.toInstant(),
            parents != null && parents.size() > 1,
            row != null && row.touchesJava(),
            row == null ? 0 : row.javaAdded(),
            row == null ? 0 : row.javaDeleted(),
            row == null ? null : row.project());

        for (final Object issue : commit.getList("linked_issue_ids", Object.class)) {
          final ObjectId issueId = (ObjectId) issue;
          if (wanted.contains(issueId) && issueProject.containsKey(issueId)) {
            byIssue.computeIfAbsent(issueId, key -> new ArrayList<>()).add(linked);
          }
        }
      }
      if ((from / LINK_BATCH) % 20 == 0) {
        progress.count(from, all.size(), "issues probed");
      }
    }
    return byIssue;
  }

  private Map<ObjectId, Instant> firstResolution() {
    final Map<ObjectId, Instant> first = new HashMap<>(1 << 18);
    for (final Document event : source.getCollection(EVENT)
        .find(Filters.eq("status", "status"))
        .projection(Projections.include("issue_id", "created_at", "new_value"))) {
      final Object value = event.get("new_value");
      final Date at = event.getDate("created_at");
      final ObjectId issueId = event.getObjectId("issue_id");
      if (at == null || issueId == null || value == null || !RESOLVED.contains(value.toString())) {
        continue;
      }
      first.merge(issueId, at.toInstant(), (a, b) -> a.isBefore(b) ? a : b);
    }
    return first;
  }

  private Map<ObjectId, String> projectByIssueSystem() {
    final Map<ObjectId, String> nameById = new HashMap<>();
    for (final Document project : source.getCollection(PROJECT)
        .find().projection(Projections.include("name"))) {
      nameById.put(project.getObjectId("_id"), project.getString("name"));
    }
    final Map<ObjectId, String> byIssueSystem = new HashMap<>();
    for (final Document system : source.getCollection(ISSUE_SYSTEM)
        .find().projection(Projections.include("project_id"))) {
      byIssueSystem.put(system.getObjectId("_id"), nameById.get(system.getObjectId("project_id")));
    }
    return byIssueSystem;
  }

  private Set<ObjectId> javaFileIds() {
    return ChangedMethods.javaFileIds(source, FILE);
  }

  /** Earliest by authoring instant, revision hash breaking ties, so "first" is reproducible. */
  private static Linked first(final List<Linked> commits) {
    return commits.stream()
        .min(java.util.Comparator.comparing(Linked::authoredAt)
            .thenComparing(c -> c.revisionHash() == null ? "" : c.revisionHash()))
        .orElseThrow();
  }

  private Map<ObjectId, List<Linked>> keepIf(final Map<ObjectId, List<Linked>> current,
      final List<FunnelStage> stages, final String name,
      final java.util.function.Predicate<Linked> keep) {

    final Map<ObjectId, List<Linked>> next = new LinkedHashMap<>();
    for (final Map.Entry<ObjectId, List<Linked>> entry : current.entrySet()) {
      final List<Linked> kept = entry.getValue().stream().filter(keep).toList();
      if (!kept.isEmpty()) {
        next.put(entry.getKey(), kept);
      }
    }
    stages.add(stage(name, next, current));
    return next;
  }

  private static FunnelStage stage(final String name, final Map<ObjectId, ?> next,
      final Map<ObjectId, ?> current) {
    return new FunnelStage(name, next.size(), current.size() - next.size(), current.size());
  }


}
