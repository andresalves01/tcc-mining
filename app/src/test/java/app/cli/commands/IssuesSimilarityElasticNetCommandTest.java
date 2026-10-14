package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation;
import app.issues.ElasticNetSelection;
import app.issues.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which files the Elastic Net step may write over, and which matrices it refuses. */
class IssuesSimilarityElasticNetCommandTest {

  @Test
  @DisplayName("only a file of this step's own choices is overwritten: not the registered chain's, "
      + "not the direct predictors'")
  void overwritesOnlyItsOwnFile() {
    final List<String> own = List.of(ElasticNetSelection.HEADER,
        "# step\tname\tparameter\tvalue",
        "elastic-net\tinputs\tsimilarity_parameters_sha256\tab12",
        "elastic-net\trevised_twelve\talpha\t0.5", "elastic-net\trevised_twelve\tlambda\t0.1");
    assertTrue(IssuesSimilarityElasticNetCommand.onlyThisStep(own));
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(List.of(Selection.HEADER,
        "# step\tname\tparameter\tvalue", "direct\tjaccard_median\tcandidate\t10")));
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(List.of(
        "# population\tcell\talpha\tlambda -- written by issues-grid on the inner selection",
        "IN_PROGRESS\tOWN / C1\t0.5\t0.01")));
    final List<String> appended = new ArrayList<>(own);
    appended.add("direct\tjaccard_median\tcandidate\t10");
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(appended));
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(List.of()));
  }

  @Test
  @DisplayName("a column the models declare, or a registered column's stored indicator, that "
      + "the matrix lacks is named, so the run refuses rather than reading it as missing")
  void namesTheColumnsTheMatrixLacks() {
    final Map<String, Integer> chosen =
        Map.of("jaccard_median", 10, "embedding_median", 5, "cluster_median", 32);
    final List<String> columns = new ArrayList<>(app.issues.Attributes.stored());
    columns.addAll(app.issues.Extractor.TEAM_REVISED);
    columns.addAll(Augmentation.columns());
    assertEquals(List.of(), IssuesSimilarityElasticNetCommand.missing(
        ElasticNetSelection.cells(chosen, Augmentation.Variant.INNER), columns));
    columns.remove("cluster_median_32_inner");
    columns.remove("TEAM_loc_sd");
    columns.remove("A09_exec_loc_tot_ausente");
    assertEquals(List.of("A09_exec_loc_tot_ausente", "TEAM_loc_sd", "cluster_median_32_inner"),
        IssuesSimilarityElasticNetCommand.missing(
            ElasticNetSelection.cells(chosen, Augmentation.Variant.INNER), columns));
  }
}
