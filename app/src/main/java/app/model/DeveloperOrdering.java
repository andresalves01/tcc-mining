package app.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Whether the model can say which of two developers finishes sooner — which is the question team
 * selection actually consumes.
 *
 * <p>Ordering tasks and ordering people are different abilities and the study needs the second.
 * A model can rank tasks well purely from how large each change turned out to be, and still be
 * unable to tell two people apart. So the comparison is restricted to pairs of observations in the
 * <b>same project</b> with <b>different developers</b> and <b>comparable tasks</b>, and what is
 * scored is whether the sign of the predicted difference matches the sign of the real one.
 *
 * <p><b>There is no single right filter, so the whole gradient is reported.</b> Measured
 * 2026-09-21 against the mongosh prototype, which the Java implementation reproduces pair for
 * pair: the agreement falls monotonically as task attributes are controlled — 63.4% over any pair,
 * 59.5% matching size, 53.6% matching type, size and description. The last is the honest one, and
 * it is barely above the coin. Reporting only a loose filter would credit the developer component
 * with task ordering; reporting only the tight one would hide how much of the drop is the filter.
 *
 * <p>The pairs are not independent of each other — one observation appears in many — so the
 * decimals of the resulting share must not be read as precision. What it answers is whether the
 * number sits above the coin flip at all.
 */
public final class DeveloperOrdering {

  private DeveloperOrdering() {}

  public record Result(String matching, int pairs, double agreement) {}

  /**
   * How alike two tasks must be before their developers are compared.
   *
   * <p>The five the mongosh prototype sweeps, so the two implementations report the same thing.
   *
   * @param sizeTolerance {@link Double#MAX_VALUE} to not match on size at all
   */
  public record Matching(String name, boolean sameType, double sizeTolerance,
      double descriptionTolerance) {

    public static final Matching ANY =
        new Matching("qualquer par", false, Double.MAX_VALUE, Double.MAX_VALUE);
    public static final Matching TYPE =
        new Matching("mesmo tipo", true, Double.MAX_VALUE, Double.MAX_VALUE);
    public static final Matching SIZE =
        new Matching("tamanho parecido", false, 0.5, Double.MAX_VALUE);
    public static final Matching TYPE_SIZE =
        new Matching("mesmo tipo E tamanho", true, 0.5, Double.MAX_VALUE);
    public static final Matching TIGHT =
        new Matching("mesmo tipo, tamanho e descricao", true, 0.25, 0.5);

    /** Loosest first, so the report shows the gradient rather than one chosen number. */
    public static final List<Matching> GRADIENT = List.of(ANY, TYPE, SIZE, TYPE_SIZE, TIGHT);
  }

  public static Result of(final List<Observation> rows,
      final ToDoubleFunction<Observation> prediction, final Matching matching) {

    final Map<String, List<Observation>> byProject = new HashMap<>();
    for (final Observation row : rows) {
      byProject.computeIfAbsent(row.project(), k -> new ArrayList<>()).add(row);
    }
    int pairs = 0;
    int agree = 0;
    for (final List<Observation> inProject : byProject.values()) {
      for (int i = 0; i < inProject.size(); i++) {
        for (int j = i + 1; j < inProject.size(); j++) {
          final Observation a = inProject.get(i);
          final Observation b = inProject.get(j);
          if (a.developer().equals(b.developer())) {
            continue; // the question is about people, not about the same person twice
          }
          if (!comparable(a, b, matching)) {
            continue; // too different a task for the developer to be what separates them
          }
          final double real = a.logTtc() - b.logTtc();
          final double guess =
              prediction.applyAsDouble(a) - prediction.applyAsDouble(b);
          if (real == 0 || guess == 0) {
            continue; // a tie decides nothing either way
          }
          pairs++;
          if (Math.signum(real) == Math.signum(guess)) {
            agree++;
          }
        }
      }
    }
    return new Result(matching.name(), pairs, pairs == 0 ? 0 : (double) agree / pairs);
  }

  private static boolean comparable(final Observation a, final Observation b,
      final Matching matching) {

    if (matching.sameType()) {
      for (final int column : Columns.typeIndices()) {
        if (a.values()[column] != b.values()[column]) {
          return false;
        }
      }
    }
    return alike(a, b, Columns.taskSize(), matching.sizeTolerance())
        && alike(a, b, Columns.descriptionLength(), matching.descriptionTolerance());
  }

  private static boolean alike(final Observation a, final Observation b, final int column,
      final double tolerance) {

    return tolerance == Double.MAX_VALUE
        || Math.abs(a.values()[column] - b.values()[column]) <= tolerance;
  }
}
