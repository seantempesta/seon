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
            [seon.test-fixture :refer [with-test-store]]
            [seon.render.fit :as fit]))

(deftest ^{:cost/ticks 55000000} incremental-skip-reads-forwarding-and-recursive-rules-test
  (testing "Native d/q step-rules skips 100% on unrelated datoms, re-fires ONLY error rule on error tx, forwards skipped reads, and evaluates recursive % rules"
    (with-test-store [{:keys [conn]}]
      (let [src-a "(ns demo.rec-a)\n(defn leaf [x] (inc x))\n(defn mid [x] (leaf x))\n(defn top [x] (mid x))\n"
            _ (d/transact conn {:tx-data (concat
                                          (code/file-import-tx @conn "src/demo/rec_a.clj" src-a false)
                                          [{:seon.task/id :demo/t3 :seon.task/title "Leaf task" :seon.task/done? false}
                                           {:seon.task/id :demo/t2 :seon.task/title "Mid task" :seon.task/needs #{:demo/t3} :seon.task/done? false}
                                           {:seon.task/id :demo/t1 :seon.task/title "Top task" :seon.task/needs #{:demo/t2} :seon.task/done? false}
                                           {:seon.agent/id :ag/inc-test :seon.agent/branch :main :seon.agent/tasks #{[:seon.task/id :demo/t1]}}])})
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
          (is (= #{:rule.ctx/open-errors} (set recomp-ids)))
          (is (= 1 (:recomputed s3)))
          (is (= (- (count ctx-rs) 1) (:skipped s3))))

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

  (testing "upgrade-ladder :already-shown handles :new, :unchanged (0 tokens), explicit new :fn :upgraded, and :rewarmed (newer ?tx or content hash)"
    (let [cands [{:fn 'demo/f-chip :hiccup [:span "f"] :tokens 12 :min-width 120 :area 2400}
                 {:fn 'demo/f-full :hiccup [:div "f full"] :tokens 60 :min-width 420 :area 16000}]
          d1 (fit/upgrade-ladder [{:entity 101 :zone :workspace :tx 10 :hash 1 :candidates cands}]
                                 {:target :wire :token-budget 20})
          shown1 (:shown d1)
          ;; Turn 2: same entity, same tx & hash -> :unchanged (0 tokens) even with 4000 spare budget!
          d2 (fit/upgrade-ladder [{:entity 101 :zone :workspace :tx 10 :hash 1 :candidates cands}]
                                 {:target :wire :token-budget 4000 :already-shown shown1})
          ;; Turn 3: explicit different :fn 'demo/f-full -> :upgraded once!
          d3 (fit/upgrade-ladder [{:entity 101 :zone :workspace :tx 10 :hash 1 :fn 'demo/f-full :candidates cands}]
                                 {:target :wire :token-budget 4000 :already-shown (:shown d2)})
          ;; Turn 4: entity fades out -> stays remembered in :shown!
          d4 (fit/upgrade-ladder [] {:target :wire :token-budget 4000 :already-shown (:shown d3)})
          ;; Turn 5: higher native ?tx 25 -> :rewarmed!
          d5 (fit/upgrade-ladder [{:entity 101 :zone :workspace :tx 25 :hash 1 :candidates cands}]
                                 {:target :wire :token-budget 4000 :already-shown (:shown d4)})
          ;; Turn 6: content hash changes even at same tx -> :rewarmed!
          d6 (fit/upgrade-ladder [{:entity 101 :zone :workspace :tx 25 :hash 2 :candidates cands}]
                                 {:target :wire :token-budget 4000 :already-shown (:shown d5)})]
      (is (= :new (get-in d1 [:selected 0 :status])))
      (is (= :unchanged (get-in d2 [:selected 0 :status])))
      (is (= 0 (:tokens d2)))
      (is (= :upgraded (get-in d3 [:selected 0 :status])))
      (is (= 'demo/f-full (get-in d3 [:selected 0 :chosen-fn])))
      (is (contains? (:shown d4) 101) "Faded entity stays tracked in shown")
      (is (= :rewarmed (get-in d5 [:selected 0 :status])))
      (is (= :rewarmed (get-in d6 [:selected 0 :status]))))))

(deftest ^{:cost/ticks 110000000} all-10-native-datalog-context-rules-test
  (testing "Baseline 0 native :rule.ctx/* rules surface errors+fixes, branch-edited vars, failing tests, active tasks+prereqs, scope ns, unread messages, delegated calls, review target, and pinned entities"
    (with-test-store [{:keys [conn cfg]}]
      (let [reg! (fn [k form doc]
                   (let [{:keys [dh-decls entity]} (schema/validate-schema-registration! @conn {:key k :form form :doc doc})]
                     (d/transact conn {:tx-data (vec (concat dh-decls [entity]))})))]
        (reg! :seon.demo.widget/id [:and {:seon.db/identity true} :keyword] "Widget ID")
        (reg! :seon.demo.widget/entity [:map {:seon.db/attributes true} [:seon.demo.widget/id :seon.demo.widget/id]] "Widget entity"))
      (let [src-lib (str "(ns demo.wlib)\n"
                         "(defn core-op \"Core op\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n")
            src-test (str "(ns demo.wlib-test (:require [clojure.test :refer [deftest is]] [demo.wlib :as w]))\n"
                          "(deftest core-op-test (is (= 2 (w/core-op 1))))\n")
            _ (d/transact conn {:tx-data (concat
                                          (code/file-import-tx @conn "src/demo/wlib.clj" src-lib false)
                                          (code/file-import-tx @conn "test/demo/wlib_test.clj" src-test false))})
            _ (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn)})
            db0 @conn
            core-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.wlib/core-op)))
            test-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.wlib-test/core-op-test)))
            err-ent (error/error-tx {:kind :demo/oom-class :source :eval :agent :ag/full :message "Out of memory"})
            fix-ent {:seon.fix/id (random-uuid) :seon.fix/error-class :demo/oom-class
                     :seon.fix/symptom "OOM on big scan" :seon.fix/root-cause "Unindexed scan"
                     :seon.fix/change-summary "Switch to :avet lookup"}
            msg-id (random-uuid)
            call-id (random-uuid)
            _ (d/transact conn {:tx-data
                                [err-ent fix-ent
                                 {:seon.task/id :demo/pre :seon.task/title "Prereq Task" :seon.task/done? false}
                                 {:seon.task/id :demo/main-task :seon.task/title "Main Task" :seon.task/scope 'demo.wlib :seon.task/needs #{:demo/pre} :seon.task/done? false}
                                 {:seon.demo.widget/id :w/pinned-one}
                                 {:seon.agent/id :ag/peer :seon.agent/branch :main :seon.agent/goal "Peer"}
                                 {:seon.agent/id :ag/full :seon.agent/branch :branch/full :seon.agent/scope 'demo.wlib
                                  :seon.agent/tasks #{[:seon.task/id :demo/main-task]}
                                  :seon.agent/target [:seon.agent/id :ag/peer]
                                  :seon.agent/pinned [[:seon.demo.widget/id :w/pinned-one]]}
                                 {:seon.agent.message/id msg-id :seon.agent.message/seq 1 :seon.agent.message/from :user
                                  :seon.agent.message/to :ag/full :seon.agent.message/type :chat :seon.agent.message/body "Status?"}
                                 {:seon.agent.call/id call-id :seon.agent.call/caller :ag/full :seon.agent.call/callee :ag/peer
                                  :seon.agent.call/status :pending :seon.agent.call/prompt "Check peer" :seon.agent.call/created-tx 1}]})]
        (store/branch! conn :main :branch/full)
        (let [bconn (store/connect cfg :branch/full)]
          (try
            (let [src-lib-v2 (str/replace src-lib "(inc x)" "(+ x 2)")]
              (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/demo/wlib.clj" src-lib-v2 false)})
              (d/transact bconn {:tx-data (graph/backfill-codebase-analysis-tx @bconn)})
              (d/transact bconn {:tx-data
                                 [{:seon.test/digest "dg-fail-1234" :seon.test/var 'demo.wlib-test/core-op-test
                                   :seon.test/var-ref test-eid :seon.test/kind :deftest
                                   :seon.test/pass? false :seon.test/ms 2.0 :seon.test/report "expected 2 got 3"}]}))
            (let [mdb @conn
                  bdb @bconn
                  res (ctx/project-entity mdb bdb [:seon.agent/id :ag/full] {:target :wire :token-budget 32000 :semantic? false})
                  projs (:candidates res)
                  by-ent (into {} (map (juxt :entity identity)) projs)
                  fired (into #{} (map :rule-id) (get-in res [:ctx-trace :fired-rules]))
                  err-eid (:e (first (d/datoms mdb :avet :seon.error/id (:seon.error/id err-ent))))
                  fix-eid (:e (first (d/datoms mdb :avet :seon.fix/id (:seon.fix/id fix-ent))))
                  fail-eid (:e (first (d/datoms bdb :avet :seon.test/digest "dg-fail-1234")))
                  pre-eid (:e (first (d/datoms bdb :avet :seon.task/id :demo/pre)))
                  pin-eid (:e (first (d/datoms bdb :avet :seon.demo.widget/id :w/pinned-one)))
                  ns-eid (:e (first (d/datoms bdb :avet :seon.code.ns/name 'demo.wlib)))
                  peer-eid (:e (first (d/datoms mdb :avet :seon.agent/id :ag/peer)))
                  msg-eid (:e (first (d/datoms mdb :avet :seon.agent.message/id msg-id)))
                  call-eid (:e (first (d/datoms mdb :avet :seon.agent.call/id call-id)))]
              (is (clojure.set/subset?
                   #{:rule.ctx/open-errors :rule.ctx/active-tasks :rule.ctx/pinned-entities
                     :rule.ctx/branch-edits :rule.ctx/failing-tests :rule.ctx/scope-namespaces
                     :rule.ctx/unread-messages :rule.ctx/delegated-calls :rule.ctx/review-target}
                   fired))
              (is (contains? by-ent err-eid))
              (is (= err-eid (:parent (get by-ent fix-eid))))
              (is (contains? by-ent core-eid))
              (is (= "expected 2 got 3" (get-in by-ent [fail-eid :data :seon.test/report])))
              (is (some #(and (= fail-eid (:parent %)) (contains? (:data %) :seon.code.form/src)) projs))
              (is (contains? by-ent pre-eid))
              (is (contains? by-ent pin-eid))
              (is (contains? by-ent ns-eid))
              (is (contains? by-ent peer-eid))
              (is (contains? by-ent msg-eid))
              (is (contains? by-ent call-eid)))
            (finally
              (d/release bconn))))))))

(deftest ^{:cost/ticks 120000000} generic-2-read-discovery-budget-trace-diff-and-extract-refs-test
  (testing "Generic 2-read agent-world-datoms catches brand-new custom scalar + reverse :seon.db/to [:seon.agent/entity] ref, budget prunes cleanly, and REPL context-trace/diff-context work"
    (with-test-store [{:keys [conn]}]
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
      (d/transact conn {:tx-data [{:seon.agent/id :ag/generic :seon.agent/branch :main :seon.custom.lab/mood "curious"
                                   :seon.agent/pinned [[:seon.custom.lab/ticket-id :tkt/num-42]]}
                                  {:seon.custom.lab/ticket-id :tkt/num-42
                                   :seon.custom.lab/assigned-agent [:seon.agent/id :ag/generic]}]})
      (let [db @conn
            world (ctx/agent-world-datoms db db :ag/generic)
            tkt-eid (:e (first (d/datoms db :avet :seon.custom.lab/ticket-id :tkt/num-42)))
            res (ctx/project-entity db db [:seon.agent/id :ag/generic] {:target :wire :token-budget 4000 :semantic? false})
            by-ent (into {} (map (juxt :entity identity)) (:candidates res))
            tight (ctx/project-entity db db [:seon.agent/id :ag/generic] {:target :wire :token-budget 2 :semantic? false})]
        ;; 1. Generic 2-read discovery caught BOTH brand-new custom scalar attr AND reverse ref entity!
        (is (some #(= :seon.custom.lab/mood (:a %)) (:forward world)))
        (is (some #{:seon.custom.lab/assigned-agent} (:reverse-attrs world)))
        (is (contains? (:connected world) tkt-eid))
        (is (= "curious" (get-in res [:focus-data :seon.custom.lab/mood])))
        (is (= :tkt/num-42 (get-in by-ent [tkt-eid :data :seon.custom.lab/ticket-id])))

        ;; 2. Token budget pruning caps :tokens <= budget and records :dropped!
        (is (<= (long (get-in tight [:fitted :tokens] 0)) 2))
        (is (seq (get-in tight [:fitted :dropped])))

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
                  seon.render.fit.measure/pass2-upgrade
                  seon.render.fit.measure/finalize-entry]]
      (let [res (schema/stress-var nil sym {:samples 100 :seed 42})]
        (is (:ok? res) (str sym " failed seed=42 generative check: " (pr-str res)))))))

(deftest ^{:cost/ticks 60000000} live-ctx-atom-iatom2-auto-deref-and-neighborhood-writeback-test
  (testing "CtxAtom implements IAtom2, lazily auto-advances on external commits, projects filtered :seon.ctx/neighborhood, and transacts swap! write-back on nested neighborhood entities"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.task/id :task/nb-live
                                   :seon.task/title "Live Neighborhood Task"
                                   :seon.task/plan "v1-plan"
                                   :seon.task/done? false}
                                  {:seon.agent/id :ag/nb-live
                                   :seon.agent/branch :main
                                   :seon.agent/tasks #{[:seon.task/id :task/nb-live]}
                                   :seon.agent/goal "Initial goal"}]})
      (let [ca (ctx/ctx-atom conn [:seon.agent/id :ag/nb-live])
            v0 @ca
            nb0 (:seon.ctx/neighborhood v0)]
        (is (instance? clojure.lang.IAtom2 ca))
        (is (= "Initial goal" (:seon.agent/goal v0)))
        (is (map? nb0))
        (is (= "v1-plan" (get-in nb0 [:tasks :task/nb-live :seon.task/plan])))
        (is (integer? (get-in nb0 [:tasks :task/nb-live :db/id])))
        (is (<= (+ (count (:tasks nb0)) (count (:vars nb0)) (count (:errors nb0)) (count (:pinned nb0))) 8))
        ;; External Datahike commit: deref auto-advances lazily with zero manual refresh-active!
        (d/transact conn {:tx-data [{:seon.agent/id :ag/nb-live :seon.agent/goal "External commit goal"}]})
        (is (= "External commit goal" (:seon.agent/goal @ca)))
        ;; Bidirectional IAtom2 swap-vals! write-back on nested neighborhood entity persists to Datahike!
        (let [[old-m new-m] (swap-vals! ca assoc-in [:seon.ctx/neighborhood :tasks :task/nb-live :seon.task/plan] "v2-via-neighborhood")]
          (is (= "v1-plan" (get-in old-m [:seon.ctx/neighborhood :tasks :task/nb-live :seon.task/plan])))
          (is (= "v2-via-neighborhood" (get-in new-m [:seon.ctx/neighborhood :tasks :task/nb-live :seon.task/plan])))
          (is (= "v2-via-neighborhood" (:seon.task/plan (d/pull @conn '[:seon.task/plan] [:seon.task/id :task/nb-live])))))
        ;; reset-vals! on agent scalar field also returns [old new] and persists cleanly!
        (let [[old-r new-r] (reset-vals! ca (assoc @ca :seon.agent/goal "Reset goal"))]
          (is (= "External commit goal" (:seon.agent/goal old-r)))
          (is (= "Reset goal" (:seon.agent/goal new-r)))
          (is (= "Reset goal" (:seon.agent/goal (d/pull @conn '[:seon.agent/goal] [:seon.agent/id :ag/nb-live])))))))))

(deftest ^{:cost/ticks 60000000} ported-scope-ns-history-fixes-and-reverse-task-lineage-rules-test
  (testing "Baseline 0 native rules surface scope/extra namespaces, active tasks + prereqs, open errors + matching :seon.fix/*, and system overview"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (concat
                                  (code/file-import-tx @conn "src/demo/scoped.clj" "(ns demo.scoped \"Scoped ns\")\n(defn f [x] x)" false)
                                  (code/file-import-tx @conn "src/demo/extra.clj" "(ns demo.extra \"Extra ns\")\n(defn g [x] x)" false)
                                  [{:seon.task/id :task/prereq-a :seon.task/title "Prereq A" :seon.task/scope 'demo.scoped :seon.task/done? false}
                                   {:seon.task/id :task/child-cur :seon.task/title "Current Child" :seon.task/scope 'demo.scoped :seon.task/needs #{:task/prereq-a} :seon.task/done? false}
                                   (error/error-tx {:kind :demo/scoped-bug :severity :error :source :eval :agent :ag/scoped-worker :message "boom"})
                                   {:seon.fix/id (random-uuid) :seon.fix/error-class :demo/scoped-bug :seon.fix/symptom "boom"
                                    :seon.fix/root-cause "nil" :seon.fix/change-summary "Guarded demo.scoped/f" :seon.fix/regression-test 'demo.scoped-test/t1}
                                   {:seon.agent/id :ag/scoped-worker :seon.agent/branch :main :seon.agent/scope 'demo.scoped :seon.agent/tasks #{[:seon.task/id :task/child-cur]}
                                    :seon.agent/context-prefs {:extra-namespaces ['demo.extra]}}
                                   {:seon.agent/id :ag/unassigned-coord :seon.agent/branch :main :seon.agent/router? true}])})
      (let [db @conn
            w-res (ctx/project-entity db db [:seon.agent/id :ag/scoped-worker] {:target :wire :token-budget 32000 :semantic? false})
            w-eids (into #{} (map :entity) (:candidates w-res))
            s-res (ctx/project-entity db db [:seon.system/id :root] {:target :wire :token-budget 32000 :semantic? false})
            s-eids (into #{} (map :entity) (:candidates s-res))
            eid-of (fn [a v] (:e (first (d/datoms db :avet a v))))]
        (is (contains? w-eids (eid-of :seon.code.ns/name 'demo.scoped)))
        (is (contains? w-eids (eid-of :seon.code.ns/name 'demo.extra)))
        (is (contains? w-eids (eid-of :seon.task/id :task/child-cur)) "Assigned active task must surface")
        (is (contains? w-eids (eid-of :seon.task/id :task/prereq-a)) "Prerequisite task must surface")
        (is (some #(contains? (:data %) :seon.fix/error-class) (:candidates w-res)) "Matching :seon.fix/* for open error must surface")
        (is (contains? s-eids (eid-of :seon.task/id :task/child-cur)) "Open non-template task must surface in :system-overview")))))

(deftest ^{:cost/ticks 120000000} cycle2-compounding-rules-and-wire-tail-producers-test
  (testing "Baseline 0 rules surface open errors with nested fix, scope namespace, branch-only unloaded renderer safety, and additive :db/index upgrades"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data [{:seon.schema/key :demo.c2/widget-id :seon.schema/form [:string {:seon.db/identity true}] :seon.schema/doc "Widget ID"}
                                  {:seon.schema/key :demo.c2/widget :seon.schema/form [:map [:demo.c2/widget-id :demo.c2/widget-id]]
                                   :seon.schema/keys [[:seon.schema/key :demo.c2/widget-id]] :seon.schema/doc "Widget entity"}]})
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/c2.clj"
                                   "(ns demo.c2)
(defn make-widget {:malli/schema [:=> [:cat :string] :demo.c2/widget-id]} [s] s)
(defn use-widget {:malli/schema [:=> [:cat :demo.c2/widget-id] :string]} [w] (str w))" false)})
      (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/c2_test.clj"
                                   "(ns demo.c2-test (:require [clojure.test :refer [deftest is]] [demo.c2 :as c2]))
(deftest widget-roundtrip-test (is (= \"ok\" (c2/use-widget (c2/make-widget \"ok\")))))" false)})
      (d/transact conn {:tx-data [{:seon.fix/id (random-uuid) :seon.fix/error-class :demo.c2/boom
                                   :seon.fix/symptom "boom" :seon.fix/root-cause "nil" :seon.fix/change-summary "fix"
                                   :seon.fix/regression-test 'demo.c2-test/widget-roundtrip-test}
                                  {:seon.error/id (random-uuid) :seon.error/kind :demo.c2/boom :seon.error/severity :error
                                   :seon.error/source :eval :seon.error/status :open :seon.error/agent :ag/c2
                                   :seon.error/var 'demo.c2/use-widget :seon.error/message "boom"}
                                  {:seon.agent/id :ag/c2 :seon.agent/branch :branch/c2 :seon.agent/scope 'demo.c2}]})
      (store/branch! conn :main :branch/c2)
      (let [bconn (store/connect cfg :branch/c2)]
        (try
          (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/demo/branch_only_ui.clj"
            "(ns demo.branch-only-ui)
(defn render-ghost {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div \"ghost\"]})" false)})
          (let [mdb @conn bdb @bconn
                rs (ctx/load-context-rules mdb bdb nil)
                res (ctx/gather-agent-candidates mdb bdb :ag/c2 {:target :wire :rules rs})
                fired (set (map :rule-id (:fired-rules (:ctx-trace res))))]
            (is (contains? fired :rule.ctx/open-errors) "open error must fire :rule.ctx/open-errors")
            (is (contains? fired :rule.ctx/scope-namespaces) "scope must fire :rule.ctx/scope-namespaces")
            (is (some #(= :demo.c2/boom (get-in % [:data :seon.fix/error-class])) (:candidates res))
                "matching :seon.fix/* must attach as nested child of open error")
            (let [fitted (seon.render.fit/fit-projections bdb (:candidates res) {:main-db mdb :target :wire :token-budget 4000})
                  fitted-nil-main (seon.render.fit/fit-projections bdb (:candidates res) {:target :wire :token-budget 4000})]
              (is (map? fitted))
              (is (seq (:selected fitted)))
              (is (map? fitted-nil-main) "branch-only unloaded renderer must not crash even when main-db is nil"))
            ;; Verify additive :db/index true upgrade via seon.schema/sync-dh-decls!
            (let [up (seon.schema/sync-dh-decls {:demo/a {:db/ident :demo/a :db/valueType :db.type/keyword}}
                                                [{:db/ident :demo/a :db/valueType :db.type/keyword :db/index true}])]
              (is (= 1 (count up)) "sync-dh-decls must emit additive :db/index true upgrades on existing attrs")))
          (finally (d/release bconn)))))))