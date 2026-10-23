(ns seon.check-test
  "bin/check's store (owner, 2026-10-21: one store, many branches, as in production): the run
  seeds one store (seon.check/seed); every step's root opens it on a main of its own, a
  branch of the seed (seon.db/open!, as seon.root/start! does), names its agents' branches
  under that main, and a recovery step reopens the same store on the same main."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [clojure.core.async :as a] [seon.check] [seon.docs] [seon.problems] [seon.fx] [seon.source] [clojure.string] [clojure.string :as str] [seon.lib]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest steps-are-branches-of-one-store
  ;; a store on disk of this test's own, the last resort: what is proved is how bin/check
  ;; opens its LMDB store from separate roots and reopens it after a kill; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/check-test-" (random-uuid))
        seed (pdb/open! :lmdb path 300 :db)
        a (pdb/open! :lmdb path 300 :smoke-1)
        b (pdb/open! :lmdb path 300 :drain-1)]
    (try
      (testing "the run seeds the store once; each step's main is a new branch of it"
        (is (:seeded? seed))
        (is (not (:seeded? a)))
        (is (not (:seeded? b)))
        (is (not (:recovered? a)))
        (is (= [:smoke-1 :drain-1] (mapv (comp pdb/main-branch :cfg) [a b])))
        (is (= (get-in seed [:cfg :store :id]) (get-in a [:cfg :store :id]) (get-in b [:cfg :store :id])))
        (is (= (d/commit-id @(:conn seed)) (d/merge-base (:conn a) :smoke-1 :db) (d/merge-base (:conn b) :drain-1 :db)))
        (is (= (count (d/datoms @(:conn seed) :eavt)) (count (d/datoms @(:conn a) :eavt)))))
      (testing "a step's writes stay on its main"
        (d/transact (:conn a) [{:note/text "smoke's"}])
        (is (= #{"smoke's"} (notes @(:conn a))))
        (is (= #{} (notes @(:conn b))))
        (is (= #{} (notes @(:conn seed)))))
      (testing "agents' branches are named under each root's main, so steps never share one; on :db (production) as given"
        (is (= :smoke-1/agent-1 (pdb/on-main :smoke-1 :agent-1)))
        (is (= :smoke-1/agent-1 (pdb/on-main :smoke-1 :smoke-1/agent-1)))
        (is (= :smoke-1 (pdb/on-main :smoke-1 :db)))
        (is (not= (pdb/on-main :smoke-1 :agent-1) (pdb/on-main :drain-1 :agent-1)))
        (is (= :agent-1 (pdb/on-main :db :agent-1)))
        (d/branch! (:conn a) :smoke-1 :smoke-1/agent-1)
        (d/branch! (:conn b) :drain-1 :drain-1/agent-1)
        (is (= [:agent-1] (pdb/of-main :smoke-1 (d/branches (:conn a)))))
        (is (= [:agent-1] (pdb/of-main :drain-1 (d/branches (:conn a))))))
      (testing "a recovery step reopens the same store on the same main"
        (pdb/sync! (:cfg a))
        (run! #(d/release (:conn %)) [a b seed])
        (let [r (pdb/open! :lmdb path 300 :smoke-1)]
          (is (:recovered? r))
          (is (not (:seeded? r)))
          (is (= #{"smoke's"} (notes @(:conn r))))
          (d/release (:conn r))))
      (finally
        (clojure.java.shell/sh "rm" "-rf" path)))))

(defn- entries "The names in directory p (none when it does not exist)." [p]
  (set (map #(.getName %) (or (.listFiles (java.io.File. p)) []))))

(deftest a-check-leaves-its-store-as-it-found-it
  ;; task seon.check/a-run-takes-a-branch-and-deletes-it: a check step run against a store
  ;; works on a branch of it (its main, check-<step>) and deletes what it made when it is
  ;; green; a test's branch of the test store is deleted by its fixture (seon.test-store/test-branch!);
  ;; neither makes a store. The store given is this test's own (the last resort: what is
  ;; proved is what a run leaves in the store it is given); removed at the end.
  (let [dir (System/getProperty "user.dir")
        path (str dir "/target/runs/check-test-given-" (random-uuid))
        _ (d/release (:conn (pdb/open! :lmdb path 300 :db)))
        branches #(let [c (d/connect (pdb/store-cfg :lmdb path))] (try (d/branches c) (finally (d/release c))))
        before (branches)
        ;; the step's own runs directory: other tests (bin/test's shards run at once) make theirs
        ;; under target/runs while this one runs, so only a directory of its own shows what it made
        runs (str dir "/target/runs/check-test-runs-" (random-uuid))
        _ (.mkdirs (java.io.File. runs))
        run (ts/binary-sh "env" (str "SEON_STORE=" path) (str "SEON_RUNS=" runs) "SEON_WEB_PORT=0" "bin/check" "config" :dir dir)]
    (try
      (is (= 0 (:exit run)) (str "the step is green: " (:out run)))
      (is (= before (branches)) "the store's branch set holds nothing the step made")
      (is (= #{} (entries runs)) "and no new store or run directory exists")
      (testing "a test's branch of the test store is deleted when the test ends"
        (let [seen (fn [cfg conn] [(:branch cfg) (d/branches conn) (count (d/datoms @conn :eavt))])
              [b1 in1 datoms] (ts/test-branch! seen)
              [b2 in2] (ts/test-branch! seen)]
          (is (pos? datoms) "the fixture hands the test a branch holding the seed")
          (is (in1 b1) "the branch exists while the test runs")
          (is (not (in2 b1)) "a later test sees no branch the earlier one made")
          (is (= (disj in1 b1) (disj in2 b2)) "and nothing else changed")))
      (finally (clojure.java.shell/sh "rm" "-rf" path (str path "-agents") runs)))))

(deftest records-are-read-at-the-agents-commit
  ;; task seon.check/mcp-reads-the-agents-records-once-committed: the branch is read again at
  ;; each of the agent's commit events, never before one when its records are not there yet
  (let [ev (a/chan 8)
        reads (atom 0)
        ;; the branch shows the records from its second read on: after a commit
        read #(if (= 1 (swap! reads inc)) [] [:r1 :r2])]
    (doseq [e [{:type :reply :agent :a1} {:type :commit :agent :a2 :t 5} {:type :commit :agent :a1 :t 7} {:type :ready :agent :a3}]]
      (a/>!! ev e))
    (is (= [:r1 :r2] (#'seon.check/await-records ev :a1 read #(= 2 (count %)))))
    (is (= 2 @reads) "once at the start, once at a1's commit")
    (is (= {:type :ready :agent :a3} (a/poll! ev)) "it waited through a1's commit, not a2's or a reply")))

(deftest the-probe-says-what-it-took
  ;; task seon.check/every-wait-is-the-event-it-means: the system check awaits the probe's
  ;; own event for its third message, not a window
  (let [ev (a/chan 4)
        st (#'seon.check/probe-proc {:tag "one" :events ev})
        [st o1] (#'seon.check/probe-proc st :in 1)
        [_ o2] (#'seon.check/probe-proc st :in 2)]
    (is (= {:events ev} (:clojure.core.async.flow/out-ports st)))
    (is (= [[{:type :probe :tag "one" :got 1}] [{:type :probe :tag "one" :got 2}]]
           (map #(mapv (fn [e] (dissoc e :at-ms)) (:events %)) [o1 o2])))))

(deftest every-test-root-starts-through-start-root!
  ;; task seon.check/a-test-root-names-its-store-and-main: read from the program of test/ (the
  ;; compiler's references, not a text search over files), no test var but the helper's one
  ;; launcher runs bin/root or `./seon root` through binary-sh, so every test root names its
  ;; store and main and waits on its listening line
  (let [files (into {} (for [^java.io.File f (file-seq (java.io.File. "test")) :when (.endsWith (.getName f) ".clj")]
                         [(.getPath f) (slurp f)]))
        db (d/db-with (ts/test-db) (seon.source/import-tx (ts/test-db) files))
        launchers (set (for [[v src] (d/q '[:find ?v ?s :where [?e :var/name ?v] [?e :var/form ?f]
                                             [?f :form/refs seon.test-store/binary-sh] [?f :form/src ?s]] db)
                             ;; binary-sh is the primitive the one launcher calls; this test names both in its query
                             :when (not (#{'seon.test-store/binary-sh 'seon.check-test/every-test-root-starts-through-start-root!} v))
                             :when (some #(clojure.string/includes? src %) ["bin/root" "seon root" "\"./seon\" \"root\""])]
                         v))]
    (is (seq files))
    (is (= #{'seon.test-store/root-sh} launchers))))

(deftest a-starter-that-is-gone-leaves-no-fifo-after-the-next-starter
  ;; task seon.check/a-killed-starter-leaves-no-fifo: a starter removes its own fifo when it ends
  ;; or is terminated; SIGKILL runs nothing, so the next starter reclaims it (bin/held --reclaim):
  ;; the fifo of a pid that is no process goes, a live pid's (this test process) and any file not
  ;; named <who>-starter-<pid>.fifo stay
  (let [dir (str (.getCanonicalPath (java.io.File. "target/tmp")) "/reclaim-" (random-uuid))
        _ (.mkdirs (java.io.File. dir))
        dead-pid (str/trim (:out (ts/binary-sh "sh" "-c" "echo $$")))   ; a process that has ended
        live-pid (str (seon.lib/c-getpid))
        mk (fn [n] (let [p (str dir "/" n)] (clojure.java.shell/sh "mkfifo" p) p))
        dead-check (mk (str "check-starter-" dead-pid ".fifo"))
        dead-test (mk (str "test-starter-" dead-pid ".fifo"))
        live (mk (str "test-starter-" live-pid ".fifo"))
        other (mk "something-else.fifo")
        exists? #(.exists (java.io.File. ^String %))]
    (try
      (clojure.java.shell/sh "bin/held" "--reclaim" dir)
      (is (not (exists? dead-check)) "a gone check's fifo is reclaimed")
      (is (not (exists? dead-test)) "a gone test runner's fifo is reclaimed")
      (is (exists? live) "a live starter's fifo stays")
      (is (exists? other) "a file not named for a starter stays")
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest a-stop-ends-on-its-holders-end-and-a-gone-holder-answers-at-once
  ;; the first bin/update hung for 10 minutes in `bin/root stop`: it read a fifo it held open
  ;; read-write itself, so EOF could never come, and a holder that never wrote left it waiting for
  ;; ever. bin/held --end signals the holder and waits on the fifo the holder keeps open for its
  ;; life: its status line, or its last writer closing (the holder's end). A holder that is gone
  ;; has no writer, so the wait ends at once; a path that is not a fifo (a holder from before the
  ;; done fifo) is refused at once. A hang here is the bug.
  (let [dir (str (.getCanonicalPath (java.io.File. "target/tmp")) "/end-" (random-uuid))
        _ (.mkdirs (java.io.File. dir))
        sh clojure.java.shell/sh
        held (fn []
               (let [ready (str dir "/ready-" (random-uuid)) done (str dir "/done-" (random-uuid))]
                 (sh "mkfifo" ready done)
                 ;; a command that says it is up, then reads its stdin until the holder closes it
                 (sh "bin/held" "--detach" "--log" (str dir "/log") "--ready" ready "--done" done
                     "sh" "-c" "echo ROOT LISTENING 1; exec cat")
                 (let [[h c] (str/split (str/trim (slurp ready)) #" ")] {:holder h :cmd c :done done})))
        end (fn [{:keys [holder done]}] (sh "bin/held" "--end" "TERM" holder done "1"))]
    (try
      (let [live (held) r (end live)]
        (is (= [0 "0\n"] [(:exit r) (:out r)]) "a live holder: TERM closes its command's stdin, and the status is its answer"))
      (let [h (held)]
        (sh "kill" "-9" (:holder h))
        (sh "kill" "-9" (:cmd h))
        (let [r (end h)]
          (is (= [0 ""] [(:exit r) (:out r)]) "a holder that is gone answers at once, with no status")))
      (let [f (str dir "/plain")]
        (spit f "")
        (is (= 2 (:exit (sh "bin/held" "--end" "TERM" "1" f "1"))) "a path that is no fifo is refused at once"))
      (finally (sh "rm" "-rf" dir)))))

;; ---- task seon.check/health-is-what-a-branch-caused ------------------------------------------

(deftest a-failed-seed-says-failed
  ;; a single mode's seed that fails (its store a regular file, which no store opens) prints why
  ;; and the mode's verdict line; before, `run seed 2000 > /dev/null || exit 1` exited 1 with nothing
  (let [dir (System/getProperty "user.dir")
        path (str dir "/target/runs/check-test-not-a-store-" (random-uuid))
        _ (spit path "not a store")
        run (ts/binary-sh "env" (str "SEON_STORE=" path) "SEON_WEB_PORT=0" "bin/check" "config" :dir dir)
        lines (str/split-lines (str (:out run) (:err run)))]
    (try
      (is (not= 0 (:exit run)))
      (is (= "CONFIG FAILED (seed)" (last lines)) (:out run))
      (is (< 1 (count lines)) "the seed's own output says why")
      (finally (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest a-stale-binary-is-refused
  ;; bin/check runs no binary built from another tree: run from a checkout of its own (this one's
  ;; bin/, a git repository of its own) whose target/seon.tree names another tree, a mode is
  ;; refused, naming both trees, before the binary runs (main's binary in a worktree whose config
  ;; differs died reading that config, unseen)
  (let [dir (System/getProperty "user.dir")
        tmp (str dir "/target/runs/check-test-stale-" (random-uuid))]
    (try
      (clojure.java.shell/sh "zsh" "-c" (str "mkdir -p '" tmp "/target' && cp -R '" dir "/bin' '" tmp "/' && git -C '" tmp "' init -q"
                                            " && ln -s '" dir "/target/seon' '" tmp "/target/seon' && print -n not-this-tree > '" tmp "/target/seon.tree'"))
      (let [run (ts/binary-sh "env" "SEON_WEB_PORT=0" (str tmp "/bin/check") "config" :dir tmp)
            out (str (:out run) (:err run))]
        (is (not= 0 (:exit run)))
        (is (str/includes? out "target/seon was built from tree not-this-tree; this checkout is tree ") out)
        (is (= "CONFIG FAILED (stale binary)" (last (str/split-lines out))) out))
      (finally (clojure.java.shell/sh "rm" "-rf" tmp)))))

(defn- cited-db
  "The test store's seed with a wiki page citing two vars of its program and one name that names
  nothing (a dead citation the base already has), as seon.problems-test's caused fixture; and the two vars."
  []
  (let [db0 (ts/test-db)
        [v w] (map :v (take 2 (d/datoms db0 :aevt :var/name)))
        page (str "# P\n\nIt reads `" v "`, `" w "` and `" (symbol (namespace v) "no-such-var") "`.\n")]
    [(d/db-with db0 (seon.docs/load-tx db0 "demo" {"demo/p.md" page})) v]))

(deftest a-problem-the-base-had-is-not-the-branchs
  (let [[base] (cited-db)
        h (seon.check/branch-health base base nil)]
    (is (pos? (get-in (seon.problems/health base) [:classes :dead-citation :n])) "the base has an open problem")
    (is (= [] (:caused h)) "the branch changed nothing, so it caused none")
    (is (every? true? (vals (:checks h))) "and its health is green")))

(deftest a-problem-the-branch-made-fails-it
  (let [[base v] (cited-db)
        head (d/db-with base [[:db/retractEntity (pdb/eid base :var/name v)]])
        h (seon.check/branch-health base head nil)]
    (is (= [{:class :dead-citation :message (str "demo/p.md#p cites " v ", which names nothing")}] (:caused h)))
    (is (false? (get-in h [:checks :no-dead-citation])) "its health is red on the class it made")
    (testing "the classes asked for are the ones judged (bin/laws catch asks for :unnamed-site)"
      (is (= {:no-unnamed-site true} (:checks (seon.check/branch-health base head [:unnamed-site])))))
    (testing "a class no verdict judges (an attention class never fails a branch) is refused, never a green"
      (is (thrown? Exception (seon.check/branch-health base head [:stale-section]))))))

(deftest the-base-is-the-merge-base-or-the-previous-main
  ;; seon.fx/base-commit in a repository of its own: a branch is held to its merge base with main;
  ;; main's own commit to the previous main when clean, to itself when changed; a branch with no
  ;; commits of its own to its own start; main's first commit has none
  (let [dir (str (System/getProperty "user.dir") "/target/runs/check-test-base-" (random-uuid))
        git (fn [& args] (str/trim (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t" args))))
        commit! (fn [f] (spit (str dir "/" f) f) (git "add" "-A") (git "commit" "-qm" f) (git "rev-parse" "HEAD"))]
    (try
      (.mkdirs (java.io.File. dir))
      (git "init" "-q" "-b" "main")
      (let [a (commit! "a")]
        (is (nil? (seon.fx/base-commit dir)) "main's first commit has no previous main")
        (git "checkout" "-q" "-b" "topic")
        (is (= a (seon.fx/base-commit dir)) "a branch with no commits of its own: its own start, not the commit before it")
        (commit! "b")
        (is (= a (seon.fx/base-commit dir)) "a branch: its merge base with main")
        (git "checkout" "-q" "main")
        (let [c (commit! "c")]
          (is (= a (seon.fx/base-commit dir)) "main, clean: the previous main")
          (spit (str dir "/d") "d")
          (is (= c (seon.fx/base-commit dir)) "main with an uncommitted change: main itself")))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest bin-tree-names-the-tree-tree-id-names
  ;; bin/check compares the binary's record (bin/build writes it through bin/tree) with bin/tree;
  ;; root decides whether its source's inputs are its binary's with seon.fx/tree-id: one tree
  (let [dir (System/getProperty "user.dir")]
    (is (= (seon.fx/tree-id dir) (str/trim (:out (clojure.java.shell/sh (str dir "/bin/tree") dir)))))))
