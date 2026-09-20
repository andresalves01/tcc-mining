package app.cli;

import app.pipeline.port.Progress;

/**
 * Prints progress to standard output, indented under the report the command prints around it.
 *
 * <p>The one place that decides what a progress line looks like. Adapters say what happened; the
 * indentation, the column widths and the bracket style are settled here, so changing any of them is
 * a one-file change rather than a sweep through four adapters.
 */
public final class ConsoleProgress implements Progress {

  /** Phases sit one level under the command's heading, their details one level under that. */
  private static final String PHASE_INDENT = "  ";
  private static final String DETAIL_INDENT = "        ";

  /** Repository and tarball names run to about thirty characters; padding keeps counts aligned. */
  private static final String NAME_COLUMN = "%-32s";

  @Override
  public void phase(final int step, final int of, final String format, final Object... args) {
    System.out.printf("%s[%d/%d] %s%n", PHASE_INDENT, step, of, render(format, args));
  }

  @Override
  public void detail(final String format, final Object... args) {
    System.out.println(DETAIL_INDENT + render(format, args));
  }

  @Override
  public void item(final int index, final int of, final String name, final String format,
      final Object... args) {
    System.out.printf("%s[%d/%d] " + NAME_COLUMN + " %s%n",
        DETAIL_INDENT, index, of, name, render(format, args));
  }

  @Override
  public void count(final long done, final long total, final String format, final Object... args) {
    System.out.printf("%s%,d of %,d %s%n", DETAIL_INDENT, done, total, render(format, args));
  }

  /** A message with no arguments is text rather than a format, so a literal {@code %} survives. */
  private static String render(final String format, final Object... args) {
    return args.length == 0 ? format : format.formatted(args);
  }
}
