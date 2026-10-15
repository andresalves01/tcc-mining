package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Independent jobs on a pool: results in the given order, failures in the given order. */
class ParallelTest {

  @Test
  @DisplayName("results come back in the jobs' order even when the later jobs finish first")
  void order() {
    final int n = 8;
    final CountDownLatch later = new CountDownLatch(n - 1);
    final List<Callable<Integer>> jobs = new ArrayList<>();
    for (int k = 0; k < n; k++) {
      final int id = k;
      jobs.add(() -> {
        if (id == 0) {
          // the first job waits for every other to finish before it returns
          later.await(10, TimeUnit.SECONDS);
        } else {
          later.countDown();
        }
        return id;
      });
    }
    assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), Parallel.map(n, jobs));
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), Parallel.map(1, jobs.subList(1, n)));
  }

  @Test
  @DisplayName("the earliest failing job's exception is the one thrown, as it was thrown, though "
      + "a later one failed first; one thread stops at the first failure like a loop")
  void failures() {
    final IllegalStateException early = new IllegalStateException("early");
    final CountDownLatch lateFailed = new CountDownLatch(1);
    final List<Callable<Integer>> jobs = List.of(
        () -> 0,
        () -> {
          lateFailed.await(10, TimeUnit.SECONDS);
          throw early;
        },
        () -> {
          lateFailed.countDown();
          throw new IllegalArgumentException("late");
        });
    assertSame(early, assertThrows(IllegalStateException.class, () -> Parallel.map(3, jobs)));

    final AtomicInteger ran = new AtomicInteger();
    final List<Callable<Integer>> loop = List.of(
        () -> ran.incrementAndGet(),
        () -> {
          ran.incrementAndGet();
          throw early;
        },
        () -> ran.incrementAndGet());
    assertSame(early, assertThrows(IllegalStateException.class, () -> Parallel.map(1, loop)));
    assertEquals(2, ran.get(), "the job after the failure never ran");

    final IOException checked = new IOException("checked");
    final List<Callable<Integer>> wrapped = List.of(() -> 1, () -> {
      throw checked;
    });
    assertSame(checked, assertThrows(IllegalStateException.class,
        () -> Parallel.map(2, wrapped)).getCause());
    assertSame(checked, assertThrows(IllegalStateException.class,
        () -> Parallel.map(1, wrapped)).getCause());
  }

  @Test
  @DisplayName("the thread count: unset is the processors, or one for the Elastic Net fits; a "
      + "positive whole number is itself; anything else is refused")
  void threads() {
    final int processors = Runtime.getRuntime().availableProcessors();
    assertEquals(processors, Parallel.threads("X", null));
    assertEquals(processors, Parallel.threads("X", " "));
    assertEquals(1, Parallel.threads("X", "1"));
    assertEquals(6, Parallel.threads("X", " 6 "));
    for (final String bad : List.of("0", "-2", "two", "1.5")) {
      assertThrows(IllegalArgumentException.class, () -> Parallel.threads("X", bad), bad);
    }
    assertEquals(1, Parallel.threads(Parallel.ELASTIC_NET_THREADS, null), "the fits: one");
    assertEquals(processors, Parallel.threads(Parallel.BOOTSTRAP_THREADS, null));
    assertEquals(processors, Parallel.threads(Parallel.EMBEDDING_THREADS, ""));
    assertEquals(4, Parallel.threads(Parallel.ELASTIC_NET_THREADS, "4"));
    assertThrows(IllegalArgumentException.class, () -> Parallel.map(0, List.of(() -> 1)));
  }

  @Test
  @DisplayName("a bad thread count is refused at the start, before any stage runs")
  void checkedUpFront() {
    assumeTrue(System.getenv(Parallel.BOOTSTRAP_THREADS) == null, "set in the environment");
    final String before = System.getProperty(Parallel.BOOTSTRAP_THREADS);
    try {
      System.setProperty(Parallel.BOOTSTRAP_THREADS, "0");
      assertThrows(IllegalArgumentException.class, Parallel::check);
      System.setProperty(Parallel.BOOTSTRAP_THREADS, "3");
      Parallel.check();
    } finally {
      if (before == null) {
        System.clearProperty(Parallel.BOOTSTRAP_THREADS);
      } else {
        System.setProperty(Parallel.BOOTSTRAP_THREADS, before);
      }
    }
  }
}
