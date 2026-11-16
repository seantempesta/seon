(ns seon.schema-test
  "Schemas are part of the program (task seon.source/schemas-are-part-of-the-program, owner
  2026-10-21): an agent changes a schema at its REPL with my.schema/register! (v1's
  seon.schema/register!), validated against the whole population, refused as data, written
  on its branch, and seen at once by the code it evaluates next."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.program :as program]
            [seon.program-test :as pt]
            [seon.db :as pdb]
            [my.schema :as schema]
            [seon.edn :as sedn]
            [seon.contract :as contract]))

(def ^:private facts-file "resources/seon/schemas/seon.facts.edn")

(defn- main-db
  "A test branch holding two schema entries as main imports them from facts-file (the binary's own forms): not the tree's ~900,
  whose import cost ~42M ticks. See docs/source.md#schemas-at-the-repl."
  []
  (d/db-with (ts/test-db)
             (vec (for [k [:seon.task/outcome :seon.agent/key]]
                    {:schema/key k :schema/form (binding [*print-namespace-maps* false] (sedn/encode (get contract/forms k))) :schema/file facts-file}))))

(def ^:private paused-status
  "[:and {:seon.db/index true} [:enum :running :parked :told :cap :done :ready :gate :merged :reprove :dead :lost :killed :refused :closed :paused]]")

(defn- registry-of
  "The registry an evaluation on db binds (seon.program/schema-registry over db's own schema entries)."
  [db] (program/schema-registry (program/schema-changes db)))

(defn- applied
  "db with the registration of form v as schema k applied, what register! writes on the agent's branch before it re-arms."
  [db k v] (d/db-with db (:tx (schema/registration db k v false))))

(def ^:private status-of-src
  "(defn status-of {:malli/schema [:=> [:cat :seon.task/outcome] :keyword]} [s] s)")

(defn- entered
  "db with schema entry k holding form v, as a registration writes it (the key and its form alone: what the program reads of a schema)."
  [db k v] (d/db-with db [{:schema/key k :schema/form (pr-str v)}]))

