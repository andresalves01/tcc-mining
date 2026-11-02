package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.ElasticNetSelection.Combination;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.Forest;
import app.issues.model.ForestTrials;
import app.issues.model.ParallelSweepTest;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The study's Elastic Net models, each one's alpha and lambda and,
 * for the three reading the similarity medians, their n, n and k, chosen on the inner validation.
 */
class ElasticNetSelectionTest {

  private static final Combination TEN_FIVE_32 = new Combination(10, 5, 32);

  /**
   * The three start pressure columns (the fix version set before the start, the days left in the
   * release cycle and the pace of the project's other Blocker issues), in their order, the
   * Blocker pace's inner variant: in the fixture's matrix, read by no model.
   */
  private static final List<String> START = List.of("START_fix_version",
      "START_release_cycle_days", "START_blocker_pace_days_inner");

  /** The own start model. */
  private static final String OWN = "own_with_similarity_before_pressure";

  /** The six attribute sets, in their order. */
  private static final List<String> SIX_MODELS = List.of(OWN, "alzubaidi_start", "kikas_start",
      "own_retrospective", "own_retrospective_with_discussion", "kikas_retrospective");

  /** The models without the similarity medians, the registered cells. */
  private static final List<String> WITHOUT = List.of("alzubaidi_start", "kikas_start",
      "kikas_retrospective");

  /** The models with the similarity medians. */
  private static final List<String> WITH = List.of(OWN, "own_retrospective",
      "own_retrospective_with_discussion");

  /** The models the scope of fourteen leaves out: no name of theirs is a model now. */
  private static final List<String> LEFT_OUT = List.of("own_without_similarity",
      "similarity_alone", "own_with_similarity", "union_own",
      "own_retrospective_before_pressure");

  /**
   * The fixture's retrospective columns: ticket 15's eight, then ticket 19's two, the wait on
   * blocking issues among them, which the matrix of an earlier run holds and no model reads.
   */
  private static final List<String> RETROSPECTIVE = List.of("RETRO_java_lines",
      "RETRO_mean_mccc", "RETRO_mean_nesting", "RETRO_files", "RETRO_familiarity",
      "RETRO_comments", "RETRO_participants", "RETRO_assignee_changes", "RETRO_blocker_wait",
      "RETRO_urgency_days");

  /**
   * The retrospective columns of the main reading, in their order: the five of the content, the
   * assignee changes. Release lag remains in the matrix but enters no own model.
   */
  private static final List<String> MAIN = List.of("RETRO_java_lines", "RETRO_mean_mccc",
      "RETRO_mean_nesting", "RETRO_files", "RETRO_familiarity", "RETRO_assignee_changes");

  /** The comments and the participants: what the ceiling adds to the main reading. */
  private static final List<String> DISCUSSION = List.of("RETRO_comments", "RETRO_participants");

  /**
   * The own start model, the own specification with similarity without the three pressure
   * columns: the ten of ticket 12, then the three medians.
   */
  private static final List<String> OWN_START = List.of("A01_desc_length", "A03_team_size",
      "TEAM_loc_median", "TEAM_loc_sd", "TEAM_mccc_median", "TEAM_mccc_sd",
      "reporter_median_inner", "A09_exec_loc_tot", "A10_exec_mccc_tot", "A13_exec_open_load",
      "jaccard_median_10_inner", "embedding_median_5_inner", "cluster_median_32_inner");

  /**
   * Al-Zubaidi and Kikas at the start as the code chose them on {@link #full}: the chosen
   * combination, columns, alpha, lambda and error as raw bits, the sweep's step, a hash of every
   * prediction's bits, and a hash of every combination's sweep as bits. First captured from
   * commit 2059600 on the hours; regenerated once by ticket 17, when the fixture moved onto
   * business days through the population, by the code of 11d6710 (main after ticket 15); the
   * similarity medians alone left out with the fourteen models.
   */
  private static final List<String> BEFORE = List.of(
      "alzubaidi_start null [Z08_priority] 3fee666666666666 3f027ac90ffbb6b3 11 "
          + "400b9501a993aa38 aaa13482 b62",
      "kikas_start null [K14_project_issues_90d] 3fee666666666666 3f57e2f83a2b3163 9 "
          + "400b86b507b027a6 fcb8b148 b62");

  /**
   * The own start model as the code chose it on {@link #full}: the own specification with
   * similarity of 0869f6d, before ticket 13 and before the pressure columns were added to it, on
   * the hours, regenerated once by ticket 17 with {@link #BEFORE}; its label is the own start
   * model's.
   */
  private static final String OWN_BEFORE_FORESTS = OWN + " Combination[jaccardN=5, embeddingN=5, "
      + "clusterK=32] [A01_desc_length, A03_team_size, TEAM_loc_median, TEAM_loc_sd, "
      + "TEAM_mccc_median, TEAM_mccc_sd, reporter_median_inner, reporter_median_inner_ausente, "
      + "A09_exec_loc_tot, A10_exec_mccc_tot, A13_exec_open_load, jaccard_median_5_inner, "
      + "embedding_median_5_inner, cluster_median_32_inner] 3fee666666666666 3f720868e0e6ede1 "
      + "11 3ff5684caf8bf0d7 2828f000 719940f1";

  /** The three start models, in their order then: Al-Zubaidi, Kikas, the own start model. */
  private static final List<String> START_MODELS = List.of("alzubaidi_start", "kikas_start",
      OWN);

  /**
   * Those models as the code chose them on {@link #full} at no cut: per model a hash of its
   * {@link #fingerprint} and of its forest's ({@link #forestFingerprint}). Captured from 8edd07b
   * (main after ticket 21); the models the fourteen leave out left out.
   */
  private static final List<String> EIGHT = List.of(
      "alzubaidi_start d4695408 84ec1528",
      "kikas_start bdd33a7 b49f588a",
      "own_with_similarity_before_pressure b8c08335 17b86ec4");

  /** The start models and Kikas's retrospective one, which ticket 29 pinned at ticket 27. */
  private static final List<String> START_AND_KIKAS = List.of("alzubaidi_start", "kikas_start",
      OWN, "kikas_retrospective");

  /**
   * The selection of {@link #full} under the tail cut it chooses, as the code made it before the
   * blocking wait, the comments and the participants left the main retrospective model: per
   * model of {@link #START_AND_KIKAS} a hash of its {@link #everything} line (Elastic Net,
   * removed columns, forest). Captured from ef6d555 (ticket 27 with the bot filter); the start
   * models' lines are those of 6d7edf1 (main after ticket 24). The hash of the k's grid, its
   * errors those of the model the k was chosen on, is {@link #SIX_SETS_AT_TICKET_29}'s first line
   * without the errors, the k now chosen on the own start model.
   */
  private static final List<String> START_AT_TICKET_27 = List.of(
      "alzubaidi_start e7762856",
      "kikas_start 16b7d500",
      "own_with_similarity_before_pressure 46d6e3a8",
      "kikas_retrospective 51cd32ef");

  /** Kikas's twelve retrospective columns (item 59), in the registered order. */
  private static final List<String> KIKAS_RETROSPECTIVE = List.of("K03_comments", "K04_actors",
      "K05_assignments", "K06_labels_c2", "K07_mentions", "K08_commit_refs",
      "K10_mean_comment_words", "K17c2_project_issues_14d_obs",
      "K18c2_project_created_closed_14d_obs", "K19c2_project_commits_14d_obs",
      "K20_participant_commits", "K21_participant_committers");

  private static final Instant T = Instant.parse("2016-01-01T00:00:00Z");

  private final List<RowCache.Entry> entries = new ArrayList<>();

  /**
   * An issue drawn as {@code hours} in the given split, lasting a business day per 24 of them
   * ({@link BusinessDayFixtures#days}); a null value is a missing one.
   */
  private void row(final String id, final double hours, final String outer, final String inner,
      final Map<String, Double> values) {
    rowOfDays(id, BusinessDayFixtures.days(hours), outer, inner, values);
  }

