(ns seon.schema-test
  "Schemas are part of the program (task seon.source/schemas-are-part-of-the-program, owner
  2026-10-21): an agent changes a schema at its REPL with my.schema/register! (v1's
  seon.schema/register!), validated against the whole population, refused as data, written
  on its branch, and seen at once by the code it evaluates next."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.agent :as agent]
            [seon.db :as pdb]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.source :as source]))

(def ^:private here (System/getProperty "user.dir"))

(defn- schema-files []
  (into {} (for [f (.listFiles (java.io.File. (str here "/resources/seon/schemas")))
                 :when (str/ends-with? (.getName f) ".edn")]
             [(str "resources/seon/schemas/" (.getName f)) (slurp f)])))

(defn- main-db
  "A test branch with every schema resource imported, as a root's main holds them."
  []
  (let [db @(second (pdb/test-branch!))]
    (d/db-with db (source/import-tx db (schema-files)))))

(defn- evals
  "The printed value of each form of code, evaluated through the agent's REPL handler in this
  process on a branch holding db (a test-mode agent: its writes apply to its db only), and
  the branch's db after."
  [db code]
  (let [answers (volatile! [])
        old @agent/state]
    (reset! agent/state {:n 0 :ns (the-ns 'user) :mode :test :db db})
    (try ((repl/handler) {"op" "eval" "code" code :reply #(vswap! answers conj %)})
         [(mapv :printed (repl/fold @answers identity)) (:db @agent/state)]
         (finally (reset! agent/state old)))))

(def ^:private refused-status
  "[:and {:seon.db/index true} [:enum :running :ended :killed :dead :lost :done :merged :behind :bailed :closed :refused]]")

(deftest register-validates-against-the-population
  (let [[printed db] (evals (main-db)
                            (str "(seon.contract/valid? :seon.attempt/status :refused)\n"
                                 "(:seon.schema/key (my.schema/register! :seon.attempt/status " refused-status "))\n"
                                 "(seon.contract/valid? :seon.attempt/status :refused)\n"
                                 "(defn status-of {:malli/schema [:=> [:cat :seon.attempt/status] :keyword]} [s] s)\n"
                                 "(status-of :refused)\n"
                                 "(seon.contract/valid? :seon.attempt/attempt-fact {:seon.attempt/id \"a#1\" :seon.attempt/task 1 :seon.attempt/status :refused :seon.attempt/boot \"b\" :seon.attempt/agent :a :seon.attempt/start-ms 0})\n"
                                 "(:seon.error/type (my.schema/register! :x.schema-test/thing [:map [:x.schema-test/part :x.schema-test/nowhere]]))\n"
                                 "(:seon.schema/key (my.schema/register! :seon.attempt/refusal :string))\n"
                                 "(:seon.schema/key (my.schema/register! :user/qty :int))\n"
                                 "(seon.contract/valid? :user/qty 3)\n"))
        entity (fn [k] (d/q '[:find (pull ?e [:schema/form :schema/file :schema/source]) . :in $ ?k :where [?e :schema/key ?k]] db k))]
    (is (= ["false" ":seon.attempt/status" "true"] (subvec printed 0 3))
        "before: the binary's schema refuses :refused; register! answers its registration; after: the agent's registry accepts it")
    (is (= ":refused" (nth printed 4)) "a function the agent defines next is checked against the registered schema")
    (is (= "true" (nth printed 5)) "and so is a schema that names it: the population is recompiled, not the one key")
    (is (= ":seon.schema/registration-refused" (nth printed 6)) "a form naming a schema that does not exist is refused as data")
    (testing "the registration is program on the agent's branch: no longer the binary's, in the resource it came from"
      (is (= {:schema/form refused-status :schema/file "resources/seon/schemas/seon.facts.edn"} (entity :seon.attempt/status))))
    (testing "a new key goes to the resource holding most keys of its namespace (owner, 2026-10-21)"
      (is (= {:schema/form ":string" :schema/file "resources/seon/schemas/seon.facts.edn"} (entity :seon.attempt/refusal))))
    (testing "a key in a scratch namespace is seen at the REPL but is no program: it has no resource"
      (is (= ":user/qty" (nth printed 8)))
      (is (= "true" (nth printed 9)))
      (is (= {:schema/form ":int"} (entity :user/qty))))
    (is (nil? (d/q '[:find ?e . :where [?e :schema/key :x.schema-test/thing]] db)) "nothing of a refused registration is written")
    (testing "the branch's population, as the next eval reads it"
      (is (= #{:seon.attempt/status :seon.attempt/refusal :user/qty}
             (set (keys (:put (schema/changes db)))))))))

(deftest unregister-removes-a-key-nothing-names
  (let [[printed db] (evals (main-db)
                            (str "(:seon.error/type (my.schema/unregister! :seon.attempt/status))\n"
                                 "(my.schema/unregister! :seon.attempt/pulled)\n"))]
    (is (= ":seon.schema/registration-refused" (first printed)) "a key other schemas name stays: refused as data")
    (is (= ":seon.attempt/pulled" (second printed)))
    (is (nil? (d/q '[:find ?e . :where [?e :schema/key :seon.attempt/pulled]] db)) "its entry is retracted on the branch")
    (is (= #{:seon.attempt/pulled} (:removed (schema/changes db))))))
