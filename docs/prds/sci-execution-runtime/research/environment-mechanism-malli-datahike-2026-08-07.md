---
type: research
status: complete
tags: [research, runtime, schema, database]
---

# Environment as an explicit value: what Malli and Datahike actually do

Source-read of `reference-code/malli/` and `reference-code/datahike/` to ground
Seon's "one mechanism supplies platform capability as an explicit environment
value, never ambient thread state" design. Every claim below carries a
`file:line`. Both libraries contain the disease and the cure; the interesting
material is exactly *where each one drew the line*.

## 1. Malli's registry story, end to end

### The registry is a protocol with four trivial implementations

`malli.registry` is 105 lines total. The whole abstraction is:

- `reference-code/malli/src/malli/registry.cljc:11-13` — `defprotocol Registry`
  with `-schema` and `-schemas`. Two methods.
- `:17-22` `fast-registry` — a `java.util.HashMap` (1024 / 0.25 load factor)
  wrapped in a `reify`; `-schemas` still returns the original immutable map, so
  the mutable-for-speed container never escapes.
- `:24-28` `simple-registry` — the map itself is the lookup function.
- `:30-34` `registry` — the coercion: `nil` → `nil`, already-a-Registry →
  itself, a plain `map?` → `simple-registry`. **A bare Clojure map IS a
  registry.** This is the single most important line for Seon: the protocol
  exists for extension, but the ordinary caller passes data.
- `:54-59` `composite-registry` — `(some #(-schema % type) registries)`, i.e.
  ordered fallback, plus `-schemas` merging in reverse so earlier wins.
- `:67-71` `var-registry` — `(-schema [_ type] (if (var? type) @type))`. A
  registry whose "storage" is the Clojure var system itself.
- `:81-95` `lazy-registry` — a provider function with a memo atom, composed
  onto a default.

### The mutable default is one atom, isolated in 7 lines, and disableable

- `:40` `(def ^:private registry* (atom (simple-registry {})))` — the entire
  global mutable surface of Malli's registry system.
- `:42-46` `set-default-registry!` refuses when `mode` is `"strict"`, throwing
  `"can't set default registry, invalid mode"`.
- `:5-9` `mode` and `type` are read from a goog-define / JVM system property at
  load: `malli.registry/mode`, `malli.registry/type`.
- `:48-52` `custom-default-registry` is a `reify` that *dereferences* the atom
  on every lookup — one indirection so the default var can stay a stable
  value while its contents change.
- `reference-code/malli/src/malli/core.cljc:3039-3046` assembles
  `default-registry`. Under `mode=strict` it never calls
  `set-default-registry!` at all and returns the composite directly — the
  mutable slot is *statically removed from the program*, not merely unused.

The README states the position in the authors' own words
(`reference-code/malli/README.md:3171`): "`mr/set-default-registry!` is an
imperative api with global side-effects. **Easy, but not simple.** If you want
to disable the api, you can define the following compiler/jvm bootstrap" —
`-Dmalli.registry/mode=strict`. That is Hickey's easy/simple distinction
applied to the library's own global, with a supported switch to delete it.

### Every core function threads `{:registry ...}` through options

The single resolution point:

```
core.cljc:309-311
(defn -registry {:arglists '([] [{:keys [registry]}])}
  ([] default-registry)
  ([opts] (or (when opts (mr/registry (opts :registry))) default-registry)))
```

Note the shape: an explicit `:registry` **replaces** the default outright; it
does not merge. Composition is the caller's job via `mr/composite-registry`
(`README.md:3100` shows `(m/validate [:or :pos-int :neg-int] 123 {:registry
registry})` failing on `pos-int?` precisely because the custom registry did not
include the predicate schemas). `-registry` is called from exactly one lookup
site, `-lookup` at `core.cljc:320-327`, which every schema resolution funnels
through (`-lookup!` `:329-333`, `schema` `:2551-2573`, `into-schema`
`:2500-2509`).

