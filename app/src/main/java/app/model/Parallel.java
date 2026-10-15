package app.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Independent pieces of work run on a bounded pool of threads, their results handed back in the
 * order the pieces were given, never in the order they finished.
 *
 * <p>Nothing here combines numbers: each piece computes its own result alone, on one thread, in
 * its own fixed order, and whatever the caller reduces it reads in the given order afterwards. So
 * a result does not depend on how many threads ran, nor on which one ran which piece; one thread
 * is the sequential path itself and stays a supported setting.
 *
 * <p>The number of threads is read from an environment variable, or the system property of the
 * same name, with a default of its own when neither is set. A value that is not a positive whole
 * number is refused rather than read as the default.
 */
public final class Parallel {

  /**
   * Threads for the Elastic Net fits: the sweep's points and the similarity models' refits. One
   * when unset: on a matrix of the real shape the fits measured no faster on more threads, since
   * each hands its whole matrix to the native library on every product and memory, not the
   * processors, is what they wait on.
   */
  public static final String ELASTIC_NET_THREADS = "ELASTIC_NET_THREADS";

  /** Threads for the draws of the project-resampled intervals; every processor when unset. */
  public static final String BOOTSTRAP_THREADS = "BOOTSTRAP_THREADS";

  /**
   * Threads for embedding the descriptions the embedding cache misses; every processor when
   * unset.
   */
  public static final String EMBEDDING_THREADS = "EMBEDDING_THREADS";

  private Parallel() {}

  /**
   * Refuses, before any stage starts, a thread count that is set and not a positive whole number:
   * the stages read theirs only when they reach the work, which may be after a run that can
   * happen once has begun.
   */
  public static void check() {
    for (final String name : List.of(ELASTIC_NET_THREADS, BOOTSTRAP_THREADS, EMBEDDING_THREADS)) {
      threads(name);
    }
  }

  /** The threads {@code name} asks for, or its default when it is unset. */
  public static int threads(final String name) {
    final String fromEnv = System.getenv(name);
    final String value = fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    return threads(name, value);
  }

  /**
   * The threads {@code value} of {@code name} asks for; null or blank is its default: one for
   * {@link #ELASTIC_NET_THREADS}, the processors the JVM sees for any other.
   */
  static int threads(final String name, final String value) {
    if (value == null || value.isBlank()) {
      return name.equals(ELASTIC_NET_THREADS) ? 1 : Runtime.getRuntime().availableProcessors();
    }
    final int n;
    try {
      n = Integer.parseInt(value.trim());
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(name + "=" + value + " is not a whole number of threads");
    }
    if (n < 1) {
      throw new IllegalArgumentException(name + "=" + value + ": at least one thread is needed");
    }
    return n;
  }

  /**
   * Each job's result, in the jobs' order. With one thread, or one job, the jobs run on the
   * calling thread one after the other and the first failure stops the rest, exactly as a plain
   * loop would. With more, they run on a pool of at most {@code threads} threads of their own,
   * all of them to the end, and the failure of the earliest failing job, in the jobs' order, is
   * the one thrown: a runtime exception or error as it was thrown, anything else wrapped.
   */
  public static <T> List<T> map(final int threads, final List<? extends Callable<T>> jobs) {
    if (threads < 1) {
      throw new IllegalArgumentException(threads + " threads");
    }
    final List<T> out = new ArrayList<>(jobs.size());
    if (threads == 1 || jobs.size() <= 1) {
      for (final Callable<T> job : jobs) {
        try {
          out.add(job.call());
        } catch (final RuntimeException e) {
          throw e;
        } catch (final Exception e) {
          throw new IllegalStateException("a parallel job failed", e);
        }
      }
      return out;
    }
    final ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, jobs.size()),
        r -> {
          final Thread t = new Thread(r, "parallel");
          t.setDaemon(true);
          return t;
        });
    try {
      for (final Future<T> f : pool.invokeAll(jobs)) {
        out.add(f.get());
      }
      return out;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for parallel jobs", e);
    } catch (final ExecutionException e) {
      if (e.getCause() instanceof RuntimeException r) {
        throw r;
      }
      if (e.getCause() instanceof Error r) {
        throw r;
      }
      throw new IllegalStateException("a parallel job failed", e.getCause());
    } finally {
      pool.shutdownNow();
    }
  }
}
