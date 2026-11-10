(ns seon.root-test
  "Root as a whole process, run as the built binary (target/seon, bin/build seon): a root
  boots its fork server, so it runs in a process of its own rather than in the test
  runner's, whose threads and GC sweepers are the runner's."
  (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.db :as pdb] [datahike.api :as d] [seon.test-store :as ts] [seon.config :as config] [seon.contract :as contract]
            [seon.root :as root] [seon.program :as program] [my.test :as mt]))

(defn- test-store "The one store these tests' roots take a branch of (seon.test-store/process-store)." []
  (ts/process-store))

(def ^:private boot-then-boot-again
  "One cycle and a second boot in a process of its own (./seon boot-twice, seon.check/boot-twice: start!, three agents
  evaluated, stop!, then start! again), on a main of its own on the test store, deleted at once: {:exit :out :err :res},
  res its last line read (root-threads-are-what-root-declares reads it)."
  (delay
    (let [main (str "root-test-" (random-uuid))
          r (ts/binary-sh "env" "SEON_WEB_PORT=0" (str "SEON_STORE=" (test-store)) (str "SEON_MAIN=" main)
                          "./seon" "boot-twice" "--profile" (name (config/profile)) :dir "target")]
      (ts/drop-main! (test-store) main)
      (assoc r :res (edn/read-string (last (str/split-lines (:out r))))))))

(deftest root-threads-are-what-root-declares
  ;; one start!/stop! cycle, in a process of its own (a process boots root once,
  ;; a-second-boot-in-one-process-is-refused): while running (three agents just
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
  ;; instead (Jolt's jolt-fiber-pool-started? check in jolt.host/main-carrier-run!, the first thing boot!
  ;; does, before the store is touched), and nothing is forked. Asked of a root that has booted: the
  ;; shard's shared root, whose REPL starts one more (docs/testing.md#a-root-costs-what-it-boots)
  (let [root (ts/shared-root! ["--set" (str ":seon.config.source/root=" (pr-str (config/checkout)))])
        ask #(ts/value-of (:response (ts/ask root {:op :eval :agent "root" :code %})))
        server "(get-in (c/state sys) [:fork-server :pid])"
        before (ask server)
        second-boot (ask "(try (seon.root/start! {}) :booted (catch Throwable e (ex-message e)))")
        after (ask server)]
    (is (re-find #"fibers already run on carrier threads" (str second-boot)) (pr-str second-boot))
    (is (pos-int? before))
    (is (= before after) "and nothing was forked: root's fork server is the one it had")))

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
  request, then STARTED and bin/root start's last line, then EXIT and its log. opts: :args more root arguments, :env more variables."
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
     (str (str/join "\n" (map pr-str answers)) (when (seq answers) "\n") "STARTED " (:started root) "\n" "EXIT " (or (:exit stopped) (pr-str stopped)) "\n" (:log stopped)))))

