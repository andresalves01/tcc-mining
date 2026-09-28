package app.paper6;

import app.paper5.Row;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single extractor: every construct of every profile, for one issue, in one pass.
 *
 * <p>Pure. It takes the tables, the issue and the author's own stored columns, and computes -- so the
 * same code runs on the corpus, on a hand-built fixture, and on the corpus truncated at {@code t0}.
 *
 * <p>The three rules of the pull-request study hold here unchanged: windows are half-open,
 * {@code [start, t0)}; the row's own issue is excluded <b>by identifier</b>, not only by the window;
 * and a valid zero and an absence are different values, never written as each other.
 */
public final class Extractor {

  static final long DAY = 86_400_000L;
  static final long WINDOW_90 = 90L * DAY;
  static final long WINDOW_30 = 30L * DAY;

  /** Yu's "#n", kept though Jira projects rarely use it; the pull-request study counted both too. */
  private static final Pattern HASH_REFERENCE = Pattern.compile("#\\d+");

  /** A Jira key. Matched in full so the issue's own key can be told apart and not counted. */
  private static final Pattern JIRA_REFERENCE = Pattern.compile("\\b[A-Z][A-Z0-9]{1,}-\\d+\\b");

  /**
   * A mention. {@code @name} is the convention Yu counted; {@code [~name]} is the one Jira renders,
   * and on an issue tracker leaving it out would count almost nothing.
   */
  private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9_-]+|\\[~[^\\]\\s]+\\]");

  /** JavaScript's whitespace class, spelled out -- the pull-request study's word rule, reused. */
  private static final Pattern WHITESPACE = Pattern.compile(
      "[\t\n\u000B\f\r    -     　﻿]+");

  /** Priority names to codes. Anything else, including no priority at all, is its own level. */
  static final Map<String, Double> PRIORITY = Map.of(
      "Blocker", 1.0, "Critical", 2.0, "Major", 3.0, "Minor", 4.0, "Trivial", 5.0);

  /**
   * Types to codes, in the order of the five indicators the author's own specification declared
   * ({@code app.model.Columns.TYPES}), with every other type as the reference level -- which is
   * exactly how that specification encoded it.
   */
  static final Map<String, Double> TYPE = Map.of(
      "Bug", 1.0, "Improvement", 2.0, "New Feature", 3.0, "Sub-task", 4.0, "Task", 5.0);

  /** The author's stored columns this reads verbatim. The other three are replayed instead. */
  public static final List<String> OWN_STORED = List.of(
      "mu_l", "s_l", "mu_m", "s_m", "extra_team_size",
      "loc_tot_java", "extra_dev_commits", "extra_dev_tenure_days", "mccc_tot",
      "iss_dev", "extra_iss_dev_career", "iss_team",
      "loc_iss", "mccc_i", "nl_i");

  private Extractor() {}

  /** The code a type name gets, shared with the reader of the stored column so the two agree. */
  public static double typeCode(final String type) {
    return type == null ? 0.0 : TYPE.getOrDefault(type, 0.0);
  }

  /** No priority at all is its own level, the same as a priority outside the five. */
  static double priorityCode(final String priority) {
    return priority == null ? 0.0 : PRIORITY.getOrDefault(priority, 0.0);
  }

  /**
   * @param own the author's stored columns for this issue, from {@code issue_features}; a column
   *     missing there is absent here, never zero
   */
  public static Row of(final IssueUnit unit, final Tables tables, final Map<String, Double> own) {
    final long t0 = unit.createdAt().toEpochMilli();
    final String id = unit.issueId();
    final String project = unit.project();
    final String reporter = unit.reporterId();
    final Map<String, Double> values = new HashMap<>();
    final Set<String> absent = new HashSet<>();

    // -- momento ------------------------------------------------------------------------------
    final var created = Instant.ofEpochMilli(t0).atZone(ZoneOffset.UTC);
    final int weekday = created.getDayOfWeek().getValue() % 7;
    values.put("created_weekday_utc", (double) weekday);
    values.put("created_hour_utc", (double) created.getHour());
    values.put("friday_effect", weekday == 5 ? 1.0 : 0.0);

    // -- idade do projeto --------------------------------------------------------------------
    final List<Tables.Origin> origins = Tables.before(tables.originOf(project), t0);
    Double age = null;
    if (origins.isEmpty()) {
      absent.add("project_age_days");
    } else {
      long earliest = Long.MAX_VALUE;
      for (final Tables.Origin origin : origins) {
        earliest = Math.min(earliest, origin.at());
      }
      age = (double) Math.rint((double) (t0 - earliest) / DAY);
      values.put("project_age_days", age);
    }

    // -- quem resolveu no projeto nos 90 dias ------------------------------------------------
    final Set<String> active = activeResolvers(tables.resolutionsOfProject(project), t0);
    final double team = active.size();
    values.put("team_size_90d", team);

    // -- carga do projeto --------------------------------------------------------------------
    int open = 0;
    int openRecent = 0;
    for (final Tables.ProjectIssue other : Tables.before(tables.issuesOfProject(project), t0)) {
      if (other.issueId().equals(id)) {
        continue;
      }
      final Tables.Resolution resolved = tables.resolutionOf(other.issueId());
      if (resolved == null || resolved.at() >= t0) {
        open++;
        if (other.at() >= t0 - WINDOW_30) {
          openRecent++;
        }
      }
    }
    values.put("open_issues_project_at_open", (double) open);
    values.put("recent_open_issues_project_30d", (double) openRecent);

    // -- o relator --------------------------------------------------------------------------
    int prior = 0;
    int priorHere = 0;
    int priorResolved = 0;
    for (final Tables.ReporterIssue own0 : Tables.before(tables.issuesOfReporter(reporter), t0)) {
      if (own0.issueId().equals(id)) {
        continue;
      }
      prior++;
      if (own0.project().equals(project)) {
        priorHere++;
      }
      // Resolved BEFORE t0. One still open at t0 is not yet a success, however it ended later.
      final Tables.Resolution resolved = tables.resolutionOf(own0.issueId());
      if (resolved != null && resolved.at() < t0) {
        priorResolved++;
      }
    }
    values.put("reporter_prior_issues", (double) prior);
    values.put("reporter_prior_project_issues", (double) priorHere);
    if (prior == 0) {
      absent.add("reporter_resolved_ratio");
      values.put("reporter_resolved_ratio_ausente", 1.0);
    } else {
      values.put("reporter_resolved_ratio", (double) priorResolved / prior);
      values.put("reporter_resolved_ratio_ausente", 0.0);
    }

    boolean resolver = false;
    for (final Tables.Resolution resolution
        : Tables.before(tables.resolutionsOfProject(project), t0)) {
      if (reporter.equals(resolution.resolver())) {
        resolver = true;
        break;
      }
    }
    values.put("reporter_is_resolver", resolver ? 1.0 : 0.0);

    // -- conexao social ----------------------------------------------------------------------
    if (active.isEmpty()) {
      absent.add("social_connection_90d");
      values.put("social_connection_90d_ausente", 1.0);
    } else {
      values.put("social_connection_90d", (double) contacts(id, reporter, t0, active, tables));
      values.put("social_connection_90d_ausente", 0.0);
    }

    // -- interacoes: produto das pernas cruas ------------------------------------------------
    if (age == null) {
      absent.add("project_age_x_team_size");
    } else {
      values.put("project_age_x_team_size", age * team);
    }
    values.put("team_size_x_workload", team * open);

    // -- a issue como estava na criacao ------------------------------------------------------
    atOpen(unit, tables, t0, values, absent);

    // -- a especificacao propria, lida como esta gravada -------------------------------------
    for (final String column : OWN_STORED) {
      final Double value = own == null ? null : own.get(column);
      if (value == null) {
        absent.add(column);
      } else {
        values.put(column, value);
      }
    }
    // Equipe vazia: as quatro estatisticas somem juntas, e um indicador so diz isso.
    values.put("mu_l_ausente", absent.contains("mu_l") ? 1.0 : 0.0);

    // -- a mudanca que resolveu: so o teto a le ----------------------------------------------
    values.put("additions_linked", (double) unit.javaLinesAdded());
    values.put("deletions_linked", (double) unit.javaLinesDeleted());
    values.put("commits_linked", (double) unit.commits());

    return new Row(id, values, absent);
  }

  private static void atOpen(final IssueUnit unit, final Tables tables, final long t0,
      final Map<String, Double> values, final Set<String> absent) {

    final Tables.Snapshot snapshot = tables.snapshotOf(unit.issueId());
    if (snapshot == null) {
      throw new IllegalStateException(unit.issueId() + " has no snapshot: the population and the "
          + "source disagree, and extracting around it would hide which one is wrong");
    }
    final Replay.AtOpen open = Replay.of(snapshot, tables.changesOf(unit.issueId()), t0);

    final String title = open.title() == null ? "" : open.title();
    final String body = open.description() == null ? "" : open.description();
    final String whole = title + " " + body;
    int words = 0;
    for (final String word : WHITESPACE.split(whole)) {
      if (!word.isEmpty()) {
        words++;
      }
    }
    values.put("title_description_word_count", (double) words);
    values.put("issue_reference",
        HASH_REFERENCE.matcher(whole).find() || referencesAnother(whole, unit.key()) ? 1.0 : 0.0);
    values.put("person_mention", MENTION.matcher(whole).find() ? 1.0 : 0.0);

    values.put("priority_at_open", priorityCode(open.priority()));
    values.put("issue_type_at_open", typeCode(open.issueType()));
    values.put("components_at_open", (double) open.components());
    values.put("labels_at_open", (double) open.labels());
    values.put("affects_versions_at_open", (double) open.affectsVersions());
    values.put("fix_versions_at_open", (double) open.fixVersions());
    values.put("has_assignee_at_open", open.hasAssignee() ? 1.0 : 0.0);
    if (open.linkCount() < 0) {
      absent.add("linked_issues_at_open");
      values.put("linked_issues_at_open_ausente", 1.0);
    } else {
      values.put("linked_issues_at_open", (double) open.linkCount());
      values.put("linked_issues_at_open_ausente", 0.0);
    }
    if (open.description() == null) {
      absent.add("description_length_at_open");
      values.put("description_length_at_open_ausente", 1.0);
    } else {
      values.put("description_length_at_open", (double) open.description().length());
      values.put("description_length_at_open_ausente", 0.0);
    }
  }

  private static boolean referencesAnother(final String text, final String ownKey) {
    final Matcher matcher = JIRA_REFERENCE.matcher(text);
    while (matcher.find()) {
      if (!matcher.group().equals(ownKey)) {
        return true;
      }
    }
    return false;
  }

  /** The distinct people who resolved an issue of this project during {@code [t0 - 90d, t0)}. */
  static Set<String> activeResolvers(final List<Tables.Resolution> resolutions, final long t0) {
    final Set<String> active = new HashSet<>();
    for (final Tables.Resolution resolution : Tables.between(resolutions, t0 - WINDOW_90, t0)) {
      if (resolution.resolver() != null && !resolution.resolver().isEmpty()) {
        active.add(resolution.resolver());
      }
    }
    return active;
  }

  /**
   * Contacts between the reporter and the project's active resolvers in the window.
   *
   * <p>The pull-request study's two ends, transposed: an active resolver commented on an issue this
   * reporter opened, or this reporter commented on an issue an active resolver opened. The issue
   * being predicted is excluded by identifier. The same person, instant and issue is one contact
   * however many rows recorded it, and the two ends are counted separately.
   */
  static int contacts(final String issueId, final String reporter, final long t0,
      final Set<String> active, final Tables tables) {

    final long from = t0 - WINDOW_90;
    int count = 0;
    final Set<String> seen = new HashSet<>();
    for (final Tables.Contact contact : Tables.between(tables.contactsOnIssuesOf(reporter), from,
        t0)) {
      if (!contact.issueId().equals(issueId) && active.contains(contact.other())
          && seen.add(contact.at() + "\u0000" + contact.other() + "\u0000" + contact.issueId())) {
        count++;
      }
    }
    final Set<String> seenBack = new HashSet<>();
    for (final Tables.Contact contact : Tables.between(tables.contactsWrittenBy(reporter), from,
        t0)) {
      if (!contact.issueId().equals(issueId) && active.contains(contact.other())
          && seenBack.add(contact.at() + "\u0000" + contact.other() + "\u0000"
              + contact.issueId())) {
        count++;
      }
    }
    return count;
  }
}
