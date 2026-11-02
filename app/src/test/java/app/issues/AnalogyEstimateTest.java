package app.issues;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.study.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The analogy estimate (ticket 06, exploratory): its choice, its fallback and its metrics. */
class AnalogyEstimateTest {

  /** A row holding, per candidate given, its inner column; any other candidate is missing. */
  private static Row row(final String id, final Map<String, Double> inner) {
    final Map<String, Double> values = new HashMap<>();
    inner.forEach((candidate, v) -> values.put(candidate + "_inner", v));
    return new Row(id, values, Set.of());
  }

  @Test
  @DisplayName("an issue without neighbours takes the fallback, and is counted")
  void fallback() {
    final List<Row> rows = List.of(
        row("a", Map.of("jaccard_median_5", 3.0)),
        row("b", Map.of()),
        row("c", Map.of("jaccard_median_5", 7.0)));
    final AnalogyEstimate.Estimate e =
        AnalogyEstimate.estimate(rows, "jaccard_median_5_inner", 2.0);
    assertArrayEquals(new double[] {3.0, 2.0, 7.0}, e.values());
    assertEquals(1, e.fallbacks());
    assertArrayEquals(new boolean[] {false, true, false}, e.fellBack());
  }

  @Test
  @DisplayName("the candidate with the lowest MAE, fallback included, is chosen")
  void lowestMae() {
    final double[] observed = {4, 4};
    final List<Row> rows = new ArrayList<>();
    for (final String id : List.of("a", "b")) {
      final Map<String, Double> inner = new HashMap<>();
      for (final String c : AnalogyEstimate.CANDIDATES) {
        inner.put(c, 10.0);
      }
      inner.put("cluster_median_16", 4.0);
      // embedding 10: 5 on a (error 1), no neighbours on b (the fallback 4, error 0)
      if (id.equals("a")) {
        inner.put("embedding_median_10", 5.0);
      } else {
        inner.remove("embedding_median_10");
      }
      rows.add(row(id, inner));
    }
    final AnalogyEstimate.Choice choice =
        AnalogyEstimate.choose(rows, Augmentation.Variant.INNER, 4.0, observed);
    assertEquals("cluster_median_16", choice.chosen());
    assertEquals(AnalogyEstimate.CANDIDATES,
        choice.candidates().stream().map(AnalogyEstimate.Candidate::name).toList());
    final AnalogyEstimate.Candidate embedding = choice.candidates().stream()
        .filter(c -> c.name().equals("embedding_median_10")).findFirst().orElseThrow();
    assertEquals(0.5, embedding.mae(), 1e-12);
    assertEquals(1, embedding.fallbacks());
  }

  @Test
  @DisplayName("a tie goes to the first candidate: Jaccard, embedding, cluster, smaller first")
  void tieGoesToTheFirst() {
    final double[] observed = {4};
    final Map<String, Double> inner = new HashMap<>();
    for (final String c : AnalogyEstimate.CANDIDATES) {
      inner.put(c, 9.0);
    }
    inner.put("embedding_median_20", 5.0);
    inner.put("jaccard_median_20", 3.0);
    inner.put("cluster_median_8", 5.0);
    final AnalogyEstimate.Choice choice =
        AnalogyEstimate.choose(List.of(row("a", inner)), Augmentation.Variant.INNER, 1.0,
            observed);
    assertEquals("jaccard_median_20", choice.chosen());
    assertEquals(List.of("jaccard_median_5", "jaccard_median_10", "jaccard_median_20",
        "embedding_median_5", "embedding_median_10", "embedding_median_20",
        "cluster_median_8", "cluster_median_16", "cluster_median_32"), AnalogyEstimate.CANDIDATES);
  }

  @Test
  @DisplayName("MMRE is the mean of |observed - estimate| / observed")
  void mmre() {
    // relative errors 0.5, 0.25, 1.0
    assertEquals((0.5 + 0.25 + 1.0) / 3,
        AnalogyEstimate.mmre(new double[] {1, 5, 6}, new double[] {2, 4, 3}), 1e-12);
  }

  @Test
  @DisplayName("Pred(25) is the share within 25% of the observed, the edge included")
  void pred25() {
    // relative errors 0.25 (in), 0.2 (in), 0.5 (out), 0.3 (out)
    assertEquals(0.5, AnalogyEstimate.pred25(new double[] {5, 4.8, 3, 1.3},
        new double[] {4, 4, 2, 1}), 1e-12);
  }

  @Test
  @DisplayName("the share of estimates below the observed, equal ones not counted")
  void below() {
    assertEquals(0.5, AnalogyEstimate.below(new double[] {1, 4, 5, 2},
        new double[] {2, 4, 3, 3}), 1e-12);
  }

  @Test
  @DisplayName("an observed duration of zero or less is refused: the relative error is undefined")
  void zeroObservedRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> AnalogyEstimate.mmre(new double[] {1}, new double[] {0}));
    assertThrows(IllegalArgumentException.class,
        () -> AnalogyEstimate.pred25(new double[] {1, 2}, new double[] {1}));
  }
}
