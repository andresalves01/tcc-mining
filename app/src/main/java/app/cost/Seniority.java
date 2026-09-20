package app.cost;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Turns accumulated volume into the relative position {@code q} the cost curve consumes.
 *
 * <p>Two choices are made explicit here rather than buried in a query.
 *
 * <p><b>What is accumulated</b> — {@link ExperienceScope}, plus which lines count, passed in as a
 * function so that "all files" and ".java only" are the same code path.
 *
 * <p><b>Who it is ranked against</b> — only developers with at least one contribution strictly
 * before the instant asked about. Ranking against everyone who ever appears in the corpus would put
 * almost the whole population at zero volume in the early years and compress every active developer
 * into the top of the range.
 *
 * <p>Everything is computed as of an instant and nothing is cached, because a cached rank is a
 * leakage bug waiting to happen: it would be correct for the timestamp it was built at and silently
 * wrong for every other one.
 */
public final class Seniority {

  private final ExperienceScope scope;
  private final ToIntFunction<Contribution> volume;

  public Seniority(final ExperienceScope scope, final ToIntFunction<Contribution> volume) {
    this.scope = scope;
    this.volume = volume;
  }

  /**
   * Lines touched across all files: added <i>plus</i> removed.
   *
   * <p>Not net growth. Deleting a hundred lines is work done and evidence of familiarity with the
   * code deleted; counting only additions would credit it as nothing, and would rank a developer who
   * refactors below one who only appends. Both components are stored per commit, so this is a change
   * of definition rather than a re-mining.
   */
  public static ToIntFunction<Contribution> allLines() {
    return c -> c.linesAdded() + c.linesDeleted();
  }

  /** The same, restricted to {@code .java} files — the language {@code McCC} is measured in. */
  public static ToIntFunction<Contribution> javaLines() {
    return c -> c.javaLinesAdded() + c.javaLinesDeleted();
  }

  /**
   * {@code canonicalId -> q} for everyone active before {@code asOf}.
   *
   * @param projectId required under {@link ExperienceScope#PROJECT}, ignored under
   *     {@link ExperienceScope#CAREER} — under career scope the reference population is the whole
   *     corpus, so restricting it to one project would contradict the scope
   */
  public Map<String, Double> at(
      final Collection<Contribution> ledger, final String projectId, final Instant asOf) {

    if (scope == ExperienceScope.PROJECT && projectId == null) {
      throw new IllegalArgumentException("project scope needs a project to accumulate within");
    }
    final Map<String, Long> accumulated = new HashMap<>();
    for (final Contribution c : ledger) {
      if (!c.authoredAt().isBefore(asOf)) {
        continue; // a contribution at exactly asOf is not yet observable at asOf
      }
      if (scope == ExperienceScope.PROJECT && !projectId.equals(c.projectId())) {
        continue;
      }
      accumulated.merge(c.canonicalId(), (long) volume.applyAsInt(c), Long::sum);
    }
    return quantiles(accumulated);
  }

  /**
   * Relative rank in {@code [0, 1]}: how many developers hold strictly less volume, over the whole
   * population {@code n}.
   *
   * <p>The divisor is {@code n} and not {@code n - 1}, matching the definition the paper publishes.
   * The distinction is not cosmetic even though it moves {@code q} by less than a thousandth here:
   * over {@code n - 1} the single most senior developer lands exactly on {@code q = 1} and so is
   * paid {@code C_max}, whereas over {@code n} nobody reaches it and the ceiling stays what the
   * paper calls it — a scenario bound rather than a salary someone is guaranteed to draw.
   *
   * <p>Ties share a value, which settles the degenerate case: when every developer has the same
   * volume nobody outranks anybody, so all get {@code q = 0} and therefore {@code C_min}. A single
   * developer is the same case, with no one to be senior relative to.
   */
  public static Map<String, Double> quantiles(final Map<String, Long> volumeByDeveloper) {
    final int n = volumeByDeveloper.size();
    final Map<String, Double> out = new LinkedHashMap<>();
    if (n == 0) {
      return out;
    }
    final List<Long> sorted = volumeByDeveloper.values().stream().sorted().toList();
    for (final Map.Entry<String, Long> entry : volumeByDeveloper.entrySet()) {
      out.put(entry.getKey(), (double) strictlyLessThan(sorted, entry.getValue()) / n);
    }
    return out;
  }

  /** Index of the first element equal to {@code value} in a sorted list, i.e. how many precede it. */
  private static int strictlyLessThan(final List<Long> sorted, final long value) {
    int low = 0;
    int high = sorted.size();
    while (low < high) {
      final int mid = (low + high) >>> 1;
      if (sorted.get(mid) < value) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }
}
