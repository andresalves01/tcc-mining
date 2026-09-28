package app.paper6;

/**
 * What the corpus can demonstrate about a construct at {@code t0}, the instant the issue was created.
 *
 * <p>The same axis the pull-request study cut its profiles along, with different contents, and the
 * difference is the finding of the transposition. A pull request is born with its diff and without
 * an edit history; an issue is born without any diff and with a changelog. So the class the PR study
 * leaned on -- a final value standing in for the opening one -- has no member here, and a class the
 * PR study could not have -- a value recovered exactly by undoing the recorded edits -- has many.
 *
 * <p>Ordered from strongest evidence to weakest, and the four profiles read it that way.
 */
public enum TemporalClass {

  /** Every event it reads is dated and strictly earlier than {@code t0}. */
  LIMPO,

  /**
   * A field of the issue itself, whose value at {@code t0} is recovered by undoing, in reverse
   * order, every change the {@code event} collection recorded after {@code t0}.
   *
   * <p>Not "plausible": reconstructed. The claim it rests on is that the changelog is complete, and
   * that is measured per field by the extractor -- the share of issues whose last recorded change
   * agrees with the stored final value -- and printed beside every result of the profile that
   * admits it. Where the log is incomplete, the reconstruction inherits the gap; the number says how
   * large the gap is.
   */
  RECONSTRUIDO_DO_CHANGELOG,

  /**
   * Dated and strictly earlier than {@code t0} -- <b>given the person who will do the work</b>, who
   * is not known at {@code t0}.
   *
   * <p>The author's own issue specification keys every developer column on {@code canonical_id}, the
   * person who wrote the commits linked to the issue. That person is identified by those commits,
   * which come after creation. So the history is clean and the choice of whose history is not: this
   * class answers "given the assignment, how long?", a legitimate question that is not "given the
   * issue, how long?". {@code iss_team} lands here too, for the same reason one step removed: it
   * counts the team's open issues by attributing each of them through commits made later.
   */
  CONDICIONADO_A_ATRIBUICAO,

  /**
   * The work itself: lines, methods, complexity and commits of the change that resolved the issue.
   *
   * <p>At creation none of it exists in any form. It enters only the ceiling profile, as the answer
   * to "what would the literature's size constructs be worth if the change were known in advance",
   * and is never described as known at creation.
   */
  OBSERVAVEL_APENAS_NO_DESFECHO
}
