# Lane fix-f1-carry-derived-projection (2026-09-24)

Fix F1 of `docs/research/agent-platform/projection-call-cost-2026-09-24.md` (36af5c620).
Publication's fallback previous database is now the plain `(d/db connection)`. Its readers
already call `db/carried-projection`, whose cache is keyed by declaration content and commit.

## Broken or incomplete first

- **The deletion is not landed.** `test/seon/fn_test.clj:1966` still calls
  `db/carry-derived-projection`. That file is held by fix-f4-batched-index-rows, so deleting the
  function now would stop `seon.fn-test` loading. The deletion is ready as
  `tmp/fix-f1-carry-derived-projection/delete-carry-derived-projection.patch`: it removes
  `db.clj:278-298` and the now-unused `seon.schema.edn` require, 24 lines. Apply it with
  `git apply -p0` in the same commit that changes fn_test.clj:1966 from
  `(db/carry-derived-projection @connection)` to `@connection`. The next line already derives
  through `db/carried-projection`. That fn_test edit is proposed and has not been run.
- **The regression has not been run.** `seon.cluster.source-test/publication-hands-its-commit-projection-unstamped`
  uses the namespace's private physical store (`with-store`), so `bin/test-check` excludes it
  as platform tier. Root owns that run: `bin/test --platform -- seon.cluster.source-test/publication-hands-its-commit-projection-unstamped`.
- The hook runs after my edits were red on foreign tests. seon.cluster.mcp-test value window ×3
  and seon.mcp-test are MCP rendering. publication-findings-test expects a message that
  cluster.clj:123 no longer emits. config-application-test and two seon.fn-test reds come from
  canonical-definition at fn.clj:603 while fn.clj has uncommitted fix-f4 edits. None of these
  reach the changed line.
- Out of scope: once the patch lands, `seon.schema/projection-delta` (schema.clj:2519) has
  no src caller, only schema_test. schema.clj is not owned here.

## Evidence (default JVM, pid 99265)

- Equality at commit `6ab576c3-…`: `carry-derived-projection` and `carried-projection` have
  equal `:seon.schema.projection/forms` (3,381 forms) and equal registry keys (5,565,
  `malli.registry/schemas`). They are not `identical?`, and the derived projection is rebuilt
  on every call.
- Cost: `carry-derived-projection` took 465.5 ms per call (profile: 4 calls, mean 464.6 ms,
  max 470 ms). `carried-projection` took 0.018–0.092 ms on a value of a cached commit.
- Publication path probe: I made a scratch branch `:lane-f1-probe` from the published commit
  `6ab5789a-…` in default's store, as `publish!` does, and opened it. `(d/db conn)` carries no
  stamp. `(carried-projection (d/db conn))` returned in 1.24 ms, `identical?` to
  `(carried-projection (source/commit-database store commit))`. I retired the branch.
- Profile of `seon.cluster.source/publish!`: 2,805 ms mean before (×29) against 2,180 ms (×1)
  on the first hook publication after the change. Only 4 of about 36 publications this JVM
  took the fallback, so this comparison is noisy. Most callers supply
  `:seon.source/previous-database`. The saving is 465 ms per fallback publication, plus the
  instrument recompiles the fresh object caused (instrument.clj:569 reads the stamp first).

## Lines

src −1 +1 (source.clj:612), test +23 (regression and a recording populate function). With the
pending patch: src −24 more.

## Commits

See `git log -- src/seon/cluster/source.clj` (the lane commit). RESET NEEDED: no.
