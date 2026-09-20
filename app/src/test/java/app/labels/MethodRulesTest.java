package app.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The eight extraction rules, pinned so a refactor cannot quietly change what a label means. */
class MethodRulesTest {

  private static final Instant T = Instant.parse("2016-01-01T00:00:00Z");

  private static CommitMethods commit(final String hash, final Instant at,
      final ChangedMethod... methods) {
    final Map<String, ChangedMethod> map = new LinkedHashMap<>();
    for (final ChangedMethod method : methods) {
      map.put(method.longName(), method);
    }
    return new CommitMethods(hash, at, map);
  }

  private static IssueLabel labelWith(final List<ChangedMethod> methods) {
    return new IssueLabel("5bf43342ed024056f5b30676", "p", T, T.plusSeconds(86_400),
        "58bfc52702ca40f8bf147856", T, 1, 120, 45, methods);
  }

  @Nested
  @DisplayName("deduplication")
  class Deduplication {

    @Test
    @DisplayName("a method reached by several hunks of one commit counts once")
    void oncePerCommit() {
      // The within-commit rule holds by construction: the commit's methods are keyed by long name,
      // so three hunks landing in the same method occupy one entry rather than three.
      final CommitMethods one = commit("a1", T,
          new ChangedMethod("p.C.doWork()V", 7, 2),
          new ChangedMethod("p.C.doWork()V", 7, 2));

      assertEquals(1, one.methods().size());
      assertEquals(1, MethodDeduplication.byLatestChange(List.of(one)).size());
    }

    @Test
    @DisplayName("a method changed in several commits of one issue counts once")
    void oncePerIssue() {
      final List<ChangedMethod> merged = MethodDeduplication.byLatestChange(List.of(
          commit("a1", T, new ChangedMethod("p.C.doWork()V", 3, 1)),
          commit("b2", T.plusSeconds(60), new ChangedMethod("p.C.doWork()V", 9, 4))));

      assertEquals(1, merged.size());
    }

    @Test
    @DisplayName("the surviving metrics are the ones after the last eligible change")
    void latestChangeWins() {
      final List<ChangedMethod> merged = MethodDeduplication.byLatestChange(List.of(
          commit("zz", T.plusSeconds(60), new ChangedMethod("p.C.doWork()V", 9, 4)),
          commit("aa", T, new ChangedMethod("p.C.doWork()V", 3, 1))));

      assertEquals(9, merged.get(0).mccc(), "the later commit's state must survive");
      assertEquals(4, merged.get(0).nl());
    }

    @Test
    @DisplayName("commits sharing an instant are ordered by revision hash, not by arrival")
    void orderingIsTotalAndReproducible() {
      // Same authoring instant. Without the hash tiebreak the surviving state would depend on the
      // order the database happened to return rows in, and a re-run could silently disagree.
      final CommitMethods first = commit("aaa", T, new ChangedMethod("p.C.doWork()V", 3, 1));
      final CommitMethods second = commit("bbb", T, new ChangedMethod("p.C.doWork()V", 9, 4));

      assertEquals(9, MethodDeduplication.byLatestChange(List.of(first, second)).get(0).mccc());
      assertEquals(9, MethodDeduplication.byLatestChange(List.of(second, first)).get(0).mccc(),
          "the same input in a different order must give the same answer");
    }

    @Test
    @DisplayName("overloads are different methods")
    void signatureIsPartOfIdentity() {
      final List<ChangedMethod> merged = MethodDeduplication.byLatestChange(List.of(
          commit("a1", T,
              new ChangedMethod("p.C.doWork()V", 3, 1),
              new ChangedMethod("p.C.doWork(I)V", 8, 3))));

      assertEquals(2, merged.size());
    }
  }

  @Nested
  @DisplayName("absence is not zero")
  class Absence {

    @Test
    @DisplayName("an issue with no recoverable method has no mean, rather than a mean of zero")
    void noMethodsMeansNoMeasurement() {
      final IssueLabel label = labelWith(List.of());

      assertFalse(label.hasRecoverableMethods());
      assertTrue(label.meanMccc().isEmpty(), "an empty mean must not collapse to 0.0");
      assertTrue(label.meanNl().isEmpty());
    }

    @Test
    @DisplayName("lines added and removed survive even when no method metric does")
    void volumeIsIndependentOfMethodMetrics() {
      // A commit that deletes a method still deleted lines. The metrics of the deleted method are
      // unrecoverable, but the diff is not, and netting the two together would lose both.
      final IssueLabel label = labelWith(List.of());

      assertEquals(120, label.javaLinesAdded());
      assertEquals(45, label.javaLinesDeleted());
      assertEquals(165, label.javaLinesChanged(), "removals add to volume, they do not cancel it");
    }

    @Test
    @DisplayName("a surviving method still gives a mean over what was measured")
    void meanIsOverRecoveredMethodsOnly() {
      final IssueLabel label = labelWith(List.of(
          new ChangedMethod("p.C.a()V", 4, 2), new ChangedMethod("p.C.b()V", 6, 4)));

      assertEquals(5.0, label.meanMccc().orElseThrow());
      assertEquals(3.0, label.meanNl().orElseThrow());
    }
  }

  @Nested
  @DisplayName("funnel arithmetic")
  class Funnel {

    @Test
    @DisplayName("the incremental share is over what entered the stage, not over the corpus")
    void shareUsesTheStageDenominator() {
      final FunnelStage stage = new FunnelStage("recorte Java", 69_966, 21_651, 91_617);

      assertEquals(21_651 / 91_617.0, stage.incrementalShare(), 1e-12);
    }

    @Test
    @DisplayName("an empty denominator does not divide by zero")
    void emptyStageIsSafe() {
      assertEquals(0, new FunnelStage("vazio", 0, 0, 0).incrementalShare());
    }
  }
}
