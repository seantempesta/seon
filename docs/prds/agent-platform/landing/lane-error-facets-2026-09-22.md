---
type: reference
status: in-progress
created: 2026-09-22
tags: [agent-platform, cold-gate, error-facets]
---

# C1 error facets

Assignment: repair the four subset output unions and the result-position facet
check, preserving declared data branches. Initial HEAD `462c6fe68`.

## Ownership and decision

Owned production paths: `src/seon/instrument.clj`, `src/seon/error.clj`,
`src/seon/sci/admit.clj`, `src/seon/sci/kernel.clj`,
`resources/seon/schemas/seon.error.edn`, `src/seon/repl.clj`, plus their tests.
No `turn.clj` changes, worktrees, default operations, or foreign file edits.
MCP `runtime_status` and `eval_clj` are discoverable; the explicit prohibition on
operating default overrides the general runtime-start protocol for this lane.
Runtime proof uses the authorized isolated `bin/test-fast --paths` launcher.

The requested registry union needs a scope decision: the immutable registry
compiler is `src/seon/schema.clj:455-504`; resource derivation/admission is in
`src/seon/schema/edn.clj:62-99,333-339,580-604`. Neither is assigned. The current
loader derives config composites only. Asked the owner before touching either:

1. Extend ownership to these schema owners (recommended): derive one genuine
   registry union from `error/facet-keys`, including changed-projection dependency
   invalidation. Costs a coordinated schema change; retains every consumer's view.
2. Derive only in the assigned instrumentation owner: narrower implementation,
   but the registry outside instrumentation would not contain the actual union.
3. Keep strict ownership and hand the registry prerequisite to its owner: retains
   one authority, but completion waits for that prerequisite.

No dependent registry implementation is made while this decision is pending.

## Turn hand-off to lane `turn-shapes`

`seon.turn/phase` at the triage's `src/seon/turn.clj:3472-3490` declares a
polymorphic output. The wrapper fix must honor a matching non-error data branch.
The separate contract issue remains with `turn-shapes`: an unrestricted callback
result does not promise the distinguishing `:seon.turn.loop/phase-failed` member.
See cold-gate triage C1b and kind-cut diff review finding 5. This lane does not
edit, message, resume, or operate that lane's files or session.

## Grounding and dependency

Read the C1 triage and subset-union review, B3 §0/§2a/§5, assigned source owners,
and the existing `semantic-admission-explicitly-declares-every-error-facet` class
regression. Historical log: `tmp/cold-gate-2026-09-22e.log:12177` proves the subset
unions; `:11192` names stored fault pulls refused as returned errors.

Malli is pinned through `deps.edn:15` to `reference-code/malli`, git revision
`606083c5c5b388e84d169c7080af33ed3ec242ae`. Its `core.cljc:996-1022` compiles
short-circuit union validators; `:2190-2230` retains each arity's input/output
validators; `:2818-2832` dereferences schema references. First-party caller:
`instrument/compiled-wrapper` supplies the acquired projection registry, bound
contract and original callable. Non-error alternatives are derived at wrapper
compilation, proportional to result-position schema structure; map payloads are
not traversed. The resulting compiled data validator is retained by that wrapper.

## Evidence

- Initial baseline: `bin/test-fast --paths test/seon/instrument_test.clj -- seon.instrument-test`.
  Log `tmp/lane-error-facets-before.log`, run `cbbfcbfe1d0f`, 46 executed,
  0 reused, 335 assertions, 6 failures, 1 error, shell exit 1.
  The four subset assertions fail. The host recorder fails with
  `seon.db/pull returned a base error without a complete declared facet`.
  Other failures: policy acquisition and cold-arming identity; not yet diagnosed.
- The initial new pull regression selected the stored root, which does not carry
  the occurrence's `:seon.error/at`, so it passed before the fix and is not a valid
  before proof. Corrected to select the occurrence by its real fault signature,
  positively assert its identity, and pull that stored component through armed
  `seon.db/pull`; assert the returned base shape and absence of facet refusal.
- Corrected before run: `tmp/lane-error-facets-before-pull.log` (in progress).

