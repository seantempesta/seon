---
type: research
status: implementation verified; integration pending
tags: [research, render, test, steward]
---

# Entity pairs — P2 implementation and live probes

## Landing result (2026-09-16)

The owner approved exact, unevaluated query forms for **all** function
relationships. The implementation now declares one AI/HTML pair on each
entity schema. Function rendering never computes relationship counts or
test reach. Its only executable AI form is `doc`; the query forms remain
comments for the agent to run deliberately. The legacy `function-form`
definition and schema reference are removed together.

The test pair lives in `src/seon/render/test.clj`: presentation belongs
beside the other rendering owners, while execution remains in `seon.test`.
It reads stored result evidence, emits one pull including called-function
links, and supplies exact `seon.test/run` and `my.test/check` forms. Missing
results are explicitly unrun/incomplete. HTML links to namespace pages and
shows present result facts without clipping. No source is copied into AI.

Guarantee: a function or test reached by the existing walk selects its
entity-schema pair and renders even when optional result facts are absent.

### Recorded in-process proof

All four renderer definitions were evaluated through MCP JVM before their
source edits, and their complete individual return values were inspected.
Isolated prototype calls took 0.27–0.59 ms; these are renderer-body timings,
not end-to-end web latency. Attempts to run the regressions before editing
recorded fixture acquisition errors, not green assertions. This is an
explicit limit of the requested pre-edit proof; later successful runs do
not retroactively make those attempts green.

After source edits, canonical fixture acquisition was warmed in the same
development JVM with CLI-resolved `:test` dependencies carried by a scoped
DynamicClassLoader (the existing development-adoption test-support issue
documents that procedure). No test JVM was launched. Warm acquisition took
27260 ms; it included the new renderer contracts. The exact test calls were:

```clojure
(seon.test/run
 #'seon.render.entity-pairs-test/function-entity-pair-is-selected-through-the-issue-walk
 (seon.operator/connection "default"))
(seon.test/run
 #'seon.render.entity-pairs-test/test-entity-pair-is-total-through-the-issue-walk
 (seon.operator/connection "default"))
```

| Test | Recorded result entity | Run basis | Pass/fail/error | Tool form time |
|---|---:|---:|---|---:|
| Function pair | 51140 | 536871254 | 17 / 0 / 0 | 1883 ms |
| Test pair | 51142 | 536871256 | 24 / 0 / 0 | 1183 ms |

Runs occurred at 01:58:02Z and 01:58:21Z. A subsequent live
`seon.instrument/instrumented` probe found all four renderer Vars armed.
Both tests use `seon.test-support/with-database`, real SCI contexts, the
complete schema population, and explicit render profiles. Each asserts
selection, total rendering, and equality with a distance-1 issue-root walk
whose issue refs an agent. The function test observes no database reads
while producing its queries. The test test covers absent, pass, fail, and
error result facts. No separate registry, walker, or execution path was
introduced.

### Live adoption and browser boundary

The default JVM (PID 7595) contains the four armed Vars and indexed
function contracts. This proves loaded definitions, not complete adoption:
the source marker query returned no `:seon.source/commit-id`, while
`seon.cluster.source/current` returned
`6aa9f7e0-1949-5e5b-9543-701c4530a60a`.

Chrome observation of `/agent/root/debug?subject=[:seon.issue/id
"quoted-private-capability-symbol-was-not-indexable"]` showed actual
function/test references, but the attribute collection previews still
used the generic value renderer and included test source. It did **not**
prove the requested pair blocks on that page. Canonical walk proof is
green; debug paint parity remains an integration requirement. The debug
owner (`src/seon/render/web.clj`, inspection selection around line 1455)
and value/walk owners are outside this lane's edits.

A final MCP timing probe returned `:seon.dev.mcp/projection-failed` for a
map; `(pr-str *1)` returned the same error for a string. Neither returned
value envelope could support a timing claim. A subsequent `(prn *2)` through
the **same MCP session** recovered the complete raw result through its
stdout event: function AI for `seon.id/id` took **1.081541 ms**, with all
four exact relationship query comments and the single `doc` form. The
ordinary projection error is independently recorded in
[the existing MCP issue](../../../seon/issues/mcp-jvm-small-result-projection-fails-during-live-adoption.md).
Publication was explicitly requested
for the four owned source/schema paths; it waited behind a foreign
`init --dev default` lifecycle lock. No foreign process was operated.

### Gate request and exact boundaries

Orchestrator: after the shared schema/adoption changes settle, run serially:

```sh
bin/test --paths src/seon/render/ns.clj src/seon/render/test.clj resources/seon/schemas/seon.fn.edn resources/seon/schemas/seon.test.edn test/seon/render/entity_pairs_test.clj -- seon.render.entity-pairs-test
bin/test --platform
```

No lane gate or test JVM was launched. The test schema has concurrent
reach-digest edits; only its entity-map render properties are this lane's.
Do not include other schema hunks in the entity-pairs commit. The remaining
integration proofs are final gate, complete development adoption, and debug
page parity. This P2 slice does not close N1 or any unnamed class members.

Dependency ledger: Malli contracts are the existing selection inputs
(`src/seon/render.clj`); Datahike refs/pull flow through `src/seon/db.clj`
and `reference-code/datahike/src/datahike/pull_api.cljc`; SCI contexts use
the canonical fixture and `reference-code/sci/src/sci/core.cljc`; namespace
links use `src/seon/render/route.clj`; source grammar uses
`src/seon/repl.clj`. No dependency implementation changed.

The 7217.67325 ms shared reach finding remains open in
[the query-cost issue](../../../seon/issues/function-entity-render-reach-query-cost.md).
The fixture-delay observation is recorded in
[the runner issue](../../../seon/issues/in-process-runner-rethrows-failed-fixture-delay.md).

## Historical pre-edit design probe

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
