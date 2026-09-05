---
type: issue
status: resolved
severity: friction
tags: [issue, effect, sci, runtime, concurrency]
---

# Rule what bounds background work now that it is unarmed

## Problem

W2 made the interrupt arm a value that travels with the work
(`seon.sci.kernel/current-arm` + `adopt-arm`,
`src/seon/sci/kernel.clj:310-352`), and W1 wired it into flow's four
crossings. The **effect execution boundary's own thread hop was not wired**, and the
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
is the same shape as the ThreadLocal hole W2 closed — the effect execution boundary was simply
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

## OWNER RULING, 2026-08-08 night — config default, agent override, no clamp

Verbatim: **"config defaults and the agent can supply optional args for
tighter or more open limits. Great defaults and easy and intuitive
overrides."**

That is option 1's mechanism plus an agent-suppliable override that may go in
EITHER direction, with no clamp — trusted collaborators, per the no-hobbling
ruling. Implemented and proved the same night:

- **The dial.** `:seon.config.effect.background/time-limit-ms`
  (`resources/seon/schemas/seon.config.effect.background.edn`), shipped at
  600000 in `config/default.edn`. Ten minutes is an order of magnitude above
  the longest foreground capability bound (shell and web are 30 s), so a
  build, a large download, or a slow model call finishes on the default while
  a handler that never returns cannot hold one of the 64 io slots for the
  life of the process.
- **The override.** `my.background/background` takes an optional leading
  options map, so the surface reads
  `(background {:seon.effect/time-limit-ms 3600000} (my.web/fetch …))`. It
  merges into the same `:seon.effect/execution-options` the effect execution boundary already
  takes; the caller's value simply WINS, tighter or looser, exactly the
  elide-for-default/pass-to-override idiom the environment PRD names.
- **The arm.** `seon.sci.kernel/detached-arm` builds a FRESH arm at that
  limit — its own deadline, counters, and latch, travelled from birth,
  keeping only the submitting thread's SCI context so a nested evaluation
  inherits rather than being refused. `seon.effect/request*` puts it on the
  submission's environment under `:seon.sci.kernel/arm`, so flow's EXISTING
  `adopt-arm` carriage runs the work under it; `kernel/release-arm!` cancels
  the deadline task when the work settles early. No second limiting
  mechanism was added.
- **Unbounded is unrepresentable.** Absence of the config fact is a loud
  `:seon.effect/missing-background-time-limit` refusal before any receipt
  opens, and a non-positive explicit limit is
  `:seon.effect/invalid-time-limit`. There is no "no arm" path left.

**One class regression**,
`seon.effect-test/detached-work-is-bounded-by-its-own-limit-config-then-the-form`:
the config fact cuts an unbounded detached loop; an explicit tighter limit
wins over a generous config fact; an explicit looser limit wins over a strict
one (the handler enters interpreted code only after the config limit has
demonstrably latched, so a config win would cut it at its first entrance);
and both refusals are asserted. The pre-existing
`background-work-outlives-the-deadline-of-the-turn-that-started-it` still
holds the other half — the turn's deadline never applies — now asserting that
the work runs under an arm whose remaining deadline is its own.

Not vacuous (`tmp/background-bounds/liveness.clj`, one run): with the
mechanism, 9 of 9 assertions pass; with `detached-arm` neutered to return nil
the unbounded detached loop never settles and the test errors at its event
backstop.

**Live proof**, cluster `bounds` in the isolated root `tmp/bounds-root`,
2026-08-08:

- `(sleep 20)` submitted detached with `:seon.effect/time-limit-ms 2000`
  settled at `:seon.effect/duration-ms 2172` with `:my.shell/time-limit` —
  "the foreign process was terminated when its evaluation reached its time
  limit". Before this change the submission carried no arm at all, so
  `kernel/deadline-remaining-ms` was nil in the shell handler and the child
  would have run to the shell's own 30 s bound.
- `(sleep 3)` submitted detached with NO explicit limit settled normally,
  exit 0 at 3126 ms, under the cluster's 600000 ms default.

## Superseded question — what SHOULD bound background work?

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
   capability owner must declare a bound, and the effect execution boundary refuses one that does
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
