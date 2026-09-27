package app.paper5;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The single extractor: every construct known before the merge, computed from the tables.
 *
 * <p>Pure, and it takes the whole table set rather than a query handle, so the same code runs
 * against the real corpus, against a fixture, and against a view with everything from {@code t0}
 * onwards deleted. That last one is the point -- see {@link Tables}.
 *
 * <p><b>Three rules run through all of it</b>, and every construct here obeys all three:
 *
 * <ul>
 *   <li>windows are half-open, {@code [start, t0)}: an event exactly at {@code t0} is not yet
 *       observable at {@code t0};
 *   <li>the row's own pull request is excluded <b>by identifier</b>, not only by the window. A
 *       comment written on it an hour before it opened is still about it;
 *   <li>a valid zero and an absence are different values, and neither is ever written as the other.
 * </ul>
 *
 * <p>Values come out raw. {@code log1p} and standardisation are modelling choices and belong to the
 * stage that builds the design matrix; storing the count means revisiting that choice costs a
 * re-fit rather than a re-mining.
 */
public final class Extractor {

  private static final long DAY = 86_400_000L;
  private static final long WINDOW_30 = 30L * DAY;

  private Extractor() {}

  /** The twenty-three constructs, every one of them, from one pass over one table set. */
  public static final List<String> PRODUCED = List.of(
      "created_weekday_utc", "created_hour_utc", "friday_effect",
      "project_age_days", "team_size_90d",
      "open_prs_project_at_open", "recent_open_prs_project_30d",
      "title_description_word_count", "issue_reference", "person_mention",
      "owner_prior_prs", "owner_prior_project_prs", "owner_merged_ratio",
      "requester_is_integrator", "social_connection_90d",
      "project_age_x_team_size", "team_size_x_workload",
      "additions_at_open", "deletions_at_open", "num_files_at_open",
      "test_inclusion_at_open", "commits_at_open", "area_hotness_90d");

  /**
   * Everything a row carries when it is stored: the constructs, and the indicators beside them.
   *
   * <p>Separate from {@link #PRODUCED} because the two answer different questions. A coverage
   * report is about constructs, and listing {@code owner_merged_ratio} next to
   * {@code owner_merged_ratio_ausente} would double-count it. Persistence is about everything the
   * matrix will need, and an indicator dropped on the way to disk arrives at the fit as a constant
   * zero -- which is exactly what happened the first time this list did not exist.
   */
  public static final List<String> STORED = java.util.stream.Stream.concat(
      PRODUCED.stream(),
      Attributes.all().stream()
          .filter(Attribute::absenceIndicator)
          .filter(attribute -> PRODUCED.contains(attribute.name()))
          .map(Attribute::absenceName))
      .toList();

  /** The six that read the final state of the change, listed once so a report can name them. */
  public static final List<String> TECHNICAL = List.of(
      "additions_at_open", "deletions_at_open", "num_files_at_open",
      "test_inclusion_at_open", "commits_at_open", "area_hotness_90d");

