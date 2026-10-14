package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.issues.Universe.Population;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The choice of each similarity method's candidate value by its direct predictor's error. */
class SelectionTest {

  private static final Instant T = Instant.parse("2016-01-01T00:00:00Z");

  private final List<RowCache.Entry> entries = new ArrayList<>();

  /** An issue of {@code hours} in the given split, with the given columns observed. */
  private void row(final String id, final double hours, final String outer, final String inner,
      final Map<String, Double> values) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    final Instant start = T.plusSeconds(3600L * entries.size());
    entries.add(new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "d", T,
        start.plusMillis((long) (hours * 3_600_000)), "Fixed", false, start),
        new Row(id, new HashMap<>(values), new HashSet<>()), parts));
  }

  private static final List<String> JACCARD = List.of("A01_desc_length",
      "jaccard_median_5_inner", "jaccard_median_5_outer", "jaccard_median_10_inner",
      "jaccard_median_10_outer", "jaccard_median_20_inner", "jaccard_median_20_outer");

  private void fitting() {
    row("a", 10, "TRAIN", "TRAIN", Map.of());
    row("b", 20, "TRAIN", "TRAIN", Map.of());
    row("c", 30, "TRAIN", "TRAIN", Map.of());
  }

  @Test
  @DisplayName("the candidate whose direct predictor errs least on the inner validation is chosen")
  void lowestErrorWins() {
    fitting();
    row("v", 100, "TRAIN", "TEST", Map.of("jaccard_median_5_inner", 50.0,
        "jaccard_median_10_inner", 90.0, "jaccard_median_20_inner", 70.0));
    row("w", 200, "TRAIN", "TEST", Map.of("jaccard_median_5_inner", 150.0,
        "jaccard_median_10_inner", 190.0, "jaccard_median_20_inner", 170.0));

    final Selection.Result r = Selection.run(entries, JACCARD);
    final Selection.Method m = r.methods().get(0);
    assertEquals("jaccard_median", m.name());
    assertEquals(List.of(5, 10, 20), m.candidates().stream().map(Selection.Candidate::value)
        .toList());
    assertEquals(List.of(50.0, 10.0, 30.0), m.candidates().stream()
        .map(Selection.Candidate::maeHours).toList());
    assertEquals(10, m.chosen());
  }

  @Test
  @DisplayName("errors within the tie precision go to the smaller value; beyond it the lower wins")
  void tiesToTheSmaller() {
    fitting();
    // errors 10.008, 10.000 and 9.985 h: 20 is lower than both by more than the precision; then
    // 10.008, 10.000 and 10.002 h: all within it of the lowest, so the smallest value
    row("v", 100, "TRAIN", "TEST", Map.of("jaccard_median_5_inner", 110.008,
        "jaccard_median_10_inner", 110.0, "jaccard_median_20_inner", 109.985));
    assertEquals(20, Selection.run(entries, JACCARD).methods().get(0).chosen());

    entries.clear();
    fitting();
    row("v", 100, "TRAIN", "TEST", Map.of("jaccard_median_5_inner", 110.008,
        "jaccard_median_10_inner", 110.0, "jaccard_median_20_inner", 110.002));
    assertEquals(5, Selection.run(entries, JACCARD).methods().get(0).chosen());
  }

  @Test
  @DisplayName("a missing value falls back to the median duration of the inner fitting set; "
      + "coverage and the error on the observed rows are reported apart")
  void fallsBackToTheFittingMedian() {
    fitting();
    row("v", 100, "TRAIN", "TEST", Map.of("jaccard_median_10_inner", 100.0,
        "jaccard_median_20_inner", 100.0));
    row("w", 200, "TRAIN", "TEST", Map.of("jaccard_median_5_inner", 190.0,
        "jaccard_median_10_inner", 200.0, "jaccard_median_20_inner", 200.0));

    final Selection.Result r = Selection.run(entries, JACCARD);
    assertEquals(20.0, r.fallbackHours());
    final Selection.Candidate five = r.methods().get(0).candidates().get(0);
    // v reads the median of 10, 20 and 30 h: |20 - 100| and |190 - 200| over two rows
    assertEquals(45.0, five.maeHours());
    assertEquals(1, five.observed());
    assertEquals(10.0, five.maeObservedHours());
    assertEquals(2, r.methods().get(0).candidates().get(1).observed());
    assertEquals(10, r.methods().get(0).chosen());
  }

  @Test
  @DisplayName("only the inner fitting and inner validation rows are read: the outer test and the "
      + "rows crossing the cut change neither the fallback nor any error or choice")
  void readsOnlyTheInnerRows() {
    fitting();
    row("v", 100, "TRAIN", "TEST", Map.of("jaccard_median_5_inner", 50.0,
        "jaccard_median_10_inner", 90.0, "jaccard_median_20_inner", 70.0));
    final Selection.Result alone = Selection.run(entries, JACCARD);

    // were these read, the fallback would move and 20 would win on them
    for (final String outer : List.of("TEST", "CROSSES")) {
      for (int k = 0; k < 5; k++) {
        row(outer + k, 5000, outer, "", Map.of("jaccard_median_5_inner", 1.0,
            "jaccard_median_10_inner", 1.0, "jaccard_median_20_inner", 5000.0,
            "jaccard_median_20_outer", 5000.0));
      }
    }
    final Selection.Result r = Selection.run(entries, JACCARD);
    assertEquals(3, r.fitRows());
    assertEquals(1, r.validationRows());
    assertEquals(alone, r);
    assertEquals(10, r.methods().get(0).chosen());
  }

  @Test
  @DisplayName("the methods are found by their column names: a candidate value and the inner "
      + "suffix; a column with no candidate value, or of the outer variant, is no method")
  void findsTheMethodsByName() {
    fitting();
    row("v", 100, "TRAIN", "TEST", Map.of("cluster_median_16_inner", 100.0));
    final List<String> columns = new ArrayList<>(JACCARD);
    columns.addAll(List.of("reporter_median_inner", "reporter_median_outer",
        "embedding_median_10_inner", "embedding_median_5_inner", "embedding_median_5_outer",
        "cluster_median_8_inner", "cluster_median_16_inner", "cluster_median_32_outer"));

    final List<Selection.Method> methods = Selection.run(entries, columns).methods();
    assertEquals(List.of("cluster_median", "embedding_median", "jaccard_median"),
        methods.stream().map(Selection.Method::name).toList());
    assertEquals(List.of(8, 16), methods.get(0).candidates().stream()
        .map(Selection.Candidate::value).toList());
    assertEquals(List.of(5, 10), methods.get(1).candidates().stream()
        .map(Selection.Candidate::value).toList());
    assertEquals(16, methods.get(0).chosen());
    assertEquals(5, methods.get(1).chosen());
  }

  @Test
  @DisplayName("the parameters file holds one line per method's choice, read back as written; a "
      + "line of another step is kept for it, and a malformed or repeated line refuses")
  void parametersFile() {
    fitting();
    row("v", 100, "TRAIN", "TEST", Map.of("jaccard_median_5_inner", 50.0,
        "jaccard_median_10_inner", 90.0, "jaccard_median_20_inner", 70.0,
        "embedding_median_20_inner", 100.0));
    final List<String> columns = new ArrayList<>(JACCARD);
    columns.add("embedding_median_20_inner");
    final List<String> lines = Selection.parameters(Selection.run(entries, columns));
    assertEquals(List.of("direct\tembedding_median\tcandidate\t20",
        "direct\tjaccard_median\tcandidate\t10"),
        lines.stream().filter(l -> !l.startsWith("#")).toList());

    final List<String> extended = new ArrayList<>(lines);
    extended.add("elastic-net\trevised twelve\talpha\t0.5");
    final List<Selection.Parameter> back = Selection.read(extended);
    assertEquals(Map.of("embedding_median", 20, "jaccard_median", 10), Selection.chosen(back));
    assertEquals(new Selection.Parameter("elastic-net", "revised twelve", "alpha", "0.5"),
        back.get(2));

    assertThrows(IllegalArgumentException.class, () -> Selection.read(List.of("direct\tx")));
    assertThrows(IllegalArgumentException.class, () -> Selection.read(List.of(
        "direct\tx\tcandidate\t5", "direct\tx\tcandidate\t10")));
  }

  @Test
  @DisplayName("the four split sizes are counted from the split labels alone")
  void sizes() {
    fitting();
    row("v", 100, "TRAIN", "TEST", Map.of());
    row("t", 1, "TEST", "", Map.of());
    row("u", 1, "TEST", "", Map.of());
    row("x", 1, "CROSSES", "", Map.of());
    assertEquals(new RegistrationInProgress.Sizes(4, 2, 3, 1), Selection.sizes(entries));
  }

  @Test
  @DisplayName("a candidate value that is not a finite number refuses rather than choosing")
  void refusesANonFiniteValue() {
    fitting();
    row("v", 100, "TRAIN", "TEST", Map.of("jaccard_median_5_inner", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Selection.run(entries, JACCARD));
  }
}
