package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.ProjectSplit;
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
 * Scores the in-progress study's external test once, as its pre-registration lays it out. Written
 * after that file was committed.
 *
 * <p>Before anything is fitted it checks the matrix and the parameters by SHA-256 and the sizes of
 * every side against the registration, and refuses on any difference. Then each of the eleven
 * cells is refitted on the whole outer training side with its frozen alpha and the chosen lambda
 * times the ratio of rows; the weights are printed; the three references are learned on that side;
 * the outer test is scored and read every way section 8 names.
 *
 * <p>Reads CACHE and PARAMETERS; writes every prediction to PREDICTIONS; records the run in ISSUES_DB
 * by insertion.
 */
public final class IssuesInProgressReservedCommand implements CommandRunner {

  private static final Population P = Population.IN_PROGRESS;
  private static final String REGISTRATION = "in-progress pre-registration";

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path cache = path("CACHE");
    final Path params = path("PARAMETERS");
    final Path out = path("PREDICTIONS");

    final String matrixSha;
    final String paramsSha;
    final List<RowCache.Entry> entries;
    try {
      matrixSha = IssuesReservedCommand.sha256(cache);
      paramsSha = IssuesReservedCommand.sha256(params);
      entries = RowCache.read(cache);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }
    final List<IssuesEvaluationTask.Frozen> frozen =
        IssuesCheckCommand.parse(params.toString());

    // every gate before any row of the test is scored
    final List<String> problems = new ArrayList<>();
    final Fitting.Sides outer = Fitting.sides(entries, Fitting.Mode.RESERVED, P);
    final Fitting.Sides inner = Fitting.sides(entries, Fitting.Mode.INNER, P);
    final ProjectSplit.Sizes found = new ProjectSplit.Sizes(
        outer.fitUnits().size(), outer.scoreUnits().size(), inner.fitUnits().size(),
        inner.scoreUnits().size());
    if (frozen.stream().anyMatch(f -> f.population() != P)) {
      problems.add("parameters: there are lines from another population");
    }
    problems.addAll(RegistrationInProgress.differences(matrixSha, paramsSha, frozen.size(), found));

    System.out.printf("%n  DURATION FROM IN PROGRESS -- OUTER TEST, REGISTERED RUN%n");
    System.out.printf("  registration: %s%n", REGISTRATION);
    System.out.printf("  matrix     %s  sha256 %s%n", cache, matrixSha);
    System.out.printf("  parameters %s  sha256 %s (%d lines)%n", params, paramsSha,
        frozen.size());
    System.out.printf("  %-24s training %,7d  test %,6d  inner fit/selection %,6d / %,6d%n",
        IssuesPrinting.name(P), found.train(), found.test(), found.innerFit(),
        found.innerSelection());
    if (!problems.isEmpty()) {
      final List<String> lines = new ArrayList<>();
      lines.add("  REFUSED, nothing was scored: what was found differs from the registration");
      problems.forEach(x -> lines.add("    " + x));
      throw new CommandFailedException(lines);
    }
    System.out.printf("  check against the registration: all equal; the outer test is scored "
        + "now, once%n");

    final IssuesEvaluationTask.Report r = new IssuesEvaluationTask(context.progress()).run(
        entries, frozen, P, Fitting.Mode.RESERVED);

    System.out.printf("%n  ==== OUTER TEST -- %s ====%n", IssuesPrinting.name(P));
    System.out.printf("  refit on the whole outer training side (%,d rows); scored %,d rows "
        + "of the outer test%n", r.fitRows(), r.scoredRows());
    System.out.printf("  main reference by the rule (P12: lowest MAE on the inner selection among "
        + "three): %s (constant %.2f h, project median %.2f h, pair median %.2f h); the "
        + "others are secondary%n", r.main().name().toUpperCase(), r.main().constantMae(),
        r.main().projectMae(), r.main().pairMae());
    System.out.printf("  test projects with no issue in training (there the project median is the "
        + "constant): %,d issues, %s%n", r.unseenRows(), r.unseenProjects());

    System.out.printf("%n  PARAMETERS: alpha frozen; final lambda = chosen lambda x %,d / "
        + "%,d; no recalibration%n", r.fitRows(), r.innerFitRows());
    for (final IssuesEvaluationTask.Frozen f : frozen) {
      final var fitted = r.evaluated().stream()
          .filter(e -> e.fitted().cell().label().equals(f.cell())).findFirst().orElseThrow()
          .fitted();
      System.out.printf("    %-26s alpha %.2f  chosen lambda %-14.8g final %-14.8g %d columns, "
          + "%d with weight%n", f.cell(), fitted.alpha(), f.lambda(), fitted.lambda(),
          fitted.columns().size(), fitted.selected());
    }
    System.out.printf("%n  The weights below come from the outer training side only: they describe the fit, "
        + "they are neither stable importance nor causal effect.%n");
    IssuesPrinting.weights(r);

    System.out.printf("%n  ---- OUTER TEST SCORING -- %s ----%n", IssuesPrinting.name(P));
    final List<Map<String, Object>> rows = new ArrayList<>(IssuesPrinting.evaluation(r));
    rows.addAll(IssuesReservedCommand.comparisonRows(r));

    final StringBuilder predictions = new StringBuilder();
    IssuesReservedCommand.appendPredictions(predictions, r);
    try {
      Files.writeString(out, predictions.toString());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + out + ": " + e));
    }
    System.out.printf("%n  predictions written to %s%n", out);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", "issues-in-progress-reserved");
    run.put("registration", REGISTRATION);
    run.put("outer_test", "scored");
    run.put("matrix_sha256", matrixSha);
    run.put("parameters_sha256", paramsSha);
    run.put("main_reference", Map.of(P.name(), r.main().name()));
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  private static Path path(final String name) throws CommandFailedException {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE, PARAMETERS and PREDICTIONS are required; "
          + "missing " + name));
    }
    return Path.of(value);
  }
}
