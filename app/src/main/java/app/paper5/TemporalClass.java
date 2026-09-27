package app.paper5;

/**
 * What the corpus can actually demonstrate about a construct at {@code t0}, the instant the pull
 * request opened.
 *
 * <p>This is the axis the three evidence profiles are cut along, and it is a finding rather than a
 * design choice: every value here was assigned by measurement, recorded in
 * {@code MATRIZ-ATRIBUTOS.csv} and in the two investigation reports beside it. Carrying it on the
 * column means a result can never be reported without the claim it rests on.
 *
 * <p>The ordering is from strongest evidence to weakest, and profiles read it that way.
 */
public enum TemporalClass {

  /**
   * Demonstrably known at {@code t0}: every event it reads is timestamped and strictly earlier.
   */
  LIMPO,

  /**
   * Not stored as of {@code t0}, but the final value is a <b>measured proxy</b> for it.
   *
   * <p>Added on 27/09/2026 by André's decision, after the measurement in
   * {@code MEDICAO-ESTABILIDADE.md} rather than before it. The corpus stores the size of the change
   * at merge, never at opening. The question is whether the stored value stands in for the missing
   * one, and that is answerable: on the 4.166 pull requests whose commits all resolve -- 46,2% of
   * the population, and 87,4% of cópia pessoal against the population's 92,5%, so barely tilted --
   * the rank correlation between the value at opening and the value at merge is <b>0,857 for lines
   * and 0,848 for files</b>. Restricted to cópia pessoal alone it is 0,834 and 0,823, which removes
   * the main reason to distrust the subpopulation.
   *
   * <p><b>What the 0,85 does and does not license.</b> It licenses ordering: a pull request that was
   * large at opening is large at merge. It does not license magnitude -- among the ones that grew,
   * the ninetieth percentile added 6.253 lines. So the proxy is good for the rank correlation this
   * study reports as its secondary metric and injects error into the absolute error it reports as
   * its primary one. That asymmetry belongs beside every number the profile produces.
   */
  ESTAVEL_MEDIDO_ABERTURA_MERGE,

  /**
   * Derived from the list of touched files, and stable only by inheriting that list's stability.
   *
   * <p>The file list itself was measured: rank correlation 0,848, and identical between opening and
   * merge in 79,5% of cases. These constructs are computed <i>from</i> that list, so the argument is
   * that they inherit its stability -- but the probe recorded counts per commit and not paths, so
   * <b>they were never measured directly</b>. André chose on 27/09/2026 not to run the path probe
   * now and to carry the inheritance as a declared argument instead.
   *
   * <p>Separate from {@link #ESTAVEL_MEDIDO_ABERTURA_MERGE} for exactly that reason: the two enter
   * the same profile, and a reader must still be able to tell which ones carry a number and which
   * carry an argument. Collapsing them would make the measured claim cover four constructs it never
   * examined.
   */
  ESTAVEL_HERDADO_DA_LISTA_DE_ARQUIVOS,

  /**
   * Plausibly known at {@code t0}, and not demonstrable either way.
   *
   * <p>The three textual constructs. A pull request's title and body almost certainly existed at
   * submission, but the corpus keeps only the state it collected later and records no edit history,
   * so invariance cannot be shown and contamination cannot be ruled out. They are isolated in their
   * own profile precisely so their contribution can be read as a separate number.
   */
  NAO_VERIFICAVEL_EM_T0,

  /**
   * Discarded from every ex ante reading, on a qualitative argument André owns and can defend.
   *
   * <p>{@code commits_at_open} alone, and the reason is a difference of <b>kind</b> rather than of
   * degree. A commit is the unit of "I addressed your comment": a reviewer asks for a change and the
   * author answers with a new commit, usually altering a few lines of a file the pull request
   * already touched. The size of the change barely moves; the commit count climbs. So the count
   * measures, in good part, how many rounds of review there were -- part of the very thing this
   * study predicts. Lines and files describe <i>what was proposed</i>; the commit count describes
   * <i>what happened after the proposal was examined</i>.
   *
   * <p>The argument was checked against the corpus and holds, but <b>the measurement stays out of
   * the manuscript by André's decision of 27/09/2026</b>: what goes into the thesis has to be
   * something he can explain and defend on his own, and this argument stands without a number. The
   * figures live in {@code MEDICAO-ESTABILIDADE.md} as an internal record of why the decision is
   * safe, not as material to cite.
   *
   * <p>Separately, and independent of the argument: outside a subpopulation 53,7% of pull requests
   * have no sha that resolves at all, so their commits cannot even be ordered against {@code t0}.
   * That subpopulation does not grow by extracting the preserved repositories -- 84,4% of the
   * population comes from a fork, and the missing commits were never in the upstream clone.
   */
  RECONSTRUIVEL_APENAS_NA_SUBPOPULACAO,

  /**
   * Observable only in the final, collected snapshot.
   *
   * <p>{@code pull_request_file} is GitHub's consolidated diff for the whole request: no timestamp,
   * and no base recorded. The head moves after {@code t0} in 43,9% of the resolvable cases and the
   * recorded base is the base at merge, so the stored figures are the end state and not the
   * submission.
   */
  OBSERVAVEL_APENAS_NO_SNAPSHOT_FINAL,

  /**
   * Not demonstrable without reconstructing the opening diff, which this corpus does not allow.
   *
   * <p>{@code area_hotness_90d} alone. Its history half -- how much the touched files moved in the
   * ninety days before {@code t0} -- was always available; what is missing is the list of touched
   * files at {@code t0}. The window was never the defect.
   */
  NAO_DEMONSTRAVEL_SEM_RECONSTRUIR_O_DIFF
}
