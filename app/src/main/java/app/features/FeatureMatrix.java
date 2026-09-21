package app.features;

import app.labels.IssueLabel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Turns the stored labels into one predictor row each, every quantity read as of the task's
 * creation.
 *
 * <p>Pure, and separate from the stage that fetches its inputs, for the reason the split is:
 * building the matrix is a decision about definitions, and a definition that can only be exercised
 * against a 686 GB corpus is a definition nobody checks.
 */
public final class FeatureMatrix {

  private FeatureMatrix() {}

  /**
   * The rows, and what could not be observed while building them.
   *
   * @param withoutHistory no commit by this developer in this project before the task, so
   *     {@code LOC_tot} is a real zero
   * @param unknownPair a subset of the above: the ledger holds no commit by this developer in this
   *     project at all, so the zero is a gap rather than a first day
   * @param teamSizes one entry per label, so the report can show a distribution rather than a mean
   * @param withLinkedIssues the corpus recorded the field at all, which is a different question
   *     from {@code withAnyLink}: it records an empty list far more often than it omits one, so
   *     most zeros here are real and only the omissions are missing data
   */
  public record Scan(
      List<FeatureRow> rows,
      int withoutHistory,
      int unknownPair,
      int emptyTeam,
      List<Integer> teamSizes,
      int attributesFound,
      int withDescription,
      int withNonEmptyDescription,
      int withIssueType,
      int withLinkedIssues,
      int withAnyLink) {

    public Scan {
      rows = List.copyOf(rows);
      teamSizes = List.copyOf(teamSizes);
    }
  }

  /**
   * One row per label.
   *
   * @param attributes what the source corpus recorded about each issue at creation, keyed by issue
   *     id; a missing key yields {@link IssueAttributes#missing()} rather than zeros
   */
  public static Scan of(
      final Collection<IssueLabel> labels,
      final AsOfLedger ledger,
      final OpenIssues open,
      final Map<String, IssueAttributes> attributes,
      final Duration teamWindow) {

    final List<FeatureRow> rows = new ArrayList<>(labels.size());
    final List<Integer> teamSizes = new ArrayList<>(labels.size());
    int withoutHistory = 0;
    int unknownPair = 0;
    int emptyTeam = 0;
    int attributesFound = 0;
    int withDescription = 0;
    int withNonEmptyDescription = 0;
    int withIssueType = 0;
    int withLinkedIssues = 0;
    int withAnyLink = 0;

    for (final IssueLabel label : labels) {
      final String developer = label.canonicalAuthorId();
      final String project = label.projectName();
      final Instant at = label.createdAt();

      final Optional<DeveloperHistory> history = ledger.historyAt(developer, project, at);
      final boolean known = ledger.knows(developer, project);
      if (history.isEmpty()) {
        withoutHistory++;
        if (!known) {
          unknownPair++;
        }
      }

      final Set<String> members = ledger.activeTeamAt(project, at, teamWindow);
      final TeamContext team = teamContext(ledger, project, at, members);
      teamSizes.add(members.size());
      if (members.isEmpty()) {
        emptyTeam++;
      }

      final IssueAttributes attribute =
          attributes.getOrDefault(label.issueId(), IssueAttributes.missing());
      if (attribute.found()) {
        attributesFound++;
      }
      if (attribute.descriptionLength().isPresent()) {
        withDescription++;
        if (attribute.descriptionLength().getAsInt() > 0) {
          withNonEmptyDescription++;
        }
      }
      if (attribute.issueType().isPresent()) {
        withIssueType++;
      }
      if (attribute.linkedIssues().isPresent()) {
        withLinkedIssues++;
        if (attribute.linkedIssues().getAsInt() > 0) {
          withAnyLink++;
        }
      }

      rows.add(new FeatureRow(
          label.issueId(),
          project,
          developer,
          at,
          history.orElseGet(() -> DeveloperHistory.none(ledger.complexityAvailable())),
          known,
          label.javaLinesChanged(),
          mean(label.meanMccc(), label.issueId(), "cyclomatic complexity"),
          mean(label.meanNl(), label.issueId(), "nesting level"),
          new Load(
              open.openAt(developer, project, at),
              open.openAcrossProjectsAt(developer, at),
              open.openForTeamAt(members, project, at)),
          team,
          attribute));
    }
    return new Scan(rows, withoutHistory, unknownPair, emptyTeam, teamSizes, attributesFound,
        withDescription, withNonEmptyDescription, withIssueType, withLinkedIssues, withAnyLink);
  }

  /**
   * Means and deviations of the active team's accumulated volume, as of the same instant.
   *
   * <p>A member who committed inside the window brings their whole accumulation, not the window's
   * slice of it: the window bounds who counts as present, not how much they have learnt.
   */
  private static TeamContext teamContext(final AsOfLedger ledger, final String project,
      final Instant at, final Set<String> members) {

    if (members.isEmpty()) {
      return TeamContext.empty();
    }
    final double[] lines = new double[members.size()];
    final double[] complexity = new double[members.size()];
    int i = 0;
    for (final String member : members) {
      final Optional<DeveloperHistory> history = ledger.historyAt(member, project, at);
      lines[i] = history.map(DeveloperHistory::javaLines).orElse(0L);
      complexity[i] = history.flatMap(DeveloperHistory::complexity)
          .map(AccumulatedComplexity::mccc).orElse(0.0);
      i++;
    }
    // The complexity statistics exist only when the per-commit table does. Absent, never zero:
    // an unmeasured team is not a team that touched only simple code.
    return new TeamContext(members.size(), TeamStatistics.of(lines),
        ledger.complexityAvailable() ? TeamStatistics.of(complexity) : Optional.empty());
  }

  /** A label with no recoverable method never leaves the funnel, so absence means stale data. */
  private static double mean(final OptionalDouble value, final String issueId,
      final String what) {

    return value.orElseThrow(() -> new IllegalStateException(
        "label " + issueId + " carries no " + what + ", which the funnel excludes -- "
            + "the label store predates the current funnel and must be rebuilt"));
  }
}
