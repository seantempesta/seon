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

## Second pass: batch 65 cold, and the helper's own defect (2026-09-17)

Batch 65 B ran the 95 swept namespaces cold: 1,152 tests, 146 distinct failing
tests in 46 namespaces. **64 of those were `transacted!` refusals at
`test_support.clj:250` — dead fixtures the helper made honest.** The rest were
pre-existing reds the sweep never touched.

### The helper's own defect: a clipped diagnostic (`61519c245`)

The refusals arrived in the gate log as

```
#error {:cause {:seon.print/omitted 3458,
                :seon.print/prefix "Fixture write was refused at the write: …"}}
```

Measured live: `seon.test.runner/report-value` rendered the throwable through
the AGENT render profile, whose token budget is shared across the whole value.
Write admission's `ex-data` carries `:seon.db/entity-form` — the entity SCHEMA
the row was read against, 97 children — so the exception's own message was
squeezed out. A 760-character message with a 60-key `ex-data` was clipped at
204 characters; with a small `ex-data` the same message survived. **The report
named less than the runner knew: the absence-as-health class wearing the
reporter's clothes.**

A reported THROWABLE is now plain text — its complete message plus frames
bounded by the DECLARED `:seon.print/length`, never the agent's budget
(§2.4). Ordinary assertion values keep the profile; they are the ones that can
be a whole database value. The runner's failure-IDENTITY path
(`seon.test.runner/printable`) had always rendered a throwable this way, so
this makes the two halves agree. Regression:
`seon.test-support-test/a-long-refusal-reaches-the-failure-message-whole`
(9 / 0 / 0 in process), which asserts the complete refusal reaches the report
and that no elision value stands in for a test diagnostic.

This is why the second pass was fast: with the whole refusal in the failure
message, every remaining dead fixture named its own missing key.

### Classes fixed, at each mechanism's own path

| class | count | namespaces | root fix |
|---|---|---|---|
| partial config overlay (`:seon.config/applied-manifest-digest`) | 22 | bootstrap, turn-work, web.jvm, turn-loop, render.web, background-blob | **ONE helper**: `seon.test-support/apply-config!` → `config/apply!`, plus a manifest arity on `seed-cluster!` |
| program row without `:seon.schema.admission/source` / `:seon.fn/ns` | 15 | call-preparation, schema-usage-guard, maintenance-schema, maintenance, cluster.boot, bootstrap | **ONE helper**: `seon.test-support/program-fn-row` |
| bare `{:seon.cluster/name n}` (no `:seon.cluster/config`) | 6 | db, db-immutability, cluster.agent-identity, cluster.instruction | `seed-cluster!`, or a datom when only one attribute changes |
| message without `:seon.message/to` | 6 | db, render.web, render.web-context, cluster.boot | each probe message names a seeded recipient |
| evaluation without `:seon.cluster.eval/at` | 8 | turn-work, problem-routing, transcript-run, concurrency-independence | a freeze has an instant |
| `:seon.cluster.eval/result-edn` — not an installed attribute | 17 occurrences | problem-routing, context-selection, render-source, transcript-run, resume-artifact-routing, concurrency-independence, bootstrap | the current spelling is `:seon.eval/shown` (drift retired on sight) |
| turn without `:seon.turn/agent` / `:seon.turn/opened-tx` | 4 | turn-loop, concurrency-independence, concurrency-streams, context-selection | carry both |
| `:seon.turn/closed-tx` given an instant | 2 | turn-work | it is a REF to the closing transaction: `"datomic.tx"` |
| `receipt-exists` | 3 | turn-loop, fn | `plan-tx` already mints its sources' evaluations; the following `receipt-start-tx` was the fixture writing what the mechanism wrote |
| schedule rows (`:seon.schedule/zone-id`, `:seon.schedule.fire/agent`) | 3 | maintenance, maintenance-schema | supply them |
| attempt row without `:seon.ai/endpoint` | 1 | turn-loop | the attempt names its provider, as `record-attempt!` does |

**The recurring shape under half of these: a PARTIAL UPSERT of an existing
entity is read against that entity's COMPLETE required-key set.** A fixture
that means to change one attribute of a turn, a cluster or a config row must
write a datom, not an identity-keyed map. Whoever owns write admission should
decide whether that is the intended rule; from the fixture side it is the
single most common way to author a refused row.

