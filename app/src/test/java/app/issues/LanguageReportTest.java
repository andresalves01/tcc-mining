package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The language report over descriptions, with a detector that reads a marker word. */
class LanguageReportTest {

  /** "Deutsch ..." is German, "Francais ..." French, an empty text unknown, the rest English. */
  private static final Function<String, String> DETECTOR = text -> text.startsWith("Deutsch")
      ? "GERMAN" : text.startsWith("Francais") ? "FRENCH"
      : text.startsWith("???") ? LanguageReport.UNKNOWN : LanguageReport.ENGLISH;

  private static LanguageReport.Description d(final String id, final String raw) {
    return new LanguageReport.Description(id, "K-" + id, "p", raw);
  }

  @Test
  @DisplayName("counts per language, descriptions absent and descriptions that are only code")
  void counts() {
    final LanguageReport r = LanguageReport.of(List.of(d("1", "Deutsch eins"),
        d("2", "plain words"), d("3", null), d("4", "  "), d("5", "{code}x{code}"),
        d("6", "Francais un"), d("7", "Deutsch zwei"), d("8", "???")), DETECTOR, 20, 42);
    assertEquals(8, r.descriptions());
    assertEquals(2, r.absent());
    assertEquals(1, r.onlyCode());
    assertEquals(Map.of("GERMAN", 2, "ENGLISH", 1, "FRENCH", 1, LanguageReport.UNKNOWN, 1),
        r.languages());
    assertEquals(List.of("GERMAN", "ENGLISH", "FRENCH", LanguageReport.UNKNOWN),
        List.copyOf(r.languages().keySet()), "most frequent first, ties by name");
  }

  @Test
  @DisplayName("the detector never sees code; the sample shows the cleaned text with its code")
  void codeOnlyHiddenFromDetection() {
    final LanguageReport r = LanguageReport.of(
        List.of(d("1", "{code}Deutsch im Code{code} plain words"), d("2", "Deutsch *fett* {code}"
            + "int x;{code}")), DETECTOR, 20, 42);
    assertEquals(Map.of("ENGLISH", 1, "GERMAN", 1), r.languages());
    assertEquals(1, r.samples().size());
    assertEquals("Deutsch fett int x;", r.samples().get(0).cleaned());
    assertEquals("GERMAN", r.samples().get(0).language());
  }

  @Test
  @DisplayName("samples: only non-English, at most n, the same for a seed whatever the input order")
  void samples() {
    final List<LanguageReport.Description> in = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      in.add(d("g" + i, "Deutsch " + i));
      in.add(d("e" + i, "words " + i));
      in.add(d("u" + i, "???"));
    }
    final LanguageReport a = LanguageReport.of(in, DETECTOR, 20, 42);
    Collections.reverse(in);
    final LanguageReport b = LanguageReport.of(in, DETECTOR, 20, 42);
    assertEquals(20, a.samples().size());
    assertTrue(a.samples().stream().allMatch(s -> s.language().equals("GERMAN")));
    assertEquals(a.samples(), b.samples());
    final LanguageReport c = LanguageReport.of(in, DETECTOR, 20, 7);
    assertTrue(!a.samples().equals(c.samples()), "another seed draws another sample");
  }

  @Test
  @DisplayName("fewer non-English descriptions than asked: all of them")
  void fewer() {
    final LanguageReport r = LanguageReport.of(List.of(d("1", "Deutsch a"), d("2", "Francais b"),
        d("3", "english")), DETECTOR, 20, 42);
    assertEquals(2, r.samples().size());
  }
}