Options travel down structurally, never dynamically:

- `core.cljc:2560` — `schema` is `([?schema] [?schema options])`; the 1-arity
  is literally `(schema ?schema nil)`.
- `:2627-2694` — `validator`, `validate`, `explainer`, `explain`, `parser`,
  `parse`, `unparser`, `unparse` are all `([?schema] [?schema options])` /
  `([?schema value] [?schema value options])` and every one passes `options`
  into `(schema ?schema options)`. There is no `binding` anywhere in the path.
- `:335-339` `-properties-and-options` — a schema's own `:registry` property is
  composed *onto* the incoming options registry
  (`mr/composite-registry r (or % (-registry options))`), then the composed
  options flow to children. Local registries (`README.md:3116-3122`,
  `[:map {:registry {::age ...}} ...]`) are therefore lexically scoped by
  structure, and they survive EDN round-trips (`README.md:3128-3135`).
- `:2506-2508` — the same composition in `into-schema`; `:2856-2858` — again
  for `from-ast`.

**Verified: Malli works fully with explicit registries.** The only thing you
lose without the default is the zero-argument convenience arity.

### Compiled validators: derived state lives on the schema instance

This is the direct answer to Seon's defect II (a process-global compiled-
validator slot).

- `core.cljc:59-60` — `(defprotocol Cached (-cache [this]))`, and `:105`
  `-cached?` is an `instance?` check.
- `:345` `(defn -create-cache [_options] (atom {}))`.
- `:353-361` `-cached` — look up key in the schema's own cache atom, else
  compute and `swap!` it in.
- `:772-808` is the canonical construction: `-into-schema` creates
  `cache (-create-cache options)` at `:776` and the returned `reify` closes
  over **both** `options` (returned by `-options`, `:796`) and `cache`
  (returned by `-cache`, `:801`). Every schema type repeats this pattern —
  `:836/:908`, `:935/:988`, `:1007/:1033`, `:1058/:1098`, `:1121/:1142`,
  `:1171/:1193`, `:1227/:1345`, `:1374/:1441`, `:1489/:1573`, `:1598/:1649`,
  `:1673/:1696`, `:2222-2223`.
- `:2627-2633` — `(defn validator ... (-cached (schema ?schema options)
  :validator -validator))`. Its docstring says the result is cached for
  `Cached` schemas under the key `:validator`.

So the compiled validator is memoized **per schema object**, and that schema
object closed over the exact registry it was built against. Cache identity is
*instance* identity, not structural identity — two structurally equal schemas
built from different option maps are different objects with different caches,
which is precisely what makes the memo sound. A schema built with registry A
can never serve a validator compiled against registry B, because the cache is
unreachable from B. There is no global validator table to invalidate, and
`-cached` degrades to plain computation for non-`Cached` schemas (`:361`).

The returned validator is a plain closure — `-validator` at `:783-785` returns
`pred` or a composed `(fn [x] (and (pred x) (pvalidator x)))`. Nothing looks
anything up at call time.

## 2. Function schemas and instrumentation

### `m/=>` writes into one global atom — but every reader takes it as a parameter

- `core.cljc:3052` — `(defonce ^:private -function-schemas* (atom {}))`.
  Keyed `[platform-key ns name]` (`:3086`).
- `:3053` — `function-schemas` reads it; the 0-arity defaults to `:clj`.
- `:3082-3088` `-register-function-schema!` — one `swap! assoc-in`, wrapped in
  try/catch that converts a failure into `::register-function-schema`.
- `:3090-3108` — the `=>` macro. It resolves `*ns*` at macroexpansion and, for
  CLJS, registers under `:cljs` in the *Clojure* compiler process so the
  schemas are visible at macroexpansion (`:3102-3107`).
- `:3055-3072` — explicit deregistration, including a selective
  `-deregister-metadata-function-schemas!` that keeps `=>`-declared schemas and
  drops metadata-derived ones. Reload hygiene is a first-class operation.

