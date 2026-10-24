package app.cli.commands;

import app.pipeline.CanonicalizeTask;

/**
 * Prints a canonicalisation report.
 *
 * <p>Shared, because two commands end in one: {@code reconstruct-people} and {@code harvest-jira}
 * canonicalise after writing their half of the registry. Reporting the merge the same way in both
 * is what makes two runs comparable by eye.
 */
final class CanonicalPrinter {

  static void print(final CanonicalizeTask.Report report, final String wroteTo) {
    if (report.identities() == 0) {
      System.out.println();
      System.out.println("  registry is empty -- nothing to canonicalize.");
      return;
    }
    System.out.printf("%n  identities       : %,d%n", report.identities());
    System.out.printf("  canonical people : %,d  (%,d merged, %.1f%%)%n",
        report.canonicalPeople(), report.merged(), 100 * report.mergedShare());
    System.out.printf("  flagged as bots  : %,d%n", report.bots());
    System.out.printf("  bridges refused  : %,d  (ambiguous handle)%n", report.bridgesRefused());
    System.out.printf("  wrote %,d rows to %s%n", report.written(), wroteTo);
  }

  private CanonicalPrinter() {}
}
