(ns seon.root-test
  "Root as a whole process, run as the built binary (target/seon, bin/build seon): a root
  boots its fork server, so it runs in a process of its own rather than in the test
  runner's, whose threads and GC sweepers are the runner's."
  (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.db :as pdb] [datahike.api :as d] [seon.test-store :as ts] [seon.config :as config] [seon.contract :as contract]))

(defn- test-store "The one store these tests' roots take a branch of (seon.test-store/process-store)." []
  (ts/process-store))

(def ^:private boot-then-boot-again
  "One cycle and a second boot in a process of its own (./seon boot-twice, seon.check/boot-twice: start!, three agents
  evaluated, stop!, then start! again), on a main of its own on the test store, deleted at once: {:exit :out :err :res},
  res its last line read. Two claims read it, so the process boots root once for both."
  (delay
    (let [main (str "root-test-" (random-uuid))
          r (ts/binary-sh "env" "SEON_WEB_PORT=0" (str "SEON_STORE=" (test-store)) (str "SEON_MAIN=" main)
                          "./seon" "boot-twice" "--profile" (name (config/profile)) :dir "target")]
      (ts/drop-main! (test-store) main)
      (assoc r :res (edn/read-string (last (str/split-lines (:out r))))))))

(deftest root-threads-are-what-root-declares
  ;; one start!/stop! cycle, in a process of its own (a process boots root once: a
  ;; second-boot-in-one-process-is-refused, which reads the same process): while running (three agents just
  ;; evaluated) and after stop!, every thread is one seon.check/declared-threads
  ;; names, by name stem, the GC's sweepers are the declared number, and the OS counts
  ;; nothing else. A cycle is a process of its own, so a second one starts from the same
  ;; nothing and reads the same thing: this was three (82 s) and bin/check lifecycle still
  ;; repeats it (docs/testing.md#a-root-costs-what-it-boots). A diff of {} is exact; anything else names what differs. No web
  ;; listener (SEON_WEB_PORT=0): its adapter's threads outlive stop! (task
  ;; web-server-threads-are-joined-at-stop).
  (let [{:keys [exit out err res]} @boot-then-boot-again]
    (is (= 0 exit) (str out err))
    (is (= 1 (count (:running res)) (count (:stopped res))))
    (is (= [{}] (:running res)))
    (is (= [{}] (:stopped res)))))

