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
    [seon.db.branch :as branch]
    [seon.db.restore :as db.restore]
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
  ([_req res]
   (handle-readiness! nil _req res))
  ([restore-completion-result _req res]
   (let [restore? (some? restore-completion-result)
         completion (::db.restore/completion restore-completion-result)
         completion-branch-head
         (::db.restore/completion-branch-head restore-completion-result)
         acquired
         (when (and restore? (db/attached?))
           (db.restore/acquire-completion!
            {::db.restore/plan-digest (::db.restore/plan-digest completion)}))]
     (-> (js/Promise.resolve acquired)
         (.then
          (fn [acquired]
            (let [restore-readiness
                  (when (and acquired (not (:seon.error/message acquired)))
                    (db.restore/readiness
                     {::db.restore/completion completion
                      ::db.restore/current-completion
                      (::db.restore/completion acquired)
                      ::db.restore/completion-branch-head completion-branch-head
                      ::db.restore/current-branch-head
                      (branch/head-from-database-value
                       (::db.restore/current-db acquired))
                      ::db.restore/publication-rows
                      (::db.restore/publication-rows acquired)
                      :seon.runtime.admission/state (admission/state)}))
                  ordinary-ready? (admission/available?)
                  body (cond
                         restore-readiness restore-readiness
                         restore? {::db.restore/ready? false
                                   ::db.restore/executable? false}
                         :else (assoc (admission/state)
                                      :seon.runtime.admission/available?
                                      ordinary-ready?
                                      ::db.restore/executable?
                                      ordinary-ready?))
                  ready? (if restore?
                           (true? (::db.restore/ready? body))
                           ordinary-ready?)]
              (write-status!
               res
               (if ready? 200 503)
               "application/edn; charset=utf-8"
               (pr-str body)))))
         (.catch
          (fn [error]
            (log/error-console! "seon.web.serve" "readiness failed" error)
            (write-status!
             res 503 "application/edn; charset=utf-8"
             (pr-str {::db.restore/ready? false
                      ::db.restore/executable? false}))))))))

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

(defn- ^:async clear-agent! [agent-id]
  (let [database (await (db/db))]
    (if (:seon.error/message database)
      database
      (let [values
            (await
             (js/Promise.all
              #js [(db/query
                    {::db/db database
                     ::db/query
                     '[:find ?message
                       :in $ ?agent-id
                       :where
                       [?agent :seon.agent/id ?agent-id]
                       (or-join [?message ?agent]
                         [?message :seon.agent.message/from ?agent]
                         [?message :seon.agent.message/to ?agent])]
                     ::db/args [agent-id]})
                   (db/query
                    {::db/db database
                     ::db/query
                     '[:find ?eval
                       :in $ ?agent
                       :where
                       [?eval :seon.eval/agent ?agent]]
                     ::db/args [[:seon.agent/id agent-id]]})]))
            [message-rows eval-rows] (array-seq values)
            error (some #(when (:seon.error/message %) %) values)]
        (if error
          error
          (await
           (db/transact!
            {::db/db database
             ::db/expected-db database
             ::db/tx-data
             (into []
                   (map (fn [entity-id] [:db/retractEntity entity-id]))
                   (concat (map first message-rows)
                           (map first eval-rows)))})))))))

