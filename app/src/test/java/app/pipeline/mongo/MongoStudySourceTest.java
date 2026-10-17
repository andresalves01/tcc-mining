package app.pipeline.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.issues.Corpus;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The adapter's reading of one link document (ticket 19), without a database. */
class MongoStudySourceTest {

  @Test
  @DisplayName("a link document keeps its target, type and effect; its target is what every "
      + "earlier column read as the link, so the old counts stay as they were")
  void linkWithTypeAndDirection() {
    final ObjectId target = new ObjectId("58bfcbfd0ccb2667fe26ba7e");
    final Document link = new Document("issue_id", target).append("type", "Blocker")
        .append("effect", "is blocked by");
    assertEquals(new Corpus.Link(target.toHexString(), "Blocker", "is blocked by"),
        MongoStudySource.link(link));
    assertEquals(MongoStudySource.text(link), MongoStudySource.link(link).target());
    assertNull(MongoStudySource.link(null));
    assertNull(MongoStudySource.link("Blocker"));
  }
}
