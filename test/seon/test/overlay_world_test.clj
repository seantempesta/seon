(ns seon.test.overlay-world-test
  "A request made on an agent branch runs on the loaded published program plus
  that branch's differing program rows, never on a fork of the branch: the
  branch's operational rows and live default's agents stay out, a retracted
  declaration is absent and a changed callee runs its candidate definition.
  The world is a checkpoint prepared once and read only when complete."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.registry :as registry]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id :as id]
            [seon.program :as program]
            [seon.sci.eval :as sci.eval]
            [seon.test :as sut]
            [seon.test-support :as support]))

(def ^:private probe-ns 'seon.id)

(def ^:private sentinel ::sentinel)

(def ^:private victim 'seon.test.one-request-test/a-member-body-runs-on-its-own-listed-branch)

(defn- admit!
  "Evaluate one form as an agent does and write its canonical declaration row."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :string] [:maybe :seon.db/transaction-report]]}
  [handle source]
  (let [connection (:seon.db/connection handle)
        evaluation (support/await-event!
                    (future
                     (db/call-with-custody
                      {:seon.db/connection connection}
                      #(sci.eval/evaluate
                        {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :seon.cluster.eval/source source
                         :seon.cluster.eval/ns [:seon.ns/name probe-ns]
                         :seon.sci.admit/caps (config/result-caps config/defaults)
                         :seon.sci.eval/time-limit-ms 5000})))
                    ::admitted)]
    (is (nil? (:seon.cluster.eval/error evaluation)) (str (:seon.cluster.eval/error evaluation)))
    (when-let [row (:seon.program/row evaluation)]
      (support/transacted! connection [(program/declaration-row
                                        (db/carried-projection (db/db connection))
                                        row :all :agent)]))))

(deftest ^{:seon.test/long "Four agent definitions, one retraction and one nested request whose first checkpoint preparation measured 1.8 s (seon.config/apply-compiled!, issue test-world-preparation-spends-seconds-in-config-apply-and-unmemoized-reads): the request measured 7.1 s on default's JVM (root run 99a231f41221, 2026-09-24)."
           :seon.test/long-ms 15000}
  an-agent-branch-request-runs-its-candidate-on-a-clean-world
  (support/with-database
   {:seon.test-support/extra-schema (support/file-store-probe-schema sentinel)}
   (fn [connection]
     (let [handle (support/execution-handle connection)]
       (support/transacted! connection [{sentinel "operational"}])
       (support/transacted! connection [[:db/retractEntity [:seon.test/sym victim]]])
       (admit! handle "(defn overlay-callee {:malli/schema [:=> [:cat] :int]} [] 1)")
       (admit! handle "(defn overlay-caller {:malli/schema [:=> [:cat] :int]} [] (overlay-callee))")
       (admit! handle "(defn overlay-callee {:malli/schema [:=> [:cat] :int]} [] 2)")
       (admit! handle
               (str "(clojure.test/deftest overlay-candidate-runs"
                    " (clojure.test/is (= 2 (overlay-caller)))"
                    ;; The candidate's attributes belong in its world (root
                    ;; ruling); its branch's operational datum does not.
                    " (clojure.test/is (nil? (seon.db/q '[:find ?v . :where [_ " sentinel " ?v]] (seon.db/db))))"
                    " (clojure.test/is (empty? (seon.db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]] (seon.db/db))))"
                    " (clojure.test/is (nil? (seon.db/q '[:find ?e . :in $ ?s :where [?e :seon.test/sym ?s]]"
                    " (seon.db/db) '" victim "))))"))
       (let [result (sut/run {:seon.test/execution (support/execution-handle connection)
                              :seon.test/recording-connection connection
                              :seon.test/policy :named
                              :seon.test/identities #{(symbol (str probe-ns) "overlay-candidate-runs")}})]
         (is (true? (:seon.test/passed? result)) (sut/tally result))
         (is (= 1 (:seon.test/executed-count result)) (sut/tally result)))))))

(def ^:private clean-request-handle #'sut/clean-request-handle)
(def ^:private checkpoint-head #'sut/checkpoint-head)
(def ^:private prepare-checkpoint! #'sut/prepare-checkpoint!)

(deftest ^{:seon.test/long "Two concurrent first preparations and acquisitions on a fixture branch: a first preparation measured 1.7-1.8 s (config apply) and a request 7.1 s on default's JVM (root run 99a231f41221, 2026-09-24)."
           :seon.test/long-ms 15000}
  concurrent-first-preparation-yields-one-complete-checkpoint
  (support/with-database
   (fn [connection]
     (let [handle (support/execution-handle connection)
           store (:seon.store/store handle)
           ;; A cluster of its own gives this checkpoint a new identity.
           cluster (str "concurrent-" (id/id))
           base (:seon.source/commit-id (db/pull (db/db connection) [:seon.source/commit-id]
                                                 [:seon.cluster/name (:seon.cluster/name handle)]))
           _ (support/seed-cluster! connection cluster)
           _ (support/transacted! connection [{:seon.cluster/name cluster :seon.source/commit-id base}])
           effective (config/effective (db/db connection) cluster)
           prepare #(future (try (clean-request-handle handle connection cluster effective)
                                 (catch clojure.lang.ExceptionInfo refusal refusal)))
           outcomes (mapv #(support/await-event! % ::prepared) [(prepare) (prepare)])
           handles (filterv :seon.db/connection outcomes)
           ;; Handles hold whole worlds: report only their kind, never print them.
           summary (mapv #(if (:seon.db/connection %) :handle (ex-message %)) outcomes)
           checkpoints (filterv #(str/starts-with? (name %) (str "checkpoint-" cluster "-"))
                                (registry/roster store))]
       (try
         (is (seq handles) (pr-str summary))
         (is (= 1 (count checkpoints)) (pr-str checkpoints))
         (is (some? (checkpoint-head store (first checkpoints) cluster base)) "the one checkpoint is complete")
         (is (every? #(or (:seon.db/connection %)
                          (= :seon.test/prepared-checkpoint (:seon.error/expected (ex-data %))))
                     outcomes)
             (str "the other caller reuses the complete checkpoint or is pushed back by name: " (pr-str summary)))
         (finally
           (run! agent/release-context! handles)
           (run! #(registry/retire-branch! {:seon.store/store store :seon.store/branch %}) checkpoints)))))))

(deftest a-checkpoint-without-its-source-record-is-never-read
  (let [member (support/execution-handle nil)
        store (:seon.store/store member)
        cluster (:seon.cluster/name member)
        candidate (db/db (:seon.db/connection member))
        base (:seon.source/commit-id (db/pull candidate [:seon.source/commit-id] [:seon.cluster/name cluster]))
        branch (keyword (str "half-checkpoint-" (id/id)))]
    (is (:seon.cluster/created? (registry/branch! {:seon.store/store store :seon.store/branch branch
                                                   :seon.cluster.registry/from base})))
    (try
      (is (nil? (checkpoint-head store branch cluster base)) "a created, unprepared branch is not ready")
      (is (nil? (prepare-checkpoint! store branch cluster (config/effective candidate cluster)
                                     (cluster.process/id (cluster.process/current-identity)) base candidate)))
      (is (= (registry/branch-commit-id {:seon.store/store store :seon.store/branch branch})
             (checkpoint-head store branch cluster base))
          "the source record, written last, completes it")
      (finally (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))
