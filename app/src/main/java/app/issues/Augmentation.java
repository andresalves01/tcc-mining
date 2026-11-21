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
 * <p><b>Duration.</b> Every median reads the status-adjusted business days of past issues:
 * time back in Open or Reopened is excluded, days without records are retained. Each past
 * issue uses its own day boundary. The same duration is used in both split variants. A past
 * issue of 0 days enters no history; since ticket 21 the extraction draws none of them.
 *
 * <p><b>History.</b> The history of a level is its training side that teaches: the inner fitting
 * set for the inner variant, the outer training set for the outer. Each variant has one history
 * and one neighbour index, read alike by the rows of its training side and by its scored rows
 * (inner validation; outer test), each row admitting only its point-in-time part of it. No label
 * of the other side ever enters -- no validation label in the inner variant, no test label in the
 * outer -- and no row ever reads its own: the neighbours, the cluster's members and the reporter
 * median leave the query out by id. An issue crossing a level's cut ({@link Verdict#CROSSING}) is
 * in no history of that level and reads no column there unless the level scores it, nor enters
 * any cluster fit.
 *
 * <p><b>Point-in-time history</b> (the revised temporal design, {@link Histories#POINT_IN_TIME}).
 * For each query, a member counts only when it teaches at the level, its first resolution comes
 * strictly before the query's start -- the first commit -- and it is not the query: only labels
 * already known when its work began. The filter applies before anything is chosen: the closest n
 * Jaccard and embedding neighbours are the closest of the admitted members, the reporter median
 * reads the admitted issues, and the cluster centres are fitted on the admitted members' vectors
 * alone, so no issue that had not ended when the query's work began shapes its group. A query
 * with no admitted member has a missing value. Under this design the inner level also answers the
 * outer test (ticket 06): each outer test row reads the inner fitting history, point in time; its
 * inner split is outer_test, and it never enters a history or a fit, so the fitting and
 * validation rows read the same with or without it.
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
 * embedding columns and is no candidate for anyone. The history is Jaccard's. The similarity is
 * the dot product of the two unit vectors clamped to [-1, 1], the distance one minus it; the
 * candidates are the history issues at positive similarity, and the closest n give the median as
 * for Jaccard, ties by id, all of them when fewer than n. The value is missing when the issue has
 * no description, its vector was refused, or no candidate exists. The search is a scan of every
 * history vector; the dot product is summed in a fixed order, so a rerun over the same vectors
 * gives the same neighbours, bit for bit.
 *
 * <p><b>Reporter.</b> The unweighted median business days of the same canonical reporter's
 * other issues in the same project, from the matching history, whatever their dates; missing when
 * there is none. Every row is Fixed, the population the extraction reads. It corrects the
 * extractor's {@code D05_reporter_project_median_h}, which measures from creation over every issue
 * of the reporter in the project resolved before t0, to the target's interval and the matching
 * split.
 *
 * <p><b>Clusters.</b> For each candidate k and each query, the {@link Clusterer} is fitted on the
 * vectors of the members the query's history admits, the members the embedding neighbours are
 * chosen from, and on those alone, never on durations, with seed {@link #CLUSTER_SEED}. The
 * admitted members are a prefix of the history ordered by first resolution, so queries admitting
 * the same prefix read one fit, made once: under the revised design every scored row starts after
 * every member of its history has ended and reads the widest fit, and each training row one of
 * its own. A prefix is fitted in order of first commit, ties by id. Each of its members with a
 * valid vector is assigned to its nearest centre by Euclidean distance, the rule the query is
 * assigned by, so a cluster's members are the members nearest to its centre. The value is the
 * unweighted median duration of the members of the query's cluster, the query itself never among
 * them; the centre only picks the group. It is missing when the query has no valid vector, when
 * its admitted members have fewer distinct vectors than k -- k never shrunk -- or when the cluster
 * has no member but the query. The fits run on a pool of threads of their own; each depends on
 * its vectors, k and seed alone.
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

  /**
   * How far a vector's length may be from one and still be read as a unit vector. The model's
   * vectors are scaled to length one in double precision and stored as floats, which leaves an
   * error near 1e-7; this bound is three orders of magnitude above it, and the same 1e-4 the
   * embedder's reference test allows per component.
   */
  public static final double UNIT_TOLERANCE = 1e-4;

  /** Which members a history admits for a query, and which rows answer each level. */
  public enum Histories {
    /**
     * A point-in-time history: for a query, only the members that teach whose first resolution
     * comes strictly before the query's start (its first commit), never the query; the cluster
     * centres it reads are fitted on those members alone. The inner level also answers the outer
     * test (ticket 06), from the inner fitting history alone; no outer test row ever enters a
     * history or a fit. The revised temporal design's.
     */
    POINT_IN_TIME;

    /** Whether {@code member}, a member that teaches, may enter {@code query}'s history. */
    boolean admits(final Issue query, final Issue member) {
      return admits(member.resolved(), query.start());
    }

    /**
     * The point-in-time rule itself: a member resolved strictly before the query's start. The one
     * place it is written, read by {@link #admits(Issue, Issue)} and by the embedding search.
     */
    static boolean admits(final long resolved, final long start) {
      return resolved < start;
    }
  }

  /** Which split a column is computed against; its name ends in the suffix. */
  public enum Variant {
    INNER("_inner"), OUTER("_outer");

    private final String suffix;

    Variant(final String suffix) {
      this.suffix = suffix;
    }

    /** What the name of a column computed against this split ends in. */
    public String suffix() {
      return suffix;
    }

    /**
     * The rows whose outcomes may be history, those of the training side that teach ({@link
     * Verdict#teaches}): the inner fitting set, or the outer training, never an issue crossing
     * this level's cut.
     */
    public boolean fitting(final Issue i) {
      return fitting(i.outer(), i.inner());
    }

    /** The same, by an issue's outer and inner verdicts. */
    public boolean fitting(final String outer, final String inner) {
      return Verdict.teaches(this, outer, inner);
    }

    /** The rows scored against the training side: inner validation, or outer test. */
    public boolean scored(final Issue i) {
      return scored(i.outer(), i.inner());
    }

    /** The same, by an issue's outer and inner verdicts. */
    public boolean scored(final String outer, final String inner) {
      return Verdict.scored(this, outer, inner);
    }

    /**
     * The rows that read this level under {@code design}: its training side that teaches, its
     * scored rows, and under a point-in-time history the outer test at the inner level too.
     */
    public boolean answers(final Issue i, final Histories design) {
      return fitting(i) || scored(i) || outerTestAtInner(i, design);
    }

    /**
     * The row's split at this level under {@code design}: inner_fitting, inner_validation,
     * outer_training or outer_test, null when it does not read this level; an outer test row read
     * at the inner level is outer_test there too.
     */
    public String split(final Issue i, final Histories design) {
      return fitting(i) ? (this == INNER ? "inner_fitting" : "outer_training")
          : scored(i) ? (this == INNER ? "inner_validation" : "outer_test")
          : outerTestAtInner(i, design) ? "outer_test" : null;
    }

    private boolean outerTestAtInner(final Issue i, final Histories design) {
      return this == INNER && OUTER.scored(i.outer(), i.inner());
    }
  }

  /**
   * One extracted issue.
   *
   * @param reporter the canonical person who opened it
   * @param start the start, epoch milliseconds: the first In Progress, or the first commit in the
   *     first-commit study
   * @param resolved the first resolution, epoch milliseconds
   * @param innerDays its status-adjusted business days at its own day boundary; what the inner
   *     history reads, with 0 excluded
   * @param outerDays the same duration read by the outer variant
   * @param outer the outer verdict: TRAIN, TEST or CROSSING ({@link Verdict})
   * @param inner the inner verdict inside the outer training, TRAIN, TEST or CROSSING; empty
   *     outside it
   * @param description the cleaned description at the first In Progress, null when there is none
   */
  public record Issue(String id, String project, String reporter, long start, long resolved,
      int innerDays, int outerDays, String outer, String inner, String description) {

    /** Whether it may be history of {@code v}: it lasted at least one business day there. */
    boolean counted(final Variant v) {
      return days(v) >= 1;
    }

    /** What a median of {@code v} reads of it. */
    double days(final Variant v) {
      return v == Variant.INNER ? innerDays : outerDays;
    }
  }

  public record Neighbour(String id, double distance) {}

  /**
   * One row's search against one variant's history.
   *
   * @param split inner_fitting, inner_validation, outer_training or outer_test
   * @param tokens the query's distinct tokens
   * @param candidates the history issues admitted for the query sharing at least one token, the
   *     query left out
   * @param nearest the closest candidates, up to the largest n, closest first
   */
  public record Query(String id, Variant variant, String split, int tokens, int candidates,
      List<Neighbour> nearest) {

    /** How many neighbours the median with {@code n} read. */
    public int used(final int n) {
      return Math.min(n, nearest.size());
    }
  }

  /**
   * One variant's history.
   *
   * @param rows the rows that read it: its training side and its scored rows, and under a
   *     point-in-time history the outer test at the inner level
   * @param history the training side's issues of at least one business day
   */
  public record History(Variant variant, int rows, int history) {}

  /** Rows and observed values of one column within one group. */
  public record Coverage(String column, String by, String group, int rows, int observed) {}

  /**
   * One row's embedding search against one variant's history, as {@link Query} is for Jaccard.
   *
   * @param candidates the history issues admitted for the query with a valid vector at positive
   *     similarity to the query
   * @param nearest the closest candidates, up to the largest n, closest first
   */
  public record EmbeddingQuery(String id, Variant variant, String split, int candidates,
      List<Neighbour> nearest) {

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
   * One K-means fit of a point-in-time history of one variant: the members resolved before the
   * first commit of the rows that read it.
   *
   * @param vectors the admitted members with a valid vector, the vectors fitted
   * @param distinct how many of those vectors differ, as {@link Clusterer#distinct} counts
   * @param distortion the clusterer's distortion of the fit, NaN when not fitted
   * @param rows how many rows read this fit
   * @param members per cluster, the ids of the members nearest its centre, in history order, for
   *     the variant's widest fit alone, the one its scored rows read; empty for every other fit
   *     and when not fitted
   */
  public record ClusterFit(Variant variant, int k, int vectors, int distinct, boolean fitted,
      double distortion, int rows, List<List<String>> members) {}

  /**
   * One row's cluster for one k against one variant's history.
   *
   * @param cluster the nearest centre's index, -1 when the query has no valid vector or the
   *     history was not fitted
   * @param distance the query's Euclidean distance to that centre, NaN without a cluster
   * @param members how many members the median read, the query itself left out
   * @param history how many vectors the query's fit was fitted on, the members its history admits
   */
  public record ClusterQuery(String id, Variant variant, String split, int k, int cluster,
      double distance, int members, int history) {}

  /**
   * @param columns per issue id, every new column, absent when missing; every issue is a key
   * @param histories each variant's history, inner then outer
   * @param embeddingQueries every row's embedding search, as {@code queries} for Jaccard
   * @param embedded every issue with a description, in the order of the rows
   * @param clusterFits each variant's fit for every k, inner then outer
   * @param clusterQueries every row's cluster for every k, in the order of the fits
   */
  public record Result(Map<String, Map<String, Double>> columns, List<Query> queries,
      List<History> histories, List<Coverage> coverage, List<EmbeddingQuery> embeddingQueries,
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
   * vectors of {@code dimension} components, the clusters fitted by {@code clusterer}; every
   * history admitting its members as {@code design} says.
   */
  public static Result run(final List<Issue> issues, final Embedder embedder,
      final int dimension, final Clusterer clusterer, final Histories design) {
    final Tokens tokens = new Tokens(issues);
    final Vectors vectors = new Vectors(issues, embedder, dimension);
    final List<EmbeddingQuery> embeddingQueries = new ArrayList<>();
    final int mostVectors = EMBEDDING_N.stream().mapToInt(Integer::intValue).max().orElseThrow();
    final Map<String, Map<String, Double>> columns = new LinkedHashMap<>();
    issues.forEach(i -> columns.put(i.id(), new HashMap<>()));
    final List<Query> queries = new ArrayList<>();
    final List<History> histories = new ArrayList<>();
    final int most = JACCARD_N.stream().mapToInt(Integer::intValue).max().orElseThrow();
    final List<Frozen> frozen = new ArrayList<>();

    for (final Variant v : Variant.values()) {
      // the training side, in order of first In Progress, then its scored rows
      final List<Issue> fitting = issues.stream().filter(v::fitting)
          .sorted(Comparator.comparingLong(Issue::start).thenComparing(Issue::id)).toList();
      final List<Issue> rows = new ArrayList<>(fitting);
      issues.stream().filter(v::scored).forEach(rows::add);
      // the outer test at the inner level under a point-in-time history, after every other row
      issues.stream().filter(i -> !v.fitting(i) && !v.scored(i) && v.answers(i, design))
          .forEach(rows::add);
      final List<Issue> history = fitting.stream().filter(i -> i.counted(v)).toList();
      final Index index = new Index(history, tokens);
      final Space space = new Space(history, vectors);
      histories.add(new History(v, rows.size(), history.size()));
      frozen.add(new Frozen(v, history, rows));
      final List<Space.Found> found = space.search(rows, mostVectors, design);
      for (int k = 0; k < rows.size(); k++) {
        final Issue q = rows.get(k);
        queries.add(answer(q, v, design, index, tokens, most, columns));
        embeddingQueries.add(nearest(q, v, design, found.get(k), columns));
      }
      reporters(issues, history, v, design, columns);
    }
    final List<ClusterFit> clusterFits = new ArrayList<>();
    final List<ClusterQuery> clusterQueries = new ArrayList<>();
    clusters(frozen, vectors, clusterer, design, columns, clusterFits, clusterQueries);
    return new Result(columns, queries, List.copyOf(histories), coverage(issues, design, columns),
        embeddingQueries, List.copyOf(vectors.embedded.values()), clusterFits, clusterQueries);
  }

  /**
   * One variant's history and the rows that read it: its training side that teaches, then its
   * scored rows, then the outer test it answers.
   */
  private record Frozen(Variant variant, List<Issue> history, List<Issue> rows) {}

  /**
   * Every row's cluster for every k, point in time: for each row the centres are fitted on the
   * vectors of the members its history admits, those resolved strictly before its first commit,
   * and on those alone. The admitted members are a prefix of the history ordered by first
   * resolution, so the rows that admit the same prefix read the same fit, made once. Each fit runs
   * on a pool of threads of its own; the columns are written here, in the rows' order.
   */
  private static void clusters(final List<Frozen> frozen, final Vectors vectors,
      final Clusterer clusterer, final Histories design,
      final Map<String, Map<String, Double>> columns, final List<ClusterFit> fits,
      final List<ClusterQuery> queries) {
    final List<Callable<Answered>> jobs = new ArrayList<>();
    for (final Frozen f : frozen) {
      // the history members with a valid vector, by first resolution then id
      final List<Issue> byResolution = f.history().stream()
          .filter(h -> vectors.row(h.id()) >= 0)
          .sorted(Comparator.comparingLong(Issue::resolved).thenComparing(Issue::id)).toList();
      final long[] resolved = byResolution.stream().mapToLong(Issue::resolved).toArray();
      // read here, on one thread: the vectors are materialised lazily
      final Map<String, double[]> of = new HashMap<>();
      byResolution.forEach(h -> of.put(h.id(), vectors.vector(vectors.row(h.id()))));
      final Map<String, double[]> own = new HashMap<>();
      for (final Issue q : f.rows()) {
        final int row = vectors.row(q.id());
        if (row >= 0) {
          own.put(q.id(), vectors.vector(row));
        }
      }
      final TreeMap<Integer, List<Issue>> readers = new TreeMap<>();
      for (final Issue q : f.rows()) {
        readers.computeIfAbsent(admitted(resolved, q.start()), n -> new ArrayList<>()).add(q);
      }
      final int widest = readers.isEmpty() ? 0 : readers.lastKey();
      for (final int k : CLUSTER_K) {
        for (final Map.Entry<Integer, List<Issue>> e : readers.entrySet()) {
          final List<Issue> prefix = byResolution.subList(0, e.getKey());
          final List<Issue> rows = e.getValue();
          final boolean keep = e.getKey() == widest;
          jobs.add(() -> fit(f.variant(), k, prefix, rows, keep, of, own, clusterer, design));
        }
      }
    }
    final Map<String, ClusterQuery> answered = new HashMap<>();
    final Map<String, Double> values = new HashMap<>();
    for (final Answered a : runAll(jobs)) {
      fits.add(a.fit());
      a.queries().forEach(q -> answered.put(q.id() + "\t" + q.variant() + "\t" + q.k(), q));
      values.putAll(a.values());
    }
    for (final Frozen f : frozen) {
      for (final int k : CLUSTER_K) {
        for (final Issue q : f.rows()) {
          final String at = q.id() + "\t" + f.variant() + "\t" + k;
          queries.add(answered.get(at));
          final Double value = values.get(at);
          if (value != null) {
            columns.get(q.id()).put(cluster(k, f.variant()), value);
          }
        }
      }
    }
  }

  /**
   * How many members of a history ordered by first resolution, {@code resolved}, a row starting
   * at {@code start} admits: those before the first the point-in-time rule refuses.
   */
  static int admitted(final long[] resolved, final long start) {
    int low = 0;
    int high = resolved.length;
    while (low < high) {
      final int mid = (low + high) >>> 1;
      if (Histories.admits(resolved[mid], start)) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  /** One fit, the rows that read it, and their columns, keyed by id, variant and k. */
  private record Answered(ClusterFit fit, List<ClusterQuery> queries, Map<String, Double> values) {}

  /**
   * The fit of {@code prefix}'s vectors for {@code k}, in order of first commit, ties by id, as
   * every history is fitted; each member assigned to its nearest centre; and each of {@code rows}
   * assigned to its nearest centre, its value the median of the members of its cluster it admits.
   */
  private static Answered fit(final Variant v, final int k, final List<Issue> prefix,
      final List<Issue> rows, final boolean keep, final Map<String, double[]> of,
      final Map<String, double[]> own, final Clusterer clusterer, final Histories design) {
    final List<Issue> members = prefix.stream()
        .sorted(Comparator.comparingLong(Issue::start).thenComparing(Issue::id)).toList();
    final double[][] x = new double[members.size()][];
    for (int j = 0; j < x.length; j++) {
      x[j] = of.get(members.get(j).id());
    }
    final int distinct = Clusterer.distinct(x);
    final Clusterer.Fit fit = distinct < k ? Clusterer.Fit.notFitted(k)
        : clusterer.fit(x, k, CLUSTER_SEED);
    final List<List<Issue>> groups = new ArrayList<>();
    if (fit.fitted()) {
      for (int g = 0; g < k; g++) {
        groups.add(new ArrayList<>());
      }
      for (int j = 0; j < x.length; j++) {
        groups.get(fit.assign(x[j]).cluster()).add(members.get(j));
      }
    }
    final List<ClusterQuery> queries = new ArrayList<>(rows.size());
    final Map<String, Double> values = new HashMap<>();
    for (final Issue q : rows) {
      final String split = v.split(q, design);
      final double[] vector = own.get(q.id());
      if (vector == null || !fit.fitted()) {
        queries.add(new ClusterQuery(q.id(), v, split, k, -1, Double.NaN, 0, x.length));
        continue;
      }
      final Clusterer.Assignment a = fit.assign(vector);
      // the centre picks the group; its median reads the members the query's history admits
      final List<Double> days = groups.get(a.cluster()).stream()
          .filter(m -> !m.id().equals(q.id()) && design.admits(q, m)).map(m -> m.days(v))
          .toList();
      if (!days.isEmpty()) {
        values.put(q.id() + "\t" + v + "\t" + k, median(days));
      }
      queries.add(new ClusterQuery(q.id(), v, split, k, a.cluster(), a.distance(), days.size(),
          x.length));
    }
    final List<List<String>> kept = keep
        ? groups.stream().map(g -> g.stream().map(Issue::id).toList()).toList() : List.of();
    return new Answered(new ClusterFit(v, k, x.length, distinct, fit.fitted(), fit.distortion(),
        rows.size(), kept), queries, values);
  }

  /** The results of {@code jobs}, in their order, each on a thread of its own pool. */
  private static <T> List<T> runAll(final List<Callable<T>> jobs) {
    final int threads = Math.max(1, Math.min(jobs.size(),
        Runtime.getRuntime().availableProcessors()));
    final ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
      final Thread t = new Thread(r, "k-means");
      t.setDaemon(true);
      return t;
    });
    try {
      final List<T> out = new ArrayList<>(jobs.size());
      for (final Future<T> f : pool.invokeAll(jobs)) {
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

  private static Query answer(final Issue q, final Variant v, final Histories design,
      final Index index, final Tokens tokens, final int most,
      final Map<String, Map<String, Double>> columns) {
    final int[] mine = tokens.of(q.id());
    // the members the query's history admits, before the closest are chosen
    final List<Index.Hit> hits = index.search(q, mine, design);
    final List<Neighbour> nearest = new ArrayList<>();
    final List<Double> days = new ArrayList<>();
    for (final Index.Hit h : hits.subList(0, Math.min(most, hits.size()))) {
      nearest.add(new Neighbour(h.issue().id(), 1.0 - h.shared() / (double) h.union()));
      days.add(h.issue().days(v));
    }
    if (!days.isEmpty()) {
      for (final int n : JACCARD_N) {
        columns.get(q.id()).put(jaccard(n, v), median(days.subList(0, Math.min(n, days.size()))));
      }
    }
    return new Query(q.id(), v, v.split(q, design), mine.length, hits.size(), nearest);
  }

  private static EmbeddingQuery nearest(final Issue q, final Variant v, final Histories design,
      final Space.Found found, final Map<String, Map<String, Double>> columns) {
    final List<Neighbour> nearest = new ArrayList<>();
    final List<Double> days = new ArrayList<>();
    for (final Space.Hit h : found.nearest()) {
      nearest.add(new Neighbour(h.issue().id(), 1.0 - h.similarity()));
      days.add(h.issue().days(v));
    }
    if (!days.isEmpty()) {
      for (final int n : EMBEDDING_N) {
        columns.get(q.id()).put(embedding(n, v),
            median(days.subList(0, Math.min(n, days.size()))));
      }
    }
    return new EmbeddingQuery(q.id(), v, v.split(q, design), found.candidates(), nearest);
  }

  /**
   * Each row's reporter median against the members of the history {@code design} admits for it,
   * the row itself left out.
   */
  private static void reporters(final List<Issue> issues, final List<Issue> history,
      final Variant v, final Histories design, final Map<String, Map<String, Double>> columns) {
    final Map<Who, List<Issue>> by = new HashMap<>();
    for (final Issue h : history) {
      if (h.reporter() != null) {
        by.computeIfAbsent(new Who(h.project(), h.reporter()), k -> new ArrayList<>()).add(h);
      }
    }
    for (final Issue q : issues) {
      if (q.reporter() == null || !v.answers(q, design)) {
        continue;
      }
      final List<Double> days = by.getOrDefault(new Who(q.project(), q.reporter()), List.of())
          .stream().filter(h -> !h.id().equals(q.id()) && design.admits(q, h))
          .map(h -> h.days(v)).toList();
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

  private static List<Coverage> coverage(final List<Issue> issues, final Histories design,
      final Map<String, Map<String, Double>> columns) {
    final List<Coverage> out = new ArrayList<>();
    for (final String column : columns()) {
      final Variant v = column.endsWith(Variant.INNER.suffix) ? Variant.INNER : Variant.OUTER;
      final Map<String, int[]> split = new TreeMap<>();
      final Map<String, int[]> project = new TreeMap<>();
      for (final Issue i : issues) {
        final String part = v.split(i, design);
        if (part == null) {
          continue;
        }
        final int seen = columns.get(i.id()).containsKey(column) ? 1 : 0;
        for (final int[] c : List.of(split.computeIfAbsent(part, k -> new int[2]),
            project.computeIfAbsent(i.project(), k -> new int[2]))) {
          c[0]++;
          c[1] += seen;
        }
      }
      split.forEach((g, c) -> out.add(new Coverage(column, "split", g, c[0], c[1])));
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

    /**
     * The members sharing a token with query {@code q} that {@code design} admits for it, closest
     * first, ties by id; never the query.
     */
    List<Hit> search(final Issue q, final int[] query, final Histories design) {
      final String self = q.id();
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
        if (!members.get(j).id().equals(self) && design.admits(q, members.get(j))) {
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
    private final long[] resolved;
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
      resolved = members.stream().mapToLong(Issue::resolved).toArray();
    }

    /**
     * For each query, the members at positive similarity to it, the clamped dot product, counted,
     * and the closest {@code most} of them, ties by id; never the query itself. A query with no
     * vector finds nothing. The members are scanned in tiles each read by a group of queries, the
     * groups in parallel; every query still visits the members in their order, so the answer is
     * the one a plain scan gives.
     */
    List<Found> search(final List<Issue> queries, final int most, final Histories design) {
      final Found[] out = new Found[queries.size()];
      final int groups = (queries.size() + GROUP - 1) / GROUP;
      IntStream.range(0, groups).parallel().forEach(g -> {
        final int from = g * GROUP;
        final int to = Math.min(queries.size(), from + GROUP);
        final int[] row = new int[to - from];
        final long[] start = new long[to - from];
        final Hit[][] best = new Hit[to - from][most];
        final int[] size = new int[to - from];
        final int[] candidates = new int[to - from];
        for (int q = from; q < to; q++) {
          row[q - from] = vectors.row(queries.get(q).id());
          start[q - from] = queries.get(q).start();
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
              // a point-in-time history: only the members resolved before the query's start
              if (!Histories.admits(resolved[j], start[q])) {
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
