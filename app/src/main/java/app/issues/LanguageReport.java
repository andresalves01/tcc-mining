package app.issues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Which languages the descriptions are written in, to state the English-only encoder as a
 * limitation with numbers. Changes nothing: it reads descriptions and returns counts.
 *
 * <p>The detector reads {@link DescriptionCleaner#forDetection}, the cleaned text without code and
 * noformat blocks, so a stack trace does not vote; the samples show {@link DescriptionCleaner#clean},
 * what the similarity methods will read. A description with nothing left once its code is removed
 * is counted apart and never reaches the detector.
 *
 * @param descriptions every description handed in, absent ones included
 * @param absent no description, or one that cleans to nothing
 * @param onlyCode a description that was nothing but code or noformat blocks
 * @param languages detected language to count, most frequent first, ties by name
 * @param samples non-English descriptions drawn with a fixed seed, {@link #UNKNOWN} excluded
 */
public record LanguageReport(int descriptions, int absent, int onlyCode,
    Map<String, Integer> languages, List<Sample> samples) {

  public static final String ENGLISH = "ENGLISH";
  /** What a detector answers when it cannot tell; not a language, so never sampled. */
  public static final String UNKNOWN = "UNKNOWN";

  /** One issue's description as it stood at its first In Progress, raw. */
  public record Description(String id, String key, String project, String raw) {}

  public record Sample(String id, String key, String project, String language, String cleaned) {}

  /** A non-English description before the draw; only the drawn ones are cleaned in full. */
  private record Detected(Description description, String language) {}

  public static LanguageReport of(final List<Description> in,
      final Function<String, String> detector, final int samples, final long seed) {
    int absent = 0;
    int onlyCode = 0;
    final Map<String, Integer> counts = new HashMap<>();
    final List<Detected> foreign = new ArrayList<>();
    for (final Description d : in) {
      final String text = DescriptionCleaner.forDetection(d.raw());
      if (text == null) {
        if (DescriptionCleaner.clean(d.raw()) == null) {
          absent++;
        } else {
          onlyCode++;
        }
        continue;
      }
      final String language = detector.apply(text);
      counts.merge(language, 1, Integer::sum);
      if (!ENGLISH.equals(language) && !UNKNOWN.equals(language)) {
        foreign.add(new Detected(d, language));
      }
    }
    // The draw depends on the seed and the set of issues, never on the order they came in.
    foreign.sort(Comparator.comparing(x -> x.description().id()));
    Collections.shuffle(foreign, new Random(seed));
    final List<Sample> drawn = foreign.subList(0, Math.min(samples, foreign.size())).stream()
        .map(x -> new Sample(x.description().id(), x.description().key(),
            x.description().project(), x.language(), DescriptionCleaner.clean(x.description().raw())))
        .toList();
    final Map<String, Integer> languages = new LinkedHashMap<>();
    counts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
            .thenComparing(Map.Entry.comparingByKey()))
        .forEach(e -> languages.put(e.getKey(), e.getValue()));
    return new LanguageReport(in.size(), absent, onlyCode, Collections.unmodifiableMap(languages),
        drawn);
  }
}
