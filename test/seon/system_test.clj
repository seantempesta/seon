(ns seon.system-test
  "Root as a whole process, run as the built binary (target/seon, bin/build seon): a root
  boots its fork server, so it runs in a process of its own rather than in the test
  runner's, whose threads and GC sweepers are the runner's."
  (:require [seon.lib] [clojure.core.async] [jolt.io-poller] [clojure.core.async.flow :as flow] [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.db :as pdb] [datahike.api :as d] [seon.test-store :as ts] [seon.config :as config] [seon.contract :as contract]
            [seon.system :as root] [seon.program :as program] [seon.problems] [my.test :as mt]))

(defn- test-store "The one store these tests' roots take a branch of (seon.test-store/process-store)." []
  (ts/process-store))

;; docs/clocks.md#time-decides-the-owners-exceptions: a run ended past its bound takes its Seon state first (bin/evidence asks it of
;; root's server, read-only): root's stall check (::stall-armed ::busy ::lost), each agent proc's (:inflight :fx-out :stall-seen
;; :last-frame-ms), and which procs answered the ping, so a proc mid-step is seen missing, never read as nil
(deftest ^{:seon.test/estimate {:ticks 3000000}} roots-session-and-what-root-releases-are-the-users
  ;; docs/users.md#who-started-whom (owner, 2026-11-12: "Root is not special: it's the first agent; everyone descends from it"): root's
  ;; session is the first agent, with no parent, its task started by the user (seon.system/root-agent-tx); a task released at root's REPL
  ;; is started by its evaluator, the user (my.root/run!), so its agent joins the user's :seon.agent/_parent at its start. Red before: neither
  ;; wrote a starter, and those agents were reached only through :seon.agent/root
  (let [user (seon.config/value :seon.config.root/user-name)
        db0 (d/db-with (ts/test-db) [{:seon.system/id :root}])
        db (d/db-with db0 (root/root-agent-tx db0))]
    (is (= user (get-in (d/entity db [:seon.task/id "request/root"]) [:seon.task/started-by :seon.agent/key])) "root's session's task was started by the user")
    (is (nil? (:seon.agent/parent (d/entity db [:seon.agent/key "root"]))) "and root's session is the first agent: it has no parent"))
  ;; a task released at root's REPL (my.root/run!) is written with the release-tx: started by the user who evaluates there
  (let [tx (#'my.root/release-tx "demo/t")]
    (is (some #(= {:seon.task/id "demo/t" :seon.task/started-by (seon.task/sender (seon.config/value :seon.config.root/user-name))} %) tx)
        (str "a task released at root's REPL is the user's: " (pr-str tx)))))

;; Estimate: three api steps over a map, ~0.3M ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-kill-is-answered-once-the-agents-end-is-recorded
  ;; docs/processes.md#the-agent-process: kill! answers the agent's view entry when its end is recorded, so a wait on it ends;
  ;; a wait on an end event read after a tap could miss it and never end (with-agent's teardown, 300 s, Context 2026-11-12).
  ;; Red before: kill! answered nil at once, the agent still :killed with no :ended-ms. Proved on the api's state: the :kill op
  ;; holds the caller, end-agent (the agent's end recorded) answers it, a kill after the end answers the same end
  (let [st {:agents {:a1 {:branch :b}} :agent-procs {:a1 {}} :pending {} :rid 0 :boot "b" :seon.system/errors {}
            :view {:agents {:a1 {:branch :b :status :ready :pid nil :boot "b" :evals []}}}}
        o (volatile! {})
        p (promise)
        st1 (#'seon.system/ext-op st o {:op :kill :id :a1 :p p})
        before (deref p 0 :unanswered)
        st2 (#'seon.system/end-agent st1 o {:agent :a1 :pid nil})
        a (deref p 0 :unanswered)
        p2 (promise)
        _ (#'seon.system/ext-op st2 o {:op :kill :id :a1 :p p2})
        p3 (promise)
        _ (#'seon.system/ext-op st2 o {:op :kill :id :no-such-agent :p p3})]
    (is (= :unanswered before) "the kill holds its caller until the end is recorded")
    ;; its answer is the end, {:status :ended-ms}, never the whole entry, which gains :exit when the fork server reaps it
    (is (= #{:status :ended-ms} (set (keys a))) (pr-str a))
    (is (= :killed (:status a)))
    (is (some? (:ended-ms a)))
    (is (= a (deref p2 0 :unanswered)) "a second kill answers the same end")
    (is (= :seon.system/no-agent (:seon.error/type (deref p3 0 :unanswered))))))

(deftest the-sweeper-pool-is-fixed-before-anything-collects
  ;; task seon.system/the-sweeper-pool-is-fixed-before-anything-collects: six threads allocate through 30
  ;; collections in a fresh binary; Chez starts a sweeper per waiting thread and never stops one, so a
  ;; pool not fixed first ends at 6 (measured) and root-threads-are-what-root-declares reads whatever
  ;; its boot happened to start. seon.main/-main fixes the pool at 0 before anything runs: 0 sweepers.
  (let [{:keys [exit out err]} (ts/binary-sh "./seon" "sweepers-held" "--profile" ts/root-profile :dir "target")
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
         stopped (ts/stop-root! root)
         ;; a stop that did not exit 0 keeps its runs: bin/system wrote its evidence there (stop-overran-<pid>), named on the EXIT line
         kept? (not= 0 (:exit stopped))]
     (when-not kept? (seon.lib/remove-tree! runs))
     ;; each answer as the data it is (an error value among them), one per line
     (str (str/join "\n" (map pr-str answers)) (when (seq answers) "\n") "STARTED " (:started root) "\n" "EXIT " (or (:exit stopped) (pr-str stopped)) (when kept? (str " (runs kept: " runs ")")) "\n" (:log stopped)))))

(def ^:private bare
  "Root arguments for a root whose subject is its store, not the program or the wiki: it imports no source and
  loads no page: for a store of its own, which holds neither (the checkout's 4,500 forms and 39 pages are 15-20 s of its boot; on a seeded store the same arguments would retract both, 14-32 s,
  docs/testing.md#a-root-costs-what-it-boots). Its agents cite none of the pages it does not load: a cite names what
  exists (docs/references.md#an-entity-cites), so every agent's standing cites are none here."
  ["--set" ":seon.config.source/dirs=[]" "--set" ":seon.config.root/wiki-dirs=[]" "--set" ":seon.config.agent/cites=[]"])

;; Estimate: the test's own work is the store it builds, 110 seed transactions (~100k ticks each, ~11M: the measured 11.5M is nearly
;; all of it), and one binary spawn with its three answers (~2M ticks): 13,000,000 ticks
(deftest ^{:seon.test/root [] :seon.test/execs ["target/seon"] :seon.test/estimate {:ticks 13000000}} a-root-resumes-a-store-an-earlier-build-wrote
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
      (is (str/includes? out "EXIT 0") (str "and it stops when its stdin closes: " (subs out (max 0 (- (count out) 3000)))))
      (is (every? #(str/includes? out (str "ROOT STOP PHASE " %)) [":fact" ":drain" ":flow-stop"])
          "each part of its stop is in its log as it ends, so a stop killed past its bound names the part it was in")
      (finally (seon.lib/remove-tree! path (str path "-in") (str path ".log") (str path "-agents"))))))

;; Estimate: the same 110 seed transactions as the test above (~11M ticks), two branch cuts, a schema write and a sync (~5M), one
;; binary spawn and its answer (~2M): 18,000,000 ticks
(deftest ^{:seon.test/root [] :seon.test/execs ["target/seon"] :seon.test/estimate {:ticks 18000000}} an-agent-resumes-a-branch-cut-before-an-attribute
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

;; Estimate: two binary spawns (~0.5M ticks each to start, read and stop) and 22 asks read and parsed (~20k each), a task file
;; written: 2,000,000 ticks (measured 1.8M)
(deftest ^{:seon.test/root [] :seon.test/execs ["target/seon"] :seon.test/estimate {:ticks 2000000}} a-second-boot-resumes-the-same-main
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

;; Estimate: one binary spawn (~0.6M ticks, a root's boot and stop measured), one ask, its stop and a
;; socket bound and closed: ~1M ticks; 5,000,000
(deftest ^{:seon.test/estimate {:ticks 5000000} :seon.test/root [] :seon.test/execs ["target/seon"]} a-root-handed-over-answers-released-and-exits
  ;; the switch-over's hand-off (task seon.system/an-update-loses-nothing, agreed by flow and deps): a candidate asks root
  ;; {:op :hand-over :tree :results}; root stops through its one end with cause :hand-over (its stop fact names them), closes its
  ;; listening socket, answers {:released true :port p} and its process ends, so the candidate binds p at once. Red before: no such op
  (let [main (str "root-test-hand-over-" (random-uuid))
        ;; its runs dir in the tmpdir: the pid file this test reads is its own output, never the checkout read raw
        runs (str (System/getProperty "java.io.tmpdir") "/" main "-runs")
        ;; the process store by its helper: this form names no checkout, so its reads are its own output (seon.program/checkout-read?)
        root (ts/start-root! {:store (ts/process-store) :main main :runs runs})
        pid (parse-long (second (clojure.string/split (clojure.string/trim (slurp (str runs "/root.pids"))) #"\s+")))
        a (ts/ask root {:op :hand-over :tree "t1" :results {:branch :b :commit "c" :t 1}})
        ;; its exit, from the kernel (jolt.io-poller/exit-chan: at once for one already gone), never a clock
        ended (clojure.core.async/<!! (jolt.io-poller/exit-chan pid))
        free? (try (.close (java.net.ServerSocket. (int (:port root)) 50 (java.net.InetAddress/getByName "127.0.0.1"))) true
                   (catch java.io.IOException _ false))
        {:keys [log]} (ts/stop-root! root)
        cause (second (re-find #"ROOT STOPPED \{:cause (:[a-z-]+)" (str log)))]
    (is (= {:released true :port (:port root)} a) (str "the answer: " (pr-str a) "\n" (subs (str log) (max 0 (- (count (str log)) 3000)))))
    (is free? "its port is free once it answered: the candidate binds it")
    (is (= ":hand-over" cause) (str "the stop's cause: " (subs (str log) (max 0 (- (count (str log)) 3000)))))
    (is (some? ended) "and its process ends by itself: the candidate never waits on it")))

;; Estimate: two binary spawns (~0.6M ticks each, as the test above), one hand-over ask the successor makes and two stops: ~2M of this
;; process's own; 5,000,000
(deftest ^{:seon.test/estimate {:ticks 5000000} :seon.test/root [] :seon.test/execs ["target/seon"]} a-successor-takes-over-the-running-root
  ;; task seon.system/a-successor-takes-over-the-running-root: a successor started beside a running root with --take-over its port loads,
  ;; asks it to hand over, and serves on the port it released, on the same store and main; the old root ends by itself, its stop's
  ;; cause :hand-over. Red under a successor started without it (bin/system next before: a stop, then a start on a port of its own)
  (let [main (str "root-test-take-over-" (random-uuid))
        tmp (System/getProperty "java.io.tmpdir")
        store (ts/process-store)
        old (ts/start-root! {:store store :main main :runs (str tmp "/" main "-old")})
        pid (parse-long (second (clojure.string/split (clojure.string/trim (slurp (str tmp "/" main "-old/root.pids"))) #"\s+")))
        new (ts/start-root! {:store store :main main :runs (str tmp "/" main "-new") :args ["--take-over" (str (:port old))]})
        ;; the old root's exit, from the kernel (at once for one already gone), never a clock
        ended (clojure.core.async/<!! (jolt.io-poller/exit-chan pid))
        old-log (str (:log (ts/stop-root! old)))
        {new-log :log} (ts/stop-root! new)]
    (is (= (:port old) (:port new)) (str "the successor serves the port the old root released: " (subs (str new-log) (max 0 (- (count (str new-log)) 2000)))))
    (is (= ":hand-over" (second (re-find #"ROOT STOPPED \{:cause (:[a-z-]+)" old-log))) (str "the old root stopped for it: " (subs old-log (max 0 (- (count old-log) 2000)))))
    (is (some? ended) "and its process ended by itself")))

;; Estimate: the world (built once a process, ~3M when this test builds it first) + main's ~200 config entries through db-with ~1M + one
;; hold-config! (of-entries' aero read of every entry ~3.6M) ~4M + stale-binary (seon.fx/runtime-for: its tree's digest, refused
;; for a tree git cannot read ~0.1M) + main's program digest ~1M + 2 booted-source writes and 2 health derivations on the world ~2M = 11,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 11000000}} a-boot-on-a-binary-of-another-program-holds-mains-config
  ;; task seon.system/a-boot-resumes-from-the-db, THE GAP: a landing moves git first, and its bridge's merge brings main in
  ;; the db after it (bin/land), so a boot between them finds main behind its tree and its binary built from another
  ;; program. Here main states a config key root reads at start (:seon.config.web/streams-buf, read for a :bind/ buffer,
  ;; as 191e4b56ba did) at another value than the binary's (seon.config/built-text, the tree's), and was last brought to
  ;; another tree. The boot's acts on main's db value (seon.system/boot! and start!): it holds main's config
  ;; (seon.program/hold-config!), and its source fact (seon.system/booted-source) states main's program, not the binary's,
  ;; with a stale-binary problem naming the tree main was last brought to (seon.problems/stale-binary), at each boot until
  ;; bin/update rebuilds it. That a boot reads no file and writes no program fact is the whole sequence's claim, on a root:
  ;; a-root-on-a-binary-of-another-program-runs-mains-program (its last boot), seon.source-test/a-boot-resumes-main-and-reads-no-file
  (let [k :seon.config.web/streams-buf
        main (d/db-with (mt/world {:history? true}) (conj (vec (for [[ck v] config/config]
                                               {:config/key ck :config/form (pr-str (cond-> v (= k ck) inc)) :config/file config/shipped-file}))
                                         {:seon.system/id :root :seon.system/source {:root "/c" :files 1 :forms 1 :ms 1 :tree "main-tree"}}))
        built (program/binary-program)
        source #(:seon.system/source (d/pull % [:seon.system/source] [:seon.system/id :root]))
        boot (fn [db b] (d/db-with db [{:seon.system/id :root
                                        :seon.system/source (root/booted-source (source db) b built (program/program-digest db) {} (root/stale-binary db) nil)}]))
        rows #(mapv (juxt :binary :tree) (get-in (seon.problems/health %) [:classes :stale-binary :rows]))
        held @@#'config/held]
    (try
      (is (= [] (program/hold-config! main)))
      (is (= (inc (get config/config k)) (config/value k)) "it holds main's value, not the tree's or the binary's")
      (let [two (boot main "b2")
            three (boot two "b3")]
        (is (= (program/program-digest main) (:program (source two))) "its source fact states main's program, the binary's beside it")
        (is (nil? (:inputs (source two))) "and no inputs of the binary's to key agents' verdicts by")
        (is (= [[(get-in built [:inputs :tree]) "main-tree"]] (rows two))
            "its binary is of another tree than main's: one stale-binary row, naming the tree main was last brought to")
        (is (= (rows two) (rows three)) "and the row stands at the next boot while the binary does"))
      (finally (reset! @#'config/held held)))))

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

;; docs/users.md#roots-agent: a request's name is its first sentence's words, a dot inside a name kept, the same words asked again
;; numbered 2, 3 (it read "add-a-function-demo" from "demo.math/double", and a repeat took the transaction's number)
(deftest a-requests-name-is-its-first-sentence
  (let [db (ts/test-db)
        db2 (d/db-with db (seon.task/create-tx db [{:seon.task/ns 'request :seon.task/name "add-a-function-demo-math-double" :seon.task/plan "p"}]))]
    (is (= "add-a-function-demo-math-double" (seon.system/request-name db "Add a function demo.math/double. Then test it.")))
    (is (= "fix-it" (seon.system/request-name db "Fix it! Now.")))
    (is (= "work" (seon.system/request-name db "...")))
    (is (= "add-a-function-demo-math-double-2" (seon.system/request-name db2 "Add a function demo.math/double.")))))

;; docs/errors.md#nothing-crashes-and-nothing-hangs: a refused write of agents' facts is a problem against each agent, and an
;; answer that was already an error is not taken back through its :dest again. Red before: :agent-facts (a vector) went whole into
;; err/occurrence's :agent, the api's step threw, and its :error answer re-ran back-op on the same answer, throwing again: a loop
;; of errors each wrapping the last (bin/check problems hung on it, 2026-11-12)
;; docs/server.md#ops: seon_done's judge! is answered by the judge's end, or by a reconciler defect: a step that throws keeps its
;; state, so its flight never ends (flow's review). The api answers the wait in the step that records the defect, an ordinary
;; pending entry, never a scan per commit (47.5M ticks a commit before [M]). Estimate: two api steps over a map, ~0.3M
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-judge-whose-reconciler-step-threw-answers-the-defect
  (let [st {:agents {} :pending {} :rid 0 :boot "b" :seon.system/errors {} :view {:agents {}}}
        o (volatile! {})
        stale (doto (promise) (deliver {:task "x" :outcome :closed}))
        p (promise)
        st (assoc-in st [:pending 7] {:p stale :kind :reconciler-defect :agent nil})
        st (#'seon.system/ext-op st o {:op :wait :on :reconciler-defect :p p})]
    (is (not (contains? (:pending st) 7)) "an entry its other producer already answered is dropped as a new one comes")
    (is (= 1 (count (filter #(= :reconciler-defect (:kind %)) (vals (:pending st))))) "the new wait is one ordinary pending entry")
    (#'seon.system/back-op st o {:op :error :pid :writer :message "not the reconciler"})
    (is (= :unanswered (deref p 0 :unanswered)) "another proc's defect answers nothing")
    (#'seon.system/back-op st o {:op :error :pid :reconciler :message "the reconciler's step threw"})
    (is (= :seon.system/proc-defect (get-in (deref p 0 :unanswered) [:defect :seon.error/type])) "the reconciler's defect answers it")))

;; docs/errors.md#nothing-crashes-and-nothing-hangs: an agent's :merge-main waits with no clock (its merge-ms bound is deleted,
;; 2026-11-15); a writer step that throws on it answers the request's :dest, so the agent's await-reply ends with the typed error.
;; Estimate: one answer-taken over a map, ~0.2M
(deftest ^{:seon.test/estimate {:ticks 300000}} a-merge-the-writer-threw-on-answers-the-agent-waiting
  (let [st {:agents {:a1 {:status :busy}} :pending {} :rid 0 :boot "b" :seon.system/errors {}}
        o (volatile! {})
        e (seon.contract/error :seon.system/proc-defect #'seon.system/flow-error "merge-main-into threw" {})
        _ (#'seon.system/answer-taken st o :writer {:op :merge-main :branch "b" :dest {:agent :a1 :op :reply :k 7}} e)
        sent (get @o [:a1 :in])]
    (is (= 1 (count sent)) (pr-str @o))
    (is (= {:op :reply :k 7 :seon.error/type :seon.system/proc-defect} (select-keys (:msg (first sent)) [:op :k :seon.error/type]))
        "the agent's request :k is answered with the error, which merge-main! returns as it is")))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-refused-facts-write-is-each-agents-problem-and-is-not-answered-again
  (let [st {:agents {} :pending {} :rid 0 :boot "b" :seon.system/errors {}}
        o (volatile! {})
        r (seon.contract/error :seon.writer/refused #'seon.system/back-op "the store refused the write" {})
        st2 (#'seon.system/back-op st o {:dest {:agent-facts [:a1 :a2]} :r r})]
    (is (true? (:seon.system/errors-dirty st2)) "recorded as a problem, no throw")
    (is (= st (#'seon.system/answer-taken st o :writer {:dest {:agent-facts [:a1]} :r r} r))
        "an answer that was already an error is not taken back through its :dest")))

(deftest ^{:seon.test/estimate {:ticks 1000000}} an-undeliverable-output-is-a-problem
  ;; docs/flow.md#the-proc-contract: Flow reports an output it can't deliver (a pid not in the graph, an unconnected out) and one
  ;; a stop dropped on its report chan, a sliding 100 that nothing in Seon read, so past 100 they were lost unseen. Root reads
  ;; it as it reads the error chan: each such report is the api's :error with the typed :seon.system/undeliverable, which it
  ;; records as a problem; any other report is not one
  (let [report->error @#'seon.system/flow-report]
    (let [m (report->error {:clojure.core.async.flow/undeliverable [:gone :in] :clojure.core.async.flow/pid :src})]
      (is (= :error (:op m)))
      (is (= :src (:pid m)))
      (is (= :seon.system/undeliverable (get-in m [:data :seon.error/type])) "typed, so the api records it as a problem")
      (is (= "[:gone :in]" (get-in m [:data :coord]))))
    (is (= :seon.system/undeliverable (get-in (report->error {:clojure.core.async.flow/dropped-on-stop [:src :out]}) [:data :seon.error/type]))
        "an output a stop dropped, the same")
    (is (nil? (report->error {:clojure.core.async.flow/pid :x :something :else})) "a report that loses nothing is no problem")))

;; Estimate: a test branch ~3M + two evals of two agents ~1M + two back-points ~1M = 6000000
(deftest ^{:seon.test/estimate {:ticks 6000000}} going-back-takes-only-this-agents-eval
  ;; docs/turn.md#an-agent-goes-back-to-an-eval (seon.merge/a-reader-of-an-agents-records-names-its-agent): once merges are whole a branch
  ;; holds every agent's evals, so an eval named by id is gone back to only when its record is the asking agent's
  (ts/test-branch!
   (fn [cfg conn]
     (d/transact conn {:tx-data [{:seon.agent/key "mine"} {:seon.agent/key "theirs"}
                                 {:seon.eval/id "e-mine" :seon.eval/agent [:seon.agent/key "mine"] :seon.eval/code "(def a 1)" :seon.eval/response {}}
                                 {:seon.eval/id "e-theirs" :seon.eval/agent [:seon.agent/key "theirs"] :seon.eval/code "(def b 2)" :seon.eval/response {}}]
                       :tx-meta (pdb/tx-meta (:branch cfg))})
     (let [db @conn b (:branch cfg)]
       (is (not (seon.contract/error? (#'seon.system/back-point db b :mine "e-mine"))) "its own eval")
       (is (re-find #"is agent theirs's" (str (:seon.error/message (#'seon.system/back-point db b :mine "e-theirs")))) "never another agent's")))))

;; docs/processes.md#an-agent-that-starts-without-its-program: round 2 (2026-11-15): an agent told only "seon.reads-test did not
;; load" spent its turns on Jolt's bug; the message says whose each error is. Estimate: two string builds, ~20k ticks; 1,000,000
(deftest ^{:seon.test/estimate {:ticks 1000000 :bytes 1000000}} a-load-error-says-whose-it-is
  (let [swp {:seon.error/message "this agent started without part of its program: seon.reads-test did not load"
             :errors [{:ns 'seon.reads-test :message "emit-quoted: unsupported quoted form"}]}
        other (seon.system/started-without-program-text swp "seon.test-store/a-shard-says-what-each-test-waits-on")
        own (seon.system/started-without-program-text swp "seon.reads/a-read-is-recorded")]
    (is (clojure.string/includes? other "seon.reads-test: emit-quoted: unsupported quoted form") "each error, as before")
    (is (clojure.string/includes? other "Go on with your task") (str "another namespace's load is not the agent's task: " other))
    (is (not (clojure.string/includes? other "part of your task")) other)
    (is (clojure.string/includes? own "fixing that load is part of your task") (str "its own task's test namespace is: " own))))

;; Estimate: four pure holder reads over a hand-made api state: ~50k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} a-resumed-agents-own-stale-entry-never-holds-its-branch
  ;; data's pilot, 2026-11-15 [M result/df8817fb]: agent k159570deaf49ad6 was killed, and every resume of it was refused by its own
  ;; entry: spawn!'s holder check counted the agent's :agents entry under its key whatever it was (killed, or :spawning with no pid
  ;; and no caller waiting), and that entry was never reaped. A holder is a live process: a live status with a pid, or a spawn
  ;; still answering its caller
  (let [holder #'seon.system/branch-holder
        st (fn [status pid spawning?] (cond-> {:agents {:k1 {:branch :b}} :view {:agents {:k1 {:branch :b :status status :pid pid}}}}
                                        spawning? (assoc-in [:seon.system/spawning :k1] (promise))))]
    (is (nil? (holder (st :killed nil false) {:key "k1" :branch :b})) "a killed agent's leftover entry holds nothing")
    (is (nil? (holder (st :spawning nil false) {:key "k1" :branch :b})) "nor a :spawning entry with no process and no caller waiting")
    (is (= :k1 (holder (st :idle 4242 false) {:key "k1" :branch :b})) "a live agent with its process holds its branch")
    (is (= :k1 (holder (st :spawning nil true) {:key "k1" :branch :b})) "and a spawn still answering its caller holds it")))

;; docs/decisions.md, a bad entry never stops a boot: the seed and a boot's catch-up write their entries leaving out each one the
;; write check refuses for a cite to nothing, named with its error. Red before: one task citing a name that did not exist refused
;; the whole seed (seon.db/no-target), so every fresh store and every bin/test failed (2026-11-16, step 3's 33 stale cites).
;; Estimate: 1 test branch ~1M + load-tx of a 2-task file ~0.5M + 2 writes of ~2 entities (the refused one, then the rest) ~1M ~ 3,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-bad-entry-is-left-out-and-named-never-refusing-the-rest
  (ts/test-branch!
   (fn [_ conn]
     ;; a task file as the seed reads it (tasks-of, seon.task/load-tx): one task cites a var of a program namespace that has none
     (let [text (str "[{:seon.task/name \"good\" :seon.task/plan \"Make it.\" :seon.task/proofs #{}}\n"
                     " {:seon.task/name \"bad\" :seon.task/plan \"Fix `app.m0/missing` first.\" :seon.task/proofs #{}}]\n")
           tx (seon.task/load-tx @conn (root/tasks-of {"demo.edn" text}))
           {:keys [db skipped]} (#'root/transact-leaving-out! conn tx)]
       (is (some? (d/entity db [:seon.task/id "demo/good"])) "the good entry is written")
       (is (nil? (d/entity db [:seon.task/id "demo/bad"])) "the bad one is left out")
       (is (= [["demo/bad" :seon.db/no-target]] (mapv (juxt :by :seon.error/type) skipped)) (str "and named with its error: " (pr-str skipped)))))))
