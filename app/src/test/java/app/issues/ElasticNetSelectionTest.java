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
 * The Elastic Net models of the description-similarity chain, each one's alpha and lambda and,
 * for the three reading the similarity medians, their n, n and k, chosen on the inner validation.
 */
class ElasticNetSelectionTest {

  private static final Combination TEN_FIVE_32 = new Combination(10, 5, 32);

  /** The three start attributes of ticket 19, in its order, the Blocker pace's inner variant. */
  private static final List<String> START = List.of("START_fix_version",
      "START_release_cycle_days", "START_blocker_pace_days_inner");

  /**
   * The own specification without the similarity medians: the ten of ticket 12, then the three of
   * ticket 19.
   */
  private static final List<String> OWN_INNER = List.of("A01_desc_length", "A03_team_size",
      "TEAM_loc_median", "TEAM_loc_sd", "TEAM_mccc_median", "TEAM_mccc_sd",
      "reporter_median_inner", "A09_exec_loc_tot", "A10_exec_mccc_tot", "A13_exec_open_load",
      "START_fix_version", "START_release_cycle_days", "START_blocker_pace_days_inner");

  private static final List<String> SIMILARITY_INNER = List.of("jaccard_median_10_inner",
      "embedding_median_5_inner", "cluster_median_32_inner");

  private static final List<String> WITHOUT = List.of("own_without_similarity",
      "alzubaidi_start", "kikas_start");

  private static final List<String> WITH = List.of("similarity_alone", "own_with_similarity",
      "union_own", "own_retrospective", "own_with_similarity_before_pressure",
      "own_retrospective_before_pressure");

  /** The six models before the retrospective one (ticket 15). */
  private static final List<String> SIX = List.of("own_without_similarity", "similarity_alone",
      "own_with_similarity", "alzubaidi_start", "kikas_start", "union_own");

  /** The ten attributes of the retrospective scenario: ticket 15's eight, then ticket 19's two. */
  private static final List<String> RETROSPECTIVE = List.of("RETRO_java_lines",
      "RETRO_mean_mccc", "RETRO_mean_nesting", "RETRO_files", "RETRO_familiarity",
      "RETRO_comments", "RETRO_participants", "RETRO_assignee_changes", "RETRO_blocker_wait",
      "RETRO_urgency_days");

  /**
   * The three models ticket 12 does not touch, as the code chose them on {@link #full}: the
   * chosen combination, columns, alpha, lambda and error as raw bits, the sweep's step, a hash of
   * every prediction's bits, and a hash of every combination's sweep as bits. First captured from
   * commit 2059600 on the hours; regenerated once by ticket 17, when the fixture moved onto
   * business days through the population, by the code of 11d6710 (main after ticket 15).
   */
  private static final List<String> BEFORE = List.of(
      "similarity_alone Combination[jaccardN=20, embeddingN=5, clusterK=32] "
          + "[jaccard_median_20_inner, embedding_median_5_inner, cluster_median_32_inner] "
          + "3fee666666666666 3f71f4e8ef7da155 11 3ff78469881149d8 974effbc 9de1ea2a",
      "alzubaidi_start null [Z08_priority] 3fee666666666666 3f027ac90ffbb6b3 11 "
          + "400b9501a993aa38 aaa13482 b62",
      "kikas_start null [K14_project_issues_90d] 3fee666666666666 3f57e2f83a2b3163 9 "
          + "400b86b507b027a6 fcb8b148 b62");

