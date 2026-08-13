---
type: reference
status: active
tags: [reference, issue, index, wave/agent-context, wave/ai-provider-integrity, wave/ai-provider-protocol, wave/ai-retry-evidence, wave/artifact-startup, wave/background-settlement, wave/blob-staging, wave/blob-storage, wave/boot-velocity, wave/capability-surface, wave/causal-episode, wave/changed-test-selector, wave/class-kill-queue, wave/cluster-search-wiring, wave/config-application-contract, wave/config-cluster-identity, wave/config-derivation, wave/context-derivation, wave/context-fixes, wave/contract-gate, wave/contract-generator, wave/core, wave/database-codec, wave/datahike-fork-logging-seam, wave/dev-mcp, wave/dev-tooling-face-hygiene, wave/directory-claims, wave/docs-honesty, wave/effect-ordering-follow-up, wave/error-class-contract, wave/error-face-budget, wave/eval-driver-lifecycle, wave/eval-scale-economics, wave/evolving-session-phases, wave/evolving-session-prd, wave/exclusive-sweep, wave/explicit-environment-proof, wave/flow-join, wave/flow-protocol, wave/fresh-portability, wave/future-model-continuation, wave/future-program-graph-binding, wave/future-runtime-lint, wave/general, wave/generate-call-transition, wave/generated-receipts, wave/instrumentation-error-data, wave/live-drive-context, wave/live-drive-render, wave/load-time, wave/mcp-process-lifetime, wave/message-delivery, wave/message-transaction-data, wave/my-branch, wave/namespace-page-performance, wave/no-crash, wave/open-maps-accretion, wave/operator-artifact-follow-up, wave/operator-child-lifecycle, wave/operator-launch-concurrency, wave/operator-lock-contention, wave/operator-lock-scope-follow-up, wave/operator-process-identity, wave/operator-status-face, wave/oversight-test, wave/parallel-stress-triage, wave/per-cluster-live-graph, wave/per-run-fork-context, wave/post-gate-rename, wave/prefix-drift-bootstrap, wave/print-path, wave/program-graph-indexing, wave/program-index-proof, wave/provider-context, wave/publication-provenance, wave/publication-velocity, wave/rebirth-gap, wave/reconcile-evidence, wave/render-acquisition-performance, wave/render-arm, wave/render-connection-model, wave/render-context-cache, wave/render-oversight-event, wave/render-package-economics, wave/render-producers, wave/render-property-premise, wave/render-receipt-producer, wave/render-test, wave/reply-durability, wave/run-loop-velocity, wave/runtime-boundary-refactor, wave/schedule-fixture, wave/schema-admission, wave/schema-codec-deletion, wave/schema-form-extraction, wave/schema-key-ruling, wave/schema-lifecycle, wave/schema-population-deletion, wave/schema-projection-performance, wave/sci-base-context-derivation, wave/sci-eval-context-owner, wave/sci-eval-readiness, wave/sci-failure-face, wave/sci-reader-limit, wave/sci-static-admission, wave/seon-env-p3, wave/settlement, wave/shared-surface-scheduling, wave/store-perf, wave/strict-repl-display, wave/test-fixture, wave/transcript-deletion, wave/transcript-ordering-follow-up, wave/ui-watchability, wave/unreadable-reply, wave/upstream-delta, wave/verification-audit, wave/visual-qa, wave/whole-system-arc, wave/why-awake, wave/work-ordering-follow-up]
---

# Issues — Lightweight Tracking

One note per problem. Tracking is deliberately lightweight: a single lifecycle,
a single severity vocab, an `archive/` for closed notes, and one ranked owner
schedule.

## Lifecycle (`status`)

```text
open  →  resolved    (it was fixed)
      →  superseded  (it no longer applies — design changed, dead code removed)
```

There are exactly three values. `resolved` and `superseded` are both "closed"
and their notes live in `archive/`. Nothing else (`active`, `completed`,
`verified`, `closed`, `archived`) — those were drifted spellings, now normalized.

## Severity

Exactly three values, required on every issue:

- `blocker` — blocks shipping / other work; fix first.
- `friction` — slows agents or humans down; real but not blocking.
- `cleanup` — tidiness, dead code, duplication, naming, convention drift.

Architectural issues carry an `architecture` tag (the lens is in the tags, not a
separate severity).

## Layout

