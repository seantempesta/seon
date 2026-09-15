---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, config, wave/context-fixes]
---

# Read the function installation case count from configuration facts

On 2026-09-08, the ordinary source-submission probe on main-root `default`
opened `source:15478e0e-fd6b-469e-9881-9d2d143e0fd4`, but it did not close
within the probe's 20-second event bound. The agent was armed and its next
work remained ordinal zero.

`src/seon/cluster/loop.clj`, `gate-function-install`, supplied
`:seon.config.test/auto-check-cases` from the cluster handle. Live inspection
with the cluster's handed projection returned a nil handle value and a
configured database value of 25. The armed `evaluate-candidate` contract
refused the nil input. The same fault already appeared in the default log
before the probe.

The turn-cut lane changes the reader to `config/effective` on the database
value already held by the installation decision. The new real-SCI regression
`function-install-reads-the-case-count-from-cluster-facts` configures three
cases, removes the handle copy, and asserts installation, closure, and the
recorded case count. Verification is unfinished: development adoption refused
an independently installed uncontracted function, and the lane stopped under
its shared-state stop rule. This issue stays open until the regression and
the ordinary proc probe pass.

Acceptance: a contracted source definition installs and closes through the
ordinary proc using the current configured case count, with no handle copy.

## Resolution (2026-09-15 triage)

Basis: `7e35df2131c71f476a85c6a38bfc8eb292cb36f5` (committed source).

HEAD `src/seon/turn.clj:3123` obtains `:seon.config.test/auto-check-cases` from `(config/effective database (:seon.cluster/name cluster))`, not from the cluster handle. The exact regression remains at `test/seon/cluster/turn_test.clj:377`; it sets three database cases, removes the handle copy and drives the ordinary turn path. The nil-handle cause cannot occur through this current reader. `bin/test-fast --paths docs/seon/issues/function-install-case-count-is-read-from-an-absent-handle-key.md -- seon.cluster.turn-test` was launched; its result is recorded in the landing report rather than asserted green here.

surface: turn-loop

Gate boundary: the pre-correction test-fast invocation reached armed execution (981 instrumented functions; snapshot HEAD `03976706cc350f105b0d66cd78b50197c3f7642e`) but no terminal verdict was received. The process was absent when the no-JVM correction arrived. This resolution relies on the corrected source reader, not a claimed passing run.
