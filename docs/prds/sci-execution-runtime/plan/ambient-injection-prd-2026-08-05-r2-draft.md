---
type: prd
status: complete
tags: [prd, runtime, platform, sci, database, program-graph]
---

# Call preparation r2 — RULED (owner, 2026-08-05)

> Design complete under the 2026-08-05 ruling batch in [the active plan](README.md); implementation is owned by [the P17 slice plan](p17-ambient-slices-2026-08-05.md).

Graduated from draft the same day: seam = the SCI-fork hook primitive;
arity model = derived all-or-nothing shorter call shapes with
`db?`/`connection?` predicate dispatch (owner reframing — supersedes
this document's subset-expansion Choice 2 and OVERRULES its Choice 4
Option A: ruling #41's positional db/conn omission survives through the
general planner; only the bespoke seon.db implementation dies); maps =
present top-level argument map, explicitly REQUIRED keys only, no
nesting, no parent construction. Rulings recorded in
[README.md](README.md). P17 remains hard-blocked on P12.

## Draft status and preparation record

This document is an unruled replacement draft for
[the first ambient-injection PRD](ambient-injection-prd-2026-08-05.md). Its
`-draft` suffix is deliberate. Nothing proposed below is a ruling until the
owner iterates on it.

Preparation for this revision included complete reads of:

- the repository `AGENTS.md`;
- [the first ambient-injection PRD](ambient-injection-prd-2026-08-05.md);
- [the indexing-completeness audit](../research/indexing-completeness-2026-08-05.md);
- the complete P12 and P17 sections of
  [the 2026-08-05 state of the program](state-of-the-program-2026-08-05.md);
  and
- the complete **Indexing keeps the whole parse** and **Call preparation**
  rulings in [the active plan](README.md)'s 2026-08-04 ruling batch.

The current implementation owners named below were read directly, including
all of `src/seon/fn.clj` and `src/seon/program.cljc`. The relevant pinned
Malli, SCI, clj-kondo, and Datahike sources were also checked rather than
inferred from APIs.

## Verdict

The ruled direction survives, but the first PRD's claimed input is false.

**A function's own `:malli/schema` is its complete request for supplied defaults.** There is
no function-side injection metadata. At a call originating in an agent's SCI
evaluation, the runtime supplies only supplied defaults that the selected arity
declares and the caller omitted. A caller-supplied value, including supplied
nil, is never replaced. A function that declares no matching input is called
unchanged. An unavailable default is a flat `:seon.error` value and is never
injected as nil.

The missing prerequisite is a typed call address. Today
`:seon.fn.arity/input-refs` says only that an arity mentions a named schema. It
does not say which argument index mentions it, whether the occurrence is the
whole positional slot or a map entry, or which source binding occupies that
slot. P17 therefore remains blocked on the relevant P12 facts and their
publication proof.

The r2 design has four owners, in dependency order:

1. P12 publishes exact per-arity argument, binding, map-entry, and return
   facts.
2. Declared default-provider rows join those facts to named provider
   functions.
3. One SCI call-preparation seam compiles and applies invocation plans under
   the existing calling cluster's `seon.db/*conn*` binding.
4. The bespoke argument elision inside `seon.db` is deleted after every agent
   call uses the general seam.

## Ruled invariants this draft does not reopen

- The function contract is the request. No `^:inject`, function metadata,
  parameter annotation, namespace convention, or function roster is
  admissible.
- Ambient availability is declared by database rows. Adding a battery is
  adding a provider function plus data, not editing a dispatch map.
- Explicit caller data wins. Presence is tested with argument occupancy and
  `contains?`, never truthiness.
- Open maps remain open. Injection fills a declared missing key; it does not
  reject unrelated keys.
- `seon.db/*conn*` remains the one dynamic source of calling-agent cluster
  custody. No provider reads `seon.effect/*request-context*`, a global cluster
  map, or an invocation request carrying a second connection.
- Ambient preparation is not an effect request. `seon.effect` remains the one
  system-side owner for genuine fs, web, LLM, and database-write capability
  requests; it is not a function-call dependency injector.
- Every function remains callable. Ambient discovery changes arguments, not
  callability.

## Dependency ledger

### Selected dependency revisions

| Dependency | Revision checked | Required behavior |
|---|---|---|
| Malli | `80138076960e` | Function arity information and parsed schema forms in `reference-code/malli/src/malli/core.cljc:2192-2202,2276-2295,2819-2908`; instrumentation invokes the wrapped function after input preparation in `:3110-3131`. |
| SCI | `2db3358cba91` | Ordinary analyzed calls go through `return-call`/`fn-call` in `reference-code/sci/src/sci/impl/analyzer.cljc:1717-1753` and `reference-code/sci/src/sci/impl/evaluator.cljc:384-420`; the existing observers observe but do not transform arguments. |
| clj-kondo | `57252e07975710aa579b24f0d1b2b1e04195caa2` | Analysis supplies arglist strings, but `:arglists` metadata may override what is reported (`reference-code/clj-kondo/analysis/README.md:102-117`). It is a cross-check, not the source-binding authority. |
| Datahike | `c15272730e74` | Component refs own nested function facts; unique identities and ordinary Datalog joins own provider and shared-shape discovery. |

