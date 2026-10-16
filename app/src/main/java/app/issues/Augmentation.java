package app.issues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * The columns that depend on which issues are allowed as history, added to the in-progress matrix
 * after extraction: the median duration of the issues whose descriptions are closest by Jaccard,
 * and of those closest by embedding, for each candidate number of neighbours, and the reporter's
 * historical median, once against the inner split and once against the outer.
 *
 * <p><b>Duration</b> (ticket 14). Every median is of past issues' whole business days ({@link
 * BusinessDays}), each past issue counted at its own day boundary, as the target is. A past issue
 * of 0 business days enters no history -- no neighbour search, no cluster fit or cluster, no
 * reporter median -- so a history holds only the kind of issue the model predicts. The rows that read the
 * histories, the blocks they are cut in and their reference instants are those of every issue of
 * the split, as before: an issue of 0 business days is left out of the study after this stage, and
 * the columns it gets here are never read.
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
 * query; the closest n by Jaccard distance give the unweighted median of their business days,
 * all of them when fewer than n exist. Ties in distance go by issue id, never by duration: the
 * ids are 24-digit hexadecimal Mongo object ids, so their order as strings is their numeric order. The
 * value is missing when the query has no tokens or no candidate; nothing is dropped.
 *
 * <p><b>Embedding neighbours.</b> Each described issue is embedded once, and its vector read as
 * given: one that does not have the expected number of finite components at length one within
 * {@link #UNIT_TOLERANCE} is refused and recorded, never rescaled, so its issue has missing
 * embedding columns and is no candidate for anyone. The history, blocks and reference instants are
 * Jaccard's. The similarity is the dot product of the two unit vectors clamped to [-1, 1], the
 * distance one minus it; the candidates are the history issues at positive similarity, and the
 * closest n give the median as for Jaccard, ties by id, all of them when fewer than n. The value
 * is missing when the issue has no description, its vector was refused, or no candidate exists. The
 * search is a scan of every history vector; the dot product is summed in a fixed order, so a rerun
 * over the same vectors gives the same neighbours, bit for bit.
 *
 * <p><b>Reporter.</b> The unweighted median business days of the same canonical reporter's
 * other issues in the same project, from the matching fitting set, resolved strictly before this
 * issue's first In Progress; missing when there is none. Every row is Fixed, the population the
 * extraction reads. It corrects the extractor's {@code D05_reporter_project_median_h}, which
 * measures from creation over every issue of the reporter in the project resolved before t0, to
 * the target's interval and the matching split. It needs no frozen index, so it reads the set issue by issue rather than in blocks: a fitting row
 * sees the set up to its own start, a scored row the whole set.
 *
 * <p><b>Clusters.</b> For each candidate k, the {@link Clusterer} is fitted on the vectors of a
 * history, the same as the embedding neighbours', and on those alone, never on durations, with
 * seed {@link #CLUSTER_SEED}: once per block, frozen for its rows, and once on the whole fitting
 * set for the scored rows. Every history member with a valid vector is assigned to its nearest
 * centre by Euclidean distance, the rule the query is assigned by, so a cluster's members are the
 * members nearest to its centre. The value is the unweighted median duration of the members of
 * the query's cluster, the query itself never among them; the centre only picks the group. It is
 * missing when the query has no valid vector, when the history has fewer distinct vectors than k
 * -- for the whole block, k never shrunk -- or when the cluster has no member but the query. The
 * fits run on a pool of threads of their own; each depends on its vectors, k and seed alone.
 *
 * <p>Pure but for the embedder and the clusterer it is given: rows and descriptions in, columns
 * and diagnostics out.
 */
public final class Augmentation {

  /** The candidate numbers of Jaccard neighbours the selection chooses from. */
  public static final List<Integer> JACCARD_N = List.of(5, 10, 20);

  /** The candidate numbers of embedding neighbours the selection chooses from. */
  public static final List<Integer> EMBEDDING_N = List.of(5, 10, 20);

  /** The candidate numbers of K-means clusters the selection chooses from. */
  public static final List<Integer> CLUSTER_K = List.of(8, 16, 32);

  /** The seed of every K-means fit. */
  public static final long CLUSTER_SEED = 42;

  /** How many consecutive time blocks a fitting set is built in. */
  public static final int BLOCKS = 5;

  /**
   * How far a vector's length may be from one and still be read as a unit vector. The model's
   * vectors are scaled to length one in double precision and stored as floats, which leaves an
   * error near 1e-7; this bound is three orders of magnitude above it, and the same 1e-4 the
   * embedder's reference test allows per component.
   */
  public static final double UNIT_TOLERANCE = 1e-4;

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
   * @param reporter the canonical person who opened it
   * @param start the first In Progress, epoch milliseconds
   * @param resolved the first resolution, epoch milliseconds
   * @param businessDays its whole business days from {@code start} to {@code resolved}, at its own
   *     day boundary: what a median of it reads; 0 keeps it out of every history
   * @param outer the outer verdict: TRAIN, TEST or CROSSES
   * @param inner the inner verdict inside the outer training, empty outside it
   * @param description the cleaned description at the first In Progress, null when there is none
   */
  public record Issue(String id, String project, String reporter, long start, long resolved,
      int businessDays, String outer, String inner, String description) {

    /** Whether it may be history: it lasted at least one business day. */
    boolean counted() {
      return businessDays >= 1;
    }

    /** What a median reads of it. */
    double days() {
      return businessDays;
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
   * One row's embedding search against one variant's history, as {@link Query} is for Jaccard.
   *
   * @param candidates the history issues with a valid vector at positive similarity to the query
   * @param nearest the closest candidates, up to the largest n, closest first
   */
  public record EmbeddingQuery(String id, Variant variant, String split, String block,
      long reference, int candidates, List<Neighbour> nearest) {

    /** How many neighbours the median with {@code n} read. */
    public int used(final int n) {
      return Math.min(n, nearest.size());
    }
  }

  /**
   * One described issue's embedding.
   *
   * @param chunks how many chunks the embedder cut its description in
   * @param problem why its vector was refused, null when it is valid; a refused vector is no
   *     candidate and gives the issue missing embedding columns
   */
  public record Embedded(String id, int chunks, String problem) {}

  /**
   * One K-means fit of one history.
   *
   * @param block 1 to {@link #BLOCKS} for a block's history, {@link #WHOLE} for the fitting set
   * @param vectors the history members with a valid vector, the vectors fitted
   * @param distinct how many of those vectors differ, as {@link Clusterer#distinct} counts
   * @param distortion the clusterer's distortion of the fit, NaN when not fitted
   * @param members per cluster, the ids of the members nearest its centre, in history order;
   *     empty when not fitted
   */
  public record ClusterFit(Variant variant, String block, int k, int vectors, int distinct,
      boolean fitted, double distortion, List<List<String>> members) {}

  /**
   * One row's cluster for one k against one variant's history.
   *
   * @param reference the instant every member was resolved before
   * @param cluster the nearest centre's index, -1 when the query has no valid vector or the
   *     history was not fitted
   * @param distance the query's Euclidean distance to that centre, NaN without a cluster
   * @param members how many members the median read, the query itself left out
   */
  public record ClusterQuery(String id, Variant variant, String split, String block,
      long reference, int k, int cluster, double distance, int members) {}

  /**
   * @param columns per issue id, every new column, absent when missing; every issue is a key
   * @param embeddingQueries every row's embedding search, as {@code queries} for Jaccard
   * @param embedded every issue with a description, in the order of the rows
   * @param clusterFits every history's fit for every k, variant by variant, blocks then the whole
   *     set
   * @param clusterQueries every row's cluster for every k, in the order of the fits
   */
  public record Result(Map<String, Map<String, Double>> columns, List<Query> queries,
      List<Block> blocks, List<Coverage> coverage, List<EmbeddingQuery> embeddingQueries,
      List<Embedded> embedded, List<ClusterFit> clusterFits,
      List<ClusterQuery> clusterQueries) {}

  private Augmentation() {}

  /** The Jaccard median column for {@code n} neighbours. */
  public static String jaccard(final int n, final Variant v) {
    return "jaccard_median_" + n + v.suffix;
  }

  /** The embedding-neighbour median column for {@code n} neighbours. */
  public static String embedding(final int n, final Variant v) {
    return "embedding_median_" + n + v.suffix;
  }

  /** The cluster median column for {@code k} clusters. */
  public static String cluster(final int k, final Variant v) {
    return "cluster_median_" + k + v.suffix;
  }

  /** The reporter's historical median column. */
  public static String reporter(final Variant v) {
    return "reporter_median" + v.suffix;
  }

  /** Every column this stage adds, in the order it writes them. */
  public static List<String> columns() {
    final List<String> out = new ArrayList<>();
    for (final int n : JACCARD_N) {
      for (final Variant v : Variant.values()) {
        out.add(jaccard(n, v));
      }
    }
    for (final Variant v : Variant.values()) {
      out.add(reporter(v));
    }
    for (final int n : EMBEDDING_N) {
      for (final Variant v : Variant.values()) {
        out.add(embedding(n, v));
      }
    }
    for (final int k : CLUSTER_K) {
      for (final Variant v : Variant.values()) {
        out.add(cluster(k, v));
      }
    }
    return out;
  }

  /**
   * Every column and diagnostic, each described issue embedded once by {@code embedder} into
   * vectors of {@code dimension} components, the clusters fitted by {@code clusterer}.
   */
  public static Result run(final List<Issue> issues, final Embedder embedder,
      final int dimension, final Clusterer clusterer) {
    final Tokens tokens = new Tokens(issues);
    final Vectors vectors = new Vectors(issues, embedder, dimension);
    final List<EmbeddingQuery> embeddingQueries = new ArrayList<>();
    final int mostVectors = EMBEDDING_N.stream().mapToInt(Integer::intValue).max().orElseThrow();
    final Map<String, Map<String, Double>> columns = new LinkedHashMap<>();
    issues.forEach(i -> columns.put(i.id(), new HashMap<>()));
    final List<Query> queries = new ArrayList<>();
    final List<Block> blocks = new ArrayList<>();
    final Map<String, String> blockOf = new HashMap<>();
    final int most = JACCARD_N.stream().mapToInt(Integer::intValue).max().orElseThrow();
    final List<Frozen> frozen = new ArrayList<>();

    for (final Variant v : Variant.values()) {
      final List<Issue> fitting = issues.stream().filter(v::fitting)
          .sorted(Comparator.comparingLong(Issue::start).thenComparing(Issue::id)).toList();
      final List<List<Issue>> split = blocks(fitting);
      for (int b = 0; b < split.size(); b++) {
        final List<Issue> block = split.get(b);
        final long begins = block.get(0).start();
        final List<Issue> history = fitting.stream()
            .filter(h -> h.counted() && h.start() < begins && h.resolved() < begins).toList();
        final Index index = new Index(history, tokens);
        final Space space = new Space(history, vectors);
        blocks.add(new Block(v, b + 1, block.size(), begins, block.get(block.size() - 1).start(),
            history.size()));
        frozen.add(new Frozen(v, Integer.toString(b + 1), begins, history, block));
        final List<Space.Found> found = space.search(block, mostVectors);
        for (int k = 0; k < block.size(); k++) {
          final Issue q = block.get(k);
          blockOf.put(q.id() + v.suffix, Integer.toString(b + 1));
          queries.add(answer(q, v, Integer.toString(b + 1), begins, index, tokens, most, columns));
          embeddingQueries.add(nearest(q, v, Integer.toString(b + 1), begins, found.get(k),
              columns));
        }
      }
      final List<Issue> counted = fitting.stream().filter(Issue::counted).toList();
      final Index whole = new Index(counted, tokens);
      final Space wholeSpace = new Space(counted, vectors);
      final Issue latest =
          fitting.stream().max(Comparator.comparingLong(Issue::resolved)).orElse(null);
      final List<Issue> scored = issues.stream().filter(v::scored).toList();
      for (final Issue q : scored) {
        if (latest != null && latest.resolved() >= q.start()) {
          throw new IllegalArgumentException(latest.id() + " in the fitting set was resolved at "
              + "or after " + q.id() + " in the scored set started: the split is inconsistent");
        }
      }
      frozen.add(new Frozen(v, WHOLE, Long.MIN_VALUE, counted, scored));
      final List<Space.Found> found = wholeSpace.search(scored, mostVectors);
      for (int k = 0; k < scored.size(); k++) {
        final Issue q = scored.get(k);
        blockOf.put(q.id() + v.suffix, WHOLE);
        queries.add(answer(q, v, WHOLE, q.start(), whole, tokens, most, columns));
        embeddingQueries.add(nearest(q, v, WHOLE, q.start(), found.get(k), columns));
      }
      reporters(issues, counted, v, columns);
    }
    final List<ClusterFit> clusterFits = new ArrayList<>();
    final List<ClusterQuery> clusterQueries = new ArrayList<>();
    clusters(frozen, vectors, clusterer, columns, clusterFits, clusterQueries);
    return new Result(columns, queries, blocks, coverage(issues, columns, blockOf),
        embeddingQueries, List.copyOf(vectors.embedded.values()), clusterFits, clusterQueries);
  }

  /**
   * One history and the rows that read it.
   *
   * @param reference the block's first instant; unread for the whole set, whose rows are each
   *     at their own start
   */
  private record Frozen(Variant variant, String block, long reference, List<Issue> history,
      List<Issue> rows) {}

  /**
   * Fits every history for every k, all fits on a pool of threads of their own, then assigns each
   * history's members and each row to the nearest centre.
   */
  private static void clusters(final List<Frozen> frozen, final Vectors vectors,
      final Clusterer clusterer, final Map<String, Map<String, Double>> columns,
      final List<ClusterFit> fits, final List<ClusterQuery> queries) {
    final List<List<Issue>> members = new ArrayList<>();
    final List<double[][]> data = new ArrayList<>();
    final List<Callable<Clusterer.Fit>> jobs = new ArrayList<>();
    for (final Frozen f : frozen) {
      final List<Issue> kept =
          f.history().stream().filter(h -> vectors.row(h.id()) >= 0).toList();
      final double[][] x = new double[kept.size()][];
      for (int j = 0; j < x.length; j++) {
        x[j] = vectors.vector(vectors.row(kept.get(j).id()));
      }
      members.add(kept);
      data.add(x);
      for (final int k : CLUSTER_K) {
        jobs.add(() -> clusterer.fit(x, k, CLUSTER_SEED));
      }
    }
    final List<Clusterer.Fit> fitted = fitAll(jobs);
    for (int h = 0; h < frozen.size(); h++) {
      final Frozen f = frozen.get(h);
      final List<Issue> kept = members.get(h);
      final double[][] x = data.get(h);
      final int distinct = Clusterer.distinct(x);
      for (int c = 0; c < CLUSTER_K.size(); c++) {
        final int k = CLUSTER_K.get(c);
        final Clusterer.Fit fit = fitted.get(h * CLUSTER_K.size() + c);
        final List<List<Issue>> groups = new ArrayList<>();
        if (fit.fitted()) {
          final int[] nearest = IntStream.range(0, x.length).parallel()
              .map(j -> fit.assign(x[j]).cluster()).toArray();
          for (int g = 0; g < k; g++) {
            groups.add(new ArrayList<>());
          }
          for (int j = 0; j < x.length; j++) {
            groups.get(nearest[j]).add(kept.get(j));
          }
        }
        fits.add(new ClusterFit(f.variant(), f.block(), k, x.length, distinct, fit.fitted(),
            fit.distortion(), groups.stream().map(g -> g.stream().map(Issue::id).toList())
                .toList()));
        for (final Issue q : f.rows()) {
          queries.add(assign(q, f, k, fit, groups, vectors, columns));
        }
      }
    }
  }

  /** The fits of {@code jobs}, in their order, each on a thread of its own pool. */
  private static List<Clusterer.Fit> fitAll(final List<Callable<Clusterer.Fit>> jobs) {
    final int threads = Math.max(1, Math.min(jobs.size(),
        Runtime.getRuntime().availableProcessors()));
    final ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
      final Thread t = new Thread(r, "k-means");
      t.setDaemon(true);
      return t;
    });
    try {
      final List<Clusterer.Fit> out = new ArrayList<>(jobs.size());
      for (final Future<Clusterer.Fit> f : pool.invokeAll(jobs)) {
        out.add(f.get());
      }
      return out;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while fitting the clusters", e);
    } catch (final ExecutionException e) {
      if (e.getCause() instanceof RuntimeException r) {
        throw r;
      }
      if (e.getCause() instanceof Error r) {
        throw r;
      }
      throw new IllegalStateException("a cluster fit failed", e.getCause());
    } finally {
      pool.shutdownNow();
    }
  }

  private static ClusterQuery assign(final Issue q, final Frozen f, final int k,
      final Clusterer.Fit fit, final List<List<Issue>> groups, final Vectors vectors,
      final Map<String, Map<String, Double>> columns) {
    final long reference = f.block().equals(WHOLE) ? q.start() : f.reference();
    final Variant v = f.variant();
    final int row = vectors.row(q.id());
    if (row < 0 || !fit.fitted()) {
      return new ClusterQuery(q.id(), v, v.split(q), f.block(), reference, k, -1, Double.NaN, 0);
    }
    final Clusterer.Assignment a = fit.assign(vectors.vector(row));
    final List<Double> days = groups.get(a.cluster()).stream()
        .filter(m -> !m.id().equals(q.id())).map(Issue::days).toList();
    if (!days.isEmpty()) {
      columns.get(q.id()).put(cluster(k, v), median(days));
    }
    return new ClusterQuery(q.id(), v, v.split(q), f.block(), reference, k, a.cluster(),
        a.distance(), days.size());
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
    final List<Double> days = new ArrayList<>();
    for (final Index.Hit h : hits.subList(0, Math.min(most, hits.size()))) {
      nearest.add(new Neighbour(h.issue().id(), 1.0 - h.shared() / (double) h.union()));
      days.add(h.issue().days());
    }
    if (!days.isEmpty()) {
      for (final int n : JACCARD_N) {
        columns.get(q.id()).put(jaccard(n, v), median(days.subList(0, Math.min(n, days.size()))));
      }
    }
    return new Query(q.id(), v, v.split(q), block, reference, mine.length, hits.size(), nearest);
  }

  private static EmbeddingQuery nearest(final Issue q, final Variant v, final String block,
      final long reference, final Space.Found found,
      final Map<String, Map<String, Double>> columns) {
    final List<Neighbour> nearest = new ArrayList<>();
    final List<Double> days = new ArrayList<>();
    for (final Space.Hit h : found.nearest()) {
      nearest.add(new Neighbour(h.issue().id(), 1.0 - h.similarity()));
      days.add(h.issue().days());
    }
    if (!days.isEmpty()) {
      for (final int n : EMBEDDING_N) {
        columns.get(q.id()).put(embedding(n, v),
            median(days.subList(0, Math.min(n, days.size()))));
      }
    }
    return new EmbeddingQuery(q.id(), v, v.split(q), block, reference, found.candidates(),
        nearest);
  }

  /** Each row's reporter median against the fitting set's counted issues, issue by issue. */
  private static void reporters(final List<Issue> issues, final List<Issue> fitting,
      final Variant v, final Map<String, Map<String, Double>> columns) {
    final Map<Who, List<Issue>> by = new HashMap<>();
    for (final Issue h : fitting) {
      if (h.reporter() != null) {
        by.computeIfAbsent(new Who(h.project(), h.reporter()), k -> new ArrayList<>()).add(h);
      }
    }
    for (final Issue q : issues) {
      if (q.reporter() == null || !(v.fitting(q) || v.scored(q))) {
        continue;
      }
      final List<Double> days = by.getOrDefault(new Who(q.project(), q.reporter()), List.of())
          .stream().filter(h -> !h.id().equals(q.id()) && h.resolved() < q.start())
          .map(Issue::days).toList();
      if (!days.isEmpty()) {
        columns.get(q.id()).put(reporter(v), median(days));
      }
    }
  }

  private record Who(String project, String reporter) {}

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

  /**
   * Every described issue's vector, one row of a flat array each, embedded once for the whole
   * run, every description handed to the embedder in one list in the rows' order ({@link
   * Embedder#embedAll}); an issue with no description, or whose vector is refused, has no row.
   * A vector is refused unless it has {@code dimension} finite components and length one within
   * {@link #UNIT_TOLERANCE}; it is used as given, never rescaled. An issue id given twice keeps its
   * last row's description, as the Jaccard tokens do.
   */
  private static final class Vectors {

    private final Map<String, Integer> rows = new HashMap<>();
    private final Map<String, Embedded> embedded = new LinkedHashMap<>();
    private final int dimension;
    private final float[] values;
    private final double[][] doubles;

    Vectors(final List<Issue> issues, final Embedder embedder, final int dimension) {
      this.dimension = dimension;
      final List<String> texts = new ArrayList<>();
      for (final Issue i : issues) {
        if (described(i)) {
          texts.add(i.description());
        }
      }
      final List<Embedder.Embedding> all = embedder.embedAll(texts);
      if (all.size() != texts.size()) {
        throw new IllegalStateException("the embedder gave " + all.size() + " embeddings for "
            + texts.size() + " descriptions");
      }
      final Iterator<Embedder.Embedding> embeddings = all.iterator();
      final Map<String, float[]> byId = new LinkedHashMap<>();
      for (final Issue i : issues) {
        byId.remove(i.id());
        embedded.remove(i.id());
        if (!described(i)) {
          continue;
        }
        final Embedder.Embedding e = embeddings.next();
        final float[] v = e == null ? null : e.vector();
        final String problem = problem(v, dimension);
        embedded.put(i.id(), new Embedded(i.id(), e == null ? 0 : e.chunks(), problem));
        if (problem == null) {
          byId.put(i.id(), v);
        }
      }
      values = new float[byId.size() * dimension];
      int r = 0;
      for (final Map.Entry<String, float[]> e : byId.entrySet()) {
        final float[] v = e.getValue();
        System.arraycopy(v, 0, values, r * dimension, dimension);
        rows.put(e.getKey(), r++);
      }
      doubles = new double[byId.size()][];
    }

    private static boolean described(final Issue i) {
      return i.description() != null && !i.description().isBlank();
    }

    /** Why {@code v} is not a unit vector of {@code dimension} components, null when it is. */
    static String problem(final float[] v, final int dimension) {
      if (v == null) {
        return "no vector";
      }
      if (v.length != dimension) {
        return v.length + " components, expected " + dimension;
      }
      double sum = 0;
      for (final float x : v) {
        if (!Float.isFinite(x)) {
          return "a component is not finite";
        }
        sum += (double) x * x;
      }
      final double length = Math.sqrt(sum);
      return Math.abs(length - 1) <= UNIT_TOLERANCE ? null : "length " + length + ", not one";
    }

    /**
     * A row's vector, its components exact in double: made once and shared by every history that
     * holds the row, so it is read and never changed.
     */
    double[] vector(final int row) {
      if (doubles[row] == null) {
        final double[] out = new double[dimension];
        for (int d = 0; d < dimension; d++) {
          out[d] = values[row * dimension + d];
        }
        doubles[row] = out;
      }
      return doubles[row];
    }

    /** The issue's row, or -1 when it has no vector. */
    int row(final String id) {
      return rows.getOrDefault(id, -1);
    }

    /**
     * The dot product of two rows, each product exact in double and summed in a fixed order, so a
     * rerun gives the same bits.
     */
    double dot(final int a, final int b) {
      final int x = a * dimension;
      final int y = b * dimension;
      double s0 = 0;
      double s1 = 0;
      double s2 = 0;
      double s3 = 0;
      int d = 0;
      for (; d + 3 < dimension; d += 4) {
        s0 += (double) values[x + d] * values[y + d];
        s1 += (double) values[x + d + 1] * values[y + d + 1];
        s2 += (double) values[x + d + 2] * values[y + d + 2];
        s3 += (double) values[x + d + 3] * values[y + d + 3];
      }
      for (; d < dimension; d++) {
        s0 += (double) values[x + d] * values[y + d];
      }
      return (s0 + s1) + (s2 + s3);
    }
  }

  /** A frozen history's vectors, searched by a scan of dot products. */
  private static final class Space {

    record Hit(Issue issue, double similarity) {}

    record Found(int candidates, List<Hit> nearest) {}

    /** How many queries read one tile of members together. */
    private static final int GROUP = 64;

    /** How many members one tile holds: 256 of 384 floats, near 400 KB. */
    private static final int TILE = 256;

    private final List<Issue> members = new ArrayList<>();
    private final int[] rows;
    private final Vectors vectors;

    Space(final List<Issue> history, final Vectors vectors) {
      this.vectors = vectors;
      final List<Integer> kept = new ArrayList<>();
      for (final Issue h : history) {
        final int r = vectors.row(h.id());
        if (r >= 0) {
          members.add(h);
          kept.add(r);
        }
      }
      rows = kept.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * For each query, the members at positive similarity to it, the clamped dot product, counted,
     * and the closest {@code most} of them, ties by id; never the query itself. A query with no
     * vector finds nothing. The members are scanned in tiles each read by a group of queries, the
     * groups in parallel; every query still visits the members in their order, so the answer is
     * the one a plain scan gives.
     */
    List<Found> search(final List<Issue> queries, final int most) {
      final Found[] out = new Found[queries.size()];
      final int groups = (queries.size() + GROUP - 1) / GROUP;
      IntStream.range(0, groups).parallel().forEach(g -> {
        final int from = g * GROUP;
        final int to = Math.min(queries.size(), from + GROUP);
        final int[] row = new int[to - from];
        final Hit[][] best = new Hit[to - from][most];
        final int[] size = new int[to - from];
        final int[] candidates = new int[to - from];
        for (int q = from; q < to; q++) {
          row[q - from] = vectors.row(queries.get(q).id());
        }
        for (int tile = 0; tile < rows.length; tile += TILE) {
          final int last = Math.min(rows.length, tile + TILE);
          for (int q = 0; q < row.length; q++) {
            if (row[q] < 0) {
              continue;
            }
            for (int j = tile; j < last; j++) {
              // one row per id, so the query's own row is the issue itself
              if (rows[j] == row[q]) {
                continue;
              }
              final double s = Math.max(-1.0, Math.min(1.0, vectors.dot(row[q], rows[j])));
              if (!(s > 0)) {
                continue;
              }
              candidates[q]++;
              final Hit[] mine = best[q];
              if (size[q] < most || closer(s, members.get(j), mine[most - 1])) {
                size[q] = offer(mine, size[q], new Hit(members.get(j), s));
              }
            }
          }
        }
        for (int q = from; q < to; q++) {
          out[q] = new Found(candidates[q - from],
              List.of(Arrays.copyOf(best[q - from], size[q - from])));
        }
      });
      return List.of(out);
    }

    /** Keeps {@code hit} among the closest in {@code best}, closest first; the new size. */
    private static int offer(final Hit[] best, final int size, final Hit hit) {
      final int most = best.length;
      if (size == most && !closer(hit, best[most - 1])) {
        return size;
      }
      int k = size == most ? most - 1 : size;
      while (k > 0 && closer(hit, best[k - 1])) {
        best[k] = best[k - 1];
        k--;
      }
      best[k] = hit;
      return size == most ? size : size + 1;
    }

    private static boolean closer(final Hit a, final Hit b) {
      return closer(a.similarity(), a.issue(), b);
    }

    private static boolean closer(final double similarity, final Issue issue, final Hit b) {
      final int c = Double.compare(b.similarity(), similarity);
      return c != 0 ? c < 0 : issue.id().compareTo(b.issue().id()) < 0;
    }
  }
}
