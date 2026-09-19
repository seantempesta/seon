(ns my.plan-test
  "The agent-owned plan component tree, its derivation, and its two projections."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.plan :as plan]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.env :as env]
            [seon.render :as render]
            [seon.schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(def ^:private t0-ms 1786500000000)

(defn- at
  [offset]
  (java.util.Date. (long (+ t0-ms offset))))

(defn- with-plan
  [f]
  (support/with-database
    (fn [connection]
      (support/transacted! connection
                           [{:db/id "plan-ns"
                             :seon.ns/name 'fixture.plan}
                            {:seon.agent/id "alice"
                             :seon.agent/namespace "plan-ns"}
                            {:seon.agent/id "bob"}])
      (f connection))))

(defn- add
  ([connection id title]
   (add connection id title {}))
  ([connection id title more]
   (plan/add! (merge {:my.plan.item/id id :my.plan.item/title title} more)
              connection "alice")))

(defn- plan-of
  ([connection] (plan-of connection "alice"))
  ([connection agent-id]
   (plan/plan {:seon.db/db @connection :seon.agent/id agent-id})))

(defn- render-view
  [connection view]
  (assoc view
         :seon.db/db @connection
         :seon.render.value/root [:seon.agent/id (:seon.agent/id view)]
         :seon.render/profile (render/agent-render-profile (support/effective-config))
         :seon.sci.admit/caps (config/result-caps (support/effective-config))))

(defn- ids
  [steps]
  (mapv :my.plan.item/id steps))

(defn- numbers-in
  [value]
  (cond
    (number? value) [value]
    (map? value) (into [] (mapcat numbers-in) (vals value))
    (coll? value) (into [] (mapcat numbers-in) value)
    :else []))

(deftest terminal-formatters-say-a-refusal-in-their-own-words
  (with-plan
    (fn [connection]
      (let [failure (-> (db/q '[:find ?entity .
                                :where [?entity :seon.audit/poison _]]
                              @connection)
                        (assoc :seon.error/data {:seon.agent/id "alice"}))]
        (is (and (map? failure)
                 (contains? failure :seon.error/at)
                 (contains? failure :seon.error/layer)
                 (contains? failure :seon.error/operation)) (pr-str failure))
        (doseq [[subject line] [["Plan step" (plan/format-item-ai failure)]
                                ["Ready work" (plan/format-ready-items-ai failure)]
                                ["Plan" (plan/format-plan-ai failure)]]]
          (is (string? line)
              "a refusal reaching a projection is said, never handed on raw")
          (is (str/starts-with? line
                                (str subject " unavailable for \"alice\"")))
          (is (str/includes? line ":seon.db/invalid-read"))
          (is (str/includes? line "uninstalled attribute")))))))

(deftest a-refused-derivation-renders-a-typed-line-where-instructions-belong
  (with-plan
    (fn [connection]
      (let [refusal (plan/plan {:seon.db/db @connection
                                :seon.agent/id "nobody"})
            source (plan/render-plan-ai {:seon.db/db @connection
                                         :seon.agent/id "nobody"})]
        (is (= :my.plan/agent-not-found (:seon.error/kind refusal))
            "the derivation refuses as a flat value, never by throwing")
        (is (str/includes? source "(seon.plan/format-plan-ai (seon.plan/plan {}))")
            "the refusal renders through this plan's own AI pair")
        (is (str/starts-with? (plan/format-plan-ai refusal)
                              "Plan unavailable for \"nobody\""))))))

(deftest the-derivation-answers-for-a-plan-with-no-steps-and-for-parents
  (with-plan
    (fn [connection]
      (let [empty-plan (plan-of connection)]
        (is (= [] (:my.plan/steps empty-plan)))
        (is (= [] (:my.plan/ready empty-plan)))
        (is (= [] (:my.plan/blocked empty-plan)))
        (is (not (contains? empty-plan :seon.error/kind))))
      (add connection "root" "Improve the plan")
      (add connection "child" "The only child"
           {:my.plan/parent-step [:my.plan.item/id "root"]})
      (let [nested (plan-of connection)]
        (is (= ["child"] (ids (:my.plan/ready nested)))
            "a parent carrying open work below it is not ready")
        (is (= :open (:my.plan/state (first (:my.plan/steps nested))))))
      (plan/complete! "child" connection "alice")
      (let [settled (plan-of connection)]
        (is (= ["root"] (ids (:my.plan/ready settled)))
            "a parent whose steps are all complete becomes ready itself")
        (is (= [] (:my.plan/blocked settled)))))))

(deftest an-empty-plan-renders-in-both-projections
  (with-plan
    (fn [connection]
      (let [current (plan-of connection)
            ai (plan/format-plan-ai current)
            html (plan/render-plan-html (render-view connection current))]
        (is (= [] (:my.plan/steps current)))
        (is (= [] (:my.plan/ready current)))
        (is (not (contains? current :my.plan/current-step)))
        (is (= {} (:seon.plan/step-lines (edn/read-string ai))))
        (is (nil? (:seon.plan/current-line (edn/read-string ai))))
        (is (str/includes? (pr-str html) "No steps yet."))))))

(deftest one-step-is-owned-by-the-agent-through-the-component-edge
  (with-plan
    (fn [connection]
      (let [added (add connection "ship" "Ship the plan unit")
            current (plan-of connection)]
        (is (= "ship" (:my.plan.item/id added)))
        (is (= {:my.plan.item/id "ship" :my.plan.item/title "Ship the plan unit"
                :my.plan/state :ready :my.plan/needs []} added))
        (is (= ["ship"] (ids (:my.plan/steps current))))
        (is (= ["ship"] (ids (:my.plan/ready current))))
        (is (= 0 (:my.plan/depth (first (:my.plan/steps current)))))
        (is (= #{["ship"]}
               (db/q '[:find ?id
                       :where
                       [?agent :seon.agent/id "alice"]
                       [?agent :seon.agent/plan ?plan]
                       [?plan :my.plan/steps ?step]
                       [?step :my.plan.item/id ?id]]
                     @connection))
            "ownership is the forward component edge, with no stored backlink")))))

