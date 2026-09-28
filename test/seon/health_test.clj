(ns seon.health-test
  "The health report: a finding is the named schema a value satisfies, ranked;
  derivations are retained by what they read; the report writes nothing."
  (:require [clojure.test :refer [deftest is]]
            [malli.generator :as mg]
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
               finding (some #(when (= (:seon.error/signature receipt)
                                       (get-in % [:seon.health/value :seon.error/signature]))
                                %)
                             found)
               face (health/render-ai {:seon.render/value report :seon.db/db (db/db connection)})]
           (is (= :seon.problems/open-fault (:seon.health/schema finding)) "the fault is named by the schema it satisfies")
           (is (= (get-in finding [:seon.health/value :seon.error/producer]) (:seon.health/subject finding))
               "its subject is its producer, when one is known")
           (is (= :faulted (:seon.health/state report)))
           (is (apply >= (map :seon.health/score found)) "findings are ranked worst first")
           (is (= before (db/basis-t (db/db connection))) "the report commits nothing")
           (is (<= (count (:seon.health/top face)) 5) "the AI face shows the top findings")
           (is (pos? (get-in face [:seon.health/counts :seon.problems/open-fault] 0)) "and counts every schema's matches"))
         (finally (swap! running-instances dissoc cluster-name)))))))

(deftest an-open-fault-counts-against-its-producer
  ;; Default 2026-09-28: my.e1probe/opening-unit passed a bad value to
  ;; seon.turn/record-evaluated-tx; the report listed the fault under the
  ;; refuser, so the producer's task ccd4e875780c never linked.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           projection (db/carried-projection database)
           unit (-> (mg/generate :seon.problems/error-signature
                                 {:registry (:seon.schema.projection/registry projection) :seed 3})
                    (dissoc :seon.error/resolution)
                    (assoc :seon.error/operation `health/report :seon.error/producer `health/findings))
           report {:seon.health/problems {:seon.problems/error-signatures [unit]}}
           finding (some #(when (= :seon.problems/open-fault (:seon.health/schema %)) %)
                         (health/findings database report))]
       (is (= `health/findings (:seon.health/subject finding)) "the producer, not the refuser")
       (is (= `health/report (get-in finding [:seon.health/value :seon.error/operation]))
           "the refuser stays in the value")))))

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

(deftest a-replaced-root-rides-the-report-as-instrument-reports-it
  ;; bin/seon status refused its own report on default (2026-09-28): the report
  ;; declared symbols where seon.instrument/replaced-roots answers its values.
  (let [projection (schema/handed-projection)
        root (mg/generate :seon.instrument/replaced-root
                          {:registry (:seon.schema.projection/registry projection) :seed 7})]
    (is ((schema/projection-validator projection :seon.health/replaced-roots) [root]))
    (is (not ((schema/projection-validator projection :seon.health/replaced-roots)
              [(:seon.instrument/replaced-var root)]))
        "a bare Var name is not a replaced root")))