(defn- handle-clear! [req res]
  (let [agent-id (or (query-param req "agent") (db/current-agent-id))]
    (if-not agent-id
      (write-status! res 400 "text/plain; charset=utf-8"
                     "missing 'agent' query param (no agent-id in scope)")
      (-> (clear-agent! agent-id)
          (.then
           (fn [result]
             (if (:seon.error/message result)
               (do
                 (log/error-console! "seon.web.serve" "/clear refused"
                                     result)
                 (write-status! res 500 "text/plain; charset=utf-8"
                                (:seon.error/message result)))
               (write-status! res 204 "text/plain; charset=utf-8" ""))))
          (.catch
           (fn [error]
             (log/error-console! "seon.web.serve" "/clear threw" error)
             (write-status! res 500 "text/plain; charset=utf-8"
                            (str error))))))))

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
            (let [message (or (:seon.error/message result)
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
  (-> (run/current-run {:seon.agent/id agent-id})
      (.then (fn [current]
               (if (:seon.error/message current)
                 current
                 (if current
                   (run/close-run! {:seon.agent.run/id
                                    (:seon.agent.run/id current)
                                    :seon.agent.run/closed-reason :completed})
                   nil))))
      (.then (fn [result]
               (if-not (:seon.error/message result)
                 (do (log/info-console! "seon.web.serve"
                                        "POST /agent/<id>/complete OK"
                                        {:agent agent-id})
                     (write-status! res 200 "text/plain; charset=utf-8"
                                    (str agent-id)))
                 (let [error (:seon.error/message result)]
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

(defn- ^:async agent-run-timeout-ms [database agent-id requested]
  (if requested
    requested
    (await
     (run/effective-deadline-ms
      (cond-> {::db/db database}
        agent-id (assoc :seon.agent/id agent-id))))))

(defn- ^:async latest-run-start-ms
  "Wall-clock ms of the agent's MOST-RECENTLY-STARTED run (open or closed) over
   the db value `db`, or 0 when none. The /agents/run poll uses this to reject
   the agent's PRE-INJECTION state: `:idle` alone is ambiguous (an idle agent
   has no open run BEFORE our message wakes it), so we only accept an idle
   whose latest run started at/after the injection — i.e. the run our message
   woke has opened and closed."
  [database aid]
  (let [rows (await
              (db/query {::db/db database
                         ::db/query '[:find ?started :in $ ?aid :where
                                      [?a :seon.agent/id ?aid]
                                      [?r :seon.agent.run/agent ?a]
                                      [?r :seon.agent.run/started-at ?started]]
                         ::db/args [aid]}))]
    (if (:seon.error/message rows)
      rows
      (->> rows
           (map (fn [[^js started]] (.getTime started)))
           (reduce max 0)))))

(defn- database-json
  "JSON-safe external projection of one ordinary database value."
  [database]
  {:db_name (:db-name database)
   :t (:t database)
   :as_of (:as-of database)
   :since (:since database)
   :history (:history database)
   :commit_id (str (:datahike/commit-id database))})

(defn- turn-evidence-row
  "Stable external projection of captured turn prompts and raw replies."
  [turn-id bundle]
  (cond-> {:turn_id turn-id
           :ok (:seon.agent.debug/ok? bundle)}
    (:seon.agent.turn/status bundle)
    (assoc :status (name (:seon.agent.turn/status bundle)))
    (:seon.agent.turn/at bundle)
    (assoc :at (.toISOString ^js (:seon.agent.turn/at bundle)))
    (:seon.agent.turn/rendered-tx bundle)
    (assoc :rendered_transaction (:seon.agent.turn/rendered-tx bundle))
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
    (assoc :capture_error (:seon.agent.debug/error bundle))))

(defn- ^:async turn-evidence
  [database turn-ids]
  (let [bundles
        (await
         (js/Promise.all
          (clj->js
           (mapv #(agent-debug/turn {::db/db database
                                     :seon.agent.turn/id %})
                 turn-ids))))]
    (if-let [error (some #(when (:seon.error/message %) %) bundles)]
      error
      (mapv turn-evidence-row turn-ids bundles))))

(def ^:private attempt-pull-pattern
  '[:seon.ai.attempt/ordinal
    :seon.ai.attempt/provider
    :seon.ai.attempt/adapter
    :seon.ai.attempt/requested-model
    :seon.ai.attempt/temperature
    :seon.ai.attempt/max-tokens
    :seon.ai.attempt/thinking
    :seon.ai.attempt/endpoint
    :seon.ai.attempt/adapter-timeout-ms
    :seon.ai.attempt/outer-timeout-ms
    :seon.ai.attempt/stream?
    :seon.ai.attempt/extra-body-digest
    :seon.ai.attempt/dg-backend
    :seon.ai.attempt/api-key-env
    :seon.ai.attempt/credential-class
    :seon.ai.attempt/outcome
    :seon.ai.attempt/error-status
    :seon.ai.attempt/response-model
    :seon.ai.attempt/system-fingerprint
    :seon.ai.attempt/request-id
    :seon.ai.attempt/evidence-error])

(def ^:private required-attempt-attrs
  #{:seon.ai.attempt/ordinal
    :seon.ai.attempt/provider
    :seon.ai.attempt/adapter
    :seon.ai.attempt/outer-timeout-ms
    :seon.ai.attempt/stream?
    :seon.ai.attempt/outcome})

(defn- attempt-json
  [turn-id attempt historical-config-valid?]
  (cond->
      {:turn_id turn-id
       :ordinal (:seon.ai.attempt/ordinal attempt)
       :historical_config_valid historical-config-valid?
       :provider (name (:seon.ai.attempt/provider attempt))
       :adapter (name (:seon.ai.attempt/adapter attempt))
       :outer_timeout_ms (:seon.ai.attempt/outer-timeout-ms attempt)
       :stream (:seon.ai.attempt/stream? attempt)
       :outcome (name (:seon.ai.attempt/outcome attempt))}
      (contains? attempt :seon.ai.attempt/requested-model)
      (assoc :requested_model (:seon.ai.attempt/requested-model attempt))
      (contains? attempt :seon.ai.attempt/temperature)
      (assoc :temperature (:seon.ai.attempt/temperature attempt))
      (contains? attempt :seon.ai.attempt/max-tokens)
      (assoc :max_tokens (:seon.ai.attempt/max-tokens attempt))
      (contains? attempt :seon.ai.attempt/thinking)
      (assoc :thinking (:seon.ai.attempt/thinking attempt))
      (contains? attempt :seon.ai.attempt/endpoint)
      (assoc :endpoint (:seon.ai.attempt/endpoint attempt))
      (contains? attempt :seon.ai.attempt/adapter-timeout-ms)
      (assoc :adapter_timeout_ms
             (:seon.ai.attempt/adapter-timeout-ms attempt))
      (contains? attempt :seon.ai.attempt/extra-body-digest)
      (assoc :extra_body_digest
             (:seon.ai.attempt/extra-body-digest attempt))
      (contains? attempt :seon.ai.attempt/dg-backend)
      (assoc :dg_backend (name (:seon.ai.attempt/dg-backend attempt)))
      (contains? attempt :seon.ai.attempt/api-key-env)
      (assoc :api_key_env (:seon.ai.attempt/api-key-env attempt))
      (contains? attempt :seon.ai.attempt/credential-class)
      (assoc :credential_class
             (name (:seon.ai.attempt/credential-class attempt)))
      (contains? attempt :seon.ai.attempt/error-status)
      (assoc :error_status (:seon.ai.attempt/error-status attempt))
      (contains? attempt :seon.ai.attempt/response-model)
      (assoc :response_model (:seon.ai.attempt/response-model attempt))
      (contains? attempt :seon.ai.attempt/system-fingerprint)
      (assoc :system_fingerprint
             (:seon.ai.attempt/system-fingerprint attempt))
      (contains? attempt :seon.ai.attempt/request-id)
      (assoc :request_id (:seon.ai.attempt/request-id attempt))
      (contains? attempt :seon.ai.attempt/evidence-error)
      (assoc :evidence_error (:seon.ai.attempt/evidence-error attempt))))

(def ^:private comparable-attempt-keys
  [:provider :adapter :requested_model :temperature :max_tokens :thinking
   :endpoint :adapter_timeout_ms :outer_timeout_ms :stream
   :extra_body_digest :dg_backend :api_key_env])

(def ^:private attempt-config-attr-pairs
  [[:seon.ai/provider :seon.ai.attempt/provider]
   [:seon.ai/model :seon.ai.attempt/requested-model]
   [:seon.ai/temperature :seon.ai.attempt/temperature]
   [:seon.ai/max-tokens :seon.ai.attempt/max-tokens]
   [:seon.ai/thinking :seon.ai.attempt/thinking]
   [:seon.ai/timeout-ms :seon.ai.attempt/adapter-timeout-ms]
   [:seon.ai/extra-body-digest :seon.ai.attempt/extra-body-digest]
   [:seon.ai/dg-backend :seon.ai.attempt/dg-backend]
   [:seon.ai/api-key-env :seon.ai.attempt/api-key-env]])

(defn- resolved-attempt-config
  [config]
  (let [provider (:seon.ai/provider config)
        endpoint-required? (contains? #{:deepseek :openai-compat} provider)
        endpoint (when endpoint-required?
                   (when-let [cap
                              (:seon.config.model-transport/endpoint-cap config)]
                     (some-> (:seon.ai/base-url config)
                             (ai/openai-request-endpoint cap))))]
    (when (and (or (not endpoint-required?) (string? endpoint))
               (schema/valid-candidate-value? :seon.ai/resolved-config config))
      (cond->
        (into {}
              (keep (fn [[config-attr attempt-attr]]
                      (when (contains? config config-attr)
                        [attempt-attr (get config config-attr)])))
              attempt-config-attr-pairs)
        endpoint-required?
        (assoc :seon.ai.attempt/endpoint endpoint)))))

(def ^:private response-identity-attempt-attrs
  [:seon.ai.attempt/response-model
   :seon.ai.attempt/system-fingerprint
   :seon.ai.attempt/request-id
   :seon.ai.attempt/evidence-error])

(defn- response-identity-valid?
  [attempt resolved-config]
  (let [cap (:seon.config.model-transport/response-identity-cap
              resolved-config)
        present (select-keys attempt response-identity-attempt-attrs)]
    (if (contains? resolved-config
                   :seon.config.model-transport/response-identity-cap)
      (and (int? cap)
           (pos? cap)
           (every? (fn [[_ value]]
                     (and (string? value) (<= (count value) cap)))
                   present))
      (empty? present))))

(defn- attempt-config-matches?
  [attempt expected]
  (and (map? expected)
       (= (select-keys attempt
                       (conj (set (map second attempt-config-attr-pairs))
                             :seon.ai.attempt/endpoint))
          expected)))

(defn- ^:async historical-turn-valid?
  [database agent-id rendered-tx attempts]
  (if-not (and (int? rendered-tx) (<= rendered-tx (:t database)))
    false
    (let [historical (db/as-of database rendered-tx)
          values
          (await
           (js/Promise.all
            #js [(db/pull {::db/db historical
                           ::db/pull-pattern (ai/config-pull-pattern)
                           ::db/ref [:seon.ai/id "config"]})
                 (db/pull {::db/db historical
                           ::db/pull-pattern (ai/agent-config-pull-pattern)
                           ::db/ref [:seon.agent/id agent-id]})
                 (db/pull {::db/db historical
                           ::db/pull-pattern
                           (into [:seon.config/repl-mode]
                                 (ai/model-transport-pull-pattern))
                           ::db/ref [:seon.config/id config/cluster-config-id]})]))
          [config-row agent-row cluster-row] (array-seq values)]
      (if (some :seon.error/message [config-row agent-row cluster-row])
        false
        (let [resolved
              (:seon.ai/resolved-config
               (ai/resolved-config-from-rows
                (merge config-row cluster-row) agent-row))
              stream? (= :stream (:seon.config/repl-mode cluster-row))]
          (every?
           (fn [attempt]
             (and (= stream? (:seon.ai.attempt/stream? attempt))
                  (response-identity-valid? attempt resolved)
                  (= (:seon.ai.attempt/adapter attempt)
                     (ai/resolved-adapter resolved))
                  (attempt-config-matches?
                   attempt (resolved-attempt-config resolved))))
           attempts))))))

(defn- project-model-transport-rows
  "Pure bounded projection over rows selected from one final database value."
  [turn-rows rows pull-row historical-valid? cap]
  (let [attempt-eids-by-turn
        (reduce (fn [grouped [turn-eid attempt-eid]]
                  (update grouped turn-eid conj attempt-eid))
                {} rows)
        valid-row? (fn [attempt]
                     (and (every? #(contains? attempt %)
                                  required-attempt-attrs)
                          (schema/valid-candidate-value?
                            :seon.ai.attempt/entity attempt)))
        ordered? (fn [attempts]
                   (= (mapv :seon.ai.attempt/ordinal attempts)
                      (vec (range (count attempts)))))
        raw-turns
        (mapv (fn [[turn-eid turn-id]]
                (let [attempt-eids
                      (->> (get attempt-eids-by-turn turn-eid [])
                           (sort-by (comp :seon.ai.attempt/ordinal pull-row))
                           vec)
                      attempts (mapv pull-row attempt-eids)]
                  {:turn_id turn-id
                   :valid (and (ordered? attempts)
                               (every? valid-row? attempts)
                               (every? #(true? (historical-valid? %))
                                       attempt-eids))
                   :attempts attempts
                   :attempt-eids attempt-eids}))
              (map (fn [[turn-eid turn-id & _]] [turn-eid turn-id]) turn-rows))
        attempts (mapcat :attempts raw-turns)]
    (cond
      (empty? attempts)
      {:status "absent"}

      (not-every? :valid raw-turns)
      {:status "malformed"}

      :else
      (let [projected-turns
            (mapv (fn [{:keys [turn_id attempts attempt-eids]}]
                    {:turn_id turn_id
                     :attempts
                     (mapv (fn [attempt-eid attempt]
                             (attempt-json turn_id attempt
                                           (true? (historical-valid? attempt-eid))))
                           attempt-eids attempts)})
                  raw-turns)
            projected-attempts (mapcat :attempts projected-turns)
            drift? (> (count
                        (distinct
                          (map #(select-keys % comparable-attempt-keys)
                               projected-attempts)))
                      1)
            content (pr-str projected-turns)
            chars (count content)
            projected-tokens (tokens/estimate content)]
        (if (> chars cap)
          {:status "oversized"
           :chars chars
           :tokens projected-tokens}
          {:status "inline"
           :chars chars
           :tokens projected-tokens
           :transport_drift drift?
           :turns projected-turns})))))

(defn- ^:async project-model-transport-evidence
  "Bounded ordered provider-attempt proof from the run's final database value."
  [database agent-id turn-rows]
  (let [cluster-row
        (await
         (db/pull {::db/db database
                   ::db/pull-pattern [:seon.config/id
                                      :seon.config.render/database-edn-cap]
                   ::db/ref [:seon.config/id config/cluster-config-id]}))
        turn-eids (mapv first turn-rows)]
    (if (:seon.error/message cluster-row)
      {:status "malformed"}
      (let [cap (config/database-edn-cap cluster-row)
            rows (if (seq turn-eids)
                   (await
                    (db/query {::db/db database
                               ::db/query
                               '[:find ?turn ?attempt
                                 :in $ [?turn ...] :where
                                 [?turn :seon.agent.turn/llm-attempts ?attempt]]
                               ::db/args [turn-eids]}))
                   [])]
        (if (:seon.error/message rows)
          {:status "malformed"}
          (let [attempt-eids (into [] (distinct) (map second rows))
                pulled-attempts
                (if (seq attempt-eids)
                  (await
                   (db/pull-many {::db/db database
                                  ::db/pull-pattern attempt-pull-pattern
                                  ::db/refs attempt-eids}))
                  [])]
            (if (or (:seon.error/message pulled-attempts)
                    (some :seon.error/message pulled-attempts))
              {:status "malformed"}
              (let [attempts (zipmap attempt-eids pulled-attempts)
                    attempts-by-turn
                    (reduce (fn [grouped [turn-eid attempt-eid]]
                              (update grouped turn-eid conj
                                      (get attempts attempt-eid)))
                            {} rows)
                    rendered-tx-by-turn
                    (into {} (map (fn [[turn-eid _ _ _ rendered-tx]]
                                    [turn-eid rendered-tx])) turn-rows)
                    turn-validity
                    (into {}
                          (await
                           (js/Promise.all
                            (clj->js
                             (mapv
                              (fn [turn-eid]
                                (-> (historical-turn-valid?
                                     database agent-id
                                     (get rendered-tx-by-turn turn-eid)
                                     (get attempts-by-turn turn-eid []))
                                    (.then (fn [valid?] [turn-eid valid?]))))
                              turn-eids)))))
                    turn-eid-by-attempt
                    (into {} (map (fn [[turn-eid attempt-eid]]
                                    [attempt-eid turn-eid])) rows)]
                (project-model-transport-rows
                 turn-rows rows
                 #(get attempts %)
                 #(true? (get turn-validity
                              (get turn-eid-by-attempt %)))
                 cap)))))))))

