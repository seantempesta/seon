---
type: research
status: completed
tags: [research, agent, schema, database]
---

# Incremental function instrumentation audit

## TL;DR

The current pod has one good idea buried inside two broad mechanisms: an agent
`defn` is already instrumented immediately after its successful eval, but cold
boot, every warm `/agents/new`, and every Shadow reload still reach a complete
database scan. The broad pass is not merely wasteful. It reads stale program
facts during hot reload, cannot make already-compiled validators observe a
changed referenced schema, and uses Malli's process-global function-schema atom
as a second roster beside the database.

The target should have exactly two operations in `seon.instrument`:

- one boot-only operation over the complete, already-validated program snapshot;
  it constructs exact Malli instrumentation data and calls `mi/instrument!` once;
- one delta operation after a committed definition/schema transaction; it
  filtered-unstruments and re-instruments only the union of directly changed
  functions and the old/new transitive dependents of changed schema keys.

Malli already supplies the important primitives. `mi/instrument!` and
`mi/unstrument!` accept an explicit `:data` map, so Seon does not need to
populate `malli.core/-function-schemas*` at all. `m/walk` plus
`m/-ref-schema?`/`m/-ref` derives real schema edges without keyword text scans,
including refs behind local recursive registries, and terminates on cycles.
Compiled Malli refs and validators deliberately cache their children, so every
affected wrapper must be rebuilt against the new immutable registry projection.

Two correctness bugs have to be fixed in the same work:

- eval currently snapshots only `schema/current-keys`. Redefining an existing
  schema key is invisible to persistence, dependency invalidation, and failure
  rollback;
- removing or invalidating a function spec through an agent redefinition does
  not retract the old `:seon.fn/spec`, and the incremental path does not
  unstrument it.

Do not add an `instrumented?` datom, a second function registry, dependency
datoms, or a context-wording test. Program/schema facts remain canonical in
Datahike; the registry, ref graph, compiled function schemas, and wrapper census
are one disposable runtime projection.

## Scope and method

This audit covers the active CLJS pod only:

- `src/seon/instrument.cljc`;
- the eval/analyzer detect-and-tee path;
- boot, program indexing/loading, and Shadow reload wiring in
  `src/seon/client.cljs`;
- the current Malli registry in `src/seon/schema.cljc`;
- the canonical `:seon.fn`/`:seon.schema` facts; and
- the vendored Malli CLJS implementation under `reference-code/malli/`.

The paused JVM instrumentation component is a separate lane and is not a reason
to preserve the pod's mechanism. No production code was changed for this audit.
Source locations refer to the shared worktree as observed on 2026-07-12.

Three small JVM-side Malli probes were run against the pinned dependency:

- a function contract `F -> A -> B -> C` produced refs `A`, `B`, and `C` through
  `m/walk`;
- a cycle `A -> B -> A` terminated and still exposed `C` on the non-cyclic
  branch; and
- a local `:schema {:registry {...}}` recursive ref was traversed and exposed a
  global ref nested inside it.

A fourth probe built a validator while `A` was `:int`, changed the mutable
registry entry to `:string`, and observed that the old validator still accepted
integers while a newly built validator accepted strings. This matches Malli's
own mutable-registry cache tests.

## What runs today

### Cold boot and warm mint share the complete pass

`start-agent!` does all of the following before its per-agent setup:

1. opens or reuses the cluster connection;
2. rebuilds/indexes the core program and schema rows;
3. prunes ghosts using another complete desired graph;
4. reconstitutes agent namespaces into the self-host compile state; and
5. calls `instrument/instrument-from-db!` over the complete database roster.

The instrumentation call is at `src/seon/client.cljs:2690-2704`. The same
`start-agent!` is injected into `POST /agents/new` with `:mint? true` at
`src/seon/client.cljs:2791-2801`, so a warm mint repeats the complete pass even
though it created only one agent and no runtime generation changed.

`replay-program-graph!` calls the low-level `seon.eval/eval`, not
`eval-batch!` (`src/seon/client.cljs:895-1017`). Therefore declaration loading
does not run the eval tee's per-definition instrumentation. The one complete
pass after loading is the actual cold-reconstruction mechanism. Comments that
say replayed functions were already wrapped inline are stale.

