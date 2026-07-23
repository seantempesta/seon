---
type: research
status: active
tags: [research, runtime, architecture]
---

# Execution planning over the program graph — design (2026-07-23)

## Design decision

Add one derived, basis-fenced execution plan over the program graph:

```clojure
(plan-execution
  {:seon.execution/db-value ...
   :seon.execution/roots [...]
   :seon.execution/invocation ...
   :seon.execution/tier-inventories ...})
=>
{:seon.execution/placement       :anywhere | :constrained | :unplannable
 :seon.execution/eligible-tiers  #{...}
 :seon.execution/schema-manifest {...}
 :seon.execution/capability-manifest {...}
 :seon.execution/unresolved      [...]
 :seon.execution/cache-key       [...]}
```

That function becomes the sole placement authority. Contract-predicate admission, drivers, and the legacy mixed-tier router consume its result; none independently rediscover locality, purity, or requirements.

“Anywhere” means portable across every admitted claimant execution tier—not the writer or web-render processes excluded by R26—and is semantic, not “all tiers currently happen to possess this capability.” R26 fixes those process roles; R33 requires derived, capability-free portability. [program-synthesis:378](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:378) [program-synthesis:423](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:423)

## 1. Graph additions

Store only direct, intrinsic facts. Derive transitive closures and execution plans.

| Direct edge or descriptor | Derivation and treatment |
|---|---|
| Function → function call | New analyzer edge. Resolve ordinary direct calls, qualified calls, lexical aliases, and statically closed higher-order targets. Persist exact direct edges with the analyzed source generation. |
| Function contract → registered schema | Already derived exactly by `seon.schema`; expose and reuse it rather than adding another walker. [schema.cljc:359](/Users/sean/src/seon/src/seon/schema.cljc:359) |
| Registered schema → registered schema | Already derived recursively from Malli refs. [schema.cljc:27](/Users/sean/src/seon/src/seon/schema.cljc:27) [schema.cljc:91](/Users/sean/src/seon/src/seon/schema.cljc:91) |
| Schema predicate → function | New edge for named `[:fn ...]` predicates. It is the bridge that lets R33 run the same placement derivation. The writer’s present schema-reference traversal deliberately does not follow `:fn`, so this cannot be inferred from its registration closure alone. [writer.clj:346](/Users/sean/src/seon/src/seon/db/writer.clj:346) |
| Function → written attribute | New typed edge from literal transaction entity-map keys, database operation attribute slots, and lookup refs. |
| Function → read attribute | New typed edge from literal pull selectors and query attribute positions. Keep reads and writes distinct even though both resolve to registrations. |
| Function → `:all-at-basis` attributes | Used for statically visible wildcards or variable-attribute queries whose universe is precisely the installed attributes at the planned database value. Datahike already distinguishes exact dependencies from `:all`. [query.cljc:2825](/Users/sean/src/seon/reference-code/datahike/src/datahike/query.cljc:2825) |
| Function → unresolved dynamic call/attribute | Explicit uncertainty edge. Never silently treat dynamic construction as empty requirements. |
| Callable terminal → effect | Canonical effect descriptor. Absence remains external/effectful, matching the capability seam rule. [toolkit.md:105](/Users/sean/src/seon/docs/seon/architecture/toolkit.md:105) |
| Callable terminal → required leaf/binding | Exact capability leaf, package leaf, or compiled artifact export needed to invoke it. |
| Package symbol → native leaf locality | Derived from the canonical namespace prefix, never stored as a second locality flag. R16 makes the prefix authoritative. [program-synthesis:401](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:401) [packages.cljc:102](/Users/sean/src/seon/src/seon/packages.cljc:102) |
| Compiled terminal → artifact export inventory | Derived from the exact artifact/runtime export manifest. A hard-coded “core function” allowlist is not adequate. |

The persisted function facts do not currently contain call, effect, leaf, or schema-reference edges. Authored analyzer projections presently retain symbol, function-var, arglists, documentation, privacy, and spec information; namespace require edges are separate and are not a call graph. [analyzer_info.cljs:51](/Users/sean/src/seon/src/seon/analyzer_info.cljs:51) [analyzer_info.cljs:209](/Users/sean/src/seon/src/seon/analyzer_info.cljs:209) [agent.cljs:245](/Users/sean/src/seon/src/seon/agent.cljs:245)

Compiled-core indexing is also incomplete for this purpose: it indexes public first-party vars, not private helpers or third-party internals. The build must therefore publish an artifact analysis inventory containing internal call/terminal nodes without making those helpers public `:seon.fn` entities. [indexing.clj:33](/Users/sean/src/seon/src/seon/client/indexing.clj:33) [indexing.clj:85](/Users/sean/src/seon/src/seon/client/indexing.clj:85)

### Static/dynamic honesty

Statically exact:

- Direct resolved calls.
- Literal transaction attributes, database-op attributes, lookup refs and pull selectors.
- Finite literal branches whose possible calls or attributes can be unioned.
- Malli function-contract and registered-shape references.
- Package locality and exact compiled exports.

Basis-exact:

