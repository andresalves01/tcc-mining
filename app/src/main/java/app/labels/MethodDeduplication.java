package app.labels;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses the methods changed across an issue's commits into the set the issue is described by.
 *
 * <p>Two rules, and they are not the same rule applied twice.
 *
 * <p><b>Within an issue, a method counts once.</b> An issue that touches {@code doWork()} in four
 * commits describes one method, not four, and averaging over four copies would silently weight that
 * method four times against everything else the issue touched.
 *
 * <p><b>The surviving metrics are the ones after the last eligible change.</b> A method rewritten
 * twice has two recorded states, and the one that describes what the issue produced is the later.
 * The ordering is authoring instant first, revision hash second — total and reproducible, so that
 * re-running the extraction cannot silently pick a different state because rows came back in a
 * different order.
 *
 * <p>This is deliberately <i>not</i> what happens in a developer's history, where the unit is the
 * commit: the same method touched in two commits months apart is two pieces of work done, and
 * collapsing them would erase the second. Deduplication here describes one task; there it would
 * discard evidence.
 */
public final class MethodDeduplication {

  private MethodDeduplication() {}

  public static List<ChangedMethod> byLatestChange(final Collection<CommitMethods> commits) {
    final Map<String, ChangedMethod> latest = new LinkedHashMap<>();
    commits.stream()
        .sorted(Comparator.comparing(CommitMethods::authoredAt)
            .thenComparing(CommitMethods::revisionHash))
        .forEach(commit -> latest.putAll(commit.methods()));
    return List.copyOf(latest.values());
  }
}
