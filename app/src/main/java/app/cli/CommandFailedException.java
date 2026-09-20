package app.cli;

import java.util.List;

/**
 * A command that stopped on a condition the operator has to fix, carrying what to print.
 *
 * <p>The lines are pre-formatted rather than a single message because the diagnostics differ in
 * shape: a missing prerequisite is one line, a broken join is a paragraph plus a sample of the
 * violations. Printing and the exit code stay in {@code App}, so no runner decides how the process
 * ends.
 */
public final class CommandFailedException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient List<String> lines;

  public CommandFailedException(final List<String> lines) {
    super(String.join(System.lineSeparator(), lines));
    this.lines = List.copyOf(lines);
  }

  /** Exactly what to write to stderr, already indented. */
  public List<String> lines() {
    return lines;
  }
}
