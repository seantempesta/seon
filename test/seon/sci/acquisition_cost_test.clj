(ns seon.sci.acquisition-cost-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.render.value :as value]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]
            [start-flow-benchmark :as benchmark]))

(defn- measured
  "Measure one operation and optionally write its cost to the supplied evidence path."
  {:malli/schema [:=> [:cat :qualified-keyword :seon.profile/thunk] :seon.profile/measured-call]}
  [operation f]
  (let [measurement (profile/measure f)]
    (when-let [path (System/getenv "SEON_ACQUISITION_MEASUREMENTS")]
      (spit path
            (str (value/core-text
                  (assoc (select-keys measurement [:seon.profile/ms :seon.profile/alloc-bytes])
                         ::operation operation
                         :seon.profile/called
                         (select-keys (:seon.profile/called measurement)
                                      '[seon.sci.eval/copy-host-var seon.sci.eval/function-rows
                                        seon.sci.eval/function-digests seon.sci.eval/loaded-digests
                                        seon.sci.eval/behavior-changed? seon.sci.eval/loaded-database
                                        seon.sci.eval/install-function-contract!
                                        seon.sci.eval/interpret-overlay! seon.sci.eval/base-ctx
                                        seon.db/q seon.db/pull seon.db/pull-many seon.db/datoms
                                        seon.schema/contract-references seon.schema.edn/read-schema-resource])
                         :seon.profile/ranked-by-self (vec (take 8 (:seon.profile/ranked-by-self measurement))))) "\n")
            :append true))
    measurement))

