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

The replacement uses one acquired base SCI
`ctx` per cluster, persistent per-agent contexts receiving base diffs,
host-Var instrumentation under the
core-error dial, and
interpreted-function contract wrapping at the one program row installation
seam. The current owners are `src/seon/instrument.clj` and
`src/seon/sci/eval.clj`.

Re-evaluating a host `defn` requires `seon.instrument/apply!` because Malli's
wrapper is replaced with the Var root. Runtime program row publication wraps
interpreted functions from their committed contract as it installs them into
the acquired base cluster `ctx`; agent contexts receive that base diff.
The [turn PRD §14](../../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
supersedes the intermediate fresh-fork-per-turn lifecycle.

## Related

- [[agent-runtime]] — live context and evaluation target.
- [[data-model]] — program and schema facts.
- The `data-oriented-clojure` and `repl` skills — current Malli and SCI seams.
