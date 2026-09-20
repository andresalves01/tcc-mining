package app.cost;

import java.time.Instant;

/**
 * One developer's authorship of one commit, with the volume that commit carried.
 *
 * <p>The mined fact behind both halves of the model. {@code H_j} accumulates volume up to an
 * instant; seniority {@code q} ranks developers by that same accumulation. Storing the per-commit
 * rows rather than a per-developer total is what makes both computable <i>as of</i> any moment —
 * a single total per developer could only ever describe the end of the corpus, and every quantity
 * in this study is anchored to a point in time before the issue was created.
 *
 * <p>Merge commits are absent by construction: their diffs are counted against both parents, which
 * inflates reported volume by 42.67% while accounting for only 4.55% of commits.
 *
 * @param canonicalId the merged person, not the raw {@code author_id} — one human holds several
 *     rows in {@code people}, and ranking them separately would split their history
 * @param bot flagged rather than dropped: whether automated commits count as developer volume is a
 *     modelling choice, and excluding them at mining time would make it unrevisable
 * @param javaLinesAdded lines in {@code .java} files only, so {@code LOC_tot} can be defined either
 *     over all files or over the language {@code McCC} is measured in, without re-mining
 */
public record Contribution(
    String commitId,
    String revisionHash,
    String projectId,
    String projectName,
    String vcsSystemId,
    String authorId,
    String canonicalId,
    boolean bot,
    Instant authoredAt,
    int linesAdded,
    int linesDeleted,
    int javaLinesAdded,
    int javaLinesDeleted,
    int filesTouched,
    int javaFilesTouched) {}
