package app.pipeline;

import app.paper5.Row;
import app.paper6.Attribute;
import app.paper6.Attributes;
import app.paper6.Corpus;
import app.paper6.Extractor;
import app.paper6.Initial;
import app.paper6.IssueUnit;
import app.paper6.Parity;
import app.paper6.RowCache;
import app.paper6.TimeTravel;
import app.paper6.Universe;
import app.paper6.Universe.Population;
import app.paper6.World;
import app.paper6.port.StudySource;
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

/**
 * Builds the study's populations and columns from the corpus and checks them before anything is
 * fitted.
 *
 * <p>The gates, all of which must pass for the cache to be written:
 *
 * <ol>
 *   <li>the split rule, with the thesis's five resolutions per pair, reproduces the stored
 *       {@code split} of every label;
 *   <li>time travel: the historical C1 columns are identical on a view truncated at t0;
 *   <li>parity: populations and every column agree with the Python specification.
 * </ol>
 *
 * <p>The funnel and the changelog fidelity are reported, not gated: they are compared by eye with
 * {@code saidas/10_funil.txt} and {@code 20_changelog.txt}, and any difference shows up in parity.
 */
public final class Paper6ExtractTask {

  public static final double SHARE = 0.70;

  /** One population's split: cuts and sizes. */
  public record SplitSummary(Population population, int rows, Instant cut, int train, int test,
      int crosses, Instant innerCut, int fit, int selection, int innerCrosses) {}

  public record Report(Map<String, Integer> funnel, List<SplitSummary> splits,
      int thesisSplitMismatches, List<Initial.Fidelity> fidelity, List<TimeTravel.Check> travel,
      List<Parity.Membership> membership, List<Parity.Column> parity,
      List<RowCache.Entry> entries, List<String> columns) {

    public boolean travelPassed() {
      return travel.stream().allMatch(c -> c.same() == c.total());
    }

    public boolean parityPassed() {
      return membership.stream().allMatch(m -> m.onlyJava() == 0 && m.onlyPython() == 0)
          && parity.stream().allMatch(c -> c.mismatches() == 0);
    }

    public boolean passed() {
      return thesisSplitMismatches == 0 && travelPassed() && parityPassed();
    }
  }

  private final StudySource source;
  private final Progress progress;

  public Paper6ExtractTask(final StudySource source, final Progress progress) {
    this.source = source;
    this.progress = progress;
  }

