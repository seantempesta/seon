---
type: research
status: completed
tags: [research, agent, schema, database]
---

# Runtime registry and instrumentation state audit

## TL;DR

The active pod currently has three schema/program authorities where it should
have one durable fact set and disposable projections:

- `seon.schema/*schemas` is mutated one registration at a time and is treated as
  the live Malli authority;
- schema and function rows in Datahike are incomplete, overloaded durability
  copies of that process state; and
- Malli's private function-schema atom is populated as another function roster
  before wrappers are installed.

That split creates correctness bugs, not just boot cost. A failed same-key
schema redefinition leaks into the live registry. A successful registration can
remain memory-only when its background tee fails. Forward references depend on
load order. Existing validators keep the old referenced schema after the map
behind a mutable registry changes. Warm agent creation and Shadow reload scan
the complete function population even though no cold runtime reconstruction
occurred, and the reload scan can wrap a fresh function with the stale database
contract.

The minimal target is:

1. Store one full canonical schema declaration per `:seon.schema/key`. Parse
   declarations as data, never as executable registration calls. Do not store
   renderer/catalog/namespace/timestamp projections unless a measured query
   justifies the storage tradeoff.
2. Build one complete immutable schema generation from a frozen database value,
   validate every form, native storage signature, renderer entry, and dependency
   before publishing anything, then swap the live generation once.
3. Keep one Seon-owned pointer to that immutable generation. Malli's default
   registry atom remains an unavoidable library integration pointer, not an
   authority. The schema stomp guard must always re-point to the current
   generation, never a `defonce` registry object.
4. Reconstruct a fresh process by loading safe declarations once, then call
   Malli instrumentation once with an exact explicit `:data` map. Do not write
   or read Malli's function-schema roster.
5. After boot, take the exact accepted program/schema delta from the fenced
   database transition. Unstrument only its old affected functions and
   instrument only its new affected functions. Schema changes include the union
   of old and new transitive dependents.
6. Remove all instrumentation work from mint, resume, ordinary `start-agent!`,
   config apply, render, and the generic Shadow `after-load` hook. A selected
   hot-reload snapshot goes through the same exact program/schema transition;
   instrumentation does not rediscover it.

This does not mean the entire Node process should literally contain one atom.
Connections, compiler state, sockets, timers, streams, and fiber-local scopes
are real process handles. It means one lifecycle owner per irreducible handle
and no second mutable authority for facts Datahike already knows. The complete
pod-wide classification is in
[[active-cljs-pod-mutable-runtime-census-2026-07-12]]. This audit narrows that
census to the registry/reconstruction boundary and specifies the in-place
cutover.

## Relationship to the other audits

This document does not replace the two detailed source audits that already
exist:

- [[../../database-lifecycle-recovery/research/config-schema-runtime-restoration-2026-07-12]]
  owns canonical schema facts,
  native Datahike schema, and cold restoration;
- [[incremental-instrumentation-2026-07-12]] owns Malli ref walking, exact
  instrumentation data, and wrapper behavior; and
- [[active-cljs-pod-mutable-runtime-census-2026-07-12]] owns the complete active
  pod atom/socket/timer/listener inventory.

The contribution here is the combined state machine: which mutable cells are
deleted, which one remains, how schema publication and wrapper replacement
share one durable decision, and what must be proved before the legacy paths can
be removed.

No production code was changed for this audit. Source locations are from the
shared `codex/runtime-reliability-refactor` worktree as observed on 2026-07-12;
concurrent work can shift line numbers.

## What happens now

### Module load creates the first authority

`seon.schema` creates `*schemas`, wraps it in `mr/mutable-registry`, installs the
composite as Malli's process-global default, and then uses a series of top-level
`defonce (swap! *schemas assoc ...)` forms to populate bootstrap definitions
(`src/seon/schema.cljc:26-161`). Every other namespace calls
`schema/register!` at module load.

