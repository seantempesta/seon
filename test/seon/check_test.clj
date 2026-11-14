(ns seon.check-test
  "bin/check's store (owner, 2026-10-21: one store, many branches, as in production): the run
  seeds one store (seon.check/seed); every step's root opens it on a main of its own, a
  branch of the seed (seon.db/open!, as seon.system/start! does), names its agents' branches
  under that main, and a recovery step reopens the same store on the same main."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [clojure.core.async :as a] [seon.check] [test-runner] [seon.doc] [seon.problems] [seon.fx] [seon.source] [clojure.string] [clojure.string :as str] [seon.lib] [seon.contract :as contract] [clojure.set]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest ^{:seon.test/estimate {:ticks 3000000}} what-a-held-command-left-running-ends-with-it
  ;; deepseek-run4, 2026-11-07: bin/system stop left about 26 of a gate's test processes running. CMD's group ends at CMD's end, so a
  ;; --drain holder is answered then, not when they would have finished. A token names the process this test started
  (let [token (str "held-left-" (random-uuid))
        r (clojure.java.shell/sh (str (seon.config/checkout) "/bin/held") "--drain" "/bin/bash" "-c"
                                 (str "(exec -a " token " /bin/sleep 300) & exit 0"))
        left (clojure.java.shell/sh "/bin/sh" "-c" (str "ps -axo command | grep -c '^" token "'"))]
    (is (= 0 (:exit r)) (pr-str r))
    (is (= "0" (clojure.string/trim (:out left))) "nothing it left is running")))

