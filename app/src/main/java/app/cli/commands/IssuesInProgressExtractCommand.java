package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Parity;
import app.issues.RowCache;
import app.issues.TimeTravel;
import app.issues.Universe.Population;
import app.pipeline.IssuesInProgressExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The secondary study's extraction ({@code papers/issues-in-progress}): the FIXED issues read at
 * their first In Progress, the split on that instant, time travel at it and parity with the Python
 * specification. Writes the cache to CACHE only when every gate passed; records a summary in
 * ISSUES_DB. Needs PARIDADE, the export of {@code 20_exportar_andamento.py}.
 */
public final class IssuesInProgressExtractCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    final String parity = System.getenv("PARIDADE");
    if (cache == null || cache.isBlank() || parity == null || parity.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<arquivo de saida> e PARIDADE=<exportacao "
          + "de 20_exportar_andamento.py> sao obrigatorios"));
    }
    final Parity.Spec python;
    try {
      python = Parity.read(Path.of(parity));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler " + parity + ": " + e));
    }
    final IssuesInProgressExtractTask.Report r =
        new IssuesInProgressExtractTask(context.study(), context.progress()).run(python);

    System.out.printf("%n  DURACAO A PARTIR DO IN PROGRESS -- EXTRACAO E PORTOES%n");
    final var s = r.split();
    System.out.printf("%n  POPULACAO E DIVISAO 70/30 PELO INSTANTE DO IN PROGRESS%n");
    System.out.printf("    %,d issues  corte %s  treino %,d  teste %,d  atravessa %,d%n", s.rows(),
        s.cut(), s.train(), s.test(), s.crosses());
    System.out.printf("    corte interno %s  ajuste %,d  selecao %,d  atravessa %,d%n",
        s.innerCut(), s.fit(), s.selection(), s.innerCrosses());
    System.out.printf("%n  EXECUTOR DE t0 (P8)%n");
    r.executors().forEach((k, v) -> System.out.printf("    %-40s %,8d%n", k, v));

    System.out.printf("%n  PORTAO 1 -- VIAGEM NO TEMPO EM t0 = IN PROGRESS (%d issues, semente %d)%n",
        TimeTravel.SAMPLE, TimeTravel.SEED);
    for (final TimeTravel.Check c : r.travel()) {
      System.out.printf("    %-42s %4d de %4d iguais   regra ingenua difere em %4d%n", c.column(),
          c.same(), c.total(), c.naiveDiffers());
      c.examples().forEach(x -> System.out.println("        " + x));
    }
    System.out.printf("%n  PORTAO 2 -- PARIDADE COM A ESPECIFICACAO PYTHON (%s)%n", parity);
    if (r.membership().isEmpty()) {
      System.out.printf("    a exportacao nao tem a coluna 'andamento': nada comparado%n");
    }
    for (final Parity.Membership m : r.membership()) {
      System.out.printf("    populacao %-9s java %,8d  python %,8d  so java %,d  so python %,d%n",
          m.population(), m.java(), m.python(), m.onlyJava(), m.onlyPython());
    }
    for (final Parity.Column c : r.parity()) {
      System.out.printf("    %-42s %,8d comparados  %,6d divergentes%n", c.name(), c.compared(),
          c.mismatches());
      c.examples().forEach(x -> System.out.println("        " + x));
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "issues-andamento-extrair");
    run.put("portao_viagem", r.travelPassed());
    run.put("portao_paridade", r.parityPassed());
    run.put("linhas", r.entries().size());
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Parity.Column c : r.parity()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("coluna", c.name());
      row.put("comparados", c.compared());
      row.put("divergentes", c.mismatches());
      rows.add(row);
    }
    System.out.printf("%n  execucao registrada em ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));

    if (!r.passed()) {
      throw new CommandFailedException(List.of("", "  RECUSADO: um portao falhou -- o cache NAO foi "
          + "gravado. Explique cada divergencia antes de seguir."));
    }
    try {
      RowCache.write(Path.of(cache), r.entries(), r.columns(), List.of(Population.IN_PROGRESS));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui gravar " + cache + ": " + e));
    }
    System.out.printf("%n  cache gravado: %s (%,d linhas, %d colunas)%n", cache,
        r.entries().size(), r.columns().size());
  }
}
