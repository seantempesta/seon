---
type: issue
status: open
severity: friction
tags: [issue, agent, runtime, test, class/n2, wave/causal-episode]
---

# Make the Context MVP drive prove its semantic exit

## Problem

`tmp/context-mvp-drive.clj` declares success after the target agent has at
least two closed runs, no unanswered trigger, and no immediately derived work.
That predicate can become true after the target sends work to another agent,
before the resulting cross-agent episode settles and without completing the
requested self-message loop.

## Evidence

The one authorized DeepSeek drive in
[[refusal-continuation-notes-2026-07-31]] captured three `context-mvp` turns
and then a fourth `root` turn. Attempt 3 sent a toolkit message to `root`
instead of to itself. The harness still printed `CONTEXT MVP DRIVE COMPLETE`:
its capture wrapper observed every agent's provider call, while its terminal
predicate observed only `context-mvp` quiescence.

This is a false-green proof harness, not a Ruling #22 runtime failure. The
three refused turns correctly reused one trigger and rendered their findings
in the next context.

## N2 disposition — 2026-08-11

The retained counterexample is the recorded attempt 3: a target-to-root
message made the old target-only quiescence predicate true, and the harness
printed `CONTEXT MVP DRIVE COMPLETE` without the required target-to-target
message or answer. The original `tmp/context-mvp-drive.clj` is no longer in
the tree, so there is no recurring test file in this member to repair in
place.

The honest recurring proof belongs on the production causal-episode query and
the scripted-completer tests under `test/seon/eval/`, both inside the live
`projection` lane's protected set. N2 therefore defers this member to that
named holder; recreating the deleted operator drive or hand-seeding its
expected messages would preserve the false premise.

## Owner

The live `projection` lane, through the existing causal-episode fact query and
its durable scripted-completer proof. Do not add a new runner or a
prompt-string oracle.

## Acceptance

- Scope captured calls to the target agent's durable runs.
- Derive the requested contracted function, target-to-target message, final
  answer, and closed causal episode from database facts.
- A target-to-other-agent delivery cannot satisfy the self-message exit.
- Terminal success implies no open or unanswered work in the complete causal
  chain; a semantic miss exits nonzero with the observed facts.
- Keep the provider drive explicitly operator-authorized; recurring proof uses
  a scripted completer through the ordinary test gate.
