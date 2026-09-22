package app.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A decomposicao do desfecho, na parte que nao toca banco nenhum. */
class TtcDecompositionTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  private static IssueLabel label(final Optional<Instant> examinedAt) {
    return new IssueLabel("6100000000000000000000aa", "demo", T0, T0.plus(Duration.ofHours(48)),
        "6100000000000000000000bb", T0, 1, 10, 2, List.of(),
        examinedAt, examinedAt.map(at -> ExamOrigin.REVIEW));
  }

  @Test
  @DisplayName("o total existe sempre; as duas parcelas so quando houve exame")
  void onlyTheTotalIsAlwaysDefined() {
    final IssueLabel semExame = label(Optional.empty());

    assertEquals(2.0, semExame.durationOf(TtcTarget.TOTAL).orElseThrow(), 1e-9);
    assertTrue(semExame.durationOf(TtcTarget.ESPERA).isEmpty());
    assertTrue(semExame.durationOf(TtcTarget.AUTOR).isEmpty());
  }

  @Test
  @DisplayName("as parcelas somam o total, que e o que torna somar as previsoes legitimo")
  void theComponentsPartitionTheWhole() {
    final IssueLabel comExame = label(Optional.of(T0.plus(Duration.ofHours(12))));

    final double espera = comExame.durationOf(TtcTarget.ESPERA).orElseThrow();
    final double autor = comExame.durationOf(TtcTarget.AUTOR).orElseThrow();

    assertEquals(0.5, espera, 1e-9);
    assertEquals(1.5, autor, 1e-9);
    assertEquals(comExame.durationOf(TtcTarget.TOTAL).orElseThrow(), espera + autor, 1e-9);
  }

  @Test
  @DisplayName("um rotulo de issue nao tem exame externo, e o construtor antigo diz isso sozinho")
  void issueLabelsCarryNoExamination() {
    final IssueLabel issue = new IssueLabel("6100000000000000000000aa", "demo", T0,
        T0.plus(Duration.ofHours(48)), "6100000000000000000000bb", T0, 1, 10, 2, List.of());

    assertTrue(issue.examinedAt().isEmpty());
    assertTrue(issue.examOrigin().isEmpty());
    assertEquals(2.0, issue.durationOf(TtcTarget.TOTAL).orElseThrow(), 1e-9);
  }
}
