package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A contract test on the boosting wrapper, in the spirit of {@code ElasticNetLibraryTest}: it
 * pins what the library does for us, so a version bump that changes it fails here rather than
 * quietly changing a reported number.
 */
class GradientBoostingModelTest {

  private static final List<String> COLUMNS = List.of("a", "b", "c");

  @Test
  @DisplayName("recovers a non-linear signal a linear model cannot")
  void recoversAnInteraction() {
    // y = 10 when a and b agree in sign, 0 otherwise. An XOR-shaped target: no linear
    // combination of a, b and noise separates it, so a linear fit lands near the mean.
    final Random random = new Random(20260921);
    final int n = 1200;
    final double[][] x = new double[n][3];
    final double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i][0] = random.nextGaussian();
      x[i][1] = random.nextGaussian();
      x[i][2] = random.nextGaussian();
      y[i] = (x[i][0] > 0) == (x[i][1] > 0) ? 10 : 0;
    }
    final GradientBoostingModel model =
        GradientBoostingModel.fit(x, y, COLUMNS, 120, 16, 0.08);

    double error = 0;
    double aroundMean = 0;
    for (int i = 0; i < n; i++) {
      error += Math.abs(model.predict(x[i]) - y[i]);
      aroundMean += Math.abs(5.0 - y[i]);
    }
    assertTrue(error < aroundMean * 0.5,
        "boosting should beat predicting the mean on an interaction; got "
            + (error / n) + " against " + (aroundMean / n));
  }

  @Test
  @DisplayName("predict reads the columns in the order they were given")
  void columnOrderIsRespected() {
    // y depends on the first column only. Permuting a row's values must change the
    // prediction, which fails loudly if predict() ever mismatched the schema.
    final int n = 600;
    final double[][] x = new double[n][3];
    final double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i][0] = i % 2 == 0 ? -5 : 5;
      x[i][1] = 0;
      x[i][2] = 0;
      y[i] = x[i][0] * 3;
    }
    final GradientBoostingModel model = GradientBoostingModel.fit(x, y, COLUMNS, 80, 8, 0.1);
    final double low = model.predict(new double[] {-5, 0, 0});
    final double high = model.predict(new double[] {5, 0, 0});
    assertTrue(high - low > 10, "the informative column must move the prediction: "
        + low + " -> " + high);
    // and the same values in a different slot must not reproduce that move
    final double shifted = model.predict(new double[] {0, 5, 0});
    assertTrue(Math.abs(shifted - high) > 1, "column position must matter");
  }

  @Test
  @DisplayName("reports one importance per column, in order")
  void importanceHasOneEntryPerColumn() {
    // Enough rows that a tree can actually split: the ensemble draws 70% of them and the
    // smallest leaf holds five, so a handful of rows yields no split and zero importance
    // everywhere -- which says nothing about the wrapper.
    final int n = 200;
    final double[][] x = new double[n][3];
    final double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i][0] = i;
      y[i] = i;
    }
    final GradientBoostingModel model = GradientBoostingModel.fit(x, y, COLUMNS, 40, 8, 0.1);
    assertEquals(COLUMNS.size(), model.importance().length);
    assertTrue(model.importance()[0] > 0, "the only informative column must carry weight");
  }
}
