package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Embedder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The comparison with the sentence-transformers reference, run with fake embedders. */
class ReferenceCheckTest {

  private final Map<String, ReferenceCheck.Item> byText = new HashMap<>();

  ReferenceCheckTest() {
    ReferenceCheck.items().forEach(i -> byText.put(i.text(), i));
  }

  /** The reference vector of {@code text}, with {@code delta} added to its first component. */
  private Embedder.Embedding exact(final String text, final float delta) {
    final double[] reference = byText.get(text).vector();
    final float[] v = new float[reference.length];
    for (int d = 0; d < v.length; d++) {
      v[d] = (float) reference[d];
    }
    v[0] += delta;
    return new Embedder.Embedding(v, 1);
  }

  private List<long[]> ids(final String text) {
    return List.of(byText.get(text).ids().clone());
  }

  @Test
  @DisplayName("the reference ships with the program: six sentences at the pinned revision and "
      + "limit")
  void shipped() {
    assertEquals(6, ReferenceCheck.items().size());
    assertEquals(384, ReferenceCheck.items().get(0).vector().length);
  }

  @Test
  @DisplayName("an embedder that gives the reference ids and vectors passes, its largest "
      + "difference reported")
  void passes() {
    final ReferenceCheck.Result r = ReferenceCheck.compare(t -> exact(t, 5e-5f), this::ids);
    assertTrue(r.passed(), r.problems().toString());
    assertEquals(5e-5, r.maxDifference(), 1e-6);
    assertEquals(ReferenceCheck.TOLERANCE, 1e-4);
  }

  @Test
  @DisplayName("a vector further than the tolerance, or of another size, fails")
  void vectorDiffers() {
    final ReferenceCheck.Result far = ReferenceCheck.compare(t -> exact(t, 2e-4f), this::ids);
    assertFalse(far.passed());
    assertEquals(6, far.problems().size(), far.problems().toString());
    final ReferenceCheck.Result shorter = ReferenceCheck.compare(
        t -> new Embedder.Embedding(new float[] {1}, 1), this::ids);
    assertFalse(shorter.passed());
    final ReferenceCheck.Result nan = ReferenceCheck.compare(t -> exact(t, Float.NaN), this::ids);
    assertFalse(nan.passed(), "a NaN is never within the tolerance");
  }

  @Test
  @DisplayName("token ids that differ in one place, or a sentence cut into two chunks, fail")
  void idsDiffer() {
    final ReferenceCheck.Result one = ReferenceCheck.compare(t -> exact(t, 0), t -> {
      final long[] ids = byText.get(t).ids().clone();
      ids[1]++;
      return List.of(ids);
    });
    assertFalse(one.passed());
    assertTrue(one.problems().get(0).contains("token ids"), one.problems().toString());
    final ReferenceCheck.Result two = ReferenceCheck.compare(t -> exact(t, 0),
        t -> List.of(byText.get(t).ids(), byText.get(t).ids()));
    assertFalse(two.passed());
  }
}
