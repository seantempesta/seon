(ns seon.render.walk-graph-test
  "The linked graph walk (task-context S1): levels from a task root over refs
  and value references derived from the schemas, presence by render pair, definitions-first order,
  a cycle rendered once, the node cut and a missing root."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn]
            [seon.task :as task]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(def ^:private handle (task/task-handle 'tg.a "walk-graph"))

(def ^:private source
  (str "(ns tg.a {:seon/tasks #{'tg.a/walk-graph}} (:require [clojure.test :refer [deftest is]]))\n"
       "(defn c [v] v)\n"
       "(defn b [v] (c v))\n"
       "(defn a {:seon/tasks #{'tg.a/walk-graph}} [v] (b v))\n"
       "(declare q)\n"
       "(defn p {:seon/tasks #{'tg.a/walk-graph}} [v] (if (pos? v) (q (dec v)) v))\n"
       "(defn q [v] (p v))\n"
       "(deftest ^{:seon/tasks #{'tg.a/walk-graph}} proves (is (= 1 (a 1))))\n"))

(def ^:private task
  (str "[#:seon.task{:name \"walk-graph\""
       " :intent \"Prove the graph walk through `tg.a/c`.\"}]"))

(defn- world!
  "Admit the fixture program and its task through their canonical writers."
  {:malli/schema [:=> [:cat :seon.db/connection] :seon.schema/value]}
  [connection]
  (let [root (io/file "tmp" "walk-graph-test" (str (random-uuid)))]
    (try (io/make-parents (io/file root "tg/a.clj"))
         (spit (io/file root "tg/a.clj") source)
         (support/transacted! connection (seon.fn/reconcile-tx @connection (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]}) []))
         (let [indexed (task/index-tasks! {:seon.db/connection connection :seon.task/files {"resources/seon/tasks/tg.a.edn" task}})]
           (when-not (:seon.task/changed? indexed) (throw (ex-info "The fixture task did not index." {:seon.task/report indexed}))))
         (finally (support/delete-recursively! root)))))

(defn- walked
  {:malli/schema [:=> [:cat :seon.db/database-value :int [:* :map]] :seon.schema/value]}
  [database n & [caps]]
  (walk/graph {:seon.db/db database :seon.render.walk/lookup [:seon.task/id handle] :seon.render/distance n
               :seon.sci.admit/caps (merge (config/result-caps config/defaults) caps)}))

(defn- lookup-of
  {:malli/schema [:=> [:cat :map :int] :seon.schema/value]}
  [graph eid]
  (let [value (get-in graph [:seon.render.walk/nodes eid :seon.render/value])]
    (or (:seon.fn/sym value) (:seon.test/sym value) (:seon.ns/name value) (:seon.task/id value))))

(deftest the-task-graph-walks-levels-in-definition-order
  (support/with-database
   (fn [connection]
     (world! connection)
     (let [database @connection
           valid? (schema/projection-validator (db/carried-projection database) :seon.render.walk/graph)
           graphs (mapv #(walked database %) (range 4))
           order (fn [graph] (mapv #(lookup-of graph %) (:seon.render.walk/graph-order graph)))
           levels (fn [graph] (into {} (map (fn [[e node]] [(lookup-of graph e) (:seon.render.walk/node-level node)]))
                                    (:seon.render.walk/nodes graph)))]
       (is (every? valid? graphs) "every graph is its declared value")
       (testing "N=0 is the root alone, always included though a task declares no pair"
         (is (= [handle] (order (graphs 0)))))
       (testing "level 1 holds the subjects, the proof and the reference its intent cites, reached through value edges both ways"
         (is (= {handle 0 'tg.a 1 'tg.a/a 1 'tg.a/p 1 'tg.a/proves 1 'tg.a/c 1} (levels (graphs 1)))))
       (testing "level 2 reaches callees; every definition precedes its user"
         (let [g (graphs 3) position (zipmap (order g) (range))]
           (is (= 2 (get (levels g) 'tg.a/b)))
           (is (< (position 'tg.a/c) (position 'tg.a/b) (position 'tg.a/a)))
           ;; By entity: tg.a's two refer bindings (deftest, is) are nodes that `lookup-of` names nil.
           (is (apply distinct? (:seon.render.walk/graph-order g)) "each node renders once")
           (testing "the p/q cycle renders once with one back-reference"
             (is (= 1 (count (filter (fn [[holder target]] (= #{'tg.a/p 'tg.a/q} #{(lookup-of g holder) (lookup-of g target)}))
                                    (:seon.render.walk/back-references g))))))
           (testing "targets without a pair are skipped and counted, never nodes"
             (is (seq (:seon.render.walk/skipped g)))
             (is (not-any? #{:seon.fn.file/file} (map :seon.render.walk/node-schema (vals (:seon.render.walk/nodes g))))))))
       (testing "a max-nodes query-work refusal stops the level and names it; the root survives"
         (let [g (walked database 3 {:seon.config.eval.result/max-nodes 4})
               [cut] (:seon.render.walk/cuts g)]
           (is (valid? g))
           (is (= [handle] (order g)))
           (is (= [:seon.db/invalid-read 0] [(:seon.render.walk/cut-bound cut) (:seon.render.walk/node-level cut)]))
           (is (:seon.db/invalid-read (:seon.error/value cut)) "the refusal travels whole")))
       (testing "max-collection keeps the newest targets per source and attribute and cuts the rest"
         (let [g (walked database 1 {:seon.config.eval.result/max-collection 1})
               cut (first (filter #(= :seon.task/_tasks (:seon.render.walk/attribute %)) (:seon.render.walk/cuts g)))]
           (is (valid? g))
           (is (= [:seon.config.eval.result/max-collection 4] [(:seon.render.walk/cut-bound cut) (:seon.render.walk/cut-count cut)]))))
       (testing "a missing root is the declared refusal naming its lookup"
         (is (= [:seon.task/id "000000000000"]
                (:seon.render.walk/missing-lookup
                 (walk/graph {:seon.db/db database :seon.render.walk/lookup [:seon.task/id "000000000000"]
                              :seon.sci.admit/caps (config/result-caps config/defaults)})))))))))
