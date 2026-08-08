---
type: issue
status: open
severity: friction
tags: [issue, observability, sci]
---

# An unmatched print face throws "No matching clause:" and names nothing

## Problem

`seon.sci.admit/semantic-value` (`src/seon/sci/admit.clj:433-462`) dispatches
on `::print/face` with a bare `case` and no default arm. Handed a value whose
face it does not know — including a value that is not a print node at all,
so the face key is absent and the dispatch value is `nil` — it throws:

```
java.lang.IllegalArgumentException: No matching clause:
    at [seon.sci.admit$semantic_value invokeStatic admit.clj 439]
```

The message ends after the colon. It names no face, no key, no node, and no
caller. Observed live while triaging `seon.cluster.turn-test` on 2026-08-08:
the real defect was elsewhere entirely, and this exception was the only face
one of the three errors presented — a diagnostic that cost reading time
instead of saving it.

This is the loud-failures ethos inverted twice over. The refusal is neither
typed nor flat (it throws where the surrounding code trades in
`:seon.error` values), and it does not name what was missing.

## Wanted

An unknown or absent face returns a flat `:seon.error` value naming the face
it saw (including its absence) and the node's own key set, rather than
throwing a `case` miss. A `case` over a closed set of faces with no default
arm is the writable shape of this class; a dispatch that cannot miss silently
— or that refuses loudly with the face in hand — removes it.

## Acceptance

- `semantic-value` on a value with an absent or unknown `::print/face`
  returns a flat error value that contains the observed face and does not
  throw.
- One regression covers the class (absent face and unknown face), not one
  example per face.

## Owner

`src/seon/sci/admit.clj` — unassigned. Filed by the turn-test triage lane;
not fixed here because the file is outside this lane's boundary and the
finding is independent of the turn-test attribution.
