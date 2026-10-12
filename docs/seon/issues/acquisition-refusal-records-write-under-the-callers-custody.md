---
type: issue
status: closed
severity: defect
tags: [issue, agent, sci, custody]
---

# Acquisition refusal records write under the caller's custody

`seon.cluster.agent/acquire-context!` (src/seon/cluster/agent.clj, about line
986) calls `sci.eval/acquire!`. That call records rows the program cannot
install as errors by transacting into the NEW branch's connection. It does
this under whatever `seon.db` custody the caller holds. A caller inside another
branch's custody therefore gets a refused write, and acquisition throws
"Program acquisition could not record its refusals." A nested `seon.test/run`
inside `seon.test-support/with-database` is such a caller.

This was observed 2026-09-25 at the REPL (lane fix-test-failure-facts-opus).
The write named branch `:agent-67f38471eaf8` while ambient custody held
`:agent-90a0845598e2`. The probe used a hand-built parent handle off default's
head. That head holds rows that fail `install-row!`, and those failures are
what triggered recording. An ordinary member world had no refusals, so the
verify runs never reached this path.

The branch-assignment write a few lines earlier already uses
`(db/with-branch {} ...)`. The refusal record belongs to the acquired
branch too, so it should be written under that branch's custody. It should
not inherit the caller's.

## Closed 2026-09-25 by c1e40b7e8

`acquire-context!` now runs `sci.eval/acquire!` under the acquired branch's
own custody. I verified this at the REPL on default 6ab6c29c, lane
fix-test-failure-facts-opus. Under custody of `:agent-c82a20d87134`, I
committed an uninstallable `:agent` row, `(defn boom [] (no-such-fn-xyz 1))`.
I then acquired an isolated child, `:agent-602aca407681`, from that commit.
Acquisition did not throw, and the child's database held the recorded
`seon.sci.eval/install-row!` error.
