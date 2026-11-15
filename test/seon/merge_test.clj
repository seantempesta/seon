(ns seon.merge-test
  "The program merge (seon.merge, in core) over db values: the three-way case table,
  identity by content (identical definitions on two branches merge clean whatever their
  entity ids and places), an unmap merging as a delete of the definition and of the form
  it leaves dead, the latest redefinition winning, scratch data and observations never
  merging, unmerged entries derived from the branch, and behind meaning main's program
  moved."
  (:require [seon.test-store :as ts] [my.test :as mt] [seon.proofs :as proofs] [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.merge :as pm]
            [seon.program :as program] [seon.doc] [seon.config] [seon.source] [seon.profile :as profile] [seon.task]
            [seon.edn :as sedn] [seon.contract :as contract] [malli.core :as m] [malli.generator :as mg]
            [my.schema :as schema]
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
;; the store-shaped test db cost 441 M ticks for three-way-cases, the world 77 M
(defn- base [] (with (mt/world {:history? true}) (var-tx "f" "x") (var-tx "g" "x") (var-tx "h" "x")))

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
  (testing "a conflict holds the sides that have the key, and an absent side is one that never had it or deleted it"
    (are [o t sides] (= [sides] (map (fn [c] (set (filter #(contains? c %) [:base :ours :theirs])))
                                     (filter #(= :var (first (:key %))) (:conflicts (tw o t)))))
      (with (base) (var-tx "f" "(inc x)")) (with (base) (var-tx "f" "(dec x)")) #{:base :ours :theirs}
      (with (base) (var-tx "n" "1")) (with (base) (var-tx "n" "2")) #{:ours :theirs}
      (with (base) (unmap "f")) (with (base) (var-tx "f" "(dec x)")) #{:base :theirs}
      (with (base) (var-tx "f" "(inc x)")) (with (base) (unmap "f")) #{:base :ours})
    (let [c (first (:conflicts (tw (with (base) (var-tx "f" "(inc x)")) (with (base) (var-tx "f" "(dec x)")))))]
      (is (= (vk "f") (:key c)))
      (is (= (fid "f" "x") (:form (:base c))))
      (is (= (fid "f" "(inc x)") (:form (:ours c))))
      (is (= (fid "f" "(dec x)") (:form (:theirs c)))))))

(deftest uncontracted-is-what-the-branch-changed
  ;; one question for the writer's merge gate and root's judge: which functions the branch's
  ;; own changes (a three-way's :ours) define without a contract
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
      (is (= '[app/u] (pm/uncontracted (:ours (tw (with (base) u (var-tx "f" "(inc x)")) (base)))))))))

(deftest identical-defs-on-two-branches-merge-clean
  ;; one side makes an unrelated entity first; app/n is still one entity on both, at its
  ;; content id (docs/storage.md#ids)
  (let [o (with (base) [{:note/text "scratch" :note/agent :t}] (var-tx "n" "(* 2 x)"))
        t (with (base) (var-tx "n" "(* 2 x)"))]
    (is (= (d/q '[:find ?e . :where [?e :var/name app/n]] o) (d/q '[:find ?e . :where [?e :var/name app/n]] t)))
    (is (= {:apply [] :conflicts []} (select-keys (tw o t) [:apply :conflicts])))))

;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: md-scan 29's branch held batch 42's code and
;; the morning's wiki, so a catch main's wiki named read as one the branch unnamed, and held its done
(deftest a-branch-takes-mains-wiki
  (let [page (fn [t] (str "# P\n\nIt says " t ".\n\n## Kept\n\nIt stays.\n"))
        load (fn [db pages] (d/db-with db (seon.doc/load-tx db "demo" pages)))
        b (load (base) {"demo/p.md" (page "one") "demo/gone.md" "# Gone\n\nIt goes.\n" "demo/both.md" "# Both\n\nBase.\n"})
        theirs (-> b
                   (load {"demo/p.md" (page "two") "demo/new.md" "# New\n\nIt is new.\n" "demo/both.md" "# Both\n\nMain's.\n"})
                   (#(d/db-with % (for [x (d/datoms % :avet :seon.doc/address "demo/gone.md#gone")] [:db/retractEntity (:e x)]))))
        ours (-> b (with (var-tx "n" "1")) (load {"demo/both.md" "# Both\n\nThe branch's.\n"}))
        r (pm/delta-three-way b ours theirs #{})
        merged (d/db-with ours (pm/merge-tx ours b theirs (:apply r) #{}))
        addrs #(set (map :v (d/datoms % :avet :seon.doc/address)))]
    (is (= "It says two." (:seon.doc/text (d/entity merged [:seon.doc/address "demo/p.md#p"]))) "main's edit overwrites the branch's copy")
    (is (= (addrs theirs) (addrs merged)) "main's new page reaches the branch, and its removed one leaves it")
    (is (= [[:section "demo/both.md#both"]] (mapv :key (:conflicts r))) "a section both changed is a conflict, as a var is")
    (is (= "The branch's." (:seon.doc/text (d/entity merged [:seon.doc/address "demo/both.md#both"]))) "and the branch keeps its own until it resolves it")
    (is (some? (d/entity merged [:var/name 'app/n])) "the branch's own work stays")
    (is (empty? (:apply (pm/delta-three-way theirs theirs theirs #{}))) "a branch holding main's wiki takes nothing")))

;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: a branch's own doc edit merges as code does, and its
;; page is written back with the merge (seon.source/changed-files names it, seon.doc/files draws it), so the next load of
;; docs/ reads what merged (task seon.doc/any-markdown-file-is-docs-it-writes-back)
(deftest a-branch-doc-edit-merges-and-its-page-is-written-back
  (let [load (fn [db pages] (d/db-with db (seon.doc/load-tx db "demo" pages)))
        b (load (base) {"demo/p.md" "# P\n\nOne.\n" "demo/q.md" "# Q\n\nUntouched.\n"})
        edited (load b {"demo/p.md" "# P\n\nTwo.\n"})
        r (pm/delta-three-way b edited b #{})
        merged (d/db-with b (pm/merge-tx b b edited (keys (:ours r)) #{}))
        paths (seon.source/changed-files merged (:ours r))]
    (is (nil? (#'seon.writer/merge-refused b edited r)) "the writer takes it")
    (is (= ["demo/p.md"] paths) "the merge writes the page its doc changed, and only it")
    (is (= {"demo/p.md" "# P\n\nTwo.\n"} (seon.doc/files merged paths)) "as the merged store draws it: the next load reads it back unchanged")
    (is (= [] (seon.doc/load-tx merged "demo" (seon.doc/files merged paths))) "loading what was written writes nothing")))

(deftest a-config-entry-merges-per-key
  ;; docs/source.md#a-program-file: an entry of the config file is a key of its own: two branches changing two keys merge clean, one key changed two ways conflicts at it
  (let [p seon.config/shipped-file
        main (let [db (empty-db)] (d/db-with db (seon.source/import-tx db {p "{:x/a 1\n :x/b 2}\n"})))
        set-k (fn [db k v] (d/db-with db [{:config/key k :config/form v :config/file p}]))
        ours (set-k main :x/a "5")
        r (pm/delta-three-way main ours (set-k main :x/b "6") #{})]
    (is (= [[:config :x/a]] (keys (:ours r))) (pr-str r))
    (is (= [[:config :x/b]] (:apply r)))
    (is (empty? (:conflicts r)))
    (is (= {:config [:x/a]} (pm/divergence (:ours r))))
    (is (= [[:config :x/a] #{:base :ours :theirs}] ((fn [c] [(:key c) (set (filter #(contains? c %) [:base :ours :theirs]))]) (first (:conflicts (pm/delta-three-way main ours (set-k main :x/a "7") #{}))))))
    (let [merged (d/db-with main (pm/merge-tx main main ours (keys (:ours r)) #{}))
          paths (seon.source/changed-files merged (:ours r))]
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
        r (pm/delta-three-way main ours main #{})]
    (is (= {[:script "bin/x"] [{:file "bin/x" :text text :mode "100755"} {:file "bin/x" :text "#!/bin/sh\necho 2\n" :mode "100755"}]} (:ours r)) (pr-str r))
    (is (= {:scripts ["bin/x"]} (pm/divergence (:ours r))))
    (is (= [[:script "bin/x"] #{:base :ours :theirs}] ((fn [c] [(:key c) (set (filter #(contains? c %) [:base :ours :theirs]))]) (first (:conflicts (pm/delta-three-way main ours theirs #{}))))) "both sides changing it is an unmerged entry")
    (is (= "" (:seon.script/says (d/entity (d/db-with main (pm/merge-tx main main ours (keys (:ours r)) #{})) [:file/path "bin/x"]))) "what it derives rides with it")
    (let [p "resources/seon/schemas/x.edn"
          db (let [db (empty-db)] (d/db-with db (seon.source/import-tx db {p "{:x/a :int}\n"})))
          moved (d/db-with db [{:file/path p :file/text "{:x/a :int} \n"}])]
      (is (= {} (:ours (pm/delta-three-way db moved db #{}))) "a schema resource's file bears no key: its entries do"))))

(deftest unmap-merges-as-delete
  (let [ours (with (base) (unmap "g"))
        r (tw ours (base))
        main' (d/db-with (base) (pm/merge-tx (base) (base) ours (keys (:ours r)) #{}))]
    (is (= #{(vk "g") [:form (fid "g" "x")]} (set (keys (:ours r)))) "the definition, and the form it leaves dead")
    (is (nil? (seon.doc/source-of main' 'app/g)))
    (is (not (program/live? main' (pdb/eid main' :form/id (fid "g" "x")))) "the form is dead on main, as on the branch")
    (is (= [#{:base :theirs}] (map (fn [c] (set (filter #(contains? c %) [:base :ours :theirs]))) (filter #(= :var (first (:key %))) (:conflicts (tw ours (with (base) (var-tx "g" "(dec x)"))))))))))

;; docs/merging.md#a-branch-merges-main-in: md-scan 29's branch held the results of a test main deleted,
;; on an entity with no :var/name, and its next test run threw on the nil test key
(deftest a-var-main-deletes-leaves-no-facts-on-the-branch
  (let [e #(:db/id (d/entity % [:var/name 'app/g]))
        ours (with (base) (fn [db] [{:db/id (e db) :seon.test/digest "d1" :seon.test/status :green}]))
        theirs (with (base) (fn [db] [[:db/retractEntity (e db)]]))
        r (tw (base) theirs)
        merged (d/db-with ours (pm/merge-tx ours (base) theirs (:apply r) #{}))]
    (is (nil? (d/entity merged [:var/name 'app/g])) "the var is gone, as on main")
    (is (empty? (d/q '[:find ?t :where [?t :seon.test/digest] (not [?t :var/name])] merged))
        "no test facts stay on an entity whose name main retracted")))

(deftest latest-redefinition-wins
  (let [ours (with (base) (var-tx "f" "1") (var-tx "f" "2") (var-tx "f" "3"))]
    (is (= (fid "f" "3") (:form (second (get (:ours (tw ours (base))) (vk "f"))))))
    (is (= [(defn-src "f" "3")] (for [[[k] [_ o]] (:ours (tw ours (base))) :when (and (= :form k) o)] (:src o)))
        "only the last form is live: each redefinition superseded the one before")))

(deftest a-form-s-place-does-not-make-it-another
  (let [o (with (base) [{:note/text "first" :note/agent :t}] (var-tx "n" "(* 2 x)"))
        t (with (base) (var-tx "n" "(* 2 x)"))]
    (is (not= (get-in (pm/program o #{}) [[:form (fid "n" "(* 2 x)")] :order])
              (get-in (pm/program t #{}) [[:form (fid "n" "(* 2 x)")] :order])))
    (is (= {:apply [] :conflicts []} (select-keys (tw o t) [:apply :conflicts])))))

(deftest scratch-and-observations-never-merge
  (let [ours (with (base) [{:note/text "scratch" :note/agent :a1}
                         ;; the agent once, by its identity, under the eval's :by: two maps of one content-id identity in one write
                         ;; resolve to two ids (a Datahike finding, sent to Deep fixes)
                         {:seon.eval/id "e1" :seon.eval/by {:seon.agent/key "a1" :seon.process/pid 7} :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e1 :value 3}}
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
  ;; its input is a merge entry's members
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
  (let [observed (with (base) [{:seon.agent/key "root" :seon.process/pid 9} {:note/text "run facts" :note/agent :t}])]
    (is (empty? (:theirs (tw (base) observed))))
    (is (seq (:theirs (tw (base) (with (base) (var-tx "f" "(dec x)"))))))))

;; Estimate: the world 0.5M (cached) + 2 small dbs + 3 programs of :all, each a read of every data attribute's datoms (a few thousand) ~2M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} platform-records-merge-as-facts
  ;; owner, 2026-11-14: "If there are no conflicts I don't see any reason to not merge everything": root's records on main
  ;; (boot, quiescence, recorded errors) are facts a branch takes by their [:datum e a] keys, as any data; a task's content is its key
  (let [rooted (with (base) [{:seon.system/id :root :seon.system/quiescent {:t 9 :root-causes []}}])
        tasked (with (base) [{:seon.task/id "t/x" :seon.task/plan "better"}])
        p #(pm/program % :all)]
    (is (some #(= :seon.system/quiescent (nth % 2 nil)) (keys (:theirs (pm/three-way (p (base)) (p (base)) (p rooted))))) "main's record is a change the branch takes")
    (is (some #{[:task "t/x"]} (keys (:ours (pm/three-way (p (base)) (p tasked) (p (base)))))) "a task's content is its [:task id] key")))

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

(deftest a-merged-schema-entry-declares-its-own-attribute
  ;; docs/storage.md#the-write-checks-every-datom: a key registered bare (an agent's own datum, no entity map lists it) is an attribute on the
  ;; branch that registers it; merged, its entry arrived without its declaration, so main could not store what the branch could
  (let [base (empty-db)
        src (registered base :app.shape/size :int)
        onto (d/db-with base (pm/merge-tx base base src [[:schema :app.shape/size]] #{}))]
    (is (= :db.type/long (:db/valueType (get (d/schema onto) :app.shape/size))) "the merge carries its declaration")
    (is (= 3 (:app.shape/size (d/entity (d/db-with onto [{:db/id 900 :app.shape/size 3}]) 900))) "and the target can store it")))

(deftest a-merged-entry-imported-from-a-file-compiles-on-a-binary-without-it
  ;; docs/source.md#an-agent-changes-a-schema: real run 9, 3 of 5 agents closed. A key the running binary doesn't hold, imported onto main
  ;; from a file, has to compile when a branch merges it, as a registered key does; red when an imported entry was taken for the binary's
  (let [base (empty-db)
        src (d/db-with base (source/file-tx "resources/seon/schemas/app.shape.edn" "{:app.shape/size :int}"))
        onto (d/db-with base (pm/merge-tx base base src [[:schema :app.shape/size]] #{}))]
    (is (= :db.type/long (:db/valueType (get (d/schema onto) :app.shape/size))) "the merge compiled it and carries its declaration")
    (is (= #{:app.shape/size} (set (keys (program/schema-changes onto)))) "and the branch says the binary lacks it")))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-schema-entry-the-source-deleted-merges-as-its-deletion
  ;; estimate: an empty test db (~1M), two small imports (~0.5M), merge-tx's speculation, registry and declarations (~2M), schema-changes (~0.5M)
  ;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: main deleted :seon.config.proofs/root-item-workers from its
  ;; file, and context's branch could not merge main: merge-tx asked the merged registry, which no longer holds the key, for its
  ;; declaration, and threw :malli.core/invalid-schema (measured 2026-11-14 on a copy of that branch). A deleted entry declares nothing
  (let [file "resources/seon/schemas/app.shape.edn"
        base (d/db-with (empty-db) (source/file-tx file "{:app.shape/size :int :app.shape/name :string}"))
        src (d/db-with base (source/refresh-tx base {file "{:app.shape/name :string}"}))
        onto (d/db-with base (pm/merge-tx base base src [[:schema :app.shape/size]] #{}))]
    (is (= #{:app.shape/name} (set (keys (program/schema-changes onto)))) "the merge deletes the entry, as the source did")))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-merge-speculates-only-its-schema-entries
  ;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: merge-tx reads the merged registry for the schema keys it
  ;; carries, and speculated the whole merge tx to get it, so a merge committed its datoms twice (2.11B ticks and 4.7 GB of a
  ;; 10.3B merge of main, measured 2026-11-14). Only the schema entries' datoms are speculated. Estimate: a test db 1M, a
  ;; registration 3M, two definitions 1M, merge-tx 3M (first measured 32M with three whole programs diffed for its keys)
  (let [b (empty-db)
        src (with (registered b :app.shape/size :int) (var-tx "f" "x") (var-tx "g" "x"))
        ;; its keys named, not diffed: a whole program of a test db reads every schema entry it ships with
        ks (into [[:schema :app.shape/size]] (for [nm ["f" "g"] k [[:form (fid nm "x")] [:var (symbol "app" nm)]]] k))
        seen (atom nil)
        speculate pdb/speculate-db]
    (with-redefs [pdb/speculate-db (fn [db tx] (reset! seen tx) (speculate db tx))]
      (pm/merge-tx b b src ks #{}))
    (is (some #{[:schema :app.shape/size]} ks) "the merge carries a schema key beside the definitions")
    (is (and (seq @seen) (every? #(#{:schema/key :schema/form :schema/file} (:a %)) @seen))
        (str "only schema entries' datoms are speculated: " (vec (distinct (map :a @seen)))))))

(deftest a-tree-the-binary-was-built-from-states-no-schema-change
  ;; docs/source.md#a-program-file: the shipped schema resources, imported as the import reads them (seon.source/file-tx), are no change against the binary built
  ;; from them, so a root and a checkout on one tree key a test alike (seon.agent/branch-inputs mixes changes into :seon.env/schemas). Red on any entry the import writes otherwise
  (let [dir (java.io.File. (str (seon.config/checkout) "/" seon.config/schemas-dir))
        files (sort-by #(.getName ^java.io.File %) (filter #(.endsWith (.getName ^java.io.File %) ".edn") (.listFiles dir)))
        db (reduce (fn [db f] (d/db-with db (source/file-tx (str seon.config/schemas-dir "/" (.getName ^java.io.File f)) (slurp f)))) (empty-db) files)]
    (is (< 20 (count files)) "the shipped resources were read")
    (is (< 500 (count (d/datoms db :aevt :schema/key))) "and their entries imported")
    (is (= {} (program/schema-changes db)))))

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
  ;; changed resource is written back with only its entry changed
  (let [text (slurp (str (seon.config/checkout) "/" facts-path))
        main (let [db (empty-db)] (d/db-with db (source/import-tx db {facts-path text})))
        ;; the entry's line as the file has it (a property added to the entry moves with it), and
        ;; its form with :paused added, read from that text
        status-line (some #(when (str/starts-with? % " :seon.task/reason ") %) (str/split-lines text))
        status (edn/read-string "[:string {:min 1}]")
        ours (-> main (registered :seon.task/reason status) (registered :user/qty :int))
        theirs (registered main :seon.task/merged-t [:int {:min 1}])
        prog #(pm/program % #{})
        r (pm/three-way (prog main) (prog ours) (prog theirs))]
    (is (= [[:schema :seon.task/reason]] (keys (:ours r)))
        "the branch's change is its key; a scratch key is no program")
    (is (= {:form (binding [*print-namespace-maps* false] (sedn/encode status)) :file facts-path} (second (get (:ours r) [:schema :seon.task/reason]))))
    (is (= [[:schema :seon.task/merged-t]] (:apply r)) "a key only the other side changed applies")
    (is (= [] (:conflicts r)) "different keys of one resource do not conflict")
    (testing "one key changed two ways conflicts at its key"
      (let [r2 (pm/three-way (prog main) (prog ours) (prog (registered main :seon.task/reason [:string {:max 5}])))]
        (is (= [[:schema :seon.task/reason] #{:base :ours :theirs}] ((fn [c] [(:key c) (set (filter #(contains? c %) [:base :ours :theirs]))]) (first (:conflicts r2)))))))
    (testing "merged into main: the entry is main's, no longer the binary's, and write-back changes only its bytes"
      (let [main2 (d/db-with main (pm/merge-tx main main ours (keys (:ours r)) #{}))]
        (is (= #{:seon.task/reason} (set (keys (program/schema-changes main2)))))
        (is (= [facts-path] (source/changed-files main2 (:ours r))))
        (is (= {facts-path (str/replace text status-line " :seon.task/reason [:string {:min 1}]")}
               (source/files main2 (source/changed-files main2 (:ours r)))))))))

(deftest the-delta-three-way-reads-data-and-schema-entries-as-the-program-does
  ;; declared data (card one and many) and schema entries, besides forms and definitions:
  ;; delta-three-way, read only where the dbs differ, answers what three-way does
  (let [b (with (empty-db) (var-tx "f" "x") (var-tx "g" "x") (var-tx "h" "x")) ; the test db holds the task :app.m0/tidy and its refs
        task (d/q '[:find ?e . :where [?e :task/name :app.m0/tidy]] b)
        ref (:db/id (first (:task/refs (d/entity b task))))
        o (with b [{:note/text "ours" :note/agent :t} [:db/add task :task/status :done]]
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
           bc (pdb/connect (assoc cfg :branch b))]
       (try
         (d/transact bc ((var-tx "n" "1") @bc))
         (d/transact bc ((var-tx "f" "(inc x)") @bc))
         (d/transact bc [{:note/text "a platform-free scratch note" :note/agent :t}])
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
  (is (= #{'app/f 'app/g} (pm/changed-vars {[:var 'app/f] [nil {:meta #{:arglists}}] [:var 'app/g] [{:meta #{:arglists}} nil] [:schema :app/k] [nil {}]})))
  (is (= #{} (pm/changed-vars {}))))
(deftest a-merge-carries-the-namespace-of-a-form-it-carries
  ;; task seon.system/a-behind-merge-is-bounded-and-merges-main-first (gap 3): a form in a namespace the
  ;; target lacks was carried with its :form/ns ref but not the namespace entity it names, so the form read
  ;; :ns nil there and the target's program differed from the branch's at that key for ever (the writer's
  ;; behind check never settled). The merge carries the namespace entity too.
  (ts/test-branch!
   (fn [cfg conn]
     (let [b (keyword (str (name (:branch cfg)) "-ns"))
           _ (d/transact conn ((var-tx "f" "x") @conn))
           _ (d/branch! conn (:branch cfg) b)
           bc (pdb/connect (assoc cfg :branch b))
           newns (fn [db] (program/record-tx db {:id "app.z/(defn z [x] x)" :src "(defn z [x] x)" :ns 'app.z :ns-form? false
                                                  :defs [{:var 'app.z/z :meta #{:arglists} :contract "[:=> [:cat :int] :int]"}]
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

;; docs/merging.md: an agent's first prove asks for its merge candidate before it has changed anything
(deftest a-branch-with-nothing-of-its-own-merges-nothing
  (let [db (my.test/world)]
    (is (empty? (pm/merge-tx db db db nil #{})))))

(deftest a-namespace-both-sides-made-is-one-entity-after-the-merge
  ;; docs/storage.md#history: the branch and main each made app.z without an (ns) form, main after making another namespace, which moves its next id past the branch's: a namespace's id is its name's content, so the merge leaves one entity and the carried form reads it
  (let [rec (fn [nm] (fn [db] (program/record-tx db {:id (str "app.z/(defn " nm " [x] x)") :src (str "(defn " nm " [x] x)") :ns 'app.z :ns-form? false
                                                     :defs [{:var (symbol "app.z" nm) :meta #{:arglists} :contract "[:=> [:cat :int] :int]"}]
                                                     :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})))
        b (with (mt/world) (var-tx "f" "x"))
        main (with b [{:ns/name 'app.other}] (rec "m"))
        head (with b (rec "z"))
        ks (keys (:ours (pm/delta-three-way b head main #{})))
        after (d/db-with main (pm/merge-tx main b head ks #{}))]
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

;; Estimate: the world, three small dbs and one delta three-way = 80000000 ticks
(deftest ^{:seon.test/estimate {:ticks 80000000}} a-task-main-finished-is-finished-on-the-branch-after-merging-main
  ;; agent lb76's branch (2026-11-14): main closed seon.check/a-selection-derives-what-it-needs-from-its-steps (:done, :cancelled);
  ;; after merging main the branch read it as ready with no proof, and the done rule refused the agent for it. A branch's state after
  ;; merging main is main's plus its own change: what the branch never wrote is main's
  (let [b (with (base) [{:seon.task/id "t/x" :seon.task/plan "Do it." :seon.task/execution :ready}])
        theirs (with b [{:seon.task/id "t/x" :seon.task/execution :cancelled :seon.task/outcome :done}])
        ours (with b (var-tx "f" "(inc x)"))
        r (pm/delta-three-way b ours theirs :all)
        merged (d/db-with ours (pm/merge-tx ours b theirs (:apply r) :all))]
    (is (empty? (:conflicts r)) "nothing both changed")
    (is (= [:cancelled :done] ((juxt :seon.task/execution :seon.task/outcome) (d/entity merged [:seon.task/id "t/x"]))) "main's end of the task")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")))

;; docs/tasks.md#a-task-holds-how-its-work-ended (plan stage 1.6): a task's content is a key of the one three-way merge
;; Estimate: a world 0.5M (cached) + 2 create-tx 1M + 3 three-ways of a few keys 1.5M + 2 merge-tx 1M = 4000000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-tasks-content-is-a-key-of-the-one-merge
  (let [w (base)
        base (d/db-with w (seon.task/create-tx w [{:seon.task/ns 'demo :seon.task/name "x" :seon.task/plan "Make x." :seon.task/proofs #{}}]))
        branch (d/db-with base [[:db/add [:seon.task/id "demo/x"] :seon.task/plan "Make x, as the agent learned it."]])
        main (d/db-with base [[:db/add [:seon.task/id "demo/x"] :seon.task/proofs 'demo-test/x-test]])
        r (pm/delta-three-way base main branch #{})]
    (is (= [[:task "demo/x"]] (filterv #(= :task (first %)) (keys (:theirs r)))) "a branch's task edit is a change of its :task key")
    (testing "changed on one side alone: taken"
      (let [merged (d/db-with main (pm/merge-tx main base branch [[:task "demo/x"]] #{}))]
        (is (= "Make x, as the agent learned it." (:seon.task/plan (d/entity merged [:seon.task/id "demo/x"]))))))
    (testing "a task the receiving branch doesn't hold is never made by its content alone"
      (let [newer (d/db-with branch (seon.task/create-tx branch [{:seon.task/ns 'demo :seon.task/name "y" :seon.task/plan "Make y." :seon.task/proofs #{}}]))]
        (is (empty? (pm/merge-tx main base newer [[:task "demo/y"]] #{})))))
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

;; Estimate: the world 0.5M (cached) + 4 small transactions + one delta three-way at :all over a few keys ~5M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-fact-a-branch-holds-only-because-main-wrote-it-is-mains
  ;; agent lb76's branch (2026-11-14): 6,907 conflicts, nearly all facts main wrote that the branch held stale (carried before its
  ;; base moved, never written by it), and embeddings each side computes. Facts are facts at the transaction that wrote them: a
  ;; side changed [e a] only if its own transaction wrote it; a derived entity ({:seon.db/derived true}) never merges
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data tx :tx-meta {:seon.tx/branch branch}})))
        w (as (base) :main [{:seon.agent/key "a1" :seon.agent/status :busy}])
        branch (as w :main/b ((var-tx "f" "(inc x)") w))
        base' (as w :main [[:db/retract [:seon.agent/key "a1"] :seon.agent/status :busy]])
        main (as base' :main [[:db/add [:seon.agent/key "a1"] :seon.agent/status :idle]])
        r (pm/delta-three-way base' branch main :all)
        merged (d/db-with branch (pm/merge-tx branch base' main (:apply r) :all))]
    (is (empty? (:conflicts r)) (str "a stale value of main's is no conflict: " (pr-str (:conflicts r))))
    (is (= :idle (:seon.agent/status (d/entity merged [:seon.agent/key "a1"]))) "main's fact")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")))

;; Estimate: the world 0.5M (cached) + 5 small transactions ~1M + one merge-main (a diff of a few datoms, program-of on 2 keys of three
;; dbs) ~3M + one db-with ~0.5M = 10000000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} merge-main-is-main-plus-the-branchs-own-change
  ;; docs/merging.md#a-branch-merges-main-in, the agreed seon.merge/merge-main: the merged branch is main's head plus what the branch
  ;; wrote since base; main's other moves are main's already and never read; a key both changed stops the merge, nothing written
  (let [as (fn [db branch tx] (:db-after (d/with db {:tx-data (if (fn? tx) (tx db) tx) :tx-meta {:seon.tx/branch branch}})))
        b0 (as (base) :main [{:seon.agent/key "a1" :seon.agent/status :busy}])
        head (as b0 :main/b (var-tx "f" "(inc x)"))
        main (-> b0 (as :main (var-tx "g" "(dec x)")) (as :main [{:seon.agent/key "a2" :seon.agent/status :idle}]))
        {:keys [tx conflicts]} (pm/merge-main b0 head main)
        merged (d/db-with main tx)]
    (is (nil? conflicts) (pr-str conflicts))
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch's own change")
    (is (= (fid "g" "(dec x)") (:form/id (:var/form (d/entity merged [:var/name 'app/g])))) "main's, as main holds it")
    (is (= :idle (:seon.agent/status (d/entity merged [:seon.agent/key "a2"]))) "and main's facts")
    (testing "a key both changed, differently, stops it"
      (let [main' (as main :main (var-tx "f" "(* 2 x)"))]
        (is (seq (:conflicts (pm/merge-main b0 head main'))) "a conflict, and no tx")))))


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
        {:keys [tx conflicts]} (pm/merge-main b0 head main)
        merged (d/db-with main tx)]
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
        main (d/db-with b0 [[:db/add [:seon.agent/key "root"] :seon.agent/status :stopped]])
        {:keys [tx conflicts]} (pm/merge-main b0 head main)
        merged (d/db-with main tx)]
    (is (nil? conflicts) (str "a fact the branch holds from before its base is no change of its: " (pr-str conflicts)))
    (is (= :stopped (:seon.agent/status (d/entity merged [:seon.agent/key "root"]))) "main's fact, never the shared old value")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")))

;; Estimate: the world 0.5M (cached) + 1M a task's create + 3 small transactions ~1M + one merge-main and one delta three-way of a few keys
;; ~4M (an as-of program read on the overtaken key) = 10000000 ticks
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
        k [:task "demo/x"]]
    (is (= [k] (mapv :key (:conflicts (pm/merge-main b0 head main)))) "merging main into the branch: a conflict, not the branch's older plan")
    (is (= [k] (mapv :key (:conflicts (pm/delta-three-way b0 head main :all)))) "and merging the branch to main")
    (testing "a branch write after main's is the branch's change"
      (let [head' (as head :main/b (+ t0 4000) [[:db/add [:seon.task/id "demo/x"] :seon.task/plan "Make x, decided."]])]
        (is (empty? (:conflicts (pm/merge-main b0 head' main))))))))

;; Estimate: the world 0.5M (cached) + 4 small transactions ~1M + one merge-main and one merge-tx of a few datoms ~4M = 8000000 ticks
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
        {:keys [tx conflicts]} (pm/merge-main b0 head main)
        merged (d/db-with main tx)]
    (is (nil? conflicts) (pr-str conflicts))
    (is (empty? (d/datoms merged :eavt e)) "merging main into the branch: no nameless entity")
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch keeps its own change")
    (testing "and merging the branch to main"
      (let [r (pm/delta-three-way b0 head main :all)
            onto (d/db-with main (pm/merge-tx main b0 head (keys (:ours r)) :all))]
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
        {:keys [conflicts]} (pm/merge-main b0 head main)
        k (:key (first conflicts))
        with-entry (as head :main/b (pm/unmerged-tx head conflicts (:max-tx main)))
        resolved (as with-entry :main/b (pm/resolve-tx with-entry k))
        r (pm/merge-main b0 resolved main)
        merged (d/db-with main (:tx r))]
    (is (= [(vk "f")] (mapv :key conflicts)) "both changed f: a conflict")
    (is (nil? (:conflicts r)) (str "resolved, the next merge is clean: " (pr-str (:conflicts r))))
    (is (= (fid "f" "(inc x)") (:form/id (:var/form (d/entity merged [:var/name 'app/f])))) "the branch's value stands")
    (is (empty? (pm/entries merged)) "and the entry is settled")
    (testing "main moving the key again is a new conflict"
      (is (seq (:conflicts (pm/merge-main b0 resolved (as main :main (var-tx "f" "(- x)")))))))))

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
           (is (= 1 (count shown)) (str "and the agent is shown it to settle: " (pr-str shown))))
         (finally (d/release bc)))))))
