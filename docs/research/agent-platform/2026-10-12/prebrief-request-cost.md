---
title: "Pre-brief: the costs left on the eval request path (fresh custody, recording, defn test step)"
type: research
status: prebrief, 2026-10-12. Read-only research; no source, test or resource was edited. Doc only.
basis: default at f7c730911 (cold). Figures are the owner's measurements from today plus the stored notes named below; not re-measured here.
plan: docs/research/agent-platform/2026-10-12/cost-fresh.md, cost-record.md, cost-record2.md, cost-acquire.md, sentinel-baseline.md
---

# Pre-brief: the costs left on the eval request path

Symbols are fully qualified. Callers were checked with `rg` over `src`, `test`, `script` and `resources`. Stored test evidence was read on default (jvm mode, root).

## Measured today (owner, default f7c730911, cold)

| Probe | Total | Stages |
|---|---:|---|
| First request after boot | 303 ms | custody 144, recording transaction 65, test step 21 |
| Warm request | 60 ms | recording 33 |
| Fresh agent | 239 ms | custody 128, recording 49, test step 22 |
| First `defn` | 480 ms | |
| Second `defn` | 239 ms | |

The earlier sentinel (`sentinel-baseline.md`, e9bcf36d2) recorded `defn` at 379 ms with recording 137 and test step 80. Its warm probe was 38.6 ms and its fresh probe 205 to 215 ms. These are the same owners, measured on an older commit.

## (a) Fresh agent `:custody`

### Path (verified at HEAD)

`seon.mcp/execution` (private) does three durable things for an unknown agent id, in order:

1. `seon.cluster.host/register!`: the cluster-side admission, one checked transaction (`seon.cluster.host/home-call` decides it at the writer).
2. `my.agent/create-branch!` → `seon.cluster.registry/fork!`: `seon.cluster.registry/branch!` for the work branch, `seon.cluster.registry/branch-fork-entity` written as a checked transaction on the new branch, then `seon.cluster.registry/branch!` again for the retained base (`base-branch`).
3. `seon.cluster.host/host!` → `seon.cluster.host/start!`: `seon.cluster.host/register!` on the branch connection (a second admission transaction), then `seon.cluster.agent/start-branch-flow!`.

The recording transaction follows. Total fresh checked transactions today: four, counting the recording. `cost-fresh.md` says the registration-before-fork change (committed, `d099a4cfc`) removes one and the empty admission write on rehosting; its own measurement shows median `:custody` 129.35 → 90.60 ms, complete request 209 → 156 ms, on the operator's template store.

### Symbols this work changes or calls

| Symbol | Callers (non-doc) |
|---|---|
| `seon.mcp/execution` | `seon.mcp/eval-clj` (custody stage); tests `seon.mcp-entrance-test` (via the entrance) |
| `my.agent/create-branch!` | `seon.mcp/execution`; `test/my/agent_test`; `seon.batch` (docstring only) |
| `seon.cluster.registry/fork!` | `my.agent/create-branch!`; `test/my/program_test`, `test/seon/cluster/merge_test`, `script/start_flow_benchmark`, `src/my/agent` |
| `seon.cluster.registry/branch!` | `seon.cluster.registry/fork!`, `seon.cluster.registry/ensure-cluster!`, `script/seon/cost_fresh`, `src/seon/eval/drive`, and about 30 test references (`acquisition_test`, `host_test`, `branch_evidence_test`, `squash_test`) |
| `seon.cluster.registry/branch-fork-entity` | `seon.cluster.registry/fork!` only |
| `seon.cluster.host/register!` | `seon.mcp/execution`, `seon.cluster.host/start!`; `host_test`. (The bare name `register!` matches about 140 unrelated sites; the owner is `seon.cluster.host/register!`.) |
| `seon.cluster.host/home-call` | `seon.cluster.host/register!` (as the `db.fn/call` target) |
| `seon.cluster.host/host!` | `seon.mcp/execution`; `host_test`, `seon.cluster.agent` task file |
| `seon.cluster.agent/start-branch-flow!` | `seon.cluster.host/start!` |

Retained-base readers (must stay correct if the base is ever removed): `seon.cluster.source/merge-base`, `seon.turn/fork-commit`, the nested `seon.cluster.registry/fork!` call, and collection. Deleting the base alone loses bases older than the collection window. The hybrid assigns that to M17 (GC roots from open assignments), after M13 and M6.

### Tests reaching these symbols and their state

