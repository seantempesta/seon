---
type: issue
status: open
severity: friction
tags: [issue, bounded-execution, census, operator, dev-tooling]
---

# The bounded-boundary census is red on unlisted script subprocess waits

## Problem

`seon.bounded-boundary-census-test/every-foreign-subprocess-boundary-declares-a-bound`
(`test/seon/bounded_boundary_census_test.clj:281-288`) fails on committed HEAD as of
2026-09-24 09:18 (the snapshot base, `ad6316c02` or its parent). The test reports 34 subjects, all under `script/`:

- 10 in `script/seon/dev/citations.clj`: bare `babashka.process/sh` calls at lines 76, 92, 125, 152 and 155.
- 6 in `script/seon/dev/dependency_digest.clj`: `.waitFor` and `.getInputStream` in `git-dependency-pins`, lines 72-86.
- 18 in `script/seon/operator.clj`: `.get (.onExit …)` and `.waitFor` in `connected!`,
  `terminate!`, `force-stop!`, `launch-child!`, `command!`, `same-program?` and `-main`.

Most of these waits carry a bound, but they are not in `exact-subprocess-seam-internal?`.
The `sh` calls have no bound at all.

## Evidence (2026-09-24, lane fix-process-tree)

I ran the test from a `git archive` HEAD snapshot, using `clojure -M:test` and
`clojure.test/run-tests`. The result was 4 tests, 1 failure. It gave the same 34
subjects with HEAD's `src/seon/cluster/process.clj` and `src/seon/shell/jvm.clj`
and with that lane's edits to them, so the failure predates that lane.

## Fix direction

Route the script waits through `seon.cluster.process/run-process!`, or through a
declared bound the census recognizes. Do not add the waits to the allowlist.
