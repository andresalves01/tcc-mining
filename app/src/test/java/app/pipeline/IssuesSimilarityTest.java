package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation;
import app.issues.Augmentation.Variant;
import app.issues.BusinessDays;
import app.issues.Corpus;
import app.issues.DayBoundaries;
import app.issues.Embedder;
import app.issues.Initial;
import app.issues.IssueUnit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.port.StudySource;
import app.pipeline.clustering.SmileKMeans;
import app.pipeline.port.Progress;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import app.pipeline.port.SilentProgress;

/** The similarity stage over the extracted matrix: the size check and the descriptions it reads. */
class IssuesSimilarityTest {

  private static final long D = 86_400_000L;
  private static final long H = 3_600_000L;
  private static final long T0 = 1_000 * D;

  private final List<Corpus.Issue> issues = new ArrayList<>();
  private final List<Corpus.Event> events = new ArrayList<>();
  private final Map<String, Corpus.Text> texts = new HashMap<>();
  private final List<RowCache.Entry> entries = new ArrayList<>();
  private final List<Corpus.Tag> tags = new ArrayList<>();
  private final List<Corpus.Comment> comments = new ArrayList<>();
  private final List<Corpus.Commit> commits = new ArrayList<>();
  private int ext = 1;

  private static final SmileKMeans KMEANS = new SmileKMeans();

  /**
   * The hand-assigned split taken as it is: each test sets its rows' sides; the rule's check is
   * {@link #refusesAnotherSplit}'s and {@code ProjectSplitTest}'s.
   */
  private static final Function<List<RowCache.Entry>, List<String>> HAND = e -> List.of();

  /** Every description at the same unit vector of two components. */
  private static final Embedder SAME = text -> new Embedder.Embedding(new float[] {1, 0}, 1);

  /** An issue In Progress at {@code started}, Fixed at {@code resolved}, in the given split. */
  private void issue(final String id, final long started, final long resolved,
      final String outer, final String inner) {
    issue(id, "r", started, resolved, outer, inner);
  }

  /** The same, opened by {@code reporter}. */
  private void issue(final String id, final String reporter, final long started,
      final long resolved, final String outer, final String inner) {
    issue(id, reporter, "d", started, resolved, outer, inner);
  }

