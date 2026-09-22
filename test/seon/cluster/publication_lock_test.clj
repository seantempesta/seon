(ns seon.cluster.publication-lock-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.cluster.source-test :as source-test]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch]))

(deftest ^{:seon.test/fixture-observation
           "Two source publications contest current-src in a private copy of the canonical store."}
  concurrent-publication-refuses-the-stale-head-without-a-monitor-wait
  (#'source-test/with-store
    (fn [opened]
      (let [base (#'source-test/publish opened @#'source-test/digest-a)
            expected (:seon.source/commit-id base)
            entered (CountDownLatch. 1)
            release (CountDownLatch. 1)
            progress (fn [phase]
                       (when (= "publication branch head" phase)
                         (.countDown entered)
                         (test-support/await-event! release "release stale publisher")))
            first-publication
            (future
              (with-bindings {#'cluster/*source-progress!* progress}
                (#'source-test/publish opened @#'source-test/digest-b 'seon.cluster.source-test/populate-from-data!
                         {:seon.source/expected-commit-id expected
                          :seon.source.test/marker "loser"})))]
        (try
          (test-support/await-event! entered "first publisher reached head update")
          (let [second-publication
                (future
                  (#'source-test/publish opened (apply str (repeat 64 "c"))
                           'seon.cluster.source-test/populate-from-data!
                           {:seon.source/expected-commit-id expected
                            :seon.source.test/marker "winner"}))
                winner (test-support/await-event!
                         second-publication "second publisher completes while first is paused")]
            (is (true? (:seon.source/built? winner)))
            (is (= 1 (.getCount release)) "publication did not wait for the paused publisher")
            (.countDown release)
            (let [loser (test-support/await-event! first-publication "stale publisher refuses")
                  actual (:seon.source/commit-id winner)]
              (is (inst? (:seon.error/at loser)))
              (is (= expected (:seon.source/expected-commit-id loser)))
              (is (= actual (:seon.source/commit-id loser)))
              (is (= :stale-branch-head (get-in loser [:seon.error/data :type])))
              (is (= expected (:seon.error/expected loser)))
              (is (= actual (:seon.error/offending loser)))
              (is (= actual (:seon.source/commit-id (source/current opened))))
              (let [database (source/database opened actual)]
                (try
                  (is (= #{@#'source-test/digest-a "winner"}
                         (set (db/q '[:find [?marker ...]
                                      :where [_ :seon.source.test/marker ?marker]] database))))
                  (finally (d/release-materialized-db database))))
              (is (empty? (#'source-test/scratch-branches opened)))))
          (finally
            (.countDown release)
            (test-support/await-event! first-publication "first publisher exited")))))))

(deftest stale-basis-is-refused-by-the-transaction-writer
  (test-support/with-database
    (fn [connection]
      (let [basis (db/basis-t (db/db connection))
            request {:tx-data [] :datahike/expected-basis-t basis}
            first-report @(d/transact! connection request)
            second-result (try @(d/transact! connection request)
                               (catch Exception failure
                                 (some (fn [cause]
                                         (when (= :transaction/stale-basis (:error (ex-data cause)))
                                           (ex-data cause)))
                                       (take-while some? (iterate ex-cause failure)))))]
        (is (some? (:db-after first-report)))
        (is (= :transaction/stale-basis (:error second-result)))
        (is (= basis (:datahike/expected-basis-t second-result)))
        (is (= (db/basis-t (:db-after first-report))
               (:datahike/current-basis-t second-result)))))))

(deftest ^{:seon.test/fixture-observation
           "Two admitted publications contest the same guarded head update in one physical store."}
  both-admitted-publications-contest-the-guarded-update-and-return-their-own-head
  (#'source-test/with-store
    (fn [opened]
      (let [base (#'source-test/publish opened @#'source-test/digest-a)
            expected (:seon.source/commit-id base)
            arrived (CountDownLatch. 2)
            release (CountDownLatch. 1)
            progress (fn [phase]
                       (when (= "publication branch head" phase)
                         (.countDown arrived)
                         (test-support/await-event! release "release both publishers")))
            publish (fn [digest marker]
                      (future
                        (with-bindings {#'cluster/*source-progress!* progress}
                          (#'source-test/publish opened digest 'seon.cluster.source-test/populate-from-data!
                                                 {:seon.source/expected-commit-id expected
                                                  :seon.source.test/marker marker}))))
            first-publication (publish @#'source-test/digest-b "first")
            second-publication (publish (apply str (repeat 64 "c")) "second")]
        (try
          ;; Both requests passed admission and finished scratch work against
          ;; the same expected head before either reached force-branch!.
          (test-support/await-event! arrived "both publishers reached the head update")
          (.countDown release)
          (let [results [(test-support/await-event! first-publication "first publisher returned")
                         (test-support/await-event! second-publication "second publisher returned")]
                winners (filterv :seon.source/built? results)
                losers (filterv :seon.error/at results)
                head (:seon.source/commit-id (source/current opened))]
            (is (= 1 (count winners)) (pr-str results))
            (is (= 1 (count losers)) (pr-str results))
            (let [[winner] winners [loser] losers]
              (is (= head (:seon.source/commit-id winner))
                  "the winner returns the head it installed")
              (is (not= expected head))
              (is (= expected (:seon.source/expected-commit-id loser)))
              (is (= head (:seon.source/commit-id loser)))
              (is (= :stale-branch-head (get-in loser [:seon.error/data :type]))))
            (is (empty? (#'source-test/scratch-branches opened))))
          (finally
            (.countDown release)
            (test-support/await-event! first-publication "first publisher exited")
            (test-support/await-event! second-publication "second publisher exited")))))))

(deftest ^{:seon.test/fixture-observation
           "An analysis derived from a moved head is refused before scratch work."}
  publication-derived-from-a-moved-head-refuses-before-scratch-work
  (#'source-test/with-store
    (fn [opened]
      (let [base (#'source-test/publish opened @#'source-test/digest-a)
            moved (#'source-test/publish opened @#'source-test/digest-b
                                         'seon.cluster.source-test/populate-from-data!
                                         {:seon.source/expected-commit-id (:seon.source/commit-id base)
                                          :seon.source.test/marker "moved"})
            stale (#'source-test/publish opened (apply str (repeat 64 "c"))
                                         'seon.cluster.source-test/populate-from-data!
                                         {:seon.source/expected-commit-id (:seon.source/commit-id base)
                                          :seon.source.test/marker "stale"})]
        (is (= (:seon.source/commit-id base) (:seon.source/expected-commit-id stale)))
        (is (= (:seon.source/commit-id moved) (:seon.source/commit-id stale)))
        (is (= (:seon.source/commit-id moved) (:seon.source/commit-id (source/current opened))))
        (is (empty? (#'source-test/scratch-branches opened)))))))

(deftest ^{:seon.test/fixture-observation
           "The development adoption record is guarded by the store's current head."}
  adoption-record-refuses-when-the-published-head-moved
  (#'source-test/with-store
    (fn [opened]
      (let [base (#'source-test/publish opened @#'source-test/digest-a)
            moved (#'source-test/publish opened @#'source-test/digest-b
                                         'seon.cluster.source-test/populate-from-data!
                                         {:seon.source/expected-commit-id (:seon.source/commit-id base)
                                          :seon.source.test/marker "moved"})
            database (d/branch-as-db (:seon.store/connection-object opened) source/current-branch)
            cluster-ref [:seon.cluster/name "absent-cluster"]
            guard #'cluster/adoption-guard-tx]
        (try
          (is (= [] (guard database opened cluster-ref nil (:seon.source/commit-id moved))))
          (let [refusal (try (guard database opened cluster-ref nil (:seon.source/commit-id base))
                             (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
            (is (= (:seon.source/commit-id base) (:seon.source/expected-commit-id refusal)))
            (is (= (:seon.source/commit-id moved) (:seon.source/commit-id refusal))))
          (let [refusal (try (guard database opened cluster-ref (:seon.source/commit-id base)
                                    (:seon.source/commit-id moved))
                             (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
            (is (= (:seon.source/commit-id base) (:seon.source/prior-commit-id refusal))
                "a record whose starting adoption was replaced refuses"))
          (finally (d/release-materialized-db database)))))))

(deftest ^{:seon.test/fixture-observation
           "Two adoptions admitted at one head write a cluster branch of the same physical store."}
  stale-adoption-row-writes-refuse-at-the-writer
  (#'source-test/with-store
    (fn [opened]
      (let [base (#'source-test/publish opened @#'source-test/digest-a)
            h1 (:seon.source/commit-id base)
            cluster-name "adoption-contest"
            _ (registry/ensure-cluster! {:seon.store/store opened
                                         :seon.boot/cluster-name cluster-name
                                         :seon.source/commit-id h1})
            connection (store/open-branch! opened (registry/cluster-branch cluster-name))
            first-source (source/database opened h1)]
        (try
          (let [sym (db/q '[:find ?s . :where [_ :seon.fn/sym ?s]] first-source)
                adopt (fn [source-database commit]
                        (try
                          (fn/index! {:seon.db/connection connection
                                      :seon.schema/projection (:seon.schema/projection opened)
                                      :seon.source/database source-database
                                      :seon.source/previous-database (db/db connection)
                                      :seon.source/expected-head
                                      {:seon.source/branch source/current-branch
                                       :seon.source/expected-commit-id commit}
                                      :seon.reconcile/adopt-identities #{[:seon.fn/sym sym]}})
                          (catch clojure.lang.ExceptionInfo failure failure)))
                ;; Both adoptions were admitted at h1; a newer publication then
                ;; moves the head to h2 before the first one's row write.
                moved (#'source-test/publish opened @#'source-test/digest-b
                                             'seon.cluster.source-test/populate-from-data!
                                             {:seon.source/expected-commit-id h1
                                              :seon.source.test/marker "moved"})
                h2 (:seon.source/commit-id moved)
                before (db/basis-t (db/db connection))
                stale (adopt first-source h1)]
            (is (symbol? sym))
            (is (instance? clojure.lang.ExceptionInfo stale) (pr-str stale))
            (is (some #(= :stale-branch-head (:type (ex-data %)))
                      (take-while some? (iterate ex-cause stale)))
                "the refusal is the writer's stale-head refusal")
            (is (= before (db/basis-t (db/db connection)))
                "the stale adoption committed nothing")
            (let [second-source (source/database opened h2)]
              (try
                (let [current (adopt second-source h2)]
                  (is (not (instance? Throwable current)) (pr-str current))
                  (is (= (db/pull second-source '[:seon.fn/sym :seon.program/definition-digest]
                                  [:seon.fn/sym sym])
                         (db/pull (db/db connection) '[:seon.fn/sym :seon.program/definition-digest]
                                  [:seon.fn/sym sym]))
                      "the surviving row is the newer publication's"))
                (finally (d/release-materialized-db second-source)))))
          (finally
            (d/release-materialized-db first-source)
            (d/release connection)))))))

(deftest ^{:seon.test/fixture-observation
           "A schema adoption transaction on a cluster branch races a publication of the same physical store."}
  schema-adoption-refuses-at-the-writer-after-a-publication-moves-the-head
  (#'source-test/with-store
    (fn [opened]
      (let [base (#'source-test/publish opened @#'source-test/digest-a)
            h1 (:seon.source/commit-id base)
            cluster-name "schema-adoption-contest"
            _ (registry/ensure-cluster! {:seon.store/store opened
                                         :seon.boot/cluster-name cluster-name
                                         :seon.source/commit-id h1})
            connection (store/open-branch! opened (registry/cluster-branch cluster-name))
            attribute :seon.cluster.publication-lock-test/adopted
            declaration {:db/ident attribute :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one}
            adopt (fn [commit]
                    (try
                      @(d/transact! connection
                                    {:tx-data [[:db.fn/call registry/head-guard-tx
                                                {:seon.source/branch source/current-branch
                                                 :seon.source/expected-commit-id commit}]
                                               declaration]})
                      (catch Exception failure failure)))]
        (try
          ;; Admitted at h1; a publication moves current-src to h2 first.
          (let [moved (#'source-test/publish opened @#'source-test/digest-b
                                             'seon.cluster.source-test/populate-from-data!
                                             {:seon.source/expected-commit-id h1
                                              :seon.source.test/marker "moved"})
                h2 (:seon.source/commit-id moved)
                before (db/basis-t (db/db connection))
                stale (adopt h1)]
            (is (instance? Exception stale) (pr-str stale))
            (is (some #(= {:type :stale-branch-head :expected-current-commit h1 :current-commit h2}
                          (select-keys (ex-data %) [:type :expected-current-commit :current-commit]))
                      (take-while some? (iterate ex-cause stale))))
            (is (= before (db/basis-t (db/db connection))) "the stale schema change committed nothing")
            (is (nil? (get (:schema (db/db connection)) attribute)))
            (let [current (adopt h2)]
              (is (some? (:db-after current)) (pr-str current))
              (is (= :db.type/string (get-in (:schema (db/db connection)) [attribute :db/valueType])))))
          (finally (d/release connection)))))))
