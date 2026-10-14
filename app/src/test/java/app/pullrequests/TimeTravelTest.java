package app.pullrequests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same row, computed three ways, from tables that differ only after {@code t0}.
 *
 * <ul>
 *   <li><b>complete</b> -- every event, and the extractor applies the window itself;
 *   <li><b>truncated</b> -- every event dated at or after {@code t0} deleted before the call, so
 *       there is no future left to filter;
 *   <li><b>injected</b> -- the complete view plus synthetic later events: a merge, a close, a
 *       comment, a review, a new pull request and a new integrator.
 * </ul>
 *
 * <p>Complete against truncated asks whether the half-open window is doing the work, or whether the
 * right answer was an accident of missing data. Complete against injected asks whether anything
 * that happened later can reach the row at all. A construct that passes the first and fails the
 * second reads the future; one that fails the first was never really filtering.
 *
 * <p><b>The three textual constructs are excluded on purpose</b>, and their exclusion is the
 * finding rather than a gap in the test: the corpus keeps no historical version of a title or body,
 * so the truncated view copies the collected text unchanged and the injected view cannot simulate
 * an edit. Passing here would say nothing about them, which is exactly why they sit in their own
 * evidence profile.
 */
class TimeTravelTest {

  private static final long DAY = 86_400_000L;
  private static final Instant T0 = Instant.parse("2018-06-15T12:00:00Z");
  private static final long T0_MS = T0.toEpochMilli();

  /** Everything the profiles STRICT_T0 and LITERATURE_COMPATIBLE share minus the text. */
  private static final Set<String> TEXTUAL =
      Set.of("title_description_word_count", "issue_reference", "person_mention");

  private static final Unit UNIT =
      new Unit("alvo", "p", "ana", T0, T0.plusMillis(3 * DAY));

  /**
   * A project with history on both sides of the cut, so truncation actually removes something.
   */
  private static Tables.Builder populated() {
    final Tables.Builder builder = new Tables.Builder()
        .pullRequest("alvo", "ana", T0_MS, "p")
        .origin("p", T0_MS - 900 * DAY, "commit")
        .origin("p", T0_MS - 1200 * DAY, "issue")

        // before the cut
        .pullRequest("ana1", "ana", T0_MS - 80 * DAY, "p")
        .pullRequest("ana2", "ana", T0_MS - 60 * DAY, "q")
        .pullRequest("bia1", "bia", T0_MS - 70 * DAY, "p")
        .pullRequest("caio1", "caio", T0_MS - 50 * DAY, "p")
        // A merge closes a pull request, so the fixture records both, the way the adapter does.
        .merge("p", T0_MS - 75 * DAY, "bia", "ana1")
        .closed("ana1", T0_MS - 75 * DAY)
        .merge("p", T0_MS - 30 * DAY, "bia", "bia1")
        .closed("bia1", T0_MS - 30 * DAY)
        .merge("q", T0_MS - 55 * DAY, "dora", "ana2")
        .closed("ana2", T0_MS - 55 * DAY)

        // after the cut: none of it may reach the row. caio1 opened before and closed after, so
        // it is open at t0 and only the truncated view proves the close was not consulted.
        .pullRequest("ana3", "ana", T0_MS + 10 * DAY, "p")
        .pullRequest("bia2", "bia", T0_MS + 5 * DAY, "p")
        .merge("p", T0_MS + DAY, "novo", "caio1")
        .closed("caio1", T0_MS + 2 * DAY)
        .merge("p", T0_MS + 12 * DAY, "bia", "ana3")
        .closed("ana3", T0_MS + 12 * DAY);

    builder.contact("ana1", "bia", T0_MS - 78 * DAY);     // before: counts
    builder.contact("bia1", "ana", T0_MS - 65 * DAY);     // before: counts
    builder.contact("ana1", "bia", T0_MS + 4 * DAY);      // after: must not count
    builder.contact("alvo", "bia", T0_MS - 1000);         // the row itself: never counts
    return builder;
  }

