(ns seon.fault-test
  "One regression per class of the one core-fault route (`seon.fault/fault!`,
  AGENTS.md \"The error policy\"), each on its own canonical fixture branch."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.writer]
            [datahike.writing]
            [seon.blob :as blob]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.branch :as cluster.branch]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error :as error]
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
  ;; a member's world holds no agent it did not create; root is the shipped
  ;; escalation owner, so the boundary's cluster and root are seeded as boot does
  (test-support/seed-cluster! connection cluster-name manifest)
  (cluster/seed-root-agent! connection cluster-name cluster/boot-process-identity)
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

(deftest a-fault-stores-the-whole-fault-and-wakes-the-responsible-agent
  (test-support/with-database
   (fn [connection]
     (let [started (System/nanoTime)
           receipt (fault/fault! (world connection "fault-record" {})
                                 (nested-failure) context)
           elapsed-ms (/ (- (System/nanoTime) started) 1e6)
           {:keys [row woken]} (stored connection (:seon.error/signature receipt))
           occurrence (first (:seon.error/occurrences row))]
       (is (true? (:seon.fault/committed? receipt)))
       (is (string? (:seon.error.occurrence/id receipt)))
       (is (= 'clojure.lang.ExceptionInfo (:seon.error/exception-class row)))
       (is (= 1 (:seon.error.occurrence/count occurrence)))
       (testing "the stored evidence (its content blob) keeps the hidden root cause"
         (let [digest (get-in occurrence [:seon.error.occurrence/data-blob
                                          :seon.error.occurrence/blob-digest])]
           (is (string? digest) (pr-str occurrence))
           (is (str/includes? (str (blob/get connection digest)) "inner root cause"))))
       (testing "the shipped config delivers an unowned fault to root
       (owner ruling 2026-09-25 20:50Z), and the receipt names that recipient"
         (is (= "root" (:seon.config.error/escalate-to
                        (config/effective (db/db connection) "fault-record"))))
         (is (= #{"root"} woken))
         (is (= #{"root"} (:seon.fault/delivered-to receipt))))
       (is (< elapsed-ms 1000.0) (str "one synchronous fault write took " elapsed-ms " ms"))))))

(deftest a-fault-that-happens-to-the-escalation-owner-wakes-nobody-and-reaches-its-own-faults
  ;; The writer never mails the agent a fault happened to (a message is a
  ;; wake: the 2026-08-08 self-feeding loop). When that agent is the
  ;; escalation owner, root, no other agent can act, so the fault reaches root
  ;; through its own faults (`seon.error/for-agent`, its next context) and the
  ;; outside root through the MCP reply; a parked root answers a submission
  ;; with the park error (9f4f43443).
  (test-support/with-database
   (fn [connection]
     (let [receipt (fault/fault! (world connection "fault-escalation-owner" {})
                                 (nested-failure) (assoc context :seon.agent/id "root"))]
       (is (true? (:seon.fault/committed? receipt)) (pr-str receipt))
       (is (= "root" (:seon.config.error/escalate-to
                      (config/effective (db/db connection) "fault-escalation-owner"))))
       (is (= #{} (:woken (stored connection (:seon.error/signature receipt)))) "no wake to itself")
       (is (empty? (:seon.fault/delivered-to receipt)))
       (is (contains? (set (map :seon.error/signature (error/for-agent (db/db connection) "root")))
                      (:seon.error/signature receipt))
           "root reads it in its own faults")))))

(deftest the-boundary-names-the-site-of-a-failure-that-declares-none
  ;; Two boundaries catching the same undeclared class from one thrower are
  ;; two observed sites: the stored fact and its signature carry each
  ;; boundary's declared layer and operation. A failure that
  ;; declares its own operation keeps it.
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "fault-boundary" {})
           thrown (fn [] (IllegalStateException. "one thrower"))
           at (fn [operation failure]
                (fault/fault! environment failure (assoc context :seon.error/operation operation)))
           first-site (at `first-boundary (thrown))
           second-site (at `second-boundary (thrown))
           declared (at `first-boundary (ex-info "declared" {:seon.error/layer ::own
                                                             :seon.error/operation `own-operation}))
           fact (fn [receipt] (db/pull (db/db connection) [:seon.error/layer :seon.error/operation]
                                       [:seon.error/signature (:seon.error/signature receipt)]))]
       (is (not= (:seon.error/signature first-site) (:seon.error/signature second-site)))
       (is (= {:seon.error/layer ::boundary :seon.error/operation `first-boundary} (fact first-site)))
       (is (= {:seon.error/layer ::boundary :seon.error/operation `second-boundary} (fact second-site)))
       (is (= {:seon.error/layer ::own :seon.error/operation `own-operation} (fact declared)))))))

(deftest a-stored-occurrence-carries-its-own-declared-schemas-attributes
  ;; The writer derives which diagnostics an occurrence stores from the fault's
  ;; declared schema and the base alone, never from every declared error
  ;; schema: `:seon.program/deletion-refused-error` declares
  ;; `:seon.db/attributes false`, so its `:seon.db/transaction-refused` stays
  ;; evidence even though another error schema stores that attribute.
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "fault-attributes" {})
           occurrence (fn [declared-schema extra]
                        (let [receipt (fault/fault! environment
                                                    (merge {:seon.error/at (java.util.Date.)
                                                            :seon.error/layer ::boundary
                                                            :seon.error/operation `boundary
                                                            :seon.error/message "refused"
                                                            :seon.db/transaction-refused true}
                                                           extra)
                                                    (assoc context :seon.error/declared-schema declared-schema))]
                          (is (true? (:seon.fault/committed? receipt)) (pr-str receipt))
                          (db/pull (db/db connection) '[*]
                                   [:seon.error.occurrence/id (:seon.error.occurrence/id receipt)])))
           kept-out (occurrence :seon.program/deletion-refused-error
                                 {:seon.program/referrers [{:seon.program/subject 'my.fault/f
                                                            :seon.program/identity [:seon.fn/sym 'my.fault/f]
                                                            :seon.program/relation :seon.fn/calls
                                                            :seon.program/referrer {:seon.fn/sym 'my.fault/f}
                                                            :db/id 1}]})
           kept (occurrence :seon.db/transaction-refused-error {:seon.db.write.attempt/request-id "r1"})]
       (is (= "refused" (:seon.error/message kept-out)) "the base's attributes are stored")
       (is (not (contains? kept-out :seon.db/transaction-refused)) (pr-str kept-out))
       (is (true? (:seon.db/transaction-refused kept)) (pr-str kept))
       (is (= "r1" (:seon.db.write.attempt/request-id kept)))))))

