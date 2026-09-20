package app.identity;

/**
 * How aggressively identities may be collapsed into people.
 *
 * <p>Measured over all 452,286 commits of SmartSHARK 2.2:
 *
 * <pre>
 *   no merging                 7,199 ids
 *   EMAIL_ONLY                 6,511    688 merged  ( 9.6%)
 *   EMAIL_AND_FULL_NAME        5,403  1,796 merged  (25.0%)
 *   (+ email local part)       5,234  BROKEN -- not offered
 * </pre>
 */
public enum Strictness {
  /** Exact email only. Every merge sound by construction; leaves cross-address aliases split. */
  EMAIL_ONLY,
  /** Adds exact full-name matching. The validated default. */
  EMAIL_AND_FULL_NAME
}
