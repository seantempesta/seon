---
type: issue
status: open
severity: friction
tags: [issue, test, operating, tooling]
---

# The development cluster's JVM cannot run an in-process regression

Observed 2026-09-16 on the reforked `default` cluster (pid 53378, started
after the 04:10 refork), from a bounded lane whose rule is to verify with
`(seon.test/run #'ns/test (seon.operator/connection "default"))` and never
launch a test JVM.

That JVM's classpath carries `src`, `resources` and the vendored
`reference-code/*` roots only: `test/` is absent, so
`(require 'seon.test-support)` fails with `FileNotFoundException`. Adding
`file:.../test/` to the evaluating `DynamicClassLoader` loads the test
namespaces (the addition is per-evaluation — the URL and the `require` must
share one form), but the fixture then fails at the first canonical
`with-database`:

```
Could not locate clojure/core/async/flow_monitor__init.class,
clojure/core/async/flow_monitor.clj or clojure/core/async/flow_monitor.cljc
on classpath.
```

The test-only dependencies of the `:test` alias are not on the cluster JVM's
classpath, so no regression that uses `seon.test-support/with-database` can
run in the development cluster. The lane rule's in-process proof is therefore
unavailable there, and a lane is left with direct live probes of the changed
functions plus the orchestrator's batched gate.

Earlier the same day a `default` JVM did carry `seon.test-support`, so this is
a property of how the cluster is started, not a permanent one. Either the
development cluster should be started with the test classpath (the REPL-first
development environment is also where lanes are told to run one regression),
or the lane rule should name the supported in-process path explicitly.
