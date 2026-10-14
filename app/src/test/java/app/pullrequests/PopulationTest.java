package app.pullrequests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.labels.ChangedMethod;
import app.labels.IssueLabel;
import app.labels.SplitPart;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The population of the Yu/Chouchen replication: who is in it, and counted by whom. */
class PopulationTest {

  private static final Instant EPOCH = Instant.parse("2015-01-01T00:00:00Z");

  /**
   * One unit, opened on a day and merged some hours later.
   *
   * <p>Hours rather than days, and always inside the opening day, so that no fixture straddles the
   * cut by accident: the cut is one of the creation instants exactly, and a row opened before it
   * and merged at or after it is {@code CROSSES_CUT} -- correct behaviour, pinned by
   * {@code TemporalSplitTest.theCutIsAClosedFuture}, and noise in a test about who is counted.
   * Crossing is exercised on purpose below, by a row built to cross.
   */
  private static IssueLabel label(final String canonicalAuthor, final String project,
      final int openedDay, final long mergedAfterHours, final int seed) {

    final Instant opened = EPOCH.plus(openedDay, ChronoUnit.DAYS);
    return new IssueLabel(
        String.format("%024x", seed),
        project,
        opened,
        opened.plus(mergedAfterHours, ChronoUnit.HOURS),
        canonicalAuthor,
        opened,
        1, 10, 5,
        List.of(new ChangedMethod("p.C.m()V", 3, 1)));
  }

  /** Ten per author, one a day, each merged the same day -- history enough for any threshold. */
  private static List<IssueLabel> corpus(final String... authors) {
    final List<IssueLabel> labels = new ArrayList<>();
    int day = 1;
    int seed = 1;
    for (final String author : authors) {
      for (int i = 0; i < 10; i++, day++, seed++) {
        labels.add(label(author, "p", day, 12, seed));
      }
    }
    return labels;
  }

  private static Map<String, String> creators(final List<IssueLabel> labels, final String creator) {
    final Map<String, String> byUnit = new HashMap<>();
    labels.forEach(label -> byUnit.put(label.issueId(), creator));
    return byUnit;
  }

  @Test
  @DisplayName("people are counted by creator_id, not by the canonical commit author")
  void creatorsAreTheUnitOfPerson() {
    // The whole reason this study drops the inherited-history rule: it keys on whoever opened the
    // request. Three distinct commit authors who all had their work opened by one maintainer are
    // one person here, and three in the earlier pull-request study's split that runs over the same rows.
    final List<IssueLabel> labels = corpus("ana", "bruno", "carla");

    final Population.Report report =
        Population.of(labels, creators(labels, "mantenedor"), 0.70, 0);

    assertEquals(1, report.usable().creators(),
        "one creator opened every row, however many commit authors wrote them");
    assertEquals(30, report.usable().rows());
  }

  @Test
  @DisplayName("a label crossing the cut is counted and kept out of the usable set")
  void crossingLabelsAreNotUsable() {
    final List<IssueLabel> labels = new ArrayList<>(corpus("ana", "bruno"));
    labels.add(label("ana", "p", 5, 400 * 24, 900));

    final Population.Report report =
        Population.of(labels, creators(labels, "mantenedor"), 0.70, 0);

    assertEquals(1, report.byPart().get(SplitPart.CROSSES_CUT).rows());
    assertEquals(labels.size() - 1, report.usable().rows(),
        "usable is TRAIN plus TEST, and the crossing row is in neither");
  }

  @Test
  @DisplayName("with the rule off nothing lands in insufficient history")
  void theInheritedHistoryRuleIsOff() {
    final List<IssueLabel> labels = new ArrayList<>(corpus("veteran"));
    labels.add(label("newcomer", "p", 40, 12, 800));

    final Population.Report report =
        Population.of(labels, creators(labels, "mantenedor"), 0.70, 0);

    assertEquals(0, report.byPart().get(SplitPart.INSUFFICIENT_HISTORY).rows());
    assertEquals(labels.size(), report.usable().rows());
    assertEquals(0, report.minResolved(), "the value is carried, so every result can record it");
  }

  @Test
  @DisplayName("a unit with no creator in the corpus fails the run instead of being dropped")
  void aUnitWithoutACreatorIsRefused() {
    // Silently skipping it would change the population without changing the number that names it.
    final List<IssueLabel> labels = corpus("ana", "bruno");
    final Map<String, String> byUnit = creators(labels, "mantenedor");
    byUnit.remove(labels.get(3).issueId());

    final Population.MissingCreatorException thrown =
        assertThrows(Population.MissingCreatorException.class,
            () -> Population.of(labels, byUnit, 0.70, 0));

    assertEquals(List.of(labels.get(3).issueId()), thrown.unitIds());
    assertTrue(thrown.getMessage().contains("creator_id"));
  }

  @Test
  @DisplayName("the train and test lists carry the outcome in hours, from the same two instants")
  void unitsCarryTheOutcome() {
    final List<IssueLabel> labels = corpus("ana", "bruno");

    final Population.Report report =
        Population.of(labels, creators(labels, "mantenedor"), 0.70, 0);

    assertTrue(report.train().stream().allMatch(unit -> unit.durationHours() == 12.0),
        "every fixture merges twelve hours after it opens");
    assertTrue(report.train().stream().allMatch(unit -> unit.openedAt().isBefore(unit.mergedAt())),
        "the target is positive on every row, which the integrity tests require");
  }
}
