package app.pipeline.jira;

import app.identity.JiraResolution;
import app.identity.PersonRecord;
import app.identity.port.JiraIdentitySource;
import app.pipeline.port.Progress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Recovers the Jira half of {@code people} from the public issue tracker.
 *
 * <p><b>Why a second source is needed.</b> The git reconstruction resolves everyone who ever
 * committed, but a label in this study is keyed on {@code issue.assignee_id}, which lives in a
 * different id space: of 4,453 distinct Jira assignee ids, only 365 (8.2%) also appear as a git
 * author. Without this step, {@code H_j} — accumulated from commits — cannot be attached to the
 * developer a label is about. That join is the model, so this is not optional refinement.
 *
 * <p><b>The join is structural, not heuristic.</b> SmartSHARK stores {@code event.external_id} as
 * {@code "{jiraHistoryId}%%{itemIndex}"}, and Jira's changelog exposes the same history id and item
 * ordering, so an event maps onto a changelog item exactly:
 *
 * <pre>
 *   our event    external_id = "16504282%%0"   new_value = ObjectId("5bc05c8c…")
 *   Jira         history 16504282, items[0]    assignee -&gt; to="stain", "Stian Soiland-Reyes"
 * </pre>
 *
 * <p>No timestamp tolerance, no name similarity, and no dependence on the issue↔commit linkage —
 * which matters, since a bridge inferred from that linkage would be circular once the map is used
 * to attach commit-derived features to issue-derived labels.
 *
 * <p><b>On courtesy.</b> These are read-only GETs against a third party. Responses are cached on
 * disk so re-runs cost nothing, requests are paced, and failures are backed off rather than
 * retried tightly.
 */
public final class JiraChangelogSource implements JiraIdentitySource {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<Integer> BACKOFF_SECONDS = List.of(2, 8, 30);
  private static final int PROGRESS_EVERY = 100;
  private static final String SEPARATOR = "%%";

  private static final String ISSUE = "issue";
  private static final String ISSUE_SYSTEM = "issue_system";
  private static final String EVENT = "event";

  /** The collections this source opens. Declared here so it cannot drift from
   * the queries above it; {@link app.pipeline.mongo.MongoCorpusCheck} verifies it. */
  public static final Set<String> READS = Set.of(ISSUE, ISSUE_SYSTEM, EVENT);

  private final MongoClient client;
  private final String sourceDb;
  private final Path cacheDir;
  private final Duration delay;
  private final Progress progress;
  private final HttpClient http;

