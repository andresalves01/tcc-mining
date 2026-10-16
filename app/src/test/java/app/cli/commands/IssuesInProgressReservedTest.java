package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Attribute;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.study.Row;
import app.pipeline.IssuesEvaluationTask;
import app.pipeline.IssuesInProgressExtractTask;
import app.pipeline.port.Progress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The secondary study's reserved run, on a corpus small enough to check by hand. */
class IssuesInProgressReservedTest {

  private static final String CELL = "ALZUBAIDI / C1";

  /**
   * Six projects; per project 20 inner-fit, 10 inner-selection, 10 test and 2 crossing rows, each
   * started an hour after its creation; every fifth row paused.
   */
  private static List<RowCache.Entry> corpus() {
    final Random random = new Random(11);
    final Instant created = Instant.parse("2015-01-01T00:00:00Z");
    final Instant start = created.plusSeconds(3600);
    final List<Attribute> columns = Cell.byLabel(CELL, Population.IN_PROGRESS).attributes();
    final List<RowCache.Entry> out = new ArrayList<>();
    int n = 0;
    for (int p = 0; p < 6; p++) {
      for (int k = 0; k < 42; k++) {
        final String[] part = k < 20 ? new String[] {"TRAIN", "TRAIN"}
            : k < 30 ? new String[] {"TRAIN", "TEST"}
            : k < 40 ? new String[] {"TEST", ""} : new String[] {"CROSSES", ""};
        final Map<String, Double> values = new HashMap<>();
        double signal = 0;
        for (final Attribute a : columns) {
          final double v = random.nextInt(5);
          values.put(a.name(), v);
          signal += v;
        }
        values.put(IssuesInProgressExtractTask.PAUSED, n % 5 == 0 ? 1.0 : 0.0);
        final double hours = 1 + 10 * p + signal + random.nextDouble() * 20;
        final String id = "i" + (n++);
        final Map<Population, String[]> parts = new EnumMap<>(Population.class);
        parts.put(Population.IN_PROGRESS, part);
        out.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, "p" + p, "r", "e" + (k % 3),
            created, start.plusMillis((long) (hours * 3_600_000)), "Fixed", false, start),
            new Row(id, values, Set.of()), parts));
      }
    }
    return out;
  }

  @Test
  @DisplayName("lambda scaled by the ratio of rows, three references in the main rule, the pause "
      + "sensitivity, and one prediction line per scored row")
  void reservedRun() {
    final IssuesEvaluationTask.Report r = new IssuesEvaluationTask(Progress.silent()).run(corpus(),
        List.of(new IssuesEvaluationTask.Frozen(Population.IN_PROGRESS, CELL, 0.5, 10.0)),
        Population.IN_PROGRESS, Fitting.Mode.RESERVED);
    assertEquals(180, r.fitRows());
    assertEquals(120, r.innerFitRows());
    assertEquals(60, r.scoredRows());
    assertEquals(10.0 * 180 / 120, r.evaluated().get(0).fitted().lambda(), 1e-12);
    assertTrue(r.recalibrated().isEmpty());
    assertFalse(Double.isNaN(r.main().pairMae()), "P12: the pair is a candidate");
    assertTrue(r.sensitivities().stream()
        .anyMatch(x -> x.what().startsWith("without the issues that go back")), "P16");

    final StringBuilder out = new StringBuilder();
    IssuesReservedCommand.appendPredictions(out, r);
    final String[] lines = out.toString().split("\n");
    assertTrue(lines[0].startsWith("population\tid\tkey\tproject\tobserved_h\t"), lines[0]);
    assertEquals(1 + 60, lines.length);
    assertEquals(lines[0].split("\t").length, lines[1].split("\t").length);
    assertEquals(r.observed()[0], Double.parseDouble(lines[1].split("\t")[4]), 1e-12,
        "measured from the start");
  }
}
