package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.DescriptionCleaner;
import app.issues.LanguageReport;
import app.pipeline.IssuesDescriptionLanguageTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The language report on the in-progress population's descriptions at their first In Progress.
 * Reads the corpus, writes three files to REPORT_DIR and nothing else -- no Mongo record, so it may
 * be run at any time.
 */
public final class IssuesDescriptionLanguageCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String fromEnv = System.getenv("REPORT_DIR");
    final String dir = fromEnv == null || fromEnv.isBlank() ? System.getProperty("REPORT_DIR")
        : fromEnv;
    if (dir == null || dir.isBlank()) {
      throw new CommandFailedException(List.of("  REPORT_DIR=<output directory> is required"));
    }
    final LanguageReport r =
        new IssuesDescriptionLanguageTask(context.study(), context.progress()).run();

    System.out.printf("%n  LANGUAGE OF THE DESCRIPTIONS AT THE FIRST IN PROGRESS%n");
    System.out.printf("    %,d issues  no description %,d  only code or log blocks %,d%n",
        r.descriptions(), r.absent(), r.onlyCode());
    r.languages().forEach((k, v) -> System.out.printf("    %-20s %,8d%n", k, v));

    final Path out = Path.of(dir);
    try {
      Files.createDirectories(out);
      Files.write(out.resolve("summary.tsv"), summary(r));
      final List<String> languages = new ArrayList<>(List.of("language\tdescriptions"));
      r.languages().forEach((k, v) -> languages.add(k + "\t" + v));
      Files.write(out.resolve("languages.tsv"), languages);
      // The cleaned text holds no tab or line break: the cleaner collapses every space to one.
      final List<String> samples = new ArrayList<>(List.of("id\tkey\tproject\tlanguage\tcleaned"));
      r.samples().forEach(s -> samples.add(String.join("\t", s.id(), s.key(), s.project(),
          s.language(), s.cleaned())));
      Files.write(out.resolve("non-english-samples.tsv"), samples);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + dir + ": " + e));
    }
    System.out.printf("%n  written to %s: summary.tsv, languages.tsv, non-english-samples.tsv"
        + " (%d samples)%n", dir, r.samples().size());
  }

  private static List<String> summary(final LanguageReport r) {
    return List.of("field\tvalue",
        "descriptions\t" + r.descriptions(),
        "no_description\t" + r.absent(),
        "only_code_or_log_blocks\t" + r.onlyCode(),
        "detected\t" + (r.descriptions() - r.absent() - r.onlyCode()),
        "cleaner_version\t" + DescriptionCleaner.VERSION,
        "detector\tlingua " + IssuesDescriptionLanguageTask.LINGUA + ", all languages",
        "sample_seed\t" + IssuesDescriptionLanguageTask.SEED,
        "samples\t" + r.samples().size());
  }
}
