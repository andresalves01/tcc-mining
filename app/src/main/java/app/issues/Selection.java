package app.issues;

import app.issues.Universe.Population;
import app.issues.model.Fitting;
import app.study.model.Baseline;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The choice, on the inner validation only, of each similarity method's candidate value -- the
 * number of Jaccard or embedding neighbours, the number of clusters -- by the error of its direct
 * predictor.
 *
 * <p><b>Methods.</b> Found in the matrix, not listed here: a method is every column named
 * {@code <method>_<value>_inner}, the value a whole number, as {@link Augmentation} names its
 * candidate columns. So the Jaccard columns alone are enough, and the methods the stage gains later
 * are picked up as they appear.
 *
 * <p><b>Direct predictor.</b> The column's value is the prediction, in hours, with no fitting, as
 * the existing references are read; a missing value falls back to the global median duration of
 * the inner fitting set. Its error is the mean absolute error in hours over every inner validation
 * row, fallback included, the scale on which the current chain chooses its parameters. The value
 * with the lowest error is chosen, ties within {@link #TIE_HOURS} going to the smaller value.
 */
public final class Selection {

  /**
   * Two errors this close, in hours, are a tie, and the smaller candidate value is chosen: the
   * smallest value whose error is within this of the lowest. A hundredth of an hour, the
   * precision the chain prints errors in hours with.
   */
  public static final double TIE_HOURS = 0.01;

  /** Sums of hours divided by rows: a difference of exactly {@link #TIE_HOURS} is still a tie. */
  private static final double ROUNDING = 1e-9;

  private static final Pattern CANDIDATE = Pattern.compile("(.+)_(\\d{1,6})_inner");

  private static final Population P = Population.IN_PROGRESS;

  private static final String TRAIN = "TRAIN";
  private static final String TEST = "TEST";

  /**
   * One candidate value of a method on the inner validation.
   *
   * @param maeHours over every validation row, a missing value read as the fallback
   * @param observed validation rows where the column has a value
   * @param maeObservedHours over those rows only; NaN when there is none
   */
  public record Candidate(int value, String column, double maeHours, int observed,
      double maeObservedHours) {}

  /**
   * @param chosen the candidate value every later model reads
   */
  public record Method(String name, List<Candidate> candidates, int chosen) {}

  /**
   * @param fallbackHours the global median duration of the inner fitting set
   */
  public record Result(int fitRows, int validationRows, double fallbackHours,
      List<Method> methods) {}

  /**
   * One line of the parameters file: which step chose it, for what, which parameter, its value.
   *
   * @param step {@link #DIRECT} for a method's candidate value; the later steps add their own
   */
  public record Parameter(String step, String name, String parameter, String value) {}

  /** The first line of every parameters file this step writes. */
  public static final String HEADER =
      "# chosen on the inner validation only; the outer test was not read";

  /** The step of the direct predictors in the parameters file. */
  public static final String DIRECT = "direct";

  private static final String CANDIDATE_VALUE = "candidate";

  private Selection() {}

  /**
   * Outer training, outer test, inner fitting and inner validation rows, counted from the split
   * labels: nothing else of an outer test row is read.
   */
  public static RegistrationInProgress.Sizes sizes(final List<RowCache.Entry> entries) {
    int train = 0;
    int test = 0;
    int fit = 0;
    int validation = 0;
    for (final RowCache.Entry e : entries) {
      if (TRAIN.equals(e.outer(P))) {
        train++;
        fit += TRAIN.equals(e.inner(P)) ? 1 : 0;
        validation += TEST.equals(e.inner(P)) ? 1 : 0;
      } else if (TEST.equals(e.outer(P))) {
        test++;
      }
    }
    return new RegistrationInProgress.Sizes(train, test, fit, validation);
  }

  /**
   * @param columns the matrix's columns, as {@link RowCache#columns} reads them
   */
  public static Result run(final List<RowCache.Entry> entries, final List<String> columns) {
    final Fitting.Sides sides = Fitting.sides(entries, Fitting.Mode.INNER, P);
    final double fallback = Baseline.learnedOn(sides.fitUnits()).constantHours();
    final double[] observed = sides.observed();

    final Map<String, Map<Integer, String>> methods = new TreeMap<>();
    for (final String column : columns) {
      final Matcher m = CANDIDATE.matcher(column);
      if (m.matches()) {
        methods.computeIfAbsent(m.group(1), k -> new TreeMap<>())
            .put(Integer.parseInt(m.group(2)), column);
      }
    }
    final List<Method> out = new ArrayList<>();
    for (final Map.Entry<String, Map<Integer, String>> method : methods.entrySet()) {
      final List<Candidate> candidates = new ArrayList<>();
      for (final Map.Entry<Integer, String> c : method.getValue().entrySet()) {
        final double[] predicted = new double[observed.length];
        int seen = 0;
        double seenError = 0;
        for (int i = 0; i < predicted.length; i++) {
          final Double value = sides.scoreRows().get(i).values().get(c.getValue());
          if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(c.getValue() + " of "
                + sides.scoreUnits().get(i).issueId() + " is " + value + ", not a duration");
          }
          predicted[i] = value == null ? fallback : value;
          if (value != null) {
            seen++;
            seenError += Math.abs(value - observed[i]);
          }
        }
        candidates.add(new Candidate(c.getKey(), c.getValue(), Fitting.mae(predicted, observed),
            seen, seen == 0 ? Double.NaN : seenError / seen));
      }
      final double lowest =
          candidates.stream().mapToDouble(Candidate::maeHours).min().orElseThrow();
      final Candidate chosen = candidates.stream()
          .filter(c -> c.maeHours() - lowest <= TIE_HOURS + ROUNDING).findFirst()
          .orElseThrow();
      out.add(new Method(method.getKey(), List.copyOf(candidates), chosen.value()));
    }
    return new Result(sides.fitUnits().size(), observed.length, fallback, List.copyOf(out));
  }

  /**
   * The parameters file: a commented header, then one tab-separated line per parameter, {@code
   * step, name, parameter, value}. Here one {@link #DIRECT} line per method; a later step appends
   * its own lines in the same four fields, so one file carries every choice to the test.
   */
  public static List<String> parameters(final Result r) {
    final List<String> out = new ArrayList<>();
    out.add(HEADER);
    out.add("# step\tname\tparameter\tvalue");
    for (final Method m : r.methods()) {
      out.add(String.join("\t", DIRECT, m.name(), CANDIDATE_VALUE, Integer.toString(m.chosen())));
    }
    return out;
  }

  /** Every parameter of a parameters file, in its order; comments and blank lines skipped. */
  public static List<Parameter> read(final List<String> lines) {
    final List<Parameter> out = new ArrayList<>();
    final Set<String> seen = new HashSet<>();
    for (final String line : lines) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      final String[] f = line.split("\t", -1);
      if (f.length != 4) {
        throw new IllegalArgumentException("not step, name, parameter and value: " + line);
      }
      if (!seen.add(f[0] + '\u0000' + f[1] + '\u0000' + f[2])) {
        throw new IllegalArgumentException("given twice: " + line);
      }
      out.add(new Parameter(f[0], f[1], f[2], f[3]));
    }
    return List.copyOf(out);
  }

  /** The candidate value chosen for each method. */
  public static Map<String, Integer> chosen(final List<Parameter> parameters) {
    final Map<String, Integer> out = new TreeMap<>();
    for (final Parameter p : parameters) {
      if (p.step().equals(DIRECT) && p.parameter().equals(CANDIDATE_VALUE)) {
        out.put(p.name(), Integer.parseInt(p.value()));
      }
    }
    return out;
  }
}
