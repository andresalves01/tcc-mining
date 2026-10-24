package app.issues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which issues exist for the study, what their outcome is, and the population built on them.
 *
 * <p>The rules of the Python library and funnel scripts, and
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
 *   <li><b>Units.</b> Every home, unique issue with a creation instant, a first resolution after
 *       it and a reporter has a unit, its duration from the creation; so do the issues of the
 *       labelled funnel ({@code issue_label}) with the first linked commit no later than the
 *       resolution, one per key. The units are the history the columns and the Blocker pace read;
 *       dropping some would move those columns.
 *   <li><b>Population.</b> {@link Population#IN_PROGRESS}: the units whose reason is Fixed with a
 *       status change to In Progress in [creation, first resolution); their duration starts at the
 *       first such change.
 * </ul>
 */
public final class Universe {

  public static final Set<String> RESOLVED = Set.of("Resolved", "Closed");
  public static final Set<String> MOVE_FIELDS = Set.of("Key", "Project", "project");

  /** The study's population. */
  public enum Population {
    /**
     * The issues resolved as Fixed at the first resolution with a status change to In Progress in
     * [creation, first resolution); their duration starts at the first such change.
     */
    IN_PROGRESS
  }

  /** The status that marks the start of the work. */
  public static final String IN_PROGRESS_STATUS = "In Progress";

  /** Where one issue landed under the split ({@link ProjectSplit}). */
  public enum Part { TRAIN, TEST }

  /**
   * One issue with its outcome, as every later stage sees it.
   *
   * @param start where the duration starts: the creation for a unit of the history, the first In
   *     Progress in {@link Population#IN_PROGRESS}
   * @param startedBy the raw author of the change that started it, null when it is the creation
   */
  public record Unit(String id, String key, String project, String reporter, long created,
      long resolved, String reason, boolean moved, long start, String startedBy) {

    /** A unit whose duration starts at its creation. */
    public Unit(final String id, final String key, final String project, final String reporter,
        final long created, final long resolved, final String reason, final boolean moved) {
      this(id, key, project, reporter, created, resolved, reason, moved, created, null);
    }

  }

  private final Map<String, Corpus.Issue> issues;
  private final Map<String, List<Corpus.Event>> changelog;
  private final List<String> universe;
  private final Map<String, Unit> units;
  private final List<String> inProgress;
  private final Map<String, Unit> started;

  private Universe(final Map<String, Corpus.Issue> issues,
      final Map<String, List<Corpus.Event>> changelog, final List<String> universe,
      final Map<String, Unit> units, final List<String> inProgress,
      final Map<String, Unit> started) {
    this.issues = issues;
    this.changelog = changelog;
    this.universe = universe;
    this.units = units;
    this.inProgress = inProgress;
    this.started = started;
  }

  public static Universe of(final Corpus corpus) {
    final Map<String, Corpus.Issue> issues = new HashMap<>(corpus.issues().size() * 2);
    for (final Corpus.Issue issue : corpus.issues()) {
      issues.put(issue.id(), issue);
    }
    final Map<String, List<Corpus.Event>> changelog = changelog(corpus.events());

    // home, then one per key, then a creation instant
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

    final Map<String, Unit> units = new HashMap<>();
    final List<String> fixed = new ArrayList<>();
    for (final String id : universe) {
      final Corpus.Issue issue = issues.get(id);
      final List<Corpus.Event> events = changelog.getOrDefault(id, List.of());
      final Long resolved = firstResolution(events);
      if (resolved == null || resolved <= issue.created() || issue.reporter() == null) {
        continue;
      }
      final String reason = reasonAt(events, resolved);
      final boolean moved = events.stream().anyMatch(e -> MOVE_FIELDS.contains(e.field()));
      units.put(id, new Unit(id, issue.key(), issue.project(), issue.reporter(), issue.created(),
          resolved, reason, moved));
      if ("Fixed".equals(reason)) {
        fixed.add(id);
      }
    }

    // the labelled funnel's issues, resolved no earlier than their first linked commit, one per
    // key: their units join the history
    final List<String> inheritedAll = new ArrayList<>();
    for (final Map.Entry<String, Corpus.Label> e : corpus.labels().entrySet()) {
      if (e.getValue().resolved() >= e.getValue().firstCommit() && issues.containsKey(e.getKey())) {
        inheritedAll.add(e.getKey());
      }
    }
    Collections.sort(inheritedAll);
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
    }

    // the population: the Fixed units with an In Progress before their first resolution
    final Map<String, Unit> started = new HashMap<>();
    final List<String> inProgress = new ArrayList<>();
    for (final String id : fixed) {
      final Unit u = units.get(id);
      final Corpus.Event e =
          firstInProgress(changelog.getOrDefault(id, List.of()), u.created(), u.resolved());
      if (e != null) {
        started.put(id, new Unit(id, u.key(), u.project(), u.reporter(), u.created(),
            u.resolved(), u.reason(), u.moved(), e.at(), e.author()));
        inProgress.add(id);
      }
    }
    return new Universe(issues, changelog, List.copyOf(universe), units, List.copyOf(inProgress),
        started);
  }

  /** The first status change to In Progress with its instant in {@code [from, to)}, or null. */
  public static Corpus.Event firstInProgress(final List<Corpus.Event> events, final long from,
      final long to) {
    for (final Corpus.Event e : events) {
      if ("status".equals(e.field()) && IN_PROGRESS_STATUS.equals(e.newValue()) && e.at() != null
          && e.at() >= from && e.at() < to) {
        return e;
      }
    }
    return null;
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

  // ------------------------------------------------------------------ accessors

  /** Home, unique issues with a creation instant: the history every column counts over. */
  public List<String> universe() {
    return universe;
  }

  public List<Unit> population(final Population population) {
    return inProgress.stream().map(started::get).toList();
  }

  /** The unit of an issue in the history, its duration from creation; null without one. */
  public Unit unit(final String id) {
    return units.get(id);
  }

  /** The unit of an issue in the population: it starts at the first In Progress. */
  public Unit unit(final Population population, final String id) {
    return started.get(id);
  }

  public Corpus.Issue issue(final String id) {
    return issues.get(id);
  }

  public List<Corpus.Event> events(final String id) {
    return changelog.getOrDefault(id, List.of());
  }
}
