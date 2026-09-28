package app.paper6;

import app.labels.IssueLabel;
import app.labels.SplitPart;
import app.labels.TemporalSplit;
import app.paper6.port.IssueUnitSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which issues the transposition fits on, and what each partition holds.
 *
 * <p><b>One exclusion, and only one</b>, by André's instruction of 27/09/2026: an issue resolved
 * before the first commit linked to it -- resolved outside the lifecycle of its branch -- is left
 * out, because its duration does not measure the work it is labelled with. Nothing else is
 * filtered. Very short and very long issues stay; how the models do on them is read from the
 * results by percentile, not settled by removing them. No label has its first commit before its
 * creation, so the lifecycle has no violation on the other end to exclude.
 *
 * <p>The exclusion comes <b>before</b> the split, so the cut is the quantile of the population that
 * is actually studied. {@link #MIN_RESOLVED} is fixed at zero: no inherited-history rule, the same
 * frozen choice as the pull-request study, which also makes the split independent of whose history
 * a row would be keyed on.
 */
public final class Population {

  public static final int MIN_RESOLVED = 0;

  public record Group(int rows, int projects, int reporters) {

    static Group of(final List<IssueUnit> units) {
      final Set<String> projects = new HashSet<>();
      final Set<String> reporters = new HashSet<>();
      for (final IssueUnit unit : units) {
        projects.add(unit.project());
        reporters.add(unit.reporterId());
      }
      return new Group(units.size(), projects.size(), reporters.size());
    }
  }

  /**
   * @param resolvedBeforeCommit the excluded issues, counted
   * @param reproduction how many labels the split rule places somewhere other than the partition
   *     the thesis pipeline stored, when run on the unfiltered labels with that pipeline's own
   *     setting. Zero means this code reads the same labels and cuts them the same way
   * @param innerParts the inner verdict for each row of the outer training side
   */
  public record Report(
      int labels,
      int resolvedBeforeCommit,
      int reporterFromCreator,
      int reporterUnknown,
      Instant outerCut,
      Instant innerCut,
      Map<SplitPart, Group> byPart,
      List<IssueUnit> train,
      List<IssueUnit> test,
      Map<String, SplitPart> outerParts,
      Map<String, SplitPart> innerParts,
      int reproduction,
      int reproductionMinResolved) {}

  private Population() {}

  public static Report of(final List<IssueUnitSource.Label> labels, final double trainShare) {
    final List<IssueLabel> kept = new ArrayList<>();
    final Map<String, IssueUnitSource.Label> byId = new LinkedHashMap<>();
    int excluded = 0;
    int fromCreator = 0;
    int unknown = 0;
    for (final IssueUnitSource.Label label : labels) {
      final IssueLabel l = label.label();
      if (l.resolvedAt().isBefore(l.firstCommitAt())) {
        excluded++;
        continue;
      }
      kept.add(l);
      byId.put(l.issueId(), label);
      if (label.reporterFromCreator()) {
        fromCreator++;
      }
      if (label.reporterId().isEmpty()) {
        unknown++;
      }
    }

    final TemporalSplit.Split split = TemporalSplit.of(kept, trainShare, MIN_RESOLVED);
    final Map<SplitPart, List<IssueUnit>> grouped = new EnumMap<>(SplitPart.class);
    for (final SplitPart part : SplitPart.values()) {
      grouped.put(part, new ArrayList<>());
    }
    for (final IssueLabel l : kept) {
      grouped.get(split.parts().get(l.issueId())).add(unit(byId.get(l.issueId())));
    }
    final Map<SplitPart, Group> byPart = new LinkedHashMap<>();
    grouped.forEach((part, units) -> byPart.put(part, Group.of(units)));

    // The reproduction gate: the thesis pipeline's own rule, on its own unfiltered labels.
    final int thesisMinResolved = 5;
    final TemporalSplit.Split thesis = TemporalSplit.of(
        labels.stream().map(IssueUnitSource.Label::label).toList(), trainShare, thesisMinResolved);
    int mismatches = 0;
    for (final IssueUnitSource.Label label : labels) {
      final SplitPart part = thesis.parts().get(label.label().issueId());
      if (!part.name().equals(label.storedSplit())) {
        mismatches++;
      }
    }

    return new Report(labels.size(), excluded, fromCreator, unknown, split.outer().cut(),
        split.inner().cut(), Map.copyOf(byPart),
        List.copyOf(grouped.get(SplitPart.TRAIN)), List.copyOf(grouped.get(SplitPart.TEST)),
        split.parts(), split.innerParts(), mismatches, thesisMinResolved);
  }

  static IssueUnit unit(final IssueUnitSource.Label label) {
    final IssueLabel l = label.label();
    return new IssueUnit(l.issueId(), label.key(), l.projectName(), label.reporterId(),
        l.canonicalAuthorId(), l.createdAt(), l.resolvedAt(), l.firstCommitAt(), l.commits(),
        l.javaLinesAdded(), l.javaLinesDeleted());
  }
}
