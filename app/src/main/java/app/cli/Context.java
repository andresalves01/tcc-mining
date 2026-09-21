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
    Corpus corpus,
    Progress progress) {}
