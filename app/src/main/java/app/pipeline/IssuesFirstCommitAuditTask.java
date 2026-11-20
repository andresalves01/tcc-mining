package app.pipeline;

import app.issues.BusinessDays;
import app.issues.Corpus;
import app.issues.DayBoundaries;
import app.issues.DurationEvidence;
import app.issues.FirstCommit;
import app.issues.FirstCommitFacts;
import app.issues.IssueUnit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.TemporalSplit;
import app.issues.Universe;
import app.issues.Universe.Part;
import app.issues.Universe.Population;
import app.issues.World;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The first-commit population and its target on the training side, read only: the base, the
 * commits that count, the selection ({@link FirstCommit}), the split drawn by the run's rule
 * ({@link SplitRule}) over the issues of a target of at least one business day, the cap learned on
 * the inner fitting alone, and the target of the outer training. Of the outer test only its size
 * leaves this task, and under the temporal rule also its projects and the dates of its first
 * commits ({@link Temporal}), never a target.
 *
 * <p><b>Commits that count.</b> Linked to the issue, authored before its first resolution, not a
 * merge, by a human: neither the person nor the ledger flags the account as a bot, and neither the
 * raw account nor its person is in the bot list of the In Progress study ({@link
 * DayBoundaries#bots}, over the whole universe). Pull-request originals recovered by {@link
 * StudySource#durationEvidence} count too, and replace the repository commit that integrated them.
 *
 * <p><b>Human activity and patches.</b> The comments, attachments and reviews that the same
 * evidence reads; an attachment whose file name matches {@link FirstCommit#PATCH} is a patch.
 */
public final class IssuesFirstCommitAuditTask {

  /** Where an issue lands in a split, with the name the audit prints. */
  public enum Side {
    FIT("ajuste interno"),
    VALIDATION("validação interna"),
    TEST("teste externo"),
    OUTSIDE("fora do In Progress");

    private final String label;

    Side(final String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** One line of the funnel: what is left after a rule, and how many it took out. */
  public record Step(String label, int remaining, int removed) {}

  /** The split in counts. */
  public record Sizes(int rows, int projects, int train, int test, int fit, int validation) {}

  /**
   * The duplicates over the issues that reach that rule.
   *
   * @param issues issues with at least one commit removed
   * @param belowTwo issues with two commits or more that count but fewer than two after them
   */
  public record DuplicateTotals(int issues, int sameAuthorDate, int sameTitleLines, int belowTwo) {}

  /** The commits that count over the base: all, recovered originals, natives they replaced. */
  public record CodeTotals(int counted, int originals, int replaced) {}

  /** The running counts behind {@link CodeTotals}. */
  static final class Tally {
    int counted;
    int originals;
    int replaced;
  }

  /** The target on the outer training, and its parts summed. */
  public record Training(FirstCommit.Summary target, long implementation, long tail, long capped,
      int withPatches) {}

  /** The population and target under one limit of the first commit's share. */
  public record Sensitivity(double limit, Sizes sizes, FirstCommit.Threshold threshold,
      FirstCommit.Summary target) {}

  /**
   * One issue of the per-issue audit: every issue that left, and every one of the outer training;
   * none of the outer test.
   *
   * @param side {@link Side#FIT} or {@link Side#VALIDATION} on the outer training; null for one
   *     that left
   * @param count its target; null unless on the outer training
   */
  public record IssueRow(String id, String key, String project, FirstCommit.Verdict verdict,
      int counted, int originals, Side side, FirstCommit.Count count) {}

  /**
   * @param overlap per side of this split's outer training, per side of the In Progress split,
   *     how many issues; the outer test is left out, its size is in {@code sizes}
   * @param waitFenceK the k of the wait fence the cap was learned with, {@link
   *     FirstCommit#NO_WAIT_CAP} for none
   */
  public record Report(int bots, CodeTotals code, List<Step> funnel, DuplicateTotals duplicates, Sizes sizes,
      FirstCommit.Threshold threshold, Training training, List<Sensitivity> sensitivity,
      Map<Side, Map<Side, Integer>> overlap, List<IssueRow> rows, SplitRule rule,
      Temporal temporal, double waitFenceK) {}

  /** How many projects have issues on each side. */
  public record Projects(int train, int test, int fit, int validation) {}

  /**
   * What the temporal rule adds to the report; none of it is a target of the outer test.
   *
   * @param fit the target of the inner fitting
   * @param validation the target of the inner validation
   * @param newProjects the projects with issues on the outer test alone, and how many there;
   *     under a rule that leaves crossing issues out, those with no issue that teaches
   * @param newProjectsTrainingSide under a rule that leaves crossing issues out, the projects
   *     with issues on the outer test and none on the outer training side, crossing issues
   *     included; null otherwise
   * @param newInnerProjects the projects with issues on the inner validation alone, and how many
   *     there; under a rule that leaves crossing issues out, those with no inner fitting issue
   *     that teaches, as the scoring reads them
   * @param newInnerProjectsTrainingSide under a rule that leaves crossing issues out, the projects
   *     with issues on the inner validation and none on the inner fitting side, crossing issues
   *     included; null otherwise
   * @param crossing the outer training issues whose first resolution comes after the outer cut
   * @param innerCrossing the inner fitting issues whose first resolution comes after the inner cut
   * @param random the cap the random rule learns on the same issues, for comparison
   */
  public record Temporal(TemporalSplit.Cuts cuts, Projects projects, FirstCommit.Summary fit,
      FirstCommit.Summary validation, Map<String, Integer> newProjects,
      Map<String, Integer> newProjectsTrainingSide, Map<String, Integer> newInnerProjects,
      Map<String, Integer> newInnerProjectsTrainingSide, int crossing, int innerCrossing,
      FirstCommit.Threshold random, Censoring censoring) {}

  /**
   * The censoring at the end of the data, in dates and counts alone.
   *
   * <p>The end of a project's data is its latest Jira record: an issue's creation, a changelog
   * event or a comment, all stamped by the tracker. Commit dates are written by the author's clock
   * and are left out; the latest one is given apart. The end of the data is the latest project
   * end.
   *
   * <p>The issues counted are those of the universe without a first resolution as Fixed whose
   * first native commit that counts (linked, human, not a merge, before the first resolution when
   * there is one; pull-request originals are not read for them) comes after the outer cut.
   *
   * @param unresolved of them, with no first resolution by the end of the data: censored
   * @param otherReason of them, first resolved with a reason other than Fixed
   * @param unitless of them, resolved (Fixed or not) but without a unit (no reporter, or resolved
   *     at creation), so outside the base
   * @param unresolvedDays the calendar days from each censored issue's first commit to the end
   * @param unresolvedProjectDays the same to the end of its project's data
   * @param testDays the calendar days from each outer test issue's first commit to the end
   * @param testProjectDays the same to the end of its project's data
   */
  public record Censoring(Instant end, Map<String, Instant> projectEnds, Instant lastCommit,
      int unresolved, int otherReason, int unitless, FirstCommit.Summary unresolvedDays,
      FirstCommit.Summary unresolvedProjectDays, FirstCommit.Summary testDays,
      FirstCommit.Summary testProjectDays) {}

  private final StudySource source;
  private final Progress progress;
  private final SplitRule rule;
  private final double waitK;

  /** The audit under the random rule. */
  public IssuesFirstCommitAuditTask(final StudySource source, final Progress progress) {
    this(source, progress, SplitRule.RANDOM);
  }

  /** The audit under {@code rule}, with the official wait fence. */
  public IssuesFirstCommitAuditTask(final StudySource source, final Progress progress,
      final SplitRule rule) {
    this(source, progress, rule, FirstCommit.DEFAULT_WAIT_K);
  }

  /** The audit under {@code rule}, the wait fence at Q3 + {@code waitK} IQR. */
  public IssuesFirstCommitAuditTask(final StudySource source, final Progress progress,
      final SplitRule rule, final double waitK) {
    this.source = source;
    this.progress = progress;
    this.rule = rule;
    this.waitK = waitK;
  }

  /**
   * The issues under one limit, split, the cap learned and the training counted.
   *
   * @param candidates the issues drawn, with the instant of their first kept commit
   * @param training the target of each outer training issue, at the cap
   */
  record Drawn(Map<String, FirstCommit.Verdict> verdicts, List<SplitRule.Candidate> candidates,
      ProjectSplit.Parts parts, Sizes sizes, FirstCommit.Threshold threshold,
      Map<String, FirstCommit.Count> training, SplitRule rule) {

    /**
     * How many outer training issues cross the outer cut and inner fitting issues the inner one.
     * Under a rule that leaves them out ({@link SplitRule#crossingOut}), the sizes of its crossing
     * sets, which are those issues by construction, and {@code issues} is not read; otherwise
     * counted from the first resolutions of {@code issues}, as the design of 02/11/2026 counts
     * them. Only for a temporal rule, which has cuts.
     */
    Crossing crossing(final Map<String, FirstCommit.Issue> issues) {
      if (rule.crossingOut()) {
        return new Crossing(parts.outerCrossing().size(), parts.innerCrossing().size());
      }
      final TemporalSplit.Cuts cuts = TemporalSplit.cuts(candidates);
      return new Crossing(IssuesFirstCommitAuditTask.crossing(issues, parts.outer(), cuts.outer()),
          IssuesFirstCommitAuditTask.crossing(issues, parts.inner(), cuts.inner()));
    }
  }

  /**
   * The issues crossing each cut, in counts.
   *
   * @param outer the outer training issues whose first resolution comes after the outer cut
   * @param inner the inner fitting issues whose first resolution comes after the inner cut
   */
  public record Crossing(int outer, int inner) {}

  /**
   * The base and what the rules read of each of its issues.
   *
   * @param units the issues resolved as Fixed at their first resolution, in universe order
   * @param issues per base issue id, its commits that count, patches and human activity
   */
  record Base(Corpus corpus, Universe universe, DayBoundaries days, Set<String> bots,
      List<Universe.Unit> units, Map<String, FirstCommit.Issue> issues, Tally tally) {

    /** The day boundary of the first commit's author, read at that commit. */
    FirstCommit.Boundary boundary() {
      return (author, project, at) -> days.at(author, project, at).hour();
    }
  }

  public Report run() {
    final Base b = base(6);
    final Corpus corpus = b.corpus();
    final Universe universe = b.universe();
    final List<Universe.Unit> base = b.units();
    final Map<String, FirstCommit.Issue> issues = b.issues();
    final Tally tally = b.tally();
    final Set<String> bots = b.bots();

    progress.phase(4, 6, "selection, split and cap");
    final FirstCommit.Boundary boundary = b.boundary();
    final Map<Double, Drawn> drawn = new LinkedHashMap<>();
    for (final double limit : FirstCommit.SENSITIVITY) {
      drawn.put(limit, draw(issues, limit, boundary, rule, waitK));
    }
    final Drawn main = drawn.get(FirstCommit.SHARE);

    progress.phase(5, 6, "the In Progress population and split, for the overlap");
    final Map<String, Side> inProgress = inProgressSides(corpus, universe);

    progress.phase(6, 6, "audit");
    final List<Step> funnel = funnel(base.size(), main.verdicts());
    final List<IssueRow> rows = new ArrayList<>();
    final Map<Side, Map<Side, Integer>> overlap = new EnumMap<>(Side.class);
    for (final Side side : List.of(Side.FIT, Side.VALIDATION)) {
      final Map<Side, Integer> row = new EnumMap<>(Side.class);
      for (final Side other : Side.values()) {
        row.put(other, 0);
      }
      overlap.put(side, row);
    }
    int dupIssues = 0;
    int sameAuthorDate = 0;
    int sameTitleLines = 0;
    int belowTwo = 0;
    for (final Universe.Unit u : base) {
      final FirstCommit.Issue i = issues.get(u.id());
      final FirstCommit.Verdict v = main.verdicts().get(u.id());
      final FirstCommit.Duplicates d = v.duplicates();
      if (d.sameAuthorDate() + d.sameTitleLines() > 0) {
        dupIssues++;
        sameAuthorDate += d.sameAuthorDate();
        sameTitleLines += d.sameTitleLines();
      }
      if (v.failed() == FirstCommit.Rule.FEWER_THAN_TWO && i.code().size() >= 2) {
        belowTwo++;
      }
      final int originals = (int) i.code().stream().filter(FirstCommit.Code::original).count();
      final Side side = side(main.parts(), u.id());
      if (side == Side.TEST) {
        continue; // sealed: only its size leaves this task
      }
      if (side != null) {
        overlap.get(side).merge(inProgress.getOrDefault(u.id(), Side.OUTSIDE), 1, Integer::sum);
      }
      rows.add(new IssueRow(u.id(), u.key(), u.project(), v, i.code().size(), originals,
          side, main.training().get(u.id())));
    }
    final List<Sensitivity> sensitivity = new ArrayList<>();
    drawn.forEach((limit, d) -> sensitivity.add(new Sensitivity(limit, d.sizes(), d.threshold(),
        FirstCommit.summary(d.training().values().stream().map(FirstCommit.Count::total)
            .toList()))));
    final Temporal temporal = rule.temporal()
        ? temporal(b, main,
            draw(issues, FirstCommit.SHARE, boundary, SplitRule.RANDOM, waitK).threshold())
        : null;
    return new Report(bots.size(),
        new CodeTotals(tally.counted, tally.originals, tally.replaced), funnel,
        new DuplicateTotals(dupIssues, sameAuthorDate, sameTitleLines, belowTwo), main.sizes(),
        main.threshold(), training(main.training()), sensitivity, overlap, rows, rule, temporal, waitK);
  }

  /**
   * Phases 1 to 3 of {@code phases}: the corpus, the base, and each base issue's commits that
   * count, patches and human activity. Shared with the extraction of the first-commit population
   * ({@link IssuesFirstCommitExtractTask}), so both read the same issues.
   */
  Base base(final int phases) {
    progress.phase(1, phases, "reading the corpus: %s", source.describe());
    final Corpus corpus = source.load();

    progress.phase(2, phases, "base: the issues resolved as Fixed at their first resolution");
    final Universe universe = Universe.of(corpus);
    final DayBoundaries days = DayBoundaries.of(corpus, universe);
    final Set<String> bots = days.bots();
    final List<Universe.Unit> base = new ArrayList<>();
    for (final String id : universe.universe()) {
      final Universe.Unit u = universe.unit(id);
      if (u != null && "Fixed".equals(u.reason())) {
        base.add(u);
      }
    }
    final Map<String, List<Corpus.Commit>> natives = natives(corpus, base, bots);

    progress.phase(3, phases, "pull-request originals and human activity of %,d issues",
        base.size());
    final Map<String, DurationEvidence> evidence = evidence(base, bots);
    final Set<String> nativeIds = new TreeSet<>();
    natives.values().forEach(l -> l.forEach(c -> nativeIds.add(c.id())));
    final Set<String> originalShas = new TreeSet<>();
    final Set<String> attachmentIds = new TreeSet<>();
    evidence.values().forEach(e -> {
      e.changes().stream().filter(DurationEvidence.Change::original)
          .forEach(c -> originalShas.add(c.sha()));
      e.activities().stream().filter(a -> "attachment".equals(a.kind()))
          .forEach(a -> attachmentIds.add(a.sourceId()));
    });
    final FirstCommitFacts facts =
        source.firstCommitFacts(nativeIds, originalShas, attachmentIds);
    final Tally tally = new Tally();
    final Map<String, FirstCommit.Issue> issues = new LinkedHashMap<>();
    for (final Universe.Unit u : base) {
      issues.put(u.id(), issue(u, natives.getOrDefault(u.id(), List.of()),
          evidence.get(u.id()), facts, corpus.people(), universe, tally));
    }
    return new Base(corpus, universe, days, bots, base, issues, tally);
  }

  // ------------------------------------------------------------------ commits that count

  /** Whether a raw account is a human's, by the person registry and the bot list. */
  static boolean human(final String raw, final Map<String, Corpus.Person> people,
      final Set<String> bots) {
    if (raw == null || bots.contains(raw) || bots.contains(World.person(raw, people))) {
      return false;
    }
    final Corpus.Person p = people.get(raw);
    return p == null || !p.bot();
  }

  /** Whether a native commit can count: dated, not a merge, by a human. */
  static boolean counts(final Corpus.Commit c, final Corpus corpus, final Set<String> bots) {
    if (c.parents() > 1 || c.authoredAt() == null || !human(c.author(), corpus.people(), bots)) {
      return false;
    }
    final Corpus.Contribution k = corpus.contributions().get(c.id());
    return k == null || !k.bot();
  }

  /** Per base issue, its linked native commits: human, not a merge, before its resolution. */
  static Map<String, List<Corpus.Commit>> natives(final Corpus corpus,
      final List<Universe.Unit> base, final Set<String> bots) {
    final Map<String, Universe.Unit> byId = new HashMap<>(base.size() * 2);
    base.forEach(u -> byId.put(u.id(), u));
    final Map<String, List<Corpus.Commit>> out = new HashMap<>();
    for (final Corpus.Commit c : corpus.commits()) {
      if (!counts(c, corpus, bots)) {
        continue;
      }
      for (final String issue : new LinkedHashSet<>(c.linked())) {
        final Universe.Unit u = byId.get(issue);
        if (u != null && c.authoredAt() < u.resolved()) {
          out.computeIfAbsent(issue, x -> new ArrayList<>()).add(c);
        }
      }
    }
    return out;
  }

  /** The pull-request evidence of the base, read one project at a time. */
  private Map<String, DurationEvidence> evidence(final List<Universe.Unit> base,
      final Set<String> bots) {
    final Map<String, List<RowCache.Entry>> byProject = new TreeMap<>();
    for (final Universe.Unit u : base) {
      final IssueUnit unit = new IssueUnit(u.id(), u.key(), u.project(), u.reporter(), null,
          Instant.ofEpochMilli(u.created()), Instant.ofEpochMilli(u.resolved()), u.reason(),
          u.moved(), Instant.ofEpochMilli(u.created()));
      byProject.computeIfAbsent(u.project(), p -> new ArrayList<>())
          .add(new RowCache.Entry(unit, new Row(u.id(), Map.of(), Set.of()), Map.of()));
    }
    final Map<String, DurationEvidence> out = new HashMap<>(base.size() * 2);
    int n = 0;
    for (final Map.Entry<String, List<RowCache.Entry>> e : byProject.entrySet()) {
      progress.item(++n, byProject.size(), e.getKey(), "%,d issues", e.getValue().size());
      out.putAll(source.durationEvidence(e.getValue(), bots));
    }
    if (!out.keySet().equals(new HashSet<>(base.stream().map(Universe.Unit::id).toList()))) {
      throw new IllegalStateException("the evidence does not cover exactly the base");
    }
    return out;
  }

  /** The other issues a commit is linked to, by key; an id outside the corpus stands as itself. */
  static Set<String> others(final List<String> linked, final Universe.Unit u,
      final Universe universe) {
    final Set<String> out = new TreeSet<>();
    for (final String x : linked) {
      if (x == null || x.equals(u.id())) {
        continue;
      }
      final Corpus.Issue other = universe.issue(x);
      final String key = other == null ? x : other.key();
      if (!u.key().equals(key)) {
        out.add(key);
      }
    }
    return out;
  }

  /**
   * One base issue as the rules read it. {@code tally} adds the commits that count, the originals
   * among them and the natives the originals replaced.
   */
  static FirstCommit.Issue issue(final Universe.Unit u, final List<Corpus.Commit> natives,
      final DurationEvidence evidence, final FirstCommitFacts facts,
      final Map<String, Corpus.Person> people, final Universe universe, final Tally tally) {
    final Set<String> replaced = new HashSet<>();
    evidence.integrations().stream().filter(DurationEvidence.Integration::replacedByOriginals)
        .forEach(i -> replaced.add(i.sourceId()));
    final List<FirstCommit.Code> code = new ArrayList<>();
    final Set<String> hashes = new HashSet<>();
    for (final Corpus.Commit c : natives) {
      if (replaced.contains(c.id())) {
        tally.replaced++;
        continue;
      }
      final FirstCommitFacts.Native f = facts.natives().get(c.id());
      hashes.add(c.hash());
      code.add(new FirstCommit.Code(c.hash(), World.person(c.author(), people), c.authoredAt(),
          f == null ? null : f.lines(), f == null ? null : f.title(),
          others(c.linked(), u, universe), false));
    }
    final Set<String> seen = new HashSet<>();
    for (final DurationEvidence.Change c : evidence.changes()) {
      if (!c.original() || c.authoredAt() >= u.resolved() || hashes.contains(c.sha())
          || !seen.add(c.sha())) {
        continue;
      }
      final FirstCommitFacts.Original f = facts.originals().get(c.sha());
      code.add(new FirstCommit.Code(c.sha(), f == null ? null : World.person(f.author(), people),
          c.authoredAt(), f == null ? null : f.lines(), f == null ? null : f.title(),
          f == null ? Set.of() : others(f.linked(), u, universe), true));
      tally.originals++;
    }
    tally.counted += code.size();
    final List<Long> patches = new ArrayList<>();
    final List<Long> activity = new ArrayList<>();
    for (final DurationEvidence.Activity a : evidence.activities()) {
      activity.add(a.at());
      final String name = facts.attachments().get(a.sourceId());
      if ("attachment".equals(a.kind()) && name != null && FirstCommit.PATCH.matcher(name).find()) {
        patches.add(a.at());
      }
    }
    return new FirstCommit.Issue(u.id(), u.key(), u.project(), u.created(), u.resolved(),
        List.copyOf(code), List.copyOf(patches), List.copyOf(activity));
  }

  // ------------------------------------------------------------------ population and target

  /**
   * The issues under one limit, the sides drawn by {@code rule} over those in the population, the
   * cap learned on the inner fitting's waits alone, and the outer training's targets at that cap.
   * The temporal rules order them by their first kept commit; the revised one also reads the
   * training side's first resolutions, and the waits of the inner fitting issues crossing the
   * inner cut are left out of the cap, while every outer training issue, crossing or not, is
   * counted at it.
   */
  static Drawn draw(final Map<String, FirstCommit.Issue> issues, final double limit,
      final FirstCommit.Boundary boundary, final SplitRule rule) {
    return draw(issues, limit, boundary, rule, FirstCommit.DEFAULT_WAIT_K);
  }

  /** As {@link #draw(Map, double, FirstCommit.Boundary, SplitRule)}, the fence at {@code waitK}. */
  static Drawn draw(final Map<String, FirstCommit.Issue> issues, final double limit,
      final FirstCommit.Boundary boundary, final SplitRule rule, final double waitK) {
    final Map<String, FirstCommit.Verdict> verdicts = new LinkedHashMap<>();
    final Map<String, String> projects = new HashMap<>();
    final List<SplitRule.Candidate> candidates = new ArrayList<>();
    issues.forEach((id, i) -> {
      final FirstCommit.Verdict v = FirstCommit.select(i, limit, boundary);
      verdicts.put(id, v);
      if (v.eligible()) {
        projects.put(id, i.project());
        candidates.add(new SplitRule.Candidate(id, i.project(),
            Instant.ofEpochMilli(v.kept().getFirst().at()), Instant.ofEpochMilli(i.resolved())));
      }
    });
    final ProjectSplit.Parts parts = rule.draw(candidates);
    final List<Integer> gaps = new ArrayList<>();
    parts.inner().forEach((id, part) -> {
      if (part == Part.TRAIN && !parts.innerCrossing().contains(id)) {
        gaps.addAll(FirstCommit.gaps(issues.get(id), verdicts.get(id)));
      }
    });
    final FirstCommit.Threshold threshold = FirstCommit.threshold(gaps, waitK).requirePositive();
    final Map<String, FirstCommit.Count> training = new TreeMap<>();
    parts.outer().forEach((id, part) -> {
      if (part == Part.TRAIN) {
        final FirstCommit.Count c =
            FirstCommit.count(issues.get(id), verdicts.get(id), threshold.cap());
        if (c.total() < 1) {
          throw new IllegalStateException(id + " was drawn with a target of " + c.total());
        }
        training.put(id, c);
      }
    });
    return new Drawn(verdicts, List.copyOf(candidates), parts, sizes(projects, parts), threshold,
        training, rule);
  }

  static Sizes sizes(final Map<String, String> projects, final ProjectSplit.Parts parts) {
    final int train = (int) parts.outer().values().stream().filter(p -> p == Part.TRAIN).count();
    final int fit = (int) parts.inner().values().stream().filter(p -> p == Part.TRAIN).count();
    return new Sizes(parts.outer().size(), new HashSet<>(projects.values()).size(), train,
        parts.outer().size() - train, fit, parts.inner().size() - fit);
  }

  /** The side an issue landed on; null when it was not drawn. */
  static Side side(final ProjectSplit.Parts parts, final String id) {
    final Part outer = parts.outer().get(id);
    if (outer == null) {
      return null;
    }
    if (outer == Part.TEST) {
      return Side.TEST;
    }
    return parts.inner().get(id) == Part.TRAIN ? Side.FIT : Side.VALIDATION;
  }

  static List<Step> funnel(final int base, final Map<String, FirstCommit.Verdict> verdicts) {
    final Map<FirstCommit.Rule, Integer> out = new EnumMap<>(FirstCommit.Rule.class);
    verdicts.values().forEach(v -> {
      if (!v.eligible()) {
        out.merge(v.failed(), 1, Integer::sum);
      }
    });
    final List<Step> steps = new ArrayList<>();
    int left = base;
    steps.add(new Step("Fixed na primeira resolução", left, 0));
    for (final FirstCommit.Rule r : FirstCommit.Rule.values()) {
      final int removed = out.getOrDefault(r, 0);
      left -= removed;
      steps.add(new Step(r.label(), left, removed));
    }
    return steps;
  }

  private static Training training(final Map<String, FirstCommit.Count> counts) {
    long implementation = 0;
    long tail = 0;
    long capped = 0;
    int withPatches = 0;
    for (final FirstCommit.Count c : counts.values()) {
      implementation += c.implementation();
      tail += c.tail();
      capped += c.capped();
      withPatches += c.patches() > 0 ? 1 : 0;
    }
    return new Training(FirstCommit.summary(counts.values().stream().map(FirstCommit.Count::total)
        .toList()), implementation, tail, capped, withPatches);
  }

  // ------------------------------------------------------------------ the temporal report

  /** The temporal report of {@code main}, drawn by the temporal rule; {@code random} beside it. */
  static Temporal temporal(final Base b, final Drawn main, final FirstCommit.Threshold random) {
    final TemporalSplit.Cuts cuts = TemporalSplit.cuts(main.candidates());
    final Crossing crossing = main.crossing(b.issues());
    final Map<String, String> projects = new HashMap<>(main.candidates().size() * 2);
    main.candidates().forEach(c -> projects.put(c.id(), c.project()));
    final Map<Side, Set<String>> sideProjects = new EnumMap<>(Side.class);
    final Map<Side, List<Integer>> targets = new EnumMap<>(Side.class);
    final List<SplitRule.Candidate> test = new ArrayList<>();
    for (final SplitRule.Candidate c : main.candidates()) {
      final Side side = side(main.parts(), c.id());
      sideProjects.computeIfAbsent(side, x -> new TreeSet<>()).add(c.project());
      if (side == Side.TEST) {
        test.add(c);
        continue;
      }
      targets.computeIfAbsent(side, x -> new ArrayList<>())
          .add(main.training().get(c.id()).total());
    }
    final Set<String> train = new TreeSet<>(sideProjects.getOrDefault(Side.FIT, Set.of()));
    train.addAll(sideProjects.getOrDefault(Side.VALIDATION, Set.of()));
    final Projects counts = new Projects(train.size(),
        sideProjects.getOrDefault(Side.TEST, Set.of()).size(),
        sideProjects.getOrDefault(Side.FIT, Set.of()).size(),
        sideProjects.getOrDefault(Side.VALIDATION, Set.of()).size());
    final Map<String, Integer> byTrainingSide = newProjects(projects, main.parts().outer());
    final boolean revised = main.rule().crossingOut();
    final Map<String, Part> taught = new HashMap<>(main.parts().outer());
    taught.keySet().removeAll(main.parts().outerCrossing());
    final Map<String, Integer> byFittingSide = newProjects(projects, main.parts().inner());
    // the inner fitting issues that teach: those crossing the inner cut left out, the inner
    // validation kept whole
    final Map<String, Part> innerTaught = new HashMap<>(main.parts().inner());
    innerTaught.keySet().removeAll(main.parts().innerCrossing());
    return new Temporal(cuts, counts, summaryOrNull(targets.get(Side.FIT)),
        summaryOrNull(targets.get(Side.VALIDATION)),
        revised ? newProjects(projects, taught) : byTrainingSide,
        revised ? byTrainingSide : null,
        revised ? newProjects(projects, innerTaught) : byFittingSide,
        revised ? byFittingSide : null,
        crossing.outer(), crossing.inner(), random,
        censoring(b.corpus(), b.universe(), b.bots(), cuts.outer(), test));
  }

  /**
   * The issues on the training part of {@code sides} whose first resolution comes after {@code
   * cut}: with the outer sides and cut, the outer training issues that cross the cut; with the
   * inner ones, the inner fitting's.
   */
  static int crossing(final Map<String, FirstCommit.Issue> issues, final Map<String, Part> sides,
      final Instant cut) {
    int n = 0;
    for (final Map.Entry<String, Part> e : sides.entrySet()) {
      if (e.getValue() == Part.TRAIN && issues.get(e.getKey()).resolved() > cut.toEpochMilli()) {
        n++;
      }
    }
    return n;
  }

  /**
   * The projects with issues on the {@link Part#TEST} part of {@code sides} and none on its
   * training part, with how many issues each has there, in order of name; {@code projects} maps
   * each issue id to its project.
   */
  static Map<String, Integer> newProjects(final Map<String, String> projects,
      final Map<String, Part> sides) {
    return perProject(TemporalSplit.newProjects(projects, sides), projects, sides);
  }

  /**
   * Of the projects {@code chosen}, how many issues each has on the {@link Part#TEST} part of
   * {@code sides} (the outer test, or the inner validation), in order of name.
   */
  private static Map<String, Integer> perProject(final Set<String> chosen,
      final Map<String, String> projects, final Map<String, Part> sides) {
    final Map<String, Integer> out = new TreeMap<>();
    sides.forEach((id, part) -> {
      if (part == Part.TEST && chosen.contains(projects.get(id))) {
        out.merge(projects.get(id), 1, Integer::sum);
      }
    });
    return out;
  }

  private static FirstCommit.Summary summaryOrNull(final List<Integer> values) {
    return values == null || values.isEmpty() ? null : FirstCommit.summary(values);
  }

  /** The censoring at the end of the data ({@link Censoring}), over the universe. */
  static Censoring censoring(final Corpus corpus, final Universe universe, final Set<String> bots,
      final Instant cut, final Collection<SplitRule.Candidate> test) {
    final Map<String, String> projectOf = new HashMap<>();
    final Map<String, Long> ends = new TreeMap<>();
    // per issue outside the base, the instant its commits must precede: its first resolution
    final Map<String, Long> bounds = new HashMap<>();
    int unresolvedIssues = 0;
    for (final String id : universe.universe()) {
      final Corpus.Issue i = universe.issue(id);
      projectOf.put(id, i.project());
      ends.merge(i.project(), i.created(), Math::max);
      final List<Corpus.Event> events = universe.events(id);
      for (final Corpus.Event e : events) {
        if (e.at() != null) {
          ends.merge(i.project(), e.at(), Math::max);
        }
      }
      final Universe.Unit u = universe.unit(id);
      if (u == null || !"Fixed".equals(u.reason())) {
        final Long resolved = Universe.firstResolution(events);
        bounds.put(id, resolved == null ? Long.MAX_VALUE : resolved);
      }
    }
    for (final Corpus.Comment c : corpus.comments()) {
      final String p = projectOf.get(c.issue());
      if (p != null && c.at() != null) {
        ends.merge(p, c.at(), Math::max);
      }
    }
    long lastCommit = Long.MIN_VALUE;
    final Map<String, Long> first = new HashMap<>();
    for (final Corpus.Commit c : corpus.commits()) {
      if (c.authoredAt() != null) {
        lastCommit = Math.max(lastCommit, c.authoredAt());
      }
      if (!counts(c, corpus, bots)) {
        continue;
      }
      for (final String issue : c.linked()) {
        final Long bound = bounds.get(issue);
        if (bound != null && c.authoredAt() < bound) {
          first.merge(issue, c.authoredAt(), Math::min);
        }
      }
    }
    final long end = ends.values().stream().mapToLong(Long::longValue).max().orElseThrow();
    int otherReason = 0;
    int unitless = 0;
    final List<Integer> toEnd = new ArrayList<>();
    final List<Integer> toProjectEnd = new ArrayList<>();
    for (final Map.Entry<String, Long> e : new TreeMap<>(first).entrySet()) {
      if (e.getValue() <= cut.toEpochMilli()) {
        continue;
      }
      if (bounds.get(e.getKey()) == Long.MAX_VALUE) {
        unresolvedIssues++;
        toEnd.add(days(e.getValue(), end));
        toProjectEnd.add(days(e.getValue(), ends.get(projectOf.get(e.getKey()))));
      } else if (universe.unit(e.getKey()) != null) {
        otherReason++;
      } else {
        unitless++;
      }
    }
    final List<Integer> testToEnd = new ArrayList<>();
    final List<Integer> testToProjectEnd = new ArrayList<>();
    for (final SplitRule.Candidate c : test) {
      testToEnd.add(days(c.firstCommit().toEpochMilli(), end));
      testToProjectEnd.add(days(c.firstCommit().toEpochMilli(), ends.get(c.project())));
    }
    final Map<String, Instant> projectEnds = new TreeMap<>();
    ends.forEach((p, t) -> projectEnds.put(p, Instant.ofEpochMilli(t)));
    return new Censoring(Instant.ofEpochMilli(end), projectEnds,
        lastCommit == Long.MIN_VALUE ? null : Instant.ofEpochMilli(lastCommit), unresolvedIssues,
        otherReason, unitless, summaryOrNull(toEnd), summaryOrNull(toProjectEnd),
        summaryOrNull(testToEnd), summaryOrNull(testToProjectEnd));
  }

  /** Whole calendar days from {@code from} to {@code to}, rounded down. */
  private static int days(final long from, final long to) {
    return (int) Math.floorDiv(to - from, World.DAY);
  }

  /** The side of each issue of the In Progress population, drawn as its extraction draws it. */
  static Map<String, Side> inProgressSides(final Corpus corpus, final Universe universe) {
    final List<Universe.Unit> started = universe.population(Population.IN_PROGRESS);
    final Set<String> ids = new TreeSet<>();
    started.forEach(u -> ids.add(u.id()));
    final BusinessDays.Counted counted = BusinessDays.of(corpus, universe, ids);
    final Map<String, String> projects = new HashMap<>();
    for (final Universe.Unit u : started) {
      if (counted.active().get(u.id()) >= 1) {
        projects.put(u.id(), u.project());
      }
    }
    final ProjectSplit.Parts parts = ProjectSplit.draw(projects);
    final Map<String, Side> out = new HashMap<>(projects.size() * 2);
    projects.keySet().forEach(id -> out.put(id, side(parts, id)));
    return out;
  }
}
