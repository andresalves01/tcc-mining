package app.issues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What an issue said about itself at creation, recovered by reversing its changelog.
 *
 * <p>The rule of the Python changelog script, in <b>strict t0</b>
 * (decision 10): every recorded change is undone, the ones stamped at the creation instant too --
 * the value at creation is the one before any edit. Scalars take the old value of their first
 * change; labels are the words of the first change's old value; links are reversed as a multiset
 * from the current list, because one issue can hold two links of different types to the same
 * target. A text that was blank counts as absent.
 */
public final class Initial {

  /** The values at creation that any column reads. */
  public record Values(String title, String description, String type, String priority,
      String assignee, String parent, Set<String> labels, List<String> links) {}

  /**
   * How far the changelog explains a field, for the gate.
   *
   * @param changed issues with at least one change of the field
   * @param consistent of those, the ones whose changes chain and end on the stored value
   * @param differs issues whose value at creation differs from the stored one
   */
  public record Fidelity(String field, int issues, int changed, int consistent, int differs) {}

  private Initial() {}

  public static Values of(final Corpus.Issue issue, final List<Corpus.Event> events,
      final Corpus.Text text) {
    final List<Corpus.Event> textChanges = text == null ? List.of() : text.changes();
    return new Values(
        scalar(field(textChanges, "title"), text == null ? null : text.title()).initial,
        scalar(field(textChanges, "desc"), text == null ? null : text.description()).initial,
        scalar(field(events, "issue_type"), issue.type()).initial,
        scalar(field(events, "priority"), issue.priority()).initial,
        scalar(field(events, "assignee_id"), issue.assignee()).initial,
        scalar(field(events, "parent_issue_id"), issue.parent()).initial,
        labels(field(events, "labels"), issue.labels()).initial,
        links(field(events, "issue_links"), issue.links()).initial);
  }

  /**
   * What the issue said about itself at {@code t0}, in strict t0: only the changes stamped at or
   * after {@code t0} are undone. The extraction reads this at the first In Progress. {@link #of},
   * the reading at the creation, undoes every change instead: one event of the corpus is stamped
   * before its issue's creation.
   */
  public static Values at(final Corpus.Issue issue, final List<Corpus.Event> events,
      final Corpus.Text text, final long t0) {
    final Corpus.Text from = text == null ? null
        : new Corpus.Text(text.title(), text.description(), since(text.changes(), t0));
    return of(issue, since(events, t0), from);
  }

  static List<Corpus.Event> since(final List<Corpus.Event> events, final long t0) {
    final List<Corpus.Event> out = new ArrayList<>();
    for (final Corpus.Event e : events) {
      if (e.at() != null && e.at() >= t0) {
        out.add(e);
      }
    }
    return out;
  }

  /** Events in changelog order: instant, then Jira changelog id and index. */
  public static List<Corpus.Event> ordered(final List<Corpus.Event> events) {
    final Map<String, List<Corpus.Event>> by = Universe.changelog(events);
    final List<Corpus.Event> out = new ArrayList<>();
    by.values().forEach(out::addAll);
    return out;
  }

  static List<Corpus.Event> field(final List<Corpus.Event> events, final String name) {
    final List<Corpus.Event> out = new ArrayList<>();
    for (final Corpus.Event e : events) {
      if (name.equals(e.field())) {
        out.add(e);
      }
    }
    return out;
  }

  /**
   * Python's {@code str.strip()}, then blank as absent. Java's {@code strip} keeps the no-break
   * space and a few others Python removes; the parity gate found it on 1,407 descriptions.
   */
  public static String norm(final String value) {
    if (value == null) {
      return null;
    }
    int start = 0;
    int end = value.length();
    while (start < end && TextMeasures.space(value.codePointAt(start))) {
      start += Character.charCount(value.codePointAt(start));
    }
    while (end > start && TextMeasures.space(value.codePointBefore(end))) {
      end -= Character.charCount(value.codePointBefore(end));
    }
    final String s = value.substring(start, end);
    return s.isEmpty() ? null : s;
  }

  private record Reversal<T>(T initial, T stored, boolean changed, boolean consistent) {}

