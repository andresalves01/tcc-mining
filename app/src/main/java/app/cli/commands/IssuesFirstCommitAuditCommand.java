package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.FirstCommit;
import app.issues.SplitRule;
import app.issues.Universe.Population;
import app.pipeline.IssuesFirstCommitAuditTask;
import app.pipeline.IssuesFirstCommitAuditTask.IssueRow;
import app.pipeline.IssuesFirstCommitAuditTask.Report;
import app.pipeline.IssuesFirstCommitAuditTask.Side;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The first-commit population and its target on the training side, as files in AUDIT_DIR: the
 * summary {@code audit.md} and {@code per-issue.tsv}. The split is drawn by the rule {@code
 * SPLIT_RULE} names ({@link SplitRule}), random by default; under the temporal rule the summary
 * adds the cuts, the target of each training side, the projects of each side, the crossings and
 * the censoring at the end of the data. Reads the corpus; writes to no database.
 */
public final class IssuesFirstCommitAuditCommand implements CommandRunner {

  private static final Locale PT = Locale.forLanguageTag("pt-BR");

  private final Function<String, String> settings;

  public IssuesFirstCommitAuditCommand() {
    this(n -> {
      final String s = System.getenv(n);
      return s == null ? System.getProperty(n) : s;
    });
  }

  public IssuesFirstCommitAuditCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  @Override
  public void run(final Context context) throws Exception {
    final String dir = settings.apply("AUDIT_DIR");
    if (dir == null || dir.isBlank()) {
      throw new CommandFailedException(List.of("  AUDIT_DIR=<output directory> is required"));
    }
    final SplitRule rule;
    try {
      rule = SplitRule.declared(settings.apply(SplitRule.SETTING), Population.FIRST_COMMIT);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }
    final Path root = Path.of(dir);
    Files.createDirectories(root);
    final Report r =
        new IssuesFirstCommitAuditTask(context.study(), context.progress(), rule).run();
    write(root, r);
    System.out.printf("%n  First-commit audit: %,d issues drawn (training %,d, test %,d); cap %d "
        + "business days%n", r.sizes().rows(), r.sizes().train(), r.sizes().test(),
        r.threshold().cap());
    if (r.temporal() != null) {
      System.out.printf("  temporal split: outer cut %s, inner cut %s%n",
          r.temporal().cuts().outer(), r.temporal().cuts().inner());
    }
    System.out.printf("  written to %s%n", root.toAbsolutePath());
  }

  static void write(final Path root, final Report r) throws IOException {
    Files.write(root.resolve("audit.md"), summary(r));
    Files.write(root.resolve("per-issue.tsv"), perIssue(r));
  }

  private static String n(final long v) {
    return String.format(PT, "%,d", v);
  }

  private static String d(final double v) {
    return String.format(PT, "%,.2f", v);
  }

