package app.issues;

import app.issues.Universe.Part;
import app.issues.Universe.Population;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shape of a drawn split, the sides by issue id ({@link Parts}) and their sizes ({@link
 * Sizes}), and the check of a matrix's verdicts against the sides a rule drew again ({@link
 * #differences}). The rule itself is {@link TemporalSplit#drawRevised}.
 */
public final class ProjectSplit {

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
