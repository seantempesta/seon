(ns seon.store.internal.wire-node
  "Plain-Node (NO WASM) UDS transport to the JVM wire-server, built as a
   shadow-cljs `:node-script` runtime you can REPL into over MCP
   (`mcp__seon_cljs__eval`, agent_id \"proc:wire\").

   This is the MISSING transport piece from the CLJ-pivot scoping: the CLJS
   guest client (`seon.client-runtime.db`) routes every datahike op through
   WIT-bound host fns (WASM). The V0 pod is plain Node with NO node:net UDS
   client. This ns is that client — and unlike the throwaway standalone Node
   script, it lives in the project's shadow-cljs node setup, reuses the SAME
   Transit codec (`seon.client-runtime.transit`) the WASM guest uses, and is a
   live, REPL-addressable runtime. It is the prototype the pod's db path will
   later route through (a coordinated `:client` change, NOT done here).

   Protocol (verified against `seon.server.{codec,wire}`):
   - transport: node:net Unix-domain socket, one frame in / one frame out.
   - framing: 4-byte big-endian length + Transit-JSON payload.
   - uniform frame: ONE Transit-JSON map, `:seon.store.wire/*` keyword keys
     and NATIVE values (query/args/tx-data/selectors/eids/results/tempids/
     tx-meta/datom a,v) — one encode/decode, no inner Transit strings.
   - multi-DB routing: optional :seon.store.wire/agent-id / db-name on any req.

   Stays OUT of the `:client` build (its own `:wire-node` shadow build, its own
   `-main`). Build: `clj -M:cljs watch wire-node`;
   run: `node out/wire-node/main.js`."
  (:require [cognitect.transit :as t]
            ;; canonical env reader (zero-require host-sniff ns) — used to make
            ;; `default-req-sock` cluster-isolation-aware (SEON_REQ_SOCK).
            [seon.platform :as platform]
            ;; MCP runtime-addressing probe — this runtime answers
            ;; `(seon.dev.runtime-id/hosted)` with [\"proc:<name>\"]
            ;; (default \"proc:wire\"; the `proc:` grammar marks non-agent
            ;; infrastructure runtimes — mcp-agent-id-unification PRD §2.1).
            ;; Otherwise transit/cbor-only by design; the slim :wire-node build
            ;; stays minimal.
            [seon.dev.runtime-id :as runtime-id]))

(def ^js net (js/require "node:net"))
(def ^js Buffer (.-Buffer (js/require "node:buffer")))

;; Transit-JSON codec for the WHOLE uniform frame (envelope + native values).
;; Byte-compatible with seon.server.codec (transit-clj :json). The
;; writer/reader are memoized + safe to reuse: transit-js clears its per-message
;; cache at the end of every write/read, and the pod is single-threaded
;; (^:async/await, no worker threads). Used directly here (not via a guest
;; wrapper) so wire-node depends only on namespaces under src/.
(defonce ^:private !writer (atom nil))
(defonce ^:private !reader (atom nil))
(defn- writer [] (or @!writer (reset! !writer (t/writer :json))))
(defn- reader [] (or @!reader (reset! !reader (t/reader :json))))

;; ---------- uniform Transit-JSON frame codec + length framing ----------

(defn enc-frame
  "Encode a CLJS map to a length-framed Transit-JSON Buffer: 4-byte big-endian
   length header + the Transit-JSON UTF-8 bytes."
  ^js [m]
  (let [payload (.from Buffer ^String (t/write (writer) m) "utf-8")
        header  (.alloc Buffer 4)]
    (.writeUInt32BE header (.-length payload) 0)
    (.concat Buffer #js [header payload])))

(defn dec-payload
  "Decode a Transit-JSON payload Buffer (no length header) into a CLJS map with
   `:seon.store.wire/*` keyword keys + native values."
  [^js buf]
  (t/read (reader) (.toString buf "utf-8")))

(def default-req-sock
  "The cluster wire-server's UDS request socket — the default `sock-path`
   for EVERY wire op below. Cluster-isolation-aware: reads `SEON_REQ_SOCK`
   from the pod's environment first (set+exported by an isolated launcher
   like `bin/acme`), falling back to the live-default constant when the env
   var is unset/blank. Under the default deployment (`bin/seon`, which does
   NOT export this var) it resolves byte-identically to the old constant.
   Mirrors bin/seon's `SEON_REQ_SOCK` default + the wire-server's --req-sock."
  (or (platform/env-val "SEON_REQ_SOCK")
      "tmp/seon-cluster-default-req.sock"))

;; ---------- one request / one reply ----------

(def ^:private rpc-tick-ms
  "The rpc timeout's event-loop-alive tick. The budget below accumulates one
   tick per interval FIRE, not per wall-clock elapse — Node coalesces every
   interval fire missed during a synchronous stall into ONE, so blocked-loop
   time costs a single tick instead of expiring the budget."
  250)

(defn rpc
  "Open a UDS connection to `sock-path`, send `req` (a CLJS map with
   `:seon.store.wire/*` keyword keys + native values), read one length-framed
   Transit-JSON reply, resolve a promise with the decoded reply map. Rejects on
   socket error / timeout / early close.

   `timeout-ms` is measured in event-loop-ALIVE time, not wall time: the pod is
   single-threaded, and a long synchronous stall (self-host seed eval,
   instrumentation, an agent-turn compile) used to expire the wall-clock timer
   in Node's timers phase BEFORE the poll phase could deliver a reply already
   sitting in the socket buffer — a spurious timeout that killed the tx-feed
   pump on every heavy pod window. A stall now extends the deadline by its own
   duration (coalesced interval, one tick), while a genuinely absent reply
   still rejects after ~timeout-ms of live loop."
  ([req] (rpc default-req-sock req {}))
  ([sock-path req] (rpc sock-path req {}))
  ([sock-path req {:keys [timeout-ms] :or {timeout-ms 5000}}]
   (js/Promise.
    (fn [resolve reject]
      (let [sock     (.createConnection net sock-path)
            !need    (atom nil)
            !payload (atom (.alloc Buffer 0))
            !lenbuf  (atom (.alloc Buffer 0))
            !settled (atom false)
            !alive-ms (atom 0)
            started  (js/Date.now)
            !timer   (atom nil)
            _        (reset! !timer
                             (js/setInterval
                              (fn []
                                (when (and (not @!settled)
                                           (>= (swap! !alive-ms + rpc-tick-ms) timeout-ms))
                                  (reset! !settled true)
                                  (js/clearInterval @!timer)
                                  (.destroy sock)
                                  (reject (js/Error.
                                           (str "wire rpc timeout (alive " @!alive-ms
                                                "ms, wall " (- (js/Date.now) started) "ms)")))))
                              rpc-tick-ms))
            done     (fn [err val]
                       (when-not @!settled
                         (reset! !settled true)
                         (js/clearInterval @!timer)
                         (.end sock)
                         (if err (reject err) (resolve val))))]
        (.on sock "error" (fn [e] (done e nil)))
        (.on sock "connect"
             (fn [] (.write sock (enc-frame req))))
        (.on sock "data"
             (fn [chunk]
               (if (nil? @!need)
                 (let [^js lb (.concat Buffer #js [@!lenbuf chunk])]
                   (reset! !lenbuf lb)
                   (when (>= (.-length lb) 4)
                     (reset! !need (.readUInt32BE lb 0))
                     (reset! !payload (.subarray lb 4))))
                 (reset! !payload (.concat Buffer #js [@!payload chunk])))
               (when (and (some? @!need) (>= (.-length ^js @!payload) @!need))
                 (try
                   (done nil (dec-payload (.subarray ^js @!payload 0 @!need)))
                   (catch :default e (done e nil))))))
        (.on sock "end"
             (fn [] (when-not @!settled (done (js/Error. "wire closed before reply") nil)))))))))

;; ---------- op surface (mirrors seon.server.wire) ----------

(defn- routed [req {:keys [agent-id db-name]}]
  (cond-> req
    agent-id (assoc :seon.store.wire/agent-id agent-id)
    db-name  (assoc :seon.store.wire/db-name db-name)))

(defn- then [p f] (.then p f))

(defn ping
  ([] (ping default-req-sock))
  ([sock] (rpc sock {:seon.store.wire/op "ping"})))

(defn ensure-db
  "ensure-db a named DB. backend defaults to \"memory\"."
  ([db-name] (ensure-db default-req-sock db-name "memory" nil))
  ([sock db-name backend path]
   (rpc sock (cond-> {:seon.store.wire/op "ensure-db"
                      :seon.store.wire/db-name db-name
                      :seon.store.wire/backend backend}
               path (assoc :seon.store.wire/path path)))))

(defn transact
  "Transact tx-data (a CLJS value). Returns a promise of the decoded report map."
  ([tx-data] (transact default-req-sock tx-data {}))
  ([sock tx-data] (transact sock tx-data {}))
  ([sock tx-data {:keys [tx-meta request-id] :as opts}]
   (-> (rpc sock (routed (cond-> {:seon.store.wire/op "transact"
                                  :seon.store.wire/tx-data tx-data
                                  :seon.store.wire/write-id (or request-id "")}
                           tx-meta (assoc :seon.store.wire/tx-meta tx-meta))
                         opts))
       (then (fn [resp] resp)))))

(defn q
  "Run a Datalog query. Returns a promise of the decoded result."
  ([query] (q default-req-sock query [] {}))
  ([sock query] (q sock query [] {}))
  ([sock query args] (q sock query args {}))
  ([sock query args opts]
   (-> (rpc sock (routed {:seon.store.wire/op "q"
                          :seon.store.wire/query query
                          :seon.store.wire/args (vec args)} opts))
       (then (fn [resp] (if (:seon.store.wire/ok resp) (:seon.store.wire/result resp) resp))))))

(defn pull
  ([selector eid] (pull default-req-sock selector eid {}))
  ([sock selector eid opts]
   (-> (rpc sock (routed {:seon.store.wire/op "pull"
                          :seon.store.wire/selector selector
                          :seon.store.wire/eid eid} opts))
       (then (fn [resp] (if (:seon.store.wire/ok resp) (:seon.store.wire/result resp) resp))))))

(defn schema
  ([] (schema default-req-sock {}))
  ([sock opts]
   (-> (rpc sock (routed {:seon.store.wire/op "schema"} opts))
       (then (fn [resp] (if (:seon.store.wire/ok resp) (:seon.store.wire/result resp) resp))))))

(defn knn-search
  "Embedding KNN over the wire (P2-C). The pod sends the NL `query` TEXT (the
   wire-server prepends the retrieval instruction + embeds via Gemini, both of
   which live on the JVM, never the pod) and `k`; `eids` is an OPTIONAL
   type-scope (a coll of entity-ids the wire-server restricts KNN to). Resolves
   to the decoded hits vector `[{:seon.embed/eid e :seon.embed/distance d} …]`
   (distance-ascending), or the raw not-ok envelope on error."
  ([query k] (knn-search default-req-sock query k nil {}))
  ([sock query k eids opts]
   (-> (rpc sock (routed (cond-> {:seon.store.wire/op "knn-search"
                                  :seon.store.wire/query query
                                  :seon.store.wire/k k}
                           (seq eids) (assoc :seon.store.wire/eids (vec eids)))
                         opts))
       (then (fn [resp] (if (:seon.store.wire/ok resp) (:seon.store.wire/result resp) resp))))))

;; ---------- raw tx feed (subscribe-tx / next-tx-event / unsubscribe-tx) ----------

(defn subscribe-tx
  "Open a raw tx-feed subscription. `opts` may carry `:since-t` (a basis-t):
   when present the wire-server replays every committed tx with basis-t >
   since-t — in commit order, ahead of live events — so a RECONNECTING
   subscriber recovers the gap instead of dropping a wake (DE-2). A fresh
   subscriber omits it. Returns a promise of the reply map (carries
   :seon.store.wire/ok + :seon.store.wire/handle, plus :seon.store.wire/replayed
   when a since-t replay ran)."
  ([] (subscribe-tx default-req-sock {}))
  ([sock opts]
   (-> (rpc sock (routed (cond-> {:seon.store.wire/op "subscribe-tx"}
                           (:since-t opts) (assoc :seon.store.wire/since-t (:since-t opts)))
                         opts))
       (then (fn [resp] resp)))))

(defn next-tx-event
  "Poll one raw tx event for `handle`. Resolves to the event map (keyword keys)
   or a not-ok map with :seon.store.wire/error \"no-event\" on the bounded-wait
   timeout."
  ([handle] (next-tx-event default-req-sock handle))
  ([sock handle] (rpc sock {:seon.store.wire/op "next-tx-event"
                            :seon.store.wire/handle handle})))

(defn unsubscribe-tx
  ([handle] (unsubscribe-tx default-req-sock handle))
  ([sock handle] (rpc sock {:seon.store.wire/op "unsubscribe-tx"
                            :seon.store.wire/handle handle})))

;; ---------- main ----------

(defn -main [& args]
  (let [v    (vec args)
        name (loop [i 0]
               (cond
                 (>= i (count v)) "wire"
                 (= "--process-name" (nth v i)) (or (get v (inc i)) "wire")
                 :else (recur (inc i))))
        id   (str "proc:" name)]
    ;; Answer the MCP probe under the `proc:<name>` grammar — non-agent
    ;; infrastructure runtimes share the agent resolver but can never
    ;; collide with core agent ids (new-id! never emits `:`).
    (runtime-id/host! id)
    (js/console.log (str "wire-node ready: id=" id
                         " pid=" (.-pid js/process)
                         " req-sock=" default-req-sock))
    ;; idle forever so the process stays a live shadow runtime
    (js/setInterval (fn [] nil) 60000)))