```text
issues/
  README.md     ← this file (the convention)
  index.md      ← ranked schedule for every open note
  *.md          ← OPEN issues only
  archive/*.md  ← resolved + superseded issues
```

After triage, top-level `*.md` is ONLY open issues. When an issue is fixed or
no longer applies, set its `status` and `git mv` it into `archive/`.

## The index is the schedule

`index.md` is the owner's ranked execution schedule. Every top-level open issue
appears exactly once with one disposition:

- a named running lane;
- a named future wave; or
- after verification makes it moot, a resolved/superseded archive entry with
  the dissolving commit or ruling.

Severity still ranks the work inside those destinations; it is not itself a
destination. Update the schedule whenever an issue opens, closes, or changes
owner. `bin/issues-index [--check]` only VALIDATES: it reads the notes plus
`index.md` and fails on a missing, duplicated, or severity-mismatched row, a
row naming a note that is no longer open, or a blank destination. It never
generates or overwrites the schedule.

## Query tags

Open issue tags are a query contract. `issue` is always first, followed by one
to three area tags from this controlled vocabulary:

`render`, `runtime`, `schema`, `database`, `sci`, `flow`, `operator`, `test`,
`web`, `agent`, `ai`, `config`, `message`, `effect`, `blob`, `mcp`,
`performance`, and `docs`.

Named-class members then carry one of `class/p1` through `class/p5` or
`class/n1` through `class/n14`. A class issue carries its own `class/<id>` plus
`class-kill`. Deliberate singletons and uncertain post-mining findings carry no
class tag.

Every open issue ends with one `wave/<slug>` tag mirroring its destination in
`index.md`; the index remains the schedule. For an unlisted destination, remove
Markdown links and parenthetical scheduling annotations, kebab-case the text,
and drop terminal scheduling words such as `wave`, `lane`, `fix`, `repair`,
`incident`, `investigation`, `diagnosis`, `implementation`, `design`, `gate`,
`cleanup`, `sweep`, and `work`. These are the maintained short aliases and
intentional merges:

This README's frontmatter adopts every active wave tag so the Markdown
checker's corpus-derived vocabulary also recognizes a one-issue destination.

```text
class-kill queue                                      -> class-kill-queue
evolving-session implementation phases               -> evolving-session-phases
rebirth-gap fix wave                                  -> rebirth-gap
seon.env Phase 3 production sweep                     -> seon-env-p3
projection causal episode query/proof                 -> causal-episode
operator launch-concurrency variants                  -> operator-launch-concurrency
operator status-face hygiene variants                 -> operator-status-face
upstream-delta sweep variants                         -> upstream-delta
prefix-drift bootstrap diagnosis/design               -> prefix-drift-bootstrap
RULED keep-serial contention destination              -> operator-lock-contention
my.branch verb wave                                   -> my-branch
W2 render property premise repair                     -> render-property-premise
render/arm nesting fix                                -> render-arm
schema-key owner ruling                               -> schema-key-ruling
adversarial-audit fix wave                            -> verification-audit
agent context audit repair                            -> agent-context
development MCP envelope repair                       -> dev-mcp
development-velocity run-loop incident                -> run-loop-velocity
exclusive sweep implementation                        -> exclusive-sweep
generated-episode receipt integration                 -> generated-receipts
no-crash architecture gate                            -> no-crash
operator directory-claim governor                     -> directory-claims
program-index production-subject wave                 -> program-index-proof
publication registration-provenance                   -> publication-provenance
production documentation honesty                      -> docs-honesty
render important-schema producers                     -> render-producers
strict dogfood provider-context repair                -> provider-context
strict dogfood transcript deletion                    -> transcript-deletion
test runner explicit-environment proof                -> explicit-environment-proof
unreadable-reply coordination                         -> unreadable-reply
why-awake situation work                              -> why-awake
context wave fix lane                                 -> context-fixes
contract-gate repair                                  -> contract-gate
flow join-wedge diagnosis                             -> flow-join
```

## Frontmatter template

```yaml
---
type: issue
status: open          # open | resolved | superseded
severity: cleanup
tags: [issue, agent, wave/example-destination]
---

# Short imperative title

## Problem

One observed mismatch.

## Evidence

Current file/symbol plus a failing test, live observation, or exact source
existence result.

## Owner

The one namespace or mechanism that should be strengthened.

## Acceptance

Behavioral falsification, not exact prose.
```