`seon.sci.eval-instrumentation-test/an-instrumented-dev-cluster-builds-an-attempt-ready-prompt`
left 33 vars instrumented for the next test in its pooled worker; it now runs
inside `seon.test-support/preserving-instrumentation-state` (§5, own nothing
global) — `0a1bc44c1`.

Commits: `61519c245` (runner), `e35c32118` (config class), `4209bdf89`
(program rows), `0da13c8ae` (clusters/messages/turns), `0a1bc44c1`
(instrumentation), `d090c9934` (second wave).

### Per-namespace verdict

In-process, one test at a time, before the orchestrator paused runs:
**21 of the first 64 were green**, 21 named a further missing key (all of them
fixed in `d090c9934`, whose verification batch was cut off when `default`
went away), 8 refused in process for reasons that are not the class, 14 were
non-refusal reds.

| namespace | verdict |
|---|---|
| bootstrap, turn-work, turn-loop, render.web, cluster.instruction, cluster.problem-routing, concurrency-independence, concurrency-streams, context-selection, maintenance, maintenance-schema, render.transcript-run, render.web-context, render-source, schema-usage-guard, call-preparation, db, db-immutability, cluster.agent-identity, cluster.boot, cluster.resume-artifact-routing, sci.eval-instrumentation | fixed — cold gate is the proof |
| `seon.web.jvm-test` (7 tests), `seon.background-blob-test` (1) | **cold-only**: these reach a file-backed published root and answer a flat error in process, exactly as the orchestrator warned. Not worked around. |
| `seon.cluster.boot-test/explicit-refork-destroys-…`, `seon.flow-test/forced-child-jvm-death-…` | **cold-only**: they spawn or refork real JVMs |
| `seon.call-preparation-test/a-compiled-first-party-call-is-prepared` | **excluded** — another lane triages it as a possible real defect |
| `seon.cluster.boot-test/a-dead-holders-run-is-unclaimed-by-the-time-start-returns` | **excluded** — same |
| `render_simplification_test`, `returned_error_test`, `render/page_settings_test`, `render_coverage_test` | **excluded** — another lane holds the files; their 16 raw discarding writes are the whole remaining baseline |

Non-refusal reds observed in process and NOT this lane's (a refusal throws; an
assertion failure cannot be caused by adding a check to a write that lands):

- `my.agent-test` ×2 — `agent/settings` answers `{}` where the test expects
  `#:my.agent{:turns-left 0}`; `:my.agent/turns-left` comes from
  `seon.turn/turns-left`, an area a peer lane was editing.
- `seon.agent-situation-test/situation-is-the-live-derived-control-surface`.
- `seon.bootstrap-test/absent-intent-budget-refuses-loudly` — its premise is
  that `seed-cluster!` leaves `:seon.config.bootstrap/beyond-closure-token-budget`
  absent, but `config/default.edn:140` ships it. Stale expectation, not a seed.
- `seon.bootstrap-test/drive-free-generation-is-pure-deterministic-and-pull-gated`
  — a `seon.render.walk/ordered-episode` contract violation at
  `[:seon.repl/candidates 4 :seon.repl/subject]`.
- `seon.cluster.agent-identity-test/identity-map-and-omitted-arguments-use-the-same-function`
  — with the cluster now really seeded, `(:my.agent/id value)` is nil. The
  fixture is honest; the assertion now reaches a real question about the
  identity map. Flagged rather than forced.
- `seon.cluster.boot-test/incompatible-sovereign-schema-refusal-steers-the-operator`
  — the cause chain no longer retains the schema mismatch.
- `seon.concurrency-test` ×24 at `concurrency_test.clj:219`/`:243`, and the
  `instrument.clj:414` group — untouched by this sweep.

### Verification boundary, second pass

- The orchestrator PAUSED in-process runs on `default` (store growing ~1 GB/min,
  writer under measurement) while `d090c9934`'s verification batch was running;
  that batch's results are lost with the JVM. Everything in `d090c9934` is
  therefore **edit-verified and lint-clean but cold-gate-unproven**.
- `default` is `0/0 clusters alive` as this note is written. This lane did not
  start, stop or refork it.
- `test/seon/operator_test.clj`,
  `resources/seon/schemas/seon.operator.cluster-cleanup.edn`, `src/seon/fn.clj`,
  `src/seon/sci/eval.clj`, `src/seon/test.clj` and
  `resources/seon/schemas/seon.test.edn` carried peer edits throughout; none was
  touched or committed by this lane. `seon.fn-test` gained a peer's new test
  inside `b981b04d0` (named above).

