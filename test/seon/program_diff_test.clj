(ns seon.program-diff-test
  "Making a process run one program where it holds another (seon.program/load-diff!, a resumed
  or loaded session's first step) costs the difference between them, never the programs:
  seon.program/diff-plan reads what the two values' program datoms differ by, and plans what
  reading both programs whole planned."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.lib] [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]] [datahike.api :as d]
            [seon.cost :as cost] [seon.program :as program] [seon.program-cost-test :as pc] [seon.db :as pdb]
            [clojure.java.shell :as sh]))

(defn- acct
  {:malli/schema [:=> [:cat :int :string] :map]}
  [i body]
  {:id (str "app.pc/f" i "-" body) :src (str "(defn f" i " [x] " body ")") :ns 'app.pc :ns-form? false
   :defs [{:var (symbol "app.pc" (str "f" i)) :meta #{:arglists}}] :declares #{} :methods [] :impls []
   :refs (if (pos? i) #{(symbol "app.pc" (str "f" (dec i)))} #{}) :requires #{} :banned #{} :inline-defs #{}})

(defn- whole-plan
  "What load-diff! planned before diff-plan, reading both programs whole: the oracle."
  {:malli/schema [:=> [:cat :any :any] :map]}
  [from to]
  (let [def-keys (fn [db] (into #{} (for [a (vals program/pointer) dt (d/datoms db :aevt a)] (#'program/definition-key db (:e dt)))))
        live-from (#'program/live-ids from)
        to-defs (def-keys to)
        sc-from (program/schema-changes from)]
    {:remove (vec (sort-by str (remove to-defs (def-keys from))))
     :load (vec (for [e (sort (vals (apply dissoc (#'program/live-ids to) (keys live-from))))] (program/form-row to e)))
     :schemas (set (for [[k v] (program/schema-changes to) :when (not= v (get sc-from k))] k))}))

;; Estimate: branch-of 3: 3 account-tx x 0.1M = 0.3M; 2 whole-plan reads (the oracle: 2 program scans of ~3 forms + schema-changes) x 0.5M = 1M; ~14 diff-plans x 0.1M = 1.4M; ~8 db-withs x 0.1M. Estimated 3,500,000 ticks, worked from the work, never from a run.
(deftest ^{:seon.test/estimate {:ticks 3500000}} a-diff-plans-what-reading-both-programs-planned
  (let [[db] (pc/branch-of 3)
        var-e (fn [db i] (:db/id (d/entity db [:var/name (symbol "app.pc" (str "f" i))])))
        redefined (d/db-with db (program/record-tx db (acct 1 "(dec x)")))
        added (d/db-with db (program/account-tx db (acct 3 "x") 3))
        removed (d/db-with db [[:db/retract (var-e db 2) :var/form (:db/id (:var/form (d/entity db (var-e db 2))))]])
        registered (d/db-with db [{:schema/key :app.pc/s :schema/form ":int"}])
        several (let [db1 (d/db-with db (program/record-tx db (acct 0 "(* 2 x)")))]
                  (d/db-with db1 (concat (program/account-tx db1 (acct 4 "x") 4) [{:schema/key :app.pc/t :schema/form ":string"}])))
        changed-in-place (d/db-with registered [{:schema/key :app.pc/s :schema/form ":string"}])
        unregistered (d/db-with registered [[:db/retractEntity (:db/id (d/entity registered [:schema/key :app.pc/s]))]])]
    ;; the oracle (reading both programs whole) costs ~10 ms a read: it judges the case that holds a redefinition, an addition and a
    ;; registration at once, both ways (so a removal and an unregistration too); a changed entry is held to its key; each single kind is held to its plan's own rows
    (testing "several at once"
      (is (= (whole-plan db several) (program/diff-plan db several)) "from the program to this")
      (is (= (whole-plan several db) (program/diff-plan several db)) "and back"))
    (testing "the single kinds differ as they should"
      (is (= {:remove [] :load [] :schemas #{}} (program/diff-plan db db)))
      (is (= 1 (count (:load (program/diff-plan db redefined)))))
      (is (= 1 (count (:load (program/diff-plan db added)))))
      (is (= [[:var 'app.pc/f2]] (:remove (program/diff-plan db removed))))
      (is (= #{:app.pc/s} (:schemas (program/diff-plan db registered))))
      (is (= #{} (:schemas (program/diff-plan registered db))) "an entry gone from the program to load has no schema to register")
      (is (= #{:app.pc/s} (:schemas (program/diff-plan registered changed-in-place))) "a changed entry's key")
      (is (= #{} (:schemas (program/diff-plan registered unregistered)))))))

(def ^:private diff-sizes "The sizes diff-plan's proof measures." [10 30 100])

;; Estimate: sizes 10+30+100 = 140 forms x 0.1M = 14M of setup; 3 LMDB stores made x S 10M = 30M; 3 branch writes and measured diff-plans (9 descents ~0.3M each, measured again when over) ~3M. Estimated 50,000,000 ticks, worked from the work, never from a run.
(deftest ^{:seon.test/estimate {:ticks 30000000}} a-diff-costs-the-change-never-the-program
  (try
    (cost/proof
      {:of 'seon.program/diff-plan :input 'seon.program-cost-test/on-disk-diverged-of
       :n "forms main and the branch hold (the branch redefines one)"
       :work (str "datoms-diff over the four definition pointers, :form/id, :form/defines and the schema's three "
                  "attributes (each skips the stored subtrees the two values share: one index path per changed "
                  "entry, log n), then the candidate entities' own datoms and the one changed form's row; the "
                  "whole read before was four live-form scans and one entity read a definition on each side")
       :growth :log
       ;; 2026-10-29, task seon.cost/step-costs-are-not-a-shape: what diff-plan reads is the nine descents (the
       ;; four pointers, :form/id, :form/defines, the schema's three: 54-95 KB each when nothing differs, 100-340 KB
       ;; with a change) and ~85 KB of lookups beside them, one descent an attribute and no more (an in-process
       ;; probe summed the nine at 650 KB of 738 KB at 10 forms); an index's depth is a step in them: 738 KB at 10 and
       ;; 100 forms, 912 KB at 300, 1.15 MB at 1,000, 1.39 MB at 3,000 on Jolt 546c3d1f (655k, 699k, 972k at 10, 100,
       ;; 1,000 on the Jolt before it). The fit leaves out an increment under :seon.cost/growth-share, so the flat side
       ;; of a step is no shape at any sizes; the estimate is the trend through those rows
       ;; measured 2026-10-26 on LMDB: 1.56 MB at 100 forms, 2.34 MB at 1,000 (about 234 KB a doubling:
       ;; the index paths to the changed form), 6 contracted calls; reading both programs whole was
       ;; 400-450 ms a resumed spawn on Seon's program (seon.agent/spawn-phases' :base)
       :per-n {:bytes 85000.0}
       :fixed {:bytes 480000.0}
       :sizes diff-sizes})
    (finally
      (doseq [n diff-sizes :let [path (#'pc/on-disk-path n)] :when (pdb/store? path)]
        (doseq [b [:db :branch]] (d/release (pdb/connect (assoc (pdb/store-cfg :lmdb path) :branch b)) true))
        (seon.lib/remove-tree! path)))))
