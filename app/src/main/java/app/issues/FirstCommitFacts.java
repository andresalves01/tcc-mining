package app.issues;

import java.util.List;
import java.util.Map;

/**
 * What the first-commit audit reads of commits and attachments beyond the {@link Corpus}.
 *
 * @param natives per commit id, its size and the first line of its message
 * @param originals per hash of a commit recovered from a pull request, what is known of it
 * @param attachments per attachment event id, the attached file's name
 */
public record FirstCommitFacts(Map<String, Native> natives, Map<String, Original> originals,
    Map<String, String> attachments) {

  /**
   * @param lines lines added plus removed in every file; null when the ledger has no row for it
   */
  public record Native(Long lines, String title) {}

  /**
   * @param author the raw account that wrote it, as the pull request records it
   * @param lines the size of the repository's copy of it; null when the repository has none
   * @param linked the issues the repository's copy is linked to; empty without one
   */
  public record Original(String author, String title, Long lines, List<String> linked) {}

  public FirstCommitFacts {
    natives = Map.copyOf(natives);
    originals = Map.copyOf(originals);
    attachments = Map.copyOf(attachments);
  }
}
