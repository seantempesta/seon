(ns seon.render.walk-test
  "Seeded properties for schema-derived neighbourhood membership and caps."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.render.transcript :as transcript]
            [seon.render.walk :as walk]
            [seon.test-support :as support]))

(def ^:private property-seed 2026073101)

(def ^:private base-caps
  {:seon.config.eval.result/max-depth 8
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(def ^:private audit-scalar-attributes
  [:audit/scalar-a :audit/scalar-b :audit/scalar-c])

(def ^:private audit-ref-attributes
  [:audit/points-at-a :audit/points-at-b])

(def ^:private audit-schema
  (into
   [{:db/ident :audit/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :audit/target-id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}]
   (concat
    (map (fn [attribute]
           {:db/ident attribute
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one})
         audit-scalar-attributes)
    (map (fn [attribute]
           {:db/ident attribute
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/one})
         audit-ref-attributes))))

(defn- nodes
  [node]
  (tree-seq #(seq (:seon.render.walk/neighbours %))
            :seon.render.walk/neighbours
            node))

(defn- walk-agent
  ([db agent-id caps]
   (walk-agent db agent-id caps 2))
  ([db agent-id caps distance]
  (walk/neighborhood
   {:seon.db/db db
    :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
    :seon.render/kind :seon.render/ai
    :seon.render/floor 'seon.render.block/data-prose
    :seon.sci.admit/caps caps
    :seon.render/distance distance})))

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
   (some #(= 'seon.render.walk/elision
             (:seon.render/projection %))
         (nodes node))))

(defn- reverse-elision?
  [node]
  (boolean
   (some #(and (= 'seon.render.walk/elision
                  (:seon.render/projection %))
               (re-find #"reverse :seon.cluster.agent/cluster"
                        (str (:seon.render/output %))))
         (nodes node))))

(deftest p1-out-of-family-attributes-reach-the-floor
  (let [attribute-generator
        (gen/vector-distinct (gen/elements audit-scalar-attributes)
                             {:min-elements 1
                              :max-elements (count audit-scalar-attributes)})
        property
        (prop/for-all
         [attributes attribute-generator]
         (support/with-database
           {:seon.test-support/extra-schema audit-schema}
           (fn [connection]
             (let [values (into {}
                                (map-indexed
                                 (fn [index attribute]
                                   [attribute (str (name attribute) "-" index)]))
                                attributes)
                   _ (d/transact connection
                                 [(assoc values :audit/id "outside-family")])
                   db @connection
                   result
                   (walk/neighborhood
                    {:seon.db/db db
                     :seon.render.walk/lookup
                     [:audit/id "outside-family"]
                     :seon.render/kind :seon.render/ai
                     :seon.render/floor 'seon.render.block/data-prose
                     :seon.sci.admit/caps base-caps
                     :seon.render/distance 0})
                   output (:seon.render/output result)]
               (and (string? output)
                    (every? (fn [[attribute value]]
                              (and (str/includes? output (pr-str attribute))
                                   (str/includes? output (pr-str value))))
                            values))))))]
    (support/assert-check!
     (tc/quick-check 20 property :seed property-seed)
     "P1: every out-of-family attribute must reach the render floor.")))

