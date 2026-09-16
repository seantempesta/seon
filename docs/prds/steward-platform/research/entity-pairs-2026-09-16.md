---
type: research
status: decision needed
tags: [research, render, test, steward]
---

# Entity pairs — pre-edit design probe

## Scope and structural change

Assignment: issue-family prerequisite P2, one declared AI/HTML pair on each
function and test entity schema. The existing selection and walk must select
those pairs; issue rendering links to them without copying source. This is
a bounded prerequisite, not closure of the broad N1 issue class. No class
issue or member set was named specifically for this lane.

The intended guarantee is: a linked function or test renders through its
entity schema's pair, including when optional result facts are absent.

## Current verdicts

- Function pair: still missing. `resources/seon/schemas/seon.fn.edn:25`
  points only to `seon.render.ns/function-form`; `rg` found its definition
  at `src/seon/render/ns.clj:834` and this schema reference, with no other
  executable readers. Retire it with the pair, not before.
- Test pair: still missing on `:seon.test/test`. Reach-digest edits were
  in flight in this schema; none were altered by this lane.
- Lint refs: installed source declarations exist at
  `resources/seon/schemas/seon.lint.edn`; query installed schema before
  including optional lint/error reverse refs.
- No production function or schema was edited. No member was closed.

The issue-family spec was read end to end. Namespace-model §0 and §8 and
the mining report's structural-kill table were read. Initial batched reads
of AGENTS.md and other long authorities were truncated; this note does not
claim completed end-to-end grounding for all requested authorities.

## Live evidence

Initial default PID 69622 answered health queries. Two subsequent MCP JVM
reads returned `repl-unavailable` with a missing advertisement. Operator
status then showed no advertised clusters. Default later appeared under
PID 7595 without lane intervention. This was recorded in the existing
[MCP observation issue](../../../seon/issues/mcp-runtime-status-lists-no-clusters-for-an-explicit-root.md).
No cause of the process transition is claimed.

On the new JVM, dereferencing the connection directly yielded
`:seon.schema/missing-projection`; acquiring the database through
`seon.db/db` returned the full `seon.id/id` docstring and contract in 2 ms.
All subsequent database probes used that owner.

Four prototype render functions were evaluated through MCP JVM mode before
any source edit: function AI/HTML in `seon.render.ns`, test AI/HTML in
`seon.render.test`. A combined real-database function render exceeded
the 30000 ms MCP bound. Its complete result was unavailable. An isolated
existing-query probe then returned **936 reaching tests in 7217.67325 ms**:

```clojure
(seon.fn/tests-reaching
  (seon.db/db (seon.operator/connection "default"))
  "seon.id/id")
```

The [committed read-only reproduction](entity_pairs_probe_2026_09_16.clj)
measures the same operation. Sparse function/test maps without database
custody returned four complete outputs in one 2 ms JVM probe: unknown
counts explicitly labeled, one `doc` read for the function, one `pull`
read for the test, and exact `seon.test/run` / `my.test/check` calls.
That is a prototype observation, not a selection or armed-contract proof.
All four prototype Vars were subsequently unmapped through MCP (1 ms).

## Owner design gate: exactly three options

Estimates below are engineering estimates, not measured execution times.

1. **Recommended — explicit read for the reaching-test count.** Land pairs
   with cheap local counts and the exact reaching-tests query; label that
   count unevaluated until requested. Guarantee: painting a function does
   not run transitive test reach. Cost: roughly 1–2 hours for pairs,
   canonical regressions, adoption and browser proof. Give up the requested
   eager reaching-test number; requires an explicit scope ruling.
2. **Keep eager counts using today's query.** Land the requested pair as
   specified. Guarantee: each completed count comes from the authoritative
   query. Cost: roughly 1–2 hours implementation plus measured seconds of
   query work per function render (7217.7 ms in this probe). Give up prompt
   rendering latency; requests with tighter bounds can time out. Do not
   hide that with a new cache or raise timeouts silently.
3. **Improve the shared reach query first.** Extend work to the
   `seon.fn/gate-set` owner, preserving subject and pending-subject semantics,
   then land eager-count pairs. Guarantee: one exact reach mechanism serves
   tests and rendering, with performance established by measurement.
   Cost: estimated half to one engineering day plus coordination with
   program-provenance/reach-digest work. Give up immediate P2 delivery.

This is the assignment's stop-before-production design gate for work
crossing owners. The new
[query-cost issue](../../../seon/issues/function-entity-render-reach-query-cost.md)
keeps the finding outside conversation memory. The orchestrator should add
it to the issue index; this lane does not edit that schedule.

## Verification and handoff boundary

In-process regression runs: **none**. Canonical armed selection, issue-root
distance-1 walk, adopted definitions, and debug-page proof: **not run**.
No test JVM, test-fast invocation, or gate was launched. No background shell
or scratch root was created. Default was never started/stopped/reforked by
this lane. Production edits await the design choice; final gate request
will name the actual implementation paths and test namespace after it lands.

Documentation hook reported unrelated stale gitlink citations in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
Those citations were not modified here. HEAD observed during the probe:
`46b1df45652e61835bc694480540f5c4c31e1806` (the shared branch continued moving).
