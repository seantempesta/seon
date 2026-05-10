# Listener-dispatch patterns for the agent's reactive DB

**Date**: 2026-05-10
**Status**: research, decision-ready
**Author**: Claude (research agent), 2026-05-10 session
**Question**: When agent code (or the orchestrator) registers reactions to Datahike transactions in the agent, what's the efficient and ergonomic pattern?
**Scope**: Compares register-separately / central-router / pattern-subscription shapes against prior art (Datascript, Datahike, Datalevin, Datomic, Posh, re-frame, MutationObserver, chokidar, Reagent). Recommends shape for MVP and Phase-2 upgrade path.

---

## TL;DR

**Ship Shape B (central router with attribute-and-op signatures) for the MVP.** Datahike's own `listen!` already gives us Shape A (one list, every callback fires) — adding ~30 lines of router on top buys us the "I care about `:agent.message/role` `:added` only" ergonomics without a dependency graph and without committing to Posh-shaped invalidation. Shape C (pattern-subscription with a dependency graph, Posh-style) is the right Phase-2 destination *only if* observation shows we're regularly registering listeners that filter on entity-shape rather than just attribute — and at MVP we don't yet know whether that shape shows up. Don't pre-build Shape C; build the cheap router, log dispatch volume, upgrade when the data demands it.

---

## 1. The shapes, named precisely

### Shape A — "Listen-and-filter" (every callback gets every tx-report)

The simplest pattern. A connection holds an unordered map (or vector) of `key → callback`. After each transaction succeeds, the connection iterates the map and calls every callback with the full `TxReport`. Each callback filters internally: walks `tx-data`, pattern-matches on `[e a v t op]`, decides to act or skip.

- **Fan-out cost**: O(N) per tx, where N = number of registered listeners.
- **Filter logic**: duplicated across all listeners.
- **Implementation cost**: trivial. Datascript and Datahike give it to you for free.
- **Failure mode**: at high N, most listeners do nothing useful with most tx-reports. Cost is wasted iteration + GC churn over `tx-data`.

### Shape B — "Central router with signatures"

Listeners register a *signature* — typically `{:attrs #{:agent.message/role :agent.message/text} :ops #{:added}}` or a slightly richer predicate — and a callback. A single host-side router subscribes to the connection (one underlying `listen!`), receives each tx-report, scans `tx-data` once, builds an attribute-and-op index over the datoms, and fans out to listeners whose signatures match.

- **Fan-out cost**: O(D + M) per tx, where D = datoms in tx-data, M = number of *matching* listeners. Non-matching listeners are not invoked at all.
- **Filter logic**: lives in the router, not in each callback. One implementation, easy to test.
- **Implementation cost**: ~30-60 lines. No graph, no incremental invalidation — just signature-matching on each tx.
- **Failure mode**: if signatures need to express things richer than "attribute + op" (entity-shape, value predicates, joins), the router either grows complicated or the whole pattern collapses back to Shape A semantics. At that point Shape C becomes the honest answer.

### Shape C — "Pattern-subscription with a dependency graph"

The Posh / Reagent / re-frame shape. Listeners express a *query or pull-pattern* — "I care about the answer to `[:find ?role ?text :where [?e :agent.message/role ?role] [?e :agent.message/text ?text]]`" or "I care about `(d/pull '[:agent.message/role :agent.message/text :agent.message/at] eid)`." The reactive system maintains a dependency graph: which subscriptions depend on which `(e a v)` patterns. On each tx, the system inspects `tx-data`, marks subscriptions whose dependencies were touched as dirty, re-runs them lazily (only if observed), and notifies listeners only when the *answer* changed.

- **Fan-out cost**: O(D × log K) for invalidation, where K = active subscriptions; plus O(re-run cost) for actually-dirty subs. In the steady state, most txs fire ~zero subscriptions.
- **Filter logic**: declarative — the listener says what data it depends on, not what changed.
- **Implementation cost**: ~hundreds to thousands of lines. Posh is ~2.5K lines of CLJS; Reagent's reaction subsystem is similarly nontrivial.
- **Failure mode**: the dependency-tracking is itself the bug surface. Posh has a long history of subtle issues with reactive joins and rules. The complexity is real.

