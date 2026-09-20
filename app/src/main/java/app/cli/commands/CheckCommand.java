package app.cli.commands;

import app.cli.Command;
import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pipeline.port.Corpus;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reports what the source corpus holds, and which commands that leaves runnable.
 *
 * <p>Reads no more than collection metadata, so it answers in a second on a corpus the mining
 * stages take an hour to cross. It is the one command worth running before the pipeline: every
 * other diagnosis of a wrong {@code SOURCE_DB} or of the small release costs a full pass first.
 *
 * <p>What a stage needs is not decided here. This asks the {@link Corpus} port and translates its
 * stages back into the command names the operator typed.
 */
public final class CheckCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Corpus.Survey survey = context.corpus().survey();

    System.out.printf("  server           : %s   (release documented against %s)%n",
        survey.server(), survey.documentedServer());
    System.out.printf("  source database  : %s%n%n", survey.database());

    System.out.printf("  %-24s %16s   %s%n", "collection", "documents", "read by");
    survey.entries().forEach(entry -> System.out.printf("  %-24s %16s   %s%n",
        entry.name(), entry.state(), String.join(", ", commandsFor(entry.neededBy()))));

    final List<String> blocked = Arrays.stream(Command.values())
        .filter(command -> command.stage().filter(stage -> !survey.satisfies(stage)).isPresent())
        .map(command -> command.token())
        .toList();

    if (blocked.isEmpty()) {
      System.out.printf("%n  every stage has what it reads.%n");
      return;
    }
    throw new CommandFailedException(Stream.concat(
        Stream.of("", "  cannot run: " + String.join(", ", blocked)),
        survey.diagnosis().stream()).toList());
  }

  /** The commands that drive the given stages, named as they are typed. */
  private static List<String> commandsFor(final Set<Corpus.Stage> stages) {
    return Arrays.stream(Command.values())
        .filter(command -> command.stage().filter(stages::contains).isPresent())
        .map(command -> command.token()).toList();
  }
}
