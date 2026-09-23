---
type: landing
status: landed in part; host-code gating needs the decision below
lane: save-gate (README §4 1.4c; lane-realities-one-lifecycle.md §1 "save-time gate", §2 rows 10, 13)
---

# Save-time gate — design (written before code)

## The composition

A filesystem edit with changed paths P, one request (`seon.cluster/refresh-source!`
with the request member `{:seon.source/gate? true}`):

| step | installed owner (file:line at HEAD 7e5cdea9e) | proportional to |
|---|---|---|
| 1a index P onto `current-src` (the files' index, unchanged) | `full-source-refresh!` `src/seon/cluster.clj:1978` → `source/publish!` `src/seon/cluster/source.clj:543` | P and their declarations (digest compare per path, analysis of changed files only) |
| 1b bring the ONE persistent candidate `:cluster-<name>-candidate` to default's head | `registry/branch!` `src/seon/cluster/registry.clj:193` (from default's commit id), `registry/retire-branch!` `:330` when its head is not default's commit | O(1): two roster writes, no data copied |
| 1c adopt the changed rows onto the candidate | the DB half of `development-source-refresh!` (`src/seon/cluster.clj:2470`) split out as `adopt-rows!`: declaration changes, `seon.fn/index!` with `:seon.reconcile/adopt-identities`, `seon.issue/adopt!` — on the connection it is handed | the changed identities (`source/changed-identities` `source.clj:301` answers from the publication report) |
| 2 reaching tests on the candidate | `seon.cluster.agent/acquire-context!` `src/seon/cluster/agent.clj:783` with `:seon.agent/branch` (borrow, no isolation) → `seon.test/run` `src/seon/test.clj:1584` policy `:named` with `:seon.test/changed` = changed `:seon.fn/sym`s and `:seon.test/identities` = changed `:seon.test/sym`s; recording on the candidate connection; `release-context!` `agent.clj:750` | the reaching tests (members branch off the candidate commit; changed rows interpreted from the branch by `sci.eval/acquire!`, B2 §2a, 39a337013) |
| 3 green → advance default | the existing `development-source-refresh!` unchanged (index onto default, `require :reload` per declaration, re-arm, adoption record) | the changed identities + the per-declaration reload set |
| 3 red → default unchanged | nothing runs; the candidate keeps the red rows for inspection (`eval_clj` with `branch`) until the next save resets it | — |
| 4 one terminal result | refresh-source!'s existing result + `:seon.source/gate` (tally, run result, phase ms) + the existing `:seon.profile/explanation` from `seon.profile/explain-slow` over the whole request | — |

