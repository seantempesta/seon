(ns seon.web.serve
  "Pod-side HTTP+SSE server on a loopback ephemeral port.

   Per spec-05 §10.2 A-5 + §21.1 the pod hosts its own minimal HTTP
   surface so a browser (Chrome in Lane A dev, Tauri WebView in Lane B
   prod) can reach the agent UI without intermediate infrastructure.

   Routes (V0.5):
     GET  /                  → root.s agent view (seeded :seon.route/root → datastar)
     GET  /css/output.css    → resources/public/css/output.css
     GET  /js/datastar.js    → resources/public/js/datastar.js
     POST /chat              → A-8 (user message → message! with from = the user ref)

   ## Port discovery

   `start!` listens on a fixed port (default 7890, override via
   `SEON_PORT`; set to 0 for ephemeral allocation) and writes the
   actually-bound port to `$SEON_PORT_FILE` (default
   `tmp/seon-port` — project-local per CLAUDE.md). External tooling
   reads this file rather than
   parsing logs. Live views use the one normalized gzip feed registry in
   `seon.web.datastar`."
  (:require
    ["node:http" :as http]
    ["node:fs" :as fs]
    ["node:path" :as path]
    [cljs.reader :as reader]
    [clojure.string :as str]
    [goog.object :as gobj]
    [my.blob :as blob]
    [seon.agent :as agent]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.agent.debug :as agent-debug]
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.run :as run]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.derive :as derive]
    [seon.eval :as seval]
    [seon.log :as log]
    [seon.platform :as platform]
    [seon.repl :as repl]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]
    [seon.web.datastar :as datastar]
    [seon.web.router :as router]))

;; ============================================================
;; Process-lifetime state
;; ============================================================

(defonce ^{:doc "The bound HTTP server, or nil before start!."}
  !server (atom nil))

;; ============================================================
;; Agent creation — POST /agents
;;
;; `seon.agent/start!` is the one birth→resume transition for both web and
;; programmatic callers. The route requires the domain owner directly; no
;; client boot callback, creation lock, or second mint seam exists.
;; ============================================================

;; ============================================================
;; Static serving
;;
;; Map URL prefix → disk root. The roots are seon BUILD ARTIFACTS, so
;; they resolve through `seon.platform/artifact-path`: CWD-relative
;; when the pod runs from the seon repo root (today's usage), under
;; SEON_RUNTIME_ROOT when a downstream pod runs from its own runtime root.
;; ============================================================

(def ^:private static-roots
  {"/css/" (platform/artifact-path "resources/public/css/")
   "/js/"  (platform/artifact-path "resources/public/js/")})

(defn- mime-type [filename]
  (cond
    (str/ends-with? filename ".css")  "text/css; charset=utf-8"
    (str/ends-with? filename ".js")   "application/javascript; charset=utf-8"
    (str/ends-with? filename ".html") "text/html; charset=utf-8"
    (str/ends-with? filename ".json") "application/json; charset=utf-8"
    (str/ends-with? filename ".png")  "image/png"
    (str/ends-with? filename ".svg")  "image/svg+xml"
    :else                             "application/octet-stream"))

(defn- write-status! [^js res code mime body]
  (.writeHead res code #js {"Content-Type"  mime
                            "Cache-Control" "no-store, no-cache, must-revalidate"
                            "Pragma"        "no-cache"
                            "Expires"       "0"})
  (.end res body))

(defn- handle-readiness!
  "Report current executable admission; this can turn false after startup."
  [_req res]
  (let [ready? (admission/available?)]
    (write-status!
      res
      (if ready? 200 503)
      "application/edn; charset=utf-8"
      (pr-str (assoc (admission/state)
                     :seon.runtime.admission/available? ready?)))))

(defn- serve-static! [res url]
  (if-let [[prefix root] (some (fn [[p r]]
                                 (when (str/starts-with? url p) [p r]))
                               static-roots)]
    (let [rel  (subs url (count prefix))
          ;; Path-traversal guard — reject relative segments that
          ;; escape the static root. `node:path/normalize` collapses
          ;; `..` segments; if the result begins with `..` or contains
          ;; one, refuse.
          safe (.normalize path rel)]
      (if (or (str/blank? safe)
              (str/starts-with? safe "..")
              (str/includes? safe "/..")
              (.isAbsolute path safe))
        (write-status! res 404 "text/plain; charset=utf-8" (str "Not found: " url))
        (let [full (.join path root safe)]
          (try
            (let [body (.readFileSync fs full)]
              (write-status! res 200 (mime-type full) body))
            (catch :default _
              (write-status! res 404 "text/plain; charset=utf-8" (str "Not found: " url)))))))
    (write-status! res 404 "text/plain; charset=utf-8" (str "Not found: " url))))

;; ============================================================
;; Route handlers
;; ============================================================

;; ============================================================
;; POST /chat — inject a user message into the named agent's log.
;; The message tx wakes the agent's loop; it runs a turn, the LLM
;; responds, and the web UI morphs the view via SSE.
;;
;; Body is application/x-www-form-urlencoded (Datastar's
;; `@post('/chat', {contentType:'form'})` posts FormData). `agent` is
;; in the query string.
;; ============================================================

(defn- read-body
  "Collect a Node request body into a String. Returns a Promise."
  [^js req]
  (js/Promise.
    (fn [resolve _reject]
      (let [chunks (atom [])]
        (.on req "data"
             (fn [chunk]
               (swap! chunks conj chunk)))
        (.on req "end"
             (fn []
               (resolve (.toString
                          (.concat js/Buffer (clj->js @chunks))))))))))

(defn- parse-urlencoded
  "Parse an `application/x-www-form-urlencoded` body into a map of
   String → String. URLSearchParams handles RFC 3986 percent decoding."
  [body]
  (let [params (js/URLSearchParams. body)]
    (into {} (map (fn [[k v]] [k v]) (es6-iterator-seq (.entries params))))))

(defn- query-param
  "Pull a single query-string value out of `req.url`. Returns nil if
   absent. Defensive against malformed URLs."
  [req k]
  (try
    (let [full-url (str "http://x" (.-url req))   ; URL needs an origin
          u (js/URL. full-url)]
      (.get (.-searchParams u) k))
    (catch :default _ nil)))

(defn- handle-log! [req res]
  ;; Receives WebView console.log/warn/error forwards. Body is JSON
  ;; `{level, msg}`. We just print them on the server so a tail of
  ;; /tmp/seon-node.log shows browser-side events too.
  (-> (read-body req)
      (.then (fn [body]
               (try
                 (let [parsed (js->clj (js/JSON.parse body) :keywordize-keys true)
                       level  (or (:level parsed) "log")
                       msg    (str (:msg parsed))]
                   (case level
                     "error" (log/error-console! "browser" msg nil)
                     "warn"  (log/info-console!  "browser" (str "WARN " msg) nil)
                     (log/info-console! "browser" msg nil))
                   (write-status! res 204 "text/plain; charset=utf-8" ""))
                 (catch :default e
                   (log/error-console! "seon.web.serve" "/log parse failed" e)
                   (write-status! res 400 "text/plain; charset=utf-8"
                                  (str "bad log body: " e))))))
      (.catch (fn [err]
                (log/error-console! "seon.web.serve" "/log body read failed" err)
                (write-status! res 500 "text/plain; charset=utf-8" (str err))))))

