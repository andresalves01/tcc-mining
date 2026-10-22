package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.DayBoundaries.Action;
import app.issues.DayBoundaries.Boundary;
import app.issues.DayBoundaries.Source;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An issue's day boundary, read from its clicker's and its project's past tracker activity. */
class DayBoundariesTest {

  private static final long H = 3_600_000L;
  private static final long DAY = 24 * H;

  /** 2024-01-01T00:00Z, a Monday. */
  private static final long T0 = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();

  /** Far after every action of the tests. */
  private static final long LATE = T0 + 400 * DAY;

  private final List<Action> actions = new ArrayList<>();

  /** {@code person} acts in {@code project} at {@code hour} UTC of day {@code day}, minute 10. */
  private void act(final String person, final String project, final int day, final int hour) {
    actions.add(new Action(T0 + day * DAY + hour * H + 10 * 60_000L, person, "i-" + project,
        project));
  }

  /** {@code person} acts once at every hour of the day but {@code length} from {@code from}. */
  private void activeBut(final String person, final String project, final int day, final int from,
      final int length) {
    for (int h = 0; h < 24; h++) {
      if (Math.floorMod(h - from, 24) >= length) {
        act(person, project, day, h);
      }
    }
  }

  private Boundary boundary(final String person, final String project, final long click) {
    return DayBoundaries.of(actions).at(person, project, click);
  }

  @Test
  @DisplayName("the person's rule: the middle of their quietest eight hours")
  void person() {
    activeBut("ana", "p", 0, 14, 8);
    final Boundary b = boundary("ana", "p", LATE);
    assertEquals(new Boundary(18, Source.PERSON, 16, 16), b);
  }

  @Test
  @DisplayName("one unit per person per clock hour: a burst within an hour counts once")
  void oneUnitPerHour() {
    for (int k = 0; k < 9; k++) {
      act("ana", "p", k, 12);
      act("ana", "p", k, 12);
    }
    assertEquals(9, boundary("ana", "p", LATE).personHours());
  }

  @Test
  @DisplayName("only activity strictly before the click counts; at or after it changes nothing")
  void strictlyBefore() {
    for (int k = 0; k < 9; k++) {
      act("ana", "p", k, 12);
    }
    final long click = T0 + 20 * DAY + 12 * H;
    final Boundary before = boundary("ana", "p", click);
    assertEquals(Source.FALLBACK, before.source());
    assertEquals(9, before.personHours());
    actions.add(new Action(click, "ana", "i-p", "p"));
    activeBut("ana", "p", 21, 2, 8);
    activeBut("bea", "p", 22, 2, 8);
    assertEquals(before, boundary("ana", "p", click));
    actions.add(new Action(click - 1, "ana", "i-p", "p"));
    assertEquals(Source.PERSON, boundary("ana", "p", click).source(),
        "a tenth hour a millisecond before the click is read");
  }

  @Test
  @DisplayName("fallbacks: the person at >= 10 active hours, else the start of the project's "
      + "quietest eight hours at >= 30 person-hours, else 04:00 UTC")
  void fallbacks() {
    for (int k = 0; k < 9; k++) {
      act("ana", "p", k, 9);
    }
    // the project: 30 person-hours, quiet from 20:00 to 03:59
    for (int k = 0; k < 3; k++) {
      activeBut("p" + k, "p", 30 + k, 20, 8);
    }
    act("bea", "p", 40, 10);
    act("bea", "p", 40, 11);
    act("bea", "p", 40, 12);
    act("bea", "p", 40, 13);
    act("bea", "p", 40, 14);
    act("bea", "p", 41, 15);
    final Boundary project = boundary("ana", "p", LATE);
    assertEquals(new Boundary(20, Source.PROJECT, 9, 9 + 48 + 6), project);

    actions.clear();
    for (int k = 0; k < 29; k++) {
      act("x" + k, "q", k, 10);
    }
    assertEquals(new Boundary(DayBoundaries.FALLBACK_HOUR, Source.FALLBACK, 0, 29),
        boundary("nobody", "q", LATE));
    act("x29", "q", 29, 10);
    assertEquals(Source.PROJECT, boundary("nobody", "q", LATE).source(), "the thirtieth");
    assertEquals(new Boundary(DayBoundaries.FALLBACK_HOUR, Source.FALLBACK, 0, 0),
        boundary(null, "elsewhere", LATE), "no clicker and no project history");

    actions.clear();
    for (int k = 0; k < 10; k++) {
      act("ana", "p", k, 9);
    }
    assertEquals(Source.PERSON, boundary("ana", "p", LATE).source(), "the tenth active hour");
  }

  @Test
  @DisplayName("ties between equally quiet windows go to the boundary closest to 04:00 UTC")
  void ties() {
    // active from 04:00 to 07:59 and from 16:00 to 19:59 on four days: quiet 08-15 and 20-03
    for (int d = 0; d < 4; d++) {
      for (final int h : new int[] {4, 5, 6, 7, 16, 17, 18, 19}) {
        act("ana", "p", d, h);
      }
    }
    // middles 12:00 and 00:00, eight and four hours from 04:00
    assertEquals(0, boundary("ana", "p", LATE).hour());
    // the project's rule takes the start: 08:00 and 20:00, four and eight hours from 04:00
    assertEquals(new Boundary(8, Source.PROJECT, 0, 32), boundary("nobody", "p", LATE));
  }

