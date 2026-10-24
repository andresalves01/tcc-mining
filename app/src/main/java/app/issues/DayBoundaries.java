package app.issues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Each issue's day boundary (ticket 14): the UTC hour at which one of its working dates ends and
 * the next begins, the cut {@link BusinessDays} counts dates at. Declared, not tuned: the rule the
 * business-day prototype chose on the inner fitting rows.
 *
 * <p><b>History.</b> Tracker activity only -- every changelog event (status changes, assignments,
 * any other change) and every comment -- on any issue of the corpus's home, unique issues, read
 * strictly before the issue's first In Progress. One unit is one person active in one clock hour,
 * counted once however many actions it holds, at the instant of its first action; it counts for
 * the project of the issue that first action was on. {@code ProjectImport} events, entries with no
 * author and bot accounts ({@link #bots}) are dropped. No commit, no commit time zone offset and no
 * outcome of any issue is read.
 *
 * <p><b>Rule.</b> The middle of the clicker's quietest {@value #WINDOW_HOURS}-hour window of past
 * active hours, if they have at least {@value #MIN_PERSON_HOURS}; else the start of the project's
 * quietest {@value #WINDOW_HOURS}-hour window of past person-hours, if it has at least {@value
 * #MIN_PROJECT_HOURS}; else {@value #FALLBACK_HOUR}:00 UTC. A window is circular over the 24 hours
 * of the day; of equally quiet windows the one whose boundary is closest to {@value
 * #FALLBACK_HOUR}:00 wins, then the earlier hour.
 */
public final class DayBoundaries {

  /** The hour every boundary falls back to, and the one ties lean towards. */
  public static final int FALLBACK_HOUR = 4;

  /** How many hours a quiet window spans. */
  public static final int WINDOW_HOURS = 8;

  /** The active hours a clicker needs before the click for their own rule. */
  public static final int MIN_PERSON_HOURS = 10;

  /** The person-hours a project needs before the click for its rule. */
  public static final int MIN_PROJECT_HOURS = 30;

  /** A bot account has at least this many comments ... */
  public static final int BOT_MIN_COMMENTS = 20;

  /** ... and at least this share of them opening like a bot's template. */
  public static final double BOT_TEMPLATED_SHARE = 0.25;

  /** The bot rule in words, as the run records of the stages that leave bots out state it. */
  public static final String BOT_RULE = "at least " + BOT_MIN_COMMENTS + " comments, at least "
      + BOT_TEMPLATED_SHARE + " of them opening like a bot's template "
      + "(Jira color markup allowed before patch-test overall scores)";

  /**
   * How a bot's comment opens: GitHub mirroring, commit notices, build reports, patch-test reports.
   * Read on the text with its tabs and line breaks turned into spaces, from its first character.
   * Patch-test scores may start with Jira's color markup, as the Tez CI reports do.
   */
  private static final Pattern TEMPLATE = Pattern.compile("^(GitHub user|Commit [0-9a-f]+ (from|in)"
      + "|((SUCCESS|FAILURE|UNSTABLE|ABORTED|FIXED|ERROR): )?Integrated in"
      + "|(?:\\{color:[^}]+\\})?[-+]1 overall"
      + "|Testing JIRA)", Pattern.CASE_INSENSITIVE);

  private static final Pattern BREAKS = Pattern.compile("[\\t\\r\\n]+");

  private static final long HOUR = 3_600_000L;

  /** Which rule gave a boundary. */
  public enum Source { PERSON, PROJECT, FALLBACK }

  /**
   * One issue's boundary.
   *
   * @param personHours the clicker's active hours before the click
   * @param projectHours the project's person-hours before the click
   */
  public record Boundary(int hour, Source source, int personHours, int projectHours) {}

  /**
   * One tracker action, already kept.
   *
   * @param person the canonical person who acted
   * @param project the project of the issue acted on
   */
  public record Action(long at, String person, String issue, String project) {}

  private final Map<String, Hours> people;
  private final Map<String, Hours> projects;
  private final Set<String> bots;

  private DayBoundaries(final Map<String, Hours> people, final Map<String, Hours> projects,
      final Set<String> bots) {
    this.people = people;
    this.projects = projects;
    this.bots = bots;
  }

  /** The raw accounts left out as bots; empty when the actions were given already kept. */
  public Set<String> bots() {
    return bots;
  }

  /** How much of a comment's opening is read for a template, far beyond its longest pattern. */
  private static final int OPENING = 240;

  /** Whether a comment's text opens like a bot's template. */
  public static boolean templated(final String text) {
    if (text == null) {
      return false;
    }
    final String opening = text.substring(0, Math.min(text.length(), OPENING));
    return TEMPLATE.matcher(BREAKS.matcher(opening).replaceAll(" ")).lookingAt();
  }

  /**
   * The raw accounts with at least {@value #BOT_MIN_COMMENTS} comments on {@code issues}, at least
   * a quarter of them templated.
   */
  public static Set<String> bots(final List<Corpus.Comment> comments, final Set<String> issues) {
    final Map<String, int[]> tally = new HashMap<>();
    for (final Corpus.Comment c : comments) {
      if (c.author() == null || !issues.contains(c.issue())) {
        continue;
      }
      final int[] t = tally.computeIfAbsent(c.author(), k -> new int[2]);
      t[0]++;
      t[1] += c.templated() ? 1 : 0;
    }
    final Set<String> out = new HashSet<>();
    tally.forEach((author, t) -> {
      if (t[0] >= BOT_MIN_COMMENTS && t[1] >= BOT_TEMPLATED_SHARE * t[0]) {
        out.add(author);
      }
    });
    return out;
  }

  static List<Action> actions(final List<Corpus.Event> events,
      final List<Corpus.Comment> comments, final Map<String, String> projectOfIssue,
      final Map<String, Corpus.Person> people, final Set<String> bots) {
    final List<Action> out = new ArrayList<>();
    for (final Corpus.Event e : events) {
      if (!"ProjectImport".equals(e.field())) {
        add(out, e.issue(), e.author(), e.at(), projectOfIssue, people, bots);
      }
    }
    for (final Corpus.Comment c : comments) {
      add(out, c.issue(), c.author(), c.at(), projectOfIssue, people, bots);
    }
    out.sort(Comparator.comparingLong(Action::at).thenComparing(Action::person)
        .thenComparing(Action::issue));
    return out;
  }

  private static void add(final List<Action> out, final String issue, final String author,
      final Long at, final Map<String, String> projectOfIssue,
      final Map<String, Corpus.Person> people, final Set<String> bots) {
    final String project = projectOfIssue.get(issue);
    if (project == null || author == null || at == null || bots.contains(author)) {
      return;
    }
    out.add(new Action(at, World.person(author, people), issue, project));
  }

  /**
   * The boundaries of the corpus's home, unique issues ({@link Universe#universe}), their actions
   * read by {@link #actions}.
   */
  public static DayBoundaries of(final Corpus corpus, final Universe universe) {
    final Map<String, String> projectOfIssue = new HashMap<>();
    for (final String id : universe.universe()) {
      projectOfIssue.put(id, universe.issue(id).project());
    }
    final Set<String> bots = Set.copyOf(bots(corpus.comments(), projectOfIssue.keySet()));
    return of(actions(corpus.events(), corpus.comments(), projectOfIssue, corpus.people(), bots),
        bots);
  }

  static DayBoundaries of(final List<Action> actions, final Set<String> bots) {
    // one unit per person per clock hour, at its first action, for that action's project
    final Map<String, Map<Long, Action>> first = new HashMap<>();
    final Comparator<Action> earlier = Comparator.comparingLong(Action::at)
        .thenComparing(Action::issue);
    for (final Action a : actions) {
      first.computeIfAbsent(a.person(), k -> new HashMap<>())
          .merge(Math.floorDiv(a.at(), HOUR), a, (x, y) -> earlier.compare(x, y) <= 0 ? x : y);
    }
    final Map<String, List<Action>> byPerson = new HashMap<>();
    final Map<String, List<Action>> byProject = new HashMap<>();
    first.forEach((person, slots) -> slots.values().forEach(a -> {
      byPerson.computeIfAbsent(person, k -> new ArrayList<>()).add(a);
      byProject.computeIfAbsent(a.project(), k -> new ArrayList<>()).add(a);
    }));
    final Map<String, Hours> people = new HashMap<>();
    byPerson.forEach((k, v) -> people.put(k, new Hours(v)));
    final Map<String, Hours> projects = new HashMap<>();
    byProject.forEach((k, v) -> projects.put(k, new Hours(v)));
    return new DayBoundaries(people, projects, bots);
  }

  /** The boundary of an issue clicked by {@code clicker}, null when unknown, in {@code project}. */
  public Boundary at(final String clicker, final String project, final long click) {
    final int[] mine = counts(clicker == null ? null : people.get(clicker), click);
    final int[] theirs = counts(projects.get(project), click);
    final int personHours = Arrays.stream(mine).sum();
    final int projectHours = Arrays.stream(theirs).sum();
    if (personHours >= MIN_PERSON_HOURS) {
      return new Boundary(quietest(mine, WINDOW_HOURS / 2), Source.PERSON, personHours,
          projectHours);
    }
    if (projectHours >= MIN_PROJECT_HOURS) {
      return new Boundary(quietest(theirs, 0), Source.PROJECT, personHours, projectHours);
    }
    return new Boundary(FALLBACK_HOUR, Source.FALLBACK, personHours, projectHours);
  }

  private static int[] counts(final Hours hours, final long click) {
    return hours == null ? new int[24] : hours.before(click);
  }

  /**
   * The boundary of the quietest circular window of {@link #WINDOW_HOURS} hours, {@code offset}
   * hours after its start; ties to the boundary closest to {@link #FALLBACK_HOUR}, then the
   * earlier.
   */
  static int quietest(final int[] counts, final int offset) {
    int best = -1;
    long bestTotal = Long.MAX_VALUE;
    int bestDistance = Integer.MAX_VALUE;
    for (int s = 0; s < 24; s++) {
      long total = 0;
      for (int k = 0; k < WINDOW_HOURS; k++) {
        total += counts[(s + k) % 24];
      }
      final int b = (s + offset) % 24;
      final int d = Math.min(Math.abs(b - FALLBACK_HOUR), 24 - Math.abs(b - FALLBACK_HOUR));
      if (total < bestTotal || total == bestTotal
          && (d < bestDistance || d == bestDistance && b < best)) {
        best = b;
        bestTotal = total;
        bestDistance = d;
      }
    }
    return best;
  }

  /**
   * The hours of the day of a set of units, counted before any instant: the units' instants
   * sorted, with running counts kept every {@link #STRIDE} units.
   */
  private static final class Hours {

    private static final int STRIDE = 64;

    private final long[] times;
    private final byte[] hours;
    private final int[][] checkpoints;

    Hours(final List<Action> units) {
      final List<Action> sorted = new ArrayList<>(units);
      sorted.sort(Comparator.comparingLong(Action::at));
      times = new long[sorted.size()];
      hours = new byte[sorted.size()];
      checkpoints = new int[sorted.size() / STRIDE + 1][];
      final int[] running = new int[24];
      for (int i = 0; i < times.length; i++) {
        if (i % STRIDE == 0) {
          checkpoints[i / STRIDE] = running.clone();
        }
        times[i] = sorted.get(i).at();
        hours[i] = (byte) Math.floorMod(Math.floorDiv(times[i], HOUR), 24);
        running[hours[i]]++;
      }
      if (times.length % STRIDE == 0) {
        checkpoints[times.length / STRIDE] = running.clone();
      }
    }

    /** Counts by hour of the day of the units strictly before {@code t}. */
    int[] before(final long t) {
      int lo = 0;
      int hi = times.length;
      while (lo < hi) {
        final int mid = (lo + hi) >>> 1;
        if (times[mid] < t) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      final int[] out = checkpoints[lo / STRIDE].clone();
      for (int i = lo - lo % STRIDE; i < lo; i++) {
        out[hours[i]]++;
      }
      return out;
    }
  }
}
