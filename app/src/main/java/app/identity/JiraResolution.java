package app.identity;

import java.util.List;

/**
 * What querying the issue tracker produced.
 *
 * @param records resolved Jira identities, ready to merge into the registry
 * @param issuesFetched issues actually requested over the network
 * @param issuesFromCache issues served from the on-disk cache, so a re-run costs nothing
 * @param issuesFailed issues that could not be resolved (deleted, moved, or access-restricted)
 */
public record JiraResolution(
    List<PersonRecord> records,
    int issuesFetched,
    int issuesFromCache,
    int issuesFailed) {}
