package app.cli.commands;

import app.issues.IssueUnit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.Universe.Part;
import app.issues.Universe.Population;
import app.study.Row;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A matrix of ten issues of one project at the revised design's verdicts but one, moved to the
 * other outer side: what a stage that checks the split must refuse before reading anything else.
 */
final class MovedSplitFixture {

  private MovedSplitFixture() {}

  /** Writes the matrix to {@code path}, every row with one column. */
  static Path write(final Path path) throws IOException {
    final Map<String, String> projects = new LinkedHashMap<>();
    for (int k = 0; k < 10; k++) {
      projects.put("i" + k, "p");
    }
    final Instant t = Instant.parse("2016-03-01T10:00:00Z");
    final List<app.issues.SplitRule.Candidate> candidates = new ArrayList<>();
    int k = 0;
    for (final String id : projects.keySet()) {
      final Instant start = t.plusSeconds(3600L * ++k);
      candidates.add(new app.issues.SplitRule.Candidate(id, "p", start,
          start.plusSeconds(86_400 * 3)));
    }
    final ProjectSplit.Parts parts = app.issues.TemporalSplit.drawRevised(candidates);
    k = 0;
    final List<RowCache.Entry> entries = new ArrayList<>();
    for (final String id : projects.keySet()) {
      final Part inner = parts.inner().get(id);
      String[] verdicts = {parts.outer().get(id).name(), inner == null ? "" : inner.name()};
      if (id.equals("i0")) {
        verdicts = verdicts[0].equals("TRAIN") ? new String[] {"TEST", ""}
            : new String[] {"TRAIN", "TRAIN"};
      }
      final Map<Population, String[]> by = new EnumMap<>(Population.class);
      by.put(Population.FIRST_COMMIT, verdicts);
      final Instant start = t.plusSeconds(3600L * ++k);
      entries.add(new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "d", t,
          start.plusSeconds(86_400 * 3), "Fixed", false, start),
          new Row(id, Map.of("A01_desc_length", 12.0), Set.of()), by));
    }
    RowCache.write(path, entries, List.of("A01_desc_length"), List.of(Population.FIRST_COMMIT));
    return path;
  }
}
