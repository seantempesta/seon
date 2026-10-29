(ns seon.check-test
  "bin/check's store (owner, 2026-10-21: one store, many branches, as in production): the run
  seeds one store (seon.check/seed); every step's root opens it on a main of its own, a
  branch of the seed (seon.db/open!, as seon.root/start! does), names its agents' branches
  under that main, and a recovery step reopens the same store on the same main."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [clojure.core.async :as a] [seon.check] [test-runner] [seon.docs] [seon.problems] [seon.fx] [seon.source] [clojure.string] [clojure.string :as str] [seon.lib] [seon.contract :as contract]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest steps-are-branches-of-one-store
  ;; a store on disk of this test's own, the last resort: what is proved is how bin/check
  ;; opens its LMDB store from separate roots and reopens it after a kill; removed at the end
  (let [path (str (seon.config/checkout) "/target/check-test-" (random-uuid))
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
  (let [dir (seon.config/checkout)
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
  (let [files (into {} (for [^java.io.File f (file-seq (java.io.File. (seon.config/checkout) "test")) :when (.endsWith (.getName f) ".clj")]
                         [(.getPath f) (slurp f)]))
        db (d/db-with (ts/test-db) (seon.source/import-tx (ts/test-db) files))
        launchers (set (for [[v src] (d/q '[:find ?v ?s :where [?e :var/name ?v] [?e :var/form ?f]
                                             [?f :form/refs seon.test-store/binary-sh] [?f :form/src ?s]] db)
                             ;; binary-sh is the primitive the one launcher calls; this test names both in its query
                             :when (not (#{'seon.test-store/binary-sh 'seon.check-test/every-test-root-starts-through-start-root!} v))
                             ;; the launch is a literal argument ("bin/root", or "./seon" "root"), never prose that
                             ;; names the script (a test that writes a config file mentioning bin/root)
                             :when (some #(clojure.string/includes? src %) ["\"bin/root\"" "\"./seon\" \"root\""])]
                         v))]
    (is (seq files))
    (is (= #{'seon.test-store/root-sh} launchers))))

(deftest a-starter-that-is-gone-leaves-no-fifo-after-the-next-starter
  ;; task seon.check/a-killed-starter-leaves-no-fifo: a starter removes its own fifo when it ends
  ;; or is terminated; SIGKILL runs nothing, so the next starter reclaims it (bin/held --reclaim):
  ;; the fifo of a pid that is no process goes, a live pid's (this test process) and any file not
  ;; named <who>-starter-<pid>.fifo stay
  (let [dir (str (.getCanonicalPath (java.io.File. (seon.config/checkout) "target/tmp")) "/reclaim-" (random-uuid))
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
      (clojure.java.shell/sh (str (seon.config/checkout) "/bin/held") "--reclaim" dir)
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
  (let [dir (str (.getCanonicalPath (java.io.File. (seon.config/checkout) "target/tmp")) "/end-" (random-uuid))
        _ (.mkdirs (java.io.File. dir))
        sh clojure.java.shell/sh
        started (volatile! [])
        held (fn []
               (let [ready (str dir "/ready-" (random-uuid)) done (str dir "/done-" (random-uuid))]
                 (sh "mkfifo" ready done)
                 ;; a command that says it is up, then reads its stdin until the holder closes it
                 (sh (str (seon.config/checkout) "/bin/held") "--detach" "--log" (str dir "/log") "--ready" ready "--done" done
                     "sh" "-c" "echo ROOT LISTENING 1; exec cat")
                 (let [[h c] (str/split (str/trim (slurp ready)) #" ")]
                   (vswap! started conj h c)
                   {:holder h :cmd c :done done})))
        end (fn [{:keys [holder done]}] (sh (str (seon.config/checkout) "/bin/held") "--end" "TERM" holder done "1"))]
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
        (is (= 2 (:exit (sh (str (seon.config/checkout) "/bin/held") "--end" "TERM" "1" f "1"))) "a path that is no fifo is refused at once"))
      ;; the holders this test started end with it, by their own pids, whatever an assertion threw
      (finally (doseq [p @started] (sh "kill" "-9" p)) (sh "rm" "-rf" dir)))))

;; ---- task seon.check/health-is-what-a-branch-caused ------------------------------------------

(deftest a-failed-seed-says-failed
  ;; a single mode's seed that fails (its store a regular file, which no store opens) prints why
  ;; and the mode's verdict line; before, `run seed 2000 > /dev/null || exit 1` exited 1 with nothing
  (let [dir (seon.config/checkout)
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
  (let [dir (seon.config/checkout)
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

(deftest the-health-step-reads-the-task-files-relative-to-the-checkout
  ;; test-runner/health!: the config holds :seon.config.root/tasks-dir resolved against the
  ;; checkout, and the base's task files are read from the base's tree extracted elsewhere, so
  ;; the step takes the directory relative to the checkout (an absolute one read no base task)
  (let [rel (#'test-runner/tasks-dir)]
    (is (not (str/starts-with? rel "/")) rel)
    (is (= (str (.getCanonicalFile (java.io.File. (str (seon.config/checkout) "/" rel))))
           (str (.getCanonicalFile (java.io.File. (str (seon.config/value :seon.config.root/tasks-dir))))))
        "it names the configured directory under the checkout")))

(deftest a-problem-the-base-had-is-not-the-branchs
  (let [[base] (cited-db)
        h (test-runner/branch-health base base nil)]
    (is (pos? (get-in (seon.problems/health base) [:classes :dead-citation :n])) "the base has an open problem")
    (is (= [] (:caused h)) "the branch changed nothing, so it caused none")
    (is (every? true? (vals (:checks h))) "and its health is green")))

(deftest a-problem-the-branch-made-fails-it
  (let [[base v] (cited-db)
        head (d/db-with base [[:db/retractEntity (pdb/eid base :var/name v)]])
        h (test-runner/branch-health base head nil)]
    (is (= [{:class :dead-citation :message (str "demo/p.md#p cites " v ", which names nothing")}] (:caused h)))
    (is (false? (get-in h [:checks :no-dead-citation])) "its health is red on the class it made")
    (testing "the classes asked for are the ones judged (bin/laws catch asks for :unnamed-site)"
      (is (= {:no-unnamed-site true} (:checks (test-runner/branch-health base head [:unnamed-site])))))
    (testing "a class no verdict judges (an attention class never fails a branch) is refused, never a green"
      (is (thrown? Exception (test-runner/branch-health base head [:stale-section]))))))

(deftest a-class-the-health-step-does-not-read-is-refused
  (let [[base v] (cited-db)
        head (d/db-with base [[:db/retractEntity (pdb/eid base :var/name v)]])]
    (doseq [c [:orphan-page :stalled :stale-pin :leadless-section :uncited-task :problem-unlinked]]
      (is (thrown? Exception (test-runner/branch-health base head [c])) (str (name c) " is no verdict of caused-split")))))

(deftest the-health-steps-checks-are-the-classes-caused-reads
  (let [[base v] (cited-db)
        attention? (:seon.problems/attention (contract/properties :seon.problems/class))]
    (is (= (into #{} (comp (remove attention?) (map #(keyword (str "no-" (name %))))) seon.problems/caused-classes)
           (set (keys (:checks (test-runner/branch-health base base nil))))))
    (let [head (d/db-with base [[:db/retractEntity (pdb/eid base :var/name v)]])]
      (is (every? seon.problems/caused-classes (map :class (:rows (seon.problems/caused-split base head))))
          "every class caused-split answers is one the health step judges"))))

(deftest the-base-is-the-merge-base-or-the-previous-main
  ;; seon.fx/base-commit in a repository of its own: a branch is held to its merge base with main;
  ;; main's own commit to the previous main when clean, to itself when changed; a branch with no
  ;; commits of its own to its own start; main's first commit has none
  (let [dir (str (seon.config/checkout) "/target/runs/check-test-base-" (random-uuid))
        git (fn [& args] (str/trim (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t" args))))
        commit! (fn [f] (spit (str dir "/" f) f) (git "add" "-A") (git "commit" "-qm" f) (git "rev-parse" "HEAD"))]
    (try
      (.mkdirs (java.io.File. dir))
      (git "init" "-q" "-b" "main")
      (is (nil? (seon.fx/base-commit dir)) "a repository with no commit has no base")
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

(deftest the-tree-id-reads-what-changed-and-is-the-tree-hashed-whole
  ;; task seon.fx/inputs-hash-only-what-changed: tree-id starts from the checkout's own index, whose stat cache
  ;; skips every file that did not move (0.2 s against 1.1 s on this repository), and answers the tree a fresh index
  ;; of HEAD plus `add -A` would: for a clean tree, an edit, a new file, a deleted file, a staged and an ignored one;
  ;; the repository's index is not touched
  (let [dir (str (seon.config/checkout) "/target/runs/tree-id-test-" (random-uuid))
        git (fn [& args] (str/trim (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t" args))))
        write! (fn [f t] (.mkdirs (.getParentFile (java.io.File. (str dir "/" f)))) (spit (str dir "/" f) t))
        fresh (fn [] (let [idx (str dir "/.git/ref-index") run #(apply clojure.java.shell/sh "env" (str "GIT_INDEX_FILE=" idx) "git" "-C" dir %&)]
                       (try (run "read-tree" "HEAD") (run "add" "-A") (run "rm" "-r" "--cached" "-q" "--ignore-unmatch" "--" ":(top)claude_scratch")
                            (str/trim (:out (run "write-tree")))
                            (finally (.delete (java.io.File. idx))))))
        index-bytes (fn [] (vec (java.nio.file.Files/readAllBytes (.toPath (java.io.File. (str dir "/.git/index"))))))]
    (try
      (.mkdirs (java.io.File. dir))
      (git "init" "-q" "-b" "main")
      (write! ".gitignore" "ignored/\n") (write! "src/a.clj" "(ns a)") (write! "src/b.clj" "(ns b)") (write! "claude_scratch/n.md" "n")
      (git "add" "-A") (git "commit" "-qm" "first")
      (let [idx0 (index-bytes)]
        (is (= (fresh) (seon.fx/tree-id dir)) "clean")
        (write! "src/a.clj" "(ns a) ;; edited with a longer text")
        (is (= (fresh) (seon.fx/tree-id dir)) "an edit")
        (write! "src/c.clj" "(ns c)")
        (is (= (fresh) (seon.fx/tree-id dir)) "a new file")
        (.delete (java.io.File. (str dir "/src/b.clj")))
        (is (= (fresh) (seon.fx/tree-id dir)) "a deleted file")
        (write! "ignored/x" "x") (write! "claude_scratch/m.md" "m")
        (is (= (fresh) (seon.fx/tree-id dir)) "an ignored file and a note move nothing")
        (git "add" "src/c.clj") (write! "src/c.clj" "(ns c) ;; staged, then changed again")
        (is (= (fresh) (seon.fx/tree-id dir)) "a staged file changed again: the file on disk")
        (git "reset" "-q" "--mixed")
        (testing "index flags and index-only state read HEAD's tree afresh, never the copy's"
          (git "add" "-A") (git "commit" "-qm" "second")
          (git "update-index" "--assume-unchanged" "src/a.clj") (write! "src/a.clj" "(ns a) ;; changed behind assume-unchanged")
          (is (= (fresh) (seon.fx/tree-id dir)) "an edit of an assume-unchanged file")
          (git "update-index" "--no-assume-unchanged" "src/a.clj")
          (git "update-index" "--skip-worktree" "src/c.clj") (.delete (java.io.File. (str dir "/src/c.clj")))
          (is (= (fresh) (seon.fx/tree-id dir)) "a deleted skip-worktree file")
          (git "update-index" "--no-skip-worktree" "src/c.clj")
          (git "add" "-f" "ignored/x")
          (is (= (fresh) (seon.fx/tree-id dir)) "a forced-added ignored file"))
        (is (not= idx0 (index-bytes)) "(the test's own git commands moved the index)")
        (let [before (index-bytes)] (seon.fx/tree-id dir) (is (= before (index-bytes)) "tree-id leaves the repository's index as it was")))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest a-commit-to-notes-moves-no-tree-id-and-one-to-src-does
  ;; task seon.fx/the-tree-id-is-what-is-read: claude_scratch/ is no input (every merge appends its log line
  ;; there), so a notes-only commit moves no digest and rebuilds nothing; a source commit does
  (let [dir (str (seon.config/checkout) "/target/runs/check-test-tree-" (random-uuid))
        git (fn [& args] (str/trim (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t" args))))
        write! (fn [f] (.mkdirs (.getParentFile (java.io.File. (str dir "/" f)))) (spit (str dir "/" f) (str f (random-uuid))))
        commit! (fn [f] (write! f) (git "add" "-A") (git "commit" "-qm" f))]
    (try
      (.mkdirs (java.io.File. dir))
      (git "init" "-q" "-b" "main")
      (commit! "src/a.clj")
      (let [t0 (seon.fx/tree-id dir)]
        (write! "claude_scratch/merge-log.md")
        (is (= t0 (seon.fx/tree-id dir)) "an untracked note")
        (commit! "claude_scratch/merge-log.md")
        (is (= t0 (seon.fx/tree-id dir)) "a committed note")
        (commit! "docs/x.md")
        (is (not= t0 (seon.fx/tree-id dir)) "a doc is in the tree")
        (write! "src/a.clj")
        (is (not= (seon.fx/tree-id dir) (do (commit! "src/a.clj") (seon.fx/tree-id dir))) "and so is a source file")
        (is (not= (seon.fx/tree-id dir []) (seon.fx/tree-id dir)) "with no exclusions the note is in it")
        (testing "bin/tree reads the same exclusions from the shipped config (its sed against cfg/value)"
          (let [me (seon.config/checkout)]
            (.mkdirs (java.io.File. (str dir "/bin"))) (.mkdirs (java.io.File. (str dir "/resources/seon/config")))
            (clojure.java.shell/sh "cp" (str me "/bin/tree") (str dir "/bin/tree"))
            (clojure.java.shell/sh "cp" (str me "/resources/seon/config/default.edn") (str dir "/resources/seon/config/default.edn"))
            (git "add" "-A") (git "commit" "-qm" "bin and config")
            (let [same (fn [] (= (seon.fx/tree-id dir) (str/trim (:out (clojure.java.shell/sh (str dir "/bin/tree") dir)))))]
              (is (same) "agree")
              (write! "claude_scratch/more.md")
              (is (same) "and agree with a note written")))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest bin-tree-names-the-tree-tree-id-names
  ;; bin/check compares the binary's record (bin/build writes it through bin/tree) with bin/tree;
  ;; root decides whether its source's inputs are its binary's with seon.fx/tree-id: one tree
  (let [dir (seon.config/checkout)]
    (is (= (seon.fx/tree-id dir) (str/trim (:out (clojure.java.shell/sh (str dir "/bin/tree") dir)))))))

(deftest a-stalled-test-is-named-and-the-run-goes-on
  ;; root, 2026-10-26 (the owner: "the hangs are super bad especially if you don't notice"): a test
  ;; quiet past its own bound is named, never ended (bin/stalls, which bin/test runs beside its
  ;; shards); its bound is its line in the plan's stall bounds (k times its own last run), the floor
  ;; otherwise. Two shards' logs each say a test started, and each shard waits on a FIFO the test
  ;; releases only once one is named: the test whose bound is 200 ms is named, once; the one whose
  ;; bound is 600 s is not (a healthy slow test), nor is one whose test ended (the shard quiet after
  ;; it); both go on and end, and the watcher with them
  (let [root (seon.config/checkout)
        d (str root "/target/stalls-test-" (random-uuid))
        _ (.mkdirs (java.io.File. d))
        script (str "cd " d "; printf 'started seon.x-test/hangs\\n' > a.log; printf 'started seon.x-test/slow\\n' > b.log; printf 'started seon.x-test/done\\nended seon.x-test/done\\n' > c.log\n"
                    "printf 'seon.x-test/hangs 200\\nseon.x-test/slow 600000\\nseon.x-test/done 200\\n' > bounds; mkfifo ra rb out\n"
                    "(read x < ra) & pa=$!; (read x < rb) & pb=$!\n"
                    root "/bin/stalls 100000 bounds a.log:$pa b.log:$pb c.log:$pb > out & s=$!\n"
                    "line=$(head -1 out)\n"
                    "echo go > ra; echo go > rb; wait $s; echo \"exit $?\"; echo \"$line\"; grep -c STALLED a.log; grep -c STALLED b.log; grep -c STALLED c.log\n")
        [ex line na nb nc] (str/split-lines (:out (clojure.java.shell/sh "zsh" "-c" script)))]
    (is (= "exit 0" ex) "the watcher ends when the shards do: the run goes on")
    (is (re-find #"^STALLED seon.x-test/hangs \d+ ms \(bound 200 ms, load [\d.]+," (str line)) "the quiet test past its own bound is named, with its quiet ms and the load it was quiet under")
    (is (= ["1" "0" "0"] [na nb nc]) "recorded once in its shard's log; the slow test within its bound, and a test that ended, are not named")))

(deftest a-merges-verdict-is-its-exit-statuses-and-it-writes-its-line
  ;; bin/prove --merge's verdict comes from prove's exit status alone (contracts and smoke are steps of
  ;; its run: task seon.proofs/the-merge-runs-no-step-twice), names each red from its log, and prints
  ;; its line for the merge commit's message (so a line cannot say green over a red exit; git keeps
  ;; every merge's numbers, docs/merging.md); bin/prove --merge-verdict runs that part alone
  (let [dir (str (.getCanonicalPath (java.io.File. (seon.config/checkout) "target/tmp")) "/merge-verdict-" (random-uuid))
        run (fn [st] (clojure.java.shell/sh (str (seon.config/checkout) "/bin/prove") "--merge-verdict" dir (str st)))
        line-of (fn [r] (first (filter #(str/includes? % " bin/prove --merge ") (str/split-lines (:out r)))))]
    (.mkdirs (java.io.File. dir))
    (spit (str dir "/prove.log") (str "prove: 12 keys (10 tests, 2 check steps), 4 green at their digest (reused), 8 tests to run in 2 shards, 0 steps to run; selected in 5 ms\n"
                                      "RED seon.x-test/a-red {:pass 1, :fail 1, :error 0}\nPROVE FAILED\n"))
    (try
      (let [r (run 1) line (line-of r)]
        (is (= 1 (:exit r)) (:out r))
        (is (str/ends-with? (str/trim (:out r)) "MERGE FAILED"))
        (is (str/includes? (str line) "MERGE FAILED; prove FAILED (exit 1)") (:out r))
        (is (str/includes? (str line) "reds: RED seon.x-test/a-red") (:out r))
        (is (re-find #"main's last line: (\d+|none recorded)\)$" (str line)) "the left-out count of main's last merge message, or none recorded"))
      (let [r (run 0) line (line-of r)]
        (is (zero? (:exit r)))
        (is (str/includes? (str line) "MERGE OK") (:out r)))
      (is (not (.exists (java.io.File. (str dir "/merge-log.md")))) "nothing is written beside the run")
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

