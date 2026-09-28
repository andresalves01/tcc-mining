package app.paper6;

import app.paper5.Row;
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
 * The extracted population on disk, so fitting does not re-read the corpus.
 *
 * <p>The pull-request study's cache, with an issue's fields. Absence is an empty field and comes
 * back as absence; a zero is a zero. A working file under the paper's own folder, never a database
 * anyone else depends on.
 */
public final class RowCache {

  private static final String SEP = "\t";
  private static final int FIXED = 13;

  /**
   * @param outer {@code TRAIN} or {@code TEST}; {@code TEST} is the reserved set
   * @param inner the second cut's verdict on the outer training side, empty elsewhere
   */
  public record Entry(IssueUnit unit, Row row, String outer, String inner) {}

  private RowCache() {}

  public static void write(final Path path, final List<Entry> entries, final List<String> columns)
      throws IOException {

    final StringBuilder out = new StringBuilder(1 << 22);
    out.append(String.join(SEP, "id", "chave", "projeto", "relator", "autor", "criada_em",
        "resolvida_em", "primeiro_commit_em", "commits", "linhas_add", "linhas_del", "particao",
        "particao_interna"));
    columns.forEach(column -> out.append(SEP).append(column));
    out.append('\n');
    for (final Entry entry : entries) {
      final IssueUnit u = entry.unit();
      out.append(String.join(SEP, u.issueId(), u.key(), u.project(), u.reporterId(), u.authorId(),
          u.createdAt().toString(), u.resolvedAt().toString(), u.firstCommitAt().toString(),
          String.valueOf(u.commits()), String.valueOf(u.javaLinesAdded()),
          String.valueOf(u.javaLinesDeleted()), entry.outer(), entry.inner()));
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
    final List<Entry> entries = new ArrayList<>(lines.size());
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final String[] f = line.split(SEP, -1);
      final Map<String, Double> values = new HashMap<>(header.length * 2);
      final Set<String> absent = new HashSet<>();
      for (int i = FIXED; i < header.length && i < f.length; i++) {
        if (f[i].isEmpty()) {
          absent.add(header[i]);
        } else {
          values.put(header[i], Double.parseDouble(f[i]));
        }
      }
      absent.removeIf(name -> name.endsWith("_ausente"));
      entries.add(new Entry(
          new IssueUnit(f[0], f[1], f[2], f[3], f[4], Instant.parse(f[5]), Instant.parse(f[6]),
              Instant.parse(f[7]), Integer.parseInt(f[8]), Long.parseLong(f[9]),
              Long.parseLong(f[10])),
          new Row(f[0], values, absent), f[11], f[12]));
    }
    return entries;
  }
}
