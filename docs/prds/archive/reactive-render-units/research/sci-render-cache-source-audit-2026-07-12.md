---
type: research
status: active
tags: [research, web, agent]
---

# SCI render cache source audit

## TL;DR

Do not memoize `seon.render.sci/invoke-bounded` yet. A safe result hit must
prove that the renderer's code environment and every database result it
observed are unchanged. The current source has neither a transitive database
read observer nor a safe reusable SCI context:

- compiled helper functions exposed to SCI can call `seon.db` outside any
  SCI-local function wrapper;
- stored `:seon.fn/read-attrs` is an attribute-literal approximation, not the
  normalized results of reads that actually executed; and
- `sci/fork` shallow-copies the environment atom, so reusing a prepared context
  does not isolate mutable host values, SCI vars, or atoms.

The official JVM `core.memoize`/`core.cache` libraries are not ClojureScript
libraries. Seon's CLJS classpath already contains the established
`com.github.pkpkpk/cljs-cache` port through Datahike. Its `cljs.cache` LRU
supports bounded structural Clojure keys and explicit lookup/hit/miss/evict;
it is the right cache implementation after correctness keys exist. It must be
used directly, not by ignoring a database argument.

The ordered prerequisite is one synchronous `seon.db` read-capture scope. It
must observe transitive helper calls, emit database-handle-free immutable
request/result facts, mark lazy/temporal/opaque reads non-replayable, compose
under nesting, and add only one AsyncLocalStorage lookup to an unobserved read.
Only then can a unit-scoped bounded render cache safely replay captured reads
against a new immutable database value and skip SCI when every result is equal.

## Question and constraints

The requested optimization is stronger than a conventional source memo:

> Unchanged renderer code plus unchanged normalized database read results
> should return the previous realized view without entering SCI.

The cache must remain runtime-only, bounded, cold after restart, scoped to one
semantic render unit, and free of database objects or whole-basis values in its
keys. It must not persist generated HTML. A source change, require/environment
change, schema/instrumentation change, non-database input change, or changed
read result must miss exactly the affected unit.

## Current renderer execution path

`seon.render.sci/invoke-bounded` currently performs this work on every agent
renderer invocation (`src/seon/render/sci.cljs:472-610`):

1. Query the renderer's stored `:seon.fn/source`.
2. Read stored require edges, with an old-store source parser fallback.
3. Enumerate compiled functions and data from the required and owning
   namespaces, plus indexed function rows.
4. Create a fresh SCI context with those host values.
5. Evaluate the renderer definition and invocation string.
6. Deep-realize the result under the deadline.

The fresh context is part of the safety contract. SCI documents `fork` only as
isolating newly created vars. Its implementation is exactly
`(update ctx :env (fn [env] (atom @env)))`
(`reference-code/sci/src/sci/core.cljc:276-281`). The environment map is copied,
but values reachable from it are not. Reusing or forking a context containing a
mutable host value, SCI atom, or already-defined renderer can therefore leak
state between agents or units. Keep `sci/init` fresh on every cache miss.

SCI initialization installs supplied namespace values into its mutable
environment (`reference-code/sci/src/sci/impl/opts.cljc:167-202`). That gives us
a safe immutable *plan* boundary, but not a reusable execution context.

## Why the existing read approximation cannot key a result cache

The analyzer stores qualified keyword literals as `:seon.fn/read-attrs`.
`seon.agent.ctx.render-fns/renderer-read-attrs` exposes that set and retains a
source-regex fallback for old rows
(`src/seon/agent/ctx/render_fns.cljs:170-259`). It is useful as a conservative
candidate gate today, but it cannot prove result equality:

- it is not transitive through helper calls;
- it includes output/write/options/constants that are not reads;
- it misses dynamic attributes, rules, wildcards, and reads in branches that
  call compiled helpers;
- it does not include entity or query input scope; and
- the same attribute can change on an unrelated entity while the result stays
  equal.

The critical bypass is `expose-ns`. It supplies compiled host functions from
`js/globalThis` directly to SCI (`src/seon/render/sci.cljs:371-441`). A renderer
can call one of those helpers, and that helper can call `seon.db/query`, `pull`,
or `entity` without crossing a wrapper installed only in SCI's namespace map.
Consequently, an SCI-local recorder would miss a real dependency and permit a
stale cache hit.

This matches the existing reliability design: actual synchronous reads at the
single `seon.db` boundary are the dependency truth; stored literal read sets
are deleted only after runtime capture and recency behavior are proven.

## Library audit

### `core.memoize` and `core.cache`: incompatible with the active pod

The CLJS dependency graph currently contains `org.clojure/core.memoize`
1.1.266 and `org.clojure/core.cache` 1.1.234 transitively through the JVM
analyzer stack. Both artifacts implement Clojure/JVM namespaces in `.clj`
files. Their latest upstream releases on 2026-02-25 are
`core.memoize` 1.2.281 and `core.cache` 1.2.263; the current upstream sources
remain `.clj`-only.

Mechanical proof against Seon's exact `:cljs` classpath:

```text
$ CP="$(clojure -Spath -M:cljs)"
$ java -cp "$CP" clojure.main -m cljs.main -co '{:target :nodejs}' \
    -e "(require '[clojure.core.memoize :as memo])"
Unexpected error (ExceptionInfo) compiling at (<cljs repl>:1:1).
No such namespace: clojure.core.memoize, could not locate
clojure/core/memoize.cljs, clojure/core/memoize.cljc, or JavaScript source
providing "clojure.core.memoize"
```

`core.memoize` also advertises `:clojure.core.memoize/args-fn` for ignoring a
mutable JDBC connection in a cache key. That is specifically unsafe for this
renderer. Ignoring `:seon.db/db` without replacing it with captured normalized
read results turns every later database state into the same key.

