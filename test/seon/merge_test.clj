(ns seon.merge-test
  "The program merge (seon.merge, in core) over db values: the three-way case table,
  identity by content (identical definitions on two branches merge clean whatever their
  entity ids and places), an unmap merging as a delete of the definition and of the form
  it leaves dead, the latest redefinition winning, scratch data and observations never
  merging, unmerged entries derived from the branch, and behind meaning main's program
  moved."
  (:require [seon.test-store :as ts] [seon.proofs :as proofs] [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.merge :as pm]
            [seon.program :as program] [seon.docs]
            [seon.edn :as sedn] [seon.contract :as contract] [malli.core :as m] [malli.generator :as mg]
            [my.schema :as schema]
            [seon.source :as source]
            [clojure.string :as str] [clojure.edn :as edn]))

(defn- empty-db [] (ts/test-db))

(defn- defn-src [nm body] (str "(defn " nm " {:malli/schema [:=> [:cat :int] :int]} [x] " body ")"))

(defn- fid [nm body] (str "app/" (defn-src nm body)))

(defn- var-tx
  "A recorded definition of app/nm (as seon.program/record-tx writes it): a fn of the db."
  [nm body]
  (fn [db]
    (program/record-tx db {:id (fid nm body) :src (defn-src nm body) :ns 'app :ns-form? false
                           :defs [{:var (symbol "app" nm) :kind :fn :contract "[:=> [:cat :int] :int]"}]
                           :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})))

(defn- with [db & txs] (reduce (fn [db t] (d/db-with db (if (fn? t) (t db) t))) db txs))

;; a function, not a def: a test namespace loads without effects (it is loaded where main's
;; program runs, the fork server included, which opens no store)
(defn- base [] (with (empty-db) (var-tx "f" "x") (var-tx "g" "x") (var-tx "h" "x")))

(defn- unmap [nm]
  (fn [db] (let [e (:db/id (d/entity db [:var/name (symbol "app" nm)]))]
             [[:db/retract e :var/form (:db/id (:var/form (d/entity db e)))]])))

