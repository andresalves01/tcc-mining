package app.issues;

import app.issues.Augmentation.Variant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The own start model's three attributes of ticket 19 (items 17a, 21 to 23, 25, 27 and 28 of the
 * retrospective decisions). They join the own specification, so every model built on it reads
 * them.
 *
 * <ol>
 *   <li>{@link #FIX_VERSION}: 1 if the issue had a fix version at creation or got one strictly
 *       before its first In Progress (t0), even if it was removed later; 0 otherwise ({@link
 *       FixVersions#setBefore}).
 *   <li>{@link #RELEASE_CYCLE}: the expected calendar days left in the project's release cycle
 *       ({@link Releases#cycle}): the median gap between its releases dated strictly before t0
 *       minus the days since the last of them, negative when overdue; missing with fewer than
 *       three such releases. Raw: it is signed.
 *   <li>{@link #blockerPace}: the median calendar days from creation to first resolution of the
 *       project's other issues of the split's training side that were Blocker priority at their
 *       first resolution, whatever their dates; missing with fewer than three. It reads other
 *       issues' durations, labels, so since ticket 21 (items 45 c and 46 of the retrospective
 *       decisions) it follows the rule of the similarity medians ({@link Augmentation}): the
 *       history is the training side, the inner fitting set or the outer training, never a label
 *       of the other side, never the issue itself, which is left out by id. It depends on the
 *       split, so it is written once per variant, its name ending as the similarity columns' do
 *       ({@link Variant#suffix}); a row outside a variant's rows, an outer test row in the inner
 *       variant, has none. Before ticket 21 it read every issue of the project the universe gives
 *       an outcome of any reason, resolved strictly before t0.
 * </ol>
 *
 * <p>The flag and the release cycle are known at t0 and stay so: they describe the issue and its
 * project at the start, no other issue's duration (item 45 d). Calendar days are elapsed 24-hour
 * days: other issues and releases have no day boundary of their own. The flag does read the
 * changelog after t0, but only to undo it back to the creation: it counts the versions there were
 * then ({@link FixVersions#setBefore}), never one added later. A version Jira merged into another
 * or deleted later leaves no changelog entry and only lowers the count at creation, so it can only
 * lower the flag, never raise it.
 */
public final class Pressure {

  public static final String FIX_VERSION = "START_fix_version";
  public static final String RELEASE_CYCLE = "START_release_cycle_days";

  /** The Blocker pace's name before its variant's suffix ({@link #blockerPace}). */
  public static final String BLOCKER_PACE = "START_blocker_pace_days";

  /** The Blocker pace computed against the split of {@code v}. */
  public static String blockerPace(final Variant v) {
    return BLOCKER_PACE + v.suffix();
  }

  /** The columns, in the ticket's order, the Blocker pace once per variant. */
  public static final List<String> COLUMNS = List.of(FIX_VERSION, RELEASE_CYCLE,
      blockerPace(Variant.INNER), blockerPace(Variant.OUTER));

  /** The priority whose issues set the pace. */
  static final String BLOCKER = "Blocker";

  /** Fewer reference issues than this, and the pace is missing. */
  static final int MIN_REFERENCES = 3;

  /** One reference issue: its split and in how many calendar days it was resolved. */
  private record Reference(String id, String outer, String inner, double days) {}

  private final Universe universe;
  private final Releases releases;
  private final Map<String, String[]> parts;
  private final Map<String, List<Reference>> blockers;

  private Pressure(final Universe universe, final Releases releases,
      final Map<String, String[]> parts, final Map<String, List<Reference>> blockers) {
    this.universe = universe;
    this.releases = releases;
    this.parts = parts;
    this.blockers = blockers;
  }

  /**
   * The references of every project, read once.
   *
   * @param parts per issue of the matrix, its outer and inner verdicts; the Blocker issues among
   *     them are the only references, each read in the variants whose training side holds it
   */
  public static Pressure of(final Universe universe, final Releases releases,
      final Map<String, String[]> parts) {
    final Map<String, List<Reference>> blockers = new HashMap<>();
    parts.forEach((id, p) -> {
      final Universe.Unit u = universe.unit(id);
      if (u == null) {
        return;
      }
      final String priority = priorityAt(universe.issue(id), universe.events(id), u.resolved());
      if (BLOCKER.equals(priority)) {
        blockers.computeIfAbsent(u.project(), k -> new ArrayList<>()).add(new Reference(id,
            p[0], p[1], (u.resolved() - u.created()) / (double) World.DAY));
      }
    });
    blockers.values().forEach(l -> l.sort(Comparator.comparing(Reference::id)));
    return new Pressure(universe, releases, Map.copyOf(parts), blockers);
  }

  /** The priority in force right after {@code t}, changes at {@code t} included. */
  static String priorityAt(final Corpus.Issue issue, final List<Corpus.Event> events,
      final long t) {
    return Initial.scalarAt(Initial.field(events, "priority"), issue.priority(), t);
  }

  /**
   * The columns of one In-Progress unit of the matrix, every one keyed, a missing one as null.
   */
  public Map<String, Double> row(final Universe.Unit u) {
    final long t0 = u.start();
    final Map<String, Double> v = new LinkedHashMap<>();
    v.put(FIX_VERSION, FixVersions.setBefore(universe.issue(u.id()), universe.events(u.id()), t0)
        ? 1.0 : 0.0);
    v.put(RELEASE_CYCLE, releases.cycle(u.project(), t0));
    final String[] mine = parts.get(u.id());
    for (final Variant variant : Variant.values()) {
      v.put(blockerPace(variant), mine == null || !(variant.fitting(mine[0], mine[1])
          || variant.scored(mine[0], mine[1])) ? null : pace(u, variant));
    }
    return v;
  }

  /** The median over the project's Blocker issues of the variant's training side, u left out. */
  private Double pace(final Universe.Unit u, final Variant variant) {
    final List<Double> days = new ArrayList<>();
    for (final Reference r : blockers.getOrDefault(u.project(), List.of())) {
      if (!r.id().equals(u.id()) && variant.fitting(r.outer(), r.inner())) {
        days.add(r.days());
      }
    }
    return days.size() < MIN_REFERENCES ? null : Augmentation.median(days);
  }
}
