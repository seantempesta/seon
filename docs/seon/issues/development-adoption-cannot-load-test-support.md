---
type: issue
status: open
severity: friction
tags: [issue, operator, test, wave/dev-tooling-face-hygiene]
---

# Development adoption cannot load the canonical test support

On 2026-09-08 at 20:20 UTC, publication of `src/seon/cluster/run.clj`
refused with `Could not locate seon/test_support__init.class,
seon/test_support.clj or seon/test_support.cljc on classpath.`
The complete envelope is in `logs/current-source-failure.log`.
MCP runtime status observes default alive at pid 36758, web port 7994.

`script/seon/fresh_operator.clj` starts the child with `-M:dev`;
`deps.edn` gives the canonical test directory to `:test`, not `:dev`.
Development adoption now loads changed test declarations, so this classpath
cannot load their fixture dependency. Directly reloading a runtime Var does
not repair the database's old program contract and is not convergence.

Verify the development child can acquire a newly introduced canonical-fixture
test namespace and that adoption completes, including SCI and instrumentation.

## 2026-09-15 in-process check evidence

The reaching-tests lane reproduced the wider dependency boundary on default
(pid 69622, process start 2026-09-15T19:25:43Z). Adding the `:test` alias's
declared source paths permits ordinary test Vars to load, but preparing the
canonical fixture also loads `dev-cache` and the flow-monitor test namespace.
The ordinary `:dev` JVM lacks `io.github.clojure/tools.build` and
`io.github.clojure/core.async.flow-monitor`, both declared under `:test`.

Supplying the CLI-resolved `:test` classpath to a scoped DynamicClassLoader
allowed the real canonical fixture regression set to execute in this same
JVM: five tests, 22 assertions, no failures or errors, 50926.348792 ms. This is
an explicit verification prerequisite, not proof that a fresh development
boot can run these tests unaided. The proof script and exact results are in
[the reaching-tests landing note](../../prds/steward-platform/research/reaching-tests-tier-2026-09-15.md).

## Owner

The development process classpath in `script/seon/fresh_operator.clj` and the
existing dependency-basis mechanism. Test execution must not introduce a
second dependency resolver or start a test JVM.

## Acceptance

A fresh ordinary development boot, using its declared dependency basis, runs
`seon.test/check` over a canonical-fixture test without manual classloader
setup. Changed test namespaces load their current definitions and retain
armed contracts. Missing dependencies remain named failures, never zero-test
success.
