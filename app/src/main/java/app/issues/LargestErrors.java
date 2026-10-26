package app.issues;

import app.study.Row;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The largest errors on each side of one model, with what the matrix says of each issue: the
 * profiles the In Progress study's analyses read by hand (25/10/2026), here written by the inner
 * report of the first-commit study. Underestimates are the issues whose observed business days
 * most exceed the prediction, overestimates those whose prediction most exceeds them; the error is
 * {@code observed - predicted}, positive for an underestimate.
 *
 * <p>Of every scored issue, read from its row: the issue type, the target's parts (from the first
 * commit to the last mark, the days after it, the days the cap took out), the commits and patches
 * among the marks, the first commit's size and share and the issue's changed lines they give,
 * the Java lines and files of its commits, the comments and participants, the description's
 * length. Beside the issues, each column's median on each side's issues and on every scored issue,
 * so a side's profile reads against the whole. Reads the scored issues only: in the inner report,
 * the inner validation.
 */
public final class LargestErrors {

  /** How many issues each side lists. */
  public static final int COUNT = 10;

  /** What the profiles are, in words for the report. */
  public static final String RULE = "the issues of the largest error on each side, the error "
      + "observed minus predicted business days (positive: the model predicted too little); ties "
      + "in the scored issues' order; the share is of the model's absolute error summed over every "
      + "scored issue; every column is the matrix's for that issue, the issue's changed lines the "
      + "first commit's lines over its share";

  // the target's parts as the first-commit extraction writes them (IssuesFirstCommitExtractTask)
  private static final String IMPLEMENTATION = "FC_implementation_days";
  private static final String TAIL = "FC_tail_days";
  private static final String CAPPED = "FC_capped_days";
  private static final String MARKS = "FC_marks";
  private static final String PATCHES = "FC_patches";

  /** The prefix of the issue type's indicator columns. */
  private static final String TYPE = "Z_type_";

  /**
   * One column of a profile.
   *
   * @param name its header
   * @param of its value for a row, null when missing
   */
  public record Column(String name, Function<Row, Double> of) {}

  /** The profile's columns, in their order. */
  public static final List<Column> COLUMNS = List.of(
      column("implementation_bd", IMPLEMENTATION),
      column("after_last_mark_bd", TAIL),
      column("capped_bd", CAPPED),
      new Column("commits", r -> {
        final Double marks = r.values().get(MARKS);
        final Double patches = r.values().get(PATCHES);
        return marks == null || patches == null ? null : marks - patches;
      }),
      column("patches", PATCHES),
      column("first_commit_lines", FirstCommit.FIRST_LINES),
      column("first_commit_share", FirstCommit.FIRST_SHARE),
      new Column("issue_lines", r -> {
        final Double first = r.values().get(FirstCommit.FIRST_LINES);
        final Double share = r.values().get(FirstCommit.FIRST_SHARE);
        return first == null || share == null || share <= 0 ? null
            : (double) Math.round(first / share);
      }),
      column("java_lines", Retrospective.LINES),
      column("files", Retrospective.FILES),
      column("comments", Retrospective.COMMENTS),
      column("participants", Retrospective.PARTICIPANTS),
      column("description_length", "A01_desc_length"));

  private static Column column(final String name, final String matrixColumn) {
    return new Column(name, r -> r.values().get(matrixColumn));
  }

  /**
   * One listed issue.
   *
   * @param side {@code under} or {@code over}
   * @param rank 1 for the largest error of its side
   * @param type the issue type, from the matrix's indicators; {@code ?} when none is set
   * @param share its absolute error over the model's, summed over every scored issue
   * @param values per column of {@link #COLUMNS}, its value, null when missing
   */
  public record Profile(String side, int rank, IssueUnit unit, String type, double observed,
      double predicted, double error, double share, List<Double> values) {}

  /**
   * Both sides of one model.
   *
   * @param scored the scored issues
   * @param absoluteError the model's absolute error summed over them, in business days
   * @param medians per column of {@link #COLUMNS}, its median on the underestimates, on the
   *     overestimates and on every scored issue, NaN where none has it
   */
  public record Profiles(String model, int scored, double absoluteError, List<Profile> under,
      List<Profile> over, List<double[]> medians) {}

  private LargestErrors() {}

  /**
   * The {@code count} largest errors on each side of {@code model} in the scored issues of {@code
   * report}, their rows read from {@code entries} by id.
   */
  public static Profiles of(final SimilarityScoring.Report report,
      final List<RowCache.Entry> entries, final String model, final int count) {
    final SimilarityScoring.Scored scored = report.models().stream()
        .filter(m -> m.name().equals(model)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no model " + model + " in the report"));
    final Map<String, Row> rows = new HashMap<>(entries.size() * 2);
    entries.forEach(e -> rows.put(e.unit().issueId(), e.row()));
    final double[] observed = report.observed();
    final double[] predicted = scored.predicted();
    final int n = observed.length;
    double absolute = 0;
    for (int i = 0; i < n; i++) {
      absolute += Math.abs(observed[i] - predicted[i]);
    }
    final List<Integer> order = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      order.add(i);
    }
    final Comparator<Integer> byError =
        Comparator.comparingDouble(i -> observed[i] - predicted[i]);
    final List<Integer> under = order.stream().filter(i -> observed[i] > predicted[i])
        .sorted(byError.reversed()).limit(count).toList();
    final List<Integer> over = order.stream().filter(i -> observed[i] < predicted[i])
        .sorted(byError).limit(count).toList();
    final List<Profile> u = profiles("under", under, report, rows, predicted, absolute);
    final List<Profile> o = profiles("over", over, report, rows, predicted, absolute);
    final List<double[]> medians = new ArrayList<>(COLUMNS.size());
    for (int c = 0; c < COLUMNS.size(); c++) {
      final List<Double> all = new ArrayList<>(n);
      for (final IssueUnit unit : report.scoredUnits()) {
        all.add(COLUMNS.get(c).of().apply(row(rows, unit)));
      }
      final int k = c;
      medians.add(new double[] {median(u.stream().map(p -> p.values().get(k)).toList()),
          median(o.stream().map(p -> p.values().get(k)).toList()), median(all)});
    }
    return new Profiles(model, n, absolute, u, o, medians);
  }

