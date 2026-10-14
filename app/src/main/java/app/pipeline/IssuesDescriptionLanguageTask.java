package app.pipeline;

import app.issues.Corpus;
import app.issues.Initial;
import app.issues.LanguageReport;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Which languages the in-progress population's descriptions are written in, read as they stood at
 * each issue's first In Progress. Reads only.
 *
 * <p>The corpus is opened as {@link IssuesInProgressExtractTask} opens it, and each description is
 * the one {@link Initial#at} recovers by undoing the changelog from the first In Progress on. The
 * detector is Lingua with every language it knows loaded.
 */
public final class IssuesDescriptionLanguageTask {

  /** How many non-English descriptions the report draws for reading. */
  public static final int SAMPLES = 20;
  public static final long SEED = 42;

  /**
   * The Lingua release the build pins, for the report: its jar states no version of its own. A
   * test holds it equal to {@code gradle/libs.versions.toml}.
   */
  public static final String LINGUA = "1.2.2";

  private final StudySource source;
  private final Progress progress;
  private final Supplier<Function<String, String>> detector;

  public IssuesDescriptionLanguageTask(final StudySource source, final Progress progress) {
    this(source, progress, () -> {
      final LanguageDetector lingua = LanguageDetectorBuilder.fromAllLanguages().build();
      return text -> lingua.detectLanguageOf(text).name();
    });
  }

  /** With another detector, built only once the corpus is no longer held. */
  IssuesDescriptionLanguageTask(final StudySource source, final Progress progress,
      final Supplier<Function<String, String>> detector) {
    this.source = source;
    this.progress = progress;
    this.detector = detector;
  }

  public LanguageReport run() {
    final List<LanguageReport.Description> descriptions = descriptions();
    progress.phase(3, 3, "detecting the language of %,d descriptions", descriptions.size());
    return LanguageReport.of(descriptions, detector.get(), SAMPLES, SEED);
  }

  /** Kept apart from {@link #run} so the corpus can be collected before Lingua loads its models. */
  private List<LanguageReport.Description> descriptions() {
    progress.phase(1, 3, "reading the corpus: %s", source.describe());
    final Corpus corpus = source.load();
    final Universe universe = Universe.of(corpus);
    final List<Universe.Unit> units = universe.population(Population.IN_PROGRESS);

    progress.phase(2, 3, "descriptions of %,d issues at their first In Progress", units.size());
    final Set<String> ids = new TreeSet<>();
    units.forEach(u -> ids.add(u.id()));
    final Map<String, Corpus.Text> texts = source.texts(ids);
    final List<LanguageReport.Description> out = new ArrayList<>(units.size());
    for (final Universe.Unit u : units) {
      final Initial.Values init =
          Initial.at(universe.issue(u.id()), universe.events(u.id()), texts.get(u.id()), u.start());
      out.add(new LanguageReport.Description(u.id(), u.key(), u.project(), init.description()));
    }
    return out;
  }
}
