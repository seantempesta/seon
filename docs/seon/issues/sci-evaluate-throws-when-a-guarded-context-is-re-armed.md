---
type: issue
status: open
severity: friction
tags: [issue, sci, runtime]
---

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