### Hot reload also scans everything, from stale facts

`seon.client/after-reload` calls `instrument-from-db!` at
`src/seon/client.cljs:286-317`. It does not reconcile the changed source/spec
facts first. A changed core function is a fresh live JavaScript function, but
the pass reads its old `:seon.fn/spec` from Datahike. This is the open C61 defect
already recorded in `docs/seon/orchestrator/issues/dual-code-paths-registry.md`.

The current pod log made the waste concrete. Between 14:37 and 14:46 on the
audit date it recorded one boot pass and thirteen reload passes. Every line
reported 708 registered targets and four structural opt-outs. These reloads
were ordinary concurrent source edits, not thirteen runtime reconstructions.
The `registered` statistic counts rows routed into Malli's roster; under the
skip flag it does not prove that 708 live wrappers were replaced.

Shadow already exposes an exact reload input. Its node client receives
`{:info {:compiled <resource-ids> :sources [...]}}`, selects files from those
ids, reloads them, and only then invokes build notification/hooks
(`reference-code/shadow-cljs/src/main/shadow/cljs/devtools/client/node.cljs:22-46`
and `:162-167`). A `:build-notify` callback therefore can hand the exact changed
namespace set to the same program reconciler, rather than asking instrumentation
to discover changes with a whole-program scan.

### Agent definitions use a targeted but separate path

The successful eval path is closer to the target:

- `changed-defs` identifies new/redefined analyzer vars, including the
  body-only redefinition rescue;
- `collect-instrument-targets` extracts each changed function's schema; and
- `instrument-tee-fns!` registers those targets and calls `mi/instrument!` with
  a filter for only those namespace/symbol pairs.

The code is at `src/seon/eval.cljs:1763-1790`, `:1845-1868`, and
`:4391-4407`. It runs after `record-eval!`, which is the correct side of the
durable write in the successful case. It is nevertheless a second
implementation: it mutates Malli's function roster directly and knows nothing
about schema dependents, spec removal, program-row deletion, or the exact
program reconciler delta.

## Every complete-pass trigger

| Trigger | Current call | Why it is wrong |
|---|---|---|
| Fresh pod reconstruction | `client.cljs:2701` | This is the one legitimate complete pass. |
| Warm `/agents/new` | same `start-agent!` call | No runtime reconstruction occurred; it scans the complete roster. |
| Any later `start-agent!`/start helper | same call | Mint/resume/service startup is coupled to instrumentation. |
| Every Shadow reload | `client.cljs:314` | Scans all facts and can enforce the old persisted spec on a new live fn. |
| Manual root warning repair | prose at `agent/ctx/warnings.cljs:126-127` | Teaches a human/agent to invoke the broad mechanism instead of repairing the exact delta. |

Tests also call Malli directly or use the old `collect!` macro. Those are test
fixtures, not runtime triggers, but they preserve a third registration path and
should migrate to the same public target API.

## Malli facts that constrain the design

### Explicit `:data` removes the duplicate roster

In CLJS, `malli.instrument/-strument!` defaults `data` to
`(m/function-schemas :cljs)`, but accepts caller-supplied `:data`
(`reference-code/malli/src/malli/instrument.cljs:95-111`). The outer `doseq`
walks every entry before applying filters. Supplying a filter over a global map
therefore remains O(all registered functions); supplying an exact data map is
O(the affected functions).

The required entry shape is ordinary data:

```clojure
{'my.example
 {'total {:schema <compiled-function-schema>
          :ns 'my.example
          :name 'total}}}
```

`mi/unstrument!` accepts the same explicit data option
(`reference-code/malli/src/malli/instrument.cljs:148-158`). Seon can derive this
map from the canonical program snapshot, pass it directly, and stop writing to
Malli's private `-function-schemas*` atom. The database remains the only roster.
Malli exposes whole-platform deregistration but no per-symbol deregistration in
that atom (`malli/core.cljc:3052-3088`), which is another reason not to use it as
Seon's lifecycle store.

Accordingly, current `register-target!` should become a pure target-data builder
instead of calling `m/-register-function-schema!`. The compile-time `collect!`
macro and its test-only alternate roster should be deleted after its tests use
the canonical builder.