(defn- handle-config-apply!
  "Apply one operator-selected config manifest through the live pod.

   The request body is the exact EDN operation input
   `{:seon.config/path <absolute-path>}`. The pod owns Aero resolution and the
   one `seon.client/apply-config!` database operation; this HTTP boundary only
   transports the request and its structured result."
  [req res]
  (-> (read-body req)
      (.then
        (fn [body]
          (let [request  (reader/read-string body)
                path     (:seon.config/path request)
                apply-fn (seval/lookup-value 'seon.client/apply-config!)]
            (when-not (and (= #{:seon.config/path} (set (keys request)))
                           (string? path)
                           (not (str/blank? path)))
              (throw (ex-info "invalid config apply request"
                              {:seon.error/kind :user-input})))
            (when-not apply-fn
              (throw (ex-info "live config operation is unavailable"
                              {:seon.error/kind :core})))
            (apply-fn {:seon.config/manifest
                       (config/load-manifest-path path)}))))
      (.then
        (fn [result]
          (write-status! res
                         (if (:seon.state/ok? result) 200 422)
                         "application/edn; charset=utf-8"
                         (pr-str result))))
      (.catch
        (fn [error]
          (let [message (or (.-message error) (str error))]
            ;; Keep the operator boundary structured and bounded: exception
            ;; objects can print stacks, source, or large analyzer data.
            (log/error-console! "seon.web.serve" "config apply failed"
                                {:seon.error/message message})
          (write-status! res 422 "application/edn; charset=utf-8"
                         (pr-str {:seon.state/ok? false
                                  :seon.state/error
                                  message})))))))

(defn- handle-clear! [req res]
  ;; Retract every :seon.agent.message AND :seon.eval entity for the agent.
  ;; Evals contain the full source of (seon.db/transact! ...) calls
  ;; that include the assistant message text — so retracting messages
  ;; alone leaves the conversation visible via the timeline's eval
  ;; rendering. Notes (`:seon.note/*`) are preserved — they ARE the
  ;; durable memory.
  ;;
  ;; Agent-id resolution (audit P1 — 2026-05-24): the query param wins;
  ;; otherwise we read `(db/current-agent-id)` from whatever ALS scope
  ;; the HTTP handler ran inside (none, by default — Node's request
  ;; callback runs at the event-loop root). When neither is set, 400 —
  ;; no silent fallback to a hardcoded id.
  (let [agent-id (or (query-param req "agent") (db/current-agent-id))]
    (when-not agent-id
      (write-status! res 400 "text/plain; charset=utf-8"
                     "missing 'agent' query param (no agent-id in scope)")
      (throw (js/Error. "handle-clear!: no agent-id resolved")))
    (log/info-console! "seon.web.serve" "/clear ENTER" {:agent agent-id})
    (try
      (let [my-eid   (:db/id (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))
            ;; "My conversation" is DERIVED: from = me OR to ∋ me.
            msg-eids (when my-eid
                       (->> (db/query
                              {:seon.db/query
                               '[:find ?m
                                 :in $ ?me
                                 :where
                                 (or-join [?m ?me]
                                   [?m :seon.agent.message/from ?me]
                                   [?m :seon.agent.message/to ?me])]
                               :seon.db/args [my-eid]})
                            (map first)))
            eval-eids (->> (db/query
                             {:seon.db/query
                              '[:find ?e
                                :in $ ?aid
                                :where
                                [?e :seon.eval/agent ?aid]]
                              :seon.db/args [[:seon.agent/id agent-id]]})
                           (map first))
            retractions (concat
                          (mapv (fn [e] [:db/retractEntity e]) msg-eids)
                          (mapv (fn [e] [:db/retractEntity e]) eval-eids))]
        (log/info-console! "seon.web.serve" "/clear query OK"
                           {:agent agent-id
                            :msg-count (count msg-eids)
                            :eval-count (count eval-eids)})
        ;; Retractions only — turn counts are DERIVED from the message/
        ;; session log (the retired :seon.agent/turn-count /
        ;; :turns-since-user attrs are unregistered; transacting them
        ;; threw :seon.db/unregistered-attrs and broke /clear).
        ;; ENVELOPE CONTRACT (A4): db/transact! ALWAYS resolves —
        ;; failures arrive as `{:seon.db/ok? false :seon.db/error …}`,
        ;; never as a rejection. Branch on the envelope; the .catch
        ;; below only guards non-transact throws in the .then body.
        (-> (db/transact! {:seon.db/tx-data (vec retractions)})
            (.then (fn [{ok?   :seon.db/ok?
                         error :seon.db/error}]
                     (if ok?
                       (do
                         (log/info-console! "seon.web.serve" "/clear TRANSACT OK"
                                            {:agent agent-id
                                             :messages-retracted (count msg-eids)
                                             :evals-retracted    (count eval-eids)})
                         (write-status! res 204 "text/plain; charset=utf-8" "")
                         (log/info-console! "seon.web.serve" "/clear RESPONSE SENT" {}))
                       (do
                         (log/error-console! "seon.web.serve" "/clear transact failed"
                                             (:seon.error/message error))
                         (write-status! res 500 "text/plain; charset=utf-8"
                                        (str "clear failed: "
                                             (:seon.error/message error)))))))
            (.catch (fn [err]
                      (log/error-console! "seon.web.serve" "/clear handler threw" err)
                      (write-status! res 500 "text/plain; charset=utf-8"
                                     (str "clear failed: " err))))))
      (catch :default e
        (log/error-console! "seon.web.serve" "/clear THREW SYNC" e)
        (write-status! res 500 "text/plain; charset=utf-8"
                       (str "clear failed: " e))))))

(defn- handle-create-agent!
  "POST /agents — atomically mint, commit, and resume one live agent.

   Responds 200 with the new id as plain text. This transition does no cluster
   seed, program replay, or global instrumentation, so concurrent requests rely
   on the sole writer's allocator serialization rather than a web-process lock."
  [req res]
  (log/info-console! "seon.web.serve" "POST /agents — creating agent" {})
  (-> (read-body req)
      (.then
        (fn [body]
          (let [purpose (some-> (get (parse-urlencoded (or body "")) "purpose")
                                str/trim
                                not-empty)]
            (agent/start! (cond-> {}
                            purpose (assoc :seon.agent/purpose purpose))))))
      (.then
        (fn [{id :seon.agent/id :as result}]
          (if (and id (not= false (:seon.agent.runtime/resumed? result)))
            (do
              (log/info-console! "seon.web.serve" "POST /agents OK"
                                 {:agent id})
              (write-status! res 200 "text/plain; charset=utf-8" (str id)))
            (let [message (or (get-in result [:seon.db/error :seon.error/message])
                              (:seon.agent.runtime/error result)
                              "agent creation returned no id")]
              (log/error-console! "seon.web.serve" "POST /agents refused" message)
              (write-status! res 500 "text/plain; charset=utf-8" message)))))
      (.catch
        (fn [err]
          (log/error-console! "seon.web.serve" "POST /agents failed" err)
          (write-status! res 500 "text/plain; charset=utf-8"
                         (str "create agent failed: " err))))))

(schema/register! ::ring-request :any)

(defn create-agent!
  "Database-routed POST `/agents` handler.

   Extracts the opaque Node request/response pair from the canonical Ring
   adapter and delegates to the single agent-creation transition above."
  {:malli/schema [:=> [:catn [::ring-request ::ring-request]] :any]}
  [ring-request]
  (if (admission/available?)
    (handle-create-agent! (:seon.http/node-req ring-request)
                          (:seon.http/node-res ring-request))
    (write-status!
      (:seon.http/node-res ring-request)
      503 "text/plain; charset=utf-8"
      (get-in (admission/unavailable)
              [:seon/error :seon.error/message]))))

(defn- handle-complete-agent!
  "POST /agent/<id>/complete — external control: CLOSE the agent's open run
   `:completed` (derived state falls to `:idle`, the single wakeable parked
   state — a new message opens a fresh run). Same effect as the agent's own
   `complete` function. When the agent has no open run it is already idle → 200
   no-op. 200 + id on success, 500 with the store error otherwise."
  [_req res agent-id]
  (-> (js/Promise.resolve
        (if-let [r (run/current-run {:seon.agent/id agent-id})]
          (run/close-run! {:seon.agent.run/id            (:seon.agent.run/id r)
                           :seon.agent.run/closed-reason :completed})
          {:seon.db/ok? true}))
      (.then (fn [{ok? :seon.db/ok? :as env}]
               (if ok?
                 (do (log/info-console! "seon.web.serve"
                                        "POST /agent/<id>/complete OK"
                                        {:agent agent-id})
                     (write-status! res 200 "text/plain; charset=utf-8"
                                    (str agent-id)))
                 (let [error (get-in env [:seon.db/error :seon.error/message])]
                   (log/error-console! "seon.web.serve"
                                       "/agent/<id>/complete refused" error)
                   (write-status! res 500 "text/plain; charset=utf-8"
                                  (str error))))))
      (.catch (fn [err]
                (log/error-console! "seon.web.serve"
                                    "/agent/<id>/complete threw" err)
                (write-status! res 500 "text/plain; charset=utf-8"
                               (str "complete failed: " err))))))

;; ============================================================
;; POST /agents/run — the one-shot composition door, built purely from the
;; agent/message primitives: start-or-reuse an agent IN THE POD'S OWN CLUSTER,
;; deliver `input` as a user message via the REAL wake path (message!, from =
;; user-ref — identical to /chat), await the DERIVED state falling back to
;; :idle (the agent's OWN multi-turn FSM decides turns, never the caller) or
;; the request timeout, then reply with truthful turn/eval/reply metadata read
;; from the ONE conn. No scratch store, no conn/schema root swap — a CLUSTER
;; is the isolation unit (one pod per cluster; the supervisor mints per-task
;; clusters when isolation is wanted, `bin/seon cluster create`). The cluster
;; store is durable, so passing an existing `agent_id` drives THE SAME agent
;; again — including across a pod restart (boot re-arms armable agents).
;; No Malli schema (opaque node req/res, same as the sibling handlers).
;; Body: application/json {"input" str, "timeout_ms" int?, "agent_id" str?}.
;; An absent request timeout derives from the same database run policy and
;; optional agent override that open-run! uses; the door owns no second bound.
;; ============================================================

(defn- agent-run-timeout-ms [dbv agent-id requested]
  (or requested
      (run/effective-deadline-ms
        (cond-> {:seon.db/db dbv}
          agent-id (assoc :seon.agent/id agent-id)))))

(defn- latest-run-start-ms
  "Wall-clock ms of the agent's MOST-RECENTLY-STARTED run (open or closed) over
   the db value `db`, or 0 when none. The /agents/run poll uses this to reject
   the agent's PRE-INJECTION state: `:idle` alone is ambiguous (an idle agent
   has no open run BEFORE our message wakes it), so we only accept an idle
   whose latest run started at/after the injection — i.e. the run our message
   woke has opened and closed."
  [db aid]
  (->> (db/query {:seon.db/db db
                  :seon.db/query '[:find ?started :in $ ?aid :where
                                   [?a :seon.agent/id ?aid]
                                   [?r :seon.agent.run/agent ?a]
                                   [?r :seon.agent.run/started-at ?started]]
                  :seon.db/args [aid]})
       (map (fn [[^js started]] (.getTime started)))
       (reduce max 0)))

