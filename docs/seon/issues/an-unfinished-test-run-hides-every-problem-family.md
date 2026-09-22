---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, problems, test-runner, agent-platform]
---

# An unfinished test run hides every problem family

Observed on default PID 70720 (started 2026-09-22T21:47:19Z), 22:33-22:38 UTC,
read-only JVM probes through MCP `eval_clj`:

- `(seon.problems/problems (seon.db/db (seon.cluster.boot/connection "default")) {})`
  answers a refusal, not the family map (1167 ms cold, ~40 ms warm):
  `:seon.error/operation seon.test.runner/latest-results`,
  `:seon.test/execution-refusal :seon.test/population-unknown`,
  `:seon.error/expected :completed-member`,
  `:seon.error/offending seon.schedule-test/returned-and-thrown-handler-errors-use-the-existing-root-wake`,
  `:seon.error/data {:seon.test.run/id "7365f44c382f"}`.
- Run `7365f44c382f` (policy `:named`, namespace `seon.schedule-test`, branch
  `cluster-default`, `:seon.test.run/at` 2026-09-22T22:06:55Z, i.e. inside the
  current JVM's lifetime) has 7 members; 4 carry no
  `:seon.test.member/completed-tx`:
  `schedule-remains-the-third-proc-in-the-agent-graph`,
  `returned-and-thrown-handler-errors-use-the-existing-root-wake`,
  `scheduled-operational-events-do-not-expand-or-invalidate-the-agent-page`,
  `root-maintenance-seed-is-complete-and-has-no-minute-task`.

Two defects, two owners:

1. **Runner** (`src/seon/test/runner.clj`): a run that stopped mid-execution
   left members neither completed nor recorded as interrupted. AGENTS:
   "Recovery closes open turns and records unfinished evaluations as
   interrupted"; the same must hold for test members, whether the request
   ended in-JVM or at recovery (`:seon.boot/recovered-runs 0` here, since the
   run began after boot). Unknown/red evidence must be recorded, not left open.
2. **Problems** (`src/seon/problems.clj:345-407`): `failed-tests` returns
   the runner's refusal and `problems` then answers that refusal for the whole
   derivation (`(if (:seon.error/at tests) tests found)`), so one test's missing
   evidence hides error signatures, failed runs, errored receipts, deferred
   agents, unowned namespaces, stale vars and missing models. The unknown
   belongs to the failed-tests family; the other families are still derivable
   from the same database value.

Consumer side is fixed: `seon.cluster/mcp-runtime-observation` shows the
refusal as `:seon.dev.mcp/problems-unavailable` instead of crashing
(lane runtime-status-crash, landing note
`docs/prds/agent-platform/landing/lane-runtime-status-crash-2026-09-23.md`).
Also: `:seon.boot/readiness` in `resources/seon/schemas/seon.boot.edn:114`
declares `:seon.problems/problems` as the family map only, while `readiness`
(`src/seon/cluster/boot.clj:346`) carries the declared union that
`seon.problems/problems` returns; the readiness schema should be the union.
