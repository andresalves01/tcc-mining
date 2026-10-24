package app.issues;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project's releases, read from the tags of its repository (ticket 19; items 22 to 24 and 28 of
 * the retrospective decisions).
 *
 * <p><b>Matching.</b> A version name and a tag name are compared {@link #normalised}: lower case;
 * the suffixes {@code -incubating}, {@code -ga}, {@code -final} and {@code -release} dropped; a
 * textual prefix ending in {@code -}, {@code _}, a space or {@code /} dropped, and a leading {@code
 * v}; {@code _} read as {@code .}. So {@code release-1.9.0}, {@code v1.9.0} and {@code Jena 1.9.0}
 * are all {@code 1.9.0}, and {@code 1.9.0-rc1} stays apart. This is the feasibility audit's rule
 * (b), the one the decision names; step 0 of the ticket checked by hand that its false matches stay
 * under one in ten, and that they come almost all from one project whose tracker holds several
 * products (karaf-cellar).
 *
 * <p><b>Dates.</b> A tag's date is its own, or, for a lightweight tag without one, the tagged
 * commit's commit date. A version matched by several tags takes the earliest.
 *
 * <p><b>Releases.</b> A tag is a release when its normalised name is a version number of at least
 * two parts and nothing else ({@code 1.9}, {@code 10.5.1.1}): a release candidate, alpha, beta,
 * milestone, snapshot or branch name is not. Each version counts once, at its earliest tag, and
 * versions tagged at the same instant are one release, as a repository migrated with every old tag
 * on one date has one.
 */
public final class Releases {

  private static final Pattern NAME =
      Pattern.compile("^(?:[a-z][a-z0-9 ._-]*?[-_ /])?v?(\\d[0-9a-z._-]*)$");
  private static final Pattern RELEASE = Pattern.compile("^\\d+(\\.\\d+)+$");

  /** Per project, normalised name to its earliest tag date. */
  private final Map<String, Map<String, Long>> dates;

  /** Per project, the release instants, ascending. */
  private final Map<String, TreeSet<Long>> releases;

  private Releases(final Map<String, Map<String, Long>> dates,
      final Map<String, TreeSet<Long>> releases) {
    this.dates = dates;
    this.releases = releases;
  }

  /** The releases of every project of the corpus, from its tags and the tagged commits. */
  public static Releases of(final Corpus corpus) {
    final Map<String, Long> committed = new HashMap<>();
    for (final Corpus.Commit c : corpus.commits()) {
      if (c.committedAt() != null) {
        committed.put(c.id(), c.committedAt());
      }
    }
    final Map<String, Map<String, Long>> dates = new HashMap<>();
    for (final Corpus.Tag t : corpus.tags()) {
      final Long at = t.taggedAt() != null ? t.taggedAt() : committed.get(t.commit());
      if (at == null || t.project() == null || t.name() == null) {
        continue;
      }
      dates.computeIfAbsent(t.project(), k -> new HashMap<>())
          .merge(normalised(t.name()), at, Math::min);
    }
    final Map<String, TreeSet<Long>> releases = new HashMap<>();
    dates.forEach((project, byName) -> {
      final TreeSet<Long> instants = new TreeSet<>();
      byName.forEach((name, at) -> {
        if (RELEASE.matcher(name).matches()) {
          instants.add(at);
        }
      });
      releases.put(project, instants);
    });
    return new Releases(dates, releases);
  }

  /** The name as matched: see the class comment; null stays null. */
  public static String normalised(final String name) {
    if (name == null) {
      return null;
    }
    final String s = name.trim().toLowerCase(Locale.ROOT)
        .replaceAll("[-_ ]incubating$", "").replaceAll("[-._](ga|final|release)$", "");
    final Matcher m = NAME.matcher(s);
    return m.matches() ? m.group(1).replace('_', '.') : s;
  }

  /** The release date of a version of the project: its earliest matching tag's; null if none. */
  public Long date(final String project, final String version) {
    if (version == null) {
      return null;
    }
    return dates.getOrDefault(project, Map.of()).get(normalised(version));
  }

}
