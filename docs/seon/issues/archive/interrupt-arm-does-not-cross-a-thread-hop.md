---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, runtime, concurrency]
---

# Arm the time limit on the thread that runs the work

## Problem

`seon.sci.kernel`'s time limit — the ONE limit — governs only the thread that
called `arm`. Work handed to another thread from inside an armed evaluation
runs completely unarmed: no deadline reaches it, `sci.interrupt/interrupt!` is
never called for it, and its interpreted function-body entrances are not
counted. An agent-reachable path that crosses a thread therefore escapes the
only bound the runtime has, and the eval's recorded diagnostics report the
escape as zero activity.

This was the sealed
[seon.env PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)'s
top unprobed hypothesis ("also in scope", item 1). It is now probed and
confirmed.

## Evidence

Full method and raw output:
[env-phase0-fork-carriage-2026-08-07.md](../../prds/sci-execution-runtime/research/env-phase0-fork-carriage-2026-08-07.md).
Probe: `tmp/env-probes/env_probes/probe_b_interrupt_arm.clj`
(`clojure -M:dev`, no cluster).

Mechanism, from source:

- `src/seon/sci/kernel.clj:45-47` — the process guard's arm state is a plain
  `(ThreadLocal.)`, not an `InheritableThreadLocal`; virtual threads do not
  inherit it either.
- `src/seon/sci/kernel.clj:50-64` — the counter, the deadline test, and
  `sci.interrupt/interrupt!` (`:60`) all sit inside
  `(when-let [armed (.get thread-arm)] …)`, so the guard no-ops on any thread
  that was not armed.
- `src/seon/sci/kernel.clj:190-204` — `own-arm` sets that ThreadLocal on the
  calling thread only and schedules the single deadline task there.
- `reference-code/sci/src/sci/impl/fns.cljc:40,64,152` — sci lifts the
  `:interrupt-fn` off the ctx captured at fn creation, so the FUNCTION does
  cross the thread with the code. Only the ARM does not.

Measured, one shared ctx:

| Arm | Result |
|---|---|
| 20,000-iteration interpreted loop, armed, same thread | `:seon.eval/fn-entries 20002`, outcome `:ok` |
| identical workload awaited on a virtual thread | `:seon.eval/fn-entries 0`, correct value returned on a virtual thread |
| unbounded loop detached to a virtual thread, 300 ms limit | still running at 1500 ms (52,426 ticks), `isDone` false, never interrupted |
| control: same unbounded loop on the arming thread | interrupted at 310 ms, outcome `:time`, 3,108,328 entries |

## Owner

The `seon.sci.kernel` guard, with the `seon.env` Phase 1 constructor.

## State — RESOLVED

Landed 2026-08-07 in `src/seon/sci/kernel.clj` (commit "Carry the interrupt
arm with the work, not with the thread"). The arm is a VALUE: `current-arm`
hands the governing arm to whatever crosses a thread, `adopt-arm` installs it
where the work runs and restores the displaced arm on the way out, the
deadline is a latch on the arm value rather than a timer on the arming
thread, and the counters are `AtomicLong` so entrances accumulate wherever
they happen. Every acceptance criterion above is met and proven by
`test/seon/sci/kernel_arm_carriage_test.clj` — five consecutive green runs,
and the regression fails (detached loop still running at 3 s, 183,830 ticks)
when `adopt-arm` is neutered. Evidence:
[env-phase1-w2-notes-2026-08-07.md](../../prds/sci-execution-runtime/research/env-phase1-w2-notes-2026-08-07.md).

CLOSED 2026-08-07 by lane W1: the real crossings in `src/seon/flow.clj` now
carry the arm. `submit!` and `submit!!` capture `(kernel/current-arm)` onto
the submission's environment under the declared optional
`:seon.sci.kernel/arm` member (`resources/seon/schemas/seon.env.edn`), and
the io task, the compute task, and the terminal `complete!` callback each run
inside `kernel/adopt-arm` with it. Reading the arm from submission DATA is
what survives the Phase 3 `bound-fn*` deletions — nothing here depends on a
binding frame. An unarmed submitter carries no arm, which is ordinary
system-side work and never a refusal.

Submission-level proof:
`seon.env-test/a-submission-carries-the-submitting-threads-interrupt-arm` —
an io submission made from inside an armed extent delivers the submitting
thread's arm to the io thread (`identical?` at both halves, carried and
adopted), and an unarmed submitter delivers none. Evidence:
[env-phase1-w1-notes-2026-08-07.md](../../prds/sci-execution-runtime/research/env-phase1-w1-notes-2026-08-07.md).

## Acceptance criteria

- Work handed across a thread from inside an armed evaluation is governed by
  the same deadline as the evaluation that spawned it, or is refused at the
  crossing. The arm rides the ctx/fork and the submission the way the rest of
  the environment does; it is never read from a thread-local the receiving
  thread never inherited.
- `:seon.eval/fn-entries` accounts for entrances that occurred on behalf of the
  evaluation regardless of thread, or the diagnostic states explicitly that it
  is per-thread — a `0` that means "20,000 entrances elsewhere" is a lying
  diagnostic.
- `arm`'s re-entrancy rule states what happens when work from one fork lands on
  a thread already armed for a different fork.
- One class regression, graduated from the probe above, asserting the wanted
  behavior: a detached interpreted loop under a short limit terminates from the
  limit, not from a probe-owned flag.
