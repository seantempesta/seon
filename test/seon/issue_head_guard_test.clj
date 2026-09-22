(ns seon.issue-head-guard-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.source-test :as source-test]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.issue :as issue]))

(deftest ^{:seon.test/fixture-observation
           "An issue adoption on a cluster branch races a publication of the same physical store."}
  stale-issue-adoption-refuses-at-the-writer
  (#'source-test/with-store
    (fn [opened]
      (let [base (#'source-test/publish opened @#'source-test/digest-a)
            h1 (:seon.source/commit-id base)
            cluster-name "issue-adoption-contest"
            _ (registry/ensure-cluster! {:seon.store/store opened
                                         :seon.boot/cluster-name cluster-name
                                         :seon.source/commit-id h1})
            connection (store/open-branch! opened (registry/cluster-branch cluster-name))
            first-source (source/database opened h1)
            identities (set (take 1 (d/q '[:find [?id ...] :where [_ :seon.issue/id ?id]]
                                         first-source)))]
        (try
          (let [moved (#'source-test/publish opened @#'source-test/digest-b
                                             'seon.cluster.source-test/populate-from-data!
                                             {:seon.source/expected-commit-id h1
                                              :seon.source.test/marker "moved"})
                h2 (:seon.source/commit-id moved)
                head (fn [commit] {:seon.source/branch source/current-branch
                                   :seon.source/expected-commit-id commit})
                before (db/basis-t (db/db connection))
                stale (try (issue/adopt! connection first-source identities (head h1))
                           (catch clojure.lang.ExceptionInfo failure failure))]
            (is (seq identities) "the published source holds an issue to adopt")
            (is (some #(= {:type :stale-branch-head :expected-current-commit h1 :current-commit h2}
                          (select-keys (ex-data %) [:type :expected-current-commit :current-commit]))
                      (take-while some? (iterate ex-cause stale)))
                (pr-str stale))
            (is (= before (db/basis-t (db/db connection))) "the stale issue adoption committed nothing")
            (let [second-source (source/database opened h2)]
              (try
                (is (some? (:db-after (issue/adopt! connection second-source identities (head h2)))))
                (is (some? (d/q '[:find ?e . :in $ [?id ...] :where [?e :seon.issue/id ?id]]
                                (db/db connection) identities)))
                (finally (d/release-materialized-db second-source)))))
          (finally
            (d/release-materialized-db first-source)
            (d/release connection)))))))