  /**
   * All six models of the start as the code chose them on {@link #full}: first captured from
   * 0869f6d, before ticket 13, on the hours; regenerated once by ticket 17 with {@link #BEFORE}.
   */
  private static final List<String> BEFORE_FORESTS = List.of(
      "own_without_similarity null [A01_desc_length, A03_team_size, TEAM_loc_median, "
          + "TEAM_loc_sd, TEAM_mccc_median, TEAM_mccc_sd, reporter_median_inner, "
          + "reporter_median_inner_ausente, A09_exec_loc_tot, A10_exec_mccc_tot, "
          + "A13_exec_open_load] 3fee666666666666 3f6f586aa3de7da9 11 4004b7ddbc073745 f5df19a7 "
          + "b62",
      BEFORE.get(0),
      "own_with_similarity Combination[jaccardN=5, embeddingN=5, clusterK=32] "
          + "[A01_desc_length, A03_team_size, TEAM_loc_median, TEAM_loc_sd, TEAM_mccc_median, "
          + "TEAM_mccc_sd, reporter_median_inner, reporter_median_inner_ausente, "
          + "A09_exec_loc_tot, A10_exec_mccc_tot, A13_exec_open_load, jaccard_median_5_inner, "
          + "embedding_median_5_inner, cluster_median_32_inner] 3fee666666666666 3f720868e0e6ede1 "
          + "11 3ff5684caf8bf0d7 2828f000 719940f1",
      BEFORE.get(1),
      BEFORE.get(2),
      "union_own Combination[jaccardN=5, embeddingN=5, clusterK=32] [Z08_priority, "
          + "K14_project_issues_90d, A01_desc_length, A03_team_size, TEAM_loc_median, "
          + "TEAM_loc_sd, TEAM_mccc_median, TEAM_mccc_sd, reporter_median_inner, "
          + "reporter_median_inner_ausente, A09_exec_loc_tot, A10_exec_mccc_tot, "
          + "A13_exec_open_load, jaccard_median_5_inner, embedding_median_5_inner, "
          + "cluster_median_32_inner] 3fee666666666666 3f720868e0e6ede1 11 3ff51fe8cd20a5a5 "
          + "8ce9d2ac d67cd950");

  /**
   * Ticket 19's five columns, the three of the start and the two of the retrospective scenario,
   * then the missing indicators of the four that have one, the Blocker pace in the inner variant:
   * what the ninth model leaves out of the retrospective one's design columns.
   */
  private static final List<String> TICKET_19 = ticket19(Augmentation.Variant.INNER);

  /** {@link #TICKET_19}, the Blocker pace and its indicator in the given variant. */
  private static List<String> ticket19(final Augmentation.Variant v) {
    final String pace = "START_blocker_pace_days" + v.suffix();
    return List.of("START_fix_version", "START_release_cycle_days", pace, "RETRO_blocker_wait",
        "RETRO_urgency_days", "START_release_cycle_days_ausente", pace + "_ausente",
        "RETRO_blocker_wait_ausente", "RETRO_urgency_days_ausente");
  }

  /** The eight models before ticket 22, in their order. */
  private static final List<String> EIGHT_MODELS = List.of("own_without_similarity",
      "similarity_alone", "own_with_similarity", "alzubaidi_start", "kikas_start", "union_own",
      "own_retrospective", "own_with_similarity_before_pressure");

  /**
   * The eight models before ticket 22 as the code chose them on {@link #full}: per model a hash of
   * its {@link #fingerprint} and of its forest's ({@link #forestFingerprint}). Captured from
   * 8edd07b (main after ticket 21).
   */
  private static final List<String> EIGHT = List.of(
      "own_without_similarity 6ab5bff6 d9f3c526",
      "similarity_alone 770ef34c b17efd7e",
      "own_with_similarity c3552547 40d1fdb",
      "alzubaidi_start d4695408 84ec1528",
      "kikas_start bdd33a7 b49f588a",
      "union_own 86fed577 1129d2e8",
      "own_retrospective 6c6a7086 db8e5bd",
      "own_with_similarity_before_pressure b8c08335 17b86ec4");

  /**
   * The ninth model as the code chose it on {@link #full}, as {@link #EIGHT} pins the others.
   * Captured from 5ff8564 (main after ticket 22), before the tail cut.
   */
  private static final String NINTH = "own_retrospective_before_pressure 64e61f67 5d9fca28";

