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
