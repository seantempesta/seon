(ns seon.task-gate-test
  "Unit and integration tests for multi-task agents (`:seon.agent/tasks`), true ref proofs
   (`:seon.task/test-vars`), declarative `:rule.gate/*` gating, `seon.reconciler`, and `seon.transactor.merge`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.reconciler :as rec]
            [seon.rule :as rules]
            [seon.store :as store]
            [seon.task.gate :as gate]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.merge :as tmerge]
            [seon.render.wire :as wire]
            [clojure.string :as str]
            [seon.transactor.spawn :as tspawn]
            [seon.fork.calls :as fcalls]
            [seon.transactor.calls :as tcalls]))

(defn- setup-demo-vars! [conn]
  (let [src (str "(ns demo.gate-test (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest alpha-test (is (= 2 (+ 1 1))))\n"
                 "(deftest beta-test (is (= 4 (* 2 2))))\n"
                 "(deftest gamma-test (is true))")]
    (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/gate_test.clj" src false)})))

(deftest ^{:cost/ticks 40000000} multi-task-ref-proofs-and-rule-provenance-test
(testing "Multi-task :seon.agent/tasks + :seon.task/test-vars (:db.type/ref) + latest-digest gating + :seon.task/needs"
  (with-test-store [{:keys [conn]}]
    (setup-demo-vars! conn)
    (d/transact conn {:tx-data
                      [{:seon.task/id :task/prereq :seon.task/title "Prerequisite" :seon.task/done? false}
                       {:seon.task/id :task/alpha :seon.task/title "Alpha legacy tests"
                        :seon.task/done? false :seon.task/tests #{'demo.gate-test/alpha-test}}
                       {:seon.task/id :task/beta :seon.task/title "Beta ref proofs"
                        :seon.task/done? false :seon.task/test-vars #{[:seon.code.var/name 'demo.gate-test/beta-test]}
                        :seon.task/needs #{:task/prereq}}
                       {:seon.agent/id :ag/multi :seon.agent/branch :main :seon.agent/tasks [[:seon.task/id :task/alpha] [:seon.task/id :task/beta]]}]})
    (let [db0 @conn]
      (is (= :db.type/ref (get-in db0 [:schema :seon.agent/tasks :db/valueType])))
      (is (= :db.type/ref (get-in db0 [:schema :seon.task/test-vars :db/valueType])))
      (is (= [:task/alpha :task/beta] (gate/agent-task-ids db0 :ag/multi)))
      (is (= #{'demo.gate-test/alpha-test} (gate/task-required-test-syms db0 :task/alpha)))
      (is (= #{'demo.gate-test/beta-test} (gate/task-required-test-syms db0 :task/beta)))

      ;; Alpha passes, Beta has missing verdict AND open :task/prereq
      (d/transact conn {:tx-data [{:seon.test/digest "dg-alpha-1" :seon.test/var 'demo.gate-test/alpha-test
                                   :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}]})
      (let [g1 (gate/evaluate-agent-gate @conn @conn :ag/multi)]
        (is (false? (:ok? g1)))
        (is (= [:task/alpha :task/beta] (:tasks g1)))
        (is (= [:task/alpha] (:passed-tasks g1)))
        (is (= [:task/beta] (:blocked-tasks g1)))
        (is (= #{:task-needs-open :test-missing} (into #{} (map :reason) (:blockers g1))))
        (is (= #{:rule.gate/task-needs-open :rule.gate/test-missing}
               (into #{} (map :rule) (:blockers g1)))))

      ;; Direct seon.rules/step-rules check for :rule.gate/task-needs-open + :rule.gate/agent-blocked provenance
      (let [wm0 (rules/add-facts (rules/empty-wm)
                                 [[:ag/multi :gate/assigned-task :task/beta]
                                  [:task/beta :gate/needs :task/prereq]]
                                 {:rule :test/seed :stratum 0})
            sres (rules/step-rules nil wm0 {:main @conn :branch @conn} (gate/gate-rules) nil)
            ab (rules/wm-vals (:wm sres) :ag/multi :gate/blocker)]
        (is (= 1 (count ab)))
        (is (= :task-needs-open (:reason (first ab))))
        (is (= :rule.gate/task-needs-open
               (:rule (first (get-in sres [:wm :provenance [:task/beta :gate/blocker (first ab)]])))))
        (is (= :rule.gate/agent-blocked
               (:rule (first (get-in sres [:wm :provenance [:ag/multi :gate/blocker (first ab)]]))))))

      ;; Mark prereq done, but beta passes first then FAILS on newer digest -> latest digest must block!
      (d/transact conn {:tx-data [{:seon.task/id :task/prereq :seon.task/done? true}
                                  {:seon.test/digest "dg-beta-old-pass" :seon.test/var 'demo.gate-test/beta-test
                                   :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "old ok"}]})
      (d/transact conn {:tx-data [{:seon.test/digest "dg-beta-new-fail" :seon.test/var 'demo.gate-test/beta-test
                                   :seon.test/kind :deftest :seon.test/pass? false :seon.test/ms 2.0 :seon.test/report "assert failed"}]})
      (let [g2 (gate/evaluate-agent-gate @conn @conn :ag/multi)]
        (is (false? (:ok? g2)))
        (is (= [:task/alpha] (:passed-tasks g2)))
        (is (= [:task/beta] (:blocked-tasks g2)))
        (is (= [:test-failing] (mapv :reason (:blockers g2))))
        (is (= [:rule.gate/test-failing] (mapv :rule (:blockers g2))))
        (is (= "dg-beta-new-fail" (get-in g2 [:blockers 0 :detail :digest]))))

      ;; Newer passing digest supersedes failing digest -> ALL tasks pass!
      (d/transact conn {:tx-data [{:seon.test/digest "dg-beta-green" :seon.test/var 'demo.gate-test/beta-test
                                   :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.1 :seon.test/report "green"}]})
      (let [g3 (gate/evaluate-agent-gate @conn @conn :ag/multi)]
        (is (true? (:ok? g3)))
        (is (= [:task/alpha :task/beta] (:passed-tasks g3)))
        (is (empty? (:blocked-tasks g3)))
        (is (empty? (:blockers g3))))))))

(deftest ^{:cost/ticks 40000000} legacy-upserts-reconciler-merge-and-helpers-test
  (testing "Legacy :seon.agent/task + :upserted-vars, reconciler gate-blocked -> merging, handle-merge-op!, and user helpers"
    (with-test-store [{:keys [conn cfg]}]
      (setup-demo-vars! conn)
      (d/transact conn {:tx-data
                        [{:seon.task/id :task/legacy :seon.task/title "Legacy task"
                          :seon.task/done? false :seon.task/tests #{'demo.gate-test/missing-upserted-test}}
                         {:seon.agent/id :ag/legacy :seon.agent/branch :main :seon.agent/task :task/legacy}
                         {:seon.test/digest "dg-up-0001" :seon.test/var 'demo.gate-test/missing-upserted-test
                          :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}]})
      (is (false? (:ok? (gate/evaluate-agent-gate @conn @conn :ag/legacy))))
      (is (true? (:ok? (gate/evaluate-agent-gate @conn @conn :ag/legacy {:upserted-vars #{'demo.gate-test/missing-upserted-test}}))))

      ;; Reconciler + Merge integration on branched multi-task agent
      (d/transact conn {:tx-data
                        [{:seon.task/id :task/m1 :seon.task/title "M1" :seon.task/done? false
                          :seon.task/tests #{'demo.gate-test/alpha-test}}
                         {:seon.task/id :task/m2 :seon.task/title "M2" :seon.task/done? false
                          :seon.task/test-vars #{[:seon.code.var/name 'demo.gate-test/beta-test]}}
                         {:seon.test/digest "dg-m1-0001" :seon.test/var 'demo.gate-test/alpha-test
                          :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}
                         {:seon.test/digest "dg-m2-fail" :seon.test/var 'demo.gate-test/beta-test
                          :seon.test/kind :deftest :seon.test/pass? false :seon.test/ms 1.0 :seon.test/report "fail"}
                         {:seon.agent/id :ag/rec :seon.agent/branch :branch/rec :seon.agent/auto-merge? true
                          :seon.agent/tasks [[:seon.task/id :task/m1] [:seon.task/id :task/m2]]}]})
      (store/branch! conn :main :branch/rec)
      (let [bconn (store/connect cfg :branch/rec)]
        (try
          (d/transact bconn {:tx-data [{:seon.agent/id :ag/rec :seon.agent/ready? true}]})
          (d/transact conn {:tx-data [{:seon.agent/id :ag/rec :seon.agent/tested-tx (:max-tx @bconn)}]})
          (let [st-blocked (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/rec)]
            (is (= :gate-blocked (:status st-blocked)))
            (is (false? (:needs-merge? st-blocked)))
            (is (false? (get-in st-blocked [:gate :ok?])))
            (is (= [:task/m1] (get-in st-blocked [:gate :passed-tasks])))
            (is (= [:task/m2] (get-in st-blocked [:gate :blocked-tasks]))))

          ;; Partial merge marks ONLY :passed-tasks (:task/m1) done, keeping :task/m2 open
          (let [[_ _] (tmerge/handle-merge-op! {:conn conn :cfg cfg :commit-git? false :writes 0}
                                               {:op :merge :branch :branch/rec :agent-id :ag/rec}
                                               (d/commit-id @conn))]
            (is (true? (:seon.task/done? (d/pull @conn '[:seon.task/done?] [:seon.task/id :task/m1]))))
            (is (false? (:seon.task/done? (d/pull @conn '[:seon.task/done?] [:seon.task/id :task/m2])))))

          ;; Fix :task/m2 proof and branch ready -> reconciler flips to :merging & handle-merge-op! marks :task/m2 done!
          (d/transact conn {:tx-data [{:seon.test/digest "dg-m2-pass" :seon.test/var 'demo.gate-test/beta-test
                                       :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}]})
          (d/transact bconn {:tx-data [{:seon.agent/id :ag/rec :seon.agent/ready? false}]})
          (d/transact bconn {:tx-data [{:seon.agent/id :ag/rec :seon.agent/ready? true}]})
          (d/transact conn {:tx-data [{:seon.agent/id :ag/rec :seon.agent/tested-tx (:max-tx @bconn)}]})
          (let [st-ok (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/rec)]
            (is (= :merging (:status st-ok)))
            (is (true? (:needs-merge? st-ok)))
            (is (true? (get-in st-ok [:gate :ok?]))))
          (tmerge/handle-merge-op! {:conn conn :cfg cfg :commit-git? false :writes 0}
                                   {:op :merge :branch :branch/rec :agent-id :ag/rec}
                                   (d/commit-id @conn))
          (is (true? (:seon.task/done? (d/pull @conn '[:seon.task/done?] [:seon.task/id :task/m2]))))

          ;; install-user-helpers! assigns 1..N refs + primary and evaluates gate-status live
          (let [tmp-ns-sym 'seon.test.temp-gate-user
                u (create-ns tmp-ns-sym)]
            (try
              (d/transact bconn {:tx-data [{:seon.agent/id :ag/helper :seon.agent/branch :branch/rec }]})
              (gate/install-user-helpers! u (fn [] {:conn bconn :agent-id :ag/helper :branch :branch/rec}))
              (let [assign! @(ns-resolve u 'assign-tasks!)
                    gate-st @(ns-resolve u 'gate-status)
                    res (assign! [:task/m1 :task/m2])
                    gs (gate-st)]
                (is (true? (:ok? res)))
                (is (= :task/m1 (:task res)))
                (is (= [:task/m1 :task/m2] (:tasks res)))
                (is (= [:task/m1 :task/m2] (gate/agent-task-ids @bconn :ag/helper)))
                (is (true? (:ok? gs))))
              (finally
                (remove-ns tmp-ns-sym))))
          (finally
            (d/release bconn)))))))

(deftest ^{:cost/ticks 40000000} multi-task-steering-surfaces-all-scopes-and-gates-all-proofs
  (testing "multi-task :seon.agent/tasks surfaces every task scope in walk context and gates all proofs"
    (with-test-store [{:keys [conn]}]
      (setup-demo-vars! conn)
      (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/other_test.clj"
                                                      (str "(ns demo.other-test (:require [clojure.test :refer [deftest is]]))\n"
                                                           "(deftest other-one (is true))") false)})
      (d/transact conn {:tx-data
                        [{:seon.task/id :task/scope-one :seon.task/title "Scope one" :seon.task/done? false
                          :seon.task/scope 'demo.gate-test
                          :seon.task/test-vars #{[:seon.code.var/name 'demo.gate-test/alpha-test]}}
                         {:seon.task/id :task/scope-two :seon.task/title "Scope two" :seon.task/done? false
                          :seon.task/scope 'demo.other-test
                          :seon.task/test-vars #{[:seon.code.var/name 'demo.other-test/other-one]}}
                         {:seon.agent/id :ag/steer :seon.agent/branch :main :seon.agent/scope 'demo.gate-test
                          :seon.agent/tasks [[:seon.task/id :task/scope-one] [:seon.task/id :task/scope-two]]}]})
      (let [db @conn]
        (is (= [:task/scope-one :task/scope-two] (gate/agent-task-ids db :ag/steer)))
        ;; no proofs yet -> BOTH tasks blocked (all proofs gate completion)
        (let [g0 (gate/evaluate-agent-gate db db :ag/steer)]
          (is (false? (:ok? g0)))
          (is (= [:task/scope-one :task/scope-two] (:tasks g0)))
          (is (= [:task/scope-one :task/scope-two] (:blocked-tasks g0)))
          (is (empty? (:passed-tasks g0))))
        ;; only one proof -> still blocked on the other task
        (d/transact conn {:tx-data [{:seon.test/digest "dg-alpha-pass-1" :seon.test/var 'demo.gate-test/alpha-test
                                     :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}]})
        (let [g1 (gate/evaluate-agent-gate @conn @conn :ag/steer)]
          (is (false? (:ok? g1)))
          (is (= [:task/scope-one] (:passed-tasks g1)))
          (is (= [:task/scope-two] (:blocked-tasks g1))))
        ;; all proofs -> gate passes
        (d/transact conn {:tx-data [{:seon.test/digest "dg-other-pass-1" :seon.test/var 'demo.other-test/other-one
                                     :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}]})
        (is (true? (:ok? (gate/evaluate-agent-gate @conn @conn :ag/steer))))
        ;; walk context surfaces BOTH task scopes (single :seon.agent/scope + tasks union)
        (let [wt (wire/render-agent-context @conn @conn :ag/steer)]
          (is (str/includes? wt "walk-context"))
          (is (str/includes? wt "demo.gate-test"))
          (is (str/includes? wt "demo.other-test")))))))

(deftest ^{:cost/ticks 140000000} forked-review-compacts-and-merges-back-to-worker-branch
  (testing "COW-forked review compacts selected turns via compact!, edits task + fixes code on review branch, finishes via finish-review!, and merges back into worker branch excluding reviewer evals"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data
        (concat
         (code/file-import-tx @conn "src/demo/fr_wip.clj" "(ns demo.fr-wip)\n(defn wip [] :base)\n" false)
         (code/file-import-tx @conn "src/demo/fr_bug.clj" "(ns demo.fr-bug)\n(defn bug [] :broken)\n" false)
         [{:seon.task/id :task/fr-work :seon.task/title "Build fr-wip" :seon.task/plan "Initial plan" :seon.task/done? false}
          {:seon.task/id :template/review-and-improve :seon.task/title "Review" :seon.task/plan "Audit" :seon.task/template? true :seon.task/done? false}
          {:db/id "w1" :seon.agent/id :w/fr :seon.agent/branch :branch/w-fr :seon.agent/goal "Build fr-wip" :seon.agent/task :task/fr-work :seon.agent/tasks #{[:seon.task/id :task/fr-work]} :seon.agent/paused? true}])})
      (store/branch! conn :main :branch/w-fr)
      (let [wconn (store/connect cfg :branch/w-fr)]
        (try
          (let [we (:e (first (d/datoms @wconn :avet :seon.agent/id :w/fr)))]
            (d/transact wconn {:tx-data
              (concat
               (code/file-import-tx @wconn "src/demo/fr_wip.clj" "(ns demo.fr-wip)\n(defn wip [] :worker-wip)\n" false)
               [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/fr :seon.agent.eval/agent-ref we :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/code "(t1)" :seon.agent.eval/value "1"}
                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/fr :seon.agent.eval/agent-ref we :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0 :seon.agent.eval/code "(noise)" :seon.agent.eval/error "boom"}
                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/fr :seon.agent.eval/agent-ref we :seon.agent.eval/turn 3 :seon.agent.eval/call-index 0 :seon.agent.eval/code "(t3)" :seon.agent.eval/value "3"}])}))
          ;; Spawn COW-forked reviewer directly from :branch/w-fr!
          (let [st0 {:conn conn :cfg cfg :msg-seq 0 :writes 0 :commit-git? false}
                _ (tspawn/handle-spawn-agent! st0
                    {:op :spawn-agent :id :review/fr :branch :branch/rev-fr :parent :w/fr
                     :forked-from :w/fr :tasks [:template/review-and-improve] :goal "Review :w/fr"}
                    (d/commit-id @conn))
                rconn (store/connect cfg :branch/rev-fr)]
            (try
              ;; Reviewer has worker's WIP, worker's evals, AND runs its own evals + compact! + task clarity + bug fix + finish-review!
              (is (str/includes? (code/render-path @rconn "src/demo/fr_wip.clj") ":worker-wip"))
              (d/transact rconn {:tx-data
                (concat
                 (code/file-import-tx @rconn "src/demo/fr_bug.clj" "(ns demo.fr-bug)\n(defn bug {:malli/schema [:=> [:cat] :keyword] :cost/ticks 5000} [] :fixed)\n" false)
                 [{:seon.task/id :task/fr-work :seon.task/plan "Clarified plan with docs link"}
                  {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :review/fr :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/code "(compact)" :seon.agent.eval/value "ok"}])})
              (binding [fcalls/*agent-ctx* {:conn rconn :agent-id :review/fr :branch :branch/rev-fr}]
                (let [cres (fcalls/compact! {:agent-id :w/fr :turns #{2} :summary "Removed noisy turn 2"})
                      fres (fcalls/finish-review! "Compacted turn 2, clarified task plan, and fixed demo.fr-bug/bug.")]
                  (is (= 1 (:retracted-evals cres)))
                  (is (= 2 (:remaining-evals cres)))
                  (is (true? (:ok? fres)))))
              ;; Merge :branch/rev-fr -> :branch/w-fr via handle-merge-op!
              (let [[_ _] (tmerge/handle-merge-op! {:conn conn :cfg cfg :msg-seq 1 :writes 0 :commit-git? false}
                            {:op :merge :branch :branch/rev-fr :agent-id :review/fr} (d/commit-id @conn))
                    wdb-after (store/head conn :branch/w-fr)
                    mdb-after @conn
                    w-evals (mapv #(d/pull wdb-after '[:seon.agent.eval/turn :seon.agent.eval/code] (:e %))
                                  (d/datoms wdb-after :avet :seon.agent.eval/agent :w/fr))
                    rev-evals-on-w (vec (d/datoms wdb-after :avet :seon.agent.eval/agent :review/fr))
                    note (first (filter #(= :review-note (:seon.agent.message/type %))
                                        (map #(d/pull mdb-after '[*] (:e %)) (d/datoms mdb-after :avet :seon.agent.message/to :w/fr))))]
                (is (= #{1 3} (set (map :seon.agent.eval/turn w-evals))) "Noisy turn 2 retracted; turns 1 & 3 preserved!")
                (is (empty? rev-evals-on-w) "Reviewer's own evals must NOT pollute worker branch!")
                (is (= "Removed noisy turn 2" (:seon.agent/summary (d/pull wdb-after '[:seon.agent/summary] [:seon.agent/id :w/fr]))))
                (is (str/includes? (code/render-path wdb-after "src/demo/fr_bug.clj") ":fixed") "Reviewer's bug fix merged onto worker branch!")
                (is (str/includes? (code/render-path wdb-after "src/demo/fr_wip.clj") ":worker-wip") "Worker's WIP preserved!")
                (is (= "Clarified plan with docs link" (:seon.task/plan (d/pull wdb-after '[:seon.task/plan] [:seon.task/id :task/fr-work]))) "Edited task lives on worker branch for clean merge to :main")
                (is (false? (:seon.agent/paused? (d/pull mdb-after '[:seon.agent/paused?] [:seon.agent/id :w/fr]))))
                (is (str/includes? (str (:seon.agent.message/body note)) "Compacted turn 2, clarified task plan, and fixed demo.fr-bug/bug."))
                (is (str/includes? (code/render-path mdb-after "src/demo/fr_wip.clj") ":base") "Worker WIP must NOT leak onto :main!"))
              (finally (d/release rconn))))
          (finally (d/release wconn)))))))

(deftest ^{:cost/ticks 120000000} trace-audit-root-cause-fixes-test
  (testing "2-arg remember!, forked parent :last-proofs inheritance, and move-vars! :seon.code/reexport stress-var continuity"
    (with-test-store [{:keys [conn cfg]}]
      ;; 1. 2-arg remember! writes durable memory onto target agent
      (d/transact conn {:tx-data [{:seon.agent/id :w/tr1 :seon.agent/branch :branch/w-tr1 :seon.agent/goal "work" :seon.agent/last-proofs {:ok? false :failures [{:var 'foo/bar}]}}
                                  {:seon.agent/id :rev/tr1 :seon.agent/branch :branch/rev-tr1 :seon.agent/goal "rev" :seon.agent/forked-from [:seon.agent/id :w/tr1]}]})
      (binding [fcalls/*agent-ctx* {:conn conn :agent-id :rev/tr1 :branch :branch/rev-tr1}]
        (let [res (fcalls/remember! :w/tr1 "Verified split plan")]
          (is (true? (:ok? res)))
          (is (= "Verified split plan" (:seon.agent/summary (d/pull @conn '[:seon.agent/summary] [:seon.agent/id :w/tr1]))))))
      ;; 2. gather-branch-status-data inherits parent's :last-proofs when reviewer has none
      (let [db @conn
            ag (d/pull db '[*] [:seon.agent/id :rev/tr1])
            bs ((requiring-resolve 'seon.render.wire/gather-branch-status-data) db db ag [{:seon.agent.eval/turn 1}])]
        (is (= {:ok? false :failures [{:var 'foo/bar}]} (:last-proofs bs))))
      ;; 3. make-reexport-src emits :seon.code/reexport and stress-var follows it transparently
      (let [src ((requiring-resolve 'seon.code.rewrite.move/make-reexport-src) 'demo.from/adder 'child "(defn adder {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))" false 'demo.child)]
        (is (str/includes? src ":seon.code/reexport 'demo.child/adder"))))))

(deftest unified-fork-subagent-competing-branches-and-rule-examples-test
  (testing "1. Organic graph-driven :example/* matching via shared :seon.agent/tasks (zero hardcoded :examples on spawn)"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data [{:seon.task/id :template/review-and-improve :seon.task/title "Review & improve" :seon.task/done? false}
                                  {:seon.agent/id :example/clean-fork-review :seon.agent/branch :main :seon.agent/paused? true :seon.agent/goal "Clean fork review"
                                   :seon.agent/tasks #{[:seon.task/id :template/review-and-improve]}}]})
      (let [ex-eid (:e (first (d/datoms @conn :avet :seon.agent/id :example/clean-fork-review)))]
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :example/clean-fork-review
                                     :seon.agent.eval/agent-ref ex-eid :seon.agent.eval/turn 1
                                     :seon.agent.eval/code "(user/health)" :seon.agent.eval/value "{:ok? true}"}]}))
      (let [st {:conn conn :cfg cfg :msg-seq 0 :writes 0 :last-commit (d/commit-id @conn) :last-tx (:max-tx @conn)}
            [st1 _] (tspawn/handle-spawn-agent! st {:op :spawn-agent :id :w/parent :branch :branch/parent :scope 'demo.comp :goal "Parent work"} (d/commit-id @conn))
            _ (let [pconn (store/connect cfg :branch/parent)]
                (try
                  (let [tx (code/file-import-tx @pconn "src/demo/comp.clj" "(ns demo.comp)\n(defn f [] :base)\n" false)
                        pe (:e (first (d/datoms @pconn :avet :seon.agent/id :w/parent)))]
                    (d/transact pconn {:tx-data (conj tx {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/parent
                                                          :seon.agent.eval/agent-ref pe :seon.agent.eval/turn 1
                                                          :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/value "2"})}))
                  (finally (d/release pconn))))
            ;; Spawn reviewer WITHOUT passing :examples — rule must match :example/clean-fork-review via shared :seon.agent/tasks!
            [st2 _] (tspawn/handle-spawn-agent! st1 {:op :spawn-agent :id :rev/auto :branch :branch/rev-auto
                                                    :fork-from :w/parent
                                                    :merge-target [:seon.agent/id :w/parent]
                                                    :tasks [:template/review-and-improve] :goal "Review parent"} (d/commit-id @conn))
            rconn (store/connect cfg :branch/rev-auto)]
        (try
          (let [w (wire/build-live-wire-request @conn @rconn :rev/auto {:conn conn :vision? false})
                txt (str/join "\n" (keep :content (:messages w)))]
            (is (str/includes? txt "<agent id=\":w/parent\""))
            (is (str/includes? txt "<agent id=\":example/clean-fork-review\" kind=\"example\""))
            (is (= :branch/parent (:target-branch (store/fork-parent-target @conn :branch/rev-auto :rev/auto)))))
          (finally (d/release rconn)))
        (testing "2. :prefix-mode :fresh omits parent transcript while keeping COW branch inheritance"
          (let [[st3 _] (tspawn/handle-spawn-agent! st2 {:op :spawn-agent :id :w/fresh-fork :branch :branch/fresh-fork
                                                        :branch-from :w/parent :merge-target :none
                                                        :goal "Fresh look"} (d/commit-id @conn))
                fconn (store/connect cfg :branch/fresh-fork)]
            (try
              (let [w (wire/build-live-wire-request @conn @fconn :w/fresh-fork {:conn conn :vision? false})
                    txt (str/join "\n" (keep :content (:messages w)))]
                (is (not (str/includes? txt "<agent id=\":w/parent\"")))
                (is (nil? (store/fork-parent-target @conn :branch/fresh-fork :w/fresh-fork)))
                (is (false? (:seon.agent/auto-merge? (d/pull @conn '[:seon.agent/auto-merge?] [:seon.agent/id :w/fresh-fork])))))
              (finally (d/release fconn)))
            (testing "3. Competing COW forks via :create-call (ask!) stay isolated with :merge-target :none"
              (let [helpers {:reply! tmerge/reply! :db-notice tmerge/db-notice :focus-tx (fn [_] [])}
                    [st4 _] (tcalls/handle-call-op! st3 {:op :create-call :caller :w/parent :callee :w/approach-a
                                                         :prompt "Try A" :fork-from :w/parent :merge-target :none} (d/commit-id @conn) helpers)
                    [_ _] (tcalls/handle-call-op! st4 {:op :create-call :caller :w/parent :callee :w/approach-b
                                                       :prompt "Try B" :fork-from :w/parent :merge-target :none} (d/commit-id @conn) helpers)
                    a-br (:seon.agent/branch (d/pull @conn '[:seon.agent/branch] [:seon.agent/id :w/approach-a]))
                    b-br (:seon.agent/branch (d/pull @conn '[:seon.agent/branch] [:seon.agent/id :w/approach-b]))]
                (is (store/branch? conn a-br))
                (is (store/branch? conn b-br))
                (is (nil? (store/fork-parent-target @conn a-br :w/approach-a)))
                (is (nil? (store/fork-parent-target @conn b-br :w/approach-b)))
                ;; Both branches inherited demo.comp/f from :branch/parent via COW fork!
                (is (some? (first (d/datoms (store/head conn a-br) :avet :seon.code.var/name 'demo.comp/f))))
                (is (some? (first (d/datoms (store/head conn b-br) :avet :seon.code.var/name 'demo.comp/f))))))))))))