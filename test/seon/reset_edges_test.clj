(ns seon.reset-edges-test
  (:require [malli.registry :as mr]
            [seon.schema] [seon.schema.internal] [malli.core] [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [seon.cluster.source :as source]
            [seon.agent :as agent]
            [seon.cluster.agent :as cluster-agent]
            [seon.cluster.source-test :as source-test]
            [seon.id :as id]
            [seon.turn :as turn]
            [seon.issue :as issue]
            [seon.error :as error]
            [seon.db :as db]
            [seon.config :as config]
            [seon.sci.eval :as evaluation]
            [seon.fn :as functions]
            [seon.program :as program]
            [seon.test-support :as support]))

(deftest named-edges-refuse-deletion-until-the-final-callers-are-repaired
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           target 'seon.turn/open?
           callers (db/q '[:find ?caller ?attribute
                           :in $ ?target [?attribute ...]
                           :where [?caller ?attribute ?target]]
                         database target
                         [:seon.fn/calls :seon.fn/references :seon.test/subject
                          :seon.effect/capability])
           basis (db/basis-t database)
           refusal (db/transact! connection [[:db/retractEntity [:seon.fn/sym target]]])]
       (is (seq callers) "The real indexed identity must have live referrers.")
       (is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))
       (is (= (count callers)
              (count (get-in refusal [:seon.error/data :seon.program/referrers]))))
       (is (= basis (db/basis-t (db/db connection))))
       (support/transacted!
        connection
        (conj (mapv (fn [[caller attribute]] [:db/retract caller attribute target]) callers)
              [:db/retractEntity [:seon.fn/sym target]]))
       (is (nil? (db/pull (db/db connection) [:db/id] [:seon.fn/sym target])))
       (is (vector? (functions/gate-set (db/db connection) target)))))))

