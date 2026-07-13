---
type: research
status: completed
tags: [research, database, schema, agent]
---

# Malli runtime schema authority audit

## TL;DR

At `da160744`, Seon does not yet have one Malli schema authority. A mutable
process registry is authoritative for validation, database rows contain an
ambiguous and sometimes truncated source string, entity-render dispatch uses a
second persisted decomposition, and some context renderers bypass the database
again. These paths can disagree after a failed eval, restart, hot reload, or
historical database read.

The smallest coherent repair is to persist one full, round-trippable Malli form
per `:seon.schema/key`, construct and validate a complete immutable registry
candidate from one database value, derive runtime catalogs and dependency edges
from that candidate, and swap one process-local projection only after the
database transaction commits. The atom remains a justified runtime cache; it is
not authority. Function source and analyzer data remain the authority for
program forms. The persisted entity-schema decomposition, schema-source replay,
and asynchronous schema tee should then be deleted rather than retained as an
“inventory” or compatibility path.

Instrumentation is already mostly on the desired explicit-data path. It needs a
schema-dependency closure, the exact candidate registry passed to every target,
and hot-reload ordering that commits the changed program facts before wrapping
live vars. Rendering should use a database-coordinate/fingerprint cache of the
same candidate-derived catalog, so unrelated transactions do not repeat Malli
or SCI work and as-of views never consult current process state.

## Scope and baseline

This is a read-only audit of the CLJS pod's Malli/runtime authority after commit
`da160744`. The pod and JVM writer were healthy during the audit at
`http://127.0.0.1:7890`; no database state was changed.

The broader boot audit in
[[config-schema-runtime-restoration-2026-07-12]] remains valid background. That
audit measured 1,159 schema rows, 18 persisted entity decompositions, and 36
call-shaped schema sources in the then-live test database. Those counts were not
re-measured here. Since that report, optional config boot and explicit-data
instrumentation have landed; the authority split described below remains.

The target architecture is already stated in
[[../../../seon/architecture/data-model]] and
[[../../../seon/architecture/agent-runtime]]. This report is the current code
map and smallest execution order, not a new architecture.

## Current authorities

| Concern | Authority today | Required authority |
|---|---|---|
| Native Datahike attribute shape | Installed Datahike schema, plus an eager full boot assertion assembled from CLJS schemas | Installed native schema, reconciled only for missing compatible declarations |
| Malli domain form | Mutable `seon.schema/*schemas`, with partial `:seon.schema/source` rows in the database | Full canonical `:seon.schema/form` facts in one database value |
| Compiled program source | Analyzer metadata and source strings | Keep analyzer/source truth; do not reconstruct it from schema projections |
| Function contracts | `:seon.fn/spec` database facts, then explicit-data instrumentation | Keep, compiled against the exact candidate registry |
| Entity render dispatch | Persisted `required-attrs`/`id-attr`/render-symbol decomposition | Pure catalog derived from the candidate registry |
| Live validators | Mutable Malli registry and process-global default registry | One atom containing a complete immutable projection, swapped after commit |
| Historical rendering | Mix of historical database facts and current live registry | Candidate/catalog derived solely from the requested database value |

There is no useful independent “schema inventory” in this model. The durable
facts are schema forms. Lists, decompositions, dependency edges, render dispatch,
and debug summaries are projections of those facts and should be named for what
they do, computed from a database value, and cached only as a performance choice.

## Where authority splits today

### Mutable registry precedes durability

- `src/seon/schema.cljc:39` installs a mutable composite registry as Malli's
  process-global default and watches Malli's private registry atom to reverse
  later stomps.
- `src/seon/schema.cljc:202` validates against the current live registry, mutates
  `*schemas`, rewrites entity forms with derived metadata, and only then invokes
  an asynchronous tee.
- `src/seon/schema.cljc:257` can discard only newly introduced keys. It cannot
  restore the previous form when an existing schema is redefined.
- `src/seon/eval.cljs:2210` detects schema changes by key-set difference. A
  successful redefinition of an existing key is therefore not persisted.
- The failure path near `src/seon/eval.cljs:4036` uses the same key-set
  difference, so a failed eval can leave an existing redefinition live while the
  database retains the old fact.
