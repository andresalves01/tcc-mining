package app.issues;

import app.issues.Universe.Population;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which rule draws a matrix's sides, and checks them: the random split ({@link ProjectSplit}),
 * the first-commit study's temporal split ({@link TemporalSplit}) as it was opened on 02/11/2026,
 * or that split's revised design ({@link #REVISED}). All draw the same shape of
 * sides ({@link ProjectSplit.Parts}) and check a matrix the same way, so a stage reads the rule a
 * run declares and nothing else changes.
 *
 * <p>The rule is the run setting {@link #SETTING}: unset, the random rule, so a run that does not
 * name one reads and checks exactly as before. The In Progress study's split is the random one
 * alone; another rule declared for its matrix is refused ({@link #declared}).
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
  },
  /**
   * The temporal split's revised design (the review of November 2026): the same population, cuts
   * and sides as {@link #TEMPORAL}, its crossing issues marked {@link Verdict#CROSSING} so they
   * teach nothing, and every history point-in-time. Until the crossing issues are marked it draws
   * and checks exactly as the temporal rule, with no crossing set.
   */
  REVISED {
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
      return TemporalSplit.REVISED_RULE;
    }
  };

  /**
   * The run setting that names the rule: {@code random} (the default), {@code temporal} or
   * {@code revised}.
   */
  public static final String SETTING = "SPLIT_RULE";

  /**
   * One issue the split is drawn over: its id, its project, the instant of its first commit that
   * counts, which only the temporal rules read, and the instant of its first resolution, which
   * only the revised rule reads, of the training side alone, to find the issues crossing a cut.
   *
   * @param firstResolution null when not given
   */
  public record Candidate(String id, String project, Instant firstCommit,
      Instant firstResolution) {

    /** A candidate without its first resolution, as every rule but the revised one draws. */
    public Candidate(final String id, final String project, final Instant firstCommit) {
      this(id, project, firstCommit, null);
    }
  }

  /** The sides of {@code candidates} under this rule, outer and inner. */
  public abstract ProjectSplit.Parts draw(Collection<Candidate> candidates);

  /**
   * Where the matrix's sides under {@code population} are not this rule's, drawn again over the
   * matrix's own issues; empty when every one is.
   */
  public abstract List<String> differences(List<RowCache.Entry> entries, Population population);

  /** The rule in words, for the help text and the run records. */
  public abstract String rule();

  /**
   * Whether this rule draws the sides by the first commit, at the cuts: the temporal design and
   * its revised one. Everything the temporal split decides beyond its sides -- the analogy
   * estimate as the fifteenth model, the forests' 500 trees, the run collections of its own,
   * the record's design field -- holds for both.
   */
  public boolean temporal() {
    return this == TEMPORAL || this == REVISED;
  }

  /** Whether every history reads only the labels known at each issue's first commit. */
  public boolean pointInTime() {
    return this == REVISED;
  }

  /** Whether the issues crossing a cut are left out of everything the training side teaches. */
  public boolean crossingOut() {
    return this == REVISED;
  }

  /**
   * The record field that names the split design a run was drawn under; only the runs of a design
   * other than the random one write it ({@link #recordedBy}).
   */
  public static final String DESIGN_FIELD = "split_design";

  /** The record field that gives the split's rule in words ({@link #rule}). */
  public static final String RULE_FIELD = "split_rule";

  /**
   * The suffix of the collections the runs of this design are recorded in: none for the random
   * rule, so its runs keep the collections they were always recorded in; {@code _temporal} for the
   * temporal one and {@code _revised} for the revised one, so no design's runs land among
   * another's.
   */
  public String collectionSuffix() {
    return this == RANDOM ? "" : "_" + setting();
  }

  /**
   * The design a recorded run was drawn under: the rule its {@link #DESIGN_FIELD} names; without
   * one, the rule whose words its {@link #RULE_FIELD} gives, when that is the temporal or the
   * revised rule's, otherwise the random rule, as every run recorded before the temporal design. A
   * design no rule names is refused.
   */
  public static SplitRule recordedBy(final Map<String, ?> run) {
    final Object design = run.get(DESIGN_FIELD);
    if (design != null) {
      return of(design.toString());
    }
    final Object words = run.get(RULE_FIELD);
    for (final SplitRule r : List.of(TEMPORAL, REVISED)) {
      if (r.rule().equals(words)) {
        return r;
      }
    }
    return RANDOM;
  }

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
    throw new IllegalArgumentException(SETTING + " is random, temporal or revised, not '" + value
        + "'");
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
