(ns seon.cycle4-perf-test
  "Regression proofs for Cycle 4 lower-bound caching, scoped health, and allocation budgets."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.health :as health]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]
            [seon.render.status :as rstatus]
            [seon.ctx :as ctx]
            [seon.code.edit :as cedit]
            [seon.fork.calls :as fcalls]
            [seon.fork.helpers :as fhelpers]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]))

(deftest ^{:cost/ticks 180000000} cycle4-pass-a-merkle-schema-head-cache-and-scoped-caused-by-branch-test
  (testing "Merkle db-registry-cache promotes across non-schema txs and invalidates on schema edits; store/head caches by cid; scoped caused-by-branch catches broken-doc-ref, impure-gen, and blocking-in-flow-proc; format-tool-content caches cleanly"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/c4/a.clj"
                                   "(ns demo.c4.a)\n(defn ^:seon.flow/edge edge-op [] 1)\n(defn ok-fn \"Calls `demo.c4.a/edge-op`.\" {:malli/schema [:=> [:cat :int] :int] :cost/ticks 5000} [x] (inc x))\n" false)})
      (let [h1 (store/head conn :main)
            h2 (store/head conn :main)]
        (is (identical? h1 h2) "store/head must hit cid LRU when branch head unchanged"))
      (let [r1 (schema/registry-for-db @conn)
            _ (d/transact conn {:tx-data [{:seon.agent/id :ag/c4-noop :seon.agent/branch :main :seon.agent/role :worker :seon.agent/goal "Noop"}]})
            r2 (schema/registry-for-db @conn)
            h3 (store/head conn :main)]
        (is (identical? (:registry r1) (:registry r2)) "Non-schema tx must promote anchored registry via d/changes")
        (is (= (:max-tx @conn) (:max-tx h3)) "store/head must invalidate when branch advances")
        (d/transact conn {:tx-data [{:seon.schema/key :demo.c4/tag :seon.schema/form [:keyword]}]})
        (let [r3 (schema/registry-for-db @conn)]
          (is (contains? (:forms r3) :demo.c4/tag) "Schema tx must invalidate Merkle anchor")))
      (store/branch! conn :main :branch/c4-health)
      (let [bconn (store/connect cfg :branch/c4-health)]
        (try
          (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/demo/c4/a.clj"
                                        "(ns demo.c4.a)\n(defn ^:seon.flow/edge edge-op [] 1)\n(defn ok-fn \"Calls `seon.nonexistent/ghost`.\" {:malli/schema [:=> [:cat :int] :int] :cost/ticks 5000} [x] (+ x (edge-op)))\n(defn bad-proc [] (Thread/sleep 1))\n" false)})
          (let [errs (health/caused-by-branch @conn @bconn {} ['demo.c4.a])
                classes (set (map :class errs))]
            (is (contains? classes :broken-doc-ref))
            (is (contains? classes :impure-generatable-fn))
            (is (contains? classes :blocking-in-flow-proc)))
          (let [s1 (rstatus/cached-format-tool-content {:eval-db @bconn :main-db @conn} {:handle 'result/e1 :ok? true :value "{:a 1 :b 2}"})
              s2 (rstatus/cached-format-tool-content {:eval-db @bconn :main-db @conn} {:handle 'result/e1 :ok? true :value "{:a 1 :b 2}"})]
          (is (identical? s1 s2) "cached-format-tool-content must hit 512-slot LRU cache"))
          (finally (d/release bconn)))))))

(deftest ^{:cost/ticks 180000000} cycle4-pass-b-rules-lazy-ctx-batch-meta-and-exports-test
  (testing "Atomic 1-tx batch set-meta!, lazy CtxAtom + addWatch catch-up, O(E) unanswered-user-chat + compounding rules, and declarative ^:seon.repl/export metadata"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/c4/b.clj"
                                   "(ns demo.c4.b)\n(defn f1 [x] (inc x))\n(defn f2 [x] (dec x))\n" false)})
      (d/transact conn {:tx-data [{:seon.agent/id :ag/c4-w :seon.agent/branch :main :seon.agent/role :worker :seon.agent/goal "Init"}
                                  {:seon.agent/id :review/c4-r :seon.agent/branch :main :seon.agent/role :worker :seon.agent/goal "Review"
                                   :seon.agent/target [:seon.agent/id :ag/c4-w]}]})
      ;; 1. Single-tx atomic batch set-meta!
      (do (create-ns 'demo.c4.b)
      (try
        (let [tx0 (:max-tx @conn)
              res (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :ag/c4-w}]
                    (cedit/set-meta! {'demo.c4.b/f1 {:cost/ticks 5000} 'demo.c4.b/f2 {:cost/ticks 6000}}))]
          (is (:ok? res) (pr-str res))
          (is (= 2 (:updated res)))
          (is (= 1 (- (:max-tx @conn) tx0)) "Batch set-meta! must transact all targets in one atomic Datahike tx"))
        (finally (remove-ns 'demo.c4.b))))
      ;; 2. Lazy CtxAtom + addWatch catch-up
      (let [ca (ctx/ctx-atom conn [:seon.agent/id :ag/c4-w])
            snap0 (ctx/-snapshot ca)
            r1 (d/transact conn {:tx-data [{:seon.agent/id :ag/c4-w :seon.agent/goal "Updated-1"}]})
            _ (binding [ctx/ctx ca] (ctx/refresh-active! (:db-after r1) (:tx-data r1)))
            snap1 (ctx/-snapshot ca)
            seen (atom [])]
        (is (= (:tx snap0) (:tx snap1)) "refresh-active! with 0 watches must skip eager advance")
        (add-watch ca :c4-w (fn [_ _ o n] (swap! seen conj [(:seon.agent/goal o) (:seon.agent/goal n)])))
        (is (= "Updated-1" (:seon.agent/goal (:val (ctx/-snapshot ca)))) "addWatch must catch up snapshot before registering watch")
        (is (empty? @seen) "addWatch catch-up must not fire watch for pre-watch changes")
        (let [r2 (d/transact conn {:tx-data [{:seon.agent/id :ag/c4-w :seon.agent/goal "Updated-2"}]})]
          (binding [ctx/ctx ca] (ctx/refresh-active! (:db-after r2) (:tx-data r2)))
          (is (= [["Updated-1" "Updated-2"]] @seen) "refresh-active! with watch must advance and notify")
          (is (= "Updated-2" (:seon.agent/goal @ca)))))
      ;; 3. O(E) unanswered-user-chat-tx + compounding rules (unanswered-user-chat, eval-error-class-fix, review-diagnosis)
      (d/transact conn {:tx-data [{:seon.agent.message/id (java.util.UUID/randomUUID)
                                  :seon.agent.message/from :user :seon.agent.message/to :ag/c4-w
                                  :seon.agent.message/type :chat :seon.agent.message/body "Status?"
                                  :seon.agent.message/seq 1}
                                  {:seon.fix/id (java.util.UUID/randomUUID) :seon.fix/error-class :alloc-budget-overrun
                                   :seon.fix/symptom "OOM" :seon.fix/root-cause "Scan" :seon.fix/change-summary "Indexed"}
                                  {:seon.agent.eval/id (java.util.UUID/randomUUID) :seon.agent.eval/agent :ag/c4-w
                                   :seon.agent.eval/turn 1 :seon.agent.eval/code "(user/health)"
                                   :seon.agent.eval/error "Eval exceeded allocation budget (limit 268435456 bytes)."
                                   :seon.agent.eval/ms 10.0}
                                  {:seon.error/id (java.util.UUID/randomUUID) :seon.error/kind :eval-error
                                   :seon.error/severity :error :seon.error/source :eval :seon.error/status :open
                                   :seon.error/message "Boom" :seon.error/agent :ag/c4-w
                                   :seon.error/agent-ref [:seon.agent/id :ag/c4-w] :seon.error/var 'demo.c4.b/f1}]})
      (is (integer? (rstatus/unanswered-user-chat-tx @conn @conn :ag/c4-w)))
      (let [rs (rules/load-rules @conn (rseed/starter-rules))
            ru-w (ctx/related-updates {:seon.db/main @conn :seon.db/branch @conn :seon.agent/id :ag/c4-w :seon.ctx/rules rs})
            wm-w (get-in ru-w [:seon.ctx/session :rule-state :wm :facts])
            ru-r (ctx/related-updates {:seon.db/main @conn :seon.db/branch @conn :seon.agent/id :review/c4-r :seon.ctx/rules rs})
            wm-r (get-in ru-r [:seon.ctx/session :rule-state :wm :facts])]
        (is (some #(= :wm/unanswered-user-chat (second %)) wm-w) ":rule.ctx/unanswered-user-chat must assert :wm/unanswered-user-chat")
        (is (some #(= :wm/matched-fix (second %)) wm-w) ":rule.ctx/eval-error-class-fix must assert :wm/matched-fix")
        (is (some #(= :wm/active-var (second %)) wm-r) ":rule.ctx/review-diagnosis must assert :wm/active-var on target error var"))
      (d/transact conn {:tx-data [{:seon.agent.message/id (java.util.UUID/randomUUID)
                                  :seon.agent.message/from :ag/c4-w :seon.agent.message/to :user
                                  :seon.agent.message/type :chat :seon.agent.message/body "Done!"
                                  :seon.agent.message/seq 2}]})
      (is (nil? (rstatus/unanswered-user-chat-tx @conn @conn :ag/c4-w)))
      ;; 4. Declarative ^:seon.repl/export metadata preservation
      (let [ns-sym (gensym "seon.c4-export-test-")
            u (create-ns ns-sym)]
        (try
          (let [n1 (fcalls/export-ns-helpers! u 'seon.fork.helpers)
                n2 (fcalls/export-ns-helpers! u 'seon.fork.calls)]
            (is (>= n1 16))
            (is (>= n2 18))
            (doseq [s ['conn 'conn-of 'db 'main-db 'source 'remove-var! 'doc! 'chat! 'pin! 'monitor]]
              (let [v (ns-resolve u s) m (meta v)]
                (is (var? v) (str "Expected exported var " s))
                (is (string? (:doc m)) (str "Expected :doc on " s))
                (is (seq (:arglists m)) (str "Expected :arglists on " s))
                (is (qualified-symbol? (:seon.repl/source m)) (str "Expected :seon.repl/source on " s)))))
          (finally (remove-ns ns-sym)))))))

(deftest ^{:cost/ticks 180000000} cycle4-auditor-must-fix-regression-test
  (testing "MF-1 missing rule fn safety, MF-2 review->covering-test compounding, MF-3 speculative d/with & AsOfDB registry cache, MF-4 nil vs empty format cache, MF-5 test-proc filter, MF-7 duplicate batch keys & nil ctx"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/c4/c.clj"
                                   "(ns demo.c4.c)\n(defn f1 [x] (inc x))\n" false)})
      (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/c4/c_test.clj"
                                   "(ns demo.c4.c-test (:require [clojure.test :refer [deftest is]] [demo.c4.c :as c]))\n(deftest f1-test (is (= 2 (c/f1 1))))\n" false)})
      ;; MF-1: branch-only / missing qualified fn in rule returns [] cleanly without throwing
      (is (= [] (rules/eval-rule-query (rules/empty-wm) {:main @conn :branch @conn} :ag/c4-w
                  {:seon.rule/id :rule.test/missing-fn
                   :seon.rule/query '[:find ?x :in $branch :where [(seon.nonexistent.ns/ghost-fn $branch) ?x]]})))
      ;; MF-2: review-diagnosis asserts :wm/active-var :error and compounds into active-var-covering-tests
      (d/transact conn {:tx-data [{:seon.agent/id :ag/c4-t :seon.agent/branch :main :seon.agent/role :worker :seon.agent/goal "Work"}
                                  {:seon.agent/id :review/c4-t :seon.agent/branch :main :seon.agent/role :worker :seon.agent/goal "Review"
                                   :seon.agent/target [:seon.agent/id :ag/c4-t]}
                                  {:seon.error/id (java.util.UUID/randomUUID) :seon.error/kind :eval-error
                                   :seon.error/severity :error :seon.error/source :eval :seon.error/status :open
                                   :seon.error/message "Boom" :seon.error/agent :ag/c4-t
                                   :seon.error/agent-ref [:seon.agent/id :ag/c4-t] :seon.error/var 'demo.c4.c/f1}]})
      (let [rs (rules/load-rules @conn (rseed/starter-rules))
            ru (ctx/related-updates {:seon.db/main @conn :seon.db/branch @conn :seon.agent/id :review/c4-t :seon.ctx/rules rs})
            wm (get-in ru [:seon.ctx/session :rule-state :wm :facts])
            f1-eid (:e (first (d/datoms @conn :avet :seon.code.var/name 'demo.c4.c/f1)))
            t-eid (:e (first (d/datoms @conn :avet :seon.code.var/name 'demo.c4.c-test/f1-test)))]
        (is (contains? wm [f1-eid :wm/active-var :error]) "MF-2: review-diagnosis must assert :wm/active-var :error")
        (is (some #(= t-eid (:entity %)) (:seon.ctx/projections ru)) "MF-2: reviewer must compound covering test via active-var-covering-tests"))
      ;; MF-3: speculative d/with schema edit must NOT return stale registry; AsOfDB caches cleanly
      (let [r-base (schema/registry-for-db @conn)
            spec-db (:db-after (d/with @conn {:tx-data [{:seon.schema/key :demo.c4/spec-only :seon.schema/form [:string]}]}))
            r-spec (schema/registry-for-db spec-db)
            asof-db (d/as-of @conn (:max-tx @conn))
            r-a1 (schema/registry-for-db asof-db)
            r-a2 (schema/registry-for-db asof-db)]
        (is (not (contains? (:forms r-base) :demo.c4/spec-only)))
        (is (contains? (:forms r-spec) :demo.c4/spec-only) "MF-3: speculative d/with schema edit must not hit stale anchor")
        (is (identical? (:registry r-a1) (:registry r-a2)) "MF-3: AsOfDB registry-for-db must cache cleanly"))
      ;; MF-4: cached-format-tool-content distinguishes nil vs empty string value
      (let [s-nil (rstatus/cached-format-tool-content {:eval-db @conn :main-db @conn} {:handle 'result/e9 :ok? true :value nil})
            s-emp (rstatus/cached-format-tool-content {:eval-db @conn :main-db @conn} {:handle 'result/e9 :ok? true :value ""})]
        (is (not= s-nil s-emp) "MF-4: nil value and empty string value must not collide in tool-format-cache"))
      ;; MF-5: *-test ns *-proc calling Thread/sleep must NOT false-positive :blocking-in-flow-proc
      (store/branch! conn :main :branch/c4-mf5)
      (let [bconn (store/connect cfg :branch/c4-mf5)]
        (try
          (d/transact bconn {:tx-data (code/file-import-tx @bconn "test/demo/c4/c_test.clj"
                                        "(ns demo.c4.c-test (:require [clojure.test :refer [deftest is]] [demo.c4.c :as c]))\n(defn fake-proc [] (Thread/sleep 1))\n(deftest f1-test (is (= 2 (c/f1 1))))\n" false)})
          (let [errs (health/caused-by-branch @conn @bconn {} ['demo.c4.c-test])]
            (is (not-any? #(= :blocking-in-flow-proc (:class %)) errs) "MF-5: test-ns *-proc must not trigger :blocking-in-flow-proc"))
          (finally (d/release bconn))))
      ;; MF-7: nil *agent-ctx* satisfies :map contract; duplicate resolved keys in batch merge cleanly in 1 tx
      (is (= {:ok? false :error :no-agent-ctx}
             (binding [fcalls/*agent-ctx* nil] (cedit/set-meta! {'demo.c4.c/f1 {:cost/ticks 5000}}))))
      (do (create-ns 'demo.c4.c)
          (try
            (let [txs-before (count (d/datoms @conn :aevt :db/txInstant))
                res (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :ag/c4-t}]
                      (cedit/set-meta! {'demo.c4.c/f1 {:cost/ticks 5000} "demo.c4.c/f1" {:cost/bytes 65536}}))
                v (d/pull @conn '[:seon.code.var/ticks-budget :seon.code.var/bytes-budget] [:seon.code.var/name 'demo.c4.c/f1])]
            (is (:ok? res) (pr-str res))
            (is (= 1 (- (count (d/datoms @conn :aevt :db/txInstant)) txs-before)) "MF-7: duplicate resolved keys must merge into 1 atomic tx")
            (is (= 5000 (:seon.code.var/ticks-budget v)))
            (is (= 65536 (:seon.code.var/bytes-budget v))))
            (finally (remove-ns 'demo.c4.c)))))))