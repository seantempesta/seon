---
type: issue
status: resolved
severity: cleanup
tags: [issue, sci, admit, dead-code, shown-text, vocabulary]
created: 2026-09-16
---

# `seon.sci.admit/restorable-node` has no caller after the shown-text cut

## Observed

`src/seon/sci/admit.clj:602` still declares `restorable-node`, whose whole job
is to read a stored PRINT NODE back and decide whether the value survived it:

> Nil for a node that kept only a name, for an unreadable node, and for an
> evaluation that stored none.

`ff9507c1b` ("Keep private SCI objects in memory and store shown evaluation
text", 2026-09-08) retired stored print nodes. An evaluation now stores the
exact text its value renderer showed (`:seon.eval/shown`) and the live object
is interned in the agent's fork under the evaluation's handle
(`seon.sci.eval/bind-result!`, `src/seon/sci/eval.clj:519`). Nothing is read
back, so the question the predicate answers no longer arises.

Measured at HEAD `e0c9d8923`:

```
rg restorable-node src/ test/
src/seon/sci/admit.clj:602        (the declaration)
test/seon/render/transcript_test.clj:1199   (removed by this lane)
```

After this lane's repair of the transcript reds the declaration has ZERO
callers in `src/` and `test/`.

## Why it matters

It is a second, superseded answer to "is this value reachable again?" sitting
beside the ruled one (`bind-result!` plus `:seon.eval/shown`). A later reader
who finds it will believe evaluations still store restorable nodes, which is
exactly the drift AGENTS §2.5 forbids — git is the archive.

`opaque-result-faces` immediately above it (`src/seon/sci/admit.clj:596`) needs
the same check; it may have no other caller either.

## Wanted

Delete `restorable-node` (and `opaque-result-faces` if it is likewise
callerless) in one path-limited commit, after confirming with the program
graph rather than a text search:

```clojure
(seon.fn/callers (seon.db/db) "seon.sci.admit/restorable-node")
```

Out of scope for the transcript/web-debug reds lane, which owned the test
expectations rather than the admission namespace.

## Resolution (lane cut-l1, 2026-09-23)

`restorable-node` and `opaque-result-faces` (its only reader) are deleted from
`src/seon/sci/admit.clj`; `rg` over src, test, script, resources and the indexed
`:seon.fn/calls`/`:seon.fn/references` rows on `default` found no caller.
