package app.cli.commands;

import app.cli.CommandFailedException;
import app.study.model.Score;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.pipeline.IssuesEvaluationTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the issue-duration study's commands print and record the same way. */
final class IssuesPrinting {

  private IssuesPrinting() {}

  static List<RowCache.Entry> cache() throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<absolute path> is required"));
    }
    try {
      return RowCache.read(Path.of(cache));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read " + cache + ": " + e));
    }
  }

  /**
   * CACHE read only when it is the registered in-progress study's matrix, by SHA-256 ({@link
   * RegistrationInProgress#MATRIX_SHA256}): the registered grid and check were declared on it, and
   * since ticket 21 the extraction writes a matrix of another population and split.
   */
  static List<RowCache.Entry> registeredCache(final String stage) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<absolute path> is required"));
    }
    refuseUnregistered(Path.of(cache), stage);
    return cache();
  }

  /** Refuses, before anything else of it is read, a matrix that is not the registered one. */
  static void refuseUnregistered(final Path cache, final String stage)
      throws CommandFailedException {
    final String sha;
    try {
      sha = IssuesReservedCommand.sha256(cache);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read " + cache + ": " + e));
    }
    if (!RegistrationInProgress.MATRIX_SHA256.equals(sha)) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was read, fitted or recorded: "
          + stage + " reads only the registered in-progress study's matrix (SHA-256 "
          + RegistrationInProgress.MATRIX_SHA256 + "); " + cache + " has " + sha + ". A matrix "
          + "of the random split (ticket 21) is the similarity chain's, not this one's"));
    }
  }

  /** POPULATIONS=FIXED,BROAD,INHERITED (default: all three, the main one first). */
  static Set<Population> populations() throws CommandFailedException {
    final String env = System.getenv("POPULATIONS");
    if (env == null || env.isBlank()) {
      return EnumSet.copyOf(Population.MAIN);
    }
    final Set<Population> out = EnumSet.noneOf(Population.class);
    try {
      for (final String p : env.split(",")) {
        final Population population = Population.valueOf(p.strip());
        if (!Population.MAIN.contains(population)) {
          throw new IllegalArgumentException(p);
        }
        out.add(population);
      }
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  POPULATIONS accepts FIXED, BROAD, INHERITED"));
    }
    return out;
  }

  static String name(final Population p) {
    return switch (p) {
      case FIXED -> "FIXED ONLY (main)";
      case BROAD -> "BROAD (sensitivity)";
      case INHERITED -> "INHERITED (conditional)";
      case IN_PROGRESS -> "FIXED ONLY, FROM IN PROGRESS";
    };
  }

  static void header() {
    System.out.printf("  %-52s %6s %9s %8s %8s %9s %8s %8s%n", "specification", "n", "MAE h",
        "MAE d", "gain%", "vs const%", "Spearman", "pairwise");
  }

  static void row(final Score s) {
    System.out.printf("  %-52s %6d %9.2f %8.2f %8.1f %9.1f %8.3f %8.1f%n", s.label(), s.n(),
        s.mae(), s.maeDays(), s.gainPercent(), s.gainOverConstantPercent(), s.spearman(),
        100 * s.pairwise());
  }

  static void outcome(final Score.Outcome o) {
    System.out.printf("  target (hours): n %,d  mean %.1f  median %.1f  p25 %.1f  p75 %.1f  "
        + "p90 %.1f  max %.0f%n", o.n(), o.meanHours(), o.medianHours(), o.p25Hours(),
        o.p75Hours(), o.p90Hours(), o.maxHours());
  }

  /** The whole evaluation of one population. */
  static List<Map<String, Object>> evaluation(final IssuesEvaluationTask.Report r) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    outcome(r.outcome());
    System.out.printf("%n  MAIN TABLE (gain%% against the project median; vs const%% against the "
        + "constant; pairwise in %%)%n");
    header();
    for (final var e : r.references()) {
      row(e.fitted().score());
      rows.add(scoreRow(r.population(), "reference", e.fitted().score()));
    }
    for (final var e : r.evaluated()) {
      row(e.fitted().score());
      final Map<String, Object> m = scoreRow(r.population(), "specification", e.fitted().score());
      m.put("alpha", e.fitted().alpha());
      m.put("lambda", e.fitted().lambda());
      m.put("columns", e.fitted().columns());
      m.put("ci_gain_project", List.of(e.overProject().low(), e.overProject().high()));
      m.put("ci_gain_constant", List.of(e.overConstant().low(), e.overConstant().high()));
      m.put("ci_gain_pair", List.of(e.overPair().low(), e.overPair().high()));
      m.put("mae_1h_365d", e.window().mae());
      m.put("pairwise_1h_365d", e.window().pairwise());
      m.put("median_error", e.errors().p50());
      rows.add(m);
    }

    System.out.printf("%n  GAIN WITH A 95%% INTERVAL RESAMPLING WHOLE PROJECTS (%d draws)%n",
        app.issues.model.ProjectBootstrap.DRAWS);
    final boolean started = r.population() == Population.IN_PROGRESS;
    System.out.printf("  %-52s %24s %24s %24s%n", "specification", "over the project median",
        "over the constant", started ? "over the pair median" : "over the pair (C2 only)");
    for (final var e : concat(r.references(), r.evaluated())) {
      System.out.printf("  %-52s %24s %24s %24s%n", e.fitted().score().label(),
          interval(e.overProject()), interval(e.overConstant()), interval(e.overPair()));
    }

    System.out.printf("%n  WHERE THE ERROR LIES: percentiles of the absolute error (h) and the mean without "
        + "the slowest tenth by true duration%n");
    System.out.printf("  %-52s %8s %8s %8s %8s %9s %9s%n", "specification", "mean", "p50", "p75",
        "p90", "p99", "trimmed");
    for (final var e : concat(r.references(), r.evaluated())) {
      final var x = e.errors();
      System.out.printf("  %-52s %8.1f %8.1f %8.1f %8.1f %9.1f %9.1f%n",
          e.fitted().score().label(), x.mae(), x.p50(), x.p75(), x.p90(), x.p99(), x.trimmedMae());
    }

    System.out.printf("%n  BY BAND OF TRUE DURATION, AT PERCENTILES 25/50/75/90 (MAE in hours)%n");
    final var first = r.references().get(0).bands();
    System.out.printf("  %-52s", "specification");
    first.forEach(b -> System.out.printf(" %16s", b.label()));
    System.out.println();
    System.out.printf("  %-52s", "  (n / median true duration)");
    first.forEach(b -> System.out.printf(" %16s",
        String.format("%d / %.0fh", b.n(), b.medianTruth())));
    System.out.println();
    for (final var e : concat(r.references(), r.evaluated())) {
      System.out.printf("  %-52s", e.fitted().score().label());
      e.bands().forEach(b -> System.out.printf(" %16.1f", b.mae()));
      System.out.println();
    }

    final var w0 = r.references().get(0).window();
    System.out.printf("%n  THE 1 HOUR TO 365 DAYS BAND, READ WITHOUT FILTERING: %,d rows; 1 h falls at "
        + "percentile %.1f and 365 d at percentile %.1f of true duration%n", w0.n(), w0.lowPercentile(),
        w0.highPercentile());
    System.out.printf("  %-52s %9s %9s %9s%n", "specification", "MAE h", "median", "pairwise");
    for (final var e : concat(r.references(), r.evaluated())) {
      System.out.printf("  %-52s %9.1f %9.1f %9.1f%n", e.fitted().score().label(),
          e.window().mae(), e.window().medianError(), 100 * e.window().pairwise());
    }

    final var b0 = r.references().get(0).body();
    System.out.printf("%n  THE BODY OF THE DISTRIBUTION (percentiles 25 to 90 of true duration: %.1f h to %.1f h, "
        + "%,d rows)%n", b0.lowHours(), b0.highHours(), b0.n());
    System.out.printf("  %-52s %9s %9s %9s %9s%n", "specification", "MAE h", "median", "Spearman",
        "pairwise");
    for (final var e : concat(r.references(), r.evaluated())) {
      final var b = e.body();
      System.out.printf("  %-52s %9.1f %9.1f %9.3f %9.1f%n", e.fitted().score().label(), b.mae(),
          b.medianError(), b.spearman(), 100 * b.pairwise());
    }

    System.out.printf("%n  FAMILY AGAINST FAMILY, %s (C1)%n",
        started ? "AT THE FIRST IN PROGRESS" : "AT CREATION");
    gaps(r.familiesByProjects());
    comparisons(r.families());
    System.out.printf(started ? "%n  C2 BY BLOCK AGAINST C1 OF THE SAME FAMILY (P19: diagnostic; "
        + "exposure without D06, P20)%n"
        : "%n  C2 BY BLOCK AGAINST C1 OF THE SAME FAMILY (decision 14: content justifies "
        + "decomposing; exposure does not)%n");
    gaps(r.blocksByProjects());
    comparisons(r.blocks());

    System.out.printf("%n  SENSITIVITIES (same parameters)%n");
    System.out.printf("  %-44s %-40s %10s %10s %10s %10s%n", "reading", "cell", "MAE h base",
        "MAE h var.", "pair base", "pair var.");
    for (final var s : r.sensitivities()) {
      System.out.printf("  %-44s %-40s %10.2f %10.2f %10.1f %10.1f%n", s.what(), s.cell(),
          s.base().mae(), s.changed().mae(), 100 * s.base().pairwise(),
          100 * s.changed().pairwise());
    }

    if (!r.recalibrated().isEmpty()) {
      recalibration(r);
    }
    if (r.mode() == app.issues.model.Fitting.Mode.INNER) {
      weights(r);
    }
    return rows;
  }

  private static void recalibration(final IssuesEvaluationTask.Report r) {
    System.out.printf("%n  LINEAR RECALIBRATION IN LOG, BESIDE THE MODEL (requested on 29/09/2026, "
        + "to decide after seeing it; it does not replace the model)%n");
    System.out.printf("  log1p(h) = a + b * prediction in log, with a and b by least squares on the "
        + "fit; monotone: Spearman and pairwise do not change%n");
    System.out.printf("  %-26s %7s %6s %9s %9s  %-24s %-24s %-16s %-16s%n", "cell", "a", "b",
        "MAE h", "recal.", "vs const % (model)", "vs const % (recal.)", "p10/p50/p90 mod.",
        "p10/p50/p90 rec.");
    for (final var c : r.recalibrated()) {
      System.out.printf("  %-26s %7.3f %6.3f %9.2f %9.2f  %-24s %-24s %-16s %-16s%n", c.cell(),
          c.a(), c.b(), c.base().mae(), c.recalibrated().mae(),
          interval(c.baseOverConstant()), interval(c.overConstant()), spread(c.baseSpread()),
          spread(c.spread()));
    }
    System.out.printf("%n  RECALIBRATION BY BAND OF TRUE DURATION (MAE in hours: model -> "
        + "recalibrated)%n");
    for (final var c : r.recalibrated()) {
      final StringBuilder line = new StringBuilder(String.format("  %-26s", c.cell()));
      for (int i = 0; i < c.bands().size(); i++) {
        line.append(String.format("  %7.1f -> %7.1f", c.baseBands().get(i).mae(),
            c.bands().get(i).mae()));
      }
      line.append(String.format("   body p25-p90 %6.1f -> %6.1f", c.baseBody().mae(),
          c.body().mae()));
      System.out.println(line);
    }
  }

  /** What each column weighed in each fit: a description of the fit, read from the fitting side. */
  static void weights(final IssuesEvaluationTask.Report r) {
    System.out.printf("%n  WEIGHT OF EACH COLUMN IN THE FIT (standardised coefficient | own rho, "
        + "both on the fitting side)%n");
    for (final var e : r.evaluated()) {
      System.out.printf("  -- %s  (alpha %.2f, lambda %.6g)%n", e.fitted().cell().label(),
          e.fitted().alpha(), e.fitted().lambda());
      if (!e.fitted().dropped().isEmpty()) {
        System.out.printf("     dropped for being constant: %s%n", e.fitted().dropped());
      }
      e.fitted().contributions().stream()
          .sorted((a, b) -> Double.compare(Math.abs(b.coefficient()), Math.abs(a.coefficient())))
          .forEach(c -> System.out.printf("     %-44s %+9.4f | %+.3f%s%n", c.column(),
              c.coefficient(), c.ownSpearman(), c.selected() ? "" : "   (zeroed)"));
    }
  }

  private static String spread(final double[] p) {
    return String.format("%.0f/%.0f/%.0f", p[0], p[1], p[2]);
  }

  private static <T> List<T> concat(final List<T> a, final List<T> b) {
    final List<T> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  static String interval(final app.issues.model.ProjectBootstrap.Interval i) {
    return String.format("%+6.1f [%+6.1f, %+6.1f]", i.gain(), i.low(), i.high());
  }

  /** The registered tie criterion: the paired interval from resampling whole projects. */
  private static void gaps(final List<app.issues.model.ProjectBootstrap.Gap> list) {
    System.out.printf("  paired resampling of PROJECTS, %d draws (registered criterion: "
        + "interval containing zero = tie); diff. = MAE(A) - MAE(B), negative when A errs less%n",
        app.issues.model.ProjectBootstrap.DRAWS);
    for (final var g : list) {
      System.out.printf("    %-30s x %-30s  diff. %+8.2f h  CI [%+8.2f, %+8.2f]  %s%n", g.a(),
          g.b(), g.gap(), g.low(), g.high(), g.tied() ? "TIE" : "distinguishable");
    }
  }

  private static void comparisons(final List<app.study.model.Bootstrap.Comparison> list) {
    System.out.printf("  paired resampling of ROWS, %d draws (narrower reading, alongside)%n", app.study.model.Bootstrap.DRAWS);
    for (final var c : list) {
      System.out.printf("    %-30s x %-30s  A wins in %5.1f%%  mean diff. %+8.2f h  CI [%+8.2f, "
          + "%+8.2f]  %s%n", c.a(), c.b(), 100 * c.winRate(), c.meanGap(), c.lowGap(), c.highGap(),
          c.indistinguishable() ? "TIE" : "distinguishable");
    }
  }

  static Map<String, Object> scoreRow(final Population p, final String kind, final Score s) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("population", p.name());
    row.put("kind", kind);
    row.put("specification", s.label());
    row.put("n", s.n());
    row.put("mae_hours", s.mae());
    row.put("mae_days", s.maeDays());
    row.put("gain_percent", s.gainPercent());
    row.put("gain_over_constant_percent", s.gainOverConstantPercent());
    row.put("spearman", s.spearman());
    row.put("pairwise_accuracy", s.pairwise());
    return row;
  }
}
