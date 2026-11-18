package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation.Variant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The verdict of an issue at each level of the split, and what it says: whether the issue
 * teaches, is on the training side, or is scored. The five cases of an outer training issue (the
 * plan of the revised design), and the outer test.
 */
class VerdictTest {

  /** One case: its outer and inner verdict, and for INNER then OUTER what it is. */
  private record Case(String name, String outer, String inner, boolean[] teaches,
      boolean[] side, boolean[] scored) {}

  private static boolean[] at(final boolean inner, final boolean outer) {
    return new boolean[] {inner, outer};
  }

  private static final List<Case> CASES = List.of(
      // 1: inner fitting, first resolution by the inner cut: teaches at both levels
      new Case("1", "TRAIN", "TRAIN", at(true, true), at(true, true), at(false, false)),
      // 2: inner fitting, crossing the inner cut but not the outer: teaches the outer level only
      new Case("2", "TRAIN", "CROSSING", at(false, true), at(true, true), at(false, false)),
      // 3: inner fitting, crossing the outer cut: on both training sides, teaches nothing
      new Case("3", "CROSSING", "CROSSING", at(false, false), at(true, true), at(false, false)),
      // 4: inner validation, first resolution by the outer cut: teaches the outer level, scored
      // in the inner
      new Case("4", "TRAIN", "TEST", at(false, true), at(false, true), at(true, false)),
      // 5: inner validation, crossing the outer cut: teaches nothing, still scored in the inner
      new Case("5", "CROSSING", "TEST", at(false, false), at(false, true), at(true, false)),
      // the outer test: scored in the outer level alone
      new Case("test", "TEST", "", at(false, false), at(false, false), at(false, true)));

  @Test
  @DisplayName("the five cases of an outer training issue and the outer test: what each teaches, "
      + "which training side it is on and where it is scored, per level")
  void cases() {
    for (final Case c : CASES) {
      for (final Variant v : Variant.values()) {
        final int at = v == Variant.INNER ? 0 : 1;
        assertEquals(c.teaches()[at], Verdict.teaches(v, c.outer(), c.inner()),
            "case " + c.name() + " teaches " + v);
        assertEquals(c.side()[at], Verdict.trainingSide(v, c.outer(), c.inner()),
            "case " + c.name() + " on the training side of " + v);
        assertEquals(c.scored()[at], Verdict.scored(v, c.outer(), c.inner()),
            "case " + c.name() + " scored in " + v);
      }
      assertEquals(!c.outer().equals("TEST"), Verdict.outerTraining(c.outer()), c.name());
    }
  }

  @Test
  @DisplayName("an issue that teaches is on the training side; none is both on it and scored; an "
      + "issue that crosses the outer cut never teaches")
  void consistent() {
    for (final Case c : CASES) {
      for (final Variant v : Variant.values()) {
        if (Verdict.teaches(v, c.outer(), c.inner())) {
          assertTrue(Verdict.trainingSide(v, c.outer(), c.inner()), c.name());
        }
        assertFalse(Verdict.trainingSide(v, c.outer(), c.inner())
            && Verdict.scored(v, c.outer(), c.inner()), c.name());
        if (c.outer().equals("CROSSING")) {
          assertFalse(Verdict.teaches(v, c.outer(), c.inner()), c.name());
        }
      }
    }
  }

  @Test
  @DisplayName("under the random and the temporal rule, with no crossing verdict, teaching and "
      + "being on the training side are the same, as before the revised design")
  void oldRules() {
    for (final String[] p : List.of(new String[] {"TRAIN", "TRAIN"},
        new String[] {"TRAIN", "TEST"}, new String[] {"TEST", ""})) {
      for (final Variant v : Variant.values()) {
        assertEquals(Verdict.trainingSide(v, p[0], p[1]), Verdict.teaches(v, p[0], p[1]));
      }
    }
  }

  @Test
  @DisplayName("a verdict's text is its name, the empty field no verdict; any other text refused")
  void text() {
    assertEquals(Verdict.CROSSING, Verdict.of("CROSSING"));
    assertEquals(Verdict.TRAIN, Verdict.of("TRAIN"));
    assertNull(Verdict.of(""));
    assertEquals("", Verdict.text(null));
    assertEquals("TEST", Verdict.text(Verdict.TEST));
    assertThrows(IllegalArgumentException.class, () -> Verdict.of("train"));
    assertTrue(Verdict.TRAIN.is("TRAIN"));
    assertFalse(Verdict.TRAIN.is(null));
    assertEquals(java.util.Set.of("TRAIN", "CROSSING"), Verdict.OUTER_TRAINING);
  }
}
