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

## Recurrence on default pid 90963 (2026-09-23, lane live-defects-diagnosis)

The plan status audit (commit 04899bece, 04:18:59Z) saw `runtime_status`
problems unavailable with `seon.test/population-unknown`. Read-only
re-observation:

- `(seon.problems/problems db {})` answered the family map at 04:19Z and again
  at 04:28:47Z. The unknown is intermittent, not permanent.
- Runs since boot that left members without `:seon.test.member/completed-tx`
  (query: runs with `:seon.test.run/at` after boot, members minus completed):
  - `47a759c6f410`, 04:11:14Z, `seon.program-test`: 8 of 30 members open.
  - `80d1340b16fc`, 04:23:40Z, `seon.render.transcript-test`: 13 of 17 members open.
- Later complete runs of the same namespaces superseded them, and `problems`
  recovered. Those runs are `3cc834ea6b90` (04:14:12Z, 30 members) and
  `f430406e6816` / `3d30c0558fa5` (04:24–04:25Z, 17 members).
- The profile shows `seon.test/run` threw 4 times by 04:23 and 5 by 04:28.
- Every thrown in-process `bin/test-check` run makes `problems` unknown until
  its members run again to completion. An in-flight run does the same for its
  duration. Both owners named above still stand:
  - The runner should record unfinished members as terminated.
  - `problems` should confine the unknown to the failed-tests family.

## Resolution (lane unfinished-runs, 2026-09-23)

- **Problems owner: fixed.** `seon.problems/problems` puts unknown test
  evidence under its own declared family, `:seon.problems/failed-tests-unknown`
  (a vector of `:seon.test/execution-error`), and still derives every other
  family from the same database value. On default pid 90963, `runtime_status`
  answers `problem-counts {error-signatures 8, failed-runs 6,
  failed-tests-unknown 1}` where it used to answer `problems-unavailable`.
  Regression:
  `seon.test.interrupted-request-test/unknown-test-evidence-leaves-the-other-problem-families-derivable`.
- **Runner owner: the writer is fixed, the call site is not wired.**
  `seon.test.runner/record-interrupted!` gives every admitted member that has no
  outcome a red terminal record naming the whole cause chain. The member stays
  an obligation. Regression:
  `seon.test.interrupted-request-test/a-thrown-request-records-its-unfinished-members-as-failed-obligations`.
  **Still open:** `seon.test/run` (`src/seon/test.clj`, held by the nsa-unblock
  lane) must call it when a batch throws. The hunk is in
  `docs/prds/agent-platform/landing/lane-unfinished-runs-2026-09-23.md`. Until
  that lands, a thrown request still leaves members open. Now only the
  failed-tests family reads unknown, not every family.
- Still open: members left open by a JVM exit. Recovery closes turns only
  (`seon.cluster/recover-runs!`).
- Still open: the readiness schema should be the union
  (`resources/seon/schemas/seon.boot.edn:114`).
