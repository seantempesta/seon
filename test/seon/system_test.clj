(ns seon.system-test
  "Root as a whole process, run as the built binary (target/seon, bin/build seon): a root
  boots its fork server, so it runs in a process of its own rather than in the test
  runner's, whose threads and GC sweepers are the runner's."
  (:require [seon.lib] [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.db :as pdb] [datahike.api :as d] [seon.test-store :as ts] [seon.config :as config] [seon.contract :as contract]
            [seon.system :as root] [seon.program :as program] [my.test :as mt]))

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
  ;; is the one thread (seon.system/boot!); its threads (Jolt's carriers, timer, poller) outlive
  ;; stop!, so a second start! in that process would fork beside them. It is refused by name
  ;; instead (Jolt's jolt-fiber-pool-started? check in jolt.host/main-carrier-run!, the first thing boot!
  ;; does, before the store is touched), and nothing is forked. Asked of a root that has booted: the
  ;; shard's shared root, whose REPL starts one more (docs/testing.md#a-root-costs-what-it-boots)
  (let [root (ts/shared-root! ["--set" (str ":seon.config.source/root=" (pr-str (config/checkout)))])
        ask #(ts/value-of (:response (ts/ask root {:op :eval :agent "root" :code %})))
        server "(get-in (c/state sys) [:fork-server :pid])"
        before (ask server)
        second-boot (ask "(try (seon.system/start! {}) :booted (catch Throwable e (ex-message e)))")
        after (ask server)]
    (is (re-find #"fibers already run on carrier threads" (str second-boot)) (pr-str second-boot))
    (is (pos-int? before))
    (is (= before after) "and nothing was forked: root's fork server is the one it had")))

;; docs/clocks.md#time-decides-the-owners-exceptions: a run ended past its bound takes its Seon state first (bin/evidence asks it of
;; root's server, read-only): root's stall check (::stall-armed ::busy ::lost), each agent proc's (:inflight :fx-out :stall-seen
;; :last-frame-ms), and which procs answered the ping, so a proc mid-step is seen missing, never read as nil
(deftest roots-session-and-what-root-releases-are-the-users
  ;; docs/users.md#who-started-whom (owner, 2026-11-11: one open graph of agents): the agents are one tree rooted at the user.
  ;; Root's session is started by the user (seon.system/root-agent-tx), and a task released at root's REPL is started by its
  ;; evaluator, the user (my.root/run!), so its agent joins the user's :seon.agent/agents at its start. Red before: neither
  ;; wrote a starter, and those agents were reached only through :seon.agent/root
  (let [user (seon.config/value :seon.config.root/user-name)
        db0 (d/db-with (ts/test-db) [{:seon.system/id :root}])
        db (d/db-with db0 (root/root-agent-tx db0))]
    (is (= user (get-in (d/entity db [:seon.task/id "request/root"]) [:seon.task/started-by :seon.agent/key])) "root's session's task was started by the user")
    (is (some #{"root"} (map :seon.agent/key (:seon.agent/agents (d/entity db [:seon.agent/key user])))) "and the user's tree holds root's session"))
  (let [root (ts/shared-root! ["--set" (str ":seon.config.source/root=" (pr-str (config/checkout)))])
        ns (str "started" (seon.lib/unique-id))
        resp (:response (ts/ask root {:op :eval :agent "root"
                                                :code (str "(do (my.root/create! sys [{:seon.task/ns '" ns " :seon.task/name \"t\" :seon.task/plan \"Work.\" :seon.task/kind :work :seon.task/proofs #{}}])"
                                                           " (my.root/run! sys [\"" ns "/t\"])"
                                                           " (let [by (:seon.task/started-by (datahike.api/pull @(:conn sys) [{:seon.task/started-by [:seon.agent/key]}] [:seon.task/id \"" ns "/t\"]))]"
                                                           "   (my.root/cancel! sys [\"" ns "/t\"]) by))")}))
        r (ts/value-of resp)]
    (is (= {:seon.agent/key (seon.config/value :seon.config.root/user-name)} r) (str "a task released at root's REPL is the user's: " (pr-str resp)))))

(deftest a-hangs-evidence-holds-roots-stall-state
  (let [root (ts/shared-root! ["--set" (str ":seon.config.source/root=" (pr-str (config/checkout)))])
        ;; with an agent of its own running, so an agent proc's state is read, then ended
        r (ts/value-of (:response (ts/ask root {:op :eval :agent "root"
                                                :code "(let [ag (seon.system/spawn! sys {}) s (seon.system/stall-state sys)] (seon.system/kill! sys ag) s)"})))]
    (is (map? (:root r)) (pr-str r))
    (is (every? #{:seon.system/stall-armed :seon.system/busy :seon.system/lost} (keys (:root r))) "root's stall check, nothing else of its state")
    ;; red before (batch 32's problems overrun): the api's ping answered only :agents :closing :pending, so :root was {} on every
    ;; root and a hang's evidence never showed whether the stall check was armed or which agents it held busy
    (is (boolean? (:seon.system/stall-armed (:root r))) "the armed flag, read whatever its value")
    (is (set? (:seon.system/busy (:root r))) "the agents with an eval in flight, empty or not")
    (is (map? (:agents r)) "each agent proc's, by pid")
    (is (and (seq (:agents r)) (every? #(and (contains? % :stall-seen) (contains? % :last-frame-ms)) (vals (:agents r)))) "with what its stall check reads")
    (is (some #{:api} (:answered r)) "and the procs that answered, root's api among them")))

(deftest a-kill-is-answered-once-the-agents-end-is-recorded
  ;; docs/processes.md#the-agent-process: kill! answers the agent's view entry when its end is recorded, so a wait on it ends;
  ;; a wait on an end event read after a tap could miss it and never end (with-agent's teardown, 300 s, Context 2026-11-12).
  ;; Red before: kill! answered nil at once, the agent still :killed with no :ended-ms
  (let [root (ts/shared-root! ["--set" (str ":seon.config.source/root=" (pr-str (config/checkout)))])
        r (ts/value-of (:response (ts/ask root {:op :eval :agent "root"
                                                :code (str "(let [ag (seon.system/spawn! sys {}) a @(seon.system/kill! sys ag) b @(seon.system/kill! sys ag)]"
                                                           " [(select-keys a [:status]) (some? (:ended-ms a)) (= a b) (set (keys a))"
                                                           "  (:seon.error/type @(seon.system/kill! sys :no-such-agent))])")})))]
    ;; its answer is the end, {:status :ended-ms}, never the whole entry, which gains :exit when the fork server reaps it (a second
    ;; kill answered another map in a shard: Context, 2026-11-12)
    (is (= [{:status :killed} true true #{:status :ended-ms} :seon.system/no-agent] r) (pr-str r))))

(deftest the-sweeper-pool-is-fixed-before-anything-collects
  ;; task seon.system/the-sweeper-pool-is-fixed-before-anything-collects: six threads allocate through 30
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
  request, then STARTED and bin/system start's last line, then EXIT and its log. opts: :args more root arguments, :env more variables."
  ([path main requests] (root-answers path main requests {}))
  ([path main requests {:keys [args env]}]
   ;; runs: this main's own, beside the store (every root-answers call on the one test store
   ;; shared path-runs, and a test asked another test's root: task seon.test-store/a-test-root-is-its-own)
   (let [runs (str path "-" main "-runs")
         root (ts/start-root! (cond-> {:store path :main main :runs runs :args (vec args)} env (assoc :env env)))
         answers (when (:port root) (mapv #(ts/ask root %) requests))
         stopped (ts/stop-root! root)]
     (seon.lib/remove-tree! runs)
     ;; each answer as the data it is (an error value among them), one per line
     (str (str/join "\n" (map pr-str answers)) (when (seq answers) "\n") "STARTED " (:started root) "\n" "EXIT " (or (:exit stopped) (pr-str stopped)) "\n" (:log stopped)))))

(def ^:private bare
  "Root arguments for a root whose subject is its store, not the program or the wiki: it imports no source and
  loads no page: for a store of its own, which holds neither (the checkout's 4,500 forms and 39 pages are 15-20 s of its boot; on a seeded store the same arguments would retract both, 14-32 s,
  docs/testing.md#a-root-costs-what-it-boots). Its agents cite none of the pages it does not load: a cite names what
  exists (docs/references.md#an-entity-cites), so every agent's standing cites are none here."
  ["--set" ":seon.config.source/dirs=[]" "--set" ":seon.config.root/wiki-dirs=[]" "--set" ":seon.config.agent/cites=[]"])

(deftest a-root-resumes-a-store-an-earlier-build-wrote
  ;; task seon.system/a-root-resumes-a-store-an-earlier-build-wrote: the store as builds of
  ;; 2026-10-21 before CONFIG merged left it (next/runs/store-full-20261021, read 17:35): its
  ;; schema lacks the attributes added since (the config's, the model manifest's, the source's), and its main's
  ;; root entity holds a quiescent fact and no boot (their boot fact was refused), which
  ;; seon.task/entity-value could not read. The built root resumes that main and answers a
  ;; root eval and {:op :agents}. A store on disk of this test's own, the last resort: what
  ;; is proved is a kept store another build wrote; removed at the end.
  (let [checkout (seon.config/checkout)
        path (str checkout "/target/root-test-resume-" (random-uuid))
        later? #(or (str/starts-with? (namespace (:db/ident %)) "seon.config") (= "seon.ai.model" (namespace (:db/ident %)))
                    (#{:ns/file :ns/tail :form/lead :form/source :seon.task/commit} (:db/ident %)))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database cfg)
        c (pdb/connect cfg)
        _ (d/transact c {:tx-data (vec (remove later? pdb/schema))})
        _ (doseq [tx (pdb/seed-tx 110)] (d/transact c {:tx-data tx}))
        _ (d/branch! c :db :old-run)
        c2 (pdb/connect (assoc cfg :branch :old-run))
        _ (d/transact c2 {:tx-data [{:seon.system/id :root :seon.system/quiescent {:t 536870914 :root-causes []}}]})
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
      (finally (seon.lib/remove-tree! path (str path "-in") (str path ".log") (str path "-agents"))))))

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
                            :code (str "(let [ev (seon.system/events sys) ag (seon.system/spawn! sys {:branch :kept})]"
                                       " (loop [] (let [e (clojure.core.async/<!! ev)]"
                                       " (cond (not= ag (:agent e)) (recur)"
                                       " (= :ready (:type e)) (:text @(seon.system/submit! sys ag \"(contains? (datahike.api/schema (seon.agent/db)) :form/keywords)\"))"
                                       " (#{:dead :ended :killed} (:type e)) (:type e)"
                                       " :else (recur)))))")}]
                          {:args bare})
        answer (fn [l] (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))]
    (try
      (is (str/includes? (str (answer (first (str/split-lines out)))) ":value true")
          (str "the agent on the branch cut before :form/keywords is ready, its branch holding the attribute: " (subs out 0 (min 2000 (count out)))))
      (finally (seon.lib/remove-tree! path (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest a-second-boot-resumes-the-same-main
  ;; task seon.system/one-main-resumed: a root started with no main named works on the
  ;; config's main ("main"), and a second root on the same store resumes it: the task the
  ;; first loaded and the run it started are still there. A store of this test's own, the
  ;; last resort: what is proved is the default main of a store, which a shared test store's
  ;; other users would also resume; removed at the end.
  ;; And every end of a root is a fact, on the same two boots (it was a test of its own,
  ;; two more boots on a clone, 70 s): a clean stop is a fact naming its cause (:seon.system/stopped; here
  ;; its stdin closes, as bin/system stop closes it), the next boot records it as :seon.system/previous-end, and
  ;; a boot that left no stop fact (SIGKILL, a crash) is found :unclean. Who started a boot is that boot's
  ;; (:seon.system/started-by): the test running start-root!, or the last --started-by its arguments name
  (let [checkout (seon.config/checkout)
        path (str checkout "/target/runs/root-test-main-" (random-uuid))
        tasks (str path "-tasks")
        _ (.mkdirs (java.io.File. tasks))
        _ (spit (str tasks "/demo.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]))
        ev (fn [code] {:op :eval :agent "root" :code code})
        main (ev "(name (seon.db/conn-main (:conn sys)))")
        held (ev "[(count (seon.task/tasks @(:conn sys))) (set (keys (seon.task/agents @(:conn sys))))]")
        tree-b (keyword (str "tree-" (apply str (repeat 40 "a"))))
        by (ev "(:started-by (seon.task/root @(:conn sys)))")
        ;; every name the interfaces give an agent: root's view and the page's address (its key, seon.render.ui/agent-address)
        view (ev "(vec (sort (keys (:agents (seon.system/state sys)))))")
        addresses (ev "(mapv #(seon.render.ui/agent-address (d/pull @(:conn sys) '[:seon.agent/key] [:seon.agent/key %])) (keys (seon.task/agents @(:conn sys))))")
        first-boot (str/split-lines (root-answers path "main" [main (ev (str "(my.root/load-tasks! sys " (pr-str tasks) ")"))
                                                           {:op :attach :task "demo/t"} held (ev "(:boot sys)") by
                                                           {:op :agents} view addresses
                                                           ;; a tree's branch whose last run this root's end cuts short
                                                           (ev (str "@(#'seon.system/write! sys {:op :branch-for-tree :branch " tree-b " :tx []})"))]
                                                  {:args bare}))
        second-boot (str/split-lines (root-answers path "main" [main held
                                                                (ev "(:seon.system/previous-end (d/pull @(:conn sys) [:seon.system/previous-end] [:seon.system/id :root]))")
                                                                (ev "(#'seon.system/previous-end (d/db-with @(:conn sys) [{:seon.system/id :root :seon.system/boot \"killed-boot\"}]))")
                                                                by (ev "(= [\"--started-by\" \"seon.system-test/another\"] (vec (take-last 2 (my.root/resume-args sys))))")
                                                                {:op :attach :task "demo/t"} {:op :agents} view addresses
                                                                (ev "[(get-in (seon.system/state sys) [:boot :dropped-trees]) (filterv seon.db/tree-branch? (d/branches (:conn sys)))]")]
                                                   {:args (into bare ["--started-by" "seon.system-test/another"])}))
        raw (fn [l] (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))
        printed (fn [l] (ts/value-of (:response (raw l))))]
    (try
      (is (= "main" (printed (first first-boot))) "a boot with no main named works on the config's main")
      (is (= "main" (printed (first second-boot))) "the second boot resumes the same main")
      (let [[tasks-1 agents-1] (printed (nth first-boot 3))
            [tasks-2 agents-2] (printed (second second-boot))]
        ;; two tasks: the one it loaded (a file task is one whole task) and root's session's, made at its boot (seon.system/root-agent-boot-tx)
        (is (= [2 1] [tasks-1 (count agents-1)]) "the first boot holds the task it loaded, root's session's, and the agent it started")
        (is (= 2 tasks-2) "the second boot still holds both, its boot making no second session")
        (is (every? agents-2 agents-1) "and the first boot's agent (its task is reaped, and started again by the next agent of the session)"))
      (let [b1 (printed (nth first-boot 4))
            [prev killed by2 resume] (map printed (drop 2 second-boot))]
        (is (string? b1))
        (is (= "seon.system-test/a-second-boot-resumes-the-same-main" (printed (nth first-boot 5))) "the test that started the boot is its fact")
        (is (= "seon.system-test/another" by2) "and the next boot's is its own")
        (is (true? resume) "and the generation after it carries it")
        (is (= {:boot b1 :cause :stdin-eof} (select-keys prev [:boot :cause]))
            "the second boot found the first's clean end: its stdin closed")
        (is (= {:boot "killed-boot" :cause :unclean} (select-keys killed [:boot :cause]))
            "a boot that left no stop fact is found :unclean"))
      ;; docs/merging.md#a-session-proves-through-root (Deep fixes' review of jolt/treeprove): a root's end takes a tree run's agent
      ;; with it, so the run's drop never comes; the next boot drops every tree branch before any agent exists. Red before: kept
      (is (= {:ok true :exists false} (printed (nth first-boot 9))) "the first boot made the tree's branch")
      (is (= [[tree-b] []] (printed (nth second-boot 10))) "the second boot dropped it, and says so in its :boot view")
      ;; docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08: an agent's id is the keyword of its :seon.agent/key,
      ;; minted once (seon.system/start-agent) and kept by the session's next agent (seon.task/agent-key), so the restart leaves every
      ;; interface naming the task's agent as before: the attach, the task's agent, the server's agents list, root's view and the page's address
      (let [[attached agents-1 view-1 addresses-1] [(:agent (raw (nth first-boot 2))) (raw (nth first-boot 6)) (printed (nth first-boot 7)) (printed (nth first-boot 8))]
            [again agents-2 view-2 addresses-2] [(:agent (raw (nth second-boot 6))) (raw (nth second-boot 7)) (printed (nth second-boot 8)) (printed (nth second-boot 9))]
            all-names (fn [ag agents view addresses] (map #(str/replace (pr-str %) "\"" "") (concat [ag (get-in agents [:tasks "demo/t" :agent])] (keys (:agents agents)) view addresses)))]
        (is (keyword? attached) (pr-str (nth first-boot 2)))
        (is (some #{(name attached)} addresses-1) "the page's address is the agent's key, its id's name")
        (is (some #{attached} view-1) "and root's view names it")
        (is (= (name attached) (some-> again name)) (str "the same agent after the restart: " (nth second-boot 6)))
        (is (= again (get-in agents-2 [:tasks "demo/t" :agent])) "and the task's agent")
        (is (some #{again} (keys (:agents agents-2))) "and the server's agents list")
        (is (some #{again} view-2) "and root's view")
        (is (some #{(name again)} addresses-2) "and its page address")
        (is (not-any? #(re-find #"^:?a\d+$" %) (concat (all-names attached agents-1 view-1 addresses-1) (all-names again agents-2 view-2 addresses-2)))
            "no id of a counter's form"))
      (finally (seon.lib/remove-tree! path tasks (str path "-in") (str path ".log") (str path "-agents"))))))

(deftest a-root-stops-in-a-second
  ;; task seon.system/a-root-stops-in-a-second (measured 2026-11-10): every stop of a root less than ~35 s old took 26-32 s,
  ;; because its stop waited for the boot fork server to read the EOF of its socket, which a server still warming (preload!,
  ;; its tests' load) did not do until its warm-up was done. The stop ends the boot server's process group at once
  ;; (seon.fork-server/release!), and says so: stopped at once after its start, the root's answer names the group ended. Red
  ;; before: the server was left to read its EOF (no :ended)
  (let [checkout (seon.config/checkout)
        main (str "root-test-stops-" (random-uuid))
        runs (str checkout "/target/runs/" main "-runs")
        root (ts/start-root! {:store (ts/process-store checkout) :main main :runs runs})
        {:keys [exit log]} (ts/stop-root! root)
        i (clojure.string/index-of log "ROOT STOPPED ")
        stopped (when i (edn/read-string {:default tagged-literal} (subs log (+ i (count "ROOT STOPPED ")))))]
    (is (= 0 exit) log)
    (is (= :group-ended (get-in stopped [:drained :ended])) (str "the boot server's group was ended by the stop: " (pr-str stopped)))
    (is (true? (:graph stopped)) "every proc exited")
    ;; what the stop calls, named here so this test's digest takes it (a root's own code is the full run's otherwise)
    (is (= [:enum :group-ended :eof] (last (:malli/schema (meta #'seon.fork-server/release!)))) "release! answers how the server ended")))

(deftest a-test-roots-task-files-are-its-own
  ;; the second boot above read 810 tasks (2026-10-25): its root started seconds after a merge
  ;; rewrote the checkout's task files, and a load read them. A test root's task dir is its own
  ;; (seon.test-store/start-root!), so its loads read its own files and never the checkout's; and a boot
  ;; that resumes a main reads none (task seon.system/a-boot-resumes-from-the-db): only a load a person asks
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
      ;; the read's outcome is root's :seon.system/tasks, the subject a refused read names; an unchanged good read writes nothing
      (is (= [{:ok true :files 1} true {:files 1} {:ok true :changed 0 :files 1}]
             (ask "(let [r (my.root/load-tasks! sys)] [(select-keys r [:ok :files]) (contains? (seon.task/tasks @(:conn sys)) \"demo/t\") (:seon.system/tasks (datahike.api/pull @(:conn sys) [:seon.system/tasks] [:seon.system/id :root])) (my.root/load-tasks! sys)])"))
          "a load a person asks for reads its own dir's one whole task, records the read's outcome, and a read that finds it so writes nothing")
      (finally (ts/stop-root! root) (ts/drop-main! path main) (seon.lib/remove-tree! runs)))))

(def ^:private boot-probe
  "What a boot of `boots-on-a-tree` answers: the config key and the schema entry the trees change, as root holds them; the
  t of this boot's fact (:seon.system/boot, written once the graph runs, after the fork server forked), of main's
  newest program fact (config and schema entries) and of the seed's index (the first :seon.system/source fact in main's
  history: the seed writes it with the program, in one transaction); the source fact, and the stale-binary rows health
  derives from it."
  {:op :eval :agent "root"
   :code (str "(let [db @(:conn sys) root (:db/id (datahike.api/entity db [:seon.system/id :root]))"
              " t-of (fn [a] (reduce max 0 (map :tx (datahike.api/datoms db :aevt a))))]"
              " {:streams-buf (seon.config/value :seon.config.web/streams-buf)"
              " :schema-changed? (contains? (seon.program/schema-changes db) :seon.config.web/streams-buf)"
              " :boot-t (:tx (first (datahike.api/datoms db :eavt root :seon.system/boot)))"
              " :program-t (max (t-of :config/form) (t-of :schema/form))"
              " :seed-t (reduce min (map :tx (datahike.api/datoms (datahike.api/history db) :aevt :seon.system/source)))"
              " :fact (dissoc (:seon.system/source (datahike.api/pull db [:seon.system/source] root)) :boot :inputs :ms)"
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
       (finally (seon.lib/remove-tree! tree store (str store "-agents") (str store "-in") (str store ".log")))))))

(deftest a-boot-on-a-binary-of-another-program-holds-mains-config
  ;; task seon.system/a-boot-resumes-from-the-db, THE GAP: a landing moves git first, and its bridge's merge brings main in
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
  (let [built (program/binary-program)
        db0 (mt/world)
        on (fn [db tree] (d/db-with db [{:seon.system/id :root :seon.system/source {:root "/c" :files 1 :forms 1 :ms 1 :tree tree}}]))
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
   :code (str "(try (let [ag (seon.system/spawn! sys {})] (seon.system/kill! sys ag) [:spawned ag])"
              " (catch clojure.lang.ExceptionInfo e (let [d (ex-data e)] [(:seon.error/type d) (ex-message e) (some? (:binary d))])))")})

(deftest a-root-on-a-binary-of-another-program-forks-no-agent
  ;; task seon.system/a-boot-resumes-from-the-db, the owner's rule (2026-11-10: "dbboot is supposed to resume if the store is
  ;; already there ... make sure this rule holds"): a store made by tree A (here a schema entry, :seon.config.web/streams-buf
  ;; with another bound, seeded) under the binary of tree B. Root resumes it and stays up, but forks no agent: an agent
  ;; would load main's forms over the binary's code and run the two mixed (2026-11-10, Web's and Context's roots: old
  ;; contracts against new callers, a fork server lost on its first fork). The refusal names the acts that bring them
  ;; into agreement: erase the store, reindex from disk, or bin/system next. Red before: the agent forked, holding main's entry
  (let [file "resources/seon/schemas/seon.config.web.edn"
        form (get contract/forms :seon.config.web/streams-buf)
        merged (pr-str {:seon.config.web/streams-buf form})
        before (pr-str {:seon.config.web/streams-buf (update form 1 assoc :min 2)})
        [seed two] (boots-on-a-tree file [before merged] {:also [spawn-on-it]})
        [type message stale?] (first (:also two))]
    (is (not= before merged))
    (is (str/includes? (str seed) "SEED OK") (str seed))
    (is (true? (:listening? two)) (str "root resumed the store and listens: " two))
    (is (= :seon.system/refused type) (str "the spawn is refused: " (pr-str (first (:also two)))))
    (is stale? "naming the two trees")
    (doseq [act ["bin/remove-tree runs/store" "bin/system start --reindex" "my.root/import!" "bin/system next"]]
      (is (str/includes? (str message) act) (str "its message names " act))
      ;; docs/source.md#files-and-the-db-two-directions: a mismatch is never silent to a person
      (is (str/includes? (str (:started two)) act) (str "and bin/system start's last line names it too: " (:started two))))
    (is (str/starts-with? (str (:started two)) "ROOT STALE BINARY") (str "bin/system start ends on the mismatch: " (:started two)))
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
  ;; task seon.system/a-boot-resumes-from-the-db: the tree's schema resource no longer reads (a commit broke it). The boot
  ;; reads no file, so nothing of it is refused or written: root starts on main as it was (docs/source.md#a-file-that-does-not-load
  ;; is a seed's or a re-index's). Red before: the boot read the tree and recorded the refusal on main's source fact
  (let [[seed two] @broken-boot]
    (is (str/includes? (str seed) "SEED OK") (str seed))
    (is (map? two) (str "the root started: " two))
    (is (nil? (:error (:fact two))) (pr-str (:fact two)))
    (is (= (:seed-t two) (:program-t two)) "nothing of the tree was written: main's program is the seed's")
    (is (= (config/value :seon.config.web/streams-buf) (:streams-buf two)) "main's entries as they were")))

;; docs/users.md#roots-agent: a request's name is its first sentence's words, a dot inside a name kept, the same words asked again
;; numbered 2, 3 (it read "add-a-function-demo" from "demo.math/double", and a repeat took the transaction's number)
(deftest a-requests-name-is-its-first-sentence
  (let [db (ts/test-db)
        db2 (d/db-with db (seon.task/create-tx db [{:seon.task/ns 'request :seon.task/name "add-a-function-demo-math-double" :seon.task/plan "p"}]))]
    (is (= "add-a-function-demo-math-double" (seon.system/request-name db "Add a function demo.math/double. Then test it.")))
    (is (= "fix-it" (seon.system/request-name db "Fix it! Now.")))
    (is (= "work" (seon.system/request-name db "...")))
    (is (= "add-a-function-demo-math-double-2" (seon.system/request-name db2 "Add a function demo.math/double.")))))
