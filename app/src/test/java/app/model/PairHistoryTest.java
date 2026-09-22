package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PairHistoryTest {

  private static PairHistory history() {
    return PairHistory.of(List.of(
        new PairHistory.Entry("alice\u0000demo", "demo", 100, 1.0),
        new PairHistory.Entry("alice\u0000demo", "demo", 200, 3.0),
        new PairHistory.Entry("alice\u0000demo", "demo", 300, 5.0),
        new PairHistory.Entry("bob\u0000demo", "demo", 150, 9.0),
        new PairHistory.Entry("carol\u0000outro", "outro", 150, 20.0)));
  }

  @Test
  @DisplayName("a propria linha nunca entra na mediana que a preve")
  void strictlyPrior() {
    final PairHistory h = history();

    // Em t=200 a unica anterior e a de t=100.
    assertEquals(1.0, h.medianFor("alice\u0000demo", "demo", 200), 1e-9);
    assertEquals(1, h.priorCount("alice\u0000demo", 200));
    // Em t=300, as de 100 e 200: mediana da lista de dois elementos, pelo indice n/2.
    assertEquals(3.0, h.medianFor("alice\u0000demo", "demo", 300), 1e-9);
    assertEquals(2, h.priorCount("alice\u0000demo", 300));
  }

  @Test
  @DisplayName("sem passado do par, recorre ao projeto; sem projeto, ao conjunto inteiro")
  void fallsBackInTheDeclaredOrder() {
    final PairHistory h = history();

    // alice em t=100 nao tem nada anterior: cai no projeto demo (1, 3, 5, 9 -> indice 2 = 5.0).
    assertEquals(5.0, h.medianFor("alice\u0000demo", "demo", 100), 1e-9);
    assertEquals(0, h.priorCount("alice\u0000demo", 100));
    // par e projeto desconhecidos: mediana global de 1, 3, 5, 9, 20 -> 5.0.
    assertEquals(5.0, h.medianFor("dave\u0000nenhum", "nenhum", 999), 1e-9);
  }

  @Test
  @DisplayName("um par de outro projeto nao contamina a mediana deste")
  void doesNotMixProjects() {
    final PairHistory h = history();

    assertEquals(20.0, h.medianFor("carol\u0000outro", "outro", 100), 1e-9);
  }
}
