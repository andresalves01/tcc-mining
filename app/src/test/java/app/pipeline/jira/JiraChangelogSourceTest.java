package app.pipeline.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure helpers of the issue-tracker adapter, exercised without network access. */
class JiraChangelogSourceTest {

  @Test
  @DisplayName("derives the API base from the issue_system url")
  void derivesApiBase() {
    assertEquals(Optional.of("https://issues.apache.org/jira/rest/api/2"),
        JiraChangelogSource.apiBaseOf(
            "https://issues.apache.org/jira/rest/api/2/search?jql=project=COMMONSRDF"));
    assertTrue(JiraChangelogSource.apiBaseOf("https://example.org/no-search-segment").isEmpty());
    assertTrue(JiraChangelogSource.apiBaseOf(null).isEmpty());
  }
}