### Existing Seon mechanisms

- Static function rows retain exact source, one serialized arglists value, and
  canonical `:seon.fn/spec` in `src/seon/fn.clj:292-357`.
- `seon.program/contract-facts` compiles the Malli function schema once and
  emits arity and generic AST facts in `src/seon/program.cljc:255-315`.
- Static publication adds those facts in `src/seon/fn.clj:1031-1058`; runtime
  definition publication uses the same `seon.program` owner through
  `src/seon/sci/eval.clj`.
- Exact replacement currently owns only `:seon.fn/arities` and
  `:seon.fn/ast` in `src/seon/program.cljc:450-469`; the contract backfill
  knows only those roots in `src/seon/fn.clj:1060-1114`.
- The cluster SCI context holds one connection at
  `:seon.sci.eval/custody :seon.db/connection`. `evaluate` rebinds
  `seon.db/*conn*` from that context in `src/seon/sci/eval.clj:1588-1610`, and
  named `kernel/invoke` does the same in `src/seon/sci/kernel.clj:315-354`.
- Agent-authored function roots receive the current Malli wrapper in
  `src/seon/sci/eval.clj:646-656`; compiled first-party functions are installed
  as live JVM Vars in `src/seon/sci/eval.clj:783-834` so hot reload remains
  visible.
- Scheduled fires do not directly invoke a function in current source.
  `seon.schedule` commits an ordinary message telling the agent which call to
  make (`src/seon/schedule.clj:155-267`). The active plan now separately rules
  a target turn-free operation path whose schedule proc makes a direct Var
  call on its `:io` proc with one recorded ordinary request map and no SCI
  evaluation. Those system-side handlers receive explicit request data and
  are not an ambient-injection entrance. P17 must not route them through the
  guarded compute-oriented `kernel/invoke`.

### Existing recurring proof surfaces

- `test/seon/program_test.clj` owns pure contract projection and exact
  component replacement.
- `test/seon/fn_test.clj` owns database publication, same-transaction
  assertions, and backfill.
- `test/seon/sci/eval_test.clj` owns runtime function installation and
  two-connection custody.
- `test/seon/instrument_test.clj` owns interpreted contract wrapping.
- `test/seon/schedule_test.clj` owns scheduled-message routing, not direct
  invocation.

## What exists now and what P12 must add

### Current per-arity facts

At the current source boundary, an arity row has:

- `:seon.fn.arity/order`;
- serialized Malli `:seon.fn.arity/arity`;
- `:seon.fn.arity/min` and optional `:seon.fn.arity/max`;
- component refs `:seon.fn.arity/input`, `/output`, and optional `/guard`; and
- set-valued `/input-refs`, `/output-refs`, and `/guard-refs`.

This is implemented by `arity-row` at `src/seon/program.cljc:261-283` and
declared in `resources/seon/schemas/seon.fn.arity.edn:1-33`.

The generic AST retains more than the first PRD acknowledged. Ordered
`:cat` children remain ordered components, and map entries retain an order, a
child AST, and a key rendered with `pr-str`. That is a recovery oracle, but it
is not the typed provider join P17 needs: the key is a string rather than a
keyword fact, there is no per-arity argument entity, and there is no connection
to the source binding. A query can match a known serialized string, but doing
so bakes serialization knowledge into the consumer and still cannot answer the
complete call address.

The return location also exists today as `:seon.fn.arity/output`. What is
missing is a shared, queryable return-shape identity. P12 is upgrading return
identity, not discovering that an output exists.

### Required P12 fact model

P12 supplies the following facts before P17 compiles any invocation plan.
Names below follow the indexing-completeness design and remain draft names.

#### Per arity

- existing `order`, `arity`, `min`, and optional `max`;
- `:seon.fn.arity/arguments`, an ordered vector of component argument rows;
- `:seon.fn.arity/return-schema`, one ref to the shared schema-shape row; and
- optional `:seon.fn.arity/guard-schema`, also a shared-shape ref.

#### Per argument

- `:seon.fn.argument/order`, the source-vector order;
- `:seon.fn.argument/index`, the zero-based actual call position;
- `:seon.fn.argument/rest?`, true only for the binding after `&`;
- `:seon.fn.argument/binding`, a component source-binding tree;
- `:seon.fn.argument/schema`, the complete Malli slot shape;
- optional `:seon.fn.argument/label` for a `:catn` label; and
- the complete Malli regex-tail shape for a rest binding.

