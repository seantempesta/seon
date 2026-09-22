# Oversight owning-instance diff review

Reviewed `da2086452` and `1d8838629` with `git show`; source citations below
are pinned to `1d8838629`, not the concurrently edited tree. AGENTS.md read first.
Only this review is committed; no source, test, resource or other lane was changed.

## Findings, ranked

1. **P2 — Missing live observations still disappear silently.**
   `src/seon/oversight.clj:168-174` returns nil for supplied routing without a
   graph, indistinguishable from a deliberately detached request. The root page
   drops the unit at `src/seon/render/web.clj:473-501`, showing no diagnostic.
   `src/seon/oversight.clj:145` also treats absent armed-state as `{}`, producing
   `:seon.oversight/agents []`. This fallback predates the change; the new
   graph-less-routing case extends and explicitly endorses the same failure class.
   **Smallest fix:** distinguish intentional detached rendering from unavailable
   live state; return a declared refusal/unknown rendered visibly for the latter.
   Require the routing snapshot's armed map; only a present empty map means empty.

2. **P2 — The new boundary is not completely contracted.**
   `resources/seon/schemas/seon.agent.edn:127-132` declares an atom predicate,
   not its contents (`src/seon/flow.clj:39-41` checks only `IAtom`).
   `unit` accepts arbitrary qualified-key values through `:seon.render/unit`
   (`src/seon/oversight.clj:164`, `resources/seon/schemas/seon.render.edn:206-213`),
   so routing need not even be dereferenceable before entry. `fleet-value`'s new
   output is merely `:map` (`src/seon/oversight.clj:141-143`): missing fleet fields
   pass. Private helpers at `:36`, `:42`, `:47`, `:59`, `:88`, `:126` still lack
   contracts (inherited debt, not newly introduced omissions).
   **Smallest fix:** declare an open routing-content schema, validate the snapshot
   at its producer/read boundary, and declare the oversight request and fleet
   result fields plus the explicit unavailable result. An atom's opaque identity
   is legitimate; using that opacity to leave consumed state undeclared violates
   Total, honest boundaries. The landing's “undeclared” justification is not proof.

3. **P2 — Regression coverage protects wiring and silence, not the complete behavior.**
   `test/seon/oversight_test.clj:207-228` checks a deleted private Var, uses a
   keyword as a graph, replaces `fleet-value`, and asserts graph-less omission.
   It would fail against the parent (the Var exists and the old unit does not use
   handed routing), but those failures do not prove a real handed fleet renders.
   The booted test has useful real fleet/HTTP assertions (`:138-174`, `:197-205`)
   and the added graph identity assertion would fail without the join (`:134-137`).
   Neither exercises a later SSE render-proc pass; both affected test bodies were
   unverified in the landing (`docs/prds/agent-platform/landing/lane-oversight-owning-instance-2026-09-23.md:93-108`).
   **Smallest fix:** replace the stub/Var assertion with real handed-graph behavior,
   assert a visible missing-live-state diagnostic, and assert the fleet in an SSE
   update after a database wake. Run the focused namespace with the canonical base.

## Verified non-findings

- **(1), (5): no database stamp or replacement ownership search found.**
  Core.async is pinned to `dc35f3e0d7bc2eef502e77982f48641f025c8051`.
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:155-162`
  supplies pid, args, resolver, cast and channels, not the containing graph;
  `:166-168` starts procs and documents channel connectivity (the quoted comment
  is actually line 168). `src/seon/cluster.clj:2930-2932` carries the same graph
  object returned on the instance (`:2968-2971`), not a reconstructed observation.
  Existing joins precede resume (`src/seon/flow.clj:85-94`); the assignment is O(1)
  per graph construction. This adds a reference, not a second graph/cache/registry.
- **(2): all production callers converted, including SSE, by static trace.**
  GET carries routing through `src/seon/cluster.clj:2538-2541` and
  `src/seon/render/web.clj:2183-2194`; SSE receives it at `cluster.clj:2820-2822`
  and merges state with handle at `render/web.clj:2354`. Both reach `:2158`,
  `:2099`, then the sole production `oversight/unit` call at `:461-463`.
  Both `fleet-value` callers pass routing (`src/seon/oversight.clj:160`, `:172`).
  No normal-path routing omission found; finding 1 concerns degraded inputs.

## Evidence and limits

Static diff, dependency and caller review; no gate, JVM reload or browser proof.
Diagnostic `bb tmp/oversight-astra-review/probe.clj` evaluated committed unit/fleet
forms with synthetic db/graph and stubbed observation helpers: missing routing=nil,
missing graph=nil, missing armed=empty agents. It ran in 0.01 s, unarmed; this is
control-flow evidence only. Initial diagnostic had a harness delimiter typo, fixed.
Every shell operation timed with `/usr/bin/time -p`; none before commit exceeded 1 s.
The landing's stale-base failures are reported evidence, not independently rerun.
