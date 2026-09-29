package app.paper6;

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
 * <p>The rule of {@code papers/issues/viabilidade/analises/20_changelog.py}, in <b>strict t0</b>
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

  static String norm(final String value) {
    if (value == null) {
      return null;
    }
    final String s = value.strip();
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

  static Set<String> words(final String value) {
    final Set<String> out = new TreeSet<>();
    if (value != null) {
      for (final String w : value.trim().split("\\s+")) {
        if (!w.isEmpty()) {
          out.add(w);
        }
      }
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
    final Map<String, Integer> state = new HashMap<>();
    if (current != null) {
      for (final String target : current) {
        if (target != null) {
          state.merge(target, 1, Integer::sum);
        }
      }
    }
    final List<String> stored = elements(state);
    boolean ok = true;
    for (int k = changes.size() - 1; k >= 0; k--) {
      final Corpus.Event e = changes.get(k);
      if (e.newValue() != null) {
        final int held = state.getOrDefault(e.newValue(), 0);
        if (held > 0) {
          state.put(e.newValue(), held - 1);
        } else {
          ok = false;
        }
      }
      if (e.oldValue() != null) {
        state.merge(e.oldValue(), 1, Integer::sum);
      }
    }
    return new Reversal<>(elements(state), stored, !changes.isEmpty(), ok);
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

  /** The changelog gate: per field, over the given issues. */
  public static List<Fidelity> fidelity(final Universe universe, final List<String> ids,
      final Map<String, Corpus.Text> texts) {
    final Map<String, int[]> tally = new LinkedHashMap<>();
    for (final String f : List.of("title", "desc", "priority", "issue_type", "assignee_id",
        "labels", "issue_links")) {
      tally.put(f, new int[3]);
    }
    for (final String id : ids) {
      final Corpus.Issue issue = universe.issue(id);
      final List<Corpus.Event> events = universe.events(id);
      final Corpus.Text text = texts.get(id);
      final List<Corpus.Event> tc = text == null ? List.of() : text.changes();
      add(tally.get("title"), scalar(field(tc, "title"), text == null ? null : text.title()));
      add(tally.get("desc"), scalar(field(tc, "desc"), text == null ? null : text.description()));
      add(tally.get("priority"), scalar(field(events, "priority"), issue.priority()));
      add(tally.get("issue_type"), scalar(field(events, "issue_type"), issue.type()));
      add(tally.get("assignee_id"), scalar(field(events, "assignee_id"), issue.assignee()));
      add(tally.get("labels"), labels(field(events, "labels"), issue.labels()));
      add(tally.get("issue_links"), links(field(events, "issue_links"), issue.links()));
    }
    final List<Fidelity> out = new ArrayList<>();
    tally.forEach((f, t) -> out.add(new Fidelity(f, ids.size(), t[0], t[1], t[2])));
    return out;
  }

  private static void add(final int[] t, final Reversal<?> r) {
    if (r.changed()) {
      t[0]++;
      if (r.consistent()) {
        t[1]++;
      }
    }
    if (!java.util.Objects.equals(r.initial(), r.stored())) {
      t[2]++;
    }
  }
}
