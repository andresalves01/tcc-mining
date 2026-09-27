package app.paper5;

/**
 * Which specification a construct belongs to.
 *
 * <p>A construct can belong to more than one: {@code additions_at_open} is Yu's and Chouchen's
 * alike, and both of them are in the combined family. Membership is a set on the attribute rather
 * than three separate lists, so a construct is declared once and cannot drift between them.
 */
public enum Family {

  /** Yu et al. (2015), seventeen attributes, restricted to what is known before the merge. */
  YU_PRE_COMPAT,

  /**
   * Ten cheap attributes standing in for the recurring dimensions of Chouchen et al.
   *
   * <p>An adaptation, not a reproduction: Chouchen's own light model takes the ten most important
   * variables per project and period, and those ten move. This set is fixed in advance instead, so
   * that the mining cost it claims is the cost it actually has.
   */
  CHOUCHEN_CORE_10,

  /**
   * The three predictors of this study's own earlier paper, on their own.
   *
   * <p><b>Added on 27/09/2026, after the per-column measurement</b> that found these three carry the
   * highest isolated rank correlation with duration of any column in the grid -- 0,334, 0,311 and
   * 0,306 against 0,217 for the best of the literature's own. That measurement exposed a gap in the
   * design rather than a result: Yu and Chouchen each had a number of their own, and this study's
   * contribution had never been fitted except inside a bag of twenty-five, where the penalty spread
   * weight across half the columns. Being added after seeing a number, it is a <b>post hoc
   * family</b>, and every report says so; what it must never do is enter a frozen specification
   * without having been declared before the reserved side is opened.
   *
   * <p><b>Its three profiles are identical by construction</b>, and that is the point rather than a
   * defect: all three constructs are clean in {@code t0}, so none of them is ever excluded by a
   * profile. It means this family competes in the <b>strict</b> profile -- against Yu's 0,152 and
   * Chouchen's 0,130 -- and not against their literature readings, which buy their ordering with
   * constructs the corpus cannot show were available at opening.
   *
   * <p>One of the three, the project's own past median, is very nearly the mandatory reference
   * itself. A family of three whose strongest member rewrites the comparator has to be read with
   * that in mind, and the report states it beside the number.
   */
  CONTRIBUICAO_PROPRIA,

  /**
   * The union of the two replicated papers, without repeating a construct: twenty attributes and two
   * interactions.
   *
   * <p>{@code friday_effect} is the one member of Yu's family that does not appear here, because
   * {@code created_weekday_utc} already contains it.
   */
  COMBINED_PRE,

  /**
   * The combined family plus the three predictors the previous paper found carried the most.
   *
   * <p>A separate family and not an edit to {@link #COMBINED_PRE}, because that one is a
   * replication: a replication whose attribute set has grown is not one any more. This family asks a
   * different question -- whether what the developer-ordering study found predictive adds anything
   * on top of the literature's own constructs -- and it is reported beside the three, never instead
   * of them.
   *
   * <p>The three are past median durations: of that creator in that project, of that creator
   * anywhere, and of that project. See {@code app.paper5.model.History} for the two rules they
   * obey.
   */
  COMBINADO_MAIS_ARTIGO4
}
