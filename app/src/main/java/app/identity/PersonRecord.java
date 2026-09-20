package app.identity;

/**
 * One identity as observed in one system, before any merging.
 *
 * @param personId the SmartSHARK {@code ObjectId}, as a hex string
 * @param identity name and email; for Jira records the email is blank, since Jira withholds
 *     addresses
 * @param username Jira username, or {@code null} for git records
 */
public record PersonRecord(String personId, Identity identity, String username, Source source) {

  public static PersonRecord git(final String personId, final Identity identity) {
    return new PersonRecord(personId, identity, null, Source.GIT);
  }

  public static PersonRecord jira(final String personId, final String displayName, final String username) {
    final String name = displayName == null ? username : displayName;
    return new PersonRecord(personId, new Identity(name, ""), username, Source.JIRA);
  }
}