The mitigating design is at the consumer:

```
instrument.clj:18-20
(defn -strument!
  ([] (-strument! nil))
  ([{:keys [mode data filters gen report]
     :or {mode :instrument, data (m/function-schemas)} ...}]
```

`data` is an ordinary option whose *default* is the global atom. Every caller
may supply its own declaration set and the global is never consulted.
`instrument!` / `unstrument!` (`:152-162`) and `check` (`:125-134`) are all
thin wrappers over `-strument!` with a `:mode`.

### The instrumentation seam itself is per-schema and closure-based

- `core.cljc:3110-3131` — `-instrument` takes a props map
  (`:schema :scope :report :gen`), defaults `:scope` to
  `#{:input :output :guard}` and `:report` to `-fail!` (`:3127-3128`),
  resolves `(-> props :schema (schema options))` at `:3129`, and dispatches
  through the `FunctionSchema` protocol at `:3130`.
- `:89-94` — `defprotocol FunctionSchema` with `-instrument-f [schema props f
  options]`; `:110-114` extends `Object`/`default` to return `false`/`nil`, so
  "not a function schema" is a value, not an exception.
- `:2203-2221` is the actual wrapper. It pre-compiles `validate-input`,
  `validate-output`, `validate-guard` and the `wrap-*` booleans **outside** the
  returned `(fn [& args] ...)`, then the wrapper closes over them. Zero
  registry lookup, zero atom read, zero dynamic var at call time.
- `instrument.clj:34-38` — installation is `alter-var-root` with the original
  stashed as `::original` metadata on the wrapper; `:8-9` `-f->original`
  recovers it, and `:39` `:unstrument` is `(alter-var-root v -f->original)`.
  Instrumentation is therefore idempotent and exactly reversible.

**As a model for Seon:** yes, but with the polarity flipped. The valuable part
is *declaration is data, wrapping is a pure function of that data, and the
declaration source is a parameter*. The part to not copy is the ambient
`defonce` atom as the discovery mechanism — Seon already has the strictly
better version, because its declarations are database facts queried at a basis
rather than accumulated by load-order side effects.

## 3. Datahike: connection identity, explicit db/conn, and the writer

### Connection identity is a derived value, not a name

```
store.cljc:44-55
(defn connection-id [config]
  (let [base [(store-identity (:store config)) (:branch config)]
        writer-backend (get-in config [:writer :backend] :self)]
    (cond-> base (not= :self writer-backend) (conj writer-backend))))
```

`[store-id branch]` for self-writers, with the writer backend appended for
remote backends so a server and a synced client for the same logical store can
coexist in one process (`:47-50`). `store-identity` (`:35-42`) is just
`(:id config)`. `physical-store-key` (`:57-69`) is a separate, deliberately
*wider* key — the whole pure store config, postwalked to strip per-call runtime
opts — used only to decide which internal resources (write hooks) may be
shared, with an explicit note that runtime objects compare by identity and so
yield a "safe false negative rather than unsafe resource sharing."

### The process-local connection registry is refcounting, not resolution

`connections.cljc:3` — `(def ^:dynamic *connections* (atom {}))`. This looks
like the disease. Read what it actually holds
(`:66-73`): `{:opening? :completion :generation :waiters :acquisition-key
:physical-store-key :write-hooks}` — a reservation/refcount protocol, not a
lookup table for the read path.

- `:37-92` `reserve-connection-opening!` uses one `swap-vals!` and returns a
  *state value* (`:owner` / `:opening` / `:existing` / `:releasing` /
  `:config-mismatch`), the caller then acts on it. Concurrency resolved by CAS,
  reported as data.
- `:11-35` `release-connection-reference!` symmetrically returns
  `:absent` / `:in-progress` / `:last` / `:retained`.
