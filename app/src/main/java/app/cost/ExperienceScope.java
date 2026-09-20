package app.cost;

/**
 * Over what history a developer's experience is accumulated.
 *
 * <p>Both exist because the study needs both, for different purposes.
 *
 * <p>{@link #PROJECT} is the right scope for {@code H_j}, the regression predictor: the mechanism
 * under test is familiarity with the codebase being changed, and work in a different repository is
 * not that.
 *
 * <p>{@link #CAREER} is the right scope for {@code q}, the cost input: cost follows the person, and
 * a senior developer rotating onto a new project does not become cheap. Measured on the 1,091
 * developers of the labelled sample, the distinction is not academic — 31.0% of them commit to more
 * than one project and they carry 44.6% of the labels, and for the median (project, developer) pair
 * 35.7% of that person's corpus activity lies outside the project in question.
 *
 * <p>"Career" means activity inside these 98 Apache repositories. Experience earned elsewhere is
 * invisible under either scope; this is the less partial of the two views, not a complete one.
 */
public enum ExperienceScope {

  /** Accumulate only within the project the issue belongs to. */
  PROJECT,

  /** Accumulate across the whole corpus, from the developer's first commit anywhere. */
  CAREER
}