`register!` compiles one form against the registry as it exists at that moment,
mutates `*schemas`, and only afterward invokes a late-bound asynchronous tee
(`schema.cljc:221-262`). This means:

- declaration order is semantic even though a complete registry could resolve
  forward references;
- process memory changes before the durable decision;
- the tee deliberately cannot fail the registration, so database and runtime
  can disagree; and
- the values of the bootstrap `defonce`s retain successive old registry-map
  roots because `swap!` returns the whole updated map.

The stomp guard in `relink-registry!` solves a real integration problem: the
self-host loader can execute Malli's top-level default-registry initialization
again. Its current closure captures the one `defonce` `seon-registry`, however,
so it cannot follow a future immutable generation swap without being rewritten
to dereference the current generation (`schema.cljc:39-79`).

### Cold boot rebuilds and reprocesses overlapping populations

The current cold path:

1. opens the cluster connection and retransacts the complete native attribute
   schema (`src/seon/client.cljs:637-666`);
2. `boot-seed!` rebuilds the complete core program/schema desired graph and
   conditionally writes it (`client.cljs:2374-2530`);
3. `prune-core-ghosts!` builds the complete program graph again to discover
   removals (`client.cljs:2025-2123`);
4. `replay-program-graph!` evaluates reconstructed agent namespaces plus
   call-shaped schema registration strings (`client.cljs:841-968`);
5. those registration calls mutate `*schemas` again; and
6. `instrument-from-db!` queries, parses, resolves, and registers every specced
   function, then invokes Malli globally (`client.cljs:2648-2669`).

The low-level declaration loader calls `seon.eval/eval`, not `eval-batch!`, so
it does not run the per-definition instrumentation tee. One complete
instrumentation operation is therefore legitimate for a genuinely fresh JS
runtime. Its placement inside the general agent-start path is not.

### Warm mint and resume repeat cold work

`POST /agents/new` calls `start-agent!` with `:mint? true`
(`client.cljs:2763-2766`). The same `start-agent!` runs boot seed, ghost pruning,
declaration loading, and the complete instrumentation scan. Creating one
database agent entity did not create a new JavaScript runtime or change the
schema/program generation, so none of those operations belongs to mint.

The current `!agent-conn`/existing-connection check gates only pieces of crash
recovery. It does not own an explicit “fresh runtime needs reconstruction”
transition. The broader lifecycle audit correctly assigns that split to the
runtime attachment owner, not to instrumentation.

### Shadow reload scans stale facts

`after-reload` re-arms hooks/timers and calls `instrument-from-db!` over the
complete database (`client.cljs:287-318`). Shadow has already replaced the live
JavaScript functions, but no exact current-core program transaction has updated
their source/spec facts. The pass can therefore install yesterday's persisted
contract onto today's function.

Malli's `:skip-instrumented? true` does not refresh a wrapped contract. It skips
any live value carrying the instrumented flag
(`reference-code/malli/src/malli/instrument.cljs:95-111`). A redefined function
gets wrapped only because it is a fresh unflagged object. An unchanged wrapper
continues closing over its old compiled validators.

Shadow already reports the exact changed resources after loading. The selected
current-core snapshot/reconciler must consume that change set, commit canonical
facts, and hand its accepted delta to instrumentation. The generic reload hook
should never issue a safety scan.

### Agent eval has a narrower but duplicate path

The eval boundary snapshots only `schema/current-keys`, executes the form, and
computes newly added keys with set difference (`src/seon/eval.cljs:3924-3928`,
`:4021-4031`). A same-key replacement is therefore invisible. If a later part
of the form fails, `discard-registrations!` can remove new keys but cannot
restore the prior value for an existing key.

On success, `build-tee-entities` makes schema rows only for newly observed keys
(`eval.cljs:2195-2325`). `record-eval!` commits the eval/program facts; afterward
`collect-instrument-targets` recomputes analyzer changes and
`instrument-tee-fns!` mutates Malli's global function roster and runs a filtered
global instrumentation call (`eval.cljs:1694-1721`, `:1776-1799`,
`:4181-4199`). That target calculation is not the accepted transaction delta
and can diverge from override filtering or recovery.

