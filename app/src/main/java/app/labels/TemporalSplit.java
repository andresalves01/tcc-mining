package app.labels;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trains on the past and tests on the future, with the cut taken from the data rather than chosen.
 *
 * <p>The cut is the quantile of the issues' <b>creation</b> instants, because creation is the
 * moment the prediction is anchored to: at that instant a manager would be deciding who takes the
 * work, and everything the model may use has to already exist.
 *
 * <p>An issue created before the cut but resolved after it belongs to neither side. Training on it
 * would use an outcome that was not known at the cut, and testing on it would test a task that was
 * not new. It is excluded and counted, so the effective proportion is reported rather than assumed.
 *
 * <p><b>Enough history means enough history here.</b> Eligibility is counted per
 * {@code (developer, project)} pair rather than per developer. Four resolutions in one project and
 * one in another are not five in either: what they qualify a developer for is familiarity with the
 * codebase under change, and the individual median the evaluation compares against is likewise a
 * claim about that developer in that project.
 *
 * <p>The inner split has nothing to do with evaluation. Elastic Net has to choose its regularisation
 * somehow, and choosing it against the test set would consume the one look at the future the design
 * allows. So the same rule is applied again inside the training side, eligibility rebuilt from
 * scratch against the earlier instant: a pair holding five resolutions by the outer cut need not
 * have held them by the inner one, and carrying the outer verdict inward would credit it with work
 * it had not yet done.
 */
public final class TemporalSplit {

  /** Eligibility is a claim about a person in a project, so the key is the pair. */
  private record Pair(String developer, String project) {}

  /**
   * One cut, and everything it separates.
   *
   * <p>The two insufficient-history groups are kept apart because they are read differently: on the
   * past side the rule removes labels the model could have learned from, on the future side it
   * removes tasks it would have been asked about. Their sizes have no reason to be alike, and
   * summing them would hide exactly that.
   */
  public record Side(Instant cut, int eligiblePairs, int eligibleDevelopers,
      List<IssueLabel> past, List<IssueLabel> future, List<IssueLabel> crossing,
      List<IssueLabel> insufficientPast, List<IssueLabel> insufficientFuture) {

    public double pastShare() {
      final long total = past.size() + (long) future.size();
      return total == 0 ? 0 : (double) past.size() / total;
    }

    public static int projects(final List<IssueLabel> labels) {
      return (int) labels.stream().map(IssueLabel::projectName).distinct().count();
    }

    public long total() {
      return past.size() + (long) future.size() + crossing.size()
          + insufficientPast.size() + insufficientFuture.size();
    }
  }

  /**
   * Where every label landed, under both cuts.
   *
   * @param parts the outer verdict, over all labels: training side, test side, or excluded
   * @param innerParts the inner verdict, over the outer training side only. A label absent here
   *     never reached that side. Recomputed against the earlier instant rather than inherited, so
   *     a label that is {@code TRAIN} outside can be {@code INSUFFICIENT_HISTORY} inside — its
   *     author had not yet done the work that made them eligible.
   */
  public record Split(Side outer, Side inner, Map<String, SplitPart> parts,
      Map<String, SplitPart> innerParts) {}

  private TemporalSplit() {}

  public static Split of(final List<IssueLabel> labels, final double trainShare,
      final int minResolved) {

    if (labels.isEmpty()) {
      throw new IllegalArgumentException("no labels to split");
    }
    final Map<String, SplitPart> parts = new LinkedHashMap<>();
    final Map<String, SplitPart> innerParts = new LinkedHashMap<>();
    final Side outer = cutAt(labels, quantileOfCreation(labels, trainShare), minResolved, parts);
    final Side inner = cutAt(outer.past(),
        quantileOfCreation(outer.past(), trainShare), minResolved, innerParts);
    return new Split(outer, inner, Map.copyOf(parts), Map.copyOf(innerParts));
  }

  /** @param parts when given, records where each label landed under this cut */
  private static Side cutAt(final List<IssueLabel> labels, final Instant cut, final int minResolved,
      final Map<String, SplitPart> parts) {

    final Set<Pair> eligible = eligiblePairs(labels, cut, minResolved);
    final List<IssueLabel> past = new ArrayList<>();
    final List<IssueLabel> future = new ArrayList<>();
    final List<IssueLabel> crossing = new ArrayList<>();
    final List<IssueLabel> insufficientPast = new ArrayList<>();
    final List<IssueLabel> insufficientFuture = new ArrayList<>();

    for (final IssueLabel label : labels) {
      final boolean createdBefore = label.createdAt().isBefore(cut);
      final SplitPart part = classify(label, cut, eligible);
      if (parts != null) {
        parts.put(label.issueId(), part);
      }
      switch (part) {
        case TRAIN -> past.add(label);
        case TEST -> future.add(label);
        case CROSSES_CUT -> crossing.add(label);
        case INSUFFICIENT_HISTORY -> (createdBefore ? insufficientPast : insufficientFuture)
            .add(label);
        default -> throw new IllegalStateException(part.name());
      }
    }
    return new Side(cut, eligible.size(), distinctDevelopers(eligible), List.copyOf(past),
        List.copyOf(future), List.copyOf(crossing), List.copyOf(insufficientPast),
        List.copyOf(insufficientFuture));
  }

  /**
   * Pairs holding at least {@code minResolved} labels resolved strictly before the cut.
   *
   * <p>Resolution alone decides it. A label created before the cut but still open at it taught
   * nobody anything by that instant, so it cannot count towards the history that makes its author
   * eligible.
   */
  private static Set<Pair> eligiblePairs(final List<IssueLabel> labels, final Instant cut,
      final int minResolved) {

    final Map<Pair, Long> resolved = new HashMap<>();
    for (final IssueLabel label : labels) {
      if (label.resolvedAt().isBefore(cut)) {
        resolved.merge(pairOf(label), 1L, Long::sum);
      }
    }
    final Set<Pair> eligible = new HashSet<>();
    resolved.forEach((pair, count) -> {
      if (count >= minResolved) {
        eligible.add(pair);
      }
    });
    return eligible;
  }

  /**
   * Crossing the cut is decided before the author's history is consulted, because it is a property
   * of the issue and not of the person: an issue whose outcome was still unknown at the cut is
   * unusable no matter who wrote it.
   */
  private static SplitPart classify(final IssueLabel label, final Instant cut,
      final Set<Pair> eligible) {

    if (label.createdAt().isBefore(cut) && !label.resolvedAt().isBefore(cut)) {
      return SplitPart.CROSSES_CUT;
    }
    if (!eligible.contains(pairOf(label))) {
      return SplitPart.INSUFFICIENT_HISTORY;
    }
    return label.createdAt().isBefore(cut) ? SplitPart.TRAIN : SplitPart.TEST;
  }

  private static Pair pairOf(final IssueLabel label) {
    return new Pair(label.canonicalAuthorId(), label.projectName());
  }

  private static int distinctDevelopers(final Set<Pair> pairs) {
    return (int) pairs.stream().map(Pair::developer).distinct().count();
  }

  private static Instant quantileOfCreation(final List<IssueLabel> labels, final double share) {
    final List<Instant> created = labels.stream().map(IssueLabel::createdAt).sorted().toList();
    return created.get((int) (share * (created.size() - 1)));
  }
}
