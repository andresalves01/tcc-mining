package app.paper5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three rules every construct obeys, each exercised on its own.
 *
 * <p>{@code paper5-extract} already checks the whole population against the reference extraction
 * the validation produced in Python, which is stronger evidence than any fixture. These tests exist
 * for what that check cannot do: run without a database, and fail on the specific rule that broke
 * rather than on a count of differing rows.
 */
class ExtractorTest {

  private static final long DAY = 86_400_000L;
  private static final Instant T0 = Instant.parse("2018-06-15T12:00:00Z");
  private static final long T0_MS = T0.toEpochMilli();

  private static Unit unit(final String creator) {
    return new Unit("alvo", "p", creator, T0, T0.plusMillis(3 * DAY));
  }

  /** The row's own pull request, its project's origin, and nothing else. */
  private static Tables.Builder base() {
    return new Tables.Builder()
        .pullRequest("alvo", "ana", T0_MS, "p")
        .origin("p", T0_MS - 1000 * DAY, "commit");
  }

  @Test
  @DisplayName("an event exactly at t0 is not yet observable at t0")
  void theWindowIsHalfOpen() {
    final Tables atT0 = base()
        .pullRequest("antes", "ana", T0_MS - DAY, "p")
        .pullRequest("exatamente", "ana", T0_MS, "p")
        .build();

    final Row row = Extractor.of(unit("ana"), atT0);

    assertEquals(1.0, row.values().get("owner_prior_prs"),
        "the one opened a day earlier counts; the one opened exactly at t0 does not");
  }

  @Test
  @DisplayName("the row's own pull request is excluded by identifier, not only by the window")
  void theRowExcludesItself() {
    // Its own opening instant is not before t0, so the window would drop it anyway. The test that
    // matters is the project's workload, where the row's own request is open at t0 by definition.
    final Tables tables = base().build();

    final Row row = Extractor.of(unit("ana"), tables);

    assertEquals(0.0, row.values().get("open_prs_project_at_open"),
        "the only open request in the project is the row itself, which never counts for itself");
    assertEquals(0.0, row.values().get("owner_prior_prs"));
  }

  @Test
  @DisplayName("a pull request still open at t0 counts as workload; one closed before it does not")
  void workloadIsWhatWasStillOpen() {
    final Tables tables = base()
        .pullRequest("aberto", "bruno", T0_MS - 10 * DAY, "p")
        .pullRequest("fechado", "bruno", T0_MS - 10 * DAY, "p")
        .closed("fechado", T0_MS - 5 * DAY)
        .pullRequest("fecha_depois", "bruno", T0_MS - 10 * DAY, "p")
        .closed("fecha_depois", T0_MS + DAY)
        .build();

    final Row row = Extractor.of(unit("ana"), tables);

    assertEquals(2.0, row.values().get("open_prs_project_at_open"),
        "still open at t0: the one never closed and the one closed afterwards");
  }

  @Test
  @DisplayName("a creator with no earlier pull request has no ratio, and never a zero")
  void absenceIsNotZero() {
    final Row row = Extractor.of(unit("ana"), base().build());

    assertTrue(row.isAbsent("owner_merged_ratio"),
        "no denominator means no ratio -- not zero, and not a division by one");
    assertEquals(1.0, row.values().get("owner_merged_ratio_ausente"));
    assertFalse(row.values().containsKey("owner_merged_ratio"));
  }

  @Test
  @DisplayName("a creator whose earlier pull requests were never merged scores a real zero")
  void validZeroIsNotAbsence() {
    final Tables tables = base()
        .pullRequest("antes", "ana", T0_MS - 10 * DAY, "p")
        .build();

    final Row row = Extractor.of(unit("ana"), tables);

    assertEquals(0.0, row.values().get("owner_merged_ratio"),
        "they had one and it was not merged: the ratio is zero, and the zero is a fact");
    assertEquals(0.0, row.values().get("owner_merged_ratio_ausente"));
    assertFalse(row.isAbsent("owner_merged_ratio"));
  }

  @Test
  @DisplayName("a pull request merged only after t0 was not yet a success at t0")
  void successIsMeasuredAtTheCut() {
    final Tables tables = base()
        .pullRequest("antes", "ana", T0_MS - 10 * DAY, "p")
        .merge("p", T0_MS + DAY, "integradora", "antes")
        .build();

    final Row row = Extractor.of(unit("ana"), tables);

    assertEquals(0.0, row.values().get("owner_merged_ratio"),
        "it ended up merged, but not by t0, and the row is predicted at t0");
  }

  @Test
  @DisplayName("with no active integrator the social value is absent, not zero")
  void socialConnectionIsAbsentWithoutIntegrators() {
    final Tables tables = base()
        .merge("p", T0_MS - 200 * DAY, "antiga", "velho")
        .build();

    final Row row = Extractor.of(unit("ana"), tables);

    assertEquals(0.0, row.values().get("team_size_90d"),
        "the only merge is outside the ninety-day window");
    assertTrue(row.isAbsent("social_connection_90d"),
        "there is no set of active integrators to measure connection to");
    assertEquals(1.0, row.values().get("social_connection_90d_ausente"));
  }