## Third pass: batch 70 (`ca9a8b0e8`) — the closing instant, and the rest of the keys

Batch 70 ran the 23 second-pass namespaces cold: 306 tests, 297 F / 16 E.
With the diagnostic now unclipped, every refusal named its own offending row,
so this pass read causes instead of guessing them.

### (a) `:seon.turn/closed-tx` is a transaction REF — one reader, not 50 edits

`ae0e54841` ("Move agent data to transaction refs…") changed
`:seon.turn/closed-tx` from an instant to a ref to the CLOSING TRANSACTION.
Pulling the attribute answers `{:db/id N}`, so every
`(inst? (:seon.turn/closed-tx …))` assertion has been false since that commit —
they simply never ran, because their fixtures refused first. 50 assertions,
but only **four reader sites** (one 45-assertion loop in
`concurrency_independence_test.clj:350`, a local `closed-at` helper, and two
inline pulls).

`seon.test-support/turn-closed-at` pulls `{:seon.turn/closed-tx [:db/txInstant]}`
and answers the instant, exactly as `seon.plan` reads
`:my.plan.item/completed-tx` (`src/seon/plan.clj:67`). **No assertion was
relaxed**: each still asserts the turn closed, and now reads the fact where it
lives. One fixture was also WRITING an instant into the ref
(`problem_routing_test.clj:247`); `"datomic.tx"` is how a transaction names
itself.

Design note, deliberately not taken here: the same reader arguably belongs in
`seon.turn` as a public contracted function, since agents ask when a turn
closed. It was kept in `seon.test-support` because the drift is in test
expectations, not in production, and a new public contract in `src/` during a
red-fixing pass is a separate decision. Commit `4d181533d`.

### (e) The remaining fixture keys — `55a0abae0`

| key | where | root fix |
|---|---|---|
| `:seon.schema.admission/source` on `:seon.test/sym` rows | bootstrap (2) | declared, as on `:seon.fn/sym` |
| `:seon.turn/opened-tx` | background-blob, web.jvm | every seeded turn carries it |
| `:seon.cluster.eval/at` | context-selection | a freeze has an instant |
| `:seon.error/at` | turn-work | **not an installed attribute**: it is a REQUEST key `seon.error/recording` reads (`src/seon/error.clj:1417`), and the writer decides which datoms an occurrence gets. The hand-written row dropped it. |
| `:seon.ai/endpoint` | turn-loop | an attempt names its provider |
| config ROW written as `(assoc (config/defaults) …)` | web.jvm (2 sites) | `web-manifest` is the overlay, `apply-config!` the path; `config` stays the effective VALUE handed directly to a handler, which is a different thing |

### (f) The four singles

- `seon.bootstrap-test/absent-intent-budget-refuses-loudly` — its premise is
  that the dial is absent, but `config/default.edn:140` SHIPS
  `:seon.config.bootstrap/beyond-closure-token-budget`, so `seed-cluster!`
  always writes it. **Absent is a fact**: the fixture now retracts the dial,
  which is the only honest way to observe the loud refusal.
- `seon.render.web-context-test/context-and-page-do-not-demand-the-render-proc`
  and `seon.render.web-test/unrelated-transaction-reuses-debug-observation-and-render-call`
  (`d7e5a0268`) — both prove an UNRELATED commit does not re-walk the agent's
  history, and both seeded that commit as a message addressed to the agent
  under test. `:seon.message/inbox` is a LISTENED attribute: once the seed
  stopped refusing, the "unrelated" transaction was a wake (`@walks` = 2, not
  1). Both now address a bystander agent. The sweep did not break these; it
  revealed that they had been proving nothing.
- `seon.test-runner-test/repeated-identical-errors-have-one-whole-face` and
  `seon.render-source-test/source-contracts-…` were NOT reached: see the
  blocker below.

### Blocked, not fixed

- **`seon.concurrency-independence-test` (199 F) and
  `seon.concurrency-streams-test` (9 F) are blocked on the history-cut
  ruling** — the dir-elision lane's A/B shows the agent's history is rendered
  as ONE string through the value budget (`transcript.clj:1893`; 2,075 chars
  against a ~640-token budget), so `(str/includes? rendered payload)` could
  never have passed. Issue:
  `docs/seon/issues/the-agents-history-is-cut-as-one-string-by-the-value-budget.md`.
  Their assertions were NOT relaxed. Their closed-tx reads were fixed anyway,
  since that is an independent bug.