  public static Row of(final Unit unit, final Tables tables) {
    final long t0 = unit.openedAt().toEpochMilli();
    final String id = unit.pullRequestId();
    final String project = unit.project();
    final String creator = unit.creatorId();

    final Map<String, Double> values = new HashMap<>();
    final Set<String> absent = new HashSet<>();

    // -- momento da abertura --------------------------------------------------------------
    final var opened = Instant.ofEpochMilli(t0).atZone(ZoneOffset.UTC);
    // Sunday is 0, matching the UTC day-of-week the probes reported, so the categories in the
    // model and the categories in the validation tables are the same seven.
    final int weekday = opened.getDayOfWeek().getValue() % 7;
    values.put("created_weekday_utc", (double) weekday);
    values.put("created_hour_utc", (double) opened.getHour());
    values.put("friday_effect", weekday == 5 ? 1.0 : 0.0);

    // -- idade do projeto: desde min(primeiro commit, primeira issue), decisao da 1.5 -------
    final List<Tables.Origin> origins = Tables.before(tables.originOf(project), t0);
    final Double ageDays;
    if (origins.isEmpty()) {
      ageDays = null;
      absent.add("project_age_days");
    } else {
      long earliest = Long.MAX_VALUE;
      for (final Tables.Origin origin : origins) {
        earliest = Math.min(earliest, origin.at());
      }
      // Math.rint, not Math.round: the reference implementation rounds half to even, and a pull
      // request opened exactly twelve hours into a day would otherwise differ by one.
      ageDays = Math.rint((double) (t0 - earliest) / DAY);
      values.put("project_age_days", ageDays);
    }

    // -- equipe de integracao ativa --------------------------------------------------------
    final List<Tables.Merge> merges = tables.mergesOfProject(project);
    final Set<String> active = SocialConnection.activeIntegrators(merges, t0);
    final double teamSize = active.size();
    values.put("team_size_90d", teamSize);

    // -- carga do projeto: aberto antes de t0 e ainda nao fechado em t0 --------------------
    int open = 0;
    int openRecent = 0;
    for (final Tables.ProjectPr other : Tables.before(tables.prsOfProject(project), t0)) {
      if (other.pullRequestId().equals(id)) {
        continue;
      }
      final Long closed = tables.closedAt(other.pullRequestId());
      if (closed == null || closed >= t0) {
        open++;
        if (other.at() >= t0 - WINDOW_30) {
          openRecent++;
        }
      }
    }
    values.put("open_prs_project_at_open", (double) open);
    values.put("recent_open_prs_project_30d", (double) openRecent);

    // -- texto: plausivel em t0, nao demonstravel. Ver TemporalClass --------------------
    final Tables.Text text = tables.text(id);
    values.put("title_description_word_count", (double) text.words());
    // Decisao da 1.5: as duas convencoes contam. A de Yu, "#n", ocorre em 6,7% desta populacao;
    // a chave Jira, que e a que estes projetos usam, em 72,6%.
    values.put("issue_reference", text.hashReference() || text.jiraReference() ? 1.0 : 0.0);
    values.put("person_mention", text.mention() ? 1.0 : 0.0);

    // -- historico do criador, sempre por creator_id --------------------------------------
    int prior = 0;
    int priorHere = 0;
    int priorMerged = 0;
    for (final Tables.CreatorPr own : Tables.before(tables.prsOfCreator(creator), t0)) {
      if (own.pullRequestId().equals(id)) {
        continue;
      }
      prior++;
      if (own.project().equals(project)) {
        priorHere++;
      }
      // Merged BEFORE t0. One still open at t0 is not yet a success, however it ended later.
      final Long mergedAt = tables.firstMergeOf(own.pullRequestId());
      if (mergedAt != null && mergedAt < t0) {
        priorMerged++;
      }
    }
    values.put("owner_prior_prs", (double) prior);
    values.put("owner_prior_project_prs", (double) priorHere);
    if (prior == 0) {
      absent.add("owner_merged_ratio");
      values.put("owner_merged_ratio_ausente", 1.0);
    } else {
      values.put("owner_merged_ratio", (double) priorMerged / prior);
      values.put("owner_merged_ratio_ausente", 0.0);
    }

    boolean integrator = false;
    for (final Tables.Merge merge : Tables.before(merges, t0)) {
      if (creator.equals(merge.actor())) {
        integrator = true;
        break;
      }
    }
    values.put("requester_is_integrator", integrator ? 1.0 : 0.0);

    // -- conexao social --------------------------------------------------------------------
    final OptionalInt social = SocialConnection.of(id, creator, t0, merges, tables);
    if (social.isEmpty()) {
      absent.add("social_connection_90d");
      values.put("social_connection_90d_ausente", 1.0);
    } else {
      values.put("social_connection_90d", (double) social.getAsInt());
      values.put("social_connection_90d_ausente", 0.0);
    }

    // -- interacoes: produto das pernas CRUAS, e ausente onde uma perna e ausente ----------
    if (ageDays == null) {
      absent.add("project_age_x_team_size");
    } else {
      values.put("project_age_x_team_size", ageDays * teamSize);
    }
    values.put("team_size_x_workload", teamSize * open);

    technical(unit, tables, t0, values, absent);
    return new Row(id, values, absent);
  }

  /**
   * The six constructs that read the consolidated diff.
   *
   * <p>They are here, in the same pass, because the specification asks for one extractor -- not
   * because they carry the same claim. Every one of them belongs to the literature-compatible
   * profile alone: {@code pull_request_file} is the final state of the request, with no timestamp
   * and no base recorded, so none of these is demonstrably the value at {@code t0}. The names keep
   * the {@code _at_open} suffix the two papers use, and the temporal class on each attribute says
   * what that suffix does not.
   */
  private static void technical(final Unit unit, final Tables tables, final long t0,
      final Map<String, Double> values, final Set<String> absent) {

    final Tables.Diff diff = tables.diffOf(unit.pullRequestId());
    values.put("additions_at_open", (double) diff.additions());
    values.put("deletions_at_open", (double) diff.deletions());
    values.put("num_files_at_open", (double) diff.files());
    values.put("test_inclusion_at_open", diff.touchesTest() ? 1.0 : 0.0);
    values.put("commits_at_open", (double) diff.commits());

    // area_hotness_90d, on Yu's three-month window: the median, over the files the change touches,
    // of how many distinct commits touched that file in [t0-90d, t0), relative to everything the
    // project committed in the same window.
    //
    // The window was never the defect and the history half was always available. What is
    // retrospective is the SET OF FILES, which comes from the final diff -- so the contamination
    // enters through the set, not through the window.
    final long from = t0 - SocialConnection.WINDOW_MILLIS;
    final int projectCommits = tables.commitsOfProject(unit.project(), from, t0);
    final List<String> paths = tables.pathsOf(unit.pullRequestId());
    if (projectCommits == 0 || paths.isEmpty()) {
      // No denominator, or nothing to take a median over. Absent with an indicator -- never a
      // division by one, and never a zero standing in for an unobservable.
      absent.add("area_hotness_90d");
      values.put("area_hotness_90d_ausente", 1.0);
      return;
    }
    final double[] touching = new double[paths.size()];
    for (int i = 0; i < touching.length; i++) {
      touching[i] = tables.commitsTouching(unit.project(), paths.get(i), from, t0);
    }
    values.put("area_hotness_90d", median(touching) / projectCommits);
    values.put("area_hotness_90d_ausente", 0.0);
  }

  /** The middle value, or the mean of the two middle ones. Sorts a copy, never the caller's array. */
  private static double median(final double[] values) {
    final double[] sorted = values.clone();
    Arrays.sort(sorted);
    final int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle]
        : (sorted[middle - 1] + sorted[middle]) / 2.0;
  }
}
