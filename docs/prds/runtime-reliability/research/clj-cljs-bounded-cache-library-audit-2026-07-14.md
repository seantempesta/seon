---
type: research
status: completed
tags: [research, web]
---

# Bounded cache library audit for reactive render units

## TL;DR

Do not build Seon's reactive correctness on `memoize`, `core.memoize`, or any
cache library. The generic render-unit engine should retain exactly one current
derivation per active normalized unit: stable plain inputs, renderer digest,
captured read requests/results, and the last serialized element. That state is
naturally bounded by active subscriptions, is released with the final consumer,
and is sufficient to skip both unchanged renders and identical patches.

Add an across-subscription LRU only after profiling proves that reopening units
reuses enough work to justify it. If that gate passes, use
[`lru-cache` 11.5.2](https://github.com/isaacs/node-lru-cache/tree/v11.5.2)
behind the single Seon render-unit engine. It is the best fit for the only
process that currently renders: the Node ClojureScript pod. It enforces entry
count and estimated-output-token bounds together, supports a per-entry cap,
updates recency on access, exposes eviction/invalidation/status hooks, has no
runtime package dependencies, and passed the disposable CLJS spike.

Do not add Caffeine to the JVM database server now. Caffeine is the strongest
JVM cache evaluated, but that process does not render and Caffeine cannot apply
entry-count and weight limits simultaneously. Adding it would create a second,
unused cache backend. If JVM rendering becomes real later, revisit Caffeine
behind the same Seon-owned operation schema rather than changing renderers.

This recommendation deliberately gives agents no cache API. Core and
agent-authored renderers remain ordinary pure `view = f(data)` functions. One
engine owns observation, candidate selection, replay, rendering, serialization,
active-unit reuse, optional recent reuse, metrics, and fail-open behavior.

## Question and constraints

The requested cache must support Seon's automatic, high-level reactive engine,
not require agents to write memoized functions. The relevant constraints are:

- one mechanism for root, agent, canvas, context, debug, and `/data` units;
- runtime-observed database reads remain the live-correctness authority;
- no Datahike database value, entity view, connection, or other snapshot-like
  object may appear in a cache key or retained value;
- stable equal plain-data inputs must hit in CLJS even when newly allocated;
- active state must be bounded by active consumers;
- optional recent reuse must be bounded by both entry count and estimated
  serialized-output tokens, with a maximum per-entry output;
- source changes, input changes, or changed normalized read results must miss;
- eviction or cache failure may affect latency only, never output or updates;
- cache behavior and invalidation must be measurable without adding persisted
  derived state to the database.

## What Seon does today

The current system already contains most of the right primitives, but ownership
is split:

- `src/seon/web/datastar.cljs:227-251` renders under `db/capture-reads`, then
  replays each captured observation and invokes the renderer only when a result
  changes.
- `src/seon/db.cljs:510-534` captures normalized immutable requests/results.
  `src/seon/db.cljs:1565-1602` replays them. This is deliberately not a cache
  and retains no database handle.
- `src/seon/web/datastar.cljs:405-434` shares one full rendered event across
  equivalent live consumers and releases it with the final consumer.
- `src/seon/web/datastar.cljs:532-601` suppresses an identical last event.
- `src/seon/ui/agent_view.cljs:354-432` owns a second page-specific transition.
  Its declared-attribute gate at lines 363-372 can reject a real nested runtime
  dependency before exact replay. A cache cannot repair that correctness bug.
- `src/seon/web/view_unit.cljs:1-56` already owns canonical render-unit
  coordinates and opaque stable tokens. The eventual generic engine belongs
  with this existing concept; the library must remain an implementation detail.

The target is already stated correctly in `docs/seon/architecture/ui.md` under
"The live channel" and in Slice 2 of
`docs/prds/runtime-reliability/roadmap.md`: runtime observations select and
validate dirty units; active units retain one current result; only a measured
recent-reuse layer is an LRU.

## Caching is not invalidation

The general algorithm should be:

1. A database transaction supplies changed datoms to the one render engine.
2. The reverse index over actual captured read descriptors selects candidate
   active units. Broad observations remain candidates.
3. Each unique normalized read is replayed once against the new immutable
   database value.
4. Equal read results reuse the active unit's serialized element without
   invoking its renderer.
5. Unequal results invoke the renderer with small plain data, capture its new
   reads, and serialize its complete stable-ID element.
6. Equal serialization emits no patch. Changed serialization emits one normal
   Datastar element morph.
7. The last consumer closing removes the active state. An optional recent LRU
   may retain only the successful plain-data result for a later reopen.

This makes caching automatic for every renderer, including agent-authored
canvas functions, without trusting agents to know or use a cache library.

## Recommendation

### Required layer: no cache library

Represent the current result as ordinary state on each normalized active render
unit. A unit already needs its coordinate, consumers, observations, and last
event. Adding its renderer digest, stable inputs, normalized read results, and
last serialized element is not a general-purpose cache; it is the minimum state
of the transition machine.

This layer has exact lifecycle bounds, needs no eviction policy, preserves CLJS
structural equality, and cannot stampede because synchronous rendering runs in
one Node event loop. It also eliminates the temptation to memoize functions over
database values.

### Optional layer: `lru-cache` 11.5.2 in the CLJS pod

Adopt only if a profile shows meaningful reopen/cross-subscription hits. Configure
all three limits:

- `max` for entry count;
- `maxSize` with `sizeCalculation` returning the stored element's existing
  `seon.ai.tokens/estimate` value;
- `maxEntrySize` so one pathological render is rejected rather than displacing
  the useful cache.

The library supports `max` and `maxSize` simultaneously. Its source rejects
unbounded configurations, updates recency on `get`, rejects overlarge entries,
and exposes `dispose`, operation status, `delete`, and `clear`. Relevant pinned
source is `src/index.ts:740-749`, `:2130-2177`, and `:2151-2155` at commit
`16b3a916662ab449d496b7b4b4f04132565d1d28`.

The important caveat is key equality: JavaScript `Map` keys use identity for
objects. The spike proved that two equal freshly allocated CLJS vector/map keys
miss in `lru-cache`, while they hit in `cljs.cache`. The Seon wrapper must use a
canonical stable string or digest, never pass a CLJS collection directly. The
existing canonical coordinate token is one part of that key; renderer and
input/read-result digests must use the existing canonical content-digest
mechanism. Retain the small plain key data with the cached entry and compare it
on a digest hit so a collision becomes a miss, never a wrong result.

### Ownership and public shape

Do not expose the library or its mutable object outside the render-unit engine.
The engine should own a small fully namespaced data API such as:

```clojure
{:seon.web.view-unit/cache-key
 {:seon.web.view-unit/coordinate coordinate
  :seon.web.view-unit/renderer-digest renderer-digest
  :seon.web.view-unit/input-digest input-digest
  :seon.web.view-unit/read-results-digest read-results-digest}
 :seon.web.view-unit/serialized-element serialized-element
 :seon.web.view-unit/output-tokens output-tokens}
```

The exact schema should be designed with the generic engine, not added as a
standalone cache namespace. The backing JS key is a canonical scalar derived
from `::cache-key`. No database object participates in either shape.

Runtime counters should be ephemeral projections from the owner, not database
facts: hits, misses, inserts, evictions, overlarge rejections, current entries,
and current estimated output tokens. Existing logging/observability can expose
them with fully namespaced keys.

Cache exceptions, malformed entries, digest mismatch, or unavailable metrics
must fail open as a miss and run the normal renderer. Do not cache unsuccessful
or timed-out recent results; active state may show the current error, but a
cross-subscription cache must not extend a potentially transient failure.

## Candidate comparison

| Candidate | CLJ | CLJS/Node | Strict count + token weight | Equal CLJS data keys | Invalidation/metrics | Decision |
|---|---:|---:|---:|---:|---:|---|
| Active-unit state, no library | yes | yes | lifecycle-bound; no recent LRU | structural | engine-owned | required correctness layer |
| `lru-cache` 11.5.2 | no | yes | yes, simultaneously, plus per-entry cap | no; canonical scalar required | strong | optional measured recent layer |
| `core.cache` 1.1.234 / `cljs-cache` 1.0.21 | yes | yes, parallel port | count only | yes | manual, no weight metrics | reject for recent outputs |
| Taoensso Encore `cache` 3.85.0 | yes | yes | approximate count only | yes | delete/clear, little telemetry | reject |
| Caffeine 3.2.4 | yes | no | count **or** weight, not both | n/a | excellent | strongest JVM option, not currently needed |
| maintained Datahike `WeightedLRU` | yes | yes | yes | yes | no `dissoc`, clear, or metrics | keep private to Datahike |
| `core.memoize` 1.1.266 | yes | no | delegated count policies | n/a | function-centric | reject |

### `core.cache` and `cljs-cache`

These are attractive because Seon already resolves both transitively and their
protocols align. The spikes proved structural CLJS keys, access-refreshing LRU,
single-key invalidation, and clear. However, the stock LRU is entry-count-only;
adding estimated-output-token accounting, per-entry rejection, eviction metrics,
and safe concurrent ownership would be Seon-maintained cache code. That is more
surface than using the mature Node library in the only renderer.

`cljs-cache` is a port, not the same artifact or source file. The official
source at commit `4a2a3c8c6af93f0c69c09cdca99ebfa44552c34c` defines the same
protocol shape and count LRU, but no weight policy. Calling the pair "one
cross-platform implementation" would hide a real maintenance boundary.

### Taoensso Encore

Encore is already transitive and CLJC. Its `cache` function provides TTL,
approximate size, delete, clear, and concurrent same-key suppression. The pinned
source at commit `95d9d731f327f7866883877ae8d7944bff3ad130` performs size cleanup
periodically after overshooting its configured threshold. It has no token
weight, per-entry cap, or useful eviction accounting. It is a good convenience
memoizer, but a poor retained-HTML budget authority.

### Caffeine

Caffeine 3.2.4 is excellent JVM software: thread-safe, atomic same-key loading,
invalidation, removal listeners, and detailed statistics. The concurrency spike
computed one value for 24 simultaneous same-key callers. Official source at
commit `836b65c0a83e5d1641ded9c6de578654bc04b2e9` documents atomic loading in
`Cache.java:57-82` and metrics/invalidation in `Cache.java:154-197`.

It is not the current answer for two reasons. The JVM process is the database
writer, not a renderer, and `Caffeine.java:423-475` explicitly makes
`maximumSize` and `maximumWeight` mutually exclusive. Choosing it now would
either lose one required bound or add wrapper policy in an unused parallel
runtime.

### Datahike `WeightedLRU`

The maintained Datahike source already contains a CLJC LRU bounded by both
entry count and weight (`reference-code/datahike/src/datahike/lru.cljc:68-158`),
with property tests in `reference-code/datahike/test/datahike/test/`.
It is intentionally a private query-cache structure, not an application cache:
reads do not refresh recency unless callers re-associate, it implements no
`dissoc`/clear operation or metrics, and it deliberately retains one overweight
newest entry. The spike's `(dissoc cache key)` failed on both platforms. Do not
couple the web renderer to a private Datahike namespace or fork a second copy.

### `core.memoize`

`core.memoize` provides good JVM same-key suppression through its retrying delay,
and the spike computed once for 24 concurrent callers. It is JVM-only: the CLJS
probe failed because only `clojure/core/memoize.clj` exists. More importantly,
wrapping arbitrary renderer functions is the wrong boundary; the database value
would naturally leak into keys, invalidation would be implicit, and agents would
have to understand memoization.

## Dependency evidence

The current `clojure -X:deps tree :aliases '[:cljs]'` already resolves:

```text
org.clojure/core.memoize 1.1.266
org.clojure/core.cache 1.1.234
com.github.pkpkpk/cljs-cache 1.0.21
org.clojars.mmb90/cljs-cache 0.1.4
```

Those are transitive Datahike/Konserve dependencies, not an application cache
contract. Seon has no current JS cache dependency. `lru-cache` 11.5.2 has no
runtime dependency and supports the Node versions required by its pinned
package. It should be an explicit application dependency only if the profile
gate passes.

Official sources inspected:

- Caffeine tag `v3.2.4`, commit
  `836b65c0a83e5d1641ded9c6de578654bc04b2e9`;
- `lru-cache` tag `v11.5.2`, commit
  `16b3a916662ab449d496b7b4b4f04132565d1d28`;
- `core.cache` current source commit
  `bdf41c62ce1d2047f8d860a3bd936b85d144f2b3` and release `1.1.234`
  behavior used by Seon;
- `core.memoize` current source commit
  `6d0e5d9ce8e822301de34155ec095eba8c1c7f49` and release `1.1.266`
  behavior used by Seon;
- `cljs-cache` release `1.0.21`, commit
  `4a2a3c8c6af93f0c69c09cdca99ebfa44552c34c`;
- Encore tag `v3.85.0`, commit
  `95d9d731f327f7866883877ae8d7944bff3ad130`;
- maintained Datahike checkout commit
  `ea6e98812df0d9ddb3d7b089a350505e01ff8c0c`, with weighted LRU introduced
  by commit `d1634958602cc0392aecf68f4483069326c7eea3`.

## Disposable spike results

These are behavior probes, not production benchmarks. JVM startup and CLJS
compilation dominate the timings.

### JVM

Command:

```bash
/usr/bin/time -lp clojure -Sdeps \
  '{:paths ["/tmp/seon-cache-research/spikes" "/Users/sean/src/seon/reference-code/datahike/src"]
    :deps {org.clojure/clojure {:mvn/version "1.12.0"}
           org.clojure/core.cache {:mvn/version "1.1.234"}
           org.clojure/core.memoize {:mvn/version "1.1.266"}
           org.clojure/data.priority-map {:mvn/version "1.2.0"}
           com.github.ben-manes.caffeine/caffeine {:mvn/version "3.2.4"}
           com.taoensso/encore {:mvn/version "3.85.0"}}}' \
  -M /tmp/seon-cache-research/spikes/cache_spike.clj
```

Observed elapsed time: 1.77 seconds. Relevant exact output:

```clojure
{:core.cache/after-clear {},
 :core.memoize/concurrent-values #{42},
 :datahike.lru/weight-keys #{:b},
 :caffeine/after-clear #{},
 :core.cache/after-invalidate {:c 3},
 :encore/concurrent-values #{42},
 :encore/concurrent-calls 1,
 :core.memoize/concurrent-calls 1,
 :caffeine/size-keys #{:c :a},
 :encore/after-bound {:a 2, :b 1, :c 1},
 :datahike.lru/count-keys #{:c :a},
 :encore/after-clear {:a 4, :b 1, :c 1},
 :caffeine/concurrent-values #{42},
 :caffeine/weight-keys #{:a},
 :datahike.lru/dissoc #:cache-spike{:error "java.lang.ClassCastException"},
 :caffeine/concurrent-calls 1,
 :caffeine/db-like-collected? true,
 :encore/after-invalidate {:a 3, :b 1, :c 1},
 :caffeine/after-invalidate #{:c},
 :core.cache/lru {:a 1, :c 3},
 :caffeine/stats "CacheStats{hitCount=1, missCount=0, loadSuccessCount=0, loadFailureCount=0, totalLoadTime=0, evictionCount=1, evictionWeight=1}"}
```

### ClojureScript/Node

Commands:

```bash
npm install --prefix /tmp/seon-cache-research/spikes \
  --no-package-lock --no-save lru-cache@11.5.2
cd /tmp/seon-cache-research/spikes
clojure -M -m shadow.cljs.devtools.cli compile spike
NODE_PATH=/tmp/seon-cache-research/spikes/node_modules \
  /usr/bin/time -lp node shadow-out/main.js
```

The Shadow build compiled 126 files with zero warnings in 1.01 seconds. The
Node probe elapsed time was 0.10 seconds. Relevant exact output:

```clojure
{:datahike.lru/weight-keys #{:b},
 :node-lru/weight-keys #{"heavy"},
 :node-lru/concurrent-values #{42},
 :node-lru/equivalent-data-key-hit? false,
 :encore/after-bound {:a 2, :b 1, :c 1},
 :node-lru/count-keys #{"c" "a"},
 :cljs.cache/after-clear {},
 :datahike.lru/count-keys #{:a :c},
 :node-lru/after-clear #{},
 :encore/after-clear {:a 4, :b 1, :c 1},
 :node-lru/after-invalidate #{},
 :datahike.lru/dissoc {:cache-spike/error "Error"},
 :node-lru/events [["b" "evict"] ["a" "evict"] ["c" "evict"] ["heavy" "delete"]],
 :node-lru/concurrent-fetch-calls 1,
 :cljs.cache/after-invalidate {:c 3},
 :node-lru/status {:op "get", :key "missing", :cache #object[u [object LRUCache]], :get "miss"},
 :encore/after-invalidate {:a 3, :b 1, :c 1},
 :cljs.cache/lru {:a 1, :c 3},
 :cljs.cache/equivalent-data-key-hit? true,
 :node-lru/retains-db-like? false,
 :node-lru/projected-key-only #{"unit|digest|1"}}
```

The `retains-db-like?` assertion inspected actual cache keys and values after a
large fake database-shaped object was deliberately excluded by projection. The
JVM spike additionally used a weak reference and observed collection after the
only strong reference was released. These prove the proposed key/value shape,
not a magical property of either library: passing a database value to any cache
would retain it.

### `core.memoize` CLJS failure

Command:

```bash
clojure -M -m shadow.cljs.devtools.cli compile memoize-probe
```

Exact failure:

```text
The required namespace "clojure.core.memoize" is not available, it was required by "memoize_probe.cljs".
"clojure/core/memoize.clj" was found on the classpath. Maybe this library only supports CLJ?
```

## Implementation acceptance gates

### Before adding `lru-cache`

- Instrument reopen/cross-subscription opportunities with the active-only
  engine first.
- Record avoided renderer calls and avoided estimated output tokens, not raw
  text length.
- Demonstrate a meaningful hit rate on real root, agent, debug, and `/data`
  browser behavior. If active sharing captures nearly all reuse, add no LRU.
- Choose count, total-token, and per-entry-token limits from the observed
  distribution rather than arbitrary defaults.

### Correctness

- An unrelated transaction invokes zero queries/renderers for an unaffected
  unit.
- A helper-indirected database read updates an already-open unit even when the
  source-declared attribute set is incomplete.
- An unknown/broad read is conservative and cannot become stale.
- Equal replayed read results skip renderer invocation.
- Equal serialized output emits no Datastar patch.
- Equivalent tabs share one normalized active result.
- Closing the final consumer releases active observations and output.
- Source, input, or read-result digest changes miss.
- Eviction, disabled cache, forced zero capacity, and cache exceptions produce
  the same HTML and patch sequence as a cold render.
- A digest collision or malformed retained key becomes a miss.
- Error and timeout results are not retained in the recent LRU.

### Bounds and lifecycle

- Entry count never exceeds its configured bound after an operation returns.
- Total retained output-token weight never exceeds its configured bound.
- An overlarge single entry is rejected and leaves existing useful entries
  intact.
- Repeated unique large outputs do not cause unbounded RSS growth after GC.
- Heap/key inspection finds no Datahike database/entity/connection value in
  keys, values, closures, listener data, or metrics.
- Final feed close, hot reload, and explicit reset clear the appropriate active
  state; recent reuse survives only the lifecycle explicitly chosen by the
  engine.

### Observability and development feedback

- Hits, misses, inserts, evictions, overlarge rejections, current entries, and
  current output-token weight are queryable from one engine-owned snapshot.
- Development mode validates the fully namespaced key/value envelope and fails
  immediately on a database-like retained value.
- Production mode logs the same defect, treats it as a miss, and keeps the feed
  live.
- Behavioral tests assert calls, patches, lifecycle, bounds, and equivalent
  output, never specific context wording.

## Rejected shortcuts

- **Memoize every renderer.** It hides invalidation, encourages database keys,
  gives agents a new API to misuse, and `core.memoize` does not run in CLJS.
- **Cache whole pages.** One small context or transcript change invalidates a
  huge value and recreates the HTML/SSE amplification this refactor is removing.
- **Persist cache entries or hit counters.** They are derived runtime state and
  would turn optimization into database truth requiring reconciliation.
- **Use declared source attributes as the cache dependency key.** They are
  non-transitive hints and already caused stale nested reads.
- **Adopt Caffeine and `lru-cache` simultaneously now.** The JVM has no renderer;
  two backends would be speculative parallel machinery.
- **Expose Datahike's private LRU.** It has query-cache semantics and an
  intentionally incomplete application-cache interface.
- **Implement a new CLJC cache.** The active layer needs no library, and the
  optional Node layer already has a mature implementation with the required
  bounds.

## Exact disposable probe sources

The probes lived only under `/tmp/seon-cache-research/spikes`; no production
file or dependency was changed. Their exact source follows for reproduction.

### JVM probe

```clojure
(require '[clojure.core.cache :as cc]
         '[clojure.core.memoize :as memo]
         '[datahike.lru :as dh-lru]
         '[taoensso.encore :as enc])
(import '(com.github.benmanes.caffeine.cache Caffeine Weigher)
        '(java.lang.ref WeakReference)
        '(java.util.concurrent CountDownLatch))

(defn force-gc [^WeakReference ref]
  (loop [n 0]
    (when (and (< n 100) (.get ref))
      (System/gc)
      (Thread/sleep 5)
      (recur (inc n))))
  (nil? (.get ref)))

(def core-state (atom (cc/lru-cache-factory {} :threshold 2)))
(defn core-put! [k v] (swap! core-state cc/miss k v))
(defn core-get! [k]
  (let [before @core-state]
    (if (cc/has? before k)
      (do (swap! core-state cc/hit k) (cc/lookup @core-state k))
      ::miss)))
(core-put! :a 1)
(core-put! :b 2)
(core-get! :a)
(core-put! :c 3)
(def core-after-lru (into {} @core-state))
(swap! core-state cc/evict :a)
(def core-after-invalidate (into {} @core-state))
(swap! core-state cc/seed {})
(def core-after-clear (into {} @core-state))

(def memo-calls (atom 0))
(def memo-gate (CountDownLatch. 1))
(def memoized
  (memo/lru (fn [x]
              (swap! memo-calls inc)
              (.await memo-gate)
              (* x 2))
            :lru/threshold 8))
(def memo-futures (doall (repeatedly 24 #(future (memoized 21)))))
(Thread/sleep 20)
(.countDown memo-gate)
(def memo-results (mapv deref memo-futures))

(def caffeine-evictions (atom []))
(def ^com.github.benmanes.caffeine.cache.Cache caffeine-size
  (-> (Caffeine/newBuilder)
      (.maximumSize 2)
      (.recordStats)
      (.removalListener
       (reify com.github.benmanes.caffeine.cache.RemovalListener
         (onRemoval [_ k _v cause]
           (swap! caffeine-evictions conj [k (str cause)]))))
      (.build)))
(.put caffeine-size :a 1)
(.put caffeine-size :b 2)
(.getIfPresent caffeine-size :a)
(.put caffeine-size :c 3)
(.cleanUp caffeine-size)
(def caffeine-size-keys (set (.keySet (.asMap caffeine-size))))
(.invalidate caffeine-size :a)
(def caffeine-after-invalidate (set (.keySet (.asMap caffeine-size))))
(.invalidateAll caffeine-size)
(def caffeine-after-clear (set (.keySet (.asMap caffeine-size))))

(def ^com.github.benmanes.caffeine.cache.Cache caffeine-weight
  (-> (Caffeine/newBuilder)
      (.maximumWeight 5)
      (.weigher (reify Weigher (weigh [_ _ v] (count v))))
      (.build)))
(.put caffeine-weight :a [1 2 3])
(.put caffeine-weight :b [4 5 6])
(.cleanUp caffeine-weight)
(def caffeine-weight-keys (set (.keySet (.asMap caffeine-weight))))

(def caffeine-calls (atom 0))
(def caffeine-gate (CountDownLatch. 1))
(def ^com.github.benmanes.caffeine.cache.Cache caffeine-concurrent
  (-> (Caffeine/newBuilder) (.maximumSize 8) (.build)))
(def caffeine-futures
  (doall
   (repeatedly
    24
    #(future
       (.get caffeine-concurrent :answer
             (reify java.util.function.Function
               (apply [_ _]
                 (swap! caffeine-calls inc)
                 (.await caffeine-gate)
                 42)))))))
(Thread/sleep 20)
(.countDown caffeine-gate)
(def caffeine-results (mapv deref caffeine-futures))

(def db-like (atom (byte-array (* 8 1024 1024))))
(def db-ref (WeakReference. @db-like))
(.put caffeine-concurrent
      [:cache-spike/unit :cache-spike/digest {:seon.render/input 1}]
      {:seon.render/html "ok"})
(reset! db-like nil)
(def caffeine-dropped-db? (force-gc db-ref))

(def dh-count
  (-> (dh-lru/weighted-lru 2 0)
      (assoc :a 1) (assoc :b 2) (assoc :a 1) (assoc :c 3)))
(def dh-weight
  (-> (dh-lru/weighted-lru 100 5 count)
      (assoc :a [1 2 3]) (assoc :b [4 5 6])))
(def dh-dissoc
  (try
    {:cache-spike/ok (dissoc dh-count :a)}
    (catch Throwable e {:cache-spike/error (.getName (class e))})))

(def encore-calls (atom {}))
(def encore-cache
  (enc/cache {:size 2 :gc-every 1}
             (fn [x] (swap! encore-calls update x (fnil inc 0)) x)))
(doseq [x [:a :b :c :a]] (encore-cache x))
(def encore-after-bound @encore-calls)
(encore-cache :cache/del :a)
(encore-cache :a)
(def encore-after-invalidate @encore-calls)
(encore-cache :cache/del :cache/all)
(encore-cache :a)
(def encore-after-clear @encore-calls)

(def encore-concurrent-calls (atom 0))
(def encore-gate (CountDownLatch. 1))
(def encore-concurrent
  (enc/cache {:size 8 :gc-every 1}
             (fn [x]
               (swap! encore-concurrent-calls inc)
               (.await encore-gate)
               (* x 2))))
(def encore-futures (doall (repeatedly 24 #(future (encore-concurrent 21)))))
(Thread/sleep 20)
(.countDown encore-gate)
(def encore-results (mapv deref encore-futures))

(prn
 {:core.cache/lru core-after-lru
  :core.cache/after-invalidate core-after-invalidate
  :core.cache/after-clear core-after-clear
  :core.memoize/concurrent-calls @memo-calls
  :core.memoize/concurrent-values (set memo-results)
  :caffeine/size-keys caffeine-size-keys
  :caffeine/after-invalidate caffeine-after-invalidate
  :caffeine/after-clear caffeine-after-clear
  :caffeine/weight-keys caffeine-weight-keys
  :caffeine/concurrent-calls @caffeine-calls
  :caffeine/concurrent-values (set caffeine-results)
  :caffeine/stats (str (.stats caffeine-size))
  :caffeine/db-like-collected? caffeine-dropped-db?
  :datahike.lru/count-keys (set (keys (:key-value (.-state dh-count))))
  :datahike.lru/weight-keys (set (keys (:key-value (.-state dh-weight))))
  :datahike.lru/dissoc dh-dissoc
  :encore/after-bound encore-after-bound
  :encore/after-invalidate encore-after-invalidate
  :encore/after-clear encore-after-clear
  :encore/concurrent-calls @encore-concurrent-calls
  :encore/concurrent-values (set encore-results)})
(shutdown-agents)
```

### CLJS probe

```clojure
(ns cache-spike
  (:require [cljs.cache :as cc]
            [datahike.lru :as dh-lru]
            [taoensso.encore :as enc]
            ["lru-cache" :refer [LRUCache]]))

(def core-state (atom (cc/lru-cache-factory {} :threshold 2)))
(defn core-put! [k v] (swap! core-state cc/miss k v))
(defn core-get! [k]
  (let [before @core-state]
    (if (cc/has? before k)
      (do (swap! core-state cc/hit k) (cc/lookup @core-state k))
      ::miss)))
(core-put! :a 1)
(core-put! :b 2)
(core-get! :a)
(core-put! :c 3)
(def core-after-lru (.-cache @core-state))
(swap! core-state cc/evict :a)
(def core-after-invalidate (.-cache @core-state))
(swap! core-state cc/seed {})
(def core-after-clear (.-cache @core-state))
(def equivalent-key-a [:cache-spike/unit {:seon.render/input 1}])
(def equivalent-key-b [:cache-spike/unit {:seon.render/input 1}])
(def equivalent-core-cache
  (cc/miss (cc/lru-cache-factory {} :threshold 2) equivalent-key-a :hit))
(def equivalent-core-hit? (cc/has? equivalent-core-cache equivalent-key-b))

(def node-events (atom []))
(def node-cache
  (LRUCache.
   #js {:max 2
        :maxSize 5
        :sizeCalculation (fn [v _k] (.-weight v))
        :dispose (fn [_v k reason]
                   (swap! node-events conj [(str k) reason]))}))
(def equivalent-node-cache (LRUCache. #js {:max 2}))
(.set equivalent-node-cache equivalent-key-a :hit)
(def equivalent-node-hit? (some? (.get equivalent-node-cache equivalent-key-b)))
(.set node-cache "a" #js {:weight 2 :value 1})
(.set node-cache "b" #js {:weight 2 :value 2})
(.get node-cache "a")
(.set node-cache "c" #js {:weight 2 :value 3})
(def node-count-keys (set (array-seq (js/Array.from (.keys node-cache)))))
(.set node-cache "heavy" #js {:weight 4 :value 9})
(def node-weight-keys (set (array-seq (js/Array.from (.keys node-cache)))))
(.delete node-cache "heavy")
(def node-after-invalidate (set (array-seq (js/Array.from (.keys node-cache)))))
(.clear node-cache)
(def node-after-clear (set (array-seq (js/Array.from (.keys node-cache)))))

(def node-status #js {})
(.get node-cache "missing" #js {:status node-status})

(def fetch-calls (atom 0))
(def async-cache
  (LRUCache.
   #js {:max 8
        :fetchMethod (fn [_key _old _opts]
                       (swap! fetch-calls inc)
                       (js/Promise.
                        (fn [resolve _]
                          (js/setTimeout #(resolve 42) 15))))}))

(def dh-count
  (-> (dh-lru/weighted-lru 2 0)
      (assoc :a 1) (assoc :b 2) (assoc :a 1) (assoc :c 3)))
(def dh-weight
  (-> (dh-lru/weighted-lru 100 5 count)
      (assoc :a [1 2 3]) (assoc :b [4 5 6])))
(def dh-dissoc
  (try
    {:cache-spike/ok (dissoc dh-count :a)}
    (catch :default e {:cache-spike/error (.-name e)})))

(def encore-calls (atom {}))
(def encore-cache
  (enc/cache {:size 2 :gc-every 1}
             (fn [x] (swap! encore-calls update x (fnil inc 0)) x)))
(doseq [x [:a :b :c :a]] (encore-cache x))
(def encore-after-bound @encore-calls)
(encore-cache :cache/del :a)
(encore-cache :a)
(def encore-after-invalidate @encore-calls)
(encore-cache :cache/del :cache/all)
(encore-cache :a)
(def encore-after-clear @encore-calls)

(def fake-db #js {:large (js/Uint8Array. (* 8 1024 1024))})
(.set async-cache "unit|digest|1" #js {:html "ok"})
(def cache-keys-before-async
  (set (array-seq (js/Array.from (.keys async-cache)))))
(def node-retains-db?
  (boolean
   (some #(identical? fake-db %)
         (concat (array-seq (js/Array.from (.keys async-cache)))
                 (array-seq (js/Array.from (.values async-cache)))))))

(defn main! []
  (-> (js/Promise.all
       (into-array (repeatedly 24 #(.fetch async-cache "answer"))))
      (.then
       (fn [values]
         (println
          (pr-str
           {:cljs.cache/lru core-after-lru
            :cljs.cache/after-invalidate core-after-invalidate
            :cljs.cache/after-clear core-after-clear
            :cljs.cache/equivalent-data-key-hit? equivalent-core-hit?
            :node-lru/count-keys node-count-keys
            :node-lru/weight-keys node-weight-keys
            :node-lru/after-invalidate node-after-invalidate
            :node-lru/after-clear node-after-clear
            :node-lru/status (js->clj node-status :keywordize-keys true)
            :node-lru/equivalent-data-key-hit? equivalent-node-hit?
            :node-lru/events @node-events
            :node-lru/concurrent-fetch-calls @fetch-calls
            :node-lru/concurrent-values (set (array-seq values))
            :node-lru/projected-key-only cache-keys-before-async
            :node-lru/retains-db-like? node-retains-db?
            :datahike.lru/count-keys (set (keys (:key-value (.-state dh-count))))
            :datahike.lru/weight-keys (set (keys (:key-value (.-state dh-weight))))
            :datahike.lru/dissoc dh-dissoc
            :encore/after-bound encore-after-bound
            :encore/after-invalidate encore-after-invalidate
            :encore/after-clear encore-after-clear}))))
      (.catch
       (fn [e]
         (js/console.error e)
         (set! (.-exitCode js/process) 1)))))

(defn -main [] (main!))
```

The exact CLJS-only `core.memoize` failure probe was:

```clojure
(ns memoize-probe
  (:require [clojure.core.memoize :as memo]))

(defn -main [] (println (memo/memo identity)))
```
