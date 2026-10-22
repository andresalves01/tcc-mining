package app.pipeline.mongo;

import static org.junit.jupiter.api.Assertions.*;
import app.issues.Corpus;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoDurationSourceTest {
  @Test void originalDateRequiresFullShaMatchingSubjectAndTimeZone() {
    String a="a".repeat(40),b="b".repeat(40);
    var wanted=Map.of(a,List.of(new Document("message","ISSUE-1 fix the parser")),
        b,List.of(new Document("message","ISSUE-1 add tests")));
    var dates=new HashMap<String,List<MongoDurationSource.Stamp>>();
    String body="commit "+a+"\r\nAuthor: a\r\nDate: 2024-01-02T10:00:00+02:00\r\n\r\n    ISSUE-1 fix the parser\r\n"
        +"commit "+b+"\nAuthor: b\nDate: Tue, 2 Jan 2024 09:00:00 GMT\n\n    ISSUE-1 add tests\n";
    MongoDurationSource.gitDates(body,"message","source-id",wanted,dates);
    assertEquals(Instant.parse("2024-01-02T08:00:00Z").toEpochMilli(),dates.get(a).getFirst().at());
    assertEquals("source-id",dates.get(b).getFirst().id());
    dates.clear();
    MongoDurationSource.gitDates(body.replace("fix the parser","wrong subject").replace("add tests","different subject"),"message","x",wanted,dates);
    assertTrue(dates.isEmpty());
    assertNull(MongoDurationSource.parseDate("2024-01-02T08:00:00"));
  }
  @Test void mixedIssuePrAndBotsAreNotTreatedAsUnambiguousHumanWork() {
    assertTrue(MongoDurationSource.foreign("ISSUE-1 and ISSUE-2","ISSUE-1"));
    assertFalse(MongoDurationSource.foreign("ISSUE-1: fixes","ISSUE-1"));
    assertFalse(MongoDurationSource.human(null,Set.of(),Map.of()));
    assertFalse(MongoDurationSource.human("raw",Set.of("canon"),Map.of("raw",new Corpus.Person("canon",false))));
    assertFalse(MongoDurationSource.human("raw",Set.of(),Map.of("raw",new Corpus.Person("canon",true))));
    assertTrue(MongoDurationSource.human("raw",Set.of(),Map.of()));
  }
}