(defn- tw
  "The three-way of base, o and t; every case also proves delta-three-way, read only where
  the dbs differ, answers the same."
  [o t]
  (let [b (base) full (pm/three-way (pm/program b #{}) (pm/program o #{}) (pm/program t #{}))]
    (is (= full (pm/delta-three-way b o t #{})) "delta-three-way answers what three-way does")
    full))
(defn- vk [nm] [:var (symbol "app" nm)])
(defn- defs-of "The definition keys among ks (a form's own key follows its definition)." [ks] (filterv #(not= :form (first %)) ks))

(deftest three-way-cases
  (testing "o = t: nothing to do (identical change, both deleted)"
    (are [o t] (let [r (tw o t)] (and (empty? (:apply r)) (empty? (:conflicts r))))
      (with (base) (var-tx "f" "(inc x)")) (with (base) (var-tx "f" "(inc x)"))
      (with (base) (unmap "f")) (with (base) (unmap "f"))
      (with (base) (var-tx "n" "1")) (with (base) (var-tx "n" "1"))))
  (testing "o = b: theirs applies (add, modify, delete)"
    (are [t k] (= [k] (defs-of (:apply (tw (base) t))))
      (with (base) (var-tx "n" "1")) (vk "n")
      (with (base) (var-tx "f" "(dec x)")) (vk "f")
      (with (base) (unmap "g")) (vk "g")))
  (testing "t = b: ours kept, nothing applied, and it is an ours-change"
    (let [r (tw (with (base) (var-tx "f" "(dec x)")) (base))]
      (is (empty? (:apply r))) (is (empty? (:conflicts r))) (is (= [(vk "f")] (defs-of (keys (:ours r)))))))
  (testing "conflicts carry their kind, base, ours and theirs"
    (are [o t kind] (= [kind] (map :kind (filter #(= :var (first (:key %))) (:conflicts (tw o t)))))
      (with (base) (var-tx "f" "(inc x)")) (with (base) (var-tx "f" "(dec x)")) :both-modified
      (with (base) (var-tx "n" "1")) (with (base) (var-tx "n" "2")) :both-added
      (with (base) (unmap "f")) (with (base) (var-tx "f" "(dec x)")) :deleted-by-us
      (with (base) (var-tx "f" "(inc x)")) (with (base) (unmap "f")) :deleted-by-them)
    (let [c (first (:conflicts (tw (with (base) (var-tx "f" "(inc x)")) (with (base) (var-tx "f" "(dec x)")))))]
      (is (= (vk "f") (:key c)))
      (is (= (fid "f" "x") (:form (:base c))))
      (is (= (fid "f" "(inc x)") (:form (:ours c))))
      (is (= (fid "f" "(dec x)") (:form (:theirs c)))))))

(deftest uncontracted-is-what-the-branch-changed
  ;; one question for the writer's merge gate and root's judge: which functions the branch's
  ;; own changes (a three-way's :ours) define without a contract
  (let [ours {[:var 'app/f] [nil {:kind :fn}]
              [:var 'app/g] [nil {:kind :fn :contract "[:=> [:cat] :int]"}]
              [:var 'app/v] [nil {:kind :def}]
              [:var 'app/e] [{:kind :fn} {:kind :fn}]}]
    (is (= '[app/e app/f] (pm/uncontracted ours)))
    (is (= [] (pm/uncontracted {}))))
  (testing "over recorded programs: a branch that adds an uncontracted fn and edits a contracted one"
    (let [u (fn [db] (program/record-tx db {:id "app/(defn u [x] x)" :src "(defn u [x] x)" :ns 'app :ns-form? false
                                            :defs [{:var 'app/u :kind :fn}]
                                            :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}}))]
      (is (= '[app/u] (pm/uncontracted (:ours (tw (with (base) u (var-tx "f" "(inc x)")) (base)))))))))

(deftest identical-defs-on-two-branches-merge-clean
  ;; one side makes an unrelated entity first; app/n is still one entity on both, at its
  ;; content id (docs/storage.md#ids)
  (let [o (with (base) [{:note/text "scratch"}] (var-tx "n" "(* 2 x)"))
        t (with (base) (var-tx "n" "(* 2 x)"))]
    (is (= (d/q '[:find ?e . :where [?e :var/name app/n]] o) (d/q '[:find ?e . :where [?e :var/name app/n]] t)))
    (is (= {:apply [] :conflicts []} (select-keys (tw o t) [:apply :conflicts])))))

(deftest unmap-merges-as-delete
  (let [ours (with (base) (unmap "g"))
        r (tw ours (base))
        main' (d/db-with (base) (pm/merge-tx (base) (base) ours (keys (:ours r)) #{}))]
    (is (= #{(vk "g") [:form (fid "g" "x")]} (set (keys (:ours r)))) "the definition, and the form it leaves dead")
    (is (nil? (seon.docs/source-of main' 'app/g)))
    (is (not (program/live? main' (pdb/eid main' :form/id (fid "g" "x")))) "the form is dead on main, as on the branch")
    (is (= [:deleted-by-us] (map :kind (filter #(= :var (first (:key %))) (:conflicts (tw ours (with (base) (var-tx "g" "(dec x)"))))))))))

(deftest latest-redefinition-wins
  (let [ours (with (base) (var-tx "f" "1") (var-tx "f" "2") (var-tx "f" "3"))]
    (is (= (fid "f" "3") (:form (second (get (:ours (tw ours (base))) (vk "f"))))))
    (is (= [(defn-src "f" "3")] (for [[[k] [_ o]] (:ours (tw ours (base))) :when (and (= :form k) o)] (:src o)))
        "only the last form is live: each redefinition superseded the one before")))

(deftest a-form-s-place-does-not-make-it-another
  (let [o (with (base) [{:note/text "first"}] (var-tx "n" "(* 2 x)"))
        t (with (base) (var-tx "n" "(* 2 x)"))]
    (is (not= (get-in (pm/program o #{}) [[:form (fid "n" "(* 2 x)")] :order])
              (get-in (pm/program t #{}) [[:form (fid "n" "(* 2 x)")] :order])))
    (is (= {:apply [] :conflicts []} (select-keys (tw o t) [:apply :conflicts])))))

(deftest scratch-and-observations-never-merge
  (let [ours (with (base) [{:note/text "scratch" :note/agent :a1}
                         {:seon.eval/id "e1" :seon.eval/by "a1" :seon.eval/record {:code "(+ 1 2)" :response {:handle 'result/e1 :value 3}}}
                         {:seon.agent/id :a1 :seon.agent/pid 7}
                         {:task/name :x/y :task/status :done}])]
    (is (= (pm/program (base) #{}) (pm/program ours #{})))
    (is (empty? (:ours (tw ours (base)))))))

(deftest unmerged-entry-derivation
  (let [o (with (base) (var-tx "f" "(inc x)"))
        t (with (base) (var-tx "f" "(dec x)"))
        cs (:conflicts (tw o t))
        b1 (d/db-with o (pm/unmerged-tx o cs 42))
        unmerged (fn [db] (mapv :key (pm/unmerged db)))]
    (are [db expected] (= expected (unmerged db))
      ;; written, ours unchanged: unmerged
      b1 [(vk "f")]
      ;; re-defn of the var: resolved by derivation
      (with b1 (var-tx "f" "(+ x 0)")) []
      ;; resolve! keeping ours: marked
      (d/db-with b1 (pm/resolve-tx b1 (vk "f"))) [])
    (is (= 42 (:main-t (first (pm/entries b1)))))
    (testing "a run's conflict evidence (the same members, no :main-t) is not an entry"
      (is (= [(vk "f")] (mapv :key (pm/entries (d/db-with b1 [(pm/conflict-tx (first cs))]))))))
    (testing "a second conflict on the same key replaces the entry"
      (let [b2 (d/db-with b1 (pm/unmerged-tx b1 cs 43))]
        (is (= [43] (map :main-t (pm/entries b2))))))))

(deftest a-conflict-value-reads-a-pulled-entry
  ;; task seon.merge/conflict-value-states-its-input: its input is a merge entry's members
  ;; (mg/check generated nil and 0 from its `:any` input, which state no conflict)
  (let [c (first (:conflicts (tw (with (base) (var-tx "f" "(inc x)")) (with (base) (var-tx "f" "(dec x)")))))
        db (d/db-with (base) [(pm/conflict-tx c)])
        e (d/q '[:find ?e . :where [?e :seon.merge/key]] db)]
    (is (= c (pm/conflict-value (pm/conflict-tx c))) "the inverse of conflict-tx")
    (is (= c (pm/conflict-value (d/pull db '[*] e))) "a pulled entry")
    (let [seed (proofs/test-seed)]
      (is (nil? (mg/check (m/deref (contract/schema (contract/contract #'pm/conflict-value))) pm/conflict-value
                          {:registry contract/registry ::mg/=>seed seed}))
          (str "mg/check: every input its contract generates states a conflict; seed " seed)))))

(deftest behind-means-main-program-moved
  (let [observed (with (base) [{:seon.agent/id :root :seon.agent/pid 9} {:note/text "run facts"}])]
    (is (empty? (:theirs (tw (base) observed))))
    (is (seq (:theirs (tw (base) (with (base) (var-tx "f" "(dec x)"))))))))

(deftest platform-records-never-put-a-branch-behind
  ;; root's records on main (boot, quiescence, recorded errors) are platform records: with
  ;; all data merged (the writer's :all) they change no key a branch carries; the task
  ;; content an agent may improve (my.task/update!) is carried
  (let [rooted (with (base) [{:seon.root/id :root :seon.root/quiescent {:t 9 :root-causes []}}])
        tasked (with (base) [{:seon.task/id "t/x" :seon.task/plan "better"}])
        p #(pm/program % :all)]
    (is (empty? (:theirs (pm/three-way (p (base)) (p (base)) (p rooted)))))
    (is (some (fn [[k]] (= [:datum :seon.task/plan] [(first k) (nth k 2)])) (:ours (pm/three-way (p (base)) (p tasked) (p (base))))))))

(deftest declared-data-merges-by-key
  ;; the data part, used by core's generic merge (every non-program, non-observation attribute)
  (let [o (with (base) [{:db/id 900 :note/text "n" :note/agent :a1}])
        r (pm/three-way (pm/program (base) :all) (pm/program o :all) (pm/program (base) :all))]
    (is (= #{[:datum 900 :note/text] [:datum 900 :note/agent]} (set (keys (:ours r)))))
    (is (empty? (:ours (pm/three-way (pm/program (base) #{}) (pm/program o #{}) (pm/program (base) #{})))))))

(def ^:private facts-path "resources/seon/schemas/seon.facts.edn")

(defn- registered
  "db after registering form v as schema k on it, as my.schema/register! does."
  [db k v] (d/db-with db (:tx (schema/registration db k v (= "user" (namespace k))))))

(deftest a-merge-declares-the-attributes-its-datoms-use-before-them
  ;; found 2026-10-27 through 0.7 (agent a1, (my.branch/merge-main!) on the standing root): main had
  ;; gained datoms of :form/ops, and the agent's branch was cut before the attribute: Datahike refused
  ;; the datoms (Bad entity attribute :form/ops, not defined in current schema). A merge's tx-data
  ;; declares, first, every attribute its datoms use that the target's schema lacks, read from the
  ;; registry, not only for the schema keys merged
  (let [full (empty-db)
        schema (into {} (map (fn [[k v]] [k (dissoc v :db/id)])) (dissoc (d/schema full) :form/ops))
        onto (datahike.core/empty-db schema {:schema-flexibility :write})
        datoms [{:e 100 :a :form/ops :v :seon.op/x}]
        decls (pm/attribute-declarations onto datoms)]
    (is (= [:form/ops] (mapv :db/ident decls)) "the one attribute the branch lacks")
    (is (= [] (pm/attribute-declarations full datoms)) "a branch that has it declares nothing")
    (is (thrown? Exception (d/db-with onto [[:db/add 100 :form/ops :seon.op/x]])) "without it the datom is refused")
    (is (= #{:seon.op/x} (set (map :v (d/datoms (d/db-with onto (into decls [[:db/add 100 :form/ops :seon.op/x]])) :eavt 100 :form/ops))))
        "declared first, the datom lands")))

(deftest a-merge-base-cut-before-an-attribute-diffs-it-as-the-heads
  ;; found 2026-10-27 through 0.7 (agent a1, merge-main! after the merge above declared :form/ops): the
  ;; content diff read every attribute either side declares from a base cut before :form/ops, and
  ;; datahike.db/datoms-diff resolved it against the base only (not defined in current schema)
  (let [full (empty-db)
        base (datahike.core/empty-db (into {} (map (fn [[k v]] [k (dissoc v :db/id)])) (dissoc (d/schema full) :form/ops))
                                     {:schema-flexibility :write})
        head (d/db-with base (into (pm/attribute-declarations base [{:e 100 :a :form/ops :v :seon.op/x}]) [[:db/add 100 :form/ops :seon.op/x]]))]
    (is (= [[100 :form/ops :seon.op/x]]
           (mapv (juxt :e :a :v) (filter #(= :form/ops (:a %)) (#'pm/content-datoms base head #{}))))
        "the head's datoms of the attribute the base lacks are what moved")))

(deftest a-schema-entry-merges-per-key
  ;; task seon.source/schemas-are-part-of-the-program: a schema entry is a program key,
  ;; [:schema k], merged like a var (v1's [:seon.schema/key k] identity), and the merge's
  ;; changed resource is written back with only its entry changed
  (let [text (slurp facts-path)
        main (let [db (empty-db)] (d/db-with db (source/import-tx db {facts-path text})))
        ;; the entry's line as the file has it (a property added to the entry moves with it), and
        ;; its form with :paused added, read from that text
        status-line (some #(when (str/starts-with? % " :seon.run/status ") %) (str/split-lines text))
        status (edn/read-string (str/replace (subs status-line (count " :seon.run/status ")) ":closed :refused]]" ":closed :refused :paused]]"))
        ours (-> main (registered :seon.run/status status) (registered :user/qty :int))
        theirs (registered main :seon.run/pid [:int {:min 1}])
        prog #(pm/program % #{})
        r (pm/three-way (prog main) (prog ours) (prog theirs))]
    (is (= [[:schema :seon.run/status]] (keys (:ours r)))
        "the branch's change is its key; a scratch key is no program")
    (is (= {:form (binding [*print-namespace-maps* false] (sedn/encode status)) :file facts-path} (second (get (:ours r) [:schema :seon.run/status]))))
    (is (= [[:schema :seon.run/pid]] (:apply r)) "a key only the other side changed applies")
    (is (= [] (:conflicts r)) "different keys of one resource do not conflict")
    (testing "one key changed two ways conflicts at its key"
      (let [r2 (pm/three-way (prog main) (prog ours) (prog (registered main :seon.run/status [:enum :running])))]
        (is (= [[:schema :seon.run/status] :both-modified] ((juxt :key :kind) (first (:conflicts r2)))))))
    (testing "merged into main: the entry is main's, no longer the binary's, and write-back changes only its bytes"
      (let [main2 (d/db-with main (pm/merge-tx main main ours (keys (:ours r)) #{}))]
        (is (= #{:seon.run/status} (set (keys (program/schema-changes main2)))))
        (is (= [facts-path] (source/changed-files main2 (:ours r))))
        (is (= {facts-path (str/replace text status-line (str/replace status-line ":closed :refused]]" ":closed :refused :paused]]"))}
               (source/files main2 (source/changed-files main2 (:ours r)))))))))

(deftest the-delta-three-way-reads-data-and-schema-entries-as-the-program-does
  ;; declared data (card one and many) and schema entries, besides forms and definitions:
  ;; delta-three-way, read only where the dbs differ, answers what three-way does
  (let [b (base)
        task (d/q '[:find ?e . :where [?e :task/name :app.m0/tidy]] b)
        ref (:db/id (first (:task/refs (d/entity b task))))
        o (with b [{:note/text "ours"} [:db/add task :task/status :done]]
                [{:schema/key :x/y :schema/form "[:int]" :schema/file "x.edn"}])
        t (with b [[:db/retract task :task/refs ref] [:db/add task :task/plan "theirs"]] (var-tx "g" "(dec x)"))]
    (doseq [da [#{} :all #{:task/status :task/refs}]]
      (let [full (pm/three-way (pm/program b da) (pm/program o da) (pm/program t da))]
        (is (= full (pm/delta-three-way b o t da)) (pr-str da))
        (is (seq (:ours full)))))
    (is (= (pm/changes (pm/program b :all) (pm/program o :all)) (pm/delta-changes b o :all)))))

(deftest divergence-names-each-change-a-merge-carries
  ;; owner D4 (2026-10-22): the agent sees, every turn, every change its branch's merge will
  ;; carry back to main: what it added, redefined and removed, and the schemas it registered,
  ;; projected from the one diff a turn reads (seon.agent/branch-changes); a schema registered
  ;; with no file is a scratch one, never merged, so it is none of them
  (let [b (with (base) [{:schema/key :app/k :schema/form ":int" :schema/file "app.edn"}])
        branch (with b (var-tx "f" "(inc x)") (unmap "g") (var-tx "c" "x")
                     [{:schema/key :app/k :schema/form ":string" :schema/file "app.edn"} {:schema/key :app/n :schema/form ":int" :schema/file "app.edn"}
                      {:schema/key :app/scratch :schema/form ":int"}])
        ch (pm/delta-changes b branch #{})]
    (is (= {:added ['app/c] :changed ['app/f] :removed ['app/g] :schemas [:app/k :app/n]} (pm/divergence ch)))
    (is (= #{'app/c 'app/f 'app/g} (pm/changed-vars ch)) "the changed vars are its vars")
    (is (= ch (pm/delta-changes b branch #{} (apply concat (d/datoms-diff b branch :eavt [])))) "given the datoms that differ, nothing is diffed again and the changes are the same")
    (is (= {} (pm/divergence (pm/delta-changes b b #{}))) "an unchanged branch carries nothing")))

(deftest a-branch-that-merged-main-carries-only-its-own-changes
  ;; root, 2026-10-22: entity ids are a branch's own, and a merge applies main's forms by
  ;; content under the branch's ids; against its merge base (main's head once merged), the
  ;; branch carries only what it changed itself, never main's changes
  (let [main (with (base) (var-tx "f" "(inc x)") (var-tx "m" "x"))
        ;; the branch writes entities of its own, adds k, then merges main: main's forms by content
        merged (with (base) [{:db/id "x0" :form/id "(comment 0)" :form/src "x"}] (var-tx "k" "x") (var-tx "f" "(inc x)") (var-tx "m" "x"))]
    (is (= {:added ['app/k]} (pm/divergence (pm/delta-changes main merged #{}))) "only k, the branch's own")
    (is (= #{'app/k} (pm/changed-vars (pm/delta-changes main merged #{}))) "and the tests it reaches are k's")))

(deftest a-merge-carries-the-branchs-own-datoms
  ;; a verbatim merge (seon.merge/merge-tx): main gains exactly the datoms the branch wrote
  ;; for the keys that merge, with their own entity ids and tx ids, and nothing else of its
  ;; own; a card-one change carries the branch's retraction, so main writes no extra one
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (keyword (str (name (:branch cfg)) "-v"))
           _ (d/transact conn ((var-tx "f" "x") @conn))
           _ (d/branch! conn (:branch cfg) b)
           bc (d/connect (assoc cfg :branch b))]
       (try
         (d/transact bc ((var-tx "n" "1") @bc))
         (d/transact bc ((var-tx "f" "(inc x)") @bc))
         (d/transact bc [{:note/text "a platform-free scratch note"}])
         (let [base (pdb/branch-base conn (:branch cfg) b) head @bc main @conn
               r (pm/delta-three-way base head main #{})
               ks (keys (:ours r))
               tx (pm/merge-tx main base head ks #{})
               rep (d/merge-db conn #{b} tx (pdb/tx-meta (:branch cfg)))
               after (:db-after rep)
               merge-t (:max-tx after)
               own (remove #(= merge-t (:tx %)) (:tx-data rep))
               carried (set (map (juxt :e :a :v :tx :added) tx))]
           (testing "main's program at the merged keys is the branch's"
             (is (= (pm/program-of head ks #{}) (pm/program-of after ks #{}))))
           (testing "every datom main gained besides the merge's own is one the branch wrote, ids and tx kept"
             (is (seq own))
             (is (every? #(carried ((juxt :e :a :v :tx :added) %)) own)))
           (testing "each new form is one entity on both sides"
             (is (= (pdb/eid head :var/name 'app/n) (pdb/eid after :var/name 'app/n))))
           (testing "the scratch note stays on the branch"
             (is (empty? (d/datoms after :avet :note/text "a platform-free scratch note")))))
         (finally (d/release bc) (d/delete-branch! conn b)))))))

(deftest changed-vars-are-the-var-keys-of-a-change
  ;; found red on main (2026-10-27): it read each key [kind x] one level too deep, so any branch
  ;; that changed something threw "nth not supported on this type: Keyword" in every turn's test plan
  (is (= #{'app/f 'app/g} (pm/changed-vars {[:var 'app/f] [nil {:kind :fn}] [:var 'app/g] [{:kind :fn} nil] [:schema :app/k] [nil {}]})))
  (is (= #{} (pm/changed-vars {}))))
(deftest a-merge-carries-the-namespace-of-a-form-it-carries
  ;; task seon.root/a-behind-merge-is-bounded-and-merges-main-first (gap 3): a form in a namespace the
  ;; target lacks was carried with its :form/ns ref but not the namespace entity it names, so the form read
  ;; :ns nil there and the target's program differed from the branch's at that key for ever (the writer's
  ;; behind check never settled). The merge carries the namespace entity too.
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (keyword (str (name (:branch cfg)) "-ns"))
           _ (d/transact conn ((var-tx "f" "x") @conn))
           _ (d/branch! conn (:branch cfg) b)
           bc (d/connect (assoc cfg :branch b))
           newns (fn [db] (program/record-tx db {:id "app.z/(defn z [x] x)" :src "(defn z [x] x)" :ns 'app.z :ns-form? false
                                                  :defs [{:var 'app.z/z :kind :fn :contract "[:=> [:cat :int] :int]"}]
                                                  :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}}))]
       (try
         (d/transact bc (newns @bc))
         (let [base (pdb/branch-base conn (:branch cfg) b) head @bc main @conn
               r (pm/delta-three-way base head main #{})
               ks (keys (:ours r))
               after (:db-after (d/merge-db conn #{b} (pm/merge-tx main base head ks #{}) (pdb/tx-meta (:branch cfg))))]
           (is (some #(= :form (first %)) ks) "the form is a key to carry")
           (is (= (pm/program-of head ks #{}) (pm/program-of after ks #{})) "main reads the branch's program at those keys, :ns included")
           (is (= 'app.z (:ns (val (first (filter #(= :form (first (key %))) (pm/program-of after ks #{})))))) "the form's namespace")
           (is (= {} (:ours (pm/delta-three-way head after main #{}))) "and nothing differs between the two programs any more"))
         (finally (d/release bc) (d/delete-branch! conn b)))))))

(deftest a-namespace-both-sides-made-is-one-entity-after-the-merge
  ;; the branch and main each made app.z without an (ns) form, so they hold it under two entity ids: carrying the
  ;; branch's would give :ns/name two entities; the merge keeps main's and points the carried form at it
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (keyword (str (name (:branch cfg)) "-ns2"))
           _ (d/transact conn ((var-tx "f" "x") @conn))
           _ (d/branch! conn (:branch cfg) b)
           bc (d/connect (assoc cfg :branch b))
           rec (fn [nm] (fn [db] (program/record-tx db {:id (str "app.z/(defn " nm " [x] x)") :src (str "(defn " nm " [x] x)") :ns 'app.z :ns-form? false
                                                        :defs [{:var (symbol "app.z" nm) :kind :fn :contract "[:=> [:cat :int] :int]"}]
                                                        :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})))]
       (try
         (d/transact conn ((rec "m") @conn))
         (d/transact bc ((rec "z") @bc))
         (let [base (pdb/branch-base conn (:branch cfg) b) head @bc main @conn
               r (pm/delta-three-way base head main #{})
               ks (keys (:ours r))
               after (:db-after (d/merge-db conn #{b} (pm/merge-tx main base head ks #{}) (pdb/tx-meta (:branch cfg))))]
           (is (= 1 (count (d/datoms after :avet :ns/name 'app.z))) "one entity for the namespace")
           (is (= 'app.z (:ns (val (first (filter #(= :form (first (key %))) (pm/program-of after ks #{})))))) "the carried form reads it"))
         (finally (d/release bc) (d/delete-branch! conn b)))))))
