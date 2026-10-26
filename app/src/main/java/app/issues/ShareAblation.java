package app.issues;

import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.ProjectBootstrap;
import app.issues.model.References;
import app.model.Parallel;
import app.study.model.Bootstrap;
import app.study.model.Score;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * A diagnostic of the first-commit study, outside the fourteen declared models: what the first
 * commit's share of the issue's lines ({@link FirstCommit#FIRST_SHARE}, decision 16a) adds to the
 * main reading of the own retrospective model. The model is chosen twice by the selection's own
 * procedure, with the share and without it, on the inner fitting rows at or below the fence of
 * the k the selection chose, every choice scored on the usual-flow inner validation issues: per
 * combination of the similarity medians the declared sweep, the combination of lowest error kept,
 * ties to the earlier one ({@link ElasticNetSelection}). Each choice is refitted as the inner
 * report refits it and read on the usual flow and on every inner validation issue: the error,
 * Spearman, pairwise ordering, and the paired differences with their intervals resampling whole
 * projects ({@link ProjectBootstrap}). Reads the inner fitting and validation rows only. Chooses
 * nothing for any other step.
 */
public final class ShareAblation {

  /** The two variants' names in the comparisons. */
  public static final String WITH = ElasticNetSelection.RETROSPECTIVE;

  public static final String WITHOUT = ElasticNetSelection.RETROSPECTIVE + "_without_share";

  /** What the diagnostic is, for its report. */
  public static final String RULE = "a diagnostic outside the fourteen declared models: the main "
      + "reading of the own retrospective model chosen twice by the selection's procedure, with "
      + "and without the first commit's share of the issue's lines (decision 16a), on the inner "
      + "fitting rows at or below the chosen k's fence, every choice scored on the usual-flow "
      + "inner validation issues; each refitted as the inner report refits it and read on the "
      + "usual flow and on every inner validation issue; not part of the model set or of the "
      + "outer test";

  /** One variant's choice: its combination, alpha and lambda. */
  public record Chosen(String name, ElasticNetSelection.Combination combination, double alpha,
      double lambda, double selectionMae) {}

  /**
   * One reading of both variants.
   *
   * @param gap MAE(with) - MAE(without), with its interval
   * @param ordering Spearman and pairwise, with minus without, with their intervals
   */
  public record Reading(TailCut.Reading reading, int rows, Score with, Score without,
      ProjectBootstrap.Gap gap, ProjectBootstrap.Ordering ordering) {}

  public record Result(double k, double fence, int fitRows, Chosen with, Chosen without,
      List<Reading> readings) {}

  private ShareAblation() {}

  /**
   * Both variants chosen and read, on {@code entries}, {@link BusinessDays#population}'s rows of
   * the first-commit population, at the tail cut's {@code k}.
   */
  public static Result run(final List<RowCache.Entry> entries, final double k, final int threads) {
    final Population p = Population.FIRST_COMMIT;
    final List<Cell> every = ElasticNetSelection.everyCell(Augmentation.Variant.INNER, p);
    final Fitting.Sides whole = ElasticNetSelection.flagged(
        Fitting.sides(BusinessDays.counted(entries), Fitting.Mode.INNER, p), every);
    // as the selection: both fences on the inner fitting set before any cut
    final double usualFence = TailCut.fence(whole.fitUnits(), TailCut.USUAL_FLOW_K);
    final Fitting.Sides usual = TailCut.usualFlow(whole, usualFence);
    final double fence = TailCut.fence(usual.fitUnits(), k);
    final Fitting.Sides cut = TailCut.cut(usual, fence);
    final References refs = References.learnedOn(cut.fitUnits());
    final double constantMae = Fitting.mae(refs.constant(cut.observed().length), cut.observed());
    final double projectMae = Fitting.mae(refs.projectMedian(cut.scoreUnits()), cut.observed());

    final Chosen with = choose(WITH, true, cut, projectMae, constantMae, threads);
    final Chosen without = choose(WITHOUT, false, cut, projectMae, constantMae, threads);

    // the refit and the readings, as the inner report reads them
    final Fitting.Sides fitted = TailCut.cut(whole, TailCut.fence(whole.fitUnits(), k));
    final List<Reading> readings = new ArrayList<>();
    for (final TailCut.Reading reading : List.of(TailCut.Reading.USUAL_FLOW,
        TailCut.Reading.ALL)) {
      final Fitting.Sides sides = reading == TailCut.Reading.ALL ? fitted
          : TailCut.usualFlow(fitted, usualFence);
      final References r = References.learnedOn(sides.fitUnits());
      final double[] observed = sides.observed();
      final double constant = Fitting.mae(r.constant(observed.length), observed);
      final double project = Fitting.mae(r.projectMedian(sides.scoreUnits()), observed);
      final Fitting.Fitted a = refit(with, true, sides, project, constant);
      final Fitting.Fitted b = refit(without, false, sides, project, constant);
      final List<String> projects = sides.projects();
      readings.add(new Reading(reading, observed.length, a.score(), b.score(),
          ProjectBootstrap.compare(projects, WITH, Bootstrap.errors(a.predicted(), observed),
              WITHOUT, Bootstrap.errors(b.predicted(), observed), threads),
          ProjectBootstrap.ordering(projects, WITH, a.predicted(), WITHOUT, b.predicted(),
              observed, threads)));
    }
    return new Result(k, fence, cut.fitUnits().size(), with, without, List.copyOf(readings));
  }

  /** The main reading of the own retrospective model at {@code c}, with or without the share. */
  static Cell cell(final ElasticNetSelection.Combination c, final boolean share) {
    final Cell cell = ElasticNetSelection.cells(c, Augmentation.Variant.INNER,
        Population.FIRST_COMMIT).get(ElasticNetSelection.MODELS.indexOf(WITH));
    if (share) {
      return cell;
    }
    return new Cell(WITHOUT, cell.family(), cell.scenario(), cell.attributes().stream()
        .filter(a -> !a.name().equals(FirstCommit.FIRST_SHARE)).toList());
  }

  private static Chosen choose(final String name, final boolean share, final Fitting.Sides cut,
      final double projectMae, final double constantMae, final int threads) {
    final List<ElasticNetSelection.Combination> all = ElasticNetSelection.Combination.all();
    final List<Callable<Fitting.Fitted>> jobs = new ArrayList<>(all.size());
    for (final ElasticNetSelection.Combination c : all) {
      jobs.add(() -> Fitting.sweep(ElasticNetSelection.kept(cell(c, share), cut.fitRows(),
          "inner fitting").cell(), cut, projectMae, constantMae, 1));
    }
    final List<Fitting.Fitted> swept = Parallel.map(threads, jobs);
    int best = 0;
    for (int i = 1; i < swept.size(); i++) {
      // strictly lower, as the selection keeps its combinations: a tie stays with the earlier
      if (swept.get(i).score().mae() < swept.get(best).score().mae()) {
        best = i;
      }
    }
    final Fitting.Fitted f = swept.get(best);
    return new Chosen(name, all.get(best), f.alpha(), f.lambda(), f.score().mae());
  }

  private static Fitting.Fitted refit(final Chosen chosen, final boolean share,
      final Fitting.Sides sides, final double projectMae, final double constantMae) {
    final ElasticNetSelection.Kept kept = ElasticNetSelection.kept(
        cell(chosen.combination(), share), sides.fitRows(), "inner fitting");
    return Fitting.frozen(kept.cell(), sides, chosen.alpha(), chosen.lambda(), projectMae,
        constantMae);
  }
}