  private static Map<String, Double> temporalOnly(final Row row) {
    final Map<String, Double> kept = new TreeMap<>();
    row.values().forEach((name, value) -> {
      if (!TEXTUAL.contains(name)) {
        kept.put(name, value);
      }
    });
    return kept;
  }

  @Test
  @DisplayName("the truncated view gives the same values: the window is what filters, not luck")
  void completeAndTruncatedAgree() {
    final Tables complete = populated().build();
    final Tables truncated = complete.truncatedBefore(T0_MS);

    final Row fromComplete = Extractor.of(UNIT, complete);
    final Row fromTruncated = Extractor.of(UNIT, truncated);

    assertEquals(temporalOnly(fromComplete), temporalOnly(fromTruncated));
    assertEquals(fromComplete.absent(), fromTruncated.absent(),
        "and the same constructs are absent in both, for the same reason");
  }

  @Test
  @DisplayName("nothing injected after t0 reaches the row")
  void injectedFutureDoesNotReachTheRow() {
    final Tables complete = populated().build();

    final Tables.Builder injected = complete.toBuilder()
        .pullRequest("sintetico", "ana", T0_MS + DAY, "p")
        .merge("p", T0_MS + DAY, "integrador_sintetico", "sintetico")
        .closed("sintetico", T0_MS + DAY + 3_600_000L)
        // The earlier pull request only becomes a success now, after the cut.
        .merge("p", T0_MS + DAY, "integrador_sintetico", "bia1");
    injected.rawContact("ana", "integrador_sintetico", T0_MS + DAY, "sintetico");

    final Row fromComplete = Extractor.of(UNIT, complete);
    final Row fromInjected = Extractor.of(UNIT, injected.build());

    assertEquals(temporalOnly(fromComplete), temporalOnly(fromInjected));
    assertEquals(fromComplete.absent(), fromInjected.absent());
  }

  @Test
  @DisplayName("the fixture has future to remove, so the two comparisons are not vacuous")
  void theFixtureIsNotDegenerate() {
    // A test that truncates nothing would pass whatever the extractor did. This pins that the
    // fixture really straddles the cut, on every table the constructs read.
    final Tables complete = populated().build();
    final Tables truncated = complete.truncatedBefore(T0_MS);

    assertTrue(complete.prsOfProject("p").size() > truncated.prsOfProject("p").size(),
        "pull requests were removed");
    assertTrue(complete.mergesOfProject("p").size() > truncated.mergesOfProject("p").size(),
        "merge events were removed");
    assertTrue(complete.contactsOnPrsOf("ana").size() > truncated.contactsOnPrsOf("ana").size(),
        "contacts were removed");
    assertTrue(complete.closedAt("caio1") != null && truncated.closedAt("caio1") == null,
        "a close dated after the cut was removed, which is what makes caio1 open at t0");
  }

  @Test
  @DisplayName("the row sees the project's earlier history, so the values are not all zero")
  void theValuesAreSubstantive() {
    final Row row = Extractor.of(UNIT, populated().build());

    assertEquals(2.0, row.values().get("owner_prior_prs"), "ana1 and ana2, both before the cut");
    assertEquals(1.0, row.values().get("owner_prior_project_prs"), "only ana1 is in this project");
    assertEquals(1.0, row.values().get("owner_merged_ratio"),
        "both of her earlier requests were merged before the cut");
    assertEquals(1.0, row.values().get("team_size_90d"), "bia merged inside the window");
    assertEquals(2.0, row.values().get("social_connection_90d"), "one contact at each end");
    assertEquals(1200.0, row.values().get("project_age_days"), "the first issue is the earliest");
    assertEquals(1.0, row.values().get("open_prs_project_at_open"),
        "of the project's earlier requests only caio1 was still open at the cut: ana1 and bia1 had"
            + " been merged and closed, and the row itself never counts for itself");
  }
}