(deftest a-slower-boot-phase-is-a-regression-until-it-recovers
  ;; Default 2026-09-28: the source-current phase took 15.5 s on one program
  ;; and 27.8 s on the next (bin/seon start 20.0 s -> 32.1 s ready), and
  ;; nothing said so. Records as boot writes them at seeding.
  (let [projection (schema/handed-projection)
        regression? (schema/projection-validator projection :seon.health/boot-regression)
        record (fn [id at program source-current]
                 {:seon.db.process/id id :seon.db.process/start-instant at :seon.source/commit-id program
                  :seon.db.process/layers [[:seon.boot/prepl-server 409] [:seon.store/store 474]
                                           [:seon.source/current source-current]]})
        a #uuid "6abad9d6-eb00-5dd4-aafc-6ee8ed005767"
        b #uuid "6abadd62-42f1-5e33-96a2-af68e8f85790"
        c #uuid "6abadefe-c452-5f3e-9784-56d7b065d137"
        slower (health/boot-comparisons [(record "1-1" #inst "2026-09-28T21:29Z" a 16007)
                                         (record "2-2" #inst "2026-09-28T21:30Z" a 16100)
                                         (record "3-3" #inst "2026-09-28T21:33Z" b 28224)]
                                        boot/boot-layers)
        by-phase (into {} (map (juxt :seon.health/phase identity)) slower)
        phase (by-phase :seon.source/current)]
    (is ((schema/projection-validator projection :seon.health/regressions) slower))
    (is (= [15626 27750 a b "2-2"] ((juxt :seon.health/before-ms :seon.health/after-ms :seon.health/before-program
                                         :seon.health/after-program :seon.health/before-process) phase))
        "the newest boot beside the newest boot of the previous program")
    (is (regression? phase))
    (is (not (regression? (by-phase :seon.store/store))) "an unchanged phase is no regression")
    (is (not-any? regression? (health/boot-comparisons [(record "3-3" #inst "2026-09-28T21:33Z" b 28224)
                                                        (record "4-4" #inst "2026-09-28T21:40Z" c 17788)]
                                                       boot/boot-layers))
        "it disappears when the numbers recover")
    (is (= [] (health/boot-comparisons [(record "1-1" #inst "2026-09-28T21:29Z" a 16007)] boot/boot-layers))
        "one program has nothing to compare")))

(deftest a-definition-slower-under-its-new-digest-is-a-regression
  (let [projection (schema/handed-projection)
        regression? (schema/projection-validator projection :seon.health/definition-regression)
        measured (fn [digest p99 at]
                   {:seon.profile.measurement/id (subs digest 0 16) :seon.profile.measurement/sym 'seon.db/q
                    :seon.profile.measurement/digest digest :seon.profile.measurement/p50 19
                    :seon.profile.measurement/p99 p99 :seon.profile.measurement/errors :none
                    :seon.profile.measurement/calls 300 :seon.profile.measurement/at at})
        older (apply str (repeat 64 "a"))
        newer (apply str (repeat 64 "b"))
        compared (health/definition-comparisons [(measured older 23 #inst "2026-09-28T20:00Z")
                                                 (measured newer 25 #inst "2026-09-28T21:00Z")])
        p99 (some #(when (= :seon.profile.measurement/p99 (:seon.health/quantile %)) %) compared)]
    (is ((schema/projection-validator projection :seon.health/regressions) compared))
    (is (= [2 older newer] ((juxt :seon.health/buckets-up :seon.health/before-digest :seon.health/after-digest) p99)))
    (is (not (regression? p99)) "a p99 move alone never flags")
    (is (not-any? regression? (filter #(= :seon.profile.measurement/p50 (:seon.health/quantile %)) compared))
        "an unmoved quantile is no regression")
    (is (= [] (health/definition-comparisons [(measured older 23 #inst "2026-09-28T20:00Z")]))
        "one digest has nothing to compare")))

(deftest a-definition-regression-is-a-median-move-over-enough-calls
  ;; Default 2026-09-28: transact! p99 0.54 s -> 1.07 s flagged from 68 vs 795
  ;; calls (fixture-write tails) with p50 flat at ~33 ms.
  (let [regression? (schema/projection-validator (schema/handed-projection) :seon.health/definition-regression)
        flagged (fn [p50-after p99-after calls-before calls-after]
                  (let [row (fn [digest p50 p99 calls at]
                              {:seon.profile.measurement/id (apply str (repeat 16 digest)) :seon.profile.measurement/sym 'seon.db/transact!
                               :seon.profile.measurement/digest (apply str (repeat 64 digest))
                               :seon.profile.measurement/p50 p50 :seon.profile.measurement/p99 p99
                               :seon.profile.measurement/errors :none :seon.profile.measurement/calls calls
                               :seon.profile.measurement/at at})]
                    (filterv regression?
                             (health/definition-comparisons [(row "a" 25 29 calls-before #inst "2026-09-28T20:00Z")
                                                             (row "b" p50-after p99-after calls-after #inst "2026-09-28T21:00Z")]))))]
    (is (empty? (flagged 25 30 68 795)) "flat p50, p99 +1 bucket, small N: no flag")
    (is (empty? (flagged 25 30 300 795)) "flat p50, p99 +1 bucket, large N: no flag")
    (is (empty? (flagged 26 30 68 795)) "a p50 move over too few before-calls: no flag")
    (is (= [:seon.profile.measurement/p50] (map :seon.health/quantile (flagged 26 30 300 795)))
        "a doubled p50 at N >= 200 flags")))
