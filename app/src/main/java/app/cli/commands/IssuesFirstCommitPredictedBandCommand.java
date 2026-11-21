package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.PredictedBands;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The error by band of the predicted business days ({@link PredictedBands}), read-only: a
 * description of the test's frozen predictions, nothing refitted, rescored or chosen. Reads
 * {@code similarity-test-predictions.tsv} from REPORT_DIR, and the error bands' limits and the
 * usual-flow fence from the run's log (RUN_LOG, by default {@code run.log} beside REPORT_DIR),
 * which writes the band limits to two decimals and the fence to three; BAND_TOPS ("a,b,c") and USUAL_FENCE override that
 * where the quartiles are not whole. Writes {@link #REPORT} and {@link #TABLE} to OUT_DIR;
 * nothing to the database, no run recorded.
 */
public final class IssuesFirstCommitPredictedBandCommand implements CommandRunner {

  public static final String STAGE = "issues-first-commit-predicted-band";

  public static final String REPORT = "predicted-band.md";

  public static final String TABLE = "predicted-band.tsv";

  /** The Elastic Net models of the start: no forests, references or retrospective ones. */
  public static final List<String> MODELS = List.of(
      ElasticNetSelection.BEFORE_PRESSURE, ElasticNetSelection.ALZUBAIDI_START,
      ElasticNetSelection.KIKAS_START);

  private final Function<String, String> settings;

  public IssuesFirstCommitPredictedBandCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  /** @param settings the settings by name; the tests hand their own */
  IssuesFirstCommitPredictedBandCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  /** Never reaches {@code context}: nothing is read from or written to the database. */
  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path reports = required("REPORT_DIR");
    final Path out = required("OUT_DIR");
    final Path log = optional("RUN_LOG") == null
        ? reports.toAbsolutePath().getParent().resolve("run.log") : Path.of(optional("RUN_LOG"));
    final PredictedBands.Predictions predictions;
    final double[] tops;
    final double fence;
    try {
      predictions = PredictedBands.parse(Files.readAllLines(reports.resolve(IssuesSimilarityTestCommand.PREDICTIONS)),
          MODELS);
      final String topsSetting = optional("BAND_TOPS");
      final String fenceSetting = optional("USUAL_FENCE");
      final List<String> logLines = topsSetting != null && fenceSetting != null ? List.of()
          : Files.readAllLines(log);
      tops = topsSetting == null ? PredictedBands.bandTops(logLines) : numbers(topsSetting);
      fence = fenceSetting == null ? PredictedBands.usualFence(logLines)
          : parseFence(fenceSetting);
      PredictedBands.validate(tops, fence);
      BusinessDays.bandLabels(tops);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written: "
          + e.getMessage()));
    }
    final List<Result> results = new ArrayList<>();
    for (final String reading : List.of("all", "usual_flow")) {
      for (final String model : MODELS) {
        final double[] predicted = predictions.predicted().get(model);
        results.add(new Result(reading, model, reading.equals("all")
            ? PredictedBands.of(predicted, predictions.observed(), tops)
            : PredictedBands.usualFlow(predicted, predictions.observed(), tops, fence)));
      }
    }
    final List<String> md = report(results, tops, fence, predictions.observed().length);
    md.forEach(line -> System.out.println("  " + line));
    try {
      Files.createDirectories(out);
      IssuesSimilarityTestCommand.write(out.resolve(REPORT), md);
      IssuesSimilarityTestCommand.write(out.resolve(TABLE), table(results));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + out + ": " + e));
    }
    System.out.printf("%n  written to %s: %s, %s; nothing recorded in the database%n", out,
        REPORT, TABLE);
  }

  /** One model's reading, of the full population ("all") or of the usual flow. */
  record Result(String reading, String model, PredictedBands.Reading bands) {}

  private Path required(final String name) throws CommandFailedException {
    final String value = optional(name);
    if (value == null) {
      throw new CommandFailedException(List.of("  REPORT_DIR and OUT_DIR are required; missing "
          + name));
    }
    return Path.of(value);
  }

  private String optional(final String name) {
    final String value = settings.apply(name);
    return value == null || value.isBlank() ? null : value;
  }

  private static double parseFence(final String text) {
    try {
      return PredictedBands.number(text);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("USUAL_FENCE is not a number: " + text);
    }
  }

  private static double[] numbers(final String csv) {
    try {
      return Arrays.stream(csv.split(",")).mapToDouble(s -> Double.parseDouble(s.trim()))
          .toArray();
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("BAND_TOPS is not three numbers: " + csv);
    }
  }

  private static String number(final double v) {
    return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.3f", v);
  }

  static List<String> table(final List<Result> results) {
    final List<String> lines = new ArrayList<>();
    lines.add("reading\tmodel\tview\tband\tmetric\tvalue");
    for (final Result r : results) {
      band(lines, r, "by_prediction", r.bands().byPrediction());
      band(lines, r, "by_observation", r.bands().byObservation());
      final PredictedBands.LongBand l = r.bands().longBand();
      final String[][] counts = {{"predicted_long_observed_long", "" + l.both()},
          {"predicted_long_observed_not", "" + l.predictedOnly()},
          {"predicted_not_observed_long", "" + l.observedOnly()},
          {"neither", "" + l.neither()}, {"precision", number(l.precision())},
          {"recall", number(l.recall())}};
      for (final String[] c : counts) {
        lines.add(String.join("\t", r.reading(), r.model(), "long", "long", c[0], c[1]));
      }
      final PredictedBands.RiskBand k = r.bands().riskBand();
      final PredictedBands.LongBand m = k.counts();
      final String[][] risk = {{"threshold_bd", number(k.threshold())},
          {"marked_observed_long", "" + m.both()}, {"marked_observed_not", "" + m.predictedOnly()},
          {"not_marked_observed_long", "" + m.observedOnly()}, {"neither", "" + m.neither()},
          {"precision", number(m.precision())}, {"recall", number(m.recall())},
          {"base_rate", number(k.baseRate())}};
      for (final String[] c : risk) {
        lines.add(String.join("\t", r.reading(), r.model(), "risk", "top_quarter", c[0], c[1]));
      }
    }
    return lines;
  }

  private static void band(final List<String> lines, final Result r, final String view,
      final List<PredictedBands.Band> bands) {
    for (final PredictedBands.Band b : bands) {
      lines.add(String.join("\t", r.reading(), r.model(), view, b.label(), "n", "" + b.n()));
      lines.add(String.join("\t", r.reading(), r.model(), view, b.label(), "mae",
          number(b.mae())));
      lines.add(String.join("\t", r.reading(), r.model(), view, b.label(), "median_error",
          number(b.medianError())));
      lines.add(String.join("\t", r.reading(), r.model(), view, b.label(), "bias",
          number(b.bias())));
    }
  }

  static List<String> report(final List<Result> results, final double[] tops, final double fence,
      final int scored) {
    final List<String> md = new ArrayList<>();
    md.add("# Error by band of the predicted business days (a description)");
    md.add("");
    md.add("Description of the test's frozen predictions: nothing was refitted, rescored or "
        + "chosen. Each issue goes to the band of its PREDICTION, beside the same bands by its "
        + "observed duration. The limits are the quartiles of the observed business days of the "
        + "cut fitting rows, as the test's report records them (" + BusinessDays.bandLimits(tops)
        + "), never recomputed on the test; the bands are half-open, up to and including each "
        + "limit. MAE and median error are of the absolute error in business days; bias is the "
        + "mean of observed - predicted (positive means underestimation). The error is the "
        + "whole one, never clipped. The long band marks the predictions above the last limit; "
        + "the risk band marks the quarter of the highest predictions of each model in each "
        + "reading (above the third quartile of those predictions, no label read), both against "
        + "the issues observed above the last limit. Models: the three Elastic Net models of the "
        + "start. "
        + scored + " scored issues; the usual flow is the issues observed at or under "
        + BusinessDays.days(fence) + " business days, the report's fence.");
    for (final String reading : List.of("all", "usual_flow")) {
      md.add("");
      md.add(reading.equals("all") ? "## Reading: full population (main)"
          : "## Reading: usual flow (observed at or under " + BusinessDays.days(fence) + ")");
      for (final Result r : results) {
        if (!r.reading().equals(reading)) {
          continue;
        }
        md.add("");
        md.add("### " + r.model());
        md.add("");
        md.add("| band | n by prediction | MAE | median error | bias | n by observation | MAE "
            + "| median error | bias |");
        md.add("|---|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (int b = 0; b < 4; b++) {
          final PredictedBands.Band p = r.bands().byPrediction().get(b);
          final PredictedBands.Band o = r.bands().byObservation().get(b);
          md.add("| " + p.label() + " | " + p.n() + " | " + number(p.mae()) + " | "
              + number(p.medianError()) + " | " + number(p.bias()) + " | " + o.n() + " | "
              + number(o.mae()) + " | " + number(o.medianError()) + " | " + number(o.bias())
              + " |");
        }
        final PredictedBands.LongBand l = r.bands().longBand();
        md.add("");
        md.add("Long band (above " + BusinessDays.days(tops[2]) + "): predicted long and "
            + "observed long " + l.both() + ", predicted long only " + l.predictedOnly()
            + ", observed long only " + l.observedOnly() + ", neither " + l.neither()
            + "; precision " + number(l.precision()) + ", recall " + number(l.recall())
            + " (n/a: division by zero).");
        final PredictedBands.RiskBand k = r.bands().riskBand();
        final PredictedBands.LongBand m = k.counts();
        md.add("");
        md.add("Risk band (the quarter of the highest predictions, above their third quartile, "
            + number(k.threshold()) + " business days, against observed above "
            + BusinessDays.days(tops[2]) + "): marked and observed long " + m.both()
            + ", marked only " + m.predictedOnly() + ", observed long only " + m.observedOnly()
            + ", neither " + m.neither() + "; precision " + number(m.precision()) + ", recall "
            + number(m.recall()) + "; share observed long " + number(k.baseRate())
            + ", the precision of a mark of the same size drawn at random.");
      }
    }
    return md;
  }
}
