package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pullrequests.PriorStudyFeatures;
import app.pullrequests.RowCache;
import app.pullrequests.model.Breakdown;
import app.pullrequests.model.Score;
import app.pipeline.PullRequestCorrectedReservedTask;
import app.pipeline.PullRequestCorrectedSweepTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores the reserved rows with the corrected columns, once, as registered in
 * {@code REGISTRO-DA-CORRECAO.md}. Reads CACHE, A4 and A4_CORRIGIDO; writes the predictions to
 * PREVISOES and appends the run to PR_DB.
 */
public final class PullRequestCorrectedReservedCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final List<RowCache.Entry> entries;
    final PriorStudyFeatures registered;
    final PriorStudyFeatures corrected;
    try {
      entries = RowCache.read(Path.of(System.getenv("CACHE")));
      registered = PriorStudyFeatures.read(Path.of(System.getenv("A4")));
      corrected = PriorStudyFeatures.read(Path.of(System.getenv("A4_CORRIGIDO")));
    } catch (final IOException | NullPointerException e) {
      throw new CommandFailedException(List.of("  CACHE, A4, A4_CORRIGIDO e PREVISOES: " + e));
    }
    final PullRequestCorrectedReservedTask.Report r = new PullRequestCorrectedReservedTask(
        context.progress()).run(entries, registered, corrected);

    System.out.printf("%n  PULL REQUESTS -- RESERVADO COM AS COLUNAS DO ESTUDO ANTERIOR CORRIGIDAS%n");
    System.out.printf("  registro da correcao: REGISTRO-DA-CORRECAO.md (parametros no adendo)%n");
    PullRequestCorrectedSweepTask.FROZEN_CORRIGIDO.forEach(f -> System.out.printf(
        "    %-24s alpha=%.2f lambda=%.6g (congelados antes desta execucao)%n", f.family(),
        f.alpha(), f.lambda()));
    System.out.printf("  Yu, Chouchen e Yu + Chouchen: previsoes registradas, inalteradas%n");
    System.out.printf("  treino %,d linhas, reservado %,d linhas%n", r.registered().trainingRows(),
        r.registered().reservedRows());

    System.out.printf("%n  TABELA VALIDA (MAE, ganho sobre a constante, Spearman, par a par)%n");
    for (final var row : concat(r.references(), r.valid())) {
      print(row.score(), row.corrected());
    }
    System.out.printf("  -- ao lado, as versoes registradas das duas familias afetadas:%n");
    r.registeredAffected().forEach(row -> print(row.score(), false));

    System.out.printf("%n  RUIDO, REAMOSTRAGEM PAREADA POR LINHAS (2.000), as cinco validas%n");
    r.byRows().forEach(c -> System.out.printf("    %-36s x %-36s vence %5.1f%%  dif %+7.2f h  "
        + "IC [%+7.2f, %+7.2f] %s%n", c.a(), c.b(), 100 * c.winRate(), c.meanGap(), c.lowGap(),
        c.highGap(), c.indistinguishable() ? "EMPATE" : "exclui zero"));
    System.out.printf("%n  REAMOSTRANDO PROJETOS INTEIROS (2.000)%n  diferenca de MAE:%n");
    r.maeByProjects().forEach(c -> System.out.printf("    %-36s - %-36s %+7.2f IC [%+7.2f, %+7.2f] %s%n",
        c.a(), c.b(), c.point(), c.low(), c.high(), c.excludesZero() ? "exclui zero" : "contem zero"));
    System.out.printf("  ganho sobre a constante (%%):%n");
    r.gainByProjects().forEach(c -> System.out.printf("    %-36s %+7.2f IC [%+7.2f, %+7.2f] %s%n",
        c.a(), c.point(), c.low(), c.high(), c.excludesZero() ? "exclui zero" : "contem zero"));
    System.out.printf("  diferenca de Spearman da uniao corrigida contra cada outra:%n");
    r.spearmanByProjects().forEach(c -> System.out.printf("    %-36s - %-36s %+7.3f IC [%+7.3f, %+7.3f] %s%n",
        c.a(), c.b(), c.point(), c.low(), c.high(), c.excludesZero() ? "exclui zero" : "contem zero"));

    System.out.printf("%n  DENTRO DE CADA PROJETO%n");
    r.withinProject().forEach(w -> System.out.printf("    %-36s projetos %d  somado %5.1f%%  "
        + "media (>=20 linhas, %d projetos) %5.1f%%  rho medio %.3f%n", w.label(), w.projects(),
        100 * w.pooled(), w.projectsForMean(), 100 * w.meanPairwise(), w.meanSpearman()));

    System.out.printf("%n  ONDE O ERRO MORA%n");
    for (final var row : concat(r.references(), r.valid())) {
      final Breakdown.Errors e = row.errors();
      System.out.printf("    %-36s media %7.1f  p50 %6.1f  p90 %7.1f  p99 %7.1f  sem o decimo "
          + "mais lento %6.1f  fracao do erro no decimo mais lento %4.1f%%%n", row.label(),
          e.mae(), e.p50(), e.p90(), e.p99(), e.trimmedMae(), 100 * r.tailShare().get(row.label()));
    }
    System.out.printf("  por faixa da duracao real (MAE h):%n");
    for (final var row : concat(r.references(), r.valid())) {
      final StringBuilder b = new StringBuilder();
      row.bands().forEach(x -> b.append(String.format("  %s: %.1f", x.label(), x.mae())));
      System.out.printf("    %-36s%s%n", row.label(), b);
    }
    System.out.printf("  corpo da distribuicao:%n");
    for (final var row : concat(r.references(), r.valid())) {
      final Breakdown.Body x = row.body();
      System.out.printf("    %-36s n=%d  %.1f a %.1f h  MAE %.1f  rho %.3f  par a par %.1f%%%n",
          row.label(), x.n(), x.lowHours(), x.highHours(), x.mae(), x.spearman(),
          100 * x.pairwise());
    }

    System.out.printf("%n  PESOS DAS DUAS FAMILIAS CORRIGIDAS (reajuste no treino inteiro)%n");
    for (final var row : r.valid()) {
      if (!row.corrected()) {
        continue;
      }
      System.out.printf("  -- %s%n", row.label());
      row.weights().entrySet().stream()
          .filter(e -> Math.abs(e.getValue()) >= 1e-4)
          .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
          .forEach(e -> System.out.printf("     %-40s %+8.4f%n", e.getKey(), e.getValue()));
    }

    final String out = System.getenv("PREVISOES");
    if (out != null) {
      final StringBuilder tsv = new StringBuilder("id\tprojeto\tobservado_h");
      final List<double[]> cols = new ArrayList<>();
      for (final var row : concat(r.references(), concat(r.valid(), r.registeredAffected()))) {
        tsv.append('\t').append(row.label().replace(' ', '_'));
        cols.add(row.hours());
      }
      tsv.append('\n');
      final double[] observed = r.registered().observedHours();
      for (int i = 0; i < observed.length; i++) {
        tsv.append(r.units().get(i).pullRequestId()).append('\t')
            .append(r.units().get(i).project()).append('\t').append(observed[i]);
        for (final double[] c : cols) {
          tsv.append('\t').append(c[i]);
        }
        tsv.append('\n');
      }
      try {
        Files.writeString(Path.of(out), tsv.toString());
      } catch (final IOException e) {
        throw new CommandFailedException(List.of("  nao consegui gravar " + out));
      }
      System.out.printf("%n  previsoes gravadas em %s%n", out);
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("estagio", "reservado com as colunas do estudo anterior corrigidas (REGISTRO-DA-CORRECAO.md)");
    run.put("parametros_congelados", PullRequestCorrectedSweepTask.FROZEN_CORRIGIDO.toString());
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final var row : concat(r.references(), concat(r.valid(), r.registeredAffected()))) {
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("rotulo", row.label());
      m.put("mae_horas", row.score().maeHours());
      m.put("ganho_sobre_constante_percentual", row.score().gainOverConstantPercent());
      m.put("spearman", row.score().spearman());
      m.put("acerto_par_a_par", row.score().pairwise());
      rows.add(m);
    }
    System.out.printf("  execucao registrada em PR_DB: %s%n",
        context.prResults().record(run, rows));
  }

  private static void print(final Score s, final boolean corrected) {
    System.out.printf("  %-38s MAE %8.2f  vs cte %+6.2f%%  Spearman %.3f  par a par %5.1f%%%s%n",
        s.label(), s.maeHours(), s.gainOverConstantPercent(), s.spearman(), 100 * s.pairwise(),
        corrected ? "   <- corrigida" : "");
  }

  private static <T> List<T> concat(final List<T> a, final List<T> b) {
    final List<T> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }
}
