package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.study.Row;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The profile's own readings of a row: the issue type, the issue's lines, the medians. */
class LargestErrorsTest {

  private static Double value(final String name, final Map<String, Double> values) {
    final LargestErrors.Column c = LargestErrors.COLUMNS.stream()
        .filter(x -> x.name().equals(name)).findFirst().orElseThrow();
    return c.of().apply(new Row("i", values, Set.of()));
  }

  @Test
  @DisplayName("the type is the indicator set; the commits are the marks less the patches; the "
      + "issue's lines are the first commit's over its share, unknown without a positive share")
  void readsARow() {
    final Map<String, Double> values = new HashMap<>(Map.of("Z_type_Bug", 0.0,
        "Z_type_Improvement", 1.0, "FC_marks", 5.0, "FC_patches", 2.0,
        FirstCommit.FIRST_LINES, 30.0, FirstCommit.FIRST_SHARE, 0.25));
    assertEquals("Improvement", LargestErrors.type(new Row("i", values, Set.of())));
    assertEquals("?", LargestErrors.type(new Row("i", Map.of("Z_type_Bug", 0.0), Set.of())));
    assertEquals(3.0, value("commits", values));
    assertEquals(120.0, value("issue_lines", values));
    values.put(FirstCommit.FIRST_SHARE, 0.0);
    assertEquals(null, value("issue_lines", values));
    assertEquals(null, value("java_lines", values));
  }

  @Test
  @DisplayName("the median of the values present, NaN when none is")
  void median() {
    assertEquals(2.0, LargestErrors.median(Arrays.asList(3.0, null, 1.0, 2.0)));
    assertEquals(2.5, LargestErrors.median(List.of(4.0, 1.0, 2.0, 3.0)));
    assertEquals(Double.NaN, LargestErrors.median(Arrays.asList((Double) null)));
  }
}