The indexing audit proposed an optional single
`:seon.fn.argument/rest-element-schema`. That is valid only when the parsed
tail proves one repeated element. Malli regex tails can be compositions, so
P12 must always retain the complete tail and may derive a single element only
for shapes that establish one. P17 does not inject a rest tail in its first
implementation.

#### Per source binding

- exact canonical EDN form as the fidelity oracle;
- queryable shape in `#{:symbol :map :sequential}`;
- symbol leaves and ordered nested children;
- map destructuring entries with their source key, nested/local binding, and
  spelling in `#{:explicit :keys :strs :syms}`;
- `:as`, `:or`, nested sequential destructuring, and rest children.

This tree describes source syntax. It is not a second schema and it does not
decide injection.

#### Per schema shape and entry

- content-addressed `:seon.schema.shape/fingerprint`;
- canonical normalized form and comparison status;
- ordered children and typed entry rows;
- connections from named `:seon.schema` rows that have that shape;
- for every map entry: typed key, order/path, optionality, and child shape; and
- connections from every argument, return, guard, and nested entry to its
  shape.

P17 needs argument index plus the typed map path/key and value shape. P12 keeps
the whole path even if P17 initially admits only a direct entry in an argument
map.

### Source-to-contract join

The join must be bijective and must fail loudly:

1. Parse each exact stored `defn`/`defn-` declaration form under
   `*read-eval* false` through one shared function-signature parser. The
   runtime reader's existing `defn` logic at `src/seon/sci/reader.cljc:238-286`
   is the starting evidence; build and runtime publication must not invent
   separate grammars.
2. Treat clj-kondo arglist strings as a consistency check only when analysis
   proves they were not replaced by legitimate `:arglists` metadata. Record an
   override as such and keep the exact declaration form authoritative; an
   expected override disagreement is not a refusal.
3. Derive fixed count or variadic minimum/rest index from the exact form.
4. Match source and Malli arities by fixed count or variadic minimum, not
   incidental vector order.
5. Join fixed Malli slots to source bindings by zero-based actual-call index;
   join the complete Malli regex tail to the one binding after `&`.
6. Refuse a non-bijection, unmatched slot, unsupported declaration form, or an
   analyzer disagreement only when no declared arglists override explains it.
7. Assert the spec, arities, arguments, bindings, shapes, return, guard, and
   generic fidelity oracle atomically.

### P12 dependency boundary for P17

P17 is hard-blocked on all of the following:

1. installed Datahike declarations for arguments, bindings, schema shapes,
   and typed entries; P17 owns the later `seon.ambient` row schema and facts,
   while P12 supplies the generic function/schema connections they reference;
2. a stable per-arity argument address: fixed/rest classification, argument
   index, typed map path/key, and normalized value-shape fingerprint, including
   convergence of equal registered and inline declarations;
3. the exact-form source/Malli bijection and its loud refusals;
4. identical static and runtime-definition publication;
5. atomic replacement and redefinition, including retraction of old
   argument/binding components while shared content-addressed shapes remain
   global;
6. updated presence/backfill checks, pulls, program snapshots, and the SCI
   `doc` consumer;
7. a Datalog regression proving the positional and map-key cases no longer
   collapse; and
8. complete `current-src` republication plus a fresh-cluster acquisition proof,
   because schema-resource changes cannot graduate through an incremental
   publication alone.

P12's clj-kondo occurrence expansion is not an injection dependency. Complete
destructuring and return facts are P12 graduation requirements, but the P17
argument transformer consumes only argument addresses and schema shapes.
P17 must not begin production implementation against a partial or provisional
address model merely because those are the only fields it reads.

The old indexing audit's 174+49 connection-renaming census is also no longer a
P17 dependency at the current revision: the broad
`:seon.store/branch-connection` and `:seon.config/connection` wave has already
left live `resources/`, `src/`, and `test/`. Remaining role-specific connection
keys require their own semantics and are not a reason to reopen that wave here.

## Declared default-provider rows

### Row contract

An entity is injectable only when one database row has all three facts:

- proposed `:seon.ambient/key` — unique qualified-keyword identity;
- proposed `:seon.ambient/schema` — ref to the registered schema whose P12
  shared shape is the value contract; and
- proposed `:seon.ambient/provider` — ref to the provider's `:seon.fn/sym`
  row.

The effective join follows `:seon.ambient/schema` to
`:seon.schema/shape`. This keeps initialization data authorable by registered
schema identity while allowing an equal inline contract shape to converge on
the same P12 fingerprint. The provider symbol is data; it is resolved live in
the acquired cluster context so hot reload remains visible. A cached plan
never captures a function root.