- Pull/query wildcard populations represented as `:all-at-basis`.
- Attribute sets selected from the complete registration projection at a pinned database value.

Not statically exact:

- Keywords assembled from strings or runtime data.
- Arbitrary pull patterns, transactions, rules or queries passed as values.
- Reflective/dynamic invocation and genuinely open higher-order targets.

Version one should fail closed on unresolved edges. An invocation planner may specialize an open function-level edge using concrete invocation arguments before dispatch; if it remains open, the plan is `:unplannable`. Existing transaction admission can remain the last safety boundary, but it must not be mislabeled as ahead-of-time proof. [db.cljc:417](/Users/sean/src/seon/src/seon/db.cljc:417) [internal.cljc:388](/Users/sean/src/seon/src/seon/db/internal.cljc:388)

## 2. One placement derivation

`plan-execution` walks the reachable function graph once, with cycle detection, and folds terminal constraints:

1. An ordinary corpus function contributes its call edges, contract/schema edges and typed attribute edges.
2. A pure primitive contributes no placement restriction.
3. A capability call contributes its effect and required leaf/binding. Eligible claimant tiers are those whose inventory can provide that binding locally or through the defined remote seam.
4. A package call derives native locality from its namespace:
   - JavaScript native package leaf → Bun.
   - JVM native package leaf → JVM.
   - A provisioned remote package wrapper constrains the required leaf, not necessarily the caller’s evaluator tier.
5. A compiled-only terminal intersects eligible tiers with the exact artifact export inventory.
6. Any unresolved call, attribute, missing descriptor or absent effect declaration makes the result unplannable.
7. `:anywhere` is returned only when every reachable terminal is pure, capability-free, package-free, platform-independent and statically closed.

Purity is deliberately conservative. The analyzer does not prove arbitrary semantic purity; purity is derived from trusted terminal descriptors plus the transitive call graph. An undeclared terminal is external, not optimistically pure.

Phase eligibility remains a separate scheduler policy: phase capabilities decide who may claim a phase; the execution plan further decides whether that claimant can run this particular work. [core.cljc:50](/Users/sean/src/seon/src/seon/agent/loop/core.cljc:50) [driver.cljc:217](/Users/sean/src/seon/src/seon/agent/driver.cljc:217)

This absorbs the router’s present syntactic reconstruction from executable symbols, loader forms, require edges and `seon.packages.js.*` scans. [host.cljs:817](/Users/sean/src/seon/src/seon/execution/host.cljs:817) [host.cljs:832](/Users/sean/src/seon/src/seon/execution/host.cljs:832) [host.cljs:1016](/Users/sean/src/seon/src/seon/execution/host.cljs:1016)

## 3. Schema and capability manifest protocol

### Schema manifest

The manifest is the union of:

- Transitive registered schemas referenced by reachable function contracts.
- Transitive registered schemas referenced by predicate contracts.
- Registrations for exact read/write attributes.
- Their recursive canonical-form dependencies.
- Predicate functions needed to validate those forms.
- Either an exact attribute set or `:all-at-basis`; never an invented subset for open construction.

The existing projection already contains direct function-contract dependencies and schema-to-schema dependencies, and instrumentation already consumes that projection. [schema.cljc:359](/Users/sean/src/seon/src/seon/schema.cljc:359) [instrument.cljc:895](/Users/sean/src/seon/src/seon/instrument.cljc:895)

### Recommendation: verify full projection coverage

For normal persistent claimant leaves:

- Acquire the complete committed R29 schema projection at the planned database value.
- Verify that the manifest is covered by that projection and that its fingerprint matches the plan.
- If stale, reacquire the projection and replan or reverify.
- Do not perform per-invocation subset installation.

The full projection is cheap, already acquired as a coherent unit, and avoids a second mutable installation lifecycle. The pod and JVM host already acquire canonical committed projections at a basis. [admission.cljs:194](/Users/sean/src/seon/src/seon/runtime/admission.cljs:194) [context.clj:1524](/Users/sean/src/seon/src/seon/host/context.clj:1524)

For the writer, schema is database-global. Do not “install a plan subset” per dispatched job. Continue computed boot installation plus lazy first-use transaction admission as the final totality check. The writer already recursively follows canonical schema-form references and rejects missing or incompatible declarations. [writer.clj:332](/Users/sean/src/seon/src/seon/db/writer.clj:332) [writer.clj:422](/Users/sean/src/seon/src/seon/db/writer.clj:422) [writer.clj:474](/Users/sean/src/seon/src/seon/db/writer.clj:474)

Subset provisioning is worthwhile only for a disposable minimal validator/leaf. It receives a transitively closed selection from the committed projection—never re-executes `register!`. `:all-at-basis` or open requirements force the complete projection.

Thus the schema manifest is primarily:

- A verification artifact on persistent leaves.
- A provisioning artifact for disposable minimal leaves.
- An audit/debug artifact everywhere.

The capability manifest similarly names exact required bindings, native leaves, effects and artifact exports. Tier inventories must come from one capability installer/enumerator; they cannot be reconstructed from wrapper tables or source scans.

## 4. Basis, generations and plan lifetime

