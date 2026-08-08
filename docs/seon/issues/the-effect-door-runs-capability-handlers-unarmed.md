---
type: issue
status: open
severity: friction
tags: [issue, effect, sci, runtime, concurrency]
---

# Rule what bounds background work now that it is unarmed

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

## Both halves repaired — 2026-08-08 (`f3b8eabda`, `226da97f8`)

**Half 1, foreground: done.** `dispatch` captures the requesting thread's arm
into this request's environment (`dispatching-environment`, scoping
`:seon.sci.kernel/arm` onto the carried environment) and the executor thread
runs the handler under `kernel/adopt-arm`. Two assertions, in
`seon.effect-test/the-door-runs-its-handler-under-the-requesting-evaluations-arm`,
both non-vacuous (neutering the adoption fails both):

- a handler that enters interpreted code records `>= 20000`
  `:seon.eval/fn-entries` on the requesting arm, where the previous code
  recorded 0 — the exact shape this note asked for;
- a handler running a GENUINELY unbounded interpreted loop under a 300 ms
  limit ends by SCI's own interrupt rather than running on.

**Half 2, background: ruled unarmed and asserted.** `flow/submit!` no longer
captures the submitter's arm at all; `submit!!` still does. The rule is the
surface's own meaning, not a flag: an AWAITED submission blocks its
submitter, so the submitter's limit is the work's limit; a DETACHED
submission exists to OUTLIVE its turn, so inheriting that turn's deadline
latch inverts what `my.background` is for. Asserted in
`seon.effect-test/background-work-outlives-the-deadline-of-the-turn-that-started-it`,
which waits on the OBSERVABLE (the submitting arm's own `reached` latch
closing) before releasing the handler into interpreted code, so an inherited
arm would cut it at the first entrance. `my.background`'s docstring now says
plainly that background work is not bounded by its run.

A third defect surfaced while repairing this one and was fixed in the same
commit: background handlers reached their worker with
`seon.effect/*request-context*` at nil, so every `my.shell/run` submitted in
the background failed on a nil connection
([issue](every-background-capability-request-loses-its-connection.md)). The
far side now rebuilds that frame from the value its submission carried.

## OWNER RULING NEEDED, morning — what SHOULD bound background work?

Deliberately not invented here. Background work is now unarmed, which is
correct about what it must NOT inherit and silent about what it must obey.
Today the only thing bounding a background capability request is whatever the
capability bounds itself with (`my.shell`'s time limit, `my.web`'s timeout);
a handler with no internal bound runs until the launcher stops.

The three shapes worth ruling between:

1. **Its own config-fact caps** — a `:seon.config.effect.background/*`
   time limit, armed at the submission with a FRESH arm rather than the
   turn's. Bounded by construction, one dial, and the arm mechanism already
   supports it (`kernel/arm` on the worker, nothing new).
2. **The capability's own bound only** — status quo made explicit: every
   capability owner must declare a bound, and the door refuses one that does
   not. Pushes the constraint to where the resource actually lives.
3. **Agent-supplied, capped** — the submitting form names a limit, clamped by
   a config fact.

Recommendation is (1): it is the smallest thing that makes "unbounded" an
unrepresentable state, and it reuses the arm rather than adding a second
limiting mechanism.

## Remaining, and NOT this note's repair

`dispatch` still wraps the foreground handler in `bound-fn`, because
`src/seon/shell/jvm.clj:290` and its peers still read
`seon.effect/*request-context*` rather than taking their environment as an
argument. Deleting that wrap before converting the readers would break the
foreground arm. It belongs to the seon.env Phase 3 reader conversion, where
`with-request-context` in `src/seon/effect.clj` is deleted with them.
