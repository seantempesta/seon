(ns seon.test.publication-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster.source :as source]
            [seon.cluster.source-test :as source-test]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Two canonical source publications verify complete admitted membership."
           :seon.test/long-ms 900000}
  publication-preserves-every-admitted-member
  (let [root (str "tmp/source-member-test/" (id/id))]
    (.mkdirs (io/file root))
    (try
      (with-open [opened (support/closeable
                          (store/open-store! {:seon.store/dir (str root "/store")})
                          store/release-store!)]
        (let [held @opened
              digest (id/digest 64 [:publication-members])
              publication {:seon.store/store held
                           :seon.source/digest digest
                           :seon.source/populate 'seon.cluster/populate-source!
                           :seon.source/activation 'seon.cluster.source-test/activation
                           :seon.source/populate-request
                           {:seon.fn/manifest @support/source-manifest}}
              first-publication (source/publish! publication)
              database (source/database held (:seon.source/commit-id first-publication))
              symbols (set (take 1001 (sort (db/q '[:find [?symbol ...]
                                                   :where [_ :seon.test/sym ?symbol]] database))))
              run (assoc (runner/provenance database)
                         :seon.test.run/branch :current-src
                         :seon.test.run/published-base-digest digest
                         :seon.test.run/overlay-input-digest digest)
              admitted (source/record-results!
                        held {:seon.test.run/provenance run
                              :seon.test.run/input-digest digest
                              :seon.test.run/policy :named
                              :seon.test.run/members
                              (mapv (fn [sym] {:seon.test.member/symbol sym
                                               :seon.test.member/reasons #{:named}})
                                    (sort symbols))})
              rebuilt (source/publish! publication)
              rebuilt-database (source/database held (:seon.source/commit-id rebuilt))
              actual (db/q '[:find [?symbol ...] :in $ ?run-id
                              :where [?run :seon.test.run/id ?run-id]
                                     [?run :seon.test.run/members ?member]
                                     [?member :seon.test.member/symbol ?symbol]]
                           rebuilt-database (:seon.test.run/id run))]
          (is (= 1001 (count symbols)) "The canonical population supplies the complete probe set.")
          (is (= 1001 (count (:seon.test.run/members admitted))) (pr-str admitted))
          (is (= symbols (set actual)) "Publication preserves every selected symbol, beyond pull's default cap.")))
      (finally (support/delete-recursively! root)))))
