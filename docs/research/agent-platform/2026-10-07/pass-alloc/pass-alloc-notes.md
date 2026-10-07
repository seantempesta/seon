---
type: notes
status: active
created: 2026-10-07
lane: pass-alloc
---

# pass-alloc: where an ordinary pass's bytes go (lane pass-alloc, 2026-10-07)

Tasks: `seon.turn/a-trivial-pass-allocates-a-hundred-megabytes` (bf9186de3b28),
`seon.sci.eval/acquisition-rebuilds-the-whole-program` (4a738d4f7138). Spec budgets: `docs/seon/specs/agent-loop.md` §3.

## Broken things first

- **Nothing is under budget.** After both fixes below, on a small probe store: `(+ 1 2)` 82-100 ms / 67-79 MB
  (budget 200 ms / 5 MB), a new one-line `defn` 197-287 ms / 175-249 MB (budget 300 ms / 30 MB), the pass after it
  89-102 ms / 70-82 MB (budget 150 ms / 10 MB).
- **Per-pass cost grows with the agent's own history: about 0.2 MB per earlier run.** Same agent, fork loaded:
  67-75 MB at run 1, 88-91 MB after 105 runs, 107-121 MB after 210 runs. Growth by owner (JFR, run ~215 against a
  fresh agent, per pass): `seon.turn/opening-deferred?` +6.5 MB, `spent-runs` +6.1, `continuing-reply?` +6.1,
  `previous-ending-namespace` +4.0, `seon.repl/entity-emission` +3.4, `fault-window` +2.8,
  `seon.render.transcript/branch-errors` +2.7, `regenerate-agent-context!` +2.1, `evaluation-terminal-data` +2.0,
  `run-receipts` +1.9, `run-observation` +1.9, `open-for-agent` +1.5. Every one is a query over all of the agent's
  runs or evaluations. Existing task `seon.turn/a-turn-pass-derives-its-work-twice` (41b9185ccf23); the slices that
  delete them are listed below. On default, whose agents have hundreds to thousands of runs, this is why the live
  numbers (125-176 ms / 142-194 MB) exceed the probe's.
- **Default is `:faulted`**: the one open fault is the cross-branch settlement fault
  `seon.turn.batch/an-entrant-inside-another-custody-cannot-record` (3d2fb1b41dc8), fixed by defn-cost and awaiting
  root's restart.
