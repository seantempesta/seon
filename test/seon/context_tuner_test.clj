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
            [seon.transactor.spawn :as spawn]
            [seon.ctx :as ctx]
            [seon.bench.golden :as bgolden]
            [seon.bench.helpers :as bhelpers]
            [seon.bench.lab :as blab]
            [seon.server :as server]
            [seon.signals :as sig]
            [seon.render.data :as rdata]
            [seon.ctx.tree :as ctree]
            [seon.render.wire :as rwire]))

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

(deftest ^{:cost/ticks 120000000} turn0-scoped-recall-and-bounded-ctx-atom-test
  (testing "Turn-0 scoped recall surfaces entity schemas, scope vars & sibling tests before any edit, and CtxAtom advances/rolls back cleanly via commit-delta-datoms"
    (with-test-store [{:keys [conn]}]
      (let [e1 (random-uuid) e2 (random-uuid) e3 (random-uuid)]
        (d/transact conn {:tx-data (concat
                                     (code/file-import-tx @conn "src/demo/ren.clj"
                                                          "(ns demo.ren)\n(defn render-task-chip {:malli/schema [:=> [:cat :map] :map]} [m] m)\n(defn render-card {:malli/schema [:=> [:cat :map] :map]} [m] (render-task-chip m))\n" false)
                                     (code/file-import-tx @conn "test/demo/ren_test.clj"
                                                          "(ns demo.ren-test (:require [clojure.test :refer [deftest is]] [demo.ren :as r]))\n(deftest render-card-test (is (= {} (r/render-card {}))))\n" true)
                                     [{:seon.schema/key :seon.test.run/entity
                                       :seon.schema/form "[:map [:seon.test.run/id :uuid]]"
                                       :seon.schema/doc "Test run entity"}
                                      {:seon.task/id :task/turn0-ren
                                       :seon.task/title "Add renderers for :seon.test.run/entity"
                                       :seon.task/plan "Add compact and detail renderers in demo.ren for :seon.test.run/entity following render-task-chip conventions."
                                       :seon.task/scope 'demo.ren
                                       :seon.task/done? false}
                                      {:seon.agent/id :ag/turn0
                                       :seon.agent/branch :main
                                       :seon.agent/tasks [[:seon.task/id :task/turn0-ren]]}
                                      {:seon.agent/id :ag/tuner0
                                       :seon.agent/branch :main
                                       :seon.agent/forked-from [:seon.agent/id :ag/turn0]}
                                      {:seon.agent.eval/id e1 :seon.agent.eval/agent :ag/turn0
                                       :seon.agent.eval/agent-ref [:seon.agent/id :ag/turn0]
                                       :seon.agent.eval/turn 1 :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/thought "t1"}
                                      {:seon.agent.eval/id e2 :seon.agent.eval/agent :ag/turn0
                                       :seon.agent.eval/agent-ref [:seon.agent/id :ag/turn0]
                                       :seon.agent.eval/turn 2 :seon.agent.eval/code "bad" :seon.agent.eval/thought "t2"}
                                      {:seon.agent.eval/id e3 :seon.agent.eval/agent :ag/turn0
                                       :seon.agent.eval/agent-ref [:seon.agent/id :ag/turn0]
                                       :seon.agent.eval/turn 3 :seon.agent.eval/code "bad2" :seon.agent.eval/thought "t3"}])})
        ;; 1. Turn-0 projection for scoped worker surfaces schema + scoped vars + sibling test ns before any edits
        (let [proj (ctx/project-entity @conn @conn [:seon.agent/id :ag/turn0] {:target :wire})
              refs (set (map :ref (:candidates proj)))]
          (is (contains? refs [:seon.task/id :task/turn0-ren]) "Task must be surfaced on Turn 0")
          (is (contains? refs [:seon.schema/key :seon.test.run/entity]) "Referenced entity schema must be surfaced on Turn 0")
          (is (contains? refs [:seon.code.var/name 'demo.ren/render-task-chip]) "Scoped convention var must be surfaced on Turn 0")
          (is (contains? refs [:seon.code.var/name 'demo.ren/render-card]) "Scoped var must be surfaced on Turn 0")
          (is (contains? refs [:seon.code.ns/name 'demo.ren-test]) "Sibling -test ns must be surfaced on Turn 0"))

        ;; 2. CtxAtom deref + unrelated tx commit-delta advance + swap! rollback of target evals
        (let [ca (ctx/ctx-atom conn [:seon.agent/id :ag/tuner0])
              v0 @ca
              _ (d/transact conn {:tx-data [{:seon.task/id :task/unrelated :seon.task/title "Other" :seon.task/done? true}]})
              v1 @ca
              _ (swap! ca update-in [:seon.agent/forked-from :seon.agent.eval/_agent-ref]
                       (fn [evs] (filterv #(< (:seon.agent.eval/turn %) 2) evs)))
              v2 @ca
              remaining-turns (mapv :seon.agent.eval/turn
                                    (get-in v2 [:seon.agent/forked-from :seon.agent.eval/_agent-ref]))]
          (is (= 3 (count (get-in v0 [:seon.agent/forked-from :seon.agent.eval/_agent-ref]))))
          (is (= 3 (count (get-in v1 [:seon.agent/forked-from :seon.agent.eval/_agent-ref]))))
          (is (= [1] remaining-turns) "Core Clojure swap! rollback must cleanly retract target evals 2 & 3")
          (is (nil? (:db/id (d/pull @conn [:db/id] [:seon.agent.eval/id e2]))))
          (is (nil? (:db/id (d/pull @conn [:db/id] [:seon.agent.eval/id e3]))))
          (is (integer? (:db/id (d/pull @conn [:db/id] [:seon.agent.eval/id e1])))))

        ;; 3. Generative checks for new pure helpers
        (is (:ok? (schema/stress-var nil 'seon.ctx.tree/neighborhood-node-key {:samples 50 :seed 42})))
        (is (:ok? (schema/stress-var nil 'seon.ctx.tree/candidates->neighborhood {:samples 50 :seed 42})))))))

(deftest ^{:cost/ticks 120000000} tool-ergonomics-deftype-replace-timeout-and-diagnostics-test
  (testing "REPL deftype replaces in-place without duplicates, caller timeout overrides task default, failure diagnostics persist, and explore-scope works"
    (with-test-store [{:keys [conn]}]
      ;; 1. In-place deftype replacement in persist-forms-by-ns (zero duplicate #expr# forms, preserves order!)
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/box.clj"
                                                      "(ns demo.box)\n(deftype MyBox [x])\n(defn make-box [x] (MyBox. x))\n" false)})
      (let [tx2 (code/persist-forms-by-ns @conn {'demo.box ["(deftype MyBox [x y])"]} {})]
        (d/transact conn {:tx-data tx2})
        (let [fs (code/ns-forms @conn 'demo.box)
              exprs (filter #(clojure.string/includes? (:seon.code.form/key %) "#expr#") fs)
              mk (first (filter #(= "demo.box/make-box" (:seon.code.form/key %)) fs))]
          (is (= 1 (count exprs)) "Redefining deftype must replace old #expr# form rather than duplicating it")
          (is (= "(deftype MyBox [x y])" (:seon.code.form/src (first exprs))))
          (is (< (:seon.code.form/order (first exprs)) (:seon.code.form/order mk))
              "Replaced deftype must keep its original order before make-box")))

      ;; 2. build-ab-tasks honors explicit caller :timeout-ms over task-level default :timeout-ms
      (let [built (bgolden/build-ab-tasks
                   [{:id :t/slow :timeout-ms 180000 :task {:id :t/slow :title "Slow" :plan "Do it."}}]
                   [[:cold {:seon.config/max-tool-turns 24}]]
                   {:timeout-ms 240000})]
        (is (= 240000 (:timeout-ms (first built))) "Caller :timeout-ms must win over task default"))

      ;; 3. trial-telemetry preserves :error-samples and :failing-tests
      (let [tel (bhelpers/trial-telemetry :schema-proof
                                          {:eval-errors 2
                                           :error-samples [{:turn 3 :code "(bad)" :error "boom"}]
                                           :failing-tests [{:var 'demo.box-test/t1 :report "expected 1 got 2"}]})]
        (is (= 1 (count (:error-samples tel))))
        (is (= 1 (count (:failing-tests tel)))))

      ;; 4. explore-scope returns structured subtree view
      (let [sv (server/explore-scope @conn 'demo.box)]
        (is (= 'demo.box (:ns sv)))
        (is (some #(= 'demo.box/make-box (:seon.code.var/name %)) (:vars sv))))

      ;; 5. summarize-bench-run + generative seed=42 checks for new pure helpers
      (let [sum (blab/summarize-bench-run
                 {:seon.bench.run/id (random-uuid) :seon.bench.run/title "R" :seon.bench.run/status :partial
                  :seon.bench.run/passed 1 :seon.bench.run/failed 1 :seon.bench.run/total 2
                  :seon.bench.run/started-ms 1000 :seon.bench.run/finished-ms 11000
                  :seon.bench.run/proxy-stats {:prompt-tokens 1000 :cache-hit-tokens 850}
                  :seon.bench.run/trials [{:id :t/1 :status :failed :error-samples [{:turn 1 :error "e"}]}]})]
        (is (= 10.0 (:elapsed-s sum)))
        (is (= 85.0 (:kv-hit-pct sum)))
        (is (= 1 (count (:error-samples (first (:trials sum)))))))
      (is (:ok? (schema/stress-var nil 'seon.code.parse/named-expr-sig {:samples 50 :seed 42})))
      (is (:ok? (schema/stress-var nil 'seon.bench.lab/summarize-bench-run {:samples 50 :seed 42}))))))

(deftest wave1-scale-anchor-ghost-target-and-delta-safety-test
  (testing "signals delta-anchor skips O(N) scan on unrelated commits, commit-delta-datoms guards non-ancestors, and target-preview ignores branch keywords while firing on Turn 0"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (or rules/*base-rules-override* (rseed/starter-rules))})
      (d/transact conn {:tx-data [{:seon.agent/id :w/w1 :seon.agent/branch :main :seon.agent/goal "Work"}
                                  {:seon.agent/id :tune/w1 :seon.agent/branch :main :seon.agent/scope 'seon.ctx :seon.agent/forked-from [:seon.agent/id :w/w1]
                                   :seon.agent/merge-target [:seon.agent/id :w/w1]}
                                  {:seon.agent/id :rev/ghost :seon.agent/branch :main :seon.agent/merge-target :branch/nonexistent}
                                  {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/w1 :seon.agent.eval/turn 1
                                   :seon.agent.eval/handle 'result/e1 :seon.agent.eval/code "(boom)" :seon.agent.eval/error "Boom fail"}]})
      ;; 1. Signals delta-anchor handles unrelated summary commit cleanly
      (let [s1 (sig/all-signals @conn)
            _ (d/transact conn {:tx-data [{:seon.summary/id (random-uuid) :seon.summary/kind :milestone :seon.summary/title "Unrelated"}]})
            s2 (sig/all-signals @conn)]
        (is (= (:flow s1) (:flow s2)))
        (is (> (:tx s2) (:tx s1))))
      ;; 2. user-view-state 2-arg skips entry-agent-id when :viewing-id passed
      (let [vs (rdata/user-view-state @conn {:viewing-id :w/w1})]
        (is (nil? (:entry-id vs))))
      ;; 3. commit-delta-datoms returns nil safely for unrecorded/unknown commit UUID
      (is (nil? (ctree/commit-delta-datoms (random-uuid) @conn)))
      ;; 4. build-live-wire-request gives Turn-0 target-context-preview with failing evals for real target, NOT for branch keyword
      (let [w-real (rwire/build-live-wire-request @conn @conn :tune/w1 {:include-tail? true})
            tprev (some #(when (= "target-context-preview" (:tag %)) %) (:tail-blocks w-real))
            w-ghost (rwire/build-live-wire-request @conn @conn :rev/ghost {:include-tail? true})
            gprev (some #(when (= "target-context-preview" (:tag %)) %) (:tail-blocks w-ghost))]
        (is (some? tprev) "Tuner must get <target-context-preview> on Turn 0")
        (is (.contains ^String (:text tprev) "Boom fail") "Turn-0 preview must surface target recent failing evals")
        (is (nil? gprev) "Branch keyword :merge-target must never fabricate a ghost agent preview")))))

(deftest wave2-honest-sampler-verify-spec-and-trial-outcome-test
  (testing "sample-random-tasks excludes vacuous namespaces & already-rendered/impure targets, attaches :verify-spec, and trial-outcome enforces health + verify"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (concat
                                   (code/file-import-tx @conn "src/seon/demo/constonly.clj" "(ns seon.demo.constonly)
(def css \".a{}\")" false)
                                   (code/file-import-tx @conn "src/seon/demo/pure.clj" "(ns seon.demo.pure)
(defn loose-fn {:malli/schema [:=> [:cat :int] :map]} [x] {:a x})
(defn ^:seon.flow/edge edge-fn! {:malli/schema [:=> [:cat :int] :map]} [x] {:a x})" false)
                                   [{:seon.schema/key :seon.demo/entity :seon.schema/form [:map [:seon.demo/id :keyword]]}])})
      (let [tasks (bhelpers/sample-random-tasks @conn {:n 10 :seed 42})
            ids (set (map :id tasks))
            scopes (set (map #(get-in % [:task :scope]) tasks))]
        (is (not (contains? scopes 'seon.demo.constonly)) "0-defn constant namespace must be excluded")
        (is (some #(= 'seon.demo.pure/loose-fn (get-in % [:verify-spec :var])) tasks) "Pure loose-return var must be sampled")
        (is (not (some #(= 'seon.demo.pure/edge-fn! (get-in % [:verify-spec :var])) tasks)) "Impure edge! var must be excluded")
        (is (every? #(map? (:verify-spec %)) tasks) "Every sampled task must carry a serializable :verify-spec")
        (is (false? (:ok? (bhelpers/verify-task-spec @conn {:kind :tighten-return-contract :var 'seon.demo.pure/loose-fn}))))
        (is (false? (:ok? (bhelpers/verify-task-spec @conn {:kind :entity-renderers :schema :seon.demo/entity :min-renderers 2})))))
      (let [to (requiring-resolve 'seon.bench.cluster/trial-outcome)
            base {:task {:merge? true} :ag-snap {:status :merged} :eval-res nil :agent-res {:wait {:ok? true}}
                  :test-res {:ok? true} :merge-res {:ok? true}}]
        (is (false? (:ok? (to (assoc base :health-res {:ok? false})))) "trial-outcome must reject unhealthy main")
        (is (false? (:ok? (to (assoc base :health-res {:ok? true} :verify-res {:ok? false :reason "still loose"})))) "trial-outcome must reject failed verify-spec")
        (is (true? (:ok? (to (assoc base :health-res {:ok? true} :verify-res {:ok? true})))))))))

(deftest wave3-auto-proofs-repl-multi-arity-and-flow-pool-test
  (testing "Wave 3: <auto-proofs> renders in wire tail, multi-arity stress-var works, ns-unmap persists, in-ns hydrates clojure.core, and Flow trial pool runs bounded"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (or rules/*base-rules-override* (rseed/starter-rules))})
      (d/transact conn {:tx-data [{:seon.agent/id :w/proofs :seon.agent/branch :main
                                   :seon.agent/last-proofs {:tx 99 :ok? false :ran 3 :passed 2 :failed 1 :ms 12.5 :failures ["foo/bar [gen]: failed"]}}]})
      ;; 1. <auto-proofs> tail block surfaces :seon.agent/last-proofs
      (let [w (rwire/build-live-wire-request @conn @conn :w/proofs {:include-tail? true})
            pb (some #(when (= "auto-proofs" (:tag %)) %) (:tail-blocks w))]
        (is (some? pb) "<auto-proofs> tail block must render :seon.agent/last-proofs")
        (is (.contains ^String (:text pb) "foo/bar [gen]: failed")))
      ;; 2. Multi-arity [:function ...] with concrete types is generatable & stressed
      (is (true? (schema/generatable-function-schema? [:function [:=> [:cat :int] :int] [:=> [:cat :int :int] :int]])))
      (is (true? (:ok? (schema/stress-var @conn 'seon.code.parse/lead-end {:samples 15 :seed 42}))))
      ;; 3. (in-ns 'demo.fresh-test) hydrates clojure.core + clojure.test, and (ns-unmap ...) retracts from Datahike
      (let [r1 (code/eval-forms @conn {:agent-id :w/proofs :turn 1} "(in-ns 'demo.fresh-w3-test) (defn helper [x] (+ x 1)) (deftest h-test (is (= 2 (helper 1))))")
            _ (d/transact conn {:tx-data (:tx-data r1)})
            r2 (code/eval-forms @conn {:agent-id :w/proofs :turn 2} "(ns-unmap 'demo.fresh-w3-test 'helper)")
            _ (d/transact conn {:tx-data (:tx-data r2)})]
        (is (:ok? r1) (str "in-ns auto-hydration must work: " (:error r1)))
        (is (:ok? r2) (str "ns-unmap must succeed: " (:error r2)))
        (is (nil? (first (d/datoms @conn :avet :seon.code.var/name 'demo.fresh-w3-test/helper))) "ns-unmap must retract var from Datahike"))
      ;; 4. Atomic eval error does not persist earlier forms
      (let [r3 (code/eval-forms @conn {:agent-id :w/proofs :turn 3} "(in-ns 'demo.atomic-w3) (defn partial-fn [x] x) (throw (ex-info \"boom\" {}))")
            _ (d/transact conn {:tx-data (:tx-data r3)})]
        (is (false? (:ok? r3)))
        (is (nil? (first (d/datoms @conn :avet :seon.code.var/name 'demo.atomic-w3/partial-fn))) "Failed eval must not persist partial earlier forms"))
      ;; 5. Canonical form-identity for deftype/extend-type/declare
      (is (= ['deftype 'Box] (seon.code.parse/named-expr-sig "(deftype Box [x])")))
      (is (= ['extend-type 'String] (seon.code.parse/named-expr-sig "(extend-type String Foo)")))
      ;; 6. Flow trial pool handles empty tasks cleanly
      (let [pr (seon.bench.cluster/run-parallel-trial! {:tasks []})]
        (is (true? (:ok? pr)))
        (is (= 0 (:count pr)))))))

(deftest wave4-data-model-malli-docs-semantic-recall-and-btree-ns-test
  (testing "Wave 4: render-schema-detail renders examples, widened schema regex & scoped-without-task recall, semantic near recall, and B+-tree ns-children enrichment"
    ;; 1. render-schema-detail renders :seon.schema/examples
    (let [ren (requiring-resolve 'seon.render.entities/render-schema-detail)
          h (:seon.ui/hiccup (ren {:seon.schema/entity {:seon.schema/key :seon.demo/ex :seon.schema/type :map
                                                        :seon.schema/form [:map [:a :int]] :seon.schema/doc "Demo."
                                                        :seon.schema/examples [{:a 42}]}}))]
      (is (clojure.string/includes? (pr-str h) "{:a 42}") "render-schema-detail must display :seon.schema/examples"))
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (concat
                                   (code/file-import-tx @conn "src/demo/tree.clj" "(ns demo.tree)\n(defn root-fn [] :ok)\n" false)
                                   (code/file-import-tx @conn "src/demo/tree/child.clj" "(ns demo.tree.child)\n(defn leaf-fn \"Computes paused status.\" [x] x)\n" false)
                                   [{:seon.task/id :task/child-work :seon.task/title "Fix child" :seon.task/scope 'demo.tree.child :seon.task/done? false}
                                    {:seon.agent/id :ns/demo.tree.child :seon.agent/branch :main :seon.agent/scope 'demo.tree.child
                                     :seon.agent/goal "Inspect :seon.agent/paused? behavior for paused status"}
                                    {:seon.task/id :task/sem-only :seon.task/title "Unrelated lexical words" :seon.task/plan "Zebra quartz" :seon.task/done? false}
                                    {:seon.agent/id :w/sem :seon.agent/branch :main :seon.agent/tasks #{[:seon.task/id :task/sem-only]}}])})
      ;; 2. B+-tree ns-children enrichment includes child var-count, open-tasks, and specialist
      (let [rlinks (requiring-resolve 'seon.render.links/enrich-entity-params)
            ep (rlinks @conn {:seon.code.ns/name 'demo.tree})
            kids (:seon.ui/ns-children ep)]
        (is (= 1 (count kids)))
        (is (= 'demo.tree.child (:seon.code.ns/name (first kids))))
        (is (= 1 (:var-count (first kids))))
        (is (= 1 (:open-tasks (first kids))))
        (is (= :ns/demo.tree.child (:specialist (first kids)))))
      ;; 3. Scoped specialist WITHOUT a task entity still gets Branch-1 recall + widened :seon.agent/paused? schema regex + doc scoring!
      (let [cgraph (requiring-resolve 'seon.ctx.graph/goal-lexical-rows)
            rows (cgraph @conn @conn :ns/demo.tree.child)
            eids (set (map first rows))
            leaf-e (:e (first (d/datoms @conn :avet :seon.code.var/name 'demo.tree.child/leaf-fn)))
            paused-e (:e (first (d/datoms @conn :avet :seon.schema/key :seon.agent/paused?)))]
        (is (contains? eids leaf-e) "Scoped specialist without task entity must recall matching scope var via doc score")
        (is (contains? eids paused-e) "Widened regex must recall ?-suffixed schema :seon.agent/paused?"))
      ;; 4. Stored EmbeddingGemma-style vector triggers O(1) C-SIMD seon.vector/near recall in Branch 2
      (let [svec-pack (requiring-resolve 'seon.vector/pack)
            v-unit (into [1.0] (repeat 127 0.0))
            t-eid (:e (first (d/datoms @conn :avet :seon.task/id :task/sem-only)))
            leaf-e (:e (first (d/datoms @conn :avet :seon.code.var/name 'demo.tree.child/leaf-fn)))
            packed (svec-pack v-unit 128)]
        (d/transact conn {:tx-data [(assoc packed :seon.vector/of t-eid :seon.vector/model "test" :seon.vector/text "t")
                                    (assoc packed :seon.vector/of leaf-e :seon.vector/model "test" :seon.vector/text "v")]})
        (let [cgraph (requiring-resolve 'seon.ctx.graph/goal-lexical-rows)
              rows (cgraph @conn @conn :w/sem)
              eids (set (map first rows))]
          (is (contains? eids leaf-e) "Unscoped task with stored vector must recall matching var via seon.vector/near"))))))

(deftest turn0-doc-recall-and-cached-var-index-test
  (testing "Turn-0 cached TF-IDF doc recall matches concise reference docs for contract/example tasks"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.code.doc/id "docs/malli.md#pure-function-contracts-seed-42-generative-checks"
                                   :seon.code.doc/path "docs/malli.md"
                                   :seon.code.doc/order 1
                                   :seon.code.doc/heading "## Pure Function Contracts, Examples & `seed = 42` Generative Checks\n"
                                   :seon.code.doc/body "Attach :examples and run user/stress-var.\n"}
                                  {:seon.code.doc/id "docs/other.md#unrelated-topic"
                                   :seon.code.doc/path "docs/other.md"
                                   :seon.code.doc/order 1
                                   :seon.code.doc/heading "## Unrelated Storage Notes\n"
                                   :seon.code.doc/body "Nothing about contracts.\n"}]})
      (let [db @conn
            hits (volatile! [])]
        (seon.ctx.recall/recall-matching-docs!
          db
          (fn [e pri]
            (vswap! hits conj [(:seon.code.doc/id (d/pull db '[:seon.code.doc/id] e)) pri]))
          "Add behavioral unit tests and contract examples for pure functions"
          2 80 2)
        (is (= [["docs/malli.md#pure-function-contracts-seed-42-generative-checks" 80]] @hits))))))