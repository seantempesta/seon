---
type: research
status: current
created: 2026-09-17
tags: [research, steward, testing, fixture, absence-as-health]
---

# Finishing the fixture-write sweep — every discarding seed now proves its report (2026-09-17)

Lane: fixture-write-sweep. Issue:
[fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour](../../../seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md).
Predecessor: [fixture-write-helper-2026-09-16.md](fixture-write-helper-2026-09-16.md)
(`477cb615c`, which landed `seon.test-support/transacted!` and converted four
namespaces by hand).

The class hit an eighth time after the helper landed: `seon.render-coverage-test`
seeded `{:seon.fn/sym "my.fs/read"}` without `:seon.schema.admission/source`,
discarded the refusal and read the empty world as behaviour (`ac95db78a`). The
first pass converted the namespaces its three triages happened to visit. This
pass enumerates the whole tree.

## How the sites were enumerated

A regex over `transact!` is not an enumeration: 686 of the 1014 textual hits in
`test/` begin a line, and a line-start call is as often an argument
(`(is (:db-after\n (db/transact! …)))`) as it is a discarded statement. The sweep
parsed every file under `test/` with rewrite-clj and propagated a
*value-is-discarded* flag down the tree from each top-level form:

- a body head (`do`, `let`, `when`, `testing`, `deftest`, `try`, `binding`,
  `with-open`, …) discards every child but its last; the last inherits the
  enclosing flag;
- `doseq`/`dotimes`/`while`/`run!` discard their last body form too — they
  always answer `nil`;
- a `let` binding whose name is `_` discards; any other name consumes;
- any other list is a call, so its arguments are consumed;
- the last form of a `defn`/`defn-` is a **fn-tail**: whether it is discarded
  depends on the callers, so it was classified separately.