  static List<String> summary(final Report r) {
    final List<String> md = new ArrayList<>();
    md.add("# Estudo do primeiro commit: auditoria da população e do alvo");
    md.add("");
    md.add("Só leitura: nada foi gravado em banco. Do teste externo, só o tamanho aparece aqui.");
    md.add("");
    md.add("## Funil");
    md.add("");
    md.add("| Etapa | Restam | Saem |");
    md.add("|---|---:|---:|");
    for (int k = 0; k < r.funnel().size(); k++) {
      final IssuesFirstCommitAuditTask.Step s = r.funnel().get(k);
      md.add("| " + s.label() + " | " + n(s.remaining()) + " | "
          + (k == 0 ? "—" : n(s.removed())) + " |");
    }
    md.add("");
    md.add("O limite do primeiro commit é " + d(100 * FirstCommit.SHARE) + "% das linhas "
        + "alteradas (adicionadas mais removidas, em todos os arquivos dos commits que contam).");
    md.add("");
    md.add("## Commits que contam");
    md.add("");
    md.add("- Commits que contam, nas issues da base: " + n(r.code().counted()) + ".");
    md.add("- Originais recuperados de PR entre eles: " + n(r.code().originals())
        + "; commits do repositório substituídos por eles: " + n(r.code().replaced()) + ".");
    md.add("- Contas de bot da regra do estudo In Progress (sobre o universo): " + n(r.bots())
        + ".");
    md.add("");
    md.add("## Duplicatas");
    md.add("");
    md.add("- Issues com algum commit removido: " + n(r.duplicates().issues()) + ".");
    md.add("- Commits removidos por mesmo autor e mesma data: "
        + n(r.duplicates().sameAuthorDate()) + ".");
    md.add("- Commits removidos por mesma primeira linha e mesmas linhas alteradas, em outra "
        + "data (fica o mais antigo): " + n(r.duplicates().sameTitleLines()) + ".");
    md.add("- Issues com 2 ou mais commits que ficaram com menos de 2 depois das duplicatas: "
        + n(r.duplicates().belowTwo()) + ".");
    md.add("");
    md.add("## Partição");
    md.add("");
    md.add(r.rule().rule() + ".");
    md.add("");
    final IssuesFirstCommitAuditTask.Sizes z = r.sizes();
    md.add("| Sorteadas | Projetos | Treino externo | Teste externo | Ajuste interno "
        + "| Validação interna |");
    md.add("|---:|---:|---:|---:|---:|---:|");
    md.add("| " + n(z.rows()) + " | " + n(z.projects()) + " | " + n(z.train()) + " | "
        + n(z.test()) + " | " + n(z.fit()) + " | " + n(z.validation()) + " |");
    md.add("");
    if (r.temporal() != null) {
      temporalSides(md, r);
    }
    md.add("## Teto das pausas, aprendido no ajuste interno");
    md.add("");
    final FirstCommit.Threshold t = r.threshold();
    md.add("| Q1 | Mediana | Q3 | Cerca (Q3 + 1,5 IQR) | Teto | Intervalos |");
    md.add("|---:|---:|---:|---:|---:|---:|");
    md.add("| " + d(t.q1()) + " | " + d(t.median()) + " | " + d(t.q3()) + " | " + d(t.fence())
        + " | " + n(t.cap()) + " | " + n(t.gaps()) + " |");
    md.add("");
    if (r.temporal() != null) {
      final FirstCommit.Threshold old = r.temporal().random();
      md.add("O teto acima é aprendido só nas esperas do ajuste interno temporal e aplicado "
          + "congelado a todas as issues. Na divisão aleatória, sobre as mesmas issues e nesta "
          + "mesma rodada, o teto é " + n(old.cap()) + " dias úteis (cerca " + d(old.fence())
          + ", " + n(old.gaps()) + " intervalos); a auditoria de 25/10/2026, com a divisão "
          + "aleatória, aprendeu 2.");
      md.add("");
    }
    md.add("## Alvo no treino externo, em dias úteis");
    md.add("");
    md.add("| Issues | Mín. | Q1 | Mediana | Q3 | P90 | Máx. | Média | Soma |");
    md.add("|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
    md.add("| " + n(r.training().target().n()) + " | " + quantiles(r.training().target()) + " | "
        + n(r.training().target().sum()) + " |");
    md.add("");
    md.add("- Dias entre o primeiro commit e o último código, já com o teto: "
        + n(r.training().implementation()) + ".");
    md.add("- Dias com atividade humana depois do último código: " + n(r.training().tail())
        + ".");
    md.add("- Dias que o teto tirou das pausas: " + n(r.training().capped()) + ".");
    md.add("- Issues com algum patch entre os marcos: " + n(r.training().withPatches()) + ".");
    md.add("");
    md.add("Regra: " + FirstCommit.RULE + ".");
    md.add("");
    md.add("## Sensibilidade do limite do primeiro commit");
    md.add("");
    md.add("Cada limite sorteia a própria partição e aprende o próprio teto no próprio ajuste "
        + "interno. O alvo é o do treino externo de cada sorteio.");
    md.add("");
    md.add("| Limite | População | Treino | Teste | Teto | Mín. | Q1 | Mediana | Q3 | P90 "
        + "| Máx. | Média |");
    md.add("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
    for (final IssuesFirstCommitAuditTask.Sensitivity s : r.sensitivity()) {
      md.add("| " + d(100 * s.limit()) + "% | " + n(s.sizes().rows()) + " | "
          + n(s.sizes().train()) + " | " + n(s.sizes().test()) + " | " + n(s.threshold().cap())
          + " | " + quantiles(s.target()) + " |");
    }
    md.add("");
    md.add("## Sobreposição com o estudo In Progress");
    md.add("");
    md.add("Linhas: o lado de cada issue nesta partição. Colunas: o lado dela na partição do "
        + "estudo In Progress, sorteada como a extração dele sorteia.");
    md.add("");
    final List<Side> sides = List.of(Side.values());
    md.add("| Aqui \\ In Progress | " + String.join(" | ",
        sides.stream().map(Side::label).toList()) + " | Total |");
    md.add("|---|" + "---:|".repeat(sides.size() + 1));
    for (final Map.Entry<Side, Map<Side, Integer>> e : r.overlap().entrySet()) {
      final StringBuilder b = new StringBuilder("| " + e.getKey().label() + " |");
      int total = 0;
      for (final Side side : sides) {
        b.append(' ').append(n(e.getValue().get(side))).append(" |");
        total += e.getValue().get(side);
      }
      md.add(b.append(' ').append(n(total)).append(" |").toString());
    }
    md.add("| " + Side.TEST.label() + " |" + " — |".repeat(sides.size()) + " "
        + n(r.sizes().test()) + " |");
    md.add("");
    md.add("Do teste externo, só o total.");
    if (r.temporal() != null) {
      censoring(md, r.temporal().censoring(), r.temporal().cuts().outer());
    }
    return md;
  }

  /** The temporal split's cuts, the target and projects of each side, and the crossings. */
  private static void temporalSides(final List<String> md, final Report r) {
    final IssuesFirstCommitAuditTask.Temporal t = r.temporal();
    md.add("- Corte externo: " + t.cuts().outer() + ". Corte interno: " + t.cuts().inner()
        + ".");
    md.add("");
    md.add("Alvo em dias úteis, com o teto aprendido no ajuste interno temporal. Do teste "
        + "externo, só o total e os projetos.");
    md.add("");
    md.add("| Lado | Issues | Projetos | Mediana | Q1 | Q3 | P90 |");
    md.add("|---|---:|---:|---:|---:|---:|---:|");
    md.add(side(Side.FIT.label(), t.fit(), t.projects().fit(), r.sizes().fit()));
    md.add(side(Side.VALIDATION.label(), t.validation(), t.projects().validation(),
        r.sizes().validation()));
    md.add(side("treino externo", r.training().target(), t.projects().train(), r.sizes().train()));
    md.add("| " + Side.TEST.label() + " | " + n(r.sizes().test()) + " | "
        + n(t.projects().test()) + " | — | — | — | — |");
    md.add("");
    md.add("- Projetos novos (com issues no teste externo e nenhuma no treino externo): "
        + newProjects(t.newProjects()));
    md.add("- Projetos com issues na validação interna e nenhuma no ajuste interno: "
        + newProjects(t.newInnerProjects()));
    md.add("- Issues de treino que cruzam o corte externo (primeiro commit até o corte, primeira "
        + "resolução depois dele): " + n(t.crossing()) + " de " + n(r.sizes().train()) + ".");
    md.add("- Issues do ajuste interno que cruzam o corte interno: " + n(t.innerCrossing())
        + " de " + n(r.sizes().fit()) + ".");
    md.add("");
  }

  private static String side(final String label, final FirstCommit.Summary s, final int projects,
      final int size) {
    if (s == null) {
      return "| " + label + " | " + n(size) + " | " + n(projects) + " | — | — | — | — |";
    }
    return "| " + label + " | " + n(s.n()) + " | " + n(projects) + " | " + d(s.median()) + " | "
        + d(s.q1()) + " | " + d(s.q3()) + " | " + d(s.p90()) + " |";
  }

  private static String newProjects(final Map<String, Integer> projects) {
    if (projects.isEmpty()) {
      return "nenhum.";
    }
    final int issues = projects.values().stream().mapToInt(Integer::intValue).sum();
    return n(projects.size()) + ", com " + n(issues) + " issues ("
        + projects.entrySet().stream().map(e -> e.getKey() + ": " + n(e.getValue()))
            .collect(Collectors.joining("; ")) + ").";
  }

  /** The censoring at the end of the data, in dates and counts alone. */
  private static void censoring(final List<String> md,
      final IssuesFirstCommitAuditTask.Censoring c, final Instant cut) {
    md.add("");
    md.add("## Censura no fim dos dados");
    md.add("");
    md.add("Só datas e contagens: nenhum alvo do teste externo aparece aqui.");
    md.add("");
    md.add("O fim dos dados de um projeto é o seu registro mais recente no Jira: a criação de "
        + "uma issue, um evento do histórico ou um comentário, todos com data do servidor. As "
        + "datas de commit vêm do relógio do autor e ficam de fora. O fim dos dados é o mais "
        + "tardio desses fins.");
    md.add("");
    final List<Instant> ends = c.projectEnds().values().stream()
        .sorted().toList();
    md.add("- Fim dos dados: " + c.end() + ".");
    md.add("- Fim dos dados por projeto, em " + n(ends.size()) + " projetos: o mais cedo "
        + ends.getFirst() + ", a mediana " + ends.get((ends.size() - 1) / 2) + ".");
    md.add("- Commit mais recente do corpus, só para comparação: " + c.lastCommit() + ".");
    md.add("");
    md.add("Issues do universo fora da base (a base são as issues com unidade e primeira "
        + "resolução Fixed) cujo primeiro commit nativo que conta (ligado à issue, humano, não "
        + "merge, antes da primeira resolução quando há uma) vem depois do corte externo ("
        + cut + "). Os originais de PR não são lidos para elas, e as duplicatas não são "
        + "tiradas.");
    md.add("");
    md.add("- Sem primeira resolução até o fim dos dados (censuradas): " + n(c.unresolved())
        + ".");
    md.add("- Com a primeira resolução por outro motivo que não Fixed: " + n(c.otherReason())
        + ".");
    md.add("- Resolvidas, Fixed ou não, mas sem unidade (sem reporter, ou resolvidas na "
        + "criação): "
        + n(c.unitless()) + ".");
    md.add("");
    md.add("Dias corridos do primeiro commit até o fim dos dados:");
    md.add("");
    md.add("| Issues | Até | n | Mín. | Q1 | Mediana | Q3 | P90 | Máx. |");
    md.add("|---|---|---:|---:|---:|---:|---:|---:|---:|");
    md.add(days("censuradas", "o fim dos dados", c.unresolvedDays()));
    md.add(days("censuradas", "o fim do projeto", c.unresolvedProjectDays()));
    md.add(days("teste externo", "o fim dos dados", c.testDays()));
    md.add(days("teste externo", "o fim do projeto", c.testProjectDays()));
  }

  private static String days(final String issues, final String to,
      final FirstCommit.Summary s) {
    if (s == null) {
      return "| " + issues + " | " + to + " | 0 | — | — | — | — | — | — |";
    }
    return "| " + issues + " | " + to + " | " + n(s.n()) + " | " + String.join(" | ",
        d(s.min()), d(s.q1()), d(s.median()), d(s.q3()), d(s.p90()), d(s.max())) + " |";
  }

  /** Minimum, Q1, median, Q3, P90, maximum and mean, as table cells. */
  private static String quantiles(final FirstCommit.Summary a) {
    return String.join(" | ", d(a.min()), d(a.q1()), d(a.median()), d(a.q3()), d(a.p90()),
        d(a.max()), d(a.mean()));
  }

  private static String cell(final FirstCommit.Count c,
      final java.util.function.ToIntFunction<FirstCommit.Count> part) {
    return c == null ? "" : Integer.toString(part.applyAsInt(c));
  }

  static List<String> perIssue(final Report r) {
    final List<String> lines = new ArrayList<>();
    lines.add(String.join("\t", "id", "issue", "project", "verdict", "commits_counted",
        "originals", "commits_kept", "dup_same_author_date", "dup_same_title_lines",
        "first_commit_share", "day_boundary_hour", "code_days", "side", "marks", "patches",
        "implementation_days", "tail_days", "capped_days", "target_days"));
    for (final IssueRow row : r.rows()) {
      final FirstCommit.Verdict v = row.verdict();
      final FirstCommit.Count c = row.count();
      lines.add(String.join("\t", row.id(), row.key(), row.project(),
          v.eligible() ? "population" : v.failed().name(), Integer.toString(row.counted()),
          Integer.toString(row.originals()), Integer.toString(v.kept().size()),
          Integer.toString(v.duplicates().sameAuthorDate()),
          Integer.toString(v.duplicates().sameTitleLines()),
          v.firstShare() == null ? "" : String.format(Locale.ROOT, "%.6f", v.firstShare()),
          v.hour() == null ? "" : v.hour().toString(),
          v.hour() == null ? "" : Integer.toString(v.codeDays()),
          row.side() == null ? "" : row.side().name(), cell(c, FirstCommit.Count::marks),
          cell(c, FirstCommit.Count::patches), cell(c, FirstCommit.Count::implementation),
          cell(c, FirstCommit.Count::tail), cell(c, FirstCommit.Count::capped),
          cell(c, FirstCommit.Count::total)));
    }
    return lines;
  }
}
