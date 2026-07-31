(ns seon.render.walk-test
  "Seeded properties for schema-derived neighbourhood membership and caps."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.cluster.agent :as agent]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(def ^:private property-seed 2026073101)

(def ^:private base-caps
  {:seon.config.eval.result/max-depth 8
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(defn- nodes
  [node]
  (tree-seq #(seq (:seon.render.walk/neighbours %))
            :seon.render.walk/neighbours
            node))

(defn- walk-agent
  [db agent-id caps]
  (walk/neighborhood
   {:seon.db/db db
    :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
    :seon.render/kind :seon.render/ai
    :seon.render/floor 'seon.render.block/data-prose
    :seon.sci.admit/caps caps
    :seon.render/distance 2}))

(defn- seed-agents!
  [connection cluster-name agent-ids]
  (support/seed-cluster! connection cluster-name)
  (d/transact
   connection
   (into []
         (mapcat (fn [agent-id]
                   (agent/creation-tx
                    {:seon.cluster.agent/id agent-id
                     :seon.cluster/name cluster-name
                     :seon.ns/name (symbol "my.agents" agent-id)})))
         agent-ids)))

(defn- elision?
  [node]
  (boolean
   (some #(= :seon.render.walk/elided
             (get-in % [:seon.error/value :seon.error/kind]))
         (nodes node))))

(defn- reverse-elision?
  [node]
  (boolean
   (some #(and (= :seon.render.walk/elided
                  (get-in % [:seon.error/value :seon.error/kind]))
               (re-find #"reverse :seon.cluster.agent/cluster"
                        (get-in % [:seon.error/value :seon.error/message] "")))
         (nodes node))))