  private static final List<String> UNTOUCHED =
      List.of("similarity_alone", "alzubaidi_start", "kikas_start");

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
    for (int i = 0; i < 60; i++) {
      final double hours = Math.exp(1 + 5 * random.nextDouble());
      final Map<String, Double> v = base(random, hours, i);
      v.putAll(retrospective(retrospective, hours, i));
      v.putAll(start(start, hours, i));
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
    for (int i = 0; i < 60; i++) {
      final double hours = Math.exp(1 + 5 * random.nextDouble());
      final Map<String, Double> v = base(random, hours, i);
      v.putAll(retrospective(retrospective, hours, i));
      v.putAll(start(start, hours, i));
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
    return names(Cell.byLabel(label, Population.IN_PROGRESS));
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
  @DisplayName("nine models, the previous own specification not among them, each reading exactly "
      + "the spec's columns, the split-dependent ones in the inner variant and the similarity "
      + "medians at the combination given; the eighth is the own specification with similarity "
      + "as before ticket 19, without the start columns, the ninth the retrospective model as "
      + "before it")
  void columnsOfEachModel() {
    final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.INNER);
    assertEquals(List.of("own_without_similarity", "similarity_alone", "own_with_similarity",
        "alzubaidi_start", "kikas_start", "union_own", "own_retrospective",
        "own_with_similarity_before_pressure", "own_retrospective_before_pressure"),
        cells.stream().map(Cell::label).toList());
    assertEquals(concat(OWN_INNER.subList(0, 10), SIMILARITY_INNER),
        names(cell(cells, "own_with_similarity_before_pressure")));
    assertEquals(ElasticNetSelection.MODELS, cells.stream().map(Cell::label).toList());
    assertEquals(OWN_INNER, names(cell(cells, "own_without_similarity")));
    assertEquals(SIMILARITY_INNER, names(cell(cells, "similarity_alone")));
    final List<String> sixteen = concat(OWN_INNER, SIMILARITY_INNER);
    assertEquals(sixteen, names(cell(cells, "own_with_similarity")));
    assertEquals(registered("ALZUBAIDI / C1"), names(cell(cells, "alzubaidi_start")));
    assertEquals(registered("KIKAS / C1"), names(cell(cells, "kikas_start")));
    // the literature families as the registered union reads them -- Kikas's body length enters
    // once, as Al-Zubaidi's title and description words -- with the own model with similarity
    final List<String> literature = concat(registered("ALZUBAIDI / C1"),
        registered("KIKAS / C1").stream().filter(n -> !n.equals("K02_body_len")).toList());
    assertEquals(concat(literature, sixteen), names(cell(cells, "union_own")));
    assertEquals(concat(sixteen, RETROSPECTIVE), names(cell(cells, "own_retrospective")));
    assertEquals(concat(concat(OWN_INNER.subList(0, 10), SIMILARITY_INNER),
        RETROSPECTIVE.subList(0, 8)), names(cell(cells, "own_retrospective_before_pressure")));
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
  @DisplayName("the ninth model is the retrospective one without ticket 19's five columns and "
      + "their missing indicators, in both variants and at every combination: no other column "
      + "lost, added or declared otherwise, the order kept")
  void ninthIsTheRetrospectiveWithoutTicket19() {
    assertEquals(9, ElasticNetSelection.MODELS.size());
    assertEquals("own_retrospective_before_pressure", ElasticNetSelection.MODELS.get(8));
    assertEquals(ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE,
        ElasticNetSelection.MODELS.get(8));
    assertTrue(ElasticNetSelection.WITH_SIMILARITY.contains("own_retrospective_before_pressure"));
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      // the Blocker pace and its indicator in the variant of the cells
      final List<String> five = ticket19(v);
      for (final Combination c : Combination.all()) {
        final List<Cell> cells = ElasticNetSelection.cells(c, v);
        final Cell retrospective = cell(cells, "own_retrospective");
        final Cell ninth = cell(cells, "own_retrospective_before_pressure");
        assertEquals(retrospective.family(), ninth.family());
        assertEquals(retrospective.scenario(), ninth.scenario());
        // the declared attributes, each as the retrospective model declares it
        assertEquals(retrospective.attributes().stream()
            .filter(a -> !five.contains(a.name())).toList(), ninth.attributes());
        // the design columns: exactly the five and their four indicators gone
        final List<String> all = design(retrospective);
        final List<String> kept = design(ninth);
        assertEquals(all.stream().filter(n -> !five.contains(n)).toList(), kept);
        final List<String> lost = new ArrayList<>(all);
        lost.removeAll(kept);
        assertEquals(new HashSet<>(five), new HashSet<>(lost));
        assertEquals(five.size(), lost.size());
        // the eighth model's design columns are the ninth's first: the same start
        final List<String> eighth = design(cell(cells, "own_with_similarity_before_pressure"));
        assertEquals(eighth, kept.subList(0, eighth.size()));
      }
    }
  }

  @Test
  @DisplayName("the three start columns of ticket 19 in every own model and the union: the fix "
      + "version flag binary and without indicator, the cycle raw and the pace log1p, both with "
      + "one; never in the similarity medians alone or the registered cells")
  void startColumns() {
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, v);
      // the Blocker pace reads the training side's durations: in the variant of the cells
      final List<String> variant = List.of("START_fix_version", "START_release_cycle_days",
          Pressure.blockerPace(v));
      for (final String name : List.of("own_without_similarity", "own_with_similarity",
          "union_own", "own_retrospective")) {
        final List<Attribute> start = cell(cells, name).attributes().stream()
            .filter(a -> variant.contains(a.name())).toList();
        assertEquals(variant, start.stream().map(Attribute::name).toList(), name);
        assertEquals(app.study.Construct.Kind.BINARY, start.get(0).kind());
        assertFalse(start.get(0).absenceIndicator());
        assertEquals(app.study.Construct.Transform.NONE, start.get(1).transform());
        assertTrue(start.get(1).absenceIndicator());
        assertEquals(app.study.Construct.Transform.LOG1P, start.get(2).transform());
        assertTrue(start.get(2).absenceIndicator());
        start.forEach(a -> assertTrue(a.atCreation(), a.name() + " is known at the start"));
      }
      for (final String name : UNTOUCHED) {
        assertTrue(cell(cells, name).attributes().stream()
            .noneMatch(a -> variant.contains(a.name())), name);
      }
    }
  }

  @Test
  @DisplayName("the retrospective model has exactly the 26 columns: the own specification with "
      + "similarity, then the ten, the counts, volumes and blocking-link wait log1p, the ratio, "
      + "means and urgency raw, a missing indicator beside the seven that can be missing; it "
      + "reads the similarity medians")
  void retrospectiveModel() {
    assertEquals(SIX, ElasticNetSelection.MODELS.subList(0, 6));
    assertEquals("own_retrospective", ElasticNetSelection.MODELS.get(6));
    assertTrue(ElasticNetSelection.WITH_SIMILARITY.contains("own_retrospective"));
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, v);
      final Cell retrospective = cell(cells, "own_retrospective");
      assertEquals(26, retrospective.attributes().size());
      assertEquals(cell(cells, "own_with_similarity").attributes(),
          retrospective.attributes().subList(0, 16));
      final List<Attribute> eight = retrospective.attributes().subList(16, 26);
      assertEquals(RETROSPECTIVE, eight.stream().map(Attribute::name).toList());
      final List<String> log = List.of("RETRO_java_lines", "RETRO_files", "RETRO_comments",
          "RETRO_participants", "RETRO_assignee_changes", "RETRO_blocker_wait");
      final List<String> missing = concat(RETROSPECTIVE.subList(0, 5),
          List.of("RETRO_blocker_wait", "RETRO_urgency_days"));
      for (final Attribute a : eight) {
        assertEquals(log.contains(a.name()) ? app.study.Construct.Transform.LOG1P
            : app.study.Construct.Transform.NONE, a.transform(), a.name());
        assertEquals(missing.contains(a.name()), a.absenceIndicator(), a.name());
        assertFalse(Attributes.all().contains(a), a.name() + " is not a registered column");
      }
    }
    // none of the start models reads any of the ten
    for (final Cell c : ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.INNER)) {
      if (!c.label().startsWith("own_retrospective")) {
        assertTrue(c.attributes().stream().noneMatch(a -> a.name().startsWith("RETRO_")),
            c.label());
      }
    }
  }

  @Test
  @DisplayName("the team size in the own models and the union is the registered column, with its "
      + "log1p and without a missing indicator, as the registered cell reads it")
  void teamSizeIsTheRegisteredColumn() {
    final Attribute registered = Cell.byLabel("OWN / C1", Population.IN_PROGRESS).attributes()
        .stream().filter(a -> a.name().equals("A03_team_size")).findFirst().orElseThrow();
    assertEquals(Attributes.byName("A03_team_size"), registered);
    assertEquals(app.study.Construct.Transform.LOG1P, registered.transform());
    assertFalse(registered.absenceIndicator());
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, v);
      for (final String name : List.of("own_without_similarity", "own_with_similarity",
          "union_own")) {
        assertEquals(1, cell(cells, name).attributes().stream().filter(registered::equals)
            .count(), name);
      }
      for (final String name : UNTOUCHED) {
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
        "A10_exec_mccc_tot", "A13_exec_open_load", "START_fix_version",
        "START_release_cycle_days", "START_blocker_pace_days_outer", "jaccard_median_10_outer",
        "embedding_median_5_outer", "cluster_median_32_outer"),
        names(cell(cells, "own_with_similarity")));
  }

  @Test
  @DisplayName("given one combination per model, each model with the similarity medians reads its "
      + "own; one missing, or one given to a model without them, is refused")
  void cellsOfOneCombinationPerModel() {
    final Map<String, Combination> each = Map.of("similarity_alone", new Combination(5, 20, 8),
        "own_with_similarity", TEN_FIVE_32, "union_own", new Combination(20, 10, 16),
        "own_retrospective", new Combination(5, 5, 16), "own_with_similarity_before_pressure",
        new Combination(20, 20, 8), "own_retrospective_before_pressure",
        new Combination(10, 20, 8));
    final List<Cell> cells = ElasticNetSelection.cells(each, Augmentation.Variant.OUTER);
    assertEquals(List.of("jaccard_median_5_outer", "embedding_median_5_outer",
        "cluster_median_16_outer"), names(cell(cells, "own_retrospective")).subList(13, 16));
    final List<Cell> atTen = ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.OUTER);
    assertEquals(List.of("jaccard_median_5_outer", "embedding_median_20_outer",
        "cluster_median_8_outer"), names(cell(cells, "similarity_alone")));
    assertEquals(cell(atTen, "own_with_similarity"), cell(cells, "own_with_similarity"));
    final List<String> union = names(cell(cells, "union_own"));
    assertEquals(List.of("jaccard_median_20_outer", "embedding_median_10_outer",
        "cluster_median_16_outer"), union.subList(union.size() - 3, union.size()));
    for (final String name : WITHOUT) {
      assertEquals(cell(atTen, name), cell(cells, name));
    }

    final Map<String, Combination> missing = new HashMap<>(each);
    missing.remove("union_own");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.cells(missing, Augmentation.Variant.INNER));
    final Map<String, Combination> extra = new HashMap<>(each);
    extra.put("own_without_similarity", TEN_FIVE_32);
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.cells(extra, Augmentation.Variant.INNER));
  }

  @Test
  @DisplayName("the similarity medians alone, Al-Zubaidi and Kikas choose, bit for bit, what they "
      + "chose before ticket 12: combination, alpha, lambda, error, predictions and every sweep")
  void unchangedModelsAsBefore() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries), 1);
    assertEquals(BEFORE, UNTOUCHED.stream().map(name -> fingerprint(model(r, name))).toList());
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
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries));
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
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries));
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
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(blanked));
    assertEquals(40, r.fitRows());
    assertEquals(20, r.validationRows());
    for (final String name : WITH) {
      final ElasticNetSelection.Model m = model(r, name);
      // the union also loses the literature columns this fixture leaves out
      assertEquals(List.of(new ElasticNetSelection.Removed(
          Augmentation.cluster(m.combination().clusterK(), Augmentation.Variant.INNER),
          "missing on all 40 inner fitting rows")), m.removed().stream()
              .filter(x -> x.column().contains("_median_")).toList(), name);
      assertFalse(m.fitted().columns().stream().anyMatch(c -> c.startsWith("cluster_")));
    }
    assertEquals(List.of(), model(r, "own_without_similarity").removed());
    assertEquals(1, model(r, "own_with_similarity").removed().size());
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
    assertTrue(model(r, "own_with_similarity").fitted().columns().stream()
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
        model(ElasticNetSelection.run(counted(entries)), "own_without_similarity").fitted()
            .columns();
    assertTrue(columns.contains("reporter_median_inner"));
    assertTrue(columns.contains("reporter_median_inner_ausente"));
    // observed on every fitting row: the indicator is constant there, and the preparation drops it
    assertFalse(columns.contains("TEAM_loc_median_ausente"));

    row("nan", 10, "TRAIN", "TRAIN", Map.of("TEAM_loc_sd", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> ElasticNetSelection.run(counted(entries)));
  }

  @Test
  @DisplayName("the two registered cells choose the alpha and lambda the registered grid does")
  void registeredCellsAsTheGrid() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries));
    final app.pipeline.IssuesGridTask.Report grid =
        new app.pipeline.IssuesGridTask(app.pipeline.port.Progress.silent()).run(counted(entries),
            Population.IN_PROGRESS);
    for (final String[] pair : new String[][] {{"alzubaidi_start", "ALZUBAIDI / C1"},
        {"kikas_start", "KIKAS / C1"}}) {
      final Fitting.Fitted registered = grid.fits().stream()
          .map(app.pipeline.IssuesGridTask.Timed::fitted)
          .filter(f -> f.cell().label().equals(pair[1])).findFirst().orElseThrow();
      final Fitting.Fitted mine = model(r, pair[0]).fitted();
      assertEquals(registered.alpha(), mine.alpha(), pair[0]);
      assertEquals(registered.lambda(), mine.lambda(), pair[0]);
      assertEquals(registered.columns(), mine.columns(), pair[0]);
      assertEquals(registered.score().mae(), mine.score().mae(), pair[0]);
    }
  }

  @Test
  @DisplayName("the parameters file holds the matrix's hash, each model's alpha and lambda, and "
      + "the chosen n, n and k of the six with the similarity medians, read back exactly")
  void parametersFile() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries));
    final List<String> lines = ElasticNetSelection.parameters(r, "cd34");
    assertEquals(ElasticNetSelection.HEADER, lines.get(0));
    final List<String> data = lines.stream().filter(l -> !l.startsWith("#")).toList();
    assertEquals("elastic-net\tinputs\tmatrix_sha256\tcd34", data.get(0));
    assertEquals(1 + 2 * 9 + 3 * 6 + 3 * 9, data.size());
    // the Elastic Net lines first, as before the forests; then three forest lines per model
    assertTrue(data.subList(0, 1 + 2 * 9 + 3 * 6).stream()
        .allMatch(l -> l.startsWith("elastic-net\t")));
    assertEquals(ElasticNetSelection.MODELS, data.subList(1 + 2 * 9 + 3 * 6, data.size()).stream()
        .map(l -> l.split("\t")[1]).distinct().toList());
    final Forest.Fitted ownForest = model(r, "own_with_similarity").forest();
    assertTrue(data.contains("forest\town_with_similarity\tmtry_share\t" + ownForest.share()));
    assertTrue(data.contains("forest\town_with_similarity\tmtry\t" + ownForest.mtry()));
    assertTrue(data.contains("forest\town_with_similarity\tnode_size\t"
        + ownForest.nodeSize()));
    assertTrue(data.stream().noneMatch(l -> l.contains("similarity_parameters")));
    final ElasticNetSelection.Model own = model(r, "own_with_similarity");
    assertTrue(data.contains("elastic-net\town_with_similarity\talpha\t" + own.fitted().alpha()));
    assertTrue(data.contains("elastic-net\town_with_similarity\tjaccard_n\t"
        + own.combination().jaccardN()));
    assertTrue(data.contains("elastic-net\town_with_similarity\tembedding_n\t"
        + own.combination().embeddingN()));
    assertTrue(data.contains("elastic-net\town_with_similarity\tcluster_k\t"
        + own.combination().clusterK()));
    assertTrue(data.stream()
        .noneMatch(l -> l.startsWith("elastic-net\town_without_similarity\tjaccard")));
    assertTrue(data.stream().noneMatch(l -> l.contains("previous_own_start")));

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
        "elastic-net\tinputs\tmatrix_sha256\tcd34", "elastic-net\tsimilarity_alone\talpha\t0.5",
        "elastic-net\tsimilarity_alone\tlambda\t0.1",
        "elastic-net\tsimilarity_alone\tjaccard_n\t10",
        "elastic-net\tsimilarity_alone\tembedding_n\t5");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.choices(ElasticNetSelection.read(head)));
    final List<String> odd = new ArrayList<>(head);
    odd.add("elastic-net\tsimilarity_alone\tcluster_k\t12");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.choices(ElasticNetSelection.read(odd)));
    final List<String> whole = new ArrayList<>(head);
    whole.add("elastic-net\tsimilarity_alone\tcluster_k\t16");
    assertEquals(new Combination(10, 5, 16), ElasticNetSelection.choices(
        ElasticNetSelection.read(whole)).get("similarity_alone").combination());

    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.read(List.of("elastic-net\tx")));
    assertThrows(IllegalArgumentException.class, () -> ElasticNetSelection.read(List.of(
        "elastic-net\tx\talpha\t0.5", "elastic-net\tx\talpha\t0.1")));
  }

  @Test
  @DisplayName("every Elastic Net model before the retrospective one chooses, bit for bit, what it "
      + "chose before the forests when the matrix lacks ticket 19's start columns, the "
      + "retrospective columns in the matrix or not: combination, columns, alpha, lambda, error, "
      + "predictions and every sweep")
  void elasticNetAsBeforeTheForests() {
    full();
    final List<RowCache.Entry> withoutStart = without(entries, START);
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(withoutStart), 1);
    assertEquals(BEFORE_FORESTS, SIX.stream().map(name -> fingerprint(model(r, name))).toList());
    assertEquals(ElasticNetSelection.MODELS, r.models().stream().map(m -> m.cell().label())
        .toList());

    final ElasticNetSelection.Result before =
        ElasticNetSelection.run(counted(without(withoutStart, RETROSPECTIVE)), 1);
    for (final String name : SIX) {
      assertEquals(fingerprint(model(before, name)), fingerprint(model(r, name)), name);
      assertEquals(java.util.Arrays.toString(model(before, name).forest().predicted()),
          java.util.Arrays.toString(model(r, name).forest().predicted()), name);
      assertEquals(model(before, name).forest().trials(), model(r, name).forest().trials(), name);
    }
  }

  @Test
  @DisplayName("the eighth model, with the start columns in the matrix, chooses bit for bit what "
      + "the own specification with similarity chose before ticket 19: combination, columns, "
      + "alpha, lambda, error, predictions and every sweep")
  void beforePressureIsTheOldStartModel() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries), 1);
    final String old = BEFORE_FORESTS.get(2);
    assertTrue(old.startsWith("own_with_similarity "), old);
    assertEquals(
        ElasticNetSelection.BEFORE_PRESSURE + old.substring("own_with_similarity".length()),
        fingerprint(model(r, ElasticNetSelection.BEFORE_PRESSURE)));
    assertNotEquals(fingerprint(model(r, "own_with_similarity")), old,
        "the new own model reads the start columns");
    // its forest is the old start model's forest: on the matrix without the start columns the
    // own model with similarity is the old one, and the two forests agree to the bit
    final Forest.Fitted before = model(ElasticNetSelection.run(counted(without(entries, START)),
        1), "own_with_similarity").forest();
    final Forest.Fitted eighth = model(r, ElasticNetSelection.BEFORE_PRESSURE).forest();
    assertEquals(before.columns(), eighth.columns());
    assertEquals(bits(before.share()), bits(eighth.share()));
    assertEquals(before.mtry(), eighth.mtry());
    assertEquals(before.nodeSize(), eighth.nodeSize());
    assertEquals(bits(before.score().mae()), bits(eighth.score().mae()));
    assertEquals(java.util.Arrays.toString(before.predicted()),
        java.util.Arrays.toString(eighth.predicted()));
    assertEquals(before.trials(), eighth.trials());
  }

  /** A forest as bits: its columns, column share, count, leaf, error, predictions and grid. */
  private static String forestFingerprint(final Forest.Fitted f) {
    return f.columns() + " " + hex(f.share()) + " " + f.mtry() + " " + f.nodeSize() + " "
        + hex(f.score().mae()) + " " + java.util.Arrays.toString(f.predicted()) + " "
        + f.trials();
  }

  @Test
  @DisplayName("the eight models before the ninth choose, bit for bit, what they chose before "
      + "it: Elastic Net (combination, columns, alpha, lambda, error, predictions, every sweep) "
      + "and forest (columns, column share, leaf, error, predictions, grid)")
  void eightAsBeforeTheNinth() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries), 1);
    final List<String> now = new ArrayList<>();
    for (final String name : EIGHT_MODELS) {
      now.add(name + " " + Integer.toHexString(fingerprint(model(r, name)).hashCode()) + " "
          + Integer.toHexString(forestFingerprint(model(r, name).forest()).hashCode()));
    }
    assertEquals(EIGHT, now);
    assertEquals(EIGHT_MODELS, ElasticNetSelection.MODELS.subList(0, 8));
  }

  @Test
  @DisplayName("the ninth model chooses, bit for bit, what it chose at ticket 22: Elastic Net and "
      + "forest")
  void ninthAsAtTicket22() {
    full();
    final ElasticNetSelection.Model m = model(ElasticNetSelection.run(counted(entries), 1),
        ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE);
    assertEquals(NINTH, m.cell().label() + " " + Integer.toHexString(fingerprint(m).hashCode())
        + " " + Integer.toHexString(forestFingerprint(m.forest()).hashCode()));
  }

  @Test
  @DisplayName("the ninth model is swept over the 27 combinations, reads none of ticket 19's five "
      + "columns nor their indicators but the other eight retrospective ones, and has its forest "
      + "on the same columns")
  void ninthChosen() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries), 1);
    final ElasticNetSelection.Model m = model(r, "own_retrospective_before_pressure");
    assertEquals(27, m.combinations().size());
    assertEquals(List.of(), m.removed());
    final List<String> columns = m.fitted().columns();
    assertTrue(columns.containsAll(RETROSPECTIVE.subList(0, 8)), columns.toString());
    assertTrue(columns.contains("RETRO_java_lines_ausente"), columns.toString());
    assertTrue(columns.stream().noneMatch(TICKET_19::contains), columns.toString());
    // nor the Blocker pace of either variant
    assertTrue(columns.stream().noneMatch(c -> c.startsWith("START_blocker_pace_days")),
        columns.toString());
    assertEquals(columns, m.forest().columns());
    // the retrospective model of the same run reads the five
    final List<String> retrospective = model(r, "own_retrospective").fitted().columns();
    assertTrue(retrospective.containsAll(TICKET_19.subList(0, 5)), retrospective.toString());
  }

  @Test
  @DisplayName("the retrospective model is swept over the 27 combinations, reads the ten beside "
      + "the sixteen, missing indicators for those that can be missing, and has its forest on the "
      + "same columns")
  void retrospectiveChosen() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries), 1);
    final ElasticNetSelection.Model m = model(r, "own_retrospective");
    assertEquals(27, m.combinations().size());
    assertEquals(List.of(), m.removed());
    final List<String> columns = m.fitted().columns();
    assertTrue(columns.containsAll(RETROSPECTIVE), columns.toString());
    assertTrue(columns.contains("RETRO_java_lines_ausente"), columns.toString());
    assertFalse(columns.contains("RETRO_comments_ausente"), columns.toString());
    assertEquals(columns, m.forest().columns());
  }

  @Test
  @DisplayName("beside each of the six models a forest is swept on its Elastic Net's columns -- "
      + "the similarity medians at the n and k the Elastic Net chose -- and the same rows, keeping "
      + "the grid point that errs least on the inner validation")
  void forestBesideEachModel() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(counted(entries));
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
      final Forest.Trial chosen = f.trials().get(Forest.chosen(f.trials()));
      assertEquals(bits(chosen.mae()), bits(f.score().mae()), name);
      assertEquals(new Forest.Choice(chosen.share(), chosen.mtry(), chosen.nodeSize()),
          new Forest.Choice(f.share(), f.mtry(), f.nodeSize()), name);
    }
    final Combination c = model(r, "own_with_similarity").combination();
    assertTrue(model(r, "own_with_similarity").forest().columns().containsAll(List.of(
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
    assertEquals(new RegistrationInProgress.Sizes(4, 2, 3, 1),
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
        BusinessDays.population(BusinessDayFixtures.bounded(entries));
    assertEquals(p.all().innerFit() - 4, p.kept().innerFit());
    assertEquals(p.all().innerSelection() - 2, p.kept().innerSelection());
    final ElasticNetSelection.Result r = ElasticNetSelection.run(p.entries(), 1);
    assertEquals(p.kept().innerFit(), r.fitRows());
    assertEquals(p.kept().innerSelection(), r.validationRows());
    final double error = model(r, "own_with_similarity").fitted().score().mae();
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
}
