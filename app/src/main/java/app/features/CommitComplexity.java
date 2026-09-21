package app.features;

/**
 * One commit's contribution to a developer's accumulated complexity.
 *
 * <p>Stored per commit rather than per developer for the reason the contribution ledger is: a
 * single total could only ever describe the end of the corpus, and every quantity in this study is
 * read as of an instant before some issue was created.
 *
 * @param unrecoverable methods this commit touched whose metrics the corpus never recorded; they
 *     are not in {@code mccc} and are not zeros
 */
public record CommitComplexity(
    String commitId, double mccc, double nl, int methods, int unrecoverable) {}
