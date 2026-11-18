package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.AnalogyEstimate;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.TailCut;
import app.issues.Universe.Population;
import app.issues.Verdict;
import app.issues.model.Fitting;
import app.issues.model.ProjectBootstrap;
import app.issues.model.References;
import app.model.Metrics;
import app.model.Parallel;
import app.study.model.Bootstrap;
import app.study.model.Score;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * EXPLORATORY (the first-commit study's ticket 06, the comparison with estimation by analogy,
 * added after the outer test was opened on 27/10/2026; it confirms nothing): the {@link
 * AnalogyEstimate analogy estimate} beside the fourteen declared models, with the paper's MMRE and
 * Pred(25) added to every model's MAE and ordering.
 *
 * <p>The candidate is chosen as the selection chose the rest: the lowest MAE on the usual-flow
 * inner validation issues, read in the inner columns, the fallback the global median of the inner
 * fitting rows at or below the fence of the chosen k. Only the chosen one is read on the outer
 * test, in its outer column, the fallback the global median of the outer training rows at or
 * below that fence -- the declared reference's value, which the run checks against the
 * predictions file. The fourteen models are read from that file, as the test wrote them; nothing
 * is refitted. Both readings, every issue first, then the usual flow. The analogy estimate is
 * compared with the two reference medians and the six start models: the MAE difference and the
 * ordering differences, intervals resampling whole projects ({@link ProjectBootstrap}).
 *
 * <p>Reads MATRIX, ELASTIC_NET_PARAMETERS and TEST_PREDICTIONS; writes {@link #REPORT} and {@link
 * #PREDICTIONS} to ANALOGY_DIR; nothing to any database.
 */
public final class IssuesFirstCommitAnalogyCommand implements CommandRunner {

  public static final String REPORT = "analogy-comparison.md";

  public static final String PREDICTIONS = "analogy-predictions.tsv";

  public static final String ANALOGY = "analogy_estimate";

  /** The test step's predictions file: these columns, then one per model. */
  static final List<String> KEYS = List.of("id", "key", "project", "observed_business_days");

  private static final String OBSERVED = KEYS.get(3);

  /** The models compared with the analogy estimate, in the report's order. */
  static final List<String> AGAINST = List.of(SimilarityScoring.GLOBAL,
      SimilarityScoring.PROJECT, ElasticNetSelection.BEFORE_PRESSURE,
      ElasticNetSelection.ALZUBAIDI_START, ElasticNetSelection.KIKAS_START,
      SimilarityScoring.forest(ElasticNetSelection.BEFORE_PRESSURE),
      SimilarityScoring.forest(ElasticNetSelection.ALZUBAIDI_START),
      SimilarityScoring.forest(ElasticNetSelection.KIKAS_START));

  private final Function<String, String> settings;

  public IssuesFirstCommitAnalogyCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  IssuesFirstCommitAnalogyCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  /** The inputs, as read: their paths and hashes. */
  record Inputs(Path matrix, Path elasticNet, Path testPredictions,
      IssuesSimilarityTestCommand.Chosen chosen, String predictionsSha) {}

  /**
   * Where the candidate was chosen: the usual-flow inner validation issues, the inner fitting rows
   * at or below the fence, their global median, the fallback, and the choice.
   */
  record Inner(Fitting.Sides usual, int fitRows, double global, AnalogyEstimate.Choice choice) {}

  /** One reading of the outer test: its issues, their truth and every model's estimates. */
  record TestReading(TailCut.Reading reading, List<IssueUnit> units, double[] observed,
      Map<String, double[]> predicted, boolean[] fellBack, int fallbacks) {}

  /**
   * The outer test: the outer training rows at or below the fence, the fences, the global median,
   * the fallback, and both readings, every issue first.
   */
  record Outer(int fitRows, double fence, double usualFence, double global,
      TestReading all, TestReading usual) {}

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path dir = path("ANALOGY_DIR");
    final Inputs in;
    final List<RowCache.Entry> entries;
    final List<String> lines;
    try {
      final Path matrix = path("MATRIX");
      final Path elasticNet = path("ELASTIC_NET_PARAMETERS");
      final Path testPredictions = path("TEST_PREDICTIONS");
      if (RowCache.splitPopulation(matrix) != Population.FIRST_COMMIT) {
        throw refused(matrix + " is not of the first-commit population");
      }
      final IssuesSimilarityTestCommand.Chosen chosen =
          IssuesSimilarityTestCommand.chosen(elasticNet, matrix);
      if (chosen.population() != Population.FIRST_COMMIT) {
        throw refused(elasticNet + " was written for " + chosen.population());
      }
      entries = RowCache.read(matrix);
      lines = Files.readAllLines(testPredictions);
      in = new Inputs(matrix, elasticNet, testPredictions, chosen, Sha256.of(testPredictions));
    } catch (final IOException | IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }
    final double k = in.chosen().cut().k();
    final Inner inner = inner(entries, k);
    final Outer outer;
    try {
      outer = outer(entries, k, predictions(lines), inner.choice().chosen());
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    final List<String> md = report(in, k, inner, outer,
        Parallel.threads(Parallel.BOOTSTRAP_THREADS));
    md.forEach(line -> System.out.println("  " + line));
    try {
      Files.createDirectories(dir);
      IssuesSimilarityTestCommand.write(dir.resolve(REPORT), md);
      IssuesSimilarityTestCommand.write(dir.resolve(PREDICTIONS), estimates(outer));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + dir + ": " + e));
    }
  }

  /** The choice, on the usual-flow inner validation issues; the outer test is not read here. */
  static Inner inner(final List<RowCache.Entry> entries, final double k) {
    final List<RowCache.Entry> training = entries.stream()
        .filter(e -> Verdict.outerTraining(e.outer(Population.FIRST_COMMIT))).toList();
    final Fitting.Sides whole = Fitting.sides(
        BusinessDays.population(training, Augmentation.Variant.INNER).entries(),
        Fitting.Mode.INNER, Population.FIRST_COMMIT);
    final Fitting.Sides cut = TailCut.cut(whole, TailCut.fence(whole.fitUnits(), k));
    final Fitting.Sides usual = TailCut.usualFlow(cut,
        TailCut.fence(whole.fitUnits(), TailCut.USUAL_FLOW_K));
    final double global = References.learnedOn(cut.fitUnits()).constant(1)[0];
    return new Inner(usual, cut.fitUnits().size(), global, AnalogyEstimate.choose(
        usual.scoreRows(), Augmentation.Variant.INNER, global, usual.observed()));
  }

  /** The outer test, as the test step scored it, with the chosen candidate beside it. */
  private static Outer outer(final List<RowCache.Entry> entries, final double k,
      final Map<String, Map<String, Double>> file, final String chosen) {
    final Fitting.Sides whole = Fitting.sides(
        BusinessDays.population(entries, Augmentation.Variant.OUTER).entries(),
        Fitting.Mode.RESERVED, Population.FIRST_COMMIT);
    final double fence = TailCut.fence(whole.fitUnits(), k);
    final double usualFence = TailCut.fence(whole.fitUnits(), TailCut.USUAL_FLOW_K);
    final Fitting.Sides cut = TailCut.cut(whole, fence);
    final double global = References.learnedOn(cut.fitUnits()).constant(1)[0];
    final TestReading all = reading(TailCut.Reading.ALL, cut, file, chosen, global);
    if (all.units().size() != file.size()) {
      throw new IllegalArgumentException("the predictions file holds " + file.size()
          + " issues, the outer test " + all.units().size());
    }
    return new Outer(cut.fitUnits().size(), fence, usualFence, global, all,
        reading(TailCut.Reading.USUAL_FLOW, TailCut.usualFlow(cut, usualFence), file, chosen,
            global));
  }

  /** The test step's predictions file, per issue id its truth and models by name. */
  static Map<String, Map<String, Double>> predictions(final List<String> lines) {
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("the predictions file is empty");
    }
    final String[] header = lines.get(0).split("\t", -1);
    if (header.length <= KEYS.size()
        || !List.of(header).subList(0, KEYS.size()).equals(KEYS)) {
      throw new IllegalArgumentException("the predictions file does not begin with " + KEYS
          + " and a model");
    }
    final Map<String, Map<String, Double>> out = new LinkedHashMap<>();
    for (final String line : lines.subList(1, lines.size())) {
      final String[] f = line.split("\t", -1);
      if (f.length != header.length) {
        throw new IllegalArgumentException("a predictions line of " + f.length + " fields, the "
            + "header has " + header.length);
      }
      final Map<String, Double> values = new LinkedHashMap<>();
      for (int c = KEYS.indexOf(OBSERVED); c < f.length; c++) {
        values.put(header[c], Double.parseDouble(f[c]));
      }
      if (out.put(f[0], values) != null) {
        throw new IllegalArgumentException("issue " + f[0] + " twice in the predictions file");
      }
    }
    return out;
  }

  /**
   * One reading: every model's estimates from the file, the analogy estimate from the matrix; the
   * file must hold every issue, its truth and the global median this run learns.
   */
  private static TestReading reading(final TailCut.Reading reading, final Fitting.Sides sides,
      final Map<String, Map<String, Double>> file, final String chosen, final double global) {
    final List<IssueUnit> units = sides.scoreUnits();
    final double[] observed = sides.observed();
    final List<String> models = new ArrayList<>(file.values().iterator().next().keySet());
    models.remove(OBSERVED);
    final Map<String, double[]> predicted = new LinkedHashMap<>();
    final AnalogyEstimate.Estimate analogy = AnalogyEstimate.estimate(sides.scoreRows(),
        chosen + Augmentation.Variant.OUTER.suffix(), global);
    predicted.put(ANALOGY, analogy.values());
    for (final String m : models) {
      predicted.put(m, new double[units.size()]);
    }
    for (int i = 0; i < units.size(); i++) {
      final Map<String, Double> line = file.get(units.get(i).issueId());
      if (line == null) {
        throw new IllegalArgumentException("issue " + units.get(i).issueId() + " of the outer "
            + "test is not in the predictions file");
      }
      if (line.get(OBSERVED) != observed[i]) {
        throw new IllegalArgumentException("issue " + units.get(i).issueId() + ": observed "
            + observed[i] + " here, " + line.get(OBSERVED) + " in the file");
      }
      if (line.get(SimilarityScoring.GLOBAL) != global) {
        throw new IllegalArgumentException("the global median learned here, " + global
            + ", is not the file's, " + line.get(SimilarityScoring.GLOBAL));
      }
      for (final String m : models) {
        predicted.get(m)[i] = line.get(m);
      }
    }
    return new TestReading(reading, units, observed, predicted, analogy.fellBack(),
        analogy.fallbacks());
  }

  /** Whether a model is in the SPSP chain: the start models, the medians, the analogy. */
  static boolean inChain(final String model) {
    return model.equals(ANALOGY) || AGAINST.contains(model);
  }

  private static List<String> report(final Inputs in, final double k, final Inner inner,
      final Outer outer, final int threads) {
    final List<String> md = new ArrayList<>();
    md.add("# Issue duration from the first commit: the analogy estimate (EXPLORATORY)");
    md.add("");
    md.add("**NOTE: added after the outer test was opened on 27/10/2026; it confirms nothing and "
        + "changes none of the declared results. The analogy estimate is outside the fourteen "
        + "declared models; MMRE and Pred(25) are a summary added after the opening, computed on "
        + "the predictions the test already wrote.**");
    md.add("");
    md.add("Reference: Shepperd, M.; Schofield, C. Estimating Software Project Effort Using "
        + "Analogies. IEEE Transactions on Software Engineering 23(12):736-743, 1997.");
    md.add("");
    md.add("- matrix: " + in.matrix() + " (sha256 " + in.chosen().matrixSha() + ")");
    md.add("- parameters: " + in.elasticNet() + " (sha256 " + in.chosen().elasticNetSha() + ")");
    md.add("- test predictions: " + in.testPredictions() + " (sha256 " + in.predictionsSha()
        + ")");
    md.add(f("- tail cut: k %s, as the selection chose it", TailCut.label(k)));
    md.add("- MMRE: the mean of the magnitude of relative error, |observed - estimate| / "
        + "observed. Pred(25): the share of issues with that magnitude at or below 0.25. Both "
        + "divided by the observed, as in the paper (the study's own relative error is signed "
        + "and divided by the estimate). As the paper itself notes, the MMRE penalises "
        + "overestimation more than underestimation: an estimate below the observed errs by at "
        + "most 100%, one above it without bound. The column \"below observed\" gives each "
        + "model's share of underestimates.");
    md.add("");
    md.add("## The choice, on the inner validation (usual flow)");
    md.add("");
    md.add(f("%d inner validation issues of the usual flow; for an issue without neighbours, the "
        + "global median of the %d inner fitting rows at or below the fence, %.3f business days. "
        + "The lowest MAE wins; a tie goes to the first in the order Jaccard, embedding, cluster, "
        + "the smaller size first.", inner.usual().scoreUnits().size(), inner.fitRows(),
        inner.global()));
    md.add("");
    md.add("| candidate | MAE (bd) | issues without neighbours | chosen |");
    md.add("|---|---:|---:|---|");
    for (final AnalogyEstimate.Candidate c : inner.choice().candidates()) {
      md.add(f("| %s | %.3f | %d | %s |", c.name(), c.mae(), c.fallbacks(),
          c.name().equals(inner.choice().chosen()) ? "yes" : ""));
    }
    md.add("");
    md.add(f("On the outer test the analogy estimate reads %s%s; an issue without neighbours "
        + "takes the global median of the %d outer training rows at or below the fence of %.3f "
        + "business days, %.3f business days, the declared reference's own value (checked "
        + "against the predictions file).", inner.choice().chosen(),
        Augmentation.Variant.OUTER.suffix(), outer.fitRows(), outer.fence(), outer.global()));
    for (final TestReading r : List.of(outer.all(), outer.usual())) {
      md.add("");
      md.add("## " + (r.reading() == TailCut.Reading.ALL
          ? f("All issues: %d of the outer test", r.units().size())
          : f("Usual flow: %d of the outer test, observed at or below %.3f business days",
              r.units().size(), outer.usualFence())));
      md.add("");
      md.add(f("Issues without neighbours, given the global median: %d of %d (%.1f%%).",
          r.fallbacks(), r.units().size(), 100.0 * r.fallbacks() / r.units().size()));
      md.add("");
      md.add("| model | place | MAE (bd) | Spearman | pairwise | MMRE | Pred(25) "
          + "| below observed |");
      md.add("|---|---|---:|---:|---:|---:|---:|---:|");
      final List<String> order = new ArrayList<>();
      order.add(ANALOGY);
      order.addAll(AGAINST);
      r.predicted().keySet().stream().filter(m -> !order.contains(m)).forEach(order::add);
      for (final String m : order) {
        final double[] p = r.predicted().get(m);
        final double spearman = Metrics.spearman(p, r.observed());
        md.add(f("| %s | %s | %.3f | %s | %.3f | %.3f | %.3f | %.3f |", m,
            inChain(m) ? "SPSP chain" : "ceiling and association, outside the chain",
            Fitting.mae(p, r.observed()), Double.isNaN(spearman) ? "—" : f("%.3f", spearman),
            Score.pairwiseAccuracy(p, r.observed()), AnalogyEstimate.mmre(p, r.observed()),
            AnalogyEstimate.pred25(p, r.observed()), AnalogyEstimate.below(p, r.observed())));
      }
      md.add("");
      md.add("Paired comparisons, the analogy estimate (a) against each (b): a - b, with the 95% "
          + "interval from " + ProjectBootstrap.DRAWS + " draws of whole projects (seed "
          + ProjectBootstrap.SEED + "); an interval containing zero is a tie. A negative MAE "
          + "difference means the analogy estimate errs less; a positive ordering difference "
          + "means it orders better. The global median orders nothing, so no ordering "
          + "difference is drawn against it.");
      md.add("");
      md.add("| b | MAE a - b [95%] | Spearman a - b [95%] | pairwise a - b [95%] |");
      md.add("|---|---|---|---|");
      final List<String> projects = r.units().stream().map(IssueUnit::project).toList();
      final double[] a = r.predicted().get(ANALOGY);
      for (final String b : AGAINST) {
        final double[] pb = r.predicted().get(b);
        final ProjectBootstrap.Gap gap = ProjectBootstrap.compare(projects, ANALOGY,
            Bootstrap.errors(a, r.observed()), b, Bootstrap.errors(pb, r.observed()), threads);
        String spearman = "—";
        String pairwise = "—";
        if (!b.equals(SimilarityScoring.GLOBAL)) {
          final ProjectBootstrap.Ordering o = ProjectBootstrap.ordering(projects, ANALOGY, a, b,
              pb, r.observed(), threads);
          spearman = gap(o.spearman());
          pairwise = gap(o.pairwise());
        }
        md.add(f("| %s | %s | %s | %s |", b, gap(gap), spearman, pairwise));
      }
    }
    md.add("");
    md.add("## Reading across the three levels (descriptive, no test across studies)");
    md.add("");
    md.add("- What the SPSP studies assume: Shen et al. (2016) take a standard deviation of 10% "
        + "of the estimate for real projects, and 5% to 100% in their sensitivity analysis.");
    md.add("- What estimation reached on whole projects: Shepperd and Schofield report MMRE and "
        + "Pred(25) per data set, for effort, by jack-knife over whole projects.");
    md.add("- What the models here reach on issues: the tables above, for duration in business "
        + "days, on a test set held out once.");
    md.add("- The differences, always: effort against duration, projects against issues, "
        + "jack-knife against a held-out test.");
    return md;
  }

  /** Per outer test issue, the analogy estimate, whether it fell back, and its reading. */
  private static List<String> estimates(final Outer outer) {
    final TestReading all = outer.all();
    final List<String> out = new ArrayList<>();
    out.add(String.join("\t", KEYS) + "\tusual_flow\t" + ANALOGY + "\tfallback");
    final double[] a = all.predicted().get(ANALOGY);
    for (int i = 0; i < all.units().size(); i++) {
      final IssueUnit u = all.units().get(i);
      out.add(String.join("\t", u.issueId(), u.key(), u.project(),
          Double.toString(all.observed()[i]),
          Boolean.toString(all.observed()[i] <= outer.usualFence()), Double.toString(a[i]),
          Boolean.toString(all.fellBack()[i])));
    }
    return out;
  }

  private static String gap(final ProjectBootstrap.Gap g) {
    return f("%+.3f [%+.3f, %+.3f]%s", g.gap(), g.low(), g.high(), g.tied() ? " tie" : "");
  }

  private static String f(final String format, final Object... args) {
    return String.format(Locale.ROOT, format, args);
  }

  /** A required path setting, by name; a missing one refuses the run. */
  private Path path(final String name) throws CommandFailedException {
    final String value = settings.apply(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  MATRIX, ELASTIC_NET_PARAMETERS, "
          + "TEST_PREDICTIONS and ANALOGY_DIR are required; missing " + name));
    }
    return Path.of(value);
  }

  private static CommandFailedException refused(final String why) {
    return new CommandFailedException(List.of("  REFUSED, nothing was written: " + why));
  }
}
