package app.pipeline.embedding;

/**
 * The exponential and the error function in 32-bit floats, as the network needs them: accurate to
 * a few units in the last place of a float over the range each clamps its argument to,
 * branch-free, and built from multiplications and
 * additions the JIT can run in vector registers, where {@link Math#exp} costs about 70 cycles a
 * call and made the softmax the slowest step of the network. Both are pure and deterministic:
 * every operation is an IEEE float operation or a fused multiply-add, so a given argument gives
 * the same result whether or not a loop is vectorised.
 */
final class FloatMath {

  /** {@code 1 / sqrt 2}, the GELU's scale. */
  static final float INV_SQRT2 = 0.70710678f;

  private static final float LOG2E = 1.44269504f;

  /** ln 2 split in two (Cody and Waite), the first part exact in few bits so k ln 2 is exact. */
  private static final float LN2_HI = 0.693145752f;
  private static final float LN2_LO = 1.42860677e-6f;

  private FloatMath() {}

  /**
   * {@code e^x}, the argument clamped to [-87, 88] (below, the result would leave the normal
   * floats; above, overflow): {@code 2^k e^r} with {@code k} the nearest integer to
   * {@code x / ln 2}, {@code |r| <= ln 2 / 2}, and {@code e^r} by its Taylor polynomial of degree
   * 7, whose truncation error, below 1e-8 relative, is under the float's rounding.
   */
  static float exp(final float x) {
    final float c = Math.min(Math.max(x, -87f), 88f);
    final int k = Math.round(c * LOG2E);
    final float r = Math.fma(-k, LN2_LO, Math.fma(-k, LN2_HI, c));
    float p = 1f / 5040;
    p = Math.fma(p, r, 1f / 720);
    p = Math.fma(p, r, 1f / 120);
    p = Math.fma(p, r, 1f / 24);
    p = Math.fma(p, r, 1f / 6);
    p = Math.fma(p, r, 0.5f);
    p = Math.fma(p, r, 1f);
    p = Math.fma(p, r, 1f);
    return p * Float.intBitsToFloat((k + 127) << 23);
  }

  /**
   * {@code erf(x)} by the rational approximation Eigen and XLA use for 32-bit floats: the argument
   * clamped to [-4, 4] (erf is within a float's rounding of plus or minus one beyond), then an odd
   * polynomial of degree 13 over an even one of degree 8. Within 4e-7 of the exact erf.
   */
  static float erf(final float x) {
    final float c = Math.min(Math.max(x, -4f), 4f);
    final float c2 = c * c;
    float p = -2.72614225801306e-10f;
    p = Math.fma(p, c2, 2.77068142495902e-08f);
    p = Math.fma(p, c2, -2.10102402082508e-06f);
    p = Math.fma(p, c2, -5.69250639462346e-05f);
    p = Math.fma(p, c2, -7.34990630326855e-04f);
    p = Math.fma(p, c2, -2.95459980854025e-03f);
    p = Math.fma(p, c2, -1.60960333262415e-02f);
    float q = -1.45660718464996e-05f;
    q = Math.fma(q, c2, -2.13374055278905e-04f);
    q = Math.fma(q, c2, -1.68282697438203e-03f);
    q = Math.fma(q, c2, -7.37332916720468e-03f);
    q = Math.fma(q, c2, -1.42647390514189e-02f);
    return c * p / q;
  }
}
