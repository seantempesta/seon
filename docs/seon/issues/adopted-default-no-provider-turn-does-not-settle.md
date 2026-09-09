---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, test, wave/agent-context]
---

# Adopted default leaves a no-provider turn open until its backstop

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
