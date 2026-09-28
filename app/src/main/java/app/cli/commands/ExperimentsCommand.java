package app.cli.commands;

import app.cli.CommandRunner;
import app.cli.Context;
import app.model.Bounds;
import app.model.Population;
import app.pipeline.ExperimentsTask;
import java.util.List;

/**
 * Prints one tab-separated row per held-out observation, per population, with every prediction.
 *
 * <p>Printing rather than writing a file keeps the command in the shape every other one has, and
 * keeps the task free of the filesystem. Redirect it where you want it.
 */
public final class ExperimentsCommand implements CommandRunner {

  /** The pre-registered arms, in order. See papers/01-ttc-semantics/PRE-REGISTRO-ANALISE5.md. */
  private static final List<Population> POPULATIONS = List.of(
      Population.all(),
      Population.withoutPostdated(),
      Population.withoutImmediateByMinute(),
      Population.withoutImmediateByHour());

  @Override
  public void run(final Context context) {
    final ExperimentsTask task = new ExperimentsTask(context.observations(), context.progress());
    final ExperimentsTask.Report report = task.run(POPULATIONS, Bounds.none());

    System.out.println("## hiperparametros escolhidos na divisao interna");
    System.out.println("#pop\talpha\tlambda\ttrees\tmax_nodes\tshrinkage"
        + "\tn_fit\tn_selecao\tn_treino\tn_teste\tmae_interno_en\tmae_interno_gb");
    for (final ExperimentsTask.Chosen c : report.chosen()) {
      System.out.printf("#%s\t%.4f\t%.8g\t%d\t%d\t%.4f\t%d\t%d\t%d\t%d\t%.6f\t%.6f%n",
          c.population(), c.alpha(), c.lambda(), c.trees(), c.maxNodes(), c.shrinkage(),
          c.fitRows(), c.selectionRows(), c.trainRows(), c.testRows(),
          c.innerMaeElasticNet(), c.innerMaeBoosting());
    }
    System.out.println("populacao\tissue_id\tproject\tdeveloper\tttc_days\td2_days"
        + "\tpred_en\tpred_gb\tref_const\tref_project\tref_pair");
    for (final ExperimentsTask.Row r : report.rows()) {
      System.out.printf("%s\t%s\t%s\t%s\t%.8f\t%.8f\t%.8f\t%.8f\t%.8f\t%.8f\t%.8f%n",
          r.population(), r.issueId(), r.project(), r.developer(),
          r.ttcDays(), r.postCommitDays(),
          r.predElasticNet(), r.predBoosting(),
          r.refConstant(), r.refProject(), r.refPair());
    }
  }
}