- `:81-84` — an acquisition-key mismatch is `{:state :config-mismatch
  :existing-key ...}`, i.e. *reopening the same store with different config is
  refused as a value*, not silently coerced.

Crucially, **no query or transaction ever resolves a connection out of this
map.** `active-connection` (`:5-9`) exists for lifecycle inspection.
Every API function receives the conn or db.

### The API surface is data; every op declares its own db/conn argument

`api/specification.cljc` is the authority — the docstring at `:3-22` says all
bindings (Clojure, Java, JS/TS, HTTP, CLI) are derived from it, each op
carrying `:args` (a **Malli function schema**), `:ret`, `:doc`, `:impl`,
`:categories`, `:stability`, `:referentially-transparent?`.

- `:437-459` — `q`'s two arities are `[:cat :datahike/SQueryArgs]` and
  `[:cat [:or [:vector :any] :map :string] [:* :any]]`; the examples at
  `:450-458` all pass `db` positionally or as `:args [db]`.
- `:315-316` `db` is `[:=> [:cat :datahike/SConnection] :datahike/SDB]`;
  `:359` `transact` is `[:cat :datahike/SConnection :datahike/STransactions]`;
  `:583-584` `pull`, `:661` `entity`, `:694` `datoms` all take
  `:datahike/SDB` as the first argument.
- `api.cljc:60-89` — `emit-api` reduces the specification into `def`s aliased
  straight to `:impl`, with `:arglists` derived from the Malli schema
  (`specification.cljc:50-75` `malli-schema->argslist`). The public namespace
  is *generated from the data*; there is no hand-maintained roster.
- `specification.cljc:88-108` `capability-catalog` projects the same data into
  a transport-free namespaced operation catalog. One declaration, many
  projections — the same move Seon makes with `:seon.fn` facts.

**There is no ambient db or conn.** The 1-arity forms above take a query map
whose `:args` contains the db; nothing falls back to a "current database."

### The writer is a field on the connection value

- `connector.cljc:38-70` — `(deftype Connection [wrapped-atom])` implementing
  `IDeref`/`IAtom`/`IMeta` by delegation; `:44` exposes exactly one key,
  `:wrapped-atom`.
- `connector.cljc:372-373` — `(swap! (:wrapped-atom conn) assoc :writer
  (w/create-writer (:writer config) conn))`. The writer is installed **into the
  connection's own value**, and is constructed *with* that connection.
- `writer.cljc:393-396` — `(defn transact! [connection arg-map] ... writer
  (:writer @(:wrapped-atom connection)))`. Same at `:421` (`load-entities`),
  `:435` (`merge-db!`), `:448` (`gc-storage!`). Every write reaches its writer
  by walking the connection value. No registry lookup, no global.
- `writer.cljc:116-123` — `create-thread` allocates this connection's own
  `transaction-queue`, `commit-queue`, `inflight-ops` atom, processing thread
  and commit thread. `:42-45` `defrecord LocalWriter` holds them; `:46-53`
  `-dispatch!` is a `put!` onto *that* connection's queue with a
  `promise-chan` callback — including an explicit note that a `false` accept
  callback means the queue closed, so the caller is always resolved.
- `writer.cljc:17-20` — `defprotocol PWriter` (`-dispatch!`, `-shutdown`,
  `-streaming?`) and `:312-314` `(defmulti create-writer (fn [writer-config _]
  (:backend writer-config)))`. The protocol is the *pluggability* seam
  (self vs. kabel/remote); the *instance* still lives on the connection.
- `writer.cljc:146` — `op-fn (write-fn-map op)` where the map is
  `(merge default-write-fn-map write-fn-map)` (`:322-324`, table at
  `:305-310`). Even the set of writable operations is an injectable map with a
  default, not a hardcoded case.

### Every dynamic var in Datahike, and why

`grep '\^:dynamic' src/datahike/` returns 20 hits. Classified:

- **Configuration defaults consulted at construction**:
  `config.cljc:15-28` (`*default-index*`, `*default-schema-flexibility*`,
  `*default-store*`, `*default-db-branch*`, cache sizes). These are read when
  building a config map, and the resulting config is then an explicit value.
- **Performance/diagnostic toggles**: `query.cljc:63` `*disable-planner*`
  ("Set DATAHIKE_QUERY_PLANNER=false"), `:72` `*query-result-cache?*` ("Bind to
  false for benchmarking raw query execution"), `:2445`/`:2455` cache sizing,
  `:3473` `*profile?*`, `execute.cljc:3228` `*fixpoint-shortcuts?*`,
  `single_flight.cljc:3`, `migrate.clj:30`. None changes a query's answer.
- **Test seams**: `tools.cljc:56-60` `get-date` / `get-time-ms` are
  `^:dynamic` *functions* — the clock is redefinable, matching Seon's
  "an unjustified clock is a defect" instinct by making the justified one
  substitutable.
- **A genuine reader escape hatch**: `remote.cljc:27` `*remote-peer*`, with the
  comment "used to allow the tagged literal readers to attach the remote
  again." Tagged-literal readers take no parameters — the API has no slot, so
  the dynamic var is the only mechanism. This is the honest shape of the
  exception: it exists where a *foreign* extension point provides no argument.
- **The one semantic dynamic, and it is fed from an explicit option**:
  `resource.cljc:3-9` `*budget*` / `*evidence-sink*`, docstring "The resource
  budget active for one synchronous database operation." Consulted at
  `:68,74,81,92`. But look at the entry: `pull_api.cljc:405`
  `(binding [resource/*budget* (or budget resource/*budget*)] ...)` — the
  budget arrives as an **explicit argument** and the dynamic binding is purely
  internal propagation through a deep recursive walk that would otherwise need
  the parameter on every frame. `query.cljc:4531` does the same.

**Nothing in the read or write path resolves a connection, a db, a store, or a
writer from ambient state.** The dynamic vars are defaults-at-construction,
benchmark toggles, a test clock, one reader hack, and one internally-propagated
budget that enters as a parameter.

## 4. Derived state travels with the value it derives from

The strongest examples, ranked:

1. **Malli's per-schema cache** (`core.cljc:776` + `:801`, used by `:353-361`
   and `:2633`). The compiled validator/explainer/parser is memoized on the
   very object that determined it. Two consequences worth copying verbatim:
   the cache cannot outlive or mismatch its source, and *there is nothing to
   invalidate* — dropping the schema drops the cache.
2. **Datahike's DB record carries its own indexes.**
   `db.cljc:307` — `(defrecord-updatable DB [schema eavt aevt avet
   temporal-eavt temporal-aevt temporal-avet max-eid max-tx op-count rschema
   hash config system-entities ident-ref-map ref-ident-map secondary-indices
   meta cache-context])`. `rschema`, `ident-ref-map`, `ref-ident-map`, `hash`
   and `cache-context` are all *derived* from `schema` and the datoms — and
   they are fields on the immutable value, so a db value is self-sufficient.
   The persistent-set indexes structurally share with the previous db value;
   derivation is incremental *because* it is attached, not despite it.
3. **The writer on the connection** (`connector.cljc:372-373`,
   `writer.cljc:396`). Per-connection serial machinery reached by walking the
   value.
4. **Malli's instrumented function closes over its compiled validators**
   (`core.cljc:2204-2221`) and stashes the original on its own metadata
   (`instrument.clj:38`) — even the *undo* information travels with the
   wrapper.
5. **`fast-registry`'s HashMap** (`registry.cljc:17-22`) — a mutable
   performance container that is created once, never mutated after
   construction, and never exposed (`-schemas` returns the original `m`).
   Mutability confined to construction is not ambient state.

The counterexamples, and what makes them tolerable:

