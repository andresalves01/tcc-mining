package app.identity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A developer identity as SmartSHARK records one: the {@code (name, email)} pair.
 *
 * <p>This is the natural key of the {@code people} collection. In {@code pycoshark} the model
 * declares {@code email = StringField(unique_with=["name"])}, which makes {@code (name, email)} the
 * primary key and guarantees exactly one {@code ObjectId} per identity. That constraint is what
 * makes the reconstruction exact rather than approximate.
 *
 * <p>An identity is <em>not</em> a person. One human routinely holds several: a work address, a
 * personal address, an {@code @apache.org} address, and whatever git defaulted to on their laptop.
 * Collapsing identities into people is {@link CanonicalResolver}'s job.
 */
public record Identity(String name, String email) {

  /**
   * Addresses shared by many different names. These are placeholders written by tooling, not real
   * mailboxes, so merging on them would fuse unrelated people. {@code dev-null@apache.org} alone
   * carries 17 distinct names in the 2.2 corpus.
   */
  private static final Pattern PLACEHOLDER_EMAIL = Pattern.compile(
      "(dev-null|unknown|\\(no author\\)|localhost|invalid|^none@|no_author|^nobody@)",
      Pattern.CASE_INSENSITIVE);

  /**
   * The same idea on the name side, which the email rule cannot reach. Tooling writes
   * {@code "No Author"} and {@code "(no author)"} as author names, and both normalise to two tokens,
   * so without this they satisfy the full-name rule and fuse every anonymous commit in the corpus
   * into one person. Measured before this exclusion existed: 484 commits under a single fabricated
   * identity spanning {@code "No Author"}, {@code "PLDMS"} and {@code "K smathers"}.
   */
  private static final Pattern PLACEHOLDER_NAME = Pattern.compile(
      "^(no author|no_author|unknown|none|nobody|null|anonymous)$", Pattern.CASE_INSENSITIVE);

  /**
   * Bot detection is deliberately email-led. Human surnames collide with the obvious keywords — the
   * corpus contains Gus Jenkins, John Jenkins, Joshua Hudson and Vincent Botta, all real
   * developers. Flagging one of them deletes a human from {@code H_j}, which is worse than missing
   * a bot, so name matching is restricted to unambiguous forms.
   */
  private static final Pattern BOT_EMAIL = Pattern.compile(
      "(^bot@|\\[bot\\]@|noreply@github\\.com|^builds?@|^jenkins@|dependabot|renovate|^scm@|^ci@|^buildbot@)",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern BOT_NAME = Pattern.compile(
      "(\\[bot\\]|^jenkins$|^builds?$|^bot$|continuous integration|\\brenovate\\b|\\bdependabot\\b|^github$)",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern COMBINING_MARK = Pattern.compile("\\p{M}");
  private static final Pattern NON_LETTER = Pattern.compile("[^a-z ]");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /**
   * Explicit canonical constructor rather than a compact one, so the parameters can be final: a
   * compact constructor exists precisely to reassign them.
   */
  public Identity(final String name, final String email) {
    this.name = name == null ? "" : name.trim();
    this.email = email == null ? "" : email.trim();
  }

  /** Lower-cased address, used as the merge key. */
  public String normalizedEmail() {
    return email.toLowerCase(Locale.ROOT);
  }

  /**
   * Accent-stripped, punctuation-free, lower-cased name. {@code "Sergio Fernández"} and
   * {@code "Sergio Fernandez"} normalise alike, which is how that merge is found.
   *
   * <p>Note this strips diacritics; it does not transliterate. {@code "Jörn"} becomes {@code "jorn"},
   * not {@code "joern"}.
   */
  public String normalizedName() {
    final String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
    final String ascii = COMBINING_MARK.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    final String letters = NON_LETTER.matcher(ascii).replaceAll(" ");
    return WHITESPACE.matcher(letters).replaceAll(" ").trim();
  }

  /** Number of whitespace-separated tokens in the normalised name. */
  public int nameTokenCount() {
    final String normalized = normalizedName();
    return normalized.isEmpty() ? 0 : normalized.split(" ").length;
  }

  /** The part before {@code @}. Used only to link a git address to a Jira username. */
  public String emailLocalPart() {
    final int at = email.indexOf('@');
    return (at < 0 ? email : email.substring(0, at)).toLowerCase(Locale.ROOT);
  }

  public boolean hasPlaceholderEmail() {
    return email.isEmpty() || PLACEHOLDER_EMAIL.matcher(email).find();
  }

  /** Whether the name is a tooling stand-in rather than a person's name. */
  public boolean hasPlaceholderName() {
    return PLACEHOLDER_NAME.matcher(normalizedName()).matches();
  }

  public boolean isBot() {
    return BOT_EMAIL.matcher(email).find() || BOT_NAME.matcher(name.trim()).find();
  }

  @Override
  public String toString() {
    return name + " <" + email + ">";
  }
}
