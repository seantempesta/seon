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

(def default-pub-sock
  "The cluster wire-server's UDS PUBLISH socket — the broadcast stream every
   committed tx event is pushed down (`seon.server.broadcast/start-pub-server!`).
   Cluster-isolation-aware like `default-req-sock`: reads `SEON_PUB_SOCK`
   first (exported by `bin/acme`), falling back to bin/seon's default."
  (or (platform/env-val "SEON_PUB_SOCK")
      "tmp/seon-cluster-default-pub.sock"))

;; ---------- wire timing — the ONE home for every wire timeout/backoff ----------
;; Structural protocol constants, NOT config tunables (owner config-triage:
;; genuinely-tunable → config edge; these values are justified by mechanism —
;; op payload size, boot budgets, event-loop-alive semantics — and nobody
;; tunes them per cluster). Every wire timing value lives HERE; callers
;; (this ns + seon.store.wire) reference these defs, never inline literals.

(def ^:private rpc-tick-ms
  "The rpc timeout's event-loop-alive tick. The budget below accumulates one
   tick per interval FIRE, not per wall-clock elapse — Node coalesces every
   interval fire missed during a synchronous stall into ONE, so blocked-loop
   time costs a single tick instead of expiring the budget."
  250)

(def default-rpc-timeout-ms
  "Default rpc reply budget (event-loop-ALIVE ms — see [[rpc]]) for
   ordinary wire ops."
  5000)

(def replay-timeout-ms
  "Default budget for one bounded `replay-tx` page. [[replay-tx]] also accepts
   a per-call override via opts."
  30000)

(def ping-attempts
  "Boot ping-gate retries before the fail-loud throw (seon.store.wire)."
  5)

(def ping-timeout-ms
  "Per-attempt ping rpc budget for the boot ping gate."
  2000)

(def ping-retry-delay-ms
  "Backoff between boot ping-gate attempts."
  500)

(def ensure-db-timeout-ms
  "`ensure-db` rpc budget — creating a fresh cluster's file store on the
   wire-server can be slow, so it gets more than the default rpc budget."
  15000)

(def transact-timeout-ms
  "Transact rpc budget. Generous: the boot core-index transact carries
   thousands of rows in one tx."
  30000)

(def transact-attempts
  "Maximum same-id deliveries of one frozen transaction after reply loss.
   Every retry is idempotent at the JVM writer; exhausting the bound reports
   an unknown status and never claims that the write did not commit."
  3)

(def feed-reconnect-delay-ms
  "Delay before the tx-feed pub socket schedules ONE reconnect after a
   drop (seon.store.wire/schedule-reconnect!)."
  2000)

;; ---------- one request / one reply ----------

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
  ([sock-path req {:keys [timeout-ms] :or {timeout-ms default-rpc-timeout-ms}}]
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
                                  ;; ex-info (still a js/Error) so the caller can
                                  ;; DISTINGUISH the rpc-layer failure flavor: a
                                  ;; timed-out request MAY have been applied by the
                                  ;; server (the reply, not the request, was lost) —
                                  ;; seon.store.wire's transact path reads this key
                                  ;; to run its commit-or-not check.
                                  (reject (ex-info
                                           (str "wire rpc timeout (alive " @!alive-ms
                                                "ms, wall " (- (js/Date.now) started) "ms)")
                                           {:seon.store.wire/rpc-failure :timeout}))))
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
             (fn [] (when-not @!settled
                      (done (ex-info "wire closed before reply"
                                     {:seon.store.wire/rpc-failure :closed})
                            nil)))))))))

;; ---------- persistent pub-socket subscription (push feed) ----------

(defn connect-pub
  "Open a PERSISTENT connection to the wire-server's pub socket and stream
   every broadcast frame to `on-event`. Resolves (with the socket) once
   connected; rejects if the connection cannot be established. After connect,
   any drop — socket error, close, a frame decode failure, or an `on-event`
   throw — destroys the socket and calls `on-close` exactly ONCE with a reason
   string (the caller owns reconnect + since-t replay).

   The stream is length-framed Transit-JSON (same codec as `rpc`), but unlike
   the one-shot rpc reader this parser is INCREMENTAL: a chunk may carry a
   partial frame or several whole frames; frames are emitted in arrival order."
  [sock-path {:keys [on-event on-close]}]
  (js/Promise.
   (fn [resolve reject]
     (let [sock       (.createConnection net sock-path)
           !connected (atom false)
           !closed    (atom false)
           !buf       (atom (.alloc Buffer 0))
           close!     (fn [reason]
                        (when-not @!closed
                          (reset! !closed true)
                          (try (.destroy sock) (catch :default _))
                          (if @!connected
                            (when on-close (on-close reason))
                            (reject (js/Error. (str "pub connect failed: " reason))))))]
       (.on sock "connect" (fn []
                             (reset! !connected true)
                             (resolve sock)))
       (.on sock "error" (fn [e] (close! (or (.-message e) (str e)))))
       (.on sock "close" (fn [] (close! "socket closed")))
       (.on sock "data"
            (fn [chunk]
              (swap! !buf (fn [^js b] (.concat Buffer #js [b chunk])))
              (loop []
                (let [^js b @!buf]
                  (when (and (not @!closed) (>= (.-length b) 4))
                    (let [need (.readUInt32BE b 0)]
                      (when (>= (.-length b) (+ 4 need))
                        (reset! !buf (.subarray b (+ 4 need)))
                        (let [res (try
                                    {:ev (dec-payload (.subarray b 4 (+ 4 need)))}
                                    (catch :default e
                                      {:err (str "pub frame decode failed: "
                                                 (or (.-message e) (str e)))}))]
                          (if-let [err (:err res)]
                            (close! err)
                            ;; an on-event throw is a FEED failure (e.g. the
                            ;; local store deref lagging the event) — drop the
                            ;; connection so the caller's reconnect + since-t
                            ;; replay recovers it, mirroring the old pump's
                            ;; catch→re-subscribe semantics.
                            (let [derr (try (on-event (:ev res)) nil
                                            (catch :default e
                                              (str "feed handler threw: "
                                                   (or (.-message e) (str e)))))]
                              (if derr (close! derr) (recur))))))))))))))))

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
                                  :seon.store.wire/id (or request-id "")}
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

;; ---------- tx-feed gap recovery (replay-tx) ----------

(defn replay-tx
  "Fetch one bounded page of committed tx events after `:since-t`.

   The first request omits `:through-t` and captures the writer's upper
   watermark. Continuations send the returned upper watermark unchanged. The
   reply carries ascending live-shaped events plus explicit `continuation-t`
   and `done?` facts. Used by the pub-socket feed on every (re)connect; callers
   keep the pub socket open while walking pages. `:since-t` is required."
  ([opts] (replay-tx default-req-sock opts))
  ([sock {:keys [since-t through-t timeout-ms] :as opts}]
   (rpc sock
        (routed (cond-> {:seon.store.wire/op "replay-tx"
                         :seon.store.wire/since-t since-t}
                  (some? through-t)
                  (assoc :seon.store.wire/through-t through-t))
                opts)
        {:timeout-ms (or timeout-ms replay-timeout-ms)})))

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
    ;; infrastructure runtimes share the agent resolver but can never collide
    ;; with core agent ids (the registered agent-id grammar excludes `:`).
    (runtime-id/host! id)
    (js/console.log (str "wire-node ready: id=" id
                         " pid=" (.-pid js/process)
                         " req-sock=" default-req-sock))
    ;; idle forever so the process stays a live shadow runtime
    (js/setInterval (fn [] nil) 60000)))
