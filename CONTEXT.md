# Issue duration from In Progress

How many recorded business days an issue of an Apache Java project takes from its first transition
to In Progress to its first resolution, estimated under the declared start-attribute and
training-history protocol, and how much more is associated with retrospective attributes.
The interval measures the recorded workflow: neither status is a validated boundary of actual
implementation or person-effort. It is the only study this repository serves.

_Avoid_: secondary study, in-progress variant, main study (there is no other study to be secondary to)

## Language

### Issues and people

**Issue**:
A Jira issue of one of the studied projects, counted once per key in the project it belongs to.
_Avoid_: ticket, task

**Start**:
The issue's first status change to In Progress.
_Avoid_: t0, click, creation (the creation is a different, earlier instant)

**First resolution**:
The issue's first status change to Resolved or Closed; later reopenings do not move it.
_Avoid_: close date, final resolution

**Clicker**:
The person who made the issue's start.

**Executor**:
The person assigned to the issue at its start, or the clicker when nobody was assigned.
_Avoid_: assignee, developer, owner

**Reporter**:
The person who opened the issue.

**Bot account**:
An account identified as automated by the declared filter. It is excluded wherever the attribute
explicitly requires human discussion or tracker activity. The adapted Kikas counts retain their
existing extraction definitions and must not automatically be described as human-only counts.

### Duration

**Day boundary**:
The hour, in UTC, at which one working date ends and the next begins for one issue.

**Business days**:
The Monday-to-Friday dates reached after the date of one instant, up to and including the date of a
later one, with dates cut at the day boundary; holidays are not known.

**Duration**:
The business days from the start to the first resolution, without the periods the issue spent back
in Open or Reopened. Days without recorded activity stay; review in Patch Available stays.
It is the target of every model and every duration history. This is operational duration, not
measured person-hours of effort.
_Avoid_: TTC, time-to-code, hours, lead time, resolution time

**Population**:
The issues resolved as Fixed that have a start and a duration of at least one business day; every
estimate is conditional on the issue outlasting its start's working date.

**Fence**:
The duration Q3 + k·IQR, computed on the fitting side only, above which an issue is an extreme of
the tail for that k.

**Usual flow** (fluxo usual):
The issues whose duration is at or below the fence with k = 3.
_Avoid_: normal flow

**Fitting cut**:
The removal from the fitting rows of the issues above the fence of the chosen k.
_Avoid_: outlier removal, trimming

### Splits

**Training side** / **Test side**:
The two parts of a random split stratified by project: about 70% of each project's issues to
training, the rest to test.

**Outer training** / **Outer test**:
The split of the whole population; the outer test is sealed until the author opens it once.
_Avoid_: reserved part, holdout

**Inner fitting** / **Inner validation**:
The same split drawn again inside the outer training; every choice of the study is made on it.

**Sealed**:
Said of the outer test: nothing is run, read or reported on it until the author opens it.

**Label**:
An issue's duration, whether a model learns from it or a history reads it.

**History**:
The labels of other issues that an attribute summarises; it reads only the training side of its
split, any date, and never the issue's own label.

### Attributes

**Attribute**:
A value describing an issue that a model reads.
_Avoid_: feature, predictor, column (a column is how an attribute is stored)

**Start attribute**:
An attribute known at the issue's start (or a history, bounded by the split instead of the date).

**Retrospective attribute**:
An attribute that uses what became known after the start, during the work or after the first
resolution; it can grow with the duration itself, so a gain from it is association, not prediction.

**Description at the start**:
The issue's description as it stood at its start, with Jira markup removed.

**Similarity median**:
The median duration of the historical issues whose descriptions at the start are closest to this
one's: by shared words (**Jaccard median**), by sentence embeddings (**embedding median**), or among
the members of the nearest embedding cluster (**cluster median**). Read only inside a model, never
as an estimate on its own.
_Avoid_: direct predictor, similarity score

**Reporter median**:
The median duration of the reporter's other issues in the same project, from the history.

**Pressure attributes**:
The former start attributes about releases and Blocker issues—the fix-version flag, the release
cycle and Blocker pace—and the retrospective release lag. The three start attributes were tried in
earlier runs but are no longer written or read by a current model; the release-lag column remains
in the matrix for audit but is excluded from the final own retrospective models.

**Fix-version flag**:
Whether the issue had a fix version before its start.

