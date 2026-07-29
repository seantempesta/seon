---
type: issue
status: open
severity: blocker
tags: [issue, agent-runtime, run-loop]
---

# Boot recovery executes an unstarted plan suffix after interruption

## Problem

Boot recovery correctly marks a dead process's running receipt
`:seon.cluster.eval/interrupted-at`, but the recovered open plan then resumes at
the next ordinal. That executes work which had not started before the crash.

This contradicts the checkpoint crash contract: recovery may record the
interruption and release custody, but nothing in the interrupted run executes
after reboot. A later form can contain a capability request, so continuing the
suffix also makes a post-crash external call possible.

## Evidence

Seam re-audit attempt 6 created a held, planned two-form run in isolated cluster
`seam-reaudit6-20260729`, started receipt ordinal 0, and killed JVM process
`86782` with `kill -9`. Immediately before the kill:

- ordinal 0 was the run's only receipt and carried no terminal fact;
- the run was open, held, and planned;
- no form result or program-row effect existed; and
- 12 receipts belonging to already-closed runs had digest
  `6abdbc84eb7bd796e627bb10e679d0463b12a35401bd5fec08a3ad0e5cf32458`.

Reboot from the same isolated operator root recovered one run. The database
history then showed:

```clojure
[{:ordinal 0 :interrupted-at-tx 536871014}
 {:ordinal 1 :result-edn ":form-one" :result-tx 536871021}]
```

The second receipt did not exist before the crash. Boot created and settled it,
then closed the run. The 12 receipts from already-closed runs remained exactly
equal at the current basis and retained the same digest, so the failure is
narrowly suffix continuation rather than broad recovery corruption.

The behavior is explicit in the current mechanism:

- `src/seon/cluster/work.cljc:98-126` counts an `interrupted-at` receipt as a
  settled ordinal and selects the next missing ordinal;
- `src/seon/cluster/work.cljc:521-526` resumes an unheld planned run;
- `test/seon/cluster/loop_test.clj:326-337` requires ordinal 1 after ordinal 0
  is interrupted; and
- `test/seon/cluster/turn_test.clj:1651-1659` describes completion of the
  remaining planned work after recovery.

Those source and test contracts are the opposite of the checkpoint's
nothing-after-crash contract.

## Owner

`seon.cluster.work` owns post-recovery work derivation, with
`seon.cluster.run/recover-call` and the boot recovery caller defining the
terminal transition for an interrupted run.

## Acceptance criteria

1. Killing the JVM with one running receipt in a multi-form plan causes reboot
   to mark exactly that receipt `interrupted-at`, close and release the run,
   and retract the agent's run pointer.
2. No later receipt is created and no later form or capability request from
   that plan executes.
3. Already-terminal receipts, including receipts in closed runs, remain
   byte-unchanged.
4. The recurring crash test uses a suffix with an observable capability
   request and asserts zero post-reboot calls, not merely that the interrupted
   ordinal itself is not retried.
5. The cold-resume issue is reconciled: there is no longer a supported path
   whose correctness depends on reconstructing an interrupted plan's prefix
   context.

## Related

- `cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established.md` —
  describes context loss under the currently supported suffix-resume model.
- `docs/prds/sci-execution-runtime/research/checkpoint-audit-2026-07-29.md` —
  seam re-audit attempt 6 carries the full live proof.
