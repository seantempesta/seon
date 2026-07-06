---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# Handoff — remaining tooling-lane work (for Opus 4.8 orchestrator)

Written 2026-07-06 by the outgoing Fable orchestrator. Context: the
error-workflow arc is COMPLETE and drill-proven (read
`research/error-workflow-drill-2026-07-05.md` + the memory file
`project_error_workflow_arc_2026_07_05.md`); the debt wave is ~done.
Everything below is verify-and-finish plus two specced units. Rules that
bind you: CLAUDE.md (esp. "Errors are data — the fault workflow"),
explicit pathspecs on every commit, one agent per file tree, live-proof
per unit, registry NEXT-ID claim protocol (currently **C55**), never
touch acme (7980/7981) or eval-lane dirty files.

## 1. Verify the dead agent's finish (FIRST, ~30 min)

The pod-envelope agent committed everything (`b7057383`, `345645db`,
`a497d4d3`, `ce63baa1`, `a0e6afa0`, `904da823`) but died before its
final suite run + live proofs. Do:

- `bin/seon status` → default pod healthy (restart if not).
- Full `bin/test-cljs` on the settled tree — expect ~1005/4645 **0F/0E**,
  gate green (the two envelope-test failures earlier runs saw were the
  in-flight C45 diff, now committed). If red: the failures are in
  `error.cljs`/`eval.cljs`/`db/internal.cljs` territory from those
  commits — fix root cause, don't revert.
- Spot live-proofs (pod 7890, dial `:crash` — use
  `seon.error/expecting-core-fault!` for any `:core` provocation):
  (a) a def in `cljs.user` evals but mints NO `:seon.fn` row; a def in a
  `my.*` ns still tees (C14); (b) `(:seon.error/kind envelope)` at TOP
  level on a provoked error (C45); (c) `seon.agent.inspect/ctx-preview`
  returns an error VALUE on bad input (no throw).
- Push.

## 2. Batch-file small smells as registry rows (one docs commit)

From the three debt-wave unit reports, claim ids from NEXT-ID (C55…),
one row each, all OPEN:

- Non-section bare `[:cat :map]` inputs: `seon.agent.fs/configure!`
  (fs.cljs:213), `seon.log` merge-config!/warn!/info!/debug!,
  `seon.error.instrument/render-malli-error`, handlers/test.cljs
  entity helpers — each wants its own named request schema (C54 pattern).
- `seon.ui.components/log-line` takes a bare-keyword map
  (`:timestamp :type :details`) — namespaced-keys violation.
- `wire.clj` internal `state` atom uses bare keys (`:conn`,
  `:req-server`, …) — JVM wire-server internals.
- Inline `[:seon.agent/id {:optional true} :string]` slots can now
  reference the registered `:seon.agent/id` (its registration moved to
  seon.render in C54): render.cljs:1183, render_fns.cljs:117, ctx.cljs
  read API, system.cljs:433, agent.cljs set-purpose!. (Mechanical —
  could be one small unit instead of a row; your call.)
- `.claude/worktrees/gym-metric-validation/` carries the old shared
  port path — another agent's worktree; note-only row or skip.

## 3. UNIT: M9 + M10 — the SCI env / alias work (the substantial one)

Registry rows M9 (require-cycle injection atoms `!mint-agent-fn` /
`!create-agent-fn`, serve.cljs:110 + render/sci.cljs:493) and M10
(home-ns aliases like `db/` don't resolve in agent-authored `my.*` nses
— #73; agents must fully qualify, docs carry the workaround). One
seon-agent unit, files: eval.cljs, render/sci.cljs, serve.cljs (check
none are dirty first).

M10 design freedom (give the agent this framing): aliases are now
STORED as data — `:seon.ns/require-edges` rows carry target/alias/refers
(M4/C36). Two candidate shapes; the agent reads the source and picks,
reporting why: (a) seed every new agent ns with the standard home-ns
require-edges at `setup-agent-ns!` time, so aliases work AND are
truthfully stored as that ns's own edges (self-describing, survives
resume via the existing edge-driven reconstitution); (b) resolve-time
fallback to the home ns's alias map in the SCI/self-host env. Prefer
(a) — derived-from-stored-data, no second resolution mechanism. Must
live-prove: a fresh agent ns uses `db/`-style aliases in a real drive
eval, resumes across `bin/seon restart pod`, and `reconstitute-ns-source`
round-trips. Update the "fully qualify" workaround docs (agent-facing
guidance + #73) when it lands — the "align context with runtime" rule.

M9: read the two atoms' remaining consumers (the cluster build shrank
them); if the require cycle they bridge is now fixable directly (the
usual answer: move the fn to the ns that owns the data), fix the cycle
and DELETE the atoms; if not, report why with the cycle named.

## 4. Then: normal duty cycle

- `bin/seon watch-faults` as a background task at session start
  (re-arm after each firing). Triage chain is in CLAUDE.md.
- The eval lane owes a cluster-build review + has open coordination
  items in `coordination.md` — read the tail, respond, don't block them.
- Owner-held/tabled (do NOT start): C49 (skills dedup — tabled),
  C40 wrapper (net-only ruling stands), C30 (long-horizon), C22
  (paused JVM track).
- After each unit: close registry rows with shas, update roadmap.md,
  commit with pathspecs, push.

## Open questions for the owner (only if they ask)

None blocking. Everything above is ruled or mechanical.
