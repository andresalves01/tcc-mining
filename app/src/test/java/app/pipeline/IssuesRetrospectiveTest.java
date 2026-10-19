package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation.Variant;
import app.issues.BusinessDays;
import app.issues.Corpus;
import app.issues.IssueUnit;
import app.issues.ProjectSplit;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.port.ChangedMethodSource;
import app.issues.port.StudySource;
import app.labels.ChangedMethod;
import app.pipeline.port.Progress;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retrospective stage over the similarity stage's matrix (ticket 15), on a corpus and methods
 * held in memory: never on real data.
 */
class IssuesRetrospectiveTest {

  private static final long D = 86_400_000L;
  private static final long T0 = 1_000 * D;

  private final List<Corpus.Issue> issues = new ArrayList<>();
  private final List<Corpus.Event> events = new ArrayList<>();
  private final List<Corpus.Comment> comments = new ArrayList<>();
  private final List<Corpus.Commit> commits = new ArrayList<>();
  private final Map<String, Corpus.Contribution> contributions = new HashMap<>();
  private final Map<String, int[]> files = new HashMap<>();
  private final Map<String, Map<String, ChangedMethod>> methods = new HashMap<>();
  private final List<RowCache.Entry> entries = new ArrayList<>();
  private final Set<String> asked = new TreeSet<>();
  private final List<Corpus.Tag> tags = new ArrayList<>();
  private int ext = 1;

