package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper6.model.Fitting;
import app.pipeline.Paper6EvaluationTask;
import app.pipeline.Paper6Registration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores the reserved rows ONCE, with the parameters {@code REGISTRO-PREVIO.md} froze.
 *
 * <p>The only code path that passes {@link Fitting.Mode#RESERVED}. It takes no parameters from the
 * environment -- only the literals in {@link Paper6Registration} -- and refuses to start while those
 * are empty. The run is appended to {@code PAPER6_DB} under a fresh id, so a second run could not
 * hide behind the first.
 */
public final class Paper6ReservedCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    if (Paper6Registration.FROZEN.isEmpty()) {
      throw new CommandFailedException(List.of("  o registro previo ainda nao congelou parametros: "
          + "o reservado nao sera aberto"));
    }
    final var entries = Paper6Printing.cache();
    final Paper6EvaluationTask.Report report = new Paper6EvaluationTask(context.progress())
        .run(entries, Paper6Registration.FROZEN, Fitting.Mode.RESERVED);

    System.out.printf("%n  TRANSPOSICAO PARA ISSUES -- CONJUNTO RESERVADO, PONTUADO UMA VEZ%n");
    System.out.printf("  parametros: literais de Paper6Registration, copiados de REGISTRO-PREVIO.md%n");
    System.out.printf("  perfil principal: %s%n", Paper6Registration.PRINCIPAL);
    System.out.printf("  treino inteiro %,d linhas, reservado %,d linhas%n", report.fitRows(),
        report.scoredRows());
    final List<Map<String, Object>> rows = Paper6Printing.evaluation(report);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "paper6-reservado");
    run.put("registro", "REGISTRO-PREVIO.md");
    run.put("perfil_principal", Paper6Registration.PRINCIPAL.name());
    run.put("linhas_treino", report.fitRows());
    run.put("linhas_reservado", report.scoredRows());
    System.out.printf("%n  execucao registrada em PAPER6_DB: %s%n",
        context.paper6Results().record(run, rows));
  }
}
