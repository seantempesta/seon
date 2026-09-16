---
type: issue
status: resolved
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

## Refuted and resolved 2026-09-16 (measured on the same JVM, pid 53378)

`seon.test` carries its own loader for exactly this: `test-loader`
(`src/seon/test.clj:22`) builds a `DynamicClassLoader` over the `:test`
alias's `:extra-paths`, and `with-test-loader` (`src/seon/test.clj:29`)
installs it both as the thread's context classloader AND as
`clojure.lang.Compiler/LOADER`. `resolve-test` (`src/seon/test.clj:217`)
goes through it, and `run`'s own `bounded-result` re-enters it on the
virtual thread that runs the Var. Resolving through that loader and then
calling the TWO-argument arity works; the three-argument arity is the one
that needs `:seon.test.run/provenance` supplied.

Exact form, run in this JVM:

```clojure
(let [conn (seon.operator/connection "default")]
  (#'seon.test/with-test-loader
   (fn [] (require 'seon.cluster.registry-test :reload)))
  (seon.test/run
   (#'seon.test/resolve-test
    'seon.cluster.registry-test/non-temporal-collection-marks-current-blob-references)
   conn))
```

Measured: 1 assertion, 0 failures, 0 errors, 1,247 ms — and the same path
ran `seon.cluster.store-test`, `seon.transact-feedback-test`,
`seon.test.runner-test`, `seon.cluster-test` and `seon.test-support-test`
fixtures, all of which use the canonical `seon.test-support/with-database`.
The `flow-monitor` failure reported above came from a bare `require` under
the ambient classloader, not from the supported path.

Two operating notes, both measured rather than inferred:

- the canonical fixture base takes ~30 s to build the first time in a JVM,
  and `run`'s two-argument arity bounds a test at
  `seon.test-support/event-backstop-seconds` (20 s). Realize the base first,
  or supply `:seon.test/remaining-ms` through the three-argument arity;
- a test cancelled by that bound leaves the `database-base` delay holding
  its `InterruptedException`, and every later fixture in the JVM then fails
  with "Program indexing transaction was refused". Re-`require`
  `seon.test-support` through the same loader and realize the base again.
