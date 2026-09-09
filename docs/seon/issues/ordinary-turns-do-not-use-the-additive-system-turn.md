---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, render, wave/agent-context]
---

# Connect ordinary turns to the additive system-turn algorithm

## Problem

The debug system-turn API exists, but ordinary turns never invoke it.
The prompt still walks current record content, and answering still requires
a provider attempt. Virtual turns therefore cannot prove PRD §14.

## Evidence

On 2026-09-09, `seon.loop-proof-test/virtual-loop-end-to-end`, on the canonical
armed fixture, measured a 400-byte stored opening and a 1,429-byte acquired
prompt. A message changed exactly one retained read; explicitly running the
system turn appended it but left its wake unanswered (`answer-t=0`). The
ordinary no-provider wake path appended only `(+ 1 1)`, without the changed
read. The isolated gate completed 1 test / 47 assertions / 6 failures / 0 errors.
One failure concerns the separately documented compaction decision. The final
fixture adds turn-0 identity and the negative system-only wake case and waits
for the ordinary reply's closure: **1 test / 51 assertions / 6 failures /
0 errors**, independently confirmed by the gate.

`src/seon/turn.clj:2033` owns system turns; `:open`/`:call` and `step` do not
call it. `latest-answering-turn-t` at line 2730 joins only successful attempts.
`src/seon/render/web.clj:2480` retains current neighborhood projections, and
`src/seon/render/walk.clj:880` walks the current record rather than just its
ordered evaluation entities.

## Owner

`seon.turn` owns preparation and answering. The existing history walk and
context acquisition must consume the same saved evaluations.

## Acceptance

The one recurring loop proof passes on real SCI, canonical Datahike, and armed
contracts: ordinary wakes append only changed reads before the virtual reply,
old prompt bytes remain an exact prefix, and only qualifying observed wakes
are answered. No provider request is used to establish these properties.

Full measurements and the compaction design question live in
`docs/prds/context-generation/research/loop-proof-landing-2026-09-09.md`.

## Resumed proof, 2026-09-09

The no-provider ordinary path now invokes `system-turn` before opening its
reply; prompt acquisition renders saved evaluation entities, and the reply's
post-opening plan transaction qualifies for answering. System-only turns
remain non-answering under the explicit resume instruction. The scoped gate
passes 135 tests / 1028 assertions, and default's real message probe appended
exactly inbox read then virtual reply, retained the prior bytes, and answered
wake 536871026 with basis 536871028. Compaction follows the provisional
same-forms/same-shown-values rule. The landing note owns all exact digests.

Keep this issue open for two integration boundaries beyond that virtual proof:
an explicit system-turn after successful attempted model history encountered
a `seon.render.transcript/render-history-ai` contract refusal; and the system
read preview's use of a separate SCI context has not proven refresh of read
forms depending on persistent private bindings. Neither is foreign lane
breakage. The green virtual proof must not be reported as a complete model
history or private-read custody proof.