### `skip-instrumented?` is not a contract refresh

Malli marks both the original and the simple wrapper with
`malli$instrument$instrumented?`, and records the original under
`malli$instrument$original` (`instrument.cljs:77-86`). With
`:skip-instrumented? true`, `-strument!` skips a live value carrying the flag
(`instrument.cljs:103-111`).

Therefore a second full pass does not make an already-wrapped simple function
observe a changed spec or referenced schema. Current comments in
`src/seon/instrument.cljc:548-560` that describe simple functions as rewrapped
by the skip pass are not supported by the pinned Malli source. A natural
`defn` redefinition heals only because Shadow/self-host installed a fresh,
unflagged function object.

### Validators and refs cache the old registry generation

Malli ref schemas memoize the referenced schema and validator
(`reference-code/malli/src/malli/core.cljc:1969-1999`). The project's custom
`injecting-fschema` also closes over `vin`, `vout`, and the declared injectable
keys when Malli instruments it (`src/seon/instrument.cljc:382-471`). Malli's own
test explicitly proves a schema built from `mr/mutable-registry` continues to
see the old entry after the backing atom changes
(`reference-code/malli/test/malli/core_test.cljc:3641-3667`).

This is correct immutable-value behavior, not a Malli bug. A changed schema
definition creates a new effective function contract. Every dependent wrapper
must be rebuilt against the new registry generation; mutating a map behind the
old compiled object can never update it.

### Malli's walk is the dependency parser

Ref schemas expose their actual target through `m/-ref-schema?` and `m/-ref`.
Their walker follows refs only when `::m/walk-refs` permits it and tracks
already-walked refs to terminate cycles (`malli/core.cljc:2009-2016`). Schema
wrappers are controlled by `::m/walk-schema-refs`
(`malli/core.cljc:2085-2089`). `m/walk` is the public postwalk driver
(`malli/core.cljc:2612-2625`).

This is better than walking EDN keywords:

- a map entry key, enum member, metadata value, and real registry reference are
  different Malli nodes even if they contain the same keyword;
- explicit `[:ref k]`, bare registered-key refs, `:schema` wrappers, and local
  recursive registries use the same ref protocol; and
- cycle termination is already Malli's responsibility.

### Filtered unstrument has one prerequisite

Malli's CLJS unstrument path loops over every live fixed arity and restores its
`malli$instrument$original` value (`instrument.cljs:113-130`). If a multi-arity
function was instrumented from an incomplete schema, an uncovered arity has no
recorded original; Malli writes nil into that accessor. Seon's smoke fixture
already avoids unrelated namespaces for exactly this reason
(`test/seon/instrument_smoke_test.cljs:65-72`).

The clean immediate policy is to validate at program-candidate time that every
instrumentable multi-arity/variadic function has a complete matching
`:function` contract. Reject the bad declaration before any wrapper mutation.
The structural async opt-out remains unchanged. This gives the filtered
unstrument/instrument sequence a mechanically proven precondition without
forking Malli or copying its var-surgery code. The small nil-guard is worth an
upstream Malli report, but Seon should not rely on an incomplete function
contract even after that guard is fixed.

## Current mutable state and caches

