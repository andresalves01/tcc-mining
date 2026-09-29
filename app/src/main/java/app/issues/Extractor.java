package app.issues;

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
 * Every column of one issue, in both scenarios.
 *
 * <p>The Java twin of {@code papers/issues/viabilidade/analises/colunas.py}, which the extraction is
 * checked against column by column, plus what the Python did not compute: the team's open load
 * (A14), the change's lines divided among the issues a commit links (A15, decision 12; A15w keeps
 * the whole commit for the sensitivity), the files changed and the executor's familiarity with them
 * (D07, D08), and how much of the McCC history was measured (decision 8).
 *
 * <p><b>C1</b> reads only what existed at the creation instant t0. <b>C2</b> adds columns measured in
 * {@code [t0, t1)}, where t1 is the first resolution -- always new columns, never a C1 value
 * replaced. A value that cannot be computed is {@code null}, never zero.
 */
public final class Extractor {

  public static final long WINDOW_90 = 90 * World.DAY;
  public static final long WINDOW_14 = 14 * World.DAY;

  public static final List<String> AZ_TYPES =
      List.of("Bug", "Task", "Improvement", "Test", "Sub-task", "New Feature", "Wish");
  static final Map<String, Double> AZ_PRIORITY = Map.of("Trivial", 1.0, "Minor", 2.0, "Major", 3.0,
      "Critical", 4.0, "Blocker", 5.0);

  private final World world;
  private final Universe universe;
  private final Map<String, Long> firstInteraction = new HashMap<>();
  private final Map<String, long[][]> executorOpen = new HashMap<>();
  private final Map<String, Map<Integer, Long>> firstTouch = new HashMap<>();

  /**
   * Builds what every row needs from the whole universe: first interactions, the retrospective
   * executors' open issues, and the files each studied executor had touched.
   */
  public Extractor(final World world, final Set<String> studied) {
    this.world = world;
    this.universe = world.universe;
    for (final String id : universe.universe()) {
      firstInteraction.put(id, firstInteraction(id));
    }
    final Map<String, List<long[]>> open = new HashMap<>();
    for (final String id : universe.universe()) {
      final Corpus.Issue issue = universe.issue(id);
      final Long resolved = Universe.firstResolution(universe.events(id));
      final long limit = resolved == null ? Long.MAX_VALUE : resolved;
      final World.Commit first = executorCommit(id, limit);
      if (first != null) {
        open.computeIfAbsent(first.who() + "\u0000" + issue.project(), k -> new ArrayList<>())
            .add(new long[] {issue.created(), limit});
      }
    }
    open.forEach((k, list) -> {
      final long[] created = list.stream().mapToLong(a -> a[0]).sorted().toArray();
      final long[] resolved = list.stream().mapToLong(a -> a[1]).sorted().toArray();
      executorOpen.put(k, new long[][] {created, resolved});
    });
    // first touch of each file, only for the (executor, project) pairs of studied issues
    final Set<String> wanted = new HashSet<>();
    for (final String id : studied) {
      final Universe.Unit u = universe.unit(id);
      final World.Commit ex = executorCommit(id, u.resolved());
      if (ex != null) {
        wanted.add(ex.who() + "\u0000" + u.project());
      }
    }
    for (final World.Commit c : world.commits()) {
      final String who = c.who();
      if (who == null || !wanted.contains(who + "\u0000" + c.project())) {
        continue;
      }
      final int[] files = world.corpus.files().get(c.id());
      if (files == null) {
        continue;
      }
      final long visible = c.visible();
      final Map<Integer, Long> touch =
          firstTouch.computeIfAbsent(who + "\u0000" + c.project(), k -> new HashMap<>());
      for (final int f : files) {
        touch.merge(f, visible, Math::min);
      }
    }
  }

  /** The retrospective executor's first commit, or null: the person the conditioned reference uses. */
  public World.Commit executorOf(final String issue, final long limit) {
    return executorCommit(issue, limit);
  }

