---
type: issue
status: open
severity: friction
tags: [issue, flow, runtime, testing]
---

# Carry the submitting environment across the IO half of the work launcher

## Problem

The two halves of one work launcher have OPPOSITE binding semantics.
`seon.flow/submit!!` wraps compute work with `bound-fn*`, so compute work runs
with the submitter's dynamic bindings. `seon.flow/submit!` wraps only the
completion callback and never `::work-fn`, so IO work runs with none of them.

This is not a schema problem. EVERY ambient environment value in Seon rides the
same dynamic-binding carrier, so they all drop together — probed and confirmed
for the schema declaration population, `seon.db/*conn*` ("the current cluster's
live branch connection"), and `seon.effect/*request-context*` ("the current
evaluation's durable identity"). Every capability request that crosses the
guarded door on `:io` — fs, web, llm, db — therefore runs with no cluster
identity, no declarations, and no request identity. Under one cluster this is
invisible because the process-wide fallback happens to be right, which is why it
survived.

## Evidence

- `src/seon/flow.clj:673` — `submit!!` does `(bound-fn* work-fn)`.
- `src/seon/flow.clj:618` — `submit!` does `(bound-fn* complete!)` only; the
  submission's `::work-fn` is used raw at `:347-366`.
- `src/seon/db.clj:65-67` and `src/seon/effect.clj:26-28` — the other two ambient
  values on the same carrier.
- `tmp/isolation-probes/probe_work_launcher_binding.clj` — deterministic FAIL
  with two real launchers and one fully bound submitter:
  compute work saw `{:schema-declarations true :ambient-connection true
  :effect-request-context true}`; IO work saw all three `false`.
- The same probe confirms launcher INDEPENDENCE is fine: stopping a peer
  launcher over the shared process-root executors left the other accepting and
  completing work.

Full audit:
[parallel-isolation-audit-2026-08-07.md](../../prds/sci-execution-runtime/research/parallel-isolation-audit-2026-08-07.md).

Dependency grounding in core.async's own source (2026-08-07):
[environment-mechanism-flow-2026-08-07.md](../../prds/sci-execution-runtime/research/environment-mechanism-flow-2026-08-07.md).
Flow conveys NO bindings anywhere — its one off-thread hop is
`futurize`'s bare `#(apply f args)`
(`reference-code/core.async/.../flow/impl.clj:29-36`), used for the proc run
loop (`:323`), each `:compute` transform (`:258-260`), and even `inject`
(`:197`). core.async conveys bindings only in `thread-call`
(`clojure/core/async.clj:528`) and `go` (`impl/go.clj:1048`, `:895-897`) —
never in flow. The `:io` executor starts a fresh virtual thread per
`.execute` (`impl/dispatch.clj:82-89`), so IO work never *had* the
submitter's frame; `:compute`/`:mixed` are cached pools
(`dispatch.clj:71-73`), so a pooled thread can even carry a previous task's
stale frame.

## Owner

`seon.flow/submit!` (`src/seon/flow.clj:610-650`).

## Acceptance criteria

- The correct fix depends on
  [Make the schema environment an explicit argument](schema-environment-is-ambient-not-explicit.md):
  once the projection is explicit, the SUBMISSION carries its cluster's
  projection as data and no dynamic binding needs to cross a thread. Land that
  first if the two are scheduled together.
- If that is deferred, `bound-fn*` on `::work-fn` is the minimal stopgap and is
  recorded in the source as a stopgap, not as the design. The source grounding
  above raises the bar on that stopgap: flow's author declined binding
  conveyance throughout flow, so a third `bound-fn*` would make Seon's
  capability door depend on a mechanism the dependency deliberately does not
  use. The flow-native shape is `:args` → init arity → state
  (`flow/impl.clj:48`, `:155`, `:263`, `:271`, `:304`), which a graph rebuild
  re-delivers for free because `spi/start` re-reads args on every start
  (`flow/impl.clj:166-167`, `flow/spi.clj:19-22`).
- The class-killing regression asserts BOTH halves: `:io` and `:compute` work
  each receive a complete environment argument, AND the ambient dynamic Vars
  are at their root values inside the work fn — the second assertion is what
  stops a future `bound-fn*` from quietly becoming the real mechanism again.
- Either way the asymmetry between `submit!` and `submit!!` is gone: one
  function pair, one conveyance rule, stated once.