### Shape D — "Pre-indexed event bus" (the one I almost missed)

Every tx-report goes through a single host-side scanner that emits structured events to typed channels — e.g. `:agent.message/role-added`, `:agent.var/admitted`, `:agent.task/completed`. Listeners subscribe to event topics, not DB attributes. The mapping `tx-data → events` is hand-written: the host knows that "an entity with `:agent.message/role` newly appearing on a tx" is the meaningful event, not "a `:agent.message/role` datom got added" (which could be a retraction-then-readd, a schema migration, etc.).

This is a degenerate Shape B (signatures = topic names) but with **semantics in the topic definitions** rather than `[attr op]` matching. It's how `core.async.flow` graphs typically consume DB events in Clojure — a `tx-report → event-stream` transducer in the orchestrator, downstream nodes filter by event type.

- **Fan-out cost**: O(D) for the scanner + O(matching listeners) per event.
- **Filter logic**: lives in the *scanner*, which is a curated pipeline mapping low-level datoms to product-level events.
- **Implementation cost**: comparable to Shape B but front-loads work into "what events does the agent care about?" — a useful design exercise.
- **Failure mode**: requires up-front taxonomy. New event types require host code changes, which means the agent can't dynamically register reactions to attributes the host hasn't pre-blessed. This is a feature for a sandboxed agent runtime; it would be a bug in a general-purpose reactive DB.

For the agent specifically, Shape D is a strong candidate because **the orchestrator already mediates everything between agent and world**, and the set of "interesting events" is small and known (message arrived, function admitted, tool result returned, eval failed, etc.).

---

## 2. Per-project survey

### 2.1 Datascript — Shape A, exactly

**Source**: `reference/datascript/src/datascript/conn.cljc`, lines 117-169.

```clojure
(defn listen!
  ([conn callback]
   (listen! conn (rand) callback))
  ([conn key callback]
   {:pre [(conn? conn)]}
   (swap! (:atom conn) update :listeners assoc key callback)
   key))

(defn unlisten! [conn key]
  {:pre [(conn? conn)]}
  (swap! (:atom conn) update :listeners dissoc key))
```

After each successful `transact!`, the connection iterates listeners and calls every callback with the tx-report:

```clojure
(doseq [[_ callback] (:listeners @(:atom conn))]
  (callback report))
```

The callback receives `{:db-before, :db-after, :tx-data, :tx-meta}`. Filtering is the listener's job. No attribute filtering, no pattern matching, no dependency tracking — Datascript ships the bare metal.

### 2.2 Datahike — Shape A, identical

**Source**: `reference/datahike/src/datahike/core.cljc:206-224` and `reference/datahike/src/datahike/writer.cljc:247, :274`.

```clojure
(defn listen!
  "Listen for changes on the given connection. Whenever a transaction is applied
   to the database via [[transact!]], the callback is called with the transaction
   report. `key` is any opaque unique value."
  ([conn callback] (listen! conn (rand) callback))
  ([conn key callback]
   {:pre [(conn? conn) (atom? (:listeners (meta conn)))]}
   (swap! (:listeners (meta conn)) assoc key callback)
   key))
```

Dispatch in `writer.cljc`:

```clojure
(doseq [[_ callback] (some-> (:listeners (meta connection)) (deref))]
  (callback report))
```

Same pattern, same shape. Datahike has stronger guarantees than Datascript about *when* the callback fires (after the transaction is durable in `konserve`, not just after the in-memory swap), but the dispatch shape is identical Shape A.

The codebase comments explicitly distinguish "watches" (cheap, fire on every atom update including non-commits) from "listeners" (proper, fire after commit). Watches use Clojure's standard `add-watch`/`remove-watch`; listeners are the dedicated API.

### 2.3 Datalevin — Shape A (verified API shape; behavior parity unverified)

