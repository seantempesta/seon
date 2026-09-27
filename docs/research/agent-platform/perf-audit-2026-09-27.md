---
type: audit
status: diagnosis only; source unchanged; task entries for root to add
---

# Performance audit, 2026-09-27 (lane perf-audit-opus)

This audit is diagnosis only. It changes no source. The evidence is the profile of default pid 66629, which was up
from 17:36Z to 18:00Z (snapshot `tmp/perf-audit/profile-pid66629.edn`, thread dump
`tmp/perf-audit/threads-pid66629.txt`). The live probes ran on pid 75447, which was restarted at 18:00:49Z at HEAD
`9e61340b2`. Every probe was a read-only JVM-mode form. The exception is one disposable MCP branch,
`perf-audit-opus-probe`, which was created, used for three SCI forms and retired. Probe wall time was about
18:00Z to 18:08Z.

The ranking is by time saved per ordinary operation. Items 1 and 3 have one cause.

## 1. Whole-store collection after every test request holds the store's exclusive gate for 25–82 s, and every branch creation and blob publication waits behind it

**Evidence from pid 66629:** `seon.maintenance/collect-released!` ran twice, with a maximum of 82,774 ms and a
total of 163,442 ms. The same maximum appears under `seon.sci.kernel/adopt-arm` (x4, max 82,774). The following
maxima all land within 0.5 s of it:

- `seon.cluster.registry/branch!` (x18, total 228,983 ms, max 82,311)
- `sci.eval/disposable-copy` (82,359), `sci.eval/evaluate-candidate` (82,410), `turn/gate-function-install` (82,426),
  `turn/evaluate-sources` (82,766), `cluster.agent/evaluate-source!` (83,049)
- `seon.test/clean-request-handle` (79,616), which calls `registry/branch!` at `src/seon/test.clj:1850,1861`
- `seon.blob/with-publication!` (x46, max 34,471)

**Verified cause (structural, from the pinned fork `fe1d4e26`):**

- `datahike.gc/gc-storage!` (`reference-code/datahike/src/datahike/gc.cljc:128-177`) takes the sweep permit before
  anything else. It holds the permit across the whole mark, the reachability extension, the key listing and the
  sweep.
- `datahike.gc-guard/immediately-admissible?` (`gc_guard.cljc:97-106`) admits a `:roster` permit (every `branch!`
  and `delete-branch!`, `versioning.cljc:227-231,293-297`) only when no sweep is active or waiting. It admits a
  `:blob` permit (`seon.blob/with-publication!`, `src/seon/blob.clj:309`) under the same condition.

So any `branch!` or blob publication that arrives during a collection waits until the whole collection ends. The
chain for an agent `defn` is:

`gate-function-install` → `evaluate-candidate` → `disposable-copy` (`src/seon/sci/eval.clj:5055`) →
`acquire-context!` → `registry/branch!` (`src/seon/cluster/agent.clj:1082`) → roster permit → wait for the sweep.

This confirms the hypothesis: an agent's evaluation (install gate → disposable branch) waited on storage GC. The GC
holds the store's reachability gate. The only work that truly needs it is the final sweep and a check of roster
changes.

**Why a collection runs at all:** `seon.test/run` submits `collect-released!` after every request that unlinks its
branches (`src/seon/test.clj:2512-2528`). That collection uses `registry/plain-cutoff` (the epoch), so it marks every
roster branch's entire history.

