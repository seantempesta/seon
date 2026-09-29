(ns my.program-test
  (:require [clojure.test :refer [deftest is]]
            [my.program :as program]
            [seon.config :as config]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.id :as id]
            [seon.env :as env]
            [seon.error :as error]
            [seon.fn :as function]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest program-reads-name-the-referrers-and-propose-without-writing
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           request {:seon.db/db database :seon.program/subject 'seon.turn/open?}
           report (program/breaks request)
           caller-report (program/callers request)
           tests (program/tests-reaching request)
           expected (set (db/q '[:find [?name ...] :in $ ?target
                                 :where [?caller :seon.fn/calls ?target]
                                 (or [?caller :seon.fn/sym ?name]
                                     [?caller :seon.test/sym ?name])]
                               database 'seon.turn/open?))
           valid? (schema/projection-validator (schema/handed-projection) :seon.program/breakage)]
       (is (not (:seon.error/at report)))
       (is (= expected (:seon.program/callers report)))
       (is (= expected (:seon.program/callers caller-report)))
       (is (= expected (set (map :seon.fn/sym (:seon.program/call-sites report)))))
       (is (every? :seon.fn/form-span (:seon.program/call-sites report)))
       (is (seq (:seon.program/gating report)))
       (is (= (function/gate-set database 'seon.turn/open?)
              (:seon.program/gating tests) (:seon.program/gating report)))
       (is (not (contains? report :seon.program/plan)) "a break names its referrers; it proposes no issues")
       (is (seq (:seon.program/unknown report)))
       (is (valid? report))
       (is (= (db/basis-t database) (db/basis-t (db/db connection))))
       (let [missing (program/breaks (assoc request :seon.program/subject 'absent.program/function))]
         (is (= 'absent.program/function (:seon.program/not-found missing)))
         (is (= 'absent.program/function (:seon.program/not-found missing))))))))

(deftest schema-and-namespace-reads-keep-different-connections-distinct
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           key-report (program/reads-key {:seon.db/db database :seon.schema/key :seon.db/connection})
           namespace-report (program/breaks {:seon.db/db database :seon.program/subject 'seon.turn})
           render-report (program/breaks {:seon.db/db database
                                          :seon.program/subject 'seon.render.value/render-ai})]
       (is (not (:seon.error/at key-report)))
       (is (contains? (:seon.program/contract-refs key-report) 'seon.db/transact!))
       (is (seq (:seon.program/schema-references key-report)))
       (is (seq (:seon.program/mentions key-report)))
       (is (= :seon.ns/name (:seon.program/kind namespace-report)))
       (is (contains? (:seon.program/owned-declarations namespace-report) 'seon.turn/open?))
       (is (seq (:seon.program/requiring-namespaces namespace-report)))
       (is (seq (:seon.program/render-declared-by render-report)))
       (is (contains? (:seon.program/render-declared-by render-report)
                      {:seon.schema/key :seon.program/breakage
                       :seon.schema/property :seon.render/ai}))
       (doseq [report [key-report namespace-report render-report]]
         (is ((schema/projection-validator (schema/handed-projection) :seon.program/breakage) report)))))))

(deftest past-reach-is-advisory-and-source-history-survives-retraction
  (support/with-database
   (fn [connection]
     (let [target (symbol "my.program" "fixture-history")
           source "(defn fixture-history [] :first)"
           changed-source "(defn fixture-history [] :second)"
           creation (support/transacted!
                     connection [(support/program-fn-entity (db/db connection) target source)])
           before (:db-after creation)
           old-t (db/basis-t before)
           metadata-change (support/transacted!
                            connection [(support/revision before [:seon.fn/sym target]
                                                          {:seon.fn/doc "Later metadata"})])
           metadata-t (db/basis-t (:db-after metadata-change))]
       (support/transacted! connection [(support/revision (:db-after metadata-change) [:seon.fn/sym target]
                                                          {:seon.fn/source changed-source})])
       (let [snapshot (program/history {:seon.db/db (db/db connection)
                                        :seon.program/subject (symbol target) :seon.db/tx old-t})
             metadata-snapshot (program/history {:seon.db/db (db/db connection)
                                                 :seon.program/subject (symbol target)
                                                 :seon.db/tx metadata-t})]
         (is (not (:seon.error/at snapshot)))
         (is (= [source] (mapv :seon.program/source (:seon.program/history snapshot))))
         (is (not (contains? (:seon.program/definition (first (:seon.program/history snapshot)))
                            :seon.fn/doc)))
         (is (not (:seon.error/at metadata-snapshot)))
         (is (= metadata-t (:seon.db/tx metadata-snapshot)))
         (is (= ["Later metadata"]
                (mapv #(get-in % [:seon.program/definition :seon.fn/doc])
                      (:seon.program/history metadata-snapshot)))))
       (support/transacted! connection [[:db/retractEntity [:seon.fn/sym target]]])
       (let [result (program/history {:seon.db/db (db/db connection)
                                      :seon.program/subject (symbol target)})
             events (:seon.program/history result)]
         (is (not (:seon.error/at result)))
         (is (= #{source changed-source} (set (map :seon.program/source events))))
         (is (= [true false true false] (mapv :seon.db/added? events)))
         (is (= (mapv :seon.program/source events)
                (mapv #(get-in % [:seon.program/definition :seon.fn/source]) events)))
         (is ((schema/projection-validator (schema/handed-projection) :seon.program/history-report) result)))
       (let [database (db/db connection)
             test-name 'my.program-test/past-reach-is-advisory-and-source-history-survives-retraction]
         (support/transacted! connection
                              [(support/revision (db/db connection) [:seon.test/sym test-name]
                                                 {:seon.test/reach #{'seon.turn/open?}})])
         (let [report (program/breaks {:seon.db/db (db/db connection)
                                       :seon.program/subject 'seon.turn/open?})]
           (is (contains? (:seon.program/stale-reach report) (symbol test-name)))
           (is (not (contains? (:seon.program/subject-of report #{}) (symbol test-name))))
         (is (< (db/basis-t database) (db/basis-t (db/db connection))))))))))

(deftest repl-documentation-and-supplied-database-use-the-canonical-population
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "program-read-fixture")
     (let [database (db/db connection)
           decisions (support/effective-config)
           caps (config/result-caps decisions)
           ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (support/evaluate
                  {:seon.sci.eval/ctx ctx :seon.db/db database
                   :seon.db/connection connection
                   :seon.cluster.eval/source source
                   :seon.sci.admit/caps caps
                   :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms decisions)}))
           directory (:seon.sci.admit/value (run "(dir my.program)"))
           documentation (:seon.sci.admit/value (run "(doc my.program/breaks)"))
           read-evaluation (run "(my.program/breaks {:seon.program/subject 'seon.turn/open?})")]
       (is (every? (set (map :sym (:functions directory)))
                   '#{my.program/breaks my.program/callers my.program/tests-reaching
                      my.program/reads-key my.program/history my.program/diff
                      my.program/ns-unmap! my.program/remove-ns! my.program/ns-unalias!}))
       (is (every? #(and (:in %) (:out %) (:doc %)) (:functions directory)))
       (is (:example documentation))
       (is (not (:seon.cluster.eval/error read-evaluation)))
       (is (seq (get-in read-evaluation [:seon.sci.admit/value :seon.program/callers])))))))

(deftest program-refusals-carry-complete-distinguishable-declared-schemas
  (support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           failed (#'program/read-result
                   {:seon.program/subject 'sample/missing}
                   'my.program/callers
                   #(throw (ex-info "The read failed." {:sample/evidence 42})))
           missing (program/supplied-context
                    (env/environment {:seon.boot/cluster-name "program-declared-schemas"}))
           absent (program/callers {:seon.db/db (db/db connection)
                                    :seon.program/subject 'sample/missing})]
       (is ((seon.schema/projection-validator projection :seon.program/read-refused-error) failed))
       (is (= 'my.program/callers (:seon.program/read-operation failed)))
       (is (= [{:seon.error/throwable-class "clojure.lang.ExceptionInfo"
                :seon.error/message "The read failed."
                :seon.error/data {:sample/evidence 42}}]
              (mapv #(dissoc % :seon.error/frames) (:seon.error/chain failed)))
           "the read failure is its whole cause chain")
       (is (= {:seon.program/subject 'sample/missing} (:seon.error/offending failed))
           "the request is referenced by its subject, never copied")
       (is ((seon.schema/projection-validator projection :seon.program/context-unavailable-error) missing))
       (is (= #{:my.program/executing-ctx :my.program/base-ctx :seon.db/connection}
              (:seon.program/missing-context-members missing)))
       (is ((seon.schema/projection-validator projection :seon.program/not-found-error) absent))
       (is (= 'sample/missing (:seon.program/not-found absent)))
       (is (not ((schema/projection-validator projection :seon.program/read-refused-error)
                                {:seon.error/at (java.util.Date.)
                                 :seon.error/layer :seon.program/read
                                 :seon.error/operation 'my.program/callers})))))))

(deftest diff-names-what-the-branch-added-changed-and-removed-against-its-cluster
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        branch (keyword (str "program-diff-" (id/id)))
        changed 'my.program/sizes
        added 'my.program/diff-fixture-added
        removed 'my.program-test/program-refusals-carry-complete-distinguishable-declared-schemas]
    (registry/branch! {:seon.store/store store :seon.store/branch branch
                       :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
    (let [held (agent/acquire-context! target nil {:seon.agent/branch branch})
          connection (:seon.db/connection held)
          database (db/db connection)]
      (try
        (support/transacted! connection
                             (into (support/replacement-tx database [:seon.fn/sym changed]
                                                           "(defn sizes\n  \"Changed.\"\n  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] :map]}\n  [_ _]\n  {})" {})
                                   (support/replacement-tx database [:seon.fn/sym added]
                                                           "(defn diff-fixture-added\n  \"Added.\"\n  {:malli/schema [:=> [:cat] :int]}\n  []\n  1)" {})))
        (support/transacted! connection [[:db/retractEntity [:seon.test/sym removed]]])
        (let [context {:seon.db/connection connection :seon.store/store store :seon.cluster/name cluster-name
                       :seon.sci.eval/ctx (:seon.sci.eval/ctx held) :my.program/base-ctx (:seon.sci.eval/base-ctx held)}
              by-sym (fn [entries] (into {} (map (juxt :sym identity)) entries))
              value (program/diff context)
              one (program/diff context changed)]
          (is (contains? (by-sym (:added value)) added))
          (is (:digest-after (get (by-sym (:added value)) added)))
          (is (not= (:digest-before (get (by-sym (:changed value)) changed))
                    (:digest-after (get (by-sym (:changed value)) changed))))
          (is (= (list 'my.program/diff changed) (:dig (get (by-sym (:changed value)) changed))))
          (is (contains? (by-sym (:removed value)) removed))
          (is (not= (:before one) (:after one)) "the dig form answers the head's and the branch's source"))
        (finally
          (agent/release-context! held)
          (registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))))
