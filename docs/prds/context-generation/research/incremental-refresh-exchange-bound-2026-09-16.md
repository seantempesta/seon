# The incremental-refresh exchange bound: a cloned artifact names a checkout that is gone

Dated 2026-09-16. Bounded triage of batch 88's new red. Retained root
`tmp/test-runs/run.j9rx0e` (left intact; another lane reads it).

## What never arrived

Worker `pool-2`'s `:task-complete` event for exchange
`1d5dff44-9385-480e-b381-c6949a695a92`
(`tmp/test-runs/run.j9rx0e/workers/pool-2/logs/worker-dispatch.edn`):

```
:dispatch-at "2026-09-16T15:41:11.128276Z"
:expected-worker-event :task-complete
:completion-bound-seconds 270
:task-symbols ["seon.cluster.boot-test/incremental-source-refresh-publishes-without-touching-existing-clusters"]
```

The bound expired at 15:45:41Z. The worker was neither wedged nor waiting on a
missing event: it was inside a COMPLETE source publication. Segment mtimes in
the test's own root
`workers/pool-2/tmp/boot-test/5aa0cc50-…/data/store`:

| minute (local) | `.ksv` segments written | what |
|---|---|---|
| 09:33 | 32 | cloned published base |
| 09:34 | 629 | cloned published base |
| 09:41 | 71 | `old-world` boot (test dispatch 09:41:11) |
| 09:44 | 5 | |
| 09:45 | **657** | a second complete publication, still running when the bound fired |

657 new segments is the same order as the 661 the base publication itself
wrote. `worker-stderr.log` shows no exception and no dump: the JVM was
healthy and writing.

## Why a complete publication happens at all

`seon.test-support/populate-published-root!` (`test/seon/test_support.clj:101`)
clones the runner's immutable base with `clone-directory! base root`. The
cloned source artifact `build/current-src.edn` is keyed by the ABSOLUTE
canonical paths of the base checkout. Measured from the retained root:

```
file-digests count:        2048
base-prefixed keys:        2048   (/Users/sean/src/seon/target/test-published-bases/1e52e…/checkout/…)
worker-prefixed keys:         0   (…/run.j9rx0e/workers/pool-2/…)
:seon.fn.manifest/roots:  [ …/test-published-bases/1e52e…/checkout/src
                            …/test-published-bases/1e52e…/checkout/test ]
```

The worker's own `current-source-snapshot` keys the same 2 057 files under
`…/workers/pool-2/…`. `seon.cluster/changed-source-paths`
(`src/seon/cluster.clj:1948`) unions both key sets, so EVERY file reads as
changed: ~2 048 base paths as `:deleted` (the file does not exist) and ~2 048
worker paths as `:added` (no artifact in the cached manifest). Of those, 337
are `.clj`/`.cljc`, and each one is fully analysed by `seon.fn/build-artifact`
inside `incremental-source-refresh!` BEFORE the `(seq structural)` test
discards all of it and calls `full-source-refresh!`
(`src/seon/cluster.clj:2025`), which analyses the whole tree a second time and
publishes it.

So the test's declared cost — `:seon.test/long "186.733 s pool: complete
incremental publication dominates"` (`test/seon/cluster/boot_test.clj:928`) —
is a record of this defect, not of the operation the test names. The two
assertion failures batches 68–79 recorded are the same cause seen before the
cost crossed the bound:

- `(is (false? (:seon.source/built? refreshed)) "an unchanged reported file
  reuses the published source head")` (`test/seon/cluster/boot_test.clj:950`)
  — the reported file genuinely did not change; only its absolute path prefix
  did.
- `(is (= old-basis (:max-tx @old-connection)))` — the complete publication
  advances what the test asserts is untouched.

**The expectation is not stale.** `built? false` is the ruled behaviour, and
the bound firing is the bug report §2.3 describes. Adding
`:seon.test/long-ms` here would be exactly the tuned constant standing in for
an observation the program already makes.

## Attribution: not 19874b71b

`19874b71b` threads `publication-roots` through the publication seams. It
touches no path identity and adds no per-row work; the complete-publication
path this test takes predates it (batches 68, 71, 74, 79 all recorded the same
two assertion failures). It is not the cause of either the red or the growth.

The measured growth candidate is `9cc181289` ("program shapes: ownership
follows the declarations, not the process"), which moved two derivations off a
process `defonce` and onto per-operation / per-row paths. Measured on this
checkout (`clojure -M:dev`, uninstrumented, 2 741 forms, 6 identity
attributes):

| call | µs/call | cost at this test's shape |
|---|---|---|
| `seon.schema.edn/packaged-forms` | 14 942 | 337 `build-artifact` calls → **5.0 s** re-resolving the same population |
| `seon.schema.edn/declaration-stamp` | 618 | every zero-arity `program/shapes` |
| `program/shapes` (authored, stamped) | 703 | was ~0 (a `defonce`) before `9cc181289` |
| `program/shapes-in forms` (uncached) | 13.8 | 90 000 rows → 1.2 s per per-row caller |
| `program/shapes forms` (memoized) | 0.2 | what the memo costs when it is used |

`seon.fn/declaration-forms` (`src/seon/fn.clj:1044`) falls back to
`packaged-forms` on every `build-artifact` call, and `build-artifact` is a
per-file public entry point.

Separately, `9cc181289` added `supplied-shapes` — "a per-row caller pays one
identity check" — but the three forms-supplied arities its own per-row callers
use never route through it: `program/shape` (`src/seon/program.cljc:250`),
`program/canonical-row` (`:893`) and `program/changed-attributes` (`:1018`)
call `shapes-in` directly, paying 13.8 µs where 0.2 µs was intended.
`seon.fn/artifact` (`src/seon/fn.clj:1067`) and `normalized-index-row` are
both per-row callers of `canonical-row`'s two-arity.

## What to fix, in order

1. **File identity in the artifact must survive relocation of its checkout.**
   `:seon.source/file-digests` and `:seon.fn.manifest/roots` are absolute
   canonical paths; a base published in one directory and cloned into another
   cannot describe it. Root-relative keys resolved against the artifact's own
   root make the clone honest and make this test's `built? false` reachable.
2. **Until then, name it.** `incremental-source-refresh!`
   (`src/seon/cluster.clj:1957`) already has the "complete publication:
   missing or stale artifact" branch. A cached artifact whose
   `:seon.fn.manifest/roots` differ from this publication's `:seon.fn/roots`
   (the value `19874b71b` already carries) is stale BY CONSTRUCTION. Deciding
   it there skips 337 discarded analyses and a 4 096-path phantom diff, and
   reports the relocation instead of reading it as 4 096 real changes. This is
   an absence-as-health check today: a path that is merely spelled differently
   is read as a file that was deleted.
3. **Route the per-row arities through the memo** — `(shapes forms)` in
   `program/shape`, `canonical-row` and `changed-attributes` — and resolve the
   declaration population once per operation in `build-artifact` rather than
   per call.

None of 1–3 was committed by this lane: the assignment allowed no test JVMs,
and a gated seam in `src/seon/cluster.clj` should not land unverified.

## Re-gate when a fix lands

`seon.cluster.boot-test seon.fn-test seon.program-test` plus
`bin/test --platform`.