## Changed files (current)

- `src/seon/instrument.clj`
- `test/seon/instrument_test.clj`
- `docs/prds/agent-platform/landing/lane-error-facets-2026-09-22.md`

No publication/adoption or browser proof is claimed. No gate, HEAD load, or final
five-namespace green is yet claimed. No commits yet.

## Wrapper implementation and intermediate evidence

`declared-result` now accepts either its existing authored output form or the
compiled output node and retains a data validator alongside its facet permissions.
It follows result alternatives only; map/collection payload schemas are not
classified. Malli's compiled nodes preserve predicates and local registry scope.
`:and` retains its constraints; `:maybe` retains nil; `:orn` retains its labels;
`:multi` keeps excluded error dispatch keys with an unsatisfiable branch so that
removing them cannot expose the default data arm. On invocation the wrapper checks
that retained validator before considering a facet refusal. Successful data does
not run the population-wide `error/facets` scan.

The user's C1b instruction supersedes the older B3 sentence that a broad `:map`
success output must reject every error-shaped value. Existing host/SCI refusal
regressions now name a concrete success map, so their undeclared-facet checks
remain meaningful. Added stored occurrence pull, parsed shown value, polymorphic
output, composition, and dispatch regressions. No predicate, kind, or class added.

The first implementation used a new private helper Var. Five-namespace execution
showed the published fixture reports that new loaded Var as stale. Folded it into
the existing `declared-result` owner instead; no new production Var remains.
The cold-arming test's stale assertion that a projection has no predicate-functions
was replaced by a positive check that its cold arm has no ambient projection.
`schema/declaration-projection:1271` explicitly carries predicates; its arming
behavior and the declared contract enforcement assertions remain intact.

- Corrected before: `tmp/lane-error-facets-before-pull.log`, admitted run
  `8f77c68aea33`, 47 executed, 342 assertions, 8 failures, 6 errors.
  At log lines 335–338 the new regression fails specifically with
  `seon.db/pull returned undeclared error facets #{:seon.agent/error}`.
  Result recording failed (`Malformed PREPL result`); this is execution evidence,
  not a recorded green or red tally. Shell exit 1, process exited.
- Wrapper after: `tmp/lane-error-facets-wrapper.log`, admitted run
  `49e403847509`, 47 executed, 356 assertions, 8 failures, 0 errors.
  Stored occurrence regression passes in 1,162.546 ms; parsed/data results pass.
  Four failures are the untouched subset unions; one is the now-corrected
  cold-arming assertion; three are duration overruns (5,478.378, 5,851.283,
  9,544.399 ms against 5,000 ms). These overruns are failures, not excused as load.
  Recording failed with `Malformed PREPL result`; shell exit 1, process exited.
- Intermediate five-namespace request: `tmp/lane-error-facets-wrapper-five.log`,
  admitted run `bd2a3b967293`, 141 executed, 1,422 assertions, 81 failures,
  2 errors. The new helper Var caused stale-loaded-image findings in
  `seon.problems-test` (including 56 repeated absence assertions); the helper is
  now folded into the owner. Recording failed; shell exit 1, process exited.
- Prescribed namespace require exited 0, `tmp/lane-error-facets-load.log`.
  This was the working source before the final in-place fold, not a HEAD proof.
- `clj-kondo` on both changed Clojure files: 0 errors, 9 existing warnings.

Foreign proof boundaries observed in the five-namespace execution:
`seon.flow-test/forced-child-jvm-death-preserves-committed-facts` reports its
child exited before the durable fact; turn completion reports submission did
not settle within 1,000 ms; `seon.problems-test/every-committed-error-fact-shape-is-projectable`
passes an error fact missing required `:seon.error/layer`; other problems and
message assertions also remain red. No cause beyond those observations is
claimed and no foreign file is edited. The result recording boundary is
`seon.test.runner/record-snapshot!` / `record-tx`, returning `Malformed PREPL result`.

No publication clock is claimed: the committed measurement script creates a
worktree and boots/adopts clusters, outside this lane's explicit operation scope.
