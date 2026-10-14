package app.pullrequests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The previous paper's columns computed twice: as they were registered, and as corrected on
 * 28/09/2026 so that nothing later than the opening enters them.
 *
 * <p>Pure. Both versions come from the same tables in the same pass, so they differ only by the rule
 * the registration of the correction ({@code REGISTRO-DA-CORRECAO.md}) says they differ by. The
 * registered version exists to be checked: it must reproduce the values the previous paper stored,
 * and if it does not, this is not the same computation and the corrected one is not run.
 */
public final class PriorStudyCorrection {

  static final long DAY = 86_400_000L;
  static final long WINDOW = 90L * DAY;
  static final long NONE = Long.MIN_VALUE;

  /** The marker for "never happened", for the adapter that fills the records. */
  public static final long NONE_VALUE = NONE;

  /** The columns this class recomputes, in the order the export carries them. */
  public static final List<String> COLUMNS = List.of("loc_tot_java", "extra_dev_commits",
      "extra_dev_tenure_days", "pr_familiarity", "pr_familiarity_share", "iss_dev", "iss_team",
      "mu_l", "s_l", "extra_team_size", "pr_project_wait", "dev_known_to_ledger");

  /**
   * One pull request of the corpus, merged or not.
   *
   * @param creatorCanonical the canonical person behind {@code creator}, or null when the identity
   *     does not resolve -- the same bridge the previous paper's team presence used
   * @param merged first merge instant, or {@link #NONE}
   * @param closed first close or merge instant, or {@link #NONE}
   * @param author the single author of its commits, or null when there are none or several
   * @param firstReview the earliest review, whenever it was, or {@link #NONE}
   * @param firstComment the earliest comment by someone who is not an author of its commits
   */
  public record Pr(String id, String project, String creator, String creatorCanonical, long opened,
      long merged, long closed, String author, long firstReview, long firstComment,
      List<String> paths) {}

  /** One commit of the contribution ledger. {@code visible} is when it reached the repository. */
  public record Commit(String canonical, String project, long authored, long visible,
      long javaLines, boolean bot) {}

  /** One unit to compute: a pull request of the population, with its person as the paper keyed it. */
  public record Unit(String id, String project, String canonical, long t0) {}

  /** Everything the computation reads, as a source hands it over. */
  public record Inputs(List<Pr> pullRequests, List<Commit> ledger, List<Unit> units,
      Map<String, long[]> unitInterval, int ledgerInMergedPr, int ledgerByCommitterDate,
      int ledgerByAuthoring) {}

  /** The two versions, per unit, by column. {@code null} is absence. */
  public record Result(Map<String, Map<String, Double>> registered,
      Map<String, Map<String, Double>> corrected) {}

  private final Map<String, Pr> prs = new HashMap<>();
  private final Map<String, List<Pr>> mergedByProject = new HashMap<>();
  private final Map<String, List<Pr>> allByProject = new HashMap<>();
  private final Map<String, List<Pr>> mergedByAuthorProject = new HashMap<>();
  private final Map<String, List<Pr>> allByCreatorProject = new HashMap<>();
  private final Map<String, List<Pr>> allByCanonicalProject = new HashMap<>();
  private final Map<String, List<Pr>> presenceByProject = new HashMap<>();
  private final Map<String, History> byPair = new HashMap<>();
  private final Map<String, List<long[]>> labelsByPair = new HashMap<>();