(def ^:private bare
  "Root arguments for a root whose subject is its store, not the program or the wiki: it imports no source and
  loads no page: for a store of its own, which holds neither (the checkout's 4,500 forms and 39 pages are 15-20 s of its boot; on a seeded store the same arguments would retract both, 14-32 s,
  docs/testing.md#a-root-costs-what-it-boots). Its agents cite none of the pages it does not load: a cite names what
  exists (docs/references.md#an-entity-cites), so every agent's standing cites are none here."
  ["--set" ":seon.config.source/dirs=[]" "--set" ":seon.config.root/wiki-dirs=[]" "--set" ":seon.config.agent/cites=[]"])

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
        held (ev "[(count (seon.task/tasks @(:conn sys))) (set (keys (seon.task/agents @(:conn sys))))]")
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
      (let [[tasks-1 agents-1] (printed (nth first-boot 3))
            [tasks-2 agents-2] (printed (second second-boot))]
        (is (= [1 1] [tasks-1 (count agents-1)]) "the first boot holds the one task it loaded (a file task is one whole task) and the agent it started")
        (is (= 1 tasks-2) "the second boot still holds the task")
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
  ;; rewrote the checkout's task files, and a load read them. A test root's task dir is its own
  ;; (seon.test-store/start-root!), so its loads read its own files and never the checkout's; and a boot
  ;; that resumes a main reads none (task seon.root/a-boot-resumes-from-the-db): only a load a person asks
  ;; for (my.root/load-tasks!) reads them. Asserted through the root's own reads, with no write to the checkout.
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
      (is (= "src/a.clj" (ask "((resolve 'seon.ref/line-citation) \"src/a.clj:50\")")) "the built root reads a path:line citation")
      ;; a boot loads nothing of main's program (docs/source.md#a-boot-resumes-from-the-db), so the binary holds every
      ;; namespace of src: my.test, which no namespace of the binary required, was loaded by the boot's load-all! before
      (is (= [] (ask "(vec (for [f (file-seq (java.io.File. (str (seon.config/checkout) \"/src\"))) :let [p (.getPath f)] :when (.endsWith p \".clj\") :let [n (symbol (-> p (subs (count (str (seon.config/checkout) \"/src/\"))) (clojure.string/replace #\"\\.clj$\" \"\") (clojure.string/replace \"/\" \".\") (clojure.string/replace \"_\" \"-\")))] :when (not (find-ns n))] n))"))
          "the binary holds every namespace of src")
      (is (false? (ask "(contains? (seon.task/tasks @(:conn sys)) \"demo/t\")")) "the boot resumed the seeded main and read no task file")
      ;; the read's outcome is root's :seon.root/tasks, the subject a refused read names; an unchanged good read writes nothing
      (is (= [{:ok true :files 1} true {:files 1} {:ok true :changed 0 :files 1}]
             (ask "(let [r (my.root/load-tasks! sys)] [(select-keys r [:ok :files]) (contains? (seon.task/tasks @(:conn sys)) \"demo/t\") (:seon.root/tasks (datahike.api/pull @(:conn sys) [:seon.root/tasks] [:seon.root/id :root])) (my.root/load-tasks! sys)])"))
          "a load a person asks for reads its own dir's one whole task, records the read's outcome, and a read that finds it so writes nothing")
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

(def ^:private boot-probe
  "What a boot of `boots-on-a-tree` answers: the config key and the schema entry the trees change, as root holds them; the
  t of this boot's fact (:seon.root/boot, written once the graph runs, after the fork server forked), of main's
  newest program fact (config and schema entries) and of the seed's index (the first :seon.root/source fact in main's
  history: the seed writes it with the program, in one transaction); the source fact, and the stale-binary rows health
  derives from it."
  {:op :eval :agent "root"
   :code (str "(let [db @(:conn sys) root (:db/id (datahike.api/entity db [:seon.root/id :root]))"
              " t-of (fn [a] (reduce max 0 (map :tx (datahike.api/datoms db :aevt a))))]"
              " {:streams-buf (seon.config/value :seon.config.web/streams-buf)"
              " :schema-changed? (contains? (seon.program/schema-changes db) :seon.config.web/streams-buf)"
              " :boot-t (:tx (first (datahike.api/datoms db :eavt root :seon.root/boot)))"
              " :program-t (max (t-of :config/form) (t-of :schema/form))"
              " :seed-t (reduce min (map :tx (datahike.api/datoms (datahike.api/history db) :aevt :seon.root/source)))"
              " :fact (dissoc (:seon.root/source (datahike.api/pull db [:seon.root/source] root)) :boot :inputs :ms)"
              " :stale (mapv (juxt :binary :tree) (get-in (my.root/problems sys) [:classes :stale-binary :rows]))})")})

(defn- boots-on-a-tree
  "A store of its own (removed at the end) seeded (./seon seed-program) from a scratch repository under target/ holding
  the one file path (dirs resources/seon) as its first text, and with :wiki a page's text at docs/zz-broken.md; then
  boot-probe's answer at each boot of a root on it, the file committed with the next of texts before each (the same text
  commits nothing): a main behind its tree, as main is between a landing in git and its bridge's merge (bin/land), on a
  binary built from another program (this checkout's). Answers [the seed's output answer ...], a boot that never answered
  its output. opts: :also, more requests (answers in :also). Each answer's :listening? (its log says ROOT LISTENING) and
  :tree (the tree's path in the checkout). A few seconds a boot. See docs/source.md#a-boot-resumes-from-the-db."
  ([path texts] (boots-on-a-tree path texts {}))
  ([path [seeded & texts] {:keys [also wiki]}]
   (let [rel (str "target/tmp/root-test-boot-" (random-uuid))
         tree (str (config/checkout) "/" rel)
         store (str tree "-store")
         git (fn [& args] (apply sh/sh "git" "-C" tree "-c" "user.name=t" "-c" "user.email=t@localhost" args))
         args ["--set" (str ":seon.config.source/root=" (pr-str tree)) "--set" ":seon.config.source/dirs=[\"resources/seon\"]"
               "--set" (str ":seon.config.root/wiki-dirs=" (pr-str (if wiki [(str rel "/docs")] [])))
               ;; the tree's own task files (none): the checkout's cite the whole program, which this tree's is not
               "--set" (str ":seon.config.root/tasks-dir=" (pr-str (str tree "/resources/seon/tasks")))]
         ;; an answer's value, or the line as it is when it holds none (an error)
         read-line (fn [l] (let [v (ts/value-of (:response (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil))))] (if (some? v) v l)))]
     (.mkdirs (.getParentFile (java.io.File. (str tree "/" path))))
     (sh/sh "git" "init" "-q" tree)
     (try
       (when wiki
         (.mkdirs (java.io.File. (str tree "/docs")))
         (spit (str tree "/docs/zz-broken.md") wiki))
       (spit (str tree "/" path) seeded)
       (git "add" "-A")
       (git "commit" "-q" "-m" "seeded")
       (into [(:out (apply ts/binary-sh (concat ["./seon" "seed-program" store] args ["--profile" (name (config/profile)) :dir "target"])))]
             (for [text texts]
               (let [_ (spit (str tree "/" path) text)
                     _ (git "add" "-A")
                     _ (git "commit" "-q" "-m" "tree")
                     out (root-answers store "main" (into [boot-probe] also) {:args args})
                     [probe & more] (str/split-lines out)
                     v (read-line probe)]
                 (sh/sh "rm" "-f" (str store "-in"))
                 (if (map? v)
                   (assoc v :also (mapv read-line (take (count also) more)) :listening? (str/includes? out "ROOT LISTENING") :tree rel
                          :started (some #(when (str/starts-with? % "STARTED ") (subs % 8)) more))
                   out))))
       (finally (sh/sh "rm" "-rf" tree store (str store "-agents") (str store "-in") (str store ".log")))))))

(deftest a-boot-on-a-binary-of-another-program-holds-mains-config
  ;; task seon.root/a-boot-resumes-from-the-db, THE GAP: a landing moves git first, and its bridge's merge brings main in
  ;; the db after it (bin/land), so a boot between them finds main behind its tree and its binary built from another
  ;; program. Here a merge adds a config key root reads at start (:seon.config.web/streams-buf, read for a :bind/ buffer,
  ;; as 191e4b56ba did): main holds the tree before it (the key at another value: a missing one refuses the seed, as it
  ;; would a boot, seon.config's closed), and the tree has the binary's. The boot reads neither the tree nor the commit: it
  ;; holds main's config, writes no program fact, and says its binary is another program than main's, a problem fact
  ;; (seon.problems/stale-binary), at each boot until bin/update rebuilds it. Red before: the boot imported the commit
  (let [file "resources/seon/config/default.edn"
        ;; the shipped file as the binary was built
        merged config/built-text
        before (str/replace merged #"(:seon\.config\.web/streams-buf )(\d+)" (fn [[_ k v]] (str k (inc (parse-long v)))))
        [seed two three] (boots-on-a-tree file [before merged merged])]
    (is (not= before merged))
    (is (str/includes? (str seed) "SEED OK") (str seed))
    (is (map? two) (str "the root booted on a main behind its tree: " two))
    (is (= (inc (config/value :seon.config.web/streams-buf)) (:streams-buf two)) "it holds main's value, not the tree's or the binary's")
    (is (= (:seed-t two) (:program-t two)) "the boot wrote no program fact: main's program is the seed's")
    (is (< (:program-t two) (:boot-t two)))
    (is (= 1 (count (:stale two))) (str "its binary is of another tree than main's: one stale-binary row " (pr-str (:stale two))))
    (is (= (second (first (:stale two))) (:tree (:fact two))) "naming the tree main was last brought to")
    (is (= (:seed-t three) (:program-t three)) "the next boot neither")
    (is (= (:stale two) (:stale three)) "and the row stands while the binary does")))

(deftest what-agents-merged-into-main-is-no-stale-binary
  ;; docs/source.md#a-boot-resumes-from-the-db (the db is the source of truth): root refuses spawns only when its binary was built
  ;; from another git tree than the one main was last brought to from outside (an import or a landing: :tree on the source
  ;; fact). Main moved since by what its agents merged is main's own: its program differs from the binary's, its tree does
  ;; not, and spawns go on. Red before: the refusal compared main's program with the binary's, and refused e2e-loop's root
  ;; after its agent merged, and a second boot of a store whose agents had worked (2026-11-10, the batch's prove)
  (let [built (root/binary-program)
        db0 (mt/world)
        on (fn [db tree] (d/db-with db [{:seon.root/id :root :seon.root/source {:root "/c" :files 1 :forms 1 :ms 1 :tree tree}}]))
        merged (mt/with db0 (mt/code 'app.merged "(ns app.merged)\n(defn f [x] x)"))]
    (is (string? (get-in built [:inputs :tree])) (str "bin/build recorded the tree it was built from: " (pr-str (:program built))))
    (is (not= (program/program-digest db0) (program/program-digest merged)) "the merge moved main's program")
    (is (nil? (root/stale-binary (on merged (get-in built [:inputs :tree])))) "main of the binary's tree with an agent's merge on it: no refusal")
    (is (= [(get-in built [:inputs :tree]) "another-tree"] ((juxt :binary :tree) (root/stale-binary (on db0 "another-tree"))))
        "a store last brought to a tree git cannot diff against the binary's: not current (unknown is never current), naming both")
    (is (nil? (root/stale-binary db0)) "a store brought from no tree: nothing known, nothing refused")
    ;; a landing (a pin bump among them: a gitlink is in the tree id) moves the fact to the landed tree, which the binary
    ;; bin/update builds from it records: the store last brought to the old tree agrees with the new binary once it lands
    (let [old (on merged "the-tree-before")
          landed (d/db-with old (root/landed-tx old {} {} (get-in built [:inputs :tree]) "c1"))]
      (is (some? (root/stale-binary old)) "before the landing's bridge: the new binary is of another tree")
      (is (nil? (root/stale-binary landed)) "after it: the landed tree is the binary's, nothing refused"))))

(def ^:private spawn-on-it
  "A request to root: spawn an agent and answer its id, or the refusal's [type message stale-binary] when the spawn is
  refused; the agent, if one was made, is ended after."
  {:op :eval :agent "root"
   :code (str "(try (let [ag (seon.root/spawn! sys {})] (seon.root/kill! sys ag) [:spawned ag])"
              " (catch clojure.lang.ExceptionInfo e (let [d (ex-data e)] [(:seon.error/type d) (ex-message e) (some? (:binary d))])))")})

(deftest a-root-on-a-binary-of-another-program-forks-no-agent
  ;; task seon.root/a-boot-resumes-from-the-db, the owner's rule (2026-11-10: "dbboot is supposed to resume if the store is
  ;; already there ... make sure this rule holds"): a store made by tree A (here a schema entry, :seon.config.web/streams-buf
  ;; with another bound, seeded) under the binary of tree B. Root resumes it and stays up, but forks no agent: an agent
  ;; would load main's forms over the binary's code and run the two mixed (2026-11-10, Web's and Context's roots: old
  ;; contracts against new callers, a fork server lost on its first fork). The refusal names the acts that bring them
  ;; into agreement: erase the store, reindex from disk, or bin/root next. Red before: the agent forked, holding main's entry
  (let [file "resources/seon/schemas/seon.config.web.edn"
        form (get contract/forms :seon.config.web/streams-buf)
        merged (pr-str {:seon.config.web/streams-buf form})
        before (pr-str {:seon.config.web/streams-buf (update form 1 assoc :min 2)})
        [seed two] (boots-on-a-tree file [before merged] {:also [spawn-on-it]})
        [type message stale?] (first (:also two))]
    (is (not= before merged))
    (is (str/includes? (str seed) "SEED OK") (str seed))
    (is (true? (:listening? two)) (str "root resumed the store and listens: " two))
    (is (= :seon.root/refused type) (str "the spawn is refused: " (pr-str (first (:also two)))))
    (is stale? "naming the two trees")
    (doseq [act ["rm -rf runs/store" "bin/root start --reindex" "my.root/import!" "bin/root next"]]
      (is (str/includes? (str message) act) (str "its message names " act))
      ;; docs/source.md#files-and-the-db-two-directions: a mismatch is never silent to a person
      (is (str/includes? (str (:started two)) act) (str "and bin/root start's last line names it too: " (:started two))))
    (is (str/starts-with? (str (:started two)) "ROOT STALE BINARY") (str "bin/root start ends on the mismatch: " (:started two)))
    (is (true? (:schema-changed? two)) "main's entry differs from the binary's")
    (is (= (:seed-t two) (:program-t two)) "and the boot wrote no program fact")))

(def ^:private broken-boot
  "boots-on-a-tree on a seeded tree whose next commit breaks its schema resource (it no longer reads): the seed's output
  and the one boot's answer."
  (delay
    (let [file "resources/seon/schemas/seon.config.web.edn"
          merged (pr-str {:seon.config.web/streams-buf (get contract/forms :seon.config.web/streams-buf)})]
      (boots-on-a-tree file [merged (str merged "\n{:unclosed")]))))

(deftest a-boot-reads-no-file-a-commit-broke
  ;; task seon.root/a-boot-resumes-from-the-db: the tree's schema resource no longer reads (a commit broke it). The boot
  ;; reads no file, so nothing of it is refused or written: root starts on main as it was (docs/source.md#a-file-that-does-not-load
  ;; is a seed's or a re-index's). Red before: the boot read the tree and recorded the refusal on main's source fact
  (let [[seed two] @broken-boot]
    (is (str/includes? (str seed) "SEED OK") (str seed))
    (is (map? two) (str "the root started: " two))
    (is (nil? (:error (:fact two))) (pr-str (:fact two)))
    (is (= (:seed-t two) (:program-t two)) "nothing of the tree was written: main's program is the seed's")
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
