# Process tests: what runs only when asked (worktree session pure-tests, 2026-10-23)

A run that names nothing (bin/prove, bin/test with no namespace, on-change runs) leaves out every
test and bin/check step whose reach includes a process var (seon.program/process-vars: in-agent,
shell sh, lib fork!, test-store binary-sh), classified by seon.program/reach-index's :process?
(test-runner/automatic). Owner: "Tests shouldn't be running agent turns. Not automatically run
tests at least."

## Before and after, from recorded durations (the 2026-10-23 full run on jolt/pure-tests, load 12-41)

- before: 443 tests, 917 s of test time (486 s wall in 4 shards) + 33 check steps, 1092 s of
  step time, serial: ~26 min.
- after: 353 in-process tests, 135 s of test time (~35-45 s wall in 4 shards, + ~15 s plan)
  + laws (19 s) and lmdb-kill (0.3 s): ~1-2 min. mg-check and contracts are left out too: their
  mode runs under run-check, which can boot a root, so the walk reaches fork!.
- On this branch (jolt/dry-run + this change) the plan reads 454 tests: 122 process tests and
  32 steps left out, 332 tests and 2 steps automatic (bin/test --plan, 2026-10-23).

## The process tests, by namespace (recorded ms, from the classification over jolt/pure-tests)

- seon.agent-test: 15 tests, 187 s
- seon.program-test: 14 tests, 47 s
- seon.db-test: 11 tests, 6 s
- seon.root-test: 9 tests, 49 s
- seon.server-test: 6 tests, 49 s
- seon.reconcile-test: 6 tests, 0 s
- seon.source-test: 5 tests, 361 s
- seon.proofs-test: 5 tests, 6 s
- seon.procs-test: 5 tests, 0 s
- seon.repl-test: 4 tests, 57 s
- seon.lib-test: 3 tests, 0 s
- seon.main-test: 2 tests, 0 s
- seon.check-test: 2 tests, 9 s
- seon.render-test: 1 tests, 5 s
- seon.problems-test: 1 tests, 0 s
- seon.context-test: 1 tests, 0 s

The walk was checked against a hand inventory: every test it leaves in (agent-, repl-, check-test)
runs in-process in 0-400 ms; it misses none.

## What the slowest uniquely cover, and the recorded-data unit test that would carry the logic

| test or step | s | uniquely covers | unit test from recorded data (task seon.proofs/turn-logic-from-recorded-data) |
|---|---|---|---|
| source-test/a-restart-catches-up-and-a-new-generation-resumes | 351 | build! -> bin/root next on the same store; catch-up after SIGKILL | the catch-up as load-all! over a db holding two merges (no root); the build stays end to end |
| agent-test (15, in-agent) | 187 | an agent's lifecycle through a real root: fork, ends with its root, interrupts, failure marked, lease | the agent-proc step from recorded events (procs-test style), the reconciler's end from fact states |
| repl-test (4, in-agent) | 58 | the help page and the templates' forms run in a real agent; interrupts | the forms evaluated by seon.repl's handler in process (as a-reply-item… does) |
| root-test (9) | 50 | a binary root boots, resumes, follows its tree, its threads | stays end to end (it is the binary) |
| server-test (6) | 49 | the server's answers through a real root | seon.server/respond on recorded requests in process (as a-line-opening-a-string-key-is-mcps does) |
| program-test (14, binary-sh / sh) | 48 | digests across checkouts, tests reading the tree | one import per process, shared (the digests from a db, no shell) |
| e2e steps, swarm, loop, resume | 25-250 each | a task graph worked end to end | the reconciler's step from a recorded fact state; the merge from two dbs; the context from a recorded basis |