- `seon.render.transcript-run-test/render-run-selects-only-the-requested-run`
  (11 F) looks like the SAME mechanism and is reported rather than forced: the
  HTML render carries the turn header and "Evaluations 2", while the AI render
  answers `""`. That is a whole history omitted, not a missing fixture row.
- `seon.schema-usage-guard-test` (~20) — the registry-preservation lane.
- `seon.cluster.agent-identity-test`, `seon.context-selection-test` assertion
  failures — the agent-identity/dials triage. Their REFUSED writes were fixed
  here; their assertions were left alone.

### Verification boundary, third pass

**The canonical fixture base cannot be constructed on `default` (pid 74930).**
Two daemon-thread attempts, per the BASE CONSTRUCTION RULE, both answered:

```
:seon.test-support/database-base-unavailable
"Canonical fixture base construction failed: Schema declaration resolution
 requires the projection handed to the operation."
```

`retrying-base` leaves the delay unrealized rather than caching the throwable,
so it is NOT poisoned — but no lane can run an in-process test on this JVM
until it constructs. `src/seon/db.clj`, `src/seon/test.clj` and
`src/seon/test/runner.clj` carried a peer lane's uncommitted edits at the time
(their note:
`bounded-write-retry-and-registry-preservation-2026-09-17.md`), which is the
likeliest source; this lane did not touch them and did not attempt a repair.

**Everything in `4d181533d`, `55a0abae0` and `d7e5a0268` is therefore
edit-verified and lint-clean but COLD-GATE-UNPROVEN.**

## Fourth pass: batch 75 — the authored turn, and two attributions

Batch 75: 184 F / 7 E over the pass-3 namespaces, 154 of them
`concurrency-independence` (blocked on the history-cut ruling, untouched).

### The root cause under most of the turn reds: an AUTHORED turn is not an open turn

`ae0e54841` moved the agent→turn edge onto a RUNTIME COMPONENT. The agent's
open turn is read through it:

```clojure
;; src/seon/turn.clj:330, seon.turn/open-for-agent
[?runtime :seon.runtime/agent ?agent]
[?runtime :seon.runtime/turns ?turn]
(not [?turn :seon.turn/closed-tx])
```

Only `open-call` writes that edge. Two derivation tables authored
`{:seon.turn/id … :seon.turn/agent …}` rows directly, so `agent-run` answered
nil and EVERY row derived `:open` or idle no matter what else it built. Both
now open through `seon.turn/open-tx`, with the planned / closed / completed
markers as datoms on the opened turn.

| test | before | after (in process) |
|---|---|---|
| `seon.turn-work-test/the-derivation-is-total-over-every-state` | 16 / 6 / 0 | **25 / 0 / 0** |
| `seon.turn-work-test/a-generated-run-resumes-then-requests-one-more-form` | 0 / 3 / 0 | **3 / 0 / 0** |
| `seon.turn-work-test/comment-only-input-is-recorded-but-never-becomes-eval-work` | 1 / 2 / 0 | **3 / 0 / 0** |
| `seon.turn-loop-test/kill-positions-per-agent-test` | 1 / 5 / 0 | **6 / 0 / 0** |

Two expectations followed the writer's own rulings rather than the reverse:

- **"claimed, no plan, custody died" expected `nil`** because a turn used to
  be able to carry no situation. `open-call` writes
  `:seon.turn.work/situation :call` at open (`src/seon/turn.clj:377`), so that
  state is unproducible — and `seon.turn-work-test`'s totality table has said
  `:call` for the same state all along. The stale table followed the live one.
- **Idle after a closed run needs the DISPOSITION.** A closed provider reply
  CONTINUES unless its turn carries one (`src/seon/turn.clj:2845`); the
  disposition is the fact `my.turn/complete` leaves
  (`src/seon/turn.clj:3512`). Both tables now record it, which is what the
  rows meant by "idle".
- **`triggers-come-back-oldest-first`** bound an arrival instant per message
  and expected the order to follow it. The instant was never written —
  clj-kondo had been reporting the binding unused — and there is no arrival
  attribute: `unanswered-triggers` sorts by `(juxt :seon.wake/t :db/id)`
  because answeredness is decided in exactly one place, by `:t`
  (`src/seon/turn.clj:3003`, turn PRD §14). 1 / 0 / 0 after.

