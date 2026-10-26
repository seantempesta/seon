(ns seon.root-test
  "Root as a whole process, run as the built binary (target/seon, bin/build seon): a root
  boots its fork server, so it runs in a process of its own rather than in the test
  runner's, whose threads and GC sweepers are the runner's."
  (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.db :as pdb] [datahike.api :as d] [seon.test-store :as ts]))

(defn- test-store "The one store these tests' roots take a branch of (seon.test-store/process-store)." []
  (ts/process-store))

(deftest root-threads-are-what-root-declares
  ;; three start!/stop! cycles, each in a process of its own (a process boots root once: a
  ;; second-boot-in-one-process-is-refused): while running (three agents just
  ;; evaluated) and after each stop!, every thread is one seon.check/declared-threads
  ;; names, by name stem, the GC's sweepers are the declared number, and the OS counts
  ;; nothing else. A diff of {} is exact; anything else names what differs. No web
  ;; listener (SEON_WEB_PORT=0): its adapter's threads outlive stop! (task
  ;; web-server-threads-are-joined-at-stop).
  ;; On a main of its own on the test store, deleted at the end.
  (let [main (str "root-test-" (random-uuid))
        {:keys [exit out err]} (ts/binary-sh "env" "SEON_WEB_PORT=0" (str "SEON_STORE=" (test-store)) (str "SEON_MAIN=" main)
                                      "./seon" "threads-declared" "3" :dir "target")
        _ (ts/drop-main! (test-store) main)
        res (edn/read-string (last (str/split-lines out)))]
    (is (= 0 exit) (str out err))
    (is (= 3 (count (:running res)) (count (:stopped res))))
    (is (= [{} {} {}] (:running res)))
    (is (= [{} {} {}] (:stopped res)))))