  /** An issue In Progress at {@code started}, Fixed at {@code resolved}, in the given split. */
  private void issue(final String id, final long started, final long resolved,
      final String outer, final String inner) {
    issues.add(new Corpus.Issue(id, "P-" + id, "p", T0, "r", "r", null, "Bug", "Major", null,
        List.of(), List.of()));
    events.add(new Corpus.Event(id, started, "status", "dev", "Open", "In Progress",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "status", "d", "In Progress", "Resolved",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "resolution", "d", null, "Fixed",
        (ext - 1) + "%%1"));
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    entries.add(new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "d",
        Instant.ofEpochMilli(T0), Instant.ofEpochMilli(resolved), "Fixed", false,
        Instant.ofEpochMilli(started)),
        new Row(id, target(started, resolved), Set.of()), parts));
  }

  /** The matrix's columns of the target: no silent day, the active count in both variants. */
  private static Map<String, Double> target(final long started, final long resolved) {
    final double days = BusinessDays.count(started, resolved, 4);
    final Map<String, Double> out = new HashMap<>(Map.of("A01_desc_length", 1.0,
        BusinessDays.BOUNDARY, 4.0, BusinessDays.ACTIVE, days));
    for (final Variant v : Variant.values()) {
      out.put(BusinessDays.recorded(v), days);
      out.put(BusinessDays.threshold(v), BusinessDays.LEAST_SILENT);
    }
    return out;
  }

  private void commit(final String id, final long at, final long java, final String issue) {
    commits.add(new Corpus.Commit(id, "p", "h" + id, "coder", at, at, 1, List.of(issue)));
    contributions.put(id, new Corpus.Contribution(false, java > 0 ? 1 : 0, java, 0));
    files.put(id, new int[] {id.hashCode()});
  }

  private StudySource source() {
    final Corpus corpus = new Corpus(issues, events, comments, List.of(), commits,
        Map.of("p", Set.of("P")), Map.of(), Map.of(), contributions, Map.of(), Map.of(), files,
        tags);
    return new StudySource() {
      @Override
      public Corpus load() {
        return corpus;
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        throw new AssertionError("the texts are not read");
      }

      @Override
      public Set<String> reads() {
        return Set.of();
      }

      @Override
      public String describe() {
        return "in memory";
      }
    };
  }

  private ChangedMethodSource changed() {
    return new ChangedMethodSource() {
      @Override
      public Map<String, Map<String, ChangedMethod>> changed(final Collection<String> ids) {
        asked.addAll(ids);
        final Map<String, Map<String, ChangedMethod>> out = new HashMap<>();
        ids.stream().filter(methods::containsKey).forEach(id -> out.put(id, methods.get(id)));
        return out;
      }
    };
  }

  /** The hand-assigned split taken as it is; the rule's check is {@link #refuses}' last. */
  private IssuesRetrospectiveTask task() {
    return new IssuesRetrospectiveTask(source(), changed(), Progress.silent(), e -> List.of());
  }

  private static RowCache.Entry entry(final IssuesRetrospectiveTask.Report r, final String id) {
    return r.entries().stream().filter(e -> e.unit().issueId().equals(id)).findFirst()
        .orElseThrow();
  }

  /** Fitting a, b; validation v; outer test t. */
  private void corpus() {
    issue("a", T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    issue("b", T0 + 2 * D, T0 + 11 * D, "TRAIN", "TRAIN");
    issue("v", T0 + 20 * D, T0 + 25 * D, "TRAIN", "TEST");
    issue("t", T0 + 30 * D, T0 + 35 * D, "TEST", "");
    commit("ca", T0 + 2 * D, 10, "a");
    methods.put("ca", Map.of("M.m()", new ChangedMethod("M.m()", 3, 2)));
    commit("late", T0 + 12 * D, 10, "a");
    methods.put("late", Map.of("M.n()", new ChangedMethod("M.n()", 99, 99)));
    commit("cv", T0 + 21 * D, 0, "v");
    commit("ct", T0 + 31 * D, 10, "t");
    comments.add(new Corpus.Comment("a", "u", T0 + 3 * D, 4, false));
  }

  @Test
  @DisplayName("the eight columns join every row of the matrix, the earlier columns kept, a missing "
      + "value missing; only the implementation commits' methods are asked for")
  void addsTheEightColumns() {
    corpus();
    final IssuesRetrospectiveTask.Report r = task().run(entries);
    assertEquals(List.of(), r.refused());
    assertEquals(entries.size(), r.entries().size());
    assertEquals(Set.of("ca", "cv", "ct"), asked);
    final RowCache.Entry a = entry(r, "a");
    assertEquals(1.0, a.row().values().get("A01_desc_length"));
    assertEquals(10.0, a.row().values().get(Retrospective.LINES));
    assertEquals(3.0, a.row().values().get(Retrospective.MCCC));
    assertEquals(1.0, a.row().values().get(Retrospective.COMMENTS));
    final RowCache.Entry b = entry(r, "b");
    for (final String column : Retrospective.CONTENT) {
      assertFalse(b.row().values().containsKey(column), column);
      assertTrue(b.row().absent().contains(column), column);
    }
    assertEquals(0.0, b.row().values().get(Retrospective.COMMENTS));
    final RowCache.Entry v = entry(r, "v");
    assertEquals(0.0, v.row().values().get(Retrospective.LINES));
    assertTrue(v.row().absent().contains(Retrospective.MCCC));
    assertEquals(a.parts(), entries.get(0).parts());
  }

  @Test
  @DisplayName("urgency reads the release of the fix version from the tags, missing without one; "
      + "the wait on blocking issues is not written, not even for an issue that waited on one")
  void urgencyAndNoBlockingWait() {
    corpus();
    // a waits on x, resolved a day later: no column of the wait is written for it
    final Corpus.Link waits = new Corpus.Link("x", "Blocker", "is blocked by");
    issues.set(0, new Corpus.Issue("a", "P-a", "p", T0, "r", "r", null, "Bug", "Major", null,
        List.of(), List.of("x"), List.of(waits), List.of("2.0")));
    issues.add(new Corpus.Issue("x", "P-x", "p", T0, "r", "r", null, "Bug", "Major", null,
        List.of(), List.of()));
    events.add(new Corpus.Event("x", T0 + 2 * D + 3 * 3_600_000L, "status", "d", "Open",
        "Resolved", (ext++) + "%%0"));
    tags.add(new Corpus.Tag("p", "release-2.0", T0 + 13 * D, null));
    final IssuesRetrospectiveTask.Report r = task().run(entries);
    assertEquals(List.of(), r.refused());
    final RowCache.Entry a = entry(r, "a");
    assertEquals(3.0, a.row().values().get(Retrospective.URGENCY), 1e-9);
    final RowCache.Entry b = entry(r, "b");
    assertTrue(b.row().absent().contains(Retrospective.URGENCY));
    for (final RowCache.Entry e : r.entries()) {
      assertFalse(e.row().values().containsKey("RETRO_blocker_wait"), e.unit().issueId());
      assertFalse(e.row().absent().contains("RETRO_blocker_wait"), e.unit().issueId());
    }
    assertFalse(Retrospective.COLUMNS.contains("RETRO_blocker_wait"));
  }

  @Test
  @DisplayName("coverage is counted on the inner fitting and inner validation only, on the issues "
      + "of at least one business day; the outer test is not counted")
  void coverageOnTheInnerSides() {
    corpus();
    final IssuesRetrospectiveTask.Report r = task().run(entries);
    assertEquals(List.of("inner fitting", "inner validation"),
        r.coverage().stream().map(IssuesRetrospectiveTask.Coverage::side).toList());
    final IssuesRetrospectiveTask.Coverage fit = r.coverage().get(0);
    assertEquals(2, fit.rows());
    assertEquals(1, fit.observed().get(Retrospective.LINES));
    assertEquals(2, fit.observed().get(Retrospective.COMMENTS));
    final IssuesRetrospectiveTask.Coverage validation = r.coverage().get(1);
    assertEquals(1, validation.rows());
    assertEquals(1, validation.observed().get(Retrospective.LINES));
    assertEquals(0, validation.observed().get(Retrospective.MCCC));
    assertEquals(Retrospective.COLUMNS, List.copyOf(fit.observed().keySet()));
  }

  @Test
  @DisplayName("an issue of the matrix the corpus does not hold as an In-Progress issue, or one whose "
      + "start or resolution differs there, refuses the run; so does a split not its rule's")
  void refuses() {
    corpus();
    final List<RowCache.Entry> unknown = new ArrayList<>(entries);
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {"TEST", ""});
    unknown.add(new RowCache.Entry(new IssueUnit("x", "P-x", "p", "r", "d",
        Instant.ofEpochMilli(T0), Instant.ofEpochMilli(T0 + 9 * D), "Fixed", false,
        Instant.ofEpochMilli(T0 + D)), new Row("x", Map.of(), Set.of()), parts));
    final IssuesRetrospectiveTask.Report r = task().run(unknown);
    assertEquals(1, r.refused().size(), r.refused().toString());
    assertTrue(r.refused().get(0).contains("x"), r.refused().toString());
    assertTrue(r.entries().isEmpty());
    assertTrue(asked.isEmpty(), "no method was read");

    final List<RowCache.Entry> moved = new ArrayList<>(entries);
    final RowCache.Entry first = moved.get(0);
    final IssueUnit u = first.unit();
    moved.set(0, new RowCache.Entry(new IssueUnit(u.issueId(), u.key(), u.project(),
        u.reporter(), u.executor(), u.createdAt(), u.resolvedAt(), u.reason(), u.moved(),
        u.startedAt().plusSeconds(60)), first.row(), first.parts()));
    assertEquals(1, task().run(moved).refused().size());

    final List<RowCache.Entry> unbounded = new ArrayList<>(entries);
    unbounded.set(0, new RowCache.Entry(first.unit(),
        new Row("a", Map.of(BusinessDays.ACTIVE, 1.0), Set.of()), first.parts()));
    final IssuesRetrospectiveTask.Report boundary = task().run(unbounded);
    assertTrue(boundary.refused().get(0).contains(BusinessDays.BOUNDARY),
        boundary.refused().toString());
    assertFalse(boundary.refused().get(0).contains(BusinessDays.ACTIVE),
        "only the missing column is named: " + boundary.refused());
    final List<RowCache.Entry> inactive = new ArrayList<>(entries);
    inactive.set(0, new RowCache.Entry(first.unit(),
        new Row("a", Map.of(BusinessDays.BOUNDARY, 4.0), Set.of()), first.parts()));
    final IssuesRetrospectiveTask.Report active = task().run(inactive);
    assertTrue(active.refused().get(0).contains(BusinessDays.ACTIVE), active.refused().toString());
    assertFalse(active.refused().get(0).contains(BusinessDays.BOUNDARY),
        "only the missing column is named: " + active.refused());
    assertTrue(asked.isEmpty(), "no method was read");

    // the hand split happens to be the rule's for these four issues; with a moved to the outer
    // test it is not
    assertEquals(List.of(), ProjectSplit.differences(entries));
    final List<RowCache.Entry> other = new ArrayList<>(entries);
    final Map<Population, String[]> test = new EnumMap<>(Population.class);
    test.put(Population.IN_PROGRESS, new String[] {"TEST", ""});
    other.set(0, new RowCache.Entry(first.unit(), first.row(), test));
    final IssuesRetrospectiveTask.Report split = new IssuesRetrospectiveTask(source(), changed(),
        Progress.silent()).run(other);
    assertEquals(1, split.refused().size(), split.refused().toString());
    assertTrue(split.refused().get(0).contains("not at the verdicts of the split's rule"),
        split.refused().toString());
    assertTrue(asked.isEmpty(), "no method was read");
  }
}
