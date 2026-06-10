(ns seon.store.internal.wire-node
  "Plain-Node (NO WASM) UDS transport to the JVM wire-server, built as a
   shadow-cljs `:node-script` runtime you can REPL into over MCP
   (`mcp__seon_cljs__eval`, agent_id \"wire\").

   This is the MISSING transport piece from the CLJ-pivot scoping: the CLJS
   guest client (`seon.client-runtime.db`) routes every datahike op through
   WIT-bound host fns (WASM). The V0 pod is plain Node with NO node:net UDS
   client. This ns is that client — and unlike the throwaway standalone Node
   script, it lives in the project's shadow-cljs node setup, reuses the SAME
   Transit codec (`seon.client-runtime.transit`) the WASM guest uses, and is a
   live, REPL-addressable runtime. It is the prototype the pod's db path will
   later route through (a coordinated `:client` change, NOT done here).

   Protocol (verified against `seon.server.{codec,transit,wire}`):
   - transport: node:net Unix-domain socket, one frame in / one frame out.
   - framing: 4-byte big-endian length + CBOR payload (`seon.store.internal.cbor`).
   - control envelope: CBOR map, string keys.
   - VALUES (query/args/tx-data/selectors/eids/results/tempids/tx-meta/datom
     a,v): Transit-JSON STRINGS (`seon.client-runtime.transit`).
   - multi-DB routing: optional \"agent-id\" / \"db-name\" on any request.

   Stays OUT of the `:client` build (its own `:wire-node` shadow build, its own
   `-main`). Build: `clj -M:cljs watch wire-node`;
   run: `node out/wire-node/main.js`."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            [seon.store.internal.cbor :as cbor]
            ;; reused only so this runtime answers the MCP agent-id probe
            ;; (`(seon.dev.node-agent/agent-id)`) — see set-agent-id! below.
            [seon.dev.node-agent :as node-agent]))

(def ^js net (js/require "node:net"))

;; Transit-JSON value codec. Byte-identical to seon.client-runtime.transit and
;; seon.server.transit (all three are the same two cognitect.transit calls).
;; Used directly here (not via the guest wrapper) so wire-node depends only on
;; namespaces under src/ — the live shadow watcher hosting the pod does NOT
;; have guest-cljs/src on its classpath, and restarting it would disturb the
;; pod runtime. The pod-integration handoff will consolidate onto the guest's
;; transit ns.
(defonce ^:private !writer (atom nil))
(defonce ^:private !reader (atom nil))
(defn- writer [] (or @!writer (reset! !writer (t/writer :json))))
(defn- reader [] (or @!reader (reset! !reader (t/reader :json))))

(def default-req-sock "tmp/seon-cluster-default-req.sock")

;; The agent-id this runtime answers to (so MCP eval can pin it). Mirrors
;; seon.dev.node-agent's resolution contract.
(defonce ^:private !agent-id (atom "wire"))
(defn agent-id [] @!agent-id)

;; ---------- value codec ----------

(defn T [v] (t/write (writer) v))
(defn readT [s]
  (when (and s (not= "" s) (not= "null" s))
    (t/read (reader) s)))

;; ---------- one request / one reply ----------

