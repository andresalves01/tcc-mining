package app.pipeline.mongo;

import app.issues.FirstCommitFacts;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

/** Reads, by id only, what the first-commit audit needs beyond the corpus. Read-only. */
final class MongoFirstCommitFacts {

  private static final int BATCH = 2_000;

  private final MongoDatabase source;
  private final MongoDatabase thesis;

  MongoFirstCommitFacts(final MongoDatabase source, final MongoDatabase thesis) {
    this.source = source;
    this.thesis = thesis;
  }

  private static String id(final Object o) {
    return o instanceof ObjectId x ? x.toHexString() : null;
  }

  /** The first line of a commit message, stripped. */
  static String title(final String message) {
    return Objects.toString(message, "").lines().findFirst().orElse("").strip();
  }

  /** Lines added plus removed in every file; null when either count is missing. */
  static Long lines(final Document contribution) {
    if (contribution == null || !(contribution.get("lines_added") instanceof Number a)
        || !(contribution.get("lines_deleted") instanceof Number d)) {
      return null;
    }
    return a.longValue() + d.longValue();
  }

  /** Every document of {@code collection} whose {@code field} is in {@code values}, in batches. */
  private static <T> void each(final MongoDatabase db, final String collection,
      final String field, final List<T> values, final Consumer<Document> sink,
      final String... fields) {
    for (int from = 0; from < values.size(); from += BATCH) {
      final Bson filter = Filters.in(field, values.subList(from,
          Math.min(from + BATCH, values.size())));
      db.getCollection(collection).find(filter).projection(Projections.include(fields))
          .sort(Sorts.ascending("_id")).forEach(sink);
    }
  }

  private static List<ObjectId> objectIds(final Collection<String> ids) {
    return ids.stream().filter(Objects::nonNull).distinct().sorted().map(ObjectId::new).toList();
  }

  FirstCommitFacts load(final Collection<String> commitIds, final Collection<String> shas,
      final Collection<String> attachmentIds) {
    // native commits: message title and size
    final Map<String, String> titles = new HashMap<>();
    each(source, "commit", "_id", objectIds(commitIds),
        d -> titles.put(id(d.get("_id")), title(d.getString("message"))), "message");
    final Map<String, Long> sizes = new HashMap<>();
    each(thesis, "developer_contribution", "_id", objectIds(commitIds),
        d -> sizes.put(id(d.get("_id")), lines(d)), "lines_added", "lines_deleted");
    final Map<String, FirstCommitFacts.Native> natives = new HashMap<>();
    for (final String c : titles.keySet()) {
      natives.put(c, new FirstCommitFacts.Native(sizes.get(c), titles.get(c)));
    }

    // originals: who wrote them, as the pull request says, and the repository's copy if any
    final List<String> hashes = shas.stream().filter(Objects::nonNull).distinct().sorted()
        .toList();
    final Map<String, String> authors = new HashMap<>();
    final Map<String, String> prTitles = new HashMap<>();
    each(source, "pull_request_commit", "commit_sha", hashes, d -> {
      final String sha = d.getString("commit_sha");
      if (id(d.get("author_id")) != null) {
        authors.putIfAbsent(sha, id(d.get("author_id")));
      }
      prTitles.putIfAbsent(sha, title(d.getString("message")));
    }, "commit_sha", "author_id", "message");
    final Map<String, String> copyOf = new HashMap<>();
    final Map<String, List<String>> linkedOf = new HashMap<>();
    each(source, "commit", "revision_hash", hashes, d -> {
      final String sha = d.getString("revision_hash");
      if (copyOf.putIfAbsent(sha, id(d.get("_id"))) == null) {
        final List<String> linked = new ArrayList<>();
        d.getList("linked_issue_ids", Object.class, List.of()).forEach(x -> linked.add(id(x)));
        linkedOf.put(sha, linked.stream().filter(Objects::nonNull).toList());
      }
    }, "revision_hash", "linked_issue_ids");
    final Map<String, Long> copySizes = new HashMap<>();
    each(thesis, "developer_contribution", "_id", objectIds(copyOf.values()),
        d -> copySizes.put(id(d.get("_id")), lines(d)), "lines_added", "lines_deleted");
    final Map<String, FirstCommitFacts.Original> originals = new HashMap<>();
    for (final String sha : hashes) {
      final String copy = copyOf.get(sha);
      originals.put(sha, new FirstCommitFacts.Original(authors.get(sha), prTitles.get(sha),
          copy == null ? null : copySizes.get(copy), linkedOf.getOrDefault(sha, List.of())));
    }

    // attachments: the file name
    final Map<String, String> names = new HashMap<>();
    each(source, "event", "_id", objectIds(attachmentIds), d -> {
      if (d.get("new_value") != null) {
        names.put(id(d.get("_id")), d.get("new_value").toString());
      }
    }, "new_value");
    return new FirstCommitFacts(natives, originals, names);
  }
}
