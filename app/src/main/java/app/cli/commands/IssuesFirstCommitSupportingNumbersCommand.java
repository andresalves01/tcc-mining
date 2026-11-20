package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.FirstCommit;
import app.issues.SplitRule;
import app.issues.SupportingNumbers;
import app.issues.Universe.Population;
import app.pipeline.IssuesFirstCommitSupportingNumbersTask;
import app.pipeline.IssuesFirstCommitSupportingNumbersTask.Example;
import app.pipeline.IssuesFirstCommitSupportingNumbersTask.Report;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The numbers that support the text of the first-commit study, as files in SUPPORTING_DIR:
 * {@code filter-counts.md}, {@code worked-example.md} and {@code worked-example.tsv}. The split
 * rule and the wait fence's k are read as the audit reads them. Reads the corpus; writes to no
 * database and records no run.
 */
public final class IssuesFirstCommitSupportingNumbersCommand implements CommandRunner {

  private static final Locale PT = Locale.forLanguageTag("pt-BR");

  private final Function<String, String> settings;

  public IssuesFirstCommitSupportingNumbersCommand() {
    this(n -> {
      final String s = System.getenv(n);
      return s == null ? System.getProperty(n) : s;
    });
  }

  public IssuesFirstCommitSupportingNumbersCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  @Override
  public void run(final Context context) throws Exception {
    final String dir = settings.apply("SUPPORTING_DIR");
    if (dir == null || dir.isBlank()) {
      throw new CommandFailedException(List.of("  SUPPORTING_DIR=<output directory> is required"));
    }
    final SplitRule rule;
    try {
      rule = SplitRule.declared(settings.apply(SplitRule.SETTING), Population.FIRST_COMMIT);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }
    final double waitK = IssuesInProgressExtractCommand.waitFenceK(settings);
    final Path root = Path.of(dir);
    Files.createDirectories(root);
    final Report r = new IssuesFirstCommitSupportingNumbersTask(context.study(),
        context.progress(), rule, waitK).run();
    write(root, r);
    System.out.printf("%n  First-commit supporting numbers: %,d issues with a commit, %,d on "
        + "the outer training, %d worked examples%n", r.counts().withCode(), r.training(),
        r.examples().size());
    System.out.printf("  written to %s%n", root.toAbsolutePath());
  }

  static void write(final Path root, final Report r) throws IOException {
    Files.write(root.resolve("filter-counts.md"), counts(r));
    Files.write(root.resolve("worked-example.md"), prose(r));
    Files.write(root.resolve("worked-example.tsv"), table(r));
  }

  private static String n(final long v) {
    return String.format(PT, "%,d", v);
  }

  private static String pct(final long part, final long whole) {
    return whole == 0 ? "—" : String.format(PT, "%.2f%%", 100.0 * part / whole);
  }

  static List<String> counts(final Report r) {
    final SupportingNumbers.Counts c = r.counts();
    final List<String> md = new ArrayList<>();
    md.add("# Estudo do primeiro commit: contagens de apoio ao filtro dos 50%");
    md.add("");
    md.add("Só leitura: nada foi gravado em banco. Cada contagem é própria, fora do funil: uma "
        + "issue pode estar em mais de uma. Os denominadores estão em cada linha.");
    md.add("");
    md.add("Base: as issues resolvidas como Fixed na primeira resolução, na base da auditoria ("
        + n(c.base()) + "). Denominador comum: as que têm algum commit que conta ("
        + n(c.withCode()) + ").");
    md.add("");
    md.add("| Contagem | Issues | Denominador | Parte |");
    md.add("|---|---:|---:|---:|");
    md.add("| Fixed na primeira resolução, com algum commit que conta | " + n(c.withCode())
        + " | " + n(c.base()) + " | " + pct(c.withCode(), c.base()) + " |");
    md.add("| Criada depois do primeiro commit (commit antes da criação) | "
        + n(c.createdAfterFirstCommit()) + " | " + n(c.withCode()) + " | "
        + pct(c.createdAfterFirstCommit(), c.withCode()) + " |");
    md.add("| Criada no mesmo instante do primeiro commit | " + n(c.createdAtFirstCommit())
        + " | " + n(c.withCode()) + " | " + pct(c.createdAtFirstCommit(), c.withCode()) + " |");
    md.add("| Primeiro commit antes do In Progress | " + n(c.firstBeforeInProgress()) + " | "
        + n(c.withInProgress()) + " | " + pct(c.firstBeforeInProgress(), c.withInProgress())
        + " |");
    md.add("| Um único commit | " + n(c.single()) + " | " + n(c.withCode()) + " | "
        + pct(c.single(), c.withCode()) + " |");
    md.add("");
    md.add("- O primeiro commit é o mais antigo que conta, antes de remover as duplicatas, como "
        + "a regra do filtro o lê. As duas linhas de criação são disjuntas: \"depois\" é o primeiro "
        + "commit estritamente antes da criação; \"no mesmo instante\" é a igualdade. A regra "
        + "do funil, criada no instante do primeiro commit ou depois, é a soma das duas, "
        + "contada aqui sobre todas as issues com commit.");
    md.add("- \"Antes do In Progress\" é contado só entre as issues que tiveram In Progress ("
        + n(c.withInProgress()) + " de " + n(c.withCode()) + "), com o primeiro In Progress "
        + "antes da primeira resolução como início; a criação nunca entra no lugar dele. A "
        + "comparação é estrita: um commit no mesmo instante não é antes. Issue sem relator "
        + "nunca recebe início In Progress no universo, e conta como sem In Progress.");
    md.add("- \"Um único commit\" é contado direto, antes da remoção das duplicatas: duas cópias "
        + "do mesmo commit são dois commits aqui. A linha do funil \"menos de 2 commits depois "
        + "das duplicatas\" mistura esse caso com o das issues que as duplicatas reduziram.");
    md.add("");
    return md;
  }

