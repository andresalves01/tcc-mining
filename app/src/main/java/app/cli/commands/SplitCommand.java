package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.config.PipelineConfig;
import app.labels.IssueLabel;
import app.labels.SplitPart;
import app.labels.TemporalSplit;
import app.pipeline.SplitTask;
import java.util.List;

/** Cuts the labels into past and future, with an inner cut inside the training side. */
public final class SplitCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final PipelineConfig config = context.config();

    final SplitTask.Report report;
    try {
      report = new SplitTask(context.labelStore())
          .run(config.trainShare(), config.minResolvedBeforeCut());
    } catch (final SplitTask.LabelsEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }
    final TemporalSplit.Split split = report.split();

    System.out.printf("  rotulos : %,d      minimo de resolucoes por par (dev, projeto) : %d%n",
        report.labels().size(), config.minResolvedBeforeCut());
    printSide("CORTE EXTERNO t0", split.outer(), "treino", "teste");
    printSide("CORTE INTERNO t1", split.inner(), "ajuste", "selecao de parametros");
    final long ajuste = split.innerParts().values().stream()
        .filter(part -> part == SplitPart.TRAIN).count();
    final long selecao = split.innerParts().values().stream()
        .filter(part -> part == SplitPart.TEST).count();
    System.out.printf("%n  AS TRES PARTICOES UTILIZAVEIS%n");
    System.out.printf("    ajuste          split=TRAIN split_inner=TRAIN : %,7d%n", ajuste);
    System.out.printf("    selecao de hiperparametros  split_inner=TEST : %,7d%n", selecao);
    System.out.printf("    teste                        split=TEST      : %,7d%n",
        split.outer().future().size());
    System.out.printf("    (o resto atravessa um dos cortes ou nao tem historico suficiente)%n");

    System.out.printf("%n  gravou %,d atribuicoes em %s%n", report.written(),
        context.labelStore().describe());
  }

  private static void printSide(final String title, final TemporalSplit.Side side,
      final String pastName, final String futureName) {

    System.out.printf("%n  %s%n", title);
    System.out.printf("    data do corte                    : %s%n", side.cut());
    System.out.printf("    pares (dev, projeto) elegiveis   : %,d%n", side.eligiblePairs());
    System.out.printf("    desenvolvedores distintos        : %,d%n", side.eligibleDevelopers());
    System.out.printf("    %-32s : %,7d   projetos %,d%n", pastName, side.past().size(),
        TemporalSplit.Side.projects(side.past()));
    System.out.printf("    %-32s : %,7d   projetos %,d%n", futureName, side.future().size(),
        TemporalSplit.Side.projects(side.future()));
    System.out.printf("    atravessam o corte               : %,7d%n", side.crossing().size());
    System.out.printf("    historico insuficiente, passado  : %,7d%n",
        side.insufficientPast().size());
    System.out.printf("    historico insuficiente, futuro   : %,7d%n",
        side.insufficientFuture().size());
    System.out.printf("    proporcao efetiva                : %.1f / %.1f   (total %,d)%n",
        100 * side.pastShare(), 100 * (1 - side.pastShare()), side.total());
    printTtc("      " + pastName, side.past());
    printTtc("      " + futureName, side.future());
    printTtc("      atravessam", side.crossing());
    printTtc("      hist. insuf. passado", side.insufficientPast());
    printTtc("      hist. insuf. futuro", side.insufficientFuture());
  }

  private static void printTtc(final String name, final List<IssueLabel> labels) {
    if (labels.isEmpty()) {
      return;
    }
    final List<Double> ttc = labels.stream().map(IssueLabel::ttcDays).sorted().toList();
    System.out.printf("    %-24s TTC  p25=%7.2fd  p50=%7.2fd  p75=%8.2fd  p95=%9.2fd%n", name,
        Quantiles.at(ttc, 0.25), Quantiles.at(ttc, 0.50),
        Quantiles.at(ttc, 0.75), Quantiles.at(ttc, 0.95));
  }
}