The publisher is not duplicated: publication still writes only `current-src` (the files'
index, which default's adoption already reads), and the target branch of the row write
is the connection member `adopt-rows!` is handed — the candidate's, then default's.
`publish!` itself needs no target-branch member: the rows reach the candidate through
the same `seon.fn/index!` adoption that reaches default.

Unchanged path (c): default's adopted commit already equals the published commit, so
no identities exist and no test is selected or executed.

## Simplest alternatives considered

1. **Per-save isolated branch** (`acquire-context! {:seon.agent/isolate? true}`, unlinked
   after). Equally cheap, but a red save leaves nothing to inspect; the realities spec
   §1 row "save-time gate" rules the candidate persists as default's staging. Rejected.
2. **Run tests on `current-src` directly.** `current-src` holds no cluster rows or config
   (`seon.test/run` reads `config/effective` of the execution cluster); rejected.
3. **`force-branch!` the candidate to default's head** (`versioning.cljc:323`). Writes a
   stored db and requires every candidate connection released anyway; retire + branch!
   is two roster writes. Rejected.

## Measured floor before the change (default, pid 90963, 2026-09-23)

| operation | wall ms | note |
|---|---|---|
| `bin/seon init --dev default --changed src/seon/cluster/source.clj`, unchanged | 532 | converged, no work |
| `bin/test-check default --policy named --ns seon.cluster.publication-test`, 1 executed | 8,998 | OVER 10 s-class: see below |
| same request answered by reuse (eval_clj) | 1,906 | `seon.test/host-exclusions` 1,309 ms → `seon.fn/gate-sets` → `declared-reference-edges` 1,179 ms: whole-program walk per request (existing issue `docs/seon/issues/gate-set-rederives-the-declared-reference-population-on-every-call.md`) |
| `acquire-context!` isolate + release, first / warm | 934 / 340 | per member and per request handle |

These floors are in `src/seon/test.clj`, `src/seon/fn.clj` and `src/seon/cluster/agent.clj`,
outside this lane's files; the gate composes them and cannot be faster than them.

# What landed (implementation, 2026-09-23)

- `seon.cluster/adopt-rows!` (new private, extracted verbatim from
  `development-source-refresh!`): the database half of adoption on the connection it
  is handed. `development-source-refresh!` calls it; behaviour unchanged.
- `seon.cluster/candidate-gate!` (public): reset the named candidate branch to the
  source's head (roster + `registry/branch!`), `write!` onto the candidate's execution
  handle (`acquire-context!` with `:seon.agent/branch`), `seon.test/run` `:named` with
  `:seon.test/changed` / `:seon.test/identities` restricted to declarations present
  after the write (a retired test is no member), `release-context!`, then `advance!`
  only on green. Answers `:seon.test/passed?`, `:seon.source/tally`,
  `:seon.source/phase-ms`, `:seon.source/gate-run`, `:seon.source/advanced`.
- `seon.cluster/save-gate!` (private): unchanged adopted commit → no test; otherwise
  `candidate-gate!` on `:cluster-<name>-candidate` with `write!` = `adopt-rows!` and
  `advance!` = the existing `development-source-refresh!`.
- `refresh-source!` gains the request member `{:seon.source/gate? true}` (5th arity);
  red answers `:seon.source/reloaded-namespaces []` and the cluster is not written.
  Answer member `:seon.source/gate` = tally, verdict, phase ms (publish, candidate,
  tests, adopt); the existing `:seon.profile/explanation` covers the whole request.
- `bin/test-check [CLUSTER] --gate --changed-path PATH...`: the explicit request over
  the existing prepl client (chosen over a `bin/seon init` flag because `init --dev`
  is served by `src/seon/cluster/boot.clj:637`, outside this lane). Its catch now
  prints every cause's class, message and ex-data (was message only).
- `bin/seon-hook` publication sends the gated request and answers `refused by the save
  gate; default unchanged: <tally> <phase ms>` on red. `.claude/seon-hook.edn` NOT flipped.
- `bin/seon-hook` contract compile: `require` of `seon.contracts-compile-test` (with
  `:reload` only when that file is edited) replaces the `load-file` into default
  (orchestrator follow-up; `docs/seon/issues/the-edit-hook-load-files-the-contract-checker-into-default.md`).
  Observed: hook lines at 04:36:47Z and 04:36:56Z `available errors=0` 72–96 ms with the
  new form. The 19 Vars still show the absolute `:file` from the last pre-change
  `load-file`; a plain `require` is a no-op on a loaded lib, so they clear at the next
  reload of that namespace (not forced by this lane).

Net: src +125 (`src/seon/cluster.clj` 169+/44−; ~40 of the added lines are the moved
`adopt-rows!` body), bin +53/−6, test +68 (new).

# Evidence

| probe (default pid 90963) | result | ms |
|---|---|---|
| gate, unchanged path (`refresh-source! … {:seon.source/gate? true}`, eval_clj) | `unchanged: no changed declaration, no test selected`, candidate 22.9, publish 56.9, adopt 3.1 | 436 |
| `bin/test-check default --gate --changed-path src/seon/cluster/source.clj`, unchanged ×2 | same answer, publish 68.8 / 66.1 | 800 / 740 |
| `bin/seon init --dev default --changed src/seon/cluster/source.clj`, unchanged, parent code (before adoption) | converged | 532 |
| same, this slice ×3 (concurrent lane load in default) | converged | 560 / 620 / 700 |
| regression `seon.cluster.save-gate-test` run 4a2f7723dbb9 | pass 16 / fail 0 | 86,950 |
| regression run 527a3b512103 | pass 16 / fail 0 | 81,360 |
| regression run 161760c0ba45 | red: green case pass-count 0 and target head unchanged; not reproduced | — |
| regression run 1609d2e77163 (started while 161760c0ba45's member thread was live) | unfinished at the cluster check bound | 129,809 |
| next request | `Test run admission refused inconsistent evidence.` (member of 1609d2e77163 still held) | 14,949 |
| contracts of `cluster.clj` + `save_gate_test.clj` against `(schema/declaration-projection (schema.edn/packaged-forms))` | `[]` | 380 |

Regression command (long member; bound 150 s declared with its measurement):
`bin/test-check default --policy named --ns seon.cluster.save-gate-test --include-long --time-limit-ms 300000`.
Without `--include-long` the member is excluded as long.

## Live docstring case: BLOCKED (decision needed, below)

| attempt | what happened |
|---|---|
| `seon.cluster.source/current` docstring (reached by 321 tests) | `seon.test/host-exclusions` refused its own output (`:seon.test/destructive-path` nil at member 16) after 21,396 ms (17,549 pulls; `source/database` projection 16.9 s). Existing issue `docs/seon/issues/test-host-classification-and-path-use-different-program-edges.md`; reproduced on default without the gate. |
| `dependency-digests` docstring (1 reaching test; `:seon.fn/host-bound? true`) | candidate 19,100 ms (acquire 7,644 + rows 684), then `install-row!` refuses host-bound `seon.cluster.reload-measure/-main` inside `seon.test/resolve-test`, whose output contract then refuses the undeclared error map (test.clj:1495). |
| `deleted-identities` docstring (`:seon.fn/host-bound?` false, 1 reaching test) | `Cannot interpret seon.cluster.source/deleted-identities: Unable to resolve symbol: program/identity-attributes` → same resolve-test contract refusal. |

Default was never adopted by a red or refused gate (checked: adopted commit ≠
`current-src`, loaded docstring unchanged). Each probe edit was restored and default
converged with `bin/seon init --dev default --changed src/seon/cluster/source.clj`.

## Why ≤ 700 ms cannot come from this lane's files (measured floors)

| cost | ms | owner | proportional to |
|---|---|---|---|
| `seon.test.runner/program-digest` on any new commit (candidate after its write) | 10,706 (0.2 cached) | `src/seon/test/runner.clj:1024`; audit item 6, `docs/research/agent-platform/cache-invalidation-audit-2026-09-23.md` | program rows touched since the seal, per new connection's revision key |
| `acquire-context!` on default's head (isolate or named) | 340 at 04:05Z → 6,930 / 6,833 at 04:30Z (1,020 `admit-partitioned`) | `src/seon/cluster/agent.clj:783` → `sci.eval/acquire!`; `docs/seon/issues/a-data-only-commit-rebuilds-the-whole-sci-program.md` | rows whose digest differs from the loaded commit |
| `seon.test/host-exclusions` → `seon.fn/declared-reference-edges` per request | 1,191–1,311 | `src/seon/test.clj`, `src/seon/fn.clj`; `docs/seon/issues/gate-set-rederives-the-declared-reference-population-on-every-call.md` | whole program |
| publication of a changed `cluster.clj` (3,600 lines) | 11,431–31,979 under concurrent lane load | `full-source-refresh!` → `seon.fn/analyze-rows` 12.5 s, `assert-capability-contracts!` 11.0 s | the changed file (large) + contention |
| a refused contract carrying the whole handle as `:seon.error/offending` | prepl answer >2 GB ("Required array length 2147483638 + 26") | `src/seon/instrument.clj` refusal | the offending value |

The gate's own work is proportional to the changed identities: branch reset 30 ms,
row adoption 684 ms for 87 identities, unchanged check < 25 ms.

## Decision needed: host code cannot be interpreted on a candidate

The gate's step 2 relies on B2 §2a: the candidate interprets changed rows in SCI. For
filesystem edits to `src/` this does not hold today: 811 of 4,758 functions are
`:seon.fn/host-bound?`, and a row marked interpretable (`deleted-identities`) fails on
an unresolved namespace alias. So a green gate is reachable only for SCI-interpretable
changes (the regression's synthetic agent tests). Three options:

1. **(recommended) Host-bound or uninterpretable changes gate after reload.** When any
   changed row is host-bound or refuses installation, the gate adopts (reload) and runs
   the same reaching tests on a branch of the reloaded default, then returns the tally.
   Red is reported but default keeps the files (default IS the files). Guarantees:
   interpretable changes are gated before mutation; host changes get the same feedback
   one step later. Cost: ~15 lines in `candidate-gate!`/`save-gate!`. Gives up:
   protection of default from a red host edit.
2. **Refuse host-bound changes at the gate.** The hook answers `host-bound: adopt with
   bin/seon init --dev` and never adopts them. Guarantees default never takes an
   untested host change. Cost: ~5 lines. Gives up: the hook for most `src/` edits.
3. **Compile changed host namespaces for the candidate in an isolated classloader.**
   True pre-mutation gating of host code. Cost: new machinery (hundreds of lines),
   protocol/type identity across loaders; violates small-or-wrong. Gives up: simplicity.

Also needed regardless: `seon.test/resolve-test` must return its declared
`:seon.test/resolution-error` for an install refusal (test.clj:1495), and the
aggregation semantics are real: the gate tests the union of every published change
since default's adopted commit, so another lane's pending host edit blocks every save.

RESET NEEDED: no.

# Follow-up (orchestrator ruling: option 1; adoption reorder)

## What changed

- `save-gate!` reads the per-declaration fact through `host-bound-declarations`: a
  changed `:seon.fn/sym` whose `:seon.fn/host-bound?` is true, or that has no such fact,
  in default's rows or the published rows. If there are none, the candidate is tested
  first (as built). If there are any, `adopt-then-test!` adopts into default first, then
  `reaching-run` answers the same reaching tests on default, and red comes back at once
  with `:seon.source/host-bound`, the tally, `:seon.source/phase-ms` and the request's
  `explain-slow`. `reaching-run` is the one selection/run helper both paths use. The
  hook reports `red after adoption (host-bound …)`, and `bin/test-check` prints the
  host-bound list.
- `development-source-refresh!` reorder, with no new mechanism: the reload set, requires,
  arming identities, definition digests and instrumentation projection now come from
  `published-database`, and the config from the pre-adoption cluster value. Reload,
  verify and arm run FIRST. `adopt-rows!` (which now takes the identities; see
  `published-changes`) and the adoption record are then written back to back. Observed
  on default after the change: rows at tx 536871601 / 536871608 / 536871610 and the
  record at 536871602 / 536871609 / 536871611, in the same second each time. Before,
  the note `docs/seon/issues/development-adoption-writes-program-rows-seconds-before-its-record.md`
  measured 0.5–23 s. The pair is still two transactions: `seon.fn/index!`
  (`src/seon/fn.clj:3508`) has no request member for appending tx-data, so making it one
  transaction needs a one-member change in fn.clj, which is outside this lane. No
  regression for this ordering: it would need a development adoption (a reload of
  default's namespaces) inside a test member, which the no-redefs-in-default rule forbids.
- New regression `a-host-bound-change-adopts-first-then-reports-its-reaching-tests`:
  the classification reads the fact (`dependency-digests` true, `writing` false), and
  `adopt-then-test!` adopts before answering the red reaching test.

## (a) run 161760c0ba45: found, not silent

Recorded failure 47528 (`seon.test.failure/message`): the green case's inner run
`cd75254b223d` had an error: `Cannot interpret seon.cluster.reload-measure/-main:
Host-bound declaration … must change through the loaded source files.` The gate returned
`:seon.test/passed? false` with that tally, so it did not advance. The report was exact,
not silent. Run 4ea5109c5a35 (after this follow-up) failed the same way, and so did its
new host-bound member, at the outer member's own acquisition.

## (b) `deleted-identities` in SCI: cause is in `src/seon/sci/eval.clj` (not edited)

Probe (eval_clj on default, isolated handle):
- The cluster ctx's `::loaded-database` commit is `6ab34d95…` (about boot), while default's
  adoption record names `6ab35bfd…`. For `seon.cluster.source/dependency-digests`, the
  loaded digest is `3faf7e12…` and default's row is `7e2bee2d…`. The snapshot does not
  follow adoption: `acquire!` returns early through `acquired-database?`
  (sci/eval.clj:2615) and keeps the stale `::loaded-database`. So every declaration
  adopted since boot reads as overridden (`overridden-row?`, sci/eval.clj:1007) on every
  branch of default, and any host-bound one, or a host-bound caller in the affected
  closure (`reload-measure/-main`), refuses. That is the cause of (a).
- Once a host-namespace row is interpreted, SCI has no aliases for that namespace:
  `(ns-aliases 'seon.cluster.source)` → nil and `(resolve 'seon.program/identity-attributes)`
  → nil in the forked ctx, while the DB `:seon.ns/aliases` row lists 15 aliases,
  including `program`. The host namespace's ns-row bindings are not installed
  (`install-row!`, sci/eval.clj:940, installs a namespace only when its row is
  installed). So `:seon.fn/host-bound? false` on a function of a compiled host
  namespace still cannot interpret. Once the loaded-database defect is fixed, that
  function is no longer overridden and the question disappears for unchanged rows. For
  a changed row, the gate would still need the namespace's aliases in SCI, or the
  indexer's fact to be true.
- Also seen: a concurrent lane's probe `resolution exploded` (`seon.test.one-request-test/probe`)
  surfaced inside my gate's `seon.test/run` at 04:55:25Z, so a redefinition over
  default's Vars was live at that moment.

## Fresh verification results (after this follow-up)

| command | result | ms |
|---|---|---|
| `bin/test-check default --gate --changed-path src/seon/cluster/source.clj` (restoring the HEAD docstring of `dependency-digests`) | host path: host-bound `[dependency-digests publish! publication-input-digest! publication-error record-results-at-head!]` adopted (adopt 423 ms), then red `Test run admission refused inconsistent evidence.` (tests 17,405 ms; `program-digest` 20,006 ms inclusive); exit 1 | 22,702 |
| same, unchanged | `unchanged: no changed declaration, no test selected`, publish 39 ms; exit 0 | 327 |
| `bin/test-check default --policy named --ns seon.cluster.save-gate-test --include-long --time-limit-ms 400000` | run 4ea5109c5a35: red; both members error on the stale loaded program (b) | 113,590 |

The gate names four more `source.clj` functions as changed on every save of that file
(`publish!`, `publication-input-digest!`, `publication-error`, `record-results-at-head!`),
and they widen the reaching set. Their definitions did not change, so the publication
report's identity set for that file needs a check (indexer; not diagnosed here).
Per the ruling, the union of all published changes no longer blocks a save: each save
adopts. The admission refusal above is `seon.test`'s own (a reached member holds
unfinished evidence).
