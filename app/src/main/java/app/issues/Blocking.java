package app.issues;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * How long an issue waited on the issues it was blocked by (ticket 19; items 16, 26 and 28 of the
 * retrospective decisions), a retrospective attribute: it reads other issues' resolutions up to
 * this issue's first resolution (t1).
 *
 * <p><b>Blockers.</b> Every issue linked to this one with an effect, read from this issue's side,
 * that makes it wait ({@link #WAITING}: {@code is blocked by}, {@code depends upon}, {@code
 * requires}), at any time before t1: the links at creation, recovered by undoing every link change
 * of the changelog from the current record, and those added strictly before t1, removed later or
 * not. A type whose effect gives no direction ({@code Blocked / Blocked}, {@code Dependent /
 * Dependent}) does not count. A blocker may be in any project. A link usually points at the copy
 * of the other issue that this tracker stores, which has no changelog; its first resolution is read
 * from the universe's issue of the same key ({@link Universe#universe}, the home, one-per-key
 * issues). A blocker the universe holds no issue of that key for is left out: its resolution is
 * unknown, not absent.
 *
 * <p><b>Wait.</b> Each blocker's first resolution of any reason ({@link Universe#firstResolution}),
 * never resolved or resolved after t1 taken as t1; the latest of them; the active business days
 * from t0 to it at the issue's day boundary, the time the issue spent back in Open or Reopened left
 * out by the same function as the target ({@link BusinessDays#active}, item 37), so the wait never
 * exceeds the duration; 0 when it is not after t0. Missing without a blocker.
 */
public final class Blocking {

  /** The link effects, read from the issue's own side, under which it waits on the other issue. */
  public static final Set<String> WAITING = Set.of("is blocked by", "depends upon", "requires");

  private static final String LINKS = "issue_links";

  private final Universe universe;
  private final Set<String> home;
  private final Map<String, String> homeByKey;

  private Blocking(final Universe universe, final Set<String> home,
      final Map<String, String> homeByKey) {
    this.universe = universe;
    this.home = home;
    this.homeByKey = homeByKey;
  }

  public static Blocking of(final Universe universe) {
    final Map<String, String> byKey = new HashMap<>();
    for (final String id : universe.universe()) {
      byKey.put(universe.issue(id).key(), id);
    }
    return new Blocking(universe, new HashSet<>(universe.universe()), byKey);
  }

  /**
   * The business days from t0 to the last of the blockers' first resolutions, clipped at t1, at
   * {@code boundaryHour}; null without a blocker of known resolution.
   */
  public Double wait(final Universe.Unit u, final int boundaryHour) {
    final long t0 = u.start();
    final long t1 = u.resolved();
    Long latest = null;
    for (final String target : blockers(universe.issue(u.id()), universe.events(u.id()), t1)) {
      final String resolvedFrom = home.contains(target) ? target
          : universe.issue(target) == null ? null
          : homeByKey.get(universe.issue(target).key());
      if (resolvedFrom == null || resolvedFrom.equals(u.id())) {
        continue;
      }
      final Long first = Universe.firstResolution(universe.events(resolvedFrom));
      final long clipped = first == null ? t1 : Math.min(first, t1);
      latest = latest == null ? clipped : Math.max(latest, clipped);
    }
    if (latest == null) {
      return null;
    }
    return latest <= t0 ? 0.0
        : (double) BusinessDays.active(universe.events(u.id()), t0, latest, boundaryHour);
  }

  /**
   * The issues {@code issue} waited on at any time before {@code t1}, by the ids its links point
   * at, itself left out.
   */
  static Set<String> blockers(final Corpus.Issue issue, final List<Corpus.Event> events,
      final long t1) {
    // the links at creation: the current ones with every link change undone, last first, by the
    // rule the link count at creation is read with
    final List<Corpus.Event> changes = Initial.field(events, LINKS);
    final List<Corpus.Link> current = issue.typedLinks() == null ? List.of()
        : issue.typedLinks().stream().map(Blocking::key).toList();
    final Set<String> out = new TreeSet<>();
    Initial.undo(current, changes, e -> e.newLink() == null ? null : key(e.newLink()),
        e -> e.oldLink() == null ? null : key(e.oldLink())).held()
        .forEach(l -> add(out, l, issue.id()));
    for (final Corpus.Event e : changes) {
      if (e.newLink() != null && e.at() != null && e.at() < t1) {
        add(out, key(e.newLink()), issue.id());
      }
    }
    return out;
  }

  /** The link as compared: its target and its effect in lower case; the type does not matter. */
  private static Corpus.Link key(final Corpus.Link l) {
    return new Corpus.Link(l.target(), null,
        l.effect() == null ? null : l.effect().trim().toLowerCase(Locale.ROOT));
  }

  private static void add(final Set<String> out, final Corpus.Link l, final String self) {
    // a link without a recorded effect has no direction, so it is not a wait
    if (l.target() != null && l.effect() != null && !l.target().equals(self)
        && WAITING.contains(l.effect())) {
      out.add(l.target());
    }
  }
}
