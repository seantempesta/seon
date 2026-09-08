---
type: issue
status: open
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