**Release cycle**:
The project's median gap between past releases minus the calendar days since its last release before
the start; negative when the release is overdue.

**Blocker pace**:
The median creation-to-resolution time, in calendar days, of the project's other Blocker-priority
issues, read from the training side and from issues outside the population, never the test side.

**Release lag (formerly urgency)**:
The calendar days from the first resolution to the release of the issue's fix version.

### Models

**Own specification** (especificação própria):
The start attributes the author brought to the study, as opposed to those taken from the literature
families: description length, team size, the team's code size and complexity, the reporter median,
the executor's code size, complexity and open workload, and the three similarity medians.
_Avoid_: revised specification, C1

**Own start model**:
The start model fitted on the own specification; the fitting cut's k is chosen on it alone, and the
retrospective model builds on it. Its intended SPSP connection does not establish adequate effort
estimation or scheduling performance.
_Avoid_: own specification with similarity (the earlier model that also read the start pressure
attributes)

**Start model**:
Any model that reads only start attributes: the own start model and the literature families' start
models.

**Retrospective model**:
The own start model plus the retrospective attributes, read twice. Its **main reading** leaves out
the comments and the participants, preserving the declared attribute specification; the
reading **with discussion** adds those two and is a **ceiling**, an upper reference of what was
recorded during the work, not a competitor of the other models. The wait on blocking issues is in
neither: counted in the target's own unit, it equalled the target in about half of the issues that
had one.
_Avoid_: reading the ceiling's gain as a result of its own

**Literature families**:
The attribute sets taken from Al-Zubaidi (at the start) and from Kikas (at the start, and
retrospective: Kikas's start set plus its counts observed up to the resolution). Each is measured as
an attribute set on the study's own estimators, not as its source's method.
_Avoid_: union (the families are no longer read together with the own specification)

**Attribute set**:
One of the six sets the study compares, each fitted by an Elastic Net (the main reading) and by a
random forest (the robustness check): at the start, the own start model, Al-Zubaidi and Kikas; after
the resolution, the retrospective model's main reading, its ceiling and Kikas's retrospective set.
With the two reference medians they make the fourteen models of both the inner report and the outer
test.

**Before pressure**:
Said of a model without the start pressure attributes; the own start model is the own specification
before pressure.

**Reference median**:
A constant estimate learned on the fitting rows: the **global median** or the **project median**.
_Avoid_: baseline

### Evaluation

**Usual-flow reading**:
The evaluation on the validation issues of the usual flow; the main reading.

**Complementary reading**:
The same evaluation on every validation issue, the tail included.

**Paired comparison**:
The difference between two models on the same issues, with a 95% interval from resampling whole
projects.

**Useful**:
Said of the pressure attributes in the earlier comparisons when their addition lowered the error,
or improved the ordering without raising the error; the rule by which the earlier runs left the
three start attributes out of the current model.

**Attribute relevance**:
Which attributes carry a model: the Elastic Net's coefficients on standardised columns, each with a
95% interval from resampling whole projects of the fitting rows and refitting on each draw; in the
inner report also the forest's permutation importance, the rise in error when one column is shuffled
among the validation issues, which the outer test does not read since it would read the test's
labels.

**Prediction interval**:
The range around an estimate that holds a stated share of such issues (80% or 95%), calibrated on
the inner validation.

**Prediction quartile**:
One of the four ranges of a model's own estimates. It does not choose a calibration factor:
each model and interval level uses one pooled inner-validation factor.

**Prediction percentile illustration**:
A model's P25, P50, P75, P90 or P95 estimate on the scored issues, with its existing prediction
interval. It does not establish coverage conditional on that percentile.

**Relative error**:
The observed duration minus the estimate, divided by the estimate. Positive means underestimation.
Zero estimates remain in the population and absolute-error measures, but have undefined relative
error and are counted separately. The mean signed relative error measures bias; its pooled sample
standard deviation describes spread. Shares within ±25%, ±50% and ±100% use positive estimates.

**SPSP uncertainty context**:
Comparison with published effort-uncertainty scenarios. Under fixed staff dedication, effort and
duration have equal relative errors only under that allocation assumption; the study's
status-adjusted business days measure recorded workflow duration, not observed person-effort.
Pooled error spread is not each activity's conditional uncertainty, and a tested scenario is not
a validated scheduling tolerance. These readings choose no model or population.
