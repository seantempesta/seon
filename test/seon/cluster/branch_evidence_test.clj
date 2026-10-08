(ns seon.cluster.branch-evidence-test
  "The commit gate's question (`seon.cluster.branch-evidence/branch-evidence`):
  does a lane's branch prove the files it commits?"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as agent]
            [seon.cluster.branch-evidence :as evidence]
            [seon.cluster.write-back-test :as write-back]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.id :as id]
            [seon.program :as program]
            [seon.test-support :as support]))

(def ^:private callee-original
  "(defn callee\n  \"Add one.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 1))")

(def ^:private caller-original
  "(defn caller\n  \"Call the callee.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (callee x))")

(defn- core-source
  "A callee and its caller."
  {:malli/schema [:=> [:cat :symbol] :string]}
  [fixture-ns]
  (str "(ns " fixture-ns "\n  \"Branch-evidence fixture.\"\n  (:require [clojure.test]))\n\n"
       callee-original "\n\n" caller-original "\n"))

(defn- lonely-source
  "A function no test reaches."
  {:malli/schema [:=> [:cat :symbol] :string]}
  [fixture-ns]
  (str "(ns " fixture-ns "\n  \"Unreached fixture.\")\n\n"
       "(defn lonely\n  \"Double.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (* 2 x))\n"))

(def ^:private callee-replacement
  "(defn callee\n  \"Add one, by increment.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (inc x))")

(def ^:private lonely-replacement
  "(defn lonely\n  \"Double, by addition.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x x))")

(defn- replace-in-file!
  "Write `replacement` over the original declaration `original` in the file at `path`, as write-back would."
  {:malli/schema [:=> [:cat :string :string :string] :nil]}
  [path original replacement]
  (spit path (str/replace (slurp path) original replacement))
  nil)

(deftest ^{:seon.test/long "Six agent turns, two of them my.test/check runs (one on the branch, one on H, each executing two fixture tests on their own branches), and five analyses of two one-file fixtures."
           :seon.test/long-ms 60000}
  a-branch-proves-its-files-only-with-current-green-evidence
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        h-branch (get-in (db/db h-conn) [:config :branch])
        cluster-name (:seon.cluster/name target)
        suffix (id/id)
        core-ns (symbol (str "befix" suffix ".core"))
        lonely-ns (symbol (str "befix" suffix ".lonely"))
        core-path (str "src/befix" suffix "/core.clj")
        lonely-path (str "src/befix" suffix "/lonely.clj")
        root (str "tmp/branch-evidence-test-" suffix)
        absolute (.getCanonicalPath (io/file root))
        sym #(symbol (str %1) %2)
        callee [:seon.fn/sym (sym core-ns "callee")]
        lonely [:seon.fn/sym (sym lonely-ns "lonely")]
        c (keyword (str "branch-evidence-c-" suffix))
        tests-source ["(clojure.test/deftest caller-check (clojure.test/is (= 3 (caller 2))))"
                      "(clojure.test/deftest broken-check (clojure.test/is (= 0 (callee 1))))"]
        ask (fn [paths accepted]
              (evidence/branch-evidence store {:seon.store/branch c :seon.source/head h-branch :seon.fn/root absolute
                                               :seon.source/paths paths :seon.source/accepted-red-before accepted}))]
    (io/make-parents (io/file root core-path))
    (spit (io/file root core-path) (core-source core-ns))
    (spit (io/file root lonely-path) (lonely-source lonely-ns))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            ;; The functions and their tests are an agent's own, evaluated at its REPL on H
            ;; before the lane forks, so SCI holds a Var for each.
            _ (write-back/with-agent! h-conn cluster-name (str "be-author-" suffix) core-ns
                (fn [request] (doseq [text (into [callee-original caller-original] tests-source)]
                                (write-back/submit! request text))))
            _ (registry/branch! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)
            check (str "(my.test/check {:seon.test/changed ['" (second callee) "]})")]
        (try
          (write-back/with-agent! c-conn cluster-name (str "be-lonely-" suffix) lonely-ns
            #(write-back/submit! % lonely-replacement))
          (write-back/with-agent! c-conn cluster-name (str "be-lane-" suffix) core-ns
            (fn [request]
              (write-back/submit! request callee-replacement)
              (testing "before the file is written the gate refuses it as differing, whatever the test evidence"
                (let [answer (ask [core-path] #{})]
                  (is (false? (:seon.source/proven? answer)))
                  (is (= [callee] (mapv :seon.source/declaration (:seon.source/differing answer))))
                  (is (= [callee] (:seon.source/changed answer)))
                  (is (some #{(sym core-ns "caller-check")} (:seon.source/verified answer))
                      "the defn's automatic step already ran its reaching tests for the new digest")))
              ;; The lane runs its reaching tests as an agent does.
              (write-back/submit! request check)))
          ;; Root runs them on H: broken-check is red there too.
          (write-back/with-agent! h-conn cluster-name (str "be-root-" suffix) core-ns #(write-back/submit! % check))
          (replace-in-file! (str root "/" core-path) callee-original callee-replacement)
          (replace-in-file! (str root "/" lonely-path) "(defn lonely\n  \"Double.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (* 2 x))" lonely-replacement)
          (testing "the file equals the branch and the reaching test is green there; a test red on H too is red before"
            (let [answer (ask [core-path] #{})]
              (is (= [] (:seon.source/differing answer)))
              (is (= [(sym core-ns "caller-check")] (:seon.source/verified answer)))
              (is (= [(sym core-ns "broken-check")] (:seon.source/red-before answer)))
              (is (false? (:seon.source/proven? answer)) "red before refuses until root accepts it")))
          (testing "root's acceptance of the red-before test proves the file"
            (is (true? (:seon.source/proven? (ask [core-path] #{(sym core-ns "broken-check")})))))
          (testing "a changed function no test reaches is unreached"
            (let [answer (ask [core-path lonely-path] #{(sym core-ns "broken-check")})]
              (is (= [(second lonely)] (:seon.source/unreached answer)))
              (is (false? (:seon.source/proven? answer)))))
          (finally (agent/release-context! candidate))))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch c})
        (support/delete-recursively! root)))))
