package app.pipeline;

import app.labels.IssueLabel;
import app.pullrequests.Extractor;
import app.pullrequests.Population;
import app.pullrequests.Row;
import app.pullrequests.Tables;
import app.pullrequests.Unit;
import app.pullrequests.port.TableSource;
import app.pullrequests.port.UnitSource;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes every pre-merge construct for the whole population, once.
 *
 * <p>The tables are loaded once and the extractor is pure, so this is a single pass over the
 * population against data already in memory. Nothing is written here: the task returns the rows and
 * the coverage, and a separate stage decides whether they are worth storing.
 */
public final class BuildAttributesTask {

  /** Yu's three-month window, the same one the hotness denominator is counted over. */
  private static final long NINETY_DAYS = 90L * 86_400_000L;

  /** Named so a caller can tell an empty population from every other kind of failure. */
  public static final class PopulationEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    PopulationEmptyException(final String source) {
      super(source + " holds no units -- point UNITS_DB at a database where pr-labels has run");
    }
  }

  /**
   * What a column looks like over the population.
   *
   * @param validZero rows whose value is a real zero. Counted apart from {@code absent} because
   *     conflating them is the specific error the specification names twice
   */
  public record Coverage(String column, int withValue, int absent, int validZero,
      int absentTrain, int absentTest) {}

  /**
   * @param hotnessWithoutDenominator rows whose project recorded no commit in the ninety days
   *     before {@code t0}. Reported apart from the next one because they are different facts about
   *     the corpus, and because {@code area_hotness_90d} is absent on a third of the population --
   *     a number that has to be explained, not merely printed
   * @param hotnessWithoutPaths rows whose consolidated diff lists no file at all
   */
  public record Report(
      Population.Report population,
      List<Row> rows,
      Map<String, Coverage> coverage,
      int unresolvedProject,
      int hotnessWithoutDenominator,
      int hotnessWithoutPaths) {}

  private final UnitSource units;
  private final TableSource tables;
  private final Progress progress;

  public BuildAttributesTask(final UnitSource units, final TableSource tables,
      final Progress progress) {

    this.units = units;
    this.tables = tables;
    this.progress = progress;
  }

  /**
   * @param minResolved the inherited-history rule; zero turns it off, which is this study's frozen
   *     population. Passed through rather than assumed, and recorded on the report
   */
  public Report run(final double trainShare, final int minResolved) {
    final List<IssueLabel> labels = units.readAll();
    if (labels.isEmpty()) {
      throw new PopulationEmptyException(units.describe());
    }
    final Population.Report population = Population.of(labels,
        units.creatorByUnit(labels.stream().map(IssueLabel::issueId).toList()),
        trainShare, minResolved);

    final Tables loaded = tables.load(labels.stream().map(IssueLabel::issueId).toList());

    // Train and test together: every family and every profile is scored on the same identifiers,
    // and a row is never extracted twice under two different sets of tables.
    final List<Unit> usable = new ArrayList<>(population.train());
    usable.addAll(population.test());

    final List<Row> rows = new ArrayList<>(usable.size());
    int done = 0;
    int noDenominator = 0;
    int noPaths = 0;
    for (final Unit unit : usable) {
      rows.add(Extractor.of(unit, loaded));
      final long t0 = unit.openedAt().toEpochMilli();
      if (loaded.commitsOfProject(unit.project(), t0 - NINETY_DAYS, t0) == 0) {
        noDenominator++;
      }
      if (loaded.pathsOf(unit.pullRequestId()).isEmpty()) {
        noPaths++;
      }
      if (++done % 1_000 == 0) {
        progress.count(done, usable.size(), "extracting");
      }
    }
    // Pull requests whose project could not be resolved. Their history still counts for their
    // creator, but they are indexed under "?" and cannot contribute to any project's tables.
    final java.util.Set<String> onTrain = new java.util.HashSet<>(population.train().size() * 2);
    population.train().forEach(unit -> onTrain.add(unit.pullRequestId()));
    return new Report(population, List.copyOf(rows), coverageOf(rows, onTrain),
        loaded.prsOfProject("?").size(), noDenominator, noPaths);
  }

  /**
   * Coverage per column, split by partition.
   *
   * <p>Split because absence is not spread evenly over time: the corpus's commit mining thins out
   * sharply after 2018 while the population peaks in 2019 and 2020, so a construct that reads
   * commit history is missing far more often on the test side than on the training side. A single
   * total would hide that, and the difference between the two sides is what decides whether a
   * construct can be used at all.
   */
  private static Map<String, Coverage> coverageOf(final List<Row> rows,
      final java.util.Set<String> onTrain) {

    final Map<String, Coverage> coverage = new LinkedHashMap<>();
    for (final String column : Extractor.PRODUCED) {
      int withValue = 0;
      int absent = 0;
      int validZero = 0;
      int absentTrain = 0;
      int absentTest = 0;
      for (final Row row : rows) {
        if (row.isAbsent(column)) {
          absent++;
          if (onTrain.contains(row.unitId())) {
            absentTrain++;
          } else {
            absentTest++;
          }
          continue;
        }
        final Double value = row.values().get(column);
        if (value == null) {
          continue;
        }
        withValue++;
        if (value == 0.0) {
          validZero++;
        }
      }
      coverage.put(column, new Coverage(column, withValue, absent, validZero,
          absentTrain, absentTest));
    }
    return Map.copyOf(coverage);
  }
}
