package app.pipeline;

import app.model.Metrics;
import app.pullrequests.PriorStudyFeatures;
import app.pullrequests.Attribute;
import app.pullrequests.Family;
import app.pullrequests.RowCache;
import app.pullrequests.Unit;
import app.pullrequests.model.Breakdown;
import app.pullrequests.model.Cell;
import app.pullrequests.model.Prepared;
import app.pullrequests.model.Score;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The audit of 28/09/2026: the reserved result recomputed, checked against itself, and read in the
 * ways the correction handout asked for. <b>Everything here after the first step is an analysis
 * made after the reserved set was opened, and is labelled so wherever it is printed.</b>
 *
 * <p>It searches for nothing and changes nothing that was frozen. Step one runs the registered
 * {@link PullRequestReservedTask} unchanged -- a third execution of the same deterministic code, with the
 * same literals -- so its summary can be compared, at full precision, with the two executions
 * stored in {@code smartshark_paper5}. Step two refits the five frozen specifications through
 * {@link Prepared} and refuses to continue unless every prediction is identical to step one's. Only
 * then are the new readings computed, on those same predictions.
 */
public final class PullRequestAuditTask {

  /**
   * The columns of the previous paper's specification that the temporal audit found reading
   * information that was not visible at {@code t0}. See {@code correcoes-2026-09-28/}.
   *
   * <ul>
   *   <li>{@code pr_project_wait}: waits that ended in an examination after {@code t0}, over pull
   *       requests included because they would be merged later;
   *   <li>{@code pr_familiarity}, {@code pr_familiarity_share}: earlier pull requests counted
   *       because they would be merged, some after {@code t0};
   *   <li>{@code iss_dev}, {@code iss_team}: every open pull request they count is one that would
   *       be merged after {@code t0} -- that is what being a unit means;
   *   <li>{@code loc_tot_java}, {@code a4_loc_por_commit}, {@code extra_dev_commits},
   *       {@code extra_dev_tenure_days}, {@code mu_l}, {@code s_l}: the commit history is cut by
   *       authorship date, and a fork's commits are authored before the opening but reach the
   *       repository only at the merge -- very often the pull request's own.
   * </ul>
   *
   * <p>{@code extra_team_size} was in this set when {@code 83-auditoria-final.txt} was produced, by
   * mistake: the pull-request pipeline takes the active team from who opened pull requests, which is
   * known at t0. Removed on 28/09/2026; the sensitivity in that output is superseded by the
   * correction of the columns.
   */
  public static final Set<String> VAZADAS = Set.of("pr_project_wait", "pr_familiarity",
      "pr_familiarity_share", "iss_dev", "iss_team", "loc_tot_java", "a4_loc_por_commit",
      "extra_dev_commits", "extra_dev_tenure_days", "mu_l", "s_l");

  public static final int DRAWS = 2_000;
  public static final long SEED = 20260927L;

  /** A named prediction over the reserved rows, in their order. */
  public record Named(String label, double[] hours) {}

  /** Ordering computed inside each project, then aggregated. */
  public record WithinProject(String label, int projects, long comparablePairs, double pooled,
      int projectsForMean, double meanPairwise, double meanSpearman, int projectsWithSpearman) {}

  /** A difference between two predictions with an interval that resamples whole projects. */
  public record Clustered(String a, String b, double point, double low, double high) {

    public boolean excludesZero() {
      return low > 0 || high < 0;
    }
  }

  /** One specification without the columns that read the future, fitted with its frozen pair. */
  public record Sensitivity(Family family, List<String> removed, Score reserved, Score inner) {}

  /**
   * One family read under the strict profile on the reserved rows -- never registered, so an
   * analysis after the opening -- with the attributes that separate it from the frozen profile.
   */
  public record Strict(Family family, double alpha, double lambda, List<String> removed,
      Score reserved, Score inner) {}

  public record Report(
      PullRequestReservedTask.Report frozen,
      List<String> equality,
      List<Unit> reservedUnits,
      List<Named> specifications,
      List<Named> references,
      int meanFellBack,
      Map<String, Score> scores,
      Map<String, Double> tailShare,
      List<WithinProject> withinProject,
      List<Clustered> maeDifferences,
      List<Clustered> spearmanDifferences,
      List<Clustered> gainOverConstant,
      List<Sensitivity> sensitivity,
      List<Strict> strict,
      Score.Outcome innerOutcome) {}

  private final Progress progress;

