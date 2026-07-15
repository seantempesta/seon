---
type: research
status: completed
tags: [research, orchestrator, flow]
---

# Orchestrator role audit — 2026-07-14

## Decision

Retain `ORCHESTRATOR.md` as a thin compatibility and role overlay, not a second
repository instruction authority. Remove its duplicated operator, test,
provider, issue, model, and platform instructions. Keep only the few duties
unique to the top-level development agent: integration ownership, durable
context checkpoints, coherent delegation, independent verification, and
shared-tree coordination.

Deleting the file would save little and would break useful historical links
plus Claude/Codex role adapters that still name the orchestrator workflow. A
thin file preserves that compatibility without asking current models to load a
291-line duplicate manual.

## Why the old shape existed

The original workflow assumed the top-level model had a scarce, irrecoverable
context window. It therefore told the orchestrator to avoid implementation,
delegate nearly everything, and treat agent tokens as cheap. That was a useful
guard for weaker models and clients without durable compaction, goals, plans,
or shared repository evidence.

The current harness changes the tradeoff:

- compaction resumes from a maintained task summary instead of asking the
  human to restate the project;
- goals and task plans persist independently of the prompt window;
- all agents share one workspace and can return durable files;
- the roadmap, issue corpus, research reports, retained test artifacts, and
  small commits provide authoritative resume state; and
- stronger models can integrate broad source context directly without
  defaulting to an imperative or superficial implementation.

Delegation still improves parallel throughput and independent review. Forced
delegation can now hurt accuracy when it separates the integrator from the
source needed to reconcile overlapping changes or creates several partial
audits of one question.

## Audit findings

Before cleanup, `ORCHESTRATOR.md` was 291 lines beside a 435-line root
`AGENTS.md`. Most of its operational content duplicated or lagged the root
authority:

- process topology, `bin/seon`, reset, logs, tests, browser/SSE, `agy`, and the
  default provider already live in `AGENTS.md` and localized authorities;
- fixed ACME ports and lifecycle examples were stale relative to dynamic
  cluster ownership and the active separately owned ACME lane;
- fixed test-duration claims become stale measurements;
- Claude-only Task tool and model aliases were presented as universal; and
- the claim that compaction forces a new human-explained session was false for
  the current harness.

The durable unique content was small:

1. the top-level agent owns integration and user communication;
2. scope agents by one coherent result, with exact sources and acceptance;
3. preserve status in repository artifacts before compaction;
4. verify agent claims against diffs and running behavior; and
5. coordinate shared mutable files, tests, processes, and destructive actions.

## Context-preservation cadence

The maintained cadence is event-driven rather than token-count-driven:

1. At start or after compaction, read root/local instructions, the active PRD
   ledger, `git status`, live-agent ownership, and retained proof paths.
2. Before a coherent unit, record its acceptance and delegate only independent
   work whose owner can return one integrated result.
3. When a finding changes the plan, write its issue/research/roadmap evidence
   immediately; never leave it only in chat.
4. Review and commit each coherent gain before starting another overlapping
   implementation.
5. At handoff, leave the roadmap honest and name incomplete proof, blockers,
   active agents, protected paths, and cleanup requiring owner authorization.

This cadence preserves accuracy across compaction without keeping the
orchestrator artificially ignorant of the code it must integrate.

## Acceptance

- `AGENTS.md` remains the only shared repository authority.
- `ORCHESTRATOR.md` contains no duplicate runtime command catalog, fixed ports,
  provider policy, testing manual, or Claude-only universal claim.
- Claude role aliases and historical links continue to resolve.
- A Codex or Claude top-level agent can resume from roadmap/issues/research/git
  state without conversation archaeology.
- Delegation is chosen for independence, parallelism, or verification—not as a
  blanket prohibition on direct implementation.

## Adapter follow-through

The subsequent compatibility audit found one remaining contradiction outside
`ORCHESTRATOR.md`: the Claude and Codex `seon-agent` descriptions still required
delegation for every implementation task, while both verifier adapters assumed
a mandatory lower-cost verification stage. Their long embedded manuals also
duplicated `AGENTS.md` and `AGENT.md`, creating another drift surface.

Both client formats now retain the role names but define them as selective,
bounded aliases. The implementation adapter points to the shared authority and
delegated-lane workflow; the verifier adapter is used when risk warrants an
independent falsification pass. Neither adapter prescribes universal delegation
or a cheaper model. Claude's metadata format and Codex's TOML format remain
separate so existing client configuration continues to load.
