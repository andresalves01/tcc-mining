package app.issues;

import app.study.Row;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The revised team columns ({@link Extractor#TEAM_REVISED}) on their own file, one line per issue
 * keyed by id, for the study.
 *
 * <p>Apart from the extracted matrix so the extraction and the team calculation remain separate.
 * The similarity stage joins this file into the matrix it writes, so downstream stages still read
 * one matrix. Absence is an empty field and comes back as absence.
 */
public final class TeamColumns {

  private static final String SEP = "\t";
  private static final String HEADER = "id" + SEP + String.join(SEP, Extractor.TEAM_REVISED);

  private TeamColumns() {}

  /**
   * Per issue id, the four columns; a missing value is a null or an absent key. Refuses to replace
   * a file that is not a team file, so a misdirected path cannot destroy the extracted matrix.
   */
  public static void write(final Path path, final Map<String, Map<String, Double>> values)
      throws IOException {
    if (Files.exists(path)) {
      final String first;
      try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
        first = lines.findFirst().orElse("");
      } catch (final java.io.UncheckedIOException e) {
        throw new IOException(path + " exists and is not a team file; it was left as it is", e);
      }
      if (!first.isEmpty() && !first.equals(HEADER)) {
        throw new IOException(path + " exists and is not a team file; it was left as it is");
      }
    }
    final List<String> lines = new ArrayList<>(values.size() + 1);
    lines.add(HEADER);
    values.forEach((id, row) -> {
      final StringBuilder line = new StringBuilder(id);
      for (final String column : Extractor.TEAM_REVISED) {
        line.append(SEP);
        final Double v = row.get(column);
        if (v != null) {
          line.append(v);
        }
      }
      lines.add(line.toString());
    });
    Files.createDirectories(path.toAbsolutePath().getParent());
    Files.write(path, lines, StandardCharsets.UTF_8);
  }

  /** Per issue id, the observed columns only. */
  public static Map<String, Map<String, Double>> read(final Path path) throws IOException {
    final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
      throw new IOException(path + ": the header should be " + HEADER.replace(SEP, " "));
    }
    final Map<String, Map<String, Double>> out = new LinkedHashMap<>(lines.size() * 2);
    for (int k = 1; k < lines.size(); k++) {
      final String line = lines.get(k);
      if (line.isBlank()) {
        continue;
      }
      final String[] f = line.split(SEP, -1);
      if (f.length != Extractor.TEAM_REVISED.size() + 1) {
        throw new IOException(path + ": line " + (k + 1) + " has " + f.length + " fields, not "
            + (Extractor.TEAM_REVISED.size() + 1));
      }
      final Map<String, Double> row = new HashMap<>();
      for (int i = 0; i < Extractor.TEAM_REVISED.size(); i++) {
        if (!f[i + 1].isEmpty()) {
          try {
            row.put(Extractor.TEAM_REVISED.get(i), Double.parseDouble(f[i + 1]));
          } catch (final NumberFormatException e) {
            throw new IOException(path + ": line " + (k + 1) + " holds " + f[i + 1]
                + ", not a number", e);
          }
        }
      }
      if (out.put(f[0], row) != null) {
        throw new IOException(path + ": issue " + f[0] + " appears twice");
      }
    }
    return out;
  }

  /**
   * The matrix's rows with the four columns added. Refuses unless the file holds exactly the
   * matrix's issues, so a file from another extraction cannot be joined in part.
   */
  public static List<RowCache.Entry> join(final List<RowCache.Entry> entries,
      final Map<String, Map<String, Double>> values) {
    final List<String> notInFile = new ArrayList<>();
    final Set<String> ids = new HashSet<>();
    for (final RowCache.Entry e : entries) {
      ids.add(e.unit().issueId());
      if (!values.containsKey(e.unit().issueId())) {
        notInFile.add(e.unit().issueId());
      }
    }
    final List<String> notInMatrix = values.keySet().stream().filter(id -> !ids.contains(id))
        .toList();
    if (!notInFile.isEmpty() || !notInMatrix.isEmpty()) {
      throw new IllegalArgumentException(String.format("the team file and the matrix differ: %d "
          + "issues of the matrix are not in the file%s, %d issues of the file are not in the "
          + "matrix%s", notInFile.size(), first(notInFile), notInMatrix.size(),
          first(notInMatrix)));
    }
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> v = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      final Map<String, Double> team = values.get(e.unit().issueId());
      for (final String column : Extractor.TEAM_REVISED) {
        final Double x = team.get(column);
        if (x == null) {
          absent.add(column);
        } else {
          v.put(column, x);
        }
      }
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), v, absent), e.parts()));
    }
    return out;
  }

  private static String first(final List<String> ids) {
    return ids.isEmpty() ? "" : " (the first " + ids.get(0) + ")";
  }
}
