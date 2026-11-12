(ns seon.task-cost-test
  "Cost proofs of the task reader's hot lookups."
  (:refer-clojure :exclude [defn defn-])
  (:require [my.test :as mt] [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.task :as task] [seon.cost :as cost]))

(defn tasks-db
  "The input of size n: a main db holding n tasks, and the id of one of them."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :keyword :string]]}
  [n]
  (let [db (mt/world)
        decls (vec (for [i (range n)] {:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan "p"}))]
    [(d/db-with db (task/create-tx db decls)) :seon.task/id (str "demo/t" (quot n 2))]))

(defn replaced-db
  "The input of size n: a db keeping history where one note's text was replaced n times, and the note."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [db0 (d/db-with (mt/world {:history? true}) [{:db/id "n" :note/text "t0" :note/agent :x}])
        e (:e (first (d/datoms db0 :avet :note/agent :x)))]
    [(reduce (fn [db i] (d/db-with db [[:db/add e :note/text (str "t" i)]])) db0 (range 1 (inc n))) e]))

(deftest an-entitys-history-costs-its-rows
  ;; docs/storage.md#history
  (cost/proof
    {:of 'seon.db/history :input 'seon.task-cost-test/replaced-db
     :n "times the entity's attribute was replaced"
     :work "one seek of the entity in the history index, then a row per datom, each with one seek of its transaction's time: about 20 KB a row"
     :growth :linear
     :per-n {:calls 0 :bytes 20000}
     :fixed {:calls 2 :bytes 5000}
     :sizes [10 40 160]}))

(defn answer-of
  "The input of size n: an eval's arguments to seon.repl/handled, an answer of n plain rows."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :string :string :map :map]]}
  [n]
  ["e1" "(rows)" {:value (vec (for [i (range n)] {:id i :name (str "row " i) :tags [:a :b] :text "some text of a plain answer"}))} {:origin :model}])

(deftest a-handle-costs-its-answer
  ;; docs/agent.md#your-turn; 10 calls by the owner's yes, 2026-11-07: an eval's split into its result (seon.db/with-response) is one more;
  ;; 11 by the owner's yes, 2026-11-08: the handle's name is seon.lib/id's form, one call more
  (cost/proof
    {:of 'seon.repl/handled :input 'seon.task-cost-test/answer-of
     :n "rows in the answer"
     :work "one walk of the answer to record it and one to hash it, a row at a time"
     :growth :linear
     :per-n {:calls 0 :bytes 50000}
     :fixed {:calls 11 :bytes 50000}
     :sizes [10 40 160]}))

