---
type: issue
status: open
severity: blocker
tags: [issue, render, agent, wave/rebirth-gap]
---

# Rebirth opening omits agent-authored results

## Problem

`generate(current facts, empty history)` completes after the root `help` form
without emitting the agent's durable namespace results. The resulting context
is compact and deterministic, but it omits the `dir`/`docs` account of the
agent's authored functions and green test required by ruling 45.

## Evidence

The isolated rebirth proof committed these current program facts before
forking two branches at one commit:

- `my.agents.rebirth/current-items` with a complete contract;
- `my.agents.rebirth/render-plan-ai` with a complete contract;
- `my.agents.rebirth/render-namespace-ai` with a complete contract; and
- `my.agents.rebirth/plan-current-state-test` with `1` pass, `0` failures,
  and `0` errors.

On both branches, `seon.bootstrap/next-entry` emitted only the generated
`(help)` entry and then returned no next entry. The raw histories are equal and
the rendered histories are equal, but neither contains `dir`, `doc`, `docs`,
the three function symbols, or the green test result. The exact branch
histories and program-graph query are in
[`tmp/rebirth/scratch-root-4/rebirth-evidence.edn`](../../../tmp/rebirth/scratch-root-4/rebirth-evidence.edn),
produced by [`tmp/rebirth/probe.clj`](../../../tmp/rebirth/probe.clj).

`seon.bootstrap/pull-result` derives direct and listing candidates from the
root acquisition (`src/seon/bootstrap.clj:189-231`), and `next-entry` asks
`seon.render.walk/ordered-episode` for the next unsettled entry
(`src/seon/bootstrap.clj:237-306`). The generated episode therefore owns the
missing namespace-results entry; transcript rendering is only exposing the
omission.

## Owner

`seon.bootstrap/pull-result` and `seon.bootstrap/next-entry`, composed with
`seon.render.walk/ordered-episode`. The fix must derive the agent namespace's
current result forms from the program graph. It must not replay authored
history or append a rebirth-only transcript path.

## Acceptance

- With current functions, schemas, and latest test-result facts in the
  agent's namespace, empty-history generation emits a bounded `dir`/`docs`
  account of those results.
- A demonstrated green lesson is not taught again; current program facts, not
  prior shown history, close that gap.
- Two branches forked at one commit produce byte-identical raw and rendered
  episodes.
- Superseding the lived runs removes their scaffolding and errors from the
  active history while the old runs remain queryable.
