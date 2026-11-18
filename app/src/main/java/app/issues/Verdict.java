package app.issues;

import app.issues.Augmentation.Variant;
import app.issues.Universe.Population;
import java.util.Set;

/**
 * An issue's verdict at one level of the split, as a matrix's split columns hold it: the outer
 * column the outer level's, the inner column the inner level's, empty on the outer test.
 *
 * <p>Every split before the revised design gives {@link #TRAIN} and {@link #TEST} alone. The
 * revised temporal design adds {@link #CROSSING}: an issue on a level's training side whose first
 * resolution comes after that level's cut, so its label was not yet known there. It stays on the
 * training side and is no issue of that level's validation or test, but it teaches nothing at
 * that level. An outer training issue thus carries two things that do not depend on each other,
 * its inner role and whether it crosses, which give five cases:
 *
 * <pre>
 *   case  inner role        first resolution             outer     inner
 *   1     inner fitting     by the inner cut             TRAIN     TRAIN
 *   2     inner fitting     between the two cuts         TRAIN     CROSSING
 *   3     inner fitting     after the outer cut          CROSSING  CROSSING
 *   4     inner validation  by the outer cut             TRAIN     TEST
 *   5     inner validation  after the outer cut          CROSSING  TEST
 * </pre>
 *
 * <p>A reader of the first-commit study's split columns asks what an issue is through the
 * predicates here, never by comparing the text: a reader that compared it and was forgotten would
 * drop issues from the validation or the counts, a visible failure, and never let a crossing label
 * teach. The In Progress study's readers still compare {@code "TRAIN"} and {@code "TEST"}; that
 * study takes the random rule alone, whose columns never hold {@link #CROSSING}.
 */
public enum Verdict {
  /** On the training side, and it teaches at this level. */
  TRAIN,
  /** Scored at this level: the inner validation, or the outer test. */
  TEST,
  /** On the training side, its label not yet known at this level's cut: it teaches nothing here. */
  CROSSING;

  /** The outer verdicts of the outer training side, crossing issues included. */
  public static final Set<String> OUTER_TRAINING = Set.of(TRAIN.name(), CROSSING.name());

  /** Whether {@code text} is this verdict. */
  public boolean is(final String text) {
    return name().equals(text);
  }

  /** The verdict a split column holds; null for the empty field. Refuses any other text. */
  public static Verdict of(final String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    for (final Verdict v : values()) {
      if (v.is(text)) {
        return v;
      }
    }
    throw new IllegalArgumentException("'" + text + "' is no verdict of a split");
  }

  /** A verdict as a split column writes it; the empty field for none. */
  public static String text(final Verdict v) {
    return v == null ? "" : v.name();
  }

  /** Whether an outer verdict is on the outer training side, crossing issues included. */
  public static boolean outerTraining(final String outer) {
    return OUTER_TRAINING.contains(outer);
  }

  /**
   * Whether an issue teaches at {@code v}'s level: it is a fitting row there, and a history and
   * a reference may read its label. The inner level: case 1; the outer: cases 1, 2 and 4.
   */
  public static boolean teaches(final Variant v, final String outer, final String inner) {
    return v == Variant.INNER ? TRAIN.is(outer) && TRAIN.is(inner) : TRAIN.is(outer);
  }

  /**
   * Whether an issue is on the training side of {@code v}'s level, whether it teaches there or
   * crosses its cut: the inner fitting side (cases 1 to 3) or the outer training (cases 1 to 5).
   */
  public static boolean trainingSide(final Variant v, final String outer, final String inner) {
    return v == Variant.INNER
        ? outerTraining(outer) && (TRAIN.is(inner) || CROSSING.is(inner))
        : outerTraining(outer);
  }

  /**
   * Whether an issue is scored at {@code v}'s level: the inner validation (cases 4 and 5, every
   * one kept), or the outer test.
   */
  public static boolean scored(final Variant v, final String outer, final String inner) {
    return v == Variant.INNER ? outerTraining(outer) && TEST.is(inner) : TEST.is(outer);
  }

  /** {@link #teaches} of a matrix row under {@code population}'s split columns. */
  public static boolean teaches(final Variant v, final RowCache.Entry e,
      final Population population) {
    return teaches(v, e.outer(population), e.inner(population));
  }

  /** {@link #trainingSide} of a matrix row under {@code population}'s split columns. */
  public static boolean trainingSide(final Variant v, final RowCache.Entry e,
      final Population population) {
    return trainingSide(v, e.outer(population), e.inner(population));
  }

  /** {@link #scored} of a matrix row under {@code population}'s split columns. */
  public static boolean scored(final Variant v, final RowCache.Entry e,
      final Population population) {
    return scored(v, e.outer(population), e.inner(population));
  }
}