| Cell/cache | Current role | Target disposition |
|---|---|---|
| `seon.schema/*schemas` atom | Mutable forms map and de facto schema authority | Replace with one immutable, DB-derived registry projection swapped only after complete validation. |
| `seon.schema/seon-registry` | Defonce composite over the mutable atom | Replace per registry generation; do not expect old compiled schemas to see mutations. |
| Malli `registry*` | Library-owned global default pointer | Necessary integration handle; point it at the current immutable projection. It is not durable authority. |
| schema stomp-guard watch | Repairs self-host Malli bundle reloads | Retain only while those loads can replace Malli's pointer; make it re-point to the current projection, not a frozen registry object. |
| `seon.schema/!tee-fn` | Late-bound DB durability callback | Delete with the DB-first canonical schema write path; it permits in-memory success plus durability failure. |
| `seon.schema/!last-tee` | Test handle for the callback's Promise | Delete with the callback; tests await the real transaction result. |
| Malli `-function-schemas*` | Seon's second function roster | Stop populating it in the pod; pass exact `:data` to instrument/unstrument. |
| compiled Malli Schema caches | Ref targets, validators, parsers | Legitimate immutable per-generation cache; discard/rebuild only affected function schemas. |
| wrapper `malli$instrument$original` markers | Var-local reversible wrapper record | Retain and use through Malli's filtered unstrument/instrument path. No Seon mirror. |
| `seon.repl/!compile-state` | Self-host analyzer/runtime handle | Legitimate irreducible runtime object; definition deltas come from it, but it is not canonical durability. |
| `client/core-vars`, `!indexed-test-vars`, `!extra-core-vars` | Compiled roster inputs | Fold into the one deterministic program snapshot/reconciler; do not let instrumentation read separate lists. |
| eval's `defs-before` | Per-form analyzer snapshot | Legitimate lexical diff input. |
| eval's `schemas-before` key set | Per-form schema diff | Insufficient; replace with a full form snapshot or, preferably, a staged canonical schema delta. |
| `instrument-from-db!` local volatile stats | One-call accumulator | Harmless lexical state, but the broad operation becomes boot-only. |

`seon.instrument/injectables` is an immutable code registry, not durable state.
The wrapper closes over the declared key set but reads provider functions at call
time; a changed request schema still requires the wrapper rebuild described
above.

## Correctness defects found

### Existing schema-key changes are invisible

`schema/register!` performs `swap! assoc` on the live forms atom
(`src/seon/schema.cljc:227-268`). Eval snapshots only `(set (keys @*schemas))`
through `current-keys` (`schema.cljc:270-277`) and calculates
`set/difference` after the form (`src/seon/eval.cljs:2271-2288`).

Consequences:

- changing the form for an existing key produces no `:seon.schema` tee row;
- no instrumentation target is selected;
- the live mutable registry and canonical database disagree;
- restart restores the old form; and
- if the eval later fails, `discard-registrations!` receives only newly added
  keys, so it does not restore the existing key's prior form
  (`eval.cljs:4205-4215`).

This is a failed-transaction leak into process-global semantic state. The fix is
not a better keyset diff. A schema change must be staged as data, validated
against the whole candidate, committed, and only then installed as the new
projection.

### Spec removal is not an effective definition update

For agent definitions, `build-tee-entities` omits `:seon.fn/spec` when metadata
is absent or invalid (`eval.cljs:2311-2362`). Datahike identity upsert does not
retract an omitted cardinality-one datom. The boot-only core index has a special
stale-spec retraction (`client.cljs:2037-2049`), but the agent eval path does
not. `collect-instrument-targets` also selects only functions with a parseable
schema, so it never unstruments a function whose spec disappeared.

In-session, the redef installed a fresh unwrapped JavaScript function; after
restart, the stale database spec can wrap it again. Exact whole-row program
reconciliation must retract the old spec/schema-error as appropriate, and the
same delta must route the symbol through targeted unstrument even when its new
spec is absent.

### Schema changes never select transitive function dependents

The eval path's target set is `changed-defs` only. Neither it nor the full pass
derives refs from function contracts. Since existing wrappers cache validators,
changing `C` in `F -> A -> B -> C` leaves `F` enforcing the prior generation
unless the function itself happens to be redefined/reloaded.

### The hot-reload pass can install the wrong contract

Shadow replaces the live var first. `after-reload` then queries the old
`:seon.fn/spec` because no program reconciliation ran. The fresh var is
unflagged, so Malli can wrap it successfully—with the old contract. A green
coverage census proves only that a wrapper exists, not that it matches the
current source or registry generation.

### The current full pass registers skipped rows before one global call

`instrument-from-db!` reads/parses/resolves every row and mutates Malli's global
function-schema map through `register-target!`, then calls `mi/instrument!` over
that entire map (`src/seon/instrument.cljc:577-628`). A stale entry from an older
run can therefore remain in the library roster even when it is no longer a
canonical program fact. `coverage-gaps` avoids some false positives by querying
Datahike again, but that is a census layered over duplicated rosters rather than
one roster.

