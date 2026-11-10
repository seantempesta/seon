(ns seon.source-test
  "Seon's own source is the program (the self-improvement loop, owner 2026-10-21): next/src
  read through the :source effect's performer, every form recorded with the compiler's
  account and its exact text, written back byte for byte, a merged change rewriting only
  its form, and the written files committed on the merges branch through the :git effect's
  performer. Never the live tree: write-back and commits go to a copy under target/."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as sh]
            [clojure.string :as str] [clojure.edn :as edn] [seon.config :as config]
            [datahike.api :as d]
            [seon.db :as pdb] [seon.cost :as cost] [seon.render.ui]
            [seon.fx :as fx] [seon.contract :as contract]
            [seon.merge :as pm]
            [seon.program :as program]
            [seon.source :as source]
            [seon.program-test :as pt] [seon.stage :as stage] [seon.root :as root] [seon.doc]))

(defn- empty-db [] (ts/test-db))

(declare hold!)

(def ^:private here (seon.config/checkout))

(def ^:private imp-text
  "A fixture file holding each kind of form an import records: a docstring ns with a require, a
  value, a macro, a declare, a private function, a contracted function calling it, a multimethod and
  its method, a defonce, a protocol, the same form text twice (told apart by occurrence), comments
  between forms and a tail after the last."
  (str "(ns app.imp\n  \"A fixture file.\"\n  (:require [clojure.string :as str]))\n\n"
       ";; a value\n(def banned #{'a 'b})\n\n"
       "(defmacro twice\n  \"Runs x twice.\"\n  [x] `(do ~x ~x))\n\n"
       "(declare later)\n\n"
       "(defn- topo [xs] (vec (sort xs)))\n\n"
       "(defn load-order\n  \"Sorted.\"\n  {:malli/schema [:=> [:cat [:sequential :int]] [:vector :int]]}\n  [xs] (topo xs))\n\n"
       "(defmulti area :shape)\n(defmethod area :sq [{:keys [s]}] (* s s))\n\n"
       "(defonce counter (atom 0))\n\n"
       "(defprotocol Go (go [this]))\n\n"
       "(defn later [] (str/upper-case \"x\"))\n\n"
       "(comment :same)\n\n(comment :same)   ; twice\n\n"
       ";; the tail"))

(defn- src-files
  "The files an import is shown on: two small real files of next/src as the :source effect reads
  them (my.fx, seon.edn: the namespaces this process holds, so their forms are analyzed, never run)
  and imp-text, one of each kind of form: {relative path text}."
  []
  (let [r (fx/read-source {:req {:root here :dirs ["src"]}})]
    (is (map? (:value r)) (pr-str r))
    (assoc (select-keys (:value r) ["src/my/fx.clj" "src/seon/edn.clj"]) "src/app/imp.clj" imp-text)))