  private static List<Profile> profiles(final String side, final List<Integer> indices,
      final SimilarityScoring.Report report, final Map<String, Row> rows,
      final double[] predicted, final double absolute) {
    final List<Profile> out = new ArrayList<>(indices.size());
    for (final int i : indices) {
      final IssueUnit unit = report.scoredUnits().get(i);
      final Row row = row(rows, unit);
      final List<Double> values = new ArrayList<>(COLUMNS.size());
      for (final Column c : COLUMNS) {
        values.add(c.of().apply(row));
      }
      final double error = report.observed()[i] - predicted[i];
      out.add(new Profile(side, out.size() + 1, unit, type(row), report.observed()[i],
          predicted[i], error, absolute == 0 ? 0 : Math.abs(error) / absolute, values));
    }
    return List.copyOf(out);
  }

  private static Row row(final Map<String, Row> rows, final IssueUnit unit) {
    final Row row = rows.get(unit.issueId());
    if (row == null) {
      throw new IllegalArgumentException(unit.issueId() + " is scored and has no row");
    }
    return row;
  }

  /** The issue type whose indicator is set, {@code ?} when none is. */
  static String type(final Row row) {
    return row.values().entrySet().stream()
        .filter(e -> e.getKey().startsWith(TYPE) && e.getValue() == 1.0)
        .map(e -> e.getKey().substring(TYPE.length())).sorted().findFirst().orElse("?");
  }

  /** The median of the values present; NaN when none is. */
  static double median(final List<Double> values) {
    final double[] v = values.stream().filter(x -> x != null).mapToDouble(Double::doubleValue)
        .sorted().toArray();
    if (v.length == 0) {
      return Double.NaN;
    }
    return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
  }

  /** The listed issues, one line each, under a header. */
  public static List<String> tsv(final Profiles p) {
    final List<String> out = new ArrayList<>();
    final StringBuilder header = new StringBuilder("model\tside\trank\tid\tkey\tproject\ttype"
        + "\tobserved_bd\tpredicted_bd\terror_bd\tshare_of_absolute_error");
    COLUMNS.forEach(c -> header.append('\t').append(c.name()));
    out.add(header.toString());
    for (final Profile x : concat(p)) {
      final StringBuilder line = new StringBuilder(String.join("\t", p.model(), x.side(),
          Integer.toString(x.rank()), x.unit().issueId(), x.unit().key(), x.unit().project(),
          x.type(), Double.toString(x.observed()), Double.toString(x.predicted()),
          Double.toString(x.error()), Double.toString(x.share())));
      x.values().forEach(v -> line.append('\t').append(v == null ? "" : Double.toString(v)));
      out.add(line.toString());
    }
    return out;
  }

  private static List<Profile> concat(final Profiles p) {
    final List<Profile> out = new ArrayList<>(p.under());
    out.addAll(p.over());
    return out;
  }

  /** The section of the report: both sides' issues, then the medians against every issue. */
  public static List<String> markdown(final Profiles p) {
    final List<String> md = new ArrayList<>();
    md.add("## The largest errors of " + p.model() + ", every inner validation issue");
    md.add("");
    md.add(RULE + ". Of the " + p.scored() + " issues, the model's absolute error sums to "
        + f("%.1f", p.absoluteError()) + " business days.");
    md.add("");
    for (final String side : List.of("under", "over")) {
      final List<Profile> list = side.equals("under") ? p.under() : p.over();
      md.add("### " + (side.equals("under") ? "Underestimates: the model predicted too little"
          : "Overestimates: the model predicted too much") + f(" (%.1f%% of the absolute error)",
          100 * list.stream().mapToDouble(Profile::share).sum()));
      md.add("");
      final StringBuilder head = new StringBuilder("| issue | type | observed | predicted | error");
      final StringBuilder rule = new StringBuilder("|---|---|---:|---:|---:");
      COLUMNS.forEach(c -> {
        head.append(" | ").append(c.name());
        rule.append("|---:");
      });
      md.add(head.append(" |").toString());
      md.add(rule.append("|").toString());
      for (final Profile x : list) {
        final StringBuilder line = new StringBuilder(f("| %s | %s | %.0f | %.1f | %+.1f",
            x.unit().key(), x.type(), x.observed(), x.predicted(), x.error()));
        x.values().forEach(v -> line.append(" | ").append(number(v)));
        md.add(line.append(" |").toString());
      }
      md.add("");
    }
    md.add("### Each column's median: the underestimates, the overestimates, every issue");
    md.add("");
    md.add("| column | underestimates | overestimates | every issue |");
    md.add("|---|---:|---:|---:|");
    for (int c = 0; c < COLUMNS.size(); c++) {
      final double[] m = p.medians().get(c);
      md.add("| " + COLUMNS.get(c).name() + " | " + number(m[0]) + " | " + number(m[1]) + " | "
          + number(m[2]) + " |");
    }
    md.add("");
    return md;
  }

  private static String number(final Double v) {
    if (v == null || v.isNaN()) {
      return "-";
    }
    return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString(v.longValue())
        : f("%.2f", v);
  }

  private static String f(final String format, final Object... args) {
    return String.format(Locale.ROOT, format, args);
  }
}
