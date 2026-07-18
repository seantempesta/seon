(ns seon.web.reactive.call
  "The `/agent/{id}/call` route — agent fn-calls authored as hiccup, routed by NAMESPACE
   into the owning agent's sandbox.

   This is the THIRD door of the one sandboxed-execution service (eval +
   render are the other two): an interaction is just an eval authored as
   hiccup and routed by its namespace. The browser POSTs a standard Datastar
   `@post('/agent/{id}/call?fn=…&args=…')` (produced by
   `seon.web.reactive.transform`).
   We resolve the OWNING AGENT from the fn symbol's namespace, capability-check
   that the fn is one of that agent's GRANTED fns, then invoke it through the
   agent's own eval — which transacts, and the existing reactive feed
   (`listen!` → render → SSE push) updates the UI.

   ## Namespace IS the route

   The name is the route: a fn symbol `my.agent.<id>/foo` resolves to
   agent `<id>` (`seon.agent.home/home-ns` is the canonical id↔ns mapping). No
   routing table.

   ## The capability gate (the security boundary)

   [[capability-check]] is the refusal point — it runs BEFORE any invocation:

   1. The fn's namespace must be `my.agent.<id>` according to the canonical
      `seon.agent.home/home-ns` round trip. `fs/readFileSync`, `seon.client/…`,
      and other namespaces are refused without a database request.
   2. One query at the request's immutable database value proves both that the
      agent is live and that the fn is a registered `:seon.fn` owned by that
      agent's home namespace. A dead agent or missing fn row is refused.

   A refused call is NEVER invoked; it returns a clean error value (403).
   This is the same surface idea as the SCI canvas sandbox that denies `fs` (the
   symbol simply doesn't resolve) — but as an explicit, queryable pre-invoke
   gate, because for an interaction the refusal IS the security claim.

   ## Invoke = resolve-and-apply (args stay DATA, never recompiled)

   A granted call is NOT synthesized into a source string and eval'd — that
   would let an attacker-controlled arg expression execute (an arg printed into
   source then re-read as code is the classic break-out). Instead [[invoke!]]
   resolves the granted symbol in the owning supervised Bun child and applies
   it to the args as VALUES. The
   resolved `f` is still the always-on-instrumented var, so its own
   `:malli/schema` is enforced (no second validator to drift); a bad arg or a
   throw surfaces as a value (422), not a crash, because [[invoke!]] catches it.
   The `?args=` query is additionally decoded DATA-ONLY
   (`seon.web.reactive.transform/decode-args`) so a symbol/list/tagged value is
   refused before invoke — belt-and-suspenders behind resolve-and-apply."
  (:require
    [clojure.string :as str]
    [seon.agent.home :as home]
    [seon.db :as db]
    [seon.execution :as execution]
    [seon.execution.host :as execution.host]
    [seon.log :as log]
    [seon.runtime.admission :as admission]
    [seon.web.reactive.transform :as transform]))

;; ============================================================
;; Capability gate — one query at one immutable database value.
;; ============================================================

(defn- owning-agent-id
  "Return the agent id encoded by an exact home namespace, or nil."
  [fn-sym]
  (let [ns-str (namespace fn-sym)
        prefix "my.agent."]
    (when (and ns-str (str/starts-with? ns-str prefix))
      (let [id (subs ns-str (count prefix))]
        (when (and (seq id)
                   (= (str (home/home-ns id)) ns-str))
          id)))))

(defn ^:async capability-check
  "Resolve and gate `fn-sym` against one immutable database value.

   Returns `{::agent-id <id>}` when the fn is granted to its owning
   agent, else `{::refused <reason>}`. Never invokes anything —
   the refusal is the security boundary, evaluated before any execution."
  {:malli/schema [:=> [:catn [::db :seon.db/db] [::fn-sym :symbol]] :map]}
  [database fn-sym]
  (if-let [agent-id (owning-agent-id fn-sym)]
    (let [home-ns-name (keyword (str (home/home-ns agent-id)))
          granted
          (await
           (db/query
            {:seon.db/db database
             :seon.db/query
             '[:find ?function .
               :in $ ?agent-id ?function-symbol ?namespace-name
               :where
               [?agent :seon.agent/id ?agent-id]
               [?agent :seon.eval/home-requires _]
               (not [?agent :seon.agent/terminated-at _])
               [?function :seon.fn/sym ?function-symbol]
               [?function :seon.fn/ns ?namespace]
               [?namespace :seon.ns/name ?namespace-name]]
             :seon.db/args [agent-id (str fn-sym) home-ns-name]}))]
      (cond
        (:seon.error/message granted) granted
        (some? granted) {::agent-id agent-id}
        :else
        {::refused
         (str "`" fn-sym "` is not a granted :seon.fn of live agent " agent-id
              " — an interactive handler must be a fn the agent defined in its "
              "home ns " (home/home-ns agent-id) ".")}))
    {::refused
     (str "no agent owns the namespace of `" fn-sym
          "` — agent calls route only into an agent's home ns (my.agent.<id>); "
          "fs / core / cross-agent symbols are refused.")}))