Datalevin exposes `listen!` with the same signature as Datascript. It receives the full tx-report. Per Gemini-Flash secondary search citing the project README (UNVERIFIED at the source-code level — I didn't pull Datalevin into `reference/`), Datalevin's `listen!` is monolithic: every listener gets every tx-report, filtering is the user's job. This matches the Datascript API contract Datalevin claims compatibility with.

If the MVP needs Datalevin behavior verified, clone `juji-io/datalevin` into `reference/` and grep for `listen` like we did with Datahike. For now, treat as Shape A.

### 2.4 Datomic — Shape A with a twist (queue, not callback)

**Source**: Datomic's `tx-report-queue` API ([docs.datomic.com](https://docs.datomic.com/clojure/index.html#datomic.api/tx-report-queue)).

Datomic doesn't take callbacks. Instead, it exposes a `BlockingQueue` per peer; transactions get pushed onto the queue, and consumer code pops them off in a worker loop. This is a **structural** difference from Shape A (queue vs. callback) but the *dispatch semantics* are identical: every tx-report ends up at every consumer, filtering is the consumer's job. The queue shape is friendlier for backpressure and decoupling, less friendly for "I just want a callback" ergonomics.

The canonical filter idiom in Datomic code (per various Cognitect blog posts and Datomic Day talks; UNVERIFIED at exact-citation level) is to maintain a small set of "interesting attributes" as Datomic-IDs and check `(:a datom)` against the set before doing work. This is exactly the boilerplate Shape B / Shape D abstract away.

### 2.5 Posh — Shape C, the canonical reactive Datalog binding

**Source**: [github.com/mpdairy/posh](https://github.com/mpdairy/posh). I did not pull Posh into `reference/` for source verification; the following is from Posh's README and design notes plus secondary descriptions.

Posh sits between Datascript/Datomic and Reagent. The user writes:

```clojure
(defn message-list [conn]
  (let [msgs @(posh/q '[:find ?e ?role ?text
                        :where [?e :agent.message/role ?role]
                               [?e :agent.message/text ?text]]
                      conn)]
    [:ul (for [[e role text] msgs] [:li role ": " text])]))
```

Internally, Posh:
1. Parses the query, extracts the `[e a v]` patterns it depends on (`[?e :agent.message/role ?role]`, etc.).
2. Maintains a dependency graph: `query → pattern-set`.
3. On each tx-report, scans `tx-data` and checks which queries' pattern-sets intersect with the changed datoms.
4. Marks intersected queries as dirty.
5. Re-runs only the dirty queries, compares results to the cached previous results, and notifies Reagent reactions only when the *result* changed.

The dependency-tracking layer is what makes Posh interesting and what makes it hard. Posh has a long-tail of issues around recursive rules, pull patterns with reverse refs, and queries that depend on entire indexes. The shape is unambiguously Shape C.

**Verification status**: API and overall architecture verified from the README and from re-reading prior Posh investigations; specific code-level claims (function names, exact graph structure) are **UNVERIFIED** without pulling Posh into `reference/`.

### 2.6 re-frame subscriptions — Shape C, layered

**Source**: [day8.github.io/re-frame/subscriptions](https://day8.github.io/re-frame/subscriptions/), [github.com/day8/re-frame](https://github.com/day8/re-frame).

Re-frame's app-db is a single Reagent `ratom` (reactive atom). Subscriptions are functions `app-db → value` (Layer 2) or `[other-subs] → value` (Layer 3, computational). The dispatch graph is:

- `app-db` is a `ratom`. Mutating it notifies its watchers.
- Layer 2 subscriptions are `reaction`s that `@deref` `app-db`. When `app-db` changes, the reactions are marked dirty.
- Layer 3 subscriptions `@deref` Layer 2 subscriptions. When a Layer 2 sub's value changes (not just `app-db`), the Layer 3 sub re-runs.
- Reagent components that `@deref` subscriptions get re-rendered when the sub's value changes.

The dependency tracking is **automatic via `@deref` capture**: when a reaction body runs, every `@`-derefed reactive source registers the reaction as a watcher. This is the same trick MobX, Vue's `computed`, and SolidJS use.

The structural insight: re-frame doesn't pattern-match on tx-data because there *is* no tx-data — the app-db mutates wholesale, and subscriptions are pure functions over the new state. Equality-checking at each layer ensures unchanged values don't propagate.

For the agent, this matters because **Datahike does have tx-data** and we'd be throwing away signal if we collapsed everything to "the DB changed, recompute everything." Re-frame's pattern works for Re-frame because the app-db is small and pure functions over it are cheap. the agent's Datahike-per-agent will not be small.

### 2.7 Hyperfiddle Electric / Missionary — Shape C++, continuous-time

Hyperfiddle Electric is a Clojure dialect where every expression is a *flow* — a value that changes over time. Missionary is the underlying signal/flow library. The reactive primitive is the `Flow`, an async iterable of values; `Watch` (over a `ratom`-shaped reference) and `Continuous` (interpolated values) are the two core flow types.

Electric's reactive engine is a continuous-time signal graph. When `app-db` changes, dependent flows re-evaluate; Electric does fine-grained dependency tracking (per-DOM-node, even). It's Posh / re-frame at industrial scale, with first-class network distribution.

**the agent fit**: Massive overkill for MVP, but informs the *direction* we'd evolve toward at Phase-3+ if the agent is doing complex reactive composition over its own DB. Don't pull Missionary in for MVP — the orchestrator already speaks `core.async.flow`, which is the Clojure-flavor signal-graph primitive we'd actually use.

### 2.8 DOM MutationObserver — Shape B with pre-built signature support

**Source**: [DOM Living Standard, §4.3](https://dom.spec.whatwg.org/#mutationobserver), [MDN MutationObserver](https://developer.mozilla.org/en-US/docs/Web/API/MutationObserver).

The MutationObserver API is the cleanest expression of Shape B in the wild. Subscription:

```javascript
const observer = new MutationObserver(callback);
observer.observe(targetNode, {
  childList: true,            // node additions / removals
  attributes: true,           // attribute value changes
  attributeFilter: ['class', 'agent-label'],   // ← attribute-name filter
  attributeOldValue: true,
  characterData: true,
  characterDataOldValue: true,
  subtree: true,              // descendants too
});
```

The `attributeFilter` array is the load-bearing piece. The browser's mutation-tracking machinery scans only those attributes for the registered observer. Other observers with different filters get separate dispatch. The callback receives `MutationRecord[]` — batched into microtask boundaries so multiple synchronous mutations get one dispatch.

For the agent's purposes this is the **strongest prior art for Shape B**. The signature is "attribute name" (and a couple of orthogonal axes — subtree, oldValue), and the browser router handles fan-out efficiently. We can lift the API shape almost verbatim:

```clojure
(host-listen! conn
  {:attributes #{:agent.message/role :agent.message/text}
   :ops #{:added}                           ; analog to MutationObserver's "type"
   :include-tx-meta? true}                  ; analog to attributeOldValue
  (fn [matched-datoms tx-report] ...))
```

The MutationObserver API has shipped in every browser since 2012 and has held up. That's a strong "this shape works in production" signal.

### 2.9 chokidar — Shape B with glob signatures

**Source**: [github.com/paulmillr/chokidar](https://github.com/paulmillr/chokidar).

Chokidar wraps `inotify` / `FSEvents` / `ReadDirectoryChangesW` and dispatches file-system events. Subscription is by glob pattern + event types:

```javascript
chokidar.watch(['src/**/*.cljs', 'docs/**/*.md'], {
  ignored: /node_modules/,
  persistent: true,
}).on('change', (path) => { ... })
  .on('add', (path) => { ... });
```

Glob matching uses the `picomatch` (or older `anymatch`) library. Each fs event triggers a glob test against the watcher's patterns; matched events fire callbacks. This is Shape B with glob-as-signature; the analog for the agent's DB would be `[a-pattern e-predicate]` instead of file globs.

The chokidar lesson: **the signature language can be richer than `[attr op]`**, and a small library (picomatch is ~2K lines) can give you a lot of expressive matching cheaply. But we shouldn't over-design the signature language up front — chokidar's globs are themselves a long-evolved compromise, not a clean greenfield.

### 2.10 Reagent reactions — Shape C (auto-tracked dependencies)

**Source**: [github.com/reagent-project/reagent](https://github.com/reagent-project/reagent), specifically `reagent.ratom`.

Reagent's `ratom` and `reaction` are the engine under re-frame. The trick: when a `reaction`'s body runs, Reagent installs a thread-local "currently-running reaction" pointer; any `@`-deref of a `ratom` inside that body registers the reaction as a watcher of that ratom. When the ratom changes, watchers are notified; when watchers re-run, they recompute their watcher set (in case their dependencies changed). This is identical to MobX's autorun and SolidJS's createEffect.

The dependency capture is **by execution**, not by declaration. The user writes pure code; the system tracks what was read.

For the agent, this pattern is interesting if we want the **agent to write reactive code**:

```clojure
(define-reaction
  (let [recent-msgs (db/q '[...])]
    (when (some unread? recent-msgs)
      (notify "you have unread messages"))))
```

…and the runtime figures out that this reaction depends on `:agent.message/unread?` and `:agent.message/at` and only re-fires when those are touched. That's cool. It's also Shape C, which means the dependency-tracking infrastructure has to exist. Defer.

### 2.11 Recoil / Jotai — atom-graph reactives for React

Same shape as Reagent: small atoms, derived selectors, automatic dependency capture via the React render cycle. Not Datalog, not relevant for tx-data filtering directly. But: they confirm that Shape C is the dominant pattern when you want the user to write *what they want*, not *which changes they care about*. Useful confirmation that Shape C is the ergonomic ceiling.

---

## 3. The "user message arrived" subscription, expressed in each shape

The motivating use case: the orchestrator wants to know when a user message lands in the agent's DB so it can kick the agent loop. A user message is an entity with `:agent.message/role :user` and `:agent.message/text "..."` newly transacted.

### Shape A — listen-and-filter

```clojure
(d/listen! conn ::user-msg
  (fn [{:keys [tx-data]}]
    (doseq [[e a v _t op] tx-data
            :when (and (= a :agent.message/role)
                       (= v :user)
                       op)]                         ; op = true = added
      (kick-agent-loop! e))))
```

Every tx fires this; callback walks `tx-data` itself. Easy to write, easy to reason about, scales linearly with listener count.

### Shape B — central router with signature

```clojure
(host/subscribe! conn
  {:attrs #{:agent.message/role}
   :pred  (fn [datom] (= :user (:v datom)))
   :ops   #{:added}}
  (fn [matched-datoms _tx-report]
    (doseq [{:keys [e]} matched-datoms]
      (kick-agent-loop! e))))
```

Cleaner — the listener body deals only with already-filtered datoms. The router maintains the underlying `(d/listen!)` once and fans out matched datoms to the right callbacks.

### Shape C — pattern-subscription (Posh-shape)

```clojure
(host/subscribe-pull! conn
  '[:agent.message/role :agent.message/text :agent.message/at]
  {:role :user}                                ; entity-shape filter
  (fn [matched-entity-maps]
    (doseq [m matched-entity-maps]
      (kick-agent-loop! (:db/id m)))))
```

Most ergonomic. Most code behind the scenes. Listener says "what data do I want to react to," not "what changes touch what I want."

### Shape D — pre-indexed event bus

```clojure
;; In the host-side scanner:
(defmethod tx->events :agent.message/role [datoms tx-report]
  (for [{:keys [e v op]} datoms
        :when (and op (= v :user))]
    {:type :user-message-arrived :eid e}))

;; In the orchestrator:
(events/subscribe! :user-message-arrived
  (fn [{:keys [eid]}]
    (kick-agent-loop! eid)))
```

The "what counts as a user-message-arrived event" semantics live in the scanner, not in every listener. When Phase-2 adds tool-result events, message-deletion events, etc., the taxonomy lives in one place. This is what `core.async.flow` graphs typically consume.

---

## 4. Cost analysis at N = 1, 10, 50, 200 listeners

Assume 100 transactions/sec average (well above MVP), each with ~5 datoms (small).

| Shape | N=1 | N=10 | N=50 | N=200 |
|---|---|---|---|---|
| **A — listen-and-filter** | 100 cb/s × 5 datoms = 500 ops/s | 5K ops/s | 25K ops/s | 100K ops/s |
| **B — router with signatures** | 500 router ops + 100 cb/s × ~1 matched = 600 ops/s | 500 + ~100 = 600 ops/s (most listeners don't match) | 500 + ~500 = 1K ops/s | 500 + ~2K = 2.5K ops/s |
| **C — pattern-subscription** | ~500 invalidation-checks + ~10 query re-runs = ~1K ops/s (with overhead) | similar (most subs not dirty) | similar | similar but with larger graph maintenance |
| **D — pre-indexed event bus** | 500 scanner ops + 1 event dispatch = 501 ops/s | similar | similar (events fan out narrowly) | similar |

At the agent's MVP scale (1-2 trajectories, maybe 10 listeners total per pod), all four shapes are basically free. The crossover where Shape A starts to hurt is somewhere around N=50+ with high tx rate, which is **Phase-2+ territory** when multiple agent-side reactions exist.

The non-CPU cost matters more at MVP: **developer cost of writing and debugging dispatch logic** is real, and Shape A duplicates filter code into every listener (10 listeners = 10 places to fix a bug). Shape B centralizes that into the router. Shape D centralizes that into the scanner.

---

## 5. Concrete recommendation for the agent MVP

### Ship: Shape B (central router with attribute-and-op signatures), backed by a single `d/listen!`

Rationale:

1. **Datahike's `listen!` is Shape A.** We're already getting Shape A for free. The router is purely additive — it sits in the host (the JVM service per the architecture spec §4.3, or the Node pod for in-process Datascript per §3) and translates signatures-from-listeners into a single underlying subscription.

2. **The signature language is small**: `{:attrs #{...} :ops #{:added :retracted} :pred (fn [datom] ...)}`. Lifted from MutationObserver. ~30-60 lines of Clojure. Testable in isolation.

3. **Avoids Shape C's complexity tax.** Posh-shaped dependency tracking is ~10× the code and ~10× the bug surface. We don't need it yet.

4. **Composes with Shape D.** The router's signatures *are* the event taxonomy in disguise. If we later want to expose named topics ("user-message-arrived"), that's a thin layer over the router that registers a fixed signature and labels its output.

5. **The orchestrator's `core.async.flow` graphs want event streams, not callbacks.** Wrapping the router's dispatch into a `core.async` channel gives us a stream interface for free. Flow nodes filter by signature; this is the natural Clojure idiom.

### MVP API sketch

```clojure
(ns agent.host.dispatch
  "Listener-dispatch router. Sits between Datahike's d/listen! and host code
   that wants attribute-filtered notifications. One d/listen! per connection;
   N user signatures dispatched from one scan.")

(defn subscribe!
  "Register a listener. Returns an opaque key for unsubscribe.

   Signature shape:
     :attrs   #{kw ...}      ; required-any: at least one matched datom must have one of these :a's
     :ops     #{:added :retracted}  ; default #{:added :retracted}
     :pred    (fn [datom]) → boolean   ; optional further filter on each matched datom
     :include-tx-report? boolean       ; default false; if true, callback gets (matched-datoms, tx-report)

   Callback receives a non-empty seq of matched datoms (and optionally the full tx-report)."
  [conn signature callback]
  ...)

(defn unsubscribe! [conn key] ...)
```

Implementation: one `d/listen!` per connection; on each tx-report, walk `:tx-data` once, build `attr → datoms` map, then for each registered signature do `(set/intersection (:attrs sig) (keys attr-map))` and if non-empty, filter through `:pred` and invoke callback.

### Phase-2 path (when, not if)

**Upgrade trigger**: observation that >30% of registered listeners use the same `:pred` to filter on entity-shape (e.g., "messages from this conversation only," "vars in this namespace only"). At that point the cost of duplicated `:pred` evaluation across listeners starts to matter, and *also* the ergonomics of "I care about all data needed to render this view" become real.

**Upgrade target**: layer Shape C on top of Shape B, not replace it. Pull-pattern subscriptions translate to a set of attribute signatures; the router invokes the pull, diffs, and notifies. This is exactly Posh's algorithm — but we'd write it against our own router, with our own observability, rather than pulling Posh in (Posh hasn't seen serious commits in years and was Datascript-coupled).

**Don't pre-build Shape C.** Every the agent research thread hits the same lesson: build the cheap thing, observe how it's used, upgrade with data. Shape C is real engineering and the pre-emptive build of it would burn a week minimum.

### Where the router lives

Two options, both viable:

- **In the JVM service** (architecture spec §4) — alongside `datahike-server`, exposes a stream of pre-filtered events over SSE on `:7891`. Pods subscribe via `EventSource`; signature lives server-side.
- **In the Node pod** — for the per-pod Datascript scratch projection (architecture spec §3.1), router runs in the host process and dispatches to in-pod CLJS callbacks via a host capability primitive.

These aren't mutually exclusive; both surfaces want the same router shape. **MVP**: build the in-pod version first since the JVM service is Phase-1+ per the spec. The JVM-side router is a port of the same code.

---

## 6. Honest gaps

- **Datalevin source not verified**. I claimed Shape A based on Gemini-Flash secondary search and API-compatibility claims with Datascript. If Datalevin is the eventual deployment target, pull it into `reference/` and confirm.

- **Posh internals not verified at the source level**. I described its dependency-graph algorithm from the README and prior knowledge. The exact pattern-extraction code (which file, which function) is UNVERIFIED. If we end up building Shape C, do a proper Posh source read first.

- **Open question**: the agent's tx volume at Phase-1+ is unknown. If the agent is making 10s of small txs per turn (likely once it's writing to the fact graph aggressively), and if there are 50+ listeners at multi-pod scale, Shape A's 250+ no-op callbacks per turn becomes nontrivial GC pressure inside QuickJS. This argues for Shape B at MVP rather than waiting; the cost of going from A to B once we have data is small but the *latency* (a week of "wait, why is the bundle running slow?") is annoying.

- **Open question**: does the agent itself want to register listeners, or only the orchestrator? Architecture spec §3.5 has the agent talking to `globalThis.db.*` synchronously; reactive subscription on the agent side would need an additional capability primitive. **Punted to Phase-2** per spec §11 ("reactor model deferred to Phase 2; MVP uses linear messages + tool-call loop"). When that comes back, we'll want Shape B's signature API exposed as `db.subscribe!` — same router, same shape.

- **Open question — does `core.async.flow` change the answer?** If the orchestrator is already a flow graph, every DB tx-report could be a flow source and downstream nodes filter via standard flow operators (`filter`, `transduce`, etc.). At that point Shape B's router is *literally* a `(filter signature-matches?)` node in the flow graph, and the recommendation collapses to "use what `core.async.flow` already gives you." Worth a 1-hour spike in the orchestrator code before locking the router design.

- **What I'm not unsure about**: Shape A is provably enough for MVP scale. If you ship Shape A and never upgrade, nothing breaks until you have many listeners. The router is an ergonomic upgrade, not a correctness upgrade.

---

## 7. Sources

**Verified at source-code level (in `reference/`)**:
- Datascript: `reference/datascript/src/datascript/conn.cljc` (lines 117-169, 144-146)
- Datahike: `reference/datahike/src/datahike/core.cljc` (lines 206-224), `reference/datahike/src/datahike/writer.cljc` (lines 247, 274), `reference/datahike/src/datahike/connector.cljc` (lines 43-46, 55-57, 84)

**Verified at primary-doc level**:
- DOM MutationObserver: [DOM Living Standard §4.3](https://dom.spec.whatwg.org/#mutationobserver), [MDN MutationObserver](https://developer.mozilla.org/en-US/docs/Web/API/MutationObserver)
- Datomic `tx-report-queue`: [docs.datomic.com](https://docs.datomic.com/clojure/index.html#datomic.api/tx-report-queue)
- re-frame subscriptions: [day8.github.io/re-frame/subscriptions](https://day8.github.io/re-frame/subscriptions/)

**Verified at README / project-page level only**:
- Posh: [github.com/mpdairy/posh](https://github.com/mpdairy/posh)
- Datalevin: [github.com/juji-io/datalevin](https://github.com/juji-io/datalevin)
- chokidar: [github.com/paulmillr/chokidar](https://github.com/paulmillr/chokidar)
- Reagent: [github.com/reagent-project/reagent](https://github.com/reagent-project/reagent)
- Hyperfiddle Electric: [github.com/hyperfiddle/electric](https://github.com/hyperfiddle/electric)
- Missionary: [github.com/leonoel/missionary](https://github.com/leonoel/missionary)

**Architecture spec referenced**:
- `repos/agent/docs/specifications/2026-05-10-agent-runtime-architecture.md` §1 (vision), §3 (pod runtime), §4 (JVM service, esp. §4.3 SSE on :7891), §5 (orchestrator core.async.flow), §11 (reactor model deferred)
