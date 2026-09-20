package app.labels.port;

import app.labels.LabelScan;
import java.util.Map;
import java.util.Set;

/** Where labels are mined from. A port, so the funnel rules never name a storage technology. */
public interface LabelSource {

  /** @param pilot when set, restricts the scan to projects whose name contains it */
  /**
   * @param pilot restricts the scan to projects whose name contains it; {@code null} scans all
   * @param canonicalByPerson the equivalence relation every commit author is resolved through
   * @param bots canonical ids whose commits are automated
   */
  LabelScan scan(String pilot, Map<String, String> canonicalByPerson, Set<String> bots);
}
