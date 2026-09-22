package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.model.Bounds;
import app.pipeline.FitModelTask;
import java.util.List;

/**
 * Fits the Elastic Net on the training side and scores it against the inner selection only.
 *
 * <p>{@code fit} itself always does two things in one invocation: {@link FitModelTask#run} chooses
 * alpha and lambda on the inner split, then {@link FitModelTask#heldOut} refits on the whole
 * training side and scores the held-out partition exactly once, by design. That is correct once a
 * specification is locked, and wrong while one is still being worked out -- there is no way to run
 * {@code fit} and see only the first half, so changing a definition and re-running it opens the
 * held-out set again on every attempt, under whatever the definition happened to be that time.
 *
 * <p>This command exists for exactly that working-out period. It calls {@link FitModelTask#run}
 * and nothing else -- there is no code path from here to {@link FitModelTask#heldOut} at all, not
 * merely a convention of not calling it, so no future edit to this file can reach it by accident
 * without the compiler needing a new import to do it.
 *
 * <p>Once a specification is settled and committed, {@code fit} is the command to run -- once,
 * deliberately, the same discipline every other stage of this study already keeps.
 */
public final class FitInnerCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final FitModelTask task = new FitModelTask(context.observations(), context.progress());
    for (final Bounds bounds : List.of(Bounds.none(), Bounds.oneHourToOneYear())) {
      try {
        FitCommand.print(task.run(bounds), bounds, "SELECAO INTERNA");
      } catch (final FitModelTask.NoObservationsException e) {
        throw new CommandFailedException(List.of("  " + e.getMessage()));
      }
    }
    System.out.printf("%n  fit-inner nao chama FitModelTask.heldOut() em nenhum caminho --"
        + " o conjunto retido nao foi tocado por esta execucao.%n");
  }
}