(deftest a-second-boot-in-one-process-is-refused
  ;; root boots on its main carrier, before any thread, and forks its fork server there while it
  ;; is the one thread (seon.root/boot!); its threads (Jolt's carriers, timer, poller) outlive
  ;; stop!, so a second start! in that process would fork beside them. It is refused by name
  ;; instead, and nothing is forked
  (let [main (str "root-test-" (random-uuid))
        {:keys [exit out err]} (ts/binary-sh "env" "SEON_WEB_PORT=0" (str "SEON_STORE=" (test-store)) (str "SEON_MAIN=" main)
                                      "./seon" "boot-twice" :dir "target")
        _ (ts/drop-main! (test-store) main)
        res (edn/read-string (last (str/split-lines out)))]
    (is (= 0 exit) (str out err))
    (is (re-find #"fibers already run on carrier threads" (str (:second res))) (pr-str res))))

(deftest the-sweeper-pool-is-fixed-before-anything-collects
  ;; task seon.root/the-sweeper-pool-is-fixed-before-anything-collects: six threads allocate through 30
  ;; collections in a fresh binary; Chez starts a sweeper per waiting thread and never stops one, so a
  ;; pool not fixed first ends at 6 (measured) and root-threads-are-what-root-declares reads whatever
  ;; its boot happened to start. seon.main/-main fixes the pool at 0 before anything runs: 0 sweepers.
  (let [{:keys [exit out err]} (ts/binary-sh "./seon" "sweepers-held" :dir "target")
        res (edn/read-string (last (str/split-lines out)))]
    (is (= 0 exit) (str out err))
    (is (<= 30 (:collections res)) "the workers collect on until joined: at least the 30 waited for")
    (is (= 0 (:sweepers res)))))

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
        runs (str checkout "/target/runs/" main "-runs")
        root (ts/start-root! {:store (test-store) :main main :runs runs
                              :args ["--set" (str ":seon.config.source/root=" (pr-str checkout))
                                     "--set" ":seon.config.source/dirs=[\"src\" \"test\"]"]})
        {:keys [exit log] :as stopped} (ts/stop-root! root)]
    (ts/drop-main! (test-store) main)
    (sh/sh "rm" "-rf" runs)
    (is (some? (:port root)) "the root boots with src and test imported, and listens")
    (is (= 0 exit) (str "and stops cleanly: " (pr-str stopped)))
    (is (not (str/includes? log "Could not locate")) "every required library located")))

(defn- root-answers
  "The output of a built root on the store at path and main (seon.test-store/start-root!: both
  named, never inherited), asked each request in turn on its server: one answer line per
  request, then EXIT and its log. opts: :args more root arguments, :env more variables."
  ([path main requests] (root-answers path main requests {}))
  ([path main requests {:keys [args env]}]
   ;; runs: this main's own, beside the store (every root-answers call on the one test store
   ;; shared path-runs, and a test asked another test's root: task seon.test-store/a-test-root-is-its-own)
   (let [runs (str path "-" main "-runs")
         root (ts/start-root! (cond-> {:store path :main main :runs runs :args (vec args)} env (assoc :env env)))
         answers (when (:port root) (mapv #(ts/ask root %) requests))
         stopped (ts/stop-root! root)]
     (sh/sh "rm" "-rf" runs)
     ;; each answer as the data it is (an error value among them), one per line
     (str (str/join "\n" (map pr-str answers)) (when (seq answers) "\n") "EXIT " (or (:exit stopped) (pr-str stopped)) "\n" (:log stopped)))))

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
                    (#{:ns/file :ns/tail :form/lead :form/source :seon.run/commit :seon.run/commit-error} (:db/ident %)))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database cfg)
        c (d/connect cfg)
        _ (d/transact c {:tx-data (vec (remove later? pdb/schema))})
        _ (doseq [tx (pdb/seed-tx 110)] (d/transact c {:tx-data tx}))
        _ (d/branch! c :db :old-run)
        c2 (d/connect (assoc cfg :branch :old-run))
        _ (d/transact c2 {:tx-data [{:seon.root/id :root :seon.root/quiescent {:t 536870914 :root-causes []}}]})
        _ (pdb/sync! cfg)
        _ (do (d/release c2) (d/release c))
        out (root-answers path "old-run"
                          [{:op :eval :agent "root" :code "(+ 1 2)"} {:op :agents}
                           {:op :eval :agent "root" :code "(get-in (my.root/problems sys) [:classes :platform :n])"}])
        [eval-line agents-line problems-line] (str/split-lines out)
        answer (fn [l] (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))]
    (try
      (is (str/includes? out "ROOT LISTENING") "the root boots on the earlier build's store")
      (is (= 3 (ts/value-of (:response (answer eval-line)))) "a root eval answers")
      (is (map? (:tasks (answer agents-line))) "{:op :agents} answers its tasks")
      (is (= 0 (ts/value-of (:response (answer problems-line)))) "and nothing it read was a defect")
      (is (str/includes? out "EXIT 0") "and it stops when its stdin closes")
      (finally (sh/sh "rm" "-rf" path (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest an-agent-resumes-a-branch-cut-before-an-attribute
  ;; root, 2026-10-25 (ctx-atom's finding): a branch made before :form/keywords existed killed its
  ;; agent at resume (seon.program/load-diff! -> schema-dependents read an attribute its schema
  ;; lacks). A store of this test's own, as a-root-resumes-a-store-an-earlier-build-wrote: main and
  ;; a kept branch cut from it before the attribute; today's root opens main (which gains it) and
  ;; spawns an agent onto the kept branch, which gains it on resume and answers an eval
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        path (str checkout "/target/root-test-cut-before-" (random-uuid))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database cfg)
        c (d/connect cfg)
        _ (d/transact c {:tx-data (vec (remove #(= :form/keywords (:db/ident %)) pdb/schema))})
        _ (doseq [tx (pdb/seed-tx 110)] (d/transact c {:tx-data tx}))
        _ (d/branch! c :db :old-run)
        _ (d/branch! c :old-run (pdb/on-main :old-run :kept))
        ;; the branch's own schema entry: resumed, its contract's dependents are re-armed
        ;; (schema-dependents), the read that died
        kc (d/connect (assoc cfg :branch (pdb/on-main :old-run :kept)))
        _ (d/transact kc {:tx-data [{:schema/key :demo.kept/thing :schema/form (seon.edn/encode [:map [:demo.kept/x :int]])}]})
        _ (d/release kc)
        _ (pdb/sync! cfg)
        _ (d/release c)
        out (root-answers path "old-run"
                          [{:op :eval :agent "root"
                            :code (str "(let [ev (seon.root/events sys) ag (seon.root/spawn! sys {:branch :kept})]"
                                       " (loop [] (let [e (clojure.core.async/<!! ev)]"
                                       " (cond (not= ag (:agent e)) (recur)"
                                       " (= :ready (:type e)) (:text @(seon.root/submit! sys ag \"(contains? (datahike.api/schema (seon.agent/db)) :form/keywords)\"))"
                                       " (#{:dead :ended :killed} (:type e)) (:type e)"
                                       " :else (recur)))))")}])
        answer (fn [l] (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))]
    (try
      (is (str/includes? (str (answer (first (str/split-lines out)))) ":value true")
          (str "the agent on the branch cut before :form/keywords is ready, its branch holding the attribute: " (subs out 0 (min 2000 (count out)))))
      (finally (sh/sh "rm" "-rf" path (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest a-second-boot-resumes-the-same-main
  ;; task seon.root/one-main-resumed: a root started with no main named works on the
  ;; config's main ("main"), and a second root on the same store resumes it: the task the
  ;; first loaded and the run it started are still there. A store of this test's own, the
  ;; last resort: what is proved is the default main of a store, which a shared test store's
  ;; other users would also resume; removed at the end.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        path (str checkout "/target/runs/root-test-main-" (random-uuid))
        tasks (str path "-tasks")
        _ (.mkdirs (java.io.File. tasks))
        _ (spit (str tasks "/demo.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]))
        main {:op :eval :agent "root" :code "(name (seon.db/conn-main (:conn sys)))"}
        ;; and root's watch fact: a task count off names the batch that loaded the task files (:last-load)
        held {:op :eval :agent "root" :code "[(count (seon.task/tasks @(:conn sys))) (set (map str (keys (seon.task/runs @(:conn sys))))) (dissoc (:seon.root/watch (d/pull @(:conn sys) [:seon.root/watch] [:seon.root/id :root])) :roots :boot)]"}
        first-boot (str/split-lines (root-answers path "main" [main {:op :eval :agent "root" :code (str "(my.root/load-tasks! sys " (pr-str tasks) ")")}
                                                           {:op :attach :task "demo/t"} held]))
        second-boot (str/split-lines (root-answers path "main" [main held]))
        printed (fn [l] (ts/value-of (:response (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))))]
    (try
      (is (= "main" (printed (first first-boot))) "a boot with no main named works on the config's main")
      (is (= "main" (printed (first second-boot))) "the second boot resumes the same main")
      (let [[tasks-1 runs-1 watch-1] (printed (nth first-boot 3))
            [tasks-2 runs-2 watch-2] (printed (second second-boot))]
        (is (= [2 1] [tasks-1 (count runs-1)]) (str "the first boot holds root's task and the one it loaded, and the run it started; its watcher: " (pr-str watch-1)))
        (is (= 2 tasks-2) (str "the second boot still holds both tasks; its watcher: " (pr-str watch-2)))
        (is (every? runs-2 runs-1) "and the first boot's run (it reaps it and starts the next)"))
      (finally (sh/sh "rm" "-rf" path tasks (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest a-test-roots-task-files-are-its-own
  ;; the second boot above read 810 tasks (2026-10-25): its root started seconds after a merge
  ;; rewrote the checkout's task files, FSEvents delivered those writes to its new watcher, and
  ;; a task file's event loads every file of the task dir. A test root's task dir is its own
  ;; (seon.test-store/start-root!), so its loads read its own files and never the checkout's.
  ;; Asserted through the root's own reads, with no watcher event and no write to the checkout
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        path (str checkout "/target/runs/root-test-own-tasks-" (random-uuid))
        runs (str path "-main-runs")
        _ (.mkdirs (java.io.File. (str runs "/tasks")))
        _ (spit (str runs "/tasks/demo.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work}]))
        root (ts/start-root! {:store path :main "main" :runs runs})
        ask (fn [code] (ts/value-of (:response (ts/ask root {:op :eval :agent "root" :code code}))))]
    (try
      (is (= (str runs "/tasks") (ask "(seon.config/value :seon.config.root/tasks-dir)")) "its task dir is under its runs, not the checkout's")
      ;; a fresh root's boot loads its task dir (my.root/watch!: a fresh store, or a task file changed
      ;; while root was down, has nothing else to load it): demo/t and the root task the first load
      ;; writes, none of the checkout's. It held none before: a root loaded task files only on a
      ;; watcher event, and this assertion was red (0 tasks), so no event for demo.edn's write
      ;; loaded it; the count is the boot's load
      (is (= 2 (ask "(count (seon.task/tasks @(:conn sys)))")) "its own task file and root's task, loaded at boot")
      ;; its watcher's roots are what its loaders read and the refs, never the checkout, whose builds,
      ;; stores and (in the main tree) worktrees no loader reads: the whole checkout kept fseventsd
      ;; saturated (task seon.watch/root-watches-what-its-loaders-read)
      (let [[roots dirs co] (ask "(let [co (seon.config/checkout)] [(:roots (:seon.root/watch (datahike.api/pull @(:conn sys) [:seon.root/watch] [:seon.root/id :root]))) (set (mapcat val (#'my.root/loader-dirs co))) co])")]
        (is (not-any? #{co} roots) (str "the checkout is no watched root: " roots))
        (is (= dirs (set (butlast roots))) "the loaders' dirs, then the refs")
        (is (str/ends-with? (last roots) "/refs") (str roots))
        ;; its task dir is outside its checkout, and a task file there is the task loader's
        (is (= [:tasks] (ask (str "(vec (keys (my.root/watch-loads (seon.config/checkout) nil nil [[:path \"" runs "/tasks/x.edn\"]])))")))
            "a task file in a task dir outside the checkout loads the tasks"))
      ;; the read's outcome is root's :seon.root/tasks, the subject a refused read names; an unchanged good read writes nothing
      (is (= [{:files 1} {:ok true :changed 0 :files 1}]
             (ask "[(:seon.root/tasks (datahike.api/pull @(:conn sys) [:seon.root/tasks] [:seon.root/id :root])) (my.root/load-tasks! sys)]"))
          "the boot read's outcome, and a read that finds it so writes nothing")
      (finally (ts/stop-root! root) (clojure.java.shell/sh "rm" "-rf" path runs (str path "-agents"))))))

(deftest a-dead-link-in-the-wiki-is-a-problem-and-the-boot-goes-on
  ;; root's boot loads the wiki (seon.root/wiki-tx) in its boot fact's commit; one link to no
  ;; section in any page used to end the boot (bin/root start and next with it), then refused
  ;; the whole wiki. Now the rest loads, the link is a dead citation my.root/problems shows,
  ;; and the root boots. A
  ;; checkout of this test's own: this one's files, its docs/ with one broken page more
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        tmp (str checkout "/target/runs/root-test-wiki-" (random-uuid))
        path (str tmp "-store")
        _ (.mkdirs (java.io.File. (str tmp "/docs")))
        _ (doseq [f (.listFiles (java.io.File. checkout)) :let [n (.getName f)] :when (not (#{"docs" ".git"} n))]
            (sh/sh "ln" "-s" (.getPath f) (str tmp "/" n)))
        _ (doseq [f (.listFiles (java.io.File. checkout "docs"))] (sh/sh "cp" (.getPath f) (str tmp "/docs/")))
        _ (spit (str tmp "/docs/zz-broken.md") "# Broken\n\nIt links to [nowhere](agent.md#nowhere).\n")
        out (root-answers path "main" [{:op :eval :agent "root" :code "(boolean (re-find #\"links to docs/agent.md#nowhere\" (pr-str (my.root/problems sys))))"}]
                          {:env {"SEON_RUN_CHECKOUT" tmp}})
        printed (ts/value-of (:response (try (edn/read-string {:default tagged-literal} (first (str/split-lines out))) (catch Exception _ nil))))]
    (try
      (is (str/includes? out "ROOT LISTENING") (str "the root boots: " out))
      (is (true? printed) "and the dead link is an open problem")
      (finally (sh/sh "rm" "-rf" tmp path (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest every-end-of-a-root-is-a-fact
  ;; task seon.root/every-end-of-a-root-is-a-fact: a clean stop is a fact naming its cause
  ;; (:seon.root/stopped; here its stdin closes, as bin/root stop closes it), the next boot
  ;; records it as :seon.root/previous-end, and a boot that left no stop fact (SIGKILL, a
  ;; crash) is found :unclean. A store of this test's own, as a-second-boot-resumes-the-same-main's
  ;; (root-answers names its fifo and log after the store); removed at the end.
  (let [path (str (.getCanonicalPath (java.io.File. ".")) "/target/runs/root-test-ends-" (random-uuid))
        ev (fn [code] {:op :eval :agent "root" :code code})
        printed (fn [l] (ts/value-of (:response (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))))
        [b1] (str/split-lines (root-answers path "main" [(ev "(:boot sys)")]))
        [prev killed] (str/split-lines
                       (root-answers path "main" [(ev "(:seon.root/previous-end (d/pull @(:conn sys) [:seon.root/previous-end] [:seon.root/id :root]))")
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
        out (root-answers path main
                          [{:op :eval :agent "root" :code "((resolve 'seon.ref/line-citation) \"src/a.clj:50\")"}])
        answer (try (edn/read-string {:default tagged-literal} (first (str/split-lines out))) (catch Exception _ nil))]
    (try
      (is (= "src/a.clj" (ts/value-of (:response answer))) out)
      (finally (ts/drop-main! (test-store) main) (sh/sh "rm" "-f" (str path "-in") (str path ".log"))))))

(deftest a-build-without-whole-program-inference-reads-with-tools-reader
  ;; task jolt.ns/a-build-without-whole-program-inference-compiles-tools-reader: with
  ;; JOLT_NO_WP_INFER=1, `jolt build` of an app requiring clojure.tools.reader fails to analyze
  ;; tools.reader.impl.utils (`<=clojure-1-7-alpha5 is not bound`), so a root cannot be built
  ;; open-world; with inference on the same app builds and reads.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        out (str checkout "/target/reader-no-wp-" (random-uuid))
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
        args ["--set" (str ":seon.config.source/root=" (pr-str tree)) "--set" ":seon.config.source/dirs=[\"src\"]"]
        probe {:op :eval :agent "root"
               :code (str "(let [db @(:conn sys)] {:fact (:seon.root/source (datahike.api/pull db [:seon.root/source] [:seon.root/id :root]))"
                          " :text (seon.source/file-text db 'follow.tree)"
                          " :removed-forms (count (filter #(clojure.string/includes? % \"removed\") (map :v (datahike.api/datoms db :aevt :form/src))))"
                          " :removed-var (count (datahike.api/datoms db :avet :var/name 'follow.tree/removed))"
                          " :tasks (count (seon.task/tasks db))})")}
        boot! (fn [requests]
                (let [out (root-answers path main requests {:args args})]
                  (sh/sh "rm" "-f" (str path "-in"))
                  (mapv (fn [l] (or (ts/value-of (:response (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))) l))
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
        (is (= (dissoc (:fact two) :boot) (dissoc (:fact three) :boot)) "the same commit imports nothing: the fact is the second boot's, but for whose boot it is")
        (is (not= (:boot (:fact two)) (:boot (:fact three))) "one source fact per boot")
        (is (and (nil? (:inputs (:fact two))) (re-find #"binary was built from" (str (:unshared (:fact two)))))
            "the running binary is not built from this tree: no inputs to key agents' results by, and why")
        (is (= b (:text three))))
      (finally (ts/drop-main! (test-store) main) (sh/sh "rm" "-rf" tree tasks (str path "-in") (str path ".log"))))))
