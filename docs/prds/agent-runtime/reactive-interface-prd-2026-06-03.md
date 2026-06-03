---
type: prd
status: draft
tags: [prd, agent, database, flow]
---

# Reactive interface — Host ⇄ guest reactive engine (2026-06-03)

> The Host owns datahike and does the routing. A guest registers a standing
> query and goes dormant. On each commit the Host decides — cheaply — which
> subscriptions could be affected, re-runs those queries, and notifies only the
> agents whose result actually moved. The notified agent re-renders its summary
> (in CLJS, where its code lives) and transacts it back. All facts are
> transactions; notifications are ephemeral; effects live at the edges.

Vocabulary is canonical per [glossary](glossary.md): **agent**, **subagent**,
**subscription**, **summary**, **render function**, **reactive engine**,
**notification**, **cluster**, **host**, **guest**, **database**. This PRD
specifies the *interface* between the JVM **Host** (`src/seon/server/`) and the
CLJS **agents** running in **guests** — the wire ops, the entity schema, the
`d/listen!` hook contract, and *how the render is produced*.

It implements [reactive-agent-topology](reactive-agent-topology.md), leans on
[code-as-data-runtime](../../seon/concepts/code-as-data-runtime.md), and plugs
into the seam the platform track is building in
[clusters-and-multi-db-wiring-2026-06-03](clusters-and-multi-db-wiring-2026-06-03.md).
It is the reactive track's Milestones 3–4 made concrete, and goes to the
platform agent for review (see [Questions for the platform track](#10-questions-for-the-platform-track)).

## 1. Overview

The shape of the system this interface serves:

```text
external effects (web, email, MCP, timers, user)
        │  write what they observe
        ▼
   d/transact conn          ; per-conn, serialized by datahike (writer thread)
        │  synchronous, full TxReport
        ▼
   d/listen! callback  ←──── seon.server.reactive/on-tx!   (the reactive engine)
        │   1. route: which subscriptions could this tx touch?  (index + cheap gate)
        │   2. re-run: re-run each candidate's query; keep only really-changed
        │   3. emit: a `changed-summaries` event (+ the raw `tx` event)
        ▼
   broadcast.clj (per-DB routed)  ──►  guests
        │
        ▼  a guest whose OWN subscription is among the changed re-renders its
           summary — by running its render function (a :seon.fn, CLJS) over the
           new query rows — and transacts :seon.render/ai + :seon.render/html
           back as a NEW tx. The Host never writes; the guest does.
```

Everything in this loop is a datom in the database:

- the **agent** entity,
- its **subscription** (the standing query + the derived patterns + a reference
  to its render function),
- its **render function** — a `:seon.fn` entity whose body *is* data
  ([code-as-data-runtime](../../seon/concepts/code-as-data-runtime.md)),
- its **summary** — `:seon.render/ai` + `:seon.render/html` on the agent's own
  entity, the realized output of the render function.

The reactive engine keeps an **in-memory cache** (`{subscription →
{:reload-patterns :results …}}` plus the §3 index) that is *derived* from those
datoms and rebuilt from them on Host restart. Notifications carry no
authoritative information; a dropped one loses nothing because the data is in the
database (recover via basis-t catch-up).

**Dependency stance.** Posh stays a *vendored reference* (`reference-code/posh`),
**not** a classpath dependency (fewer-dependencies rule). We port the one small
hard piece — the e/a/v datom matcher (~15 lines, `datom_matcher.cljc`) — and
write our own where-clause pattern extraction (attribute + literal precision for
MVP, which is the `:simple-patterns` level posh itself uses for `q`) and engine
loop directly against datahike. **No posh on the classpath, no
`clojure.core.match`** (which posh's `q_analyze` pulls in), and **no `dcfg`
indirection** (that abstraction existed only so posh could target datascript *or*
datomic; we are datahike-only and call `datahike.query/q` directly). Posh's
`q_analyze` is consulted as the reference for *entity-precise* pattern derivation
if/when we want tighter-than-attribute routing (a later optimization, §3) — we'd
port that piece too, never depend on the library.

This PRD answers, in order: how the render is produced (§2), how routing scales
(§3, the inverted index), the entity schema (§4), the wire surface (§5), the
`listen!` hook contract (§6), platform fit (§7), milestones (§8), risks (§9), and
open questions for the platform track (§10).

## 2. How the render is produced — render is data

The Host routes cheaply (posh patterns over the tx datoms) and re-runs the
subscription's query (it owns the DB). The open question is only the *last* step:
turning the query rows into the summary values `:seon.render/ai` (text) and
`:seon.render/html` (hiccup).

### 2.1 The output is always data; the producer is a function, also stored as data

Two facts settle the design:

1. **The summary output is always fully-realized, serializable data.**
   `:seon.render/ai` is a string; `:seon.render/html` is a hiccup vector. They are
   datoms (persisted, sent over the wire), so they cannot hold live function
   *objects*. A render value may, however, contain **function references** —
   symbols/keywords naming a function — because a name is data and is resolved at
   display time (reagent-style composition, but with serializable references
   instead of closures). So "static vs dynamic" is not two modes: a render value
   is data that is either a literal (used as-is) or carries references (resolved
   by whoever displays it). This is [code-as-data-runtime](../../seon/concepts/code-as-data-runtime.md).

2. **The producer is a CLJS render function — itself stored as data.** Real
   summaries are compositional: a function that maps over rows and calls helper
   functions that themselves return hiccup (`(for [e rows] (email-row e))`). That
   is computation, i.e. code — it cannot be a fill-in-the-holes template. So the
   render function is CLJS, authored by the agent, and stored as a `:seon.fn`
   entity (its body is data — the same mechanism that persists every function in
   the substrate). The subscription references it.

So there is **no `:data`-vs-`:cljs` kind switch.** The render is a function
reference (the common case) or a literal (the trivial case); both are data; the
runtime that *holds* the referenced function runs it. For an agent's CLJS render
function that runtime is the **guest**. The Host routes and ships the rows; the
guest renders.

### 2.2 Where each step runs

```text
QUERY (routing + produce rows)   → Host (JVM, datahike). Always.
RENDER (rows → ai/html)          → the holder of the render fn:
                                     · agent CLJS render fn  → the guest
                                     · a literal / a Host-resolvable fn → the Host
WRITEBACK (:seon.render/ai,html) → the guest, as a new transaction.
```

The render function is a **pure function of the subscription's query rows**:
`(fn [rows] {:seon.render/ai "…" :seon.render/html […]})`. Keeping it pure-of-the-rows
is what keeps wake-up *sound*: the subscription's query is the complete read-set,
so the patterns derived from it (§3) are exactly what should wake the render. If a
render needs more data, it goes in the query, not in a side-channel read inside
the function.

The two-timescale split from the topology doc still holds, and now sits cleanly on
this:

```text
f_i(D, E)  ⇝  render-fn  (a :seon.fn, pure over rows)   summary = render-fn(rows)
                                          ▲ fast loop: cheap, runs in the guest on change
LLM recompile (rare, expensive) ──────────┘ slow loop: rewrites the render-fn (a new :seon.fn datom)
```

The fast loop re-runs the render function (cheap CLJS in the guest — **not** an
LLM call). The slow loop is the LLM rewriting the render function, which is just
writing a new `:seon.fn` datom. A DB change re-runs the render; it never invokes
the LLM (the query/action split).

### 2.3 Reactivity comes from re-running, not from embedded live functions

Reagent embeds component functions in the tree so the client can re-render
reactively. **We don't need that** — our reactivity is the engine: a tx matches
the subscription's query → the engine wakes the guest → the guest re-runs the
render function → transacts fresh realized hiccup. The stored output stays plain
data; freshness is the engine's job, not the data's. So summaries are normal
datoms the UI and other agents read by plain `pull`/subscription, *and* they are
always current.

### 2.4 The one optimization, named and deferred: Host-side render

When the guest must be **dormant or powered off** (scale — many clusters × many
agents, the experiments world), waking it just to reformat rows is wasteful. The
optimization: the Host resolves and runs renders it *can* — literals, or render
functions simple enough to evaluate JVM-side (a flat `rows → template`
projection). Two ways to get there, both deferred past MVP:

- the Host **auto-lifts** a simple CLJS render function into a JVM-evaluable form
  (analyze the `:seon.fn` body, lift the flat ones); the agent still only ever
  writes CLJS.
- or a render value is already a literal/Host-resolvable reference.

This is **not** a separate authoring surface and **not** an MVP feature. At MVP
scale (one cluster, one database, a few agents on a laptop) guests are alive and
re-render on change; the dormant-guest path is a later, localized addition that
does not change the wire contract (§5) or the hook (§6).

## 3. Performance — the routing index

### 3.1 The posh-as-is cost, read honestly

Posh's engine walks **every registered item** per commit. From
`posh.core/after-transact` → `cache-changes` (`reference-code/posh/src/posh/core.cljc`):

```clojure
(defn cache-changes [{:keys [graph cache] :as posh-tree} db-id tx new-cache storage-key]
  (if (get new-cache storage-key)
    {}
    (let [current-analysis (get cache storage-key)
          reloaded (when (dm/any-datoms-match?
                          (get (:reload-patterns current-analysis) db-id)
                          tx)                         ; ← cheap gate, per item
                     ((:reload-fn current-analysis) posh-tree storage-key))
          ...])))
```

and `after-transact` reduces `cache-changes` across the graph's items. The cheap
gate is `dm/any-datoms-match?` — a pure pattern match, no query run
(`reference-code/posh/src/posh/lib/datom_matcher.cljc`):

```clojure
(defn datom-match-pattern? [pattern datom]
  (if (empty? pattern) true
    (when (let [p (first pattern)]
            (or (= p '_) (and (set? p) (p (first datom))) (= p (first datom))))
      (recur (rest pattern) (rest datom)))))

(defn any-datoms-match? [patterns datoms]
  (case patterns
    nil nil, [] nil, [[]] true
    (some #(datom-match? patterns %) datoms)))
```

So per commit the cost is **O(N subscriptions × P patterns × D tx-datoms)** for
the cheap gate alone, before any re-run. For posh's original setting — one
browser tab, a handful of live reactions — this is nothing. For *our* setting —
one shared database, many agents, each with one or more standing subscriptions,
plus the UI — N grows with the agent population and we scan all of N on every
single commit, including commits that touch attributes no subscription cares
about. **That is the part that does not scale.** It is also wasteful in a
specific, fixable way: the cheap gate's *first* discriminator is almost always
the attribute (`a`), and most subscriptions will be rejected on the attribute
alone.

**Where posh-as-is is genuinely fine:** small N (single-cluster MVP, a handful
of agents). We should not over-engineer Milestone 1 — the headless proof runs
stock-shaped scanning. The index is needed once N grows (multi-agent clusters,
Milestone 6) and we should build it *behind the same engine API* so it is a swap,
not a rewrite.

### 3.2 The fix: an attribute-keyed inverted index over subscriptions

datahike already computes the dual operand of `W(Δ) ∩ I_i` — the *modified
attributes* of a tx — for its own query-cache eviction. From
`datahike.writing/complete-db-update` (`reference-code/datahike/src/datahike/writing.cljc:351`):

```clojure
modified-attrs (into #{}
                     (comp (map :a) (filter some?)
                           (map (fn [a] (if (and rim (number? a)) (get rim a a) a))))
                     tx-data)
(dq/propagate-query-cache old db modified-attrs)
```

and `propagate-query-cache` (`query.cljc:2296`) evicts exactly those cache
entries whose `attrs` overlap `modified-attrs`. **This is in-tree prior art for
attribute-keyed routing — we apply the same idea to subscriptions instead of to
the query cache.** (Convex calls the equivalent component a "subscription
manager.")

**Data structure.** The engine maintains, alongside the posh `cache`:

```clojure
;; attribute → set of subscription-keys whose reload-patterns reference that attr
{:seon.email/subject  #{sub-k1 sub-k7}
 :seon.email/from     #{sub-k1}
 :seon.health/workout #{sub-k4 sub-k9} …}

;; plus an entity bucket: subscriptions whose patterns are attr-free
;; (an entity-only pull, [e _ _]) — checked on any tx touching that entity:
{<eid> #{sub-k2} …}

;; plus a small "match-everything" set: subscriptions with [[]] patterns
;; (rare; e.g. a "watch the whole DB" debug sub). Always candidates.
```

Building it is mechanical: a subscription's `:reload-patterns` is a vector of
`[e a v]` patterns (posh's `patterns-from-eavs`,
`reference-code/posh/src/posh/lib/q_analyze.cljc`). For each pattern, index by
its `a` if `a` is a concrete keyword (the overwhelmingly common case); index by
`e` if `a` is `_` but `e` is concrete; put it in the match-everything set only
if the pattern is `[]`/`[[]]`.

**Per-tx algorithm:**

```text
on-tx!(tx-report):
  modified-attrs ← attrs-of(tx-report)            ; same extraction datahike does
  touched-eids   ← eids-of(tx-report)
  candidates ← (⋃ index[a] for a in modified-attrs)
             ∪ (⋃ entity-index[e] for e in touched-eids)
             ∪ match-everything
  for sub-k in candidates:                         ; NOT all N
     run posh's cheap gate (any-datoms-match?) to confirm  ; index is a
                                                            ; superset filter
     if confirmed: re-run query; keep if really-changed
  emit changed-summaries for the really-changed set
```

**Complexity.** Per-tx cost becomes **O(|modified-attrs| + |candidates| × P × D)**
— proportional to the subscriptions whose attributes the tx actually touched,
*not* to total N. A commit to `:seon.health/workout` never even looks at the
email-watching subscriptions. The index lookup is set-union over small sets;
maintenance is O(P) per subscription on register/unregister. This is the
datahike-`propagate-query-cache` pattern, one level up.

The index is a **superset filter**, not a replacement for the cheap gate: it
narrows N to the candidates that *could* match by attribute; posh's
`any-datoms-match?` then confirms the precise e/a/v match (e.g. an
entity-precise pattern `[123 :seon.email/subject _]` must still confirm the
entity). Soundness: a subscription is missed only if its pattern references an
attribute the tx modified but the index didn't list it — which can't happen
because we index by exactly the pattern's attribute. The match-everything and
entity-index buckets cover attr-free patterns. **No under-match.**

### 3.3 Why not reuse datahike's query cache as the routing table

Tempting (it is already attribute-keyed) but wrong: datahike's cache is keyed by
*query text + db-cache-key* for *result memoization* and is an LRU that evicts
under memory pressure. Our routing table must be *complete and durable* (every
live subscription, never evicted) and keyed by *subscription identity*. They
solve different problems; coupling them would make a subscription silently stop
firing when its query result fell out of the LRU. Keep them separate. (The
topology doc already calls this out: "Do not reuse datahike's internal query
cache as the routing table.")

## 4. The subscription / agent / summary schema (fresh, server-side)

Authored fresh server-side per the 2026-06-03 decision — **not** ported from the
V0 pod. Fully-namespaced Malli, no `:any`, `{:optional true}` for absent fields.
Shared shapes registered once and referenced (the `:seon.db/ref` discipline).

### 4.1 Shared shapes (register once)

```clojure
;; The agent id is one shape, referenced everywhere (promote session.clj's
;; ::agent-id to a shared shape so wire + reactive agree).
(schema/register! :seon.agent/id
  [:string {:min 1 :seon.db/identity true
            :description "Opaque agent id (V2 uses 14-char hex)."}])

(schema/register! :seon.subscription/id
  [:string {:min 1 :seon.db/identity true}])

;; basis-t: datahike's monotonic clock. One shape; reused by sub + summary.
(schema/register! :seon.reactive/basis-t [:int {:min 0}])

;; A datalog query, stored as data (code-as-data). Concrete (a vector); we don't
;; deep-schema the datalog AST — it is validated by being runnable, and the
;; patterns are derived from it. The element schema is the datalog-clause shape
;; (a flagged coarse spot — see §9), NOT bare :any.
(schema/register! :seon.subscription/query [:vector :seon.subscription/clause])
(schema/register! :seon.subscription/clause [:or :keyword :symbol [:vector :any]])

;; e/a/v patterns derived from the query (posh shape).
(schema/register! :seon.subscription/patterns [:vector [:vector :any]])
```

### 4.2 The render function is a code entity (code-as-data)

The producer is a function, stored as data like every function in the substrate
([code-as-data-runtime](../../seon/concepts/code-as-data-runtime.md)). The
subscription references it; the **guest** resolves and runs it. There is **no
`:seon.render/kind` enum** — the render is a function reference (or, trivially, a
literal); the runtime that holds it resolves by shape.

Render functions are **CLJS** (authored by the agent, run in the guest), so they
are owned by the **CLJS-side code-indexing/storage system** — *not* the JVM
`graph`/`render.clj` machinery. They are separate code systems that persist code
entities to the *same shared database*. The JVM Host **stores only the reference**
and **ships rows**; it never resolves or runs the render fn. This is exactly why
the read-only-Host / CLJS-render-default decisions fit — the Host never touches
render code.

```clojure
;; the subscription references the persisted render fn (a code entity in the
;; shared DB, managed by the CLJS code system). The guest resolves + runs it.
(schema/register! :seon.subscription/render-fn :seon.db/ref) ; → the render fn entity

;; the render fn is, by contract, a pure function of the subscription's query
;; rows: (fn [rows] {:seon.render/ai "…" :seon.render/html […]}).
```

A literal-render (the trivial "static" case the user noted) is the degenerate
form: the subscription carries the render value directly instead of a `render-fn`
ref — both are data, and §5.2's event handles them identically (the value is
just shipped or recomputed).

### 4.3 The subscription entity (durable source of truth)

```clojure
(schema/register! :seon.subscription/agent :seon.db/ref)   ; → the owning agent
(schema/register! :seon.subscription/active? :boolean)

(schema/register! :seon.subscription/entity
  [:map
   [:seon.subscription/id        :seon.subscription/id]
   [:seon.subscription/agent     :seon.subscription/agent]
   [:seon.subscription/query     :seon.subscription/query]
   [:seon.subscription/render-fn :seon.subscription/render-fn]
   [:seon.subscription/patterns  {:optional true} :seon.subscription/patterns]
   [:seon.subscription/active?   :boolean]
   [:seon.subscription/basis-t   {:optional true} :seon.reactive/basis-t]])
```

`:patterns` and `:basis-t` are `{:optional true}` because they are *derived* and
*advance over time*: the Host fills `:patterns` when it analyzes the query and
updates `:basis-t` to the commit at which it last re-derived. They are persisted
(so the engine cache is rebuildable on restart) but absent at registration time.

### 4.4 The summary, on the agent's own entity

The summary is NOT a separate entity (per glossary). It is the realized render
output, written by the guest onto the agent:

```clojure
(schema/register! :seon.render/ai :string)         ; AI-text summary (realized)
;; :seon.render/html follows Seon's existing render.clj registration for hiccup
;; (the hiccup-typing concern is tracked there, not re-litigated here — §9).

(schema/register! :seon.agent/entity
  [:map
   [:seon.agent/id     :seon.agent/id]
   [:seon.agent/launched-by {:optional true} :seon.db/ref] ; the subagent relation
   [:seon.render/ai    {:optional true} :seon.render/ai]
   [:seon.render/html  {:optional true} :seon.render/html]])
```

`:seon.agent/launched-by` is the *only* schema distinction between an agent and a
subagent (glossary: "a subagent is an agent with a launched-by relationship").
Subscribing to a subagent = a subscription whose query reads that subagent's
`:seon.render/*` — which are normal datoms, because the guest wrote them back.

### 4.5 Engine cache = derived from these datoms

The in-memory engine cache (`{subscription-key → {:reload-patterns :results …}}`
plus the §3 inverted index) is *derived*. On Host start / `ensure-db`, the engine
queries `[:find … :where [?s :seon.subscription/active? true]]`, re-analyzes each
query against the live db to repopulate `:reload-patterns`/`:results`, and
rebuilds the index. **The bootstrap-from-DB pattern**: nothing in the cache is
authoritative; the datoms are. This is also how it survives a crash — no separate
persistence of engine state.

## 5. The wire surface

New ops/events fitting the existing `wire.clj` `handle-op` multimethod and the
CBOR-envelope / Transit-value contract (`src/seon/server/wire.clj`). Control
envelope is CBOR string-keyed; values are Transit-JSON strings.

### 5.1 `register-subscription` op (guest → Host)

```text
op = "register-subscription"
  "agent-id"     : string          (control)        ; resolves conn via registry
  "subscription" : Transit string  of :seon.subscription/entity
                                    (minus :patterns/:basis-t — Host derives;
                                     :render-fn references a :seon.fn the guest
                                     also transacted, or transacts in the same op)
→ ok {
  "subscription-id" : string
  "basis-t"         : int           ; basis at which patterns were derived
  "patterns"        : Transit string of derived patterns (for guest visibility)
  "rows"            : Transit string of the initial query rows
}
```

Handler: read the entity, analyze the query → patterns, **transact the
subscription as a datom** (it is durable — §4), register it in the engine cache +
inverted index, run the initial query, and return the first rows (the guest does
the initial render + writeback, like any change). The subscription tx itself goes
through the same `d/transact` → `listen!` path (self-consistent: registering a
subscription is a fact).

`unregister-subscription` (op `"unregister-subscription"`, `subscription-id`)
retracts `:seon.subscription/active?` (or the whole entity), drops it from the
cache + index. Lifecycle ("agent with ≥1 subscriber stays active") is a *query*
over `:seon.subscription/active?` datoms, not a separate mechanism.

### 5.2 `changed-summaries` broadcast event (Host → guests) — the second event type

The platform track keeps the raw `tx` event (for own-tx dedup + basis-t
catch-up). This is the *second* event type — the routing win. For the common
case (a CLJS render fn) it is a **wake carrying the new rows**; the guest
re-renders and writes back.

```text
event = "changed-summaries"
  "db-name"  : string            ; the committing conn's db-name (P1 tagging)
  "basis-t"  : int               ; the commit's basis-t  (the pinned snapshot)
  "changed"  : [ {                ; one entry per really-changed subscription
     "agent-id"        : string
     "subscription-id" : string
     "rows"            : Transit string   ; the new query rows (the render input)
     ;; OPTIONAL, only when the Host itself resolved the render (a literal or a
     ;; Host-evaluable fn — the §2.4 optimization, not MVP): the realized output,
     ;; shipped as a cache so the consumer needn't recompute.
     "ai"              : Transit string (optional)
     "html"            : Transit string (optional)
  } ... ]
```

Malli for the event payload (registered, validated at the boundary):

```clojure
(schema/register! :seon.reactive/changed-entry
  [:map
   [:seon.agent/id          :seon.agent/id]
   [:seon.subscription/id   :seon.subscription/id]
   [:seon.reactive/rows     [:vector :any]]              ; the render input
   [:seon.render/ai   {:optional true} :seon.render/ai]  ; Host-resolved (opt)
   [:seon.render/html {:optional true} :any]])           ; hiccup (see §9)

(schema/register! :seon.reactive/changed-summaries-event
  [:map
   [:seon.server.store/db-name :seon.server.store/db-name]
   [:seon.reactive/basis-t     :seon.reactive/basis-t]
   [:seon.reactive/changed     [:vector :seon.reactive/changed-entry]]])
```

**Crucial invariant:** the event carries *already-transacted-or-derivable* data
only. `rows` are the query result (derivable by re-querying at basis-t). `ai`/`html`
appear only when the Host resolved the render (the §2.4 optimization); the durable
copy is always the guest's (or Host's) writeback tx (§5.3). Drop the event and
nothing is lost (basis-t catch-up).

### 5.3 Guest-side contract (how a guest applies it)

A guest holds, per subscription it owns: `{subscription-id → {render-fn,
last-basis-t}}`. On `changed-summaries`:

1. **Dedup against own tx.** If the change was caused by this guest's own
   writeback (it just transacted its own `:seon.render/*`), ignore — recognized
   by `request-id` correlation carried on the raw `tx` event (existing mechanism)
   or by basis-t ≤ its own last writeback. This is why the raw `tx` event is
   kept.
2. **Owner entries (the common case).** The guest's own subscription is among
   `changed` → run its render function (the `:seon.fn`, CLJS) over the entry's
   `rows`, producing realized `:seon.render/ai`/`:seon.render/html`, and transact
   them back onto its agent entity (a new tx, op `"transact"`, `request-id`-tagged
   for step 1). If the Host already shipped `ai`/`html` (§2.4), use them and skip
   the render.
3. **Consumer entries.** A top-level agent subscribed to a *subagent's* summary,
   or the UI: it reads the subagent's `:seon.render/*` (now updated by step 2's
   writeback, which is itself a tx that matched the consumer's subscription) — so
   consumers are driven by the *writeback*, exactly like any other data change.
   Render against the **pinned `basis-t`** so the consumer sees a coherent
   snapshot.

### 5.4 Riding the P1 per-DB broadcast routing

Every event (raw `tx` and `changed-summaries`) carries `db-name` (P1 kills the
hardcoded `"default"` — `wire.clj:232`). `broadcast.clj` routes per-DB (P1).
Guests bound to cluster A never receive cluster B's `changed-summaries`. The
reactive engine emits *into* the same per-DB broadcast the platform track is
building; it adds an event *type*, not a new transport. **This is a strict win
over the current model:** today every subscriber receives every raw datom and
filters client-side; with `changed-summaries` the Host does the pattern routing
once and tells each guest exactly which of *its* subscriptions moved.

## 6. The `listen!` hook contract

The platform track registers one `d/listen!` per conn on `ensure-db` (P1 item 5).
This PRD specifies what runs inside it.

### 6.1 Entrypoint signature

```clojure
(ns seon.server.reactive)

(defn on-tx!
  "The reactive engine's d/listen! callback. Registered once per conn on
   ensure-db. Runs SYNCHRONOUSLY on datahike's writer thread, after commit,
   with the full TxReport. Routes the commit to affected subscriptions, re-runs
   really-changed queries, and emits the changed-summaries event (and lets the
   raw-tx broadcast proceed). READ-ONLY: it must not transact."
  [{:keys [::db-name ::conn ::emit!] :as ctx} ^datahike.db.TxReport tx-report]
  ...)
```

Registration in the P1 hook:

```clojure
;; in ensure-db, per conn:
(d/listen! conn ::reactive
  (fn [tx-report]
    (reactive/on-tx! {::reactive/db-name db-name
                      ::reactive/conn    conn
                      ::reactive/emit!   bcast/broadcast!} tx-report)))
```

The platform track's default hook (raw `broadcast!`) stays under its own key;
`on-tx!` *additionally* emits `changed-summaries`. Both events flow. `on-tx!`
receives the emit fn so it is decoupled from `broadcast.clj` (testable in
isolation — Milestone 1 drives it with a capturing `emit!`).

### 6.2 Read-only invariant (load-bearing, and now natural)

**`on-tx!` MUST NOT transact.** datahike's listeners fire synchronously on the
writer thread inside the commit path
(`reference-code/datahike/src/datahike/writer.cljc:247`):

```clojure
(doseq [[_ callback] (some-> (:listeners (meta connection)) (deref))]
  (callback tx-report))
```

Transacting from inside the callback would re-enter the writer (re-entrancy
hazard) and block the writer thread on the engine's own work. With CLJS-render as
the default this invariant is *natural*, not a tension: **the guest does every
summary writeback**, so the Host never needs to write inside the callback. The
Host's job is purely:

- **route + re-run (pure reads against `:db-after`) + emit.** `:db-after` is a
  realized immutable value; `d/q` over it is synchronous and side-effect-free
  (verified: posh's engine runs synchronously inside the listener;
  `datahike.query/q` is synchronous over a realized db).
- The writeback is a *new* commit by the guest, which itself fires `on-tx!` again
  — and is filtered by §5.3 dedup so it does not cascade (a writeback whose value
  didn't move produces no `changed` entry).

(The §2.4 optimization, where the Host resolves a render itself, is the *only*
case the Host would persist a summary — and even then the write is an enqueued
follow-up tx off the writer thread, never inside the callback. Out of scope for
MVP, which is why the MVP Host is cleanly read-only.)

### 6.3 MVP: synchronous in the hook; async is a later swap

**MVP runs `on-tx!` synchronously in the hook.** Routing (the inverted-index
lookup + cheap-gate confirm) is cheap; re-running the candidate queries is the
only real work, and the expensive part (LLM recompile, and even the render) is
off this path by construction — the Host does not render in the default case, it
emits rows. So the writer thread does bounded work (route + re-query + emit).

If re-querying the candidate set ever gets heavy on a hot attribute, the swap is:
`on-tx!` does *only* routing synchronously (candidate set + cheap gate), hands the
candidates to a **per-conn worker** that re-queries + emits off the writer thread.
**This swap does not change the `on-tx!` signature or the event shapes.** We note
it now so the contract is forward-compatible; we don't build it for MVP.

## 7. Platform-fit review (the live system)

Read of the live `src/seon/server/`:

| Reactive design needs | Live state | Fit |
| --- | --- | --- |
| One `d/listen!` per conn | `listen!` called **nowhere** server-side; broadcast is imperative after commit (`wire.clj:279`) | **Gap — owned by P1 item 5.** Clean: the hook is exactly the seam. |
| Real `db-name` on events | Hardcoded `"db-name" "default"` (`wire.clj:232`) | **Gap — P1 item 3.** `changed-summaries` reuses the same fix. |
| Per-DB broadcast routing | `broadcast.clj` is a process-global subscriber set, no per-DB keying | **Gap — P1 item 4.** `changed-summaries` rides the same routing. |
| Conn resolution by agent-id/db-name | `session.clj` registry exists + tested, **but `wire.clj` doesn't use it** (`handle-op` threads one ambient conn) | **Gap — P1 item 1.** `register-subscription` needs the registry to resolve the conn from `agent-id`. |
| Agent/subscription/render-fn/summary entities | V0-pod-only; nothing server-side | **Gap — schema authored fresh (§4), this PRD.** |
| Transit roundtrip for query/patterns/rows | `wire_props_test.clj` proves keyword/ns-keyword/vector/map/set roundtrip with class preserved | **Fits.** Our payloads are vectors/maps of keywords — covered. |
| Synchronous re-run over `:db-after` | `handle-op "q"` runs `d/q` synchronously over a resolved db; `as-of`/basis-t wired | **Fits.** `on-tx!` uses the same synchronous `d/q`. |
| basis-t catch-up for dropped notifications | `resolve-db-with-basis-t` + `as-of` already in `wire.clj` | **Fits.** A guest that missed a `changed-summaries` re-queries at basis-t. |

**Flags for the platform track:**

1. **`session.clj` → `seon.server.registry` rename must be wired into `wire.clj`,
   not just renamed.** `register-subscription` resolves conn by `agent-id` via the
   registry; we need the renamed, *wired-into-wire.clj* registry (P1 item 1), not
   just the tested-but-unused one. Flagged.
2. **The `::pub-chan nil?` slot in `session.clj` (schema `[:fn nil?]`).** Reserved
   "for Wave 4 broadcast" and *forced nil*. Per-DB broadcast (P1 item 4) will want
   to populate it; the schema currently *forbids* a non-nil value (instrumentation
   would throw). **Flag:** widen `:seon.server.session/pub-chan` when wiring
   broadcast, or the registry and the broadcast routing will fight at the
   instrumentation boundary.
3. **(Resolved by the CLJS-render default.)** The earlier `:data`-writeback vs
   read-only-hook tension is gone: the *guest* does summary writebacks, so the
   Host hook is cleanly read-only with no off-thread-write machinery needed for
   MVP. Listed for the record; no action.

No mismatch with the committed P1 scope or the runbook was found beyond these.
The runbook's "node-side wire client is NEXT TICK" means guest-side subscription
registration + writeback (§5.1/§5.3 from the guest) can't be end-to-end tested
until that client lands; the Host-side engine (§3–§6) and the
`register-subscription` *handler* can be built and tested via the socket-REPL /
direct `handle-op` calls before then.

## 8. Milestones

Aligned to the topology doc's numbering; this PRD owns 3–4 and refines 1.

1. **Headless reactive engine on datahike** (refine of topology M1; ~1 day). A
   `seon.server.reactive` with a ported datom-matcher + our own where-clause
   pattern extraction + engine loop (no posh dependency, no `dcfg` — see §1
   dependency stance), driven by a real `d/listen!` on the JVM Host. Prove: a
   subscription's cheap gate fires *only* on relevant txns; `:changed` only when
   the result actually moves. No LLM, no wire, no render — capturing `emit!`.
   **No index yet** (stock scanning) — establish correctness first.
2. **Prerequisite gaps** = platform P1 (registry wired into `wire.clj`, real
   db-name on events, per-DB broadcast, the `listen!` hook). Owned by platform
   track. **Blocking** for §5 guest-facing ops.
3. **`register-subscription` + `changed-summaries`** (§5). Handler persists the
   subscription datom, derives patterns, registers in the engine; the second
   event type emits really-changed entries with rows. Tested via `handle-op`
   directly + the socket-REPL, matching `wire_props_test.clj`'s style. Keep raw
   `tx` for dedup.
4. **CLJS render + guest writeback (the default render path)** (§2). The guest
   stores its render function as a `:seon.fn`, runs it over the event's `rows` on
   each `changed-summaries`, and transacts `:seon.render/ai`/`:seon.render/html`
   back. The query's patterns are the wake interest. (Gated on the node-side wire
   client per the runbook for full end-to-end; the handler + a Node-driven render
   loop can be proven incrementally.)
5. **Consumers.** A top-level agent subscribes to subagent summaries (their
   `:seon.render/*` datoms); the UI subscribes too. Both are driven by the
   writeback txns, re-rendering against the pinned `basis-t`. Hierarchical
   manifest (one-line per subagent, expand-on-demand = a query) so the top-level
   agent's context doesn't fill.
6. **The inverted index + multi-cluster** (§3.2). Swap stock scanning for the
   attribute-keyed index *behind the same engine API*. Engine-per-conn over the
   registry; shared-database populations. (Index can land earlier if N bites
   sooner — it's a localized swap.)
7. **(Later, scale only) Host-side render / auto-lift** (§2.4). Resolve literal
   and Host-evaluable renders JVM-side so dormant/powered-off guests don't need
   waking. Does not change the wire contract or the hook.

## 9. Risks (honest)

- **Guests must be alive to re-render.** With CLJS-render as the default, a value
  change wakes the guest for a cheap CLJS render (not an LLM call). At MVP scale
  (one cluster, a few agents) that is fine — guests are alive. At large scale the
  dormant-guest concern is real, and the mitigation is §2.4 (Host-side
  render/auto-lift), deferred. The "JVM does the heavy lifting" claim holds
  regardless: the JVM does all the routing + querying (the expensive-at-scale
  part); the guest only formats rows it is handed.
- **Hiccup typing.** `:seon.render/html` and the event's `html`/`rows` carry
  hiccup / heterogeneous datalog rows, which resist a precise Malli schema.
  Seon's existing `render.clj` already registers `:seon.render/html` (coarsely);
  this PRD **reuses that registration rather than introducing a new smell**, and
  the datalog-clause shape (§4.1) is a real `[:or …]`, not bare `:any`. A precise
  hiccup-with-references grammar is its own project; tracked, not blocking.
- **Posh `Datom` seq-access on datahike.** posh's matcher does `(first datom)` /
  `(rest datom)` over datoms; datahike `Datom` is a record with `.-e/.-a/.-v`. The
  matcher needs datahike datoms seq-accessible as `[e a v …]` (or an adapter).
  First-milestone test target. Low risk, unverified until M1.
- **Cascade / quiescence.** A summary writeback wakes a consumer whose writeback
  wakes the UI. The change-gate (`really-changed` only) damps it; a writeback whose
  value didn't move emits nothing. But a *non-deterministic* render (reworded-
  equivalent output from the same rows) reads as changed forever → no quiescence.
  Mitigate: render functions should be deterministic over their rows (pure); the
  topology doc's "compare the render function, not its output" is the deeper fix,
  deferred.
- **Index/cache rebuild on restart.** The cache is rebuilt from subscription
  datoms (§4.5). Re-derive patterns from the *current* db value on rebuild (we
  already do — patterns are `{:optional true}` and recomputed); treat persisted
  `:patterns` as a hint, not authority, so a schema/attribute change between runs
  can't resurrect a stale pattern.
- **Read-only invariant leakage.** Any future "Host resolves a render and writes
  it back" (§2.4) must go off-writer-thread. Guard: `on-tx!`'s `ctx` deliberately
  does **not** include a transact fn — the only way to write is a separate enqueue
  path. Make the absence structural.

## 10. Questions for the platform track

1. **Registry wire-up timing (P1 item 1).** Will `ensure-db` + `handle-op` route
   by `agent-id`/`db-name` through `seon.server.registry` in the same P1 drop that
   adds the `listen!` hook? `register-subscription` (§5.1) is blocked on
   conn-resolution-by-agent-id, not just on the hook existing.

2. **`:seon.server.session/pub-chan` schema (`[:fn nil?]`).** This forbids a
   non-nil channel today (§7 flag 2). When you wire per-DB broadcast (P1 item 4),
   will you widen it, or is broadcast routing living outside the registry entry?
   If the rename + broadcast wiring lands with the `nil?` constraint intact,
   instrumentation will throw the moment anything populates it.

3. **Confirm the UI/consumer read model is plain `pull`/subscription.** With
   CLJS-render, **summaries are always persisted datoms** (`:seon.render/ai`/`html`
   written back by the guest) — so the UI and other agents read them by normal
   `pull`/subscription, and are driven by the writeback tx. We are *not* relying
   on un-persisted, recompute-on-read summaries. Confirm the UI reads
   `:seon.render/*` off the agent entity (we assume yes); if any consumer needs a
   different read model, flag it now.

4. **Own-tx dedup correlation.** §5.3 step 1 dedups a guest's own writeback via
   `request-id` on the raw `tx` event. The `transact` op already threads
   `request-id` (`wire.clj`). Will `changed-summaries` events also carry the
   originating `request-id` so a guest can correlate a `changed` entry to its own
   write, or do we dedup purely on basis-t? Carrying `request-id` is cleaner;
   confirm it fits your event envelope.

5. **One `listen!` key per conn — collision.** We register under key `::reactive`
   (`seon.server.reactive/reactive`). datahike `listen!` is idempotent per key
   (override). Confirm the platform default broadcast hook uses a *different* key
   (e.g. `::raw-broadcast`) so both coexist, rather than the engine overriding the
   raw broadcaster.

## References

- [glossary](glossary.md) — canonical names.
- [reactive-agent-topology](reactive-agent-topology.md) — the design this
  implements (the two-timescale split, the formal model, the engine-host-side
  decision).
- [code-as-data-runtime](../../seon/concepts/code-as-data-runtime.md) — render
  functions and render values are data; the `:seon.fn` mechanism this reuses.
- [clusters-and-multi-db-wiring-2026-06-03](clusters-and-multi-db-wiring-2026-06-03.md)
  — the platform track's P1; this PRD's Milestone 2 + the `listen!` seam.
- [v2-bringup-runbook-2026-06-03](v2-bringup-runbook-2026-06-03.md) — LIVE NOW
  vs NEXT TICK (the node-side wire client gating end-to-end guest tests).
- Live code: `src/seon/server/{wire,broadcast,session}.clj`,
  `test/seon/server/wire_props_test.clj`.
- Posh engine: `reference-code/posh/src/posh/{core,plugin_base}.cljc`,
  `lib/{datom_matcher,q_analyze,update}.cljc`.
- datahike: `reference-code/datahike/src/datahike/{core,query,writer,writing}.cljc`.
