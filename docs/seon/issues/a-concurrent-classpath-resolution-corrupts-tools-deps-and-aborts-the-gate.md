---
type: issue
status: open
severity: friction
tags: [issue, test, runner, dependency-cache, class/bounded-execution]
---

# A concurrent classpath resolution corrupts tools.deps and aborts the gate

Observed by the orchestrator on 2026-09-17 (batch 58, HEAD `9d2138219`):
`bin/test`'s dependency-cache freshness phase aborted the whole gate ~17 s in
with

```
Error building classpath. class java.util.HashMap$Node cannot be cast to
class java.util.HashMap$TreeNode … at java.util.HashMap$TreeNode.moveRootToFront
bin/test: dependency cache freshness check failed
```

`clojure -Spath` succeeded seconds later, and the relaunched batch (58b) ran.
The stack is a `java.util.HashMap` corrupted by concurrent mutation inside
Maven's model validation during tools.deps resolution — another JVM (a
research lane's scratch `clojure -M:dev` or a lane's `clojure -Spath`) was
resolving the same `deps.edn` at the same instant against the shared
`~/.m2` / `.cpcache` state.

The gate's dependency phase reads one transient resolver failure as a
permanent gate failure. Under §2.3 a bound firing is a bug report naming what
never arrived; here the report is right but the class is wrong: the freshness
check should distinguish "resolution failed" (retry once, then refuse naming
the concurrent resolver if a lock or cache file shows one) from "cache is
stale". The cheapest structural fix is a file lock around the checkout's
`.cpcache` refresh so two resolutions never run concurrently on one checkout
(`bin/test`'s dependency phase and `bin/seon`'s JVM launches share it).

## Recurrence 2026-09-16 21:06Z (batch 106, HEAD `f42af6261`)

Two gates launched in the same second (batches 106 and 107). Batch 106
aborted in the freshness phase with `Error building classpath. class
java.util.HashMap$Node cannot be cast to class java.util.HashMap$TreeNode`
from `DefaultModelBuilder.importDependencyManagement`; batch 107 proceeded.
Log retained at `tmp/orchestrator/gate-results/batch-106-deps-race.log`.
The relaunch a minute later ran normally. Same class, same fix: one file
lock around the checkout's classpath resolution.

## Sighting 2026-09-17 ~22:05Z

`bin/test --prepare-head-base` on HEAD `f9eab9e31` failed in
`dependency-cache-and-classpath` with `java.util.HashMap$Node cannot be cast
to java.util.HashMap$TreeNode` (retained root `tmp/test-runs/run.QZ6Ffg`)
while a lane's `bin/test-fast --paths` run was building the same cache in the
same second. The chained `bin/test --platform` that followed built the cache
and prepared the base itself. The launcher still does not serialize cache
construction across invocations.