(deftest removing-a-name-from-a-surviving-entity-refuses-its-callers
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           target 'seon.turn/open?
           entity (:db/id (db/pull database [:db/id] [:seon.fn/sym target]))
           basis (db/basis-t database)]
       (doseq [operations [[[:db/retract entity :seon.fn/sym target]]
                           [[:db/add entity :seon.fn/sym 'seon.turn/renamed-open?]]]]
         (let [refusal (db/transact! connection operations)
               referrers (get-in refusal [:seon.error/data :seon.program/referrers])]
           (is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))
           (is (seq referrers) "The refusal names the surviving callers, not just the missing identity.")
           (is (every? #(= target (:seon.program/subject %)) referrers))
           (is (= basis (db/basis-t (db/db connection))))))))))

(deftest two-keyword-members-remain-two-values-through-nested-writer-output
  (support/with-database
   (fn [connection]
     (let [target [:seon.fn/sym 'seon.id/id]
           members #{:seon.agent/id :seon.db/db}]
       (support/transacted!
        connection
        [[:db.fn/call (fn [_] [{:seon.fn/sym 'seon.id/id
                               :seon.fn/writes members}])]])
       (is (= members
              (set (:seon.fn/writes
                    (db/pull (db/db connection) '[(limit :seon.fn/writes nil)] target)))))))))

(deftest analysis-is-a-required-positive-fact
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           target [:seon.fn/sym 'seon.id/id]
           row (db/pull database [:seon.program/analyzed-source-digest :seon.fn/file] target)
           basis (db/basis-t database)]
       (is (= 64 (count (:seon.program/analyzed-source-digest row))))
       (doseq [attribute [:seon.program/analyzed-source-digest :seon.fn/file]]
         (let [refusal (db/transact! connection [[:db.fn/retractAttribute target attribute]])]
           (is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))
           (is (= basis (db/basis-t (db/db connection))))))))))

(deftest an-agent-definition-with-only-historical-reach-deletes
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           namespace-row {:seon.ns/name 'seon.reset-edges-test}
           target (symbol "seon.reset-edges-test" "owned-unreferenced-definition")
           rows (functions/source-rows
                 database (program/shapes) namespace-row
                 "(defn owned-unreferenced-definition [] true)" #{})
           test-name 'seon.reset-edges-test/analysis-is-a-required-positive-fact]
       (is (= target (:seon.fn/sym (first rows))))
       (is (= 64 (count (:seon.program/analyzed-source-digest (first rows)))))
       (is (nil? (:seon.fn/file (first rows))))
       (support/transacted! connection rows)
       (support/transacted! connection
                            [{:seon.test/sym test-name :seon.test/reach #{target}}])
       (support/transacted! connection [[:db/retractEntity [:seon.fn/sym target]]])
       (is (nil? (db/pull (db/db connection) [:db/id] [:seon.fn/sym target])))
       (is (some #{ {:seon.test/sym test-name :seon.fn/callee target}}
                 (:seon.program/stale-test-reach (functions/unresolved-callers (db/db connection)))))
       (is (= #{target}
              (set (:seon.test/reach
                    (db/pull (db/db connection) '[(limit :seon.test/reach nil)]
                             [:seon.test/sym test-name])))))))))

(deftest stored-many-members-never-stand-in-for-the-producing-fact
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           forms (:seon.schema.projection/forms (db/carried-projection database))
           installed (:schema (db/schema-database database))
           stored (filter (fn [[schema-key _form]]
                            (:seon.db/attributes (seon.schema.internal/entity-properties (mr/schema (:seon.schema.projection/registry (seon.schema/handed-projection)) schema-key)))) forms)
           violations
           (for [[schema-key form] stored
                 [attribute options] (seon.schema.internal/entity-entries (mr/schema (:seon.schema.projection/registry (seon.schema/handed-projection)) schema-key))
                 :when (and (not (:optional (when (map? options) options)))
                            (not (pos? (or (:min (malli.core/properties (mr/schema (:seon.schema.projection/registry (seon.schema/handed-projection)) attribute))) 0)))
                            (= :db.cardinality/many
                               (get-in installed [attribute :db/cardinality])))]
             [schema-key attribute])]
       (is (seq stored))
       (is (empty? violations) (pr-str violations))
       (doseq [attribute [:seon.fn/calls :seon.fn/references :seon.test/reach]]
         (is (= :db.type/symbol (get-in installed [attribute :db/valueType])))
         (is (true? (get-in installed [attribute :db/index]))))
       (is (nil? (get forms :seon.fn/capability-fn)))))))

(deftest namespace-and-schema-retractions-return-every-surviving-value-referrer
  (support/with-database
   (fn [connection]
     (doseq [[identity-attribute target relations]
             [[:seon.ns/name 'seon.turn [:seon.ns/requires]]
              [:seon.schema/key :seon.db/connection
               [:seon.fn/writes :seon.schema/references
                :seon.fn.arity/input-refs :seon.fn.arity/output-refs
                :seon.fn.arity/guard-refs]]]]
       (let [database (db/db connection)
             expected (db/q '[:find ?referrer ?relation
                               :in $ ?target [?relation ...]
                               :where [?referrer ?relation ?target]]
                             database target relations)
             refusal (db/transact! connection [[:db/retractEntity [identity-attribute target]]])
             referrers (get-in refusal [:seon.error/data :seon.program/referrers])]
         (is (seq expected))
         (is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))
         (is (= (set expected)
                (set (map (juxt :db/id :seon.program/relation) referrers)))
             (pr-str refusal))
         (is (every? (comp seq :seon.program/referrer) referrers))
         (is (= (db/basis-t database) (db/basis-t (db/db connection))))
         (prn {:seon.program/subject target :seon.program/referrer-count (count referrers)}))))))

(deftest sci-deletion-is-a-flat-refusal-with-no-committed-prefix
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "reset-deletion-origin")
     (let [ctx (support/fork-cluster-ctx connection)
           database (db/db connection)
           decisions (support/effective-config)
           result (evaluation/evaluate
                   {:seon.sci.eval/ctx ctx :seon.db/db database
                    :seon.db/connection connection
                    :seon.cluster.eval/source
                    "(seon.db/transact! [[:db/retractEntity [:seon.fn/sym 'seon.turn/open?]]])"
                    :seon.sci.admit/caps (config/result-caps decisions)
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms decisions)
                    :seon.config/on-core-error :panic})
           refusal (:seon.sci.admit/value result)]
       (is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str result))
       (is (seq (get-in refusal [:seon.error/data :seon.program/referrers])))
       (is (= (db/basis-t database) (db/basis-t (db/db connection))))))))

