---
type: issue
status: open
severity: friction
tags: [issue, test, performance, query]
---

# A cold reach derivation for 20 tests takes over one second

Measured 2026-09-24 on default by lane locks-fs-and-runner-stamp
(`docs/prds/agent-platform/landing/lane-locks-fs-and-runner-stamp-2026-09-24.md`).
The measurement called `seon.test.runner/reach-digests` for 20 test symbols that
the index did not yet cover.

- The first read took 1,068 ms at HEAD b0f5a286d and 1,141 ms with 4b487b5ac.
- A second new set of 20 took 547 to 741 ms.
- A warm hit took 0.48 ms.

The profile names `seon.test.runner/reach-facts`. Inside it, `seon.db/q` ran
284 times, with 240,340 `seon.db/query-binding-values` calls and
`seon.db/with-declarations` at 1,421 ms inclusive. `seon.id/digest` ran
1,397 times through `seon.id/printed`, at about 580 ms.

The suspected cause is that each `reach-facts` round issues a query per
identity attribute, and `with-declarations` is paid once per query. The cost
therefore grows with the closure's depth multiplied by the number of queries,
where it should grow with the number of changed rows.

Done when a cold 20-test reach read takes under 1 s, and the profile names no
per-query declaration cost.

Update (2026-09-24, same lane): the index is now shared by program content across
branches (`reach-holders`), so the cold cost is paid once per program content, not
once per branch. A cold derivation for 200 tests still took 5.6 to 6.5 s. Most of it
was the per-test closure walk in `reach-entry` (26 of 28 ms for a closure of 1,853
names) and `reach-facts` queries. After any program write, `reach-refresh` re-reads
every requested test's facts (1.2 s for 200 tests).