- `seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply` (task in `resources/seon/tasks/seon.mcp.edn`). Its named proof `seon.mcp-test/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply` **does not exist** (there is no `test/seon/mcp_test.clj`; the entrance test is `test/seon/mcp_entrance_test.clj`). The tagged deftests are `seon.datahike-fork-test/a-branch-serializes-only-its-commit-pointer` (`test/seon/datahike_fork_test.clj:26`, a serialization probe) and `seon.test-reaching-test/a-branchs-change-is-what-it-changed-since-its-fork` (`test/seon/test_reaching_test.clj:658`). Neither is the custody-bound proof the task asks for.
- `seon.cluster.agent/a-fresh-agents-environment-costs-one-fork` has a tagged deftest (`test/seon/cluster/agent_test.clj:2132`); it covers the SCI fork part.
- Stored runs on default: no failing member with the name `*request-cost*`, `*datahike-fork*`, `*mcp-entrance*` or `*owned-value*`. Absence of a stored member is unknown, not green. Known reds from the cost lanes that the stored runs do not show: `seon.request-cost-test/a-jvm-reply-carries-its-request-cost` (0.0 MB calling-thread allocation, red on committed baseline `50f51c94f` too, `cost-acquire.md`), and three `seon.cluster.host-test` / `seon.mcp-entrance-test` reds recorded in `cost-fresh.md`.

### Prior attempts (custody)

| Attempt | Result | Verdict |
|---|---|---|
| Pointer branch: serialize only the commit pointer on `datahike.versioning/branch!` (`cost-acquire.md`) | Encoding 0.70 ms / 611 KB / 38,782 bytes → 0.04 ms / 6 KB / 55 bytes, isolated. Installed root restart: fresh pair 299 ms against 330 ms earlier; `:custody` 169.5 and 181.4 ms | Kept (pointer representation). Not an acquisition win by itself. |
| Schema derivation reuse: `seon.schema/projection-cache-value` in `seon.schema/canonical-database-attributes` (`cost-acquire.md`) | 12.22 ms and 18.7 MB → 0.00 ms and 760 bytes on a held value, a composition probe | Kept. Installed timing still open. |
| Admission before fork (`cost-fresh.md`, commit `d099a4cfc`) | Median complete 209 → 156 ms; `:custody` 129 → 91 ms; checked transactions 4 → 3 | Kept. Remaining target unmet. |
| Concurrent fresh pair, cold JFR (`cost-record2.md`, three fresh agents) | Checked transactions 340 ms self, branch operations 124 ms, validation 27 ms, SCI forks 21 ms | Diagnosis. The writer's first-transaction poll was refuted (first entry 0.7–3.4 ms, `cost-record2.md`). Do not repeat it. |
| `seon.test/digest-differences` reuse via `seon.db/content-revisions` (`cost-record2.md`) | Unchanged branch: 11.92 ms and 27 MB, two pulls → 0.05 ms and 3.9 KB, zero pulls, a composition probe. Changed declarations still take the full comparison (5 ms left). | Kept. Measured on a retained branch; no installed default timing. |

Rejected or unmeasured candidates to avoid repeating: do not delete the retained base before M13 and M17 (`cost-fresh.md`); do not skip the fork transaction alone (its entity is read by lifecycle consumers, `cost-record2.md`); do not add a process-wide cache for admissions.

### Ranked remaining owners, custody (estimated ms saved on a fresh first request; evidence in parentheses)

1. The second durable fork transaction and the second `registry/branch!` for the retained base: about 40 to 60 ms of a 128 ms custody (`cost-record2.md`: fork 21 ms SCI plus branch 124 ms across three requests, roughly 40 ms per request). Waits for M13 and M17.
2. The branch-side admission transaction in `seon.cluster.host/start!`: one checked transaction, about 20 to 30 ms (`cost-fresh.md` says it is the one removed by the admission ordering; the remaining branch write is the second admission). Needs the branch admission to be composed with `seon.cluster.host/home-call`, not suppressed.
3. `seon.cluster.registry/branch-fork-entity` projection and `schema/handed-projection` derivation on the new branch: a few ms (`cost-record2.md` per-request branch cost). Measure before changing.

## (b) The recording transaction

### Path (verified at HEAD)

`seon.turn/settle-batch!` builds one transaction: `seon.turn.batch/program-facts-call` (derived at the writer), receipts, evaluation terminal data, `close-folded-call`, and `seon.fault/folded-tx`. It is committed by `seon.db/transact!` → private `seon.db/transact-call`. Inside `transact-call`: `write-error` → `stamp-transaction` → `prepared-write` (`seon.schema.datahike/encode-transaction-in`, `vectors-tx`, `namespace-digests-tx`) → `d/transact!` (or `d/merge-db!` with parents) → deref under the write time limit → `charge-writer!` → `hold-committed-projection!`.