- The self-tee path at `src/seon/eval.cljs:2661-2750` treats a failed database
  write as loggable after the registry has changed. `!last-tee` exists to await
  this race in tests rather than making the transition atomic.

This is the central split-brain failure: validation can accept a form the
database cannot restore, while restart can restore a different form than the
one that accepted the last transaction.

### The persisted form is neither canonical nor complete

- `src/seon/client.cljs:1833-1876` serializes a registered runtime value into
  `:seon.schema/source` and caps it at 1,000 characters.
- `src/seon/eval.cljs:711` uses “starts with `(`” as a discriminator for that
  same attribute, which sometimes contains a Malli form and sometimes an
  executable `register!` call.
- `src/seon/eval.cljs:772-824` reconstitutes a namespace by concatenating these
  call-shaped strings with function and namespace source.
- Runtime function objects are still accepted inside registered schemas. For
  example, direct predicates appear in `src/my/data.cljs`,
  `src/seon/web/datastar.cljs`, `src/seon/ai/tokens.cljc`,
  `src/seon/db/transport/uds.cljs`, and `src/seon/retry.cljs`. Their evaluated
  values do not have a readable EDN round trip. The custom `:inst` registration
  in `src/seon/schema.cljc` is also a runtime schema object rather than a durable
  form.

Schema facts therefore need a mechanical pure-data and read/print round-trip
gate. Error rendering may replace objects with placeholders; canonical storage
must reject them.

### Derived entity decomposition is a second database model

- `src/seon/schema.cljc:120-126` registers persisted attributes for required
  keys, identity key, and render functions.
- `src/seon/schema.cljc:309-362` derives and emits those datoms from the live
  registry.
- `src/seon/client.cljs:2151-2225` transacts the complete decomposition during
  every boot, before replay of restored agent program forms.
- `src/seon/render.cljs:252-326` queries that decomposition to decide which
  schema renders an entity. Cardinality-many required keys accumulate unless
  explicitly retracted, and an agent schema restored after this boot phase can
  have a Malli form without a render decomposition.
- `src/seon/render.cljs:303` caches only while the entire database object is
  identical. Any unrelated transaction invalidates it and repeats the queries
  and catalog construction.

These datoms are projections of Malli forms. Persisting them creates drift and
does not make the system faster reliably. A candidate-derived catalog cached by
schema fingerprint provides the speed without another authority.

### Context and database validation consult different states

- `src/seon/agent/ctx.cljs:1874-1958` can normalize and close references from
  database schema source, but `schema-block-ai` at line 1967 still prefers the
  current live registry.
- `src/seon/agent/ctx/namespaces.cljs:566-571` correctly treats the database row
  as input for compact rendering.
- `src/seon/handlers/schema.cljs:20` reads only the live registry. Its AI/HTML
  view can disagree with an as-of database view.
- `src/seon/db/internal.cljs:188-841` resolves schemas, validates attributes,
  detects refs, and builds native Datahike declarations from
  `schema/registered?` and `schema/schema-definition`. A durable database schema
  fact absent from the atom is unusable; an atom-only form can authorize writes.
- `ensure-datahike-attrs!` at `src/seon/db/internal.cljs:1604` detects native
  shape conflicts, but boot still eagerly sends the complete native schema from
  `src/seon/client.cljs:648-729`.

All these consumers should accept a database-derived projection explicitly, or
read the single current projection atom when they are intentionally operating on
the current database. Historical consumers must never fall back to that atom.

### Instrumentation is close, but schema changes are not a delta yet

- `src/seon/instrument.cljc:501-601` performs cold instrumentation from explicit
  database rows and provides an exact function delta path.
- `src/seon/instrument.cljc:657-708` refreshes changed namespaces from database
  facts. This is substantially better than re-instrumenting the whole program.
- `prepare-target` already accepts an immutable registry, but normal callers do
  not pass the candidate registry.
- `src/seon/eval.cljs:1700` instruments only the exact function definitions
  emitted by a successful eval. A changed referenced schema does not rewrap
  transitively dependent functions, whose compiled validators retain the old
  schema.
