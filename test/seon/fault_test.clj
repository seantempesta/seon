(ns seon.fault-test
  "One regression per class of the one core-fault route (`seon.fault/fault!`,
  AGENTS.md \"The error policy\"), each on its own canonical fixture branch."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.writer]
            [datahike.writing]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.test-support :as test-support])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(def ^:private context
  {:seon.error/layer ::boundary
   :seon.error/operation `boundary
   :seon.db.process/id "fault-test"})

(defn- world
  "The environment a boundary already holds, for one fixture cluster."
  [connection cluster-name manifest]
  (test-support/apply-config! connection cluster-name manifest)
  (env/environment {:seon.boot/cluster-name cluster-name
                    :seon.db/connection connection
                    :seon.sci.admit/caps (config/result-caps
                                          (config/effective (db/db connection) cluster-name))}))

(defn- nested-failure
  "An unexpected failure whose root cause an outer wrapper hides."
  []
  (ex-info "outer wrapper" {::wrapped true}
           (IllegalStateException. "inner root cause")))

(defn- stored
  "The committed error row, its occurrence count and the wakes naming it."
  [connection signature]
  (let [database (db/db connection)]
    {:row (db/pull database '[:seon.error/exception-class
                              {:seon.error/occurrences
                               [:seon.error.occurrence/count
                                {:seon.error.occurrence/data-blob
                                 [:seon.error.occurrence/blob-digest]}]}]
                   [:seon.error/signature signature])
     :woken (set (db/q '[:find [?to ...] :in $ ?signature
                         :where [?message :seon.message/about ?signature]
                                [?message :seon.message/to ?agent]
                                [?agent :seon.agent/id ?to]]
                       database signature))}))

(deftest record-mode-stores-the-whole-fault-and-wakes-the-responsible-agent
  (test-support/with-database
   (fn [connection]
     (let [started (System/nanoTime)
           receipt (fault/fault! (world connection "fault-record" {:seon.config/on-core-error :record})
                                 (nested-failure) context)
           elapsed-ms (/ (- (System/nanoTime) started) 1e6)
           {:keys [row woken]} (stored connection (:seon.error/signature receipt))
           occurrence (first (:seon.error/occurrences row))]
       (is (= :record (:seon.fault/policy receipt)))
       (is (true? (:seon.fault/committed? receipt)))
       (is (string? (:seon.error.occurrence/id receipt)))
       (is (= 'clojure.lang.ExceptionInfo (:seon.error/exception-class row)))
       (is (= 1 (:seon.error.occurrence/count occurrence)))
       (testing "the stored evidence (its content blob) keeps the hidden root cause"
         (let [digest (get-in occurrence [:seon.error.occurrence/data-blob
                                          :seon.error.occurrence/blob-digest])]
           (is (string? digest) (pr-str occurrence))
           (is (str/includes? (str (blob/get connection digest)) "inner root cause"))))
       (testing "the writer wakes the configured responsible agent (root by default)"
         (is (= "root" (:seon.config.error/escalate-to
                        (config/effective (db/db connection) "fault-record"))))
         (is (contains? woken "root")))
       (is (< elapsed-ms 1000.0) (str "one synchronous fault write took " elapsed-ms " ms"))))))

(deftest panic-mode-stores-then-throws-its-receipt-and-a-rethrow-is-not-recounted
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "fault-panic" {:seon.config/on-core-error :panic})
           failure (nested-failure)
           panic (try (fault/fault! environment failure context) nil
                      (catch clojure.lang.ExceptionInfo thrown thrown))
           receipt (:seon.fault/recorded (ex-data panic))]
       (is (some? panic) "panic mode throws")
       (is (identical? failure (ex-cause panic)) "the original is the real cause")
       (is (= :panic (:seon.fault/policy receipt)))
       (is (true? (:seon.fault/committed? receipt)) "it stores before it throws")
       (let [rethrown (try (fault/fault! environment panic context) nil
                           (catch Throwable thrown thrown))
             wrapped (ex-info "enclosing boundary" {} panic)
             propagated (try (fault/fault! environment wrapped context) nil
                             (catch Throwable thrown thrown))
             {:keys [row woken]} (stored connection (:seon.error/signature receipt))]
         (is (identical? panic rethrown) "a carried receipt propagates unchanged")
         (is (identical? wrapped propagated) "found through a cause-preserving wrapper")
         (is (= 1 (:seon.error.occurrence/count (first (:seon.error/occurrences row))))
             "the propagation counted once")
         (is (contains? woken "root")))))))

(deftest an-unknown-write-outcome-panics-in-both-modes-without-retry
  (doseq [mode [:record :panic]]
    (test-support/with-database
     (fn [connection]
       (let [environment (world connection (str "fault-unknown-" (name mode))
                                {:seon.config/on-core-error mode
                                 :seon.config.db/write-time-limit-ms 25})
             before (db/basis-t @connection)
             entered (atom 0)
             started (CountDownLatch. 1)
             release (CountDownLatch. 1)]
         (test-support/await-event!
          (datahike.writer/shutdown (:writer @connection))
          "the canonical fixture writer to stop before replacement")
         (swap! (:wrapped-atom connection) assoc :writer
                (datahike.writer/create-writer
                 {:backend :self
                  :write-fn-map
                  {'transact!
                   (fn [database request]
                     (swap! entered inc)
                     (.countDown started)
                     (when-not (.await release test-support/event-backstop-seconds TimeUnit/SECONDS)
                       (throw (ex-info "The test did not release its blocked writer."
                                       {:seon.test/event :blocked-writer-release})))
                     (datahike.writing/transact! database request))}}
                 connection))
         (try
           (let [panic (try (fault/fault! environment (nested-failure) context) nil
                            (catch clojure.lang.ExceptionInfo thrown thrown))
                 receipt (:seon.fault/recorded (ex-data panic))]
             (is (some? panic) (str mode " panics"))
             (is (= :panic (:seon.fault/policy receipt)))
             (is (false? (:seon.fault/committed? receipt)))
             (is (true? (get-in (ex-data panic) [:seon.fault/outcome
                                                 :seon.db/transaction-outcome-unknown])))
             (is (= 1 @entered) "the writer saw exactly one attempt: no retry")
             (.countDown release)
             (test-support/await-event!
              connection "the unknown fault write to settle before the branch is released"
              #(> (db/basis-t %) before)))
           (finally (.countDown release))))))))