;; ============================================================
;; Invoke — through the one supervised authored-execution service.
;; ============================================================

(defn- err->msg [e]
  (or (ex-message e) (str e)))

(defn ^:async invoke!
  "Invoke a granted authored function in its supervised Bun child.

   Uses the request's immutable database value, prepares the function's authored
   source identity at exactly that value, and sends the ordinary argument
   vector through `seon.execution.host/invoke!`. The child applies the function
   inside its agent and database transaction context, awaits async work, and
   returns one bounded ordinary result. Child or preparation failures become
   `{::ok? false ::error <message>}` values; arguments are never source text."
  {:malli/schema [:=> [:catn [::db :seon.db/db] [::agent-id :string]
                       [::fn-sym :symbol]
                       [::args [:sequential :any]]]
                  :any]}
  [database agent-id fn-sym args]
  (if-not (admission/available?)
    (let [refusal (admission/unavailable)]
      {::ok? false
       ::unavailable? true
       ::error (get-in refusal [:seon/error :seon.error/message])})
    (try
      (let [plan (execution/invocation-plan agent-id fn-sym (vec args))
            prepared
            (await
             (execution/prepare-invocations!
              {:seon.db/db database
               ::execution/invocation-plans [plan]}))]
        (if (:seon.error/message prepared)
          {::ok? false ::error (:seon.error/message prepared)}
          (if-let [invocation (first prepared)]
            (let [result (await (execution.host/invoke! invocation))]
              (if (= execution/result-message (::execution/message result))
                {::ok? true ::value (::execution/result result)}
                {::ok? false
                 ::error
                 (or (get-in result [::execution/error :seon.error/message])
                     "The execution child returned no result.")}))
            {::ok? false
             ::error "Invocation preparation returned no invocation."})))
      (catch :default e
        {::ok? false ::error (err->msg e)}))))

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
  [^js req]
  (if (datastar-request? req)
    (js/Response. nil #js {:status 204
                           :headers #js {"Cache-Control" "no-store"}})
    (json-response 200 {::ok? true})))

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
  "POST /agent/{id}/call — gate a call descriptor and invoke if granted.

   Parses the call descriptor, capability-checks, and (only if
   granted) invokes. The fn symbol rides `?fn=`; the fn-CALL case carries its
   render-time args in `?args=` (transit, decoded DATA-ONLY), the fn-REF case
   takes the POST body's Datastar signals as a single map argument. Responses:
   200 `{ok? true}` on success; 403 with the refusal reason when the capability
   gate denies the fn (never invoked); 422 when the invoked fn fails OR when
   `?args=` is malformed / non-data (refused before invoke); 400 for a missing
   fn. Every path through here writes exactly one response — a bad `?args=`
   decode is caught inside the promise chain, never a hung request. The UI
  update is the reactive feed's job — the invoked fn's transact fans out via
  the web UI tx-listener."
  [request]
  (let [^js req (:seon.http/request request)]
    (if-not (admission/available?)
      (json-response 503
                     {::ok? false
                      ::unavailable? true
                      ::error
                      (get-in (admission/unavailable)
                              [:seon/error :seon.error/message])})
      (let [fn-str (query-val req "fn")]
       (if (str/blank? fn-str)
         (json-response 400 {::ok? false
                             ::error "missing 'fn' query param"})
         (try
           (let [fn-sym (symbol fn-str)
                 database (await (db/db))
                 cap (if (:seon.error/message database)
                       database
                       (await (capability-check database fn-sym)))]
             (cond
               (:seon.error/message cap)
               (json-response 503 {::ok? false
                                   ::unavailable? true
                                   ::error (:seon.error/message cap)})

               (::refused cap)
               (let [reason (::refused cap)]
                 (log/info-console! "seon.web.reactive.call" "agent call REFUSED"
                                    {:seon.web.call/fn fn-str})
                 (json-response 403 {::ok? false ::refused reason}))

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
                                         ::error (str "bad args: " arg-error)}))
                   (let [{ok? ::ok? err ::error}
                         (await (invoke! database agent-id fn-sym args))]
                     (if ok?
                       (do
                         (log/info-console! "seon.web.reactive.call" "agent call OK"
                                            {:seon.web.call/fn fn-str
                                             :seon.agent/id agent-id})
                         (success-response req))
                       (do
                         (log/error-console! "seon.web.reactive.call"
                                             "agent call invoke error" (str err))
                         (json-response 422 {::ok? false
                                             ::error (str err)}))))))))
           (catch :default e
             (log/error-console! "seon.web.reactive.call" "agent call threw" e)
             (json-response 500 {::ok? false ::error (str e)}))))))))
