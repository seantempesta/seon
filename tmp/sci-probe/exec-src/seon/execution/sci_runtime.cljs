(ns seon.execution.sci-runtime
  "B2-EXPERIMENTAL sci-engined execution child (sci-execution-runtime PRD).

   The production child composition with ONE substitution: the compiled
   `seon.execution.runtime/eval-batch!` entry routes through a sci engine
   instead of the self-host cljs.js compiler. `render-prompt!` and
   `render-agent-view!` are the PRODUCTION functions, reused verbatim from
   `seon.execution.runtime`; the child boots through the production
   `seon.execution/-main` (real session open, real admission, real IPC).

   This namespace lives under tmp/sci-probe/exec-src (a harness path), is
   compiled only by the `:execution-sci` shadow build in a separate cache,
   and is never selected by any operator flavor. It is measurement
   apparatus for stage B2, not a second production execution path.

   Engine-independent production owners are REUSED, not ported:
   `seon.eval/record-eval!` (receipts), `augment-ns-source`,
   `parity-intercept`, `result-var-ref?`, `result-miss-message`,
   `race-timeout`/`timed-out?`, `budget`/`defer` wrapper types,
   `seon.agent.home/home-requires-for`/`home-ns-form`.

   B1 adapter-work implemented here: binding-table provisioning from the
   admitted database program graph + `eval/lookup-value` (item 3),
   catch-site error classification with production prose shape (items 1/2,
   minimal), sci home-ns setup (item 7), per-form print capture through
   `sci/print-fn` (item 6, per-form — not ALS-spanning), in-process sync
   deadline via `:interrupt-fn` (item 9), sci result-var interning.
   Deferred: analyzer-resolution queries for prose-demote/preflight repair
   (item 4), instrumentation reapply over sci vars (item 5), cljs.test in
   ctx (item 8), the full program-graph tee (a minimal defn/ns tee rides
   receipts instead)."
  (:require
   [cljs.tools.reader :as tools-reader]
   [clojure.string :as str]
   [sci.core :as sci]
   [sci.interrupt :as interrupt]
   [seon.agent.home :as home]
   [seon.config :as config]
   [seon.db :as db]
   [seon.eval :as eval]
   [seon.execution :as execution]
   [seon.execution.runtime :as runtime]
   [seon.runtime.admission :as admission]))

(def ^:private default-await-ms 10000)
(def ^:private sync-deadline-ms 60000)

;;; ------------------------------------------------------------------
;;; Engine state — one retained sci context per child process (the sci
;;; analog of the retained self-host compile-state).
;;; ------------------------------------------------------------------

(defonce ^:private !engine (atom nil))

(def ^:private compiled-fn-query
  ;; Every fn the database program graph names, core-indexed and authored.
  ;; Compiled ones resolve through eval/lookup-value and become the sci
  ;; binding table; authored-only ones load through :load-fn instead.
  '[:find ?sym ?ns-name
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/ns ?namespace]
    [?namespace :seon.ns/name ?ns-name]])

(def ^:private authored-source-query
  ;; Same provenance filter as seon.execution's runtime-namespace-query.
  '[:find ?name ?source
    :where
    [?namespace :seon.ns/name ?name]
    [?namespace :seon.ns/source ?source ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]])

(defn- ^:async provisioned-namespaces
  "COMPUTED binding provisioning (B1 item 3): derive the sci ctx
   :namespaces table from the admitted program graph. Never a hand list."
  [database]
  (let [rows (await (db/query {::db/db database
                               :seon.db/query compiled-fn-query}))]
    (if (:seon.error/message rows)
      rows
      (reduce
       (fn [table [sym ns-name]]
         (let [qualified (symbol (str sym))
               value (eval/lookup-value qualified)]
           (if (some? value)
             (assoc-in table [(symbol (str ns-name))
                              (symbol (name qualified))]
                       value)
             table)))
       {}
       rows))))

(defn- ^:async authored-sources-for
  [database]
  (let [rows (await (db/query {::db/db database
                               :seon.db/query authored-source-query}))]
    (if (:seon.error/message rows)
      {}
      (into {} (map (fn [[ns-name source]]
                      [(symbol (str ns-name)) source]))
            rows))))

