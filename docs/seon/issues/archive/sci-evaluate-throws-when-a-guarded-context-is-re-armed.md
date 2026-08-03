---
type: issue
status: resolved
severity: friction
tags: [issue, sci, runtime]
---

## Resolution (2026-08-03, `04fe5f247` + `db0d78368`)

The cause was not a missing catch: `evaluate` and `kernel/invoke` each
carried their OWN arming rule, and only one of them was written to survive
a re-entrant call. `seon.sci.kernel/arm` is now the single rule for both
entrances — an identical context inherits the governing arm and its
deadline, a different context is refused — and `evaluate` arms INSIDE its
boundary so that refusal leaves as an ordinary flat value.

Falsified before, verified after, on the live default cluster JVM:

- re-entrant `evaluate` on an armed context returned `3` where it
  previously threw `:seon.sci.kernel/already-armed`;
- a nested evaluation asking for a 600,000 ms limit under an outer 50 ms
  arm was cut at 54 ms — the previous throw hid a real hole, because
  nested work that did NOT throw would have restarted the clock;
- a foreign context on an armed thread returns
  `:seon.sci.kernel/already-armed` as a value, with the message the run
  loop reads (a preserved refusal used to store a nil there).

Acceptance, item by item:

1. **Done.** Regressions `a-re-entrant-evaluation-inherits-the-governing-arm`,
   `an-inherited-arm-keeps-the-governing-deadline`, and
   `a-foreign-armed-context-is-refused-as-a-value` in
   `test/seon/sci/eval_test.clj`.
2. **Done, by refusal rather than by relocation.** `sci/fork` still shares
   the guard, so the invariant chosen is the loud one: a forked context is
   not `identical?` to its parent and is refused on an armed thread. The
   third assertion of `a-foreign-armed-context-is-refused-as-a-value` pins
   it, and `kernel/arm`'s docstring states the rule.
3. **Already covered** by `agent-context-exposes-no-concurrency-capability`,
   which proves the base context exposes no class that can create or carry
   thread work — the dependency the per-thread guard rests on.

# A re-armed SCI context throws out of `evaluate`, and `sci/fork` shares its guard

## Problem

`7ed006f18` moved the interrupt guard from a per-evaluation closure to
one stable per-context `:interrupt-fn` whose armed state lives in a
`ThreadLocal` (`src/seon/sci/eval.clj`, `interrupt-guard`/`fork`/`arm`).
The refactor is right — it is what keeps a previously acquired function
using the current evaluation's limit — but it introduced three
properties nobody tested, two of which contradict the namespace's own
stated contracts.

Falsified live (probe retained at `tmp/audit-0731/probe6.clj`,
`clojure -M:dev:test`):

1. **`evaluate` throws.** Arming a guarded context and then calling
   `evaluate` with that same context on the same thread throws
   `ExceptionInfo` carrying
   `{:seon.error/kind :seon.sci.eval/already-armed}`. The namespace
   docstring states "NOTHING THROWS. Every failure is a flat
   `:seon.error` value carried inside an ordinary evaluation map …
   because there is nothing to catch." A re-entrant evaluation is a
   programmer error, but it must leave as a value like every other
   failure at this boundary, or the docstring is wrong.

2. **`sci/fork` silently shares the guard.** `sci/fork` is
   `(update ctx :env …)` (`reference-code/sci/src/sci/core.cljc:318-323`),
   so it preserves every other key. Measured: a `sci/fork` of a
   `seon.sci.eval/fork` context has an `identical?`
   `::interrupt-guard`, therefore the same `ThreadLocal`. Forking a
   run's context and using both on one thread cannot work, and nothing
   says so.

3. **The limit no longer applies off the arming thread.** The
   `interrupt-fn` returns immediately when the current thread has no
   armed state, so interpreted code running on any other thread has NO
   time limit. Today this is unreachable and was verified so:
   `future`, `future-call`, `pmap`, and `Thread.` are all unresolvable
   in the base context ("Unable to resolve symbol: future", etc.). The
   guard's correctness now silently depends on that, and nothing
   records or tests the dependency — the day a threading symbol is
   admitted to the base context, the only limit in the system becomes a
   no-op with no failure signal.

The direction the seam was most suspected in is SAFE and was measured:
a second guarded context arms and evaluates cleanly on a thread where
another context is already armed, so a renderer evaluation on a turn's
own thread does not collide with the run's guard.

## Acceptance

- `evaluate` returns the re-entrancy refusal as a flat `:seon.error`
  value; `arm` may still throw into core code, but the agent-facing
  operation does not. One regression asserts a re-entrant evaluation on
  one context yields an error value, not a throw.
- `fork`'s docstring names `sci/fork` as unsafe over a guarded context,
  or `fork` stores the guard where a `sci/fork` cannot copy it. One
  regression asserts the chosen invariant.
- One recurring proof that the base context exposes no way to start a
  thread, stated as the guard's dependency — so admitting such a symbol
  fails the gate rather than silently removing the time limit.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`
