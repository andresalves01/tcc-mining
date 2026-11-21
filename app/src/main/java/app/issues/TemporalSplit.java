package app.issues;

import app.issues.SplitRule.Candidate;
import app.issues.Universe.Part;
import app.issues.Universe.Population;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The first-commit study's temporal split: training is what had started by the cut, test every
 * other candidate.
 *
 * <p><b>Rule.</b> The candidates ordered by the instant of their first commit, the issue id
 * breaking ties. The cut is the instant at position {@code ceil(0.7 n)} of that order, counted
 * from 1, taken as {@code (7 n + 9) / 10} in whole numbers so no floating-point product decides it.
 * Training is every candidate whose instant is at or before the cut, so every one tied at the cut
 * goes to training and training may pass 70%; test is the rest. The inner split is the same rule
 * on the training side. No stratification by project: a project may be on one side alone. No
 * candidate is set aside: an issue whose first resolution comes after the cut stays in training.
 *
 * <p>Ties are broken by the id for a fixed order only: candidates tied at one instant fall on the
 * same side whatever their order, so the sides do not depend on the order they are given in.
 */
public final class TemporalSplit {

  /** The share of the candidates up to the cut, in tenths: position {@code ceil(0.7 n)}. */
  public static final int TENTHS = 7;

  /**
   * The revised design's rule in words, for the help text and the run records: the temporal
   * sides, its crossing issues out of what the training side teaches, every history
   * point-in-time.
   */
  public static final String REVISED_RULE = "temporal, revised: the candidates ordered by their "
      + "first commit, the issue id breaking ties; the cut is the instant at position "
      + "ceil(0.7 n), from 1; training is every first commit at or before the cut, ties included, "
      + "and test the rest; the same rule on the training side gives the inner fitting and the "
      + "inner validation; no stratification by project; a training issue whose first resolution "
      + "comes after its level's cut crosses it and teaches nothing at that level, while "
      + "validation and test keep every issue; every history reads only the training issues "
      + "resolved strictly before the first commit of the issue it describes";

  /**
   * The instants of the two cuts.
   *
   * @param outer the cut of the candidates; null when there are none
   * @param inner the cut of the training side; null when it is empty
   */
  public record Cuts(Instant outer, Instant inner) {}

  private static final Comparator<Candidate> ORDER =
      Comparator.comparing(Candidate::firstCommit).thenComparing(Candidate::id);

  private TemporalSplit() {}

  /** The sides of {@code candidates}: outer, and inner over the outer training. */
  public static ProjectSplit.Parts draw(final Collection<Candidate> candidates) {
    final List<Candidate> order = ordered(candidates);
    final Map<String, Part> outer = new HashMap<>(order.size() * 2);
    final List<Candidate> training = side(order, outer);
    final Map<String, Part> inner = new HashMap<>(training.size() * 2);
    side(training, inner);
    return new ProjectSplit.Parts(Map.copyOf(outer), Map.copyOf(inner));
  }

  /**
   * The revised design's sides of {@code candidates}: the sides of {@link #draw}, and on each
   * training side the issues crossing its cut, those whose first resolution comes strictly after
   * it; a resolution at the cut itself was known there and does not cross. The outer training's
   * issues cross the outer cut, the inner fitting's the inner one, and an inner fitting issue
   * crossing the outer cut crosses the inner one too, which comes no later. The first resolution
   * is read of the training side alone and required there: a test candidate's is never read.
   */
  public static ProjectSplit.Parts drawRevised(final Collection<Candidate> candidates) {
    final ProjectSplit.Parts parts = draw(candidates);
    final Cuts cuts = cuts(candidates);
    final Set<String> outer = new HashSet<>();
    final Set<String> inner = new HashSet<>();
    for (final Candidate c : candidates) {
      if (parts.outer().get(c.id()) != Part.TRAIN) {
        continue;
      }
      if (c.firstResolution() == null) {
        throw new IllegalArgumentException(c.id() + " is on the training side without its first "
            + "resolution");
      }
      if (c.firstResolution().isAfter(cuts.outer())) {
        outer.add(c.id());
      }
      if (parts.inner().get(c.id()) == Part.TRAIN && c.firstResolution().isAfter(cuts.inner())) {
        inner.add(c.id());
      }
    }
    return new ProjectSplit.Parts(parts.outer(), parts.inner(), outer, inner);
  }

