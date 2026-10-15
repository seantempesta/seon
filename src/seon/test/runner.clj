(ns seon.test.runner
  "Capture test Var results in this JVM and commit them as per-member facts."
  (:require [seon.error.refusal]
            [sci.ctx-store]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :as test]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [malli.instrument :as mi]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.blob :as blob]
            [seon.cache :as cache]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.instrument :as instrument]
            [seon.id :as id]
            [seon.ai.tokens :as tokens]
            [seon.program :as program]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.sci.admit :as admit]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(defn var-reference?
  "True for a host or SCI Var reference."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (or (var? value) (sci.utils/var? value)))

(def var-generator
  "Finite representatives for the closed host/SCI Var representation sum."
  (gen/elements [#'var-reference? #'var-generator]))

(schema/register-core-predicate! 'seon.test.runner/var-reference?
                                 var-reference?)

(def class-loader-generator
  (gen/return (clojure.lang.RT/baseLoader)))

(defn class-loader?
  "Whether a supplied runtime value is a JVM class loader."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? ClassLoader value))

(schema/register-core-predicate! 'seon.test.runner/class-loader? class-loader?)

(defn- var-symbol
  [test-var]
  (when test-var
    (let [{:keys [name ns]} (meta test-var)]
      (when (and name ns)
        (symbol (str ns) (str name))))))

(defn- event-symbol
  [event]
  (var-symbol (or (:var event) (first test/*testing-vars*))))

(defn- report-options
  ([]
   (let [configuration config/defaults]
    (assoc (select-keys configuration
                        [:seon.print/length :seon.print/level])
           :seon.render/profile
           (render/agent-render-profile configuration)
           :seon.schema/projection (schema/handed-projection)
           :seon.test/time-limit-ms
           (get-in (schema.edn/packaged-forms) [:seon.test/time-limit-ms 1 :default]))))
  ([supplied]
   (merge (or (::report-options supplied) (report-options))
          (select-keys supplied
                       [:seon.print/length :seon.print/level :seon.render/profile]))))

(defn- throwable-signature
  [^Throwable failure]
  (loop [current failure]
    (when current
      (or (:seon.error/signature (ex-data current))
          (recur (ex-cause current))))))

(defn- event-signature
  [event]
  (or (:seon.error/signature event)
      (when (instance? Throwable (:actual event))
        (throwable-signature (:actual event)))))

(defn- report-value
  "One reported assertion value, rendered for a human reading the gate log.

  A throwable is shown as its cause chain (`seon.error.refusal/chain`): per
  link its class, message, ex-data and first-party frames, as data the value
  printer bounds, so each link's message is shown beside its data rather
  than squeezed out by it.

  Ordinary values keep the profile: they are the ones that can be a whole
  database value. The unit's required database is the one `options` hands
  (recording, outside any body's custody) or the runner's own custody."
  {:malli/schema [:=> [:cat [:map [:seon.db/db {:optional true} :seon.db/database-value]] :seon.schema/value]
                  :string]}
  [options reported-value]
  (value/render-ai
   (cond-> {:seon.render/value (cond-> reported-value
                                 (instance? Throwable reported-value) seon.error.refusal/chain)
            :seon.render.value/root 'seon.test.runner/assertion
            :seon.render/profile (or (:seon.render/profile options)
                                     (:seon.render/profile (report-options)))}
     (:seon.db/db options) (assoc :seon.db/db (:seon.db/db options))
     (and (not (:seon.db/db options)) db/*conn*) (assoc :seon.db/db (db/db db/*conn*)))))

(defn- message-text
  "An assertion's `is` message as shown in the gate log. A string within the
  profile's token budget counted as characters (a token is at least one) is
  shown verbatim, as clojure.test prints it; anything longer or not a string is
  an ordinary reported value, bounded once by `report-value` with its elision
  named. The whole message stays on the stored failure (`failure-report`),
  which recording stages to the blob tier."
  [options message]
  (let [profile (or (:seon.render/profile options) (:seon.render/profile (report-options)))]
    (if (and (string? message)
             (<= (count message) (:seon.render.profile/token-budget profile)))
      message
      (report-value (assoc options :seon.render/profile profile) message))))

(defn- failure-message
  "The gate-log text of one failing assertion, laid out from its
  `failure-report`: the operands are rendered once there, never again here."
  {:malli/schema [:=> [:cat :map :map :seon.test.failure/report] :string]}
  [options event report]
  (->> [(when (seq test/*testing-contexts*)
          (test/testing-contexts-str))
        (when (some? (:message event)) (message-text options (:message event)))
        (some->> (:seon.test.failure/expected report) (str "expected: "))
        (some->> (:seon.test.failure/actual report) (str "actual: "))]
       (remove str/blank?)
       (str/join "\n")))

(defn- ensure-result
  [capture test-symbol]
  (if (contains? (::results capture) test-symbol)
    capture
    (-> capture
        (update ::order conj test-symbol)
        (assoc-in [::results test-symbol]
                  {:seon.test/sym test-symbol
                   :seon.test/pass-count 0
                   :seon.test/fail-count 0
                   :seon.test/error-count 0
                   :seon.test.member/began? false
                   :seon.test.member/ended? false
                   ::failure-messages []
                   ::failure-identities #{}}))))

(defn- report-pair
  "An assertion's expected and actual texts as ONE shown unit: one token budget
  is shared, never granted twice (CLAUDE.md \"Apply presentation limits
  once\"). That budget is the profile's, no more than the characters the
  blob threshold keeps inline (`seon.ai.tokens/estimate-of-characters`), so
  the shown text is stored inline beside the failure and every reader shows
  it. Expected renders under half of it and actual under the rest, so a small
  expected leaves its unused share to actual. A thrown value is the floor's
  text, which the profile does not bound."
  {:malli/schema [:=> [:cat :map :map] [:tuple [:maybe :string] [:maybe :string]]]}
  [options event]
  (let [profile (or (:seon.render/profile options) (:seon.render/profile (report-options)))
        budget (min (:seon.render.profile/token-budget profile)
                    (tokens/estimate-of-characters
                     (:seon.config.eval.result/blob-threshold config/defaults)))
        under (fn [tokens] (assoc options :seon.render/profile
                                  (assoc profile :seon.render.profile/token-budget (max 1 tokens))))
        share (if (and (find event :expected) (find event :actual)) (quot budget 2) budget)
        expected (when (find event :expected) (report-value (under share) (:expected event)))
        spent (if expected (min share (tokens/estimate expected)) 0)
        actual (when (find event :actual) (report-value (under (- budget spent)) (:actual event)))]
    [expected actual]))

(defn- failure-report
  "One failing assertion as stored evidence. Expected and actual are the text
  `report-value` renders — the text the gate log shows, bounded once by the
  value renderer with each elision named, or a throwable's whole cause chain
  under the floor — never a whole-value print: a test error carrying a
  program overlay printed a >2 GB string and killed default with
  OutOfMemoryError (2026-09-24T23:27Z). The whole message stays on the
  report for the blob tier; the whole values stay on the event in memory."
  {:malli/schema [:=> [:cat :map :map] :seon.test.failure/report]}
  [options event]
  (let [[expected actual] (report-pair options event)
        error-type (when (instance? Throwable (:actual event))
                     (:seon.error/type (ex-data (:actual event))))]
  (cond-> (with-meta {:seon.test.failure/type (:type event)}
            ;; The whole thrown value rides as metadata, in memory only: it is
            ;; no part of the report's value (equality, printing, identity),
            ;; and recording stores it as its error value in the blob tier
            ;; (`prepare-failures!`); `actual` is only its shown text.
            (when (instance? Throwable (:actual event)) {::actual-throwable (:actual event)}))
    (find event :expected) (assoc :seon.test.failure/expected expected)
    (find event :actual) (assoc :seon.test.failure/actual actual)
    (:message event) (assoc :seon.test.failure/message (str (:message event)))
    (seq test/*testing-contexts*)
    ;; A label prints as `clojure.test/testing-contexts-str` prints it, by `str`
    ;; (`reference-code/clojure/src/clj/clojure/test.clj:307`);
    ;; the stored context is its string, whatever value the label was.
    (assoc :seon.test.failure/testing-contexts (mapv str (reverse test/*testing-contexts*)))
    (:file event) (assoc :seon.test.failure/reported-file (str (:file event)))
    (and (integer? (:line event)) (pos? (:line event)))
    (assoc :seon.test.failure/line (:line event))
    (event-signature event) (assoc :seon.test.failure/signature (event-signature event))
    error-type (assoc :seon.error/type error-type)
    (instance? Throwable (:actual event))
    (assoc :seon.test.failure/throwable (symbol (.getName (class (:actual event))))))))

(defn- report-identity
  "Content identity of one failing assertion from its stored `report`.

  SCI reports interpreted tests from generic JVM frames, so a source position
  would claim precision the reporter does not have. The claim is the test,
  the event type, the bounded message and the report's bounded expected and
  actual text: stable across reruns, distinct for claims that render
  differently, and costing the render bound, never the values' printed size."
  {:malli/schema [:=> [:cat :map :qualified-symbol :map :seon.test.failure/report] [:string {:min 64 :max 64}]]}
  [options test-symbol event report]
  (id/id [test-symbol
          (:type event)
          (when (some? (:message event)) (message-text options (:message event)))
          (:seon.test.failure/expected report)
          (:seon.test.failure/actual report)
          (event-signature event)]
         64))

(defn- failure-identity
  "Content identity for one failing assertion event (`report-identity`)."
  [options test-symbol event]
  (report-identity options test-symbol event (failure-report options event)))

(defn- capture-event!
  [options capture selected-namespaces event report]
  (when-let [test-symbol (event-symbol event)]
    (when (contains? selected-namespaces (symbol (namespace test-symbol)))
      ;; The report is rendered once by the caller, before the swap: a retried
      ;; swap never renders again.
      (let [failure-id (when report (report-identity options test-symbol event report))
            message (when report (failure-message options event report))]
        (swap! capture
               (fn [current]
                 (let [current (ensure-result current test-symbol)
                       event-type (:type event)]
                   (case event-type
                     :begin-test-var
                     (-> current
                         (assoc-in [::results test-symbol :seon.test.member/began?] true)
                         (assoc-in [::results test-symbol ::started-nanos] (System/nanoTime)))

                     :end-test-var
                     (-> current
                         (assoc-in [::results test-symbol :seon.test.member/ended?] true)
                         (assoc-in [::results test-symbol ::ended-nanos] (System/nanoTime)))

                     :pass
                     (update-in current [::results test-symbol
                                         :seon.test/pass-count] inc)

                     (:fail :error)
                     (let [seen? (contains?
                                  (get-in current [::results test-symbol
                                                   ::failure-identities])
                                  failure-id)]
                       (cond-> (-> current
                                   (update-in [::results test-symbol :seon.test.failure/reports]
                                              (fnil conj [])
                                              (assoc report :seon.test/failure-identity failure-id))
                                   (update-in
                                          [::results test-symbol
                                           (if (= :fail event-type)
                                             :seon.test/fail-count
                                             :seon.test/error-count)]
                                          inc))
                         (not seen?)
                         (update-in [::results test-symbol
                                     ::failure-identities] conj failure-id)
                         (not seen?)
                         (update-in [::results test-symbol ::failure-messages]
                                    conj message)))

                     current))))))))

(defn- report-error!
  [options event report signature]
  (test/with-test-out
    (test/inc-report-counter :error)
    (println "\nERROR in" (test/testing-vars-str event))
    (println (failure-message options event report))
    (when signature
      (println "  signature:" signature))))

(defn- report-event!
  [options default-report reported-signatures event report]
  (if (and (= :error (:type event))
           (instance? Throwable (:actual event)))
    (let [signature (event-signature event)]
      (if (and signature (contains? @reported-signatures signature))
        (test/inc-report-counter :error)
        (do
          (when signature
            (swap! reported-signatures conj signature))
          (report-error! options event report signature))))
    (if (#{:fail :error} (:type event))
      (test/with-test-out
        (test/inc-report-counter (:type event))
        (println (str "\n" (str/upper-case (name (:type event))) " in")
                 (test/testing-vars-str event))
        (println (failure-message options event report)))
      (default-report event))))

(defn- assertionless-failure
  [capture event]
  (when (= :end-test-var (:type event))
    (let [test-symbol (event-symbol event)
          result (get-in @capture [::results test-symbol])
          assertion-count
          (+ (get result :seon.test/pass-count 0)
             (get result :seon.test/fail-count 0)
             (get result :seon.test/error-count 0))]
      (when (and result (zero? assertion-count))
        {:type :fail
         :var (:var event)
         :message (str "Test " test-symbol
                       " completed without assertion evidence.")
         :expected '(pos? assertion-count)
         :actual assertion-count}))))

(defn declared-limit-ms
  "The body bound `test-var` declares: its `:seon.test/long-ms` when it also names its `:seon.test/long` reason,
  never below `ordinary`, the packaged `:seon.test/time-limit-ms` by default."
  {:malli/schema [:function [:=> [:cat :seon.test/var] :seon.test/time-limit-ms]
                  [:=> [:cat :seon.test/var :seon.test/time-limit-ms] :seon.test/time-limit-ms]]}
  ([test-var]
   (declared-limit-ms test-var (get-in (schema.edn/packaged-forms) [:seon.test/time-limit-ms 1 :default])))
  ([test-var ordinary]
   (let [metadata (meta test-var)
         declaration (program/test-markers metadata (meta (:ns metadata)))
         reason (:seon.test/long declaration)
         allowance (:seon.test/long-ms declaration)]
     (if (and (string? reason) (not (str/blank? reason))
              (integer? allowance) (pos? allowance))
       (max ordinary allowance) ordinary))))

(defn- duration-failures
  "Turn an observed body overrun into ordinary, durably recorded assertion evidence."
  {:malli/schema [:=> [:cat :seon.test.runner/report-options :seon.test/var :seon.test/elapsed-ms :seon.test/time-limit-ms]
                  [:vector :seon.test/duration-failure]]}
  [_options test-var elapsed ordinary]
  (let [limit (declared-limit-ms test-var ordinary)]
    (if (> elapsed limit)
      [{:type :fail :var test-var
        :message (str "Test " (var-symbol test-var) " exceeded its declared duration: "
                      elapsed " ms; bound " limit " ms.")
        :expected {:seon.test/time-limit-ms limit}
        :actual {:seon.test/elapsed-ms elapsed}}]
      [])))

(defn- capture-and-report-event!
  "Capture and print each event; a failing one's `failure-report` is rendered
  once and serves the stored evidence, its identity and the gate log."
  [options capture selected-namespaces default-report reported-signatures event]
  (let [report-of #(when (#{:fail :error} (:type %)) (failure-report options %))
        report (report-of event)
        both! (fn [failure]
                (let [evidence (report-of failure)]
                  (capture-event! options capture selected-namespaces failure evidence)
                  (report-event! options default-report reported-signatures failure evidence)))]
    (capture-event! options capture selected-namespaces event report)
    (some-> (assertionless-failure capture event) both!)
    (when (= :end-test-var (:type event))
      (when-let [started (get-in @capture [::results (event-symbol event) ::started-nanos])]
        (run! both! (duration-failures options (:var event)
                                       (/ (double (- (get-in @capture [::results (event-symbol event) ::ended-nanos])
                                                     started)) 1e6)
                                       (:seon.test/time-limit-ms options)))))
    (report-event! options default-report reported-signatures event report)))

(defn- captured-results
  [{::keys [order results]}]
  (mapv
   (fn [test-symbol]
     (let [result (get results test-symbol)
           messages (::failure-messages result)
           identities (::failure-identities result)]
       (cond-> (dissoc result ::failure-messages ::failure-identities ::started-nanos ::ended-nanos)
         (seq identities)
         (assoc :seon.test/failing-assertions (vec (sort identities)))
         (seq messages)
         (assoc :seon.test/failure-message (str/join "\n\n" messages)))))
   order))

(declare ambient-snapshot ambient-drift run-selected-tests)

(defn run-vars!
  "Capture selected host or SCI Vars together, applying namespace fixtures once."
  {:malli/schema
   [:=> [:cat [:vector :seon.test/var] :seon.db/custody-request]
    [:or [:vector :seon.test.runner/captured-result]
     :seon.test/not-runnable-error]]}
  [test-vars custody]
  ;; An SCI test Var's :test and its callees are read as the custody's
  ;; context binds them. A host run binds no context: a nil binding would
  ;; hide the one a library stored (clj-kondo's hooks `reset-ctx!` it).
  (with-bindings (if-let [ctx (:seon.sci.eval/ctx custody)] {#'sci.ctx-store/*ctx* ctx} {})
    (if-let [unrunnable (first (remove #(ifn? (:test (meta %))) test-vars))]
      (seon.error.refusal/error :seon.test/not-runnable-error
       {:seon.error/layer :seon.test/execution
        :seon.error/operation `run-vars!
        :seon.error/message "The supplied Var has no clojure.test function."
        :seon.test/not-runnable (str unrunnable)
        :seon.error/member :seon.test/var
        :seon.error/expected "a Var carrying a clojure.test function"
        :seon.error/offending unrunnable
        :seon.error/data {:seon.test/var (str unrunnable)}})
      (let [selected-namespaces (set (map (comp symbol namespace symbol var-symbol) test-vars))
            options (report-options custody)
            capture (atom {::order [] ::results {}})
            reported-signatures (atom #{})
            default-report (.getRawRoot #'test/report)
            before (ambient-snapshot)]
        (binding [test/*report-counters* (ref test/*initial-report-counters*)
                  test/*testing-vars* ()
                  test/*testing-contexts* ()
                  test/report
                  (fn [event]
                    (capture-and-report-event!
                     options capture selected-namespaces default-report
                     reported-signatures event))]
          (db/with-branch custody
            #(run-selected-tests (sort selected-namespaces) test-vars
                                 (:seon.sci.eval/ctx custody))))
        (let [results (captured-results @capture)
              drift (ambient-drift before (ambient-snapshot))]
          (mapv (fn [result]
                  (cond-> result
                    (seq drift) (update :seon.test/error-count (fnil inc 0))
                    (seq drift) (update :seon.test/failure-message
                                        #(str (when % (str % "\n"))
                                              "Worker-global state changed: " (report-value options drift)))))
                results))))))

(defn run-var!
  "Run one host or SCI test Var under the custody its caller hands it, and
  return its captured assertion result.

  This is the same capture and reporter path used by `bin/test`; it performs
  no database write. Each invocation owns its counters, test context and terminal
  reporter; nested runs cannot contribute evidence to their caller.
  `commit-results!` is the sole completion writer.

  THE CUSTODY IS A VALUE, NEVER A RE-READ. `:seon.db/connection` on the
  supplied request is the cluster whose work this run IS: an agent running
  its own declared tests inside its evaluation reaches that cluster through
  the elided `seon.db` arities, exactly as the rest of its evaluation does.
  The one-argument arity hands none — a host REPL or a `bin/test` worker
  running the same Var is nobody's cluster work, and an elided arity there
  refuses loudly and names what it needed.

  Whatever custody this thread INHERITED decides nothing. A `bound-fn` or a
  virtual thread carries the bindings of whoever created it, so before this
  seam took the connection as a value an agent's own test silently read and
  wrote whichever cluster happened to be in scope — on 2026-09-17 that put a
  test's synthetic schema rows into `default`'s datoms and every later write
  on the cluster was refused, and the repair then left the agent's own tests
  with no cluster at all. The caller that knows whose work a run is says so;
  this binds exactly that answer (AGENTS §2.1)."
  {:malli/schema
   [:function
    [:=> [:cat :seon.test/var]
     [:or :seon.test.runner/captured-result
      :seon.test/not-runnable-error]]
    [:=> [:cat :seon.test/var :seon.db/custody-request]
     [:or :seon.test.runner/captured-result
      :seon.test/not-runnable-error]]]}
  ([test-var] (run-var! test-var {}))
  ([test-var custody]
   (let [results (run-vars! [test-var] custody)]
     (if (contains? results :seon.test/not-runnable) results (first results)))))

(defn- run-selected-tests
  ([namespaces selected-vars] (run-selected-tests namespaces selected-vars nil))
  ([namespaces selected-vars ctx]
  (let [selected-by-namespace (group-by (comp :ns meta) selected-vars)]
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (doseq [namespace-name namespaces
              [namespace-object namespace-vars] selected-by-namespace
              :when (= (str namespace-name) (str namespace-object))
              :let [host? (instance? clojure.lang.Namespace namespace-object)
                    bindings (if host? (ns-interns namespace-object)
                                 (get (sci/namespace-state ctx) namespace-name))
                    hook (get bindings 'test-ns-hook)]
              :when (seq namespace-vars)]
        (when host? (test/do-report {:type :begin-test-ns :ns namespace-object}))
        (if hook
          (if (= (set namespace-vars)
                 (set (filter (comp :test meta) (vals bindings))))
            (@hook)
            (throw
             (ex-info
              "A namespace test hook requires its complete admitted selection."
              (seon.error.refusal/error
               :seon.test/execution-error
               {:seon.error/layer :seon.test/execution
                :seon.error/operation `run-selected-tests
                :seon.error/message "A namespace test hook requires its complete admitted selection."
                :seon.test/execution-refusal :seon.test/incomplete-test-ns-hook
                :seon.ns/name namespace-name
                :seon.error/expected "the namespace's complete admitted test selection"
                :seon.error/offending namespace-vars
                :seon.error/data {:seon.ns/name namespace-name}}))))
          (test/test-vars namespace-vars))
        (when host? (test/do-report {:type :end-test-ns :ns namespace-object})))
      @test/*report-counters*))))

(defn- resolve-loaded
  "The Var one qualified symbol names, or nothing when it is not loaded.

  `find-var` THROWS on an absent namespace, so a snapshot must not use it to
  ask whether something is loaded: a worker that has not loaded a namespace
  has nothing there to leak, which is an answer, not an error."
  [qualified-symbol]
  (when (find-ns (symbol (namespace qualified-symbol)))
    (find-var qualified-symbol)))

(defn live-cluster-schema-states
  "Each running cluster's projection state and the environment it holds now.

  The pair is what a restore needs: the atom to write, and the environment
  value carrying the CONNECTION whose facts decide what belongs in it. The
  environment's key set is evidence about what the run entered with, never
  the thing a restore puts back — that is derived from the connection.
  Nothing is remembered between calls."
  {:malli/schema [:=> [:cat] :map]}
  []
  (into {}
        (keep (fn [[cluster-name instance]]
                (when-let [state (get-in instance
                                         [:seon.sci.eval/ctx env/state-carrier])]
                  [cluster-name [state @state]])))
        (some-> (resolve-loaded 'seon.operator.runtime/running-instances)
                var-get deref)))

(defn- projection-form-keys
  "The declaration keys one projection value holds."
  [projection]
  (set (keys (:seon.schema.projection/forms projection))))

(defn restore-live-cluster-schema!
  "Advance each live cluster's schema projection to the one ITS OWN FACTS
  declare, and name every key on which the run's exit state disagreed.

  A live cluster's declarations are database facts; its projection is a
  compiled view of them. So the question after an in-process run is never
  `what did this projection hold before?` — it is `what do the cluster's
  committed facts say now?`. `projection-from-database` at the connection's
  current basis answers exactly that, and `env/advance-projection!` is the
  same seam adoption and evaluation advance through: ONE restore path.

  Putting the ENTERING SNAPSHOT back was a mirror acting against the writer.
  On 2026-09-17 a probe turn deliberately retracted four declarations from
  `default` during a run; the snapshot restore reasserted them, and the
  cluster's projection disagreed with its own committed facts until an
  explicit advance repaired it
  (`docs/seon/issues/the-drift-restore-undoes-a-committed-schema-retraction.md`).

  The snapshot is still taken, and still matters — as EVIDENCE for naming,
  not as the thing restored. Comparing the run's exit key set with the
  derived one classifies every difference by whether the entering set held
  the key:

  - present at exit, absent from the facts, not entering: the run registered
    it and committed nothing — `::drift-added`, restored away;
  - present at exit, absent from the facts, entering: a committed transaction
    retracted it — `::committed-removed`, and it STAYS retracted;
  - in the facts, absent at exit, entering: the run dropped it in memory —
    `::drift-removed`, restored back;
  - in the facts, absent at exit, not entering: a committed transaction added
    it — `::committed-added`.

  A cluster whose snapshot carries no connection has no authority to derive
  from; that is reported as `::schema-authority-unavailable` — the typed
  unknown, never silence (AGENTS §2.4). A run that touched no declarations
  reports nothing at all."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [before]
  (into
   []
   (keep
    (fn [[cluster-name [state entering-environment]]]
      (let [connection (:seon.db/connection entering-environment)
            exit-projection (:seon.schema/projection @state)
            database (when connection (db/db connection))]
        (if (or (nil? connection)
                (seon.error.refusal/error? database))
          {:seon.cluster/name cluster-name
           ::schema-authority-unavailable
           (if connection
             (str "The cluster's declaration facts could not be read: "
                  (:seon.error/message database))
             "The snapshot carries no connection, so the cluster's declaration facts could not be read.")}
          ;; The ENTERING projection is the reusable value, never the exit
          ;; one: reuse is decided by the fingerprint the value CARRIES, and
          ;; a projection a run edited in memory still carries the
          ;; fingerprint of the entities it was built from. Reusing it would let
          ;; the run's own edit answer the question the facts must answer.
          (let [derived (schema/projection-from-database
                         database (:seon.schema/projection entering-environment))
                entering-keys (projection-form-keys
                               (:seon.schema/projection entering-environment))
                exit-keys (projection-form-keys exit-projection)
                derived-keys (projection-form-keys derived)]
            (when-not (identical? derived exit-projection)
              (env/advance-projection! state (db/basis-t database) derived))
            (let [uncommitted (set/difference exit-keys derived-keys)
                  absent (set/difference derived-keys exit-keys)
                  named (fn [declaration-keys]
                          (vec (sort (map str declaration-keys))))
                  drift-added (set/difference uncommitted entering-keys)
                  committed-removed (set/intersection uncommitted entering-keys)
                  drift-removed (set/intersection absent entering-keys)
                  committed-added (set/difference absent entering-keys)
                  entity (cond-> {:seon.cluster/name cluster-name}
                        (seq drift-added)
                        (assoc ::drift-added (named drift-added))
                        (seq drift-removed)
                        (assoc ::drift-removed (named drift-removed))
                        (seq committed-added)
                        (assoc ::committed-added (named committed-added))
                        (seq committed-removed)
                        (assoc ::committed-removed (named committed-removed)))]
              (when (next entity) entity))))))
    before)))

(defn schema-restore-drift
  "The restore rows naming a run's OWN uncommitted change, and only those.

  A committed addition or retraction is the writer doing its job during a
  run; a key the run registered or dropped in memory is the run failing to
  own nothing global. Only the second is a test error, so the caller that
  turns rows into a verdict asks here rather than reading the row keys."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [entities]
  (filterv #(or (seq (::drift-added %)) (seq (::drift-removed %))) entities))

(defn- ambient-snapshot
  "Facts about this worker JVM's process-global state, DERIVED.

  AGENTS §5.7: a test owns nothing global. Nothing DECLARES which state that
  is, and a declaration would be the maintained list §2.2 bans, so the worker
  measures the shared state it can see and reports what a task changed.

  Every member is a fact about the running process, not a count somebody kept:
  the wrappers malli actually installed, the contracts it actually holds, the
  clusters actually running, and the shared SCI base each fork inherits."
  []
  (let [wrapped (instrument/instrumented)
        ;; A wrapper holding its Var's own declared contract is the program's
        ;; arming, which any request of this JVM (a first load's
        ;; `arm-first-loads!`, a development adoption) installs concurrently:
        ;; its appearance is no test's change, so only its removal (a Var
        ;; left unarmed) counts. A wrapper with any other contract is a test's.
        own? (fn [^clojure.lang.Var candidate]
               (= (mi/-schema candidate)
                  (:seon.instrument/authored (meta (.getRawRoot candidate)))))
        var-symbol (fn [candidate]
                     (let [{namespace-object :ns var-name :name} (meta candidate)]
                       (symbol (str (ns-name namespace-object)) (str var-name))))
        ;; One pass. A wrapper carrying a supplied-default plan (the arming
        ;; request's `:seon.call-preparation/snapshot`) is PLANNED; a re-arm
        ;; without `:seon.db/db` keeps the Var's own contract, so `own?` alone
        ;; cannot see it strip the plan (2026-09-27: 2,247 of 2,249 wrappers).
        [instrumented armed planned]
        (reduce (fn [[instrumented armed planned] ^clojure.lang.Var candidate]
                  (let [named (var-symbol candidate)
                        root (.getRawRoot candidate)]
                    (if (own? candidate)
                      [instrumented (conj! armed named)
                       (cond-> planned
                         (:seon.call-preparation/snapshot (:seon.instrument/policy (meta root)))
                         (conj! named))]
                      [(conj! instrumented named) armed planned])))
                [(transient #{}) (transient #{}) (transient #{})] wrapped)
        snapshot
        {::snapshot-instrumented (persistent! instrumented)
         ::snapshot-armed (persistent! armed)
         ::snapshot-planned (persistent! planned)
         ::snapshot-registered
         (into #{}
               (mapcat (fn [[namespace-symbol entries]]
                         (map (fn [[name-symbol _]]
                                (symbol (str namespace-symbol)
                                        (str name-symbol)))
                              entries)))
               (m/function-schemas))
         ::snapshot-live-clusters
         ;; The owner's Var: `find-var` sees interned Vars only, and
         ;; `seon.cluster` merely referred this one, so that name read nil.
         (or (some-> (resolve-loaded 'seon.operator.runtime/running-instances)
                     var-get deref keys set)
             #{})
         ;; A LIVE CLUSTER'S SCHEMA REGISTRY IS NOT A MEMBER HERE.
         ;; Its authority is the cluster's own declaration facts, so a
         ;; before/after key-set diff cannot tell a run's leak from a
         ;; committed retraction — it reported the latter as drift.
         ;; `restore-live-cluster-schema!` derives that answer from the
         ;; facts and is the ONE owner of it.
         }]
    snapshot))

(defn- set-drift
  "The whole native members added to and removed from one snapshot set;
  the floor printer is the one place that bounds what is shown."
  [before after]
  (let [added (set/difference after before)
        removed (set/difference before after)]
    (cond-> {}
      (seq added) (assoc ::drift-added added)
      (seq removed) (assoc ::drift-removed removed))))

(def ^:private drift-directions
  "Which direction of change is a LEAK, per member — derived from what the
  member means, not from a preference.

  A wrapper that disappeared leaves every later task unarmed; a wrapper that
  appeared is a test that armed something extra and did not undo it: both
  matter. A malli function-schema REGISTRATION that disappeared is a real
  loss, but registrations that appeared are ordinary accretion — a test
  calling `apply!` collects every loaded namespace's contracts, including its
  own, and reporting those 47 rows as a defect is noise that buries the one
  line that matters. A cluster that appeared is one nobody stopped; a cluster
  that disappeared is a test stopping something it did not start. A wrapper
  holding its Var's own contract that appeared is the program's arming, which
  concurrent requests install (2026-09-27: a first test after a restart was
  blamed for another request's first-load arming of seon.test-support). A
  planned wrapper that lost its supplied-default plan leaves every later
  caller of that Var refused its supplied `:seon.db/db`."
  {::snapshot-instrumented #{::drift-added ::drift-removed}
   ::snapshot-armed #{::drift-removed}
   ::snapshot-planned #{::drift-removed}
   ::snapshot-registered #{::drift-removed}
   ::snapshot-live-clusters #{::drift-added ::drift-removed}})

(defn- ambient-drift
  "What one task changed in the worker's process-global state, or nothing.

  Each member's declared leak directions decide what counts as drift."
  [before after]
  (into {}
        (keep (fn [[member directions]]
                (let [drift (select-keys (set-drift (get before member #{}) (get after member #{}))
                                         directions)]
                  (when (seq drift) [member drift]))))
        drift-directions))

;;; Reach: what a test's reach digest covers, read from the tested database value.
;;; Attribute content supplies each declaration's node. Changed names retire
;;; their reverse closure's component digests; unchanged components terminate
;;; the next graph walk. Every request shares these per-definition digests.

(defn- reach-read!
  "`value`, or a throw carrying the read refusal it is; [[reach-entries]]
  answers the refusal as its diagnostic."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [value]
  (if (db/read-refusal? value) (throw (ex-info "Reach unavailable." value)) value))

(defn- reach-keywords
  {:malli/schema [:=> [:cat :seon.schema/value] [:set :qualified-keyword]]}
  [form]
  (into #{} (filter qualified-keyword?) (tree-seq coll? seq form)))

(defn- reach-out
  "The names one declaration's reach facts reach: its calls, references and subject."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :seon.schema/value]] [:vector :seon.schema/value]]}
  [row]
  (cond-> (into (vec (:seon.fn/calls row)) (:seon.fn/references row))
    (:seon.test/subject row) (conj (:seon.test/subject row))))

(defn- reach-node
  "One declaration's contribution to reach: its leaf (`::leaf`, the
  declaration's behavior digest), the names it reaches (`reach-out`) and the
  keywords it names (its own and its contract's)."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :seon.schema/value]] :map]}
  [row]
  {::leaf (::leaf row :seon.error/unknown)
   ::out (reach-out row)
   ::keys (into (set (:seon.fn/keywords row))
                (reach-keywords (some-> (:seon.fn/spec row) edn/read-string)))})

(defn- reach-schema-node
  "One schema declaration's leaf and the keywords its form names."
  {:malli/schema [:=> [:cat :keyword :string] :map]}
  [schema-key form]
  (let [value (edn/read-string form)]
    {::leaf (id/digest 64 [schema-key value]) ::keys (reach-keywords value)}))

;;; Reach kinds' weights: bytes of the structure an entry builds, measured
;;; per entry with leaves excluded (bench/seon/cache_kind_measurement.edn).
;;; Strings and symbols are the datoms' own objects, held by Datahike's index
;;; nodes and shared by every revision's read, so they are not charged here;
;;; structure another kind built is charged to the kind that built it.

(def reach-map-entry-bytes
  "Bytes per entry of an attribute's map: hash-trie slot, boxed entity id, node share."
  52)

(def reach-set-element-bytes
  "Bytes per value in an entity's set of a many attribute."
  36)

(def reach-node-name-bytes
  "Bytes per name a reach node holds: its out vector, key set and the row it is keyed by."
  48)

(def reach-graph-entry-bytes
  "Bytes per node or schema entry of a reached graph's maps."
  32)

(def reach-index-entry-bytes
  "Bytes per reached node or schema of a reach index's key and value."
  16)

(defn- reach-weight
  "A node's estimated retained bytes, from the counts it already has."
  {:malli/schema [:=> [:cat :map] :int]}
  [node]
  (* reach-node-name-bytes (+ 8 (count (::out node)) (count (::keys node)))))

(def ^:private reach-fact-attributes
  "Each reach fact attribute, with whether it holds many values."
  [[:seon.fn/calls true] [:seon.fn/references true] [:seon.fn/keywords true]
   [:seon.fn/spec false] [:seon.test/subject false]])

(defn- reach-revision-key
  "Each attribute `reached-graph` reads to the commit whose datoms of it
  equal `database`'s (`seon.db/content-revisions`), or nil. Equal on every
  branch of one program; a commit writing only test results keeps it."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil [:map-of :qualified-keyword :uuid]]]}
  [database]
  (db/content-revisions database (into [:seon.fn/sym :seon.test/sym :seon.program/definition-digest :seon.fn/doc
                                        :seon.schema/key :seon.schema/form]
                                       (map first) reach-fact-attributes)))

(defn- reach-owner
  "The attached connection generation and the branch of the held database value."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil :map]]}
  [database]
  (when-let [identity (db/committed-value-identity database)]
    (assoc (dissoc identity :datahike.value/commit-id)
           :seon.store/branch (get-in (db/schema-database database) [:config :branch]))))

(defn- reach-changed-entities
  "Entities changed between two attribute content revisions, shared by map and graph derivation."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :uuid :uuid :seon.db/basis-t]
                  [:set :seon.db/entity-id]]}
  [database attribute before after basis]
  (cache/lookup ::reach-changed-entities [attribute before after]
                #(* reach-set-element-bytes (count %))
                #(db/entities-changed-after database basis attribute)))

(defn- retire-replaced-revision!
  "Retain this branch's attribute basis and release its previous cached attribute map."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword [:enum :by-value :by-entity :many] :uuid] :nil]}
  [database attribute shape revision]
  (let [head [(reach-owner database) attribute shape]
        prior (cache/present ::reach-attribute-head head)]
    (when (not= revision (::revision prior))
      (when prior (cache/evict! ::reach-attribute [attribute shape (::revision prior)]))
      (cache/evict! ::reach-attribute-head head)
      (cache/lookup ::reach-attribute-head head (constantly 128)
                    #(hash-map ::basis (db/basis-t database) ::revision revision)))
    nil))

(defn- reach-attribute
  "An attribute's content map, updated from changed entities on the same connection generation."
  {:malli/schema [:=> [:cat :seon.db/database-value [:or :nil [:map-of :qualified-keyword :uuid]]
                       :qualified-keyword [:enum :by-value :by-entity :many]]
                  [:map-of :seon.schema/value :seon.schema/value]]}
  [database revisions attribute shape]
  (let [revision (get revisions attribute)
        identity (reach-owner database)
        head [identity attribute shape]
        prior (when revision (cache/present ::reach-attribute-head head))
        previous (when (and prior (< (::basis prior) (db/basis-t database)))
                   (cache/present ::reach-attribute [attribute shape (::revision prior)]))
        derive (fn []
                 (let [changed (when previous (reach-changed-entities database attribute (::revision prior) revision (::basis prior)))
                       datoms (if changed
                                (mapcat #(reach-read! (db/datoms database :eavt % attribute)) changed)
                                (reach-read! (db/datoms database :aevt attribute)))
                       base (if changed
                              (if (= shape :by-value)
                                (into {} (remove (comp changed val)) previous)
                                (apply dissoc previous changed))
                              {})]
                   (case shape
                     :by-value (into base (map (juxt :v :e)) datoms)
                     :by-entity (into base (map (juxt :e :v)) datoms)
                     :many (persistent!
                            (reduce (fn [by-entity {:keys [e v]}]
                                      (assoc! by-entity e (conj (get by-entity e #{}) v)))
                                    (transient base) datoms)))))
        weigh (fn [by] (+ (* reach-map-entry-bytes (count by))
                          (if (= :many shape)
                            (* reach-set-element-bytes (transduce (map count) + 0 (vals by))) 0)))]
    (if revision
      (let [by (cache/lookup ::reach-attribute [attribute shape revision] weigh derive)]
        (retire-replaced-revision! database attribute shape revision)
        by)
      (derive))))

(defn- reach-declarations
  "The names, digests and reach facts; unchanged attributes reuse the held derivation."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value]
                   [:map [::rows [:map-of :qualified-symbol :int]]
                    [::digests [:map-of :int :seon.program/definition-digest]]
                    [::facts [:map-of :qualified-keyword :map]]
                    [::schema-ids :map] [::schema-forms :map] [::attributes :map] [::revisions [:or :nil :map]]]]
                  [:=> [:cat :seon.db/database-value :map]
                   [:map [::rows [:map-of :qualified-symbol :int]]
                    [::digests [:map-of :int :seon.program/definition-digest]]
                    [::facts [:map-of :qualified-keyword :map]]
                    [::schema-ids :map] [::schema-forms :map] [::attributes :map] [::revisions [:or :nil :map]]]]]}
  ([database] (reach-declarations database {}))
  ([database previous]
   (let [revisions (reach-revision-key database)
         shapes (into [[:seon.fn/sym :by-value] [:seon.test/sym :by-value]
                       [:seon.program/definition-digest :by-entity] [:seon.fn/doc :by-entity]
                       [:seon.schema/key :by-value] [:seon.schema/form :by-entity]]
                      (map (fn [[a many?]] [a (if many? :many :by-entity)])) reach-fact-attributes)
         attributes (into {}
                          (map (fn [[a shape]]
                                 [a (if (and revisions (= (get revisions a) (get (::revisions previous) a)))
                                      (get (::attributes previous) a)
                                      (reach-attribute database revisions a shape))])) shapes)]
     {::rows (merge (:seon.fn/sym attributes) (:seon.test/sym attributes))
      ::digests (:seon.program/definition-digest attributes)
      ::facts (select-keys attributes (into [:seon.fn/doc] (map first) reach-fact-attributes))
      ::schema-ids (:seon.schema/key attributes) ::schema-forms (:seon.schema/form attributes)
      ::attributes attributes ::revisions revisions})))

(defn- reach-fixed-point
  "Every name reachable from `start` through each node's `edges`, by name:
  its node, or nil for a name `node-of` does not find."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?] :keyword [:set :seon.schema/value]]
                  [:map-of :seon.schema/value [:maybe :map]]]}
  [node-of edges start]
  (loop [pending (vec start) scheduled (transient start) resolved (transient {})]
    (if-let [item (peek pending)]
      (let [node (node-of item)
            pending (reduce (fn [pending child]
                              (if (contains? scheduled child)
                                pending
                                (do (conj! scheduled child) (conj pending child))))
                            (pop pending) (edges node))]
        (recur pending scheduled (assoc! resolved item node)))
      (persistent! resolved))))

(defn- reached-graph
  "The requested closure, stopping at components whose content is already known."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                   [:map [::nodes [:map-of :seon.schema/value [:maybe :map]]]
                    [::schemas [:map-of :seon.schema/value [:maybe :map]]]]]
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym] :map :map [:set :seon.schema/value]]
                   [:map [::nodes [:map-of :seon.schema/value [:maybe :map]]]
                    [::schemas [:map-of :seon.schema/value [:maybe :map]]]]]]}
  ([database test-symbols]
   (reached-graph database test-symbols (reach-declarations database) {} #{}))
  ([database test-symbols declarations known unknown]
  (let [{::keys [rows digests facts schema-ids schema-forms]} declarations
        node-of (fn [s]
                  (if-let [digest (get known s)]
                    {::leaf {::known digest} ::undigested? (contains? unknown s)
                     ::absent? (not (contains? rows s))}
                    (when-let [e (get rows s)]
                    (let [declaration (into {} (keep (fn [[attribute values]]
                                                      (when-some [v (get values e)] [attribute v]))) facts)
                          digest (get digests e)
                          content (assoc declaration ::leaf digest)]
                      (cache/lookup
                       ::reach-node content reach-weight
                       #(reach-node (assoc declaration ::leaf
                                           (or (when digest
                                                 (if (:seon.fn/doc declaration)
                                                   (db/behavior-digest database e) digest))
                                               :seon.error/unknown))))))))
        schema-node-of (fn [k]
                         (if-let [digest (get known k)]
                           {::leaf {::known digest}}
                           (when-let [form (get schema-forms (get schema-ids k))]
                           (cache/lookup ::reach-schema-node [k form] reach-weight #(reach-schema-node k form)))))
        nodes (reach-fixed-point node-of ::out (set test-symbols))]
    {::nodes nodes
     ::schemas (reach-fixed-point schema-node-of ::keys (into #{} (mapcat ::keys) (keep val nodes)))})))

(defn- reach-graph
  "`reached-graph` of the requested tests; a committed value's graph is cached
  under the revisions of the attributes it reads (`reach-revision-key`), so the
  requests and recordings of one program share it."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                  [:map [::nodes [:map-of :seon.schema/value [:maybe :map]]]
                   [::schemas [:map-of :seon.schema/value [:maybe :map]]]]]}
  [database test-symbols]
  (if-let [revisions (reach-revision-key database)]
    (if-let [held (let [entries (cache/present ::reach-index-by-revision revisions)]
                   (when (every? #(contains? (::component-digests entries) %) test-symbols) entries))]
      (select-keys held [::nodes ::schemas])
      (cache/lookup ::reach-graph [revisions (set test-symbols)]
                  (fn [{::keys [nodes schemas]}] (* reach-graph-entry-bytes (+ (count nodes) (count schemas))))
                  #(reached-graph database test-symbols)))
    (reached-graph database test-symbols)))

(defn- reach-successors
  "Each of `items`' successors as positions in `items`, one int array per item."
  {:malli/schema [:=> [:cat [:vector :seon.schema/value] [:map-of :seon.schema/value :int] [:fn clojure.core/ifn?]]
                  [:vector [:fn clojure.core/some?]]]}
  [items positions edges-of]
  (mapv (fn [item] (int-array (keep positions (edges-of item)))) items))

(defn- reach-components
  "The strongly connected components of a graph given as one int array of
  successor positions per vertex (Tarjan's algorithm, iterative): each
  component a sorted vector of positions, every component after the
  components it reaches, beside each vertex's component as an int array.

  ## Costs

  - Vertices plus edges, plus sorting each component's members: default's
    9,165 names and 104,761 edges once per derivation."
  {:malli/schema [:=> [:cat [:vector [:fn clojure.core/some?]]]
                  [:tuple [:vector [:vector :int]] [:fn clojure.core/some?]]]}
  [successors]
  (let [n (count successors)
        index (int-array n -1)
        low (int-array n)
        on-stack (boolean-array n)
        component-of (int-array n -1)
        counter (int-array 1)
        stack (java.util.ArrayDeque.)
        frames (java.util.ArrayDeque.)
        components (java.util.ArrayList.)
        visit! (fn [^long v]
                 (aset index v (aget counter 0))
                 (aset low v (aget counter 0))
                 (aset counter 0 (inc (aget counter 0)))
                 (aset on-stack v true)
                 (.push stack (int v))
                 (.push frames (int-array [v 0])))]
    (dotimes [root n]
      (when (neg? (aget index root))
        (visit! root)
        (while (not (.isEmpty frames))
          (let [^ints frame (.peek frames)
                v (aget frame 0)
                i (aget frame 1)
                ^ints following (nth successors v)]
            (if (< i (alength following))
              (let [w (aget following i)]
                (aset frame 1 (inc i))
                (cond (neg? (aget index w)) (visit! w)
                      (aget on-stack w) (aset low v (min (aget low v) (aget index w)))))
              (do (.pop frames)
                  (when (= (aget low v) (aget index v))
                    (let [c (.size components)]
                      (.add components
                            (loop [members []]
                              (let [w (int (.pop stack))]
                                (aset on-stack w false)
                                (aset component-of w c)
                                (if (= w v) (vec (sort (conj members w))) (recur (conj members w))))))))
                  (when-let [^ints parent (.peek frames)]
                    (let [u (aget parent 0)]
                      (aset low u (min (aget low u) (aget low v)))))))))))
    [(vec components) component-of]))

(defn- reach-component-digests
  "Component digests in dependency order; a known terminal keeps its existing digest."
  {:malli/schema [:=> [:cat [:vector :seon.schema/value] [:fn clojure.core/ifn?] [:vector [:fn clojure.core/some?]]
                       [:vector [:vector :int]] [:fn clojure.core/some?] [:fn clojure.core/ifn?]]
                  [:vector [:string {:min 64 :max 64}]]]}
  [items leaf-of successors components ^ints component-of extra-of]
  (persistent!
   (reduce
    (fn [digests c]
      (let [members (nth components c)
            leaf (when (= 1 (count members)) (leaf-of (nth items (first members))))]
        (conj! digests
               (if (map? leaf)
                 (::known leaf)
                 (id/id [(mapv (fn [v] [(nth items v) (leaf-of (nth items v))]) members)
                       (let [following
                             (persistent!
                              (reduce (fn [found v]
                                        (reduce (fn [found w]
                                                  (let [d (aget component-of (int w))]
                                                    (if (= d c) found (conj! found d))))
                                                found (nth successors v)))
                                      (transient #{}) members))]
                         (vec (sort (distinct (map #(nth digests %) following)))))
                       (vec (sort (distinct (mapcat #(extra-of (nth items %)) members))))] 64)))))
    (transient []) (range (count components)))))

(defn- test-reach-digest
  "The identity of a test's name and its closure's component digest (the empty
  string for a test with no program entity)."
  {:malli/schema [:=> [:cat :seon.test/sym :string] [:string {:min 64 :max 64}]]}
  [test-symbol component-digest]
  (id/id [test-symbol component-digest] 64))

(defn- reach-index
  "The requested digests and component digests, with undigested declarations propagated to their callers."
  {:malli/schema [:=> [:cat [:map [::nodes [:map-of :seon.schema/value [:maybe :map]]]
                              [::schemas [:map-of :seon.schema/value [:maybe :map]]]]
                       [:vector :seon.test/sym]]
                  [:map [::digests [:map-of :seon.test/sym [:string {:min 64 :max 64}]]]
                   [::undigested [:vector :seon.test/sym]]
                   [::unknown-names [:set :seon.test/sym]]
                   [::component-digests [:map-of [:or :qualified-symbol :qualified-keyword]
                                         :seon.program/definition-digest]]]]}
  [{::keys [nodes schemas]} test-symbols]
  (let [schema-keys (vec (sort (keys schemas)))
        key-index (zipmap schema-keys (range))
        key-successors (reach-successors schema-keys key-index #(::keys (get schemas %)))
        [key-components ^ints key-of] (reach-components key-successors)
        key-digests (reach-component-digests schema-keys #(::leaf (get schemas %)) key-successors
                                             key-components key-of (constantly nil))
        key-digest (fn [k] (nth key-digests (aget key-of (int (key-index k)))))
        names (vec (sort (keys nodes)))
        name-index (zipmap names (range))
        successors (reach-successors names name-index #(::out (get nodes %)))
        [components ^ints component-of] (reach-components successors)
        digests (reach-component-digests names #(if-let [node (get nodes %)] (::leaf node) :seon.error/unknown)
                                         successors components component-of
                                         #(map key-digest (::keys (get nodes %))))
        ;; A declared entity without its stored digest is unknown evidence;
        ;; a name with no program entity is host code (`clojure.core/=`).
        undigested? (reduce (fn [flags c]
                              (let [members (nth components c)]
                                (conj flags
                                      (boolean
                                       (or (some #(when-let [node (get nodes (nth names %))]
                                                    (if (map? (::leaf node)) (::undigested? node)
                                                        (not (string? (::leaf node)))))
                                                 members)
                                           (some #(let [d (aget component-of (int %))]
                                                    (and (not= d c) (nth flags d)))
                                                 (mapcat #(nth successors %) members)))))))
                            [] (range (count components)))]
    (reduce (fn [index s]
              (let [c (when (and (get nodes s) (not (::absent? (get nodes s))))
                        (aget component-of (int (name-index s))))]
                (if (and c (nth undigested? c))
                  (update index ::undigested conj s)
                  (assoc-in index [::digests s] (test-reach-digest s (if c (nth digests c) ""))))))
            {::digests {} ::undigested []
             ::unknown-names (into #{} (filter #(nth undigested? (aget component-of (int (name-index %))))) names)
             ::component-digests
             (into (into {} (map (fn [k] [k (key-digest k)])) schema-keys)
                   (map (fn [s] [s (nth digests (aget component-of (int (name-index s))))])) names)}
            test-symbols)))

(defn- reach-names
  "Every name the closures of `starts` in `nodes` hold, `starts` included: one
  walk however many starts share their reach."
  {:malli/schema [:=> [:cat [:map-of :seon.schema/value [:maybe :map]] [:sequential :seon.schema/value]]
                  [:set :seon.schema/value]]}
  [nodes starts]
  (loop [pending (vec starts) seen (transient #{})]
    (if-let [n (peek pending)]
      (if (contains? seen n)
        (recur (pop pending) seen)
        (recur (into (pop pending) (::out (get nodes n))) (conj! seen n)))
      (persistent! seen))))

(defn- reach-unavailable
  "The unknown-error a failed reach read answers `operation` for `test-symbols`."
  {:malli/schema [:=> [:cat :qualified-symbol [:fn clojure.core/some?] [:vector :seon.test/sym]] :seon.test/unknown-error]}
  [operation ^Exception failure test-symbols]
  (seon.error.refusal/error :seon.test/unknown-error
   {:seon.error/layer :seon.test/recording
    :seon.error/operation operation
    :seon.error/message (str "Reach digest unavailable: " (ex-message failure))
    :seon.test/unknown "reach digest"
    :seon.error/member :seon.test/reach-digests
    :seon.error/expected "derived reach digests for the requested tests"
    :seon.error/offending test-symbols
    :seon.error/data {:seon.test/syms test-symbols}}))

(defn- reach-reverse
  "The reverse dependencies after replacing the supplied graph's concrete nodes."
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [reverse before after]
  (reduce-kv
   (fn [reverse name node]
     (let [old (get (if (keyword? name) (::schemas before) (::nodes before)) name ::absent)]
       (if (or (map? (::leaf node)) (= node old))
         reverse
         (let [edges #(into (set (::out %)) (::keys %))
               removed (reduce #(update %1 %2 disj name) reverse (edges old))]
           (reduce #(update %1 %2 (fnil conj #{}) name) removed (edges node))))))
   reverse after))

(defn- changed-reach
  "The names whose facts changed since a held derivation, plus their reverse closure."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :map :map] [:set :seon.schema/value]]}
  [database revisions declarations prior]
  (let [entities (reduce-kv (fn [changed attribute revision]
                              (if (= revision (get (::revisions prior) attribute)) changed
                                  (into changed (reach-changed-entities database attribute (get (::revisions prior) attribute) revision (::basis prior)))))
                            #{} revisions)
        names (into #{} (comp (filter (comp entities val)) (map key))
                    (concat (::rows declarations) (::schema-ids declarations)
                            (::rows (::declarations prior)) (::schema-ids (::declarations prior))))]
    (set (keys (reach-fixed-point #(hash-map ::out (get (::reverse prior) %)) ::out names)))))

(defn- reach-entries
  "The requested digests; only changed definitions and their reverse closure lose known components.
  A work branch's digests are a function of its own HEAD: main's later
  changes reach it only when merged."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]] :map]}
  [database test-symbols]
  (if (empty? test-symbols)
    {::digests {} ::undigested []}
    (try
      (let [attribute-revisions (reach-revision-key database)
            ;; equal programs share one index
            revisions attribute-revisions
            identity (reach-owner database)
            current (when revisions (cache/present ::reach-index-by-revision revisions))
            covered (into (set (keys (::digests current))) (::undigested current))
            entries
            (if (every? covered test-symbols)
              current
              (let [previous-head (when identity (cache/present ::reach-head identity))
                    previous (when previous-head
                               (some-> (cache/present ::reach-index-by-revision (::revisions previous-head))
                                       (assoc ::basis (::basis previous-head))))
                    prior (or current (when (and previous (< (::basis previous) (db/basis-t database))) previous))
                    declarations (reach-declarations database (::declarations prior {}))
                    changed (if (and prior (not current))
                              (changed-reach database attribute-revisions declarations prior)
                              #{})
                    known (apply dissoc (::component-digests prior {}) changed)
                    unknown (set/difference (set (::undigested prior)) changed)
                    {::keys [nodes schemas] :as graph}
                    (reached-graph database test-symbols declarations known unknown)
                    index (cache/lookup ::reach-index [(set test-symbols) nodes (update-vals schemas ::leaf)]
                                        (fn [_] (* (+ reach-index-entry-bytes 160) (+ (count nodes) (count schemas))))
                                        #(reach-index graph test-symbols))
                    concrete (into {} (remove (comp map? ::leaf val)) (concat nodes schemas))
                    entries {::nodes (merge (::nodes prior) (select-keys concrete (keys nodes)))
                             ::schemas (merge (::schemas prior) (select-keys concrete (keys schemas)))
                             ::reverse (reach-reverse (::reverse prior {})
                                                      (or prior {}) concrete)
                             ::component-digests (merge known (::component-digests index))
                             ::digests (merge (apply dissoc (::digests prior) changed) (::digests index))
                             ::undigested (into unknown (::unknown-names index))
                             ::revisions attribute-revisions ::declarations declarations}]
                (when revisions
                  (cache/evict! ::reach-index-by-revision revisions)
                  (cache/lookup ::reach-index-by-revision revisions
                                #(+ (* 160 (+ (count (::nodes %)) (count (::schemas %))))
                                    (* reach-set-element-bytes (transduce (map count) + 0 (vals (::reverse %)))))
                                (constantly entries)))
                entries))]
        (when identity
          (cache/evict! ::reach-head identity)
          (cache/lookup ::reach-head identity (constantly 256)
                        #(hash-map ::basis (db/basis-t database) ::revisions revisions)))
        (assoc entries ::digests (select-keys (::digests entries) test-symbols)
                       ::undigested (filterv (set (::undigested entries)) test-symbols)))
      (catch Exception failure (reach-unavailable `reach-entries failure test-symbols)))))

(defn- static-reach-digests
  "Each symbol's static reach digest on `database` (`reach-digests` without extra roots)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                  [:or :seon.test/reach-digests :seon.test/unknown-error]]}
  [database test-symbols]
  (let [entries (reach-entries database test-symbols)]
    (cond
      (:seon.test/unknown entries)
      (seon.error.refusal/error
       :seon.test/unknown-error
       (assoc (select-keys entries [:seon.error/message :seon.test/unknown])
              :seon.error/layer :seon.test/reach
              :seon.error/operation 'seon.test.runner/reach-digests))
      ;; Unknown evidence never keys reuse: equal unknown leaves would match.
      (seq (::undigested entries))
      ;; One walk from every undigested test: a closure per test is tests
      ;; times closure (one undigested hub reached by 2,111 of default's tests
      ;; took 5.9 s, 2026-10-07).
      (let [nodes (::nodes entries)
            tests (vec (sort (::undigested entries)))
            declarations (into (sorted-set)
                               (filter #(when-let [node (get nodes %)] (not (string? (::leaf node)))))
                               (reach-names nodes tests))]
        (seon.error.refusal/error
         :seon.test/unknown-error
         {:seon.error/layer :seon.test/reach
          :seon.error/operation 'seon.test.runner/reach-digests
          :seon.error/message (str "Reach digest unknown: " (count declarations) " declarations (:seon.error/data)"
                                   " have no stored :seon.program/definition-digest; reached by " (count tests)
                                   " tests (:seon.error/offending).")
          :seon.error/data {::undigested-declarations (vec declarations)}
          :seon.test/unknown "reach digest"
          :seon.error/member :seon.program/definition-digest
          :seon.error/offending tests}))
      :else (::digests entries))))

(defn- closure-digests
  "Each test's digest of its static closure on `database` and of the `extra-roots` its body executed outside it."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]
                       [:map-of :seon.test/sym :seon.test.member/dynamic-calls]]
                  [:or :seon.test/reach-digests :seon.test/unknown-error]]}
  [database test-symbols extra-roots]
  (let [extras (into #{} (comp (filter (comp (set test-symbols) key)) (mapcat val)) extra-roots)
        digests (static-reach-digests database (into (vec test-symbols) (remove (set test-symbols)) (sort extras)))]
    (if (seon.error.refusal/error? digests)
      digests
      (into {} (keep (fn [test-symbol]
                       (when-let [digest (get digests test-symbol)]
                         [test-symbol
                          (if-let [roots (seq (get extra-roots test-symbol))]
                            (id/id [digest (into (sorted-map) (map (juxt identity digests)) roots)] 64)
                            digest)])))
            test-symbols))))

(def program-content-bound
  "The bound on the declaration datoms one program content read takes (`seon.program/digest-map`)."
  {:seon.program/max-datoms 1000000})

(defn program-content-digest
  "The content of `database`'s program: the digest of its declarations' definition digests
  (`seon.program/digest-map`), equal on every value holding the same declarations, whatever connection, commit or
  as-of view; derived once per commit (`seon.cache`). The refusal when the declarations cannot be read whole."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :seon.program/definition-digest :seon.program/digest-map-refusal]]}
  [database]
  (let [derive #(let [digests (program/digest-map database program-content-bound)]
                  (if (:seon.error/at digests) digests (id/digest 64 [(into (sorted-map) digests)])))]
    (if-let [commit (:datahike.value/commit-id (db/committed-value-identity database))]
      (cache/lookup ::program-content commit (constantly 128) derive string?)
      (derive))))

(defn task-input
  "What a test body judging task `sym` reads (`seon.test/task`): the task entity on `database`, the requesting
  branch's value, and that value; the unknown-error when `database` holds no such task."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym] [:or :seon.test/task-input :seon.test/unknown-error]]}
  [database sym]
  (if-let [entity (first (db/datoms database :avet :seon.task/sym sym))]
    {:seon.test/task (db/stored-entity database (:e entity)) :seon.test/branch database}
    (seon.error.refusal/error :seon.test/unknown-error
     {:seon.error/layer :seon.test/selection
      :seon.error/operation 'seon.test.runner/task-input
      :seon.error/message (str "The request names task " sym ", which the requesting branch does not hold.")
      :seon.test/unknown "task input"
      :seon.error/offending sym})))

(defn- task-identity
  "What a result judging task input `input` is keyed by beside its closure, all content: the task's revision, the
  next task's proofs and the requesting branch's program content (`program-content-digest`); the refusal when that
  program cannot be read whole."
  {:malli/schema [:=> [:cat :seon.test/task-input]
                  [:or [:tuple [:maybe :string] [:vector :symbol] :seon.program/definition-digest]
                   :seon.program/digest-map-refusal]]}
  [{task :seon.test/task database :seon.test/branch}]
  (let [program (program-content-digest database)]
    (if (:seon.error/at program)
      program
      [((requiring-resolve 'seon.task/task-revision) database (:seon.task/id task))
       (vec (sort (some->> (:seon.task/next task) (db/pull database [:seon.task/proofs]) :seon.task/proofs)))
       program])))

(defn task-inputs
  "Each of `test-symbols` whose body calls the test namespace's task reader, mapped to the `task-input` of `task` on
  `database`; empty with no task: an unbound reader reads nil, never a tag. A reader is a direct caller (its
  `:seon.fn/calls`): every deftest's closure reaches the reader through `clojure.test`, so reach cannot tell one."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                   [:or :seon.test/task-inputs :seon.test/unknown-error :seon.error/value]]
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym] [:maybe :seon.test/task]]
                   [:or :seon.test/task-inputs :seon.test/unknown-error :seon.error/value]]]}
  ([database test-symbols] (task-inputs database test-symbols nil))
  ([database test-symbols task]
   (let [;; The reader's name is built, not quoted: a quoted symbol is an analysed reference, so this harness
         ;; function would call it.
         readers (when task
                   (db/q '[:find [?s ...] :in $ [?s ...] ?reader :where [?t :seon.test/sym ?s] [?t :seon.fn/calls ?reader]]
                         database (vec test-symbols) (symbol "seon.test" "task")))
         input (when (seq readers) (task-input database task))]
     (cond (empty? readers) {}
           (seon.error.refusal/error? input) input
           :else (zipmap readers (repeat input))))))

(defn- task-reach-digests
  "`closure-digests`, each test of `inputs` (`task-inputs`) keyed too by its input's `task-identity`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]
                       [:map-of :seon.test/sym :seon.test.member/dynamic-calls]
                       [:or :seon.test/task-inputs :seon.test/unknown-error :seon.error/value]]
                  [:or :seon.test/reach-digests :seon.test/unknown-error :seon.error/value]]}
  [database test-symbols extra-roots inputs]
  (let [digests (closure-digests database test-symbols extra-roots)]
    (cond (seon.error.refusal/error? digests) digests
          (seon.error.refusal/error? inputs) inputs
          :else (reduce-kv (fn [result test-symbol input]
                             (if-let [digest (get result test-symbol)]
                               (let [identity (task-identity input)]
                                 (if (:seon.error/at identity)
                                   (reduced identity)
                                   (assoc result test-symbol (id/id [digest identity] 64))))
                               result))
                           digests inputs))))

(defn reach-digests
  "Each test's reach digest: the content of its static closure on `database`,
  and, given `extra-roots` (test -> the definitions its body executed outside
  that closure, `:seon.test.member/dynamic-calls`), of theirs too. A test whose
  closure calls the test namespace's task reader also keys, given `task` (the
  request's `:seon.test/task`; nil: none), the content of the input it judges (`task-identity`): the one result
  identity every recorder and reader of a task-reading test shares."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                   [:or :seon.test/reach-digests :seon.test/unknown-error]]
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]
                        [:map-of :seon.test/sym :seon.test.member/dynamic-calls]]
                   [:or :seon.test/reach-digests :seon.test/unknown-error]]
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]
                        [:map-of :seon.test/sym :seon.test.member/dynamic-calls] [:maybe :seon.test/task]]
                   [:or :seon.test/reach-digests :seon.test/unknown-error :seon.error/value]]]}
  ([database test-symbols]
   (reach-digests database test-symbols {}))
  ([database test-symbols extra-roots]
   (task-reach-digests database test-symbols extra-roots (task-inputs database test-symbols)))
  ([database test-symbols extra-roots task]
   (task-reach-digests database test-symbols extra-roots (task-inputs database test-symbols task))))

(defn reach-memberships
  "The tested closure's function names, including unresolved targets.

  ## Costs

  - The answer is every test's closure, so it is as large as tests times
    closure (5.2 million names for default's 2,365 tests): ask
    [[tests-reaching]] for which tests reach one name."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                  [:or :seon.test/reaches :seon.test/unknown-error]]}
  [database test-symbols]
  (try
    (let [{::keys [nodes]} (when (seq test-symbols) (reach-graph database test-symbols))]
      (into {} (keep (fn [s] (when (get nodes s) [s (disj (reach-names nodes [s]) s)]))) test-symbols))
    (catch Exception failure (reach-unavailable `reach-memberships failure test-symbols))))

(defn tests-reaching
  "The tests among `test-symbols` whose closure holds `target` (a test never
  counts as reaching itself): one walk of the reached graph's reversed edges
  from `target`.

  ## Costs

  - Linear in the reached graph whatever the number of tests."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym] :symbol]
                  [:or [:set :seon.test/sym] :seon.test/unknown-error]]}
  [database test-symbols target]
  (try
    (let [{::keys [nodes]} (when (seq test-symbols) (reach-graph database test-symbols))
          walk (fn [] (let [callers (persistent!
                                     (reduce-kv (fn [m caller node]
                                                  (reduce (fn [m callee] (assoc! m callee (conj (get m callee []) caller)))
                                                          m (::out node)))
                                                (transient {}) nodes))]
                        (loop [pending (get callers target []) seen (transient #{})]
                          (if-let [n (peek pending)]
                            (if (contains? seen n)
                              (recur (pop pending) seen)
                              (recur (into (pop pending) (get callers n)) (conj! seen n)))
                            (persistent! seen)))))
          revisions (reach-revision-key database)
          ;; One walk per target and graph: the program's whole index serves every request of that program.
          whole? (and revisions (identical? nodes (::nodes (cache/present ::reach-index-by-revision revisions))))
          reaching (if revisions
                     (cache/lookup ::reaching (if whole? [revisions target] [revisions target (set test-symbols)])
                                   #(* reach-node-name-bytes (+ 8 (count %))) walk)
                     (walk))]
      (into #{} (filter #(and (get nodes %) (not= % target) (contains? reaching %))) test-symbols))
    (catch Exception failure (reach-unavailable `tests-reaching failure test-symbols))))

(defn- program-digest-read-attributes
  "Every attribute the digest reads: the source seal and the program rows' own
  attributes (`program/program-attributes`), with the identities that select them."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:set :qualified-keyword]]}
  [projection]
  (-> (program/program-attributes projection)
      (into program/identity-attributes)
      (conj :seon.source/digest)))

(defn program-written-since?
  "Whether an attribute the program digest reads may have been written after
  `basis-t` on `database`'s lineage, by Datahike's revisions
  (`seon.db/changed-attributes`): one stored commit record per commit after
  `basis-t`. A value with no such write has the program digest of its
  `basis-t` value, which lets the writer's in-transaction value (no commit id,
  so no memo) confirm a digest its caller derived instead of deriving it again."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/basis-t] :boolean]}
  [database basis-t]
  (boolean (seq (db/changed-attributes basis-t database
                                       (program-digest-read-attributes
                                        (or (db/carried-projection database) (schema/handed-projection)))))))

(defn program-revisions
  "Datahike's revisions of the program attributes, including speculative values.
  Equal keys prove equal program rows within one connection generation;
  nil when the value carries no complete revision context."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :nil [:tuple :seon.db/connection-id :uuid :uuid [:map-of :qualified-keyword :uuid]]]]}
  [database]
  (let [context (:cache-context database)]
    (when (and (:datahike.cache/connection-id context)
               (:datahike.cache/generation context)
               (:datahike.cache/conservative-revision context))
      (let [projection (db/carried-projection database)]
        [(:datahike.cache/connection-id context) (:datahike.cache/generation context)
         (:datahike.cache/conservative-revision context)
         (select-keys (:datahike.cache/attribute-revisions context)
                      (schema/projection-cache-value
                       projection ::program-digest-read-attributes
                       #(program-digest-read-attributes projection)))]))))

(defn program-revisions-digest
  "The revision digest, or nil when the database has no revision authority."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:maybe :seon.test.run/program-revisions]]}
  [database]
  (some->> (program-revisions database) (id/digest 64)))

(defn program-digest
  "The tested program's identity: the digest of the commit the tests read.
  Datahike derives the commit id from the committed value's content
  (`create-commit-id`, `reference-code/datahike/src/datahike/writing.cljc:363`); reuse across
  commits is decided per member by its stored reach digest, never by this.
  A value with no commit (the writer's in-transaction value, an as-of or
  history view) has no program identity: the typed refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.test.run/program-digest :seon.test.run/unavailable-error]]}
  [database]
  (if-let [commit (:datahike.value/commit-id (db/committed-value-identity database))]
    (id/digest 64 [(str commit)])
    (seon.error.refusal/error :seon.test.run/unavailable-error
     {:seon.error/layer :seon.test/provenance
      :seon.error/operation 'seon.test.runner/program-digest
      :seon.test.run/unavailable true
     :seon.test.run/provenance-failure "The tested value has no commit."
     :seon.error/data {:seon.test.run/basis-t (db/basis-t database)}
     :seon.error/message "Test provenance unavailable: the tested value has no commit."})))

(defn provenance
  "Capture immutable test custody before execution.
  Git is optional for an
  agent's database program, which need not have a corresponding Git commit."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.test.run/provenance :seon.test.run/unavailable-error]]}
  [database]
  (let [digest (program-digest database)
        revisions (program-revisions-digest database)]
    (if (seon.error.refusal/error? digest) digest
        (cond-> {:seon.test.run/id (id/id)
                 :seon.test.run/at (java.util.Date.)
                 :seon.test.run/program-digest digest
                 :seon.test.run/basis-t (db/basis-t database)
                 :seon.test.run/branch (get-in database [:config :branch])}
          revisions (assoc :seon.test.run/program-revisions revisions)))))

(def ^:private report-occurrence-attributes
  ;; The facts `failure-identity` leaves out of the captured signature, which
  ;; covers the claim's content (type, message, expected, actual text, event
  ;; signature): where the claim was reported, and the digest of the whole
  ;; thrown value this execution stored (`prepare-failures!`). That value
  ;; carries its own `:seon.error/at`, so a rerun of the same claim stores
  ;; another blob; under the signature alone it collided with the stored
  ;; report and refused the whole completion (fault 93e4a1e2, 2026-09-27).
  [:seon.test.failure/line :seon.test.failure/reported-file :seon.test.failure/testing-contexts
   :seon.test.failure/actual-blob])

(defn report-entity
  "The stored report for one captured failure of `test-symbol`.

  Its identity is the captured signature, the position it was reported at
  and the stored thrown value: the same claim reported from a moved line, or
  thrown again as another stored value, is another report, never a conflict
  with the earlier one, while other content under an unchanged signature,
  position and thrown value is the same identity with different facts, which
  the recorder refuses as a report conflict."
  {:malli/schema [:=> [:cat [:vector :qualified-keyword] :seon.test/sym :map]
                  [:map [:seon.test.report/id :string]
                   [:seon.test.report/symbol :seon.test/sym]]]}
  [report-attributes test-symbol failure]
  (let [report (into (sorted-map)
                     (assoc (select-keys failure report-attributes)
                            :seon.test.report/symbol test-symbol))]
    (assoc report :seon.test.report/id
           (id/id [test-symbol (:seon.test/failure-identity report)
                   (into (sorted-map) (select-keys report report-occurrence-attributes))]))))

(defn- execution-refusal!
  "Throw the `:seon.test/execution-error` of `kind` for `operation`; the
  recording boundary converts it back into that value."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.schema/value :qualified-keyword
                       :seon.schema/value :seon.schema/value]
                  [:or :nil :seon.test/execution-error]]}
  [operation run-id kind expected observed]
  (let [failure (seon.error.refusal/error :seon.test/execution-error
                 {:seon.error/layer :seon.test/execution
                  :seon.error/operation operation
                  :seon.error/message "The test execution evidence does not authorize this transition."
                  :seon.error/expected expected
                  :seon.error/offending observed
                  :seon.error/data {:seon.test.run/id run-id}
                  :seon.test/execution-refusal kind})]
    (throw (ex-info (:seon.error/message failure) failure))))

(defn- execution-read [value]
  (when (seon.error.refusal/error? value)
    (throw (ex-info (:seon.error/message value) value)))
  value)

(defn- admitted-members
  "The admitted members of `runs` (each pulled with `:db/id`, `:seon.test.run/id`
  and `:seon.test.run/selection-tx`) as `[run-eid member]` pairs, each member
  pulled once; `wanted` `[run-eid symbol]` tuples select, nil takes all.
  Membership refs are asserted only by the admission transaction, so any
  membership datom from another transaction (an addition or a retraction) is
  a changed population: one history read of every run's refs, never an as-of
  view of the store. Reads are one batch whatever the number of runs."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :map]
                       [:maybe [:sequential [:tuple :int :seon.test/sym]]]]
                  [:vector [:tuple :int :map]]]}
  [database runs wanted]
  (doseq [run runs]
    (when-not (:seon.test.run/selection-tx run)
      (execution-refusal! 'seon.test.runner/execution-members (:seon.test.run/id run)
                          :seon.test/population-unknown :admitted-selection
                          (if (:db/id run) run :absent))))
  (let [attributes [:seon.test.run/members :seon.test.run/covered-by]
        run-ids (into {} (map (juxt :db/id :seon.test.run/id)) runs)
        changed (execution-read
                 (db/q '[:find ?run ?member :in $ [[?run ?selected] ...] [?attribute ...]
                         :where [?run ?attribute ?member ?tx] [(not= ?tx ?selected)]]
                       (db/history database)
                       (mapv (juxt :db/id (comp :db/id :seon.test.run/selection-tx)) runs) attributes))
        _ (when-let [[run] (first changed)]
            (execution-refusal! 'seon.test.runner/execution-members (run-ids run)
                                :seon.test/population-unknown :admitted-membership
                                (vec (keep (fn [[r m]] (when (= r run) m)) changed))))
        current (vec (execution-read
                      (if wanted
                        (db/q '[:find ?run ?member :in $ [[?run ?symbol] ...] [?attribute ...]
                                :where [?run ?attribute ?member] [?member :seon.test.member/symbol ?symbol]]
                              database (vec wanted) attributes)
                        (db/q '[:find ?run ?member :in $ [?run ...] [?attribute ...]
                                :where [?run ?attribute ?member]]
                              database (vec (keys run-ids)) attributes))))
        pulled (execution-read
                (db/pull-many database
                              [:db/id :seon.test.member/symbol :seon.test.member/reasons
                               :seon.test.member/worker :seon.test.member/claimed-at
                               :seon.test.member/claim-tx :seon.test.member/host
                               :seon.test.member/completed-tx :seon.test.member/terminated-tx
                               :seon.test.member/pass-count :seon.test.member/fail-count
                               :seon.test.member/error-count :seon.test.member/began?
                               :seon.test.member/ended? :seon.test.member/error
                               :seon.test.member/reach-digest :seon.test.member/calls
                               :seon.test.member/alloc-bytes :seon.test.member/run-ms
                               :seon.test.member/evaluator]
                              (mapv second current)))]
    (mapv
     (fn [[run] member]
       (let [counts (select-keys member [:seon.test.member/pass-count :seon.test.member/fail-count
                                         :seon.test.member/error-count])]
         (when (or (and (:seon.test.member/completed-tx member)
                        (or (not= 3 (count counts))
                            (not (boolean? (:seon.test.member/began? member)))
                            (not (boolean? (:seon.test.member/ended? member)))))
                   (and (seq counts) (not (:seon.test.member/completed-tx member))))
           (execution-refusal! 'seon.test.runner/execution-members (run-ids run)
                               :seon.test/population-unknown :complete-outcome member))
         [run member]))
     current pulled)))

(defn- execution-members
  "The admitted members of `run-id` (those named by `symbols` when given)."
  ([database run-id] (execution-members database run-id nil))
  ([database run-id symbols]
   (let [run (execution-read
              (db/pull database [:db/id :seon.test.run/id :seon.test.run/selection-tx]
                       [:seon.test.run/id run-id]))]
     (mapv second (admitted-members database [(or run {:seon.test.run/id run-id})]
                                    (when symbols (mapv #(vector (:db/id run) %) symbols)))))))

(defn- worker-identity [database worker]
  (execution-read
   (db/pull database [:db/id :seon.db.process/id
                     :seon.db.process/pid :seon.db.process/start-instant] worker)))

(defn- thrown-artifact
  "The stored text of a thrown actual: its one error value
  (`seon.error.refusal/diagnostic`), admitted under `caps`, as the value
  artifact `seon.render.value/read-artifact` and `artifact-value` read back,
  as fault evidence is (`seon.error/prepare`). Over the bound it is the
  artifact of the missing marker. The admission's own emitted text
  (`:seon.sci.admit/edn`) was stored here: its print-node tree, which no
  artifact reader accepted (blob d64d79ce…, 2026-09-27)."
  {:malli/schema [:=> [:cat :seon.error/throwable :seon.sci.admit/caps] :string]}
  [thrown caps]
  (value/artifact-edn
   (value/artifact
    (admit/admit-value {:seon.sci.admit/value
                        (seon.error.refusal/diagnostic
                         (java.util.Date.) :seon.test/execution `failure-report
                         {:seon.error/throwable thrown})
                        :seon.sci.admit/interrupt-fn (constantly nil)
                        :seon.sci.admit/caps caps}))))

(defn- prepare-failures!
  [connection database completion]
  (let [run (:seon.test.run/provenance completion)
        tested-branch (or (:seon.test.run/tested-branch run) (:seon.test.run/branch run))
        tested (or (:seon.db/db completion)
                   (when (= tested-branch (get-in database [:config :branch]))
                     (db/as-of database (:seon.test.run/basis-t run)))
                   database)
        staged (volatile! [])
        stage-field
        (fn [failure field size-key blob-key]
          (if-let [text (get failure field)]
            (let [{fact :seon.blob/fact writes :seon.blob/staged-writes}
                  (blob/inline-or-stage connection text field size-key blob-key)]
              (vswap! staged into writes)
              (merge (dissoc failure field) fact))
            failure))
        ;; THE STORED ERROR IS ADMITTED, NEVER PRINTED WHOLE: its ex-data may
        ;; name an offending value that is an endless lazy sequence (a refused
        ;; render of `(map f (iterate inc 0))`), and `pr-str` realized it until
        ;; default died of OutOfMemoryError (2026-09-27, value-test
        ;; ai-realizes-only-the-members-it-prints). Admission emits EDN under
        ;; the storage bound and stops there; over it, the marker is stored.
        admission-caps
        (into {} (map (fn [cap] [cap (db/q '[:find ?n . :in $ ?a :where [_ ?a ?n]] database cap)]))
              [:seon.config.eval.result/max-bytes :seon.config.eval.result/max-source])
        whole-actual
        (fn [failure thrown]
          (let [write (blob/stage! connection (thrown-artifact thrown admission-caps))]
            (vswap! staged conj write)
            (-> failure
                (with-meta nil)
                (assoc :seon.test.failure/actual-blob (:seon.blob/digest write)))))
        results
        (mapv
          (fn [{test-symbol :seon.test/sym :as result}]
            (let [test-entity (db/pull tested
                                   '[{:seon.fn/file [:seon.fn.file/relative-path]}]
                                   [:seon.test/sym test-symbol])
                  path (get-in test-entity [:seon.fn/file :seon.fn.file/relative-path])
                  reports (or (seq (:seon.test.failure/reports result))
                              (when (pos? (+ (:seon.test/fail-count result 0)
                                             (:seon.test/error-count result 0)))
                                (let [event {:type (if (pos? (:seon.test/error-count result 0)) :error :fail)
                                             :message (or (:seon.test/failure-message result)
                                                          "The runner reported a failure without an assertion event.")}]
                                  [{:seon.test.failure/type (:type event)
                                    :seon.test.failure/message (:message event)
                                    :seon.test/failure-identity
                                    (failure-identity {:seon.db/db tested} (symbol test-symbol) event)}])))
                  [_ failures]
                  (reduce
                    (fn [[ordinals failures] report]
                      (let [reported (:seon.test.failure/reported-file report)
                            line (:seon.test.failure/line report)
                            known-site? (and path reported line
                                             (= (.getName (io/file path)) (.getName (io/file reported))))
                            site (if known-site? [path line]
                                     (mapv report [:seon.test.failure/type :seon.test.failure/message
                                                   :seon.test.failure/expected :seon.test.failure/actual
                                                   :seon.test.failure/signature]))
                            ordinal (get ordinals site 0)
                            failure (cond-> (-> report
                                                (dissoc :seon.test.failure/reported-file :seon.test.failure/line)
                                                (assoc :seon.test.failure/id (id/id [test-symbol site ordinal])
                                                       :seon.test.failure/ordinal ordinal))
                                      known-site? (assoc :seon.test.failure/file [:seon.fn.file/relative-path path]
                                                         :seon.test.failure/line line))
                            failure (-> failure
                                        (stage-field :seon.test.failure/message :seon.test.failure/message-size :seon.test.failure/message-blob)
                                        (stage-field :seon.test.failure/expected :seon.test.failure/expected-size :seon.test.failure/expected-blob))
                            failure (if-let [thrown (::actual-throwable (meta failure))]
                                      ;; A thrown actual is stored whole, as its error value;
                                      ;; its shown text stays inline beside the reference.
                                      (whole-actual failure thrown)
                                      (stage-field failure :seon.test.failure/actual :seon.test.failure/actual-size :seon.test.failure/actual-blob))]
                        [(assoc ordinals site (inc ordinal)) (conj failures failure)]))
                    [{} []] reports)]
              ;; The staged failures ARE the claim; the joined text beside
              ;; them would store every message again, inline and unbounded.
              (cond-> (-> result (dissoc :seon.test.failure/reports)
                          (assoc :seon.test/failures failures))
                (seq failures) (dissoc :seon.test/failure-message))))
          (:seon.test.runner/results completion))]
    (assoc completion :seon.test.runner/results results :seon.blob/staged-writes @staged)))

(defn- failure-replacement-tx
  "Transaction data replacing one test's stored failures with `failures`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/ref [:sequential :map] :seon.schema/value :inst]
                  :seon.store/transaction-data]}
  [database test-entity-id failures run-ref at]
  (let [previous (when-not (string? test-entity-id)
                   (db/pull database '[{:seon.test/failures [*]}] test-entity-id))
        previous-by-id (into {} (map (juxt :seon.test.failure/id identity)) (:seon.test/failures previous))
        retained (set (map :seon.test.failure/id failures))]
    (into
      ;; A dropped failure leaves the test's failures vector through that
      ;; vector: the writer retracts its cell, and the cell's component with
      ;; it. Retracting the entity alone left the cell empty, and the next
      ;; read refused the vector (`vector-element-swept`, red-triage r6 E4).
      (mapv (fn [old] [:db/retract test-entity-id :seon.test/failures (:db/id old)])
            (remove #(retained (:seon.test.failure/id %)) (:seon.test/failures previous)))
      (mapcat
        (fn [{failure-id :seon.test.failure/id :as failure}]
          (let [old (or (get previous-by-id failure-id)
                        (db/pull database '[*] [:seon.test.failure/id failure-id]))
                old-run (get-in old [:seon.test.failure/last-run :db/id])
                current-run (:db/id (db/pull database [:db/id] run-ref))
                entity (assoc failure
                           :db/id (or (:db/id old) (str "test-failure:" failure-id))
                           :seon.test.failure/test test-entity-id
                           :seon.test.failure/first-run (or (get-in old [:seon.test.failure/first-run :db/id]) run-ref)
                           :seon.test.failure/last-run run-ref
                           :seon.test.failure/seen-count (+ (get old :seon.test.failure/seen-count 0)
                                                          (if (and current-run (= old-run current-run)) 0 1))
                           :seon.test.failure/last-seen-at at)]
            (concat
              ;; Retract ONLY what the new entity does not assert. A
              ;; cardinality-one add replaces its own value and an identical
              ;; add emits no datom at all, so retracting every attribute
              ;; first turned an unchanged re-record into pure churn.
              (for [attribute (keys (dissoc old :db/id :seon.test.failure/id))
                    :when (not (contains? entity attribute))]
                [:db.fn/retractAttribute (:db/id old) attribute])
              ;; The contexts vector, when asserted, replaces the stored one whole.
              [entity]))) failures))))

(defn complete-members
  "Accept immutable member outcomes for an admitted run or its exact writer claim.

  Called by record-tx after the existing blob preparation. Only failing
  outcomes produce reports, keyed by the captured content signature. Program
  rows are never created. A later observation of termination may add its
  transaction without changing the recorded outcome. A member's explicit
  termination observation overrides the completion's default."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/completion]
                  :seon.store/transaction-data]}
  [database {run :seon.test.run/provenance results :seon.test.runner/results
             worker :seon.test.member/worker claim :seon.test.member/claim-tx
             terminated? :seon.test.run/terminated? reach :seon.test/reach-digests}]
  (let [run-id (:seon.test.run/id run)
        operation 'seon.test.runner/record-tx
        members (into {} (map (juxt :seon.test.member/symbol identity))
                      (execution-members database run-id
                                         (mapv (comp symbol :seon.test/sym) results)))
        worker-id (when worker (:db/id (worker-identity database worker)))
        claim-id (when claim (:db/id (execution-read (db/pull database [:db/id] claim))))
        forms (:seon.schema.projection/forms (db/carried-projection database))
        provenance-attributes (mapv first (filter vector? (rest (get forms :seon.test.run/provenance))))
        recorded-run (execution-read
                      (db/pull database
                               (conj (vec (remove #{:seon.test.run/callers-at-head} provenance-attributes))
                                     [:seon.test.run/callers-at-head :limit nil])
                               [:seon.test.run/id run-id]))
        recorded-run (cond-> recorded-run
                       (:seon.test.run/callers-at-head recorded-run)
                       (update :seon.test.run/callers-at-head set))
        _ (when (not= (select-keys run provenance-attributes)
                      (dissoc recorded-run :db/id))
            (execution-refusal! operation run-id :seon.test.run/immutable
                                recorded-run run))
        report-attributes (mapv first (drop 2 (get forms :seon.test.report/report)))
        _ (when (empty? report-attributes)
            (execution-refusal! operation run-id :seon.test/population-unknown
                                :seon.test.report/report :absent))
        outcome-keys [:seon.test.member/pass-count :seon.test.member/fail-count
                      :seon.test.member/error-count :seon.test.member/began?
                      :seon.test.member/ended? :seon.test.member/error]
        prepared
        (mapv
         (fn [result]
           (let [test-symbol (symbol (:seon.test/sym result))
                 member (get members test-symbol)
                 reports
                 (mapv
                  (fn [failure]
                    (let [signature (:seon.test/failure-identity failure)
                          _ (when-not signature
                              (execution-refusal! operation run-id :seon.test/population-unknown
                                                  :captured-claim-signature failure))
                          path (when-let [file (:seon.test.failure/file failure)]
                                 (:seon.fn.file/relative-path
                                  (execution-read
                                   (db/pull database [:seon.fn.file/relative-path] file))))]
                      (report-entity report-attributes test-symbol
                                  (cond-> failure
                                    path (assoc :seon.test.failure/reported-file path)))))
                  (:seon.test/failures result))
                 outcome (cond-> {:seon.test.member/pass-count (:seon.test/pass-count result)
                          :seon.test.member/fail-count (:seon.test/fail-count result)
                          :seon.test.member/error-count (:seon.test/error-count result)
                          :seon.test.member/began? (:seon.test.member/began? result)
                          :seon.test.member/ended? (:seon.test.member/ended? result)}
                           (:seon.test.member/error result)
                           (assoc :seon.test.member/error
                                  (execution-read
                                   (db/pull database [:db/id] (:seon.test.member/error result)))))]
             (when (or (not member)
                       (and (or worker claim (:seon.test.member/claim-tx member))
                            (or (not worker-id) (not claim-id)
                                (not= worker-id (get-in member [:seon.test.member/worker :db/id]))
                                (not= claim-id (get-in member [:seon.test.member/claim-tx :db/id])))))
               (execution-refusal! operation run-id :seon.test/claim-replaced
                                   {:seon.test.member/worker worker
                                    :seon.test.member/claim-tx claim}
                                   (or member :absent)))
             (when (or (not (boolean? (:seon.test.member/began? result)))
                       (not (boolean? (:seon.test.member/ended? result)))
                       (and (pos? (+ (:seon.test/fail-count result)
                                     (:seon.test/error-count result)))
                            (empty? reports)))
               (execution-refusal! operation run-id :seon.test/population-unknown
                                   :complete-outcome-events result))
             {:seon.test.member/value member :seon.test.member/outcome outcome
              ;; What the body cost, measured on its own thread and sink.
              ::cost (select-keys result [:seon.test.member/calls :seon.test.member/alloc-bytes
                                          :seon.test.member/run-ms :seon.test.member/top
                                          :seon.test.member/evaluator :seon.test.member/dynamic-calls])
              :seon.test.run/terminated? (true? (get result :seon.test.run/terminated? terminated?))
              :seon.test.report/values reports})) results)
        _ (when (not= (count results) (count (set (map :seon.test/sym results))))
            (execution-refusal! operation run-id :seon.test.run/immutable
                                :one-outcome-per-member results))
        reports (group-by :seon.test.report/id (mapcat :seon.test.report/values prepared))
        report-tx
        (into []
              (keep (fn [[report-id variants]]
                      (let [wanted (first variants)
                            previous (execution-read
                                      (db/pull database (into [:db/id] report-attributes)
                                               [:seon.test.report/id report-id]))]
                        (when (or (not (apply = variants))
                                  (and (:db/id previous)
                                       (not= wanted (dissoc previous :db/id))))
                          (execution-refusal! operation run-id :seon.test/report-conflict
                                              wanted (or previous variants)))
                        (when-not (:db/id previous) wanted)))) reports)]
    (into report-tx
          (mapcat
           (fn [{member :seon.test.member/value outcome :seon.test.member/outcome
                 cost ::cost terminated? :seon.test.run/terminated?
                 reports :seon.test.report/values}]
             (let [eid (:db/id member)
                   report-ids (set (map :seon.test.report/id reports))
                   old-report-ids (set (execution-read
                                       (db/q '[:find [?id ...] :in $ ?member
                                               :where [?member :seon.test.member/failures ?report]
                                                      [?report :seon.test.report/id ?id]]
                                             database eid)))
                   completed? (:seon.test.member/completed-tx member)]
               (when (and completed?
                          (or (not= outcome (select-keys member outcome-keys))
                              (not= report-ids old-report-ids)))
                 (execution-refusal! operation run-id :seon.test.run/immutable
                                     (select-keys member outcome-keys) outcome))
               (cond-> []
                 (not completed?)
                 (conj (cond-> (assoc (merge outcome cost) :db/id eid
                                      :seon.test.member/completed-tx "datomic.tx")
                         (seq report-ids)
                         (assoc :seon.test.member/failures
                                (set (map #(vector :seon.test.report/id %) report-ids)))
                         ;; The content this execution tested: reuse compares it
                         ;; with the current reach, on any branch or lineage.
                         (string? (get reach (:seon.test.member/symbol member)))
                         (assoc :seon.test.member/reach-digest
                                (get reach (:seon.test.member/symbol member)))))
                 (and terminated? (not (:seon.test.member/terminated-tx member)))
                 (conj [:db/add eid :seon.test.member/terminated-tx "datomic.tx"]))))
           prepared))))

(defn- record-latest-tx
  "Transaction data replacing each test row's complete latest result.

  The replacement is a DELTA and still total: every attribute, reach member
  and failure identity this result does not assert is retracted, and every
  one it asserts unchanged writes nothing. Retracting first and re-asserting
  identically cost 157,981 datoms for one unchanged 93-result completion
  (measured 2026-09-17 on `default`), which is what filled the store. This
  runs as a `:db.fn/call` transaction function, so the presence decision
  reads the WRITER's own database value — a caller pre-read could strand
  a retract's lookup ref against a concurrently retracted row and reject
  the whole result transaction."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/completion]
                  :seon.store/transaction-data]}
  [database
   {results :seon.test.runner/results
    run :seon.test.run/provenance
    tested-database :seon.db/db
    destination :seon.test.run/branch
    reach-unknown :seon.test/reach-unknown
    carried-digests :seon.test/reach-digests
    carried-reaches :seon.test/reaches
    task :seon.test/task}]
  (let [tested-branch (or (:seon.test.run/tested-branch run) (:seon.test.run/branch run))
        tested (or tested-database
                   (when (= tested-branch (get-in database [:config :branch]))
                     (db/as-of database (:seon.test.run/basis-t run))))
        destination (or destination (get-in database [:config :branch]))
        run (cond-> (assoc run :seon.test.run/branch destination)
              (not= tested-branch destination) (assoc :seon.test.run/tested-branch tested-branch))
        digests (or carried-digests
                    (when tested (if task
                                   (reach-digests tested (mapv :seon.test/sym results) {} task)
                                   (reach-digests tested (mapv :seon.test/sym results)))))
        derived-reaches (when tested
                          (reach-memberships tested
                            (into [] (comp (map :seon.test/sym)
                                           (remove #(contains? carried-reaches %))) results)))
        reaches (if (seon.error.refusal/error? derived-reaches)
                  (or carried-reaches derived-reaches)
                  (merge derived-reaches carried-reaches))
        run-id (:seon.test.run/id run)
        basis-t (:seon.test.run/basis-t run)
        at (:seon.test.run/at run)
        run-ref [:seon.test.run/id run-id]
        current-by-symbol
        (into {}
              (keep (fn [{test-symbol :seon.test/sym}]
                      (when-let [entity (db/pull database
                                              [:db/id
                                               :seon.test/reach-digest
                                               :seon.test/reach-unknown
                                               :seon.test/failure-message
                                               :seon.test/failing-assertions
                                               '(limit :seon.test/reach nil)]
                                              [:seon.test/sym test-symbol])]
                        [test-symbol entity])))
              results)
        file-present?
        (memoize #(some? (db/pull database [:db/id] [:seon.fn.file/relative-path %])))
        ;; A file identity cannot be minted honestly: `:seon.fn.file/file`
        ;; requires the digest of the file the indexer walked. An absent site
        ;; keeps its line and reports its path as the typed unknown, when the
        ;; database being written into declares that attribute.
        reported-path? (some? (get (:schema database) :seon.test.failure/reported-file))
        portable-failure
        (fn [failure]
          (let [path (second (:seon.test.failure/file failure))]
            (if (or (nil? path) (file-present? path))
              failure
              (cond-> (dissoc failure :seon.test.failure/file)
                reported-path? (assoc :seon.test.failure/reported-file path)))))
        previous (db/pull database
                          (conj (vec (remove #{:seon.test.run/callers-at-head} (keys run)))
                                [:seon.test.run/callers-at-head :limit nil]) run-ref)
        previous (cond-> previous
                   (:seon.test.run/callers-at-head previous)
                   (update :seon.test.run/callers-at-head set))]
    (when (seon.error.refusal/error? previous)
      (throw (ex-info (:seon.error/message previous) previous)))
    (when (and previous (not= run (dissoc previous :db/id)))
      (let [failure (seon.error.refusal/error :seon.test.run/immutable-error
                     {:seon.error/layer :seon.test/recording
                      :seon.error/operation 'seon.test.runner/record-tx
                      :seon.error/message "A test run's provenance is immutable."
                      :seon.test.run/id run-id
                      :seon.test.run/immutable run-id
                      :seon.error/expected (dissoc previous :db/id)
                      :seon.error/offending run
                      :seon.error/data {:seon.test.run/id run-id
                                        :seon.db/basis-t (db/basis-t database)}})]
        (throw (ex-info (:seon.error/message failure) failure))))
    ;; A result needs its test entity: recording must never recreate a retracted
    ;; definition. The writer decides from its own value and refuses the whole
    ;; completion as a declared value, so seon.db/transact! returns it verbatim
    ;; and changes nothing, instead of reporting an unknown outcome.
    (let [missing (into [] (comp (map :seon.test/sym) (remove current-by-symbol)) results)]
      (when (seq missing)
        (let [failure (seon.error.refusal/error :seon.test.run/retracted-definition-error
                        {:seon.error/layer :seon.test/recording
                         :seon.error/operation 'seon.test.runner/record-tx
                         :seon.error/message (str "Test completion names " (count missing)
                                                  " test definition(s) retracted before recording; nothing was recorded.")
                         :seon.test.run/retracted-definitions missing
                         :seon.db/basis-t (db/basis-t database)
                         :seon.error/member :seon.test/sym
                         :seon.error/expected "a surviving test definition for every recorded result"
                         :seon.error/offending missing
                         :seon.error/data {:seon.test.run/id run-id}})]
          (throw (ex-info (:seon.error/message failure) failure)))))
    (into [(assoc run :db/id "test-run")]
     (mapcat
      (fn [{test-symbol :seon.test/sym :as result}]
        (let [test-ref [:seon.test/sym test-symbol]
              current (get current-by-symbol test-symbol)
              exists? (some? current)
              test-entity-id test-ref
              failures (mapv portable-failure (:seon.test/failures result))
              wanted-reach (when (set? (get reaches test-symbol))
                             (get reaches test-symbol))
              wanted-members (set wanted-reach)
              ;; The reach is replaced member by member: an unchanged
              ;; membership emits nothing, a dropped member emits its own
              ;; retraction, a new member its own assertion. Retracting the
              ;; whole attribute first cost 149,436 datoms for 93 identical
              ;; results (measured 2026-09-17 on `default`).
              held-members (set (:seon.test/reach current))
              retracted-members (mapv #(vector :db/retract test-ref :seon.test/reach %)
                                      (remove wanted-members held-members))
              added-members (into #{} (remove held-members) wanted-reach)
              wanted-digest (get digests test-symbol)
              result-entity
              ;; A member's cost is its own evidence (`complete-members`), never copied here.
              (cond-> (assoc (dissoc result :seon.test/failures :seon.test.run/terminated?
                                     :seon.test.member/calls :seon.test.member/alloc-bytes
                                     :seon.test.member/run-ms :seon.test.member/top
                                     :seon.test.member/evaluator :seon.test.member/dynamic-calls
                                     :seon.test.member/executed)
                             :db/id test-entity-id
                             :seon.test/run "test-run"
                             :seon.test/run-basis-t basis-t
                             :seon.test/run-at at)
                (string? wanted-digest)
                (assoc :seon.test/reach-digest wanted-digest)
                (seq added-members)
                (assoc :seon.test/reach added-members)
                (nil? wanted-reach)
                (assoc :seon.test/reach-unknown
                       (or (:seon.error/message reaches)
                           reach-unknown
                           "The completion did not retain its tested database closure membership."))
                (seq failures) (assoc :seon.test/failures
                                      (mapv (fn [failure]
                                              (let [failure-id (:seon.test.failure/id failure)]
                                                (or (:db/id (db/pull database [:db/id]
                                                              [:seon.test.failure/id failure-id]))
                                                    (str "test-failure:" failure-id))))
                                            failures))
)]
          (into (cond-> []
            ;; An attribute is retracted ONLY when this result does not
            ;; assert it: a cardinality-one add replaces its own value, and
            ;; Datahike emits nothing for an identical add. The update stays
            ;; total — a green run still clears the stale failure evidence —
            ;; while an unchanged re-record writes no datom.
            (and exists? (not (contains? result :seon.test/failure-message))
                 (contains? current :seon.test/failure-message))
            (conj [:db.fn/retractAttribute test-ref :seon.test/failure-message])

            (and exists? (not (string? wanted-digest))
                 (contains? current :seon.test/reach-digest))
            (conj [:db.fn/retractAttribute test-ref :seon.test/reach-digest])

            (and exists? (some? wanted-reach)
                 (contains? current :seon.test/reach-unknown))
            (conj [:db.fn/retractAttribute test-ref :seon.test/reach-unknown])

            ;; The failing-assertions vector, when asserted, replaces the stored one whole.
            (and exists? (not (contains? result :seon.test/failing-assertions))
                 (contains? current :seon.test/failing-assertions))
            (conj [:db.fn/retractAttribute test-ref :seon.test/failing-assertions])

            exists? (into retracted-members)
            true (conj result-entity))
            (failure-replacement-tx database test-entity-id failures run-ref at))))
      results))))

(defn record-tx
  "Record one completion at the writer, using its admitted claim when present.

  Admitted runs complete members without recreating program rows. Legacy
  unadmitted runs retain the existing latest-result replacement until their
  callers migrate to pre-execution admission."
  {:seon/tasks #{'seon.test.runner/failure-evidence-is-stored-as-text}
   :malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/completion]
                  :seon.test.runner/record-tx]}
  [database completion]
  (let [run-id (get-in completion [:seon.test.run/provenance :seon.test.run/id])
        entity (execution-read
             (db/pull database [:seon.test.run/selection-tx] [:seon.test.run/id run-id]))]
    (if (or (:seon.test.run/selection-tx entity)
            (:seon.test.member/claim-tx completion)
            (:seon.test.member/worker completion))
      (execution-read (complete-members database completion))
      (record-latest-tx database completion))))

(def ^:private result-selector
  [:seon.test/reach-digest
   :seon.test/reach-unknown
   :seon.test/sym
   :seon.test/pass-count
   :seon.test/fail-count
   :seon.test/error-count
   :seon.test/run-basis-t
   :seon.test/run-at
   :seon.test/run
   {:seon.test/failures ['* {:seon.test.failure/file [:db/id :seon.fn.file/relative-path]}]}
   :seon.test/failing-assertions
   :seon.test/failure-message])

(defn- recorded-member-results
  "The recorded results of each `[run-id test-symbol]` in `wanted`, by symbol:
  one run pull, one member read, one report query and one report pull for the
  whole set, however many runs it spans. A member without a completed outcome
  is its own typed unknown, so the others' evidence stays readable and the
  unknown is never counted."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential [:tuple :seon.test.run/id :seon.test/sym]]]
                  [:map-of :seon.test/sym [:or :seon.test/result :seon.test/result-unknown]]]}
  [database wanted]
  (let [run-ids (vec (distinct (map first wanted)))
        runs (zipmap run-ids
                     (execution-read
                      (db/pull-many database
                                    [:db/id :seon.test.run/id :seon.test.run/selection-tx
                                     :seon.test.run/at :seon.test.run/stopped
                                     :seon.test.run/basis-t :seon.test.run/program-digest
                                     :seon.test.run/published-base-digest
                                     :seon.test.run/overlay-input-digest :seon.test.run/input-digest]
                                    (mapv #(vector :seon.test.run/id %) run-ids))))
        by-eid (into {} (map (juxt :db/id identity)) (vals runs))
        pairs (admitted-members database (mapv #(or (runs %) {:seon.test.run/id %}) run-ids)
                                (mapv (fn [[id sym]] [(:db/id (runs id)) (symbol sym)]) wanted))
        report-entities (execution-read
                     (db/q '[:find ?member ?report :in $ [?member ...]
                             :where [?member :seon.test.member/failures ?report]]
                           database (mapv (comp :db/id second) pairs)))
        reports (group-by first report-entities)
        pulled (zipmap (map second report-entities)
                       (execution-read (db/pull-many database '[*] (mapv second report-entities))))]
    (into {}
          (map (fn [[run-eid member]]
                 (let [run (by-eid run-eid)
                       base (select-keys run [:seon.test.run/program-digest :seon.test.run/basis-t
                                              :seon.test.run/published-base-digest
                                              :seon.test.run/overlay-input-digest
                                              :seon.test.run/input-digest])
                       member-reports (mapv #(dissoc (get pulled (second %)) :db/id)
                                            (get reports (:db/id member)))
                       test-symbol (:seon.test.member/symbol member)
                       run-ref [:seon.test.run/id (:seon.test.run/id run)]]
                   [test-symbol
                    (if-not (and (:seon.test.member/completed-tx member)
                                 (every? integer? (map member [:seon.test.member/pass-count
                                                               :seon.test.member/fail-count
                                                               :seon.test.member/error-count])))
                      (seon.error.refusal/error
                       :seon.test/result-unknown
                       {:seon.error/layer :seon.test/execution
                        :seon.error/operation 'seon.test.runner/recorded-member-results
                        :seon.error/message
                        (if-let [cause (:seon.test.run/stopped run)]
                          (str "Never started: request " (:seon.test.run/id run) " stopped (" cause ") before this test.")
                          "This test's latest admitted execution recorded no completed outcome.")
                        :seon.error/expected :completed-member :seon.error/offending test-symbol
                        :seon.test/execution-refusal :seon.test/population-unknown
                        :seon.test/error-test-symbol test-symbol :seon.test/run run-ref
                        :seon.test/run-at (:seon.test.run/at run)})
                    (cond-> (merge base
                                   {:seon.test/sym (:seon.test.member/symbol member)
                                    :seon.test/pass-count (:seon.test.member/pass-count member)
                                    :seon.test/fail-count (:seon.test.member/fail-count member)
                                    :seon.test/error-count (:seon.test.member/error-count member)
                                    :seon.test/run-basis-t (:seon.test.run/basis-t run)
                                    :seon.test/run-at (:seon.test.run/at run)
                                    :seon.test/run [:seon.test.run/id (:seon.test.run/id run)]
                                    :seon.test.member/completed-tx (:seon.test.member/completed-tx member)}
                                   (select-keys member [:seon.test.member/reach-digest :seon.test.member/calls
                                                 :seon.test.member/alloc-bytes :seon.test.member/run-ms
                                                 :seon.test.member/evaluator]))
                      ;; Reports only: text is built where it is shown
                      ;; (`seon.test/failure-message`), never per read.
                      (seq member-reports)
                      (assoc :seon.test.failure/reports member-reports)))])))
          pairs)))

(def ^:private harness-entries
  "The runner's entrances into a test body (`run-var!` for a compiled test,
  `seon.sci.eval/run-test` for an interpreted one): what their static closures
  execute inside the body's call sink is the runner's work, not the test's."
  '[seon.test.runner/run-var! seon.sci.eval/run-test])

(defn- harness-closure
  "Every name the static closures of `harness-entries` reach on `tested`, the entries included: a walk of the reach
  facts by name (`reach-declarations`, `reach-out`), never a node's behavior digest, which no name set needs."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:set :seon.schema/value]]}
  [tested]
  (let [{::keys [rows facts]} (reach-declarations tested)
        node-of (fn [s] (when-let [e (get rows s)]
                          {::out (reach-out (into {} (keep (fn [[attribute values]]
                                                             (when-some [v (get values e)] [attribute v])))
                                                  (select-keys facts [:seon.fn/calls :seon.fn/references :seon.test/subject])))}))]
    (set (keys (reach-fixed-point node-of ::out (set harness-entries))))))

(defn- function-digests
  "The definition digest `database` holds for each of the function `symbols` it holds."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :seon.fn/sym]] [:map-of :seon.fn/sym :string]]}
  [database symbols]
  (if (seq symbols)
    (into {} (execution-read
              (db/q '[:find ?symbol ?digest :in $ [?symbol ...]
                      :where [?e :seon.fn/sym ?symbol] [?e :seon.program/definition-digest ?digest]]
                    database (vec symbols))))
    {}))

(defn- executed-roots
  "What each result's body executed (`:seon.test.member/executed`) beyond its
  static closure on `tested`: test -> the declarations it ran outside `reaches`
  (absent when none)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:or :seon.test/reaches :seon.test/unknown-error]
                       :seon.test.runner/captured-results]
                  [:map-of :seon.test/sym :seon.test.member/dynamic-calls]]}
  [tested reaches results]
  (let [executed (into {} (keep (fn [{test-symbol :seon.test/sym ran :seon.test.member/executed}]
                                  (when (seq ran) [test-symbol ran])))
                       results)
        symbols (into [] (comp (mapcat val) (map first) (distinct)) executed)
        declared (function-digests tested symbols)
        static (if (seon.error.refusal/error? reaches) {} reaches)
        ;; The runner's own work inside the body's sink (running the var,
        ;; reporting, printing failures) is the harness's, not the test's, and
        ;; a contract's predicates are its schema's, which the reach names by form.
        harness (cond-> (into (set harness-entries)
                              (keys (schema/predicate-functions-in
                                     (or (db/carried-projection tested) (schema/handed-projection)))))
                  (seq executed) (into (harness-closure tested)))]
    (into {} (keep (fn [[test-symbol ran]]
                     (let [closure (get static test-symbol #{})
                           outside (into (sorted-set)
                                         (comp (map first)
                                               (filter #(contains? declared %))
                                               (remove #(or (= test-symbol %) (contains? closure %)
                                                            (contains? harness %))))
                                         ran)]
                       (when (seq outside) [test-symbol outside]))))
          executed)))

(defn- retain-results!
  "Retain each terminated outcome of run `run-id` on `database`, red included, in `seon.cache` under its reuse
  identity `[sym reach-digest input-digest evaluator]`, the key `seon.test/select` reuses by, naming the run: any
  branch asking that identity answers from it (`seon.test/carried-results`). A pure speed-up: the recorded runs
  answer a miss. Called where a member's outcome or its exit commits, whichever lands last. Only an outcome the
  body reached is retained: at least one assertion, no boundary error, terminated; a member that answered from
  another run is never retained itself. A newer outcome at a held identity replaces it: the newest answers."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/id] :nil]}
  [database run-id]
  (doseq [[test-symbol digest pass fail error inputs evaluator at]
          (execution-read
           (db/q '[:find ?symbol ?digest ?pass ?fail ?error ?inputs ?evaluator ?at :in $ ?id
                   :where [?run :seon.test.run/id ?id] [?run :seon.test.run/selection-tx]
                          [?run :seon.test.run/input-digest ?inputs] [?run :seon.test.run/evaluator ?evaluator]
                          [?run :seon.test.run/at ?at]
                          [?run :seon.test.run/members ?member]
                          [?member :seon.test.member/symbol ?symbol]
                          [?member :seon.test.member/reach-digest ?digest]
                          [?member :seon.test.member/completed-tx] [?member :seon.test.member/terminated-tx]
                          [?member :seon.test.member/pass-count ?pass]
                          [?member :seon.test.member/fail-count ?fail] [?member :seon.test.member/error-count ?error]
                          [(+ ?pass ?fail ?error) ?assertions] [(pos? ?assertions)]
                          (not [?member :seon.test.member/error])
                          (not [?member :seon.test.member/reused-run])]
                 database run-id))
          :let [reuse-key [test-symbol digest inputs evaluator]
                outcome {:seon.test.run/id run-id :seon.test.run/at at :seon.test/pass-count pass
                         :seon.test/fail-count fail :seon.test/error-count error}
                held (cache/present ::result reuse-key)]]
    (when (and held (not= run-id (:seon.test.run/id held)) (pos? (compare at (:seon.test.run/at held))))
      (cache/evict! ::result reuse-key))
    (cache/lookup ::result reuse-key (constantly 256) (constantly outcome)))
  nil)

(defn commit-results!
  "Commit captured test results and return those exact committed facts."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.test.run/completion]
    [:or :seon.test/results :seon.test.run/retracted-definition-error :seon.db/error-result]]}
  [connection {results :seon.test.runner/results :as completion}]
  (let [database (db/db connection)
        completion (prepare-failures! connection database completion)
        tested (:seon.db/db completion)
        test-symbols (mapv :seon.test/sym results)
        reaches (when tested (reach-memberships tested test-symbols))
        prepared (:seon.test.runner/results completion)
        dynamic (when tested (executed-roots tested reaches prepared))
        completion (assoc completion :seon.test.runner/results
                          (mapv (fn [{test-symbol :seon.test/sym :as result}]
                                  (cond-> (dissoc result :seon.test.member/executed)
                                    (seq (get dynamic test-symbol))
                                    (assoc :seon.test.member/dynamic-calls (get dynamic test-symbol))))
                                prepared))
        completion (if tested
                     (assoc (dissoc completion :seon.db/db)
                            :seon.test/reach-digests
                            (if-let [task (:seon.test/task completion)]
                              (reach-digests tested test-symbols dynamic task)
                              (reach-digests tested test-symbols dynamic))
                            :seon.test/reaches reaches)
                     completion)
        transaction-report
        (if (seon.error.refusal/error? database)
          database
          (blob/with-publication! connection (:seon.blob/staged-writes completion)
            #(db/transact! connection
                           [[:db.fn/call #'record-tx (dissoc completion :seon.blob/staged-writes)]]))) ]
    (if (seon.error.refusal/error? transaction-report)
      transaction-report
      (let [after (:db-after transaction-report)
            admitted? (:seon.test.run/selection-tx
                       (db/pull after [:seon.test.run/selection-tx]
                                [:seon.test.run/id (get-in completion [:seon.test.run/provenance :seon.test.run/id])]))
            by-symbol (when admitted?
                        (recorded-member-results
                         after (let [run-id (get-in completion [:seon.test.run/provenance :seon.test.run/id])]
                                 (mapv #(vector run-id (:seon.test/sym %)) results))))
            recorded (mapv (fn [{test-symbol :seon.test/sym}]
                             (if admitted?
                               (get by-symbol (symbol test-symbol))
                               (dissoc (db/pull after result-selector [:seon.test/sym test-symbol]) :db/id)))
                           results)]
        (or (first (filter seon.error.refusal/error? recorded))
            (do (when admitted? (retain-results! after (get-in completion [:seon.test.run/provenance :seon.test.run/id])))
                recorded))))))

(defn exit-tx
  "Record at the writer that `test-symbol`'s body in `run-id` has exited.

  Only a member whose result is recorded and whose exit is not gains
  `:seon.test.member/terminated-tx`; its outcome is never touched. Otherwise
  this writes nothing: a result not yet recorded carries its exit itself."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/id :seon.test/sym]
                  :seon.store/transaction-data]}
  [database run-id test-symbol]
  (let [member (first (execution-members database run-id [test-symbol]))]
    (if (and (:seon.test.member/completed-tx member)
             (not (:seon.test.member/terminated-tx member)))
      [[:db/add (:db/id member) :seon.test.member/terminated-tx "datomic.tx"]]
      [])))

(defn record-exit!
  "Commit [[exit-tx]] on `connection`; a refusal is returned, never thrown."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.test.run/id :seon.test/sym]
                  [:or :seon.db/transaction-report :seon.db/transaction-refused-error]]}
  [connection run-id test-symbol]
  (let [report (db/transact! connection [[:db.fn/call #'exit-tx run-id test-symbol]])]
    (when-not (seon.error.refusal/error? report)
      (retain-results! (:db-after report) run-id))
    report))

(defn record-interrupted!
  "Stop `completion`'s run with `:request-threw` and commit the results its
  members captured before `failure`, the throwable that ended the request.

  The throw is the request's, never a member's: a member with no outcome
  gains no result, so it reads as an unknown (never green, never a designed
  red), keeps no reach digest and is selected again. Members already recorded
  are left as they are."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.test.run/completion :seon.error/throwable]
                  [:or :seon.test/results :seon.db/error-result]]}
  [connection completion ^Throwable _failure]
  (let [run-id (get-in completion [:seon.test.run/provenance :seon.test.run/id])
        completed (into #{} (keep #(when (:seon.test.member/completed-tx %) (:seon.test.member/symbol %)))
                        (execution-members (db/db connection) run-id))
        captured (vec (remove #(completed (:seon.test/sym %)) (:seon.test.runner/results completion)))
        recorded (if (seq captured)
                   (commit-results! connection (assoc completion :seon.test.run/terminated? true
                                                      :seon.test.runner/results captured))
                   [])
        stopped (when-not (seon.error.refusal/error? recorded)
                  (db/transact! connection [[:db/add [:seon.test.run/id run-id] :seon.test.run/stopped :request-threw]]))]
    (if (seon.error.refusal/error? stopped) stopped recorded)))

(def ^:private run-result-query
  '[:find ?symbol ?pass ?fail ?error ?id ?at ?basis ?program ?inputs
    :in $ [?member ...]
    :where [?member :seon.test.member/symbol ?symbol]
           [?member :seon.test.member/completed-tx]
           [?member :seon.test.member/terminated-tx]
           [?member :seon.test.member/pass-count ?pass]
           [?member :seon.test.member/fail-count ?fail]
           [?member :seon.test.member/error-count ?error]
           [?owner :seon.test.run/members ?member]
           [?owner :seon.test.run/id ?id]
           [?owner :seon.test.run/at ?at]
           [?owner :seon.test.run/basis-t ?basis]
           [?owner :seon.test.run/program-digest ?program]
           [?owner :seon.test.run/input-digest ?inputs]])

(defn- run-result-facts
  "Read complete admitted membership and terminal evidence from one database."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/id]
                  :seon.test.run/result-facts]}
  [database run-id]
  (let [members (execution-members database run-id)]
    {:seon.test.run/expected (set (map :seon.test.member/symbol members))
     :seon.test.run/rows (vec (execution-read
                              (db/q run-result-query database (mapv :db/id members))))}))

(defn- results-from-facts
  "Refuse incomplete coverage; preserve the owning execution's confidence."
  {:malli/schema [:=> [:cat :seon.test.run/id :seon.test.run/result-facts]
                  :seon.test/results]}
  [run-id {expected :seon.test.run/expected entities :seon.test.run/rows}]
  (when-not (and (= expected (set (map first entities))) (= (count expected) (count entities)))
    (execution-refusal! 'seon.test.runner/run-results run-id
                        :seon.test/population-unknown expected entities))
  (mapv (fn [[sym pass fail errors owner at basis program inputs]]
          (cond-> {:seon.test/sym sym :seon.test/pass-count pass
                   :seon.test/fail-count fail :seon.test/error-count errors
                   :seon.test/run [:seon.test.run/id owner]
                   :seon.test/run-at at :seon.test/run-basis-t basis
                   :seon.test.run/basis-t basis :seon.test.run/program-digest program
                   :seon.test.run/input-digest inputs}
            (not= run-id owner) (assoc :seon.test/unchanged true))) entities))

(defn- result-read-error
  {:malli/schema [:=> [:cat :seon.test.run/id :seon.error/throwable]
                  :seon.test/execution-error]}
  [run-id failure]
  (if (:seon.test/execution-refusal (ex-data failure))
    (ex-data failure)
    (seon.error.refusal/error :seon.test/execution-error
     {:seon.error/layer :seon.test/recording
      :seon.error/operation 'seon.test.runner/run-results
      :seon.error/message "Recorded run coverage is unavailable."
      :seon.error/expected :complete-recorded-membership
      :seon.error/offending (Throwable->map failure)
      :seon.error/data {:seon.test.run/id run-id}
      :seon.test/execution-refusal :seon.test/population-unknown})))

(defn run-results
  "Query the total recorded results of an admitted run, including reused members."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/id]
                  [:or :seon.test/results :seon.test/execution-error]]}
  [database run-id]
  (try (results-from-facts run-id (run-result-facts database run-id))
       (catch Exception failure (result-read-error run-id failure))))

(defn- newest-results
  "The result of the newest admitted execution among `members` (member entity to test symbol) on `database`, one
  per test, sorted by symbol: one query for the runs holding the members and one over those runs, however many.
  With `own?`, only runs of this branch's lineage: a run of another commit recorded here (its tested branch) is
  read only at its key."
  {:malli/schema [:=> [:cat :seon.db/database-value [:map-of :int :qualified-symbol] :boolean]
                  [:vector [:or :seon.test/result :seon.test/result-unknown]]]}
  [database members own?]
  (let [runs (when (seq members)
               (mapv (fn [[run member]] [(members member) run])
                     (execution-read
                      (db/q '[:find ?run ?member :in $ [?member ...]
                              :where [?run :seon.test.run/members ?member]]
                            database (vec (keys members))))))
        admitted (when (seq runs)
                   (into {} (keep (fn [[run id selected tested]] (when-not (and own? tested) [run [id selected]])))
                         (execution-read
                          (db/q '[:find ?run ?id ?selected ?tested
                                  :in $ [?run ...]
                                  :where [?run :seon.test.run/id ?id]
                                         [?run :seon.test.run/selection-tx ?selected]
                                         (not [?run :seon.test.run/published-base-digest])
                                         [(get-else $ ?run :seon.test.run/tested-branch false) ?tested]]
                                database (vec (distinct (map second runs)))))))
        entities (into #{} (keep (fn [[test-symbol run]]
                                   (when-let [[id selected] (get admitted run)] [test-symbol id selected])))
                       runs)
        latest (vals (reduce (fn [result [sym :as entity]] (assoc result sym entity))
                             (sorted-map) (sort-by #(nth % 2) entities)))
        results (if (seq latest) (recorded-member-results database (mapv (fn [[sym id]] [id sym]) latest)) {})]
    (->> latest
         (map (comp results first))
         (sort-by (some-fn :seon.test/sym :seon.test/error-test-symbol))
         vec)))

(defn latest-results
  "Read the latest native admitted execution of each requested test.
  Missing executions are absent; an incomplete latest execution is that test's
  `:seon.test/result-unknown`, never an older green. A failed read refuses the
  whole answer. Snapshot evidence never certifies this branch. A value reads every
  run its lineage holds, those it inherited from the branch it forked included:
  whether a result still holds is its reach digest's to decide, never the branch name.

  ## Costs

  An index read from each name (`:seon.test.member/symbol` AVET), one query
  for the runs holding those members and one over those runs: proportional
  to the requested tests' executions, never every member, and a run more
  adds no read."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
                  [:or :seon.test/latest-results :seon.test/execution-error]]}
  [database test-symbols]
  (try
    (newest-results database
                    (into {} (for [test-symbol (distinct test-symbols)
                                   {member :e} (execution-read (db/datoms database :avet :seon.test.member/symbol test-symbol))]
                               [member test-symbol]))
                    true)
    (catch Exception failure (result-read-error "latest-results" failure))))

(defn result-at-reach
  "Each test of `reaches` (test to reach digest) with the result of its newest admitted execution recorded on
  `database` under exactly that digest, however many later executions at other digests exist; a test none ran
  there is absent. One `:seon.test.member/symbol` index read per test and one digest read per member, then the
  newest-execution reads once for the whole set."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/reach-digests]
                  [:or [:map-of :seon.test/sym [:or :seon.test/result :seon.test/result-unknown]] :seon.test/execution-error]]}
  [database reaches]
  (try
    (let [members (into {} (for [[test-symbol digest] reaches
                                 {member :e} (execution-read (db/datoms database :avet :seon.test.member/symbol test-symbol))
                                 :when (= digest (:v (first (execution-read (db/datoms database :eavt member :seon.test.member/reach-digest)))))]
                             [member test-symbol]))]
      (into {} (map (juxt #(or (:seon.test/sym %) (:seon.test/error-test-symbol %)) identity))
            (newest-results database members false)))
    (catch Exception failure (result-read-error "result-at-reach" failure))))

(defn current-results
  "Each of `test-symbols` with the result of its newest admitted execution on `database` recorded at its current
  identity there judging `task` (nil: none): the reach digest `reach-digests` computes with the definitions that
  execution's own body ran outside its closure (`:seon.test.member/dynamic-calls`) and the task's content, the one
  identity the recorder writes (`record`) and selection reuses by. A test none ran at its identity is absent. One
  reach read per distinct set of a test's dynamic calls, then the newest-execution reads once for the whole set."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :seon.test/sym] [:maybe :seon.test/task]]
                  [:or [:map-of :seon.test/sym [:or :seon.test/result :seon.test/result-unknown]]
                   :seon.test/execution-error :seon.test/unknown-error :seon.error/value]]}
  [database test-symbols task]
  (try
    (let [ran (into {} (for [test-symbol (distinct test-symbols)
                             {member :e} (execution-read (db/datoms database :avet :seon.test.member/symbol test-symbol))
                             :let [digest (:v (first (execution-read (db/datoms database :eavt member :seon.test.member/reach-digest))))]
                             :when digest]
                         [member [test-symbol digest (into (sorted-set) (map :v) (execution-read (db/datoms database :eavt member :seon.test.member/dynamic-calls)))]]))
          ;; each test's distinct dynamic sets, read in rounds of one set per test
          sets (reduce (fn [result [test-symbol _ dynamic]] (update result test-symbol (fnil conj #{}) dynamic)) {} (vals ran))
          rounds (apply max 0 (map count (vals sets)))
          identities (reduce (fn [result i]
                               (if (seon.error.refusal/error? result)
                                 (reduced result)
                                 (let [round (into {} (keep (fn [[test-symbol dynamic]] (when-let [d (nth (vec dynamic) i nil)] [test-symbol d]))) sets)
                                       digests (reach-digests database (vec (keys round)) (into {} (filter (comp seq val)) round) task)]
                                   (if (seon.error.refusal/error? digests)
                                     (reduced digests)
                                     (into result (map (fn [[test-symbol d]] [[test-symbol d] (get digests test-symbol)])) round)))))
                             {} (range rounds))]
      (if (seon.error.refusal/error? identities)
        identities
        (let [members (into {} (keep (fn [[member [test-symbol digest dynamic]]]
                                       (when (= digest (get identities [test-symbol dynamic])) [member test-symbol])))
                            ran)]
          (into {} (map (juxt #(or (:seon.test/sym %) (:seon.test/error-test-symbol %)) identity))
                (newest-results database members false)))))
    (catch Exception failure (result-read-error "current-results" failure))))

(defn red-result?
  "Whether `result` is an executed, failing recorded test result."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [result]
  (boolean (and (:seon.test/pass-count result)
                (pos? (+ (:seon.test/fail-count result) (:seon.test/error-count result))))))

(defn designed-red?
  "Whether `result` is a Prove step's designed red: an executed red with at
  least one failing report, a failing assertion or an error its body raised.
  A test that cannot load never runs, so it has no result to count."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [result]
  (boolean (and (red-result? result) (seq (:seon.test.failure/reports result)))))

(defn red-at-current-reach?
  "Whether `test-symbol`'s result at its current identity on `database`
  (`current-results`, no task) is red: red on that branch as it stands, not only once."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym] :boolean]}
  [database test-symbol]
  (let [current (current-results database [test-symbol] nil)]
    (boolean (and (map? current) (not (:seon.error/at current)) (red-result? (get current test-symbol {}))))))
