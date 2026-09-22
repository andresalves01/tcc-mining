package app.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkingTimeTest {

  // 2020-01-01 foi uma quarta-feira. Sabado 04/01, domingo 05/01.
  private static final Instant QUARTA = Instant.parse("2020-01-01T00:00:00Z");

  @Test
  @DisplayName("dentro da semana e igual ao relogio de parede")
  void weekdaysAreUnchanged() {
    assertEquals(2.0,
        WorkingTime.daysBetween(QUARTA, QUARTA.plus(Duration.ofDays(2))), 1e-9);
  }

  @Test
  @DisplayName("atravessar o fim de semana desconta exatamente dois dias")
  void weekendIsRemoved() {
    // quarta 01/01 ate quarta 08/01: sete dias de calendario, dois deles fim de semana
    assertEquals(5.0,
        WorkingTime.daysBetween(QUARTA, QUARTA.plus(Duration.ofDays(7))), 1e-9);
  }

  @Test
  @DisplayName("um intervalo inteiramente dentro do fim de semana vale zero")
  void aWeekendIsNoTimeAtAll() {
    final Instant sabado = Instant.parse("2020-01-04T02:00:00Z");
    final Instant domingo = Instant.parse("2020-01-05T20:00:00Z");

    assertEquals(0.0, WorkingTime.daysBetween(sabado, domingo), 1e-9);
  }

  @Test
  @DisplayName("sexta a noite ate segunda de manha quase nao e tempo de trabalho")
  void theCaseThisExistsFor() {
    final Instant sextaNoite = Instant.parse("2020-01-03T22:00:00Z");
    final Instant segundaManha = Instant.parse("2020-01-06T08:00:00Z");

    final double parede = Duration.between(sextaNoite, segundaManha).toMillis() / 86_400_000.0;
    final double util = WorkingTime.daysBetween(sextaNoite, segundaManha);

    assertEquals(2.4167, parede, 1e-3);
    assertEquals(0.4167, util, 1e-3, "duas horas de sexta mais oito de segunda");
    assertTrue(util < parede);
  }

  @Test
  @DisplayName("intervalo nao positivo vale zero em vez de negativo")
  void nonPositiveIsZero() {
    assertEquals(0.0, WorkingTime.daysBetween(QUARTA, QUARTA), 1e-9);
    assertEquals(0.0, WorkingTime.daysBetween(QUARTA.plus(Duration.ofDays(1)), QUARTA), 1e-9);
  }
}
