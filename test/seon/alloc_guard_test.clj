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
            [seon.test-fixture :refer [with-test-store]]
            [seon.fork.worker-support :as wsup]
            [seon.merge :as merge]
            [seon.test :as stest]
            [seon.repl :as repl]
            [seon.code.reach :as reach]
            [seon.fork.calls :as fcalls]
            [seon.ctx.touches :as ctouches]
            [clojure.java.io :as io]
            [seon.repl.search :as rsearch]
            [seon.bench.sampler]
            [seon.code.errors]))

(deftest file-import-and-transact-allocation-budget-test
  (testing "Importing and transacting a ~30 KB multi-form namespace stays well below 64 MB"
    (let [raw-src (slurp (io/file store/checkout-root "src/seon/vector.clj"))
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
(testing "eval-forms/eval-tool-calls captures uncapped domain EIDs in live memory and :results with ZERO persisted :touched/:touched-refs LMDB bloat"
  (with-test-store [{:keys [conn]}]
    (let [agent-id :agent/touched-probe
          sample-src "(ns seon.touched-demo)\n\n(defn target-fn \"Demo doc.\" [x] (inc x))\n"
          import-tx (code/file-import-tx @conn "src/seon/touched_demo.clj" sample-src false)]
      (d/transact conn {:tx-data (conj import-tx {:seon.agent/id agent-id :seon.agent/interactive? true :seon.agent/branch :main})})
      (let [db0 @conn
            var-eid (:e (first (d/datoms db0 :avet :seon.code.var/name (quote seon.touched-demo/target-fn))))
            agent-eid (:e (first (d/datoms db0 :avet :seon.agent/id agent-id)))
            sch-eid (:e (first (d/datoms db0 :avet :seon.schema/key :seon.agent/entity)))
            _ (ctouches/clear-live-reads! db0 agent-id)
            _ (intern (create-ns (quote user)) (quote _touched_test_db) db0)
            tc (code/eval-tool-calls
                conn {:agent-id agent-id :turn 1}
                [{:id "tc-pull"
                  :code "(do (datahike.api/pull user/_touched_test_db (quote [:seon.code.var/name :seon.code.var/doc]) [:seon.code.var/name (quote seon.touched-demo/target-fn)]) (datahike.api/pull user/_touched_test_db (quote [*]) [:seon.schema/key :seon.agent/entity]) (datahike.api/pull user/_touched_test_db (quote [:seon.agent/id :seon.agent/router?]) [:seon.agent/id :agent/touched-probe]) :ok)"}])
            db1 @conn
            eval-e (:e (first (d/datoms db1 :avet :seon.agent.eval/agent agent-id)))
            stored (d/pull db1 (quote [:seon.agent.eval/touched :seon.agent.eval/touched-refs]) eval-e)
            returned (:touched-eids (first (:results tc)))
            live-set (get (ctouches/live-reads-for db1 agent-id) 1 #{})
            tset (set returned)]
        (is (true? (:ok? tc)))
        (is (nil? (:seon.agent.eval/touched stored)) "Zero derived :seon.agent.eval/touched persisted to LMDB")
        (is (nil? (:seon.agent.eval/touched-refs stored)) "Zero derived :seon.agent.eval/touched-refs persisted to LMDB")
        (is (vector? returned))
        (is (every? integer? returned))
        (is (contains? tset var-eid) "Touched domain var EID must be captured live")
        (is (contains? live-set var-eid) "Touched domain var EID recorded in live in-memory ring")
        (is (not (contains? tset agent-eid)) "Agent own EID must be excluded")
        (is (not (contains? tset sch-eid)) "Schema entity EID must be excluded")
        (is (not (contains? tset eval-e)) "Eval entity EID must be excluded")
        (ctouches/clear-live-reads! db1 agent-id))))))

(deftest stale-branch-base-merged-candidate-overlay-and-overrides-test
  (testing "Parallel branch forked before :main advances tests exact post-merge candidate schemas, rules, and spliced callers"
    (with-test-store [{:keys [conn cfg]}]
      (let [leaf-v1 "(ns demo.stale-leaf)\n\n(defn val-fn {:malli/schema [:=> [:cat] :int] :cost/ticks 10000} [] 1)\n"
            leaf-v2 "(ns demo.stale-leaf)\n\n(defn val-fn {:malli/schema [:=> [:cat] :int] :cost/ticks 10000} [] 42)\n"
            caller-v1 "(ns demo.stale-caller\n  (:require [demo.stale-leaf :as leaf]))\n\n(defn call-leaf {:malli/schema [:=> [:cat] [:tuple :int :keyword]] :cost/ticks 20000} [] [(leaf/val-fn) :v1])\n"
            caller-v2 "(ns demo.stale-caller\n  (:require [demo.stale-leaf :as leaf]))\n\n(defn call-leaf {:malli/schema [:=> [:cat] [:tuple :int :keyword]] :cost/ticks 20000} [] [(leaf/val-fn) :v2-from-main])\n"
            reg! (fn [c k form doc]
                   (let [{:keys [dh-decls entity]} (schema/validate-schema-registration! @c {:key k :form form :doc doc})]
                     (when (seq dh-decls) (d/transact c {:tx-data dh-decls}))
                     (d/transact c {:tx-data [entity]})))
            mk-rule (fn [rid doc]
                      {:seon.rule/id rid :seon.rule/doc doc :seon.rule/category :seed
                       :seon.rule/stratum 50 :seon.rule/enabled? true :seon.rule/target :any
                       :seon.rule/query '[:find ?v :in $wm :where [$wm ?v :ctx/viewer _]]
                       :seon.rule/assert '[[?v :rel/probe ?v]]})]
        (try
          ;; 1. Baseline on :main before branch forks
          (reg! conn :seon.stale.probe/base-tag [:string {:seon.db/index true}] "Baseline tag.")
          (d/transact conn {:tx-data [(mk-rule :rule.stale/base "Base rule")]})
          (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/stale_leaf.clj" leaf-v1 true)})
          (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/stale_caller.clj" caller-v1 true)})
          ;; 2. Fork parallel worker branch
          (store/branch! conn :main :branch/stale-worker)
          ;; 3. Advance :main after fork (new schema, new rule, updated caller)
          (reg! conn :seon.stale.probe/main-added [:string {:seon.db/index true}] "Main-added tag.")
          (d/transact conn {:tx-data [(mk-rule :rule.stale/main-added "Main rule")]})
          (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/stale_caller.clj" caller-v2 true)})
          ;; 4. Advance :branch/stale-worker independently (edited leaf, new schema/rule, retracted base schema)
          (let [bconn (store/connect cfg :branch/stale-worker)]
            (try
              (reg! bconn :seon.stale.probe/branch-added [:int {:seon.db/index true}] "Branch-added tag.")
              (d/transact bconn {:tx-data [[:db/retractEntity [:seon.schema/key :seon.stale.probe/base-tag]]
                                           (mk-rule :rule.stale/branch-added "Branch rule")]})
              (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/demo/stale_leaf.clj" leaf-v2 true)})
              (let [main-db @conn
                    branch-db @bconn
                    plan (merge/three-way conn cfg :branch/stale-worker)
                    cand-db (stest/candidate-merged-db main-db branch-db plan)
                    ov (stest/build-branch-overlay conn plan :branch/stale-worker cand-db)
                    caller-form (some #(when (= "demo.stale-caller/call-leaf" (:seon.code.form/key %)) %) (:upserts ov))
                    ;; Direct 3-way override check (before apply-form-overlay!)
                    ov-direct (wsup/prepare-test-worker-overrides! branch-db nil)
                    direct-rids (set (map :seon.rule/id (:rules ov-direct)))]
                (is (true? (:ok? plan)))
                (is (empty? (:conflicts plan)))
                (is (some? caller-form) "Spliced caller must be in expanded overlay")
                (is (str/includes? (:seon.code.form/src caller-form) ":v2-from-main")
                    "build-branch-overlay must pull :main latest caller, not stale branch caller")
                (is (contains? (:retracted-schemas ov) :seon.stale.probe/base-tag))
                (is (some #(= :seon.stale.probe/branch-added (:seon.schema/key %)) (:schema-upserts ov)))
                (is (some #(= :rule.stale/branch-added (:seon.rule/id %)) (:rule-upserts ov)))
                ;; Candidate DB & direct worker overrides both preserve :main additions and branch edits/retracts
                (is (contains? (:schemas ov-direct) :seon.stale.probe/main-added))
                (is (contains? (:schemas ov-direct) :seon.stale.probe/branch-added))
                (is (not (contains? (:schemas ov-direct) :seon.stale.probe/base-tag)))
                (is (contains? direct-rids :rule.stale/main-added))
                (is (contains? direct-rids :rule.stale/branch-added))
                (is (= (d/commit-id main-db) (d/commit-id (:form-db ov-direct))))
                ;; Apply overlay in worker mode and execute caller -> must combine branch 42 + main :v2-from-main!
                (is (empty? (wsup/apply-form-overlay! branch-db ov)))
                (let [ov-cached (wsup/prepare-test-worker-overrides! branch-db nil)
                      cached-rids (set (map :seon.rule/id (:rules ov-cached)))]
                  (is (contains? (:schemas ov-cached) :seon.stale.probe/main-added))
                  (is (contains? (:schemas ov-cached) :seon.stale.probe/branch-added))
                  (is (not (contains? (:schemas ov-cached) :seon.stale.probe/base-tag)))
                  (is (contains? cached-rids :rule.stale/main-added))
                  (is (contains? cached-rids :rule.stale/branch-added))
                  (is (= [42 :v2-from-main] ((resolve 'demo.stale-caller/call-leaf))))))
              (finally
                (d/release bconn))))
          (finally
            (when (find-ns 'demo.stale-caller) (remove-ns 'demo.stale-caller))
            (when (find-ns 'demo.stale-leaf) (remove-ns 'demo.stale-leaf))))))))

(deftest ^{:cost/ticks 150000000} repl-run-tests-branch-overrides-and-rule-reach-digest-test
  (testing "seon.repl/run-tests! binds branch schema & rule overrides, and rule-only edits invalidate starter-rules reach digests cleanly"
    (with-test-store [{:keys [conn cfg]}]
      ;; Seed starter-rules + a rule-reaching test + an unrelated test
      (let [src-seed "(ns seon.rule.seed)
(defn starter-rules [] [])
"
            src-t1 "(ns tmp.ov-rule-test (:require [clojure.test :refer [deftest is]] [datahike.api :as d] [seon.rule.seed :as rs] [seon.test-fixture :refer [with-test-store]]))
(deftest uses-branch-schema-and-rules-test (with-test-store [{:keys [conn]}] (d/transact conn {:tx-data [{:tmp.ov/tag \"ok\"}]}) (is (some? (first (d/datoms @conn :aevt :tmp.ov/tag)))) (is (seq (rs/starter-rules)))))
"
            src-t2 "(ns tmp.ov-unrel-test (:require [clojure.test :refer [deftest is]]))
(deftest unrel-math-test (is (= 4 (+ 2 2))))
"]
        (d/transact conn {:tx-data (concat (code/file-import-tx @conn "src/seon/rule/seed.clj" src-seed false)
                                           (code/file-import-tx @conn "test/tmp/ov_rule_test.clj" src-t1 false)
                                           (code/file-import-tx @conn "test/tmp/ov_unrel_test.clj" src-t2 false)
                                           [{:seon.rule/id :rule.tmp/demo :seon.rule/category :context :seon.rule/stratum 10
                                             :seon.rule/enabled? true :seon.rule/target :any
                                             :seon.rule/query '[:find ?e :where [?e :seon.agent/id _]]
                                             :seon.rule/assert '[]}])})
        (store/branch! conn :main :branch/ov-demo)
        (let [bconn (store/connect cfg :branch/ov-demo)
              g-main (reach/build-reach-graph @conn)]
          (try
            ;; Add a brand-new schema attr + update rule ONLY on :branch/ov-demo
            (d/transact bconn {:tx-data [{:db/ident :tmp.ov/tag :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                                         {:seon.schema/key :tmp.ov/tag :seon.schema/form :string}
                                         {:seon.schema/key :tmp.ov/entity :seon.schema/form [:map {:seon.db/attributes true} [:tmp.ov/tag :tmp.ov/tag]]}
                                         {:seon.rule/id :rule.tmp/demo :seon.rule/enabled? false}]})
            ;; 1. seon.repl/run-tests! succeeds on :branch/ov-demo WITHOUT any manual override bindings!
            (binding [fcalls/*agent-ctx* {:conn bconn :branch :branch/ov-demo :agent-id :repl/ov-demo}]
            (let [tr (repl/run-tests! 'tmp.ov-rule-test/uses-branch-schema-and-rules-test)]
              (is (true? (:ok? tr)) (pr-str tr))
              (is (= 1 (:passed tr)))))
            ;; 2. Rule-only overlay invalidates starter-rules-reaching test, keeps unrelated test cached, and matches post-merge build-reach-graph!
            (let [plan (merge/three-way conn cfg :branch/ov-demo)
                  ov (stest/build-branch-overlay conn plan :branch/ov-demo)
                  g-ov (reach/apply-overlay-to-reach-graph @bconn g-main ov)]
              (is (not= (get-in g-main [:tests 'tmp.ov-rule-test/uses-branch-schema-and-rules-test])
                        (get-in g-ov   [:tests 'tmp.ov-rule-test/uses-branch-schema-and-rules-test])))
              (is (= (get-in g-main [:tests 'tmp.ov-unrel-test/unrel-math-test])
                     (get-in g-ov   [:tests 'tmp.ov-unrel-test/unrel-math-test])))
              ;; Merge onto main and verify update-reach-graph == build-reach-graph == apply-overlay!
              (d/transact conn {:tx-data (:tx-data plan)})
              (let [g-upd (reach/update-reach-graph g-main conn)
                    g-full (reach/build-reach-graph @conn)]
                (is (= (:tests g-ov) (:tests g-upd)))
                (is (= (:tests g-full) (:tests g-upd)))
                (is (= (:nodes g-full) (:nodes g-upd)) "2-pass :rdeps wiring must match full build-reach-graph exactly")
                (is (contains? (get-in g-upd [:nodes "schema/tmp.ov__tag" :rdeps]) "schema/tmp.ov__entity"))))
            (finally
              (d/release bconn)
              (doseq [n ['tmp.ov-rule-test 'tmp.ov-unrel-test]] (when (find-ns n) (remove-ns n))))))))))

(deftest ^{:cost/ticks 120000000} lmdb-monotonic-mapsize-grow-and-resize-recovery-test
  (testing "open-env monotonically grows existing writable data.mdb (never shrinks or remaps RDONLY), close-env is idempotent, and interrupt-safe RW-lock never leaks"
    (is (true? (store/install-interrupt-safe-lmdb!)))
    (let [tmp (java.io.File. (str "/tmp/seon-lmdb-grow-" (System/nanoTime)))
          path (.getPath tmp)
          mb4 (* 4 1024 1024)
          mb64 (* 64 1024 1024)]
      (try
        (.mkdirs tmp)
        ;; 1. Create store at 4 MB and commit a value
        (let [db1 (lmdb-ffi/open-env path {:map-size mb4})]
          (try
            (is (= mb4 (:map-bytes (lmdb-ffi/env-info db1))))
            (lmdb-ffi/write-txn db1 #(lmdb-ffi/put-bytes! db1 % "k1" (.getBytes "v1" "UTF-8")))
            (finally (lmdb-ffi/close-env db1))))
        ;; 2. RDONLY open with larger map-size does NOT gratuitously remap 4 MB store
        (let [dbr (lmdb-ffi/open-env path {:map-size mb64 :flags lmdb-ffi/MDB_RDONLY})]
          (try
            (is (= mb4 (:map-bytes (lmdb-ffi/env-info dbr))))
            (finally (lmdb-ffi/close-env dbr))))
        ;; 3. Re-open existing writable store with 64 MB -> grows monotonically and persists on write
        (let [db2 (lmdb-ffi/open-env path {:map-size mb64})]
          (try
            (is (= mb64 (:map-bytes (lmdb-ffi/env-info db2))))
            (lmdb-ffi/write-txn db2 #(lmdb-ffi/put-bytes! db2 % "k2" (.getBytes "v2" "UTF-8")))
            (is (= "v1" (String. ^bytes (lmdb-ffi/read-txn db2 #(lmdb-ffi/get-bytes db2 % "k1")) "UTF-8")))
            (finally (lmdb-ffi/close-env db2))))
        ;; 4. Re-open with smaller 4 MB -> NEVER shrinks below persisted 64 MB
        (let [db3 (lmdb-ffi/open-env path {:map-size mb4})]
          (try
            (is (= mb64 (:map-bytes (lmdb-ffi/env-info db3))))
            (is (= "v2" (String. ^bytes (lmdb-ffi/read-txn db3 #(lmdb-ffi/get-bytes db3 % "k2")) "UTF-8")))
            (finally (lmdb-ffi/close-env db3))))
        ;; 5. Interrupt mid-read-txn & mid-write-txn releases RW lock cleanly + adopt-mapsize! + idempotent double close
        (let [db4 (lmdb-ffi/open-env path {:map-size mb64})
              ^java.util.concurrent.locks.ReentrantReadWriteLock rw (:rw-lock db4)]
          (try
            (is (some? rw))
            (try
              (jolt.host/run-interruptible (jolt.host/make-interrupt)
                #(lmdb-ffi/read-txn db4 (fn [_] (loop [i 0 a []] (if (< i 500000) (recur (inc i) (conj a (str i))) a))))
                100000000 524288)
              (catch Throwable _ nil))
            (is (zero? (.getReadLockCount rw)) "Interrupted read-txn must release RW readLock cleanly")
            (is (false? (.isWriteLocked rw)))
            (is (zero? (lmdb-ffi/adopt-mapsize! db4)))
            (is (= mb64 (:map-bytes (lmdb-ffi/env-info db4))))
            (is (= "v2" (String. ^bytes (lmdb-ffi/read-txn db4 #(lmdb-ffi/get-bytes db4 % "k2")) "UTF-8")))
            (finally
              (lmdb-ffi/close-env db4)
              (lmdb-ffi/close-env db4))))
        (finally (store/rm-rf! tmp))))))

(deftest apply-overlay-arms-against-candidate-schema-override-test
  (let [db0 (datahike.db/empty-db nil {:schema-flexibility :write})
        base-db (:db-after (d/with db0 [{:db/ident :seon.schema/key :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                                        {:db/ident :seon.schema/form :db/valueType :db.type/data :db/cardinality :db.cardinality/one}
                                        {:seon.schema/key :seon.test.cand/item :seon.schema/form [:map [:old-req :int]]}]))
        cand-forms (assoc @seon.schema/disk-base-forms :seon.test.cand/item [:map [:new-req :string]])]
    (binding [seon.schema/*base-forms-override* cand-forms]
      (let [errs (seon.code.overlay/apply-overlay!
                   base-db
                   {:upserts [{:seon.code.form/key "seon.cand-demo/check-item"
                               :seon.code.form/ns 'seon.cand-demo
                               :seon.code.form/order 1
                               :seon.code.form/src "(defn check-item {:malli/schema [:=> [:cat :seon.test.cand/item] :string]} [m] (:new-req m))"}]
                    :retracted-keys #{}
                    :schema-upserts [{:seon.schema/key :seon.test.cand/item :seon.schema/form [:map [:new-req :string]]}]
                    :retracted-schemas #{}}
                   false)]
        (is (empty? errs))
        (is (= "ok" ((resolve 'seon.cand-demo/check-item) {:new-req "ok"})))
        (is (thrown? clojure.lang.ExceptionInfo ((resolve 'seon.cand-demo/check-item) {:old-req 1})))))))

(deftest fast-tier-lpt-binning-and-edn-safe-stress-var-test
  (testing "build-namespace-subs packs many namespaces into <= max-workers bins without dropping tests/gens"
    (let [tests (mapv (fn [i] {:ns (symbol (str "seon.ns" i)) :var (symbol (str "seon.ns" i) "t") :digest (str "d" i) :est-ms 12.0}) (range 20))
          gens (mapv (fn [i] {:ns (symbol (str "seon.ns" i)) :var (symbol (str "seon.ns" i) "g") :digest (str "g" i) :est-ms 5.0}) (range 20))
          subs (seon.test.lpt/build-namespace-subs "b1" "fast" {} :main nil tests gens 10 42 4)]
      (is (= 4 (count subs)))
      (is (= 20 (reduce + 0 (map (comp count :test-items) subs))))
      (is (= 20 (reduce + 0 (map (comp count :gen-items) subs))))))
  (testing "stress-var failure maps always round-trip cleanly through clojure.edn/read-string even with #object return values"
    (let [ns-sym 'seon.tmp.edn-safe-stress]
      (try
        (let [ns-obj (create-ns ns-sym)]
          (binding [*ns* ns-obj]
            (refer-clojure)
            (eval '(defn bad-obj {:malli/schema [:=> [:cat :int] :string]} [x] (java.util.regex.Pattern/compile "a")))))
        (let [r (seon.schema/stress-var nil 'seon.tmp.edn-safe-stress/bad-obj {:samples 3 :seed 42})]
          (is (false? (:ok? r)))
          (is (map? (clojure.edn/read-string (pr-str r)))))
        (finally (when (find-ns ns-sym) (remove-ns ns-sym)))))))

(deftest eval-forms-catches-lazy-seq-and-stress-var-args-test
  (testing "lazy-seq exception and infinite lazy-seq are caught inside eval-forms under run-interruptible"
    (with-test-store [{:keys [conn]}]
      (let [db @conn
            r-err (code/eval-forms db {:agent-id :repl/lazy-test :turn 1}
                                   "(map (fn [_] (seq (fn [] 1))) [1])")
            r-inf (code/eval-forms db {:agent-id :repl/lazy-test :turn 1
                                       :tick-budget 2000000 :byte-budget 16777216}
                                   "(range)")]
        (is (false? (:ok? r-err)))
        (is (str/includes? (str (:error r-err)) "ISeq"))
        (is (false? (:ok? r-inf)))
        (is (true? (:interrupted? r-inf))))))
  (testing "stress-var guards non-map/non-symbol args cleanly and is exported as user/stress-var"
    (let [bad-2 (schema/stress-var 'seon.ctx.recall/tokenize-goal 42)
          bad-sym (schema/stress-var nil 42 {})
          ok-v (schema/stress-var nil #'seon.ctx.recall/tokenize-goal {:samples 5 :seed 42})]
      (is (false? (:ok? bad-2)))
      (is (= :invalid-stress-var-args (:error bad-2)))
      (is (false? (:ok? bad-sym)))
      (is (= :invalid-stress-var-sym (:error bad-sym)))
      (is (true? (:ok? ok-v))))))

(deftest wave6e-stress-var-examples-on-any-function-and-wire-12k-test
  (testing "stress-var checks :examples on :function schemas with :any (never returns silent nil)"
    (let [r (schema/stress-var nil 'seon.render.text/nonblank {:samples 10 :seed 42})]
      (is (map? r))
      (is (true? (:ok? r)))
      (is (true? (:skipped-gen? r)))
      (is (= 3 (:ran r)))))
  (testing "format-eval-output preserves multi-var source maps up to 12k chars without summarizing"
    (let [s1 (apply str (repeat 2600 "a"))
          s2 (apply str (repeat 2600 "b"))
          out (seon.model.wire/format-eval-output {:handle 'result/e1 :ok? true :value (pr-str {'a/f1 s1 'a/f2 s2})})]
      (is (str/includes? out s1))
      (is (str/includes? out s2))))
  (testing "effective-eval-budgets grants 4GB override to standard clojure.test/run-tests"
    (with-test-store [{:keys [conn]}]
      (let [res (code/eval-forms @conn {:agent-id :repl/ct-ov :turn 1 :byte-budget 1048576}
                                 "(do (require 'clojure.test) (let [_ clojure.test/run-tests] (vec (range 50000))))")]
        (is (true? (:ok? res)) (pr-str res))))))

(deftest ^{:cost/ticks 60000000} wave7d-stress-var-kwargs-and-schema-docs-test
  (testing "stress-var accepts keyword args cleanly without invalid-arity errors"
    (let [r1 (schema/stress-var 'seon.model.messages/vision-capable-model? :seed 42)
          r2 (schema/stress-var 'seon.model.messages/vision-capable-model? :samples 12 :seed 42)
          r3 (schema/stress-var 'seon.model.messages/vision-capable-model? :samples)]
      (is (true? (:ok? r1)))
      (is (true? (:ok? r2)))
      (is (false? (:ok? r3)))
      (is (= :invalid-stress-var-args (:error r3)))))
  (testing "Every registered schema carries :seon.schema/doc"
    (when-let [conn (:conn seon.fork.calls/*agent-ctx*)]
      (let [db @conn
            missing (remove #(some? (:seon.schema/doc %))
                            (map #(d/pull db '[:seon.schema/key :seon.schema/doc] (:e %))
                                 (d/datoms db :aevt :seon.schema/key)))]
        (is (empty? missing))))))

(deftest ^{:cost/ticks 60000000} wave8b-scoped-search-grep-and-run-tests-only-test
  (testing "search and grep-src accept bare namespace symbol as 2nd arg"
    (when seon.fork.calls/*agent-ctx*
      (let [s-res (seon.repl.search/search "stress" 'seon.schema)
            g-res (seon.repl.search/grep-src "stress-var" 'seon.schema)]
        (is (some #(= 'seon.schema/stress-var %) (:vars s-res)))
        (is (every? #(or (= "seon.schema" (namespace %)) (clojure.string/starts-with? (str (namespace %)) "seon.schema.")) (:vars s-res)))
        (is (seq g-res))
        (is (every? #(or (= "seon.schema" (:ns %)) (clojure.string/starts-with? (str (:ns %)) "seon.schema.")) g-res)))))
  (testing "run-tests! accepts :only keyword arg without passing :only into reaching-tests"
    (when seon.fork.calls/*agent-ctx*
      (let [rt (seon.repl/run-tests! 'seon.alloc-guard-test :only '[seon.alloc-guard-test/wave7d-stress-var-kwargs-and-schema-docs-test])]
        (is (true? (:ok? rt)))
        (is (= 1 (:ran rt)))))))

(deftest ^{:cost/ticks 60000000} wave9e-doseq-ns-unmap-and-scratch-def-guard-test
  (testing "doseq/do (ns-unmap ...) retracts vars & forms from Datahike, and runtime scratch defs do not pollute target namespaces"
    (with-test-store [{:keys [conn]}]
      (let [r1 (code/eval-forms @conn {:agent-id :repl/w9e :turn 1}
                 "(ns demo.w9e-unmap) (def keep-const 42) (def tmp-a 1) (def tmp-b 2) (def db-probe (when false (datahike.api/pull nil '[*] 1)))")
            _ (when (seq (:tx-data r1)) (d/transact conn {:tx-data (:tx-data r1)}))
            db1 @conn
            has-form? (fn [db k] (boolean (first (d/datoms db :avet :seon.code.form/key k))))]
        (is (true? (:ok? r1)))
        (is (has-form? db1 "demo.w9e-unmap/keep-const"))
        (is (has-form? db1 "demo.w9e-unmap/tmp-a"))
        (is (has-form? db1 "demo.w9e-unmap/tmp-b"))
        (is (not (has-form? db1 "demo.w9e-unmap/db-probe")) "Runtime DB-query scratch def must not persist")
        (let [r2 (code/eval-forms @conn {:agent-id :repl/w9e :turn 2}
                   "(doseq [s '[tmp-a tmp-b]] (ns-unmap 'demo.w9e-unmap s))")
              _ (when (seq (:tx-data r2)) (d/transact conn {:tx-data (:tx-data r2)}))
              db2 @conn]
          (is (true? (:ok? r2)))
          (is (has-form? db2 "demo.w9e-unmap/keep-const"))
          (is (not (has-form? db2 "demo.w9e-unmap/tmp-a")) "doseq ns-unmap must retract form from Datahike")
          (is (not (has-form? db2 "demo.w9e-unmap/tmp-b")) "doseq ns-unmap must retract form from Datahike"))))))

(deftest wave10c-aliased-budget-ns-size-summary-and-ask-kwargs-test
  (testing "effective-eval-budgets resolves aliased vars (both in-form :as and required aliases)"
    (let [eb #'seon.code/effective-eval-budgets
          [_ b-in-form] (eb '(do (require '[seon.code.mine :as mn]) (mn/mine-hygiene-findings db)) 100 1000)
          [_ b-fq] (eb '(seon.code.mine/mine-hygiene-findings db) 100 1000)]
      (is (>= b-in-form 1073741824))
      (is (= b-fq b-in-form))))
  (testing "ns-size-summary ranks namespaces by token count in O(N) without loading form source"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (concat
                                  (code/file-import-tx @conn "src/demo/small.clj" "(ns demo.small)\n(defn a [] 1)\n" false)
                                  (code/file-import-tx @conn "src/demo/big.clj" "(ns demo.big)\n(defn f [x] (+ x 1))\n(defn g [y] (* y 2))\n(defn h [z] (str z \"-extra-padding-text\"))\n" false))})
      (let [rows (rsearch/ns-size-summary @conn {:prefix "demo."})]
        (is (= ['demo.big 'demo.small] (mapv :ns rows)))
        (is (> (:tokens (first rows)) (:tokens (second rows))))
        (is (= 3 (:vars (first rows)))))))
  (testing "ask! and call! Malli schemas accept both opts maps and variadic keyword args"
    (doseq [v [#'fcalls/ask! #'fcalls/call!]]
      (let [sch (:malli/schema (meta v))]
        (is (some? sch))
        (is (= [:cat :keyword :any [:* :any]] (second sch)))))))

(deftest wave10e-live-repl-source-budget-override-test
  (testing "effective-eval-budgets resolves live :seon.repl/source metadata and grants 512MB to deftest forms"
    (let [eb #'seon.code/effective-eval-budgets
          _ (intern (create-ns 'seon.tmp-budget-src) (with-meta 'heavy-fn {:seon.eval/budget-override {:ticks 999999 :bytes 2147483648}}) (fn [] :ok))
          _ (intern (create-ns 'seon.tmp-budget-wrap) (with-meta 'heavy-fn {:seon.repl/source 'seon.tmp-budget-src/heavy-fn :seon.eval/budget-override {:ticks 10 :bytes 100}}) (fn [] :ok))]
      (try
        (let [[t b] (eb '(seon.tmp-budget-wrap/heavy-fn) 50 500)
              [_ db-b] (eb '(deftest foo-test (is (= 1 1))) 50 500)]
          (is (= 999999 t))
          (is (= 2147483648 b))
          (is (>= db-b 536870912)))
        (finally
          (remove-ns 'seon.tmp-budget-wrap)
          (remove-ns 'seon.tmp-budget-src)))))
  (testing "set-meta! rejects non-map batch entries and live fn objects in :malli/schema upfront"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/m.clj" "(ns demo.m)\n(defn f [x] (inc x))\n" false)})
      (binding [fcalls/*agent-ctx* {:conn conn :agent-id :repl/t :branch :main}]
        (let [r-bad-batch (seon.code.edit/set-meta! {:target 'demo.m/f :malli/schema [:=> [:cat :int] :int]})
              r-bad-fn (seon.code.edit/set-meta! 'demo.m/f {:malli/schema [:=> [:cat pos-int?] :int]})]
          (is (false? (:ok? r-bad-batch)))
          (is (= :invalid-batch-map (:error r-bad-batch)))
          (is (false? (:ok? r-bad-fn)))
          (is (= :unquoted-fn-in-schema (:error r-bad-fn))))))))

(deftest wave11b-grep-int-limit-clip-n1-and-test-worker-parity-test
  (testing "seon.render.text/clip enforces <= n length even at n=1"
    (is (= "…" (seon.render.text/clip "abc" 1)))
    (is (= "a" (seon.render.text/clip "a" 1)))
    (is (= "hello…" (seon.render.text/clip "hello world" 6))))
  (testing "seon.repl.search/grep-src accepts integer 2nd arg as :limit instead of throwing Long->Named"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/g.clj" "(ns demo.g)\n(defn alpha [] :needle)\n(defn beta [] :needle)\n(defn gamma [] :needle)\n" false)})
      (binding [fcalls/*agent-ctx* {:conn conn :agent-id :repl/t :branch :main}]
        (let [hits (rsearch/grep-src "needle" 2)]
          (is (= 2 (count hits)))))))
  (testing "enrich-eval-error hints schema/validate and pprint"
    (is (str/includes? (seon.code.errors/enrich-eval-error "(schema/validate s x)" "No such var: schema/validate" {}) "malli.core/validate"))
    (is (str/includes? (seon.code.errors/enrich-eval-error "(pprint x)" "Unable to resolve symbol: pprint" {}) "clojure.pprint"))))

(deftest wave12b-reconciler-merge-before-deliver-and-test-user-db-guard-test
  (testing "add-fn! rejects (user/db) inside *-test namespaces upfront before COW worker failure"
    (with-test-store [{:keys [conn]}]
      (binding [fcalls/*agent-ctx* {:conn conn :agent-id :repl/t :branch :main}]
        (let [bad (seon.code.edit/add-fn! 'demo.foo-test "(deftest bad-t (is (some? (user/db))))")
              good (seon.code.edit/add-fn! 'demo.foo-test "(deftest good-t (is (= 2 (+ 1 1))))")]
          (is (false? (:ok? bad)))
          (is (= :repl-helper-in-test-ns (:error bad)))
          (is (true? (:ok? good)))))))
  (testing "reconciler holds resolved call delivery while edited callee is :testing/:merging and releases once :merged"
    (with-test-store [{:keys [conn]}]
      (let [cid (random-uuid)
            _ (d/transact conn {:tx-data (concat
                                          (code/file-import-tx @conn "src/demo/x.clj" "(ns demo.x)\n(defn f [x] x)\n" false)
                                          [{:seon.agent/id :agent/root :seon.agent/branch :main :seon.agent/awaiting #{cid}}
                                           {:seon.agent/id :ns/demo.x :seon.agent/branch :branch/ns.demo.x}
                                           {:seon.agent.call/id cid :seon.agent.call/caller :agent/root :seon.agent.call/callee :ns/demo.x
                                            :seon.agent.call/status :resolved :seon.agent.call/delivered? false
                                            :seon.agent.call/prompt "edit" :seon.agent.call/result {:ok? true}}])})
            _ (store/branch! conn :main :branch/ns.demo.x)
            bconn (d/connect (assoc (:config @conn) :branch :branch/ns.demo.x))]
        (try
          (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/demo/x.clj" "(ns demo.x)\n(defn f [x] (inc x))\n" false)})
          (let [mdb @conn
                st (:store mdb)]
            (is (true? (seon.reconciler.calls/callee-merge-in-flight? mdb st {:ns/demo.x {:branch :branch/ns.demo.x :status :testing}} cid)))
            (is (true? (seon.reconciler.calls/callee-merge-in-flight? mdb st {:ns/demo.x {:branch :branch/ns.demo.x :status :merging}} cid)))
            (is (false? (seon.reconciler.calls/callee-merge-in-flight? mdb st {:ns/demo.x {:branch :branch/ns.demo.x :status :merged}} cid)))
            (is (false? (seon.reconciler.calls/callee-merge-in-flight? mdb st {:ns/demo.x {:branch :branch/ns.demo.x :status :gate-blocked}} cid)))
            (is (= #{:agent/root} (seon.reconciler.calls/callers-awaiting-callees mdb #{:ns/demo.x}))))
          (finally (d/release bconn)))))))

(deftest wave13b-bplus-await-merge-sync-pprint-and-error-hints-test
  (testing "call-delivery-state holds awaiting-blocked? until delivered and flags stale already-delivered awaits"
    (with-test-store [{:keys [conn]}]
      (let [cid1 (random-uuid)
            cid2 (random-uuid)]
        (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/branch :main :seon.agent/awaiting #{cid1}}
                                    {:seon.agent/id :ns/demo.w :seon.agent/branch :branch/ns.demo.w :seon.agent/auto-merge? true}
                                    {:seon.agent.call/id cid1 :seon.agent.call/caller :agent/root :seon.agent.call/callee :ns/demo.w
                                     :seon.agent.call/status :resolved :seon.agent.call/delivered? false}]})
        (let [mdb @conn
              ma (d/pull mdb '[:seon.agent/awaiting] [:seon.agent/id :agent/root])
              ds (seon.reconciler.calls/call-delivery-state mdb :agent/root ma #{})]
          (is (true? (:awaiting-blocked? ds)))
          (is (= 1 (:awaiting-count ds)))
          (is (= [cid1] (:awaiting-ready-to-deliver ds))))
        (d/transact conn {:tx-data [{:seon.agent.call/id cid1 :seon.agent.call/delivered? true}
                                    {:seon.agent/id :agent/root :seon.agent/awaiting #{cid2}}
                                    {:seon.agent.call/id cid2 :seon.agent.call/caller :agent/root :seon.agent.call/callee :ns/demo.w
                                     :seon.agent.call/status :resolved :seon.agent.call/delivered? true}]})
        (let [mdb @conn
              ma (d/pull mdb '[:seon.agent/awaiting] [:seon.agent/id :agent/root])
              ds (seon.reconciler.calls/call-delivery-state mdb :agent/root ma #{})]
          (is (false? (:awaiting-blocked? ds)))
          (is (= #{cid1 cid2} (set (:awaiting-stale-to-clear ds))))))))
  (testing "handle-settle-calls! clears stale awaiting IDs even when 0 undelivered calls remain"
    (with-test-store [{:keys [conn]}]
      (let [cid (random-uuid)
            replies (atom [])]
        (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/branch :main :seon.agent/awaiting #{cid}}
                                    {:seon.agent/id :ns/demo.s :seon.agent/branch :main}
                                    {:seon.agent.call/id cid :seon.agent.call/caller :agent/root :seon.agent.call/callee :ns/demo.s
                                     :seon.agent.call/status :resolved :seon.agent.call/delivered? true}]})
        (let [[_ outs] (seon.transactor.calls/handle-call-op!
                         {:conn conn :msg-seq 0 :writes 0}
                         {:op :settle-calls :caller :agent/root :call-ids [cid] :clear-awaiting? true}
                         (d/commit-id @conn)
                         {:reply! (fn [_ r] (swap! replies conj r))
                          :db-notice (fn [p db ds why] {:prev p :tx (:max-tx db) :ds ds :why why})})]
          (is (= 1 (:cleared (first @replies))))
          (is (seq (:db outs)))
          (is (empty? (:seon.agent/awaiting (d/pull @conn '[:seon.agent/awaiting] [:seon.agent/id :agent/root]))))))))
  (testing "handle-await-calls! suspends on resolved calls whose callee branch still has unmerged code edits"
    (with-test-store [{:keys [conn cfg]}]
      (let [cid (random-uuid)
            replies (atom [])]
        (seon.store/branch! conn :main :branch/ns.demo.m)
        (let [bconn (seon.store/connect cfg :branch/ns.demo.m)]
          (try
            (d/transact bconn {:tx-data [{:seon.code.form/key "demo.m/f" :seon.code.form/ns 'demo.m
                                          :seon.code.form/order 1 :seon.code.form/src "(defn f [] 1)"}]})
            (finally (d/release bconn))))
        (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/branch :main}
                                    {:seon.agent/id :ns/demo.m :seon.agent/branch :branch/ns.demo.m :seon.agent/auto-merge? true}
                                    {:seon.agent.call/id cid :seon.agent.call/caller :agent/root :seon.agent.call/callee :ns/demo.m
                                     :seon.agent.call/status :resolved :seon.agent.call/delivered? false}]})
        (seon.transactor.calls/handle-call-op!
          {:conn conn :msg-seq 0 :writes 0}
          {:op :await-calls :caller :agent/root :call-ids [cid]}
          (d/commit-id @conn)
          {:reply! (fn [_ r] (swap! replies conj r))
           :db-notice (fn [p db ds why] {:prev p :tx (:max-tx db) :ds ds :why why})})
        (is (false? (:settled? (first @replies))))
        (is (= [cid] (:awaiting (first @replies))))
        (is (contains? (set (:seon.agent/awaiting (d/pull @conn '[:seon.agent/awaiting] [:seon.agent/id :agent/root]))) cid)))))
  (testing "enrich-eval-error hints get-else nil default and bare user/* helper names; pprint is exported"
    (let [h1 (seon.code.errors/enrich-eval-error "(d/q ...)" "get-else: nil default value is not supported" {})
          h2 (seon.code.errors/enrich-eval-error "(add-fn! 'a '(defn x [] 1))"
               "Unable to resolve symbol: add-fn! in this context (did you mean add-tap?)"
               {"add-fn!" {:arglists "([ns-sym form])" :doc "Adds a form."}})]
      (is (clojure.string/includes? h1 "non-nil sentinel"))
      (is (clojure.string/includes? h2 "user/add-fn!")))))

(deftest wave14b-media-image-validation-and-wire-sanitizer-test
  (testing "extract-recent-eval-images ignores schema/form metadata maps and bogus non-image media examples"
    (let [bad-schema-val (pr-str {:seon.schema/key :seon.media/image
                                  :seon.schema/examples [{:seon.media/mime "3w" :seon.media/base64 "Kt" :seon.media/width -1 :seon.media/height -1}]})
          bad-inline-val (pr-str {:seon.media/mime "" :seon.media/base64 "y" :seon.media/width 0 :seon.media/height 0})
          good-inline-val (pr-str {:seon.media/mime "image/png" :seon.media/base64 "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==" :seon.media/width 1 :seon.media/height 1})]
      (is (empty? (seon.model.media/extract-recent-eval-images nil [{:calls [{:value bad-schema-val}]}])))
      (is (empty? (seon.model.media/extract-recent-eval-images nil [{:calls [{:value bad-inline-val}]}])))
      (is (= 1 (count (seon.model.media/extract-recent-eval-images nil [{:calls [{:value good-inline-val}]}]))))))
  (testing "sanitize-wire-image-parts strips synthetic/corrupt base64 data URLs while preserving valid PNG/JPEG images"
    (let [png-url "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
          bad-msgs [{:role "user" :content [{:type "text" :text "Check"}
                                            {:type "image_url" :image_url {:url "data:image/jpeg;base64,y"}}]}]
          good-msgs [{:role "user" :content [{:type "text" :text "Check"}
                                             {:type "image_url" :image_url {:url png-url}}]}]]
      (is (= [{:role "user" :content "Check"}] (seon.model/sanitize-wire-image-parts bad-msgs)))
      (is (= good-msgs (seon.model/sanitize-wire-image-parts good-msgs)))
      (is (true? (seon.model/multimodal-unsupported-error? 400 "{\"error\":{\"message\":\".messages[17].content[2]: You have uploaded an unsupported\"}}"))))))

(deftest wave15b-bounce-diagnostics-and-char-map-entry-hint-test
  (testing "enrich-eval-error hints Character->Map$Entry when calling keys on user/source string"
    (let [h (seon.code.errors/enrich-eval-error "(keys (user/source 'seon.repl.search))"
              "class java.lang.Character cannot be cast to class java.util.Map$Entry" {})]
      (is (clojure.string/includes? h "user/ns-outline"))))
  (testing "trial-audit-summary surfaces :bounce-messages from main-db after :after-main-tx"
    (with-test-store [{:keys [conn]}]
      (let [t0 (:max-tx @conn)]
        (d/transact conn {:tx-data [{:seon.agent/id :w/bounce-test :seon.agent/branch :main}
                                    {:seon.agent.message/id (random-uuid) :seon.agent.message/seq 1
                                     :seon.agent.message/from :transactor :seon.agent.message/to :w/bounce-test
                                     :seon.agent.message/type :merge-conflict :seon.agent.message/body "3-way merge conflict on :branch/x"}]})
        (let [res (seon.bench.helpers/trial-audit-summary conn @conn :w/bounce-test {:after-main-tx t0})]
          (is (= 1 (count (:bounce-messages res))))
          (is (= :merge-conflict (:type (first (:bounce-messages res))))))))))

(deftest wave16b-turn0-callees-examples-quote-parity-and-tuner-merged-test
  (testing "set-defn-meta-src quotes symbol-bearing :examples so source compiles without Unable to resolve symbol"
    (let [src "(defn f [q] q)"
          res (seon.code.cst.ns/set-defn-meta-src src {:malli/schema [:=> {:examples [[[[':find '?e]] {:find ['?e]}]]} [:cat :any] :map]})]
      (is (:ok? res))
      (is (clojure.string/includes? (:src res) "(quote"))
      (let [form (read-string (:src res))
            attr (nth form 2)]
        (is (= [[[:find '?e]] {:find ['?e]}] (first (:examples (second (:malli/schema (eval attr))))))))))
  (testing "add-turn0-var-neighborhood! surfaces delegation-target callees alongside callers"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:db/id "target" :seon.code.var/name 'demo.ns.impl/helper :seon.code.var/ns 'demo.ns.impl}
                                  {:db/id "wrap" :seon.code.var/name 'demo.ns/helper :seon.code.var/ns 'demo.ns :seon.code.var/calls #{"target"}}]})
      (let [db @conn
            we (:e (first (d/datoms db :avet :seon.code.var/name 'demo.ns/helper)))
            te (:e (first (d/datoms db :avet :seon.code.var/name 'demo.ns.impl/helper)))
            seen (atom [])]
        (seon.ctx.recall/add-turn0-var-neighborhood! db (fn [e pri] (swap! seen conj [e pri])) we)
        (is (some #(= te (first %)) @seen))))))

(deftest wave17b-error-hints-and-health-splits-test
  (testing "enrich-eval-error hints macro-as-value, standalone thrown?, and malli.registry contains?"
    (is (clojure.string/includes? (seon.code.errors/enrich-eval-error "" "Can't take value of a macro: #'clojure.core/ns" {}) "quote"))
    (is (clojure.string/includes? (seon.code.errors/enrich-eval-error "" "Unable to resolve symbol: thrown? in this context" {}) "clojure.test/is"))
    (is (clojure.string/includes? (seon.code.errors/enrich-eval-error "" "contains? not supported on type: malli.registry$composite_registry" {}) "entity-index")))
  (testing "split health child namespaces keep full public re-export parity"
    (is (fn? seon.health.coverage/rule-atlas))
    (is (fn? seon.health.coverage/lint-rule-generality))
    (is (fn? seon.health.static/form-meta-snapshot))
    (is (fn? seon.health.static/used-before-defined))))

(deftest wave20b-o1-changed-ops-run-tests-cache-and-verify-reach-test
  (testing "changed-form-ops-aevt matches d/changes path for upserts, retracts, schemas, rules, and caches cleanly"
    (seon.test-fixture/with-test-store [{:keys [conn]}]
      (let [c0 (d/commit-id @conn)
            _ (d/transact conn {:tx-data (seon.code/file-import-tx @conn "src/demo/reach_aevt.clj"
                                           "(ns demo.reach-aevt)\n(defn f [x] (inc x))" false)})
            c1 (d/commit-id @conn)
            from-db (d/commit-as-db conn c0)
            to-db @conn
            via-aevt (#'seon.code.reach/changed-form-ops-aevt from-db to-db)
            via-ops (seon.code.reach/changed-form-ops conn c0 c1)
            via-cached (seon.code.reach/changed-form-ops conn c0 c1)]
        (is (= (set (map :seon.code.form/key (:upserts via-ops)))
               (set (map :seon.code.form/key (:upserts via-aevt)))))
        (is (= (:retracted-keys via-ops) (:retracted-keys via-aevt)))
        (is (= (:schema-keys via-ops) (:schema-keys via-aevt)))
        (is (= (:rule-ids via-ops) (:rule-ids via-aevt)))
        (is (= via-ops via-cached)))))
  (testing "verify-task-spec :ns-test-or-example credits new tests reaching a child ns through a parent re-export"
    (seon.test-fixture/with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (seon.code/file-import-tx @conn "src/demo/child.clj"
                                   "(ns demo.child)\n(defn helper [x] (inc x))" false)})
      (d/transact conn {:tx-data (seon.code/file-import-tx @conn "src/demo/parent.clj"
                                   "(ns demo.parent (:require [demo.child :as c]))\n(defn helper [x] (c/helper x))" false)})
      (let [t0 (:max-tx @conn)]
        (is (false? (:ok? (seon.bench.sampler/verify-task-spec @conn {:kind :ns-test-or-example :ns 'demo.child :after-main-tx t0}))))
        (d/transact conn {:tx-data (seon.code/file-import-tx @conn "test/demo/parent_test.clj"
                                     "(ns demo.parent-test (:require [clojure.test :refer [deftest is]] [demo.parent :as p]))\n(deftest via-reexport-test (is (= 2 (p/helper 1))))" false)})
        (let [vr (seon.bench.sampler/verify-task-spec @conn {:kind :ns-test-or-example :ns 'demo.child :after-main-tx t0})]
          (is (true? (:ok? vr)))
          (is (some #{'demo.parent-test/via-reexport-test} (:tests vr)))))))
  (testing "Wave 20A static error hints fire cleanly"
    (is (clojure.string/includes? (seon.code.errors/enrich-eval-error "" "class malli.registry$composite_registry$reify__1 cannot be cast to class clojure.lang.IFn" {}) "malli.core/schema"))
    (is (clojure.string/includes? (seon.code.errors/enrich-eval-error "" "No matching clause: :ave" {}) ":avet"))
    (is (clojure.string/includes? (seon.code.errors/enrich-eval-error "" "Lookup ref should contain 2 elements: [12345]" {}) "never `[eid]`"))))