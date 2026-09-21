package app.pipeline.mongo;

import app.pipeline.jira.JiraChangelogSource;
import app.pipeline.port.Corpus;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;

/**
 * What the source database actually holds, asked before a stage spends an hour finding out.
 *
 * <p>Two variants of SmartSHARK 2.2 are published and they are not interchangeable: the small one
 * omits {@code code_entity_state}, which is where a method's McCC and nesting level come from. Run
 * against it, {@code funnel} would not fail. It would exclude every issue at its last stage and
 * print a funnel that reads like a finding rather than a missing half of the corpus. The same
 * mistake costs one metadata query to diagnose here.
 *
 * <p>Counts come from {@code estimatedDocumentCount}, which reads collection metadata rather than
 * scanning: the point is to tell absent from populated, not to audit the corpus.
 */
public final class MongoCorpusCheck implements Corpus {

  /** Absent from the small release, and required for the method metrics the funnel ends on. */
  static final String CODE_ENTITY_STATE = "code_entity_state";

  private static final String COMMIT = "commit";

  /** The release this pipeline was written against, as its publishers document it. */
  private static final String DOCUMENTED_SERVER = "4.4";

  /**
   * What each stage needs to be there before it runs.
   *
   * <p>Each entry defers to the adapter that does the reading, so the requirement is whatever that
   * adapter actually opens rather than a second list kept in step by hand. Callers name a stage;
   * only this class knows what a stage costs in collections.
   */
  private static final Map<Stage, Set<String>> READS = Map.of(
      Stage.RECONSTRUCT_PEOPLE, GridFsGitIdentitySource.READS,
      Stage.HARVEST_JIRA, JiraChangelogSource.READS,
      Stage.BUILD_LEDGER, MongoContributionSource.READS,
      Stage.FUNNEL, MongoLabelSource.READS,
      Stage.FEATURES, MongoIssueAttributes.READS);

  private final MongoClient client;
  private final String database;

  public MongoCorpusCheck(final MongoClient client, final String database) {
    this.client = client;
    this.database = database;
  }

  @Override
  public List<String> whyCannotRun(final Stage stage) {
    final List<Entry> entries = inspect(READS.get(stage), stage);
    final List<Entry> missing = entries.stream().filter(entry -> !entry.usable()).toList();
    if (missing.isEmpty()) {
      return List.of();
    }

    final List<String> lines = new ArrayList<>();
    lines.add("");
    lines.add("  " + database + " does not hold what this stage reads:");
    missing.forEach(entry ->
        lines.add(String.format("    %-24s %s", entry.name(), entry.state())));
    if (looksLikeSmallRelease(entries)) {
      lines.add("  This looks like the small SmartSHARK release, which ships without the code");
      lines.add("  entity states the method metrics are read from. The full release carries them.");
    }
    return List.copyOf(lines);
  }

  @Override
  public Survey survey() {
    final List<Entry> entries = inspect(everything(), null);
    return new Survey(serverVersion(), DOCUMENTED_SERVER, database, entries, diagnosis(entries));
  }

  /**
   * Looks up {@code names}, in name order.
   *
   * <p>Sorted rather than left in the order asked: a stage declares what it reads as a {@code Set},
   * whose iteration order Java varies between runs, and a report that lists the same gaps in a
   * different order each time is harder to compare than it needs to be.
   */
  private List<Entry> inspect(final Set<String> names, final Stage only) {
    final MongoDatabase db = client.getDatabase(database);
    final Set<String> present = new HashSet<>();
    db.listCollectionNames().forEach(present::add);

    return names.stream().sorted().map(name -> {
      final boolean here = present.contains(name);
      final long documents = here ? db.getCollection(name).estimatedDocumentCount() : 0;
      final String state = !here ? "ABSENT" : documents == 0 ? "EMPTY" : String.format("%,d", documents);
      return new Entry(name, state, here && documents > 0,
          only == null ? needing(name) : Set.of(only));
    }).toList();
  }

  /** Every collection any stage reads, in a stable order. */
  private static Set<String> everything() {
    return READS.values().stream().flatMap(Set::stream).sorted()
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** The stages held up by one collection being absent, for a report that says who cares. */
  private static Set<Stage> needing(final String collection) {
    return Arrays.stream(Stage.values()).filter(stage -> READS.get(stage).contains(collection))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Whether this looks like the small release rather than a wrong database.
   *
   * <p>Distinguished by the corpus being otherwise populated: an empty database is a misdirected
   * {@code SOURCE_DB}, and saying "small release" about it would send the operator to a download
   * page instead of their configuration.
   */
  private static boolean looksLikeSmallRelease(final List<Entry> entries) {
    return entries.stream().anyMatch(e -> e.name().equals(CODE_ENTITY_STATE) && !e.usable())
        && entries.stream().anyMatch(e -> e.name().equals(COMMIT) && e.usable());
  }

  /** What is wrong with this corpus, in words, for a caller that has found something is. */
  private List<String> diagnosis(final List<Entry> entries) {
    if (entries.stream().allMatch(entry -> entry.usable())) {
      return List.of();
    }
    if (looksLikeSmallRelease(entries)) {
      return List.of(
          "  The corpus is populated but carries no code entity states, which is how the",
          "  small SmartSHARK release differs from the full one. The method metrics the",
          "  funnel ends on are read from them, so it would exclude every issue at its",
          "  last stage rather than fail. The full release carries them.");
    }
    return List.of(
        "  Collections marked ABSENT or EMPTY above are missing from " + database + ".",
        "  If that database name is wrong, set SOURCE_DB; otherwise the restore is short.");
  }

  /**
   * The server's own version, or {@code unknown}.
   *
   * <p>Reported rather than enforced. The release is documented against MongoDB 4.4 and the runs
   * behind this pipeline were made on 5.0, so a mismatch is information for the operator, not a
   * fault this class is in a position to declare.
   */
  private String serverVersion() {
    try {
      return client.getDatabase(database).runCommand(new Document("buildInfo", 1))
          .getString("version");
    } catch (final MongoException e) {
      return "unknown";
    }
  }
}
