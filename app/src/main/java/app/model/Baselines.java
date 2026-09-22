package app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * What the model has to beat, built only from the fit side.
 *
 * <p>The study names one of these as its primary reference: the historical median duration of the
 * (developer, project) pair, computed exclusively from resolutions before the cut. No outcome from
 * the partition being scored takes part in building it — that is the whole point of computing it
 * here rather than over everything.
 *
 * <p>A pair with no history falls back to the project, and a project with none to the corpus. The
 * chain is stated rather than silent, because how often it is reached is itself a result: a
 * reference that mostly falls back is not a per-pair reference.
 */
public final class Baselines {

  private Baselines() {}

  /** The reference the study declares primary, with its fallbacks. */
  public static ToDoubleFunction<Observation> perPair(final List<Observation> fit) {
    final Map<String, Double> byPair = medianBy(fit, Observation::pair);
    final Map<String, Double> byProject = medianBy(fit, Observation::project);
    final double global = median(fit.stream().map(Observation::logTtc).toList());
    return row -> byPair.getOrDefault(row.pair(),
        byProject.getOrDefault(row.project(), global));
  }

  /** How often the per-pair reference actually has a pair, which decides what it can be called. */
  public static double pairCoverage(final List<Observation> fit, final List<Observation> scored) {
    final Map<String, Double> byPair = medianBy(fit, Observation::pair);
    return scored.stream().filter(row -> byPair.containsKey(row.pair())).count()
        / (double) scored.size();
  }

  /**
   * Prever toda tarefa de uma pessoa pela media dela, sem olhar a tarefa.
   *
   * <p>Deliberadamente ingenua: nao ve tamanho, nem equipe, nem revisao. Serve para responder uma
   * pergunta que nenhuma outra referencia responde -- quanto de ordenacao de pessoas se consegue
   * sabendo <i>so</i> quem e a pessoa.
   *
   * <p>De onde vem a media muda tudo, e por isso existem duas chamadas possiveis. Construida sobre
   * o lado do ajuste, e um metodo honesto e comparavel ao modelo. Construida sobre as proprias
   * linhas que estao sendo pontuadas, e um oraculo: usa a resposta para montar o palpite, nao pode
   * ser usada para prever nada, e vale como TETO -- o maximo que qualquer metodo que ordene
   * pessoas por um numero por pessoa poderia alcancar naquele conjunto.
   */
  public static ToDoubleFunction<Observation> perDeveloper(final List<Observation> from) {
    final Map<String, Double> byDeveloper = meanBy(from, Observation::developer);
    final double global = median(from.stream().map(Observation::logTtc).toList());
    return row -> byDeveloper.getOrDefault(row.developer(), global);
  }

  private static Map<String, Double> meanBy(final List<Observation> rows,
      final Function<Observation, String> key) {

    final Map<String, List<Double>> grouped = new HashMap<>();
    for (final Observation row : rows) {
      grouped.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(row.logTtc());
    }
    final Map<String, Double> means = new HashMap<>(grouped.size() * 2);
    grouped.forEach((k, values) ->
        means.put(k, values.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
    return means;
  }

  /**
   * Prever cada tarefa pela mediana historica das tarefas de tamanho parecido.
   *
   * <p>O analogo, do lado da tarefa, de {@link #perDeveloper}. Uma pessoa e uma entidade que se
   * repete e por isso da para "repetir o que ela fez antes"; uma tarefa nao. Repetir, aqui, e
   * agrupar tarefas parecidas e devolver a mediana do grupo -- uma tabela de consulta por tamanho,
   * sem modelo nenhum.
   *
   * <p>Os grupos sao decis do tamanho da tarefa calculados <b>so no lado do ajuste</b>, pelo mesmo
   * motivo que toda referencia aqui e: um corte derivado das linhas que se esta pontuando deixaria
   * a referencia ver a resposta. Dez grupos e escolha declarada antes de ver o resultado, e nao
   * foi variada depois: com poucos grupos a tabela vira a mediana global, com muitos ela decora.
   */
  public static ToDoubleFunction<Observation> perTaskSize(final List<Observation> fit) {
    final int buckets = 10;
    final int column = Columns.taskSize();
    final List<Double> sizes = fit.stream()
        .map(row -> row.values()[column]).sorted().toList();
    final double[] cuts = new double[buckets - 1];
    for (int i = 1; i < buckets; i++) {
      cuts[i - 1] = sizes.get((int) ((long) i * sizes.size() / buckets));
    }
    final Map<Integer, Double> byBucket = medianBy(fit, row -> String.valueOf(bucketOf(row.values()[column], cuts)))
        .entrySet().stream()
        .collect(java.util.stream.Collectors.toMap(e -> Integer.valueOf(e.getKey()), Map.Entry::getValue));
    final double global = median(fit.stream().map(Observation::logTtc).toList());
    return row -> byBucket.getOrDefault(bucketOf(row.values()[column], cuts), global);
  }

  private static int bucketOf(final double size, final double[] cuts) {
    int bucket = 0;
    while (bucket < cuts.length && size >= cuts[bucket]) {
      bucket++;
    }
    return bucket;
  }

  public static ToDoubleFunction<Observation> perProject(final List<Observation> fit) {
    final Map<String, Double> byProject = medianBy(fit, Observation::project);
    final double global = median(fit.stream().map(Observation::logTtc).toList());
    return row -> byProject.getOrDefault(row.project(), global);
  }

  /** Guessing the same number every time: the floor any model has to clear to mean anything. */
  public static ToDoubleFunction<Observation> constant(final List<Observation> fit) {
    final double global = median(fit.stream().map(Observation::logTtc).toList());
    return row -> global;
  }

  private static Map<String, Double> medianBy(final List<Observation> fit,
      final Function<Observation, String> key) {

    final Map<String, List<Double>> grouped = new HashMap<>();
    for (final Observation row : fit) {
      grouped.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(row.logTtc());
    }
    final Map<String, Double> medians = new HashMap<>(grouped.size() * 2);
    grouped.forEach((k, values) -> medians.put(k, median(values)));
    return medians;
  }

  private static double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }
}