`seon.db/write-owned-values-error` (private) runs inside `write-report-error` as the Datahike report validator (`:datahike/validate-report` in `prepared-write`). It therefore runs on the writer thread, inside the commit window, not on the caller. Its task `seon.db/the-checked-writer-is-slow-on-a-fresh-branch` is tagged on `seon.request-cost-test` line 87 (a deftest), so it has a proof.

Konserve/Datahike write path: `datahike.index.persistent-set/datom-write-handler` (Fressian datom tag and list), `konserve.compressor/Lz4Compressor` (64 KB LZ4 frames, `LZ4FrameOutputStream` with fresh block buffers per node). The task `seon.cluster.store/each-commit-serializes-hundreds-of-times-the-bytes-it-writes` is tagged on `test/seon/datahike_fork_test.clj:57` (a deftest).

### Symbols this work changes or calls

| Symbol | Callers |
|---|---|
| `seon.db/transact-call` (private) | `seon.db/transact!` only; tests reach it through `#'` |
| `seon.db/write-owned-values-error` (private) | `seon.db/write-report-error` (inside a `delay`); `test/seon/owned_value_test` (through `#'`); `test/seon/data_shapes_test` (comment); `script/seon/cost_acquire` |
| `seon.db/prepared-write` | `seon.db/transact-call` and `seon.db/with` |
| `seon.db/hold-committed-projection!` | `seon.db/transact-call` |
| `seon.turn/system-run-plan` | `seon.turn/system-run-call` → `seon.turn/system-run-tx` |
| `seon.turn/system-run-call`, `seon.turn/system-run-tx` | `seon.cluster.agent` (the run), `test/seon/turn_test`, `test/seon/cluster/host_test`, `test/seon/data_shapes_test`, `test/seon/concurrency_independence_test`, `resources/seon/schemas/seon.agent.edn` |
| `seon.turn/settle-batch!` | `seon.turn/settle-evaluated!`; `seon.turn.batch`; `test/seon/turn_loop_test`, `turn_disposition_test`, `host_test` |
| Fressian datom writer `datahike.index.persistent-set/datom-write-handler` | Datahike internal; pinned dependency. Changing it needs a dependency seam. |

### Tests and tasks

- `seon.request-cost-test/the-checked-writer-is-slow-on-a-fresh-branch` (line 87) is the proof for the writer's validation task.
- `seon.datahike-fork-test/each-commit-serializes-hundreds-of-times-the-bytes-it-writes` (line 57) is the proof for the serialization task, and it encodes the Fressian count and allocation. It is red on HEAD by the stored notes (cost-record.md: 1,284,656 bytes against 1 MB). Stored runs show no member row, so state is unknown on default.
- `seon.turn/the-test-step-writes-nothing-on-main` (`test/seon/turn_test.clj:2081`, `test/seon/context_test.clj:299`) is the proof for the test-step write-set task.
- Callers `seon.turn/system-run-call` count proof: `cost-acquire.md` reports a warm request calls it twice (namespace reference before its declaration) and the change moves the declarations first. Its one-call proof passes in candidate `5f4be93f`; the request-cost assertion (`a-jvm-reply-carries-its-request-cost`) is red for reasons unrelated to it.

### Prior attempts (recording), with measured results

