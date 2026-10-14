package app.pipeline;

import app.issues.Attributes;
import app.issues.Corpus;
import app.issues.Extractor;
import app.issues.Initial;
import app.issues.IssueUnit;
import app.issues.Parity;
import app.issues.RowCache;
import app.issues.TimeTravel;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.World;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The secondary study's population and columns ({@code papers/issues-in-progress/DESENHO.md}):
 * FIXED issues read at their first In Progress, and the gates before anything is fitted.
 *
 * <p>Every column is the main study's, read at t0 = the first In Progress (P11): the changelog is
 * undone from t0 on ({@link Initial#at}), the executor is the assignee at t0 or else whoever made
 * the change (P8), and the participants are the reporter and whoever commented on or changed the
 * issue before t0 (P13). The gates, all of which must pass for the cache to be written: time travel
 * at t0, and parity of the population and of every column with the Python specification
 * ({@code papers/issues-in-progress/viabilidade/analises/20_exportar_andamento.py}).
 */
public final class IssuesInProgressExtractTask {

  public static final double SHARE = IssuesExtractTask.SHARE;

  /** P16: the issue went back from In Progress to Open before its first resolution. */
  public static final String PAUSED = "PAUSA_volta_open";

  public record Report(IssuesExtractTask.SplitSummary split, List<TimeTravel.Check> travel,
      List<Parity.Membership> membership, List<Parity.Column> parity,
      Map<String, Integer> executors, List<RowCache.Entry> entries, List<String> columns) {

    public boolean travelPassed() {
      return travel.stream().allMatch(c -> c.same() == c.total());
    }

    public boolean parityPassed() {
      return !membership.isEmpty()
          && membership.stream().allMatch(m -> m.onlyJava() == 0 && m.onlyPython() == 0)
          && parity.stream().allMatch(c -> c.mismatches() == 0);
    }

    public boolean passed() {
      return travelPassed() && parityPassed();
    }
  }

  private final StudySource source;
  private final Progress progress;

  public IssuesInProgressExtractTask(final StudySource source, final Progress progress) {
    this.source = source;
    this.progress = progress;
  }

  public Report run(final Parity.Spec python) {
    progress.phase(1, 4, "lendo o corpus: %s", source.describe());
    final Corpus corpus = source.load();

    progress.phase(2, 4, "populacao a partir do In Progress e divisao pelo instante dele");
    final Universe universe = Universe.of(corpus);
    final List<Universe.Unit> units = universe.population(Population.IN_PROGRESS);
    final Universe.Split split = universe.split(Population.IN_PROGRESS, SHARE);

    progress.phase(3, 4, "colunas de %,d issues no instante do In Progress", units.size());
    final Set<String> ids = new TreeSet<>();
    units.forEach(u -> ids.add(u.id()));
    final Map<String, Corpus.Text> texts = source.texts(ids);
    final World world = new World(universe, corpus);
    final Extractor extractor = new Extractor(world, ids);
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
          : world.person(u.startedBy());
      executors.merge(assignee != null ? "atribuido em t0"
          : executor != null ? "sem atribuido: quem fez a transicao" : "nenhum", 1, Integer::sum);
      final Extractor.At a = new Extractor.At(t0, executor, participants(world, universe, u, t0));
      at.put(u.id(), a);
      final Map<String, Double> row =
          extractor.row(u, init, corpus.labels().get(u.id()), a);
      row.put(PAUSED, paused(events, t0, u.resolved()) ? 1.0 : 0.0);
      values.put(u.id(), row);
    }

    progress.phase(4, 4, "portoes: viagem no tempo e paridade");
    final List<TimeTravel.Check> travel = TimeTravel.run(world, units, values, at);
    final List<Parity.Membership> membership = new ArrayList<>();
    final List<Parity.Column> parity = new ArrayList<>();
    if (python != null && python.populations().containsKey("andamento")) {
      membership.add(Parity.membership("andamento", python.populations().get("andamento"), ids));
      parity.addAll(Parity.columns(python, values));
    }

    final List<String> columns = new ArrayList<>(Attributes.stored());
    columns.add(PAUSED);
    final List<RowCache.Entry> entries = new ArrayList<>(units.size());
    for (final Universe.Unit u : units) {
      final Universe.Part outer = split.outer().get(u.id());
      final Universe.Part inner = split.inner().get(u.id());
      final Map<Population, String[]> parts = new EnumMap<>(Population.class);
      parts.put(Population.IN_PROGRESS,
          new String[] {outer.name(), inner == null ? "" : inner.name()});
      final app.study.Row row = IssuesExtractTask.row(u.id(), values.get(u.id()));
      final Map<String, Double> withPause = new java.util.LinkedHashMap<>(row.values());
      withPause.put(PAUSED, values.get(u.id()).get(PAUSED));
      entries.add(new RowCache.Entry(new IssueUnit(u.id(), u.key(), u.project(),
          world.person(u.reporter()), at.get(u.id()).executor(),
          Instant.ofEpochMilli(u.created()), Instant.ofEpochMilli(u.resolved()), u.reason(),
          u.moved(), Instant.ofEpochMilli(u.start())),
          new app.study.Row(u.id(), withPause, row.absent()), parts));
    }
    return new Report(IssuesExtractTask.summary(Population.IN_PROGRESS, units.size(), split),
        travel, membership, parity, executors, entries, columns);
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