  /** The same, moved to In Progress by {@code clicker}. */
  private void issue(final String id, final String reporter, final String clicker,
      final long started, final long resolved, final String outer, final String inner) {
    issues.add(new Corpus.Issue(id, "P-" + id, "p", T0, "r", "r", null, "Bug", "Major", null,
        List.of(), List.of()));
    events.add(new Corpus.Event(id, started, "status", clicker, "Open", "In Progress",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "status", "d", "In Progress", "Resolved",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "resolution", "d", null, "Fixed",
        (ext - 1) + "%%1"));
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    entries.add(new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", reporter, "d",
        Instant.ofEpochMilli(T0), Instant.ofEpochMilli(resolved), "Fixed", false,
        Instant.ofEpochMilli(started)), new Row(id, Map.of("A01_desc_length", 1.0), Set.of()),
        parts));
  }

  /** The description now is {@code after}; it was {@code before} until {@code at}. */
  private void edited(final String id, final String before, final String after, final long at) {
    texts.put(id, new Corpus.Text("t", after,
        List.of(new Corpus.Event(id, at, "desc", "d", before, after, (ext++) + "%%0"))));
  }

  private StudySource source(final boolean readable) {
    final Corpus corpus = new Corpus(issues, events, List.copyOf(comments), List.of(),
        List.copyOf(commits), Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(),
        Map.of(), Map.of(), tags);
    return new StudySource() {
      @Override
      public Corpus load() {
        if (!readable) {
          throw new AssertionError("the corpus was read before the sizes were checked");
        }
        return corpus;
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        final Map<String, Corpus.Text> out = new HashMap<>();
        ids.stream().filter(texts::containsKey).forEach(id -> out.put(id, texts.get(id)));
        return out;
      }


      @Override
      public String describe() {
        return "in memory";
      }
    };
  }

  @Test
  @DisplayName("a split other than its rule's refuses the run before the corpus is read; the "
      + "rule's own passes")
  void refusesAnotherSplit() {
    for (int k = 0; k < 6; k++) {
      issue("i" + k, T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    }
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(false),
        SilentProgress.INSTANCE, ProjectSplit::differences, SAME, 2, KMEANS).run(entries);
    assertEquals(1, r.refused().size());
    assertTrue(r.refused().get(0).contains("not at the verdicts of the split's rule"),
        r.refused().get(0));
    assertTrue(r.entries().isEmpty());

    // the same issues at the rule's verdicts go on to the corpus
    final ProjectSplit.Parts parts = ProjectSplit.draw(Map.of("i0", "p", "i1", "p", "i2", "p",
        "i3", "p", "i4", "p", "i5", "p"));
    entries.replaceAll(e -> {
      final String id = e.unit().issueId();
      final Universe.Part inner = parts.inner().get(id);
      final Map<Population, String[]> drawn = new EnumMap<>(Population.class);
      drawn.put(Population.IN_PROGRESS,
          new String[] {parts.outer().get(id).name(), inner == null ? "" : inner.name()});
      return new RowCache.Entry(e.unit(), e.row(), drawn);
    });
    assertThrows(AssertionError.class, () -> new IssuesSimilarityTask(source(false),
        SilentProgress.INSTANCE, ProjectSplit::differences, SAME, 2, KMEANS).run(entries),
        "the split passed, so the corpus was read");
  }

  @Test
  @DisplayName("the description read is the one at the first In Progress; the new columns join the "
      + "old ones, and an issue without a description stays with them missing")
  void augments() {
    issue("a", T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    edited("a", "alpha beta", "alpha beta", T0);
    issue("b", T0 + 2 * D, T0 + 11 * D, "TRAIN", "TRAIN");
    edited("b", "gamma", "gamma", T0);
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    edited("v", "alpha", "gamma", T0 + 25 * D);
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");

    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of(), r.refused());
    assertEquals(4, r.entries().size());
    final Map<String, Row> rows = new HashMap<>();
    r.entries().forEach(e -> rows.put(e.unit().issueId(), e.row()));
    assertEquals(7.0, rows.get("v").values().get(Augmentation.jaccard(5, Variant.INNER)),
        "a, by the text before the later edit: Thursday 00:00, a Wednesday date at 04:00, to "
            + "the next Saturday 00:00, a Friday date");
    assertEquals(1.0, rows.get("v").values().get("A01_desc_length"));
    assertFalse(rows.get("t").values().containsKey(Augmentation.jaccard(5, Variant.OUTER)));
    assertTrue(rows.get("t").absent().contains(Augmentation.jaccard(5, Variant.OUTER)));
    assertEquals(3, r.described());
  }

  @Test
  @DisplayName("the three start columns of ticket 19 join every row, read at each issue's first In "
      + "Progress; a missing one recorded as absent")
  void startColumns() {
    issue("a", T0 + 40 * D, T0 + 50 * D, "TRAIN", "TRAIN");
    events.add(new Corpus.Event("a", T0 + 39 * D, "fix_versions", "d", null, "1.0",
        (ext++) + "%%0"));
    issue("b", T0 + 2 * D, T0 + 11 * D, "TRAIN", "TRAIN");
    events.add(new Corpus.Event("b", T0 + 2 * D, "fix_versions", "d", null, "1.0",
        (ext++) + "%%0"));
    issue("v", T0 + 60 * D, T0 + 62 * D, "TRAIN", "TEST");
    issue("t", T0 + 70 * D, T0 + 71 * D, "TEST", "");
    tags.add(new Corpus.Tag("p", "release-1.0", T0 + 10 * D, null));
    tags.add(new Corpus.Tag("p", "release-1.1", T0 + 20 * D, null));
    tags.add(new Corpus.Tag("p", "release-1.2", T0 + 50 * D, null));

    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of(), r.refused());
    final Map<String, Row> rows = new HashMap<>();
    r.entries().forEach(e -> rows.put(e.unit().issueId(), e.row()));
    // The old start-pressure attributes are no longer written by this stage.
    for (final Row row : rows.values()) {
      assertFalse(row.values().keySet().stream().anyMatch(n -> n.startsWith("START_")));
      assertFalse(row.absent().stream().anyMatch(n -> n.startsWith("START_")));
    }
    // the fix versions' fidelity, on the outer training rows a, b and v only: the record lacks
    // the version a and b got, so neither chain is explained, and t is not counted
    assertEquals(new Initial.Fidelity("fix_versions", 3, 2, 0, 0), r.fixVersions());
  }

  @Test
  @DisplayName("the embedder reads the description at the first In Progress, never a later one; "
      + "the embedding columns join the others and each description's chunk count is kept")
  void embeds() {
    issue("a", T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    edited("a", "alpha beta", "alpha beta", T0);
    issue("b", T0 + 2 * D, T0 + 11 * D, "TRAIN", "TRAIN");
    edited("b", "gamma", "gamma", T0);
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    edited("v", "alpha", "gamma", T0 + 25 * D);
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");
    final Map<String, float[]> hand = Map.of("alpha", new float[] {1, 0},
        "alpha beta", new float[] {0.6f, 0.8f}, "gamma", new float[] {0, 1});
    final List<String> asked = new ArrayList<>();
    final Embedder embedder = text -> {
      asked.add(text);
      return new Embedder.Embedding(hand.get(text), text.length());
    };
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, embedder, 2, KMEANS)
        .run(entries);
    final Map<String, Row> rows = new HashMap<>();
    r.entries().forEach(e -> rows.put(e.unit().issueId(), e.row()));
    assertEquals(7.0, rows.get("v").values().get(Augmentation.embedding(5, Variant.INNER)),
        "a, by the text before the later edit; b is orthogonal");
    assertTrue(rows.get("t").absent().contains(Augmentation.embedding(5, Variant.OUTER)),
        "no description");
    assertEquals(Set.of("alpha beta", "gamma", "alpha"), Set.copyOf(asked));
    assertEquals(List.of(new Augmentation.Embedded("a", 10, null),
        new Augmentation.Embedded("b", 5, null), new Augmentation.Embedded("v", 5, null)),
        r.result().embedded());
  }

  @Test
  @DisplayName("the reporter median reads the canonical reporter of each row; with no earlier issue "
      + "of that reporter it is missing and the row stays")
  void reporterMedian() {
    issue("a", "r", T0 + D, T0 + 2 * D, "TRAIN", "TRAIN");
    issue("b", "s", T0 + D, T0 + 4 * D, "TRAIN", "TRAIN");
    issue("v", "s", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    issue("t", "z", T0 + 30 * D, T0 + 31 * D, "TEST", "");
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS)
        .run(entries);
    final Map<String, Row> rows = new HashMap<>();
    r.entries().forEach(e -> rows.put(e.unit().issueId(), e.row()));
    assertEquals(2.0, rows.get("v").values().get(Augmentation.reporter(Variant.INNER)),
        "b, Wednesday's date to Saturday's: Thursday and Friday");
    assertEquals(2.0, rows.get("v").values().get(Augmentation.reporter(Variant.OUTER)), "b");
    assertTrue(rows.get("t").absent().contains(Augmentation.reporter(Variant.OUTER)));
    assertEquals(4, r.entries().size());
  }

  @Test
  @DisplayName("the medians read past business days, each past issue at its own day boundary; the "
      + "boundary goes to the matrix")
  void businessDays() {
    // T0 is a Wednesday at 00:00 UTC. Ana was active from 00:00 to 15:59 on an earlier day, on
    // another issue: her quietest eight hours are 16:00 to 23:59, her dates turn at 20:00.
    issues.add(new Corpus.Issue("w", "P-w", "p", T0 - 10 * D, "r", "r", null, "Bug", "Major",
        null, List.of(), List.of()));
    for (int h = 0; h < 16; h++) {
      events.add(new Corpus.Event("w", T0 - 5 * D + h * H, "labels", "ana", null, "x",
          (ext++) + "%%0"));
    }
    // Thursday 19:00 is Wednesday's date at 20:00, Friday 21:00 is Friday's: two business days,
    // where 04:00 would give one
    issue("a", "r", "ana", T0 + D + 19 * H, T0 + 2 * D + 21 * H, "TRAIN", "TRAIN");
    edited("a", "alpha", "alpha", T0);
    // the clicker d has no history to speak of: 04:00; Thursday 10:00 to Tuesday 10:00, three
    issue("b", "r", "d", T0 + D + 10 * H, T0 + 6 * D + 10 * H, "TRAIN", "TRAIN");
    edited("b", "alpha", "alpha", T0);
    issue("v", "r", "d", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    edited("v", "alpha", "alpha", T0);
    issue("t", "r", "d", T0 + 30 * D, T0 + 31 * D, "TEST", "");

    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of(), r.refused());
    final Map<String, Row> rows = new HashMap<>();
    r.entries().forEach(e -> rows.put(e.unit().issueId(), e.row()));
    final Map<String, Double> v = rows.get("v").values();
    assertEquals(2.5, v.get(Augmentation.reporter(Variant.INNER)), "a's two and b's three");
    assertEquals(2.5, v.get(Augmentation.jaccard(5, Variant.INNER)));
    assertEquals(2.5, v.get(Augmentation.embedding(5, Variant.INNER)));
    assertEquals(2.5, v.get(Augmentation.reporter(Variant.OUTER)));
    assertEquals(20.0, rows.get("a").values().get(BusinessDays.BOUNDARY));
    assertEquals(4.0, rows.get("b").values().get(BusinessDays.BOUNDARY));
    assertEquals(DayBoundaries.Source.PERSON, r.boundaries().get("a").source());
    assertEquals(DayBoundaries.Source.FALLBACK, r.boundaries().get("b").source());
  }

  /** A status change of {@code id} to {@code status}, by d, at {@code at}. */
  private void status(final String id, final long at, final String status) {
    events.add(new Corpus.Event(id, at, "status", "d", null, status, (ext++) + "%%0"));
  }

  @Test
  @DisplayName("the time back in Open or Reopened is taken out of each issue's business days, "
      + "written to the matrix, and of every past issue the medians read")
  void activeTime() {
    // T0 is a Wednesday at 00:00 UTC; every boundary falls back to 04:00. a: Thursday 10:00 to
    // the next Wednesday 10:00, 4 business days, back in Open from Friday 10:00 to Tuesday 10:00:
    // 2 active
    issue("a", T0 + D + 10 * H, T0 + 7 * D + 10 * H, "TRAIN", "TRAIN");
    status("a", T0 + 2 * D + 10 * H, "Open");
    status("a", T0 + 6 * D + 10 * H, "In Progress");
    edited("a", "alpha", "alpha", T0);
    // c: in Patch Available from Thursday to Tuesday, which stays: 4
    issue("c", T0 + D + 10 * H, T0 + 7 * D + 10 * H, "TRAIN", "TRAIN");
    status("c", T0 + 2 * D + 10 * H, "Patch Available");
    edited("c", "beta", "beta", T0);
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    edited("v", "alpha", "alpha", T0);
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");

    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of(), r.refused());
    final Map<String, Row> rows = new HashMap<>();
    r.entries().forEach(e -> rows.put(e.unit().issueId(), e.row()));
    assertEquals(2.0, rows.get("a").values().get(BusinessDays.ACTIVE));
    assertEquals(4.0, rows.get("c").values().get(BusinessDays.ACTIVE));
    assertEquals(1.0, rows.get("v").values().get(BusinessDays.ACTIVE));
    for (final Row row : rows.values()) {
      assertTrue(BusinessDays.LEGACY_RECORDED_COLUMNS.stream().noneMatch(row.values()::containsKey));
    }
    final Map<String, Double> v = rows.get("v").values();
    assertEquals(3.0, v.get(Augmentation.reporter(Variant.INNER)),
        "a has 2 and c has 4; the silent review days remain");
    assertEquals(3.0, v.get(Augmentation.reporter(Variant.OUTER)));
    assertEquals(3.0, v.get(Augmentation.embedding(5, Variant.INNER)));
    assertEquals(2.0, v.get(Augmentation.jaccard(5, Variant.INNER)),
        "a, the only one sharing a word with it");
  }

  /** Four issues, Thursday 10:00 to the next Thursday 10:00, 5 business days each. */
  private void week() {
    final long start = T0 + D + 10 * H;
    final long end = T0 + 8 * D + 10 * H;
    issue("f1", start, end, "TRAIN", "TRAIN");
    edited("f1", "alpha", "alpha", T0);
    issue("f2", start, end, "TRAIN", "TRAIN");
    edited("f2", "alpha", "alpha", T0);
    issue("v", start, end, "TRAIN", "TEST");
    edited("v", "alpha", "alpha", T0);
    issue("t", start, end, "TEST", "");
    // f1: a comment Friday 12:00 and a commit authored Tuesday 12:00, committed Wednesday 12:00:
    // gaps 1, 2, 1 and 1; f2 without a record, one gap of 5
    comments.add(new Corpus.Comment("f1", "x", T0 + 2 * D + 12 * H, 3, false));
    commits.add(new Corpus.Commit("c1", "p", "h1", "coder", T0 + 6 * D + 12 * H,
        T0 + 7 * D + 12 * H, 1, List.of("f1")));
  }

  private IssuesSimilarityTask.Report run() {
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS).run(entries);
    assertEquals(List.of(), r.refused());
    return r;
  }

  private static Map<String, Double> column(final IssuesSimilarityTask.Report r,
      final String column) {
    final Map<String, Double> out = new HashMap<>();
    r.entries().forEach(e -> out.put(e.unit().issueId(), e.row().values().get(column)));
    return out;
  }

  @Test
  @DisplayName("all five status-adjusted days remain in similarity histories even without records")
  void silentDaysRemain() {
    week();
    final var before = run();
    assertEquals(5.0, column(before, BusinessDays.ACTIVE).get("f2"));
    assertEquals(5.0, column(before, Augmentation.reporter(Variant.INNER)).get("v"));
    assertEquals(5.0, column(before, Augmentation.jaccard(5, Variant.INNER)).get("v"));
    for (int k = 0; k < 4; k++) {
      comments.add(new Corpus.Comment("v", "x", T0 + (2 + k) * D + 12 * H, 3, false));
      comments.add(new Corpus.Comment("t", "x", T0 + (2 + k) * D + 12 * H, 3, false));
    }
    final var after = run();
    assertEquals(column(before, BusinessDays.ACTIVE), column(after, BusinessDays.ACTIVE));
    assertEquals(column(before, Augmentation.reporter(Variant.INNER)),
        column(after, Augmentation.reporter(Variant.INNER)));
    for (int k = 0; k < entries.size(); k++) {
      assertEquals(entries.get(k).parts().get(Population.IN_PROGRESS)[0],
          after.entries().get(k).outer(Population.IN_PROGRESS));
      assertEquals(entries.get(k).parts().get(Population.IN_PROGRESS)[1],
          after.entries().get(k).inner(Population.IN_PROGRESS));
    }
  }

  @Test
  @DisplayName("a row of 0 active business days refuses the run: the extraction draws the split "
      + "over the issues of at least one, so such a matrix was extracted before ticket 21")
  void refusesZeroBusinessDays() {
    issue("a", T0 + D + 10 * H, T0 + 7 * D + 10 * H, "TRAIN", "TRAIN");
    // b: Thursday 10:00 to Tuesday 12:00, in Reopened from Thursday 12:00 to Tuesday 10:00: none
    // active
    issue("b", T0 + D + 10 * H, T0 + 6 * D + 12 * H, "TRAIN", "TRAIN");
    status("b", T0 + D + 12 * H, "Reopened");
    status("b", T0 + 6 * D + 10 * H, "In Progress");
    // z: resolved the same working date it started
    issue("z", T0 + D + 11 * H, T0 + D + 18 * H, "TRAIN", "TEST");
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS).run(entries);
    assertEquals(1, r.refused().size(), r.refused().toString());
    assertTrue(r.refused().get(0).startsWith("2 issues of the matrix have 0 active business days"),
        r.refused().get(0));
    assertTrue(r.entries().isEmpty());
  }

  @Test
  @DisplayName("a corpus missing an issue of the matrix refuses the run rather than reading it as "
      + "having no description")
  void refusesOtherCorpus() {
    issue("a", T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");
    issues.remove(1);
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of("1 issues of the matrix are not in the corpus, the first v"),
        r.refused());
    assertTrue(r.entries().isEmpty());
  }

  @Test
  @DisplayName("an issue the corpus holds but not as the matrix's In-Progress issue refuses the "
      + "run: its start columns could not be read at its first In Progress")
  void refusesAnIssueNotStartedAsInTheMatrix() {
    issue("a", T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");
    events.removeIf(e -> e.issue().equals("v") && "In Progress".equals(e.newValue()));
    assertRefusedOn("v");
  }

  @Test
  @DisplayName("an issue whose first In Progress in the corpus is not the matrix's refuses the run")
  void refusesAnotherStart() {
    issue("a", T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");
    events.replaceAll(e -> e.issue().equals("a") && "In Progress".equals(e.newValue())
        ? new Corpus.Event("a", T0 + 2 * D, e.field(), e.author(), e.oldValue(), e.newValue(),
            e.external()) : e);
    assertRefusedOn("a");
  }

  @Test
  @DisplayName("an issue whose first resolution in the corpus is not the matrix's refuses the run")
  void refusesAnotherResolution() {
    issue("a", T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");
    events.replaceAll(e -> e.issue().equals("t") && "Resolved".equals(e.newValue())
        ? new Corpus.Event("t", T0 + 32 * D, e.field(), e.author(), e.oldValue(), e.newValue(),
            e.external()) : e);
    assertRefusedOn("t");
  }

  private void assertRefusedOn(final String id) {
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        SilentProgress.INSTANCE, HAND, SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of("1 issues of the matrix are not In-Progress issues of the corpus with "
        + "the same first In Progress and first resolution, the first " + id), r.refused());
    assertTrue(r.entries().isEmpty());
  }
}