Bare REPL registration is a third persistence path.
`tee-registered-schema!` stores a replayable call string after the in-memory
mutation and logs, but accepts, a transaction failure (`eval.cljs:2681-2762`).
The single global `schema/!last-tee` Promise is only an observation seam and is
overwritten by concurrent registrations.

## Mutable-cell disposition

| Cell or registry | Current meaning | Target disposition |
|---|---|---|
| `schema/*schemas` | Mutable form map and live semantic authority | Delete as an incremental authority. Replace with the forms inside one immutable, DB-derived generation. |
| `schema/seon-registry` | `defonce` composite over the mutable map | Replace on each validated generation. Never expect an old compiled Schema to observe a map mutation. |
| Malli `registry*` | Library-owned default-registry pointer | Retain only as an integration handle. Point it at the current immutable generation after publication. |
| Registry stomp watch | Repairs self-host Malli reloads | Retain as one lifecycle-owned named watch, but dereference the current generation and uninstall/reinstall mechanically. |
| Bootstrap schema `defonce` values | Load guards whose values are old whole-map snapshots | Delete with source-snapshot declarations. At minimum, never retain the return of `swap!`. |
| `schema/!tee-fn`, `schema/!last-tee` | Background durability callback and one global Promise | Delete. The one schema transition returns and awaits its own fenced transaction result. |
| `schema/current-keys`, `discard-registrations!` | Key-only diff and partial failed-eval rollback | Delete. Candidate construction occurs before live publication, so rollback is unnecessary. |
| Stored schema call strings | Executable reconstruction input | Delete. One canonical declaration-data codec and one reconstruction path. |
| Persisted entity-schema decomposition | Renderer catalog materialization | Delete by default. Derive once while building the schema generation. |
| `render/!schema-cache` | DB-identity-keyed cache of that decomposition | Delete with the materialization; renderer reads the generation catalog. |
| Malli `-function-schemas*` | Second process-global function roster | Stop populating and reading it. Pass exact `:data` directly to Malli. |
| `instrument/register-target!` | Mutates the Malli roster | Make it a pure exact-entry builder, preserving wrapper-shape selection. |
| `instrument-from-db!` | Whole-roster query/build/register/instrument operation | Split into one cold bulk operation and one exact delta operation. Neither discovers its input. |
| Eval's instrumentation helpers | Separate changed-def analysis and filtered global call | Delete after eval passes the accepted reconciler delta to `seon.instrument`. |
| Malli wrapper markers/originals | Reversible record attached to live function objects | Retain and use through exact `mi/unstrument!`/`mi/instrument!`. Do not mirror in Datahike. |
| `client/core-vars`, `!indexed-test-vars`, `!extra-core-vars` | Parallel desired-program roster inputs | Phase 7 replaces them with one selected file-grouped source snapshot. They are not instrumentation inputs. |
| `repl/!compile-state` | Self-host compiler/analyzer handle | Retain under its own compiler generation. It is reconstructable runtime state, not a durable fact. |

The surrounding atoms in sockets, feeds, error ALS, timers, and test seams are
not schema facts and should not be forced into this transition. Their exact
classifications and teardown requirements are in the mutable-runtime census.

## Malli behavior that fixes the design boundary

### An immutable generation is the natural unit

Malli registries are a protocol with simple, fast, composite, mutable, dynamic,
and lazy implementations. `fast-registry` closes over a map;
`composite-registry` resolves left to right; `mutable-registry` dereferences its
atom for each registry lookup (`reference-code/malli/src/malli/registry.cljc:11-65`).
`set-default-registry!` merely swaps the library's current registry pointer
(`registry.cljc:40-52`).

Building a complete map, wrapping it in a fast/composite registry, validating
all declarations against it, and publishing the registry pointer once is fully
supported. No Malli plugin or serialized registry object is needed.

