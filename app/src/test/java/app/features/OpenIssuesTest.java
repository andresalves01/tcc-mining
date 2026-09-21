package app.features;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.labels.ChangedMethod;
import app.labels.IssueLabel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How much was already underway when a task arrived, counted over the labels themselves. */
class OpenIssuesTest {

  private static final Instant EPOCH = Instant.parse("2010-01-01T00:00:00Z");

  private static Instant day(final int day) {
    return EPOCH.plus(day, ChronoUnit.DAYS);
  }

  private static IssueLabel label(final String id, final String developer, final String project,
      final int created, final int resolved) {

    return new IssueLabel(id, project, day(created), day(resolved), developer, day(created), 1,
        10, 5, List.of(new ChangedMethod("p.C.m()V", 3, 1)));
  }

  @Test
  @DisplayName("a task is never part of the load it is predicted from")
  void aTaskDoesNotCountAgainstItself() {
    final IssueLabel subject = label("i1", "alice", "lucene", 100, 200);
    final OpenIssues open = new OpenIssues(List.of(subject));

    assertEquals(0, open.openAt("alice", "lucene", subject.createdAt()),
        "at its own creation the issue has just arrived, so it is not work already in hand");
    assertEquals(1, open.openAt("alice", "lucene", day(150)),
        "halfway through its life it is open like any other");
  }

  @Test
  @DisplayName("open means created strictly before and resolved strictly after")
  void boundaries() {
    final OpenIssues open = new OpenIssues(List.of(label("i1", "alice", "lucene", 100, 200)));

    assertEquals(0, open.openAt("alice", "lucene", day(100)), "created exactly then: not yet open");
    assertEquals(0, open.openAt("alice", "lucene", day(200)), "resolved exactly then: no longer");
    assertEquals(1, open.openAt("alice", "lucene", day(100).plusMillis(1)));
    assertEquals(1, open.openAt("alice", "lucene", day(200).minusMillis(1)));
  }

  @Test
  @DisplayName("the individual count is scoped to the project, and the career count is not")
  void scope() {
    final OpenIssues open = new OpenIssues(List.of(
        label("i1", "alice", "lucene", 100, 300),
        label("i2", "alice", "derby", 100, 300),
        label("i3", "bob", "lucene", 100, 300)));

    assertEquals(1, open.openAt("alice", "lucene", day(200)), "only alice's lucene issue");
    assertEquals(2, open.openAcrossProjectsAt("alice", day(200)),
        "both of alice's, whichever project they belong to");
  }

  @Test
  @DisplayName("team load sums its members and cannot double count, because a label has one author")
  void teamLoad() {
    final OpenIssues open = new OpenIssues(List.of(
        label("i1", "alice", "lucene", 100, 300),
        label("i2", "alice", "lucene", 110, 300),
        label("i3", "bob", "lucene", 120, 300),
        label("i4", "carol", "lucene", 130, 300)));

    assertEquals(3, open.openForTeamAt(Set.of("alice", "bob"), "lucene", day(200)),
        "two of alice's and one of bob's; carol is not on the team");
    assertEquals(0, open.openForTeamAt(Set.of(), "lucene", day(200)),
        "an empty team carries no load, which is a count and not a missing value");
  }

  @Test
  @DisplayName("a developer the labels never mention carries nothing rather than failing")
  void unknownDeveloper() {
    final OpenIssues open = new OpenIssues(List.of(label("i1", "alice", "lucene", 100, 300)));

    assertEquals(0, open.openAt("stranger", "lucene", day(200)));
    assertEquals(0, open.openAcrossProjectsAt("stranger", day(200)));
  }
}
