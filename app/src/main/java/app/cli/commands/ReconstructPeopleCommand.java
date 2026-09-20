package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.GitReconstruction;
import app.identity.port.PersonRegistry;
import app.pipeline.ReconstructPeopleTask;
import java.util.ArrayList;
import java.util.List;

/** Rebuilds the git half of the registry out of the bundled repositories, then canonicalises. */
public final class ReconstructPeopleCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws Exception {
    final PipelineConfig config = context.config();
    final PersonRegistry registry = context.registry();

    final ReconstructPeopleTask task =
        new ReconstructPeopleTask(context.gitIdentities(), registry, config.strictness());

    final ReconstructPeopleTask.Report report;
    try {
      report = task.run();
    } catch (final ReconstructPeopleTask.NotBijectiveException e) {
      throw notBijective(e);
    }

    final GitReconstruction git = report.reconstruction();
    System.out.printf("%n  commits scanned      : %,d%n", git.commitsScanned());
    System.out.printf("  unresolvable         : %,d (%.3f%%)%n",
        git.unresolvedCommits(), 100 * git.unresolvedShare());
    System.out.printf("  person ids mapped    : %,d%n", git.identities().size());
    System.out.printf("  bijection violations : %d%n", git.bijectionViolations().size());
    System.out.printf("  wrote %,d identities to %s%n",
        report.identitiesWritten(), registry.describeIdentities());
    CanonicalPrinter.print(report.canonical(), registry.describeCanonical());
  }

  /**
   * The abort message for a join that is not one-to-one.
   *
   * <p>Only the first few violations are shown: the point is to make the premise visible, and a
   * corpus that breaks the key tends to break it thousands of times over.
   */
  private static CommandFailedException notBijective(
      final ReconstructPeopleTask.NotBijectiveException e) {

    final List<String> lines = new ArrayList<>();
    lines.add("");
    lines.add("  ABORTING -- " + e.getMessage());
    lines.add("  The name-and-email primary key does not hold for this corpus, so every");
    lines.add("  merge below it would rest on a false premise. First few:");
    e.violations().stream().limit(10).forEach(violation -> lines.add("    " + violation));
    return new CommandFailedException(lines);
  }
}
