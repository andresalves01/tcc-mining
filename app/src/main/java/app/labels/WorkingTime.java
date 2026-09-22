package app.labels;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Duracao com os fins de semana descontados.
 *
 * <p>Existe porque boa parte da variacao que estavamos tentando explicar nao e sobre pessoa
 * nenhuma. Um pedido aberto numa sexta a noite e mesclado na segunda de manha leva dois dias e
 * meio de relogio de parede e quase nenhum tempo de trabalho, e nenhum preditor deste estudo tem
 * como saber a diferenca. Sabado e domingo sao 2/7 do calendario, entao e a maior fatia unica
 * desse ruido que da para remover sem inventar nada.
 *
 * <p><b>O que esta medida nao faz, e por que.</b> Nao desconta noites. Descontar exigiria saber o
 * fuso de quem trabalhou, e o corpus nao carrega isso no pull request -- {@code created_at} e
 * UTC, sem deslocamento. Em 98 projetos de codigo aberto com gente espalhada pelo mundo, assumir
 * um horario comercial unico trocaria um vies conhecido por um inventado. Fim de semana tem o
 * mesmo problema em menor grau, porque o calendario de descanso nao e universal, mas sabado e
 * domingo coincidem em quase todo lugar de onde vem este corpus.
 *
 * <p>A medida e continua, nao arredondada para dias: um intervalo dentro do mesmo dia util fica
 * igual ao de relogio de parede, e so encolhe quando atravessa um fim de semana.
 */
public final class WorkingTime {

  private static final double DAY_MILLIS = 86_400_000.0;

  private WorkingTime() {}

  /** Dias uteis fracionarios entre dois instantes, descontando sabados e domingos. */
  public static double daysBetween(final Instant from, final Instant to) {
    if (!to.isAfter(from)) {
      return 0;
    }
    final long total = Duration.between(from, to).toMillis();
    return (total - weekendMillis(from, to)) / DAY_MILLIS;
  }

  /** Quantos milissegundos do intervalo caem em sabado ou domingo, em UTC. */
  private static long weekendMillis(final Instant from, final Instant to) {
    long weekend = 0;
    ZonedDateTime cursor = from.atZone(ZoneOffset.UTC);
    final ZonedDateTime end = to.atZone(ZoneOffset.UTC);
    while (cursor.isBefore(end)) {
      final ZonedDateTime nextMidnight = cursor.toLocalDate().plusDays(1)
          .atStartOfDay(ZoneOffset.UTC);
      final ZonedDateTime sliceEnd = nextMidnight.isBefore(end) ? nextMidnight : end;
      final DayOfWeek day = cursor.getDayOfWeek();
      if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
        weekend += Duration.between(cursor, sliceEnd).toMillis();
      }
      cursor = sliceEnd;
    }
    return weekend;
  }
}
