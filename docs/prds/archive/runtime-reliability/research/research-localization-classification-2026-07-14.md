---
type: research
status: completed
tags: [research, prd, flow]
---

# Runtime-reliability research localization classification — 2026-07-14

## Scope and result

This read-only classification covers the 51 dated Markdown reports that were
already in `docs/prds/runtime-reliability/research/` before this report. It
compares each report with the nine carved successor PRD runbooks and roadmaps,
then inventories every repository Markdown backlink by basename.

The protected untracked
`docs/prds/repl-autosuggest/research/shared-schema-section-2026-07-13.md` was
excluded from every read and search. The active ACME worktree was not
inspected. No report was moved and no backlink was edited by this audit.

The result is:

- 18 reports remain with unit 0 because they are branch graduation,
  cross-cutover, documentation-authority, test-operator, or legacy-lane
  evidence;
- 23 reports have one clear successor owner and should move there;
- 10 reports span successor owners and should stay here as link-only shared
  inputs; and
- no old report has one-owner evidence for `database-browser`,
  `root-workspace-sessions`, or `agent-canvas-interaction`. Their new grounded
  audits are correctly authoritative.

“Link-only shared input” means a successor roadmap may link the report, but
must not copy its findings or treat the umbrella folder as current authority.

## Remain as unit-0 graduation evidence

| Report | Reason it remains here |
|---|---|
| `agents-claude-instruction-unification-2026-07-14` | Completed repository-wide instruction-authority cutover evidence. |
| `architecture-target-drift-audit-2026-07-14` | The program-level architecture/roadmap carve-out decision, not one successor's source audit. |
| `automatic-test-feedback-infrastructure-audit-2026-07-14` | Completed unit-0 edit-feedback/operator mechanism. |
| `cljs-test-suite-speed-and-quality-audit-2026-07-12` | Historical baseline for the completed unit-0 test-door correction. |
| `config-coherence-audit-2026-06-28` | Broad pre-refactor configuration/provider inventory with no single remaining successor owner. |
| `dependency-shadow-mcp-acme-audit-2026-07-14` | Crosses dependency bases, artifact flavors, MCP, ACME, and Inspect; it is the pre-integration unit-0 checkpoint. |
| `dependency-shadow-mcp-acme-post-integration-audit-2026-07-14` | Cross-flavor integration and current simultaneous-runtime proof. |
| `issue-authority-and-startup-triage-audit-2026-07-14` | Completed repository-wide issue authority and startup policy. |
| `issues-audit-2026-06-28` | Historical issue migration evidence with many archived-issue backlinks. |
| `jvm-archive-boundary-2026-07-13` | Justifies the central runtime cutover and deletion boundary; packaging is only one consequence. |
| `legacy-lane-retirement-audit-2026-07-14` | Unit-0 lane disposition and cleanup authorization boundary. |
| `phase-0-default-pod-live-baseline-2026-07-12` | Original whole-system baseline for this refactor. |
| `phase-1-baseline-2026-07-13` | Coordinated branch-cut baseline and artifact/process evidence. |
| `repo-rough-edges-2026-06-28` | Broad onboarding baseline, substantially superseded by unit-0 changes. |
| `test-impact-selection-and-runner-audit-2026-07-14` | Completed unit-0 changed-test selection design and proof. |
| `test-runtime-trim-design-2026-07-12` | Historical input to the completed test-runner correction. |
| `token-reporting-surface-audit-2026-07-12` | Cross-runtime completed cleanup and deletion disposition, not one successor's remaining plan. |
| `unified-clj-cljs-cljc-test-feedback-2026-07-14` | Completed unit-0 three-boundary feedback implementation evidence. |

## Move to one successor PRD

Move reports without rewriting their historical observations. The successor's
current audit and roadmap remain the status authority.

### Priority 1 — database lifecycle recovery

Destination: `docs/prds/database-lifecycle-recovery/research/`.

