package app.pipeline;

import app.paper6.Profile;
import java.util.List;

/**
 * What {@code REGISTRO-PREVIO.md} froze, as literals. Changing a line here invalidates the result.
 *
 * <p>Empty until the grid has run and the registration has been committed. {@code paper6-reservado}
 * refuses to start while it is empty, so the reserved rows cannot be reached before the parameters
 * that will be used on them exist in writing.
 */
public final class Paper6Registration {

  /** The principal reading, fixed before any result: the widest one that is still ex ante. */
  public static final Profile PRINCIPAL = Profile.T0_RECONSTRUIDO;

  public static final List<Paper6EvaluationTask.Frozen> FROZEN = List.of();

  private Paper6Registration() {}
}