  /**
   * @param labels the previous paper's units as (canonical, project, created, merged), which its
   *     open-load columns were counted over
   */
  public PriorStudyCorrection(final List<Pr> pullRequests, final List<Commit> ledger,
      final List<Unit> labels, final Map<String, long[]> labelInterval) {

    for (final Pr pr : pullRequests) {
      prs.put(pr.id(), pr);
      add(allByProject, pr.project(), pr);
      add(allByCreatorProject, pr.creator() + "\u0000" + pr.project(), pr);
      if (pr.creatorCanonical() != null) {
        add(allByCanonicalProject, pr.creatorCanonical() + "\u0000" + pr.project(), pr);
        add(presenceByProject, pr.project(), pr);
      }
      if (pr.merged() != NONE) {
        add(mergedByProject, pr.project(), pr);
        if (pr.author() != null) {
          add(mergedByAuthorProject, pr.author() + "\u0000" + pr.project(), pr);
        }
      }
    }
    final Comparator<Pr> byOpened = Comparator.comparingLong(Pr::opened);
    for (final Map<String, List<Pr>> index : List.of(mergedByProject, allByProject,
        mergedByAuthorProject, allByCreatorProject, allByCanonicalProject, presenceByProject)) {
      index.values().forEach(l -> l.sort(byOpened));
    }
    final Map<String, List<Commit>> commits = new HashMap<>();
    for (final Commit c : ledger) {
      if (c.canonical() != null && c.project() != null) {
        add(commits, c.canonical() + "\u0000" + c.project(), c);
      }
    }
    commits.forEach((k, v) -> byPair.put(k, new History(v)));
    for (final Unit label : labels) {
      final long[] interval = labelInterval.get(label.id());
      labelsByPair.computeIfAbsent(label.canonical() + "\u0000" + label.project(),
          k -> new ArrayList<>()).add(interval);
    }
  }

  private static <T> void add(final Map<String, List<T>> map, final String key, final T value) {
    map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
  }

  public Result compute(final List<Unit> units) {
    final Map<String, Map<String, Double>> registered = new LinkedHashMap<>();
    final Map<String, Map<String, Double>> corrected = new LinkedHashMap<>();
    for (final Unit u : units) {
      final Pr self = prs.get(u.id());
      final Map<String, Double> r = new HashMap<>();
      final Map<String, Double> c = new HashMap<>();
      final String pair = u.canonical() + "\u0000" + u.project();
      final History own = byPair.get(pair);

      // -- historico individual ----------------------------------------------------------------
      final double[] byAuthoring = own == null ? null : own.byAuthoring(u.t0());
      final double[] byVisibility = own == null ? null : own.byVisibility(u.t0());
      putHistory(r, byAuthoring, own != null);
      putHistory(c, byVisibility, byVisibility != null && byVisibility[1] > 0);

      // -- equipe ativa: quem abriu pull request no projeto nos 90 dias (a mesma nas duas) --------
      final Set<String> team = new HashSet<>();
      for (final Pr p : presenceByProject.getOrDefault(u.project(), List.of())) {
        if (p.opened() >= u.t0() - WINDOW && p.opened() < u.t0()) {
          team.add(p.creatorCanonical());
        }
      }
      r.put("extra_team_size", (double) team.size());
      c.put("extra_team_size", (double) team.size());
      final double[] volA = new double[team.size()];
      final double[] volV = new double[team.size()];
      int k = 0;
      for (final String member : team) {
        final History h = byPair.get(member + "\u0000" + u.project());
        final double[] a = h == null ? null : h.byAuthoring(u.t0());
        final double[] v = h == null ? null : h.byVisibility(u.t0());
        volA[k] = a == null ? 0 : a[0];
        volV[k] = v == null ? 0 : v[0];
        k++;
      }
      putTeam(r, volA);
      putTeam(c, volV);

      // -- carga aberta ------------------------------------------------------------------------
      r.put("iss_dev", (double) openLabels(pair, u.t0()));
      int teamOpen = 0;
      for (final String member : team) {
        teamOpen += openLabels(member + "\u0000" + u.project(), u.t0());
      }
      r.put("iss_team", (double) teamOpen);
      c.put("iss_dev", self == null ? null : (double) openAt(
          allByCreatorProject.getOrDefault(self.creator() + "\u0000" + u.project(), List.of()),
          u.t0(), u.id()));
      int teamOpenNow = 0;
      for (final String member : team) {
        teamOpenNow += openAt(allByCanonicalProject.getOrDefault(member + "\u0000" + u.project(),
            List.of()), u.t0(), u.id());
      }
      c.put("iss_team", (double) teamOpenNow);

      // -- espera tipica do projeto ------------------------------------------------------------
      r.put("pr_project_wait", median(waits(mergedByProject.getOrDefault(u.project(), List.of()),
          u.t0(), false)));
      c.put("pr_project_wait", median(waits(allByProject.getOrDefault(u.project(), List.of()),
          u.t0(), true)));

      // -- familiaridade -----------------------------------------------------------------------
      if (self != null) {
        familiarity(r, self, u.t0(), false);
        familiarity(c, self, u.t0(), true);
      }
      registered.put(u.id(), r);
      corrected.put(u.id(), c);
    }
    return new Result(registered, corrected);
  }

