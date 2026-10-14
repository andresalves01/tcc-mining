package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.IssueUnit;
import app.issues.Registration;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.model.Fitting;
import app.pipeline.IssuesEvaluationTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores the external test once, as {@code papers/issues/implementacao/REGISTRO-PREVIO.md} lays it
 * out. Written after that file was committed.
 *
 * <p>Before anything is fitted it checks the matrix and the parameters by SHA-256, the sizes of
 * every side of every population and the never-scored subset against the registration, and refuses
 * on any difference. Then, per population: each cell is refitted on the whole outer training side
 * with its frozen alpha and the lambda of decision 20; the weights are printed; the references are
 * learned on that side; the outer test is scored and read every way section 8 names.
 *
 * <p>Reads CACHE, PARAMETROS and NUNCA_PONTUADAS; writes every prediction to PREVISOES; records the
 * run in ISSUES_DB by insertion. All three populations, always: POPULACOES is not read.
 */
public final class IssuesReservedCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path cache = path("CACHE");
    final Path params = path("PARAMETROS");
    final Path never = path("NUNCA_PONTUADAS");
    final Path out = path("PREVISOES");

    final String matrixSha;
    final String paramsSha;
    final List<RowCache.Entry> entries;
    final Map<Population, Set<String>> neverScored;
    try {
      matrixSha = sha256(cache);
      paramsSha = sha256(params);
      entries = RowCache.read(cache);
      neverScored = neverScored(never);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler as entradas: " + e));
    }
    final List<IssuesEvaluationTask.Frozen> frozen =
        IssuesCheckCommand.parse(params.toString());

    // every gate, for every population, before any row of the test is scored
    final Map<Population, Registration.Sizes> found = new EnumMap<>(Population.class);
    final List<String> problems = new ArrayList<>();
    for (final Population p : Population.MAIN) {
      final Fitting.Sides outer = Fitting.sides(entries, Fitting.Mode.RESERVED, p);
      final Fitting.Sides inner = Fitting.sides(entries, Fitting.Mode.INNER, p);
      final Set<String> test = new HashSet<>();
      outer.scoreUnits().forEach(u -> test.add(u.issueId()));
      final Set<String> ids = neverScored.getOrDefault(p, Set.of());
      if (!test.containsAll(ids)) {
        problems.add(p + ": ha ids nunca pontuados fora do teste externo");
      }
      found.put(p, new Registration.Sizes(outer.fitUnits().size(), outer.scoreUnits().size(),
          inner.fitUnits().size(), inner.scoreUnits().size(), ids.size()));
      if (frozen.stream().filter(f -> f.population() == p).count() != 11) {
        problems.add(p + ": nao ha onze celulas nos parametros");
      }
    }
    problems.addAll(Registration.differences(matrixSha, paramsSha, frozen.size(), found));

    System.out.printf("%n  DURACAO DE ISSUES -- TESTE EXTERNO, EXECUCAO REGISTRADA%n");
    System.out.printf("  registro: papers/issues/implementacao/REGISTRO-PREVIO.md%n");
    System.out.printf("  matriz     %s  sha256 %s%n", cache, matrixSha);
    System.out.printf("  parametros %s  sha256 %s (%d linhas)%n", params, paramsSha,
        frozen.size());
    for (final Population p : Population.MAIN) {
      final Registration.Sizes s = found.get(p);
      System.out.printf("  %-24s treino %,7d  teste %,6d  ajuste/selecao internos %,6d / %,6d  "
          + "nunca pontuadas %,6d%n", IssuesPrinting.name(p), s.train(), s.test(), s.innerFit(),
          s.innerSelection(), s.neverScored());
    }
    if (!problems.isEmpty()) {
      final List<String> lines = new ArrayList<>();
      lines.add("  RECUSADO, nada foi pontuado: o que foi encontrado difere do registro");
      problems.forEach(x -> lines.add("    " + x));
      throw new CommandFailedException(lines);
    }
    System.out.printf("  conferencia contra o registro: tudo igual; o teste externo e pontuado "
        + "agora, uma vez%n");

    final List<Map<String, Object>> rows = new ArrayList<>();
    final StringBuilder predictions = new StringBuilder();
    final Map<String, Object> mains = new LinkedHashMap<>();
    for (final Population p : Population.MAIN) {
      // the registration reads the never-scored subset on the main and the broad populations only
      final IssuesEvaluationTask.Report r = new IssuesEvaluationTask(context.progress()).run(
          entries, frozen, p, Fitting.Mode.RESERVED,
          p == Population.INHERITED ? null : neverScored.get(p));
      mains.put(p.name(), r.main().name());

      System.out.printf("%n  ==== TESTE EXTERNO -- %s ====%n", IssuesPrinting.name(p));
      System.out.printf("  reajuste no treino externo inteiro (%,d linhas); pontuadas %,d linhas "
          + "do teste externo%n", r.fitRows(), r.scoredRows());
      System.out.printf("  referencia principal pela regra da decisao 17 (menor MAE na selecao "
          + "interna): %s (constante %.2f h, mediana do projeto %.2f h); a outra e a secundaria%n",
          r.main().name().toUpperCase(), r.main().constantMaeHours(), r.main().projectMaeHours());
      System.out.printf("  projetos do teste sem issue no treino (nelas a mediana do projeto e a "
          + "constante): %,d issues, %s%n", r.unseenRows(), r.unseenProjects());

      System.out.printf("%n  PARAMETROS: alpha congelado; lambda final = lambda escolhido x %,d / "
          + "%,d (decisao 20); sem recalibracao (decisao 22)%n", r.fitRows(), r.innerFitRows());
      for (final IssuesEvaluationTask.Frozen f : frozen) {
        if (f.population() != p) {
          continue;
        }
        final var fitted = r.evaluated().stream()
            .filter(e -> e.fitted().cell().label().equals(f.cell())).findFirst().orElseThrow()
            .fitted();
        System.out.printf("    %-26s alpha %.2f  lambda escolhido %-14.8g final %-14.8g %d colunas, "
            + "%d com peso%n", f.cell(), fitted.alpha(), f.lambda(), fitted.lambda(),
            fitted.columns().size(), fitted.selected());
      }
      System.out.printf("%n  Os pesos abaixo vem so do treino externo: descrevem o ajuste, nao sao "
          + "importancia estavel nem efeito causal.%n");
      IssuesPrinting.weights(r);

      System.out.printf("%n  ---- PONTUACAO DO TESTE EXTERNO -- %s ----%n",
          IssuesPrinting.name(p));
      rows.addAll(IssuesPrinting.evaluation(r));
      subset(r, rows);
      rows.addAll(comparisonRows(r));
      appendPredictions(predictions, r, neverScored.getOrDefault(p, Set.of()));
    }

    try {
      Files.writeString(out, predictions.toString());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui gravar " + out + ": " + e));
    }
    System.out.printf("%n  previsoes gravadas em %s%n", out);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "issues-reservado");
    run.put("registro", "papers/issues/implementacao/REGISTRO-PREVIO.md");
    run.put("teste_externo", "pontuado");
    run.put("matriz_sha256", matrixSha);
    run.put("parametros_sha256", paramsSha);
    run.put("referencia_principal", mains);
    System.out.printf("  execucao registrada em ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  private static void subset(final IssuesEvaluationTask.Report r,
      final List<Map<String, Object>> rows) {
    final IssuesEvaluationTask.Subset s = r.neverScored();
    if (s == null) {
      System.out.printf("%n  SUBCONJUNTO NUNCA PONTUADO: nao lido nesta populacao (o registro o "
          + "declara inexistente aqui)%n");
      return;
    }
    System.out.printf("%n  SUBCONJUNTO NUNCA PONTUADO (%,d issues; as mesmas previsoes, sem "
        + "reajuste; referencias pontuadas nas mesmas issues)%n", s.n());
    IssuesPrinting.header();
    for (final var row : s.rows()) {
      IssuesPrinting.row(row.score());
      final Map<String, Object> m =
          IssuesPrinting.scoreRow(r.population(), "nunca pontuado", row.score());
      m.put("ic_ganho_projeto", List.of(row.overProject().low(), row.overProject().high()));
      m.put("ic_ganho_constante", List.of(row.overConstant().low(), row.overConstant().high()));
      rows.add(m);
    }
    System.out.printf("  %-52s %24s %24s%n", "  ganho com IC 95% por projetos",
        "sobre mediana do projeto", "sobre a constante");
    for (final var row : s.rows()) {
      System.out.printf("  %-52s %24s %24s%n", row.score().label(),
          IssuesPrinting.interval(row.overProject()), IssuesPrinting.interval(row.overConstant()));
    }
  }

  static List<Map<String, Object>> comparisonRows(final IssuesEvaluationTask.Report r) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    final List<app.issues.model.ProjectBootstrap.Gap> gaps = new ArrayList<>(
        r.familiesByProjects());
    gaps.addAll(r.blocksByProjects());
    final List<app.pullrequests.model.Bootstrap.Comparison> pairs = new ArrayList<>(r.families());
    pairs.addAll(r.blocks());
    for (int i = 0; i < gaps.size(); i++) {
      final var g = gaps.get(i);
      final var c = pairs.get(i);
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("populacao", r.population().name());
      m.put("tipo", "comparacao");
      m.put("a", g.a());
      m.put("b", g.b());
      m.put("dif_mae_horas", g.gapHours());
      m.put("ic_projetos", List.of(g.low(), g.high()));
      m.put("empate", g.tied());
      m.put("ic_linhas", List.of(c.lowGap(), c.highGap()));
      rows.add(m);
    }
    return rows;
  }

  /** One line per scored row; without the never-scored column when {@code neverScored} is null. */
  static void appendPredictions(final StringBuilder out,
      final IssuesEvaluationTask.Report r, final Set<String> neverScored) {
    final List<String> labels = new ArrayList<>();
    final List<double[]> hours = new ArrayList<>();
    for (final var e : r.references()) {
      labels.add(e.fitted().score().label());
      hours.add(e.fitted().predictedHours());
    }
    for (final var e : r.evaluated()) {
      labels.add(e.fitted().cell().label());
      hours.add(e.fitted().predictedHours());
    }
    if (out.isEmpty()) {
      out.append("populacao\tid\tchave\tprojeto")
          .append(neverScored == null ? "" : "\tnunca_pontuada").append("\tobservado_h");
      labels.forEach(l -> out.append('\t').append(l));
      out.append('\n');
    }
    for (int i = 0; i < r.scoredUnits().size(); i++) {
      final IssueUnit u = r.scoredUnits().get(i);
      out.append(r.population().name()).append('\t').append(u.issueId()).append('\t')
          .append(u.key()).append('\t').append(u.project()).append('\t');
      if (neverScored != null) {
        out.append(neverScored.contains(u.issueId()) ? '1' : '0').append('\t');
      }
      out.append(r.observed()[i]);
      for (final double[] h : hours) {
        out.append('\t').append(h[i]);
      }
      out.append('\n');
    }
  }

  private static Path path(final String name) throws CommandFailedException {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE, PARAMETROS, NUNCA_PONTUADAS e PREVISOES "
          + "sao obrigatorios; falta " + name));
    }
    return Path.of(value);
  }

  static String sha256(final Path path) throws IOException {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Lines of {@code populacao<TAB>id}, as {@code 97_subconjuntos_do_teste.py} writes them. */
  static Map<Population, Set<String>> neverScored(final Path path) throws IOException {
    final Map<Population, Set<String>> out = new EnumMap<>(Population.class);
    for (final String line : Files.readAllLines(path)) {
      if (line.isBlank() || line.startsWith("populacao\t")) {
        continue;
      }
      final String[] f = line.split("\t");
      out.computeIfAbsent(Population.valueOf(f[0]), k -> new HashSet<>()).add(f[1]);
    }
    return out;
  }
}
