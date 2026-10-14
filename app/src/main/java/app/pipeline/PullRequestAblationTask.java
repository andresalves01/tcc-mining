package app.pipeline;

import app.model.ElasticNetModel;
import app.pullrequests.PriorStudyFeatures;
import app.pullrequests.Attribute;
import app.pullrequests.Attributes;
import app.pullrequests.Row;
import app.pullrequests.RowCache;
import app.pullrequests.Unit;
import app.pullrequests.model.Baseline;
import app.pullrequests.model.Bootstrap;
import app.pullrequests.model.Cell;
import app.pullrequests.model.DesignMatrix;
import app.pullrequests.model.History;
import app.pullrequests.model.Score;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Do the literature's variables beat the size of the change, once the size is taken away from them?
 *
 * <p>The per-column measurement found that additions alone order 60,4% of pairs while the largest
 * specification, with sixty-nine columns, reaches 63,6%. That invites a question none of the earlier
 * runs answers: <b>strip the size out of each specification and put what is left against a model
 * that is nothing but the size.</b> If the stripped specifications lose, then the three papers'
 * contribution over "big change, slow merge" is smaller than any of their tables suggest.
 *
 * <p>Two cuts, because "size" has two meanings that the corpus records separately. The first removes
 * <b>lines</b> -- added, deleted, and the previous paper's Java-only count. The second also removes
 * <b>file counts</b>, which are a different construct measuring the same idea. Reporting both stops
 * the answer from depending on where one person happens to draw the word.
 *
 * <p>Every ablated specification keeps the alpha and the lambda of the full one. Retuning would
 * change two things at once and the comparison would stop being an ablation.
 *
 * <p>Development side only: the reserved partition was scored once and is not reopened for this.
 */
public final class PullRequestAblationTask {

  /** Lines of code changed, under every name the three specifications give it. */
  public static final Set<String> LINHAS = Set.of("additions_at_open", "deletions_at_open",
      "loc_iss");

  /** File counts: the same idea measured as a count of files rather than of lines. */
  public static final Set<String> ARQUIVOS = Set.of("num_files_at_open", "pr_files");

  /**
   * The single column the whole comparison is against: lines <b>changed</b>, added plus deleted.
   *
   * <p>Not additions alone. None of the three specifications declares the sum -- they all count the
   * two legs separately -- but the quantity a reader means by "the size of a change" is the total
   * touched, not half of it, and the opponent in this comparison has to be the quantity the sentence
   * is about. It is derived here and belongs to no scenario.
   */
  public static final String SOZINHA = "lines_changed_at_open";

  /**
   * The fair opponent: the size of the change as the corpus measures it, both ways at once.
   *
   * <p>An earlier version of this comparison put one column -- lines -- against specifications that
   * had been stripped of two. That handicapped the side being tested. Lines and file counts are not
   * the same information: removing lines alone costs a specification little, because the file count
   * is still there telling the same story, and only removing both makes a difference. So the model
   * that stands for "size" has to carry both, exactly what the ablation takes away.
   */
  public static final List<String> TAMANHO = List.of("lines_changed_at_open", "num_files_at_open");

  public record Measured(String label, int columns, Score score, double[] predictedHours) {}

  public record Report(Score baseline, Score constant, List<Measured> measured,
      List<Bootstrap.Comparison> comparisons, int fitRows, int selectionRows) {}

  private final app.pipeline.port.Progress progress;