(defn- ^:async make-engine!
  [database]
  (let [namespaces (await (provisioned-namespaces database))]
    (when (:seon.error/message namespaces)
      (throw (ex-info "sci binding provisioning failed."
                      {:seon.error/kind :core-bug
                       :seon.error/data namespaces})))
    (let [!deadline (atom nil)
          !authored (atom {})
          !print (atom nil)
          ctx (sci/init
               {:classes {'js js/globalThis :allow :all}
                :interrupt-fn
                (fn []
                  (when-let [deadline @!deadline]
                    (when (> (js/Date.now) deadline)
                      (interrupt/interrupt! "eval wall-clock budget exceeded"))))
                :load-fn
                (fn [{:keys [libname]}]
                  (when-let [source (get @!authored libname)]
                    {:source source}))
                :namespaces
                (-> namespaces
                    (update 'clojure.core merge interrupt/clojure-core)
                    (assoc 'result {}))})]
      ;; Evaluated printing reaches the per-form buffer (B1 item 6,
      ;; per-form scope; ALS-spanning capture stays deferred).
      (sci/alter-var-root sci/print-fn
                          (constantly
                           (fn [text]
                             (when-let [buffer @!print]
                               (vswap! buffer conj text)))))
      (sci/alter-var-root sci/print-newline (constantly true))
      {::ctx ctx
       ::!deadline !deadline
       ::!authored !authored
       ::!print !print
       ::home-namespaces #{}})))

(defn- ^:async ensure-engine!
  [database]
  (or @!engine
      (let [engine (await (make-engine! database))]
        (reset! !engine engine)
        engine)))

(defn- ^:async ensure-home-ns!
  "Sci form of setup-agent-ns! (B1 item 7): evaluate the agent's exact
   production home `(ns …)` source in the sci ctx. Idempotent per ns."
  [{::keys [ctx home-namespaces] :as engine} agent-ns-sym agent-id]
  (if (contains? home-namespaces agent-ns-sym)
    engine
    (let [require-specs (await (home/home-requires-for agent-id))
          _ (when (and (map? require-specs)
                       (string? (:seon.error/message require-specs)))
              (throw (ex-info (:seon.error/message require-specs)
                              require-specs)))
          setup-source (home/home-ns-form agent-ns-sym require-specs)]
      (sci/eval-string+ ctx setup-source)
      (let [updated (update engine ::home-namespaces conj agent-ns-sym)]
        (swap! !engine
               (fn [current]
                 (if current
                   (update current ::home-namespaces conj agent-ns-sym)
                   current)))
        updated))))

;;; ------------------------------------------------------------------
;;; One-form eval + await (the B1 seam, over production wrapper types)
;;; ------------------------------------------------------------------

(defn- sci-error-map
  "Catch-site classification (B1 items 1/2): sci throws at analysis where
   cljs.js warns; synthesize the production compile-error prose shape."
  [exception source]
  (let [message (or (some-> exception .-message) (str exception))
        data (ex-data exception)
        unresolved? (str/includes? message "Unable to resolve symbol")
        missing-ns? (str/includes? message "Could not find namespace")]
    (cond-> {:seon.error/kind (if (or unresolved? missing-ns?) :compile :eval)
             :seon.error/message
             (if (or unresolved? missing-ns?)
               (str message
                    " — the form ran NOTHING; fix the reference and re-eval.")
               message)}
      (:line data) (assoc :seon.error/line (:line data))
      (:column data) (assoc :seon.error/column (:column data))
      source (assoc :seon.error/data {:seon.eval/source source}))))

(defn- ^:async sci-eval-form!
  "Evaluate one source string in the retained sci ctx. Returns the
   production eval envelope; never throws."
  [{::keys [ctx !deadline]} source starting-ns]
  (let [ns-object (or (sci/find-ns ctx starting-ns)
                      (do (sci/eval-string* ctx (str "(in-ns '" starting-ns ")"))
                          (sci/find-ns ctx starting-ns)))
        result-reference? (eval/result-var-ref? source)]
    (reset! !deadline (+ (js/Date.now) sync-deadline-ms))
    (try
      (let [{:keys [val ns]} (sci/eval-string+ ctx source {:ns ns-object})]
        {::eval/ok? true
         ::eval/value val
         ::eval/ending-ns (or (some-> ns str symbol) starting-ns)})
      (catch :default exception
        (if (and result-reference?
                 (str/includes? (or (some-> exception .-message) "")
                                "Unable to resolve symbol"))
          {::eval/ok? true
           ::eval/value (eval/result-miss-message (str/trim (str source)))
           ::eval/ending-ns starting-ns}
          {::eval/ok? false
           :seon/error (sci-error-map exception source)
           ::eval/ending-ns starting-ns}))
      (finally
        (reset! !deadline nil)))))

(defn- ^:async await-value
  "Reduced port of the private seon.eval/maybe-await-value over the SAME
   production wrapper types (budget/defer/race-timeout are reused)."
  [runtime-value]
  (let [budgeted? (instance? eval/Budgeted runtime-value)
        value (if budgeted? (.-value runtime-value) runtime-value)
        wall-ms (if budgeted? (.-ms runtime-value) default-await-ms)]
    (cond
      (instance? eval/Deferred value)
      {::eval/ok? false ::pending-promise (.-promise value)}

      (instance? js/Promise value)
      (let [raced (try
                    (await (eval/race-timeout value wall-ms))
                    (catch :default exception
                      {::rejection exception}))]
        (cond
          (and (map? raced) (contains? raced ::rejection))
          {::eval/ok? false
           :seon/error (sci-error-map (::rejection raced) nil)}

          (eval/timed-out? raced)
          {::eval/ok? false ::pending-promise value}

          :else
          {::eval/ok? true ::eval/value raced}))

      :else
      {::eval/ok? true ::eval/value value})))

;;; ------------------------------------------------------------------
;;; Minimal defn/ns tee (full program-graph tee deferred; see ns doc)
;;; ------------------------------------------------------------------

(defn- first-form [source]
  (try
    (tools-reader/read-string source)
    (catch :default _ nil)))

(defn- minimal-tee
  [source ending-ns]
  (let [form (first-form source)]
    (cond
      (and (seq? form)
           (contains? #{'defn 'defn- 'def} (first form))
           (symbol? (second form))
           (some-> ending-ns str (str/starts-with? "my.")))
      [{:seon.fn/sym (str ending-ns "/" (second form))
        :seon.fn/source source
        :seon.fn/ns {:seon.ns/name ending-ns}}]

      (and (seq? form) (= 'ns (first form)) (symbol? (second form))
           (str/starts-with? (str (second form)) "my."))
      [{:seon.ns/name (second form)
        :seon.ns/source source}]

      :else [])))

;;; ------------------------------------------------------------------
;;; The batch loop — the production fold, reduced (no repair, no
;;; prose-demote, no auto-test-run; those stay named deferred items).
;;; ------------------------------------------------------------------

(defn- database-error? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- ^:async record-entry!
  [engine configuration turn-id agent-id current-ns request]
  (await
   (db/with-agent
     agent-id
     (fn ^:async record-as-agent! []
       (await
        (db/with-tx-context
          {::db/user [:seon.agent/id agent-id]
           ::db/process [:seon.db.process/id :seon.db.process/repl]
           ::eval/ns current-ns}
          (fn ^:async record-with-provenance! []
            (await
             (eval/record-eval!
              (merge {:seon.agent.turn/id-of-turn turn-id
                      :seon.config/configuration configuration}
                     request))))))))))

(defn ^:async sci-eval-batch!
  "The sci-engined form of seon.execution.runtime/eval-batch!."
  [{:seon.eval/keys [parsed starting-ns]
    turn-id :seon.agent.turn/id-of-turn
    run-id :seon.agent.run/id-of-run}]
  (if-not (admission/available?)
    {:seon.eval/ids []
     :seon.eval/n-ok 0
     :seon.eval/n-fail 0
     :seon/error (:seon/error (admission/unavailable))}
    (let [database (::db/db (db/current-tx-context))
          agent-id (db/current-agent-id)
          configuration
          (let [stored (await (db/entity database
                                         [:seon.config/id
                                          config/cluster-config-id]))]
            (if (database-error? stored) {} (db/decode-edn-values stored)))
          engine (await (ensure-engine! database))
          engine (await (ensure-home-ns! engine starting-ns agent-id))
          _ (reset! (::!authored engine)
                    (await (authored-sources-for database)))
          fence-lost?
          (when run-id
            (database-error?
             (await (db/transact!
                     {::db/db database
                      :seon.db/tx-data
                      [(db/cas-assert [:seon.agent/id agent-id]
                                      :seon.agent/run
                                      [:seon.agent.run/id run-id])]}))))
          eids (volatile! [])
          n-ok (volatile! 0)
          n-fail (volatile! 0)
          current-ns (volatile! starting-ns)]
      (doseq [entry (when-not fence-lost? parsed)
              :while (admission/available?)]
        (let [kind (:seon.repl/kind entry)
              narration (or (:seon.repl/narration entry) "")]
          (cond
            (= :read kind)
            (let [recorded
                  (await
                   (record-entry!
                    engine configuration turn-id agent-id @current-ns
                    {::eval/at (js/Date.)
                     ::eval/duration-ms 0
                     ::eval/narration narration
                     ::eval/source (or (:seon.repl/source entry) "")
                     ::eval/ending-ns @current-ns
                     ::eval/result
                     {::eval/ok? false
                      :seon/error
                      {:seon.error/kind :read
                       :seon.error/message
                       (str "the form did not read — nothing ran: "
                            (get-in entry [:seon/error
                                           :seon.error/message]))}}}))]
              (when-not (database-error? recorded)
                (vswap! eids conj (:seon.eval/id recorded)))
              (vswap! n-fail inc))

            (= :comment kind)
            (let [recorded
                  (await
                   (record-entry!
                    engine configuration turn-id agent-id @current-ns
                    {::eval/at (js/Date.)
                     ::eval/duration-ms 0
                     ::eval/narration narration
                     ::eval/source ""
                     ::eval/ending-ns @current-ns
                     ::eval/result {::eval/ok? true ::eval/value nil}}))]
              (when-not (database-error? recorded)
                (vswap! eids conj (:seon.eval/id recorded))))

            :else
            (let [source (or (:seon.repl/eval-source entry)
                             (:seon.repl/source entry)
                             "")
                  source (or (eval/augment-ns-source source) source)
                  parity (eval/parity-intercept source @current-ns)
                  started (js/Date.now)
                  print-buffer (volatile! [])
                  _ (reset! (::!print engine) print-buffer)
                  raw (if parity
                        {::eval/ok? true
                         ::eval/value (:seon.eval/value parity)
                         ::eval/ending-ns @current-ns}
                        (await (sci-eval-form! engine source @current-ns)))
                  awaited (if (::eval/ok? raw)
                            (await (await-value (::eval/value raw)))
                            raw)
                  _ (reset! (::!print engine) nil)
                  output (str/join "" @print-buffer)
                  duration (- (js/Date.now) started)
                  pending? (contains? awaited ::pending-promise)
                  ending-ns (if (::eval/ok? raw)
                              (::eval/ending-ns raw)
                              @current-ns)
                  result (cond
                           pending?
                           {::eval/ok? true ::eval/value nil}

                           (::eval/ok? awaited)
                           {::eval/ok? true ::eval/value (::eval/value awaited)}

                           :else
                           {::eval/ok? false :seon/error (:seon/error awaited)})
                  tee (when (and (::eval/ok? result) (not pending?))
                        (minimal-tee source ending-ns))
                  recorded
                  (await
                   (record-entry!
                    engine configuration turn-id agent-id ending-ns
                    (cond->
                     {::eval/at (js/Date.)
                      ::eval/duration-ms duration
                      ::eval/narration narration
                      ::eval/source (or (:seon.repl/source entry) source)
                      ::eval/ending-ns ending-ns
                      ::eval/result result
                      ::eval/tee (vec (or tee []))
                      ::db/db database}
                      pending? (assoc ::eval/pending? true)
                      (seq output) (assoc ::eval/output output))))]
              (if (database-error? recorded)
                (vswap! n-fail inc)
                (let [eval-id (:seon.eval/id recorded)]
                  (vswap! eids conj eval-id)
                  (when eval-id
                    (sci/intern (::ctx engine) 'result
                                (symbol (str eval-id))
                                (if pending?
                                  (::pending-promise awaited)
                                  (::eval/value result))))
                  (if (::eval/ok? result)
                    (do (vswap! n-ok inc)
                        (vreset! current-ns ending-ns))
                    (vswap! n-fail inc))))))))
      (cond-> {:seon.eval/ids @eids
               :seon.eval/n-ok @n-ok
               :seon.eval/n-fail @n-fail}
        fence-lost? (assoc :seon.eval/fenced? true)
        (not (admission/available?))
        (assoc :seon/error (:seon/error (admission/unavailable)))))))

;;; ------------------------------------------------------------------
;;; Artifact composition — production render entries, sci eval entry.
;;; ------------------------------------------------------------------

(def compiled-functions
  "The production compiled map with eval-batch! routed through sci."
  (assoc runtime/compiled-functions
         'seon.execution.runtime/eval-batch!
         {::execution/compiled-function
          (fn [arguments _invoke-selected! _compile-state! _prepare-program!]
            (apply sci-eval-batch! arguments))
          ::execution/pin-database? true}))

(defn -main
  "Start the sci-engined execution child through the production owner."
  []
  (execution/-main compiled-functions))
