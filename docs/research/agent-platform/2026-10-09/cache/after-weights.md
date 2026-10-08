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
