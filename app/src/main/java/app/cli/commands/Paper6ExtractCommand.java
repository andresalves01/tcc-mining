package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper6.Initial;
import app.paper6.Parity;
import app.paper6.RowCache;
import app.paper6.TimeTravel;
import app.pipeline.Paper6ExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the study's populations and columns, prints the gates, and writes the cache to the file
 * {@code CACHE} names -- only when every gate passed. Needs {@code PARIDADE}, the Python
 * specification's export ({@code analises/95_exportar_colunas.py}). Records a summary in
 * {@code PAPER6_DB}.
 */
public final class Paper6ExtractCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    final String parity = System.getenv("PARIDADE");
    if (cache == null || cache.isBlank() || parity == null || parity.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<arquivo de saida> e PARIDADE=<exportacao "
          + "de 95_exportar_colunas.py> sao obrigatorios"));
    }
    final Parity.Spec python;
    try {
      python = Parity.read(Path.of(parity));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler " + parity + ": " + e));
    }
    final Paper6ExtractTask.Report r =
        new Paper6ExtractTask(context.study(), context.progress()).run(python);

    System.out.printf("%n  DURACAO DE ISSUES -- EXTRACAO E PORTOES%n");
    System.out.printf("%n  FUNIL%n");
    r.funnel().forEach((k, v) -> System.out.printf("    %-50s %,10d%n", k, v));

    System.out.printf("%n  DIVISAO 70/30 POR POPULACAO (regra de TemporalSplit sem elegibilidade)%n");
    for (final var s : r.splits()) {
      System.out.printf("    %-10s %,8d linhas  corte %s  treino %,d  teste %,d  atravessa %,d%n",
          s.population(), s.rows(), s.cut(), s.train(), s.test(), s.crosses());
      System.out.printf("    %-10s          corte interno %s  ajuste %,d  selecao %,d  atravessa %,d%n",
          "", s.innerCut(), s.fit(), s.selection(), s.innerCrosses());
    }
    System.out.printf("%n  PORTAO 1 -- a regra, com 5 resolucoes por par, reproduz o split gravado dos "
        + "rotulos: %,d divergencias%n", r.thesisSplitMismatches());

    System.out.printf("%n  CHANGELOG (ampla): por campo, issues alteradas, cadeia e valor final "
        + "coerentes, valor inicial != atual%n");
    for (final Initial.Fidelity f : r.fidelity()) {
      System.out.printf("    %-12s alterada %6.2f%%  coerente %6.2f%%  inicial!=atual %6.2f%%%n",
          f.field(), 100.0 * f.changed() / f.issues(),
          f.changed() == 0 ? 100.0 : 100.0 * f.consistent() / f.changed(),
          100.0 * f.differs() / f.issues());
    }

    System.out.printf("%n  PORTAO 2 -- VIAGEM NO TEMPO (%d issues da ampla, semente %d)%n",
        TimeTravel.SAMPLE, TimeTravel.SEED);
    for (final TimeTravel.Check c : r.travel()) {
      System.out.printf("    %-42s %4d de %4d iguais   regra ingenua difere em %4d%n", c.column(),
          c.same(), c.total(), c.naiveDiffers());
      c.examples().forEach(x -> System.out.println("        " + x));
    }

    System.out.printf("%n  PORTAO 3 -- PARIDADE COM A ESPECIFICACAO PYTHON (%s)%n", parity);
    for (final Parity.Membership m : r.membership()) {
      System.out.printf("    populacao %-8s java %,8d  python %,8d  so java %,d  so python %,d%n",
          m.population(), m.java(), m.python(), m.onlyJava(), m.onlyPython());
    }
    for (final Parity.Column c : r.parity()) {
      System.out.printf("    %-42s %,8d comparados  %,6d divergentes%n", c.name(), c.compared(),
          c.mismatches());
      c.examples().forEach(x -> System.out.println("        " + x));
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "paper6-extrair");
    run.put("funil", r.funnel());
    run.put("portao_split_tcc", r.thesisSplitMismatches());
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
    System.out.printf("%n  execucao registrada em PAPER6_DB: %s%n",
        context.paper6Results().record(run, rows));

    if (!r.passed()) {
      throw new CommandFailedException(List.of("", "  RECUSADO: um portao falhou -- o cache NAO foi "
          + "gravado. Explique cada divergencia antes de seguir."));
    }
    try {
      RowCache.write(Path.of(cache), r.entries(), r.columns());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui gravar " + cache + ": " + e));
    }
    System.out.printf("%n  cache gravado: %s (%,d linhas, %d colunas)%n", cache,
        r.entries().size(), r.columns().size());
  }
}
