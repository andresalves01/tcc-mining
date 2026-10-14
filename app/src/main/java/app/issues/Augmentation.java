package app.issues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The columns that depend on which issues are allowed as history, added to the in-progress matrix
 * after extraction: the median duration of the issues whose descriptions are closest by Jaccard,
 * for each candidate number of neighbours, once against the inner split and once against the outer.
 *
 * <p><b>History.</b> The rows of a fitting set (inner fitting; outer training) are built in five
 * consecutive time blocks of that set, ordered by first In Progress with equal instants kept in the
 * same block; a block's history is the issues of the same set that started and were resolved
 * before the block's first instant, which is its reference instant. So an issue that crosses the
 * inner cut, part of the outer training set, enters an outer block's history only once resolved.
 * The scored rows (inner validation; outer test) read the whole fitting set, whose every issue was
 * resolved before the cut and so before any scored row started; their reference instant is their
 * own start. Validation and test outcomes never enter any history.
 *
 * <p><b>Neighbours.</b> The candidates are the history issues sharing at least one token with the
 * query; the closest n by Jaccard distance give the unweighted median of their durations in hours,
 * all of them when fewer than n exist. Ties in distance go by issue id, never by duration: the
 * ids are 24-digit hexadecimal Mongo object ids, so their order as strings is their numeric order. The
 * value is missing when the query has no tokens or no candidate; nothing is dropped.
 *
 * <p>Pure: rows and descriptions in, columns and diagnostics out.
 */
public final class Augmentation {

  /** The candidate numbers of Jaccard neighbours the selection chooses from. */
  public static final List<Integer> JACCARD_N = List.of(5, 10, 20);

  /** How many consecutive time blocks a fitting set is built in. */
  public static final int BLOCKS = 5;

  /** The block label of a scored row, which reads the whole fitting set. */
  public static final String WHOLE = "whole";

  private static final String TRAIN = "TRAIN";
  private static final String TEST = "TEST";

  /** Which split a column is computed against; its name ends in the suffix. */
  public enum Variant {
    INNER("_inner"), OUTER("_outer");

    private final String suffix;

    Variant(final String suffix) {
      this.suffix = suffix;
    }

    /** The rows whose outcomes may be history: the inner fitting set, or the outer training. */
    boolean fitting(final Issue i) {
      return this == INNER ? TRAIN.equals(i.outer()) && TRAIN.equals(i.inner())
          : TRAIN.equals(i.outer());
    }

    /** The rows scored against the whole fitting set: inner validation, or outer test. */
    boolean scored(final Issue i) {
      return this == INNER ? TRAIN.equals(i.outer()) && TEST.equals(i.inner())
          : TEST.equals(i.outer());
    }

    public String split(final Issue i) {
      return fitting(i) ? (this == INNER ? "inner_fitting" : "outer_training")
          : scored(i) ? (this == INNER ? "inner_validation" : "outer_test") : null;
    }
  }

  /**
   * One extracted issue.
   *
   * @param start the first In Progress, epoch milliseconds
   * @param resolved the first resolution, epoch milliseconds
   * @param outer the outer verdict: TRAIN, TEST or CROSSES
   * @param inner the inner verdict inside the outer training, empty outside it
   * @param description the cleaned description at the first In Progress, null when there is none
   */
  public record Issue(String id, String project, long start, long resolved, String outer,
      String inner, String description) {

    public double hours() {
      return (resolved - start) / 3_600_000.0;
    }
  }

  public record Neighbour(String id, double distance) {}

  /**
   * One row's search against one variant's history.
   *
   * @param split inner_fitting, inner_validation, outer_training or outer_test
   * @param block 1 to {@link #BLOCKS} for a fitting row, {@link #WHOLE} for a scored one
   * @param reference the instant every neighbour was resolved before
   * @param tokens the query's distinct tokens
   * @param candidates the history issues sharing at least one token
   * @param nearest the closest candidates, up to the largest n, closest first
   */
  public record Query(String id, Variant variant, String split, String block, long reference, int tokens,
      int candidates, List<Neighbour> nearest) {

    /** How many neighbours the median with {@code n} read. */
    public int used(final int n) {
      return Math.min(n, nearest.size());
    }
  }

  /**
   * One time block of a fitting set.
   *
   * @param begins its first instant: the reference of every row in it
   * @param last its last instant
   * @param history the issues of the set started and resolved before it began
   */
  public record Block(Variant variant, int index, int rows, long begins, long last, int history) {}

  /** Rows and observed values of one column within one group. */
  public record Coverage(String column, String by, String group, int rows, int observed) {}