(deftest ^{:seon/tasks #{"6b8d26a16ce7"}}
  a-stored-fault-returns-its-receipt-beside-the-result-and-a-carried-receipt-is-not-recounted
  ;; Task 6b8d26a16ce7: there is no core-error dial. A fault whose write
  ;; commits is data beside the caller's result: `fault!` returns, never throws.
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "fault-stored" {:seon.config.error/escalate-to "root"})
           failure (nested-failure)
           receipt (fault/fault! environment failure context)]
       (is (true? (:seon.fault/committed? receipt)) "it stores and returns")
       (is (not (contains? receipt :seon.fault/policy)) "no policy is decided")
       (let [carried (ex-info "SEON CORE FAULT (not recorded): carried"
                              {:seon.fault/recorded receipt} failure)
             rethrown (try (fault/fault! environment carried context) nil
                           (catch Throwable thrown thrown))
             wrapped (ex-info "enclosing boundary" {} carried)
             propagated (try (fault/fault! environment wrapped context) nil
                             (catch Throwable thrown thrown))
             {:keys [row woken]} (stored connection (:seon.error/signature receipt))]
         (is (identical? carried rethrown) "a carried receipt propagates unchanged")
         (is (identical? wrapped propagated) "found through a cause-preserving wrapper")
         (is (= 1 (:seon.error.occurrence/count (first (:seon.error/occurrences row))))
             "the propagation counted once")
         (is (contains? woken "root") "a manifest naming root wakes it"))))))

