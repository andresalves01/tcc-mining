package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper5.Attributes;
import app.paper5.Row;
import app.pipeline.BuildAttributesTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Extracts every pre-merge construct for the whole population and prints what it found. Writes
 * nothing.
 *
 * <p>With {@code ORACULO=<path>} it also diffs the result, row by row and column by column,
 * against the reference extraction the validation produced in Python. That file was computed by a
 * different implementation, in a different language, from a different export of the same corpus,
 * and it was the input to the time-travel test -- so agreeing with it is evidence the port did not
 * quietly change a definition, which reading the two implementations side by side is not.
 *
 * <p>Comparison is exact. Three columns carry a known, bounded difference: the reference read a
 * project's first activity from a CSV that stored it truncated to the day, an artefact of that
 * export, while this implementation uses the full instant; and the reference stored
 * {@code owner_merged_ratio} rounded to six decimals. Those rows are counted in their own
 * column of the report rather than tolerated, so the size of the effect is visible instead of
 * hidden behind an epsilon.
 */
public final class Paper5ExtractCommand implements CommandRunner {

  /** The reference file names the textual construct by its old name. Mapped, not renamed. */
  private static final Map<String, String> ORACLE_NAMES =
      Map.of("description_word_count", "title_description_word_count");

  private static final double EPSILON = 1e-9;

