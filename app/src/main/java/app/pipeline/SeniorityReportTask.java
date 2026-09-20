package app.pipeline;

import app.cost.Contribution;
import app.cost.CostCurve;
import app.cost.CostParams;
import app.cost.ExperienceScope;
import app.cost.Seniority;
import app.cost.port.ContributionLedger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Reads the ledger back and reports what seniority looks like under each scope.
 *
 * <p>Exists because the choice between the two scopes is a modelling decision that has to be
 * defended in the paper, and defending it needs the size of the disagreement rather than an
 * argument about it. Reporting both also keeps the discarded option reproducible: anyone can rerun
 * this and see what project-scoped seniority would have given.
 *
 * <p>Bots are excluded from every population here. They are kept in the ledger — whether automated
 * commits count as volume is revisable — but they are not developers to be hired, so leaving them
 * in the ranking would push real people down a scale that includes actors nobody can select.
 */
public final class SeniorityReportTask {

  /** Thrown when the ledger holds no human contributions, so every population would be empty. */
  public static final class LedgerEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LedgerEmptyException(final String ledger) {
      super(ledger + " holds no contributions -- run build-ledger first");
    }
  }

  private final ContributionLedger ledger;

  public SeniorityReportTask(final ContributionLedger ledger) {
    this.ledger = ledger;
  }

  public Report run(final CostParams params, final ToIntFunction<Contribution> volume) {
    final List<Contribution> all = ledger.readAll().stream().filter(c -> !c.bot()).toList();
    if (all.isEmpty()) {
      throw new LedgerEmptyException(ledger.describe());
    }
    // One instant past the last contribution, so every row is observable and the report describes
    // the corpus as a whole rather than an arbitrary cut through it.
    final Instant asOf = all.stream()
        .map(Contribution::authoredAt).max(Instant::compareTo).orElseThrow().plusMillis(1);

    final Map<String, Double> career =
        new Seniority(ExperienceScope.CAREER, volume).at(all, null, asOf);

    final Set<String> projects = new HashSet<>();
    all.forEach(c -> projects.add(c.projectId()));

    final Seniority perProject = new Seniority(ExperienceScope.PROJECT, volume);
    final Map<String, Map<String, Double>> project = new HashMap<>();
    for (final String projectId : projects) {
      project.put(projectId, perProject.at(all, projectId, asOf));
    }

    final List<Double> gaps = new ArrayList<>();
    for (final Map<String, Double> ranks : project.values()) {
      ranks.forEach((developer, q) -> {
        final Double global = career.get(developer);
        if (global != null) {
          gaps.add(Math.abs(q - global));
        }
      });
    }
    gaps.sort(Double::compare);

    final CostCurve curve = new CostCurve(params);
    final List<Double> costs = career.values().stream().map(curve::at).sorted().toList();

    return new Report(
        asOf, all.size(), career.size(), projects.size(),
        sorted(career.values()), gaps, costs, params);
  }

  private static List<Double> sorted(final Collection<Double> values) {
    return values.stream().sorted().toList();
  }

  /**
   * @param careerRanks q under career scope, sorted
   * @param scopeGaps |q_project - q_career| over every (project, developer) pair, sorted
   * @param costs cost under career-scoped q at the reported parameters, sorted
   */
  public record Report(
      Instant asOf,
      int contributions,
      int developers,
      int projects,
      List<Double> careerRanks,
      List<Double> scopeGaps,
      List<Double> costs,
      CostParams params) {

    public static double quantile(final List<Double> sortedValues, final double p) {
      if (sortedValues.isEmpty()) {
        return Double.NaN;
      }
      return sortedValues.get((int) Math.round(p * (sortedValues.size() - 1)));
    }
  }
}
