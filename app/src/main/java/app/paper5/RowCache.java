package app.paper5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The extracted population, on disk, so that fitting does not re-read the corpus.
 *
 * <p>Loading the tables costs minutes -- six million file actions, half a million commits -- and the
 * modelling stages read the same 8.919 rows over and over. Writing them once turns each iteration
 * from minutes into nothing.
 *
 * <p><b>A file, not a database.</b> The specification asks for the attributes to be persisted in
 * this study's own database, and that stage exists; this is not it. This is a working cache under
 * the paper's own folder, and it writes nowhere near the corpus or either frozen study.
 *
 * <p>Absence is written as an empty field and read back as absence. A zero is written as a zero.
 * The file format exists to keep those two apart, which is the same thing {@link Row} exists for.
 */
public final class RowCache {

  private static final String SEPARATOR = "\t";

  /**
   * One cached unit.
   *
   * @param outer which side of the outer cut it fell on. {@code TEST} here is the reserved set, and
   *     the fitting stage refuses to read those rows at all
   * @param inner the verdict of the second cut, over the outer training side only. Empty for a row
   *     that never reached that side
   */
  public record Entry(Unit unit, Row row, String outer, String inner) {}

  private RowCache() {}

  public static void write(final Path path, final List<Entry> entries, final List<String> columns)
      throws IOException {

    final StringBuilder out = new StringBuilder(1 << 20);
    out.append(String.join(SEPARATOR,
        "id", "projeto", "criador", "aberto_em", "mesclado_em", "particao", "particao_interna"));
    columns.forEach(column -> out.append(SEPARATOR).append(column));
    out.append('\n');

    for (final Entry entry : entries) {
      out.append(entry.unit().pullRequestId()).append(SEPARATOR)
          .append(entry.unit().project()).append(SEPARATOR)
          .append(entry.unit().creatorId()).append(SEPARATOR)
          .append(entry.unit().openedAt()).append(SEPARATOR)
          .append(entry.unit().mergedAt()).append(SEPARATOR)
          .append(entry.outer()).append(SEPARATOR)
          .append(entry.inner());
      for (final String column : columns) {
        out.append(SEPARATOR);
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
    final String[] header = lines.get(0).split(SEPARATOR, -1);
    final List<Entry> entries = new ArrayList<>(lines.size());
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final String[] fields = line.split(SEPARATOR, -1);
      final Map<String, Double> values = new HashMap<>(header.length * 2);
      final Set<String> absent = new HashSet<>(4);
      for (int i = 7; i < header.length && i < fields.length; i++) {
        if (fields[i].isEmpty()) {
          absent.add(header[i]);
        } else {
          values.put(header[i], Double.parseDouble(fields[i]));
        }
      }
      // An indicator column is never absent, so it never lands in the absent set; a construct that
      // has one is absent exactly when its own field is empty, which is what was written.
      absent.removeIf(name -> name.endsWith("_ausente"));
      entries.add(new Entry(
          new Unit(fields[0], fields[1], fields[2],
              Instant.parse(fields[3]), Instant.parse(fields[4])),
          new Row(fields[0], values, absent),
          fields[5], fields[6]));
    }
    return entries;
  }
}
