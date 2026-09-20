(ns seon.test.host-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.id :as id]
            [seon.id-test :as id-test]
            [seon.issue :as issue]
            [seon.issue.opening :as opening]
            [seon.problems :as problems]
            [seon.render.test :as render]
            [clojure.string :as str]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Canonical SCI acquisition verifies both host requests against one cluster."
           :seon.test/long-ms 300000}
  owned-host-requests-record-fresh-events-and-reuse-green-members
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "owned-reuse")
     (support/transacted!
      connection
      [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                                  (db/db connection))
        :seon.source/test-input-digest (id/digest 64 [:owned-reuse-inputs])}])
     (let [ctx (support/fork-cluster-ctx connection)
           request {:seon.db/connection connection
                    :seon.test.run/cluster [:seon.cluster/name "owned-reuse"]
                    :seon.test/var #'id-test/data-shape-and-explicit-length-determine-identity
                    :my.program/context {:seon.db/connection connection
                                         :seon.sci.eval/ctx ctx :my.program/base-ctx ctx}}
           first-result (sut/run-owned request)
           second-result (sut/run-owned request)
           database (db/db connection)
           confidence [:seon.test.run/basis-t :seon.test.run/program-digest
                       :seon.test.run/input-digest]]
       (is (pos? (get first-result :seon.test/pass-count 0)) (pr-str first-result))
       (is (= [0 0] (mapv first-result [:seon.test/fail-count :seon.test/error-count])))
       (is (true? (:seon.test/unchanged second-result)) (pr-str second-result))
       (is (= 3 (count (select-keys second-result confidence))))
       (is (= (select-keys first-result confidence) (select-keys second-result confidence)))
       (is (true? (sut/verified? database (:seon.test/sym first-result))))
       (is (empty? (sut/stale database [(:seon.test/sym first-result)])))
       (is (= (select-keys first-result confidence)
              (select-keys (sut/recorded-result database (:seon.test/sym first-result)) confidence)))
       (is (not (:seon.error/at (issue/index!
        {:seon.db/connection connection
         :seon.issue/notes [{:seon.issue/path "docs/seon/issues/host-reuse-proof.md"
                            :seon.issue/text
                            "---\ntype: issue\nstatus: open\nseverity: friction\ntags: [issue]\n---\n# Host reuse proof\nseon.id-test/data-shape-and-explicit-length-determine-identity"}]}))))
       (let [current (db/db connection)
             status (issue/status {:seon.db/db current :seon.issue/id "host-reuse-proof"})]
         (is (true? (issue/done? current [:seon.issue/id "host-reuse-proof"])) (pr-str status))
         (is (= [:verified] (mapv :seon.issue.test/state (:seon.issue/tests status))))
         (is (= (select-keys first-result confidence)
                (select-keys (first (:seon.issue/tests
                                     (opening/context {:seon.db/db current
                                                       :seon.issue/id "host-reuse-proof"})))
                             confidence)))
         (is (str/includes? (render/render-ai {:seon.db/db current
                                               :seon.render/value {:seon.test/sym (:seon.test/sym first-result)}})
                            ": pass")))
       (is (= 2 (count (db/q '[:find [?run ...] :where [?cluster :seon.cluster/name "owned-reuse"]
                              [?run :seon.test.run/cluster ?cluster]] database)))
           "Each request is a fresh event.")
       (is (= 1 (count (db/q '[:find [?member ...] :where [?cluster :seon.cluster/name "owned-reuse"]
                              [?run :seon.test.run/cluster ?cluster]
                              [?run :seon.test.run/members ?member]] database)))
           "Only the first request admitted execution.")
       (is (= 1 (count (db/q '[:find [?member ...] :where [?cluster :seon.cluster/name "owned-reuse"]
                              [?run :seon.test.run/cluster ?cluster]
                              [?run :seon.test.run/covered-by ?member]] database)))
           "The second event records its coverage by the existing green member.")
       (is (= :seon.test/invalid-basis
              (:seon.test/selection-refusal
               (sut/run-owned (assoc request :seon.test/run-basis-t
                                     (dec (:seon.test.run/basis-t first-result))))))
           "An unmatched historical basis cannot execute the current program.")
       (let [ns-name (symbol (str "host.reader" (id/id)))
             test-symbol (symbol (str ns-name) "probe")
             test-var (intern (create-ns ns-name) 'probe)
             run-request (fn [] {:seon.test.run/cluster [:seon.cluster/name "owned-reuse"]
                                :seon.test.run/provenance (runner/provenance (db/db connection))
                                :seon.test/remaining-ms 10000})]
         (try
           (support/transacted! connection [{:seon.ns/name ns-name}])
           (support/transacted! connection
                                [(support/program-row (db/db connection) [:seon.test/sym test-symbol]
                                                      "(clojure.test/deftest probe (clojure.test/is false))")])
           (alter-meta! test-var assoc :test (fn [] (is false "recorded member failure")))
           (let [red (sut/run test-var connection (run-request))
                 failed (#'problems/failed-tests (db/db connection))]
             (is (= 1 (:seon.test/fail-count red)) (pr-str red))
             (is (= 1 (count (:seon.test.failure/reports red))))
             (is (some #(= test-symbol (:seon.test/sym %)) failed) (pr-str failed))
             (is (str/includes? (render/render-ai {:seon.db/db (db/db connection)
                                                   :seon.render/value {:seon.test/sym test-symbol}})
                                "recorded member failure")))
           (support/transacted! connection
                                [(support/program-row (db/db connection) [:seon.test/sym test-symbol]
                                                      "(clojure.test/deftest probe (clojure.test/is true))")])
           (alter-meta! test-var assoc :test (fn [] (is true)))
           (let [green (sut/run test-var connection (run-request))
                 current (db/db connection)]
             (is (= 1 (:seon.test/pass-count green)) (pr-str green))
             (is (true? (sut/verified? current test-symbol)))
             (is (not-any? #(= test-symbol (:seon.test/sym %)) (#'problems/failed-tests current)))
             (is (= 1 (count (db/q '[:find [?member ...] :in $ ?symbol
                                     :where [?member :seon.test.member/symbol ?symbol]
                                            [?member :seon.test.member/fail-count 1]] current test-symbol)))
                 "The red history remains; the derived current failure disappears."))
           (finally (remove-ns ns-name))))))))
