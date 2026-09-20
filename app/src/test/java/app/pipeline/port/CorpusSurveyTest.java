package app.pipeline.port;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.pipeline.port.Corpus.Entry;
import app.pipeline.port.Corpus.Stage;
import app.pipeline.port.Corpus.Survey;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reading of a corpus survey, exercised without MongoDB. */
class CorpusSurveyTest {

  private static Survey survey(final Entry... entries) {
    return new Survey("5.0.33", "4.4", "smartshark_2_2", List.of(entries), List.of());
  }

  private static Entry entry(final String name, final boolean usable, final Stage... stages) {
    return new Entry(name, usable ? "452,286" : "ABSENT", usable, Set.of(stages));
  }

  @Test
  @DisplayName("a stage is satisfied only when everything it reads is populated")
  void satisfiedWhenPopulated() {
    final Survey survey = survey(
        entry("commit", true, Stage.BUILD_LEDGER, Stage.FUNNEL),
        entry("code_entity_state", false, Stage.FUNNEL));

    assertTrue(survey.satisfies(Stage.BUILD_LEDGER));
    assertFalse(survey.satisfies(Stage.FUNNEL));
  }

  @Test
  @DisplayName("an empty collection blocks a stage exactly as an absent one does")
  void emptyCountsAsUnusable() {
    // A restore that created the collection but loaded nothing into it reads as zero rows all the
    // way through, which is the failure the check exists to make loud.
    final Survey survey = survey(new Entry("hunk", "EMPTY", false, Set.of(Stage.FUNNEL)));

    assertFalse(survey.satisfies(Stage.FUNNEL));
  }

  @Test
  @DisplayName("a stage that reads nothing in this survey is satisfied by it")
  void nothingToRead() {
    // Not vacuous: the stages that read only what an earlier one wrote are checked by the task
    // that runs them, not here, and must not be blocked by a corpus they never touch.
    final Survey survey = survey(entry("commit", true, Stage.BUILD_LEDGER));

    assertTrue(survey.satisfies(Stage.HARVEST_JIRA));
  }
}
