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
