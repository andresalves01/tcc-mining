package app.cli.commands;

import app.cli.CommandRunner;
import app.cli.Context;
import app.identity.PersonRecord;
import app.identity.Source;
import app.identity.port.PersonRegistry;
import java.util.Map;

/** Summarises what the registry holds, split by the system each identity was observed in. */
public final class StatsCommand implements CommandRunner {

  @Override
  public void run(final Context context) {
    final PersonRegistry registry = context.registry();
    final Map<String, PersonRecord> records = registry.readAll();
    final long git = records.values().stream()
        .filter(record -> record.source() == Source.GIT).count();
    final long jira = records.values().stream()
        .filter(record -> record.source() == Source.JIRA).count();
    System.out.printf("  registry %s%n", registry.describeIdentities());
    System.out.printf("    total : %,d%n", records.size());
    System.out.printf("    git   : %,d%n", git);
    System.out.printf("    jira  : %,d%n", jira);
    System.out.printf("    bots  : %,d%n", PersonRegistry.countBots(records.values()));
  }
}