The declarations live as initialization rows, following the existing
descriptor-row pattern in `config/default.edn`; the schema lives under
`resources/seon/schemas/`. There is no Clojure map of supplied default keys, no case
expression, and no naming convention. Adding a third battery adds one row and
one ordinary contracted provider function.

A missing or malformed provider row is a publication/acquisition core fault.
An honestly declared provider that has no value in this invocation returns the
agent-facing unavailable error described below.

Publication/acquisition also proves provider coherence from program facts: the
provider has zero arguments, its declared return contains exactly the row's
successful value shape plus `:seon.error/value`, and the referenced function
exists in the acquired program. Call preparation validates every non-error
provider result against the row's value shape even when the ordinary
instrumentation dial is `:record`. A declared or actual mismatch is a core
fault at the provider boundary; it is never injected and disguised as the
target function's Malli violation.

### Initial registry

| Supplied-default key | Declared value schema | Named provider behavior |
|---|---|---|
| `:seon.db/db` | `:seon.db/database-value` | Read the already-bound `seon.db/*conn*` once and return its current immutable database value; unavailable without a live calling-agent binding. |
| `:seon.db/connection` | `:seon.db/connection` | Return the already-bound live connection itself; unavailable without a live calling-agent binding. |

Provider function names remain an owner choice; descriptive candidates are
`seon.db/ambient-database-value` and `seon.db/ambient-connection`. Each takes no
arguments, declares an output of its value schema or `:seon.error/value`, and
reads only `seon.db/*conn*`. An unavailable provider can report only its local
cause; it does not know the target function or argument address. The
call-preparation owner enriches that cause from the compiled plan into the
target-specific face below.

For a map entry, provider selection joins both the declared key and the value
shape. For a positional slot, selection joins the slot shape. If multiple
provider rows ever share one positional shape, plan compilation refuses the
ambiguity; it does not pick by row order.

## Invocation-plan derivation

One Datalog query by function identity returns every arity, argument index,
rest classification, typed map path/key, value shape, and matching provider
row. It never reads `:seon.fn/spec`, source text, generic AST strings, or a
hand-maintained provider list.

The query result compiles to immutable call data keyed by:

```clojure
[function-identity contract-assertion-transaction provider-basis-transaction]
```

The cache is owned by one acquired cluster context; it is never process-global
across sovereign branches. Its key uses transactions, not a database value.
The provider basis is derived as the newest transaction that asserted or
retracted any provider-row fact; it is not a stored counter.

Acquisition derives the provider attributes from the declared provider-row
schema, registers a Datahike listener for those attributes, and stores the
complete provider snapshot plus its checked-through basis in one cluster-local
atom adjacent to the program snapshot. The attribute set is therefore a query,
not an embedded watch list.

The listener is an eager invalidation optimization, not the correctness
boundary. Datahike exposes the committed database on its connection before it
delivers public listener callbacks. Every invocation therefore dereferences
the calling connection once and defines its linearization point at that
database value's basis transaction. If the context snapshot is checked through
that basis, invocation uses its plan immediately. If the database basis is
newer, invocation synchronously derives the complete provider snapshot from
that immutable database value and CASes the context state before plan lookup.
Concurrent refreshes keep the newest checked-through basis. Unrelated
transactions may cause one conservative refresh; no stale empty plan can
survive a provider addition, retraction, or new ambiguity.

Runtime function publication likewise evicts that function identity before
the new root is visible. A newly acquired context starts with an empty plan
cache. The steady empty-plan path is one connection dereference, one
context-state read/basis comparison, and one cache lookup followed by the
original invocation: no provider call, Datalog query, argument copy, or map
allocation. The benchmark includes those operations and a concurrent-provider
publication falsifier proves the basis boundary.

### Map preparation

For a present map argument:

1. inspect only entries selected by the compiled plan;
2. use `contains?` at the exact key;
3. retain a present caller value byte-for-byte/identity-for-identity, including
   nil; and
4. call the provider and associate its value only when the key is absent.

The first implementation should support direct entries of the argument map.
P12 still indexes nested paths. Constructing an absent parent map introduces
new questions about required parents, defaults, and multiple constructors, so
nested construction remains an explicit option below rather than an implicit
behavior.

### Positional preparation

For each fixed arity, plan compilation derives the accepted supplied-argument
patterns:

- an exact full-arity call always invokes unchanged;
- for a shorter call, consider subsets of declared supplied default argument positions
  whose removal makes the supplied count fit;
- map supplied values left-to-right onto the remaining indexes, then insert
  provider values at the recorded supplied default argument indexes; and
- accept the expansion only when exactly one arity and one omitted-slot subset
  fit. Otherwise return the ambiguity error without calling the target.

