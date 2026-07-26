---
type: issue
status: open
severity: correctness
tags: [issue, agent, database, flow]
---

# The eval deadline interrupt can be swallowed by an in-flight database call

## Problem

The SCI `time-limit` is delivered exactly once through `:interrupt-fn`, as a
`Thread.interrupt` on the eval-pool worker, and it is observed only through
`Thread.isInterrupted`. A database round-trip in flight at that moment consumes
the interrupt and clears the flag, so no later `fn` body entrance calls
`interrupt!` and
the form runs to completion past its deadline.

Path:

- `seon.host.invoke/arm-deadline!` schedules ONE watchdog task and installs the
  platform interrupt predicate `#(.isInterrupted ^Thread worker)`
  (`src/seon/host/invoke.clj:37-44`). There is no re-delivery.
- the current `seon.host.guard/check-holder!` reads that predicate through
  `:interrupt-fn` at every SCI `fn` body entrance and
  calls `stop!` only while it returns true (`src/seon/host/guard.cljc:202-204`).
- An agent `seon.db/transact!`/`query` blocks the same worker in
  `(deref task (long deadline-ms) timeout)` inside
  `seon.db.host/invoke-member!` (`src/seon/db/host.clj:709`). `deref` on a
  `java.util.concurrent.Future` delegates to `.get`
  (`clojure.core/deref-future`, clojure-1.12.3.jar `clojure/core.clj:2315`),
  which throws `InterruptedException` and — per the JDK interrupt contract —
  clears the thread's interrupted status.
- `invoke-member!` catches `Throwable` and returns `{::call-outcome :failure}`
  (`src/seon/db/host.clj:722-732`); `call!` then retries the same request on a
  fresh pool member (`src/seon/db/host.clj:811-838`). The retry proceeds on a
  now-uninterrupted thread.

After that point `(.isInterrupted worker)` is false forever, so every later
`fn` body entrance occurs. Only `time-limit` can stop the form.

The eval is still *reported* as interrupted, because
`seon.host.eval/finish-evaluation!` reads the separate
`::session/interrupt-fired?` atom (`src/seon/host/eval.clj:189-201`) — but that
check runs only AFTER the form returns. The observable defect is unbounded
overrun of `:seon.config.guard/deadline-ms`, not a wrong envelope.

## Falsifiable failure

Evaluate a form that performs one `seon.db/query` and then spins in a long pure
loop, with `:seon.config.guard/deadline-ms` set below the query latency plus
loop time, with `time-limit` set high enough not to fire. Expected
under the current code: the batch returns `:seon.eval/interrupted? true` only
after the loop finishes naturally, with wall time far exceeding the deadline.

## Owner and acceptance

Owner: `src/seon/host/invoke.clj` (deadline arming) with
`src/seon/host/guard.cljc` (current `:interrupt-fn` implementation).

Acceptance: the deadline is observed through state that a blocking call cannot
consume — the existing `::session/interrupt-fired?` atom is already that state,
so `install-interrupted!` should read it (optionally OR'd with
`.isInterrupted`) rather than the thread flag alone. Regression: a form that
issues a database call and then loops must stop at the deadline, and the
existing "interrupt during a pure loop" behavior must be unchanged.

Related standing rule: `AGENTS.md` §Runtime contracts — a deadline is a
last-resort backstop whose firing is itself a bug report; a backstop that can be
silently consumed is worse than none.
