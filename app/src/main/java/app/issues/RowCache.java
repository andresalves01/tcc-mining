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
 * The extraction on disk, so the grid does not re-read the corpus.
 *
 * <p>One line per studied issue, with its outer and inner verdict under each population (empty when
 * the issue is not in it). Absence is an empty field and comes back as absence; a zero is a zero.
 */
public final class RowCache {

  private static final String SEP = "\t";
  // The column names below are the file format: the caches already on disk, and the matrices whose
  // SHA-256 the pre-registrations froze, were written with them, so they stay as they are.
  private static final List<String> BASE = List.of("id", "chave", "projeto", "relator",
      "executor", "criada_em", "resolvida_em", "motivo", "movida");
  private static final String STARTED = "iniciada_em";

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
      case FIXED -> "fixed";
      case BROAD -> "ampla";
      case INHERITED -> "herdada";
      case IN_PROGRESS -> "andamento";
    };
  }

  private static String text(final String s) {
    return s == null ? "" : s;
  }

  /** The main study's cache: the three populations, the duration from creation. */
  public static void write(final Path path, final List<Entry> entries, final List<String> columns)
      throws IOException {
    write(path, entries, columns, Population.MAIN);
  }

  /**
   * One cache for the given populations. The start instant is written only when a population's
   * duration does not start at creation, so the main study's file keeps its layout byte for byte.
   */
  public static void write(final Path path, final List<Entry> entries, final List<String> columns,
      final List<Population> populations) throws IOException {
    final boolean started = populations.contains(Population.IN_PROGRESS);
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

  /** Reads either study's cache: the partitions and the start are found by column name. */
  public static List<Entry> read(final Path path) throws IOException {
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
    int fixed = BASE.size() + (started == null ? 0 : 1);
    for (final Population p : Population.values()) {
      final Integer k = at.get(prefix(p) + "_externa");
      if (k != null) {
        partAt.put(p, k);
        fixed = Math.max(fixed, k + 2);
      }
    }
    final List<Entry> entries = new ArrayList<>(lines.size());
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
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
