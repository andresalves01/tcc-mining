package app.features;

import app.labels.IssueLabel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much work was already underway when a task arrived, counted over the labels themselves.
 *
 * <p>A label is open at an instant when it was created strictly before it and resolved strictly
 * after it. The strict lower bound is what keeps a task out of its own load: at {@code t_i} the
 * issue has just been created, so it is not yet one of the things its developer was already
 * carrying.
 *
 * <p><b>Two consequences to declare rather than hide.</b> A label's developer is the author of its
 * first linked commit, so "assigned to" here means "eventually worked on by", which is known only
 * in retrospect. And the population is the labels alone, so issues that never produced Java code,
 * or that were never resolved, are invisible -- the count is a lower bound on real load, uniformly
 * so across developers but not necessarily across projects.
 *
 * <p>Because each label has exactly one developer, a team's count cannot double-count an issue the
 * way the study's "without duplication" guards against: there is no issue held by two members at
 * once to begin with.
 *
 * <p>The lookup is a linear scan of one developer's labels. A median team is seven people and a
 * pair holds tens of labels, so an interval tree would buy nothing and cost the reader something.
 */
public final class OpenIssues {

  private final Map<String, List<long[]>> byPair;
  private final Map<String, List<long[]>> byDeveloper;

  public OpenIssues(final Collection<IssueLabel> labels) {
    this.byPair = new HashMap<>(1 << 12);
    this.byDeveloper = new HashMap<>(1 << 12);
    for (final IssueLabel label : labels) {
      final long[] interval = {
          label.createdAt().toEpochMilli(), label.resolvedAt().toEpochMilli()};
      byPair.computeIfAbsent(key(label.canonicalAuthorId(), label.projectName()),
          k -> new ArrayList<>()).add(interval);
      byDeveloper.computeIfAbsent(label.canonicalAuthorId(), k -> new ArrayList<>()).add(interval);
    }
  }

  /** Open labels held by {@code developer} in {@code project} at {@code at}. */
  public int openAt(final String developer, final String project, final Instant at) {
    return count(byPair.get(key(developer, project)), at.toEpochMilli());
  }

  /** The same across every project, which the study scopes the individual component away from. */
  public int openAcrossProjectsAt(final String developer, final Instant at) {
    return count(byDeveloper.get(developer), at.toEpochMilli());
  }

  /** Open labels held by anyone in {@code team}, within {@code project}. */
  public int openForTeamAt(
      final Collection<String> team, final String project, final Instant at) {

    int open = 0;
    for (final String member : team) {
      open += openAt(member, project, at);
    }
    return open;
  }

  private static int count(final List<long[]> intervals, final long at) {
    if (intervals == null) {
      return 0;
    }
    int open = 0;
    for (final long[] interval : intervals) {
      if (interval[0] < at && interval[1] > at) {
        open++;
      }
    }
    return open;
  }

  private static String key(final String developer, final String project) {
    return developer + '\u0000' + project;
  }
}
