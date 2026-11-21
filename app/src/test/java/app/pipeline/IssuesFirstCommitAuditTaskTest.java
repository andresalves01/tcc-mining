package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Corpus;
import app.issues.DurationEvidence;
import app.issues.FirstCommit;
import app.issues.FirstCommit.Rule;
import app.issues.FirstCommitFacts;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.Universe.Part;
import app.issues.World;
import app.issues.port.StudySource;
import app.pipeline.IssuesFirstCommitAuditTask.IssueRow;
import app.pipeline.IssuesFirstCommitAuditTask.Report;
import app.pipeline.IssuesFirstCommitAuditTask.Side;
import app.pipeline.port.SilentProgress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The audit on a corpus built by hand: one issue per rule, and the sealed side kept sealed. */
class IssuesFirstCommitAuditTaskTest {

  private static final long H = World.HOUR;

  /** Noon UTC of a day of 2024: January 1 is a Monday. */
  private static long day(final int d) {
    return Instant.parse("2024-01-01T12:00:00Z").toEpochMilli() + (d - 1) * World.DAY;
  }

  private final List<Corpus.Issue> issues = new ArrayList<>();
  private final List<Corpus.Event> events = new ArrayList<>();
  private final List<Corpus.Comment> comments = new ArrayList<>();
  private final List<Corpus.Commit> commits = new ArrayList<>();
  private final Map<String, Corpus.Person> people = new HashMap<>();
  private final Map<String, FirstCommitFacts.Native> natives = new HashMap<>();
  private final Map<String, FirstCommitFacts.Original> originals = new HashMap<>();
  private final Map<String, DurationEvidence> evidence = new HashMap<>();
  private final List<Set<String>> evidenceCalls = new ArrayList<>();
  private final Map<String, Long> resolutions = new HashMap<>();
  private int ext = 1;

  private static String id(final int n) {
    return String.format("%024x", n);
  }

  private void issue(final int n, final long created, final long resolved, final String reason) {
    issue("p", "P-" + n, n, created, resolved, reason);
  }

