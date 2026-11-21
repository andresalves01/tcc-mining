package app.issues;

import app.issues.Universe.Population;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rule that draws a matrix's sides, and checks them: the first-commit study's temporal split
 * in its revised design (the review of November 2026). The candidates are ordered by their first
 * commit and cut at the same instants as the temporal split opened on 02/11/2026; a training
 * issue whose first resolution comes after its level's cut crosses it, is marked
 * {@link Verdict#CROSSING} and teaches nothing there ({@link TemporalSplit#drawRevised}), and
 * every history is point-in-time. It is the only rule the app draws.
 */
public enum SplitRule {
  REVISED {
    @Override
    public ProjectSplit.Parts draw(final Collection<Candidate> candidates) {
      return TemporalSplit.drawRevised(candidates);
    }

    @Override
    public List<String> differences(final List<RowCache.Entry> entries,
        final Population population) {
      return TemporalSplit.differencesRevised(entries, population);
    }

    @Override
    public String rule() {
      return TemporalSplit.REVISED_RULE;
    }
  };

  /**
   * One issue the split is drawn over: its id, its project, the instant of its first commit that
   * counts, and the instant of its first resolution, which the rule reads of the training side
   * alone, to find the issues crossing a cut.
   *
   * @param firstResolution null when not given
   */
  public record Candidate(String id, String project, Instant firstCommit,
      Instant firstResolution) {

    /** A candidate without its first resolution. */
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

  /** The record field that names the split design a run was drawn under. */
  public static final String DESIGN_FIELD = "split_design";

  /** The record field that gives the split's rule in words ({@link #rule}). */
  public static final String RULE_FIELD = "split_rule";

  /**
   * The suffix of the collections the runs are recorded in, {@code _revised}, as the runs of the
   * revised design always were.
   */
  public String collectionSuffix() {
    return "_" + setting();
  }

  /**
   * Whether a recorded run was drawn under this design: its {@link #DESIGN_FIELD} names it, or,
   * without one, its {@link #RULE_FIELD} gives this rule's words.
   */
  public static boolean recordedBy(final Map<String, ?> run) {
    final Object design = run.get(DESIGN_FIELD);
    return design != null ? REVISED.setting().equals(design.toString())
        : REVISED.rule().equals(run.get(RULE_FIELD));
  }

  /** The setting's name in lower case, as a run writes it. */
  public String setting() {
    return name().toLowerCase(Locale.ROOT);
  }
}