| class | count before the sweep |
|---|---|
| discarded outright | 593 |
| fn-tail (fixture helper's own return) | 32 |
| bound to a name or consumed by `is`/`:db-after`/`map?` | 310 |

The 310 consumed sites were left alone: they already read the answer, and
widening them would churn diffs for nothing. The 32 fn-tails were converted
too — `transacted!` returns the same transaction report on success, so a caller
that *does* consume it is unaffected, while a caller that discards it now stops
at the write.

## What changed

524 sites in 90 files in the first mechanical pass, then 54 more (tail
positions and the named seeding helpers) in a second, then three hand slices.
**588 call sites across 96 files.** The rewrite was scripted, then verified
token by token: for every changed file the token stream of `HEAD` and of the
working tree are identical except for the called symbol — no string literal, no
argument, no form, no reader tag changed. Only the called symbol and the
continuation-line indentation of the rewritten form differ.

Commits (branch `steward-platform`), one path-limited slice per namespace group:

| commit | group |
|---|---|
| `e89117099` | `test/seon/cluster/` (21 files) |
| `8f403d034` | `test/seon/render/` (10 files) |
| `391e3be12` | turn, evaluation, rereads, call-preparation (14 files) |
| `6fada0838` | `test/my/` (4 files) |
| `3041973a7` | program, schema, test machinery, everything else (41 files) |
| `e485aa8d2` | tail-position seeds and the 32 seeding helpers (26 files) |
| `73a58c309` | `seon.effect-test`, `seon.edit-test`, `seon.flow.kill-child` |
| `f37556a78` | `seon.turn-test`'s three dead fixtures |
| `b981b04d0` | `seon.fn-test`'s dead settlement-parity fixture |

**Shared-tree note on `b981b04d0`:** a peer lane added
`seon.fn-test/a-file-changed-after-capture-analyzes-to-the-captured-spans` to
`test/seon/fn_test.clj` between this lane's `git status` and its path-limited
commit, so that commit carries their new test as well as this repair. Nothing
was lost or reverted; naming it so the authorship is not a mystery.

### `seon.effect-test` and `seon.edit-test` — checked, but a second path

Both already read the report, so neither was the absence-as-health class; both
carried their OWN checked wrapper with its own `ex-info` shape, which is the
duplication the issue's fix shape names. `seon.effect-test/transact-fixture!`
(21 call sites) is now a three-line delegation to
`seon.test-support/transacted!`; `seon.edit-test`'s two inline
`(when-not (:db-after report) (throw …))` blocks are gone
(`test/seon/edit_test.clj:262`, `:290`). One failure shape, not three.

### `seon.flow.kill-child` — a discarding write outside any fixture

`test/seon/flow/kill_child.clj:29` and `:32` are the child JVM of the Flow
process-death standing proof (`test/seon/flow_test.clj:1477`). It committed its
durable step and discarded the answer: a refused write would have reached the
parent as a MISSING DATOM after the kill, not as a named refusal — the class
outside a fixture. Both writes now go through `transacted!`.
**Flagged risk:** the child now requires `seon.test-support`, which is a heavier
load in a cold child JVM. The parent's readiness wait is
`seon.test-support/event-backstop-seconds` and its own comment already allows
for a cold JVM loading Clojure and Datahike, but this is the one edit in the
sweep that changes a timing budget rather than only a called symbol. Watch
`seon.flow-test` in the cold gate.

### Deliberately NOT converted

- **Raw `datahike.api/transact` fixture writes** — `test/seon/db_test.clj:382`,
  `test/seon/render/data_test.clj:72`, `:105`, `:120`,
  `test/seon/schema_test.clj:35`, `test/seon/cluster/store_transact_test.clj:187`,
  `test/seon/dev/fresh_operator_test.clj:1442`. These bypass `seon.db`'s write
  admission ON PURPOSE (synthetic attributes admission would refuse), and
  Datahike's own `transact` THROWS rather than returning a flat value, so a
  refusal there is already loud. They are not the class. Converting them would
  turn seven deliberate probes red.
- **Four files another lane holds**: `test/seon/render_simplification_test.clj`,
  `test/seon/returned_error_test.clj`, `test/seon/render/page_settings_test.clj`,
  `test/seon/render_coverage_test.clj`.
- **`seon.custody-stability-test`** — already examined by the first pass: SCI
  source strings, not fixture calls with a connection in hand.

## Baseline for the detector

After this sweep, **16 raw discarding `seon.db/transact!` sites remain in
`test/`, and every one of them is in a file this lane is forbidden to touch**:

| file | sites |
|---|---|
| `test/seon/render_simplification_test.clj` | 15 (`:105`, `:140`, `:185`, `:345`, `:431`, `:446`, `:572`, `:707`, `:787`, `:828`, `:838`, `:855`, `:927`, `:947`, `:964`) |
| `test/seon/render_coverage_test.clj` | 1 (`:408`) |

Outside the lane-held files the count is **ZERO**. That is the baseline the
detector (issue-note item 2) should hold at: a test function calling
`seon.db/transact!` whose value is discarded is a generated issue. The
classifier above is the shape the detector needs — a textual rule would report
310 false positives.

## In-process verdicts

pid 17352, cluster `default`, `seon.test-support/database-base` already realized
by a peer lane (checked with `realized?` first, per the BASE CONSTRUCTION RULE,
and its value dereferenced to prove it was not a cached throwable). Every run:
`(seon.test/run v c {:seon.test.run/provenance (seon.test.runner/provenance
(seon.db/db c)) :seon.test/remaining-ms 100000})` on a daemon thread, each test
namespace reloaded through `#'seon.test/with-test-loader` first (the
IN-PROCESS TEST RELOAD RULE — a bare `require` reports false greens).

### Dead fixtures the helper made honest

Four fixtures had been seeding NOTHING since write admission tightened, and
passed anyway. All four surfaced at `test_support.clj:247` in the orchestrator's
cold batch-62 gate (`tmp/orchestrator/gate-results/batch-62/named.md`, root
`tmp/test-runs/run.cdS8Pp`), were repaired at the root through the mechanism's
own path, and are green in-process:

| test | what was dead | repair | in-process |
|---|---|---|---|
| `seon.turn-test/refreshes-only-terminal-system-reads-once` | `settle! "agent-source" true` ran `receipt-start-tx` for an ordinal `seon.turn/plan-tx` had ALREADY minted — `receipt-exists` | the fixture stops writing what the mechanism writes (`false`) | 8 / 0 / 0 |
| `seon.turn-test/run-derives-its-opening-database-and-starting-namespace` | upserted `{:seon.turn/id "replay-run" :seon.turn/opened-tx …}` with no `:seon.turn/agent`, which the turn schema requires | supply the agent ref the open transaction used | 9 / 0 / 0 |
| `seon.turn-test/recovery-preserves-terminal-receipts-exactly` | set ONE attribute on an existing turn through an identity-keyed map, which write admission reads against the WHOLE turn schema (`:seon.turn/agent`, then `:seon.turn/opened-tx`) | one attribute on an existing entity is a datom: `[:db/add [:seon.turn/id run-id] :seon.turn.work/situation :generate]` | 1 / 0 / 0 (the generative property) |
| `seon.fn-test/settled-agent-form-has-static-index-edge-parity` | same `receipt-exists` as the first: `plan-tx` mints ordinal 0, then the fixture started it again | the redundant `receipt-start-tx` is deleted | 4 / 0 / 0 |

Commits `f37556a78` (turn) and `b981b04d0` (fn).

The third one is worth naming as a finding rather than a fix: **a partial upsert
of an existing entity is read against the entity's complete required-key set.**
The fixture's map carried the one attribute it meant to change and was refused
for keys the open transaction had already written. That is the
`write-admission-validated-partial-maps-against-every-schema` issue seen from
the fixture side; the datom form sidesteps it, but a lane owning admission
should decide whether a partial map upsert SHOULD require keys the entity
already carries.

### The broad sweep

pid 17352, one test at a time on a daemon thread, alphabetically over the
touched namespaces. **184 tests across the first ~25 namespaces
(`my.agent-test` → `seon.cluster.armed-test`) completed with ZERO fixture-write
refusals** beyond the four above. Then the `default` JVM disappeared
(`bin/seon status`: `0/0 clusters alive`, one orphan seon JVM 38501) and the
sweep's state went with it. A lane never restarts `default`; reported, not
repaired. The remaining ~70 namespaces are **cold-gate only** for this lane.

Non-refusal reds seen during the sweep, none of them this lane's:

- `my.agent-test/settings-are-one-owned-component-with-a-derived-render-pair`
  and `…/settings-updates-preserve-one-component-and-agent-isolation` —
  `(agent/settings db "other-owner")` answers `{}` where the test expects
  `#:my.agent{:turns-left 0}`. `:my.agent/turns-left` is produced by
  `seon.turn/turns-left`, which a peer lane was editing in the working tree
  during this run.
- `seon.agent-situation-test/situation-is-the-live-derived-control-surface`.
- `seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold`
  and `seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings`
  — both expired the declared 100 s in-process bound on a JVM running another
  lane's work; not observed cold.

**Why an assertion failure cannot be this sweep's.** `transacted!` differs from
`seon.db/transact!` in exactly one way: a refusal throws instead of returning a
flat value. If the seed lands, behaviour is identical. So only an `ex-info`
reading `Fixture write was refused at the write: …` belongs to this lane; a
`FAIL` is either pre-existing or another lane's.

### Reading a red

The conversion changes exactly one thing: a refused write now throws instead of
returning a flat value. So an **assertion failure cannot be caused by this
sweep** — if the seed landed, behaviour is byte-identical to before. Only an
`ex-info` reading `Fixture write was refused at the write: …` is this lane's:
it is a fixture that had been seeding NOTHING and passing anyway.

## Verification boundary

- In-process only, on one shared JVM whose namespaces other lanes also reload.
  The cold gate is the final proof; the request is
  [tmp/orchestrator/gate-requests/fixture-write-sweep.txt](../../../../tmp/orchestrator/gate-requests/fixture-write-sweep.txt).
- `^{:seon.test/long}` tests were excluded from the in-process sweep.
- `seon.flow-test`'s process-death proof spawns a child JVM; it was not run
  in-process and is cold-only for this lane.
- `src/seon/render.clj` and `src/seon/turn.clj` carried a peer lane's
  uncommitted edits throughout; they were never touched and never committed.
- The `default` JVM (pid 17352) vanished mid-sweep. This lane did not start,
  stop, or refork it.
