package app.paper5;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The correction of 28/09/2026, on a corpus small enough to know every answer. */
class Artigo4CorrectionTest {

  private static final long D = Artigo4Correction.DAY;
  private static final long T0 = 1000 * D;
  private static final long NONE = Artigo4Correction.NONE_VALUE;

  private static Artigo4Correction.Pr pr(final String id, final String creator, final long opened,
      final long merged, final long closed, final String author, final long review,
      final List<String> paths) {
    return new Artigo4Correction.Pr(id, "p", creator, creator == null ? null : "c-" + creator,
        opened, merged, closed, author, review, NONE, paths);
  }

  private static Map<String, Map<String, Double>>[] run() {
    final List<Artigo4Correction.Pr> prs = List.of(
        // the pull request being predicted: opened at t0 by ana, merged later
        pr("alvo", "ana", T0, T0 + 5 * D, T0 + 5 * D, "ana", T0 + D,
            List.of("A.java", "B.java")),
        // ana's earlier one, merged before t0, touched A.java
        pr("velho", "ana", T0 - 50 * D, T0 - 40 * D, T0 - 40 * D, "ana", T0 - 49 * D,
            List.of("A.java")),
        // ana's other one, opened before t0 but merged after: at t0 not yet a merge
        pr("pendente", "ana", T0 - 3 * D, T0 + 2 * D, T0 + 2 * D, "ana", T0 + D,
            List.of("B.java")),
        // bob's, opened before t0, never merged, still open at t0; examined before t0
        pr("aberto", "bob", T0 - 10 * D, NONE, NONE, "bob", T0 - 8 * D, List.of("C.java")));
    final List<Artigo4Correction.Commit> ledger = List.of(
        // an old commit of ana, in the repository long before t0
        new Artigo4Correction.Commit("dev-ana", "p", T0 - 100 * D, T0 - 100 * D, 10, false),
        // the target's own commit: written before t0, in the repository only at its merge
        new Artigo4Correction.Commit("dev-ana", "p", T0 - D, T0 + 5 * D, 500, false));
    final List<Artigo4Correction.Unit> units = List.of(
        new Artigo4Correction.Unit("alvo", "p", "dev-ana", T0),
        new Artigo4Correction.Unit("pendente", "p", "dev-ana", T0 - 3 * D));
    final Map<String, long[]> interval = Map.of("alvo", new long[] {T0, T0 + 5 * D},
        "pendente", new long[] {T0 - 3 * D, T0 + 2 * D});
    final Artigo4Correction.Result r =
        new Artigo4Correction(prs, ledger, units, interval).compute(List.of(units.get(0)));
    @SuppressWarnings("unchecked")
    final Map<String, Map<String, Double>>[] out = new Map[] {r.registered(), r.corrected()};
    return out;
  }

  @Test
  @DisplayName("the pull request's own commit is in the registered history and not in the corrected")
  void ownCommitLeavesTheHistory() {
    final var two = run();
    assertEquals(510.0, two[0].get("alvo").get("loc_tot_java"), "registered: both, by authorship");
    assertEquals(10.0, two[1].get("alvo").get("loc_tot_java"), "corrected: only the old one");
    assertEquals(2.0, two[0].get("alvo").get("extra_dev_commits"));
    assertEquals(1.0, two[1].get("alvo").get("extra_dev_commits"));
  }

  @Test
  @DisplayName("open load: the registered one counts only future merges; the corrected counts all")
  void openLoad() {
    final var two = run();
    assertEquals(1.0, two[0].get("alvo").get("iss_dev"),
        "registered: 'pendente' counts only because it will be merged");
    assertEquals(1.0, two[1].get("alvo").get("iss_dev"),
        "corrected: 'pendente' is open at t0 and ana opened it");
    assertEquals(2.0, two[1].get("alvo").get("iss_team"),
        "corrected: ana's 'pendente' and bob's never-merged 'aberto' are both open at t0");
  }

  @Test
  @DisplayName("the project's wait counts only examinations made before t0")
  void waitOnlyWhatWasKnown() {
    final var two = run();
    // registered: merged PRs opened in the 90 days -- 'velho' waited 1 d, 'pendente' 4 d with an
    // examination after t0 -- upper median of {1, 4} = 4
    assertEquals(4.0, two[0].get("alvo").get("pr_project_wait"), 1e-9);
    // corrected: every PR opened in the 90 days examined before t0 -- 'velho' 1 d, 'aberto' 2 d,
    // never merged -- upper median of {1, 2} = 2
    assertEquals(2.0, two[1].get("alvo").get("pr_project_wait"), 1e-9);
  }

  @Test
  @DisplayName("familiarity: a pull request merged after t0 does not make a file known")
  void familiarity() {
    final var two = run();
    assertEquals(2.0, two[0].get("alvo").get("pr_familiarity"),
        "registered: A.java from 'velho' and B.java from 'pendente', merged only after t0");
    assertEquals(1.0, two[1].get("alvo").get("pr_familiarity"), "corrected: A.java only");
    assertEquals(0.5, two[1].get("alvo").get("pr_familiarity_share"), 1e-12);
  }
}
