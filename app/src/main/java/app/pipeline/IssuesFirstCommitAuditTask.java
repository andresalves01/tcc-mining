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
import app.issues.Universe;
import app.issues.Universe.Part;
import app.issues.Universe.Population;
import app.issues.World;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
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
 * The first-commit population and its target on the training side, read only: the base, the commits that count, the selection ({@link FirstCommit}), the
 * split ({@link ProjectSplit}) over the issues of a target of at least one business day, the cap
 * learned on the inner fitting alone, and the target of the outer training. Of the outer test only
 * its size leaves this task.
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
   */
  public record Report(int bots, CodeTotals code, List<Step> funnel, DuplicateTotals duplicates, Sizes sizes,
      FirstCommit.Threshold threshold, Training training, List<Sensitivity> sensitivity,
      Map<Side, Map<Side, Integer>> overlap, List<IssueRow> rows) {}

  private final StudySource source;
  private final Progress progress;

  public IssuesFirstCommitAuditTask(final StudySource source, final Progress progress) {
    this.source = source;
    this.progress = progress;
  }

  /**
   * The issues under one limit, split, the cap learned and the training counted.
   *
   * @param training the target of each outer training issue, at the cap
   */
  record Drawn(Map<String, FirstCommit.Verdict> verdicts, ProjectSplit.Parts parts,
      Sizes sizes, FirstCommit.Threshold threshold, Map<String, FirstCommit.Count> training) {}

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
      drawn.put(limit, draw(issues, limit, boundary));
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
    return new Report(bots.size(),
        new CodeTotals(tally.counted, tally.originals, tally.replaced), funnel,
        new DuplicateTotals(dupIssues, sameAuthorDate, sameTitleLines, belowTwo), main.sizes(),
        main.threshold(), training(main.training()), sensitivity, overlap, rows);
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

  /** Per base issue, its linked native commits: human, not a merge, before its resolution. */
  static Map<String, List<Corpus.Commit>> natives(final Corpus corpus,
      final List<Universe.Unit> base, final Set<String> bots) {
    final Map<String, Universe.Unit> byId = new HashMap<>(base.size() * 2);
    base.forEach(u -> byId.put(u.id(), u));
    final Map<String, List<Corpus.Commit>> out = new HashMap<>();
    for (final Corpus.Commit c : corpus.commits()) {
      if (c.parents() > 1 || c.authoredAt() == null || !human(c.author(), corpus.people(), bots)) {
        continue;
      }
      final Corpus.Contribution k = corpus.contributions().get(c.id());
      if (k != null && k.bot()) {
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

  static Drawn draw(final Map<String, FirstCommit.Issue> issues, final double limit,
      final FirstCommit.Boundary boundary) {
    final Map<String, FirstCommit.Verdict> verdicts = new LinkedHashMap<>();
    final Map<String, String> projects = new HashMap<>();
    issues.forEach((id, i) -> {
      final FirstCommit.Verdict v = FirstCommit.select(i, limit, boundary);
      verdicts.put(id, v);
      if (v.eligible()) {
        projects.put(id, i.project());
      }
    });
    final ProjectSplit.Parts parts = ProjectSplit.draw(projects);
    final List<Integer> gaps = new ArrayList<>();
    parts.inner().forEach((id, part) -> {
      if (part == Part.TRAIN) {
        gaps.addAll(FirstCommit.gaps(issues.get(id), verdicts.get(id)));
      }
    });
    final FirstCommit.Threshold threshold = FirstCommit.threshold(gaps).requirePositive();
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
    return new Drawn(verdicts, parts, sizes(projects, parts), threshold, training);
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
