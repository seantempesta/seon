---
type: research
status: active
tags: [research, flow, architecture, agent]
---

# Spec-rewrite cluster: Flow + Transport

## Findings

### Q1. harness.bridge transport-agnosticism

The bridge `transform` (arity-4 step) is transport-agnostic — it operates on
deserialized Clojure maps tagged with `:seon.flow.in/request` /
`:seon.flow.in/reply` and emits `:seon.flow.out/reply` /
`:seon.flow.out/request` (see `src/seon/flow/harness/bridge.clj:194-255`).
The transport assumption lives one layer down in
`seon.flow.harness.channel`: length-prefixed Nippy framing in
`read-message!` / `write-message!`
(`src/seon/flow/harness/channel.clj:27-42`) wired to in/out
core.async channels via `wire-socket!`
(`src/seon/flow/harness/channel.clj:81-94`). The bridge step is also handed
an `execute-local` that round-trips the result through Nippy to assert
serializability (`bridge.clj:155-170`) — that check is Nippy-specific and
would need to be parameterized for a non-Nippy transport, or relaxed to
"caller-supplied predicate."

A `seon.flow.harness.websocket` sibling would need to expose the same
shape: `start-server!` / `connect!` returning `{::in-ch ::out-ch ::close!}`
of Clojure values. Frame-on-the-wire becomes Transit-msgpack (or fressian)
instead of Nippy. The Nippy round-trip in `execute-local` should be
swapped for the transport's own serializer or made injectable via the
init args map. Refactor is shallow: ~30 LoC in `bridge.clj` plus a new
~150-LoC `websocket.clj` peer to `channel.clj`. `harness.clj` and
`topology.clj` need no changes — they consume the channels uniformly.

### Q2. core.async.flow API + seon's usage

Seon uses the multi-arity step-fn convention. `flow/process` takes a var
of a 0/1/2/4-arity fn (`harness.clj:96-168`, `topology.clj:42-76`):

- **0-ary (describe):** returns `{:ins {<in-id> "desc"} :outs {<out-id> "desc"} :params {<k> "desc"} :workload :io|:compute}`.
- **1-ary (init):** receives the args map merged from `flow/create-flow`'s `:args`, returns initial state. May embed `:clojure.core.async.flow/in-ports` and `:clojure.core.async.flow/out-ports` (raw channels) into state (`harness.clj:107-118`) — that's how external transports are bound.
- **2-ary (transition):** `[state ::flow/resume|::flow/pause|::flow/stop]` → state. Used for cleanup (`bridge.clj:224-236` cancels pending promises on `::flow/stop`).
- **4-ary (transform):** `[state input-id msg] → [state' {out-id [msg ...]}]`. Outputs are vectors per port id; `nil` means no emission (`bridge.clj:239-255`).

`flow/create-flow {:procs ... :conns ...}` builds the topology, `flow/start`
hands back `{:report-chan :error-chan}`, `flow/resume` runs it, `flow/ping`
is the start barrier (`topology.clj:439-443`). `flow/inject [pid in-port]
[msgs]` is the entry point for sync request/reply
(`topology.clj:212`). Reply delivery is via the reply-router step plus a
global `pending-promises` atom keyed by `::msg/id`
(`topology.clj:123-168`). `seon.flow.topology/build-infrastructure!`
(`topology.clj:595-661`) is the canonical example: it stitches
`:seon.flow/repl`, `:seon.flow/reply-router`, two sinks, and a status
collector into one flow.

**Agent-step verdict:** the snippet from spec-02 §E.5 is a plain Clojure
fn, not a flow step. To run inside the flow it needs the standard
arities and to route inputs through `:seon.flow.in/tick` (or similar) and
emit follow-up effects (LLM-result events, DB writes) as outs rather than
side-effecting inline. Minimum changes:

```clojure
(defn agent-step
  ([] {:ins  {:seon.flow.in/tick "Driver ticks"
              :seon.flow.in/llm-reply "Async LLM completions"}
       :outs {:seon.flow.out/llm-request "Outbound LLM calls"
              :seon.flow.out/tx          "Datahike writes (to tx-bus)"
              :seon.flow.out/event       "Trace events"}
       :params {::user-id "User namespace this agent serves"}
       :workload :io})
  ([{::keys [user-id] :as args}] {::user-id user-id ::last-tick nil})
  ([state _transition] state)
  ([state input-id msg]
   (case input-id
     :seon.flow.in/tick      (handle-tick state msg)
     :seon.flow.in/llm-reply (handle-llm-reply state msg)
     [state nil])))
```

`build-ctx` becomes a query against `seon.db/query` (request!-routed),
`llm/complete` becomes a `:seon.flow.out/llm-request` emission with a
correlation id, `process-llm-response` becomes a `:seon.flow.out/tx`
emission that fans into the existing datahike-flow tx pipeline.

### Q3. datahike-cljs writer pluggability

