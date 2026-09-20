package app.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeniorityTest {

  private static final Instant T0 = Instant.parse("2015-01-01T00:00:00Z");

  private static Contribution contribution(
      final String developer, final String project, final Instant when, final int lines) {
    return new Contribution(
        "c" + developer + project + when.toEpochMilli(), "hash", project, project, "vcs",
        developer, developer, false, when, lines, 0, lines, 0, 1, 1);
  }

  @Test
  @DisplayName("q divides by the whole population, so the ceiling is never reached")
  void ranksByStrictlyLesserVolume() {
    final Map<String, Long> volume = new LinkedHashMap<>();
    volume.put("low", 10L);
    volume.put("mid", 20L);
    volume.put("high", 30L);

    final Map<String, Double> q = Seniority.quantiles(volume);

    assertEquals(0.0, q.get("low"));
    assertEquals(1 / 3.0, q.get("mid"), 1e-12);
    assertEquals(2 / 3.0, q.get("high"), 1e-12);
  }

  @Test
  @DisplayName("nobody is priced at C_max, because q = 1 is unreachable by construction")
  void ceilingIsNeverAttained() {
    // The paper calls C_max a scenario bound rather than a wage anyone is guaranteed. That only
    // holds if the divisor is the population and not the population minus one: over n - 1 the most
    // senior developer lands exactly on 1 and draws the ceiling.
    final Map<String, Long> volume = new LinkedHashMap<>();
    for (int i = 0; i < 50; i++) {
      volume.put("d" + i, (long) i);
    }

    Seniority.quantiles(volume).values().forEach(q -> assertTrue(q < 1.0, "q reached the ceiling"));
  }

  @Test
  @DisplayName("ties share a rank, so one distinct value puts everyone at C_min")
  void tiesCollapseToZero() {
    // The degenerate case the plan calls out: if every developer has the same volume, nobody is
    // senior to anybody, and the cost curve must price them all at C_min rather than fan them out
    // on an ordering that carries no information.
    final Map<String, Long> volume = new LinkedHashMap<>();
    volume.put("a", 7L);
    volume.put("b", 7L);
    volume.put("c", 7L);

    Seniority.quantiles(volume).values().forEach(q -> assertEquals(0.0, q));
  }

  @Test
  @DisplayName("a single developer ranks at zero rather than dividing by zero")
  void singleDeveloperIsNotSeniorToAnyone() {
    assertEquals(Map.of("only", 0.0), Seniority.quantiles(Map.of("only", 999L)));
    assertTrue(Seniority.quantiles(Map.of()).isEmpty());
  }

  @Test
  @DisplayName("partial ties keep the developers above them at the ranks they earned")
  void partialTiesDoNotShiftTheTop() {
    final Map<String, Long> volume = new LinkedHashMap<>();
    volume.put("a", 5L);
    volume.put("b", 5L);
    volume.put("c", 9L);
    volume.put("d", 12L);

    final Map<String, Double> q = Seniority.quantiles(volume);

    assertEquals(0.0, q.get("a"));
    assertEquals(0.0, q.get("b"));
    assertEquals(0.5, q.get("c"), 1e-12);
    assertEquals(0.75, q.get("d"), 1e-12);
  }

  @Test
  @DisplayName("contributions at or after the instant asked about are not visible")
  void isStrictlyAsOf() {
    // The whole point of keeping per-commit rows. A contribution landing exactly at t_i is not
    // observable when the issue is created, and letting it in is the cheapest possible leak.
    final List<Contribution> ledger = List.of(
        contribution("early", "p", T0.minusSeconds(1), 100),
        contribution("late", "p", T0, 1_000_000));

    final Map<String, Double> q =
        new Seniority(ExperienceScope.CAREER, Seniority.allLines()).at(ledger, null, T0);

    assertEquals(Map.of("early", 0.0), q, "only the developer active before T0 is ranked");
  }

  @Test
  @DisplayName("career scope counts work in other projects, project scope does not")
  void scopeChangesWhoOutranksWhom() {
    // The measured case in miniature: `visitor` is a heavyweight elsewhere and a newcomer here.
    final List<Contribution> ledger = List.of(
        contribution("local", "here", T0.minusSeconds(10), 500),
        contribution("visitor", "here", T0.minusSeconds(10), 10),
        contribution("visitor", "elsewhere", T0.minusSeconds(20), 100_000));

    final Map<String, Double> project =
        new Seniority(ExperienceScope.PROJECT, Seniority.allLines()).at(ledger, "here", T0);
    final Map<String, Double> career =
        new Seniority(ExperienceScope.CAREER, Seniority.allLines()).at(ledger, null, T0);

    assertEquals(0.0, project.get("visitor"), "inside this project the visitor looks like a junior");
    assertEquals(0.5, project.get("local"));
    assertEquals(0.5, career.get("visitor"), "across the corpus the visitor is the senior one");
    assertEquals(0.0, career.get("local"));
  }

  @Test
  @DisplayName("java-only volume ranks differently from all-file volume")
  void volumeFunctionIsPartOfTheDefinition() {
    final Contribution docsHeavy = new Contribution(
        "c1", "h", "p", "p", "v", "docs", "docs", false, T0.minusSeconds(5), 10_000, 0, 5, 0, 9, 1);
    final Contribution codeHeavy = new Contribution(
        "c2", "h", "p", "p", "v", "code", "code", false, T0.minusSeconds(5), 900, 0, 900, 0, 3, 3);
    final List<Contribution> ledger = List.of(docsHeavy, codeHeavy);

    assertEquals(0.5, new Seniority(ExperienceScope.CAREER, Seniority.allLines())
        .at(ledger, null, T0).get("docs"));
    assertEquals(0.5, new Seniority(ExperienceScope.CAREER, Seniority.javaLines())
        .at(ledger, null, T0).get("code"));
  }

  @Test
  @DisplayName("volume counts lines removed, not just lines added")
  void volumeCountsRemovals() {
    // A refactor that deletes 900 lines and adds 10 is work done and familiarity demonstrated.
    // Counting additions alone would rank it below a commit that only appends.
    final Contribution refactor = new Contribution(
        "c1", "h", "p", "p", "v", "refactor", "refactor", false, T0.minusSeconds(5),
        10, 900, 10, 900, 4, 4);
    final Contribution appender = new Contribution(
        "c2", "h", "p", "p", "v", "appender", "appender", false, T0.minusSeconds(5),
        400, 0, 400, 0, 2, 2);

    final Map<String, Double> q = new Seniority(ExperienceScope.CAREER, Seniority.javaLines())
        .at(List.of(refactor, appender), null, T0);

    assertEquals(0.5, q.get("refactor"), "910 lines touched outranks 400");
    assertEquals(0.0, q.get("appender"));
  }

  @Test
  @DisplayName("project scope without a project is a programming error, not a silent default")
  void projectScopeRequiresAProject() {
    final Seniority seniority = new Seniority(ExperienceScope.PROJECT, Seniority.allLines());
    assertThrows(IllegalArgumentException.class, () -> seniority.at(List.of(), null, T0));
  }
}
