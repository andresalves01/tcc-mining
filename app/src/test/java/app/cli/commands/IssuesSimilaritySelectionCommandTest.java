package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Selection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which existing parameters file the selection may write over. */
class IssuesSimilaritySelectionCommandTest {

  @Test
  @DisplayName("only a file of this step's own choices is overwritten: not the registered chain's, "
      + "not one a later step appended to")
  void overwritesOnlyItsOwnFile() {
    final List<String> own = List.of(Selection.HEADER, "# step\tname\tparameter\tvalue",
        "direct\tjaccard_median\tcandidate\t10");
    assertTrue(IssuesSimilaritySelectionCommand.onlyThisStep(own));
    assertFalse(IssuesSimilaritySelectionCommand.onlyThisStep(List.of(
        "# population\tcell\talpha\tlambda -- written by issues-grid on the inner selection",
        "IN_PROGRESS\tC1 all\t0.5\t0.01")));
    final List<String> appended = new java.util.ArrayList<>(own);
    appended.add("elastic-net\trevised twelve\talpha\t0.5");
    assertFalse(IssuesSimilaritySelectionCommand.onlyThisStep(appended));
    assertFalse(IssuesSimilaritySelectionCommand.onlyThisStep(List.of()));
  }
}
