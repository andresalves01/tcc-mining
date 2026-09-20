package app.cli;

/**
 * One CLI command, already given everything it needs to run.
 *
 * <p>A runner takes the ports it needs off the {@link Context}, hands them to a task in
 * {@code app.pipeline} and prints the result. It builds no adapters and names no storage
 * technology. It holds no state, so {@link Command} can keep a single instance per constant.
 *
 * <p>Failure is signalled with {@link CommandFailedException} rather than {@code System.exit}: a
 * runner that exits mid-flight would leave the {@link Context}'s Mongo client unclosed and would be
 * untestable without a forked JVM. Exit codes are decided in one place, {@code App}.
 */
@FunctionalInterface
public interface CommandRunner {

  void run(Context context) throws Exception;
}
