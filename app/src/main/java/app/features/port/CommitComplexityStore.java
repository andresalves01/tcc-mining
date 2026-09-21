package app.features.port;

import app.features.CommitComplexity;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Where the per-commit complexity table is kept.
 *
 * <p>A commit's complexity is a property of the commit and of the corpus, not of the current label
 * set, so rows are upserted and never dropped: a second run repairs and extends rather than
 * replacing. That is also what makes the build resumable, which matters more here than anywhere
 * else in the pipeline.
 */
public interface CommitComplexityStore {

  long save(Collection<CommitComplexity> rows);

  /** Which commits are already read, so a resumed run does not pay for them twice. */
  Set<String> storedIds();

  /** The whole table, keyed by commit id. Empty means it was never built. */
  Map<String, CommitComplexity> readAll();

  String describe();
}
