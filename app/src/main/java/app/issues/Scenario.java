package app.issues;

/**
 * What a model is allowed to know.
 *
 * <p>C1 is the start: what was known when the work started. C2 adds what surfaced between the start
 * and the first resolution, in two blocks: the <b>content</b> of the work -- who did it and what was
 * changed, which does not grow just because the issue lasted -- and the <b>exposure</b>, counted
 * over a window whose length is the duration itself.
 */
public enum Scenario {

  C1,

  /** C1 plus everything observed up to the first resolution. */
  C2;

  /** Where a column added in C2 belongs. */
  public enum Block { CONTENT, EXPOSURE }

  /** Whether a C2 column of this block enters this scenario. */
  public boolean admits(final Block block) {
    return this == C2;
  }
}
