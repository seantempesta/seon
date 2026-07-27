(ns seon.web.serve
  "Pod-side HTTP+SSE server on a loopback ephemeral port.

   Per spec-05 §10.2 A-5 + §21.1 the pod hosts its own minimal HTTP
   surface so a browser (Chrome in Lane A dev, Tauri WebView in Lane B
   prod) can reach the agent UI without intermediate infrastructure.

   Routes (V0.5):
     GET  /                  → root.s agent view (seeded :seon.route/root → datastar)
     GET  /css/output.css    → resources/public/css/output.css
     GET  /js/datastar.js    → resources/public/js/datastar.js

   ## Port discovery

   `start!` listens on a fixed port (default 7890, override via
   `SEON_PORT`; set to 0 for ephemeral allocation) and writes the
   actually-bound port to `$SEON_PORT_FILE` (default
   `tmp/seon-port` — project-local per CLAUDE.md). External tooling
   reads this file rather than
   parsing logs. Live views use the one normalized direct-stream feed registry in
   `seon.web.datastar`."
  (:require
    ["node:fs" :as fs]
    ["node:path" :as path]
    [cljs.reader :as reader]
    [cljs.tools.reader.edn :as tools-edn]
    [cljs.tools.reader.reader-types :as reader-types]
    [clojure.string :as str]
    [goog.object :as gobj]
    [my.blob :as blob]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.branch :as branch]
    [seon.db.restore :as db.restore]
    [seon.derive :as derive]
    [seon.error :as error]
    [seon.log :as log]
    [seon.platform :as platform]
    [seon.render :as render]
    [seon.render.core :as render.core]
    [seon.render.value :as render.value]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]
    [seon.ui.html :as html]
    [seon.web.datastar :as datastar]
    [seon.web.router :as router]
    [seon.web.value :as web-value]))

;; ============================================================
;; Process-lifetime state
;; ============================================================

(defonce ^{:doc "The bound HTTP server, or nil before start!."}
  !server (atom nil))

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

(defn- write-status! [_res code mime body]
  (js/Response.
   body
   #js {:status code
        :headers #js {"Content-Type" mime
                      "Cache-Control" "no-store, no-cache, must-revalidate"
                      "Pragma" "no-cache"
                      "Expires" "0"}}))

(defn- bounded-error-message [value]
  (let [message (str (or value "Internal server error."))]
    (subs message 0 (min 1024 (count message)))))

(defn- terminal-core-fault!
  "Persist one caught handler failure before returning the shared flat 500."
  [operation raw]
  (let [recorded (error/record! {:seon.error/raw raw
                                 :seon.error/fault :core})
        failure {:seon.error/message
                 (bounded-error-message (:seon.error/message recorded))
                 :seon.error/kind
                 (or (:seon.error/kind recorded) :core-bug)}]
    (log/error-console! "seon.web.serve" operation recorded)
    (write-status! nil 500 "application/json; charset=utf-8"
                   (js/JSON.stringify
                    #js {"seon.error/message"
                         (:seon.error/message failure)
                         "seon.error/kind"
                         (name (:seon.error/kind failure))}))))

