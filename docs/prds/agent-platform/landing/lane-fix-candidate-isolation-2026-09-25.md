---
type: landing
status: committed; awaiting root test runs
lane: fix-candidate-isolation-opus
created: 2026-09-25
issue: docs/seon/issues/candidate-context-shares-parent-program-metadata.md
---

# Candidate isolation: the parent was stale, not leaked into

A candidate is `fork-for-turn` over its parent: `sci/fork` (fresh `:env` atom, new generation,
`reference-code/sci/src/sci/core.cljc:345`) plus fresh `::kernel/program-snapshot`,
`::kernel/installed-functions` and environment-state atoms (`src/seon/sci/eval.clj:2967-2978`).
SCI's copy-on-write (`sci/impl/utils.cljc:364` `owned-var`, `:395` `bind-root!`, and the def path
`sci/impl/evaluator.cljc:36-49`, which re-creates a Var of another generation) keeps every install on
the fork. The REPL confirms it. **No product change is needed.** The fix is the test fixture.

## Broken or unproven, first

- **No root run of the changed tests yet.** I ran both deftests once in default's JVM (below). The
  parent assertions pass. The candidate half fails there for a foreign reason: every evaluation on a
  fresh branch refuses `"Cannot interpret seon.test/resolve-test: Host-bound declaration
  seon.test/resolve-test must change through the loaded source files."` Default's loaded `seon.test`
  lags the branch row from `94df186e9` (committed 23:11Z; default started 22:11Z). Root adopts, then
  runs the commands below. **RESET NEEDED: no**, adoption of HEAD is enough.
- The issue file still claims the leak mechanism. It is outside my row. Root should close it or
  rewrite it from this note (the metadata atoms it cites have been fresh per fork since
  `fork-for-turn` gained them; the live reproduction is stale-parent acquisition).
- Design question for the owner, not a defect found: when a fork's parent is stale against the
  request's database, the fork's first `evaluate` acquires the PARENT (`eval.clj:4184`, `base (or
  (::base-ctx evaluation-ctx) evaluation-ctx)`, then `acquire!` resets its `:env` at `eval.clj:3334`).
  That is the designed branch-head reacquisition: the parent advances to its own committed rows, and
  its own definitions survive unless their row changed (`acquire!` `replaced`). But the "PROGRAM
  CHANGE replaced your definition" notice then goes to the fork's evaluation, not to the parent's
  agent. In production the candidate's parent is the agent's retained context, already acquired at
  the turn's database (`turn.clj:4776` → `evaluate-for-install`), so this does not fire there.

## Evidence (default pid 39484, JVM mode, disposable branch `lane-fci`, retired)

Fixture built with the canonical writers under `seon.test/*member*` bound to a handle acquired on
`lane-fci` (the MCP branch tool's own `acquire-context!` call), so `with-database` opened and
unlinked its own isolated branch. A watch on the parent's `:env` atom recorded the stack of every
write to `fixture.candidate/target`.

1. Test as written (parent not acquired at `@connection`): after `fork-candidate-ctx`, parent Var
   identical, `acquired-database?` of the fork = false, `::base-ctx` present (parent carries
   `::custody`). The only shared atom is `:seon.call-preparation/state`. After `evaluate` on the
   fork: the parent Var is replaced. One write, from `seon.sci.eval/acquire!` (eval.clj:3334), called
   from `evaluate` (eval.clj:4185) with `base` = the parent. Parent still answered 1: the row is
   `target [x] x`. This is exactly the root-run red at :220/:222.
2. Parent acquired first (`acquire!` 1478 ms, cold on a new branch): fork + evaluate write the parent
   `:env` zero times.
3. Two forks `(inc x)` and `(dec x)`, each evaluated and passed through `install-candidate-function!`:
   parent Var `identical?`, root bytes equal, namespace map, program snapshot, installed set and
   environment all `identical?`; forks hold distinct Vars; answers `[1 2 0]`.
4. Fork cost: `fork-candidate-ctx` 7.7, 4.4, 5.7, 5.6, 4.2 ms (five in a row). No product change,
   so before = after.
5. After the edit, both deftests run in default (4.7 s, including two branch acquisitions): every
   parent-isolation assertion passes. 5 fails, all the foreign resolve-test refusal (the candidate
   never evaluates, so results are `[]` and forks answer 1).

## Change

`test/seon/test/accretion_test.clj` (+57, −0):
- `candidate-tests-run-on-a-copy-on-write-turn-fork` acquires the parent at `@connection` through
  `sci.eval/acquire!`, the pattern of `call_preparation_test.clj:875`, before capturing its state.
- New class regression `two-candidate-forks-of-one-base-keep-their-own-roots`: two forks of one
  base install different roots through `evaluate-candidate`. It checks `identical?` on the base
  Var, its root bytes, snapshot, installed set and environment, and `[1 2 0]` answers.

Net: production 0, tests +57. No deletions: the premise the brief asked me to fix did not exist.
Nothing in `src/seon/sci/eval.clj` or `reference-code/sci` changed.

## Root runs (serially, after default adopts HEAD)

```
bin/test-check default --test seon.test.accretion-test/candidate-tests-run-on-a-copy-on-write-turn-fork
bin/test-check default --test seon.test.accretion-test/two-candidate-forks-of-one-base-keep-their-own-roots
```
