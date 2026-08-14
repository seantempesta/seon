---
type: defect
status: open
severity: friction
tags: [sci, instrument, repl]
---

# cluster-ctx's 1- and 2-arities are uncallable under instrumentation

`seon.sci.eval/cluster-ctx` ([src/seon/sci/eval.clj:1611-1625]) delegates
`([db])` → `(cluster-ctx db nil)` and `([db connection])` →
`(cluster-ctx db connection nil)`. The instrumented 3-arity contract
requires a real `:seon.db/connection` and a real
`:seon.sci.eval/projection-state`, so the nil-passing delegations are
refused: `(cluster-ctx db)` fails "must be a live unreleased Datahike
connection" and `(cluster-ctx db conn)` fails "must hold one immutable
replacement environment". Observed live 2026-08-14 (host prepl, jvm
mode) while probing the render walk; only the explicit 3-arity with
`(projection-state db projection)` works. Either the declared function
schema should permit the nil-delegation shapes, or the delegating
arities should construct real defaults — as written, the advertised
arities lie. Note production callers (`bootstrap_drive.clj:148`,
`cluster/curate.clj:178`) use the 2-arity, so either they run
un-instrumented (worth checking) or they hit this same refusal.