This is the only rule that permits a leading or middle ambient to be elided.
It also makes the reinterpretation explicit: a shorter call's arguments name
the explicit-argument positions only when the plan is unique. A variadic ambient tail
is indexed but never auto-filled until a declared provider fact can answer how
many repetitions to supply.

A positional slot is eligible only when the slot's complete normalized shape
matches a provider value shape. Merely containing that shape below `:or`, a
collection, a map entry, or a variadic regex tail does not make the whole slot
injectable. Map-entry injection is planned separately by its typed key/path.

## Invocation seam

### What current seams cover

- `kernel/invoke` is the guarded named-function entrance used by render and
  other explicit named calls. It does not observe `(outer)` calling `(inner)`
  inside `sci/eval-form`.
- `instrument/wrap-interpreted` is installed only for agent-authored
  interpreted functions and returns the original function in `:record` mode.
- compiled first-party functions remain raw live JVM Vars in the SCI context;
  replacing them with ordinary SCI proxy Vars would change current Var
  identity, read-only metadata, and hot-reload behavior.
- SCI's current built-in/interop observers discard their return values. They
  cannot prepare arguments.

Therefore neither `kernel/invoke` alone nor the current instrumentation wrapper
implements the ruled all-program-function behavior.

### Recommended seam

Add one narrow, optional, hook-aware invocation primitive to the maintained
SCI fork. SCI's analyzed `return-call`/`fn-call` path and Seon's named
`kernel/invoke` both call that primitive, so ordinary nested calls and explicit
named SCI calls share one call-preparation owner rather than reproducing its
logic at two entrances.

The installed hook receives the context, a provable program-function identity,
the resolved callable, and evaluated arguments. It returns either prepared
arguments or the flat default-supply error. The normal call then proceeds, so Malli
validates the completed arguments and the ordinary function body remains
unchanged. `kernel/invoke` continues to own arming, admission, and failure
classification; the shared SCI primitive owns only call preparation and
application.

The hook must meet these constraints:

- direct symbol calls and nested calls are covered when the resolved Var gives
  a program identity. A callable passed as an ordinary value is not covered in
  v1 unless SCI still presents the identity-bearing Var itself; raw function
  roots, closures, and metadata guesses are untouched;
- raw compiled Vars remain raw/live, and the hook dereferences their current
  root through SCI's existing behavior;
- agent-authored functions retain their existing Malli wrapper, with ambient
  preparation outside and before input validation;
- provider errors short-circuit and are returned as the call result; the
  target body is not entered and the error is not injected as an argument;
- the hook is absent/no-op in non-Seon SCI contexts; and
- the optimized zero/one/two-argument SCI paths remain measured. P17 does not
  trade a ubiquitous call regression for convenience.

Both `evaluate` and `kernel/invoke` already establish the correct dynamic
`seon.db/*conn*` binding before they reach the shared primitive. The hook and
provider therefore compose with one custody path. Turn-free scheduled operator
handlers remain explicit system-side JVM calls under their sealed `:io` proc
contract. No new flow proc, channel, effect request, dynamic Var, or cluster
lookup is introduced by call preparation.

## Failure faces

### Ambient unavailable

Recommended value shape:

```clojure
{:seon.error/kind :seon.ambient/unavailable
 :seon.error/message
 "Cannot call sample/read: ambient :seon.db/db is unavailable because this invocation has no calling-agent cluster connection."
 :seon.error/data
 {:seon.fn/sym "sample/read"
  :seon.ambient/key :seon.db/db
  :seon.ambient/schema :seon.db/database-value
  :seon.ambient/provider "seon.db/ambient-database-value"
  :seon.fn.argument/index 0}}
```

For map placement, data also carries the typed map path. The headline names
the target and missing default in user vocabulary; it does not expose a
dynamic Var. The zero-argument provider returns a local flat unavailable cause
instead of nil. Call preparation preserves that cause in error data, adds the
target/key/schema/provider/address facts from the plan, constructs the honest
headline, and returns the enriched flat value directly instead of feeding it
into Malli.

### Ambiguous positional elision

Recommended kind: `:seon.ambient/ambiguous-call`. Its message names the target,
the supplied count, and the candidate arities/ambient indexes. This is a
compiled-plan refusal whenever ambiguity is structural; a call-time value is
needed only if the ambiguity depends on supplied count. The runtime never
guesses by arity order.

### Explicit invalid value

An explicit nil or wrong-shaped value is not an ambient-unavailable error.
Caller presence wins, the value reaches ordinary Malli input validation, and
the existing contract-violation face owns the result.

## Falsifiers and acceptance evidence

### The probe that would have stopped r1

