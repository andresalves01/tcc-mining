package app.pipeline;

import app.pullrequests.PriorStudyFeatures;
import app.pullrequests.Family;
import app.pullrequests.RowCache;
import app.pullrequests.Unit;
import app.pullrequests.model.Bootstrap;
import app.pullrequests.model.Breakdown;
import app.pullrequests.model.Cell;
import app.pullrequests.model.Prepared;
import app.pullrequests.model.Score;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reserved rows scored with the corrected columns, as {@code REGISTRO-DA-CORRECAO.md} laid out:
 * the three families the correction does not touch keep their registered predictions, the two it
 * does are refitted on the whole training side with the corrected columns and the pairs the addendum
 * froze, and all five are read the same ways as the registered result. Searches for nothing.
 */
public final class PullRequestCorrectedReservedTask {

  /** One specification as it enters the corrected table. */
  public record Row(String label, boolean corrected, double[] hours, Score score,
      Breakdown.Errors errors, List<Breakdown.Band> bands, Breakdown.Body body,
      Map<String, Double> weights) {}

  public record Report(PullRequestReservedTask.Report registered, List<Row> valid,
      List<Row> registeredAffected, List<Row> references, List<Bootstrap.Comparison> byRows,
      List<PullRequestAuditTask.Clustered> maeByProjects,
      List<PullRequestAuditTask.Clustered> gainByProjects,
      List<PullRequestAuditTask.Clustered> spearmanByProjects,
      List<PullRequestAuditTask.WithinProject> withinProject, Map<String, Double> tailShare,
      List<Unit> units) {}

  private final Progress progress;

  public PullRequestCorrectedReservedTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries, final PriorStudyFeatures registeredA4,
      final PriorStudyFeatures correctedA4) {

    final PullRequestReservedTask.Report reg = new PullRequestReservedTask(progress).run(entries,
        registeredA4);
    final double[] observed = reg.observedHours();
    final double constantMae = PullRequestAuditTask.mae(reg.constantHours(), observed);
    final double baselineMae = PullRequestAuditTask.mae(reg.baselineHours(), observed);
    final Prepared corrected = Prepared.reserved(entries, correctedA4);
    if (!java.util.Arrays.equals(corrected.observed(), observed)) {
      throw new IllegalStateException("linhas reservadas fora de ordem");
    }

    final List<Row> valid = new ArrayList<>();
    final List<Row> affectedRegistered = new ArrayList<>();
    for (final PullRequestReservedTask.Result r : reg.results()) {
      final boolean affected = r.family() == Family.COMBINADO_MAIS_ARTIGO4
          || r.family() == Family.CONTRIBUICAO_PROPRIA;
      final Map<String, Double> w = new LinkedHashMap<>();
      r.weights().forEach(x -> w.put(x.column(), x.coefficient()));
      final Row row = row(r.family().name() + (affected ? " (registrada)" : ""), false,
          r.predictedHours(), observed, baselineMae, constantMae, w);
      (affected ? affectedRegistered : valid).add(row);
    }
    for (final PullRequestReservedTask.Frozen f : PullRequestCorrectedSweepTask.FROZEN_CORRIGIDO) {
      progress.item(1, 1, f.family().name(), "reajuste com as colunas corrigidas");
      final Cell cell = Cell.of(f.family(), PullRequestReservedTask.PROFILE);
      valid.add(row(f.family().name() + " (corrigida)", true,
          corrected.predict(cell, f.alpha(), f.lambda()), observed, baselineMae, constantMae,
          corrected.weights(cell, f.alpha(), f.lambda())));
    }
    valid.sort(java.util.Comparator.comparingDouble(r -> r.score().maeHours()));
    final List<Row> refs = List.of(
        row("mediana do projeto", false, reg.baselineHours(), observed, baselineMae, constantMae,
            Map.of()),
        row("constante (mediana do treino)", false, reg.constantHours(), observed, baselineMae,
            constantMae, Map.of()));

    final List<Bootstrap.Comparison> byRows = Bootstrap.allPairs(
        valid.stream().map(Row::label).toList(),
        valid.stream().map(r -> Bootstrap.errors(r.hours(), observed)).toList());

    final List<Unit> units = corrected.scoreUnits();
    final Map<String, List<Integer>> rowsOf = new LinkedHashMap<>();
    for (int i = 0; i < units.size(); i++) {
      rowsOf.computeIfAbsent(units.get(i).project(), k -> new ArrayList<>()).add(i);
    }
    final List<List<Integer>> clusters = new ArrayList<>(rowsOf.values());
    final List<PullRequestAuditTask.Named> named = valid.stream()
        .map(r -> new PullRequestAuditTask.Named(r.label(), r.hours())).toList();
    final List<PullRequestAuditTask.Clustered> mae = new ArrayList<>();
    for (int a = 0; a < named.size(); a++) {
      for (int b = a + 1; b < named.size(); b++) {
        mae.add(PullRequestAuditTask.clusteredMae(named.get(a), named.get(b), clusters, observed));
      }
    }
    final PullRequestAuditTask.Named constant =
        new PullRequestAuditTask.Named("constante", reg.constantHours());
    final List<PullRequestAuditTask.Clustered> gain = new ArrayList<>();
    gain.add(PullRequestAuditTask.clusteredGain(
        new PullRequestAuditTask.Named("mediana do projeto", reg.baselineHours()), constant, clusters,
        observed));
    named.forEach(n -> gain.add(PullRequestAuditTask.clusteredGain(n, constant, clusters, observed)));
    final PullRequestAuditTask.Named union = named.stream()
        .filter(n -> n.label().startsWith(Family.COMBINADO_MAIS_ARTIGO4.name())).findFirst()
        .orElseThrow();
    final List<PullRequestAuditTask.Clustered> spearman = new ArrayList<>();
    for (final PullRequestAuditTask.Named other : named) {
      if (other != union) {
        spearman.add(PullRequestAuditTask.clusteredSpearman(union, other, clusters, observed));
      }
    }
    final List<PullRequestAuditTask.WithinProject> within = new ArrayList<>();
    for (final Row r : concat(refs, valid)) {
      within.add(PullRequestAuditTask.withinProject(new PullRequestAuditTask.Named(r.label(), r.hours()),
          rowsOf, observed));
    }
    final Map<String, Double> tail = new LinkedHashMap<>();
    final int kept = Breakdown.errors(reg.constantHours(), observed).trimmedN();
    final Integer[] order = new Integer[observed.length];
    for (int i = 0; i < order.length; i++) {
      order[i] = i;
    }
    java.util.Arrays.sort(order, (a, b) -> Double.compare(observed[b], observed[a]));
    for (final Row r : concat(refs, valid)) {
      double total = 0;
      double slow = 0;
      for (int i = 0; i < observed.length; i++) {
        total += Math.abs(r.hours()[i] - observed[i]);
      }
      for (int k = 0; k < observed.length - kept; k++) {
        slow += Math.abs(r.hours()[order[k]] - observed[order[k]]);
      }
      tail.put(r.label(), slow / total);
    }
    return new Report(reg, List.copyOf(valid), List.copyOf(affectedRegistered), refs, byRows,
        mae, gain, spearman, within, tail, units);
  }

  private static Row row(final String label, final boolean corrected, final double[] hours,
      final double[] observed, final double baselineMae, final double constantMae,
      final Map<String, Double> weights) {
    return new Row(label, corrected, hours,
        Score.of(label, hours, observed, baselineMae, constantMae),
        Breakdown.errors(hours, observed), Breakdown.byTruth(hours, observed),
        Breakdown.body(hours, observed, 0.25, 0.90), weights);
  }

  private static <T> List<T> concat(final List<T> a, final List<T> b) {
    final List<T> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }
}
