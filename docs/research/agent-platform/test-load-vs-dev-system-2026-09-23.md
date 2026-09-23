---
type: research
status: measured; design recommended; no code changed
lane: braid-test-load (Opus 5.5)
date: 2026-09-23
---

# Test load against the development system: what one member costs, what must be released

Measured on `default`, pid 63253 (started 14:11:20Z), `(seon.fs/source-directory)` =
`/Users/sean/src/seon`, HEAD `2e3eb03d3`, Datahike pinned `2cc313a6` (checkout `84a20308`).
Line numbers are the working tree at that HEAD. Heap max 10,488 MB (G1).

## The braid

Tests run in default's JVM (ruled), on branches of default's store, recording into
default's database. Four things are braided with the development system and none can
fail alone today:

| shared part | what a test member takes from it | released? |
|---|---|---|
| heap | 3 open Datahike connections per request (request, member, fixture branch), each with its own node cache, plus 3 forked SCI contexts | yes, at release (measured below) |
| store (disk) | 0.47 MB head record per created branch, plus every node its body writes | **no**: `delete-branch!` drops only the roster entry and collection is off |
| default's writer | one recording transaction per member (~200-250 ms each) | n/a, but serial with every agent turn and adoption |
| the cluster's `contexts` monitor | every acquisition (branch!, open, fork, program acquisition) runs inside `(locking contexts)` | held 50 ms quiet, 1-6 s when the program must be derived again |

Nothing bounds how many requests enter at once: two lanes ran requests concurrently at
14:16Z (one without the adoption token), and a third request (mine) overlapped a merge
test at 14:28-14:34Z.

## Method (exact forms, throwaway namespace `braid.probe`, private MCP session)

- `snap`/`state`/`full`: konserve file count and bytes under `data/store`; `d/branches` on
  the store's main connection; `@datahike.connections/*connections*`; the cluster handle's
  `:seon.agent/context-state`; G1 Old Gen after `System/gc` (what `jcmd GC.run` calls;
  one `jcmd 63253 GC.run` took 321 ms and left 3,864 MB old); `:max-tx` of default.
- `run1`: `seon.test/run` with the cluster handle as execution and its connection as
  recording connection, exactly the form `bin/test-check` sends.
- `cycle1`: the product lifecycle by hand through the same functions the runner calls:
  `seon.cluster.agent/acquire-context!` isolate off default's commit (the request
  handle), again off it (the member), again off that (the canonical fixture), optionally
  one `d/transact` of one datom on the fixture branch, then `release-context!` in reverse.

## Cost table

Quiet = no other request running; loaded = concurrent lane requests and adoptions.

