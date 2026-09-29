package app.issues;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The time-travel gate: the historical C1 columns recomputed on a view of the corpus truncated at
 * each issue's creation instant, by plain scans and without {@link World}'s indexes.
 *
 * <p>The truncated view holds only the issues created before t0, each resolved only if its first
 * resolution came before t0, and only the commits already visible at t0. A column is "strictly
 * known at t0" when the scan and the extractor agree on every sampled issue. Beside it, a
 * <b>naive</b> rule -- the final outcome instead of the one known at t0, the author date instead of
 * visibility -- must disagree somewhere, which proves the sample holds the adversarial case. The
 * Java twin of {@code analises/40_viagem.py}.
 *
 * <p>The instant is each unit's start: the creation in the main study, the first In Progress in
 * the secondary one, where {@code at} also brings the executor and the participants known then, and
 * the executor's history and the participants' commits are checked too.
 */
public final class TimeTravel {

  public static final long SEED = 20260929L;
  public static final int SAMPLE = 600;

  /** Per column: agreements, comparisons, and how often the naive rule differs. */
  public record Check(String column, int same, int total, int naiveDiffers, List<String> examples) {}

  private TimeTravel() {}

  private static final class Tally {
    int same;
    int total;
    int naive;
    final List<String> examples = new ArrayList<>();
  }

  private static boolean equal(final Double a, final Double b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return Math.abs(a - b) <= 1e-6 * Math.max(1.0, Math.abs(a));
  }

  public static List<Check> run(final World world, final List<Universe.Unit> population, final Map<String, Map<String, Double>> rows) {
    return run(world, population, rows, null);
  }

