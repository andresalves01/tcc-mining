package app.paper6;

/**
 * Which specification a construct belongs to. Six, and every one is reported.
 *
 * <p>The two unions are <b>resolved</b>: a construct two specifications share enters once, and where
 * one specification's column measures what another's already measures, the union keeps one of them
 * and {@link Attributes} says which and why, next to the declaration.
 */
public enum Family {

  /** Yu et al. (2015), transposed: the constructs with an issue analogue, redefinitions declared. */
  YU_ISSUE,

  /** Chouchen et al., transposed the same way. */
  CHOUCHEN_ISSUE,

  /**
   * The author's own issue specification, the 24 columns of {@code smartshark_tcc.issue_features}.
   *
   * <p>Read verbatim except for its three creation columns -- description length, linked issues,
   * issue type -- which the stored table holds at their final state. Those three are taken at
   * {@code t0} from the changelog like every other field of the issue, and the size of the
   * difference is measured and printed rather than argued.
   */
  ESPEC_PROPRIA,

  /** Yu and Chouchen together, each shared construct once. */
  COMBINADO_LITERATURA,

  /**
   * What an issue tracker records at triage and a pull request has no equivalent of: priority,
   * type, components, labels, versions, links, whether someone is assigned, how long the
   * description is. All of it at {@code t0}, from the changelog. The contribution of the
   * transposition, since neither replicated paper has a place for these.
   */
  TRIAGEM_JIRA,

  /** Everything, resolved: the literature, the author's specification and the triage fields. */
  COMBINADO_TOTAL
}