| operation (per unit) | quiet | loaded | proportional to |
|---|---|---|---|
| isolated acquire, program already acquired (`acquire-context!`) | 48-68 ms (`registry/branch!` ~22, `fork-cluster-ctx` ~21, `config/effective` ~10, `open-branch!` ~8) | 1,000-5,980 ms for ~30 of 64 members | O(1) quiet; O(program) when `sci.eval/acquire!` derives a base context |
| first acquire at a new head | 1,628 ms (`derive-base-ctx` 1,305 ms, inside it `seon.fn/reverse-closure` 1,087 ms) | — | whole program, once per program identity |
| release (`release-context!`) | 5.5-8.9 ms | 8-20 ms | O(1) |
| member body (`seon.id-test`, 2 members) | 48-59 ms | — | the body |
| recording, per member (default's writer) | ~210 ms (13.1-14.7 s over 64) | ~500 ms (32.2 s over 64) | one transaction per member |
| fully reused request (2 members) | 78-81 ms, 0 transactions | — | selection reads |
| store, per created branch | +1 file, 472,620-472,761 bytes (the branch's head record) | — | the stored db record, copied (`versioning.cljc:269`) |
| store, one one-datom transaction on a branch | +3 files, ~734 KB (212,267 + 49,640 + new 472 KB head) | — | index nodes rewritten on the path, branching factor 4,096 (`store.clj:189`) |
| store, per executed member (64-member requests) | — | +5.7 to +9.8 MB, +20 to +32 files | branches + fixture writes; matches the 9.8 MB/sample of `docs/seon/issues/eval-samples-cost-42mb-of-store-each.md` |
| heap, one open branch after scanning `:aevt` (519,444 datoms, 296 ms) | +61 MB held; release returned it | — | nodes read through that connection |
| roster / connections / contexts during one request | +3 / +3 / +3 | two requests: roster 15, connections 11, contexts 9 | open requests × 3 |

### Five repeats: retention or baseline?

- Lifecycle `cycle1` with one write, 5 times: store +6 files and exactly +2,152,656 bytes
  per cycle; roster 4, connections 3, contexts 1 after every cycle; old gen after GC
  4,135 MB every cycle. **Heap, roster, connections and contexts return to baseline;
  store bytes grow linearly and never return.**
- `seon.test/run` on `seon.id-test`, 5 times: reused after the first, 78-81 ms, max-tx
  unchanged, no store growth. A reused request costs nothing durable.
- The same request with `:seon.test/changed [seon.id/id]`, 5 times (my error: the change
  widened a 2-member request to every test reaching `seon.id/id`): 20.3, 78.3, 77.0, 79.5,
  103.2 s, 64 members executed per request under the 120 s bound; old gen after GC 4,015 →
  4,072 → 4,239 → 3,943 → 4,214 MB (no monotonic growth); store 5.27 → 7.19 GB (+1.92 GB
  for ~256 members, 7,000 files); max-tx +71 to +77 per request; roster and contexts back
  to baseline after each.
- Whole session: the store went from 2.66 GB / 7,878 files (14:15Z) to 7.21 GB /
  20,873 files (14:36Z) under all lanes' test load.

## Retention found

1. **Store: every created branch and every body write stays on disk.** Holder: konserve
   files no roster branch reaches. `registry/retire-branch!` (`registry.clj:330`) calls
   `d/delete-branch!`, which removes only the roster entry and leaves the 472 KB head key
   (`reference-code/datahike/src/datahike/versioning.cljc:279-320`; the head is written by
   `k/assoc store new-branch updated-db` at `:269`). The only releaser is
   `collect!`, and collection is off (`resources/seon/schemas/seon.config.maintenance.edn:5`,
   pending `docs/seon/issues/published-head-references-never-written-index-nodes.md`). At
   the measured rate a 2,000-test baseline run adds 12-20 GB.
2. **An acquisition whose caller throws before release has no sweeper.** My own probe
   threw after `acquire-context!` and left `agent-468d3b122bca`: its roster entry, its
   Datahike connection and its context entry, until I released it by hand.
   `acquire-context!` cleans up only its own failure (`agent.clj:925-935`). Every product
   caller brackets correctly (next section), so this is a caller discipline, not a leak in
   the runner; but no owner notices an orphan.
3. **Heap: no per-member retention found.** Old gen after GC is flat across lifecycle
   cycles and across requests. The live floor itself, 3.5-4.4 GB after GC of 10.5 GB,
   belongs to the datahike-memory-research lane
   (`docs/research/agent-platform/datahike-memory-retention-2026-09-23.md`, in flight).

## Lifecycle audit: every acquire has its release

| acquire | release on success | on throw | on timeout |
|---|---|---|---|
| request handle, `test.clj:1761` | `finally`, `test.clj:1861-1862` | same `finally`; nothing between `:1761` and the `try` at `:1835` can throw (fn definitions) | same |
| member branch, `test.clj:1469` | `release!` handed to `bounded-result`, `test.clj:187` | `test.clj:1567` if not handed; `test.clj:209` if the body never started | watcher joins the live thread, then releases: `test.clj:179-181`; the branch is held until the body exits, by design |
| fixture branch, `test/seon/test_support.clj:636` | `finally`, `:641` | same | inherits the member's thread |
| `release-context!` itself, `agent.clj:749-780` | connection, roster entry and context entry each attempted; first cause rethrown with the rest suppressed | — | — |
| `acquire-context!` partial failure, `agent.clj:925-935` | releases the connection it opened and retires the branch it created | — | — |

Two observations on the timeout row. The member branch of a body that never exits is
held forever with no count anywhere a status reader sees; that is correct isolation but
silent. And an MCP `eval_clj` timeout does not stop the evaluation: my timed-out request
kept running on prepl thread 56 for six more minutes (stack sampled at `acquire-context!`).

## Findings outside this lane (paths for routing; not edited)

- The adoption token is not owned: another lane's script ran `mkdir … && echo got; …;
  rmdir` and its `rmdir` removed the token I held (taken 14:17:48Z, gone before 14:36Z).
  A failed `mkdir` must skip the `rmdir`. Owner of the protocol: the orchestrator's rules
  text; no issue note found (`rg adopt-token docs/seon/issues` → none).
- A request's member count is unknown to its caller before it runs: `--changed seon.id/id`
  turned a 2-member request into at least 256 executions. The tally should print the
  selected count first; the time bound is the only brake today.
- Acquisition holds the cluster-wide `(locking contexts)` monitor (`agent.clj:823`) across
  `registry/branch!`, `store/open-branch!` (itself under `branch-open-monitor`,
  `store.clj:550`), `fork-cluster-ctx` and `sci.eval/acquire!`. Under load, ~30 of 64
  member acquisitions took 1-6 s; a stack sample showed the request thread at the monitor.
  An agent turn's acquisition waits behind a test member's program derivation.
- When the captured program differs from the loaded namespaces (another lane published),
  members were refused with "Host-bound declaration seon.cluster.reload-measure/-main must
  change through the loaded source files" after a 1-3 s acquisition each (runs
  `7d808fffe075`, `6c8133d0077d`: 43 errors of 64). The member does not reuse the request
  handle's acquired program; `sci.eval` keeps 4 base contexts (`sci/eval.clj:2326`).
- Existing class for store growth per branch: `docs/seon/issues/eval-samples-cost-42mb-of-store-each.md`
  (9.8 MB/sample then; 5.7-9.8 MB/member now). It should gain today's numbers.

## Recommended design: each part fails alone

State the problem once: a test request consumes four shared resources in proportion to
its member count and to how many requests run at once, and only one of them (heap
objects) is released. The design bounds entry, releases durable bytes at unlink, and
makes recording proportional to batches.

1. **Admission bound per JVM.** A request takes one permit of a declared capacity before
   selection; it waits at most its own `:seon.test/check-time-limit-ms` remainder and
   then refuses, naming the holding run ids. Datahike and Malli have nothing to offer here;
   `java.util.concurrent.Semaphore` (fair) is the whole mechanism. The capacity is a
   declared constant beside `batch-limit` (`test.clj:1608`) with its measurement:

   **`request-capacity` = 2.** Two concurrent requests were observed safe (14:16-14:17Z:
   roster 15, connections 11, old gen 2.9-3.3 GB before GC, no failures). Each request
   holds at most 3 branches because members run serially, so capacity 2 bounds open test
   branches at 6. The OOM point was not measured here; raise the number only with a
   measurement of peak old gen under N requests.
2. **Durable release at unlink.** `delete-branch!` in the Datahike fork also removes the
   branch's head key when no connection holds it (it already refuses otherwise). That
   returns 0.47 MB per branch at once, ~1 MB per member. Written nodes still need
   collection; re-enabling it is the store-damage lane's gate.
3. **Recording per batch.** `run-batch` collects results and calls
   `runner/commit-results!` once per batch of at most 64 (it already takes a vector;
   `release-not-started!` already records many members in one transaction). A throw still
   reaches `record-interrupted!`, which records every open member red. Default's writer
   then carries 2 transactions per batch instead of 65.
4. **Acquire outside the monitor.** An isolated acquisition names a fresh branch
   (`agent-<id>`), so nothing races it; only the lookup and the `swap!` need `contexts`.
   A slow program derivation then blocks only its own caller.

Braids removed: test entry vs. heap (1), branch unlink vs. disk (2), member count vs.
default's writer (3), test acquisition vs. agent-turn acquisition (4). Braid added: one
process-wide permit; each part's one role is "how many requests may hold test branches".

### Algorithmic cost, before and after

| per request of M members, R concurrent requests | now | after |
|---|---|---|
| open test branches | 3R, unbounded R | ≤ 6 |
| default-writer transactions | M + 1 per batch | 2 per batch |
| durable bytes left after unlink | ~1 MB × M + body writes, forever while collection is off | body writes only, until collection |
| monitor hold per acquisition | branch + open + fork + program acquisition | lookup + swap |

Simplest alternative considered: capacity 1 (one request per JVM). It needs no number,
but serializes every lane behind the longest request; it is option C below.

## Slice plan

| slice | files | added src | regression (wanted behaviour) | collision |
|---|---|---|---|---|
| S1 admission permit | `src/seon/test.clj` | ~20 | a third concurrent request refuses by name within its bound while two hold permits; the permits return after a request that throws | test-overhead holds `test.clj` |
| S2 batch recording | `src/seon/test.clj`, `src/seon/test/runner.clj` | ~10 net (removes the per-member call) | a 3-member request adds exactly 2 transactions to the recording connection; a body throw still records every member | test-overhead holds both |
| S3 head key released at unlink | `reference-code/datahike/src/datahike/versioning.cljc`, its test | ~5 | after `retire-branch!` the store holds no key for the branch; a branch with a connection still refuses | store-damage may hold the fork |
| S4 acquisition outside `locking contexts` | `src/seon/cluster/agent.clj` | ~0 (moves lines) | a live acquisition returns while an isolated acquisition is parked inside program acquisition | m4-n1 holds `agent.clj` |
| S5 orphan visibility | `src/seon/cluster/agent.clj` status read | ~10 | a handle acquired and never released appears in `runtime_status` with its branch and age | m4-n1 |

S1 and S2 go to the test-overhead lane as a follow-up; S3 to store-damage; S4 and S5
after m4-n1 releases `agent.clj`. `db.clj` (writer-cost-2) is not needed.

## Owner decisions

Admission of concurrent test requests:

- **A (recommended): capacity 2 with a bounded wait.** Guarantee: at most 6 open test
  branches and two requests' transient heap; a request never waits past its own bound
  and refuses naming the holders. Cost: ~20 lines in `seon.test`; lanes may see a named
  refusal under contention. Gives up: throughput beyond two requests until measured.
- **B: admit against measured heap headroom.** Read G1 Old Gen collection usage at
  admission; refuse when it exceeds a declared fraction. Guarantee: adapts to the live
  floor. Cost: ~30 lines; the reading can change before the request allocates, which is
  the pre-read the design laws warn about. Gives up: a fixed, testable bound.
- **C: capacity 1.** Guarantee: one request at a time, simplest to prove. Cost: ~15
  lines. Gives up: every lane waits behind the longest request (64 members measured at
  77-103 s under load).

Durable release: approve S3 (Datahike fork change to `delete-branch!`) now, or wait for
collection to be re-enabled. Recommendation: S3 now; it is independent of the collector
defect and returns about half of each member's bytes.

## Timings over one second (my operations)

| operation | wall | justification or defect |
|---|---|---|
| `seon.test/run` `seon.id-test`, 2 members, first | 2,047 ms | bodies 108 ms, acquire 108 ms, release 27 ms; the rest is 4 writer transactions and selection. Defect: S2 |
| widened request (`--changed seon.id/id`), 5 runs | 20.3 / 78.3 / 77.0 / 79.5 / 103.2 s | 64 members each; bodies 15-56 s, recording 13-32 s, acquisitions up to 53 s under concurrent adoption. Over 10 s: my request error; the recording and acquisition shares are the defects S2 and S4; existing class `eval-samples-cost-42mb-of-store-each.md` |
| first isolated acquisition at a new head | 1,628 ms | whole-program base-context derivation, once per program identity (`derive-base-ctx` 1,305 ms). Defect in SCI acquisition (B2 target: interpret only changed rows) |
| MCP evaluation of the widened request | 120,000 ms, then timed out while the request continued | the MCP timeout is not termination (finding above) |
| observation windows (10 × 1.5 s, 8 × 10 s, 9 × 12 s, wait loop) | 15.1 / 80.0 / 110.4 s | deliberate sleeps between samples, no work |
| `jcmd 63253 GC.run` | 321 ms | — |

No RESET NEEDED: every branch, connection and context this lane created was released
(final state roster 4, connections 3, contexts 1).
