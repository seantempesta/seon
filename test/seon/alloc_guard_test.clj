(ns seon.alloc-guard-test
  "Regression proofs for Bug A (file-import-tx + validate-tx-data! + d/transact allocation caps)
   and Bug B (interrupt-safe LMDB write transaction unwind preventing self-deadlock on lock.mdb)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.host]
            [konserve.jolt.lmdb-ffi :as lmdb-ffi]
            [seon.code :as code]
            [seon.code.io :as cio]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(deftest file-import-and-transact-allocation-budget-test
  (testing "Importing and transacting a ~30 KB multi-form namespace stays well below 64 MB"
    (let [raw-src (slurp "src/seon/vector.clj")
          sample-src (str/replace-first raw-src "(ns seon.vector" "(ns seon.alloc-guard-sample")
          edited-src (str/replace-first sample-src "(def default-shortlist 64)" "(def default-shortlist 128)")
          sample-path "src/seon/alloc_guard_sample.clj"]
      (is (> (count sample-src) 25000))
      (with-test-store [{:keys [conn]}]
        (let [m-new (code/measure-call
                     #(jolt.host/run-interruptible
                       (jolt.host/make-interrupt)
                       (fn []
                         (let [tx (code/file-import-tx @conn sample-path sample-src false)]
                           (d/transact conn {:tx-data tx})
                           (count tx)))
                       100000000
                       (* 64 1024 1024)))]
          (is (< (:bytes m-new) (* 64 1024 1024))
              (str "Brand-new 30 KB import+transact exceeded 64 MB: " (:bytes m-new)))
          (is (= sample-src (code/render-path @conn sample-path)))
          (is (>= (count (cio/ns-forms @conn (quote seon.alloc-guard-sample))) 40))
          (is (>= (count (cio/ns-vars @conn (quote seon.alloc-guard-sample))) 35)))
        ;; Add a synthetic compiler-analyzed ref on an existing var and prove unchanged re-import preserves it
        (d/transact conn {:tx-data [[:db/add [:seon.code.var/name (quote seon.alloc-guard-sample/pack)]
                                     :seon.code.var/refs (quote clojure.core/byte-array)]]})
        (let [m-same (code/measure-call
                      #(code/file-import-tx @conn sample-path sample-src false))
              tx-same (:val m-same)]
          (is (empty? tx-same) "Unchanged re-import should emit zero tx ops")
          (is (< (:bytes m-same) (* 32 1024 1024)))
          (is (contains? (set (:seon.code.var/refs
                               (d/pull @conn (quote [:seon.code.var/refs])
                                       [:seon.code.var/name (quote seon.alloc-guard-sample/pack)])))
                         (quote clojure.core/byte-array))))
        ;; 1-form edit emits only the 1 changed form + 1 changed var
        (let [m-edit (code/measure-call
                      #(let [tx (code/file-import-tx @conn sample-path edited-src false)]
                         (d/transact conn {:tx-data tx})
                         tx))
              tx-edit (:val m-edit)]
          (is (= 2 (count tx-edit)) "1-form edit should emit only 1 form + 1 var op")
          (is (< (:bytes m-edit) (* 36 1024 1024)))
          (is (= edited-src (code/render-path @conn sample-path))))))))

