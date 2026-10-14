package app.issues;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lexical reference among the similarity methods: two descriptions are as close as the share of
 * the words they have in common.
 *
 * <p>The similarity of two sets is their shared tokens over all their tokens, computed by
 * {@link Augmentation} from counts; a set with no tokens shares none. A token is a maximal run of
 * Unicode letters, decimal digits and underscore, lowercased; a description is the set of its
 * tokens. No stop words are removed, nothing is stemmed or weighted.
 */
public final class Jaccard {

  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{Nd}_]+");

  private Jaccard() {}

  /** The set of tokens of a cleaned description; empty when it is absent or has none. */
  public static Set<String> tokens(final String text) {
    final Set<String> out = new HashSet<>();
    if (text == null) {
      return out;
    }
    final Matcher m = TOKEN.matcher(text);
    while (m.find()) {
      out.add(m.group().toLowerCase(Locale.ROOT));
    }
    return out;
  }
}
