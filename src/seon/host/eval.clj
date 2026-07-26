(ns seon.host.eval
  "Serve recorded SCI eval batches for the JVM execution host."
  (:require [sci.core :as sci]
            [sci.ctx-store]
            [seon.db.transport.uds :as uds]
            [seon.error.sci :as error.sci]
            [seon.host.context :as context]
            [seon.host.graduate :as graduate]
            [seon.host.guard :as guard]
            [seon.host.instrument :as instrument]
            [seon.host.preflight :as preflight]
            [seon.host.record :as record]
            [seon.host.session.leaf :as session]
            [seon.program.edge :as edge]
            [seon.repl.parse.repair :as repair]
            [seon.schema :as schema])
  (:import [java.io Writer]))

(set! *warn-on-reflection* true)

(def ^:private output-truncation-marker "…⟨output truncated⟩")

(def ^:private repair-policy-keys
  [:seon.config.repair/level
   :seon.config.repair.class/delimiters?
   :seon.config.repair.class/def-vs-defn?
   :seon.config.repair.class/undeclared-var?
   :seon.config.repair/max-fixes-per-form
   :seon.config.repair/budget-ms])

(defn agent-home-ns
  "The deterministic home-ns symbol for an agent id.

   Mirrors `seon.agent.home/home-ns` (the pod-side owner of the
   derivation): `(agent-home-ns \"seon\") => 'my.agent.seon`."
  {:malli/schema [:=> [:cat [:string {:min 1}]] :symbol]}
  [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn- entry-source [entry]
  (or (:seon.repl/eval-source entry) (:seon.repl/source entry)))

(defn- sci-var-symbol [value]
  (when (instance? sci.lang.Var value)
    (sci/var->symbol value)))

(defn- source-effect [target sci-var]
  (or (:seon.capability/effect (meta sci-var))
      (when-let [host-ns (some-> target namespace symbol find-ns)]
        (some-> (ns-resolve host-ns (symbol (name target)))
                meta
                :seon.capability/effect))))

(defn namespace-resolution
  "Return the retained P1 symbol resolution used to analyze parsed forms."
  {:malli/schema [:=> [:cat 'some? :symbol]
                  :seon.program.edge/resolution]}
  [ctx ns-sym]
  (let [namespaces (:namespaces @(:env ctx))
        current (get namespaces ns-sym)
        entries
        (for [[_ namespace-map] namespaces
              [_ value] namespace-map
              :let [target (sci-var-symbol value)]
              :when target]
          [target value])]
    {::edge/namespace ns-sym
     ::edge/aliases (into {} (:aliases current))
     ::edge/refers
     (into {}
           (keep (fn [[local value]]
                   (when-let [target (sci-var-symbol value)]
                     [local target])))
           (:refers current))
     ::edge/current-vars
     (into #{}
           (keep (fn [[local value]]
                   (when (and (symbol? local) (sci-var-symbol value))
                     local)))
           current)
     ::edge/core-vars
     (into #{}
           (keep (fn [[local value]]
                   (when (and (symbol? local) (sci-var-symbol value))
                     local)))
           (get namespaces 'clojure.core))
     ::edge/known-namespaces
     (into #{} (filter symbol?) (keys namespaces))
     ::edge/macro-symbols
     (into #{}
           (keep (fn [[target value]]
                   (when (:macro (meta value)) target)))
           entries)
     ::edge/effects
     (into {}
           (keep (fn [[target value]]
                   (when-let [effect (source-effect target value)]
                     [target effect])))
           entries)}))

(defn classified-error-value
  "Classify one SCI throwable as an execution error value."
  {:malli/schema [:=> [:cat :any :symbol :any] :map]}
  [ctx home-ns throwable]
  (or (when-let [holder (::guard/holder ctx)]
        (guard/steering-error! holder throwable))
      (let [classified
            (error.sci/classify
             {:seon.error.sci/throwable throwable
              :seon.error.sci/context ctx
              :seon.error.sci/home-ns home-ns})]
        (assoc classified :seon.error/message
               (error.sci/steering-head
                classified error.sci/default-error-head-token-cap)))))

(defn- transit-safe-value
  [value]
  (try
    (uds/encode {::probe value})
    value
    (catch Throwable _
      (cond
        (map? value) (into (empty value)
                           (map (fn [[k v]] [(transit-safe-value k)
                                             (transit-safe-value v)]))
                           value)
        (vector? value) (mapv transit-safe-value value)
        (set? value) (into #{} (map transit-safe-value) value)
        (list? value) (apply list (map transit-safe-value value))
        (seq? value) (doall (map transit-safe-value value))
        :else (pr-str value)))))

(defn- wire-safe-value
  "Keep a transit-encodable value; project anything else to its print form.

   sci vars (every `def`'s return) and other host objects cannot cross the
   protocol; their envelope keeps `:seon.eval/value-display` instead."
  [envelope]
  (let [envelope
        (if-not (contains? envelope :seon.eval/value)
          envelope
          (let [value (:seon.eval/value envelope)]
            (try
              (uds/encode {::probe value})
              envelope
              (catch Throwable _
                (-> envelope
                    (dissoc :seon.eval/value)
                    (assoc :seon.eval/value-display (pr-str value)))))))]
    (transit-safe-value envelope)))
(defn- output-capture [holder database-edn-cap]
  (let [limit (max 0 (- database-edn-cap
                        (count output-truncation-marker)))
        text (StringBuilder.)
        truncated? (volatile! false)
        retain!
        (fn [x offset length]
          (let [remaining (max 0 (- limit (.length text)))
                retained (min remaining length)]
            (when (pos? retained)
              (if (string? x)
                (.append text ^CharSequence x (int offset)
                         (int (+ offset retained)))
                (.append text ^chars x (int offset) (int retained))))
            (when (> length retained)
              (vreset! truncated? true)
              (guard/stop! holder :agent))))
        writer
        (proxy [Writer] []
          (write
            ([x]
             (if (string? x)
               (retain! x 0 (count x))
               (retain! (char-array [(char x)]) 0 1)))
            ([x offset length]
             (retain! x offset length)))
          (flush [] nil)
          (close [] nil))]
    {::output-writer writer
     ::output-exceeded? (fn [] @truncated?)
     ::output-text (fn []
                     (str text (when @truncated?
                                 output-truncation-marker)))}))

(defn finish-evaluation!
  "Apply the invocation interrupt state to one eval envelope."
  {:malli/schema [:=> [:cat ::session/session :map] :map]}
  [session envelope]
  (let [interrupted?
        (locking (::session/interrupt-lock session)
          (reset! (::session/worker-phase session) :recording)
          (let [fired? @(::session/interrupt-fired? session)
                flagged? (Thread/interrupted)]
            (or fired? flagged?)))]
    (if (and interrupted? (not (:seon.eval/interrupted? envelope)))
      {:seon.eval/ok? false
       :seon.eval/interrupted? true
       :seon/error
       (guard/policy-error! (::guard/holder (::session/ctx session))
                            :timeout)}
      envelope)))

(defn eval-form!
  "Evaluate one prepared source in the agent context; every outcome a value.

   `::var-meta` (a returned sci var's metadata, the tee's projection
   input) is host-internal and stripped before the envelope crosses the
   protocol."
  {:malli/schema
   [:function
    [:=> [:cat ::session/session :any :symbol :string] :map]
    [:=> [:cat ::session/session :any :symbol :string [:int {:min 1}]] :map]]}
  ([session ctx home-ns source]
   (eval-form! session ctx home-ns source 16384))
  ([session ctx home-ns source database-edn-cap]
   (let [{::keys [output-writer output-exceeded? output-text]}
         (output-capture (::guard/holder ctx) database-edn-cap)]
     (locking (::session/interrupt-lock session)
       (reset! (::session/worker-phase session) :evaluating))
     (let [envelope
           (try
             (let [value (sci/with-bindings {sci/out output-writer
                                             sci/err output-writer}
                           (sci/eval-string* ctx source))]
               (cond-> (assoc (sci.ctx-store/with-ctx ctx
                                (wire-safe-value {:seon.eval/ok? true
                                                  :seon.eval/value value}))
                              ::live-value value)
                 (instance? sci.lang.Var value)
                 (assoc ::var-meta (meta value))))
             (catch Throwable throwable
               (let [error (classified-error-value ctx home-ns throwable)
                     interrupted? (= :interrupt
                                     (get-in error [:seon.error/data
                                                    :seon.error.sci/class]))]
                 (wire-safe-value
                  {:seon.eval/ok? false
                   :seon.eval/interrupted? interrupted?
                   :seon/error error}))))
           envelope (finish-evaluation! session envelope)
           envelope
           (if (and (output-exceeded?) (:seon.eval/ok? envelope))
             {:seon.eval/ok? false
              :seon.eval/interrupted? true
              :seon/error
              (guard/policy-error! (::guard/holder ctx) :agent)}
             envelope)
           output (output-text)]
       (cond-> envelope
         (seq output) (assoc ::output output))))))

(defn- read-error-envelope [entry]
  {:seon.eval/ok? false
   :seon/error (session/error-value
                (str "The form could not be read: "
                     (or (:seon.repl/message entry) "read error"))
                :agent)})

(defn- batch-summary
  [ids results]
  (let [evaluated (remove :seon.eval/skipped? results)]
    {:seon.eval/ids ids
     :seon.eval/n-ok (count (filter :seon.eval/ok? evaluated))
     :seon.eval/n-fail (count (remove :seon.eval/ok? evaluated))
     :seon.host/results (vec results)}))

(defn- run-fence-transaction
  [agent-id run-fence]
  (let [run-ref [:seon.agent.run/id (:seon.agent.run/id run-fence)]
        claim-epoch (:seon.agent.run/claim-epoch run-fence)]
    (if (int? claim-epoch)
      [[:db.fn/cas [:seon.agent/id agent-id]
        :seon.agent/run run-ref run-ref]
       [:db.fn/cas run-ref :seon.agent.run/claim-epoch
        claim-epoch claim-epoch]]
      (throw
       (ex-info "The invocation run fence is missing its held claim epoch."
                {:seon.error/kind :core-bug
                 :seon.agent/id agent-id
                 :seon.execution/run-fence run-fence})))))

(defn claim-run-fence!
  "Claim one invocation run fence at its immutable database value."
  {:malli/schema
   [:=> [:cat ::context/writer :seon.db/db [:string {:min 1}]
         [:map-of :qualified-keyword :any]]
    [:or :nil :map]]}
  [writer database agent-id run-fence]
  (when (seq run-fence)
    (context/transact-writer!
     writer database (run-fence-transaction agent-id run-fence))))

(defn- declared-next-ns
  "The ns an executed source moves the batch to, when it moves it.

   An explicit `(ns X …)` or `(in-ns 'X)` as the FIRST form advances the
   fold; ordinary forms cannot move the REPL namespace."
  [forms]
  (let [form (first forms)]
    (cond
      (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (second form)

      (and (seq? form) (= 'in-ns (first form))
           (seq? (second form)) (= 'quote (first (second form)))
           (symbol? (second (second form))))
      (second (second form))

      :else nil)))

(defn- install-recorded-function!
  "Install one durably recorded function through the nursery registry path."
  [session function-row function-var]
  (let [instrument-state (::instrument/state session)
        lib (some-> (:seon.fn/sym function-row) symbol namespace symbol)
        loaded-contexts
        (into []
              (filter #(sci/find-ns % lib))
              (vals @(::session/contexts session)))
        outcome
        (graduate/install-nursery!
         {::context/registry (::instrument/registry instrument-state)
          ::graduate/function-row function-row
          ::graduate/function-var function-var
          ::graduate/contexts loaded-contexts})]
    (when-not (::graduate/ok? outcome)
      (throw
       (ex-info (or (::graduate/error outcome)
                    "The recorded nursery function did not install.")
                {:seon.error/kind :core-bug
                 :seon.host.graduate/outcome outcome})))
    outcome))

(defn eval-batch-result
  "Serve the host-session eval batch over SCI with recording.

   Each executed form records through the one corpus mechanism: a
   `:running` receipt with a managed `:seon.eval/id` commits BEFORE the
   form runs (the durable execution boundary — no receipt, no run), and
   one terminal transaction carries the CAS fence, the frozen eval row,
   and every program-graph row the form tees (`:seon.fn` for a single
   defn, `:seon.ns` + require edges for an ns declaration,
   `:seon.schema` for registrations detected by registry diff). The
   batch evals in the request's starting ns so defs land in the agent's
   home namespace, not scratch `user`. Recording engages only when the
   request names its owning turn; receiptless probes stay engine-only
   with empty `:seon.eval/ids`."
  {:malli/schema
   [:=> [:cat ::session/session :map :map :seon.db/db
         [:map-of :qualified-keyword :any]]
    :map]}
  [session {parsed :seon.eval/parsed
            starting-ns :seon.eval/starting-ns
            turn-id :seon.agent.turn/id-of-turn}
   sampling-limits database run-fence]
  (let [ctx (::session/ctx session)
        writer (::session/writer session)
        agent-id (:seon.execution/agent-id @(::session/startup session))
        record? (boolean (and writer turn-id agent-id))
        batch-ns (or starting-ns 'user)
        database-edn-cap
        (:seon.config.render/database-edn-cap sampling-limits)
        value-sampling-limits
        (apply dissoc sampling-limits
               (conj repair-policy-keys
                     :seon.config.render/database-edn-cap))
        fence-result (claim-run-fence! writer database agent-id run-fence)]
    (if (:seon.error/message fence-result)
      (assoc (batch-summary [] []) :seon.eval/fenced? true)
      (do
        (when-not (contains? record/transient-ns-syms batch-ns)
          (context/ensure-context-ns! ctx batch-ns))
        (loop [entries (vec (or parsed []))
               current-ns batch-ns
               ids []
               results []]
          (if (empty? entries)
            (batch-summary ids results)
            (let [entry (first entries)
                  kind (:seon.repl/kind entry)
                  repaired
                  (when (= :read kind)
                    (preflight/repair-read-entry
                     ctx current-ns sampling-limits entry))]
              (if repaired
                (recur (into (vec (:seon.host.preflight/entries repaired))
                             (rest entries))
                       current-ns ids results)
                (if-not (contains? #{:form :read} kind)
            ;; comment/prose entries evaluate and record nothing.
                  (recur (rest entries) current-ns ids
                         (conj results {:seon.eval/ok? true
                                        :seon.eval/skipped? true}))
                  (let [source (or (entry-source entry) "")
                      narration (or (:seon.repl/narration entry) "")
                      at (java.util.Date.)
                      start-ms (session/now-ms)
                      started (when record?
                                (context/start-eval-receipt!
                                 writer
                                 {:seon.agent.turn/id turn-id
                                  :seon.eval/at at
                                  :seon.eval/source source
                                  :seon.eval/narration narration
                                  :seon.eval/ns current-ns
                                  :seon.agent/id agent-id}))]
                  (if (and record? (:seon.error/message started))
                ;; The receipt is the durable execution boundary: a form
                ;; whose receipt cannot commit never runs.
                    (recur (rest entries) current-ns ids
                           (conj results {:seon.eval/ok? false
                                          :seon/error started}))
                    (let [preflight-result
                          (when (= :form kind)
                            (preflight/preflight!
                             ctx
                             (:seon.host.instrument/registry
                              (::instrument/state session))
                             (agent-home-ns agent-id)
                             current-ns sampling-limits source))
                          fixed? (= :fixed
                                    (:seon.host.preflight/status
                                     preflight-result))
                          source (if fixed?
                                   (:seon.repl.parse.repair/source preflight-result)
                                   source)
                          narration
                          (if fixed?
                            (str (when (seq narration) (str narration "\n"))
                                 (repair/fix-note
                                  {:seon.repl.parse.repair/fixes
                                   (:seon.repl.parse.repair/fixes preflight-result)}))
                            narration)
                          schema-delta (schema/begin-registration-delta)
                      raw-envelope
                      (schema/call-with-registration-delta
                        schema-delta
                        #(cond
                           (= :terminal
                              (:seon.host.preflight/status preflight-result))
                           (:seon.host.preflight/envelope preflight-result)

                           (= :form kind)
                           (instrument/call-with-read-admission
                            (::instrument/state session)
                            (fn []
                              (eval-form! session ctx (agent-home-ns agent-id)
                                          (str "(in-ns '" current-ns ")\n"
                                               source)
                                          database-edn-cap)))

                           :else
                           (read-error-envelope entry)))
                      raw-envelope
                      (cond-> raw-envelope
                        (seq (:seon.repl.parse.repair/changes entry))
                        (assoc :seon.repl.parse.repair/changes
                               (:seon.repl.parse.repair/changes entry))

                        (seq (:seon.repl.parse.repair/fixes preflight-result))
                        (assoc :seon.repl.parse.repair/fixes
                               (:seon.repl.parse.repair/fixes preflight-result)
                               :seon.repl.parse.repair/applied-class
                               (:seon.repl.parse.repair/applied-class
                                preflight-result)))
                      ok? (boolean (:seon.eval/ok? raw-envelope))
                      ;; A failed eval must not leave half a registration:
                      ;; discard only this form's isolated registration delta.
                      _ (when (and (= :form kind) (not ok?))
                          (schema/restore! schema-delta))
                      new-schema-keys (if ok?
                                        (schema/commit-registration-delta!
                                          schema-delta)
                                        #{})
                      resolution (when (= :form kind)
                                   (namespace-resolution ctx current-ns))
                      forms (if (= :form kind)
                              (record/read-forms
                               {::record/source source
                                ::record/ns-sym current-ns
                                ::record/aliases (::edge/aliases resolution)})
                              [])
                      var-meta (::var-meta raw-envelope)
                      live-value (::live-value raw-envelope)
                      output (::output raw-envelope)
                      envelope
                      (wire-safe-value
                       (dissoc raw-envelope ::var-meta ::live-value ::output))
                      eval-id (:seon.eval/id started)
                      recorded
                      (when (and record? eval-id)
                        (context/record-eval-terminal!
                         writer
                         {:seon.eval/id eval-id
                          ::context/envelope envelope
                          ::context/at at
                          ::context/duration-ms (- (session/now-ms) start-ms)
                          ::context/source source
                          ::context/narration narration
                          ::context/ns-sym current-ns
                          ::context/resolution resolution
                          ::context/agent-id agent-id
                          ::context/forms forms
                          ::context/var-meta var-meta
                          ::context/new-schema-keys new-schema-keys
                          ::context/output output
                          ::context/database-edn-cap database-edn-cap}))
                      projection-change?
                      (true? (::context/projection-changed? recorded))
                      recorded-function-rows
                      (::context/function-rows recorded)
                      committed-projection
                      (::context/committed-projection recorded)
                      projection-refresh
                      (when (and ok? recorded (:seon.db/ok? recorded)
                                 (or (seq recorded-function-rows)
                                     projection-change?))
                        (instrument/call-with-write-admission
                         (::instrument/state session)
                         (fn []
                           (doseq [function-row recorded-function-rows]
                             (install-recorded-function!
                              session function-row live-value))
                           (when projection-change?
                             (instrument/publish-maintained-and-reconcile!
                              (::instrument/state session)
                              (:db-after recorded)
                              committed-projection)))))
                      _ (when (:seon/error projection-refresh)
                          (throw
                            (ex-info
                              (get-in projection-refresh
                                      [:seon/error :seon.error/message])
                              {:seon.error/kind :core-bug
                               :seon.host/projection-error
                               (:seon/error projection-refresh)})))
                      ids (if (and recorded (:seon.db/ok? recorded))
                            (conj ids eval-id)
                            ids)
                      envelope (if (and recorded
                                        (not (:seon.db/ok? recorded)))
                                 ;; The outcome could not become durable —
                                 ;; surface it on the envelope as data.
                                 (assoc envelope ::record-error recorded)
                                 envelope)
                      next-ns (or (when ok? (declared-next-ns forms))
                                  current-ns)]
                      (if (:seon.eval/interrupted? envelope)
                        (batch-summary ids (conj results envelope))
                        (recur (rest entries) next-ns ids
                               (conj results envelope)))))))))))))))

(defn interrupted-batch?
  "Whether an eval-batch result contains an interrupted form."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [result]
  (boolean (some :seon.eval/interrupted? (:seon.host/results result))))

(defn interrupted-error
  "The first interrupted form's error value, when present."
  {:malli/schema [:=> [:cat :map] [:or :nil :map]]}
  [result]
  (some (fn [envelope]
          (when (:seon.eval/interrupted? envelope)
            (:seon/error envelope)))
        (:seon.host/results result)))