  /** The outer cut, over {@code candidates}, and the inner one, over its training side. */
  public static Cuts cuts(final Collection<Candidate> candidates) {
    final List<Candidate> order = ordered(candidates);
    final Instant outer = cut(order);
    final List<Candidate> training = outer == null ? List.of()
        : order.stream().filter(c -> !c.firstCommit().isAfter(outer)).toList();
    return new Cuts(outer, cut(training));
  }

  /**
   * The projects with issues on the test side of {@code sides} and none on its training side,
   * in order of name; {@code projects} maps each issue id to its project.
   */
  public static Set<String> newProjects(final Map<String, String> projects,
      final Map<String, Part> sides) {
    final Set<String> seen = new HashSet<>();
    final Set<String> tested = new TreeSet<>();
    sides.forEach((id, part) -> (part == Part.TRAIN ? seen : tested).add(projects.get(id)));
    tested.removeAll(seen);
    return tested;
  }

  /**
   * Where the matrix's verdicts under {@code population} are not the revised design's ({@link
   * #drawRevised}), drawn again over the matrix's own issues; empty when every one is. Reads each
   * row's id, project, start and verdicts and, of the issues the rule puts on the outer training
   * side alone, the first resolution ({@link IssueUnit#resolvedAt}), which decides who crosses a
   * cut: no value, and nothing of the outer test but its start. A row without a start, and a
   * training row without its first resolution, is a difference of its own. A matrix of the
   * temporal design of 02/11/2026 fails here on its crossing rows.
   */
  public static List<String> differencesRevised(final List<RowCache.Entry> entries,
      final Population population) {
    final List<Candidate> started = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      if (e.unit().startedAt() == null) {
        return List.of(e.unit().issueId() + " has no first-commit instant; the revised split "
            + "cannot be checked on this matrix");
      }
      started.add(new Candidate(e.unit().issueId(), e.unit().project(), e.unit().startedAt()));
    }
    final ProjectSplit.Parts parts;
    final Cuts cuts;
    try {
      final Map<String, Part> sides = draw(started).outer();
      final List<Candidate> candidates = new ArrayList<>(entries.size());
      for (final RowCache.Entry e : entries) {
        final IssueUnit u = e.unit();
        if (sides.get(u.issueId()) != Part.TRAIN) {
          candidates.add(new Candidate(u.issueId(), u.project(), u.startedAt()));
        } else if (u.resolvedAt() == null) {
          return List.of(u.issueId() + " is on the training side by the rule but has no first "
              + "resolution, and the matrix's verdict for it is "
              + verdictText(e.outer(population)) + " (a row the matrix marks TEST has its dates "
              + "unread); the revised split cannot be checked on this matrix");
        } else {
          candidates.add(new Candidate(u.issueId(), u.project(), u.startedAt(), u.resolvedAt()));
        }
      }
      parts = drawRevised(candidates);
      cuts = cuts(candidates);
    } catch (final IllegalArgumentException e) {
      return List.of(e.getMessage() + "; the revised split cannot be checked on this matrix");
    }
    return ProjectSplit.differences(entries, population, parts,
        "temporal, revised, cuts " + cuts.outer() + " and " + cuts.inner());
  }

  private static String verdictText(final String verdict) {
    return verdict.isEmpty() ? "none" : verdict;
  }

  /** The candidates in the rule's order; refuses one without an instant and a repeated id. */
  private static List<Candidate> ordered(final Collection<Candidate> candidates) {
    final Set<String> ids = new HashSet<>(candidates.size() * 2);
    for (final Candidate c : candidates) {
      if (c.firstCommit() == null) {
        throw new IllegalArgumentException(c.id() + " has no first-commit instant");
      }
      if (!ids.add(c.id())) {
        throw new IllegalArgumentException(c.id() + " is a candidate twice");
      }
    }
    return candidates.stream().sorted(ORDER).toList();
  }

  /** The cut of candidates already in order: the instant at position ceil(0.7 n), from 1. */
  private static Instant cut(final List<Candidate> order) {
    if (order.isEmpty()) {
      return null;
    }
    final int position = (TENTHS * order.size() + 9) / 10;
    return order.get(position - 1).firstCommit();
  }

  /** One split of candidates in order into {@code out}; its training side, still in order. */
  private static List<Candidate> side(final List<Candidate> order, final Map<String, Part> out) {
    final Instant cut = cut(order);
    final List<Candidate> training = new ArrayList<>();
    for (final Candidate c : order) {
      final boolean train = !c.firstCommit().isAfter(cut);
      out.put(c.id(), train ? Part.TRAIN : Part.TEST);
      if (train) {
        training.add(c);
      }
    }
    return training;
  }
}