(deftest source-publication-refuses-an-outside-caller-and-keeps-its-head
  (let [root (io/file "tmp/reset-publication" (id/id))
        target-file (io/file root "target.clj")
        caller-file (io/file root "caller.clj")
        manifest @support/source-manifest
        artifact (fn [file]
                   (functions/build-artifact
                    {:seon.fn/source-path (.getCanonicalPath file)
                     :seon.fn.file/first-party-functions
                     (functions/manifest-function-symbols manifest)}))]
    (.mkdirs root)
    (try
      (spit target-file "(ns reset.publication.target) (defn target [] true)")
      (spit caller-file "(ns reset.publication.caller (:require [reset.publication.target :as target])) (defn caller [] (target/target))")
      (let [before (artifact target-file)
            caller (artifact caller-file)
            baseline (functions/replace-manifest-artifacts manifest [before caller])]
        (#'source-test/with-store
         (fn [opened]
           (let [published (#'source-test/publish
                            opened (id/digest 64 [:baseline]) 'seon.cluster/populate-source!
                            {:seon.fn/manifest baseline})
                 _ (spit target-file "(ns reset.publication.target)")
                 after (artifact target-file)
                 plan (functions/plan-file-change (assoc {:seon.fn.change/status :modified
                        :seon.fn.change/current-artifact before
                        :seon.fn.change/desired-artifact after} :seon.schema/projection (seon.schema/handed-projection)))
                 changed (functions/replace-manifest-artifacts baseline [after])
                 refusal (support/refusal-data
                          #(#'source-test/publish
                            opened (id/digest 64 [:removed]) 'seon.cluster/populate-source!
                            {:seon.fn/manifest changed}))]
             (is (= :full-rebuild (:seon.fn.change/action plan)))
             (is (= :seon.cluster.source/source-deletion-refused (:seon.cluster.source/rule refusal))
                 (pr-str refusal))
             (is (some #(= 'reset.publication.caller/caller
                           (get-in % [:seon.program/referrer :seon.fn/sym]))
                       (:seon.program/referrers refusal)))
             (is (= (:seon.source/commit-id published)
                    (:seon.source/commit-id (source/current opened))))
             (is (pos? (get-in published [:seon.program/unresolved-report
                                         :seon.program/analyzed-count])))
             (is (vector? (get-in published [:seon.program/unresolved-report
                                            :seon.program/unresolved-callers])))))))
      (finally (support/delete-recursively! root)))))

(deftest agents-archive-without-retraction-or-ownership-loss
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "reset-agent-archive")
     (support/transacted!
      connection (cluster-agent/creation-tx
                  {:seon.agent/id "reset-archive-agent"
                   :seon.cluster/name "reset-agent-archive"
                   :seon.ns/name 'reset.archive.agent}))
     (let [agent-id "reset-archive-agent"
           lookup [:seon.agent/id agent-id]
           selector [:db/id :seon.agent/namespace :seon.agent/runtime]
           before (db/pull (db/db connection) selector lookup)
           basis (db/basis-t (db/db connection))
           refusal (db/transact! connection [[:db/retractEntity lookup]])]
       (is (= :seon.db/invalid-write (:seon.error/kind refusal)))
       (is (= basis (db/basis-t (db/db connection))))
       (is (true? (agent/open? (db/db connection) agent-id)))
       (let [result (agent/archive! connection agent-id)]
         (is (nil? (:seon.error/kind result)) (pr-str result)))
       (is (true? (agent/archived? (db/db connection) agent-id)))
       (is (false? (agent/open? (db/db connection) agent-id)))
       (is (= before (db/pull (db/db connection) selector lookup)))))))

(deftest refused-notes-do-not-mint-incomplete-issue-identities
  (support/with-database
   (fn [connection]
     (let [note {:seon.issue/path "docs/seon/issues/reset-invalid-note.md"
                 :seon.issue/text "---\ntype: defect\nstatus: open\nseverity: blocker\n---\n# Invalid type\n\n## Problem\nThis note is refused.\n"}
           rows (issue/index-tx (db/db connection) [note])]
       (is (seq (:seon.issue/refusals (meta rows))))
       (is (empty? rows))
       (is (nil? (db/pull (db/db connection) [:db/id]
                          [:seon.issue/id "reset-invalid-note"])))))))

(deftest recording-a-fault-does-not-mint-a-program-identity
  (support/with-database
   (fn [connection]
     (let [target (symbol "reset.observed-only" "absent")
           failure (doto (ex-info "Historical observation" {})
                     (.setStackTrace
                      (into-array StackTraceElement
                                  [(StackTraceElement. "reset.observed_only$absent"
                                                       "invokeStatic" "observed_only.clj" 9)])))
           defaults (config/defaults)
           recording (error/recording
                      (db/db connection)
                      (merge (select-keys defaults [:seon.config.error/recurrence-limit
                                                     :seon.config.error/max-evidence-bytes
                                                     :seon.config.error/escalate-to])
                             {:seon.error/source failure :seon.error/id (id/id)
                              :seon.error/at (java.util.Date.)
                              :seon.error/process "seon.db.process/boot"
                              :seon.sci.admit/caps (config/result-caps defaults)}))
           signature (get-in recording [:seon.error/fact :seon.error/signature])]
       (support/transacted! connection (:seon.db/tx-data recording))
       (is (= target (:seon.instrument/fn (db/pull (db/db connection) [:seon.instrument/fn]
                                             [:seon.error/signature signature]))))
       (is (nil? (db/pull (db/db connection) [:db/id] [:seon.fn/sym target])))
       (is (nil? (db/pull (db/db connection) [:db/id]
                          [:seon.ns/name (symbol (namespace target))])))))))