- `src/seon/client.cljs:323-335` handles a Shadow build by querying stored
  function rows before first reconciling the freshly compiled analyzer/source
  facts. It can wrap a new live function using a stale database contract.

Vendored Malli confirms the intended fix. `malli.registry/fast-registry` is
immutable and `composite-registry` composes registries
(`reference-code/malli/src/malli/registry.cljc:17-59`). `malli.core/schema`
accepts an explicit registry (`reference-code/malli/src/malli/core.cljc:2551-2573`).
The default registry setter is only an atom reset, and Malli describes that
global imperative path as easy but not simple. Malli instrumentation mutates
vars one at a time (`reference-code/malli/src/malli/instrument.cljs`), so Seon
must finish candidate compilation before beginning wrapper mutation.

## Canonical schema facts

The durable row should contain only facts that cannot be projected cheaply or
that a separate process needs directly:

```clojure
{:seon.schema/key  :my.domain/item
 :seon.schema/form "[:map [:my.domain/id :uuid] ...]"
 :seon.schema/ns   [:seon.ns/name 'my.domain]}
```

`:seon.schema/form` is the full, untruncated, readable EDN form. The string is a
storage encoding, not source code to eval. `:seon.schema/source` should disappear
from schema entities. Original eval source remains on eval entities; namespace
and function source remain on their program entities. Analyzer output remains
the structured authority for function definitions and contracts.

The existing generator policy may remain a materialized fact because the JVM
writer consumes it without loading the CLJS Malli registry. That is an explicit
cross-process processing-versus-storage tradeoff, not a general license to
persist required keys, render dispatch, dependency edges, or lists.

## One candidate projection

Build a pure candidate from one immutable database value:

1. Query all schema keys, full forms, namespace refs, and the explicitly retained
   cross-process policies.
2. Read every form as EDN and reject unreadable, truncated, executable, or
   runtime-object values. Report all offending keys together.
3. Build an immutable map of domain forms. Compose an isolated
   `malli.registry/fast-registry` with Malli's immutable built-ins; do not compose
   through the process-global default registry.
4. Compile every key with `m/schema` and that explicit candidate registry. This
   validates forward and cyclic references as one graph rather than requiring
   declaration order.
5. Derive native Datahike attribute signatures and compare them with the
   installed database schema. Emit only missing compatible declarations;
   incompatible value type, cardinality, or uniqueness is a hard transition
   failure.
6. Derive an entity render catalog from schemas whose map properties say
   `:seon.db/entity true`: identity attribute, required attributes, and render
   symbols. Do not rewrite canonical forms with `:seon.entity/id-attr`.
7. Derive direct schema reference edges and the reverse function-spec dependency
   index. These stay in the projection.
8. Return one immutable value containing forms, registry, catalog, dependency
   indexes, and a stable fingerprint. Only after the database commit succeeds,
   reset one runtime projection atom to this complete value.

One atom is appropriate here: Malli validators and live functions need a
process-local compiled projection. It is disposable and fully reconstructible
from the database. Separate atoms for forms, catalogs, dependencies, or
registration side effects would recreate partial states.

For rendering, cache projections by the sorted schema-form fingerprint rather
than database object identity. Keep the live candidate plus a small bounded cache
for historical/as-of views. An unrelated message or canvas transaction then
reuses every schema-derived result. Changed-attribute routing should request a
rebuild only when canonical schema facts change; it should not globally fan out
schema work.

## Ordered refactor plan

1. **Make forms durable data.** Add `:seon.schema/form`; change every schema
   declaration to a readable pure-data form, including `:inst` and predicate
   schemas; enforce the round-trip gate. Atomically stop writing and reading
   schema `:source` rather than supporting two formats. This test database does
   not require migration.
2. **Introduce the pure candidate builder.** It accepts a database value and
   returns either a complete immutable projection or structured errors. It has
   no global mutation, transactions, logging side effects, or source replay.
3. **Fix boot ordering and cost.** Read canonical facts, combine them with the
   explicitly selected current core declarations, validate the entire desired
   graph, transact only missing/changed canonical facts and missing compatible
   native attributes, reread the committed database value, rebuild, then swap.
   A converged restart sends no full-schema or entity-decomposition transaction.
