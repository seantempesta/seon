---
type: issue
status: open
severity: friction
tags: [issue, agent]
---

# acme cluster has no programmatic SCI eval seam

## Problem

There is no way to eval a CLJS form in the **acme** cluster's pod SCI runtime
programmatically. The `seon_cljs` MCP attaches only to the DEFAULT pod's shadow
`:repl` build; acme runs the unwatched `out-acme` bundle (no cljs-watch), and
acme's wire-REPL on 7981 is the **JVM datahike writer** (`nc` only), NOT the
CLJS SCI cage. So a measurement or probe that needs to eval Clojure inside
acme's runtime can't reach it.

## Impact

Surfaced by the eval-tier masked-divergent measurement
([[parser-as-generation-oracle-2026-06-28]]): it needed to eval ~250 forms in
the SCI cage, but had to run them on the DEFAULT pod's `:client` runtime instead
of acme. That was SAFE only because every form was a pure expression (no db
writes / defs / agent state, verified by construction) — but it means any
acme-side live-eval measurement, or a probe that must run in acme's isolated
store context, currently has no seam. The only way to exercise acme's SCI
runtime today is to drive a full live AGENT on it (the agent loop evals), which
is heavyweight and indirect.

## Acceptance criteria

A direct eval seam into acme's pod SCI runtime — e.g. an HTTP `/eval` endpoint
on the acme pod (7980), or wiring the `seon_cljs` MCP / a socket REPL to acme's
CLJS runtime. Must stay isolated to acme's store (never touch the default
cluster). Then the eval-tier measurement (and future acme-side probes) can run
in the isolated cluster as originally intended.

## Workarounds (today)

- Pure expressions on the default pod's `:client` runtime (no side effects) —
  what the measurement did.
- Drive a live agent on acme (its turn loop evals) — heavyweight, indirect.