(defn- coordinate-json
  "JSON-safe external projection of one complete database coordinate."
  [point]
  {:database_id (str (::coordinate/database-id point))
   :branch (name (::coordinate/branch point))
   :commit_id (str (::coordinate/commit-id point))
   :t (::coordinate/t point)})

(defn- captured-turn-coordinate-json
  "JSON-safe complete coordinate stored on one debug turn bundle."
  [bundle]
  (when (every? #(contains? bundle %)
                [:seon.agent.turn/rendered-database-id
                 :seon.agent.turn/rendered-branch
                 :seon.agent.turn/rendered-commit-id
                 :seon.agent.turn/rendered-t])
    {:database_id (str (:seon.agent.turn/rendered-database-id bundle))
     :branch (name (:seon.agent.turn/rendered-branch bundle))
     :commit_id (str (:seon.agent.turn/rendered-commit-id bundle))
     :t (:seon.agent.turn/rendered-t bundle)}))

(defn- turn-evidence
  "Stable external projection of captured turn prompts and raw replies."
  [turn-ids]
  (mapv
    (fn [turn-id]
      (let [bundle (agent-debug/turn {:seon.agent.turn/id turn-id})
            rendered-coordinate (captured-turn-coordinate-json bundle)]
        (cond-> {:turn_id turn-id
                 :ok (:seon.agent.debug/ok? bundle)}
          (:seon.agent.turn/status bundle)
          (assoc :status (name (:seon.agent.turn/status bundle)))
          (:seon.agent.turn/at bundle)
          (assoc :at (.toISOString ^js (:seon.agent.turn/at bundle)))
          rendered-coordinate
          (assoc :rendered_coordinate rendered-coordinate)
          (contains? bundle :seon.agent.debug/prompt)
          (assoc :prompt (:seon.agent.debug/prompt bundle))
          (contains? bundle :seon.agent.debug/prompt-tokens)
          (assoc :prompt_tokens (:seon.agent.debug/prompt-tokens bundle))
          (contains? bundle :seon.agent.debug/reply)
          (assoc :reply (:seon.agent.debug/reply bundle))
          (contains? bundle :seon.agent.debug/reply-tokens)
          (assoc :reply_tokens (:seon.agent.debug/reply-tokens bundle))
          (:seon.agent.turn/error bundle)
          (assoc :turn_error (:seon.agent.turn/error bundle))
          (:seon.agent.debug/error bundle)
          (assoc :capture_error (:seon.agent.debug/error bundle)))))
    turn-ids))

