package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.model.Bounds;
import app.model.DeveloperOrdering;
import app.model.ElasticNetModel;
import app.model.Metrics;
import app.model.Population;
import app.pipeline.FitModelTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fits the Elastic Net on the training side and scores it. Reads only; writes nothing. */
public final class FitCommand implements CommandRunner {

  /**
   * The population arms, in the order they are reported.
   *
   * <p>All four were fixed in the analysis 5 pre-registration before any
   * of them was run, and all four are printed. Neither the set nor the order is chosen by what the
   * scores turn out to be.
   */
  private static final List<Population> POPULATIONS = List.of(
      Population.all(),
      Population.withoutPostdated(),
      Population.withoutImmediateByMinute(),
      Population.withoutImmediateByHour());

  /** One line of the cross-arm table, so the eight reports can be read as a comparison. */
  private record Summary(String population, String bounds, String partition, int n,
      double mae, double r2, double ordering, double withinTwo, double referenceMae) {}

  @Override
  public void run(final Context context) throws CommandFailedException {
    // Both duration arms and all four population arms from one run. Which population a study of
    // issue lifetime is actually modelling is the question; reporting one arm would make the
    // others impossible to put beside it.
    final FitModelTask task = new FitModelTask(context.observations(), context.progress());
    final List<Summary> summaries = new ArrayList<>();
    for (final Population population : POPULATIONS) {
      for (final Bounds bounds : List.of(Bounds.none(), Bounds.oneHourToOneYear())) {
        try {
          // The inner selection chooses the parameters; the held-out partition is then scored once
          // with them, refitted over the whole training side. Re-sweeping there would let the
          // held-out outcomes pick their own model.
          final FitModelTask.Report inner = task.run(bounds, population);
          print(inner, bounds, "INNER SELECTION");
          summaries.add(summarise(inner, "inner selection"));
          final FitModelTask.Report held =
              task.heldOut(bounds, population, inner.alpha(), inner.lambda());
          print(held, bounds, "OUTER TEST");
          summaries.add(summarise(held, "outer test"));
        } catch (final FitModelTask.NoObservationsException e) {
          throw new CommandFailedException(List.of("  " + e.getMessage()));
        }
      }
    }
    printSummary(summaries);
  }

  private static Summary summarise(final FitModelTask.Report report, final String partition) {
    Metrics model = null;
    double referenceMae = Double.NaN;
    for (final FitModelTask.Scored scored : report.comparison()) {
      if (scored.name().equals("full model")) {
        model = scored.metrics();
      } else if (scored.name().startsWith("REFERENCE")) {
        referenceMae = scored.metrics().maeDays();
      }
    }
    final List<DeveloperOrdering.Result> gradient =
        report.comparison().get(report.comparison().size() - 1).ordering();
    return new Summary(report.population().name(), report.bounds().name(), partition,
        model.n(), model.maeDays(), model.r2Log(), model.rankCorrelation(),
        model.withinFactorTwo(), referenceMae);
  }

  /**
   * The cross-arm table. This is what analysis 5 actually asks for: not whether one arm scores
   * better, but whether the population being modelled changes materially with an assumption that
   * issue-lifetime studies do not usually state.
   */
  private static void printSummary(final List<Summary> summaries) {
    System.out.printf("%n%n  ===================================================================%n");
    System.out.printf("  ANALYSIS 5 -- THE FOUR POPULATIONS SIDE BY SIDE%n");
    System.out.printf("  ===================================================================%n%n");
    System.out.printf("  Thresholds fixed in the analysis 5 pre-registration before any%n");
    System.out.printf("  run. The outer test was read once per arm, with%n");
    System.out.printf("  hyperparameters frozen from the inner split.%n%n");
    System.out.printf("  %-24s %-18s %-15s %7s %8s %8s %8s %9s %9s%n",
        "population", "duration", "partition", "n", "MAE d", "ref MAE", "R2 log",
        "orders", "within 2x");
    System.out.printf("  %s%n", "-".repeat(124));
    for (final Summary s : summaries) {
      System.out.printf("  %-24s %-18s %-15s %7d %8.2f %8.2f %8.3f %9s %8.1f%%%n",
          s.population(), s.bounds(), s.partition(), s.n(), s.mae(), s.referenceMae(), s.r2(),
          Double.isNaN(s.ordering()) ? "--" : String.format("%.3f", s.ordering()),
          100 * s.withinTwo());
    }
    System.out.printf("%n  'ref MAE' is reference 3.6, the median of the (developer,"
        + " project) pair.%n");
  }

