package app.issues;

import app.issues.model.Fitting;
import app.study.Row;
import java.util.ArrayList;
import java.util.List;

/**
 * EXPLORATORY (the first-commit study's ticket 06): the <b>analogy estimate</b>, one similarity
 * median used directly as an issue's estimate, with no model around it, in the manner of
 * estimation by analogy (Shepperd and Schofield, 1997). An issue without one -- no neighbours, as
 * when its description at the start is empty -- takes a fallback, the global median. Outside the
 * fourteen declared models; the declared rule that a similarity median is read only inside a
 * model still holds for them.
 *
 * <p>Also the two accuracy measures that paper reports, beside the study's own, both on the
 * magnitude of relative error (MRE), {@code |observed - estimate| / observed}: its mean, {@link
 * #mmre}, and the share at or below 0.25, {@link #pred25}. The MRE is divided by the observed; the
 * study's relative error is signed and divided by the estimate, and is not this.
 */
public final class AnalogyEstimate {

  /**
   * The similarity medians of the matrix, without their split suffix, in the order a tie is
   * broken: Jaccard, embedding, cluster, the smaller size first.
   */
  public static final List<String> CANDIDATES = List.of("jaccard_median_5", "jaccard_median_10",
      "jaccard_median_20", "embedding_median_5", "embedding_median_10", "embedding_median_20",
      "cluster_median_8", "cluster_median_16", "cluster_median_32");

  /** One candidate's estimate per row, whether each row took the fallback, and how many did. */
  public record Estimate(double[] values, boolean[] fellBack, int fallbacks) {}

  /** One candidate read where the choice is made: its MAE and its fallbacks there. */
  public record Candidate(String name, double mae, int fallbacks) {}

  /** The chosen candidate, and every candidate in {@link #CANDIDATES}' order. */
  public record Choice(String chosen, List<Candidate> candidates) {}

  private AnalogyEstimate() {}

  /** Per row, the value of {@code column}, or {@code fallback} where the row has none. */
  public static Estimate estimate(final List<Row> rows, final String column,
      final double fallback) {
    final double[] out = new double[rows.size()];
    final boolean[] fellBack = new boolean[rows.size()];
    int fallbacks = 0;
    for (int i = 0; i < out.length; i++) {
      final Double v = rows.get(i).values().get(column);
      if (v == null || v.isNaN()) {
        out[i] = fallback;
        fellBack[i] = true;
        fallbacks++;
      } else {
        out[i] = v;
      }
    }
    return new Estimate(out, fellBack, fallbacks);
  }

  /**
   * The candidate with the lowest MAE on {@code rows}, each read in its column of {@code variant}
   * and the fallback where it has none; a tie goes to the first in {@link #CANDIDATES}.
   */
  public static Choice choose(final List<Row> rows, final Augmentation.Variant variant,
      final double fallback,
      final double[] observed) {
    if (rows.size() != observed.length || rows.isEmpty()) {
      throw new IllegalArgumentException("one observed duration per row, and at least one row");
    }
    final List<Candidate> candidates = new ArrayList<>();
    Candidate best = null;
    for (final String name : CANDIDATES) {
      final Estimate e = estimate(rows, name + variant.suffix(), fallback);
      final Candidate c = new Candidate(name, Fitting.mae(e.values(), observed), e.fallbacks());
      candidates.add(c);
      if (best == null || c.mae() < best.mae()) {
        best = c;
      }
    }
    return new Choice(best.name(), List.copyOf(candidates));
  }

  /** The MMRE: the mean of {@code |observed - estimate| / observed}. */
  public static double mmre(final double[] estimate, final double[] observed) {
    check(estimate, observed);
    double sum = 0;
    for (int i = 0; i < observed.length; i++) {
      sum += magnitude(estimate[i], observed[i]);
    }
    return sum / observed.length;
  }

  /** Pred(25): the share of rows with {@code |observed - estimate| / observed} at or below 0.25. */
  public static double pred25(final double[] estimate, final double[] observed) {
    check(estimate, observed);
    int within = 0;
    for (int i = 0; i < observed.length; i++) {
      if (magnitude(estimate[i], observed[i]) <= 0.25) {
        within++;
      }
    }
    return (double) within / observed.length;
  }

  /**
   * The share of rows whose estimate is below the observed: how much of the MMRE comes from
   * underestimates, which it bounds at 100%.
   */
  public static double below(final double[] estimate, final double[] observed) {
    check(estimate, observed);
    int below = 0;
    for (int i = 0; i < observed.length; i++) {
      if (estimate[i] < observed[i]) {
        below++;
      }
    }
    return (double) below / observed.length;
  }

  /** The magnitude of relative error, divided by the observed. */
  private static double magnitude(final double estimate, final double observed) {
    return Math.abs(observed - estimate) / observed;
  }

  private static void check(final double[] estimate, final double[] observed) {
    if (estimate.length != observed.length || observed.length == 0) {
      throw new IllegalArgumentException("one estimate per observed duration, and at least one");
    }
    for (final double o : observed) {
      if (!(o > 0)) {
        throw new IllegalArgumentException("an observed duration of " + o + ": the magnitude "
            + "of relative error is defined only above zero");
      }
    }
  }
}