The plan is a derived immutable value attached to an invocation and optionally cached process-locally. It is never stored as a database table or durable status projection, consistent with R21’s derive-never-store rule. [program-synthesis:415](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:415)

Use a cache key containing:

```clojure
[database identity
 basis-t
 commit-id
 complete-program-graph digest
 schema-projection fingerprint
 capability/tier-inventory digest
 artifact-export digest
 root/invocation digest]
```

The existing schema “generation” hashes canonical forms and function contracts only, so it does not notice function-body, call-edge or locality changes. [admission.cljs:40](/Users/sean/src/seon/src/seon/runtime/admission.cljs:40) [schema.cljc:475](/Users/sean/src/seon/src/seon/schema.cljc:475)

The Bun program digest is broader—it includes source, namespace rows, require edges, schemas and contracts—but the new call/effect/leaf/attribute/export inputs must enter that digest or a sibling canonical graph digest. [execution.cljs:478](/Users/sean/src/seon/src/seon/execution.cljs:478) [execution.cljs:795](/Users/sean/src/seon/src/seon/execution.cljs:795)

Provision and execute under the same generation/basis admission fence. If the database value, program digest, schema projection, capability inventory or artifact inventory changes, discard the plan and recompute. Closure-only cache reuse is safe only if its digest contains every transitive input.

## 5. Enforcement wiring

### R33 contract-predicate admission

A named predicate is admissible exactly when:

```clojure
(= :anywhere (:seon.execution/placement
               (plan-execution predicate-root)))
```

Run this:

- Before committing a schema containing a predicate.
- Again when a function reachable from an accepted predicate changes.
- While reconstructing/activating a committed projection, so invalid historical combinations fail closed.

`schema/register!` can continue structural validation, but authoritative R33 admission needs the candidate program graph and database value; it cannot be implemented by the local Malli walker alone.

### Driver pre-dispatch

After parsing the proposed invocation but before transitioning `reply-ready → evaling`:

1. Derive the plan at the claim database value.
2. Verify schema coverage.
3. Compare capability/export requirements with the claimant inventory.
4. Provision permitted remote leaves.
5. Only then enter the execution phase.

If this claimant cannot satisfy the plan but another eligible claimant can, release the work unchanged for handoff. If no tier can satisfy it, return/persist a flat `:seon/error` steering value naming:

- Root function/form and relevant callsite.
- Missing capability leaves or exports.
- Missing/incompatible schema keys.
- Unresolved dynamic edges.
- Planned and observed basis/generation.
- Eligible and inspected tiers.

The leaf performs a final digest/manifest invariant check. A missing requirement discovered after a complete exact plan is a core consistency fault, not ordinary steering.

### Router

The mixed-tier router receives the plan’s selected tier and manifests. It retains result-symbol ownership routing, but no longer scans ASTs, loader forms, require edges or package prefixes itself. R18 becomes a consumer behavior of the planner instead of an independent derivation. [program-synthesis:406](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:406)

## 6. Migration and absorbed mechanisms

| Piece | Size | Absorbs |
|---|---:|---|
| Analyzer call graph, typed attribute edges, dynamic uncertainty, compiled internal graph and exact-set retractions | **L** | Missing call closure; broad qualified-keyword approximation |
| Expose existing function/schema closures; add schema-predicate→function edges | **S–M** | Duplicate schema-ref walking in context rendering [ctx.cljs:1222](/Users/sean/src/seon/src/seon/agent/ctx.cljs:1222) |
| Canonical effect/leaf/export descriptors and one tier inventory | **L**, or **M** atop the completed capability seam | Manual placement census and fixed binding inventories |
| Pure transitive `plan-execution` with placement and manifests | **M** | All independent purity/locality folds |
| Full-projection manifest verification; optional disposable subset provisioning | **S–M** | Per-consumer guesses about required registrations |
| Computed writer transactable population and pull admission | **M–L** | `agent-bootstrap-attrs` hand list [client.cljs:741](/Users/sean/src/seon/src/seon/client.cljs:741) |
| R33 predicate admission and dependency revalidation | **M** | Predicate-specific portability logic |
| Driver pre-phase verification, handoff and steering errors | **M** | Dispatch-then-discover-failure |
| Router conversion and deletion of source scans | **S–M** | `host.cljs` batch-selection helpers |
| Basis/program/schema/capability cache integration | **M** | Schema-fingerprint-only freshness |
| Replace regex purity classification | **M** | `pure-block?` source regex [context.clj:960](/Users/sean/src/seon/src/seon/host/context.clj:960) |
| Closed higher-order/dataflow inference beyond fail-closed | **L**, optional follow-on | Nothing in v1; do not conceal it inside the planner |

The earliest unsettled contract is the canonical direct-edge bundle and its generation digest. Once that is fixed, the dependency-ready next unit is the pure planner over those edges; schema verification, R33 admission, driver enforcement and router deletion can then follow without inventing their own semantics.

The graduation gate is one exact derivation producing placement plus both manifests, used by R33, drivers and routing, with no late missing-capability or missing-schema failures for executions whose plan was exact. No files, builds, or runtime state were changed.