  /** The human author of the first linked commit visible before {@code limit}, by authoring. */
  World.Commit executorCommit(final String issue, final long limit) {
    final List<World.Commit> commits = executorCommits(issue, limit);
    return commits.isEmpty() ? null : commits.get(0);
  }

  private List<World.Commit> executorCommits(final String issue, final long limit) {
    final List<World.Commit> out = new ArrayList<>();
    for (final World.Commit c : world.linked(issue)) {
      if (c.visible() < limit && c.who() != null && !c.bot()) {
        out.add(c);
      }
    }
    out.sort(Comparator.comparingLong(World.Commit::authoredAt).thenComparing(World.Commit::hash));
    return out;
  }

  /**
   * The first substantive interaction: a comment, an assignment or a status change that is not the
   * resolution, by someone other than the reporter. Null when there is none.
   */
  private Long firstInteraction(final String id) {
    final String reporter = world.person(universe.issue(id).reporter());
    Long best = null;
    for (final Corpus.Comment c : world.comments(id)) {
      if (c.at() != null && c.author() != null && !world.person(c.author()).equals(reporter)) {
        best = c.at();
        break;
      }
    }
    for (final Corpus.Event e : universe.events(id)) {
      if (e.at() == null || e.author() == null || world.person(e.author()).equals(reporter)) {
        continue;
      }
      if ("assignee_id".equals(e.field())
          || ("status".equals(e.field()) && !Universe.RESOLVED.contains(e.newValue()))) {
        best = best == null ? e.at() : Math.min(best, e.at());
        break;
      }
    }
    return best;
  }

  /** The first substantive interaction of any universe issue, for the time-travel gate. */
  public Long firstInteractionOf(final String id) {
    return firstInteraction.get(id);
  }

  private int executorOpenAt(final String who, final String project, final long t) {
    final long[][] a = executorOpen.get(who + "\u0000" + project);
    return a == null ? 0 : World.lowerBound(a[0], t) - World.lowerBound(a[1], t);
  }

  static Double median(final List<Double> values) {
    if (values.isEmpty()) {
      return null;
    }
    final double[] s = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    final int n = s.length;
    return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
  }

  /** Population mean and standard deviation; nulls for an empty list. */
  static Double[] meanSd(final List<Double> values) {
    if (values.isEmpty()) {
      return new Double[] {null, null};
    }
    double m = 0;
    for (final double v : values) {
      m += v;
    }
    m /= values.size();
    double s = 0;
    for (final double v : values) {
      s += (v - m) * (v - m);
    }
    return new Double[] {m, Math.sqrt(s / values.size())};
  }

  private static Double bool(final boolean b) {
    return b ? 1.0 : 0.0;
  }

