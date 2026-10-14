package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Universe.Population;
import app.issues.model.Forest;
import app.pipeline.IssuesEvaluationTask;
import app.pipeline.IssuesForestTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The random forest beside the Elastic Net, on the inner selection only (asked on 29/09/2026,
 * after the reserved run). Reads CACHE and the linear model's frozen PARAMETROS; POPULACOES as the
 * other commands. Records the run in ISSUES_DB. The outer test is not read.
 */
public final class IssuesForestCommand implements CommandRunner {

  private static final int TOP = 10;

  @Override
  public void run(final Context context) throws CommandFailedException {
    final var entries = IssuesPrinting.cache();
    final String params = System.getenv("PARAMETROS");
    if (params == null || params.isBlank()) {
      throw new CommandFailedException(List.of("  PARAMETROS=<arquivo da grade> e obrigatorio"));
    }
    final List<IssuesEvaluationTask.Frozen> frozen = IssuesCheckCommand.parse(params);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Population population : IssuesPrinting.populations()) {
      final IssuesForestTask.Report r =
          new IssuesForestTask(context.progress()).run(entries, frozen, population);
      print(r, params);
      rows.addAll(rows(r));
    }
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "issues-floresta");
    run.put("parametros_elastic_net", params);
    run.put("teste_externo", "nao lido");
    run.put("grade_floresta", Map.of("arvores", Forest.TREES, "profundidade", Forest.MAX_DEPTH,
        "fracoes_mtry", List.of(1.0 / 6, 1.0 / 3, 2.0 / 3), "folha_minima",
        List.of(5, 25, 100), "semente", Forest.SEED));
    System.out.printf("%n  execucao registrada em ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  private static void print(final IssuesForestTask.Report r, final String params) {
    System.out.printf("%n  DURACAO DE ISSUES -- RANDOM FOREST AO LADO DO ELASTIC NET -- SELECAO "
        + "INTERNA -- %s%n", IssuesPrinting.name(r.population()));
    System.out.printf("  Elastic Net com os parametros de %s; floresta com a grade declarada: %d "
        + "arvores, profundidade ate %d, colunas por divisao 1/6, 1/3 e 2/3 das da celula, folha "
        + "minima 5, 25 e 100, semente %d%n", params, Forest.TREES, Forest.MAX_DEPTH,
        Forest.SEED);
    System.out.printf("  os dois escolheram parametros NESTA MESMA selecao: numeros otimistas para "
        + "os dois, para comparar estimadores; o teste externo nao e lido%n");
    System.out.printf("  ajuste %,d linhas, pontuadas %,d linhas%n", r.fitRows(), r.scoredRows());

    System.out.printf("%n  TABELA PRINCIPAL (ganho%% contra a mediana do projeto; vs cte%% contra a "
        + "constante; par a par em %%)%n");
    IssuesPrinting.header();
    IssuesPrinting.row(r.project());
    IssuesPrinting.row(r.constant());
    for (final var c : r.cells()) {
      IssuesPrinting.row(c.linear().score());
      IssuesPrinting.row(c.forest().score());
    }

    System.out.printf("%n  GANHO COM INTERVALO DE 95%% REAMOSTRANDO PROJETOS INTEIROS (%d sorteios)%n",
        app.issues.model.ProjectBootstrap.DRAWS);
    System.out.printf("  %-44s %24s %24s %24s%n", "celula", "floresta sobre mediana proj.",
        "floresta sobre constante", "Elastic Net sobre cte");
    for (final var c : r.cells()) {
      System.out.printf("  %-44s %24s %24s %24s%n", c.linear().cell().label(),
          IssuesPrinting.interval(c.forestOverProject()),
          IssuesPrinting.interval(c.forestOverConstant()),
          IssuesPrinting.interval(c.linearOverConstant()));
    }

    System.out.printf("%n  FLORESTA CONTRA ELASTIC NET, MESMA CELULA: reamostragem pareada de "
        + "PROJETOS, %d sorteios (intervalo com zero = empate); dif. = MAE(floresta) - "
        + "MAE(Elastic Net), negativa quando a floresta erra menos%n",
        app.issues.model.ProjectBootstrap.DRAWS);
    for (final var c : r.cells()) {
      final var g = c.forestMinusLinear();
      System.out.printf("    %-30s dif. %+9.2f h  IC [%+9.2f, %+9.2f]  %s%n",
          c.linear().cell().label(), g.gapHours(), g.low(), g.high(),
          g.tied() ? "EMPATE" : "distinguivel");
    }

    System.out.printf("%n  GRADE DA FLORESTA: MAE (h) na selecao interna por colunas por divisao x "
        + "folha minima; * = escolhida%n");
    for (final var c : r.cells()) {
      final var f = c.forest();
      final StringBuilder line = new StringBuilder();
      for (final Forest.Trial t : f.trials()) {
        final boolean chosen = t.mtry() == f.mtry() && t.nodeSize() == f.nodeSize();
        line.append(String.format("  %d/%d %.2f%s", t.mtry(), t.nodeSize(), t.maeHours(),
            chosen ? "*" : ""));
      }
      System.out.printf("    %-26s %d colunas:%s%n", f.cell().label(), f.columns().size(), line);
    }

    System.out.printf("%n  IMPORTANCIA NA FLORESTA ESCOLHIDA (queda do erro quadratico somada nas "
        + "divisoes, em %% do total; descreve o ajuste, nao e efeito causal), as %d maiores%n", TOP);
    for (final var c : r.cells()) {
      final var f = c.forest();
      double total = 0;
      for (final double v : f.importance()) {
        total += v;
      }
      final List<Integer> order = new ArrayList<>();
      for (int i = 0; i < f.importance().length; i++) {
        order.add(i);
      }
      order.sort(Comparator.comparingDouble((Integer i) -> -f.importance()[i]));
      System.out.printf("  -- %s  (colunas por divisao %d, folha minima %d)%n", f.cell().label(),
          f.mtry(), f.nodeSize());
      for (final int i : order.subList(0, Math.min(TOP, order.size()))) {
        System.out.printf("     %-46s %5.1f%%%n", f.columns().get(i),
            total == 0 ? 0 : 100 * f.importance()[i] / total);
      }
    }
  }

  private static List<Map<String, Object>> rows(final IssuesForestTask.Report r) {
    final List<Map<String, Object>> out = new ArrayList<>();
    out.add(IssuesPrinting.scoreRow(r.population(), "referencia", r.project()));
    out.add(IssuesPrinting.scoreRow(r.population(), "referencia", r.constant()));
    for (final var c : r.cells()) {
      out.add(IssuesPrinting.scoreRow(r.population(), "elastic net", c.linear().score()));
      final Map<String, Object> m =
          IssuesPrinting.scoreRow(r.population(), "floresta", c.forest().score());
      m.put("mtry", c.forest().mtry());
      m.put("folha_minima", c.forest().nodeSize());
      m.put("ic_ganho_projeto", List.of(c.forestOverProject().low(),
          c.forestOverProject().high()));
      m.put("ic_ganho_constante", List.of(c.forestOverConstant().low(),
          c.forestOverConstant().high()));
      m.put("dif_mae_floresta_menos_elastic_net", c.forestMinusLinear().gapHours());
      m.put("ic_projetos", List.of(c.forestMinusLinear().low(), c.forestMinusLinear().high()));
      out.add(m);
    }
    return out;
  }
}
