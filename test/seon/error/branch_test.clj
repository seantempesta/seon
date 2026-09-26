(ns seon.error.branch-test
  "Errors are recorded on the branch where they happen and read back per branch
  by basis window (language-requirements §8.2-8.4)."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.problems :as problems]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(defn- record!
  "Commit one occurrence of the same error on `connection` at `at`."
  [connection at extra]
  (test-support/transacted!
   connection
   (error/commit-tx
    (db/db connection)
    (merge {:seon.schema/projection (schema/handed-projection)
            :seon.error/source {:seon.error/at at
                                :seon.error/layer :seon.error.branch-test/probe
                                :seon.error/operation 'seon.error.branch-test/probe
                                :seon.error/message "A probe failure."}
            :seon.error/declared-schema :seon.error/normalization-error
            :seon.error/id (str (random-uuid))
            :seon.error/at at
            :seon.error/process "branch-test-4242-1753650000000"
            :seon.sci.admit/caps (config/result-caps config/defaults)
            :seon.config.error/recurrence-limit 100
            :seon.config.error/max-evidence-bytes 16384}
           extra))))

(defn- branch [connection] (get-in (db/db connection) [:config :branch]))

(deftest an-error-is-seen-on-its-own-branch-once-per-window
  ;; A custody writes only its own branch, so each branch is its own fixture,
  ;; in sequence; the cluster copy is written after A is released (unlinked).
  (let [at (java.util.Date.)
        origin (test-support/with-database
                   (fn [a]
                     (let [a0 (db/basis-t (db/db a))]
                       (record! a at {})
                       (let [seen (error/occurred-since (db/db a) a0)]
                         (testing "the next evaluation on A carries it, tagged with A"
                           (is (= ['seon.error.branch-test/probe] (mapv :seon.error/operation seen)))
                           (is (= #{(branch a)} (:seon.error.occurrence/branch (first seen))))
                           (testing "as the recorded error, not a summary (task f58c3aab9277)"
                             (is (= [:seon.error.branch-test/probe "A probe failure."]
                                    ((juxt :seon.error/layer :seon.error/message) (first seen))))
                             (is (string? (:seon.error/id (first seen)))))))
                       (let [a1 (db/basis-t (db/db a))]
                         (testing "the following window no longer shows it"
                           (is (= [] (error/occurred-since (db/db a) a1))))
                         (record! a (java.util.Date. (inc (.getTime at))) {})
                         (let [recurred (error/occurred-since (db/db a) a1)]
                           (testing "a recurrence re-enters the window"
                             (is (= [2] (mapv :seon.error.occurrence/count recurred))))
                           (testing "a resolution after the occurrence marks it and hides nothing (task f58c3aab9277)"
                             (is (:db-after (problems/resolve! a (:seon.error/signature (first recurred)))))
                             (is (= [:seon.error/resolved-tx]
                                    (mapv (comp :seon.error/resolved-by :seon.error/resolution)
                                          (error/occurred-since (db/db a) a1)))))))
                       (branch a))))]
    (test-support/with-database
     (fn [b]
       (testing "B never sees A's error"
         (is (not= origin (branch b)))
         (is (not-any? #(= 'seon.error.branch-test/probe (:seon.error/operation %))
                       (error/occurred-since (db/db b) 0))))))
    (test-support/with-database
     (fn [cluster]
       (let [start (db/basis-t (db/db cluster))]
         (testing "a core fault's copy on the cluster branch names its origin"
           (record! cluster at {:seon.error.occurrence/branch origin})
           (record! cluster at {}))
         (let [seen (error/occurred-since (db/db cluster) start)]
           (testing "with A released, the cluster branch keeps the copy beside its own occurrence"
             (is (keyword? origin))
             (is (= [#{origin (branch cluster)}] (mapv :seon.error.occurrence/branch seen)))
             (is (= [2] (mapv :seon.error/occurrence-count seen))
                 "the cumulative count over every occurrence of the signature"))))))))

(deftest a-fault-is-delivered-and-shown-open-once-per-opening
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection (test-support/agents-tx @connection ["root"]))
     (let [fault! (fn [at] (record! connection at
                                    {:seon.error/source {:clojure.core.async.flow/ex (ex-info "boom" {})}
                                     :seon.error/declared-schema :seon.flow/exception-error
                                     :seon.config.error/escalate-to "root"}))
           message-datoms #(count (db/q '[:find ?m ?tx :where [?m :seon.message/about ?s]
                                                              [?e :seon.error/signature ?s]
                                                              [?e :seon.error/occurrences ?o]
                                                              [?o :seon.error.occurrence/message "boom"]
                                                              [?m :seon.message/content _ ?tx]]
                                        (db/history (db/db connection))))
           shown #(pr-str (error/render-html {:seon.db/db (db/db connection)
                                              :seon.render/value {:seon.error/signature %}}))
           at (java.util.Date.)
           _ (fault! at)]
       (testing "the opening occurrence is delivered once; a repeat while open is not"
         (is (= 1 (message-datoms)))
         (fault! (java.util.Date.))
         (is (= 1 (message-datoms))))
       (let [signature (db/q '[:find ?s . :where [?e :seon.error/signature ?s]
                                                 [?e :seon.error/occurrences ?o]
                                                 [?o :seon.error.occurrence/message "boom"]]
                             (db/db connection))]
         (is (:db-after (problems/resolve! connection signature)))
         (testing "a resolved signature renders resolved"
           (is (re-find #"\"Resolved\"" (shown signature))))
         (fault! (java.util.Date.))
         (testing "a recurrence after the resolution re-opens and re-delivers"
           (is (re-find #"recurred after its resolution" (shown signature)))
           (is (= 2 (message-datoms)))))))))