(deftest finding-a-task-by-its-id-is-one-index-seek
  (cost/proof
    {:of 'seon.db/eid :input 'seon.task-cost-test/tasks-db
     :n "tasks on main"
     :work (str "one seek in the unique attribute's AVET index: 2-3 B-tree nodes, a binary search of ~6 datom "
                "comparisons (~100 ns each on Jolt) per node, then one datom answered; no query planning, nothing "
                "per task. About 3 us and 1.3 KB at 10k tasks")
     :growth :log
     :per-n {:ns 150 :bytes 50}
     :fixed {:ns 1000 :bytes 600 :calls 1}
     :sizes [100 300 1000]
     ;; measured 2026-10-20 (was a Datalog query: 99 us, 108 KB): the seek itself allocates
     ;; 23-32 KB in our Datahike / persistent-sorted-set on Jolt, on the whole process's count.
     ;; Re-measured 2026-10-23 on the proof's own thread (18b0774; Chez 80e4b9f, load 17): 17 ->
     ;; 21 KB and 18 -> 21 us. Taken again 2026-11-10 at 1,000, the largest size (a waiver is judged at its proof's
     ;; largest, and 10,000 is past the cap): 8.8 KB, 8.0x the bound's bytes. The record a ratchet compares with is this measure's
     ;; 2026-11-10, with Datahike at the seek fix (a unique attribute's value is one descent, no slice): 2,384 B and
     ;; 5.2 us at 1,000 (was 8,832 B), 2.2x the bound's bytes. What remains is Jolt's, not the seek's: constructing the
     ;; bound datom (448 B), the descent's method calls (576), d/datoms' multimethod and varargs (448), protocol dispatch
     ;; (about 280). So the waiver names the Jolt dispatch task now; it closes with that work, measured again on merged Jolt
     :overrun {:task "jolt.backend/a-deftype-method-call-takes-a-site-cache" :n 1000 :measured {:ns 5166.0 :bytes 2384.0}}}))

(defn ended-db
  "The input of size n: a main db holding n tasks whose agents ended, each with what a judging carries on main: its
  reason, error, a commit, a red test's assertions, a cost report and the turns' waste (outcome-tx's members), and its agent."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n]
  (let [db (mt/world)
        ids (mapv #(str "demo/t" %) (range n))
        db (d/db-with db (task/create-tx db (mapv (fn [i] {:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan "p"}) (range n))))
        report {:rows [{:n 100 :measured {:ns 1200.0 :bytes 900.0} :expected {:ns 1000.0 :bytes 800.0}}] :verdict {:ok? false :over [{:dim :ns :n 100 :ratio 1.2}] :steeper [] :under [] :unmerged []}}
        started (d/db-with db (vec (mapcat (fn [i id] (task/start-tx db id "boot-1" (keyword (str "a" i)) {:branch :b :model "m" :max-turns 4} 1000)) (range) ids)))]
    [(d/db-with started
                (vec (mapcat (fn [id]
                               (task/outcome-tx started id :running :dead
                                                {:seon.task/reason "the model failed" :seon.task/commit "c0ffee"
                                                 :seon.task/error {:seon.error/type :seon.model/no-provider :seon.error/operation 'seon.model/complete :seon.error/message "no model provider is configured"}
                                                 :seon.task/failures {'demo-test/t [{:type :fail :message "sums" :expected "(= 3 (f 1 2))" :actual "(= 3 4)"}]}
                                                 :seon.task/costs {'demo-test/c report}
                                                 :seon.task/waste {'seon.doc/resolve-ref {:forms 2 :turns 1}}}))
                             ids)))]))

(deftest a-task-reads-with-every-member-its-judging-wrote
  ;; land6 review: the wildcard pull (research row 20) brings every stored member (its error, failures, cost reports,
  ;; waste, commit) into the value, so a row of a page draws from it what the judging recorded
  (let [[db] (ended-db 3)
        tk (task/task db "demo/t1")]
    (is (= [:dead "c0ffee" "the model failed"] ((juxt :seon.task/outcome :seon.task/commit :seon.task/reason) tk)))
    (is (every? #(contains? tk %) [:seon.task/error :seon.task/failures :seon.task/costs :seon.task/waste]) "the wildcard brings the problem members")
    (is (= "a1" (:seon.task/agent tk)) "and names its agent by key")))

(deftest reading-every-task-costs-what-its-datoms-cost
  (cost/proof
    {:of 'seon.task/tasks :input 'seon.task-cost-test/ended-db
     :n "tasks whose agents ended on main, each with its judging's and problem members"
     :work (str "one query for the task entities, then one named pull each (seon.task's task-pull: its schema's attributes, a "
                "seek each on Datahike's pull today), every datom of the task read once, its EDN members (error, failures, cost report, waste) decoded, then task-value (one contracted "
                "call) and its accumulated plan and proofs. Measured 2026-10-22 at 100 runs (the entity this replaced): "
                "14.9 ms and 15.8 MB (149 us, 158 KB each); the explicit 23-attribute pull it replaced read the same "
                "input in 32.6 ms and 30.1 MB (one lookup per named attribute, absent ones included)")
     :growth :linear
     ;; 2026-10-31 (shrink-tests-4), as runs on the world, sizes [10 30 100]: 1.40M ns / 1.45 MB at 10, 3.87M / 4.18 MB at 30,
     ;; 12.4M / 13.6 MB at 100: ~124 us and 135 KB each (the larger sizes read the same per entity)
     ;; 2026-11-04, as tasks: 0.60M ns / 0.94 MB and 23 contracted calls at 10 (60 us, 94 KB, 2 calls each: task-value and
     ;; accumulate; the runs it replaced made one, run-value). The ns and bytes bounds stay the runs' own
     ;; 2026-11-12, the named pull (the owner: a pull names the shape its reader needs): 2.53M ns / 1.89 MB at 10, a seek a named
     ;; attribute where '* read one :eavt range; Deep fixes' named-pull item (one range read) should bring it back to 130 us / 140 KB
     :per-n {:ns 250000 :bytes 190000 :calls 2}
     :fixed {:ns 100000 :bytes 100000 :calls 3}
     :sizes [10 30 100]}))

(defn carried-of
  "The input of size n: carry-tx's three dbs (base, branch, main) over n tasks, each with a plan and
  two proofs, the branch having improved one task's plan."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
  [n]
  (let [db (mt/world)
        decls (vec (for [i (range n)] {:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan (str "Make t" i " true.")
                                       :seon.task/proofs #{(symbol "demo-test" (str "t" i "-test")) 'demo-test/other}}))
        base (d/db-with db (task/create-tx db decls))]
    [base (d/db-with base [[:db/add [:seon.task/id "demo/t0"] :seon.task/plan "Better."]]) base]))

(deftest carrying-a-branch-reads-each-carried-datom-once
  (cost/proof
    {:of 'seon.task/carry-tx :input 'seon.task-cost-test/carried-of
     :n "tasks on main"
     :work (str "the carried attributes' datoms (a plan and two proofs a task) read from :aevt on each of the three "
                "dbs, one map entry each, a set member's cardinality read from the db's attribute index "
                "(datahike.db.utils/multival?), and the compare per member; nothing is written for an unchanged task")
     :growth :linear
     ;; measured: 0.46 / 3.09 / 28.8 MB at 10 / 100 / 1,000 tasks, 2 calls; it was 774 MB at 100 when each datom's
     ;; cardinality read (d/schema main), which rebuilds the whole schema map
     ;; 2026-10-31 (shrink-tests-4): on the world, sizes [10 30 100]: 323 KB at 10, 809 KB at 30, 2.57 MB at 100: ~25 KB a task
     :per-n {:bytes 26000}
     :fixed {:calls 2 :bytes 100000}
     :sizes [10 30 100]}))

(defn loaded-of
  "The input of size n: a main db holding n tasks loaded from their file (each with its declaration stored), and the
  same file read again."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:map-of :symbol :any]]]}
  [n]
  (let [db (mt/world)
        files {'demo (vec (for [i (range n)] {:seon.task/name (str "t" i) :seon.task/ns 'demo :seon.task/plan "p"
                                              :seon.task/needs (if (zero? (mod i 5)) #{} #{(str "demo/t" (dec i))})}))}]
    [(d/db-with db (task/load-tx db files)) files]))

(deftest an-unchanged-load-reads-four-members-of-each-task
  (cost/proof
    {:of 'seon.task/load-tx :input 'seon.task-cost-test/loaded-of
     :n "tasks on main, all unchanged"
     :work (str "four :aevt scans (id, ns, execution, needs: ~4 datoms a task, one map entry each) in place of a "
                "pull of every member of every task (`seon.task/tasks`: ~93 KB a task on main, 46 MB for 452), and "
                "the stored declaration of each task decoded to compare it; nothing is written")
     :growth :linear
     :per-n {:ticks 20000.0 :bytes 30000.0 :calls 6.0}
     :fixed {:ticks 100000.0 :bytes 200000.0 :calls 20.0}
     :sizes [10 100 300]}))

(defn shipped-shaped
  "The input of size n: the world (mt/world), and n task entries as the shipped files hold them, ten a file: a plan of about
  1.5 KB (the files' mean is 1,550 chars) citing a var (`app.calc/add`), a section (`docs/calc.md#reports`) and the entry
  before it in the same load, naming a var, a page whole and a task on main as text words, with one or two proofs, a need on
  the entry before it in one of ten, instructions in one of ten."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:map-of :symbol :any]]]}
  [n]
  (let [prose (apply str (repeat 12 "The reader takes the rows as they are, and a row it cannot read is the load's refusal. "))
        entry (fn [i]
                (let [nsym (symbol (str "app.gen" (quot i 10)))]
                  (cond-> {:seon.task/ns nsym :seon.task/name (str "t" i)
                           :seon.task/plan (str "What exists: `app.calc/add` sums two numbers, and docs/calc.md says why; app.calc/total "
                                                "is the sum of a seq (`docs/calc.md#reports`), app.calc/total-is-exact its task. "
                                                (when (pos? i) (str "It follows `app.gen" (quot (dec i) 10) "/t" (dec i) "`. "))
                                                prose)
                           :seon.task/proofs (if (even? i) #{(symbol (str nsym "-test") (str "t" i "-holds"))}
                                               #{(symbol (str nsym "-test") (str "t" i "-holds")) 'app.calc-test/add-adds})}
                    (and (pos? i) (zero? (mod i 10))) (assoc :seon.task/needs #{(str "app.gen" (quot (dec i) 10) "/t" (dec i))})
                    (= 5 (mod i 10)) (assoc :seon.task/instructions "One pass over the rows; see `docs/calc.md#calc`."))))]
    [(mt/world) (reduce (fn [m i] (let [e (entry i)] (update m (:seon.task/ns e) (fnil conj []) e))) {} (range n))]))

(deftest a-load-costs-each-new-entry-its-text-and-its-cites
  ;; docs/references.md#an-entity-cites: the shipped task files' load (seon.task-files-test/the-trees-task-files-and-pages-load-with-no-refusal)
  (cost/proof
    {:of 'seon.task/load-tx :input 'seon.task-cost-test/shipped-shaped
     :n "new task entries, each a production-shaped declaration (a 1.5 KB plan citing five things)"
     :work (str "per entry: its declaration encoded once (seon.edn/encode, ~1.7 KB at pr-str's 43 ticks and 26 B a char: 74k "
                "ticks, 44 KB); its plan parsed once (seon.ref/parse, a char seq step a char: 17k ticks, 120 KB); five citations "
                "each resolved twice, kept then its cite written (seon.ref/target, ~3 index seeks at 2.4k ticks and 2.4 KB: 72k "
                "ticks, 72 KB); its identity speculated once so a cite of a task of the same load resolves (one unique datom, "
                "8.3k ticks and 19 KB: docs/storage.md#what-a-datom-costs-to-add); ~100 contracted calls (1.4k ticks). About "
                "173k ticks and 255 KB an entry. Fixed: the held tasks' scans and the needs graph of a small world. Before, "
                "every new task was speculated whole twice (create-tx's, then load-tx's), ~10 datoms each: +170k ticks and "
                "+300 KB an entry")
     :growth :linear
     :per-n {:ticks 173000.0 :bytes 255000.0}
     :fixed {:ticks 500000.0 :bytes 1000000.0}
     :sizes [10 30 100]}))
