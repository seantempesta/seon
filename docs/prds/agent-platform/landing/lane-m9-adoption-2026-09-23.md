---
type: landing
status: landed (03bd7cfc9, eac0333c6, 306f32431, 779fb9b23); base-context-per-connection (audit item 5) not landed
created: 2026-09-23
tags: [agent-platform, adoption, sci, acquisition, M9, P0-0j]
---

# Lane m9-adoption (2026-09-23)

Schedule rows M9 / #9 and the P0 added mid-lane (move to HEAD panicked "Program
acquisition refused"). Grounding: `docs/research/agent-platform/m9-adoption-2026-09-23.md`
(c0fd1877c). The draft patch there did not apply; it was re-derived against HEAD.

## Commits

| commit | change |
|---|---|
| `03bd7cfc9` | `acquire!` derives `::loaded-database` from the adopting cluster's live record (`loaded-source` → `loaded-commit-id` → `loaded-program`), never the boot capture; `::loaded-connection` passed by `cluster/boot.clj` and inherited by forks; `acquired-database?` treats a moved record as stale (`loaded-program-current?`), answering an unmoved record from Datahike's attribute revision (`loaded-basis`). An agent-authored row's refusal goes to `::agent-mistakes` (recorded through the one error owner, not an acquisition refusal); every other row's still refuses. `acquisition-refusal` carries `:seon.error/chain`. Regression added. |
| `eac0333c6` | the throwable-carrying refusal is a new function (`interpretation-failure`), not a new arity of `interpretation-error` (see "stale arming" below). |
| `306f32431` | both `call-preparation/watch!` registrations deleted (flow audit D3). `seon.call-preparation/watch!` has no remaining caller (`rg` over src, test, script). |
| `779fb9b23` | `branch_execution_test.clj`: the data-only commit writes a `my.note` instead of the retired `seon.dev.mcp.artifact` row; the M9 regression asserts the subject leaves the interpreted set. |

Changed paths: `src/seon/sci/eval.clj`, `src/seon/cluster/boot.clj`,
`test/seon/sci/branch_execution_test.clj`, this note.

## Proof

All on scratch roots booted from `git archive` snapshots (reference-code, target
linked; `.cpcache` absent in the checkout, so a cold classpath). Default was never
touched. Probe forms: `tmp/m9-adoption/probe-scratch.clj`, `bench_scratch.clj`,
`bench_unrelated.clj`, `bench_branch_write.clj`, `agent_mistake_scratch.clj`.
Parent code was loaded with `load-file` of `03bd7cfc9~1:src/seon/sci/eval.clj`
(identical bytes, `cmp`), mine with the committed file; scratch JVMs only.

1. **Probe (tmp/m9-root, HEAD e4f280e81 + diff).** Two rows' digests changed on a
   stand-in source branch, adoption record written on a probe branch, fork acquires
   with that branch as loaded connection.
   - parent: loaded = boot commit `6ab32632…`, **149 interpreted / 120 refused**, acquire 16,782 ms
     (`seon.error/commit-call` ×120 = 10,514 ms recording them).
   - mine: loaded = record `6ab327c7…`, **0 / 0**, acquire 1,656 ms; unchanged re-acquire 0.57 ms.
2. **Regression** `seon.sci.branch-execution-test/an-adoption-record-moves-the-loaded-program-every-acquisition-compares-against`
   through `seon.test/run` (`--changed seon.sci.eval/acquire!` so it executes):
   red at the parent (run `19c4828026ad`, 6 fail / 12), green at mine (run `9328ad0741a2`, 12 pass)
   on tmp/m9-move-root; earlier version red `67436f359177` (5 fail) / green `897a63bb0092`.
   The other four tests in the namespace error identically at parent and mine (run
   `9bc6da60924a`): `seon.fn/source-rows refused namespace-row … definition-digest`
   (test_support.clj:746, existing issue `fixture-namespace-rows-lack-the-required-definition-digest.md`)
   and "A different SCI context is already armed on this thread".
