(ns seon.cluster.publication-reuse-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.source-test :as source-test]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.id-test :as fixture]
            [seon.test-support :as support]))

(defn populate-input!
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]]] :nil]}
  [request]
  (cluster/populate-source! (assoc request :seon.source/change-classes #{:program})))

(deftest ^{:seon.test/long "Canonical publication, real recorded test, one changed input and temporal reuse."
           :seon.test/long-ms 600000}
  recorded-result-resolves-after-one-file-publication
  (let [root (str "tmp/publication-reuse/" (id/id))
        file (io/file root "src/unrelated.clj")
        manifest-request {:seon.fn/root root :seon.fn/roots ["src"]
                          :seon.fn.analyzer/cache-root (str root "/resolver")}
        path "src/unrelated.clj"
        test-symbol 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]
    (io/make-parents file)
    (try
      (spit file "(ns publication.unrelated) (defn f {:malli/schema [:=> [:cat] :int]} [] 1)")
      (#'source-test/with-store
        (fn [opened]
          (#'source-test/publish opened (id/digest 64 [:initial]))
          (let [before (functions/build-manifest manifest-request)
                publish! (fn [manifest previous]
                           (source/publish!
                             {:seon.store/store opened
                              :seon.source/digest (:seon.fn.manifest/digest manifest)
                              :seon.source/populate 'seon.cluster.publication-reuse-test/populate-input!
                              :seon.source/activation 'seon.cluster.source-test/activation
                              :seon.source/populate-request
                              {:seon.fn/manifest manifest :seon.fn/previous-manifest previous
                               :seon.fn/changed-paths #{path}}}))]
            (publish! before (assoc before :seon.fn.manifest/artifacts []))
            (let [connection (store/open-branch! opened source/current-branch)
                  request {:seon.test/identities #{test-symbol}
                           :seon.test.run/cluster [:seon.cluster/name "publication-reuse"]}
                  admitted (try
                             (let [_ (support/seed-cluster! connection "publication-reuse")
                                   admission (sut/selection-admission
                                               (assoc request :seon.db/db (db/db connection)))
                                   run (:seon.test.run/provenance admission)]
                               (when (:seon.error/at admission)
                                 (throw (ex-info (str "Fixture selection refused: " (pr-str admission)) admission)))
                               (support/transacted! connection [[:db.fn/call sut/admit-run admission]])
                               (is (= 1 (count (:seon.test.run/members admission))))
                               (let [result (runner/run-var! #'fixture/an-evaluation-id-is-stable-short-and-a-symbol)
                                     recorded (runner/commit-results! connection
                                                {:seon.test.run/provenance run
                                                 :seon.test/run-basis-t (:seon.test.run/basis-t run)
                                                 :seon.test/run-at (:seon.test.run/at run)
                                                 :seon.test.run/terminated? true
                                                 :seon.db/db (db/db connection)
                                                 :seon.test.runner/results [result]})]
                                 (is (vector? recorded) (pr-str recorded)))
                               admission)
                             (finally (d/release connection)))]
              (spit file "(ns publication.unrelated) (defn f {:malli/schema [:=> [:cat] :int]} [] 2)")
              (let [after (functions/build-manifest (assoc manifest-request :seon.fn/previous-manifest before))
                    published (publish! after before)
                    database (source/database opened (:seon.source/commit-id published))
                    run (:seon.test.run/provenance admitted)]
                (try
                  (is (= 1 (count (#'runner/execution-members database (:seon.test.run/id run))))
                      "The original selection transaction still authorizes its member.")
                  (is (= 1 (count (runner/run-results database (:seon.test.run/id run)))))
                  (let [reused (sut/select (assoc request :seon.db/db database))]
                    (is (= [] (:seon.test.run/members reused)) (pr-str reused))
                    (is (= #{test-symbol} (set (map :seon.test/sym (:seon.test.selection/unchanged reused)))))
                    (is (= (:seon.test.run/basis-t run)
                           (:seon.test.run/basis-t (first (:seon.test.selection/unchanged reused)))))
                    (is (= 5 (:seon.test/pass-count (first (:seon.test.selection/unchanged reused))))))
                  (finally (d/release-materialized-db database))))))))
      (finally (support/delete-recursively! root)))))
