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

  /** The rule in words, for the help text and the run records. */
  public static final String RULE = "temporal: the candidates ordered by their first commit, "
      + "the issue id breaking ties; the cut is the instant at position ceil(0.7 n), from 1; "
      + "training is every first commit at or before the cut, ties included, and test the rest; "
      + "the same rule on the training side gives the inner fitting and the inner validation; "
      + "no stratification by project; no issue is set aside";

  /**
   * The revised design's rule in words, for the help text and the run records: the same sides,
   * its crossing issues out of what the training side teaches, every history point-in-time. Never
   * the words of {@link #RULE}, so a run of either design is told apart by them alone.
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
   * Where the matrix's sides under {@code population} are not this rule's, drawn again over the
   * matrix's own issues from their start instant ({@link IssueUnit#startedAt}, the first commit
   * that counts in a first-commit matrix); empty when every one is. Reads each row's id, project,
   * start and sides, nothing else: no value and no outcome. A row without a start is a
   * difference of its own. As with {@link ProjectSplit#differences}, a matrix missing issues is
   * drawn again over those it holds, so this check does not prove it holds the whole population.
   */
  public static List<String> differences(final List<RowCache.Entry> entries,
      final Population population) {
    final List<Candidate> candidates = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      if (e.unit().startedAt() == null) {
        return List.of(e.unit().issueId() + " has no first-commit instant; the temporal split "
            + "cannot be checked on this matrix");
      }
      candidates.add(new Candidate(e.unit().issueId(), e.unit().project(), e.unit().startedAt()));
    }
    final ProjectSplit.Parts parts;
    final Cuts cuts;
    try {
      parts = draw(candidates);
      cuts = cuts(candidates);
    } catch (final IllegalArgumentException e) {
      return List.of(e.getMessage() + "; the temporal split cannot be checked on this matrix");
    }
    return ProjectSplit.differences(entries, population, parts,
        "temporal, cuts " + cuts.outer() + " and " + cuts.inner());
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