3. **P0 move proof (tmp/m9-move-root).** Booted at `bfcce39ad`; `my.agents.root/largest`
   (`[:=> [:cat [:vector :map]] :map]`, admission `:agent`) declared through evaluation + `turn/row-tx`.
   - `start --head` at `d3bcdc302` (parent): **did not reach readiness**, "Cannot interpret
     my.agents.root/largest: :malli.core/invalid-schema" (`tmp/m9-adoption/move-parent-2.log`).
   - at `03bd7cfc9`: **ready** (missing-layers `[]`), the refusal recorded as an error fact in
     readiness problems (signature `ae2fbc75…`, layer acquisition, row-member largest); adoption
     then refused on stale arming (below) (`move-mine.log`).
   - at `eac0333c6` (includes wrapper-profiling `0913df3c7`): **ready and adopted**, exit 0
     (`move-mine-2.log`). largest now installs (1 interpreted, 0 refusals): the invalid schema was
     the handed-projection defect `0913df3c7` fixed, a core cause. After adoption the context was
     stale (record `6ab32de0…` ≠ loaded `6ab32a81…`), re-acquired to the record in 2,845 ms.
4. **Contracts compile from zero.** `seon.contracts-compile-test/check` over the three files
   against the packaged projection of the `eac0333c6` archive: 0 findings, 705 ms.
5. **sci/eval.clj:~3193 (IdentityHashMap).** At HEAD the contract is `:seon.profile/mark`, whose
   snapshot member is the named predicate `seon.profile/snapshot?` (resource `seon.profile.edn`).
   A `(seon.profile/begin)` mark validates against the packaged and the carried projection and
   `explained-evaluation` accepts it (moved root). No edit was needed.

## Hot path (parent → mine, same scratch JVM, µs per call unless marked)

| operation | parent | mine | note |
|---|---:|---:|---|
| `acquired-database?`, unchanged | 5.6 / 5.9 | 27.9 / 29.9 | **+22 µs, over 20 %.** The added read is `db/db` of the adopting connection (21 µs armed); the record query runs only when the revision moved. Absolute cost is 0.07 % of one evaluation. Needs the orchestrator's ruling or a cheaper connection read in `seon.db`. |
| `acquire!`, unchanged program | 6.0 / 6.4 | 26.8 / 28.2 | same read |
| plain SCI eval `(+ 1 2)` | 31.4 / 35.7 ms | 31.7 / 31.8 ms | unchanged |
| eval after an unrelated commit, 30 rounds (watch deletion) | median 30.3 ms | 26.6 ms | |

## TIMINGS (every operation over 1 s)

