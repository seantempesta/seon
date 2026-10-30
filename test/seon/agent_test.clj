(ns seon.agent-test
  "An agent's process, run as the built binary (target/seon, bin/build seon): the root forks
  it, so the test boots a root of its own, as seon.root-test does, and asks the agent over
  the server what its REPL answers.

  An agent's test run, read from its child's pipe (task seon.agent/one-test-bound-derived-and-named):
  no clock ends it; the child's exit does, and a child that died names the test it was running.
  Run: bin/test seon.agent-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.agent :as agent] [seon.proofs :as proofs] [seon.contract :as contract]
            [datahike.api :as d] [seon.db :as pdb] [seon.test-store :as ts] [seon.program-test] [seon.lib :as lib] [seon.program :as program] [seon.sock :as sock]
            [seon.fx :as fx] [seon.cost :as cost] [seon.config :as cfg] [seon.merge :as pm] [seon.profile :as profile] [seon.task :as task] [seon.repl :as repl] [seon.ctx] [seon.turn] [test-runner :as runner] [seon.context-test] [my.task] [my.agent] [seon.template :as tpl] [seon.stall-proofs] [clojure.core.async]))

(def ^:private await-file
  "seon.await, which a root (importing src only) loads by this path."
  (.getCanonicalPath (java.io.File. (seon.config/checkout) "test/seon/await.clj")))

(def ^:private before-done
  "Main's t just before a :done, kept in root's REPL as t-before-done: :done answers once the judge
  has, and the reconciler may be quiet by then, so a test waiting for quiescence after :done waits
  for a report written after this t, never after :done's answer (that wait could never end)."
  {:op :eval :agent "root" :code "(def t-before-done (:max-tx @(:conn sys)))"})

(def value-of "seon.test-store/value-of, as this namespace's callers name it." ts/value-of)

