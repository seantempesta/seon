(ns seon.sci.acquisition-cost-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.render.value :as value]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

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
                                      '[seon.sci.eval/copy-host-var seon.sci.eval/authored-rows
                                        seon.sci.eval/loaded-database seon.sci.eval/overlay-plan
                                        seon.sci.eval/install-row! seon.sci.eval/base-ctx
                                        seon.sci.eval/interpret-overlay!]))) "\n")
            :append true))
    measurement))

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
