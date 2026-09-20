package app.identity;

/**
 * Which system an identity was observed in.
 *
 * <p>The distinction is not bookkeeping: the two spaces barely overlap. Of 4,453 distinct Jira
 * assignee ids in SmartSHARK 2.2, only 365 (8.2%) also appear as a git author. A label is keyed in
 * one space and {@code H_j} is accumulated in the other, so bridging them is the whole point of
 * {@link CanonicalResolver}.
 */
public enum Source {
  GIT,
  JIRA
}
