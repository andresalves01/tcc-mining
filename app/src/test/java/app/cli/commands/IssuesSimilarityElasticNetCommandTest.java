package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation;
import app.issues.ElasticNetSelection;
import app.issues.model.Cell;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which files the Elastic Net step may write over, and which matrices it refuses. */
class IssuesSimilarityElasticNetCommandTest {

  @Test
  @DisplayName("only a file of this step's own choices in its current format is overwritten: not "
      + "the registered chain's, not one in the format before ticket 11, not one naming the "
      + "models before ticket 12, not one of the six models before the retrospective one")
  void overwritesOnlyItsOwnFile() {
    final List<String> own = List.of(ElasticNetSelection.HEADER,
        "# step\tname\tparameter\tvalue",
        "elastic-net\tinputs\tmatrix_sha256\tab12",
        "elastic-net\town_with_similarity\talpha\t0.5",
        "elastic-net\town_with_similarity\tlambda\t0.1",
        "elastic-net\town_with_similarity\tjaccard_n\t10");
    assertTrue(IssuesSimilarityElasticNetCommand.onlyThisStep(own));
    final List<String> withForest = new ArrayList<>(own);
    withForest.add("forest\town_with_similarity\tmtry_share\t0.3333333333333333");
    withForest.add("forest\town_with_similarity\tnode_size\t25");
    assertTrue(IssuesSimilarityElasticNetCommand.onlyThisStep(withForest));
    final List<String> foreignForest = new ArrayList<>(own);
    foreignForest.add("forest\tprevious_own_start\tnode_size\t25");
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(foreignForest));
    final List<String> forestInputs = new ArrayList<>(own);
    forestInputs.add("forest\tinputs\tmatrix_sha256\tab12");
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(forestInputs));
    // ticket 12's file, without the forests, under its header then
    final List<String> beforeForests = new ArrayList<>(own);
    beforeForests.set(0, "# Elastic Net alpha and lambda, and the similarity medians' n, n and "
        + "k, chosen on the inner validation only; the outer test was not read");
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(beforeForests));
    // ticket 14's file, six models without the retrospective one, under its header then
    final List<String> beforeRetrospective = new ArrayList<>(own);
    beforeRetrospective.set(0, "# Elastic Net alpha and lambda, the similarity medians' n, n and "
        + "k, and each random forest's column share and leaf, chosen on the inner validation "
        + "only, the target in whole business days on the issues of at least one; the outer test "
        + "was not read");
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(beforeRetrospective));
    // ticket 11's file, same header and format, with the models as they were named then
    for (final String before : List.of("previous_own_start", "revised_nine", "revised_twelve",
        "union_revised")) {
      final List<String> earlier = new ArrayList<>(own);
      earlier.add("elastic-net\t" + before + "\talpha\t0.5");
      earlier.add("elastic-net\t" + before + "\tlambda\t0.1");
      assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(earlier), before);
    }
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(List.of(
        "# Elastic Net alpha and lambda chosen on the inner validation only; the outer test was "
            + "not read", "elastic-net\town_with_similarity\talpha\t0.5")));
    assertFalse(IssuesSimilarityElasticNetCommand.onlyThisStep(List.of(
        "# chosen on the inner validation only; the outer test was not read",
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
  @DisplayName("a column a model declares at any of the 27 combinations, or a registered column's "
      + "stored indicator, that the matrix lacks is named, so the run refuses rather than reading "
      + "it as missing")
  void namesTheColumnsTheMatrixLacks() {
    final List<String> columns = new ArrayList<>(app.issues.Attributes.stored());
    columns.addAll(app.issues.Extractor.TEAM_REVISED);
    columns.addAll(app.pipeline.IssuesSimilarityTask.columns());
    final List<Cell> every = ElasticNetSelection.everyCell(Augmentation.Variant.INNER);
    assertEquals(27 * 8, every.size());
    // the similarity stage's matrix lacks the retrospective columns, named in their order
    assertEquals(app.issues.Retrospective.COLUMNS,
        IssuesSimilarityElasticNetCommand.missing(every, columns));
    columns.addAll(app.issues.Retrospective.COLUMNS);
    assertEquals(List.of(), IssuesSimilarityElasticNetCommand.missing(every, columns));
    columns.remove("cluster_median_16_inner");
    columns.remove("jaccard_median_20_inner");
    columns.remove("TEAM_loc_sd");
    columns.remove("A09_exec_loc_tot_ausente");
    assertEquals(List.of("TEAM_loc_sd", "A09_exec_loc_tot_ausente", "cluster_median_16_inner",
        "jaccard_median_20_inner"), IssuesSimilarityElasticNetCommand.missing(every, columns));
    // the outer columns are the test's, never read here
    columns.removeIf(c -> c.endsWith("_outer"));
    columns.add("cluster_median_16_inner");
    columns.add("jaccard_median_20_inner");
    columns.add("TEAM_loc_sd");
    columns.add("A09_exec_loc_tot_ausente");
    assertEquals(List.of(), IssuesSimilarityElasticNetCommand.missing(every, columns));
  }
}
