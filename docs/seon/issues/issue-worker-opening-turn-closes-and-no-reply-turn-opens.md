---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, turn, test]
---

# An issue worker's opening turn closes, and its reply turn never opens

## Symptom

A no-provider issue worker is started by `seon.issue/start!` and armed. Its opening
turn then runs and closes, and no second turn opens within 20 s. Meanwhile
`seon.turn/next-agent-work` answers `{:seon.turn.work/situation :open}` for that
worker. So the work is derived, but no turn proc takes it. Nothing reaches the
routing fault channel.

## Evidence (2026-09-23, default pid 90963, lane nsa-unblock)

Runs `346a5f85134a`, `4dcf98c29a2a` and `42d3721f4be0` of
`seon.namespace-agent-loop-test/an-issue-worker-started-on-its-candidate-branch-runs-there-alone`.
The diagnostics were taken at the reply-wait timeout, on C:

- The worker has one turn, with `:seon.turn.work/situation :generate` and opened-tx
  `536871565`. It carries `closed-tx` `536871582` and has no `:seon.turn/reply-size`.
- The worker has 8 evaluations. They are the opening's system sources: `(help)`,
  identity, `(my.issue/status …)`, plan, messages, settings, notes and errors.
- The unanswered wakes are one `:seon.issue/agent` wake at t `536871565`.
- `(turn/latest-answering-turn-t d worker)` is 0, and `turns-left` is 3.
- `(turn/next-agent-work d {:seon.agent/id worker})` returns
  `{:seon.turn.work/situation :open}`.
- Only the worker is armed. The fault channel is empty.

`seon.cluster.agent-arming-test/starting-an-issue-leaves-one-unanswered-wake-its-first-reply-answers`
fails the same way in run `3f9f2144a8aa`: its reply wait does not publish.

## Not the cause

The earlier opening fault was `seon.render/invoked` refusing `:seon.render/source-blocks`.
It is fixed in `src/seon/render.clj` by lane nsa-unblock, and no fault is observed any more.

## Where to look

- `seon.turn` no-provider path: `turn.clj:4507` `(freeze! {:seon.ai/text ""})` → `prepared []`.
  Check whether a turn with no sources closes without writing reply-size, and whether
  that close rewakes the turn proc (`more-agent-work?`).
- Related: [adopted-default-no-provider-turn-does-not-settle](adopted-default-no-provider-turn-does-not-settle.md).