  private void issue(final String project, final String key, final int n, final long created,
      final long resolved, final String reason) {
    final String id = id(n);
    resolutions.put(id, resolved);
    issues.add(new Corpus.Issue(id, key, project, created, "rep", "rep", null, "Bug", "Major",
        null, List.of(), List.of()));
    events.add(new Corpus.Event(id, resolved, "status", "x", "Open", "Resolved", (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "resolution", "x", null, reason, (ext - 1) + "%%1"));
  }

  private void commit(final String sha, final String author, final long at, final Long lines,
      final int... linked) {
    final List<String> ids = new ArrayList<>();
    for (final int n : linked) {
      ids.add(id(n));
    }
    commits.add(new Corpus.Commit("c" + sha, "p", sha, author, at, at, 1, List.copyOf(ids)));
    natives.put("c" + sha, new FirstCommitFacts.Native(lines, "title " + sha));
  }

  /**
   * A Fixed issue in the population, {@code n} weeks after the first: two commits, the second
   * {@code wait} days later, and the resolution four weeks after the creation, so only the issues
   * started just before a temporal cut cross it.
   */
  private void eligible(final int n, final int wait) {
    final int week = 7 * n;
    issue(n, day(1 + week) - H, day(30 + week), "Fixed");
    commit("a" + n, "dev", day(2 + week), 10L, n);
    commit("b" + n, "dev", day(2 + wait + week), 30L, n);
    // moved to In Progress an hour before its first commit
    events.add(new Corpus.Event(id(n), day(2 + week) - H, "status", "dev", "Open",
        "In Progress", (ext++) + "%%0"));
  }

  @BeforeEach
  void corpus() {
    for (int n = 1; n <= 30; n++) {
      eligible(n, 1 + n % 7);
    }
    issue(101, day(1), day(9), "Fixed"); // no commit at all
    issue(102, day(3), day(9), "Fixed"); // created after its first commit
    commit("s102", "dev", day(2), 10L, 102);
    commit("t102", "dev", day(4), 30L, 102);
    issue(103, day(1), day(9), "Fixed"); // two copies of one commit
    commit("s103", "dev", day(2), 10L, 103);
    commit("t103", "dev", day(2), 10L, 103);
    issue(104, day(1), day(9), "Fixed"); // a commit of unknown size
    commit("s104", "dev", day(2), 10L, 104);
    commit("t104", "dev", day(3), null, 104);
    issue(105, day(1), day(9), "Fixed"); // the first commit carries most of the lines
    commit("s105", "dev", day(2), 60L, 105);
    commit("t105", "dev", day(3), 40L, 105);
    issue(106, day(1), day(9), "Fixed"); // code on one date
    commit("s106", "dev", day(2), 10L, 106);
    commit("t106", "dev", day(2) + 2 * H, 30L, 106);
    issue(107, day(1), day(9), "Fixed"); // a commit shared with a Won't Fix issue
    issue(201, day(1), day(9), "Won't Fix");
    commit("s107", "dev", day(2), 10L, 107);
    commit("t107", "dev", day(3), 30L, 107, 201);
    issue(108, day(1), day(9), "Fixed"); // a weekend of code, and no activity after it
    commit("s108", "dev", day(6), 10L, 108);
    commit("t108", "dev", day(7), 30L, 108);
    // only a bot's code: by the registry, and by the template rule over the universe
    issue(109, day(1), day(9), "Fixed");
    people.put("robot", new Corpus.Person("robot", true));
    commit("s109", "robot", day(2), 10L, 109);
    for (int k = 0; k < 20; k++) {
      comments.add(new Corpus.Comment(id(201), "jenkins", day(3) + k, 5, true));
    }
    commit("t109", "jenkins", day(3), 30L, 109);
    // originals recovered from a pull request replace the commit that merged them; alone in
    // its project, the issue always lands in the inner fitting
    issue("q", "Q-110", 110, day(1) - H, day(30), "Fixed");
    commit("m110", "dev", day(9), 500L, 110);
    originals.put("o1", new FirstCommitFacts.Original("dev", "start", 20L, List.of(id(110))));
    originals.put("o2", new FirstCommitFacts.Original("dev", "finish", 45L, List.of()));
    evidence.put(id(110), new DurationEvidence(
        List.of(new DurationEvidence.Change("o1", day(2), null, "commit.author_date", "x", true),
            new DurationEvidence.Change("o2", day(5), null, "commit.author_date", "y", true)),
        List.of(), List.of(), "recorded_originals_recovered", List.of(),
        List.of(new DurationEvidence.Integration("m110", day(9), day(9), "cm110", true))));
  }

  private Report run() {
    return run(SplitRule.REVISED, FirstCommit.DEFAULT_WAIT_K);
  }

  private Report run(final SplitRule rule, final double waitK) {
    final Corpus corpus = new Corpus(issues, events, comments, List.of(), commits,
        Map.of("p", Set.of("P"), "q", Set.of("Q")), Map.of(), people, Map.of(), Map.of(), Map.of(), Map.of(),
        List.of());
    final StudySource source = new StudySource() {
      @Override
      public Corpus load() {
        return corpus;
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        throw new AssertionError("the audit reads no text");
      }

      @Override
      public Map<String, DurationEvidence> durationEvidence(final List<RowCache.Entry> rows,
          final Set<String> bots) {
        evidenceCalls.add(rows.stream().map(r -> r.unit().issueId()).collect(Collectors.toSet()));
        assertTrue(bots.contains("jenkins"), "the In Progress bot list");
        final Map<String, DurationEvidence> out = new HashMap<>();
        rows.forEach(r -> out.put(r.unit().issueId(), evidence.getOrDefault(r.unit().issueId(),
            new DurationEvidence(List.of(), List.of(), List.of(), "native", List.of(),
                List.of()))));
        return out;
      }

      @Override
      public FirstCommitFacts firstCommitFacts(final Collection<String> commitIds,
          final Collection<String> shas, final Collection<String> attachments) {
        final Map<String, FirstCommitFacts.Native> n = new HashMap<>();
        commitIds.forEach(c -> n.put(c, natives.get(c)));
        final Map<String, FirstCommitFacts.Original> o = new HashMap<>();
        shas.forEach(s -> o.put(s, originals.get(s)));
        return new FirstCommitFacts(n, o, Map.of());
      }

      @Override
      public String describe() {
        return "in memory";
      }
    };
    return new IssuesFirstCommitAuditTask(source, SilentProgress.INSTANCE, rule, waitK).run();
  }

  private static Map<String, IssueRow> byKey(final Report r) {
    return r.rows().stream().collect(Collectors.toMap(IssueRow::key, Function.identity()));
  }

  @Test
  @DisplayName("the funnel takes each issue out at the first rule it fails")
  void funnel() {
    final Report r = run();
    final Map<String, Integer> removed = new HashMap<>();
    r.funnel().forEach(s -> removed.put(s.label(), s.removed()));
    assertEquals(40, r.funnel().getFirst().remaining(), "the Won't Fix issue is not in the base");
    assertEquals(2, removed.get(Rule.NO_CODE.label()), "no commit, and only a bot's");
    for (final Rule rule : List.of(Rule.CREATED_AFTER_FIRST_COMMIT, Rule.FEWER_THAN_TWO,
        Rule.UNKNOWN_SIZE, Rule.FIRST_TOO_LARGE, Rule.ONE_DAY, Rule.SHARED, Rule.ZERO)) {
      assertEquals(1, removed.get(rule.label()), rule.name());
    }
    assertEquals(31, r.funnel().getLast().remaining());
    assertEquals(31, r.sizes().rows());
    assertEquals(1, r.duplicates().sameAuthorDate());
    assertEquals(1, r.duplicates().belowTwo());
    final Map<String, IssueRow> rows = byKey(r);
    assertEquals(Rule.CREATED_AFTER_FIRST_COMMIT, rows.get("P-102").verdict().failed());
    assertEquals(Rule.SHARED, rows.get("P-107").verdict().failed());
    assertEquals(Set.of("P-201"), rows.get("P-107").verdict().kept().getLast().others());
  }

  @Test
  @DisplayName("the evidence is read for the base alone, one project at a time")
  void evidenceScope() {
    run();
    assertEquals(List.of(39, 1), evidenceCalls.stream().map(Set::size).toList());
    assertFalse(evidenceCalls.getFirst().contains(id(201)));
    assertEquals(Set.of(id(110)), evidenceCalls.getLast());
  }

  @Test
  @DisplayName("recovered originals replace the commit that integrated them")
  void originals() {
    final Report r = run();
    final IssueRow row = byKey(r).get("Q-110");
    assertEquals(IssuesFirstCommitAuditTask.Side.FIT, row.side());
    final FirstCommit.Verdict v = row.verdict();
    assertTrue(v.eligible());
    assertEquals(List.of("o1", "o2"), v.kept().stream().map(FirstCommit.Code::sha).toList());
    assertEquals(20 / 65.0, v.firstShare(), 1e-12);
    assertEquals(2, row.originals());
    assertEquals(3, row.count().total(), "Tuesday to Friday");
    assertEquals(new IssuesFirstCommitAuditTask.CodeTotals(76, 2, 1), r.code());
  }

  @Test
  @DisplayName("of the outer test only the size leaves; every training issue carries its target")
  void sealedTest() {
    final Report r = run();
    final long training = r.rows().stream().filter(x -> x.side() != null).count();
    assertEquals(r.sizes().train(), training);
    assertTrue(r.sizes().test() > 0);
    assertEquals(r.sizes().rows(), r.sizes().train() + r.sizes().test());
    for (final IssueRow row : r.rows()) {
      if (row.side() == null) {
        assertNull(row.count());
      } else {
        assertTrue(row.count().total() >= 1);
      }
    }
    assertEquals(r.sizes().train(), r.training().target().n());
  }

  @Test
  @DisplayName("the cap is learned on the inner fitting's gaps alone, without those of the issues "
      + "crossing the inner cut")
  void capFromInnerFitting() {
    final Report r = run();
    final long cut = r.temporal().cuts().inner().toEpochMilli();
    final List<Integer> gaps = new ArrayList<>();
    int fit = 0;
    int crossing = 0;
    for (final IssueRow row : r.rows()) {
      if (row.side() == IssuesFirstCommitAuditTask.Side.FIT) {
        fit++;
        if (resolutions.get(row.id()) > cut) {
          crossing++;
        } else {
          gaps.add(row.count().implementation() + row.count().capped());
        }
      }
    }
    assertEquals(r.sizes().fit(), fit);
    assertTrue(crossing > 0, "the fixture has fitting issues crossing the inner cut");
    assertEquals(r.temporal().innerCrossing(), crossing);
    assertEquals(FirstCommit.threshold(gaps), r.threshold(),
        "each fitting issue here has one gap, its uncapped implementation days");
  }

  @Test
  @DisplayName("the sensitivity redraws the population at 30%, 50% and 70%")
  void sensitivity() {
    final Report r = run();
    assertEquals(List.of(0.3, 0.5, 0.7),
        r.sensitivity().stream().map(IssuesFirstCommitAuditTask.Sensitivity::limit).toList());
    final var at = r.sensitivity().stream().collect(Collectors.toMap(
        IssuesFirstCommitAuditTask.Sensitivity::limit, s -> s.sizes().rows()));
    assertEquals(31, at.get(0.5));
    assertEquals(30, at.get(0.3), "Q-110's first original carries 31% of its lines");
    assertEquals(32, at.get(0.7), "P-105's first commit carries 60%");
    assertEquals(r.sizes(), r.sensitivity().get(1).sizes());
  }

  // ------------------------------------------------------------------ the draw, by rule

  private static final FirstCommit.Boundary MIDNIGHT = (author, project, at) -> 0;

  /**
   * An issue of the population drawn from by hand: its first commit on the Monday of week
   * {@code week}, the second {@code gap} business days later, resolved an hour after it.
   */
  private static FirstCommit.Issue started(final int n, final String project, final int week,
      final int gap) {
    final long first = day(1 + 7 * week);
    final long second = first + gap * World.DAY;
    return new FirstCommit.Issue(id(n), project.toUpperCase() + "-" + n, project, first - H,
        second + H, List.of(
            new FirstCommit.Code("a" + n, "dev", first, 10L, "start " + n, Set.of(), false),
            new FirstCommit.Code("b" + n, "dev", second, 30L, "finish " + n, Set.of(), false)),
        List.of(), List.of());
  }

  // ------------------------------------------------------------------ the wait fence's k

  /** Ten weekly issues: the inner fitting's waits are {@code fit}, the validation's are 4 days. */
  private static Map<String, FirstCommit.Issue> weekly(final int... fit) {
    final Map<String, FirstCommit.Issue> from = new LinkedHashMap<>();
    for (int week = 0; week < 10; week++) {
      final FirstCommit.Issue i = started(week + 1, "p", week, week < 5 ? fit[week] : 4);
      from.put(i.id(), i);
    }
    return from;
  }

  @Test
  @DisplayName("the default k is 1.5: draw without a k is the draw at 1.5")
  void drawDefaultsToTheOfficialFence() {
    final Map<String, FirstCommit.Issue> from = weekly(1, 1, 1, 2, 4);
    final IssuesFirstCommitAuditTask.Drawn usual = IssuesFirstCommitAuditTask.draw(from,
        FirstCommit.SHARE, MIDNIGHT, SplitRule.REVISED);
    final IssuesFirstCommitAuditTask.Drawn explicit = IssuesFirstCommitAuditTask.draw(from,
        FirstCommit.SHARE, MIDNIGHT, SplitRule.REVISED, 1.5);
    assertEquals(usual.threshold(), explicit.threshold());
    assertEquals(usual.training(), explicit.training());
  }

  @Test
  @DisplayName("k = 3 and no cap raise the cap and change the targets of the outer training")
  void largerFenceChangesCapAndTarget() {
    // the long wait on week 2, so the issue resolves before the inner cut and its wait is learned
    final Map<String, FirstCommit.Issue> from = weekly(1, 1, 10, 2, 1);
    final IssuesFirstCommitAuditTask.Drawn usual = IssuesFirstCommitAuditTask.draw(from,
        FirstCommit.SHARE, MIDNIGHT, SplitRule.REVISED, 1.5);
    final IssuesFirstCommitAuditTask.Drawn none = IssuesFirstCommitAuditTask.draw(from,
        FirstCommit.SHARE, MIDNIGHT, SplitRule.REVISED, FirstCommit.NO_WAIT_CAP);
    assertEquals(2, usual.threshold().cap());
    assertEquals(Integer.MAX_VALUE, none.threshold().cap(), "no cap at all");
    // the validation's waits (4 business days) are cut at 2, kept whole with no cap
    assertEquals(2, usual.training().get(id(6)).total());
    assertEquals(4, none.training().get(id(6)).total());
    assertEquals(0, none.training().values().stream().mapToInt(FirstCommit.Count::capped).sum());
    assertEquals(usual.parts(), none.parts(), "the sides do not depend on the k");

    // issue 5, at the inner cut, crosses it: its wait is not learned
    final Map<String, FirstCommit.Issue> mild = weekly(1, 1, 1, 4, 1);
    assertEquals(1, IssuesFirstCommitAuditTask.draw(mild, FirstCommit.SHARE, MIDNIGHT,
        SplitRule.REVISED, 1.5).threshold().cap());
    assertEquals(4, IssuesFirstCommitAuditTask.draw(mild, FirstCommit.SHARE, MIDNIGHT,
        SplitRule.REVISED, 3.0).threshold().cap(), "the wait of 4 is between the fences");
  }

  @Test
  @DisplayName("the audit's report carries the k, and its sensitivity uses it")
  void auditReportsItsFence() {
    assertEquals(FirstCommit.DEFAULT_WAIT_K, run().waitFenceK());
    final Report none = run(SplitRule.REVISED, FirstCommit.NO_WAIT_CAP);
    assertEquals(FirstCommit.NO_WAIT_CAP, none.waitFenceK());
    for (final var s : none.sensitivity()) {
      assertEquals(Double.POSITIVE_INFINITY, s.threshold().fence(), "limit " + s.limit());
    }
  }

  @Test
  @DisplayName("the revised rule learns the cap without the waits of the inner fitting issues "
      + "crossing the inner cut, draws the temporal sides with their crossing sets, and still "
      + "counts a target for every outer training issue, crossing ones included")
  void revisedCap() {
    final Map<String, FirstCommit.Issue> from = new LinkedHashMap<>();
    // inner fitting weeks 0..4 (inner cut week 4), inner validation 5 and 6 (outer cut week 6);
    // each issue resolved an hour after its second commit, so the issue at each cut crosses it
    final int[] gaps = {1, 1, 2, 2, 3, 1, 1, 1, 1, 1};
    for (int week = 0; week < 10; week++) {
      final FirstCommit.Issue i = started(week + 1, "p", week, gaps[week]);
      from.put(i.id(), i);
    }
    final IssuesFirstCommitAuditTask.Drawn d = IssuesFirstCommitAuditTask.draw(from,
        FirstCommit.SHARE, MIDNIGHT, SplitRule.REVISED);
    assertEquals(Set.of(id(5)), d.parts().innerCrossing(), "week 4, at the inner cut");
    assertEquals(Set.of(id(7)), d.parts().outerCrossing(), "week 6, at the outer cut");
    // the four waits of the inner fitting issues that teach; week 4's three-day wait is left out
    assertEquals(FirstCommit.threshold(List.of(1, 1, 2, 2)), d.threshold());
    assertEquals(2, d.threshold().cap());
    assertEquals(7, d.training().size(), "the outer training keeps every issue");
    assertEquals(2, d.training().get(id(5)).total(), "week 4's wait capped at two days");
    assertEquals(new IssuesFirstCommitAuditTask.Crossing(1, 1), d.crossing(),
        "counted from the crossing sets");
  }

  @Test
  @DisplayName("with no cap, a wait longer than every wait the cap is learned on is not cut: the "
      + "inner fitting issue crossing the inner cut, outside that set, keeps its whole wait in the "
      + "outer training, which k = 1.5 and k = 3 cut")
  void noCapCutsNoWaitOutsideTheLearningSet() {
    final Map<String, FirstCommit.Issue> from = new LinkedHashMap<>();
    // as in revisedCap: week 4 crosses the inner cut, and its wait, a week from Monday to Monday
    // (five business days), is left out of the waits the cap is learned on
    final int[] gaps = {1, 1, 2, 2, 7, 1, 1, 1, 1, 1};
    for (int week = 0; week < 10; week++) {
      final FirstCommit.Issue i = started(week + 1, "p", week, gaps[week]);
      from.put(i.id(), i);
    }
    final IssuesFirstCommitAuditTask.Drawn none = IssuesFirstCommitAuditTask.draw(from,
        FirstCommit.SHARE, MIDNIGHT, SplitRule.REVISED, FirstCommit.NO_WAIT_CAP);
    assertEquals(Set.of(id(5)), none.parts().innerCrossing());
    assertTrue(none.threshold().uncapped());
    assertEquals(5, none.training().get(id(5)).total(), "the whole wait of five days");
    assertEquals(0, none.training().values().stream().mapToInt(FirstCommit.Count::capped).sum());
    for (final double k : new double[] {1.5, 3.0}) {
      final IssuesFirstCommitAuditTask.Drawn finite = IssuesFirstCommitAuditTask.draw(from,
          FirstCommit.SHARE, MIDNIGHT, SplitRule.REVISED, k);
      assertEquals(2, finite.threshold().cap(), "learned on the waits 1, 1, 2, 2; k " + k);
      assertEquals(2, finite.training().get(id(5)).total(), "k " + k);
      assertEquals(3, finite.training().get(id(5)).capped(), "k " + k);
    }
  }
}
