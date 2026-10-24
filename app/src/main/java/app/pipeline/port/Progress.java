package app.pipeline.port;

/**
 * Where a long stage says what it is doing, so the adapter doing the reading does not also decide
 * how a terminal renders it.
 *
 * <p>Deliberately not a logging framework. SLF4J and log4j2 are already on the classpath by way of
 * Spark, and neither would help: nothing written here is ever filtered, routed or switched off by
 * level. This is the running commentary of a batch job somebody is watching, and the only decisions
 * worth making about it -- indentation, column widths, whether to print at all -- belong to the
 * layer that owns the terminal, not to a class whose job is reading GridFS.
 *
 * <p>Every method takes a format string with its arguments rather than an assembled line, so a run
 * that prints nothing also formats nothing.
 */
public interface Progress {

  /** A numbered phase of a stage: {@code [2/3] joining on commit.revision_hash}. */
  void phase(int step, int of, String format, Object... args);

  /** A single fact under the current phase, with no counter of its own. */
  void detail(String format, Object... args);

  /** One named unit of work out of a known total -- a repository, a bundled tarball. */
  void item(int index, int of, String name, String format, Object... args);

  /** Progress through a countable population whose members have no name worth printing. */
  void count(long done, long total, String format, Object... args);
}
