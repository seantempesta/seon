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
- Machine degradation point: not yet reached at 8 concurrent lanes
  (observations to date: measurement noise ~40% under load; no failures
  attributable to load itself).