- **Malli's `-function-schemas*`** (`core.cljc:3052`) — a real global,
  mitigated only by every consumer accepting it as a parameter
  (`instrument.clj:20`).
- **Datahike's query result cache** (`query.cljc:2413`, `:2489`) — a global
  LRU, but read the key: "keyed by exact committed identity `[connection-id
  generation commit-id]`" (`:2433-2434`), with generations opened and closed
  around connection lifetime (`connector.cljc:369`, `:399`). Because the key
  includes the full identity of the value, the cache is pure memoization: it
  can only ever return what recomputation would have returned. A global cache
  keyed by complete identity is a *different thing* from a global slot holding
  "the current X". The `:cache-context` field on the DB record
  (`db.cljc:307`, consumed at `query.cljc:3094,3102`) is the part that had to
  travel with the value.

## 5. Verdict for Seon

### (a) Explicit environment parameters with a convenience default — where's the line

Both libraries draw the same line, from opposite directions:

> **The default may only be a value the explicit parameter would also have
> accepted, resolved at exactly one site, and the whole system must remain
> correct with the default statically removed.**

Malli passes this test literally: `-registry` (`core.cljc:309-311`) is the one
site, an explicit `:registry` fully replaces the default, and
`-Dmalli.registry/mode=strict` (`registry.cljc:42-46`, `core.cljc:3040-3046`)
deletes the mutable slot from the program. Datahike passes it by never having
the default at all for db/conn — the config defaults it *does* have
(`config.cljc:15-28`) are consumed when *building an explicit config value*,
which is the safe position for a default.

Three concrete rules that follow, all with source backing:

1. **One resolution site.** `core.cljc:311` and `:320-327` — every schema
   lookup in Malli funnels through `-lookup`. If Seon's environment is
   resolved in more than one place, the default has become a second mechanism.
2. **Convenience is an arity, never a fallback inside the logic.**
   `core.cljc:2558-2559`, `:2630-2631` — the 1-arity is `(f x nil)`, one line,
   and the real body only ever sees `options`. Contrast the shape to avoid:
   a function body that does `(or passed-in @the-global)` mid-logic.
3. **Ambient state is admissible only where a foreign extension point provides
   no parameter.** `remote.cljc:27` (tagged-literal readers) is the one honest
   instance in Datahike. `resource.cljc:3` + `pull_api.cljc:405` is the second
   admissible shape: enter by parameter, `binding` only for internal
   propagation through a call tree you own end to end, never as the entry
   contract.

Applied to Seon's defect II: the fix is not "make the global validator slot
thread-safe" but "there is no slot." Compile the validator against the acquired
registry and let it live on the acquired thing — which is exactly what
`seon.schema`'s generation-keyed design (`src/seon/schema.clj:679-692`,
`seon-registry` reading `(active-forms)` behind a stable facade, with candidate
validation "passes `candidate-registry` explicitly") is already reaching for.
The Malli lesson says finish the move: the stable facade is the *bootstrap*
convenience, and every runtime path should carry the registry it acquired.

### (b) Attaching derived state to source values

**Attach derived state to the value it was derived from, and identity problems
disappear.** Malli's `Cached` protocol (`core.cljc:59-60`, `:345`, `:353-361`)
is 20 lines and eliminates an entire class of cache-invalidation bug, because
the cache is *unreachable* from any value that would need a different answer.
Datahike's DB record (`db.cljc:307`) does the same at scale.

For Seon: a compiled predicate, a rendered block, an execution plan, a
workload classification — each belongs on (or in a memo keyed by the complete
identity of) the value it derives from. When a global cache is genuinely
warranted for cross-value reuse, key it by *complete* identity the way
Datahike keys by `[connection-id generation commit-id]` (`query.cljc:2433`),
so it degenerates to memoization and can never answer a question the source
value would answer differently.

### (c) What a "guaranteed interface" looks like — map or protocol?

**Data by default; protocols only for open type polymorphism at a real
extension boundary.** The evidence is unambiguous about which is which.

Where these libraries use protocols, and for what:

- `registry.cljc:11-13` `Registry` — three shipped implementations plus
  composites and user-defined ones. Genuine open extension. And note
  `:30-34`: a plain map is coerced into it, so the protocol never leaks into
  ordinary use.
- `core.cljc:23-103` — `IntoSchema`, `Schema`, `AST`, `EntryParser`,
  `EntrySchema`, `Cached`, `LensSchema`, `RefSchema`, `Walker`, `Transformer`,
  `RegexSchema`, `FunctionSchema`, `DistributiveSchema`, `ParserInfo`. Every
  one dispatches across 100+ schema *types* — textbook type polymorphism. The
  optional ones (`Cached`, `FunctionSchema`) are probed with `-cached?`
  (`:105`) or extended on `Object` to return false (`:110-114`), so absence is
  a value.
- `writer.cljc:17-20` `PWriter` + `:312` `(defmulti create-writer ... :backend)`
  — self vs. remote backends. Again: a real second implementation exists.
- `connector.cljc:38-70` `Connection` deftype — implementing *Clojure's own*
  `IDeref`/`IAtom`/`IMeta` so a connection behaves like the host abstraction it
  is.

Where they use plain data for the thing that is *configuration*:

- `{:registry ...}` in an options map, everywhere (`core.cljc:311`).
- `{:schema :scope :report :gen}` as `-instrument`'s props (`:3121-3128`).
- `write-fn-map` as an injectable symbol→function map (`writer.cljc:305-324`).
- The entire Datahike API as a specification map with Malli `:args` schemas
  (`api/specification.cljc:12-22`), from which the Clojure namespace
  (`api.cljc:60-89`), the capability catalog (`specification.cljc:88-108`),
  and every other binding are *derived*.

That last one is the model for Seon. A guaranteed interface in data-oriented
Clojure is **a declaration set plus a schema over it**, with implementations
reached by ordinary function values in a map. Datahike proves it scales to a
full public API across five language bindings.

**Recommendation: Seon's environment should be a map, not a protocol.** It is
configuration and capability *values*, consumed by first-party code that owns
both sides — there is no second implementation to dispatch on, which is the
only thing a protocol buys. Declare its shape as one globally identified Malli
schema (accretion applies: declared keys validated rigorously, extra keys
ignored — `AGENTS.md` ruling #48, and Malli's maps are open by default,
`README.md:294`). If a *single* capability inside it later needs genuine
multi-implementation dispatch — a store backend, a model provider — give that
capability a protocol the way Datahike gives one to the writer
(`writer.cljc:17-20`), while the environment carrying it stays a map.

Two further shapes worth stealing outright:

- **Return states as values, not exceptions or booleans.**
  `connections.cljc:76-92` returns `{:state :owner|:opening|:existing|
  :releasing|:config-mismatch ...}` from a `swap-vals!`. Concurrency resolved
  by CAS, outcome reported as data for the caller to act on. This is exactly
  Seon's errors-as-values discipline applied to lifecycle.
- **Reversible installation.** `instrument.clj:34-39` — wrap via
  `alter-var-root`, stash the original in the wrapper's metadata, unwrap by
  reading it back. Idempotent and exactly undoable, with no side table.

## Method note

Read end to end: `malli/src/malli/registry.cljc` (all 105 lines),
`malli/src/malli/instrument.clj` (all 163 lines),
`datahike/src/datahike/connections.cljc` (all 128 lines). Read in relevant
part: `malli/src/malli/core.cljc` (registry/cache/schema/validator/function-
schema/instrument regions and the protocol block),
`datahike/src/datahike/{writer,connector,store,query,api,resource,remote,
config}.cljc` and `api/specification.cljc`. The `^:dynamic` classification in
§3 is exhaustive over `grep -rn '\^:dynamic' datahike/src/datahike/`.
No files were modified other than this report.
