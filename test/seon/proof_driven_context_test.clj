(ns seon.proof-driven-context-test
  "End-to-end proofs for:
   1. Bidirectional ordered doc-atom citations (var docstrings, schema docs, task plans — zero doc levels).
   2. Malli relational :=> postconditions, concrete :examples, deterministic shrinking, and typed contract blame.
   3. Backward proof graph walk, eval-frontier bridge, blame-cut localization, chain-boundary witness, Known-Set back-refs.
   4. Recursive decoupled :rule.trigger/* cascade (Rule A -> Data B -> Rule B -> Data C -> mechanical refinement gate)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cite :as cite]
            [seon.code :as code]
            [seon.ctx.graph :as cgraph]
            [seon.error :as serr]
            [seon.render :as render]
            [seon.render.entities.graph :as rgraph]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.task.gate :as gate]
            [seon.task.refine :as refine]
            [seon.test-fixture :refer [with-test-store]]))

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
                                       :seon.task/proofs #{proof-e}}
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
                txt (render/hiccup->text (:seon.ui/hiccup (rgraph/render-chain-boundary {:seon.graph/chain-boundary cb})))]
            (is (= :seon.chain/in (:input-schema cb)))
            (is (= :seon.chain/out (:output-schema cb)))
            (is (str/includes? txt ":seon.chain/in -> :seon.chain/out"))
            (is (str/includes? txt "demo.chain/f1 (see Turn 1 / result/e1)"))
            (is (not-any? #(= "docs/spec.md#step-one" (:id %)) (:doc-atoms cb)) "Already-shown doc atom must be omitted"))
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
