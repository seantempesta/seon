(ns seon.merge-test
  "The one merge (seon.merge/decide and carry, seon.writer/candidate) over db values: the case table,
  identity by content (identical definitions on two branches merge clean whatever their
  entity ids and places), an unmap merging as a delete of the definition and of the form
  it leaves dead, the latest redefinition winning, scratch data and observations never
  merging, unmerged entries derived from the branch, and behind meaning main's program
  moved."
  (:require [seon.test-store :as ts] [my.test :as mt] [seon.proofs :as proofs] [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.merge :as pm]
            [seon.program :as program] [clojure.string :as str] [seon.doc] [seon.config] [seon.source] [seon.profile :as profile] [seon.task]
            [seon.edn :as sedn] [seon.contract :as contract] [malli.core :as m] [malli.generator :as mg]
            [seon.source :as source] [seon.writer]
            [clojure.string :as str] [clojure.edn :as edn]))

(defn- empty-db [] (ts/test-db))

(defn- defn-src [nm body] (str "(defn " nm " {:malli/schema [:=> [:cat :int] :int]} [x] " body ")"))

(defn- fid [nm body] (str "app/" (defn-src nm body)))

(defn- var-tx
  "A recorded definition of app/nm (as seon.program/record-tx writes it): a fn of the db."
  [nm body]
  (fn [db]
    (program/record-tx db {:id (fid nm body) :src (defn-src nm body) :ns 'app :ns-form? false
                           :defs [{:var (symbol "app" nm) :meta #{:arglists} :contract "[:=> [:cat :int] :int]"}]
                           :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})))

(defn- with [db & txs] (reduce (fn [db t] (d/db-with db (if (fn? t) (t db) t))) db txs))

;; a function, not a def: a test namespace loads without effects (it is loaded where main's
;; program runs, the fork server included, which opens no store)
;; the world, on a db that keeps history (docs/testing.md#testing-in-the-world): cases need f, g, h beside a small program;
;; the store-shaped test db cost 441 M ticks for the case table, the world 77 M
(def ^:private ^:seon.test/fixture base-world
  "The cases' world, built once a process (a fixture, forced before any test, never inside the first test's budget)."
  (delay (with (mt/world {:history? true}) (var-tx "f" "x") (var-tx "g" "x") (var-tx "h" "x"))))

(defn- base [] @base-world)

(defn- unmap [nm]
  (fn [db] (let [e (:db/id (d/entity db [:var/name (symbol "app" nm)]))]
             [[:db/retract e :var/form (:db/id (:var/form (d/entity db e)))]])))

(defn- one-merge
  "The one merge (seon.writer/candidate) of main t into branch o, both from (base): {:conflicts} or {:db merged ...}."
  [o t] (seon.writer/candidate t (base) o {}))
(defn- form-key "The merge key of app/nm's definition on db: its var's :var/form." [db nm] [:datum (:db/id (d/entity db [:var/name (symbol "app" nm)])) :var/form])
(defn- form-conflicts "The conflicts of merging main t into branch o (seon.merge/decide) at a var's definition." [o t]
  (filterv #(= :var/form (nth (:key %) 2)) (:conflicts (pm/decide (base) o t))))
(defn- sides [c] (set (filter #(contains? c %) [:base :ours :theirs])))
(defn- vk [nm] [:var (symbol "app" nm)])
(defn- defs-of "The definition keys among ks (a form's own key follows its definition)." [ks] (filterv #(not= :form (first %)) ks))

;; Estimate: the world (a fixture) + 6 small dbs ~1.5M + 3 candidates (decide, carry and replay of 1 to 3 definitions, ~0.5M each) ~1.5M
;; + 5 decides ~0.5M + one delta-changes ~1.6M = 5.5M, 7000000 for the db builds counted loosely. Each class of the table is one merge with the three kinds of change at once.
(deftest ^{:seon.test/estimate {:ticks 7000000}} three-way-cases
  ;; the case table, on the one merge (seon.writer/candidate): the merged program is the side that changed, or both's when alike
  (let [b (base)
        all-three (with b (var-tx "n" "1") (var-tx "f" "(dec x)") (unmap "g")) ; an add, a modify and a delete
        f-inc (with b (var-tx "f" "(inc x)")) f-dec (with b (var-tx "f" "(dec x)"))
        n1 (with b (var-tx "n" "1")) n2 (with b (var-tx "n" "2")) f-gone (with b (unmap "f"))
        alike (fn [o t] (let [r (one-merge o t)] (and (nil? (:conflicts r)) (= (pm/program t #{}) (pm/program (:db r) #{})))))]
    (testing "o = t: nothing to do (an identical change)" (is (alike all-three all-three)))
    (testing "o = b: theirs applies (an add, a modify and a delete)" (is (alike b all-three)))
    (testing "t = b: ours kept, and it is an ours-change"
      (let [r (one-merge f-dec b)]
        (is (nil? (:conflicts r))) (is (= (pm/program f-dec #{}) (pm/program (:db r) #{})))
        (is (= [(vk "f")] (defs-of (keys (pm/delta-changes b f-dec #{})))))))
    (testing "a conflict holds the sides that have the key, and an absent side is one that never had it or deleted it"
      (are [o t ss] (= [ss] (map sides (form-conflicts o t)))
        f-inc f-dec #{:base :ours :theirs}
        n1 n2 #{:ours :theirs}
        f-gone f-dec #{:base :theirs}
        f-inc f-gone #{:base :ours})
      (let [c (first (form-conflicts f-inc f-dec))]
        (is (= (form-key f-inc "f") (:key c)))
        (is (nil? (:db (one-merge f-inc f-dec))) "a conflict merges nothing")
        (is (= (fid "f" "x") (:form/id (d/entity b (:base c)))))
        (is (= (fid "f" "(inc x)") (:form/id (d/entity f-inc (:ours c)))))
        (is (= (fid "f" "(dec x)") (:form/id (d/entity f-dec (:theirs c)))))))))

(deftest uncontracted-is-what-the-branch-changed
  ;; one question for the writer's merge gate and root's judge: which functions the branch's
  ;; own changes (its delta-changes since base) define without a contract
  (let [ours {[:var 'app/f] [nil {:meta #{:arglists}}]
              [:var 'app/g] [nil {:meta #{:arglists} :contract "[:=> [:cat] :int]"}]
              [:var 'app/v] [nil {:kind :def}]
              [:var 'app/e] [{:meta #{:arglists}} {:meta #{:arglists}}]}]
    (is (= '[app/e app/f] (pm/uncontracted ours)))
    (is (= [] (pm/uncontracted {}))))
  (testing "over recorded programs: a branch that adds an uncontracted fn and edits a contracted one"
    (let [u (fn [db] (program/record-tx db {:id "app/(defn u [x] x)" :src "(defn u [x] x)" :ns 'app :ns-form? false
                                            :defs [{:var 'app/u :meta #{:arglists}}]
                                            :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}}))]
      (is (= '[app/u] (pm/uncontracted (pm/delta-changes (base) (with (base) u (var-tx "f" "(inc x)")) #{})))))))

(deftest identical-defs-on-two-branches-merge-clean
  ;; one side makes an unrelated entity first; app/n is still one entity on both, at its
  ;; content id (docs/storage.md#ids)
  (let [o (with (base) [{:note/text "scratch" :note/agent :t}] (var-tx "n" "(* 2 x)"))
        t (with (base) (var-tx "n" "(* 2 x)"))]
    (is (= (d/q '[:find ?e . :where [?e :var/name app/n]] o) (d/q '[:find ?e . :where [?e :var/name app/n]] t)))
    (let [r (one-merge o t)]
      (is (nil? (:conflicts r)) (pr-str (:conflicts r)))
      (is (= 1 (count (d/datoms (:db r) :avet :var/name 'app/n))) "one definition after the one merge"))))

;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: md-scan 29's branch held batch 42's code and
;; the morning's wiki, so a catch main's wiki named read as one the branch unnamed, and held its done
(deftest a-branch-takes-mains-wiki
  (let [page (fn [t] (str "# P\n\nIt says " t ".\n\n## Kept\n\nIt stays.\n"))
        load (fn [db pages] (d/db-with db (seon.doc/load-tx db "demo" pages)))
        b (load (base) {"demo/p.md" (page "one") "demo/gone.md" "# Gone\n\nIt goes.\n" "demo/both.md" "# Both\n\nBase.\n"})
        theirs (-> b
                   (load {"demo/p.md" (page "two") "demo/new.md" "# New\n\nIt is new.\n" "demo/both.md" "# Both\n\nMain's.\n"})
                   (#(d/db-with % (for [x (d/datoms % :avet :seon.doc/address "demo/gone.md#gone")] [:db/retractEntity (:e x)]))))
        own (with b (var-tx "n" "1"))
        ours (load own {"demo/both.md" "# Both\n\nThe branch's.\n"})
        ;; the one merge (seon.writer/candidate): main's head plus the branch's own change since b
        merged (:db (seon.writer/candidate theirs b own {}))
        addrs #(set (map :v (d/datoms % :avet :seon.doc/address)))]
    (is (= "It says two." (:seon.doc/text (d/entity merged [:seon.doc/address "demo/p.md#p"]))) "main's edit overwrites the branch's copy")
    (is (= (addrs theirs) (addrs merged)) "main's new page reaches the branch, and its removed one leaves it")
    (is (some? (d/entity merged [:var/name 'app/n])) "the branch's own work stays")
    (let [cs (:conflicts (pm/decide b ours theirs))]
      (is (= #{[:seon.doc/address "demo/both.md#both"]} (set (map :entity cs))) (str "a section both changed is a conflict, as a var is: " (pr-str cs)))
      (is (nil? (:db (seon.writer/candidate theirs b ours {}))) "and nothing merges: the branch keeps its own until it resolves it"))
    (is (= [] (:txs (pm/carry theirs theirs theirs))) "a branch holding main's wiki takes nothing")))

;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: a branch's own doc edit merges as code does, and its
;; page is written back with the merge (seon.source/changed-files names it, seon.doc/files draws it), so the next load of
;; docs/ reads what merged (task seon.doc/any-markdown-file-is-docs-it-writes-back)
(deftest a-branch-doc-edit-merges-and-its-page-is-written-back
  (let [load (fn [db pages] (d/db-with db (seon.doc/load-tx db "demo" pages)))
        b (load (base) {"demo/p.md" "# P\n\nOne.\n" "demo/q.md" "# Q\n\nUntouched.\n"})
        edited (load b {"demo/p.md" "# P\n\nTwo.\n"})
        ch (pm/delta-changes b edited #{})
        merged (:db (seon.writer/candidate b b edited {}))
        paths (seon.source/changed-files merged ch)]
    (is (nil? (#'seon.writer/merge-refused b edited {:ours ch})) "the writer takes it")
    (is (= ["demo/p.md"] paths) "the merge writes the page its doc changed, and only it")
    (is (= {"demo/p.md" "# P\n\nTwo.\n"} (seon.doc/files merged paths)) "as the merged store draws it: the next load reads it back unchanged")
    (is (= [] (seon.doc/load-tx merged "demo" (seon.doc/files merged paths))) "loading what was written writes nothing")))

(deftest a-config-entry-merges-per-key
  ;; docs/source.md#a-program-file: an entry of the config file is a key of its own: two branches changing two keys merge clean, one key changed two ways conflicts at it
  (let [p seon.config/shipped-file
        main (let [db (empty-db)] (d/db-with db (seon.source/import-tx db {p "{:x/a 1\n :x/b 2}\n"})))
        set-k (fn [db k v] (d/db-with db [{:config/key k :config/form v :config/file p}]))
        ours (set-k main :x/a "5")
        ch (pm/delta-changes main ours #{})
        ;; the one merge (seon.writer/candidate) of a main that changed the other key
        r (seon.writer/candidate (set-k main :x/b "6") main ours {})
        form-of (fn [db k] (:config/form (d/entity db [:config/key k])))]
    (is (= [[:config :x/a]] (keys ch)) (pr-str ch))
    (is (= ["5" "6"] [(form-of (:db r) :x/a) (form-of (:db r) :x/b)]) "each side's key merges")
    (is (nil? (:conflicts r)))
    (is (= {:config [:x/a]} (pm/divergence ch)))
    (is (= [[:config/key :x/a] #{:base :ours :theirs}] ((juxt :entity sides) (first (:conflicts (pm/decide main ours (set-k main :x/a "7")))))))
    (let [merged (:db (seon.writer/candidate main main ours {}))
          paths (seon.source/changed-files merged ch)]
      (is (= [p] paths))
      (is (= {p "{:x/a 5\n :x/b 2}\n"} (seon.source/files merged paths)) "a merged entry is what the write-back writes"))))

(deftest a-script-merges-per-key
  ;; docs/source.md#a-program-file: a script is a file of one entry, [:script path], merged like a var with its text and mode; a schema resource's file bears no such key
  (let [text "#!/bin/sh\n# bin/x says hello\necho 1\n"
        main (let [db (empty-db)] (d/db-with db (seon.source/import-tx db {"bin/x" text})))
        main (d/db-with main (seon.source/modes-tx main {"bin/x" "100755"}))
        edit (fn [db t] (d/db-with db (seon.source/file-tx "bin/x" t)))
        ours (edit main "#!/bin/sh\necho 2\n")
        theirs (edit main "#!/bin/sh\necho 3\n")
        ch (pm/delta-changes main ours #{})]
    (is (= {[:script "bin/x"] [{:file "bin/x" :text text :mode "100755"} {:file "bin/x" :text "#!/bin/sh\necho 2\n" :mode "100755"}]} ch) (pr-str ch))
    (is (= {:scripts ["bin/x"]} (pm/divergence ch)))
    (is (= [[:file/path "bin/x"] #{:base :ours :theirs}] ((juxt :entity sides) (first (filter #(= :file/text (nth (:key %) 2)) (:conflicts (pm/decide main ours theirs))))))
        "both sides changing it is an unmerged entry")
    (is (= "" (:seon.script/says (d/entity (:db (seon.writer/candidate main main ours {})) [:file/path "bin/x"]))) "what it derives rides with it")
    (let [p "resources/seon/schemas/x.edn"
          db (let [db (empty-db)] (d/db-with db (seon.source/import-tx db {p "{:x/a :int}\n"})))
          moved (d/db-with db [{:file/path p :file/text "{:x/a :int} \n"}])]
      (is (= {} (pm/delta-changes db moved #{})) "a schema resource's file bears no key: its entries do"))))

(deftest unmap-merges-as-delete
  ;; the one merge (seon.writer/candidate) carries the branch's retraction of the definition
  (let [ours (with (base) (unmap "g"))
        main' (:db (one-merge ours (base)))]
    (is (= #{(vk "g") [:form (fid "g" "x")]} (set (keys (pm/delta-changes (base) ours #{})))) "the definition, and the form it leaves dead")
    (is (nil? (seon.doc/source-of main' 'app/g)))
    (is (not (program/live? main' (pdb/eid main' :form/id (fid "g" "x")))) "the form is dead on main, as on the branch")
    (is (= [#{:base :theirs}] (map sides (form-conflicts ours (with (base) (var-tx "g" "(dec x)"))))))))

;; docs/merging.md#a-branch-merges-main-in: md-scan 29's branch held the results of a test main deleted,
;; on an entity with no :var/name, and its next test run threw on the nil test key
(deftest a-var-main-deletes-leaves-no-facts-on-the-branch
  (let [e #(:db/id (d/entity % [:var/name 'app/g]))
        ours (with (base) (fn [db] [{:db/id (e db) :seon.test/digest "d1" :seon.test/status :green}]))
        theirs (with (base) (fn [db] [[:db/retractEntity (e db)]]))
        ;; the one merge (seon.writer/candidate): main's delete whole takes the branch's facts of the var with it
        merged (:db (one-merge ours theirs))]
    (is (nil? (d/entity merged [:var/name 'app/g])) "the var is gone, as on main")
    (is (empty? (d/q '[:find ?t :where [?t :seon.test/digest] (not [?t :var/name])] merged))
        "no test facts stay on an entity whose name main retracted")))

(deftest latest-redefinition-wins
  (let [ours (with (base) (var-tx "f" "1") (var-tx "f" "2") (var-tx "f" "3"))]
    (is (= (fid "f" "3") (:form (second (get (pm/delta-changes (base) ours #{}) (vk "f"))))))
    (is (= [(defn-src "f" "3")] (for [[[k] [_ o]] (pm/delta-changes (base) ours #{}) :when (and (= :form k) o)] (:src o)))
        "only the last form is live: each redefinition superseded the one before")
    (is (= (fid "f" "3") (:form/id (:var/form (d/entity (:db (one-merge ours (base))) [:var/name 'app/f])))) "and the one merge carries the last")))

(deftest a-form-s-place-does-not-make-it-another
  (let [o (with (base) [{:note/text "first" :note/agent :t}] (var-tx "n" "(* 2 x)"))
        t (with (base) (var-tx "n" "(* 2 x)"))]
    (is (not= (get-in (pm/program o #{}) [[:form (fid "n" "(* 2 x)")] :order])
              (get-in (pm/program t #{}) [[:form (fid "n" "(* 2 x)")] :order])))
    (is (nil? (:conflicts (pm/decide (base) o t))) "the one merge finds no conflict")))

(deftest scratch-and-observations-never-merge
  (let [ours (with (base) [{:note/text "scratch" :note/agent :a1}
                         ;; the agent once, by its identity, under the eval's :by: two maps of one content-id identity in one write
                         ;; resolve to two ids (a Datahike finding, sent to Deep fixes)
                         {:seon.eval/id "e1" :seon.eval/by {:seon.agent/key "a1" :seon.process/pid 7} :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e1 :value 3}}
                         {:task/name :x/y :task/status :done}])]
    ;; they are no change of the program (delta-changes); the one merge carries every fact (owner, 2026-11-14), so they are kept
    ;; out of the program, not out of the merge
    (is (= (pm/program (base) #{}) (pm/program ours #{})))
    (is (empty? (pm/delta-changes (base) ours #{})))))

(deftest unmerged-entry-derivation
  (let [o (with (base) (var-tx "f" "(inc x)"))
        t (with (base) (var-tx "f" "(dec x)"))
        cs (form-conflicts o t)
        k (form-key o "f")
        b1 (d/db-with o (pm/unmerged-tx o cs 42))
        unmerged (fn [db] (mapv :key (pm/unmerged db)))]
    (are [db expected] (= expected (unmerged db))
      ;; written, ours unchanged: unmerged
      b1 [k]
      ;; re-defn of the var: resolved by derivation
      (with b1 (var-tx "f" "(+ x 0)")) []
      ;; resolve! keeping ours: marked
      (d/db-with b1 (pm/resolve-tx b1 k)) [])
    (is (= 42 (:main-t (first (pm/entries b1)))))
    (testing "a run's conflict evidence (the same members, no :main-t) is not an entry"
      (is (= [k] (mapv :key (pm/entries (d/db-with b1 [(pm/conflict-tx (first cs))]))))))
    (testing "a second conflict on the same key replaces the entry"
      (let [b2 (d/db-with b1 (pm/unmerged-tx b1 cs 43))]
        (is (= [43] (map :main-t (pm/entries b2))))))))

(deftest a-conflict-value-reads-a-pulled-entry
  ;; its input is a merge entry's members
  ;; a conflict as seon.merge/decide answers it, its :entity (a name to show) left out, as the writer leaves it out of an entry
  (let [c (dissoc (first (form-conflicts (with (base) (var-tx "f" "(inc x)")) (with (base) (var-tx "f" "(dec x)")))) :entity)
        db (d/db-with (base) [(pm/conflict-tx c)])
        e (d/q '[:find ?e . :where [?e :seon.merge/key]] db)]
    (is (= c (pm/conflict-value (pm/conflict-tx c))) "the inverse of conflict-tx")
    (is (= c (pm/conflict-value (d/pull db '[*] e))) "a pulled entry")))
;; the generative check of conflict-value's contract (every input it generates states a conflict) is the per-function key
;; mg/seon.merge/conflict-value (docs/checks.md#the-generative-check-is-per-function), run for every contracted function: not run again
;; here (100 cases, ~15M ticks)

(deftest behind-means-main-program-moved
  (let [observed (with (base) [{:seon.agent/key "root" :seon.process/pid 9} {:note/text "run facts" :note/agent :t}])]
    ;; main's program change since base (delta-changes, the main side of seon.merge/readiness)
    (is (empty? (pm/delta-changes (base) observed #{})))
    (is (seq (pm/delta-changes (base) (with (base) (var-tx "f" "(dec x)")) #{})))))

;; Estimate: the world 0.5M (cached) + 3 small dbs + one candidate (decide, carry and replay of the branch's one definition ~2M)
;; + one delta-changes at :all over one task's keys ~1M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} platform-records-merge-as-facts
  ;; owner, 2026-11-14: "If there are no conflicts I don't see any reason to not merge everything": root's records on main
  ;; (boot, quiescence, recorded errors) are facts a branch takes by their [:datum e a] keys, as any data; a task's content is its key
  (let [rooted (with (base) [{:seon.system/id :root :seon.system/quiescent {:t 9 :root-causes []}}])
        tasked (with (base) [{:seon.task/id "t/x" :seon.task/plan "better"}])
        ;; the one merge (seon.writer/candidate) of main's record into a branch with a change of its own
        merged (:db (one-merge (with (base) (var-tx "f" "(inc x)")) rooted))]
    (is (= {:t 9 :root-causes []} (:seon.system/quiescent (d/entity merged [:seon.system/id :root]))) "main's record is a change the branch takes")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "beside the branch's own")
    (is (some #{[:task "t/x"]} (keys (pm/delta-changes (base) tasked :all))) "a task's content is its [:task id] key")))

;; Estimate: the world 0.5M (cached) + 1 small db + 2 delta-changes of one entity ~1M + one candidate of 2 facts ~2M = 4000000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} declared-data-merges-by-key
  ;; the data part: every fact that is no program, no derived value and no transaction's own merges by its [:datum e a] key
  ;; (the one merge, seon.writer/candidate)
  (let [o (with (base) [{:db/id 900 :note/text "n" :note/agent :a1}])]
    (is (= #{[:datum 900 :note/text] [:datum 900 :note/agent]} (set (keys (pm/delta-changes (base) o :all)))))
    (is (empty? (pm/delta-changes (base) o #{})))
    (is (= ["n" :a1] ((juxt :note/text :note/agent) (d/entity (:db (one-merge o (base))) 900))) "the one merge carries them")))

(def ^:private facts-path "resources/seon/schemas/seon.facts.edn")

(defn- registered
  "db after registering form v as schema k on it, as my.schema/register! writes it (the entry, in the shipped resource unless k is a
  scratch `user` key, and the declaration of a bare key: seon.program/own-declaration), without its judging of the whole population,
  which my.schema's own tests prove: a merge reads the entry, not the registration."
  [db k v]
  (let [entry (cond-> {:schema/key k :schema/form (binding [*print-namespace-maps* false] (sedn/encode v))}
                (not= "user" (namespace k)) (assoc :schema/file facts-path))
        with-entry (d/db-with db [entry])]
    (d/db-with with-entry (program/own-declaration db k (program/schema-registry (program/schema-changes with-entry))))))

(deftest a-merged-schema-entry-declares-its-own-attribute
  ;; docs/storage.md#the-write-checks-every-datom: a key registered bare (an agent's own datum, no entity map lists it) is an attribute on the
  ;; branch that registers it; merged, its entry arrived without its declaration, so main could not store what the branch could
  ;; the one merge (seon.writer/candidate) of the branch into a main unmoved since its base
  (let [base (empty-db)
        src (registered base :app.shape/size :int)
        onto (:db (seon.writer/candidate base base src {}))]
    (is (= :db.type/long (:db/valueType (get (d/schema onto) :app.shape/size))) "the merge carries its declaration")
    (is (= 3 (:app.shape/size (d/entity (d/db-with onto [{:db/id 900 :app.shape/size 3}]) 900))) "and the target can store it")))

(deftest a-merged-entry-imported-from-a-file-compiles-on-a-binary-without-it
  ;; docs/source.md#an-agent-changes-a-schema: real run 9, 3 of 5 agents closed. A key the running binary doesn't hold, imported onto main
  ;; from a file, has to compile when a branch merges it, as a registered key does; red when an imported entry was taken for the binary's.
  ;; The branch declares it as a registration does (seon.program/own-declaration; an import that leaves a bare key undeclared is
  ;; task seon.source/an-imported-bare-key-is-declared), and the one merge carries the branch's declaration (seon.writer/replay)
  (let [base (empty-db)
        ftx (source/file-tx "resources/seon/schemas/app.shape.edn" "{:app.shape/size :int}")
        decl (program/own-declaration base :app.shape/size (program/schema-registry (program/schema-changes (d/db-with base ftx))))
        src (d/db-with base (into decl ftx))
        onto (:db (seon.writer/candidate base base src {}))]
    (is (= :db.type/long (:db/valueType (get (d/schema onto) :app.shape/size))) "the merge carries the branch's declaration")
    (is (= #{:app.shape/size} (set (keys (program/schema-changes onto)))) "and the branch says the binary lacks it")))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-schema-entry-the-source-deleted-merges-as-its-deletion
  ;; estimate: an empty test db (~1M), two small imports (~0.5M), the one merge's decide, carry and replay of the entry's retraction
  ;; and the file's text (~2M), schema-changes (~0.5M)
  ;; docs/merging.md#a-branch-merges-main-in: main deleted :seon.config.proofs/root-item-workers from its
  ;; file, and context's branch could not merge main: the old merge asked the merged registry, which no longer holds the key, for its
  ;; declaration, and threw :malli.core/invalid-schema (measured 2026-11-14 on a copy of that branch). A deleted entry declares nothing;
  ;; the one merge (seon.writer/candidate) carries the entry's deletion
  (let [file "resources/seon/schemas/app.shape.edn"
        base (d/db-with (empty-db) (source/file-tx file "{:app.shape/size :int :app.shape/name :string}"))
        src (d/db-with base (source/refresh-tx base {file "{:app.shape/name :string}"}))
        onto (:db (seon.writer/candidate base base src {}))]
    (is (= #{:app.shape/name} (set (keys (program/schema-changes onto)))) "the merge deletes the entry, as the source did")))

;; a-merge-speculates-only-its-schema-entries deleted with seon.merge/merge-tx: the one merge speculates nothing, it replays the
;; branch's transactions once through the write check (seon.writer/replay); see a-required-value-the-branch-alone-changed-merges-clean

(deftest a-merge-declares-the-attributes-its-datoms-use-before-them
  ;; found 2026-10-27 through 0.7 (agent a1, (my.branch/merge-main!) on the standing root): main had
  ;; gained datoms of :form/ops, and the agent's branch was cut before the attribute: Datahike refused
  ;; the datoms (Bad entity attribute :form/ops, not defined in current schema). A merge's tx-data
  ;; declares, first, every attribute its datoms use that the target's schema lacks, read from the
  ;; registry, not only for the schema keys merged. The one merge carries the declarations the branch made since base, read from the
  ;; branch's own schema (seon.merge/mapped's :declarations), at the head of its first replayed transaction (seon.writer/replay)
  ;; on a world, as real branches are: the branch registers an attribute main lacks and stores a datom of it
  (let [base (empty-db)
        head (with (registered base :app.shape/size :int) [{:db/id 900 :app.shape/size 3}])]
    (is (thrown? Exception (d/db-with base [{:db/id 900 :app.shape/size 3}])) "without it the datom is refused")
    (is (= [:app.shape/size] (mapv :db/ident (:declarations (pm/mapped base head base)))) "the one attribute the branch declared since base")
    (is (= [] (:declarations (pm/mapped head head base))) "a branch that declared nothing since base carries none")
    (is (= 3 (:app.shape/size (d/entity (:db (seon.writer/candidate base base head {})) 900))) "declared first, the datom lands")))

(deftest a-merge-base-cut-before-an-attribute-diffs-it-as-the-heads
  ;; found 2026-10-27 through 0.7 (agent a1, merge-main! after the merge above declared :form/ops): the
  ;; content diff read every attribute either side declares from a base cut before :form/ops, and
  ;; datahike.db/datoms-diff resolved it against the base only (not defined in current schema)
  (let [full (empty-db)
        base (datahike.core/empty-db (into {} (map (fn [[k v]] [k (dissoc v :db/id)])) (dissoc (d/schema full) :form/ops))
                                     {:schema-flexibility :write})
        head (d/db-with base [(dissoc (get (d/schema full) :form/ops) :db/id) [:db/add 100 :form/ops :seon.op/x]])]
    (is (= [[100 :form/ops :seon.op/x]]
           (mapv (juxt :e :a :v) (filter #(= :form/ops (:a %)) (#'pm/content-datoms base head #{}))))
        "the head's datoms of the attribute the base lacks are what moved")))

(deftest content-datoms-given-a-diff-are-its-content-ones
  ;; docs/cost.md#a-diff-per-attribute-costs-a-descent-each: a caller that read the whole :eavt diff hands it over
  ;; and nothing is diffed again; a transaction's instant is no key's content
  (let [b (base)
        branch (with b (var-tx "f" "(inc x)"))
        whole (apply concat (d/datoms-diff b branch :eavt []))
        mine (pm/content-datoms b branch #{})]
    (is (seq mine) "the branch changed the program")
    (is (= (set mine) (set (pm/content-datoms b branch #{} whole))) "the content ones among the whole diff are the ones read per attribute")
    (is (some #(= :db/txInstant (:a %)) whole) "the whole diff holds the transactions' instants")
    (is (not-any? #(= :db/txInstant (:a %)) mine) "which are no content")
    (is (= (pm/delta-changes b branch #{}) (pm/delta-changes b branch #{} mine) (pm/delta-changes b branch #{} whole)))))

(deftest a-schema-entry-merges-per-key
  ;; task seon.source/schemas-are-part-of-the-program: a schema entry is a program key,
  ;; [:schema k], merged like a var (v1's [:seon.schema/key k] identity), and the merge's
  ;; changed resource is written back with only its entry changed. Its world is built from text: the resource holds three of the
  ;; binary's own entries as the binary states them (seon.contract/forms), so main differs from the binary at nothing and only
  ;; this test's changes are changes; read from the checkout's file, it went red whenever the binary was older than the checkout
  (let [enc (fn [k] (binding [*print-namespace-maps* false] (sedn/encode (get seon.contract/forms k))))
        text (str "{:seon.task/merged-t " (enc :seon.task/merged-t) "\n :seon.task/reason " (enc :seon.task/reason)
                  "\n :seon.task/plan " (enc :seon.task/plan) "}\n")
        main (let [db (empty-db)] (d/db-with db (source/import-tx db {facts-path text})))
        ;; the entry's line as the file has it (a property added to the entry moves with it), and
        ;; its form with :paused added, read from that text
        status-line (some #(when (str/starts-with? % " :seon.task/reason ") %) (str/split-lines text))
        status (edn/read-string "[:string {:min 1}]")
        ours (-> main (registered :seon.task/reason status) (registered :user/qty :int))
        theirs (registered main :seon.task/merged-t [:int {:min 1}])
        ch (pm/delta-changes main ours #{})
        ;; the one merge (seon.writer/candidate) of main, which changed another key of the resource, into the branch
        r (seon.writer/candidate theirs main ours {})
        form-of (fn [db k] (:schema/form (d/entity db [:schema/key k])))]
    (is (= [[:schema :seon.task/reason]] (keys ch))
        "the branch's change is its key; a scratch key is no program")
    (is (= {:form (binding [*print-namespace-maps* false] (sedn/encode status)) :file facts-path} (second (get ch [:schema :seon.task/reason]))))
    (is (= (form-of theirs :seon.task/merged-t) (form-of (:db r) :seon.task/merged-t)) "a key only the other side changed applies")
    (is (= (form-of ours :seon.task/reason) (form-of (:db r) :seon.task/reason)) "beside the branch's own")
    (is (nil? (:conflicts r)) "different keys of one resource do not conflict")
    (testing "one key changed two ways conflicts at its key"
      (let [cs (:conflicts (pm/decide main ours (registered main :seon.task/reason [:string {:max 5}])))]
        (is (= [[:schema/key :seon.task/reason] #{:base :ours :theirs}] ((juxt :entity sides) (first (filter #(= :schema/form (nth (:key %) 2)) cs)))))))
    (testing "merged into main: the entry is main's, no longer the binary's, and write-back changes only its bytes"
      ;; the branch without the scratch key: the one merge carries every fact, a scratch entry's too
      (let [ours' (registered main :seon.task/reason status)
            main2 (:db (seon.writer/candidate main main ours' {}))]
        (is (= #{:seon.task/reason} (set (keys (program/schema-changes main2)))))
        (is (= [facts-path] (source/changed-files main2 ch)))
        (is (= {facts-path (str/replace text status-line " :seon.task/reason [:string {:min 1}]")}
               (source/files main2 (source/changed-files main2 ch))))))))

;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: every key kind the merge carries (seon.merge's stated-keys) is one
;; :seon.merge/program-key names, so the contracts every merge function is armed with in the binary accept it. Red before [:tasks] and
;; [:fixture] were named: a seon_done of any branch that changed a task file refused at seon.merge/entity-keys' output (tests2's first
;; landing on the new main, 2026-11-16). Estimate: one walk of a schema form, ~10k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} every-carried-key-kind-is-a-program-key
  ;; the schema as the armed contracts read it: the registry in force, the branch's own (never the forms the binary was built with)
  (let [named (set (for [x (tree-seq coll? seq (m/form (m/deref (m/schema :seon.merge/program-key))))
                         :when (and (vector? x) (= := (first x)) (keyword? (second x)))]
                     (second x)))]
    (is (empty? (remove named (keys @#'pm/stated-keys))) (str "carried kinds the schema does not name: " (vec (remove named (keys @#'pm/stated-keys)))))))

;; docs/source.md#a-merged-task-reaches-mains-files: a task file's text is a fact of the program, [:tasks path], merged whole and written
;; back byte for byte, so a task a branch files reaches git with the code it merged (tests2's seon_done pilot, 2026-11-16: 20 files written,
;; the branch's resources/seon/tasks/seon.source.edn not). Red before the :tasks row of seon.merge's stated-keys: no key, no file.
;; Estimate: an empty db and 3 small file writes ~1M, two delta-changes over 2 files ~0.4M, one candidate of one key ~1M = 3000000
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-merge-that-changed-a-task-file-writes-it-byte-for-byte
  (let [dir (seon.config/value :seon.config.source/tasks-dir)
        p (str dir "/demo.edn") q (str dir "/other.edn")
        old "[{:seon.task/name \"a\"\n  :seon.task/plan \"Old.\"}]\n"
        new "[{:seon.task/name \"a\"\n  :seon.task/plan \"Old.\"}\n ;; filed on the branch\n {:seon.task/name \"b\" :seon.task/plan \"New.\"}]\n"
        main (d/db-with (empty-db) [{:file/path p :file/text old} {:file/path q :file/text "[]\n"}])
        ours (d/db-with main [{:file/path p :file/text new}])
        theirs (d/db-with main [{:file/path q :file/text "[{:seon.task/name \"c\"}]\n"}])
        ch (pm/delta-changes main ours #{})
        r (seon.writer/candidate theirs main ours {})]
    (is (= [[:tasks p]] (keys ch)) "the branch's change is its task file")
    (is (nil? (:conflicts r)) "another task file main changed is no conflict")
    (is (= [p] (source/changed-files (:db r) ch)) "the merge names the file")
    (is (= {p new} (source/files (:db r) [p])) "and writes it as the branch holds it, byte for byte")
    (is (= "[{:seon.task/name \"c\"}]\n" (get (source/files (:db r) [q]) q)) "main's own change to its file stays")
    (testing "one task file changed on both sides conflicts at the file: its text merges whole"
      (is (seq (:conflicts (seon.writer/candidate (d/db-with main [{:file/path p :file/text "[]\n"}]) main ours {})))))))

;; the-delta-three-way-reads-data-and-schema-entries-as-the-program-does: its three-way equivalence went with seon.merge/three-way and
;; delta-three-way; what remains of its claim, a branch's own change read where the dbs differ, is the test below
;; Estimate: the world (a fixture) + 2 task creates ~1M + one db-with of the change ~0.5M + the whole program of base and of the changed
;; db at :all and at two attributes (4 reads of a small world, ~0.5M each) + 2 delta-changes ~1M = 8000000 ticks (measured 7M). Was 76M on the
;; store-shaped test db, whose every schema entry is a program key; the world holds a small program.
(deftest ^{:seon.test/estimate {:ticks 8000000}} delta-changes-reads-data-and-schema-entries-as-the-program-does
  ;; declared data (card one and many) and schema entries, besides forms and definitions, changed on one db at once:
  ;; delta-changes, read only where the dbs differ, answers what changes over the whole programs does
  (let [b (mt/with (base) (mt/task 'demo "n" "N.") (mt/task 'demo "m" "M." #{} #{"demo/n"}))
        task (d/entity b [:seon.task/id "demo/m"])
        need (:db/id (first (:seon.task/needs task)))
        side (-> b
                 (d/db-with [{:note/text "ours" :note/agent :t} [:db/add (:db/id task) :seon.task/plan "ours"] [:db/retract (:db/id task) :seon.task/needs need]
                             {:schema/key :x/y :schema/form "[:int]" :schema/file "x.edn"}])
                 (with (var-tx "g" "(dec x)")))]
    (doseq [da [:all #{:seon.task/plan :seon.task/needs}]]
      (let [full (pm/changes (pm/program b da) (pm/program side da))]
        (is (= full (pm/delta-changes b side da)) (pr-str da))
        (is (seq full))))
    (is (contains? (pm/delta-changes b side #{:seon.task/needs}) [:datum (:db/id task) :seon.task/needs need]) "a removed ref of a card-many attribute is a change at its value's key")))

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
  ;; the one merge (seon.writer/candidate): main gains exactly the facts the branch wrote since
  ;; base, on their own entity ids (content ids), replayed as new transactions naming their source,
  ;; and nothing else; every fact merges (owner, 2026-11-14: no list of what merges)
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (keyword (str (name (:branch cfg)) "-v"))
           _ (d/transact conn ((var-tx "f" "x") @conn))
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))]
       (try
         (d/transact bc ((var-tx "n" "1") @bc))
         (d/transact bc ((var-tx "f" "(inc x)") @bc))
         (d/transact bc [{:note/text "a platform-free scratch note" :note/agent :t}])
         (let [base (pdb/branch-base conn (:branch cfg) b) head @bc main @conn
               ks (keys (pm/delta-changes base head #{}))
               after (:db (seon.writer/candidate main base head {}))
               ;; what the merged value gained over main, a transaction's own facts left out
               own (remove #(seq (d/datoms after :eavt (:e %) :db/txInstant)) (second (pm/changed-datoms main after)))]
           (testing "main's program at the merged keys is the branch's"
             (is (= (pm/program-of head ks #{}) (pm/program-of after ks #{}))))
           (testing "every fact main gained is one the branch holds, its entity id kept"
             (is (seq own))
             (is (every? #(seq (d/datoms head :eavt (:e %) (:a %) (:v %))) own) (pr-str (vec (take 5 own)))))
           (testing "each new form is one entity on both sides"
             (is (= (pdb/eid head :var/name 'app/n) (pdb/eid after :var/name 'app/n))))
           (testing "the scratch note merges too: every fact merges (:note/text is not indexed: read by attribute, never :avet)"
             (is (some #(= "a platform-free scratch note" (:v %)) (d/datoms after :aevt :note/text)))))
         (finally (d/release bc) (d/delete-branch! conn b)))))))

(deftest changed-vars-are-the-var-keys-of-a-change
  ;; found red on main (2026-10-27): it read each key [kind x] one level too deep, so any branch
  ;; that changed something threw "nth not supported on this type: Keyword" in every turn's test plan
  (is (= #{'app/f 'app/g} (pm/changed-vars {[:var 'app/f] [nil {:meta #{:arglists}}] [:var 'app/g] [{:meta #{:arglists}} nil] [:schema :app/k] [nil {}]})))
  (is (= #{} (pm/changed-vars {}))))
(deftest a-merge-carries-the-namespace-of-a-form-it-carries
  ;; task seon.system/a-behind-merge-is-bounded-and-merges-main-first (gap 3): a form in a namespace the
  ;; target lacks was carried with its :form/ns ref but not the namespace entity it names, so the form read
  ;; :ns nil there and the target's program differed from the branch's at that key for ever (the writer's
  ;; behind check never settled). The one merge (seon.writer/candidate) carries the namespace entity too.
  ;; In process: main is the base (a db value), the branch the base plus a form of a namespace the base lacks.
  (let [main (base)
        newns (fn [db] (program/record-tx db {:id "app.z/(defn z [x] x)" :src "(defn z [x] x)" :ns 'app.z :ns-form? false
                                               :defs [{:var 'app.z/z :meta #{:arglists} :contract "[:=> [:cat :int] :int]"}]
                                               :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}}))
        head (with main newns)
        ks (keys (pm/delta-changes main head #{}))
        after (:db (seon.writer/candidate main main head {}))]
    (is (some #(= :form (first %)) ks) "the form is a key to carry")
    (is (= (pm/program-of head ks #{}) (pm/program-of after ks #{})) "main reads the branch's program at those keys, :ns included")
    (is (= 'app.z (:ns (val (first (filter #(= :form (first (key %))) (pm/program-of after ks #{})))))) "the form's namespace")
    (is (= {} (pm/delta-changes head after #{})) "and nothing differs between the two programs any more")))

;; docs/merging.md: an agent's first prove asks for its merge candidate before it has changed anything
(deftest a-branch-with-nothing-of-its-own-merges-nothing
  ;; the one merge (seon.writer/candidate): nothing to replay, no key touched
  (let [db (my.test/world)
        c (seon.writer/candidate db db db {})]
    (is (= [] (:txs (pm/carry db db db))))
    (is (nil? (:conflicts c)))
    (is (empty? (:keys c)))))

(deftest a-namespace-both-sides-made-is-one-entity-after-the-merge
  ;; docs/storage.md#history: the branch and main each made app.z without an (ns) form, main after making another namespace, which moves its next id past the branch's: a namespace's id is its name's content, so the merge leaves one entity and the carried form reads it
  (let [rec (fn [nm] (fn [db] (program/record-tx db {:id (str "app.z/(defn " nm " [x] x)") :src (str "(defn " nm " [x] x)") :ns 'app.z :ns-form? false
                                                     :defs [{:var (symbol "app.z" nm) :meta #{:arglists} :contract "[:=> [:cat :int] :int]"}]
                                                     :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})))
        b (with (mt/world) (var-tx "f" "x"))
        main (with b [{:ns/name 'app.other}] (rec "m"))
        head (with b (rec "z"))
        ks (keys (pm/delta-changes b head #{}))
        ;; the one merge (seon.writer/candidate)
        after (:db (seon.writer/candidate main b head {}))]
    (is (= 1 (count (d/datoms after :avet :ns/name 'app.z))) "one entity for the namespace")
    (is (= 'app.z (:ns (val (first (filter #(= :form (first (key %))) (pm/program-of after ks #{})))))) "the carried form reads it")))

;; docs/storage.md#mains-content-mark: two commits of main with one content mark hold the same content, so a reader of main's
;; content asks the mark and reads no diff; a main no writer marked is diffed. Red before: content-commit diffed every attribute
(deftest same-content-is-read-from-mains-mark
  (ts/test-branch!
    (fn [_ conn]
      (let [mark! #(d/transact conn [{:seon.system/id :root :seon.system/content-t (inc (:max-tx @conn))}])
            calls (fn [f] (profile/begin!) (f) (into {} (map (juxt :var :calls)) (profile/touched)))]
        (d/transact conn ((var-tx "f" "x") @conn))
        (let [unmarked @conn]
          (d/transact conn [{:seon.agent/key "b1/a1" :seon.agent/status :busy}])
          (is (not (pm/same-content? unmarked @conn)) "a main no writer marked is not known to hold the same content")
          (is (pos? (get (calls #(pm/content-commit unmarked @conn)) 'seon.merge/content-datoms 0)) "so it is diffed"))
        (mark!)
        (let [held @conn]
          (d/transact conn [{:seon.agent/key "b1/a1" :seon.agent/status :idle}])
          (is (pm/same-content? held @conn) "a commit of records leaves the mark")
          (is (= (d/commit-id held) (pm/content-commit held @conn)) "so the commit held stands")
          (is (nil? (get (calls #(pm/content-commit held @conn)) 'seon.merge/content-datoms)) "and no content is read")
          (is (not (pm/moves-content? [[:db/add 1 :seon.agent/status :idle]])) "records are no content")
          (is (pm/moves-content? [[:db/add 1 :var/name 'app/g]]) "a definition is")
          (is (pm/moves-content? [[:db/add 1 :seon.task/plan "p"]]) "and so is carried task content")
          (d/transact conn ((var-tx "g" "x") @conn))
          (mark!)
          (is (not (pm/same-content? held @conn)) "the writer moves the mark with the content")
          (is (= (d/commit-id @conn) (pm/content-commit held @conn)) "and a definition moves the commit"))))))

;; docs/render.md#how-the-work-stands: what a reader of main's content keys on moves when the content does, never on a commit of records
(deftest a-commit-of-records-leaves-mains-content-commit
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn ((var-tx "f" "x") @conn))
      (let [held @conn
            c0 (d/commit-id held)]
        (is (= c0 (pm/content-commit nil held)) "none held: main's own")
        (d/transact conn [{:seon.agent/key "b1/a1" :seon.agent/status :busy}])
        (is (not= c0 (d/commit-id @conn)) "the record moved main's commit")
        (is (= c0 (pm/content-commit held @conn)) "and no content, so the commit held stands")
        (d/transact conn ((var-tx "g" "x") @conn))
        (is (= (d/commit-id @conn) (pm/content-commit held @conn)) "a definition moves it")))))

;; Estimate: the world, three small dbs and one candidate (decide, carry and replay of one definition, a few M) = 80000000 ticks
(deftest ^{:seon.test/estimate {:ticks 80000000}} a-task-main-finished-is-finished-on-the-branch-after-merging-main
  ;; agent lb76's branch (2026-11-14): main closed seon.check/a-selection-derives-what-it-needs-from-its-steps (:done, :cancelled);
  ;; after merging main the branch read it as ready with no proof, and the done rule refused the agent for it. A branch's state after
  ;; merging main is main's plus its own change: what the branch never wrote is main's (the one merge, seon.writer/candidate)
  (let [b (with (base) [{:seon.task/id "t/x" :seon.task/plan "Do it." :seon.task/execution :ready}])
        theirs (with b [{:seon.task/id "t/x" :seon.task/execution :cancelled :seon.task/outcome :done}])
        ours (with b (var-tx "f" "(inc x)"))
        r (seon.writer/candidate theirs b ours {})
        merged (:db r)]
    (is (nil? (:conflicts r)) "nothing both changed")
    (is (= [:cancelled :done] ((juxt :seon.task/execution :seon.task/outcome) (d/entity merged [:seon.task/id "t/x"]))) "main's end of the task")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")))

;; docs/tasks.md#a-task-holds-how-its-work-ended (plan stage 1.6): a task's content is a key of the one merge
;; Estimate: a world 0.5M (cached) + 2 create-tx 1M + one delta-changes of a few keys 0.5M + 2 candidates (decide, carry and replay
;; of a few facts, ~1.5M each) 3M + content-tx and side-tx 0.5M = 6M; 8000000 ticks
(deftest ^{:seon.test/estimate {:ticks 8000000}} a-tasks-content-is-a-key-of-the-one-merge
  (let [w (base)
        base (d/db-with w (seon.task/create-tx w [{:seon.task/ns 'demo :seon.task/name "x" :seon.task/plan "Make x." :seon.task/proofs #{}}]))
        branch (d/db-with base [[:db/add [:seon.task/id "demo/x"] :seon.task/plan "Make x, as the agent learned it."]])
        main (d/db-with base [[:db/add [:seon.task/id "demo/x"] :seon.task/proofs 'demo-test/x-test]])]
    (is (= [[:task "demo/x"]] (filterv #(= :task (first %)) (keys (pm/delta-changes base branch #{})))) "a branch's task edit is a change of its :task key")
    (testing "changed on one side alone: taken (the one merge, seon.writer/candidate)"
      (let [merged (:db (seon.writer/candidate main base branch {}))]
        (is (= "Make x, as the agent learned it." (:seon.task/plan (d/entity merged [:seon.task/id "demo/x"]))))))
    (testing "a task the receiving branch doesn't hold is never made by its content alone: it merges whole, with its declaration"
      (let [newer (d/db-with branch (seon.task/create-tx branch [{:seon.task/ns 'demo :seon.task/name "y" :seon.task/plan "Make y." :seon.task/proofs #{}}]))
            y (d/entity (:db (seon.writer/candidate main base newer {})) [:seon.task/id "demo/y"])]
        (is (= "Make y." (:seon.task/plan y)))
        (is (= (:seon.task/execution (d/entity newer [:seon.task/id "demo/y"])) (:seon.task/execution y)) "its declaration's facts came with it")))
    (testing "an unmerged task entry is settled by writing a side's content (my.branch/resolve! of [:task ...])"
      (let [settled (d/db-with main (pm/content-tx main [:task "demo/x"] {:plan "Make x, settled." :proofs #{'demo-test/x-test}}))]
        (is (= ["Make x, settled." #{'demo-test/x-test}] ((juxt :seon.task/plan (comp set :seon.task/proofs)) (d/entity settled [:seon.task/id "demo/x"]))))))
    (testing "an unmerged datum entry is settled by writing that side's value (my.branch/resolve! of [:datum e a]: Stage 0's lb76, whose
              five old records content-tx refused as no map)"
      (let [t (d/entity main [:seon.task/id "demo/x"])
            e (:db/id t)
            was (:seon.task/plan t)
            set-to (d/db-with main (pm/side-tx main [:datum e :seon.task/plan] "Make x, main's."))
            none (d/db-with main (pm/side-tx main [:datum e :seon.task/plan] nil))]
        (is (some? was) "the world's task holds a plan to replace")
        (is (= "Make x, main's." (:seon.task/plan (d/entity set-to e))) "a side's value is the datum's")
        (is (nil? (:seon.task/plan (d/entity none e))) "a side holding none retracts it")))))

;; Estimate: the world 0.5M (cached) + 4 small transactions + one candidate (decide, carry and replay of one definition) ~3M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-fact-a-branch-holds-only-because-main-wrote-it-is-mains
  ;; agent lb76's branch (2026-11-14): 6,907 conflicts, nearly all facts main wrote that the branch held stale (carried before its
  ;; base moved, never written by it), and embeddings each side computes. The one merge (seon.writer/candidate) reads the branch's
  ;; change from the true merge base, w, an ancestor of both: the stale value is base's, so no change of the branch's; a derived
  ;; entity ({:seon.db/derived true}) never merges
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data tx :tx-meta {:seon.tx/branch branch}})))
        w (as (base) :main [{:seon.agent/key "a1" :seon.agent/status :busy}])
        branch (as w :main/b ((var-tx "f" "(inc x)") w))
        base' (as w :main [[:db/retract [:seon.agent/key "a1"] :seon.agent/status :busy]])
        main (as base' :main [[:db/add [:seon.agent/key "a1"] :seon.agent/status :idle]])
        r (seon.writer/candidate main w branch {})
        merged (:db r)]
    (is (nil? (:conflicts r)) (str "a stale value of main's is no conflict: " (pr-str (:conflicts r))))
    (is (= :idle (:seon.agent/status (d/entity merged [:seon.agent/key "a1"]))) "main's fact")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")))


(defn- merged-of
  "What the writer's merge of main into the branch head makes, from base b0 (seon.merge/decide, then the branch's change carried onto main, seon.merge/carry and seon.writer/replay): {:conflicts},
  the decision's or the replay's entries (a fact the write check refuses), or {:db} the merged value."
  [b0 head main]
  (let [r (pm/decide b0 head main)]
    (if (:conflicts r)
      r
      (let [{:keys [db entries]} (seon.writer/replay main b0 head (pm/carry b0 head main) {})]
        (if (seq entries) {:conflicts entries} {:db (cond-> db (seq (:settle-ops r)) (-> (d/with (:settle-ops r)) :db-after))})))))

;; Estimate: the world 0.5M (cached) + 5 small transactions ~1M + one merge-main (a diff of a few datoms, program-of on 2 keys of three
;; dbs) ~3M + one db-with ~0.5M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} merge-main-is-main-plus-the-branchs-own-change
  ;; docs/merging.md#a-branch-merges-main-in, the agreed seon.merge/merge-main: the merged branch is main's head plus what the branch
  ;; wrote since base; main's other moves are main's already and never read; a key both changed stops the merge, nothing written
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        b0 (as (base) :main [{:seon.agent/key "a1" :seon.agent/status :busy}])
        head (as b0 :main/b (var-tx "f" "(inc x)"))
        main (-> b0 (as :main (var-tx "g" "(dec x)")) (as :main [{:seon.agent/key "a2" :seon.agent/status :idle}]))
        {:keys [db conflicts]} (merged-of b0 head main)
        merged db]
    (is (nil? conflicts) (pr-str conflicts))
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch's own change")
    (is (= (fid "g" "(dec x)") (:form/id (:var/form (d/entity merged [:var/name 'app/g])))) "main's, as main holds it")
    (is (= :idle (:seon.agent/status (d/entity merged [:seon.agent/key "a2"]))) "and main's facts")
    (testing "a key both changed, differently, stops it"
      (let [main' (as main :main (var-tx "f" "(* 2 x)"))]
        (is (seq (:conflicts (merged-of b0 head main'))) "a conflict, and no tx")))))


;; Estimate: the world 0.5M (cached) + 4 small transactions ~1M + one merge-main of a few datoms ~3M + one db-with ~0.5M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-fact-both-sides-hold-from-different-transactions-is-kept
  ;; d8a0285ba8's regression (lb76, 2026-11-15 00:33): datoms-diff compares whole datoms, tx included, so a fact base and the branch both
  ;; hold, asserted in different transactions, was a gone datom and an added one; the added (shared history) was dropped and the gone
  ;; retracted, and the merge took six tasks' execution away. A fact is [e a v] (docs/merging.md#a-branch-merges-main-in)
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        w (d/db-with (base) [{:seon.agent/key "a1" :seon.agent/status :busy}])
        head (as w :main/b (var-tx "f" "(inc x)"))
        b0 (-> w (as :main [[:db/add [:seon.agent/key "a1"] :seon.agent/status :idle]])
               (as :main [[:db/add [:seon.agent/key "a1"] :seon.agent/status :busy]]))
        main (as b0 :main [{:seon.agent/key "a2" :seon.agent/status :idle}])
        {:keys [db conflicts]} (merged-of b0 head main)
        merged db]
    (is (nil? conflicts) (pr-str conflicts))
    (is (= :busy (:seon.agent/status (d/entity merged [:seon.agent/key "a1"]))) "the fact both hold stays")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")))

;; Estimate: the world 0.5M (cached) + 4 small transactions ~1M + one merge-main (a diff of a few datoms, one history seek each, program-of
;; on 2 keys of three dbs) ~3M + one db-with ~0.5M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-fact-from-before-the-base-is-neither-sides-change
  ;; agent lb76's branch (2026-11-15): root's :seon.system/retained, written unstamped before the branch parted, was a conflict: the
  ;; branch's base had moved to a newer main commit (an older merge path carried only some attributes), so the branch still held the old
  ;; value, and an unstamped transaction counted as the branch's own. A fact base's history asserted in the same transaction is shared
  ;; history (docs/merging.md#a-branch-merges-main-in)
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        w (d/db-with (base) [{:seon.agent/key "root" :seon.agent/status :busy}])
        head (as w :main/b (var-tx "f" "(inc x)"))
        b0 (d/db-with w [[:db/add [:seon.agent/key "root"] :seon.agent/status :idle]])
        main (d/db-with b0 [[:db/add [:seon.agent/key "root"] :seon.agent/status :ended]])
        {:keys [db conflicts]} (merged-of w head main)
        merged db]
    (is (nil? conflicts) (str "read from the true base, w, the branch never changed root's fact: no change of its: " (pr-str conflicts)))
    (is (= :ended (:seon.agent/status (d/entity merged [:seon.agent/key "root"]))) "main's fact, never the shared old value")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")))

;; Estimate: the world 0.5M (cached) + 1M a task's create + 3 small transactions ~1M + two merge-mains and one candidate, each a decide
;; of a few keys (the candidate stops at its conflict) ~4M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-branch-write-main-rewrote-later-is-a-conflict
  ;; agent lb76's branch (2026-11-15): it rewrote three other tasks' plans at 10:00 and 14:36; main rewrote them at 17:57 and 18:45; an
  ;; older merge path then moved its base past main's writes without taking the plans, so the merge to main read the branch's older plan
  ;; as its change since base and would have replaced main's newer one. Both changed the plan since the value the branch replaced: a
  ;; conflict, decided by the branch (docs/merging.md#a-branch-merges-main-in)
  (let [as (fn [db branch ms tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx)
                                                        :tx-meta {:seon.tx/branch branch :db/txInstant (java.util.Date. (long ms))}})))
        w (base)
        w (d/db-with w (seon.task/create-tx w [{:seon.task/ns 'demo :seon.task/name "x" :seon.task/plan "Make x." :seon.task/proofs #{}}]))
        t0 (.getTime ^java.util.Date (:db/txInstant (d/entity w (:max-tx w))))
        head (as w :main/b (+ t0 1000) [[:db/add [:seon.task/id "demo/x"] :seon.task/plan "Make x, as the agent read it."]])
        b0 (as w :main (+ t0 2000) [[:db/add [:seon.task/id "demo/x"] :seon.task/plan "Make x, as main learned it later."]])
        main (as b0 :main (+ t0 3000) [{:seon.agent/key "a2" :seon.agent/status :idle}])
        k [:datum (:db/id (d/entity w [:seon.task/id "demo/x"])) :seon.task/plan]]
    ;; the one merge reads from the true merge base, w, an ancestor of both: both changed the plan since, so a conflict, named by the
    ;; task; the branch decides it (a-conflict-the-branch-resolved-merges-the-next-time)
    (is (= [k] (mapv :key (:conflicts (merged-of w head main))))
        "merging main into the branch: a conflict, not the branch's older plan")
    (is (= [[:seon.task/id "demo/x"]] (mapv :entity (:conflicts (merged-of w head main)))) "shown by the task's name")
    ;; a merge of the branch into main takes main into the branch first (seon.writer/candidate), from the same merge base
    (is (= [k] (mapv :key (:conflicts (seon.writer/candidate main w head {})))) "and merging the branch to main")))

;; Estimate: the world 0.5M (cached) + 4 small transactions ~1M + one merge-main and one candidate of a few datoms ~4M = 8000000 ticks
(deftest ^{:seon.test/estimate {:ticks 8000000}} a-fact-of-an-entity-the-other-side-deleted-is-left-out
  ;; Stage 0 (2026-11-15 01:28): main removed the test var seon.lib-test/a-fork-whose-wait-is-cancelled-says-how-many-run, whole; lb76's
  ;; own verdict on it (:seon.test/digest, :seon.test/status) merged onto main's db, a nameless entity, and seon.agent/record-results!
  ;; read a nil :var/name. A fact has no subject once the other side deleted its entity (docs/merging.md#a-branch-merges-main-in)
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        b0 (as (base) :main (var-tx "t" "(is true)"))
        e (:db/id (d/entity b0 [:var/name 'app/t]))
        head (-> b0 (as :main/b [[:db/add e :seon.test/digest "d1"] [:db/add e :seon.test/status :deferred]])
                 (as :main/b (var-tx "f" "(inc x)")))
        main (as b0 :main [[:db/retractEntity e]])
        {:keys [conflicts db]} (merged-of b0 head main)
        merged db]
    ;; root's ruling (2026-11-15): a conflict is only an [e a] both changed, so none here; main holds no datom of the var, deleted whole,
    ;; and that is what is replayed: the branch's facts of it go with it, never a nameless entity
    (is (nil? conflicts) (pr-str conflicts))
    (is (empty? (d/datoms merged :eavt e)) "merging main into the branch: no nameless entity")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")
    (testing "and merging the branch to main (the one merge, seon.writer/candidate, which a merge into main takes first)"
      (let [onto (:db (seon.writer/candidate main b0 head {}))]
        (is (some? onto))
        (is (empty? (d/datoms onto :eavt e)))))))

;; Estimate: the world 0.5M (cached) + 5 small transactions + 3 merge-mains (each a diff of a few datoms, program-of on ~3 keys of three
;; dbs) ~10M = 15000000 ticks
(deftest ^{:seon.test/estimate {:ticks 15000000}} a-conflict-the-branch-resolved-merges-the-next-time
  ;; docs/merging.md#a-branch-merges-main-in: a conflict stops the merge and is an unmerged entry; the branch resolving it (git add)
  ;; is its decision, so the next merge of the same main takes the branch's value and settles the entry; main moving the key again is
  ;; a new conflict (root and context-4's catch: a resolved conflict met the same conflict forever)
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        b0 (base)
        head (as b0 :main/b (var-tx "f" "(inc x)"))
        main (as b0 :main (var-tx "f" "(* 2 x)"))
        {:keys [conflicts]} (merged-of b0 head main)
        k (:key (first conflicts))
        with-entry (as head :main/b (pm/unmerged-tx head conflicts (:max-tx main)))
        resolved (as with-entry :main/b (pm/resolve-tx with-entry k))
        r (merged-of b0 resolved main)
        merged (:db r)]
    (is (= [[:datum (:db/id (d/entity head [:var/name 'app/f])) :var/form]] (mapv :key conflicts)) "both changed f: a conflict, f's form")
    (is (= [[:var/name 'app/f]] (mapv :entity conflicts)) "shown by the var's name")
    (is (nil? (:conflicts r)) (str "resolved, the next merge is clean: " (pr-str (:conflicts r))))
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch's value stands")
    (is (empty? (pm/entries merged)) "and the entry is settled")
    (testing "main moving the key again is a new conflict"
      (is (seq (:conflicts (merged-of b0 resolved (as main :main (var-tx "f" "(- x)")))))))))

;; Estimate: a test branch (~0.5M), 20 commits of one note each (~3M), changed-datoms over 20 commits (20 reads at ~20k and
;; ~80 datoms: ~0.5M), one datoms-diff for the answer (~0.3M), ~4.5M = 4500000
(deftest ^{:seon.test/estimate {:ticks 4500000}} a-branchs-change-is-read-from-its-commits
  ;; deps, 2026-11-15: own-datoms read a branch's change by datoms-diff of base and head, ~3.1B ticks on lb76's change (30x
  ;; its apply). Each commit keeps what it changed (Datahike d/changes), so changed-datoms reads the commits instead and
  ;; answers what the diff answers, by [e a v]
  (ts/test-branch!
    (fn [_ conn]
      (let [e1 (get (:tempids (d/transact conn [{:db/id "a" :note/text "kept" :note/agent :k}])) "a")
            base @conn
            _ (dotimes [i 18] (d/transact conn [{:note/text (str "n" i) :note/agent :n}]))
            _ (d/transact conn [[:db/retract e1 :note/text "kept"] [:db/add e1 :note/text "changed"]])
            head @conn
            fact (fn [ds] (into #{} (map (fn [x] [(:e x) (:a x) (:v x)])) (remove #(= :db/txInstant (:a %)) ds)))
            t0 (jolt.host/ticks)
            [gone added] (pm/changed-datoms base head)
            dt (- (jolt.host/ticks) t0)
            [dgone dadded] (d/datoms-diff base head :eavt [])]
        (is (= (fact dadded) (fact added)) "what head added, as the diff answers it")
        (is (= (fact dgone) (fact gone)) "and what it let go of")
        (is (and (seq gone) (not-any? :added gone)) "read from the commits' changes (a retraction), never a diff of the values (a datom of base)")
        ;; 20 commits: 20 changes values read (~20k ticks each) and their ~80 datoms
        (is (< dt 1000000) (str "read from its 20 commits: " dt " ticks"))))))

;; docs/merging.md#a-branch-merges-main-in: a fact both sides changed differently since the base is a conflict the branch's agent can
;; see and settle. Red before (found on tests' pilot branch at root, 2026-11-15, result/cad55069): the writer's merge-main-into
;; refused the merge and wrote the conflict's entry, but seon.merge/unmerged (what my.branch/unmerged shows the agent) read the
;; branch's content through program-of, which holds no datums, and showed nothing: the agent saw nothing to resolve while the merge
;; stayed refused
;; Estimate: the test branch ~3M + 4 small transactions ~1M + one merge-main-into ~3M = ~8M; 10000000
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-datum-conflict-is-shown-and-settled
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (pdb/on-main (:branch cfg) :dc-branch)
           _ (d/transact conn {:tx-data [{:seon.agent/key "dc-agent" :seon.agent/model "base-model"}] :tx-meta (pdb/tx-meta (:branch cfg))})
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))]
       (try
         (d/transact bc {:tx-data [[:db/add [:seon.agent/key "dc-agent"] :seon.agent/model "branch-model"]] :tx-meta (pdb/tx-meta b)})
         (d/transact conn {:tx-data [[:db/add [:seon.agent/key "dc-agent"] :seon.agent/model "main-model"]] :tx-meta (pdb/tx-meta (:branch cfg))})
         (let [r (seon.writer/merge-main-into conn cfg b)
               shown (pm/unmerged (pdb/head conn b))]
           (is (false? (:ok r)) (str "both changed it differently: a conflict, nothing merged: " (pr-str r)))
           (is (= 1 (count shown)) (str "and the agent is shown it to settle: " (pr-str shown)))
           (testing "settled to main's side (my.branch/resolve!'s two writes), the next merge goes through"
             (let [{k :key theirs :theirs} (first shown) head (pdb/head conn b)]
               (d/transact bc {:tx-data (into (pm/side-tx head k theirs) (pm/resolve-tx head k)) :tx-meta (pdb/tx-meta b)})
               (is (empty? (pm/unmerged (pdb/head conn b))) "nothing left to settle")
               (is (:ok (seon.writer/merge-main-into conn cfg b)) "and main merges in")
               (is (= "main-model" (:seon.agent/model (d/entity (pdb/head conn b) [:seon.agent/key "dc-agent"]))) "holding main's value"))))
         (finally (d/release bc)))))))


;; docs/merging.md#a-branch-merges-main-in: a value the branch alone changed merges clean, an entity's required attribute included. Red
;; before (context's and web's seon_done, 2026-11-16, result/d56d44da at root): a value changed twice nets to the base value's retraction,
;; stamped with the first change, and the last value, stamped with the last; carry filed each under its own transaction, so the replay
;; wrote the retraction alone and the write check refused it ("task-fact requires :seon.task/execution"), an entry nobody could settle.
;; Estimate: the test branch ~3M + 4 small transactions ~1M + one merge-main-into ~3M = ~7M; 10,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-required-value-the-branch-alone-changed-merges-clean
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (pdb/on-main (:branch cfg) :rv-branch)
           id "demo/rv-task"
           _ (d/transact conn {:tx-data (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "rv-task" :seon.task/plan "p" :seon.task/kind :work}])
                               :tx-meta (pdb/tx-meta (:branch cfg))})
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))]
       (try
         ;; the branch changes it twice: the net change's retraction is stamped with the first, its new value with the second
         (d/transact bc {:tx-data (seon.task/execution-tx [id] :running) :tx-meta (pdb/tx-meta b)})
         (d/transact bc {:tx-data (seon.task/execution-tx [id] :cancelled) :tx-meta (pdb/tx-meta b)})
         ;; main moves elsewhere, so the merge has main to take
         (d/transact conn {:tx-data [{:seon.agent/key "rv-other" :seon.agent/model "main-moved"}] :tx-meta (pdb/tx-meta (:branch cfg))})
         (let [r (seon.writer/merge-main-into conn cfg b)
               head (pdb/head conn b)]
           (is (:ok r) (str "one side's change, no entry: " (pr-str (select-keys r [:ok :conflicts]))))
           (is (empty? (pm/unmerged head)) "nothing to settle")
           (is (= :cancelled (:seon.task/execution (d/entity head [:seon.task/id id]))) "the branch's value stands")
           (is (= "main-moved" (:seon.agent/model (d/entity head [:seon.agent/key "rv-other"]))) "beside main's"))
         (finally (d/release bc)))))))

;; docs/merging.md#a-branch-merges-main-in: a clean merge leaves no entry for a conflict it no longer finds. Red before: decide settled only
;; entries at keys both sides changed, so the entries the replay once wrote wrongly at one-sided keys (context's and web's branches,
;; 2026-11-16) stayed after every clean merge and refused the branch's merge into main until a hand resolved each.
;; Estimate: the test branch ~3M + 4 small transactions ~1M + one merge-main-into ~3M = ~7M; 10,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-clean-merge-settles-every-entry-it-finds-no-conflict-at
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (pdb/on-main (:branch cfg) :se-branch)
           id "demo/se-task"
           _ (d/transact conn {:tx-data (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "se-task" :seon.task/plan "p" :seon.task/kind :work}])
                               :tx-meta (pdb/tx-meta (:branch cfg))})
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))]
       (try
         (d/transact bc {:tx-data (seon.task/execution-tx [id] :cancelled) :tx-meta (pdb/tx-meta b)})
         ;; the entry an earlier merge wrongly left at the one-sided key
         (let [head @bc e (:db/id (d/entity head [:seon.task/id id]))]
           (d/transact bc {:tx-data (pm/unmerged-tx head [{:key [:datum e :seon.task/execution] :base :held :ours :cancelled :theirs :held
                                                            :why "a stale entry"}] (:max-tx @conn))
                           :tx-meta (pdb/tx-meta b)}))
         (d/transact conn {:tx-data [{:seon.agent/key "se-other" :seon.agent/model "main-moved"}] :tx-meta (pdb/tx-meta (:branch cfg))})
         (is (= 1 (count (pm/unmerged (pdb/head conn b)))) "the branch holds the stale entry")
         (let [r (seon.writer/merge-main-into conn cfg b) head (pdb/head conn b)]
           (is (:ok r) (pr-str (select-keys r [:ok :conflicts])))
           (is (empty? (pm/unmerged head)) "the clean merge settles it")
           (is (= :cancelled (:seon.task/execution (d/entity head [:seon.task/id id]))) "and the branch's value stands"))
         (finally (d/release bc)))))))

;; docs/merging.md#a-branch-merges-main-in: a merge of main into a branch is built on main's head, so it states its change against the
;; branch head it replaces (datahike's force-branch! :changes, deps 0fb0383f): what main moved for the branch. seon.merge/change-record
;; reads it at the keys either side touched; deps' condition: it equals, by net presence at both ends, the fact difference of the two
;; values. Red before: d/changes(branch-head -> merged) was refused, and the fallback diff (tx-sensitive) showed every branch fact moved,
;; so a wake re-sent the whole page (seon.agent-test/a-wake-shows-what-moved-not-the-page-again)
;; Estimate: the world 0.5M (cached) + 5 small transactions ~1M + decide and carry ~2M + two whole reads of a small world ~1M = 5000000
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-merge-states-what-main-moved-for-the-branch
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        b0 (as (base) :main [{:seon.agent/key "a1" :seon.agent/model "m0"}])
        head (as b0 :main/b (var-tx "f" "(inc x)"))
        main (-> b0 (as :main (var-tx "g" "(dec x)")) (as :main [[:db/add [:seon.agent/key "a1"] :seon.agent/model "m1"]]))
        {:keys [db]} (merged-of b0 head main)
        [gone added] (pm/changed-datoms b0 main)
        c (pm/carry b0 head main)
        ks (distinct (for [{:keys [ops]} (:txs c) [o e a] ops :when (not= :db/retractEntity o)] [e a]))
        ds (pm/merge-record head db [gone added] ks (mapcat identity (:remapped c)))
        ;; the facts of a value by net presence, transactions' own facts left out
        facts (fn [v] (into #{} (comp (remove #(seq (d/datoms v :eavt (:e %) :db/txInstant))) (map (juxt :e :a :v))) (d/datoms v :eavt)))
        [h m] [(facts head) (facts db)]
        net (fn [added?] (into #{} (comp (filter #(= added? (:added %))) (map (juxt :e :a :v))) ds))]
    (is (= [#{} #{}] (let [x (into #{} (remove h) m) y (net true)] [(into #{} (remove y) x) (into #{} (remove x) y)])) "what it adds is exactly what the merged value holds and the head did not: [missing extra]")
    (is (= [#{} #{}] (let [x (into #{} (remove m) h) y (net false)] [(into #{} (remove y) x) (into #{} (remove x) y)])) "and what it retracts, what the head held and the merged value does not: [missing extra]")
    (is (contains? (net true) [(:db/id (d/entity db [:var/name 'app/g])) :var/form (:db/id (:var/form (d/entity db [:var/name 'app/g])))]) "main's new var is a move")
    (is (not-any? #(= (:db/id (d/entity db [:var/name 'app/f])) (first %)) (net true)) "the branch's own var is no move of its own")))

;; docs/merging.md#a-branch-merges-main-in: a fact a schema now forbids is a conflict like any other (owner, 2026-11-16): the merge refuses
;; it as an unmerged entry naming its key, base, ours, theirs and why, and the branch settles it as it settles a data conflict. The real
;; shape (a branch ~26k t stale, result/bffd83fc): a task the branch left without :seon.task/execution, which the task's schema requires.
;; Red before: the replay answered :seon.merge/replay-refused, a typed refusal no agent could settle.
;; Estimate: the world 0.5M (cached) + a task's create ~1M + 3 small transactions ~1M + decide, carry and a replay of 2 transactions ~3M = ~6M; 10000000
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-fact-a-schema-change-forbids-is-an-unmerged-entry
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        w (base)
        w (d/db-with w (seon.task/create-tx w [{:seon.task/ns 'demo :seon.task/name "x" :seon.task/plan "Make x." :seon.task/proofs #{}}]))
        e (:db/id (d/entity w [:seon.task/id "demo/x"]))
        ex (:seon.task/execution (d/entity w e))
        ;; the branch's own retraction (unchecked here, as a branch written under an older schema held it) and a change of its own
        head (-> w (as :main/b [[:db/retract e :seon.task/execution ex]]) (as :main/b (var-tx "f" "(inc x)")))
        main (as w :main [{:seon.agent/key "a2" :seon.agent/status :idle}])
        {:keys [conflicts db]} (merged-of w head main)]
    (is (some? ex) "the task has an execution to retract")
    (is (nil? db) "nothing merged")
    (is (= [[:datum e :seon.task/execution]] (mapv :key conflicts)) (str "one entry, at the key the schema requires: " (pr-str conflicts)))
    (is (= [ex nil ex] ((juxt :base :ours :theirs) (first conflicts))) "base and main hold it, the branch does not")
    (is (some? (:why (first conflicts))) "and why: the write check's refusal")
    (testing "settled to main's side, the next merge goes through"
      (let [k (:key (first conflicts))
            with-entry (as head :main/b (pm/unmerged-tx head conflicts (:max-tx main)))
            settled (as with-entry :main/b (into (pm/side-tx with-entry k ex) (pm/resolve-tx with-entry k)))
            r (merged-of w settled main)]
        (is (nil? (:conflicts r)) (pr-str (:conflicts r)))
        (is (= ex (:seon.task/execution (d/entity (:db r) e))) "the task holds main's execution")
        (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity (:db r) [:var/name 'app/f])))) "and the branch's own change")))))

;; docs/merging.md#a-branch-merges-main-in: a derived value ({:seon.db/derived true}: an embedding, a section's read forms) is a cache each
;; side computes, so the merge neither judges nor carries it; it is derived again where it lands. Red before: slice 1 merged every attribute,
;; so session/root's branch held ~130 unmerged entries on :seon.vector/coarse, :seon.vector/f16 and :seon.doc/read (result/a2ad3499), its
;; spawn's merge of main refused, and its agent could never start on main's pins.
;; Estimate: the world 0.5M (cached) + 4 small transactions ~1M + decide, carry and a replay of 1 transaction ~2M = ~3.5M; 5,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-derived-value-both-sides-computed-is-no-conflict
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        a (first (filter #(= "seon.doc" (namespace %)) (sort (:derived-attrs (pdb/index)))))
        w (base)
        e (:db/id (d/entity w [:var/name 'app/g]))
        ;; each side computes the cache, differently; the branch also changes a var of its own
        head (-> w (as :main/b [[:db/add e a "branch's"]]) (as :main/b (var-tx "f" "(inc x)")))
        main (as w :main [[:db/add e a "main's"]])
        {:keys [conflicts db]} (merged-of w head main)]
    (is (some? a) "a derived attribute to write")
    (is (nil? conflicts) (str "a cache both sides computed is no conflict: " (pr-str conflicts)))
    (is (= "main's" (get (d/entity db e) a)) "the merged value holds main's, derived again where it lands")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity db [:var/name 'app/f])))) "and the branch's own change")))
