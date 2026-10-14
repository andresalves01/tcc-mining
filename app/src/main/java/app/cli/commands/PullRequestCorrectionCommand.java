package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pullrequests.PriorStudyCorrection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The correction of 28/09/2026 of the previous paper's columns, as registered in
 * {@code REGISTRO-DA-CORRECAO.md}: recomputes every affected column under the registered rule and
 * under the corrected one, requires the registered rule to reproduce the stored export {@code A4},
 * and only then writes the corrected export to {@code SAIDA}, in the same format.
 */
public final class PullRequestCorrectionCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String a4 = System.getenv("A4");
    final String out = System.getenv("SAIDA");
    if (a4 == null) {
      throw new CommandFailedException(List.of("  A4=<export gravado> e obrigatorio"));
    }
    final List<String> lines;
    try {
      lines = Files.readAllLines(Path.of(a4), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler " + a4 + ": " + e));
    }
    final String[] header = lines.get(0).split("\t", -1);
    final Map<String, Integer> at = new HashMap<>();
    for (int i = 0; i < header.length; i++) {
      at.put(header[i], i);
    }
    final Map<String, String[]> stored = new HashMap<>();
    for (final String line : lines.subList(1, lines.size())) {
      if (!line.isBlank()) {
        final String[] f = line.split("\t", -1);
        stored.put(f[0], f);
      }
    }

    final PriorStudyCorrection.Inputs in = context.priorStudyTables().load();
    System.out.printf("%n  CORRECAO DAS COLUNAS DO ESTUDO ANTERIOR -- registro: REGISTRO-DA-CORRECAO.md%n");
    System.out.printf("  pull requests: %,d; commits no livro: %,d (visiveis pelo merge do PR: %,d; "
        + "pela data no repositorio: %,d; so pela autoria: %,d); unidades: %,d%n",
        in.pullRequests().size(), in.ledger().size(), in.ledgerInMergedPr(),
        in.ledgerByCommitterDate(), in.ledgerByAuthoring(), in.units().size());
    final PriorStudyCorrection.Result result = new PriorStudyCorrection(in.pullRequests(), in.ledger(),
        in.units(), in.unitInterval()).compute(in.units());

    System.out.printf("%n  PORTAO: a regra registrada, recalculada, contra o export gravado%n");
    System.out.printf("  %-24s %10s %10s   primeiro exemplo%n", "coluna", "comparadas", "diferem");
    boolean passed = true;
    for (final String column : PriorStudyCorrection.COLUMNS) {
      int compared = 0;
      int differ = 0;
      String example = "";
      for (final Map.Entry<String, Map<String, Double>> e : result.registered().entrySet()) {
        final String[] f = stored.get(e.getKey());
        if (f == null) {
          continue;
        }
        compared++;
        final Double mine = e.getValue().get(column);
        final String raw = f[at.get(column)].trim();
        final Double theirs = raw.isEmpty() ? null : Double.valueOf(raw);
        if (!same(mine, theirs)) {
          differ++;
          if (example.isEmpty()) {
            example = e.getKey() + " recalculado=" + mine + " gravado=" + theirs;
          }
        }
      }
      passed &= differ == 0;
      System.out.printf("  %-24s %,10d %,10d   %s%n", column, compared, differ, example);
    }

    System.out.printf("%n  QUANTO A CORRECAO MUDA: linhas em que corrigido != registrado%n");
    for (final String column : PriorStudyCorrection.COLUMNS) {
      int changed = 0;
      for (final Map.Entry<String, Map<String, Double>> e : result.corrected().entrySet()) {
        if (!same(e.getValue().get(column), result.registered().get(e.getKey()).get(column))) {
          changed++;
        }
      }
      System.out.printf("  %-24s %,10d de %,d%n", column, changed, result.corrected().size());
    }

    if (!passed) {
      throw new CommandFailedException(List.of("  o portao falhou: a regra registrada nao "
          + "reproduz o export gravado. A correcao nao foi gravada."));
    }
    if (out == null) {
      System.out.printf("%n  portao aprovado. Defina SAIDA=<caminho> para gravar o export corrigido.%n");
      return;
    }
    final StringBuilder tsv = new StringBuilder(lines.get(0)).append('\n');
    int written = 0;
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final String[] f = line.split("\t", -1);
      final Map<String, Double> corrected = result.corrected().get(f[0]);
      if (corrected != null) {
        for (final String column : PriorStudyCorrection.COLUMNS) {
          final Double v = corrected.get(column);
          f[at.get(column)] = v == null ? "" : format(v);
        }
        written++;
      }
      tsv.append(String.join("\t", f)).append('\n');
    }
    try {
      Files.writeString(Path.of(out), tsv.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui gravar " + out + ": " + e));
    }
    System.out.printf("%n  portao aprovado; export corrigido gravado em %s (%,d linhas)%n", out,
        written);
  }

  private static boolean same(final Double a, final Double b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return Math.abs(a - b) <= 1e-6 * Math.max(1.0, Math.abs(b));
  }

  private static String format(final double v) {
    return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : Double.toString(v);
  }
}