  /**
   * @param columns per issue id, every new column, absent when missing; every issue is a key
   */
  public record Result(Map<String, Map<String, Double>> columns, List<Query> queries,
      List<Block> blocks, List<Coverage> coverage) {}

  private Augmentation() {}

  /** The Jaccard median column for {@code n} neighbours. */
  public static String jaccard(final int n, final Variant v) {
    return "jaccard_median_" + n + v.suffix;
  }

  /** Every column this stage adds, in the order it writes them. */
  public static List<String> columns() {
    final List<String> out = new ArrayList<>();
    for (final int n : JACCARD_N) {
      for (final Variant v : Variant.values()) {
        out.add(jaccard(n, v));
      }
    }
    return out;
  }

  /** Outer training, outer test, inner fitting and inner validation rows. */
  public static RegistrationInProgress.Sizes sizes(final List<Issue> issues) {
    return new RegistrationInProgress.Sizes(count(issues, Variant.OUTER::fitting),
        count(issues, Variant.OUTER::scored), count(issues, Variant.INNER::fitting),
        count(issues, Variant.INNER::scored));
  }

  private static int count(final List<Issue> issues, final Predicate<Issue> p) {
    return (int) issues.stream().filter(p).count();
  }

  public static Result run(final List<Issue> issues) {
    final Tokens tokens = new Tokens(issues);
    final Map<String, Map<String, Double>> columns = new LinkedHashMap<>();
    issues.forEach(i -> columns.put(i.id(), new HashMap<>()));
    final List<Query> queries = new ArrayList<>();
    final List<Block> blocks = new ArrayList<>();
    final Map<String, String> blockOf = new HashMap<>();
    final int most = JACCARD_N.stream().mapToInt(Integer::intValue).max().orElseThrow();

    for (final Variant v : Variant.values()) {
      final List<Issue> fitting = issues.stream().filter(v::fitting)
          .sorted(Comparator.comparingLong(Issue::start).thenComparing(Issue::id)).toList();
      final List<List<Issue>> split = blocks(fitting);
      for (int b = 0; b < split.size(); b++) {
        final List<Issue> block = split.get(b);
        final long begins = block.get(0).start();
        final List<Issue> history =
            fitting.stream().filter(h -> h.start() < begins && h.resolved() < begins).toList();
        final Index index = new Index(history, tokens);
        blocks.add(new Block(v, b + 1, block.size(), begins, block.get(block.size() - 1).start(),
            history.size()));
        for (final Issue q : block) {
          blockOf.put(q.id() + v.suffix, Integer.toString(b + 1));
          queries.add(answer(q, v, Integer.toString(b + 1), begins, index, tokens, most, columns));
        }
      }
      final Index whole = new Index(fitting, tokens);
      final Issue latest =
          fitting.stream().max(Comparator.comparingLong(Issue::resolved)).orElse(null);
      for (final Issue q : issues) {
        if (!v.scored(q)) {
          continue;
        }
        if (latest != null && latest.resolved() >= q.start()) {
          throw new IllegalArgumentException(latest.id() + " in the fitting set was resolved at "
              + "or after " + q.id() + " in the scored set started: the split is inconsistent");
        }
        blockOf.put(q.id() + v.suffix, WHOLE);
        queries.add(answer(q, v, WHOLE, q.start(), whole, tokens, most, columns));
      }
    }
    return new Result(columns, queries, blocks, coverage(issues, columns, blockOf));
  }

  /**
   * Five consecutive blocks of a set ordered by start, as equal in size as equal instants allow: a
   * boundary that would split equal instants moves forward past them. Fewer when the set is small.
   */
  static List<List<Issue>> blocks(final List<Issue> sorted) {
    final List<List<Issue>> out = new ArrayList<>();
    int from = 0;
    for (int b = 1; b <= BLOCKS && from < sorted.size(); b++) {
      int to = b == BLOCKS ? sorted.size() : Math.max(from, (int) ((long) b * sorted.size() / BLOCKS));
      while (to < sorted.size() && to > 0 && sorted.get(to).start() == sorted.get(to - 1).start()) {
        to++;
      }
      if (to > from) {
        out.add(sorted.subList(from, to));
        from = to;
      }
    }
    return out;
  }

  private static Query answer(final Issue q, final Variant v, final String block,
      final long reference, final Index index, final Tokens tokens, final int most,
      final Map<String, Map<String, Double>> columns) {
    final int[] mine = tokens.of(q.id());
    final List<Index.Hit> hits = index.search(q.id(), mine);
    final List<Neighbour> nearest = new ArrayList<>();
    final List<Double> hours = new ArrayList<>();
    for (final Index.Hit h : hits.subList(0, Math.min(most, hits.size()))) {
      nearest.add(new Neighbour(h.issue().id(), 1.0 - h.shared() / (double) h.union()));
      hours.add(h.issue().hours());
    }
    if (!hours.isEmpty()) {
      for (final int n : JACCARD_N) {
        columns.get(q.id()).put(jaccard(n, v), median(hours.subList(0, Math.min(n, hours.size()))));
      }
    }
    return new Query(q.id(), v, v.split(q), block, reference, mine.length, hits.size(), nearest);
  }

