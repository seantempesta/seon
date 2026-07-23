(ns seon.program-plan-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [seon.program.edge :as edge]
   [seon.program.plan :as plan]
   [seon.schema :as schema]))

(def database
  {:db-name "planner-test"
   :store-id ["planner-test" "main"]
   :t 7
   :datahike/commit-id "commit-7"})

(def schema-projection
  (schema/projection-from-rows
   {:seon.schema/schema-rows
    #{[:demo/id "[:string {:seon.db/identity true}]"]
      [:demo/name ":string"]
      [:demo/entity
       "[:map {:closed true :seon.db/entity true} [:demo/id :demo/id] [:demo/name :demo/name]]"]}
    :seon.schema/function-contract-rows
    #{['fixture/root "[:=> [:cat :demo/entity] :demo/name]"]}}))

(defn terminal [target effect]
  {::edge/terminal-symbol target
   ::edge/effect effect
   ::edge/required-bindings #{target}
   ::edge/terminal-generation (str "terminal-" target)})

(defn bundle
  ([function-symbol calls]
   (bundle function-symbol calls #{} #{} false #{} []))
  ([function-symbol calls reads writes all? uncertainties terminals]
   {::edge/function-symbol function-symbol
    ::edge/generation (str "generation-" function-symbol)
    ::edge/calls calls
    ::edge/read-attributes reads
    ::edge/written-attributes writes
    ::edge/all-at-basis? all?
    ::edge/uncertainties uncertainties
    ::edge/terminals terminals}))

(def tier-inventories
  {:bun
   {:seon.execution.inventory/bindings
    #{"seon.db/query" "seon.packages.js.lodash/get"}
    :seon.execution.inventory/remote-bindings #{}
    :seon.execution.inventory/pure-bindings #{"clojure.core/inc"}
    :seon.execution.inventory/digest "bun-v1"}
   :jvm
   {:seon.execution.inventory/bindings #{"seon.db/query"}
    :seon.execution.inventory/remote-bindings #{}
    :seon.execution.inventory/pure-bindings #{"clojure.core/inc"}
    :seon.execution.inventory/digest "jvm-v1"}})

(defn projection
  ([bundles]
   (projection bundles
               {:seon.execution.inventory/availability :unavailable
                :seon.execution.inventory/unavailable-reason
                :missing-artifact-export-inventory}))
  ([bundles artifacts]
   {:seon.execution/basis-t (:t database)
    :seon.execution/commit-id (:datahike/commit-id database)
    :seon.execution/edge-bundles
    (into {} (map (juxt ::edge/function-symbol identity)) bundles)
    :seon.execution/graph-digest (edge/program-graph-digest bundles)
    :seon.execution/schema-projection schema-projection
    :seon.execution/schema-fingerprint
    (:seon.schema.projection/fingerprint schema-projection)
    :seon.execution/artifact-inventories artifacts}))

(defn request [roots bundles]
  {:seon.execution/db-value database
   :seon.execution/roots roots
   :seon.execution/tier-inventories tier-inventories
   :seon.execution/planning-projection (projection bundles)})

(deftest pure-corpus-chain-is-anywhere-and-cycle-safe
  (let [bundles [(bundle "fixture/root" #{"fixture/helper"})
                 (bundle "fixture/helper" #{"fixture/root"})]
        result (plan/plan-execution (request ["fixture/root"] bundles))]
    (is (= :anywhere (:seon.execution/placement result)))
    (is (= #{:bun :jvm} (:seon.execution/eligible-tiers result)))
    (is (empty? (:seon.execution/unresolved result)))))

(deftest capabilities-and-package-prefixes-constrain-honest-tiers
  (doseq [[target effect expected]
          [["seon.db/query" :read #{:bun :jvm}]
           ["seon.packages.js.lodash/get" :external #{:bun}]]]
    (let [root (bundle "fixture/root" #{target} #{} #{} false #{}
                       [(terminal target effect)])
          result (plan/plan-execution (request ["fixture/root"] [root]))]
      (is (= :constrained (:seon.execution/placement result)) target)
      (is (= expected (:seon.execution/eligible-tiers result)) target))))

(deftest uncertainty-and-absent-artifact-inventory-fail-closed
  (testing "the dynamic keyword edge is named"
    (let [root (bundle "fixture/root" #{} #{} #{} false
                       #{:constructed-keyword} [])
          result (plan/plan-execution (request ["fixture/root"] [root]))]
      (is (= :unplannable (:seon.execution/placement result)))
      (is (= [:constructed-keyword]
             (mapv :seon.execution/reason
                   (:seon.execution/unresolved result))))))
  (testing "compiled-only steering names the missing inventory"
    (let [target "fixture.compiled/leaf"
          root (bundle "fixture/root" #{target} #{} #{} false #{}
                       [(terminal target :pure)])
          result (plan/plan-execution (request ["fixture/root"] [root]))]
      (is (= :unplannable (:seon.execution/placement result)))
      (is (= :missing-artifact-export-inventory
             (-> result :seon.execution/unresolved first
                 :seon.execution/reason)))
      (is (re-find #"artifact export inventory"
                   (-> result :seon.execution/unresolved first
                       :seon.execution/steering))))))

(deftest manifests-are-exact-or-all-at-basis
  (let [root (bundle "fixture/root" #{} #{:demo/name} #{:demo/id}
                     false #{} [])
        exact (plan/plan-execution (request ["fixture/root"] [root]))
        open-root (assoc root ::edge/all-at-basis? true)
        open (plan/plan-execution (request ["fixture/root"] [open-root]))
        manifest (:seon.execution/schema-manifest exact)]
    (is (= #{:demo/id :demo/name}
           (:seon.execution/attributes manifest)))
    (is (= #{:demo/entity :demo/id :demo/name}
           (:seon.execution/schema-keys manifest)))
    (is (plan/manifest-covered-by-projection?
         manifest schema-projection
         (:seon.schema.projection/fingerprint schema-projection)))
    (is (= :all-at-basis
           (get-in open [:seon.execution/schema-manifest
                         :seon.execution/attributes])))))

(deftest projection-fence-mismatch-is-a-flat-core-bug
  (let [result
        (plan/plan-execution
         (assoc-in
          (request ["fixture/root"] [(bundle "fixture/root" #{})])
          [:seon.execution/planning-projection :seon.execution/basis-t]
          8))]
    (is (= :core-bug (:seon.error/kind result)))
    (is (string? (:seon.error/message result)))
    (is (map? (:seon.error/data result)))))

(deftest every-cache-input-digest-mutates-the-key
  (let [root (bundle "fixture/root" #{})
        base-request (request ["fixture/root"] [root])
        base-key (:seon.execution/cache-key
                  (plan/plan-execution base-request))
        mutations
        [(assoc-in base-request
                   [:seon.execution/planning-projection
                    :seon.execution/graph-digest]
                   "changed-graph")
         (-> base-request
             (assoc-in
              [:seon.execution/planning-projection
               :seon.execution/schema-fingerprint]
              101)
             (assoc-in
              [:seon.execution/planning-projection
               :seon.execution/schema-projection
               :seon.schema.projection/fingerprint]
              101))
         (assoc-in base-request
                   [:seon.execution/tier-inventories :bun
                    :seon.execution.inventory/digest]
                   "changed-tier")
         (assoc-in base-request
                   [:seon.execution/planning-projection
                    :seon.execution/artifact-inventories]
                   {:seon.execution.inventory/availability :available
                    :seon.execution.inventory/exports-by-tier {}
                    :seon.execution.inventory/digest "changed-artifacts"})
         (assoc base-request :seon.execution/invocation
                {:fixture/argument 1})]]
    (doseq [mutation mutations]
      (is (not= base-key
                (:seon.execution/cache-key
                 (plan/plan-execution mutation)))))))
