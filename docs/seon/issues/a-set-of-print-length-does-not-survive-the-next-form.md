---
type: issue
status: resolved
severity: friction
tags: [issue, repl, sci, render, print, class/p1]
---

# `(set! *print-length* 2)` does not survive to the agent's next form

## Evidence — 2026-09-07

Found by the `prompt-and-entity-merge` lane's end-to-end regression
`seon.render.transcript-test/`
`one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`,
which evaluates one reply through the ordinary path:

```clojure
(set! *print-length* 2)
(vec (range 40))
```

The stored evaluations, read back:

| ordinal | source | `:seon.print/length` |
|---|---|---|
| 3 | `(set! *print-length* 2)` | `2` |
| 4 | `(vec (range 40))` | absent |

and ordinal 4's response prints `[0 1 2 3 … 31 ...]` — the shipped default of
32, not the agent's 2.

## Cause

`seon.sci.eval/evaluate` opens a fresh binding frame for EVERY form
(`src/seon/sci/eval.clj`, `eval-form!`):

```clojure
(sci/binding [sci/ns namespace-object
              sci/out printed
              sci/err printed
              sci/print-length @sci/print-length      ; ← the ROOT value
              sci/print-level  @sci/print-level
              …]
  …)
```

`set!` mutates the frame's binding, and the `finally` correctly captures it
before the frame unwinds — which is why ordinal 3 stores `2`. But the NEXT
form opens a new frame seeded from `@sci/print-length`, the unchanged root,
so the agent's choice is discarded between forms.

## Why it matters

The agent's context is a REPL session (ruling 24-28, the repl-transcript
PRD): a form's `set!` of a dynamic that a REPL owns is expected to hold for
the rest of the session, exactly as it does at a `clojure.main` REPL. An
agent that sets `*print-length*` to keep a large value readable gets one
short response — the one that did the setting, whose value is the integer 2 —
and then full-width output again, with no signal that its instruction was
dropped. Silent discard is the class this project keeps meeting.

The storage half is now correct and proven: settlement records
`:seon.print/length` / `:seon.print/level` per evaluation
(`seon.cluster.run/evaluation-facts`), and both `seon.repl` and
`seon.render.transcript` print the stored value under them. Only the
carry-forward is missing.

## The fix

The turn owns the print bindings, not the form. `evaluate` should seed each
form's frame from the value the PREVIOUS form left — the same way it already
threads the ending namespace through `evaluate-sources` — or the bindings
should live on the turn's fork rather than being reopened per form. Either
way the authority is the session, and the per-form frame reads it instead of
re-deriving it from the process root.

Owner: `src/seon/sci/eval.clj` (`evaluate`'s `eval-form!`) together with
`src/seon/cluster/loop.clj`'s `evaluate-sources` loop.

## Resolution — 2026-09-07

The SESSION owns the print bindings, not the form. `fork-for-turn` gives the
turn's fork one print carrier seeded by `session-print-options` — derived from
the agent's own latest evaluation that recorded `:seon.print/length` or
`/level`, so the carry crosses turns without remembering anything — and
`evaluate`'s per-form `sci/binding` now seeds from that carrier instead of the
process root, writing the ending value back in the same `finally` that already
captured it for storage (`src/seon/sci/eval.clj`). Regression:
`seon.sci.eval-test/a-set-print-length-survives-to-the-turns-next-form`.

Still open, separately: settlement stores `:seon.print/length` only when the
value is an `int?`, so `(set! *print-length* nil)` — a deliberate choice of
unbounded printing — carries within the JVM's live session but is not stored,
and a later fork seeds without it (`src/seon/cluster/run.clj:192-197`).
