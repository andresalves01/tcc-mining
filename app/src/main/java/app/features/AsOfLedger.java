package app.features;

import app.cost.Contribution;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The contribution ledger, indexed so that "what had this developer done before that instant" costs
 * a binary search rather than a scan.
 *
 * <p>{@link app.cost.Seniority} answers the same question by walking the whole ledger once per
 * instant asked about. That is right for a report that asks a handful of times and wrong here,
 * where every label asks and there are tens of thousands of them. The two agree on the rule that
 * matters: a contribution authored exactly at the instant is skipped, because a change made at
 * {@code t_i} is not observable at {@code t_i} -- and because a change belonging to the task itself
 * must never compose the history used to predict it.
 *
 * <p>Pairs are keyed on the project <i>name</i>, not the project id, because that is what a label
 * carries.
 *
 * <p>Bots count towards a developer's own accumulation and are excluded from team membership. The
 * asymmetry is deliberate. The funnel already removed bot-authored labels, so filtering the
 * developer side could only ever zero a row silently; the active team, in contrast, is defined by
 * the study as the humans who committed inside the window.
 */
public final class AsOfLedger {

  private static final char KEY_SEPARATOR = '\u0000';

  private final Map<String, Pair> pairs;
  private final Map<String, ProjectIndex> projects;

  public AsOfLedger(final Collection<Contribution> ledger) {
    final Map<String, List<Contribution>> byPair = new HashMap<>(1 << 12);
    final Map<String, List<Contribution>> byProject = new HashMap<>(1 << 8);
    for (final Contribution c : ledger) {
      if (c.canonicalId() == null || c.projectName() == null || c.authoredAt() == null) {
        continue; // a row missing any of the three cannot be placed in time or attributed
      }
      byPair.computeIfAbsent(key(c.canonicalId(), c.projectName()), k -> new ArrayList<>()).add(c);
      if (!c.bot()) {
        byProject.computeIfAbsent(c.projectName(), k -> new ArrayList<>()).add(c);
      }
    }
    this.pairs = new HashMap<>(byPair.size() * 2);
    byPair.forEach((k, commits) -> pairs.put(k, new Pair(commits)));
    this.projects = new HashMap<>(byProject.size() * 2);
    byProject.forEach((name, commits) -> projects.put(name, new ProjectIndex(commits)));
  }

  /**
   * What {@code developer} had accumulated in {@code project} strictly before {@code asOf}, or
   * empty when they had committed nothing there yet.
   *
   * <p>Empty is not an error and must not become a dropped row: a developer's first task in a
   * project legitimately carries {@code LOC_tot = 0}. Use {@link #knows} to tell that case apart
   * from a pair the ledger never heard of, which is the one worth counting in a report.
   */
  public Optional<DeveloperHistory> historyAt(
      final String developer, final String project, final Instant asOf) {

    final Pair pair = pairs.get(key(developer, project));
    return pair == null ? Optional.empty() : pair.at(asOf);
  }

  /** Whether the ledger holds any commit at all by {@code developer} in {@code project}. */
  public boolean knows(final String developer, final String project) {
    return pairs.containsKey(key(developer, project));
  }

  /**
   * The humans with at least one commit in {@code project} during {@code [asOf - window, asOf)}.
   *
   * <p>The window bounds participation observed, and deliberately does not discount its members'
   * accumulated experience -- someone who committed yesterday brings their whole history with them.
   * The retrospective author of the task is not added: whether they were active is a fact to
   * measure, not an assumption to encode.
   */
  public Set<String> activeTeamAt(
      final String project, final Instant asOf, final Duration window) {

    final ProjectIndex index = projects.get(project);
    return index == null ? Set.of() : index.between(asOf.minus(window), asOf);
  }

  private static String key(final String developer, final String project) {
    return developer + KEY_SEPARATOR + project;
  }

  /**
   * How many elements of {@code sorted} are strictly less than {@code value}.
   *
   * <p>The same shape as {@code Seniority.strictlyLessThan}, and the same reason: it is also the
   * index of the first element that is not, which is what a half-open range needs.
   */
  private static int strictlyBefore(final long[] sorted, final long value) {
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

  /** One (developer, project) pair's commits in order, with volume accumulated along them. */
  private static final class Pair {

    private final long[] authoredAt;
    private final long[] javaPrefix;
    private final long[] allPrefix;

    Pair(final List<Contribution> commits) {
      final List<Contribution> ordered = new ArrayList<>(commits);
      ordered.sort(Comparator.comparing(Contribution::authoredAt)
          .thenComparing(c -> c.revisionHash() == null ? "" : c.revisionHash()));
      this.authoredAt = new long[ordered.size()];
      this.javaPrefix = new long[ordered.size()];
      this.allPrefix = new long[ordered.size()];
      long java = 0;
      long all = 0;
      for (int i = 0; i < ordered.size(); i++) {
        final Contribution c = ordered.get(i);
        // Added plus deleted, never netted: removing a hundred lines is work done and evidence of
        // familiarity with what was removed. Seniority.allLines() states the same choice.
        java += c.javaLinesAdded() + c.javaLinesDeleted();
        all += c.linesAdded() + c.linesDeleted();
        authoredAt[i] = c.authoredAt().toEpochMilli();
        javaPrefix[i] = java;
        allPrefix[i] = all;
      }
    }

    Optional<DeveloperHistory> at(final Instant asOf) {
      final int commits = strictlyBefore(authoredAt, asOf.toEpochMilli());
      if (commits == 0) {
        return Optional.empty();
      }
      final int last = commits - 1;
      final double tenure = (asOf.toEpochMilli() - authoredAt[0]) / 86_400_000.0;
      return Optional.of(
          new DeveloperHistory(javaPrefix[last], allPrefix[last], commits, tenure));
    }
  }

  /** One project's non-merge, non-bot commits in order, for reading a window off the middle. */
  private static final class ProjectIndex {

    private final long[] authoredAt;
    private final String[] canonicalId;

    ProjectIndex(final List<Contribution> commits) {
      final List<Contribution> ordered = new ArrayList<>(commits);
      ordered.sort(Comparator.comparing(Contribution::authoredAt));
      this.authoredAt = new long[ordered.size()];
      this.canonicalId = new String[ordered.size()];
      for (int i = 0; i < ordered.size(); i++) {
        authoredAt[i] = ordered.get(i).authoredAt().toEpochMilli();
        canonicalId[i] = ordered.get(i).canonicalId();
      }
    }

    /** Distinct authors in {@code [from, to)}, in first-commit order so a run is reproducible. */
    Set<String> between(final Instant from, final Instant to) {
      final int start = strictlyBefore(authoredAt, from.toEpochMilli());
      final int end = strictlyBefore(authoredAt, to.toEpochMilli());
      final Set<String> members = new LinkedHashSet<>();
      for (int i = start; i < end; i++) {
        members.add(canonicalId[i]);
      }
      return members;
    }
  }
}