A load-only probe against current `seon.program/contract-facts` compared a
positional contract with a map-entry contract that both referenced one named
ambient schema. The observed result was:

```clojure
{:positional-input-refs #{[:seon.schema/key :sample/ambient]}
 :map-input-refs        #{[:seon.schema/key :sample/ambient]}
 :equal?                true
 :typed-joined-placement? false}
```

That probe belongs as the first recurring P12 falsifier. After P12 it must
query, without parsing strings, two different argument addresses: the
positional argument index and the map argument index plus typed key/path.
The generic AST already distinguishes their structure through ordered
components and serialized keys; the failed premise is the absence of typed,
joined per-arity address facts, not the absence of all recoverable structure.

### P12 recurring proofs

| Claim | Proof surface |
|---|---|
| Positional and map-key occurrences have distinct queryable addresses; source binding shape and return shape are present. | Extend `test/seon/program_test.clj`'s `parsed-contract` examples. |
| Every contracted arity has a complete ordered argument vector and one return shape in the same assertion transaction as its spec. | Extend the database proof in `test/seon/fn_test.clj`. |
| Exact redefinition retracts old arity-owned argument/binding components and points to the new shapes. | Extend `test/seon/program_test.clj`'s replacement proof. |
| Runtime-authored contracted definitions publish the same facts as static indexing. | Extend `test/seon/sci/eval_test.clj`. |
| Exact source arities and Malli arities join bijectively; every fixed/rest slot joins once; shape reconstruction round-trips. | One fixed-seed generative property beside `seon.program-test`. |

### P17 behavior matrix

- **Positional argument:** `[left db right]` called as `[left right]` receives
  the current immutable database value at index 1 and retains the two explicit
  values in order.
- **Map key:** an argument map declaring `:seon.db/db` receives it only when
  that key is absent.
- **Elided arity:** one unique shorter-call expansion succeeds; an exact arity
  wins unchanged; multiple candidate expansions yield the flat ambiguity
  error.
- **Caller supplies explicitly:** a context from cluster A plus an explicit
  immutable database value from B delivers B unchanged. A foreign explicit
  write connection remains subject to `seon.db`'s existing custody refusal.
- **Supplied nil:** map `contains?` or a full positional slot preserves nil;
  Malli, not injection, rejects it.
- **Unavailable:** a program-acquired context whose custody connection is nil
  returns the flat unavailable value, and a counter proves the target body was
  not entered. A raw base context is not this proof because it may fail to
  resolve the program function first.
- **Undeclared:** under the same context, an undeclared function receives no
  extra argument or map key and no provider is called.
- **Nested direct call:** an interpreted outer function directly calls a
  declared inner Var and the inner receives the ambient. This falsifies a
  `kernel/invoke`-only design.
- **Higher-order/unprovable callable:** passing a root function or computed
  closure as ordinary data leaves it untouched unless SCI still supplies the
  identity-bearing program Var. This proves the runtime does not guess an
  identity from metadata or source spelling; general higher-order identity is
  outside v1.
- **Compiled first-party:** an interpreted form directly calls a compiled
  first-party function with an elided declared ambient. This falsifies an
  agent-wrapper-only design.
- **Registry-derived:** a test transacts a synthetic third provider row and a
  matching declared function; injection succeeds without editing runtime
  dispatch code.
- **Provider coherence:** a row whose value schema disagrees with the
  provider's declared success branch is refused at publication/acquisition;
  a provider that returns a wrong-shaped success at runtime becomes a core
  provider fault before the target is called.
- **Per-cluster custody:** two cluster contexts call the same function while
  the host thread has a deliberately conflicting outer `seon.db/*conn*`
  binding. Each result names only its context's cluster.
- **Per-cluster plan isolation:** warm a plan for function identity F in
  cluster A, then call the same identity in sovereign cluster B where its
  contract has a different or no ambient declaration. B must compile from its
  own acquired facts rather than reuse A's plan.
- **Provider publication race:** warm an empty plan, commit a matching provider
  row, and coordinate an invocation after the connection exposes the new basis
  but before the listener callback updates context state. The basis comparison
  must synchronously refresh and use the new plan; no sleep or listener timing
  is the proof.
- **Rest:** a declared ambient rest tail is indexed but an elided call returns
  the explicit unsupported/ambiguity value; it never invents a count.
- **Schedule:** until the turn-free operations target lands, the existing test
  continues to prove only message routing and no test pretends a direct call
  exists. The sealed turn-free target separately proves a direct JVM Var call
  with its explicit recorded request and no SCI evaluation; it is not a P17
  ambient test.

### Performance and live proof

- A deterministic counter test proves an empty plan performs no provider
  lookup, Datalog query, argument copy, or map construction.
