package app.pipeline.port;

/** A progress sink that prints nothing, for tests that want only the result. */
public final class SilentProgress implements Progress {

  public static final Progress INSTANCE = new SilentProgress();

  private SilentProgress() {}

  @Override
  public void phase(final int step, final int of, final String format, final Object... args) {}

  @Override
  public void detail(final String format, final Object... args) {}

  @Override
  public void item(final int index, final int of, final String name, final String format,
      final Object... args) {}

  @Override
  public void count(final long done, final long total, final String format,
      final Object... args) {}
}
