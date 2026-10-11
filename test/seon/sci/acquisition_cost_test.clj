(ns seon.sci.acquisition-cost-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
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

(deftest function-acquisition-preserves-the-loaded-declarations
  (support/with-database
   (fn [connection]
     (let [loaded (#'evaluation/loaded-program (db/db connection))
           expected (db/q (::benchmark/function-rows benchmark/base-queries) loaded)
           acquired (measured ::function-rows #(#'evaluation/function-rows loaded))]
       (is (= (set expected)
              (into #{} (map (fn [[sym source _ admission private?]]
                              [sym source admission private?]))
                    (:seon.profile/value acquired))))
       (is (zero? (profile/calls-of acquired 'seon.db/q)))
       (is (zero? (profile/calls-of acquired 'seon.db/pull-many)))
       (is (= 4 (profile/calls-of acquired 'seon.db/datoms)))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-cold-base-derivation-is-proportional-to-declarations}
           :seon.test/platform "Empties the central cache in the disposable check JVM."}
  a-cold-base-derivation-is-proportional-to-declarations
  (support/with-database
   (fn [connection]
     (let [loaded (#'evaluation/loaded-program (db/db connection))]
       (benchmark/empty-cache!)
       (let [cold (measured ::cold-base #(#'evaluation/derive-base-ctx loaded))]
         (is (evaluation/ctx? (:seon.profile/value cold)))
         (is (< (:seon.profile/ms cold) 300))
         (is (< (:seon.profile/alloc-bytes cold) 300000000))
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

(deftest ^{:seon/tasks #{'seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte}}
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
