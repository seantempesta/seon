(ns seon.source-test
  "Seon's own source is the program (the self-improvement loop, owner 2026-10-21): next/src
  read through the :source effect's performer, every form recorded with the compiler's
  account and its exact text, written back byte for byte, a merged change rewriting only
  its form, and the written files committed on the merges branch through the :git effect's
  performer. Never the live tree: write-back and commits go to a copy under target/."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.fx :as fx]
            [seon.merge :as pm]
            [seon.program :as program]
            [seon.source :as source]
            [seon.program-test :as pt]))

(defn- empty-db []
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (d/db-with @(d/connect cfg) pdb/schema)))

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
      (is (= {:loaded 0 :errors []} (program/load-all! db)) "load-all! skips namespaces imported from files")
      (is (= [] (program/load-changes! db (vec (for [dt (d/datoms db :aevt :form/src)] [:db/add (:e dt) :form/src (:v dt)]))))
          "and so does load-changes!"))))

(deftest import-then-write-back-is-a-zero-diff
  (let [files (src-files)
        db (imported files)
        out (source/files db (source/source-nss db))
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
    [(d/db-with main (pm/apply-tx main (into {} (for [[k [_ o]] (:ours r)] [k o])))) (:ours r)]))

(deftest a-merged-change-rewrites-only-its-form
  (let [main (main-with-file)]
    (is (= {"src/app/pt_src.clj" pt-text} (source/files main ['app.pt-src])))
    (let [[main2 ours] (merged main 'app.pt-src "(defn twice [x] (* 2 x))")]
      (is (= ['app.pt-src] (source/changed-nss ours)))
      (is (= {"src/app/pt_src.clj" (str/replace pt-text "(defn twice\n  [x]\n  (add x x))" "(defn twice [x] (* 2 x))")}
             (source/files main2 (source/changed-nss ours)))
          "the redefined form in its place, its lead kept; every other byte as it was"))
    (testing "a new form goes after the last, a blank line before it"
      (let [[main2 ours] (merged main 'app.pt-src "(defn thrice [x] (* 3 x))")]
        (is (= (str pt-text "\n(defn thrice [x] (* 3 x))\n")
               (get (source/files main2 (source/changed-nss ours)) "src/app/pt_src.clj")))))))

(deftest an-agent-runs-its-branchs-change-to-imported-source
  (let [main (main-with-file)
        branch (pt/record main 'app.pt-src "(defn twice [x] (* 2 x))")]
    (remove-ns 'app.pt-src)
    (is (= {:loaded 0 :errors []} (program/load-all! main)) "main's imported text is the binary's: never evaluated")
    (is (= [] (program/load-diff! main branch)))
    (is (= 6 ((resolve 'app.pt-src/twice) 3)) "the branch's redefinition runs in the agent")
    (is (nil? (resolve 'app.pt-src/add)) "the imported forms around it are not evaluated again")))

(defn- git [dir & args]
  (let [r (apply sh/sh "git" "-C" dir args)]
    (is (zero? (:exit r)) (str args " " (:err r)))
    (str/trim (:out r))))

(deftest a-merge-is-committed-on-the-merges-branch
  (let [tree (scratch-dir "git")
        _ (do (git tree "init" "-q" "-b" "main")
              (git tree "config" "user.email" "seon@localhost") (git tree "config" "user.name" "seon")
              (.mkdirs (java.io.File. (str tree "/src/app")))
              (spit (str tree "/src/app/pt_src.clj") pt-text)
              (git tree "add" "src") (git tree "commit" "-q" "-m" "base"))
        main-sha (git tree "rev-parse" "main")
        [main2 ours] (merged (main-with-file) 'app.pt-src "(defn twice [x] (* 2 x))")
        files (source/files main2 (source/changed-nss ours))
        msg (source/commit-message [{:task :app.pt/twice :attempt "app.pt/twice#1"}])
        r (fx/git-commit {:req {:dir tree :branch "jolt/seon-merges" :files files :message msg}})]
    (is (re-matches #"[0-9a-f]{40}" (str (get-in r [:value :sha]))) (pr-str r))
    (is (= (get-in r [:value :sha]) (git tree "rev-parse" "jolt/seon-merges")) "the merges branch, made at HEAD, holds the commit")
    (is (= main-sha (git tree "rev-parse" "jolt/seon-merges~1")) "its parent: the commit the tree checks out")
    (testing "the next merge's commit follows the last"
      (let [[main3 ours3] (merged main2 'app.pt-src "(def limit 4)")
            r2 (fx/git-commit {:req {:dir tree :branch "jolt/seon-merges" :files (source/files main3 (source/changed-nss ours3)) :message msg}})]
        (is (= (get-in r [:value :sha]) (git tree "rev-parse" "jolt/seon-merges~1")))
        (is (= (get-in r2 [:value :sha]) (git tree "rev-parse" "jolt/seon-merges")))
        (is (str/includes? (git tree "show" "jolt/seon-merges:src/app/pt_src.clj") "(def limit 4)"))))
    (is (= main-sha (git tree "rev-parse" "main")) "main never moves")
    (is (= (get files "src/app/pt_src.clj") (str (git tree "show" (str (get-in r [:value :sha]) ":src/app/pt_src.clj")) "\n")) "the merged text is what was committed")
    (is (= ["src/app/pt_src.clj"] (str/split-lines (git tree "diff" "--name-only" "main" (get-in r [:value :sha])))) "only the merged file")
    (is (str/includes? (git tree "log" "-1" "--format=%B" (get-in r [:value :sha])) "app.pt/twice#1") "the message names the attempt")
    (testing "a refused commit is the declared error, never a throw"
      (is (= :seon.fx/io-error (:seon.error/type (fx/git-commit {:req {:dir (str tree "/nope") :branch "jolt/seon-merges" :files files :message msg}}))))))
  (is (= {:value {:sha nil :files 0}} (fx/git-commit {:req {:dir "/nonexistent" :branch "b" :files {} :message "m"}}))
      "nothing to write commits nothing"))