  public JiraChangelogSource(final MongoClient client, final String sourceDb, final Path cacheDir,
      final Duration delay, final Progress progress) {
    this.client = client;
    this.sourceDb = sourceDb;
    this.cacheDir = cacheDir;
    this.delay = delay;
    this.progress = progress;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  @Override
  public JiraResolution resolve(final Set<String> alreadyResolved)
      throws IOException, InterruptedException {

    final MongoDatabase db = client.getDatabase(sourceDb);
    Files.createDirectories(cacheDir);

    progress.phase(1, 3, "locating one issue per unresolved assignee");
    final Map<String, ObjectId> personToIssue = findIssuesForUnresolvedAssignees(db, alreadyResolved);
    progress.detail("%,d assignee ids need resolving", personToIssue.size());

    final Map<ObjectId, String> issueKeys = new HashMap<>();
    final Map<ObjectId, ObjectId> issueSystems = new HashMap<>();
    for (final Document issue : db.getCollection(ISSUE)
        .find(Filters.in("_id", new HashSet<>(personToIssue.values())))
        .projection(Projections.include("external_id", "issue_system_id"))) {
      issueKeys.put(issue.getObjectId("_id"), issue.getString("external_id"));
      issueSystems.put(issue.getObjectId("_id"), issue.getObjectId("issue_system_id"));
    }

    final Map<ObjectId, String> apiBases = new HashMap<>();
    for (final Document system : db.getCollection(ISSUE_SYSTEM).find()
        .projection(Projections.include("url"))) {
      apiBaseOf(system.getString("url"))
          .ifPresent(base -> apiBases.put(system.getObjectId("_id"), base));
    }

    progress.phase(2, 3, "fetching changelogs");
    final Map<String, PersonRecord> resolved = new LinkedHashMap<>();
    int fetched = 0;
    int cached = 0;
    int failed = 0;
    int processed = 0;

    for (final Map.Entry<String, ObjectId> entry : personToIssue.entrySet()) {
      processed++;
      if (resolved.containsKey(entry.getKey())) {
        continue; // already resolved as a by-product of an earlier issue
      }
      final ObjectId issueId = entry.getValue();
      final String key = issueKeys.get(issueId);
      final String base = apiBases.get(issueSystems.get(issueId));
      if (key == null || base == null) {
        failed++;
        continue;
      }

      final Path cacheFile = cacheDir.resolve(key.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
      final String body;
      if (Files.exists(cacheFile)) {
        body = Files.readString(cacheFile);
        cached++;
      } else {
        final String downloaded =
            fetchWithRetry(base + "/issue/" + key + "?expand=changelog&fields=assignee");
        if (downloaded == null) {
          failed++;
          continue;
        }
        Files.writeString(cacheFile, downloaded);
        body = downloaded;
        fetched++;
        Thread.sleep(delay.toMillis());
      }

      try {
        matchChangelogToEvents(db, issueId, body, alreadyResolved, resolved);
      } catch (final IllegalStateException e) {
        failed++;
      }

      if (processed % PROGRESS_EVERY == 0) {
        progress.count(processed, personToIssue.size(),
            "assignees  (%,d resolved, %,d fetched, %,d cached, %,d failed)",
            resolved.size(), fetched, cached, failed);
      }
    }

    progress.phase(3, 3, "resolved %,d of %,d", resolved.size(), personToIssue.size());
    return new JiraResolution(new ArrayList<>(resolved.values()), fetched, cached, failed);
  }

  /** One representative issue per assignee id that is not already known. */
  private Map<String, ObjectId> findIssuesForUnresolvedAssignees(
      final MongoDatabase db, final Set<String> known) {

    final Map<String, ObjectId> personToIssue = new LinkedHashMap<>();
    for (final Document event : db.getCollection(EVENT)
        .find(Filters.eq("status", "assignee_id"))
        .projection(Projections.include("issue_id", "new_value"))) {
      if (!(event.get("new_value") instanceof ObjectId personId)) {
        continue;
      }
      final String hex = personId.toHexString();
      if (known.contains(hex) || personToIssue.containsKey(hex)) {
        continue;
      }
      personToIssue.put(hex, event.getObjectId("issue_id"));
    }
    return personToIssue;
  }

  /**
   * Maps this issue's {@code assignee_id} events onto changelog items via the shared history id.
   *
   * <p>A fetched issue carries every assignee change it ever had, including ones belonging to people
   * the git reconstruction already resolved. Those must be skipped: a Jira record has a display name
   * but no address, so writing one over an existing git identity would discard that person's email —
   * the strongest merge key available.
   */
  private void matchChangelogToEvents(
      final MongoDatabase db,
      final ObjectId issueId,
      final String body,
      final Set<String> alreadyResolved,
      final Map<String, PersonRecord> resolved) {

    final JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (final IOException e) {
      throw new IllegalStateException("unreadable changelog payload", e);
    }

    final Map<String, JsonNode> histories = new HashMap<>();
    for (final JsonNode history : root.path("changelog").path("histories")) {
      histories.put(history.path("id").asText(), history.path("items"));
    }

    for (final Document event : db.getCollection(EVENT)
        .find(Filters.and(Filters.eq("issue_id", issueId), Filters.eq("status", "assignee_id")))
        .projection(Projections.include("external_id", "new_value"))) {

      final String externalId = event.getString("external_id");
      if (!(event.get("new_value") instanceof ObjectId personId) || externalId == null) {
        continue;
      }
      final int separator = externalId.indexOf(SEPARATOR);
      if (separator < 0) {
        continue;
      }
      final JsonNode items = histories.get(externalId.substring(0, separator));
      if (items == null) {
        continue;
      }
      final int itemIndex = parseIndex(externalId.substring(separator + SEPARATOR.length()));
      if (itemIndex < 0 || itemIndex >= items.size()) {
        continue;
      }
      final JsonNode item = items.get(itemIndex);
      if (!"assignee".equals(item.path("field").asText())) {
        continue; // ordering disagreement -- refuse rather than guess
      }
      final String username = item.path("to").asText(null);
      if (username == null || username.isBlank()) {
        continue; // an unassignment
      }
      final String hex = personId.toHexString();
      if (alreadyResolved.contains(hex)) {
        continue; // already known from git -- never overwrite a real address with a blank one
      }
      resolved.putIfAbsent(hex, PersonRecord.jira(hex, item.path("toString").asText(null), username));
    }
  }

  private static int parseIndex(final String raw) {
    try {
      return Integer.parseInt(raw);
    } catch (final NumberFormatException e) {
      return -1;
    }
  }

  /** {@code …/rest/api/2/search?jql=…} -&gt; {@code …/rest/api/2}, when the url carries one. */
  public static Optional<String> apiBaseOf(final String issueSystemUrl) {
    if (issueSystemUrl == null) {
      return Optional.empty();
    }
    final int index = issueSystemUrl.indexOf("/search");
    return index < 0 ? Optional.empty() : Optional.of(issueSystemUrl.substring(0, index));
  }

  private String fetchWithRetry(final String url) throws InterruptedException {
    for (int attempt = 0; attempt <= BACKOFF_SECONDS.size(); attempt++) {
      try {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(45))
            .header("Accept", "application/json")
            .header("User-Agent", "puc-tcc-team-composition/1.0 (academic research; read-only)")
            .GET()
            .build();
        final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        final int status = response.statusCode();
        if (status == 200) {
          return response.body();
        }
        if (status == 401 || status == 403 || status == 404) {
          return null; // deleted, moved, or restricted -- retrying will not help
        }
      } catch (final IOException e) {
        // fall through to the backoff below
      }
      if (attempt < BACKOFF_SECONDS.size()) {
        Thread.sleep(Duration.ofSeconds(BACKOFF_SECONDS.get(attempt)).toMillis());
      }
    }
    return null;
  }
}