(deftest p2-out-of-family-inbound-refs-are-complete-or-named
  (let [property
        (prop/for-all
         [source-count (gen/choose 1 12)
          width (gen/choose 1 12)]
         (support/with-database
           {:seon.test-support/extra-schema audit-schema}
           (fn [connection]
             (d/transact
              connection
              (into [{:audit/target-id "target"}]
                    (map (fn [index]
                           {:audit/id (str "source-" index)
                            :audit/points-at-a [:audit/target-id "target"]
                            :audit/points-at-b [:audit/target-id "target"]}))
                    (range source-count)))
             (let [db @connection
                   target-eid (:db/id
                               (d/pull db [:db/id]
                                       [:audit/target-id "target"]))
                   expected (into #{}
                                  (map (fn [index]
                                         (:db/id
                                          (d/pull db [:db/id]
                                                  [:audit/id
                                                   (str "source-" index)]))))
                                  (range source-count))
                   connections
                   (walk/refs
                    db target-eid
                    (assoc base-caps
                           :seon.config.eval.result/max-collection width))]
               (every?
                (fn [attribute]
                  (let [matching (filter #(= attribute
                                             (:seon.render.walk/attribute %))
                                         connections)
                        present (into #{}
                                      (keep :seon.render.walk/target)
                                      matching)
                        missing (set/difference expected present)
                        markers (keep :seon.error/value matching)]
                    (and (set/subset? present expected)
                         (= (min width source-count) (count present))
                         (= (if (seq missing) 1 0) (count markers))
                         (= (count missing)
                            (or (get-in (first markers)
                                        [:seon.error/data
                                         :seon.render.walk/elided-count])
                                0))
                         (every? #(= attribute
                                     (get-in % [:seon.error/data
                                                :seon.render.walk/attribute]))
                                 markers))))
                audit-ref-attributes)))))]
    (support/assert-check!
     (tc/quick-check 20 property :seed (inc property-seed))
     (str "P2: every out-of-family inbound ref must render or be "
          "accounted for by its attribute's marker."))))

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
        (testing "distance truncation is one compact actionable line"
          (let [result
                (walk/neighborhood
                 {:seon.db/db db
                  :seon.render.walk/lookup
                  [:seon.cluster.agent/id (first agent-ids)]
                  :seon.render/kind :seon.render/ai
                  :seon.render/floor 'seon.render.block/data-prose
                  :seon.sci.admit/caps base-caps
                  :seon.render/distance 0})
                prose (walk/prose db result)]
            (is (elision? result))
            (is (= 1 (count (re-seq #"branches elided" prose))))
            (is (re-find #"branches elided · \d+ tokens" prose))
            (is (str/includes?
                 prose
                 (str "(seon.render/walk "
                      (pr-str {:root [:seon.cluster.agent/id
                                      (first agent-ids)]
                               :depth 1})
                      ")")))
            (is (not (str/includes? prose
                                    "elided connections at the requested")))))
        (testing "the html frontier is a quiet affordance, not an error"
          (let [result
                (walk/neighborhood
                 {:seon.db/db db
                  :seon.render.walk/lookup
                  [:seon.cluster.agent/id (first agent-ids)]
                  :seon.render/kind :seon.render/html
                  :seon.render/floor 'seon.render.block/data-html
                  :seon.sci.admit/caps base-caps
                  :seon.render/distance 0})
                marker (some #(when (= 'seon.render.walk/elision
                                       (:seon.render/projection %))
                                %)
                             (nodes result))]
            (is (nil? (:seon.error/value marker)))
            (is (= "seon-walk-elision"
                   (get-in marker [:seon.render/output 1 :class])))))))))

(deftest asked-for-run-edges-use-the-collection-cap
  (support/with-database
    (fn [connection]
      (seed-agents! connection "asked-cap" ["asker" "answerer"])
      (doseq [index (range 5)]
        (let [message-id (str "asked-message-" index)]
          (d/transact connection
                      [{:seon.cluster.message/id message-id
                        :seon.cluster.message/from
                        [:seon.cluster.agent/id "asker"]
                        :seon.cluster.message/to
                        [:seon.cluster.agent/id "answerer"]
                        :seon.cluster.message/content (str "request " index)
                        :seon.cluster.message/at
                        (java.util.Date. (long index))}])
          (d/transact connection
                      {:tx-data
                       [{:seon.cluster.run/id (str "asked-run-" index)
                         :seon.cluster.run/agent
                         [:seon.cluster.agent/id "answerer"]
                         :seon.cluster.run/opened-at
                         (java.util.Date. (long index))}]
                       :tx-meta
                       {:seon.db/trigger
                        [:seon.cluster.message/id message-id]}})))
      (let [db @connection
            asker-eid (:db/id
                        (d/pull db [:db/id]
                                [:seon.cluster.agent/id "asker"]))
            matching
            (filter #(= :seon.render.walk/asked-for-run
                        (:seon.render.walk/attribute %))
                    (walk/refs
                     db asker-eid
                     (assoc base-caps
                            :seon.config.eval.result/max-collection 2)))
            targets (keep :seon.render.walk/target matching)
            markers (keep :seon.error/value matching)]
        (is (= 2 (count targets)))
        (is (= 1 (count markers)))
        (is (= 3 (get-in (first markers)
                         [:seon.error/data
                          :seon.render.walk/elided-count])))
        (is (= :seon.render.walk/asked-for-run
               (get-in (first markers)
                       [:seon.error/data
                        :seon.render.walk/attribute])))))))

