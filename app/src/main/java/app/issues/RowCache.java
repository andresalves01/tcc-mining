package app.issues;

import app.paper5.Row;
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
  private static final List<String> FIXED = List.of("id", "chave", "projeto", "relator",
      "executor", "criada_em", "resolvida_em", "motivo", "movida",
      "fixed_externa", "fixed_interna", "ampla_externa", "ampla_interna",
      "herdada_externa", "herdada_interna");

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

  private static String text(final String s) {
    return s == null ? "" : s;
  }

  public static void write(final Path path, final List<Entry> entries, final List<String> columns)
      throws IOException {
    final StringBuilder out = new StringBuilder(1 << 24);
    out.append(String.join(SEP, FIXED));
    columns.forEach(column -> out.append(SEP).append(column));
    out.append('\n');
    for (final Entry entry : entries) {
      final IssueUnit u = entry.unit();
      out.append(String.join(SEP, u.issueId(), u.key(), u.project(), text(u.reporter()),
          text(u.executor()), u.createdAt().toString(), u.resolvedAt().toString(),
          text(u.reason()), u.moved() ? "1" : "0",
          entry.outer(Population.FIXED), entry.inner(Population.FIXED),
          entry.outer(Population.BROAD), entry.inner(Population.BROAD),
          entry.outer(Population.INHERITED), entry.inner(Population.INHERITED)));
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

  public static List<Entry> read(final Path path) throws IOException {
    final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (lines.isEmpty()) {
      throw new IOException(path + " esta vazio");
    }
    final String[] header = lines.get(0).split(SEP, -1);
    final int fixed = FIXED.size();
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
      put(parts, Population.FIXED, f[9], f[10]);
      put(parts, Population.BROAD, f[11], f[12]);
      put(parts, Population.INHERITED, f[13], f[14]);
      entries.add(new Entry(new IssueUnit(f[0], f[1], f[2], empty(f[3]), empty(f[4]),
          Instant.parse(f[5]), Instant.parse(f[6]), empty(f[7]), "1".equals(f[8])),
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