### Mutating a registry cannot refresh compiled schemas

Malli Schema objects own caches. Validators and explainers use the schema's
cache (`malli/core.cljc:345-361`, `:2627-2658`). Ref schemas memoize their
resolved child and validator (`core.cljc:1969-2008`). Malli's own test changes a
mutable registry from `:int` to `:boolean` and proves an already-created pointer
or ref still dereferences to `:int`
(`reference-code/malli/test/malli/core_test.cljc:3641-3667`).

This is not a Malli defect. A changed canonical form means a new registry
generation and new wrappers for affected contracts. The current mutable map
suggests a live-update guarantee the library intentionally does not provide.

### Exact instrumentation data is already a public option

Malli's CLJS `-strument!` defaults to `(m/function-schemas :cljs)`, but callers
may supply `:data`. The function iterates the supplied nested map and then
applies filters (`malli/instrument.cljs:95-111`). `mi/unstrument!` accepts the
same options (`:148-158`). Therefore:

```clojure
{'my.math
 {'total {:schema <compiled-contract>
          :ns 'my.math
          :name 'total}}}
```

is sufficient for both removal and installation. A filter over the global map
still walks the global map; an exact data map is proportional to the affected
set.

Malli's private function-schema atom offers whole-platform deregistration but
no per-symbol lifecycle (`malli/core.cljc:3052-3088`). Seon should not use it as
a database mirror.

### Wrapper replacement must be explicit

For a simple function Malli records the original on the wrapper and marks both
objects instrumented (`malli/instrument.cljs:77-86`). For multi-arity and
variadic functions it mutates arity slots on the original object
(`:41-75`). `:skip-instrumented?` skips a marked live value; it is a stack guard,
not a schema refresh operation.

The delta transition must call `mi/unstrument!` with the old exact entries,
compile new entries against the candidate registry, then call `mi/instrument!`
with the new exact entries. Before any mutation, reject incomplete
multi-arity/variadic contracts; Malli's current unstrument loop assumes every
instrumented arity has a recorded original (`:113-130`).

### Ref walking gives the dependency graph

Malli ref schemas expose their target through `m/-ref-schema?` and `m/-ref`.
Their walker follows requested refs and tracks already-walked refs, terminating
cycles (`malli/core.cljc:2009-2016`). `m/walk` is the public traversal driver
(`:2612-2625`). This distinguishes a real registry ref from the same keyword
used as a map key, enum member, literal, or property value.

Derive schema-to-schema and function-to-schema edges from compiled candidate
objects. Do not persist them. On a schema change, use reverse transitive closure
in both the old and candidate graphs; an edge removed in the candidate still
affects the old wrapper that depended on it.

## Canonical form codec is a required gate

“Store the full EDN form” is directionally correct but not yet a sufficient
implementation specification. Current runtime forms include values that strict
EDN cannot round-trip:

- `src/seon/db/id.cljc:30-52` registers `[:re <RegExp>]`; `pr-str` emits a
  regex reader literal, while `cljs.reader/read-string` explicitly reads the
  EDN subset and does not accept regex dispatch;
- several registrations contain live predicate functions (`fn?`, `number?`,
  `sequential?`) rather than symbols; and
- bootstrap entries in `seon.schema` contain Malli Schema objects constructed
  by `m/-simple-schema`.

The canonical value must be declaration data captured before those symbols are
resolved to live functions. One codec, colocated with schema ownership, must:

1. accept the schema declaration syntax used by both compiled source and agent
   eval;
2. normalize aliases and auto-resolved keywords to fully qualified symbols and
   keywords;
3. preserve regex literals through a reader that supports Clojure data syntax
   (the already-present `cljs.tools.reader`, whose full reader supports regex),
   or normalize regex to one explicit tagged data representation;
4. reject functions, Malli Schema objects, arbitrary host objects, and
   executable registration-call lists;
