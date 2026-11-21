(ns seon.code-analyze-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [jolt.analyzer]
            [seon.code :as code]
            [seon.code.analyze :as analyze]
            [seon.schema :as schema]
            [seon.schema.datahike :as sdh]
            [seon.store :as store]))

(deftest unit-account-extracts-defs-refs-ops-catches-and-macros-test
  (let [tap-acc (volatile! {:nodes [] :before {}})
        _ (binding [jolt.analyzer/*unit-tap* (analyze/make-tap tap-acc)]
            (eval '(do
                     (ns seon.analyze-scratch)
                     (defn sample-worker [x]
                       (try
                         (let [f (requiring-resolve 'seon.code/eval-forms)]
                           {:op :demo/work :val (f x)})
                         (catch Exception _e :swallowed)))
                     (defmacro sample-mac "Macro docstring" [a]
                       `(clojure.string/upper-case ~a)))))
        accts (mapv analyze/unit-account (:nodes @tap-acc))
        merged (analyze/merge-accounts accts)
        changed (analyze/set-vars @tap-acc)
        mmeta (analyze/macro-var-meta 'seon.analyze-scratch/sample-mac)]
    (testing "compiler IR accounting extracts defs, requiring-resolve, macro template refs, ops, and catches"
      (is (contains? (:refs merged) 'seon.code/eval-forms))
      (is (contains? (:refs merged) 'clojure.string/upper-case))
      (is (contains? (:ops merged) :demo/work))
      (is (some #(= :constant (:answers %)) (:catches merged)))
      (is (some #(= 'seon.analyze-scratch/sample-worker %) changed))
      (is (some #(= 'seon.analyze-scratch/sample-mac %) changed))
      (is (= "Macro docstring" (:doc mmeta)))
      (is (seq (:arglists mmeta))))
    (remove-ns 'seon.analyze-scratch)))

(deftest tx-meta-and-eval-touched-capture-test
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}
        _ (d/create-database cfg)
        conn (d/connect (assoc-in cfg [:writer :write-fn-map]
                                  {'transact! (fn [db arg-map]
                                                (let [raw (if (map? arg-map) (:tx-data arg-map) arg-map)
                                                      txd (#'store/stamp-tx-meta db raw)
                                                      eff (if (map? arg-map) (assoc arg-map :tx-data txd) txd)]
                                                  (store/validate-tx-data! db txd)
                                                  (datahike.writing/transact! db eff)))}))
        reg (:registry (schema/registry-for-db nil (schema/db-schema-forms (d/db (d/connect cfg)))))
        decls (sdh/declarations (:registry @schema/base-registry))
        extra-decls [{:db/ident :seon.tx/agent :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
                     {:db/ident :seon.tx/branch :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
                     {:db/ident :seon.tx/reason :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                     {:db/ident :seon.agent.eval/touched :db/valueType :db.type/data :db/cardinality :db.cardinality/one}]]
    (d/transact conn {:tx-data decls})
    (d/transact conn {:tx-data extra-decls})
    (let [r (binding [store/*tx-meta* {:seon.tx/agent :agent/test-writer
                                       :seon.tx/branch :branch/tx-demo
                                       :seon.tx/reason "unit-test"}]
              (d/transact conn {:tx-data [{:seon.task/id :seon.demo/tx-task
                                           :seon.task/title "Tx meta task"}]}))
          tx-id (:max-tx (:db-after r))
          tx-ent (d/pull (:db-after r) '[:seon.tx/agent :seon.tx/branch :seon.tx/reason] tx-id)]
      (is (= :agent/test-writer (:seon.tx/agent tx-ent)))
      (is (= :branch/tx-demo (:seon.tx/branch tx-ent)))
      (is (= "unit-test" (:seon.tx/reason tx-ent))))
    (let [[_ rset] (reads/with-reads
                     (fn []
                       (d/pull @conn '[:seon.task/title] [:seon.task/id :seon.demo/tx-task])))
          touched (analyze/extract-touched-eids rset 16)]
      (is (seq touched))
      (is (some #(= [:seon.task/id :seon.demo/tx-task] %) touched)))))