  /** Every column of one studied issue. Null values are absences. */
  public Map<String, Double> row(final Universe.Unit u, final Initial.Values init,
      final Corpus.Label label) {
    final Map<String, Double> v = new LinkedHashMap<>();
    final String p = u.project();
    final long t0 = u.created();
    final long t1 = u.resolved();
    final String rep = world.person(u.reporter());

    // ------------------------------------------------------------ text at creation
    final String title0 = init.title() == null ? "" : init.title();
    final String desc0 = init.description() == null ? "" : init.description();
    v.put("TXT_title_words", (double) TextMeasures.words(title0));
    v.put("TXT_desc_words", init.description() != null ? (double) TextMeasures.words(desc0) : null);
    v.put("TXT_title_fog", TextMeasures.fog(title0));
    v.put("TXT_desc_fog", init.description() != null ? TextMeasures.fog(desc0) : null);
    v.put("K02_body_len", (double) (TextMeasures.words(TextMeasures.cleaned(title0))
        + TextMeasures.words(TextMeasures.cleaned(desc0))));
    v.put("A01_desc_length", init.description() != null ? (double) TextMeasures.length(desc0)
        : null);

    // ------------------------------------------------------------ categories at creation
    for (final String type : AZ_TYPES) {
      v.put("Z_type_" + type, bool(type.equals(init.type())));
    }
    v.put("Z08_priority", init.priority() == null ? null : AZ_PRIORITY.get(init.priority()));
    v.put("LBL_count_c1", (double) init.labels().size());
    v.put("LNK_count_c1", (double) init.links().size());
    v.put("ASG_present_c1", bool(init.assignee() != null));

    // ------------------------------------------------------------ reporter
    final List<World.Reported> hist = new ArrayList<>();
    for (final World.Reported r : world.reported(rep)) {
      if (r.created() < t0) {
        hist.add(r);
      }
    }
    final long fixed = hist.stream().filter(r -> r.fixedAt() != null && r.fixedAt() < t0).count();
    v.put("Z09_reputation", rep == null ? null : fixed / (hist.size() + 1.0));
    final List<World.Reported> win =
        hist.stream().filter(r -> r.created() >= t0 - WINDOW_90).toList();
    v.put("K11_creator_issues_90d", rep == null ? null : (double) win.size());
    v.put("K12_creator_issues_closed_90d", rep == null ? null
        : (double) win.stream().filter(r -> r.resolved() != null && r.resolved() < t0).count());
    // decision 9: without a Git identity known at t0 the reporter's commits are unknown, not zero
    final boolean known = world.gitKnownAt(rep, t0);
    v.put("REP_git_known_t0", bool(known));
    v.put("K13_creator_commits_90d",
        known ? (double) world.personCommitsIn(rep, p, t0 - WINDOW_90, t0) : null);
    v.put("K13x_reporter_no_commit_ever",
        bool(rep == null || world.personHistory(rep, p, t0).commits() == 0));
    final List<Double> past = new ArrayList<>();
    for (final World.Reported r : hist) {
      if (r.project().equals(p) && r.resolved() != null && r.resolved() < t0) {
        past.add((double) (r.resolved() - r.created()));
      }
    }
    final Double pastMedian = median(past);
    v.put("D05_reporter_project_median_h", pastMedian == null ? null : pastMedian / World.HOUR);

    // ------------------------------------------------------------ project
    v.put("K14_project_issues_90d", (double) world.countCreated(p, t0 - WINDOW_90, t0));
    final List<World.Created> cw = world.createdIn(p, t0 - WINDOW_90, t0);
    v.put("K15_project_issues_closed_90d",
        (double) cw.stream().filter(c -> c.resolved() != null && c.resolved() < t0).count());
    v.put("K16_project_commits_90d", (double) world.countCommits(p, t0 - WINDOW_90, t0));
    v.put("K17_project_issues_14d", (double) world.countCreated(p, t0 - WINDOW_14, t0));
    v.put("K18_project_created_closed_14d", (double) world.createdIn(p, t0 - WINDOW_14, t0)
        .stream().filter(c -> c.resolved() != null && c.resolved() < t0).count());
    final Integer rc = known ? world.personCommitsIn(rep, p, t0 - WINDOW_14, t0) : null;
    v.put("K20c1_participant_commits_14d", rc == null ? null : (double) rc);
    v.put("K21c1_participant_committers_14d", rc == null ? null : bool(rc > 0));
    v.put("K19_project_commits_14d", (double) world.countCommits(p, t0 - WINDOW_14, t0));
    v.put("D01_backlog_open", (double) world.openAt(p, t0));
    final List<Double> waits = new ArrayList<>();
    for (final World.Created c : cw) {
      final Long fi = firstInteraction.get(c.id());
      if (fi != null && fi < t0) {
        waits.add((double) (fi - c.created()));
      }
    }
    final Double wait = median(waits);
    v.put("D03_project_first_interaction_median_h", wait == null ? null : wait / World.HOUR);
    v.put("PROJ_age_days", (t0 - world.projectFirstCreated(p)) / (double) World.DAY);

    // ------------------------------------------------------------ team (visible commits, 90 days)
    final Set<String> team = world.authorsIn(p, t0 - WINDOW_90, t0);
    v.put("A03_team_size", (double) team.size());
    final List<Double> locs = new ArrayList<>();
    final List<Double> mcs = new ArrayList<>();
    int teamJava = 0;
    int teamCovered = 0;
    for (final String m : team.stream().sorted().toList()) {
      final World.PersonHistory h = world.personHistory(m, p, t0);
      locs.add((double) h.loc());
      mcs.add(h.mccc());
      teamJava += h.javaCommits();
      teamCovered += h.javaCovered();
    }
    final Double[] l = meanSd(locs);
    final Double[] m = meanSd(mcs);
    v.put("A04_mu_l", l[0]);
    v.put("A05_s_l", l[1]);
    v.put("A06_mu_m", m[0]);
    v.put("A07_s_m", m[1]);
    v.put("A06c_team_mccc_coverage", teamJava == 0 ? null : (double) teamCovered / teamJava);

    // ============================================================ C2: between t0 and t1
    final List<Corpus.Comment> cms = new ArrayList<>();
    for (final Corpus.Comment c : world.comments(u.id())) {
      if (c.at() != null && c.at() >= t0 && c.at() < t1) {
        cms.add(c);
      }
    }
    final List<Corpus.Event> evs = new ArrayList<>();
    for (final Corpus.Event e : universe.events(u.id())) {
      if (e.at() != null && e.at() >= t0 && e.at() < t1) {
        evs.add(e);
      }
    }
    v.put("K03_comments", (double) cms.size());
    final Set<String> actors = new HashSet<>();
    if (rep != null) {
      actors.add(rep);
    }
    for (final Corpus.Comment c : cms) {
      if (c.author() != null) {
        actors.add(world.person(c.author()));
      }
    }
    for (final Corpus.Event e : evs) {
      if (e.author() != null
          && !("status".equals(e.field()) && Universe.RESOLVED.contains(e.newValue()))) {
        actors.add(world.person(e.author()));
      }
    }
    v.put("K04_actors", (double) actors.size());
    v.put("K05_assignments",
        (double) evs.stream().filter(e -> "assignee_id".equals(e.field())).count());
    Set<String> labels = init.labels();
    for (final Corpus.Event e : evs) {
      if ("labels".equals(e.field())) {
        labels = Initial.words(e.newValue());
      }
    }
    v.put("K06_labels_c2", (double) labels.size());
    v.put("K07_mentions", (double) world.mentionsIn(u.id(), t0, t1));
    final List<World.Commit> lk = world.linked(u.id());
    v.put("K08_commit_refs",
        (double) lk.stream().filter(c -> c.visible() >= t0 && c.visible() < t1).count());
    v.put("K10_mean_comment_words", cms.isEmpty() ? null
        : cms.stream().mapToInt(Corpus.Comment::words).sum() / (double) cms.size());
    long participantCommits = 0;
    int participantCommitters = 0;
    for (final String a : actors) {
      final int n = world.personCommitsIn(a, p, t0 - WINDOW_14, t1);
      participantCommits += n;
      participantCommitters += n > 0 ? 1 : 0;
    }
    v.put("K20_participant_commits", (double) participantCommits);
    v.put("K21_participant_committers", (double) participantCommitters);
    v.put("K17c2_project_issues_14d_obs", (double) world.countCreated(p, t0 - WINDOW_14, t1));
    v.put("K18c2_project_created_closed_14d_obs", (double) world.createdIn(p, t0 - WINDOW_14, t1)
        .stream().filter(c -> c.resolved() != null && c.resolved() < t1).count());
    v.put("K19c2_project_commits_14d_obs", (double) world.countCommits(p, t0 - WINDOW_14, t1));
    final Map<String, Integer> links = new HashMap<>();
    init.links().forEach(t -> links.merge(t, 1, Integer::sum));
    for (final Corpus.Event e : evs) {
      if ("issue_links".equals(e.field())) {
        if (e.newValue() != null) {
          links.merge(e.newValue(), 1, Integer::sum);
        }
        if (e.oldValue() != null && links.getOrDefault(e.oldValue(), 0) > 0) {
          links.merge(e.oldValue(), -1, Integer::sum);
        }
      }
    }
    v.put("A02c2_links_before_res",
        (double) links.values().stream().mapToInt(Integer::intValue).sum());
    v.put("ASG_present_c2", bool(init.assignee() != null || evs.stream()
        .anyMatch(e -> "assignee_id".equals(e.field()) && Initial.norm(e.newValue()) != null)));
    final Long fi = firstInteraction.get(u.id());
    // an interaction stamped before creation (a clock anomaly) counts as immediate: log1p of the
    // negative hours would not be a number
    v.put("D06_first_interaction_h",
        fi != null && fi < t1 ? Math.max(0.0, (fi - t0) / (double) World.HOUR) : null);

    // ------------------------------------------------------------ retrospective executor
    final List<World.Commit> exCommits = executorCommits(u.id(), t1);
    final String ex = exCommits.isEmpty() ? null : exCommits.get(0).who();
    v.put("EXE_known", bool(ex != null));
    if (ex != null) {
      final World.PersonHistory h = world.personHistory(ex, p, t0);
      v.put("A09_exec_loc_tot", (double) h.loc());
      v.put("A10_exec_mccc_tot", h.mccc());
      v.put("A10c_exec_mccc_coverage",
          h.javaCommits() == 0 ? null : (double) h.javaCovered() / h.javaCommits());
      v.put("A11_exec_commits", (double) h.commits());
      v.put("A12_exec_tenure_days",
          h.first() == null ? 0.0 : (t0 - h.first()) / (double) World.DAY);
      v.put("A13_exec_open_load", (double) executorOpenAt(ex, p, t0));
      double whole = 0;
      double shared = 0;
      final Set<Integer> files = new HashSet<>();
      for (final World.Commit c : exCommits) {
        whole += c.loc();
        shared += c.loc() / (double) Math.max(1, c.linked().size());
        final int[] f = world.corpus.files().get(c.id());
        if (f != null) {
          Arrays.stream(f).forEach(files::add);
        }
      }
      v.put("A15_change_loc", shared);
      v.put("A15w_change_loc_whole", whole);
      if (!files.isEmpty()) {
        final Map<Integer, Long> touch = firstTouch.getOrDefault(ex + "\u0000" + p, Map.of());
        final long knownFiles =
            files.stream().filter(f -> touch.getOrDefault(f, Long.MAX_VALUE) < t0).count();
        v.put("D07_files_changed", (double) files.size());
        v.put("D08_file_familiarity", knownFiles / (double) files.size());
      } else {
        v.put("D07_files_changed", null);
        v.put("D08_file_familiarity", null);
      }
    } else {
      for (final String k : List.of("A09_exec_loc_tot", "A10_exec_mccc_tot",
          "A10c_exec_mccc_coverage", "A11_exec_commits", "A12_exec_tenure_days",
          "A13_exec_open_load", "A15_change_loc", "A15w_change_loc_whole", "D07_files_changed",
          "D08_file_familiarity")) {
        v.put(k, null);
      }
    }
    int teamLoad = 0;
    for (final String member : team) {
      teamLoad += executorOpenAt(member, p, t0);
    }
    v.put("A14_team_open_load", (double) teamLoad);
    v.put("A16_change_mean_mccc", label == null ? null : label.meanMccc());
    v.put("A17_change_mean_nl", label == null ? null : label.meanNl());
    v.put("CODE_linked_any", bool(!lk.isEmpty()));
    v.put("CODE_linked_before_res", bool(!exCommits.isEmpty()));
    v.put("CODE_linked_after_res_only",
        bool(!lk.isEmpty() && lk.stream().noneMatch(c -> c.visible() < t1)));
    v.put("CODE_java_before_res", bool(exCommits.stream().anyMatch(World.Commit::java)));
    return v;
  }
}
