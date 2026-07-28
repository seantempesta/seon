---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent-runtime, schema]
---

# my.run's error values are outside their own declared output schema

## Problem

`my.run/wait` and `my.run/complete` declare their output as
`[:or :my.run/wait :seon.error/value]` (and `[:or :my.run/completed
:seon.error/value]`), and on the error path they return

```clojure
{:seon.error/message "complete needs the reply text you want delivered, as a string."}
```

`:seon.error/value` is a CLOSED map that REQUIRES `:seon.error/kind`
(`src/seon/schema/error.edn`), so this value is not one. The declared contract
is therefore violated on every agent mistake — the most common path an
agent-facing function has. Nothing catches it today because `seon.instrument`
is not applied in the suites, and the loop only asks whether the value IS a
disposition (it is not, correctly), so the wrong shape is invisible until
something validates it.

The consequence is not a crash. It is that the one flat error shape the whole
system agreed on has an exception in the two functions an agent touches most,
which makes "every failure is a `:seon.error/value`" false as a general claim
and forces every consumer to handle two shapes.

## Evidence

`src/my/run.cljc:57-62` and `:74-79` return the bare-message map;
`src/seon/schema/error.edn:36` requires the kind. Asserted directly in
`test/my/message_test.clj`, `the-error-value-is-the-registered-one`, which pins
the correct behaviour for `my.message/send` (which does carry
`:my.message/no-recipient` / `:my.message/no-content`) and deliberately asserts
the `my.run` defect so that fixing it fails the test and points here.

## Owner

`src/my/run.cljc`, with `test/my/run_test.clj` and the assertion in
`test/my/message_test.clj`.

## Acceptance

Both functions return values satisfying `:seon.error/value` — a registered
`:seon.error/kind` in the `my.run` namespace plus the existing message — and
`my.run-test` asserts validity against `:seon.error/value` rather than only
`string?` on the message. The deliberate defect assertion in
`my.message-test/the-error-value-is-the-registered-one` is deleted in the same
commit, and this note moves to `archive/`.

## Resolution (2026-07-28)

Fixed by `932ff55fb` (`fix(run): disposition errors carry their kind`):
`my.run/wait` and `my.run/complete` now return values satisfying
`:seon.error/value`. The canary assertion in
`test/my/message_test.clj` fired exactly as designed (the one baseline
failure of the 2026-07-28 custody-revision gate) and is deleted with
this note's archival.