(defn- scratch-dir
  "A new empty directory under target/ (the live tree is never written)."
  [label]
  (let [d (str here "/target/tmp/source-test-" label "-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(defn- imported
  "Main's db with the files imported (a real file's namespace is required first); its namespaces are this process's (the
  running generation), so each form is analyzed, never run."
  [files]
  (doseq [[path text] files :when (.exists (java.io.File. (str here "/" path)))] (require (source/file-ns text)))
  (let [db (empty-db)] (d/db-with db (source/import-tx db files))))

;; docs/sharing.md: an inserted form renumbered every later form in its file, on entities spread through EAVT by content id, and a merge ignores a form's place, so branches held it stale
(deftest a-form-inserted-writes-one-place
  (let [text (fn [& fs] (str "(ns app.pl)\n\n" (str/join "\n\n" fs) "\n"))
        fs (mapv #(str "(defn f" % " [] " % ")") (range 8))
        db (d/db-with (ts/test-db) (source/import-tx (ts/test-db) {"src/app/pl.clj" (apply text fs)}))
        places (fn [tx] (filter #(and (map? %) (:form/order %)) tx))
        tx (source/refresh-tx db {"src/app/pl.clj" (apply text (concat (subvec fs 0 3) ["(defn new-one [] 9)"] (subvec fs 3)))})
        after (d/db-with db tx)
        order (fn [db] (map :form/src (sort-by :form/order (d/q '[:find [(pull ?f [:form/src :form/order]) ...] :where [?n :ns/name app.pl] [?f :form/ns ?n] [?f :form/order]] db))))]
    (is (= 1 (count (places tx))) (str "one place written, the new form's: " (pr-str (places tx))))
    (is (= (concat ["(ns app.pl)"] (subvec fs 0 3) ["(defn new-one [] 9)"] (subvec fs 3)) (order after)) "and it sorts where the file has it")
    (is (= (apply text (concat (subvec fs 0 3) ["(defn new-one [] 9)"] (subvec fs 3))) (source/file-text after 'app.pl)) "the file reads back whole")
    (let [moved (apply text (concat ["(defn f7 [] 7)"] (subvec fs 0 7)))
          tx2 (source/refresh-tx after {"src/app/pl.clj" moved})
          a2 (d/db-with after tx2)]
      (is (= moved (source/file-text a2 'app.pl)) "a form moved reads back where the file has it"))))

(deftest importing-src-records-every-form-exactly
  (let [files (src-files)
        db (imported files)
        forms-of (fn [nsym] (d/q '[:find ?id ?src :in $ ?n :where [?ns :ns/name ?n] [?f :form/ns ?ns] [?f :form/id ?id] [?f :form/src ?src]] db nsym))]
    (is (= 3 (count files)) "two real files and the fixture")
    (testing "every file: one namespace with its file, one form per form read, each form's text exact"
      (doseq [[path text] files
              :let [nsym (source/file-ns text) read (:forms (source/read-forms 'user text))]]
        (is (= path (d/q '[:find ?p . :in $ ?n :where [?e :ns/name ?n] [?e :ns/file ?p]] db nsym)) path)
        (is (= (count read) (count (forms-of nsym))) path)
        (is (= (frequencies (map :src read)) (frequencies (map second (forms-of nsym)))) path)))
    (testing "definitions from the compiler, pointing to their forms"
      (let [row (fn [sym] (d/pull db '[:var/meta :var/contract {:var/form [:form/src :form/refs]}] [:var/name sym]))]
        (is (str/starts-with? (get-in (row 'app.imp/load-order) [:var/form :form/src]) "(defn load-order\n"))
        (is (contains? (set (:var/meta (row 'app.imp/load-order))) :arglists))
        (is (= "[:=> [:cat [:sequential :int]] [:vector :int]]" (:var/contract (row 'app.imp/load-order))))
        (is (contains? (set (get-in (row 'app.imp/load-order) [:var/form :form/refs])) 'app.imp/topo)
            "a reference resolved by the compiler")
        (is (contains? (set (:var/meta (row 'app.imp/twice))) :macro))
        (is (not-any? (set (:var/meta (row 'app.imp/banned))) [:arglists :macro :test]))
        (is (= "[:=> [:cat :any] :string]" (:var/contract (row 'seon.edn/encode))) "a real file's contract")
        (is (every? #(contains? (set (:var/meta (row %))) :arglists) '[seon.edn/decode my.fx/call]))))
    (testing "the platform's own namespaces are not loaded from main: the binary is the running generation"
      (let [db (imported (dissoc files "src/app/imp.clj"))]
        (is (= {:loaded (count (program/live-forms (empty-db))) :errors []} (program/load-all! db))
            "load-all! loads the test store's example program, and none of the namespaces imported from files")
        (is (= [] (program/load-changes! db (vec (for [dt (d/datoms db :aevt :form/src)] [:db/add (:e dt) :form/src (:v dt)]))))
          "and so does load-changes!")))))

(deftest root-loads-the-program-and-leaves-test-namespaces-unevaluated
  ;; task seon.root/root-holds-no-test-code, docs/source.md#a-process-holds-the-tests-only-when-it-runs-them:
  ;; root imports test/ as facts and evaluates its src; the fork server (load-all! with no option) evaluates
  ;; both, so every agent inherits main's tests. A form changed later follows the same rule
  (let [files {"src/x/rnt_prog.clj" "(ns x.rnt-prog)\n\n(defn k [] 1)\n"
               "test/x/rnt_thing_test.clj" "(ns x.rnt-thing-test (:require [clojure.test :refer [deftest is]] [x.rnt-prog :as p]))\n\n(deftest t (is (= 1 (p/k))))\n\n(defn helper [] 2)\n"}
        db (d/db-with (empty-db) (source/import-tx (empty-db) files))
        bound? (fn [s] (boolean (some-> (find-ns (symbol (namespace s))) (ns-resolve (symbol (name s))) bound?)))
        forms-of (fn [db nsym] (d/q '[:find [?e ...] :in $ ?n :where [?ns :ns/name ?n] [?e :form/ns ?ns]] db nsym))]
    (is (= #{'x.rnt-thing-test} (program/test-nss db)) "a namespace read from test/ is a test's; src/'s is the program's")
    (testing "root's load: the program's forms, none of the test namespace's"
      (let [r (program/load-all! db {:tests? false})]
        (is (= [] (:errors r)))
        (is (true? (bound? 'x.rnt-prog/k)) "src/ is evaluated")
        (is (not (bound? 'x.rnt-thing-test/t)) "the deftest is not")
        (is (not (bound? 'x.rnt-thing-test/helper)) "nor a helper beside it")))
    (testing "a change main makes to the test namespace: root's load-changes! leaves it, the fork server's loads it"
      (let [ops (vec (for [e (forms-of db 'x.rnt-thing-test) :let [s (:form/src (d/entity db e))]] [:db/add e :form/src s]))]
        (is (seq ops))
        (is (= [] (program/load-changes! db ops {:tests? false})))
        (is (not (bound? 'x.rnt-thing-test/t)) "root's leaves it")
        (is (= [] (program/load-changes! db ops)))
        (is (true? (bound? 'x.rnt-thing-test/t)) "the default loads it")))
    (testing "and load-all! with no option (the fork server's, an agent's) holds it all"
      (is (= [] (:errors (program/load-all! db))))
      (is (true? (bound? 'x.rnt-thing-test/helper))))))

(deftest a-config-entry-writes-back-only-its-own-bytes
  ;; docs/source.md#a-program-file: the config file is one entity per entry, each form its exact text with its tags unread, and a write-back changes only what an entry changed
  (let [p config/shipped-file
        text ";; the file's own words\n{;; a's words\n :x/a 1 ;; beside a\n ;; b's words\n :x/b #or [#env NOPE 2]\n :x/c #ref [:x/a]}\n"
        db (let [db (empty-db)] (d/db-with db (source/import-tx db {p text})))
        forms (fn [db] (into {} (for [dt (d/datoms db :aevt :config/form)] [(:config/key (d/entity db (:e dt))) (:v dt)])))
        out (fn [db] (get (source/files db [p]) p))]
    (is (= {:x/a "1" :x/b "#or [#env NOPE 2]" :x/c "#ref [:x/a]"} (forms db)))
    (is (= text (out db)) "as imported, byte for byte")
    (is (= (str/replace text " :x/a 1 " " :x/a 5 ") (out (d/db-with db [{:config/key :x/a :config/form "5"}]))) "a changed value")
    (is (= (str/replace text "#ref [:x/a]}" "#ref [:x/a]\n :x/d 9}")
           (out (d/db-with db [{:config/key :x/d :config/form "9" :config/file p}]))) "a new key")
    (let [gone (d/db-with db (source/edit-tx db p (str/replace text " ;; b's words\n :x/b #or [#env NOPE 2]\n" "")))]
      (is (= [:x/a :x/c] (sort (keys (forms gone)))) "an edit that takes a key out retracts its entry"))))

(defn- config-root!
  "A root on a clone of this checkout, its three tasks its own (loaded once it listens), and how a test speaks to it: :at-root, :in (an agent's REPL), :ask, :restart!. See docs/config.md#a-process-holds-its-branchs-config."
  []
  (let [dir (scratch-dir "config")
        tree (str dir "/tree")
        runs (str dir "/runs")
        _ (.mkdirs (java.io.File. (str runs "/tasks")))
        cloned (sh/sh "git" "clone" "-q" "--local" here tree)
        _ (spit (str runs "/tasks/cfgt.edn") (pr-str (vec (for [n ["one" "two" "three"]] {:seon.task/name n :seon.task/plan "Work." :seon.task/kind :work}))))
        options {:store (ts/clone-process-store! (str runs "/store")) :main "main" :runs runs
                 :args ["--set" (str ":seon.config.source/root=" (pr-str tree))]}
        root (atom (ts/start-root! options))
        ask (fn [m] (ts/ask @root m))
        ;; a boot resumes main and reads no task file: its tasks are a load a person asks for (docs/source.md#indexing-a-tree)
        _ (ask {:op :eval :agent "root" :code "(my.root/load-tasks! sys)"})]
    {:tree tree :cloned cloned :root root :ask ask
     :restart! (fn [] (ts/stop-root! @root) (reset! root (ts/start-root! options)))
     :at-root (fn [code] (ts/value-of (:response (ask {:op :eval :agent "root" :code code}))))
     :in (fn [a code] (:value (ts/value-of (:response (ask {:op :eval :agent "root" :code (str "@(c/submit! sys " a " " (pr-str code) " {:origin :outside})")})))))}))

(def ^:private a-branchs-key :seon.config.root/a-branchs-own-key)

(def ^:private reads-the-key (str "(try (seon.config/value " a-branchs-key ") (catch Exception _ :nowhere))"))

(def ^:private adds-the-key
  (let [k a-branchs-key]
    (str "(do (my.schema/register! " k " [:int {:min 0 :seon.config/unit \"bytes\" :description \"How much.\"}])"
         " (my.schema/register! :seon.config/config (conj (my.schema/forms :seon.config/config) [" k " {:optional true} " k "]))"
         " (seon.repl/edit! seon.config/shipped-file \" :seon.config.root/death-stderr-bytes\" \" " k " 7\\n :seon.config.root/death-stderr-bytes\"))")))

(deftest a-branchs-config-is-its-own-and-reaches-the-next-process
  ;; docs/config.md#a-process-holds-its-branchs-config, reproducing 2026-11-07: one agent's unmerged spit of a key with no schema into the config file reached a second agent's live test
  ;; and stopped a new process. Here it acts on nothing, a boot reads no file and a re-index that meets it says which file and which commit, and a key a branch adds merges, writes back byte-preserved and reads at the next process.
  (let [{:keys [tree cloned root ask restart! at-root in]} (config-root!)
        k a-branchs-key
        file (str tree "/" config/shipped-file)
        held (slurp file)
        bad (str/replace-first held "{;; ==== root" "{:seon.config.nope/key 1\n ;; ==== root")]
    (try
      (is (zero? (:exit cloned)) (:err cloned))
      (is (:port @root) (:log @root))
      (let [one (:agent (ask {:op :attach :task "cfgt/one"}))
            two (:agent (ask {:op :attach :task "cfgt/two"}))]
        (in one (str "(spit " (pr-str file) " " (pr-str bad) ")"))
        (is (not= held (slurp file)) "the write reached the disk: an agent keeps it")
        (is (= :reads (in two "(or (seon.fx/unreadable seon.config/shipped-file (seon.config/shipped-text)) :reads)")) "the other agent's reader of the shipped file sees what its branch states")
        (is (= :nowhere (in two "(try (seon.config/value :seon.config.nope/key) (catch Exception _ :nowhere))")))
        (spit file held)
        (testing "a key added on a branch reads at that branch's REPL, and no other's"
          (is (= :nowhere (in one reads-the-key)))
          (in one adds-the-key)
          (is (= 7 (in one reads-the-key)))
          (is (= [k] (in one "(:config (my.branch/changes))")))
          (is (= :nowhere (in two reads-the-key))))
        (testing "it merges, and writes back byte-preserved"
          (is (true? (at-root "(-> @(seon.root/call! sys {:op :write :req {:op :octopus-merge :branches [:task-cfgt-one] :data-attrs #{}}}) :results first :merged?)")))
          (is (= 7 (at-root reads-the-key)) "root holds main's")
          (is (= 7 (at-root (str "(get-in (datahike.api/pull @(:conn sys) [{:seon.root/config [" k "]}] [:seon.root/id :root]) [:seon.root/config " k "])"))) "and main's config in force, the entity root's :seon.root/config names, says so")
          (is (= (let [end (str/last-index-of held "}")] (str (subs held 0 end) "\n " k " 7" (subs held end)))
                 (at-root "(get (seon.source/files @(:conn sys) [seon.config/shipped-file]) seon.config/shipped-file)")))))
      (testing "a boot on a file that doesn't load comes up and keeps main's, reading no file; a re-index says which file and which commit"
        (spit file bad)
        (restart!)
        (is (:port @root) (:log @root))
        (is (nil? (at-root "(:error (:seon.root/source (datahike.api/pull @(:conn sys) [:seon.root/source] [:seon.root/id :root])))")) "the boot read no file")
        (let [err (str (at-root "(:seon.error/message (my.root/import! sys))"))]
          (is (str/includes? err config/shipped-file) err)
          (is (str/includes? err "seon.config.nope/key") err)
          ;; the commit it names held a file that loads: a seed may name an earlier commit of the same content, so it's read, not compared with HEAD
          (let [[_ commit] (re-find #"at commit ([0-9a-f]{40})" err)
                shown (when commit (clojure.java.shell/sh "git" "-C" tree "show" (str commit ":" config/shipped-file)))]
            (is (some? commit) err)
            (is (= 0 (:exit shown)) (pr-str shown))
            (is (and (some? (:out shown)) (not (str/includes? (:out shown) "seon.config.nope/key"))) "the file as that commit holds it is free of the bad key")))
        (is (= 7 (at-root reads-the-key)) "the next process reads the merged key from main")
        (is (= 7 (in (:agent (ask {:op :attach :task "cfgt/three"})) reads-the-key)) "and so does an agent forked from it"))
      (finally
        (when @root (ts/stop-root! @root))))))

(deftest import-then-write-back-is-a-zero-diff
  (let [files (src-files)
        db (imported files)
        out (source/files db (source/source-files db))
        orig (scratch-dir "zero-orig")
        copy (scratch-dir "zero")]
    (is (= (set (keys files)) (set (keys out))) "every file written back")
    (doseq [[path text] files] (is (= text (get out path)) path))
    (testing "through the :file effect's performer, onto a copy: diff -r finds nothing"
      (doseq [[path text] files]
        (.mkdirs (.getParentFile (java.io.File. (str orig "/" path))))
        (spit (str orig "/" path) text))
      (doseq [[path text] out]
        (.mkdirs (.getParentFile (java.io.File. (str copy "/" path))))
        (is (= {:value {:bytes (.length (java.io.File. (str orig "/" path))) :path (str copy "/" path)}}
               (fx/write-file {:req {:path (str copy "/" path) :text text}}))))
      (let [r (sh/sh "diff" "-r" (str orig "/src") (str copy "/src"))]
        (is (= [0 ""] [(:exit r) (:out r)]))))))

;; docs/source.md#the-binary-records-its-program: the build's record was the whole tree indexed into an empty db at every
;; build (1.23 G ticks and 5.4 GB of analysis, 2.34 G and 4.9 GB of checked transaction, 10.7 s at load 2); it is now the
;; seed's own incremental index on a store the build keeps, so a build pays for what its tree changed. Its digest is the
;; one a fresh index of the same tree holds. A scratch repository of two files, two stores of the test's own, removed
(deftest the-code-that-indexes-is-keyed-by-its-reach
  ;; docs/source.md#the-binary-records-its-program: a kept index is a cache of what the indexing code made of the files,
  ;; so its key holds that code (seon.root/index-code: the reach digest of its roots), and a change the code does not reach
  ;; keeps it. On the world: roots app.ix/make, which calls app.ix/rule; app.other is reached by nothing
  (let [db (my.test/with (my.test/world)
                         (my.test/code 'app.ix "(ns app.ix)\n\n(defn rule [x] (str x))\n\n(defn make [x] (rule x))\n")
                         (my.test/code 'app.other "(ns app.other)\n\n(defn other [] 1)\n"))
        in {:env "e" :tree "t" :code "c" :config {}}
        code #(root/index-code % in '[app.ix/make])
        k (code db)]
    (is (string? k))
    (is (= k (code (my.test/with db (my.test/code 'app.other "(ns app.other)\n\n(defn other [] 2)\n")))) "a change it does not reach keeps it")
    (is (= k (root/index-code db (assoc in :tree "t2" :code "c2") '[app.ix/make])) "the tree and the source it reads are the files', not its key")
    (is (not= k (code (my.test/with db (my.test/code 'app.ix "(ns app.ix)\n\n(defn rule [x] (str x :v2))\n\n(defn make [x] (rule x))\n")))) "a change to a form it reaches moves it")
    (is (not= k (root/index-code db (assoc in :env "e2") '[app.ix/make])) "and so does the runtime it runs on")
    (is (nil? (root/index-code db in '[app.none/x])) "roots the program does not define: no key, made again")))

(deftest a-builds-kept-index-holds-the-program-a-fresh-index-holds
  ;; and it is made again from nothing when a change reaches the code that indexes (seon.root/index-code; here
  ;; the roots app.bp/make, which calls app.bp/rule), and only then
  (let [dir (scratch-dir "built")
        tree (str dir "/tree")
        git (fn [& args] (apply sh/sh "git" "-C" tree "-c" "user.name=t" "-c" "user.email=t@t" args))
        put! (fn [p t] (.mkdirs (.getParentFile (java.io.File. (str tree "/" p)))) (spit (str tree "/" p) t))
        commit! #(do (git "add" "-A") (git "commit" "-q" "-m" "t"))
        roots '[app.bp/make]
        kept (str dir "/kept") fresh (str dir "/fresh")
        index #(:program (root/built-program tree % roots))
        ;; a datom of the test's own in the kept index: a store indexed again from nothing holds none
        at-kept (fn [f] (let [p (str (first (filter (fn [d] (.startsWith (.getName d) "kept-")) (.listFiles (java.io.File. ^String dir)))) "/index")
                              {:keys [cfg conn]} (pdb/open! :lmdb p 0 :db)]
                          (try (f conn) (finally (pdb/sync! cfg) (d/release conn)))))
        mark! #(at-kept (fn [c] (d/transact c {:tx-data [{:note/text "kept" :note/agent :kept-index}]})))
        marked? #(at-kept (fn [c] (boolean (some #(= "kept" (:v %)) (d/datoms @c :aevt :note/text)))))]
    (try
      (sh/sh "git" "init" "-q" tree)
      ;; an index another schema or layout made (seon.db/schema-digest names it) is dropped, never opened
      (.mkdirs (java.io.File. (str dir "/kept-00000000/index")))
      (put! "src/app/bp.clj" "(ns app.bp)\n\n(defn rule [x] (str x))\n\n(defn make [x] (rule x))\n")
      (put! "src/app/other.clj" "(ns app.other)\n\n(defn other [] 1)\n")
      (commit!)
      (let [a (index kept)
            _ (mark!)
            _ (is (marked?) "the mark is there")
            _ (is (not (.exists (java.io.File. (str dir "/kept-00000000")))) "the index of another schema or layout was dropped")]
        (put! "src/app/other.clj" "(ns app.other)\n\n(defn other [] 2)\n")
        (put! "src/app/bq.clj" "(ns app.bq)\n\n(defn two [] 2)\n")
        (commit!)
        (let [b (index kept)]
          (is (and (string? a) (not= a b)) "the kept index moved with the tree")
          (is (marked?) "in the same store: the change reaches none of the code that indexes")
          (is (= b (index fresh)) "and holds the program a fresh index of the same tree holds"))
        (put! "src/app/bp.clj" "(ns app.bp)\n\n(defn rule [x] (str x :v2))\n\n(defn make [x] (rule x))\n")
        (commit!)
        (let [c (index kept)]
          (is (not (marked?)) "a change to a form the indexing code reaches makes the index again from nothing")
          (is (= c (index (str dir "/fresh2"))) "and it holds what a fresh index holds")))
      (finally (sh/sh "rm" "-rf" dir)))))

(deftest a-landing-moves-roots-source-fact-to-the-landed-tree
  ;; docs/source.md#a-landing-enters-main-as-data: what the bridge writes (seon.root/landed-tx) moves root's source fact to
  ;; the landed tree, so the next re-index reads what differs from it, not every file landed since the last import
  (let [db0 (pdb/empty-db false)
        held {:root "/r" :dirs ["src"] :tree "t0" :commit "c0" :digest "d0" :files 1 :forms 0 :ms 1.0}
        db (:db-after (pdb/with db0 [{:seon.root/id :root :seon.root/source held}]))
        db1 (:db-after (pdb/with db (root/landed-tx db {"src/app/landed.clj" "(ns app.landed)\n(defn f [] 1)\n"} {} "t1" "c1")))
        fact (:seon.root/source (d/pull db1 [:seon.root/source] [:seon.root/id :root]))]
    (is (= ["t1" "c1"] [(:tree fact) (:commit fact)]) "the landed tree and commit")
    (is (not= "d0" (:digest fact)) "its digest is the landed tree's")
    (is (= ["/r" ["src"]] [(:root fact) (:dirs fact)]) "what the import read from stays")
    (is (some? (d/entity db1 [:var/name 'app.landed/f])) "and the landed file's facts are main's")))

(deftest a-landing-through-the-bridge-is-a-zero-diff
  ;; task seon.root/a-boot-resumes-from-the-db: a Claude session's landing enters main as data, the changed files' facts
  ;; (seon.root/changed-tx, what bin/land's bridge has root write on main).
  ;; Main indexed so from tree A, then from what A -> B changed (a form changed, a namespace added, a script's text and
  ;; mode, a page changed and one removed, a task file added), holds B: the program a seed of B holds (its program
  ;; digest, so a binary built from B is no stale binary), written back byte for byte (seon.doc/files), so the main
  ;; tree's git status stays clean after the write-back merges, with B's pages and tasks. In memory, no root
  (let [a (assoc (src-files)
                 "bin/zz-land" "#!/bin/sh\necho a\n"
                 "docs/zz-land.md" "# Land\n\nA page.\n\n## Kept\n\nIt stays.\n"
                 "docs/zz-gone.md" "# Gone\n\nA page the landing removes.\n")
        b (-> a
              (assoc "src/app/imp.clj" (str/replace imp-text "(* s s)" "(* s s s)")
                     "src/app/land.clj" "(ns app.land)\n\n(defn landed [] 1)\n"
                     "bin/zz-land" "#!/bin/sh\necho b\n"
                     "docs/zz-land.md" "# Land\n\nA page, changed.\n\n## Kept\n\nIt stays.\n"
                     "resources/seon/tasks/zz-land.edn" (pr-str [{:seon.task/name "t" :seon.task/plan "Landed." :seon.task/kind :work}]))
              (dissoc "docs/zz-gone.md"))
        ;; as bin/land's bridge passes them: every changed text file's mode, a namespace's and a page's too
        modes (assoc (zipmap (keys b) (repeat "100644")) "bin/zz-land" "100755")
        changed (into {} (for [p (into (set (keys a)) (keys b)) :when (not= (a p) (b p))] [p (b p)]))
        _ (doseq [[path text] a :when (.exists (java.io.File. (str here "/" path)))] (require (source/file-ns text)))
        db0 (pdb/empty-db false)
        ;; through the write check every write takes (seon.db/with), as the agent's transact! does
        main-a (:db-after (pdb/with db0 (root/changed-tx db0 a modes "resources/seon/tasks")))
        main-b (:db-after (pdb/with main-a (root/changed-tx main-a changed modes "resources/seon/tasks")))
        program-paths (filter #(or (str/starts-with? % "src/") (str/starts-with? % "bin/")) (keys b))
        seeded-b (let [db (pdb/empty-db false)] (d/db-with db (source/tx-of db (select-keys b program-paths) false)))]
    (is (= (set (keys changed)) #{"src/app/imp.clj" "src/app/land.clj" "bin/zz-land" "docs/zz-land.md" "docs/zz-gone.md" "resources/seon/tasks/zz-land.edn"}))
    (is (= (select-keys b (conj program-paths "docs/zz-land.md")) (seon.doc/files main-b (conj (vec program-paths) "docs/zz-land.md")))
        "every program file and page written back as B holds it, byte for byte")
    (is (= "100755" (:file/mode (d/entity main-b [:file/path "bin/zz-land"]))) "the script's mode")
    (is (nil? (d/entity main-b [:seon.doc/address "docs/zz-gone.md#gone"])) "the removed page's sections are gone")
    (is (some? (d/entity main-b [:seon.task/id "zz-land/t"])) "the added task file's task is main's")
    (is (= (program/program-digest seeded-b) (program/program-digest main-b))
        "the program main holds is the one a seed of B holds: one program digest")))

(def ^:private pt-text
  "(ns app.pt-src\n  \"A fixture file.\")\n\n;; adds\n(defn add [a b] (+ a b))\n\n(defn twice\n  [x]\n  (add x x))\n\n(def limit 3)\n")

(defn- main-with-file []
  (let [db (empty-db)] (d/db-with db (source/import-tx db {"src/app/pt_src.clj" pt-text}))))

(defn- merged
  "main after a branch from it records forms (as the agent's REPL does) and merges."
  [main & forms]
  (let [branch (apply pt/record main forms)
        r (pm/three-way (pm/program main #{}) (pm/program branch #{}) (pm/program main #{}))]
    [(d/db-with main (pm/merge-tx main main branch (keys (:ours r)) #{})) (:ours r)]))

(deftest a-change-to-a-namespace-with-no-file-is-named-never-dropped
  ;; changed-files leaves a form out when its namespace has no :ns/file; unfiled-forms names it,
  ;; so a merge of source with nowhere to write it records that (seon.reconcile :octopus-merged)
  (let [main (main-with-file)
        [main2 ours] (merged main 'app.pt-src "(defn twice [x] (* 2 x))")
        [main3 ours3] (merged main 'app.no-file "(defn probe [] 1)")]
    (is (= {} (source/unfiled-forms main2 ours)) "a filed namespace's change has its file")
    (is (= [] (source/changed-files main3 ours3)))
    (is (= {'app.no-file [[:var 'app.no-file/probe]]} (source/unfiled-forms main3 ours3)))))

(deftest a-merged-change-rewrites-only-its-form
  (let [main (main-with-file)]
    (is (= {"src/app/pt_src.clj" pt-text} (source/files main ["src/app/pt_src.clj"])))
    (let [[main2 ours] (merged main 'app.pt-src "(defn twice [x] (* 2 x))")]
      (is (= ["src/app/pt_src.clj"] (source/changed-files main2 ours)))
      (is (= {"src/app/pt_src.clj" (str/replace pt-text "(defn twice\n  [x]\n  (add x x))" "(defn twice [x] (* 2 x))")}
             (source/files main2 (source/changed-files main2 ours)))
          "the redefined form in its place, its lead kept; every other byte as it was"))
    (testing "a new form goes after the last, a blank line before it"
      (let [[main2 ours] (merged main 'app.pt-src "(defn thrice [x] (* 3 x))")]
        (is (= (str pt-text "\n(defn thrice [x] (* 3 x))\n")
               (get (source/files main2 (source/changed-files main2 ours)) "src/app/pt_src.clj")))))))

(deftest a-moved-tree-replaces-mains-program
  ;; task seon.root/main-follows-its-source-tree: main imported the file, then merged a new
  ;; form; the tree moves on (one function changed, one removed, the merged form in it)
  (let [[main _] (merged (main-with-file) 'app.pt-src "(defn thrice [x] (* 3 x))")
        b (-> (source/file-text main 'app.pt-src)
              (str/replace "(defn twice\n  [x]\n  (add x x))" "(defn twice [x] (* 2 x))")
              (str/replace "\n\n(def limit 3)" ""))
        tx (source/replace-tx main {"src/app/pt_src.clj" b})
        main2 (d/db-with main tx)
        form (fn [db src] (d/q '[:find (pull ?f [:form/source]) . :in $ ?s :where [?f :form/src ?s]] db src))
        others (fn [db] (remove #(= 'app.pt-src (:ns %)) (program/live-forms db)))]
    (is (= b (source/file-text main2 'app.pt-src)) "main's file is the tree's, byte for byte")
    (is (empty? (d/datoms main2 :avet :var/name 'app.pt-src/limit)) "the removed function's var is gone")
    (is (nil? (form main2 "(defn twice\n  [x]\n  (add x x))")) "the changed function's old form is gone")
    (is (= {:form/source true} (form main2 "(defn twice [x] (* 2 x))")) "its new form is the binary's")
    (is (nil? (:form/source (form main2 "(defn thrice [x] (* 3 x))"))) "the merged form keeps no :form/source")
    (is (= (others main) (others main2)) "the program no file holds is untouched")
    (is (= (d/q '[:find ?n ?p :where [?t :task/name ?n] [?t :task/plan ?p]] main)
           (d/q '[:find ?n ?p :where [?t :task/name ?n] [?t :task/plan ?p]] main2)) "data is untouched")
    (is (= 3 (count (filter #(= :db/retractEntity (first %)) (filter vector? tx)))) "retracted: twice's old form, limit's form and var")
    (is (empty? (filter vector? (source/replace-tx main2 {"src/app/pt_src.clj" b}))) "the same tree retracts nothing")
    (let [db (empty-db)]
      (is (= (source/file-text (d/db-with db (source/import-tx db {"src/app/pt_src.clj" pt-text})) 'app.pt-src)
             (source/file-text (d/db-with db (source/replace-tx db {"src/app/pt_src.clj" pt-text})) 'app.pt-src))
          "on a db holding no import it is the import"))))

(deftest an-agent-runs-its-branchs-change-to-imported-source
  (let [main (main-with-file)
        branch (pt/record main 'app.pt-src "(defn twice [x] (* 2 x))")]
    ;; the process holds the imported namespace, as a binary built from that source does:
    ;; every var it defines bound
    (hold! 'app.pt-src pt-text)
    (let [add0 @(resolve 'app.pt-src/add)]
      (is (= {:loaded (count (program/live-forms (empty-db))) :errors []} (program/load-all! main))
          "main's imported text whose namespace this process holds is the binary's: never evaluated (the example program is)")
      (is (= [] (program/load-diff! main branch)))
      (is (= 6 ((resolve 'app.pt-src/twice) 3)) "the branch's redefinition runs in the agent")
      (is (identical? add0 @(resolve 'app.pt-src/add)) "the imported forms around it are not evaluated again"))))

(deftest a-form-that-fails-at-load-is-named
  ;; root's ruling, 2026-10-27: a definition that fails at load fails the load loudly, naming the
  ;; form; it said only "did not load in seon.task-test: nil where a ... Future is required"
  (let [main (main-with-file)
        b0 (pt/record main 'app.pt-src "(def boom 1)")
        ;; its text as a load would meet it: recorded, it ran (pt/record evaluates as the agent does)
        branch (d/db-with b0 [{:db/id (:db/id (:var/form (d/entity b0 [:var/name 'app.pt-src/boom])))
                               :form/src "(def boom (throw (ex-info \"boom at load\" {})))"}])
        [e] (program/load-diff! main branch)]
    (is (= 'app.pt-src (:ns e)) (pr-str e))
    (is (string? (:form e)) "the form, by its id")
    (is (= "(def boom (throw (ex-info \"boom at load\" {})))" (:lead e)) "and its text's first line")
    (is (clojure.string/includes? (str (:message e)) "boom at load"))))

(deftest an-imported-namespace-the-process-does-not-hold-is-loaded
  ;; test/ is imported but not built into the
  ;; binary; its forms load where main's program runs (root, the fork server, so every agent)
  (let [main (main-with-file)]
    (remove-ns 'app.pt-src)
    (is (= {:loaded (+ 4 (count (program/live-forms (empty-db)))) :errors []} (program/load-all! main))
        "the imported namespace's four forms load beside the example program")
    (is (= 6 ((resolve 'app.pt-src/twice) 3)) "and run")))

(defn- git [dir & args]
  (let [r (apply sh/sh "git" "-C" dir args)]
    (is (zero? (:exit r)) (str args " " (:err r)))
    (str/trim (:out r))))

(deftest a-script-merged-is-written-back-with-its-mode
  ;; docs/source.md#a-program-file: read from the tree with its mode, edited on a branch, merged, committed: the tree's text is main's and the execute bit is kept (the roots-log agent's spit took it off)
  (let [tree (scratch-dir "script")
        p (str tree "/bin/x")
        text "#!/bin/sh\n# bin/x says hello\necho 1\n"
        _ (do (git tree "init" "-q" "-b" "main")
              (.mkdirs (java.io.File. (str tree "/bin")))
              (spit p text) (.setExecutable (java.io.File. ^String p) true)
              (git tree "add" "bin") (git tree "-c" "user.name=test" "-c" "user.email=test@localhost" "commit" "-q" "-m" "base"))
        r (fx/read-source {:req {:root tree :dirs ["bin"]}})
        _ (is (= {"bin/x" text} (:value r)) "a file on the allow-list is read whatever its name")
        main (let [db (empty-db) db (d/db-with db (source/import-tx db (:value r)))] (d/db-with db (source/modes-tx db (:modes r))))
        _ (is (= [] (source/modes-tx main (:modes r))) "read again, nothing to write")
        edited "#!/bin/sh\n# bin/x says hello\necho 2\n"
        ours (d/db-with main (source/file-tx "bin/x" edited))
        ch (:ours (seon.merge/delta-three-way main ours main #{}))
        merged (d/db-with main (seon.merge/merge-tx main main ours (keys ch) #{}))
        paths (source/changed-files merged ch)
        c (fx/git-commit {:req {:dir tree :branch "jolt/seon-merges" :files (source/files merged paths) :modes (source/modes merged paths) :message "m"}})]
    (is (some? (get-in c [:value :sha])) (pr-str c))
    (is (= (str/trim edited) (git tree "show" "jolt/seon-merges:bin/x")) "the tree's text is main's")
    (is (str/starts-with? (git tree "ls-tree" "jolt/seon-merges" "bin/x") "100755 ") "the execute bit is kept")))

(deftest a-script-says-what-its-for-and-how-to-call-it
  ;; docs/source.md#a-program-file: what its lead comment says is held beside its text, and its render draws it
  (let [db (let [db (empty-db)] (d/db-with db (source/import-tx db {"bin/x" "#!/bin/sh\n# bin/x NAME says hello to NAME\n# and nothing else\necho 1\n"})))]
    (is (= [:section [:h3 "bin/x"] [:p "bin/x NAME says hello to NAME\nand nothing else"] [:pre "bin/x NAME says hello to NAME"]]
           (seon.render.ui/script {} (d/pull db [:file/path :seon.script/says :seon.script/usage] [:file/path "bin/x"]))))))

(deftest a-merge-is-committed-on-the-merges-branch
  (let [tree (scratch-dir "git")
        ;; the base commit is the test's own; the tree holds no identity of its own, as a
        ;; root's checkout has none under its HOME (target/home)
        _ (do (git tree "init" "-q" "-b" "main")
              (.mkdirs (java.io.File. (str tree "/src/app")))
              (spit (str tree "/src/app/pt_src.clj") pt-text)
              (git tree "add" "src") (git tree "-c" "user.name=test" "-c" "user.email=test@localhost" "commit" "-q" "-m" "base"))
        main-sha (git tree "rev-parse" "main")
        [main2 ours] (merged (main-with-file) 'app.pt-src "(defn twice [x] (* 2 x))")
        files (source/files main2 (source/changed-files main2 ours))
        msg (source/commit-message [{:task "app.pt/twice"}])
        r (fx/git-commit {:req {:dir tree :branch "jolt/seon-merges" :files files :message msg}})]
    (is (re-matches #"[0-9a-f]{40}" (str (get-in r [:value :sha]))) (pr-str r))
    (is (= (get-in r [:value :sha]) (git tree "rev-parse" "jolt/seon-merges")) "the merges branch, made at HEAD, holds the commit")
    (is (= main-sha (git tree "rev-parse" "jolt/seon-merges~1")) "its parent: the commit the tree checks out")
    (testing "the next merge's commit follows the last"
      (let [[main3 ours3] (merged main2 'app.pt-src "(def limit 4)")
            r2 (fx/git-commit {:req {:dir tree :branch "jolt/seon-merges" :files (source/files main3 (source/changed-files main3 ours3)) :message msg}})]
        (is (= (get-in r [:value :sha]) (git tree "rev-parse" "jolt/seon-merges~1")))
        (is (= (get-in r2 [:value :sha]) (git tree "rev-parse" "jolt/seon-merges")))
        (is (str/includes? (git tree "show" "jolt/seon-merges:src/app/pt_src.clj") "(def limit 4)"))))
    (is (= main-sha (git tree "rev-parse" "main")) "main never moves")
    (is (= (get files "src/app/pt_src.clj") (str (git tree "show" (str (get-in r [:value :sha]) ":src/app/pt_src.clj")) "\n")) "the merged text is what was committed")
    (is (= ["src/app/pt_src.clj"] (str/split-lines (git tree "diff" "--name-only" "main" (get-in r [:value :sha])))) "only the merged file")
    (is (str/includes? (git tree "log" "-1" "--format=%B" (get-in r [:value :sha])) "task app.pt/twice") "the message names the task")
    (is (= (str (config/value :seon.config.source/committer-name) " <" (config/value :seon.config.source/committer-email) "> "
                (config/value :seon.config.source/committer-name) " <" (config/value :seon.config.source/committer-email) ">")
           (git tree "log" "-1" "--format=%an <%ae> %cn <%ce>" (get-in r [:value :sha])))
        "Seon authors and commits its merges, by the config's identity, whatever HOME holds")
    (testing "a refused commit is the declared error, never a throw"
      (is (= :seon.fx/io-error (:seon.error/type (fx/git-commit {:req {:dir (str tree "/nope") :branch "jolt/seon-merges" :files files :message msg}}))))))
  (is (= {:value {:sha nil :files 0}} (fx/git-commit {:req {:dir "/nonexistent" :branch "b" :files {} :message "m"}}))
      "nothing to write commits nothing"))

(deftest a-file-whose-namespace-is-not-loaded-imports
  ;; task seon.source/the-tests-are-part-of-the-program: a root imports test/ whose
  ;; namespaces its binary never loaded (proofs live there), so the analyzer reads a
  ;; form's aliases from the file's own ns form, not from a loaded namespace
  (let [files {"test/x/unloaded_test.clj"
               "(ns x.unloaded-test (:require [clojure.test :refer [deftest is]] [datahike.api :as d] [x.z-support :as z]))\n\n(defn q [db] (d/q '[:find ?e :where [?e :a]] db))\n\n(deftest q-test (is (= #{} (q nil))) (is (= ::z/k (z/k))))\n"
               ;; required by the file before it in path order, and never loaded
               "test/x/z_support.clj" "(ns x.z-support)\n\n(defn k [] ::k)\n"}
        db (empty-db)
        tx (source/import-tx db files)
        db (d/db-with db tx)]
    (is (= 3 (count (d/q '[:find ?f :where [?n :ns/name x.unloaded-test] [?f :form/ns ?n] [?f :form/source true]] db)))
        "the ns form and both definitions (the test store's seed holds forms of its own)")
    (is (contains? (set (d/q '[:find [?r ...] :where [?v :var/name x.unloaded-test/q] [?v :var/form ?f] [?f :form/refs ?r]] db))
                   'datahike.api/q)
        "an alias resolved through the file's own ns form")
    (is (contains? (set (d/q '[:find [?r ...] :where [?v :var/name x.unloaded-test/q-test] [?v :var/form ?f] [?f :form/refs ?r]] db))
                   'x.z-support/k)
        "a namespace required from a later file is analyzed first, and its alias reads ::z/k")))

(deftest a-changed-ns-form-applies-its-requires-before-its-forms
  ;; a restarted root
  ;; loads main's program first (seon.program/load-all!), which runs a test namespace's old
  ;; ns form and so marks it loaded; the import of its changed file must still apply the
  ;; new ns form (a new require and alias) before analyzing the forms that use it. Seen
  ;; 2026-10-22: the owner's root died on `No such namespace: ts` once test files required
  ;; seon.test-store :as ts.
  ;; main's program as load-all! runs it: the namespace's old ns form, then another test
  ;; namespace's that requires it, which marks it loaded
  (binding [*ns* (the-ns 'user)] (eval '(ns x.reloaded-test)) (eval '(ns x.reloaded-user (:require [x.reloaded-test]))))
  (is (contains? (loaded-libs) 'x.reloaded-test) "the setup: main's program marked it loaded")
  (let [files {"test/x/reloaded_test.clj"
               "(ns x.reloaded-test (:require [x.reloaded-support :as rs]))\n\n(defn g [] (rs/k))\n"
               "test/x/reloaded_support.clj" "(ns x.reloaded-support)\n\n(defn k [] 1)\n"}
        db (d/db-with (empty-db) (source/import-tx (empty-db) files))]
    (is (contains? (set (d/q '[:find [?r ...] :where [?v :var/name x.reloaded-test/g] [?v :var/form ?f] [?f :form/refs ?r]] db))
                   'x.reloaded-support/k)
        "the alias the changed ns form adds resolves in the form after it")))

(deftest a-schema-file-round-trips-through-the-program
  ;; task seon.source/schemas-are-part-of-the-program: a schema resource is program data
  ;; like a source file (v1: the program is entities: functions, tests, schemas), so an
  ;; agent can change it on its branch and the merge writes it back; imported then written
  ;; back unchanged, it is the same text
  (let [path "resources/seon/schemas/seon.task.edn"
        text (slurp (str here "/" path))
        db (empty-db)
        db (d/db-with db (source/import-tx db {path text}))
        out (source/files db (source/source-files db))]
    (is (= text (get out path)) "the schema file, byte for byte")))

(def ^:private facts-path "resources/seon/schemas/seon.facts.edn")

(deftest a-changed-schema-entry-rewrites-only-itself
  ;; task seon.source/schemas-are-part-of-the-program: write-back rewrites only the changed
  ;; entries of a schema resource (v1's schema-file-text over rewrite-clj), every other byte
  ;; as it was: a replaced value in its old layout, a removed entry with its line, a new key
  ;; after the last entry, separated as the file's entries are
  (let [text (slurp (str here "/" facts-path))
        db (empty-db)
        db (d/db-with db (source/import-tx db {facts-path text}))
        ;; the entry's line as the file has it (a property added to the entry moves with it), and
        ;; its form with :paused added, as an edit would write it
        status-line (some #(when (str/starts-with? % " :seon.task/reason ") %) (str/split-lines text))
        paused (str/trim (subs (str/replace status-line ":string" "[:string {:min 1}]") (count " :seon.task/reason ")))
        edit (fn [tx] (get (source/files (d/db-with db tx) [facts-path]) facts-path))]
    (is (str/ends-with? status-line " :string") "the entry's line is its plain value, as the edit expects")
    (testing "every schema resource imported and written back unchanged is the same text"
      (let [dir (java.io.File. (str here "/resources/seon/schemas"))
            files (into {} (for [f (.listFiles dir) :when (str/ends-with? (.getName f) ".edn")]
                             [(str "resources/seon/schemas/" (.getName f)) (slurp f)]))
            db (empty-db)
            db (d/db-with db (source/import-tx db files))]
        (is (seq files) "the schemas directory holds resources")
        (is (= files (source/files db (source/source-files db))))))
    (testing "a replaced value: only its changed part"
      (is (= (str/replace text status-line (str/replace status-line ":string" "[:string {:min 1}]"))
             (edit [{:schema/key :seon.task/reason
                     :schema/form paused}]))))
    (testing "a removed entry takes its line"
      (is (= (str/replace text "\n :seon.task/merged-t :int" "")
             (edit [[:db/retractEntity [:schema/key :seon.task/merged-t]]]))))
    (testing "a new key after the last entry, read back as written"
      (let [out (edit [{:schema/key :seon.task/invalid :schema/form "[:map [:seon.error/message :string]]" :schema/file facts-path}])]
        (is (= (str (subs text 0 (- (count text) 2)) "\n :seon.task/invalid [:map [:seon.error/message :string]]}\n") out))
        (is (= (assoc (edn/read-string text) :seon.task/invalid [:map [:seon.error/message :string]])
               (edn/read-string out)))))))

;; ---- a merge loads in place (BUILD plan P4) ---------------------------------------------

(def ^:private mm-text
  "(ns app.mm-src)\n\n(defmacro twice [x] `(* 2 ~x))\n\n(defn f [y] (twice y))\n\n(defprotocol Sized (size [x]))\n\n(defrecord Box [n] Sized (size [_] n))\n\n(defn g [n] (size (->Box n)))\n\n(defn box? [x] (instance? Box x))\n")

(defn- hold!
  "This process holding namespace nsym's text compiled, as a binary built from it does."
  [nsym text]
  (remove-ns nsym)
  (let [db (empty-db)]
    (is (= [] (:errors (program/load-all! (d/db-with db (source/text-tx db nsym text nil))))))))

(defn- held-main
  "main with text imported from path as the binary's own source, held by this process."
  [nsym text path]
  (hold! nsym text)
  (let [db (empty-db)] (d/db-with db (source/import-tx db {path text}))))

(defn- merge-loaded
  "main after a branch from it records forms (evaluated here, as the agent's REPL does, then
  this process holds the text again: the agent was another process) and merges, and the
  errors of loading that merge here (seon.program/load-changes!, as root and the fork server
  do)."
  [main nsym text & forms]
  (let [branch (apply pt/record main forms)
        _ (hold! nsym text)
        r (pm/three-way (pm/program main #{}) (pm/program branch #{}) (pm/program main #{}))
        rep (d/with main (pm/merge-tx main main branch (keys (:ours r)) #{}))]
    [(:db-after rep) (program/load-changes! (:db-after rep) (pdb/report->tx rep))]))

(deftest a-merge-to-imported-source-loads-in-place
  (let [main (held-main 'app.pt-src pt-text "src/app/pt_src.clj")
        [_ errs] (merge-loaded main 'app.pt-src pt-text 'app.pt-src "(defn add [a b] (+ a b 100))")]
    (is (= [] errs))
    (is (= 106 ((resolve 'app.pt-src/twice) 3)) "the binary's compiled caller reaches the merged definition")))

(deftest a-changed-macro-reloads-the-forms-it-expanded-in
  (let [main (held-main 'app.mm-src mm-text "src/app/mm_src.clj")
        f (fn [] ((resolve 'app.mm-src/f) 2))]
    (is (= 4 (f)))
    (let [[_ errs] (merge-loaded main 'app.mm-src mm-text 'app.mm-src "(defmacro twice [x] `(* 3 ~x))")]
      (is (= [] errs))
      (is (= 6 (f)) "f, compiled against the old expansion and held by the binary, is reloaded"))))

(deftest a-merged-protocol-keeps-its-records
  (let [main (held-main 'app.mm-src mm-text "src/app/mm_src.clj")
        [_ errs] (merge-loaded main 'app.mm-src mm-text 'app.mm-src "(defprotocol Sized \"How big a thing is.\" (size [x]))")]
    (is (= [] errs))
    (is (= 5 ((resolve 'app.mm-src/g) 5)) "Box dispatches through the redefined protocol (Jolt needs no reload of its record)")))

(deftest a-merged-record-is-the-one-its-callers-see
  (let [main (held-main 'app.mm-src mm-text "src/app/mm_src.clj")
        [_ errs] (merge-loaded main 'app.mm-src mm-text 'app.mm-src "(defrecord Box [n] Sized (size [_] (inc n)))")]
    (is (= [] errs))
    (is (= 6 ((resolve 'app.mm-src/g) 5)) "the new record")
    (is (true? ((resolve 'app.mm-src/box?) ((resolve 'app.mm-src/->Box) 1))) "an instance? test held by the binary sees the redefined record")))

(deftest a-form-imported-into-a-loaded-namespace-is-loaded-not-unmapped
  ;; the step E dry run (2026-10-22): a root restarted on a tree whose test namespace gained a
  ;; deftest imported it onto main, but held? read the namespace's existence as "the binary
  ;; holds it", so neither root nor the fork server loaded it; the next agent's stage read
  ;; the missing var as an unmap and the merge deleted the deftest from main
  (let [nsym (symbol (str "app.imported-" (random-uuid)))
        path (str "test/app/" (str/replace (name nsym) "-" "_") ".clj")
        a-text (str "(ns " nsym ")\n\n(defn a [] 1)\n")
        db (d/db-with (empty-db) (source/import-tx (empty-db) {path a-text}))
        _ (program/load-all! db)
        rep (d/with db (source/replace-tx db {path (str a-text "\n(defn b [] 2)\n")}))
        errs (program/load-changes! (:db-after rep) (pdb/report->tx rep))]
    (is (= [] errs))
    (is (= 2 (some-> (resolve (symbol (str nsym) "b")) deref (apply []))) "the imported form is loaded where main's program runs")
    (is (= [] (stage/stage-tx (:db-after rep))) "so staging this runtime unmaps nothing")))

;; docs/source.md#a-process-holds-the-tests-only-when-it-runs-them: agents forked before an import ran the previous main's tests against the new program,
;; red with nothing to see: their records said the new text, their vars the old
;; docs/source.md#indexing-a-tree: a changed test file in a root that loaded no test namespace: its require looked on disk, the import failed whole and main stayed old
(deftest a-refresh-reads-what-a-changed-file-requires-and-this-process-has-not-loaded
  ;; a macro from a namespace this process hasn't loaded expands as it should, because db's text set the namespace up first: without it the form's references lose what the macro expands to, and a test's reach with them.
  ;; An import loads what it reads, so this process forgets them (a root has loaded no test namespace)
  (let [names '[app.rq-dep app.rq-mid app.rq-top]
        mid "(ns app.rq-mid (:require [app.rq-dep :as dep]))\n\n(defmacro mm [] `(dep/d 1))\n"
        top (fn [n] (str "(ns app.rq-top (:require [app.rq-mid :as mid]))\n\n(defn t [] (+ " n " (mid/mm)))\n"))
        db (d/db-with (empty-db) (source/import-tx (empty-db) {"test/app/rq_dep.clj" "(ns app.rq-dep)\n\n(defn d [x] x)\n" "test/app/rq_mid.clj" mid "test/app/rq_top.clj" (top 1)}))
        changed {"test/app/rq_top.clj" (top 2)}]
    (is (= changed (source/required-files db changed '#{app.rq-mid})) "what's loaded is left")
    (run! remove-ns names)
    (dosync (alter @#'clojure.core/*loaded-libs* #(reduce disj % names)))
    (let [after (d/db-with db (source/refresh-tx db changed))]
      (is (some #{'app.rq-dep/d} (d/q '[:find [?r ...] :where [?v :var/name app.rq-top/t] [?v :var/form ?f] [?f :form/refs ?r]] after)) "the macro expanded as what it is")
      (is (= (top 2) (source/file-text after 'app.rq-top)) "and the change lands")
      (is (= mid (source/file-text after 'app.rq-mid)) "what was only read changes nothing"))))

(deftest a-form-an-import-replaces-runs-its-new-text
  (let [nsym (symbol (str "app.replaced-" (random-uuid)))
        path (str "test/app/" (str/replace (name nsym) "-" "_") ".clj")
        text (fn [n] (str "(ns " nsym ")\n\n(defn a [] " n ")\n"))
        db (d/db-with (empty-db) (source/import-tx (empty-db) {path (text 1)}))
        _ (program/load-all! db)
        run #(some-> (resolve (symbol (str nsym) "a")) deref (apply []))
        _ (is (= 1 (run)))
        rep (d/with db (source/replace-tx db {path (text 2)}))]
    (is (= [] (program/load-changes! (:db-after rep) (pdb/report->tx rep))))
    (is (= 2 (run)) "the process runs what main now holds")))

(deftest the-source-read-carries-each-reference-code-pin
  ;; land6 review, must-fix 4: a cited reference-code file shows the revision main's source
  ;; pins it at; the :source effect reads each gitlink once, at import, so no render runs git
  (let [r (fx/read-source {:req {:root here :dirs ["src"]}})
        head-tree (str/trim (:out (sh/sh "git" "-C" here "rev-parse" "HEAD:reference-code/malli")))]
    (is (= head-tree (get (:pins r) "reference-code/malli")) "the gitlink HEAD's tree records for it")
    (is (every? #(str/starts-with? % "reference-code/") (keys (:pins r))))))

(deftest a-test-that-reaches-a-process-is-found-by-the-reach-walk
  ;; root's ruling (2026-10-22, owner 2026-10-23): a test that reaches a process (an agent, a
  ;; fork, a root of its own, the binary) never runs automatically, only when named; a shell call
  ;; is none (audit 2026-10-26, item 7: it left main-test's laws out of every plain prove). It is
  ;; found by the compiler's facts (the one reach walk, :seon.config.source/process-vars),
  ;; never a marker. Each process var, reached directly or through a helper, and a test that
  ;; reaches none. Step E's run 4: a change to seon.problems/report reached tests that boot a
  ;; root, and an agent's proof run spun for minutes in its fork
  (let [text (str "(ns app.proc-test (:require [clojure.java.shell :as sh] [clojure.test :refer [deftest is]] [app.proc :as p]"
                  " [seon.lib :as lib] [seon.test-store :as ts] [seon.agent-test :as at] [seon.program :as sp]))\n\n"
                  "(defn- shell-ok? [] (zero? (:exit (sh/sh \"true\"))))\n\n"
                  "(deftest via-shell (is (shell-ok?)) (is (= 2 (p/twice 1))))\n\n"
                  "(defn- forks? [] (lib/fork! (fn [] 0)))\n\n"
                  "(deftest via-fork (is (forks?)))\n\n"
                  "(deftest via-binary (is (ts/binary-sh \"./seon\" \"version\")))\n\n"
                  "(deftest via-root (is (at/in-agent [\"1\"])))\n\n"
                  ;; tree-readers names binary-sh and a shell as quoted data: a test that only reaches that list
                  ;; uses neither (the reach walk's own tests read it)
                  "(deftest via-names (is (seq sp/tree-readers)))\n\n"
                  "(deftest pure (is (= 2 (p/twice 1))))\n")
        db (d/db-with (empty-db) (source/import-tx (empty-db) {"src/app/proc.clj" "(ns app.proc)\n\n(defn twice [x] (* 2 x))\n"
                                                               "src/seon/program.clj" "(ns seon.program)\n\n(def tree-readers '#{seon.test-store/binary-sh clojure.java.shell/sh})\n"
                                                               "test/app/proc_test.clj" text}))
        idx (ts/reach-index db {:env "" :tree "" :config {}})
        mine (into {} (filter #(= "app.proc-test" (namespace (key %)))) idx)]
    (is (= {'app.proc-test/via-shell false 'app.proc-test/via-fork true 'app.proc-test/via-binary true
            'app.proc-test/via-root true 'app.proc-test/via-names false 'app.proc-test/pure false}
           (update-vals mine :process?))
        "each process var marks the test that reaches it, and only that: a shell is none")
    (is (= #{'app.proc-test/via-fork 'app.proc-test/via-binary 'app.proc-test/via-root}
           (set (filter #(= "app.proc-test" (namespace %)) (program/process-tests idx)))))
    ;; only seon.test-store/binary-sh runs target/seon (:seon.config.source/binary-vars): a shell
    ;; or a fork is a process, not the binary, so a run of only those builds nothing (via-root
    ;; reaches the binary through the real program's start-root!, which this program lacks)
    (is (= {'app.proc-test/via-shell false 'app.proc-test/via-fork false 'app.proc-test/via-binary true
            'app.proc-test/via-root false 'app.proc-test/via-names false 'app.proc-test/pure false}
           (update-vals mine :binary?)))
    (testing "why a test is a process test: the process var it uses, and the chain of calls to it (dogfood 2026-10-25: :deferred named the test and nothing else)"
      (let [g (program/reach-graph db nil)]
        (is (nil? (program/process-path g 'app.proc-test/via-shell)) "a shell, even through a helper, is no process")
        (is (= {:uses ['seon.lib/fork!] :through ['app.proc-test/via-fork 'app.proc-test/forks?]}
               (program/process-path g 'app.proc-test/via-fork)) "through its helper")
        (is (= {:uses ['seon.test-store/binary-sh] :through ['app.proc-test/via-binary]}
               (program/process-path g 'app.proc-test/via-binary)) "used by the test itself")
        (is (nil? (program/process-path g 'app.proc-test/pure)) "a test that reaches no process has none")))))

;; ---- a changed file re-imports only what reaches it (task seon.source/an-import-reads-only-changed-files)

(defn- program-of
  "db's live forms as the reach walk reads them: by id, their text, refs, requires and lead."
  [db] (into (sorted-map) (for [f (program/live-forms db)] [(:id f) (select-keys f [:src :refs :requires :ns :lead])])))

(deftest a-refresh-is-the-whole-trees-replace-at-the-cost-of-what-changed
  ;; root's ruling (2026-10-23): the test service's branch follows main by re-importing what a
  ;; change reaches, not the program: a namespace whose file changed, and every one requiring it
  (let [files {"src/rf/mac.clj" "(ns rf.mac)\n\n(defmacro twice [x] `(* 2 ~x))\n"
               "src/rf/use.clj" "(ns rf.use (:require [rf.mac :refer [twice]]))\n\n(defn f [x] (twice x))\n"
               "src/rf/other.clj" "(ns rf.other)\n\n(defn g [x] (inc x))\n"}
        db (empty-db)
        db (d/db-with db (source/import-tx db files))]
    (testing "a changed macro: its namespace and the one using it read again, as the whole tree's replace reads them"
      (let [mac "(ns rf.mac)\n\n(defmacro twice [x] `(+ ~x ~x))\n"]
        (is (= (program-of (d/db-with db (source/replace-tx db (assoc files "src/rf/mac.clj" mac))))
               (program-of (d/db-with db (source/refresh-tx db {"src/rf/mac.clj" mac})))))))
    (testing "a removed file: its namespace's program is retracted, the others kept"
      (let [after (d/db-with db (source/refresh-tx db {"src/rf/other.clj" nil}))]
        (is (nil? (seon.db/eid after :var/name 'rf.other/g)))
        (is (some? (seon.db/eid after :var/name 'rf.use/f)))
        (is (= (program-of (d/db-with db (source/replace-tx db (dissoc files "src/rf/other.clj"))))
               (program-of after)))))
    (testing "an unchanged tree: nothing to do"
      (is (= [] (source/refresh-tx db {}))))))

(defn- refresh-program
  "A program of n forms in other files (files of ten functions each, every one requiring rfc.c
  and calling it when hub? is true) beside rfc.c, a file of ten functions: db holding it, and
  rfc.c's text with one function's body edited."
  {:malli/schema [:=> [:cat pos-int? :boolean] [:tuple :seon.host/db [:map-of :string :string]]]}
  [n hub?]
  (let [fns (fn [body] (apply str (for [i (range 10)] (str "\n\n(defn f" i " [x] " (body i) ")"))))
        c (fn [body] (str "(ns rfc.c)" (fns body) "\n"))
        fs (into {"src/rfc/c.clj" (c #(str "(+ x " % ")"))}
                 (for [j (range (max 1 (quot n 10)))]
                   [(str "src/rfc/o" j ".clj")
                    (str "(ns rfc.o" j (when hub? " (:require [rfc.c :as c])") ")"
                         (fns #(if hub? (str "(c/f" % " x)") (str "(* x " % ")"))) "\n")]))
        db (empty-db)]
    [(d/db-with db (source/import-tx db fs)) {"src/rfc/c.clj" (c #(if (zero? %) "(- x 1)" (str "(+ x " % ")")))}]))

(defn refresh-of
  "refresh-tx's arguments: a program of n forms in other files, and a body edit in a file no
  namespace requires."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:map-of :string :string]]]}
  [n] (refresh-program n false))

(defn hub-refresh-of
  "refresh-tx's arguments: a program of n forms in other files that all require rfc.c, and a
  body edit in rfc.c."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:map-of :string :string]]]}
  [n] (refresh-program n true))

(defn ns-edit-of
  "refresh-tx's arguments: a program of n forms in other files that all require rfc.c, and an edit of
  rfc.c's ns form alone (a new require): what its dependents read of it is unchanged."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:map-of :string :string]]]}
  [n] (let [[db ch] (hub-refresh-of n)]
        [db {"src/rfc/c.clj" (str/replace (source/file-text db 'rfc.c) "(ns rfc.c)" "(ns rfc.c (:require [clojure.string]))")}]))

(defn read-of
  "seon.source/read-nss's arguments: refresh-of n's db, its edited file, and the tree read (the same file)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map :map]]}
  [n] (let [[db ch] (refresh-of n)] [db ch ch]))

(defn interface-of
  "seon.source/db-interface's arguments: hub-refresh-of n's db and its hub."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :symbol]]}
  [n] [(first (hub-refresh-of n)) 'rfc.c])

(defn diff-of
  "seon.source/program-tx's arguments: refresh-of n's db and its edited file, read."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map :map [:set :any]]]}
  [n] (let [[db ch] (refresh-of n)] [db (#'source/read-nss db ch ch) ch #{}]))

(defn transact-of
  "datahike.api/db-with's arguments: refresh-of n's db and its refresh's facts."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.db/tx-data]]}
  [n] (let [[db ch] (refresh-of n)] [db (source/refresh-tx db ch)]))

(defn- written
  "The namespaces whose forms tx writes (each form map's :form/ns tempid)."
  [tx] (set (keep #(when (and (map? %) (:form/src %)) (:form/ns %)) tx)))

(deftest a-body-edit-writes-only-its-form
  (let [[db ch] (refresh-of 10)
        tx (source/refresh-tx db ch)
        after (d/db-with db tx)
        tree (into {} (for [[p n] [["src/rfc/c.clj" 'rfc.c] ["src/rfc/o0.clj" 'rfc.o0]]] [p (source/file-text db n)]))]
    (is (= (program-of (d/db-with db (source/replace-tx db (merge tree ch)))) (program-of after))
        "the same program as the whole tree's replace")
    (is (= 1 (count (filter #(and (map? %) (:form/src %)) tx))) "one form written: the edited one")
    (is (= [] (source/refresh-tx after ch)) "the same text again: nothing")
    (let [[hdb hch] (hub-refresh-of 10)]
      (is (= #{"ns rfc.c"} (written (source/refresh-tx hdb hch))) "a hub's body edit reads no dependent"))
    (let [[hdb hch] (ns-edit-of 10)
          tree {"src/rfc/c.clj" (hch "src/rfc/c.clj") "src/rfc/o0.clj" (source/file-text hdb 'rfc.o0)}]
      (is (= (program-of (d/db-with hdb (source/replace-tx hdb tree))) (program-of (d/db-with hdb (source/refresh-tx hdb hch))))
          "a changed ns form: the same program as the whole tree's replace, its dependents not read again"))
    (let [[hdb hch] (hub-refresh-of 10)
          renamed {"src/rfc/c.clj" (str/replace (hch "src/rfc/c.clj") "(defn f9 " "(defn g9 ")}]
      (is (= #{"ns rfc.c" "ns rfc.o0"} (written (source/refresh-tx hdb (assoc renamed "src/rfc/o0.clj" (str/replace (source/file-text hdb 'rfc.o0) "(c/f9 x)" "(c/f8 x)")))))
          "a renamed function changes the interface: its dependents are read again"))))

(defn file-of
  "seon.source/file-text's arguments: a db whose namespace app.ft holds a declare and n
  functions, each calling the five before it (fewer near the first) and the declared one,
  and a helper recorded at a REPL after the first function, which now calls it (the
  write-back moves it before its user)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :symbol]]}
  [n]
  (let [call (fn [i] (str "(+ (g x)" (apply str (for [j (range (max 0 (- i 5)) i)] (str " (f" j " x)"))) ")"))
        text (apply str "(ns app.ft)\n\n(declare g)\n" (for [i (range n)] (str "\n(defn f" i " [x] " (call i) ")\n")))
        db0 (empty-db)
        db (d/db-with db0 (source/import-tx db0 {"src/app/ft.clj" text}))]
    [(pt/record db 'app.ft "(defn- h [x] x)" 'app.ft "(defn f0 [x] (h x))") 'app.ft]))

(def ^:private refresh-sizes
  "Forms in the other files: a fixed change costs the same at each: a program 10 times the smallest's
  shows it (at 10 100 1000 the proofs cost 2.3 G ticks; a normal run stops at 1000, seon.config.proofs/cost-max-n)."
  [3 9 30])

(def ^:private fixed-change-estimates
  "The estimates a fixed change is held to, one per phase and per kind of edit (the tests below run them in
  four groups: each proof measures its function tens of times at each size, so all seven in one test
  cost 2.3 G ticks, past the ceiling of work a test may do)."
  [{:of 'seon.source/read-nss :input 'seon.source-test/read-of
              :n "forms in the program's other files; the read and analysis of one 10-function file"
              :work "read: the file's text read form by form; analyze: each form's account (Jolt's analyzer, not run)"
              :growth :constant
              ;; measured 2026-10-23: 294k ticks, 2.44 MB, 391 calls at every size (1.3 ms CPU)
              :fixed {:ticks 300000 :bytes 2500000 :calls 391}
              :sizes refresh-sizes}
             {:of 'seon.source/db-interface :input 'seon.source-test/interface-of
              :n "forms in the program's other files, every file requiring the hub"
              :work "the gate on dependents: the hub's forms (avet :form/ns) and their definitions read once"
              :growth :constant
              ;; measured 2026-10-23: 346k -> 351k ticks, 873 -> 887 KB, 25 calls (0.9 ms CPU)
              :fixed {:ticks 360000 :bytes 910000 :calls 25}
              :sizes refresh-sizes}
             {:of 'seon.source/program-tx :input 'seon.source-test/diff-of
              :n "forms in the program's other files"
              :work "diff: each of the file's forms looked up by id and compared with its account; the tx: one form's facts"
              :growth :constant
              ;; measured 2026-10-23: 1.20M -> 1.23M ticks, 3.37 -> 3.48 MB, 142 calls (3.3 ms CPU); 1.16M -> 1.19M
              ;; ticks before form-row read :form/source (seon.program/load-order's :source)
              :fixed {:ticks 1260000 :bytes 3560000 :calls 142}
              :sizes refresh-sizes}
             {:of 'datahike.api/db-with :input 'seon.source-test/transact-of
              :n "forms in the program's other files"
              :work "transact: one form's facts and its var's pointer, into the indexes of the program (log n per datom)"
              :growth :log
              ;; measured 2026-10-23: 561k -> 635k ticks, 1.38 -> 1.59 MB from 10 to 1,000 (1.9 ms CPU);
              ;; 393k / 420k / 465k ticks and 1.23 / 1.31 / 1.43 MB at 10 / 100 / 1,000 after Datahike
              ;; d89be47 (a datom costs its index inserts) and Jolt 0d39edc3: per log2 n 10.8k ticks
              ;; and 30 KB, so 357k and 1.13 MB fixed
              :fixed {:ticks 360000 :bytes 1130000} :per-n {:ticks 11500.0 :bytes 31000.0}
              :sizes refresh-sizes}
             {:of 'seon.source/refresh-tx :input 'seon.source-test/refresh-of
              :n "forms in the program's other files; a body edit in a file no namespace requires"
              :work "read, analyze, gate, diff and the tx of one file, the phases above: nothing of the program's other files"
              :growth :constant
              ;; measured 2026-10-23: 1.80M -> 1.84M ticks, 6.62 -> 6.76 MB, 563 calls (6-8 ms CPU); was 822k
              ;; ticks at 10 namespaces of one function each, +350 ticks and +1.1 KB per namespace
              :fixed {:ticks 1900000 :bytes 6950000 :calls 563}
              :sizes refresh-sizes}
             {:of 'seon.source/refresh-tx :input 'seon.source-test/ns-edit-of
              :n "forms in the program's other files, every file requiring the edited one"
              :work "the edited file's read and analysis; the gate finds nothing lost (its names and macros are as they were: a dependent never reads another namespace's ns form), so no dependent is read"
              :growth :constant
              ;; measured 2026-10-27 at 10 forms: 1.28M ticks, 5.33 MB, 575 calls (the ns form re-read, no dependent)
              :fixed {:ticks 1400000 :bytes 5600000 :calls 590}
              :sizes refresh-sizes}
             {:of 'seon.source/refresh-tx :input 'seon.source-test/hub-refresh-of
              :n "forms in the program's other files, every file requiring the edited one"
              :work "as a leaf's: the interface is unchanged, so no dependent is read"
              :growth :constant
              ;; measured 2026-10-23: as the leaf's, 1.80M -> 1.84M ticks, 6.61 -> 6.75 MB, 563 calls
              :fixed {:ticks 1900000 :bytes 6950000 :calls 563}
              :sizes refresh-sizes}])

(defn- prove-fixed
  "Each of the fixed-change estimates at the indexes is a proof."
  [& is]
  (doseq [e (map fixed-change-estimates is)]
    (testing (str (:of e) " on " (:input e))
      (cost/proof e))))

(deftest a-fixed-change-costs-the-same-at-any-program-size
  (prove-fixed 0 1 2))

(deftest a-fixed-change-is-transacted-at-the-same-cost-at-any-program-size
  (prove-fixed 3))

(deftest a-fixed-leaf-edit-costs-the-same-at-any-program-size
  (prove-fixed 4))

(deftest a-fixed-ns-form-edit-costs-the-same-at-any-program-size
  (prove-fixed 5))

(deftest a-fixed-hub-edit-costs-the-same-at-any-program-size
  (prove-fixed 6))

(deftest a-helper-recorded-after-its-user-is-written-before-it
  ;; step E run #10 (2026-10-23): an agent defined form-of at its REPL, then redefined
  ;; summary-of (which keeps its place) to call it; the merge wrote form-of after summary-of,
  ;; a file that cannot load fresh. file-text writes a form after what it references in its
  ;; namespace, unless an earlier form declares it, and leaves a file in that order as it is
  (let [db0 (empty-db)
        db (d/db-with db0 (source/import-tx db0 {"src/app/wb.clj" "(ns app.wb)\n\n(defn a [] 1)\n\n(defn b [] (a))\n"}))
        db (pt/record db 'app.wb "(defn- h [] 2)" 'app.wb "(defn a [] (h))")
        text (source/file-text db 'app.wb)]
    (is (< (str/index-of text "(defn- h") (str/index-of text "(defn a")) text)
    (is (< (str/index-of text "(defn a") (str/index-of text "(defn b")) "the rest keeps its order")
    (is (= 2 (try (binding [*ns* *ns*] (load-string (str/replace text "app.wb" "app.wb-fresh"))) ((resolve 'app.wb-fresh/b))
                  (finally (remove-ns 'app.wb-fresh))))
        "the written file loads fresh and runs")
    (testing "a declare recorded after its user is written before it"
      (let [text (source/file-text (pt/record db 'app.wb "(declare k)" 'app.wb "(defn b [] (k) (a))") 'app.wb)]
        (is (< (str/index-of text "(declare k)") (str/index-of text "(defn b")) text)))
    (testing "a redefinition that now calls a function after it in the file is written after it"
      (let [dbl (d/db-with db0 (source/import-tx db0 {"src/app/wl.clj" "(ns app.wl)\n\n(defn a [] 1)\n\n(defn z [] 5)\n"}))
            text (source/file-text (pt/record dbl 'app.wl "(defn a [] (z))") 'app.wl)]
        (is (< (str/index-of text "(defn z") (str/index-of text "(defn a")) text)))
    (doseq [[what src] [["an earlier declare" "(ns app.wd)\n\n(declare g)\n\n(defn f [] (g))\n\n(defn g [] 3)\n"]
                        ["a quoted name of a later var (no load dependency)" "(ns app.wd)\n\n(defn f [] 'app.wd/g)\n\n(defn g [] 3)\n"]]]
      (testing (str what ": the file stays as written")
        (let [dbd (d/db-with db0 (source/import-tx db0 {"src/app/wd.clj" src}))]
          (is (= src (source/file-text dbd 'app.wd))))))))

(deftest the-write-back-of-a-file-is-one-pass-over-its-forms
  (cost/proof {:of 'seon.source/file-text :input 'seon.source-test/file-of
               :n "functions in the namespace, each calling the five before it and a declared one"
               :work "per form: its row (seon.program/form-row: the entity and its definitions, refs and declares) and its refs looked up once in seon.program/load-order"
               :growth :linear
               ;; measured 2026-10-23: 813k -> 63.7M ticks, 2.19 -> 174 MB, 55 -> 4,015 calls from 10 to 1,000 forms;
               ;; main's file-text (a sort by :order): 789k -> 61.0M ticks, 1.99 -> 153 MB, 4,012 calls (load-order: +4% ticks, +14% bytes, three calls)
               :per-n {:ticks 64000.0 :bytes 176000.0 :calls 4.0}
               :fixed {:ticks 200000 :bytes 500000 :calls 15}
               :sizes [5 15 50]}))

(deftest a-checkout-whose-submodule-moved-is-synced-to-its-pin
  ;; root, 2026-10-27: a worktree that merged main kept its old Jolt checked out (no one ran
  ;; bin/worktree --sync), and bin/build built that Jolt, not the pin: the forks step hung on a
  ;; Jolt three fixes old. The one sync path moves a submodule checkout to the commit its HEAD pins
  ;; (bin/build calls it first); one with uncommitted work it would lose is an error naming it
  (let [tmp (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-sync-" (random-uuid))) (.mkdirs)))
        here (seon.config/checkout)
        sub (str tmp "/sub") co (str tmp "/co") x (str co "/reference-code/x")
        git (fn [dir & args] (let [r (apply sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t" "-c" "protocol.file.allow=always" args)]
                               (is (zero? (:exit r)) (str args (:err r))) (str/trim (:out r))))
        sync! #(sh/sh (str here "/bin/worktree") "--sync" co)]
    (try
      (.mkdirs (java.io.File. sub)) (.mkdirs (java.io.File. co))
      (git sub "init" "-q" "-b" "main")
      (spit (str sub "/f") "a") (git sub "add" ".") (git sub "commit" "-q" "-m" "a")
      (let [a (git sub "rev-parse" "HEAD")
            _ (do (spit (str sub "/f") "b") (git sub "commit" "-q" "-am" "b"))
            b (git sub "rev-parse" "HEAD")]
        (git co "init" "-q" "-b" "main")
        (git co "submodule" "add" "-q" sub "reference-code/x")
        (git x "checkout" "-q" "--detach" a)
        (git co "add" ".") (git co "commit" "-q" "-m" "pin a")
        (testing "a checkout left at another commit is moved to the pin, and says so"
          (git x "checkout" "-q" "--detach" b)
          (let [r (sync!)]
            (is (zero? (:exit r)) (:err r))
            (is (str/includes? (:out r) (str "reference-code/x: " (subs b 0 8) " -> " (subs a 0 8))) (:out r))
            (is (= a (git x "rev-parse" "HEAD")))))
        (testing "one with uncommitted work it would lose is an error naming it, and left as it is"
          (git x "checkout" "-q" "--detach" b)
          (spit (str x "/f") "work")
          (let [r (sync!)]
            (is (not (zero? (:exit r))))
            (is (str/includes? (str (:out r) (:err r)) "reference-code/x") (pr-str r))
            (is (= b (git x "rev-parse" "HEAD")))
            (is (= "work" (slurp (str x "/f"))))))
        (testing "at its pin, nothing moves"
          (git x "checkout" "-q" "--" ".") (git x "checkout" "-q" "--detach" a)
          (is (= "" (str/trim (:out (sync!)))))))
      (finally (sh/sh "rm" "-rf" tmp)))))

;; a scratch repository of one file on a store of its own, empty: the seed is one of the claims, and none reads more
;; than one namespace (the checkout's tree analysed at each boot was 81 s)
(deftest a-boot-resumes-main-and-reads-no-file
  ;; task seon.root/a-boot-resumes-from-the-db (owner, 2026-11-09: "Boot's job is to resume the existing system or if
  ;; it's fresh to index it and get it to the point that everything is running off the db. From that point on we are
  ;; syncing data TO files not reading from them"), end to end through bin/root: a fresh store is indexed once, at its
  ;; first boot (seon.root/seed!); a file written under the running root changes nothing on main; a commit made while
  ;; root is down changes nothing at the next boot either, which resumes main with its source tree moved away; only
  ;; my.root/import!, a person's re-index, reads the tree. Red before: the boot imported the commit (import-tree!)
  (let [dir (scratch-dir "resume")
        co (str dir "/co") away (str dir "/co-away")
        git (fn [d & args] (let [r (apply sh/sh "git" "-C" d "-c" "user.name=t" "-c" "user.email=t@t" args)]
                             (is (zero? (:exit r)) (str args (:err r))) (str/trim (:out r))))
        probe! (fn [n] (spit (str co "/src/seon/resume_probe_" n ".clj") (str "(ns seon.resume-probe-" n ")\n\n(defn probe [] " n ")\n")))
        commit! (fn [] (git co "add" "src") (git co "commit" "-q" "-m" "probe") (git co "rev-parse" "HEAD"))
        root0 {:store (str dir "/runs/store") :main "main" :runs (str dir "/runs") :env {"SEON_RESULTS_PORT" "0"}
               :args ["--set" (str ":seon.config.source/root=" (pr-str co)) "--set" ":seon.config.source/dirs=[\"src\"]"
                      "--set" ":seon.config.root/wiki-dirs=[]"]}
        root (atom nil)
        start! #(do (reset! root (ts/start-root! root0))
                    (is (some? (:port @root)) (str "the root started: " (ts/output (str dir "/runs/root.log")))))
        ask (fn [code] (let [a (ts/ask @root {:op :eval :agent "root" :code code})]
                         (if (:error a) a (ts/value-of (:response a)))))
        has-var? (fn [n] (ask (str "(some? (seon.db/eid @(:conn sys) :var/name 'seon.resume-probe-" n "/probe))")))
        state "[(seon.program/program-digest @(:conn sys)) (:commit (:seon.root/source (datahike.api/pull @(:conn sys) [:seon.root/source] [:seon.root/id :root])))]"]
    (try
      (.mkdirs (java.io.File. (str co "/src/seon")))
      ;; a tree's own deps, which the binary was not built with
      (spit (str co "/deps.edn") "{:deps {}}")
      (git co "init" "-q" "-b" "main")
      (probe! 0)
      (let [c0 (commit!)]
        (start!)
        (is (true? (has-var? 0)) "a fresh store is indexed at its first boot")
        (let [[d0 seeded] (ask state)]
          (is (= c0 seeded) "from the tree's commit")
          (probe! 1)
          (is (false? (has-var? 1)) "a file written under the running root changes nothing on main")
          (ts/stop-root! @root)
          (commit!)
          (probe! 2)
          (commit!)
          (is (zero? (:exit (sh/sh "mv" co away))))
          (start!)
          (is (= [false false [d0 c0]] [(has-var? 1) (has-var? 2) (ask state)])
              "the next boot resumed main as it was: it read neither the commits made while it was down nor the tree, which is gone")
          (ts/stop-root! @root)
          (is (zero? (:exit (sh/sh "mv" away co))))
          (start!)
          (is (= [false false] [(has-var? 1) (has-var? 2)]) "nor with the tree back")
          (is (string? (ask "(:commit (my.root/import! sys))")) "import!, a person's re-index, reads the tree")
          (is (= [true true] [(has-var? 1) (has-var? 2)]) "and brings what it changed onto main")))
      (finally
        (when @root (ts/stop-root! @root))
        (sh/sh "rm" "-rf" dir)))))

(defn tree-of
  "read-source's request for one changed file in a tree of n source files (a new directory under
  target/, no git repository): the :paths of the file that changed, the others never asked."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [d (scratch-dir (str "tree" n))]
    (.mkdirs (java.io.File. (str d "/src")))
    (dotimes [i n] (spit (str d "/src/f" i ".clj") (str "(ns f" i ")\n\n(defn a [] " i ")\n")))
    [{:req {:root d :dirs ["src"] :paths #{"src/f0.clj"}}}]))

(deftest a-batch-of-edits-reads-the-changed-files-not-the-tree
  ;; my.root/import! with paths names its files, and the :source read takes
  ;; them (seon.fx/read-source :paths) in place of walking and reading every file, so an edit costs
  ;; the same in a tree of any size (task seon.source/an-import-reads-only-changed-files)
  (cost/proof {:of 'seon.fx/read-source :input 'seon.source-test/tree-of
               :n "source files in the tree, one of them changed"
               :work "the one changed file read; the others neither listed nor read"
               :growth :constant
               :fixed {:bytes 500000 :calls 20}
               :sizes [10 100 1000]}))

(deftest an-ns-form-adds-to-a-recorded-form
  ;; docs/agent.md#the-repl: the recorded text as a REPL's ns form leaves the namespace: what it adds, every other byte kept
  (let [old "(ns a.b\n  \"Doc.\"\n  (:refer-clojure :exclude [defn])\n  ;; why\n  (:require [x.y :as y]\n            [z.w]))"]
    (is (= old (source/ns-text-adding old '(ns a.b))) "a bare ns switches and adds nothing")
    (is (= old (source/ns-text-adding old '(ns a.b (:require [x.y :as y])))) "a require it holds adds nothing")
    (let [t (source/ns-text-adding old '(ns a.b (:require [q.r :as r])))]
      (is (= #{'x.y 'z.w 'q.r} (seon.program/ns-requires (read-string t))) t)
      (is (clojure.string/includes? t ";; why") "a comment stays")
      (is (clojure.string/includes? t "(:refer-clojure :exclude [defn])")))
    (is (= "New." (nth (read-string (source/ns-text-adding old '(ns a.b "New."))) 2)) "a docstring it states")
    (is (= #{'q.r} (seon.program/ns-requires (read-string (source/ns-text-adding "(ns a.b)" '(ns a.b (:require q.r)))))) "a form with no :require takes one")
    (is (some #(= '(:import [java.io File]) %) (read-string (source/ns-text-adding old '(ns a.b (:import [java.io File]))))) "another clause is added whole")))

(deftest an-alias-only-the-process-holds-is-refused
  ;; docs/source.md#main-s-program-follows-the-tree: real run 10: a file whose ns form had lost its requires passed analysis in a process that still held them, and merged.
  ;; What a file's forms name must be what its own ns form declares, in any process
  (binding [*ns* *ns*]
    (eval '(ns app.held-alias (:require [clojure.string :as held]))))
  (let [said (try (source/accounts 'app.held-alias "(ns app.held-alias)\n\n(defn f [] (held/join []))\n") nil
                  (catch Exception e (ex-message e)))]
    (is (some-> said (clojure.string/includes? "held")) (str "refused, naming the alias: " (pr-str said))))
  (is (map? (source/accounts 'app.held-alias "(ns app.held-alias (:require [clojure.string :as held]))\n\n(defn f [] (held/join []))\n")) "declared: read")
  (is (map? (source/accounts 'app.held-alias "(ns app.held-alias)\n\n(defn g [] (clojure.string/join []) (Math/abs 1) '(held/x))\n")) "a full name, a class's static and quoted data are not aliases"))

(deftest a-var-the-import-makes-public-may-be-named-by-a-later-file
  ;; the standing root's main held test-runner/tree-branch private (defn-) while the tree made it
  ;; public and seon.proofs-test named it: every import of main failed "is not public" and root's
  ;; program stayed stale (seon.source/analyze-form; docs/failure-points.md)
  (binding [*ns* *ns*] (eval '(ns app.pubsrc)) (eval '(defn- g [] 1)))
  (let [db (empty-db)
        files {"src/app/pubsrc.clj" "(ns app.pubsrc)\n\n(defn g [] 1)\n"
               "src/app/pubuser.clj" "(ns app.pubuser (:require [app.pubsrc]))\n\n(defn h [] (app.pubsrc/g))\n"}]
    (is (:private (meta (resolve 'app.pubsrc/g))) "the image holds g as private")
    (is (vector? (source/tx-of db files true)) "the user of g analyzes after the file that makes it public")
    (is (:private (meta (resolve 'app.pubsrc/g))) "and the image's var is as it was: analysis changes none (the tree's privacy answers, seon.source/tree-private)")
    (is (vector? (source/tx-of db {"src/app/pubsrc.clj" "(ns app.pubsrc)\n\n(defn- g [] 1)\n"} true)))))

(deftest a-var-is-as-private-as-the-tree-analyzed-holds-it
  ;; docs/source.md#a-var-is-private-as-the-tree-analyzed-holds-it: whether a file may name a var is the privacy of the tree analyzed, never
  ;; the image's var, which analysis does not change: tree A defines app.privt/g with defn-, tree B with defn and a changed file names it;
  ;; analyzed A, B, A, then B again (a changed file only, as health analyzes a head after its base) B names it, and the image's var is as it was
  (binding [*ns* *ns*] (eval '(ns app.privt)) (eval '(defn- g [] 1)))
  (let [db (empty-db)
        a {"src/app/privt.clj" "(ns app.privt)\n\n(defn- g [] 1)\n"
           "src/app/privuser.clj" "(ns app.privuser (:require [app.privt]))\n\n(defn h [] 1)\n"}
        user-b "(ns app.privuser (:require [app.privt]))\n\n(defn h [] (app.privt/g))\n"
        b (assoc a "src/app/privt.clj" "(ns app.privt)\n\n(defn g [] 1)\n" "src/app/privuser.clj" user-b)
        private-before (:private (meta (resolve 'app.privt/g)))
        tx-b (source/tx-of db b true)
        db-b (d/db-with db tx-b)]
    (is (vector? tx-b) "B names g, public in B, whatever the image holds")
    (is (vector? (source/tx-of db a true)) "A analyzed after B")
    (is (vector? (source/tx-of db-b {"src/app/privuser.clj" user-b} true)) "B's changed file alone, after A: g is public as B's program holds it")
    (is (= private-before (:private (meta (resolve 'app.privt/g)))) "the image's var is as it was before the four analyses")))

(deftest a-dependent-read-again-takes-the-privacy-of-every-file-the-refresh-read
  ;; docs/source.md#a-var-is-private-as-the-tree-analyzed-holds-it: refresh-tx reads a dependent again in a later round (its
  ;; required namespace lost a name it references), and that round's analysis asks the tree's privacy of every file the refresh
  ;; read, never only that round's: a seed of a store holding seon.db/index as a private def failed "var: #'seon.db/index is not
  ;; public" when a dependent named the index the same refresh made public (2026-11-09, jolt/remodel-4 after jolt/index-arg)
  (let [db (empty-db)
        db (d/db-with db (source/import-tx db {"src/rp/a.clj" "(ns rp.a)\n\n(defn- f [] 1)\n\n(defn g [] 2)\n"
                                               "src/rp/b.clj" "(ns rp.b (:require [rp.a]))\n\n(defn h [] (rp.a/g))\n"}))
        tx (source/refresh-tx db {"src/rp/a.clj" "(ns rp.a)\n\n(defn f [] 1)\n"
                                  "src/rp/b.clj" "(ns rp.b (:require [rp.a]))\n\n(defn h [] (rp.a/f))\n"})]
    (is (vector? tx) "rp.b, read again because rp.a lost g, names rp.a/f, public in the tree the refresh read")
    (is (some? (seon.db/eid (d/db-with db tx) :var/name 'rp.b/h)))))

(deftest a-source-the-image-cannot-analyze-is-an-error-not-a-throw
  ;; the standing root's watcher died of "var: #'seon.db/install-missing! is not public": a changed file named a var
  ;; the running (older) image held as private; the import threw out of the batch handler (task
  ;; seon.source/analysis-failure-is-data)
  (let [db (empty-db)
        files {"src/app/priv.clj" "(ns app.priv)\n\n(defn f [] (seon.source/analyze 'x 'y))\n"}
        e (source/tx-of db files true)]
    (is (= :seon.source/unanalyzable (:seon.error/type e)) "the image holds seon.source/analyze as private")
    (is (re-find #"not public" (:seon.error/message e)) "with the analyzer's words")
    (is (= ["src/app/priv.clj"] (:files e)))
    (is (vector? (source/tx-of db {"src/app/ok.clj" "(ns app.ok)\n\n(defn f [] 1)\n"} true)) "a file that analyzes is its facts")
    (is (vector? (source/tx-of db {"src/app/ok.clj" "(ns app.ok)\n\n(defn f [] 1)\n"} false)) "whole tree too")))

(deftest a-forms-head-reads-without-its-namespace-loaded
  ;; docs/health.md#a-function-that-cites-no-section: seon.source/first-head read a recorded form with
  ;; read-string in its namespace, found by find-ns; in a process that never loaded it (bin/check health),
  ;; a form holding `::flow/in-ports` did not read, and the check failed on any change to such a var
  (is (= "defn" (seon.source/first-head 'never.loaded.ns "(defn f [] {::flow/in-ports {:in 1} ::own 2})")))
  (is (= "defmacro" (seon.source/first-head 'never.loaded.ns "(defmacro m [x] `(do ~x ::a/b))")))
  (is (nil? (seon.source/first-head 'never.loaded.ns "[::a/b 1]"))))

(deftest a-renamed-alias-target-analyzes-beside-the-image
  ;; task seon.source/another-trees-ns-form-analyzes-beside-the-image: one process analyzes two trees whose
  ;; namespaces disagree (a namespace renamed, the alias of its requirer pointed at the new one), in either
  ;; direction: the removed file's old text is never read again (refresh-tx's dependents), and a namespace
  ;; no source root holds answers its requirer's require from the analysis (read-nss), never a load from disk
  (let [old {"src/ra/old_lib.clj" "(ns ra.old-lib)\n\n(defn f [x] (inc x))\n"
             "src/ra/user.clj" "(ns ra.user (:require [ra.old-lib :as lib]))\n\n(defn g [x] (lib/f x))\n"}
        new {"src/ra/new_lib.clj" "(ns ra.new-lib)\n\n(defn f [x] (inc x))\n"
             "src/ra/user.clj" "(ns ra.user (:require [ra.new-lib :as lib]))\n\n(defn g [x] (lib/f x))\n"}
        db0 (empty-db)
        a (d/db-with db0 (source/import-tx db0 old))
        forward (d/db-with a (source/refresh-tx a {"src/ra/old_lib.clj" nil "src/ra/new_lib.clj" (new "src/ra/new_lib.clj") "src/ra/user.clj" (new "src/ra/user.clj")}))
        b (d/db-with db0 (source/import-tx db0 new))
        back (d/db-with b (source/refresh-tx b {"src/ra/new_lib.clj" nil "src/ra/old_lib.clj" (old "src/ra/old_lib.clj") "src/ra/user.clj" (old "src/ra/user.clj")}))]
    (is (some? (seon.db/eid forward :var/name 'ra.new-lib/f)) "the renamed namespace is read")
    (is (nil? (seon.db/eid forward :var/name 'ra.old-lib/f)) "the removed one is gone, its text never read again")
    (is (some? (seon.db/eid back :var/name 'ra.old-lib/f)) "and back: a namespace on no root answers its requirer")
    (is (nil? (seon.db/eid back :var/name 'ra.new-lib/f)))))

(deftest a-comment-typed-at-a-repl-keeps-its-line
  ;; found checking a real sync, 2026-11-07: a REPL form's lead is its comment alone, so "(ns x);; c(deftest t ...)" read the deftest as part of the comment
  (let [main (d/db-with (main-with-file) [{:db/id "f" :form/id "repl-f" :form/ns [:ns/name 'app.pt-src] :form/src "(def lead-kept 1)" :form/lead ";; why" :form/order 100000}])
        text (source/file-text main 'app.pt-src)]
    (is (clojure.string/includes? text "\n\n;; why\n(def lead-kept 1)") text)))

(deftest a-new-namespace-takes-a-file-by-its-name
  ;; the owner, 2026-11-07: "these changed forms name no source file to write back" stops being an outcome: a namespace made at a REPL reaches disk as any other
  (is (= "src/app/my_lib/core.clj" (source/namespace-path 'app.my-lib.core)))
  (is (= "test/app/core_test.clj" (source/namespace-path 'app.core-test))))
