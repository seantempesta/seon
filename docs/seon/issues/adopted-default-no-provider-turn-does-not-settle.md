---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, test, wave/agent-context]
---

# Adopted default leaves a no-provider turn open until its backstop

## 2026-09-09 loop-live investigation

The supplied full blob `8ab5942fe7698af07d0937ed6fac24c52b8b210fc51990e73b6f659de630fd05`
is 35,165 bytes. It names **Juniper**, `seon.turn/close-call`, and
`:seon.turn/run-closed`, reached from the empty no-provider reply branch.
It does not contain a Malli attribute-validation refusal. A fresh scratch
boot also produced `Batch refusal settlement was refused.`: its original
and refusal writes both failed `:seon.turn/run-closed` in
`seon.turn/receipt-settle-call` during Juniper's generated opening.

The live fixture directly calls `seon.cluster.agent/arm!` while the ordinary
armer also acquires graphs. The entry check and graph creation were not
serialized: concurrent calls returned different graphs for one routing
entry on the canonical armed fixture. Their independent completion permits
allow overlapping work on one turn. The old loop proof had no competing
armer and did not assert fault absence. It also accepted a no-provider
reply that closed by recording `:seon.cluster.reply/no-forms`.

The owning changes and measured proof are recorded in
[the loop-live landing note](../../prds/context-generation/research/loop-live-landing-2026-09-09.md).
No change to transaction validation is justified by either decoded fault.

The repair verifies ordinary message wakes on a fresh scratch boot and the
same JVM after adoption: 2,116.208291 ms and 1,172.484125 ms, two stored
evaluations each, exactly 20 → 19 turns-left, and zero faults, evaluation
errors, provider attempts, or unanswered wakes. The regressions fail against
unchanged production and pass with the repair. This issue remains open for
the earlier exact pre-reply stall and the owner's default acceptance;
the lane did not reproduce that 282-second timing or operate default's
lifecycle. Previously leaked graphs are not reachable through routing and
are not removed by this prevention change. The unrelated historical schema
reset and paid-trial acceptance below are not claimed complete.

On 2026-09-09 the context-blocks lane verified default's published and adopted
source commits both equal `6aa1dba5-4724-543f-8bc6-eb3d72cb5f1d`, then reseeded
Juniper through the shared fixture. PID 37586 was retained throughout.
The opening was correct: seven unique system evaluations, help first,
6,116 bytes, no errors, one instruction message. Its exact text is committed
in `docs/prds/context-generation/research/context-blocks-final-prompt-2026-09-09.txt`.

The live graph then opened ordinary turn `9fc9bc9ef8ad` at 22:21:44 UTC.
It had no plan digest, no provider attempt, and no evaluation. Its opening
reserved a turn, so live turns-left became 19 while the saved opening showed
20. At 22:26:26 the loop added message `a294b8e538ac` reporting
`:seon.await/backstop-fired`, interrupting that turn. Its fault id was
`0a1b76c6-619b-4672-bdd7-b5c17506fd38`. The changed inbox and settings appended
once; the whole opening was not duplicated.

A subsequent fixture installation returned
`:seon.render/unknown`, with message
`The renderer seon.error/render-faults-ai did not return: refused.`
The paid harness refused at preflight before any HTTP call; its target
artifact did not exist. No paid trial was silently retried.

The same ordinary-wake/empty-reply path passes in the fresh canonical loop
fixture, including close and answered-wake assertions. The final reader gate
passed 67 tests / 548 assertions; its platform rerun passed 83 / 490. A live
virtual-thread dump showed the completion-backstop waiter and parked Flow
procs, not an executing call-turn body. This is a verified live/fresh boundary,
not an attributed implementation cause.

Reproduce with the committed `juniper_fixture_2026_09_06.clj` installer in
an explicitly selected disposable cluster; inspect with
`reply_reader_trial_probe_2026_09_09/observe`. Both scripts live under
`docs/prds/context-generation/research/`. Do not add placeholder forms,
weaken the backstop, or hide the fault message to make the fixture look clean.

RESET NEEDED remains with `e915d2de0` and `e262fdca4`. The orchestrator owns
default's one batched refork; the lane requested it before the remaining
paid trial. Acceptance: after that refork, the shared fixture remains clean,
ordinary no-provider turns settle as in the canonical loop test, and the
single paid trial consumes the captured real prompt.
