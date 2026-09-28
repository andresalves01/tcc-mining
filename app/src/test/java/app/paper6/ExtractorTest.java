package app.paper6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.paper5.Row;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The extractor on a corpus built by hand, where every right answer is known. */
class ExtractorTest {

  private static final long DAY = 86_400_000L;
  private static final long T0 = 400 * DAY;

  private static IssueUnit unit() {
    return new IssueUnit("alvo", "PX-9", "p", "ana", "autor", Instant.ofEpochMilli(T0),
        Instant.ofEpochMilli(T0 + 3 * DAY), Instant.ofEpochMilli(T0 + DAY), 2, 30, 5);
  }

  private static Tables corpus() {
    final Tables.Builder b = new Tables.Builder();
    b.origin("p", 10 * DAY, "commit").origin("p", 50 * DAY, "issue");
    // Ana's history: two earlier issues, one resolved before t0 and one still open at t0.
    b.issue("a1", "p", "ana", T0 - 100 * DAY).resolution("a1", T0 - 90 * DAY, "rui");
    b.issue("a2", "q", "ana", T0 - 20 * DAY).resolution("a2", T0 + DAY, "rui");
    // Project p: one issue open at t0 created 10 days ago, resolved later by bia.
    b.issue("b1", "p", "bia", T0 - 10 * DAY).resolution("b1", T0 + 2 * DAY, "bia");
    // Rui resolved something in p within the window, so he is an active resolver.
    b.issue("r1", "p", "rui", T0 - 40 * DAY).resolution("r1", T0 - 30 * DAY, "rui");
    // The target itself, and events after t0 that must not be seen.
    b.issue("alvo", "p", "ana", T0).resolution("alvo", T0 + 3 * DAY, "rui");
    b.issue("depois", "p", "ana", T0 + DAY);
    // Rui commented on Ana's issue a1 inside the window; also on the target itself, which is
    // excluded by identifier; and on a1 again after t0, which is outside the window.
    b.comment("a1", "ana", "rui", T0 - 5 * DAY);
    b.comment("alvo", "ana", "rui", T0 - DAY);
    b.comment("a1", "ana", "rui", T0 + DAY);
    // Ana commenting on her own issue is not a contact.
    b.comment("a1", "ana", "ana", T0 - 3 * DAY);
    b.snapshot("alvo", new Tables.Snapshot("Crash in PARSER", "see PX-3 and PX-9, ping [~rui]",
        "Critical", "Bug", "xyz", List.of("perf"), 1, 0, 1, 2));
    b.change("alvo", new Tables.Change(T0 + DAY, "priority", "Major", "Critical"));
    b.change("alvo", new Tables.Change(T0 + DAY, "assignee_id", null, "xyz"));
    b.change("alvo", new Tables.Change(T0 + DAY, "issue_links", null, "l2"));
    return b.build();
  }

  @Test
  @DisplayName("every dated construct counts only what happened before t0")
  void datedConstructs() {
    final Row row = Extractor.of(unit(), corpus(), Map.of());

    assertEquals(390.0, row.values().get("project_age_days"), "from the first commit, day 10");
    assertEquals(2.0, row.values().get("reporter_prior_issues"), "a1 and a2, not the target");
    assertEquals(1.0, row.values().get("reporter_prior_project_issues"));
    assertEquals(0.5, row.values().get("reporter_resolved_ratio"), 1e-12,
        "a2 was resolved after t0, so at t0 it was not yet a success");
    assertEquals(1.0, row.values().get("team_size_90d"), "rui, from r1; a1's resolution is older");
    assertEquals(1.0, row.values().get("open_issues_project_at_open"), "b1 only");
    assertEquals(1.0, row.values().get("recent_open_issues_project_30d"));
    assertEquals(0.0, row.values().get("reporter_is_resolver"));
    assertEquals(1.0, row.values().get("social_connection_90d"),
        "one comment by rui on a1 inside the window; the one on the target is excluded by id");
  }

  @Test
  @DisplayName("the issue's own fields are read as they were at creation")
  void fieldsAtCreation() {
    final Row row = Extractor.of(unit(), corpus(), Map.of());

    assertEquals(3.0, row.values().get("priority_at_open"), "Major at creation, not the final Critical");
  }

  @Test
  @DisplayName("text constructs: another issue's key counts, the issue's own key does not")
  void textConstructs() {
    final Row row = Extractor.of(unit(), corpus(), Map.of());

    assertEquals(1.0, row.values().get("issue_reference"), "PX-3 is another issue; PX-9 is the issue itself");
    assertEquals(1.0, row.values().get("person_mention"), "[~rui] is a Jira mention");
    assertEquals(9.0, row.values().get("title_description_word_count"));
    assertEquals(0.0, row.values().get("has_assignee_at_open"));
    assertEquals(1.0, row.values().get("linked_issues_at_open"), "two now, one added later");
  }

  @Test
  @DisplayName("truncating the corpus at t0 changes no construct the ex ante profiles admit")
  void timeTravel() {
    final Tables full = corpus();
    final Row now = Extractor.of(unit(), full, Map.of());
    final Row then = Extractor.of(unit(), full.truncatedAt(T0), Map.of());

    for (final Attribute attribute : Attributes.all()) {
      if (!Profile.T0_RECONSTRUIDO.admits(attribute.temporalClass())
          || attribute.id().startsWith("P")) {
        continue;
      }
      assertEquals(now.values().get(attribute.name()), then.values().get(attribute.name()),
          attribute.name() + " reads something at or after t0");
    }
  }

  @Test
  @DisplayName("a missing stored column of the author's specification is absent, never zero")
  void ownColumnsKeepAbsence() {
    final Row row = Extractor.of(unit(), corpus(), Map.of("loc_tot_java", 0.0));

    assertEquals(0.0, row.values().get("loc_tot_java"), "a real zero stays a zero");
    assertTrue(row.isAbsent("mu_l"));
    assertEquals(1.0, row.values().get("mu_l_ausente"));
    assertFalse(row.values().containsKey("s_l"));
  }

  @Test
  @DisplayName("with nobody resolving in the window, social connection is absent, not zero")
  void socialAbsentWithoutResolvers() {
    final Tables.Builder b = new Tables.Builder();
    b.issue("alvo", "p", "ana", T0);
    b.snapshot("alvo", new Tables.Snapshot("t", null, null, null, null, List.of(), 0, 0, 0, -1));
    final Row row = Extractor.of(unit(), b.build(), Map.of());

    assertTrue(row.isAbsent("social_connection_90d"));
    assertEquals(1.0, row.values().get("social_connection_90d_ausente"));
    assertTrue(row.isAbsent("project_age_days"), "no origin before t0");
    assertTrue(row.isAbsent("description_length_at_open"));
    assertTrue(row.isAbsent("linked_issues_at_open"));
    assertEquals(0.0, row.values().get("priority_at_open"), "no priority is its own level");
  }
}
