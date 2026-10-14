package app.issues;

import java.util.ArrayList;
import java.util.List;

/**
 * What the in-progress study's pre-registration froze before any code could score its external
 * test, as its reserved run checks it. Every number
 * here is copied from that file, which was committed first; a difference refuses the run before
 * anything is scored.
 */
public final class RegistrationInProgress {

  /** Section 2: the matrix {@code issues-andamento-extrair} wrote after its gates passed. */
  public static final String MATRIX_SHA256 =
      "0b811b8fd3f2e60073b3bc6c32535749cc71beb7491a067a61329e41dadb99d6";

  /** Section 5: the alphas and lambdas the inner selection chose, at full precision. */
  public static final String PARAMETERS_SHA256 =
      "0600bf88ba533319c8c7667322f39e9c1038d0a937928c195f420b6c16fc8c2a";

  /** Section 2: outer training, outer test, inner fitting and inner selection rows. */
  public record Sizes(int train, int test, int innerFit, int innerSelection) {}

  public static final Sizes SIZES = new Sizes(11_606, 5_066, 8_000, 3_483);

  /** Section 5: eleven cells in one population. */
  public static final int PARAMETER_LINES = 11;

  private RegistrationInProgress() {}

  /** Every difference between what was registered and what was found; empty when none. */
  public static List<String> differences(final String matrixSha, final String parametersSha,
      final int parameterLines, final Sizes found) {
    final List<String> out = new ArrayList<>();
    if (!MATRIX_SHA256.equals(matrixSha)) {
      out.add("matrix: SHA-256 " + matrixSha + ", registered " + MATRIX_SHA256);
    }
    if (!PARAMETERS_SHA256.equals(parametersSha)) {
      out.add("parameters: SHA-256 " + parametersSha + ", registered " + PARAMETERS_SHA256);
    }
    if (parameterLines != PARAMETER_LINES) {
      out.add("parameters: " + parameterLines + " lines, registered " + PARAMETER_LINES);
    }
    if (!SIZES.equals(found)) {
      out.add("IN_PROGRESS: " + found + ", registered " + SIZES);
    }
    return out;
  }
}
