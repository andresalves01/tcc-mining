package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.model.Bounds;
import app.model.DeveloperOrdering;
import app.model.ElasticNetModel;
import app.model.Metrics;
import app.pipeline.FitModelTask;
import java.util.List;
import java.util.Map;

/** Fits the Elastic Net on the training side and scores it. Reads only; writes nothing. */
public final class FitCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    // Both arms from one run. Whether to restrict the sample to a coherent duration band is still
    // open, and reporting only one of them would make the other impossible to put beside it.
    final FitModelTask task = new FitModelTask(context.observations(), context.progress());
    for (final Bounds bounds : List.of(Bounds.none(), Bounds.oneHourToOneYear())) {
      try {
        print(task.run(bounds), bounds);
      } catch (final FitModelTask.NoObservationsException e) {
        throw new CommandFailedException(List.of("  " + e.getMessage()));
      }
    }
  }

  private static void print(final FitModelTask.Report report, final Bounds bounds) {

    System.out.printf("%n%n  ===================================================================%n");
    System.out.printf("  AJUSTE DO ELASTIC NET  --  %s%n", bounds.name().toUpperCase());
    System.out.printf("  ===================================================================%n%n");
    System.out.printf("  ajuste : %,d rotulos      selecao : %,d rotulos%n",
        report.fitRows(), report.selectionRows());
    System.out.printf("  postos de lado por equipe vazia : %,d no ajuste, %,d na selecao%n",
        report.excludedEmptyTeamFit(), report.excludedEmptyTeamSelection());
    System.out.printf("  O CONJUNTO DE TESTE EXTERNO NAO FOI LIDO.%n");
    if (!report.droppedColumns().isEmpty()) {
      System.out.printf("%n  constantes no ajuste, retiradas : %s%n",
          String.join(", ", report.droppedColumns()));
    }

    System.out.printf("%n  HIPERPARAMETROS ESCOLHIDOS NA DIVISAO INTERNA%n");
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

    System.out.printf("%n  CONTRA AS REFERENCIAS  (medido na selecao interna)%n");
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
    System.out.printf("%n  EXPLORATORIO: a divisao interna ja foi olhada muitas vezes nas"
        + " sessoes anteriores.%n");
  }

  /** Short enough for a column header. */
  private static String abbreviate(final String name) {
    return name.length() <= 14 ? name : name.substring(0, 13) + ".";
  }
}
