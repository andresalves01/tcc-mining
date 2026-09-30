package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.model.Fitting;
import app.pipeline.IssuesEvaluationTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores the secondary study's external test once, as
 * {@code papers/issues-in-progress/implementacao/REGISTRO-PREVIO.md} lays it out. Written after
 * that file was committed.
 *
 * <p>Before anything is fitted it checks the matrix and the parameters by SHA-256 and the sizes of
 * every side against the registration, and refuses on any difference. Then each of the eleven
 * cells is refitted on the whole outer training side with its frozen alpha and the chosen lambda
 * times the ratio of rows; the weights are printed; the three references are learned on that side;
 * the outer test is scored and read every way section 8 names. No never-scored subset (P22).
 *
 * <p>Reads CACHE and PARAMETROS; writes every prediction to PREVISOES; records the run in ISSUES_DB
 * by insertion.
 */
public final class IssuesInProgressReservedCommand implements CommandRunner {

  private static final Population P = Population.IN_PROGRESS;
  private static final String REGISTRO =
      "papers/issues-in-progress/implementacao/REGISTRO-PREVIO.md";

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path cache = path("CACHE");
    final Path params = path("PARAMETROS");
    final Path out = path("PREVISOES");

    final String matrixSha;
    final String paramsSha;
    final List<RowCache.Entry> entries;
    try {
      matrixSha = IssuesReservedCommand.sha256(cache);
      paramsSha = IssuesReservedCommand.sha256(params);
      entries = RowCache.read(cache);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler as entradas: " + e));
    }
    final List<IssuesEvaluationTask.Frozen> frozen =
        IssuesCheckCommand.parse(params.toString());

    // every gate before any row of the test is scored
    final List<String> problems = new ArrayList<>();
    final Fitting.Sides outer = Fitting.sides(entries, Fitting.Mode.RESERVED, P);
    final Fitting.Sides inner = Fitting.sides(entries, Fitting.Mode.INNER, P);
    final RegistrationInProgress.Sizes found = new RegistrationInProgress.Sizes(
        outer.fitUnits().size(), outer.scoreUnits().size(), inner.fitUnits().size(),
        inner.scoreUnits().size());
    if (frozen.stream().anyMatch(f -> f.population() != P)) {
      problems.add("parametros: ha linhas de outra populacao");
    }
    problems.addAll(RegistrationInProgress.differences(matrixSha, paramsSha, frozen.size(), found));

    System.out.printf("%n  DURACAO A PARTIR DO IN PROGRESS -- TESTE EXTERNO, EXECUCAO REGISTRADA%n");
    System.out.printf("  registro: %s%n", REGISTRO);
    System.out.printf("  matriz     %s  sha256 %s%n", cache, matrixSha);
    System.out.printf("  parametros %s  sha256 %s (%d linhas)%n", params, paramsSha,
        frozen.size());
    System.out.printf("  %-24s treino %,7d  teste %,6d  ajuste/selecao internos %,6d / %,6d%n",
        IssuesPrinting.name(P), found.train(), found.test(), found.innerFit(),
        found.innerSelection());
    if (!problems.isEmpty()) {
      final List<String> lines = new ArrayList<>();
      lines.add("  RECUSADO, nada foi pontuado: o que foi encontrado difere do registro");
      problems.forEach(x -> lines.add("    " + x));
      throw new CommandFailedException(lines);
    }
    System.out.printf("  conferencia contra o registro: tudo igual; o teste externo e pontuado "
        + "agora, uma vez%n");

    final IssuesEvaluationTask.Report r = new IssuesEvaluationTask(context.progress()).run(
        entries, frozen, P, Fitting.Mode.RESERVED, null);

    System.out.printf("%n  ==== TESTE EXTERNO -- %s ====%n", IssuesPrinting.name(P));
    System.out.printf("  reajuste no treino externo inteiro (%,d linhas); pontuadas %,d linhas "
        + "do teste externo%n", r.fitRows(), r.scoredRows());
    System.out.printf("  referencia principal pela regra (P12: menor MAE na selecao interna entre "
        + "tres): %s (constante %.2f h, mediana do projeto %.2f h, mediana do par %.2f h); as "
        + "outras sao secundarias%n", r.main().name().toUpperCase(), r.main().constantMaeHours(),
        r.main().projectMaeHours(), r.main().pairMaeHours());
    System.out.printf("  projetos do teste sem issue no treino (nelas a mediana do projeto e a "
        + "constante): %,d issues, %s%n", r.unseenRows(), r.unseenProjects());

    System.out.printf("%n  PARAMETROS: alpha congelado; lambda final = lambda escolhido x %,d / "
        + "%,d; sem recalibracao%n", r.fitRows(), r.innerFitRows());
    for (final IssuesEvaluationTask.Frozen f : frozen) {
      final var fitted = r.evaluated().stream()
          .filter(e -> e.fitted().cell().label().equals(f.cell())).findFirst().orElseThrow()
          .fitted();
      System.out.printf("    %-26s alpha %.2f  lambda escolhido %-14.8g final %-14.8g %d colunas, "
          + "%d com peso%n", f.cell(), fitted.alpha(), f.lambda(), fitted.lambda(),
          fitted.columns().size(), fitted.selected());
    }
    System.out.printf("%n  Os pesos abaixo vem so do treino externo: descrevem o ajuste, nao sao "
        + "importancia estavel nem efeito causal.%n");
    IssuesPrinting.weights(r);

    System.out.printf("%n  ---- PONTUACAO DO TESTE EXTERNO -- %s ----%n", IssuesPrinting.name(P));
    final List<Map<String, Object>> rows = new ArrayList<>(IssuesPrinting.evaluation(r));
    rows.addAll(IssuesReservedCommand.comparisonRows(r));

    final StringBuilder predictions = new StringBuilder();
    IssuesReservedCommand.appendPredictions(predictions, r, null);
    try {
      Files.writeString(out, predictions.toString());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui gravar " + out + ": " + e));
    }
    System.out.printf("%n  previsoes gravadas em %s%n", out);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "issues-andamento-reservado");
    run.put("registro", REGISTRO);
    run.put("teste_externo", "pontuado");
    run.put("matriz_sha256", matrixSha);
    run.put("parametros_sha256", paramsSha);
    run.put("referencia_principal", Map.of(P.name(), r.main().name()));
    System.out.printf("  execucao registrada em ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  private static Path path(final String name) throws CommandFailedException {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE, PARAMETROS e PREVISOES sao obrigatorios; "
          + "falta " + name));
    }
    return Path.of(value);
  }
}
