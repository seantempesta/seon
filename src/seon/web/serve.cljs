(ns seon.web.serve
  "Pod-side HTTP+SSE server on a loopback ephemeral port.

   Per spec-05 §10.2 A-5 + §21.1 the pod hosts its own minimal HTTP
   surface so a browser (Chrome in Lane A dev, Tauri WebView in Lane B
   prod) can reach the agent UI without intermediate infrastructure.

   Routes (V0.5):
     GET  /                  → 302 redirect to /world (the converged human surface)
     GET  /css/output.css    → resources/public/css/output.css
     GET  /js/datastar.js    → resources/public/js/datastar.js
     GET  /sse               → SSE stream
     POST /chat              → A-8 (user message → message! with from = the user ref)

   ## Port discovery

   `start!` listens on a fixed port (default 7890, override via
   `SEON_PORT`; set to 0 for ephemeral allocation) and writes the
   actually-bound port to `$SEON_PORT_FILE` (default
   `tmp/seon-port` — project-local per CLAUDE.md). External tooling
   reads this file rather than
   parsing logs.

   ## V0.5 throwaway

   When V1+ lands the JVM seon server takes over HTTP+SSE rendering
   (it already has a similar pipeline in `seon.web.sse` per the
   2026-05-19 audit). This namespace becomes dev-mode only — a
   standalone-pod render path so we can iterate on agent code in
   Chrome without booting the full server stack. The CLJS pod's role
   in the V0.5 demo Tauri shell becomes 'eval core', not
   'HTTP server'.

   ## SSE connection registry

   A-6 will register each open SSE stream's `response` object in
   `!sse-connections` so the broadcast tx-listener can write
   `datastar-patch-elements` events. Today A-5 ships the registry +
   the connection-add-on-open + connection-remove-on-close lifecycle;
   broadcast.cljs gets to assume the registry exists."
  (:require
    ["node:http" :as http]
    ["node:fs" :as fs]
    ["node:path" :as path]
    [clojure.string :as str]
    [goog.object :as gobj]
    [seon.agent :as agent]
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.run :as run]
    [seon.db :as db]
    [seon.log :as log]
    [seon.platform :as platform]
    [seon.web.router :as router]))

;; ============================================================
;; Process-lifetime state
;; ============================================================

(defonce ^{:doc "The bound HTTP server, or nil before start!."}
  !server (atom nil))

(defonce ^{:doc "Connection registry — atom of vector of
                  `{:id <uuid> :res <http.ServerResponse>}` for every
                  open SSE stream. A-6 reads this to fan out
                  datastar-patch-elements events per tx."}
  !sse-connections (atom []))

(defn open-sse-connections
  "Public accessor — returns the current vector of open SSE
   connections. A-6 will close over this via `seon.db/listen!`."
  []
  @!sse-connections)

;; ============================================================
;; Agent creation — POST /agents/new
;;
;; The pod's boot path (`seon.client/start-agent!`) is the ONE way an
;; agent comes to life (conn, bootstrap-CLJS, replay, seed, boot!,
;; trigger). serve.cljs can't require seon.client (require cycle:
;; client → serve), so client INJECTS its start-agent! closure here at
;; load time via `set-create-agent-fn!`. No parallel creation
;; mechanism — the endpoint just calls the existing boot path.
;; ============================================================