  /**
   * Package-visible rather than private: {@link FitInnerCommand} reuses this formatting for the
   * inner-selection half only, so the two commands cannot silently drift apart in what they print
   * for the same kind of report. Safe to share because printing a {@link FitModelTask.Report} that
   * already exists never calls {@link FitModelTask#heldOut} itself -- that call happens, or does
   * not, entirely in the caller.
   */
  static void print(final FitModelTask.Report report, final Bounds bounds,
      final String partition) {

    System.out.printf("%n%n  ===================================================================%n");
    System.out.printf("  %s  --  %s%n", partition, bounds.name().toUpperCase());
    System.out.printf("  ===================================================================%n%n");
    System.out.printf("  fitted on : %,d labels      scored on : %,d labels%n",
        report.fitRows(), report.selectionRows());
    System.out.printf("  set aside for an empty team : %,d and %,d%n",
        report.excludedEmptyTeamFit(), report.excludedEmptyTeamSelection());
    if (partition.startsWith("OUTER")) {
      System.out.printf("  Opened on 21/09/2026, once, with the specification described in%n");
      System.out.printf("  the pre-registration. A single measurement -- NOT an independent estimate of%n");
      System.out.printf("  generalisation: McCC_tot missing and the duration cut undecided.%n");
    }
    if (!report.droppedColumns().isEmpty()) {
      System.out.printf("%n  constant in the fit, dropped : %s%n",
          String.join(", ", report.droppedColumns()));
    }

    System.out.printf("%n  HYPERPARAMETERS  (always chosen on the inner split)%n");
    System.out.printf("    alpha = %.2f      lambda = %.6g%n", report.alpha(), report.lambda());
    System.out.printf("    columns selected : %d of %d   (threshold |b|/|b|max >= %.0e,"
        + " declared beforehand)%n", report.selectedColumns(), report.columns().size(),
        ElasticNetModel.SELECTION_THRESHOLD);

    System.out.printf("%n  COEFFICIENTS  (standardised columns, so comparable with each other)%n");
    final double[] coefficients = report.coefficients();
    double largest = 0;
    for (final double c : coefficients) {
      largest = Math.max(largest, Math.abs(c));
    }
    for (int i = 0; i < report.columns().size(); i++) {
      final boolean kept = largest > 0
          && Math.abs(coefficients[i]) / largest >= ElasticNetModel.SELECTION_THRESHOLD;
      System.out.printf("    %-26s %12.6f   %s%n", report.columns().get(i), coefficients[i],
          kept ? "" : "below the threshold");
    }
    System.out.printf("    %-26s %12.6f%n", "(intercept)", report.intercept());

    System.out.printf("%n  AGAINST THE REFERENCES  (measured on %s)%n", partition.toLowerCase());
    System.out.printf("    %-32s %8s %9s %11s %9s %9s %9s %9s%n", "", "n", "MAE d",
        "RMSE d", "med err", "R2 log", "orders", "within 2x");
    for (final FitModelTask.Scored scored : report.comparison()) {
      final Metrics m = scored.metrics();
      System.out.printf("    %-32s %8d %9.2f %11.2f %9.2f %9.3f %9s %8.1f%%%n",
          scored.name(), m.n(), m.maeDays(), m.rmseDays(), m.medianErrorDays(),
          m.r2Log(), Double.isNaN(m.rankCorrelation()) ? "--"
              : String.format("%.3f", m.rankCorrelation()), 100 * m.withinFactorTwo());
    }
    System.out.printf("    reference 3.6 finds the pair in %.1f%% of the rows%n",
        100 * report.pairCoverage());

    System.out.printf("%n  ORDERING DEVELOPERS  (same project, different people)%n");
    System.out.printf("    as the tasks become alike, only the difference between the"
        + " people remains%n%n");
    // Each cell carries its own denominator. A single "pairs" column would be the count of ONE
    // of the predictors standing as the label for all, and they differ: a pair whose prediction
    // ties decides nothing and drops out, and different predictors tie on different pairs.
    // Comparing two percentages without the denominators in view was a real defect of this table.
    final List<DeveloperOrdering.Result> gradient =
        report.comparison().get(report.comparison().size() - 1).ordering();
    System.out.printf("    %-34s", "");
    for (final FitModelTask.Scored scored : report.comparison()) {
      System.out.printf(" %22s", abbreviate(scored.name()));
    }
    System.out.println();
    for (int i = 0; i < gradient.size(); i++) {
      System.out.printf("    %-34s", gradient.get(i).matching());
      for (final FitModelTask.Scored scored : report.comparison()) {
        final DeveloperOrdering.Result o = scored.ordering().get(i);
        // A prediction that does not vary between two people in the same project ties every
        // pair, and a tie decides nothing. That is the baseline's shape, not a score of zero.
        System.out.printf(" %22s", o.pairs() == 0 ? "does not distinguish"
            : String.format("%.1f%% (%,d)", 100 * o.agreement(), o.pairs()));
      }
      System.out.println();
    }
    System.out.printf("%n    (50%% is the coin. The pairs are not independent of each other, so the%n");
    System.out.printf("     decimals should not be read as precision. In parentheses, the pairs%n");
    System.out.printf("     each predictor actually decided.)%n");

    System.out.printf("%n  ABLATION BY PREDICTOR GROUP%n");
    for (final Map.Entry<String, FitModelTask.Scored> entry : report.ablation().entrySet()) {
      final FitModelTask.Scored scored = entry.getValue();
      if (scored.metrics() == null) {
        System.out.printf("    %-24s (no columns)%n", entry.getKey());
        continue;
      }
      final DeveloperOrdering.Result tight =
          scored.ordering().get(scored.ordering().size() - 1);
      System.out.printf("    %-24s MAE %8.2fd   R2 log %7.3f   orders tasks %6.3f"
          + "   orders devs %5.1f%% (strict filter)%n", entry.getKey(),
          scored.metrics().maeDays(), scored.metrics().r2Log(),
          scored.metrics().rankCorrelation(), 100 * tight.agreement());
    }
    System.out.printf("%n  %s%n", partition.startsWith("OUTER")
        ? "SINGLE MEASUREMENT on the pre-registered specification, opened on 21/09/2026."
        : "EXPLORATORY: the inner split has already been looked at many times in earlier sessions.");
  }

  /** Short enough for a column header. */
  private static String abbreviate(final String name) {
    return name.length() <= 14 ? name : name.substring(0, 13) + ".";
  }
}