(deftest an-unknown-write-outcome-stops-and-throws-without-retry
  ;; storage failing is the one case in which a fault throws (task 6b8d26a16ce7)
  (let [mode :unknown]
    (test-support/with-database
     (fn [connection]
       (let [cluster-name (str "fault-unknown-" (name mode))
             environment (world connection cluster-name {})
             ;; the bound is for the fault's write alone: set last, so the
             ;; world's own seeding writes never run under it
             _ (test-support/apply-config! connection cluster-name
                                           {:seon.config.db/write-time-limit-ms 25})
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
             (is (some? panic) "an unstorable fault throws")
             (is (false? (:seon.fault/committed? receipt)))
             (is (true? (get-in (ex-data panic) [:seon.fault/outcome
                                                 :seon.db/transaction-outcome-unknown])))
             (is (= 1 @entered) "the writer saw exactly one attempt: no retry")
             (.countDown release)
             (test-support/await-event!
              connection "the unknown fault write to settle before the branch is released"
              #(> (db/basis-t %) before)))
           (finally (.countDown release))))))))

(deftest a-core-fault-is-recorded-on-its-branch-and-copied-to-the-cluster-branch
  ;; The fixture branch is the attempt; a sibling `:cluster-<name>` branch of
  ;; the same store is the cluster, open in this process like a running one's.
  (let [cluster-name (str "fault-branches-" (random-uuid))
        home (cluster.branch/cluster-branch cluster-name)
        branches-seen (fn [connection since]
                        (mapv :seon.error.occurrence/branch (error/occurred-since (db/db connection) since)))
        [store cluster origin since]
        (test-support/with-database
         (fn [attempt]
           (let [origin (get-in (db/db attempt) [:config :branch])
                 store (:seon.store/store (test-support/execution-handle attempt))]
             (test-support/apply-config! attempt cluster-name {})
             (test-support/transacted!
              attempt (into (test-support/agents-tx @attempt ["root"])
                            (agent/creation-tx {:seon.agent/id "worker" :seon.ns/name 'my.agents.worker
                                                :seon.cluster/name cluster-name :seon.agent/branch origin})))
             (registry/branch! {:seon.store/store store :seon.store/branch home
                                :seon.cluster.registry/from (db/commit-id (db/db attempt))})
             (let [cluster (db/carry-connection-projection-state!
                            (store/open-branch! store home)
                            (:seon.sci.eval/projection-state (meta attempt)))]
              (try
               (let [since (db/basis-t (db/db attempt))
                     receipt (db/call-with-custody
                              {:seon.db/connection cluster}
                              #(fault/fault! (env/environment
                                              {:seon.boot/cluster-name cluster-name
                                               :seon.db/connection cluster
                                               :seon.sci.admit/caps (config/result-caps config/defaults)})
                                             (nested-failure)
                                             (assoc context :seon.agent/id "worker")))]
               (testing "the cluster connection's committer records on the agent's own branch"
                 (is (true? (:seon.fault/committed? receipt)) (pr-str receipt))
                 (is (= [#{origin}] (branches-seen attempt since))))
               (testing "and copies it to the cluster branch naming its origin"
                 (is (= [#{origin}] (branches-seen cluster since))))
               (testing "both occurrences name the agent it happened to, so its own faults carry it
               (fault! hands the context's agent to the recording; sweep 2026-09-27 item 7)"
                 (doseq [connection [attempt cluster]]
                   (is (contains? (set (map :seon.error/signature (error/for-agent (db/db connection) "worker")))
                                  (:seon.error/signature receipt)))))
                 [store cluster origin since])
               (catch Throwable failure
                 (store/release-branch! cluster)
                 (registry/retire-branch! {:seon.store/store store :seon.store/branch home})
                 (throw failure)))))))]
    (try
      (testing "retiring the origin branch keeps the cluster copy"
        (is (not (contains? (registry/roster store) origin)))
        (is (= [#{origin}] (branches-seen cluster since))))
      (finally
        (store/release-branch! cluster)
        (registry/retire-branch! {:seon.store/store store :seon.store/branch home})))))

(deftest a-fault-no-agent-entity-places-stays-on-its-own-branch
  ;; A test member's world names its cluster (`default` on the development
  ;; JVM) and that cluster's branch is open in the process, but the world is
  ;; not one of the cluster's agents' branches: its fault is recorded there
  ;; only, never copied onto the cluster (seon.turn-test's fired-bound fault
  ;; was copied onto default ten times on 2026-09-26).
  (let [cluster-name (str "fault-member-" (random-uuid))
        home (cluster.branch/cluster-branch cluster-name)
        branches-seen (fn [connection since]
                        (mapv :seon.error.occurrence/branch (error/occurred-since (db/db connection) since)))]
    (test-support/with-database
     (fn [attempt]
       (let [origin (get-in (db/db attempt) [:config :branch])
             store (:seon.store/store (test-support/execution-handle attempt))]
         (test-support/apply-config! attempt cluster-name {})
         (test-support/transacted! attempt (test-support/agents-tx @attempt ["root"]))
         (registry/branch! {:seon.store/store store :seon.store/branch home
                            :seon.cluster.registry/from (db/commit-id (db/db attempt))})
         (let [cluster (db/carry-connection-projection-state!
                        (store/open-branch! store home)
                        (:seon.sci.eval/projection-state (meta attempt)))]
           (try
             (let [since (db/basis-t (db/db attempt))
                   receipt (fault/fault! (env/environment
                                          {:seon.boot/cluster-name cluster-name
                                           :seon.db/connection attempt
                                           :seon.sci.admit/caps (config/result-caps config/defaults)})
                                         (nested-failure)
                                         (assoc context :seon.agent/id "no-row"))]
               (is (true? (:seon.fault/committed? receipt)) (pr-str receipt))
               (is (= [#{origin}] (branches-seen attempt since)) "recorded where it happened")
               (is (= [] (branches-seen cluster since)) "and not copied onto the cluster"))
             (finally
               (store/release-branch! cluster)
               (registry/retire-branch! {:seon.store/store store :seon.store/branch home})))))))))
