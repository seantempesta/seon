(ns seon.task-gate-test
  "Unit and integration tests for multi-task agents (`:seon.agent/tasks`), true ref proofs
   (`:seon.task/proofs`), declarative `:rule.gate/*` gating, `seon.reconciler`, and `seon.transactor.merge`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.reconciler :as rec]
            [seon.rules :as rules]
            [seon.store :as store]
            [seon.task.gate :as gate]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.merge :as tmerge]))

(defn- setup-demo-vars! [conn]
  (let [src (str "(ns demo.gate-test (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest alpha-test (is (= 2 (+ 1 1))))\n"
                 "(deftest beta-test (is (= 4 (* 2 2))))\n"
                 "(deftest gamma-test (is true))")]
    (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/gate_test.clj" src false)})))

(deftest ^{:cost/ticks 40000000} multi-task-ref-proofs-and-rule-provenance-test
  (testing "Multi-task :seon.agent/tasks + :seon.task/proofs (:db.type/ref) + latest-digest gating + :seon.task/needs"
    (with-test-store [{:keys [conn]}]
      (setup-demo-vars! conn)
      (d/transact conn {:tx-data
                        [{:seon.task/id :task/prereq :seon.task/title "Prerequisite" :seon.task/done? false}
                         {:seon.task/id :task/alpha :seon.task/title "Alpha legacy tests"
                          :seon.task/done? false :seon.task/tests #{'demo.gate-test/alpha-test}}
                         {:seon.task/id :task/beta :seon.task/title "Beta ref proofs"
                          :seon.task/done? false :seon.task/proofs #{[:seon.code.var/name 'demo.gate-test/beta-test]}
                          :seon.task/needs #{:task/prereq}}
                         {:seon.agent/id :ag/multi :seon.agent/branch :main :seon.agent/role :worker
                          :seon.agent/tasks [[:seon.task/id :task/alpha] [:seon.task/id :task/beta]]}]})
      (let [db0 @conn]
        (is (= :db.type/ref (get-in db0 [:schema :seon.agent/tasks :db/valueType])))
        (is (= :db.type/ref (get-in db0 [:schema :seon.task/proofs :db/valueType])))
        (is (= [:task/alpha :task/beta] (gate/agent-task-ids db0 :ag/multi)))
        (is (= #{'demo.gate-test/alpha-test} (gate/task-required-proof-syms db0 :task/alpha)))
        (is (= #{'demo.gate-test/beta-test} (gate/task-required-proof-syms db0 :task/beta)))

        ;; Alpha passes, Beta has missing verdict AND open :task/prereq
        (d/transact conn {:tx-data [{:seon.test/digest "dg-alpha-1" :seon.test/var 'demo.gate-test/alpha-test
                                     :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}]})
        (let [g1 (gate/evaluate-agent-gate @conn @conn :ag/multi)]
          (is (false? (:ok? g1)))
          (is (= [:task/alpha :task/beta] (:tasks g1)))
          (is (= [:task/alpha] (:passed-tasks g1)))
          (is (= [:task/beta] (:blocked-tasks g1)))
          (is (= #{:task-needs-open :proof-missing} (into #{} (map :reason) (:blockers g1))))
          (is (= #{:rule.gate/task-needs-open :rule.gate/proof-missing}
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
          (is (= [:proof-failing] (mapv :reason (:blockers g2))))
          (is (= [:rule.gate/proof-failing] (mapv :rule (:blockers g2))))
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
                         {:seon.agent/id :ag/legacy :seon.agent/branch :main :seon.agent/role :worker
                          :seon.agent/task :task/legacy}
                         {:seon.test/digest "dg-up-1" :seon.test/var 'demo.gate-test/missing-upserted-test
                          :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}]})
      (is (false? (:ok? (gate/evaluate-agent-gate @conn @conn :ag/legacy))))
      (is (true? (:ok? (gate/evaluate-agent-gate @conn @conn :ag/legacy {:upserted-vars #{'demo.gate-test/missing-upserted-test}}))))

      ;; Reconciler + Merge integration on branched multi-task agent
      (d/transact conn {:tx-data
                        [{:seon.task/id :task/m1 :seon.task/title "M1" :seon.task/done? false
                          :seon.task/tests #{'demo.gate-test/alpha-test}}
                         {:seon.task/id :task/m2 :seon.task/title "M2" :seon.task/done? false
                          :seon.task/proofs #{[:seon.code.var/name 'demo.gate-test/beta-test]}}
                         {:seon.test/digest "dg-m1" :seon.test/var 'demo.gate-test/alpha-test
                          :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1.0 :seon.test/report "ok"}
                         {:seon.test/digest "dg-m2-fail" :seon.test/var 'demo.gate-test/beta-test
                          :seon.test/kind :deftest :seon.test/pass? false :seon.test/ms 1.0 :seon.test/report "fail"}
                         {:seon.agent/id :ag/rec :seon.agent/branch :branch/rec :seon.agent/role :worker
                          :seon.agent/auto-merge? true
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
              (d/transact bconn {:tx-data [{:seon.agent/id :ag/helper :seon.agent/branch :branch/rec :seon.agent/role :worker}]})
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