| Report | Primary evidence owned there |
|---|---|
| `config-schema-runtime-restoration-2026-07-12` | Fresh/reopen/config/schema reconstruction transitions. |
| `database-runtime-responsiveness-audit-2026-07-13` | Writer convergence, attachment, replay/feed, and database boundedness. |
| `datahike-as-of-fork-and-restore-2026-07-12` | Native branch, coordinate, restore, and undo design. |
| `db-protocol-cut-implementation-audit-2026-07-13` | The one protocol, writer, replica, receipt, and registry cut. |
| `human-readable-word-ids-datahike-and-tokenization-2026-07-12` | Datahike identity allocation evidence consumed by provenance design. |
| `local-allocation-writer-config-audit-2026-07-12` | Atomic generated-id allocation in the maintained Datahike writer. |
| `malli-runtime-schema-authority-audit-2026-07-13` | Database-native schema candidate/reconstruction authority. |
| `provenance-users-processes-and-ids-2026-07-12` | Canonical transaction user/process/identity facts. |
| `time-travel-api-implementation-audit-2026-07-12` | Exact current branch/restore implementation and deletion map. |

These nine reports should move first because several links among them become
same-folder links, and the grounded lifecycle roadmap already supersedes their
status claims.

### Priority 2 — agent runtime correctness

Destination: `docs/prds/agent-runtime-correctness/research/`.

| Report | Primary evidence owned there |
|---|---|
| `agent-lifecycle-responsiveness-audit-2026-07-13` | Mint/resume/turn/provider responsiveness and cancellation gaps. |
| `boot-agent-lifecycle-audit-2026-07-12` | Cold-start versus warm-agent lifecycle ownership. |
| `eval-query-memory-safety-audit-2026-07-14` | Query/pull and retained-result resource bounds. |
| `eval-turn-atomic-id-cutover-2026-07-12` | Ordered eval/turn identity and durable-result transitions. |
| `incremental-instrumentation-2026-07-12` | Program-graph-derived instrumentation behavior. |
| `runtime-registry-instrumentation-audit-2026-07-12` | Immutable schema/instrumentation generation and publication. |

### Priority 3 — reactive render units

Destination: `docs/prds/reactive-render-units/research/`.

| Report | Primary evidence owned there |
|---|---|
| `live-feed-fix-review-2026-07-13` | The exact connection-local dependency and stale-feed failure. |
| `web-responsiveness-audit-2026-07-13` | Feed correctness, pay-for-use work, and boundedness baseline. |
| `web-ui-pay-for-use-unit-loading-2026-07-12` | Generic view-unit activation and lazy-detail design. |

The five reactive reports already moved to that folder are not part of this
51-report inventory and require no action.

### Priority 4 — Inspect/autocomplete evidence

Destination: `docs/prds/inspect-autocomplete-evidence/research/`.

| Report | Primary evidence owned there |
|---|---|
| `evaluation-harness-replacement-2026-07-12` | Inspect selection and retired-harness replacement. |
| `inspect-autocomplete-lane-integration-audit-2026-07-14` | Commit/evidence disposition for autocomplete, planning, and Inspect lanes. |
| `skill-source-unification-implementation-audit-2026-07-13` | Canonical skill corpus and generated agent tool-context boundary. |

### Priority 5 — distribution and final performance

| Report | Destination | Primary evidence owned there |
|---|---|---|
| `client-distribution-and-server-rendering-boundary-2026-07-13` | `docs/prds/independent-downstream-distribution/research/` | Producer/client/runtime distribution boundary and portable CLJS source. |
| `shadow-compiler-memory-profile-2026-07-13` | `docs/prds/local-performance-graduation/research/` | Measured Shadow JVM memory baseline and comparison protocol. |

## Keep here as link-only shared input