| operation | wall ms | justification / status |
|---|---:|---|
| scratch boot tmp/m9-root (HEAD archive) | 139,140 (ready 102,981) | **>10 s defect**, existing `from-zero-boot-takes-minutes.md`; dependency classes missed (`:pins-unavailable` in an archive) |
| scratch boot tmp/m9-move-root (`bfcce39ad`) | 170,200 (ready 130,781) | **>10 s defect**, same issue; cold `.cpcache` (the checkout's is absent) |
| `start --head` parent, attempt 1 | 127,080 | refused static analysis: I seeded the root's analysis cache from a snapshot linked to the checkout's `.clj-kondo/.cache` (my setup error; the operator's `root-analysis-cache!` then seeded from it) |
| `start --head` parent, attempt 2 | 163,690 (launch 155,615) | **>10 s defect** (boot); P0 reproduced |
| `start --head` 03bd7cfc9 | 182,230 (ready 111,923, adopt 13,225) | **>10 s defect** (boot; adoption refused, below) |
| `start --head` eac0333c6 | 219,750 (ready 125,557, adopt 52,306) | **>10 s defect**: adoption 52 s is M11's class |
| acquire with 120 refusals (parent code) | 16,782 | **>10 s defect** from `record-acquisition-refusals!`: 87 ms per `error/commit-call`; extends `warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor.md`. With M9 these rows no longer refuse. |
| full acquisition (mine) | 1,656 / 2,845 | proportional to the whole program: `cache-program!` over every function row and `seon.fn/reverse-closure` (1.5 s) seeded by the overridden rows |
| regression test run | 14,548–22,809 | test body ~10.8 s: four complete acquisitions (0.46–4.4 s each, two recording one refusal) plus two branches; `seon.test.runner/program-digest` ×6 = 20,730 ms in one run (**>10 s defect**, audit item 6) |
| namespace test run (6 members) | 30,375–41,121 | same causes; four members error pre-existing |
| reverse-closure sweep over 867 host-bound rows (probe on default, pre-move pid 9104) | 49,657 | my observer: 867 × 57 ms closures, not system work |
| mechanical acquisition probes on branches | 2,856–5,570 | two full derivations each |
| `git archive` HEAD | 1,610 | proportional to the tree's files |

## Not landed: base context per connection (audit eb903978b item 5)

Reproduced on tmp/m9-move-root: a branch that takes a data write before its first
fork re-derives the base context, fork + acquire 2,911–3,327 ms at an equal program
(parent and mine alike). A branch written after its fork already reuses the acquisition
(in-connection lineage from `5f2aa93f4`, eval after write 29–69 ms). A listener that
inherits `db-before`'s identity (tried, reverted) cannot see a write that lands before
any listener exists on that connection, so it gains nothing. The fix needs the listener
registered where the branch connection opens (`cluster.store/open-branch!` or
`cluster.agent/acquire-context!`, neither held here), or a content key.

## Findings outside these paths

- **agent.clj:885-888** (held by realities): with `03bd7cfc9` `acquired-program`'s
  `:seon.test/acquisition-refusals` holds only non-agent rows. The ruled change is to delete
  the `_ (when (seq refusals) (throw …))` binding, so `arm!` arms what acquires; refusals are
  already stored and routed by `record-acquisition-refusals!`. Keep a throw only for
  `::acquisition-recording-error` (a refusal that could not be stored). Under `:panic`, a
  core-row refusal should throw per the error policy; that choice is the orchestrator's.
- **Classification risk.** A refusal is an agent mistake because its row is agent-authored. On
  the parent move its cause was core (`0913df3c7`). It is still stored with its chain and
  delivered, but not loud under `:panic`.
- **Stale arming on a moved root** (cluster.clj / instrument.clj, wrapper-profiling):
  `arm-host-program!` arms from the pre-move rows. `interpretation-error`'s new arity refused
  "argument count 3" (eval.clj:1035), and `seon.db/write-render-target-error`'s arity from
  `f4dd51d68` refused "argument count 2" (db.clj:3787) during adoption. Any arity change in code
  a moved root runs before re-indexing is refused.
- **One stored agent contract that references an undeclared key refuses every projection
  derivation** of its cluster: MCP projection, writes and disarm (`seon.schema/projection-registry`).
  I fabricated it on tmp/m9-root with a raw `:seon.fn/spec` write that the writer accepted.
- "no core definition …-test/…" entries in the move logs are `:seon.sci.eval/load-results`
  (`:unavailable`), not acquisition refusals: 0 of them sat in the refusal vector.
- `seon.test/run` with `:seon.test/namespaces` threw `seon.test/green? refused result at
  [:seon.test/run-basis-t]` on a reused member (test.clj:1718).

## Verification boundary

Scratch JVMs only (tmp/m9-root HEAD e4f280e81 + diff; tmp/m9-move-root moved to eac0333c6,
then 306f32431 and the test file loaded with `load-file`). Default is at its own program
and did not run this code. The regression ran through `seon.test/run` on the scratch
cluster. Parent comparisons swapped `sci/eval.clj` in the same JVM, so the base-context
memo (defonce) is shared across versions. RESET NEEDED: no. Both scratch roots were
stopped and removed at the end; the `tmp/m9-adoption/` logs are kept.