4. **Make runtime registration a database transition.** The eval record boundary
   stages a candidate including every changed form, including redefinitions;
   commits eval and schema facts; rebuilds from the committed database; swaps;
   and only then reports success. Core module-load declarations are collected as
   boot input, not asynchronously teed. Delete schema self-tee, `!last-tee`,
   key-set change detection, and `discard-registrations!`.
5. **Cut rendering to the candidate catalog.** Pass the database-derived
   projection to entity dispatch, schema handlers, namespace cards, context
   blocks, debug views, and historical views. Delete the decomposition functions,
   boot transaction, attributes, and cache keyed by full database identity.
6. **Close incremental instrumentation.** Cold boot instruments once after
   program load and candidate swap. A committed delta reinstruments the union of
   changed function symbols and functions transitively dependent on changed
   schemas, using that exact candidate registry. Shadow reload first reconciles
   analyzer/function/schema facts, then invokes the same delta path. Resume,
   canvas rendering, and ordinary transactions do not instrument.
7. **Remove the residual global path.** Once all compilation receives an explicit
   registry or the single current projection, remove the private Malli atom
   watcher and schema-source replay. Keep a default-registry bridge only if a
   proven third-party Malli call cannot accept explicit options; document that
   boundary rather than making it authority.
8. **Profile and prove.** Count candidate builds, compiled validators, affected
   function wrappers, render-cache hits, and native schema transactions in
   estimated tokens or event counts as appropriate. Profile a large transcript
   with debug closed and open; schema work must be proportional to schema changes,
   not transcript size or total transaction count.

## Failure and rollback behavior

| Failure point | Required behavior |
|---|---|
| Unreadable or invalid candidate before commit | No database or runtime change; return structured errors naming every bad key |
| Native signature conflicts with installed Datahike schema | No commit or swap; require an explicit reset or migration decision |
| Database transaction fails | Old projection and wrappers remain current |
| Commit succeeds but reconstruction or swap fails | Do not undo history or continue split-brain; stop admission, mark the runtime unhealthy, and reconstruct from the committed database or restart |
| Wrapper preparation fails | No var mutation because every target is compiled before instrumentation begins |
| Malli mutates some vars and then instrumentation fails | Unstrument the exact affected set back to originals and stop admission; do not report the transition as healthy |
| Historical row cannot build a candidate | Render an explicit error surface for that coordinate; do not poison the live projection/cache |

Database commit is the durable decision. Runtime effects after that decision are
recoverable projections, not facts to compensate with reverse transactions.

## Mechanical proofs

Use behavioral and failure-injection tests, not expected prose:

- Forward references compile when the complete candidate is valid.
- One invalid form causes no database transaction and no projection swap.
- Redefining an existing key persists; a failed redefinition leaves both DB and
  live validation on the previous form.
- Every persisted form round-trips; function objects and truncation are rejected.
- A cold restart renders a restored agent entity schema without source replay or
  decomposition datoms.
- Historical rendering uses the historical form and catalog, never the current
  live registry.
- A schema change reinstruments only its transitive function dependents; an
  unrelated transaction rebuilds neither registry nor catalog.
- A converged boot sends no complete native-schema or decomposition transaction.
- Hot reload writes the new analyzer/function contract before wrapping the live
  var.
- An injected post-commit projection failure makes the runtime unavailable until
  reconstruction succeeds; it never serves the old registry against new facts.
- Large transcripts and closed debug views do not increase candidate builds or
  schema-render work.

## Deletions that complete the authority change

The refactor is incomplete while any of these remain active:

- `:seon.schema/required-attrs`, `:seon.schema/id-attr`,
  `:seon.schema/render-fn`, and `:seon.schema/render-html-fn` datoms.
- `entity-schema-tx-data`, `all-entity-schemas-tx-data`, and the boot
  `:entity-schemas` transaction.
- Schema `:source` call/form overloading, call-shape classification, and schema
  source concatenation during namespace replay.
- Schema self-tee, `!last-tee`, registration key-set snapshots, and partial
  registration rollback.
- Current-registry fallback in database/as-of schema renderers.
- The private Malli registry reset watch once explicit registry use is complete.

Removing these is what turns the database from one participant in a loose
registry synchronization protocol into the canonical schema-fact authority.
