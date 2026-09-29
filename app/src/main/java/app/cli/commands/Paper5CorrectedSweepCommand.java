package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper5.Artigo4Features;
import app.paper5.Family;
import app.paper5.RowCache;
import app.pipeline.Paper5CorrectedSweepTask;
import app.pipeline.Paper5ReservedTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Sweep of the correction of 28/09/2026, on the inner selection only: first proves the sweep
 * reproduces the registered choice with the registered columns ({@code A4}), then sweeps with the
 * corrected ones ({@code A4_CORRIGIDO}) and prints the pair to freeze. Reads files; writes nothing.
 */
public final class Paper5CorrectedSweepCommand implements CommandRunner {

  private static final List<Family> AFFECTED =
      List.of(Family.COMBINADO_MAIS_ARTIGO4, Family.CONTRIBUICAO_PROPRIA);

  @Override
  public void run(final Context context) throws CommandFailedException {
    final List<RowCache.Entry> entries;
    final Artigo4Features registered;
    final Artigo4Features corrected;
    try {
      entries = RowCache.read(Path.of(System.getenv("CACHE")));
      registered = Artigo4Features.read(Path.of(System.getenv("A4")));
      corrected = Artigo4Features.read(Path.of(System.getenv("A4_CORRIGIDO")));
    } catch (final IOException | NullPointerException e) {
      throw new CommandFailedException(List.of("  CACHE, A4 e A4_CORRIGIDO: " + e));
    }
    final Paper5CorrectedSweepTask task = new Paper5CorrectedSweepTask();
    System.out.printf("%n  VARREDURA DA CORRECAO -- selecao interna (ajuste 4.252, escolha 1.865)%n");
    System.out.printf("%n  PORTAO: com as colunas registradas, a varredura reencontra o registro?%n");
    boolean passed = true;
    for (final Family family : AFFECTED) {
      final Paper5CorrectedSweepTask.Chosen c = task.sweep(entries, registered, family);
      final Paper5ReservedTask.Frozen f = Paper5ReservedTask.FROZEN.stream()
          .filter(x -> x.family() == family).findFirst().orElseThrow();
      final boolean same = Math.abs(c.alpha() - f.alpha()) < 1e-9
          && String.format("%.4g", c.lambda()).equals(String.format("%.4g", f.lambda()));
      passed &= same;
      System.out.printf("  %-24s escolheu alpha=%.2f lambda=%.4g | registro alpha=%.2f lambda=%.4g"
          + "  %s%n", family, c.alpha(), c.lambda(), f.alpha(), f.lambda(),
          same ? "IGUAL" : "DIFERENTE");
    }
    if (!passed) {
      throw new CommandFailedException(List.of("  a varredura nao reproduz o registro; parei."));
    }
    System.out.printf("%n  COM AS COLUNAS CORRIGIDAS%n");
    for (final Family family : AFFECTED) {
      final Paper5CorrectedSweepTask.Chosen c = task.sweep(entries, corrected, family);
      System.out.printf("  %-24s alpha=%.2f lambda=%s  degrau %d de %d%s%n", family, c.alpha(),
          Double.toString(c.lambda()), c.step(), 24, c.atEdge() ? "  NA BORDA DA GRADE" : "");
      System.out.printf("      selecao interna: MAE=%.4f  Spearman=%.4f  par a par=%.4f  vsCte=%.2f%%%n",
          c.inner().maeHours(), c.inner().spearman(), c.inner().pairwise(),
          c.inner().gainOverConstantPercent());
    }
    System.out.printf("%n  nada foi escrito; o reservado nao foi lido.%n");
  }
}