  /** @param at per issue, what the secondary study read at its start; null in the main study */
  public static List<Check> run(final World world, final List<Universe.Unit> population,
      final Map<String, Map<String, Double>> rows, final Map<String, Extractor.At> at) {
    final Universe universe = world.universe;
    final List<Universe.Unit> sorted = new ArrayList<>(population);
    sorted.sort((a, b) -> a.id().compareTo(b.id()));
    final Random random = new Random(SEED);
    final List<Universe.Unit> sample = new ArrayList<>();
    final Set<Integer> taken = new HashSet<>();
    while (sample.size() < Math.min(SAMPLE, sorted.size())) {
      final int k = random.nextInt(sorted.size());
      if (taken.add(k)) {
        sample.add(sorted.get(k));
      }
    }
    // raw lists, no index
    final Map<String, List<String>> projectIssues = new HashMap<>();
    for (final String id : universe.universe()) {
      projectIssues.computeIfAbsent(universe.issue(id).project(), p -> new ArrayList<>()).add(id);
    }
    final Map<String, List<World.Commit>> projectCommits = new HashMap<>();
    final Map<String, List<World.Commit>> personCommits = new HashMap<>();
    for (final World.Commit c : world.commits()) {
      projectCommits.computeIfAbsent(c.project(), p -> new ArrayList<>()).add(c);
      if (c.who() != null) {
        personCommits.computeIfAbsent(c.who(), p -> new ArrayList<>()).add(c);
      }
    }
    final Map<String, Long> resolution = new HashMap<>();
    final Map<String, Boolean> fixed = new HashMap<>();
    for (final String id : universe.universe()) {
      final List<Corpus.Event> ev = universe.events(id);
      final Long r = Universe.firstResolution(ev);
      resolution.put(id, r);
      fixed.put(id, r != null && "Fixed".equals(Universe.reasonAt(ev, r)));
    }
    final Map<String, Tally> tallies = new LinkedHashMap<>();

    for (final Universe.Unit u : sample) {
      final Map<String, Double> v = rows.get(u.id());
      final long t0 = u.start();
      final String p = u.project();
      final String rep = world.person(u.reporter());
      // ---- the truncated project
      final List<long[]> issues = new ArrayList<>(); // created, resolved-or-MIN(unknown), id index
      final List<String> ids = new ArrayList<>();
      for (final String id : projectIssues.getOrDefault(p, List.of())) {
        final long created = universe.issue(id).created();
        if (created < t0) {
          final Long r = resolution.get(id);
          issues.add(new long[] {created, r != null && r < t0 ? r : Long.MIN_VALUE});
          ids.add(id);
        }
      }
      final List<World.Commit> commits = new ArrayList<>();
      for (final World.Commit c : projectCommits.getOrDefault(p, List.of())) {
        if (c.visible() < t0) {
          commits.add(c);
        }
      }
      final long w90 = t0 - Extractor.WINDOW_90;
      final long w14 = t0 - Extractor.WINDOW_14;
      int k14 = 0;
      int k15 = 0;
      int k17 = 0;
      int k18 = 0;
      int k15naive = 0;
      int k18naive = 0;
      int backlog = 0;
      int backlogNaive = 0;
      for (int i = 0; i < issues.size(); i++) {
        final long[] x = issues.get(i);
        final boolean known = x[1] != Long.MIN_VALUE;
        final boolean ever = resolution.get(ids.get(i)) != null;
        if (x[0] >= w90) {
          k14++;
          k15 += known ? 1 : 0;
          k15naive += ever ? 1 : 0;
        }
        if (x[0] >= w14) {
          k17++;
          k18 += known ? 1 : 0;
          k18naive += ever ? 1 : 0;
        }
        backlog += known ? 0 : 1;
        backlogNaive += ever ? 0 : 1;
      }
      add(tallies, "K14_project_issues_90d", (double) k14, v, null);
      add(tallies, "K15_project_issues_closed_90d", (double) k15, v, (double) k15naive);
      add(tallies, "K17_project_issues_14d", (double) k17, v, null);
      add(tallies, "K18_project_created_closed_14d", (double) k18, v, (double) k18naive);
      add(tallies, "D01_backlog_open", (double) backlog, v, (double) backlogNaive);
      int k16 = 0;
      int k19 = 0;
      for (final World.Commit c : commits) {
        k16 += c.visible() >= w90 ? 1 : 0;
        k19 += c.visible() >= w14 ? 1 : 0;
      }
      int k16naive = 0;
      int k19naive = 0;
      for (final World.Commit c : projectCommits.getOrDefault(p, List.of())) {
        k16naive += c.authoredAt() >= w90 && c.authoredAt() < t0 ? 1 : 0;
        k19naive += c.authoredAt() >= w14 && c.authoredAt() < t0 ? 1 : 0;
      }
      add(tallies, "K16_project_commits_90d", (double) k16, v, (double) k16naive);
      add(tallies, "K19_project_commits_14d", (double) k19, v, (double) k19naive);
      // ---- team
      final Set<String> team = new HashSet<>();
      final Set<String> teamNaive = new HashSet<>();
      for (final World.Commit c : commits) {
        if (c.visible() >= w90 && c.who() != null && !c.bot()) {
          team.add(c.who());
        }
      }
      for (final World.Commit c : projectCommits.getOrDefault(p, List.of())) {
        if (c.authoredAt() >= w90 && c.authoredAt() < t0 && c.who() != null && !c.bot()) {
          teamNaive.add(c.who());
        }
      }
      add(tallies, "A03_team_size", (double) team.size(), v, (double) teamNaive.size());
      final List<Double> locs = new ArrayList<>();
      final List<Double> mcs = new ArrayList<>();
      final List<Double> locsNaive = new ArrayList<>();
      for (final String m : team.stream().sorted().toList()) {
        double loc = 0;
        double mc = 0;
        double locNaive = 0;
        for (final World.Commit c : projectCommits.getOrDefault(p, List.of())) {
          if (m.equals(c.who())) {
            if (c.visible() < t0) {
              loc += c.loc();
              mc += c.mccc() == null ? 0.0 : c.mccc();
            }
            if (c.authoredAt() < t0) {
              locNaive += c.loc();
            }
          }
        }
        locs.add(loc);
        mcs.add(mc);
        locsNaive.add(locNaive);
      }
      final Double[] l = Extractor.meanSd(locs);
      final Double[] mm = Extractor.meanSd(mcs);
      add(tallies, "A04_mu_l", l[0], v, Extractor.meanSd(locsNaive)[0]);
      add(tallies, "A05_s_l", l[1], v, null);
      add(tallies, "A06_mu_m", mm[0], v, null);
      add(tallies, "A07_s_m", mm[1], v, null);
      // ---- first interaction in the project's last 90 days, seen from t0
      final List<Double> waits = new ArrayList<>();
      final List<Double> waitsNaive = new ArrayList<>();
      for (int i = 0; i < issues.size(); i++) {
        if (issues.get(i)[0] < w90) {
          continue;
        }
        final String id = ids.get(i);
        final String rx = world.person(universe.issue(id).reporter());
        Long first = null;
        Long firstComment = null;
        for (final Corpus.Comment c : world.comments(id)) {
          if (c.at() != null && c.author() != null && !world.person(c.author()).equals(rx)) {
            if (c.at() < t0) {
              first = first == null ? c.at() : Math.min(first, c.at());
            }
            firstComment = firstComment == null ? c.at() : Math.min(firstComment, c.at());
          }
        }
        for (final Corpus.Event e : universe.events(id)) {
          if (e.at() != null && e.at() < t0 && e.author() != null
              && !world.person(e.author()).equals(rx)
              && ("assignee_id".equals(e.field())
              || ("status".equals(e.field()) && !Universe.RESOLVED.contains(e.newValue())))) {
            first = first == null ? e.at() : Math.min(first, e.at());
          }
        }
        if (first != null) {
          waits.add((double) (first - issues.get(i)[0]));
        }
        if (firstComment != null) {
          waitsNaive.add((double) (firstComment - issues.get(i)[0]));
        }
      }
      final Double wait = Extractor.median(waits);
      final Double waitNaive = Extractor.median(waitsNaive);
      add(tallies, "D03_project_first_interaction_median_h",
          wait == null ? null : wait / World.HOUR, v,
          waitNaive == null ? null : waitNaive / World.HOUR);
      // ---- the reporter, over every project
      if (rep != null) {
        int opened = 0;
        int fixedBefore = 0;
        int fixedNaive = 0;
        int k11 = 0;
        int k12 = 0;
        int k12naive = 0;
        final List<Double> past = new ArrayList<>();
        final List<Double> pastNaive = new ArrayList<>();
        for (final String id : universe.universe()) {
          final Corpus.Issue issue = universe.issue(id);
          if (issue.created() >= t0 || !rep.equals(world.person(issue.reporter()))) {
            continue;
          }
          opened++;
          final Long r = resolution.get(id);
          final boolean closedBefore = r != null && r < t0;
          fixedBefore += closedBefore && fixed.get(id) ? 1 : 0;
          fixedNaive += fixed.get(id) ? 1 : 0;
          if (issue.created() >= w90) {
            k11++;
            k12 += closedBefore ? 1 : 0;
            k12naive += r != null ? 1 : 0;
          }
          if (issue.project().equals(p)) {
            if (closedBefore) {
              past.add((double) (r - issue.created()));
            }
            if (r != null) {
              pastNaive.add((double) (r - issue.created()));
            }
          }
        }
        add(tallies, "Z09_reputation", fixedBefore / (opened + 1.0), v,
            fixedNaive / (opened + 1.0));
        add(tallies, "K11_creator_issues_90d", (double) k11, v, null);
        add(tallies, "K12_creator_issues_closed_90d", (double) k12, v, (double) k12naive);
        final Double pm = Extractor.median(past);
        final Double pmNaive = Extractor.median(pastNaive);
        add(tallies, "D05_reporter_project_median_h", pm == null ? null : pm / World.HOUR, v,
            pmNaive == null ? null : pmNaive / World.HOUR);
        boolean known = false;
        int k13 = 0;
        int k13naive = 0;
        int k20 = 0;
        int k20naive = 0;
        boolean anyInProject = false;
        for (final World.Commit c : personCommits.getOrDefault(rep, List.of())) {
          known |= c.visible() < t0;
          if (!c.project().equals(p)) {
            continue;
          }
          anyInProject |= c.visible() < t0;
          k13 += c.visible() >= w90 && c.visible() < t0 ? 1 : 0;
          k13naive += c.authoredAt() >= w90 && c.authoredAt() < t0 ? 1 : 0;
          k20 += c.visible() >= w14 && c.visible() < t0 ? 1 : 0;
          k20naive += c.authoredAt() >= w14 && c.authoredAt() < t0 ? 1 : 0;
        }
        add(tallies, "K13_creator_commits_90d", known ? (double) k13 : null, v,
            (double) k13naive);
        if (at == null) {
          add(tallies, "K20c1_participant_commits_14d", known ? (double) k20 : null, v,
              (double) k20naive);
        }
        add(tallies, "K13x_reporter_no_commit_ever", anyInProject ? 0.0 : 1.0, v, null);
      }
    }
    if (at != null) {
      startChecks(tallies, sample, rows, at, personCommits);
    }
    final List<Check> out = new ArrayList<>();
    tallies.forEach((k, t) -> out.add(new Check(k, t.same, t.total, t.naive,
        List.copyOf(t.examples))));
    return out;
  }

