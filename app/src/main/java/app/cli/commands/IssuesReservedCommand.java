package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.IssueUnit;
import app.issues.Registration;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.model.Fitting;
import app.pipeline.IssuesEvaluationTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores the external test once, as the study's pre-registration lays it out. Written after that
 * file was committed.
 *
 * <p>Before anything is fitted it checks the matrix and the parameters by SHA-256, the sizes of
 * every side of every population against the registration, and refuses
 * on any difference. Then, per population: each cell is refitted on the whole outer training side
 * with its frozen alpha and the lambda of decision 20; the weights are printed; the references are
 * learned on that side; the outer test is scored and read every way section 8 names.
 *
 * <p>Reads CACHE and PARAMETERS; writes every prediction to PREDICTIONS; records the
 * run in ISSUES_DB by insertion. All three populations, always: POPULATIONS is not read.
 */
public final class IssuesReservedCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path cache = path("CACHE");
    final Path params = path("PARAMETERS");
    final Path out = path("PREDICTIONS");

    final String matrixSha;
    final String paramsSha;
    final List<RowCache.Entry> entries;
    try {
      matrixSha = sha256(cache);
      paramsSha = sha256(params);
      entries = RowCache.read(cache);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }
    final List<IssuesEvaluationTask.Frozen> frozen =
        IssuesCheckCommand.parse(params.toString());

    // every gate, for every population, before any row of the test is scored
    final Map<Population, Registration.Sizes> found = new EnumMap<>(Population.class);
    final List<String> problems = new ArrayList<>();
    for (final Population p : Population.MAIN) {
      final Fitting.Sides outer = Fitting.sides(entries, Fitting.Mode.RESERVED, p);
      final Fitting.Sides inner = Fitting.sides(entries, Fitting.Mode.INNER, p);
      found.put(p, new Registration.Sizes(outer.fitUnits().size(), outer.scoreUnits().size(),
          inner.fitUnits().size(), inner.scoreUnits().size()));
      if (frozen.stream().filter(f -> f.population() == p).count() != 11) {
        problems.add(p + ": the parameters do not hold eleven cells");
      }
    }
    problems.addAll(Registration.differences(matrixSha, paramsSha, frozen.size(), found));

    System.out.printf("%n  ISSUE DURATION -- OUTER TEST, REGISTERED RUN%n");
    System.out.printf("  registration: the study's pre-registration%n");
    System.out.printf("  matrix     %s  sha256 %s%n", cache, matrixSha);
    System.out.printf("  parameters %s  sha256 %s (%d lines)%n", params, paramsSha,
        frozen.size());
    for (final Population p : Population.MAIN) {
      final Registration.Sizes s = found.get(p);
      System.out.printf("  %-24s training %,7d  test %,6d  inner fit/selection %,6d / %,6d%n",
          IssuesPrinting.name(p), s.train(), s.test(), s.innerFit(), s.innerSelection());
    }
    if (!problems.isEmpty()) {
      final List<String> lines = new ArrayList<>();
      lines.add("  REFUSED, nothing was scored: what was found differs from the registration");
      problems.forEach(x -> lines.add("    " + x));
      throw new CommandFailedException(lines);
    }
    System.out.printf("  check against the registration: all equal; the outer test is scored "
        + "now, once%n");

    final List<Map<String, Object>> rows = new ArrayList<>();
    final StringBuilder predictions = new StringBuilder();
    final Map<String, Object> mains = new LinkedHashMap<>();
    for (final Population p : Population.MAIN) {
      final IssuesEvaluationTask.Report r = new IssuesEvaluationTask(context.progress()).run(
          entries, frozen, p, Fitting.Mode.RESERVED);
      mains.put(p.name(), r.main().name());

      System.out.printf("%n  ==== OUTER TEST -- %s ====%n", IssuesPrinting.name(p));
      System.out.printf("  refit on the whole outer training side (%,d rows); scored %,d rows "
          + "of the outer test%n", r.fitRows(), r.scoredRows());
      System.out.printf("  main reference by the rule of decision 17 (lowest MAE on the inner "
          + "selection): %s (constant %.2f h, project median %.2f h); the other is secondary%n",
          r.main().name().toUpperCase(), r.main().constantMaeHours(), r.main().projectMaeHours());
      System.out.printf("  test projects with no issue in training (there the project median is the "
          + "constant): %,d issues, %s%n", r.unseenRows(), r.unseenProjects());

      System.out.printf("%n  PARAMETERS: alpha frozen; final lambda = chosen lambda x %,d / "
          + "%,d (decision 20); no recalibration (decision 22)%n", r.fitRows(), r.innerFitRows());
      for (final IssuesEvaluationTask.Frozen f : frozen) {
        if (f.population() != p) {
          continue;
        }
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

      System.out.printf("%n  ---- OUTER TEST SCORING -- %s ----%n",
          IssuesPrinting.name(p));
      rows.addAll(IssuesPrinting.evaluation(r));
      rows.addAll(comparisonRows(r));
      appendPredictions(predictions, r);
    }

    try {
      Files.writeString(out, predictions.toString());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + out + ": " + e));
    }
    System.out.printf("%n  predictions written to %s%n", out);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", "issues-reserved");
    run.put("registration", "pre-registration");
    run.put("outer_test", "scored");
    run.put("matrix_sha256", matrixSha);
    run.put("parameters_sha256", paramsSha);
    run.put("main_reference", mains);
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  static List<Map<String, Object>> comparisonRows(final IssuesEvaluationTask.Report r) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    final List<app.issues.model.ProjectBootstrap.Gap> gaps = new ArrayList<>(
        r.familiesByProjects());
    gaps.addAll(r.blocksByProjects());
    final List<app.study.model.Bootstrap.Comparison> pairs = new ArrayList<>(r.families());
    pairs.addAll(r.blocks());
    for (int i = 0; i < gaps.size(); i++) {
      final var g = gaps.get(i);
      final var c = pairs.get(i);
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("population", r.population().name());
      m.put("kind", "comparison");
      m.put("a", g.a());
      m.put("b", g.b());
      m.put("mae_diff_hours", g.gapHours());
      m.put("ci_projects", List.of(g.low(), g.high()));
      m.put("tie", g.tied());
      m.put("ci_rows", List.of(c.lowGap(), c.highGap()));
      rows.add(m);
    }
    return rows;
  }

  /** One line per scored row: its observed duration and every specification's prediction. */
  static void appendPredictions(final StringBuilder out, final IssuesEvaluationTask.Report r) {
    final List<String> labels = new ArrayList<>();
    final List<double[]> hours = new ArrayList<>();
    for (final var e : r.references()) {
      labels.add(e.fitted().score().label());
      hours.add(e.fitted().predictedHours());
    }
    for (final var e : r.evaluated()) {
      labels.add(e.fitted().cell().label());
      hours.add(e.fitted().predictedHours());
    }
    if (out.isEmpty()) {
      out.append("population\tid\tkey\tproject\tobserved_h");
      labels.forEach(l -> out.append('\t').append(l));
      out.append('\n');
    }
    for (int i = 0; i < r.scoredUnits().size(); i++) {
      final IssueUnit u = r.scoredUnits().get(i);
      out.append(r.population().name()).append('\t').append(u.issueId()).append('\t')
          .append(u.key()).append('\t').append(u.project()).append('\t');
      out.append(r.observed()[i]);
      for (final double[] h : hours) {
        out.append('\t').append(h[i]);
      }
      out.append('\n');
    }
  }

  private static Path path(final String name) throws CommandFailedException {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE, PARAMETERS and PREDICTIONS are required; "
          + "missing " + name));
    }
    return Path.of(value);
  }

  static String sha256(final Path path) throws IOException {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
