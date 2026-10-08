# Cache after the weight fix (dc79b069f), measured on default

Measured through MCP `eval_clj` on default (restarted on dc79b069f), scratch agent `cache-measure`, namespace
`cm.scratch`. Before numbers are from `docs/research/agent-platform/2026-10-08/morning-report.md`.

## Before / after

| Measure | Before (morning report) | After |
|---|---|---|
| Occupancy at start (fresh JVM) | 506 of 512 MB | 201-203 of 512 MB |
| Occupancy after 11 requests + probes | (at cap) | 338 of 512 MB |
| Evictions (all kinds) | 13 on reach-declarations | 0 |
| reach-declarations | 195 MB, 46% hits | not cached (no kind) |
| reach-attribute at end | n/a | 83 MB, 66% hits (176 hit / 88 miss) |
| reach-node / reach-schema-node | n/a | 7 MB 10% / 2 MB 23% |
| reach-digests warm (same db, 2nd call) | 21-40 ms | 0.21-0.36 ms, 62 KB, 0 reads |
| reach-digests first call on a new branch db | ~178 ms / 322 MB (after eviction) | 86 ms / 271 MB (db from the test step's own commit); 16 ms / 28 MB (db after an unrelated defn) |

## Per-request test-step (10 requests: one contracted defn + one deftest reaching it)

Request 1 includes agent and branch creation. Requests 2 and 3 were issued concurrently (contended); 4-10 sequential.

| # | request ms | test-step ms | settle ms | calls | reads | alloc MB |
|---|---|---|---|---|---|---|
| 1 | 1766 | 855 | 280 | 94,936 | 338 | 3.1 |
| 2 (concurrent) | 2210 | 1887 | 235 | 107,718 | 297 | 2.3 |
| 3 (concurrent) | 884 | 561 | 227 | 111,197 | 313 | 0.9 |
| 4 | 790 | 483 | 224 | 117,146 | 333 | 0.9 |
| 5 | 754 | 471 | 216 | 117,714 | 353 | 0.9 |
| 6 | 781 | 531 | 185 | 118,518 | 373 | 0.9 |
| 7 | 943 | 618 | 246 | 129,425 | 393 | 0.9 |
| 8 | 915 | 650 | 202 | 128,096 | 413 | 0.9 |
| 9 | 1040 | 818 | 155 | 131,173 | 433 | 0.9 |
| 10 | 1134 | 946 | 129 | 132,485 | 453 | 0.9 |

Unrelated defn afterwards: 305 ms total, test-step 167 ms (the test is reused).

## Findings

- The fix holds: no evictions, warm reach-digests is sub-millisecond and the cold case is 16-86 ms, not 178 ms.
- Every request stays over 0.75 s, and the test step is 60-80% of it (0.47-0.95 s for one trivial test). Reads rise
  by exactly 20 per request and the test-step grows from 483 to 946 ms across requests 4-10, so the reaching run
  does work proportional to the number of tests already on the branch (calls 117k to 132k). Expected cost of
  one trivial test is milliseconds; this is a 100x-class finding for a separate task.
- Occupancy grew 203 to 338 MB in about 12 requests (reach-attribute +75 MB, loaded-digests +20, projection +19,
  declaration-projection +9, derivation-world +9): about 7 MB per request in reach-attribute. At that rate the cap is
  reached after roughly 25 more branch requests across agents, so eviction pressure returns; reach-attribute
  per-branch-db keying is the next thing to look at.
- Dedicated cache kinds never hit: file-derivation-key and file-schema-keys (16%), behavior-digest 8%, reach-node 10%.

## After step narrowing (da1210491)

Measured through MCP `eval_clj` on default restarted on HEAD (665ac1c10, containing da1210491 and dc79b069f), scratch
agent `measure-2`, namespace `m2.scratch`, the same workload: 10 requests each defining one contracted defn and one
deftest reaching it, then one unrelated defn and one `:ping`. All 10 were strictly sequential (the table above has
requests 2 and 3 concurrent, so only 4-10 compare cleanly). Departure from the instructions: the unrelated defn and the
`:ping` were issued in the same turn and may have overlapped; both are small.

| # | request ms (was) | test-step ms (was) | settle ms (was) | calls (was) | reads (was) | alloc MB (was) |
|---|---|---|---|---|---|---|
| 1 | 1896 (1766) | 1046 (855) | 290 (280) | 87,438 (94,936) | 339 (338) | 5.0 (3.1) |
| 2 | 1429 (2210 concurrent) | 1100 (1887 concurrent) | 253 (235) | 111,282 (107,718) | 293 (297) | 0.9 (2.3) |
| 3 | 808 (884 concurrent) | 524 (561) | 212 (227) | 112,670 (111,197) | 309 (313) | 0.9 (0.9) |
| 4 | 788 (790) | 509 (483) | 210 (224) | 114,552 (117,146) | 325 (333) | 0.9 |
| 5 | 794 (754) | 529 (471) | 187 (216) | 114,523 (117,714) | 341 (353) | 0.9 |
| 6 | 799 (781) | 527 (531) | 200 (185) | 117,486 (118,518) | 357 (373) | 0.9 |
| 7 | 819 (943) | 551 (618) | 203 (246) | 119,312 (129,425) | 373 (393) | 0.9 |
| 8 | 763 (915) | 509 (650) | 184 (202) | 120,923 (128,096) | 389 (413) | 0.9 |
| 9 | 791 (1040) | 519 (818) | 192 (155) | 123,226 (131,173) | 405 (433) | 0.9 |
| 10 | 805 (1134) | 546 (946) | 196 (129) | 126,014 (132,485) | 421 (453) | 0.9 |
| 11 (profiled) | 621 | 442 | 112 | 94,865 | 456 | 0.9 |

Unrelated defn afterwards: 349 ms total, test-step 181 ms, settle 134 ms (was 305 / 167). `:ping`: 173 ms, test-step
107 ms, settle 48 ms, 59,353 calls, 109 reads.

Cache (`seon.cache/status`): weighted bytes 136 MB before the workload (fresh JVM; measured after the status read
itself) to 187 MB after 11 requests, ping and probes; zero evictions in every kind. reach-attribute 8.2 MB (49% hits,
54 hit / 56 miss) against 83 MB at the same point before; reach-attribute-head 3.5 KB; reach-graph, reach-index and
reach-node are KB-sized. Largest kinds now are loaded-digests 23.8 MB, base-context 23.8 MB, loaded-function-rows
23.2 MB, tasks-tx 21 MB, task-basis 19 MB, population-targets 17.7 MB.

### Findings

- The growth is gone. Test-step is flat at 509-551 ms over requests 4-10 (was 483 growing to 946), request totals
  763-819 ms (was 790 growing to 1134). The step now does work proportional to the change, not to the tests on the
  branch. Reads still rise by 16 per request (was 20) and calls by about 1-3k per request, so a small
  per-branch-size term remains; it is not visible in time yet.
- Cache growth is 1/10 of before for reach-attribute (about 0.7 MB per request against 7 MB), so the 25-request
  horizon to the cap is gone for that kind.
- Every request is still over 0.75 s, and the floor is not the test: a `:ping` with no form and no test costs 173 ms,
  of which test-step 107 ms and settle 48 ms, and an unrelated defn costs 349 ms. Expected cost of a trivial test is
  milliseconds (the evaluation itself is 6-8 ms); the remaining 500 ms test-step per request is still 50x or more the
  work. The test step runs (about 510 ms) even though only the new test changed.

### Slowest remaining owner: the test step, 442-551 ms per request (65% of the request)

Profile window across request 11 (`seon.profile/begin` before, `explain` after; the window was 7.5 s because it spanned
my own turns, so only the ranked self/total times and call counts below are meaningful; they include other threads):

| armed definition | self ms | total ms | calls |
|---|---|---|---|
| seon.schema/call-with-projection-state | 616 | 618 | 3 |
| seon.await/await-port-operations | 616 | 616 | 1 |
| seon.db/transact-call | 311 | 314 | 6 |
| seon.db/vector-query-call | 131 | 168 | 144 |
| seon.db/declaration-content-key | 72 | 72 | 11 |
| seon.sci.eval/fork-for-turn | 21 | 30 | 4 |
| seon.db/pull-logical | 18 | 26 | 311 |
| seon.test.runner/reach-attribute | 14 | 26 | 11 |
| seon.db/speculative-projection | 12 | 78 | 83 |
| seon.db/with-declarations | 8 | 246 | 563 |

Reading: `await-port-operations` (616 ms) is the wait for the test step's reaching run; `call-with-projection-state`
(616 ms, x3) is the enclosing transaction wrapper, so the two are the same time seen from two owners. Inside it,
`transact-call` is 311 ms for 6 transactions (4 are the request's own per the cost map, about 50 ms each, over the 100 ms
single-write guidance in total), `vector-query-call` is 131 ms over 144 queries (about 1 ms each), and
`declaration-content-key` 72 ms for 11 calls (about 6.5 ms each, a per-call cost worth a task: 11 calls for one
changed definition). `reach-attribute` is now only 14 ms self, 26 total, so the reach cache itself is no longer the
cost. Per-stage remainder for the step's 442 ms: transactions about 311 ms, queries about 131 ms, content keys about 72
ms (overlapping, since totals nest). Not attributable from the profile: unarmed time in the test run itself.
