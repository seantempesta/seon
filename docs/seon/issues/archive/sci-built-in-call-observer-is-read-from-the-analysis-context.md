---
type: issue
status: resolved
severity: friction
tags: [issue, sci, runtime]
---

# Read the SCI built-in call observer from the runtime context

## Resolution (2026-08-07, Phase 1 W3)

Fixed in the maintained fork on branch `seon-env-hook`, commit `af8a5fb`
("Read the built-in call observer from the runtime ctx"), branched from pin
`2db3358c`; branch head `f934044`. The superproject pin bump is the
orchestrator's, deliberately not staged by the lane. Evidence and suite
results: [env-phase1-w3-notes-2026-08-07.md](../../prds/sci-execution-runtime/research/env-phase1-w3-notes-2026-08-07.md).

The fully qualified symbol still resolves at analysis time (it is a property
of the callee), but the observer is read from the node's runtime `ctx`. The
observation node is therefore generated whenever the callee is a built-in Var
rather than only when an observer was installed at analysis time — otherwise a
node analyzed without an observer would stay unobservable in every fork.
Acceptance met by `sci.interop-test/built-in-call-observer-runtime-ctx-test`:
one node analyzed once, executed under two forks carrying different observers
and then under a fork with none, each notifying only its own. Fork JVM suite
green (393 tests / 1470 assertions / 0 failures on Clojure 1.10.3 and 1.11.1);
CLJS unchanged from the pin.

**The `:interrupt-fn` half of this issue is withdrawn as misdiagnosed.**
`fns/fun` is called from inside the fn-node's body
(`reference-code/sci/src/sci/impl/analyzer.cljc:368-370,400,417-419`), so its
`ctx` is the RUNTIME context at the moment the `fn` form is evaluated — the fn
object's creation context, not an analysis context. An interpreted fn is later
invoked as a plain `IFn` with no ctx argument, so no other context exists at
invocation to read instead. That is sci's load-bearing "the ctx travels with
the code" design, the same mechanism as Phase 0 finding 2 (a fn created
against the base ctx pins the base environment). Threading a caller ctx into
every interpreted invocation is a separate and much larger design question,
not a corollary of this fix.

Related, found while verifying and left open for a separate fork issue:
`sci.interop-test/built-in-call-observer-test` already fails under
`script/test/node` at the pin. Our `:built-in-call-observer` feature is
JVM-only in practice, because CLJS reaches a Var through the var-deref path
where `return-call` never sees a Var callee.

## Problem

In the maintained SCI fork, `:built-in-call-observer` is lifted off the
context during ANALYSIS and closed over by the generated call node. Every
later execution of that node notifies the observer that was present when the
node was analyzed, on whatever fork is actually executing.

Today analysis and execution share one fork in Seon, so the wrong observer is
never observed. The moment anything caches or shares analyzed nodes across
forks — the stated direction for cluster-wide program-graph reuse — a node
analyzed under fork A notifies fork A's observer while running under fork B.
The same shape would silently poison any new call-time hook copied from this
one, which is why it must be fixed before the `seon.env` call-preparation hook
work lands rather than after.

## Evidence

- `reference-code/sci/src/sci/impl/analyzer.cljc:1719` —
  `(let [observer# (:built-in-call-observer ~'ctx) ...]` sits in
  `return-call`'s own `let`, i.e. it evaluates during analysis. `ctx` there is
  the analysis context, not the node's runtime `ctx` argument.
- `reference-code/sci/src/sci/impl/analyzer.cljc:1745-1750` — the notifying
  node closes over that analysis-time `observer#`; it never re-reads the
  context it is handed at eval time.
- `reference-code/sci/src/sci/impl/types.cljc:264-273` — `->Node` expands to
  `(reify Eval (eval [this ctx bindings] body))`, so a runtime-ctx read inside
  the body is available and free; the observer simply does not use it.
- `reference-code/sci/src/sci/core.cljc:331-337` — `fork` produces a distinct
  immutable context value, so two forks legitimately carry different
  observers.
- Named as a present hazard by
  [environment-mechanism-sci-2026-08-07.md](../../prds/sci-execution-runtime/research/environment-mechanism-sci-2026-08-07.md)
  (section 4, obstacle 3; invariant 4 in section 5(d)) and carried into the
  sealed design as scope item 3 of
  [seon-env-prd-2026-08-07.md](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md).
- The corrected shape is demonstrated on branch `seon-env-hook-probe` of
  `reference-code/sci` (commit `a072c8e`), where the new
  `:call-preparation-hook` is read inside the node body from the runtime
  `ctx`; the fork's own suite stays green (386 tests, 1443 assertions, 0
  failures). See
  [env-phase0-runtime-ctx-hook-2026-08-07.md](../../prds/sci-execution-runtime/research/env-phase0-runtime-ctx-hook-2026-08-07.md).

`:interrupt-fn` at `reference-code/sci/src/sci/impl/fns.cljc:40,64,152` has the
same analysis-time-capture shape and belongs in the same fix.

## Owner

`reference-code/sci/src/sci/impl/analyzer.cljc` (`gen-return-call`), in the
maintained fork, alongside the `seon.env` hook work.

## Acceptance

One analyzed program, two forks carrying different observers, executed under
each in turn: each execution notifies the observer of the fork it is executing
under, with no re-analysis between them. The same assertion holds for
`:interrupt-fn`. The fork's own test suite stays green.
