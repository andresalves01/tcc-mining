package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation;
import app.issues.Augmentation.Variant;
import app.issues.BusinessDays;
import app.issues.Corpus;
import app.issues.DayBoundaries;
import app.issues.Embedder;
import app.issues.IssueUnit;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The similarity stage over the extracted matrix: the size check and the descriptions it reads. */
class IssuesSimilarityTest {

  private static final long D = 86_400_000L;
  private static final long H = 3_600_000L;
  private static final long T0 = 1_000 * D;

  private final List<Corpus.Issue> issues = new ArrayList<>();
  private final List<Corpus.Event> events = new ArrayList<>();
  private final Map<String, Corpus.Text> texts = new HashMap<>();
  private final List<RowCache.Entry> entries = new ArrayList<>();
  private int ext = 1;

  private static final SmileKMeans KMEANS = new SmileKMeans();

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
    final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), List.of(),
        Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
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
      public Set<String> reads() {
        return Set.of();
      }

      @Override
      public String describe() {
        return "in memory";
      }
    };
  }

  @Test
  @DisplayName("a split size other than the expected one refuses the run before the corpus is read")
  void refusesOtherSizes() {
    issue("a", T0 + D, T0 + 10 * D, "TRAIN", "TRAIN");
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(false),
        Progress.silent(), new RegistrationInProgress.Sizes(2, 1, 1, 1), SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of("outer test: 0 rows, expected 1"), r.refused());
    assertTrue(r.entries().isEmpty());
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
    issue("x", T0 + 3 * D, T0 + 40 * D, "CROSSES", "");

    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        Progress.silent(), new RegistrationInProgress.Sizes(3, 1, 2, 1), SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of(), r.refused());
    assertEquals(5, r.entries().size());
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
        Progress.silent(), new RegistrationInProgress.Sizes(3, 1, 2, 1), embedder, 2, KMEANS)
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
        Progress.silent(), new RegistrationInProgress.Sizes(3, 1, 2, 1), SAME, 2, KMEANS)
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
  @DisplayName("the medians read past business days, each past issue at its own day boundary; a "
      + "past issue of 0 business days enters no history; the boundary goes to the matrix")
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
    // the same date: 0, and in no history
    issue("z", "r", "d", T0 + D + 11 * H, T0 + D + 18 * H, "TRAIN", "TRAIN");
    edited("z", "alpha", "alpha", T0);
    issue("v", "r", "d", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    edited("v", "alpha", "alpha", T0);
    issue("t", "r", "d", T0 + 30 * D, T0 + 31 * D, "TEST", "");

    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        Progress.silent(), new RegistrationInProgress.Sizes(4, 1, 3, 1), SAME, 2, KMEANS)
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
      + "written to the matrix, and of every past issue the medians read; a past issue brought "
      + "under one business day by it enters no history")
  void activeTime() {
    // T0 is a Wednesday at 00:00 UTC; every boundary falls back to 04:00. a: Thursday 10:00 to
    // the next Wednesday 10:00, 4 business days, back in Open from Friday 10:00 to Tuesday 10:00:
    // 2 active
    issue("a", T0 + D + 10 * H, T0 + 7 * D + 10 * H, "TRAIN", "TRAIN");
    status("a", T0 + 2 * D + 10 * H, "Open");
    status("a", T0 + 6 * D + 10 * H, "In Progress");
    edited("a", "alpha", "alpha", T0);
    // b: Thursday 10:00 to Tuesday 12:00, 3 business days, in Reopened from Thursday 12:00 to
    // Tuesday 10:00: none active, so in no history
    issue("b", T0 + D + 10 * H, T0 + 6 * D + 12 * H, "TRAIN", "TRAIN");
    status("b", T0 + D + 12 * H, "Reopened");
    status("b", T0 + 6 * D + 10 * H, "In Progress");
    edited("b", "alpha", "alpha", T0);
    // c: in Patch Available from Thursday to Tuesday, which stays: 4
    issue("c", T0 + D + 10 * H, T0 + 7 * D + 10 * H, "TRAIN", "TRAIN");
    status("c", T0 + 2 * D + 10 * H, "Patch Available");
    edited("c", "beta", "beta", T0);
    issue("v", T0 + 20 * D, T0 + 21 * D, "TRAIN", "TEST");
    edited("v", "alpha", "alpha", T0);
    issue("t", T0 + 30 * D, T0 + 31 * D, "TEST", "");

    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask(source(true),
        Progress.silent(), new RegistrationInProgress.Sizes(4, 1, 3, 1), SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of(), r.refused());
    final Map<String, Row> rows = new HashMap<>();
    r.entries().forEach(e -> rows.put(e.unit().issueId(), e.row()));
    assertEquals(2.0, rows.get("a").values().get(BusinessDays.ACTIVE));
    assertEquals(0.0, rows.get("b").values().get(BusinessDays.ACTIVE));
    assertEquals(4.0, rows.get("c").values().get(BusinessDays.ACTIVE));
    assertEquals(1.0, rows.get("v").values().get(BusinessDays.ACTIVE));
    final Map<String, Double> v = rows.get("v").values();
    assertEquals(3.0, v.get(Augmentation.reporter(Variant.INNER)),
        "a's two and c's four; b, of none, left out (whole intervals: 4, 3 and 4)");
    assertEquals(3.0, v.get(Augmentation.reporter(Variant.OUTER)));
    assertEquals(3.0, v.get(Augmentation.embedding(5, Variant.INNER)));
    assertEquals(2.0, v.get(Augmentation.jaccard(5, Variant.INNER)),
        "a, the only one sharing a word with it left in the history");
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
        Progress.silent(), new RegistrationInProgress.Sizes(2, 1, 1, 1), SAME, 2, KMEANS)
        .run(entries);
    assertEquals(List.of("1 issues of the matrix are not in the corpus, the first v"),
        r.refused());
    assertTrue(r.entries().isEmpty());
  }
}