- The unavailable-face example asserts the kind, target function, ambient
  key/schema/provider, and exact positional index or map path; its headline
  contains the target and key, excludes `seon.db/*conn*`, and the target-body
  counter remains zero.
- A retained paired benchmark consumes results and compares steady-state
  empty-plan calls with current direct SCI calls. Its baseline and results are
  recorded in P17 research; timing is not a unit-test assertion.
- The reset-boundary proof performs complete `current-src` publication,
  acquires a fresh cluster, runs positional/map/explicit cases, stops and
  reopens the cluster, and repeats the queryable facts and one call. A healthy
  agent cluster necessarily has database custody, so unavailable database
  injection remains the acquired-nil-custody unit proof rather than a fake
  live-agent scenario. A second cluster in the same JVM proves custody and
  plan-cache independence.

The focused recurring gate is the affected `seon.program-test`,
`seon.fn-test`, `seon.sci.eval-test`, and eventual ambient invocation-owner
namespace. The final gate includes complete publication and fresh-cluster live
evidence; a fixture-only green suite cannot prove the new schema facts were
published or acquired.

## Deletion and migration boundary

Once the general seam proves both database defaults:

1. remove `current-database-value` and `current-connection` from
   `src/seon/db.clj`;
2. remove per-function query/input realignment whose only purpose is ambient
   insertion after the owner selects the collision-free API conversion below;
   retain Datahike's positional and argument-map vocabulary, not today's
   ambiguous short Clojure arities;
3. preserve the explicit foreign-connection custody check for writes;
4. convert `seon.db/db`, `q`, `pull`, `pull-many`, entity/index/history helpers,
   and `transact!` to receive the already-prepared explicit value;
5. convert SCI `doc`, backfill, bootstrap examples, grader checks, and every
   live `input-refs` consumer before deleting the lossy ref attributes; and
6. prove no second agent-facing ambient path remains. `evaluate` and
   `kernel/invoke` bind `seon.db/*conn*`; the two declared providers read it to
   supply values; and `seon.db`'s retained transaction boundary may read the
   same binding only to reject foreign write custody and select the existing
   agent-facing transaction-report projection. No other function resolves a
   missing default from it.

The required current-reader sweep is explicit, not deferred to a vague final
`rg`:

- delete `current-database-value` and `current-connection` and remove the
  ambient branches of `seon.db` functions;
- change `my.background/poll` and `seon.search/search` to declare the database
  value/connection they consume and use the injected explicit value;
- thread an explicit connection from the schema-declared public call through
  `seon.fs.jvm` and `seon.web.jvm` private blob helpers instead of reading the
  dynamic Var there;
- make `seon.render`'s walk use the database value/connection already present
  in its explicit walk context rather than rebinding as an ambient resolver;
- retain and name separately the `seon.db` transaction-boundary reads used by
  `foreign-connection-error` and the agent-facing transaction-report
  projection, because they enforce/describe custody rather than supply a
  missing argument; and
- require the final source sweep to find no other reader. At current source
  the named non-owner sites are `src/my/background.clj:54`,
  `src/seon/search.clj:411`, `src/seon/fs/jvm.clj:453`,
  `src/seon/render.clj:521-523`, and
  `src/seon/web/jvm.clj:301-303,384-389`.

System-side compiled Clojure callers outside SCI are not silently granted
call preparation. They pass database values/connections explicitly. If a
current system caller relies on `seon.db`'s bespoke elision, the P17 consumer
sweep converts it before deletion.

## Open choices for owner iteration

### Choice 1 — interception seam

**Option A — hook-aware SCI invocation primitive (recommended).** Route both
SCI's analyzed call path and `kernel/invoke` through one optional preparation
primitive. This guarantees declared ambient behavior for direct and nested
agent-authored, compiled first-party, and named kernel calls whose resolved Var
proves program identity. Cost is a narrow change to the maintained SCI fork
plus a ubiquitous hot-path benchmark. It preserves raw compiled Var identity
and gives up v1 injection for passed function roots, closures, and other
callables with no retained Var identity.

**Option B — always-on Seon Var wrappers.** Smaller first-party change and
already compatible with agent-authored SCI roots. It must be separated from
the `:panic`/`:record` instrumentation dial. It either gives up compiled
first-party coverage or changes the current raw-Var identity/hot-reload
contract; global JVM Var wrapping is inadmissible because clusters would
share it.

**Option C — `kernel/invoke` only.** Lowest implementation cost, but guarantees
only explicit named entrances. It gives up ordinary nested function calls and
therefore does not satisfy the ruled direction. Not recommended.

The effect request handler is not an option: it would misclassify ordinary call
preparation as a capability effect.

Provider matching by P12's normalized shape fingerprint is not an open choice.
The indexing ruling makes equal registered and inline declarations the same
queryable shape, and P17 is explicitly blocked on that P12 work. Matching only
the registered schema spelling would contradict the ruled schema-as-request
model.

