(ns seon.render.related-test
  "An entity's related data: its one hop both ways, over refs and value
  references derived from the schemas, each neighbour shown by its own
  render at distance 0, beside the entity's own render in both faces."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn]
            [seon.task :as task]
            [seon.render :as render]
            [seon.render.test :as render.test]
            [seon.render.walk :as walk]
            [seon.test-support :as support]))

(def ^:private handle (task/task-handle 'rd.a "related" :function))

(def ^:private source
  (str "(ns rd.a (:require [clojure.test :refer [deftest is]]))\n"
       "(defn c [v] v)\n"
       "(defn b {:seon/tasks #{'rd.a/related}} [v] (c v))\n"
       "(defn a [v] (b v))\n"
       "(deftest ^{:seon/tasks #{'rd.a/related}} proves (is (= 1 (a 1))))\n"))

(def ^:private task
  (str "[#:seon.task{:name \"related\" :kind :function"
       " :intent \"Prove related data.\" :must-read #{rd.a/c}}]"))

(defn- world!
  "Admit the fixture program and its task through their canonical writers."
  {:malli/schema [:=> [:cat :seon.db/connection] :seon.schema/value]}
  [connection]
  (let [root (io/file "tmp" "related-test" (str (random-uuid)))]
    (try (io/make-parents (io/file root "rd/a.clj"))
         (spit (io/file root "rd/a.clj") source)
         (support/transacted! connection (seon.fn/reconcile-tx @connection (seon.fn/program-entities {:seon.fn/roots [(.getPath root)]}) []))
         (let [indexed (task/index-tasks! {:seon.db/connection connection :seon.task/files {"resources/seon/tasks/rd.a.edn" task}})]
           (when-not (:seon.task/changed? indexed) (throw (ex-info "The fixture task did not index." {:seon.task/report indexed}))))
         (finally (support/delete-recursively! root)))))

(defn- unit
  "The world a REPL value is shown in: database, SCI context, caps and the agent profile."
  {:malli/schema [:=> [:cat :seon.db/connection :int] :map]}
  [connection max-children]
  {:seon.db/db @connection
   :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
   :seon.sci.admit/caps (config/result-caps config/defaults)
   :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
   :seon.render/profile (assoc (render/agent-render-profile config/defaults) :seon.render.profile/max-children max-children)})

(defn- names
  "The identity each shown item names, whatever its render."
  {:malli/schema [:=> [:cat [:maybe :map]] [:set :seon.schema/value]]}
  [group]
  (into #{} (map #(or (:seon.fn/sym %) (:seon.test/sym %) (:seon.ns/name %) (:seon.task/id %))) (:seon.related/items group)))

(deftest value-references-derive-from-the-schemas
  (support/with-database
   (fn [connection]
     (let [pairs (set (:seon.render.walk/value-attributes (walk/graph-structure (db/carried-projection @connection))))]
       (is (every? pairs [[:seon.fn/calls :seon.fn/sym] [:seon.task/must-read :seon.fn/sym] [:seon.test/subject :seon.fn/sym]
                          [:seon.task/tasks :seon.task/sym] [:seon.fn.arity/input-refs :seon.schema/key]
                          [:seon.schema/references :seon.schema/key] [:seon.instrument/fn :seon.fn/sym]
                          [:seon.fn/references :seon.fn/sym] [:seon.task/assigned :seon.task/id]])
           "every value attribute naming an identity is followed, with no hand list")))))

(deftest a-function-relates-its-callers-and-callees-by-their-own-render
  (support/with-database
   (fn [connection]
     (world! connection)
     (let [data (walk/related (assoc (unit connection 32) :seon.render.walk/lookup [:seon.fn/sym 'rd.a/b]))]
       (is (= [#{'rd.a/a} #{'rd.a/c}] [(names (:seon.fn/_calls data)) (names (:seon.fn/calls data))])
           "a function's callers and callees are its related data, each shown at distance zero")))))

(deftest a-task-relates-its-subjects-proofs-and-must-reads
  (support/with-database
   (fn [connection]
     (world! connection)
     (let [data (walk/related (assoc (unit connection 32) :seon.render.walk/lookup [:seon.task/id handle]))]
       (is (= [#{'rd.a/b 'rd.a/proves} #{'rd.a/c}] [(names (:seon.task/_tasks data)) (names (:seon.task/must-read data))])
           "the definitions naming a task and the functions it must read are found through value references")))))

(deftest a-group-past-the-width-names-its-count-and-the-next-call
  (support/with-database
   (fn [connection]
     (world! connection)
     (let [request (assoc (unit connection 1) :seon.render.walk/lookup [:seon.task/id handle])
           group (:seon.task/_tasks (walk/related request))
           [[_ next-request]] (:seon.render/operations group)
           rest-of (:seon.task/_tasks (walk/related (merge request next-request)))]
       (is (= 2 (:seon.related/count group)) "a group shows how many neighbours it reaches")
       (is (= #{'rd.a/b 'rd.a/proves} (into (names group) (names rest-of)))
           "the group's call shows the neighbours it did not")))))

(deftest both-faces-show-the-same-related-groups
  (support/with-database
   (fn [connection]
     (world! connection)
     (let [shown (unit connection 32)
           value (db/pull @connection '[*] [:seon.fn/sym 'rd.a/b])
           face (fn [output] (get-in (walk/with-related shown value output
                                                        (render/declared-projection shown value output))
                                     [:seon.print/node :seon.print/value]))
           ai (face :seon.render/ai)
           html (face :seon.render/html)
           summaries (into #{} (keep #(when (and (vector? %) (= :summary (first %))) (second (second %))))
                           (tree-seq vector? seq html))]
       (is (= (into #{} (map str) (keys (dissoc (:seon.related/data ai) :seon.related/skipped))) summaries)
           "the HTML face heads a section for each group the AI face returns")))))

(deftest a-distance-zero-test-render-is-its-name-and-state
  (support/with-database
   (fn [connection]
     (world! connection)
     (let [shown (render.test/render-ai {:seon.db/db @connection :seon.render/distance 0
                                         :seon.render/value {:seon.test/sym 'rd.a/proves}})]
       (is (= #{:seon.test/sym :seon.render.test/state} (set (keys shown)))
           "a test named beside another entity reads no host report")))))

(deftest breadth-first-selection-reads-group-counts-not-every-target
  (doseq [n [1 10 100]]
    (let [visited (atom 0)
          counts (map (fn [_] (swap! visited inc) 1000000) (apply list (range n)))
          shown (#'walk/shown-counts counts 10)]
      (is (= n @visited) "selection consumes one compact count per group")
      (is (= (if (= n 1) {0 10} (zipmap (range 10) (repeat 1))) shown)))))

(deftest breadth-first-selection-skips-empty-and-exhausted-groups
  (is (= {1 1, 2 3, 3 2} (#'walk/shown-counts [0 1 3 2] 100))))
