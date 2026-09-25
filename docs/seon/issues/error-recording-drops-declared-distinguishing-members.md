---
type: issue
status: open
severity: defect
tags: [issue, error, turn-loop]
---

# Error recording drops a declared error's distinguishing members

Verified 2026-09-25 (lane fix-config-boot-opus, census c class 10) by MCP
`eval_clj`, JVM mode, read-only, in namespace `fix-config-boot-scratch`.

`seon.turn/phase` (src/seon/turn.clj:3398-3414) returns a value carrying
`:seon.turn.loop/phase-failed true` and `:seon.turn.loop/failed-step`, with
meta `{:seon.error/declared-schema :seon.turn.loop/phase-failed-error}`. That
schema requires both members (resources/seon/schemas/seon.turn.loop.edn:87-98,
`{:seon.db/attributes true}`).

- `(#'seon.error/stored-observation projection source)` keeps both members.
- `(seon.error/recording cluster db :seon.turn.loop/phase-failed-error source now {...})`
  answers `:seon.error/value` with keys `(:seon.error/at :seon.error/id
  :seon.error/layer :seon.error/message :seon.error/operation
  :seon.error/signature)` only, and `:seon.error/fact` carries neither member.

So the loss is between `stored-observation` and the fact that `prepare`
(src/seon/error.clj:523) builds, used at error.clj:1528-1559. A declared
error's distinguishing members never reach its fact or its returned value.

Consequences: `seon.turn-loop-test/install-gate-failure-settles-the-started-receipt-as-a-failure`
(turn_loop_test.clj:958-961) and the other phase-failed turn-loop members fail on
the terminal value and stored shown text; turn.clj:3599 and :3795 branch on
`:seon.turn.loop/phase-failed` of values that may have passed through recording.

Fix at the owner: `prepare` keeps every member the declared schema names
(its `:seon.db/attributes` entries) in the fact, and `value` returns them.
