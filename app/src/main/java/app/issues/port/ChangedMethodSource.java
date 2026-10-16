package app.issues.port;

import app.labels.ChangedMethod;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Where the retrospective scenario reads the methods each commit changed (ticket 15), by the
 * funnel's rule: a method of a {@code .java} file whose line range meets the new range of one of
 * the commit's hunks, read as the file stands after the commit, pure deletions skipped. Read-only,
 * always.
 */
public interface ChangedMethodSource {

  /**
   * Per commit id, the methods it changed whose cyclomatic complexity and nesting the corpus
   * recorded, keyed by long name; a commit that changed none may be left out.
   */
  Map<String, Map<String, ChangedMethod>> changed(Collection<String> commitIds);

  /** The collections this source opens, so {@code check} can refuse before a long read. */
  Set<String> reads();
}
