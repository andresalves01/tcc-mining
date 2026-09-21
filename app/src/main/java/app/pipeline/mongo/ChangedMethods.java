package app.pipeline.mongo;

import app.labels.ChangedMethod;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * The methods one commit changed: every method whose line range meets the range of one of that
 * commit's hunks, in the same file.
 *
 * <p>Both sides are read at the commit itself, which is what the corpus records — the entity states
 * filed under a commit describe the file as it stands after it. The parent's states would be needed
 * to see a method the commit deleted, and they are absent for most touched files, so a deleted
 * method leaves no measurement rather than a zero.
 *
 * <p>Extracted from the funnel so that the two quantities built on it share one implementation. The
 * task-level metrics deduplicate once per issue and the developer's accumulated complexity counts
 * once per commit — deliberately different rules, as {@link app.labels.MethodDeduplication}
 * explains — but the rule for <i>which methods a commit changed</i> is the same one, and a second
 * copy of it would drift without anything failing.
 *
 * <p>Three queries per commit, which is why every stage built on this is the expensive one.
 */
final class ChangedMethods {

  static final String FILE_ACTION = "file_action";
  static final String HUNK = "hunk";
  static final String ENTITY_STATE = "code_entity_state";

  /** The collections this reader opens, for the stages that must declare what they cost. */
  static final Set<String> READS = Set.of(FILE_ACTION, HUNK, ENTITY_STATE);

  /**
   * What one commit changed.
   *
   * @param recovered keyed by long name, one entry per method however many hunks reach it
   * @param unrecoverable methods whose range the commit touched but whose {@code McCC} or
   *     {@code NL} the corpus never recorded. They are <b>not</b> in {@code recovered} and are
   *     <b>not</b> zeros: the study reports this coverage rather than reading it as simple code
   */
  record Extraction(Map<String, ChangedMethod> recovered, int unrecoverable) {

    static Extraction none() {
      return new Extraction(Map.of(), 0);
    }
  }

  private final MongoDatabase source;

  ChangedMethods(final MongoDatabase source) {
    this.source = source;
  }

  Extraction of(final ObjectId commitId, final Set<ObjectId> javaFiles) {
    final Map<ObjectId, ObjectId> fileByAction = new HashMap<>();
    for (final Document action : source.getCollection(FILE_ACTION)
        .find(Filters.eq("commit_id", commitId))
        .projection(Projections.include("file_id"))) {
      final ObjectId fileId = action.getObjectId("file_id");
      if (javaFiles.contains(fileId)) {
        fileByAction.put(action.getObjectId("_id"), fileId);
      }
    }
    if (fileByAction.isEmpty()) {
      return Extraction.none();
    }

    final Map<ObjectId, List<int[]>> rangesByFile = new HashMap<>();
    for (final Document hunk : source.getCollection(HUNK)
        .find(Filters.in("file_action_id", fileByAction.keySet()))
        .projection(Projections.include("file_action_id", "new_start", "new_lines"))) {
      final int lines = intOf(hunk.get("new_lines"));
      if (lines <= 0) {
        continue; // a pure deletion adds no line to intersect against
      }
      final int start = intOf(hunk.get("new_start"));
      rangesByFile.computeIfAbsent(fileByAction.get(hunk.getObjectId("file_action_id")),
          key -> new ArrayList<>()).add(new int[] {start, start + lines - 1});
    }
    if (rangesByFile.isEmpty()) {
      return Extraction.none();
    }

    final Map<String, ChangedMethod> changed = new LinkedHashMap<>();
    int unrecoverable = 0;
    for (final Document entity : source.getCollection(ENTITY_STATE)
        .find(Filters.and(Filters.eq("commit_id", commitId), Filters.eq("ce_type", "method"),
            Filters.in("file_id", rangesByFile.keySet())))
        .projection(Projections.include(
            "file_id", "start_line", "end_line", "long_name", "metrics.McCC", "metrics.NL"))) {

      final int start = intOf(entity.get("start_line"));
      final int end = intOf(entity.get("end_line"));
      boolean touched = false;
      for (final int[] range : rangesByFile.getOrDefault(entity.getObjectId("file_id"), List.of())) {
        if (start <= range[1] && end >= range[0]) {
          touched = true;
          break; // within a commit a method counts once, however many hunks reach it
        }
      }
      if (!touched) {
        continue;
      }
      final Document metrics = entity.get("metrics", Document.class);
      final String longName = entity.getString("long_name");
      if (metrics == null || longName == null
          || metrics.get("McCC") == null || metrics.get("NL") == null) {
        unrecoverable++; // not recoverable, and not a zero
        continue;
      }
      changed.put(longName, new ChangedMethod(longName,
          ((Number) metrics.get("McCC")).doubleValue(),
          ((Number) metrics.get("NL")).doubleValue()));
    }
    return new Extraction(changed, unrecoverable);
  }

  /** Every {@code .java} file in the corpus, by id — the set both stages intersect against. */
  static Set<ObjectId> javaFileIds(final MongoDatabase source, final String fileCollection) {
    final Set<ObjectId> ids = new java.util.HashSet<>(1 << 20);
    for (final Document file : source.getCollection(fileCollection)
        .find().projection(Projections.include("path"))) {
      final String path = file.getString("path");
      if (path != null && path.endsWith(".java")) {
        ids.add(file.getObjectId("_id"));
      }
    }
    return ids;
  }

  static int intOf(final Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }
}
