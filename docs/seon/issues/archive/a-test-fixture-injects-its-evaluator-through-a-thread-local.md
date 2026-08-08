---
type: issue
status: resolved
severity: friction
tags: [issue, testing, flow, runtime]
---

# A test fixture injected its evaluator through a thread-local

## Problem

`seon.cluster.turn-test` was red: 16 failures and 3 errors across five tests
(`a-combined-evaluation-projects-every-terminal-receipt-datom`,
`a-completing-disposition-closes-in-the-terminal-transaction`,
`a-run-prompts-from-its-opening-database-value`,
`a-waiting-disposition-frees-the-agent-and-keeps-its-note`,
`delivery-rows-and-refusal-facts-share-the-terminal-transaction`).

The faces all said one thing. The committed receipt carried
`{:seon.cluster.eval/result-edn "1", :seon.cluster.eval/result-size 1}` —
the ROOT VALUE of the fixture's stub — rather than the disposition value the
test bound. With no disposition in the result, the run did not close at
`:resume`, so the situation sequence read `[:open :call :resume :close]`
where the test asserts `[:open :call :resume]`, the terminal transaction
count fell from 1 to 0, and every downstream assertion about the terminal
commit failed somewhere other than the actual defect.

## Cause

The fixture injected its evaluator's answer through a DYNAMIC VAR
(`turn_test.clj`'s `*evaluation*`, read by `fake-evaluate`), and the run loop
runs every evaluation on a different thread:
`seon.cluster.loop/submit-evaluation!!` (`src/seon/cluster/loop.clj:335-353`)
hands the work to `seon.flow/submit!!`, which dispatches it onto a compute
thread.

That thread-local used to arrive only because `submit!!` wrapped the work-fn
in `bound-fn*` — added on 2026-07-29 by `03e7bd11b` ("Preserve eval bindings
across submission") for exactly this purpose. `226da97f8` ("Delete flow's
binding conveyance; detached submissions carry no arm") removed it, so a
submission's own data is now the only carrier across that crossing.

**That landing is correct and is not the defect.** Production's evaluator
depends on nothing conveyed: the work-fn establishes its own walk custody
INSIDE the submission (`seon.cluster.loop/resume-turn` wraps
`compiled-evaluate` in `seon.render/call-with-walk-context`, which binds on
the compute thread), and `loop.clj:338` is the only first-party `submit!!`
call site outside flow's own tests. What broke was the test fixture, which
pinned the deleted conveyance.

## Verification

Attribution was not inherited. Three independent pieces of evidence:

1. `git log -S bound-fn -- src/seon/flow.clj` returns exactly `03e7bd11b`
   (added, "Preserve eval bindings across submission") and `226da97f8`
   (removed) — the mechanism was introduced for this and deleted tonight.
2. The observed receipt is literally the Var's root value, which is what a
   thread that never received the binding reads.
3. The falsifier: replacing the thread-local carrier with a root-value
   carrier turns all 16 failures and 3 errors green with ZERO production
   change. If anything else were involved, that could not be true.

## Resolution

The fixture moved to a carrier the compute thread can see, and the class was
made unrepresentable rather than patched. `*evaluation*` is now
`injected-evaluation`, a plain (non-dynamic) Var injected with `with-redefs`,
which alters the root and is therefore visible from any thread. Omitting
`^:dynamic` means `binding` on it will not compile, so the thread-local shape
cannot be written back in; the comment above the Var names the crossing and
why.

The general property — that flow conveys nothing but the submission's data —
already has its own regression in `seon.env-test`'s carriage test, landed by
`226da97f8`. No second one was added here.

The full conversion of this fixture to the production evaluation path (the
test-infrastructure spec's hand-built-fixture inventory lists `turn_test`'s
graphs for deletion) remains for the test-infrastructure landing; this change
does not block or duplicate it.

## Evidence

- `seon.cluster.turn-test`: 49 tests / 339 assertions, 0 failures 0 errors,
  three consecutive runs.
- `seon.cluster.run-test` + `seon.cluster.loop-test`: 32 tests / 255
  assertions, 0 failures 0 errors.
- `bin/test --platform`: 69 tests / 369 assertions, 0 failures 0 errors.
