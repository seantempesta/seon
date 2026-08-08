---
type: prd
status: active
tags: [prd, runtime, testing]
---

# Overnight report — 2026-08-08 (accumulating; final version at morning)

Owner directives in force: run hot until morning; uncapped parallelism
until measured degradation (load is a stress test); multivariate success
(fewer bugs, faster, fewer lines, better agent context); adversarial
socratic checks on every claim; rotate ideas 90° before implementing;
token explosions = high-priority bugs; complete bare runs are
truth-checks, not gates.

## Decisions needing you (ranked)

1. **Background work bounds** — background submissions no longer inherit
   the turn's deadline (semantic inversion fixed), so they currently run
   unarmed. What bounds should background work have — its own config-fact
   caps? ([issue addendum](../../../seon/issues/the-effect-door-runs-capability-handlers-unarmed.md))
2. (accumulating)

## Landed overnight (chronological, with commits)

- Adversarial audit (`ae98ef06a`) — refuted "declaration-population done"
  (admission seam dominant: 54,884 fallbacks/hour); third DECLARED
  identity-collision family; effect door unarmed + background deadline
  inversion; calibration: boot-as-only-constructor, schema-derived
  members, sci refusal, arm structure all held.
- Warning-yield sweep (`8019a79d4`, `7dd61703f`) — warning wall fixed at
  owner (was 45% of boot-test transcript); seon.ai was resolving 66
  populations PER MODEL REQUEST — now one; first-party frame derivation
  computed from the CLI basis (rotation applied after audit objection);
  issues index check-clean including fixing the checker's own regex bug.

- Render-proc stop completion (`8872311d1`..`897aeb51d`) — cause was
  NOT the stop path: a declared producer delegating its own value
  RE-ENTERED selection (infinite render recursion at depth 13 on one
  virtual thread; found by in-window virtual-thread dump). Class made
  unconstructable via the rendering-chain set, not depth-capped.
  `seon.render.web-test`: first zero-error run ever (38/274, twice).
  The recent SSE fix was checked first and is innocent. Follow-up owed:
  `resources/seon/schemas/seon.render.edn` is currently UNEDITABLE (five
  pre-existing `:any` declarations block every edit at admission) — the
  new `:seon.render/rendering` key works via open maps but cannot be
  declared until that file is repaired (queued).

- Cleanup lane (7 commits, `7706a4279`..`b0900b28a`) — all 10
  plain-accumulator census items converted to immutable returns; opaque
  generators construct fresh samples; two dummy atoms deleted via an
  honest untracked arity; `var-process` sheds an `:any`. HONEST DELTA:
  **+148 lines net** (threading costs characters; the regression + shape
  comments are most of it) — what shrank is 12 mutable constructors and
  the shared-sample class, not lines. Deferred rows recorded (files
  owned by running lanes). Dead-code sweep found NOTHING deletable —
  the 5 kondo unused-private warnings in fs/jvm.clj are false (reached
  via capability symbols; kondo cannot see that edge).
- **Suite tiering SHIPPED — the overnight headline number**
  (`b6886bf36`..`0cc63c829`): bare `bin/test` went **965.9 s → 43.6 s**
  no-change (platform tier only), **52.5 s green end-to-end** on a
  one-file change, 83% bulk skipped under four-lane churn, fail-fast
  verdicts in ~37 s. One runner evolved in place; the old
  namespace-closure selector DELETED; changed = program-graph
  reachability against a content-digest green basis; widening loud and
  named; selector class regression non-vacuous both directions. Honest
  gaps recorded in the spec addendum: the platform tier lacks
  flow/settle/sci-fork coverage until the spec's seven consolidated
  regressions land (a flow test hangs without its siblings — issue
  filed); macroexpand-only test edges are a missing index fact.
- New loose ends queued: `settle!` has no `:malli/schema` (contract
  census catch, W1 wave); `seon.render.transcript-test` 28 failures
  (needs a lane once the tree is coherent); `seon.fn-test`/`program-test`
  errors to re-verify after the selection.clj fix settles.

