package app.paper5.model;

import app.paper5.Attribute;
import app.paper5.Row;
import app.paper5.Construct;
import app.paper5.Outcome;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The numbers a model sees, built from one side and applied to both.
 *
 * <p>Every parameter here -- the median that fills a gap, the levels a category expands to, the mean
 * and spread that standardise a column -- is computed on the <b>fit</b> side alone and then applied
 * unchanged to the side being scored. That is the whole reason this is an object with a factory
 * rather than a function: a transformation that learns from the rows it is about to score has
 * already leaked, however small the effect looks.
 *
 * <p>Three shapes of column, and each is handled once:
 *
 * <ul>
 *   <li><b>numeric</b> -- optionally {@code log1p}, per the attribute's declared transform, then
 *       standardised. A missing value is filled with the fit side's median and flagged;
 *   <li><b>binary</b> -- already zero or one, standardised like the rest so the coefficients stay
 *       comparable;
 *   <li><b>categorical</b> -- expanded to one indicator per level seen on the fit side, minus the
 *       lowest, which becomes the reference. A level that appears only in the scored set produces
 *       all zeros: the specification requires an unseen category to be accepted rather than refused,
 *       and all-zeros is exactly "the reference level, as far as this model can tell".
 * </ul>
 *
 * <p>A column that is constant on the fit side is dropped by name, because standardising it would
 * divide by zero and keeping it would hand the estimator a column carrying nothing.
 */
public final class DesignMatrix {

  /** The outcome, on the scale the models are fitted on. */
  public static double target(final Outcome unit) {
    return Math.log1p(unit.durationHours());
  }

  /**
   * Back to hours.
   *
   * <p>Small negatives are clamped to zero rather than kept: {@code expm1} of a slightly negative
   * prediction is a negative duration, which is not a thing, and the error it would contribute is
   * an artefact of the link rather than of the model. Clamping is stated here so that nobody has to
   * discover it in a scoring loop.
   */
  public static double hours(final double predicted) {
    return Math.max(0.0, Math.expm1(predicted));
  }

  private final List<String> columns;
  private final List<String> dropped;
  private final List<Term> terms;

  private DesignMatrix(final List<String> columns, final List<String> dropped,
      final List<Term> terms) {

    this.columns = List.copyOf(columns);
    this.dropped = List.copyOf(dropped);
    this.terms = List.copyOf(terms);
  }

  /** One column of the matrix: how to read it from a row, and how to standardise it. */
  private record Term(String name, String source, Attribute.Kind kind, boolean log,
      double level, double fill, double mean, double sd) {

    double of(final Row row) {
      final double raw;
      if (kind == Attribute.Kind.CATEGORICAL) {
        final Double value = row.values().get(source);
        raw = value != null && value == level ? 1.0 : 0.0;
      } else {
        final Double value = row.values().get(source);
        final double present = value == null ? fill : value;
        raw = log ? Math.log1p(present) : present;
      }
      return (raw - mean) / sd;
    }
  }

  /**
   * @param fit the rows every parameter is learned from. Never the rows being scored
   * @param attributes the cell's constructs, already filtered by family, profile and exclusion
   */
  public static DesignMatrix fittedOn(final List<Row> fit,
      final List<? extends Construct> attributes) {
    final List<Term> terms = new ArrayList<>();
    final List<String> dropped = new ArrayList<>();

    for (final Construct attribute : attributes) {
      if (attribute.kind() == Attribute.Kind.CATEGORICAL) {
        addCategorical(fit, attribute, terms, dropped);
        continue;
      }
      final double fill = medianOf(fit, attribute.name());
      final boolean log = attribute.transform() == Attribute.Transform.LOG1P;
      final double[] raw = new double[fit.size()];
      for (int i = 0; i < raw.length; i++) {
        final Double value = fit.get(i).values().get(attribute.name());
        final double present = value == null ? fill : value;
        raw[i] = log ? Math.log1p(present) : present;
      }
      add(terms, dropped, attribute.name(), attribute.name(), attribute.kind(), log, 0, fill, raw);

      // The absence indicator rides beside the value it explains, never instead of it. Only the
      // three constructs whose absence was measured declare one.
      if (attribute.absenceIndicator()) {
        final String indicator = attribute.absenceName();
        final double[] flags = new double[fit.size()];
        for (int i = 0; i < flags.length; i++) {
          flags[i] = fit.get(i).values().getOrDefault(indicator, 0.0);
        }
        add(terms, dropped, indicator, indicator, Attribute.Kind.BINARY, false, 0, 0, flags);
      }
    }
    return new DesignMatrix(terms.stream().map(Term::name).toList(), dropped, terms);
  }