  private static Reversal<String> scalar(final List<Corpus.Event> changes, final String current) {
    final String stored = norm(current);
    if (changes.isEmpty()) {
      return new Reversal<>(stored, stored, false, true);
    }
    boolean chain = true;
    for (int k = 1; k < changes.size(); k++) {
      chain &= java.util.Objects.equals(norm(changes.get(k).oldValue()),
          norm(changes.get(k - 1).newValue()));
    }
    final boolean last = java.util.Objects.equals(norm(changes.get(changes.size() - 1).newValue()),
        stored);
    return new Reversal<>(norm(changes.get(0).oldValue()), stored, true, chain && last);
  }

  /** Python's {@code set(value.split())}: separated by any Unicode space. */
  static Set<String> words(final String value) {
    final Set<String> out = new TreeSet<>();
    if (value == null) {
      return out;
    }
    final StringBuilder word = new StringBuilder();
    for (int i = 0; i < value.length(); ) {
      final int c = value.codePointAt(i);
      if (TextMeasures.space(c)) {
        if (word.length() > 0) {
          out.add(word.toString());
          word.setLength(0);
        }
      } else {
        word.appendCodePoint(c);
      }
      i += Character.charCount(c);
    }
    if (word.length() > 0) {
      out.add(word.toString());
    }
    return out;
  }

  private static Reversal<Set<String>> labels(final List<Corpus.Event> changes,
      final List<String> current) {
    final Set<String> stored = new TreeSet<>();
    if (current != null) {
      current.stream().filter(s -> s != null && !s.isEmpty()).forEach(stored::add);
    }
    if (changes.isEmpty()) {
      return new Reversal<>(stored, stored, false, true);
    }
    boolean chain = true;
    for (int k = 1; k < changes.size(); k++) {
      chain &= words(changes.get(k).oldValue()).equals(words(changes.get(k - 1).newValue()));
    }
    final boolean last = words(changes.get(changes.size() - 1).newValue()).equals(stored);
    return new Reversal<>(words(changes.get(0).oldValue()), stored, true, chain && last);
  }

  private static Reversal<List<String>> links(final List<Corpus.Event> changes,
      final List<String> current) {
    final Undone<String> undone =
        undo(current, changes, Corpus.Event::newValue, Corpus.Event::oldValue);
    return new Reversal<>(elements(undone.initial()), elements(undone.stored()),
        !changes.isEmpty(), undone.consistent());
  }

  /**
   * A field holding several elements, at creation: the current ones with every change undone,
   * last first, as a multiset (one issue can hold the same element twice, as two links of
   * different types to one target).
   *
   * @param initial each element at creation with its count; a count may be 0
   * @param stored the current elements with their counts
   * @param consistent whether every addition undone was held when it was undone
   */
  record Undone<T>(Map<T, Integer> initial, Map<T, Integer> stored, boolean consistent) {

    /** The elements held at creation. */
    java.util.Set<T> held() {
      final java.util.Set<T> out = new java.util.LinkedHashSet<>();
      initial.forEach((k, n) -> {
        if (n > 0) {
          out.add(k);
        }
      });
      return out;
    }
  }

  /**
   * Undoes, last first, every change of a field holding several elements: an element a change
   * added ({@code added}, null for none) is taken out, one it removed ({@code removed}) put back.
   * The rule {@link #of} reads the links with.
   */
  static <T> Undone<T> undo(final java.util.Collection<T> current,
      final List<Corpus.Event> changes, final java.util.function.Function<Corpus.Event, T> added,
      final java.util.function.Function<Corpus.Event, T> removed) {
    final Map<T, Integer> state = new HashMap<>();
    if (current != null) {
      for (final T element : current) {
        if (element != null) {
          state.merge(element, 1, Integer::sum);
        }
      }
    }
    final Map<T, Integer> stored = new HashMap<>(state);
    boolean ok = true;
    for (int k = changes.size() - 1; k >= 0; k--) {
      final Corpus.Event e = changes.get(k);
      final T in = added.apply(e);
      if (in != null) {
        final int held = state.getOrDefault(in, 0);
        if (held > 0) {
          state.put(in, held - 1);
        } else {
          ok = false;
        }
      }
      final T out = removed.apply(e);
      if (out != null) {
        state.merge(out, 1, Integer::sum);
      }
    }
    return new Undone<>(state, stored, ok);
  }

  private static List<String> elements(final Map<String, Integer> multiset) {
    final List<String> out = new ArrayList<>();
    multiset.forEach((k, n) -> {
      for (int i = 0; i < n; i++) {
        out.add(k);
      }
    });
    Collections.sort(out);
    return out;
  }

}
