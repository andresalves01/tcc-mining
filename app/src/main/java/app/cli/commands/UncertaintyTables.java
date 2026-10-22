package app.cli.commands;

import app.issues.EstimationUncertainty;
import app.issues.EstimationUncertainty.Estimate;
import app.issues.EstimationUncertainty.Percentile;
import app.issues.EstimationUncertainty.Summary;
import app.issues.PredictionIntervals;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The same uncertainty tables and explanations in the inner and outer report paths. */
final class UncertaintyTables {

  record Tables(List<String> markdown, List<String> uncertainty, List<String> percentiles,
      List<Map<String, Object>> documents) {}

  private UncertaintyTables() {}

  static Tables of(final SimilarityScoring.Readings readings, final List<RowCache.Entry> entries,
      final Map<String, PredictionIntervals.Calibration> calibrated, final int threads) {
    final Map<String, Boolean> descriptions = EstimationUncertainty.descriptions(entries);
    final List<EstimationUncertainty.Reading> results = new ArrayList<>();
    for (final SimilarityScoring.Report r : List.of(readings.main(), readings.complementary())) {
      results.add(EstimationUncertainty.read(EstimationUncertainty.Sample.of(r, descriptions),
          calibrated, threads));
    }
    return render(results);
  }

  static Tables render(final List<EstimationUncertainty.Reading> results) {
    final List<String> md = new ArrayList<>();
    final List<String> stats = new ArrayList<>();
    final List<String> percentiles = new ArrayList<>();
    final List<Map<String, Object>> documents = new ArrayList<>();
    stats.add("reading\tmodel\tgrouping\tgroup\tn\tpositive_estimates\tzero_estimates"
        + "\tprojects\tpositive_estimate_projects\tmetric\tvalue\tci_low\tci_high"
        + "\tvalid_bootstrap_draws");
    percentiles.add("reading\tmodel\tn\tprediction_percentile\testimate_bd\tpi80_low_bd"
        + "\tpi80_high_bd\tpi95_low_bd\tpi95_high_bd");
    md.add("## Estimation uncertainty");
    md.add("");
    md.add(EstimationUncertainty.RULE + ".");
    md.add("");
    md.add(EstimationUncertainty.INTERVAL_RULE + ". Confidence intervals describe uncertainty "
        + "in summary measures; prediction intervals describe possible individual durations. "
        + "Subgroup intervals are descriptive, without a multiple-comparison adjustment.");
    md.add("");
    md.add("Observed-duration groups retain the existing quartiles of the cut fitting rows; they "
        + "are "
        + "readings after the fact. Description availability is known at the start: "
        + "A01_desc_length > 0. Unavailable or blank can reflect missing historical reconstruction, "
        + "not necessarily a truly absent original description. Neither description nor "
        + "retrospective activity is an exclusion rule.");
    md.add("");
    md.add("Relative measures below are percentages; MAE is business days. Each cell is value "
        + "[95% confidence interval]; '-' means undefined. Relative measures use only positive "
        + "estimates; MAE uses all n issues. TSV and database relative measures are fractions "
        + "(0.5 = 50%); exact valid-draw counts are in the uncertainty TSV.");
    md.add("");
    for (final EstimationUncertainty.Reading r : results) {
      final String reading = r.summaries().isEmpty() ? "empty" : r.summaries().get(0).reading();
      md.add("### " + reading + ": overall and subgroup uncertainty");
      md.add("");
      md.add("| model | grouping | group | n | positive estimates | zero estimates | projects "
          + "| positive-estimate projects | MAE bd [95% CI] | bias % [95% CI] | SD % [95% CI] "
          + "| within ±25% [95% CI] "
          + "| within ±50% [95% CI] | within ±100% [95% CI] |");
      md.add("|---|---|---|---:|---:|---:|---:|---:|---|---|---|---|---|---|");
      for (final Summary s : r.summaries()) {
        final StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
            "| %s | %s | %s | %d | %d | %d | %d | %d |", s.model(), s.grouping(), s.group(),
            s.n(), s.positiveEstimates(), s.zeroEstimates(), s.projects(),
            s.positiveEstimateProjects()));
        for (int k = 0; k < EstimationUncertainty.METRICS.size(); k++) {
          final Estimate e = s.metrics().get(k);
          final double scale = k == 0 ? 1 : 100;
          line.append(" ").append(cell(e, scale)).append(" |");
          stats.add(String.join("\t", s.reading(), s.model(), s.grouping(), s.group(),
              Integer.toString(s.n()), Integer.toString(s.positiveEstimates()),
              Integer.toString(s.zeroEstimates()), Integer.toString(s.projects()),
              Integer.toString(s.positiveEstimateProjects()),
              EstimationUncertainty.METRICS.get(k), value(e.value()), value(e.low()),
              value(e.high()), Integer.toString(e.validDraws())));
          final Map<String, Object> doc = new LinkedHashMap<>();
          doc.put("kind", "estimation_uncertainty");
          doc.put("reading", s.reading());
          doc.put("model", s.model());
          doc.put("grouping", s.grouping());
          doc.put("group", s.group());
          doc.put("n", s.n());
          doc.put("positive_estimates", s.positiveEstimates());
          doc.put("zero_estimates", s.zeroEstimates());
          doc.put("projects", s.projects());
          doc.put("positive_estimate_projects", s.positiveEstimateProjects());
          doc.put("metric", EstimationUncertainty.METRICS.get(k));
          doc.put("value", numeric(e.value()));
          doc.put("ci_low", numeric(e.low()));
          doc.put("ci_high", numeric(e.high()));
          doc.put("valid_bootstrap_draws", e.validDraws());
          documents.add(doc);
        }
        md.add(line.toString());
      }
      md.add("");
      md.add("### " + reading + ": percentiles of estimates with 95% prediction intervals");
      md.add("");
      md.add(EstimationUncertainty.PERCENTILE_RULE + ".");
      md.add("");
      md.add("Each cell is estimated business days [95% prediction interval]. Constants "
          + "legitimately repeat across percentiles. The percentile TSV also contains "
          + "80% intervals.");
      md.add("");
      md.add("| model | P25 | P50 | P75 | P90 | P95 |");
      md.add("|---|---|---|---|---|---|");
      for (int i = 0; i < r.percentiles().size(); i += EstimationUncertainty.PERCENTILES.size()) {
        final StringBuilder line = new StringBuilder("| " + r.percentiles().get(i).model() + " |");
        for (int k = 0; k < EstimationUncertainty.PERCENTILES.size(); k++) {
          final Percentile p = r.percentiles().get(i + k);
          line.append(" ").append(number(p.estimate())).append(" [")
              .append(number(p.low95())).append(", ").append(number(p.high95())).append("] |");
          percentiles.add(String.join("\t", p.reading(), p.model(), Integer.toString(p.n()),
              value(p.percentile()), value(p.estimate()), value(p.low80()), value(p.high80()),
              value(p.low95()), value(p.high95())));
          final Map<String, Object> doc = new LinkedHashMap<>();
          doc.put("kind", "prediction_percentile");
          doc.put("reading", p.reading());
          doc.put("model", p.model());
          doc.put("n", p.n());
          doc.put("prediction_percentile", p.percentile());
          doc.put("estimate_business_days", numeric(p.estimate()));
          doc.put("pi80_low_business_days", numeric(p.low80()));
          doc.put("pi80_high_business_days", numeric(p.high80()));
          doc.put("pi95_low_business_days", numeric(p.low95()));
          doc.put("pi95_high_business_days", numeric(p.high95()));
          documents.add(doc);
        }
        md.add(line.toString());
      }
      md.add("");
    }
    md.addAll(context());
    return new Tables(md, stats, percentiles, documents);
  }

  /** Literature context only: no scheduling verdict, threshold, model selection or calibration. */
  static List<String> context() {
    return List.of("## SPSP uncertainty context", "",
        "Under fixed staff dedication A, the SPSP equation D = E / A implies "
            + "(D - estimated D) / estimated D = (E - estimated E) / estimated E. This is an "
            + "inference under that allocation assumption, from equations 1–2 of "
            + "[Chicano et al. (2011)](https://doi.org/10.1145/2001576.2001833). "
            + "The target and treatment of silent/Open/Reopened dates are specified in the report header; "
            + "they measure operational duration, not person-effort.", "",
        "[Shen et al. (2016), §§6.1 and 6.5](https://doi.org/10.1109/TSE.2015.2512266) "
            + "investigate the following effort-uncertainty scenarios:", "",
        "| setting | assumed or tested uncertainty |",
        "|---|---|",
        "| Instances derived from real projects | Normal effort SD = 10% of estimated effort |",
        "| Synthetic instances | Means in [8, 12], SDs in [4, 6] person-months; roughly 50% |",
        "| Sensitivity analysis on one real-project instance | SD/estimated effort = "
            + "5%, 10%, 15%, 20%, 40%, 60%, 80%, 100% |", "",
        "The robust method showed smaller increases in project duration and cost than its "
            + "comparison heuristic as uncertainty increased. These are tested scenarios, not "
            + "validated acceptable-error thresholds; an SD of 50% does not bound errors to ±50%.",
        "", "Our pooled relative-error SD is a descriptive magnitude comparison, not an "
            + "estimate of each activity's conditional uncertainty. Pooling mixes conditional "
            + "bias and variation; distribution shape, task dependence and the usual-flow "
            + "restriction also differ. Bias must be read beside SD. Comparable magnitudes do "
            + "not establish acceptable scheduling performance, which requires a scheduling "
            + "experiment. Retrospective models describe associations unavailable at the start.", "");
  }

  private static String cell(final Estimate e, final double scale) {
    return number(e.value() * scale) + " [" + number(e.low() * scale) + ", "
        + number(e.high() * scale) + "]";
  }

  private static String number(final double v) {
    return Double.isNaN(v) ? "-" : Double.isInfinite(v) ? "∞"
        : String.format(Locale.ROOT, "%.2f", v);
  }

  private static String value(final double v) {
    return Double.isNaN(v) ? "" : Double.toString(v);
  }

  private static Double numeric(final double v) {
    return Double.isNaN(v) ? null : v;
  }
}