(deftest nested-steps-derive-parent-depth-and-order
  (with-plan
    (fn [connection]
      (add connection "root" "Improve the plan")
      (add connection "second" "Second child"
           {:my.plan/parent-step [:my.plan.item/id "root"]})
      (add connection "first" "First child"
           {:my.plan/parent-step [:my.plan.item/id "root"]})
      (let [steps (:my.plan/steps (plan-of connection))
            by-id (into {} (map (juxt :my.plan.item/id identity)) steps)]
        (is (= ["root" "second" "first"] (ids steps))
            "siblings order by stored position, not by identity")
        (is (= 1 (:my.plan/depth (by-id "first"))))
        (is (= {:my.plan.item/id "root"} (:my.plan/parent (by-id "first")))
            "a parent derives from the reverse component edge")
        (is (not (contains? (by-id "root") :my.plan/parent)))))))

(deftest completing-a-dependency-unblocks-the-dependent-step
  (with-plan
    (fn [connection]
      (add connection "root" "Improve the plan")
      (add connection "prepare" "Prepare"
           {:my.plan/parent-step [:my.plan.item/id "root"]})
      (add connection "verify" "Verify"
           {:my.plan/parent-step [:my.plan.item/id "root"]
            :my.plan.item/needs #{[:my.plan.item/id "prepare"]}})
      (is (= ["prepare"] (ids (:my.plan/ready (plan-of connection)))))
      (is (= ["verify"] (ids (:my.plan/blocked (plan-of connection)))))
      (plan/complete! "prepare" connection "alice")
      (let [current (plan-of connection)]
        (is (= ["verify"] (ids (:my.plan/ready current))))
        (is (= [] (:my.plan/blocked current)))
        (is (= ["prepare"] (ids (:my.plan/recent-completions current))))))))

(deftest compact-reads-and-writes-preserve-ownership-and-dependencies
  (with-plan
    (fn [connection]
      (is (= {} (plan/current @connection "alice")))
      (is (str/includes? (plan/render-plan-ai (plan-of connection)) "(seon.plan/plan {})"))
      (let [prepare (add connection "prepare" "Prepare")
            verify (add connection "verify" "Verify"
                        {:my.plan.item/needs #{[:my.plan.item/id "prepare"]}})]
        (is (= [prepare] (plan/ready @connection "alice")))
        (is (= [verify] (plan/blocked @connection "alice")))
        (is (= ["prepare"] (:my.plan/needs verify)))
        (is (= [prepare verify] (plan/steps @connection "alice")))
        (is (= :my.plan/not-owned
               (:seon.error/kind (plan/start! "prepare" connection "bob"))))
        (is (= (assoc prepare :my.plan/state :current) (plan/start! "prepare" connection "alice")))
        (is (= (assoc prepare :my.plan/state :current) (plan/current @connection "alice")))
        (let [completed (plan/complete! "prepare" connection "alice")]
          (is (= (assoc prepare :my.plan/state :completed)
                 (dissoc completed :my.plan.item/completed-tx)))
          (is (inst? (get-in completed [:my.plan.item/completed-tx :db/txInstant]))))
        (is (= {} (plan/current @connection "alice")))
        (is (= :my.plan/unusable-current-step
               (:seon.error/kind (plan/start! "prepare" connection "alice"))))
        (is (= [(assoc verify :my.plan/state :ready)] (plan/ready @connection "alice")))
        (is (= [] (plan/blocked @connection "alice")))))))

(deftest a-changed-title-is-an-ordinary-fact-update
  (with-plan
    (fn [connection]
      (add connection "ship" "Old title")
      (support/transacted! connection
                           [{:db/id [:my.plan.item/id "ship"]
                             :my.plan.item/title "New title"}])
      (is (= ["New title"]
             (mapv :my.plan.item/title
                   (:my.plan/steps (plan-of connection))))))))

(deftest a-plan-without-a-current-step-still-renders
  (with-plan
    (fn [connection]
      (add connection "ship" "Ship the plan unit")
      (let [current (plan-of connection)]
        (is (not (contains? current :my.plan/current-step)))
        (is (= :ready (:my.plan/state (first (:my.plan/steps current)))))
        (is (nil? (:seon.plan/current-line
                   (edn/read-string (plan/format-plan-ai current)))))))))

(deftest completing-the-current-step-retracts-the-current-step-ref
  (with-plan
    (fn [connection]
      (add connection "ship" "Ship the plan unit" {:my.plan/current? true})
      (is (= {:my.plan.item/id "ship"}
             (:my.plan/current-step (plan-of connection))))
      (is (= :current
             (:my.plan/state (first (:my.plan/steps (plan-of connection))))))
      (plan/complete! "ship" connection "alice")
      (let [current (plan-of connection)]
        (is (not (contains? current :my.plan/current-step))
            "completion clears the selected focus")
        (is (= :completed
               (:my.plan/state (first (:my.plan/steps current)))))))))

(deftest retracting-a-parent-retracts-only-what-it-owns
  (with-plan
    (fn [connection]
      (add connection "root" "Improve the plan")
      (add connection "child" "Child"
           {:my.plan/parent-step [:my.plan.item/id "root"]})
      (add connection "grandchild" "Grandchild"
           {:my.plan/parent-step [:my.plan.item/id "child"]})
      (add connection "sibling" "Independent root")
      (support/transacted! connection
                           [[:db.fn/retractEntity [:my.plan.item/id "root"]]])
      (let [current (plan-of connection)]
        (is (= ["sibling"] (ids (:my.plan/steps current)))
            "component retraction removes the owned subtree and nothing else")
        (is (nil? (db/q '[:find ?step .
                          :where [?step :my.plan.item/id "grandchild"]]
                        @connection)))))))

(deftest the-whole-plan-renders-once-in-both-projections
  (with-plan
    (fn [connection]
      (add connection "root" "Improve the plan" {:my.plan/current? true})
      (add connection "child" "Inspect the facts"
           {:my.plan/parent-step [:my.plan.item/id "root"]})
      (let [current (plan-of connection)
            ai (plan/format-plan-ai current)
            html (plan/render-plan-html (render-view connection current))
            printed (pr-str html)]
        (is (= "alice" (:seon.agent/id (edn/read-string ai))))
        (is (= 1 (count (re-seq #"my-plan\"" printed))))
        (is (str/includes? ai "1. current — Improve the plan"))
        (is (str/includes? ai "1.1 ready — Inspect the facts"))
        (is (not (str/includes? ai "Objective: Improve the plan"))
            "a step title is not the plan objective")
        (is (str/includes? (:seon.plan/current-line (edn/read-string ai)) "Improve the plan"))
        (is (str/includes? printed "Inspect the facts"))
        (is (str/includes? printed "0 of 2 steps completed"))))))

(deftest plan-source-selects-reads-from-current-data
  (with-plan
    (fn [connection]
      (add connection "ship" "Ship the plan unit")
      (is (str/includes? (plan/render-plan-ai (plan-of connection)) "(seon.plan/plan {})")
          "the AI projection emits source the agent can run itself"))))

(deftest no-numeric-entity-reference-reaches-either-projection
  ;; The class this kills: a pulled ref normalized to its entity id and then
  ;; treated as a lookup vector, which threw
  ;; "Don't know how to create ISeq from: java.lang.Long".
  (with-plan
    (fn [connection]
      (add connection "prepare" "Prepare")
      (add connection "verify" "Verify"
           {:my.plan.item/needs #{[:my.plan.item/id "prepare"]}})
      (let [current (plan-of connection)
            step (first (filter #(= "verify" (:my.plan.item/id %))
                                (:my.plan/steps current)))
            entity-ids (set (db/q '[:find [?step ...]
                                    :where [?step :my.plan.item/id]]
                                  @connection))
            html (plan/render-plan-html (render-view connection current))
            ai (plan/format-plan-ai current)]
        (is (= ["prepare"] (:my.plan/needs step))
            "a dependency travels as its stable identity")
        (is (empty? (filter entity-ids (numbers-in html))))
        (is (empty? (filter entity-ids (numbers-in current))))
        (is (not (str/includes? ai (str (first entity-ids)))))
        (testing "the contract refuses a bare numeric reference"
          (is (seon.schema/valid-candidate-value? :my.plan/render-step step))
          (is (not (seon.schema/valid-candidate-value?
                    :my.plan/render-step
                    (assoc step :my.plan/needs [42])))))))))

(deftest whole-tree-reconciliation-refuses-incoherent-documents
  (with-plan
    (fn [connection]
      (let [tree {:my.plan/steps
                  [{:my.plan.item/id "root"
                    :my.plan.item/title "Improve the plan"
                    :my.plan.item/steps
                    [{:my.plan.item/id "prepare"
                      :my.plan.item/title "Prepare"}
                     {:my.plan.item/id "verify"
                      :my.plan.item/title "Verify"
                      :my.plan.item/needs #{[:my.plan.item/id "prepare"]}}]}]
                  :my.plan/current-step {:my.plan.item/id "prepare"}}]
        (is (= {:my.plan/added 3 :my.plan/changed 0 :my.plan/retracted 0}
               (:my.plan/diff (plan/plan! tree @connection connection "alice"))))
        (let [current (plan-of connection)]
          (is (= ["root" "prepare" "verify"] (ids (:my.plan/steps current))))
          (is (= {:my.plan.item/id "prepare"} (:my.plan/current-step current))))
        (testing "two siblings may not claim one position"
          (is (= :my.plan/duplicate-position
                 (:seon.error/kind
                  (plan/plan!
                   {:my.plan/steps
                    [{:my.plan.item/id "a" :my.plan.item/title "A"
                      :my.plan.item/position 0}
                     {:my.plan.item/id "b" :my.plan.item/title "B"
                      :my.plan.item/position 0}]}
                   @connection connection "alice")))))
        (testing "one step may not be owned by two parents"
          (is (= :my.plan/duplicate-identity
                 (:seon.error/kind
                  (plan/plan!
                   {:my.plan/steps
                    [{:my.plan.item/id "one" :my.plan.item/title "One"
                      :my.plan.item/steps
                      [{:my.plan.item/id "shared"
                        :my.plan.item/title "Shared"}]}
                     {:my.plan.item/id "two" :my.plan.item/title "Two"
                      :my.plan.item/steps
                      [{:my.plan.item/id "shared"
                        :my.plan.item/title "Shared"}]}]}
                   @connection connection "alice")))))
        (testing "the current step must be an open step of this plan"
          (is (= :my.plan/unusable-current-step
                 (:seon.error/kind
                  (plan/plan!
                   {:my.plan/steps [{:my.plan.item/id "root"
                                     :my.plan.item/title "Improve the plan"}]
                    :my.plan/current-step {:my.plan.item/id "absent"}}
                   @connection connection "alice")))))
        (testing "dependencies may not form a cycle"
          (is (= :my.plan/dependency-cycle
                 (:seon.error/kind
                  (plan/plan!
                   {:my.plan/steps
                    [{:my.plan.item/id "prepare" :my.plan.item/title "Prepare"
                      :my.plan.item/needs #{[:my.plan.item/id "verify"]}}
                     {:my.plan.item/id "verify" :my.plan.item/title "Verify"
                      :my.plan.item/needs #{[:my.plan.item/id "prepare"]}}]}
                   @connection connection "alice")))))))))

(deftest whole-tree-reconciliation-retracts-omitted-steps
  (with-plan
    (fn [connection]
      (plan/plan! {:my.plan/steps
                   [{:my.plan.item/id "root"
                     :my.plan.item/title "Improve the plan"
                     :my.plan.item/steps
                     [{:my.plan.item/id "child" :my.plan.item/title "Child"}]}]}
                  @connection connection "alice")
      (let [result (plan/plan!
                    {:my.plan/steps [{:my.plan.item/id "root"
                                      :my.plan.item/title "Improve the plan"}]}
                    @connection connection "alice")]
        (is (= {:my.plan/added 0 :my.plan/changed 0 :my.plan/retracted 1}
               (:my.plan/diff result))
            "an unchanged step is not counted as changed")
        (is (= ["root"] (ids (:my.plan/steps (plan-of connection)))))
        (is (:my.plan/converged?
             (plan/plan!
              {:my.plan/steps [{:my.plan.item/id "root"
                                :my.plan.item/title "Improve the plan"}]}
              @connection connection "alice"))
            "reconciling the same tree again writes nothing")))))

(deftest ready-subjects-resolve-from-the-component-tree
  (with-plan
    (fn [connection]
      (add connection "ship" "Ship the plan unit"
           {:my.plan.item/about ['seon.plan/plan! 'my.plan :my.plan.item/title]})
      (is (= ['seon.plan/plan! 'my.plan :my.plan.item/title]
             (mapv (fn [subject]
                     (or (db/q '[:find ?function .
                                 :in $ ?subject
                                 :where [?subject :seon.fn/sym ?function]]
                               @connection subject)
                         (db/q '[:find ?namespace .
                                 :in $ ?subject
                                 :where [?subject :seon.ns/name ?namespace]]
                               @connection subject)
                         (db/q '[:find ?key .
                                 :in $ ?subject
                                 :where [?subject :seon.schema/key ?key]]
                               @connection subject)))
                   (plan/ready-subjects @connection "alice")))))))

(deftest plan-reads-and-ownership-refuse-an-unreadable-database
  (with-plan
    (fn [connection]
      (add connection "owned" "Owned")
      (let [database @connection
            refusal (db/q '[:find ?entity .
                            :where [?entity :seon.audit/poison _]]
                          database)
            alice (db/q '[:find ?agent .
                          :where [?agent :seon.agent/id "alice"]]
                        database)
            query db/q]
        (is (and (map? refusal)
                 (contains? refusal :seon.error/at)
                 (contains? refusal :seon.error/layer)
                 (contains? refusal :seon.error/operation)) (pr-str refusal))
        (with-redefs [db/q (fn [& arguments]
                            (let [form (first arguments)]
                              (if (#{'[:find ?step .
                                      :in $ % ?agent ?step
                                      :where (owned ?agent ?step)]
                                    '[:find (max ?position) .
                                      :in $ ?owner ?attribute
                                      :where [?owner ?attribute ?child]
                                             [?child :my.plan.item/position ?position]]}
                                   form)
                                refusal
                                (apply query arguments))))]
          (is (= refusal
                 (support/refusal-data
                  #(#'plan/owned-step-eid!
                    database alice [:my.plan.item/id "owned"]
                    :my.plan/parent-step)))
              "an unreadable ownership query refuses verbatim")
          (is (= refusal
                 (support/refusal-data
                  #(#'plan/next-position database 1 :my.plan/steps)))
              "an unreadable position query is never coerced to long"))))))

(deftest a-second-agent-renders-through-the-same-defaults
  (with-plan
    (fn [connection]
      (support/transacted! connection
                           (filterv :seon.call-preparation/key
                                    (:seon.config/initialization
                                     (config/compile-manifest {:seon.boot/cluster-name "default"}))))
      (add connection "alice-work" "Alice work")
      (plan/add! {:my.plan.item/id "bob-work" :my.plan.item/title "Bob work"}
                 connection "bob")
      (let [database @connection
            acquired (sci.eval/cluster-ctx database connection)
            environment
            (env/refuse-incomplete-environment!
             (env/environment
              {:seon.boot/cluster-name "plan-map-defaults"
               :seon.db/connection connection
               :seon.schema/projection (:seon.schema/projection acquired)}))
            base (env/carry-state acquired
                                  (env/environment-state environment))
            evaluate
            (fn [agent-id source]
              (let [live (:seon.sci.eval/ctx
                          (sci.eval/fork-for-turn
                           {:seon.sci.eval/ctx base
                            :seon.db/db database
                            :seon.db/connection connection
                            :seon.agent/id agent-id}))]
                (:seon.sci.admit/value
                 (sci.eval/evaluate
                  {:seon.sci.eval/ctx live
                   :seon.agent/id agent-id
                   :seon.sci.admit/caps
                   (config/result-caps (support/effective-config))
                   :seon.sci.eval/time-limit-ms 5000
                   :seon.config/on-core-error :panic
                   :seon.cluster.eval/source source
                   :seon.cluster.eval/ns
                   [:seon.ns/name 'fixture.plan]}))))
            alice (evaluate "alice" "(my.plan/plan {})")
            bob (evaluate "bob" "(my.plan/plan {})")
            explicit (evaluate "alice"
                               "(my.plan/plan {:seon.agent/id \"bob\"})")
            bob-text (evaluate "bob"
                               "(seon.plan/format-plan-ai (my.plan/plan {}))")]
        (is (= ["alice-work"] (ids (:my.plan/ready alice))))
        (is (= ["bob-work"] (ids (:my.plan/ready bob))))
        (is (= bob explicit)
            "an explicit map entry wins over the calling agent default")
        (is (= "bob" (:seon.agent/id (edn/read-string bob-text)))
            "the rendered source resolves the calling agent")))))

(def ^:private juniper-fixture-steps
  ;; The shape installed by
  ;; docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj.
  #{{:db/id "step-inspect"
        :my.plan.item/id "juniper/inspect-identity-messages"
        :my.plan.item/position 0
        :my.plan.item/title "Inspect identity and messages"
        :my.plan.item/completed-tx "datomic.tx"}
       {:db/id "step-render-plan"
        :my.plan.item/id "juniper/render-plan"
        :my.plan.item/position 1
        :my.plan.item/title "Render this plan clearly"}
       {:db/id "step-compare"
        :my.plan.item/id "juniper/compare-changed-results"
        :my.plan.item/position 2
        :my.plan.item/title "Compare refreshed results"
        :my.plan.item/needs #{"step-render-plan"}}
       {:db/id "step-live-turn"
        :my.plan.item/id "juniper/try-live-turn"
        :my.plan.item/position 3
        :my.plan.item/title "Try the assembled context in a live agent turn"
        :my.plan.item/needs #{"step-compare"}}})

(deftest the-example-fixture-shape-installs-and-renders
  (support/with-database
    (fn [connection]
      (support/transacted! connection
                           [{:seon.agent/id "juniper"}
                            {:db/id [:seon.agent/id "juniper"]
                             :seon.agent/plan
                             {:my.plan/agent [:seon.agent/id "juniper"]
                              :my.plan/objective "Improve Juniper context inspection"
                              :my.plan/current-step "step-render-plan"
                              :my.plan/steps juniper-fixture-steps}}])
      (let [current (plan/plan {:seon.db/db @connection
                                :seon.agent/id "juniper"})
            ai (plan/render-plan-ai current)
            printed (pr-str (plan/render-plan-html (render-view connection current)))]
        (is (= ["juniper/inspect-identity-messages"
                "juniper/render-plan"
                "juniper/compare-changed-results"
                "juniper/try-live-turn"]
               (ids (:my.plan/steps current)))
            "nested transaction data installs one ordered component tree")
        (is (= ["juniper/render-plan"] (ids (:my.plan/ready current))))
        (is (= ["juniper/compare-changed-results" "juniper/try-live-turn"]
               (ids (:my.plan/blocked current))))
        (is (= ["juniper/inspect-identity-messages"]
               (ids (:my.plan/recent-completions current))))
        (is (= "Improve Juniper context inspection" (:my.plan/objective current)))
        (is (= "juniper/render-plan"
               (get-in current [:my.plan/current-step :my.plan.item/id])))
        (is (str/includes? ai "(seon.plan/plan {})"))
        (is (str/includes? ai "(my.plan/complete! {:my.plan.item/id \"juniper/render-plan\"})"))
        (is (str/includes? ai "my.plan/current! selects; completing clears the selection"))
        (is (not (str/includes? (plan/render-plan-ai (dissoc current :my.plan/current-step))
                               "(my.plan/complete!")))
        (is (str/includes? printed "1 of 4 steps completed"))
        (is (str/includes? printed "Current step: "))
        (is (not (str/includes? printed ":open nil"))
            "no nil attribute reaches the rendered panel")))))

(deftest the-plan-component-holds-objective-tree-and-current-step
  (with-plan
    (fn [connection]
      (plan/plan! {:my.plan/objective "Ship the change"
                  :my.plan/steps [{:my.plan.item/id "ship"
                                   :my.plan.item/title "Verify it"}]
                  :my.plan/current-step {:my.plan.item/id "ship"}}
                 @connection connection "alice")
      (let [agent (db/pull @connection
                           '[:my.plan/steps :my.plan/current-step
                             {:seon.agent/plan [*]}]
                           [:seon.agent/id "alice"])
            component (:seon.agent/plan agent)]
        (is (= "Ship the change" (:my.plan/objective component)))
        (is (seq (:my.plan/steps component)))
        (is (:my.plan/current-step component))
        (is (not (contains? agent :my.plan/steps)))
        (is (not (contains? agent :my.plan/current-step)))
        (is (= "ship" (:my.plan.item/id (plan/current @connection "alice"))))
        (support/transacted! connection [[:db.fn/retractEntity (:db/id component)]])
        (is (= "alice" (:seon.agent/id
                        (db/pull @connection [:seon.agent/id]
                                 [:seon.agent/id "alice"])))
            "the fixture preserves the agent's durable identity")
        (is (nil? (db/pull @connection '[*] (:db/id component))))
        (is (nil? (db/pull @connection '[*] [:my.plan.item/id "ship"])))))))