(defn rpc
  "Open a UDS connection to `sock-path`, send `req` (a CLJS map with string
   keys), read one length-framed CBOR reply, resolve a promise with the decoded
   reply map. Rejects on socket error / timeout / early close."
  ([req] (rpc default-req-sock req {}))
  ([sock-path req] (rpc sock-path req {}))
  ([sock-path req {:keys [timeout-ms] :or {timeout-ms 5000}}]
   (js/Promise.
    (fn [resolve reject]
      (let [sock     (.createConnection net sock-path)
            !need    (atom nil)
            !payload (atom (.alloc cbor/B 0))
            !lenbuf  (atom (.alloc cbor/B 0))
            !settled (atom false)
            timer    (js/setTimeout
                      (fn [] (when-not @!settled
                               (reset! !settled true) (.destroy sock)
                               (reject (js/Error. "wire rpc timeout"))))
                      timeout-ms)
            done     (fn [err val]
                       (when-not @!settled
                         (reset! !settled true)
                         (js/clearTimeout timer)
                         (.end sock)
                         (if err (reject err) (resolve val))))]
        (.on sock "error" (fn [e] (done e nil)))
        (.on sock "connect"
             (fn []
               (let [body  (cbor/encode req)
                     framed (cbor/frame body)]
                 (.write sock framed))))
        (.on sock "data"
             (fn [chunk]
               (if (nil? @!need)
                 (let [^js lb (.concat cbor/B #js [@!lenbuf chunk])]
                   (reset! !lenbuf lb)
                   (when (>= (.-length lb) 4)
                     (reset! !need (.readUInt32BE lb 0))
                     (reset! !payload (.subarray lb 4))))
                 (reset! !payload (.concat cbor/B #js [@!payload chunk])))
               (when (and (some? @!need) (>= (.-length ^js @!payload) @!need))
                 (try
                   (done nil (cbor/decode (.subarray ^js @!payload 0 @!need)))
                   (catch :default e (done e nil))))))
        (.on sock "end"
             (fn [] (when-not @!settled (done (js/Error. "wire closed before reply") nil)))))))))

;; ---------- op surface (mirrors seon.client-runtime.wit / seon.server.wire) ----------

(defn- routed [req {:keys [agent-id db-name]}]
  (cond-> req
    agent-id (assoc "agent-id" agent-id)
    db-name  (assoc "db-name" db-name)))

(defn- then [p f] (.then p f))

(defn ping
  ([] (ping default-req-sock))
  ([sock] (rpc sock {"op" "ping"})))

(defn ensure-db
  "ensure-db a named DB. backend defaults to \"memory\"."
  ([db-name] (ensure-db default-req-sock db-name "memory" nil))
  ([sock db-name backend path]
   (rpc sock (cond-> {"op" "ensure-db" "db-name" db-name "backend" backend}
               path (assoc "path" path)))))

(defn transact
  "Transact tx-data (a CLJS value). Returns a promise of the decoded report map."
  ([tx-data] (transact default-req-sock tx-data {}))
  ([sock tx-data] (transact sock tx-data {}))
  ([sock tx-data {:keys [tx-meta request-id] :as opts}]
   (-> (rpc sock (routed (cond-> {"op" "transact" "tx-data" (T tx-data)
                                  "request-id" (or request-id "")}
                           tx-meta (assoc "tx-meta" (T tx-meta)))
                         opts))
       (then (fn [resp] (if (get resp "ok") (readT (get resp "payload")) resp))))))

(defn q
  "Run a Datalog query. Returns a promise of the decoded result."
  ([query] (q default-req-sock query [] {}))
  ([sock query] (q sock query [] {}))
  ([sock query args] (q sock query args {}))
  ([sock query args opts]
   (-> (rpc sock (routed {"op" "q" "query" (T query) "args" (clj->js (mapv T args))} opts))
       (then (fn [resp] (if (get resp "ok") (readT (get resp "result")) resp))))))

(defn pull
  ([selector eid] (pull default-req-sock selector eid {}))
  ([sock selector eid opts]
   (-> (rpc sock (routed {"op" "pull" "selector" (T selector) "eid" (T eid)} opts))
       (then (fn [resp] (if (get resp "ok") (readT (get resp "result")) resp))))))

(defn schema
  ([] (schema default-req-sock {}))
  ([sock opts]
   (-> (rpc sock (routed {"op" "schema"} opts))
       (then (fn [resp] (if (get resp "ok") (readT (get resp "result")) resp))))))

;; ---------- raw tx feed (subscribe-tx / next-tx-event / unsubscribe-tx) ----------

(defn subscribe-tx
  "Open a raw tx-feed subscription. Returns a promise of the handle (int)."
  ([] (subscribe-tx default-req-sock {}))
  ([sock opts]
   (-> (rpc sock (routed {"op" "subscribe-tx"} opts))
       (then (fn [resp] resp)))))

(defn next-tx-event
  "Poll one raw tx event for `handle`. Resolves to the event map (string keys)
   or {\"ok\" false ... \"error\" \"no-event\"} on the bounded-wait timeout."
  ([handle] (next-tx-event default-req-sock handle))
  ([sock handle] (rpc sock {"op" "next-tx-event" "handle" handle})))

(defn unsubscribe-tx
  ([handle] (unsubscribe-tx default-req-sock handle))
  ([sock handle] (rpc sock {"op" "unsubscribe-tx" "handle" handle})))

;; ---------- main ----------

(defn -main [& args]
  (let [v (vec args)
        id (loop [i 0]
             (cond
               (>= i (count v)) "wire"
               (= "--agent-id" (nth v i)) (get v (inc i))
               :else (recur (inc i))))]
    (reset! !agent-id id)
    ;; also set the node-agent atom so the MCP resolver's probe
    ;; (`(seon.dev.node-agent/agent-id)`) finds THIS runtime by agent-id.
    (node-agent/set-agent-id! id)
    (js/console.log (str "wire-node ready: agent-id=" id
                         " pid=" (.-pid js/process)
                         " req-sock=" default-req-sock))
    ;; idle forever so the process stays a live shadow runtime
    (js/setInterval (fn [] nil) 60000)))