5. produce one stable canonical string/value for equality and database diff;
6. decode equivalently in CLJ tests and the CLJS pod; and
7. compile only through the explicitly supplied candidate registry, never by
   evaluating the stored string.

The least custom option is a restricted `tools.reader` form reader followed by
a structural allowlist and canonical `pr-str`. It reads regex and symbols as
data and does not evaluate them. Standard predicate symbols already exist as
keys in Malli's default registry: `predicate-schemas` registers both each
predicate symbol and its live function value
(`malli/core.cljc:2913-2924`, `:300-307`). Custom executable predicates should
be replaced by a named compiled kernel schema or another canonical registered
schema, not serialized as a function.

The protected compiled kernel is the unavoidable base case, analogous to
Malli's built-ins: schema constructors/predicates implemented in code are
available to compile canonical declarations. Application/schema population
facts still come from Datahike. On a populated no-overlay boot, today's compiled
application declarations must not silently replace the stored target.

At minimum the canonical schema entity needs its identity and declaration form.
The namespace is derivable from the key, creation/update time is available from
the form datom's transaction, and renderer/dependency/native projections are
derivable. A normalized writer-facing policy is independently canonical only
when it owns a fact the writer must consume; do not also hide a competing value
inside the Malli form.

## Target process projection

The transition owner publishes one immutable value, conceptually:

```clojure
{:seon.runtime.projection/coordinate <full frozen db coordinate>
 :seon.runtime.projection/schema
 {:seon.schema.generation/forms               {<key> <canonical-form>}
  :seon.schema.generation/registry            <immutable-malli-registry>
  :seon.schema.generation/catalog             {<key> <render-shape>}
  :seon.schema.generation/schema-refs          {<key> #{<key>}}
  :seon.schema.generation/reverse-schema-refs  {<key> #{<key>}}}
 :seon.runtime.projection/program
 {:seon.instrument.generation/function-refs   {<sym> #{<key>}}
  :seon.instrument.generation/function-data   {<ns> {<name> <entry>}}}}
```

This is an explanatory shape, not a request to add a generic state framework or
persist a generation entity. The Phase 2 runtime attachment owner can hold the
current immutable projection. Pure builders remain colocated:

- `seon.schema` parses declarations, builds/validates registry + catalog +
  schema refs, and publishes the schema pointer;
- `seon.instrument` resolves live vars, builds function entries/refs, calculates
  affected symbols, and mutates wrappers; and
- the existing fenced population transition owns read, compare, commit, and
  publication ordering.

If the lifecycle refactor has not yet introduced its one attachment record,
Phase 6 may temporarily own one `seon.schema/!generation` atom. Do not add a
second instrumentation roster atom. Function data can be rebuilt from the
frozen canonical program facts and candidate registry or folded into the later
attachment projection.

All map keys on these APIs and values must be fully namespaced and Malli
specified. No `:type`, `:kind`, stored generation counter, `instrumented?`
datom, dependency datom, or “restored” status fact is needed.

## Exact transition design

### Pure candidate build

Given a frozen database value and optional explicit current-core overlay:

1. query the canonical schema population and program contracts from that same
   database value;
2. apply the selected overlay in memory through the exact population contract;
3. decode every schema form with the one codec and report all invalid rows as
   data;
4. reject protected key collisions and forbidden host values;
5. build the complete form map before compiling any member;
6. derive entity identity properties against that complete map;
7. compare every attribute's complete native storage signature with the
   installed Datahike schema;
8. build an immutable fast/composite Malli registry and compile every schema;
9. derive renderer catalog and schema dependency graph;
10. compile every function contract and derive function dependency edges;
11. validate live arity/async wrapper eligibility; and
12. return the candidate projection, canonical desired facts, and exact
   old/new delta without touching Datahike, Malli's default pointer, live vars,
   or a Seon atom.

Forward references work because step 5 precedes compilation. One bad member
invalidates the candidate as a value; it never creates partial live state.

