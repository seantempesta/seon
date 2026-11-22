(ns seon.datalog-rules-core-test
  "Comprehensive unit, incremental-skip, monotonic-pull-union, zero-repetition-delta,
   10-native-context-rule, generic-2-read-discovery, related-updates, and seed=42 generative
   proofs for Stage 2's native Datahike `d/q` + `d/pull` + `datahike.reads` rule engine."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.datom :as datom]
            [datahike.reads :as reads]
            [seon.code :as code]
            [seon.code.files :as files]
            [seon.code.graph :as graph]
            [seon.ctx :as ctx]
            [seon.ctx.project]
            [seon.error :as error]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.store.query :as sq]
            [seon.test-fixture :refer [with-test-store]]))

(deftest ^{:cost/ticks 45000000} incremental-skip-reads-forwarding-and-recursive-rules-test
  (testing "Native d/q step-rules skips 100% on unrelated datoms, re-fires ONLY error rule on error tx, forwards skipped reads, and evaluates recursive % rules"
    (with-test-store [{:keys [conn]}]
      (let [src-a "(ns demo.rec-a)\n(defn leaf [x] (inc x))\n(defn mid [x] (leaf x))\n(defn top [x] (mid x))\n"
            _ (d/transact conn {:tx-data (concat
                                          (code/file-import-tx @conn "src/demo/rec_a.clj" src-a false)
                                          [{:seon.task/id :demo/t3 :seon.task/title "Leaf task" :seon.task/done? false}
                                           {:seon.task/id :demo/t2 :seon.task/title "Mid task" :seon.task/needs #{:demo/t3} :seon.task/done? false}
                                           {:seon.task/id :demo/t1 :seon.task/title "Top task" :seon.task/needs #{:demo/t2} :seon.task/done? false}
                                           {:seon.agent/id :ag/inc-test :seon.agent/branch :main :seon.agent/role :worker
                                            :seon.agent/task :demo/t1}])})
            _ (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn)})
            db1 @conn
            ctx-rs (filterv #(= :context (:seon.rule/category %)) (rules/load-rules db1 (rseed/starter-rules)))
            s1 (rules/step-rules nil {:main db1 :branch db1 :code db1 :task db1} :ag/inc-test ctx-rs nil :wire)
            unrelated [(datom/->Datom 999999 :seon.code.doc/order 7 (:max-tx db1) true)]
            [s2 outer-rs] (reads/with-reads
                            #(rules/step-rules s1 {:main db1 :branch db1 :code db1 :task db1} :ag/inc-test ctx-rs unrelated :wire))]
        ;; 1. Unrelated datom -> 0 recomputed, 100% skipped, AND outer reads STILL forwarded!
        (is (= (count ctx-rs) (:recomputed s1)))
        (is (= 0 (:recomputed s2)))
        (is (= (count ctx-rs) (:skipped s2)))
        (is (pos? (count outer-rs)) "Skipped rules must still forward their recorded datahike.reads to outer recorder")
        (is (contains? (reads/reads-index outer-rs) [:av :seon.agent/id :ag/inc-test]))

        ;; 2. Mutating 1 :seon.error/* entity re-fires ONLY :rule.ctx/fault-with-recommended-fix!
        (let [err (error/error-tx {:kind :demo/boom :source :eval :agent :ag/inc-test :message "Boom!"})
              txr (d/transact conn {:tx-data [err]})
              db2 (:db-after txr)
              s3 (rules/step-rules s2 {:main db2 :branch db2 :code db2 :task db2} :ag/inc-test ctx-rs (:tx-data txr) :wire)
              recomp-ids (vec (keep (fn [[rid run]] (when-not (:skipped? run) rid)) (:runs s3)))]
          (is (= [:rule.ctx/fault-with-recommended-fix] recomp-ids))
          (is (= 1 (:recomputed s3)))
          (is (= (dec (count ctx-rs)) (:skipped s3))))

        ;; 3. Recursive calls* and needs* work natively via % (even when :in omits default $)
        (let [rec-rules [{:seon.rule/id :r/test-calls-star :seon.rule/stratum 10 :seon.rule/enabled? true
                          :seon.rule/query '[:find ?callee :in $branch %
                                             :where [$branch ?top :seon.code.var/name demo.rec-a/top]
                                                    (calls* ?top ?callee)]
                          :seon.rule/assert '[[?callee :test/reachable true]]}
                         {:seon.rule/id :r/test-needs-star :seon.rule/stratum 10 :seon.rule/enabled? true
                          :seon.rule/query '[:find ?pre :in $branch %
                                             :where [$branch ?t1 :seon.task/id :demo/t1]
                                                    (needs* ?t1 ?pre)]
                          :seon.rule/assert '[[?pre :test/prereq true]]}]
              rr (rules/step-rules nil {:main db1 :branch db1} :ag/inc-test rec-rules nil :wire)
              leaf-eid (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.rec-a/leaf)))
              t3-eid (:e (first (d/datoms db1 :avet :seon.task/id :demo/t3)))]
          (is (true? (rules/wm-scalar (:wm rr) leaf-eid :test/reachable)))
          (is (true? (rules/wm-scalar (:wm rr) t3-eid :test/prereq))))

        ;; 4. seon.store.query/fallback-datoms uses :avet for :db.type/ref on AsOfDB
        (let [asof (d/as-of db1 (:max-tx db1))
              leaf-eid (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.rec-a/leaf)))
              [ds rs] (reads/with-reads #(sq/slice-datoms asof [nil :seon.code.var/calls leaf-eid] {:limit 5}))]
          (is (= 1 (count ds)))
          (is (contains? (reads/reads-index rs) [:av :seon.code.var/calls leaf-eid])))))))

(deftest ^{:cost/ticks 35000000} monotonic-pull-union-and-shown-delta-test
  (testing "merge-pull-patterns is commutative, associative, idempotent, and recursively merges nested ref pulls"
    (let [p1 [:seon.code.var/doc {:seon.code.var/calls [:seon.code.var/name]}]
          p2 [:seon.code.var/name :seon.code.var/schema {:seon.code.var/calls [:seon.code.var/doc :seon.code.var/arglists]}]
          p3 [:seon.code.var/doc :seon.code.var/ns]
          ab (ctx/merge-pull-patterns [p1 p2])
          ba (ctx/merge-pull-patterns [p2 p1])
          abc1 (ctx/merge-pull-patterns [(ctx/merge-pull-patterns [p1 p2]) p3])
          abc2 (ctx/merge-pull-patterns [p1 (ctx/merge-pull-patterns [p2 p3])])
          idem (ctx/merge-pull-patterns [ab ab])
          with-star (ctx/merge-pull-patterns [p1 ['*]])]
      (is (= ab ba) "merge-pull-patterns must be commutative")
      (is (= abc1 abc2) "merge-pull-patterns must be associative")
      (is (= ab idem) "merge-pull-patterns must be idempotent")
      (is (= [:seon.code.var/doc :seon.code.var/name :seon.code.var/schema
              {:seon.code.var/calls [:seon.code.var/arglists :seon.code.var/doc :seon.code.var/name]}]
             ab))
      (is (= ['* {:seon.code.var/calls [:seon.code.var/name]}] with-star))))

  (testing "compute-shown-delta handles :new, :unchanged (0 tokens), :upgraded (set/difference + id), and :rewarmed (newer ?tx or content mutation)"
    (let [p-turn1 [{:entity 101 :data {:db/id 101 :seon.code.var/name 'demo/f :seon.code.var/doc "v1"} :tx 10}]
          d1 (ctx/compute-shown-delta {} p-turn1)
          shown1 (:shown d1)
          ;; Turn 2: same entity, subset of keys, same tx -> :unchanged (empty delta-data!)
          d2 (ctx/compute-shown-delta shown1 [{:entity 101 :data {:db/id 101 :seon.code.var/name 'demo/f} :tx 10}])
          ;; Turn 3: upgraded with new key :seon.code.var/schema at same tx -> :upgraded (ONLY new key + identity!)
          d3 (ctx/compute-shown-delta (:shown d2)
                                      [{:entity 101 :data {:db/id 101 :seon.code.var/name 'demo/f :seon.code.var/doc "v1" :seon.code.var/schema '[:=> [:cat :int] :int]} :tx 10}])
          ;; Turn 4: entity fades out (not in projections) -> still remembered in :shown!
          d4 (ctx/compute-shown-delta (:shown d3) [])
          ;; Turn 5: entity re-touched at higher native ?tx 25 -> :rewarmed with FULL wanted keys!
          d5 (ctx/compute-shown-delta (:shown d4)
                                      [{:entity 101 :data {:db/id 101 :seon.code.var/name 'demo/f :seon.code.var/doc "v1"} :tx 25}])
          ;; Turn 6: content hash changes even at same tx -> :rewarmed with FULL wanted keys!
          d6 (ctx/compute-shown-delta (:shown d5)
                                      [{:entity 101 :data {:db/id 101 :seon.code.var/name 'demo/f :seon.code.var/doc "v2-edited"} :tx 25}])]
      (is (= :new (get-in d1 [:projections 0 :status])))
      (is (= :unchanged (get-in d2 [:projections 0 :status])))
      (is (= {} (get-in d2 [:projections 0 :delta-data])))
      (is (= :upgraded (get-in d3 [:projections 0 :status])))
      (is (= #{:seon.code.var/schema} (get-in d3 [:projections 0 :delta-keys])))
      (is (= {:db/id 101 :seon.code.var/name 'demo/f :seon.code.var/schema '[:=> [:cat :int] :int]}
             (get-in d3 [:projections 0 :delta-data])))
      (is (contains? (:shown d4) 101) "Faded entity stays tracked in shown")
      (is (= :rewarmed (get-in d5 [:projections 0 :status])))
      (is (= #{:seon.code.var/name :seon.code.var/doc} (get-in d5 [:projections 0 :delta-keys])))
      (is (= :rewarmed (get-in d6 [:projections 0 :status])))
      (is (= "v2-edited" (get-in d6 [:projections 0 :delta-data :seon.code.var/doc]))))))

(deftest ^{:cost/ticks 90000000} all-10-native-datalog-context-rules-test
  (testing "All 10 high-value :rule.ctx/* rules surface errors+fixes, branch-edited vars+callers, eval schemas+compatible fns, citing docs (signpost vs full), failing tests, task lineage, eval recency, and pinned entities"
    (with-test-store [{:keys [conn cfg]}]
      ;; Register a domain schema for eval-compatible-fns + eval-touched-schemas
      (let [reg! (fn [k form doc]
                   (let [{:keys [dh-decls entity]} (schema/validate-schema-registration! @conn {:key k :form form :doc doc})]
                     (d/transact conn {:tx-data (vec (concat dh-decls [entity]))})))]
        (reg! :seon.demo.widget/id [:and {:seon.db/identity true} :keyword] "Widget ID")
        (reg! :seon.demo.widget/entity [:map {:seon.db/attributes true} [:seon.demo.widget/id :seon.demo.widget/id]] "Widget entity"))
      ;; Seed code (target + direct caller + compatible consumer + private fn + test fn), docs, tasks, fix, error, agent on :main
      (let [src-lib (str "(ns demo.wlib)\n"
                         "(defn core-op \"Core op\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                         "(defn caller-op \"Direct caller\" {:malli/schema [:=> [:cat :int] :int]} [x] (core-op x))\n"
                         "(defn inspect-widget \"Public consumer\" {:malli/schema [:=> [:cat :seon.demo.widget/entity] :keyword]} [w] (:seon.demo.widget/id w))\n"
                         "(defn- secret-widget \"Private consumer\" {:malli/schema [:=> [:cat :seon.demo.widget/entity] :keyword]} [w] (:seon.demo.widget/id w))\n")
            src-test (str "(ns demo.wlib-test (:require [clojure.test :refer [deftest is]] [demo.wlib :as w]))\n"
                          "(deftest core-op-test (is (= 2 (w/core-op 1))))\n")
            _ (d/transact conn {:tx-data (concat
                                          (code/file-import-tx @conn "src/demo/wlib.clj" src-lib false)
                                          (code/file-import-tx @conn "test/demo/wlib_test.clj" src-test false))})
            _ (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn)})
            db0 @conn
            wsch-eid (:e (first (d/datoms db0 :avet :seon.schema/key :seon.demo.widget/entity)))
            core-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.wlib/core-op)))
            test-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.wlib-test/core-op-test)))
            err-ent (error/error-tx {:kind :demo/oom-class :source :eval :agent :ag/full :message "Out of memory"})
            fix-ent {:seon.fix/id (random-uuid) :seon.fix/error-class :demo/oom-class
                     :seon.fix/symptom "OOM on big scan" :seon.fix/root-cause "Unindexed scan"
                     :seon.fix/change-summary "Switch to :avet lookup"}
            _ (d/transact conn {:tx-data
                                [err-ent fix-ent
                                 {:seon.code.doc/id "docs/w-one.md#single" :seon.code.doc/path "docs/w-one.md"
                                  :seon.code.doc/order 0 :seon.code.doc/heading "Single Hit Doc"
                                  :seon.code.doc/body "Explains `:seon.demo.widget/entity` (BODY-SINGLE-SECRET)." :seon.code.doc/references #{wsch-eid}}
                                 {:seon.code.doc/id "docs/w-two.md#converge" :seon.code.doc/path "docs/w-two.md"
                                  :seon.code.doc/order 0 :seon.code.doc/heading "Converging Doc"
                                  :seon.code.doc/body "BODY-CONVERGE-FULL" :seon.code.doc/references #{wsch-eid core-eid}}
                                 {:seon.task/id :demo/pre :seon.task/title "Prereq Task" :seon.task/done? false}
                                 {:seon.task/id :demo/main-task :seon.task/title "Main Task" :seon.task/needs #{:demo/pre} :seon.task/done? false}
                                 {:seon.demo.widget/id :w/pinned-one}
                                 {:seon.agent/id :ag/full :seon.agent/branch :branch/full :seon.agent/role :worker
                                  :seon.agent/task :demo/main-task
                                  :seon.agent/pinned [[:seon.demo.widget/id :w/pinned-one]]}]})]
        ;; Fork :branch/full, edit core-op, record old + recent evals with :result-schemas, and record a failing reach test!
        (store/branch! conn :main :branch/full)
        (let [bconn (store/connect cfg :branch/full)]
          (try
            (let [src-lib-v2 (str/replace src-lib "(inc x)" "(+ x 2)")]
              (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/demo/wlib.clj" src-lib-v2 false)})
              (d/transact bconn {:tx-data (graph/backfill-codebase-analysis-tx @bconn)})
              (d/transact bconn {:tx-data
                                 [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/full
                                   :seon.agent.eval/turn 1 :seon.agent.eval/handle 'result/e1
                                   :seon.agent.eval/intent "old probe" :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/value "2"}
                                  {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/full
                                   :seon.agent.eval/turn 5 :seon.agent.eval/handle 'result/e5
                                   :seon.agent.eval/code "{:seon.demo.widget/id :w/one}" :seon.agent.eval/value "{:seon.demo.widget/id :w/one}"
                                   :seon.agent.eval/result-schemas #{wsch-eid}}
                                  {:seon.test/digest "dg-fail-1234" :seon.test/var 'demo.wlib-test/core-op-test
                                   :seon.test/var-ref test-eid :seon.test/kind :deftest
                                   :seon.test/pass? false :seon.test/ms 2.0 :seon.test/report "expected 2 got 3"}]}))
            (let [mdb @conn
                  bdb @bconn
                  res (ctx/related-updates {:seon.db/main mdb :seon.db/branch bdb :seon.agent/id :ag/full
                                            :seon.view/target :wire :seon.view/token-budget 32000})
                  projs (:seon.ctx/projections res)
                  by-ent (into {} (map (juxt :entity identity)) projs)
                  fired (into #{} (map :rule-id) (get-in res [:seon.ctx/trace :fired-rules]))
                  err-eid (:e (first (d/datoms mdb :avet :seon.error/id (:seon.error/id err-ent))))
                  fix-eid (:e (first (d/datoms mdb :avet :seon.fix/id (:seon.fix/id fix-ent))))
                  caller-eid (:e (first (d/datoms bdb :avet :seon.code.var/name 'demo.wlib/caller-op)))
                  inspect-eid (:e (first (d/datoms bdb :avet :seon.code.var/name 'demo.wlib/inspect-widget)))
                  secret-eid (:e (first (d/datoms bdb :avet :seon.code.var/name 'demo.wlib/secret-widget)))
                  doc1-eid (:e (first (d/datoms bdb :avet :seon.code.doc/id "docs/w-one.md#single")))
                  doc2-eid (:e (first (d/datoms bdb :avet :seon.code.doc/id "docs/w-two.md#converge")))
                  fail-eid (:e (first (d/datoms bdb :avet :seon.test/digest "dg-fail-1234")))
                  pre-eid (:e (first (d/datoms bdb :avet :seon.task/id :demo/pre)))
                  pin-eid (:e (first (d/datoms bdb :avet :seon.demo.widget/id :w/pinned-one)))
                  ev1-eid (:e (first (d/datoms bdb :avet :seon.agent.eval/handle 'result/e1)))
                  ev5-eid (:e (first (d/datoms bdb :avet :seon.agent.eval/handle 'result/e5)))]
              ;; ALL 10 high-value native rules fired!
              (is (= #{:rule.ctx/fault-with-recommended-fix :rule.ctx/active-task-and-lineage
                       :rule.ctx/pinned-entities :rule.ctx/recent-eval-results
                       :rule.ctx/edited-var-on-branch :rule.ctx/failing-reach-tests
                       :rule.ctx/eval-touched-schemas :rule.ctx/eval-compatible-fns
                       :rule.ctx/edited-var-callers :rule.ctx/citing-docs}
                     fired))
              ;; 1. Error + matching :seon.fix/* nested as :parent ?err
              (is (contains? by-ent err-eid))
              (is (= err-eid (:parent (get by-ent fix-eid))))
              ;; 2 & 3. Edited var has form child + callers get signature/doc/schema ONLY (no :form/src)
              (is (contains? by-ent core-eid))
              (is (some #(and (= core-eid (:parent %)) (contains? (:data %) :seon.code.form/src)) projs))
              (is (contains? by-ent caller-eid))
              (is (= false (get-in by-ent [caller-eid :params :show-source?])))
              (is (not (contains? (get-in by-ent [caller-eid :data]) :seon.code.form/src)))
              ;; 4 & 5. Eval schema + compatible public non-test fn (private excluded!)
              (is (contains? by-ent wsch-eid))
              (is (contains? by-ent inspect-eid))
              (is (not (contains? by-ent secret-eid)))
              ;; 6. Citing docs: 1 hit = signpost only (NO :body), 2 converging paths = full :body!
              (is (contains? by-ent doc1-eid))
              (is (not (contains? (get-in by-ent [doc1-eid :data]) :seon.code.doc/body)))
              (is
              (str/includes?
                (str (get-in by-ent [doc2-eid :data :seon.code.doc/body]))
                "BODY-CONVERGE-FULL"))
              ;; 7. Failing reach test + form source
              (is (= "expected 2 got 3" (get-in by-ent [fail-eid :data :seon.test/report])))
              (is (some #(and (= fail-eid (:parent %)) (contains? (:data %) :seon.code.form/src)) projs))
              ;; 8. Active task + prerequisite lineage
              (is (contains? by-ent pre-eid))
              ;; 9. Eval recency: turn 5 full in :ephemeral-tail, turn 1 1-line signpost in :workspace!
              (is (= :ephemeral-tail (:zone (get by-ent ev5-eid))))
              (is (contains? (get-in by-ent [ev5-eid :data]) :seon.agent.eval/code))
              (is (= :workspace (:zone (get by-ent ev1-eid))))
              (is (not (contains? (get-in by-ent [ev1-eid :data]) :seon.agent.eval/code)))
              ;; 10. Pinned entity discovered generically!
              (is (contains? by-ent pin-eid)))
            (finally
              (d/release bconn))))))))

(deftest ^{:cost/ticks 60000000} generic-2-read-discovery-budget-trace-diff-and-extract-refs-test
  (testing "Generic 2-read agent-world-datoms catches brand-new custom scalar + reverse :seon.db/to [:seon.agent/entity] ref, budget prunes cleanly, and REPL context-trace/diff-context work"
    (with-test-store [{:keys [conn]}]
      ;; Register brand-new custom scalar attr on agent + brand-new reverse ref pointing TO :seon.agent/entity!
      (let [reg! (fn [k form doc]
                   (let [{:keys [dh-decls entity]} (schema/validate-schema-registration! @conn {:key k :form form :doc doc})]
                     (d/transact conn {:tx-data (vec (concat dh-decls [entity]))})))]
        (reg! :seon.custom.lab/mood [:string {:seon.db/storable true}] "Custom agent mood")
        (reg! :seon.custom.lab/ticket-id [:and {:seon.db/identity true} :keyword] "Custom ticket id")
        (reg! :seon.custom.lab/assigned-agent [:and {:seon.db/to [:seon.agent/entity]} :seon.store/ref] "Reverse ref to agent")
        (reg! :seon.custom.lab/ticket [:map {:seon.db/attributes true}
                                       [:seon.custom.lab/ticket-id :seon.custom.lab/ticket-id]
                                       [:seon.custom.lab/assigned-agent :seon.custom.lab/assigned-agent]]
              "Custom ticket entity"))
      (d/transact conn {:tx-data [{:seon.agent/id :ag/generic :seon.agent/branch :main :seon.agent/role :worker
                                   :seon.custom.lab/mood "curious"}
                                  {:seon.custom.lab/ticket-id :tkt/num-42
                                   :seon.custom.lab/assigned-agent [:seon.agent/id :ag/generic]}]})
      (let [db @conn
            world (ctx/agent-world-datoms db db :ag/generic)
            tkt-eid (:e (first (d/datoms db :avet :seon.custom.lab/ticket-id :tkt/num-42)))
            ag-eid (:agent-eid world)
            res (ctx/related-updates {:seon.db/main db :seon.db/branch db :seon.agent/id :ag/generic
                                      :seon.view/target :wire :seon.view/token-budget 4000})
            by-ent (into {} (map (juxt :entity identity)) (:seon.ctx/projections res))
            ;; Tiny budget forces pruning of lower-priority blocks into :trace :dropped
            tight (ctx/related-updates {:seon.db/main db :seon.db/branch db :seon.agent/id :ag/generic
                                        :seon.view/target :wire :seon.view/token-budget 15})]
        ;; 1. Generic 2-read discovery caught BOTH brand-new custom scalar attr AND reverse ref entity!
        (is (some #(= :seon.custom.lab/mood (:a %)) (:forward world)))
        (is (some #{:seon.custom.lab/assigned-agent} (:reverse-attrs world)))
        (is (contains? (:connected world) tkt-eid))
        (is (= "curious" (get-in by-ent [ag-eid :data :seon.custom.lab/mood])))
        (is (= :tkt/num-42 (get-in by-ent [tkt-eid :data :seon.custom.lab/ticket-id])))

        ;; 2. Token budget pruning caps :seon.ctx/tokens <= budget and records :dropped!
        (is (<= (:seon.ctx/tokens tight) 15))
        (is (seq (get-in tight [:seon.ctx/trace :dropped])))

        ;; 3. REPL self-introspection helpers (user/context-trace & user/diff-context)
        (let [tmp-ns-sym 'seon.test.tmp-ctx-repl
              u (create-ns tmp-ns-sym)]
          (try
            (ctx/install-ctx-helpers! u (fn [] {:conn conn :branch :main :agent-id :ag/generic}))
            (let [tr-fn @(ns-resolve u 'context-trace)
                  df-fn @(ns-resolve u 'diff-context)
                  tr (tr-fn)
                  ctx-rs (filterv #(= :context (:seon.rule/category %)) (rules/load-rules db (rseed/starter-rules)))
                  no-pin (filterv #(not= :rule.ctx/pinned-entities (:seon.rule/id %)) ctx-rs)
                  diff (df-fn ctx-rs no-pin)]
              (is (= :ag/generic (:agent tr)))
              (is (pos? (count (:blocks tr))))
              (is (neg? (:tokens-delta diff)))
              (is (some #{tkt-eid} (:removed-entities diff))))
            (finally
              (remove-ns tmp-ns-sym))))

        ;; 4. extract-rule-refs extracts :seon.rule/schema and :seon.rule/fn from :seon.rule/query + projection :fn/:pull!
        (let [refs (files/extract-rule-refs
                    {:seon.rule/id :r/demo-proj
                     :seon.rule/query '[:find ?e :in $branch :where [$branch ?e :seon.error/status :open]]
                     :seon.rule/assert '[{:entity ?e :fn seon.error/render :pull [:seon.error/kind :seon.error/message]}]})]
          (is (set/subset? #{:seon.error/status :seon.error/kind :seon.error/message} (:schemas refs)))
          (is (contains? (:fns refs) 'seon.error/render)))))))

(deftest ^{:cost/ticks 60000000} stage2-pure-fns-seed42-generative-test
  (testing "All pure Stage 2 functions in seon.rule and seon.ctx.project pass 100-sample seed=42 Malli generative checks"
    (doseq [sym '[seon.rule/lvar?
                  seon.rule/parse-query
                  seon.rule/rule-wm-read-attrs
                  seon.rule/rule-db-keys
                  seon.rule/rule-uses-agent?
                  seon.rule/subst-template
                  seon.rule/diff-sessions
                  seon.ctx.project/merge-pull-patterns
                  seon.ctx.project/compute-shown-delta
                  seon.ctx.project/budget-and-order-projections]]
      (let [res (schema/stress-var nil sym {:samples 100 :seed 42})]
        (is (:ok? res) (str sym " failed seed=42 generative check: " (pr-str res)))))))