(deftest a-second-boot-in-one-process-is-refused
  ;; root boots on its main carrier, before any thread, and forks its fork server there while it
  ;; is the one thread (seon.root/boot!); its threads (Jolt's carriers, timer, poller) outlive
  ;; stop!, so a second start! in that process would fork beside them. It is refused by name
  ;; instead, and nothing is forked: after a real first boot, three agents and its stop! (seon.check/boot-twice),
  ;; the process root-threads-are-what-root-declares reads, so this claim pays no boot of its own
  (let [{:keys [out err res]} @boot-then-boot-again]
    (is (re-find #"fibers already run on carrier threads" (str (:second res))) (str (pr-str res) "\n" out err))))

(deftest the-sweeper-pool-is-fixed-before-anything-collects
  ;; task seon.root/the-sweeper-pool-is-fixed-before-anything-collects: six threads allocate through 30
  ;; collections in a fresh binary; Chez starts a sweeper per waiting thread and never stops one, so a
  ;; pool not fixed first ends at 6 (measured) and root-threads-are-what-root-declares reads whatever
  ;; its boot happened to start. seon.main/-main fixes the pool at 0 before anything runs: 0 sweepers.
  (let [{:keys [exit out err]} (ts/binary-sh "./seon" "sweepers-held" "--profile" (name (config/profile)) :dir "target")
        res (edn/read-string (last (str/split-lines out)))]
    (is (= 0 exit) (str out err))
    (is (<= 30 (:collections res)) "the workers collect on until joined: at least the 30 waited for")
    (is (= 0 (:sweepers res)))))

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

(def ^:private bare
  "Root arguments for a root whose subject is its store, not the program or the wiki: it imports no source and
  loads no page: for a store of its own, which holds neither (the checkout's 4,500 forms and 39 pages are 15-20 s of its boot; on a seeded store the same arguments would retract both, 14-32 s,
  docs/testing.md#a-root-costs-what-it-boots)."
  ["--set" ":seon.config.source/dirs=[]" "--set" ":seon.config.root/wiki-dirs=[]"])

(deftest a-root-resumes-a-store-an-earlier-build-wrote
  ;; task seon.root/a-root-resumes-a-store-an-earlier-build-wrote: the store as builds of
  ;; 2026-10-21 before CONFIG merged left it (next/runs/store-full-20261021, read 17:35): its
  ;; schema lacks the attributes added since (the config's, the model manifest's, the source's), and its main's
  ;; root entity holds a quiescent fact and no boot (their boot fact was refused), which
  ;; seon.task/entity-value could not read. The built root resumes that main and answers a
  ;; root eval and {:op :agents}. A store on disk of this test's own, the last resort: what
  ;; is proved is a kept store another build wrote; removed at the end.
  (let [checkout (seon.config/checkout)
        path (str checkout "/target/root-test-resume-" (random-uuid))
        later? #(or (str/starts-with? (namespace (:db/ident %)) "seon.config") (= "seon.ai.model" (namespace (:db/ident %)))
                    (#{:ns/file :ns/tail :form/lead :form/source :seon.task/commit :seon.task/commit-error} (:db/ident %)))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database cfg)
        c (pdb/connect cfg)
        _ (d/transact c {:tx-data (vec (remove later? pdb/schema))})
        _ (doseq [tx (pdb/seed-tx 110)] (d/transact c {:tx-data tx}))
        _ (d/branch! c :db :old-run)
        c2 (pdb/connect (assoc cfg :branch :old-run))
        _ (d/transact c2 {:tx-data [{:seon.root/id :root :seon.root/quiescent {:t 536870914 :root-causes []}}]})
        _ (pdb/sync! cfg)
        _ (do (d/release c2) (d/release c))
        out (root-answers path "old-run"
                          [{:op :eval :agent "root" :code "(+ 1 2)"} {:op :agents}
                           {:op :eval :agent "root" :code "(get-in (my.root/problems sys) [:classes :platform :n])"}]
                          {:args bare})
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
  (let [checkout (seon.config/checkout)
        path (str checkout "/target/root-test-cut-before-" (random-uuid))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database cfg)
        c (pdb/connect cfg)
        _ (d/transact c {:tx-data (vec (remove #(= :form/keywords (:db/ident %)) pdb/schema))})
        _ (doseq [tx (pdb/seed-tx 110)] (d/transact c {:tx-data tx}))
        _ (d/branch! c :db :old-run)
        _ (d/branch! c :old-run (pdb/on-main :old-run :kept))
        ;; the branch's own schema entry: resumed, its contract's dependents are re-armed
        ;; (schema-dependents), the read that died
        kc (pdb/connect (assoc cfg :branch (pdb/on-main :old-run :kept)))
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
                                       " :else (recur)))))")}]
                          {:args bare})
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
  ;; And every end of a root is a fact, on the same two boots (it was a test of its own,
  ;; two more boots on a clone, 70 s): a clean stop is a fact naming its cause (:seon.root/stopped; here
  ;; its stdin closes, as bin/root stop closes it), the next boot records it as :seon.root/previous-end, and
  ;; a boot that left no stop fact (SIGKILL, a crash) is found :unclean. Who started a boot is that boot's
  ;; (:seon.root/started-by): the test running start-root!, or the last --started-by its arguments name
  (let [checkout (seon.config/checkout)
        path (str checkout "/target/runs/root-test-main-" (random-uuid))
        tasks (str path "-tasks")
        _ (.mkdirs (java.io.File. tasks))
        _ (spit (str tasks "/demo.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]))
        ev (fn [code] {:op :eval :agent "root" :code code})
        main (ev "(name (seon.db/conn-main (:conn sys)))")
        ;; and root's watch fact: a task count off names the batch that loaded the task files (:last-load)
        held (ev "[(count (seon.task/tasks @(:conn sys))) (set (keys (seon.task/agents @(:conn sys)))) (dissoc (:seon.root/watch (d/pull @(:conn sys) [:seon.root/watch] [:seon.root/id :root])) :roots :boot)]")
        by (ev "(:started-by (seon.task/root @(:conn sys)))")
        first-boot (str/split-lines (root-answers path "main" [main (ev (str "(my.root/load-tasks! sys " (pr-str tasks) ")"))
                                                           {:op :attach :task "demo/t"} held (ev "(:boot sys)") by]
                                                  {:args bare}))
        second-boot (str/split-lines (root-answers path "main" [main held
                                                                (ev "(:seon.root/previous-end (d/pull @(:conn sys) [:seon.root/previous-end] [:seon.root/id :root]))")
                                                                (ev "(#'seon.root/previous-end (d/db-with @(:conn sys) [{:seon.root/id :root :seon.root/boot \"killed-boot\"}]))")
                                                                by (ev "(= [\"--started-by\" \"seon.root-test/another\"] (vec (take-last 2 (my.root/resume-args sys))))")]
                                                   {:args (into bare ["--started-by" "seon.root-test/another"])}))
        printed (fn [l] (ts/value-of (:response (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))))]
    (try
      (is (= "main" (printed (first first-boot))) "a boot with no main named works on the config's main")
      (is (= "main" (printed (first second-boot))) "the second boot resumes the same main")
      (let [[tasks-1 agents-1 watch-1] (printed (nth first-boot 3))
            [tasks-2 agents-2 watch-2] (printed (second second-boot))]
        (is (= [1 1] [tasks-1 (count agents-1)]) (str "the first boot holds the one task it loaded (a file task is one whole task) and the agent it started; its watcher: " (pr-str watch-1)))
        (is (= 1 tasks-2) (str "the second boot still holds the task; its watcher: " (pr-str watch-2)))
        (is (every? agents-2 agents-1) "and the first boot's agent (its task is reaped, and started again by the next agent of the session)"))
      (let [b1 (printed (nth first-boot 4))
            [prev killed by2 resume] (map printed (drop 2 second-boot))]
        (is (string? b1))
        (is (= "seon.root-test/a-second-boot-resumes-the-same-main" (printed (nth first-boot 5))) "the test that started the boot is its fact")
        (is (= "seon.root-test/another" by2) "and the next boot's is its own")
        (is (true? resume) "and the generation after it carries it")
        (is (= {:boot b1 :cause :stdin-eof} (select-keys prev [:boot :cause]))
            "the second boot found the first's clean end: its stdin closed")
        (is (= {:boot "killed-boot" :cause :unclean} (select-keys killed [:boot :cause]))
            "a boot that left no stop fact is found :unclean"))
      (finally (sh/sh "rm" "-rf" path tasks (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest a-test-roots-task-files-are-its-own
  ;; the second boot above read 810 tasks (2026-10-25): its root started seconds after a merge
  ;; rewrote the checkout's task files, FSEvents delivered those writes to its new watcher, and
  ;; a task file's event loads every file of the task dir. A test root's task dir is its own
  ;; (seon.test-store/start-root!), so its loads read its own files and never the checkout's.
  ;; Asserted through the root's own reads, with no watcher event and no write to the checkout.
  ;; And the built root reads a line citation (task jolt.ns/whole-program-inference-keeps-nil-checks):
  ;; compiled, `[from to]` destructured from `(if dash [a b] [suffix])` typed `to` a string, so
  ;; `(nil? to)` folded false and `src/a.clj:50` reached parse-long as nil; my.root/load-tasks!
  ;; failed on every task file citing `path:N`
  (let [checkout (seon.config/checkout)
        path (test-store)
        main (str "root-test-own-tasks-" (random-uuid))
        runs (str checkout "/target/runs/" main "-runs")
        _ (.mkdirs (java.io.File. (str runs "/tasks")))
        _ (spit (str runs "/tasks/demo.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work}]))
        root (ts/start-root! {:store path :main main :runs runs})
        ask (fn [code] (ts/value-of (:response (ts/ask root {:op :eval :agent "root" :code code}))))]
    (try
      (is (= (str runs "/tasks") (ask "(seon.config/value :seon.config.root/tasks-dir)")) "its task dir is under its runs, not the checkout's")
      ;; a fresh root's boot loads its task dir (my.root/watch!: a fresh store, or a task file changed
      ;; while root was down, has nothing else to load it): demo/t, a file task being one whole task
      ;; (my.root/task-files), none of the checkout's. It held none before: a root loaded task files only on a
      ;; watcher event, and this assertion was red (0 tasks), so no event for demo.edn's write
      ;; loaded it; the count is the boot's load
      (is (= 1 (ask "(count (seon.task/tasks @(:conn sys)))")) "its own task file's one whole task, loaded at boot")
      (is (= "src/a.clj" (ask "((resolve 'seon.ref/line-citation) \"src/a.clj:50\")")) "the built root reads a path:line citation")
      ;; its watcher's roots are what its watched loaders read, never the checkout, whose builds,
      ;; stores and (in the main tree) worktrees no loader reads: the whole checkout kept fseventsd
      ;; saturated (task seon.watch/root-watches-what-its-loaders-read)
      (let [[roots dirs co] (ask "(let [co (seon.config/checkout)] [(:roots (:seon.root/watch (datahike.api/pull @(:conn sys) [:seon.root/watch] [:seon.root/id :root]))) (set (mapcat val (#'my.root/loader-dirs co))) co])")]
        (is (not-any? #{co} roots) (str "the checkout is no watched root: " roots))
        (is (= dirs (set roots)) "the watched loaders' dirs alone: no source dir, no refs")
        ;; its task dir is outside its checkout, and a task file there is the task loader's
        (is (= [:tasks] (ask (str "(vec (keys (my.root/watch-loads (seon.config/checkout) [[:path \"" runs "/tasks/x.edn\"]])))")))
            "a task file in a task dir outside the checkout loads the tasks"))
      ;; the read's outcome is root's :seon.root/tasks, the subject a refused read names; an unchanged good read writes nothing
      (is (= [{:files 1} {:ok true :changed 0 :files 1}]
             (ask "[(:seon.root/tasks (datahike.api/pull @(:conn sys) [:seon.root/tasks] [:seon.root/id :root])) (my.root/load-tasks! sys)]"))
          "the boot read's outcome, and a read that finds it so writes nothing")
      (finally (ts/stop-root! root) (ts/drop-main! path main) (clojure.java.shell/sh "rm" "-rf" runs)))))

(deftest a-build-without-whole-program-inference-reads-with-tools-reader
  ;; with
  ;; JOLT_NO_WP_INFER=1, `jolt build` of an app requiring clojure.tools.reader fails to analyze
  ;; tools.reader.impl.utils (`<=clojure-1-7-alpha5 is not bound`), so a root cannot be built
  ;; open-world; with inference on the same app builds and reads.
  (let [checkout (seon.config/checkout)
        out (str checkout "/target/reader-no-wp-" (random-uuid))
        build (sh/sh "env" "JOLT_NO_WP_INFER=1" (str "JOLT_PWD=" checkout) (str checkout "/target/jolt/jolt") "-A:hello" "build" "-m" "build.reader" "-o" out :dir checkout)]
    (try
      (is (= 0 (:exit build)) "the build without whole-program inference succeeds")
      (is (= "[1 2]" (str/trim (:out (sh/sh out)))) "and the binary reads")
      (finally (sh/sh "rm" "-rf" out (str out ".build"))))))

(deftest main-follows-its-source-tree-across-restarts
  ;; task seon.root/main-follows-its-source-tree: a root resuming its main imports its source
  ;; tree again when the tree reads otherwise than main's last import, replacing main's
  ;; program (seon.source/replace-tx), and imports nothing when it reads the same. Three
  ;; boots on one store of its own (removed at the end), the tree a scratch
  ;; repository under target/: commit A, then commit B with one function changed and one
  ;; removed, then B again. B's ns form also gains an alias that a new function uses (2026-10-22:
  ;; an import that ran a form before its namespace's new alias killed the root, `No such namespace`),
  ;; and the second boot runs that function: the binary, older than main's program, catches up at boot.
  ;; (seon.source-test/a-restart-catches-up held these two claims on a clone of the whole checkout,
  ;; with two model turns, about 120 s; folded here 2026-11-08.) A store of its own, empty, with no wiki: on a
  ;; branch of the seeded process store each boot's import of the one-file tree retracted the checkout's
  ;; program first (docs/testing.md#a-root-costs-what-it-boots), 74 s for three boots
  (let [checkout (seon.config/checkout)
        tree (str checkout "/target/tmp/root-test-follow-" (random-uuid))
        tasks (str tree "-tasks")
        git (fn [& args] (apply sh/sh "git" "-C" tree "-c" "user.name=t" "-c" "user.email=t@localhost" args))
        commit! (fn [text]
                  (spit (str tree "/src/follow/tree.clj") text)
                  (git "add" "-A") (git "commit" "-q" "-m" "tree")
                  (str/trim (:out (git "rev-parse" "HEAD"))))
        a "(ns follow.tree)\n\n(defn kept [] 1)\n\n(defn changed [] 1)\n\n(defn removed [] 1)\n"
        b "(ns follow.tree\n  (:require [clojure.set :as follow-set]))\n\n(defn kept [] 1)\n\n(defn changed [] 2)\n\n(defn uses-alias [] (follow-set/union #{1} #{2}))\n"
        path (str tree "-store")
        main "main"
        args ["--set" (str ":seon.config.source/root=" (pr-str tree)) "--set" ":seon.config.source/dirs=[\"src\"]" "--set" ":seon.config.root/wiki-dirs=[]"]
        probe {:op :eval :agent "root"
               :code (str "(let [db @(:conn sys)] {:fact (:seon.root/source (datahike.api/pull db [:seon.root/source] [:seon.root/id :root]))"
                          " :text (seon.source/file-text db 'follow.tree)"
                          " :removed-forms (count (filter #(clojure.string/includes? % \"removed\") (map :v (datahike.api/datoms db :aevt :form/src))))"
                          " :removed-var (count (datahike.api/datoms db :avet :var/name 'follow.tree/removed))"
                          " :alias (some-> (resolve 'follow.tree/uses-alias) deref (apply []))"
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
        (is (= [sha-a a 1] [(:commit (:fact one)) (:text one) (:tasks one)]) (pr-str one))
        (is (= sha-b (:commit (:fact two))) "the second boot imported the tree's new commit")
        (is (= b (:text two)) "main holds B's text, byte for byte")
        (is (= [0 0] [(:removed-forms two) (:removed-var two)]) "no form or var of the removed function")
        (is (= 1 (:tasks two)) "the task is intact")
        (is (= 4 (:retracted (:fact two))) "retracted: the old ns form, the changed function's old form, the removed function's form and its var")
        (is (= #{1 2} (:alias two)) "the new alias applied before the form using it, and the boot loaded main's program: the function runs")
        (is (= (dissoc (:fact two) :boot) (dissoc (:fact three) :boot)) "the same commit imports nothing: the fact is the second boot's, but for whose boot it is")
        (is (not= (:boot (:fact two)) (:boot (:fact three))) "one source fact per boot")
        (is (and (nil? (:inputs (:fact two))) (re-find #"binary was built from" (str (:unshared (:fact two)))))
            "the running binary is not built from this tree: no inputs to key agents' results by, and why")
        (is (= b (:text three))))
      (finally (sh/sh "rm" "-rf" tree tasks path (str path "-agents") (str path "-in") (str path ".log"))))))

(def ^:private boot-probe
  "What a boot of `boots-on-a-tree` answers: the config key and the schema entry the trees change, as root holds them; the
  t of this boot's fact (:seon.root/boot, written once the graph runs, after the fork server forked), of main's
  newest program fact (config and schema entries) and of the seed's import (the first :seon.root/source fact in main's
  history: the seed writes it with the program, in one transaction); the boot's import (seon.root/import-tree!) and
  the source fact."
  {:op :eval :agent "root"
   :code (str "(let [db @(:conn sys) root (:db/id (datahike.api/entity db [:seon.root/id :root]))"
              " t-of (fn [a] (reduce max 0 (map :tx (datahike.api/datoms db :aevt a))))]"
              " {:streams-buf (seon.config/value :seon.config.web/streams-buf)"
              " :schema-changed? (contains? (seon.program/schema-changes db) :seon.config.web/streams-buf)"
              " :boot-t (:tx (first (datahike.api/datoms db :eavt root :seon.root/boot)))"
              " :program-t (max (t-of :config/form) (t-of :schema/form))"
              " :seed-t (reduce min (map :tx (datahike.api/datoms (datahike.api/history db) :aevt :seon.root/source)))"
              " :import (get-in sys [:bindings :view :boot :import])"
              " :fact (dissoc (:seon.root/source (datahike.api/pull db [:seon.root/source] root)) :boot :inputs :unshared :ms)})")})

(defn- boots-on-a-tree
  "A store of its own (removed at the end) seeded (./seon seed-program) from a scratch repository under target/ holding
  the one file path (dirs resources/seon) as its first text; then boot-probe's answer at each boot of a root on it, the
  file committed with the next of texts before each (the same text commits nothing). Answers [the seed's output answer
  ...], a boot that never answered its output. opts: :also, more requests (answers in :also); :wiki, a page's text at the
  tree's docs/zz-broken.md, each boot's (not the seed's) wiki. Each answer's :listening? (its log says ROOT LISTENING) and
  :tree (the tree's path in the checkout). A few seconds a boot. See docs/source.md#a-boot-imports-main-first."
  ([path texts] (boots-on-a-tree path texts {}))
  ([path [seeded & texts] {:keys [also wiki]}]
   (let [rel (str "target/tmp/root-test-boot-" (random-uuid))
         tree (str (config/checkout) "/" rel)
         store (str tree "-store")
         git (fn [& args] (apply sh/sh "git" "-C" tree "-c" "user.name=t" "-c" "user.email=t@localhost" args))
         args ["--set" (str ":seon.config.source/root=" (pr-str tree)) "--set" ":seon.config.source/dirs=[\"resources/seon\"]"]
         seed-args (into args ["--set" ":seon.config.root/wiki-dirs=[]"])
         boot-args (into args ["--set" (str ":seon.config.root/wiki-dirs=" (pr-str (if wiki [(str rel "/docs")] [])))])
         ;; an answer's value, or the line as it is when it holds none (an error)
         read-line (fn [l] (let [v (ts/value-of (:response (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil))))] (if (some? v) v l)))]
     (.mkdirs (.getParentFile (java.io.File. (str tree "/" path))))
     (sh/sh "git" "init" "-q" tree)
     (try
       (when wiki
         (.mkdirs (java.io.File. (str tree "/docs")))
         (spit (str tree "/docs/zz-broken.md") wiki))
       ;; the fork server adds the source root's deps.edn roots at its start (seon.fork-server/main): none here
       (spit (str tree "/deps.edn") "{:deps {}}")
       (spit (str tree "/" path) seeded)
       (git "add" "-A")
       (git "commit" "-q" "-m" "seeded")
       (into [(:out (apply ts/binary-sh (concat ["./seon" "seed-program" store] seed-args ["--profile" (name (config/profile)) :dir "target"])))]
             (for [text texts]
               (let [_ (spit (str tree "/" path) text)
                     _ (git "add" "-A")
                     _ (git "commit" "-q" "-m" "tree")
                     out (root-answers store "main" (into [boot-probe] also) {:args boot-args})
                     [probe & more] (str/split-lines out)
                     v (read-line probe)]
                 (sh/sh "rm" "-f" (str store "-in"))
                 (if (map? v)
                   (assoc v :also (mapv read-line (take (count also) more)) :listening? (str/includes? out "ROOT LISTENING") :tree rel)
                   out))))
       (finally (sh/sh "rm" "-rf" tree store (str store "-agents") (str store "-in") (str store ".log")))))))

(deftest a-boot-after-a-merge-reads-the-merged-config
  ;; task seon.root/a-boot-imports-main-before-it-reads-config: a merge adds a config key root reads at start
  ;; (:seon.config.web/streams-buf, read for a :bind/ buffer), as 191e4b56ba did. Main holds the tree before it (the
  ;; shipped config without the key); boot two runs the binary, whose tree has the key, on that main: the boot imports
  ;; the merged config before it holds main's (2026-11-08 the standing root refused to start, "is set nowhere"); boot
  ;; three, with nothing merged, imports and writes nothing
  (let [file "resources/seon/config/default.edn"
        ;; the shipped file as the binary was built (a test reading the checkout's files takes the tree)
        merged config/built-text
        ;; main before the merge: the key at another value (a missing key a config may not lack refuses its import, as it
        ;; would refuse a boot: seon.config's closed), so the merged value root holds is the boot's import's
        before (str/replace merged #"(:seon\.config\.web/streams-buf )(\d+)" (fn [[_ k v]] (str k (inc (parse-long v)))))
        [seed two three] (boots-on-a-tree file [before merged merged])]
    (is (not= before merged))
    (is (str/includes? (str seed) "SEED OK") (str seed))
    (is (map? two) (str "the root booted on a main older than its binary: " two))
    (is (= (config/value :seon.config.web/streams-buf) (:streams-buf two)) "it holds the merged key's value")
    (is (< (:seed-t two) (:program-t two) (:boot-t two)) "main's config became the merged one before the boot's fact: before root read it, and before the fork")
    (is (true? (:imported? (:import two))) (pr-str (:import two)))
    (is (= (:program-t two) (:program-t three)) "nothing merged: no program fact written")
    (is (false? (:imported? (:import three))) (pr-str (:import three)))
    (is (= (:fact two) (:fact three)) "and the source fact is the same import's")))

(def ^:private registry-of-an-agent
  "A request to root: the schema entry :seon.config.web/streams-buf as an agent forked now holds it (the registry its
  eval binds, seon.fork-server/warm-start's, under seon.contract/options): [whether 1 is valid, its :min]; the agent is
  ended after."
  {:op :eval :agent "root"
   :code (str "(let [ag (seon.root/spawn! sys {})"
              " r (try @(seon.root/submit! sys ag \"(let [s (malli.core/deref (malli.core/schema :seon.config.web/streams-buf seon.contract/options))] [(malli.core/validate s 1) (:min (malli.core/properties s))])\")"
              " (finally (seon.root/kill! sys ag)))]"
              " (or (:value r) r))")})

(deftest a-boot-forks-with-the-merged-schema-registry
  ;; task seon.root/a-boot-imports-main-before-it-reads-config: main holds a schema entry older than the binary's (here
  ;; :seon.config.web/streams-buf with another bound, seeded), and the merge brings the binary's. The fork server forks from main
  ;; as the boot left it and builds the registry each agent holds from main's schema changes (seon.fork-server/warm), so
  ;; the boot imports the merged entry before it forks: 2026-11-08 every model call of the first agent after a restart
  ;; failed on a registry of main's entries from before that boot's import. Asked of an agent forked after the boot
  (let [file "resources/seon/schemas/seon.config.web.edn"
        ;; the entry as the binary was built, the resource's one entry (a test reading the checkout's files takes the tree)
        form (get contract/forms :seon.config.web/streams-buf)
        merged (pr-str {:seon.config.web/streams-buf form})
        before (pr-str {:seon.config.web/streams-buf (update form 1 assoc :min 2)})
        [seed two] (boots-on-a-tree file [before merged] {:also [registry-of-an-agent]})]
    (is (not= before merged))
    (is (str/includes? (str seed) "SEED OK") (str seed))
    (is (= 1 (:min (second form))) "the merged entry's bound, which the seeded one moves")
    (is (= [true 1] (first (:also two))) (str "an agent forked after the boot holds the merged entry: " (pr-str two)))
    (is (false? (:schema-changed? two)) (str two))
    (is (< (:seed-t two) (:program-t two) (:boot-t two)) "main's entry became the merged one before the boot's fact, which is written after the fork")))

(def ^:private broken-boot
  "boots-on-a-tree on a seeded tree whose next commit breaks its schema resource (it no longer reads): the seed's output and
  the one boot's answer."
  (delay
    (let [file "resources/seon/schemas/seon.config.web.edn"
          merged (pr-str {:seon.config.web/streams-buf (get contract/forms :seon.config.web/streams-buf)})]
      (boots-on-a-tree file [merged (str merged "\n{:unclosed")]))))

(deftest a-boot-whose-import-refuses-a-file-still-starts
  ;; task seon.root/a-boot-imports-main-before-it-reads-config: the tree's schema resource no longer reads (a merge broke
  ;; it). The boot imports nothing, says which file (seon.root/unimportable), and starts on main as it was: a dead root is
  ;; the outcome the boot's import exists to end (docs/source.md#a-file-that-does-not-load)
  (let [file "resources/seon/schemas/seon.config.web.edn"
        [seed two] @broken-boot]
    (is (str/includes? (str seed) "SEED OK") (str seed))
    (is (map? two) (str "the root started: " two))
    (is (false? (:imported? (:import two))) (pr-str (:import two)))
    (is (str/includes? (str (:seon.error/message (:error (:import two)))) file) "the refusal names the file")
    (is (= (:seed-t two) (:program-t two)) "nothing of it was written, at boot or after: main's program is the seed's")
    (is (= (config/value :seon.config.web/streams-buf) (:streams-buf two)) "main's entries as they were")))

(deftest a-root-restart-names-the-same-agent-by-the-same-name
  ;; docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08: an agent's id is the keyword of its
  ;; :seon.agent/key, minted once (seon.root/start-agent) and kept by the session's next agent
  ;; (seon.task/session-key), so a root's restart leaves every interface naming the agent as before:
  ;; root's view, the task's agent, the server's agents list and the page's address. One agent on one
  ;; task; root stopped cleanly and started again on the same store, main and runs, and the task
  ;; attached again (the session continues); no id of a counter's form (:a1) appears before or after.
  (let [checkout (config/checkout)
        tns (seon.lib/unique-id)
        task (str tns "/t")
        tasks (str checkout "/target/runs/" tns "-tasks")
        main (str "restart-names-" (random-uuid))
        runs (str checkout "/target/runs/" main "-runs")
        root0 {:store (ts/process-store checkout) :main main :runs runs
               :args ["--set" (str ":seon.config.source/root=" (pr-str checkout))]}
        ;; every name the interfaces give the task's agent, read on the running root
        names-of (fn [root]
                   (let [ask #(ts/ask root %)
                         ev #(let [r (ask {:op :eval :agent "root" :code %})] (if (:error r) r (ts/value-of (:response r))))
                         attach (ask {:op :attach :task task})
                         ag (:agent attach)]
                     {:attached ag
                      :view (ev "(vec (sort (keys (:agents (seon.root/state sys)))))")
                      :task (get-in (ask {:op :agents}) [:tasks task :agent])
                      :listed (vec (keys (:agents (ask {:op :agents}))))
                      :address (ev (str "(seon.render.ui/session-address (datahike.api/pull @(:conn sys) '[:seon.agent/key] [:seon.agent/key " (pr-str (name ag)) "]))"))}))]
    (.mkdirs (java.io.File. tasks))
    (spit (str tasks "/" tns ".edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]))
    (try
      (let [boot! (fn [] (let [root (ts/start-root! root0)]
                           (when-not (:port root) (throw (ex-info (str "the root did not listen: " (:log root)) {})))
                           root))
            r1 (boot!)
            _ (ts/ask r1 {:op :eval :agent "root" :code (str "(my.root/load-tasks! sys " (pr-str tasks) ")")})
            before (names-of r1)
            _ (ts/stop-root! r1)
            r2 (boot!)
            after (try (names-of r2) (finally (ts/stop-root! r2)))
            key-of #(some-> % name)
            all-names (fn [m] (map pr-str (concat [(:attached m) (:task m) (:address m)] (:view m) (:listed m))))]
        (is (some? (:attached before)) (pr-str before))
        (is (= (key-of (:attached before)) (:address before)) "the page's address is the agent's key, its id's name")
        (is (= (key-of (:attached before)) (key-of (:attached after))) "the same agent after the restart")
        (is (= (key-of (:attached after)) (:address after)) "and its page address")
        (is (= (:attached after) (:task after)) "and the task's agent")
        (is (some #{(:attached after)} (:view after)) "and root's view")
        (is (some #{(:attached after)} (:listed after)) "and the server's agents list")
        (is (not-any? #(re-find #"^:?a\d+$" %) (map #(str/replace % "\"" "") (concat (all-names before) (all-names after))))
            "no id of a counter's form"))
      (finally (sh/sh "rm" "-rf" tasks runs)
               (ts/drop-main! (:store root0) main)))))