(declare evidence-json-value)

(defn- evidence-order-key [value]
  (cond
    (map? value)
    ["09-map"
     (->> value
          (map (fn [[k v]] [(evidence-order-key k) (evidence-order-key v)]))
          sort vec)]

    (set? value)
    ["08-set" (->> value (map evidence-order-key) sort vec)]

    (vector? value)
    ["06-vector" (mapv evidence-order-key value)]

    (list? value)
    ["07-list" (mapv evidence-order-key value)]

    (nil? value) ["00-nil"]
    (boolean? value) ["01-boolean" value]
    (number? value) ["02-number" (pr-str value)]
    (string? value) ["03-string" value]
    (keyword? value) ["04-keyword" (str value)]
    (symbol? value) ["05-symbol" (str value)]
    :else ["10-unsupported" (pr-str (type value))]))

(defn- evidence-json-value
  "Lossless JSON-safe tagged projection of one normalized EDN value."
  [value]
  (cond
    (map? value)
    {:kind "map"
     :entries
     (mapv (fn [[k v]]
             {:key (evidence-json-value k) :value (evidence-json-value v)})
           (sort-by (fn [[k v]] [(evidence-order-key k)
                                 (evidence-order-key v)])
                    value))}

    (set? value)
    {:kind "set"
     :items (mapv evidence-json-value (sort-by evidence-order-key value))}

    (vector? value)
    {:kind "vector" :items (mapv evidence-json-value value)}

    (list? value)
    {:kind "list" :items (mapv evidence-json-value value)}

    (keyword? value)
    {:kind "keyword" :value (str value)}

    (symbol? value)
    {:kind "symbol" :value (str value)}

    (or (nil? value) (boolean? value) (number? value) (string? value))
    {:kind "scalar" :value value}

    :else
    {:kind "unsupported"}))

(defn- operation-coordinate-valid? [operation-coordinate final-coordinate]
  (try
    (and (map? operation-coordinate)
         (coordinate/same-attachment? operation-coordinate final-coordinate)
         (<= (::coordinate/t operation-coordinate)
             (::coordinate/t final-coordinate)))
    (catch :default _ false)))

(defn- operation-json [operation final-coordinate]
  (let [point (:seon.db/operation-coordinate operation)]
    (cond->
      {:position (:seon.db/operation-position operation)
       :operation (str (:seon.db/read-operation operation))
       :ok (true? (:seon.db/operation-ok? operation))
       :source (str (:seon.db/read-source operation))
       :replayable (true? (:seon.db/read-replayable? operation))
       :coordinate_valid (operation-coordinate-valid? point final-coordinate)
       :request (evidence-json-value (:seon.db/read-request operation))
       :result (evidence-json-value (:seon.db/read-result operation))}
      (map? point) (assoc :coordinate (coordinate-json point)))))

(defn- valid-operation-vector? [operations]
  (and (vector? operations)
       (= (mapv :seon.db/operation-position operations)
          (vec (range (count operations))))
       (every?
         (fn [operation]
           (and (map? operation)
                (schema/valid-candidate-value?
                  :seon.db/read-observation operation)
                (every? #(contains? operation %)
                        [:seon.db/read-operation
                         :seon.db/operation-position
                         :seon.db/operation-ok?
                         :seon.db/read-source
                         :seon.db/read-request
                         :seon.db/read-result
                         :seon.db/read-replayable?])))
         operations)))

(defn- supported-evidence-json? [value]
  (and (map? value)
       (not= "unsupported" (:kind value))
       (cond
         (= "map" (:kind value))
         (every? (fn [{:keys [key value]}]
                   (and (supported-evidence-json? key)
                        (supported-evidence-json? value)))
                 (:entries value))

         (contains? #{"set" "vector" "list"} (:kind value))
         (every? supported-evidence-json? (:items value))

         (contains? #{"keyword" "symbol" "scalar"} (:kind value))
         true

         :else false)))

(defn- project-operation-evidence
  "Bounded operation proof resolved from one final-snapshot blob ref."
  [blob-row final-coordinate]
  (let [hash (:my.blob/hash blob-row)
        projected-tokens (:my.blob/tokens blob-row)
        token-ceiling (tokens/chars->tokens (config/database-edn-cap))]
    (cond
      (or (not (string? hash)) (not (int? projected-tokens)))
      (cond-> {:status "missing"}
        (string? hash) (assoc :blob_hash hash))

      (> projected-tokens token-ceiling)
      {:status "oversized" :blob_hash hash :tokens projected-tokens}

      :else
      (let [readback (blob/get {:my.blob/hash hash})]
        (cond
          (or
          (not (true? (:my.blob/ok? readback)))
          (not (string? (:my.blob/content readback))))
          {:status "missing" :blob_hash hash :tokens projected-tokens}

          (> (count (:my.blob/content readback)) (config/database-edn-cap))
          {:status "oversized"
           :blob_hash hash
           :chars (count (:my.blob/content readback))
           :bytes (js/Buffer.byteLength (:my.blob/content readback) "utf8")
           :tokens projected-tokens}

          :else
          (let [content (:my.blob/content readback)]
            (if (not= projected-tokens (:my.blob/tokens readback))
              {:status "malformed"
               :blob_hash hash
               :chars (count content)
               :bytes (js/Buffer.byteLength content "utf8")
               :tokens projected-tokens}
              (try
              (let [forms (reader/read-string (str "[" content "]"))
                    operations (when (= 1 (count forms)) (first forms))]
                (let [projected (when (valid-operation-vector? operations)
                                  (mapv #(operation-json % final-coordinate)
                                        operations))]
                  (if (and projected
                           (every? #(and (supported-evidence-json? (:request %))
                                         (supported-evidence-json? (:result %)))
                                   projected))
                    {:status "inline"
                     :blob_hash hash
                     :chars (count content)
                     :bytes (js/Buffer.byteLength content "utf8")
                     :tokens projected-tokens
                     :operations projected}
                    {:status "malformed"
                     :blob_hash hash
                     :chars (count content)
                     :bytes (js/Buffer.byteLength content "utf8")
                     :tokens projected-tokens})))
              (catch :default _
                {:status "malformed"
                 :blob_hash hash
                 :chars (count content)
                 :bytes (js/Buffer.byteLength content "utf8")
                 :tokens projected-tokens})))))))))

(defn- project-eval-evidence
  "Stable external projection of selected eval rows."
  [rows turn-eids final-coordinate pull-row]
  (->> rows
       (filter (fn [[_ turn-eid]] (contains? turn-eids turn-eid)))
       (sort-by (fn [[_ _ _ _ _ eval-t]] eval-t))
       (mapv
         (fn [[eval-eid _ turn-id id ^js at eval-t]]
           (let [row (pull-row eval-eid)]
             (cond-> {:eval_id id
                      :turn_id turn-id
                      :eval_transaction eval-t
                      :at (.toISOString at)
                      :ok (boolean (:seon.eval/ok? row))}
               (contains? row :seon.eval/source)
               (assoc :source (:seon.eval/source row))
               (contains? row :seon.eval/narration)
               (assoc :narration (:seon.eval/narration row))
               (contains? row :seon.eval/database-operations-blob)
               (assoc :operation_evidence
                      (project-operation-evidence
                        (:seon.eval/database-operations-blob row)
                        final-coordinate))))))))

(defn- eval-evidence
  "Stable external projection of one request window's evaluated forms."
  [dbv _agent-eid turn-eids]
  (let [rows (db/query {:seon.db/db dbv
                        :seon.db/query '[:find ?e ?t ?turn-id ?id ?at ?eval-t
                                         :in $ [?t ...] :where
                                         [?t :seon.agent.turn/id ?turn-id]
                                         [?t :seon.agent.turn/evals ?e]
                                         [?e :seon.eval/id ?id ?eval-t]
                                         [?e :seon.eval/at ?at]]
                        :seon.db/args [(vec turn-eids)]})]
    (project-eval-evidence
      rows turn-eids (db/head-coordinate dbv)
      (fn [eval-eid]
        (db/pull {:seon.db/db dbv
                  :seon.db/pull-pattern
                  '[:seon.eval/source :seon.eval/ok? :seon.eval/narration
                    {:seon.eval/database-operations-blob
                     [:my.blob/hash :my.blob/tokens]}]
                  :seon.db/ref eval-eid})))))

