package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Corpus;
import app.issues.LanguageReport;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The language report reads each in-progress issue's description as it stood at t0. */
class IssuesDescriptionLanguageTest {

  private static final long D = 86_400_000L;
  private static final long T0 = 1_000 * D;

  private static final Function<String, String> DETECTOR =
      text -> text.startsWith("Deutsch") ? "GERMAN" : LanguageReport.ENGLISH;

  private final List<Corpus.Issue> issues = new ArrayList<>();
  private final List<Corpus.Event> events = new ArrayList<>();
  private final Map<String, Corpus.Text> texts = new HashMap<>();
  private int ext = 1;

  private void issue(final String id, final Long started) {
    issues.add(new Corpus.Issue(id, "P-" + id, "p", T0, "r", "r", null, "Bug", "Major", null,
        List.of(), List.of()));
    if (started != null) {
      events.add(new Corpus.Event(id, started, "status", "d", "Open", "In Progress",
          (ext++) + "%%0"));
    }
    events.add(new Corpus.Event(id, T0 + 10 * D, "status", "d", "In Progress", "Resolved",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id, T0 + 10 * D, "resolution", "d", null, "Fixed",
        (ext - 1) + "%%1"));
  }

  /** The description now is {@code after}; it was {@code before} until {@code at}. */
  private void edited(final String id, final String before, final String after, final long at) {
    texts.put(id, new Corpus.Text("t", after,
        List.of(new Corpus.Event(id, at, "desc", "d", before, after, (ext++) + "%%0"))));
  }

  private LanguageReport run() {
    final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), List.of(),
        Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    final StudySource source = new StudySource() {
      @Override
      public Corpus load() {
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
    return new IssuesDescriptionLanguageTask(source, Progress.silent(), () -> DETECTOR).run();
  }

  @Test
  @DisplayName("an edit after the first In Progress is undone, one before it stands; issues never "
      + "In Progress are not read; an issue with no text counts as no description")
  void atFirstInProgress() {
    issue("a", T0 + D);
    edited("a", "Deutsch vorher", "english later", T0 + 2 * D);
    issue("b", T0 + 3 * D);
    edited("b", "Deutsch frueher", "english before the start", T0 + 2 * D);
    issue("c", null);
    edited("c", "Deutsch nie", "Deutsch nie", T0 + 2 * D);
    issue("d", T0 + D);

    final LanguageReport r = run();
    assertEquals(3, r.descriptions());
    assertEquals(1, r.absent());
    assertEquals(Map.of("GERMAN", 1, "ENGLISH", 1), r.languages());
    assertEquals(List.of("P-a"), r.samples().stream().map(LanguageReport.Sample::key).toList());
    assertEquals("Deutsch vorher", r.samples().get(0).cleaned());
  }

  @Test
  @DisplayName("the report's language names are Lingua's, and its stated version is the pinned one")
  void lingua() throws java.io.IOException {
    assertEquals(LanguageReport.ENGLISH, com.github.pemistahl.lingua.api.Language.ENGLISH.name());
    assertEquals(LanguageReport.UNKNOWN, com.github.pemistahl.lingua.api.Language.UNKNOWN.name());
    // Gradle runs the tests from app/; an IDE may run them from the repository root
    final java.nio.file.Path here = java.nio.file.Path.of("gradle", "libs.versions.toml");
    final String toml = java.nio.file.Files.readString(java.nio.file.Files.exists(here) ? here
        : java.nio.file.Path.of("..").resolve(here));
    assertTrue(toml.contains("lingua = \"" + IssuesDescriptionLanguageTask.LINGUA + "\""), toml);
  }
}