(defn in-agent
  "One agent on a task of its own in this test process's shared root on the checkout's src (its
  source root; seon.test-store/shared-root!, booted once per shard and root arguments), each of
  forms evaluated in that agent (a string) or sent to the server as it is (a map): every reply's
  response with its value read back (:value, value-of), or the answer that holds no response, in
  order. forms is a vector, or a function of the test's names {:ns :task :run :agent} (its task's
  namespace, a fresh one per test, its task \"<ns>/t\", and the run and agent the attach
  answered) answering one: a test names what it made by these, never by a counter's value, and
  reads only its own task's facts (the shard's main holds every earlier test's). Its task and
  the task's branches are its own (the namespace is); its agent is ended and its branches
  deleted when it ends, however it ends. A shard runs its tests one at a time, so root-REPL vars
  a test defines (ready-ev, t-before-done) are its own while it runs.
  opts: :dirs, the source dirs root imports (default [\"src\"]); :proofs, the task's proofs;
  :sets, more --set config overrides for the root (e.g. its scripted model): a root per distinct
  :dirs and :sets; :config, the tasks' own settings (their :seon.task/config: a test's scripted
  model, :seon.config.ai/script, rides the task, so the root's arguments stay alike and the shard's
  tests share one root: docs/config.md#the-resolver); :decls, more task declarations in the task's file beside t (a function of
  {:ns :task}, or a vector); :own-root?, a root of its own for this test (its main dropped with
  it): a test whose agent merges into main, which would move the program every later test runs;
  :client, the client its attach names (seon.server: then only that client's requests, or ones
  naming the task, act on the agent)."
  ([forms] (in-agent forms {}))
  ([forms {:keys [dirs proofs sets config decls own-root? client] :or {dirs ["src"] proofs '#{demo-test/t}}}]
  (let [checkout (seon.config/checkout)
        tns (str "t" (subs (str (random-uuid)) 0 8))
        names {:ns tns :task (str tns "/t")}
        tasks (str checkout "/target/runs/" tns "-tasks")
        _ (.mkdirs (java.io.File. tasks))
        decls (if (fn? decls) (decls names) decls)
        with-config #(cond-> % config (assoc :seon.task/config config))
        _ (spit (str tasks "/" tns ".edn") (pr-str (mapv with-config (into [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs proofs}] decls))))
        args (into ["--set" (str ":seon.config.source/root=" (pr-str checkout)) "--set" (str ":seon.config.source/dirs=" (pr-str dirs))]
                   (mapcat (fn [x] ["--set" x])) sets)
        ;; its store and main named (seon.test-store/start-root!), never inherited
        root (if own-root?
               (let [main (str "in-agent-" (random-uuid))]
                 (ts/start-root! {:store (ts/process-store checkout) :main main :runs (str checkout "/target/runs/" main "-runs") :args args}))
               (ts/shared-root! args))
        ask (fn [m] (ts/ask root m))
        eval-root (fn [code] (ask {:op :eval :agent "root" :code code}))
        made (volatile! nil)
        ;; what the test made goes with it, however it ended: on its own root, the root and its
        ;; main; on the shared one, its agent (killed, its end awaited), its tasks (cancelled) and their branches
        end! (fn []
               (let [agent @made]
                 (sh/sh "rm" "-rf" tasks)
                 (cond own-root? (let [s (ts/stop-root! root)] (ts/drop-main! (:store root) (:main root)) (sh/sh "rm" "-rf" (:runs root)) s)
                       (:port root) (eval-root (str "(let [ev (c/events sys) a " (when agent (str ":" agent)) "]"
                                                    " (when-let [v (resolve 'ready-ev)] (clojure.core.async/untap (:events-mult sys) @v))"
                                                    " (when (and a (not (:ended-ms (get (:agents (c/state sys)) a))))"
                                                    "   (c/kill! sys a)"
                                                    "   (loop [] (let [e (clojure.core.async/<!! ev)] (when-not (and (#{:ended :dead :killed} (:type e)) (= a (:agent e))) (recur)))))"
                                                    " (clojure.core.async/untap (:events-mult sys) ev)"
                                                    ;; its tasks cancelled: a run left :running keeps the reconciler from
                                                    ;; ever being quiet, and a later test's await-quiet waits on it
                                                    " (my.root/cancel! sys (filterv #(clojure.string/starts-with? % \"" tns "/\")"
                                                    "   (datahike.api/q '[:find [?id ...] :where [_ :seon.task/id ?id]] @(:conn sys))))"
                                                    " (doseq [b (datahike.api/branches (:conn sys)) :when (and (= (namespace b) (name (:main (:store (c/state sys)))))"
                                                    "                                                       (clojure.string/starts-with? (name b) " (pr-str (str "task-" tns "-")) "))]"
                                                    "   (datahike.api/delete-branch! (:conn sys) b)))")))))
        stopped (volatile! nil)
        out
        (try
          (let [attach (when (:port root)
                         (eval-root (str "(my.root/load-tasks! sys " (pr-str tasks) ")"))
                         ;; the agent's :ready event, tapped before the attach that spawns it and
                         ;; awaited after: its hello (a ping of :agents until idle was a poll)
                         (eval-root "(def ready-ev (c/events sys))")
                         (ask (cond-> {:op :attach :task (:task names)} client (assoc :client client))))
                agent (some-> (:agent attach) name)
                names (assoc names :agent agent :run (:run attach))]
            (vreset! made agent)
            (cond
               (nil? (:port root)) [{:error :root-did-not-listen :log (:log root)}]
               ;; an attach that made no agent (refused: its fork server lost) ends the test with
               ;; that answer, never a wait for a hello that cannot come
               (nil? agent) {::attach-made-no-agent attach}
               :else (do (eval-root (str "(loop [] (let [e (clojure.core.async/<!! ready-ev)] (if (and (= :ready (:type e)) (= :" agent " (:agent e))) :ready (recur))))"))
                         (mapv #(ask (if (map? %) % {:op :eval :agent agent :code %})) (if (fn? forms) (forms names) forms)))))
          (finally (vreset! stopped (end!))))]
    (when-let [a (and (map? out) (::attach-made-no-agent out))]
      (throw (ex-info (str "in-agent: the attach made no agent: " (pr-str a) (when own-root? (str "\nroot's log:\n" (:log @stopped))))
                      {:attach a})))
    (mapv #(if (contains? % :response) (let [r (:response %)] (assoc r :value (value-of r))) %) out))))

(defn- root-ended
  "A root with two agents: a1 idle, a2 evaluating code (sent; its :started event awaited). Then
  SIGKILL of root; with fork-server-first?, what a pid cleanup does before it: SIGKILL of the
  fork server's standby and of the fork server, so its group kill (seon.fork-server/main) never
  runs and each agent has only its own socket to root to end on. Answers {:agents {id pid}
  :left #{pid} :out text}: the agents' pids as root reported them, the processes of the fork
  server's group still alive once everything root started has ended (the wait is
  seon.test-store/kill-root!'s drain, an event; a process that never ends holds the test), and
  root's log, for a red run's message."
  [code fork-server-first?]
  (let [checkout (seon.config/checkout)
        main (str "root-ended-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        root (ts/start-root! {:store store :main main :runs runs})
        ask (fn [m] (ts/ask root m))
        ;; the printed value of a root eval; a reply that is an error stays the error
        eval-root (fn [code] (let [r (ask {:op :eval :agent "root" :code code})]
                               (if (:error r) r (value-of (:response r)))))
        ;; spawn! answers at each agent's hello: both are ready
        _ (eval-root "[(c/spawn! sys {}) (c/spawn! sys {})]")
        ;; a2 begins its form (the agent's :started event, tapped before it is sent)
        _ (eval-root (str "(let [ev (c/events sys)] (c/submit! sys :a2 " (pr-str code) ") "
                          "(loop [] (let [e (clojure.core.async/<!! ev)] (if (and (= :started (:type e)) (= :a2 (:agent e))) :began (recur)))))"))
        agents (eval-root "(into {} (for [[id a] (:agents (c/state sys))] [id (:pid a)]))")
        pids (set (vals agents))
        children (fn [pid] (->> (str/split-lines (:out (sh/sh "pgrep" "-P" (str pid)))) (remove str/blank?) (map parse-long)))
        ;; the fork server root names, never the first child pgrep lists (pid order wraps)
        fs (eval-root "(get-in (c/state sys) [:fork-server :pid])")]
    (when fork-server-first?
      (doseq [k (children fs) :when (not (pids k))] (sh/sh "kill" "-9" (str k)))
      (sh/sh "kill" "-9" (str fs)))
    ;; answered once root and every process it started has ended (bin/held --drain): what is
    ;; left in the fork server's group then is what did not end with its root
    (let [drained (:drained (ts/kill-root! root))
          left (->> (str/split-lines (:out (sh/sh "pgrep" "-g" (str fs)))) (remove str/blank?) (map parse-long) set)
          log (let [f (java.io.File. (str runs "/root.log"))] (if (.exists f) (slurp f) ""))]
      (doseq [p left] (sh/sh "kill" "-9" (str p)))
      (ts/drop-main! store main)
      (sh/sh "rm" "-rf" runs)
      {:agents agents :out log :left (if drained left (conj left :not-drained))})))

(deftest a-tests-request-is-answered-while-an-eval-blocks
  ;; task seon.agent/the-agent-is-a-flow-graph: root's :tests request goes to the agent's :tests proc,
  ;; never behind the eval the :repl proc runs (md-scan #21: a :tests waited behind a wait that held the
  ;; loop). An eval blocks on a channel take that nothing will answer; the test service proves the
  ;; agent's branch meanwhile and answers; then root's interrupt stops the eval and the agent answers on
  (let [checkout (seon.config/checkout)
        main (str "tests-beside-an-eval-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        root (ts/start-root! {:store store :main main :runs runs})
        ask (fn [m] (ts/ask root m))
        eval-root (fn [code] (let [r (ask {:op :eval :agent "root" :code code})] (if (:error r) r (value-of (:response r)))))
        _ (eval-root "(c/spawn! sys {})")
        _ (eval-root (str "(let [ev (c/events sys)] (def blocked (c/submit! sys :a1 \"(clojure.core.async/<!! (clojure.core.async/chan))\")) "
                          "(loop [] (let [e (clojure.core.async/<!! ev)] (if (and (= :started (:type e)) (= :a1 (:agent e))) :began (recur)))))"))
        ;; the run's answer, or :none if the agent did not answer it while its eval blocked
        proved (eval-root "(deref (future @(c/prove! sys :a1 #{} :turn)) 60000 :none)")
        _ (eval-root "(c/interrupt! sys :a1)")
        stopped (eval-root "(clojure.string/starts-with? (:seon.error/message (:ex @blocked)) \"interrupted by root\")")
        next-answer (let [r (ask {:op :eval :agent "a1" :code "(+ 2 2)"})] (if (:error r) r (value-of (:response r))))]
    (ts/stop-root! root)
    (ts/drop-main! store main)
    (sh/sh "rm" "-rf" runs)
    (is (and (map? proved) (contains? proved :pass)) (str "the run answered while the eval blocked: " (pr-str (if (map? proved) (select-keys proved [:pass :fail :error :ran]) proved))))
    (is (true? stopped) (str "the blocked eval answers interrupted by root: " stopped))
    (is (= 4 next-answer) (pr-str next-answer))))

(deftest a-spawn-after-the-fork-server-is-lost-answers
  ;; task seon.fork-server/every-fork-server-wait-ends-with-the-server: the fork server and its
  ;; standby killed (as a boot death leaves root, a server with no standby), the server is lost;
  ;; a spawn asked then is refused with the error naming it (seon.fork-server/lost), before any
  ;; agent is made, and root stops. Unfixed, the fork was neither sent nor answered and the spawn
  ;; (an attach's) waited for good; then a spawn made an agent whose socket the lost server closed,
  ;; so it answered :seon.agent/died or :seon.agent/failed, whichever end reached root first
  (let [checkout (seon.config/checkout)
        main (str "fork-server-lost-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        root (ts/start-root! {:store store :main main :runs runs})
        eval-root (fn [code] (let [r (ts/ask root {:op :eval :agent "root" :code code})]
                               (if (:error r) r (value-of (:response r)))))
        _ (eval-root "(def lost-ev (c/events sys))")
        children (fn [pid] (->> (str/split-lines (:out (sh/sh "pgrep" "-P" (str pid)))) (remove str/blank?) (map parse-long)))
        ;; the fork server is the one root names: root has other children (its file watcher), and
        ;; pgrep's order is pid order, which wraps (the first child pgrep listed was the watcher,
        ;; killed in its place, and the test waited for a loss that never came)
        fs (eval-root "(get-in (c/state sys) [:fork-server :pid])")
        ;; its standby is its child
        _ (doseq [k (children fs)] (sh/sh "kill" "-9" (str k)))
        _ (sh/sh "kill" "-9" (str fs))
        lost (eval-root "(loop [] (let [e (clojure.core.async/<!! lost-ev)] (case (:type e) :fork-server-lost :lost :proc-defect e (recur))))")
        spawned (eval-root "(try (c/spawn! sys {}) (catch clojure.lang.ExceptionInfo e (let [d (ex-data e)] [(:seon.error/type d) (:seon.error/message d)])))")
        status (eval-root "(get-in (c/state sys) [:fork-server :status])")
        agents (eval-root "(count (:agents (c/state sys)))")
        stopped (ts/stop-root! root)]
    (ts/drop-main! store main)
    (sh/sh "rm" "-rf" runs)
    (is (= :lost lost) "root heard the server is lost")
    (is (= :seon.root/refused (first spawned)) (pr-str spawned))
    (is (re-find #"fork server is lost" (str (second spawned))) (pr-str spawned))
    (is (= :lost status))
    (is (= 0 agents) "no agent was made for the refused spawn")
    (is (= "0" (str (:exit stopped))) (str "root stops with its fork server lost: " (pr-str (dissoc stopped :log))))))

(deftest a-fork-server-step-that-throws-answers-and-the-server-serves-on
  ;; task seon.fork-server/a-throwing-fork-server-step-answers-its-asker: a main commit the fork
  ;; server cannot read (a :tx naming no commit: the replica's d/commit-as-db throws, as a bad
  ;; entity in a d/with did, 2026-10-29) threw out of its step. Unfixed, the server stayed alive
  ;; at 0% CPU, root heard nothing, and the spawn asked next waited for good. Now the step's
  ;; throw is an error fact on root, the server's state is as it was, and the next spawn is served.
  ;; No clock decides: an unfixed run waits on the spawn until the runner's budget ends it
  (let [checkout (seon.config/checkout)
        main (str "fork-server-throw-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        root (ts/start-root! {:store store :main main :runs runs})
        eval-root (fn [code] (let [r (ts/ask root {:op :eval :agent "root" :code code})]
                               (if (:error r) r (value-of (:response r)))))
        fs (eval-root "(get-in (c/state sys) [:fork-server :pid])")
        _ (eval-root "@(clojure.core.async.flow/inject (:graph sys) [:fork-server :cmd] [{:op :tx :t 999999999999 :commit (random-uuid) :ns 0 :tx-data []}])")
        spawned (eval-root "(try (c/spawn! sys {}) :spawned (catch clojure.lang.ExceptionInfo e (let [d (ex-data e)] [(:seon.error/type d) (:seon.error/message d)])))")
        recorded (eval-root "(pr-str (my.root/problems sys))")
        alive (eval-root "(get-in (c/state sys) [:fork-server :pid])")
        stopped (ts/stop-root! root)
        log (let [f (java.io.File. (str runs "/root.log"))] (if (.exists f) (slurp f) ""))]
    (ts/drop-main! store main)
    (sh/sh "rm" "-rf" runs)
    (is (= :spawned spawned) (str (pr-str spawned) log))
    (is (= fs alive) "the same server serves on")
    (is (re-find #"step-failed" recorded) "and root recorded the error")
    (is (= "0" (str (:exit stopped))) (str (pr-str (dissoc stopped :log))))))

(deftest an-agent-ends-when-its-root-is-gone
  ;; task seon.agent/an-agent-ends-with-its-root: with its fork server gone first (a pid
  ;; cleanup), an agent ends on EOF of its own socket to root, whatever it is doing: an idle
  ;; agent reads it; one evaluating a form with no tick budget is interrupted by it (SIGIO)
  (let [{:keys [agents left out]} (root-ended "(loop [] (recur))" true)]
    (is (= #{:a1 :a2} (set (keys agents))) out)
    (is (not (contains? left (:a1 agents))) "the idle agent ended")
    (is (not (contains? left (:a2 agents))) "the busy agent ended")))

(deftest a-proof-run-ends-with-its-agent
  ;; task seon.agent/a-proof-run-ends-with-its-agent: the agent's proof run (a fork of it) holds
  ;; the write end of a fifo and sleeps, a test that never writes to the agent's pipe; the
  ;; agent is killed (its task cancelled); the proof run ends with it, on the kernel's word that
  ;; the reader of its pipe is gone (SIGIO). The test reads the fifo: its first byte says the
  ;; proof run is in its test, its EOF that nothing holds the fifo any more, the proof run gone.
  ;; No clock decides: a run that outlives its agent wakes from its sleep (a minute) and writes a
  ;; second byte, which fails the test.
  (let [checkout (seon.config/checkout)
        main (str "proof-ended-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        fifo (str checkout "/target/runs/" main ".fifo")
        _ (sh/sh "mkfifo" fifo)
        root (ts/start-root! {:store store :main main :runs runs})
        eval-root (fn [code] (let [r (ts/ask root {:op :eval :agent "root" :code code})]
                               (if (:error r) r (value-of (:response r)))))
        _ (eval-root "(c/spawn! sys {})")
        parked (str "(do (ns parked-proof (:require [clojure.test :refer [deftest is]]))"
                    " (deftest p (let [w (java.io.FileOutputStream. " (pr-str fifo) ")] (.write w 49) (.flush w) (Thread/sleep 60000) (.write w 50) (.flush w)) (is true))"
                    " (seon.agent/run-tests-forked 1000000000 '#{parked-proof/p} :turn))")
        ;; a1 begins the form (its :started event, tapped before the submit); root knows its pid by then
        _ (eval-root (str "(let [ev (c/events sys)] (c/submit! sys :a1 " (pr-str parked) ") "
                          "(loop [] (let [e (clojure.core.async/<!! ev)] (if (and (= :started (:type e)) (= :a1 (:agent e))) :began (recur)))))"))
        pids (eval-root "(into {} (for [[id a] (:agents (c/state sys))] [id (:pid a)]))")
        agent-pid (get pids :a1)
        in (java.io.FileInputStream. ^String fifo)
        began (.read in)
        killed (sh/sh "kill" "-9" (str agent-pid))
        ended (.read in)]
    (.close in)
    (ts/kill-root! root)
    (ts/drop-main! store main)
    (sh/sh "rm" "-rf" runs fifo)
    (is (pos-int? agent-pid) (str "the agent's pid, from " (pr-str pids)))
    (is (= 49 began) "the proof run was in its test")
    (is (= -1 ended) (str "the proof run outlived its agent (byte " ended " after its sleep); agent " (pr-str agent-pid) " kill " (pr-str killed)))))

(deftest a-two-child-proof-run-ends-with-its-agent
  ;; task seon.agent/a-proof-run-ends-with-its-agent, for a run dealt into two children. Child A's test holds the write end
  ;; of a fifo and sleeps; child B's test blocks for good where no signal reaches (a take from a channel nothing puts
  ;; on), so B outlives its agent's death. The agent is killed: the fifo's EOF says A is gone while B is alive. No clock
  ;; decides: a child that outlives its agent wakes from its sleep (a minute) and writes a second byte. (A child also
  ;; ends by watching its parent, so this does not alone show a sibling holds no copy of A's pipe: the next test does)
  (let [checkout (seon.config/checkout)
        main (str "proofs-ended-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        fifo-a (str checkout "/target/runs/" main ".a.fifo")
        fifo-b (str checkout "/target/runs/" main ".b.fifo")
        _ (doseq [f [fifo-a fifo-b]] (sh/sh "mkfifo" f))
        root (ts/start-root! {:store store :main main :runs runs :args ["--set" ":seon.config.proofs/child-min-ms=1"]})
        eval-root (fn [code] (let [r (ts/ask root {:op :eval :agent "root" :code code})]
                               (if (:error r) r (value-of (:response r)))))
        _ (eval-root "(c/spawn! sys {})")
        a (str "(ns parked0-proof (:require [clojure.test :refer [deftest is]]))"
               " (deftest p (let [w (java.io.FileOutputStream. " (pr-str fifo-a) ")] (.write w 49) (.flush w) (Thread/sleep 60000) (.write w 50) (.flush w)) (is true))")
        b (str "(ns parked1-proof (:require [clojure.test :refer [deftest is]]))"
               " (deftest p (let [w (java.io.FileOutputStream. " (pr-str fifo-b) ")] (.write w 49) (.flush w) (clojure.core.async/<!! (clojure.core.async/chan))) (is true))")
        form (str "(do " a " " b " (seon.agent/run-tests-forked 1000000000 '#{parked0-proof/p parked1-proof/p} :turn))")
        _ (eval-root (str "(let [ev (c/events sys)] (c/submit! sys :a1 " (pr-str form) ") "
                          "(loop [] (let [e (clojure.core.async/<!! ev)] (if (and (= :started (:type e)) (= :a1 (:agent e))) :began (recur)))))"))
        pids (eval-root "(into {} (for [[id a] (:agents (c/state sys))] [id (:pid a)]))")
        agent-pid (get pids :a1)
        in-a (java.io.FileInputStream. ^String fifo-a)
        in-b (java.io.FileInputStream. ^String fifo-b)
        began [(.read in-a) (.read in-b)]
        killed (sh/sh "kill" "-9" (str agent-pid))
        ended (.read in-a)]
    (.close in-a) (.close in-b)
    (ts/kill-root! root)
    (ts/drop-main! store main)
    (sh/sh "rm" "-rf" runs fifo-a fifo-b)
    (is (pos-int? agent-pid) (str "the agent's pid, from " (pr-str pids)))
    (is (= [49 49] began) "both children were in their tests")
    (is (= -1 ended) (str "child A outlived its agent (byte " ended " after its sleep); agent " (pr-str agent-pid) " kill " (pr-str killed)))))

(deftest a-handful-of-tests-runs-in-one-child
  ;; task seon.agent/a-test-run-streams-its-reports: a run forks no more children than its work needs
  ;; (:seon.config.proofs/child-min-ms); five tests never measured count :seon.config.proofs/unmeasured-ms each, under it
  (let [form (fn [n] (str "(ns app.few" n "-test (:require [clojure.test :refer [deftest is]])) (deftest a (is (= 1 1)))"))
        run (str "(let [pids (atom []) r (seon.agent/run-tests-forked 100000000 #{} :main (fn [p] (swap! pids conj p)) '#{app.few0-test app.few1-test app.few2-test app.few3-test app.few4-test})]"
                 " (pr-str {:children (count @pids) :pass (:pass r)}))")
        [_ _ _ _ _ r] (in-agent (conj (mapv form (range 5)) run))]
    (is (= {:children 1 :pass 5} (some-> (:value r) clojure.edn/read-string)))))

(deftest a-test-child-holds-only-its-own-pipe
  ;; task seon.agent/a-proof-run-ends-with-its-agent: a child forked after a sibling holds no copy of the sibling's pipe,
  ;; whose end a child must never keep (the agent's death would not reach the sibling by SIGIO while any copy is open). Each
  ;; of two children counts the descriptors open in it (fcntl F_GETFD) in its test and writes the count to a file: a child
  ;; holding a sibling's pipe end has one more than the child forked before it
  (let [checkout (seon.config/checkout)
        dir (str checkout "/target/runs/fds-" (random-uuid))
        _ (.mkdirs (java.io.File. ^String dir))
        count-in (fn [n] (str "(ns fds" n "-proof (:require [clojure.test :refer [deftest is]]))"
                              " (deftest p (let [c (count (filter #(not (neg? (seon.sock/c-fcntl % 1 0))) (range 256)))] (spit " (pr-str (str dir "/" n)) " c)) (is true))"))
        form (str "(do " (count-in 0) " " (count-in 1) " (seon.agent/run-tests-forked 1000000000 '#{fds0-proof/p fds1-proof/p} :turn))")
        [run] (in-agent [form] {:sets [":seon.config.proofs/child-min-ms=1"]})
        counts (mapv #(some-> (try (slurp (str dir "/" %)) (catch Throwable _ nil)) parse-long) [0 1])]
    (sh/sh "rm" "-rf" dir)
    (is (every? pos-int? counts) (str "both children counted: " (pr-str counts) " " (pr-str run)))
    (is (apply = counts) (str "the two children hold the same descriptors: " (pr-str counts)))))

(deftest an-agent-answers-while-its-tests-run
  ;; task seon.turn/observe-reads-are-renders (phase B): root's :tests request forks the test child
  ;; and a fiber reads it, so the agent's loop answers an eval while its own tests run. The test
  ;; child holds in its test until this test lets it go (a fifo, no clock): the eval is answered
  ;; while it holds, then the run answers green
  (let [checkout (seon.config/checkout)
        main (str "answers-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        began (str checkout "/target/runs/" main ".began")
        gate (str checkout "/target/runs/" main ".gate")
        _ (doseq [f [began gate]] (sh/sh "mkfifo" f))
        root (ts/start-root! {:store store :main main :runs runs})
        eval-root (fn [code] (let [r (ts/ask root {:op :eval :agent "root" :code code})]
                               (if (:error r) r (value-of (:response r)))))
        _ (eval-root "(c/spawn! sys {})")
        ;; two forms, as an agent writes them: each recorded on its branch (a definition inside a do is not)
        held (str "(deftest h (let [w (java.io.FileOutputStream. " (pr-str began) ")] (.write w 49) (.flush w))"
                  " (is (= 50 (.read (java.io.FileInputStream. " (pr-str gate) ")))))")
        _ (eval-root (str "@(c/submit! sys :a1 " (pr-str "(ns held-proof (:require [clojure.test :refer [deftest is]]))") ")"))
        defined (eval-root (str "@(c/submit! sys :a1 " (pr-str held) ")"))
        ;; the test service proves a1's branch as its turn does (mode :turn: the tests the branch changed,
        ;; held-proof/h among them): it asks the idle agent for :tests
        _ (eval-root "(def held-run (future @(c/prove! sys :a1 #{} :turn)))")
        in (java.io.FileInputStream. ^String began)
        in-test (.read in)
        ;; the child holds in its test: the agent answers an eval meanwhile
        answered (ts/ask root {:op :eval :agent "root" :code "@(c/submit! sys :a1 \"(+ 40 2)\")"})
        out (java.io.FileOutputStream. ^String gate)
        _ (do (.write out 50) (.flush out) (.close out) (.close in))
        result (eval-root "(select-keys @held-run [:pass :fail :error])")]
    (ts/kill-root! root)
    (ts/drop-main! store main)
    (sh/sh "rm" "-rf" runs began gate)
    (is (= 49 in-test) (str "the test child was in its test (" (pr-str defined) ")"))
    (is (= 42 (ts/value-of (ts/value-of (:response answered)))) (str "the agent answered while its test held: " (pr-str answered)))
    (is (= {:pass 1 :fail 0 :error 0} result) (str "and the run answered green once let go: " (pr-str result)))))

(deftest a-blocked-agent-ends-with-its-root
  ;; task seon.agent/an-agent-ends-with-its-root, an agent blocked in a wait: its thread in
  ;; Chez's condition-wait reaches no safe point, so no signal handler runs (SIGIO, root's
  ;; SIGUSR1) until the wait returns. The fork server's group kill on root's EOF ends it
  ;; (task seon.agent/a-signal-wakes-a-blocked-wait makes the agent's own EOF end it too)
  (let [{:keys [agents left out]} (root-ended "(clojure.core.async/<!! (clojure.core.async/chan))" false)]
    (is (= #{:a1 :a2} (set (keys agents))) out)
    (is (empty? left) (str "agents still alive: " left "\n" out))))

(deftest a-test-childs-run-ends-at-its-exit-though-a-grandchild-holds-its-pipe
  ;; agent a3 waited 3.5 h on main (2026-10-26) reading its test child's pipe to an end of file
  ;; that never came: root's :tests in flight, the agent at 0% CPU. A process the test child forks
  ;; holds the write end past the child's exit. The read ends at the end of file or the child's
  ;; exit, whichever comes first, and the child marks its end FD_CLOFORK so nothing it forks holds it.
  ;; The grandchild here waits on a pipe of its own, which the test closes after the read. A
  ;; regression waits for ever (no clock may end it): a hang is this test's red. The result line,
  ;; longer than the reader's 1 MB read, is read whole after the exit
  (let [result (pr-str {:pass 1 :fail 0 :error 0 :reports [] :pad (apply str (repeat 1200000 "x"))})
        run (fn [clofork?]
              (let [[r w] (sock/socketpair)
                    [gr gw] (sock/socketpair)
                    pid (lib/fork! {:refused (fn [n] {:refused n}) :parent identity
                                    :child (fn []
                                             (lib/c-close r) (lib/c-close gw)
                                             (when clofork? (sock/clofork! w))
                                             (lib/fork! {:refused (fn [_] nil) :parent (fn [_] nil)
                                                         :child (fn [] (let [rd (sock/line-reader gr)] (loop [] (when-not (= :eof (rd)) (recur)))))})
                                             (sock/write-line! w result))})]
                (lib/c-close w) (lib/c-close gr)
                (try (let [res (agent/read-to-exit r pid)
                           st (lib/wait-status pid)]
                       (assoc res :status st))
                     (finally (lib/c-close r) (lib/c-close gw)))))]
    (let [held (run false)]
      (is (= {:line result :held? true} (dissoc held :status)) "the child's exit ends the read though a process it forked holds the pipe")
      (is (= {:exit 0} (:status held)) "and the child exited, not killed")
      (is (= [:seon.test/pipe-held] (keep :seon.error/type (:reports (agent/test-run-answer (:line held) (:status held) true))))
          "and the run's answer names the held pipe beside what the child wrote"))
    (is (= {:line result :held? false} (dissoc (run true) :status)) "with FD_CLOFORK on its end, the child's exit is the pipe's end of file")))

(deftest a-test-childs-read-lets-the-agents-other-fibers-run
  ;; the recipe hang of 2026-10-28 (the scratch paper hang-2026-10-28-a1-prove (git history)): agent a1's test child
  ;; asked a1 something mid-run, and a1, reading the child's pipe, was blocked in a native kevent on
  ;; its one main carrier, so the fiber that would answer never ran. The poller's ready is a hint:
  ;; a stale one, left by the read before, ends the next park at once, and the read's wait then
  ;; blocked. The reader here is a main carrier, as an agent is (a pool's other carriers would run
  ;; the fiber and hide it); its writer writes one line, then waits for a go that another fiber of
  ;; the reader sends when it sees that line. A regression waits for ever (no clock may end it): a
  ;; hang is this test's red
  (let [reader (fn []
                 (let [[r w] (sock/socketpair)
                       [gr gw] (sock/socketpair)
                       pid (lib/fork! {:refused (fn [n] {:refused n}) :parent identity
                                       :child (fn []
                                                (lib/c-close r) (lib/c-close gw)
                                                (sock/write-line! w "1")
                                                (let [rd (sock/line-reader gr)] (loop [] (when (= [] (rd)) (recur))))
                                                (sock/write-line! w "2"))})
                       _ (do (lib/c-close w) (lib/c-close gr))
                       sent (promise)
                       res (agent/read-to-exit r pid #(when (= "1" %)
                                                        (clojure.core.async/go (deliver sent (sock/write-line! gw "go")))))]
                   (lib/c-exit (if (and (= {:line "2" :held? false} res) (= true @sent) (= {:exit 0} (lib/wait-status pid))) 0 3))))
        pid (lib/fork! {:refused (fn [n] {:refused n}) :parent identity
                        :child (fn [] (jolt.host/main-carrier-run! reader))})]
    (is (= {:exit 0} (lib/wait-status pid))
        "the reader, a main carrier, read both lines: the go came from its other fiber while the read waited")))

(deftest a-blocked-agent-ends-on-its-socket-eof
  ;; task seon.intr/a-signal-wakes-a-blocked-wait: an agent blocked in a channel take, its fork
  ;; server gone first, ends on its own socket's EOF (SIGIO wakes its condition-wait)
  (let [{:keys [agents left out]} (root-ended "(clojure.core.async/<!! (clojure.core.async/chan))" true)]
    (is (= #{:a1 :a2} (set (keys agents))) out)
    (is (empty? left) (str "agents still alive: " left "\n" out))))

(deftest root-interrupts-a-blocked-take
  ;; task seon.intr/a-signal-wakes-a-blocked-wait: root's interrupt! stops an eval blocked in a
  ;; channel take, and the agent answers its next request
  (let [checkout (seon.config/checkout)
        main (str "blocked-take-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        root (ts/start-root! {:store store :main main :runs runs})
        ask (fn [m] (ts/ask root m))
        eval-root (fn [code] (let [r (ask {:op :eval :agent "root" :code code})] (if (:error r) r (value-of (:response r)))))
        ;; spawn! answers at the agent's hello: it is ready; the eval has begun at its :started event
        _ (eval-root "(c/spawn! sys {})")
        _ (eval-root (str "(let [ev (c/events sys)] (def blocked (c/submit! sys :a1 \"(clojure.core.async/<!! (clojure.core.async/chan))\")) "
                          "(loop [] (let [e (clojure.core.async/<!! ev)] (if (and (= :started (:type e)) (= :a1 (:agent e))) :began (recur)))))"))
        _ (eval-root "(c/interrupt! sys :a1)")
        stopped (eval-root "(clojure.string/starts-with? (:seon.error/message (:ex @blocked)) \"interrupted by root\")")
        next-answer (let [r (ask {:op :eval :agent "a1" :code "(+ 2 2)"})] (if (:error r) r (value-of (:response r))))]
    (ts/stop-root! root)
    (ts/drop-main! store main)
    (sh/sh "rm" "-rf" runs)
    (is (true? stopped) (str "the blocked eval answers interrupted by root: " stopped))
    (is (= 4 next-answer) (pr-str next-answer))))

(deftest an-agent-holds-mains-tests-and-runs-them
  ;; task seon.agent/an-agent-holds-mains-tests: main holds the source root's test namespaces
  ;; (imported from test/), and an agent forked from the fork server holds them compiled:
  ;; it runs one without requiring it, and a green test reports green, a red one red
  (let [green 'seon.reader-test/prose-without-a-fence-is-a-comment
        red 'seon.reader-test/an-agent-made-this-red
        [held _ run] (in-agent #(vector "(boolean (find-ns 'seon.reader-test))"
                                (str "(in-ns 'seon.reader-test) (clojure.test/deftest an-agent-made-this-red (clojure.test/is (= 1 2))) (in-ns 'agent." (:agent %) ")")
                                (str "(let [rs (atom {})]"
                                     " (binding [clojure.test/report (fn [m] (when (#{:pass :fail :error} (:type m))"
                                     " (swap! rs update (symbol (first clojure.test/*testing-vars*)) (fnil conj #{}) (:type m))))]"
                                     " (clojure.test/test-vars [#'" green " #'" red "]))"
                                     " @rs)"))
                               {:dirs ["src" "test"]})]
    (is (true? (:value held)) "the agent holds main's test namespace without requiring it")
    (is (= {green #{:pass} red #{:fail}} (:value run))
        "main's test runs green in the agent; a red one beside it reports red")))

(deftest root-holds-no-test-code-and-its-agents-hold-mains-tests
  ;; task seon.root/root-holds-no-test-code: root imports test/ as facts (reach digests) and evaluates
  ;; none of it; the fork server loads the tests once, so the agent forked from it holds them
  ;; compiled (and a test child it forks runs them: an-agent-holds-mains-tests above)
  (let [;; the code this proves, named so its reach digest moves with it: root's boot and writer, the fork server's load
        _ [#'seon.root/boot! #'seon.writer/main-commit #'seon.fork-server/main]
        t "(some-> (resolve 'seon.reader-test/prose-without-a-fence-is-a-comment) bound?)"
        [agent-held root-held facts] (in-agent [(str "(boolean " t ")")
                                                {:op :eval :agent "root" :code (str "(boolean " t ")")}
                                                {:op :eval :agent "root" :code "(boolean (seon.db/eid @(:conn sys) :var/name 'seon.reader-test/prose-without-a-fence-is-a-comment))"}]
                                               {:dirs ["src" "test"]})]
    (is (true? (:value agent-held)) "the agent holds main's test compiled")
    (is (false? (:value root-held)) "root does not")
    (is (true? (:value facts)) "though main's program holds it as facts")))

(defn- statuses
  "The code of {test status} over the run that code x answers, every test it ran or reused (its
  :digests): an answer carries only its proofs' passes and its reds (seon.proofs/answer), so every
  other test it holds a digest of was green."
  [x] (str "(let [r " x "] (into {} (for [t (keys (:digests r))] [t (get (seon.proofs/results r) t :pass)])))"))

(deftest an-agents-test-run-is-its-proofs-and-what-its-change-reaches
  ;; task seon.agent/an-agent-holds-mains-tests: the agent holds main's whole suite, and its
  ;; test run (root's :tests request, the one the judge sends) selects what the done rule
  ;; reads: the task's proofs and the tests reaching what its branch changed since the main
  ;; it forked from. One redefined function (same behaviour, another form) reaches one test; its namespace's other tests
  ;; and every other namespace's do not run
  (let [proof 'seon.reader-test/prose-without-a-fence-is-a-comment
        reached 'seon.config-test/the-command-line-s-overrides-are-taken-out-of-a-mode-s-args
        ;; the other test that reaches without-overrides (it reads the profile from the command line, 4a34eb9227)
        also 'seon.config-test/the-profile-is-read-from-the-command-line
        [_ run] (in-agent #(vector (str "(in-ns 'seon.config) (defn without-overrides \"args with --config and --set (and their arguments) taken out: what a mode reads.\" {:malli/schema [:=> [:cat [:maybe [:sequential :string]]] [:vector :string]]} [args] (vec (:rest (overrides args)))) (in-ns 'agent." (:agent %) ")")
                           {:op :eval :agent "root" :code (statuses (str "@(seon.root/run-tests! sys :" (:agent %) ")"))})
                          {:dirs ["src" "test"] :proofs #{proof}})]
    (is (= {proof :pass reached :pass also :pass} (:value run))
        "the proof and the tests reaching the change ran, green; nothing else ran")))

(deftest a-plan-with-no-base-costs-what-a-plan-with-one-does
  ;; a plan that selects with no base (mode :main: the test service's run of main, and the tests that run as it
  ;; does) builds its reach graph from the agent's base as any plan does: built from nothing it allocated 853 MB
  ;; against an update's 290 (2026-10-30), and a-death-costs-the-test-it-interrupted-and-no-other and
  ;; a-handful-of-tests-runs-in-one-child went past their eval's 1 GiB byte budget in the full run. Each plan is
  ;; measured with the branch's held graph let go first, by the bytes this thread allocated
  (let [[r] (in-agent [(str "(let [bytes (fn [base] (swap! seon.agent/state dissoc :branch-graph)"
                            "              (let [a (seon.profile/alloc-bytes)] (seon.agent/test-plan (seon.agent/db) base #{}) (- (seon.profile/alloc-bytes) a)))"
                            "      with (bytes (:base @seon.agent/state)) without (bytes nil)]"
                            "  {:with with :without without :ratio (double (/ without with))})")])
        v (:value r)]
    (is (and (map? v) (< (:ratio v) 1.5)) (str "no base costs about what a base does: " (pr-str v)))))

(deftest a-death-costs-the-test-it-interrupted-and-no-other
  ;; task seon.agent/a-test-run-streams-its-reports: the test child wrote the run's one result
  ;; line at its end, so a death anywhere lost every report before it (the test service's run of
  ;; main: 344 tests, one death, 0 results). Each test's reports go as it ends; a death is the
  ;; one :error on the test it interrupted, and the rest run in a new child. Run as the service
  ;; runs main (mode :main, the namespace named)
  (let [[_ r] (in-agent ["(ns app.die-test (:require [clojure.test :refer [deftest is]])) (deftest a-first (is (= 1 1))) (deftest b-dies (seon.lib/c-exit 7)) (deftest c-last (is (= 2 2)))"
                         (str "(let [r (seon.agent/run-tests-forked 100000000 #{} :main (fn [_] nil) '#{app.die-test})]"
                              " (pr-str {:pass (:pass r) :error (:error r) :died (vec (map :var (filter :exit (:reports r))))"
                              "          :passed (vec (sort (map :var (filter #(= :pass (:type %)) (:reports r)))))}))")])
        v (some-> (:value r) clojure.edn/read-string)]
    (is (= {:pass 2 :error 1 :died ["#'app.die-test/b-dies"] :passed ["#'app.die-test/a-first" "#'app.die-test/c-last"]} v) (pr-str r))))

(deftest a-test-run-is-dealt-across-children-and-loses-none
  ;; task seon.agent/a-test-run-streams-its-reports (the deal): the selected tests are dealt by seon.proofs/shards into
  ;; :seon.config.proofs/jobs test children, each its own fork; every test reports once, and a death in one child costs
  ;; only the test it ran. Eight tests of four namespaces and one that dies, jobs 2
  (let [ns-form (fn [n] (str "(ns app.par" n "-test (:require [clojure.test :refer [deftest is]])) (deftest a (is (= 1 1))) (deftest b (is (= 2 2)))"
                             (when (= n 3) " (deftest dies (seon.lib/c-exit 7))")))
        run #(str "(let [pids (atom []) r (seon.agent/run-tests-forked 100000000 #{} :main (fn [p] (swap! pids conj p)) '#{app.par0-test app.par1-test app.par2-test app.par3-test})]"
                  " (pr-str {:children (count (distinct @pids)) :pass (:pass r) :error (:error r) :died (vec (map :var (filter :exit (:reports r))))"
                  "          :ran (vec (sort (map :var (filter #(= :pass (:type %)) (:reports r)))))}))")
        [_ _ _ _ two] (in-agent (conj (mapv ns-form (range 4)) (run))
                                      {:sets [":seon.config.proofs/jobs=2" ":seon.config.proofs/child-min-ms=1"]})
        v #(some-> (:value %) clojure.edn/read-string)]
    (is (<= 2 (:children (v two))) "at least two children forked (a death forks a new one for what its child had left)")
    (is (= [8 1 ["#'app.par3-test/dies"]] ((juxt (comp count :ran) :error :died) (v two))) "all eight passed once, and the death cost only the test it ran")))

(deftest a-message-sent-while-the-agent-waits-on-its-tests-reaches-its-inbox
  ;; task seon.agent/an-agent-has-one-wait: prove! waits for root's answer on the same wait as
  ;; every other ask (await!), so a delivery read there lands in the inbox, as when idle
  (let [[sent proved taken] (in-agent #(vector {:op :message :agent (:agent %) :body "while proving"}
                                               "(select-keys (prove) [:results])"
                                               "(my.agent/messages)"))]
    (is (true? (:ok sent)))
    (is (map? (:value proved)))
    (is (re-find #":body \"while proving\"" (:text taken)) (:text taken))))

(defn- cache-dir
  "A fresh verdict cache directory under this checkout's target/tmp (seon.fx/write-verdicts!)."
  [] (str (seon.config/checkout) "/target/tmp/verdicts-" (random-uuid)))

(defn- own-cache
  "The --set that gives a test's root (and its agents) the verdict cache dir, one this test's own
  process reads and writes too: a root per distinct :sets (in-agent)."
  [dir] (str ":seon.config.proofs/results-dir=" (pr-str dir)))

(deftest a-wide-reach-reuses-unchanged-results
  ;; task seon.proofs/prove-runs-what-a-change-reaches (and seon.agent/a-wide-reach-stays-cheap):
  ;; root's boot records its source tree's inputs on its :seon.root/source fact (its binary is
  ;; built from that tree: bin/test built it), the agent keys its digests by them and selects
  ;; against the main it forked from, and each green is cached by its digest (a test root's cache
  ;; is its own: seon.test-store/start-root!, so nothing an earlier run cached is reused),
  ;; so the next run (the next turn's observe, the judge's :tests, the merge gate) of an
  ;; unchanged branch reruns nothing and answers the same results
  (let [here (seon.config/checkout)
        proof 'seon.reader-test/prose-without-a-fence-is-a-comment
        reached 'seon.config-test/the-command-line-s-overrides-are-taken-out-of-a-mode-s-args
        ;; the other test that reaches without-overrides (it reads the profile from the command line, 4a34eb9227)
        also 'seon.config-test/the-profile-is-read-from-the-command-line
        run #(str "(let [r @(seon.root/run-tests! sys :" % ")] [" (statuses "r") " (:key r)])")
        own (str "(let [p (prove)] {:results " (statuses "p") " :ran (:ran p) :reused (:reused p)})")
        ;; how many of the two tests' verdicts the cache holds at the digests the agent's plan gives
        ;; them (its branch redefines without-overrides, so the reached test's digest is the branch's)
        stored (str "(count (seon.fx/stored-results (select-keys (:digests (seon.agent/test-plan (seon.agent/db) nil #{})) '"
                    (pr-str [proof reached]) ")))")
        [_ inputs first-run files judged second-run]
        ;; a real change that keeps them green (a vector of what the file's answers), so the reached
        ;; test is selected: the agent's change reaches it
        (in-agent #(vector (str "(in-ns 'seon.config) (defn without-overrides \"args with --config and --set (and their arguments) taken out: what a mode reads.\" {:malli/schema [:=> [:cat [:maybe [:sequential :string]]] [:vector :string]]} [args] (vec (:rest (overrides args)))) (in-ns 'agent." (:agent %) ")")
                   {:op :eval :agent "root" :code "(let [r (datahike.api/entity @(:conn sys) [:seon.root/id :root])] [(set (keys (:inputs (:seon.root/source r)))) (= (:boot (:seon.root/source r)) (:seon.root/boot r))])"}
                   own
                   stored
                   {:op :eval :agent "root" :code (run (:agent %))}
                   own)
                  ;; root imports the dirs bin/test does (:seon.config.source/dirs, schemas included), so
                  ;; root's boot shares its source tree's inputs
                  ;; and a root of its own: the shared root's main holds the greens earlier tests
                  ;; recorded, the proof's among them at the same digest (reused 1, ran 1: red
                  ;; since tests-are-facts dropped it)
                  {:dirs (cfg/value :seon.config.source/dirs) :proofs #{proof} :own-root? true})]
    (is (= [#{:env :tree :config :data-refs} true] (:value inputs)) "root's boot recorded its source tree's inputs, this boot's")
    (is (= {:results {proof :pass reached :pass also :pass} :ran 3 :reused 0} (select-keys (:value first-run) [:results :ran :reused])) "the agent's own run runs the proof and the reached test")
    (is (= 2 (:value files)) "and both are cached at the agent's digests")
    ;; the test service answers a key whose run ended with that run (seon.prove): nothing runs again
    (is (= {proof :pass reached :pass also :pass} (first (:value judged))) "root's :tests (the judge's): the same results")
    (is (re-find #"@[0-9a-f]+$" (str (second (:value judged)))) "from the run of the branch at its digest, which nothing since moved")
    (is (= {proof :pass reached :pass also :pass} (:results (:value second-run))) "and so is the agent's next run")))

(deftest one-bound-one-verdict-and-the-judge-reruns-no-green
  ;; task seon.proofs/one-prove-path: a test's tick budget is its own, so two tests that each
  ;; need t ticks are green under a budget of 1.5 t wherever they run (a per-run budget made
  ;; the second red in one run and green alone); the turn's greens are stored at their digests,
  ;; so the judge after it (the plan's tests, the same here: both are the run's proofs) runs
  ;; nothing and forks no child
  (let [[_ budget turn judge]
        ;; top-level forms, each recorded, so each test has a digest its green is stored at
        (in-agent ["(ns bound-test (:require [clojure.test :refer [deftest is]]))
                   (defn work {:malli/schema [:=> [:cat] :int]} [] (reduce + (range 300000)))
                   (deftest a (is (pos? (work))))
                   (deftest b (is (pos? (work))))"
                   "(do (def budget (long (* 3/2 (:ticks (get (seon.proofs/run-tests! '[bound-test/a] {} (fn [_]) nil) 'bound-test/a))))) budget)"
                   "(let [r (seon.agent/run-tests-forked budget #{'bound-test/a 'bound-test/b} :turn)] [(seon.proofs/results r) (:ran r) (some? (:pid r))])"
                   "(let [r (seon.agent/run-tests-forked budget #{'bound-test/a 'bound-test/b} :judge)] [(seon.proofs/results r) (:ran r) (:reused r) (some? (:pid r))])"]
                  {:dirs ["src" "test"] :proofs #{}})]
    (is (pos-int? (:value budget)) (pr-str budget))
    (is (= ['{bound-test/a :pass bound-test/b :pass} 2 true] (:value turn))
        (str "the turn runs both, each within its own budget: green " (pr-str turn)))
    (is (= ['{bound-test/a :pass bound-test/b :pass} 0 2 false] (:value judge))
        (str "the judge after it: the same verdict, both reused, nothing run and no child forked " (pr-str judge)))))

(deftest a-test-run-leaves-nothing-in-its-agents-working-directory
  ;; docs/failure-points.md, a directory walk: a test child works in a directory of its own
  ;; (seon.fx/run-dir, under the agents' working directory), so what a test writes relative to its
  ;; working directory is the run's, removed when the child has ended. The test writes a marker
  ;; relative to its working directory and checks it is not the agents' own; after the run neither
  ;; the marker nor the child's directory is in the agents' working directory.
  (let [[_ run after]
        (in-agent ["(ns leaks-test (:require [clojure.test :refer [deftest is]]))
                   (deftest w (let [cwd (System/getProperty \"user.dir\")]
                                (.mkdirs (java.io.File. \"target/leak\"))
                                (spit \"target/leak/x\" \"x\")
                                (is (.isFile (java.io.File. \"target/leak/x\")))
                                (is (re-find #\"/tests/[0-9]+$\" cwd) cwd)))"
                   "(let [r (seon.agent/run-tests-forked 100000000 #{'leaks-test/w} :turn)] [(seon.proofs/results r) (:pid r)])"
                   "(let [cwd (get seon.config/config :seon.config.agent/cwd)]
                      {:target (.exists (java.io.File. (str cwd \"/target/leak\"))) :dirs (vec (filter #(= \"tests\" %) (.list (java.io.File. cwd))))})"]
                  {:dirs ["src" "test"] :proofs #{}})]
    (is (= '{leaks-test/w :pass} (first (:value run))) (pr-str run))
    (is (false? (:target (:value after))) "the test's relative write is not in the agents' working directory")
    (is (not (.exists (java.io.File. (str "/tmp/seon-agents/tests/" (second (:value run)))))) "and its directory is removed")))

(deftest a-red-at-its-digest-is-reused-with-its-failures
  ;; task seon.proofs/a-red-at-its-digest-is-reused: a run cached r red at its digest; r is green
  ;; if it runs, so a run would say :pass. The judge reuses the red, with the failure its run
  ;; cached, and forks nothing
  (let [[_ dg _ judge]
        (in-agent ["(ns reds-test (:require [clojure.test :refer [deftest is]]))
                   (deftest r (is true))"
                   "(get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/r})) 'reds-test/r)"
                   "(seon.fx/write-verdicts! {:id \"main@1\"}
                                             {'reds-test/r {:test 'reds-test/r :digest (get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/r})) 'reds-test/r) :pass 0 :fail 1 :error 0}}
                                             {'reds-test/r [{:type :fail :message \"the recorded failure\" :expected \"1\" :actual \"2\"}]})"
                   "(let [r (seon.agent/run-tests-forked 100000000 #{'reds-test/r} :judge)]
                      [(seon.proofs/results r) (:ran r) (:reused r) (some? (:pid r)) (:fail r) (mapv (juxt :message :reused) (:reports r))])"]
                  {:dirs ["src" "test"] :proofs #{}})]
    (is (string? (:value dg)) (pr-str dg))
    (is (= ['{reds-test/r :fail} 0 1 false 1 [["the recorded failure" true]]] (:value judge))
        (str "red as recorded, with its failure, nothing run and no child forked " (pr-str judge)))))

(deftest a-test-run-writes-its-results-on-the-branch
  ;; task seon.proofs/test-results-are-a-few-facts (R2a): an agent's run of its proofs records each
  ;; result as facts on the test's var entity on its branch (seon.proofs/result-tx, beside the cache):
  ;; red with its digest and its failing assertions as data, main holding none of it; fixed and run again, green with no
  ;; failure and the sums started over at the new digest; a run that changes nothing writes nothing
  (let [facts "(let [pull (fn [db] (if-let [e (seon.db/eid db :var/name 'facts-test/r)] (datahike.api/pull db '[*] e) {}))]
                 {:branch (select-keys (pull (seon.agent/db)) [:seon.test/digest :seon.test/status :seon.test/failures :seon.test/runs])
                  :main (select-keys (pull (seon.agent/heard-main)) [:seon.test/digest :seon.test/status :seon.test/failures :seon.test/runs])})"
        [_ red fact-red _ green fact-green again]
        (in-agent ["(ns facts-test (:require [clojure.test :refer [deftest is]])) (deftest r (is (= 1 2) \"it is one\"))"
                   "(select-keys (my.agent/prove) [:results])" facts
                   "(deftest r (is (= 1 1)))"
                   "(select-keys (my.agent/prove) [:results])" facts
                   "(let [t (:max-tx (seon.agent/db))] (my.agent/prove) (= t (:max-tx (seon.agent/db))))"]
                  {:dirs ["src" "test"] :proofs #{'facts-test/r}})]
    (is (= {:results '{facts-test/r :fail}} (:value red)))
    (let [{:keys [branch main]} (:value fact-red)]
      (is (= :red (:seon.test/status branch)) (pr-str fact-red))
      (is (string? (:seon.test/digest branch)))
      (is (= [{:type :fail :message "it is one"}] (mapv #(select-keys % [:type :message]) (:seon.test/failures branch))) "its failing assertion, as data")
      (is (= {} main) "main holds none of it"))
    (is (= {:results '{facts-test/r :pass}} (:value green)))
    (let [{:keys [branch]} (:value fact-green)]
      (is (= :green (:seon.test/status branch)))
      (is (nil? (:seon.test/failures branch)) "no failures while green")
      (is (= 1 (:seon.test/runs branch)) "the sums start over at the new digest"))
    (is (true? (:value again)) "a run that changes nothing writes no datoms")))

(deftest an-agents-greens-and-a-checkouts-are-one-store
  ;; owner, 2026-10-23: a test that is the same is not run again once green, "especially when
  ;; freshly branched": an agent's greens and a checkout's (bin/test's) are one cache, keyed by
  ;; the same digests. The checkout caches p1 green there; an agent on the same tree reuses it and
  ;; runs p2, which is then cached too, so the checkout's plan reuses the agent's p2
  (let [here (seon.config/checkout)
        p1 'seon.reader-test/prose-without-a-fence-is-a-comment
        p2 'seon.reader-test/a-fenced-reply-reads-only-its-code
        digests (ts/reach-digests (runner/program-db here (ts/kept-program-store)) (fx/inputs here))
        dir (cache-dir)
        ;; the checkout's green for p1, as bin/test caches one
        recorded (fx/write-verdicts! dir {:id "agent-test"} {p1 {:test p1 :digest (digests p1) :pass 1 :fail 0 :error 0}} {})
        [_ r]
        (in-agent ["nil" "(select-keys (prove) [:results :ran :reused])"]
                  ;; root imports the dirs bin/test does (:seon.config.source/dirs): one program, one digest
                  {:dirs (cfg/value :seon.config.source/dirs) :proofs #{p1 p2} :sets [(own-cache dir)]})
        checkout (proofs/plan nil (select-keys digests [p2]) (fx/stored-results dir (select-keys digests [p2])) #{})]
    (is (= {:recorded 1} recorded) (str "the checkout cached its green for p1: " (pr-str recorded)))
    (is (= {:results {p1 :pass p2 :pass} :ran 1 :reused 1} (:value r)) "the agent reuses the checkout's p1 and runs p2")
    (is (= [] (:run checkout)) "the checkout reuses the agent's p2")))

(deftest a-previous-roots-inputs-are-not-shared
  ;; re-review 2026-10-22: the :root entity outlives its root, so a restart (or a root with no
  ;; source on the same main) must not key its agents' results by a fact an earlier boot wrote
  (let [inputs {:env "e" :tree "t" :config {} :data-refs {}}
        source {:root "/r" :files 0 :forms 0 :ms 0 :boot "b1" :inputs inputs}
        db (fn [boot] (datahike.api/db-with (ts/test-db) [{:seon.root/id :root :seon.root/boot boot :seon.root/source source}]))]
    (is (= inputs (first (agent/test-inputs (db "b1")))) "this boot's inputs: its results are keyed by them")
    (is (nil? (first (agent/test-inputs (db "b2")))) "an earlier boot's: nothing keys by them, so nothing is reused")))

(deftest a-forked-agent-knows-its-task
  ;; task seon.template/a-request-is-one-linked-transaction: root forks the agent for its
  ;; task, so my.task/current answers it at the agent's first form, before any turn loop
  ;; or context read begins (it was nil: root kept the task in its view only)
  (let [names (volatile! nil)
        [id] (in-agent (fn [n] (vreset! names n) ["(:seon.task/id (my.task/current))"]))]
    (is (= (:task @names) (:value id)) (pr-str id))))

(deftest an-agent-inherits-a-static-heap
  ;; design agent-loop section 2: an agent forked after main moved inherits the fork server's
  ;; heap in Chez's static generation, so its collections never copy (un-share) it. Measured
  ;; 2026-10-25: 163 MB of the fork server's heap sat in generations 3 and 4 at fork, and one
  ;; full collect in a fresh agent raised its footprint by 98 MB; made static, 106 MB after a
  ;; work that left today's agent at 333 MB. The bytes in Chez's collected generations are
  ;; work done (what the fork server held live), not load. Measured as the agent found them at
  ;; its start (:inherited-bytes, seon.lib/generation-bytes): its own start-up promotes 2 MB more
  ;; into them, which the fork server's rule (:seon.config.agent/inherited-bytes) does not bound.
  ;; An agent is forked and used first: the fork server's first fork freezes its heap (nothing is
  ;; outside static then, so an agent measured alone inherits 0 and any bound passes); the second
  ;; inherits what the server holds outside static since, which the bound is about.
  (in-agent ["1"])
  (let [[r] (in-agent ["(:inherited-bytes @seon.agent/state)"])
        bound (/ (seon.config/value :seon.config.agent/inherited-bytes) 1048576.0)
        mb (/ (:value r) 1048576.0)]
    (is (< mb bound) (str "the agent inherited " mb " MB outside the static generation: " (pr-str r)))))

(deftest an-agents-collect-trip-is-the-configured-one
  ;; docs/machine.md#what-a-process-owns-in-memory: Jolt's adaptive nursery raises any trip
  ;; below its floor (16 MiB) back to the floor, so a trip set directly never held and agents
  ;; collected every 16 MiB, not the configured 4 (measured 2026-10-28: 16777216); the
  ;; configured trip is the nursery's floor, set with the ceiling
  ;; read after 200 MB of garbage, so collections have run and the adaptive nursery (off for a
  ;; set trip: JOLT_GC_TRIP_BYTES) had its chance to rewrite it
  (let [[_ r] (in-agent ["(dotimes [_ 200] (count (byte-array (* 1024 1024))))"
                         "(jolt.scheme/eval-string \"(collect-trip-bytes)\")"])]
    (is (= (seon.config/value :seon.config.agent/trip-bytes) (:value r)) (pr-str r))))

(deftest a-test-childs-ceiling-is-its-own
  ;; docs/machine.md#what-a-process-owns-in-memory: a test child inherited its agent's absolute
  ;; ceiling, so a child of an agent that had grown had that much less room for its tests; its
  ;; ceiling is now its own heap at its fork plus the same allowance. The agent holds 300 MB
  ;; first, so the inherited ceiling would leave the child about 0.7 of the allowance
  (let [[_ _ r] (in-agent ["(def held (byte-array (* 300 1048576)))"
                           "(ns room-test (:require [clojure.test :refer [deftest is]])) (deftest room (is (< (* 0.95 (seon.config/value :seon.config.agent/heap-extra-bytes)) (- (jolt.scheme/eval-string \"jolt-heap-ceiling-bytes\") (seon.lib/bytes-in-heap)))))"
                           "(let [r (seon.agent/run-tests-forked 100000000 #{} :main (fn [_] nil) '#{room-test})] (pr-str {:pass (:pass r) :fail (:fail r) :error (:error r)}))"])
        v (some-> (:value r) clojure.edn/read-string)]
    (is (= {:pass 1 :fail 0 :error 0} v) (pr-str r))))

(deftest a-fork-servers-static-grows-only-with-the-program
  ;; docs/machine.md#what-a-process-owns-in-memory: the fork server made its heap static before
  ;; every fork, so what each fork replaced (main's db, the reach graph, the index) stayed in
  ;; static for the server's life. Twenty forks with main moving (each a task of its own) but
  ;; no program change: an agent's static at its start is the server's at that fork
  ;; and main's live data growing as it does (each task's facts), not every fork: a freeze is a
  ;; step of more than 64 KB in the server's static (measured: 19 in 20 forks before, 1 after)
  (let [statics (vec (for [_ (range 20)] (:value (first (in-agent ["(jolt.scheme/eval-string \"(bytes-allocated 'static)\")"])))))
        freezes (count (filter #(> % 65536) (map - (rest statics) statics)))]
    (is (<= freezes 2) (str freezes " freezes in 20 forks; the server's static at each: " (pr-str statics)))))

(deftest an-agent-allocates-little-from-ready-to-its-first-request
  ;; docs/machine.md#what-a-process-owns-in-memory: a cost proof in bytes. An agent's first commit
  ;; (it commits the run it began when it first waits) hashed Seon's whole schema, because
  ;; datahike.writing's cell for the last schema hashed was empty in every fresh process: 31.5 MB
  ;; allocated, 9.8 MB of pages written (seon.db/prime-commit!, which the fork server calls so its
  ;; agents inherit the key). The bytes this thread allocated between its :state mark (init done)
  ;; and its first request's form, measured 2026-10-29: 38.5 MB before, 6.5 MB after.
  (let [[r] (in-agent ["(- (seon.profile/alloc-bytes) (nth (last (:marks @seon.agent/state)) 2))"])]
    (is (< (:value r) 12000000) (str "an agent allocated " (:value r) " bytes from its state mark to its first request: " (pr-str r)))))

(deftest an-agent-with-no-task-resumes-a-kept-branch
  ;; found by session global-ids, 2026-10-23: an agent spawned with no task onto a branch the
  ;; store already holds (a kept branch; e2e's fresh agent on a store an earlier run left)
  ;; died in its init, giving seon.task/onto-tx a nil task, and a wait for its :ready never
  ;; ended. A kept branch takes its task from main only when the agent has one
  (let [[r] (in-agent [{:op :eval :agent "root"
                        :code (str "(let [ev (seon.root/events sys) m (seon.db/conn-main (:conn sys))]"
                                   " (datahike.api/branch! (:conn sys) m (seon.db/on-main m :kept))"
                                   " (let [ag (seon.root/spawn! sys {:branch :kept})]"
                                   " (loop [] (let [e (clojure.core.async/<!! ev)]"
                                   " (cond (not= ag (:agent e)) (recur)"
                                   " (#{:ready :dead :ended :killed} (:type e)) (:type e)"
                                   " :else (recur))))))")}]
                      {:own-root? true})]
    (is (= :ready (:value r)) (pr-str r))))

(deftest a-resumed-branchs-agent-is-this-boots
  ;; page's browser round, 2026-10-23: an agent on a kept branch recorded its evaluations under an
  ;; earlier boot's agent (its key read root's boot from the branch, whose root fact is as old as
  ;; the branch). Its key is root's boot as main holds it
  (let [[r k] (in-agent [{:op :eval :agent "root"
                          :code (str "(let [ev (seon.root/events sys) m (seon.db/conn-main (:conn sys)) b (seon.db/on-main m :kept)]"
                                     " (datahike.api/branch! (:conn sys) m b)"
                                     " (let [bc (datahike.api/connect (assoc (:cfg sys) :branch b))]"
                                     "   (datahike.api/transact bc [{:seon.root/id :root :seon.root/boot \"an-earlier-boot\"}]) (datahike.api/release bc))"
                                     " (let [ag (seon.root/spawn! sys {:branch :kept})]"
                                     " (loop [] (let [e (clojure.core.async/<!! ev)]"
                                     " (cond (not= ag (:agent e)) (recur)"
                                     " (#{:ready :dead :ended :killed} (:type e)) [(:type e) (:boot sys)]"
                                     " :else (recur))))))")}
                         ;; on a root of its own (it branches main), in-agent's agent is a1; the one
                         ;; spawned onto the kept branch is a2
                         {:op :eval :agent "a2" :code "(:key @seon.agent/state)"}]
                        {:own-root? true})
        [status boot] (:value r)]
    (is (= :ready status) (pr-str r))
    (is (str/starts-with? (:value k) (str boot "/")) (str "its key names this boot: " (:text k)))))

(deftest a-resumed-branch-holds-what-main-gained
  ;; step E run #11 (2026-10-23): a kept branch made before main imported test/ was resumed,
  ;; and its agent held seon.context-test with no vars (seon.agent/init! unmapped every
  ;; definition main had that the branch did not), so the design never reached its proof's
  ;; fixtures; and it ran the branch's old version of a function main had redefined since.
  ;; A resumed agent runs main with the branch's own changes since its merge base
  (let [[r] (in-agent [{:op :eval :agent "root"
                        :code (str "(let [ev (seon.root/events sys) m (seon.db/conn-main (:conn sys))]"
                                   " @(seon.root/transact-main! sys (seon.source/text-tx @(:conn sys) 'demo.kept \"(ns demo.kept)\\n\\n(defn h [] 1)\\n\" nil))"
                                   " (datahike.api/branch! (:conn sys) m (seon.db/on-main m :kept))"
                                   " @(seon.root/transact-main! sys (seon.source/text-tx @(:conn sys) 'demo.gained \"(ns demo.gained)\\n\\n(defn g [] 7)\\n\" nil))"
                                   " @(seon.root/transact-main! sys (seon.source/text-tx @(:conn sys) 'demo.kept \"(ns demo.kept)\\n\\n(defn h [] 2)\\n\" nil))"
                                   " (let [ag (seon.root/spawn! sys {:branch :kept})]"
                                   " (loop [] (let [e (clojure.core.async/<!! ev)]"
                                   " (cond (not= ag (:agent e)) (recur)"
                                   " (= :ready (:type e)) (:text @(seon.root/submit! sys ag \"[(demo.gained/g) (demo.kept/h)]\"))"
                                   " (#{:dead :ended :killed} (:type e)) (:type e)"
                                   " :else (recur))))))")}]
                      {:own-root? true})]
    (is (str/includes? (str (:value r)) ":value [7 2]") "a var main added, and main's redefinition of one the branch left alone")))

(deftest a-failure-marked-through-the-server-holds-the-task
  ;; owner, 2026-10-22: a failure is task data and root reacts to it. The agent marks its
  ;; task failed on its branch, hands the run back (seon_done), and root, at the
  ;; run's end, holds the task as :failed and reports it among the problems
  (let [names (volatile! nil)
        [_ f _ done seen] (in-agent (fn [{:keys [agent task] :as n}]
                                   (vreset! names n)
                                   [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} "(:reason (my.task/fail! \"the premise is refuted\" '(= 1 2)))"
                                    before-done {:op :done :agent agent}
                                    {:op :eval :agent "root"
                                     :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                                " [(seon.reconcile/task-state (seon.reconcile/index @(:conn sys)) " (pr-str task) ")"
                                                " (select-keys (first (filter #(= " (pr-str task) " (:task %)) (get-in (my.root/problems sys) [:classes :failed :rows]))) [:task :reason :counterexample :open?])])")}]))]
    (is (= "the premise is refuted" (:value f)) (pr-str f))
    ;; and the run says so itself: a failed run is never reported :green
    (is (and (str/includes? (:line done) ":outcome :failed") (str/includes? (:line done) "the premise is refuted")) (pr-str done))
    (is (= [:failed {:task (:task @names) :reason "the premise is refuted" :counterexample "(= 1 2)" :open? true}]
           (:value seen)))))

(deftest an-outside-run-writes-its-end
  ;; found by a worked example (evaluations session, 2026-10-23): an outside agent's :done
  ;; judged a run that had no end, so its run read "ended no-end (the agent wrote no end)"
  ;; whatever its proofs said. The agent writes the end first, as a turn loop does: here its
  ;; one proof is not written, so the end is :red and names why
  (let [[_ _ done seen] (in-agent (fn [{:keys [agent run]}]
                                    [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                                     before-done {:op :done :agent agent}
                                     {:op :eval :agent "root"
                                      :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                                 " (select-keys (get (seon.task/runs @(:conn sys)) " (pr-str run) ") [:seon.run/outcome :seon.run/reason]))")}]))
        run (:value seen)]
    (is (str/includes? (:line done) ":outcome :red") (str "the answer is the verdict line: " (pr-str done)))
    (is (= :red (:seon.run/outcome run)) (pr-str run))
    (is (str/includes? (str (:seon.run/reason run)) "proof-unknown demo-test/t") "the end names why it is red")
    (is (not (str/includes? (str (:seon.run/reason run)) "no end")) "never read as an end nobody wrote")))

(deftest a-branch-that-deletes-mains-test-it-breaks-is-not-merged
  ;; owner, 2026-10-26: "auto merge (the goal) means the old tests need to pass too. So deleting
  ;; the old test doesn't stop the old test from running": a branch breaks what one of main's
  ;; tests checks and deletes that test (unmapped, staged as its removal); the test still judges,
  ;; from main's definition, so the run is red, not merged, and its end names main's test
  (let [main-test 'seon.ref-test/a-section-summary-is-its-heading-and-leads-first-sentence
        [_ _ _ _ _ _ done seen]
        (in-agent (fn [{:keys [agent task run]}]
                    [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                     "(ns demo-test (:require [clojure.test :refer [deftest is]]))"
                     "(deftest t (is (= 2 (inc 1))))"
                     "(in-ns 'seon.ref) (defn- first-sentence \"Every sentence of flat prose.\" {:malli/schema [:=> [:cat :string] :string]} [flat] flat)"
                     (str "(ns-unmap 'seon.ref-test '" (name main-test) ") (seon.stage/stage!) (in-ns 'agent." agent ")")
                     before-done {:op :done :agent agent}
                     {:op :eval :agent "root"
                      :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                 " [(select-keys (get (seon.task/runs @(:conn sys)) " (pr-str run) ") [:seon.run/outcome :seon.run/reason])"
                                 "  (seon.reconcile/task-state (seon.reconcile/index @(:conn sys)) " (pr-str task) ")])")}])
                  {:dirs ["src" "test"]})
        [run state] (:value seen)]
    (is (str/includes? (:line done) ":outcome :red") (str "the answer is the verdict line: " (pr-str done)))
    (is (= :red (:seon.run/outcome run)) (pr-str run))
    (is (not (#{:done :merged} state)) (str "not merged: " state))
    (is (str/includes? (str (:seon.run/reason run)) (str main-test)) "its end names main's failing test")))

(deftest an-outside-run-with-a-green-proof-finishes
  ;; root (2026-10-23): an outside agent that writes its proof green and hands the run back is
  ;; judged by the end it wrote, and its task finishes, not held
  (let [[_ _ t _ done seen] (in-agent (fn [{:keys [agent task run]}]
                                        [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                                         "(ns demo-test (:require [clojure.test :refer [deftest is]]))"
                                         "(deftest t (is (= 2 (inc 1))))"
                                         before-done {:op :done :agent agent}
                                         {:op :eval :agent "root"
                                          :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                                     " [(select-keys (get (seon.task/runs @(:conn sys)) " (pr-str run) ") [:seon.run/outcome :seon.run/status])"
                                                     "  (seon.reconcile/task-state (seon.reconcile/index @(:conn sys)) " (pr-str task) ")])")}])
                                      {:own-root? true})
        [run state] (:value seen)]
    (is (str/includes? (:text t) ":value #'demo-test/t") (pr-str t))
    (is (and (str/includes? (:line done) ":outcome :green") (re-find #":status :(done|merged)" (:line done))) (str "the answer is the verdict line: " (pr-str done)))
    (is (= :green (:seon.run/outcome run)) (pr-str run))
    (is (#{:done :merged} state) (str "the task finishes, never held for root: " state))))

(deftest the-judge-is-a-run-of-the-test-service
  ;; task seon.reconcile/the-judge-asks-the-test-service: the judge at a run's end asks the test
  ;; service (seon.prove), so its run's verdicts are cached by digest like any run's
  (let [[_ _ _ _ _ seen] (in-agent (fn [{:keys [agent run]}]
                                     [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                                      "(ns demo-test (:require [clojure.test :refer [deftest is]]))"
                                      "(deftest t (is (= 2 (inc 1))))"
                                      before-done {:op :done :agent agent}
                                      {:op :eval :agent "root"
                                       :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                                  " (let [db @(:conn sys)]"
                                                  "  [(some-> (java.io.File. (str (seon.config/value :seon.config.proofs/results-dir) \"/demo-test\")) .list vec)"
                                                  "   (:seon.run/outcome (get (seon.task/runs db) " (pr-str run) "))]))")}])
                                   {:own-root? true})
        [cached outcome] (:value seen)]
    (is (= ["t"] cached) (str "the judge's verdict is cached at the test's digest: " (pr-str seen)))
    (is (= :green outcome))))

(deftest an-agent-finds-no-repository
  ;; owner, 2026-10-22: no git in agents, and no source-file editing as the way to develop
  ;; Seon; an agent works in a scratch dir with no repository above it
  ;; (:seon.config.agent/cwd) and still reads any file by its path
  (let [[abs git] (in-agent [(str "(count (slurp " (pr-str (.getCanonicalPath (java.io.File. (seon.config/checkout) "src/seon/reconcile.clj"))) "))")
                             "(clojure.java.shell/sh \"git\" \"rev-parse\" \"--git-dir\")"])]
    (is (= (count (slurp (str (seon.config/checkout) "/src/seon/reconcile.clj"))) (:value abs)) "a file reads by its absolute path")
    (is (str/includes? (:text git) ":exit 128") (str "git finds no repository: " (pr-str git)))
    (is (str/includes? (:text git) "not a git repository") (pr-str git))))

(deftest an-oom-carries-its-heap
  ;; docs/machine.md#how-to-read-an-out-of-memory-error: an agent past its heap bound (:seon.config.agent/heap-extra-bytes)
  ;; gets OutOfMemoryError, and its error map keeps the heap by type (Jolt's :jolt/heap) as data
  (let [[oom after] (in-agent ["(loop [held ()] (recur (conj held (byte-array 1000000))))" "(+ 1 2)"]
                              {:sets [":seon.config.agent/heap-extra-bytes=268435456"]})
        heap (get-in oom [:ex :jolt/heap])]
    (is (str/includes? (str (get-in oom [:ex :seon.error/message])) "heap ceiling") (pr-str (:ex oom)))
    (is (map? heap) (str "the error map keeps :jolt/heap: " (pr-str (:ex oom))))
    (is (<= (+ (:static heap) (:dynamic heap)) (:live heap) (* 1.01 (+ (:static heap) (:dynamic heap)))) "static and dynamic make up the live heap")
    (is (> (:bytes (first (:top heap))) (/ (:dynamic heap) 2)) (str "one type holds most of it: " (pr-str (take 2 (:top heap)))))
    (is (str/includes? (:text after) "3") "the agent lives")))

(deftest an-agent-slurps-a-child-within-its-heap
  ;; the coordinator's reproduction, 2026-10-29: in a forked agent a ProcessBuilder child of ~3 s whose stdout and
  ;; stderr are slurped by two futures took ~6.3 s, the agent's RSS jumped 78 MB -> 1.28 GB after the child exited
  ;; and the next commit ran out of memory. A sampler future reads the all-asleep detector's state (locks.ss
  ;; jolt-asleep-ids, jolt-asleep-told) and the heap while the child runs.
  ;; docs/machine.md#how-to-read-an-out-of-memory-error
  (let [sample (str "(mapv (fn [s] (jolt.scheme/eval-string s)) [\"(hashtable-size jolt-asleep-ids)\" \"(hashtable-size jolt-asleep-told)\" \"(quotient (bytes-allocated) 1048576)\" \"(quotient (current-memory-bytes) 1048576)\"])")
        code (str "(let [t0 (System/nanoTime) ms (fn [] (quot (- (System/nanoTime) t0) 1000000)) log (atom []) "
                  "stop (atom false) "
                  "smp (future nil) "
                  "p (.start (ProcessBuilder. [\"sleep\" \"3\"]))] "
                  "(with-open [so (.getInputStream p) se (.getErrorStream p)] "
                  "(let [o (future (slurp so)) e (future (slurp se)) x (.waitFor p) xo @o xe @e m (ms)] (reset! stop true) @smp {:exit x :ms m :after " sample " :hist (update (jolt.host/heap-histogram 6) :top (fn [t] (mapv (fn [r] (select-keys r [:type :count :bytes])) t)))})))")
        rs (in-agent [code "(+ 1 2)" "(+ 1 2)"])]
    (is (every? #(nil? (:ex %)) rs) (pr-str (mapv #(select-keys % [:ex]) rs)))
    (is (= 0 (:exit (:value (first rs)))) (pr-str (first rs)))
    ;; the reproduction's finding: ~1 GB of garbage during the stall, and live after a collection stays small
    (is (< (get-in (first rs) [:value :hist :dynamic]) 268435456) (pr-str (first rs)))))

(deftest each-eval-call-is-answered-before-the-next
  ;; owner, 2026-10-22 (DECISIONS "Agents work at the REPL, through tool calls"): a reply was a
  ;; batch of forms whose results came only after the whole reply, and 18% of model turns were
  ;; repetition loops asking for answers the model could not see. Now each call of the one
  ;; tool, eval, is answered as its own tool message before the next model call: the scripted model
  ;; (seon.fixtures/two-calls) writes its second call from the answer its first call got
  (let [[end msgs] (in-agent (fn [{:keys [task ns]}]
                               (let [run (str ns "/t#calls")]
                                 [(str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str run) " :max-turns 2})")
                                  (str "(let [db (seon.agent/db)]"
                                       " (->> (:messages (seon.context/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) " :run " (pr-str run)
                                       "                                       :turn 2 :max-turns 2 :origin :model :agent (:key @seon.agent/state)}))"
                                       "      (filter #(#{\"assistant\" \"tool\"} (:role %))) (mapv #(select-keys % [:role :tool-calls :tool-call-id :content]))))")]))
                             {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)
        [a0 r0 a1 r1 :as ms] (:value msgs)]
    (is (= [:cap 2] [(:outcome end) (:model-calls end)]) "two model calls (the proof stays red), then the cap")
    (is (= ["assistant" "tool" "assistant" "tool"] (mapv :role ms)) "each reply followed by its call's answer")
    (is (= [{:id "call-0-0" :code "(+ 20 21)" :prose "Add twenty and twenty-one."}] (:tool-calls a0)))
    (is (= "call-0-0" (:tool-call-id r0)) "the answer names its call")
    (is (str/includes? (:content r0) "41") "the first call's answer")
    (is (= [{:id "call-1-0" :code "(+ 1 41)" :prose "Add one to the value the last call showed."}] (:tool-calls a1)) "the second call was made after the first call's answer, from it")
    (is (str/includes? (:content r1) "42") "and both answers are in the conversation")))

(deftest a-call-after-one-that-threw-is-not-run
  ;; docs/prompt.md: a reply's calls run in order; a call after one that threw is not run, and
  ;; its result says so, so every call has its tool message (the provider refuses a call with none)
  (let [[_ msgs] (in-agent (fn [{:keys [task ns]}]
                             (let [run (str ns "/t#throws")]
                               [(str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str run) " :max-turns 1})")
                                (str "(let [db (seon.agent/db)]"
                                     " (->> (:messages (seon.context/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) " :run " (pr-str run)
                                     "                                       :turn 1 :max-turns 1 :origin :model :agent (:key @seon.agent/state)}))"
                                     "      (filter #(= \"tool\" (:role %))) (mapv #(select-keys % [:tool-call-id :content]))))")]))
                           {:config {:seon.config.ai/script 'seon.fixtures/calls-one-throws}})
        [r0 r1 r2 :as rs] (:value msgs)]
    (is (= ["call-0-0" "call-0-1" "call-0-2"] (mapv :tool-call-id rs)) (str "every call has its result, by its id: " (pr-str msgs)))
    (is (str/includes? (:content r1) "boom") "the second call's error")
    (is (str/includes? (:content r2) "not-run") "the third call was not run, and its result says so")
    (is (str/includes? (:content r2) "call-0-1") "naming the call that threw")
    (is (not (str/includes? (:content r2) ":value 2")) "(inc a) never ran")))

(deftest a-call-is-prose-and-its-forms
  ;; owner, 2026-10-29: a call is prose and one or more forms, run in order; one with no prose or
  ;; no form answers a typed error as its one result and runs nothing (docs/prompt.md)
  (let [problem #'seon.turn/call-problem]
    (is (nil? (problem "Add them." "(+ 1 2)")) "prose and one form")
    (is (= :no-prose (:problem (problem " " "(+ 1 2)"))) "no prose")
    (is (nil? (problem "Two." "(def a 1) (inc a)")) "two forms")
    (is (= [:forms 0] ((juxt :problem :forms) (problem "None." "  "))) "no form")
    (is (nil? (problem "Unread." "(+ 1")) "code that does not read is left to the REPL, which says where"))
  (let [[_ msgs] (in-agent (fn [{:keys [task ns]}]
                             (let [run (str ns "/t#forms")]
                               [(str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str run) " :max-turns 1})")
                                (str "(let [db (seon.agent/db)]"
                                     " (->> (:messages (seon.context/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) " :run " (pr-str run)
                                     "                                       :turn 1 :max-turns 1 :origin :model :agent (:key @seon.agent/state)}))"
                                     "      (filter #(= \"tool\" (:role %))) (mapv :content)))")]))
                           {:config {:seon.config.ai/script 'seon.fixtures/two-forms-in-a-call}})
        [r0 r1 :as rs] (:value msgs)]
    (is (= 2 (count rs)) (str "each call its one result: " (pr-str msgs)))
    (is (not (str/includes? r0 "invalid-call")) "the two-form call runs")
    (is (str/includes? r0 ":forms 2") "each of its two forms ran")
    (is (str/includes? r0 " 2]") "its value each one's, (inc g) after (def g 1)")
    (is (str/includes? r1 ":value 11") "and the call after it runs")))

(deftest a-turn-that-throws-is-the-agents-to-read-and-the-second-ends-its-work
  ;; docs/failure-points.md (a turn of the agent's loop): md-scan #16 registered :seon.ref/block with list keys its
  ;; scan did not make yet, the registration re-armed seon.ref/scan, and the request's own scan threw after the
  ;; turn's draw, out of work! (outcome :no-end). Here the agent breaks Seon's own seon.context/render, which a turn
  ;; calls after its draw: the throw is told to the agent and the loop goes on; the second in a row, with no model
  ;; turn between, ends the work :error, and work! answers its end
  (let [[_ end] (in-agent (fn [{:keys [task ns]}]
                            ["(in-ns 'seon.context) (clojure.core/defn render [& _] (throw (ex-info \"boom: Seon's own render, changed\" {})))"
                             (str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str (str ns "/t#threw")) " :max-turns 3})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)]
    (is (= :error (:outcome end)) (pr-str end))
    (is (str/starts-with? (str (:reason end)) "two turns in a row threw") (pr-str end))
    (is (str/includes? (str (:reason end)) "boom: Seon's own render, changed") "naming its cause"))
  ;; a turn that threw after its model call answered was the agent's turn: it read the last error and acted, so
  ;; the count starts again, and the work goes on to its cap (here seon.turn/usage, read after the reply)
  (let [[_ end] (in-agent (fn [{:keys [task ns]}]
                            ["(in-ns 'seon.turn) (clojure.core/defn usage [& _] (throw (ex-info \"boom: after the reply\" {})))"
                             (str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str (str ns "/t#after")) " :max-turns 3})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)]
    (is (= :cap (:outcome end)) (str "each turn reached its model, so none ended the work: " (pr-str end))))
  ;; md-scan #27: the agent's change broke the status, every turn drew none, and the run made 156 model calls past its
  ;; cap of 40: a turn with no status ends at the cap as every turn does
  (let [[_ end] (in-agent (fn [{:keys [task ns]}]
                            ["(in-ns 'seon.context) (clojure.core/defn status [& _] (throw (ex-info \"boom: the status, broken\" {})))"
                             (str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str (str ns "/t#nostatus")) " :max-turns 3})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)]
    (is (= :cap (:outcome end)) (pr-str end))
    (is (<= (:model-calls end) 3) (str "no model call past the cap: " (pr-str end)))))

(deftest a-run-the-branch-holds-for-another-agent-becomes-this-agents
  ;; task seon.agent/a-death-names-its-cause's resume path (bin/check resume, calc/square capped at 40): an
  ;; agent killed after it committed its run on its branch but before main recorded the run leaves the
  ;; branch's run naming the dead agent; the restarted root starts the same run, and begin-run! must make
  ;; it this agent's, or the run is no session's and every turn draws no status
  (let [[_ _ mine] (in-agent (fn [{:keys [task ns]}]
                               (let [run (str ns "/t#resumed")]
                                 [(str "(seon.agent/transact! [{:seon.run/id " (pr-str run) " :seon.run/agent {:seon.agent/key \"an-earlier-boot/a9\" :seon.agent/id :a9}}])")
                                  (str "(seon.agent/begin-run! " (pr-str task) " " (pr-str run) ")")
                                  (str "(= (:key @seon.agent/state) (-> (seon.agent/db) (datahike.api/entity [:seon.run/id " (pr-str run) "]) :seon.run/agent :seon.agent/key))")])))]
    (is (true? (:value mine)) (str "the run names this agent: " (pr-str mine)))))

(deftest a-step-that-throws-answers-its-error-and-the-agent-goes-on
  ;; task seon.agent/a-death-names-its-cause, as the owner ruled 2026-10-30 (task
  ;; seon.agent/the-agent-is-a-flow-graph, step 4): a defect no request's own boundary catches (here
  ;; the agent redefines, at its REPL as an agent changes the platform, a function its REPL proc calls
  ;; after each eval: seon.db/plain?, which the reply reads) answers that request with the one error
  ;; map, and the agent goes on: it repairs the function at its REPL and works on, the same process
  (let [[_ pid broken repaired after pid'] (in-agent (fn [_]
                                               ["(def p0 (seon.lib/c-getpid))"
                                                "p0"
                                                "(in-ns 'seon.db) (clojure.core/defn plain? [_] (throw (ex-info \"boom: the loop's own function\" {:x 1})))"
                                                "(in-ns 'seon.db) (clojure.core/defn plain? [_] true)"
                                                "(+ 20 22)"
                                                "(seon.lib/c-getpid)"]))]
    (is (= "boom: the loop's own function" (get-in broken [:ex :seon.error/message])) (str "the request is answered with its error: " (pr-str broken)))
    (is (= 'seon.repl/serve! (get-in broken [:ex :seon.error/operation])) "named by the boundary it reached")
    (is (some? repaired) (str "the repair is answered: " (pr-str repaired)))
    (is (= 42 (:value after)) (str "and the agent works on: " (pr-str after)))
    (is (= (:value pid) (:value pid')) "in the same process")))

(defn- started "The line the child writes as test var v starts." {:malli/schema [:=> [:cat :string] :string]}
  [v] (pr-str {:seon.test/started v}))

(deftest a-killed-test-run-names-the-test-that-was-running
  (testing "a child killed while running app-test/slow: that test has no verdict (proofs/results), and its death is named with how the child ended"
    (let [run (agent/test-run-answer (started "#'app-test/slow") {:signal 9} false)]
      (is (= {} (proofs/results run)) "a death is no verdict")
      (is (= [] (proofs/test-run-errors run)) "the death is the running test's, not an anonymous run error")
      (is (= ["#'app-test/slow" {:signal 9}] ((juxt :var :exit) (first (:reports run)))))))
  (testing "a child that wrote its result last: the result"
    (let [res {:pass 1 :fail 0 :error 0 :test 1 :reports [{:type :pass :var "#'app-test/fast" :message nil}]}]
      (is (= res (agent/test-run-answer (pr-str res) {:exit 0} false)))))
  (testing "a child that died before any test started: one run error naming its exit"
    (let [run (agent/test-run-answer nil {:exit 1} false)]
      (is (= {} (proofs/results run)))
      (is (= 1 (count (proofs/test-run-errors run)))))))

(deftest after-merge-main-the-agent-judges-against-the-new-base
  ;; task seon.agent/an-agents-base-is-its-merge-base: an agent chooses the tests that judge
  ;; its branch from what it changed since :base. merge-main! left :base at the main the agent
  ;; forked from, so main's own changes, merged in, counted as the agent's. In this process,
  ;; on a branch of the test store, with a socketpair standing for root
  (ts/test-branch!
   (fn [cfg conn]
     (let [ab (pdb/on-main (:branch cfg) :agent-base)
           _ (d/branch! conn (:branch cfg) ab)
           forked @conn
           ;; main redefines app.m0/f1 after the agent forked: a new form, the var pointed to it
           [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f1] [?v :var/form ?f] [?f :form/ns ?n]] forked)
           _ (d/transact conn [{:db/id "f1'" :form/id "app.m0/f1-redefined" :form/ns nsx :form/order 100 :form/defines [v]
                                :form/src "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 100))"}
                               [:db/add v :var/form "f1'"]])
           store-conn (d/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state]
       (try
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :key "test/a1" :db @store-conn :base forked})
         (is (empty? (pm/changed-vars (agent/branch-changes (:base @agent/state) (agent/db)))) "the agent has changed nothing")
         (let [r (agent/merge-main! #{})]
           (is (some #{[:var 'app.m0/f1]} (:applied r)) "main's change merges in")
           (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity (agent/db) (:t r))))
               "in a merge commit that names its runtime (task seon.db/each-commit-names-its-runtime)")
           (is (= [ab (:branch cfg)] ((juxt :seon.tx/branch :seon.tx/merged) (d/entity (agent/db) (:t r))))
               "made on the agent's branch, of what it merged from main: a caught-up fact reads as main's (task seon.ctx/a-change-names-its-eval)")
           ;; a fact on the agent's branch, no join of its pull (task seon.agent/the-agent-is-the-unit-not-the-run)
           (let [db (agent/db)
                 [cu] (for [e (d/q '[:find [?c ...] :in $ ?a :where [?c :seon.catch-up/agent ?a]] db (:db/id (d/entity db [:seon.agent/key "test/a1"])))]
                        (d/pull db '[*] e))]
             (is (= [(:branch cfg) (:max-tx @conn)] [(:seon.catch-up/from cu) (:seon.catch-up/from-t cu)])
                 "the catch-up a fact of the agent's branch (task seon.ctx/one-derivation)")
             (is (some #{[:var 'app.m0/f1]} (:seon.catch-up/applied cu)) "with what it applied")))
         (is (empty? (pm/changed-vars (agent/branch-changes (:base @agent/state) (agent/db))))
             "and is main's, not the agent's: its base is the merge base")
         (is (= {} (pm/divergence (agent/branch-changes (:base @agent/state) (agent/db))))
             "so the agent's divergence carries none of main's changes")
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest an-agent-keeps-no-db-older-than-its-branchs-current
  ;; docs/machine.md#roots-heap, a kept db value is a reference: after a run of its own commits, a
  ;; read of what they changed, its test plan's graph and a merge of main, the agent's state holds
  ;; its branch's current db and nothing older but the two main points it is judged by (:base, the
  ;; merge base, and :main, as last heard: seon.turn, seon.render.ui and my.branch read them as
  ;; dbs, so they stay values until those readers take a commit: reported with this change). A
  ;; graph, a diff and the last transaction are keyed by a point or let go (seon.db/point). In this
  ;; process, on a branch of the test store, with a socketpair standing for root.
  (ts/test-branch!
   (fn [cfg conn]
     (let [ab (pdb/on-main (:branch cfg) :agent-refs)
           _ (d/branch! conn (:branch cfg) ab)
           forked @conn
           store-conn (d/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state
           note (fn [i] (agent/transact! [{:note/text (str "n" i)}]))
           stale #(for [[path t] (ts/stale-dbs (apply dissoc @agent/state %) (:max-tx (agent/db)))] [path t])]
       (try
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :key "test/a1" :db @store-conn :base forked :main forked})
         (let [[_ in] (agent/test-inputs (agent/db))]
           (doseq [i (range 4)]
             (note i)
             (agent/branch-changes (:base @agent/state) (agent/db))
             (agent/branch-graph (agent/db) (:base @agent/state) in))
           (is (= [] (stale [:base :main])) "after commits of its own")
           ;; main redefines app.m0/f1: a program change, which a merge of main brings in
           (let [[v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f1] [?v :var/form ?f] [?f :form/ns ?n]] forked)]
             (d/transact conn [{:db/id "f1'" :form/id "app.m0/f1-redefined" :form/ns nsx :form/order 100 :form/defines [v]
                                :form/src "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 100))"}
                               [:db/add v :var/form "f1'"]]))
           (agent/heard-main)
           (is (some #{[:var 'app.m0/f1]} (:applied (agent/merge-main! #{}))) "main's change merges in")
           (is (= [] (stale [:base :main])) "right after a merge of main, whose db-after is not the branch's now")
           (note 5)
           (agent/branch-changes (:base @agent/state) (agent/db))
           (agent/branch-graph (agent/db) (:base @agent/state) in)
           (is (= [] (stale [:base :main])) "and after more of its own"))
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest merge-main-brings-the-task-content-main-carried
  ;; live finding F3 (pipeline 1): a session's merge-main! moved its base to main's head but
  ;; brought only the program, so the task content main had carried since the fork (another
  ;; session's design: its plan and registered proof) read at the run's end as this branch
  ;; reverting it, and seon.task/carry-tx wrote that revert onto main; the other session's
  ;; implement was then judged :no-proofs
  (ts/test-branch!
   (fn [cfg conn]
     (let [_ (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "x" :seon.task/plan "Make x." :seon.task/proofs #{}}]))
           ab (pdb/on-main (:branch cfg) :agent-carried)
           _ (d/branch! conn (:branch cfg) ab)
           forked @conn
           [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f1] [?v :var/form ?f] [?f :form/ns ?n]] forked)
           ;; main: another session's design carried onto demo/x, and a program change (what makes merge-main! commit)
           _ (d/transact conn [[:db/add [:seon.task/id "demo/x"] :seon.task/proofs 'demo-test/x-design-wrote-test]
                               [:db/add [:seon.task/id "demo/x"] :seon.task/plan "Make x. The design: f1 adds 100."]
                               {:db/id "f1'" :form/id "app.m0/f1-carried" :form/ns nsx :form/order 100 :form/defines [v]
                                :form/src "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 100))"}
                               [:db/add v :var/form "f1'"]])
           store-conn (d/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state]
       (try
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :key "test/a1" :db @store-conn :base forked})
         (agent/merge-main! #{})
         (let [x (task/task (agent/db) "demo/x")]
           (is (= #{'demo-test/x-design-wrote-test} (set (:seon.task/proofs x))) "main's carried proof is on the branch")
           (is (= "Make x. The design: f1 adds 100." (:seon.task/plan x)) "and its plan"))
         (is (= [] (:tx (task/carry-tx (:base @agent/state) (agent/db) @conn)))
             "so the run's end carries nothing of it back onto main")
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest a-run-takes-its-sessions-entries-from-main
  ;; design one-derivation 4.1 section 5: delivery is the fact. A session whose process is gone
  ;; is spawned again, and its run must take the entries main holds to its earlier runs; a frame
  ;; pushed only to a live process left such a run with nothing, parked short of main's count, and
  ;; spawned again
  (ts/test-branch!
   (fn [cfg conn]
     (let [_ (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Wait." :seon.task/proofs #{}}]))
           ab (pdb/on-main (:branch cfg) :agent-entries)
           ;; main names a run's branch as it is; the store qualifies it on a test's main
           env {:branch :agent-entries :model nil :max-turns 4}
           _ (d/transact conn (into (task/run-start-tx "demo/w#1" "demo/w" "boot-1" :a1 env 1000 "Wait.")
                                    (task/run-start-tx "demo/w#2" "demo/w" "boot-1" :a2 env 1000 "Wait.")))
           ;; the session's branch, then on main: a verdict to its earlier run, a message to another session's run
           _ (d/branch! conn (:branch cfg) ab)
           _ (d/transact conn (into (task/message-tx "m-v" :verdict "root" "demo/w#1" "red: demo-test/w is red")
                                    (task/run-start-tx "demo/x#1" "demo/w" "boot-1" :a3 {:branch :elsewhere :model nil :max-turns 4} 1000 "Wait.")))
           _ (d/transact conn (task/message-tx "m-x" :message "user" "demo/x#1" "not yours"))
           store-conn (d/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state]
       (try
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :run "demo/w#2" :db @store-conn})
         (is (= [{:id "m-v" :kind :verdict :from "root" :to "demo/w#1" :body "red: demo-test/w is red"}]
                (agent/untaken-entries))
             "the session takes what main holds to its earlier run, and nothing of another session's")
         (agent/transact! (task/message-tx "m-v" :verdict "root" "demo/w#1" "red: demo-test/w is red"))
         (agent/commit!)
         (is (= [] (agent/untaken-entries)) "once its branch holds it, it is taken")
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest a-runs-outcome-on-main-reaches-the-agents-ctx-at-its-next-take
  ;; owner, 2026-10-26: take! is the one way outside facts reach the agent's branch, and so its
  ;; ctx. Root writes its session's runs' outcome and reason on main, and a reader's opened
  ;; eval as the agent's :seon.agent/opened; the copy take! transacts (untaken-facts) puts each on
  ;; the branch, and @ctx, which follows the branch, holds it; a second copy has nothing to add
  (ts/test-branch!
   (fn [cfg conn]
     (let [_ (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Wait." :seon.task/proofs #{}}]))
           ab (pdb/on-main (:branch cfg) :agent-taken)
           k (task/agent-key "boot-1" :a1)
           ;; as in production: the branch is made, the run starts on main, and the branch holds
           ;; the run by reference only, as its turns record it (its id, task and agent)
           _ (d/branch! conn (:branch cfg) ab)
           _ (d/transact conn (task/run-start-tx "demo/w#1" "demo/w" "boot-1" :a1 {:branch :agent-taken :model nil :max-turns 4} 1000 "Wait."))
           store-conn (d/connect (assoc cfg :branch ab))
           _ (d/transact store-conn [{:db/id k :seon.agent/key k :seon.agent/id :a1}
                                     {:seon.run/id "demo/w#1" :seon.run/task [:seon.task/id "demo/w"] :seon.run/agent k}
                                     {:seon.eval/id "e1" :seon.eval/agent [:seon.agent/key k] :seon.eval/by "a1" :seon.eval/record {:code "1" :response {}}}])
           ;; on main: what root decided of the run, and the page's open
           _ (d/transact conn [{:seon.run/id "demo/w#1" :seon.run/status :parked :seon.run/outcome :waiting :seon.run/reason "waiting for a message"}
                               {:seon.agent/key k :seon.agent/opened #{"e1" "e9"}}])
           [a b] (sock/socketpair)
           old @agent/state
           ;; a collection's member in the agent's ctx, found by its id
           member (fn [coll attr id] (some #(when (= id (attr %)) %) (vals (get @seon.ctx/ctx coll))))]
       (try
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :run "demo/w#1" :key k :db @store-conn})
         (is (nil? (:seon.run/outcome (d/entity (agent/db) [:seon.run/id "demo/w#1"]))) "before the take, nothing of main's")
         (is (= [{:seon.run/id "demo/w#1" :seon.run/outcome :waiting :seon.run/reason "waiting for a message"}
                 {:seon.eval/id "e1" :seon.eval/opened true}]
                (agent/untaken-facts))
             "what main holds of its run and its evals that the branch does not; e9 is no eval of its branch")
         (is (= [] (agent/take-main!)) "the take a turn makes (seon.turn/take!): no entries here, the facts written")
         (is (= [nil :waiting "waiting for a message"]
                (mapv (d/entity (agent/db) [:seon.run/id "demo/w#1"]) [:seon.run/status :seon.run/outcome :seon.run/reason]))
             ;; read from the branch: the agent's record no longer pulls runs (the-agent-is-the-unit-not-the-run, cut 1)
             "the run's outcome and reason on main are on its branch after the take; its status is not taken")
         (is (= [] (task/running (agent/db))) "so the branch holds no whole run: its reference is no running run")
         (is (true? (:seon.eval/opened (member :evals :seon.eval/id "e1"))) "and the opened eval")
         (is (= [] (agent/untaken-facts)) "taken: nothing more to copy")
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest a-stall-task-closes-when-its-conditions-hold
  ;; owner, 2026-10-26: root's stall task (seon.template/stall) is done when its boundary
  ;; conditions hold, which are its proofs (seon.stall-proofs): red while the agent is stalled and
  ;; no cause is recorded; green once main retracts the stall and the worker records the cause
  ;; with its evidence and decides it is no bug, through my.task/update!
  (ts/test-branch!
   (fn [cfg conn]
     (let [k (task/agent-key "boot-1" :a1)
           stall (tpl/stall :a1 "demo/t#1" {:quiet-ms 4000 :pid 77})
           _ (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan "Work." :seon.task/proofs #{}} stall]))
           _ (d/transact conn (task/run-start-tx "demo/t#1" "demo/t" "boot-1" :a1 {:branch :task-demo-t :model nil :max-turns 4} 1000 "Work."))
           _ (d/transact conn [{:seon.agent/key k} {:seon.process/id (str k "/77") :seon.process/agent [:seon.agent/key k] :seon.process/stall {:quiet-ms 4000}}])
           ab (pdb/on-main (:branch cfg) :stall-task)
           _ (d/branch! conn (:branch cfg) ab)
           store-conn (d/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state
           proofs (fn [] (let [reps (atom [])]
                           (binding [clojure.test/report (fn [m] (swap! reps conj (:type m)))]
                             (doseq [v (sort tpl/stall-proofs)] ((:test (meta (resolve v))))))
                           (frequencies @reps)))]
       (try
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :task "root/stall-a1-demo-t-1" :db @store-conn})
         (is (pos? (:fail (proofs) 0)) "red: the agent is stalled, no cause is recorded")
         (d/transact conn [[:db.fn/retractAttribute [:seon.process/id (str k "/77")] :seon.process/stall]])
         (my.task/update! :seon.task/cause merge {:what "a test child parked on a pipe nobody wrote" :evidence {:sample "..." :lsof "..."} :bug? false})
         (agent/commit!)
         (is (= {:pass 5} (proofs)) "green: the stall ended, the cause recorded with its evidence, and it is no bug")
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest an-agents-transactions-name-their-runtime
  ;; task seon.db/each-commit-names-its-runtime: what an agent writes on its branch
  ;; (seon.agent/transact!, the run-ahead writer) carries the runtime that made it
  (ts/test-branch!
   (fn [cfg conn]
     (let [ab (pdb/on-main (:branch cfg) :agent-tx)
           _ (d/branch! conn (:branch cfg) ab)
           store-conn (d/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state]
       (try
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :db @store-conn})
         (let [{:keys [t]} (agent/transact! [{:note/text "the agent's"}])]
           (agent/commit!)
           (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity @store-conn t))))
           (is (nil? (:seon.tx/eval (d/entity @store-conn t))) "written outside any eval: it names none"))
         ;; task seon.ctx/a-change-names-its-eval: a write an eval's code makes names that eval
         (let [r (repl/eval-string "(seon.agent/transact! [{:note/text \"an eval's\"}])" {"ns" "user" :origin :outside})
               t (:t (:value r))]
           (agent/commit!)
           (is (= [ab (name (:handle r))] ((juxt :seon.tx/branch :seon.tx/eval) (d/entity @store-conn t))) (str "the eval that wrote it: " (:ex r)))
           (is (nil? (:eval @agent/state)) "and once the eval ends, a write names no eval"))
         (let [r (repl/eval-string "(throw (ex-info \"boom\" {}))" {"ns" "user" :origin :outside})]
           (is (:ex r) "an eval that throws")
           (is (nil? (:eval @agent/state)) "leaves no eval named"))
         (let [r (repl/eval-string "(do (seon.repl/eval-string \"1\" {\"ns\" \"user\" :origin :screen}) (:eval @seon.agent/state))" {"ns" "user" :origin :outside})]
           (is (= (name (:handle r)) (:value r)) "an eval inside one names its own, then the outer one's again"))
         ;; recorded as an agent records: the eval's own record names it too
         (swap! agent/state assoc :mode :agent :key "test/a1" :id :a1)
         (let [r (repl/eval-string "(+ 1 2)" {"ns" "user" :origin :outside})]
           (agent/commit!)
           (is (= (name (:handle r)) (:record r)) "recorded")
           (is (= (name (:handle r)) (:seon.tx/eval (d/entity @store-conn (:tx (first (d/datoms @store-conn :avet :seon.eval/id (:record r)))))))
               "its record's transaction names it"))
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))


(deftest a-root-with-no-provider-ends-the-run-on-its-typed-error
  ;; root's ruling 2026-10-22: a root with no model provider (no --profile) answers the
  ;; first turn with :seon.model/no-provider; the error reaches whoever can act on it: the
  ;; run's record on main and my.root/problems' :error rows
  (let [q #(str "[:find (pull ?e [:seon.run/outcome :seon.run/error]) . :where [?e :seon.run/task ?t] [?t :seon.task/id \"" % "/m\"] [?e :seon.run/error]]")
        [_ r] (in-agent (fn [{:keys [ns]}]
                          [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root"
                           :code (str "(do (my.root/run! sys [\"" ns "/m\"])"
                                      " (let [a (datahike.api/q '" (q ns) " (seon.root/await-db sys #(datahike.api/q '" (q ns) " %)))]"
                                      " [(:seon.run/outcome a) (:seon.error/type (:seon.run/error a))"
                                      " (mapv #(get-in % [:error :seon.error/type]) (get-in (my.root/problems sys) [:classes :error :rows]))]))")}])
                        {:config {:seon.config.ai/provider :none}
                         :decls [{:seon.task/name "m" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})]
    (is (= [:error :seon.model/no-provider [:seon.model/no-provider]] (:value r)) (pr-str r))))

(deftest a-green-whole-task-merges-itself-with-no-act-beyond-done
  ;; owner, 2026-10-26: "we have the agents have live feedback so we can know if we can auto merge
  ;; so test for it": through my.root/request! (the default, one whole task) and the reconciler, a
  ;; run whose proof passes on its branch ends green, and root merges its branch into main: the
  ;; agent does nothing beyond its work
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"wg\" :seon.task/plan \"Make wg.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/whole-green}})"
                   " _ (seon.root/await-db sys \"the run ended\" #(or (= :merged (seon.reconcile/task-state (seon.reconcile/index %) \"" % "/wg\"))"
                   "                                    (#{:parked :held :done :closed :merged :dead} (:seon.run/status (datahike.api/entity % [:seon.run/id \"" % "/wg#1\"])))))"
                   " db @(:conn sys) r (seon.task/run db \"" % "/wg#1\")]"
                   " [(:seon.task/kind (seon.task/task db \"" % "/wg\")) (:seon.run/outcome r) (:seon.run/status r) (some? (seon.db/eid db :var/name 'app.whole-test/t))])")
        [_ r] (in-agent #(vector {:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))})
                        {:own-root? true})]
    (is (= [:whole :green :merged true] (:value r)) (pr-str r))))

(deftest a-run-whose-proofs-pass-is-done-only-when-what-it-reaches-is-green
  ;; the owner's ruling: a turn runs the proofs, the tests the change reaches run once they are green, judged as the
  ;; judge judges them, before it is done. md-scan #24 ended green on its proofs and the judge found 18 reds its change
  ;; made: here a green proof beside a change that breaks main's edit test (seon.fixtures/whole-stray) does not end
  ;; green, and its next turn's status names the red
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"ws\" :seon.task/plan \"Make ws.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/whole-stray}})"
                   " _ (seon.root/await-db sys \"the run ended\" #(#{:parked :held :done :closed :merged :dead} (:seon.run/status (datahike.api/entity % [:seon.run/id \"" % "/ws#1\"]))))"
                   " db @(:conn sys) r (seon.task/run db \"" % "/ws#1\")"
                   " b (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"" % "/ws\")}}))"
                   " a (datahike.api/q '[:find ?a . :where [?r :seon.run/id \"" % "/ws#1\"] [?r :seon.run/agent ?a]] b)]"
                   " [(:seon.run/outcome r) (seon.reconcile/task-state (seon.reconcile/index db) \"" % "/ws\") (mapv (juxt :reason :symbol) (:reasons (seon.context/status b a {})))])")
        [_ r] (in-agent #(vector {:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))})
                        {})
        [outcome state reasons] (:value r)]
    (is (not= :green outcome) (pr-str r))
    (is (not= :merged state) "not merged")
    (is (some #{[:stray-red 'seon.repl-test/an-edit-replaces-exact-text-once-and-evaluates-the-definition]} reasons)
        (str "its status names the test its change broke: " (pr-str reasons)))))

(deftest a-red-whole-task-is-not-merged-and-its-next-turn-says-why
  ;; the other way: a whole run whose proof is red is not merged. Its run does not end red: it
  ;; parks (it waits, or reaches its cap), and its turn after the red proof carries why, the done
  ;; rule's reasons as data in the status it reads (a run root refuses at its end gets root's verdict
  ;; instead: seon.reconcile-test/a-whole-task-works-in-parallel-and-its-green-merges). The wait
  ;; ends on the run's own end, which always comes
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"wr\" :seon.task/plan \"Make wr.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/whole-red}})"
                   " _ (seon.root/await-db sys \"the run ended\" #(#{:parked :held :done :closed :merged :dead} (:seon.run/status (datahike.api/entity % [:seon.run/id \"" % "/wr#1\"]))))"
                   " db @(:conn sys) r (seon.task/run db \"" % "/wr#1\")"
                   " b (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"" % "/wr\")}}))"
                   " a (datahike.api/q '[:find ?a . :where [?r :seon.run/id \"" % "/wr#1\"] [?r :seon.run/agent ?a]] b)]"
                   " [(:seon.run/status r) (seon.reconcile/task-state (seon.reconcile/index db) \"" % "/wr\") (mapv :reason (:reasons (seon.context/status b a {})))])")
        [_ r] (in-agent #(vector {:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))})
                        {})
        [status state reasons] (:value r)]
    (is (= :parked status) (pr-str r))
    (is (not= :merged state) "not merged")
    (is (= [:proof-red] reasons) "the status carries why, as data: the proof is red")))

(deftest a-requests-implement-continues-its-designs-request
  ;; live finding A1, on a real request (the pipeline's: the implement's first request sent its own
  ;; task data in second place and no boundary, 512 of 35,126 tokens cached): through my.root/request! with the
  ;; staged choice (seon.template/stages, an experiment's: the default request is design-build and merge) and
  ;; the reconciler, the implement's first turn, on the design's session, sends the design's task data
  ;; where the design's requests did, the design's last request whole as its prefix, then root's
  ;; boundary message (seon.reconcile/stage-boundary), read off the turns as the branch recorded them
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"st\" :seon.task/plan \"Make st.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/stages}} seon.template/stages)"
                  ;; the implement parked, or a stage ended without it: its run judged (past :ended), the design's
                  ;; stopped short of done (parked, dead, lost), or a stage's task is held; a fact either way,
                  ;; so a red answers red rather than waiting
                  " _ (seon.root/await-db sys \"the implement parked, or a stage ended\""
                  "      #(let [held? (fn [id] (= :held (:seon.task/execution (datahike.api/entity % [:seon.task/id id]))))"
                  "             status (fn [id] (:seon.run/status (datahike.api/entity % [:seon.run/id id])))]"
                  "         (or (not (contains? #{nil :running :ended} (status \"" % "/st-implement#1\")))"
                  "             (not (contains? #{nil :running :ended :done} (status \"" % "/st#1\")))"
                  "             (held? \"" % "/st\") (held? \"" % "/st-implement\"))))"
                  " ended (into {} (for [id [\"" % "/st#1\" \"" % "/st-implement#1\"]]"
                  "                  [id (when-let [e (datahike.api/entity @(:conn sys) [:seon.run/id id])] {:status (:seon.run/status e) :outcome (:seon.run/outcome e) :reason (:seon.run/reason e)})]))"
                  " b (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"" % "/st\")}}))"
                  " sent (fn [run] (mapv second (sort (datahike.api/q '[:find ?n ?s :in $ ?id :where [?r :seon.run/id ?id] [?u :seon.turn/run ?r] [?u :seon.turn/n ?n] [?u :seon.turn/origin :model] [?m :seon.model-call/turn ?u] [?m :seon.model-call/sent ?s]] b run))))"
                  " boundary (datahike.api/q '[:find ?mid . :where [?r :seon.run/id \"" % "/st-implement#1\"] [?m :seon.message/to ?r] [?m :seon.message/from \"root\"] [?m :seon.message/id ?mid]] b)]"
                  " {:ended ended :design (sent \"" % "/st#1\") :implement (sent \"" % "/st-implement#1\") :boundary boundary})")
        names (volatile! nil)
        [_ r] (in-agent #(do (vreset! names %) [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))}])
                        {})
        {:keys [ended design implement boundary]} (:value r)
        last-design (pop (peek design)) first-implement (first implement)]
    (is (= :parked (get-in ended [(str (:ns @names) "/st-implement#1") :status])) (str "the implement parked; the stages' runs: " (pr-str ended)))
    (is (and (seq design) (seq implement) boundary) (pr-str r))
    (is (= (second (first design)) (second first-implement)) "the design's task data, in the same place")
    (is (= last-design (subvec first-implement 0 (min (count first-implement) (count last-design)))) "the design's last request is its exact prefix (its hashes aside: the implement's own replace them)")
    (is (some #(= [:message boundary] (vec (take 2 %))) (drop (count last-design) first-implement)) "then root's boundary")))

(deftest a-waiting-run-parks-and-a-message-wakes-the-same-process
  ;; design agent-loop section 2 (owner, 2026-10-25: "Always park"): a run that waits parks its
  ;; session, its process kept; the owner's message wakes it, and the next run is the same
  ;; process (its pid), on the same agent (the parked run's own: the harness's agent is another).
  ;; The wake's run starts clean in that process: it takes the message and runs turns of its own
  ;; (flow-lab's hazards 1 and 2: a stop left by the parked run ended the next at once with the
  ;; message untaken; turn numbers restarted over the recorded ones)
  (let [q (fn [w] (str "[:find ?id ?st ?out ?ag ?taken ?turns :where [?e :seon.run/task ?t] [?t :seon.task/id \"" w "\"] [?e :seon.run/id ?id] [?e :seon.run/status ?st] [?e :seon.run/outcome ?out] [?e :seon.run/agent ?a] [?a :seon.agent/id ?ag] [?e :seon.run/taken ?taken] [?e :seon.run/turns ?turns]]"))
        parked (fn [w n] (str "(seon.root/await-db sys \"the runs parked, or one ended\" #(let [rows (datahike.api/q '" (q w) " %)] (or (= " n " (count (filter (fn [[_ st]] (= :parked st)) rows))) (some (fn [[_ st]] (#{:held :done :closed :merged :dead} st)) rows))))"))
        names (volatile! nil)
        [_ r] (in-agent (fn [{:keys [ns] :as n}]
                          (vreset! names n)
                          (let [w (str ns "/w")]
                            [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                             {:op :eval :agent "root"
                              :code (str "(do (my.root/run! sys [" (pr-str w) "]) " (parked w 1)
                                         " (let [ag (some (fn [[id _ _ ag]] (when (= " (pr-str (str w "#1")) " id) ag)) (datahike.api/q '" (q w) " @(:conn sys)))"
                                         " pid1 (get-in (c/state sys) [:agents ag :pid])]"
                                         " @(seon.root/message! sys ag \"carry on\") " (parked w 2)
                                         " {:runs (sort (datahike.api/q '" (q w) " @(:conn sys))) :pid1 pid1 :pid2 (get-in (c/state sys) [:agents ag :pid])"
                                         " :turns (sort (datahike.api/q '[:find [?id ...] :where [_ :seon.turn/id ?id]] (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch " (pr-str w) ")}}))))}))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/wait-call}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})
        {:keys [runs pid1 pid2 turns]} (:value r)
        w (str (:ns @names) "/w")
        numbers (fn [run] (sort (keep #(when (str/starts-with? % (str run "/")) (parse-long (subs % (inc (count run))))) turns)))]
    (is (= [[(str w "#1") :parked :waiting] [(str w "#2") :parked :waiting]] (mapv #(subvec % 0 3) runs)) (pr-str r))
    (is (apply = (map #(nth % 3) runs)) "both runs on one agent")
    (is (= [0 1] (mapv #(nth % 4) runs)) "the wake's run took the message the parked one had not")
    (is (every? pos? (map #(nth % 5) runs)) "and ran turns of its own")
    (is (= (range (nth (first runs) 5)) (numbers (str w "#1"))) "the parked run's turns, numbered from 0")
    (is (= (range (nth (second runs) 5)) (numbers (str w "#2"))) "the wake's, numbered from its own 0, none over the parked run's")
    (is (and pid1 (= pid1 pid2)) (str "one process for both runs: " pid1 " " pid2))))

(deftest a-prose-reply-stays-in-the-history
  ;; task seon.context/the-context-is-the-agents-evals-and-seons (R1): a reply of prose with no call
  ;; is the agent winding down; seon.context/records read only evals and messages, so the wake that
  ;; followed it sent a history without it. It is an assistant message of the next request
  (let [q (fn [w] (str "[:find ?id ?st :where [?e :seon.run/task ?t] [?t :seon.task/id \"" w "\"] [?e :seon.run/id ?id] [?e :seon.run/status ?st] [?e :seon.run/agent ?a]]"))
        parked (fn [w n] (str "(seon.root/await-db sys \"the runs parked, or one ended\" #(let [rows (datahike.api/q '" (q w) " %)] (or (= " n " (count (filter (fn [[_ st]] (= :parked st)) rows))) (some (fn [[_ st]] (#{:held :done :closed :merged :dead} st)) rows))))"))
        [_ r] (in-agent (fn [{:keys [ns]}]
                          (let [w (str ns "/w")]
                            [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                             {:op :eval :agent "root"
                              :code (str "(do (my.root/run! sys [" (pr-str w) "]) " (parked w 1)
                                         " (let [ag (some (fn [[id _ _ ag]] (when (= " (pr-str (str w "#1")) " id) ag))"
                                         "           (datahike.api/q '[:find ?id ?st ?out ?ag :where [?e :seon.run/task ?t] [?t :seon.task/id " (pr-str w) "] [?e :seon.run/id ?id] [?e :seon.run/status ?st] [?e :seon.run/outcome ?out] [?e :seon.run/agent ?a] [?a :seon.agent/id ?ag]] @(:conn sys)))]"
                                         "   @(seon.root/message! sys ag \"carry on\") " (parked w 2)
                                         "   (let [db (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch " (pr-str w) ")}}))]"
                                         "     {:prose (sort (keep (comp :prose :seon.eval/record) (map #(datahike.api/pull db '[:seon.eval/record] %) (datahike.api/q '[:find [?e ...] :where [?e :seon.eval/record]] db))))})))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/prose-reply}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})]
    (is (= ["Saw my own reply."] (:prose (:value r))) (pr-str r))))

(deftest a-run-at-its-cap-is-recorded-cap-never-waiting
  ;; root, 2026-10-26: a run whose last turn waits spent its budget, so it ends :cap, which a
  ;; live view reads as the budget spent, not as a wait for a message; a wait before the cap is
  ;; :waiting, and the agent's own fail! at the cap is :failed (its verdict holds the task)
  (let [outcome (fn [script max-turns]
                  (:outcome (:value (first (in-agent #(vector (str "(seon.turn/work! {:task " (pr-str (:task %)) " :run " (pr-str (str (:ns %) "/t#cap")) " :max-turns " max-turns "})"))
                                                     {:config {:seon.config.ai/script (symbol "seon.fixtures" script)}})))))]
    (is (= :cap (outcome "wait-call" 1)) "a wait on the last turn: the budget spent")
    (is (= :waiting (outcome "wait-call" 2)) "a wait with turns left: waiting")
    (is (= :failed (outcome "fail-call" 1)) "fail! on the last turn: the agent's verdict")))

(deftest a-failed-call-keeps-what-it-sent
  ;; root, 2026-10-24: a turn whose model call fails still records what it sent (written as the
  ;; request goes) and its typed error (an error is data, AGENTS section 4); the debug page shows both
  (let [[end turn] (in-agent (fn [{:keys [task ns]}]
                              [(str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str (str ns "/t#fail")) " :max-turns 1})")
                              (str "(let [db (seon.agent/db) t (datahike.api/pull db '[:db/id :seon.turn/error] [:seon.turn/id " (pr-str (str ns "/t#fail/0")) "])"
                                   "      m (datahike.api/pull db '[:seon.model-call/sent] [:seon.model-call/id " (pr-str (str ns "/t#fail/0")) "])]"
                                   "  {:sent (mapv first (:seon.model-call/sent m)) :error (:seon.error/type (:seon.turn/error t))"
                                   "   :read (mapv :content (mapcat second (seon.context/parts-of db (:seon.model-call/sent m))))})")])
                             {:config {:seon.config.ai/provider :none}})]
    (is (= :error (:outcome (:value end))) (pr-str end))
    (is (= [:section :parts :hashes] (:sent (:value turn))) "the help page, then the page's parts whole, then their hashes")
    (is (= 2 (count (:read (:value turn)))) "read back from its records")
    (is (str/includes? (str (peek (:read (:value turn)))) "1 of 1") (str "the page's status: the turn of how many: " (peek (:read (:value turn)))))
    (is (= :seon.model/no-provider (:error (:value turn))) "and the typed error the call answered")))

(deftest a-whole-tasks-work-turns-are-no-merge-turns
  ;; the first whole-task run (2026-10-26): all ten requests ended :merge-failed at turn 3, each work
  ;; turn counted against :seon.config.merge/max-merge-turns (2) as a merge task's turns are; a
  ;; whole task's turn is a merge turn only while what both sides changed is unresolved
  (let [[end] (in-agent #(vector (str "(seon.turn/work! {:task " (pr-str (str (:ns %) "/w")) " :run " (pr-str (str (:ns %) "/w#1")) " :max-turns 4})"))
                        {:config {:seon.config.ai/script 'seon.fixtures/same-call}
                         :decls [{:seon.task/name "w" :seon.task/plan "Work." :seon.task/kind :whole :seon.task/proofs '#{demo-test/t}}]})]
    (is (= [:cap 4] ((juxt :outcome :model-calls) (:value end))) (pr-str (:value end)))))

(deftest a-proof-run-ends-at-its-budget-though-its-tests-run-bounded-evals
  ;; Jolt jolt/nested-tick-budget: a tick budget covers all the work inside it, nested
  ;; bounded runs' included. Step E's run 7 (2026-10-22): the agent's proof fork, whose
  ;; tests each run bounded evals (seon.intr/run), spun 2.5 h past its 2e11-tick budget.
  ;; Here a proof that loops over bounded evals for ever ends at its own budget, named
  (let [[_ r] (in-agent ["(do (ns spin-test (:require [clojure.test :refer [deftest is]]))
                              (deftest spin (loop [] (seon.intr/run 100000 #(reduce + (range 1000))) (recur))))"
                         "(let [r (seon.agent/run-tests-forked 100000000 #{'spin-test/spin} :turn)] [(:seon.error/type (first (filter :seon.error/type (:reports r)))) (some :var (:reports r))])"])
        [type test] (:value r)]
    (is (= :seon.test/ticks-spent type) (:text r))
    (is (= "#'spin-test/spin" test) "naming the test that was running")))

(deftest an-agent-commits-after-each-eval
  ;; task seon.render/a-view-renders-once-per-change-of-its-reads (D; design section 11, item 1):
  ;; each eval of a turn (the screen's two, then the model's) is committed as it lands, so a page
  ;; shows it then, not at the turn's end. Walked back from the branch's head, no commit adds more
  ;; than one eval record (one commit at the turn's end added the turn's evals at once: 2 -> 4)
  (let [[_ counts] (in-agent (fn [{:keys [task ns]}]
                             [(str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str (str ns "/t#each")) " :max-turns 1})")
                              (str "(let [db (seon.agent/db)"
                                   "      n (fn [db] (count (datahike.api/q '[:find ?e :where [?e :seon.eval/id]] db)))"
                                   "      back (fn [db] (when-let [p (first (datahike.api/parent-commit-ids db))] (datahike.api/commit-as-db db p)))]"
                                   "  (vec (distinct (map n (take 30 (take-while some? (iterate back db)))))))")])
                             {:config {:seon.config.ai/script 'seon.fixtures/same-call}})
        ns (:value counts)]
    (is (< 3 (count ns)) (pr-str ns))
    (is (every? #(<= % 1) (map - ns (rest ns))) (str "each eval its own commit: " (pr-str ns)))))

(deftest a-model-turn-records-its-prose-and-the-message-it-took
  ;; task seon.render/a-debug-view-of-any-agent: the owner's message is a fact root writes on
  ;; main (from "owner", to the run the agent works); the turn that takes it writes the same
  ;; fact on its branch before its commit, naming the agent (:seon.message/agent), so the conversation
  ;; and every later reading read its text from that commit (the basis names no message); the reply's prose (here the scripted model's)
  ;; is the turn's reply and its call's eval's prose, and the call names its one eval
  ;; (docs/render.md)
  (let [names (volatile! nil)
        [_ end turn on-main] (in-agent (fn [{:keys [agent task ns run] :as n}]
                                         (vreset! names n)
                                         [{:op :message :agent agent :body "use demo/g"}
                                        (str "(seon.turn/work! {:task " (pr-str task) " :run " (pr-str (str ns "/t#think")) " :max-turns 1})")
                                        (str "(let [db (seon.agent/db) t (datahike.api/pull db '[*] [:seon.turn/id " (pr-str (str ns "/t#think/0")) "])"
                                             "      m (datahike.api/pull db '[*] [:seon.model-call/id " (pr-str (str ns "/t#think/0")) "])"
                                             "      msgs (fn [db] (vec (datahike.api/q '[:find ?id ?f ?b ?k :where [?m :seon.message/id ?id] [?m :seon.message/from ?f] [?m :seon.message/body ?b] [?m :seon.message/agent ?a] [?a :seon.agent/key ?k]] db)))]"
                                             "  {:calls (mapv #(select-keys % [:code :eval]) (:seon.turn/calls t)) :reply (:seon.turn/reply t) :basis-keys (set (keys (:seon.model-call/basis m)))"
                                             "   :agent-key (:key @seon.agent/state) :on-branch (msgs db)"
                                             "   :committed (msgs (datahike.api/commit-as-db db (get-in m [:seon.model-call/basis :commit])))"
                                             "   :prose (mapv #(:prose (datahike.api/q '[:find ?r . :in $ ?i :where [?e :seon.eval/id ?i] [?e :seon.eval/record ?r]] db %)) (keep :eval (:seon.turn/calls t)))})")
                                        {:op :eval :agent "root"
                                         :code (str "(datahike.api/q '[:find ?id ?f ?to ?b :where [?m :seon.message/id ?id] [?m :seon.message/from ?f]"
                                                    " [?m :seon.message/to ?r] [?r :seon.run/id ?to] [(= ?to " (pr-str run) ")] [?m :seon.message/body ?b]] @(:conn sys))")}])
                                       {:config {:seon.config.ai/script 'seon.fixtures/thinking-call}})
        end (:value end)
        {:keys [calls on-branch committed prose reply agent-key basis-keys]} (:value turn)
        [[mid from to body] :as facts] (vec (:value on-main))]
    (is (= 1 (:model-calls end)) (pr-str end))
    (is (= [["owner" (:run @names) "use demo/g"]] (mapv #(subvec % 1) facts)) "one fact on main: from the owner, to the run the agent works")
    (is (= [[mid "owner" "use demo/g" agent-key]] on-branch) "the turn wrote the fact root wrote, by its id, naming the agent")
    (is (= on-branch committed) "and it is in the commit the turn read")
    (is (not-any? #{:observed :taken} basis-keys) "the basis names no message: the fact is the record")
    ;; the prose comes in the eval call (docs/prompt.md); the scripted reply's text is the same prose
    (is (= "check the sum before the proof" reply) "the reply's text: the prose")
    (is (string? (:eval (first calls))) (str "the call names its one eval: " (pr-str calls)))
    (is (= ["check the sum before the proof"] prose) "whose record keeps the prose beside it")))

(deftest a-compile-errors-line-and-suggestions-arrive-as-data
  ;; the exception Jolt's analyzer raises for `(prinltn 1)`, as the one error map: its line,
  ;; column and symbol are keys, its suggestions the fix, and nothing is a printed copy
  (let [e (try (eval (read-string "(prinltn 1)")) (catch Throwable e e))
        em (agent/error-map e)]
    (is (= :seon.agent/eval-error (:seon.error/type em)))
    (is (= 1 (:jolt.error/line em)))
    (is (= 1 (:jolt.error/column em)))
    (is (= "prinltn" (:jolt.error/symbol em)))
    (is (some #{"println"} (:jolt.error/suggestions em)))
    (is (= "did you mean print, printf, println" (:seon.error/fix em)))
    (is (= "Unable to resolve symbol: prinltn in this context" (:seon.error/message em)) "the suggestions are not in the message too")
    (is (nil? (:data em)) "no printed copy of the ex-data")))

(deftest memory-is-read-when-asked
  ;; task seon.agent/memory-is-read-when-asked: mem-mb is a reading made at the call, never a
  ;; value kept for a time, so each call reads the process's footprint again (jolt.host/
  ;; process-footprint-bytes, one call). The megabytes it answers move with the machine (a collection
  ;; between two calls lowers them), so the test counts the calls, exactly (seon.profile's counted
  ;; calls)
  (let [reads (volatile! nil)
        [a b] (profile/measure! #(vector (agent/mem-mb) (agent/mem-mb))
                                (fn [_] (vreset! reads (into {} (map (juxt :var :calls)) (profile/touched)))))]
    (is (= 2 (get @reads 'seon.lib/real-mb)) "two calls, two readings: none kept between them")
    (is (and (pos? a) (pos? b)) "a reading in MB")))

(deftest related-is-an-eval-of-the-plan-and-of-any-text
  ;; my.task/related (the screen form) and my.agent/related (the agent's own words), in a test-mode
  ;; agent over seon.context-test's five sections; a process test because my.agent reaches a fork
  (let [[db te dir] (#'seon.context-test/sections-near "Make the page say one thing (docs/vector.md#what-is-embedded).")
        ;; the agent working the task, as a session holds it: its entity and its running run
        db (d/db-with db [{:seon.agent/key "demo/a1"} {:seon.run/id "demo/r#1" :seon.run/task te :seon.run/status :running :seon.run/agent [:seon.agent/key "demo/a1"]}
                          ;; the toy's chances sit far below the page's entry cut, so every item scored enters
                          [:db/add te :seon.config.render/relevance-doc 0.0]])
        old @agent/state]
    (reset! agent/state {:n 0 :mode :test :db db :task "demo/r" :key "demo/a1" :branch :b :turn {:run "demo/r#1"}})
    (try
      ;; the sections the plan does not cite, each a line "<address> <chance>%: <lead>", best first by relevance
      ;; (seon.context/relevance: the toy's chances are all near 0, so its order is relevance's, not the cosine's)
      (is (= #{"docs/vector.md#what-the-work-is-likely-to-need-as-an-eval" "docs/vector.md#the-model" "docs/vector.md#the-pyramid" "docs/vector.md#fused-with-the-call-graph"}
             (set (map #(first (str/split % #" ")) (:sections (my.task/related)))))
          "the screen form: the sections nearest the plan, the cited one (what-is-embedded) left out")
      (binding [agent/*fx-stub* (fn [_ _] {:value {:model "m" :vectors [(dir 1.0 0.5)] :tokens [3]}})]
        (let [v (my.agent/related "the last one" 2)]
          (is (every? #(re-find #"^docs/vector\.md#[a-z-]+: Section \d says one thing\." %) (:sections v))
              "the agent's own words refine it: sections of the toy wiki, a line each, best first by relevance")
          (is (= 2 (count (:sections v))) "k of them")))
      (finally (reset! agent/state old)))))

(deftest a-parked-agent-draws-its-parts-and-a-gone-one-answers-at-once
  ;; the agent draws its own page (one derivation, slice 2): the render proc's {:op :render} ask is
  ;; answered by an agent parked between requests with its parts' HTML, a key its branch renders
  ;; drawn by that render, recording nothing; a request to an agent that is gone is answered at
  ;; once, so its page is drawn by root from its branch and never waits
  (let [n "(count (datahike.api/datoms (seon.agent/db) :aevt :seon.eval/id))"
        [_ _ swapped before drawn after gone]
        (in-agent #(vector "(my.schema/register! :lab.ctx/steps '[:vector {:seon.render/html lab.ui/steps} :string])"
                   "(ns lab.ui) (defn steps \"The steps, numbered.\" {:malli/schema [:=> [:cat :map [:vector :string]] :any]} [_ v] [:ol (for [s v] [:li s])])"
                   "(swap! seon.ctx/ctx assoc :lab.ctx/steps [\"read the plan\" \"write the test\"])"
                   n
                   {:op :eval :agent "root"
                    :code (str "(let [id :" (:agent %) "]"
                               " @(seon.root/call! sys {:op :render :id id}))")}
                   n
                   {:op :eval :agent "root" :code "@(seon.root/call! sys {:op :render :id :gone})"}))
        ;; the keys it added, one component (seon.render.ui/ctx-keys) holding each key's own
        steps (some #(when (= "ctx-keys" (:id %)) %) (:units (:value drawn)))]
    (is (str/includes? (str (:html steps)) "<ol><li>read the plan</li><li>write the test</li></ol>")
        (str "the swap! answered " (pr-str swapped) "; the units: " (pr-str (map :id (:units (:value drawn))))))
    (is (= (inc (:value before)) (:value after)) "the drawing recorded no eval: only the count before it is new")
    (is (= :seon.root/no-agent (:seon.error/type (:value gone))) (pr-str gone))))

(deftest a-line-that-does-not-read-ends-its-test-and-the-run-goes-on
  ;; task seon.agent/a-test-childs-line-always-reads (root, 2026-10-27): a report holding a value
  ;; that prints as no EDN (an exception's #error {\n splits the line, a var's #'v does not read)
  ;; killed its agent with exit 70. The line is that test's :error, once, and the next test's lines
  ;; are taken as before
  (let [enc seon.edn/encode
        bad (enc {:seon.test/ended "#'app.t/b" :reports [{:type :fail :actual [(ex-info "boom" {:a 1}) #'clojure.core/inc]}]})
        lines (concat [(enc {:seon.test/started "#'app.t/a"}) (enc {:seon.test/ended "#'app.t/a" :reports [{:type :pass :var "#'app.t/a"}]})
                       (enc {:seon.test/started "#'app.t/b"})]
                      (str/split-lines bad)
                      [(enc {:seon.test/started "#'app.t/c"}) (enc {:seon.test/ended "#'app.t/c" :reports [{:type :pass :var "#'app.t/c"}]})])
        acc (reduce agent/take-line {:streamed [] :ended #{} :fns {}} lines)
        errors (filter #(= :error (:type %)) (:streamed acc))]
    (is (< 1 (count (str/split-lines bad))) "the exception's raw newline splits the line")
    (is (= 1 (count errors)) "the unreadable line is one :error")
    (is (= "#'app.t/b" (:var (first errors))) "on the test that was running")
    (is (= :seon.test/unreadable-line (:seon.error/type (first errors))))
    (is (= #{"#'app.t/a" "#'app.t/c"} (:ended acc)) "the tests around it are taken as before")
    (is (= :error (get-in (agent/test-run-answer (enc {:a #'clojure.core/inc}) {:exit 0} false) [:reports 0 :type]))
        "a last line that does not read is the run's :error, not the agent's death")))

(deftest a-tests-ended-line-keeps-the-live-heap-it-left
  ;; docs/cost.md#what-a-test-run-measures (root, 2026-10-28): 23 of 191 tests of a forked run ran
  ;; out of memory at the agent's heap ceiling with no stack; the heap each test leaves, after a
  ;; collection, is on its ended line and kept per test, so the one that holds memory is named
  (let [enc seon.edn/encode
        acc (reduce agent/take-line {:streamed [] :ended #{} :fns {} :heap {}}
                    [(enc {:seon.test/started "#'app.t/a"}) (enc {:seon.test/ended "#'app.t/a" :reports [] :heap 1200})
                     (enc {:seon.test/started "#'app.t/b"}) (enc {:seon.test/ended "#'app.t/b" :reports [] :heap 900000})])]
    (is (= {'app.t/a 1200 'app.t/b 900000} (:heap acc)))))

;; See docs/merging.md#main-moved-is-said-at-every-eval
(deftest main-moved-is-said-at-every-eval
  ;; the owner (root, 2026-10-28): "we are supposed to be telling the agent always at every eval reply
  ;; what is different with main ... there are never any surprises": what main changed since the base,
  ;; which of it the branch changed or calls, and what a merge of main would conflict on
  (let [base (seon.program-test/record (ts/test-db) 'user "(ns app.md)" 'app.md "(defn f [x] x)" 'app.md "(defn g [x] (f x))" 'app.md "(defn h [x] x)")
        branch (seon.program-test/record base 'app.md "(defn g [x] (inc (f x)))")
        main (seon.program-test/record base 'app.md "(defn f [x] (* 2 x))" 'app.md "(defn h [x] (dec x))")
        both (seon.program-test/record main 'app.md "(defn g [x] (dec (f x)))")]
    (is (nil? (agent/main-line base branch base)) "main as the base: nothing to say")
    (let [l (agent/main-line base branch main)]
      (is (str/includes? l "2 names changed") l)
      (is (str/includes? l "ones your changes call: [app.md/f]") l)
      (is (not (str/includes? l "conflict")) l)
      (is (str/includes? l "(my.branch/merge-main!)") "and the form that merges it, when the agent chooses"))
    (let [l (agent/main-line base branch both)]
      (is (str/includes? l "ones you changed too: [app.md/g]") l)
      (is (str/includes? l "conflict on: [app.md/g]") l))))

;; docs/merging.md#main-moved-is-said-at-every-eval: a turn merges main in whenever main moved past the branch's
;; merge base (the owner, 2026-10-30: "updates as often as we can"), and never when it did not
(deftest a-turn-merges-main-only-when-main-moved
  ;; committed values, as an agent's base and heard main are (a d/db-with value names no commit)
  (ts/test-branch!
    (fn [_ conn]
      (let [base @conn
            _ (d/transact conn {:tx-data [{:note/text "main moved"}]})
            main @conn]
        (is (false? (agent/main-moved? base base)) "main at the merge base: nothing to merge")
        (is (true? (agent/main-moved? base main)) "main moved: the turn merges it")
        (is (false? (agent/main-moved? nil main)) "no merge base yet: nothing to compare")))))

;; See docs/merging.md#main-moved-is-said-at-every-eval
(deftest main-line-costs-what-changed
  ;; the coordinator, 2026-10-29: an eval should cost ms; main-line (13.9 M ticks, 38.8 MB, 304 contracted
  ;; calls on every eval, 13.0 M with main the base) is O(1) while main is the base, pays main's diff once
  ;; per (base, main) pair, and a later eval at the same main pays only the branch's diff and one query.
  ;; Work done (ticks, bytes, calls), never time
  (let [base (seon.program-test/record (ts/test-db) 'user "(ns app.md)" 'app.md "(defn f [x] x)" 'app.md "(defn g [x] (f x))" 'app.md "(defn h [x] x)")
        branch (seon.program-test/record base 'app.md "(defn g [x] (inc (f x)))")
        main (seon.program-test/record base 'app.md "(defn f [x] (* 2 x))" 'app.md "(defn h [x] (dec x))")
        fresh! #(swap! agent/state dissoc :main-changes :branch-changes)
        unchanged (cost/measure (fn [] (fresh!) (agent/main-line base branch base)) [])
        first-eval (cost/measure (fn [] (fresh!) (agent/main-line base branch main)) [])
        _ (agent/main-line base branch main)
        second-eval (cost/measure (fn [] (agent/main-line base branch main)) [])]
    (is (< (:ticks unchanged) (* 0.01 (:ticks first-eval))) "main as the base: no diff, no query")
    (is (< (:ticks second-eval) (* 0.5 (:ticks first-eval))) "the second eval at the same main does not diff main again")
    (is (< (:bytes second-eval) (* 0.5 (:bytes first-eval))))))

(deftest a-shell-child-costs-what-it-reads
  ;; the reproduction, 2026-10-29: in a forked agent, clojure.java.shell/sh of a ~3 s command (its two output pipes
  ;; read by two futures) spun after the child exited, ~470,000 passes a second and ~1.5 GB of garbage in 3.5 s, then ran
  ;; the agent out of memory or hung it (in 3 of 6 runs). The readers' wait to enter the io poller's monitor made
  ;; the main carrier's wake, itself an entry of that monitor, again and again from inside its own wait; a wait to
  ;; enter a monitor no longer tells the loop it fell asleep (Jolt's locks.ss jolt-asleep-silent). The work done is bytes
  ;; allocated, never time. docs/forks.md (Jolt: a thread waiting to enter a monitor)
  (let [bytes "(jolt.scheme/eval-string \"(lambda () (sstats-bytes (statistics)))\")"
        code (str "(let [bytes " bytes " b0 (bytes) rs (vec (repeatedly 4 #(clojure.java.shell/sh \"sleep\" \"1\")))] "
                  "{:exits (mapv :exit rs) :mb (quot (- (bytes) b0) 1048576)})")
        rs (in-agent [code "(+ 1 2)"])]
    (is (every? #(nil? (:ex %)) rs) (pr-str (mapv #(select-keys % [:ex]) rs)))
    (is (= [0 0 0 0] (:exits (:value (first rs)))) (pr-str (first rs)))
    ;; four children of 1 s: ~4 MB with the fix, ~1,500 MB (the agent's heap ceiling) without it
    (is (< (:mb (:value (first rs))) 64) (pr-str (first rs)))
    (is (= 3 (:value (second rs))) "the agent lives")))

(deftest a-refused-write-in-a-proof-runs-finish-answers-the-asker-with-the-error
  ;; AGENTS.md §4 (nothing hangs), docs/failure-points.md (a refused write): the run's results are
  ;; recorded on the branch when the run finishes, which the loop runs, not the asker's eval; a
  ;; write the branch refuses there answers the asker (my.agent/prove) with the error, never leaves it waiting.
  ;; The agent's own REPL makes its writer refuse (its process, its definition of transact!)
  (let [[_ _ r] (in-agent ["(ns fin-test (:require [clojure.test :refer [deftest is]])) (deftest r (is (= 1 1)))"
                           "(alter-var-root #'seon.agent/transact! (fn [f] (fn [tx] (if (some :seon.test/status (filter map? tx)) (throw (ex-info \"transact refused: no\" {:writer-error \"no\"})) (f tx)))))"
                           "(let [p (my.agent/prove)] (select-keys p [:pass :unrecorded]))"]
                          {:dirs ["src" "test"] :proofs #{'fin-test/r}})]
    (is (nil? (:ex r)) (pr-str r))
    (is (= {:pass 1} (select-keys (:value r) [:pass])) (pr-str r))
    (is (some #(str/includes? % "transact refused: no") (:unrecorded (:value r))) "named, not thrown")))
