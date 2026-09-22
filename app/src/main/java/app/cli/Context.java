package app.cli;

import app.config.PipelineConfig;
import app.cost.port.ContributionLedger;
import app.cost.port.ContributionSource;
import app.features.port.CommitComplexitySource;
import app.features.port.CommitComplexityStore;
import app.features.port.FeatureStore;
import app.features.port.IssueAttributeSource;
import app.identity.port.GitIdentitySource;
import app.identity.port.JiraIdentitySource;
import app.identity.port.PersonRegistry;
import app.labels.port.LabelSource;
import app.labels.port.LabelStore;
import app.model.port.ObservationSource;
import app.paper4.port.MergedPullRequestSource;
import app.paper4.port.PullRequestExtraSource;
import app.pipeline.port.Corpus;
import app.pipeline.port.Progress;

/**
 * What every command is handed: the resolved configuration and the ports it may reach the data
 * through.
 *
 * <p>Ports rather than a database handle, so no command names a storage technology and none of
 * them repeats the wiring. Which adapters these are is decided once, in {@code App}; swapping them
 * for in-memory doubles is what makes a runner testable without a server.
 *
 * <p>{@code progress} is the one port that carries nothing out of storage: it is where a stage says
 * what it is doing while it does it, so an adapter reading 84 GB does not also decide how a terminal
 * renders the fact.
 *
 * <p>Every command is handed all of them. A command uses two or three, and the alternative --
 * a context per command, or a lookup by type -- buys nothing here: the adapters are field
 * assignments, so building the set costs nothing a command would notice.
 *
 * <p>{@code externalRegistry}, {@code externalLedger} and {@code externalComplexity} point at
 * {@code LEDGER_DB} rather than {@code TARGET_DB} -- see {@link PipelineConfig#ledgerDb()}. Every
 * other field keeps reading and writing {@code TARGET_DB} exactly as before; these three exist so a
 * stage deriving labels from a different outcome can read an already-built identity registry,
 * ledger and complexity table without writing anywhere near the database they came from. Unused by
 * every command that predates them.
 */
public record Context(
    PipelineConfig config,
    PersonRegistry registry,
    GitIdentitySource gitIdentities,
    JiraIdentitySource jiraIdentities,
    ContributionSource contributions,
    ContributionLedger ledger,
    LabelSource labels,
    LabelStore labelStore,
    IssueAttributeSource issueAttributes,
    FeatureStore featureStore,
    CommitComplexitySource commitComplexity,
    CommitComplexityStore complexityStore,
    ObservationSource observations,
    Corpus corpus,
    Progress progress,
    PersonRegistry externalRegistry,
    ContributionLedger externalLedger,
    CommitComplexityStore externalComplexity,
    MergedPullRequestSource pullRequests,
    IssueAttributeSource pullRequestAttributes,
    ContributionLedger pullRequestPresence,
    PullRequestExtraSource pullRequestExtras) {}