| Report | Successor consumers | Why it must not move to only one consumer |
|---|---|---|
| `active-cljs-pod-mutable-runtime-census-2026-07-12` | database lifecycle, reactive units, agent runtime, local performance | Its census mixes connections, compiler state, routes, feeds, result retention, and teardown. |
| `eval-render-fanout-design-2026-07-13` | agent runtime, reactive units | The semantic hold begins at ordered eval transactions but ends in render invalidation/coalescing. |
| `jvm-server-cljs-client-storage-sync-2026-07-13` | database lifecycle, downstream distribution | It combines writer/replica correctness with future remote/packaged client topology. |
| `legacy-acme-archive-readback-runbook-2026-07-14` | Inspect evidence, local performance cleanup | Historical dependency-safe read-back is both evidence preservation and final destructive cleanup input. |
| `root-view-presence-crash-batch-audit-2026-07-13` | root sessions, agent runtime, reactive units, local performance | One report combines root layout, browser sessions, crash recovery, batch eval, and telemetry. Its old root-as-ordinary-page target is superseded. |
| `runtime-reconstruction-and-replay-boundary-2026-07-12` | database lifecycle, agent runtime, Inspect evidence | It distinguishes program reconstruction, forensic reads, and transaction replay across three owners. |
| `runtime-state-atom-audit-2026-07-13` | database lifecycle, reactive units, agent runtime, local performance | Its mutable-state census intentionally spans every process-local owner. |
| `seon-cli-lifecycle-audit-2026-07-13` | downstream distribution, local performance, database lifecycle | The one operator/process graph governs packages, restart/recovery, and performance measurement. |
| `surface-vocabulary-and-dead-ui-path-audit-2026-07-13` | reactive units, root sessions, canvas | Vocabulary and deletion changes cut across all UI successors. |
| `worktree-evidence-preservation-manifest-2026-07-14` | Inspect evidence, local performance cleanup | It is the shared non-destructive evidence ledger and cleanup gate. |

## Backlink repair ledger

Moving by Git preserves history but does not preserve path-qualified
wikilinks. Execute each group as one explicit move-and-link patch, then search
the entire Markdown corpus for every moved basename.

High-priority external backlinks are:

- `runtime-reliability/AGENTS.md` and `roadmap.md` link the agent lifecycle,
  client distribution, CLJS test, database responsiveness, JVM boundary,
  storage sync, live feed, phase-1, root/crash/batch, CLI, surface vocabulary,
  and web responsiveness reports. Update only the links for reports that move;
  keep shared and graduation links local.
- `runtime-reliability/provenance-and-lifecycle-design.md` links
  `datahike-as-of-fork-and-restore-2026-07-12`.
- `docs/seon/issues/archive/eval-memory-safety.md` links
  `eval-query-memory-safety-audit-2026-07-14`.
- `docs/seon/issues/autocomplete-worktree-evidence-preservation.md` links the
  Inspect lane audit. Its archive/runbook/retirement links remain local.

Cross-report links requiring special care are:

- the moving Malli and instrumentation reports link the moving config/schema,
  incremental-instrumentation, and registry reports across two destinations;
- the moving evaluation-harness report is linked by two test reports that
  remain here;
- the moving Inspect lane audit is linked by the legacy-retirement and
  worktree-preservation reports that remain here;
- the moving Datahike report is linked from the moving config/schema report;
  and
- the moving human-readable-id report is linked from the moving provenance
  report.

Bare `[[basename]]` links are not sufficient proof: after the moves, use
destination-qualified links from roadmaps and explicit relative links for
cross-PRD references. A final no-stale-path check should include ordinary
Markdown links as well as wikilinks.

## Recommended move sequence and acceptance

1. Move the nine database reports and repair their internal and external
   links.
2. Move the six agent-runtime reports; repair the cross-link from its registry
   audit to the database config/schema report.
3. Move the three reactive reports and update the umbrella runbook/roadmap.
4. Move the three Inspect reports and repair the retained preservation reports
   and issue note.
5. Move the distribution and performance reports.
6. Add link-only shared inputs to successor roadmaps only where they constrain
   ordered work; do not duplicate all ten into every research index.
7. Search all Markdown for each of the 23 moved basenames, validate every
   affected document with `seon.dev.markdown`, run the issue-index check for
   the two issue backlinks, and require `git diff --check`.

The localization task is complete when all 23 reports exist only under their
one owner, every backlink resolves, the 18 graduation reports and 10 shared
inputs remain here, and successor roadmaps contain current state rather than
inheriting historical status prose from the moved reports.
