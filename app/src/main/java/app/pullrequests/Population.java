package app.pullrequests;

import app.labels.IssueLabel;
import app.labels.SplitPart;
import app.labels.TemporalSplit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which pull requests this study fits on, and what each partition is made of.
 *
 * <p>Pure: it takes the labels and the creator of each one, and computes. Nothing here reads a
 * database, so the whole population report is a unit test away from being checked against the
 * numbers the author registered.
 *
 * <p><b>The inherited-history rule is off, and that is a decision, not a default.</b> It is passed
 * in rather than assumed so that both populations stay reportable from the same code:
 * {@code minResolved = 0} is the frozen specification of this study (see
 * {@code DECISOES-FECHADAS.md} §1), and any positive value reproduces the earlier pull-request study's rule for
 * comparison. Choosing between them after seeing a score is what the pre-registration forbids, so
 * the value arrives from configuration and is recorded next to every result.
 *
 * <p>A label that crosses the cut is excluded from the usable set by the split itself, for the
 * reason that type documents: its outcome was not known at the cut, so training on it would use
 * the future and testing on it would test a task that was not new.
 */
public final class Population {

  /**
   * One partition, described the three ways the population table reports it.
   *
   * @param creators distinct {@code creator_id}, which is this study's unit of person. Not the
   *     canonical author the split's own {@code eligibleDevelopers} counts
   */
  public record Group(int rows, int projects, int creators) {

    static Group of(final List<Unit> units) {
      final Set<String> projects = new HashSet<>();
      final Set<String> creators = new HashSet<>();
      for (final Unit unit : units) {
        projects.add(unit.project());
        creators.add(unit.creatorId());
      }
      return new Group(units.size(), projects.size(), creators.size());
    }
  }

  /**
   * The whole population, partitioned.
   *
   * @param usable {@code TRAIN} plus {@code TEST}: every row a model or the baseline may see
   * @param byPart every partition the outer cut produced, including the empty ones, so a reader
   *     can tell "nothing landed here" from "this was not reported"
   */
  public record Report(
      Instant outerCut,
      Instant innerCut,
      int minResolved,
      double trainShare,
      Map<SplitPart, Group> byPart,
      Group usable,
      List<Unit> train,
      List<Unit> test,
      Map<String, SplitPart> innerParts) {}

  private Population() {}

  /**
   * @param labels every unit, as stored by the stage that built them
   * @param creatorByUnit {@code pull_request.creator_id} for each, joined from the source corpus
   * @throws MissingCreatorException when a label has no creator, because a row this study cannot
   *     attribute is not a row it may quietly drop
   */
  public static Report of(final List<IssueLabel> labels, final Map<String, String> creatorByUnit,
      final double trainShare, final int minResolved) {

    final TemporalSplit.Split split = TemporalSplit.of(labels, trainShare, minResolved);
    final List<String> missing = new ArrayList<>();
    final Map<SplitPart, List<Unit>> grouped = new EnumMap<>(SplitPart.class);
    for (final SplitPart part : SplitPart.values()) {
      grouped.put(part, new ArrayList<>());
    }
    for (final IssueLabel label : labels) {
      final String creator = creatorByUnit.get(label.issueId());
      if (creator == null) {
        missing.add(label.issueId());
        continue;
      }
      grouped.get(split.parts().get(label.issueId())).add(new Unit(label.issueId(),
          label.projectName(), creator, label.createdAt(), label.resolvedAt()));
    }
    if (!missing.isEmpty()) {
      throw new MissingCreatorException(missing);
    }

    final Map<SplitPart, Group> byPart = new LinkedHashMap<>();
    grouped.forEach((part, units) -> byPart.put(part, Group.of(units)));
    final List<Unit> usable = new ArrayList<>(grouped.get(SplitPart.TRAIN));
    usable.addAll(grouped.get(SplitPart.TEST));

    return new Report(split.outer().cut(), split.inner().cut(), minResolved, trainShare,
        Map.copyOf(byPart), Group.of(usable),
        List.copyOf(grouped.get(SplitPart.TRAIN)), List.copyOf(grouped.get(SplitPart.TEST)),
        split.innerParts());
  }

  /** Named so a caller can tell a join that came up short from any other kind of failure. */
  public static final class MissingCreatorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> unitIds;

    MissingCreatorException(final List<String> unitIds) {
      super(unitIds.size() + " units have no creator_id in the source corpus -- this study keys on "
          + "pull_request.creator_id and cannot attribute them; first: " + unitIds.get(0));
      this.unitIds = List.copyOf(unitIds);
    }

    public List<String> unitIds() {
      return unitIds;
    }
  }
}