(deftest p1-membership-is-complete-or-loudly-elided
  (let [registry (:seon.schema.projection/registry
                  (schema/current-projection))
        count-generator
        (mg/generator (m/schema [:int {:min 2 :max 10}]
                                {:registry registry}))
        property
        (prop/for-all
         [agent-count count-generator
          width (gen/choose 1 10)]
         (support/with-database
           (fn [connection]
             (let [agent-ids (mapv #(str "p1-" %) (range agent-count))
                   _ (seed-agents! connection "p1" agent-ids)
                   db @connection
                   caps (assoc base-caps
                               :seon.config.eval.result/max-collection width)
                   result (walk-agent db (first agent-ids) caps)
                   expected
                   (into #{}
                         (map (fn [agent-id]
                                (:db/id
                                 (d/pull db [:db/id]
                                         [:seon.cluster.agent/id agent-id]))))
                         agent-ids)
                   present (into #{} (keep :seon.render.walk/lookup)
                                 (nodes result))
                   missing (remove present expected)]
               (or (empty? missing) (elision? result))))))]
    (support/assert-check!
     (tc/quick-check 20 property :seed property-seed)
     "P1: every reachable agent must appear or be covered by an elision.")))

(deftest p5-shared-instruction-leaves-are-byte-identical
  (support/with-database
    (fn [connection]
      (seed-agents! connection "p5" ["p5-a" "p5-b"])
      (let [db @connection
            instruction-eids
            (into {}
                  (map (fn [instruction-id]
                         [instruction-id
                          (:db/id
                           (d/pull db [:db/id]
                                   [:seon.cluster.instruction/id
                                    instruction-id]))]))
                  [:reply-grammar :messaging :declining :global])
            leaves
            (fn [agent-id]
              (let [by-eid (into {} (map (juxt :seon.render.walk/lookup
                                               :seon.render/output))
                                  (nodes (walk-agent db agent-id base-caps)))]
                (into {} (map (fn [[instruction-id eid]]
                                [instruction-id (get by-eid eid)]))
                      instruction-eids)))
            property
            (prop/for-all
             [instruction-id (gen/elements (keys instruction-eids))]
             (= (get (leaves "p5-a") instruction-id)
                (get (leaves "p5-b") instruction-id)))]
        (support/assert-check!
         (tc/quick-check 100 property :seed (inc property-seed))
         "P5: shared instruction leaves must produce identical bytes.")))))

(deftest p6-every-active-cap-is-loud
  (support/with-database
    (fn [connection]
      (let [agent-ids (mapv #(str "p6-" %) (range 12))
            _ (seed-agents! connection "p6" agent-ids)
            db @connection
            property
            (prop/for-all
             [width (gen/choose 1 16)]
             (let [result (walk-agent
                           db (first agent-ids)
                           (assoc base-caps
                                  :seon.config.eval.result/max-collection
                                  width))]
               (= (< width (count agent-ids)) (reverse-elision? result))))]
        (support/assert-check!
         (tc/quick-check 100 property :seed (+ 2 property-seed))
         "P6: reverse collection truncation must emit an elision marker.")
        (testing "distance truncation is loud too"
          (let [result
                (walk/neighborhood
                 {:seon.db/db db
                  :seon.render.walk/lookup
                  [:seon.cluster.agent/id (first agent-ids)]
                  :seon.render/kind :seon.render/ai
                  :seon.render/floor 'seon.render.block/data-prose
                  :seon.sci.admit/caps base-caps
                  :seon.render/distance 0})]
            (is (elision? result))
            (is (re-find #"distance cap" (walk/prose db result)))))))))

(deftest reverse-reads-never-match-equal-non-ref-longs
  (support/with-database
    (fn [connection]
      (seed-agents! connection "longs" ["long-target"])
      (let [db-before @connection
            target (:db/id
                    (d/pull db-before [:db/id]
                            [:seon.cluster.agent/id "long-target"]))]
        (d/transact connection
                    [{:seon.cluster.run.form/id "same-long"
                      :seon.cluster.run.form/run
                      [:seon.cluster.run/id "long-run"]
                      :seon.cluster.run.form/ordinal target
                      :seon.cluster.run.form/source "(+ 1 1)"}
                     {:seon.cluster.run/id "long-run"
                      :seon.cluster.run/agent
                      [:seon.cluster.agent/id "long-target"]
                      :seon.cluster.run/opened-at (java.util.Date.)}])
        (let [form-eid (:db/id
                        (d/pull @connection [:db/id]
                                [:seon.cluster.run.form/id "same-long"]))
              targets (into #{} (keep :seon.render.walk/target)
                            (walk/refs @connection target base-caps))]
          (is (not (contains? targets form-eid))))))))

(deftest walk-threads-floor-provenance
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "floor")
      (let [result
            (walk/neighborhood
             {:seon.db/db @connection
              :seon.render.walk/lookup [:seon.cluster/name "floor"]
              :seon.render/kind :seon.render/ai
              :seon.render/floor 'seon.render.block/data-prose
              :seon.sci.admit/caps base-caps
              :seon.render/distance 0})]
        (is (true? (:seon.render/would-fall-to-floor? result)))))))

(deftest prose-labels-real-paths-and-orders-stable-before-churn
  (support/with-database
    (fn [connection]
      (let [node {:seon.render.walk/lookup [:example/id "root"]
                  :seon.render/distance 2
                  :seon.render.walk/changed-at 0
                  :seon.render/projection 'example/root
                  :seon.render/output "root-output"
                  :seon.render.walk/neighbours
                  [{:seon.render.walk/lookup [:example/id "branch-a"]
                    :seon.render/distance 1
                    :seon.render.walk/changed-at 7
                    :seon.render/projection 'example/a
                    :seon.render/output "branch-a"
                    :seon.render.walk/neighbours
                    [{:seon.render.walk/lookup [:example/id "branch-a-child"]
                      :seon.render/distance 0
                      :seon.render.walk/changed-at 7
                      :seon.render/projection 'example/a-child
                      :seon.render/output "branch-a-child"}]}
                   {:seon.render.walk/lookup [:example/id "branch-b"]
                    :seon.render/distance 1
                    :seon.render.walk/changed-at 7
                    :seon.render/projection 'example/b
                    :seon.render/output "branch-b"}
                   {:seon.render.walk/lookup [:example/id "churn"]
                    :seon.render/distance 1
                    :seon.render.walk/changed-at 9
                    :seon.render/projection 'example/churn
                    :seon.render/output "churn"}]}
            text (walk/prose @connection node)]
        (is (= 1 (count (re-seq #";; \(seon\.render/walk" text))))
        (is (re-find #"path=\[:seon\.render\.walk/neighbours 0\].*depth=1.*example/a"
                     text))
        (is (< (.indexOf text "branch-a")
               (.indexOf text "branch-a-child")
               (.indexOf text "branch-b")
               (.indexOf text "churn"))
            "equal changes cluster by branch and the newest unit is last")))))

(deftest ordinary-requires-refs-and-derived-message-edges-are-walked
  (support/with-database
    (fn [connection]
      (seed-agents! connection "derived" ["asker" "answerer"])
      (d/transact connection
                  [{:seon.ns/name 'derived.target}
                   {:seon.ns/name 'external.missing}])
      (d/transact connection
                  [{:seon.ns/name 'my.agents/asker
                    :seon.ns/requires
                    #{[:seon.ns/name 'derived.target]
                      [:seon.ns/name 'external.missing]}}
                   {:seon.cluster.message/id "derived-message"
                    :seon.cluster.message/from
                    [:seon.cluster.agent/id "asker"]
                    :seon.cluster.message/to
                    [:seon.cluster.agent/id "asker"]
                    :seon.cluster.message/content "please answer"
                    :seon.cluster.message/at (java.util.Date.)}])
      (d/transact connection
                  {:tx-data
                   [{:seon.cluster.run/id "derived-run"
                     :seon.cluster.run/agent
                     [:seon.cluster.agent/id "answerer"]
                     :seon.cluster.run/opened-at (java.util.Date.)}
                    {:seon.cluster.agent/id "answerer"
                     :seon.cluster.agent/run
                     [:seon.cluster.run/id "derived-run"]}]
                   :tx-meta
                   {:seon.db/trigger
                    [:seon.cluster.message/id "derived-message"]}})
      (let [db @connection
            asker (:db/id
                   (d/pull db [:db/id] [:seon.cluster.agent/id "asker"]))
            answerer (:db/id
                      (d/pull db [:db/id]
                              [:seon.cluster.agent/id "answerer"]))
            run-eid (:db/id
                     (d/pull db [:db/id]
                             [:seon.cluster.run/id "derived-run"]))
            message-eid (:db/id
                         (d/pull db [:db/id]
                                 [:seon.cluster.message/id
                                  "derived-message"]))
            target-ns (:db/id
                       (d/pull db [:db/id]
                               [:seon.ns/name 'derived.target]))
            external-ns (:db/id
                         (d/pull db [:db/id]
                                 [:seon.ns/name 'external.missing]))
            asker-refs (walk/refs db asker base-caps)
            answerer-refs (walk/refs db answerer base-caps)
            namespace-refs
            (walk/refs db
                       (:db/id
                        (d/pull db [:db/id]
                                [:seon.ns/name 'my.agents/asker]))
                       base-caps)
            agent-walk (walk-agent db "asker" base-caps)]
        (is (some #(and (= :seon.render.walk/asked-for-run
                           (:seon.render.walk/attribute %))
                        (= run-eid (:seon.render.walk/target %)))
                  asker-refs))
        (is (some #(and (= :seon.db/trigger
                           (:seon.render.walk/attribute %))
                        (= message-eid (:seon.render.walk/target %)))
                  answerer-refs))
        (is (some #(= target-ns (:seon.render.walk/target %)) namespace-refs))
        (is (some #(= external-ns (:seon.render.walk/target %)) namespace-refs))
        (is (some #(and (= :seon.ns/requires
                           (:seon.render.walk/attribute %))
                        (= target-ns (:seon.render.walk/lookup %)))
                  (nodes agent-walk))
            "the agent reaches a required namespace at distance two")
        (is (some #(and (= external-ns (:seon.render.walk/lookup %))
                        (= "external.missing" (:seon.render/output %)))
                  (nodes agent-walk))
            "a name-only external namespace renders its honest name")))))
