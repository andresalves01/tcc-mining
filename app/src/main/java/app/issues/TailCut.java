package app.issues;

import app.issues.model.Fitting;
import app.study.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The study's tail cut (ticket 23, item 50): the issues of extreme
 * duration leave the fitting rows, and the validation is read on the usual flow.
 *
 * <p><b>Fence.</b> {@code Q3 + k * IQR} of the business days ({@link BusinessDays#ACTIVE}, the
 * outcome each unit carries) of the fitting side of the split it serves, and only of it: the inner
 * fitting set in the inner scope, the outer training set in the outer one; never of validation or
 * test labels. The quartiles are those of {@link #quantile}, the only definition this chain uses
 * for them.
 *
 * <p><b>Usual flow.</b> The issues at or below the fence with {@link #USUAL_FLOW_K}, fixed at 3,
 * Tukey's convention for extreme outliers, apart from the fitting k chosen on the grid. Every
 * choice of the selection is scored on the usual-flow validation issues; in the scoring, the
 * scored issues of the usual flow are the main reading and every scored issue, all issues, the
 * complementary one.
 *
 * <p><b>Fitting cut.</b> The fitting rows above the fence with the chosen k leave every model's
 * and forest's fitting rows and the references' (global, project and executor-project medians).
 * The k is chosen once, on the own specification with similarity, among {@link #GRID}. The
 * histories (similarity medians, clusters, reporter median, Blocker pace) are not cut: they are
 * columns the similarity stage wrote from every training-side issue, and no k changes them.
 */
public final class TailCut {

  /** The usual flow's k: Tukey's extreme outliers, fixed. */
  public static final double USUAL_FLOW_K = 3.0;

  /** The k of no cut: an infinite fence, every fitting row kept. */
  public static final double NO_CUT = Double.POSITIVE_INFINITY;

  /** The fitting cut's candidate k, ascending; the last is no cut. */
  public static final List<Double> GRID = List.of(1.5, 3.0, 5.0, 10.0, NO_CUT);

  /** How the quartiles are taken, for the report and the run record. */
  public static final String QUANTILES = "quartiles by linear interpolation between order "
      + "statistics at position (n - 1) p of the sorted business days (Hyndman and Fan's type 7, "
      + "the default of R and numpy)";

  /** The fence and the grid, in words, for the report and the run record. */
  public static final String RULE = "fence Q3 + k IQR of the business days of the fitting side "
      + "of the split (inner: the inner fitting set; outer: the outer training set), never of "
      + "validation or test labels; the fitting rows above the chosen k's fence leave every "
      + "model's, forest's and reference's fitting rows, the histories keep them; k chosen once "
      + "on the own start model (own_with_similarity_before_pressure) among 1.5, 3, 5, 10 and no "
      + "cut by its error on the usual-flow "
      + "validation issues, ties to the larger k; usual flow: the issues at or below the k = 3 "
      + "fence; every choice of the selection (alpha, lambda, the medians' n and k, each forest's "
      + "point, k) is scored on the usual-flow validation issues";

  private static final String NONE = "none";

  /**
   * Which validation or test issues are read (ticket 23, item 50 i): what the selection scores
   * every choice on, and what each reading of the scoring holds. The fitting is the same in both.
   */
  public enum Reading {
    /**
     * The usual flow, the main reading: the scored issues at or below the fence at {@link
     * #USUAL_FLOW_K} of the fitting side. Every choice of the selection is scored on it.
     */
    USUAL_FLOW("usual flow (main reading): the scored issues at or below the k = 3 fence"),
    /**
     * All issues, the complementary reading: every scored issue, the tail included; what the
     * selection scored before ticket 23.
     */
    ALL("all issues (complementary reading): every scored issue, those above the k = 3 fence "
        + "included");

    private final String description;

    Reading(final String description) {
      this.description = description;
    }

    /** What the reading holds, in words, for the report. */
    public String description() {
      return description;
    }
  }

  /**
   * One k of the grid, swept.
   *
   * @param fence its fence, in business days; infinite for no cut
   * @param fitRows the fitting rows at or below it
   * @param mae the own specification with similarity, chosen as usual on those rows, its error on
   *     the usual-flow validation issues, in business days
   */
  public record Trial(double k, double fence, int fitRows, double mae) {}

  /**
   * The cut chosen.
   *
   * @param k the chosen k, one of {@link #GRID}
   * @param fence the fitting fence at {@code k}, in business days
   * @param usualFence the usual flow's fence, at {@link #USUAL_FLOW_K}, in business days
   * @param grid every k swept, in the grid's order
   */
  public record Choice(double k, double fence, double usualFence, List<Trial> grid) {}

  private TailCut() {}

  /**
   * The value at {@code p} of {@code sorted}, ascending: linear interpolation between the order
   * statistics around position {@code (n - 1) p}.
   */
  static double quantile(final double[] sorted, final double p) {
    final double position = (sorted.length - 1) * p;
    final int low = (int) Math.floor(position);
    final int high = Math.min(low + 1, sorted.length - 1);
    return sorted[low] + (sorted[high] - sorted[low]) * (position - low);
  }

  /**
   * The first quartile, the median and the third quartile of {@code values}, by {@link #quantile}:
   * the limits of the error bands and of the prediction quartiles (ticket 24).
   */
  public static double[] quartiles(final double[] values) {
    if (values.length == 0) {
      throw new IllegalArgumentException("no value to take quartiles of");
    }
    final double[] sorted = values.clone();
    Arrays.sort(sorted);
    return new double[] {quantile(sorted, 0.25), quantile(sorted, 0.5), quantile(sorted, 0.75)};
  }

  /**
   * {@code Q3 + k * IQR} of the units' business days; infinite for {@link #NO_CUT}. The units are
   * the fitting side the fence serves, and nothing else.
   */
  public static double fence(final List<IssueUnit> fitting, final double k) {
    if (fitting.isEmpty()) {
      throw new IllegalArgumentException("no fitting issue to take a fence from");
    }
    if (k == NO_CUT) {
      return NO_CUT;
    }
    final double[] days = fitting.stream().mapToDouble(IssueUnit::duration).sorted().toArray();
    final double q1 = quantile(days, 0.25);
    final double q3 = quantile(days, 0.75);
    return q3 + k * (q3 - q1);
  }

  /**
   * Which of the trials, in the grid's order, wins: the lowest error, read from the larger k down
   * and kept only when strictly lower, the sweep's own rule, so of errors exactly equal the larger
   * k, the smaller cut, wins. No tolerance.
   */
  public static int chosen(final List<Trial> trials) {
    int best = -1;
    for (int t = trials.size() - 1; t >= 0; t--) {
      if (best == -1 || trials.get(t).mae() < trials.get(best).mae()) {
        best = t;
      }
    }
    return best;
  }

  /** The sides without the fitting rows above {@code fence}; the scored side as it is. */
  public static Fitting.Sides cut(final Fitting.Sides sides, final double fence) {
    if (fence == NO_CUT) {
      return sides;
    }
    final List<IssueUnit> units = new ArrayList<>();
    final List<Row> rows = new ArrayList<>();
    for (int i = 0; i < sides.fitUnits().size(); i++) {
      if (sides.fitUnits().get(i).duration() <= fence) {
        units.add(sides.fitUnits().get(i));
        rows.add(sides.fitRows().get(i));
      }
    }
    return new Fitting.Sides(List.copyOf(units), List.copyOf(rows), sides.scoreUnits(),
        sides.scoreRows(), sides.observed());
  }

  /** The sides with only the scored rows at or below {@code fence}; the fitting side as it is. */
  public static Fitting.Sides usualFlow(final Fitting.Sides sides, final double fence) {
    final boolean[] within = within(sides.observed(), fence);
    final List<IssueUnit> units = new ArrayList<>();
    final List<Row> rows = new ArrayList<>();
    for (int i = 0; i < within.length; i++) {
      if (within[i]) {
        units.add(sides.scoreUnits().get(i));
        rows.add(sides.scoreRows().get(i));
      }
    }
    final double[] observed = units.stream().mapToDouble(IssueUnit::duration).toArray();
    return new Fitting.Sides(sides.fitUnits(), sides.fitRows(), List.copyOf(units),
        List.copyOf(rows), observed);
  }

  /** Per observed duration, whether it is at or below {@code fence}. */
  public static boolean[] within(final double[] observed, final double fence) {
    final boolean[] out = new boolean[observed.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = observed[i] <= fence;
    }
    return out;
  }

  /** The mean absolute error over the issues observed at or below {@code fence}. */
  public static double mae(final double[] predicted, final double[] observed, final double fence) {
    double sum = 0;
    int n = 0;
    for (int i = 0; i < observed.length; i++) {
      if (observed[i] <= fence) {
        sum += Math.abs(predicted[i] - observed[i]);
        n++;
      }
    }
    if (n == 0) {
      throw new IllegalArgumentException("no scored issue at or below the fence " + fence);
    }
    return sum / n;
  }

  /** A k as the parameters file writes it: {@code none} for no cut, else as Java prints it. */
  public static String label(final double k) {
    return k == NO_CUT ? NONE : Double.toString(k);
  }

  /** A k as {@link #label} wrote it; a value off the grid refuses. */
  public static double parse(final String label) {
    final double k = NONE.equals(label) ? NO_CUT : number(label);
    if (!GRID.contains(k) || !label.equals(label(k))) {
      throw new IllegalArgumentException("k " + label + " is not one of the grid "
          + GRID.stream().map(TailCut::label).toList());
    }
    return k;
  }

  private static double number(final String label) {
    try {
      return Double.parseDouble(label);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("k " + label + " is not a number", e);
    }
  }

  /** The grid as the parameters file and the run record write it. */
  public static List<String> labels() {
    return GRID.stream().map(TailCut::label).toList();
  }
}
