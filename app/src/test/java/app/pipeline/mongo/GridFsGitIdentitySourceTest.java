package app.pipeline.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure helpers of the GridFS adapter, exercised without MongoDB. */
class GridFsGitIdentitySourceTest {

  @Test
  @DisplayName("shortens repository urls for progress output")
  void shortensRepositoryUrls() {
    assertEquals("commons-rdf",
        GridFsGitIdentitySource.shortName("https://github.com/apache/commons-rdf.git"));
    assertEquals("ant-ivy",
        GridFsGitIdentitySource.shortName("https://github.com/apache/ant-ivy"));
    assertEquals("kafka",
        GridFsGitIdentitySource.shortName("https://github.com/apache/kafka/"));
    assertEquals("(unknown)", GridFsGitIdentitySource.shortName(null));
    assertEquals("(unknown)", GridFsGitIdentitySource.shortName("  "));
  }
}
