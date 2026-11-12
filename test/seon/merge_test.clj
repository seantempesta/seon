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
            [seon.program :as program] [seon.doc] [seon.config] [seon.source] [seon.profile :as profile]
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
  (let [b (base) full (pm/three-way (pm/program b) (pm/program o) (pm/program t))]
    (is (= full (pm/delta-three-way b o t)) "delta-three-way answers what three-way does")
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
        r (pm/delta-three-way b ours theirs)
        addrs #(set (map :v (d/datoms % :avet :seon.doc/address)))]
    (is (= #{[:section "demo/p.md#p"] [:section "demo/new.md#new"] [:section "demo/gone.md#gone"]} (set (filter #(= :section (first %)) (:apply r)))) "main's edit, its new page and its removed one apply")
    (is (= [[:section "demo/both.md#both"]] (mapv :key (:conflicts r))) "a section both changed is a conflict, as a var is")
    (is (empty? (:apply (pm/delta-three-way theirs theirs theirs))) "a branch holding main's wiki takes nothing")))

;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: a branch's own doc edit merges as code does, and its
;; page is written back with the merge (seon.source/changed-files names it, seon.doc/files draws it), so the next load of
;; docs/ reads what merged (task seon.doc/any-markdown-file-is-docs-it-writes-back)
(deftest a-branch-doc-edit-merges-and-its-page-is-written-back
  (let [load (fn [db pages] (d/db-with db (seon.doc/load-tx db "demo" pages)))
        b (load (base) {"demo/p.md" "# P\n\nOne.\n" "demo/q.md" "# Q\n\nUntouched.\n"})
        edited (load b {"demo/p.md" "# P\n\nTwo.\n"})
        r (pm/delta-three-way b edited b)
        merged edited
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
        r (pm/delta-three-way main ours (set-k main :x/b "6"))]
    (is (= [[:config :x/a]] (keys (:ours r))) (pr-str r))
    (is (= [[:config :x/b]] (:apply r)))
    (is (empty? (:conflicts r)))
    (is (= {:config [:x/a]} (pm/divergence (:ours r))))
    (is (= [[:config :x/a] #{:base :ours :theirs}] ((fn [c] [(:key c) (set (filter #(contains? c %) [:base :ours :theirs]))]) (first (:conflicts (pm/delta-three-way main ours (set-k main :x/a "7")))))))
    (let [merged ours
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
        r (pm/delta-three-way main ours main)]
    (is (= {[:script "bin/x"] [{:file "bin/x" :text text :mode "100755"} {:file "bin/x" :text "#!/bin/sh\necho 2\n" :mode "100755"}]} (:ours r)) (pr-str r))
    (is (= {:scripts ["bin/x"]} (pm/divergence (:ours r))))
    (is (= [[:script "bin/x"] #{:base :ours :theirs}] ((fn [c] [(:key c) (set (filter #(contains? c %) [:base :ours :theirs]))]) (first (:conflicts (pm/delta-three-way main ours theirs))))) "both sides changing it is an unmerged entry")
    (is (= "" (:seon.script/says (d/entity ours [:file/path "bin/x"]))) "what it derives rides with it")
    (let [p "resources/seon/schemas/x.edn"
          db (let [db (empty-db)] (d/db-with db (seon.source/import-tx db {p "{:x/a :int}\n"})))
          moved (d/db-with db [{:file/path p :file/text "{:x/a :int} \n"}])]
      (is (= {} (:ours (pm/delta-three-way db moved db))) "a schema resource's file bears no key: its entries do"))))

(deftest unmap-merges-as-delete
  (let [ours (with (base) (unmap "g"))
        r (tw ours (base))
        main' ours]
    (is (= #{(vk "g") [:form (fid "g" "x")]} (set (keys (:ours r)))) "the definition, and the form it leaves dead")
    (is (nil? (seon.doc/source-of main' 'app/g)))
    (is (not (program/live? main' (pdb/eid main' :form/id (fid "g" "x")))) "the form is dead on main, as on the branch")
    (is (= [#{:base :theirs}] (map (fn [c] (set (filter #(contains? c %) [:base :ours :theirs]))) (filter #(= :var (first (:key %))) (:conflicts (tw ours (with (base) (var-tx "g" "(dec x)"))))))))))

(deftest latest-redefinition-wins
  (let [ours (with (base) (var-tx "f" "1") (var-tx "f" "2") (var-tx "f" "3"))]
    (is (= (fid "f" "3") (:form (second (get (:ours (tw ours (base))) (vk "f"))))))
    (is (= [(defn-src "f" "3")] (for [[[k] [_ o]] (:ours (tw ours (base))) :when (and (= :form k) o)] (:src o)))
        "only the last form is live: each redefinition superseded the one before")))

(deftest a-form-s-place-does-not-make-it-another
  (let [o (with (base) [{:note/text "first" :note/agent :t}] (var-tx "n" "(* 2 x)"))
        t (with (base) (var-tx "n" "(* 2 x)"))]
    (is (not= (get-in (pm/program o) [[:form (fid "n" "(* 2 x)")] :order])
              (get-in (pm/program t) [[:form (fid "n" "(* 2 x)")] :order])))
    (is (= {:apply [] :conflicts []} (select-keys (tw o t) [:apply :conflicts])))))

(deftest scratch-and-observations-never-merge
  (let [ours (with (base) [{:note/text "scratch" :note/agent :a1}
                         ;; the agent once, by its identity, under the eval's :by: two maps of one content-id identity in one write
                         ;; resolve to two ids (a Datahike finding, sent to Deep fixes)
                         {:seon.eval/id "e1" :seon.eval/by {:seon.agent/key "a1" :seon.process/pid 7} :seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e1 :value 3}}
                         {:task/name :x/y :task/status :done}])]
    (is (= (pm/program (base)) (pm/program ours)))
    (is (empty? (:ours (tw ours (base)))))))

(deftest behind-means-main-program-moved
  (let [observed (with (base) [{:seon.agent/key "root" :seon.process/pid 9} {:note/text "run facts" :note/agent :t}])]
    (is (empty? (:theirs (tw (base) observed))))
    (is (seq (:theirs (tw (base) (with (base) (var-tx "f" "(dec x)"))))))))

(def ^:private facts-path "resources/seon/schemas/seon.facts.edn")

(defn- registered
  "db after registering form v as schema k on it, as my.schema/register! does."
  [db k v] (d/db-with db (:tx (schema/registration db k v (= "user" (namespace k))))))

(deftest a-tree-the-binary-was-built-from-states-no-schema-change
  ;; docs/source.md#a-program-file: the shipped schema resources, imported as the import reads them (seon.source/file-tx), are no change against the binary built
  ;; from them, so a root and a checkout on one tree key a test alike (seon.agent/branch-inputs mixes changes into :seon.env/schemas). Red on any entry the import writes otherwise
  (let [dir (java.io.File. (str (seon.config/checkout) "/" seon.config/schemas-dir))
        files (sort-by #(.getName ^java.io.File %) (filter #(.endsWith (.getName ^java.io.File %) ".edn") (.listFiles dir)))
        db (reduce (fn [db f] (d/db-with db (source/file-tx (str seon.config/schemas-dir "/" (.getName ^java.io.File f)) (slurp f)))) (empty-db) files)]
    (is (< 20 (count files)) "the shipped resources were read")
    (is (< 500 (count (d/datoms db :aevt :schema/key))) "and their entries imported")
    (is (= {} (program/schema-changes db)))))

(deftest a-merge-base-cut-before-an-attribute-diffs-it-as-the-heads
  ;; found 2026-10-27 through 0.7 (agent a1, merge-main! after the merge above declared :form/ops): the
  ;; content diff read every attribute either side declares from a base cut before :form/ops, and
  ;; datahike.db/datoms-diff resolved it against the base only (not defined in current schema)
  (let [full (empty-db)
        base (datahike.core/empty-db (into {} (map (fn [[k v]] [k (dissoc v :db/id)])) (dissoc (d/schema full) :form/ops))
                                     {:schema-flexibility :write})
        head (d/db-with base [(assoc (dissoc (get (d/schema full) :form/ops) :db/id) :db/ident :form/ops) [:db/add 100 :form/ops :seon.op/x]])]
    (is (= [[100 :form/ops :seon.op/x]]
           (mapv (juxt :e :a :v) (filter #(= :form/ops (:a %)) (#'pm/content-datoms base head))))
        "the head's datoms of the attribute the base lacks are what moved")))

(deftest content-datoms-given-a-diff-are-its-content-ones
  ;; docs/cost.md#a-diff-per-attribute-costs-a-descent-each: a caller that read the whole :eavt diff hands it over
  ;; and nothing is diffed again; a transaction's instant is no key's content
  (let [b (base)
        branch (with b (var-tx "f" "(inc x)"))
        whole (apply concat (d/datoms-diff b branch :eavt []))
        mine (pm/content-datoms b branch)]
    (is (seq mine) "the branch changed the program")
    (is (= (set mine) (set (pm/content-datoms b branch whole))) "the content ones among the whole diff are the ones read per attribute")
    (is (some #(= :db/txInstant (:a %)) whole) "the whole diff holds the transactions' instants")
    (is (not-any? #(= :db/txInstant (:a %)) mine) "which are no content")
    (is (= (pm/delta-changes b branch) (pm/delta-changes b branch mine) (pm/delta-changes b branch whole)))))

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
        prog #(pm/program %)
        r (pm/three-way (prog main) (prog ours) (prog theirs))]
    (is (= [[:schema :seon.task/reason]] (keys (:ours r)))
        "the branch's change is its key; a scratch key is no program")
    (is (= {:form (binding [*print-namespace-maps* false] (sedn/encode status)) :file facts-path} (second (get (:ours r) [:schema :seon.task/reason]))))
    (is (= [[:schema :seon.task/merged-t]] (:apply r)) "a key only the other side changed applies")
    (is (= [] (:conflicts r)) "different keys of one resource do not conflict")
    (testing "one key changed two ways conflicts at its key"
      (let [r2 (pm/three-way (prog main) (prog ours) (prog (registered main :seon.task/reason [:string {:max 5}])))]
        (is (= [[:schema :seon.task/reason] #{:base :ours :theirs}] ((fn [c] [(:key c) (set (filter #(contains? c %) [:base :ours :theirs]))]) (first (:conflicts r2)))))))))

(deftest the-delta-three-way-reads-schema-entries-as-the-program-does
  ;; schema entries, besides forms and definitions:
  ;; delta-three-way, read only where the dbs differ, answers what three-way does
  (let [b (with (empty-db) (var-tx "f" "x") (var-tx "g" "x") (var-tx "h" "x")) ; the test db holds the task :app.m0/tidy and its refs
        task (d/q '[:find ?e . :where [?e :task/name :app.m0/tidy]] b)
        ref (:db/id (first (:task/refs (d/entity b task))))
        o (with b [{:note/text "ours" :note/agent :t} [:db/add task :task/status :done]]
                [{:schema/key :x/y :schema/form "[:int]" :schema/file "x.edn"}])
        t (with b [[:db/retract task :task/refs ref] [:db/add task :task/plan "theirs"]] (var-tx "g" "(dec x)"))]
    (let [full (pm/three-way (pm/program b) (pm/program o) (pm/program t))]
      (is (= full (pm/delta-three-way b o t)))
      (is (seq (:ours full))))
    (is (= (pm/changes (pm/program b) (pm/program o)) (pm/delta-changes b o)))))

(deftest divergence-names-each-change-a-merge-carries
  ;; owner D4 (2026-10-22): the agent sees, every turn, every change its branch's merge will
  ;; carry back to main: what it added, redefined and removed, and the schemas it registered,
  ;; projected from the one diff a turn reads (seon.agent/branch-changes); a schema registered
  ;; with no file is a scratch one, never merged, so it is none of them
  (let [b (with (base) [{:schema/key :app/k :schema/form ":int" :schema/file "app.edn"}])
        branch (with b (var-tx "f" "(inc x)") (unmap "g") (var-tx "c" "x")
                     [{:schema/key :app/k :schema/form ":string" :schema/file "app.edn"} {:schema/key :app/n :schema/form ":int" :schema/file "app.edn"}
                      {:schema/key :app/scratch :schema/form ":int"}])
        ch (pm/delta-changes b branch)]
    (is (= {:added ['app/c] :changed ['app/f] :removed ['app/g] :schemas [:app/k :app/n]} (pm/divergence ch)))
    (is (= #{'app/c 'app/f 'app/g} (pm/changed-vars ch)) "the changed vars are its vars")
    (is (= ch (pm/delta-changes b branch (apply concat (d/datoms-diff b branch :eavt [])))) "given the datoms that differ, nothing is diffed again and the changes are the same")
    (is (= {} (pm/divergence (pm/delta-changes b b))) "an unchanged branch carries nothing")))

(deftest a-branch-that-merged-main-carries-only-its-own-changes
  ;; root, 2026-10-22: entity ids are a branch's own, and a merge applies main's forms by
  ;; content under the branch's ids; against its merge base (main's head once merged), the
  ;; branch carries only what it changed itself, never main's changes
  (let [main (with (base) (var-tx "f" "(inc x)") (var-tx "m" "x"))
        ;; the branch writes entities of its own, adds k, then merges main: main's forms by content
        merged (with (base) [{:db/id "x0" :form/id "(comment 0)" :form/src "x"}] (var-tx "k" "x") (var-tx "f" "(inc x)") (var-tx "m" "x"))]
    (is (= {:added ['app/k]} (pm/divergence (pm/delta-changes main merged))) "only k, the branch's own")
    (is (= #{'app/k} (pm/changed-vars (pm/delta-changes main merged))) "and the tests it reaches are k's")))

(deftest changed-vars-are-the-var-keys-of-a-change
  ;; found red on main (2026-10-27): it read each key [kind x] one level too deep, so any branch
  ;; that changed something threw "nth not supported on this type: Keyword" in every turn's test plan
  (is (= #{'app/f 'app/g} (pm/changed-vars {[:var 'app/f] [nil {:meta #{:arglists}}] [:var 'app/g] [{:meta #{:arglists}} nil] [:schema :app/k] [nil {}]})))
  (is (= #{} (pm/changed-vars {}))))
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
