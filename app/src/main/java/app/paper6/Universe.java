package app.paper6;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which issues exist for the study, what their outcome is, and the populations built on them.
 *
 * <p>The rules of {@code papers/issues/viabilidade/analises/lib.py} and {@code 10_funil.py}, and
 * nothing else:
 *
 * <ul>
 *   <li><b>Home.</b> A tracker stores copies of other projects' issues (link targets). An issue is
 *       the project's own when its key prefix is the dominant key of one of the project's trackers.
 *   <li><b>One issue per key.</b> The maven trackers MAVEN and MNG hold the same issues, collected
 *       at different dates. Of the copies, the one with the longest changelog stays -- the most
 *       recent collection -- and the smallest id breaks ties.
 *   <li><b>Outcome.</b> The first status transition to Resolved or Closed. The reason is the
 *       {@code resolution} in force at that instant, read from the changelog and never replaced by
 *       the final value. Reopenings do not move it.
 *   <li><b>Populations.</b> {@link Population#BROAD}: the home, unique issues with a creation
 *       instant, a first resolution after it, and a reporter. {@link Population#FIXED}, the main
 *       one: those whose reason is Fixed. {@link Population#INHERITED}: the previous study's labels
 *       with the first linked commit no later than the resolution, one per key.
 * </ul>
 */
public final class Universe {

  public static final Set<String> RESOLVED = Set.of("Resolved", "Closed");
  public static final Set<String> MOVE_FIELDS = Set.of("Key", "Project", "project");

  /** The three populations of the study, in the order the report prints them. */
  public enum Population {
    /** Main: resolved as Fixed at the first resolution. */
    FIXED,
    /** Sensitivity: any reason. */
    BROAD,
    /** Conditional: the previous study's labels, code-linked, Δ₂ ≥ 0, one per key. */
    INHERITED
  }

  /** Where one issue landed under one cut. */
  public enum Part { TRAIN, TEST, CROSSES }

  /** One issue with its outcome, as every later stage sees it. */
  public record Unit(String id, String key, String project, String reporter, long created,
      long resolved, String reason, boolean moved) {

    public double durationHours() {
      return (resolved - created) / 3_600_000.0;
    }
  }

  private final Map<String, Corpus.Issue> issues;
  private final Map<String, List<Corpus.Event>> changelog;
  private final List<String> universe;
  private final Map<String, Unit> units;
  private final Map<Population, List<String>> populations;
  private final Map<String, Integer> funnel;

  private Universe(final Map<String, Corpus.Issue> issues,
      final Map<String, List<Corpus.Event>> changelog, final List<String> universe,
      final Map<String, Unit> units, final Map<Population, List<String>> populations,
      final Map<String, Integer> funnel) {
    this.issues = issues;
    this.changelog = changelog;
    this.universe = universe;
    this.units = units;
    this.populations = populations;
    this.funnel = funnel;
  }

  /** The funnel of the broad population, stage by stage, as {@code 10_funil.txt} prints it. */
  public Map<String, Integer> funnel() {
    return funnel;
  }

  public static Universe of(final Corpus corpus) {
    final Map<String, Corpus.Issue> issues = new HashMap<>(corpus.issues().size() * 2);
    for (final Corpus.Issue issue : corpus.issues()) {
      issues.put(issue.id(), issue);
    }
    final Map<String, List<Corpus.Event>> changelog = changelog(corpus.events());

    // home, then one per key, then a creation instant -- the order of the funnel
    final List<String> home = new ArrayList<>();
    for (final Corpus.Issue issue : corpus.issues()) {
      if (corpus.homeKeys().getOrDefault(issue.project(), Set.of()).contains(prefix(issue.key()))) {
        home.add(issue.id());
      }
    }
    Collections.sort(home);
    final List<String> unique = uniqueByKey(home, issues, changelog);
    final List<String> universe = new ArrayList<>();
    for (final String id : unique) {
      if (issues.get(id).created() != null) {
        universe.add(id);
      }
    }

    final Map<String, Integer> funnel = new LinkedHashMap<>();
    funnel.put("issues no corpus", corpus.issues().size());
    funnel.put("da casa", home.size());
    funnel.put("chave Jira unica", unique.size());
    funnel.put("com instante de criacao", universe.size());
    int withResolution = 0;
    int positive = 0;
    final Map<String, Unit> units = new HashMap<>();
    final List<String> broad = new ArrayList<>();
    final List<String> fixed = new ArrayList<>();
    for (final String id : universe) {
      final Corpus.Issue issue = issues.get(id);
      final List<Corpus.Event> events = changelog.getOrDefault(id, List.of());
      final Long resolved = firstResolution(events);
      if (resolved == null) {
        continue;
      }
      withResolution++;
      if (resolved <= issue.created()) {
        continue;
      }
      positive++;
      if (issue.reporter() == null) {
        continue;
      }
      final String reason = reasonAt(events, resolved);
      final boolean moved = events.stream().anyMatch(e -> MOVE_FIELDS.contains(e.field()));
      units.put(id, new Unit(id, issue.key(), issue.project(), issue.reporter(), issue.created(),
          resolved, reason, moved));
      broad.add(id);
      if ("Fixed".equals(reason)) {
        fixed.add(id);
      }
    }

    // the previous study's labels, resolved no earlier than their first linked commit, one per key
    final List<String> inheritedAll = new ArrayList<>();
    for (final Map.Entry<String, Corpus.Label> e : corpus.labels().entrySet()) {
      if (e.getValue().resolved() >= e.getValue().firstCommit() && issues.containsKey(e.getKey())) {
        inheritedAll.add(e.getKey());
      }
    }
    Collections.sort(inheritedAll);
    final List<String> inherited = new ArrayList<>();
    for (final String id : uniqueByKey(inheritedAll, issues, changelog)) {
      // a label is kept only when the universe also yields it a unit: same outcome, same key
      if (!units.containsKey(id)) {
        final Corpus.Issue issue = issues.get(id);
        final List<Corpus.Event> events = changelog.getOrDefault(id, List.of());
        final Long resolved = firstResolution(events);
        if (resolved == null || issue.created() == null || resolved <= issue.created()) {
          continue;
        }
        units.put(id, new Unit(id, issue.key(), issue.project(), issue.reporter(),
            issue.created(), resolved, reasonAt(events, resolved),
            events.stream().anyMatch(ev -> MOVE_FIELDS.contains(ev.field()))));
      }
      inherited.add(id);
    }

    funnel.put("com primeira transicao para Resolved/Closed", withResolution);
    funnel.put("com duracao positiva", positive);
    funnel.put("com relator (AMPLA)", broad.size());
    funnel.put("motivo Fixed na primeira resolucao (FIXED)", fixed.size());
    funnel.put("rotulos herdados com delta2 >= 0", inheritedAll.size());
    funnel.put("rotulos herdados, chave unica (HERDADA)", inherited.size());
    final Map<Population, List<String>> populations = new LinkedHashMap<>();
    populations.put(Population.FIXED, List.copyOf(fixed));
    populations.put(Population.BROAD, List.copyOf(broad));
    populations.put(Population.INHERITED, List.copyOf(inherited));
    return new Universe(issues, changelog, List.copyOf(universe), units, populations, funnel);
  }

  // ------------------------------------------------------------------ rules

  static String prefix(final String key) {
    if (key == null) {
      return "";
    }
    final int dash = key.indexOf('-');
    return dash < 0 ? key : key.substring(0, dash);
  }

  /** Events grouped by issue, each list in changelog order: instant, changelog id, index. */
  static Map<String, List<Corpus.Event>> changelog(final Collection<Corpus.Event> events) {
    final Map<String, List<Corpus.Event>> by = new HashMap<>();
    for (final Corpus.Event event : events) {
      if (event.issue() != null) {
        by.computeIfAbsent(event.issue(), k -> new ArrayList<>()).add(event);
      }
    }
    final Comparator<Corpus.Event> order = Comparator
        .comparingLong((Corpus.Event e) -> e.at() == null ? -1L : e.at())
        .thenComparingLong(e -> changelogId(e.external()))
        .thenComparingLong(e -> changelogIndex(e.external()));
    by.values().forEach(list -> list.sort(order));
    return by;
  }

  private static long changelogId(final String external) {
    if (external == null) {
      return -1;
    }
    final int sep = external.indexOf("%%");
    final String head = sep < 0 ? external : external.substring(0, sep);
    return head.chars().allMatch(Character::isDigit) && !head.isEmpty() ? Long.parseLong(head) : -1;
  }

  private static long changelogIndex(final String external) {
    if (external == null) {
      return 0;
    }
    final int sep = external.indexOf("%%");
    if (sep < 0) {
      return 0;
    }
    final String tail = external.substring(sep + 2);
    return tail.chars().allMatch(Character::isDigit) && !tail.isEmpty() ? Long.parseLong(tail) : 0;
  }

  /** Among copies of one key, the longest changelog, then the smallest id. Keeps the input order. */
  static List<String> uniqueByKey(final List<String> ids, final Map<String, Corpus.Issue> issues,
      final Map<String, List<Corpus.Event>> changelog) {
    final Map<String, String> keep = new HashMap<>();
    for (final String id : ids) {
      final String key = issues.get(id).key();
      final String held = keep.get(key);
      if (held == null || better(id, held, changelog)) {
        keep.put(key, id);
      }
    }
    final Set<String> kept = new HashSet<>(keep.values());
    final List<String> out = new ArrayList<>();
    for (final String id : ids) {
      if (kept.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  private static boolean better(final String candidate, final String held,
      final Map<String, List<Corpus.Event>> changelog) {
    final int a = changelog.getOrDefault(candidate, List.of()).size();
    final int b = changelog.getOrDefault(held, List.of()).size();
    return a != b ? a > b : candidate.compareTo(held) < 0;
  }

  /** The first status transition to Resolved or Closed, or null. */
  public static Long firstResolution(final List<Corpus.Event> events) {
    for (final Corpus.Event e : events) {
      if ("status".equals(e.field()) && e.at() != null && RESOLVED.contains(e.newValue())) {
        return e.at();
      }
    }
    return null;
  }

  /** The resolution in force right after {@code t}, events at {@code t} included; null without one. */
  static String reasonAt(final List<Corpus.Event> events, final long t) {
    String value = null;
    for (final Corpus.Event e : events) {
      if (e.at() == null || e.at() > t) {
        break;
      }
      if ("resolution".equals(e.field())) {
        value = e.newValue();
      }
    }
    return value;
  }

  /** How often the status leaves Resolved/Closed after {@code after}. */
  public static int reopenings(final List<Corpus.Event> events, final long after) {
    int n = 0;
    for (final Corpus.Event e : events) {
      if ("status".equals(e.field()) && e.at() != null && e.at() > after
          && RESOLVED.contains(e.oldValue()) && !RESOLVED.contains(e.newValue())) {
        n++;
      }
    }
    return n;
  }

  // ------------------------------------------------------------------ split

  /** Outer and inner verdicts of one population, by issue id. */
  public record Split(long cut, long innerCut, Map<String, Part> outer, Map<String, Part> inner) {}

  /**
   * The rule of {@code app.labels.TemporalSplit} with no eligibility: the cut is the creation at
   * position int(0.70 (n − 1)); created before and resolved before is training; created before and
   * resolved at or after crosses and is set aside; created at or after is test. The same rule again
   * on the training side gives the inner split.
   */
  public Split split(final Population population, final double share) {
    final List<Unit> rows = population(population);
    final long cut = cutOf(rows, share);
    final Map<String, Part> outer = parts(rows, cut);
    final List<Unit> train = rows.stream().filter(u -> outer.get(u.id()) == Part.TRAIN).toList();
    final long innerCut = cutOf(train, share);
    return new Split(cut, innerCut, outer, parts(train, innerCut));
  }

  static long cutOf(final List<Unit> rows, final double share) {
    final long[] created = rows.stream().mapToLong(Unit::created).sorted().toArray();
    return created[(int) (share * (created.length - 1))];
  }

  static Map<String, Part> parts(final List<Unit> rows, final long cut) {
    final Map<String, Part> out = new HashMap<>(rows.size() * 2);
    for (final Unit u : rows) {
      out.put(u.id(), u.created() < cut ? (u.resolved() < cut ? Part.TRAIN : Part.CROSSES)
          : Part.TEST);
    }
    return out;
  }

  /**
   * The gate that the split rule is the thesis's: with five resolutions per (person, project)
   * required, it must reproduce the stored {@code split} of every label. Returns the mismatches.
   */
  public static int thesisSplitMismatches(final Map<String, Corpus.Label> labels,
      final double share, final int minResolved) {
    final long[] created = labels.values().stream().mapToLong(Corpus.Label::created).sorted()
        .toArray();
    final long cut = created[(int) (share * (created.length - 1))];
    final Map<String, Integer> resolvedBefore = new HashMap<>();
    for (final Corpus.Label l : labels.values()) {
      if (l.resolved() < cut) {
        resolvedBefore.merge(l.canonical() + "|" + l.project(), 1, Integer::sum);
      }
    }
    int mismatches = 0;
    for (final Corpus.Label l : labels.values()) {
      final String part;
      if (l.created() < cut && l.resolved() >= cut) {
        part = "CROSSES_CUT";
      } else if (resolvedBefore.getOrDefault(l.canonical() + "|" + l.project(), 0) < minResolved) {
        part = "INSUFFICIENT_HISTORY";
      } else {
        part = l.created() < cut ? "TRAIN" : "TEST";
      }
      if (!part.equals(l.split())) {
        mismatches++;
      }
    }
    return mismatches;
  }

  // ------------------------------------------------------------------ accessors

  /** Home, unique issues with a creation instant: the history every column counts over. */
  public List<String> universe() {
    return universe;
  }

  public List<Unit> population(final Population population) {
    return populations.get(population).stream().map(units::get).toList();
  }

  public Unit unit(final String id) {
    return units.get(id);
  }

  public Corpus.Issue issue(final String id) {
    return issues.get(id);
  }

  public List<Corpus.Event> events(final String id) {
    return changelog.getOrDefault(id, List.of());
  }

  /** Every issue any population holds. */
  public Set<String> studied() {
    final Set<String> out = new HashSet<>();
    populations.values().forEach(out::addAll);
    return out;
  }
}
