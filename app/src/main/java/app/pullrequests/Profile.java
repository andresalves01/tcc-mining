package app.pullrequests;

import java.util.Set;

/**
 * How much temporal evidence a run is willing to stand on.
 *
 * <p>Four nested profiles, and the nesting is the point: each one is the previous plus a named
 * group, so the difference between two results is attributable to exactly the constructs that were
 * added. They answer different questions and none of them is the winner -- reporting only the best
 * would be choosing a claim by the score it produced.
 *
 * <p><b>The second rung was added on 27/09/2026</b>, by André's decision and after the measurement
 * in {@code MEDICAO-ESTABILIDADE.md}. The order is from firmer evidence to weaker: a change's size
 * is a proxy with a measured quality of 0,85, while a title and a body are editable and the corpus
 * keeps no history of the edits, so the text is the weaker of the two additions and comes after it.
 *
 * <p>One consequence to state rather than hide: under strict nesting the old middle rung -- clean
 * plus text, with no size -- is not one of these four. Its numbers are not lost, they are in
 * {@code implementacao/33-} and {@code 34-}, but they are not recomputed here.
 *
 * <p>A profile is a <b>predicate over {@link TemporalClass}</b>, not a second list of column names.
 * Keeping it that way means a construct's profile membership can never disagree with the temporal
 * claim recorded beside it, because there is only one place the claim is written.
 */
public enum Profile {

  /**
   * Only what the corpus demonstrates was known at {@code t0}.
   *
   * <p>The operational question: what can be predicted at the instant a pull request opens, by
   * someone standing there, with nothing that came later.
   */
  STRICT_T0(Set.of(TemporalClass.LIMPO)),

  /**
   * The above plus the size of the change, whose final value is a proxy of measured quality.
   *
   * <p>The difference against {@link #STRICT_T0} is what lines, files and the constructs derived
   * from the file list contribute. Both stability classes enter here, the measured one and the
   * inherited one, so a report on this profile has to name which constructs carry a number and which
   * carry an argument -- {@link TemporalClass#ESTAVEL_HERDADO_DA_LISTA_DE_ARQUIVOS} says why.
   */
  TAMANHO_ESTAVEL(Set.of(TemporalClass.LIMPO,
      TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE,
      TemporalClass.ESTAVEL_HERDADO_DA_LISTA_DE_ARQUIVOS)),

  /**
   * The above plus the textual constructs, whose invariance is plausible and unproven.
   *
   * <p>The difference against {@link #TAMANHO_ESTAVEL} is the approximate contribution of the text,
   * now measured on top of the size rather than instead of it.
   */
  T0_TEXT_PROXY(Set.of(TemporalClass.LIMPO,
      TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE,
      TemporalClass.ESTAVEL_HERDADO_DA_LISTA_DE_ARQUIVOS,
      TemporalClass.NAO_VERIFICAVEL_EM_T0)),

  /**
   * Everything, which now means one construct more than the profile below: the commit count.
   *
   * <p>The ceiling the literature's own approach reaches on this corpus. What separates it from
   * {@link #T0_TEXT_PROXY} is {@code commits_at_open} alone, whose proxy quality is 0,686 against
   * 0,85 for the other two sizes -- so this profile is where the weakest substitution lives, and the
   * gap between the two profiles is that substitution's whole contribution.
   */
  LITERATURE_COMPATIBLE(Set.of(TemporalClass.values()));

  private final Set<TemporalClass> admitted;

  Profile(final Set<TemporalClass> admitted) {
    this.admitted = Set.copyOf(admitted);
  }

  public boolean admits(final TemporalClass temporalClass) {
    return admitted.contains(temporalClass);
  }

  /** The classes this profile lets in, for a report that has to name them. */
  public Set<TemporalClass> admitted() {
    return admitted;
  }
}
