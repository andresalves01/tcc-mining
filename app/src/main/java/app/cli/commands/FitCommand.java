package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.model.Bounds;
import app.model.DeveloperOrdering;
import app.model.ElasticNetModel;
import app.model.Metrics;
import app.model.Population;
import app.pipeline.FitModelTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fits the Elastic Net on the training side and scores it. Reads only; writes nothing. */
public final class FitCommand implements CommandRunner {

  /**
   * The population arms, in the order they are reported.
   *
   * <p>All four were fixed in {@code papers/01-ttc-semantics/PRE-REGISTRO-ANALISE5.md} before any
   * of them was run, and all four are printed. Neither the set nor the order is chosen by what the
   * scores turn out to be.
   */
  private static final List<Population> POPULATIONS = List.of(
      Population.all(),
      Population.withoutPostdated(),
      Population.withoutImmediateByMinute(),
      Population.withoutImmediateByHour());

  /** One line of the cross-arm table, so the eight reports can be read as a comparison. */
  private record Summary(String population, String bounds, String partition, int n,
      double mae, double r2, double ordering, double withinTwo, double referenceMae) {}

  @Override
  public void run(final Context context) throws CommandFailedException {
    // Both duration arms and all four population arms from one run. Which population a study of
    // issue lifetime is actually modelling is the question; reporting one arm would make the
    // others impossible to put beside it.
    final FitModelTask task = new FitModelTask(context.observations(), context.progress());
    final List<Summary> summaries = new ArrayList<>();
    for (final Population population : POPULATIONS) {
      for (final Bounds bounds : List.of(Bounds.none(), Bounds.oneHourToOneYear())) {
        try {
          // The inner selection chooses the parameters; the held-out partition is then scored once
          // with them, refitted over the whole training side. Re-sweeping there would let the
          // held-out outcomes pick their own model.
          final FitModelTask.Report inner = task.run(bounds, population);
          print(inner, bounds, "SELECAO INTERNA");
          summaries.add(summarise(inner, "selecao interna"));
          final FitModelTask.Report held =
              task.heldOut(bounds, population, inner.alpha(), inner.lambda());
          print(held, bounds, "TESTE EXTERNO");
          summaries.add(summarise(held, "teste externo"));
        } catch (final FitModelTask.NoObservationsException e) {
          throw new CommandFailedException(List.of("  " + e.getMessage()));
        }
      }
    }
    printSummary(summaries);
  }

  private static Summary summarise(final FitModelTask.Report report, final String partition) {
    Metrics model = null;
    double referenceMae = Double.NaN;
    for (final FitModelTask.Scored scored : report.comparison()) {
      if (scored.name().equals("modelo completo")) {
        model = scored.metrics();
      } else if (scored.name().startsWith("REFERENCIA")) {
        referenceMae = scored.metrics().maeDays();
      }
    }
    final List<DeveloperOrdering.Result> gradient =
        report.comparison().get(report.comparison().size() - 1).ordering();
    return new Summary(report.population().name(), report.bounds().name(), partition,
        model.n(), model.maeDays(), model.r2Log(), model.rankCorrelation(),
        model.withinFactorTwo(), referenceMae);
  }

  /**
   * The cross-arm table. This is what analysis 5 actually asks for: not whether one arm scores
   * better, but whether the population being modelled changes materially with an assumption that
   * issue-lifetime studies do not usually state.
   */
  private static void printSummary(final List<Summary> summaries) {
    System.out.printf("%n%n  ===================================================================%n");
    System.out.printf("  ANALISE 5 -- AS QUATRO POPULACOES LADO A LADO%n");
    System.out.printf("  ===================================================================%n%n");
    System.out.printf("  Limiares fixados em PRE-REGISTRO-ANALISE5.md antes de qualquer%n");
    System.out.printf("  execucao. O teste externo foi lido uma vez por arm, com%n");
    System.out.printf("  hiperparametros congelados vindos da divisao interna.%n%n");
    System.out.printf("  %-24s %-18s %-15s %7s %8s %8s %8s %9s %9s%n",
        "populacao", "duracao", "particao", "n", "MAE d", "ref MAE", "R2 log",
        "ordena", "dentro 2x");
    System.out.printf("  %s%n", "-".repeat(124));
    for (final Summary s : summaries) {
      System.out.printf("  %-24s %-18s %-15s %7d %8.2f %8.2f %8.3f %9s %8.1f%%%n",
          s.population(), s.bounds(), s.partition(), s.n(), s.mae(), s.referenceMae(), s.r2(),
          Double.isNaN(s.ordering()) ? "--" : String.format("%.3f", s.ordering()),
          100 * s.withinTwo());
    }
    System.out.printf("%n  'ref MAE' e a referencia 3.6, a mediana do par (desenvolvedor,"
        + " projeto).%n");
  }

