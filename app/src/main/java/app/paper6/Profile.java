package app.paper6;

import java.util.Set;

/**
 * How much temporal evidence a run stands on. Four rungs, nested, each the previous plus one class.
 *
 * <p>The nesting is what makes the table readable: the difference between two adjacent rungs is
 * exactly what the added class contributes -- reconstructing the text and the triage fields, then
 * knowing who will do the work, then knowing the work. None of the four is the winner; they answer
 * four questions.
 *
 * <p>A predicate over {@link TemporalClass}, never a second list of names, so a construct's profile
 * membership cannot disagree with the temporal claim written beside it.
 */
public enum Profile {

  /** Only dated evidence: what someone standing at creation, with nothing that came later, has. */
  STRICT_T0(Set.of(TemporalClass.LIMPO)),

  /**
   * Plus the issue's own fields as they were at creation, recovered from the changelog.
   *
   * <p><b>The frozen principal reading</b>: the widest one that is still ex ante.
   */
  T0_RECONSTRUIDO(Set.of(TemporalClass.LIMPO, TemporalClass.RECONSTRUIDO_DO_CHANGELOG)),

  /** Plus the history of the person who did the work. Not ex ante, and named so it cannot pass. */
  ATRIBUICAO_DADA(Set.of(TemporalClass.LIMPO, TemporalClass.RECONSTRUIDO_DO_CHANGELOG,
      TemporalClass.CONDICIONADO_A_ATRIBUICAO)),

  /** Plus the change that resolved the issue. The ceiling, post hoc by construction. */
  DESFECHO_CONHECIDO(Set.of(TemporalClass.values()));

  private final Set<TemporalClass> admitted;

  Profile(final Set<TemporalClass> admitted) {
    this.admitted = Set.copyOf(admitted);
  }

  public boolean admits(final TemporalClass temporalClass) {
    return admitted.contains(temporalClass);
  }

  /** True for the two rungs that describe the moment of creation and nothing later. */
  public boolean exAnte() {
    return this == STRICT_T0 || this == T0_RECONSTRUIDO;
  }
}