`cljs.core/memoize` does compile, but its implementation is an atom containing
an unbounded argument-to-result map with no eviction or inspection surface
(`reference-code/clojurescript/src/main/cljs/cljs/core.cljs:11196-11209`). It
does not satisfy the bounded-cache requirement.

### Existing `cljs.cache`: compatible and preferred after the observer lands

Datahike already brings `com.github.pkpkpk/cljs-cache` 1.0.21 onto the active
CLJS classpath and uses it behind the same reader conditionals as JVM
`core.cache` (`reference-code/datahike/src/datahike/store.cljc:12-16`,
`reference-code/datahike/src/datahike/lru.cljc:1-4`). A direct require succeeds:

```text
$ java -cp "$CP" clojure.main -m cljs.main -co '{:target :nodejs}' \
    -e "(require '[cljs.cache :as cache])"
# exit 0
```

The package's `LRUCache` stores structural Clojure values as keys, updates a
priority-map usage clock on hit/miss, evicts the least-recently-used key at its
threshold, and supports explicit `lookup`, `has?`, `hit`, `miss`, `evict`, and
`seed`. `cljs.cache.wrapped` provides the atom-owned version and
`lookup-or-miss`. A two-entry mechanical probe using map keys retained the
recently hit entry and evicted the other:

```clojure
{:count 2, :one :a, :two :missing, :three :c,
 :seq ([{:unit/id 1} :a] [{:unit/id 3} :c])}
```

One package edge matters for diagnostics: `seq` and `count` work, while an
`into` probe selected the package's `IIterable` implementation and failed
because its underlying persistent map has no JavaScript `.iterator` method.
Use the cache protocol plus `seq`/`count`, not generic `into`, for runtime
inspection. If Seon imports `cljs.cache` directly, add an explicit `:cljs`
dependency rather than relying on Datahike's transitive declaration.

The package solves bounded storage. It does not solve the renderer's
correctness key, result normalization, source/environment revision, or SCI
isolation.

## Safe read-capture prerequisite

The API belongs in the existing `seon.db` namespace. It is one mechanism, not a
parallel renderer namespace or a persistent dependency graph.

A capture scope receives the immutable database value the unit is rendering
and a synchronous thunk. Each canonical read emits an ephemeral fact containing
only:

```clojure
{:seon.db/read-operation  :seon.db.read.operation/query
 :seon.db/read-source     :seon.db.read.source/captured
 :seon.db/read-request    {:seon.db/query <form>
                           :seon.db/args  <normalized non-db inputs>}
 :seon.db/read-result     <normalized immutable result>
 :seon.db/read-replayable? true}
```

The actual database handle is never in an observation or future cache key.
Reads against another/temporal database, lazy entities, functions, or unknown
host objects use immutable sentinels and set `:seon.db/read-replayable? false`.
That is a conservative miss, not a guessed projection.

Use a dedicated Node AsyncLocalStorage scope, following the existing
`seon.db.internal` transaction/agent scopes. This is required even though
rendering is synchronous today: it makes nested captures explicit and prevents
future async fibers from sharing a process-global collector. The capture stack
semantics are compositional:

- a scope records reads made by transitive compiled helpers;
- an inner scope records only reads in its own extent;
- the outer scope also records reads performed inside the inner scope; and
- both scopes normalize database source relative to their own captured value.

When no scope is active, a read performs one `AsyncLocalStorage.getStore()`
check and allocates no request/event maps. Normalization and capture buckets
exist only on the observed path. Capture rejects a Promise-returning thunk: the
surface is deliberately synchronous, so returning before its reads finish
would make the observation incomplete.

Primitive boundary coverage begins with `query`, `installed-schema`, `pull`,
`entity`, `entity-lazy`, `history`, `as-of`, `since`, and `basis-t`. Derived
read helpers already call these boundaries. `entity-lazy` and temporal DB-value
operations are captured but non-replayable until callers migrate to explicit
bounded reads. Direct `datahike.api` reads in render code remain a known bypass
and must be migrated before exact invalidation can claim complete coverage.

## Ordered cache implementation after capture proof

1. Capture synchronous read observations for one stable semantic unit.
2. Migrate direct Datahike and lazy render reads behind explicit `seon.db`
   operations; broad/non-replayable observations always miss meanwhile.
3. Build an immutable code-plan key from exact renderer source, normalized
   require facts, exposed host member identities, and schema/instrumentation
   revision. Do not cache or fork SCI contexts.
4. Build a stable non-database input key. Exclude callbacks and the database
   handle only because captured read results replace the latter; include
   `:seon.render/at` when code actually observes it.
5. Use a bounded `cljs.cache` LRU per runtime/unit owner. Cache only successful,
   deep-realized renderer values and their normalized observations. Never cache
   interrupts or errors.
6. On a candidate hit, replay each distinct replayable read once against the
   new immutable database value. Equal results return the cached value without
   SCI. Any unequal/non-replayable read misses and runs a fresh SCI context.
7. Prove one-unit invalidation, source/require/helper/schema revision misses,
   no SCI mutable-state leakage, bounded eviction, and a cold empty cache after
   process restart.

## Upstream status relevant to this work

Datahike upstream `main` remains `990c6539ee885000f52ca9b01dceaad119822696`
(0.8.1729). Seon's fork at `67934f650fae30924ac115c899cd3412d90dcacb`
is zero commits behind and 26 commits ahead. Upstream does not contain Seon's
effective-TxReport correction, so there is no upstream replacement to integrate
for that patch today.

The cache-library result is different: do not add latest JVM
`core.memoize`/`core.cache`, but do reuse the already-compatible `cljs.cache`
package once the observer makes a correct cache key possible.
