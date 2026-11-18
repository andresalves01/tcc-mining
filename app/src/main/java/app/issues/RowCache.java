package app.issues;

import app.study.Row;
import app.issues.Universe.Population;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The extraction on disk, so the later stages do not re-read the corpus.
 *
 * <p>One line per issue, with its start and its outer and inner verdicts (the inner one empty on
 * the outer test). Absence is an empty field and comes back as absence; a zero is a zero.
 */
public final class RowCache {

  private static final String SEP = "\t";
  // The column names below are the file format: the matrices already on disk were written with
  // them, so they stay as they are.
  private static final List<String> BASE = List.of("id", "chave", "projeto", "relator",
      "executor", "criada_em", "resolvida_em", "motivo", "movida");
  private static final String STARTED = "iniciada_em";
  /** The position of the first resolution, {@code resolvida_em}, in {@link #BASE}. */
  private static final int RESOLVED = 6;

  /** An issue, its columns, and per population its {@code [outer, inner]} verdicts. */
  public record Entry(IssueUnit unit, Row row, Map<Population, String[]> parts) {

    public String outer(final Population population) {
      final String[] p = parts.get(population);
      return p == null ? "" : p[0];
    }

    public String inner(final Population population) {
      final String[] p = parts.get(population);
      return p == null ? "" : p[1];
    }
  }

  private RowCache() {}

  /** The prefix of a population's two partition columns. */
  static String prefix(final Population p) {
    return switch (p) {
      case IN_PROGRESS -> "andamento";
      case FIRST_COMMIT -> "primeiro_commit";
    };
  }

  private static String text(final String s) {
    return s == null ? "" : s;
  }

  /**
   * One cache for the given populations. The start instant is written when a population's duration
   * does not start at creation, as neither {@link Population#IN_PROGRESS}'s nor {@link
   * Population#FIRST_COMMIT}'s does; a cache holds the start of one population only.
   */
  public static void write(final Path path, final List<Entry> entries, final List<String> columns,
      final List<Population> populations) throws IOException {
    if (populations.size() > 1) {
      throw new IllegalArgumentException("one start instant per cache: " + populations);
    }
    final boolean started = !populations.isEmpty();
    final StringBuilder out = new StringBuilder(1 << 24);
    out.append(String.join(SEP, BASE));
    if (started) {
      out.append(SEP).append(STARTED);
    }
    for (final Population p : populations) {
      out.append(SEP).append(prefix(p)).append("_externa").append(SEP).append(prefix(p))
          .append("_interna");
    }
    columns.forEach(column -> out.append(SEP).append(column));
    out.append('\n');
    for (final Entry entry : entries) {
      final IssueUnit u = entry.unit();
      out.append(String.join(SEP, u.issueId(), u.key(), u.project(), text(u.reporter()),
          text(u.executor()), u.createdAt().toString(), u.resolvedAt().toString(),
          text(u.reason()), u.moved() ? "1" : "0"));
      if (started) {
        out.append(SEP).append(u.startedAt().toString());
      }
      for (final Population p : populations) {
        out.append(SEP).append(entry.outer(p)).append(SEP).append(entry.inner(p));
      }
      for (final String column : columns) {
        out.append(SEP);
        final Double value = entry.row().values().get(column);
        if (value != null) {
          out.append(value);
        }
      }
      out.append('\n');
    }
    Files.createDirectories(path.toAbsolutePath().getParent());
    Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
  }