  @Test
  @DisplayName("bot accounts, ProjectImport events, authorless entries and issues outside the "
      + "studied ones never enter the history; people are read as their canonical identity")
  void history() {
    final long t = T0 + 10 * H;
    final List<Corpus.Event> events = List.of(
        new Corpus.Event("i1", t, "status", "raw-ana", "Open", "In Progress", null),
        new Corpus.Event("i1", t + H, "ProjectImport", "raw-bea", null, null, null),
        new Corpus.Event("i1", t + 2 * H, "assignee", null, null, "x", null),
        new Corpus.Event("i1", t + 3 * H, "labels", "bot", null, "x", null),
        new Corpus.Event("foreign", t + 4 * H, "labels", "raw-ana", null, "x", null),
        new Corpus.Event("i2", t + 5 * H, "Attachment", "raw-bea", null, "x.patch", null));
    final List<Corpus.Comment> comments = new ArrayList<>();
    for (int k = 0; k < 20; k++) {
      comments.add(new Corpus.Comment("i2", "bot", t + k * DAY, 3, k < 5));
    }
    for (int k = 0; k < 19; k++) {
      comments.add(new Corpus.Comment("i2", "terse", t + (k + 1) * DAY, 3, true));
    }
    comments.add(new Corpus.Comment("i2", null, t, 3, false));
    final Map<String, Corpus.Person> people = Map.of(
        "raw-ana", new Corpus.Person("ana", false), "raw-bea", new Corpus.Person("bea", false));

    assertEquals(Set.of("bot"),
        DayBoundaries.bots(comments, Set.of("i1", "i2")), "twenty comments, a quarter templated");
    final List<Action> kept = DayBoundaries.actions(events, comments,
        Map.of("i1", "p", "i2", "q"), people);
    assertEquals(List.of("ana", "bea"), kept.stream().filter(a -> a.at() < t + DAY)
        .map(Action::person).toList(), "the In Progress change and the attachment");
    assertEquals(21, kept.size(), "and the nineteen comments of a person with fewer than twenty");
    assertTrue(kept.stream().noneMatch(a -> "bot".equals(a.person())));
    assertEquals("q", kept.get(1).project());
  }

  @Test
  @DisplayName("a comment opens like a bot template as the prototype read it")
  void templates() {
    assertTrue(DayBoundaries.templated("GitHub user britter closed the pull request at:\n"));
    assertTrue(DayBoundaries.templated("Commit 1533346 from Bob in branch 'trunk'"));
    assertTrue(DayBoundaries.templated("Commit\n1533346 in trunk"),
        "line breaks read as spaces");
    assertTrue(DayBoundaries.templated("FAILURE: Integrated in Hadoop-trunk #12"));
    assertTrue(DayBoundaries.templated("integrated in build 7"));
    assertTrue(DayBoundaries.templated("-1 overall.  Here are the results"));
    assertTrue(DayBoundaries.templated(
        "{color:red}-1 overall{color}.  Here are the results of testing the latest attachment"));
    assertTrue(DayBoundaries.templated(
        "{color:green}+1 overall{color}.  Here are the results of testing the latest attachment"));
    assertTrue(DayBoundaries.templated("Testing JIRA HADOOP-1"));
    assertFalse(DayBoundaries.templated("I think the commit 15333 from yesterday broke it"));
    assertFalse(DayBoundaries.templated("I fixed the {color:red}-1 overall{color} test result"));
    assertFalse(DayBoundaries.templated("{color:red}This patch still needs a review{color}"));
    assertFalse(DayBoundaries.templated(null));
  }

  @Test
  @DisplayName("colored CI reports identify the account and remove its tracker actions")
  void coloredPatchReportsIdentifyBotAccount() {
    final List<Corpus.Comment> comments = new ArrayList<>();
    for (int k = 0; k < 20; k++) {
      final String body = k < 5
          ? "{color:red}-1 overall{color}. Here are the results of testing the latest attachment"
          : "More build details";
      comments.add(new Corpus.Comment("i", "ci", T0 + k * H, 10,
          DayBoundaries.templated(body)));
    }
    comments.add(new Corpus.Comment("i", "dev", T0, 5,
        DayBoundaries.templated("I fixed the {color:red}-1 overall{color} result")));
    assertEquals(Set.of("ci"), DayBoundaries.bots(comments, Set.of("i")));
    final List<Corpus.Event> events = List.of(
        new Corpus.Event("i", T0, "labels", "ci", null, "test", "1%%0"),
        new Corpus.Event("i", T0 + H, "status", "dev", "Open", "In Progress", "2%%0"));
    final List<Action> actions = DayBoundaries.actions(events, comments,
        Map.of("i", "p"), Map.of());
    assertEquals(2, actions.size(), "only the developer's comment and status change remain");
    assertTrue(actions.stream().allMatch(a -> a.person().equals("dev")));
  }
}