(deftest interrupted-lmdb-write-never-self-deadlocks-test
  (testing "Byte-budget escape mid-LMDB-write aborts txn, rolls back, and never deadlocks next write"
    (is (true? (store/install-interrupt-safe-lmdb!)))
    (with-test-store [{:keys [conn]}]
      (let [aborts0 (store/aborted-lmdb-write-txns)
            lmdb-db (:db (:backing (:store @conn)))
            ex-info-map (try
                          (jolt.host/run-interruptible
                           (jolt.host/make-interrupt)
                           (fn []
                             (lmdb-ffi/transact
                              lmdb-db
                              (fn []
                                (lmdb-ffi/put-bytes! lmdb-db lmdb-ffi/*txn*
                                                     "seon-alloc-guard-uncommitted"
                                                     (.getBytes "must-rollback" "UTF-8"))
                                (loop [i 0 acc []]
                                  (if (< i 500000)
                                    (recur (inc i) (conj acc (str "alloc-" i)))
                                    acc)))))
                           100000000
                           524288)
                          nil
                          (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
        (is (true? (:jolt/interrupted ex-info-map)))
        (is (zero? (store/active-lmdb-write-txns)))
        (is (> (store/aborted-lmdb-write-txns) aborts0))
        (is (nil? (lmdb-ffi/read-txn lmdb-db #(lmdb-ffi/get-bytes lmdb-db % "seon-alloc-guard-uncommitted"))))
        ;; Next transaction on the SAME connection must succeed immediately without self-deadlocking
        (let [m-next (code/measure-call
                      #(d/transact conn {:tx-data [{:seon.code.file/path "post-abort.txt"
                                                    :seon.code.file/text "alive"
                                                    :seon.code.file/exec? false}]}))]
          (is (some? (:db-after (:val m-next))))
          (is (< (:ms m-next) 500.0))
          (is (= "alive" (:seon.code.file/text
                            (d/pull @conn (quote [:seon.code.file/text]) [:seon.code.file/path "post-abort.txt"]))))))
      ;; Also verify eval-forms + eval-tool-calls handles an over-budget eval mid-d/transact without deadlock
      (let [boom-code "(loop [i 0 acc []] (if (< i 500000) (recur (inc i) (conj acc (str i))) acc))"
            ev (code/eval-forms @conn {:agent-id :test/guard :turn 1 :byte-budget 524288} boom-code)]
        (is (false? (:ok? ev)))
        (is (true? (:interrupted? ev)))
        (is (false? (:poisoned-worker? ev)))
        (is (zero? (store/active-lmdb-write-txns)))
        (let [r1 (d/transact conn {:tx-data (:tx-data ev)})]
          (is (some? (:db-after r1))))
        (let [tc (code/eval-tool-calls conn {:agent-id :test/guard :turn 2}
                                       [{:id "tc-ok" :code "(+ 40 2)"}])]
          (is (true? (:ok? tc)))
          (is (= "42" (:value (first (:results tc))))))))))

(deftest validate-tx-data-fast-registry-and-cache-floor-test
  (testing "Empty tx validation is zero-cost, fast-registry avoids cold recompile, and child-cfg keeps 16 MB floor"
    (is (>= (:store-cache-bytes (store/child-cfg {} 4)) (* 16 1024 1024)))
    (with-test-store [{:keys [conn]}]
      (let [db0 @conn
            m-empty (code/measure-call #(store/validate-tx-data! db0 []))
            reg0 (schema/registry-for-db db0)]
        (is (< (:bytes m-empty) 65536))
        (is (identical? reg0 @schema/base-registry))
        ;; Registering a brand-new branch schema must bypass base-registry and validate properly
        (let [{:keys [dh-decls entity]}
              (schema/validate-schema-registration!
               db0 {:key :seon.alloc.guard/tag
                    :form [:string {:seon.db/index true}]
                    :doc "Test branch attribute."})]
          (when (seq dh-decls) (d/transact conn {:tx-data dh-decls}))
          (d/transact conn {:tx-data [entity]})
          (let [db1 @conn
                reg1 (store/fast-registry-for-db db1)]
            (is (not (identical? reg1 @schema/base-registry)))
            (is (nil? (store/validate-tx-data! db1 [{:seon.alloc.guard/tag "ok"}])))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Schema rail rejected"
                                  (store/validate-tx-data! db1 [{:seon.alloc.guard/tag 12345}])))))))))

(deftest eval-touched-domain-eids-test
  (testing "eval-forms/eval-tool-calls records <= 64 distinct domain EIDs in :seon.agent.eval/touched, excluding meta/schema/eval/self EIDs"
    (with-test-store [{:keys [conn]}]
      (let [agent-id :agent/touched-probe
            sample-src "(ns seon.touched-demo)\n\n(defn target-fn \"Demo doc.\" [x] (inc x))\n"
            import-tx (code/file-import-tx @conn "src/seon/touched_demo.clj" sample-src false)]
        (d/transact conn {:tx-data (conj import-tx {:seon.agent/id agent-id :seon.agent/role :repl :seon.agent/branch :main})})
        (let [db0 @conn
              var-eid (:e (first (d/datoms db0 :avet :seon.code.var/name (quote seon.touched-demo/target-fn))))
              agent-eid (:e (first (d/datoms db0 :avet :seon.agent/id agent-id)))
              sch-eid (:e (first (d/datoms db0 :avet :seon.schema/key :seon.agent/entity)))
              ;; Evaluate pulls touching the target var, a schema entity, and the agent itself
              _ (intern (create-ns (quote user)) (quote _touched_test_db) db0)
              tc (code/eval-tool-calls
                  conn {:agent-id agent-id :turn 1}
                  [{:id "tc-pull"
                    :code "(do (datahike.api/pull user/_touched_test_db (quote [:seon.code.var/name :seon.code.var/doc]) [:seon.code.var/name (quote seon.touched-demo/target-fn)]) (datahike.api/pull user/_touched_test_db (quote [*]) [:seon.schema/key :seon.agent/entity]) (datahike.api/pull user/_touched_test_db (quote [:seon.agent/id :seon.agent/role]) [:seon.agent/id :agent/touched-probe]) :ok)"}])
              db1 @conn
              eval-e (:e (first (d/datoms db1 :avet :seon.agent.eval/agent agent-id)))
              touched (:seon.agent.eval/touched (d/pull db1 (quote [:seon.agent.eval/touched]) eval-e))
              tset (set touched)]
          (is (true? (:ok? tc)))
          (is (vector? touched))
          (is (<= (count touched) 64))
          (is (every? integer? touched))
          (is (contains? tset var-eid) "Touched domain var EID must be recorded")
          (is (not (contains? tset agent-eid)) "Agent own EID must be excluded")
          (is (not (contains? tset sch-eid)) "Schema entity EID must be excluded")
          (is (not (contains? tset eval-e)) "Eval entity EID must be excluded"))))))