(defn- ^:async run-agent-task!
  "Drive ONE task through an agent in the pod's own cluster to completion.

   Start-or-reuse: a nil `agent-id` calls [[seon.agent/start!]]; a supplied
   `agent-id` reuses that agent —
   it must already exist, and because the cluster store is durable the same
   agent can be driven again after a pod restart (boot re-arms armable
   agents), which multi-phase drives rely on. `input` lands via the real
   wake path (`agent/message!`, from = the user ref); the DERIVED state is
   polled to the `:idle` of the run our message woke (see
   [[latest-run-start-ms]]) or `timeout-ms`; the returned map carries
   turn/eval/reply metadata scoped to THIS request's window (runs started
   at/after injection — a reused agent's earlier work never inflates the
   counts). The agent's OWN FSM decides turns; this caller never does.

   Timeout honesty: a timeout exit carries `:closed_reason \"timeout\"` +
   `:timed_out true` (never a stale derived last-closed-reason), AND the
   still-open run is closed `:superseded` so it stops burning LLM tokens.
   A refusal (unknown agent-id, failed mint) returns `{:error <msg>}`."
  [agent-id input timeout-ms]
  (let [conn    db/*conn*
        reuse?  (some? agent-id)
        exists? (when reuse?
                  (some? (db/query {:seon.db/db @conn
                                    :seon.db/query '[:find ?e . :in $ ?id :where
                                                     [?e :seon.agent/id ?id]]
                                    :seon.db/args [agent-id]})))]
    (if (and reuse? (not exists?))
      {:error (str "unknown agent_id: " agent-id)}
      (let [minted (when-not reuse? (await (agent/start! {})))
            aid    (or agent-id (:seon.agent/id minted))]
        (if (and (not reuse?)
                 (or (false? (:seon.db/ok? minted))
                     (false? (:seon.agent.runtime/resumed? minted))
                     (nil? aid)))
          {:error (str "agent mint failed: "
                       (or (get-in minted
                                   [:seon.db/error :seon.error/message])
                           (:seon.agent.runtime/error minted)
                           "mint returned no committed agent id"))}
          ;; `injected-at` is stamped BEFORE the message lands, so the run the
          ;; wake opens can never predate the window the reads below scope to.
          (let [start       (js/Date.now)
                injected-at start]
            (log/info-console! "seon.web.serve" "POST /agents/run — task in"
                               {:agent aid :reused reuse?
                                :tokens (tokens/estimate (str input))})
            (await (agent/message!
                     {:seon.agent.message/from    agent/user-ref
                      :seon.agent.message/to      [[:seon.agent/id aid]]
                      :seon.agent.message/content input}))
            (loop []
              (await (js/Promise. (fn [r] (js/setTimeout r 1500))))
              ;; ONE db snapshot per poll — every read below sees the same
              ;; snapshot (a mid-poll write must not split state vs reply reads).
              (let [db       @conn
                    st       (derive/derive-state db aid)
                    elapsed  (- (js/Date.now) start)
                    done?    (and (= :idle st)
                                  (>= (latest-run-start-ms db aid) injected-at))
                    timeout? (> elapsed timeout-ms)]
                (if-not (or done? timeout?)
                  (recur)
                  (do
                    ;; TIMEOUT HONESTY: close the run we woke (if still open)
                    ;; so it stops driving turns after we've given up.
                    (when timeout?
                      (when-let [run (derive/current-run db aid)]
                        (await (run/close-run!
                                 {:seon.agent.run/id            (:seon.agent.run/id run)
                                  :seon.agent.run/closed-reason :superseded}))))
                    ;; A timeout close is itself a commit. Refresh once after
                    ;; that write so response metadata and its head coordinate
                    ;; describe the same immutable final database value.
                    (let [db        (if timeout? @conn db)
                          agent-eid (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id aid]}))
                          user-eid  (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.user/id "user"]}))
                          ;; the runs THIS request opened (window scoping)
                          run-eids  (->> (db/query {:seon.db/db db
                                                    :seon.db/query '[:find ?r ?started :in $ ?ag :where
                                                                     [?r :seon.agent.run/agent ?ag]
                                                                     [?r :seon.agent.run/started-at ?started]]
                                                    :seon.db/args [agent-eid]})
                                         (keep (fn [[r ^js started]]
                                                 (when (>= (.getTime started) injected-at) r)))
                                         set)
                          turn-rows (->> (db/query {:seon.db/db db
                                                    :seon.db/query '[:find ?t ?id ?at ?r :in $ ?ag :where
                                                                     [?r :seon.agent.run/agent ?ag]
                                                                     [?t :seon.agent.turn/run ?r]
                                                                     [?t :seon.agent.turn/id ?id]
                                                                     [?t :seon.agent.turn/at ?at]]
                                                    :seon.db/args [agent-eid]})
                                         (filter (fn [[_ _ _ r]]
                                                   (contains? run-eids r)))
                                         (sort-by (fn [[_ id ^js at _]]
                                                    [(.getTime at) id])))
                          turn-eids (into #{} (map first) turn-rows)
                          turn-ids  (mapv second turn-rows)
                          eval-rows (eval-evidence db agent-eid turn-eids)
                          ;; Model config — COMPUTED at response time by the
                          ;; ONE resolver (seon.ai/resolved-config), a pure fn
                          ;; of this poll's db snapshot: the agent's own
                          ;; :seon.ai/agent-* overrides → the global config
                          ;; row → shipped defaults. Derive-don't-store (owner
                          ;; correction 2026-07-04 — supersedes the per-turn
                          ;; llm-* stamping). Honest CURRENT intent for a
                          ;; just-finished run; per-turn historical exactness
                          ;; is the same resolver over (db/as-of db <turn's
                          ;; rendered-as-of>) — see the resolver docstring.
                          model-cfg (let [{:seon.ai/keys [resolved-config]}
                                          (ai/resolved-config
                                            {:seon.db/db db :seon.agent/id aid})
                                          {:seon.ai/keys [provider model temperature
                                                          max-tokens thinking]}
                                          resolved-config]
                                      (cond-> {:provider (name provider)}
                                        model       (assoc :model model)
                                        temperature (assoc :temperature temperature)
                                        max-tokens  (assoc :max_tokens max-tokens)
                                        thinking    (assoc :thinking thinking)))
                          reply     (->> (db/query {:seon.db/db db
                                                    :seon.db/query '[:find ?f ?to ?at ?c :where
                                                                     [?m :seon.agent.message/from ?f]
                                                                     [?m :seon.agent.message/to ?to]
                                                                     [?m :seon.agent.message/at ?at]
                                                                     [?m :seon.agent.message/content ?c]]})
                                         (filter (fn [[from to ^js at _]]
                                                   (and (= from agent-eid) (= to user-eid)
                                                        (>= (.getTime at) injected-at))))
                                         (sort-by (fn [[_ _ at _]] (.getTime ^js at)))
                                         (map (fn [[_ _ _ c]] c)) last)]
                      ;; On timeout report the HONEST reason — never a stale
                      ;; derived last-closed-reason from an earlier close.
                      (cond-> {:agent_id aid :turns (count turn-eids)
                               :evals (count eval-rows)
                               :reply (or reply "") :elapsed_ms elapsed
                               :database_coordinate
                               (coordinate-json (db/head-coordinate db))
                               :turn_evidence (turn-evidence turn-ids)
                               :eval_evidence eval-rows
                               ;; always present — the resolver always resolves
                               ;; (worst case: all shipped defaults).
                               :model_config model-cfg
                               :closed_reason (if timeout?
                                                "timeout"
                                                (str (derive/last-closed-reason db aid)))}
                        timeout? (assoc :timed_out true)))))))))))))

(defn- handle-agent-run! [req res]
  (-> (read-body req)
      (.then (fn [body]
               (let [parsed     (js->clj (js/JSON.parse body))
                     input      (get parsed "input")
                     agent-id   (get parsed "agent_id")
                     requested-timeout-ms (get parsed "timeout_ms")
                     timeout-ms (agent-run-timeout-ms
                                  @db/*conn* agent-id
                                  requested-timeout-ms)]
                 (-> (run-agent-task! agent-id input timeout-ms)
                     (.then
                       (fn [result]
                         (assoc result
                                :effective_timeout_ms timeout-ms
                                :timeout_source
                                (if (some? requested-timeout-ms)
                                  "request"
                                  "database"))))))))
      (.then (fn [result]
               (if (:error result)
                 (do
                   (log/error-console! "seon.web.serve" "/agents/run refused"
                                       (:error result))
                   (write-status! res 422 "application/json; charset=utf-8"
                                  (js/JSON.stringify #js {:error (:error result)})))
                 (do
                   (log/info-console! "seon.web.serve" "POST /agents/run OK"
                                      {:agent      (:agent_id result)
                                       :turns      (:turns result)
                                       :evals      (:evals result)
                                       :elapsed-ms (:elapsed_ms result)})
                   (write-status! res 200 "application/json; charset=utf-8"
                                  (js/JSON.stringify (clj->js result)))))))
      (.catch (fn [err]
                (log/error-console! "seon.web.serve" "/agents/run threw" err)
                (write-status! res 500 "application/json; charset=utf-8"
                               (js/JSON.stringify #js {:error (str err)}))))))

(defn- handle-chat! [req res]
  ;; Agent-id resolution (audit P1 — 2026-05-24): query param wins,
  ;; else `(db/current-agent-id)`, else 400 — no silent "seon" fallback.
  (let [agent-id (or (query-param req "agent") (db/current-agent-id))]
    (when-not agent-id
      (write-status! res 400 "text/plain; charset=utf-8"
                     "missing 'agent' query param (no agent-id in scope)")
      (throw (js/Error. "handle-chat!: no agent-id resolved")))
    (-> (read-body req)
        (.then (fn [body]
                 (let [params (parse-urlencoded body)
                       text   (get params "text")]
                   (if (or (nil? text) (str/blank? text))
                     (write-status! res 400 "text/plain; charset=utf-8"
                                    "missing 'text' param")
                     ;; The HTTP adapter is the USER's hands — stamp
                     ;; from = the user ref explicitly (no ALS agent
                     ;; scope at the event-loop root). message! is the
                     ;; single entry point; the envelope is checked,
                     ;; never assumed.
                     (-> (agent/message!
                           {:seon.agent.message/from    agent/user-ref
                            :seon.agent.message/to      [[:seon.agent/id agent-id]]
                            :seon.agent.message/content text})
                         (.then (fn [{ok?     :seon.agent.message/ok?
                                      msg-id  :seon.agent.message/id
                                      hops    :seon.agent.message/hops
                                      error   :seon.db/error}]
                                  (if ok?
                                    (do
                                      (log/info-console! "seon.web.serve" "POST /chat"
                                                         {:agent agent-id :tokens (tokens/estimate text)})
                                      ;; A DISTINCT intake
                                      ;; line per accepted message (the generic
                                      ;; POST log above only records token count).
                                      ;; Carries the durable message id + hops so
                                      ;; an intake can be correlated with the wake
                                      ;; (or its drain) in logs/pod.log.
                                      (log/info-console! "seon.web.serve" "INTAKE"
                                                         {:agent    agent-id
                                                          :msg-id   msg-id
                                                          :hops     hops})
                                      (write-status! res 204 "text/plain; charset=utf-8" ""))
                                    (do
                                      (log/error-console! "seon.web.serve" "/chat message! refused"
                                                          (:seon.error/message error))
                                      (write-status! res 422 "text/plain; charset=utf-8"
                                                     (str "chat refused: "
                                                          (:seon.error/message error)))))))
                         (.catch (fn [err]
                                   (log/error-console! "seon.web.serve" "/chat message! threw" err)
                                   (write-status! res 500 "text/plain; charset=utf-8"
                                                  (str "chat failed: " err)))))))))
        (.catch (fn [err]
                  (log/error-console! "seon.web.serve" "/chat body read failed" err)
                  (try
                    (write-status! res 500 "text/plain; charset=utf-8" (str err))
                    (catch :default _ nil)))))))

;; ============================================================
;; POST /stop — the graceful STOP: PAUSE the agent's open run (resumable).
;; `run/pause!` stamps the open run `paused-at` (⇒ derived state `:paused`) and
;; banks the remaining wall-clock budget; the drive loop reads the lost lease
;; (the fencing CAS) and exits. No open run ⇒ already idle (204 no-op). The
;; agent is HELD, not killed — POST /resume re-drives it.
;; ============================================================

(defn- handle-stop! [req res]
  ;; Agent-id resolution mirrors /chat: query param wins, else the ALS scope,
  ;; else 400 — no silent fallback.
  (let [agent-id (or (query-param req "agent") (db/current-agent-id))]
    (when-not agent-id
      (write-status! res 400 "text/plain; charset=utf-8"
                     "missing 'agent' query param (no agent-id in scope)")
      (throw (js/Error. "handle-stop!: no agent-id resolved")))
    (if-let [r (run/current-run {:seon.agent/id agent-id})]
      (-> (run/pause! {:seon.agent/id     agent-id
                       :seon.agent.run/id (:seon.agent.run/id r)})
          (.then (fn [{ok? :seon.db/ok? error :seon.db/error}]
                   (if ok?
                     (do
                       (log/info-console! "seon.web.serve" "POST /stop — paused open run"
                                          {:agent agent-id :run (:seon.agent.run/id r)})
                       (write-status! res 204 "text/plain; charset=utf-8" ""))
                     (do
                       (log/error-console! "seon.web.serve" "/stop pause! refused"
                                           (:seon.error/message error))
                       (write-status! res 422 "text/plain; charset=utf-8"
                                      (str "stop refused: " (:seon.error/message error)))))))
          (.catch (fn [err]
                    (log/error-console! "seon.web.serve" "/stop pause! threw" err)
                    (write-status! res 500 "text/plain; charset=utf-8"
                                   (str "stop failed: " err)))))
      (do
        (log/info-console! "seon.web.serve" "POST /stop — no open run (already idle)"
                           {:agent agent-id})
        (write-status! res 204 "text/plain; charset=utf-8" "")))))

;; ============================================================
;; POST /resume — wake a PAUSED run. `lifecycle/resume` clears `paused-at`,
;; re-extends the deadline by the banked budget, AND re-enters the drive loop
;; (the loop EXITED on :pause, so resume must re-drive). It reads
;; `(db/current-agent-id)`, so we run it inside the agent's ALS scope via
;; `db/with-agent` (which preserves the id across the resume's awaits). It
;; returns the derived state keyword (`:running`) on success or a loud error
;; envelope (e.g. not paused / no open run).
;; ============================================================

(defn- handle-resume! [req res]
  (let [agent-id (or (query-param req "agent") (db/current-agent-id))]
    (when-not agent-id
      (write-status! res 400 "text/plain; charset=utf-8"
                     "missing 'agent' query param (no agent-id in scope)")
      (throw (js/Error. "handle-resume!: no agent-id resolved")))
    (-> (js/Promise.resolve (db/with-agent agent-id (fn [] (lifecycle/resume))))
        (.then (fn [result]
                 ;; success = a derived state keyword (:running); failure = the
                 ;; `{:seon.db/ok? false …}` envelope (a map).
                 (if (keyword? result)
                   (do
                     (log/info-console! "seon.web.serve" "POST /resume — re-driving"
                                        {:agent agent-id :state result})
                     (write-status! res 204 "text/plain; charset=utf-8" ""))
                   (let [error (get-in result [:seon.db/error :seon.error/message])]
                     (log/error-console! "seon.web.serve" "/resume refused" error)
                     (write-status! res 422 "text/plain; charset=utf-8"
                                    (str "resume refused: " error))))))
        (.catch (fn [err]
                  (log/error-console! "seon.web.serve" "/resume threw" err)
                  (write-status! res 500 "text/plain; charset=utf-8"
                                 (str "resume failed: " err)))))))

;; ============================================================
;; CSRF / same-origin guard for state-changing POSTs. Loopback BINDING is not
;; protection — a page on any site the human visits can `no-cors` POST to
;; 127.0.0.1. A browser attaches an `Origin` header on such cross-site
;; requests, so we refuse any POST whose Origin is present and NOT loopback.
;; ============================================================

(def ^:private loopback-hosts
  ;; A same-origin fetch from the pod's own loopback UI carries one of these
  ;; hostnames; a cross-site Origin (any internet page) will not. The fallback
  ;; allow when no Host header is available to compare against.
  #{"127.0.0.1" "localhost" "[::1]" "::1"})

(def ^:private loopback-peer-addresses
  #{"127.0.0.1" "::1" "::ffff:127.0.0.1"})

(defn loopback-peer?
  "Whether the TCP peer of this Node request is the local machine.

   This is the operator lifecycle identity check, not a browser-origin check.
   Missing socket evidence fails closed; Host and Origin headers are never
   accepted as substitutes for the kernel-reported remote address."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [^js req]
  (contains? loopback-peer-addresses
             (some-> req .-socket .-remoteAddress)))

(defn- handle-operator-quiesce!
  "Drain this pod and flush its typed lifecycle result as EDN."
  [_req res]
  (if-let [quiesce! (seval/lookup-value 'seon.client/quiesce-runtime!)]
    (-> (js/Promise.resolve (quiesce!))
        (.then
         (fn [result]
           (write-status!
            res
            (if (:seon.client/quiesced? result) 200 409)
            "application/edn; charset=utf-8"
            (pr-str result))))
        (.catch
         (fn [error]
           (log/error-console! "seon.web.serve"
                               "operator quiesce failed" error)
           (write-status!
            res 500 "application/edn; charset=utf-8"
            (pr-str
             {:seon.client/quiesced? false
              :seon.client/quiesce-error
              (or (.-message error) (str error))})))))
    (write-status!
     res 503 "application/edn; charset=utf-8"
     (pr-str
      {:seon.client/quiesced? false
       :seon.client/quiesce-error
       "The runtime lifecycle owner is not loaded."}))))

(defn same-origin?
  "Whether the request passes the same-origin (CSRF) check.

   True (ALLOW) when no `Origin` header is present (curl / the agent / any
   non-browser caller) OR the request is genuinely same-origin; false (REFUSE)
   when an Origin IS present and is cross-site — the CSRF case.

   Same-origin is decided by matching the Origin's host to the request's own
   `Host` header (so it holds for loopback dev AND a Caddy/Tauri front that
   preserves Host). When no Host is available we fall back to allowing loopback
   origins only. `req` is an opaque Node IncomingMessage (Ring-style boundary,
   no Malli schema — same as the /call, debug, and serve handlers)."
  [^js req]
  (let [headers (.-headers req)
        origin  (when headers (gobj/get headers "origin"))]
    (boolean
      (or (str/blank? origin)
          (try
            (let [o-host (.-host (js/URL. origin))            ; host[:port] of Origin
                  h-host (when headers (gobj/get headers "host"))]
              (or (and h-host (= o-host h-host))               ; genuine same-origin
                  (contains? loopback-hosts (.-hostname (js/URL. origin)))))
            (catch :default _ false))))))

;; ============================================================
;; Reitit front door — `seon.web.router` owns the route vector + the
;; Node↔Ring adapter; serve keeps the handler fns (they touch serve-state:
;; the SSE registry) and the same-origin? gate (a
;; test pins it). We INJECT both into router here. This call re-runs on
;; hot-reload, so the cached router always holds the freshly-reloaded
;; handler fns. createServer (below) dispatches every request through
;; `router/handle-request`.
;; ============================================================

;; `/` is NOT a serve handler — it is a SEEDED core route
;; (:seon.route/root → seon.web.datastar/serve-root!, root's own agent view),
;; resolved late by the router's db->routes. Only the non-core supplement
;; handlers are injected here.
(router/install!
  {:seon.web.router/static        serve-static!
   :seon.web.router/readiness     handle-readiness!
   :seon.web.router/chat          handle-chat!
   :seon.web.router/stop          handle-stop!
   :seon.web.router/resume        handle-resume!
   :seon.web.router/clear         handle-clear!
   :seon.web.router/log           handle-log!
   :seon.web.router/complete      handle-complete-agent!
   :seon.web.router/agent-run     handle-agent-run!
   :seon.web.router/config-apply  handle-config-apply!
   :seon.web.router/operator-quiesce handle-operator-quiesce!
   :seon.web.router/same-origin?  same-origin?
   :seon.web.router/loopback-peer? loopback-peer?})

;; ============================================================
;; Lifecycle
;; ============================================================

(defn- ensure-tmp-dir! []
  ;; Project-local `tmp/` per CLAUDE.md ("never use /tmp"). Created
  ;; relative to cwd — assumes the pod runs from the project root.
  (try (.mkdirSync fs "tmp" #js {:recursive true})
       (catch :default _ nil)))

(defn- write-port-file! [port]
  (ensure-tmp-dir!)
  (let [target (or (.. js/process -env -SEON_PORT_FILE)
                   "tmp/seon-port")]
    (.writeFileSync fs target (str port))
    target))

(defn- requested-port
  "Pick the bind port. Default 7890 (fixed, bookmarkable across pod
   restarts). Override via SEON_PORT — set to 0 for ephemeral
   allocation (useful when running multiple pods side-by-side).

   Examples:
     SEON_PORT=7890   ; default — fixed
     SEON_PORT=0      ; ephemeral — Node picks a free port
     SEON_PORT=8080   ; explicit override"
  []
  (let [raw (.. js/process -env -SEON_PORT)]
    (if (nil? raw)
      7890
      (let [n (js/parseInt raw 10)]
        (if (js/Number.isNaN n) 7890 n)))))

(defn- bind-host
  "Pick the bind interface. Default loopback (`127.0.0.1` — nothing on the
   LAN sees a dev pod). Override via SEON_BIND — a containerized pod sets
   `SEON_BIND=0.0.0.0` so docker's published-port forward (which targets the
   container's own interface, never its loopback) can reach the server.
   Infra-wiring env read at point of use, same category as SEON_PORT."
  []
  (or (.. js/process -env -SEON_BIND) "127.0.0.1"))

(defn start!
  "Start the HTTP+SSE server on a loopback port.

   Returns a Promise resolving to:
     {:seon.web/port <int> :seon.web/port-file <abs-path>}

   Default port is 7890 (override via $SEON_PORT; set to 0 for
   ephemeral). Writes the bound port to $SEON_PORT_FILE (default
   `tmp/seon-port`). Idempotent — when a server is already LISTENING
   the call resolves with the existing binding (restarting would drop
   every open SSE stream AND kill the in-flight request when a second
   agent is born via POST /agents → seon.agent/start!). A dead
   (closed) server object is replaced.

   The server binds to 127.0.0.1 by default (loopback only — browsers on
   the same machine can connect; nothing on the LAN sees the pod). A
   containerized pod overrides via SEON_BIND=0.0.0.0 (see [[bind-host]]).

   If the requested port is in use, the listen fails fast — that's
   the expected behavior for a dev pod (only one instance at a time).
   To run multiple pods, set SEON_PORT=0 for ephemeral allocation."
  {:malli/schema [:=> [:cat] :any]}
  []
  (js/Promise.
    (fn [resolve reject]
      ;; Attach the router's stable database listener and reconcile the
      ;; NOW-SEEDED route projection before accepting a request. The top-level
      ;; install ran before boot-seed! and therefore initially compiled only
      ;; the static supplement; route transactions after this point update the
      ;; cache through the same Datahike listener bus as the rest of the pod.
      (router/attach!)
      (if-let [live-addr (some-> @!server .address)]
        ;; Already listening — reuse (see docstring; a second
        ;; start-runtime! on the same pod must NOT bounce the server).
        (resolve {:seon.web/port      (.-port live-addr)
                  :seon.web/port-file (or (.. js/process -env -SEON_PORT_FILE)
                                          "tmp/seon-port")})
        (do
          (when-let [old @!server]
            ;; Exists but not listening (closed/dead) — replace it.
            (try (.close old) (catch :default _ nil))
            (reset! !server nil))
          (let [;; LATE-BINDING wrapper: createServer captures the fn OBJECT,
                ;; so the wrapper re-reads `router/handle-request` on every
                ;; request. `handle-request` derefs the cached reitit
                ;; ring-handler, which `router/install!` rebuilds on every
                ;; serve hot-reload — so a reloaded route never 404s until
                ;; pod restart (the live 2026-06-10 agent-birth failure mode).
                server (.createServer http (fn [req res] (router/handle-request req res)))
                port   (requested-port)]
            (.once server "error"
                   (fn [err]
                     (log/error-console! "seon.web.serve"
                                         (str "listen failed on port " port) err)
                     (reject err)))
            (.listen server port (bind-host)
                     (fn []
                       (let [addr      (.address server)
                             bound     (.-port addr)
                             port-file (write-port-file! bound)]
                         (reset! !server server)
                         (log/info-console! "seon.web.serve"
                                            (str "listening on http://127.0.0.1:" bound)
                                            {:port-file port-file})
                         (resolve {:seon.web/port bound
                                   :seon.web/port-file port-file}))))))))))

(defn stop!
  "Close every SSE feed and await HTTP server shutdown."
  {:malli/schema [:=> [:cat] :any]}
  []
  (datastar/close-all-feeds!)
  (router/detach!)
  (if-let [server @!server]
    (js/Promise.
     (fn [resolve reject]
       (try
         (.close server
                 (fn [error]
                   (if error
                     (reject error)
                     (do
                       (reset! !server nil)
                       (resolve nil)))))
         (catch :default error
           (reject error)))))
    (js/Promise.resolve nil)))