(defn- through-terminal-fault-door
  "Run one HTTP handler behind the sole terminal core-fault catch."
  [operation thunk]
  (try
    (let [result (thunk)]
      (if (instance? js/Promise result)
        (.catch result #(terminal-core-fault! operation %))
        result))
    (catch :default error
      (terminal-core-fault! operation error))))

(defn- terminal-handler [operation handler]
  (fn [& args]
    (through-terminal-fault-door operation #(apply handler args))))

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

(defn ^:async ^:private serve-static! [res url]
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
        (let [full (.join path root safe)
              file (.file js/Bun full)]
          (if (await (.exists file))
            (write-status! res 200 (mime-type full) file)
            (write-status! res 404 "text/plain; charset=utf-8"
                           (str "Not found: " url))))))
    (write-status! res 404 "text/plain; charset=utf-8" (str "Not found: " url))))

;; ============================================================
;; Route handlers
;; ============================================================

(defn- read-body
  "Read a WHATWG Request body. Returns a Promise<String>."
  [^js req]
  (.text req))

;; ============================================================
;; GET /agent/{id}/value — one bounded, authorized value slice.
;; ============================================================

(def ^:private value-query-framing-max-bytes 32768)
(def ^:private value-path-framing-max-bytes 8192)
(def ^:private value-query-fields #{"eval" "entity" "path" "offset"})
(def ^:private value-eof (js-obj))

(def ^:private value-eval-owner-query
  '[:find ?eval .
    :in $ ?eval-id ?agent-id
    :where
    [?eval :seon.eval/id ?eval-id]
    [?eval :seon.eval/agent ?agent]
    [?agent :seon.agent/id ?agent-id]])

(defn- utf8-bytes [s]
  (.-length (.encode (js/TextEncoder.) s)))

(defn- valid-percent-framing? [s]
  (loop [index 0]
    (if (= index (count s))
      true
      (if (= "%" (subs s index (inc index)))
        (and (< (+ index 2) (count s))
             (boolean (re-matches #"[0-9A-Fa-f]{2}"
                                  (subs s (inc index) (+ index 3))))
             (recur (+ index 3)))
        (recur (inc index))))))

(defn- value-input-error []
  {:seon.error/message "Invalid or over-budget value request."
   :seon.error/kind :user-input})

(defn- value-core-error []
  {:seon.error/message "Value sampling is temporarily unavailable."
   :seon.error/kind :core-bug})

(defn- value-absent-error []
  {:seon.error/message "Value not found."
   :seon.error/kind :not-found})

(defn- raw-value-query
  "Validate fixed query framing before acquiring database policy."
  [ring-request]
  (try
    (let [raw (or (:query-string ring-request) "")]
      (when (or (> (utf8-bytes raw) value-query-framing-max-bytes)
                (not (valid-percent-framing? raw)))
        (throw (js/Error. "invalid framing")))
      (let [components (if (str/blank? raw) [] (str/split raw #"&" -1))
            url (js/URL. (.-url (:seon.http/request ring-request)))
            decoded (vec (es6-iterator-seq (.entries (.-searchParams url))))]
        (when-not (= (count components) (count decoded))
          (throw (js/Error. "ambiguous framing")))
        (let [fields
              (reduce
                (fn [fields [component [decoded-name decoded-value]]]
                  (let [equals (.indexOf component "=")
                        raw-name (if (neg? equals)
                                   component
                                   (subs component 0 equals))
                        raw-value (if (neg? equals)
                                    ""
                                    (subs component (inc equals)))]
                    (when (or (not= raw-name decoded-name)
                              (not (contains? value-query-fields decoded-name))
                              (contains? fields decoded-name)
                              (and (= "path" decoded-name)
                                   (> (utf8-bytes raw-value)
                                      value-path-framing-max-bytes)))
                      (throw (js/Error. "invalid query field")))
                    (assoc fields decoded-name
                           {:seon.web.serve/decoded decoded-value
                            :seon.web.serve/raw raw-value})))
                {}
                (map vector components decoded))
              selectors (filter #(contains? fields %) ["eval" "entity"])]
          (when-not (and (= 1 (count selectors))
                         (seq (get-in fields [(first selectors)
                                              :seon.web.serve/decoded])))
            (throw (js/Error. "invalid selector framing")))
          fields)))
    (catch :default _ (value-input-error))))

(defn- strict-path [text]
  (try
    (let [source (reader-types/string-push-back-reader text)
          value (tools-edn/read {:eof value-eof :readers {}} source)
          trailing (tools-edn/read {:eof value-eof :readers {}} source)]
      (when (and (vector? value)
                 (identical? value-eof trailing)
                 (= text (pr-str value))
                 (every? render.value/drill-path-segment? value)
                 (not-any? #(and (number? %)
                                 (or (not (js/Number.isFinite %))
                                     (js/Object.is % (js/Number "-0"))))
                           value))
        value))
    (catch :default _ nil)))

(defn- canonical-nonnegative-integer [text]
  (when (and (string? text) (re-matches #"(?:0|[1-9][0-9]*)" text))
    (let [value (js/Number text)]
      (when (js/Number.isSafeInteger value) value))))

(defn- configured-value-request
  "Decode one framed query under the acquired database policy."
  [fields effective-limits]
  (try
    (when (:seon.error/message fields)
      (throw (js/Error. "invalid framing")))
    (let [selectors (filter #(contains? fields %) ["eval" "entity"])
          selector (first selectors)
          selector-value (get-in fields [selector :seon.web.serve/decoded])
          path-text (get-in fields ["path" :seon.web.serve/decoded] "[]")
          raw-path (get-in fields ["path" :seon.web.serve/raw] "[]")
          offset-text (get-in fields ["offset" :seon.web.serve/decoded] "0")
          path (strict-path path-text)
          offset (canonical-nonnegative-integer offset-text)
          page-size (:seon.render.value/page-size effective-limits)
          realized-max (:seon.config.render/value-max-realized-items
                        effective-limits)
          entity-id (when (= selector "entity")
                      (canonical-nonnegative-integer selector-value))]
      (when-not (and (= 1 (count selectors))
                     (seq selector-value)
                     path
                     (<= (count path)
                         (:seon.config.render/value-max-path-segments
                          effective-limits))
                     (<= (utf8-bytes raw-path)
                         (:seon.config.render/value-max-path-bytes
                          effective-limits))
                     (some? offset)
                     (<= offset (- js/Number.MAX_SAFE_INTEGER page-size))
                     (<= (+ offset page-size) realized-max)
                     (or (= selector "eval")
                         (and (some? entity-id) (pos? entity-id))))
        (throw (js/Error. "invalid configured request")))
      {:seon.web.serve/selector selector
       :seon.web.serve/selector-value (if entity-id entity-id selector-value)
       :seon.render.value/path path
       :seon.render.value/offset offset
       :seon.render.value/effective-limits effective-limits})
    (catch :default _ (value-input-error))))

(defn- value-response [status mime body]
  (js/Response.
    body
    #js {:status status
         :headers #js {"Content-Type" mime "Cache-Control" "no-store"}}))

(defn- value-error-response [status error]
  (value-response status "application/edn; charset=utf-8" (pr-str error)))

(defn- value-html-response
  [configuration render-request value-route-base value-selector result]
  (let [value-request
        {:seon.render/value-route-base value-route-base
         :seon.render/value-selector value-selector
         :seon.render/value-projection
         (:seon.render.value/projection result)}]
    (value-response
      200
      "text/html; charset=utf-8"
      (html/->string
        (render/block :html configuration render-request value-request)))))

(defn- value-result-response
  [configuration render-request value-route-base value-selector result]
  (let [availability (:seon.render.value/availability result)
        kind (get-in result [:seon/error :seon.error/kind])]
    (cond
      (and (true? (:seon.render.value/ok? result))
           (contains? #{:available :unavailable} availability)
           (map? (:seon.render.value/projection result)))
      (value-html-response configuration render-request value-route-base
                           value-selector result)

      (contains? #{:agent :user-input} kind)
      (value-error-response 400 (value-input-error))

      :else
      (value-error-response 503 (value-core-error)))))

(defn- db-result-error? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn ^:async value!
  "Serve one authorized, bounded value projection."
  [ring-request]
  (let [framed (raw-value-query ring-request)]
    (if (:seon.error/message framed)
      (value-error-response 400 framed)
      (try
        (let [database (await (db/db))
              _ (when (db-result-error? database)
                  (throw (js/Error. "database unavailable")))
              configuration (await (web-value/policy! database))
              effective-limits
              (config/effective-value-drill-limits
                {:seon.config/configuration configuration})
              request (configured-value-request framed effective-limits)]
          (if (:seon.error/message request)
            (value-error-response 400 request)
            (let [agent-id (get-in ring-request [:path-params :id])
                  selector (:seon.web.serve/selector request)
                  selector-value (:seon.web.serve/selector-value request)
                  value-route-base
                  (str "/agent/" (js/encodeURIComponent agent-id) "/value")
                  drill-request (dissoc request
                                        :seon.web.serve/selector
                                        :seon.web.serve/selector-value)]
              (if (= selector "eval")
                (let [authorized
                      (await (db/query {::db/db database
                                       ::db/query value-eval-owner-query
                                       ::db/args [selector-value agent-id]}))]
                  (if (or (db-result-error? authorized) (nil? authorized))
                    (if (db-result-error? authorized)
                      (throw (js/Error. "authorization unavailable"))
                      (value-error-response 404 (value-absent-error)))
                    (value-error-response 503 (value-core-error))))
                (if (not= "root" agent-id)
                  (value-error-response 404 (value-absent-error))
                  (let [entity (await (db/entity database selector-value))]
                    (if (or (db-result-error? entity) (nil? entity))
                      (if (db-result-error? entity)
                        (throw (js/Error. "entity unavailable"))
                        (value-error-response 404 (value-absent-error)))
                      (let [projection (await (web-value/program-projection! database))
                            result (render.value/drill-value
                                     projection entity drill-request)]
                        (value-result-response
                          configuration
                          {:seon.agent/id agent-id
                           :seon.schema/projection projection}
                          value-route-base
                          {:seon.render/entity-id selector-value}
                          result)))))))))
        (catch :default error
          (log/error-console! "seon.web.serve" "value route failed"
                              {:seon.error/message (or (.-message error)
                                                       (str error))})
          (value-error-response 503 (value-core-error)))))))

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
                                  (str "bad log body: " e))))))))

(defn- handle-config-apply!
  "Apply one operator-resolved config payload through the live pod.

   The operator resolves the manifest and hardware-dependent values once under
   its stack lock. This boundary validates and transports that immutable value;
   it never rereads Aero or observes hardware."
  [req res]
  (-> (read-body req)
      (.then
        (fn [body]
          (let [request (reader/read-string body)
                apply-fn
                (render.core/resolve-compiled
                 'seon.client/config-apply-control!)]
            (when-not
             (schema/valid-candidate-value?
              :seon.launch/config-apply-request request)
              (throw (ex-info "invalid config apply request"
                              {:seon.error/kind :user-input})))
            (when-not apply-fn
              (throw (ex-info "live config operation is unavailable"
                              {:seon.error/kind :core})))
            (apply-fn request))))
      (.then
        (fn [result]
          (let [ok? (or (true? (:seon.runtime.state/ok? result))
                        (true? (:seon.client/writer-replacement-entered? result))
                        (true? (:seon.client/writer-replacement-resumed? result)))]
          (write-status! res
                         (if ok? 200 422)
                         "application/edn; charset=utf-8"
                         (pr-str result)))))
      (.catch
        (fn [error]
          (let [message (or (.-message error) (str error))]
            ;; Keep the operator boundary structured and bounded: exception
            ;; objects can print stacks, source, or large analyzer data.
            (log/error-console! "seon.web.serve" "config apply failed"
                                {:seon.error/message message})
          (write-status! res 422 "application/edn; charset=utf-8"
                         (pr-str {:seon.runtime.state/ok? false
                                  :seon.runtime.state/error
                                  message})))))))

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
  "Whether Bun reports the request's TCP peer as the local machine.

   This is the operator lifecycle identity check, not a browser-origin check.
   Missing socket evidence fails closed; Host and Origin headers are never
   accepted as substitutes for the kernel-reported remote address."
  {:malli/schema [:=> [:cat :any :any] :boolean]}
  [^js req ^js server]
  (contains? loopback-peer-addresses
             (when (some? (gobj/get server "requestIP"))
               (some-> server (.requestIP req) .-address))))

(defn- handle-operator-quiesce!
  "Drain this pod and flush its typed lifecycle result as EDN."
  [_req res]
  (if-let [quiesce!
           (render.core/resolve-compiled 'seon.client/quiesce-runtime!)]
    (-> (js/Promise.resolve (quiesce!))
        (.then
         (fn [result]
           (write-status!
            res
            (if (:seon.client/quiesced? result) 200 409)
            "application/edn; charset=utf-8"
            (pr-str result)))))
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
   origins only. `req` is a WHATWG Request."
  [^js req]
  (let [headers (.-headers req)
        origin  (when headers (.get headers "origin"))]
    (boolean
      (or (str/blank? origin)
          (try
            (let [o-host (.-host (js/URL. origin))            ; host[:port] of Origin
                  h-host (when headers (.get headers "host"))]
              (or (and h-host (= o-host h-host))               ; genuine same-origin
                  (contains? loopback-hosts (.-hostname (js/URL. origin)))))
            (catch :default _ false))))))

;; ============================================================
;; Reitit front door — `seon.web.router` owns the route vector + the
;; Node↔Ring adapter; serve keeps the handler fns (they touch serve-state:
;; the SSE registry) and the same-origin? gate (a
;; test pins it). We INJECT both into router here. This call re-runs on
;; hot-reload, so the cached router always holds the freshly-reloaded
;; handler fns. Bun.serve (below) dispatches every request through
;; `router/handle-request`.
;; ============================================================

;; `/` is NOT a serve handler — it is a SEEDED core route
;; (:seon.route/root → seon.web.datastar/serve-root!, root's own agent view),
;; resolved late by the router's db->routes. Only the non-core supplement
;; handlers are injected here.
(router/install!
  {:seon.web.router/static
   (terminal-handler "static asset handler failed" serve-static!)
   :seon.web.router/readiness
   (terminal-handler "readiness handler failed" handle-readiness!)
   :seon.web.router/log
   (terminal-handler "POST /log failed" handle-log!)
   :seon.web.router/config-apply
   (terminal-handler "config apply handler failed" handle-config-apply!)
   :seon.web.router/operator-quiesce
   (terminal-handler "operator quiesce handler failed"
                     handle-operator-quiesce!)
   :seon.web.router/operator-blobs
   (terminal-handler "operator blob handler failed" handle-operator-blobs!)
   :seon.web.router/same-origin?  same-origin?
   :seon.web.router/loopback-peer? loopback-peer?})

;; ============================================================
;; Lifecycle
;; ============================================================

(defn- write-port-file! [port]
  (let [target (or (.. js/process -env -SEON_PORT_FILE)
                   "tmp/seon-port")]
    (.mkdirSync fs (.dirname path target) #js {:recursive true})
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
(schema/register! ::configuration :seon.config/singleton)
(schema/register! ::start-request
                  [:map {:closed true}
                   [::readiness-only? {:optional true} ::readiness-only?]
                   [::configuration ::configuration]
                   [::restore-completion-result
                    {:optional true} ::restore-completion-result]])

(defn ^:async start!
  "Start the HTTP+SSE server on a loopback port.

   Returns a Promise resolving to:
     {:seon.web/port <int> :seon.web/port-file <abs-path>}

   Default port is 7890 (override via $SEON_PORT; set to 0 for
   ephemeral). Writes the bound port to $SEON_PORT_FILE (default
   `tmp/seon-port`). Idempotent — when a server is already LISTENING
   the call resolves with the existing binding. A dead (closed) server object
   is replaced.

   The server binds to 127.0.0.1 by default (loopback only — browsers on
   the same machine can connect; nothing on the LAN sees the pod). A
   containerized pod overrides via SEON_BIND=0.0.0.0 (see [[bind-host]]).

   If the requested port is in use, the listen fails fast — that's
   the expected behavior for a dev pod (only one instance at a time).
   To run multiple pods, set SEON_PORT=0 for ephemeral allocation."
  {:malli/schema [:=> [:cat ::start-request] :any]}
  [{::keys [readiness-only? restore-completion-result configuration]}]
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
  (datastar/configure! (config/reactive-policy configuration))
  (when-not readiness-only?
    (await (router/attach!)))
  (await
   (js/Promise.
    (fn [resolve reject]
      (if-let [server @!server]
        ;; Already listening — reuse (see docstring; a second
        ;; start-runtime! on the same pod must NOT bounce the server).
        (if (= (boolean readiness-only?)
               (boolean (gobj/get @!server "seonReadinessOnly")))
          (if (= restore-completion-result
                 (gobj/get @!server "seonRestoreCompletionResult"))
            (resolve {:seon.web/port      (.-port server)
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
            (try (.stop old true) (catch :default _ nil))
            (reset! !server nil))
          (let [port (requested-port)
                server
                (.serve js/Bun
                        #js {:port port
                             :hostname (bind-host)
                             :idleTimeout 0
                             :fetch
                             (if readiness-only?
                               (fn [req _server]
                                 (let [url (js/URL. (.-url req))]
                                   (if (and (= "GET" (.-method req))
                                            (= "/_seon/ready" (.-pathname url)))
                                     (handle-readiness! restore-completion-result req nil)
                                     (write-status! nil 503 "text/plain; charset=utf-8"
                                                    "Restore preparation is not executable."))))
                               (fn [req server]
                                 (router/handle-request req server)))
                             :error
                             (fn [error]
                               (terminal-core-fault!
                                "Bun.serve request failed" error))})]
            (gobj/set server "seonReadinessOnly" (boolean readiness-only?))
            (gobj/set server "seonRestoreCompletionResult"
                      restore-completion-result)
            (let [bound (.-port server)
                  port-file (write-port-file! bound)]
              (reset! !server server)
              (log/info-console! "seon.web.serve"
                                 (str "listening on http://127.0.0.1:" bound)
                                 {:port-file port-file})
              (resolve {:seon.web/port bound
                        :seon.web/port-file port-file})))))))))

(defn ^:async stop!
  "Close every SSE feed and await HTTP server shutdown."
  {:malli/schema [:=> [:cat] :any]}
  []
  (await (datastar/close-all-feeds!))
  (await (router/detach!))
  (await
   (if-let [server @!server]
     (js/Promise.resolve
      (do
        (.stop server true)
        (reset! !server nil)
        nil))
     (js/Promise.resolve nil))))
