package app.issues;

import app.issues.Universe.Population;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What {@code papers/issues/implementacao/REGISTRO-PREVIO.md} froze before any code could score the
 * external test, as the reserved run checks it. Every number here is copied from that file, which
 * was committed first; a difference refuses the run before anything is scored.
 */
public final class Registration {

  /** Section 2: the matrix the extraction wrote after its gates passed. */
  public static final String MATRIX_SHA256 =
      "15e1f317900007f50ab0c3262c30bf17513202b59cb393906b53f8489677b43f";

  /** Section 5: the alphas and lambdas the inner selection chose, at full precision. */
  public static final String PARAMETERS_SHA256 =
      "e465c79e2ff3cf7a12d9a6b700b4bb1801f62190fb893cf88fddc2f5f082a88d";

  /**
   * Sections 2 and 5, per population: outer training, outer test, inner fitting and inner
   * selection rows, and the test rows never scored before (section 3).
   */
  public record Sizes(int train, int test, int innerFit, int innerSelection, int neverScored) {}

  public static final Map<Population, Sizes> SIZES = Map.of(
      Population.FIXED, new Sizes(83_742, 37_236, 56_302, 25_124, 21_614),
      Population.BROAD, new Sizes(105_891, 47_831, 68_832, 31_768, 31_645),
      Population.INHERITED, new Sizes(33_816, 15_028, 22_676, 10_146, 10));

  /** Section 5: eleven cells in each of three populations. */
  public static final int PARAMETER_LINES = 33;

  private Registration() {}

  /** Every difference between what was registered and what was found; empty when none. */
  public static List<String> differences(final String matrixSha, final String parametersSha,
      final int parameterLines, final Map<Population, Sizes> found) {
    final List<String> out = new ArrayList<>();
    if (!MATRIX_SHA256.equals(matrixSha)) {
      out.add("matriz: SHA-256 " + matrixSha + ", registrado " + MATRIX_SHA256);
    }
    if (!PARAMETERS_SHA256.equals(parametersSha)) {
      out.add("parametros: SHA-256 " + parametersSha + ", registrado " + PARAMETERS_SHA256);
    }
    if (parameterLines != PARAMETER_LINES) {
      out.add("parametros: " + parameterLines + " linhas, registradas " + PARAMETER_LINES);
    }
    for (final Population p : Population.values()) {
      final Sizes f = found.get(p);
      if (f == null) {
        out.add(p + ": nao conferida");
      } else if (!SIZES.get(p).equals(f)) {
        out.add(p + ": " + f + ", registrado " + SIZES.get(p));
      }
    }
    return out;
  }
}
