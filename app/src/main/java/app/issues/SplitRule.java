package app.issues;

import app.issues.Universe.Population;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which rule draws a matrix's sides, and checks them: the random split ({@link ProjectSplit}) or
 * the first-commit study's temporal split ({@link TemporalSplit}). Both draw the same shape of
 * sides ({@link ProjectSplit.Parts}) and check a matrix the same way, so a stage reads the rule a
 * run declares and nothing else changes.
 *
 * <p>The rule is the run setting {@link #SETTING}: unset, the random rule, so a run that does not
 * name one reads and checks exactly as before. The In Progress study's split is the random one
 * alone; a temporal rule declared for its matrix is refused ({@link #declared}).
 */
public enum SplitRule {
  RANDOM {
    @Override
    public ProjectSplit.Parts draw(final Collection<Candidate> candidates) {
      final Map<String, String> projects = new HashMap<>(candidates.size() * 2);
      candidates.forEach(c -> projects.put(c.id(), c.project()));
      return ProjectSplit.draw(projects);
    }

    @Override
    public List<String> differences(final List<RowCache.Entry> entries,
        final Population population) {
      return ProjectSplit.differences(entries, population);
    }

    @Override
    public String rule() {
      return ProjectSplit.RULE;
    }
  },
  TEMPORAL {
    @Override
    public ProjectSplit.Parts draw(final Collection<Candidate> candidates) {
      return TemporalSplit.draw(candidates);
    }

    @Override
    public List<String> differences(final List<RowCache.Entry> entries,
        final Population population) {
      return TemporalSplit.differences(entries, population);
    }

    @Override
    public String rule() {
      return TemporalSplit.RULE;
    }
  };

  /** The run setting that names the rule: {@code random} (the default) or {@code temporal}. */
  public static final String SETTING = "SPLIT_RULE";

  /**
   * One issue the split is drawn over: its id, its project, and the instant of its first commit
   * that counts, which only the temporal rule reads.
   */
  public record Candidate(String id, String project, Instant firstCommit) {}

  /** The sides of {@code candidates} under this rule, outer and inner. */
  public abstract ProjectSplit.Parts draw(Collection<Candidate> candidates);

  /**
   * Where the matrix's sides under {@code population} are not this rule's, drawn again over the
   * matrix's own issues; empty when every one is.
   */
  public abstract List<String> differences(List<RowCache.Entry> entries, Population population);

  /** The rule in words, for the help text and the run records. */
  public abstract String rule();

  /** The setting's name in lower case, as a run writes it. */
  public String setting() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The rule a setting names; unset or blank is {@link #RANDOM}. */
  public static SplitRule of(final String value) {
    if (value == null || value.isBlank()) {
      return RANDOM;
    }
    for (final SplitRule r : values()) {
      if (r.name().equalsIgnoreCase(value.strip())) {
        return r;
      }
    }
    throw new IllegalArgumentException(SETTING + " is random or temporal, not '" + value + "'");
  }

  /**
   * The rule a setting declares for a matrix of {@code population}: the In Progress study's split
   * is the random one alone, so another rule declared for its matrix is refused.
   */
  public static SplitRule declared(final String value, final Population population) {
    final SplitRule rule = of(value);
    if (population == Population.IN_PROGRESS && rule != RANDOM) {
      throw new IllegalArgumentException(SETTING + "=" + rule.setting() + " was declared for a "
          + "matrix of the In Progress study, whose split is the random one alone");
    }
    return rule;
  }
}
