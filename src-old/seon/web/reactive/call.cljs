(ns seon.web.reactive.call
  "Validate browser-authored calls and commit interaction facts.

   The HTTP request never waits for authored execution. It capability-checks
   the live route agent and exact committed handler row, validates the
   schema-projected argument vector, commits one pending interaction/run fact,
   and acknowledges. The cluster JVM executes that fact; the page learns its
   outcome only from committed result/error facts through the normal
   database-interest → reactive render → Datastar morph chain."
  (:require
    [clojure.string :as str]
    [seon.agent.interaction :as interaction]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.log :as log]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]
    [seon.web.reactive.transform :as transform]))

;; ============================================================
;; Capability gate — one query at one immutable database value.
;; ============================================================

(defn ^:async capability-check
  "Gate one route agent and function against one immutable database value.

   Returns `{::agent-id <id>}` when the shared fn is available to the live route
   agent, else `{::refused <reason>}`. Never invokes anything —
   the refusal is the security boundary, evaluated before any execution."
  {:malli/schema [:=> [:catn [::db :seon.db/db]
                       [::agent-id :string]
                       [::fn-sym :symbol]] :map]}
  [database agent-id fn-sym]
  (let [granted
        (await
         (db/query
          {:seon.db/db database
           :seon.db/query
           '[:find (pull ?function
                         [:seon.fn/source-fingerprint :seon.fn/spec]) .
             :in $ ?agent-id ?function-symbol
             :where
             [?agent :seon.agent/id ?agent-id]
             (not [?agent :seon.agent/terminated-at _])
             [?function :seon.fn/sym ?function-symbol]
             [(get-else $ ?function :seon.fn/private? false) ?private]
             [(= false ?private)]
             [?function :seon.fn/source _ ?source-tx]
             [?source-tx :seon.db/user ?author]
             [?author :seon.agent/id _]
             [?source-tx :seon.db/process ?process]
             [?process :seon.db.process/id :seon.db.process/repl]]
           :seon.db/args [agent-id (str fn-sym)]}))]
    (cond
      (:seon.error/message granted) granted
      (and (string? (:seon.fn/source-fingerprint granted))
           (string? (:seon.fn/spec granted)))
      {::agent-id agent-id
       ::handler fn-sym
       ::handler-source-fingerprint
       (:seon.fn/source-fingerprint granted)
       ::handler-spec (:seon.fn/spec granted)}
      (some? granted)
      {:seon.error/message
       (str "The committed interaction handler " fn-sym
            " has no complete source identity and schema.")
       :seon.error/kind :core-bug}
      :else
      {::refused
       (str "`" fn-sym "` is not a shared agent-authored function available "
            "to live agent " agent-id ".")})))

;; ============================================================
;; Submission — one generated interaction/run fact, no execution.
;; ============================================================

(defn- err->msg [e]
  (or (ex-message e) (str e)))

(defn- error-value
  "Normalize one call failure to the standard structured error value."
  ([value] (error-value value :agent))
  ([value kind]
   (if (and (map? value) (string? (:seon.error/message value)))
     value
     {:seon.error/message (str value)
      :seon.error/kind kind})))

(defn ^:async submit!
  "Validate and commit one pending interaction fact, returning its durable id."
  {:malli/schema
   [:=> [:catn [::db :seon.db/db]
                 [::capability :map]
                 [::args :seon.agent.interaction/arguments]]
    :map]}
  [database capability args]
  (let [agent-id (::agent-id capability)
        handler (::handler capability)
        validated
        (interaction/validate-request
         {:seon.agent/id agent-id
          :seon.agent.interaction/handler handler
          :seon.agent.interaction/handler-source-fingerprint
          (::handler-source-fingerprint capability)
          :seon.agent.interaction/handler-spec
          (::handler-spec capability)
          :seon.agent.interaction/arguments args
          :seon.schema/projection (schema/current-projection)})]
    (if (:seon.error/message validated)
      {::ok? false ::error validated}
      (let [now (js/Date.)
            allocation
            (await
             (db/without-agent
              #(db.id/allocate!
                {::db/db database
                 ::db.id/allocations
                 [{::db.id/key ::interaction-id
                   ::db.id/identity-attr
                   :seon.agent.interaction/id}
                  {::db.id/key ::run-id
                   ::db.id/identity-attr :seon.agent.run/id}]
                 ::db.id/transaction-builder
                 (fn [ids]
                   {::db/tx-data
                    (interaction/open-tx-data
                     {:seon.agent.interaction/id
                      (get ids ::interaction-id)
                      :seon.agent.run/id (get ids ::run-id)
                      :seon.agent/id agent-id
                      :seon.agent.interaction/handler handler
                      :seon.agent.interaction/handler-source-fingerprint
                      (::handler-source-fingerprint capability)
                      :seon.agent.interaction/arguments args
                      :seon.agent.interaction/subjects
                      #{[:seon.agent/id agent-id]}
                      :seon.agent.interaction/requested-at now})})})))]
        (if (:seon.error/message allocation)
          {::ok? false ::unavailable? true ::error allocation}
          {::ok? true
           ::interaction-id
           (get-in allocation [::db.id/ids ::interaction-id])})))))

;; ============================================================
;; HTTP handler — POST /agent/{id}/call. The Ring request carries the native
;; WHATWG Request; every branch returns exactly one Response.
;; ============================================================

(defn- json-response [code m]
  (js/Response.
   (js/JSON.stringify (clj->js m))
   #js {:status code
        :headers #js {"Content-Type" "application/json; charset=utf-8"
                      "Cache-Control" "no-store"}}))