  private static void addCategorical(final List<Row> fit, final Construct attribute,
      final List<Term> terms, final List<String> dropped) {

    final TreeSet<Double> levels = new TreeSet<>();
    for (final Row row : fit) {
      final Double value = row.values().get(attribute.name());
      if (value != null) {
        levels.add(value);
      }
    }
    if (levels.size() < 2) {
      dropped.add(attribute.name() + " (uma categoria so)");
      return;
    }
    // The lowest level is the reference. With an intercept, a full set of indicators is collinear
    // with it, and which one is dropped is a choice that has to be made somewhere and stated.
    final Double reference = levels.first();
    for (final Double level : levels) {
      if (level.equals(reference)) {
        continue;
      }
      final double[] raw = new double[fit.size()];
      for (int i = 0; i < raw.length; i++) {
        final Double value = fit.get(i).values().get(attribute.name());
        raw[i] = value != null && value.equals(level) ? 1.0 : 0.0;
      }
      add(terms, dropped, attribute.name() + "=" + format(level), attribute.name(),
          Attribute.Kind.CATEGORICAL, false, level, 0, raw);
    }
  }

  private static void add(final List<Term> terms, final List<String> dropped, final String name,
      final String source, final Attribute.Kind kind, final boolean log, final double level,
      final double fill, final double[] raw) {

    double mean = 0;
    for (final double value : raw) {
      mean += value;
    }
    mean /= raw.length;
    double variance = 0;
    for (final double value : raw) {
      variance += (value - mean) * (value - mean);
    }
    final double sd = Math.sqrt(variance / raw.length);
    if (sd <= 1e-12) {
      dropped.add(name);
      return;
    }
    terms.add(new Term(name, source, kind, log, level, fill, mean, sd));
  }

  private static double medianOf(final List<Row> fit, final String column) {
    final List<Double> present = new ArrayList<>(fit.size());
    for (final Row row : fit) {
      final Double value = row.values().get(column);
      if (value != null) {
        present.add(value);
      }
    }
    if (present.isEmpty()) {
      return 0.0;
    }
    present.sort(Double::compare);
    final int middle = present.size() / 2;
    return present.size() % 2 == 1 ? present.get(middle)
        : (present.get(middle - 1) + present.get(middle)) / 2.0;
  }

  private static String format(final double level) {
    return level == Math.rint(level) ? String.valueOf((long) level) : String.valueOf(level);
  }

  /** The columns that survived, in order. What a coefficient listing is read against. */
  public List<String> columns() {
    return columns;
  }

  /** What was left out for being constant on the fit side, so a report can say so. */
  public List<String> dropped() {
    return dropped;
  }

  public double[] apply(final Row row) {
    final double[] values = new double[terms.size()];
    for (int i = 0; i < values.length; i++) {
      values[i] = terms.get(i).of(row);
    }
    return values;
  }

  public double[][] applyAll(final List<Row> rows) {
    final double[][] matrix = new double[rows.size()][];
    for (int i = 0; i < matrix.length; i++) {
      matrix[i] = apply(rows.get(i));
    }
    return matrix;
  }

  /** The per-column mean and spread, for a report that has to record what standardising did. */
  public Map<String, double[]> scaling() {
    final Map<String, double[]> scaling = new LinkedHashMap<>();
    terms.forEach(term -> scaling.put(term.name(), new double[] {term.mean(), term.sd()}));
    return scaling;
  }

  @Override
  public String toString() {
    return columns.size() + " colunas" + (dropped.isEmpty() ? ""
        : ", " + dropped.size() + " retiradas por serem constantes: " + Arrays.toString(dropped.toArray()));
  }
}
