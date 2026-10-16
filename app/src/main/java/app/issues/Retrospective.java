package app.issues;

import app.labels.ChangedMethod;
import app.labels.CommitMethods;
import app.labels.MethodDeduplication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The retrospective scenario's eight attributes (ticket 15), observed between the first In
 * Progress (t0) and the first resolution (t1). They describe the work as it was done; a count
 * grows with the duration itself, so a model reading them shows association with the duration,
 * not a prediction available during the work.
 *
 * <p><b>Implementation commits</b>: the issue's linked commits by a human author (not a bot
 * commit, {@link World.Commit#bot}) visible before t1, those before t0 included; nothing visible
 * at or after t1. Without one, the five content attributes are missing, never zero.
 *
 * <ol>
 *   <li>{@link #LINES}: the sum over those commits of their Java lines added and removed, each
 *       commit's split evenly among the issues it cites; 0 when they changed no Java.
 *   <li>{@link #MCCC} and {@link #NESTING}: the mean cyclomatic complexity and nesting of the
 *       methods those commits changed, by the funnel's rule for which methods a commit changed,
 *       each method once at its latest state ({@link MethodDeduplication#byLatestChange}); no
 *       single-author requirement. Missing when no method was recovered.
 *   <li>{@link #FILES}: the distinct files of any type those commits touched; missing when the
 *       corpus records none, as the registered {@code D07} is.
 *   <li>{@link #FAMILIARITY}: the share of those files that at least one of those commits'
 *       authors had touched in the issue's project in a commit visible strictly before t0; no
 *       commit linked to the issue counts in that history.
 * </ol>
 *
 * <p><b>Discussion and coordination</b>, in {@code [t0, t1)}, the bot accounts ({@link
 * DayBoundaries#bots}) left out of all three:
 *
 * <ol start="6">
 *   <li>{@link #COMMENTS}: the comments.
 *   <li>{@link #PARTICIPANTS}: the distinct canonical people who commented or changed any field;
 *       the reporter only when active; the resolution's own action, at t1, outside the window;
 *       commit authors not counted.
 *   <li>{@link #ASSIGNEE_CHANGES}: the changes of the assignee whose old and new values differ
 *       once normalised ({@link Initial#norm}): nobody to a person, a person to nobody and a
 *       person to another alike.
 * </ol>
 *
 * <p>Separate from the registered columns of {@link Extractor}: {@code A15}, {@code A16}, {@code
 * A17}, {@code D07}, {@code D08}, {@code K03}, {@code K04} and {@code K05} keep their meaning in the
 * registered matrix, and these are new columns under new names.
 */
public final class Retrospective {

  public static final String LINES = "RETRO_java_lines";
  public static final String MCCC = "RETRO_mean_mccc";
  public static final String NESTING = "RETRO_mean_nesting";
  public static final String FILES = "RETRO_files";
  public static final String FAMILIARITY = "RETRO_familiarity";
  public static final String COMMENTS = "RETRO_comments";
  public static final String PARTICIPANTS = "RETRO_participants";
  public static final String ASSIGNEE_CHANGES = "RETRO_assignee_changes";

  /** The five read from the implementation commits, missing together without one. */
  public static final List<String> CONTENT = List.of(LINES, MCCC, NESTING, FILES, FAMILIARITY);

  /** The eight, in the ticket's order. */
  public static final List<String> COLUMNS = List.of(LINES, MCCC, NESTING, FILES, FAMILIARITY,
      COMMENTS, PARTICIPANTS, ASSIGNEE_CHANGES);

  /** The counts and volumes, which enter as {@code log1p}; the ratio and means enter raw. */
  public static final Set<String> COUNTS =
      Set.of(LINES, FILES, COMMENTS, PARTICIPANTS, ASSIGNEE_CHANGES);

  /** The ones that can be missing, each with its missing indicator. */
  public static final Set<String> CAN_BE_MISSING = Set.copyOf(CONTENT);

  /** What the scenario is, for the help text and the run records. */
  public static final String SCENARIO = "retrospective: eight attributes observed between the "
      + "first In Progress and the first resolution (implementation content, discussion and "
      + "coordination); the counts grow with the duration itself, so a gain is association with "
      + "the duration observed up to the resolution, not a prediction available during the work, "
      + "nor causation";

  private final World world;
  private final Universe universe;
  private final Set<String> bots;
  private final Map<String, List<World.Commit>> byAuthor = new HashMap<>();
  private final Map<String, Map<Integer, List<World.Commit>>> touches = new HashMap<>();

  /**
   * @param bots the raw accounts left out of the comments, participants and assignee changes
   */
  public Retrospective(final World world, final Set<String> bots) {
    this.world = world;
    this.universe = world.universe;
    this.bots = Set.copyOf(bots);
    for (final World.Commit c : world.commits()) {
      if (c.who() != null) {
        byAuthor.computeIfAbsent(c.who() + "\u0000" + c.project(), k -> new ArrayList<>()).add(c);
      }
    }
  }

  /**
   * The issue's implementation commits: linked, by a human author, visible before {@code t1}; in
   * authoring order, the revision hash breaking ties.
   */
  public List<World.Commit> implementation(final String issue, final long t1) {
    final List<World.Commit> out = new ArrayList<>();
    for (final World.Commit c : world.linked(issue)) {
      if (c.visible() < t1 && c.who() != null && !c.bot()) {
        out.add(c);
      }
    }
    out.sort(Comparator.comparingLong(World.Commit::authoredAt).thenComparing(World.Commit::hash));
    return out;
  }

  /**
   * The eight attributes of one In-Progress unit, a missing one as null.
   *
   * @param methods per commit id, the methods it changed; a commit absent or empty changed none
   *     recovered
   */
  public Map<String, Double> row(final Universe.Unit u,
      final Map<String, Map<String, ChangedMethod>> methods) {
    final long t0 = u.start();
    final long t1 = u.resolved();
    final Map<String, Double> v = new LinkedHashMap<>();
    content(u, t0, t1, methods, v);
    discussion(u, t0, t1, v);
    return v;
  }

  private void content(final Universe.Unit u, final long t0, final long t1,
      final Map<String, Map<String, ChangedMethod>> methods, final Map<String, Double> v) {
    final List<World.Commit> commits = implementation(u.id(), t1);
    if (commits.isEmpty()) {
      CONTENT.forEach(column -> v.put(column, null));
      return;
    }
    double lines = 0;
    final Set<Integer> files = new HashSet<>();
    final Set<String> authors = new HashSet<>();
    final List<CommitMethods> changed = new ArrayList<>();
    for (final World.Commit c : commits) {
      lines += c.loc() / (double) Math.max(1, c.linked().size());
      final int[] touched = world.corpus.files().get(c.id());
      if (touched != null) {
        for (final int f : touched) {
          files.add(f);
        }
      }
      authors.add(c.who());
      final Map<String, ChangedMethod> m = methods.getOrDefault(c.id(), Map.of());
      if (!m.isEmpty()) {
        changed.add(new CommitMethods(c.hash(), Instant.ofEpochMilli(c.authoredAt()), m));
      }
    }
    v.put(LINES, lines);
    final List<ChangedMethod> deduplicated = MethodDeduplication.byLatestChange(changed);
    v.put(MCCC, deduplicated.isEmpty() ? null
        : deduplicated.stream().mapToDouble(ChangedMethod::mccc).average().orElseThrow());
    v.put(NESTING, deduplicated.isEmpty() ? null
        : deduplicated.stream().mapToDouble(ChangedMethod::nl).average().orElseThrow());
    if (files.isEmpty()) {
      v.put(FILES, null);
      v.put(FAMILIARITY, null);
      return;
    }
    final Set<String> own = new HashSet<>();
    world.linked(u.id()).forEach(c -> own.add(c.id()));
    final List<String> sortedAuthors = authors.stream().sorted().toList();
    long known = 0;
    for (final int f : files) {
      for (final String who : sortedAuthors) {
        if (touchedBefore(who, u.project(), f, t0, own)) {
          known++;
          break;
        }
      }
    }
    v.put(FILES, (double) files.size());
    v.put(FAMILIARITY, known / (double) files.size());
  }

  /** Whether {@code who} touched file {@code f} of the project before t0 in a commit not own. */
  private boolean touchedBefore(final String who, final String project, final int f,
      final long t0, final Set<String> own) {
    for (final World.Commit c : touches(who, project).getOrDefault(f, List.of())) {
      if (c.visible() >= t0) {
        return false;
      }
      if (!own.contains(c.id())) {
        return true;
      }
    }
    return false;
  }

  /** Per file, the person's commits in the project that touched it, in visibility order. */
  private Map<Integer, List<World.Commit>> touches(final String who, final String project) {
    return touches.computeIfAbsent(who + "\u0000" + project, key -> {
      final Map<Integer, List<World.Commit>> out = new HashMap<>();
      for (final World.Commit c : byAuthor.getOrDefault(key, List.of())) {
        final int[] touched = world.corpus.files().get(c.id());
        if (touched != null) {
          for (final int f : touched) {
            out.computeIfAbsent(f, x -> new ArrayList<>()).add(c);
          }
        }
      }
      out.values().forEach(list -> list.sort(Comparator.comparingLong(World.Commit::visible)
          .thenComparing(World.Commit::id)));
      return out;
    });
  }

  private void discussion(final Universe.Unit u, final long t0, final long t1,
      final Map<String, Double> v) {
    int comments = 0;
    final Set<String> people = new HashSet<>();
    for (final Corpus.Comment c : world.comments(u.id())) {
      if (inside(c.at(), t0, t1) && !bot(c.author())) {
        comments++;
        if (c.author() != null) {
          people.add(world.person(c.author()));
        }
      }
    }
    int assignees = 0;
    for (final Corpus.Event e : universe.events(u.id())) {
      if (!inside(e.at(), t0, t1) || bot(e.author())) {
        continue;
      }
      if (e.author() != null) {
        people.add(world.person(e.author()));
      }
      if ("assignee_id".equals(e.field())
          && !java.util.Objects.equals(Initial.norm(e.oldValue()), Initial.norm(e.newValue()))) {
        assignees++;
      }
    }
    v.put(COMMENTS, (double) comments);
    v.put(PARTICIPANTS, (double) people.size());
    v.put(ASSIGNEE_CHANGES, (double) assignees);
  }

  private boolean bot(final String author) {
    return author != null && bots.contains(author);
  }

  private static boolean inside(final Long at, final long t0, final long t1) {
    return at != null && at >= t0 && at < t1;
  }
}