### Fenced durable decision

The exact population reconciler owns the durable transition:

1. compile against a frozen full database coordinate;
2. if the schema/program delta is empty, perform no transaction;
3. if nonempty, submit it with the expected full-head fence;
4. on fence mismatch, discard the candidate, reread, and rebuild under the
   bounded retry policy; and
5. only a committed candidate is eligible for publication.

Schema publication cannot be an independent listener that races another head.
The same transition result that contains the accepted datoms supplies the
candidate projection. Ordinary domain transactions continue normally; only
canonical schema/program populations require this owner.

### Runtime publication and wrapper replacement

With turn admission closed and a committed candidate:

1. calculate affected symbols as direct definition/spec add/change/remove/delete
   union old/new transitive schema dependents;
2. build the exact old instrumentation map for those symbols;
3. build the exact new map against the already validated candidate registry;
4. call `mi/unstrument!` with old exact `:data`;
5. swap the Seon schema generation and point Malli's default registry at it;
6. install/reconcile the committed live declarations;
7. call `mi/instrument!` once with the nonempty new exact `:data`; and
8. publish the complete runtime projection and reopen admission.

Candidate schemas/contracts must be compiled before step 4. No agent work may
observe the interval between steps 4 and 8. If a post-commit live declaration or
wrapper mutation fails, do not continue with a mixed generation. Mark the
attachment not ready and reconstruct the committed database generation in a
fresh compiler/process. The database commit remains the durable decision.

The exact order of schema-pointer swap and declaration install is allowed to
differ for a fresh runtime versus a delta, but it must be one quiesced
publication operation with a tested rollback/rebuild path. Do not catch, log,
and continue after a partial semantic publication.

### Cold process reconstruction

A populated no-overlay cold start performs no schema/program write:

1. attach one connection to the selected database coordinate;
2. read canonical schema facts and build the validated immutable generation;
3. publish the registry generation once;
4. load safe namespace/function/test declarations once into the fresh compiler
   and `globalThis`; schema registration calls are excluded;
5. build exact instrumentation data for every live specced function against
   that registry;
6. call `mi/instrument!` once with the complete exact `:data`; and
7. start/resume agent hosts and services without any further instrumentation.

This is the only complete instrumentation pass. It is a runtime reconstruction
operation, not a safety function exposed to mint or warnings.

### Runtime schema registration

The current `register!` name conflates a source declaration with a durable
runtime transition. The target must preserve one schema mutation path:

- the source/program snapshot extracts compiled `(schema/register! key form)`
  declarations as data for an explicitly selected current-core overlay;
- a strict standalone agent schema declaration is recognized at the eval
  boundary and routed to the same pure candidate + fenced transaction + publish
  operation; and
- a bare call outside that owner must not mutate the active registry and launch
  a background tee.

Whether the source syntax remains named `register!` or becomes a declaration
macro is less important than the invariant: restoring schema state never
executes stored source, and there is one durable transition. During migration,
do not leave both the self-tee and the canonical operation live.

A compound arbitrary-effect form that both registers a schema and consumes it
later in the same form cannot be made atomic by swapping a process-global Malli
registry before commit. Either the eval boundary splits a recognized standalone
declaration into the schema transition, or the form is rejected with guidance
to commit the declaration first. A fiber-local staging map cannot make Malli's
process-global default safe for concurrent consumers.

### Agent definition and hot reload deltas

An agent `defn` still executes once; Seon never promises arbitrary effect replay.
After successful compilation/evaluation, exact whole-row program facts are
committed. Omitted spec/error fields are explicitly retracted. The accepted
delta then drives wrapper replacement. If the durable decision fails after a
live definition was installed, readiness closes and the prior committed
program is reconstructed; instrumentation never blesses the uncommitted value.

Shadow reload is the same transition with a selected changed-source overlay.
Because Shadow installs JavaScript before its build notification, a failed
fenced commit similarly requires closed readiness and reconstruction/restart.
It must not fall back to a global scan or keep serving a source/DB mismatch.

