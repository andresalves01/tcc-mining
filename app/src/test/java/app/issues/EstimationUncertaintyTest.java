package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.EstimationUncertainty.Reading;
import app.issues.EstimationUncertainty.Sample;
import app.issues.EstimationUncertainty.Summary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EstimationUncertaintyTest {

  private static final Map<String, PredictionIntervals.Calibration> CALIBRATION = Map.of(
      "model", new PredictionIntervals.Calibration(100,
          new double[] {Math.log(2), Math.log(3)}, 0, 10, PredictionIntervals.FLOOR));

  private static Summary overall(final Reading r) {
    return r.summaries().get(0);
  }

  @Test
  void zerosRemainInMaeButNotRelativeErrorsAndDenominatorIsPrediction() {
    final Reading r = EstimationUncertainty.read(new Sample("usual_flow",
        List.of("a", "a", "b", "b"), new boolean[] {true, false, true, false},
        new double[] {1, 3, 2, 8}, Map.of("model", new double[] {2, 2, 0, 4}),
        new double[] {1, 2, 3}), CALIBRATION, 1);
    final Summary s = overall(r);
    assertEquals(4, s.n());
    assertEquals(3, s.positiveEstimates());
    assertEquals(1, s.zeroEstimates());
    assertEquals(2, s.projects());
    assertEquals(2, s.metrics().get(0).value(), 1e-12);
    assertEquals(1.0 / 3, s.metrics().get(1).value(), 1e-12);
    assertEquals(Math.sqrt(7.0 / 12), s.metrics().get(2).value(), 1e-12);
    assertEquals(0, s.metrics().get(3).value());
    assertEquals(2.0 / 3, s.metrics().get(4).value(), 1e-12);
    assertEquals(1, s.metrics().get(5).value());
    assertEquals(4, r.summaries().stream()
        .filter(g -> g.grouping().equals("observed_duration")).mapToInt(Summary::n).sum());
    assertEquals(4, r.summaries().stream()
        .filter(g -> g.grouping().equals("start_description")).mapToInt(Summary::n).sum());
    final Summary zeroGroup = r.summaries().stream()
        .filter(g -> g.grouping().equals("observed_duration") && g.group().contains("2")
            && g.zeroEstimates() == 1).findFirst().orElseThrow();
    assertEquals(2, zeroGroup.metrics().get(0).value());
    assertTrue(Double.isNaN(zeroGroup.metrics().get(1).value()));
    assertEquals(0, zeroGroup.metrics().get(1).validDraws());
    final Summary description = r.summaries().stream()
        .filter(g -> g.grouping().equals("start_description") && g.group().equals("available"))
        .findFirst().orElseThrow();
    assertTrue(Double.isNaN(description.metrics().get(2).value()));
    assertTrue(Double.isNaN(description.metrics().get(2).low()));
    assertEquals(1, description.positiveEstimateProjects());
    assertTrue(Double.isNaN(description.metrics().get(1).low()));
    assertEquals(0, description.metrics().get(1).validDraws());
  }

  @Test
  void bootstrapDrawsWholeProjectsRatherThanIndependentIssuesAndIsThreadStable() {
    final Sample s = new Sample("all", List.of("a", "a", "b"),
        new boolean[] {true, true, true}, new double[] {2, 2, 6},
        Map.of("model", new double[] {2, 2, 2}), new double[] {1, 2, 3});
    final Reading sequential = EstimationUncertainty.read(s, CALIBRATION, 1);
    final Reading parallel = EstimationUncertainty.read(s, CALIBRATION, 3);
    assertEquals(sequential, parallel);
    final var bias = overall(sequential).metrics().get(1);
    assertEquals(2.0 / 3, bias.value(), 1e-12);
    assertEquals(0, bias.low());
    assertEquals(2, bias.high());
    assertEquals(600, bias.validDraws());
    // Both copies of project a must stay together in every draw. Only 0, 2/3 and 2 are possible.
    final var bounds = app.issues.model.ProjectBootstrap.finiteIntervals(s.projects(), 1,
        sample -> {
          final long a = sample.stream().filter(i -> i < 2).count();
          assertEquals(0, a % 2);
          return new double[] {a};
        }, 2);
    assertEquals(600, bounds.get(0).validDraws());
  }

  @Test
  void tiedQuartilesAndAbsentDescriptionGroupsStayExplicitAndIntervalsUseCalibration() {
    final Reading r = EstimationUncertainty.read(new Sample("usual_flow",
        List.of("a", "b"), new boolean[] {true, true}, new double[] {1, 1},
        Map.of("model", new double[] {2, 2}), new double[] {1, 1, 1}), CALIBRATION, 1);
    assertEquals(9, r.summaries().size());
    assertEquals(5, r.summaries().stream().filter(g -> g.n() == 0).count());
    r.summaries().stream().filter(g -> g.n() == 0).forEach(g -> {
      assertTrue(Double.isNaN(g.metrics().get(0).value()));
      assertTrue(Double.isNaN(g.metrics().get(0).low()));
      assertEquals(0, g.metrics().get(0).validDraws());
    });
    assertEquals(5, r.percentiles().size());
    r.percentiles().forEach(p -> {
      assertEquals(2, p.estimate());
      assertEquals(1, p.low95());
      assertEquals(8, p.high95(), 1e-12);
      assertEquals(5, p.high80(), 1e-12);
    });
  }

  @Test
  void finiteDrawCountsExposeUndefinedResamplesAndSingleProjectCannotGiveAnInterval() {
    final var bounds = app.issues.model.ProjectBootstrap.finiteIntervals(List.of("a", "b"), 1,
        sample -> new double[] {sample.contains(0) ? 1 : Double.NaN}, 1).get(0);
    assertTrue(bounds.validDraws() > 0 && bounds.validDraws() < 600);
    assertEquals(1, bounds.low());
    assertEquals(1, bounds.high());
    final var single = app.issues.model.ProjectBootstrap.finiteIntervals(List.of("a", "a"), 1,
        sample -> new double[] {1}, 1).get(0);
    assertTrue(Double.isNaN(single.low()));
    assertEquals(0, single.validDraws());
  }

  @Test
  void rejectsMisalignedOrNonfinitePredictions() {
    assertThrows(IllegalArgumentException.class, () -> EstimationUncertainty.read(
        new Sample("all", List.of("a"), new boolean[] {true}, new double[] {1},
            Map.of("model", new double[] {Double.NaN}), new double[] {1, 2, 3}),
        CALIBRATION, 1));
  }
}