Datahike's writer is a `defmulti` keyed on `:backend` and a `PWriter`
protocol (`reference/datahike/src/datahike/writer.cljc:15-18`). The
protocol is three methods: `-dispatch! [_ arg-map]` returning a promise-chan
that resolves with a tx-report, `-shutdown [_]`, `-streaming? [_]`. New
backends register via `(defmethod writer/create-writer :relay ...)` returning
anything that satisfies `PWriter`
(`reference/datahike/src/datahike/writer.cljc:158-181` for the built-in
`:self`).

Kabel already does exactly what seon needs for the relay case
(`reference/datahike/src-kabel/datahike/kabel/writer.cljc:71-153`):
`KabelWriter`'s `-dispatch!` packages `{:op :args}` (lines 82-92), sends
via `ds/invoke-remote` to a peer-id, awaits the remote's reconstructed
tx-report, and serves a local mirror through konserve-sync on `on-db-sync!`
(lines 190-238). The wire is replikativ's `distributed-scope` (kabel) —
which is WebSocket-backed and serializes via fressian handlers
(`src-kabel/datahike/kabel/fressian_handlers.cljc`).

**Verdict.** Two paths, both viable:
1. **Use `:writer :kabel` directly.** The pod becomes a replikativ peer
   bonded to the JVM master peer-id. Pro: zero new code, sync semantics
   already worked out. Con: pulls replikativ + fressian + distributed-scope
   onto the pod and pegs us to their wire format.
2. **`:writer :relay` sibling.** Mimic KabelWriter but send the arg-map
   over our own WebSocket leg (Transit-msgpack), and bridge the
   tx-report back through seon's flow substrate. ~150 LoC plus the
   tx-report reconstruction path (deferred indexes etc., per kabel's
   `reconstruct-tx-report`). Pro: keeps the wire under our control. Con:
   we re-derive the sync coordination.

Recommend (1) for first cut, downgrade to (2) only if kabel's stack
proves too heavy at the pod boundary.

### Q4. Malli bridge serializer/deserializer

`malli-map->datahike-schema` honors `:seon.db/value-type` *only* for `:or`
schemas (`src/seon/db/datahike/schema.clj:186-198`). For `:vector` / `:set`
it walks into the inner schema and hard-rejects any nested collection or
map (`schema.clj:150-162`), so a `[:vector :seon.agent.ctx/entry]` whose
entry is `[:map ...]` blows up. No serializer/deserializer hook exists
anywhere in `seon.db.clj` or `seon.db.datahike.schema`. `db/transact!`
validates against the registered Malli schema (`db.clj:111-126`,
`db.clj:255-290`) and hands the raw value to the conn-process — no
encode/decode boundary.

**Minimal extension** (3 touchpoints, ~25 LoC):

1. In `schema.clj`, extend the `(:vector :set)` branch to accept a
   `[:vector {:seon.db/value-type :db.type/string
              :seon.db/serializer 'clojure.core/pr-str
              :seon.db/deserializer 'clojure.edn/read-string}
     :seon.agent.ctx/entry]`: if `:seon.db/value-type` is present in the
   outer entry-props, skip the inner-schema walk and emit
   `{:db/valueType <vt> :db/cardinality :db.cardinality/many}`. Store the
   `:seon.db/serializer` / `:seon.db/deserializer` symbols on the
   attribute's Malli registry entry (Malli properties carry through
   `(m/properties schema)`).
2. In `db.clj/transact!`, between `validate-values!` and `dh-request!`,
   walk tx-data: for each entity, for each attr present, if the Malli
   schema has `:seon.db/serializer`, `(requiring-resolve)` the symbol and
   apply it to the value (mapv for cardinality-many). Replace the value
   in the entity before dispatch.
3. In `db.clj/query` and `db.clj/pull-*`, decode on read: after the
   conn-process returns, walk results and apply `:seon.db/deserializer`
   to attrs that declare one.

