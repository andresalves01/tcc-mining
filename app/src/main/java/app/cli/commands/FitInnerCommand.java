package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.model.Bounds;
import app.model.DeveloperOrdering;
import app.pipeline.FitModelTask;
import java.util.List;
import java.util.Map;

/**
 * Fits the Elastic Net on the training side and scores it against the inner selection only.
 *
 * <p>{@code fit} itself always does two things in one invocation: {@link FitModelTask#run} chooses
 * alpha and lambda on the inner split, then {@link FitModelTask#heldOut} refits on the whole
 * training side and scores the held-out partition exactly once, by design. That is correct once a
 * specification is locked, and wrong while one is still being worked out -- there is no way to run
 * {@code fit} and see only the first half, so changing a definition and re-running it opens the
 * held-out set again on every attempt, under whatever the definition happened to be that time.
 *
 * <p>This command exists for exactly that working-out period. It calls {@link FitModelTask#run}
 * and nothing else -- there is no code path from here to {@link FitModelTask#heldOut} at all, not
 * merely a convention of not calling it, so no future edit to this file can reach it by accident
 * without the compiler needing a new import to do it.
 *
 * <p>Once a specification is settled and committed, {@code fit} is the command to run -- once,
 * deliberately, the same discipline every other stage of this study already keeps.
 */
public final class FitInnerCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final FitModelTask task = new FitModelTask(context.observations(), context.progress());
    for (final Bounds bounds : List.of(Bounds.none(), Bounds.oneHourToOneYear())) {
      try {
        final FitModelTask.Report report = task.run(bounds);
        FitCommand.print(report, bounds, "SELECAO INTERNA");
        printAblationGradient(report);
        printPerColumn(task, bounds, report);
      } catch (final FitModelTask.NoObservationsException e) {
        throw new CommandFailedException(List.of("  " + e.getMessage()));
      }
    }
    System.out.printf("%n  fit-inner nao chama FitModelTask.heldOut() em nenhum caminho --"
        + " o conjunto retido nao foi tocado por esta execucao.%n");
  }

  /**
   * The ordering gradient for each ablation, not only its strictest point.
   *
   * <p>{@code fit} prints one number per ablation, taken at the tightest matching. That hides the
   * question the gradient exists to answer: an ablation that orders well over any pair and falls to
   * the coin once tasks are matched was reading the task, not the people.
   */
  private static void printAblationGradient(final FitModelTask.Report report) {
    final List<DeveloperOrdering.Result> shape =
        report.ablation().values().iterator().next().ordering();
    if (shape == null) {
      return;
    }
    System.out.printf("%n  ORDENAR DESENVOLVEDORES POR ABLACAO  (gradiente inteiro)%n");
    System.out.printf("    cada celula traz a concordancia e, entre parenteses, os pares que a%n");
    System.out.printf("    produziram. Elas mudam por linha: um par cuja previsao empata nao%n");
    System.out.printf("    decide nada e sai. Um modelo que so ve a tarefa empata quase tudo%n");
    System.out.printf("    quando o filtro ja igualou as tarefas, entao pontua em muito menos%n");
    System.out.printf("    pares -- as porcentagens de linhas diferentes nao sao comparaveis%n");
    System.out.printf("    entre si sem olhar o denominador.%n%n");
    System.out.printf("    %-26s", "");
    for (final DeveloperOrdering.Result step : shape) {
      System.out.printf(" %22s", abbreviate(step.matching()));
    }
    System.out.println();
    for (final Map.Entry<String, FitModelTask.Scored> entry : report.ablation().entrySet()) {
      final List<DeveloperOrdering.Result> gradient = entry.getValue().ordering();
      if (gradient == null) {
        continue;
      }
      System.out.printf("    %-26s", entry.getKey());
      for (final DeveloperOrdering.Result step : gradient) {
        System.out.printf(" %22s", step.pairs() == 0 ? "--"
            : String.format("%.1f%% (%,d)", 100 * step.agreement(), step.pairs()));
      }
      System.out.println();
    }
  }

  /** What each predictor carries alone, which is not what its coefficient says it carries. */
  private static void printPerColumn(final FitModelTask task, final Bounds bounds,
      final FitModelTask.Report report) {

    final Map<String, FitModelTask.Scored> byColumn =
        task.perColumn(bounds, report.alpha(), report.lambda());
    System.out.printf("%n  PODER PREDITIVO DE CADA FATOR, SOZINHO%n");
    System.out.printf("    um modelo por coluna, mesmo alpha, mesmo lambda, mesmas particoes%n%n");
    System.out.printf("    as duas ultimas colunas trazem os pares entre parenteses, pelo mesmo%n");
    System.out.printf("    motivo: um fator que empata muito pontua em poucos pares.%n%n");
    System.out.printf("    %-26s %9s %9s %14s %20s %20s%n",
        "fator", "MAE d", "R2 log", "ordena tarefa", "ordena devs", "ordena devs");
    System.out.printf("    %-26s %9s %9s %14s %20s %20s%n",
        "", "", "", "", "(qualquer par)", "(estrito)");
    for (final Map.Entry<String, FitModelTask.Scored> entry : byColumn.entrySet()) {
      final FitModelTask.Scored scored = entry.getValue();
      if (scored.metrics() == null) {
        continue;
      }
      final List<DeveloperOrdering.Result> gradient = scored.ordering();
      final DeveloperOrdering.Result loose = gradient.get(0);
      final DeveloperOrdering.Result tight = gradient.get(gradient.size() - 1);
      System.out.printf("    %-26s %9.2f %9.3f %14s %20s %20s%n",
          entry.getKey(), scored.metrics().maeDays(), scored.metrics().r2Log(),
          Double.isNaN(scored.metrics().rankCorrelation()) ? "--"
              : String.format("%.3f", scored.metrics().rankCorrelation()),
          loose.pairs() == 0 ? "--"
              : String.format("%.1f%% (%,d)", 100 * loose.agreement(), loose.pairs()),
          tight.pairs() == 0 ? "--"
              : String.format("%.1f%% (%,d)", 100 * tight.agreement(), tight.pairs()));
    }
  }

  private static String abbreviate(final String name) {
    return name.length() <= 22 ? name : name.substring(0, 21) + ".";
  }
}