- **A Datahike join whose first group answers nothing still scans the next group's whole attribute**: 17.5 MB and
  10 ms for 0 rows in every defn pass (`seon.db/report-arity-comparison`'s bounds query asked for `clojure.core/inc`).
  Present at the pin and at fork 2ff3bd4d. New task `seon.db/a-join-after-an-empty-group-scans-the-next-groups-attribute`
  (8400d2e82410); the fix is in the fork's executor (4,700-line `execute.cljc`), not in reach in 15 minutes.

## Method

Throwaway operator root (`bin/seon --root <scratchpad>/pass-alloc/root start probe`, HEAD 18a68b248, pid 25479)
started with `JAVA_TOOL_OPTIONS=-XX:FlightRecorderOptions:stackdepth=512` (the log confirms it was picked up; stacks
up to 485 frames, none truncated). Driven over its prepl. Each pass is `seon.mcp/call` `eval_clj` (SCI) for an agent
id on cluster `probe`. Allocation: `getTotalThreadAllocatedBytes` around each call, plus `getThreadAllocatedBytes`
per thread; attribution: an in-JVM `jdk.jfr.Recording` of `jdk.ObjectAllocationSample` (throttle 20,000/s) over 5-10
passes, parsed with `jdk.jfr.consumer.RecordingFile` and grouped by the innermost Seon function of each stack (or
the Datahike/konserve/hasch frames when none). JFR inflates totals by about 10%. The probe store is small (5,663
functions, few runs per branch), so history-proportional costs are smaller than on default. Scripts: scratchpad
`pass-alloc/pa.clj` and the `*.clj` probes beside it (not committed: disposable).

Datahike variants were loaded into the same JVM with `load-file` of the seven query namespaces
(`analyze estimate logical plan lower execute query`) and `writing.cljc`, extracted with `git show <rev>:path`.
Each row of the table below is a new agent and new definition names, run twice.

## 1. Attribution at HEAD (pin d9121bbb), per pass

Threads: the agent's turn proc runs on a ForkJoin worker, the Datahike writer on `async-mixed`, the MCP bridge on the
prepl connection thread. `(+ 1 2)`, steady, 115-125 MB: ForkJoin ~73 MB, async-mixed ~35 MB, MCP thread ~10 MB.

| stage / call path | MB per `(+ 1 2)` | proportional to |
|---|---|---|
| analyze: `turn.batch/analyze-form` → `seon.fn/runtime-analysis-batch` → `db/q` (namespace functions: `[?n :seon.ns/name ?name] [?f :seon.fn/ns ?n] [?f :seon.fn/sym _]`) | 36.7 | every program function (5,663), once per fresh database value (Datahike's result cache hides it on repeats: 37 MB first, 0.3 MB on the same value) |
| analyze: clj-kondo (`analyzer/invoke-kondo`) | 3.7-4.1 | the form plus the namespace prelude |
| settle (writer thread): `datahike.writing/db->stored` → `hasch` uuid of the schema-meta | 13-17 | the whole schema (2,202 attributes), every commit |
| settle (writer thread): index node serialization (fressian, LZ4) | ~6 | nodes written |
| settle: `seon.db/validate-write-report` → `write-owned-values-error` | 2.4 | the pass's owned values |
| acquire: `fork-for-turn` → `regenerate-agent-context!` + `base-bindings` | 5.5 | every program binding, every turn |
| eval/show: `seon.render/viewer` → `seon.sci.kernel/public-functions-in` | 2.3 | every program function, per shown value |
| analyze: `host-bound-callers` → `interpreted-namespaces` → `base-context-injected-symbols` | 2.3 | all 3,394 projection forms |
| turn work derivation: `open-for-agent`, `fault-window`, `continuing-reply?`, `spent-runs`, `latest-answering-turn-t`, `previous-ending-namespace`, `next-ordinal`, `evaluation-terminal-data`, `opening-deferred?`, `system-run-plan`, `agent-dial`, `max-episode-runs` | 1-3 each, ~25 total | the agent's runs (identity-join scans plus history reads) |
| reply (MCP thread): `seon.cluster.agent/run-observation` (`history-entries` + `run-receipts`) | 9.3 | the agent's runs |
| `seon.schema/call-with-projection` (write validation) and `changed-candidate-keys` | 3.5 | the write, projection |

Fixed per-query overhead (`seon.db/q` encode, lowering, `may-read-vectors?`, `query-variable-attributes`,
`native-query-result?`): 0.3-0.6 MB each, about 47 queries per pass (growth notes §1).

One-line `defn` at HEAD, 230-292 MB (new names), additionally:

| call path | MB per defn | proportional to |
|---|---|---|
| projection: `call-with-projection`, `projection-from-entities`, `projection-entities`, `projection-admissions`, `declaration-content-key` (×3: speculative, content, validate) | 45-70 | the program's declarations; existing tasks `seon.schema/a-declaration-write-reloads-the-whole-projection`, `seon.schema/a-function-contract-changes-the-schema-projection` |
| `report-arity-comparison` bounds query for `clojure.core/inc` | 17.9 | the `:seon.fn.arity/min` attribute (task 8400d2e82410) |
| `sci.eval/installation-covers-program-change?` | 5.6-12 | program (identity joins) |
| `seon.fn/identity-names`, `declared-callers`, `sci.eval/committed-row?`, `turn/current-run`, `close-folded-call` | 6-8 each | identity joins over the program / runs |
| writer thread, Datahike commit (hasch + nodes) | ~36 | schema + nodes |

Machinery transactions: one per pass (the agent branch's `max-tx` advanced by exactly 1 for a `(+ 1 2)` and for a
`defn`).

## 2. The Datahike fork (planner fix 2ff3bd4d), same probe

| Datahike in the probe JVM | first call (new agent) | `(+ 1 2)` | new `defn` | pass after |
|---|---|---|---|---|
| pin d9121bbb | 342 ms / 295 MB | 113-133 ms / 115-126 MB | 227-366 ms / 229-305 MB | 118-136 ms / 121-129 MB |
| fork 2ff3bd4d | 293-298 ms / 257-260 MB | 94-106 ms / 83-92 MB | 217-376 ms / 193-263 MB | 87-115 ms / 88-97 MB |
| fork + schema-meta key (e0aaa450, below) | 255-265 ms / 204-219 MB | 82-100 ms / 67-79 MB | 197-287 ms / 175-249 MB | 89-102 ms / 70-82 MB |

The planner fix removes ~33 MB per trivial pass here, almost all of it the analysis query (37 MB → 0.4 MB on a fresh
value; `user` namespace, 0 rows; `seon.turn`, 194 rows: 44 MB → 8.6 MB, which is now the pull itself). On default the
identity-join reads scale with branch history (growth notes: 19,004 → 22 node visits for `run-receipts`), so the
saving there is larger than measured here. Not removed: the empty-group scan (task 8400d2e82410) and every
history-proportional query whose answer itself grows (spent-runs and friends).

## 3. Fix: a commit that changes no schema reuses its schema-meta key (Datahike fork, e0aaa450)

Producer: `datahike.writing/db->stored` computed `(hasch/uuid schema-meta)` over the whole schema, rschema and ident
maps on every commit, to name the schema-meta record. Measured on the probe's 2,202-attribute schema: 17.0 MB and
6.5-10.6 ms per commit (the schema alone 10.6 MB). A data commit carries the identical schema maps (checked on an
agent branch across a `(+ 1 2)` pass and a `defn` pass: all five maps `identical?`), and branches opened from the same
stored schema share them through Datahike's schema cache.

Change (EQUIVALENT: the key is a pure function of the maps' values, reused only when every map is `identical?` to
the last keyed one): `schema-meta-content-key` keeps the last keyed schema-meta and its key in one atom of
`datahike.writing` and hashes only on a change. A different schema (another branch with its own schema, a schema
transaction) hashes as before and replaces the slot.

Before/after on the same probe (fork loaded, fresh agent): `(+ 1 2)` 83-92 → 67-79 MB, defn 193-263 → 175-249 MB,
first call 257-260 → 204-219 MB; JFR shows no `hasch` frame left in a pass.

Regression: `datahike.test.schema-meta-key-test/a-commit-that-changes-no-schema-hashes-no-schema` (1,000-attribute
memory db): the stored key equals the content uuid before and after a schema change, a data commit keeps the
identical schema, and keying the unchanged schema allocates under a tenth of hashing it. Red with the parent's
`writing.cljc` (allocated 130,957,200 B against 13,095,360 B for one hash) and green after, in the probe JVM.
Fork suites green with the change: `schema-meta-key-test commit-graph-test writer-error-test audit-verify-test
schema-test api-test` 71 tests / 480 assertions, one error that is the classpath's missing hitchhiker-tree backend
(`test-metrics-hht`: no `-flush` for `hitchhiker.tree.DataNode`, the same classpath also cannot load `store-test`);
`transact-test versioning-test` 25 / 181 green.

Committed on the fork branch `seon/identity-join-first` as e0aaa450 (on 2ff3bd4d). **Seon's gitlink is not bumped**:
the bump is root's, together with the reviewed planner range.

Which step of the one loop it serves: record (every settlement commit). Nothing is added beside the dependency's own
key computation; it does less.

## 4. What remains, by slice

| remaining per pass (after both fixes) | MB | slice / task |
|---|---|---|
| run-history reads in work derivation and budgets: `opening-deferred?`, `spent-runs`, `continuing-reply?`, `max-episode-runs`, `latest-answering-turn-t`, `next-agent-work` twice | ~15 at run 1, +0.2 per earlier run | **S6** (§4.5 work rules; delete the run machinery and episodes); task 41b9185ccf23 |
| `next-ordinal` | 1.5 | **S3** (writer-assigned ordinals) |
| `previous-ending-namespace` (max over every evaluation of the agent) | 2.0, grows | **S4** (the proc-held transcript and session namespace) |
| `fault-window` | 2.3, grows | **S5** (fault occurrences the pass observed) |
| `run-observation` + `run-receipts` + `branch-errors` + `selected-run-entity-ids` on the MCP thread | 5-10, grows | **S5** (replies from the held pass) |
| `seon.repl/entity-emission`, `evaluation-terminal-data`, `close-call`, `open-for-agent`, `resume-turn` | 1.4-3.7 each | **S5/S6** (one settlement owner, one machinery transaction) |
| analysis of a form that defines nothing: kondo 4.1 + injected symbols 2.3 | 6.4 | **S5** (§4.1 ANALYZE skipped when nothing was defined); task `seon.fn/a-form-that-defines-nothing-is-analyzed-against-the-whole-program` (70c0ac1f942f) |
| `regenerate-agent-context!` + `base-bindings` | 5.2 | **S4** (per-turn context acquisition); task `seon.sci.eval/a-turn-snapshots-every-program-binding` (2527a56ca9fe) |
| `seon.render/viewer` → `public-functions-in` | 2.3 | render owner; task `seon.render/the-viewer-scans-every-program-function` (f55702031f83) |
| `write-owned-values-error`, `call-with-projection`, `changed-candidate-keys` | 3-4 | **S5** (recording preparation) |
| writer: index node serialization | ~2-6 | store bytes, accepted (~150 KB per small commit) |
| defn: projection derivations (3 per declaration write) | 45-70 | schema owner, existing tasks named in §1 |
| defn: empty-group scan | 17.9 | Datahike executor, task 8400d2e82410 |
| defn: `installation-covers-program-change?`, `identity-names`, `declared-callers`, `committed-row?`, `current-run` | 6-12 each | 4a738d4f7138 (incremental acquisition) and the fork bump for the identity joins |

## Design check

- Held: the schema-meta key belongs to its producer, `db->stored`; one slot keyed by identity reuses the value the
  dependency already computed, with no Seon-side cache. Awkward: one slot thrashes when two branches with different
  schemas commit alternately; they then cost what they cost before.
- Not done, with reason: the caller rewrite of `runtime-analysis-batch`'s query to a lookup-ref input (37 → 0.3 MB
  measured) is the workaround root declined in favour of the fork; the fork already gives the same result.
  `base-bindings` reuse by environment identity looked EQUIVALENT but `regenerate-agent-context!` also restores
  unmapped base Vars and merges non-Var entries; changing it is the eval.clj owner's semantic work (S4).
- The probe's numbers understate default's history-proportional costs; the growth measurement is the evidence for
  that class.

## Verification limits

- Measured in a throwaway JVM with Datahike namespaces reloaded in place, not in a JVM booted from a bumped gitlink.
- Armed call counts per pass were not collected (JFR and allocation only).
- Default was not touched beyond read-only status queries and task creation; no restart or reset needed for this
  lane's change (it reaches default only through root's gitlink bump).