### The eval and program-acceptance target sets can diverge

The core-override guard filters `tee-entities`, but
`collect-instrument-targets` independently recomputes analyzer changes from the
unfiltered source. Likewise, `record-eval!` recovery can report a dropped tee
while later code still runs best-effort instrumentation. The target must be the
accepted, committed program delta—not a second analysis of the attempted form.

## Target runtime model

### One canonical input, one disposable projection

The program reconciler produces one validated snapshot/delta from Datahike:

```clojure
{:seon.instrument/defined-syms        #{'my.math/total}
 :seon.instrument/retracted-syms      #{}
 :seon.instrument/changed-schema-keys #{:my.math/amount}
 :seon.instrument/registry-generation <derived-runtime-value>}
```

The actual request/response schemas must live in `seon.instrument` and use fully
namespaced keys. The generation is derived from the canonical form map and is a
runtime cache key, never a datom. The program snapshot owns source/spec facts;
instrumentation does not query a second roster or recompute a source diff.

The one runtime projection contains:

- the immutable Malli registry for the generation;
- canonical parsed schema forms;
- schema-key forward/reverse ref graphs;
- function-symbol to direct schema-ref sets;
- compiled function-schema objects for currently wrapped functions; and
- the renderer catalog if the wider schema refactor colocates it there.

It is safe to lose and fully reconstruct from the database. No wrapper state,
dependency edge, generation, or `instrumented?` fact is persisted.

### Boot-only bulk operation

After the complete registry is installed and safe declarations are live:

1. derive exact instrumentation data for every live specced function in the
   validated program snapshot;
2. structurally exclude `async-unwrappable?` functions;
3. validate live arities against every function contract;
4. call `mi/instrument!` once with `{:data exact-data :report report-fn}`; and
5. return namespaced stats/target symbols as data.

This operation is private to a fresh runtime reconstruction transition. It is
not called by mint, resume, config apply, render, server start, or ordinary
transactions. Declaration loading itself does not instrument per definition;
the one call happens after the complete live program exists.

### Post-boot delta operation

After a program/schema transaction commits and its live declarations are
installed:

1. take the union of directly defined/redefined symbols and old/new transitive
   schema dependents;
2. add spec-removal and definition-retraction symbols;
3. deduplicate so a function changed by both its own definition and a schema
   change is processed once;
4. call `mi/unstrument!` with exact data for those currently wrapped symbols;
5. build new function-schema objects against the candidate registry;
6. omit deleted, unspecced, and structural opt-out functions; and
7. call `mi/instrument!` once over only that exact remaining map.

For a normal one-function eval, the target map contains one function. A schema
transaction may legitimately contain several dependents, but it is still a
delta pass, not a global safety pass. Unaffected wrapper object identity must
remain unchanged.

The transition coordinator closes agent-turn admission while swapping the
registry and wrappers. Candidate schemas/function contracts are fully compiled
before any live mutation. If Malli var surgery nevertheless fails, the pod must
not reopen in a mixed generation: restore the prior projection/wrappers or fail
the runtime and let the supervisor reconstruct from the committed database.

## Exact dependency derivation

### Direct schema edges

For each canonical schema key `k`, compile its form against the complete
candidate registry and walk the schema object. Collect `m/-ref` for every
`m/-ref-schema?` node, filtered to canonical global keys.

Use this walk policy:

- `::m/walk-schema-refs true` so `:schema` wrappers are traversed;
- follow a ref when it is a local-registry key, so global refs nested inside a
  local recursive definition are visible;
- do not follow a canonical global key while deriving direct edges; record it
  and let the graph supply transitivity; and
- reject a local property-registry key that collides with a canonical global
  key, because Malli resolution scope would make a keyword-only edge ambiguous.

Conceptually, the ref predicate is
`(complement canonical-schema-keys)`; Malli's own walked-ref set terminates local
cycles. The resulting graph is `schema-key -> #{direct global schema keys}`.

### Function edges and affected closure

Compile each changed function contract with `m/function-schema` against the
same candidate registry and apply the same direct-ref walk. Maintain
`fn-sym -> #{direct global schema keys}` in the disposable projection.

