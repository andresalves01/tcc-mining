package app.paper6.port;

import app.paper6.Corpus;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Where the issue-duration study reads the corpus from. Read-only, always. */
public interface StudySource {

  /** Everything but the text of the issues. The long read: every commit is scanned once. */
  Corpus load();

  /** Title and description, now and as changed, for the given issues only. */
  Map<String, Corpus.Text> texts(Collection<String> issueIds);

  /** The collections this source opens, so {@code check} can refuse before a long read. */
  Set<String> reads();

  String describe();
}
