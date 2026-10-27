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
            [seon.db :as pdb] [seon.cost :as cost]
            [seon.fx :as fx]
            [seon.merge :as pm]
            [seon.program :as program]
            [seon.source :as source]
            [seon.program-test :as pt] [seon.stage :as stage]))

(defn- empty-db [] (ts/test-db))

(declare hold!)

(def ^:private here (System/getProperty "user.dir"))

(defn- src-files
  "next/src as the :source effect reads it: {relative path text}."
  []
  (let [r (fx/read-source {:req {:root here :dirs ["src"]}})]
    (is (map? (:value r)) (pr-str r))
    (:value r)))

(defn- scratch-dir
  "A new empty directory under target/ (the live tree is never written)."
  [label]
  (let [d (str here "/target/tmp/source-test-" label "-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(defn- imported
  "Main's db with every file of next/src imported; its namespaces are this process's (the
  running generation), so each form is analyzed, never run."
  [files]
  (doseq [n (keep #(source/file-ns (val %)) files)] (require n))
  (let [db (empty-db)] (d/db-with db (source/import-tx db files))))

(deftest importing-src-records-every-form-exactly
  (let [files (src-files)
        db (imported files)
        forms-of (fn [nsym] (d/q '[:find ?id ?src :in $ ?n :where [?ns :ns/name ?n] [?f :form/ns ?ns] [?f :form/id ?id] [?f :form/src ?src]] db nsym))]
    (is (< 40 (count files)) "every source file (seon.* and my.*)")
    (testing "every file: one namespace with its file, one form per form read, each form's text exact"
      (doseq [[path text] files
              :let [nsym (source/file-ns text) read (:forms (source/read-forms 'user text))]]
        (is (= path (d/q '[:find ?p . :in $ ?n :where [?e :ns/name ?n] [?e :ns/file ?p]] db nsym)) path)
        (is (= (count read) (count (forms-of nsym))) path)
        (is (= (frequencies (map :src read)) (frequencies (map second (forms-of nsym)))) path)))
    (testing "definitions from the compiler, pointing to their forms"
      (let [row (fn [sym] (d/pull db '[:var/kind :var/contract {:var/form [:form/src :form/refs]}] [:var/name sym]))]
        (is (str/starts-with? (get-in (row 'seon.program/load-order) [:var/form :form/src]) "(defn load-order\n"))
        (is (= :fn (:var/kind (row 'seon.program/load-order))))
        (is (= "[:=> [:cat [:sequential :map]] [:vector :map]]" (:var/contract (row 'seon.program/load-order))))
        (is (contains? (set (get-in (row 'seon.program/load-order) [:var/form :form/refs])) 'seon.program/topo)
            "a reference resolved by the compiler")
        (is (= :macro (:var/kind (row 'seon.contract/defn))))
        (is (= :value (:var/kind (row 'seon.program/banned))))
        (is (= '[:fn :fn] (mapv #(:var/kind (row %)) '[seon.source/read-forms my.fx/call])))))
    (testing "the platform's own namespaces are not loaded from main: the binary is the running generation"
      (is (= {:loaded (count (program/live-forms (empty-db))) :errors []} (program/load-all! db))
          "load-all! loads the test store's example program, and none of the namespaces imported from files")
      (is (= [] (program/load-changes! db (vec (for [dt (d/datoms db :aevt :form/src)] [:db/add (:e dt) :form/src (:v dt)]))))
          "and so does load-changes!"))))

(deftest import-then-write-back-is-a-zero-diff
  (let [files (src-files)
        db (imported files)
        out (source/files db (source/source-files db))
        copy (scratch-dir "zero")]
    (is (= (set (keys files)) (set (keys out))) "every file written back")
    (doseq [[path text] files] (is (= text (get out path)) path))
    (testing "through the :file effect's performer, onto a copy: diff -r finds nothing (README.md is no program)"
      (doseq [[path text] out]
        (.mkdirs (.getParentFile (java.io.File. (str copy "/" path))))
        (is (= {:value {:bytes (.length (java.io.File. (str here "/" path))) :path (str copy "/" path)}}
               (fx/write-file {:req {:path (str copy "/" path) :text text}}))))
      (let [r (sh/sh "diff" "-r" "-x" "*.md" (str here "/src") (str copy "/src"))]
        (is (= [0 ""] [(:exit r) (:out r)]))))))

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

(deftest an-imported-namespace-the-process-does-not-hold-is-loaded
  ;; task seon.agent/an-agent-holds-mains-tests: test/ is imported but not built into the
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
        msg (source/commit-message [{:task :app.pt/twice :run "app.pt/twice#1"}])
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
    (is (str/includes? (git tree "log" "-1" "--format=%B" (get-in r [:value :sha])) "app.pt/twice#1") "the message names the run")
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
  ;; task seon.source/an-import-reads-a-changed-ns-form-before-its-forms: a restarted root
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

(deftest a-restart-catches-up-and-a-new-generation-resumes
  ;; BUILD plan P5 (task seon.source/the-next-generation-runs-from-the-merges-commit),
  ;; through the path the owner uses: bin/root starts a root on a clone of this checkout
  ;; (Seon's own source as main's program, its store under this test's directory); through
  ;; its server two loop turns merge seon.generation/generation 1 -> 2 and a new
  ;; seon.generation/generations, each live in place. Root is SIGKILLed and the same binary,
  ;; older than both merges, started on the same store and main: it catches up at boot
  ;; (seon.program/load-all!). Then the cold-start path: my.root/build! builds main's last
  ;; merges commit (seon.fx/build, bin/build seon in the generation's worktree) and
  ;; `bin/root next` starts it on the same store and main. The root imports the owner's
  ;; standing root's dirs (src, test and the schemas: the main tree's config/local.edn, not
  ;; the default), so a restart re-imports test/ after load-all! has run main's test
  ;; namespaces, as the owner's does (2026-10-22: the import skipped seon.program-test's ns
  ;; form and the root died on `No such namespace: ts`). Declared long, bound 300 s: two
  ;; turns (~14 s each), three boots (~3-9 s each with the import) and one build (~25-60 s).
  (let [dir (scratch-dir "nextgen")
        tree (str dir "/tree")
        ;; its root's own store and main, named (seon.test-store/start-root!: an inherited
        ;; SEON_STORE or SEON_MAIN opened a store whose main already held these merges)
        ;; and its own results directory: a proof that resolves its var at run time
        ;; (app.proofs/gens-test) has a digest that leaves the var out, so a green another run
        ;; stored there closed its task with no turn and merged nothing
        root0 {:store (str dir "/runs/store") :main "main" :runs (str dir "/runs")
               :env {"SEON_TEST_RESULTS" (str dir "/test-results")}}
        ;; any failure or error in this test keeps its boot logs: each report passes through
        red (volatile! false)
        report clojure.test/report
        ;; bin/root start truncates runs/root.log: each boot's log is copied to its own file
        ;; (runs/boot-N.log) before the next boot, so a red keeps every boot's
        boots (volatile! 0)
        keep-boot! (fn [] (sh/sh "cp" (str dir "/runs/root.log") (str dir "/runs/boot-" (vswap! boots inc) ".log")))
        cloned (sh/sh "git" "clone" "-q" "--local" here tree)
        root (atom (ts/start-root! (assoc root0 :args ["--set" (str ":seon.config.source/root=" (pr-str tree))
                                                       "--set" ":seon.config.source/dirs=[\"src\" \"test\" \"resources/seon/schemas\"]"])))
        ;; the printed value of a root eval; a reply that is an error stays the error value
        ask (fn [code] (let [a (ts/ask @root {:op :eval :agent "root" :code code})]
                         (if (:error a) a (ts/value-of (:response a)))))]
    (binding [clojure.test/report (fn [m] (when (#{:fail :error} (:type m)) (vreset! red true)) (report m))]
    (try
      (is (zero? (:exit cloned)) (:err cloned))
      (is (some? (:port @root)) (str "the root started: " (slurp (str dir "/runs/root.log"))))
      (let [t1 (ask "(seon.e2e/loop-turn! sys)")
            t2 (ask "(seon.e2e/loop-turn! sys :loop-2)")
            args (ask "(my.root/resume-args sys)")]
        (is (= [2 2] [(:root t1) (:root t2)]) (str "each merge live in place: " (pr-str [t1 t2])))
        (is (= 2 (count (:commits t2)))
            (when-not (= 2 (count (:commits t2)))
              (str "two merges committed: " (pr-str (select-keys t1 [:commits :commit-errors])) " then " (pr-str (select-keys t2 [:commits :commit-errors]))
                 "; the runs: " (pr-str (ask "(vec (sort (datahike.api/q (quote [:find ?id ?st ?out ?turns :where [?r :seon.run/id ?id] [?r :seon.run/status ?st] [(get-else $ ?r :seon.run/outcome :none) ?out] [(get-else $ ?r :seon.run/turns :none) ?turns]]) @(:conn sys))))")))))
        ;; a crash: answered at the root's death, as its holder reaped it
        (ts/kill-root! @root)
        (keep-boot!)
        ;; the tree changes a test namespace's ns form while root is down (as a merge to test/
        ;; does): main still holds the old one, which load-all! runs at boot (and another test
        ;; namespace's require marks it loaded), and the import must still apply the new
        ;; alias before the form that uses it
        (let [f (str tree "/test/seon/program_test.clj")]
          (spit f (str (str/replace-first (slurp f) "(:require " "(:require [clojure.set :as restart-set] ")
                       "\n(defn restart-probe [] (restart-set/union #{1} #{2}))\n")))
        (let [restarted (reset! root (ts/start-root! (assoc root0 :args args)))]
          (is (some? (:port restarted)) (str "restarted: " (slurp (str dir "/runs/root.log"))))
          (is (true? (ask "(some? (seon.db/eid @(:conn sys) :var/name 'seon.program-test/restart-probe))"))
              "the changed ns form's alias analyzed: the form using it is main's program")
          (is (= [2 [1 2]] (ask "[(seon.generation/generation) (seon.generation/generations)]"))
              "the binary older than both merges runs both, caught up at boot on the same main"))
        (let [g (ask "(my.root/build! sys)")
              _ (keep-boot!)
              nxt (reset! root (ts/next-root! @root))]
          (is (nil? (:error g)) (str "the generation built: " (pr-str g)))
          (is (some? (:port nxt)) (str "the next generation started: " (slurp (str dir "/runs/root.log"))))
          (is (= [2 [1 2] (:commit g)] (ask "[(seon.generation/generation) (seon.generation/generations) (:commit (my.root/generation sys))]"))
              "the new generation runs both, resumed on main holding its generation")))
      (finally
        (ts/stop-root! @root)
        (keep-boot!)
        ;; a red keeps every boot's log (target/restart-test-<uuid>/boot-N.log, named in the
        ;; failure), the evidence a deleted scratch dir took with it
        (when @red
          (let [kept (str here "/target/restart-test-" (random-uuid))]
            (.mkdirs (java.io.File. kept))
            (doseq [n (range 1 (inc @boots))] (sh/sh "cp" (str dir "/runs/boot-" n ".log") kept))
            (println "the restart test's boot logs kept in" kept)))
        (doseq [g (.listFiles (java.io.File. (str dir "/runs/generations")))]
          (sh/sh (str here "/bin/worktree") "--remove-at" (.getPath g)))
        (sh/sh "rm" "-rf" dir))))))

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
        status-line (some #(when (str/starts-with? % " :seon.run/status ") %) (str/split-lines text))
        paused (str/trim (subs (str/replace status-line ":closed :refused]]" ":closed :refused :paused]]") (count " :seon.run/status ")))
        edit (fn [tx] (get (source/files (d/db-with db tx) [facts-path]) facts-path))]
    (is (str/ends-with? status-line ":closed :refused]]") "the entry's line ends its enum as the edit expects")
    (testing "every schema resource imported and written back unchanged is the same text"
      (let [dir (java.io.File. (str here "/resources/seon/schemas"))
            files (into {} (for [f (.listFiles dir) :when (str/ends-with? (.getName f) ".edn")]
                             [(str "resources/seon/schemas/" (.getName f)) (slurp f)]))
            db (empty-db)
            db (d/db-with db (source/import-tx db files))]
        (is (seq files) "the schemas directory holds resources")
        (is (= files (source/files db (source/source-files db))))))
    (testing "a replaced value: only its changed part"
      (is (= (str/replace text status-line (str/replace status-line ":closed :refused]]" ":closed :refused :paused]]"))
             (edit [{:schema/key :seon.run/status
                     :schema/form paused}]))))
    (testing "a removed entry takes its line"
      (is (= (str/replace text "\n :seon.run/pid :int" "")
             (edit [[:db/retractEntity [:schema/key :seon.run/pid]]]))))
    (testing "a new key after the last entry, read back as written"
      (let [out (edit [{:schema/key :seon.run/invalid :schema/form "[:map [:seon.error/message :string]]" :schema/file facts-path}])]
        (is (= (str (subs text 0 (- (count text) 2)) "\n :seon.run/invalid [:map [:seon.error/message :string]]}\n") out))
        (is (= (assoc (edn/read-string text) :seon.run/invalid [:map [:seon.error/message :string]])
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

(deftest the-source-read-carries-each-reference-code-pin
  ;; land6 review, must-fix 4: a cited reference-code file shows the revision main's source
  ;; pins it at; the :source effect reads each gitlink once, at import, so no render runs git
  (let [r (fx/read-source {:req {:root here :dirs ["src"]}})
        head-tree (str/trim (:out (sh/sh "git" "rev-parse" "HEAD:reference-code/malli")))]
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
        idx (program/reach-index db {:env "" :tree "" :config {}})
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

(defn read-of
  "seon.source/read-nss's argument: refresh-of n's edited file."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n] [(second (refresh-of n))])

(defn interface-of
  "seon.source/db-interface's arguments: hub-refresh-of n's db and its hub."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :symbol]]}
  [n] [(first (hub-refresh-of n)) 'rfc.c])

(defn diff-of
  "seon.source/program-tx's arguments: refresh-of n's db and its edited file, read."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map :map :set]]}
  [n] (let [[db ch] (refresh-of n)] [db (#'source/read-nss ch) ch #{}]))

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
  "Forms in the other files: a fixed change costs the same at each."
  [10 100 1000])

(deftest a-fixed-change-costs-the-same-at-any-program-size
  (doseq [e [{:of 'seon.source/read-nss :input 'seon.source-test/read-of
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
             {:of 'seon.source/refresh-tx :input 'seon.source-test/hub-refresh-of
              :n "forms in the program's other files, every file requiring the edited one"
              :work "as a leaf's: the interface is unchanged, so no dependent is read"
              :growth :constant
              ;; measured 2026-10-23: as the leaf's, 1.80M -> 1.84M ticks, 6.61 -> 6.75 MB, 563 calls
              :fixed {:ticks 1900000 :bytes 6950000 :calls 563}
              :sizes refresh-sizes}]]
    (testing (str (:of e) " on " (:input e))
      (cost/proof e))))

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
    (is (= 2 (try (load-string (str/replace text "app.wb" "app.wb-fresh")) ((resolve 'app.wb-fresh/b))
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
               :sizes [10 100 1000]}))

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
  ;; the watcher's source batch (my.root/source-changes) names its files, and the :source read takes
  ;; them (seon.fx/read-source :paths) in place of walking and reading every file, so an edit costs
  ;; the same in a tree of any size (task seon.source/an-import-reads-only-changed-files)
  (cost/proof {:of 'seon.fx/read-source :input 'seon.source-test/tree-of
               :n "source files in the tree, one of them changed"
               :work "the one changed file read; the others neither listed nor read"
               :growth :constant
               :fixed {:bytes 500000 :calls 20}
               :sizes [10 100 1000]}))
