package app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O que a duracao passada daquela pessoa naquele projeto dizia, no instante em que a tarefa abriu.
 *
 * <p>Existe porque a mediana do par ja era a referencia primaria do estudo e vinha ganhando do
 * modelo inteiro em alguns alvos -- na espera, 0,237 contra -0,083 -- sem nunca ter sido oferecida
 * ao ajuste. Era rival, nao ingrediente. Esta classe a transforma em coluna.
 *
 * <p><b>Estritamente anterior, e so do lado do ajuste.</b> Duas regras, e as duas sao necessarias
 * por motivos diferentes. Do lado do ajuste porque um desfecho da particao que se esta pontuando
 * nao pode entrar em preditor nenhum. Estritamente anterior porque, sem isso, a mediana do par
 * incluiria a propria linha: sobre um par com cinco rotulos, a linha responderia por um quinto da
 * sua propria previsao. A regra de anterioridade e a mesma que {@code Seniority} ja documenta --
 * uma observacao exatamente em {@code t} ainda nao e observavel em {@code t}.
 *
 * <p>A cadeia de recurso e a de {@link Baselines}: par, depois projeto, depois tudo. O recurso ao
 * projeto usa a mediana do lado do ajuste inteiro e nao a como-de, porque e recurso -- e com que
 * frequencia ele e alcancado e resultado a reportar, nao detalhe a esconder.
 */
public final class PairHistory {

  /** Uma observacao do lado do ajuste, reduzida ao que a mediana precisa. */
  public record Entry(String pair, String project, long at, double logValue) {}

  private final Map<String, List<Entry>> byPair;
  private final Map<String, Double> byProject;
  private final double global;

  private PairHistory(final Map<String, List<Entry>> byPair,
      final Map<String, Double> byProject, final double global) {

    this.byPair = byPair;
    this.byProject = byProject;
    this.global = global;
  }

  public static PairHistory of(final List<Entry> pool) {
    final Map<String, List<Entry>> byPair = new HashMap<>();
    final Map<String, List<Double>> perProject = new HashMap<>();
    final List<Double> all = new ArrayList<>(pool.size());
    for (final Entry entry : pool) {
      byPair.computeIfAbsent(entry.pair(), k -> new ArrayList<>()).add(entry);
      perProject.computeIfAbsent(entry.project(), k -> new ArrayList<>()).add(entry.logValue());
      all.add(entry.logValue());
    }
    byPair.values().forEach(list -> list.sort((a, b) -> Long.compare(a.at(), b.at())));
    final Map<String, Double> byProject = new HashMap<>(perProject.size() * 2);
    perProject.forEach((project, values) -> byProject.put(project, median(values)));
    return new PairHistory(byPair, byProject, all.isEmpty() ? 0 : median(all));
  }

  /** Quantas observacoes daquele par sao estritamente anteriores a {@code at}. */
  public int priorCount(final String pair, final long at) {
    final List<Entry> history = byPair.get(pair);
    if (history == null) {
      return 0;
    }
    int count = 0;
    for (final Entry entry : history) {
      if (entry.at() >= at) {
        break;
      }
      count++;
    }
    return count;
  }

  /** A mediana como-de do par, com recurso ao projeto e depois ao conjunto inteiro. */
  public double medianFor(final String pair, final String project, final long at) {
    final List<Entry> history = byPair.get(pair);
    if (history != null) {
      final List<Double> prior = new ArrayList<>();
      for (final Entry entry : history) {
        if (entry.at() >= at) {
          break;
        }
        prior.add(entry.logValue());
      }
      if (!prior.isEmpty()) {
        return median(prior);
      }
    }
    return byProject.getOrDefault(project, global);
  }

  private static double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }
}