(deftest ^{:seon.test/platform "Empties the central cache in the disposable check JVM."}
  function-acquisition-preserves-the-loaded-declarations
  (support/with-database
   (fn [connection]
     (let [loaded (#'evaluation/loaded-program (db/db connection))
           _ (benchmark/empty-cache!)
           acquired (measured ::function-rows #(#'evaluation/function-rows loaded))
           expected (db/q (::benchmark/function-rows benchmark/base-queries) loaded)]
       (is (= (set expected)
              (into #{} (map (fn [[sym source _ admission private?]]
                              [sym source admission private?]))
                    (:seon.profile/value acquired))))
       (is (zero? (profile/calls-of acquired 'seon.db/q)))
       (is (zero? (profile/calls-of acquired 'seon.db/pull-many)))
       (is (= 4 (profile/calls-of acquired 'seon.db/datoms)))))))

(deftest base-namespace-acquisition-preserves-declarations-and-bindings
  (support/with-database
   (fn [connection]
     (let [loaded (#'evaluation/loaded-program (db/db connection))
           names (db/q (::benchmark/namespace-names benchmark/base-queries) loaded)
           assertions (db/q (::benchmark/namespace-assertions benchmark/base-queries) loaded)
           acquired (measured ::namespace-rows #(#'evaluation/base-namespace-rows loaded names assertions))
           expected (db/pull-many loaded @#'evaluation/namespace-pattern
                                  (mapv #(vector :seon.ns/name %) names))
           meaning (fn [row] [(:seon.ns/source row)
                              (when (:seon.ns/source row) (:seon.schema.admission/source row))
                              (#'evaluation/row-bindings row)])]
       (is (= (into {} (map (juxt :seon.ns/name meaning)) expected)
              (update-vals (:seon.profile/value acquired) meaning)))
       (is (zero? (profile/calls-of acquired 'seon.db/pull-many)))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-cold-base-derivation-is-proportional-to-declarations}
           :seon.test/platform "Empties the central cache in the disposable check JVM."}
  a-cold-base-derivation-is-proportional-to-declarations
  (support/with-database
   (fn [connection]
     (let [loaded (#'evaluation/loaded-program (db/db connection))]
       (benchmark/empty-cache!)
       (let [cold (measured ::cold-base #(#'evaluation/derive-base-ctx loaded))]
         (is (evaluation/program-context? (:seon.profile/value cold)))
         (is (< (:seon.profile/ms cold) 300))
         (is (< (:seon.profile/alloc-bytes cold) 300000000))
         (is (zero? (profile/calls-of cold 'seon.sci.eval/function-digests)))
         (is (zero? (profile/calls-of cold 'seon.sci.eval/behavior-changed?)))
         (is (zero? (profile/calls-of cold 'seon.sci.eval/loaded-database)))
         (is (zero? (profile/calls-of cold 'seon.sci.eval/install-function-contract!)))
         (is (zero? (profile/calls-of cold 'seon.schema/contract-references))))))))

(deftest ^{:seon/tasks #{'seon.config/a-config-read-rebuilds-the-packaged-projection}
           :seon.test/platform "Empties the central cache in the disposable check JVM."}
  a-config-read-reuses-its-carried-projection
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           cluster (db/q '[:find ?name . :where [_ :seon.config/cluster ?name]] database)]
       (benchmark/empty-cache!)
       (let [cold (measured ::cold-config #(config/effective database cluster))]
         (is (map? (:seon.profile/value cold)))
         (is (not (:seon.error/type (:seon.profile/value cold))))
         (is (zero? (profile/calls-of cold 'seon.schema/contract-references)))
         (is (zero? (profile/calls-of cold 'seon.schema.edn/read-schema-resource))))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-second-acquisition-of-an-unmoved-branch-interprets-nothing}}
  a-second-acquisition-of-an-unmoved-branch-interprets-nothing
  (support/with-database
   (fn [connection]
     (support/transacted!
      connection
      (support/replacement-tx
       (db/db connection) [:seon.fn/sym 'seon.sci.eval-test/cut?]
       "(defn cut? {:malli/schema [:=> [:cat :map] :boolean]} [evaluation] true)"
       {:seon.schema.admission/source :agent}))
     (let [database (db/db connection)
           first-ctx (:seon.profile/value (measured ::overlay-first #(evaluation/base-ctx database)))
           warm (measured ::overlay-warm #(evaluation/base-ctx database))
           second-ctx (:seon.profile/value warm)]
       (is (true? (support/agent-value first-ctx "(seon.sci.eval-test/cut? {})")))
       (is (true? (support/agent-value second-ctx "(seon.sci.eval-test/cut? {})")))
       (is (zero? (profile/calls-of warm 'seon.sci.eval/interpret-overlay!)))
       (is (< (:seon.profile/ms warm) 1000))
       (is (< (:seon.profile/alloc-bytes warm) 300000000))
       (sci/eval-string* first-ctx "(def private-overlay-value 42)")
       (is (nil? (sci/resolve second-ctx 'user/private-overlay-value)))
       (is (not (identical? (:seon.sci.kernel/program-snapshot first-ctx)
                            (:seon.sci.kernel/program-snapshot second-ctx))))
       (is (not (identical? (:seon.sci.kernel/installed-functions first-ctx)
                            (:seon.sci.kernel/installed-functions second-ctx))))
       (sci/eval-string* first-ctx "(in-ns 'seon.sci.eval-test) (defn cut? [evaluation] false)")
       (is (true? (support/agent-value second-ctx "(seon.sci.eval-test/cut? {})")))
       (let [branch :astra-r4-successor]
         (d/branch! connection (db/commit-id database) branch)
         (try
           (let [successor (d/connect (assoc (:config database) :branch branch))]
             (try
               (db/carry-connection-projection-state!
                successor (evaluation/projection-state @successor (db/carried-projection database)))
               (let [resumed (measured ::overlay-successor #(evaluation/base-ctx (db/db successor)))]
                 (is (zero? (profile/calls-of resumed 'seon.sci.eval/interpret-overlay!)))
                 (is (true? (support/agent-value (:seon.profile/value resumed)
                                                "(seon.sci.eval-test/cut? {})"))))
               (finally (d/release successor))))
           (finally (d/delete-branch! connection branch))))
       (support/transacted! connection [(support/task-row "overlayforks" "Advance only data.")])
       (let [again (measured ::overlay-data-write #(evaluation/base-ctx (db/db connection)))]
         (is (zero? (profile/calls-of again 'seon.sci.eval/interpret-overlay!)))
         (is (true? (support/agent-value (:seon.profile/value again)
                                        "(seon.sci.eval-test/cut? {})"))))))))

(deftest the-interpret-set-is-derived-once-per-commit-pair
  ;; The interpret set I(B, L) is a function of the branch's program and the
  ;; loaded commit alone (branch-model ruling 2). Before, a work branch's
  ;; overlay plan was also keyed by main's program revisions, so every program
  ;; write main made after the fork derived the branch's plan again.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           store (:seon.store/store (support/execution-handle connection))
           branch (keyword (str "interpret-pair-" (seon.id/id)))
           request {:seon.store/store store :seon.store/branch branch
                    :seon.cluster.registry/from (db/commit-id database) :seon.db/db database}
           loaded (#'evaluation/loaded-program database)
           plan #(#'evaluation/overlay-plan (db/db %) loaded)
           derived #(profile/calls-of % 'seon.sci.eval/derive-overlay-plan)]
       (is (:seon.cluster/created? (registry/fork! request)))
       (try
         (let [fork (store/open-branch! store branch)]
           (try
             (support/transacted!
              fork (support/replacement-tx (db/db fork) [:seon.fn/sym 'seon.sci.eval-test/paired?]
                                           "(defn paired? [] true)" {:seon.schema.admission/source :agent}))
             (let [parent (measured ::interpret-set-first #(plan fork))
                   again (measured ::interpret-set-again #(plan fork))
                   _ (support/transacted! fork [(support/task-row "interpairone" "Advance only data.")])
                   result-only (measured ::interpret-set-result-only #(plan fork))
                   _ (support/transacted!
                      connection (support/replacement-tx (db/db connection) [:seon.fn/sym 'seon.sci.eval-test/main-moved?]
                                                         "(defn main-moved? [] true)" {:seon.schema.admission/source :agent}))
                   main-moved (measured ::interpret-set-after-main-moved #(plan fork))]
               (is (= 1 (derived parent)) "the first request derives the branch's interpret set")
               (is (contains? (:seon.sci.eval/overridden (:seon.profile/value parent)) 'seon.sci.eval-test/paired?)
                   "the branch's own definition")
               (doseq [[label change] {"a second request" again "a result-only commit" result-only
                                       "a program commit on main" main-moved}]
                 (is (zero? (derived change)) (str label " derives nothing"))
                 (is (< (:seon.profile/alloc-bytes change) (:seon.profile/alloc-bytes parent))
                     (str label " allocates less than the derivation")))
               (is (= (:seon.profile/value parent) (:seon.profile/value main-moved))
                   "main's later program does not change the branch's interpret set"))
             (finally (store/release-branch! fork))))
         (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch]))))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/install-row-is-twice-as-slow-after-the-stale-var-fix}}
  a-core-row-installs-without-opening-the-loaded-program
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           row (db/pull database '[*] [:seon.fn/sym 'seon.id/valid?])
           measurement (measured ::install
                        #(evaluation/install-row! {:seon.sci.eval/ctx ctx :seon.db/db database
                                                   :seon.program/row row}))]
       (is (= 1 (:seon.sci.eval/installed (:seon.profile/value measurement))))
       (is (zero? (profile/calls-of measurement 'seon.sci.eval/loaded-database)))))))

(deftest
  a-data-only-commit-reuses-the-program-overlay
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           _ (evaluation/base-ctx database)
           _ (support/transacted! connection [(support/task-row "overlayreuse" "Advance only data.")])
           measurement (measured ::fork #(evaluation/base-ctx (db/db connection)))]
       (is (evaluation/ctx? (:seon.profile/value measurement)))
       (is (zero? (profile/calls-of measurement 'seon.sci.eval/copy-host-var)))
       (is (zero? (profile/calls-of measurement 'seon.sci.eval/authored-rows)))))))


(deftest a-branch-head-read-costs-one-primary-materialization
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           branch (get-in database [:config :branch])
           commit (db/commit-id database)
           open #(d/commit-as-db database commit {:secondary-indices? false})
           _ (open)
           _ (db/branch-database database branch)
           direct (measured ::commit open)
           head (measured ::branch #(db/branch-database database branch))]
       (is (= commit (db/commit-id (:seon.profile/value head))))
       (is (< (:seon.profile/alloc-bytes head)
              (* 1.5 (:seon.profile/alloc-bytes direct)))
           "reading the head must not materialize and discard a second database")))))

(deftest a-change-reinterprets-only-its-dependent-declarations
  (support/with-running-agent
   (fn [running agent-id _connection]
     (let [request (fn [code] (json/write-str {"name" "eval_clj"
                                                "arguments" {"code" code "agent" agent-id "cluster" running}}))]
       (mcp/call (request (str "(ns probe.cost)"
                               " (defn f1 [] 1) (defn g1 [] (+ 100 (f1))) (def v1 (f1)) (def v4 (g1))"
                               " (defn h1 [] 3) (defn h2 [] (h1)) (defn h3 [x] (* x 2)) (def u1 10)")))
       (mcp/call (request "(+ 1 2)"))
       (let [measured (measured ::unrelated-defn #(json/read-str (mcp/call (request "(defn other [] 2)"))))
             reply (:seon.profile/value measured)
             cost (support/mcp-request reply)
             {:keys [derive test-step]} (:seon.cost/stages cost)]
         (is (false? (get reply "isError")))
         (is (<= (profile/calls-of measured 'seon.sci.eval/install-row!) 2)
             "an unrelated definition interprets itself, not the branch's other rows")
         ;; parent 06bbba850: derive 17.9 + test-step 88.5 ms, step 153.4 MB
         (is (< (+ (or derive 0.0) (or test-step 0.0)) 100.0)
             "the derivation and test step cost less than the whole-plan derivation's")
         (is (< (get-in cost [:seon.cost/step :seon.cost/alloc-mb]) 145.0)
             "the request allocates less than the whole-plan derivation's"))))))

(deftest an-unrelated-defn-leaves-a-value-rows-refusal-count-unchanged
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [request (fn [code] (json/write-str {"name" "eval_clj"
                                                "arguments" {"code" code "agent" agent-id "cluster" running}}))
           counted #(or (db/q '[:find (sum ?count) . :with ?occurrence
                                :where [?occurrence :seon.error.occurrence/message ?message]
                                [(clojure.string/includes? ?message "probe.once/L2")]
                                [?occurrence :seon.error.occurrence/count ?count]]
                              (db/db connection))
                        0)]
       (mcp/call (request (str "(ns probe.once) (def d (seon.db/db))"
                               " (def L2 (seon.ref/brief d 'seon.edit/single-form))")))
       (mcp/call (request "(+ 1 2)"))
       (let [before (counted)]
         (is (pos? before) "the value row's refusal is recorded")
         (mcp/call (request "(defn other [] 2)"))
         (mcp/call (request "(+ 1 3)"))
         (is (= before (counted))
             "an unrelated definition records no refusal of a row it does not change"))))))

(deftest ^{:seon.test/long "Eight MCP eval requests on main and one agent branch, its first acquiring the branch's context."
           :seon.test/long-ms 10000}
  a-predicate-transition-re-arms-once
  ;; main and a branch holding no predicate apart; then `takes` armed, its
  ;; registered predicate redefined on the branch (the transition), redefined
  ;; again, and a request after
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [agent-name code]
                  (support/mcp-reply (json/read-str (mcp/call (json/write-str
                                                               {"name" "eval_clj"
                                                                "arguments" {"cluster" running "agent" agent-name "code" code}})))))
           predicate (fn [value]
                       (str "(in-ns 'seon.edit)
                             (defn valid-form-operation? {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason \"A total predicate accepts arbitrary objects.\", :gen/elements [nil {}]}]] :boolean]} [request] " value ")"))
           arms #(profile/calls-of % 'seon.sci.eval/arm-contract!)
           recompiles #(profile/calls-of % 'seon.schema/projection-with-declarations)]
       (call "root" "(+ 1 1)")
       (call agent-id "(+ 1 1)")
       (let [main (measured ::main-request #(call "root" "(+ 1 1)"))
             branch (measured ::branch-request #(call agent-id "(+ 1 1)"))
             _ (call agent-id "(ns bp.transition)
                               (defn takes {:malli/schema [:=> [:cat :my.edit/form-request] :boolean]} [request] true)")
             transition (measured ::predicate-transition #(call agent-id (predicate "false")))
             later (measured ::predicate-redefinition #(call agent-id (predicate "(nil? request)")))
             after (measured ::after-predicate-change #(call agent-id "(+ 1 1)"))]
         (is (= 2 (:seon.repl/value (:seon.profile/value main))))
         (is (zero? (recompiles main)) "main binds every predicate as compiled")
         (is (zero? (recompiles branch)) "a branch holding no predicate apart recompiles nothing")
         (is (pos? (recompiles transition)) "the transition binds the branch's predicate")
         (is (< (arms later) (arms transition)) "a later redefinition re-arms no dependent")
         (is (zero? (recompiles after)) "a held-apart binding needs no recompile"))))))
