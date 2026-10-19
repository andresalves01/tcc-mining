package app.config;

import app.identity.Strictness;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * All external configuration in one place, resolved once at startup.
 *
 * <p>Kept out of the domain and out of the adapters so that neither reads the environment on its
 * own: a class that calls {@code System.getenv} in its constructor cannot be unit-tested without
 * mutating the process, and configuration read in several places drifts.
 *
 * @param targetDb written to; must differ from {@code sourceDb} so reconstructed documents can
 *     never be mistaken for ones that shipped in the published release
 * @param costRatio {@code C_max / C_min}. A reporting parameter only: it is never written to the
 *     ledger, because the sensitivity analysis sweeps it and a stored cost would freeze one
 *     scenario into what otherwise reads as mined data
 * @param javaOnlyVolume rank seniority by lines in {@code .java} files rather than all files, so
 *     that volume is measured in the same language {@code McCC} is
 * @param issuesDb where the issue-duration study records its runs ({@code smartshark_issues}).
 *     Its own database, refused if it names any database the codebase reads
 * @param pilotProject when present, restricts a scan to projects whose name contains it. A full
 *     over {@code commit} reads 84 GB, so a wrong field name should cost one small repository to
 *     discover, not half an hour
 */
public record PipelineConfig(
    String mongoUri,
    String sourceDb,
    String targetDb,
    String issuesDb,
    Path workDir,
    Path jiraCacheDir,
    Duration jiraDelay,
    Strictness strictness,
    double costMinimum,
    double costRatio,
    boolean javaOnlyVolume,
    Optional<String> pilotProject) {

  public PipelineConfig {
    if (targetDb.equals(sourceDb)) {
      throw new IllegalArgumentException(
          "TARGET_DB must differ from SOURCE_DB ('" + sourceDb + "'): writing reconstructed data "
              + "into the source would make provenance unrecoverable");
    }
    // The issue study reads the thesis labels from TARGET_DB and never writes there. Its own
    // output going into a database it reads would be the same mistake.
    if (java.util.List.of(sourceDb, targetDb).contains(issuesDb)) {
      throw new IllegalArgumentException(
          "ISSUES_DB must differ from SOURCE_DB and TARGET_DB (got '"
              + issuesDb + "'): the issue study writes there and only there");
    }
  }

  public static PipelineConfig fromEnvironment() {
    final String targetDb = value("TARGET_DB", "smartshark_tcc");
    return new PipelineConfig(
        value("MONGO_URI", "mongodb://127.0.0.1:27017"),
        value("SOURCE_DB", "smartshark_2_2"),
        targetDb,
        value("ISSUES_DB", "smartshark_issues"),
        Path.of(value("WORK_DIR", "build/identity-work")),
        Path.of(value("JIRA_CACHE_DIR", "build/jira-cache")),
        Duration.ofMillis(Long.parseLong(value("JIRA_DELAY_MS", "250"))),
        Strictness.valueOf(value("MERGE_STRICTNESS", Strictness.EMAIL_AND_FULL_NAME.name())),
        Double.parseDouble(value("COST_MIN", "1.0")),
        Double.parseDouble(value("COST_RATIO", "1.7283")),
        !"ALL".equalsIgnoreCase(value("VOLUME", "JAVA")),
        Optional.of(value("PILOT", "")).filter(pilot -> !pilot.isBlank()));
  }

  /** Environment first, then system properties, so both {@code FOO=} and {@code -DFOO=} work. */
  private static String value(final String key, final String fallback) {
    final String fromEnv = System.getenv(key);
    final String found = fromEnv == null || fromEnv.isBlank() ? System.getProperty(key) : fromEnv;
    return found == null || found.isBlank() ? fallback : found;
  }

  public String describe() {
    return "source: " + sourceDb + "   target: " + targetDb + "   uri: " + mongoUri;
  }
}
