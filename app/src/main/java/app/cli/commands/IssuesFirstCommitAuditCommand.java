package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.FirstCommit;
import app.issues.ProjectSplit;
import app.pipeline.IssuesFirstCommitAuditTask;
import app.pipeline.IssuesFirstCommitAuditTask.IssueRow;
import app.pipeline.IssuesFirstCommitAuditTask.Report;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The first-commit population and its target on the training side, as files in AUDIT_DIR: the
 * summary {@code audit.md} and {@code per-issue.tsv}. Reads the corpus; writes to no database.
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
    final Path root = Path.of(dir);
    Files.createDirectories(root);
    final Report r = new IssuesFirstCommitAuditTask(context.study(), context.progress()).run();
    write(root, r);
    System.out.printf("%n  First-commit audit: %,d issues drawn (training %,d, test %,d); cap %d "
        + "business days%n  written to %s%n", r.sizes().rows(), r.sizes().train(),
        r.sizes().test(), r.threshold().cap(), root.toAbsolutePath());
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
    md.add(ProjectSplit.RULE + ".");
    md.add("");
    final IssuesFirstCommitAuditTask.Sizes z = r.sizes();
    md.add("| Sorteadas | Projetos | Treino externo | Teste externo | Ajuste interno "
        + "| Validação interna |");
    md.add("|---:|---:|---:|---:|---:|---:|");
    md.add("| " + n(z.rows()) + " | " + n(z.projects()) + " | " + n(z.train()) + " | "
        + n(z.test()) + " | " + n(z.fit()) + " | " + n(z.validation()) + " |");
    md.add("");
    md.add("## Teto das pausas, aprendido no ajuste interno");
    md.add("");
    final FirstCommit.Threshold t = r.threshold();
    md.add("| Q1 | Mediana | Q3 | Cerca (Q3 + 1,5 IQR) | Teto | Intervalos |");
    md.add("|---:|---:|---:|---:|---:|---:|");
    md.add("| " + d(t.q1()) + " | " + d(t.median()) + " | " + d(t.q3()) + " | " + d(t.fence())
        + " | " + n(t.cap()) + " | " + n(t.gaps()) + " |");
    md.add("");
    md.add("## Alvo no treino externo, em dias úteis");
    md.add("");
    md.add("| Issues | Mín. | Q1 | Mediana | Q3 | P90 | Máx. | Média | Soma |");
    md.add("|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
    md.add(row(r.training().target()));
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
      final FirstCommit.Summary a = s.target();
      md.add("| " + d(100 * s.limit()) + "% | " + n(s.sizes().rows()) + " | "
          + n(s.sizes().train()) + " | " + n(s.sizes().test()) + " | " + n(s.threshold().cap())
          + " | " + d(a.min()) + " | " + d(a.q1()) + " | " + d(a.median()) + " | " + d(a.q3())
          + " | " + d(a.p90()) + " | " + d(a.max()) + " | " + d(a.mean()) + " |");
    }
    md.add("");
    md.add("## Sobreposição com o estudo In Progress");
    md.add("");
    md.add("Linhas: o lado de cada issue nesta partição. Colunas: o lado dela na partição do "
        + "estudo In Progress, sorteada como a extração dele sorteia.");
    md.add("");
    final List<String> sides = List.copyOf(r.overlap().values().iterator().next().keySet());
    md.add("| Aqui \\ In Progress | " + String.join(" | ", sides) + " | Total |");
    md.add("|---|" + "---:|".repeat(sides.size() + 1));
    for (final Map.Entry<String, Map<String, Integer>> e : r.overlap().entrySet()) {
      final StringBuilder b = new StringBuilder("| " + e.getKey() + " |");
      int total = 0;
      for (final String side : sides) {
        b.append(' ').append(n(e.getValue().get(side))).append(" |");
        total += e.getValue().get(side);
      }
      md.add(b.append(' ').append(n(total)).append(" |").toString());
    }
    return md;
  }

  private static String row(final FirstCommit.Summary a) {
    return "| " + n(a.n()) + " | " + d(a.min()) + " | " + d(a.q1()) + " | " + d(a.median())
        + " | " + d(a.q3()) + " | " + d(a.p90()) + " | " + d(a.max()) + " | " + d(a.mean())
        + " | " + n(a.sum()) + " |";
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
          v.hour() == null ? "" : Integer.toString(v.codeDays()), row.side(),
          c == null ? "" : Integer.toString(c.marks()),
          c == null ? "" : Integer.toString(c.patches()),
          c == null ? "" : Integer.toString(c.implementation()),
          c == null ? "" : Integer.toString(c.tail()),
          c == null ? "" : Integer.toString(c.capped()),
          c == null ? "" : Integer.toString(c.total())));
    }
    return lines;
  }
}
