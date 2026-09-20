package app.cli.commands;

import app.cli.CommandRunner;
import app.cli.Context;
import app.identity.port.PersonRegistry;
import app.pipeline.CanonicalizeTask;

/**
 * Recomputes the equivalence relation from whatever the registry already holds.
 *
 * <p>Reads no source collection, so it is cheap enough to re-run while comparing strictness
 * settings.
 */
public final class CanonicalizeCommand implements CommandRunner {

  @Override
  public void run(final Context context) {
    final PersonRegistry registry = context.registry();
    CanonicalPrinter.print(new CanonicalizeTask(registry, context.config().strictness())
        .run(registry.readAuthoredCommits()), registry.describeCanonical());
  }
}
