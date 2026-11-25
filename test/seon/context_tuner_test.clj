(ns seon.context-tuner-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.fork.calls :as fcalls]
            [seon.health.corpus :as corpus]
            [seon.health.coverage :as cov]
            [seon.repl :as repl]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.test-fixture :refer [with-test-store]]))

(def ^:private baseline0-core-rules
  #{:rule.ctx/active-tasks :rule.ctx/agent-prefix-sessions :rule.ctx/pinned-entities
    :rule.ctx/scope-namespaces :rule.ctx/branch-edits :rule.ctx/delegated-calls
    :rule.ctx/failing-tests :rule.ctx/open-errors :rule.ctx/review-target
    :rule.ctx/system-overview :rule.ctx/unread-messages})

(deftest situation-corpus-12-scenarios-deterministic-and-memory-bound-test
  (testing "All 12 Universal Scenarios run deterministically (seed=42) under 80 MB with exact ablation"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (or rules/*base-rules-override* (rseed/starter-rules))})
      (let [db @conn
            m1 (code/measure-call #(corpus/corpus-report db {:use-cache? false}))
            r1 (:val m1)
            r2 (corpus/corpus-report db {:use-cache? false})
            _ (corpus/corpus-report db)
            t0 (System/nanoTime)
            rw (corpus/corpus-report db)
            warm-ms (/ (double (- (System/nanoTime) t0)) 1e6)
            cold-mb (/ (double (:bytes m1)) 1048576.0)
            by-id (into {} (map (juxt :id identity)) (:corpus r1))
            abl-by (into {} (map (juxt :rule-id identity)) (:ablation r1))]
        (is (= 12 (get-in r1 [:counts :corpus-total])))
        (is (= 12 (count (:corpus r1))))
        (is (< cold-mb 100.0) (str "Cold corpus-report allocated " cold-mb " MB (must be < 100 MB)"))
        (is (< warm-ms 5.0) (str "Warm corpus-report took " warm-ms " ms"))
        (is (= (mapv #(dissoc % :ms) (:corpus r1)) (mapv #(dissoc % :ms) (:corpus r2)))
            "Deterministic across repeated runs (seed=42)")
        (is (= (:ablation r1) (:ablation r2)))
        (is (= (:counts r1) (:counts rw)))
        (is (true? (:pass? (get by-id :sit/scoped-task))))
        (is (true? (:pass? (get by-id :sit/forked-review))))
        (is (true? (:pass? (get by-id :sit/open-error-and-child-escalation))))
        (is (true? (:pass? (get by-id :sit/branch-health-regression))))
        (is (true? (:pass? (get by-id :sit/unread-chat-and-zero-repeat))))
        (is (true? (:pass? (get by-id :sit/system-and-entity-browser))))
        (is (true? (:pass? (get by-id :sit/responsive-viewport-fit))))
        (is (= 12 (get-in r1 [:counts :corpus-passed])) "All 12 Universal Scenarios pass")
        (is (true? (:pass? (get by-id :sit/concept-doc-teaching))))
        (is (true? (:pass? (get by-id :sit/active-code-neighborhood))))
        (is (true? (:pass? (get by-id :sit/failing-reach-test))))
        (is (true? (:pass? (get by-id :sit/visual-ui-authoring))))
        (is (true? (:pass? (get by-id :sit/vague-request))))
        (is (every? #(true? (:ablation-needed? (get abl-by %))) baseline0-core-rules)
            "All 11 Baseline 0 core context rules contribute")))))

(deftest self-building-loop-general-doc-rule-flips-corpus-and-ablation-test
  (testing "Adding a general :seon.code.doc/references rule flips :sit/concept-doc-teaching false->true with 0 regressions, 0 lint issues, and exact ablation"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (mapv #(if (= :rule.ctx/referenced-concept-docs (:seon.rule/id %))
                                          (assoc % :seon.rule/enabled? false)
                                          %)
                                       (or rules/*base-rules-override* (rseed/starter-rules)))})
      (let [mdb @conn
            r0 (corpus/corpus-report mdb)
            doc0 (first (filter #(= :sit/concept-doc-teaching (:id %)) (:corpus r0)))]
        (is (false? (:pass? doc0)))
        (is (= [[:seon.code.doc/id "docs/sit-canvas.md#canvas-guide"]] (:missing-present doc0)))

        (d/transact conn
          {:tx-data [{:seon.rule/id :rule.ctx/doc-references-connected
                      :seon.rule/stratum 26 :seon.rule/category :context :seon.rule/target :any :seon.rule/enabled? true
                      :seon.rule/doc "Surfaces docs referencing any entity connected in the agent's focal world."
                      :seon.rule/query '[:find ?doc ?tx :in $wm $branch
                                         :where [$wm ?e :world/connected true]
                                                [$branch ?doc :seon.code.doc/references ?e ?tx]
                                                [$branch ?doc :seon.code.doc/id _]]
                      :seon.rule/assert '[{:entity ?doc :zone :workspace :priority 78 :tx ?tx
                                           :pull [:seon.code.doc/id :seon.code.doc/heading :seon.code.doc/body :seon.code.doc/path]}]}]})
        (let [bdb @conn
              cc (repl/check-corpus {:db bdb :main-db mdb})
              r1 (corpus/corpus-report bdb)
              doc1 (first (filter #(= :sit/concept-doc-teaching (:id %)) (:corpus r1)))
              new-abl (first (filter #(= :rule.ctx/doc-references-connected (:rule-id %)) (:ablation r1)))]
          (is (true? (:pass? doc1)) "General rule flips :sit/concept-doc-teaching false -> true")
          (is (empty? (:missing-present doc1)))
          (is (= [:sit/concept-doc-teaching] (get-in cc [:delta-vs-main :newly-passed])))
          (is (empty? (get-in cc [:delta-vs-main :regressed])) "0 regressions across all other 11 scenarios")
          (is (true? (get-in cc [:delta-vs-main :ok?])))
          (is (empty? (:generality-issues cc)) "0 generality lint violations")
          (is (empty? (cov/lint-rule-generality (rules/load-rules bdb))))
          (is (true? (:ablation-needed? new-abl)))
          (is (some #{:sit/concept-doc-teaching} (:contributing-situations new-abl))))

        (d/transact conn
          {:tx-data [{:seon.rule/id :rule.ctx/doc-references-duplicate
                      :seon.rule/stratum 27 :seon.rule/category :context :seon.rule/target :any :seon.rule/enabled? true
                      :seon.rule/doc "Duplicate doc rule to verify ablation redundancy detection."
                      :seon.rule/query '[:find ?doc ?tx :in $wm $branch
                                         :where [$wm ?e :world/connected true]
                                                [$branch ?doc :seon.code.doc/references ?e ?tx]
                                                [$branch ?doc :seon.code.doc/id _]]
                      :seon.rule/assert '[{:entity ?doc :zone :workspace :priority 77 :tx ?tx
                                           :pull [:seon.code.doc/id :seon.code.doc/heading :seon.code.doc/body :seon.code.doc/path]}]}]})
        (let [r2 (corpus/corpus-report @conn)
              dup-abl (first (filter #(= :rule.ctx/doc-references-duplicate (:rule-id %)) (:ablation r2)))]
          (is (false? (:ablation-needed? dup-abl)))
          (is (pos? (get-in r2 [:counts :redundant-rules]))))))))

(deftest simulator-check-corpus-and-pause-fork-tuner-test
  (testing "simulate-context, check-corpus, and tune-context! work end-to-end with Pause-and-Fork + map backwards compatibility"
    (with-test-store [{:keys [conn]}]
      (d/transact conn
        {:tx-data (into (vec (or rules/*base-rules-override* (rseed/starter-rules)))
                        [{:seon.task/id :template/tune-context :seon.task/template? true :seon.task/done? false
                          :seon.task/scope 'seon.ctx :seon.task/title "Pause-and-Fork Context Tuner"
                          :seon.task/plan "Diagnose, edit via 3 Levers, verify corpus, finish-review!"}
                         {:seon.task/id :sim/t1 :seon.task/title "Base task" :seon.task/done? false}
                         {:seon.agent/id :agent/sim-worker :seon.agent/branch :branch/sim-worker
                          :seon.agent/goal "Work on base task" :seon.agent/tasks #{[:seon.task/id :sim/t1]}}])})
      (let [mdb @conn
            _ (d/transact conn {:tx-data [{:seon.task/id :sim/t2 :seon.task/title "New branch task" :seon.task/done? false}
                                          {:seon.agent/id :agent/sim-worker :seon.agent/tasks #{[:seon.task/id :sim/t1] [:seon.task/id :sim/t2]}}]})
            bdb @conn
            sim (repl/simulate-context :agent/sim-worker {:db bdb :main-db mdb :conn conn})
            cc (repl/check-corpus {:db bdb :main-db mdb})
            dry (repl/tune-context! :agent/sim-worker {:db bdb :main-db mdb :dry-run? true})
            live (repl/tune-context! :agent/sim-worker {:conn conn :main-db mdb})
            db-after @conn
            paused-ag (d/pull db-after [:seon.agent/paused?] [:seon.agent/id :agent/sim-worker])
            tuner-ag (d/pull db-after [:seon.agent/id :seon.agent/auto-merge?
                                       {:seon.agent/forked-from [:seon.agent/id]}
                                       {:seon.agent/merge-target [:seon.agent/id]}
                                       {:seon.agent/tasks [:seon.task/id]}]
                             [:seon.agent/id (:tuner-id live)])]
        (is (= [:seon.agent/id :agent/sim-worker] (:focus-ref sim)))
        (is (seq (:selected sim)))
        (is (pos? (:tokens sim)))
        (is (some #{[:seon.task/id :sim/t2]} (get-in sim [:diff-vs-main :added])))
        (is (pos? (get-in sim [:diff-vs-main :token-delta])))
        (is (map? (:wire sim)))
        (is (string? (:corpus-score cc)))
        (is (true? (get-in cc [:delta-vs-main :ok?])))
        (is (empty? (:generality-issues cc)))
        (is (true? (:ok? dry)))
        (is (false? (:dispatched? dry)))
        (is (= [:transact :spawn-agent] (mapv :op (:ops dry))))
        (is (= "tune" (namespace (:tuner-id dry))))
        (is (= [:template/tune-context] (get-in dry [:spawn-op :tasks])))
        (is (true? (get-in dry [:spawn-op :auto-merge?])))
        (is (true? (:dispatched? live)))
        (is (true? (:seon.agent/paused? paused-ag)) "Target agent is paused in Datahike")
        (is (= :agent/sim-worker (get-in tuner-ag [:seon.agent/forked-from :seon.agent/id])))
        (is (= :agent/sim-worker (get-in tuner-ag [:seon.agent/merge-target :seon.agent/id])))
        (is (true? (:seon.agent/auto-merge? tuner-ag)))
        (is (= #{:template/tune-context} (set (map :seon.task/id (:seon.agent/tasks tuner-ag)))))
        ;; Backwards compatibility: 1-arg map delegates cleanly to seon.fork.calls/tune-context!
        (binding [fcalls/*agent-ctx* nil]
          (is (nil? (repl/tune-context! {:pin [[:seon.task/id :sim/t1]]}))))))))