For changed keys:

1. construct reverse schema edges;
2. compute the reverse transitive closure in both the old and candidate graphs;
3. select functions whose direct refs intersect either affected set; and
4. union them with directly changed/spec-removed/deleted functions.

Using both generations matters when an update removes an old dependency edge.
The old wrapper is still affected even if the candidate graph no longer reaches
it. Candidate validation ensures no dangling reference survives a removal.

This algorithm does not mistake keyword data for refs and does not require a
stored graph. Its work on a schema update is proportional to the schema graph
closure plus the affected function set; boot is the only all-function build.

## Hot reload integration

Do not make instrumentation infer a reload by scanning all live vars. Reuse the
deterministic program snapshot from the program reconciliation phase:

1. configure a Shadow `:build-notify` for watched client builds;
2. on successful `:build-complete`, derive the changed namespace set from
   `:info/:compiled` joined to `:info/:sources`;
3. route those namespaces through the one source-file-grouped program snapshot
   builder and exact database reconciler;
4. keep agent-turn admission closed until that asynchronous reconcile/runtime
   transition completes; and
5. pass the reconciler's accepted delta to `seon.instrument`.

The notification is delivered after Shadow loads the JavaScript, so the live
vars are present. The database delta must be committed before agents can call
them. Remove only the instrumentation work from `after-reload`; its unrelated
wake/ticker/server hooks are separate lifecycle refactors.

Compiled top-level `schema/register!` calls must become desired-program inputs,
not immediate mutation of the active registry. Otherwise Shadow changes the
semantic registry before the database/candidate transition begins. The wider
DB-canonical schema refactor is therefore a prerequisite, not optional cleanup.

## Mechanically testable acceptance matrix

These are structural tests. None assert context prose.

### Pure registry/ref graph tests

- Forward refs validate in one complete candidate regardless of declaration
  order.
- Recursive `A -> B -> A` terminates.
- `F -> A -> B -> C`, changed `C`, selects only `F` and other true dependents.
- A keyword used only as a map entry key, enum value, property value, or literal
  is not a dependency.
- A global ref behind a local recursive `:schema {:registry ...}` is found.
- A local/global registry-key collision is rejected before swap.
- Equal canonical form maps produce no new generation/delta.
- A same-key form change appears in `:seon.instrument/changed-schema-keys`.
- Removing a still-referenced key invalidates the candidate without changing the
  live projection.

### Wrapper behavior tests

- Fresh reconstruction invokes the injected Malli instrument seam once with the
  complete exact target map.
- A new function gets one wrapper; valid input/output pass and invalid input
  reports the existing structured Malli envelope.
- A body/spec redefinition leaves wrapper depth one: wrapper -> original, whose
  own `malli$instrument$original` is absent.
- Changing a leaf schema flips validation in its dependent function while an
  unrelated wrapper remains `identical?`.
- A transitive schema change produces the same result.
- Removing a spec unstruments the function and does not leave it in any Seon
  roster.
- Deleting a definition unstruments it; deletion of the actual live var remains
  the program loader's responsibility.
- A function changed directly and through a schema dependency is wrapped once.
- Complete multi-arity and variadic contracts survive
  unstrument/re-instrument with every arity callable.
- An incomplete multi-arity contract is rejected before mutation.
- All existing async simple-wrapper, rejection-recording, injection, and
  structural opt-out tests continue through the one target API.

### Integration and live proofs

- Cold boot emits exactly one structured `:seon.instrument/mode :bulk` event and
  `coverage-gaps` is empty.
- Warm mint, existing-agent resume, config apply, render, and server restart emit
  no instrumentation event and do not query the function roster.
- An agent-defined function emits one `:delta` event naming that symbol.
- Redefining it changes only its wrapper and persists the exact whole row.
- Changing a referenced schema persists the new full form and names exactly the
  transitive dependent symbols in the delta result.
- A failed schema candidate leaves the database, registry object, and wrapper
  identities unchanged.
- A Shadow function/spec edit reconciles the new database fact before reopening
  work and enforces the new contract immediately.
- A Shadow deletion retracts the program row and removes its instrumentation.
- Restart from the same store reconstructs the same validation behavior without
  eval replay.
