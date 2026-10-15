package app.issues;

import java.util.ArrayList;
import java.util.List;

/**
 * What the embedding similarity methods read of a description: one vector of length one, however
 * long the text, and how many chunks it took to cover it.
 *
 * <p>The production implementation runs the pinned sentence-embedding model; the tests of the
 * stages that read embeddings give hand-made vectors instead.
 */
public interface Embedder {

  /** A description's vector, at length one, and the number of chunks it was embedded in. */
  record Embedding(float[] vector, int chunks) {}

  /** The embedding of a cleaned, non-empty description. */
  Embedding embed(String text);

  /**
   * The embeddings of {@code texts}, in their order: by default each embedded in turn, as a loop
   * over {@link #embed} would, the first failure stopping the rest. An implementation may embed
   * them otherwise, so long as every result is the loop's, one per text, in their order.
   */
  default List<Embedding> embedAll(final List<String> texts) {
    final List<Embedding> out = new ArrayList<>(texts.size());
    for (final String text : texts) {
      out.add(embed(text));
    }
    return out;
  }
}
