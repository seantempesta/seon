(ns seon.root-test
  "Root as a whole process, run as the built binary (target/seon, bin/build seon): a root
  boots its fork server, so it runs in a process of its own rather than in the test
  runner's, whose threads and GC sweepers are the runner's."
  (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.db :as pdb] [datahike.api :as d]))

(defn- test-store "The one store these tests' roots take a branch of." []
  (str (.getCanonicalPath (java.io.File. ".")) "/target/runs/test-store"))

(defn- drop-main!
  "Delete main and every branch under it from the test store (seon.check/drop-mains), once
  the root that made them has stopped."
  [main]
  (sh/sh "./seon" "drop" main "--set" (str ":seon.config.root/store-path=" (pr-str (test-store))) :dir "target"))

(deftest root-threads-are-what-root-declares
  ;; three start!/stop! cycles in one process: while running (three agents just
  ;; evaluated) and after each stop!, every thread is one seon.root/declared-threads
  ;; names, by name stem, the GC's sweepers are the declared number, and the OS counts
  ;; nothing else. A diff of {} is exact; anything else names what differs. No web
  ;; listener (SEON_WEB_PORT=0): its adapter's threads outlive stop! (task
  ;; web-server-threads-are-joined-at-stop).
  ;; On a main of its own on the test store, deleted at the end.
  (let [main (str "root-test-" (random-uuid))
        {:keys [exit out err]} (sh/sh "env" "SEON_WEB_PORT=0" (str "SEON_STORE=" (test-store)) (str "SEON_MAIN=" main)
                                      "./seon" "threads-declared" "3" :dir "target")
        _ (drop-main! main)
        res (edn/read-string (last (str/split-lines out)))]
    (is (= 0 exit) (str out err))
    (is (= 3 (count (:running res)) (count (:stopped res))))
    (is (= [{} {} {}] (:running res)))
    (is (= [{} {} {}] (:stopped res)))))

(deftest a-root-binary-requires-a-library-it-did-not-compile
  ;; task jolt.ns/a-built-binary-requires-a-stdlib-namespace-it-did-not-compile: a root
  ;; importing src and test runs each test file's ns form, and seon.print-test's requires
  ;; fipp.engine, which needs Jolt's stdlib clojure.core.rrb-vector; a binary finds only
  ;; what it compiled. The root boots, imports, and exits when its stdin (empty) closes.
  ;; Its store: a main of its own on the one test store, target/runs/test-store (under runs/
  ;; so a worktree's clone of target/ leaves it out: a store's identity is its path), deleted
  ;; with its branches when the root has stopped.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        main (str "root-test-" (random-uuid))
        {:keys [exit out err]} (sh/sh "env" "SEON_WEB_PORT=0" "./seon" "root"
                                      "--set" (str ":seon.config.source/root=" (pr-str checkout))
                                      "--set" ":seon.config.source/dirs=[\"src\" \"test\"]"
                                      "--set" (str ":seon.config.root/store-path=" (pr-str (test-store)))
                                      "--set" (str ":seon.config.root/main=" (pr-str main))
                                      :in "" :dir "target")]
    (drop-main! main)
    (is (= 0 exit) "the root boots with src and test imported")
    (is (str/includes? out "ROOT LISTENING") "and listens")
    (is (not (str/includes? (str out err) "Could not locate")) "every required library located")))

(defn- root-answers
  "The output of a built root on the store at path (extra --set args), asked each request
  in turn on its server: one answer line per request, then EXIT and its log. The root's
  stdin is a fifo held open until every answer is in; its listening line (or its death)
  ends the wait, as bin/root's start does. env: variables the root starts with (\"K=v \")."
  ([path args requests] (root-answers path args requests ""))
  ([path args requests env]
  (let [port (let [s (java.net.ServerSocket. 0)] (try (.getLocalPort s) (finally (.close s))))
        ask (fn [m] (str "printf '%s\\n' " (pr-str (pr-str m)) " | nc -w 30 127.0.0.1 " port ";"))
        script (str "mkfifo '" path "-in'; " env "./seon root"
                    " --set ':seon.config.root/store-path=" (pr-str path) "' " (str/join " " args)
                    " --set ':seon.config.root/entrance-port=" port "' --set ':seon.config.web/port=0'"
                    " < '" path "-in' > '" path ".log' 2>&1 & R=$!; exec 3>'" path "-in';"
                    " until grep -q 'ROOT LISTENING' '" path ".log' || ! kill -0 $R 2>/dev/null; do sleep 0.1; done;"
                    (apply str (map ask requests))
                    " exec 3>&-; wait $R; echo EXIT $?; cat '" path ".log'")]
    (:out (sh/sh "zsh" "-c" script :dir "target")))))

