(ns seon.proof-driven-context-test
  "End-to-end proofs for:
   1. Bidirectional ordered doc-atom citations (var docstrings, schema docs, task plans — zero doc levels).
   2. Malli relational :=> postconditions, concrete :examples, deterministic shrinking, and typed contract blame.
   3. Backward proof graph walk (scope focus + reverse :needs + harness exclusion), eval-frontier bridge, blame-cut localization, chain-boundary witness, Known-Set back-refs.
   4. Recursive decoupled :rule.trigger/* cascade (Rule A -> Data B -> Rule B -> Data C -> mechanical refinement gate).
   5. Live Refiner red-TDD acceptance, branch-local update-task!, fork-merge marking prereq done, and zero-conflict 3-way merge to :main."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cite :as cite]
            [seon.code :as code]
            [seon.ctx.graph :as cgraph]
            [seon.error :as serr]
            [seon.merge :as merge]
            [seon.render :as render]
            [seon.render.entities.graph :as rgraph]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.task.gate :as gate]
            [seon.task.refine :as refine]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.merge :as tmerge]
            [seon.transactor.spawn :as tspawn]
            [seon.schema.index :as six]))

(deftest ^{:cost/ticks 90000000} bidirectional-ordered-doc-atoms-and-task-refs-test
  (testing "ordered-citations and resolve-doc-refs preserve appearance order of exact :seon.code.doc/id atoms and wire var/schema doc-refs"
    (with-test-store [{:keys [conn]}]
      (let [doc-md "# Guide\n\n## Alpha Atom\nExplain `demo.doc/step-a`.\n\n## Beta Atom\nExplain `:seon.demo/rec`.\n"
            code-src (str "(ns demo.doc)\n"
                          "(defn step-a\n"
                          "  \"Reads `docs/guide.md#beta-atom` then [alpha](docs/guide.md#alpha-atom) and `:seon.demo/rec`.\"\n"
                          "  {:malli/schema [:=> {:examples [[[1] 2]]} [:cat :int] :int] :cost/ticks 10000}\n"
                          "  [x] (inc x))\n")]
        (d/transact conn {:tx-data (code/file-import-tx @conn "docs/guide.md" doc-md false)})
        (let [reg (schema/validate-schema-registration!
                   @conn {:key :seon.demo/rec
                          :form [:map {:gen/elements [{:n 1}]} [:n :int]]
                          :doc "See `docs/guide.md#alpha-atom` and `docs/guide.md#beta-atom`."})]
          (d/transact conn {:tx-data [(:entity reg)]}))
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/doc.clj" code-src false)})
        (let [db @conn
              ord (cite/ordered-citations "First `docs/guide.md#beta-atom` then [a](docs/guide.md#alpha-atom) and `demo.doc/step-a`.")
              res (cite/resolve-doc-refs db "First `docs/guide.md#beta-atom` then [a](docs/guide.md#alpha-atom) and `demo.doc/step-a`.")
              v-ent (d/pull db '[:seon.code.var/doc-refs :seon.code.var/doc-ref-order] [:seon.code.var/name 'demo.doc/step-a])
              s-ent (d/pull db '[:seon.schema/doc-refs] [:seon.schema/key :seon.demo/rec])]
          (is (= [[:doc "docs/guide.md#beta-atom"] [:doc "docs/guide.md#alpha-atom"] [:var 'demo.doc/step-a]] ord))
          (is (= [[:seon.code.doc/id "docs/guide.md#beta-atom"]
                  [:seon.code.doc/id "docs/guide.md#alpha-atom"]
                  [:seon.code.var/name 'demo.doc/step-a]]
                 (:ordered res)))
          (is (= 3 (count (:refs res))))
          (is (= [[:seon.code.doc/id "docs/guide.md#beta-atom"]
                  [:seon.code.doc/id "docs/guide.md#alpha-atom"]
                  [:seon.schema/key :seon.demo/rec]]
                 (:seon.code.var/doc-ref-order v-ent)))
          (is (= 3 (count (:seon.code.var/doc-refs v-ent))))
          (is (= 2 (count (:seon.schema/doc-refs s-ent)))))))))

(deftest ^{:cost/ticks 90000000} malli-relational-guard-shrinking-and-blame-test
  (testing "stress-var enforces relational :=> guard + deterministic counterexample shrinking, and arm-var! emits typed blame"
    (with-test-store [{:keys [conn]}]
      (let [ns-obj (create-ns 'demo.contract.probe)
            _ (binding [*ns* ns-obj] (refer-clojure))
            _bad-fn (intern ns-obj (with-meta 'buggy-inc
                                     {:malli/schema [:=> {:examples [[[1] 2]]}
                                                     [:cat [:int {:min 0 :max 100}]] :int
                                                     [:fn (fn [[[x] r]] (> r x))]]})
                            (fn [x] (if (>= x 10) x (inc x))))
            good-fn (intern ns-obj (with-meta 'strict-add
                                     {:malli/schema [:=> [:cat :int :int] :int]})
                            (fn [a b] (+ a b)))
            res (schema/stress-var @conn 'demo.contract.probe/buggy-inc {:samples 80 :seed 42})]
        (is (false? (:ok? res)))
        (is (true? (:relational-guard-failed? res)))
        (is (= :output-contract (:blame res)))
        (is (= [10] (:args res)) "Deterministic shrinker must minimize failing int down to exact boundary [10]")
        (schema/arm-var! @conn good-fn)
        (let [ex-d (try (@good-fn "not-int" 2) nil (catch Throwable t (ex-data t)))
              blame (serr/classify-eval-blame {:ex-data ex-d} "(strict-add \"not-int\" 2)")]
          (is (= :input-contract (:blame ex-d)))
          (is (= :input-contract (:blame blame)))
          (is (= 'demo.contract.probe/strict-add (:var blame)))
          (is (string? (:input-sample blame))))))))

(deftest ^{:cost/ticks 120000000} backward-proof-walk-blame-cut-and-chain-boundary-test
  (testing "proof-backward-rows, eval-frontier-rows, error-blame-rows, and build-chain-boundary assemble exact graph context"
    (with-test-store [{:keys [conn]}]
      (let [doc-md "# Spec\n\n## Input Shape\nAbout `:seon.chain/in`.\n\n## Step One\nAbout `demo.chain/f1`.\n\n## Step Two\nAbout `demo.chain/f2`.\n"
            code-src (str "(ns demo.chain)\n"
                          "(defn f1 \"See `docs/spec.md#step-one`.\" {:malli/schema [:=> {:examples [[[{:n 1}] {:m 2}]]} [:cat :seon.chain/in] :seon.chain/mid] :cost/ticks 10000} [{:keys [n]}] {:m (inc n)})\n"
                          "(defn f2 \"See `docs/spec.md#step-two`.\" {:malli/schema [:=> [:cat :seon.chain/mid] :seon.chain/out] :cost/ticks 10000} [{:keys [m]}] {:ok? (pos? m)})\n"
                          "(defn prim-step {:malli/schema [:=> {:examples [[[\"ab\"] 2]]} [:cat :string] :int] :cost/ticks 10000} [s] (count s))\n"
                          "(defn proof-chain-test [] (f2 (f1 {:n 1})))\n")]
        (d/transact conn {:tx-data (code/file-import-tx @conn "docs/spec.md" doc-md false)})
        (doseq [[k f doc] [[:seon.chain/in [:map {:gen/elements [{:n 1}]} [:n :int]] "Input doc `docs/spec.md#input-shape`."]
                           [:seon.chain/mid [:map {:gen/elements [{:m 2}]} [:m :int]] "Mid state."]
                           [:seon.chain/out [:map {:gen/elements [{:ok? true}]} [:ok? :boolean]] "Final output."]]]
          (let [r (schema/validate-schema-registration! @conn {:key k :form f :doc doc})]
            (d/transact conn {:tx-data [(:entity r)]})))
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/chain.clj" code-src false)})
        (let [db1 @conn
              proof-e (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.chain/proof-chain-test)))
              f1-e (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.chain/f1)))
              f2-e (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.chain/f2)))
              in-e (:e (first (d/datoms db1 :avet :seon.schema/key :seon.chain/in)))
              mid-e (:e (first (d/datoms db1 :avet :seon.schema/key :seon.chain/mid)))
              out-e (:e (first (d/datoms db1 :avet :seon.schema/key :seon.chain/out)))
              doc-in-e (:e (first (d/datoms db1 :avet :seon.code.doc/id "docs/spec.md#input-shape")))
              doc-f1-e (:e (first (d/datoms db1 :avet :seon.code.doc/id "docs/spec.md#step-one")))]
          (d/transact conn {:tx-data [{:seon.task/id :task/demo-chain :seon.task/title "Fix chain" :seon.task/done? false
                                       :seon.task/scope 'demo.chain :seon.task/proofs #{proof-e}}
                                      {:seon.agent/id :agent/chain-worker :seon.agent/branch :branch/chain
                                       :seon.agent/tasks #{[:seon.task/id :task/demo-chain]}}]})
          (let [rows (cgraph/proof-backward-rows @conn @conn :agent/chain-worker)
                eids (set (map first rows))]
            (is (contains? eids proof-e))
            (is (contains? eids f1-e))
            (is (contains? eids f2-e))
            (is (contains? eids in-e))
            (is (contains? eids mid-e))
            (is (contains? eids out-e))
            (is (contains? eids doc-in-e))
            (is (contains? eids doc-f1-e)))
          (let [cb (cgraph/build-chain-boundary @conn '[demo.chain/f1 demo.chain/f2]
                                                {f1-e {:turn 1 :handle 'result/e1} doc-f1-e {:turn 1}})
                txt (render/hiccup->text (:seon.ui/hiccup (rgraph/render-chain-boundary {:seon.graph/chain-boundary cb})))
                cb-prim (cgraph/build-chain-boundary @conn '[demo.chain/prim-step])
                txt-prim (render/hiccup->text (:seon.ui/hiccup (rgraph/render-chain-boundary {:seon.graph/chain-boundary cb-prim})))]
            (is (= :seon.chain/in (:input-schema cb)))
            (is (= :seon.chain/out (:output-schema cb)))
            (is (str/includes? txt ":seon.chain/in -> :seon.chain/out"))
            (is (str/includes? txt "demo.chain/f1 (see Turn 1 / result/e1)"))
            (is (not-any? #(= "docs/spec.md#step-one" (:id %)) (:doc-atoms cb)) "Already-shown doc atom must be omitted")
            (is (= :string (:input-type cb-prim)))
            (is (= :int (:output-type cb-prim)))
            (is (str/includes? txt-prim ":string -> :int")))
          (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/chain-worker
                                       :seon.agent.eval/turn 1 :seon.agent.eval/code "(demo.chain/f1 {:n 1})"
                                       :seon.agent.eval/called-vars #{f1-e}
                                       :seon.agent.eval/result-schemas #{mid-e}}]})
          (let [frows (cgraph/eval-frontier-rows @conn @conn :agent/chain-worker)
                feids (set (map first frows))]
            (is (contains? feids f2-e) "Bridge consumer f2 accepting :seon.chain/mid must surface")
            (is (contains? feids doc-f1-e)))
          (let [err-in (assoc (serr/attach-blame-refs
                               @conn
                               (serr/error-tx {:kind :contract/input :severity :error :source :eval
                                               :agent :agent/chain-worker :message "Bad input"
                                               :blame :input-contract :var 'demo.chain/f1
                                               :input-sample "[{:n 1}]" :call-chain ['demo.chain/f1]}))
                              :seon.error/schema-ref in-e)]
            (d/transact conn {:tx-data [err-in]})
            (let [brows (cgraph/error-blame-rows @conn @conn :agent/chain-worker)
                  beids (set (map first brows))]
              (is (contains? beids in-e) "Input blame must surface violated input schema")
              (is (contains? beids doc-in-e) "Input blame must surface input schema doc atom")
              (is (not (contains? beids f1-e)) "Input blame must NOT dump callee var body"))))))))

(deftest ^{:cost/ticks 120000000} recursive-trigger-cascade-and-refinement-gate-test
  (testing "Decoupled Rule A -> Data B -> Rule B -> Data C cascade and mechanical refinement gate + seed=42 stress"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (rseed/starter-rules)})
      (d/transact conn {:tx-data [{:seon.rule/id :rule.trigger/unproven-task :seon.rule/category :trigger
                                   :seon.rule/stratum 26 :seon.rule/enabled? true :seon.rule/target :any
                                   :seon.rule/query '[:find ?ag ?tid :in $main :where [(seon.task.refine/unproven-task-rows $main) [[?ag ?tid]]]]
                                   :seon.rule/assert '[[?tid :trigger/refine-task {:agent ?ag}]]}
                                  {:seon.rule/id :rule.trigger/spawn-unassigned-prereq :seon.rule/category :trigger
                                   :seon.rule/stratum 27 :seon.rule/enabled? true :seon.rule/target :any
                                   :seon.rule/query '[:find ?ag ?ptid ?rtid :in $main :where [(seon.task.refine/unassigned-prereq-rows $main) [[?ag ?ptid ?rtid]]]]
                                   :seon.rule/assert '[[?rtid :trigger/spawn-prereq {:parent-agent ?ag :parent-task ?ptid}]]}
                                  {:seon.task/id :template/refine-task-proofs :seon.task/title "Refine proofs"
                                   :seon.task/template? true :seon.task/done? false :seon.task/plan "Search widely and write proofs."}
                                  {:seon.config/key :seon.config/root :seon.config/auto-refine-tasks? true}
                                  {:seon.task/id :task/rough-goal :seon.task/title "Add smart caching"
                                   :seon.task/plan "Make lookups faster." :seon.task/done? false}
                                  {:seon.agent/id :worker/rough :seon.agent/branch :branch/rough
                                   :seon.agent/tasks #{[:seon.task/id :task/rough-goal]}}]})
      (let [ops1 (refine/compute-trigger-ops @conn)
            ref-id (refine/refinement-task-id :task/rough-goal)]
        (is (= 1 (count ops1)))
        (is (= [:trigger/refine-task :task/rough-goal] (:reason (first ops1))))
        (d/transact conn {:tx-data (:tx-data (first ops1))})
        (let [pgate (gate/evaluate-agent-gate @conn @conn :worker/rough)]
          (is (false? (:ok? pgate)))
          (is (some #(= :task-needs-open (:reason %)) (:blockers pgate))))
        (let [ops2 (refine/compute-trigger-ops @conn)]
          (is (not-any? #(= :trigger/refine-task (first (:reason %))) ops2) "Rule A must be idempotent once Data B commits")
          (is (some #(= :spawn-agent (:op %)) ops2) "Rule B must spawn Refiner on Data B")
          (let [sp (first (filter #(= :spawn-agent (:op %)) ops2))]
            (is (= [:seon.agent/id :worker/rough] (:merge-target sp)))
            (is (= [ref-id] (:tasks sp)))))
        (d/transact conn {:tx-data [{:seon.agent/id :refine/worker-1 :seon.agent/branch :branch/refine-1
                                     :seon.agent/tasks #{[:seon.task/id ref-id]}}]})
        (let [g0 (gate/evaluate-agent-gate @conn @conn :refine/worker-1)]
          (is (false? (:ok? g0)))
          (is (some #(= :refinement-missing-proofs (:reason %)) (:blockers g0)))
          (is (some #(= :refinement-missing-refs (:reason %)) (:blockers g0))))
        (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/cache_test.clj"
                                                        "(ns demo.cache-test (:require [clojure.test :refer [deftest is]]))\n(deftest cache-proof-test (is (= 1 2)))\n" false)})
        (let [pe (:e (first (d/datoms @conn :avet :seon.code.var/name 'demo.cache-test/cache-proof-test)))]
          (d/transact conn {:tx-data [{:seon.task/id :task/rough-goal
                                       :seon.task/proofs #{pe}
                                       :seon.task/refs #{pe}
                                       :seon.task/plan "Make `demo.cache-test/cache-proof-test` pass."}]})
          (let [g1 (gate/evaluate-agent-gate @conn @conn :refine/worker-1)]
            (is (true? (:ok? g1)) (str "Refinement gate must pass once proof + refs exist: " (pr-str g1))))))
      (let [db @conn]
        (doseq [sym '[seon.cite/ordered-citations
                      seon.error/classify-eval-blame
                      seon.task.refine/refinement-task-id
                      seon.render.entities.graph/format-known-backref
                      seon.render.entities.graph/render-chain-boundary
                      seon.render.entities.graph/render-blame-cut]]
          (let [r (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (true? (:ok? r)) (str sym " failed seed=42 check: " (pr-str r)))))))))

(deftest ^{:cost/ticks 180000000} refiner-red-tdd-fork-merge-and-clean-main-merge-test
  (testing "Full t0->t7 lifecycle: spawn refiner -> expected-red-proof-vars excludes :main tests -> fork-merge marks prereq done & unpauses worker -> zero-conflict 3-way merge to :main"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (rseed/starter-rules)})
      ;; Existing :main code + test (must NEVER become expected-red!)
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/slug.clj"
                                                      "(ns demo.slug)\n(defn clean-slug \"Normalizes text.\" {:malli/schema [:=> [:cat :string] :string] :cost/ticks 10000} [s] s)\n" false)})
      (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/slug_test.clj"
                                                      "(ns demo.slug-test (:require [clojure.test :refer [deftest is]] [demo.slug :as slug]))\n(deftest existing-slug-test (is (= \"a\" (slug/clean-slug \"a\"))))\n" false)})
      (d/transact conn {:tx-data [{:seon.rule/id :rule.trigger/unproven-task :seon.rule/category :trigger
                                   :seon.rule/stratum 26 :seon.rule/enabled? true :seon.rule/target :any
                                   :seon.rule/query '[:find ?ag ?tid :in $main :where [(seon.task.refine/unproven-task-rows $main) [[?ag ?tid]]]]
                                   :seon.rule/assert '[[?tid :trigger/refine-task {:agent ?ag}]]}
                                  {:seon.rule/id :rule.trigger/spawn-unassigned-prereq :seon.rule/category :trigger
                                   :seon.rule/stratum 27 :seon.rule/enabled? true :seon.rule/target :any
                                   :seon.rule/query '[:find ?ag ?ptid ?rtid :in $main :where [(seon.task.refine/unassigned-prereq-rows $main) [[?ag ?ptid ?rtid]]]]
                                   :seon.rule/assert '[[?rtid :trigger/spawn-prereq {:parent-agent ?ag :parent-task ?ptid}]]}
                                  {:seon.task/id :template/refine-task-proofs :seon.task/title "Refine proofs"
                                   :seon.task/template? true :seon.task/done? false :seon.task/plan "Write proofs referencing `demo.slug/clean-slug`."}
                                  {:seon.config/key :seon.config/root :seon.config/auto-refine-tasks? true}]})
      ;; t0: Create rough task + spawn worker on :branch/worker-slug
      (let [st0 {:conn conn :cfg cfg :msg-seq 0 :writes 0 :commit-git? false}
            [st1 _] (tspawn/handle-create-task! st0 {:id :demo/slug-task :title "Lowercase slugs"
                                                     :plan "Make slug normalization lowercase and dash-separated."
                                                     :scope 'demo.slug :spawn-agent? true :agent-id :worker/slug}
                                                (d/commit-id @conn))
            ;; t1: Rule A -> stamp :refine/demo-slug-task on :main
            ops1 (refine/compute-trigger-ops @conn)
            _ (is (= 1 (count ops1)))
            _ (d/transact conn {:tx-data (:tx-data (first ops1))})
            ;; t2: Rule B -> pause worker + spawn Refiner forked from :worker/slug
            ops2 (refine/compute-trigger-ops @conn)
            pause-op (first (filter #(= :transact (:op %)) ops2))
            spawn-op (first (filter #(= :spawn-agent (:op %)) ops2))
            _ (d/transact conn {:tx-data (:tx-data pause-op)})
            [st2 _] (tspawn/handle-spawn-agent! st1 spawn-op (d/commit-id @conn))
            ref-aid (:id spawn-op)
            ref-branch (:seon.agent/branch (d/pull @conn '[:seon.agent/branch] [:seon.agent/id ref-aid]))
            ref-tid (refine/refinement-task-id :demo/slug-task)]
        ;; Refiner branch has normalized parent-task ref + reverse :needs walk pulls parent task!
        (let [rconn (store/connect cfg ref-branch)]
          (try
            (let [rows0 (cgraph/proof-backward-rows @conn @rconn ref-aid)
                  parent-eid (:e (first (d/datoms @rconn :avet :seon.task/id :demo/slug-task)))]
              (is (some #(= parent-eid (first %)) rows0) "Refiner Turn-0 backward walk must surface parent task"))
            ;; t3: Refiner authors RED deftest on its branch + updates parent task via branch-update-task-tx
            (d/transact rconn {:tx-data (code/file-import-tx @rconn "test/demo/slug_test.clj"
                                                             "(ns demo.slug-test (:require [clojure.test :refer [deftest is]] [demo.slug :as slug]))\n(deftest existing-slug-test (is (= \"a\" (slug/clean-slug \"a\"))))\n(deftest new-red-slug-proof-test (is (= \"hello-world\" (slug/clean-slug \"Hello World\"))))\n" false)})
            (do (is (true? (:ok? (gate/evaluate-agent-gate @conn @rconn ref-aid))) "Refinement gate must auto-accept newly authored compiling branch deftest even before manual update-task!")
            (let [utx (refine/branch-update-task-tx @rconn :demo/slug-task
                                                    {:plan "Update `demo.slug/clean-slug` for lowercase dash slugs."})]
              (d/transact rconn {:tx-data utx})))
            ;; t4: expected-red-proof-vars includes ONLY newly authored proof, NEVER pre-existing :main test!
            (let [exp-red (refine/expected-red-proof-vars @conn @rconn ref-aid)
                auto-ops (refine/auto-anchor-parent-proofs-tx @conn @rconn ref-aid)]
            (is (= #{'demo.slug-test/new-red-slug-proof-test} exp-red))
            (is (not (contains? exp-red 'demo.slug-test/existing-slug-test)))
            (is (= 1 (count auto-ops)))
            (is (= :demo/slug-task (:seon.task/id (first auto-ops))))
            (is (= #{'demo.slug-test/new-red-slug-proof-test} (:seon.task/tests (first auto-ops)))))
            (is (true? (:ok? (gate/evaluate-agent-gate @conn @rconn ref-aid))))
            (finally (d/release rconn))))
        ;; t5: Fork-merge Refiner -> worker branch marks :refine/* done on BOTH branches & unpauses :worker/slug!
        (let [worker-branch (:seon.agent/branch (d/pull @conn '[:seon.agent/branch] [:seon.agent/id :worker/slug]))
              [_ _] (tmerge/handle-merge-op! st2 {:op :merge :branch ref-branch :agent-id ref-aid} (d/commit-id @conn))]
          (is (true? (:seon.task/done? (d/pull @conn '[:seon.task/done?] [:seon.task/id ref-tid])))
              "Fork-merge must mark prerequisite refinement task :done? true on :main")
          (is (false? (boolean (:seon.agent/paused? (d/pull @conn '[:seon.agent/paused?] [:seon.agent/id :worker/slug]))))
              "Fork-merge must unpause parent worker")
          (is (empty? (refine/compute-trigger-ops @conn))
              "Recursive triggers must settle to empty once prereq is done (no infinite respawn loop)")
          ;; t6: Worker wakes with backward-walked proof context & implements green fix
          (let [wconn (store/connect cfg worker-branch)]
            (try
              (let [wrows (cgraph/proof-backward-rows @conn @wconn :worker/slug)
                    w-eids (set (map first wrows))
                    slug-e (:e (first (d/datoms @wconn :avet :seon.code.var/name 'demo.slug/clean-slug)))
                    proof-e (:e (first (d/datoms @wconn :avet :seon.code.var/name 'demo.slug-test/new-red-slug-proof-test)))]
                (is (contains? w-eids proof-e) "Worker Turn-0 walk must include newly merged proof deftest")
                (is (contains? w-eids slug-e) "Worker Turn-0 walk must include target fn called by proof"))
              (d/transact wconn {:tx-data (code/file-import-tx @wconn "src/demo/slug.clj"
                                                               "(ns demo.slug (:require [clojure.string :as str]))\n(defn clean-slug \"Normalizes text.\" {:malli/schema [:=> [:cat :string] :string] :cost/ticks 10000} [s] (-> s str/lower-case (str/replace #\"\\s+\" \"-\")))\n" false)})
              (finally (d/release wconn))))
          ;; t7: 3-way merge worker branch -> :main succeeds with ZERO conflicts!
          (let [plan (merge/three-way conn cfg worker-branch {:skip-health? true})]
            (is (true? (:ok? plan)) (str "Worker -> :main 3-way merge must have 0 conflicts: " (pr-str (:conflicts plan))))))))))

(deftest ^{:cost/ticks 90000000} live-refiner-ergonomics-and-zero-race-deferral-test
  (testing "lookup-schemas, defer-triggered-agents, aborted-refiner-cleanup-tx, and Turn-0 unproven scope walk"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/erg.clj"
                                                      "(ns demo.erg)\n(defn f-pub {:malli/schema [:=> [:cat :int] :int] :cost/ticks 1000} [x] (inc x))\n" false)})
      (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/erg_test.clj"
                                                      "(ns demo.erg-test (:require [clojure.test :refer [deftest is]]))\n(deftest erg-smoke-test (is true))\n" false)})
      (let [reg (schema/validate-schema-registration! @conn {:key :seon.erg/sample :form [:map [:x :int]] :doc "Sample doc-refs test schema."})]
        (d/transact conn {:tx-data [(:entity reg)
                                    {:seon.task/id :template/refine-task-proofs :seon.task/title "Refine" :seon.task/template? true :seon.task/done? false}
                                    {:seon.task/id :refine/demo-erg :seon.task/title "Refine erg" :seon.task/scope 'demo.erg :seon.task/template :template/refine-task-proofs :seon.task/done? false}
                                    {:seon.agent/id :refine/erg-1 :seon.agent/branch :branch/erg-1 :seon.agent/tasks #{[:seon.task/id :refine/demo-erg]}}]}))
      (let [db @conn]
        (is (contains? (set (six/lookup-schemas db)) :seon.erg/sample))
        (is (= :seon.erg/sample (:seon.schema/key (six/lookup-schemas db :seon.erg/sample))))
        (is (contains? (six/lookup-schemas db "doc-refs") :seon.erg/sample))
        (is (= #{:w/b} (refine/defer-triggered-agents #{:w/a :w/b} [{:defer-agent :w/a}])))
        (let [cleanup (refine/aborted-refiner-cleanup-tx db {:seon.agent/tasks [{:seon.task/id :refine/demo-erg}]} "cutoff")]
          (is (= 1 (count cleanup)))
          (is (true? (:seon.task/done? (first cleanup)))))
        (let [rows (cgraph/proof-backward-rows db db :refine/erg-1)
              eids (set (map first rows))
              ns-e (:e (first (d/datoms db :avet :seon.code.ns/name 'demo.erg)))
              tns-e (:e (first (d/datoms db :avet :seon.code.ns/name 'demo.erg-test)))
              v-e (:e (first (d/datoms db :avet :seon.code.var/name 'demo.erg/f-pub)))]
          (is (contains? eids ns-e) "Unproven/refiner Turn-0 walk must surface scope ns")
          (is (contains? eids tns-e) "Unproven/refiner Turn-0 walk must surface matching -test ns")
          (is (contains? eids v-e) "Unproven/refiner Turn-0 walk must surface scope public vars"))))))

(deftest ^{:cost/ticks 180000000} r1-root-causes-zero-conflict-kv-anchor-and-edit-ergonomics-test
  (testing "Refiner editing :refine/* plan causes zero 3-way conflict on worker merge, pauses Refiner, anchors branch-reset-tx on :fork-review, and keeps prefix-hash stable across branch task edits"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (rseed/starter-rules)})
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/kv.clj"
                                                      "(ns demo.kv)\n(defn base-fn {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] x)\n" false)})
      (d/transact conn {:tx-data (code/file-import-tx @conn "test/demo/kv_test.clj"
                                                      "(ns demo.kv-test (:require [clojure.test :refer [deftest is]] [demo.kv :as kv]))\n(deftest base-kv-test (is (= 1 (kv/base-fn 1))))\n" false)})
      (d/transact conn {:tx-data [{:seon.rule/id :rule.trigger/unproven-task :seon.rule/category :trigger
                                   :seon.rule/stratum 26 :seon.rule/enabled? true :seon.rule/target :any
                                   :seon.rule/query '[:find ?ag ?tid :in $main :where [(seon.task.refine/unproven-task-rows $main) [[?ag ?tid]]]]
                                   :seon.rule/assert '[[?tid :trigger/refine-task {:agent ?ag}]]}
                                  {:seon.rule/id :rule.trigger/spawn-unassigned-prereq :seon.rule/category :trigger
                                   :seon.rule/stratum 27 :seon.rule/enabled? true :seon.rule/target :any
                                   :seon.rule/query '[:find ?ag ?ptid ?rtid :in $main :where [(seon.task.refine/unassigned-prereq-rows $main) [[?ag ?ptid ?rtid]]]]
                                   :seon.rule/assert '[[?rtid :trigger/spawn-prereq {:parent-agent ?ag :parent-task ?ptid}]]}
                                  {:seon.task/id :template/refine-task-proofs :seon.task/title "Refine proofs"
                                   :seon.task/template? true :seon.task/done? false :seon.task/plan "Write failing proof."}
                                  {:seon.config/key :seon.config/root :seon.config/auto-refine-tasks? true}]})
      (let [st0 {:conn conn :cfg cfg :msg-seq 0 :writes 0 :commit-git? false}
            [st1 _] (tspawn/handle-create-task! st0 {:id :demo/kv-task :title "Add double fn"
                                                     :plan "Add double-fn in demo.kv."
                                                     :scope 'demo.kv :spawn-agent? true :agent-id :worker/kv}
                                                (d/commit-id @conn))
            ops1 (refine/compute-trigger-ops @conn)
            _ (d/transact conn {:tx-data (:tx-data (first ops1))})
            ops2 (refine/compute-trigger-ops @conn)
            _ (d/transact conn {:tx-data (:tx-data (first (filter #(= :transact (:op %)) ops2)))})
            spawn-op (first (filter #(= :spawn-agent (:op %)) ops2))
            [st2 _] (tspawn/handle-spawn-agent! st1 spawn-op (d/commit-id @conn))
            ref-aid (:id spawn-op)
            ref-branch (:seon.agent/branch (d/pull @conn '[:seon.agent/branch] [:seon.agent/id ref-aid]))
            ref-tid (refine/refinement-task-id :demo/kv-task)
            worker-branch (:seon.agent/branch (d/pull @conn '[:seon.agent/branch] [:seon.agent/id :worker/kv]))]
        (let [rconn (store/connect cfg ref-branch)]
          (try
            ;; Measure Refiner Turn-1 prefix-hash BEFORE updating task on branch
            (let [rwire (requiring-resolve 'seon.render.wire/build-live-wire-request)
                  w1 (rwire @conn @rconn ref-aid {:conn conn})
                  p1 (:prefix-hash w1)]
              (d/transact rconn {:tx-data (code/file-import-tx @rconn "src/demo/kv.clj"
                                                               "(ns demo.kv)\n(defn base-fn {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] x)\n(defn double-fn {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] 0)\n" false)})
              (d/transact rconn {:tx-data (code/file-import-tx @rconn "test/demo/kv_test.clj"
                                                               "(ns demo.kv-test (:require [clojure.test :refer [deftest is]] [demo.kv :as kv]))\n(deftest base-kv-test (is (= 1 (kv/base-fn 1))))\n(deftest double-fn-proof-test (is (= 6 (kv/double-fn 3))))\n" false)})
              ;; Deliberately update :refine/* plan directly (reproduces H2-Treatment failure!) + via apply-update-task!
              (d/transact rconn {:tx-data [{:seon.task/id ref-tid :seon.task/plan "DIRECT MUTATION ON REFINE TASK"}]})
              (let [res (refine/apply-update-task! {:conn rconn :branch ref-branch :agent-id ref-aid}
                        {:id ref-tid :plan "API: demo.kv/double-fn"}
                        (fn [_ _] nil) (fn [_] nil))]
                (is (= :demo/kv-task (:task res)) "apply-update-task! must reroute Refiner :plan update onto parent task"))
              (let [w2 (rwire @conn @rconn ref-aid {:conn conn})]
                (is (= p1 (:prefix-hash w2)) "Updating task on branch must NOT bust frozen KV prefix-hash")))
            (finally (d/release rconn))))
        ;; Fork-merge Refiner -> worker branch
        (tmerge/handle-merge-op! st2 {:op :merge :branch ref-branch :agent-id ref-aid} (d/commit-id @conn))
        (is (true? (:seon.agent/paused? (d/pull @conn '[:seon.agent/paused?] [:seon.agent/id ref-aid])))
            "Fork-merged Refiner must be paused so it never executes a post-merge zombie turn")
        (let [wconn (store/connect cfg worker-branch)]
          (try
            (let [rstatus (requiring-resolve 'seon.render.status/branch-reset-tx)
                  rwire (requiring-resolve 'seon.render.wire/build-live-wire-request)
                  rtx (rstatus @wconn :worker/kv)
                  ww (rwire @conn @wconn :worker/kv {:conn conn})]
              (is (pos? rtx) "branch-reset-tx must recognize :seon.tx/merged :fork-review")
              (let [proof-e (:e (first (d/datoms @wconn :avet :seon.code.var/name 'demo.kv-test/double-fn-proof-test)))]
              (is (some #(= proof-e (:entity %)) (:tiles ww)) "Post-refinement Worker Turn 0 must anchor merged proof inside frozen prefix :tiles")
              (is (< (long (or (get-in ww [:fit-trace :delta-tokens]) 0)) 150) "Post-refinement Worker Turn 0 must not leak proof context into uncached delta")))
            ;; Worker implements double-fn and runs 3-way merge to :main -> MUST have 0 conflicts!
            (d/transact wconn {:tx-data (code/file-import-tx @wconn "src/demo/kv.clj"
                                                             "(ns demo.kv)\n(defn base-fn {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] x)\n(defn double-fn {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] (* 2 x))\n" false)})
            (let [plan (merge/three-way conn cfg worker-branch {:exclude-agent :worker/kv})]
              (is (true? (:ok? plan)) (str "Worker 3-way merge to :main must have zero :refine/* conflict: " (pr-str (:conflicts plan)))))
            (finally (d/release wconn))))
        (is (= [{:seon.code.var/name 'demo/f}]
               (refine/strip-task-ops [{:seon.task/id :refine/x :seon.task/plan "p"}
                                       [:db/add [:seon.task/id :refine/x] :seon.task/done? true]
                                       {:seon.code.var/name 'demo/f}]
                                      [:refine/x])))))))