---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test-fixture, instrumentation, documentation]
---

# Cold SCI fixture captures core callables before canonical arming

Batch 123 B (`tmp/orchestrator/gate-results/batch-123b.log`, HEAD
`312f60560`) refutes the earlier missing-cluster-config diagnosis. Seeding
panic configuration did not fix it: the bad recipient still reaches
`seon.cluster.message/send!` instead of refusing at `my.message/send`.

The stored declaration is complete and core-admitted. `:my.message/to` is a
nonempty string; `:my.message/send-request` requires it. The cold worker's
`worker-command-loop!` constructs `seon.test-support/database-base` before
`serve-worker-commands!` receives its initialization command and arms the
program. Fixture construction acquires SCI, whose `sci/copy-var*` copies
`@clojure-var` (`reference-code/sci/src/sci/core.cljc:138`). Later arming
replaces the JVM root but cannot replace the cached SCI copy. The unarmed
outer callable delegates through the now-armed inner JVM Var.

Fast initialization arms first, then constructs the fixture. A canonical
fast diagnostic observed the same `:seon.instrument/var #'my.message/send`
on both JVM and SCI callable metadata and the complete stored contract.
No self-arming was performed. The regression now scopes instrumentation
state and explicitly requires that acquired wrapper, alongside exact
function identity, documentation equality, example text, and no writes.

The root repair is to call the existing `initialize-contracts!` before cold
fixture acquisition. `src/seon/test/runner.clj` is concurrently edited by
the stage-2 lane; the assignment's held-file boundary leaves it untouched.
The exact pending hunk is recorded in
[message-documentation-arming-pending-2026-09-17.patch](../../prds/steward-platform/research/message-documentation-arming-pending-2026-09-17.patch).
This issue remains open until that hunk lands and the orchestrator's cold
regression passes. No schema or documentation-content change is needed.

See [the landing note](../../prds/steward-platform/research/message-wake-model-2026-09-17.md)
for commands, measured results, and the review boundary.