### Choice 2 — positional elision

**Option A — unique expansion at recorded indexes (recommended).** Supports
leading, middle, and trailing ambients. It guarantees no guess: exact calls
win, and only one arity/omitted-slot subset may fit. Cost is explicitly
interpreting shorter arguments as the non-ambient slots.

**Option B — suffix-only elision.** Preserves ordinary left-to-right Clojure
argument meaning with the smallest semantic surprise. It gives up leading and
middle positional batteries; callers must use an argument map or pass those
values explicitly.

### Choice 3 — nested map paths

**Option A — direct argument-map entries only for v1 (recommended).** The
runtime fills a missing key only when its containing argument map already
exists. P12 still indexes every nested path. This gives up automatic
construction of nested parent maps until their semantics are declared.

**Option B — construct unique nested paths.** Supports deeper declarations but
must specify missing-parent construction, defaults, optional parents, and
conflicts. The cost and semantic surface are substantially larger; ambiguity
must refuse rather than merge guesses.

### Choice 4 — converting `seon.db` without preserving hidden elision

The current API cannot simply lose its helper functions. `q` hides the
database inside Datahike's `:args` vector; `pull`/`pull-many` have short ambient
arities whose counts collide with explicit overloads; `db` has a zero-argument
ambient arm; and `transact!` has a one-argument ambient arm. P12 addresses do
not make a value absent from those current contract slots.

**Option A — collision-free explicit cores plus request maps (recommended).**
Delete every bespoke short ambient arm. Make the complete positional contract
put database/connection in its own leading slot: `db` takes connection;
`transact!` takes connection plus transaction; entity/index/history functions
take database plus their ordinary inputs. Their ambient shorter calls become
ordinary unique leading-slot injection.

Variadic positional `q` is different: `[database query & inputs]` overlaps
ambient `(q query & inputs)` at every supplied count of two or more, so exact
full-arity precedence cannot distinguish them. Its positional interface is
therefore explicit-database-only. Ambient `q` uses the one open Datahike-key
request map (`:query` and optional `:args`) whose contract also declares
`:seon.db/db`; callers write the Datahike keys and injection supplies the
missing Seon database key. The implementation may place that already-explicit
database into Datahike's parsed `$` input position; that is dependency
translation, not custody lookup.

For `pull`/`pull-many` and any other count collision, likewise keep the full
explicit positional form and make the ambient form the one open Datahike-key
request map (`:selector` plus `:eid`/`:eids`) whose contract declares
`:seon.db/db`. Cost: a breaking caller sweep and deletion of ambient positional
`q`, ambiguous short `pull` forms, and other colliding spellings. Guarantee: no
wrong-shaped explicit database is silently reinterpreted.

**Option B — schema-aware overload fallback.** Preserve short positional
`pull` forms by trying the exact-count contract first and considering ambient
expansion when it does not validate. This keeps more call spellings but turns
an invalid explicit call into a different valid ambient call, weakening the
literal caller-wins rule and making overload selection value-dependent. Not
recommended.

**Option C — exempt `seon.db`.** Retain current short arms and
`aligned-query-arguments` as a special ambient implementation. This minimizes
the caller migration but leaves the exact second mechanism the ruling requires
P17 to delete. Rejected.

## Graduation gate

P17 graduates only when all of these are simultaneously true:

- P12's exact argument/address/shape facts are queryable in a freshly
  published cluster and static/runtime definitions agree;
- provider discovery is entirely row-driven and a synthetic provider proves
  accretion without runtime dispatch edits;
- direct, nested, and compiled first-party SCI calls meet the
  selected seam's stated guarantee;
- positional, map-key, elided-arity, explicit-caller, supplied-nil,
  undeclared, unavailable, and two-cluster cases pass;
- unavailable custody is an honest flat error and the target body is not
  entered;
- empty-plan overhead is measured and accepted;
- bespoke `seon.db` argument elision and lossy `input-refs` consumers are gone;
  and
- complete publication, fresh acquisition, stop/reopen, and two-cluster live
  proof all agree with the recurring suite.

Anything less leaves two ambient mechanisms or asks runtime code to reconstruct
facts P12 was supposed to declare.

## Output-quality finding

The current unavailable-custody error in `src/seon/db.clj:103-119` says
`No current cluster connection is bound to seon.db/*conn*.` That is an ugly
agent-facing face: it exposes an internal dynamic Var and omits the target
function and requested ambient. P17 replaces it with the honest
`:seon.ambient/unavailable` face above. The load-only premise probe also emits
the JVM's incubator-module warning before its useful EDN result; that is noisy
developer output, not a runtime render contract.
