package app.features;

/**
 * How many issues were already open when this one arrived.
 *
 * <p>The individual component is scoped to the task's project, matching the scope rule the study
 * states for everything individual except the career-wide cost variable. The cross-project count is
 * carried beside it because the scope is the arguable part: a developer split across two projects
 * is carrying both, whatever the task at hand belongs to.
 */
public record Load(int developerInProject, int developerAcrossProjects, int team) {}
