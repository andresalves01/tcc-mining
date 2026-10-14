package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation;
import app.issues.Augmentation.Variant;
import app.issues.Corpus;
import app.issues.IssueUnit;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.port.StudySource;
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
  private static final long T0 = 1_000 * D;

  private final List<Corpus.Issue> issues = new ArrayList<>();
  private final List<Corpus.Event> events = new ArrayList<>();
  private final Map<String, Corpus.Text> texts = new HashMap<>();
  private final List<RowCache.Entry> entries = new ArrayList<>();
  private int ext = 1;

  /** An issue In Progress at {@code started}, Fixed at {@code resolved}, in the given split. */
  private void issue(final String id, final long started, final long resolved,
      final String outer, final String inner) {
    issues.add(new Corpus.Issue(id, "P-" + id, "p", T0, "r", "r", null, "Bug", "Major", null,
        List.of(), List.of()));
    events.add(new Corpus.Event(id, started, "status", "d", "Open", "In Progress",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "status", "d", "In Progress", "Resolved",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, resolved, "resolution", "d", null, "Fixed",
        (ext - 1) + "%%1"));
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    entries.add(new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "d",
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
        Progress.silent(), new RegistrationInProgress.Sizes(2, 1, 1, 1)).run(entries);
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
        Progress.silent(), new RegistrationInProgress.Sizes(3, 1, 2, 1)).run(entries);
    assertEquals(List.of(), r.refused());
    assertEquals(5, r.entries().size());
    final Map<String, Row> rows = new HashMap<>();
    r.entries().forEach(e -> rows.put(e.unit().issueId(), e.row()));
    assertEquals(216.0, rows.get("v").values().get(Augmentation.jaccard(5, Variant.INNER)),
        "a, by the text before the later edit");
    assertEquals(1.0, rows.get("v").values().get("A01_desc_length"));
    assertFalse(rows.get("t").values().containsKey(Augmentation.jaccard(5, Variant.OUTER)));
    assertTrue(rows.get("t").absent().contains(Augmentation.jaccard(5, Variant.OUTER)));
    assertEquals(3, r.described());
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
        Progress.silent(), new RegistrationInProgress.Sizes(2, 1, 1, 1)).run(entries);
    assertEquals(List.of("1 issues of the matrix are not in the corpus, the first v"),
        r.refused());
    assertTrue(r.entries().isEmpty());
  }
}
