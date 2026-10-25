package app.issues.port;

import app.issues.Corpus;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Where the issue-duration study reads the corpus from. Read-only, always. */
public interface StudySource {

  /** Everything but the text of the issues. The long read: every commit is scanned once. */
  Corpus load();

  /** Title and description, now and as changed, for the given issues only. */
  Map<String, Corpus.Text> texts(Collection<String> issueIds);

  /** Only the explicitly supplied cohort; includes status history even when code is absent. */
  default Map<String, app.issues.DurationEvidence> durationEvidence(
      java.util.List<app.issues.RowCache.Entry> rows, Set<String> bots) {
    throw new UnsupportedOperationException("duration evidence not supported by this source");
  }

  /**
   * The size and message title of native commits, what is known of commits recovered from pull
   * requests, and the file names of attachments; only the ids given.
   */
  default app.issues.FirstCommitFacts firstCommitFacts(Collection<String> commitIds,
      Collection<String> originalShas, Collection<String> attachmentIds) {
    throw new UnsupportedOperationException("first-commit facts not supported by this source");
  }

  String describe();
}
