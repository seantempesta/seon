(ns seon.test.runner
  "Capture test Var results in this JVM and commit them as per-member facts."
  (:require [seon.error.refusal]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test :as test]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.instrument :as instrument]
            [seon.id :as id]
            [seon.program :as program]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import (java.nio.charset StandardCharsets)))

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

(defn- printable
  [options value]
  (if (instance? Throwable value)
    (str (.getName (class value)) ": " (or (ex-message value) ""))
    (binding [*print-length* (:seon.print/length options)
              *print-level* (:seon.print/level options)]
      (pr-str value))))

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

(defn- throwable-text
  "One reported throwable as its own diagnostic body.

  Its complete message plus frames bounded by the DECLARED
  `:seon.print/length`. THE SIGNATURE IS NOT PART OF THIS TEXT: the cause's
  signature is the error face's own trailer, named once per distinct cause by
  `report-error!`, and a second copy carried inside the reported value made
  one whole face name the same signature twice."
  [options ^Throwable failure]
  (str/trim-newline
   (with-out-str
     (println (str (.getName (class failure)) ": "
                   (or (ex-message failure) "")))
     (doseq [frame (take (:seon.print/length options)
                         (.getStackTrace failure))]
       (println "    at" frame)))))

(defn- report-value
  "One reported assertion value, rendered for a human reading the gate log.

  A THROWABLE IS A DIAGNOSTIC, NOT AN AGENT PROJECTION. The AI profile's token
  budget is shared across the whole rendered value, so a large `ex-data`
  squeezes the exception's own message out of the report: a fixture refusal
  arrived as `{:seon.print/omitted 3458 :seon.print/prefix \"Fixture write was
  refused at the write: …\"}` and named less than the runner knew. A check that
  reports less than it observed is the failure class this project keeps
  meeting, so the reported throwable is plain text — its complete message plus
  frames bounded by the DECLARED print length, never the agent's budget
  (AGENTS §2.4). The identity path (`printable`) has always done this. It is
  the throwable's BODY (`throwable-text`), not a whole face: the error face's
  signature trailer belongs to `report-error!`, which names it once per
  distinct cause.

  Ordinary values keep the profile: they are the ones that can be a whole
  database value."
  [options reported-value]
  (if (instance? Throwable reported-value)
    (throwable-text options reported-value)
    (value/render-ai
     {:seon.render/value reported-value
      :seon.render.value/root 'seon.test.runner/assertion
      :seon.render/profile (or (:seon.render/profile options)
                               (:seon.render/profile (report-options)))})))

