package app.config;

import app.identity.Strictness;
import app.model.Columns;
import app.labels.TtcTarget;
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
 * @param ledgerDb read-only source for the identity registry, the contribution ledger and the
 *     per-commit complexity table. Defaults to {@code targetDb}, which reproduces every existing
 *     command's behaviour exactly: they all read the ledger from the same database they write to.
 *     A stage that needs a fresh, empty {@code targetDb} while still reading an already-built
 *     ledger from elsewhere -- a paper branch deriving new labels from a different outcome, never
 *     writing to the database the ledger came from -- sets it explicitly instead
 * @param columnProfile which column set the run fits. Default reproduces the thesis exactly
 * @param ttcTarget which duration of a label the fit regresses: the whole life, the wait before
 *     the first external examination, or the cycle after it. Read only when observations are
 *     assembled -- labelling always records all three, so switching target re-fits without
 *     re-mining, and every target shares one split
 * @param costRatio {@code C_max / C_min}. A reporting parameter only: it is never written to the
 *     ledger, because the sensitivity analysis sweeps it and a stored cost would freeze one
 *     scenario into what otherwise reads as mined data
 * @param javaOnlyVolume rank seniority by lines in {@code .java} files rather than all files, so
 *     that volume is measured in the same language {@code McCC} is
 * @param trainShare the quantile of issue creation instants that separates past from future
 * @param minResolvedBeforeCut how many labels a developer must have resolved before the cut to be
 *     carried into it. Applied to both sides of the split, though it does not bite both equally.
 *     <b>Zero turns the rule off entirely</b>, which is the frozen population of the Yu/Chouchen
 *     replication: that study keys on {@code creator_id} and reads absence of history as an
 *     observable value, so the requirement -- which belongs to the developer-ordering experiment --
 *     would only drop its least frequent authors and projects
 * @param unitsDb where the Yu/Chouchen replication reads its already-built merged pull requests
 *     from, read-only. Defaults to {@code targetDb}, so every command that predates it behaves
 *     exactly as before; that study points it at the frozen database of the previous paper and
 *     writes its own output somewhere else entirely
 * @param paper5Db where the Yu/Chouchen replication writes its attributes and results. Its own
 *     database by specification: the corpus and both previous papers' databases are read-only to
 *     it, and no stage of it may add a column to them
 * @param paper6Db where the transposition of the same method to issue labels writes its results.
 *     Its own database, refused if it names any database the codebase reads
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
    String ledgerDb,
    String unitsDb,
    String paper5Db,
    String paper6Db,
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
    Columns.Profile columnProfile,
    TtcTarget ttcTarget,
    Optional<String> pilotProject) {

  public PipelineConfig {
    if (targetDb.equals(sourceDb)) {
      throw new IllegalArgumentException(
          "TARGET_DB must differ from SOURCE_DB ('" + sourceDb + "'): writing reconstructed data "
              + "into the source would make provenance unrecoverable");
    }
    // The same argument, for the one other database this codebase writes to. PAPER5_DB holding
    // the corpus, the thesis ledger or the frozen units of the previous paper would put a drop()
    // one command away from data no stage here can rebuild.
    if (paper5Db.equals(sourceDb) || paper5Db.equals(unitsDb) || paper5Db.equals(ledgerDb)) {
      throw new IllegalArgumentException(
          "PAPER5_DB must differ from SOURCE_DB, UNITS_DB and LEDGER_DB (got '" + paper5Db
              + "'): the replication writes there and reads the other three, which are frozen");
    }
    // The transposition reads the thesis labels from TARGET_DB and never writes there. Its own
    // output going into any database it reads, or into the PR study's, would be the same mistake.
    if (java.util.List.of(sourceDb, targetDb, unitsDb, ledgerDb, paper5Db).contains(paper6Db)) {
      throw new IllegalArgumentException(
          "PAPER6_DB must differ from SOURCE_DB, TARGET_DB, UNITS_DB, LEDGER_DB and PAPER5_DB (got '"
              + paper6Db + "'): the transposition writes there and only there");
    }
  }

  public static PipelineConfig fromEnvironment() {
    final String targetDb = value("TARGET_DB", "smartshark_tcc");
    return new PipelineConfig(
        value("MONGO_URI", "mongodb://127.0.0.1:27017"),
        value("SOURCE_DB", "smartshark_2_2"),
        targetDb,
        value("LEDGER_DB", targetDb),
        value("UNITS_DB", targetDb),
        value("PAPER5_DB", "smartshark_paper5"),
        value("PAPER6_DB", "smartshark_paper6"),
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
        Columns.Profile.valueOf(value("COLUMNS", Columns.Profile.ISSUE.name())),
        TtcTarget.valueOf(value("TTC_TARGET", TtcTarget.TOTAL.name())),
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