Commits: `04a364e93`, `4b0e21c16`, `583b43d5b`, `132e0ae73`.

### The two ERRORS, attributed (reported, not forced)

**1. `seon.render.walk/ordered-episode refused … [:seon.repl/candidates N
:seon.repl/subject]: expected an integer, got a symbol`.** The PRODUCER is the
stale side, and it is not the vocabulary. `seon.bootstrap/namespace-subject`
(`src/seon/bootstrap.clj:219`) takes a valid lookup `[:seon.ns/name my.foo]`
and returns its VALUE — a bare symbol — then assigns it to
`:seon.repl/subject`, which is contracted as `:seon.render.walk/lookup`
(`:int`, `:keyword`, `:string`, `:uuid`, or `[qualified-keyword value]`;
`resources/seon/schemas/seon.render.walk.edn:18`). A bare symbol is not an
entity lookup, and the neighbouring producer
(`usage-demonstration-candidates`, `:432`) passes `subject-lookup` correctly.
Owning commit: `91f536c36` "Derive opening episode from symbol frontier" —
the frontier is symbols, but the SUBJECT is still the entity the candidate is
about. This is not the result-handle question: the ruled `result/e<id>` symbol
handle lives on `:seon.repl/handle`, not here.

**2. "The capability request does not satisfy its owner contract" — a
platform defect, measured.** `seon.effect/accepts-request?`
(`src/seon/effect.clj:178`) asks
`(schema/function-accepts-in? projection owner-sym [request])`, and that
function validates the COMPLETE declared input contract (its own docstring,
`src/seon/schema.clj:3093`). Every capability owner takes TWO arguments —
the request and the effective config the executor hands it. Live on pid 74930:

```clojure
{:one-arg false   ; seon.web.jvm/fetch with [request]
 :two-arg true    ; seon.web.jvm/fetch with [request effective]
 :fs-one  false}  ; seon.fs.jvm/read with [request]
```

So the door refuses EVERY declared capability. It surfaced only because the
sweep made `seon.web.jvm-test`'s seed honest, so the door was reached for the
first time — the named class from the other side: a check that had never run
reads as health. Issue filed:
[the-effect-door-validates-a-one-argument-request-against-a-two-argument-owner](../../../seon/issues/the-effect-door-validates-a-one-argument-request-against-a-two-argument-owner.md).
Introduced with `0e15593aa`. Not repaired here — the effect owner's slice.

### Per-namespace verdict, pass 4

| namespace | batch 75 | now |
|---|---|---|
| `seon.turn-work-test` | 13 F | 3 tests green in process; `situation-totality-property` 1 F remains (same continuation/disposition family: the generator closes runs without recording one) |
| `seon.turn-loop-test` | 11 F | `kill-positions` 6/0/0 green. Untouched: `attempt-settlement-updates-the-registered-model-gauges` 3 F (`ai/model-row` answers nil — the gauges are never written), `prompt-and-call-resolve-once` 2 F + 1 E (the overlay the fixture applies is not the one the call reads: it got the shipped `deepseek-*` models), `a-clean-last-form…` 1 F |
| `seon.cluster.problem-routing-test` | 2 F | untouched (form-state derivation) |
| `seon.render.web-context-test` | 2 F | untouched — the bystander fix did not settle it; ANY commit now re-walks (`@walks` 2 vs 1 AND 3 vs 2), which is a question for the acquisition owner, not the seed |
| `seon.render.web-test` | 1 F | untouched (same shape: discovery/invocation counts move) |
| `seon.bootstrap-test` | 1 F + 1 E | `intent-membership` delta untouched; the ERROR is attribution 1 above |
| `seon.web.jvm-test` | 2 E | attribution 2 above — a platform defect, not a fixture |
| `seon.concurrency-independence-test` | 154 F | blocked on the history-cut ruling |

### Verification boundary, fourth pass

- In process on `default`, one test at a time, across pids 74930 and 88182
  (the store was reset mid-pass; the base was reconstructed per the rule on
  the new pid before the first run).
- One run answered `"Cannot delete a branch with an active connection"` and
  was re-run clean — a fixture branch-lease race in the shared JVM, named
  rather than hidden.
- `src/seon/fn.clj`, `test/seon/fn_test.clj` and `test/seon/program_test.clj`
  carried a peer lane's uncommitted edits at the end of this pass; none was
  touched or committed here.