  private static String date(final Example e, final long at) {
    return FirstCommit.dateOf(at, e.verdict().hour()).toString();
  }

  static List<String> prose(final Report r) {
    final List<String> md = new ArrayList<>();
    md.add("# Estudo do primeiro commit: exemplo trabalhado");
    md.add("");
    md.add("Só leitura: nada foi gravado em banco. As issues vêm do treino externo ("
        + n(r.training()) + " issues); nenhuma do teste externo é explicada aqui.");
    md.add("");
    md.add("- Divisão: " + r.rule().rule() + ".");
    md.add("- Cerca das esperas: " + FirstCommit.fenceTextPt(r.waitFenceK()) + ".");
    md.add(r.threshold().uncapped()
        ? "- Teto das esperas: nenhum, nenhuma espera é cortada (o ajuste interno tem "
            + n(r.threshold().gaps()) + " esperas)."
        : "- Teto das esperas, aprendido no ajuste interno: " + n(r.threshold().cap())
            + " dias úteis (" + n(r.threshold().gaps()) + " esperas).");
    md.add("- Escolha: a maior diferença entre os dias úteis de calendário e os do estudo "
        + "(calendar_minus_study), o maior número de dias úteis que o teto tirou das esperas "
        + "(capped) e a maior cauda em dias absolutos (tail). Empates pelo menor id; se um "
        + "critério cai numa issue já escolhida, entra a próxima distinta e a nota diz isso.");
    md.add("- A maior diferença tende a ser uma issue anômala: o exemplo mostra como o alvo "
        + "é feito, não o caso típico.");
    md.add("");
    for (final Example e : r.examples()) {
      final SupportingNumbers.Pick p = e.pick();
      final FirstCommit.Explanation x = p.entry().explanation();
      final FirstCommit.Count c = x.count();
      md.add("## " + p.entry().key() + " (" + p.criterion() + ")");
      md.add("");
      md.add("- Critério " + p.criterion() + ": " + n(p.value()) + " dias úteis."
          + (p.note().isEmpty() ? "" : " Nota: " + p.note() + "."));
      md.add("- Id " + p.entry().id() + ", projeto " + e.issue().project() + ".");
      md.add("- Criada em " + Instant.ofEpochMilli(e.issue().created()) + "; primeira resolução "
          + "em " + Instant.ofEpochMilli(e.issue().resolved()) + ".");
      md.add("- Dias úteis do calendário, do primeiro commit à resolução: " + n(x.calendar())
          + "; dias úteis do estudo: " + n(c.total()) + " (implementação " + n(c.implementation())
          + ", cauda " + n(c.tail()) + "); o teto tirou " + n(c.capped()) + ".");
      md.add("");
      md.add("Marcas (" + n(x.marks().size()) + ", " + n(c.patches()) + " delas patches):");
      md.add("");
      md.add("| # | Instante | Data | Tipo |");
      md.add("|---:|---|---|---|");
      for (int i = 0; i < x.marks().size(); i++) {
        final long at = x.marks().get(i);
        md.add("| " + (i + 1) + " | " + Instant.ofEpochMilli(at) + " | " + date(e, at) + " | "
            + (x.kinds().get(i) == FirstCommit.Kind.COMMIT ? "commit" : "patch") + " |");
      }
      md.add("");
      md.add("Esperas entre marcas consecutivas:");
      md.add("");
      md.add("| De | Até | Dias úteis | Com atividade | Contados | Cortados |");
      md.add("|---|---|---:|---:|---:|---:|");
      for (final FirstCommit.Wait w : x.waits()) {
        md.add("| " + date(e, w.from()) + " | " + date(e, w.to()) + " | " + n(w.businessDays())
            + " | " + n(w.active()) + " | " + n(w.counted()) + " | " + n(w.cut()) + " |");
      }
      md.add("");
      md.add("Cauda (datas com atividade humana depois da última marca, até a resolução): "
          + (x.tail().isEmpty() ? "nenhuma" : x.tail().stream().map(Object::toString)
              .collect(Collectors.joining(", "))) + ".");
      md.add("");
    }
    return md;
  }

  static List<String> table(final Report r) {
    final List<String> out = new ArrayList<>();
    out.add(String.join("\t", "criterion", "value", "note", "id", "key", "project", "created",
        "first_commit", "resolved", "marks", "patches", "calendar_business_days",
        "study_business_days", "implementation", "tail_days", "capped", "waits", "tail_dates"));
    for (final Example e : r.examples()) {
      final SupportingNumbers.Pick p = e.pick();
      final FirstCommit.Explanation x = p.entry().explanation();
      final FirstCommit.Count c = x.count();
      final String waits = x.waits().stream().map(w -> date(e, w.from()) + ">" + date(e, w.to())
          + ":" + w.businessDays() + "/" + w.counted() + "/" + w.cut())
          .collect(Collectors.joining(";"));
      out.add(String.join("\t", p.criterion(), Long.toString(p.value()), p.note(),
          p.entry().id(), p.entry().key(), e.issue().project(),
          Instant.ofEpochMilli(e.issue().created()).toString(),
          Instant.ofEpochMilli(x.marks().getFirst()).toString(),
          Instant.ofEpochMilli(e.issue().resolved()).toString(), Integer.toString(c.marks()),
          Integer.toString(c.patches()), Integer.toString(x.calendar()),
          Integer.toString(c.total()), Integer.toString(c.implementation()),
          Integer.toString(c.tail()), Integer.toString(c.capped()), waits,
          x.tail().stream().map(Object::toString).collect(Collectors.joining(";"))));
    }
    return out;
  }
}