  private static void print(final FitModelTask.Report report, final Bounds bounds,
      final String partition) {

    System.out.printf("%n%n  ===================================================================%n");
    System.out.printf("  %s  --  %s%n", partition, bounds.name().toUpperCase());
    System.out.printf("  ===================================================================%n%n");
    System.out.printf("  ajustado em : %,d rotulos      pontuado em : %,d rotulos%n",
        report.fitRows(), report.selectionRows());
    System.out.printf("  postos de lado por equipe vazia : %,d e %,d%n",
        report.excludedEmptyTeamFit(), report.excludedEmptyTeamSelection());
    if (partition.startsWith("TESTE")) {
      System.out.printf("  Aberto em 21/09/2026, uma vez, com a especificacao descrita em%n");
      System.out.printf("  PRE-REGISTRO.md. Medicao unica -- NAO e estimativa independente de%n");
      System.out.printf("  generalizacao: McCC_tot ausente e o corte de duracao indeciso.%n");
    }
    if (!report.droppedColumns().isEmpty()) {
      System.out.printf("%n  constantes no ajuste, retiradas : %s%n",
          String.join(", ", report.droppedColumns()));
    }

    System.out.printf("%n  HIPERPARAMETROS  (escolhidos sempre na divisao interna)%n");
    System.out.printf("    alpha = %.2f      lambda = %.6g%n", report.alpha(), report.lambda());
    System.out.printf("    colunas selecionadas : %d de %d   (limiar |b|/|b|max >= %.0e,"
        + " declarado antes)%n", report.selectedColumns(), report.columns().size(),
        ElasticNetModel.SELECTION_THRESHOLD);

    System.out.printf("%n  COEFICIENTES  (colunas padronizadas, entao comparaveis entre si)%n");
    final double[] coefficients = report.coefficients();
    double largest = 0;
    for (final double c : coefficients) {
      largest = Math.max(largest, Math.abs(c));
    }
    for (int i = 0; i < report.columns().size(); i++) {
      final boolean kept = largest > 0
          && Math.abs(coefficients[i]) / largest >= ElasticNetModel.SELECTION_THRESHOLD;
      System.out.printf("    %-26s %12.6f   %s%n", report.columns().get(i), coefficients[i],
          kept ? "" : "abaixo do limiar");
    }
    System.out.printf("    %-26s %12.6f%n", "(intercepto)", report.intercept());

    System.out.printf("%n  CONTRA AS REFERENCIAS  (medido em %s)%n", partition.toLowerCase());
    System.out.printf("    %-32s %8s %9s %11s %9s %9s %9s %9s%n", "", "n", "MAE d",
        "RMSE d", "erro med", "R2 log", "ordena", "dentro 2x");
    for (final FitModelTask.Scored scored : report.comparison()) {
      final Metrics m = scored.metrics();
      System.out.printf("    %-32s %8d %9.2f %11.2f %9.2f %9.3f %9s %8.1f%%%n",
          scored.name(), m.n(), m.maeDays(), m.rmseDays(), m.medianErrorDays(),
          m.r2Log(), Double.isNaN(m.rankCorrelation()) ? "--"
              : String.format("%.3f", m.rankCorrelation()), 100 * m.withinFactorTwo());
    }
    System.out.printf("    a referencia da 3.6 encontra o par em %.1f%% das linhas%n",
        100 * report.pairCoverage());

    System.out.printf("%n  ORDENAR DESENVOLVEDORES  (mesmo projeto, pessoas diferentes)%n");
    System.out.printf("    conforme as tarefas ficam parecidas, so a diferenca entre as"
        + " pessoas sobra%n%n");
    final List<DeveloperOrdering.Result> gradient =
        report.comparison().get(report.comparison().size() - 1).ordering();
    System.out.printf("    %-34s %10s", "", "pares");
    for (final FitModelTask.Scored scored : report.comparison()) {
      System.out.printf(" %14s", abbreviate(scored.name()));
    }
    System.out.println();
    for (int i = 0; i < gradient.size(); i++) {
      System.out.printf("    %-34s %10s", gradient.get(i).matching(),
          String.format("%,d", gradient.get(i).pairs()));
      for (final FitModelTask.Scored scored : report.comparison()) {
        final DeveloperOrdering.Result o = scored.ordering().get(i);
        // A prediction that does not vary between two people in the same project ties every
        // pair, and a tie decides nothing. That is the baseline's shape, not a score of zero.
        System.out.printf(" %14s", o.pairs() == 0 ? "nao distingue"
            : String.format("%.1f%%", 100 * o.agreement()));
      }
      System.out.println();
    }
    System.out.printf("%n    (50%% e a moeda. Os pares nao sao independentes entre si, entao os%n");
    System.out.printf("     decimais nao devem ser lidos como precisao.)%n");

    System.out.printf("%n  ABLACAO POR GRUPO DE PREDITORES%n");
    for (final Map.Entry<String, FitModelTask.Scored> entry : report.ablation().entrySet()) {
      final FitModelTask.Scored scored = entry.getValue();
      if (scored.metrics() == null) {
        System.out.printf("    %-24s (sem colunas)%n", entry.getKey());
        continue;
      }
      final DeveloperOrdering.Result tight =
          scored.ordering().get(scored.ordering().size() - 1);
      System.out.printf("    %-24s MAE %8.2fd   R2 log %7.3f   ordena tarefas %6.3f"
          + "   ordena devs %5.1f%% (filtro estrito)%n", entry.getKey(),
          scored.metrics().maeDays(), scored.metrics().r2Log(),
          scored.metrics().rankCorrelation(), 100 * tight.agreement());
    }
    System.out.printf("%n  %s%n", partition.startsWith("TESTE")
        ? "MEDICAO UNICA sobre a especificacao de PRE-REGISTRO.md, aberta em 21/09/2026."
        : "EXPLORATORIO: a divisao interna ja foi olhada muitas vezes nas sessoes anteriores.");
  }

  /** Short enough for a column header. */
  private static String abbreviate(final String name) {
    return name.length() <= 14 ? name : name.substring(0, 13) + ".";
  }
}