(defn- project-eval-evidence
  "Stable external projection of selected eval rows."
  [rows turn-eids pull-row]
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
               (assoc :narration (:seon.eval/narration row))))))))

(defn- ^:async eval-evidence
  "Stable external projection of one request window's evaluated forms."
  [database turn-eids]
  (let [rows (if (seq turn-eids)
               (await
                (db/query {::db/db database
                           ::db/query '[:find ?e ?t ?turn-id ?id ?at ?eval-t
                                        :in $ [?t ...] :where
                                        [?t :seon.agent.turn/id ?turn-id]
                                        [?t :seon.agent.turn/evals ?e]
                                        [?e :seon.eval/id ?id ?eval-t]
                                        [?e :seon.eval/at ?at]]
                           ::db/args [(vec turn-eids)]}))
               [])]
    (if (:seon.error/message rows)
      rows
      (let [eval-eids (mapv first rows)
            pulled
            (if (seq eval-eids)
              (await
               (db/pull-many {::db/db database
                              ::db/pull-pattern
                              [:seon.eval/source :seon.eval/ok?
                               :seon.eval/narration]
                              ::db/refs eval-eids}))
              [])]
        (if (or (:seon.error/message pulled)
                (some :seon.error/message pulled))
          (or (when (:seon.error/message pulled) pulled)
              (some #(when (:seon.error/message %) %) pulled))
          (let [by-eid (zipmap eval-eids pulled)]
            (project-eval-evidence rows turn-eids #(get by-eid %))))))))

(defn- model-config-json [resolved-config]
  (let [{:seon.ai/keys [provider model temperature max-tokens thinking]}
        resolved-config]
    (cond-> {:provider (name provider)}
      (contains? resolved-config :seon.ai/model)
      (assoc :model model)
      (contains? resolved-config :seon.ai/temperature)
      (assoc :temperature temperature)
      (contains? resolved-config :seon.ai/max-tokens)
      (assoc :max_tokens max-tokens)
      (contains? resolved-config :seon.ai/thinking)
      (assoc :thinking thinking))))

(defn- ^:async turn-rows-with-rendered-tx
  "Attach each turn's native rendered transaction without dropping malformed
   turns from the final request window."
  [database turn-rows]
  (if (empty? turn-rows)
    []
    (let [pulled
          (await
           (db/pull-many {::db/db database
                          ::db/pull-pattern [:seon.agent.turn/rendered-tx]
                          ::db/refs (mapv first turn-rows)}))]
      (cond
        (:seon.error/message pulled) pulled
        (not= (count pulled) (count turn-rows))
        {:seon.error/message
         "Turn rendered-transaction acquisition returned the wrong row count."
         :seon.error/kind :core-bug}
        :else
        (mapv (fn [row turn]
                (conj row (:seon.agent.turn/rendered-tx turn)))
              turn-rows pulled)))))

(defn- ^:async final-agent-task-result
  [database aid injected-at elapsed timeout?]
  (let [values
        (await
         (js/Promise.all
          #js [(db/query {::db/db database
                          ::db/query '[:find ?r ?started :in $ ?aid :where
                                       [?agent :seon.agent/id ?aid]
                                       [?r :seon.agent.run/agent ?agent]
                                       [?r :seon.agent.run/started-at ?started]]
                          ::db/args [aid]})
               (db/query {::db/db database
                          ::db/query '[:find ?turn ?id ?at ?run
                                       :in $ ?aid :where
                                       [?agent :seon.agent/id ?aid]
                                       [?run :seon.agent.run/agent ?agent]
                                       [?turn :seon.agent.turn/run ?run]
                                       [?turn :seon.agent.turn/id ?id]
                                       [?turn :seon.agent.turn/at ?at]]
                          ::db/args [aid]})
               (db/query {::db/db database
                          ::db/query '[:find ?at ?content :in $ ?aid :where
                                       [?agent :seon.agent/id ?aid]
                                       [?user :seon.user/id "user"]
                                       [?message :seon.agent.message/from ?agent]
                                       [?message :seon.agent.message/to ?user]
                                       [?message :seon.agent.message/at ?at]
                                       [?message :seon.agent.message/content ?content]]
                          ::db/args [aid]})
               (db/pull {::db/db database
                         ::db/pull-pattern (ai/config-pull-pattern)
                         ::db/ref [:seon.ai/id "config"]})
               (db/pull {::db/db database
                         ::db/pull-pattern (ai/agent-config-pull-pattern)
                         ::db/ref [:seon.agent/id aid]})
               (db/pull {::db/db database
                         ::db/pull-pattern (ai/model-transport-pull-pattern)
                         ::db/ref [:seon.config/id config/cluster-config-id]})]))
        [run-rows turn-identities reply-rows config-row agent-row cluster-row]
        (array-seq values)
        acquired-error
        (some #(when (:seon.error/message %) %) (array-seq values))
        all-turn-rows
        (if acquired-error
          acquired-error
          (await (turn-rows-with-rendered-tx database turn-identities)))
        error (or acquired-error
                  (when (:seon.error/message all-turn-rows) all-turn-rows))]
    (if error
      {:error (:seon.error/message error)}
      (let [run-eids (into #{}
                           (keep (fn [[run-eid ^js started]]
                                   (when (>= (.getTime started) injected-at)
                                     run-eid)))
                           run-rows)
            turn-rows (->> all-turn-rows
                           (filter #(contains? run-eids (nth % 3)))
                           (sort-by (fn [[_ id ^js at]] [(.getTime at) id]))
                           vec)
            turn-eids (into #{} (map first) turn-rows)
            turn-ids (mapv second turn-rows)
            eval-rows (await (eval-evidence database turn-eids))
            turn-proof (await (turn-evidence database turn-ids))
            transport-proof
            (await (project-model-transport-evidence database aid turn-rows))
            evidence-error
            (some #(when (:seon.error/message %) %)
                  [eval-rows turn-proof transport-proof])
            resolved
            (:seon.ai/resolved-config
             (ai/resolved-config-from-rows
              (merge config-row cluster-row) agent-row))
            reply (->> reply-rows
                       (filter (fn [[^js at]]
                                 (>= (.getTime at) injected-at)))
                       (sort-by (fn [[^js at]] (.getTime at)))
                       last second)
            closed-reason (when-not timeout?
                            (await (derive/last-closed-reason database aid)))]
        (if evidence-error
          {:error (:seon.error/message evidence-error)}
          (cond-> {:agent_id aid
                   :turns (count turn-eids)
                   :evals (count eval-rows)
                   :reply (or reply "")
                   :elapsed_ms elapsed
                   :database (database-json database)
                   :turn_evidence turn-proof
                   :model_transport_evidence transport-proof
                   :eval_evidence eval-rows
                   :model_config (model-config-json resolved)
                   :closed_reason (if timeout? "timeout" (str closed-reason))}
            timeout? (assoc :timed_out true)))))))

(defn- ^:async run-agent-task!
  "Drive one task and derive its response from one final database value."
  [agent-id input timeout-ms]
  (let [initial-database (await (db/db))
        reuse? (some? agent-id)
        existing
        (when (and reuse? (not (:seon.error/message initial-database)))
          (await
           (db/query {::db/db initial-database
                      ::db/query '[:find ?agent . :in $ ?id :where
                                   [?agent :seon.agent/id ?id]]
                      ::db/args [agent-id]})))]
    (cond
      (:seon.error/message initial-database)
      {:error (:seon.error/message initial-database)}

      (:seon.error/message existing)
      {:error (:seon.error/message existing)}

      (and reuse? (nil? existing))
      {:error (str "unknown agent_id: " agent-id)}

      :else
      (let [minted (when-not reuse? (await (agent/start! {})))
            aid (or agent-id (:seon.agent/id minted))]
        (if (or (:seon.error/message minted) (nil? aid))
          {:error (or (:seon.error/message minted)
                      (:seon.agent.runtime/error minted)
                      "agent mint returned no id")}
          (let [start (js/Date.now)
                injected-at start
                message-result
                (await
                 (agent/message!
                  {:seon.agent.message/from agent/user-ref
                   :seon.agent.message/to [[:seon.agent/id aid]]
                   :seon.agent.message/content input}))]
            (if (:seon.error/message message-result)
              {:error (:seon.error/message message-result)}
              (do
                (log/info-console! "seon.web.serve" "POST /agents/run — task in"
                                   {:agent aid :reused reuse?
                                    :tokens (tokens/estimate (str input))})
                (loop []
                  (await (js/Promise. (fn [resolve]
                                       (js/setTimeout resolve 1500))))
                  (let [database (await (db/db))
                        observations
                        (when-not (:seon.error/message database)
                          (await
                           (js/Promise.all
                            #js [(derive/derive-state database aid)
                                 (latest-run-start-ms database aid)])))
                        [state latest-start] (when observations
                                               (array-seq observations))
                        error (or (when (:seon.error/message database) database)
                                  (when (:seon.error/message state) state)
                                  (when (:seon.error/message latest-start)
                                    latest-start))
                        elapsed (- (js/Date.now) start)
                        done? (and (= :idle state)
                                   (>= latest-start injected-at))
                        timeout? (> elapsed timeout-ms)]
                    (cond
                      error {:error (:seon.error/message error)}
                      (not (or done? timeout?)) (recur)
                      :else
                      (let [current
                            (when timeout?
                              (await
                               (run/current-run
                                {::db/db database :seon.agent/id aid})))
                            close-result
                            (cond
                              (:seon.error/message current) current
                              current
                              (await
                               (run/close-run!
                                {:seon.agent.run/id
                                 (:seon.agent.run/id current)
                                 :seon.agent.run/closed-reason :superseded}))
                              :else nil)]
                        (if (:seon.error/message close-result)
                          {:error (:seon.error/message close-result)}
                          (let [final-database (await (db/db))]
                            (if (:seon.error/message final-database)
                              {:error (:seon.error/message final-database)}
                              (await
                               (final-agent-task-result
                                final-database aid injected-at elapsed
                                timeout?)))))))))))))))))

(defn- handle-agent-run! [req res]
  (-> (read-body req)
      (.then
       (fn [body]
         (let [parsed (js->clj (js/JSON.parse body))
               input (get parsed "input")
               agent-id (get parsed "agent_id")
               requested-timeout-ms (get parsed "timeout_ms")]
           (-> (db/db)
               (.then
                (fn [database]
                  (if (:seon.error/message database)
                    database
                    (agent-run-timeout-ms database agent-id
                                          requested-timeout-ms))))
               (.then
                (fn [timeout-ms]
                  (if (:seon.error/message timeout-ms)
                    {:error (:seon.error/message timeout-ms)}
                    (-> (run-agent-task! agent-id input timeout-ms)
                        (.then
                         (fn [result]
                           (assoc result
                                  :effective_timeout_ms timeout-ms
                                  :timeout_source
                                  (if (some? requested-timeout-ms)
                                    "request"
                                    "database"))))))))))))
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
                     ;; single entry point and returns concise domain data or
                     ;; a direct error value.
                     (-> (agent/message!
                           {:seon.agent.message/from    agent/user-ref
                            :seon.agent.message/to      [[:seon.agent/id agent-id]]
                            :seon.agent.message/content text})
                         (.then (fn [{msg-id :seon.agent.message/id
                                     hops :seon.agent.message/hops
                                     :as result}]
                                  (if-not (:seon.error/message result)
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
                                                          result)
                                      (write-status! res 422 "text/plain; charset=utf-8"
                                                     (str "chat refused: "
                                                          (:seon.error/message result)))))))
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
  (let [agent-id (or (query-param req "agent") (db/current-agent-id))]
    (if-not agent-id
      (write-status! res 400 "text/plain; charset=utf-8"
                     "missing 'agent' query param (no agent-id in scope)")
      (-> (run/current-run {:seon.agent/id agent-id})
          (.then
           (fn [current]
             (cond
               (:seon.error/message current) current
               (nil? current) nil
               :else
               (run/pause! {:seon.agent/id agent-id
                            :seon.agent.run/id
                            (:seon.agent.run/id current)}))))
          (.then
           (fn [result]
             (if (:seon.error/message result)
               (do
                 (log/error-console! "seon.web.serve" "/stop refused" result)
                 (write-status! res 422 "text/plain; charset=utf-8"
                                (:seon.error/message result)))
               (write-status! res 204 "text/plain; charset=utf-8" ""))))
          (.catch
           (fn [error]
             (log/error-console! "seon.web.serve" "/stop threw" error)
             (write-status! res 500 "text/plain; charset=utf-8"
                            (str error))))))))

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
                 ;; Success is a derived state keyword; failures are direct
                 ;; error values.
                 (if (keyword? result)
                   (do
                     (log/info-console! "seon.web.serve" "POST /resume — re-driving"
                                        {:agent agent-id :state result})
                     (write-status! res 204 "text/plain; charset=utf-8" ""))
                   (let [error (:seon.error/message result)]
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

(defn- bounded-operator-error
  "Bound one operator failure to a stable EDN value without stack data."
  [request error]
  (let [message (or (:seon.error/message error)
                    (some-> error .-message)
                    (str error))]
    {:my.blob/ok? false
     :my.blob/target-branch-head (:my.blob/target-branch-head request)
     :my.blob/error (subs message 0 (min 1024 (count message)))}))

(defn- execute-blob-operator!
  "Acquire retained hashes from one database value and execute one request."
  [request]
  (let [target-branch-head (:my.blob/target-branch-head request)]
    (-> (db/db)
        (.then
          (fn [database]
            (cond
              (:seon.error/message database)
              database

              (or (not= (:t database) (::branch/basis-t target-branch-head))
                  (not= (:datahike/commit-id database)
                        (::branch/commit-id target-branch-head)))
              {:seon.error/message
               "The pod database value does not match the retained restore target."}

              :else
              (db/query
               {::db/db database
                ::db/query
                '[:find [?hash ...]
                  :where
                  [_ :my.blob/hash ?hash]]
                ::db/max-results 100000
                ::db/max-result-weight 4194304}))))
        (.then
         (fn [retained-hashes]
           (if (:seon.error/message retained-hashes)
             (bounded-operator-error request retained-hashes)
             (case (:my.blob/operator-operation request)
               :my.blob.operator.operation/observe-retained
               (blob/observe-retained
                {:my.blob/target-branch-head target-branch-head
                 :my.blob/retained-hashes retained-hashes})

               :my.blob.operator.operation/materialize-retained
               (blob/materialize-retained-intent!
                (-> request
                    (dissoc :my.blob/operator-operation)
                    (assoc :my.blob/retained-hashes retained-hashes))))))))))

(defn- handle-operator-blobs!
  "Observe or materialize an exact retained blob set and return closed EDN."
  [req res]
  (-> (read-body req)
      (.then
        (fn [body]
          (let [request (reader/read-string body)]
            (if (schema/valid-candidate-value? :my.blob/operator-request request)
              (execute-blob-operator! request)
              (bounded-operator-error
                request
                (js/Error. "invalid retained-blob operator request"))))))
      (.then
        (fn [result]
          (write-status! res
                         (if (:my.blob/ok? result) 200 422)
                         "application/edn; charset=utf-8"
                         (pr-str result))))
      (.catch
        (fn [error]
          (let [result (bounded-operator-error {} error)]
            (write-status! res 422 "application/edn; charset=utf-8"
                           (pr-str result)))))))

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
   :seon.web.router/operator-blobs handle-operator-blobs!
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

(schema/register! ::readiness-only? :boolean)
(schema/register! ::restore-completion-result ::db.restore/record-success)
(schema/register! ::start-request
                  [:map {:closed true}
                   [::readiness-only? {:optional true} ::readiness-only?]
                   [::restore-completion-result
                    {:optional true} ::restore-completion-result]])

(defn ^:async start!
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
  {:malli/schema [:=> [:cat ::start-request] :any]}
  [{::keys [readiness-only? restore-completion-result]}]
  (when-not (= (boolean readiness-only?)
               (boolean restore-completion-result))
    (throw
     (ex-info "Restore readiness requires exact completion evidence."
              {::readiness-only? readiness-only?
               ::restore-completion-result restore-completion-result
               :seon.error/kind :core-bug})))
  ;; The authority acknowledges the selective route interest at one immutable
  ;; database value. Compile that exact projection before HTTP admission so request
  ;; dispatch never performs a database read.
  (when-not readiness-only?
    (await (router/attach!)))
  (await
   (js/Promise.
    (fn [resolve reject]
      (if-let [live-addr (some-> @!server .address)]
        ;; Already listening — reuse (see docstring; a second
        ;; start-runtime! on the same pod must NOT bounce the server).
        (if (= (boolean readiness-only?)
               (boolean (gobj/get @!server "seonReadinessOnly")))
          (if (= restore-completion-result
                 (gobj/get @!server "seonRestoreCompletionResult"))
            (resolve {:seon.web/port      (.-port live-addr)
                      :seon.web/port-file
                      (or (.. js/process -env -SEON_PORT_FILE)
                          "tmp/seon-port")})
            (reject
              (ex-info "The HTTP server retains different restore evidence."
                       {::restore-completion-result
                        restore-completion-result})))
          (reject (ex-info "The HTTP server already owns another admission surface."
                           {::readiness-only? readiness-only?})))
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
                server
                (.createServer
                 http
                 (if readiness-only?
                   (fn [req res]
                     (if (and (= "GET" (.-method req))
                              (= "/_seon/ready" (.-url req)))
                       (handle-readiness! restore-completion-result req res)
                       (write-status! res 503 "text/plain; charset=utf-8"
                                      "Restore preparation is not executable.")))
                   (fn [req res] (router/handle-request req res))))
                port   (requested-port)]
            (gobj/set server "seonReadinessOnly" (boolean readiness-only?))
            (gobj/set server "seonRestoreCompletionResult"
                      restore-completion-result)
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
                                   :seon.web/port-file port-file})))))))))))

(defn ^:async stop!
  "Close every SSE feed and await HTTP server shutdown."
  {:malli/schema [:=> [:cat] :any]}
  []
  (datastar/close-all-feeds!)
  (await (router/detach!))
  (await
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
     (js/Promise.resolve nil))))
