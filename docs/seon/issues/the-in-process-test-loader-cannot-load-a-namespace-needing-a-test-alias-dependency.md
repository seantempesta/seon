---
type: issue
status: open
severity: friction
tags: [seon.test, test-loader, in-process, classpath]
opened: 2026-09-17
---

# The in-process test loader cannot load a namespace needing a `:test` alias dependency

## Problem

`seon.test`'s `test-loader` (`src/seon/test.clj:106`) adds only the `:test`
alias's `:extra-paths` to its `DynamicClassLoader`, never its `:extra-deps`.
`test/seon/test_runner_test.clj` requires `dev-cache` (`dev_cache.clj`), which
requires `clojure.tools.build.api`, an `:extra-deps` entry of the alias. In the
development JVM both `require :reload` and `seon.test/resolve-test` fail with
`Could not locate clojure/tools/build/api__init.class … on classpath`, so
`seon.test/run` cannot run any test in that namespace in process and a lane
must prove its slice by driving the changed functions directly (landing note:
`docs/prds/steward-platform/research/runner-error-face-2026-09-17.md`).

The refusal is loud (a load error), so this is friction rather than a silent
gap, but it is invisible until a lane hits it, and it leaves the runner's own
regressions cold-only.

## Wanted

The loader derives the alias's complete classpath (paths AND deps) the same way
the cold gate's worker does, or `seon.test/run` returns a typed refusal naming
the missing dependency and the alias that declares it. Either way the answer
is derived from `deps.edn`, never a hand-maintained list of dependencies.

## Evidence

Observed 2026-09-17 on default pid 30138, adopted commit
`6aaab871-4c0d-57a3-83bc-79c1e128b59f`, by the runner-face lane.