  @Override
  public void run(final Context context) throws CommandFailedException {
    final BuildAttributesTask task = new BuildAttributesTask(
        context.units(), context.paper5Tables(), context.progress());

    final BuildAttributesTask.Report report;
    try {
      report = task.run(context.config().trainShare(), context.config().minResolvedBeforeCut());
    } catch (final BuildAttributesTask.PopulationEmptyException
        | app.paper5.Population.MissingCreatorException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    System.out.printf("%n  ATRIBUTOS DO ARTIGO 5%n%n");
    System.out.printf("  unidades extraidas : %,d%n", report.rows().size());
    System.out.printf("  MIN_RESOLVED       : %d%s%n", report.population().minResolved(),
        report.population().minResolved() <= 0 ? "  (sem filtro de historico herdado)" : "");
    System.out.printf("  PRs sem projeto resolvido no corpus : %,d%n", report.unresolvedProject());
    System.out.printf("  area_hotness_90d ausente por projeto sem commit na janela : %,d%n",
        report.hotnessWithoutDenominator());
    System.out.printf("  area_hotness_90d ausente por diff sem nenhum arquivo      : %,d%n%n",
        report.hotnessWithoutPaths());

    System.out.printf("  %-30s %10s %9s %12s %9s %9s%n",
        "coluna", "com valor", "ausente", "zero valido", "aus.TRAIN", "aus.TEST");
    report.coverage().values().stream()
        .sorted((a, b) -> a.column().compareTo(b.column()))
        .forEach(c -> System.out.printf("  %-30s %10d %9d %12d %9d %9d%n",
            c.column(), c.withValue(), c.absent(), c.validZero(),
            c.absentTrain(), c.absentTest()));

    System.out.printf("%n  classe temporal de cada construto%n");
    Attributes.all().stream()
        .filter(a -> report.coverage().containsKey(a.name()))
        .forEach(a -> System.out.printf("    %-30s %s%n", a.name(), a.temporalClass()));

    final String cache = System.getenv("CACHE");
    if (cache != null && !cache.isBlank()) {
      try {
        final java.util.List<app.paper5.RowCache.Entry> entries =
            new java.util.ArrayList<>(report.rows().size());
        final java.util.Map<String, app.paper5.Unit> units =
            new java.util.HashMap<>(report.rows().size() * 2);
        report.population().train().forEach(unit -> units.put(unit.pullRequestId(), unit));
        report.population().test().forEach(unit -> units.put(unit.pullRequestId(), unit));
        final java.util.Set<String> onTrain = new java.util.HashSet<>();
        report.population().train().forEach(unit -> onTrain.add(unit.pullRequestId()));
        for (final Row row : report.rows()) {
          final app.labels.SplitPart inner = report.population().innerParts().get(row.unitId());
          entries.add(new app.paper5.RowCache.Entry(units.get(row.unitId()), row,
              onTrain.contains(row.unitId()) ? "TRAIN" : "TEST",
              inner == null ? "" : inner.name()));
        }
        app.paper5.RowCache.write(Path.of(cache), entries, app.paper5.Extractor.STORED);
        System.out.printf("%n  cache gravado em %s -- %,d linhas. E um arquivo de trabalho desta"
            + " pasta, nao um banco.%n", cache, entries.size());
      } catch (final IOException e) {
        throw new CommandFailedException(List.of("  nao consegui gravar " + cache + ": " + e));
      }
    }

    final String oracle = System.getenv("ORACULO");
    if (oracle == null || oracle.isBlank()) {
      System.out.printf("%n  nada foi escrito. Defina ORACULO=<caminho> para conferir contra a "
          + "extracao de referencia.%n");
      return;
    }
    compare(report.rows(), Path.of(oracle));
  }

  private void compare(final List<Row> rows, final Path oracle) throws CommandFailedException {
    final Map<String, Map<String, String>> reference;
    try {
      reference = read(oracle);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler " + oracle + ": " + e));
    }

    final Map<String, Row> byId = new HashMap<>(rows.size() * 2);
    rows.forEach(row -> byId.put(row.unitId(), row));

    // [0] rows that differ at all, [1] of those, the ones the reference's day truncation explains.
    final Map<String, int[]> divergences = new TreeMap<>();
    final List<String> examples = new ArrayList<>();
    int compared = 0;
    int missing = 0;

    for (final Map.Entry<String, Map<String, String>> entry : reference.entrySet()) {
      final Row row = byId.get(entry.getKey());
      if (row == null) {
        missing++;
        continue;
      }
      compared++;
      for (final Map.Entry<String, String> cell : entry.getValue().entrySet()) {
        final String column = ORACLE_NAMES.getOrDefault(cell.getKey(), cell.getKey());
        if (!row.values().containsKey(column) && !row.isAbsent(column)) {
          continue;
        }
        final String expected = cell.getValue();
        final boolean expectedAbsent = expected.isEmpty();
        if (expectedAbsent != row.isAbsent(column)) {
          record(divergences, examples, entry.getKey(), column,
              expectedAbsent ? "ausente" : expected,
              row.isAbsent(column) ? "ausente" : String.valueOf(row.values().get(column)), false);
          continue;
        }
        if (expectedAbsent) {
          continue;
        }
        final double got = row.values().get(column);
        final double want = Double.parseDouble(expected);
        if (Math.abs(got - want) > EPSILON) {
          final boolean explained = Math.abs(got - want) <= explainable(column, row) + EPSILON;
          record(divergences, explained ? null : examples, entry.getKey(), column,
              expected, String.valueOf(got), explained);
        }
      }
    }

    System.out.printf("%n  CONFERENCIA CONTRA %s%n%n", oracle.getFileName());
    System.out.printf("  linhas comparadas          : %,d%n", compared);
    System.out.printf("  linhas do oraculo ausentes : %,d%n", missing);
    if (divergences.isEmpty()) {
      System.out.printf("%n  ZERO DIVERGENCIAS em todas as colunas com oraculo.%n");
    } else {
      System.out.printf("%n  %-30s %10s %14s %12s%n",
          "coluna", "divergem", "explicadas", "restantes");
      divergences.forEach((column, counts) -> System.out.printf("    %-28s %10d %14d %12d%n",
          column, counts[0], counts[1], counts[0] - counts[1]));
      System.out.printf("%n  'explicadas' sao as diferencas dentro de um limite conhecido e"
          + " registrado: a razao do criador foi gravada com seis decimais no arquivo de"
          + " referencia, e a origem do projeto foi gravada truncada no dia, o que desloca a"
          + " idade em no maximo um.%n");
      if (!examples.isEmpty()) {
        System.out.printf("%n  casos nao explicados:%n");
        examples.stream().limit(10).forEach(line -> System.out.printf("    %s%n", line));
      }
    }
    System.out.printf("%n  nada foi escrito.%n");
  }

  /**
   * How far a column may differ from the reference for a reason already known and written down.
   *
   * <p>Two such reasons, both artefacts of how the reference was exported rather than of what it
   * computed, and both bounded. Anything outside these bounds, and every other column, must match
   * to the last bit.
   */
  private static double explainable(final String column, final Row row) {
    switch (column) {
      // The reference stored the ratio rounded to six decimals.
      case "owner_merged_ratio":
        return 5e-7;
      // The reference read the project's first activity from a CSV truncated to the day.
      case "project_age_days":
        return 1.0;
      case "project_age_x_team_size":
        return Math.max(1.0, row.values().getOrDefault("team_size_90d", 1.0));
      default:
        return 0.0;
    }
  }

  private static void record(final Map<String, int[]> divergences, final List<String> examples,
      final String unit, final String column, final String expected, final String got,
      final boolean explainedByTruncation) {

    final int[] counts = divergences.computeIfAbsent(column, key -> new int[2]);
    counts[0]++;
    if (explainedByTruncation) {
      counts[1]++;
      return;
    }
    if (examples != null && examples.size() < 10) {
      examples.add(unit + "  " + column + ": oraculo=" + expected + "  java=" + got);
    }
  }

  /** The reference extraction: a plain CSV, one row per unit, no quoted fields. */
  private static Map<String, Map<String, String>> read(final Path path) throws IOException {
    final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    final String[] header = lines.get(0).split(",", -1);
    final Map<String, Map<String, String>> byUnit = new HashMap<>(lines.size() * 2);
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final String[] fields = line.split(",", -1);
      final Map<String, String> row = new HashMap<>(header.length * 2);
      for (int i = 1; i < header.length && i < fields.length; i++) {
        row.put(header[i], fields[i]);
      }
      byUnit.put(fields[0], row);
    }
    return byUnit;
  }
}
