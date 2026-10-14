package app.issues;

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
}
