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
 * @param trainShare the quantile of issue creation instants that separates past from future
 * @param minResolvedBeforeCut how many labels a developer must have resolved before the cut to be
 *     carried into it. Applied to both sides of the split, though it does not bite both equally
 * @param teamWindow how far back a commit still counts as presence on the active team. It bounds
 *     participation observed and deliberately does not discount its members' accumulated
 *     experience: someone who committed yesterday brings their whole history with them
 * @param pilotProject when present, restricts a scan to projects whose name contains it. A full
 *     over {@code commit} reads 84 GB, so a wrong field name should cost one small repository to
 *     discover, not half an hour
 */
public record PipelineConfig(
    String mongoUri,
    String sourceDb,
    String targetDb,
    Path workDir,
    Path jiraCacheDir,
    Duration jiraDelay,
    Strictness strictness,
    double costMinimum,
    double costRatio,
    boolean javaOnlyVolume,
    double trainShare,
    int minResolvedBeforeCut,
    Duration teamWindow,
    Optional<String> pilotProject) {

  public PipelineConfig {
    if (targetDb.equals(sourceDb)) {
      throw new IllegalArgumentException(
          "TARGET_DB must differ from SOURCE_DB ('" + sourceDb + "'): writing reconstructed data "
              + "into the source would make provenance unrecoverable");
    }
  }

  public static PipelineConfig fromEnvironment() {
    return new PipelineConfig(
        value("MONGO_URI", "mongodb://127.0.0.1:27017"),
        value("SOURCE_DB", "smartshark_2_2"),
        value("TARGET_DB", "smartshark_tcc"),
        Path.of(value("WORK_DIR", "build/identity-work")),
        Path.of(value("JIRA_CACHE_DIR", "build/jira-cache")),
        Duration.ofMillis(Long.parseLong(value("JIRA_DELAY_MS", "250"))),
        Strictness.valueOf(value("MERGE_STRICTNESS", Strictness.EMAIL_AND_FULL_NAME.name())),
        Double.parseDouble(value("COST_MIN", "1.0")),
        Double.parseDouble(value("COST_RATIO", "1.7283")),
        !"ALL".equalsIgnoreCase(value("VOLUME", "JAVA")),
        Double.parseDouble(value("TRAIN_SHARE", "0.70")),
        Integer.parseInt(value("MIN_RESOLVED", "5")),
        Duration.ofDays(Long.parseLong(value("TEAM_WINDOW_DAYS", "90"))),
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
