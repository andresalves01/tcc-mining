package app.issues;

/**
 * What a model is allowed to know, for one cell of the grid.
 *
 * <p>C1 is the creation instant. C2 adds what surfaced between creation and the first resolution,
 * and is read by block (decision 14): the <b>content</b> of the work -- who did it and what was
 * changed, which does not grow just because the issue lasted -- and the <b>exposure</b>, counted over
 * a window whose length is the duration itself. A gain from content justifies splitting the problem
 * into sub-problems (predict the change's size from C1); a gain from exposure alone does not.
 */
public enum Scenario {

  C1,

  /** C1 plus the content block of C2. */
  C1_CONTENT,

  /** C1 plus the exposure block of C2. */
  C1_EXPOSURE,

  /** C1 plus all of C2. */
  C2;

  /** Where a column added in C2 belongs. */
  public enum Block { CONTENT, EXPOSURE }

  /** Whether a C2 column of this block enters this scenario. */
  public boolean admits(final Block block) {
    return switch (this) {
      case C1 -> false;
      case C1_CONTENT -> block == Block.CONTENT;
      case C1_EXPOSURE -> block == Block.EXPOSURE;
      case C2 -> true;
    };
  }
}
