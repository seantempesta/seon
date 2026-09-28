(ns seon.health-test
  "The health report: a finding is the named schema a value satisfies, ranked;
  derivations are retained by what they read; the report writes nothing."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.boot :as boot]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.health :as health]
            [seon.operator.runtime :refer [running-instances]]
            [seon.schema :as schema]
            [seon.test-support :as support])
  (:import [java.util.concurrent.atomic AtomicLong]))

(defn- fixture-instance
  "A declared `:seon.boot/instance` for a fixture cluster, built by boot's own
  constructor over this JVM's live listener and bootstrap config (renamed), with
  the fixture's connection as its database layer."
  [connection cluster-name]
  (let [live (some #(when (:seon.boot/prepl-server %) %) (vals @running-instances))]
    (assert live "a fixture instance stands beside this JVM's live instance")
    (assoc (boot/listening-instance (assoc (:seon.boot/config live) :seon.boot/cluster-name cluster-name)
                                    (:seon.boot/prepl-server live))
           :seon.boot/cluster-connection connection)))

(deftest ^{:seon.test/long "A cold problems derivation: 2.0 s measured on default 2026-09-28 (failed-tests' 1,775 captured index seeks, 1.4 s of it, until the runner reads members in one query)."
           :seon.test/long-ms 8000}
  an-open-fault-is-a-ranked-finding-and-the-report-writes-nothing
  (support/with-database
   (fn [connection]
     (let [cluster-name (str "health-" (random-uuid))
           _ (support/apply-config! connection cluster-name {})
           receipt (fault/fault! (env/environment {:seon.boot/cluster-name cluster-name :seon.db/connection connection})
                                 (IllegalStateException. "health fixture fault")
                                 {:seon.error/layer ::fixture
                                  :seon.error/operation `an-open-fault-is-a-ranked-finding-and-the-report-writes-nothing
                                  :seon.db.process/id "health-test"})]
       (swap! running-instances assoc cluster-name (fixture-instance connection cluster-name))
       (try
         (let [before (db/basis-t (db/db connection))
               report (health/report {:seon.boot/cluster-name cluster-name :seon.health/deadline-ms 7000})
               found (:seon.health/findings report)
               finding (some #(when (= `an-open-fault-is-a-ranked-finding-and-the-report-writes-nothing
                                       (:seon.health/subject %))
                                %)
                             found)
               face (health/render-ai {:seon.render/value report :seon.db/db (db/db connection)})]
           (is (= :seon.problems/open-fault (:seon.health/schema finding)) "the fault is named by the schema it satisfies")
           (is (= (:seon.error/signature receipt) (get-in finding [:seon.health/value :seon.error/signature])))
           (is (= :faulted (:seon.health/state report)))
           (is (apply >= (map :seon.health/score found)) "findings are ranked worst first")
           (is (= before (db/basis-t (db/db connection))) "the report commits nothing")
           (is (<= (count (:seon.health/top face)) 5) "the AI face shows the top findings")
           (is (pos? (get-in face [:seon.health/counts :seon.problems/open-fault] 0)) "and counts every schema's matches"))
         (finally (swap! running-instances dissoc cluster-name)))))))

(deftest a-derivation-is-reused-until-an-attribute-it-read-changes
  (support/with-database
   (fn [connection]
     (let [cluster-name (str "health-derived-" (random-uuid))
           _ (support/apply-config! connection cluster-name {})
           derivations (AtomicLong.)
           models (fn [database]
                    (.incrementAndGet derivations)
                    (db/q '[:find [?m ...] :where [_ :seon.config.ai/model ?m]] database))
           derivation-name [::models cluster-name]
           derive-models #(health/derived (db/db connection) derivation-name models)]
       (derive-models)
       (derive-models)
       (is (= 1 (.get derivations)) "the same database value reads the retained derivation")
       (fault/fault! (env/environment {:seon.boot/cluster-name cluster-name :seon.db/connection connection})
                     (IllegalStateException. "an unrelated commit")
                     {:seon.error/layer ::fixture :seon.error/operation `a-derivation-is-reused-until-an-attribute-it-read-changes
                      :seon.db.process/id "health-test"})
       (derive-models)
       (is (= 1 (.get derivations)) "a commit changing none of its attributes reuses it")
       (support/transacted! connection [[:db/add [:seon.config/cluster cluster-name] :seon.config.ai/model "health-test-model"]])
       (is (some #{"health-test-model"} (derive-models)))
       (is (= 2 (.get derivations)) "a commit to an attribute it read derives it again")))))

(deftest a-stored-finding-schema-sees-a-member-constrained-absent
  ;; The health model proof: pulling only required keys made "public function
  ;; with no contract" match all 1,710 public functions instead of 250.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           projection (schema/build-projection
                       (merge (:seon.schema.projection/forms (db/carried-projection database))
                              {::uncontracted [:map {:seon.health/severity 1 :seon.health/subject :seon.fn/sym}
                                               [:seon.fn/sym :seon.fn/sym]
                                               [:seon.fn/private? [:= false]]
                                               [:seon.fn/spec {:optional true} [:not :any]]]
                               ::every-function [:map {:seon.health/severity 1} [:seon.fn/sym :seon.fn/sym]]}))
           matches (#'health/stored-matches database projection ::uncontracted)
           expected (db/q '[:find (count ?e) . :where [?e :seon.fn/sym] [?e :seon.fn/private? false]
                            [(missing? $ ?e :seon.fn/spec)]]
                          database)]
       (is (pos? expected) "the world holds public functions without a contract")
       (is (= expected (count matches)) "an optional member constrained absent is pulled and judged")
       (is (not-any? :seon.fn/spec matches))
       (is (#'health/identity-only? database projection ::every-function)
           "a stored schema constraining only an identity is refused")
       (is (not (#'health/identity-only? database projection ::uncontracted)))))))
