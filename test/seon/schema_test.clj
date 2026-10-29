(ns seon.schema-test
  "Schemas are part of the program (task seon.source/schemas-are-part-of-the-program, owner
  2026-10-21): an agent changes a schema at its REPL with my.schema/register! (v1's
  seon.schema/register!), validated against the whole population, refused as data, written
  on its branch, and seen at once by the code it evaluates next."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.agent :as agent]
            [seon.program :as program]
            [seon.program-test :as pt]
            [seon.db :as pdb]
            [seon.repl :as repl]
            [my.schema :as schema]
            [seon.source :as source]))

(def ^:private here (System/getProperty "user.dir"))

(defn- schema-files []
  (into {} (for [f (.listFiles (java.io.File. (str here "/resources/seon/schemas")))
                 :when (str/ends-with? (.getName f) ".edn")]
             [(str "resources/seon/schemas/" (.getName f)) (slurp f)])))

(defn- main-db
  "A test branch with every schema resource imported, as a root's main holds them."
  []
  (let [db (ts/test-db)]
    (d/db-with db (source/import-tx db (schema-files)))))

(defn- evals
  "The printed value of each line of code, each its own request through the agent's REPL in
  this process on a branch holding db (a test-mode agent: its writes apply to its db only; a
  request stops at its first error, so each line is one), and the branch's db after."
  [db code]
  (let [old @agent/state]
    (reset! agent/state {:n 0 :ns (the-ns 'user) :mode :test :db db})
    (try [(mapv #(let [r (repl/eval-string % {})] (if (:ex r) (pr-str (:ex r)) (pr-str (:value r))))
                (clojure.string/split-lines code))
          (:db @agent/state)]
         (finally (reset! agent/state old)))))

(def ^:private paused-status
  "[:and {:seon.db/index true} [:enum :running :ended :killed :dead :lost :done :merged :behind :closed :refused :paused]]")

(deftest register-validates-against-the-population
  (let [[printed db] (evals (main-db)
                            (str "(seon.contract/valid? :seon.run/status :paused)\n"
                                 "(:seon.schema/key (my.schema/register! :seon.run/status " paused-status "))\n"
                                 "(seon.contract/valid? :seon.run/status :paused)\n"
                                 "(defn status-of {:malli/schema [:=> [:cat :seon.run/status] :keyword]} [s] s)\n"
                                 "(status-of :paused)\n"
                                 "(seon.contract/valid? :seon.run/run-fact {:seon.run/id \"a#1\" :seon.run/task 1 :seon.run/status :paused :seon.run/boot \"b\" :seon.run/agent 2 :seon.run/start-ms 0})\n"
                                 "(:seon.error/type (my.schema/register! :x.schema-test/thing [:map [:x.schema-test/part :x.schema-test/nowhere]]))\n"
                                 "(:seon.schema/key (my.schema/register! :seon.run/invalid :string))\n"
                                 "(:seon.schema/key (my.schema/register! :user/qty :int))\n"
                                 "(seon.contract/valid? :user/qty 3)\n"))
        entity (fn [k] (d/q '[:find (pull ?e [:schema/form :schema/file :schema/source]) . :in $ ?k :where [?e :schema/key ?k]] db k))]
    (is (= ["false" ":seon.run/status" "true"] (subvec printed 0 3))
        "before: the binary's schema refuses :paused; register! answers its registration; after: the agent's registry accepts it")
    (is (= ":paused" (nth printed 4)) "a function the agent defines next is checked against the registered schema")
    (is (= "true" (nth printed 5)) "and so is a schema that names it: the population is recompiled, not the one key")
    (is (= ":seon.schema/registration-refused" (nth printed 6)) "a form naming a schema that does not exist is refused as data")
    (testing "the registration is program on the agent's branch: no longer the binary's, in the resource it came from"
      (is (= {:schema/form paused-status :schema/file "resources/seon/schemas/seon.facts.edn"} (entity :seon.run/status))))
    (testing "a new key goes to the resource holding most keys of its namespace (owner, 2026-10-21)"
      (is (= {:schema/form ":string" :schema/file "resources/seon/schemas/seon.facts.edn"} (entity :seon.run/invalid))))
    (testing "a key in a scratch namespace is seen at the REPL but is no program: it has no resource"
      (is (= ":user/qty" (nth printed 8)))
      (is (= "true" (nth printed 9)))
      (is (= {:schema/form ":int"} (entity :user/qty))))
    (is (nil? (d/q '[:find ?e . :where [?e :schema/key :x.schema-test/thing]] db)) "nothing of a refused registration is written")
    (testing "the branch's population, as the next eval reads it"
      (is (= #{:seon.run/status :seon.run/invalid :user/qty}
             (set (keys (program/schema-changes db))))))))

(deftest a-registration-counts-the-stored-values-it-refuses
  ;; task my.schema/a-registration-counts-the-stored-values-it-refuses (v1's
  ;; seon.db/registration-check, ported later by the owner's ruling of 2026-10-21): a
  ;; registration that narrows a stored attribute answers how many stored values its new
  ;; form refuses, so the agent sees what its change would break before it merges
  (let [db (d/db-with (main-db) [{:seon.run/id "a#1" :seon.run/status :merged}
                                 {:seon.run/id "a#2" :seon.run/status :running}])
        r (schema/registration db :seon.run/status [:and {:seon.db/index true} [:enum :running :ended]] false)]
    (is (= {:checked 2 :refused 1} (:seon.schema.usage/check (:registration r))))))

(deftest a-loaded-form-compiles-against-its-dbs-schemas
  ;; found by the live demonstration (2026-10-21): the merge agent loaded the work branch's
  ;; (defn status-of {:malli/schema [:=> [:cat :seon.run/status] ...]}) with the built
  ;; registry, so its proof erred on :refused. A process loading a db's forms (an agent's
  ;; merge of main, root and the fork server after a merge) compiles them against that db's
  ;; schemas
  (let [main (main-db)
        branch (d/db-with main (:tx (schema/registration main :seon.run/status (read-string paused-status) false)))
        branch (pt/record branch 'x.schema-load "(defn status-of {:malli/schema [:=> [:cat :seon.run/status] :keyword]} [s] s)")]
    (remove-ns 'x.schema-load)
    (is (= [] (program/load-diff! main branch)))
    (is (= :paused ((resolve 'x.schema-load/status-of) :paused)) "its contract accepts the branch's :paused")))

(deftest a-merged-schema-change-re-arms-the-contracts-naming-it
  ;; BUILD plan P4: main's change to a schema reaches the code already loaded where main's
  ;; program runs (root, the fork server): each contracted var whose contract names it, or a
  ;; schema naming it, is loaded again against the changed population
  (let [main (pt/record (main-db) 'x.schema-arm "(defn status-of {:malli/schema [:=> [:cat :seon.run/status] :keyword]} [s] s)")
        status-of (fn [s] ((resolve 'x.schema-arm/status-of) s))
        _ (remove-ns 'x.schema-arm)
        _ (is (= [] (:errors (program/load-all! main))) "main's program loaded, its contracts armed")
        _ (is (thrown? Exception (status-of :paused)) "the built schema refuses :paused")
        rep (d/with main (:tx (schema/registration main :seon.run/status (read-string paused-status) false)))]
    (is (= [] (program/load-changes! (:db-after rep) (pdb/report->tx rep))))
    (is (= :paused (status-of :paused)) "re-armed against main's :seon.run/status")))

(deftest an-unquoted-predicate-is-refused-naming-it
  ;; 2026-10-29: the md-scan bench's run registered [:vector [:tuple nat-int? nat-int?]] unquoted,
  ;; was refused :unreadable, and spent its last turns finding which part did not read back
  (let [r (schema/registration (main-db) :x.schema-test/pairs [:vector [:tuple nat-int? 'nat-int?]] false)]
    (is (= :unreadable (:seon.schema/refused-because r)))
    (is (str/includes? (:seon.error/message r) "at [1 1]") "where the value that is no EDN is")
    (is (str/includes? (:seon.error/message r) "quote the form") "and how to write it as data")))