| Attempt | Result | Verdict |
|---|---|---|
| Fressian `Writer`/`StreamingWriter` datom write without intermediate list (`cost-record.md`, Datahike `ec907fb5` pinned in the fork) | Candidate `eb8df0418`: recording 72.4 → 48.5 ms, commit allocation 7.36 → 5.87 MB (matched probes; startup/test overlap, not isolated causal) | Kept (`eb8df0418`). The 15–20 ms recording target is not met. |
| Query derivation: `seon.db/query-patterns` and `seon.db/query-variable-attributes` once per query (`cost-record.md`) | Deterministic count 7 → 1 and 2 → 1; warm request 153 → 176 ms, 8.9 MB caller allocation (observed window, not causal) | Kept (count proof). Wall time not shown to improve. |
| Tempid replay: namespace declarations before their runs in `seon.turn/system-run-plan` (`cost-record.md`, `cost-acquire.md`) | Retries 1 → 0; immutable `datahike.api/with` 0.33 ms / 577 KB → 0.25 ms / 379 KB (13 datoms each) | Kept (patch in `cost-record-turn.patch`, protected owner). Installed end-to-end not measured. |
| Configuration once per step (`cost-record.md`, patch) | Warm request 83.73 ms with 12 effective-configuration reads, six `seon.turn/agent-dial` calls; patch not loaded | Unavailable. Not accepted. |
| Cost selection `seon.profile/selected` (`cost-record.md`) | 700 below-floor definitions: 3.62 ms and 6.7 MB → zero construction | Kept. Not a recording cost. |
| LZ4 buffer reuse / a smaller frame size (`cost-record.md`, `cost-gb.md`) | Installed LZ4 frame API has 64 KB minimum blocks and no reset/reuse seam. Public API cannot reuse buffers. | Rejected for now. Needs a dependency seam or a separately named encoding with its own compatibility proof. Do not edit the konserve pin to try it. |
| f18ab95e3, Fressian per-stream attribute cache (`cost-record2.md`) | Count proof 1000 → 1 green; quiet candidate warm recording 21.6–28.2 → 24.0–34.4 ms, overall 33.9–42.1 → 38.2–58.0 ms (regression observed). Reverted by `c2284b109`. | Rejected. Encoder call count was a proxy, not the cost. Do not repeat without a wall-clock/allocation proof on a matched pair. |
| Owning-value validation changes (`seon.db/write-owned-values-error`) | Averages below 1 ms per request (JFR, `cost-record2.md`); task says 8–9 ms per three root transactions and grows with a runtime's turns | Not yet a target. Measure a settle with a long-lived agent before changing it. |
| Reverting to skip durable commit or fsync | Not attempted. `seon.db/transact!` promises durability before answer; `file-force` is 9.65 ms per commit (`cost-record2.md`). | Forbidden by the one-REPL-path law. |

### Ranked remaining owners, recording (estimated ms; evidence in parentheses)

1. Commit serialization: Fressian datom encoding plus LZ4 frame allocation per node. Largest sampled commit CPU share (JFR: 71 of 107 commit samples include serialization, `cost-record2.md`). Only a dependency-level change is available; blocked by the frame API.
2. Directory force per commit: 9.65 ms per commit (`cost-record2.md`), a floor the code cannot remove without durability changes.
3. Writer queue and `prepared-write`/`stamp-transaction`: small (below 1 ms per request, `cost-record2.md`).
4. Owned-value validation: below 1 ms per request in JFR; more under a long-lived agent (task measurement 10.8 ms at 179 turns).

## (c) The `defn` path: recording and test step

### Path