(defn- datastar-request? [^js req]
  (= "true" (some-> (.-headers req)
                     (.get "datastar-request")
                     str/lower-case)))

(defn- success-response
  "Return a successful browser action without a redundant payload.

   The database listener owns the visible update. Direct API callers retain
   the small JSON acknowledgement."
  [^js req interaction-id]
  (if (datastar-request? req)
    (js/Response. nil #js {:status 204
                           :headers #js {"Cache-Control" "no-store"}})
    (json-response 200 {::ok? true ::interaction-id interaction-id})))

(defn- query-val [^js req k]
  (try
    (let [u (js/URL. (.-url req))]
      (.get (.-searchParams u) k))
    (catch :default _ nil)))

(defn- parse-signals
  "Datastar sends current signals as a JSON body on POST. Parse to a map with
   keyword keys; blank/garbled → {}.

   `my.canvas` fields use a `seon_` + base64url encoding so Datastar receives a
   safe identifier while agent handlers receive the original fully-qualified
   keyword. When at least one encoded canvas field is present, return ONLY
   those fields: page-level signals (`:t`, `:live`, chat text) are transport
   state, not domain input. Raw non-canvas forms keep their existing map."
  [body]
  (try
    (let [raw (if (str/blank? body)
                {}
                (js->clj (js/JSON.parse body) :keywordize-keys true))
          canvas-fields
          (into {}
                (keep (fn [[k v]]
                        (let [n (name k)]
                          (when (str/starts-with? n "seon_")
                            (let [decoded (.toString
                                            (.from js/Buffer (subs n 5) "base64url")
                                            "utf8")]
                              (when (str/starts-with? decoded ":")
                                [(keyword (subs decoded 1)) v]))))))
                raw)]
      (if (seq canvas-fields) canvas-fields raw))
    (catch :default _ {})))

(defn ^:async handle!
  "POST /agent/{id}/call — validate and transact an interaction if granted.

   Parses the call descriptor, capability-checks, and (only if
   granted) submits. The fn symbol rides `?fn=`; the fn-CALL case carries its
   render-time args in `?args=` (transit, decoded DATA-ONLY), the fn-REF case
   takes the POST body's Datastar signals as a single map argument. Responses:
   204 for Datastar or 200 with the durable interaction id on success; 403 with
   the refusal reason when the capability gate denies the fn; 422 when the
   argument schema fails OR when
   `?args=` is malformed / non-data (refused before submission); 400 for a missing
   fn. Every path through here writes exactly one response — a bad `?args=`
   decode is caught inside the promise chain, never a hung request. The UI
  update is the reactive feed's job — committed outcome facts fan out via the
  web UI transaction listener."
  [request]
  (let [^js req (:seon.http/request request)]
    (if-not (admission/available?)
      (json-response 503
                     {::ok? false
                      ::unavailable? true
                      ::error
                      (error-value
                       (get (admission/unavailable) :seon/error)
                       :core-bug)})
      (let [fn-str (query-val req "fn")
            route-agent-id (get-in request [:path-params :id])]
       (if (or (str/blank? route-agent-id) (str/blank? fn-str))
         (json-response 400 {::ok? false
                             ::error (error-value
                                      "missing route agent id or 'fn' query param"
                                      :user-input)})
         (try
           (let [fn-sym (symbol fn-str)
                 database (await (db/db))
                 cap (if (:seon.error/message database)
                       database
                       (await (capability-check
                               database route-agent-id fn-sym)))]
             (cond
               (:seon.error/message cap)
               (json-response 503 {::ok? false
                                   ::unavailable? true
                                   ::error cap})

               (::refused cap)
               (let [reason (::refused cap)]
                 (log/info-console! "seon.web.reactive.call" "agent call REFUSED"
                                    {:seon.web.call/fn fn-str})
                 (json-response 403 {::ok? false
                                     ::refused reason
                                     ::error (error-value reason :user-input)}))

               :else
               (let [agent-id (::agent-id cap)
                     args-q (query-val req "args")
                     {args ::args arg-error ::arg-error}
                     (if (some? args-q)
                       (try {::args (transform/decode-args args-q)}
                            (catch :default e {::arg-error (err->msg e)}))
                       (let [signals (parse-signals (await (.text req)))]
                         {::args (if (seq signals) [signals] [])}))]
                 (if arg-error
                   (do
                     (log/info-console! "seon.web.reactive.call" "agent call BAD ARGS"
                                        {:seon.web.call/fn fn-str
                                         :seon.web.call/error arg-error})
                     (json-response 422 {::ok? false
                                         ::error
                                         (error-value (str "bad args: " arg-error)
                                                      :user-input)}))
                   (let [{ok? ::ok? err ::error
                          interaction-id ::interaction-id
                          unavailable? ::unavailable?}
                         (await (submit! database cap args))]
                     (if ok?
                       (do
                         (log/info-console! "seon.web.reactive.call"
                                            "interaction accepted"
                                            {:seon.web.call/fn fn-str
                                             :seon.agent/id agent-id
                                             :seon.agent.interaction/id
                                             interaction-id})
                         (success-response req interaction-id))
                       (do
                         (log/error-console! "seon.web.reactive.call"
                                             "interaction refused"
                                             (:seon.error/message err))
                         (json-response (if unavailable? 503 422)
                                        {::ok? false
                                         ::error err}))))))))
           (catch :default e
             (log/error-console! "seon.web.reactive.call" "agent call threw" e)
             (json-response 500 {::ok? false
                                 ::error
                                 (error-value (err->msg e) :core-bug)}))))))))