(defn- failure-message
  [options event]
  (->> [(when (seq test/*testing-contexts*)
          (test/testing-contexts-str))
        (:message event)
        (when (contains? event :expected)
          (str "expected: " (report-value options (:expected event))))
        (when (contains? event :actual)
          (str "actual: " (report-value options (:actual event))))]
       (remove str/blank?)
       (str/join "\n")))

(defn- failure-identity
  "Content identity for one normalized failing assertion report.

  SCI reports interpreted tests from generic JVM frames, so a source position
  would claim precision the reporter does not have. The normalized report is
  stable across reruns and distinguishes different failing claims."
  [options test-symbol event]
  (schema/sha-256
   [(.getBytes
     (pr-str [test-symbol
              (:type event)
              (:message event)
              (when (contains? event :expected)
                (printable options (:expected event)))
              (when (contains? event :actual)
                (printable options (:actual event)))
              (event-signature event)])
     StandardCharsets/UTF_8)]))

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

(defn- failure-report [event]
  (cond-> {:seon.test.failure/type (:type event)}
    (find event :expected) (assoc :seon.test.failure/expected (printable {} (:expected event)))
    (find event :actual) (assoc :seon.test.failure/actual (printable {} (:actual event)))
    (:message event) (assoc :seon.test.failure/message (str (:message event)))
    (seq test/*testing-contexts*)
    (assoc :seon.test.failure/contexts (mapv vector (range) (reverse test/*testing-contexts*)))
    (:file event) (assoc :seon.test.failure/reported-file (str (:file event)))
    (and (integer? (:line event)) (pos? (:line event)))
    (assoc :seon.test.failure/line (:line event))
    (event-signature event) (assoc :seon.test.failure/signature (event-signature event))
    (instance? Throwable (:actual event))
    (assoc :seon.test.failure/throwable (symbol (.getName (class (:actual event)))))))

(defn- capture-event!
  [options capture selected-namespaces event]
  (when-let [test-symbol (event-symbol event)]
    (when (contains? selected-namespaces (symbol (namespace test-symbol)))
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
                   (let [failure-id
                         (failure-identity options test-symbol event)
                         seen? (contains?
                                (get-in current [::results test-symbol
                                                 ::failure-identities])
                                failure-id)]
                     (cond-> (-> current
                                 (update-in [::results test-symbol :seon.test.failure/reports]
                                            (fnil conj [])
                                            (assoc (failure-report event)
                                                   :seon.test/failure-identity failure-id))
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
                                  conj (failure-message options event))))

                   current)))))))

(defn- report-error!
  [options event signature]
  (test/with-test-out
    (test/inc-report-counter :error)
    (println "\nERROR in" (test/testing-vars-str event))
    (println (failure-message options event))
    (when signature
      (println "  signature:" signature))))

(defn- report-event!
  [options default-report reported-signatures event]
  (if (and (= :error (:type event))
           (instance? Throwable (:actual event)))
    (let [signature (event-signature event)]
      (if (and signature (contains? @reported-signatures signature))
        (test/inc-report-counter :error)
        (do
          (when signature
            (swap! reported-signatures conj signature))
          (report-error! options event signature))))
    (if (#{:fail :error} (:type event))
      (test/with-test-out
        (test/inc-report-counter (:type event))
        (println (str "\n" (str/upper-case (name (:type event))) " in")
                 (test/testing-vars-str event))
        (println (failure-message options event)))
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

(defn- duration-failures
  "Turn an observed body overrun into ordinary, durably recorded assertion evidence."
  {:malli/schema [:=> [:cat :seon.test.runner/report-options :seon.test/var :seon.test/elapsed-ms :seon.test/time-limit-ms]
                  [:vector :seon.test/duration-failure]]}
  [_options test-var elapsed ordinary]
  (let [metadata (meta test-var)
        declaration (program/test-markers metadata (meta (:ns metadata)))
        reason (:seon.test/long declaration)
        allowance (:seon.test/long-ms declaration)
        limit (if (and (string? reason) (not (str/blank? reason))
                       (integer? allowance) (pos? allowance))
                (max ordinary allowance) ordinary)]
    (if (> elapsed limit)
      [{:type :fail :var test-var
        :message (str "Test " (var-symbol test-var) " exceeded its declared duration: "
                      elapsed " ms; bound " limit " ms.")
        :expected {:seon.test/time-limit-ms limit}
        :actual {:seon.test/elapsed-ms elapsed}}]
      [])))

(defn- capture-and-report-event!
  [options capture selected-namespaces default-report reported-signatures event]
  (capture-event! options capture selected-namespaces event)
  (when-let [failure (assertionless-failure capture event)]
    (capture-event! options capture selected-namespaces failure)
    (report-event! options default-report reported-signatures failure))
  (when (= :end-test-var (:type event))
    (when-let [started (get-in @capture [::results (event-symbol event) ::started-nanos])]
      (doseq [failure (duration-failures options (:var event)
                                       (/ (double (- (get-in @capture [::results (event-symbol event) ::ended-nanos])
                                                     started)) 1e6)
                                       (:seon.test/time-limit-ms options))]
        (capture-event! options capture selected-namespaces failure)
        (report-event! options default-report reported-signatures failure))))
  (report-event! options default-report reported-signatures event))

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
  (if-let [unrunnable (first (remove #(ifn? (:test (meta %))) test-vars))]
    (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/execution `run-vars!
     {:seon.error/message "The supplied Var has no clojure.test function."
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
        (db/call-with-custody custody
          #(run-selected-tests (sort selected-namespaces) test-vars
                               (:seon.sci.eval/ctx custody))))
      (let [results (captured-results @capture)
            drift (ambient-drift before (ambient-snapshot))]
        (mapv (fn [result]
                (cond-> result
                  (seq drift) (update :seon.test/error-count (fnil inc 0))
                  (seq drift) (update :seon.test/failure-message
                                      #(str (when % (str % "\n"))
                                            "Worker-global state changed: " (pr-str drift)))))
              results)))))

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
              {:seon.error/at (java.util.Date.)
                :seon.error/layer :seon.test/execution
                :seon.error/operation `run-selected-tests
                :seon.error/message "A namespace test hook requires its complete admitted selection."
                :seon.ns/name namespace-name
                :seon.test.runner/long-test-ns-hook namespace-name
                :seon.error/expected "the namespace's complete admitted test selection"
                :seon.error/offending namespace-vars
                :seon.error/data {:seon.ns/name namespace-name}})))
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
                (and (map? database)
                     (contains? database :seon.error/at)))
          {:seon.cluster/name cluster-name
           ::schema-authority-unavailable
           (if connection
             (str "The cluster's declaration facts could not be read: "
                  (:seon.error/message database))
             "The snapshot carries no connection, so the cluster's declaration facts could not be read.")}
          ;; The ENTERING projection is the reusable value, never the exit
          ;; one: reuse is decided by the fingerprint the value CARRIES, and
          ;; a projection a run edited in memory still carries the
          ;; fingerprint of the rows it was built from. Reusing it would let
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
                  row (cond-> {:seon.cluster/name cluster-name}
                        (seq drift-added)
                        (assoc ::drift-added (named drift-added))
                        (seq drift-removed)
                        (assoc ::drift-removed (named drift-removed))
                        (seq committed-added)
                        (assoc ::committed-added (named committed-added))
                        (seq committed-removed)
                        (assoc ::committed-removed (named committed-removed)))]
              (when (next row) row))))))
    before)))

(defn schema-restore-drift
  "The restore rows naming a run's OWN uncommitted change, and only those.

  A committed addition or retraction is the writer doing its job during a
  run; a key the run registered or dropped in memory is the run failing to
  own nothing global. Only the second is a test error, so the caller that
  turns rows into a verdict asks here rather than reading the row keys."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [rows]
  (filterv #(or (seq (::drift-added %)) (seq (::drift-removed %))) rows))

(defn- ambient-snapshot
  "Facts about this worker JVM's process-global state, DERIVED.

  AGENTS §5.7: a test owns nothing global. Nothing DECLARES which state that
  is, and a declaration would be the maintained list §2.2 bans, so the worker
  measures the shared state it can see and reports what a task changed.

  Every member is a fact about the running process, not a count somebody kept:
  the wrappers malli actually installed, the contracts it actually holds, the
  clusters actually running, and the shared SCI base each fork inherits."
  []
  (let [snapshot
        {::snapshot-instrumented
         (into #{}
               (map (fn [candidate]
                      (let [{namespace-object :ns var-name :name}
                            (meta candidate)]
                        (symbol (str (ns-name namespace-object))
                                (str var-name)))))
               (instrument/instrumented))
         ::snapshot-registered
         (into #{}
               (mapcat (fn [[namespace-symbol entries]]
                         (map (fn [[name-symbol _]]
                                (symbol (str namespace-symbol)
                                        (str name-symbol)))
                              entries)))
               (m/function-schemas))
         ::snapshot-live-clusters
         (or (some-> (resolve-loaded 'seon.cluster/running-instances)
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

(defn- bounded-drift
  [before after]
  (let [added (set/difference after before)
        removed (set/difference before after)]
    (cond-> {}
      (seq added) (assoc ::drift-added
                         (vec (take 10 (sort (map str added))))
                         ::drift-added-count (count added))
      (seq removed) (assoc ::drift-removed
                           (vec (take 10 (sort (map str removed))))
                           ::drift-removed-count (count removed)))))

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
  that disappeared is a test stopping something it did not start."
  {::snapshot-instrumented #{::drift-added ::drift-removed}
   ::snapshot-registered #{::drift-removed}
   ::snapshot-live-clusters #{::drift-added ::drift-removed}})

(defn- ambient-drift
  "What one task changed in the worker's process-global state, or nothing.

  Each member's declared leak directions decide what counts as drift."
  [before after]
  (let [set-drift
        (into {}
              (keep (fn [[member directions]]
                      (let [drift (select-keys
                                   (bounded-drift (get before member #{})
                                                  (get after member #{}))
                                   (into #{}
                                         (mapcat (fn [direction]
                                                   [direction
                                                    (keyword
                                                     (namespace direction)
                                                     (str (name direction)
                                                          "-count"))]))
                                         directions))]
                        (when (seq drift) [member drift]))))
              drift-directions)]
    set-drift))

(def ^:private reach-attributes
 [:seon.fn/sym :seon.fn/source :seon.fn/spec :seon.fn/calls :seon.fn/references :seon.fn/keywords
  :seon.test/sym :seon.test/source :seon.test/subject
  :seon.schema/key :seon.schema/form])
(defn- reach-keywords [form]
 (into #{} (filter qualified-keyword?) (tree-seq coll? seq form)))
(defn- reach-canonical [value]
 (walk/postwalk (fn [v] (cond
  (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v)
  (set? v) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) v)
  :else v)) value))
(defn- reach-row [row]
 (let [sym (or (:seon.test/sym row) (:seon.fn/sym row))
       schema-key (:seon.schema/key row)
       spec (some-> (:seon.fn/spec row) edn/read-string)
       form (some-> (:seon.schema/form row) edn/read-string)]
  (cond-> row
   sym (assoc ::reach-symbol sym
              ::reach-leaf (id/digest 64 [sym (or (:seon.test/source row) (:seon.fn/source row)) (:seon.fn/spec row)])
              ::reach-keys (into (set (:seon.fn/keywords row)) (reach-keywords spec)))
   schema-key (assoc ::reach-leaf (id/digest 64 [schema-key (reach-canonical form)])
                     ::reach-keys (reach-keywords form)))))
(defn- reach-schema-keys [rows schemas seeds]
  (loop [pending (vec seeds) seen #{}]
    (if-let [k (peek pending)]
      (if (seen k)
        (recur (pop pending) seen)
        (recur (into (pop pending) (get-in rows [(get schemas k) ::reach-keys]))
               (conj seen k)))
      seen)))

(defn- reach-facts
  "Read only requested declarations and their transitive call/schema references."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:sequential [:or :qualified-symbol :qualified-keyword]]]
    [:map-of :int [:map-of :qualified-keyword :seon.schema/value]]]}
  [database test-symbols]
  (let [installed-schema (:schema (db/schema-database database))
        identities (fn [tokens]
                     (mapcat (fn [token]
                               (if (keyword? token)
                                 [[:seon.schema/key token]]
                                 [[:seon.fn/sym token] [:seon.test/sym token]])) tokens))]
    (loop [pending (set (identities test-symbols)) seen #{} rows {}]
      (if (empty? pending)
        rows
        (let [entities (mapcat
                        (fn [[attribute pairs]]
                          (let [result (db/q '[:find [?entity ...]
                                               :in $ ?identity [?token ...]
                                               :where [?entity ?identity ?token]]
                                             database attribute (mapv second pairs))]
                            (when (map? result)
                              (throw (ex-info "Reach identities unavailable." result)))
                            result))
                        (group-by first pending))
              facts (if (seq entities)
                      (db/q '[:find ?entity ?attribute ?value
                              :in $ [?entity ...] ?attributes
                              :where [?entity ?attribute ?value]
                                     [(contains? ?attributes ?attribute)]]
                            database (vec (distinct entities)) (set reach-attributes))
                      [])
              _ (when (map? facts)
                  (throw (ex-info "Reach rows unavailable." facts)))
              acquired (reduce (fn [result [entity attribute value]]
                                 (if (= :db.cardinality/many
                                        (get-in installed-schema [attribute :db/cardinality]))
                                   (update-in result [entity attribute] (fnil conj #{}) value)
                                   (assoc-in result [entity attribute] value)))
                               {} facts)
              tokens (mapcat (fn [[_ row]]
                               (concat (:seon.fn/calls row) (:seon.fn/references row)
                                       (when-let [subject (:seon.test/subject row)] [subject])
                                       (:seon.fn/keywords row)
                                       (reach-keywords (some-> (:seon.fn/spec row) edn/read-string))
                                       (reach-keywords (some-> (:seon.schema/form row) edn/read-string))))
                             acquired)
              seen (into seen pending)]
          (recur (into #{} (remove seen) (identities tokens)) seen (merge rows acquired)))))))

(defn- reach-revisions
  "Datahike's revisions of the attributes reach reads (`datahike.db/advance-cache-context`,
  `reference-code/datahike/src/datahike/db.cljc:423`): equal revisions mean no reach
  datom changed, so a moved head costs one map comparison, not a history scan."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil [:vector :seon.schema/value]]]}
  [database]
  (let [context (:cache-context database)]
    (when (:datahike.cache/committed? context)
      [(:datahike.cache/connection-id context) (:datahike.cache/generation context)
       (:datahike.cache/conservative-revision context)
       (select-keys (:datahike.cache/attribute-revisions context) reach-attributes)])))

(defn- reach-refresh [database previous test-symbols]
 (let [basis (db/basis-t database)
       revisions (reach-revisions database)
       changed-entities (when (and previous (not (and revisions (= revisions (::reach-revisions previous)))))
                          (db/q '[:find [?entity ...] :in $ [?attribute ...]
                                  :where [?entity ?attribute]]
                                (db/since (db/history database) (::reach-basis previous))
                                reach-attributes))]
   (when (map? changed-entities)
     (throw (ex-info "Reach changes unavailable." changed-entities)))
   (if (and previous (empty? changed-entities)
            (every? #(get-in previous [::reach-symbols %]) test-symbols))
     (assoc previous ::reach-basis basis ::reach-revisions revisions ::reach-updated 0 ::reach-invalidated 0)
 (let [by-entity (reach-facts database test-symbols)
       ;; Rows re-read for these tests, and retained rows a write since the
       ;; previous basis touched; every other retained row is kept as it is.
       ids (into (set (keys by-entity)) (filter (::reach-rows previous {})) changed-entities)
       old-rows (::reach-rows previous {})
       pulled (mapv (fn [entity] (assoc (get by-entity entity {}) :db/id entity)) ids)
       changed (filterv #(not= (dissoc (get old-rows (:db/id %)) ::reach-symbol ::reach-leaf ::reach-keys) %) pulled)
       rows (reduce (fn [rs r] (assoc rs (:db/id r) (reach-row r))) old-rows changed)
       schemas (if (seq changed)
                 (into {} (keep (fn [[e row]] (when-let [k (:seon.schema/key row)] [k e]))) rows)
                 (::reach-schemas previous {}))
       tokens (into (set (map :db/id changed))
                    (mapcat (fn [r] (for [v [r (get old-rows (:db/id r))]
                                         :let [s (or (:seon.test/sym v) (:seon.fn/sym v))
                                               k (:seon.schema/key v)]
                                         token (cond-> [] s (conj [::reach-symbol s]) k (conj [::reach-schema k]))]
                                     token))) changed)
       kept (if (seq tokens)
              (into {} (remove (fn [[_ entry]] (some (::reach-dependencies entry) tokens)))
                    (::reach-digests previous {}))
              (::reach-digests previous {}))]
  (assoc (or previous {})
    ::reach-basis basis ::reach-revisions revisions ::reach-rows rows
    ::reach-symbols (if (seq changed) (into {} (keep (fn [[e r]] (when-let [s (::reach-symbol r)] [s e]))) rows) (::reach-symbols previous {}))
    ::reach-schemas schemas
    ::reach-digests kept
    ::reach-updated (count changed) ::reach-invalidated (- (count (::reach-digests previous)) (count kept)))))))
(defn- reach-entry [index test-symbol]
  (let [rows (::reach-rows index)
        symbols (::reach-symbols index)
        schemas (::reach-schemas index)
        start (get symbols test-symbol)
        names (loop [pending (if start [test-symbol] []) seen #{}]
                (if-let [target (peek pending)]
                  (if (seen target)
                    (recur (pop pending) seen)
                    (let [row (get rows (get symbols target))]
                      (recur (into (pop pending)
                                   (concat (:seon.fn/calls row)
                                           (:seon.fn/references row)
                                           (when-let [subject (:seon.test/subject row)] [subject])))
                             (conj seen target))))
                  seen))
        entities (into #{} (keep symbols) names)
        keyword-seeds (reduce into #{} (map #(get-in rows [% ::reach-keys]) entities))
        schema-keys (reach-schema-keys rows schemas keyword-seeds)
        node-parts (mapv (fn [name] [name (get-in rows [(get symbols name) ::reach-leaf]
                                                  :seon.error/unknown)]) (sort names))
        schema-parts (mapv (fn [key] [key (get-in rows [(get schemas key) ::reach-leaf])])
                           (sort schema-keys))]
    {::reach-digest (id/digest 64 [test-symbol node-parts schema-parts])
     ::reach-refs (when start
                    (into #{} (remove #(= % test-symbol)) names))
     ::reach-dependencies (into entities
                                (concat (map #(vector ::reach-symbol %) names)
                                        (map #(vector ::reach-schema %) schema-keys)))
     ::reach-function-count (count names)}))
(defn- reach-cache [database]
 (or (:seon.sci.eval/projection-state (meta database))
     (when-let [projection (db/carried-projection database)]
      (schema/projection-cache-value projection
       [::reach-cache (:config database)] #(atom nil)))))
(defn- reach-entries
 "Derive selected reach digests, incrementally on the database's carried cache.
 Result-only transactions invalidate nothing. A cache retains only one basis;
 older or different branch values derive independently and never replace it."
 [database test-symbols]
 (if (empty? test-symbols)
   {}
 (try
 (let [holder (reach-cache database)
       configuration (:config database)
       value-identity (db/committed-value-identity database)
       derive-index (fn [previous]
                (let [index (if (and (= (db/basis-t database) (::reach-basis previous))
                                    (every? #(get-in previous [::reach-symbols %]) test-symbols))
                              previous (reach-refresh database previous test-symbols))
                      missing (remove #(get-in index [::reach-digests % ::reach-refs]) test-symbols)
                      index (reduce (fn [i s] (assoc-in i [::reach-digests s] (reach-entry i s))) index missing)]
                 (assoc index ::reach-config configuration ::reach-value-identity value-identity ::reach-computed (count missing))))]
  (if holder
   (locking holder
    (let [previous (::reach-index (meta holder))
          revisions (reach-revisions database)
          ;; Equal reach-attribute revisions name the same reach rows at any basis.
          usable (and value-identity (= configuration (::reach-config previous))
                      (or (< (::reach-basis previous 0) (db/basis-t database))
                          (= value-identity (::reach-value-identity previous))
                          (and revisions (= revisions (::reach-revisions previous)))))
          index (derive-index (when usable previous))]
     (when (and value-identity (or usable (nil? previous)))
      (alter-meta! holder assoc ::reach-index index))
     (select-keys (::reach-digests index) test-symbols)))
   (let [index (derive-index nil)]
    (select-keys (::reach-digests index) test-symbols))))
 (catch Exception failure
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/recording `reach-entries
   {:seon.error/message (str "Reach digest unavailable: " (ex-message failure))
    :seon.test/unknown "reach digest"
    :seon.error/member :seon.test/reach-digests
    :seon.error/expected "derived reach digests for the requested tests"
    :seon.error/offending test-symbols
    :seon.error/data {:seon.test/syms test-symbols}})))))

(defn reach-digests
  "Derive equality keys from the tested database's incremental reach index."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                  [:or :seon.test/reach-digests :seon.test/unknown-error]]}
  [database test-symbols]
  (let [entries (reach-entries database test-symbols)]
    (if (:seon.test/unknown entries)
      (assoc (select-keys entries [:seon.error/message :seon.test/unknown])
             :seon.error/at (java.util.Date.)
             :seon.error/layer :seon.test/reach
             :seon.error/operation 'seon.test.runner/reach-digests)
        (into {} (map (fn [[s entry]] [s (::reach-digest entry)])) entries))))

(defn reach-memberships
  "The tested closure's function names, including unresolved targets."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                  [:or :seon.test/reaches :seon.test/unknown-error]]}
  [database test-symbols]
  (let [entries (reach-entries database test-symbols)]
    (if (contains? entries :seon.test/unknown) entries
        (into {} (keep (fn [[s entry]] (when (set? (::reach-refs entry))
                                       [s (::reach-refs entry)]))) entries))))

(defn- program-digest-read-attributes
  "Every attribute the digest reads: the source seal and the program rows' own
  attributes (`program/program-attributes`), with the identities that select them."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:set :qualified-keyword]]}
  [projection]
  (-> (program/program-attributes projection)
      (into program/identity-attributes)
      (conj :seon.source/digest)))

(defn program-written-since?
  "Whether any datom of an attribute the program digest reads was written after
  `basis-t` on `database`'s lineage: O(datoms since `basis-t`). A value with no
  such write has the program digest of its `basis-t` value, which lets the
  writer's in-transaction value (no commit id, so no memo) confirm a digest
  its caller derived instead of deriving it again."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/basis-t]
                  [:or :boolean :seon.db/invalid-read-error]]}
  [database basis-t]
  (let [written (db/q '[:find ?entity . :in $ [?attribute ...] :where [?entity ?attribute]]
                      (db/since (db/history database) basis-t)
                      (vec (program-digest-read-attributes
                            (or (db/carried-projection database) (schema/handed-projection)))))]
    (if (and (map? written) (:seon.error/at written)) written (some? written))))

(defn program-revisions
  "Datahike's revisions of every attribute a program row carries, with the
  value's connection, generation and conservative revision
  (`datahike.db/advance-cache-context`, `reference-code/datahike/src/datahike/db.cljc:423`):
  equal keys name equal program rows, whatever else was written between them.
  Nil for a value with no committed context."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil [:vector :seon.schema/value]]]}
  [database]
  (let [context (:cache-context database)]
    (when (:datahike.cache/committed? context)
      (let [projection (db/carried-projection database)]
        [(:datahike.cache/connection-id context) (:datahike.cache/generation context)
         (:datahike.cache/conservative-revision context)
         (select-keys (:datahike.cache/attribute-revisions context)
                      (schema/projection-cache-value
                       projection ::program-digest-read-attributes
                       #(program-digest-read-attributes projection)))]))))

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
    (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/provenance 'seon.test.runner/program-digest
     {:seon.test.run/unavailable true
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
  (let [digest (program-digest database)]
    (if (and (map? digest) (contains? digest :seon.error/at) (contains? digest :seon.error/layer) (contains? digest :seon.error/operation)) digest
        {:seon.test.run/id (id/id)
   :seon.test.run/at (java.util.Date.)
   :seon.test.run/program-digest digest
   :seon.test.run/basis-t (db/basis-t database)
   :seon.test.run/branch (get-in database [:config :branch])})))

(def ^:private report-position-attributes
  ;; Where a claim was reported: the facts `failure-identity` leaves out of
  ;; the captured signature, which covers the claim's content (type, message,
  ;; expected, actual, event signature).
  [:seon.test.failure/line :seon.test.failure/reported-file :seon.test.failure/contexts])

(defn report-row
  "The stored report for one captured failure of `test-symbol`.

  Its identity is the captured signature and the position it was reported
  at: the same claim reported from a moved line is another report, never a
  conflict with the earlier one, while other content under an unchanged
  signature at the same position is the same identity with different facts,
  which the recorder refuses as a report conflict."
  {:malli/schema [:=> [:cat [:vector :qualified-keyword] :seon.test/sym :map]
                  [:map [:seon.test.report/id :string]
                   [:seon.test.report/symbol :seon.test/sym]]]}
  [report-attributes test-symbol failure]
  (let [report (into (sorted-map)
                     (assoc (select-keys failure report-attributes)
                            :seon.test.report/symbol test-symbol))]
    (assoc report :seon.test.report/id
           (id/id [test-symbol (:seon.test/failure-identity report)
                   (into (sorted-map) (select-keys report report-position-attributes))]))))

(defn- execution-refusal! [operation run-id kind expected observed]
  (let [failure (assoc (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/execution operation
                        {:seon.error/message "The test execution evidence does not authorize this transition."
                  :seon.error/expected expected
                  :seon.error/offending observed
                  :seon.error/data {:seon.test.run/id run-id}}) :seon.test/execution-refusal kind)]
    (throw (ex-info (:seon.error/message failure) failure))))

(defn- execution-read [value]
  (when (and (map? value) (contains? value :seon.error/at) (contains? value :seon.error/layer) (contains? value :seon.error/operation))
    (throw (ex-info (:seon.error/message value) value)))
  value)

(defn- execution-members
  "The admitted members of `run-id` (those named by `symbols` when given), each
  pulled once. Membership refs are asserted only by the admission transaction,
  so any membership datom from another transaction (an addition or a
  retraction) is a changed population: one history read of this run's refs,
  never an as-of view of the store."
  ([database run-id] (execution-members database run-id nil))
  ([database run-id symbols]
   (let [run (execution-read
              (db/pull database [:db/id :seon.test.run/selection-tx]
                       [:seon.test.run/id run-id]))
         _ (when-not (:seon.test.run/selection-tx run)
             (execution-refusal! 'seon.test.runner/execution-members run-id
                                 :seon.test/population-unknown :admitted-selection
                                 (or run :absent)))
         attributes [:seon.test.run/members :seon.test.run/covered-by]
         changed (execution-read
                  (db/q '[:find [?member ...] :in $ ?run [?attribute ...] ?selected
                          :where [?run ?attribute ?member ?tx] [(not= ?tx ?selected)]]
                        (db/history database) (:db/id run) attributes
                        (get-in run [:seon.test.run/selection-tx :db/id])))
         _ (when (seq changed)
             (execution-refusal! 'seon.test.runner/execution-members run-id
                                 :seon.test/population-unknown :admitted-membership changed))
         current (execution-read
                  (if symbols
                    (db/q '[:find [?member ...] :in $ ?run [?attribute ...] [?symbol ...]
                            :where [?run ?attribute ?member] [?member :seon.test.member/symbol ?symbol]]
                          database (:db/id run) attributes (vec symbols))
                    (db/q '[:find [?member ...] :in $ ?run [?attribute ...]
                            :where [?run ?attribute ?member]]
                          database (:db/id run) attributes)))]
     (mapv
      (fn [member]
        (let [counts (select-keys member [:seon.test.member/pass-count :seon.test.member/fail-count
                                          :seon.test.member/error-count])]
          (when (or (and (:seon.test.member/completed-tx member)
                         (or (not= 3 (count counts))
                             (not (boolean? (:seon.test.member/began? member)))
                             (not (boolean? (:seon.test.member/ended? member)))))
                    (and (seq counts) (not (:seon.test.member/completed-tx member))))
            (execution-refusal! 'seon.test.runner/execution-members run-id
                                :seon.test/population-unknown :complete-outcome member))
          member))
      (execution-read
       (db/pull-many database
                     [:db/id :seon.test.member/symbol :seon.test.member/reasons
                      :seon.test.member/worker :seon.test.member/claimed-at
                      :seon.test.member/claim-tx :seon.test.member/host
                      :seon.test.member/completed-tx :seon.test.member/terminated-tx
                      :seon.test.member/pass-count :seon.test.member/fail-count
                      :seon.test.member/error-count :seon.test.member/began?
                      :seon.test.member/ended? :seon.test.member/error
                      :seon.test.member/reach-digest]
                     (vec current)))))))

(defn- worker-identity [database worker]
  (execution-read
   (db/pull database [:db/id :seon.db.process/id
                     :seon.db.process/pid :seon.db.process/start-instant] worker)))

(defn- prepare-failures!
  [connection database completion]
  (let [run (:seon.test.run/provenance completion)
        tested-branch (or (:seon.test.run/tested-branch run) (:seon.test.run/branch run))
        tested (or (:seon.db/db completion)
                   (when (= tested-branch (get-in database [:config :branch]))
                     (db/as-of database (:seon.test.run/basis-t run)))
                   database)
        threshold (db/q '[:find ?n . :where [_ :seon.config.eval.result/blob-threshold ?n]] database)
        _ (when (and (map? threshold) (contains? threshold :seon.error/at) (contains? threshold :seon.error/layer) (contains? threshold :seon.error/operation))
            (throw (ex-info "Cannot read the assertion blob threshold." threshold)))
        staged (volatile! [])
        stage-field
        (fn [failure field size-key blob-key]
          (if-let [text (get failure field)]
            (let [size (alength (.getBytes ^String text StandardCharsets/UTF_8))]
              (if (and threshold (> size threshold))
                (let [write (blob/stage! connection text)]
                  (vswap! staged conj write)
                  (-> failure (dissoc field)
                      (assoc size-key size blob-key (:seon.blob/digest write))))
                (assoc failure size-key size)))
            failure))
        results
        (mapv
          (fn [{test-symbol :seon.test/sym :as result}]
            (let [test-row (db/pull tested
                                   '[{:seon.fn/file [:seon.fn.file/relative-path]}]
                                   [:seon.test/sym test-symbol])
                  path (get-in test-row [:seon.fn/file :seon.fn.file/relative-path])
                  reports (or (seq (:seon.test.failure/reports result))
                              (when (pos? (+ (:seon.test/fail-count result 0)
                                             (:seon.test/error-count result 0)))
                                (let [event {:type (if (pos? (:seon.test/error-count result 0)) :error :fail)
                                             :message (or (:seon.test/failure-message result)
                                                          "The runner reported a failure without an assertion event.")}]
                                  [{:seon.test.failure/type (:type event)
                                    :seon.test.failure/message (:message event)
                                    :seon.test/failure-identity
                                    (failure-identity {} (symbol test-symbol) event)}])))
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
                                        (stage-field :seon.test.failure/expected :seon.test.failure/expected-size :seon.test.failure/expected-blob)
                                        (stage-field :seon.test.failure/actual :seon.test.failure/actual-size :seon.test.failure/actual-blob))]
                        [(assoc ordinals site (inc ordinal)) (conj failures failure)]))
                    [{} []] reports)]
              (-> result (dissoc :seon.test.failure/reports)
                  (assoc :seon.test/failures failures))))
          (:seon.test.runner/results completion))]
    (assoc completion :seon.test.runner/results results :seon.blob/staged-writes @staged)))

(defn- failure-replacement-tx [database test-row-id failures run-ref at]
  (let [previous (when-not (string? test-row-id)
                   (db/pull database '[{:seon.test/failures [*]}] test-row-id))
        previous-by-id (into {} (map (juxt :seon.test.failure/id identity)) (:seon.test/failures previous))
        retained (set (map :seon.test.failure/id failures))]
    (into
      (mapv (fn [old] [:db.fn/retractEntity (:db/id old)])
            (remove #(retained (:seon.test.failure/id %)) (:seon.test/failures previous)))
      (mapcat
        (fn [{failure-id :seon.test.failure/id :as failure}]
          (let [old (or (get previous-by-id failure-id)
                        (db/pull database '[*] [:seon.test.failure/id failure-id]))
                old-run (get-in old [:seon.test.failure/last-run :db/id])
                current-run (:db/id (db/pull database [:db/id] run-ref))
                row (assoc failure
                           :db/id (or (:db/id old) (str "test-failure:" failure-id))
                           :seon.test.failure/test test-row-id
                           :seon.test.failure/first-run (or (get-in old [:seon.test.failure/first-run :db/id]) run-ref)
                           :seon.test.failure/last-run run-ref
                           :seon.test.failure/seen-count (+ (get old :seon.test.failure/seen-count 0)
                                                          (if (and current-run (= old-run current-run)) 0 1))
                           :seon.test.failure/last-seen-at at)]
            (concat
              ;; Retract ONLY what the new row does not assert. A
              ;; cardinality-one add replaces its own value and an identical
              ;; add emits no datom at all, so retracting every attribute
              ;; first turned an unchanged re-record into pure churn.
              (for [attribute (keys (dissoc old :db/id :seon.test.failure/id))
                    :when (not (contains? row attribute))]
                [:db.fn/retractAttribute (:db/id old) attribute])
              ;; The one cardinality-many failure attribute changes member by
              ;; member; only the members the new row drops are retracted.
              (let [wanted (set (:seon.test.failure/contexts row))]
                (for [context (:seon.test.failure/contexts old)
                      :when (not (contains? wanted context))]
                  [:db/retract (:db/id old) :seon.test.failure/contexts context]))
              [row]))) failures))))

(defn complete-members
  "Accept immutable member outcomes for an admitted run or its exact writer claim.

  Called by record-tx after the existing blob preparation. Only failing
  outcomes produce reports, keyed by the captured content signature. Program
  rows are never created. A later observation of termination may add its
  transaction without changing the recorded outcome."
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
                      (report-row report-attributes test-symbol
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
                 (conj (cond-> (assoc outcome :db/id eid
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
  [database
   {results :seon.test.runner/results
    run :seon.test.run/provenance
    tested-database :seon.db/db
    destination :seon.test.run/branch
    reach-unknown :seon.test/reach-unknown
    carried-digests :seon.test/reach-digests
    carried-reaches :seon.test/reaches}]
  (let [tested-branch (or (:seon.test.run/tested-branch run) (:seon.test.run/branch run))
        tested (or tested-database
                   (when (= tested-branch (get-in database [:config :branch]))
                     (db/as-of database (:seon.test.run/basis-t run))))
        destination (or destination (get-in database [:config :branch]))
        run (cond-> (assoc run :seon.test.run/branch destination)
              (not= tested-branch destination) (assoc :seon.test.run/tested-branch tested-branch))
        digests (or carried-digests
                    (when tested (reach-digests tested (mapv :seon.test/sym results))))
        derived-reaches (when tested (reach-memberships tested (mapv :seon.test/sym results)))
        reaches (if (and (map? derived-reaches) (contains? derived-reaches :seon.error/at) (contains? derived-reaches :seon.error/layer) (contains? derived-reaches :seon.error/operation))
                  (or carried-reaches derived-reaches)
                  (merge derived-reaches carried-reaches))
        run-id (:seon.test.run/id run)
        basis-t (:seon.test.run/basis-t run)
        at (:seon.test.run/at run)
        run-ref [:seon.test.run/id run-id]
        current-by-symbol
        (into {}
              (keep (fn [{test-symbol :seon.test/sym}]
                      (when-let [row (db/pull database
                                              [:db/id
                                               :seon.test/reach-digest
                                               :seon.test/reach-unknown
                                               :seon.test/failure-message
                                               :seon.test/failing-assertions
                                               '(limit :seon.test/reach nil)]
                                              [:seon.test/sym test-symbol])]
                        [test-symbol row])))
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
    (when (and (map? previous) (contains? previous :seon.error/at) (contains? previous :seon.error/layer) (contains? previous :seon.error/operation))
      (throw (ex-info (:seon.error/message previous) previous)))
    (when (and previous (not= run (dissoc previous :db/id)))
      (let [failure (assoc (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/recording 'seon.test.runner/record-tx
                            {:seon.error/message "A test run's provenance is immutable."
                      :seon.test.run/id run-id
                      :seon.test.run/immutable run-id
                      :seon.error/expected (dissoc previous :db/id)
                      :seon.error/offending run
                      :seon.error/data {:seon.test.run/id run-id
                       :seon.db/basis-t (db/basis-t database)}}) :seon.test.run/immutable run-id)]
        (throw (ex-info (:seon.error/message failure) failure))))
    (let [missing (into [] (comp (map :seon.test/sym) (remove current-by-symbol)) results)]
      (when (seq missing)
        (throw (ex-info "Test completion has no surviving test definition."
                        {:seon.test/symbols missing}))))
    (into [(assoc run :db/id "test-run")]
     (mapcat
      (fn [{test-symbol :seon.test/sym :as result}]
        (let [test-ref [:seon.test/sym test-symbol]
              current (get current-by-symbol test-symbol)
              exists? (some? current)
              test-row-id test-ref
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
              wanted-assertions (set (:seon.test/failing-assertions result))
              result-row
              (cond-> (assoc (dissoc result :seon.test/failures :seon.test.run/terminated?)
                             :db/id test-row-id
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

            exists?
            (into (comp (remove wanted-assertions)
                        (map (fn [assertion]
                               [:db/retract test-ref :seon.test/failing-assertions
                                assertion])))
                  (:seon.test/failing-assertions current))

            exists? (into retracted-members)
            true (conj result-row))
            (failure-replacement-tx database test-row-id failures run-ref at))))
      results))))

(defn record-tx
  "Record one completion at the writer, using its admitted claim when present.

  Admitted runs complete members without recreating program rows. Legacy
  unadmitted runs retain the existing latest-result replacement until their
  callers migrate to pre-execution admission."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/completion]
                  :seon.test.runner/record-tx]}
  [database completion]
  (let [run-id (get-in completion [:seon.test.run/provenance :seon.test.run/id])
        row (execution-read
             (db/pull database [:seon.test.run/selection-tx] [:seon.test.run/id run-id]))]
    (if (or (:seon.test.run/selection-tx row)
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
  "The recorded results of `test-symbols` in one admitted `run`, by symbol:
  one member read, one report query and one report pull for the whole set."
  {:malli/schema [:=> [:cat :seon.db/database-value :map [:sequential :seon.test/sym]]
                  [:map-of :seon.test/sym :seon.test/result]]}
  [database run test-symbols]
  (let [members (execution-members database (:seon.test.run/id run) (mapv symbol test-symbols))
        report-rows (execution-read
                     (db/q '[:find ?member ?report :in $ [?member ...]
                             :where [?member :seon.test.member/failures ?report]]
                           database (mapv :db/id members)))
        reports (group-by first report-rows)
        pulled (zipmap (map second report-rows)
                       (execution-read (db/pull-many database '[*] (mapv second report-rows))))
        base (merge (select-keys run [:seon.test.run/program-digest :seon.test.run/basis-t
                                      :seon.test.run/published-base-digest
                                      :seon.test.run/overlay-input-digest])
                    (select-keys (execution-read
                                  (db/pull database [:seon.test.run/input-digest]
                                           [:seon.test.run/id (:seon.test.run/id run)]))
                                 [:seon.test.run/input-digest]))]
    (into {}
          (map (fn [member]
                 (let [member-reports (mapv #(dissoc (get pulled (second %)) :db/id)
                                            (get reports (:db/id member)))]
                   [(:seon.test.member/symbol member)
                    (cond-> (merge base
                                   {:seon.test/sym (:seon.test.member/symbol member)
                                    :seon.test/pass-count (:seon.test.member/pass-count member)
                                    :seon.test/fail-count (:seon.test.member/fail-count member)
                                    :seon.test/error-count (:seon.test.member/error-count member)
                                    :seon.test/run-basis-t (:seon.test.run/basis-t run)
                                    :seon.test/run-at (:seon.test.run/at run)
                                    :seon.test/run [:seon.test.run/id (:seon.test.run/id run)]
                                    :seon.test.member/completed-tx (:seon.test.member/completed-tx member)}
                                   (select-keys member [:seon.test.member/reach-digest]))
                      (seq member-reports)
                      (assoc :seon.test.failure/reports member-reports
                             :seon.test/failure-message
                             (str/join "\n\n" (map (requiring-resolve 'seon.test/failure-text) member-reports))))])))
          members)))

(defn commit-results!
  "Commit captured test results and return those exact committed facts."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.test.run/completion]
    [:or :seon.test/results :seon.db/error-result]]}
  [connection {results :seon.test.runner/results :as completion}]
  (let [database (db/db connection)
        completion (prepare-failures! connection database completion)
        tested (:seon.db/db completion)
        completion (if tested
                     (assoc (dissoc completion :seon.db/db)
                            :seon.test/reach-digests
                            (reach-digests tested (mapv :seon.test/sym results))
                            :seon.test/reaches
                            (reach-memberships tested (mapv :seon.test/sym results)))
                     completion)
        transaction-report
        (if (and (map? database) (contains? database :seon.error/at) (contains? database :seon.error/layer) (contains? database :seon.error/operation))
          database
          (blob/with-publication! connection (:seon.blob/staged-writes completion)
            #(db/transact! connection
                           [[:db.fn/call #'record-tx (dissoc completion :seon.blob/staged-writes)]]))) ]
    (if (and (map? transaction-report) (contains? transaction-report :seon.error/at) (contains? transaction-report :seon.error/layer) (contains? transaction-report :seon.error/operation))
      transaction-report
      (let [after (:db-after transaction-report)
            admitted? (:seon.test.run/selection-tx
                       (db/pull after [:seon.test.run/selection-tx]
                                [:seon.test.run/id (get-in completion [:seon.test.run/provenance :seon.test.run/id])]))
            by-symbol (when admitted?
                        (recorded-member-results after (:seon.test.run/provenance completion)
                                                 (mapv :seon.test/sym results)))
            recorded (mapv (fn [{test-symbol :seon.test/sym}]
                             (if admitted?
                               (get by-symbol (symbol test-symbol))
                               (dissoc (db/pull after result-selector [:seon.test/sym test-symbol]) :db/id)))
                           results)]
        (or (first (filter :seon.error/at recorded)) recorded)))))

(defn record-interrupted!
  "Give every admitted member of `completion`'s run that has no outcome a
  terminal error naming `failure`, the throwable that ended its request.

  An interrupted member is an obligation, never green: it is recorded red with
  the whole cause chain, so the run's evidence is complete and the member is
  selected again. Members already recorded are left as they are."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.test.run/completion :seon.error/throwable]
                  [:or :seon.test/results :seon.db/error-result]]}
  [connection completion ^Throwable failure]
  (let [run-id (get-in completion [:seon.test.run/provenance :seon.test.run/id])
        open (remove :seon.test.member/completed-tx
                     (execution-members (db/db connection) run-id))
        options (report-options {})
        cause (str/join "\nCaused by: "
                        (for [link (take-while some? (iterate ex-cause failure))]
                          (str (throwable-text options link)
                               (when-let [data (ex-data link)]
                                 (str "\n    data " (printable options data))))))
        message (str "Interrupted: request " run-id " threw before this member finished.\n" cause)]
    (if (empty? open)
      []
      (commit-results!
       connection
       (assoc completion
              :seon.test.run/terminated? true
              :seon.test.runner/results
              (mapv (fn [member]
                      {:seon.test/sym (:seon.test.member/symbol member)
                       :seon.test.member/began? false :seon.test.member/ended? false
                       :seon.test/pass-count 0 :seon.test/fail-count 0 :seon.test/error-count 1
                       :seon.test/failure-message message})
                    open))))))

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
  [run-id {expected :seon.test.run/expected rows :seon.test.run/rows}]
  (when-not (and (= expected (set (map first rows))) (= (count expected) (count rows)))
    (execution-refusal! 'seon.test.runner/run-results run-id
                        :seon.test/population-unknown expected rows))
  (mapv (fn [[sym pass fail errors owner at basis program inputs]]
          (cond-> {:seon.test/sym sym :seon.test/pass-count pass
                   :seon.test/fail-count fail :seon.test/error-count errors
                   :seon.test/run [:seon.test.run/id owner]
                   :seon.test/run-at at :seon.test/run-basis-t basis
                   :seon.test.run/basis-t basis :seon.test.run/program-digest program
                   :seon.test.run/input-digest inputs}
            (not= run-id owner) (assoc :seon.test/unchanged true))) rows))

(defn- result-read-error
  {:malli/schema [:=> [:cat :seon.test.run/id :seon.error/throwable]
                  :seon.test/execution-error]}
  [run-id failure]
  (if (:seon.test/execution-refusal (ex-data failure))
    (ex-data failure)
    (assoc (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/recording 'seon.test.runner/run-results
            {:seon.error/message "Recorded run coverage is unavailable."
             :seon.error/expected :complete-recorded-membership
             :seon.error/offending (Throwable->map failure)
             :seon.error/data {:seon.test.run/id run-id}})
           :seon.test/execution-refusal :seon.test/population-unknown)))

(defn run-results
  "Query the total recorded results of an admitted run, including reused members."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/id]
                  [:or :seon.test/results :seon.test/execution-error]]}
  [database run-id]
  (try (results-from-facts run-id (run-result-facts database run-id))
       (catch Exception failure (result-read-error run-id failure))))

(defn latest-results
  "Read the latest native admitted execution of each requested test.
  Missing executions are absent; incomplete latest executions refuse rather
  than exposing an older green. Snapshot evidence never certifies this branch."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
                  [:or :seon.test/results :seon.test/execution-error]]}
  [database test-symbols]
  (try
    (let [rows (execution-read
                (db/q '[:find ?symbol ?id ?selected
                        :in $ [?symbol ...] ?branch
                        :where [?member :seon.test.member/symbol ?symbol]
                               [?run :seon.test.run/members ?member]
                               [?run :seon.test.run/id ?id]
                               [?run :seon.test.run/branch ?branch]
                               [?run :seon.test.run/selection-tx ?selected]
                               (not [?run :seon.test.run/published-base-digest])]
                      database test-symbols
                      (get-in (db/schema-database database) [:config :branch])))
          latest (vals (reduce (fn [result [sym :as row]] (assoc result sym row))
                               (sorted-map) (sort-by #(nth % 2) rows)))]
      ;; One read per distinct run, never one per test.
      (->> (group-by second latest)
           (mapcat (fn [[run-id rows]]
                      (let [run (execution-read
                                 (db/pull database
                                          [:seon.test.run/id :seon.test.run/at
                                           :seon.test.run/basis-t :seon.test.run/program-digest]
                                          [:seon.test.run/id run-id]))
                            results (recorded-member-results database run (mapv first rows))]
                        (for [[sym] rows
                              :let [result (get results sym)]]
                          (if (and (:seon.test.member/completed-tx result)
                                   (every? integer? (map result [:seon.test/pass-count
                                                                :seon.test/fail-count
                                                                :seon.test/error-count])))
                            result
                            (execution-refusal! 'seon.test.runner/latest-results run-id
                                                :seon.test/population-unknown :completed-member sym))))))
           (sort-by :seon.test/sym)
           vec))
    (catch Exception failure (result-read-error "latest-results" failure))))
