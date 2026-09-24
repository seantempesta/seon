---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, test-runner, instrumentation, allocation]
---

# The drift snapshot copies every namespace map twice per test

Seen 2026-09-24 by lane fix-test-run-cost on default (pid 90471): 587 namespaces,
467,616 namespace mappings, 1,833 wrapped Vars.

## Problem

`seon.test.runner/run-vars!` takes `ambient-snapshot` before and after every body
(`src/seon/test/runner.clj:350`, `:383`). Each snapshot costs 15.8–16.3 ms and allocates
75.5 MB. The whole cost is `seon.instrument/instrumented` (`src/seon/instrument.clj:41`),
at 15.3 ms / 75.3 MB. It calls `ns-interns` on every namespace, and `ns-interns` builds a
fresh filtered map from `ns-map` each time. The other two members are negligible: Malli
`function-schemas` took 0.07 ms and is empty in this JVM, and the running-cluster keys are
a small set.

## Measured alternative (same answer)

This is `instrumented`, rewritten to reduce over `(.getMappings ns)`, keep `Var`s whose
`.ns` is the namespace, and test `(identical? v (::var (meta (.getRawRoot v))))`. It returned
the equal set of 1,833 in 5.5–7.7 ms and 0.17 MB. That is 2–3× less time and about 440×
less allocation. The work is still O(all mappings). No Clojure seam announces a change to
a Var root, so making it proportional to the test would need change tracking on Vars.
That would be new machinery.

## Owner

`src/seon/instrument.clj` (`instrumented`). The runner keeps calling the owner. A copy in
the runner would be a second implementation.