## Minimal in-place implementation and deletion map

### `src/seon/schema.cljc` and `src/seon/schema/internal.cljc`

- Add the one canonical form encode/decode/validation boundary.
- Replace per-key `swap!` semantics with pure complete-candidate construction.
- Expose accessors over the current immutable generation rather than
  `*schemas`.
- Derive identity properties, catalog, and schema ref graph in the candidate.
- Publish/relink exactly once after the durable decision.
- Make the stomp guard follow the current generation.
- Delete tee atoms/functions, keyset diff/rollback, entity-schema tx builders,
  and map-retaining bootstrap guards.

Do not create `seon.schema.v2`, `schema-new`, or a parallel registry namespace.
The existing schema namespace owns the corrected mechanism.

### `src/seon/instrument.cljc`

- Keep live-var resolution, original-function unwrapping, async shape routing,
  custom injecting/Promise-aware wrapper, error reporter, and derived coverage
  census.
- Change `register-target!` into a pure entry builder accepting an explicit
  registry.
- Add one complete operation for a fresh runtime and one exact delta operation.
- Pass explicit exact `:data` to both Malli calls.
- Remove reads/writes of `m/function-schemas :cljs`, the broad database query
  from instrumentation, and the compile-time `collect!` alternate roster after
  fixtures migrate.

The complete and delta operations receive their program facts/delta; they do
not query a second roster or infer changes from live globals.

### `src/seon/eval.cljs`

- Remove schema key snapshots and partial rollback.
- Remove executable schema-member reconstruction and call-string
  classification.
- Remove the self-tee installation and bare registration persistence path.
- Make exact program rows retract omitted spec/schema-error attrs.
- Feed only the committed population delta to `seon.instrument`.
- Delete `collect-instrument-targets` and `instrument-tee-fns!` after cutover.

### `src/seon/client.cljs`

- Call registry restoration and complete instrumentation only in fresh-runtime
  reconstruction.
- Remove complete instrumentation from general `start-agent!`.
- Remove it from `after-reload`; the selected source snapshot owns reload.
- Stop evaluating schema calls in declaration loading.
- Phase 7's one file-grouped snapshot replaces `core-vars`, mutable extra/test
  rosters, duplicate core graph construction, and ghost pruning.

### `src/seon/render.cljs`

- Read entity/render shape data from the current immutable schema generation.
- Delete DB schema decomposition queries and the full-DB-value cache.

### Tests and warnings

- Migrate direct Malli/`collect!` fixtures to the production exact-data builder.
- Keep `coverage-gaps` as a derived diagnostic over canonical facts and live
  vars; do not store coverage status.
- Remove warning text that tells an operator to invoke the global repair pass.
- Do not add tests for exact warning/context wording.

## Mechanical proof matrix

### Canonical facts and codec

- A canonical form containing forward refs, a predicate symbol, a regex, and
  render properties round-trips CLJ/CLJS and compiles equivalently.
- A form containing a live function, Malli Schema object, host object, reader
  eval, or registration-call list is rejected before a transaction.
- Two semantically identical accepted declarations normalize to one equal
  canonical value.
- Every current schema key has exactly one full declaration fact after
  migration; no form is truncated or call-shaped.
- The namespace and update time are projected from key/datom transaction unless
  a benchmarked query explicitly ratifies materialization.

### Registry generation

- Mutually forward-referencing schemas validate regardless of declaration
  order.
- A same-key form replacement is present in the exact delta.
- One invalid form leaves the live generation object and Malli default pointer
  `identical?` to their prior values.
- A deleted still-referenced schema fails before commit.
- A changed native storage facet fails with migration data; a validator-only
  refinement changes no native Datahike declaration.
- An agent entity schema is renderable immediately after commit and cold restore
  without decomposition datoms.

### Instrumentation behavior

