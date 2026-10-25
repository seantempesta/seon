(ns seon.program-diff-test
  "Making a process run one program where it holds another (seon.program/load-diff!, a resumed
  or loaded session's first step) costs the difference between them, never the programs:
  seon.program/diff-plan reads what the two values' program datoms differ by, and plans what
  reading both programs whole planned."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]] [datahike.api :as d]
            [seon.cost :as cost] [seon.program :as program] [seon.program-cost-test :as pc] [seon.db :as pdb]
            [clojure.java.shell :as sh]))

(defn- acct
  {:malli/schema [:=> [:cat :int :string] :map]}
  [i body]
  {:id (str "app.pc/f" i "-" body) :src (str "(defn f" i " [x] " body ")") :ns 'app.pc :ns-form? false
   :defs [{:var (symbol "app.pc" (str "f" i)) :kind :fn}] :declares #{} :methods [] :impls []
   :refs (if (pos? i) #{(symbol "app.pc" (str "f" (dec i)))} #{}) :requires #{} :banned #{} :inline-defs #{}})

(defn- whole-plan
  "What load-diff! planned before diff-plan, reading both programs whole: the oracle."
  {:malli/schema [:=> [:cat :any :any] :map]}
  [from to]
  (let [def-keys (fn [db] (into #{} (for [a (vals program/pointer) dt (d/datoms db :aevt a)] (#'program/definition-key (d/entity db (:e dt))))))
        live-from (#'program/live-ids from)
        to-defs (def-keys to)
        sc-from (program/schema-changes from)]
    {:remove (vec (sort-by str (remove to-defs (def-keys from))))
     :load (vec (for [e (sort (vals (apply dissoc (#'program/live-ids to) (keys live-from))))] (program/form-row to e)))
     :schemas (set (for [[k v] (program/schema-changes to) :when (not= v (get sc-from k))] k))}))

(deftest a-diff-plans-what-reading-both-programs-planned
  (let [[db] (pc/branch-of 20)
        var-e (fn [db i] (:db/id (d/entity db [:var/name (symbol "app.pc" (str "f" i))])))
        cases {"nothing changed" db
               "a form redefined" (d/db-with db (program/record-tx db (acct 10 "(dec x)")))
               "a form added" (d/db-with db (program/account-tx db (acct 20 "x") 20))
               "a definition removed" (d/db-with db [[:db/retract (var-e db 5) :var/form (:db/id (:var/form (d/entity db (var-e db 5))))]])
               "a schema entry registered" (d/db-with db [{:schema/key :app.pc/s :schema/form ":int"}])
               "several at once" (let [db1 (d/db-with db (program/record-tx db (acct 3 "(* 2 x)")))]
                                   (d/db-with db1 (concat (program/account-tx db1 (acct 21 "x") 21) [{:schema/key :app.pc/t :schema/form ":string"}])))}]
    (doseq [[what to] cases]
      (testing what
        (is (= (whole-plan db to) (program/diff-plan db to)) "from the program to this")
        (is (= (whole-plan to db) (program/diff-plan to db)) "and back")))
    (let [db1 (d/db-with db [{:schema/key :app.pc/s :schema/form ":int"}])]
      (doseq [[what to] {"a schema entry changed in place" (d/db-with db1 [{:schema/key :app.pc/s :schema/form ":string"}])
                         "a schema entry made the binary's" (d/db-with db1 [{:schema/key :app.pc/s :schema/source true}])}]
        (testing what
          (is (= (whole-plan db1 to) (program/diff-plan db1 to)) "from the entry to this")
          (is (= (whole-plan to db1) (program/diff-plan to db1)) "and back")))
      (is (= #{:app.pc/s} (:schemas (program/diff-plan db1 (d/db-with db1 [{:schema/key :app.pc/s :schema/form ":string"}])))) "a changed entry's key"))
    (testing "the cases differ as they should"
      (is (= {:remove [] :load [] :schemas #{}} (program/diff-plan db db)))
      (is (= 1 (count (:load (program/diff-plan db (get cases "a form redefined"))))))
      (is (= [[:var 'app.pc/f5]] (:remove (program/diff-plan db (get cases "a definition removed")))))
      (is (= #{:app.pc/s} (:schemas (program/diff-plan db (get cases "a schema entry registered"))))))))

(deftest a-diff-costs-the-change-never-the-program
  (try
    (cost/proof
      {:of 'seon.program/diff-plan :input 'seon.program-cost-test/on-disk-diverged-of
       :n "forms main and the branch hold (the branch redefines one)"
       :work (str "datoms-diff over the four definition pointers, :form/id, :form/defines and the schema's three "
                  "attributes (each skips the stored subtrees the two values share: one index path per changed "
                  "entry, log n), then the candidate entities' own datoms and the one changed form's row; the "
                  "whole read before was four live-form scans and one entity read a definition on each side")
       :growth :log
       ;; measured 2026-10-26 on LMDB: 1.56 MB at 100 forms, 2.34 MB at 1,000 (about 234 KB a doubling:
       ;; the index paths to the changed form), 6 contracted calls; reading both programs whole was
       ;; 400-450 ms a resumed spawn on Seon's program (seon.agent/spawn-phases' :base)
       :per-n {:bytes 240000.0}
       :fixed {:bytes 100000.0 :calls 6}
       :sizes @#'pc/divergence-sizes})
    (finally
      (doseq [n @#'pc/divergence-sizes :let [path (#'pc/on-disk-path n)] :when (pdb/store? path)]
        (doseq [b [:db :branch]] (d/release (d/connect (assoc (pdb/store-cfg :lmdb path) :branch b)) true))
        (sh/sh "rm" "-rf" path)))))