  public PullRequestAblationTask(final app.pipeline.port.Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries, final PriorStudyFeatures priorStudy) {
    final List<Unit> fitUnits = new ArrayList<>();
    final List<Row> fitRows = new ArrayList<>();
    final List<Unit> scoreUnits = new ArrayList<>();
    final List<Row> scoreRows = new ArrayList<>();
    for (final RowCache.Entry entry : entries) {
      if (!"TRAIN".equals(entry.outer())) {
        continue;
      }
      if ("TRAIN".equals(entry.inner())) {
        fitUnits.add(entry.unit());
        fitRows.add(entry.row());
      } else if ("TEST".equals(entry.inner())) {
        scoreUnits.add(entry.unit());
        scoreRows.add(entry.row());
      }
    }
    final double[] observed = new double[scoreUnits.size()];
    for (int i = 0; i < observed.length; i++) {
      observed[i] = scoreUnits.get(i).durationHours();
    }
    final Baseline baseline = Baseline.learnedOn(fitUnits);
    final double[] baselineHours = new double[scoreUnits.size()];
    for (int i = 0; i < baselineHours.length; i++) {
      baselineHours[i] = baseline.predictHours(scoreUnits.get(i));
    }
    final double[] constantHours = new double[scoreUnits.size()];
    java.util.Arrays.fill(constantHours, baseline.constantHours());
    final double constantMae = PullRequestAuditTask.mae(constantHours, observed);
    // Corrigido em 28/09/2026: o quarto argumento e o ERRO da constante nestas linhas, nao o valor
    // que ela preve. Antes passava-se a previsao (68,2 h), e a coluna "ganho sobre a constante" das
    // duas linhas de referencia saia -262% e -235% em vez de -8,3% e 0. As linhas dos modelos nunca
    // foram afetadas: elas ja recebiam o erro da constante.
    final Score baselineScore = Score.reference("referencia: mediana do projeto",
        baselineHours, observed, constantMae);
    final Score constantScore = Score.reference("calibracao: constante",
        constantHours, observed, constantMae);

    final History history = History.learnedOn(fitUnits);
    for (int i = 0; i < fitRows.size(); i++) {
      fitRows.set(i, history.augment(fitRows.get(i), fitUnits.get(i)));
    }
    for (int i = 0; i < scoreRows.size(); i++) {
      scoreRows.set(i, history.augment(scoreRows.get(i), scoreUnits.get(i)));
    }
    if (priorStudy != null) {
      for (int i = 0; i < fitRows.size(); i++) {
        fitRows.set(i, priorStudy.augment(fitRows.get(i), fitUnits.get(i).pullRequestId()));
      }
      for (int i = 0; i < scoreRows.size(); i++) {
        scoreRows.set(i, priorStudy.augment(scoreRows.get(i), scoreUnits.get(i).pullRequestId()));
      }
    }
    // A derivada, posta nas linhas antes de qualquer ajuste. Ausente quando qualquer das duas
    // pernas falta, porque metade de uma soma nao e a soma.
    somaLinhas(fitRows);
    somaLinhas(scoreRows);

    final double[] y = new double[fitUnits.size()];
    for (int i = 0; i < y.length; i++) {
      y[i] = DesignMatrix.target(fitUnits.get(i));
    }

    final List<Measured> measured = new ArrayList<>();
    int done = 0;
    final int total = PullRequestReservedTask.FROZEN.size() * 3 + 2;

    // O adversario: um modelo que e so o tamanho. Penalizacao praticamente nula, porque uma coluna
    // nao precisa ser selecionada nem encolhida -- nao ha entre o que distribuir peso.
    progress.item(++done, total, SOZINHA, "modelo de uma variavel");
    measured.add(fit("SO linhas alteradas", List.of(Attributes.byName(SOZINHA)),
        fitRows, scoreRows, y, observed, baselineScore, constantScore, 0.95, 1e-6));

    progress.item(++done, total, "tamanho", "linhas + arquivos");
    measured.add(fit("SO tamanho (linhas + arquivos)",
        TAMANHO.stream().map(Attributes::byName).toList(),
        fitRows, scoreRows, y, observed, baselineScore, constantScore, 0.95, 1e-6));

    for (final PullRequestReservedTask.Frozen frozen : PullRequestReservedTask.FROZEN) {
      final Cell full = Cell.of(frozen.family(), PullRequestReservedTask.PROFILE);
      final List<Attribute> todas = full.attributes();

      progress.item(++done, total, frozen.family().toString(), "completo");
      measured.add(fit(frozen.family() + " completo", todas, fitRows, scoreRows, y, observed,
          baselineScore, constantScore, frozen.alpha(), frozen.lambda()));

      progress.item(++done, total, frozen.family().toString(), "sem linhas");
      measured.add(fit(frozen.family() + " SEM linhas",
          todas.stream().filter(a -> !LINHAS.contains(a.name())).toList(),
          fitRows, scoreRows, y, observed, baselineScore, constantScore,
          frozen.alpha(), frozen.lambda()));

      progress.item(++done, total, frozen.family().toString(), "sem linhas nem arquivos");
      measured.add(fit(frozen.family() + " SEM linhas nem arquivos",
          todas.stream()
              .filter(a -> !LINHAS.contains(a.name()) && !ARQUIVOS.contains(a.name())).toList(),
          fitRows, scoreRows, y, observed, baselineScore, constantScore,
          frozen.alpha(), frozen.lambda()));
    }

    // A comparacao simetrica: quem perdeu as duas medidas de tamanho contra um modelo que tem as
    // duas. E a que responde "os construtos da literatura valem mais do que o tamanho da mudanca?".
    final Measured sozinha = measured.get(0);
    final Measured tamanho = measured.get(1);
    final List<Bootstrap.Comparison> comparisons = new ArrayList<>();
    for (final Measured m : measured) {
      if (m.label().contains("SEM linhas nem arquivos")) {
        comparisons.add(Bootstrap.compare(m.label(), Bootstrap.errors(m.predictedHours(), observed),
            tamanho.label(), Bootstrap.errors(tamanho.predictedHours(), observed)));
      }
    }
    // E a assimetrica, mantida porque responde outra coisa: o que sobra quando so as linhas saem.
    for (final Measured m : measured) {
      if (m.label().endsWith("SEM linhas")) {
        comparisons.add(Bootstrap.compare(m.label(), Bootstrap.errors(m.predictedHours(), observed),
            sozinha.label(), Bootstrap.errors(sozinha.predictedHours(), observed)));
      }
    }

    return new Report(baselineScore, constantScore, List.copyOf(measured),
        List.copyOf(comparisons), fitUnits.size(), scoreUnits.size());
  }

  private static void somaLinhas(final List<Row> rows) {
    for (int i = 0; i < rows.size(); i++) {
      final Row row = rows.get(i);
      final Double mais = row.values().get("additions_at_open");
      final Double menos = row.values().get("deletions_at_open");
      final java.util.Map<String, Double> values = new java.util.HashMap<>(row.values());
      final Set<String> absent = new java.util.HashSet<>(row.absent());
      if (mais == null || menos == null) {
        absent.add(SOZINHA);
      } else {
        values.put(SOZINHA, mais + menos);
      }
      rows.set(i, new Row(row.unitId(), values, absent));
    }
  }

  private static Measured fit(final String label, final List<Attribute> attributes,
      final List<Row> fitRows, final List<Row> scoreRows, final double[] y,
      final double[] observed, final Score baseline, final Score constant,
      final double alpha, final double lambda) {

    final DesignMatrix matrix = DesignMatrix.fittedOn(fitRows, attributes);
    final ElasticNetModel model =
        ElasticNetModel.fit(matrix.applyAll(fitRows), y, matrix.columns(), alpha, lambda);
    final double[][] xs = matrix.applyAll(scoreRows);
    final double[] predicted = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      predicted[i] = DesignMatrix.hours(model.predict(xs[i]));
    }
    return new Measured(label, matrix.columns().size(),
        Score.of(label, predicted, observed, baseline.maeHours(), constant.maeHours()), predicted);
  }
}