**What the collection is proportional to** (pid 75447, 5 roster branches, idle apart from root's own work):

| part | probe | time |
|---|---|---|
| mark: `datahike.gc/reachable-in-branch` over the roster with shared `visited`/`seen` (fix `25dcaabb` is loaded; arglists `[store branch after-date config visited seen]`) | form A below | 28,078 ms, then 25,214 ms |
| of that, the first branch to reach the shared history (`:design-1`) | per-branch timing | 27,678 ms; `:cluster-default` then adds 136 ms |
| store listing `konserve.core/keys` (reads every file's header, `konserve/impl/defaults.cljc:408-440`) | form B | 4,895 ms for 157,753 keys. **It runs twice per collection**: once for the inventory's `key-count` (`src/seon/cluster/registry.clj:538`) and once inside `konserve.gc/sweep!` (`konserve/gc.cljc:28`) |
| `registry/referenced-blobs` (history, all branches) | form C | 301 ms |
| candidates: keys not reached | form A2 | **537** (499 blob strings, 36 UUID nodes, 2 keywords) of 157,753 keys |

The work is the whole store: 9,168 commits and 202,872 index nodes (27 GB, `keep-history? true`), plus two full
header scans. The useful result is 537 garbage keys, about 0.3% of the store. Under concurrent load the same work
took 82 s. The work should be proportional to what the unlinked branches wrote. A collection's cost is also not
proportional to one test request, so it does not belong on the request's path.

**Smallest fixes, in order:**

1. **Delete the per-request collection.** Remove the submission at `src/seon/test.clj:2512-2528` and
   `seon.maintenance/collect-released!` (`src/seon/maintenance.clj:957-970`), about 31 lines deleted and 0 added.
   The weekly `seon.maintenance/collect!` (`src/seon/schedule.clj:58-62`, `0 3 * * 0`) still reclaims. A test's
   garbage is dozens of nodes, so its retention cost is negligible. This also deletes the open task "a collection
   stopped with its jvm is recorded as a fault" (`resources/seon/tasks/seon.test.edn:52`).
2. **Shrink the gate hold in the fork** (`gc.cljc:128-177`), about 25 lines added and 5 deleted:
   - Mark and list keys **before** acquiring the sweep permit.
   - Under the permit, re-run `reachable-in-branch` only for the heads of the current roster, reusing the shared
     `visited`/`seen`. Unchanged heads cost one `k/get` each, and new commits cost only their new nodes. Then sweep
     the pre-listed candidates.
   - This is safe because the sweep already deletes only keys written before the safe point
     (`gc.cljc:136-139`). A key written after the listing is not in the listing. A branch created during the mark is
     re-marked under the permit.
   - Expected permit hold: about 1 s instead of 25–82 s.
3. Drop the second header scan: the inventory's `key-count` (`registry.clj:538`) duplicates the sweep's own listing.
   Derive it from the candidate listing, or drop it (task 534966f34876 already asks for the inventory to be
   recorded). This saves about 5 s of gate hold.

**Hypothesis not verified:** the collection ran under an evaluation's kernel arm, because `seon.flow` io work
adopts its submitter's arm (`src/seon/flow.clj:458-460`). If so, the collection's allocation counts against the
agent, and the evaluation's deadline can interrupt a sweep. `adopt-arm` is timed even with a nil arm, so the equal
maxima do not prove that the arm was non-nil.

**Existing task:** 534966f34876 `collection-marks-slowly-and-records-nothing`. Its "waits up to ~6 s" is now 82 s
even with the shared-visited fix loaded. The proposed task below records the new cause and replaces its gate
sentence.

```clojure
#:seon.task{:name "a-test-request-collects-the-whole-store-under-the-exclusive-gate"
            :kind :function
            :intent "seon.test/run submits collect-released! after every request (test.clj:2512-2528): an epoch-cutoff collection that marks every roster branch's whole history (9,168 commits, 202,872 nodes, 25-28 s at 5 branches on pid 75447) and lists all 157,753 store keys twice (4.9 s each), to find 537 garbage keys. gc-storage! holds the sweep permit for all of it (gc.cljc:128-177), and gc-guard admits no :roster or :blob permit while a sweep is active or waiting (gc_guard.cljc:97-106), so every branch! (install-gate disposable copy, test world, MCP branch create) and every blob publication waits: pid 66629 saw branch! 82,311 ms, gate-function-install 82,426 ms and with-publication! 34,471 ms behind 82,774 ms collections. Delete the per-request collection (the weekly seon.maintenance/collect! remains); in the fork, mark and list before the permit and hold it only for re-marking roster heads and the sweep."
            :must-read #{seon.maintenance/collect-released! seon.cluster.registry/collect! seon.cluster.registry/branch! seon.blob/with-publication!}
            :evidence #{"docs/research/agent-platform/perf-audit-2026-09-27.md"}}
```

**Probe forms** (JVM mode, `conn = (seon.cluster.boot/connection "default")`):

- A (mark): `(let [db @conn store (:store db) config (:config db) roster (k/get store :branches nil {:sync? true}) visited (atom #{}) seen (atom #{}) rib @(resolve 'datahike.gc/reachable-in-branch)] (mapv (fn [b] (count (async/<!! (rib store b (java.util.Date. 0) config visited seen)))) roster))`
  gave 28,078 ms, 9,168 commits and 202,872 nodes.
- A2 (candidates): the same reachable union, then `(remove #(contains? reach (:key %)) (k/keys store {:sync? true}))`
  gave 537.
- B (listing): `(k/keys (:store @conn) {:sync? true})` gave 4,895 ms and 157,753 keys (155,372 older than 1 h).
- C (blobs): `(seon.cluster.registry/referenced-blobs conn #{b} true)` per branch gave 301 ms in total.

## 2. A defn's settlement row is computed and validated twice; an unverified 19.4 s settlement

**Evidence from pid 66629:** `turn/settle-batch!` max 19,475 ms (x20, 1 threw), in `db/transact-applied!` →
`transact-call` 19,453 ms. The armed callees inside the writer and the checked write are all far below that:

- `validate-write-report` max 850 ms
- `write-report-error` max 649 ms
- `receipt-settle-call` (x93, total 22,713 ms, mean 244 ms, max 1,033)
- `row-tx` (x99, total 12,838 ms, max 703)
- `close-folded-call` max 718 ms

**Refuted:**

- **Writer queued behind GC commits.** Ordinary commits take no reachability permit (`gc.cljc:139-141`), and the
  applied answer is put before the commit-queue put (`writer.cljc:199-204`), so apply does not wait on commits.
- **core.async dispatch starvation by the mark's blocking IO in go blocks.** Go blocks run on the cached, unbounded
  `:mixed` pool (`core.async/impl/dispatch.clj:71-117`; the thread dump shows `async-mixed-*`).
- **The blob permit.** `with-publication!` acquires it before `commit-roots!` (`blob.clj:309-314`), so its wait falls
  outside `transact-applied!`. That wait is item 1.

**What remains:** the 19.4 s is spent inside the writer's apply of one batch. The batch is proportional to
forms × `receipt-settle-call`, at 244 ms mean per form. The profile cannot say which batch, how many forms or when
(item 7), so this is **unverified**. A batch of about 70 defining forms fits the means.

**Verified duplicate:**

1. The install gate computes each row's settlement through `with-settled` → `settled-value` → `db/with (row-tx …)`
   (`src/seon/turn.clj:1415-1431`, called at `:5089-5090`). That path has x43, total 13,552 ms, mean 315 ms,
   max 1,176.
2. The writer then recomputes the same `row-tx` inside `receipt-settle-call` (`turn.clj:1770-1772`).

So every defining form pays declaration-row validation, projection and the checked write twice. The second copy
runs on the writer thread, where it serializes every other write on the branch.

**Proportional now:** forms × (program-row validation + projection work), done twice. **Should be:** once per form.
The writer checks only that the basis the gate computed against is still the head (Datahike's
`:datahike/expected-basis-t`, already supported by `transact!`, `db.clj` docstring).

**Smallest fix:** carry the gate's `row-tx` data on the receipt and let `receipt-settle-call` transact it under an
expected-basis fence. Fall back to recomputation only when the basis moved. Estimate: about 15 lines added and 5
deleted. Related tasks: `the-projection-is-advanced-at-five-sites`, `definition-row-builds-a-projection-to-discard`,
`a-function-contract-changes-the-schema-projection` (seon.sci.eval / seon.schema), and 4bb788513862
(`write-owned-values-error` expands every turn of the runtime).

```clojure
#:seon.task{:name "a-definition-is-settled-twice"
            :kind :function
            :intent "The install gate builds each row's settlement with with-settled -> settled-value -> db/with (row-tx) (turn.clj:1415-1431, 5089), then the writer rebuilds the same row-tx in receipt-settle-call (turn.clj:1770): x43 mean 315 ms and x93 mean 244 ms on pid 66629, the second on the writer thread. One settle-batch! applied for 19,475 ms with no armed callee over 1.1 s, which fits a batch of ~70 defining forms. Settle the gate's computed tx-data under an expected-basis fence and recompute only when the head moved."
            :must-read #{seon.turn/receipt-settle-call seon.turn/with-settled seon.turn/row-tx}
            :evidence #{"docs/research/agent-platform/perf-audit-2026-09-27.md"}}
```

## 3. `seon.blob/with-publication!` 34 s: the item 1 gate

`with-publication!` acquires a `:blob` permit (`src/seon/blob.clj:309`). A `:blob` permit is not admissible while a
sweep is active or queued (`gc_guard.cljc:97-106`), so any settlement that staged a blob (an oversized value, a fault
evidence blob, `schedule.clj:594`, `fault.clj:174`) waits out the collection.

The reverse also holds. The permit is held until the settlement's durable commit resolves (`blob.clj:322-325`), so a
queued sweep waits for every pending publisher, and each newly queued sweep closes publisher admission. Its x46
total of 62,830 ms minus the 34,471 ms maximum leaves about 0.6 s mean for the others.

**Fix:** item 1. No separate task.

## 4. The health read costs 580–675 ms warm, and 1.7 s under load

**Probe (pid 75447, 121 agents on `:cluster-default`):**

- `seon.problems/problems`: 600 ms cold, 220 ms warm.
- `mcp-runtime-observation`: 676 ms, then 580 ms.

| family / part | time | rows |
|---|---|---|
| `deferred-agents` | 117 ms | 0 found, over 121 agents |
| `failed-tests` | 85–155 ms | 150–153 |
| `open-faults` (recomputes `error-signatures` again) | 68 ms | 10 |
| `error-signatures` | 14 ms | 30 |
| `replaced-roots` | 13 ms | 0 over 4,647 rows |
| profile summary | 10 ms | |
| flow status | 7 ms | |
| readiness, cache | about 1 ms | |

**Proportional now:** every agent ever (`deferred-agents` runs `turn/deferred-triggers` per agent id,
`src/seon/problems.clj:262-281`), every recorded test result, and the error signatures twice. **Should be:** the open
problems. Examples: agents with pending triggers found by one query over pending triggers, and the latest failing
results by one index read.

**Smallest fixes:**

- Pass `problems`'s `:seon.problems/error-signatures` into `open-faults` rather than re-deriving them. That is already
  the argument (`cluster.clj:623-624`), so the double work is inside `open-faults`: 68 ms for 10 faults. Check it.
- Make `deferred-agents` start from the pending-trigger datoms, not the agent roster: about 5 lines changed.

**Existing task:** 41a8e43ce5a4 (`a-status-observation-costs-half-a-gigabyte`), with 150 ms at 44 agents. Now it is
220 ms at 121 agents, and it grows with agents that never retire. The proposed evidence is an addition to that task,
not a new one.

## 5. Acquisition after a program change: 339 ms, recorded but hidden from the reply

**Probe:** on the disposable branch, `(inc 1)` answered `:ms 2`. A contracted `defn` plus call answered `:ms 59`. A
second contracted `defn` answered `:ms 43`, and its evaluation row holds `:seon.eval/acquisition-ms 339`, which the
reply omits.

Pid 66629 had `sci.eval/acquire!` x85 (max 1,173 ms, total 12,142) and `fork-cluster-ctx` max 1,178.
`cluster.host/host!` max 1,457 is the first acquisition of a hosted branch agent.

**Proportional now:** the whole program per changed program identity. **Should be:** the changed entities.

**Existing task:** d215b1ebde7c `acquisition-rebuilds-the-whole-program` (cite it; no new task). The hidden number is
item 7.

## 6. Refuted or already owned

- **`seon.schema/call-with-projection` (max 2,392 ms, x2,409) and `call-with-forms` (max 1,198 ms).** Wrapper cost
  measured at 0.47 µs and 8.9 µs per call (2,000 and 200 calls with a no-op callback). Their inclusive time is their
  callbacks'. **Refuted as a cost.** They are already task 5f54ae50d841 (`scope-wrappers-report-callback-time-as-work`)
  and c87892f697b8 (`the-projection-travels-ambiently`). The same applies to `db/call-with-custody` (max 1,796),
  `render/call-with-walk-context`, `turn/phase` and `await!`.
- **`seon.sci.eval/call-detecting-program-change`** (max 1,796, 8 threw): task 361088de3d56
  (`program-change-check-scans-the-whole-history`), with an unbound history scan.
- **`turn/settled-value` / `with-settled`** (max 1,176): item 2.
- **`fn.analyzer/analyze`** (max 1,104; `invoke-kondo` max 940, mean 110 ms): the maximum is the stale-entry rerun
  (`analyzer.clj:362-369`). This is task 547fc42c47a5; not probed further.
- **`cluster/refresh-source!` 2,514 ms, `cluster.boot/request!` 2,770 ms, `development-source-refresh!` 2,210 ms,
  `adopt-rows!` 1,227 ms:** publication and adoption. Tasks d8f73961e44f and 17027736987a. `refresh-source!` already
  attaches `profile/explain-slow` to its result (`cluster.clj:3145,3186`).
- **`render.web/graph-debug-html`** 1,068 ms (x1): the debug page, off the agent path. Task 55974f161f71 covers its
  cache. Not probed.
- **Excluded** (stability-sweep lane): `call-with-projection-state` (551,689 ms max, which is Flow proc lifetimes
  per 5f54ae50d841) and `settle-batch-refusal!`.

## 7. Measurement gap: a slow reply names nothing

**Verified.** The SCI reply map carries `:seon.repl/ms` = `:seon.eval/duration-ms`, the arm's evaluation time only
(`src/seon/repl.clj:218-219`). The reply leaves out all of the following:

- acquisition (339 ms above, recorded but not shown)
- the install gate and its disposable branch
- settlement
- waits outside the arm

It names no armed function. JVM mode carries only the event `ms`.

`profile/begin` / `explain-slow` exist, but only publication (`cluster.clj:3145`) and test members (`test.clj:2043`)
call them. Their JVM-wide window counts concurrent work: a 2,974 ms window around one `defn` on pid 75447 listed
root's test run, 7,615 ms in `routing-trial`. Task c7bc0c506aa4 owns that defect.

The 82 s evaluations of pid 66629 left no trace: `:cluster-default` holds 121 `duration-ms` datoms with a maximum of
471 ms, and the slow runs were on branches that have since been retired.

**Smallest route, adding no store:**

- The kernel arm is already per-evaluation and carried across threads (`adopt-arm`, `sci/kernel.clj:598-631`). It
  already accumulates `::adopted-allocated-bytes`.
- In `seon.profile/timed`'s completion, when `elapsed` exceeds `slow-ms`, append `[sym ms]` to the current arm's
  slow list, if an arm is present. The fast path pays one comparison.
- Settlement copies the list onto the evaluation beside `:seon.eval/acquisition-ms`.
- `seon.repl` shows `:seon.repl/slow` and the whole wall time (acquisition + evaluation + gate + settlement) whenever
  the whole exceeds 1 s.

This is concurrency-correct because the arm is the evaluation's own. It would have named `registry/branch! 82311` on
the 82 s reply. Estimate: about 15 lines added in `profile`, `kernel` and `repl`, and it lets
`explain-slow`'s JVM-wide window go.

```clojure
#:seon.task{:name "a-slow-reply-names-nothing"
            :kind :function
            :intent "The SCI reply's :seon.repl/ms is the arm's evaluation time only (repl.clj:218): acquisition (339 ms on a probe, recorded as :seon.eval/acquisition-ms but not shown), the install gate's disposable branch, and settlement are absent, and no armed function is named. pid 66629's 82 s evaluations left nothing on the cluster (max recorded duration 471 ms). Record [sym ms] of armed calls over slow-ms into the evaluating kernel arm (already per evaluation and carried across threads), settle them beside acquisition-ms, and show the whole wall time and those names when it exceeds one second."
            :must-read #{seon.profile/explain-slow seon.sci.kernel/adopt-arm seon.repl/reply}
            :evidence #{"docs/research/agent-platform/perf-audit-2026-09-27.md"}}
```

## Side observations

- **An unexplained query timeout.** A JVM-mode read on the fresh branch value timed out at 30 s:
  `(d/q '[:find ?src ?d ?a :where [?e :seon.eval/duration-ms ?d] [(get-else $ ?e :seon.eval/acquisition-ms 0) ?a] [(get-else $ ?e :seon.cluster.eval/source "") ?src]] (d/branch-as-db conn :perf-audit-opus-probe))`.
  The same rows read through `d/datoms :aevt` took 0.15 ms. This is unexplained. It may be `get-else` over an
  attribute absent from the schema. The io-prepl `default` session's thread may still be running it.
- **`seon.db/with-declarations`** x93,756 (total 66,974 ms) and **`carried-projection`** x59,745 (total 26,715 ms) are
  the top non-wrapper call counts. Projection builds (`load-projection` x116, total 23,786 ms, about 205 ms each) are
  owned by b2e2909e662d and the contracts-out-of-projection ruling.