;; docs/clocks.md#time-decides-the-owners-exceptions: a step that runs past its bound (the plan's step-bounds) is ended after its
;; evidence is captured, and is red with its typed reason, never run again by the run. laws at a bound of 1 ms: ended at once
;; docs/checks.md#modes: a check that ran nothing is no green (AGENTS.md §9): a selection with one step name a line runs each
;; (2026-11-10: a plan written one a line ran none and said CHECK OK), and a name no step has is red, never passed over.
;; Each step under a 1 ms bound, so the run is seconds; what it shows is which steps it ran
(deftest ^{:seon.test/execs ["bin/check"] :seon.test/estimate {:ticks 2000000}} a-selection-runs-every-step-it-names-and-refuses-one-it-cannot
  ;; T3 (2026-11-12): bin/check's selection is the subject; one run holds the three claims (3 runs, 6.7 s, were one each): the
  ;; selection's first line names two steps and one no step has, its second a step a line, its third a step that needs the seeded
  ;; store in a selection whose steps-binary says none does (2026-11-11: a hand-written one ran a step on an unseeded store, and it
  ;; failed two phases later). Work: one bin/check start, 4 steps judged, 2 under the 1 ms bound. Each step under it, so the run is seconds
  (let [sel (str (System/getProperty "java.io.tmpdir") "/check-sel-" (random-uuid))
        _ (clojure.java.shell/sh "mkdir" "-p" sel)
        _ (doseq [[f t] {"steps" "laws no-such-step\nlmdb-lock\nsmoke\n" "steps-binary" "" "step-bounds" "laws 1\nlmdb-lock 1\nsmoke 60000\n"}] (spit (str sel "/" f) t))
        r (clojure.java.shell/sh (str (seon.config/checkout) "/bin/check") "--steps" sel)
        out (second (re-find #"(?m)^outputs: (\S+)" (:out r)))
        _ (clojure.java.shell/sh "rm" "-rf" sel)
        _ (some->> out (clojure.java.shell/sh "rm" "-rf"))]
    ;; a step's header is printed only when the selection holds it (bin/check's unit)
    (is (re-find #"(?m)^== laws " (:out r)) (:out r))
    (is (re-find #"(?m)^== lmdb-lock " (:out r)) "every name, one a line or several, ran")
    (is (= 1 (:exit r)) (:out r))
    (is (re-find #"FAILED no-such-step: no step of that name" (:out r)) "a name no step has is red, named")
    (is (re-find #"smoke runs target/seon on the seeded store, but this selection's steps-binary" (:out r))
        "a step that needs the seeded store is refused by name, never run on none")))

;; docs/clocks.md#time-decides-the-owners-exceptions: a step past its bound is ended after its evidence and FAILS, as a test past
;; its bound is an error (the owner, 2026-11-12: "A test killed for running too long is a bug and it should be recorded as an
;; error and fixed"): named with its CPU seconds and the load, no green stored. Red before: it was a signal and nothing failed
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-step-past-its-bound-is-ended-with-its-evidence-and-fails
  (let [sel (str (System/getProperty "java.io.tmpdir") "/step-bound-" (random-uuid))
        _ (clojure.java.shell/sh "mkdir" "-p" sel)
        _ (doseq [[f t] {"steps" "laws\n" "steps-binary" "" "step-bounds" "laws 1\n"}] (spit (str sel "/" f) t))
        r (clojure.java.shell/sh (str (seon.config/checkout) "/bin/check") "--steps" sel)
        ev (second (re-find #"evidence in (\S+)" (:out r)))
        out (second (re-find #"(?m)^outputs: (\S+)" (:out r)))
        ms (slurp (str sel "/step-ms"))
        green (let [f (java.io.File. (str sel "/green"))] (if (.isFile f) (slurp f) ""))]
    (clojure.java.shell/sh "rm" "-rf" sel)
    (is (= 1 (:exit r)) (pr-str r))
    (is (re-find #"(?m)^FAILED laws: STEP-OVERRAN, ran past its bound of 1 ms \(.*CPU [\d.?]+ s, load [\d.]+\)" (:out r)) (:out r))
    (is (re-find #"(?m)^CHECK FAILED: 1 step" (:out r)) "the run failed, naming it")
    (is (not (re-find #"check-laws" green)) "it stored no green: it runs again")
    (is (and ev (.isFile (java.io.File. (str ev "/ps.txt")))) "its process tree was captured before it was ended")
    (is (not (re-find #"(?m)^laws OK" (:out r))) "and it did not run on to its own end")
    (is (re-find #"^laws \d+\n$" ms) "its time is kept for its verdict, as a green step's is")
    ;; a unit's root runs in a runs directory of its own (SEON_RUNS beside its store), never the checkout's runs/, which a
    ;; system running in the checkout owns: a failed unit keeps it with its store, to be read
    (is (and out (.isDirectory (java.io.File. (str out "/stores/laws-runs")))) "the unit's own runs directory, kept with its failed store")
    (some->> out (clojure.java.shell/sh "rm" "-rf"))))

(deftest ^{:seon.test/estimate {:ticks 20000000}} steps-are-branches-of-one-store
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
        (d/transact (:conn a) [{:note/text "smoke's" :note/agent :t}])
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
        (seon.lib/remove-tree! path)))))

(defn- entries "The names in directory p (none when it does not exist)." [p]
  (set (map #(.getName %) (or (.listFiles (java.io.File. p)) []))))

(deftest ^{:seon.test/estimate {:ticks 30000000}} a-check-leaves-its-store-as-it-found-it
  ;; task seon.check/a-run-takes-a-branch-and-deletes-it: a check step run against a store
  ;; works on a branch of it (its main, check-<step>) and deletes what it made when it is
  ;; green; a test's branch of the test store is deleted by its fixture (seon.test-store/test-branch!);
  ;; neither makes a store. The store is this test's own (the last resort: what is proved is what a run leaves in a store),
  ;; and the step is what its root does to it (seon.db/open! on its main, an agent's branch under it, a write), then
  ;; bin/check's drop (seon.check/drop-mains: seon.db/delete-main!), with no root booted; removed at the end.
  ;; Estimate: a store made (its schema installed, 300 datoms seeded), two opens, a branch, a write, a delete of two branches,
  ;; two test-branch! fixtures: ~25M ticks (a first 10M was under: measured 23M, the store's make not yet split out)
  (let [path (str (seon.config/checkout) "/target/runs/check-test-given-" (random-uuid))
        _ (d/release (:conn (pdb/open! :lmdb path 300 :db)))
        branches #(let [c (pdb/connect (pdb/store-cfg :lmdb path))] (try (d/branches c) (finally (d/release c))))
        before (branches)]
    (try
      (let [{:keys [conn]} (pdb/open! :lmdb path 300 :check-config)]
        (d/branch! conn :check-config (pdb/on-main :check-config :agent-1))
        (d/transact conn [{:note/text "the step's" :note/agent :t}])
        (d/release conn))
      (is (= #{:check-config :check-config/agent-1} (clojure.set/difference (branches) before)) "the step's main and its agent's branch")
      (let [c (pdb/connect (pdb/store-cfg :lmdb path))]
        (try (is (= #{:check-config :check-config/agent-1} (set (pdb/delete-main! c :check-config))) "the drop deletes both")
             (finally (d/release c))))
      (is (= before (branches)) "the store's branch set holds nothing the step made")
      (testing "a test's branch of the test store is deleted when the test ends"
        (let [seen (fn [cfg conn] [(:branch cfg) (d/branches conn) (count (d/datoms @conn :eavt))])
              [b1 in1 datoms] (ts/test-branch! seen)
              [b2 in2] (ts/test-branch! seen)]
          (is (pos? datoms) "the fixture hands the test a branch holding the seed")
          (is (in1 b1) "the branch exists while the test runs")
          (is (not (in2 b1)) "a later test sees no branch the earlier one made")
          (is (= (disj in1 b1) (disj in2 b2)) "and nothing else changed")))
      (finally (seon.lib/remove-tree! path (str path "-agents"))))))

(deftest ^{:seon.test/estimate {:ticks 200000}} the-probe-says-what-it-took
  ;; the system check awaits the probe's
  ;; own event for its third message, not a window
  (let [ev (a/chan 4)
        st (#'seon.check/probe-proc {:tag "one" :events ev})
        [st o1] (#'seon.check/probe-proc st :in 1)
        [_ o2] (#'seon.check/probe-proc st :in 2)]
    (is (= {:events ev} (:clojure.core.async.flow/out-ports st)))
    (is (= [[{:type :probe :tag "one" :got 1}] [{:type :probe :tag "one" :got 2}]]
           (map #(mapv (fn [e] (dissoc e :at-ms)) (:events %)) [o1 o2])))))

(deftest ^{:seon.test/estimate {:ticks 5000000} :seon.test/execs ["git"]} every-test-root-starts-through-start-root!
  ;; read from the program of test/ (the
  ;; compiler's references, not a text search over files), no test var but the helper's one
  ;; launcher runs bin/system or `./seon root` through binary-sh, so every test root names its
  ;; store and main and waits on its listening line
  ;; read from the program the run already holds (runner/program: src and test as facts, each form's :form/refs), never by
  ;; importing the files again: re-importing every file naming binary-sh was 387M ticks of the test's own work
  (let [db (test-runner/program)
        files (d/q '[:find [?file ...] :where [?f :form/refs seon.test-store/binary-sh] [?f :form/ns ?ns] [?ns :ns/file ?file]] db)
        ;; the selective clause first (the forms that name binary-sh, an index seek), then their vars: begun at every var, the
        ;; join was most of the test's work
        launchers (set (for [[v src] (d/q '[:find ?v ?s :where [?f :form/refs seon.test-store/binary-sh] [?e :var/form ?f]
                                             [?e :var/name ?v] [?f :form/src ?s]] db)
                             ;; binary-sh is the primitive the one launcher calls; this test names both in its query
                             :when (not (#{'seon.test-store/binary-sh 'seon.check-test/every-test-root-starts-through-start-root!} v))
                             ;; the launch is a literal argument ("bin/system", or "./seon" "root"), never text that
                             ;; names the script (a test that writes a config file mentioning bin/system)
                             :when (some #(clojure.string/includes? src %) ["\"bin/system\"" "\"./seon\" \"root\""])]
                         v))]
    (is (seq files))
    (is (= #{'seon.test-store/root-sh} launchers))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-starter-that-is-gone-leaves-no-fifo-after-the-next-starter
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
      (finally (seon.lib/remove-tree! dir)))))

(deftest ^{:seon.test/estimate {:ticks 15000000}} a-stop-ends-on-its-holders-end-and-a-gone-holder-answers-at-once
  ;; the first bin/update hung for 10 minutes in `bin/system stop`: it read a fifo it held open
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
      (finally (doseq [p @started] (sh "kill" "-9" p)) (seon.lib/remove-tree! dir)))))

;; ---- task seon.check/health-is-what-a-branch-caused ------------------------------------------

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-failed-seed-says-failed
  ;; a single mode's seed that fails (its store a regular file, which no store opens) prints why
  ;; and the mode's verdict line; before, `run seed 2000 > /dev/null || exit 1` exited 1 with nothing.
  ;; A store SEON_STORE names is the caller's (docs/checks.md#modes): keying the checks' own store once
  ;; removed it and seeded over it, CONFIG OK
  (let [dir (seon.config/checkout)
        path (str dir "/target/runs/check-test-not-a-store-" (random-uuid))
        _ (spit path "not a store")
        run (ts/binary-sh "env" (str "SEON_STORE=" path) "SEON_WEB_PORT=0" "bin/check" "config" :dir dir)
        lines (str/split-lines (str (:out run) (:err run)))]
    (try
      (is (not= 0 (:exit run)))
      (is (= "CONFIG FAILED (seed)" (last lines)) (:out run))
      (is (< 1 (count lines)) "the seed's own output says why")
      (is (= "not a store" (slurp path)) "and the store the caller named is never removed or seeded over")
      (finally (seon.lib/remove-tree! path)))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-stale-binary-is-refused
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
      (finally (seon.lib/remove-tree! tmp)))))

(defn- catch-db
  "The test store's seed with n live forms in a src namespace, each defining one var and carrying a catch the law page
  does not name (each an :unnamed-site), as seon.problems-test's catch-forms; and the n-th var's name."
  [db0 n]
  (let [ns-e (:db/id (:var/ns (d/entity db0 (:e (first (d/datoms db0 :aevt :var/name))))))]
    [(d/db-with db0 (into [[:db/add ns-e :ns/file "src/app/m.clj"]]
                          (mapcat (fn [i] [{:db/id (- (inc (* 2 i))) :form/id (str "check-form-" i) :form/ns ns-e :form/order i :form/src "(defn f [])"
                                            :form/catches #{"{:class Exception, :answers :expr}"} :form/defines [(- (* 2 i))]}
                                           {:db/id (- (* 2 i)) :var/name (symbol "app.check" (str "v" i)) :var/ns ns-e :var/form (- (inc (* 2 i)))}]))
                          (range 1 (inc n))))
     (symbol "app.check" (str "v" n))]))

(deftest ^{:seon.test/estimate {:ticks 300000}} the-health-step-reads-the-task-files-relative-to-the-checkout
  ;; test-runner/health!: the base's task files are read from the base's tree extracted elsewhere, so the step takes where the
  ;; tree keeps them, relative to it (an absolute one read no base task), and from the tree's layout
  ;; (:seon.config.source/tasks-dir), never this process's own task dir: bin/check points that at its run's own (SEON_TASKS), and
  ;; health read it as the tree's ("pathspec 'target/check/tasks' did not match", 2026-11-10)
  (let [rel (#'test-runner/tasks-dir)]
    (is (not (str/starts-with? rel "/")) rel)
    (is (.isDirectory (java.io.File. (str (seon.config/checkout) "/" rel))) "it names the tree's task files under the checkout")
    (is (= rel (seon.config/value :seon.config.source/tasks-dir)) "the tree's layout, not the process's place")))

;; docs/health.md#checks-are-callers-of-seon: the base's program is built by the base's own code in a process of its own, never in the
;; image that holds the head's. Analysis evaluates each file's ns form in the live namespace, so a base whose ns form aliases a
;; namespace this image has loaded under another target (across the rename of seon.system to seon.graph, seon.server's `c` names
;; one in each tree) is a retargeted alias, which Jolt refuses. This image has loaded seon.check-test, `contract` naming
;; seon.contract; a clone of this checkout's HEAD gets a base commit where that alias names seon.error, and a head restoring it
(deftest ^{:seon.test/estimate {:ticks 100000000}} the-health-step-reads-a-base-whose-alias-the-head-retargets
  (let [co (str (seon.config/checkout))
        dir (str co "/target/tmp/health-rename-" (random-uuid))
        repo (str dir "/repo")
        store (ts/kept-program-store)
        file "test/seon/check_test.clj"
        sh! (fn [& args] (let [r (apply clojure.java.shell/sh args)] (is (zero? (:exit r)) (pr-str args r)) (str/trim (:out r))))
        commit! (fn [msg] (sh! "git" "-C" repo "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-a" "-m" msg)
                  (sh! "git" "-C" repo "rev-parse" "HEAD"))
;; the ns form's alias only: this test's own body holds the retargeted text as a string, which another form's
        ;; source would match
        retargeted? (fn [db] (boolean (some #(str/includes? % "[seon.error :as contract]")
                                            (d/q '[:find [?s ...] :in $ ?n :where [?ns :ns/name ?n] [?ns :ns/form ?f] [?f :form/src ?s]] db 'seon.check-test))))]
    (try
      (sh! "git" "clone" "-q" "--shared" co repo)
      ;; as a shard's earlier test does on a clean checkout: this process holds HEAD's tree from the kept store, so the test reads
      ;; that store itself, never a copy: a copy keeps the store's id, and one branch under two paths is refused by Datahike
      ;; (\"Configuration does not match existing connections\", batches 58 and 60, here and in every later test of the process)
      (test-runner/program-db repo (ts/kept-program-store))
      (let [text (slurp (str repo "/" file))
            _ (is (str/includes? text "[seon.contract :as contract]"))
            _ (spit (str repo "/" file) (str/replace text "[seon.contract :as contract]" "[seon.error :as contract]"))
            base (commit! "base")]
        (spit (str repo "/" file) text)
        (commit! "head")
        (let [head (test-runner/program-db repo store)
              based (test-runner/base-program repo base store (str dir "/base-store") (str dir "/base"))]
          (is (not (contract/error? based)) (pr-str based))
          (is (not (retargeted? head)) "the head's alias is this image's")
          (is (retargeted? based) "the base holds its own")
          (is (= 'seon.contract (some-> (get (ns-aliases 'seon.check-test) 'contract) ns-name))
              "and this image's namespace keeps its own alias: the base's ns form never ran here")
          (is (not (.exists (java.io.File. (str dir "/base")))) "the base's checkout is removed once its runner ran")
          (is (retargeted? (test-runner/base-program repo base store (str dir "/base-store") (str dir "/base2")))
              "a base the base store keeps is read again")
          (is (not (.exists (java.io.File. (str dir "/base2")))) "with no checkout made")))
      (finally (test-runner/remove-checkout! (str dir "/base")) (seon.lib/remove-tree! dir)))))

;; docs/references.md#task-references: a task's citation of nothing is refused at its write (owner, 2026-11-09); the health step loads
;; the tree's tasks through that one checked write, so a task citing a key no program holds is health's typed refusal, never a lookup
;; Datahike throws (batch 15: a plan citing :db/contentId in one backtick threw out of health before any row)
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-task-citing-nothing-is-the-health-steps-typed-refusal
  (let [dir (str (System/getProperty "java.io.tmpdir") "/health-cite-" (random-uuid))
        tasks (str dir "/" (#'test-runner/tasks-dir))
        _ (clojure.java.shell/sh "mkdir" "-p" tasks (str dir "/docs"))
        _ (spit (str tasks "/zz.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Read `:no.such/key` first." :seon.task/kind :work :seon.task/proofs #{}}]))
        r (#'test-runner/with-wiki (ts/test-db) dir)]
    (clojure.java.shell/sh "rm" "-rf" dir)
    (is (seon.contract/error? r) "an answer, not a throw")
    (is (= {:seon.error/type :seon.db/no-target :target [:schema/key :no.such/key] :by "zz/t"} (select-keys r [:seon.error/type :target :by])) (pr-str r))
    (is (str/includes? (str (:seon.error/message r)) "two backticks") "its message says what to write for a name that is no citation")))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-problem-the-base-had-is-not-the-branchs
  (let [[base] (catch-db (ts/test-db) 1)
        h (test-runner/branch-health base base nil)]
    (is (pos? (get-in (seon.problems/health base) [:classes :unnamed-site :n])) "the base has an open problem")
    (is (= [] (:caused h)) "the branch changed nothing, so it caused none")
    (is (every? true? (vals (:checks h))) "and its health is green")))

(deftest ^{:seon.test/estimate {:ticks 8000000}} a-problem-the-branch-made-fails-it
  (let [[base] (catch-db (ts/test-db) 1)
        [head v] (catch-db base 2)
        h (test-runner/branch-health base head nil)]
    (is (= [:unnamed-site] (mapv :class (:caused h))))
    (is (str/includes? (:message (first (:caused h))) (str v)) "naming what the branch made")
    (is (false? (get-in h [:checks :no-unnamed-site])) "its health is red on the class it made")
    (testing "the classes asked for are the ones judged (bin/laws catch asks for :unnamed-site): one the branch did not make is green"
      (is (= {:no-unnamed-site true} (:checks (test-runner/branch-health base base [:unnamed-site])))))
    (testing "a class no verdict judges (an attention class never fails a branch) is refused, never a green"
      (is (thrown? Exception (test-runner/branch-health base head [:stale-section]))))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-class-the-health-step-does-not-read-is-refused
  (let [[base] (catch-db (ts/test-db) 1)
        [head] (catch-db base 2)]
    ;; the running system's state is no branch's content: no verdict of caused-split judges it
    (doseq [c [:stalled :stale-pin :stale-binary :unsynced :problem-unlinked]]
      (is (thrown? Exception (test-runner/branch-health base head [c])) (str (name c) " is no verdict of caused-split")))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} the-health-steps-checks-are-the-classes-caused-reads
  (let [[base] (catch-db (ts/test-db) 1)
        attention? (:seon.problems/attention (contract/properties :seon.problems/class))]
    (is (= (into #{} (comp (remove attention?) (map #(keyword (str "no-" (name %))))) seon.problems/caused-classes)
           (set (keys (:checks (test-runner/branch-health base base nil))))))
    (let [[head] (catch-db base 2)]
      (is (every? seon.problems/caused-classes (map :class (:rows (seon.problems/caused-split base head))))
          "every class caused-split answers is one the health step judges"))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} the-base-is-the-merge-base-or-the-previous-main
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
      (finally (seon.lib/remove-tree! dir)))))

(deftest ^{:seon.test/estimate {:ticks 100000000}} the-tree-id-reads-what-changed-and-is-the-tree-hashed-whole
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
      (finally (seon.lib/remove-tree! dir)))))

(deftest ^{:seon.test/estimate {:ticks 100000000}} a-commit-to-notes-moves-no-tree-id-and-one-to-src-does
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
        (let [before (seon.fx/tree-id dir) after (do (commit! "src/a.clj") (seon.fx/tree-id dir))]
          (is (not= before after) "and so is a source file")
          ;; each tree-id is about seven git children: the tree just read is not read again
          (is (not= (seon.fx/tree-id dir []) after) "with no exclusions the note is in it"))
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
      (finally (seon.lib/remove-tree! dir)))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} bin-tree-names-the-tree-tree-id-names
  ;; bin/check compares the binary's record (bin/build writes it through bin/tree) with bin/tree;
  ;; root decides whether its source's inputs are its binary's with seon.fx/tree-id: one tree
  (let [dir (seon.config/checkout)]
    (is (= (seon.fx/tree-id dir) (str/trim (:out (clojure.java.shell/sh (str dir "/bin/tree") dir)))))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-stalled-test-is-named-an-overran-one-ended-and-the-run-goes-on
  ;; root, 2026-10-26 (the owner: "the hangs are super bad especially if you don't notice"): a test
  ;; quiet past its own stall bound is named, not ended (bin/stalls, which bin/test runs beside its
  ;; shards); its bound is its line in the plan's stall bounds (k times its own last run), the floor
  ;; otherwise. Three shards' logs each say a test started, and two shards wait on a FIFO the test
  ;; releases only once the third is ended: the test whose bound is 200 ms is named, once; the one whose
  ;; bound is 600 s is not (a healthy slow test), nor is one whose test ended (the shard quiet after
  ;; it); both go on and end, and the watcher with them. The logs' quiet is set by their mtime, not
  ;; waited out (bin/stalls reads it in whole seconds): 10 s for the 200 ms bound and 200 s for the 600 s
  ;; one, each on the other side of the 100 s floor from its own bound, so a watcher that reads the floor is red.
  ;; The third shard's test parks forever (a read of a FIFO nobody writes): past its end bound (500 ms, its
  ;; line's third word; the others' is the floor, 100000 s) its evidence is captured, OVERRAN recorded once
  ;; in its log and its shard ended with SIGKILL, the owner's fourth clock exception
  ;; (docs/clocks.md#time-decides-the-owners-exceptions); its running time is read from when the watcher saw it start
  (let [root (seon.config/checkout)
        d (str root "/target/stalls-test-" (random-uuid))
        _ (.mkdirs (java.io.File. d))
        script (str "cd " d "; printf 'started seon.x-test/hangs\\n' > a.log; printf 'started seon.x-test/slow\\n' > b.log; printf 'started seon.x-test/done\\nended seon.x-test/done\\n' > c.log\n"
                    "printf 'started seon.x-test/parks\\n' > d.log\n"
                    "touch -t $(date -v-10S +%Y%m%d%H%M.%S) a.log c.log; touch -t $(date -v-200S +%Y%m%d%H%M.%S) b.log\n"
                    "printf 'seon.x-test/hangs 200\\nseon.x-test/slow 600000\\nseon.x-test/done 200\\nseon.x-test/parks 600000 500\\n' > bounds; mkfifo ra rb rd\n"
                    "(read x < ra) & pa=$!; (read x < rb) & pb=$!; (read x < rd) & pd=$!\n"
                    root "/bin/stalls 100000 100000000 bounds a.log:$pa b.log:$pb c.log:$pb d.log:$pd > out & s=$!\n"
                    "wait $pd; dx=$?\n"
                    "echo go > ra; echo go > rb; wait $s; echo \"exit $?\"; head -1 out; grep -c STALLED a.log; grep -c STALLED b.log; grep -c STALLED c.log\n"
                    "echo \"parked $dx\"; grep -c '^OVERRAN seon.x-test/parks 500 ms' d.log; grep -c STALLED d.log; [[ -s d-overran/ps.txt ]] && echo evidence\n")
        [ex line na nb nc parked no nd ev] (str/split-lines (:out (clojure.java.shell/sh "zsh" "-c" script)))]
    (try
      (is (= "exit 0" ex) "the watcher ends when the shards do: the run goes on")
      (is (re-find #"^STALLED seon.x-test/hangs \d+ ms \(bound 200 ms, load [\d.]+," (str line)) "the quiet test past its own bound is named, with its quiet ms and the load it was quiet under")
      (is (= ["1" "0" "0"] [na nb nc]) "recorded once in its shard's log; the slow test within its bound, and a test that ended, are not named")
      (is (= ["parked 137" "1" "0" "evidence"] [parked no nd ev]) "the parked test past its end bound: its shard ended by SIGKILL, OVERRAN recorded once with its bound, its evidence beside the log")
      (finally (seon.lib/remove-tree! d)))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-merges-verdict-is-its-exit-statuses-and-it-writes-its-line
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
      (finally (seon.lib/remove-tree! dir)))))

;; docs/checks.md#modes: the checks' seeded store is remade past its bound, as nothing in it is kept and its file and roster only grow
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-seeded-store-past-its-bound-is-removed
  (let [dir (str (seon.config/checkout) "/target/tmp/seed-bound-" (random-uuid))]
    (.mkdirs (java.io.File. dir))
    (spit (str dir "/data.mdb") "0123456789")
    (try
      (is (false? (seon.check/remove-store-past! dir 10)) "at the bound it stays")
      (is (.exists (java.io.File. (str dir "/data.mdb"))))
      (is (true? (seon.check/remove-store-past! dir 9)) "past it, it goes")
      (is (not (.exists (java.io.File. dir))))
      (is (false? (seon.check/remove-store-past! dir 9)) "a store not there is nothing to remove")
      (finally (seon.lib/remove-tree! dir)))))


(deftest ^{:seon.test/estimate {:ticks 1000000}} a-feed-that-ends-unsettled-ends-the-wait-that-names-it
  ;; docs/checks.md#modes (nothing hangs: docs/errors.md#nothing-crashes-and-nothing-hangs; a live producer that owes a mark is
  ;; seon.web/a-tabs-first-keyframe-settles-on-its-own): seon.check/idle waits on a feed only its reader thread fills. A stream that
  ;; sends bytes and then ends (or throws on read) without a settled mark answers the wait false, so
  ;; the check's :tab-settled is red, at once; before, the wait had no end. A stream that does settle
  ;; answers true, one that ends before any byte fails the first-bytes wait
  (let [feed-of (fn [^String text] (#'seon.check/watch-feed! (java.io.ByteArrayInputStream. (.getBytes text "UTF-8"))))
        await-feed #'seon.check/await-feed
        unsettled (feed-of "event: datastar-patch-elements\ndata: x\n")
        settled (feed-of "event: datastar-patch-elements\n: settled\n")
        empty-feed (feed-of "")
        throws (#'seon.check/watch-feed! (proxy [java.io.InputStream] [] (read ([] 0) ([b] (throw (java.io.IOException. "reset"))) ([b o l] (throw (java.io.IOException. "reset"))))))]
    (is (true? (await-feed unsettled #(pos? (:bytes %)))) "its first bytes arrived")
    (is (false? (await-feed unsettled :settled)) "ended without a settled mark: the wait answers false, not never")
    (is (= "eof" (:ended @unsettled)) "and says why it ended")
    (is (true? (await-feed settled :settled)))
    (is (false? (await-feed empty-feed #(pos? (:bytes %)))) "ended before its first bytes")
    (is (false? (await-feed throws :settled)))
    (is (= "read threw: reset" (:ended @throws)))))

;; docs/processes.md#a-stop-reaps-every-child: one rule decides what a stop left running, for drain, lifecycle and orphan
;; (seon.check/ran-nothing-more?). Batch 46's lifecycle left {:pid 87561 :ppid 1 :stat "?NE" :command "(seon)"}: an orphan
;; exiting, which a rule counting only zombies read as running
(deftest ^{:seon.test/estimate {:ticks 200000}} a-process-that-can-run-nothing-more-is-not-left-by-a-stop
  (let [self 4242]
    (is (seon.check/ran-nothing-more? {:pid 87561 :ppid 1 :stat "?NE" :command "(seon)"} self) "an orphan exiting")
    (is (seon.check/ran-nothing-more? {:pid 9 :ppid 1 :stat "Z" :command "(seon)"} self) "an orphan zombie")
    (is (not (seon.check/ran-nothing-more? {:pid 9 :ppid 1 :stat "S" :command "seon"} self)) "an orphan still running stays")
    (is (not (seon.check/ran-nothing-more? {:pid 9 :ppid self :stat "Z" :command "(seon)"} self)) "a child the stop failed to reap stays")
    (is (not (seon.check/ran-nothing-more? nil self)) "a pid ps did not find is judged by alive?, not here")))

;; docs/processes.md#a-stop-reaps-every-child: batch 58b's lifecycle left {:pid 74564} with no ps row, under load: kill 0
;; found it, then ps listed it no more, so it had exited between the two reads, and the check counted it left
(deftest ^{:seon.test/estimate {:ticks 200000}} a-pid-gone-between-two-reads-is-not-left-by-a-stop
  (let [self 4242]
    (is (not (seon.check/left? nil false self)) "no ps row and kill 0 finds it no more: it exited, not left")
    (is (seon.check/left? nil true self) "no ps row but still there when asked again: left")
    (is (seon.check/left? {:pid 9 :ppid 1 :stat "S"} true self) "a row still running: left")
    (is (not (seon.check/left? {:pid 9 :ppid 1 :stat "Z"} true self)) "a row that can run nothing more: not left")))


;; docs/server.md#working-a-task-from-a-worktree: bin/mcp relays each message on its own connection to root, and answers each as it
;; comes, so one call waiting (a prove parked on a gate) blocks no other: measured 2026-11-14, one held call kept a session's every
;; other tool call waiting 17 min while root answered others in 3 ms. Here a stand-in root holds call 1 open and answers call 2
(deftest ^{:seon.test/execs ["bin/mcp"] :seon.test/estimate {:ticks 3000000}} a-relay-answers-a-quick-call-while-a-slow-one-waits
  ;; estimate: one relay process started (~1M ticks of ours to start it and read its pipes) and three socket accepts
  (with-open [ss (java.net.ServerSocket. 0 4 (java.net.InetAddress/getByName "127.0.0.1"))]
    (let [p (.start (ProcessBuilder. ^java.util.List [(str (seon.config/checkout) "/bin/mcp") (str (.getLocalPort ss))]))
          in (.getOutputStream p)
          line! (fn [^java.io.OutputStream os s] (.write os (.getBytes (str s "\n") "UTF-8")) (.flush os))
          out (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p)))
          answer! (fn [^java.net.Socket s line]
                    (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s))))
                    (line! (.getOutputStream s) line)
                    (.close s))]
      (try
        (line! in "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}")
        (let [slow (.accept ss)]
          (line! in "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}")
          (answer! (.accept ss) "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}")
          (is (str/includes? (.readLine out) "\"id\":2") "the quick call is answered while the slow one waits")
          (answer! slow "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}")
          (is (str/includes? (.readLine out) "\"id\":1") "and the slow one when root answers it, a whole line"))
        (finally (.close in) (.waitFor p))))))
