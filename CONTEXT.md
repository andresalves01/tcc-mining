# Issue duration from In Progress

How many active business days an issue of an Apache Java project takes from the moment work on it
starts to its first resolution, estimated from what was known when the work started, and how much
more is learned from what happens during the work. It is the only study this repository serves.

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
An account whose activity is automated; it never counts as a person in any attribute.

### Duration

**Day boundary**:
The hour, in UTC, at which one working date ends and the next begins for one issue.

**Business days**:
The Monday-to-Friday dates reached after the date of one instant, up to and including the date of a
later one, with dates cut at the day boundary; holidays are not known.

**Duration**:
The business days from the start to the first resolution, without the periods the issue spent back
in Open or Reopened. It is the target of every model.
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
The four attributes about releases and Blocker issues: the fix-version flag, the release cycle and
the Blocker pace at the start; the urgency in retrospect.

**Fix-version flag**:
Whether the issue had a fix version before its start.

**Release cycle**:
The project's median gap between past releases minus the calendar days since its last release before
the start; negative when the release is overdue.

**Blocker pace**:
The median creation-to-resolution time, in calendar days, of the project's other Blocker-priority
issues, read from the training side and from issues outside the population, never the test side.

**Urgency**:
The calendar days from the first resolution to the release of the issue's fix version.

### Models

**Own specification** (especificação própria):
The start attributes the author brought to the study, as opposed to those taken from the literature
families; the three similarity medians and the three start pressure attributes among them.
_Avoid_: revised specification, C1

**Own specification without similarity**:
The own specification minus the three similarity medians; a control showing what similarity adds.

**Similarity-alone model**:
A model that reads only the three similarity medians.

**Start model**:
Any model that reads only start attributes: the own specification's (with and without similarity),
the similarity-alone model, the literature families' and their union.

**Own specification model**:
The start model fitted on the own specification, on which the fitting cut is chosen. Without its
three start pressure attributes it is the **own start model**, the one chosen for the study, which a
scheduler would use when the work starts and on which the retrospective model builds.

**Retrospective model**:
The own start model plus the retrospective attributes, read twice. Its **main reading** leaves out
the comments and the participants, which count the very records the target is built from; the
reading **with discussion** adds those two and is a **ceiling**, an upper reference of what was
recorded during the work, not a competitor of the other models. The wait on blocking issues is in
neither: counted in the target's own unit, it equalled the target in about half of the issues that
had one.
_Avoid_: reading the ceiling's gain as a result of its own

**Before pressure**:
Said of a model without the pressure attributes, compared with the same model with them.

**Literature families**:
The attribute sets taken from Al-Zubaidi and from Kikas (partial), read at the start; their
**union** with the own specification is a model too.

**Reference median**:
A constant estimate learned on the fitting rows: the **global median**, the **project median** or
the **executor-project median**.
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
Said of the pressure attributes in one model when the paired comparison with its before-pressure
version lowers the error, or improves the ordering without raising the error.

**Prediction interval**:
The range around an estimate that holds a stated share of such issues (80% or 95%), calibrated on
the inner validation.

**Prediction quartile**:
One of the four ranges of a model's own estimates on the calibration issues; each has its own
interval width.
