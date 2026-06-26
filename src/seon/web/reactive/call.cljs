(ns seon.web.reactive.call
  "The `/call` route — agent fn-calls authored as hiccup, routed by NAMESPACE
   into the owning agent's sandbox.

   This is the THIRD door of the one sandboxed-execution service (eval +
   render are the other two): an interaction is just an eval authored as
   hiccup and routed by its namespace. The browser POSTs a standard Datastar
   `@post('/call?fn=…&args=…')` (produced by `seon.web.reactive.transform`).
   We resolve the OWNING AGENT from the fn symbol's namespace, capability-check
   that the fn is one of that agent's GRANTED fns, then invoke it through the
   agent's own eval — which transacts, and the existing reactive feed
   (`listen!` → render → SSE push) updates the UI.

   ## Namespace IS the route (replaces the JVM `seon.*` prefix-whitelist)

   The JVM `seon.web.reactive.actions/resolve-action` allowed ONLY `seon.*`
   namespaces — exactly wrong for agent code, which lives in `my.agent.<id>`.
   Here the name is the route: a fn symbol `my.agent.<id>/foo` resolves to
   agent `<id>` (`seon.ctx/home-ns` is the canonical id↔ns mapping). No
   routing table.

   ## The capability gate (the security boundary)

   [[capability-check]] is the refusal point — it runs BEFORE any invocation:

   1. [[resolve-owning-agent]] — the fn's namespace must be `my.agent.<id>`
      for a LIVE agent (`:seon.agent/id` row). `fs/readFileSync`,
      `seon.client/…`, a dead/absent agent → no owning agent → REFUSED.
   2. [[granted-fn?]] — the fn must be a registered `:seon.fn` whose owning ns
      is that agent's home ns (a fn the agent itself defined). A symbol with
      no matching `:seon.fn` row → REFUSED.

   A refused call is NEVER invoked; it returns a clean error envelope (403).
   This is the SAME surface idea as the SCI tile sandbox that denies `fs` (the
   symbol simply doesn't resolve) — but as an explicit, queryable pre-invoke
   gate, because for an interaction the refusal IS the security claim.

   Args are Malli-validated by the always-on instrumentation when the granted
   fn is invoked (the fn's own `:malli/schema` is the contract — no second
   validator to drift). A validation failure surfaces as a value (422), not a
   crash, because the invoke goes through `seon.eval/eval` (safe by default)."
  (:require
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.log :as log]
    [seon.repl :as repl]
    [seon.web.reactive.transform :as transform]))

;; ============================================================
;; Capability gate — pure fns of a db value (third-party boundary).
;; ============================================================

(defn resolve-owning-agent
  "The agent id that OWNS `fn-sym`, via namespace-as-route, or nil. The
   namespace must be `my.agent.<id>` (the canonical `seon.ctx/home-ns`
   mapping) AND a live agent `<id>` must exist (`:seon.agent/id` row). Any
   other namespace — `fs`, `seon.*`, a domain ns, a dead/absent agent —
   resolves to nil, and the caller refuses the call."
  {:malli/schema [:=> [:catn [::db :seon.db/db] [::fn-sym :symbol]] [:maybe :string]]}
  [db fn-sym]
  (let [ns-str (namespace fn-sym)
        prefix "my.agent."]
    (when (and ns-str (str/starts-with? ns-str prefix))
      (let [id (subs ns-str (count prefix))]
        (when (and (seq id)
                   (= (str (ctx/home-ns id)) ns-str)   ; exact round-trip
                   (seq (db/query '[:find ?a :in $ ?id :where
                                    [?a :seon.agent/id ?id]]
                                  db id)))
          id)))))

(defn granted-fn?
  "True when `fn-sym` is a registered `:seon.fn` whose owning ns is
   `agent-id`'s home ns — a fn the agent itself defined. This is the granted
   surface for `/call`: an interactive handler must be one of the agent's own
   fns. Refuses `fs`/core/cross-agent symbols (no matching `:seon.fn` row in
   the home ns)."
  {:malli/schema [:=> [:catn [::db :seon.db/db] [::agent-id :string] [::fn-sym :symbol]]
                  :boolean]}
  [db agent-id fn-sym]
  (boolean
    (seq (db/query '[:find ?f :in $ ?sym ?nsname :where
                     [?f :seon.fn/sym ?sym]
                     [?f :seon.fn/ns ?n]
                     [?n :seon.ns/name ?nsname]]
                   db (str fn-sym) (keyword (str (ctx/home-ns agent-id)))))))

(defn capability-check
  "Resolve + gate `fn-sym` against `db`. Returns
   `{::agent-id <id>}` when the fn is granted to its owning
   agent, else `{::refused <reason>}`. Never invokes anything —
   the refusal is the security boundary, evaluated before any execution."
  {:malli/schema [:=> [:catn [::db :seon.db/db] [::fn-sym :symbol]] :map]}
  [db fn-sym]
  (if-let [agent-id (resolve-owning-agent db fn-sym)]
    (if (granted-fn? db agent-id fn-sym)
      {::agent-id agent-id}
      {::refused
       (str "`" fn-sym "` is not a granted :seon.fn of agent " agent-id
            " — an interactive handler must be a fn the agent defined in its "
            "home ns " (ctx/home-ns agent-id) ".")})
    {::refused
     (str "no agent owns the namespace of `" fn-sym
          "` — /call routes only into an agent's home ns (my.agent.<id>); "
          "fs / core / cross-agent symbols are refused.")}))