  /** An issue of {@code days} business days in the given split; a null value is a missing one. */
  private void rowOfDays(final String id, final int days, final String outer, final String inner,
      final Map<String, Double> values) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    final Instant start = T.plusSeconds(3600L * entries.size());
    final Map<String, Double> present = new HashMap<>();
    final Set<String> absent = new HashSet<>();
    values.forEach((k, v) -> {
      if (v == null) {
        absent.add(k);
      } else {
        present.put(k, v);
      }
    });
    entries.add(new RowCache.Entry(new IssueUnit(id, "P-" + id, "p" + id.hashCode() % 3, "r",
        "d", T, BusinessDayFixtures.after(start, days), "Fixed", false, start),
        new Row(id, present, absent), parts));
  }

  /** The columns every model but the similarity medians reads, noise around nothing. */
  private static Map<String, Double> base(final Random random, final double hours, final int i) {
    final Map<String, Double> v = new HashMap<>();
    v.put("A01_desc_length", (double) random.nextInt(500));
    v.put("A01_desc_length_ausente", 0.0);
    v.put("A03_team_size", (double) random.nextInt(20));
    v.put("Z08_priority", (double) random.nextInt(5));
    v.put("Z08_priority_ausente", 0.0);
    v.put("K14_project_issues_90d", (double) random.nextInt(100));
    for (final String executor : List.of("A09_exec_loc_tot", "A10_exec_mccc_tot",
        "A13_exec_open_load")) {
      v.put(executor, (double) random.nextInt(1000));
      v.put(executor + "_ausente", 0.0);
    }
    for (final String team : Extractor.TEAM_REVISED) {
      v.put(team, 1000 * random.nextDouble());
    }
    v.put("reporter_median_inner", i % 5 == 0 ? null : hours * (0.5 + random.nextDouble()));
    v.put("reporter_median_outer", 500 * random.nextDouble());
    return v;
  }

  /**
   * The ten retrospective columns, drawn from their own generator so the other columns are the
   * draws they were before ticket 15: the duration with noise, the five of the content missing
   * on every fourth row, as without an implementation commit, the blocking-link wait on every
   * third and urgency on every fifth.
   */
  private static Map<String, Double> retrospective(final Random random, final double hours,
      final int i) {
    final Map<String, Double> v = new HashMap<>();
    for (final String column : RETROSPECTIVE) {
      v.put(column, hours * random.nextDouble());
    }
    if (i % 4 == 1) {
      for (final String column : RETROSPECTIVE.subList(0, 5)) {
        v.put(column, null);
      }
    }
    if (i % 3 == 2) {
      v.put("RETRO_blocker_wait", null);
    }
    if (i % 5 == 3) {
      v.put("RETRO_urgency_days", null);
    }
    return v;
  }

  /**
   * The three start columns of ticket 19, drawn from their own generator so no other column moves:
   * the flag 0 or 1, the cycle signed and missing on every third row, the pace missing on every
   * seventh, the same in both variants of the split.
   */
  private static Map<String, Double> start(final Random random, final double hours, final int i) {
    final Map<String, Double> v = new HashMap<>();
    v.put("START_fix_version", (double) random.nextInt(2));
    v.put("START_release_cycle_days", i % 3 == 0 ? null : 100 * random.nextDouble() - 50);
    final Double pace = i % 7 == 0 ? null : hours * random.nextDouble();
    v.put("START_blocker_pace_days_inner", pace);
    v.put("START_blocker_pace_days_outer", pace);
    return v;
  }

  /**
   * Kikas's twelve retrospective columns, drawn from their own generator so no other column
   * moves: the duration with noise, the mean comment words missing on every sixth row, its
   * indicator beside it.
   */
  private static Map<String, Double> kikas(final Random random, final double hours,
      final int i) {
    final Map<String, Double> v = new HashMap<>();
    for (final String column : KIKAS_RETROSPECTIVE) {
      v.put(column, hours * random.nextDouble());
    }
    // the extracted matrix stores the indicator beside its column
    v.put("K10_mean_comment_words_ausente", i % 6 == 1 ? 1.0 : 0.0);
    if (i % 6 == 1) {
      v.put("K10_mean_comment_words", null);
    }
    return v;
  }

  /** The same entries without the given columns, as values or as absent. */
  private static List<RowCache.Entry> without(final List<RowCache.Entry> entries,
      final List<String> columns) {
    final List<RowCache.Entry> out = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      values.keySet().removeAll(columns);
      absent.removeAll(columns);
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, absent),
          e.parts()));
    }
    return out;
  }

  /**
   * Forty inner fitting and twenty inner validation rows with every candidate column of both
   * variants: each similarity median follows the duration with its own noise, wider the larger
   * its n or k, so the combinations differ; the reporter median is missing on every fifth row.
   */
  private void full() {
    final Random random = new Random(13);
    final Random retrospective = new Random(41);
    final Random start = new Random(53);
    final Random kikas = new Random(61);
    for (int i = 0; i < 60; i++) {
      final double hours = Math.exp(1 + 5 * random.nextDouble());
      final Map<String, Double> v = base(random, hours, i);
      v.putAll(retrospective(retrospective, hours, i));
      v.putAll(start(start, hours, i));
      v.putAll(kikas(kikas, hours, i));
      for (final Augmentation.Variant variant : Augmentation.Variant.values()) {
        for (final int n : Augmentation.JACCARD_N) {
          v.put(Augmentation.jaccard(n, variant), hours * (1 + n / 10.0 * random.nextDouble()));
        }
        for (final int n : Augmentation.EMBEDDING_N) {
          v.put(Augmentation.embedding(n, variant),
              hours * (1 + n / 8.0 * random.nextDouble()));
        }
        for (final int k : Augmentation.CLUSTER_K) {
          v.put(Augmentation.cluster(k, variant), hours * (1 + k / 16.0 * random.nextDouble()));
        }
      }
      row("f" + i, hours, "TRAIN", i < 40 ? "TRAIN" : "TEST", v);
    }
  }

  /**
   * As {@link #full}, but every candidate value of a method has the same value on a row: all 27
   * combinations read the same numbers under different names, so their errors tie exactly.
   */
  private void tied() {
    final Random random = new Random(17);
    final Random retrospective = new Random(43);
    final Random start = new Random(59);
    final Random kikas = new Random(67);
    for (int i = 0; i < 60; i++) {
      final double hours = Math.exp(1 + 5 * random.nextDouble());
      final Map<String, Double> v = base(random, hours, i);
      v.putAll(retrospective(retrospective, hours, i));
      v.putAll(start(start, hours, i));
      v.putAll(kikas(kikas, hours, i));
      final double jaccard = hours * (1 + random.nextDouble());
      final double embedding = hours * (1 + random.nextDouble());
      final double cluster = hours * (1 + random.nextDouble());
      for (final Augmentation.Variant variant : Augmentation.Variant.values()) {
        Augmentation.JACCARD_N.forEach(n -> v.put(Augmentation.jaccard(n, variant), jaccard));
        Augmentation.EMBEDDING_N.forEach(n -> v.put(Augmentation.embedding(n, variant),
            embedding));
        Augmentation.CLUSTER_K.forEach(k -> v.put(Augmentation.cluster(k, variant), cluster));
      }
      row("t" + i, hours, "TRAIN", i < 40 ? "TRAIN" : "TEST", v);
    }
  }

  private static ElasticNetSelection.Model model(final ElasticNetSelection.Result r,
      final String name) {
    return r.models().stream().filter(m -> m.cell().label().equals(name)).findFirst()
        .orElseThrow();
  }

  private static List<String> names(final Cell cell) {
    return cell.attributes().stream().map(Attribute::name).toList();
  }

  private static List<String> registered(final String label) {
    return names(Cell.byLabel(label));
  }

  private static List<String> concat(final List<String> a, final List<String> b) {
    final List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private static long bits(final double d) {
    return Double.doubleToRawLongBits(d);
  }

  private static String hex(final double d) {
    return Long.toHexString(bits(d));
  }

  /**
   * A chosen model as bits: combination, columns, alpha, lambda, step, error, every prediction and
   * every combination's sweep.
   */
  private static String fingerprint(final ElasticNetSelection.Model m) {
    final Fitting.Fitted f = m.fitted();
    final String sweeps = m.combinations().stream().map(w -> w.combination().jaccardN() + " "
        + w.combination().embeddingN() + " " + w.combination().clusterK() + " "
        + hex(w.alpha()) + " " + hex(w.lambda()) + " " + hex(w.mae())).toList().toString();
    return m.cell().label() + " " + m.combination() + " " + f.columns() + " " + hex(f.alpha())
        + " " + hex(f.lambda()) + " " + f.step() + " " + hex(f.score().mae()) + " "
        + Integer.toHexString(java.util.Arrays.hashCode(f.predicted())) + " "
        + Integer.toHexString(sweeps.hashCode());
  }

  private static Cell cell(final List<Cell> cells, final String name) {
    return cells.stream().filter(c -> c.label().equals(name)).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("the 27 combinations are every Jaccard n, embedding n and cluster k the stage "
      + "computes, smaller values first in the order Jaccard, embedding, cluster")
  void combinationsInTheirOrder() {
    final List<Combination> all = Combination.all();
    assertEquals(Augmentation.JACCARD_N.size() * Augmentation.EMBEDDING_N.size()
        * Augmentation.CLUSTER_K.size(), all.size());
    assertEquals(27, all.size());
    assertEquals(new Combination(5, 5, 8), all.get(0));
    assertEquals(new Combination(5, 5, 16), all.get(1));
    assertEquals(new Combination(5, 10, 8), all.get(3));
    assertEquals(new Combination(10, 5, 8), all.get(9));
    assertEquals(new Combination(20, 20, 32), all.get(26));
    assertEquals(27, new HashSet<>(all).size());
  }

  @Test
  @DisplayName("six models, each reading exactly the spec's columns, the split-dependent ones in "
      + "the inner variant and the similarity medians at the combination given: the own start "
      + "model, Al-Zubaidi and Kikas at the start, the main reading of the retrospective model, "
      + "the ceiling and Kikas's retrospective one; both own retrospective readings build on the "
      + "own start model")
  void columnsOfEachModel() {
    final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.INNER);
    assertEquals(SIX_MODELS, cells.stream().map(Cell::label).toList());
    assertEquals(ElasticNetSelection.MODELS, cells.stream().map(Cell::label).toList());
    assertEquals(WITH, ElasticNetSelection.WITH_SIMILARITY);
    assertEquals(OWN_START, names(cell(cells, OWN)));
    assertEquals(registered("ALZUBAIDI / C1"), names(cell(cells, "alzubaidi_start")));
    assertEquals(registered("KIKAS / C1"), names(cell(cells, "kikas_start")));
    assertEquals(concat(OWN_START, MAIN), names(cell(cells, "own_retrospective")));
    assertEquals(concat(concat(OWN_START, MAIN), DISCUSSION),
        names(cell(cells, "own_retrospective_with_discussion")));
    assertEquals(concat(registered("KIKAS / C1"), KIKAS_RETROSPECTIVE),
        names(cell(cells, "kikas_retrospective")));
  }

  @Test
  @DisplayName("the models the fourteen leave out are no model: neither the own specification "
      + "without the similarity medians, the medians alone, the own specification with the "
      + "pressure columns, the union of the families nor earlier retrospective variants")
  void leftOutModels() {
    for (final String name : LEFT_OUT) {
      assertFalse(ElasticNetSelection.MODELS.contains(name), name);
      assertFalse(ElasticNetSelection.WITH_SIMILARITY.contains(name), name);
      for (final Augmentation.Variant v : Augmentation.Variant.values()) {
        assertTrue(ElasticNetSelection.everyCell(v).stream()
            .noneMatch(c -> c.label().equals(name)), name);
      }
    }
    assertEquals(6, ElasticNetSelection.MODELS.size());
    assertEquals(6 * Combination.all().size(),
        ElasticNetSelection.everyCell(Augmentation.Variant.INNER).size());
  }

  /** A cell's design columns as the matrix names them: each column, then its missing indicator. */
  private static List<String> design(final Cell cell) {
    final List<String> out = new ArrayList<>();
    for (final Attribute a : cell.attributes()) {
      out.add(a.name());
      if (a.absenceIndicator()) {
        out.add(a.absenceName());
      }
    }
    return out;
  }

  @Test
  @DisplayName("no model reads the three start pressure columns, in either variant and at every "
      + "combination: the own start model is the own specification without them")
  void noModelReadsThePressureColumns() {
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      final List<String> pressure = List.of("START_fix_version", "START_release_cycle_days",
          "START_blocker_pace_days" + v.suffix());
      for (final Combination c : Combination.all()) {
        for (final Cell cell : ElasticNetSelection.cells(c, v)) {
          assertTrue(design(cell).stream().noneMatch(n -> pressure.stream()
              .anyMatch(n::startsWith)), cell.label());
        }
      }
    }
  }

  @Test
  @DisplayName("the main reading of the retrospective model has exactly the 19 columns: the own "
      + "start model, then the five of the content and assignee changes, the counts "
      + "and volumes log1p, the ratio and means raw, a missing indicator beside the five "
      + "that can be missing; no release lag, comments, participants or blocking wait; it reads the "
      + "similarity medians")
  void retrospectiveModel() {
    assertEquals("own_retrospective", ElasticNetSelection.MODELS.get(3));
    assertTrue(ElasticNetSelection.WITH_SIMILARITY.contains("own_retrospective"));
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, v);
      final Cell retrospective = cell(cells, "own_retrospective");
      assertEquals(19, retrospective.attributes().size());
      assertEquals(cell(cells, OWN).attributes(), retrospective.attributes().subList(0, 13));
      final List<Attribute> six = retrospective.attributes().subList(13, 19);
      assertEquals(MAIN, six.stream().map(Attribute::name).toList());
      final List<String> log = List.of("RETRO_java_lines", "RETRO_files",
          "RETRO_assignee_changes");
      final List<String> missing = MAIN.subList(0, 5);
      for (final Attribute a : six) {
        assertEquals(log.contains(a.name()) ? app.study.Construct.Transform.LOG1P
            : app.study.Construct.Transform.NONE, a.transform(), a.name());
        assertEquals(missing.contains(a.name()), a.absenceIndicator(), a.name());
        assertFalse(Attributes.all().contains(a), a.name() + " is not a registered column");
      }
    }
    // none of the start models reads any retrospective column
    for (final Cell c : ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.INNER)) {
      if (c.scenario() == Scenario.C1) {
        assertTrue(c.attributes().stream().noneMatch(a -> a.name().startsWith("RETRO_")),
            c.label());
      }
    }
  }

  @Test
  @DisplayName("the reading with the discussion, the ceiling, is the main reading followed by the "
      + "comments and the participants, log1p and without a missing indicator, and nothing else, "
      + "in both variants and at every combination; it reads the similarity medians")
  void ceilingIsTheMainReadingWithTheDiscussion() {
    assertEquals(ElasticNetSelection.WITH_DISCUSSION, ElasticNetSelection.MODELS.get(4));
    assertTrue(ElasticNetSelection.WITH_SIMILARITY.contains("own_retrospective_with_discussion"));
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      for (final Combination c : Combination.all()) {
        final List<Cell> cells = ElasticNetSelection.cells(c, v);
        final Cell main = cell(cells, "own_retrospective");
        final Cell ceiling = cell(cells, "own_retrospective_with_discussion");
        assertEquals(main.family(), ceiling.family());
        assertEquals(main.scenario(), ceiling.scenario());
        assertEquals(main.attributes(), ceiling.attributes().subList(0, 19));
        final List<Attribute> two = ceiling.attributes().subList(19, 21);
        assertEquals(21, ceiling.attributes().size());
        assertEquals(DISCUSSION, two.stream().map(Attribute::name).toList());
        for (final Attribute a : two) {
          assertEquals(app.study.Construct.Transform.LOG1P, a.transform(), a.name());
          assertFalse(a.absenceIndicator(), a.name());
        }
        final List<String> added = new ArrayList<>(design(ceiling));
        added.removeAll(design(main));
        assertEquals(DISCUSSION, added);
      }
    }
  }

  @Test
  @DisplayName("no model reads release lag or the wait on blocking issues, nor their indicators, nor the "
      + "comments and participants but the ceiling, in both variants and at every combination")
  void noModelReadsTheBlockingWait() {
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      for (final Combination c : Combination.all()) {
        for (final Cell cell : ElasticNetSelection.cells(c, v)) {
          assertTrue(design(cell).stream().noneMatch(n -> n.startsWith("RETRO_blocker_wait")),
              cell.label());
          assertTrue(design(cell).stream().noneMatch(n -> n.startsWith("RETRO_urgency_days")),
              cell.label());
          if (!cell.label().equals("own_retrospective_with_discussion")) {
            assertTrue(design(cell).stream().noneMatch(DISCUSSION::contains), cell.label());
          }
        }
      }
    }
    assertFalse(Retrospective.COLUMNS.contains("RETRO_blocker_wait"));
  }

  @Test
  @DisplayName("the team size in the own models is the registry's column, with its log1p and "
      + "without a missing indicator")
  void teamSizeIsTheRegistryColumn() {
    final Attribute registered = Attributes.byName("A03_team_size");
    assertEquals(app.study.Construct.Transform.LOG1P, registered.transform());
    assertFalse(registered.absenceIndicator());
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, v);
      for (final String name : WITH) {
        assertEquals(1, cell(cells, name).attributes().stream().filter(registered::equals)
            .count(), name);
      }
      for (final String name : WITHOUT) {
        assertTrue(cell(cells, name).attributes().stream()
            .noneMatch(a -> a.name().equals("A03_team_size")), name);
      }
    }
  }

  @Test
  @DisplayName("the outer variant reads the outer columns, for the test that refits on them")
  void outerVariant() {
    final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.OUTER);
    assertEquals(List.of("A01_desc_length", "A03_team_size", "TEAM_loc_median", "TEAM_loc_sd",
        "TEAM_mccc_median", "TEAM_mccc_sd", "reporter_median_outer", "A09_exec_loc_tot",
        "A10_exec_mccc_tot", "A13_exec_open_load", "jaccard_median_10_outer",
        "embedding_median_5_outer", "cluster_median_32_outer"), names(cell(cells, OWN)));
  }

  @Test
  @DisplayName("given one combination per model, each model with the similarity medians reads its "
      + "own; one missing, or one given to a model without them, is refused")
  void cellsOfOneCombinationPerModel() {
    final Map<String, Combination> each = Map.of(
        "own_retrospective", new Combination(5, 5, 16), OWN, new Combination(20, 20, 8),
        "own_retrospective_with_discussion", new Combination(20, 5, 32));
    final List<Cell> cells = ElasticNetSelection.cells(each, Augmentation.Variant.OUTER);
    assertEquals(List.of("jaccard_median_5_outer", "embedding_median_5_outer",
        "cluster_median_16_outer"), names(cell(cells, "own_retrospective")).subList(10, 13));
    assertEquals(List.of("jaccard_median_20_outer", "embedding_median_5_outer",
        "cluster_median_32_outer"),
        names(cell(cells, "own_retrospective_with_discussion")).subList(10, 13));
    assertEquals(List.of("jaccard_median_20_outer", "embedding_median_20_outer",
        "cluster_median_8_outer"), names(cell(cells, OWN)).subList(10, 13));
    final List<Cell> atTen = ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.OUTER);
    for (final String name : WITHOUT) {
      assertEquals(cell(atTen, name), cell(cells, name));
    }

    final Map<String, Combination> missing = new HashMap<>(each);
    missing.remove(OWN);
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.cells(missing, Augmentation.Variant.INNER));
    final Map<String, Combination> extra = new HashMap<>(each);
    extra.put("kikas_start", TEN_FIVE_32);
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.cells(extra, Augmentation.Variant.INNER));
    final Map<String, Combination> leftOut = new HashMap<>(each);
    leftOut.put("own_with_similarity", TEN_FIVE_32);
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.cells(leftOut, Augmentation.Variant.INNER));
  }

  @Test
  @DisplayName("Al-Zubaidi and Kikas at the start choose, bit for bit, what they chose before "
      + "ticket 12: combination, alpha, lambda, error, predictions and every sweep")
  void unchangedModelsAsBefore() {
    full();
    final ElasticNetSelection.Result r = uncut(counted(entries));
    assertEquals(BEFORE, List.of("alzubaidi_start", "kikas_start").stream()
        .map(name -> fingerprint(model(r, name))).toList());
    for (final String name : WITHOUT) {
      assertNull(model(r, name).combination(), name);
      assertEquals(List.of(), model(r, name).combinations(), name);
    }
  }

  @Test
  @DisplayName("each model with the similarity medians keeps, of the 27 combinations each swept "
      + "over alpha and lambda, the one with the lowest inner validation error")
  void keepsTheLowestCombination() {
    full();
    final ElasticNetSelection.Result r = uncut(counted(entries));
    for (final String name : WITH) {
      final ElasticNetSelection.Model m = model(r, name);
      assertEquals(Combination.all(),
          m.combinations().stream().map(ElasticNetSelection.Swept::combination).toList(), name);
      final double lowest = m.combinations().stream()
          .mapToDouble(ElasticNetSelection.Swept::mae).min().orElseThrow();
      assertEquals(bits(lowest), bits(m.fitted().score().mae()), name);
      final ElasticNetSelection.Swept chosen = m.combinations().stream()
          .filter(s -> s.combination().equals(m.combination())).findFirst().orElseThrow();
      assertEquals(bits(lowest), bits(chosen.mae()), name);
      assertEquals(bits(chosen.alpha()), bits(m.fitted().alpha()), name);
      assertEquals(bits(chosen.lambda()), bits(m.fitted().lambda()), name);
      final Combination c = m.combination();
      assertTrue(m.fitted().columns().containsAll(List.of(
          Augmentation.jaccard(c.jaccardN(), Augmentation.Variant.INNER),
          Augmentation.embedding(c.embeddingN(), Augmentation.Variant.INNER),
          Augmentation.cluster(c.clusterK(), Augmentation.Variant.INNER))), name);
      assertEquals(ElasticNetSelection.cells(c, Augmentation.Variant.INNER).stream()
          .filter(x -> x.label().equals(name)).findFirst().orElseThrow(), m.cell(), name);
    }
    // the fixture makes the combinations differ, so a choice is not the first by default
    assertTrue(WITH.stream().allMatch(name -> model(r, name).combinations().stream()
        .mapToLong(s -> bits(s.mae())).distinct().count() > 1));
  }

  @Test
  @DisplayName("combinations whose lowest errors tie exactly go to the smaller values, Jaccard n "
      + "first, then embedding n, then cluster k")
  void tiesGoToTheSmallerValues() {
    tied();
    final ElasticNetSelection.Result r = uncut(counted(entries));
    for (final String name : WITH) {
      final ElasticNetSelection.Model m = model(r, name);
      assertEquals(1, m.combinations().stream().mapToLong(s -> bits(s.mae())).distinct()
          .count(), name + " does not tie: " + m.combinations());
      assertEquals(new Combination(5, 5, 8), m.combination(), name);
    }
  }

  @Test
  @DisplayName("a column missing on every inner fitting row is removed from each model reading it, "
      + "with the reason recorded, and its validation values are never imputed from")
  void removesAColumnMissingInTheFittingSet() {
    full();
    final List<RowCache.Entry> blanked = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final boolean fitting = "TRAIN".equals(e.inner(Population.IN_PROGRESS));
      final Map<String, Double> values = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      if (fitting) {
        for (final int k : Augmentation.CLUSTER_K) {
          values.remove(Augmentation.cluster(k, Augmentation.Variant.INNER));
          absent.add(Augmentation.cluster(k, Augmentation.Variant.INNER));
        }
      }
      blanked.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, absent),
          e.parts()));
    }
    final ElasticNetSelection.Result r = uncut(counted(blanked));
    assertEquals(40, r.fitRows());
    assertEquals(20, r.validationRows());
    for (final String name : WITH) {
      final ElasticNetSelection.Model m = model(r, name);
      assertEquals(List.of(new ElasticNetSelection.Removed(
          Augmentation.cluster(m.combination().clusterK(), Augmentation.Variant.INNER),
          "missing on all 40 inner fitting rows")), m.removed().stream()
              .filter(x -> x.column().contains("_median_")).toList(), name);
      assertFalse(m.fitted().columns().stream().anyMatch(c -> c.startsWith("cluster_")));
    }
    assertEquals(1, model(r, OWN).removed().size());
    assertEquals(List.of(), model(r, "kikas_retrospective").removed().stream()
        .filter(x -> x.column().startsWith("cluster_")).toList());
  }

  @Test
  @DisplayName("on several threads every choice, sweep and error is, bit for bit, what one thread "
      + "gives, the models and combinations in the same order")
  void parallelIsSequential() {
    full();
    final ElasticNetSelection.Result one = ElasticNetSelection.run(counted(entries), 1);
    final ElasticNetSelection.Result many = ElasticNetSelection.run(counted(entries), 6);
    assertEquals(one.models().size(), many.models().size());
    assertEquals(bits(one.projectMae()), bits(many.projectMae()));
    for (int m = 0; m < one.models().size(); m++) {
      assertEquals(one.models().get(m).cell(), many.models().get(m).cell());
      assertEquals(one.models().get(m).combination(), many.models().get(m).combination());
      assertEquals(one.models().get(m).removed(), many.models().get(m).removed());
      assertEquals(one.models().get(m).combinations(), many.models().get(m).combinations());
      ParallelSweepTest.assertIdentical(one.models().get(m).fitted(),
          many.models().get(m).fitted());
      assertEquals(one.models().get(m).forest().trials(), many.models().get(m).forest().trials());
      assertEquals(java.util.Arrays.toString(one.models().get(m).forest().predicted()),
          java.util.Arrays.toString(many.models().get(m).forest().predicted()));
    }
    assertEquals(ElasticNetSelection.parameters(one, "m"),
        ElasticNetSelection.parameters(many, "m"));
  }

  /** What a choice is: per model, its combination, columns, alpha, lambda and error. */
  private static List<String> choices(final ElasticNetSelection.Result r) {
    return r.models().stream().map(m -> m.cell().label() + " " + m.combination() + " "
        + m.fitted().columns() + " " + m.fitted().alpha() + " " + m.fitted().lambda() + " "
        + m.fitted().score().mae() + " " + m.combinations()).toList();
  }

  @Test
  @DisplayName("in inner mode the outer variant is never read: changing every outer column "
      + "changes no choice")
  void readsTheInnerVariant() {
    full();
    final List<String> before = choices(ElasticNetSelection.run(counted(entries)));
    final List<RowCache.Entry> changed = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.replaceAll((k, v) -> k.endsWith("_outer") ? v * 1000 + 7 : v);
      changed.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values,
          e.row().absent()), e.parts()));
    }
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(changed));
    assertEquals(before, choices(r));
    assertTrue(model(r, OWN).fitted().columns().stream()
        .noneMatch(c -> c.contains("_outer")));
  }

  @Test
  @DisplayName("the outer test rows are never read: neither their durations nor their values, "
      + "not even values that would refuse the run")
  void neverReadsTheOuterTest() {
    full();
    final List<String> before = choices(ElasticNetSelection.run(counted(entries)));
    final Map<String, Double> poison = new HashMap<>();
    for (final String column : List.of("reporter_median_inner", "TEAM_loc_median")) {
      poison.put(column, Double.NaN);
    }
    for (final String column : Augmentation.columns()) {
      poison.put(column, Double.NaN);
    }
    for (int k = 0; k < 10; k++) {
      row("t" + k, 90_000, "TEST", "", poison);
      row("x" + k, 90_000, "CROSSES", "", poison);
    }
    assertEquals(before, choices(ElasticNetSelection.run(counted(entries))));
  }

  @Test
  @DisplayName("a missing value of a new column is handed on as missing, with its indicator "
      + "beside it; a value that is not a number refuses")
  void missingIsHandedOnWithItsIndicator() {
    full();
    final List<String> columns =
        model(uncut(counted(entries)), OWN).fitted().columns();
    assertTrue(columns.contains("reporter_median_inner"));
    assertTrue(columns.contains("reporter_median_inner_ausente"));
    // observed on every fitting row: the indicator is constant there, and the preparation drops it
    assertFalse(columns.contains("TEAM_loc_median_ausente"));

    row("nan", 10, "TRAIN", "TRAIN", Map.of("TEAM_loc_sd", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> uncut(counted(entries)));
  }

  @Test
  @DisplayName("the parameters file holds the matrix's hash, each model's alpha and lambda, and "
      + "the chosen n, n and k of the three with the similarity medians, read back exactly")
  void parametersFile() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries));
    final List<String> lines = ElasticNetSelection.parameters(r, "cd34");
    assertEquals(ElasticNetSelection.HEADER, lines.get(0));
    final List<String> data = lines.stream().filter(l -> !l.startsWith("#")).toList();
    assertEquals("elastic-net\tinputs\tmatrix_sha256\tcd34", data.get(0));
    final int tail = 4 + 3 * TailCut.GRID.size();
    assertEquals(1 + tail + 2 * 6 + 3 * 3 + 3 * 6, data.size());
    // the tail cut after the matrix: the chosen k and fence, the usual flow's, then each k's
    final TailCut.Choice cut = r.cut();
    assertEquals(List.of("tail-cut\tchosen\tk\t" + TailCut.label(cut.k()),
        "tail-cut\tchosen\tfence_business_days\t" + cut.fence(),
        "tail-cut\tusual_flow\tk\t3.0",
        "tail-cut\tusual_flow\tfence_business_days\t" + cut.usualFence()), data.subList(1, 5));
    assertEquals(List.of("tail-cut\tk_none\tfence_business_days\tInfinity",
        "tail-cut\tk_none\tfit_rows\t40",
        "tail-cut\tk_none\tmae_business_days\t" + cut.grid().get(4).mae()),
        data.subList(tail - 2, tail + 1));
    // the Elastic Net lines, as before the forests; then three forest lines per model
    assertTrue(data.subList(1 + tail, 1 + tail + 2 * 6 + 3 * 3).stream()
        .allMatch(l -> l.startsWith("elastic-net\t")));
    assertEquals(ElasticNetSelection.MODELS, data.subList(1 + tail + 2 * 6 + 3 * 3, data.size())
        .stream().map(l -> l.split("\t")[1]).distinct().toList());
    assertEquals(cut, ElasticNetSelection.tailCut(ElasticNetSelection.read(lines)));
    final Forest.Fitted ownForest = model(r, OWN).forest();
    assertTrue(data.contains("forest\t" + OWN + "\tmtry_share\t" + ownForest.share()));
    assertTrue(data.contains("forest\t" + OWN + "\tmtry\t" + ownForest.mtry()));
    assertTrue(data.contains("forest\t" + OWN + "\tnode_size\t" + ownForest.nodeSize()));
    assertTrue(data.stream().noneMatch(l -> l.contains("similarity_parameters")));
    final ElasticNetSelection.Model own = model(r, OWN);
    assertTrue(data.contains("elastic-net\t" + OWN + "\talpha\t" + own.fitted().alpha()));
    assertTrue(data.contains("elastic-net\t" + OWN + "\tjaccard_n\t"
        + own.combination().jaccardN()));
    assertTrue(data.contains("elastic-net\t" + OWN + "\tembedding_n\t"
        + own.combination().embeddingN()));
    assertTrue(data.contains("elastic-net\t" + OWN + "\tcluster_k\t"
        + own.combination().clusterK()));
    assertTrue(data.stream()
        .noneMatch(l -> l.startsWith("elastic-net\talzubaidi_start\tjaccard")));
    assertTrue(data.stream().noneMatch(l -> l.contains("previous_own_start")));
    // no name of a model the fourteen leave out, in any field
    for (final String line : data) {
      for (final String field : line.split("\t")) {
        assertFalse(LEFT_OUT.contains(field), line);
      }
    }
    // the header names the own start model as where the k is chosen
    assertTrue(ElasticNetSelection.HEADER.contains("the k chosen on the own start model"));

    final List<ElasticNetSelection.Parameter> read = ElasticNetSelection.read(lines);
    final Map<String, ElasticNetSelection.Choice> back = ElasticNetSelection.choices(read);
    assertEquals(ElasticNetSelection.MODELS, List.copyOf(back.keySet()));
    for (final ElasticNetSelection.Model m : r.models()) {
      assertEquals(new ElasticNetSelection.Choice(m.fitted().alpha(), m.fitted().lambda(),
          m.combination()), back.get(m.cell().label()));
    }
    assertEquals("cd34", ElasticNetSelection.matrixSha(read));
    final Map<String, Forest.Choice> forests = ElasticNetSelection.forests(read);
    assertEquals(ElasticNetSelection.MODELS, List.copyOf(forests.keySet()));
    for (final ElasticNetSelection.Model m : r.models()) {
      assertEquals(new Forest.Choice(m.forest().share(), m.forest().mtry(),
          m.forest().nodeSize()), forests.get(m.cell().label()));
    }
  }

  @Test
  @DisplayName("a forest without its mtry share, mtry or leaf, an unknown forest parameter, or a "
      + "point the declared grid does not hold, is refused")
  void refusesIncompleteForests() {
    final List<String> head = List.of(ElasticNetSelection.HEADER,
        "forest\tkikas_start\tmtry_share\t" + (1.0 / 3), "forest\tkikas_start\tmtry\t1");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.forests(ElasticNetSelection.read(head)));
    final List<String> whole = new ArrayList<>(head);
    whole.add("forest\tkikas_start\tnode_size\t25");
    assertEquals(new Forest.Choice(1.0 / 3, 1, 25),
        ElasticNetSelection.forests(ElasticNetSelection.read(whole)).get("kikas_start"));
    final List<String> unknown = new ArrayList<>(whole);
    unknown.add("forest\tkikas_start\ttrees\t300");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.forests(ElasticNetSelection.read(unknown)));
    for (final String[] off : new String[][] {{"mtry_share", "0.5"}, {"node_size", "10"},
        {"mtry", "0"}}) {
      final List<String> lines = new ArrayList<>(List.of(ElasticNetSelection.HEADER,
          "forest\tkikas_start\tmtry_share\t" + (1.0 / 3), "forest\tkikas_start\tmtry\t1",
          "forest\tkikas_start\tnode_size\t25"));
      lines.replaceAll(l -> l.startsWith("forest\tkikas_start\t" + off[0] + "\t")
          ? "forest\tkikas_start\t" + off[0] + "\t" + off[1] : l);
      assertThrows(IllegalArgumentException.class,
          () -> ElasticNetSelection.forests(ElasticNetSelection.read(lines)), off[0]);
    }
    // the Elastic Net's choices do not read the forest lines
    assertEquals(Map.of(), ElasticNetSelection.choices(ElasticNetSelection.read(whole)));
  }

  @Test
  @DisplayName("a model with only part of its n, n and k, or a value the stage does not compute, "
      + "is refused; so is a malformed or repeated line")
  void refusesIncompleteChoices() {
    final List<String> head = List.of(ElasticNetSelection.HEADER,
        "elastic-net\tinputs\tmatrix_sha256\tcd34", "elastic-net\town_retrospective\talpha\t0.5",
        "elastic-net\town_retrospective\tlambda\t0.1",
        "elastic-net\town_retrospective\tjaccard_n\t10",
        "elastic-net\town_retrospective\tembedding_n\t5");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.choices(ElasticNetSelection.read(head)));
    final List<String> odd = new ArrayList<>(head);
    odd.add("elastic-net\town_retrospective\tcluster_k\t12");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.choices(ElasticNetSelection.read(odd)));
    final List<String> whole = new ArrayList<>(head);
    whole.add("elastic-net\town_retrospective\tcluster_k\t16");
    assertEquals(new Combination(10, 5, 16), ElasticNetSelection.choices(
        ElasticNetSelection.read(whole)).get("own_retrospective").combination());

    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.read(List.of("elastic-net\tx")));
    assertThrows(IllegalArgumentException.class, () -> ElasticNetSelection.read(List.of(
        "elastic-net\tx\talpha\t0.5", "elastic-net\tx\talpha\t0.1")));
  }

  @Test
  @DisplayName("the three start models choose, bit for bit, what they chose before the forests, "
      + "the own start model what the own specification with similarity chose before the "
      + "pressure columns were added to it; the matrix's pressure and retrospective columns move "
      + "no start model, and the pressure columns no model at all")
  void startModelsAsBeforeTheForests() {
    full();
    final ElasticNetSelection.Result r = uncut(counted(entries));
    assertEquals(concat(BEFORE, List.of(OWN_BEFORE_FORESTS)),
        START_MODELS.stream().map(name -> fingerprint(model(r, name))).toList());
    assertEquals(ElasticNetSelection.MODELS, r.models().stream().map(m -> m.cell().label())
        .toList());

    // without the pressure columns in the matrix: every model, forest and reference the same
    assertEquals(everything(r), everything(uncut(counted(without(entries, START)))));
    // without the retrospective columns too: the start models the same
    final ElasticNetSelection.Result before =
        uncut(counted(without(without(entries, START), RETROSPECTIVE)));
    for (final String name : START_MODELS) {
      assertEquals(fingerprint(model(before, name)), fingerprint(model(r, name)), name);
      assertEquals(java.util.Arrays.toString(model(before, name).forest().predicted()),
          java.util.Arrays.toString(model(r, name).forest().predicted()), name);
      assertEquals(model(before, name).forest().trials(), model(r, name).forest().trials(), name);
    }
  }

  /** A forest as bits: its columns, column share, count, leaf, error, predictions and grid. */
  private static String forestFingerprint(final Forest.Fitted f) {
    return f.columns() + " " + hex(f.share()) + " " + f.mtry() + " " + f.nodeSize() + " "
        + hex(f.score().mae()) + " " + java.util.Arrays.toString(f.predicted()) + " "
        + f.trials();
  }

  @Test
  @DisplayName("the start models choose, bit for bit, what they chose before the final "
      + "retrospective specification: Elastic Net (combination, columns, alpha, lambda, error, "
      + "predictions, every sweep) and forest (columns, column share, leaf, error, predictions, "
      + "grid)")
  void startAsBeforeTheNinth() {
    full();
    final ElasticNetSelection.Result r = uncut(counted(entries));
    final List<String> now = new ArrayList<>();
    for (final String name : START_MODELS) {
      now.add(name + " " + Integer.toHexString(fingerprint(model(r, name)).hashCode()) + " "
          + Integer.toHexString(forestFingerprint(model(r, name).forest()).hashCode()));
    }
    assertEquals(EIGHT, now);
  }

  @Test
  @DisplayName("both readings of the retrospective model are swept over the 27 combinations, "
      + "read their retrospective columns beside the own start model's, missing indicators for "
      + "those that can be missing, the ceiling the comments and the participants too, and have "
      + "their forests on the same columns")
  void retrospectiveChosen() {
    full();
    final ElasticNetSelection.Result r = uncut(counted(entries));
    final ElasticNetSelection.Model m = model(r, "own_retrospective");
    assertEquals(27, m.combinations().size());
    assertEquals(List.of(), m.removed());
    final List<String> columns = m.fitted().columns();
    assertTrue(columns.containsAll(MAIN), columns.toString());
    assertTrue(columns.contains("RETRO_java_lines_ausente"), columns.toString());
    assertTrue(columns.stream().noneMatch(c -> DISCUSSION.contains(c) || c.startsWith("START_")
        || c.startsWith("RETRO_blocker_wait")), columns.toString());
    assertEquals(columns, m.forest().columns());

    final ElasticNetSelection.Model ceiling = model(r, "own_retrospective_with_discussion");
    assertEquals(27, ceiling.combinations().size());
    assertEquals(List.of(), ceiling.removed());
    final List<String> all = ceiling.fitted().columns();
    assertTrue(all.containsAll(concat(MAIN, DISCUSSION)), all.toString());
    assertFalse(all.contains("RETRO_comments_ausente"), all.toString());
    assertTrue(all.stream().noneMatch(c -> c.startsWith("START_")
        || c.startsWith("RETRO_blocker_wait")), all.toString());
    assertEquals(all, ceiling.forest().columns());
  }

  @Test
  @DisplayName("beside each of the six models a forest is swept on its Elastic Net's columns -- "
      + "the similarity medians at the n and k the Elastic Net chose -- and the same rows, keeping "
      + "the grid point that errs least on the inner validation")
  void forestBesideEachModel() {
    full();
    final ElasticNetSelection.Result r = uncut(counted(entries));
    assertEquals(ElasticNetSelection.MODELS, r.models().stream().map(m -> m.forest().cell()
        .label()).toList());
    for (final ElasticNetSelection.Model m : r.models()) {
      final String name = m.cell().label();
      final Forest.Fitted f = m.forest();
      assertEquals(m.fitted().cell(), f.cell(), name);
      assertEquals(m.fitted().columns(), f.columns(), name);
      assertEquals(m.fitted().dropped(), f.dropped(), name);
      assertEquals(20, f.predicted().length, name);
      final int p = f.columns().size();
      final long points = java.util.Arrays.stream(Forest.MTRY_SHARES)
          .mapToInt(s -> Forest.mtry(s, p)).distinct().count() * Forest.NODE_SIZES.length;
      assertEquals(points, f.trials().size(), name);
      final Forest.Trial chosen = f.trials().get(ForestTrials.chosen(f.trials()));
      assertEquals(bits(chosen.mae()), bits(f.score().mae()), name);
      assertEquals(new Forest.Choice(chosen.share(), chosen.mtry(), chosen.nodeSize()),
          new Forest.Choice(f.share(), f.mtry(), f.nodeSize()), name);
    }
    final Combination c = model(r, OWN).combination();
    assertTrue(model(r, OWN).forest().columns().containsAll(List.of(
        Augmentation.jaccard(c.jaccardN(), Augmentation.Variant.INNER),
        Augmentation.embedding(c.embeddingN(), Augmentation.Variant.INNER),
        Augmentation.cluster(c.clusterK(), Augmentation.Variant.INNER))));
  }

  @Test
  @DisplayName("the four split sizes are counted from the split labels alone")
  void sizes() {
    row("a", 10, "TRAIN", "TRAIN", Map.of());
    row("b", 20, "TRAIN", "TRAIN", Map.of());
    row("c", 30, "TRAIN", "TRAIN", Map.of());
    row("v", 100, "TRAIN", "TEST", Map.of());
    row("t", 1, "TEST", "", Map.of());
    row("u", 1, "TEST", "", Map.of());
    row("x", 1, "CROSSES", "", Map.of());
    assertEquals(new ProjectSplit.Sizes(4, 2, 3, 1),
        ElasticNetSelection.sizes(entries));
  }

  @Test
  @DisplayName("on the business-day population the issues of 0 business days are in neither the "
      + "inner fitting nor the inner validation; the target and references are business days")
  void businessDayPopulation() {
    full();
    for (int k = 0; k < 6; k++) {
      rowOfDays("zero" + k, 0, "TRAIN", k < 4 ? "TRAIN" : "TEST", base(new Random(k), 5, k));
    }
    final BusinessDays.Population p =
        BusinessDays.population(BusinessDayFixtures.bounded(entries), Augmentation.Variant.INNER);
    assertEquals(p.all().innerFit() - 4, p.kept().innerFit());
    assertEquals(p.all().innerSelection() - 2, p.kept().innerSelection());
    final ElasticNetSelection.Result r = uncut(p.entries());
    assertEquals(p.kept().innerFit(), r.fitRows());
    assertEquals(p.kept().innerSelection(), r.validationRows());
    final double error = model(r, OWN).fitted().score().mae();
    assertTrue(error < 50, "an error in business days, not in hours: " + error);
  }

  @Test
  @DisplayName("an outer training entry whose business days are not set, as one that did not go "
      + "through the population, refuses the run: no silent fallback to the hours")
  void refusesEntriesWithoutBusinessDays() {
    full();
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.run(entries, 1));
    assertTrue(e.getMessage().contains("business days"), e.getMessage());
    final List<RowCache.Entry> one = new ArrayList<>(counted(entries));
    final RowCache.Entry first = one.get(0);
    final IssueUnit u = first.unit();
    one.set(0, new RowCache.Entry(new IssueUnit(u.issueId(), u.key(), u.project(), u.reporter(),
        u.executor(), u.createdAt(), u.resolvedAt(), u.reason(), u.moved(), u.startedAt()),
        first.row(), first.parts()));
    assertThrows(IllegalArgumentException.class, () -> ElasticNetSelection.run(one, 1));
  }

  private static List<RowCache.Entry> counted(final List<RowCache.Entry> entries) {
    return BusinessDayFixtures.counted(entries);
  }

  /**
   * The selection at no cut, every choice scored on all the validation issues, on one thread: the
   * choice before ticket 23, which every pin above was taken from.
   */
  private static ElasticNetSelection.Result uncut(final List<RowCache.Entry> entries) {
    return fixed(entries, TailCut.NO_CUT, TailCut.Reading.ALL, 1);
  }

  /** The inner fitting units of counted entries, as the selection reads them. */
  private static List<IssueUnit> innerFitting(final List<RowCache.Entry> entries) {
    return entries.stream().filter(e -> "TRAIN".equals(e.outer(Population.IN_PROGRESS))
        && "TRAIN".equals(e.inner(Population.IN_PROGRESS))).map(RowCache.Entry::unit).toList();
  }

  /**
   * The same entries with every other inner validation issue's business days times three plus
   * one, so some stay in the usual flow.
   */
  private static List<RowCache.Entry> longerValidation(final List<RowCache.Entry> entries) {
    final List<RowCache.Entry> out = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      final RowCache.Entry e = entries.get(i);
      final IssueUnit u = e.unit();
      out.add("TEST".equals(e.inner(Population.IN_PROGRESS)) && i % 2 == 0
          ? new RowCache.Entry(u.withBusinessDays(3 * u.businessDays() + 1), e.row(), e.parts())
          : e);
    }
    return out;
  }

  /** Every Elastic Net and forest of a result as bits, and the references' errors. */
  private static List<String> everything(final ElasticNetSelection.Result r) {
    final List<String> out = new ArrayList<>();
    out.add(r.fitRows() + " " + r.validationRows() + " " + hex(r.projectMae()) + " "
        + hex(r.constantMae()));
    for (final ElasticNetSelection.Model m : r.models()) {
      out.add(fingerprint(m) + " " + m.removed() + " " + forestFingerprint(m.forest()));
    }
    return out;
  }

  @Test
  @DisplayName("Kikas's retrospective model is Kikas at the start followed by its twelve "
      + "retrospective columns and the one missing indicator the matrix has for them, in both "
      + "variants and at every combination: no similarity median, no own column")
  void kikasRetrospectiveColumns() {
    assertEquals(ElasticNetSelection.KIKAS_RETROSPECTIVE, ElasticNetSelection.MODELS.get(5));
    assertFalse(ElasticNetSelection.WITH_SIMILARITY.contains("kikas_retrospective"));
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      for (final Combination c : Combination.all()) {
        final List<Cell> cells = ElasticNetSelection.cells(c, v);
        final Cell kikas = cell(cells, "kikas_retrospective");
        assertEquals(concat(names(cell(cells, "kikas_start")), KIKAS_RETROSPECTIVE),
            names(kikas));
        assertEquals(Family.KIKAS, kikas.family());
        assertEquals(Scenario.C2, kikas.scenario());
        final List<String> indicators = new ArrayList<>(design(kikas));
        indicators.removeAll(design(cell(cells, "kikas_start")));
        indicators.removeAll(KIKAS_RETROSPECTIVE);
        assertEquals(List.of("K10_mean_comment_words_ausente"), indicators);
        assertTrue(Attributes.stored().containsAll(design(kikas)));
      }
    }
  }

  @Test
  @DisplayName("Kikas's retrospective model is chosen once, without a combination, reads its "
      + "twelve columns and their indicator, and has its forest on the same columns")
  void kikasRetrospectiveChosen() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries), 1);
    final ElasticNetSelection.Model m = model(r, "kikas_retrospective");
    assertNull(m.combination());
    assertEquals(List.of(), m.combinations());
    final List<String> columns = m.fitted().columns();
    assertTrue(columns.containsAll(KIKAS_RETROSPECTIVE), columns.toString());
    assertTrue(columns.contains("K10_mean_comment_words_ausente"), columns.toString());
    assertEquals(columns, m.forest().columns());
    // the kept start columns are kikas_start's
    final List<String> start = new ArrayList<>(columns);
    start.removeAll(KIKAS_RETROSPECTIVE);
    start.remove("K10_mean_comment_words_ausente");
    assertEquals(model(r, "kikas_start").fitted().columns(), start);
  }

  @Test
  @DisplayName("the start models and Kikas's retrospective one choose, bit for bit, what they "
      + "chose at ticket 27 under the tail cut chosen: every Elastic Net and forest")
  void startAsAtTicket27() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries), 1);
    final List<String> now = new ArrayList<>();
    for (final String name : START_AND_KIKAS) {
      final ElasticNetSelection.Model m = model(r, name);
      now.add(name + " " + Integer.toHexString((fingerprint(m) + " " + m.removed() + " "
          + forestFingerprint(m.forest())).hashCode()));
    }
    assertEquals(START_AT_TICKET_27, now);
  }

  /**
   * The six attribute sets the scope of fourteen models keeps: the own start model, Al-Zubaidi
   * and Kikas at the start, both readings of the own retrospective model and Kikas's
   * retrospective one.
   */
  private static final List<String> SIX_SETS = List.of("own_with_similarity_before_pressure",
      "alzubaidi_start", "kikas_start", "own_retrospective", "own_retrospective_with_discussion",
      "kikas_retrospective");

  /**
   * The selection of {@link #full} as the code made it before the fourteen models were settled:
   * under the tail cut it chooses, a hash of the rows, the references, the chosen k, both fences
   * and every k's fence and fitting rows (not its error, which is of the model the k is chosen
   * on), then per set of {@link #SIX_SETS} a hash of its {@link #everything} line (Elastic Net,
   * removed columns, forest); then, at no cut on every validation issue, the same per set.
   * Start and literature entries retain the ticket-29 bits; the four own-retrospective entries
   * below were recaptured after the deliberate removal of release lag from those two models.
   */
  private static final List<String> SIX_SETS_FINAL = List.of(
      "d3d3ddba",
      "own_with_similarity_before_pressure 46d6e3a8",
      "alzubaidi_start e7762856",
      "kikas_start 16b7d500",
      "own_retrospective c7f07c28",
      "own_retrospective_with_discussion a38f1e78",
      "kikas_retrospective 51cd32ef",
      "own_with_similarity_before_pressure d655ca57",
      "alzubaidi_start a86a7d59",
      "kikas_start dfec3b71",
      "own_retrospective 1e6e6309",
      "own_retrospective_with_discussion 24bf342e",
      "kikas_retrospective cad789cc");

  @Test
  @DisplayName("the six final attribute sets choose, bit for bit, under the tail cut chosen "
      + "and at no cut: the k, its fences, every Elastic Net and forest")
  void sixSetsFinal() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries), 1);
    final List<String> now = new ArrayList<>(List.of(Integer.toHexString((everything(r).get(0)
        + " " + hex(r.cut().k()) + " " + hex(r.cut().fence()) + " " + hex(r.cut().usualFence())
        + " " + r.cut().grid().stream().map(t -> hex(t.k()) + " " + hex(t.fence()) + " "
            + t.fitRows()).toList()).hashCode())));
    final ElasticNetSelection.Result u = uncut(counted(entries));
    for (final ElasticNetSelection.Result x : List.of(r, u)) {
      for (final String name : SIX_SETS) {
        final ElasticNetSelection.Model m = model(x, name);
        now.add(name + " " + Integer.toHexString((fingerprint(m) + " " + m.removed() + " "
            + forestFingerprint(m.forest())).hashCode()));
      }
    }
    assertEquals(SIX_SETS_FINAL, now);
  }

  @Test
  @DisplayName("tail cut: both fences and every k's fitting rows are taken on the inner fitting "
      + "set only; changing every validation label moves none of them")
  void fencesOnTheFittingSetOnly() {
    full();
    final List<RowCache.Entry> counted = counted(entries);
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted, 1);
    final List<IssueUnit> fitting = innerFitting(counted);
    assertEquals(bits(TailCut.fence(fitting, 3)), bits(r.cut().usualFence()));
    // every choice is scored on the usual-flow validation issues
    assertEquals(TailCut.Reading.USUAL_FLOW, r.scored());
    assertEquals(java.util.Arrays.stream(validation(counted))
        .filter(d -> d <= r.cut().usualFence()).count(), r.validationRows());
    assertTrue(r.validationRows() < validation(counted).length, "the fixture's tail is read");
    assertEquals(TailCut.GRID, r.cut().grid().stream().map(TailCut.Trial::k).toList());
    for (final TailCut.Trial t : r.cut().grid()) {
      assertEquals(bits(TailCut.fence(fitting, t.k())), bits(t.fence()), "k " + t.k());
      assertEquals(fitting.stream().filter(u -> u.duration() <= t.fence()).count(), t.fitRows(),
          "k " + t.k());
    }
    final ElasticNetSelection.Result longer =
        ElasticNetSelection.run(longerValidation(counted), 1);
    assertEquals(bits(r.cut().usualFence()), bits(longer.cut().usualFence()));
    for (int t = 0; t < TailCut.GRID.size(); t++) {
      assertEquals(bits(r.cut().grid().get(t).fence()), bits(longer.cut().grid().get(t).fence()));
      assertEquals(r.cut().grid().get(t).fitRows(), longer.cut().grid().get(t).fitRows());
    }
  }

  @Test
  @DisplayName("tail cut: k is the grid's lowest error of the own start model on the usual-flow "
      + "validation issues, read from its own choice at each k, and reaches every model and "
      + "forest; each is fitted on exactly the inner fitting rows at or below the k's fence, bit "
      + "for bit as if the rows above it were not there")
  void chosenKReachesEveryModel() {
    full();
    final List<RowCache.Entry> counted = counted(entries);
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted, 1);
    final TailCut.Choice cut = r.cut();
    final TailCut.Trial chosen = cut.grid().get(TailCut.chosen(cut.grid()));
    assertEquals(chosen.k(), cut.k());
    assertEquals(bits(chosen.fence()), bits(cut.fence()));
    assertEquals(chosen.fitRows(), r.fitRows());
    // each k's error is the own start model's, chosen on that k's rows, on the usual flow
    for (final TailCut.Trial t : cut.grid()) {
      final ElasticNetSelection.Model own = model(fixed(counted, t.k(),
          TailCut.Reading.USUAL_FLOW, 1), OWN);
      assertEquals(bits(own.fitted().score().mae()), bits(t.mae()), "k " + t.k());
    }
    // the fixture's grid errs differently from one k to another, so the choice reads them
    assertTrue(cut.grid().stream().mapToLong(t -> bits(t.mae())).distinct().count() > 1,
        cut.grid().toString());
    // the same k reaches every model and forest
    assertEquals(everything(r), everything(fixed(counted, cut.k(),
        TailCut.Reading.USUAL_FLOW, 1)));

    // at a k that cuts, the fitting rows above its fence and the validation issues above the
    // usual flow's left out of the matrix, every choice scored on what is left: the same bits
    final ElasticNetSelection.Result low =
        fixed(counted, 1.5, TailCut.Reading.USUAL_FLOW, 1);
    assertTrue(low.fitRows() < innerFitting(counted).size(), "the fixture's tail is cut");
    final List<RowCache.Entry> kept = counted.stream().filter(e -> "TRAIN".equals(
        e.inner(Population.IN_PROGRESS)) ? e.unit().duration() <= low.cut().fence()
            : e.unit().duration() <= low.cut().usualFence()).toList();
    assertEquals(everything(low), everything(uncut(kept)));
  }

  /** The inner validation's business days, in the entries' order, as the selection scores them. */
  private static double[] validation(final List<RowCache.Entry> counted) {
    return counted.stream().filter(e -> "TRAIN".equals(e.outer(Population.IN_PROGRESS))
        && "TEST".equals(e.inner(Population.IN_PROGRESS)))
        .mapToDouble(e -> e.unit().duration()).toArray();
  }

  @Test
  @DisplayName("tail cut: k is chosen on the own start model alone; changing columns only other "
      + "models read changes no k, fence nor error of the grid")
  void kOnTheOwnModelOnly() {
    full();
    final List<RowCache.Entry> counted = counted(entries);
    final TailCut.Choice before = ElasticNetSelection.run(counted, 1).cut();
    final List<RowCache.Entry> changed = new ArrayList<>();
    for (final RowCache.Entry e : counted) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.replaceAll((k, v) -> k.startsWith("Z08_") || k.startsWith("K14_")
          || k.startsWith("RETRO_") ? v * 1000 + 7 : v);
      changed.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values,
          e.row().absent()), e.parts()));
    }
    assertEquals(before, ElasticNetSelection.run(changed, 1).cut());
  }

  @Test
  @DisplayName("tail cut: when no k cuts a fitting row, every k errs the same and no cut wins the "
      + "tie, the larger k; the choices are those of no cut")
  void tieGoesToTheLargerK() {
    full();
    final List<RowCache.Entry> counted = counted(entries);
    // every inner fitting issue held at the fitting set's Q3: the largest is then at the fence
    final double[] days = innerFitting(counted).stream().mapToDouble(IssueUnit::duration)
        .sorted().toArray();
    final int q3 = (int) Math.floor(TailCut.quantile(days, 0.75));
    final List<RowCache.Entry> held = new ArrayList<>();
    for (final RowCache.Entry e : counted) {
      final IssueUnit u = e.unit();
      held.add("TRAIN".equals(e.inner(Population.IN_PROGRESS)) && u.businessDays() > q3
          ? new RowCache.Entry(u.withBusinessDays(q3), e.row(), e.parts()) : e);
    }
    final ElasticNetSelection.Result r = ElasticNetSelection.run(held, 1);
    assertEquals(1, r.cut().grid().stream().mapToLong(t -> bits(t.mae())).distinct().count(),
        r.cut().grid().toString());
    assertTrue(r.cut().grid().stream().allMatch(t -> t.fitRows() == r.fitRows()));
    assertEquals(TailCut.NO_CUT, r.cut().k());
    assertEquals(everything(fixed(held, TailCut.NO_CUT,
        TailCut.Reading.USUAL_FLOW, 1)), everything(r));
  }

  @Test
  @DisplayName("no cut, every choice scored on all the validation issues, chooses bit for bit "
      + "what the selection chose at ticket 22: its fitting rows are the inner fitting set whole, "
      + "every start model, forest and reference the same")
  void noCutIsTicket22() {
    full();
    final ElasticNetSelection.Result r = uncut(counted(entries));
    assertEquals(40, r.fitRows());
    assertEquals(List.of(TailCut.NO_CUT), r.cut().grid().stream().map(TailCut.Trial::k).toList());
    assertEquals(Double.POSITIVE_INFINITY, r.cut().fence());
    final List<String> now = new ArrayList<>();
    for (final String name : START_MODELS) {
      now.add(name + " " + Integer.toHexString(fingerprint(model(r, name)).hashCode()) + " "
          + Integer.toHexString(forestFingerprint(model(r, name).forest()).hashCode()));
    }
    assertEquals(EIGHT, now);
  }

  @Test
  @DisplayName("a parameters file without the tail cut's lines, with a k off the grid, a grid "
      + "short of a k or a usual flow at another k, is refused")
  void refusesAFileWithoutTheTailCut() {
    full();
    final List<String> lines =
        ElasticNetSelection.parameters(ElasticNetSelection.run(counted(entries), 1), "cd34");
    ElasticNetSelection.tailCut(ElasticNetSelection.read(lines));
    final List<String> none = lines.stream().filter(l -> !l.startsWith("tail-cut\t")).toList();
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.tailCut(ElasticNetSelection.read(none)));
    final List<String> shortGrid = lines.stream()
        .filter(l -> !l.startsWith("tail-cut\tk_5.0\t")).toList();
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.tailCut(ElasticNetSelection.read(shortGrid)));
    final List<String> offGrid = lines.stream().map(l -> l.startsWith("tail-cut\tchosen\tk\t")
        ? "tail-cut\tchosen\tk\t2.0" : l).toList();
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.tailCut(ElasticNetSelection.read(offGrid)));
    final List<String> usual = lines.stream().map(l -> l.startsWith("tail-cut\tusual_flow\tk\t")
        ? "tail-cut\tusual_flow\tk\t1.5" : l).toList();
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.tailCut(ElasticNetSelection.read(usual)));
    final List<String> extra = new ArrayList<>(lines);
    extra.add("tail-cut\tk_2.0\tmae_business_days\t1.0");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.tailCut(ElasticNetSelection.read(extra)));
  }

  /**
   * The selection with the k given and no other k swept, every choice scored on the validation
   * issues {@code scored} reads.
   */
  private static ElasticNetSelection.Result fixed(final List<RowCache.Entry> entries,
      final double k, final TailCut.Reading scored, final int threads) {
    assertTrue(TailCut.GRID.contains(k), "k " + k + " is not one of the grid " + TailCut.GRID);
    return ElasticNetSelection.select(entries, List.of(k), scored, threads);
  }

  @Test
  @DisplayName("the header names the split rule when it is not the random one; a header without a "
      + "rule, as every file before the temporal split, is the random one; the population is "
      + "read the same either way")
  void headerSplitRule() {
    for (final Population p : Population.values()) {
      assertEquals(ElasticNetSelection.header(p),
          ElasticNetSelection.header(p, SplitRule.RANDOM));
      assertEquals(SplitRule.RANDOM, ElasticNetSelection.headerRule(ElasticNetSelection.header(p)));
      assertEquals(p, ElasticNetSelection.headerPopulation(ElasticNetSelection.header(p)));
    }
    final String temporal = ElasticNetSelection.header(Population.FIRST_COMMIT,
        SplitRule.TEMPORAL);
    assertTrue(temporal.startsWith(ElasticNetSelection.HEADER_FIRST_COMMIT), temporal);
    assertEquals(SplitRule.TEMPORAL, ElasticNetSelection.headerRule(temporal));
    assertEquals(Population.FIRST_COMMIT, ElasticNetSelection.headerPopulation(temporal));
    // a rule that names nothing, or another header, is no header of this step
    final String odd = ElasticNetSelection.HEADER_FIRST_COMMIT
        + ElasticNetSelection.RULE_MARK + "weekly";
    assertNull(ElasticNetSelection.headerRule(odd));
    assertNull(ElasticNetSelection.headerPopulation(odd));
    assertNull(ElasticNetSelection.headerRule("# something else"));
  }

  @Test
  @DisplayName("the analogy estimate's frozen choice, its column and fallback, reads back exactly; "
      + "a file without it has none; an incomplete or unknown choice is refused")
  void analogyChoice() {
    final AnalogyEstimate.Frozen frozen = new AnalogyEstimate.Frozen("embedding_median_10", 3.0);
    final List<String> lines = new ArrayList<>(List.of(ElasticNetSelection.HEADER_FIRST_COMMIT,
        "elastic-net\tinputs\tmatrix_sha256\tcd34"));
    assertNull(ElasticNetSelection.analogy(ElasticNetSelection.read(lines)));
    lines.addAll(ElasticNetSelection.analogyLines(frozen));
    assertEquals(List.of("analogy\tchosen\tcolumn\tembedding_median_10",
        "analogy\tchosen\tfallback_business_days\t3.0"), lines.subList(2, 4));
    assertEquals(frozen, ElasticNetSelection.analogy(ElasticNetSelection.read(lines)));
    // the other readers skip the analogy's lines
    assertEquals(Map.of(), ElasticNetSelection.choices(ElasticNetSelection.read(lines)));
    assertEquals(Map.of(), ElasticNetSelection.forests(ElasticNetSelection.read(lines)));

    final List<String> partial = lines.subList(0, 3);
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.analogy(ElasticNetSelection.read(partial)));
    final List<String> extra = new ArrayList<>(lines);
    extra.add("analogy\tchosen\tmae_business_days\t1.5");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.analogy(ElasticNetSelection.read(extra)));
    assertThrows(IllegalArgumentException.class,
        () -> new AnalogyEstimate.Frozen("jaccard_median_7", 3.0));
    assertThrows(IllegalArgumentException.class,
        () -> new AnalogyEstimate.Frozen("jaccard_median_5", Double.NaN));
    assertThrows(IllegalArgumentException.class,
        () -> new AnalogyEstimate.Frozen("jaccard_median_5", 0));
  }

  @Test
  @DisplayName("the forests' tree count: absent, as in every file before the temporal split, it is "
      + Forest.TREES + "; given, it is read back exactly, and no forest's choice reads it; "
      + "a count below one is refused")
  void forestTrees() {
    final List<String> lines = new ArrayList<>(List.of(ElasticNetSelection.HEADER_FIRST_COMMIT,
        "forest\tkikas_start\tmtry_share\t" + (1.0 / 3), "forest\tkikas_start\tmtry\t1",
        "forest\tkikas_start\tnode_size\t25"));
    assertEquals(300, Forest.TREES);
    assertEquals(Forest.TREES, ElasticNetSelection.trees(ElasticNetSelection.read(lines)));
    lines.add(ElasticNetSelection.treesLine(500));
    assertEquals("forest\tevery_model\ttrees\t500", lines.getLast());
    assertEquals(500, ElasticNetSelection.trees(ElasticNetSelection.read(lines)));
    assertEquals(Set.of("kikas_start"),
        ElasticNetSelection.forests(ElasticNetSelection.read(lines)).keySet());
    final List<String> none = new ArrayList<>(lines);
    none.set(none.size() - 1, "forest\tevery_model\ttrees\t0");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.trees(ElasticNetSelection.read(none)));
    final List<String> odd = new ArrayList<>(lines);
    odd.set(odd.size() - 1, "forest\tevery_model\tdepth\t20");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.trees(ElasticNetSelection.read(odd)));
  }
}