  public Report run(final Parity.Spec python) {
    progress.phase(1, 5, "lendo o corpus: %s", source.describe());
    final Corpus corpus = source.load();

    progress.phase(2, 5, "populacoes e divisao");
    final Universe universe = Universe.of(corpus);
    final int thesis = Universe.thesisSplitMismatches(corpus.labels(), SHARE, 5);
    final Map<Population, Universe.Split> splits = new EnumMap<>(Population.class);
    final List<SplitSummary> summaries = new ArrayList<>();
    for (final Population p : Population.values()) {
      final Universe.Split s = universe.split(p, SHARE);
      splits.put(p, s);
      summaries.add(summary(p, universe.population(p).size(), s));
    }

    progress.phase(3, 5, "texto das issues estudadas e valores na criacao");
    final Set<String> studied = universe.studied();
    final Map<String, Corpus.Text> texts = source.texts(studied);
    final Map<String, Initial.Values> initial = new HashMap<>(studied.size() * 2);
    for (final String id : studied) {
      initial.put(id, Initial.of(universe.issue(id), universe.events(id), texts.get(id)));
    }
    final List<Initial.Fidelity> fidelity = Initial.fidelity(universe,
        universe.population(Population.BROAD).stream().map(Universe.Unit::id).toList(), texts);

    progress.phase(4, 5, "colunas de %,d issues", studied.size());
    final World world = new World(universe, corpus);
    final Extractor extractor = new Extractor(world, studied);
    final Map<String, Map<String, Double>> values = new HashMap<>(studied.size() * 2);
    final List<String> ordered = new ArrayList<>(new TreeSet<>(studied));
    int done = 0;
    for (final String id : ordered) {
      values.put(id, extractor.row(universe.unit(id), initial.get(id),
          corpus.labels().get(id)));
      if (++done % 20_000 == 0) {
        progress.count(done, ordered.size(), "issues");
      }
    }

    progress.phase(5, 5, "portoes: viagem no tempo e paridade");
    final List<TimeTravel.Check> travel =
        TimeTravel.run(world, universe.population(Population.BROAD), values);
    final List<Parity.Membership> membership = new ArrayList<>();
    final List<Parity.Column> parity = new ArrayList<>();
    if (python != null) {
      membership.add(Parity.membership("fixed", python.populations().get("fixed"),
          ids(universe, Population.FIXED)));
      membership.add(Parity.membership("ampla", python.populations().get("ampla"),
          ids(universe, Population.BROAD)));
      membership.add(Parity.membership("herdada", python.populations().get("herdada"),
          ids(universe, Population.INHERITED)));
      parity.addAll(Parity.columns(python, values));
    }

    final List<String> columns = Attributes.stored();
    final List<RowCache.Entry> entries = new ArrayList<>(ordered.size());
    final Map<Population, Set<String>> members = new EnumMap<>(Population.class);
    for (final Population p : Population.values()) {
      members.put(p, ids(universe, p));
    }
    for (final String id : ordered) {
      final Universe.Unit u = universe.unit(id);
      final Map<Population, String[]> parts = new EnumMap<>(Population.class);
      for (final Population p : Population.values()) {
        if (members.get(p).contains(id)) {
          final Universe.Split s = splits.get(p);
          final Universe.Part outer = s.outer().get(id);
          final Universe.Part inner = s.inner().get(id);
          parts.put(p, new String[] {outer.name(), inner == null ? "" : inner.name()});
        }
      }
      final World.Commit ex = extractorExecutor(extractor, u);
      entries.add(new RowCache.Entry(new IssueUnit(u.id(), u.key(), u.project(),
          world.person(u.reporter()), ex == null ? null : ex.who(),
          Instant.ofEpochMilli(u.created()), Instant.ofEpochMilli(u.resolved()), u.reason(),
          u.moved()), row(id, values.get(id)), parts));
    }
    return new Report(universe.funnel(), summaries, thesis, fidelity, travel, membership, parity,
        entries, columns);
  }

  private static World.Commit extractorExecutor(final Extractor extractor, final Universe.Unit u) {
    return extractor.executorOf(u.id(), u.resolved());
  }

  private static Set<String> ids(final Universe universe, final Population p) {
    final Set<String> out = new HashSet<>();
    universe.population(p).forEach(u -> out.add(u.id()));
    return out;
  }

  /** The cache row: every registered column, its absence flag, and the sensitivity A15w. */
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

  private static SplitSummary summary(final Population p, final int rows, final Universe.Split s) {
    int train = 0;
    int test = 0;
    int crosses = 0;
    for (final Universe.Part part : s.outer().values()) {
      train += part == Universe.Part.TRAIN ? 1 : 0;
      test += part == Universe.Part.TEST ? 1 : 0;
      crosses += part == Universe.Part.CROSSES ? 1 : 0;
    }
    int fit = 0;
    int selection = 0;
    int innerCrosses = 0;
    for (final Universe.Part part : s.inner().values()) {
      fit += part == Universe.Part.TRAIN ? 1 : 0;
      selection += part == Universe.Part.TEST ? 1 : 0;
      innerCrosses += part == Universe.Part.CROSSES ? 1 : 0;
    }
    return new SplitSummary(p, rows, Instant.ofEpochMilli(s.cut()), train, test, crosses,
        Instant.ofEpochMilli(s.innerCut()), fit, selection, innerCrosses);
  }
}
