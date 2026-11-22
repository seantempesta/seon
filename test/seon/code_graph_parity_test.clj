(ns seon.code-graph-parity-test
  "Stage 1 regression proofs: canonical code/schema/rule/doc/eval/test ref graph,
   set-meta! & catches retraction parity, and cold-boot bootstrap-tx === live-eval parity."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cite :as cite]
            [seon.code :as code]
            [seon.code.analyze :as analyze]
            [seon.code.edit :as edit]
            [seon.code.files :as files]
            [seon.fork.calls :as fcalls]
            [seon.git :as git]
            [seon.store :as store]
            [seon.test :as stest]
            [seon.test-fixture :refer [with-test-store]]))

(deftest canonical-graph-reverse-ref-pull-queries-test
  (testing "1-line d/pull reverse-ref queries across schemas, vars, namespaces, rules, docs, evals, and tests"
    (with-test-store [{:keys [conn]}]
      (let [core-src "(ns demo.core)\n(defn helper {:malli/schema [:=> [:cat :seon.task/id] :seon.store/branch]} [id] :main)"
            svc-src (str "(ns demo.svc (:require [demo.core :as dc]))\n"
                         "(defn handle\n"
                         "  {:malli/schema [:function\n"
                         "                  [:=> {:examples [[{:seon.task/id :seon.task/entity} :ok]]}\n"
                         "                   [:cat [:map [:seon.task/id :seon.task/id] [:seon.task/plan {:optional true} :string]]]\n"
                         "                   :seon.health/report]\n"
                         "                  [:=> [:cat :seon.task/id :seon.store/branch] :seon.health/report]]}\n"
                         "  ([m] (dc/helper (:seon.task/id m)))\n"
                         "  ([id _br] (dc/helper id)))")
            _ (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/core.clj" core-src false)})
            _ (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/svc.clj" svc-src false)})
            doc-src "## Overview\nCalls `demo.svc/handle` using `:seon.task/id`.\n"
            _ (d/transact conn {:tx-data (code/file-import-tx @conn "docs/demo.md" doc-src false)})
            rule-map {:seon.rule/id :rule.demo/view
                      :seon.rule/category :present
                      :seon.rule/stratum 40
                      :seon.rule/enabled? true
                      :seon.rule/target :any
                      :seon.rule/when '[[?e :ctx/kind :seon.task/entity]]
                      :seon.rule/assert '[[?e :view/fn demo.svc/handle]]}
            _ (d/transact conn {:tx-data (files/enrich-rule-tx @conn rule-map)})
            verdict (first (stest/attach-var-refs @conn
                                                  [{:seon.test/digest "dg-parity-0001"
                                                    :seon.test/var 'demo.svc/handle
                                                    :seon.test/kind :generative
                                                    :seon.test/pass? true
                                                    :seon.test/ms 1.2
                                                    :seon.test/report "PASS"}]))
            _ (d/transact conn {:tx-data [verdict]})
            ev-res (code/eval-forms @conn {:agent-id :agent/parity :turn 1}
                                    "[{:seon.task/id :t/1 :seon.task/title \"T\"} {:seon.rule/id :r/1 :seon.rule/stratum 10 :seon.rule/enabled? true :seon.rule/when [] :seon.rule/assert []}]")
            _ (d/transact conn {:tx-data (:tx-data ev-res)})
            db @conn]
        ;; Schema AST edges + reverse refs
        (let [p (d/pull db '[:seon.schema/type {:seon.schema/keys [:seon.schema/key]}
                             {:seon.schema/optional-keys [:seon.schema/key]}]
                        [:seon.schema/key :seon.code.var/entity])
              rev-targets (map :seon.schema/key (:seon.schema/_ref-targets
                                                 (d/pull db '[{:seon.schema/_ref-targets [:seon.schema/key]}]
                                                         [:seon.schema/key :seon.code.var/entity])))]
          (is (= :map (:seon.schema/type p)))
          (is (contains? (set (map :seon.schema/key (:seon.schema/keys p))) :seon.code.var/name))
          (is (contains? (set (map :seon.schema/key (:seon.schema/optional-keys p))) :seon.code.var/input))
          (is (contains? (set rev-targets) :seon.code.var/calls))
          (is (contains? (set rev-targets) :seon.rule/fn))
          (is (contains? (set rev-targets) :seon.test/var-ref)))
        ;; Var Malli AST :_input / :_optional-input / :_output (zero :examples leak!)
        (let [in-users (set (map :seon.code.var/name (:seon.code.var/_input (d/pull db '[{:seon.code.var/_input [:seon.code.var/name]}] [:seon.schema/key :seon.task/id]))))
              opt-users (set (map :seon.code.var/name (:seon.code.var/_optional-input (d/pull db '[{:seon.code.var/_optional-input [:seon.code.var/name]}] [:seon.schema/key :seon.task/plan]))))
              out-users (set (map :seon.code.var/name (:seon.code.var/_output (d/pull db '[{:seon.code.var/_output [:seon.code.var/name]}] [:seon.schema/key :seon.health/report]))))
              leaked-in (set (map :seon.code.var/name (:seon.code.var/_input (d/pull db '[{:seon.code.var/_input [:seon.code.var/name]}] [:seon.schema/key :seon.task/entity]))))]
          (is (contains? in-users 'demo.svc/handle))
          (is (contains? in-users 'demo.core/helper))
          (is (contains? opt-users 'demo.svc/handle))
          (is (contains? out-users 'demo.svc/handle))
          (is (not (contains? leaked-in 'demo.svc/handle)) ":examples must never leak into :seon.code.var/input"))
        ;; Call graph :_calls, Namespace :_requires-ns, Rule :_fn / :_schema, Doc :_references, Test :_var-ref, Eval :_result-schemas
        (is (contains? (set (map :seon.code.var/name (:seon.code.var/_calls (d/pull db '[{:seon.code.var/_calls [:seon.code.var/name]}] [:seon.code.var/name 'demo.core/helper]))))
                       'demo.svc/handle))
        (is (contains? (set (map :seon.code.ns/name (:seon.code.ns/_requires-ns (d/pull db '[{:seon.code.ns/_requires-ns [:seon.code.ns/name]}] [:seon.code.ns/name 'demo.core]))))
                       'demo.svc))
        (is (contains? (set (map :seon.rule/id (:seon.rule/_fn (d/pull db '[{:seon.rule/_fn [:seon.rule/id]}] [:seon.code.var/name 'demo.svc/handle]))))
                       :rule.demo/view))
        (is (contains? (set (map :seon.rule/id (:seon.rule/_schema (d/pull db '[{:seon.rule/_schema [:seon.rule/id]}] [:seon.schema/key :seon.task/entity]))))
                       :rule.demo/view))
        (is (contains? (set (map :seon.code.doc/id (:seon.code.doc/_references (d/pull db '[{:seon.code.doc/_references [:seon.code.doc/id]}] [:seon.code.var/name 'demo.svc/handle]))))
                       "docs/demo.md#overview"))
        (is (contains? (set (map :seon.test/digest (:seon.test/_var-ref (d/pull db '[{:seon.test/_var-ref [:seon.test/digest]}] [:seon.code.var/name 'demo.svc/handle]))))
                       "dg-parity-0001"))
        (is (contains? (set (map :seon.agent.eval/handle (:seon.agent.eval/_result-schemas (d/pull db '[{:seon.agent.eval/_result-schemas [:seon.agent.eval/handle]}] [:seon.schema/key :seon.task/entity]))))
                       (:handle ev-res)))
        (is (contains? (set (map :seon.agent.eval/handle (:seon.agent.eval/_result-schemas (d/pull db '[{:seon.agent.eval/_result-schemas [:seon.agent.eval/handle]}] [:seon.schema/key :seon.rule/entity]))))
                       (:handle ev-res)))))))

(deftest set-meta-preserves-ops-and-catches-retraction-test
  (testing "set-meta! preserves :ops/:catches and updates :input/:output; removing try/catch retracts :catches"
    (with-test-store [{:keys [conn]}]
      (try
        (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/meta-test}]
          (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/meta_ut.clj" "(ns demo.meta-ut)\n" false)})
          (binding [*ns* *ns*] (eval '(ns demo.meta-ut)))
          (let [a-res (edit/add-fn! 'demo.meta-ut
                                    "(defn work [x] (try {:op :demo/ping :v (inc x)} (catch Exception _ :err)))")]
            (is (:ok? a-res))
            (let [v1 (d/pull @conn '[:seon.code.var/ops :seon.code.var/catches] [:seon.code.var/name 'demo.meta-ut/work])]
              (is (= #{:demo/ping} (set (:seon.code.var/ops v1))))
              (is (seq (:seon.code.var/catches v1))))
            (let [m-res (edit/set-meta! 'demo.meta-ut/work
                                        {:malli/schema [:=> [:cat [:map [:seon.task/id :seon.task/id]
                                                                   [:seon.task/plan {:optional true} :string]]]
                                                        :seon.health/report]})]
              (is (:ok? m-res))
              (let [v2 (d/pull @conn '[:seon.code.var/ops :seon.code.var/catches
                                       {:seon.code.var/input [:seon.schema/key]}
                                       {:seon.code.var/optional-input [:seon.schema/key]}
                                       {:seon.code.var/output [:seon.schema/key]}]
                               [:seon.code.var/name 'demo.meta-ut/work])]
                (is (= #{:demo/ping} (set (:seon.code.var/ops v2))) "set-meta! must preserve :ops")
                (is (seq (:seon.code.var/catches v2)) "set-meta! must preserve :catches")
                (is (= #{:seon.task/id} (set (map :seon.schema/key (:seon.code.var/input v2)))))
                (is (= #{:seon.task/plan} (set (map :seon.schema/key (:seon.code.var/optional-input v2)))))
                (is (= #{:seon.health/report} (set (map :seon.schema/key (:seon.code.var/output v2)))))))
            (let [e-res (edit/edit-fn! 'demo.meta-ut/work
                                       "(try {:op :demo/ping :v (inc x)} (catch Exception _ :err))"
                                       "{:op :demo/pong :v (inc x)}")]
              (is (:ok? e-res))
              (let [v3 (d/pull @conn '[:seon.code.var/ops :seon.code.var/catches] [:seon.code.var/name 'demo.meta-ut/work])]
                (is (= #{:demo/pong} (set (:seon.code.var/ops v3))) "old :op must retract")
                (is (nil? (:seon.code.var/catches v3)) "removing try/catch must retract cardinality-one :catches"))
              (when-let [doc-fn (resolve 'user/doc!)]
                (doc-fn "docs/meta_ut.md#live" "## Live\n" "Calls `demo.meta-ut/work`.")
                (let [refs1 (set (map :seon.code.var/name
                                      (:seon.code.doc/references
                                       (d/pull @conn '[{:seon.code.doc/references [:seon.code.var/name]}]
                                               [:seon.code.doc/id "docs/meta_ut.md#live"]))))]
                  (is (contains? refs1 'demo.meta-ut/work) "live user/doc! must populate :seon.code.doc/references"))
                (doc-fn "docs/meta_ut.md#live" "No citations here.")
                (is (empty? (:seon.code.doc/references
                             (d/pull @conn '[:seon.code.doc/references]
                                     [:seon.code.doc/id "docs/meta_ut.md#live"])))
                    "live user/doc! must retract stale :seon.code.doc/references")))))
        (finally
          (when (find-ns 'demo.meta-ut) (remove-ns 'demo.meta-ut)))))))

(defn- extract-parity-graph [db]
  (let [p-var (fn [vsym]
                (let [m (d/pull db '[:seon.code.var/ops :seon.code.var/catches
                                     {:seon.code.var/calls [:seon.code.var/name]}
                                     {:seon.code.var/input [:seon.schema/key]}
                                     {:seon.code.var/optional-input [:seon.schema/key]}
                                     {:seon.code.var/output [:seon.schema/key]}]
                                [:seon.code.var/name vsym])]
                  {:calls (set (map :seon.code.var/name (:seon.code.var/calls m)))
                   :input (set (map :seon.schema/key (:seon.code.var/input m)))
                   :optional-input (set (map :seon.schema/key (:seon.code.var/optional-input m)))
                   :output (set (map :seon.schema/key (:seon.code.var/output m)))
                   :ops (set (:seon.code.var/ops m))
                   :catches (:seon.code.var/catches m)}))
        p-ns (fn [nsym]
               (set (map :seon.code.ns/name (:seon.code.ns/requires-ns
                                             (d/pull db '[{:seon.code.ns/requires-ns [:seon.code.ns/name]}]
                                                     [:seon.code.ns/name nsym])))))
        p-rule (fn [rid]
                 (let [m (d/pull db '[{:seon.rule/schema [:seon.schema/key]}
                                      {:seon.rule/fn [:seon.code.var/name]}]
                                 [:seon.rule/id rid])]
                   {:schema (set (map :seon.schema/key (:seon.rule/schema m)))
                    :fn (set (map :seon.code.var/name (:seon.rule/fn m)))}))
        p-doc (fn [did]
                (let [rs (:seon.code.doc/references
                          (d/pull db '[{:seon.code.doc/references [:seon.code.var/name :seon.code.ns/name :seon.schema/key]}]
                                  [:seon.code.doc/id did]))]
                  (into #{} (keep #(or (:seon.code.var/name %) (:seon.code.ns/name %) (:seon.schema/key %))) rs)))]
    {:vars {'demo.parity-a/leaf (p-var 'demo.parity-a/leaf)
            'demo.parity-b/entry (p-var 'demo.parity-b/entry)}
     :requires-ns {'demo.parity-b (p-ns 'demo.parity-b)}
     :rule {:rule.parity/card (p-rule :rule.parity/card)}
     :doc {"docs/parity.md#guide" (p-doc "docs/parity.md#guide")}}))

(deftest cold-boot-bootstrap-vs-live-eval-parity-test
  (testing "Fresh-start bootstrap-tx + link pass produces the EXACT same ref graph as live evals"
    (let [repo-dir (str "/tmp/seon-parity-repo-" (System/nanoTime))
          a-src "(ns demo.parity-a)\n\n(defn leaf\n  {:malli/schema [:=> [:cat :seon.task/id] :seon.store/branch]}\n  [id]\n  :main)\n"
          b-src (str "(ns demo.parity-b\n  (:require [demo.parity-a :as pa]))\n\n"
                     "(defn entry\n"
                     "  {:malli/schema [:=> [:cat [:map [:seon.task/id :seon.task/id] [:seon.task/plan {:optional true} :string]]] :seon.health/report]}\n"
                     "  [m]\n"
                     "  (try {:op :parity/run :br (pa/leaf (:seon.task/id m))} (catch Exception _ :fallback)))\n")
          doc-src "## Guide\nUse `demo.parity-b/entry` on `:seon.task/entity` in `demo.parity-a`.\n"
          rule-map {:seon.rule/id :rule.parity/card
                    :seon.rule/category :present
                    :seon.rule/stratum 40
                    :seon.rule/enabled? true
                    :seon.rule/target :any
                    :seon.rule/when '[[?e :ctx/kind :seon.task/entity]]
                    :seon.rule/assert '[[?e :view/fn demo.parity-b/entry]]}
          rules-edn (pr-str [rule-map])]
      (try
        (.mkdirs (io/file repo-dir "src/demo"))
        (.mkdirs (io/file repo-dir "docs"))
        (.mkdirs (io/file repo-dir "resources/seon"))
        (spit (io/file repo-dir "src/demo/parity_a.clj") a-src)
        (spit (io/file repo-dir "src/demo/parity_b.clj") b-src)
        (spit (io/file repo-dir "docs/parity.md") doc-src)
        (spit (io/file repo-dir "resources/seon/rules.edn") rules-edn)
        (git/git! repo-dir "init")
        (git/git! repo-dir "config" "user.name" "Seon")
        (git/git! repo-dir "config" "user.email" "seon@local")
        (git/git! repo-dir "add" "-A")
        (git/git! repo-dir "commit" "-m" "parity seed")
        ;; Load demo namespaces once so compiler analysis can analyze them without side effects
        (binding [*ns* *ns*]
        (eval (read-string a-src))
        (binding [*ns* (the-ns 'demo.parity-a)]
          (eval (read-string "(defn leaf {:malli/schema [:=> [:cat :seon.task/id] :seon.store/branch]} [id] :main)")))
        (eval (read-string b-src))
        (binding [*ns* (the-ns 'demo.parity-b)]
          (eval (read-string "(defn entry {:malli/schema [:=> [:cat [:map [:seon.task/id :seon.task/id] [:seon.task/plan {:optional true} :string]]] :seon.health/report]} [m] (try {:op :parity/run :br (pa/leaf (:seon.task/id m))} (catch Exception _ :fallback)))"))))
        (let [snap-cold
              (with-test-store [{:keys [conn]}]
                (d/transact conn {:tx-data (git/bootstrap-tx @conn repo-dir)})
                (let [bf-ops (analyze/backfill-codebase-analysis-tx @conn {:namespaces '[demo.parity-a demo.parity-b]})]
                  (when (seq bf-ops) (d/transact conn {:tx-data bf-ops}))
                  (is (empty? (analyze/backfill-codebase-analysis-tx @conn {:namespaces '[demo.parity-a demo.parity-b]}))
                      "backfill-codebase-analysis-tx must be 100% idempotent"))
                (extract-parity-graph @conn))
              snap-live
              (with-test-store [{:keys [conn]}]
                (let [r1 (code/eval-forms @conn {:agent-id :agent/live :turn 1} a-src)
                      _ (d/transact conn {:tx-data (:tx-data r1)})
                      r2 (code/eval-forms @conn {:agent-id :agent/live :turn 2} b-src)
                      _ (d/transact conn {:tx-data (:tx-data r2)})
                      doc-ent (first (cite/enrich-doc-sections @conn [{:seon.code.doc/id "docs/parity.md#guide"
                                                                       :seon.code.doc/path "docs/parity.md"
                                                                       :seon.code.doc/order 0
                                                                       :seon.code.doc/heading "## Guide\n"
                                                                       :seon.code.doc/body "Use `demo.parity-b/entry` on `:seon.task/entity` in `demo.parity-a`.\n"}]))
                      _ (d/transact conn {:tx-data [doc-ent]})
                      _ (d/transact conn {:tx-data (files/enrich-rule-tx @conn rule-map)})]
                  (extract-parity-graph @conn)))]
          (is (= snap-live snap-cold)
              (str "Cold-boot bootstrap+link MUST match live eval!\nLive: " (pr-str snap-live) "\nCold: " (pr-str snap-cold)))
          (is (= #{'demo.parity-a/leaf} (get-in snap-cold [:vars 'demo.parity-b/entry :calls])))
          (is (= #{:parity/run} (get-in snap-cold [:vars 'demo.parity-b/entry :ops])))
          (is (seq (get-in snap-cold [:vars 'demo.parity-b/entry :catches])))
          (is (= #{:seon.task/id} (get-in snap-cold [:vars 'demo.parity-b/entry :input])))
          (is (= #{:seon.task/plan} (get-in snap-cold [:vars 'demo.parity-b/entry :optional-input])))
          (is (= #{:seon.health/report} (get-in snap-cold [:vars 'demo.parity-b/entry :output])))
          (is (= #{'demo.parity-a} (get-in snap-cold [:requires-ns 'demo.parity-b])))
          (is (= #{'demo.parity-b/entry} (get-in snap-cold [:rule :rule.parity/card :fn])))
          (is (= #{:seon.task/entity} (get-in snap-cold [:rule :rule.parity/card :schema])))
          (is (= #{'demo.parity-b/entry 'demo.parity-a :seon.task/entity} (get-in snap-cold [:doc "docs/parity.md#guide"]))))
        (finally
          (when (find-ns 'demo.parity-b) (remove-ns 'demo.parity-b))
          (when (find-ns 'demo.parity-a) (remove-ns 'demo.parity-a))
          (store/rm-rf! (io/file repo-dir)))))))