(deftest analysis-digest-covers-the-resolver-prelude-and-the-exact-input
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           namespace-row {:seon.ns/name 'seon.id}
           source "(defn reset-analysis-proof [] true)"
           analyze (fn [database]
                     (#'functions/runtime-analysis-batch
                      database [{:namespace-name 'seon.id :form-source source
                                 :seon.fn/namespace-row namespace-row}]))
           captured (analyze database)
           rows (functions/source-rows database (program/shapes) namespace-row source #{})
           expected (id/sha-256 [(.getBytes ^String (:seon.fn/source captured)
                                           java.nio.charset.StandardCharsets/UTF_8)])]
       (is (not= source (:seon.fn/source captured)) "resolver declarations are part of the input")
       (is (= expected (:seon.program/analyzed-source-digest (first rows))))
       (support/transacted! connection rows)
       (let [next-captured (analyze (db/db connection))]
         (is (not= (:seon.fn/source captured) (:seon.fn/source next-captured)))
         (is (not= expected (id/sha-256 [(.getBytes ^String (:seon.fn/source next-captured)
                                                  java.nio.charset.StandardCharsets/UTF_8)]))))))))

(deftest an-evaluation-retains-its-issue-origin-after-issue-retraction
  (support/with-database
   (fn [connection]
     (let [issue-id "reset-origin"
           agent-id "reset-origin-agent"
           turn-id "reset-origin-turn"
           evaluation-id (id/evaluation turn-id 0)]
       (support/seed-cluster! connection "reset-origin")
       (support/transacted!
        connection (cluster-agent/creation-tx
                    {:seon.agent/id agent-id :seon.cluster/name "reset-origin"
                     :seon.ns/name 'reset.origin.agent}))
       (support/transacted!
        connection
        (issue/index-tx (db/db connection)
                        [{:seon.issue/path "docs/seon/issues/reset-origin.md"
                          :seon.issue/text "---\ntype: issue\nstatus: resolved\nseverity: friction\n---\n# Historical origin\n\n## Problem\nAn evaluation observed this issue.\n"}]))
       (support/transacted!
        connection (turn/open-tx {:seon.turn/id turn-id
                                 :seon.turn/agent [:seon.agent/id agent-id]
                                 :seon.turn/opened-tx "datomic.tx"}))
       (support/transacted!
        connection
        (conj (vec (turn/receipt-start-tx
                    {:seon.turn/id turn-id :seon.cluster.eval/ordinal 0
                     :seon.cluster.eval/at (java.util.Date.)}))
              [:db/add [:seon.cluster.eval/id evaluation-id] :seon.eval/origin issue-id]))
       (support/transacted! connection [[:db/retractEntity [:seon.issue/id issue-id]]])
       (is (nil? (db/pull (db/db connection) [:db/id] [:seon.issue/id issue-id])))
       (is (= issue-id (:seon.eval/origin
                       (db/pull (db/db connection) [:seon.eval/origin]
                                [:seon.cluster.eval/id evaluation-id]))))))))

(deftest a-test-retraction-is-not-a-function-deletion
  (support/with-database
   (fn [connection]
     (let [target (symbol "seon.reset-edges-test" "removable-test")
           row (support/program-row (db/db connection)
                                    [:seon.test/sym target]
                                    "(deftest removable-test (is true))")
           observer 'seon.reset-edges-test/analysis-is-a-required-positive-fact]
       (support/transacted! connection [row])
       (support/transacted! connection [{:seon.test/sym observer :seon.fn/references #{target}}])
       (support/transacted! connection [[:db/retractEntity [:seon.test/sym target]]])
       (is (nil? (db/pull (db/db connection) [:db/id] [:seon.test/sym target])))
       (is (contains? (set (:seon.fn/references
                           (db/pull (db/db connection) '[(limit :seon.fn/references nil)]
                                    [:seon.test/sym observer]))) target))))))

(deftest a-declared-capability-handler-is-a-live-symbol-obligation
  (support/with-database
   (fn [connection]
     (let [target 'seon.fs.jvm/write
           refusal (db/transact! connection [[:db/retractEntity [:seon.fn/sym target]]])
           referrers (get-in refusal [:seon.error/data :seon.program/referrers])]
       (is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))
       (is (some #(and (= target (:seon.program/subject %))
                       (= :seon.effect/capability (:seon.program/relation %))
                       (= 'my.fs/write! (get-in % [:seon.program/referrer :seon.fn/sym])))
                 referrers))
       (is (nil? (db/pull (db/db connection) [:db/ident]
                          [:db/ident :seon.effect/capability-fn])))))))