;; register! is `registration` (judged against the whole population, refused as data) then its tx written and the code the process holds
;; re-armed (seon.program/rearm!), the REPL binding the branch's registry (seon.program/schema-registry). Each part is read on the value,
;; one test a registration (~5.6M ticks: it compiles the ~1,300 forms of the population), never through an agent's REPL, whose state swap
;; builds its whole ctx (~200M ticks a request, seon.ctx's on-state, filed to the context owner).

(deftest ^{:seon.test/estimate {:ticks 8000000}} register-validates-against-the-population
  ;; docs/source.md#schemas-at-the-repl. Estimate: a test db 0.3M + a registration 5.6M + a registry compile 0.3M + a contract armed 1M
  (let [db0 (main-db)
        db (applied db0 :seon.task/outcome (read-string paused-status))
        task {:seon.task/id "a/b" :seon.task/ns 'a :seon.task/name "b" :seon.task/plan "p" :seon.task/execution :running
              :seon.task/branch :b :seon.task/proofs #{} :seon.task/needs #{} :seon.task/kind :work :seon.task/outcome :paused}]
    (is (false? (contract/valid? :seon.task/outcome :paused)) "before: the binary's schema refuses :paused")
    (binding [malli.registry/*registry* (registry-of db)]
      (is (true? (contract/valid? :seon.task/outcome :paused)) "after: the registry the branch binds accepts it")
      (is (true? (contract/valid? :seon.task/own task)) "and so does a schema that names it: the population is recompiled, not the one key")
      (try (binding [*ns* (create-ns 'x.status)]
             (load-string "(clojure.core/refer 'clojure.core :exclude '[defn]) (clojure.core/require '[seon.contract :refer [defn]])")
             (load-string status-of-src))
           (is (= :paused ((resolve 'x.status/status-of) :paused)) "a function defined next is checked against the registered schema")
           (finally (remove-ns 'x.status))))
    (testing "the registration is program on the agent's branch: no longer the binary's, in the resource it came from"
      (is (= {:schema/form paused-status :schema/file facts-file}
             (d/q '[:find (pull ?e [:schema/form :schema/file]) . :in $ ?k :where [?e :schema/key ?k]] db :seon.task/outcome)))
      (is (= (conj (set (keys (program/schema-changes db0))) :seon.task/outcome) (set (keys (program/schema-changes db))))
          "the branch's population, as the next eval reads it: main's beside the one registered"))))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-registration-naming-no-schema-is-refused-as-data
  ;; docs/source.md#schemas-at-the-repl. Estimate: a test db 0.3M + a registration 5.6M
  (is (= :seon.schema/registration-refused
         (:seon.error/type (schema/registration (main-db) :x.schema-test/thing [:map [:x.schema-test/part :x.schema-test/nowhere]] false)))
      "a form naming a schema that does not exist is refused, and no tx comes with it"))

(deftest ^{:seon.test/estimate {:ticks 7000000}} a-new-key-goes-to-the-resource-holding-most-keys-of-its-namespace
  ;; owner, 2026-10-21. docs/source.md#schemas-at-the-repl. Estimate: a test db 0.3M + a registration 5.6M
  (is (= {:schema/form ":string" :schema/file facts-file}
         (select-keys (first (:tx (schema/registration (main-db) :seon.agent/invalid :string false))) [:schema/form :schema/file]))))

(deftest ^{:seon.test/estimate {:ticks 7000000}} a-key-in-a-scratch-namespace-has-no-resource
  ;; seen at the REPL but no program. docs/source.md#schemas-at-the-repl. Estimate: a test db 0.3M + a registration 5.6M
  (is (= {:schema/form ":int"} (select-keys (first (:tx (schema/registration (main-db) :user/qty :int true))) [:schema/form :schema/file]))))

(deftest ^{:seon.test/estimate {:ticks 7000000}} a-registration-counts-the-stored-values-it-refuses
  ;; task my.schema/a-registration-counts-the-stored-values-it-refuses (v1's
  ;; seon.db/registration-check, ported later by the owner's ruling of 2026-10-21): a
  ;; registration that narrows a stored attribute answers how many stored values its new
  ;; form refuses, so the agent sees what its change would break before it merges
  (let [db (d/db-with (main-db) [{:seon.task/id "a/one" :seon.task/outcome :merged}
                                 {:seon.task/id "a/two" :seon.task/outcome :running}])
        r (schema/registration db :seon.task/outcome [:and {:seon.db/index true} [:enum :running :ended]] false)]
    (is (= {:checked 2 :refused 1} (:seon.schema.usage/check (:registration r))))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-loaded-form-compiles-against-its-dbs-schemas
  ;; found by the live demonstration (2026-10-21): the merge agent loaded the work branch's
  ;; (defn status-of {:malli/schema [:=> [:cat :seon.task/outcome] ...]}) with the built
  ;; registry, so its proof erred on :refused. A process loading a db's forms (an agent's
  ;; merge of main, root and the fork server after a merge) compiles them against that db's
  ;; schemas
  (let [main (main-db)
        branch (entered main :seon.task/outcome (read-string paused-status))
        branch (pt/record branch 'x.schema-load "(defn status-of {:malli/schema [:=> [:cat :seon.task/outcome] :keyword]} [s] s)")]
    (remove-ns 'x.schema-load)
    (is (= [] (program/load-diff! main branch)))
    (is (= :paused ((resolve 'x.schema-load/status-of) :paused)) "its contract accepts the branch's :paused")))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-merged-schema-change-re-arms-the-contracts-naming-it
  ;; BUILD plan P4: main's change to a schema reaches the code already loaded where main's
  ;; program runs (root, the fork server): each contracted var whose contract names it, or a
  ;; schema naming it, is loaded again against the changed population. The code is loaded as a diff of the one form
  ;; (load-diff! from the db without it), never the example program's whole (load-all!, ~21M ticks).
  ;; Estimate: a test db 0.3M + a recorded form 0.3M + two load-diff! ~2M each
  (let [base (main-db)
        main (pt/record base 'x.schema-arm "(defn status-of {:malli/schema [:=> [:cat :seon.task/outcome] :keyword]} [s] s)")
        status-of (fn [s] ((resolve 'x.schema-arm/status-of) s))
        _ (remove-ns 'x.schema-arm)
        _ (is (= [] (program/load-diff! base main)) "main's program loaded, its contracts armed")
        _ (is (thrown? Exception (status-of :paused)) "the built schema refuses :paused")
        moved (entered main :seon.task/outcome (read-string paused-status))]
    (is (= [] (program/load-diff! main moved)))
    (is (= :paused (status-of :paused)) "re-armed against main's :seon.task/outcome")))

(deftest ^{:seon.test/estimate {:ticks 2500000}} a-merged-schema-change-leaves-the-process-registry-as-it-was
  ;; docs/source.md#schemas-at-the-repl: a loader is called on throwaway dbs (tests, a branch's resume), so it never sets this
  ;; process's default registry; holding main does (root's boot and main commit, the fork server's warm start). Red when
  ;; the loader set it: :paused, registered on a test db, leaked to every later check in the shard (mg/seon.task/move-tx,
  ;; bin/land --check 2026-11-14). Estimate: a test db 0.3M + an entry 0.1M + load-diff! (the registry recompiled, the
  ;; contracts naming :seon.task/outcome re-armed) 1.5M, 2M
  (let [db (ts/test-db)
        moved (entered db :seon.task/outcome (read-string paused-status))]
    (is (= [] (program/load-diff! db moved)))
    (is (false? (seon.contract/valid? :seon.task/outcome :paused)) "this process still holds its own registry, which refuses :paused")))

(deftest an-unquoted-predicate-is-refused-naming-it
  ;; 2026-10-29: the md-scan bench's run registered [:vector [:tuple nat-int? nat-int?]] unquoted,
  ;; was refused :unreadable, and spent its last turns finding which part did not read back
  (let [r (schema/registration (main-db) :x.schema-test/pairs [:vector [:tuple nat-int? 'nat-int?]] false)]
    (is (= :unreadable (:seon.schema/refused-because r)))
    (is (str/includes? (:seon.error/message r) "at [1 1]") "where the value that is no EDN is")
    (is (str/includes? (:seon.error/message r) "quote the form") "and how to write it as data")))

(deftest ^{:seon.test/estimate {:ticks 11000000}} a-registration-re-arms-the-contracts-its-process-holds
  ;; md-scan #7, #8, #11 and #13, 2026-10-29: an agent registered :seon.ref/block with its new list keys, and seon.ref/sections,
  ;; defined before, still checked the old form; so did seon.ref/scan, defined with seon.contract/defn (armed at its definition, no
  ;; raw root kept). A registration compiles the contracts that reach its key again (seon.program/rearm!), a function armed by arm!
  ;; and one the macro armed alike. Both vars here, one rearm! (~8M ticks: the walk of the 3,049 contracted vars is its floor)
  (let [src "(defn held {:malli/schema [:=> [:cat :x.rearm/state] :keyword]} [s] s)"
        db0 (entered (main-db) :x.rearm/state [:enum :a])
        held (fn [n] @(resolve (symbol (str "x." n) "held")))
        refuses? (fn [n] (try ((held n) :b) false (catch Exception _ true)))]
    (try
      (binding [malli.registry/*registry* (registry-of db0)]
        (binding [*ns* (create-ns 'x.rearm)] (load-string src) (contract/arm! (resolve 'x.rearm/held)))
        (binding [*ns* (create-ns 'x.rearm2)]
          (load-string "(clojure.core/refer 'clojure.core :exclude '[defn]) (clojure.core/require '[seon.contract :refer [defn]])")
          (load-string src))
        (alter-meta! (resolve 'x.rearm2/held) assoc :source src))
      (is (= [true true] [(refuses? "rearm") (refuses? "rearm2")]) "both refuse :b while armed against [:enum :a]")
      (is (= [] (program/rearm! (entered db0 :x.rearm/state [:enum :a :b]) #{:x.rearm/state})))
      (is (= [:b :b] [((held "rearm") :b) ((held "rearm2") :b)])
          "the function armed before the registration checks the new form, and so does one the contract macro armed")
      (finally (remove-ns 'x.rearm) (remove-ns 'x.rearm2)))))

(deftest ^{:seon.test/estimate {:ticks 7000000}} registering-another-type-says-it-cannot-change
  ;; docs/data-modeling.md#data-modeling: an attribute's type is fixed; registering says so at once, as data, not at a later write
  (let [db (d/db-with (main-db) [{:db/ident :x.schema-test/n :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
        r (schema/registration db :x.schema-test/n '[:int] false)]
    (is (= :type-change (:seon.schema/refused-because r)) (pr-str r))
    (is (str/includes? (:seon.error/message r) ":db.type/string") "the type it has")
    (is (str/includes? (:seon.error/message r) "new name") "and how to go on")))

(deftest ^{:seon.test/estimate {:ticks 7000000}} registering-the-type-an-attribute-has-registers
  ;; the contrast of registering-another-type-says-it-cannot-change: only a changed type is refused
  (let [db (d/db-with (main-db) [{:db/ident :x.schema-test/n :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])]
    (is (:tx (schema/registration db :x.schema-test/n '[:string] false)) "the same type registers")))
