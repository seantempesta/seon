---
title: "Datahike fork: time-filtered index-page reads only its prefix"
type: research
created: 2026-10-14
status: fork committed and pushed; Seon gitlink bump waits for root's restart batch
---

# Datahike fork: time-filtered `index-page` reads only its prefix (bump for root's restart batch)

Lane datahike-asof, 2026-10-14. Follows lane clean-asof (36b419c2c), whose untested patch
`docs/research/agent-platform/2026-10-14/clean-asof-datahike-index-page.patch` this lands, adjusted, in the fork.

**Seon's gitlink is NOT bumped.** A fork bump is committed only inside root's restart batch (AGENTS.md "Restart in
batches"). Root: bump `reference-code/datahike` to the fork commit below in the next batch and name the Seon tests
listed under "Root's batch".

## Fork commit

- Repository: `reference-code/datahike`, remote `origin` (https://github.com/seantempesta/datahike.git)
- Branch: `seon/overlay-file-frontend` (the branch Seon's gitlink 6d49110d sits on; it had no remote copy, so this
  push also publishes 6d49110d)
- Commit: `62f94893` ("A time-filtered index page reads only its prefix"), pushed to `origin/seon/overlay-file-frontend`, parent 6d49110d (Seon's current gitlink)
- The submodule checkout is left at `62f94893`, so `git status` shows `reference-code/datahike` modified (not staged); JVMs run committed archives at the pinned gitlink, so nothing runs it until the bump.

## Defect

`datahike.index-page/index-page` on a time-filtered view (`as-of`, `since`, `history` of an `as-of`) used the open
`datahike.db.interface/seek-datoms` (or `rseek-datoms`): the time filter (`datahike.db/post-process-datoms`) rebuilds
cardinality-one state eagerly over everything it reads, so every 200-datom page realized and sorted the whole index tail
after the prefix (forward) or everything before it (reverse). Cost was proportional to the index, not the page.

## Fix

On a view whose search context carries a time predicate, a page reads the prefix alone with
`datahike.db.interface/datoms` (index, components), restores native comparator order (`native-order`, which replaces
`restore-temporal-index-order`), and drops datoms before the cursor; the cursor-presence check and the
`limit + 1` completion rule are unchanged. Views without a time predicate (current value, plain history) keep the lazy
seek path unchanged. A page is now proportional to its prefix: paging a whole prefix of n datoms costs O(n²/limit), which
for Seon's largest declaration prefix (~7k datoms, 35 pages) is ~160 ms total.

## Fork tests

- New `datahike.test.index-page-time-filter-test`:
  - `time-filtered-pages-equal-the-open-seeks-pages`: for `as-of`, `since` and history-of-`as-of`, AVET and AEVT, forward
    and reverse, limits 1, 3 and 200, every page (datoms, order, polarity, `complete?`, cursor) equals the open seek's
    datoms over the same view, sorted natively, cut to the prefix and split by limit. Data: ranks asserted, changed and
    retracted before the as-of point and again after it, plus a 400-datom tail attribute sorting after the prefix.
    Green before and after (it is the equality the change must keep).
  - `time-filtered-page-reads-only-its-prefix`: the datoms the time filter receives for one cursor page are at most the
    prefix's history datoms plus one transaction instant per transaction among them (21). Red before: 415 (as-of
    forward), 421 (since forward), 418 (history-as-of forward), 22-24 reverse; green after.
- `datahike.test.index-page-temporal-test`: its two order/polarity tests stub `dbi/datoms` instead of
  `dbi/rseek-datoms` (the read the time-filtered path now makes); the stubbed foreign datom is gone because a prefix read
  cannot return one.
- Suite: `clojure -M:test -m kaocha.runner clj-pss` (after `bb jcompile` in a fresh worktree). Parent 6d49110d: 957 tests, 5505 assertions, 2 errors, 0 failures. Change: 959 tests, 5583 assertions, 2 errors, 1 failure. The 2 errors are `datahike.test.background-gc-test` `background-gc-with-live-branches` and `sweep-spares-in-flight-branch` (a fixed file-store path under `$TMPDIR` collided between the two concurrent runs; both pass alone on the change, 3/3). The failure is `datahike.test.optimistic-test/tx-report-happy-path-converges-to-conn` (no `:conn-advance` event), which also fails on the parent run alone 4/4: pre-existing, unrelated to `index-page`.

## Numbers (synthetic index, same JVM script on parent and change)

`docs/research/agent-platform/2026-10-14/datahike-asof-bench.clj`: memory store, `:keep-history? true`, 7,000 entities with an indexed symbol attribute (the `:seon.fn/sym`
shape), 300,000 tail datoms sorting after it in AVET (314,003 AVET datoms); view `(d/as-of db (:max-tx db))`; AVET,
components `[:fn/sym]`, limit 200; medians of 7 after 3 warm-ups. Both runs shared the machine with a fork test suite.

| measure | parent 6d49110d | change |
|---|---|---|
| as-of first page | 288.7 ms | 4.9 ms |
| as-of page at cursor 18 | 286.1 ms | 4.6 ms |
| datoms the time filter realizes for that page | 303,434 | 7,004 |
| whole prefix, 35 pages | 10,104 ms | 160 ms |
| `d/datoms` of the whole prefix on the as-of view | 2.4 ms | 2.6 ms |
| current-value first page | 0.05 ms | 0.14 ms |

(Current-value pages take the unchanged seek path; the difference is load noise.) Seon's own as-of digest map was not
measured here: it needs the bumped fork loaded in default (clean-asof measured ~26 s / ~30 GB per call on parent).

## Root's batch

After the bump, name and run:

- `seon.db-test/an-as-of-view-is-keyed-on-its-own-fixed-point`
- `seon.task-test/a-tasks-revision-is-its-content-on-an-as-of-view`
- the as-of digest-map probe, parent vs change on a quiet JVM:
  `(seon.profile/measure #(seon.program/digest-map (seon.db/as-of db (seon.db/basis-t db)) {:seon.program/max-datoms 1000000}))`
  with `db` default's main value; expected from ~26 s / ~30 GB to well under a second (each declaration attribute's
  pages now cost its prefix, not the index tail). Record the measured call in the bump commit.
