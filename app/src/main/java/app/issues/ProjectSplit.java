package app.issues;

import app.issues.Universe.Part;
import app.issues.Universe.Population;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * The study's split (ticket 21, item 44 of the retrospective decisions): random, stratified by
 * project, in place of the temporal cut it replaced, which compared different units -- an issue
 * entered a part only once started and resolved before that part's cut, so the inner validation
 * could last at most about sixteen months while the fitting set spanned twelve years.
 *
 * <p><b>Rule.</b> Per project, in order of project name: its issues ordered by id, shuffled with
 * one {@link Random} of seed {@link #SEED} shared across the projects ({@link
 * Collections#shuffle(List, Random)}); the first {@code round(0.7 n)} are training, a half
 * rounded up ({@link #TENTHS}), the rest test.
 * The inner split is the same rule on the training side, with the same {@link Random} continuing
 * after the outer draw: the inner fitting set, then the inner validation. A project of one issue
 * goes wholly to training, and to the inner fitting set. No issue is set aside: nothing crosses.
 *
 * <p><b>Population.</b> The issues of at least one active business day ({@link
 * BusinessDays#active}), the target's own rule, which the extraction counts before drawing; the
 * issues of 0 are never drawn.
 *
 */
public final class ProjectSplit {

  /** The seed of the one {@link Random} the outer and the inner draws read in turn. */
  public static final long SEED = 1738L;

  /**
   * The share of each project that goes to training, and of its training to inner fitting, in
   * tenths: {@code round(0.7 n)} is taken as {@code (7 n + 5) / 10} in whole numbers, a half
   * rounded up, so no floating-point product decides a project of 5, 15 or 25 issues.
   */
  public static final int TENTHS = 7;

  /** The same share as a fraction, for the run records. */
  public static final double SHARE = TENTHS / 10.0;

  /** The rule in words, for the help text and the run records. */
  public static final String RULE = "random, stratified by project: per project in order of "
      + "name, its issues ordered by id and shuffled with one java.util.Random(" + SEED
      + ") shared across projects, the first round(" + SHARE + " n) training, a half rounded "
      + "up, and the rest test; the same rule on the training side, the same Random continuing, "
      + "gives the inner fitting and the inner validation; a project of one issue goes wholly to "
      + "training; no issue is set aside";

  /**
   * Outer and inner sides by issue id, and the issues on a training side that cross its cut.
   *
   * <p>The verdicts a matrix's split columns hold are read from here ({@link #outerVerdict},
   * {@link #innerVerdict}): the side's name, or {@link Verdict#CROSSING} for an issue of a
   * crossing set. Every split before the revised design has no crossing set, so its verdicts are
   * its sides' names, as always.
   *
   * @param outer every issue drawn, TRAIN or TEST
   * @param inner the outer training issues only, TRAIN (inner fitting) or TEST (inner validation)
   * @param outerCrossing outer training issues whose label was not known at the outer cut
   * @param innerCrossing inner fitting issues whose label was not known at the inner cut; it holds
   *     every inner fitting issue of {@code outerCrossing}, since the inner cut comes first
   */
  public record Parts(Map<String, Part> outer, Map<String, Part> inner,
      Set<String> outerCrossing, Set<String> innerCrossing) {

    /** The sides with no issue crossing a cut, as every split before the revised design. */
    public Parts(final Map<String, Part> outer, final Map<String, Part> inner) {
      this(outer, inner, Set.of(), Set.of());
    }

    /**
     * Refuses a crossing issue off its training side, and an inner fitting issue that crosses the
     * outer cut but not the inner one, which would teach the inner level a label it crosses.
     */
    public Parts {
      outerCrossing = Set.copyOf(outerCrossing);
      innerCrossing = Set.copyOf(innerCrossing);
      for (final String id : outerCrossing) {
        if (outer.get(id) != Part.TRAIN) {
          throw new IllegalArgumentException(id + " crosses the outer cut off the outer training");
        }
        if (inner.get(id) == Part.TRAIN && !innerCrossing.contains(id)) {
          throw new IllegalArgumentException(id + " crosses the outer cut and not the inner one, "
              + "which comes before it");
        }
      }
      for (final String id : innerCrossing) {
        if (inner.get(id) != Part.TRAIN) {
          throw new IllegalArgumentException(id + " crosses the inner cut off the inner fitting");
        }
      }
    }

    /** The outer verdict of issue {@code id}: its outer side, or CROSSING. */
    public Verdict outerVerdict(final String id) {
      final Part p = outer.get(id);
      if (p == null) {
        throw new IllegalArgumentException(id + " was not drawn");
      }
      return p == Part.TEST ? Verdict.TEST
          : outerCrossing.contains(id) ? Verdict.CROSSING : Verdict.TRAIN;
    }

    /** The inner verdict of issue {@code id}: its inner side, or CROSSING; null on the outer test. */
    public Verdict innerVerdict(final String id) {
      final Part p = inner.get(id);
      if (p == null) {
        return null;
      }
      return p == Part.TEST ? Verdict.TEST
          : innerCrossing.contains(id) ? Verdict.CROSSING : Verdict.TRAIN;
    }
  }

  /** The rows on each side: outer training, outer test, inner fitting and inner validation. */
  public record Sizes(int train, int test, int innerFit, int innerSelection) {}

  private ProjectSplit() {}

  /** The verdicts of the issues of {@code projects}, each id mapped to its project. */
  public static Parts draw(final Map<String, String> projects) {
    final Map<String, List<String>> byProject = new TreeMap<>();
    projects.forEach((id, p) -> byProject.computeIfAbsent(p, k -> new ArrayList<>()).add(id));
    final Random random = new Random(SEED);
    final Map<String, Part> outer = new HashMap<>(projects.size() * 2);
    final Map<String, List<String>> training = new TreeMap<>();
    byProject.forEach((p, ids) -> training.put(p, draw(ids, random, outer)));
    final Map<String, Part> inner = new HashMap<>(projects.size() * 2);
    training.values().forEach(ids -> draw(ids, random, inner));
    return new Parts(Map.copyOf(outer), Map.copyOf(inner));
  }

  /** One project's draw into {@code out}: its ids sorted, shuffled, cut; the training ids. */
  private static List<String> draw(final List<String> ids, final Random random,
      final Map<String, Part> out) {
    final List<String> order = new ArrayList<>(ids);
    Collections.sort(order);
    Collections.shuffle(order, random);
    final int train = (TENTHS * order.size() + 5) / 10;
    for (int i = 0; i < order.size(); i++) {
      out.put(order.get(i), i < train ? Part.TRAIN : Part.TEST);
    }
    return List.copyOf(order.subList(0, train));
  }

  /**
   * Where the matrix's verdicts are not the rule's, drawn again over the matrix's own issues; empty
   * when every one is. Reads each row's id, project and verdicts, nothing else: no value and no
   * outcome, the outer test's included. A matrix of the temporal cut fails here on its crossing
   * rows. A matrix missing issues of the population is drawn again over the issues it holds, so it
   * fails only where that draw moves a verdict, which is likely but not certain: this check does
   * not prove the matrix holds the whole population.
   */
  public static List<String> differences(final List<RowCache.Entry> entries) {
    return differences(entries, Population.IN_PROGRESS);
  }

  /** {@link #differences(List)} for the verdicts of {@code population}'s split columns. */
  public static List<String> differences(final List<RowCache.Entry> entries,
      final Population population) {
    final Map<String, String> projects = new HashMap<>(entries.size() * 2);
    entries.forEach(e -> projects.put(e.unit().issueId(), e.unit().project()));
    return differences(entries, population, draw(projects), "seed " + SEED);
  }

  /**
   * Where the matrix's verdicts under {@code population} are not {@code parts}, the sides a rule
   * drew again over the matrix's own issues; {@code rule} names that rule in the message.
   */
  static List<String> differences(final List<RowCache.Entry> entries,
      final Population population, final Parts parts, final String rule) {
    int wrong = 0;
    String first = null;
    for (final RowCache.Entry e : entries) {
      final String id = e.unit().issueId();
      final String outer = parts.outerVerdict(id).name();
      final String inner = Verdict.text(parts.innerVerdict(id));
      if (!outer.equals(e.outer(population)) || !inner.equals(e.inner(population))) {
        wrong++;
        if (first == null) {
          first = id + " (" + e.outer(population) + "/" + e.inner(population) + ", the rule gives "
              + outer + "/" + inner + ")";
        }
      }
    }
    return wrong == 0 ? List.of() : List.of(String.format("%,d of %,d rows are not at the "
        + "verdicts of the split's rule (%s), the first %s; the matrix was not extracted with "
        + "it", wrong, entries.size(), rule, first));
  }
}
