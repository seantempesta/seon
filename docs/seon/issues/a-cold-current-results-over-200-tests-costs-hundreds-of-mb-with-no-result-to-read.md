---
type: issue
status: open
severity: friction
created: 2026-10-20
tags: [issue, cost, test-runner]
---

# A cold current-results over 200 tests costs hundreds of MB with no result to read

## Problem

`seon.test.runner/current-results` answers each named test's newest recorded result at its current reach digest.
On a branch with no recorded results its answer is `{}`, and the only work that answer needs is one reach digest per
named test plus one read of its executions. A cold call over 200 tests instead costs hundreds of MB and tens of
thousands of armed calls. Found by lane guards3 while measuring `seon.test.runner/held-answer!` (commit `d1965857a`).

## Evidence (2026-10-20, default, pid 11460, running `a92ba5b94`; JVM mode, read-only)

The form, on default's main (3,436 test entities, no recorded results):

```clojure
(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      names (vec (take 200 (sort (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s]] db))))]
  (seon.profile/measure #(seon.test.runner/current-results db names nil)))
```

| call | names | answer | ms | calls | MB allocated |
|---|---|---|---|---|---|
| first cold call in the request (plus one `q` over every test, 12.6 ms, 32 calls) | first 200 | `{}` | 252 (form) | 91,088 (request) | 706.1 (request) |
| cold, the next set | 200, offset 400 | `{}` | 668.5 | 170,982 | 1,693.8 (request) |
| cold, after the shared reach graph was warm | 200, offset 800 | `{}` | 124.0 | 32,897 | 135.1 |
| warm (cache hit, same key) | same 200 | `{}` | 0.24-0.41 | 49 | — |

In the 124 ms call the top by calls were `seon.cache/cache?` 3,759, `seon.cache/kind-counters` 3,015,
`seon.db/database-value?` 2,411, `seon.db/declared-collections` 2,238, `seon.cache/claim` 2,055,
`seon.schema/projection-cache-value` 1,742, `seon.id/structural-digest` 1,521; by self time
`seon.db/declaration-value` (236 calls, 30 ms) and `seon.db/behavior-digest` (214 calls, 7 ms). The first two rows
were measured as whole requests (`:seon.cost/alloc-mb`), so their MB include the other forms of the request.

## Expected cost

Proportional to the named tests' closures, each definition's behavior digest computed once by content and shared
(`seon.test.runner/reach-digests` over the cached reach graph), plus one indexed read of the executions at those
digests. For 200 tests on a warm reach graph: tens of ms, a few MB, calls in the low thousands. The cold rows run
10-100x over that.

## Done when

A `seon.profile/measure` proof over the form above (fixture program of comparable size, no recorded results)
asserts calls and allocation within that estimate, compared parent against change. Read
`seon.test.runner/current-results`, `seon.test.runner/fold-current-results`, `seon.test.runner/current-reaches`
and `seon.test.runner/reach-digests`.