- A synthetic large roster changing one leaf reports target work equal to its
  closure, never the total function count. Assert inspected target counts, not a
  fragile wall-clock threshold.

Return stats from operations as namespaced data so tests can count calls without
a process-global metric atom. Live structured logs may expose counts/timing; they
are observations, not authority.

## Implementation map

### `src/seon/instrument.cljc`

- Keep the live-var resolver, original-function unwrapping, async shape
  classifier, injecting wrapper, error reporter integration, and derived
  coverage census.
- Replace mutating `register-target!` with a pure exact-data builder that accepts
  an explicit immutable registry.
- Add the boot-only complete operation and one delta operation, both map-in/
  map-out with fully namespaced schemas.
- Pass exact `:data` to Malli; stop populating/reading
  `m/function-schemas :cljs` in the pod.
- Delete the compile-time `collect!` alternate path after fixtures migrate.

### `src/seon/schema.cljc` and `schema/internal.cljc`

- Replace per-key live mutation with complete candidate construction and one
  immutable projection swap.
- Store/read full canonical forms; no source truncation.
- Derive form changes, ref graphs, and registry generation as data.
- Delete the tee callback/last-Promise split path.
- Make rollback unnecessary: an invalid or failed transaction never mutates the
  active projection.

### `src/seon/eval.cljs`

- Replace schema keyset snapshots with staged full-form deltas.
- Make exact whole-row program transactions retract omitted spec/error fields.
- Feed only the accepted transaction delta to the one instrumentation API.
- Remove direct `malli.instrument` and duplicate target collection from eval.

### Program reconciliation and `src/seon/client.cljs`

- Call the complete operation once after cold declaration loading.
- Remove instrumentation from the combined mint/resume/start path.
- Reconcile Shadow's changed namespaces first, then apply the exact delta.
- Remove the broad pass and its stale comments from `after-reload`.

### Warning/debug surface and tests

- Keep `coverage-gaps` derived from canonical facts plus live vars.
- Replace the warning's manual global-pass instruction with exact lifecycle
  repair/restart guidance; do not add a wording assertion.
- Consolidate direct Malli/`collect!` fixtures around the production data builder
  and delta API.

## Settled recommendations

- Use Malli's explicit instrumentation `:data`; do not maintain a Seon mirror of
  `-function-schemas*`.
- Use Malli schema objects/ref walking, never regex or raw keyword occurrence.
- Treat schema changes as effective function-contract changes.
- Take the union of old/new dependency closures so removed edges refresh old
  wrappers too.
- Require complete multi-arity contracts before targeted unstrument.
- Instrument one complete runtime once, then accepted deltas only.
- Keep all dependency/version state disposable and DB-derived.
- Make failed candidate construction leave semantic runtime state untouched.

## Source index

- Seon wrapper routing and broad pass:
  `src/seon/instrument.cljc:148-219`, `:348-528`, `:531-705`.
- Agent incremental path:
  `src/seon/eval.cljs:1763-1868`, `:2257-2422`, `:4024-4407`.
- Current schema mutation/tee state:
  `src/seon/schema.cljc:26-82`, `:195-314` and
  `src/seon/eval.cljs:2775-2886`.
- Program facts: `src/seon/agent.cljs:183-217`.
- Core index stale-spec special case:
  `src/seon/client.cljs:1917-2070`.
- Cold/warm complete-pass call and reload call:
  `src/seon/client.cljs:2569-2775`, `:286-317`.
- Malli explicit data, wrapper markers, and unstrument:
  `reference-code/malli/src/malli/instrument.cljs:77-130`, `:148-158`.
- Malli's process-global function-schema registry:
  `reference-code/malli/src/malli/core.cljc:3052-3088`.
- Malli refs, walking, and caching:
  `reference-code/malli/src/malli/core.cljc:1940-2047`, `:2085-2089`,
  `:2612-2625`.
- Malli mutable-registry cache proof:
  `reference-code/malli/test/malli/core_test.cljc:3641-3667`.
- Shadow exact reload facts:
  `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/client/node.cljs:22-46`,
  `:162-167` and
  `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/client/env.cljs:179-188`.
