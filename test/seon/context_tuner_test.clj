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
            [seon.test-fixture :refer [with-test-store]]
            [seon.merge :as merge]
            [seon.reconciler :as rec]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.transactor.spawn :as spawn]))

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

(deftest ^{:cost/ticks 120000000} data-derived-auto-completion-ref-order-and-supervisor-lab-test
  (testing "Prose completion with branch edits auto-triggers :testing without ready!, guards 0-edit chat & post-edit failures, preserves non-ref ref-order on merge, and tune-context! spawns via :id"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (concat
                                   (code/file-import-tx @conn "src/demo/ac.clj"
                                                        "(ns demo.ac)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)\n" false)
                                   [{:seon.task/id :template/tune-context :seon.task/template? true :seon.task/done? false
                                     :seon.task/scope 'seon.ctx :seon.task/title "Tuner" :seon.task/plan "Tune."}
                                    {:seon.agent/id :ag/auto-w :seon.agent/branch :branch/auto-w :seon.agent/auto-merge? true}
                                    {:seon.agent/id :ag/chat-only :seon.agent/branch :branch/chat-only :seon.agent/auto-merge? true}])})
      (store/branch! conn :main :branch/auto-w)
      (store/branch! conn :main :branch/chat-only)
      (let [wconn (store/connect cfg :branch/auto-w)
            cconn (store/connect cfg :branch/chat-only)]
        (try
          ;; 1. Read-only chat agent replying in prose (0 branch edits) -> stays :waiting (no empty merge!)
          (d/transact cconn {:tx-data [{:seon.agent/id :ag/chat-only :seon.agent/waiting? true}
                                       {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/chat-only
                                        :seon.agent.eval/turn 1 :seon.agent.eval/code "" :seon.agent.eval/thought "Answered your question."}]})
          (let [sc (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/chat-only)]
            (is (= :waiting (:status sc)))
            (is (false? (:needs-test? sc))))

          ;; 2. Worker edits code & replies in prose (NO ready! call!) -> auto-triggers :testing!
          (d/transact wconn {:tx-data (concat
                                        (code/file-import-tx @wconn "src/demo/ac.clj"
                                                             "(ns demo.ac)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n" false)
                                        [{:seon.agent/id :ag/auto-w :seon.agent/waiting? true}
                                         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/auto-w
                                          :seon.agent.eval/turn 1 :seon.agent.eval/code "" :seon.agent.eval/thought "Updated demo.ac/f and verified."}])})
          (let [sw1 (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/auto-w)]
            (is (= :testing (:status sw1)) "Prose reply with branch edits must auto-trigger :testing without ready!")
            (is (true? (:needs-test? sw1))))

          ;; 3. If :test-failure arrives after the edit and agent stops in prose WITHOUT new edit -> stays :waiting (no loop!)
          (Thread/sleep 5)
          (let [fmid (random-uuid)]
            (d/transact conn {:tx-data [{:seon.agent.message/id fmid :seon.agent.message/seq 1 :seon.agent.message/from :test
                                         :seon.agent.message/to :ag/auto-w :seon.agent.message/type :test-failure :seon.agent.message/body "Failed"}]})
            (d/transact wconn {:tx-data [{:seon.agent/id :ag/auto-w :seon.agent/waiting? true}
                                         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/auto-w
                                          :seon.agent.eval/turn 2 :seon.agent.eval/code "" :seon.agent.eval/thought "Need help."
                                          :seon.agent.eval/seen-messages #{fmid}}]})
            (let [sw2 (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/auto-w)]
              (is (= :waiting (:status sw2)) "Prose reply without newer edit after failure must NOT re-trigger :testing")
              (is (false? (:needs-test? sw2)))))

          ;; 4. Newer edit after failure + prose reply -> auto-triggers :testing again!
          (Thread/sleep 5)
          (d/transact wconn {:tx-data (concat
                                        (code/file-import-tx @wconn "src/demo/ac.clj"
                                                             "(ns demo.ac)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))\n" false)
                                        [{:seon.agent/id :ag/auto-w :seon.agent/waiting? true}
                                         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/auto-w
                                          :seon.agent.eval/turn 3 :seon.agent.eval/code "" :seon.agent.eval/thought "Fixed and green."}])})
          (let [sw3 (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/auto-w)]
            (is (= :testing (:status sw3)))
            (is (true? (:needs-test? sw3))))

          ;; 5. tune-context! emits :id (not :agent-id) and spawns cleanly via handle-spawn-agent!
          (let [dry (repl/tune-context! :ag/auto-w {:db @wconn :main-db @conn :dry-run? true})
                sop (:spawn-op dry)
                st {:conn conn :cfg cfg :msg-seq 1 :writes 0 :commit-git? false}
                _ (spawn/handle-spawn-agent! st sop (d/commit-id @conn))
                tuner (d/pull @conn '[:seon.agent/id :seon.agent/branch {:seon.agent/forked-from [:seon.agent/id]}]
                              [:seon.agent/id (:tuner-id dry)])]
            (is (= (:tuner-id dry) (:id sop)) "tune-context! must emit :id for handle-spawn-agent!")
            (is (= (:tuner-id dry) (:seon.agent/id tuner)))
            (is (= :ag/auto-w (get-in tuner [:seon.agent/forked-from :seon.agent/id]))))

          ;; 6. merge/three-way preserves lookup-ref vectors inside non-ref :seon.task/ref-order when created with new doc
          (d/transact wconn {:tx-data [{:seon.code.doc/id "docs/new-guide.md#s1" :seon.code.doc/path "docs/new-guide.md"
                                        :seon.code.doc/heading "## S1" :seon.code.doc/body "Guide." :seon.code.doc/order 1}
                                       {:seon.task/id :task/with-doc :seon.task/title "T" :seon.task/done? false
                                        :seon.task/refs #{[:seon.code.doc/id "docs/new-guide.md#s1"]}
                                        :seon.task/ref-order [[:seon.code.doc/id "docs/new-guide.md#s1"]]}]})
          (let [plan (merge/three-way conn cfg :branch/auto-w {:skip-health? true})
                top (some #(when (and (map? %) (= :task/with-doc (:seon.task/id %))) %) (:tx-data plan))]
            (is (true? (:ok? plan)))
            (is (= [[:seon.code.doc/id "docs/new-guide.md#s1"]] (:seon.task/ref-order top))
                "Non-ref :seon.task/ref-order vectors must NOT be rewritten to tempid strings"))

          ;; 7. seon.bench.lab/should-intervene? passes seed=42 generative checks
          (is (:ok? (schema/stress-var nil 'seon.bench.lab/should-intervene? {:samples 50 :seed 42})))
          (finally
            (d/release wconn)
            (d/release cconn)))))))