  /**
   * The population whose split every entry carries: the one a matrix was extracted for. Refuses
   * entries of no population, of more than one, or a mixture.
   */
  public static Population population(final List<Entry> entries) {
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("no rows: the matrix's population is unknown");
    }
    final Set<Population> found = entries.getFirst().parts().keySet();
    if (found.size() != 1) {
      throw new IllegalArgumentException(entries.getFirst().unit().issueId() + " carries the "
          + "split of " + found + "; a matrix carries the split of one population");
    }
    for (final Entry e : entries) {
      if (!e.parts().keySet().equals(found)) {
        throw new IllegalArgumentException(e.unit().issueId() + " carries the split of "
            + e.parts().keySet() + ", the first row that of " + found);
      }
    }
    return found.iterator().next();
  }

  /** Reads a cache: the partitions and the start are found by column name. */
  public static List<Entry> read(final Path path) throws IOException {
    return read(path, null, Set.of());
  }

  /**
   * The population whose split columns a cache's header holds, read from the header alone: no
   * row is read. Refuses a cache with the split columns of no population or of more than one.
   */
  public static Population splitPopulation(final Path path) throws IOException {
    final List<Population> found = splitPopulations(path);
    if (found.size() != 1) {
      throw new IOException(path + " holds the split columns of " + found + "; a matrix holds "
          + "those of one population");
    }
    return found.getFirst();
  }

  /**
   * The populations whose split columns a cache's header holds, in their order, read from the
   * header alone: no row is read.
   */
  public static List<Population> splitPopulations(final Path path) throws IOException {
    final String first;
    try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
      first = lines.findFirst().orElseThrow(() -> new IOException(path + " is empty"));
    }
    final Set<String> header = new HashSet<>(List.of(first.split(SEP, -1)));
    final List<Population> found = new ArrayList<>();
    for (final Population p : Population.values()) {
      if (header.contains(prefix(p) + "_externa")) {
        found.add(p);
      }
    }
    return List.copyOf(found);
  }

  /**
   * Every row of a cache with its id, key, project, start and split verdicts only, in the cache's
   * order: no value or outcome of any row is parsed, and of the dates only the start and, on the
   * training side, the first resolution. Each unit's start is its first commit that counts in a
   * first-commit matrix, null when the cache writes none; its first resolution is read only when
   * the row is on the outer training side of every population whose split it carries, crossing
   * issues included ({@link Verdict#outerTraining}), and is null on the outer test, whose dates
   * are never parsed. Each row has no values. What reads them is the split's rule ({@link
   * SplitRule#differences}: the temporal ones read the start, the instant they order the issues
   * by, and the revised one the training side's first resolution, which decides who crosses a
   * cut) and the split's sizes, never a model.
   */
  public static List<Entry> splits(final Path path) throws IOException {
    final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (lines.isEmpty()) {
      throw new IOException(path + " is empty");
    }
    final String[] header = lines.get(0).split(SEP, -1);
    for (int i = 0; i < BASE.size(); i++) {
      if (!BASE.get(i).equals(header[i])) {
        throw new IOException(path + ": column " + i + " should be " + BASE.get(i));
      }
    }
    int startedAt = -1;
    final Map<Population, Integer> partAt = new EnumMap<>(Population.class);
    for (int i = 0; i < header.length; i++) {
      if (header[i].equals(STARTED)) {
        startedAt = i;
      }
      for (final Population p : Population.values()) {
        if (header[i].equals(prefix(p) + "_externa")) {
          partAt.put(p, i);
        }
      }
    }
    final List<Entry> out = new ArrayList<>(lines.size());
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final String id = field(line, 0);
      final Map<Population, String[]> parts = new EnumMap<>(Population.class);
      partAt.forEach((p, k) -> put(parts, p, field(line, k), field(line, k + 1)));
      final Instant started = startedAt < 0 ? null : Instant.parse(field(line, startedAt));
      final boolean training = !parts.isEmpty()
          && parts.values().stream().allMatch(p -> Verdict.outerTraining(p[0]));
      final Instant resolved = training ? Instant.parse(field(line, RESOLVED)) : null;
      out.add(new Entry(new IssueUnit(id, field(line, 1), field(line, 2), null, null, null,
          resolved, null, false, started), new Row(id, Map.of(), Set.of()), parts));
    }
    return out;
  }

  /**
   * Only the rows whose outer verdict under {@code population} is {@code outer}, in the cache's
   * order ({@link #read(Path, Population, Set)}).
   */
  public static List<Entry> read(final Path path, final Population population,
      final String outer) throws IOException {
    return read(path, population, Set.of(outer));
  }

  /**
   * The rows of the outer training side under {@code population}, crossing issues included
   * ({@link Verdict#OUTER_TRAINING}), in the cache's order; the outer test is never read beyond
   * its split field.
   */
  public static List<Entry> training(final Path path, final Population population)
      throws IOException {
    return read(path, population, Verdict.OUTER_TRAINING);
  }

  /**
   * Only the rows whose outer verdict under {@code population} is one of {@code outer}, in the
   * cache's order. Of every other row nothing but that one field is read: it is cut out by its
   * position before the line is split or any value, date or verdict of it parsed, so the rows on
   * the other side of the outer split are never read. Refuses a cache without the population's
   * split columns.
   */
  public static List<Entry> read(final Path path, final Population population,
      final Set<String> outer) throws IOException {
    final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (lines.isEmpty()) {
      throw new IOException(path + " is empty");
    }
    final String[] header = lines.get(0).split(SEP, -1);
    final Map<String, Integer> at = new HashMap<>();
    for (int i = 0; i < header.length; i++) {
      at.put(header[i], i);
    }
    for (int i = 0; i < BASE.size(); i++) {
      if (!BASE.get(i).equals(header[i])) {
        throw new IOException(path + ": column " + i + " should be " + BASE.get(i));
      }
    }
    final Integer started = at.get(STARTED);
    final Map<Population, Integer> partAt = new EnumMap<>(Population.class);
    for (final Population p : Population.values()) {
      final Integer k = at.get(prefix(p) + "_externa");
      if (k != null) {
        partAt.put(p, k);
      }
    }
    final int fixed = firstValue(at);
    final int outerAt;
    if (population == null) {
      outerAt = -1;
    } else if (partAt.containsKey(population)) {
      outerAt = partAt.get(population);
    } else {
      throw new IOException(path + " has no " + prefix(population) + "_externa column");
    }
    final List<Entry> entries = new ArrayList<>(lines.size());
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank() || outerAt >= 0 && !outer.contains(field(line, outerAt))) {
        continue;
      }
      final String[] f = line.split(SEP, -1);
      final Map<String, Double> values = new HashMap<>(header.length * 2);
      final Set<String> absent = new HashSet<>();
      for (int i = fixed; i < header.length && i < f.length; i++) {
        if (f[i].isEmpty()) {
          absent.add(header[i]);
        } else {
          values.put(header[i], Double.parseDouble(f[i]));
        }
      }
      absent.removeIf(name -> name.endsWith("_ausente"));
      final Map<Population, String[]> parts = new EnumMap<>(Population.class);
      partAt.forEach((p, k) -> put(parts, p, f[k], f[k + 1]));
      final Instant created = Instant.parse(f[5]);
      entries.add(new Entry(new IssueUnit(f[0], f[1], f[2], empty(f[3]), empty(f[4]),
          created, Instant.parse(f[6]), empty(f[7]), "1".equals(f[8]),
          started == null ? created : Instant.parse(f[started])),
          new Row(f[0], values, absent), parts));
    }
    return entries;
  }

  /** The field at {@code index} of a line, found by counting separators; empty past the end. */
  private static String field(final String line, final int index) {
    int start = 0;
    for (int i = 0; i < index; i++) {
      start = line.indexOf(SEP, start) + 1;
      if (start == 0) {
        return "";
      }
    }
    final int end = line.indexOf(SEP, start);
    return end < 0 ? line.substring(start) : line.substring(start, end);
  }

  /** The value columns of a cache, in the order its header lists them. */
  public static List<String> columns(final Path path) throws IOException {
    final String first;
    try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
      first = lines.findFirst().orElseThrow(() -> new IOException(path + " is empty"));
    }
    final String[] header = first.split(SEP, -1);
    final Map<String, Integer> at = new HashMap<>();
    for (int i = 0; i < header.length; i++) {
      at.put(header[i], i);
    }
    return List.of(header).subList(firstValue(at), header.length);
  }

  /** Where the value columns start: after the base, the start and every partition column. */
  private static int firstValue(final Map<String, Integer> at) {
    int fixed = BASE.size() + (at.containsKey(STARTED) ? 1 : 0);
    for (final Population p : Population.values()) {
      final Integer k = at.get(prefix(p) + "_externa");
      if (k != null) {
        fixed = Math.max(fixed, k + 2);
      }
    }
    return fixed;
  }

  private static String empty(final String s) {
    return s.isEmpty() ? null : s;
  }

  private static void put(final Map<Population, String[]> parts, final Population p,
      final String outer, final String inner) {
    if (!outer.isEmpty()) {
      parts.put(p, new String[] {outer, inner});
    }
  }
}
