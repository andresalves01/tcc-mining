package app.issues;

import java.util.List;

/** Read-only evidence of code and human activity, with original timestamp provenance. */
public record DurationEvidence(List<Change> changes, List<Activity> activities,
    List<Corpus.Event> events, String quality, List<String> warnings, List<Integration> integrations) {
  public static final String SOURCE_RULE_VERSION = "duration-evidence-v1.1";

  public DurationEvidence {
    changes = List.copyOf(changes);
    activities = List.copyOf(activities);
    events = List.copyOf(events);
    integrations = integrations == null ? List.of() : List.copyOf(integrations);
    final var flags = new java.util.LinkedHashSet<>(warnings);
    for (final var c : changes)
      if (c.committedAt() != null && c.authoredAt() > c.committedAt())
        flags.add("author_after_committer:" + c.sha());
    final long lastOriginal = changes.stream().filter(Change::original)
        .mapToLong(Change::authoredAt).max().orElse(Long.MIN_VALUE);
    for (final var i : integrations)
      if (i.replacedByOriginals() && lastOriginal > i.visibleAt())
        flags.add("original_after_integration:" + i.sha());
    warnings = List.copyOf(flags);
  }

  /** Native committer/visibility clocks, retained even when originals replace that observation. */
  public record Integration(String sha, Long committedAt, long visibleAt, String sourceId,
      boolean replacedByOriginals) {}

  public record Change(String sha, long authoredAt, Long committedAt, String source,
      String sourceId, boolean original) {}

  public record Activity(long at, String kind, String sourceId) {}

  public static List<Corpus.Event> ordered(final List<Corpus.Event> events) {
    return Universe.changelog(events).values().stream().findFirst().map(List::copyOf).orElse(List.of());
  }

}
