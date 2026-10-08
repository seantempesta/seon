# health-page-opus — state (2026-09-28, committed)

Landed in one commit (see git log -- src/seon/health.clj). Root lands patches/root/01–07 WITH it:
01 cluster.clj −166/+2 (mcp-runtime-observation, runtime-observation-ai, program-function-files, mcp-instance)
02 boot.clj :status → seon.health/report + render-ai (bin/seon status)
03 seon.dev.mcp.edn: drop :seon.dev.mcp/health, runtime-observation, runtime-status
04 seon.operator.edn: status cluster entry → :seon.health/cluster, :seon.health/state
05 test/seon/cluster/mcp_test.clj: drop the two runtime-observation tests (health_test replaces them)
06 test/seon/cluster/boot_test.clj: status test reads :seon.health/state and the open-fault count
07 context.clj docstring: no seon.problems/block
Until 01 lands, HEAD's mcp-runtime-observation calls the old 2-arity open-faults and is refused.
Other owners: patches/astra-fixer/01 (runner: report selector + one-query members; makes failed-tests
attribute-keyed), patches/health-attribution/01 (error_test: status fault list → problems/open-faults).

Focused tests for root: seon.health-test, seon.cache-test, seon.problems-test, seon.cluster.status-test,
seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault (with 06), seon.cluster.mcp-test (with 05),
seon.error-test (with its patch), seon.render.web-test, seon.render.web-context-test, seon.contracts-compile-test.
Proof after restart: MCP runtime_status prints the health face; GET /health paints; bin/seon status.

Evidence: proof.clj (workbench), ai-face-default.txt (830 estimated tokens). Measurements are in the commit message
and src/seon/health.clj ## Costs.