  /** [0] java lines, [1] commits, [2] earliest authoring, or null with no commit counted. */
  private static void putHistory(final Map<String, Double> out, final double[] h,
      final boolean known) {
    out.put("dev_known_to_ledger", known ? 1.0 : 0.0);
    if (h == null || h[1] == 0) {
      out.put("loc_tot_java", 0.0);
      out.put("extra_dev_commits", 0.0);
      out.put("extra_dev_tenure_days", 0.0);
      return;
    }
    out.put("loc_tot_java", h[0]);
    out.put("extra_dev_commits", h[1]);
    out.put("extra_dev_tenure_days", h[3]);
  }

  private static void putTeam(final Map<String, Double> out, final double[] volumes) {
    if (volumes.length == 0) {
      out.put("mu_l", null);
      out.put("s_l", null);
      return;
    }
    double sum = 0;
    for (final double v : volumes) {
      sum += v;
    }
    final double mean = sum / volumes.length;
    double squares = 0;
    for (final double v : volumes) {
      squares += (v - mean) * (v - mean);
    }
    out.put("mu_l", mean);
    out.put("s_l", Math.sqrt(squares / volumes.length));
  }

  /** The previous paper's rule: labels created strictly before and merged strictly after. */
  private int openLabels(final String pair, final long t0) {
    int open = 0;
    for (final long[] interval : labelsByPair.getOrDefault(pair, List.of())) {
      if (interval[0] < t0 && interval[1] > t0) {
        open++;
      }
    }
    return open;
  }

  /** The corrected rule: any pull request opened before t0 and not closed or merged by then. */
  private static int openAt(final List<Pr> list, final long t0, final String self) {
    int open = 0;
    for (final Pr p : list) {
      if (p.opened() >= t0) {
        break;
      }
      if (!p.id().equals(self) && (p.closed() == NONE || p.closed() >= t0)) {
        open++;
      }
    }
    return open;
  }

  /**
   * Waits until the first examination, in days, of the project's pull requests opened in the 90
   * days before t0. Registered: over merged ones, examination inside [opened, merged], whenever that
   * was. Corrected: over all, examination inside [opened, t0) and not after the close.
   */
  private static List<Double> waits(final List<Pr> list, final long t0, final boolean corrected) {
    final List<Double> waits = new ArrayList<>();
    for (final Pr p : list) {
      if (p.opened() >= t0) {
        break;
      }
      if (p.opened() < t0 - WINDOW) {
        continue;
      }
      final long end = corrected
          ? Math.min(t0 - 1, p.closed() == NONE ? Long.MAX_VALUE : p.closed())
          : p.merged();
      final long review = inside(p.firstReview(), p.opened(), end);
      final long comment = inside(p.firstComment(), p.opened(), end);
      final long examined = review == NONE ? comment
          : comment == NONE ? review : Math.min(review, comment);
      if (examined != NONE) {
        waits.add((examined - p.opened()) / (double) DAY);
      }
    }
    return waits;
  }

