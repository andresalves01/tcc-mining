package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Corpus;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import app.pipeline.port.SilentProgress;

/**
 * The in-progress extraction on a corpus held in memory (ticket 21): the population of at least
 * one active business day, the random split stratified by project drawn over it, and the gate.
 */
class IssuesInProgressExtractTest {

  private static final long D = 86_400_000L;
  private static final long H = 3_600_000L;
  /** A Wednesday at 00:00 UTC. */
  private static final long T0 = 1_000 * D;

  private final List<Corpus.Issue> issues = new ArrayList<>();
  private final List<Corpus.Event> events = new ArrayList<>();
  private int ext = 1;

  /** An issue of {@code project} created at T0, In Progress at {@code started}, Fixed after. */
  private void issue(final String id, final String project, final long started,
      final long resolved) {
    final String key = project.toUpperCase(java.util.Locale.ROOT) + "-" + id;
    issues.add(new Corpus.Issue(id, key, project, T0, "r", "r", null, "Bug", "Major", null,
        List.of(), List.of()));
    events.add(new Corpus.Event(id, started, "status", "d", "Open", "In Progress",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "status", "d", "In Progress", "Resolved",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "resolution", "d", null, "Fixed",
        (ext - 1) + "%%1"));
  }

  private StudySource source() {
    final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), List.of(),
        Map.of("p", Set.of("P"), "q", Set.of("Q"), "s", Set.of("S")), Map.of(), Map.of(),
        Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    return new StudySource() {
      @Override
      public Corpus load() {
        return corpus;
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        return Map.of();
      }


      @Override
      public String describe() {
        return "in memory";
      }
    };
  }

  @Test
  @DisplayName("the issues of 0 active business days are never drawn; the rest are split by the "
      + "rule, stratified by project, nothing crossing; the columns at the start count the "
      + "dropped issue as the project's state; the time-travel gate passes")
  void population() {
    // twelve issues of p and five of q, each from Thursday 10:00 to the next Tuesday 10:00
    for (int k = 0; k < 12; k++) {
      issue("p" + (char) ('a' + k), "p", T0 + D + 10 * H + k, T0 + 6 * D + 10 * H + k);
    }
    for (int k = 0; k < 5; k++) {
      issue("q" + (char) ('a' + k), "q", T0 + D + 10 * H + k, T0 + 6 * D + 10 * H + k);
    }
    // in p, resolved the same working date it started: 0 business days, never drawn
    issue("pzero", "p", T0 + D + 10 * H, T0 + D + 12 * H);
    // a project of one issue
    issue("sa", "s", T0 + D + 10 * H, T0 + 6 * D + 10 * H);

    final IssuesInProgressExtractTask.Report r =
        new IssuesInProgressExtractTask(source(), SilentProgress.INSTANCE).run();

    assertTrue(r.travelPassed(), r.travel().toString());
    final List<String> ids = r.entries().stream().map(e -> e.unit().issueId()).toList();
    assertEquals(18, ids.size());
    assertFalse(ids.contains("pzero"));
    assertEquals(r.entries().size(), r.team().size(), "the team file holds the same issues");
    // p: 8 training (6 fitting, 2 validation) and 4 test; q: 3.5 rounded up, 4 training (3 and
    // 1) and 1 test; s: its one issue training and fitting
    assertEquals(new IssuesInProgressExtractTask.Split(19, 1, 18, 3, 8 + 4 + 1, 4 + 1,
        6 + 3 + 1, 2 + 1), r.split());

    // the verdicts are the rule's, drawn over the kept issues alone
    assertEquals(List.of(), ProjectSplit.differences(r.entries()));
    final Map<String, String> projects = new HashMap<>();
    r.entries().forEach(e -> projects.put(e.unit().issueId(), e.unit().project()));
    final ProjectSplit.Parts parts = ProjectSplit.draw(projects);
    for (final RowCache.Entry e : r.entries()) {
      assertEquals(parts.outer().get(e.unit().issueId()).name(), e.outer(Population.IN_PROGRESS));
      assertFalse(e.outer(Population.IN_PROGRESS).equals("CROSSES"));
      assertFalse(e.inner(Population.IN_PROGRESS).equals("CROSSES"));
    }
    final RowCache.Entry alone = r.entries().stream()
        .filter(e -> e.unit().issueId().equals("sa")).findFirst().orElseThrow();
    assertEquals("TRAIN", alone.outer(Population.IN_PROGRESS));
    assertEquals("TRAIN", alone.inner(Population.IN_PROGRESS));

    // the state of the project at the start still counts every issue it had, the dropped one too
    final RowCache.Entry first = r.entries().stream()
        .filter(e -> e.unit().issueId().equals("pa")).findFirst().orElseThrow();
    assertEquals(13.0, first.row().values().get("K14_project_issues_90d"),
        "the thirteen issues of p created before pa's start");
  }

  @Test
  @DisplayName("the cache's split columns hold the verdicts of the sides drawn: CROSSING for an "
      + "issue of a crossing set, as the revised design marks it, every other its side's name")
  void verdictsWritten() {
    final StudySource source = new ExtractionFixture().source();
    final Corpus corpus = source.load();
    final app.issues.Universe universe = app.issues.Universe.of(corpus);
    final List<app.issues.Universe.Unit> units = universe.population(Population.IN_PROGRESS);
    final Map<String, String> projects = new HashMap<>();
    units.forEach(u -> projects.put(u.id(), u.project()));
    final ProjectSplit.Parts drawn = ProjectSplit.draw(projects);
    final List<String> fitting = new java.util.TreeSet<>(drawn.inner().keySet()).stream()
        .filter(id -> drawn.inner().get(id) == app.issues.Universe.Part.TRAIN).toList();
    final String validation = new java.util.TreeSet<>(drawn.inner().keySet()).stream()
        .filter(id -> drawn.inner().get(id) == app.issues.Universe.Part.TEST).findFirst()
        .orElseThrow();
    // case 3, case 2 and case 5
    final ProjectSplit.Parts crossing = new ProjectSplit.Parts(drawn.outer(), drawn.inner(),
        Set.of(fitting.get(0), validation), Set.of(fitting.get(0), fitting.get(1)));
    final IssuesInProgressExtractTask.Report r = IssuesInProgressExtractTask.columns(source,
        SilentProgress.INSTANCE, corpus, universe, units, new java.util.TreeSet<>(projects.keySet()),
        IssuesInProgressExtractTask.IN_PROGRESS_STARTER, Population.IN_PROGRESS, crossing,
        IssuesInProgressExtractTask.summary(units.size(), projects, crossing), Map.of(),
        List.of(), 1, 1);
    final Map<String, String> found = new java.util.TreeMap<>();
    for (final RowCache.Entry e : r.entries()) {
      final String id = e.unit().issueId();
      assertEquals(crossing.outerVerdict(id).name(), e.outer(Population.IN_PROGRESS), id);
      assertEquals(app.issues.Verdict.text(crossing.innerVerdict(id)),
          e.inner(Population.IN_PROGRESS), id);
      found.put(id, e.outer(Population.IN_PROGRESS) + "/" + e.inner(Population.IN_PROGRESS));
    }
    assertEquals("CROSSING/CROSSING", found.get(fitting.get(0)));
    assertEquals("TRAIN/CROSSING", found.get(fitting.get(1)));
    assertEquals("CROSSING/TEST", found.get(validation));
  }
}
