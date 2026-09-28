package app.paper6.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.pipeline.Paper6EvaluationTask;
import app.pipeline.Paper6Registration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The frozen literals are the grid's choices, to the last digit, and name real cells. */
class RegistrationTest {

  private static final Path PARAMS = Path.of("papers/01-ttc-semantics/replicacoes/"
      + "transposicao-do-metodo/outputs/parametros-grade.tsv");

  @Test
  @DisplayName("every cell of the grid is frozen, once, with the parameters the grid chose")
  void theLiteralsAreTheGridsChoices() throws IOException {
    assertEquals(Cell.grid(new ArrayList<>()).size(), Paper6Registration.FROZEN.size());
    for (final Paper6EvaluationTask.Frozen f : Paper6Registration.FROZEN) {
      Cell.byLabel(f.cell());
    }
    Path here = Path.of("").toAbsolutePath();
    Path file = null;
    for (int up = 0; up < 4 && here != null; up++, here = here.getParent()) {
      if (Files.isRegularFile(here.resolve(PARAMS))) {
        file = here.resolve(PARAMS);
        break;
      }
    }
    Assumptions.assumeTrue(file != null, "parametros-grade.tsv not in this checkout");
    final List<Paper6EvaluationTask.Frozen> written = new ArrayList<>();
    for (final String line : Files.readAllLines(file)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      final String[] f = line.split("\t");
      written.add(new Paper6EvaluationTask.Frozen(f[0], Double.parseDouble(f[1]),
          Double.parseDouble(f[2])));
    }
    assertEquals(written, Paper6Registration.FROZEN);
  }
}
