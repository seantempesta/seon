---
type: issue
status: open
severity: friction
tags: [issue, effect, sci, runtime, concurrency]
---

# Carry the arm across the effect door, and decide what a background arm means

## Problem

W2 made the interrupt arm a value that travels with the work
(`seon.sci.kernel/current-arm` + `adopt-arm`,
`src/seon/sci/kernel.clj:310-352`), and W1 wired it into flow's four
crossings. The **effect door's own thread hop was not wired**, and the
background half was wired in a way that needs an owner ruling.

Two halves, two problems.

**1. Foreground capability requests run unarmed.** `seon.effect/dispatch`
(`src/seon/effect.clj:315-327`) hands the handler to the process root's
`:io` executor as `(bound-fn [] (handler request effective))` inside a
`FutureTask`, and never calls `adopt-arm`. Every fs, shell, web, llm, and
db capability request therefore executes on a thread with no arm at all.
The consequences are the ones the arm work already named as defects
elsewhere: `:seon.eval/fn-entries` and the allocation sample under-report
whatever the handler does, and `interrupt!` cannot reach that thread. This
is the same shape as the ThreadLocal hole W2 closed — the door was simply
not on the list. (Host calls remaining sci's interruption ceiling is a
separate, documented fact; it does not explain an ABSENT arm, only a
non-interruptible one.)

**2. Background work now inherits the submitting turn's deadline.**
`flow/submit!` captures the submitter's arm unconditionally
(`with-current-arm`, `src/seon/flow.clj:193-206`) and `execute-work!` /
`io-terminal!` run the work under `adopt-arm`
(`src/seon/flow.clj:310-320,354-362`). `my.background` exists precisely so
work can OUTLIVE the turn that started it, yet a background effect
submitted from a turn now carries that turn's arm — so it observes that
turn's deadline latch (W2 deliberately stopped cancelling a travelled arm's
timer), counts its entrances into an arm whose `record` was already taken
at disarm, and keeps that arm's `AtomicLong`s and scheduled deadline task
alive for the crossing. Nothing observed breaks today because the
background work-fn is host code and sci's `interrupt!` only fires at an
interpreted fn entrance — but "correct only because the work happens not to
be interpreted" is exactly the silent-fallback shape the ethos section
rejects.

## Evidence

Read at `8e65e484c`, 2026-08-08:

- `src/seon/effect.clj:315-327` — the dispatch hop, `bound-fn`, no
  `adopt-arm`, no environment;
- `src/seon/effect.clj:505-522` — the background submission, which DOES
  carry `:seon.env/environment` and therefore picks up
  `with-current-arm`'s capture;
- `src/seon/flow.clj:193-206` — capture is unconditional whenever the
  submitting thread is armed; there is no "detached, do not adopt" case;
- `src/seon/sci/kernel.clj:240-250` — `stop!` no longer cancels a travelled
  arm's deadline task, by design.

Related but distinct, and correctly out of scope here: the foreground path
blocks the armed thread in `.get`, so a hung handler outlives the time limit.
That is the documented host-call ceiling, not this defect.

## Owner

`seon.effect/dispatch` for half 1. Half 2 is an owner ruling on
`seon.flow/submit!` + `my.background`: either background work is
deliberately governed by the turn that launched it (say so in
`with-current-arm`'s docstring and in `my.background`'s), or a submission
declares itself detached and carries no arm.

## Acceptance criteria

- `dispatch` runs the handler under `kernel/adopt-arm` with the arm captured
  on the calling thread, so a capability request's entrances and allocation
  are attributed to the evaluation that made it. One regression: a handler
  that enters interpreted code records ≥ N entrances on the caller's arm
  where the current code records 0 — the exact non-vacuous shape
  `test/seon/sci/kernel_arm_carriage_test.clj` uses.
- The background semantics are recorded as a ruling and asserted by one
  test: either a background submission's work is cut at the submitting
  turn's limit (and `my.background`'s docstring says so), or it carries no
  arm and the turn's `record` is unaffected by anything the background work
  does after disarm.
