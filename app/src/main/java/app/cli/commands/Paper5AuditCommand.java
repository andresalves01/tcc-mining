package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper5.RowCache;
import app.paper5.model.Score;
import app.pipeline.Paper5AuditTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit of 28/09/2026: recomputes the reserved result with the registered code, proves it
 * identical, writes every prediction to {@code PREVISOES}, and prints the readings the correction
 * handout asked for -- every one of them an analysis made after the reserved set was opened.
 */
public final class Paper5AuditCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    final String a4 = System.getenv("A4");
    final String out = System.getenv("PREVISOES");
    if (cache == null || a4 == null || out == null) {
      throw new CommandFailedException(List.of("  CACHE, A4 e PREVISOES sao obrigatorios"));
    }
    final List<RowCache.Entry> entries;
    final app.paper5.Artigo4Features features;
    try {
      entries = RowCache.read(Path.of(cache));
      features = app.paper5.Artigo4Features.read(Path.of(a4));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  leitura falhou: " + e));
    }
    final Paper5AuditTask.Report r = new Paper5AuditTask(context.progress()).run(entries, features);

    System.out.printf("%n  ARTIGO 5 -- AUDITORIA DE 28/09/2026%n");
    System.out.printf("  TUDO ABAIXO DO PASSO 2 E ANALISE POSTERIOR A ABERTURA DO RESERVADO.%n");

    System.out.printf("%n  PASSO 1 -- a tarefa registrada, reexecutada sem alteracao (valores completos)%n");
    printFull("referencia: mediana do projeto", r.frozen().baseline());
    printFull("calibracao: constante", r.frozen().constant());
    r.frozen().results().forEach(res -> printFull(res.family().name(), res.score()));
    System.out.printf("  teste de ruido por linhas (o registrado), valores completos:%n");
    r.frozen().comparisons().forEach(c -> System.out.printf(
        "    %-24s x %-24s vence %.6f  dif %.10f  IC [%.10f, %.10f]%n", c.a(), c.b(),
        c.winRate(), c.meanGap(), c.lowGap(), c.highGap()));

    System.out.printf("%n  PASSO 2 -- as mesmas especificacoes por um segundo caminho%n");
    r.equality().forEach(e -> System.out.println("    " + e));

    System.out.printf("%n  PASSO 3 -- referencias e modelos nas MESMAS %,d linhas reservadas%n",
        r.reservedUnits().size());
    System.out.printf("  a media do projeto e NOVA: nao estava no registro previo. Congelada no treino,"
        + " sem atualizacao cronologica; projeto sem treino recebe a media global (%d linhas)%n",
        r.meanFellBack());
    System.out.printf("  empates: no par a par, previsao empatada vale meio acerto; Spearman de uma "
        + "previsao constante e indefinido (NaN), nao zero%n");
    System.out.printf("  %-44s %9s %9s %9s %9s %12s%n", "", "MAE h", "vs cte %", "Spearman",
        "par a par", "erro no 10%+");
    r.scores().forEach((label, s) -> System.out.printf("  %-44s %9.2f %9.2f %9.3f %8.1f%% %11.1f%%%n",
        label, s.maeHours(), s.gainOverConstantPercent(), s.spearman(), 100 * s.pairwise(),
        100 * r.tailShare().get(label)));

    System.out.printf("%n  PASSO 4 -- ordenacao DENTRO de cada projeto (exploratorio, posterior)%n");
    System.out.printf("  agregado: pares comparaveis somados de todos os projetos; media simples so "
        + "sobre projetos com >= %d linhas reservadas%n", Paper5AuditTask.MIN_ROWS_FOR_MEAN);
    System.out.printf("  %-44s %9s %11s %10s %10s %11s %11s%n", "", "projetos", "pares",
        "somado", "proj.>=20", "media p.a.p", "media rho");
    r.withinProject().forEach(w -> System.out.printf("  %-44s %9d %,11d %9.1f%% %10d %10.1f%% %11.3f%n",
        w.label(), w.projects(), w.comparablePairs(), 100 * w.pooled(), w.projectsForMean(),
        100 * w.meanPairwise(), w.meanSpearman()));

    System.out.printf("%n  PASSO 5 -- intervalos reamostrando PROJETOS INTEIROS (%d sorteios)%n",
        Paper5AuditTask.DRAWS);
    System.out.printf("  diferenca de MAE, primeiro menos segundo (h):%n");
    r.maeDifferences().forEach(c -> System.out.printf("    %-24s - %-24s %+8.2f  IC [%+8.2f, %+8.2f] %s%n",
        c.a(), c.b(), c.point(), c.low(), c.high(), c.excludesZero() ? "exclui zero" : "contem zero"));
    System.out.printf("  diferenca de Spearman da uniao dos tres contra cada outra:%n");
    r.spearmanDifferences().forEach(c -> System.out.printf("    %-24s - %-24s %+8.3f  IC [%+8.3f, %+8.3f] %s%n",
        c.a(), c.b(), c.point(), c.low(), c.high(), c.excludesZero() ? "exclui zero" : "contem zero"));
    System.out.printf("  ganho sobre a constante (%%):%n");
    r.gainOverConstant().forEach(c -> System.out.printf("    %-44s %+8.2f  IC [%+8.2f, %+8.2f] %s%n",
        c.a(), c.point(), c.low(), c.high(), c.excludesZero() ? "exclui zero" : "contem zero"));

    System.out.printf("%n  PASSO 6 -- sensibilidade: as familias SEM as colunas com vazamento temporal%n");
    System.out.printf("  mesmos alpha e lambda congelados; nao e novo modelo, e retirada de atributos%n");
    for (final Paper5AuditTask.Sensitivity s : r.sensitivity()) {
      System.out.printf("  %s sem %s%n", s.family(), s.removed());
      printFull("    reservado", s.reserved());
      printFull("    selecao interna", s.inner());
    }

    System.out.printf("%n  PASSO 7 -- o perfil ESTRITO no reservado: nunca registrado, analise posterior%n");
    System.out.printf("  parametros: os que a varredura da selecao interna escolheu para o perfil estrito%n");
    System.out.printf("  referencias nas mesmas linhas: reservado, mediana do projeto %.1f%% par a par; "
        + "selecao interna, ver 53-ablacao%n", 100 * r.frozen().baseline().pairwise());
    for (final Paper5AuditTask.Strict s : r.strict()) {
      System.out.printf("  %s  (alpha %.2f, lambda %.4g)%n    retirados do perfil congelado: %s%n",
          s.family(), s.alpha(), s.lambda(), s.removed());
      printFull("    reservado", s.reserved());
      printFull("    selecao interna", s.inner());
    }

    try {
      final StringBuilder tsv = new StringBuilder("id\tprojeto\taberto_em\tmesclado_em\tobservado_h");
      final List<double[]> columns = new ArrayList<>();
      for (final var n : r.references()) {
        tsv.append('\t').append(n.label().replace(' ', '_'));
        columns.add(n.hours());
      }
      for (final var n : r.specifications()) {
        tsv.append('\t').append(n.label());
        columns.add(n.hours());
      }
      tsv.append('\n');
      for (int i = 0; i < r.reservedUnits().size(); i++) {
        final var u = r.reservedUnits().get(i);
        tsv.append(u.pullRequestId()).append('\t').append(u.project()).append('\t')
            .append(u.openedAt()).append('\t').append(u.mergedAt()).append('\t')
            .append(u.durationHours());
        for (final double[] c : columns) {
          tsv.append('\t').append(c[i]);
        }
        tsv.append('\n');
      }
      Files.writeString(Path.of(out), tsv.toString());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui gravar " + out + ": " + e));
    }
    System.out.printf("%n  previsoes gravadas em %s%n", out);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("estagio", "auditoria pos-abertura: terceira execucao deterministica da tarefa "
        + "registrada, nao uma nova leitura do reservado para escolher nada");
    run.put("igualdade_bit_a_bit", r.equality());
    final List<Map<String, Object>> rows = new ArrayList<>();
    r.scores().forEach((label, s) -> {
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("rotulo", label);
      m.put("mae_horas", s.maeHours());
      m.put("spearman", s.spearman());
      m.put("acerto_par_a_par", s.pairwise());
      m.put("ganho_sobre_constante_percentual", s.gainOverConstantPercent());
      rows.add(m);
    });
    System.out.printf("  auditoria registrada em PAPER5_DB: %s%n",
        context.paper5Results().record(run, rows));
  }

  private static void printFull(final String label, final Score s) {
    System.out.printf("  %-36s n=%d MAE=%.10f Spearman=%.10f parapar=%.10f vsCte=%.4f%%%n",
        label, s.n(), s.maeHours(), s.spearman(), s.pairwise(), s.gainOverConstantPercent());
  }
}