A `defn` request runs `seon.turn/settle-batch!` (the recording, measured 137 ms in the sentinel) and `seon.turn/test-step` (80 ms in the sentinel). `test-step` calls `seon.test/request-changes` (the changed-program comparison through `seon.test/digest-differences` and `seon.test/changes`), `run-proofs` (the task's proofs, bounded by `:seon.config.test/step-time-limit-ms`), `run-reaching` and `record-broken!`. Costs are summed by `test-costs`.

### Symbols and callers

| Symbol | Callers |
|---|---|
| `seon.test/digest-differences` | `seon.test/changes`, `seon.test/program-overlay-tx` (through the check); `script/seon/cost_record2`, `src/seon/test.clj` |
| `seon.test/changes` | `seon.turn/test-step` (via `@test-request-changes`), `test/seon/test_reaching_test` |
| `seon.test/request-changes` | `seon.turn/test-step`; `test/seon/test_test` (its change-reds case) |
| `seon.turn/test-step` | `seon.turn/settle-evaluated!`-adjacent pass; `test/seon/cluster/turn_test`, `test/seon/node_cache_test` and about 36 references overall |
| `seon.turn/run-reaching`, `seon.turn/run-proofs`, `seon.turn/record-broken!` | `seon.turn/test-step` only |
| `seon.turn/program-facts-call` (`seon.turn.batch`) | `seon.turn/settle-batch!` (one per settlement) |

### Tests and tasks

- `seon.test/a-check-compares-every-definition-digest-of-the-program-twice` (task in `resources/seon/tasks/seon.test.edn:135`): **no tagged deftest**. Red by absence. The cost lane's changed-program case (`cost-record2.md`, 5 ms left in `seon.test/digest-differences`) is not covered by any proof.
- `seon.test/reach-attribute-advances-from-the-recording-report` (task, `seon.test.edn:159`): no tagged deftest.
- `seon.test/a-first-deftest-in-a-new-namespace-costs-seconds-of-test-step` (task, `seon.test.edn:168`): no tagged deftest.
- `seon.test-reaching-test/a-branchs-change-is-what-it-changed-since-its-fork` (test_reaching_test.clj:658) carries the cost-record2 count assertion (two pulls expected zero). Candidate `c7c4af82cff7` passed it. It is tagged to `seon.mcp/…`, not to a `seon.test` task.
- `seon.turn/the-test-step-writes-nothing-on-main` (turn_test.clj:2081): has a tagged deftest; its root-side write rules are in `seon.turn/record-reached-red!`.

### Prior attempts (defn path)

| Attempt | Result | Verdict |
|---|---|---|
| Reuse committed revisions for unchanged declarations (`cost-record2.md`, commit `b2b884b7d`) | Unchanged branch: 11.92 ms and two pulls → 0.05 ms and zero pulls (composition probe). Fresh first request 329.5 → 275.6 ms (not causal; recording and custody unchanged). | Kept. The changed-program comparison is still full (5 ms). |
| Test-step writes nothing to main (`seon.turn.edn` task) | Proof green on its tagged deftest | Kept. Required by the agent-compartment rule. |
| Caching or memoizing `seon.test/digest-differences` across requests | Not attempted. A process-wide cache would violate the one-cache rule and needs content keys. | Not recommended. |
| Skipping the proofs when the agent holds none | Already the rule: `test-step` acquires nothing when no proof is held and nothing changed | Kept. |

### Ranked remaining owners, defn (estimated ms)

1. `settle-batch!` recording (137 ms sentinel, 66.8 ms and 73.2 ms in today's warm/recording samples): the `program-facts-call` derivation at the writer plus encoding. The first `defn` also writes the new program entities. Measure `program-facts-call` alone before choosing.
2. `test-step` (80 ms sentinel, 22 ms in today's fresh request): `seon.test/digest-differences` on a changed program (full comparison, 5 ms) and `run-reaching` on the changed set. No proof exists for the full comparison (see Tests).
3. `seon.turn/terminal-agent-ordinal` (4.4–4.8 M rows scanned on default, `seon.turn.edn` task): reported in the same settle; it is not on the test path but is a settle cost, not yet measured in the defn sample.

## Collisions with running lanes and queued phases

`bin/lane status` at the time of writing: `astra-one-system`, `astra-review-merge-base`, `cache-weights-2`, `error-locals`, `m5`, `root-drift`, `root-report`. Relevant overlaps:

- `src/seon/turn.clj`, `src/seon/test.clj`, `test/seon/turn_test.clj`, `resources/seon/tasks/seon.turn.edn`, `test/seon/request_cost_test.clj` and `resources/seon/schemas/seon.turn.loop.edn`: held by `root-drift` (lane status). The configuration and namespace-order patches in `cost-record-turn.patch` and the test-step changes both need these files. Do not claim until `root-drift` releases.
- `src/seon/db.clj` (`transact-call`, `write-owned-values-error`, `prepared-write`): the owner of the checked writer; `cost-acquire.md` says the database owner was claimed by the rename lane. Check `bin/lane status` before claiming.
- `src/seon/cluster/registry.clj` (`fork!`, `branch-fork-entity`, `branch!`) and `src/seon/cluster/host.clj` (`register!`, `start!`): on the M13 and M17 path (base retention, branch lifecycle). Queued, not claimed.
- `src/seon/mcp.clj` (`execution`): no lane holds it. Changes here reach every fresh request.
- `src/my/agent.clj` (`create-branch!`): retired-verb neighbours in M8 (`request-merge!`); keep its identity.
- Dependency pins (Datahike `c9e57c6f` / fork `ec907fb5`, konserve `e4334756`): any bump needs root's batched restart. `cost-record.md` and `cost-gb.md` both mark RESET NEEDED.

## Riskiest step and how HEAD keeps working

The riskiest step is removing the second fresh-acquisition transaction (the branch-side admission and the fork entity) or the retained-base branch. Each depends on lifecycle consumers: `seon.cluster.source/merge-base`, `seon.turn/fork-commit`, collection and M17's GC roots. Removing the retained base early loses bases older than the collection window, so a merge base vanishes. The step keeps HEAD working only if the fork entity and base remain until M13 and M17 land, and each removal lands with its consumers converted in the same commit, proved by `seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection`. The second riskiest step is the LZ4 change: it needs a dependency seam, and a Datahike or konserve pin bump affects every boot.

## Three biggest risks

1. Removing the fork or base transaction before M13 and M17 deletes merge bases; the proof for that is absent.
2. The recording target (15–20 ms) is not met, and two cheap-looking changes (Fressian attribute cache, query derivation) either regressed on the wall clock or changed nothing causal. Measure matched pairs before accepting any count.
3. The custody and defn tasks name proofs that do not exist (`seon.mcp-test/…` does not exist; `seon.test/a-check-compares-every-definition-digest-of-the-program-twice` has no deftest), so the next Astra lane could report "done" on a green test that proves nothing.