  private static long inside(final long at, final long from, final long to) {
    return at != NONE && at >= from && at <= to ? at : NONE;
  }

  /** The previous paper's median: the upper middle, and zero when there is nothing. */
  private static double median(final List<Double> values) {
    if (values.isEmpty()) {
      return 0.0;
    }
    values.sort(Double::compare);
    return values.get(values.size() / 2);
  }

  /**
   * Files of this pull request the author had touched in earlier merged pull requests.
   * Registered: earlier by opening, merged whenever. Corrected: merged before t0.
   */
  private void familiarity(final Map<String, Double> out, final Pr self, final long t0,
      final boolean corrected) {
    if (self.author() == null || self.paths().isEmpty()) {
      out.put("pr_familiarity", 0.0);
      out.put("pr_familiarity_share", 0.0);
      return;
    }
    final Set<String> seen = new HashSet<>();
    for (final Pr prior : mergedByAuthorProject.getOrDefault(
        self.author() + "\u0000" + self.project(), List.of())) {
      if (prior.opened() >= self.opened()) {
        break;
      }
      if (!corrected || prior.merged() < t0) {
        seen.addAll(prior.paths());
      }
    }
    int overlap = 0;
    for (final String path : self.paths()) {
      if (seen.contains(path)) {
        overlap++;
      }
    }
    out.put("pr_familiarity", (double) overlap);
    out.put("pr_familiarity_share", (double) overlap / self.paths().size());
  }

  /** One person's commits in one project, readable as of an instant under either dating rule. */
  static final class History {

    private final long[] authored;
    private final long[] javaByAuthoring;
    private final long firstAuthored;
    private final long[] visible;
    private final long[] javaByVisibility;
    private final long[] earliestByVisibility;

    History(final List<Commit> commits) {
      final Commit[] a = commits.toArray(new Commit[0]);
      Arrays.sort(a, Comparator.comparingLong(Commit::authored));
      authored = new long[a.length];
      javaByAuthoring = new long[a.length];
      long java = 0;
      for (int i = 0; i < a.length; i++) {
        java += a[i].javaLines();
        authored[i] = a[i].authored();
        javaByAuthoring[i] = java;
      }
      firstAuthored = a.length == 0 ? NONE : a[0].authored();
      final Commit[] v = commits.toArray(new Commit[0]);
      Arrays.sort(v, Comparator.comparingLong(Commit::visible));
      visible = new long[v.length];
      javaByVisibility = new long[v.length];
      earliestByVisibility = new long[v.length];
      java = 0;
      long earliest = Long.MAX_VALUE;
      for (int i = 0; i < v.length; i++) {
        java += v[i].javaLines();
        earliest = Math.min(earliest, v[i].authored());
        visible[i] = v[i].visible();
        javaByVisibility[i] = java;
        earliestByVisibility[i] = earliest;
      }
    }

    /** The registered rule: commits authored before t0; tenure from the first commit ever. */
    double[] byAuthoring(final long t0) {
      final int n = before(authored, t0);
      if (n == 0) {
        return new double[] {0, 0, NONE, 0};
      }
      return new double[] {javaByAuthoring[n - 1], n, firstAuthored,
          (t0 - firstAuthored) / (double) DAY};
    }

    /** The corrected rule: commits visible before t0; tenure from the first of those. */
    double[] byVisibility(final long t0) {
      final int n = before(visible, t0);
      if (n == 0) {
        return new double[] {0, 0, NONE, 0};
      }
      final long first = earliestByVisibility[n - 1];
      return new double[] {javaByVisibility[n - 1], n, first, (t0 - first) / (double) DAY};
    }

    private static int before(final long[] sorted, final long value) {
      int low = 0;
      int high = sorted.length;
      while (low < high) {
        final int mid = (low + high) >>> 1;
        if (sorted[mid] < value) {
          low = mid + 1;
        } else {
          high = mid;
        }
      }
      return low;
    }
  }
}
