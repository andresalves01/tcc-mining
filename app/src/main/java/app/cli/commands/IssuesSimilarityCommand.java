package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.DescriptionCleaner;
import app.issues.Extractor;
import app.issues.RowCache;
import app.issues.TeamColumns;
import app.issues.Universe.Population;
import app.pipeline.IssuesSimilarityTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The similarity stage of the description-similarity chain, after the in-progress extraction.
 * Separate from the registered in-progress chain: it reads that chain's matrix and changes nothing
 * of it.
 *
 * <p>Reads CACHE, the revised team columns {@code issues-in-progress-team} wrote to TEAM_COLUMNS,
 * and the corpus; refuses, writing and recording nothing, unless the four split sizes are the
 * registered study's and the team file holds exactly the matrix's issues. Writes the augmented
 * matrix -- the extracted columns, the four team columns, then the similarity columns -- to MATRIX,
 * the diagnostics as tab-separated files to REPORT_DIR, and records the run, with the SHA-256 of
 * every file it read or wrote, in ISSUES_DB.
 */
public final class IssuesSimilarityCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity";

  private static final Population P = Population.IN_PROGRESS;

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path cache = path("CACHE");
    final Path matrix = path("MATRIX");
    final Path reports = path("REPORT_DIR");
    final Path teamFile = path("TEAM_COLUMNS");
    final long began = System.nanoTime();

    final String cacheSha;
    final String teamSha;
    final List<RowCache.Entry> extracted;
    final Map<String, Map<String, Double>> team;
    final List<String> columns;
    if (same(cache, matrix) || same(teamFile, matrix) || same(cache, teamFile)) {
      throw new CommandFailedException(List.of("  REFUSED: CACHE, TEAM_COLUMNS and MATRIX must be "
          + "three different files; the inputs are never overwritten"));
    }
    try {
      cacheSha = IssuesReservedCommand.sha256(cache);
      extracted = RowCache.read(cache);
      columns = new ArrayList<>(RowCache.columns(cache));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read CACHE " + cache + ": " + e));
    }
    try {
      teamSha = IssuesReservedCommand.sha256(teamFile);
      team = TeamColumns.read(teamFile);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read TEAM_COLUMNS " + teamFile
          + " (written by issues-in-progress-team): " + e));
    }
    final List<String> already = java.util.stream.Stream.concat(Extractor.TEAM_REVISED.stream(),
        Augmentation.columns().stream()).filter(columns::contains).toList();
    if (!already.isEmpty()) {
      throw new CommandFailedException(List.of("  REFUSED: " + cache + " already has "
          + String.join(", ", already) + "; give the matrix the extraction wrote"));
    }
    System.out.printf("%n  DURATION FROM IN PROGRESS -- DESCRIPTION SIMILARITY%n");
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", cache, cacheSha,
        extracted.size());
    System.out.printf("  team columns read %s  sha256 %s%n", teamFile, teamSha);
    final List<RowCache.Entry> entries;
    try {
      entries = TeamColumns.join(extracted, team);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }
    columns.addAll(Extractor.TEAM_REVISED);

    final IssuesSimilarityTask.Report r =
        new IssuesSimilarityTask(context.study(), context.progress()).run(entries);
    final var s = r.sizes();
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    if (!r.refused().isEmpty()) {
      final List<String> lines = new ArrayList<>();
      lines.add("  REFUSED, nothing was written or recorded: the matrix is not the registered "
          + "study's population");
      r.refused().forEach(x -> lines.add("    " + x));
      throw new CommandFailedException(lines);
    }

    final Map<String, String> written = new LinkedHashMap<>();
    try {
      writeMatrix(matrix, r, columns);
      written.put("matrix", IssuesReservedCommand.sha256(matrix));
      for (final Map.Entry<String, List<String>> f : diagnostics(r).entrySet()) {
        Files.createDirectories(reports);
        final Path file = reports.resolve(f.getKey());
        Files.write(file, f.getValue());
        written.put(f.getKey(), IssuesReservedCommand.sha256(file));
      }
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write the outputs: " + e));
    }

    System.out.printf("%n  %,d of %,d issues have a description at their first In Progress "
        + "(cleaner %s)%n", r.described(), entries.size(), DescriptionCleaner.VERSION);
    System.out.printf("%n  COVERAGE BY SPLIT%n");
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Augmentation.Coverage c : r.result().coverage()) {
      if (c.by().equals("split")) {
        System.out.printf("    %-26s %-18s %,6d of %,6d%n", c.column(), c.group(), c.observed(),
            c.rows());
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("column", c.column());
        row.put("split", c.group());
        row.put("rows", c.rows());
        row.put("observed", c.observed());
        rows.add(row);
      }
    }
    System.out.printf("%n  BLOCKS%n");
    for (final Augmentation.Block b : r.result().blocks()) {
      System.out.printf("    %-6s %d  %,6d rows  %s to %s  history %,6d%n",
          name(b.variant()), b.index(), b.rows(), Instant.ofEpochMilli(b.begins()),
          Instant.ofEpochMilli(b.last()), b.history());
    }
    System.out.printf("%n  matrix written to %s (%d new columns)%n", matrix,
        Augmentation.columns().size());
    System.out.printf("  diagnostics written to %s: %s%n", reports,
        String.join(", ", written.keySet().stream().filter(k -> !k.equals("matrix")).toList()));

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    run.put("cache_sha256", cacheSha);
    run.put("team_columns_sha256", teamSha);
    run.put("written_sha256", written);
    run.put("cleaner", DescriptionCleaner.VERSION);
    run.put("jaccard_n", Augmentation.JACCARD_N);
    run.put("blocks", Augmentation.BLOCKS);
    run.put("rows", entries.size());
    run.put("described", r.described());
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    run.put("processors", Runtime.getRuntime().availableProcessors());
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  /** The matrix as read, every column and header name kept, with the new columns after them. */
  static void writeMatrix(final Path out, final IssuesSimilarityTask.Report r,
      final List<String> columns) throws IOException {
    final List<String> all = new ArrayList<>(columns);
    all.addAll(Augmentation.columns());
    RowCache.write(out, r.entries(), all, List.of(P));
  }

  /** Each diagnostic file by name, as lines with a header. */
  static Map<String, List<String>> diagnostics(final IssuesSimilarityTask.Report r) {
    final Map<String, String> project = new HashMap<>();
    r.entries().forEach(e -> project.put(e.unit().issueId(), e.unit().project()));
    final Map<String, List<String>> out = new LinkedHashMap<>();

    final List<String> queries = new ArrayList<>();
    final List<String> neighbours = new ArrayList<>();
    final StringBuilder head = new StringBuilder("id\tvariant\tsplit\tblock\treference\ttokens"
        + "\tcandidates");
    Augmentation.JACCARD_N.forEach(n -> head.append("\tused_").append(n));
    queries.add(head.toString());
    neighbours.add("id\tvariant\trank\tneighbour\tneighbour_project\tdistance");
    for (final Augmentation.Query q : r.result().queries()) {
      final StringBuilder line = new StringBuilder(String.join("\t", q.id(), name(q.variant()),
          q.split(), q.block(), Instant.ofEpochMilli(q.reference()).toString(),
          Integer.toString(q.tokens()), Integer.toString(q.candidates())));
      Augmentation.JACCARD_N.forEach(n -> line.append('\t').append(q.used(n)));
      queries.add(line.toString());
      for (int k = 0; k < q.nearest().size(); k++) {
        final Augmentation.Neighbour n = q.nearest().get(k);
        neighbours.add(String.join("\t", q.id(), name(q.variant()), Integer.toString(k + 1),
            n.id(), project.get(n.id()), Double.toString(n.distance())));
      }
    }
    out.put("jaccard-queries.tsv", queries);
    out.put("jaccard-neighbours.tsv", neighbours);

    final List<String> coverage = new ArrayList<>(List.of("column\tby\tgroup\trows\tobserved"));
    r.result().coverage().forEach(c -> coverage.add(String.join("\t", c.column(), c.by(),
        c.group(), Integer.toString(c.rows()), Integer.toString(c.observed()))));
    out.put("coverage.tsv", coverage);

    final List<String> blocks = new ArrayList<>(List.of("variant\tblock\trows\tbegins\tlast"
        + "\thistory"));
    r.result().blocks().forEach(b -> blocks.add(String.join("\t", name(b.variant()),
        Integer.toString(b.index()), Integer.toString(b.rows()),
        Instant.ofEpochMilli(b.begins()).toString(), Instant.ofEpochMilli(b.last()).toString(),
        Integer.toString(b.history()))));
    out.put("blocks.tsv", blocks);
    return out;
  }

  private static boolean same(final Path a, final Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  private static String name(final Augmentation.Variant v) {
    return v.name().toLowerCase(Locale.ROOT);
  }

  private static Path path(final String name) throws CommandFailedException {
    final String fromEnv = System.getenv(name);
    final String value = fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE, TEAM_COLUMNS, MATRIX and REPORT_DIR are "
          + "required; "
          + "missing " + name));
    }
    return Path.of(value);
  }
}