- **Tool exercise (`a4f6f31d1`) — six blockers on never-driven paths.**
  The big one: EVERY background capability request loses its connection
  on the `:io` hop (8/8 failed; foreground identical command staged
  40 MB correctly) — Defect I failing outright, relayed to the
  carriage-finish lane as acceptance evidence. Also: `my.fs` windowed
  reads refuse on whole-file size; `my.web` is unreachable from agent
  code (the only two unresolvable `my.*` fns); an interrupted
  `my.shell/run` orphans its child AND its receipt (recorder throws
  too); `next-agent-work` requires a `now` it never reads (all curation
  proofs fail); a schema-resource edit bricks value admission in every
  RUNNING cluster (live urgency evidence for the sentinel deletion).
  Sound under exercise: effect identity/provenance across 22 receipts
  incl. 8 concurrent, transport law at 40 MB (blob tier, 211 ms), fs
  symlink discipline. Velocity finding: ~2.4 s per-form turn overhead.
  Token findings: ~290 tokens per poll result; a six-word error
  rendering as 2,154 chars. Tool-repairs lane dispatched on the four
  agent-facing defects.

- **Admission blocker CLOSED** (`9d5c986eb`/`45a87196a`/`114dc6aa7`):
  one declarations delay in the walk state — an identity question went
  15.263 ms → 0.012 ms; a realistic 121-node admission ~120 populations
  → 1 (374 ms → 24 ms); the test-side hoist alone took admit-test
  259 s → 13 s. Class regression counts one resolution per admission,
  zero for scalars, zero when supplied. Remainders recorded in the
  issue for their owners (per-EVAL projection rebuild in eval.clj is
  the notable one). The fallback warning's per-caller counter did the
  finding work — the diagnostics flywheel, again.
- **Coherence incident #4, escalated:** P17-S1's uncommitted state
  still blocks every cluster boot tree-wide (config rows referencing
  unpublished supplied-* fns). Second, urgent notice sent with exact
  restore steps and a stop-and-hand-over option.

## In flight at last update

stop-completion (render proc liveness) · suite-speed (tiers +
changed-only default) · P17-S1 (provider rows) · cleanup (census
deletions) · tool-exercise (IO/background hammering) · admission blocker
(per-node resolution) · carriage-finish (door arm, background,
bound-fn* deletion) · schema-environment (Defect I root owner).

## Defect ledger (found overnight)

- Per-node admission resolution (blocker; lane on it).
- Third identity-collision family, declared shape (appended to issue).
- Effect door unarmed / background deadline inversion (lane on it).
- selection.clj `(partial instance? File)` contract broke 33 tests
  (flagged to owning lane mid-flight).
- MCP envelope echoes the submitted form in every event (5× duplication;
  queued).

## Pending milestones

- stop-completion lands → reset → 08-06 drive-arc rerun with observer →
  extend to planning+memory → messaging wave + dogfood lanes.
- Rename waves at natural drain points (owner-approved list in the PRD).
- **Degradation point FOUND at 8 concurrent lanes — and it is not the
  machine.** CPU/memory hold; measurement noise ~40% under load is the
  only compute cost. The binding constraint is SHARED-TREE COHERENCE:
  three fixture outages in one stretch, all one shape — a lane's
  mid-edit state (P17-S1's config rows referencing unpublished
  suppliers; a parse error in schema_test.clj; an unclaimed diagnostic
  hunk) turning every sibling's proofs red until it lands. The gates
  refuse loudly and attribution takes minutes (twice the flagged lane
  refuted correctly and the real owner was found by diff), but each
  incident stalls the fleet. Mitigation applied: aggressive
  commit-small-slices enforcement via direct pings; NOT adopting
  worktrees (standing ruling: shared checkout is the model). The honest
  ceiling at current discipline: ~6-8 heavy lanes when several own hot
  files; more is fine when file ownership is disjoint and cold.
