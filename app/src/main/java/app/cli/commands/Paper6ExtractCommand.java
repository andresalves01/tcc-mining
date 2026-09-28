package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.labels.SplitPart;
import app.paper6.Attributes;
import app.paper6.Population;
import app.paper6.Replay;
import app.paper6.RowCache;
import app.pipeline.Paper6ExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the transposition's population and every construct, prints the checks, and writes the
 * working cache to the file {@code CACHE} names. Records a summary in {@code PAPER6_DB}.
 *
 * <p>Refuses to write the cache when the time-travel check found anything, or when the split does
 * not reproduce the thesis pipeline's stored partitions: a cache built on either would carry the
 * defect into every fit after it.
 */
public final class Paper6ExtractCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<caminho absoluto> e obrigatorio"));
    }
    final Paper6ExtractTask.Report report = new Paper6ExtractTask(
        context.issueUnits(), context.issueTables(), context.progress())
        .run(context.config().trainShare());
    final Population.Report p = report.population();

    System.out.printf("%n  TRANSPOSICAO PARA ISSUES -- POPULACAO%n%n");
    System.out.printf("  rotulos lidos                                   %,8d%n", p.labels());
    System.out.printf("  excluidos: resolvidos antes do 1o commit ligado %,8d%n",
        p.resolvedBeforeCommit());
    System.out.printf("  relator tirado de creator_id (sem reporter_id)  %,8d%n",
        p.reporterFromCreator());
    System.out.printf("  sem relator nem criador                         %,8d%n", p.reporterUnknown());
    System.out.printf("  MIN_RESOLVED                                    %8d  (sem filtro)%n",
        Population.MIN_RESOLVED);
    System.out.printf("  corte externo  %s%n  corte interno  %s%n", p.outerCut(), p.innerCut());
    for (final SplitPart part : SplitPart.values()) {
      final Population.Group g = p.byPart().get(part);
      System.out.printf("    %-22s %,8d linhas  %3d projetos  %,6d relatores%n", part, g.rows(),
          g.projects(), g.reporters());
    }
    final Map<String, Integer> inner = new LinkedHashMap<>();
    p.innerParts().values().forEach(v -> inner.merge(v.name(), 1, Integer::sum));
    System.out.printf("  divisao interna do treino: %s%n", inner);
    System.out.printf("%n  PORTAO DE REPRODUCAO: a regra de corte, com MIN_RESOLVED=%d sobre os %,d "
        + "rotulos sem filtro, discorda da particao gravada em %d rotulos%n",
        p.reproductionMinResolved(), p.labels(), p.reproduction());

    System.out.printf("%n  PORTAO DO CHANGELOG, por campo, sobre a populacao usavel%n");
    System.out.printf("  %-18s %12s %14s %18s %12s%n", "campo", "com mudanca",
        "encadeia (%)", "difere em t0 (%)", "negativos");
    final int n = report.entries().size();
    for (final Replay.Fidelity f : report.fidelity()) {
      System.out.printf("  %-18s %,12d %14s %18s %12s%n", f.field(), f.withChanges(),
          f.chained() < 0 ? "(lista)" : String.format("%,d (%.1f)", f.chained(),
              f.withChanges() == 0 ? 0 : 100.0 * f.chained() / f.withChanges()),
          String.format("%,d (%.2f)", f.changedAfterOpen(), 100.0 * f.changedAfterOpen() / n),
          f.chained() < 0 ? String.format("%,d", f.negative()) : "-");
    }

    System.out.printf("%n  AS TRES COLUNAS DE CRIACAO DA ESPECIFICACAO PROPRIA: final gravado x t0%n");
    for (final Paper6ExtractTask.Deviation d : report.deviations()) {
      System.out.printf("  %-50s comparadas %,6d   diferem %,6d (%.2f%%)%n", d.column(),
          d.compared(), d.differ(), d.compared() == 0 ? 0 : 100.0 * d.differ() / d.compared());
    }
    System.out.printf("  linhas sem issue_features: %,d%n", report.withoutOwnColumns());

    System.out.printf("%n  PORTAO DE VIAGEM NO TEMPO: %d issues, corpus truncado na criacao de cada"
        + " uma, %,d comparacoes -> %d divergencias%n", report.timeTravelIssues(),
        report.timeTravelChecks(), report.timeTravelMismatches().size());
    report.timeTravelMismatches().stream().limit(20).forEach(m -> System.out.println("    " + m));

    System.out.printf("%n  COBERTURA%n  %-34s %-26s %9s %8s %8s %9s %8s%n", "coluna", "classe",
        "com valor", "ausente", "zero", "aus.TRAIN", "aus.TEST");
    for (final Paper6ExtractTask.Coverage c : report.coverage()) {
      System.out.printf("  %-34s %-26s %9d %8d %8d %9d %8d%n", c.column(),
          Attributes.byName(c.column()).temporalClass(), c.withValue(), c.absent(), c.zero(),
          c.absentTrain(), c.absentTest());
    }

    final List<String> failures = new ArrayList<>();
    if (!report.timeTravelMismatches().isEmpty()) {
      failures.add("  viagem no tempo encontrou construto lendo o futuro -- cache nao gravado");
    }
    if (p.reproduction() != 0) {
      failures.add("  a divisao nao reproduz a particao gravada -- cache nao gravado");
    }
    if (!failures.isEmpty()) {
      throw new CommandFailedException(failures);
    }
    try {
      RowCache.write(Path.of(cache), report.entries(), Attributes.stored());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui gravar " + cache + ": " + e));
    }
    System.out.printf("%n  cache gravado: %s (%,d linhas)%n", cache, n);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "paper6-extrair");
    run.put("rotulos", p.labels());
    run.put("excluidos_resolvidos_antes_do_commit", p.resolvedBeforeCommit());
    run.put("usaveis", n);
    run.put("corte_externo", p.outerCut().toString());
    run.put("corte_interno", p.innerCut().toString());
    run.put("reproducao_divergencias", p.reproduction());
    run.put("viagem_no_tempo_divergencias", report.timeTravelMismatches().size());
    run.put("cache", cache);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Replay.Fidelity f : report.fidelity()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("tipo", "fidelidade_changelog");
      row.put("campo", f.field());
      row.put("com_mudanca", f.withChanges());
      row.put("encadeia", f.chained());
      row.put("difere_em_t0", f.changedAfterOpen());
      row.put("negativos", f.negative());
      rows.add(row);
    }
    System.out.printf("  execucao registrada em PAPER6_DB: %s%n",
        context.paper6Results().record(run, rows));
  }
}
