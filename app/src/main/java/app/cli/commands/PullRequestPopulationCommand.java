package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.labels.SplitPart;
import app.pullrequests.Population;
import app.pullrequests.port.UnitSource;
import java.util.List;

/**
 * Prints the population of the Yu/Chouchen replication, and writes nothing anywhere.
 *
 * <p>It exists because the population is a result in its own right -- the frozen decision of
 * 27/09/2026 registers it as a table, before any score -- and because a stage that reports it
 * without writing can be run against the frozen databases as often as it takes to check a change,
 * which is the opposite of what {@code split} allows.
 *
 * <p>Read the counts against {@code DECISOES-FECHADAS.md} §1: with {@code MIN_RESOLVED=0} the
 * partition is {@code TRAIN} 6.212, {@code TEST} 2.707, {@code CROSSES_CUT} 99 excluded,
 * {@code INSUFFICIENT_HISTORY} empty, and 8.919 usable.
 */
public final class PullRequestPopulationCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final UnitSource units = context.units();
    final var labels = units.readAll();
    if (labels.isEmpty()) {
      throw new CommandFailedException(List.of(
          "  " + units.describe() + " esta vazio -- aponte UNITS_DB para a base onde pr-labels "
              + "e split ja rodaram"));
    }

    final Population.Report report;
    try {
      report = Population.of(labels, units.creatorByUnit(labels.stream()
          .map(app.labels.IssueLabel::issueId).toList()),
          context.config().trainShare(), context.config().minResolvedBeforeCut());
    } catch (final Population.MissingCreatorException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    System.out.printf("%n  POPULACAO DA REPLICACAO YU/CHOUCHEN%n%n");
    System.out.printf("  unidades      : %s%n", units.describe());
    System.out.printf("  corte externo : %s%n", report.outerCut());
    System.out.printf("  corte interno : %s%n", report.innerCut());
    System.out.printf("  quantil       : %.2f       MIN_RESOLVED : %d%s%n%n",
        report.trainShare(), report.minResolved(),
        report.minResolved() <= 0 ? "  (regra de historico herdado DESLIGADA)" : "");

    System.out.printf("  %-22s %8s %10s %10s%n", "particao", "linhas", "projetos", "criadores");
    for (final SplitPart part : SplitPart.values()) {
      final Population.Group group = report.byPart().get(part);
      System.out.printf("  %-22s %8d %10d %10d%n",
          part.name(), group.rows(), group.projects(), group.creators());
    }
    System.out.printf("%n  %-22s %8d %10d %10d%n", "UTILIZAVEIS (TRAIN+TEST)",
        report.usable().rows(), report.usable().projects(), report.usable().creators());
    System.out.printf("%n  nada foi escrito: este estagio le %s e o corpus, e so imprime.%n",
        units.describe());
  }
}
