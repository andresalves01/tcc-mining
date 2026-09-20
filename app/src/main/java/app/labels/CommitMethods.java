package app.labels;

import java.time.Instant;
import java.util.Map;

/**
 * The methods one commit changed, already deduplicated within that commit.
 *
 * <p>The map key is the method's long name, which is what makes the within-commit rule hold by
 * construction: a method reached by three separate hunks of the same commit occupies one entry, so
 * it is counted once no matter how fragmented the diff was.
 *
 * @param revisionHash breaks ties when two commits carry the same authoring instant, so the
 *     ordering that decides which state survives is total and reproducible rather than dependent on
 *     the order the database happened to return rows in
 */
public record CommitMethods(String revisionHash, Instant authoredAt,
    Map<String, ChangedMethod> methods) {}