  @Test
  @DisplayName("writing on your own pull request is not a contact with anybody")
  void selfAuthorshipIsNotAnInteraction() {
    // The case the specification calls out: a creator who is also an active integrator. Their own
    // comments on their own requests must not make them socially connected to themselves.
    final Tables.Builder builder = base()
        .pullRequest("meu", "ana", T0_MS - 20 * DAY, "p")
        .merge("p", T0_MS - 10 * DAY, "ana", "meu");
    assertFalse(builder.contact("meu", "ana", T0_MS - 15 * DAY),
        "the builder itself refuses it, so nothing downstream has to remember to");

    final Row row = Extractor.of(unit("ana"), builder.build());

    assertEquals(1.0, row.values().get("team_size_90d"), "ana merged inside the window");
    assertEquals(0.0, row.values().get("social_connection_90d"),
        "she is active and she wrote, but only on her own request: zero contacts, not one");
    assertEquals(1.0, row.values().get("requester_is_integrator"));
  }

  @Test
  @DisplayName("a contact with an active integrator counts, and one outside the window does not")
  void socialConnectionCountsBothEnds() {
    final Tables.Builder builder = base()
        .pullRequest("meu", "ana", T0_MS - 40 * DAY, "p")
        .pullRequest("dela", "bia", T0_MS - 40 * DAY, "p")
        .merge("p", T0_MS - 10 * DAY, "bia", "dela");
    builder.contact("meu", "bia", T0_MS - 20 * DAY);      // integrator wrote on the creator's
    builder.contact("dela", "ana", T0_MS - 21 * DAY);     // creator wrote on the integrator's
    builder.contact("meu", "bia", T0_MS - 200 * DAY);     // outside the window
    builder.contact("alvo", "bia", T0_MS - 1000);         // on the row itself: excluded by id

    final Row row = Extractor.of(unit("ana"), builder.build());

    assertEquals(2.0, row.values().get("social_connection_90d"),
        "one contact at each end, inside the window, neither of them on the row itself");
  }

  @Test
  @DisplayName("project age counts from the earliest activity, whichever kind it was")
  void projectAgeTakesTheEarliestOrigin() {
    final Tables tables = new Tables.Builder()
        .pullRequest("alvo", "ana", T0_MS, "p")
        .origin("p", T0_MS - 500 * DAY, "commit")
        .origin("p", T0_MS - 900 * DAY, "issue")
        .build();

    final Row row = Extractor.of(unit("ana"), tables);

    assertEquals(900.0, row.values().get("project_age_days"),
        "the first issue precedes the first commit here, and the age follows the earlier one");
  }

  @Test
  @DisplayName("a project with no observable activity before t0 has no age, and no interaction")
  void projectAgeCanBeAbsent() {
    final Tables tables = new Tables.Builder()
        .pullRequest("alvo", "ana", T0_MS, "p")
        .origin("p", T0_MS, "commit")
        .build();

    final Row row = Extractor.of(unit("ana"), tables);

    assertTrue(row.isAbsent("project_age_days"), "its origin is exactly t0, so not yet observable");
    assertTrue(row.isAbsent("project_age_x_team_size"),
        "a product with an absent leg is absent, never zero");
  }

  @Test
  @DisplayName("the interactions are products of the raw legs, not of transformed ones")
  void interactionsMultiplyRawValues() {
    final Tables tables = new Tables.Builder()
        .pullRequest("alvo", "ana", T0_MS, "p")
        .origin("p", T0_MS - 100 * DAY, "commit")
        .pullRequest("outro", "bruno", T0_MS - 10 * DAY, "p")
        .merge("p", T0_MS - 5 * DAY, "bia", "algum")
        .build();

    final Row row = Extractor.of(unit("ana"), tables);

    assertEquals(100.0, row.values().get("project_age_days"));
    assertEquals(1.0, row.values().get("team_size_90d"));
    assertEquals(1.0, row.values().get("open_prs_project_at_open"));
    assertEquals(100.0, row.values().get("project_age_x_team_size"));
    assertEquals(1.0, row.values().get("team_size_x_workload"));
  }

  @Test
  @DisplayName("the day of week is read in UTC with Sunday as zero, and Friday is day five")
  void theCalendarFieldsAreUtc() {
    // 2018-06-15 was a Friday.
    final Row row = Extractor.of(unit("ana"), base().build());

    assertEquals(5.0, row.values().get("created_weekday_utc"));
    assertEquals(1.0, row.values().get("friday_effect"));
    assertEquals(12.0, row.values().get("created_hour_utc"));
  }
}