  /**
   * The secondary study's own columns on the truncated view: the executor's history in the project
   * and the participants' commits in the fourteen days before t0, both by visibility, against the
   * naive author date.
   */
  private static void startChecks(final Map<String, Tally> tallies,
      final List<Universe.Unit> sample, final Map<String, Map<String, Double>> rows,
      final Map<String, Extractor.At> at, final Map<String, List<World.Commit>> personCommits) {
    for (final Universe.Unit u : sample) {
      final Map<String, Double> v = rows.get(u.id());
      final Extractor.At a = at.get(u.id());
      final long t0 = a.t0();
      final String p = u.project();
      final long w14 = t0 - Extractor.WINDOW_14;
      final String ex = a.executor();
      boolean exKnown = false;
      int n = 0;
      long loc = 0;
      int nNaive = 0;
      Long first = null;
      for (final World.Commit c : ex == null ? List.<World.Commit>of()
          : personCommits.getOrDefault(ex, List.of())) {
        exKnown |= c.visible() < t0;
        if (!c.project().equals(p)) {
          continue;
        }
        if (c.visible() < t0) {
          n++;
          loc += c.loc();
          first = first == null ? c.visible() : Math.min(first, c.visible());
        }
        nNaive += c.authoredAt() < t0 ? 1 : 0;
      }
      add(tallies, "A11_exec_commits", exKnown ? (double) n : null, v, (double) nNaive);
      add(tallies, "A09_exec_loc_tot", exKnown ? (double) loc : null, v, null);
      add(tallies, "A12_exec_tenure_days", !exKnown ? null
          : first == null ? 0.0 : (t0 - first) / (double) World.DAY, v, null);
      int k20 = 0;
      int k20naive = 0;
      boolean anyKnown = false;
      for (final String who : a.participants()) {
        boolean known = false;
        int mine = 0;
        for (final World.Commit c : personCommits.getOrDefault(who, List.of())) {
          known |= c.visible() < t0;
          if (c.project().equals(p)) {
            mine += c.visible() >= w14 && c.visible() < t0 ? 1 : 0;
            k20naive += c.authoredAt() >= w14 && c.authoredAt() < t0 ? 1 : 0;
          }
        }
        if (known) {
          anyKnown = true;
          k20 += mine;
        }
      }
      add(tallies, "K20c1_participant_commits_14d", anyKnown ? (double) k20 : null, v,
          (double) k20naive);
    }
  }

  private static void add(final Map<String, Tally> tallies, final String column,
      final Double truncated, final Map<String, Double> row, final Double naive) {
    final Tally t = tallies.computeIfAbsent(column, k -> new Tally());
    final Double stored = row.get(column);
    t.total++;
    if (equal(truncated, stored)) {
      t.same++;
    } else if (t.examples.size() < 3) {
      t.examples.add("truncada=" + truncated + " extrator=" + stored);
    }
    if (naive != null && truncated != null && !equal(naive, truncated)) {
      t.naive++;
    }
  }
}