(defonce ^{:doc "0-arity fn returning a Promise of
                  `{:seon.agent/id _ …}` — injected by seon.client at
                  load time (its start-agent! with the current llm-fn).
                  nil until the pod finishes loading."}
  !create-agent-fn (atom nil))

(defonce ^:private !create-in-flight (atom false))

(defn set-create-agent-fn!
  "Inject the agent-creation closure (seon.client/start-agent! with the
   pod's current llm-fn). Called at namespace-load time from
   seon.client — re-runs on hot reload so the closure tracks reloaded
   code."
  {:malli/schema [:=> [:cat fn?] :nil]}
  [f]
  (reset! !create-agent-fn f)
  nil)

;; ============================================================
;; Static serving
;;
;; Map URL prefix → disk root. The roots are seon BUILD ARTIFACTS, so
;; they resolve through `seon.platform/artifact-path`: CWD-relative
;; when the pod runs from the seon repo root (today's usage), under
;; SEON_RUNTIME_ROOT when a downstream pod runs from its own world
;; root.
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

(defn serve-root!
  "GET / — 302 to the world roster (/world). A Ring handler: takes the Ring
   request `r`, self-extracts the node res. Resolved LATE by the router
   (db->routes) from the seeded :seon.route/root datom, so it is PUBLIC — its
   symbol must resolve via eval/lookup-value at request time."
  [r]
  ;; `/world` (seon.web.datastar) is the converged human surface — the live
  ;; agent roster + per-agent canvas/tiles/chat. The root just lands the user
  ;; there.
  (let [^js res (:seon.http/node-res r)]
    (.writeHead res 302 #js {"Location"      "/world"
                             "Cache-Control" "no-store, no-cache, must-revalidate"})
    (.end res "")))

(defn- open-sse! [^js req ^js res]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  ;; Flush headers immediately so the browser registers the stream.
  ;; SSE-spec comment lines (begin with `:`) are ignored by clients.
  (.write res ": connected\n\n")
  ;; Register the connection so A-6's broadcast can write into it.
  (let [conn {:id (random-uuid) :res res :opened-at (js/Date.)}]
    (swap! !sse-connections conj conn)
    (log/info-console! "seon.web.serve" "SSE OPEN"
                       {:conn-id (str (:id conn))
                        :total   (count @!sse-connections)
                        :ua      (some-> req .-headers (aget "user-agent"))})
    (.on req "close"
         (fn []
           (swap! !sse-connections
                  (fn [conns] (vec (remove #(= (:id %) (:id conn)) conns))))
           (log/info-console! "seon.web.serve" "SSE CLOSE"
                              {:conn-id (str (:id conn))
                               :remaining (count @!sse-connections)})))))

;; ============================================================
;; POST /chat — inject a user message into the named agent's log.
;; The message tx wakes the agent's loop; it runs a turn, the LLM
;; responds, and the inspector morphs the view via SSE.
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
  "POST /agents/new — mint + start a NEW live agent via the injected
   boot path (`seon.client/start-agent!` — trigger armed, reachable via
   /chat, identical to the auto-boot agent). Responds 200 with the new
   agent id as plain text; the mission-control button navigates to
   `/agent/<id>`. One create at a time (boot is heavyweight: replay +
   core seed) — concurrent requests get 409."
  [req res]
  (let [f @!create-agent-fn]
    (cond
      (nil? f)
      (write-status! res 503 "text/plain; charset=utf-8"
                     "agent creation not wired yet (pod still booting)")

      @!create-in-flight
      (write-status! res 409 "text/plain; charset=utf-8"
                     "an agent is already being created — retry in a moment")

      :else
      (do
        (reset! !create-in-flight true)
        (log/info-console! "seon.web.serve" "POST /agents/new — creating agent" {})
        (-> (read-body req)
            (.then
              (fn [body]
                ;; Optional `purpose` form param (self-context spec
                ;; 2026-06-10) — the human's words seed the new agent's
                ;; :purpose section ("Your human created you for: …").
                ;; Absent/blank → the acquire-your-purpose placeholder.
                (let [purpose (some-> (get (parse-urlencoded (or body ""))
                                           "purpose")
                                      str/trim
                                      not-empty)]
                  (f (when purpose {:seon.agent/purpose purpose})))))
            (.then (fn [{id :seon.agent/id}]
                     (reset! !create-in-flight false)
                     (log/info-console! "seon.web.serve" "POST /agents/new OK"
                                        {:agent id})
                     (write-status! res 200 "text/plain; charset=utf-8" (str id))))
            (.catch (fn [err]
                      (reset! !create-in-flight false)
                      (log/error-console! "seon.web.serve" "/agents/new failed" err)
                      (write-status! res 500 "text/plain; charset=utf-8"
                                     (str "create agent failed: " err)))))))))

(defn- handle-complete-agent!
  "POST /agent/<id>/complete — external control: CLOSE the agent's open run
   `:completed` (derived state falls to `:idle`, the single wakeable parked
   state — a new message opens a fresh run). Same effect as the agent's own
   `complete` verb. When the agent has no open run it is already idle → 200
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
                                                         {:agent agent-id :text-len (count text)})
                                      ;; A DISTINCT intake
                                      ;; line per accepted message (the generic
                                      ;; POST log above only records text-len).
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

(defn same-origin?
  "True (ALLOW) when no `Origin` header is present (curl / the agent / any
   non-browser caller) OR the request is genuinely same-origin; false (REFUSE)
   when an Origin IS present and is cross-site — the CSRF case.

   Same-origin is decided by matching the Origin's host to the request's own
   `Host` header (so it holds for loopback dev AND a Caddy/Tauri front that
   preserves Host). When no Host is available we fall back to allowing loopback
   origins only. `req` is an opaque Node IncomingMessage (Ring-style boundary,
   no Malli schema — same as the /call, inspector, and serve handlers)."
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
;; the SSE registry, the create-agent closure) and the same-origin? gate (a
;; test pins it). We INJECT both into router here. This call re-runs on
;; hot-reload, so the cached router always holds the freshly-reloaded
;; handler fns. createServer (below) dispatches every request through
;; `router/handle-request`.
;; ============================================================

;; `serve-root!` is NOT injected here — it is a SEEDED core route
;; (:seon.route/root → seon.web.serve/serve-root!), resolved late by the
;; router's db->routes. Only the non-core supplement handlers are injected.
(router/install!
  {:seon.web.router/sse           open-sse!
   :seon.web.router/static        serve-static!
   :seon.web.router/chat          handle-chat!
   :seon.web.router/stop          handle-stop!
   :seon.web.router/resume        handle-resume!
   :seon.web.router/clear         handle-clear!
   :seon.web.router/log           handle-log!
   :seon.web.router/create-agent  handle-create-agent!
   :seon.web.router/complete      handle-complete-agent!
   :seon.web.router/same-origin?  same-origin?})

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

(defn start!
  "Start the HTTP+SSE server on a loopback port. Returns a Promise
   resolving to:
     {:seon.web/port <int> :seon.web/port-file <abs-path>}

   Default port is 7890 (override via $SEON_PORT; set to 0 for
   ephemeral). Writes the bound port to $SEON_PORT_FILE (default
   `tmp/seon-port`). Idempotent — when a server is already LISTENING
   the call resolves with the existing binding (restarting would drop
   every open SSE stream AND kill the in-flight request when a second
   agent boots via POST /agents/new → start-agent! → start!). A dead
   (closed) server object is replaced.

   The server binds to 127.0.0.1 (loopback only). Browsers on the
   same machine can connect; nothing on the LAN sees the pod.

   If the requested port is in use, the listen fails fast — that's
   the expected behavior for a dev pod (only one instance at a time).
   To run multiple pods, set SEON_PORT=0 for ephemeral allocation."
  {:malli/schema [:=> [:cat] :any]}
  []
  (js/Promise.
    (fn [resolve reject]
      ;; Re-derive the router from the NOW-SEEDED route datoms. The top-level
      ;; (router/install!) ran at module load — before boot-seed! transacted
      ;; the :seon.route/* rows and before *conn* was set — so its router held
      ;; only the static supplement. start! runs AFTER boot-seed! (see
      ;; seon.client/start-agent!), so the live conn now carries the six core
      ;; routes; rebuild before the server accepts its first request.
      (router/rebuild!)
      (if-let [live-addr (some-> @!server .address)]
        ;; Already listening — reuse (see docstring; a second
        ;; start-agent! on the same pod must NOT bounce the server).
        (resolve {:seon.web/port      (.-port live-addr)
                  :seon.web/port-file (or (.. js/process -env -SEON_PORT_FILE)
                                          "tmp/seon-port")})
        (do
          (when-let [old @!server]
            ;; Exists but not listening (closed/dead) — replace it.
            (try (.close old) (catch :default _ nil))
            (reset! !server nil)
            (reset! !sse-connections []))
          (let [;; LATE-BINDING wrapper: createServer captures the fn OBJECT,
                ;; so the wrapper re-reads `router/handle-request` on every
                ;; request. `handle-request` derefs the cached reitit
                ;; ring-handler, which `router/install!` rebuilds on every
                ;; serve hot-reload — so a reloaded route never 404s until
                ;; pod restart (the live 2026-06-10 /agents/new failure mode).
                server (.createServer http (fn [req res] (router/handle-request req res)))
                port   (requested-port)]
            (.once server "error"
                   (fn [err]
                     (log/error-console! "seon.web.serve"
                                         (str "listen failed on port " port) err)
                     (reject err)))
            (.listen server port "127.0.0.1"
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
  "Close the HTTP server, clear the connection registry. Returns nil."
  []
  (when-let [server @!server]
    (.close server)
    (reset! !server nil))
  (reset! !sse-connections [])
  nil)
