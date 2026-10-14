package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.labels.IssueLabel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Temporal audit of 28/09/2026 for the two constructs of the previous paper that read other pull
 * requests' outcomes: the project's typical wait and the author's file familiarity. Read-only; it
 * writes the per-unit counts to {@code SAIDA} and prints totals.
 */
public final class PullRequestTemporalAuditCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String out = System.getenv("SAIDA");
    if (out == null || out.isBlank()) {
      throw new CommandFailedException(List.of("  SAIDA=<caminho> e obrigatorio"));
    }
    final List<String> ids = context.units().readAll().stream().map(IssueLabel::issueId).toList();
    final Map<String, long[]> audit = context.pullRequestExtras().temporalAudit(ids);

    final StringBuilder tsv = new StringBuilder("id\tespera_usados\tespera_exame_depois_t0\t"
        + "espera_merge_depois_t0\tespera_muda\tfamiliaridade_usados\t"
        + "familiaridade_merge_depois_t0\tfamiliaridade_muda\n");
    final long[] rowsWith = new long[7];
    final long[] totals = new long[7];
    for (final String id : ids) {
      final long[] a = audit.get(id);
      if (a == null) {
        tsv.append(id).append("\tSEM_LINHA\n");
        continue;
      }
      tsv.append(id);
      for (int k = 0; k < a.length; k++) {
        tsv.append('\t').append(a[k]);
        totals[k] += a[k];
        if (a[k] > 0) {
          rowsWith[k]++;
        }
      }
      tsv.append('\n');
    }
    try {
      Files.writeString(Path.of(out), tsv.toString());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui gravar " + out + ": " + e));
    }
    System.out.printf("%n  AUDITORIA TEMPORAL -- espera tipica do projeto e familiaridade%n");
    System.out.printf("  unidades: %,d; com linha na varredura: %,d%n", ids.size(), audit.size());
    final String[] names = {"espera: PRs usados", "espera: examinados em t0 ou depois",
        "espera: mesclados em t0 ou depois", "espera: valor muda com so o conhecido",
        "familiaridade: PRs anteriores usados", "familiaridade: mesclados em t0 ou depois",
        "familiaridade: valor muda com so o conhecido"};
    for (int k = 0; k < names.length; k++) {
      System.out.printf("  %-46s linhas com >0: %,6d   total: %,9d%n", names[k], rowsWith[k],
          totals[k]);
    }
    System.out.printf("  por linha em %s. Nada foi escrito em banco.%n", out);
  }
}
