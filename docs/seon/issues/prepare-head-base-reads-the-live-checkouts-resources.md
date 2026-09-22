---
type: issue
status: open
created: 2026-09-23
owner: B4 / README §4 1.3d commit 5 (the published test base and its launcher are deleted there)
---

# `bin/test --prepare-head-base` reads the live checkout's `resources/`, not HEAD

**Evidence (2026-09-23, HEAD `225bc3489`).** `bin/test --prepare-head-base` failed in
6.67 s wall time (`tmp/orchestrator/prepare-head-base-2026-09-23.log`, run root
`tmp/test-runs/run.J7f155`). The refusal is "Schema attribute :seon.test.timing/release-ms
is declared in file:/Users/sean/src/seon/resources/seon/schemas/seon.test.edn but belongs
in seon.test.timing.edn". That attribute exists only in an uncommitted lane hunk:
`git show HEAD:resources/seon/schemas/seon.test.edn | grep -c release-ms` → 0, and the
isolated copy `run.J7f155/resources/seon/schemas/seon.test.edn` also has 0 matches. The
base child's classpath resolves `resources` against the main checkout (the
`:seon.test/classpath-roots` printed at the head of the log), so a "HEAD" base is really
HEAD source plus the live tree's resources.

**Why it matters.** Gate inputs are supposed to be DECLARED (AGENTS.md): one lane's
in-flight schema edit breaks every other lane's base and focused runs.

**Disposition.** Do not repair it in place. The published base is machinery that
1.3d commit 4/5 replaces: tests run on a branch in the shared JVM. Close this note with
that commit. Until then, the orchestrator runs `--prepare-head-base` only when no lane
holds an uncommitted `resources/` hunk.