;; ============================================================
;; Invoke — through the agent's OWN eval (the same path as eval),
;; scoped to the owning agent so its transacts tag correctly + its
;; reactive feed updates. NOT a parallel executor.
;; ============================================================

(defn ^:async invoke!
  "Invoke the (already capability-checked) granted `fn-sym` for `agent-id`
   with `args`, by synthesizing the fn-call form and routing it through the
   agent's eval in its home ns. Returns a Promise of
   `{::ok? true ::value v}` or
   `{::ok? false ::error <message>}` — errors are
   values (the eval is safe by default; an arg-validation failure from the
   fn's instrumentation arrives here as an error, never a crash). An async
   fn (one that returns a Promise — e.g. a `db/transact!`) is awaited so the
   committed effect is visible before we respond."
  {:malli/schema [:=> [:catn [::agent-id :string] [::fn-sym :symbol]
                       [::args [:sequential :any]]]
                  :any]}
  [agent-id fn-sym args]
  (let [cs   (await (repl/ensure-bootstrap!))
        home (ctx/home-ns agent-id)
        form (str "(" fn-sym
                  (when (seq args) (str " " (str/join " " (map pr-str args))))
                  ")")
        r    (await
               (db/with-agent agent-id
                 (fn []
                   (db/with-tx-context {:seon.db/origin   :agent
                                        :seon.db/agent-id agent-id}
                     (fn [] (seval/eval cs form {:ns home}))))))]
    (if (:ok r)
      (let [v  (:value r)
            v* (if (instance? js/Promise v) (await v) v)]
        {::ok? true ::value v*})
      {::ok?   false
       ::error (or (:seon.error/message (:error r))
                   (str (:error r)))})))

;; ============================================================
;; HTTP handler — POST /call. Opaque (req,res), like the inspector +
;; serve handlers (no Malli schema on the Ring-style boundary).
;; ============================================================

(defn- write-json! [^js res code m]
  (.writeHead res code #js {"Content-Type"  "application/json; charset=utf-8"
                            "Cache-Control" "no-store"})
  (.end res (js/JSON.stringify (clj->js m))))

(defn- query-val [^js req k]
  (try
    (let [u (js/URL. (str "http://x" (.-url req)))]
      (.get (.-searchParams u) k))
    (catch :default _ nil)))

(defn- read-body [^js req]
  (js/Promise.
    (fn [resolve _reject]
      (let [chunks (atom [])]
        (.on req "data" (fn [c] (swap! chunks conj c)))
        (.on req "end"
             (fn [] (resolve (.toString (.concat js/Buffer (clj->js @chunks))))))))))

(defn- parse-signals
  "Datastar sends current signals as a JSON body on POST. Parse to a map with
   keyword keys; blank/garbled → {}."
  [body]
  (try
    (if (str/blank? body)
      {}
      (js->clj (js/JSON.parse body) :keywordize-keys true))
    (catch :default _ {})))

(defn ^:async handle!
  "POST /call — parse the call descriptor, capability-check, and (only if
   granted) invoke. The fn symbol rides `?fn=`; the fn-CALL case carries its
   render-time args in `?args=` (transit), the fn-REF case takes the POST
   body's Datastar signals as a single map argument. Responses: 200
   `{ok? true}` on success; 403 with the refusal reason when the capability
   gate denies the fn (never invoked); 422 with the error when the invoked fn
   fails; 400 for a missing fn. The UI update is the reactive feed's job — the
   invoked fn's transact fans out via the inspector tx-listener."
  [^js req ^js res]
  (let [fn-str (query-val req "fn")]
    (if (str/blank? fn-str)
      (write-json! res 400 {::ok? false
                            ::error "missing 'fn' query param"})
      (let [fn-sym (symbol fn-str)
            cap    (capability-check @db/*conn* fn-sym)]
        (if-let [reason (::refused cap)]
          (do
            (log/info-console! "seon.web.reactive.call" "/call REFUSED"
                               {:fn fn-str})
            (write-json! res 403 {::ok?      false
                                  ::refused  reason}))
          (let [agent-id (::agent-id cap)
                args-q   (query-val req "args")]
            (-> (if (some? args-q)
                  ;; fn-CALL — render-time args, transit-decoded from ?args=
                  (js/Promise.resolve (vec (transform/decode-args args-q)))
                  ;; fn-REF — args from click-time signals (the POST body),
                  ;; passed as a single map argument
                  (.then (read-body req)
                         (fn [body]
                           (let [sigs (parse-signals body)]
                             (if (seq sigs) [sigs] [])))))
                (.then (fn [args] (invoke! agent-id fn-sym args)))
                (.then (fn [{ok?  ::ok?
                             err  ::error}]
                         (if ok?
                           (do
                             (log/info-console! "seon.web.reactive.call" "/call OK"
                                                {:fn fn-str :agent agent-id})
                             (write-json! res 200 {::ok? true}))
                           (do
                             (log/error-console! "seon.web.reactive.call"
                                                 "/call invoke error" (str err))
                             (write-json! res 422 {::ok?    false
                                                   ::error  (str err)})))))
                (.catch (fn [e]
                          (log/error-console! "seon.web.reactive.call" "/call threw" e)
                          (write-json! res 500 {::ok?   false
                                                ::error (str e)}))))))))))
