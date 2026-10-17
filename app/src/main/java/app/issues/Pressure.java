package app.issues;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The own start model's three attributes of ticket 19 (items 17a, 21 to 23, 25, 27 and 28 of the
 * retrospective decisions), known at the issue's first In Progress (t0). They join the own
 * specification, so every model built on it reads them.
 *
 * <ol>
 *   <li>{@link #FIX_VERSION}: 1 if the issue had a fix version at creation or got one strictly
 *       before t0, even if it was removed later; 0 otherwise ({@link FixVersions#setBefore}).
 *   <li>{@link #RELEASE_CYCLE}: the expected calendar days left in the project's release cycle
 *       ({@link Releases#cycle}): the median gap between its releases dated strictly before t0
 *       minus the days since the last of them, negative when overdue; missing with fewer than
 *       three such releases. Raw: it is signed.
 *   <li>{@link #BLOCKER_PACE}: the median calendar days from creation to first resolution of the
 *       project's other issues that were Blocker priority at their first resolution, of any
 *       reason, resolved strictly before t0; the issue itself never; missing with fewer than
 *       three. Those issues are the universe's units of any reason ({@link Universe#unit}), the
 *       broad population's rule: home, one per key, a reporter, resolved after their creation.
 * </ol>
 *
 * <p>Calendar days are elapsed 24-hour days: other issues and releases have no day boundary of
 * their own. Nothing here reads anything after t0 but the reference issues' own priority up to
 * their resolution, which is itself before t0.
 */
public final class Pressure {

  public static final String FIX_VERSION = "START_fix_version";
  public static final String RELEASE_CYCLE = "START_release_cycle_days";
  public static final String BLOCKER_PACE = "START_blocker_pace_days";

  /** The three, in the ticket's order. */
  public static final List<String> COLUMNS = List.of(FIX_VERSION, RELEASE_CYCLE, BLOCKER_PACE);

  /** The priority whose issues set the pace. */
  static final String BLOCKER = "Blocker";

  /** Fewer reference issues than this, and the pace is missing. */
  static final int MIN_REFERENCES = 3;

  /** One reference issue: when it was resolved and in how many calendar days. */
  private record Reference(String id, long resolved, double days) {}

  private final Universe universe;
  private final Releases releases;
  private final Map<String, List<Reference>> blockers;

  private Pressure(final Universe universe, final Releases releases,
      final Map<String, List<Reference>> blockers) {
    this.universe = universe;
    this.releases = releases;
    this.blockers = blockers;
  }

  /** The references of every project, read once. */
  public static Pressure of(final Universe universe, final Releases releases) {
    final Map<String, List<Reference>> blockers = new HashMap<>();
    for (final String id : universe.universe()) {
      final Universe.Unit u = universe.unit(id);
      if (u == null) {
        continue;
      }
      final String priority = priorityAt(universe.issue(id), universe.events(id), u.resolved());
      if (BLOCKER.equals(priority)) {
        blockers.computeIfAbsent(u.project(), k -> new ArrayList<>()).add(new Reference(id,
            u.resolved(), (u.resolved() - u.created()) / (double) World.DAY));
      }
    }
    blockers.values().forEach(l -> l.sort(Comparator.comparingLong(Reference::resolved)
        .thenComparing(Reference::id)));
    return new Pressure(universe, releases, blockers);
  }

  /**
   * The priority in force right after {@code t}, changes at {@code t} included: the old value of
   * the first change before any, as {@link Initial} reads it at creation.
   */
  static String priorityAt(final Corpus.Issue issue, final List<Corpus.Event> events,
      final long t) {
    final List<Corpus.Event> changes = Initial.field(events, "priority");
    String value = changes.isEmpty() ? Initial.norm(issue.priority())
        : Initial.norm(changes.get(0).oldValue());
    for (final Corpus.Event e : changes) {
      if (e.at() != null && e.at() <= t) {
        value = Initial.norm(e.newValue());
      }
    }
    return value;
  }

  /** The three attributes of one In-Progress unit, a missing one as null. */
  public Map<String, Double> row(final Universe.Unit u) {
    final long t0 = u.start();
    final Map<String, Double> v = new LinkedHashMap<>();
    v.put(FIX_VERSION, FixVersions.setBefore(universe.issue(u.id()), universe.events(u.id()), t0)
        ? 1.0 : 0.0);
    v.put(RELEASE_CYCLE, releases.cycle(u.project(), t0));
    final List<Double> days = new ArrayList<>();
    for (final Reference r : blockers.getOrDefault(u.project(), List.of())) {
      if (r.resolved() >= t0) {
        break;
      }
      if (!r.id().equals(u.id())) {
        days.add(r.days());
      }
    }
    v.put(BLOCKER_PACE, days.size() < MIN_REFERENCES ? null : Augmentation.median(days));
    return v;
  }
}
