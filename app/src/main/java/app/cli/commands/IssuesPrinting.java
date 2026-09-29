package app.cli.commands;

import app.cli.CommandFailedException;
import app.paper5.model.Score;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.pipeline.IssuesEvaluationTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the issue-duration study's commands print and record the same way. */
final class IssuesPrinting {

  private IssuesPrinting() {}

  static List<RowCache.Entry> cache() throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<caminho absoluto> e obrigatorio"));
    }
    try {
      return RowCache.read(Path.of(cache));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler " + cache + ": " + e));
    }
  }

  /** POPULACOES=FIXED,BROAD,INHERITED (default: all three, the main one first). */
  static Set<Population> populations() throws CommandFailedException {
    final String env = System.getenv("POPULACOES");
    if (env == null || env.isBlank()) {
      return EnumSet.allOf(Population.class);
    }
    final Set<Population> out = EnumSet.noneOf(Population.class);
    try {
      for (final String p : env.split(",")) {
        out.add(Population.valueOf(p.strip()));
      }
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  POPULACOES aceita FIXED, BROAD, INHERITED"));
    }
    return out;
  }

  static String name(final Population p) {
    return switch (p) {
      case FIXED -> "SO FIXED (principal)";
      case BROAD -> "AMPLA (sensibilidade)";
      case INHERITED -> "HERDADA (condicional)";
    };
  }

  static void header() {
    System.out.printf("  %-52s %6s %9s %8s %8s %9s %8s %8s%n", "especificacao", "n", "MAE h",
        "MAE d", "ganho%", "vs cte%", "Spearman", "par a par");
  }

  static void row(final Score s) {
    System.out.printf("  %-52s %6d %9.2f %8.2f %8.1f %9.1f %8.3f %8.1f%n", s.label(), s.n(),
        s.maeHours(), s.maeDays(), s.gainPercent(), s.gainOverConstantPercent(), s.spearman(),
        100 * s.pairwise());
  }

  static void outcome(final Score.Outcome o) {
    System.out.printf("  alvo (horas): n %,d  media %.1f  mediana %.1f  p25 %.1f  p75 %.1f  "
        + "p90 %.1f  maximo %.0f%n", o.n(), o.meanHours(), o.medianHours(), o.p25Hours(),
        o.p75Hours(), o.p90Hours(), o.maxHours());
  }

  /** The whole evaluation of one population. */
  static List<Map<String, Object>> evaluation(final IssuesEvaluationTask.Report r) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    outcome(r.outcome());
    System.out.printf("%n  TABELA PRINCIPAL (ganho%% contra a mediana do projeto; vs cte%% contra a "
        + "constante; par a par em %%)%n");
    header();
    for (final var e : r.references()) {
      row(e.fitted().score());
      rows.add(scoreRow(r.population(), "referencia", e.fitted().score()));
    }
    for (final var e : r.evaluated()) {
      row(e.fitted().score());
      final Map<String, Object> m = scoreRow(r.population(), "especificacao", e.fitted().score());
      m.put("alpha", e.fitted().alpha());
      m.put("lambda", e.fitted().lambda());
      m.put("colunas", e.fitted().columns());
      m.put("ic_ganho_projeto", List.of(e.overProject().low(), e.overProject().high()));
      m.put("ic_ganho_constante", List.of(e.overConstant().low(), e.overConstant().high()));
      m.put("ic_ganho_par", List.of(e.overPair().low(), e.overPair().high()));
      m.put("mae_1h_365d", e.window().mae());
      m.put("par_a_par_1h_365d", e.window().pairwise());
      m.put("erro_mediano", e.errors().p50());
      rows.add(m);
    }

    System.out.printf("%n  GANHO COM INTERVALO DE 95%% REAMOSTRANDO PROJETOS INTEIROS (%d sorteios)%n",
        app.issues.model.ProjectBootstrap.DRAWS);
    System.out.printf("  %-52s %24s %24s %24s%n", "especificacao", "sobre mediana do projeto",
        "sobre a constante", "sobre o par (so C2)");
    for (final var e : concat(r.references(), r.evaluated())) {
      System.out.printf("  %-52s %24s %24s %24s%n", e.fitted().score().label(),
          interval(e.overProject()), interval(e.overConstant()), interval(e.overPair()));
    }

    System.out.printf("%n  ONDE O ERRO MORA: percentis do erro absoluto (h) e media sem o decimo mais "
        + "lento pela duracao real%n");
    System.out.printf("  %-52s %8s %8s %8s %8s %9s %9s%n", "especificacao", "media", "p50", "p75",
        "p90", "p99", "cortada");
    for (final var e : concat(r.references(), r.evaluated())) {
      final var x = e.errors();
      System.out.printf("  %-52s %8.1f %8.1f %8.1f %8.1f %9.1f %9.1f%n",
          e.fitted().score().label(), x.mae(), x.p50(), x.p75(), x.p90(), x.p99(), x.trimmedMae());
    }

    System.out.printf("%n  POR FAIXA DA DURACAO REAL, PELOS PERCENTIS 25/50/75/90 (MAE em horas)%n");
    final var first = r.references().get(0).bands();
    System.out.printf("  %-52s", "especificacao");
    first.forEach(b -> System.out.printf(" %16s", b.label()));
    System.out.println();
    System.out.printf("  %-52s", "  (n / duracao real mediana)");
    first.forEach(b -> System.out.printf(" %16s",
        String.format("%d / %.0fh", b.n(), b.medianTruth())));
    System.out.println();
    for (final var e : concat(r.references(), r.evaluated())) {
      System.out.printf("  %-52s", e.fitted().score().label());
      e.bands().forEach(b -> System.out.printf(" %16.1f", b.mae()));
      System.out.println();
    }

    final var w0 = r.references().get(0).window();
    System.out.printf("%n  A FAIXA DE 1 HORA A 365 DIAS, LIDA SEM FILTRAR: %,d linhas; 1 h cai no "
        + "percentil %.1f e 365 d no percentil %.1f da duracao real%n", w0.n(), w0.lowPercentile(),
        w0.highPercentile());
    System.out.printf("  %-52s %9s %9s %9s%n", "especificacao", "MAE h", "mediano", "par a par");
    for (final var e : concat(r.references(), r.evaluated())) {
      System.out.printf("  %-52s %9.1f %9.1f %9.1f%n", e.fitted().score().label(),
          e.window().mae(), e.window().medianError(), 100 * e.window().pairwise());
    }

    final var b0 = r.references().get(0).body();
    System.out.printf("%n  O CORPO DA DISTRIBUICAO (percentis 25 a 90 da duracao real: %.1f h a %.1f h, "
        + "%,d linhas)%n", b0.lowHours(), b0.highHours(), b0.n());
    System.out.printf("  %-52s %9s %9s %9s %9s%n", "especificacao", "MAE h", "mediano", "Spearman",
        "par a par");
    for (final var e : concat(r.references(), r.evaluated())) {
      final var b = e.body();
      System.out.printf("  %-52s %9.1f %9.1f %9.3f %9.1f%n", e.fitted().score().label(), b.mae(),
          b.medianError(), b.spearman(), 100 * b.pairwise());
    }

    System.out.printf("%n  FAMILIA CONTRA FAMILIA, NA CRIACAO (C1)%n");
    gaps(r.familiesByProjects());
    comparisons(r.families());
    System.out.printf("%n  C2 POR BLOCO CONTRA C1 DA MESMA FAMILIA (decisao 14: conteudo justifica "
        + "decompor; exposicao nao)%n");
    gaps(r.blocksByProjects());
    comparisons(r.blocks());

    System.out.printf("%n  SENSIBILIDADES (mesmos parametros)%n");
    System.out.printf("  %-44s %-40s %10s %10s %10s %10s%n", "leitura", "celula", "MAE h base",
        "MAE h var.", "par base", "par var.");
    for (final var s : r.sensitivities()) {
      System.out.printf("  %-44s %-40s %10.2f %10.2f %10.1f %10.1f%n", s.what(), s.cell(),
          s.base().maeHours(), s.changed().maeHours(), 100 * s.base().pairwise(),
          100 * s.changed().pairwise());
    }

    if (!r.recalibrated().isEmpty()) {
      recalibration(r);
    }
    if (r.mode() == app.issues.model.Fitting.Mode.INNER) {
      weights(r);
    }
    return rows;
  }

  private static void recalibration(final IssuesEvaluationTask.Report r) {
    System.out.printf("%n  RECALIBRACAO LINEAR EM LOG, AO LADO DO MODELO (pedida em 29/09/2026, "
        + "para decidir depois de ver; nao substitui o modelo)%n");
    System.out.printf("  log1p(h) = a + b * previsao em log, com a e b por minimos quadrados no "
        + "ajuste; monotona: Spearman e par a par nao mudam%n");
    System.out.printf("  %-26s %7s %6s %9s %9s  %-24s %-24s %-16s %-16s%n", "celula", "a", "b",
        "MAE h", "recal.", "vs cte % (modelo)", "vs cte % (recal.)", "p10/p50/p90 mod.",
        "p10/p50/p90 rec.");
    for (final var c : r.recalibrated()) {
      System.out.printf("  %-26s %7.3f %6.3f %9.2f %9.2f  %-24s %-24s %-16s %-16s%n", c.cell(),
          c.a(), c.b(), c.base().maeHours(), c.recalibrated().maeHours(),
          interval(c.baseOverConstant()), interval(c.overConstant()), spread(c.baseSpread()),
          spread(c.spread()));
    }
    System.out.printf("%n  RECALIBRACAO POR FAIXA DA DURACAO REAL (MAE em horas: modelo -> "
        + "recalibrada)%n");
    for (final var c : r.recalibrated()) {
      final StringBuilder line = new StringBuilder(String.format("  %-26s", c.cell()));
      for (int i = 0; i < c.bands().size(); i++) {
        line.append(String.format("  %7.1f -> %7.1f", c.baseBands().get(i).mae(),
            c.bands().get(i).mae()));
      }
      line.append(String.format("   corpo p25-p90 %6.1f -> %6.1f", c.baseBody().mae(),
          c.body().mae()));
      System.out.println(line);
    }
  }

  /** What each column weighed in each fit: a description of the fit, read from the fitting side. */
  static void weights(final IssuesEvaluationTask.Report r) {
    System.out.printf("%n  PESO DE CADA COLUNA NO AJUSTE (coeficiente padronizado | rho proprio, "
        + "ambos no lado de ajuste)%n");
    for (final var e : r.evaluated()) {
      System.out.printf("  -- %s  (alpha %.2f, lambda %.6g)%n", e.fitted().cell().label(),
          e.fitted().alpha(), e.fitted().lambda());
      if (!e.fitted().dropped().isEmpty()) {
        System.out.printf("     retiradas por serem constantes: %s%n", e.fitted().dropped());
      }
      e.fitted().contributions().stream()
          .sorted((a, b) -> Double.compare(Math.abs(b.coefficient()), Math.abs(a.coefficient())))
          .forEach(c -> System.out.printf("     %-44s %+9.4f | %+.3f%s%n", c.column(),
              c.coefficient(), c.ownSpearman(), c.selected() ? "" : "   (zerada)"));
    }
  }

  private static String spread(final double[] p) {
    return String.format("%.0f/%.0f/%.0f", p[0], p[1], p[2]);
  }

  private static <T> List<T> concat(final List<T> a, final List<T> b) {
    final List<T> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  static String interval(final app.issues.model.ProjectBootstrap.Interval i) {
    return String.format("%+6.1f [%+6.1f, %+6.1f]", i.gain(), i.low(), i.high());
  }

  /** The registered tie criterion: the paired interval from resampling whole projects. */
  private static void gaps(final List<app.issues.model.ProjectBootstrap.Gap> list) {
    System.out.printf("  reamostragem pareada de PROJETOS, %d sorteios (criterio registrado: "
        + "intervalo com zero = empate); dif. = MAE(A) - MAE(B), negativa quando A erra menos%n",
        app.issues.model.ProjectBootstrap.DRAWS);
    for (final var g : list) {
      System.out.printf("    %-30s x %-30s  dif. %+8.2f h  IC [%+8.2f, %+8.2f]  %s%n", g.a(),
          g.b(), g.gapHours(), g.low(), g.high(), g.tied() ? "EMPATE" : "distinguivel");
    }
  }

  private static void comparisons(final List<app.paper5.model.Bootstrap.Comparison> list) {
    System.out.printf("  reamostragem pareada de LINHAS, %d sorteios (leitura mais estreita, ao "
        + "lado)%n", app.paper5.model.Bootstrap.DRAWS);
    for (final var c : list) {
      System.out.printf("    %-30s x %-30s  A ganha em %5.1f%%  dif. media %+8.2f h  IC [%+8.2f, "
          + "%+8.2f]  %s%n", c.a(), c.b(), 100 * c.winRate(), c.meanGap(), c.lowGap(), c.highGap(),
          c.indistinguishable() ? "EMPATE" : "distinguivel");
    }
  }

  static Map<String, Object> scoreRow(final Population p, final String kind, final Score s) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("populacao", p.name());
    row.put("tipo", kind);
    row.put("especificacao", s.label());
    row.put("n", s.n());
    row.put("mae_horas", s.maeHours());
    row.put("mae_dias", s.maeDays());
    row.put("ganho_percentual", s.gainPercent());
    row.put("ganho_sobre_constante_percentual", s.gainOverConstantPercent());
    row.put("spearman", s.spearman());
    row.put("acerto_par_a_par", s.pairwise());
    return row;
  }
}