  static double median(final List<Double> values) {
    final double[] s = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    final int n = s.length;
    return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
  }

  private static List<Coverage> coverage(final List<Issue> issues,
      final Map<String, Map<String, Double>> columns, final Map<String, String> blockOf) {
    final List<Coverage> out = new ArrayList<>();
    for (final String column : columns()) {
      final Variant v = column.endsWith(Variant.INNER.suffix) ? Variant.INNER : Variant.OUTER;
      final Map<String, int[]> split = new TreeMap<>();
      final Map<String, int[]> block = new TreeMap<>();
      final Map<String, int[]> project = new TreeMap<>();
      for (final Issue i : issues) {
        final String part = v.split(i);
        if (part == null) {
          continue;
        }
        final int seen = columns.get(i.id()).containsKey(column) ? 1 : 0;
        for (final int[] c : List.of(split.computeIfAbsent(part, k -> new int[2]),
            block.computeIfAbsent(part + " " + blockOf.get(i.id() + v.suffix), k -> new int[2]),
            project.computeIfAbsent(i.project(), k -> new int[2]))) {
          c[0]++;
          c[1] += seen;
        }
      }
      split.forEach((g, c) -> out.add(new Coverage(column, "split", g, c[0], c[1])));
      block.forEach((g, c) -> out.add(new Coverage(column, "block", g, c[0], c[1])));
      project.forEach((g, c) -> out.add(new Coverage(column, "project", g, c[0], c[1])));
    }
    return out;
  }

  /** Every issue's distinct tokens as sorted integers, one dictionary for the whole run. */
  private static final class Tokens {

    private final Map<String, int[]> byIssue = new HashMap<>();
    private final int distinct;

    Tokens(final List<Issue> issues) {
      final Map<String, Integer> dictionary = new HashMap<>();
      for (final Issue i : issues) {
        final Set<String> set = Jaccard.tokens(i.description());
        final int[] ids = new int[set.size()];
        int k = 0;
        for (final String t : set) {
          ids[k++] = dictionary.computeIfAbsent(t, x -> dictionary.size());
        }
        Arrays.sort(ids);
        byIssue.put(i.id(), ids);
      }
      distinct = dictionary.size();
    }

    int[] of(final String id) {
      return byIssue.get(id);
    }
  }

  /** A frozen history: which of its issues hold each token. */
  private static final class Index {

    record Hit(Issue issue, int shared, int union) {}

    private final List<Issue> members;
    private final int[] sizes;
    private final int[][] postings;

    Index(final List<Issue> members, final Tokens tokens) {
      this.members = members;
      this.sizes = new int[members.size()];
      final int[] length = new int[tokens.distinct];
      for (int j = 0; j < members.size(); j++) {
        final int[] t = tokens.of(members.get(j).id());
        sizes[j] = t.length;
        for (final int token : t) {
          length[token]++;
        }
      }
      postings = new int[tokens.distinct][];
      for (int token = 0; token < length.length; token++) {
        postings[token] = new int[length[token]];
      }
      final int[] filled = new int[tokens.distinct];
      for (int j = 0; j < members.size(); j++) {
        for (final int token : tokens.of(members.get(j).id())) {
          postings[token][filled[token]++] = j;
        }
      }
    }

    /** The members sharing a token with the query, closest first, ties by id; never the query. */
    List<Hit> search(final String self, final int[] query) {
      final int[] shared = new int[members.size()];
      final int[] touched = new int[members.size()];
      int count = 0;
      for (final int token : query) {
        for (final int j : postings[token]) {
          if (shared[j]++ == 0) {
            touched[count++] = j;
          }
        }
      }
      final List<Hit> out = new ArrayList<>(count);
      for (int k = 0; k < count; k++) {
        final int j = touched[k];
        if (!members.get(j).id().equals(self)) {
          out.add(new Hit(members.get(j), shared[j], query.length + sizes[j] - shared[j]));
        }
      }
      // closer first, compared exactly: a/b > c/d as a*d > c*b
      out.sort((x, y) -> {
        final int c = Long.compare((long) y.shared() * x.union(), (long) x.shared() * y.union());
        return c != 0 ? c : x.issue().id().compareTo(y.issue().id());
      });
      return out;
    }
  }
}