  public PullRequestAuditTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries, final PriorStudyFeatures priorStudy) {
    // -- 1. the registered code, unchanged -----------------------------------------------------
    progress.detail("%s", "passo 1: a tarefa registrada do reservado, sem nenhuma alteracao");
    final PullRequestReservedTask.Report frozen = new PullRequestReservedTask(progress).run(entries, priorStudy);

    // -- 2. the same five fits through Prepared, which must agree exactly --------------------------
    progress.detail("%s", "passo 2: as cinco especificacoes refeitas por Prepared");
    final Prepared reserved = Prepared.reserved(entries, priorStudy);
    if (!Arrays.equals(reserved.observed(), frozen.observedHours())) {
      throw new IllegalStateException("as linhas reservadas nao estao na mesma ordem");
    }
    final List<String> equality = new ArrayList<>();
    final Map<Family, double[]> byFamily = new LinkedHashMap<>();
    for (final PullRequestReservedTask.Frozen f : PullRequestReservedTask.FROZEN) {
      final double[] again = reserved.predict(Cell.of(f.family(), PullRequestReservedTask.PROFILE),
          f.alpha(), f.lambda());
      final double[] registered = frozen.results().stream()
          .filter(r -> r.family() == f.family()).findFirst().orElseThrow().predictedHours();
      double worst = 0;
      for (int i = 0; i < again.length; i++) {
        worst = Math.max(worst, Math.abs(again[i] - registered[i]));
      }
      if (!Arrays.equals(again, registered)) {
        throw new IllegalStateException(f.family() + ": Prepared nao reproduz a tarefa registrada;"
            + " maior diferenca " + worst + " h. A auditoria para aqui.");
      }
      equality.add(f.family() + ": " + again.length + " previsoes identicas, bit a bit");
      byFamily.put(f.family(), registered);
    }

    final List<Unit> units = reserved.scoreUnits();
    final double[] observed = frozen.observedHours();
    final List<String> projects = units.stream().map(Unit::project).toList();

    // -- 3. references: the two registered, and the historical mean per project, new -----------
    final Map<String, List<Double>> trainHours = new HashMap<>();
    double trainSum = 0;
    for (final Unit u : reserved.fitUnits()) {
      trainHours.computeIfAbsent(u.project(), k -> new ArrayList<>()).add(u.durationHours());
      trainSum += u.durationHours();
    }
    final double globalMean = trainSum / reserved.fitUnits().size();
    final double[] projectMean = new double[units.size()];
    int meanFellBack = 0;
    for (int i = 0; i < projectMean.length; i++) {
      final List<Double> hours = trainHours.get(units.get(i).project());
      if (hours == null) {
        projectMean[i] = globalMean;
        meanFellBack++;
      } else {
        projectMean[i] = hours.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
      }
    }
    final List<Named> references = List.of(
        new Named("mediana do projeto (registrada)", frozen.baselineHours()),
        new Named("constante = mediana do treino (registrada)", frozen.constantHours()),
        new Named("media do projeto (NOVA, posterior)", projectMean));
    final List<Named> specifications = new ArrayList<>();
    byFamily.forEach((family, hours) -> specifications.add(new Named(family.name(), hours)));

    final double constantMae = mae(frozen.constantHours(), observed);
    final double baselineMae = mae(frozen.baselineHours(), observed);
    final Map<String, Score> scores = new LinkedHashMap<>();
    final Map<String, Double> tail = new LinkedHashMap<>();
    final int kept = Breakdown.errors(frozen.constantHours(), observed).trimmedN();
    final Integer[] bySlowness = new Integer[observed.length];
    for (int i = 0; i < bySlowness.length; i++) {
      bySlowness[i] = i;
    }
    Arrays.sort(bySlowness, (a, b) -> Double.compare(observed[b], observed[a]));
    for (final Named n : concat(references, specifications)) {
      scores.put(n.label(), Score.of(n.label(), n.hours(), observed, baselineMae, constantMae));
      double total = 0;
      double slowest = 0;
      for (int i = 0; i < observed.length; i++) {
        total += Math.abs(n.hours()[i] - observed[i]);
      }
      for (int k = 0; k < observed.length - kept; k++) {
        final int i = bySlowness[k];
        slowest += Math.abs(n.hours()[i] - observed[i]);
      }
      tail.put(n.label(), slowest / total);
    }

    // -- 4. ordering inside each project -------------------------------------------------------
    final Map<String, List<Integer>> rowsOf = new LinkedHashMap<>();
    for (int i = 0; i < projects.size(); i++) {
      rowsOf.computeIfAbsent(projects.get(i), k -> new ArrayList<>()).add(i);
    }
    final List<WithinProject> within = new ArrayList<>();
    for (final Named n : concat(references, specifications)) {
      within.add(withinProject(n, rowsOf, observed));
    }

    // -- 5. intervals that resample whole projects ----------------------------------------------
    progress.detail("%s", "passo 5: reamostragem por projetos inteiros");
    final List<List<Integer>> clusters = new ArrayList<>(rowsOf.values());
    final List<Clustered> maeDiff = new ArrayList<>();
    final List<Clustered> spearmanDiff = new ArrayList<>();
    for (int a = 0; a < specifications.size(); a++) {
      for (int b = a + 1; b < specifications.size(); b++) {
        maeDiff.add(clusteredMae(specifications.get(a), specifications.get(b), clusters,
            observed));
      }
    }
    final Named union = specifications.stream()
        .filter(n -> n.label().equals(Family.COMBINADO_MAIS_ARTIGO4.name())).findFirst()
        .orElseThrow();
    for (final Named other : specifications) {
      if (other != union) {
        spearmanDiff.add(clusteredSpearman(union, other, clusters, observed));
      }
    }
    final List<Clustered> gain = new ArrayList<>();
    final Named constant = references.get(1);
    for (final Named n : concat(List.of(references.get(0), references.get(2)), specifications)) {
      gain.add(clusteredGain(n, constant, clusters, observed));
    }

    // -- 6. the two families without the columns that read the future --------------------------
    progress.detail("%s", "passo 6: sensibilidade sem as colunas com vazamento temporal");
    final Prepared inner = Prepared.inner(entries, priorStudy);
    final double innerConstantMae = mae(constantOf(inner), inner.observed());
    final double innerBaselineMae = mae(app.pullrequests.model.Baseline.learnedOn(inner.fitUnits())
        .projectOf(inner.scoreUnits()), inner.observed());
    final List<Sensitivity> sensitivity = new ArrayList<>();
    for (final PullRequestReservedTask.Frozen f : PullRequestReservedTask.FROZEN) {
      final Cell full = Cell.of(f.family(), PullRequestReservedTask.PROFILE);
      final Set<String> removed = new HashSet<>();
      for (final Attribute attribute : full.attributes()) {
        if (VAZADAS.contains(attribute.name())) {
          removed.add(attribute.name());
        }
      }
      if (removed.isEmpty()) {
        continue;
      }
      final Cell cut = new Cell(f.family(), PullRequestReservedTask.PROFILE, removed);
      final double[] onReserved = reserved.predict(cut, f.alpha(), f.lambda());
      final double[] onInner = inner.predict(cut, f.alpha(), f.lambda());
      sensitivity.add(new Sensitivity(f.family(), removed.stream().sorted().toList(),
          Score.of(cut.label() + " | reservado", onReserved, observed, baselineMae, constantMae),
          Score.of(cut.label() + " | selecao interna", onInner, inner.observed(),
              innerBaselineMae, innerConstantMae)));
    }

    // -- 7. the strict profile on the reserved rows, with the parameters the inner sweep chose ----
    progress.detail("%s", "passo 7: o perfil estrito no reservado (nunca registrado)");
    final List<Strict> strict = new ArrayList<>();
    for (final PullRequestReservedTask.Frozen f
        : PullRequestCheckTask.parametersFor(app.pullrequests.Profile.STRICT_T0)) {
      final Cell narrow = Cell.of(f.family(), app.pullrequests.Profile.STRICT_T0);
      final Set<String> narrowNames = new HashSet<>();
      narrow.attributes().forEach(a -> narrowNames.add(a.name()));
      final List<String> removed = Cell.of(f.family(), PullRequestReservedTask.PROFILE).attributes()
          .stream().map(Attribute::name).filter(n -> !narrowNames.contains(n)).toList();
      strict.add(new Strict(f.family(), f.alpha(), f.lambda(), removed,
          Score.of(narrow.label() + " | reservado", reserved.predict(narrow, f.alpha(),
              f.lambda()), observed, baselineMae, constantMae),
          Score.of(narrow.label() + " | selecao interna", inner.predict(narrow, f.alpha(),
              f.lambda()), inner.observed(), innerBaselineMae, innerConstantMae)));
    }

    return new Report(frozen, List.copyOf(equality), units, List.copyOf(specifications),
        references, meanFellBack, scores, tail, List.copyOf(within), List.copyOf(maeDiff),
        List.copyOf(spearmanDiff), List.copyOf(gain), List.copyOf(sensitivity),
        List.copyOf(strict), Score.Outcome.of(inner.observed()));
  }

  private static double[] constantOf(final Prepared prepared) {
    final double[] out = new double[prepared.scoreUnits().size()];
    Arrays.fill(out, app.pullrequests.model.Baseline.learnedOn(prepared.fitUnits()).constantHours());
    return out;
  }

  static WithinProject withinProject(final Named n,
      final Map<String, List<Integer>> rowsOf, final double[] observed) {

    long comparable = 0;
    double correct = 0;
    int projects = 0;
    int forMean = 0;
    double sumPairwise = 0;
    double sumSpearman = 0;
    int withSpearman = 0;
    for (final List<Integer> rows : rowsOf.values()) {
      final double[] p = new double[rows.size()];
      final double[] o = new double[rows.size()];
      for (int k = 0; k < p.length; k++) {
        p[k] = n.hours()[rows.get(k)];
        o[k] = observed[rows.get(k)];
      }
      long c = 0;
      double right = 0;
      for (int i = 0; i < p.length; i++) {
        for (int j = i + 1; j < p.length; j++) {
          if (o[i] == o[j]) {
            continue;
          }
          c++;
          if (p[i] == p[j]) {
            right += 0.5;
          } else if ((o[i] > o[j]) == (p[i] > p[j])) {
            right += 1;
          }
        }
      }
      if (c == 0) {
        continue;
      }
      projects++;
      comparable += c;
      correct += right;
      if (rows.size() >= MIN_ROWS_FOR_MEAN) {
        forMean++;
        sumPairwise += right / c;
        final double rho = Metrics.spearman(p, o);
        if (!Double.isNaN(rho)) {
          sumSpearman += rho;
          withSpearman++;
        }
      }
    }
    return new WithinProject(n.label(), projects, comparable, correct / comparable, forMean,
        forMean == 0 ? Double.NaN : sumPairwise / forMean, withSpearman == 0 ? Double.NaN
            : sumSpearman / withSpearman, withSpearman);
  }

  /** Projects with fewer rows than this are pooled but left out of the per-project mean. */
  public static final int MIN_ROWS_FOR_MEAN = 20;

  static Clustered clusteredMae(final Named a, final Named b,
      final List<List<Integer>> clusters, final double[] observed) {
    return clustered(a.label(), b.label(), clusters, observed.length,
        rows -> meanOver(rows, a.hours(), observed) - meanOver(rows, b.hours(), observed));
  }

  static Clustered clusteredGain(final Named model, final Named constant,
      final List<List<Integer>> clusters, final double[] observed) {
    return clustered(model.label(), constant.label(), clusters, observed.length, rows -> {
      final double ref = meanOver(rows, constant.hours(), observed);
      return 100.0 * (ref - meanOver(rows, model.hours(), observed)) / ref;
    });
  }

  static Clustered clusteredSpearman(final Named a, final Named b,
      final List<List<Integer>> clusters, final double[] observed) {
    return clustered(a.label(), b.label(), clusters, observed.length, rows -> {
      final double[] pa = new double[rows.size()];
      final double[] pb = new double[rows.size()];
      final double[] o = new double[rows.size()];
      for (int k = 0; k < pa.length; k++) {
        pa[k] = a.hours()[rows.get(k)];
        pb[k] = b.hours()[rows.get(k)];
        o[k] = observed[rows.get(k)];
      }
      return Metrics.spearman(pa, o) - Metrics.spearman(pb, o);
    });
  }

  private static Clustered clustered(final String a, final String b,
      final List<List<Integer>> clusters, final int n,
      final java.util.function.ToDoubleFunction<List<Integer>> statistic) {

    final List<Integer> all = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      all.add(i);
    }
    final double point = statistic.applyAsDouble(all);
    final double[] draws = new double[DRAWS];
    final Random random = new Random(SEED);
    for (int d = 0; d < DRAWS; d++) {
      final List<Integer> sample = new ArrayList<>(n);
      for (int c = 0; c < clusters.size(); c++) {
        sample.addAll(clusters.get(random.nextInt(clusters.size())));
      }
      draws[d] = statistic.applyAsDouble(sample);
    }
    Arrays.sort(draws);
    return new Clustered(a, b, point, draws[(int) (0.025 * DRAWS)],
        draws[(int) (0.975 * DRAWS) - 1]);
  }

  private static double meanOver(final List<Integer> rows, final double[] predicted,
      final double[] observed) {
    double sum = 0;
    for (final int i : rows) {
      sum += Math.abs(predicted[i] - observed[i]);
    }
    return sum / rows.size();
  }

  static double mae(final double[] predicted, final double[] observed) {
    double sum = 0;
    for (int i = 0; i < observed.length; i++) {
      sum += Math.abs(predicted[i] - observed[i]);
    }
    return sum / observed.length;
  }

  private static <T> List<T> concat(final List<T> a, final List<T> b) {
    final List<T> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }
}