- Fresh reconstruction invokes an injected Malli instrument seam exactly once
  with the complete exact target map.
- Mint, resume, config apply, render, and an HTTP/server restart inside the same
  reconstructed JS runtime invoke zero additional instrumentation work.
- A body/spec redefinition processes exactly one symbol and leaves wrapper
  depth one.
- Spec removal and definition deletion unstrument the old symbol and leave no
  Seon/Malli roster entry.
- `F -> A -> B -> C`, changing `C`, rewraps `F`; an unrelated wrapper remains
  `identical?`.
- Removing an old dependency edge still refreshes its former dependent because
  the affected set uses both generations.
- A function changed directly and by schema dependency is processed once.
- Complete multi-arity/variadic contracts survive exact unstrument/reinstrument;
  incomplete coverage is rejected before live mutation.
- Existing async Promise output/rejection/injection behavior passes through the
  one builder.

### Lifecycle and failure atomicity

- A populated no-overlay boot writes zero schema/program facts, evaluates zero
  schema registration source, publishes one registry generation, and performs
  one bulk instrumentation call.
- A selected current-core overlay reads each source file once, commits one exact
  transaction when changed, and hands that same delta to publication.
- A fenced head mismatch publishes nothing, rereads, and recompiles.
- A database failure during agent schema registration leaves DB, live registry,
  and wrapper identities unchanged.
- A forced post-commit wrapper/install failure closes readiness; no agent call
  observes mixed old/new state, and reconstruction from the committed DB
  restores the exact behavior.
- Restart from the same store produces the same validation/render behavior
  without effect replay.

Tests should assert datoms, transaction counts, selected symbols, wrapper
identity/depth, registry identity, and injected call counts. Do not use fragile
wall-clock thresholds or exact context prose. A synthetic large roster can
assert that one leaf update's inspected target count equals its dependency
closure rather than the total population.

## Source index

- Current mutable registry, stomp guard, self-tee, keyset rollback, and entity
  decomposition: `src/seon/schema.cljc:26-82`, `:86-161`, `:189-308`,
  `:310-410`.
- Current load-order validation: `src/seon/schema/internal.cljc:117-143`.
- Current broad instrumentation and Malli roster mutation:
  `src/seon/instrument.cljc:474-512`, `:531-705`.
- Reload trigger: `src/seon/client.cljs:287-318`.
- Connection/native full-schema transaction: `src/seon/client.cljs:637-666`.
- Declaration loading: `src/seon/client.cljs:841-968`.
- Core index and duplicate ghost pass: `src/seon/client.cljs:1870-2123`.
- General start path and bulk instrumentation:
  `src/seon/client.cljs:2522-2730`.
- Schema call reconstruction and eval delta paths:
  `src/seon/eval.cljs:710-830`, `:1694-1839`, `:2195-2325`,
  `:2681-2762`, `:3924-4031`, `:4181-4199`.
- Malli registry implementations:
  `reference-code/malli/src/malli/registry.cljc:11-104`.
- Malli cache/ref behavior:
  `reference-code/malli/src/malli/core.cljc:345-361`, `:1940-2025`,
  `:2627-2658`.
- Malli mutable-registry cache proof:
  `reference-code/malli/test/malli/core_test.cljc:3641-3667`.
- Malli function-schema roster and registration:
  `reference-code/malli/src/malli/core.cljc:3052-3088`.
- Malli explicit instrumentation data, wrapper markers, and unstrument:
  `reference-code/malli/src/malli/instrument.cljs:41-130`, `:148-158`.
- ClojureScript regex printing and strict EDN reader boundary:
  `reference-code/clojurescript/src/main/cljs/cljs/core.cljs:10525` and
  `reference-code/clojurescript/src/main/cljs/cljs/reader.cljs:150-190`.
- Full tools.reader regex support:
  `reference-code/clojurescript/src/main/clojure/cljs/vendor/clojure/tools/reader.clj:86-105`,
  `:807-823`.
