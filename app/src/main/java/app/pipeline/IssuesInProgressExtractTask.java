package app.pipeline;

import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.BusinessDays;
import app.issues.Corpus;
import app.issues.Extractor;
import app.issues.Initial;
import app.issues.IssueUnit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.TimeTravel;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.Verdict;
import app.issues.World;
import app.study.Row;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * The study's population and columns: the issues resolved as Fixed read at their first In
 * Progress, the split drawn over those of at least one active business day, and the gate before
 * anything is fitted.
 *
 * <p>Every column of the registry is read at t0 = the first In Progress (P11): the changelog is
 * undone from t0 on ({@link Initial#at}), the executor is the assignee at t0 or else whoever made
 * the change (P8), and the participants are the reporter and whoever commented on or changed the
 * issue before t0 (P13). The gate, which must pass for the cache to be written: time travel at t0.
 *
 * <p><b>Population and split</b> (ticket 21). Each issue's active business days are counted as
 * the target counts them ({@link BusinessDays#of}); only the issues of at least one are kept, and
 * the split is drawn over them alone, at random and stratified by project ({@link ProjectSplit}).
 * The cache holds those issues only. The extractor still learns its executors' first touches from
 * every In-Progress issue, as before the drop, so no kept issue's columns depend on it.
 */
public final class IssuesInProgressExtractTask {

  /** P16: the issue went back from In Progress to Open before its first resolution. */
  public static final String PAUSED = "PAUSA_volta_open";

  /**
   * The population and its split, in counts alone: no outcome of either side.
   *
   * @param started the In-Progress issues before the drop
   * @param zeroDays those of 0 active business days, never drawn
   * @param rows the issues drawn: the cache's rows
   * @param projects the projects those belong to
   * @param fit the inner fitting rows, inside the outer training
   * @param validation the inner validation rows, inside the outer training
   */
  public record Split(int started, int zeroDays, int rows, int projects, int train, int test,
      int fit, int validation) {}

  /**
   * @param entries the rows of the cache, its columns only
   * @param team per issue id, the revised team columns ({@link Extractor#TEAM_REVISED}), absent
   *     when missing; never in {@code entries}, so the cache contains only extracted columns
   */
  public record Report(Split split, List<TimeTravel.Check> travel,
      Map<String, Integer> executors, List<RowCache.Entry> entries, List<String> columns,
      Map<String, Map<String, Double>> team) {

    public boolean travelPassed() {
      return travel.stream().allMatch(c -> c.same() == c.total());
    }

    public boolean passed() {
      return travelPassed();
    }
  }

  private final StudySource source;
  private final Progress progress;

  public IssuesInProgressExtractTask(final StudySource source, final Progress progress) {
    this.source = source;
    this.progress = progress;
  }

  public Report run() {
    progress.phase(1, 5, "reading the corpus: %s", source.describe());
    final Corpus corpus = source.load();

    progress.phase(2, 5, "population from In Progress and its active business days");
    final Universe universe = Universe.of(corpus);
    final List<Universe.Unit> started = universe.population(Population.IN_PROGRESS);
    final Set<String> all = new TreeSet<>();
    started.forEach(u -> all.add(u.id()));
    final BusinessDays.Counted counted = BusinessDays.of(corpus, universe, all);
    final List<Universe.Unit> units =
        started.stream().filter(u -> counted.active().get(u.id()) >= 1).toList();

    progress.phase(3, 5, "split of the %,d issues of at least one business day", units.size());
    final Map<String, String> projects = new HashMap<>(units.size() * 2);
    units.forEach(u -> projects.put(u.id(), u.project()));
    final ProjectSplit.Parts split = ProjectSplit.draw(projects);

    progress.phase(4, 5, "columns of %,d issues at the In Progress instant", units.size());
    // the executors' first touches learnt from every In-Progress issue, as before the drop
    return columns(source, progress, corpus, universe, units, all, IN_PROGRESS_STARTER,
        Population.IN_PROGRESS, split, summary(started.size(), projects, split), Map.of(),
        List.of(), 5, 5);
  }

  /**
   * Who made the start, as a canonical person: the executor when nobody was assigned at the start;
   * and how the run names the executor's three cases.
   */
  record Starter(BiFunction<World, Universe.Unit, String> person, String assignee,
      String starter, String none) {}

  /** P8 for the In Progress population: whoever made the transition. */
  static final Starter IN_PROGRESS_STARTER = new Starter(
      (world, u) -> world.person(u.startedBy()), "assignee at t0",
      "no assignee: whoever made the transition", "none");

  /**
   * The columns of {@code units}, each read at its own start ({@link Universe.Unit#start}), the
   * time-travel gate at that instant, and the cache's entries under {@code population}'s split,
   * at the verdicts of {@code split} ({@link ProjectSplit.Parts#outerVerdict}). Phases {@code phase - 1} and {@code phase} of {@code phases}.
   *
   * @param studied the issues whose executors' first touches the extractor learns
   * @param extra per issue id, further values written after the pause flag, in the order of
   *     {@code extraColumns}; empty for none
   */
  static Report columns(final StudySource source, final Progress progress, final Corpus corpus,
      final Universe universe, final List<Universe.Unit> units, final Set<String> studied,
      final Starter starter, final Population population, final ProjectSplit.Parts split,
      final Split summary, final Map<String, Map<String, Double>> extra,
      final List<String> extraColumns, final int phase, final int phases) {
    final Set<String> ids = new TreeSet<>();
    units.forEach(u -> ids.add(u.id()));
    final Map<String, Corpus.Text> texts = source.texts(ids);
    final World world = new World(universe, corpus);
    final Extractor extractor = new Extractor(world, studied);
    final Map<String, Map<String, Double>> values = new HashMap<>(ids.size() * 2);
    final Map<String, Extractor.At> at = new HashMap<>(ids.size() * 2);
    final Map<String, Integer> executors = new java.util.LinkedHashMap<>();
    for (final Universe.Unit u : units) {
      final long t0 = u.start();
      final List<Corpus.Event> events = universe.events(u.id());
      final Initial.Values init =
          Initial.at(universe.issue(u.id()), events, texts.get(u.id()), t0);
      final String assignee = Initial.norm(init.assignee());
      final String executor = assignee != null ? world.person(assignee)
          : starter.person().apply(world, u);
      executors.merge(assignee != null ? starter.assignee()
          : executor != null ? starter.starter() : starter.none(), 1, Integer::sum);
      final Extractor.At a = new Extractor.At(t0, executor, participants(world, universe, u, t0));
      at.put(u.id(), a);
      final Map<String, Double> row =
          extractor.row(u, init, corpus.labels().get(u.id()), a);
      row.put(PAUSED, paused(events, t0, u.resolved()) ? 1.0 : 0.0);
      values.put(u.id(), row);
    }

    progress.phase(phase, phases, "gate: time travel");
    final List<TimeTravel.Check> travel = TimeTravel.run(world, units, values, at);

    final List<String> columns = new ArrayList<>(Attributes.stored());
    columns.add(PAUSED);
    columns.addAll(extraColumns);
    final List<RowCache.Entry> entries = new ArrayList<>(units.size());
    final Map<String, Map<String, Double>> team = new java.util.LinkedHashMap<>();
    for (final Universe.Unit u : units) {
      final Map<String, Double> revised = new HashMap<>();
      for (final String column : Extractor.TEAM_REVISED) {
        final Double v = values.get(u.id()).get(column);
        if (v != null) {
          revised.put(column, v);
        }
      }
      team.put(u.id(), revised);
      // the verdicts of the sides: the side's name, or CROSSING for an issue of a crossing set
      final Map<Population, String[]> parts = new EnumMap<>(Population.class);
      parts.put(population, new String[] {split.outerVerdict(u.id()).name(),
          Verdict.text(split.innerVerdict(u.id()))});
      final Row row = row(u.id(), values.get(u.id()));
      final Map<String, Double> withPause = new java.util.LinkedHashMap<>(row.values());
      withPause.put(PAUSED, values.get(u.id()).get(PAUSED));
      final Map<String, Double> more = extra.getOrDefault(u.id(), Map.of());
      for (final String column : extraColumns) {
        final Double v = more.get(column);
        if (v != null) {
          withPause.put(column, v);
        }
      }
      entries.add(new RowCache.Entry(new IssueUnit(u.id(), u.key(), u.project(),
          world.person(u.reporter()), at.get(u.id()).executor(),
          Instant.ofEpochMilli(u.created()), Instant.ofEpochMilli(u.resolved()), u.reason(),
          u.moved(), Instant.ofEpochMilli(u.start())),
          new Row(u.id(), withPause, row.absent()), parts));
    }
    return new Report(summary, travel, executors, entries, columns, team);
  }

  /** The cache row: every extracted column, its absence flag, and the sensitivity A15w. */
  static Row row(final String id, final Map<String, Double> v) {
    final Map<String, Double> values = new LinkedHashMap<>();
    final Set<String> absent = new HashSet<>();
    for (final Attribute a : Attributes.all()) {
      put(a.name(), a.absenceIndicator(), v, values, absent);
    }
    put(Attributes.SENSITIVITY_A15W, true, v, values, absent);
    return new Row(id, values, absent);
  }

  private static void put(final String name, final boolean indicator, final Map<String, Double> v,
      final Map<String, Double> values, final Set<String> absent) {
    final Double x = v.get(name);
    if (x == null || x.isNaN()) {
      absent.add(name);
      if (!indicator) {
        throw new IllegalStateException(name + " is absent but declares no absence indicator");
      }
    } else {
      values.put(name, x);
    }
    if (indicator) {
      values.put(name + "_ausente", x == null || x.isNaN() ? 1.0 : 0.0);
    }
  }

  /** The split in counts: how many issues were drawn, from how many projects, and where. */
  static Split summary(final int started, final Map<String, String> projects,
      final ProjectSplit.Parts parts) {
    final int rows = parts.outer().size();
    final int train = (int) parts.outer().values().stream()
        .filter(p -> p == Universe.Part.TRAIN).count();
    final int fit = (int) parts.inner().values().stream()
        .filter(p -> p == Universe.Part.TRAIN).count();
    return new Split(started, started - rows, rows, Set.copyOf(projects.values()).size(), train,
        rows - train, fit, parts.inner().size() - fit);
  }

  /** P13: the reporter and whoever commented on or changed the issue in [creation, t0). */
  static Set<String> participants(final World world, final Universe universe,
      final Universe.Unit u, final long t0) {
    final Set<String> out = new HashSet<>();
    final String reporter = world.person(u.reporter());
    if (reporter != null) {
      out.add(reporter);
    }
    for (final Corpus.Comment c : world.comments(u.id())) {
      if (c.at() != null && c.author() != null && c.at() >= u.created() && c.at() < t0) {
        out.add(world.person(c.author()));
      }
    }
    for (final Corpus.Event e : universe.events(u.id())) {
      if (e.at() != null && e.author() != null && e.at() >= u.created() && e.at() < t0) {
        out.add(world.person(e.author()));
      }
    }
    out.remove(null);
    return out;
  }

  /** P16: a status change from In Progress back to Open in [t0, t1). */
  static boolean paused(final List<Corpus.Event> events, final long t0, final long t1) {
    for (final Corpus.Event e : events) {
      if ("status".equals(e.field()) && Universe.IN_PROGRESS_STATUS.equals(e.oldValue())
          && "Open".equals(e.newValue()) && e.at() != null && e.at() >= t0 && e.at() < t1) {
        return true;
      }
    }
    return false;
  }
}