(deftest a-root-resumes-a-store-an-earlier-build-wrote
  ;; task seon.root/a-root-resumes-a-store-an-earlier-build-wrote: the store as builds of
  ;; 2026-10-21 before CONFIG merged left it (next/runs/store-full-20261021, read 17:35): its
  ;; schema lacks the attributes added since (the config's, the model manifest's, the source's), and its main's
  ;; root entity holds a quiescent fact and no boot (their boot fact was refused), which
  ;; seon.task/entity-value could not read. The built root resumes that main and answers a
  ;; root eval and {:op :agents}. A store on disk of this test's own, the last resort: what
  ;; is proved is a kept store another build wrote; removed at the end.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        path (str checkout "/target/root-test-resume-" (random-uuid))
        later? #(or (str/starts-with? (namespace (:db/ident %)) "seon.config") (= "seon.ai.model" (namespace (:db/ident %)))
                    (#{:ns/file :ns/tail :form/lead :form/source :seon.attempt/commit :seon.attempt/commit-error} (:db/ident %)))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database cfg)
        c (d/connect cfg)
        _ (d/transact c {:tx-data (vec (remove later? pdb/schema))})
        _ (doseq [tx (pdb/seed-tx 110)] (d/transact c {:tx-data tx}))
        _ (d/branch! c :db :old-run)
        c2 (d/connect (assoc cfg :branch :old-run))
        _ (d/transact c2 {:tx-data [{:seon.root/id :root :seon.root/quiescent {:t 536870914 :stuck []}}]})
        _ (pdb/sync! cfg)
        _ (do (d/release c2) (d/release c))
        out (root-answers path ["--set ':seon.config.root/main=\"old-run\"'"]
                          [{:op :eval :agent "root" :code "(+ 1 2)"} {:op :agents}
                           {:op :eval :agent "root" :code "(get-in (my.root/problems sys) [:classes :platform :n])"}])
        [eval-line agents-line problems-line] (str/split-lines out)
        answer (fn [l] (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))]
    (try
      (is (str/includes? out "ROOT LISTENING") "the root boots on the earlier build's store")
      (is (= "3" (:printed (first (:items (answer eval-line))))) "a root eval answers")
      (is (map? (:tasks (answer agents-line))) "{:op :agents} answers its tasks")
      (is (= "0" (:printed (first (:items (answer problems-line))))) "and nothing it read was a defect")
      (is (str/includes? out "EXIT 0") "and it stops when its stdin closes")
      (finally (sh/sh "rm" "-rf" path (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest a-second-boot-resumes-the-same-main
  ;; task seon.root/one-main-resumed: a root started with no main named works on the
  ;; config's main ("main"), and a second root on the same store resumes it: the task the
  ;; first loaded and the attempt it started are still there. A store of this test's own, the
  ;; last resort: what is proved is the default main of a store, which a shared test store's
  ;; other users would also resume; removed at the end.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        path (str checkout "/target/runs/root-test-main-" (random-uuid))
        tasks (str path "-tasks")
        _ (.mkdirs (java.io.File. tasks))
        _ (spit (str tasks "/demo.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]))
        main {:op :eval :agent "root" :code "(name (seon.db/conn-main (:conn sys)))"}
        held {:op :eval :agent "root" :code "[(count (seon.task/tasks @(:conn sys))) (set (map str (keys (seon.task/attempts @(:conn sys)))))]"}
        first-boot (str/split-lines (root-answers path [] [main {:op :eval :agent "root" :code (str "(my.root/load-tasks! sys " (pr-str tasks) ")")}
                                                           {:op :attach :task "demo/t"} held]))
        second-boot (str/split-lines (root-answers path [] [main held]))
        printed (fn [l] (:printed (first (:items (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil))))))]
    (try
      (is (= "\"main\"" (printed (first first-boot))) "a boot with no main named works on the config's main")
      (is (= "\"main\"" (printed (first second-boot))) "the second boot resumes the same main")
      (let [[tasks-1 attempts-1] (edn/read-string (printed (nth first-boot 3)))
            [tasks-2 attempts-2] (edn/read-string (printed (second second-boot)))]
        (is (= [2 1] [tasks-1 (count attempts-1)]) "the first boot holds root's task and the one it loaded, and the attempt it started")
        (is (= 2 tasks-2) "the second boot still holds both tasks")
        (is (every? attempts-2 attempts-1) "and the first boot's attempt (it reaps it and starts the next)"))
      (finally (sh/sh "rm" "-rf" path tasks (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest a-refused-wiki-is-a-recorded-problem-and-the-boot-goes-on
  ;; root's boot loads the wiki (seon.root/wiki-tx) in its boot fact's commit; one link to no
  ;; section in any page used to end the boot (bin/root start and next with it). Now the
  ;; error is a recorded :platform error my.root/problems shows, and the root boots. A
  ;; checkout of this test's own: this one's files, its docs/ with one broken page more
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        tmp (str checkout "/target/runs/root-test-wiki-" (random-uuid))
        path (str tmp "-store")
        _ (.mkdirs (java.io.File. (str tmp "/docs")))
        _ (doseq [f (.listFiles (java.io.File. checkout)) :let [n (.getName f)] :when (not (#{"docs" ".git"} n))]
            (sh/sh "ln" "-s" (.getPath f) (str tmp "/" n)))
        _ (doseq [f (.listFiles (java.io.File. checkout "docs"))] (sh/sh "cp" (.getPath f) (str tmp "/docs/")))
        _ (spit (str tmp "/docs/zz-broken.md") "# Broken\n\nIt links to [nowhere](agent.md#nowhere).\n")
        out (root-answers path [] [{:op :eval :agent "root" :code "(boolean (re-find #\"links to docs/agent.md#nowhere\" (pr-str (my.root/problems sys))))"}]
                          (str "SEON_CHECKOUT='" tmp "' "))
        printed (:printed (first (:items (try (edn/read-string {:default tagged-literal} (first (str/split-lines out))) (catch Exception _ nil)))))]
    (try
      (is (str/includes? out "ROOT LISTENING") (str "the root boots: " out))
      (is (= "true" printed) "and the refused wiki is a recorded problem")
      (finally (sh/sh "rm" "-rf" tmp path (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest every-end-of-a-root-is-a-fact
  ;; task seon.root/every-end-of-a-root-is-a-fact: a clean stop is a fact naming its cause
  ;; (:seon.root/stopped; here its stdin closes, as bin/root stop closes it), the next boot
  ;; records it as :seon.root/previous-end, and a boot that left no stop fact (SIGKILL, a
  ;; crash) is found :unclean. A store of this test's own, as a-second-boot-resumes-the-same-main's
  ;; (root-answers names its fifo and log after the store); removed at the end.
  (let [path (str (.getCanonicalPath (java.io.File. ".")) "/target/runs/root-test-ends-" (random-uuid))
        ev (fn [code] {:op :eval :agent "root" :code code})
        printed (fn [l] (some-> (:printed (first (:items (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil))))) edn/read-string))
        [b1] (str/split-lines (root-answers path [] [(ev "(:boot sys)")]))
        [prev killed] (str/split-lines
                       (root-answers path [] [(ev "(:seon.root/previous-end (d/pull @(:conn sys) [:seon.root/previous-end] [:seon.root/id :root]))")
                                              (ev "(#'seon.root/previous-end (d/db-with @(:conn sys) [{:seon.root/id :root :seon.root/boot \"killed-boot\"}]))")]))]
    (try
      (is (string? (printed b1)))
      (is (= {:boot (printed b1) :cause :stdin-eof} (select-keys (printed prev) [:boot :cause]))
          "the second boot found the first's clean end: its stdin closed")
      (is (= {:boot "killed-boot" :cause :unclean} (select-keys (printed killed) [:boot :cause]))
          "a boot that left no stop fact is found :unclean")
      (finally (sh/sh "rm" "-rf" path (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest a-built-root-reads-a-line-citation
  ;; task jolt.ns/whole-program-inference-keeps-nil-checks: compiled, `[from to]` destructured
  ;; from `(if dash [a b] [suffix])` typed `to` a string, so `(nil? to)` folded false and
  ;; `src/a.clj:50` reached parse-long as nil; my.root/load-tasks! failed on every task file
  ;; citing `path:N`. On a main of its own on the test store, deleted at the end.
  (let [path (test-store)
        main (str "root-test-" (random-uuid))
        out (root-answers path [(str "--set ':seon.config.root/main=" (pr-str main) "'")]
                          [{:op :eval :agent "root" :code "((resolve 'seon.ref/line-citation) \"src/a.clj:50\")"}])
        answer (try (edn/read-string {:default tagged-literal} (first (str/split-lines out))) (catch Exception _ nil))]
    (try
      (is (= "\"src/a.clj\"" (:printed (first (:items answer)))) out)
      (finally (drop-main! main) (sh/sh "rm" "-f" (str path "-in") (str path ".log"))))))

(deftest a-build-without-whole-program-inference-reads-with-tools-reader
  ;; task jolt.ns/a-build-without-whole-program-inference-compiles-tools-reader: with
  ;; JOLT_NO_WP_INFER=1, `jolt build` of an app requiring clojure.tools.reader fails to analyze
  ;; tools.reader.impl.utils (`<=clojure-1-7-alpha5 is not bound`), so a root cannot be built
  ;; open-world; with inference on the same app builds and reads.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        out (str checkout "/target/reader-no-wp")
        build (sh/sh "env" "JOLT_NO_WP_INFER=1" (str "JOLT_PWD=" checkout) "target/jolt/jolt" "-A:hello" "build" "-m" "build.reader" "-o" out)]
    (try
      (is (= 0 (:exit build)) "the build without whole-program inference succeeds")
      (is (= "[1 2]" (str/trim (:out (sh/sh out)))) "and the binary reads")
      (finally (sh/sh "rm" "-rf" out (str out ".build"))))))

(deftest main-follows-its-source-tree-across-restarts
  ;; task seon.root/main-follows-its-source-tree: a root resuming its main imports its source
  ;; tree again when the tree reads otherwise than main's last import, replacing main's
  ;; program (seon.source/replace-tx), and imports nothing when it reads the same. Three
  ;; boots on a main of their own on the test store (deleted at the end), the tree a scratch
  ;; repository under target/: commit A, then commit B with one function changed and one
  ;; removed, then B again.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        tree (str checkout "/target/tmp/root-test-follow-" (random-uuid))
        tasks (str tree "-tasks")
        git (fn [& args] (apply sh/sh "git" "-C" tree "-c" "user.name=t" "-c" "user.email=t@localhost" args))
        commit! (fn [text]
                  (spit (str tree "/src/follow/tree.clj") text)
                  (git "add" "-A") (git "commit" "-q" "-m" "tree")
                  (str/trim (:out (git "rev-parse" "HEAD"))))
        a "(ns follow.tree)\n\n(defn kept [] 1)\n\n(defn changed [] 1)\n\n(defn removed [] 1)\n"
        b "(ns follow.tree)\n\n(defn kept [] 1)\n\n(defn changed [] 2)\n"
        path (test-store)
        main (str "root-test-" (random-uuid))
        args [(str "--set ':seon.config.root/main=" (pr-str main) "'")
              (str "--set ':seon.config.source/root=" (pr-str tree) "'")
              "--set ':seon.config.source/dirs=[\"src\"]'"]
        probe {:op :eval :agent "root"
               :code (str "(let [db @(:conn sys)] {:fact (:seon.root/source (datahike.api/pull db [:seon.root/source] [:seon.root/id :root]))"
                          " :text (seon.source/file-text db 'follow.tree)"
                          " :removed-forms (count (filter #(clojure.string/includes? % \"removed\") (map :v (datahike.api/datoms db :aevt :form/src))))"
                          " :removed-var (count (datahike.api/datoms db :avet :var/name 'follow.tree/removed))"
                          " :tasks (count (seon.task/tasks db))})")}
        boot! (fn [requests]
                (let [out (root-answers path args requests)]
                  (sh/sh "rm" "-f" (str path "-in"))
                  (mapv (fn [l] (try (edn/read-string (:printed (first (:items (edn/read-string {:default tagged-literal} l)))))
                                     (catch Exception _ l)))
                        (take (count requests) (str/split-lines out)))))]
    (.mkdirs (java.io.File. (str tree "/src/follow")))
    (.mkdirs (java.io.File. tasks))
    (.mkdirs (.getParentFile (java.io.File. ^String path)))
    (spit (str tasks "/demo.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]))
    (sh/sh "git" "init" "-q" tree)
    (try
      (let [sha-a (commit! a)
            [_ one] (boot! [{:op :eval :agent "root" :code (str "(my.root/load-tasks! sys " (pr-str tasks) ")")} probe])
            sha-b (commit! b)
            [two] (boot! [probe])
            [three] (boot! [probe])]
        (is (= [sha-a a 2] [(:commit (:fact one)) (:text one) (:tasks one)]) (pr-str one))
        (is (= sha-b (:commit (:fact two))) "the second boot imported the tree's new commit")
        (is (= b (:text two)) "main holds B's text, byte for byte")
        (is (= [0 0] [(:removed-forms two) (:removed-var two)]) "no form or var of the removed function")
        (is (= 2 (:tasks two)) "the tasks are intact")
        (is (= 3 (:retracted (:fact two))) "retracted: the changed function's old form, the removed function's form and its var")
        (is (= (:fact two) (:fact three)) "the same commit imports nothing: the fact is the second boot's")
        (is (= b (:text three))))
      (finally (drop-main! main) (sh/sh "rm" "-rf" tree tasks (str path "-in") (str path ".log"))))))
