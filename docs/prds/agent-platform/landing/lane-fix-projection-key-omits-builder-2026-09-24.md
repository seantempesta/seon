---
type: landing
status: landed
lane: fix-projection-key-omits-builder
created: 2026-09-24
issue: docs/seon/issues/a-declared-render-pair-on-an-all-optional-map-is-never-selected.md
---

# Lane fix-projection-key-omits-builder (2026-09-24)

`seon.db/carried-projection` memoized projections by declaration content and
revision only. A projection is also a function of the code that builds it
(`seon.schema/load-projection` and what it reaches, such as `shape-row-in`).
After 14a9291ad changed `shape-row-in`, default kept serving projections that
the old code had built.

The fix works the same way as c3a225dc7:

- Every projection-cache key now also names the **loaded builder**. That is
  `seon.id/digest` of the sorted map `{sym → :seon.program/definition-digest}`
  over the declarations reached from `seon.schema/load-projection` through
  stored `:seon.fn/calls` (`builder-digest`).
- The digest is read from the commit the JVM loaded. That commit is the
  adoption record's `:seon.source/commit-id`, which moves after load and
  arming (the same rule as `seon.sci.eval/loaded-commit-id`). A value with no
  record uses its own commit. An uncommitted value with no record uses its own
  rows.
- The digest is memoized under `[::builder commit]` in the existing
  `projection-cache`. No new cache.
- `nearest-base` picks bases only from projections built by the same builder.
  A changed builder therefore rebuilds from `{}` once, and never carries rows
  that the other code built.

## Broken or unverified first

- **The regression has not run.** The owner paused hook publication and all
  new test runs to reduce load. Root should run:
  - `bin/test-check default --test seon.db-test/a-changed-projection-builder-misses-the-projection-cache`
  - `bin/test-check default --ns seon.db-test`, because the cache-key tests
    `equal-declaration-content-shares-one-projection-across-values`,
    `an-in-transaction-declaration-derives-its-population-once` and
    `an-as-of-view-of-an-in-transaction-value-reuses-its-population` depend on
    key sharing.
- **The loaded code on default is one docstring edit behind the committed
  file.** The last edit, a `carried-projection` docstring sentence, was saved
  after publication was paused. The loaded code's behaviour matches the
  committed file, which the arity-2 `loaded-builder` shows.
- **The hit cost is higher, and I cannot state the regression exactly.**
  - Before: 0.012 ms per committed hit, measured on a quieter JVM.
  - After: 0.028 ms (0.030, 0.028, 0.028 over three runs of 3,000), measured
    while default ran at about 900% CPU from ten lanes.
  - A first draft made one more armed call per read. It measured 0.046 ms
    under similar load, and `seon.db/loaded-builder` alone cost 0.0166 ms per
    call (531 ms over 32,052 calls). The committed version reads the memo
    inline and calls `loaded-builder` only on a miss.
  - Root should repeat this probe in a quiet window:
    `(let [db (datahike.api/db (seon.cluster.boot/connection "default"))] (time (dotimes [_ 3000] (seon.db/carried-projection db))))`.
    If the regression is still more than 20%, the remaining cost is the
    uncached reads on each call (`dbi/-schema`, one `aevt` datom, and
    `cache/lookup`), each measured at 0.0001 to 0.003 ms.
- **The builder reach follows stored calls only.** A building function reached
  only through a Var passed as data is outside the digest, the same limit as
  c3a225dc7. The reach has 244 symbols, 86 of them first-party with digests,
  and includes `seon.schema/shape-row-in`.
- **An uncommitted value with no adoption record recomputes the digest on each
  call** (about 1.6 ms). Examples are detached `empty-db` or `load-entities`
  values that hold declaration rows. Values in test worlds and on default
  carry the record, so they do not.

## Evidence (default pid 99265, JVM `eval_clj`)

- The builder digest is `14fefa8d…0d32`. The recorded loaded commit and the
  cluster's own rows give the same digest.
- Measured costs:

  | operation | cost |
  |---|---|
  | `builder-digest` (index walk) | 1.6 ms warm |
  | the first query-based draft | 8.8 ms |
  | `commit-as-db` with `:secondary-indices? false` | 5 ms |
  | full `load-projection` from `{}`: the one-time rebuild on a builder change | 402 ms |

- **The live fix now works.** After adoption, the live projection has the
  `:seon.problems/problems` shape row.
  - `matching-shapes-in` on the live problems value returns
    `[:seon.problems/problems]`, with `:seon.render/ai seon.problems/ai-prose`.
  - `(#'seon.render/schema-producers p {} value :seon.render/ai)` returns
    `[seon.problems/ai-prose]`. Before this change it returned `[]`.
  - The rebuild happened once, on the first read after the reload changed the
    key shape. The first probe read took 0.13 ms because the rebuild had
    already run.
- The cache after the change held 23 projection entries, all under builder
  `14fefa8d`, and a single `[::builder commit]` entry.

## Change

- `src/seon/db.clj` (+72 / −23):
  - adds `builder-digest` and `loaded-builder`;
  - `nearest-base`, `content-projection` and `value-projection` take the
    builder;
  - `carried-projection` puts the builder into its revision, commit, content
    and value keys.
- `test/seon/db_test.clj` (+23): the regression
  `a-changed-projection-builder-misses-the-projection-cache`. It checks three
  things on an isolated branch:
  - an unchanged builder hits (`identical?`);
  - a new digest on `seon.cluster/serve!`, outside the reach, leaves the
    builder digest equal;
  - a new digest on `seon.schema/shape-row-in` changes the digest, and the
    same declaration content then derives anew.
- Net: about +72 source and test lines.

## Out of scope

- `seon.cluster/analyzer-digest` (c3a225dc7) walks the same reach with a
  whole-program `:seon.fn/calls` query: about 8.8 ms against 1.6 ms for the
  index walk here. It cannot be called from `seon.db`, because `seon.cluster`
  requires `seon.db`.
  - One shared `seon.db` reach-digest function, taking the entry symbol, could
    serve both.
  - That change needs ownership of `src/seon/cluster.clj`. No issue is filed:
    I do not own the issue directory.
- The issue `a-declared-render-pair-on-an-all-optional-map-is-never-selected.md`
  can close: live selection is now proven. I did not edit it, because I do not
  own it.

RESET NEEDED: no. No branches or background shells remain. The scratch
namespace `tmp.fix-projection-key` was removed.
