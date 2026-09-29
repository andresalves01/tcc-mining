package app.pipeline;

import app.paper5.Artigo4Features;
import app.paper5.Family;
import app.paper5.RowCache;
import app.paper5.Unit;
import app.paper5.model.Bootstrap;
import app.paper5.model.Breakdown;
import app.paper5.model.Cell;
import app.paper5.model.Prepared;
import app.paper5.model.Score;
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
public final class Paper5CorrectedReservedTask {

  /** One specification as it enters the corrected table. */
  public record Row(String label, boolean corrected, double[] hours, Score score,
      Breakdown.Errors errors, List<Breakdown.Band> bands, Breakdown.Body body,
      Map<String, Double> weights) {}

  public record Report(Paper5ReservedTask.Report registered, List<Row> valid,
      List<Row> registeredAffected, List<Row> references, List<Bootstrap.Comparison> byRows,
      List<Paper5AuditTask.Clustered> maeByProjects,
      List<Paper5AuditTask.Clustered> gainByProjects,
      List<Paper5AuditTask.Clustered> spearmanByProjects,
      List<Paper5AuditTask.WithinProject> withinProject, Map<String, Double> tailShare,
      List<Unit> units) {}

  private final Progress progress;

  public Paper5CorrectedReservedTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries, final Artigo4Features registeredA4,
      final Artigo4Features correctedA4) {

    final Paper5ReservedTask.Report reg = new Paper5ReservedTask(progress).run(entries,
        registeredA4);
    final double[] observed = reg.observedHours();
    final double constantMae = Paper5AuditTask.mae(reg.constantHours(), observed);
    final double baselineMae = Paper5AuditTask.mae(reg.baselineHours(), observed);
    final Prepared corrected = Prepared.reserved(entries, correctedA4);
    if (!java.util.Arrays.equals(corrected.observed(), observed)) {
      throw new IllegalStateException("linhas reservadas fora de ordem");
    }

    final List<Row> valid = new ArrayList<>();
    final List<Row> affectedRegistered = new ArrayList<>();
    for (final Paper5ReservedTask.Result r : reg.results()) {
      final boolean affected = r.family() == Family.COMBINADO_MAIS_ARTIGO4
          || r.family() == Family.CONTRIBUICAO_PROPRIA;
      final Map<String, Double> w = new LinkedHashMap<>();
      r.weights().forEach(x -> w.put(x.column(), x.coefficient()));
      final Row row = row(r.family().name() + (affected ? " (registrada)" : ""), false,
          r.predictedHours(), observed, baselineMae, constantMae, w);
      (affected ? affectedRegistered : valid).add(row);
    }
    for (final Paper5ReservedTask.Frozen f : Paper5CorrectedSweepTask.FROZEN_CORRIGIDO) {
      progress.item(1, 1, f.family().name(), "reajuste com as colunas corrigidas");
      final Cell cell = Cell.of(f.family(), Paper5ReservedTask.PROFILE);
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
    final List<Paper5AuditTask.Named> named = valid.stream()
        .map(r -> new Paper5AuditTask.Named(r.label(), r.hours())).toList();
    final List<Paper5AuditTask.Clustered> mae = new ArrayList<>();
    for (int a = 0; a < named.size(); a++) {
      for (int b = a + 1; b < named.size(); b++) {
        mae.add(Paper5AuditTask.clusteredMae(named.get(a), named.get(b), clusters, observed));
      }
    }
    final Paper5AuditTask.Named constant =
        new Paper5AuditTask.Named("constante", reg.constantHours());
    final List<Paper5AuditTask.Clustered> gain = new ArrayList<>();
    gain.add(Paper5AuditTask.clusteredGain(
        new Paper5AuditTask.Named("mediana do projeto", reg.baselineHours()), constant, clusters,
        observed));
    named.forEach(n -> gain.add(Paper5AuditTask.clusteredGain(n, constant, clusters, observed)));
    final Paper5AuditTask.Named union = named.stream()
        .filter(n -> n.label().startsWith(Family.COMBINADO_MAIS_ARTIGO4.name())).findFirst()
        .orElseThrow();
    final List<Paper5AuditTask.Clustered> spearman = new ArrayList<>();
    for (final Paper5AuditTask.Named other : named) {
      if (other != union) {
        spearman.add(Paper5AuditTask.clusteredSpearman(union, other, clusters, observed));
      }
    }
    final List<Paper5AuditTask.WithinProject> within = new ArrayList<>();
    for (final Row r : concat(refs, valid)) {
      within.add(Paper5AuditTask.withinProject(new Paper5AuditTask.Named(r.label(), r.hours()),
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
