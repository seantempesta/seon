---
type: decision
status: superseded
date: 2026-02-20
tags: [decision, architecture, archive, schema, runtime]
---

# ADR-007: Always-on runtime instrumentation

This decision is superseded as an instrumentation lifecycle. It selected a
complete reconstructed program, delta reinstrumentation, and Shadow reload
selection for replaceable runtime contexts. That lifecycle was deleted with
the CLJS build and per-turn context reconstruction.

Rulings 2026-08-01 #29, #31, and #33 replace it with one acquired base SCI
`ctx` per cluster, fresh per-turn forks, host-Var instrumentation under the
core-error dial, and
interpreted-function contract wrapping at the one program row installation
seam. The current owners are `src/seon/instrument.clj` and
`src/seon/sci/eval.clj`.

Re-evaluating a host `defn` requires `seon.instrument/apply!` because Malli's
wrapper is replaced with the Var root. Runtime program row publication wraps
interpreted functions from their committed contract as it installs them into
the acquired base cluster `ctx`; later turn forks observe that installation.

## Related

- [[agent-runtime]] — live context and evaluation target.
- [[data-model]] — program and schema facts.
- The `data-oriented-clojure` and `repl` skills — current Malli and SCI seams.