The Malli value-validator runs *before* serialization (so it validates the
rich Clojure value, not the pr-str'd string), which is the right shape.
This pattern doesn't need to touch the conn-process or core datahike at
all — encode/decode is purely a `seon.db` boundary concern.

## Draft spec section — "Flow substrate & transport"

> Drop into spec-01 as a top-level section after the storage-authority
> framing.

Seon's runtime is uniform: an agent loop is a `core.async.flow` process,
and so is everything it talks to. The flow substrate is the **only**
cross-namespace, cross-runtime call path. Whether the agent is in the
master JVM, an agent JVM, or a Node pod with a primed QuickJS context,
the same step-fn shape runs and the same envelopes traverse the wire.

### Two flow primitives

**Sync request/reply.** `seon.flow.topology/request!` registers a promise
keyed by request-id, `flow/inject`s a `::msg/request` envelope into a
target process's `:seon.flow.in/request`, and blocks until the
`seon.flow/reply-router` delivers the matched `::msg/reply` to the promise
(`src/seon/flow/topology.clj:174-239`). Timeouts, overload, and execution
errors all return through the same channel as typed status codes — no
exception leaks across the boundary.

**Async tx-bus.** `seon.db.datahike.flow` owns one conn-process per
db-name, each emitting `::tx-report` to a fan-out `tx-bus` step
(`src/seon/db/datahike/flow.clj:1-40`). Subscribers register via
`::sub` and receive the live tx-report from their owning namespace — the
mechanism by which an agent reacts to writes from its own UI handler or
from another agent in the same trust namespace.

### Three transport legs

The substrate stays the same; only the channel adapter changes.

1. **In-process.** Caller and callee are both step-fns in the same flow.
   `flow/inject` does the routing; no serialization. Used for the master
   JVM's own namespaces.
2. **JVM → agent JVM (TCP + Nippy).** `seon.flow.harness.channel` opens a
   loopback TCP socket and frames messages as `4-byte length + Nippy
   bytes` (`src/seon/flow/harness/channel.clj:27-42`). The orchestrator's
   `namespace-step` (`src/seon/flow/harness.clj:77-168`) tracks pending
   count, forwards `::msg/request` envelopes as `:seon.flow.out/jvm-request`
   onto the socket's out-ch, and routes returning `:seon.flow.in/jvm-reply`
   back to its `:seon.flow.out/reply`. The agent JVM runs
   `seon.flow.harness.bridge` as a mirror step that consumes the same
   envelope type, resolves the var, and returns. Nippy is convenient
   because both sides are JVMs.
3. **JVM → pod (WebSocket + Transit-msgpack).** New transport sibling.
   `seon.flow.harness.websocket` exposes the same surface as
   `harness.channel` (`start-server!` / `connect!` → `{::in-ch ::out-ch
   ::close!}` of Clojure values). The pod side replaces Nippy framing
   with Transit-msgpack (read+write handlers for keywords, uuids,
   timestamps; values are pure-edn enough that no JVM-specific types
   cross the wire). The orchestrator-side `namespace-step` is unchanged;
   the bridge step's Nippy serializability check moves behind an
   injectable predicate or is dropped on the pod path because Transit
   already enforces a clean type domain.

### Storage from the pod

The pod runs a datahike-cljs replica of the user's namespace. Its writer
is `:writer :kabel` (`reference/datahike/src-kabel/datahike/kabel/writer.cljc`),
bonded to the master JVM's peer-id. Each `(d/transact ...)` on the pod's
local `*conn*` packages `{:op 'transact! :args [...]}` and sends through
the same WebSocket leg used by the agent-call substrate (one connection,
multiplexed by message type). The master applies the tx, returns a
tx-report, and konserve-sync streams the new db state back into the pod's
local store. The pod's reads stay local-fast; writes are master-authoritative.

If kabel's footprint at the pod boundary proves too heavy, a `:writer
:relay` sibling — same `PWriter` protocol
(`reference/datahike/src/datahike/writer.cljc:15-18`), our own envelope
shape, manual tx-report reconstruction — is ~150 LoC.

### Agent loop as a flow process

The agent step-fn from spec-02 §E.5 fits this model once it's reshaped
into the multi-arity convention seon uses everywhere
(`src/seon/flow/harness.clj:77-168` is the canonical template):

```clojure
(defn agent-step
  ([] {:ins  {:seon.flow.in/tick      "Driver ticks (timer or DB-reaction)"
              :seon.flow.in/llm-reply "Async LLM completions"}
       :outs {:seon.flow.out/llm-request "Outbound LLM calls"
              :seon.flow.out/tx          "Datahike writes (to tx-bus)"
              :seon.flow.out/event       "Trace events"}
       :params {::user-id "User namespace this agent serves"}
       :workload :io})
  ([{::keys [user-id]}] {::user-id user-id ::pending-llm nil})
  ([state _transition] state)
  ([state input-id msg]
   (case input-id
     :seon.flow.in/tick
     (let [ctx        (build-ctx-via-query (::user-id state))
           system-msg (render-system-message ctx)
           recent     (:seon.agent.ctx/recent-messages ctx [])
           req-id     (random-uuid)]
       [(assoc state ::pending-llm req-id)
        {:seon.flow.out/llm-request
         [{::msg/id req-id
           :messages (concat [{:role "system" :content system-msg}] recent)}]}])

     :seon.flow.in/llm-reply
     (let [tx (llm-response->tx (::user-id state) msg)]
       [(assoc state ::pending-llm nil)
        {:seon.flow.out/tx [tx]}])

     [state nil])))
```

Inputs come on `:seon.flow.in/*`, outputs leave on `:seon.flow.out/*`,
state is pure data. Build-ctx is a `request!` to the user's namespace;
the LLM call is a `request!` to the LLM provider's namespace; the DB
write is an emission to `tx-bus`. Same code in the master JVM (where
build-ctx is in-process) or in a pod (where it crosses WebSocket). The
flow substrate doesn't know or care.

### Open questions

- Transit handler set for tx-bus payloads from pod-side subscribers
  (unverified: whether datahike's `:tx-data` survives a Transit
  round-trip cleanly without custom datom handlers).
- Whether `:writer :kabel` requires the konserve store on the pod to
  carry the full store config (peer-id, store-id) or just a passthrough
  shim. Kabel's writer.cljc:281-285 suggests the former.
- Whether the Nippy round-trip serializability assertion in
  `execute-local` (bridge.clj:155-170) should be transport-injectable or
  dropped on the WebSocket leg.