(deftest transcript-node-receives-the-walk-caps
  (support/with-database
    (fn [connection]
      (seed-agents! connection "transcript-caps" ["caps-agent"])
      (let [observed (atom nil)]
        (with-redefs [transcript/render-ai
                      (fn [unit]
                        (reset! observed (:seon.sci.admit/caps unit))
                        "transcript")]
          (walk-agent @connection "caps-agent" base-caps 1))
        (is (= base-caps @observed))))))

(deftest reverse-reads-never-match-equal-non-ref-longs
  (support/with-database
    (fn [connection]
      (seed-agents! connection "longs" ["long-target"])
      (let [db-before @connection
            target (:db/id
                    (d/pull db-before [:db/id]
                            [:seon.cluster.agent/id "long-target"]))]
        (d/transact connection
                    [{:seon.cluster.run/id "long-run"
                      :seon.cluster.run/agent
                      [:seon.cluster.agent/id "long-target"]
                      :seon.cluster.run/opened-at (java.util.Date.)}
                     {:seon.cluster.run.form/id "same-long"
                      :seon.cluster.run.form/run
                      [:seon.cluster.run/id "long-run"]
                      :seon.cluster.run.form/ordinal target
                      :seon.cluster.run.form/source "(+ 1 1)"}])
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

(deftest prose-orders-one-logical-unit-with-compact-actionable-labels
  (support/with-database
    (fn [connection]
      (let [node {:seon.render.walk/lookup [:example/id "root"]
                  :seon.render/distance 2
                  :seon.render.walk/changed-at 99
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
                    :seon.render/output "churn"}
                   {:seon.render.walk/lookup [:example/id "branch-b"]
                    :seon.render/distance 1
                    :seon.render.walk/changed-at 10
                    :seon.render/projection 'example/duplicate
                    :seon.render/output "duplicate-branch-b"}
                   {:seon.render.walk/lookup [:example/id "same-bytes"]
                    :seon.render/distance 1
                    :seon.render.walk/changed-at 10
                    :seon.render/projection 'example/same-bytes
                    :seon.render/output "branch-b"}
                   {:seon.render.walk/lookup [:example/id "seen"]
                    :seon.render/distance 1
                    :seon.render.walk/changed-at 11
                    :seon.render.walk/back-reference? true}
                   {:seon.render.walk/lookup
                    [:seon.render.walk/transcript "root"]
                    :seon.render/distance 1
                    :seon.render.walk/changed-at 1
                    :seon.render/projection 'seon.render.transcript/render-ai
                    :seon.render/output "transcript-tail"}]}
            flattened (walk/units node)
            text (walk/prose @connection node)]
        (is (= flattened (walk/units node))
            "flattening is pure over the rendered node value")
        (is (= [[]
                [:seon.render.walk/neighbours 0]
                [:seon.render.walk/neighbours 0
                 :seon.render.walk/neighbours 0]
                [:seon.render.walk/neighbours 1]
                [:seon.render.walk/neighbours 2]
                [:seon.render.walk/neighbours 4]
                [:seon.render.walk/neighbours 6]]
               (mapv :seon.render.walk/path flattened))
            "root and transcript are stable rails around ordered branches")
        (is (= [99 7 7 7 9 10 1]
               (mapv :seon.render.walk/changed-at flattened))
            "changed-at is lifted onto every unit")
        (is (= 1 (count (re-seq #";; \(seon\.render/walk" text))))
        (is (re-find #"d1 · example/a · :branch \[:seon\.render\.walk/neighbours 0\]"
                     text))
        (is (not (str/includes? text "duplicate-branch-b"))
            "one logical lookup is rendered only once")
        (is (= 2 (count (re-seq #"(?m)^branch-b$" text)))
            "distinct facts survive even when their projection bytes match")
        (is (< (.indexOf text "root-output")
               (.indexOf text "branch-a")
               (.indexOf text "branch-a-child")
               (.indexOf text "branch-b")
               (.indexOf text "churn")
               (.indexOf text "transcript-tail"))
            "own state is first, branches stay grouped, transcript is last")))))

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
                        (.startsWith ^String (:seon.render/output %)
                                     "(ns external.missing"))
                  (nodes agent-walk))
            "a name-only external namespace renders its compact card")))))

(deftest namespace-detail-is-root-relative-and-members-stay-inside-the-card
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "namespace-distance")
      (d/transact
       connection
       (agent/creation-tx
        {:seon.cluster.agent/id "seon-flow-owner"
         :seon.cluster/name "namespace-distance"
         :seon.ns/name 'seon.flow}))
      (let [db @connection
            own-namespace-eid
            (:db/id (d/pull db [:db/id] [:seon.ns/name 'seon.flow]))
            required-namespace-eids
            (d/q '[:find [?required ...]
                   :in $ ?namespace
                   :where [?namespace :seon.ns/requires ?required]]
                 db own-namespace-eid)
            toolkit-namespace-eids
            (d/q '[:find [?toolkit ...]
                   :in $ ?cluster-name
                   :where
                   [?cluster :seon.cluster/name ?cluster-name]
                   [?cluster :seon.cluster/toolkit ?toolkit]]
                 db "namespace-distance")
            member-eids
            (into
             (set
              (d/q '[:find [?function ...]
                     :in $ ?namespace
                     :where [?function :seon.fn/ns ?namespace]]
                   db own-namespace-eid))
             (map :db/id)
             (mapcat val
                     (select-keys
                      (d/pull db
                              [{:seon.ns/aliases [:db/id]}
                               {:seon.ns/imports [:db/id]}
                               {:seon.ns/refers [:db/id]}]
                              own-namespace-eid)
                      [:seon.ns/aliases :seon.ns/imports :seon.ns/refers])))
            at-distance
            (fn [distance]
              (nodes (walk-agent db "seon-flow-owner" base-caps distance)))
            d1-nodes (at-distance 1)
            d2-nodes (at-distance 2)
            namespace-nodes
            (filter #(= 'seon.render.ns/render-ai
                        (:seon.render/projection %))
                    d2-nodes)
            own-node
            (some #(when (= own-namespace-eid
                            (:seon.render.walk/lookup %))
                     %)
                  namespace-nodes)
            reached-eids (into #{} (keep :seon.render.walk/lookup) d2-nodes)]
        (testing "the root agent's own namespace is always the full tier"
          (doseq [walk-nodes [d1-nodes d2-nodes]]
            (let [node (some #(when (= own-namespace-eid
                                      (:seon.render.walk/lookup %))
                               %)
                             walk-nodes)]
              (is (= 1 (:seon.render/distance node)))
              (is (.startsWith ^String (:seon.render/output node)
                               "(ns seon.flow")))))
        (testing "every other reached namespace is a compact card"
          (is (seq required-namespace-eids))
          (is (seq toolkit-namespace-eids))
          (doseq [namespace-eid (concat required-namespace-eids
                                        toolkit-namespace-eids)]
            (is (some #(and (= namespace-eid
                               (:seon.render.walk/lookup %))
                            (= 2 (:seon.render/distance %)))
                      namespace-nodes)))
          (is (every? #(= 2 (:seon.render/distance %))
                      (remove #(= own-namespace-eid
                                  (:seon.render.walk/lookup %))
                              namespace-nodes))))
        (testing "namespace traversal preserves requires and absorbs members"
          (is (seq (filter #(= :seon.ns/requires
                              (:seon.render.walk/attribute %))
                           (:seon.render.walk/neighbours own-node))))
          (is (empty? (set/intersection member-eids reached-eids)))
          (is (not (re-find #":seon\\.(?:fn/sym|ns\\.(?:alias|import|refer)/local)"
                            (walk/prose db (walk-agent db "seon-flow-owner"
                                                       base-caps 2))))))))))
