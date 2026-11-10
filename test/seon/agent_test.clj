(ns seon.agent-test
  "An agent's process, run as the built binary (target/seon, bin/build seon): the root forks
  it, so the test boots a root of its own, as seon.root-test does, and asks the agent over
  the server what its REPL answers.

  An agent's test run, read from its child's pipe:
  no clock ends it; the child's exit does, and a child that died names the test it was running.
  Run: bin/test seon.agent-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.agent :as agent] [seon.reconcile :as r] [seon.proofs :as proofs] [seon.contract :as contract]
            [datahike.api :as d] [seon.db :as pdb] [seon.test-store :as ts] [seon.program-test] [seon.lib :as lib] [seon.program :as program] [seon.sock :as sock]
            [seon.fx :as fx] [seon.cost :as cost] [seon.config :as cfg] [seon.merge :as pm] [seon.profile :as profile] [seon.task :as task] [seon.writer :as writer] [seon.repl :as repl] [seon.agent-proc] [seon.ctx] [seon.render] [seon.turn] [my.test :as mt] [test-runner :as runner] [seon.ctx.wire-test] [my.task] [my.agent] [seon.template :as tpl] [seon.stall-proofs] [seon.doc] [clojure.core.async]))

(def ^:private await-file
  "seon.await, which a root (importing src only) loads by this path."
  (.getCanonicalPath (java.io.File. (seon.config/checkout) "test/seon/await.clj")))

(def ^:private before-done
  "Main's t just before a :done, kept in root's REPL as t-before-done: :done answers once the judge
  has, and the reconciler may be quiet by then, so a test waiting for quiescence after :done waits
  for a report written after this t, never after :done's answer (that wait could never end)."
  {:op :eval :agent "root" :code "(def t-before-done (:max-tx @(:conn sys)))"})

(def value-of "seon.test-store/value-of, as this namespace's callers name it." ts/value-of)

(declare with-agent)

(defn in-agent
  "One agent on a task of its own in this test process's shared root on the checkout's src (its
  source root; seon.test-store/shared-root!, booted once per shard and root arguments), each of
  forms evaluated in that agent (a string) or sent to the server as it is (a map): every reply's
  response with its value read back (:value, value-of), or the answer that holds no response, in
  order. forms is a vector, or a function of the test's names {:ns :task :agent} (its task's
  namespace, a fresh one per test, its task \"<ns>/t\", and the agent the attach
  answered) answering one: a test names what it made by these, never by a counter's value, and
  reads only its own task's facts (the shard's main holds every earlier test's). Its task and
  the task's branches are its own (the namespace is); its agent is ended and its branches
  deleted when it ends, however it ends. A shard runs its tests one at a time, so root-REPL vars
  a test defines (ready-ev, t-before-done) are its own while it runs.
  opts: :dirs, the source dirs root imports (default: the config's, as bin/test imports them, so the root
  holds the program bin/test's seeded store holds and its test namespaces load through Jolt's AOT cache:
  docs/testing.md#a-root-costs-what-it-boots); :proofs, the task's proofs;
  :sets, more --set config overrides for the root (e.g. its scripted model): a root per distinct
  :dirs and :sets (and :group, another root of the same arguments); :config, the tasks' own settings (their :seon.task/config: a test's scripted
  model, :seon.config.ai/script, rides the task, so the root's arguments stay alike and the shard's
  tests share one root: docs/config.md#the-resolver); :decls, more task declarations in the task's file beside t (a function of
  {:ns :task}, or a vector); :client, the client its attach names (seon.server: then only that client's requests, or ones
  naming the task, act on the agent)."
  ([forms] (in-agent forms {}))
  ([forms opts]
   (mapv #(if (contains? % :response) (let [r (:response %)] (assoc r :value (value-of r))) %)
         (with-agent opts (fn [{:keys [names ask]}]
                            ;; a string: a form on the agent's REPL, by root's submit!, no reply and no turn of anyone's
                            (mapv #(if (map? %)
                                     (ask %)
                                     (let [a (ask {:op :eval :agent "root" :code (str "@(c/submit! sys :" (:agent names) " " (pr-str %) " {:origin :outside})")})]
                                       (if-let [r (get-in a [:response :value])] {:response r} a)))
                                  (if (fn? forms) (forms names) forms)))))))

(deftest a-branchs-config-key-moves-the-tests-reading-it-and-no-other
  ;; docs/merging.md#verdicts-are-a-cache: a test takes each key's value, so what a branch states of one key moves the tests reading it, and leaves the rest and the place keys alone
  (let [form (fn [nm k]
               (fn [db] (program/record-tx db {:id (str "app.k-test/" nm) :src (str "(deftest " nm " (seon.config/value " k "))") :ns 'app.k-test :ns-form? false
                                               :defs [{:var (symbol "app.k-test" nm) :meta #{:test}}] :keywords #{k}
                                               :declares #{} :methods [] :impls [] :refs '#{seon.config/value} :requires #{} :banned #{} :inline-defs #{}})))
        db (mt/with (mt/world)
             (form "reads-a" :seon.config.root/view-window)
             (form "reads-b" :seon.config.root/flow-stop-ms))
        g (program/reach-graph db nil)
        in {:env "e" :tree "t" :config (fx/config-input cfg/config (cfg/checkout))}
        digests (fn [held] (update-vals (program/graph-index g (#'agent/branch-inputs in db db held) nil) :digest))
        before (digests cfg/config)
        after (digests (update cfg/config :seon.config.root/view-window inc))]
    (is (not= (before 'app.k-test/reads-a) (after 'app.k-test/reads-a)) "the key's reader moves")
    (is (= (before 'app.k-test/reads-b) (after 'app.k-test/reads-b)) "a reader of another key doesn't")
    (is (= before (digests (assoc cfg/config :seon.config.root/main "elsewhere"))) "and a place moves nothing")))

(defn with-agent
  "in-agent's setup and teardown around a body of the test's own: f is called with {:names {:ns :task :agent}
  :ask (a request to the root's server, answered as data) :eval-root (root-REPL code, answered as the server does; root-value reads its value)
  :root}, once the agent is ready, and its answer is with-agent's. A test whose steps wait on its own process
  (a fifo, a pid) uses this instead of a root of its own; opts are in-agent's (and :group, a name for a root of its own among
  the tests that name it: :pristine, for tests that need a main nothing merged into, :merge, for tests whose agent merges)."
  [{:keys [dirs proofs sets config decls client group] :or {proofs '#{demo-test/t}}} f]
  (let [checkout (seon.config/checkout)
        tns (seon.lib/unique-id)
        names {:ns tns :task (str tns "/t")}
        proofs (if (set? proofs) proofs (proofs names))
        dirs (or dirs (when group (seon.config/value :seon.config.source/dirs)))
        tasks (str checkout "/target/runs/" tns "-tasks")
        _ (.mkdirs (java.io.File. tasks))
        decls (if (fn? decls) (decls names) decls)
        with-config #(cond-> % config (assoc :seon.task/config config))
        _ (spit (str tasks "/" tns ".edn") (pr-str (mapv with-config (into [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs proofs}] decls))))
        args (into (cond-> ["--set" (str ":seon.config.source/root=" (pr-str checkout))]
                     dirs (into ["--set" (str ":seon.config.source/dirs=" (pr-str dirs))]))
                   (mapcat (fn [x] ["--set" x])) sets)
        root (ts/shared-root! args)
        ask (fn [m] (ts/ask root m))
        eval-root (fn [code] (ask {:op :eval :agent "root" :code code}))
        made (volatile! nil)
        ;; what the test made goes with it, however it ended: its agent (killed, its end awaited), its tasks
        ;; (cancelled) and their branches
        end! (fn []
               (let [agent @made]
                 (sh/sh "rm" "-rf" tasks)
                 (cond (:port root) (eval-root (str "(let [ev (c/events sys) a " (when agent (str ":" agent)) "]"
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
                names (assoc names :agent agent)]
            (vreset! made agent)
            (cond
               (nil? (:port root)) {::no-root {:error :root-did-not-listen :log (:log root)}}
               ;; an attach that made no agent (refused: its fork server lost) ends the test with
               ;; that answer, never a wait for a hello that cannot come
               (nil? agent) {::attach-made-no-agent attach}
               :else (do (eval-root (str "(loop [] (let [e (clojure.core.async/<!! ready-ev)] (if (and (= :ready (:type e)) (= :" agent " (:agent e))) :ready (recur))))"))
                         (f {:names names :ask ask :eval-root eval-root :root root}))))
          (finally (vreset! stopped (end!))))]
    (when-let [a (and (map? out) (::attach-made-no-agent out))]
      (throw (ex-info (str "in-agent: the attach made no agent: " (pr-str a))
                      {:attach a})))
    (when-let [e (and (map? out) (::no-root out))] (throw (ex-info (str "with-agent: the root did not listen: " (pr-str e)) e)))
    out))

(defn- root-value
  "The value of a root-REPL answer r (an error answer stays itself)."
  [r] (if (:error r) r (value-of (:response r))))

(defn- began-code
  "Root-REPL code: agent a begins evaluating code (submitted, its future root's var blocked); answers :began at the agent's
  :started event, tapped before the submit (the tap let go after)."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [a code]
  (str "(let [ev (c/events sys)] (def blocked (c/submit! sys :" a " " (pr-str code) ")) "
       "(loop [] (let [e (clojure.core.async/<!! ev)] (if (and (= :started (:type e)) (= :" a " (:agent e))) (do (clojure.core.async/untap (:events-mult sys) ev) :began) (recur)))))"))

(defn- gone-code
  "Root-REPL code: answers :gone once agent a's end has been heard (the in-agent root's tap of its events, ready-ev)."
  {:malli/schema [:=> [:cat :string] :string]}
  [a]
  (str "(loop [] (let [e (clojure.core.async/<!! ready-ev)] (if (and (#{:ended :dead :killed} (:type e)) (= :" a " (:agent e))) :gone (recur))))"))

(def ^:private merge-cache
  "The verdict cache directory of the root the merging tests share (a delay: one per test process)."
  (delay (str (seon.config/checkout) "/target/tmp/verdicts-merge-" (random-uuid))))

(defn merging
  "in-agent's options for a test whose agent merges into main (which moves the program every later test runs), or
  grows main's history (an-agents-start-costs-the-same-however-long-its-root-has-run): a root for them, all alike,
  each on test names of its own, with a node cache small enough to be full at that history (an agent past it
  rereads what the fork server holds; no test root's cache is tuned otherwise: this key is a root's, read where the
  store opens, so it cannot ride a task) and a verdict cache the tests may read and write (merge-cache)."
  []
  {:sets [":seon.config.store/node-cache-bytes=8388608" (str ":seon.config.proofs/results-dir=" (pr-str @merge-cache))] :group :merge})

(defn- spawn-kept-code
  "Root-REPL code: spawn an agent onto the kept branch kept (made by pre, code run with m, root's main connection, and
  b, the branch's name) and answer (on-ready ag) at its :ready, or its end's type; the agent ended after."
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [kept pre on-ready]
  (str "(let [ev (seon.root/events sys) m (seon.db/conn-main (:conn sys)) b (seon.db/on-main m " kept ")]"
       " " pre
       " (let [ag (seon.root/spawn! sys {:branch " kept "})"
       "       r (loop [] (let [e (clojure.core.async/<!! ev)]"
       "                    (cond (not= ag (:agent e)) (recur)"
       "                          (= :ready (:type e)) " on-ready
       "                          (#{:dead :ended :killed} (:type e)) (:type e)"
       "                          :else (recur))))]"
       "   (clojure.core.async/untap (:events-mult sys) ev)"
       "   (when-not (:ended-ms (get (:agents (c/state sys)) ag)) (c/kill! sys ag))"
       "   r))"))

(defn- root-ended
  "A root with an idle agent and one more agent for each form of codes, each evaluating its form (sent; its :started event awaited). Then
  SIGKILL of root; with fork-server-first?, what a pid cleanup does before it: SIGKILL of the
  fork server's standby and of the fork server, so its group kill (seon.fork-server/main) never
  runs and each agent has only its own socket to root to end on. Answers {:ids [id ...] (the idle agent's first) :agents {id pid}
  :left #{pid} :out text}: the agents' pids as root reported them, the processes of the fork
  server's group still alive once everything root started has ended (the wait is
  seon.test-store/kill-root!'s drain, an event; a process that never ends holds the test), and
  root's log, for a red run's message."
  [codes fork-server-first?]
  (let [checkout (seon.config/checkout)
        main (str "root-ended-" (random-uuid))
        store (ts/process-store checkout)
        runs (str checkout "/target/runs/" main "-runs")
        root (ts/start-root! {:store store :main main :runs runs})
        ask (fn [m] (ts/ask root m))
        ;; the printed value of a root eval; a reply that is an error stays the error
        eval-root (fn [code] (let [r (ask {:op :eval :agent "root" :code code})]
                               (if (:error r) r (value-of (:response r)))))
        ;; spawn! answers at each agent's hello: every one is ready
        ;; their ids, as spawn! answers them (an id is a digest: nothing here names an agent but by them)
        ids (eval-root (str "(vec (repeatedly " (inc (count codes)) " #(c/spawn! sys {})))"))
        ;; the second on begin their forms (each agent's :started event, tapped before its form is sent)
        _ (doseq [[i code] (map-indexed vector codes) :let [a (nth ids (inc i))]]
            (eval-root (str "(let [ev (c/events sys)] (c/submit! sys " a " " (pr-str code) ") "
                            "(loop [] (let [e (clojure.core.async/<!! ev)] (if (and (= :started (:type e)) (= " a " (:agent e))) :began (recur)))))")))
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
          log (ts/output (str runs "/root.log"))]
      (doseq [p left] (sh/sh "kill" "-9" (str p)))
      (ts/drop-main! store main)
      (sh/sh "rm" "-rf" runs)
      {:ids ids :agents agents :out log :left (if drained left (conj left :not-drained))})))

(deftest a-tests-request-is-answered-while-an-eval-blocks
  ;; task seon.agent/the-agent-is-a-flow-graph: root's :tests request goes to the agent's :tests proc,
  ;; never behind the eval the :repl proc runs (md-scan #21: a :tests waited behind a wait that held the
  ;; loop). An eval blocks on a channel take that nothing will answer; the test service proves the
  ;; agent's branch meanwhile and answers; then root's interrupt stops the eval and the agent answers on
  (let [[proved stopped next-answer]
        (with-agent {}
          (fn [{:keys [names eval-root ask]}]
            (let [a (:agent names) ev (comp root-value eval-root)]
              (ev (began-code a "(clojure.core.async/<!! (clojure.core.async/chan))"))
              ;; the run's answer, or :none if the agent did not answer it while its eval blocked
              [(ev (str "(deref (future @(c/prove! sys :" a " #{} :turn)) 60000 :none)"))
               (do (ev (str "(c/interrupt! sys :" a ")"))
                   (ev "(clojure.string/starts-with? (:seon.error/message (:ex @blocked)) \"interrupted by root\")"))
               (ev (str "(:value @(c/submit! sys :" a " \"(+ 2 2)\" {:origin :outside}))"))])))]
    (is (and (map? proved) (contains? proved :pass)) (str "the run answered while the eval blocked: " (pr-str (if (map? proved) (select-keys proved [:pass :fail :error :ran]) proved))))
    (is (true? stopped) (str "the blocked eval answers interrupted by root: " stopped))
    (is (= 4 next-answer) (pr-str next-answer))))

(deftest an-agent-ends-when-its-root-is-gone
  ;; with its fork server gone first (a pid cleanup), an agent ends on EOF of its own socket to root,
  ;; whatever it is doing: an idle agent reads it; one evaluating a form with no tick budget is
  ;; interrupted by it (SIGIO); one blocked in a channel take, whose condition-wait reaches no safe
  ;; point, is woken by it too. One root for the three (2026-11-08: two tests booted one root each)
  (let [{:keys [ids agents left out]} (root-ended ["(loop [] (recur))" "(clojure.core.async/<!! (clojure.core.async/chan))"] true)
        [idle busy blocked] ids]
    (is (= (set ids) (set (keys agents))) out)
    (is (not (contains? left (agents idle))) "the idle agent ended")
    (is (not (contains? left (agents busy))) "the busy agent ended")
    (is (not (contains? left (agents blocked))) "the blocked agent ended")
    (is (empty? left) (str "agents still alive: " left "\n" out))))

(deftest a-proof-run-ends-with-its-agent
  ;; the agent's proof run (a fork of it) holds
  ;; the write end of a fifo and sleeps, a test that never writes to the agent's pipe; the
  ;; agent is killed (its task cancelled); the proof run ends with it, on the kernel's word that
  ;; the reader of its pipe is gone (SIGIO). The test reads the fifo: its first byte says the
  ;; proof run is in its test, its EOF that nothing holds the fifo any more, the proof run gone.
  ;; No clock decides: a run that outlives its agent wakes from its sleep (a minute) and writes a
  ;; second byte, which fails the test.
  (let [fifo (str (seon.config/checkout) "/target/runs/proof-ended-" (random-uuid) ".fifo")
        _ (sh/sh "mkfifo" fifo)
        {:keys [pid began ended killed]}
        (with-agent {}
          (fn [{:keys [names eval-root]}]
            (let [a (:agent names) ev (comp root-value eval-root)
                  parked (str "(do (ns parked-proof (:require [clojure.test :refer [deftest is]]))"
                              " (deftest p (let [w (java.io.FileOutputStream. " (pr-str fifo) ")] (.write w 49) (.flush w) (Thread/sleep 60000) (.write w 50) (.flush w)) (is true))"
                              " (seon.agent/run-tests-forked 1000000000 '#{parked-proof/p} :turn))")
                  ;; the agent begins the form (its :started event); root knows its pid by then
                  _ (ev (began-code a parked))
                  pid (ev (str "(get-in (c/state sys) [:agents :" a " :pid])"))
                  in (java.io.FileInputStream. ^String fifo)
                  began (.read in)
                  killed (sh/sh "kill" "-9" (str pid))
                  ended (.read in)]
              (.close in)
              (ev (gone-code a))
              {:pid pid :began began :ended ended :killed killed})))]
    (sh/sh "rm" "-f" fifo)
    (is (pos-int? pid) (str "the agent's pid: " (pr-str pid)))
    (is (= 49 began) "the proof run was in its test")
    (is (= -1 ended) (str "the proof run outlived its agent (byte " ended " after its sleep); agent " (pr-str pid) " kill " (pr-str killed)))))

(deftest a-handful-of-tests-runs-in-one-child
  ;; task seon.agent/a-test-run-streams-its-reports: a run forks no more children than its work needs
  ;; (:seon.config.proofs/child-min-ms); five tests never measured count :seon.config.proofs/unmeasured-ms each, under it
  (let [form (fn [n] (str "(ns app.few" n "-test (:require [clojure.test :refer [deftest is]])) (deftest a (is (= 1 1)))"))
        run (str "(let [pids (atom []) r (seon.agent/run-tests-forked 100000000 #{} :main (fn [p] (swap! pids conj p)) '#{app.few0-test app.few1-test app.few2-test app.few3-test app.few4-test})]"
                 " (pr-str {:children (count @pids) :pass (:pass r)}))")
        [_ _ _ _ _ r] (in-agent (conj (mapv form (range 5)) run))]
    (is (= {:children 1 :pass 5} (some-> (:value r) clojure.edn/read-string)))))

;; See docs/reconcile.md#a-task-holds-how-its-work-ended
(deftest a-wake-that-ran-no-turn-has-no-end
  ;; task seon.reconcile/a-wake-that-ran-no-turn-has-no-end: a turn moves only inside a turn, so a wake whose work! threw before
  ;; its first turn read the previous wake's end as its own: an old :waiting parked its task again and counted the message
  ;; that woke it as taken, and the throw's error lost to the old end's
  (let [end? "(seon.reconcile/pull-end (seon.agent/db) (:key @seon.agent/state))"
        [before threw after]
        (in-agent [(str "(do (seon.agent/transact! [{:seon.agent/key (:key @seon.agent/state) :seon.agent/outcome :waiting :seon.agent/reason \"the wake before\""
                        " :seon.agent/ended-turn (:seon.agent/turn (d/entity (seon.agent/db) [:seon.agent/key (:key @seon.agent/state)]) 0)}]) " end? ")")
                   "(seon.turn/work! {:task \"no/such-task\" :max-turns 9})"
                   end?])]
    (is (= :waiting (:outcome (:value before))) (str "the wake before ended waiting: " (pr-str before)))
    (is (:ex threw) (str "this wake threw before its first turn: " (pr-str threw)))
    (is (nil? (:value after)) (str "and it wrote no end, so root reads none: " (pr-str after)))))

;; See docs/merging.md#a-test-child-is-a-fork-of-a-live-agent
(deftest a-stall-tasks-proofs-judge-only-a-task-that-names-them
  ;; seon.stall-proofs read their task's cause, so on any other task they were stray reds (the control of 2026-11-05)
  (let [p 'seon.stall-proofs/the-stall-ended
        plan (fn [proofs] (str "(let [p (seon.agent/test-plan (seon.agent/db) nil " proofs ")] (contains? (set (:selected p)) '" p "))"))
        [named not-named] (in-agent [(plan (str "'#{" p "}")) (plan "#{}")] {})]
    (is (true? (:value named)) (str "a stall task's proof is selected: " (pr-str named)))
    (is (false? (:value not-named)) (str "and no other task's: " (pr-str not-named)))))

;; See docs/merging.md#a-test-child-is-a-fork-of-a-live-agent
(deftest an-eval-that-empties-the-agents-state-leaves-it-working
  ;; a test run at an agent's REPL ended by resetting the state it had taken for its own (seon.ctx-test's end-agent!):
  ;; every later call was seon.agent/db's nil, and the agent died at its next prove (the control of 2026-11-05)
  (let [[gone after page] (in-agent ["(do (reset! seon.agent/state nil) :emptied)" "(+ 1 2)" "(some? (seon.repl/page-walk))"])]
    (is (= :emptied (:value gone)) (pr-str gone))
    (is (= 3 (:value after)) (pr-str after))
    (is (true? (:value page)) (pr-str page))))

(deftest an-agent-answers-while-its-tests-run
  ;; task seon.turn/observe-reads-are-renders (phase B): root's :tests request forks the test child
  ;; and a fiber reads it, so the agent's loop answers an eval while its own tests run. The test
  ;; child holds in its test until this test lets it go (a fifo, no clock): the eval is answered
  ;; while it holds, then the run answers green
  (let [base (str (seon.config/checkout) "/target/runs/answers-" (random-uuid))
        began (str base ".began")
        gate (str base ".gate")
        _ (doseq [f [began gate]] (sh/sh "mkfifo" f))
        {:keys [defined in-test answered result]}
        (with-agent {}
          (fn [{:keys [names eval-root ask]}]
            (let [a (:agent names) ev (comp root-value eval-root)
                  ;; two forms, as an agent writes them: each recorded on its branch (a definition inside a do is not)
                  held (str "(deftest h (let [w (java.io.FileOutputStream. " (pr-str began) ")] (.write w 49) (.flush w))"
                            " (is (= 50 (.read (java.io.FileInputStream. " (pr-str gate) ")))))")
                  _ (ev (str "@(c/submit! sys :" a " " (pr-str "(ns held-proof (:require [clojure.test :refer [deftest is]]))") ")"))
                  defined (ev (str "@(c/submit! sys :" a " " (pr-str held) ")"))
                  ;; the test service proves the branch as its turn does (mode :turn: the tests the branch changed,
                  ;; held-proof/h among them): it asks the idle agent for :tests
                  _ (ev (str "(def held-run (future @(c/prove! sys :" a " #{} :turn)))"))
                  in (java.io.FileInputStream. ^String began)
                  in-test (.read in)
                  ;; the child holds in its test: the agent answers an eval meanwhile
                  answered (ask {:op :eval :agent "root" :code (str "@(c/submit! sys :" a " \"(+ 40 2)\")")})
                  out (java.io.FileOutputStream. ^String gate)
                  _ (do (.write out 50) (.flush out) (.close out) (.close in))
                  result (ev "(select-keys @held-run [:pass :fail :error])")]
              {:defined defined :in-test in-test :answered answered :result result})))]
    (sh/sh "rm" "-f" began gate)
    (is (= 49 in-test) (str "the test child was in its test (" (pr-str defined) ")"))
    (is (= 42 (ts/value-of (ts/value-of (:response answered)))) (str "the agent answered while its test held: " (pr-str answered)))
    (is (= {:pass 1 :fail 0 :error 0} result) (str "and the run answered green once let go: " (pr-str result)))))

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
                (try (agent/read-to-exit r pid)
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
                   (lib/c-exit (if (and (= {:line "2" :held? false} (dissoc res :status)) (= true @sent) (= {:exit 0} (:status res))) 0 3))))
        pid (lib/fork! {:refused (fn [n] {:refused n}) :parent identity
                        :child (fn [] (jolt.host/main-carrier-run! reader))})]
    (is (= {:exit 0} (lib/wait-status pid))
        "the reader, a main carrier, read both lines: the go came from its other fiber while the read waited")))

(deftest root-interrupts-a-blocked-take
  ;; root's interrupt! stops an eval blocked in a
  ;; channel take, and the agent answers its next request
  (let [[stopped next-answer]
        (with-agent {}
          (fn [{:keys [names eval-root ask]}]
            (let [a (:agent names) ev (comp root-value eval-root)]
              (ev (began-code a "(clojure.core.async/<!! (clojure.core.async/chan))"))
              (ev (str "(c/interrupt! sys :" a ")"))
              [(ev "(clojure.string/starts-with? (:seon.error/message (:ex @blocked)) \"interrupted by root\")")
               (ev (str "(:value @(c/submit! sys :" a " \"(+ 2 2)\" {:origin :outside}))"))])))]
    (is (true? stopped) (str "the blocked eval answers interrupted by root: " stopped))
    (is (= 4 next-answer) (pr-str next-answer))))

(deftest an-agent-holds-mains-tests-and-runs-them
  ;; main holds the source root's test namespaces
  ;; (imported from test/), and an agent forked from the fork server holds them compiled:
  ;; it runs one without requiring it, and a green test reports green, a red one red
  (let [green 'seon.reader-test/text-without-a-fence-is-a-comment
        red 'seon.reader-test/an-agent-made-this-red
        [held _ run] (in-agent #(vector "(boolean (find-ns 'seon.reader-test))"
                                (str "(in-ns 'seon.reader-test) (clojure.test/deftest an-agent-made-this-red (clojure.test/is (= 1 2))) (in-ns 'agent." (:agent %) ")")
                                (str "(let [rs (atom {})]"
                                     " (binding [clojure.test/report (fn [m] (when (#{:pass :fail :error} (:type m))"
                                     " (swap! rs update (symbol (first clojure.test/*testing-vars*)) (fnil conj #{}) (:type m))))]"
                                     " (clojure.test/test-vars [#'" green " #'" red "]))"
                                     " @rs)"))
                               {})]
    (is (true? (:value held)) "the agent holds main's test namespace without requiring it")
    (is (= {green #{:pass} red #{:fail}} (:value run))
        "main's test runs green in the agent; a red one beside it reports red")))

(deftest root-holds-no-test-code-and-its-agents-hold-mains-tests
  ;; task seon.root/root-holds-no-test-code: root imports test/ as facts (reach digests) and evaluates
  ;; none of it; the fork server loads the tests once, so the agent forked from it holds them
  ;; compiled (and a test child it forks runs them: an-agent-holds-mains-tests above)
  (let [;; the code this proves, named so its reach digest moves with it: root's boot and writer, the fork server's load
        _ [#'seon.root/boot! #'seon.writer/main-commit #'seon.fork-server/main]
        t "(some-> (resolve 'seon.reader-test/text-without-a-fence-is-a-comment) bound?)"
        [agent-held root-held facts] (in-agent [(str "(boolean " t ")")
                                                {:op :eval :agent "root" :code (str "(boolean " t ")")}
                                                {:op :eval :agent "root" :code "(boolean (seon.db/eid @(:conn sys) :var/name 'seon.reader-test/text-without-a-fence-is-a-comment))"}]
                                               {})]
    (is (true? (:value agent-held)) "the agent holds main's test compiled")
    (is (false? (:value root-held)) "root does not")
    (is (true? (:value facts)) "though main's program holds it as facts")))

(defn- statuses
  "The code of {test status} over the run that code x answers, every test it ran or reused (its
  :digests): an answer carries only its proofs' passes and its reds (seon.proofs/answer), so every
  other test it holds a digest of was green."
  [x] (str "(let [r " x "] (into {} (for [t (keys (:digests r))] [t (get (seon.proofs/results r) t :pass)])))"))

(deftest an-agents-test-run-is-its-proofs-and-what-its-change-reaches
  ;; the agent holds main's whole suite, and its
  ;; test run (root's :tests request, the one the judge sends) selects what the done rule
  ;; reads: the task's proofs and the tests reaching what its branch changed since the main
  ;; it forked from. One redefined function (same behaviour, another form) reaches one test; its namespace's other tests
  ;; and every other namespace's do not run
  (let [proof 'seon.reader-test/text-without-a-fence-is-a-comment
        reached 'seon.config-test/the-command-line-s-overrides-are-taken-out-of-a-mode-s-args
        ;; the other test that reaches without-overrides (it reads the profile from the command line, 4a34eb9227)
        also 'seon.config-test/the-profile-is-read-from-the-command-line
        [_ run] (in-agent #(vector (str "(in-ns 'seon.config) (defn without-overrides \"args with --config and --set (and their arguments) taken out: what a mode reads.\" {:malli/schema [:=> [:cat [:maybe [:sequential :string]]] [:vector :string]]} [args] (vec (:rest (overrides args)))) (in-ns 'agent." (:agent %) ")")
                           {:op :eval :agent "root" :code (statuses (str "@(seon.root/run-tests! sys :" (:agent %) ")"))})
                          {:proofs #{proof}})]
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
  ;; only the test it ran. Sixteen tests of four namespaces and one that dies: at the default :seon.config.proofs/child-min-ms they are worth two children or more (a-handful-of-tests-runs-in-one-child is the bound's other side).
  ;; And a child forked after a sibling holds no copy of the sibling's pipe,
  ;; whose end a child must never keep (the agent's death would not reach the sibling by SIGIO while any copy is open). Each
  ;; namespace's first test counts the descriptors open in its child (fcntl F_GETFD) and writes the count to a file: a child
  ;; holding a sibling's pipe end has one more than the child forked before it
  (let [dir (str (seon.config/checkout) "/target/runs/fds-" (random-uuid))
        _ (.mkdirs (java.io.File. ^String dir))
        ns-form (fn [n] (str "(ns app.par" n "-test (:require [clojure.test :refer [deftest is]])) (deftest a (spit " (pr-str (str dir "/" n))
                             " (count (filter #(not (neg? (seon.sock/c-fcntl % 1 0))) (range 256)))) (is (= 1 1))) (deftest b (is (= 2 2))) (deftest c (is (= 3 3))) (deftest d (is (= 4 4)))"
                             (when (= n 3) " (deftest dies (seon.lib/c-exit 7))")))
        run #(str "(let [pids (atom []) r (seon.agent/run-tests-forked 100000000 #{} :main (fn [p] (swap! pids conj p)) '#{app.par0-test app.par1-test app.par2-test app.par3-test})]"
                  " (pr-str {:children (count (distinct @pids)) :pass (:pass r) :error (:error r) :died (vec (map :var (filter :exit (:reports r))))"
                  "          :ran (vec (sort (map :var (filter #(= :pass (:type %)) (:reports r)))))}))")
        [_ _ _ _ two] (in-agent (conj (mapv ns-form (range 4)) (run)))
        v #(some-> (:value %) clojure.edn/read-string)
        counts (mapv #(parse-long (ts/output (str dir "/" %))) (range 4))]
    (sh/sh "rm" "-rf" dir)
    (is (every? pos-int? counts) (str "every child counted: " (pr-str counts) " " (pr-str two)))
    (is (apply = counts) (str "the children hold the same descriptors: " (pr-str counts)))
    (is (<= 2 (:children (v two))) "at least two children forked (a death forks a new one for what its child had left)")
    (is (= [16 1 ["#'app.par3-test/dies"]] ((juxt (comp count :ran) :error :died) (v two))) "all sixteen passed once, and the death cost only the test it ran")))

(deftest a-message-sent-while-the-agent-waits-on-its-tests-reaches-its-inbox
  ;; task seon.agent/an-agent-has-one-wait: prove! waits for root's answer on the same wait as
  ;; every other ask (await!), so a delivery read there lands in the inbox, as when idle
  (let [[sent proved taken] (in-agent #(vector {:op :message :agent (:agent %) :body "while proving"}
                                               "(select-keys (prove) [:results])"
                                               "(my.agent/messages)"))]
    (is (true? (:ok sent)))
    (is (map? (:value proved)))
    (is (re-find #":body \"while proving\"" (:text taken)) (:text taken))))

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
                  {:proofs #{}})]
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
                  {:proofs #{}})]
    (is (= '{leaks-test/w :pass} (first (:value run))) (pr-str run))
    (is (false? (:target (:value after))) "the test's relative write is not in the agents' working directory")
    (is (not (.exists (java.io.File. (str "/tmp/seon-agents/tests/" (second (:value run)))))) "and its directory is removed")))

(deftest a-red-at-its-digest-is-reused-with-its-failures
  ;; task seon.proofs/a-red-at-its-digest-is-reused: this agent's run cached r red at its digest; r is green
  ;; if it runs, so a run would say :pass. The judge reuses the red, with the failure its run
  ;; cached, and forks nothing. Another's red is not reused: a digest does not see the machine, and it runs
  (let [[_ dg _ judge _ other]
        (in-agent ["(ns reds-test (:require [clojure.test :refer [deftest is]]))
                   (deftest r (is true))"
                   "(get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/r})) 'reds-test/r)"
                   "(seon.fx/write-verdicts! {:id \"main@1\" :key (:key @seon.agent/state)}
                                             {'reds-test/r {:test 'reds-test/r :digest (get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/r})) 'reds-test/r) :pass 0 :fail 1 :error 0}}
                                             {'reds-test/r [{:type :fail :message \"the recorded failure\" :expected \"1\" :actual \"2\"}]})"
                   "(let [r (seon.agent/run-tests-forked 100000000 #{'reds-test/r} :judge)]
                      [(seon.proofs/results r) (:ran r) (:reused r) (some? (:pid r)) (:fail r) (mapv (juxt :message :reused) (:reports r))])"
                   "(do (deftest other-red (is true))
                        (let [dg (get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/other-red})) 'reds-test/other-red)]
                          (seon.fx/write-verdicts! {:id \"another\" :key \"another/a9\"} {'reds-test/other-red {:test 'reds-test/other-red :digest dg :pass 0 :fail 1 :error 0}}
                                                   {'reds-test/other-red [{:type :fail :message \"another's failure\" :expected \"1\" :actual \"2\"}]})))"
                   "(let [r (seon.agent/run-tests-forked 100000000 #{'reds-test/other-red} :judge)] [(get (seon.proofs/results r) 'reds-test/other-red) (:ran r)])"]
                  {:proofs #{}})]
    (is (string? (:value dg)) (pr-str dg))
    (is (= ['{reds-test/r :fail} 0 1 false 1 [["the recorded failure" true]]] (:value judge))
        (str "red as recorded, with its failure, nothing run and no child forked " (pr-str judge)))
    (is (= [:pass 1] (:value other)) (str "another's red runs, and is green: " (pr-str other)))))

(deftest a-test-run-writes-its-results-on-the-branch
  ;; task seon.proofs/test-results-are-a-few-facts (R2a): an agent's run of its proofs records each
  ;; result as facts on the test's var entity on its branch (seon.proofs/result-tx, beside the cache):
  ;; red with its digest and its failing assertions as data, main holding none of it; fixed and run again, green with no
  ;; failure and the sums started over at the new digest; a run that changes nothing writes nothing
  (let [facts "(let [pull (fn [db] (if-let [e (seon.db/eid db :var/name 'facts-test/r)] (datahike.api/pull db '[*] e) {}))]
                 {:branch (select-keys (pull (seon.agent/db)) [:seon.test/digest :seon.test/status :seon.test/failures :seon.test/ticks])
                  :main (select-keys (pull (seon.agent/heard-main)) [:seon.test/digest :seon.test/status :seon.test/failures :seon.test/ticks])})"
        [_ red fact-red _ green fact-green again]
        (in-agent ["(ns facts-test (:require [clojure.test :refer [deftest is]])) (deftest r (is (= 1 2) \"it is one\"))"
                   "(select-keys (my.agent/prove) [:results])" facts
                   "(deftest r (is (= 1 1)))"
                   "(select-keys (my.agent/prove) [:results])" facts
                   "(let [t (:max-tx (seon.agent/db))] (my.agent/prove) (= t (:max-tx (seon.agent/db))))"]
                  {:proofs #{'facts-test/r}})]
    (is (= {:results '{facts-test/r :fail}} (:value red)))
    (let [{:keys [branch main]} (:value fact-red)]
      (is (= :red (:seon.test/status branch)) (pr-str fact-red))
      (is (string? (:seon.test/digest branch)))
      (is (= [{:type :fail :message "it is one"}] (mapv #(select-keys % [:type :message]) (:seon.test/failures branch))) "its failing assertion, as data")
      (is (= {} main) "main holds none of it"))
    (is (= {:results '{facts-test/r :pass}} (:value green)))
    (let [{:keys [branch]} (:value fact-green)]
      (is (= :green (:seon.test/status branch)))
      (is (nil? (:seon.test/failures branch)) "no failures while green"))
    (is (true? (:value again)) "a run that changes nothing writes no datoms")))

(deftest a-green-that-is-no-proof-writes-no-fact
  ;; docs/sharing.md#what-a-branchs-nodes-are: a green that is no proof of the task is read by nothing
  ;; on this branch (seon.reconcile/done-explain reads proofs and reds), and its facts on its var rewrite a leaf
  ;; among the program's. A red is held, and stays held when it goes green, so it never reads as still red
  (let [facts "(into {} (for [t '[green-test/proof green-test/other green-test/turned]] [t (:seon.test/status (datahike.api/pull (seon.agent/db) [:seon.test/status] [:var/name t]))]))"
        [_ _ first-facts _ _ second-facts]
        (in-agent ["(ns green-test (:require [clojure.test :refer [deftest is]])) (deftest proof (is (= 1 1))) (deftest other (is (= 1 1))) (deftest turned (is (= 1 2)))"
                   "(select-keys (my.agent/prove) [:results])" facts
                   "(deftest turned (is (= 1 1)))"
                   "(select-keys (my.agent/prove) [:results])" facts]
                  {:proofs #{'green-test/proof}})]
    (is (= '{green-test/proof :green green-test/other nil green-test/turned :red} (:value first-facts)) (pr-str first-facts))
    (is (= '{green-test/proof :green green-test/other nil green-test/turned :green} (:value second-facts)) (pr-str second-facts))))

(deftest an-agents-greens-and-a-checkouts-are-one-store
  ;; owner, 2026-10-23: a test green at its digest is not run again, "especially when freshly branched": an agent's greens and a
  ;; checkout's (bin/test's) are one verdict cache, so an agent's plan must key each test by the digest bin/test's plan does.
  ;; bin/test's is seon.program/graph-index over the program's reach graph; an agent's is its base's graph brought to its branch
  ;; (seon.agent/test-plan: branch-graph, reach-indexed). On a small program under one boot's inputs the two agree, freshly
  ;; branched and after the branch changes a form. That a verdict stored at a digest is then reused is the cache's
  ;; (seon.program-test/a-result-recorded-in-one-checkout-is-reused-in-another).
  (let [in {:env "e" :tree "t" :config {} :data-refs {}}
        tests @#'seon.program-test/calc-tests
        base (d/db-with (#'seon.program-test/calc-db)
                        [{:seon.root/id :root :seon.root/boot "b1" :seon.root/source {:root "/r" :files 0 :forms 0 :ms 0 :boot "b1" :inputs in}}])
        branch (seon.program-test/record base 'app.pt-calc "(defn add [a b] (+ b a))")
        checkout (fn [db] (select-keys (ts/reach-digests db in) tests))
        agent (fn [db] (select-keys (:digests (agent/test-plan db base tests)) tests))]
    (is (= tests (set (keys (checkout base)))) "every test has a digest")
    (is (= (checkout base) (agent base)) "freshly branched: the agent keys each test as bin/test does")
    (is (= (checkout branch) (agent branch)) "and after its branch changes a form")
    (is (not= (checkout base) (checkout branch)) "which moved a digest")))

(deftest a-previous-roots-inputs-are-not-shared
  ;; re-review 2026-10-22: the :root entity outlives its root, so a restart (or a root with no
  ;; source on the same main) must not key its agents' results by a fact an earlier boot wrote
  (let [inputs {:env "e" :tree "t" :config {} :data-refs {}}
        source {:root "/r" :files 0 :forms 0 :ms 0 :boot "b1" :inputs inputs}
        db (fn [boot] (datahike.api/db-with (ts/test-db) [{:seon.root/id :root :seon.root/boot boot :seon.root/source source}]))]
    (is (= inputs (first (agent/test-inputs (db "b1")))) "this boot's inputs: its results are keyed by them")
    (is (nil? (first (agent/test-inputs (db "b2")))) "an earlier boot's: nothing keys by them, so nothing is reused")))

(deftest a-forked-agent-knows-its-task
  ;; root forks the agent for its
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
  ;; step of more than 64 KB in the server's static (measured: 19 in 20 forks before, 1 to 3 after).
  ;; It is due when what an agent would inherit passes :seon.config.agent/inherited-bytes, so what the forks may
  ;; freeze follows from what static grew: one more than that many, whichever point of the cycle the shard's
  ;; earlier tests left it at (a fixed count sat on the edge: 3 at 2.2 MB of main a fork)
  (let [statics (vec (for [_ (range 10)] (:value (first (in-agent ["(jolt.scheme/eval-string \"(bytes-allocated 'static)\")"])))))
        freezes (count (filter #(> % 65536) (map - (rest statics) statics)))
        due (inc (quot (- (peek statics) (first statics)) (seon.config/value :seon.config.agent/inherited-bytes)))]
    (is (<= freezes due) (str freezes " freezes in 10 forks, " due " due by what static grew; the server's static at each: " (pr-str statics)))))

(defn- history-tx
  "What n agents leave on main, shaped as production's (an agent started on task, and an eval record
  each), numbered from i: the history an-agents-start-costs-the-same-however-long-its-root-has-run
  grows main by between its agents."
  {:malli/schema [:=> [:cat :string nat-int? pos-int?] :seon.db/tx-data]}
  [task i n]
  (vec (mapcat (fn [k] [{:seon.agent/key (str "hist-" k) :seon.agent/boot "hist" :seon.agent/started-ms k :seon.agent/task {:seon.task/id task}}
                        {:seon.eval/id (str "e" (+ 1000000 k)) :seon.eval/by "root" :seon.eval/agent {:seon.root/id :root} :seon.eval/code "(+ 1 1)"}])
               (range i (+ i n)))))

(deftest an-agents-start-costs-the-same-however-long-its-root-has-run
  ;; docs/machine.md#an-agents-first-write: a cost proof in bytes, judged by seon.cost/judge on what
  ;; each agent measured. An agent's bytes from its :state mark (init done) to its first request
  ;; grew with its root's history: 5.1 MB at a shared root's first agent, 13.2 MB at its 40th, so this
  ;; test's bound of 12 MB was red in long shards (2026-10-31). Three causes, each a read of main an
  ;; agent repeated cold: the leaves main's commits wrote and the fork server never read (each agent
  ;; decoded them, ~300 KB a leaf), the leaves of what every agent reads (its cited sections, the
  ;; schema entries) evicted from the fork server's full node cache because an agent's reads never
  ;; reach it, and numbering an eval by reading every eval main records. The baseline is :fixed; a
  ;; change that lowers it lowers :fixed (an under past seon.cost/loose is a finding). The node
  ;; cache is small here, so a full cache is reached at this history.
  (let [sizes [10 100 1000]
        e {:of 'seon.agent/begin-run! :input 'seon.agent-test/history-tx :n "agents on main before the agent's fork"
           :work "the agent's own start: no read of main's history" :growth :constant :sizes sizes
           :fixed {:bytes 6.0e6}}
        measure "(- (seon.profile/alloc-bytes) (nth (last (:marks @seon.agent/state)) 2))"
        opts (merging)
        ;; history as it grows: commits of 10 agents each (each rewrites the leaves it touches, as a root's starts do)
        grow (fn [{:keys [task]} from n] {:op :eval :agent "root" :code (str "(doseq [i (range " from " " (+ from n) " 10)] @(seon.root/transact-main! sys (seon.agent-test/history-tx " (pr-str task) " i 10)))")})
        ;; each agent measures, then grows main to the next size; the next agent forks after it
        steps (map vector (cons 0 sizes) (concat sizes [nil]))
        measured (vec (for [[have want] steps]
                        (let [[r] (in-agent (fn [names] (cond-> [measure] want (conj (grow names have (- want have))))) opts)]
                          (:value r))))
        rows (vec (for [[n b] (map vector sizes (rest measured))] {:n n :measured {:bytes (double b)} :expected (cost/expected e cost/units n)}))
        v (cost/judge e rows)]
    (is (:ok? v) (str (cost/explain e rows v) "\nat the root's start: " (first measured) " bytes"))))

(deftest an-eval-reads-the-schema-entries-only-after-one-moved
  ;; docs/machine.md#an-agents-first-write: seon.repl/branch-registry, which each eval calls (wrap-registry,
  ;; instrument, the page walk), read every schema entry (two passes, seon.program/schema-changes: 748
  ;; entries, 1.08 MB) to learn nothing had changed. With the registry held (the fork hands main's) a call
  ;; costs under a tenth of one read; after a registration the next eval binds the registered schema.
  (let [m "(fn [f] (let [b (seon.profile/alloc-bytes)] (f) (- (seon.profile/alloc-bytes) b)))"
        [_ costs reg valid]
        (in-agent [(str "(def meas " m ")")
                   "[(meas #'seon.repl/branch-registry) (meas #(seon.program/schema-changes (:db @seon.agent/state)))]"
                   "(my.schema/register! :lab.gap/n [:int {:min 3}])"
                   "[(malli.core/validate :lab.gap/n 5 {:registry malli.registry/*registry*}) (malli.core/validate :lab.gap/n 1 {:registry malli.registry/*registry*})]"])
        [held read] (:value costs)]
    (is (< (* 10 held) read) (str "a call of branch-registry with the registry held allocated " held " bytes; one read of the schema changes " read))
    (is (= [true false] (:value valid)) (str "after a registration the next eval binds it: " (pr-str reg) " " (pr-str valid)))))

(deftest root-reads-the-health-the-render-proc-keeps
  ;; docs/health.md#cost: my.root/problems read main's health whole at every call (~600 MB on the standing
  ;; root); it reads the render proc's, kept by what each move of main changed, at the api's fold. It
  ;; answers what the whole read does, after boot and after main moved, and a read with nothing moved
  ;; costs under a tenth of the whole read.
  (let [same "(let [h (my.root/problems sys) w (seon.problems/health @(:conn sys) (seon.root/errors sys))] [(= (:classes h) (:classes w)) (count (:classes h))])"
        cost "(let [m (fn [f] (let [b (seon.profile/alloc-bytes)] (f) (- (seon.profile/alloc-bytes) b)))] (my.root/problems sys) [(m #(my.root/problems sys)) (m #(seon.problems/health @(:conn sys) (seon.root/errors sys)))])"
        [booted moved costs]
        (in-agent (fn [{:keys [task]}]
                    [{:op :eval :agent "root" :code same}
                     {:op :eval :agent "root" :code (str "(do @(seon.root/transact-main! sys [{:seon.task/id " (pr-str task) " :seon.task/failure {:reason \"provoked\"}}]) " same ")")}
                     {:op :eval :agent "root" :code cost}]))
        [held whole] (:value costs)]
    (is (= true (first (:value booted))) (str "after boot, held and whole agree: " (pr-str booted)))
    (is (= true (first (:value moved))) (str "after main moved, held and whole agree: " (pr-str moved)))
    (is (< (* 10 held) whole) (str "a held read allocated " held " bytes; a whole read " whole))))

(deftest an-agents-first-search-reads-the-index-it-inherited
  ;; root's REPL writes main through my.test/code, a namespace the binary holds (seon.root requires it): a boot loads
  ;; nothing of main's program (docs/source.md#a-boot-resumes-from-the-db), and this test was red on a binary without it
  ;; docs/vector.md#search: an agent's first grep built the search's text index from nothing (seon.doc/text-index).
  ;; The fork server builds main's once and each move carries it (seon.doc/carry-index!): the
  ;; first search finds the index held for its db and builds none, and its FTS5 table, an in-memory SQLite database,
  ;; answers in the forked child. The bytes of the first search's section reads are leaf decoding in the child,
  ;; which depends on the store's layout, so they are not this test's claim. That a merge of main keeps it current is
  ;; seon.agent-test/after-merge-main-the-agent-judges-against-the-new-base's, in this process
  (let [results (in-agent [(str "(def held (fn [] (let [d (:db @seon.agent/state) [at ix] @@#'seon.doc/indexes] {:current (= at [(:hash d) (:max-tx d)]) :ix ix})))")
                            "(def before (held))"
                            "(def rows (count (my.agent/grep \"accumulated\")))"
                            "(def after (held))"
                            "{:rows rows :current-before (:current before) :current-after (:current after) :same-index (identical? (:ix before) (:ix after)) :held? (some? (:ix before))}"])
        held (nth results 4)
        {:keys [rows current-before current-after same-index held?]} (:value held)]
    (is held? "the agent inherited an index")
    (is current-before "the index it inherited is the one held for its db")
    (is same-index "its first search built no index: the slot holds the same one after it")
    (is current-after "and still the one held for its db")
    (is (pos? rows) "the query of the index it inherited answers in the forked child")))

(deftest a-resumed-branchs-agent-is-this-boots
  ;; one kept branch, one spawn, three claims about an agent resumed onto it.
  ;; found by session global-ids, 2026-10-23: an agent spawned with no task onto a branch the
  ;; store already holds (a kept branch; e2e's fresh agent on a store an earlier run left)
  ;; died in its init, giving seon.task/onto-tx a nil task, and a wait for its :ready never
  ;; ended. A kept branch takes its task from main only when the agent has one.
  ;; page's browser round, 2026-10-23: an agent on a kept branch recorded its evaluations under an
  ;; earlier boot's agent (its key read root's boot from the branch, whose root fact is as old as
  ;; the branch). Now a key names no boot: it is the id's name, whatever boot the branch is from.
  ;; step E run #11 (2026-10-23): a kept branch made before main imported test/ was resumed,
  ;; and its agent held seon.ctx.wire-test with no vars (seon.agent/init! unmapped every
  ;; definition main had that the branch did not), so the design never reached its proof's
  ;; fixtures; and it ran the branch's old version of a function main had redefined since.
  ;; A resumed agent runs main with the branch's own changes since its merge base
  (let [[r] (in-agent (fn [{:keys [ns]}]
                        (let [kept (str ":task-" ns "-kept")
                              k (str "demo." ns ".kept") g (str "demo." ns ".gained")
                              src (fn [n body] (str "@(seon.root/transact-main! sys (seon.source/text-tx @(:conn sys) '" n " \"(ns " n ")\\n\\n" body "\\n\" nil))"))]
                          [{:op :eval :agent "root"
                            :code (spawn-kept-code kept
                                                   (str (src k "(defn h [] 1)")
                                                        " (datahike.api/branch! (:conn sys) m b)"
                                                        " (let [bc (seon.db/connect (assoc (:cfg sys) :branch b))]"
                                                        "   (datahike.api/transact bc [{:seon.root/id :root :seon.root/boot \"an-earlier-boot\"}]) (datahike.api/release bc))"
                                                        " " (src g "(defn g [] 7)")
                                                        " " (src k "(defn h [] 2)"))
                                                   (str "[:ready (:boot sys) [(name ag) (:text @(seon.root/submit! sys ag \"(:key @seon.agent/state)\"))]"
                                                        " (:text @(seon.root/submit! sys ag \"[(" g "/g) (" k "/h)]\"))]"))}]))
                      (merging))
        [status boot [id text] gained] (:value r)]
    (is (= :ready status) (pr-str r))
    (is (str/includes? (str text) (str "\"" id "\"")) (str "it records under its own key, the name of its id, whatever boot the branch is from: " text))
    (is (not (str/includes? (str text) "an-earlier-boot")) "and no key of the earlier boot's")
    (is (str/includes? (str gained) ":value [7 2]") "a var main added, and main's redefinition of one the branch left alone")))

(deftest a-failure-marked-through-the-server-holds-the-task
  ;; owner, 2026-10-22: a failure is task data and root reacts to it. The agent marks its
  ;; task failed on its branch, hands the task back (seon_done), and root, at the
  ;; agent's end, holds the task as :failed and reports it among the problems
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
    ;; and the answer says so itself: failed work is never reported done or merged
    (is (and (str/includes? (:line done) ":outcome :closed") (str/includes? (:line done) "the premise is refuted")) (pr-str done))
    (is (= [:failed {:task (:task @names) :reason "the premise is refuted" :counterexample "(= 1 2)" :open? true}]
           (:value seen)))))

(defn- ended-code
  "Root-REPL code answering how task id's work stands after its agent handed it back: [the task's outcome and reason on main,
  the agent's own end on its branch (seon.reconcile/pull-end)]."
  {:malli/schema [:=> [:cat :seon.task/id] :string]}
  [id]
  (str "(let [db @(:conn sys) t (seon.task/task db " (pr-str id) ")"
       " b (:db @(seon.root/branch-db sys (seon.task/default-branch " (pr-str id) ")))]"
       " [(select-keys t [:seon.task/outcome :seon.task/reason]) (seon.reconcile/pull-end b (:seon.task/agent t))])"))

(deftest an-outside-agent-writes-its-end
  ;; found by a worked example (evaluations session, 2026-10-23): an outside agent's :done
  ;; judged a task whose agent had no end, so it read "the agent wrote no end"
  ;; whatever its proofs said. The agent writes the end first, as a turn loop does: here its
  ;; one proof is not written, so the end is :red and names why
  (let [[_ _ done seen] (in-agent (fn [{:keys [agent task]}]
                                    [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                                     before-done {:op :done :agent agent}
                                     {:op :eval :agent "root"
                                      :code (str "(do (seon.await/await-quiet sys (inc t-before-done)) " (ended-code task) ")")}]))
        [t end] (:value seen)]
    (is (str/includes? (:line done) ":outcome :closed") (str "the answer is the verdict line: " (pr-str done)))
    (is (= :red (:outcome end)) (pr-str end))
    (is (= :closed (:seon.task/outcome t)) (pr-str t))
    (is (str/includes? (str (:reason end)) "proof-unknown demo-test/t") "the end names why it is red")
    (is (str/includes? (str (:seon.task/reason t)) "proof-unknown demo-test/t") "and the task holds it")
    (is (not (str/includes? (str (:seon.task/reason t)) "no end")) "never read as an end nobody wrote")))

(deftest a-branch-that-deletes-mains-test-it-breaks-is-not-merged
  ;; owner, 2026-10-26: "auto merge (the goal) means the old tests need to pass too. So deleting
  ;; the old test doesn't stop the old test from running": a branch breaks what one of main's
  ;; tests checks and deletes that test; the test still judges, from main's definition, so the
  ;; end is red, the task not merged, and its reason names main's test. The decision is the
  ;; judge's pure functions on two db values (docs/testing.md#testing-in-the-world): the plan
  ;; seon.agent/test-plan selects the removed test with main's source, the runner runs it against
  ;; the branch's program, and seon.reconcile/done-explain refuses the end naming it
  (let [main-test 'app.calc-test/add-adds
        main (mt/world)
        branch (mt/with main
                        (mt/code 'app.calc "(ns app.calc)\n(defn add [x y] (- x y))\n(defn inc2 [x] (add x 2))\n(defn total [xs] (reduce add 0 xs))\n")
                        (mt/without main-test))
        plan (agent/test-plan branch main #{})
        src (get (:removed plan) main-test)
        ran (atom [])
        results (try (remove-ns 'app.calc-test) (remove-ns 'app.calc)
                     ;; the branch's program, then main's removed test as the test child defines it
                     (binding [*ns* (create-ns 'app.calc)] (refer-clojure) (load-string "(defn add [x y] (- x y))"))
                     (#'agent/load-removed! (:removed plan))
                     (proofs/run-tests! [main-test] {main-test nil}
                                        (fn [x] (when-let [m (:seon.test/report x)] (swap! ran conj {:type (:type m) :var (str "#'" (:test x))}))))
                     (proofs/results {:reports @ran})
                     (finally (remove-ns 'app.calc-test) (remove-ns 'app.calc)))
        idx (r/index main)
        reasons (r/done-explain (r/done-input idx "app.calc/total-is-exact" (assoc results 'app.calc-test/total-sums :pass) #{} [] nil nil #{}))]
    (is (string? src) (str "the plan holds main's test with its source: " (pr-str (:removed plan))))
    (is (some #{main-test} (:run plan)) "and runs it, though the branch holds none")
    (is (= :fail (get results main-test)) (str "main's test is red against the branch's program: " (pr-str results)))
    (is (seq reasons) "the end is refused: not merged")
    (is (str/includes? (r/explain-text reasons) (str main-test)) (str "its reason names main's failing test: " (pr-str reasons)))))

;; docs/merging.md#main-moved-is-said-at-every-eval, the owner 2026-11-07: an agent never runs old code. A model's turn merged main when it moved; an
;; outside agent was told and went on running what it had, until it chose to merge. Its reply merges as a turn does
(deftest an-outside-agents-reply-takes-mains-program
  ;; on the shard's root: what main gains is a namespace of this test's own, which no other test reads
  (let [last-value (fn [r] (first (filter boolean? (map :value (:responses r)))))
        [_ before _ after]
        (in-agent (fn [{:keys [agent task ns]}]
                    (let [moved (str ns ".moved-on-main")
                          ;; a reply's responses hold the page Seon shows it too: the probe's is the one boolean
                          reply {:op :eval :agent agent :message-md "" :evals [{:intent "has main's new function?" :code (str "(some? (resolve '" moved "/f))")}]}]
                      [{:op :attach :task task}
                       reply
                       {:op :eval :agent "root"
                        :code (str "(let [db @(:conn sys)] @(seon.root/transact-main! sys (seon.program/record-tx db {:id \"" moved "/(defn f [] 1)\" :src \"(defn f [] 1)\" :ns '" moved " :ns-form? false"
                                   " :defs [{:var '" moved "/f :meta #{:arglists} :contract nil}] :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})))")}
                       reply])))]
    (is (false? (last-value before)) (pr-str before))
    (is (true? (last-value after)) (str "the reply after main moved holds main's program: " (pr-str after)))))

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
                              ;; an agent past 256 MB of heap is out of memory: its task's own bound (docs/config.md#the-resolver)
                              {:config {:seon.config.agent/heap-extra-bytes 268435456}})
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
  ;; (seon.fixtures/two-calls) writes its second call from the answer its first call got. (A reply's text and reasoning are
  ;; an assistant message of their own before its calls, left out here: seon.ctx.wire-test has them.) The request's parts of the
  ;; agent's evals are read ([:eval id], seon.ctx.wire/request's :parts), so the page, a call Seon made ([:page n]), is not one of them
  (let [[end msgs] (in-agent (fn [{:keys [task ns]}]
                               (let [run (str ns "/t#calls")]
                                 [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 2})")
                                  (str "(let [db (seon.agent/db)]"
                                       " (->> (:parts (seon.ctx.wire/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) 
                                       "                                       :turn 3 :max-turns 2 :origin :model :agent (:key @seon.agent/state)}))"
                                       "      (filter #(= :eval (ffirst %))) (mapcat second)"
                                       "      (filter #(#{\"assistant\" \"tool\"} (:role %))) (remove #(and (= \"assistant\" (:role %)) (empty? (:tool-calls %)))) (mapv #(select-keys % [:role :tool-calls :tool-call-id :content]))))")]))
                             {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)
        [a0 r0 a1 r1 :as ms] (:value msgs)]
    (is (= [:cap 2] [(:outcome end) (:model-calls end)]) "two model calls (the proof stays red), then the cap")
    (is (= ["assistant" "tool" "assistant" "tool"] (mapv :role ms)) "each reply followed by its call's answer")
    (is (= [{:id (:tool-call-id r0) :code "(+ 20 21)" :intent "Add twenty and twenty-one."}] (:tool-calls a0)))
    (is (re-matches #"[a-p][0-9a-f]{15}" (:tool-call-id r0)) "the answer names its call, by the eval's own id")
    (is (str/includes? (:content r0) "41") "the first call's answer")
    (is (= [{:id (:tool-call-id r1) :code "(+ 1 41)" :intent "Add one to the value the last call showed."}] (:tool-calls a1)) "the second call was made after the first call's answer, from it")
    (is (str/includes? (:content r1) "42") "and both answers are in the conversation")))

(deftest the-page-is-a-message-in-the-conversation-and-the-prefix-holds
  ;; docs/render.md#a-change-is-edits-by-name (owner, 2026-11-05: "make the rendering of the hiccup a normal eval that we inject into turn
  ;; 0 and then we can just do diffs when it changes"; stage 2b: the page is a generated eval of each turn, never written to the db): two real turns. The first request holds the page as
  ;; the call `(my.render/page p)` and its result; these turns move nothing the page shows, so no other is sent; and every request is the one before it, byte for byte, with the records added. The hash each turn recorded of
  ;; the bytes it sent is the hash of the request made again from its records at its commit.
  (let [[end r] (in-agent (fn [{:keys [task]}]
                            [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 2})")
                             (str "(let [db (seon.agent/db) k (:key @seon.agent/state)"
                                  "      basis (fn [n] {:commit (datahike.api/commit-id db) :task " (pr-str task) " :turn n :max-turns 2 :origin :model :agent k})"
                                  "      msgs (fn [n] (let [b (:seon.model-call/basis (datahike.api/pull db '[:seon.model-call/basis] [:seon.model-call/id (seon.lib/call-id k n)]))] (:messages (seon.ctx.wire/render (datahike.api/commit-as-db (:store db) (:commit b)) b))))"
                                  "      page? (fn [m] (boolean (some-> m :tool-calls first :code (.startsWith \"(my.render/\"))))"
                                  "      calls (fn [ms] (vec (keep #(some-> % :tool-calls first :code) (remove page? ms))))"
                                  "      pages (fn [ms] (vec (for [[a b] (partition 2 1 ms) :when (page? a)] (:content b))))"
                                  "      m1 (msgs 1) m2 (msgs 2) m3 (:messages (seon.ctx.wire/render db (basis 3)))"
                                  "      redrawn (fn [n] (let [m (datahike.api/pull db '[:seon.model-call/sent-hash :seon.model-call/basis] [:seon.model-call/id (seon.lib/call-id k n)])"
                                  "                              b (:seon.model-call/basis m)]"
                                  "                          [(:seon.model-call/sent-hash m) (seon.lib/digest (seon.edn/encode (:messages (seon.ctx.wire/render (datahike.api/commit-as-db (:store db) (:commit b)) b))))]))]"
                                  "  {:calls [(calls m1) (calls m2) (calls m3)] :prefix [(= m1 (subvec m2 0 (count m1))) (= m2 (subvec m3 0 (count m2)))]"
                                  "   :by (vec (sort (datahike.api/q '[:find [?c ...] :where [?e :seon.eval/by \"seon\"] [?e :seon.eval/code ?c]] db)))"
                                  "   :pages [(count (pages m1)) (count (pages m2)) (count (pages m3))]"
                                  "   :hashes [(redrawn 1) (redrawn 2)]"
                                  "   :first-page (str (first (pages m1)))"
                                  "   :first-code (some-> (first (filter page? m1)) :tool-calls first :code)})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        {:keys [calls prefix by pages hashes first-page first-code]} (:value r)]
    (is (= :cap (:outcome (:value end))) (pr-str end))
    (is (some? (:value r)) (pr-str (select-keys r [:ex :text])))
    (is (= [[] ["(+ 20 21)"] ["(+ 20 21)" "(+ 1 41)"]] calls)
        "the page is no call; each request holds the one before and the records added")
    (is (= [true true] prefix) "byte for byte")
    (is (= [] by) "Seon ran no eval: the page is its message")
    (is (= [1 1 1] pages) "the page whole once, in the first request; nothing it shows moved after, so no other message")
    (is (every? (fn [[sent redraw]] (and (string? sent) (= sent redraw))) hashes) (str "the hash a turn recorded is its redraw's: " (pr-str hashes)))
    (is (str/starts-with? first-code "(my.render/page {:commit #uuid \"") "the first request holds the page as the call (my.render/page p)")
    (is (str/includes? first-page "Your task") "the task's part")))

(deftest a-printed-page-call-runs-at-the-agents-repl
  ;; docs/prompt.md: before a turn Seon shows the page as a call it made, and the agent can make either call itself with any point it
  ;; printed. The points a call prints are {:commit :t} (seon.ctx.wire/point-text), so my.render/diff takes them as printed: its code,
  ;; read from the request and evaluated in the agent, answers what the call's message showed
  (let [[_ _ r] (in-agent (fn [{:keys [task]}]
                            [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                             (str "(my.task/update! :seon.task/plan (constantly \"Make it, then make it again.\"))")
                             (str "(let [db (seon.agent/db) k (:key @seon.agent/state)"
                                  "      ms (:messages (seon.ctx.wire/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) " :turn 2 :max-turns 2 :origin :model :agent k}))"
                                  "      [call answer] (first (for [[a b] (partition 2 1 ms) :let [c (some-> a :tool-calls first :code)] :when (and c (.startsWith c \"(my.render/diff\"))] [c (:content b)]))]"
                                  "  {:code call :same (when call (= answer (seon.print/text (vary-meta (eval (read-string call)) assoc :seon.print/full true))))})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        {:keys [code same]} (:value r)]
    (is (some? code) (str "the request after the plan moved holds the diff call: " (pr-str (select-keys r [:value :ex]))))
    (is (true? same) "its printed code, run at the agent's REPL, answers what the call's message showed")))

;; docs/resolution.md: the chain holds vars, so what a program defines is what it calls; a delay of fn values kept the old ones, and an edit of wrap-record reached nothing
(deftest an-edit-of-a-middleware-runs-next
  (let [[_ _ marked vars] (in-agent ["(def marks (atom 0)) (in-ns 'seon.repl)"
                                     "(let [old wrap-registry] (alter-var-root #'wrap-registry (fn [_] (fn [h] (let [n (old h)] (fn [req] (swap! @(find-var (symbol (str (ns-name (first (filter #(ns-resolve % 'marks) (all-ns)))) \"/marks\"))) inc) (n req)))))))"
                                     "@@(find-var (symbol (str (ns-name (first (filter #(ns-resolve % 'marks) (all-ns)))) \"/marks\")))"
                                     "(every? var? seon.repl/middleware)"])]
    (is (pos? (:value marked)) (pr-str marked))
    (is (true? (:value vars)))))

(deftest edit-says-what-it-did
  (let [[_ many same fine] (in-agent (fn [{:keys [ns]}]
                                       [(str "(ns " ns ".ed) (defn f \"One.\" {:malli/schema [:=> [:cat] :int]} [] (+ 1 1 1))")
                                        (str "(seon.repl/edit! '" ns ".ed/f \"1\" \"2\")")
                                        (str "(seon.repl/edit! '" ns ".ed/f \"(+ 1\" \"(+ 1\")")
                                        (str "(seon.repl/edit! '" ns ".ed/f \"(+ 1 1 1)\" \"(+ 1 1 2)\")")]))]
    (is (= :seon.repl/edit-refused (:seon.error/type (:value many))) (str "three places: refused, saying so: " (pr-str many)))
    (is (str/includes? (:seon.error/message (:value many)) "occurs 3 times"))
    (is (contains? (:value same) :unchanged) (pr-str same))
    (is (not (contains? (:value same) :edited)) "nothing changed is not an edit")
    (is (:edited (:value fine)) (pr-str fine))))

;; a read waits on no one: an agent in an eval that never ends is read by root from its branch, and an interrupt takes a test child with it
(deftest a-read-does-not-wait-on-a-hung-test
  (let [pidf (str (seon.config/checkout) "/target/tmp/hung-" (random-uuid))
        [read stopped gone]
        (with-agent {}
          (fn [{:keys [names eval-root ask]}]
            (let [a (:agent names) ev (comp root-value eval-root)
                  run (fn [code] (ev (str "(:value @(c/submit! sys :" a " " (pr-str code) " {:origin :outside}))")))]
              (run (str "(ns tt-hang-test (:require [clojure.test :refer [deftest is]])) (deftest hangs (spit " (pr-str pidf) " (str (seon.lib/c-getpid))) (let [[r w] (seon.sock/socketpair)] (is ((seon.sock/line-reader r)))))"))
              (ev (began-code a "(my.agent/prove '#{tt-hang-test/hangs})"))
              ;; the child has started: its pid is on file
              (ev (str "(loop [] (when-not (.exists (java.io.File. " (pr-str pidf) ")) (recur)))")) 
              (let [r (ask {:op :context :agent a})]
                [r
                 (do (ev (str "(c/interrupt! sys :" a ")"))
                     (ev "(some? (:ex @blocked))"))
                 ;; it goes when its reader reaps it: asked a bounded number of times, by work, not by a clock
                 (ev (str "(boolean (some false? (take 500 (repeatedly #(zero? (:exit (clojure.java.shell/sh \"ps\" \"-p\" " (pr-str (slurp pidf)) ")))))))"))]))))]
    (is (clojure.string/includes? (str (:context read)) "still running") (subs (pr-str read) 0 300))
    (is (true? stopped) "the interrupt ends the prove")
    (is (true? gone) "and the test child goes with it")
    (clojure.java.shell/sh "rm" "-f" pidf)))

;; a defmethod merges as a var does (seon.merge's key [:method multi dispatch]), so it is said as one is
(deftest a-method-is-listed-with-what-a-branch-carries
  (let [[_ method changes] (in-agent (fn [{:keys [ns]}]
                                       [(str "(ns " ns ".mm) (defmulti area \"Area.\" :k)")
                                        "(defmethod area :square [s] 1)"
                                        "(my.branch/changes)"]))]
    (is (some #(= ":square" (second %)) (filter vector? (:recorded method))) (pr-str method))
    (is (some #(and (vector? %) (= ":square" (second %))) (:added (:value changes))) (pr-str (:value changes)))))

;; docs/failure-points.md: a live agent ran (def form-of ...) in seon.program as a scratch name, every later write threw in seon.program/record-tx, and the run died
(deftest a-definition-that-breaks-its-own-recording-is-undone
  (let [[broke after still] (in-agent ["(in-ns 'seon.program) (def form-of (fn [v] v))"
                                       "(def after-it 41) (inc after-it)"
                                       "(seon.program/definition-form? '(def x 1) (the-ns 'user))"])]
    (is (= :seon.repl/definition-undone (:seon.error/type (:ex broke))) (pr-str (:ex broke)))
    (is (str/includes? (:seon.error/message (:ex broke)) "seon.program/form-of") "it says which")
    (is (= 42 (second (:value after))) "the run goes on: the next eval records")
    (is (nil? (:ex still)) "and Seon's own var is as it was")))

;; docs/storage.md#collection: collection may have swept the commit a page is drawn at: the same datoms as of t, never a later page
(deftest a-page-whose-commit-is-gone-is-drawn-as-of-its-t
  (let [[at] (in-agent ["(let [db (seon.agent/db) gone (assoc-in db [:meta :datahike/parents] #{(random-uuid)}) t (:max-tx db)]
                           [(:max-tx (#'seon.ctx.wire/at-t gone t)) t (some? (:origin-db (#'seon.ctx.wire/at-t gone (dec t))))])"])
        [was t as-of?] (:value at)]
    (is (= t was) "the commit at t itself needs no walk")
    (is (true? as-of?) "past what collection kept, a db as of t")))

;; docs/render.md#a-change-is-edits-by-name: a wake is told what moved since the page it last showed, never the page again: a second whole page
;; costs about 4k tokens a wake
(deftest a-wake-shows-what-moved-not-the-page-again
  (let [[_ _ whole calls]
        (in-agent (fn [{:keys [task]}]
                    [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                     (str "(seon.turn/work! {:task " (pr-str task) " :max-turns 2})")
                     (str "(let [db (seon.agent/db)] (count (filter #(some-> % :tool-calls first :code (.startsWith \"(my.render/\")) (:messages (seon.ctx.wire/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) " :turn 3 :max-turns 2 :origin :model :agent (:key @seon.agent/state)})))))")
                     "(count (datahike.api/q '[:find [?m ...] :where [?m :seon.model-call/origin :model]] (seon.agent/db)))"])
                  {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})]
    (is (= 2 (:value calls)) "the second wake took a turn, so it had the chance to show the page")
    (is (= 1 (:value whole)) "the page whole once, across both wakes: nothing moved, so the second turn adds no page message")))

(deftest a-call-after-one-that-threw-still-runs
  ;; docs/prompt.md: a reply's calls run in order, each whatever came before it (owner, 2026-11-08: "I want us to run every eval. Turns are precious
  ;; and one fuckup shouldn't punish the other evals"); every call has its tool message (the provider refuses a call with none)
  (let [[_ msgs] (in-agent (fn [{:keys [task ns]}]
                             (let [run (str ns "/t#throws")]
                               [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                                (str "(let [db (seon.agent/db)]"
                                     " (->> (:parts (seon.ctx.wire/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) 
                                     "                                       :turn 2 :max-turns 1 :origin :model :agent (:key @seon.agent/state)}))"
                                       "      (filter #(= :eval (ffirst %))) (mapcat second)"
                                     "      (filter #(= \"tool\" (:role %))) (mapv #(select-keys % [:tool-call-id :content]))))")]))
                           {:config {:seon.config.ai/script 'seon.fixtures/calls-one-throws}})
        [r0 r1 r2 :as rs] (:value msgs)]
    (is (= 3 (count (distinct (mapv :tool-call-id rs)))) (str "every call has its result, by its id: " (pr-str msgs)))
    (is (str/includes? (:content r1) "boom") "the second call's error")
    (is (str/includes? (:content r2) ":value 2") (str "(inc a) ran after the call that threw: " (:content r2)))))

(deftest a-call-after-an-error-answer-still-runs
  ;; docs/prompt.md: a call answering an error value (a refused edit!) is one result; the reply's later calls run, as after a throw (owner, 2026-11-08)
  (let [[_ msgs] (in-agent (fn [{:keys [task ns]}]
                             (let [run (str ns "/t#refused")]
                               [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                                (str "(let [db (seon.agent/db)]"
                                     " (->> (:parts (seon.ctx.wire/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) 
                                     "                                       :turn 2 :max-turns 1 :origin :model :agent (:key @seon.agent/state)}))"
                                       "      (filter #(= :eval (ffirst %))) (mapcat second)"
                                     "      (filter #(= \"tool\" (:role %))) (mapv #(select-keys % [:tool-call-id :content]))))")]))
                           {:config {:seon.config.ai/script 'seon.fixtures/calls-one-refused}})
        [_ r1 r2 :as rs] (:value msgs)]
    (is (= 3 (count (distinct (mapv :tool-call-id rs)))) (str "every call has its result, by its id: " (pr-str msgs)))
    (is (str/includes? (:content r1) "edit-refused") "the second call's error value")
    (is (str/includes? (:content r2) ":value 2") (str "(inc a) ran after the refused edit: " (:content r2)))))

(deftest a-call-is-text-and-its-forms
  ;; owner, 2026-10-29: a call is text and one or more forms, run in order; one with no text or
  ;; no form answers a typed error as its one result and runs nothing (docs/prompt.md)
  (let [problem #'seon.repl/call-problem]
    (is (nil? (problem "Add them." "(+ 1 2)")) "text and one form")
    (is (= :no-intent (:problem (problem " " "(+ 1 2)"))) "no intent")
    (is (nil? (problem "Two." "(def a 1) (inc a)")) "two forms")
    (is (= [:forms 0] ((juxt :problem :forms) (problem "None." "  "))) "no form")
    (is (nil? (problem "Unread." "(+ 1")) "code that does not read is left to the REPL, which says where"))
  (let [[_ msgs] (in-agent (fn [{:keys [task ns]}]
                             (let [run (str ns "/t#forms")]
                               [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                                (str "(let [db (seon.agent/db)]"
                                     " (->> (:parts (seon.ctx.wire/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) 
                                     "                                       :turn 2 :max-turns 1 :origin :model :agent (:key @seon.agent/state)}))"
                                       "      (filter #(= :eval (ffirst %))) (mapcat second)"
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
  ;; turn's draw, out of work! (outcome :no-end). Here the agent breaks Seon's own seon.ctx.wire/render, which a turn
  ;; calls after its draw: the throw is told to the agent and the loop goes on; the second in a row, with no model
  ;; turn between, ends the work :error, and work! answers its end
  (let [[_ end] (in-agent (fn [{:keys [task ns]}]
                            ["(in-ns 'seon.ctx.wire) (clojure.core/defn render [& _] (throw (ex-info \"boom: Seon's own render, changed\" {})))"
                             (str "(seon.turn/work! {:task " (pr-str task) " :max-turns 3})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)]
    (is (= :error (:outcome end)) (pr-str end))
    (is (str/starts-with? (str (:reason end)) "two turns in a row threw") (pr-str end))
    (is (str/includes? (str (:reason end)) "boom: Seon's own render, changed") "naming its cause"))
  ;; a turn that threw after its model call answered was the agent's turn: it read the last error and acted, so
  ;; the count starts again, and the work goes on to its cap (here seon.turn/usage, read after the reply)
  (let [[_ end] (in-agent (fn [{:keys [task ns]}]
                            ["(in-ns 'seon.turn) (clojure.core/defn usage [& _] (throw (ex-info \"boom: after the reply\" {})))"
                             (str "(seon.turn/work! {:task " (pr-str task) " :max-turns 3})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)]
    (is (= :cap (:outcome end)) (str "each turn reached its model, so none ended the work: " (pr-str end))))
  ;; md-scan #27: the agent's change broke the status, every turn drew none, and the agent made 156 model calls past its
  ;; cap of 40: a turn with no status ends at the cap as every turn does
  (let [[_ end] (in-agent (fn [{:keys [task ns]}]
                            ["(in-ns 'seon.ctx.status) (clojure.core/defn status [& _] (throw (ex-info \"boom: the status, broken\" {})))"
                             (str "(seon.turn/work! {:task " (pr-str task) " :max-turns 3})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)]
    (is (= :cap (:outcome end)) (pr-str end))
    (is (<= (:model-calls end) 3) (str "no model call past the cap: " (pr-str end)))))

(deftest a-broken-parser-reaches-the-model
  ;; docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map: two DeepSeek runs of md-scan
  ;; gave seon.ref/scan's emit step a new arity its calls did not pass, so scan threw on every call; the turn's merge check,
  ;; seon.reconcile/hairy, parsed the task's plan with it (seon.ref/names) before every model call, so two turns
  ;; in a row threw and the work ended :error before the model read its own error. Seon's own decision reads the
  ;; plan's cites, the facts its write derived, and the agent's broken parser costs only the parts that draw text:
  ;; the model is called each turn, with the error in what it is sent (the attached task is :whole, so the check runs)
  (let [[_ end msgs] (in-agent (fn [{:keys [task ns]}]
                                      ["(in-ns 'seon.ref) (clojure.core/defn scan [& _] (throw (ex-info \"Wrong number of args (6) passed to: fn\" {})))"
                                       (str "(seon.turn/work! {:task " (pr-str task) " :max-turns 2})")
                                       "(mapv :content (:requests @seon.agent/state))"])
                                    {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)]
    (is (not= :error (:outcome end)) (str "the broken parser never ended the work: " (pr-str end)))
    (is (= :cap (:outcome end)) (pr-str end))
    (is (some #(str/includes? (str %) "Wrong number of args (6)") (:value msgs))
        (str "the model was sent the error: " (pr-str (:value msgs))))))

(deftest an-agent-record-the-branch-holds-for-a-dead-process-becomes-this-processs
  ;; task seon.agent/a-death-names-its-cause's resume path (bin/check resume, calc/square capped at 40): an
  ;; agent killed after it committed its record on its branch but before main recorded the start leaves the
  ;; branch's record of the agent (its key) naming the dead process's id; the restarted root starts the same agent
  ;; (the session's key) in a new process, and begin-run! must make the record this process's, or the agent is
  ;; no session's and every turn draws no status
  (let [[_ _ mine] (in-agent (fn [{:keys [task]}]
                               [(str "(seon.agent/transact! [{:seon.agent/key (:key @seon.agent/state) :seon.agent/task {:seon.task/id " (pr-str task) "}}])")
                                (str "(seon.agent/begin-run! " (pr-str task) ")")
                                "(= (name (:id @seon.agent/state)) (:seon.agent/key (datahike.api/entity (seon.agent/db) [:seon.agent/key (:key @seon.agent/state)])))"]))]
    (is (true? (:value mine)) (str "the record names this process: " (pr-str mine)))))

(deftest a-step-that-throws-answers-its-error-and-the-agent-goes-on
  ;; task seon.agent/a-death-names-its-cause, as the owner ruled 2026-10-30 (task
  ;; seon.agent/the-agent-is-a-flow-graph, step 4): a defect no request's own boundary catches (here
  ;; the agent redefines, at its REPL as an agent changes the platform, a function its REPL proc calls
  ;; after each eval: seon.agent/on-the-wire, which makes the reply) answers that request with the one error
  ;; map, and the agent goes on: it repairs the function at its REPL and works on, the same process
  (let [[_ pid broken repaired after pid'] (in-agent (fn [_]
                                               ["(def p0 (seon.lib/c-getpid))"
                                                "p0"
                                                "(in-ns 'seon.agent) (clojure.core/defn on-the-wire [_] (throw (ex-info \"boom: the loop's own function\" {:x 1})))"
                                                "(in-ns 'seon.agent) (clojure.core/defn on-the-wire [r] (dissoc r :value))"
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
  ;; an agent chooses the tests that judge
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
           store-conn (pdb/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link]
       (try
         (reset! agent/link {:fd a}) (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :key "test/a1" :db @store-conn :base forked})
         (is (empty? (pm/changed-vars (agent/branch-changes (:base @agent/state) (agent/db)))) "the agent has changed nothing")
         (seon.doc/text-index (agent/db))
         (let [r (agent/merge-main! #{})]
           (is (some #{[:var 'app.m0/f1]} (:applied r)) "main's change merges in")
           ;; docs/vector.md#search: an agent's writes carry the text index (seon.doc/carry-index!), so a search after one rebuilds nothing; the merge's
           ;; own record of its base was a write that skipped it, so the next search after any merge rebuilt the index, about 20 MB, seen as a flake
           (is (= [(:hash (agent/db)) (:max-tx (agent/db))] (first @@#'seon.doc/indexes)) "the search's index is at the db the merge left")
           (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity (agent/db) (:t r))))
               "in a merge commit that names its runtime")
           (is (= [ab (:branch cfg)] ((juxt :seon.tx/branch :seon.tx/merged) (d/entity (agent/db) (:t r))))
               "made on the agent's branch, of what it merged from main: a caught-up fact reads as main's (task seon.ctx/a-change-names-its-eval)"))
         (is (empty? (pm/changed-vars (agent/branch-changes (:base @agent/state) (agent/db))))
             "and is main's, not the agent's: its base is the merge base")
         (is (= {} (pm/divergence (agent/branch-changes (:base @agent/state) (agent/db))))
             "so the agent's divergence carries none of main's changes")
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest a-merge-of-a-main-moved-only-by-records-reads-no-content
  ;; docs/storage.md#mains-content-mark: main moves on every request by records (an agent's status, embeddings); when its
  ;; content mark is still the base's, the agent's merge of main and what main changed since the base answer from the mark,
  ;; with no diff (today ~3 M ticks a request). Red before: merge-main! and main-changes diffed each content attribute.
  ;; In this process, on a branch of the test store, with a socketpair standing for root
  (ts/test-branch!
   (fn [cfg conn]
     (let [mark! #(d/transact conn [{:seon.root/id :root :seon.root/content-t (inc (:max-tx @conn))}])
           _ (mark!)
           ab (pdb/on-main (:branch cfg) :agent-base)
           _ (d/branch! conn (:branch cfg) ab)
           forked @conn
           _ (d/transact conn [{:seon.agent/key "test/a1" :seon.agent/status :busy}])
           store-conn (pdb/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link
           calls (fn [f] (profile/begin!) (let [r (f)] [r (into {} (map (juxt :var :calls)) (profile/touched))]))]
       (try
         (reset! agent/link {:fd a}) (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :key "test/a1" :db @store-conn :base forked})
         (let [[r n] (calls #(agent/merge-main! #{}))]
           (is (= [] (:applied r)) "only records moved main: nothing merges")
           (is (nil? (n 'seon.merge/content-datoms)) "and no content is diffed")
           (is (nil? (n 'seon.db/branch-base)) "nor the commit graph walked"))
         (let [[r n] (calls #(agent/main-changes forked (agent/heard-main)))]
           (is (= {} r) "main changed nothing of the program since the base")
           (is (nil? (n 'seon.merge/content-datoms)) "read from the mark"))
         (let [[v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f1] [?v :var/form ?f] [?f :form/ns ?n]] forked)]
           (d/transact conn [{:db/id "f1'" :form/id "app.m0/f1-redefined" :form/ns nsx :form/order 100 :form/defines [v]
                              :form/src "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 100))"}
                             [:db/add v :var/form "f1'"]])
           (mark!))
         (is (some #{[:var 'app.m0/f1]} (:applied (agent/merge-main! #{}))) "a definition and its mark: main's change merges in")
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest a-pull-after-its-own-merge-starts-from-what-it-merged
  ;; seon.agent/a-pull-after-its-own-merge-starts-from-what-it-merged: git's merge base after main merged this branch's commit C is C.
  ;; An agent whose task merged goes on (a chat's next message) and edits f1 again; its next pull of main meets its own first edit as
  ;; main's change. Judged from the main it last pulled, that is f1 changed on both sides, differently: a conflict with itself.
  ;; In this process, on a branch of the test store, with a socketpair standing for root and the real writer merging up.
  (ts/test-branch!
   (fn [cfg conn]
     (let [ab (pdb/on-main (:branch cfg) :agent-own-merge)
           _ (d/branch! conn (:branch cfg) ab)
           forked @conn
           [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f1] [?v :var/form ?f] [?f :form/ns ?n]] forked)
           redefine (fn [id body] (agent/transact! [{:db/id "f" :form/id id :form/ns nsx :form/order 100 :form/defines [v]
                                                    :form/src (str "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] " body ")")}
                                                   [:db/add v :var/form "f"]]))
           src (fn [db] (:form/src (:var/form (d/entity db [:var/name 'app.m0/f1]))))
           store-conn (pdb/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link]
       (try
         (reset! agent/link {:fd a}) (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :key "test/a1" :db @store-conn :base forked :main forked})
         (redefine "app.m0/f1-first" "(+ x 1)")
         (agent/commit!)
         (let [w (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false
                                        :to-writer (clojure.core.async/chan 4) :events (clojure.core.async/chan 4)})
               up (first (:results (:r (first (:to-api (second (#'writer/writer-proc w :in {:op :octopus-merge :branches [ab] :dest {:rid 1}})))))))]
           (is (:merged? up) (str "root merges the agent's branch up: " (pr-str up))))
         (redefine "app.m0/f1-second" "(+ x 2)")
         (agent/heard-main)
         (let [r (agent/merge-main! #{})]
           (is (= [] (:conflicts r)) "its own merged edit is no change of main's")
           (is (= "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))" (src (agent/db))) "and its second edit stands"))
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

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
           store-conn (pdb/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link
           note (fn [i] (agent/transact! [{:note/text (str "n" i) :note/agent :t}]))
           stale #(for [[path t] (ts/stale-dbs (apply dissoc @agent/state %) (:max-tx (agent/db)))] [path t])]
       (try
         (reset! agent/link {:fd a}) (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :key "test/a1" :db @store-conn :base forked :main forked})
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
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest merge-main-brings-the-task-content-main-carried
  ;; live finding F3 (pipeline 1): a session's merge-main! moved its base to main's head but
  ;; brought only the program, so the task content main had carried since the fork (another
  ;; session's design: its plan and registered proof) read at the agent's end as this branch
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
           store-conn (pdb/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link]
       (try
         (reset! agent/link {:fd a}) (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :key "test/a1" :db @store-conn :base forked})
         (agent/merge-main! #{})
         (let [x (task/task (agent/db) "demo/x")]
           (is (= #{'demo-test/x-design-wrote-test} (set (:seon.task/proofs x))) "main's carried proof is on the branch")
           (is (= "Make x. The design: f1 adds 100." (:seon.task/plan x)) "and its plan"))
         (is (= [] (:tx (task/carry-tx (:base @agent/state) (agent/db) @conn)))
             "so the agent's end carries nothing of it back onto main")
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(defn- delivery-world
  "(f {:conn :as :ka :kb :cfg :heads}) in a test store with two agents on parked tasks, each on a branch of its own forked from main
  (demo/w's a1 and demo/x's a2): (as k thunk) runs thunk as agent k, this process's state held for it (its branch's connection, as
  its process holds it), `heads` answers each branch's db. See docs/users.md#messages."
  [f]
  (ts/test-branch!
   (fn [cfg conn]
     (let [_ (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Wait." :seon.task/proofs #{}}
                                                     {:seon.task/ns 'demo :seon.task/name "x" :seon.task/plan "Wait." :seon.task/proofs #{}}]))
           ba (pdb/on-main (:branch cfg) :msg-a) bb (pdb/on-main (:branch cfg) :msg-b)
           ka "a1" kb "a2"
           _ (d/transact conn (into (ts/work-tx "demo/w" :parked {:agent :a1 :branch :msg-a}) (ts/work-tx "demo/x" :parked {:agent :a2 :branch :msg-b})))
           _ (d/branch! conn (:branch cfg) ba)
           _ (d/branch! conn (:branch cfg) bb)
           conns {ka (pdb/connect (assoc cfg :branch ba)) kb (pdb/connect (assoc cfg :branch bb))}
           branches {ka ba kb bb}
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link
           as (fn [k thunk]
                (reset! agent/link {:fd a})
                (reset! agent/state {:store-conn (conns k) :cfg cfg :branch (branches k) :key k :db @(conns k) :base @conn})
                (thunk))]
       (try (f {:conn conn :as as :ka ka :kb kb :cfg cfg :heads (fn [k] @(conns k))})
            (finally (reset! agent/state old) (reset! agent/link old-link) (doseq [c (vals conns)] (d/release c)) (lib/c-close a) (lib/c-close b)))))))

(defn- messages-held
  "The bodies of the messages this agent's @ctx holds, in the order they reached the branch."
  [] (mapv :seon.message/body (sort-by :db/id (vals (:seon.message/_to @seon.ctx/ctx)))))

(defn- remove-held!
  "The agent removes the one message its @ctx holds, as `(swap! ctx update :seon.message/_to dissoc e)` does, and its branch commits."
  [] (swap! seon.ctx/ctx update :seon.message/_to dissoc (first (keys (:seon.message/_to @seon.ctx/ctx)))) (agent/commit!))

(deftest one-message-reaches-two-agents
  ;; docs/users.md#messages: one entity to two agents; each holds it; a removal retracts that agent's own ref only
  (delivery-world
   (fn [{:keys [conn as ka kb heads]}]
     (let [body (str "to both " (random-uuid))
           _ (d/transact conn (task/message-tx "m-both" "owner" [ka kb] body 1000))
           ref-of (fn [db k] (set (map :seon.agent/key (:seon.message/to (d/entity db [:seon.message/id "m-both"])))))]
       (is (= #{ka kb} (ref-of @conn nil)) "main holds the one entity, to both")
       (as ka #(agent/deliver!))
       (as kb #(agent/deliver!))
       (as ka #(is (= [body] (messages-held)) "a holds it"))
       (as kb #(is (= [body] (messages-held)) "b holds it"))
       (is (= #{ka} (ref-of (heads ka) nil)) "a's branch has its own ref only")
       (as ka remove-held!)
       (as ka #(is (= [] (messages-held)) "a removed it"))
       (is (= #{} (ref-of (heads ka) nil)) "a's ref is retracted")
       (as kb #(is (= [body] (messages-held)) "b still holds it"))
       (is (= #{ka kb} (ref-of @conn nil)) "and main still names both")))))

(deftest a-removed-message-is-not-delivered-again
  ;; docs/users.md#messages: the entity stays on the branch, so it is delivered once; the model's request no longer holds it
  (delivery-world
   (fn [{:keys [conn as ka cfg]}]
     (let [body (str "once " (random-uuid))
           _ (d/transact conn (task/message-tx "m-once" "owner" [ka] body 1000))
           says (fn [] (let [db (agent/db) a (pdb/eid db :seon.agent/key ka)]
                         (pr-str (seon.fixtures/tree-messages (:tree (seon.ctx/build (seon.db/index) db a (:branch @agent/state))) db))))]
       (as ka #(do (is (= [{:id "m-once" :from "owner" :body body :sent-ms 1000}] (agent/undelivered)) "main holds it for this agent")
                   (is (= 1 (count (agent/deliver!))))
                   (is (str/includes? (says) body) "the request holds it")
                   (remove-held!)
                   (is (= [] (agent/undelivered)) "its entity is on the branch: nothing to deliver")
                   (is (= [] (agent/deliver!)) "a later turn brings nothing")
                   (is (= [] (messages-held)))
                   (is (not (str/includes? (says) body)) "and the request no longer holds it")))))))

(defn- turn-at!
  "The running agent opens turn n as the loop does: its model call, then its basis at the branch's t as it stands (seon.turn/basis!), committed. See docs/turn.md#the-turn-is-a-number."
  [n]
  (let [k (:key @agent/state)]
    (agent/transact! (ts/turn-tx k n {}))
    (agent/transact! [{:seon.model-call/id (lib/call-id k n) :seon.model-call/basis {:commit (d/commit-id (agent/db)) :t (:max-tx (agent/db)) :task "demo/x" :turn n :max-turns 9 :origin :model}}])
    (agent/commit!)))

(defn- read-at
  "The ids of the messages the running agent's turn n reads, as its record holds them (seon.ctx/turn-members) on its branch's db."
  [n]
  (let [db (agent/db) k (:key @agent/state) c (seon.ctx/agent-ctx (seon.db/index) db (pdb/eid db :seon.agent/key k))]
    (mapv second (:messages (seon.ctx/turn-members db c (seon.ctx/call-of db n k))))))

(deftest a-message-taken-by-one-agent-is-still-owed-to-the-other
  ;; docs/users.md#messages (owner, 2026-11-09: the message on the agent's branch is the proof of delivery): a's taking it writes a's branch alone
  (delivery-world
   (fn [{:keys [conn as ka kb heads]}]
     (d/transact conn (task/message-tx "m-two" "owner" [ka kb] "to both" 1000))
     (is (= ["m-two"] (as ka #(mapv :id (agent/undelivered)))) "owed to a")
     (as ka #(agent/deliver!))
     (is (= [] (as ka #(agent/undelivered))) "a took it")
     (is (= ["m-two"] (as kb #(mapv :id (agent/undelivered)))) "and b is owed it still: a's taking changes nothing for b")
     (is (= [] (d/q '[:find [?id ...] :where [_ :seon.message/id ?id]] (heads kb))) "b's branch holds none"))))

(deftest a-message-synced-from-main-is-delivered-and-read-by-the-next-turn
  ;; docs/users.md#messages: a merge or sync of main brings the message entity onto the branch, and that is its delivery: no longer owed, read once, by the first turn whose basis is at or after it
  (delivery-world
   (fn [{:keys [conn as kb]}]
     (d/transact conn (task/message-tx "m-sync" "owner" [kb] "synced" 1000))
     (as kb #(do (is (= ["m-sync"] (mapv :id (agent/undelivered))) "owed before the sync")
                 (turn-at! 1)
                 ;; what a merge of main writes onto the branch: the entity as main holds it
                 (agent/transact! (task/message-tx "m-sync" "owner" [kb] "synced" 1000))
                 (agent/commit!)
                 (is (= [] (agent/undelivered)) "on the branch: delivered, though no turn marked it")
                 (is (= [] (agent/deliver!)) "so a turn's delivery brings nothing again")
                 (turn-at! 2)
                 (is (= [] (read-at 1)) "the turn before the sync does not read it")
                 (is (= ["m-sync"] (read-at 2)) "the next turn reads it, once")
                 (turn-at! 3)
                 (is (= [] (read-at 3)) "and no turn after")))))) 

(deftest a-private-branch-is-owed-a-message-until-its-own-act-delivers-it
  ;; docs/users.md#messages: nothing merged from main: owed until seon.agent/deliver! writes it on the branch; read by the next turn; one delivered after a turn's basis reads at the turn after
  (delivery-world
   (fn [{:keys [conn as kb]}]
     (d/transact conn (task/message-tx "m-own" "owner" [kb] "own" 1000))
     (as kb #(do (turn-at! 1)
                 (is (= ["m-own"] (mapv :id (agent/undelivered))) "owed: nothing of main's is on the branch")
                 (is (= ["m-own"] (mapv :id (agent/deliver!))))
                 (is (= [] (agent/undelivered)) "delivered once written")
                 (turn-at! 2)
                 (is (= [] (read-at 1)))
                 (is (= ["m-own"] (read-at 2)) "read at the next turn")
                 (testing "delivered mid-turn, after the turn's basis: read at the next turn's start, not the turn in force"
                   (d/transact conn (task/message-tx "m-mid" "owner" [kb] "mid" 2000))
                   (agent/deliver!)
                   (is (= ["m-own"] (read-at 2)) "turn 2 is as it was")
                   (turn-at! 3)
                   (is (= ["m-mid"] (read-at 3)))))))))

(deftest a-program-pull-leaves-messages-alone
  ;; docs/users.md#messages: merge-main! brings the program, never another agent's messages
  (delivery-world
   (fn [{:keys [conn as ka kb heads]}]
     (let [body (str "for b " (random-uuid))
           _ (d/transact conn (task/message-tx "m-b" "owner" [kb] body 1000))
           [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f1] [?v :var/form ?f] [?f :form/ns ?n]] @conn)
           _ (d/transact conn [{:db/id "f1'" :form/id "app.m0/f1-moved" :form/ns nsx :form/order 100 :form/defines [v]
                                :form/src "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 100))"}
                               [:db/add v :var/form "f1'"]])
           on-branch (fn [k] (d/q '[:find [?id ...] :where [_ :seon.message/id ?id]] (heads k)))]
       (as ka #(is (true? (:ok (agent/merge-main! #{}))) "the pull applied main's program change"))
       (is (= [] (on-branch ka)) "and no message of b's came with it")
       (is (= [] (on-branch kb)) "b's branch holds none until b's own delivery")
       (as ka #(is (= [] (agent/deliver!)) "a is sent none"))
       (as kb #(do (agent/deliver!) (is (= [body] (messages-held))) (is (= ["m-b"] (on-branch kb)))))))))

(deftest a-message-through-removal-merge-and-restart
  ;; docs/users.md#messages, context's break test, on the branches of a store (a restart is a process holding the branch anew:
  ;; a root restarting is not set up here): a message arrives after a turn's reply and evals, lands in the next turn after them,
  ;; leaves the request's earlier bytes as they were, is removed, the branch merges up, and the agent held anew is not sent it again
  (delivery-world
   (fn [{:keys [conn as ka kb cfg heads]}]
     (let [body (str "late " (random-uuid))
           says (fn [] (let [db (agent/db) a (pdb/eid db :seon.agent/key ka)]
                         (seon.fixtures/tree-messages (:tree (seon.ctx/build (seon.db/index) db a (:branch @agent/state))) db)))
           texts (fn [ms] (mapv #(pr-str (select-keys % [:role :content :tool-calls])) ms))]
       (as ka #(agent/transact! (into (ts/turn-tx ka 1 {:seon.model-call/reply "Looking."})
                                      [{:seon.eval/id "e1" :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key ka} :seon.eval/code "(+ 1 2)" :seon.eval/call (task/call-ref ka 1)
                                        :seon.eval/response {:handle 'result/e1 :value 3}}])))
       (let [before (as ka #(texts (says)))
             _ (d/transact conn (task/message-tx "m-late" "owner" [ka kb] body 1000))
             _ (as ka #(agent/deliver!))
             after (as ka #(texts (says)))
             at (fn [ms] (first (keep-indexed (fn [i m] (when (str/includes? m body) i)) ms)))]
         (is (some? (at after)) "the message is in the request")
         (is (= before (subvec after 0 (count before))) "every earlier byte of the request is as it was")
         (is (< (dec (count before)) (at after)) "and it comes after the turn's reply and evals")
         (as ka remove-held!)
         (let [base (pdb/branch-base conn (:branch cfg) (pdb/on-main (:branch cfg) :msg-a)) head (heads ka) main @conn
               r (pm/delta-three-way base head main #{})]
           (is (empty? (:ours r)) "merged up, the branch has no change of its own from the message")
           (d/merge-db conn #{(pdb/on-main (:branch cfg) :msg-a)} (pm/merge-tx main base head (keys (:ours r)) #{}) (pdb/tx-meta (:branch cfg))))
         (as ka #(do (is (= [] (agent/undelivered)) "after the merge, held anew: not delivered again")
                     (is (= [] (agent/deliver!)))
                     (is (= [] (messages-held)))))
         (as kb #(do (is (= 1 (count (agent/undelivered))) "another recipient is still owed it")
                     (agent/deliver!)
                     (is (= [body] (messages-held)))))
         (is (= #{ka kb} (set (map :seon.agent/key (:seon.message/to (d/entity @conn [:seon.message/id "m-late"]))))) "main still names both"))))))

(deftest an-opened-eval-on-main-reaches-the-agents-ctx-at-its-next-take
  ;; owner, 2026-10-26: take! is the one way outside facts reach the agent's branch, and so its
  ;; ctx. A reader's opened eval is the agent's :seon.agent/opened on main; the copy take! transacts
  ;; (untaken-facts) puts it on the branch, and @ctx, which follows the branch, holds it; a second copy
  ;; has nothing to add. (The task's outcome and reason on main were copied too while a run held them; the
  ;; task holds them now, and the agent reads them through its task, not a copy: seon.agent/untaken-facts.)
  (ts/test-branch!
   (fn [cfg conn]
     (let [_ (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "w" :seon.task/plan "Wait." :seon.task/proofs #{}}]))
           ab (pdb/on-main (:branch cfg) :agent-taken)
           k "a1"
           ;; as in production: the branch is made, the agent starts on main, and the branch holds
           ;; the agent as its turns record it (its key, id and task)
           _ (d/branch! conn (:branch cfg) ab)
           _ (d/transact conn (ts/work-tx "demo/w" :parked {:agent :a1 :branch :agent-taken}))
           store-conn (pdb/connect (assoc cfg :branch ab))
           _ (d/transact store-conn [{:db/id k :seon.agent/key k}
                                     {:seon.eval/id "e1" :seon.eval/agent [:seon.agent/key k] :seon.eval/by "a1" :seon.eval/code "1" :seon.eval/response {}}])
           ;; on main: the page's open
           _ (d/transact conn [{:seon.agent/key k :seon.agent/opened #{"e1" "e9"}}])
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link
           ;; a collection's member in the agent's ctx, found by its id
           member (fn [coll attr id] (some #(when (= id (attr %)) %) (vals (get @seon.ctx/ctx coll))))]
       (try
         (reset! agent/link {:fd a}) (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :key k :db @store-conn})
         (is (nil? (:seon.eval/opened (member :seon.eval/_agent :seon.eval/id "e1"))) "before the take, nothing of main's")
         (is (= [{:seon.eval/id "e1" :seon.eval/opened true}]
                (agent/untaken-facts))
             "what main holds of its evals that the branch does not; e9 is no eval of its branch")
         (is (= [] (agent/deliver!)) "the delivery a turn makes (seon.turn/deliver!): no messages here, the facts written")
         (is (true? (:seon.eval/opened (member :seon.eval/_agent :seon.eval/id "e1"))) "the opened eval")
         (is (= [] (agent/untaken-facts)) "delivered: nothing more to copy")
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest a-stall-task-closes-when-its-conditions-hold
  ;; owner, 2026-10-26: root's stall task (seon.template/stall) is done when its boundary
  ;; conditions hold, which are its proofs (seon.stall-proofs): red while the agent is stalled and
  ;; no cause is recorded; green once main retracts the stall and the worker records the cause
  ;; with its evidence and decides it is no bug, through my.task/update!
  (ts/test-branch!
   (fn [cfg conn]
     (let [k "a1"
           stall (tpl/stall :a1 "demo/t" {:quiet-ms 4000 :pid 77})
           _ (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan "Work." :seon.task/proofs #{}} stall]))
           _ (d/transact conn (ts/work-tx "demo/t" :running {:agent :a1 :branch :task-demo-t}))
           _ (d/transact conn [{:seon.agent/key k} {:seon.process/id (str k "/77") :seon.process/agent [:seon.agent/key k] :seon.process/stall {:quiet-ms 4000}}])
           ab (pdb/on-main (:branch cfg) :stall-task)
           _ (d/branch! conn (:branch cfg) ab)
           store-conn (pdb/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link
           proofs (fn [] (let [reps (atom [])]
                           (binding [clojure.test/report (fn [m] (swap! reps conj (:type m)))]
                             (doseq [v (sort tpl/stall-proofs)] ((:test (meta (resolve v))))))
                           (frequencies @reps)))]
       (try
         (reset! agent/link {:fd a}) (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :task "root/stall-a1-demo-t" :db @store-conn})
         (is (pos? (:fail (proofs) 0)) "red: the agent is stalled, no cause is recorded")
         (d/transact conn [[:db.fn/retractAttribute [:seon.process/id (str k "/77")] :seon.process/stall]])
         (my.task/update! :seon.task/cause merge {:what "a test child parked on a pipe nobody wrote" :evidence {:sample "..." :lsof "..."} :bug? false})
         (agent/commit!)
         (is (= {:pass 5} (proofs)) "green: the stall ended, the cause recorded with its evidence, and it is no bug")
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

(deftest an-agents-transactions-name-their-runtime
  ;; what an agent writes on its branch
  ;; (seon.agent/transact!, the run-ahead writer) carries the runtime that made it
  (ts/test-branch!
   (fn [cfg conn]
     (let [ab (pdb/on-main (:branch cfg) :agent-tx)
           _ (d/branch! conn (:branch cfg) ab)
           store-conn (pdb/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link]
       (try
         (reset! agent/link {:fd a}) (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :db @store-conn})
         (let [{:keys [t]} (agent/transact! [{:note/text "the agent's" :note/agent :t}])]
           (agent/commit!)
           (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity @store-conn t))))
           (is (nil? (:seon.tx/eval (d/entity @store-conn t))) "written outside any eval: it names none"))
         ;; task seon.ctx/a-change-names-its-eval: a write an eval's code makes names that eval
         (let [r (repl/eval-string "(assoc (seon.agent/transact! [{:note/text \"an eval's\" :note/agent :t}]) :running (:eval @seon.agent/state))" {"ns" "user" :origin :outside})
               t (:t (:value r))]
           (agent/commit!)
           (is (= ab (:seon.tx/branch (d/entity @store-conn t))) (str "the eval that wrote it: " (:ex r)))
           (is (some? (:running (:value r))))
           (is (= (:running (:value r)) (:seon.tx/eval (d/entity @store-conn t))) "the eval that wrote it, by its id")
           (is (nil? (:eval @agent/state)) "and once the eval ends, a write names no eval"))
         (let [r (repl/eval-string "(throw (ex-info \"boom\" {}))" {"ns" "user" :origin :outside})]
           (is (:ex r) "an eval that throws")
           (is (nil? (:eval @agent/state)) "leaves no eval named"))
         (let [r (repl/eval-string "(do (seon.repl/eval-string \"1\" {\"ns\" \"user\" :origin :screen}) (:eval @seon.agent/state))" {"ns" "user" :origin :outside})]
           (is (re-matches #"[a-p][0-9a-f]{15}" (:value r)) "an eval inside one names its own, then the outer one's again"))
         ;; recorded as an agent records: the eval's own record names it too
         (swap! agent/state assoc :mode :agent :key "test/a1" :id :a1)
         (let [r (repl/eval-string "(+ 1 2)" {"ns" "user" :origin :outside})]
           (agent/commit!)
           (is (re-matches #"[a-p][0-9a-f]{15}" (:record r)) "recorded, by its id")
           (is (= (:record r) (:seon.tx/eval (d/entity @store-conn (:tx (first (d/datoms @store-conn :avet :seon.eval/id (:record r)))))))
               "its record's transaction names it"))
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))


(def ^:dynamic *peek*
  "The test's hook for a form that looks at its own agent while it runs (`an-evals-output-streams-as-it-is-written`)."
  nil)

(defn peek!
  "Call the hook the test bound: what the agent's socket holds while the form that called this still runs."
  []
  (*peek*))

(deftest an-evals-output-streams-as-it-is-written
  ;; task seon.fx/an-effect-streams-what-it-reads, docs/render.md#in-flight-output-streams: a model's eval sends its *out* and *err* to
  ;; root as they are written, in the parts "<call>/<k>/eval-<i>/out" and "/err" of its model call, each ended once however the eval
  ;; ends; the record keeps the text whole, as it did. An eval of no model call sends nothing, and only the REPL's own thread sends.
  ;; In process: a socketpair to read the frames root would get.
  (let [[a b] (sock/socketpair)
        rd (sock/conn b)
        old @agent/state old-link @agent/link
        frames (fn [] (loop [acc []] (let [m (sock/next-frame! rd)] (if (#{:none :eof} m) acc (recur (conj acc m))))))
        streamed (fn [fs part] (filterv #(and (= :stream (:op %)) (= part (:part %))) fs))
        brief (fn [fs] (mapv #(select-keys % [:text :end]) fs))
        run (fn [code req hook] (binding [*peek* hook] (repl/eval-string code (merge {"ns" "user" :origin :model} req))))
        call {:stream {:call "k/call-3" :k 0 :i 1}}]
    (try
      (reset! agent/link {:fd a})
      (reset! agent/state {:key "test/a1" :id :a1 :n 0})
      (let [mid (volatile! nil)
            r (run "(do (print \"one \") (print \"two\") (seon.agent-test/peek!) (println \"three\") :v)" call #(vreset! mid (frames)))
            fs (into @mid (frames))
            outs (streamed fs "out")
            texts (remove :end outs)
            joined (apply str (map :text texts))]
        (is (= ["one " "two"] (map :text (streamed @mid "out"))) "the first writes were out while the form still ran")
        (is (= (:seon.print/code (:out r)) joined) "the streamed text joined is the record's :out")
        (is (= "one twothree\n" joined))
        (is (= [1 true] [(count (filter :end outs)) (boolean (:end (last outs)))]) "one end, last")
        (is (every? #(and (= "k/call-3/0/eval-1/out" (:stream %)) (= "k/call-3" (:in %)) (= "test/a1" (:agent %))) outs) "each names the call's part, the call and the agent's key"))
      ;; an eval that throws ends its streams once, and the text before the error is streamed
      (let [r (run "(do (print \"before\") (throw (ex-info \"boom\" {})))" call nil)
            outs (streamed (frames) "out")]
        (is (:ex r))
        (is (= "before" (apply str (map :text (remove :end outs)))))
        (is (= [true 1] [(boolean (:end (last outs))) (count (filter :end outs))])))
      ;; *err* is its own part, ended once; out with nothing written still ends
      (let [r (run "(do (binding [*out* *err*] (print \"e\")) 1)" call nil)
            fs (frames)]
        (is (= 1 (:value r)))
        (is (= [{:text "e"} {:end true}] (brief (streamed fs "err"))))
        (is (= "k/call-3/0/eval-1/err" (:stream (first (streamed fs "err")))))
        (is (= [{:end true}] (brief (streamed fs "out")))))
      ;; an eval of no model call sends nothing, and its record is what it was
      (let [r (run "(do (print \"quiet\") 2)" {:origin :outside} nil)]
        (is (= "quiet" (:seon.print/code (:out r))))
        (is (empty? (filter #(= :stream (:op %)) (frames)))))
      ;; every one-argument write a Writer takes: a char[], a char and an int each reach the record and the stream as their text
      (let [r (run "(do (.write *out* (char-array \"ab\")) (.write *out* (int \\c)) (print \\d) 4)" call nil)
            outs (streamed (frames) "out")]
        (is (= 4 (:value r)) "a char[] write does not throw")
        (is (= "abcd" (:seon.print/code (:out r))))
        (is (= "abcd" (apply str (map :text (remove :end outs))))))
      ;; a write from another OS thread is recorded and never sent (frames can interleave across threads)
      (let [r (run "(do (print \"a\") @(future (print \"b\")) (print \"c\") 3)" call nil)
            outs (streamed (frames) "out")]
        (is (= "abc" (:seon.print/code (:out r))) "the record holds all of it")
        (is (= "ac" (apply str (map :text (remove :end outs)))) "the stream only what the REPL's thread wrote")
        (is (= ["a" "c"] (map :text (remove :end outs))) "each frame is one write's piece"))
      (finally (reset! agent/state old) (reset! agent/link old-link) (lib/c-close a) (lib/c-close b)))))

(deftest an-agents-stream-frame-goes-to-the-web-procs-streams
  ;; task seon.fx/an-effect-streams-what-it-reads: root's agent proc appends each piece an eval's output sends to that part's whole
  ;; text so far and puts the whole on the web proc's :streams in-port (Flow's dynamic route), so a dropped message there loses
  ;; nothing; at the part's :end it lets the text go
  (let [m {:stream "e1/out" :in "e1" :part "out" :agent "test/a1"}
        step (fn [st frame] (let [o (volatile! {})] [(#'seon.agent-proc/agent-frame st o (assoc frame :op :stream)) (get @o [:web :streams])]))
        [st1 out1] (step {:id :a1} (assoc m :text "x"))
        [st2 out2] (step st1 (assoc m :text "y"))
        [st3 out3] (step st2 (assoc m :end true))]
    (is (= [(assoc m :text ["x"])] out1) "the first piece is the whole so far")
    (is (= [(assoc m :text ["x" "y"])] out2) "each message holds every piece before it")
    (is (= [(assoc m :end true)] out3) "the end goes as it came")
    (is (= ["x" "y"] (get-in st2 [:seon.agent-proc/streams "e1/out"])) "the proc holds the part's text while it runs")
    (is (empty? (:seon.agent-proc/streams st3)) "and lets it go at the end")))

(deftest an-eval-run-again-replaces-itself
  ;; docs/turn.md#an-eval-can-be-run-again: through a real agent's REPL, the same record under its own id, its author and call kept,
  ;; its old code in history, and a run down to an eval goes in order and stops at the first that fails
  (let [probe "(fn [id] (let [db (seon.agent/db) e (seon.db/eid db :seon.eval/id id) p (datahike.api/pull db '[:seon.eval/code :seon.eval/by {:seon.eval/call [:seon.model-call/id]}] e)] (assoc p :e e :old (vec (distinct (map :v (filter #(= :seon.eval/code (:a %)) (datahike.api/datoms (datahike.api/history db) :eavt e))))))))"
        [_ a1 a2 a3 _ again-r edited missing to-all to-part probe-r]
        (in-agent ["(def order (atom []))"
                   "(swap! order conj :a)"
                   "(throw (ex-info \"stop\" {}))"
                   "(swap! order conj :never)"
                   (str "(do (def id-of (fn [code] (:seon.eval/id (first (filter #(= code (:seon.eval/code %)) (vals (:seon.eval/_agent @seon.ctx/ctx))))))) (def probe " probe ") (def before (probe (id-of \"(swap! order conj :a)\"))) :ok)")
                   "(seon.repl/again (id-of \"(swap! order conj :a)\"))"
                   "(seon.repl/again (id-of \"(swap! order conj :a)\") \"(swap! order conj :edited)\")"
                   "(seon.repl/again \"no-such-eval\")"
                   "(do (def all (seon.repl/again-to (id-of \"(swap! order conj :never)\"))) {:all all :order @order})"
                   "(do (reset! order []) (def part (seon.repl/again-to (id-of \"(throw (ex-info \\\"stop\\\" {}))\"))) {:part part :order @order})"
                   "(let [id (id-of \"(swap! order conj :edited)\")] (assoc (probe id) :id id :before before))"])]
    (is (= :a (peek (:value a1))) "it ran first as itself")
    (is (:ex a2) "the third eval threw") (is (some? a3))
    (is (= [:a :never :a] (:value (:value again-r))) "run again, its code really ran")
    (is (= [:a :never :a :edited] (:value (:value edited))) "edited, the new code ran")
    (is (= :seon.repl/no-eval (:seon.error/type (:value missing))) "an eval it doesn't hold says so")
    (is (true? (:failed? (peek (:all (:value to-all))))) "a run down to an eval stops at the first that fails")
    (is (= [:edited] (:order (:value to-all))) "the edited step ran from the start, and nothing past the failure")
    (is (= (dec (count (:part (:value to-part)))) (count (remove :failed? (:part (:value to-part))))) "only the last failed")
    (let [{:keys [before id e old] :as p} (:value probe-r)]
      (is (= id (:record (:value edited))) "its record is its own")
      (is (= (:e before) e) "the same record")
      (is (= "(swap! order conj :edited)" (:seon.eval/code p)) "its code is the new one")
      (is (= (:seon.eval/by before) (:seon.eval/by p)) "its author kept")
      (is (= (:seon.eval/call before) (:seon.eval/call p)) "its place in the conversation kept")
      (is (some #{"(swap! order conj :a)"} old) "history holds what it was"))))

(deftest an-agent-resets-to-a-point-and-goes-on
  ;; docs/turn.md#an-agent-goes-back-to-an-eval: one call, a new branch from the eval's commit, the old branch as it was, the same key on both, and the agent goes on
  (let [back (fn [{:keys [agent task]}]
               (str "(let [conn (:conn sys) a (keyword " (pr-str agent) ") task " (pr-str task)
                    "      b (:branch (get-in (seon.server/agents-answer sys) [:agents a]))"
                                        "      codes (fn [db] (set (datahike.api/q '[:find [?c ...] :where [_ :seon.eval/code ?c]] db)))"
                    "      id (datahike.api/q '[:find ?i . :where [?e :seon.eval/code \"(def here 2)\"] [?e :seon.eval/id ?i]] (seon.db/head conn b))"
                    "      missing (seon.root/back! sys a {:eval \"no-such-eval\"})"
                                        "      _ (clojure.core/deref (seon.root/submit! sys a (str \"(seon.agent/transact! [{:seon.eval/id \" (pr-str id) \" :seon.eval/opened true}])\")))"
                    "      before (codes (seon.db/head conn b))"
                    "      two [(#'seon.root/request! sys 'back! a {:eval id}) (#'seon.root/request! sys 'back! a {:eval id})]"
                    "      second-call (clojure.core/deref (second two))"
                    "      new (:id (clojure.core/deref (first two)))"
                    "      nb (:branch (get-in (seon.server/agents-answer sys) [:agents new]))"
                    "      went-on (clojure.core/deref (seon.root/submit! sys new \"(do (def there 3) (:key (clojure.core/deref seon.agent/state)))\"))]"
                    "  {:missing (:seon.error/message missing) :second (:seon.error/message second-call) :was (name a) :new new :old-branch b :new-branch nb"
                    "   :old-kept (= before (codes (seon.db/head conn b))) :at (codes (seon.db/head conn nb))"
                    "   :task (let [on-main (seon.root/await-db sys #(= (seon.db/off-main (seon.db/conn-main conn) nb) (:seon.task/branch (datahike.api/entity % [:seon.task/id task])))) t (datahike.api/entity on-main [:seon.task/id task])] {:outcome (:seon.task/outcome t) :agent-branch (:seon.agent/branch (:seon.task/agent t)) :task-branch (:seon.task/branch t)}) :went-on (:value went-on) :this-boots (let [k (:value went-on) on-main (seon.root/await-db sys #(= new (seon.task/this-boots % (datahike.api/entity % [:seon.agent/key k]))))] (and (:max-tx on-main) (contains? (seon.task/agents on-main) k) (some? (seon.server/agents-answer sys))))})"))
        r (peek (in-agent (fn [names]
                            ["(def here 1)" "(def here 2)" "(def here 3)"
                             {:op :eval :agent "root" :code (back names)}])))
        v (:value r)]
    (is (map? v) (pr-str r))
    (is (re-find #"no eval" (str (:missing v))) "an eval it doesn't hold says so")
    (is (re-find #"already going back" (str (:second v))) "a second press is refused, and both callers are answered")
    (is (not= (:old-branch v) (:new-branch v)) "a new branch")
    (is (:old-kept v) "the old branch as it was")
    (is (contains? (:at v) "(def here 2)") "the new branch holds the point")
    (is (not (contains? (:at v) "(def here 3)")) "and nothing after it, a reader's opening of the eval no later point")
    (is (:this-boots v) "the session's agent is this boot's, so its page knows it is running")
    (is (= :parked (:outcome (:task v))) "its task moved to the new branch, parked on the new process, which a message wakes")
    (is (= (:task-branch (:task v)) (:agent-branch (:task v))) "and its agent on main with it: the reconciler compares the two")
    (is (= (:was v) (name (:new v))) "the agent goes back under its own id: the old process is stopped, then the new one forked")
    (is (= (:was v) (:went-on v)) "under the session's key, and it goes on")))

(deftest a-sentence-on-roots-page-is-a-task-an-agent-works
  ;; docs/tasks.md: the box on root's page asks for work; it no longer starts an agent with no task
  (let [r (peek (in-agent ["(+ 1 1)"
                           {:op :eval :agent "root"
                            :code (str "(let [blank (clojure.core/deref (seon.root/ask! sys {:task \"  \"}))"
                                       "      a (clojure.core/deref (seon.root/ask! sys {:task \"Make the Dashboard greet people!\"}))"
                                       "      m (clojure.core/deref (:conn sys))"
                                       "      ids (datahike.api/q '[:find [?i ...] :where [?e :seon.task/plan \"Make the Dashboard greet people!\"] [?e :seon.task/id ?i]] m)"
                                       "      t (datahike.api/entity m [:seon.task/id (first ids)])]"
                                       "  {:blank (:seon.error/message blank) :ok (some? (:task a)) :answered (:task a) :ids (vec ids) :kind (:seon.task/kind t) :execution (:seon.task/execution t) :branch (:seon.task/branch t)})")}]))
        v (:value r)]
    (is (map? v) (pr-str r))
    (is (re-find #"say what" (str (:blank v))) "an empty box asks nothing")
    (is (:ok v))
    (is (= 1 (count (:ids v))) "one task")
    (is (re-matches #"request/make-the-dashboard-greet-people(-\d+)?" (first (:ids v))) "named by its words")
    (is (= :user (:kind v)) "an agent that works for the person, not a build")
    (is (= :running (:execution v)) "released, so the reconciler gives it an agent")
    (is (some? (:branch v)))))

(deftest an-agent-launches-an-agent-on-a-task-of-its-own
  ;; docs/agent.md#working-for-a-person: root makes the task and answers its id; it reaches its parent by a message
  (let [[a b] (in-agent ["(my.task/launch! \"Build the thing that greets people.\")"
                         "(my.task/launch! \"No such template.\" :nope)"])
        id (:task (:value a))
        probe (peek (in-agent [{:op :eval :agent "root"
                                :code (str "(let [t (datahike.api/entity (clojure.core/deref (:conn sys)) [:seon.task/id " (pr-str id) "])] [(:seon.task/kind t) (:seon.task/execution t) (:seon.task/plan t) (:seon.agent/key (:seon.task/started-by t))])")}]))
        [kind execution plan started-by] (:value probe)]
    (is (string? id) (pr-str a))
    (is (= :whole kind) "by default the template that builds, proves and merges")
    (is (= :running execution) "released: the reconciler gives it an agent")
    (is (str/includes? plan "Build the thing") "its plan what was asked")
    (is (string? started-by) "and who to tell how it ends")
    (is (re-find #"no template" (str (:seon.error/message (:value b)))) "a template that isn't one says so")))

(def ^:private not-running
  "Root-REPL code: a function true of the outcomes a task's work stands at once its agent's end is judged and it waits on nothing of
  root's (not nil, :running, :ready or :gate: a merge follows those)."
  "(fn [o] (contains? #{:parked :cap :told :behind :done :merged :dead :lost :closed :refused} o))")

(defn- settled-code
  "Root-REPL code that waits on root for task w's agent to stand its task at outcome n times (the task's outcome written o n times, by
  its history), or for the task to stand at another outcome; answers the task as pulled, with its agent's key and id."
  {:malli/schema [:=> [:cat :seon.task/id :seon.task/outcome pos-int?] :string]}
  [w o n]
  (str "(seon.root/await-db sys \"the agent stood at " o ", or its task stood otherwise\""
       " #(let [t (datahike.api/pull % '[:seon.task/outcome {:seon.task/agent [:seon.agent/key]}] [:seon.task/id " (pr-str w) "])"
       "       n (or (datahike.api/q '[:find (count ?tx) . :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/outcome " o " ?tx true]] (datahike.api/history %) " (pr-str w) ") 0)]"
       "    (or (and (= " o " (:seon.task/outcome t)) (>= n " n "))"
       "        (not (contains? #{nil :running " o "} (:seon.task/outcome t))))))"))

(defn- parked-code
  "Root-REPL code that waits on root for task w's agent to park its session n times (`settled-code` at :parked)."
  {:malli/schema [:=> [:cat :seon.task/id pos-int?] :string]}
  [w n] (settled-code w :parked n))

(defn- agent-code
  "Root-REPL code answering task w's agent on main: {:seon.agent/key}."
  {:malli/schema [:=> [:cat :seon.task/id] :string]}
  [w]
  (str "(:seon.task/agent (datahike.api/pull @(:conn sys) '[{:seon.task/agent [:seon.agent/key]}] [:seon.task/id " (pr-str w) "]))"))

(def ^:private branch-of-code
  "Root-REPL function source: the db of task w's branch."
  "(fn [w] (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch w)}})))")

(defn- send-to-parked-code
  "Root-REPL code for the send tests: task w's agent B parks (it waits for a message), agent a (the harness's) sends B a body made here
  (seon.agent/send-message, evaluated at a), B's process ends (before-send? false: after the send; true: before it), and the writer is
  asked for main's db (its queue holds the send's write before this ask, so the send is in the db it answers). Answers
  {:body :b :bkey :found :sender}: the body, B's id, B's key, and the [recipient key, from] of every message on main with that body."
  {:malli/schema [:=> [:cat :string :string :boolean] :string]}
  [w a before-send?]
  (str "(let [bid (keyword (:seon.agent/key " (agent-code w) "))"
       "      bkey " (str "(:seon.agent/key " (agent-code w) ")")
       "      body (str \"msg-\" (random-uuid))"
       "      end! (fn [] (c/kill! sys bid) (loop [] (let [e (clojure.core.async/<!! ready-ev)] (if (and (#{:ended :dead :killed} (:type e)) (= bid (:agent e))) :gone (recur)))))"
       "      send! (fn [] @(c/submit! sys :" a " (str \"(my.agent/send-message [\" (pr-str bid) \"] \" (pr-str body) \")\") {:origin :outside}))]"
       "  (if " before-send? " (do (end!) (send!)) (do (send!) (end!)))"
       "  {:body body :b bid :bkey bkey :sender " (pr-str a)
       "   :found (datahike.api/q '[:find ?k ?from :in $ ?body :where [?m :seon.message/body ?body] [?m :seon.message/to ?a] [?a :seon.agent/key ?k] [?m :seon.message/from ?from]]"
       "                          (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch :db}})) body)}"
       ")"))

(defn- send-test-forms
  "in-agent's forms for the send tests: the await namespace, B on task w parked, then send-to-parked-code."
  {:malli/schema [:=> [:cat :map :boolean] [:vector :map]]}
  [{:keys [ns agent]} before-send?]
  (let [w (str ns "/w")]
    [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
     {:op :eval :agent "root" :code (str "(do (my.root/run! sys [" (pr-str w) "]) " (parked-code w 1) " " (send-to-parked-code w agent before-send?) ")")}]))

(def ^:private waiting-b
  "in-agent's options for the send tests: a task w whose agent waits for a message (the scripted model of fixtures/wait-call)."
  {:config {:seon.config.ai/script 'seon.fixtures/wait-call}
   :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})

(deftest a-send-survives-its-receiver-dying
  ;; docs/users.md#messages: an agent's send is one entity on main, to the recipient's agent, so it is there however the recipient ends
  (let [[_ r] (in-agent #(send-test-forms % false) waiting-b)
        {:keys [body bkey found sender]} (:value r)]
    (is (string? body) (pr-str r))
    (is (= #{[bkey sender]} found) "the one message entity on main, to B, from A")))

(deftest a-send-to-an-agent-with-no-process-waits-on-main
  ;; docs/users.md#messages: the recipient has no process (it ended), yet its entity is on main with its branch, so the send is the same fact
  (let [[_ r] (in-agent #(send-test-forms % true) waiting-b)
        {:keys [body bkey found sender]} (:value r)]
    (is (string? body) (pr-str r))
    (is (= #{[bkey sender]} found) "the send is not refused: the entity is on main, to B, from A")))

(deftest a-reply-is-recorded-before-its-evals-run
  ;; deepseek-run6: records' call 28 read as a model call nobody answered, and its cost as none: the model had answered, and an eval of that
  ;; reply ran a test that deref'd a promise nobody delivers, at its own REPL. The record is written before the evals, so it says what happened
  (let [[_ r] (in-agent (fn [{:keys [ns]}]
                          (let [w (str ns "/w")]
                            [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                             {:op :eval :agent "root"
                              :code (str "(do (my.root/run! sys [" (pr-str w) "]) " (parked-code w 1)
                                         " (:reason (first (filter #(= " (pr-str w) " (:task %)) (get-in (my.root/problems sys) [:classes :failed :rows])))))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/reads-its-own-reply}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})]
    (is (= "read: The reply itself." (:value r)) (pr-str r))))

(deftest a-root-with-no-provider-ends-the-work-on-its-typed-error
  ;; root's ruling 2026-10-22: a root with no model provider (no --profile) answers the
  ;; first turn with :seon.model/no-provider; the error reaches whoever can act on it: the
  ;; task's record on main and my.root/problems' :error rows
  (let [q #(str "[:find (pull ?e [:seon.task/outcome :seon.task/error]) . :where [?e :seon.task/id \"" % "/m\"] [?e :seon.task/error]]")
        [_ r] (in-agent (fn [{:keys [ns]}]
                          [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root"
                           :code (str "(do (my.root/run! sys [\"" ns "/m\"])"
                                      " (let [a (datahike.api/q '" (q ns) " (seon.root/await-db sys #(datahike.api/q '" (q ns) " %)))]"
                                      " [(:seon.task/outcome a) (:seon.error/type (:seon.task/error a))"
                                      " (mapv #(get-in % [:error :seon.error/type]) (get-in (my.root/problems sys) [:classes :error :rows]))]))")}])
                        {:config {:seon.config.ai/provider :none}
                         :decls [{:seon.task/name "m" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})]
    (is (= [:closed :seon.model/no-provider [:seon.model/no-provider]] (:value r)) (pr-str r))))

(defn- answers-gate
  "Agent code that answers this agent's gate at its seam (seon.agent/ask-gate), each ask counted in user/gate-asks, with what user/gate-answer holds: a real
  gate is a worktree, a build and a run, and the claim here is what the agent does with its answer."
  {:malli/schema [:=> [:cat :qualified-symbol] :string]}
  [t]
  (str "(do (in-ns 'user) (def gate-asks (atom 0)) (def gate-answer (atom :fail))"
       " (alter-var-root #'seon.agent/ask-gate (constantly (fn [tests _] (swap! gate-asks inc)"
       "   {:value {:results (zipmap tests (repeat @gate-answer)) :digests (zipmap tests (repeat \"gate\"))"
       "            :failures (if (= :fail @gate-answer) {'" t " [{:type :fail :message \"the gate's red\"}]} {})}})))"
       " :ok)"))

(defn- process-proof
  "Agent code writing test var seon.repl-test/name, a process test (it reaches an agent), asserting (+ 1 1) is n."
  {:malli/schema [:=> [:cat :string :int] :string]}
  [nm n]
  (str "(do (in-ns 'seon.repl-test) (clojure.test/deftest " nm
       " (let [[r] (seon.agent-test/in-agent (fn [_] [\"(+ 1 1)\"]))] (clojure.test/is (= " n " (:value r))))) (in-ns 'user) :ok)"))

(deftest a-process-test-gets-its-verdict-while-the-agent-can-act
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches. The shape of seon.repl/a-docstring-redefinition-is-a-change's run (2026-11-06): the agent wrote
  ;; its proof as a process test and never ran it; deferred read as green, the run ended, and the judge found it red at the
  ;; merge with no turn left. Now its prove takes the gate's answer and the red is the agent's, on its status (the gate answered at its seam)
  (let [t 'seon.repl-test/a-process-proof-of-the-gate
        prove "(let [r (my.agent/prove)] {:proofs (:proofs r) :deferred (:deferred r) :ran (:ran r) :line (:seon.print/code (my.agent/prove-summary {} r))})"
        reasons "(mapv :reason (:reasons (seon.ctx.status/status (seon.agent/db) @seon.ctx/ctx {})))"
        [_ _ red red-reasons _ _ green green-reasons again asks]
        (in-agent (fn [_] [(answers-gate t) (process-proof "a-process-proof-of-the-gate" 3) prove reasons
                           "(reset! user/gate-answer :pass)" (process-proof "a-process-proof-of-the-gate" 2) prove reasons prove "@user/gate-asks"])
                  {:proofs #{t}})]
    (is (= {t :fail} (:proofs (:value red))) (pr-str red))
    (is (str/starts-with? (:line (:value red)) "red: 1") "its prove says red")
    (is (= [:proof-red] (:value red-reasons)) "a red on its status, with a turn to fix it")
    (is (= {t :pass} (:proofs (:value green))) (pr-str green))
    (is (= [] (:deferred (:value green))) "nothing left unjudged")
    (is (str/starts-with? (:line (:value green)) "green: 1 of 1") (:line (:value green)))
    (is (= [] (:value green-reasons)))
    (is (= {t :pass} (:proofs (:value again))))
    (is (= 2 (:value asks)) "one ask for each change: at the same digest its verdict stands, and no gate is asked")))

;; "if another agent has already run the same exact test we remove those" (owner, 2026-11-07)
(deftest a-process-test-green-at-its-digest-asks-no-gate
  ;; docs/merging.md#verdicts-are-a-cache. Another's green at this digest answers; another's red does not, and a schema the branch registers moves the
  ;; digest, since a process test takes the schema class and an agent's inputs are its root's boot's (seon.agent/test-plan)
  (let [t 'seon.repl-test/a-process-proof-already-green
        ;; an edge each (seon.program/reach-step): what this judges runs across prove!'s message, which no reference shows, so a fix to it moved no digest here
        _ '[seon.agent/run-selection seon.agent/test-plan seon.agent/run-tests-forked]
        digest (str "(get (:digests (seon.agent/test-plan (seon.agent/db) (:base @seon.agent/state) #{'" t "})) '" t ")")
        hold (fn [verdict] (str "(seon.fx/write-verdicts! {:id \"another\" :key \"another\"} {'" t " (assoc " (pr-str verdict) " :digest " digest ")} {})"))
        prove "(let [r (my.agent/prove)] {:proofs (:proofs r) :deferred (:deferred r)})"
        [_ _ _ red asked-on-red _ _ green asks before _ after _ after-script]
        (in-agent (fn [_] [(answers-gate t) (process-proof "a-process-proof-already-green" 2)
                           (hold {:pass 0 :fail 1 :error 0}) prove "(let [n @user/gate-asks] (reset! user/gate-asks 0) n)"
                           (process-proof "a-process-proof-already-green" 3) (hold {:pass 1 :fail 0 :error 0}) prove "@user/gate-asks"
                           digest "(my.schema/register! :seon.gate-test/k :string)" digest
                           "(do (seon.agent/transact! [{:file/path \"bin/gate-test\" :file/text \"#!/bin/sh\\n\"}]) :ok)" digest])
                  {:proofs #{t}})]
    (is (= 1 (:value asked-on-red)) (str "another's red is not this agent's verdict: the gate is asked. " (pr-str red)))
    (is (= {t :pass} (:proofs (:value green))) (pr-str green))
    (is (= [] (:deferred (:value green))) "nothing left unjudged")
    (is (= 0 (:value asks)) "a green at its digest asks no gate")
    (is (not= (:value before) (:value after)) "a schema the branch registers moves a process test's digest")
    (is (not= (:value after) (:value after-script)) "and so does a script the branch writes")))

;; docs/sharing.md#what-a-branchs-nodes-are: a process test that is no proof keeps its :deferred fact and takes the gate's green over it,
;; docs/sharing.md#a-functions-cost-is-a-fact: what a function cost is queryable on the branch, and adds up across commits, written only with a commit going out anyway
(deftest what-a-function-cost-is-a-fact-on-the-branch
  (let [stats "(:seon.fn/stats (datahike.api/pull (seon.agent/db) [:seon.fn/stats] [:seon.fn/var 'seon.lib/digest]))"
        called "(do (seon.lib/digest \"x\") (seon.lib/digest \"y\") (seon.agent/transact! [{:seon.eval/id \"probe\" :seon.eval/by \"probe\"}]) (seon.agent/commit!) :ok)"
        ended "(do (seon.agent/write-end! {:outcome :waiting}) :ok)"
        [_ committed _ first-stats _ _ second-stats q]
        (in-agent [called stats ended stats called ended stats
                   "(datahike.api/q '[:find ?v :where [?f :seon.fn/var ?v] [?f :seon.fn/stats ?s] [(get ?s :calls) ?c] [(>= ?c 2)]] (seon.agent/db))"])
        a (:value first-stats) b (:value second-stats)]
    (is (nil? (:value committed)) "a commit writes no cost: the turn's datoms stay its own work")
    (is (<= 2 (:calls a)) (str "the end writes them: " (pr-str first-stats)))
    (is (= 4 (- (:calls b) (- (:calls a) 2))) (pr-str [a b]))
    (is (pos? (:bytes b)) "bytes are counted")
    (is (pos? (reduce + (vals (:hist b)))) "calls by size")
    (is (string? (get-in b [:worst :eval])) "the worst eval, to find it again")
    (is (contains? (set (map first (:value q))) 'seon.lib/digest) (str "and a plain query reaches it: " (pr-str q)))))

;; so it never reads as unjudged after it passed and the gate is not asked again; one green in the cache was never deferred, so it needs no fact
(deftest a-deferred-test-that-is-no-proof-is-judged-once
  (let [t 'seon.repl-test/a-deferred-non-proof u 'seon.repl-test/a-cached-non-proof p 'seon.repl-test/the-in-process-proof
        _ '[seon.agent/judge! seon.agent/run-selection seon.proofs/facts-worth-keeping seon.proofs/gate-tx]
        digest (str "(get (:digests (seon.agent/test-plan (seon.agent/db) (:base @seon.agent/state) #{'" u "})) '" u ")")
        judge "(let [r (seon.agent/judge! #{} true)] {:deferred (:deferred r) :asks @user/gate-asks})"
        status (fn [x] (str "(:seon.test/status (datahike.api/pull (seon.agent/db) [:seon.test/status] [:var/name '" x "]))"))
        [_ _ _ _ _ _ _ first-judge t-status u-status second-judge]
        (in-agent (fn [_] [(answers-gate t) "(reset! user/gate-answer :pass)"
                           (process-proof "a-deferred-non-proof" 2) (process-proof "a-cached-non-proof" 2)
                           "(do (in-ns 'seon.repl-test) (clojure.test/deftest the-in-process-proof (clojure.test/is true)) (in-ns 'user) :ok)"
                           "(seon.agent/commit!)"
                           (str "(seon.fx/write-verdicts! {:id \"another\" :key \"another\"} {'" u " {:pass 1 :fail 0 :error 0 :digest " digest "}} {})")
                           judge (status t) (status u) judge])
                  {:proofs #{p}})]
    (is (= [] (:deferred (:value first-judge))) (pr-str first-judge))
    (is (= 1 (:asks (:value first-judge))) "the gate is asked for what no green holds")
    (is (= :green (:value t-status)) "the gate's green is held, over its :deferred fact")
    (is (nil? (:value u-status)) "a green the cache held is no fact")
    (is (= {:deferred [] :asks 1} (:value second-judge)) "judged again: nothing left unjudged, and no second ask")))

;; docs/merging.md#verdicts-are-a-cache: the answer keeps a pass only for what its asker reads; a test named was read as no verdict, "not green: 1 of 1"
(deftest a-passing-test-reads-as-passed
  (let [t 'seon.repl-test/a-plainly-green-named-test
        [_ named] (in-agent (fn [_] [(str "(do (in-ns 'seon.repl-test) (clojure.test/deftest a-plainly-green-named-test (clojure.test/is (= 2 (+ 1 1)))) (in-ns 'user) :ok)")
                                     (str "(:proofs (my.agent/prove '" t "))")]))]
    (is (= :pass (get (:value named) t)) (pr-str named))))

(deftest a-test-that-waits-for-good-is-told-and-the-next-agent-goes-on
  ;; deepseek-run3 (2026-11-06): an agent's own test deref'd a promise nobody delivers. Its test child parked for good at 0% CPU, tick budgets count no sleep,
  ;; and it held the test service's one slot: 5 of 5 agents idle in observe for 9 minutes. A signal the child waited on (SIGIO, for its
  ;; agent's death) counted as something that could wake it, so Jolt never told it (seon.intr/exit-at-eof!)
  (let [t 'seon.problems-test/a-test-that-waits-for-good
        write "(do (in-ns 'seon.problems-test) (clojure.test/deftest a-test-that-waits-for-good (let [p (promise)] (clojure.test/is (= 1 @p)))) (in-ns 'user) :ok)"
        [_ proved] (in-agent (fn [_] [write "(let [r (my.agent/prove)] {:proofs (:proofs r) :error (:error r)})"]) {:proofs #{t}})]
    (is (= {t :error} (:proofs (:value proved))) (pr-str proved))))

(deftest a-gate-that-cant-read-its-candidate-answers
  ;; docs/errors.md#nothing-crashes-and-nothing-hangs "every wait ends": a :gate's writer refusal comes back to whoever asked, no test run (seon.root/ext-op)
  (let [[r] (in-agent (fn [_] [{:op :eval :agent "root" :code "(:seon.error/type @(seon.root/call! sys {:op :gate :branch :no-such-branch :tests ['a/b]}))"}]))]
    (is (= :seon.writer/refused (:value r)) (pr-str r))))

(deftest a-gate-that-cannot-run-is-the-proves-answer
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: a gate that cannot run the candidate (its runner made no plan: on 2026-11-10 a checkout
  ;; that took another tree's binary) answers its typed error, and the agent's prove answers that error, never a verdict that reads as nothing to
  ;; judge; a gate whose run reached no test is a verdict that is not green. The gate answered at its seam (seon.agent/ask-gate)
  (let [t 'seon.repl-test/a-proof-no-gate-could-run
        _ '[seon.agent/judge! seon.agent/ask-gate seon.proofs/gate-tx]
        log (str (System/getProperty "java.io.tmpdir") "/gate-" (random-uuid) ".log")
        _ (spit log "prove: run\nSHARD 0 tests\n")
        seam (str "(do (in-ns 'user) (def gate-reply (atom nil))"
                  " (alter-var-root #'seon.agent/ask-gate (constantly (fn [tests _] (@gate-reply tests)))) :ok)")
        prove "(let [r (my.agent/prove)] (if (seon.contract/error? r) [:error (:seon.error/type r)] [:verdict (:proofs r)]))"
        [_ _ _ refused _ unreached]
        (in-agent (fn [_] [seam (process-proof "a-proof-no-gate-could-run" 2)
                           "(reset! user/gate-reply (fn [_] (seon.contract/error :seon.fx/io-error 'seon.fx/run-on-checkout \"the test runner made no plan\" {:no-plan true})))"
                           prove
                           (str "(reset! user/gate-reply (fn [tests] {:value {:results (zipmap tests (repeat :error)) :digests (zipmap tests (repeat \"gate\")) :log " (pr-str log) "}}))")
                           prove])
                  {:proofs #{t}})]
    (.delete (java.io.File. ^String log))
    (is (= [:error :seon.fx/io-error] (:value refused)) (pr-str refused))
    (is (= [:verdict {t :error}] (:value unreached)) (pr-str unreached))))

(deftest a-hand-back-with-a-red-process-test-is-no-green-end
  ;; the same class through seon_done: an outside agent that hands back an unjudged process test ends on its verdict. The hand-back itself, in the agent, the gate
  ;; answered at its seam: :done goes on to the judge, whose own gate is a real one (no test runs one now: task seon.agent/a-real-gate-is-proved-in-seconds)
  (let [t 'seon.repl-test/a-handed-back-proof-of-the-gate
        [_ _ end] (in-agent (fn [_] [(answers-gate t) (process-proof "a-handed-back-proof-of-the-gate" 2)
                                     "(select-keys (seon.turn/hand-back! (:task @seon.agent/state)) [:outcome :reason])"])
                            {:proofs #{t}})]
    (is (= :red (:outcome (:value end))) (pr-str end))
    (is (str/includes? (:reason (:value end)) "proof-red") (pr-str end))))

(deftest a-task-whose-proofs-pass-is-done-only-when-what-it-reaches-is-green
  ;; the owner's ruling: a turn runs the proofs, the tests the change reaches run once they are green, judged as the
  ;; judge judges them, before it is done. md-scan #24 ended green on its proofs and the judge found 18 reds its change
  ;; made: here a green proof beside a change that breaks main's edit test (seon.fixtures/whole-stray) does not end
  ;; green, and its next turn's status names the red
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"ws\" :seon.task/plan \"Make ws.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/whole-stray}})"
                   " _ (seon.root/await-db sys \"the work ended\" #(" not-running " (:seon.task/outcome (datahike.api/entity % [:seon.task/id \"" % "/ws\"]))))"
                   " db @(:conn sys) t (seon.task/task db \"" % "/ws\")"
                   " b (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"" % "/ws\")}}))"
                   " a (seon.db/eid b :seon.agent/key (:seon.task/agent t))]"
                   " [(:seon.task/outcome t) (seon.reconcile/task-state (seon.reconcile/index db) \"" % "/ws\") (mapv (juxt :reason :symbol) (:reasons (seon.ctx.status/status b (:tree (seon.ctx/build (seon.db/index) b a :b)) {})))])")
        [_ r] (in-agent #(vector {:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))})
                        {})
        [outcome state reasons] (:value r)]
    (is (not (#{:done :merged} outcome)) (pr-str r))
    (is (not= :merged state) "not merged")
    (is (some #{[:stray-red 'seon.repl-test/an-edit-replaces-exact-text-once-and-evaluates-the-definition]} reasons)
        (str "its status names the test its change broke: " (pr-str reasons)))))

(deftest a-red-whole-task-is-not-merged-and-its-next-turn-says-why
  ;; the other way: a whole task whose proof is red is not merged. Its task does not end red: it
  ;; parks (it waits, or reaches its cap), and its turn after the red proof carries why, the done
  ;; rule's reasons as data in the status it reads (a task root refuses at its end gets root's verdict
  ;; instead: seon.reconcile-test/a-whole-task-works-in-parallel-and-its-green-merges). The wait
  ;; ends on the agent's own end, which always comes
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"wr\" :seon.task/plan \"Make wr.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/whole-red}})"
                   " _ (seon.root/await-db sys \"the work ended\" #(" not-running " (:seon.task/outcome (datahike.api/entity % [:seon.task/id \"" % "/wr\"]))))"
                   " db @(:conn sys) t (seon.task/task db \"" % "/wr\")"
                   " b (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"" % "/wr\")}}))"
                   " a (seon.db/eid b :seon.agent/key (:seon.task/agent t))]"
                   " [(:seon.task/outcome t) (seon.reconcile/task-state (seon.reconcile/index db) \"" % "/wr\") (mapv :reason (:reasons (seon.ctx.status/status b (:tree (seon.ctx/build (seon.db/index) b a :b)) {})))])")
        [_ r] (in-agent #(vector {:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))})
                        {})
        [outcome state reasons] (:value r)]
    (is (#{:parked :cap} outcome) (pr-str r))
    (is (not= :merged state) "not merged")
    (is (= [:proof-red] reasons) "the status carries why, as data: the proof is red")))

(deftest a-requests-implement-continues-its-designs-request
  ;; live finding A1, on a real request (the pipeline's: the implement's first request sent its own
  ;; task data in second place and no boundary, 512 of 35,126 tokens cached): through my.root/request! with the
  ;; staged choice (seon.template/stages, an experiment's: the default request is design-build and merge) and
  ;; the reconciler, the implement's first turn, on the design's session, sends the design's task data
  ;; where the design's requests did, the design's last request whole as its prefix, then root's
  ;; boundary message (seon.reconcile/stage-boundary), read off the turns as the branch recorded them
  ;; (the design's proof and plan it registered on its branch are carried to main by its judging, which reads the done rule on them:
  ;; seon.reconcile/judge)
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"st\" :seon.task/plan \"Make st.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/stages}} seon.template/stages)"
                  ;; the implement parked, or a stage ended without it: its task judged (past :running), the design's
                  ;; stopped short of done (parked, dead, lost), or a stage's task is held; a fact either way,
                  ;; so a red answers red rather than waiting
                  " _ (seon.root/await-db sys \"the implement parked, or a stage ended\""
                  "      #(let [held? (fn [id] (= :held (:seon.task/execution (datahike.api/entity % [:seon.task/id id]))))"
                  "             outcome (fn [id] (:seon.task/outcome (datahike.api/entity % [:seon.task/id id])))]"
                  "         (or (not (contains? #{nil :running} (outcome \"" % "/st-implement\")))"
                  "             (not (contains? #{nil :running :done :merged} (outcome \"" % "/st\")))"
                  "             (held? \"" % "/st\") (held? \"" % "/st-implement\"))))"
                  " ended (into {} (for [id [\"" % "/st\" \"" % "/st-implement\"]]"
                  "                  [id (when-let [e (datahike.api/entity @(:conn sys) [:seon.task/id id])] {:outcome (:seon.task/outcome e) :reason (:seon.task/reason e)})]))"
                  " b (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"" % "/st\")}}))"
                  ;; a stage's turns: the model calls whose basis names the stage's task (the one agent works both, its turns one sequence)
                  " sent (fn [id] (mapv second (sort-by first (for [[n bs] (datahike.api/q '[:find ?n ?bs :where [?m :seon.model-call/n ?n] [?m :seon.model-call/origin :model] [?m :seon.model-call/basis ?bs]] b) :when (= id (:task bs))]"
                  "                                              [n (:messages (seon.ctx.wire/render (datahike.api/commit-as-db (:store b) (:commit bs)) bs))]))))"
                  " boundary (datahike.api/q '[:find ?mid . :in $ ?k :where [?a :seon.agent/key ?k] [?m :seon.message/to ?a] [?m :seon.message/from \"root\"] [?m :seon.message/id ?mid]] b (:seon.agent/key (:seon.task/agent (datahike.api/pull @(:conn sys) '[{:seon.task/agent [:seon.agent/key]}] [:seon.task/id \"" % "/st\"]))))]"
                  " {:ended ended :design (sent \"" % "/st\") :implement (sent \"" % "/st-implement\") :boundary boundary})")
        names (volatile! nil)
        [_ r] (in-agent #(do (vreset! names %) [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))}])
                        {})
        {:keys [ended design implement boundary]} (:value r)
        last-design (peek design) first-implement (first implement)]
    (is (= :parked (get-in ended [(str (:ns @names) "/st-implement") :outcome])) (str "the implement parked; the stages' tasks: " (pr-str ended)))
    (is (and (seq design) (seq implement) boundary) (pr-str r))
    (is (= (first (first design)) (first first-implement)) "the system prompt, the same")
    (is (= last-design (subvec first-implement 0 (min (count first-implement) (count last-design))))
        (let [i (first (keep-indexed (fn [i [a b]] (when (not= a b) i)) (map vector last-design first-implement)))]
          (str "the design's last request is its exact prefix; first differing message " i " of " (count last-design) ": "
               (pr-str (some-> i last-design (select-keys [:role :tool-calls :tool-call-id]))) " against " (pr-str (some-> i first-implement (select-keys [:role :tool-calls :tool-call-id])))
               " design " (pr-str (mapv #(subs (str (:role %) ":" (or (:content %) "") (some-> % :tool-calls first :code)) 0 (min 70 (count (str (:role %) ":" (or (:content %) "") (some-> % :tool-calls first :code))))) last-design))
               " implement " (pr-str (mapv #(subs (str (:role %) ":" (or (:content %) "") (some-> % :tool-calls first :code)) 0 (min 70 (count (str (:role %) ":" (or (:content %) "") (some-> % :tool-calls first :code))))) first-implement)))))
    (is (some #(str/includes? (str (:content %)) "Your task now is") (drop (count last-design) first-implement)) "then root's boundary")))

(deftest a-waiting-agent-parks-and-a-message-wakes-the-same-process
  ;; design agent-loop section 2 (owner, 2026-10-25: "Always park"): an agent that waits parks its
  ;; session, its process kept; the owner's message wakes it, and the next wake is the same
  ;; process (its pid), on the same agent (the parked one's own: the harness's agent is another).
  ;; The wake starts clean in that process: it takes the message and runs turns of its own
  ;; (flow-lab's hazards 1 and 2: a stop left by the parked wake ended the next at once with the
  ;; message undelivered; turn numbers restarted over the recorded ones). The agent's turn number is the agent's
  ;; (docs/turn.md#the-turn-is-a-number): one sequence across both wakes
  (let [names (volatile! nil)
        [_ r] (in-agent (fn [{:keys [ns] :as n}]
                          (vreset! names n)
                          (let [w (str ns "/w")]
                            [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                             {:op :eval :agent "root"
                              :code (str "(let [branch-of " branch-of-code "]"
                                         " (my.root/run! sys [" (pr-str w) "]) " (parked-code w 1)
                                         " (let [a " (agent-code w) " k (:seon.agent/key a) ag (keyword k)"
                                         " pid1 (get-in (c/state sys) [:agents ag :pid])"
                                         " b1 (branch-of " (pr-str w) ")"
                                         " turn1 (:seon.agent/turn (datahike.api/entity b1 [:seon.agent/key k]))]"
                                         " @(seon.root/message! sys ag \"carry on\") " (parked-code w 2)
                                         " (let [b2 (branch-of " (pr-str w) ")]"
                                         "  {:key k :pid1 pid1 :pid2 (get-in (c/state sys) [:agents ag :pid])"
                                         "   :turns [turn1 (:seon.agent/turn (datahike.api/entity b2 [:seon.agent/key k]))]"
                                         "   :numbers (sort (datahike.api/q '[:find [?n ...] :where [?m :seon.model-call/n ?n] [?m :seon.model-call/origin :model]] b2))})))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/wait-call}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})
        {:keys [key pid1 pid2 turns numbers]} (:value r)
        [t1 t2] turns]
    (is (string? key) (pr-str r))
    (is (and (pos? t1) (< t1 t2)) (str "and ran turns of its own: " (pr-str turns)))
    (is (= (range 1 (inc t2)) numbers)
        "the wake's turns go on from the parked one's: the agent's one sequence, none restarting and none over a recorded one")
    (is (and pid1 (= pid1 pid2)) (str "one process for both wakes: " pid1 " " pid2))))

(deftest a-text-reply-stays-in-the-history
  ;; task seon.ctx/the-context-is-the-agents-evals-and-seons (R1): a reply of text with no call
  ;; is the agent winding down; the record read only evals and messages, so the wake that
  ;; followed it sent a history without it. It is an assistant message of the next request
  ;; its script is named in config, as data, so seon.model/scripted is named here for the reach digest: a fix there moved no digest of
  ;; this test, and its red and green read as flaky at one (2026-11-07)
  (let [_ #'seon.model/scripted
        [_ r] (in-agent (fn [{:keys [ns]}]
                          (let [w (str ns "/w")]
                            [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                             {:op :eval :agent "root"
                              :code (str "(do (my.root/run! sys [" (pr-str w) "]) " (parked-code w 1)
                                         " (let [ag (keyword (:seon.agent/key " (agent-code w) "))]"
                                         "   @(seon.root/message! sys ag \"carry on\") " (parked-code w 2)
                                         "   (let [db (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch " (pr-str w) ")}}))]"
                                         "     {:intent (sort (datahike.api/q '[:find [?p ...] :where [?e :seon.eval/intent ?p] (not [?e :seon.eval/by \"seon\"])] db))})))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/text-reply}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})]
    (is (= ["Saw my own reply."] (:intent (:value r))) (pr-str r))))

(deftest a-woken-agent-that-replies-stays-parked
  ;; docs/turn.md#parked: a wake gives one conversation turn and its reply parks the agent: it isn't woken again by nothing, or it would spend its budget talking to itself (seen 2026-11-07 on a real model)
  (let [_ '[seon.reconcile/advance seon.reconcile/start seon.reconcile/settle-late]
        [_ r] (in-agent (fn [{:keys [ns]}]
                          (let [w (str ns "/w")]
                            [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                             {:op :eval :agent "root"
                              :code (str "(let [branch-of " branch-of-code "]"
                                         " (my.root/run! sys [" (pr-str w) "]) " (parked-code w 1)
                                         " (let [a " (agent-code w) " k (:seon.agent/key a) ag (keyword k)"
                                         "       turn-of (fn [] (:seon.agent/turn (datahike.api/entity (branch-of " (pr-str w) ") [:seon.agent/key k])))]"
                                         "   @(seon.root/message! sys ag \"carry on\") " (parked-code w 2)
                                         "   (let [t2 (turn-of) m2 (:db (deref (seon.root/call! sys {:op :write :req {:op :branch-db :branch :db}})))"
                                         "         _ (dotimes [_ 5] (deref (seon.root/call! sys {:op :write :req {:op :tx :branch :db :tx-data [{:seon.agent/key k :seon.agent/description \"a move of main\"}]}})))"
                                         "         t3 (turn-of)]"
                                         "     {:turns [t2 t3] :outcome (:seon.task/outcome (datahike.api/entity @(:conn sys) [:seon.task/id " (pr-str w) "]))})))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/wait-call}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :user}]})
        {:keys [turns outcome]} (:value r)]
    (is (= :parked outcome) (pr-str r))
    (is (apply = turns) (str "no more turns while nothing arrives: " (pr-str turns)))))

(deftest a-reply-with-no-evals-stops-with-turns-left
  ;; docs/model.md#one-tool-eval: a reply with text and no call is its message alone: nothing runs and the
  ;; agent waits, with turns left
  (let [reply (fn [text calls] {:usage {:prompt_tokens 10 :completion_tokens 2}
                                :choices [{:finish_reason "stop" :message (cond-> {:content text} calls (assoc :tool_calls calls))}]})
        text-only (seon.model/reply-of (reply "Nothing to run; I wait for you." nil) 1 100)
        one-eval (seon.model/reply-of (reply "Reading." [{:id "c1" :function {:name "eval" :arguments "{\"intent\":\"read\",\"code\":\"(+ 1 2)\"}"}}]) 1 100)
        no-text (seon.model/reply-of (reply nil [{:id "c1" :function {:name "eval" :arguments "{\"intent\":\"read\",\"code\":\"(+ 1 2)\"}"}}]) 1 100)
        [end] (in-agent #(vector (str "(seon.turn/work! {:task " (pr-str (:task %)) " :max-turns 5})"))
                          {:config {:seon.config.ai/script 'seon.fixtures/text-reply}})]
    (is (= ["Nothing to run; I wait for you." nil] [(:text text-only) (:tool-calls text-only)]) "text and no call: its message, no call")
    (is (= ["Reading." ["c1"]] [(:text one-eval) (mapv :id (:tool-calls one-eval))]) "an eval is a call beside its text")
    (is (= ["" ["c1"]] [(:text no-text) (mapv :id (:tool-calls no-text))]) "a call with no text: the call runs, the message is empty")
    (is (= [:waiting 1] ((juxt :outcome :model-calls) (:value end))) (str "the agent waits after one turn of five: " (pr-str end)))))

(deftest a-woken-agent-is-not-sent-its-own-loop
  ;; task seon.render/one-way-to-make-what-an-agent-is-sent, step 2: the eval that ran the parked work (seon.turn/work!) is
  ;; recorded when it returns, so a wake's request came to hold it as a message; it is no work of the agent's
  (let [[_ r] (in-agent (fn [{:keys [ns]}]
                          (let [w (str ns "/w")]
                            [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                             {:op :eval :agent "root"
                              :code (str "(do (my.root/run! sys [" (pr-str w) "]) " (parked-code w 1)
                                         " (let [a " (agent-code w) " k (:seon.agent/key a) ag (keyword k)]"
                                         "   @(seon.root/message! sys ag \"carry on\") " (parked-code w 2)
                                         "   (let [db (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch " (pr-str w) ")}}))]"
                                         "     {:evals (mapv #(datahike.api/pull db '[:seon.eval/id :seon.eval/by :seon.eval/code] %) (datahike.api/q '[:find [?e ...] :in $ ?k :where [?a :seon.agent/key ?k] [?e :seon.eval/agent ?a]] db k))"
                                         "      :woken (let [[_ bs] (last (sort-by first (datahike.api/q '[:find ?n ?bs :in $ ?k :where [?a :seon.agent/key ?k] [?c :seon.model-call/agent ?a] [?c :seon.model-call/n ?n] [?c :seon.model-call/basis ?bs]] db k)))"
                                         "                   ms (:messages (seon.ctx.wire/render (datahike.api/commit-as-db (:store db) (:commit bs)) bs))]"
                                         "               {:codes (vec (keep #(some-> % :tool-calls first :code) ms)) :users (vec (keep #(when (= \"user\" (:role %)) (:content %)) ms))})})))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/wait-call}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})
        {:keys [evals woken]} (:value r)
        ;; the wake's request: this agent's own newest model call (its id is <agent-key>/<n>), since a shared root's branch
        ;; holds what other tasks merged to main
        sent-codes (:codes woken)]
    (is (some #(str/includes? % "carry on") (:users woken)) (str "the wake's request holds the message it took: " (pr-str r)))
    (is (some #{"(my.turn/wait)"} sent-codes) (str "it sent the model's own eval: " (pr-str sent-codes) " of " (pr-str r)))
    (is (not-any? #(str/includes? % "seon.turn/work!") sent-codes) (pr-str sent-codes))))

(deftest a-task-at-its-cap-is-recorded-cap-never-waiting
  ;; root, 2026-10-26: a run whose last turn waits spent its budget, so it ends :cap, which a
  ;; live view reads as the budget spent, not as a wait for a message; a wait before the cap is
  ;; :waiting, and the agent's own fail! at the cap is :failed (its verdict holds the task)
  (let [outcome (fn [script max-turns]
                  (:outcome (:value (first (in-agent #(vector (str "(seon.turn/work! {:task " (pr-str (:task %)) " :max-turns " max-turns "})"))
                                                     {:config {:seon.config.ai/script (symbol "seon.fixtures" script)}})))))]
    (is (= :cap (outcome "wait-call" 1)) "a wait on the last turn: the budget spent")
    (is (= :waiting (outcome "wait-call" 2)) "a wait with turns left: waiting")
    (is (= :failed (outcome "fail-call" 1)) "fail! on the last turn: the agent's verdict")))

(deftest a-failed-call-keeps-what-it-sent
  ;; root, 2026-10-24: a turn whose model call fails still records what it sent (the hash of its bytes, written as the
  ;; request goes; the request itself is made again from its records) and its typed error (an error is data: docs/errors.md#errors);
  ;; the debug page shows both
  (let [[end turn] (in-agent (fn [{:keys [task ns]}]
                              [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                              (str "(let [db (seon.agent/db) id (seon.lib/call-id (:key @seon.agent/state) 1)"
                                   "      m (datahike.api/pull db '[:seon.model-call/sent-hash :seon.model-call/basis :seon.model-call/error] [:seon.model-call/id id])"
                                   "      b (:seon.model-call/basis m)"
                                   "      ms (:messages (seon.ctx.wire/render (datahike.api/commit-as-db (:store db) (:commit b)) b))]"
                                   "  {:sent-hash (:seon.model-call/sent-hash m) :error (:seon.error/type (:seon.model-call/error m))"
                                   "   :redrawn (seon.lib/digest (seon.edn/encode ms)) :roles (mapv :role ms)"
                                   "   :read (mapv :content ms)})")])
                             {:config {:seon.config.ai/provider :none}})]
    (is (= :error (:outcome (:value end))) (pr-str end))
    (is (= 16 (count (:sent-hash (:value turn)))) "the hash of the bytes it sent, written before the call")
    (is (= (:sent-hash (:value turn)) (:redrawn (:value turn))) "and its request made again from its records is those bytes")
    (is (= ["system" "user" "assistant" "tool"] (:roles (:value turn))) "the system message, what opens the session, then the page as the call Seon made and its answer (seon.ctx.wire/page-diff-msgs)")
    (is (not (str/includes? (str (peek (:read (:value turn)))) "turns")) (str "the page announces no cap: " (peek (:read (:value turn)))))
    (is (= :seon.model/no-provider (:error (:value turn))) "and the typed error the call answered")))

(deftest a-whole-tasks-work-turns-are-no-merge-turns
  ;; the first whole-task run (2026-10-26): all ten requests ended :merge-failed at turn 3, each work
  ;; turn counted against :seon.config.merge/max-merge-turns (2) as a merge task's turns are; a
  ;; whole task's turn is a merge turn only while what both sides changed is unresolved
  (let [[end] (in-agent #(vector (str "(seon.turn/work! {:task " (pr-str (str (:ns %) "/w")) " :max-turns 4})"))
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
  ;; each eval of a turn (the screen's two, then the model's) is committed as it lands, so a page
  ;; shows it then, not at the turn's end. One model call of three evals (seon.fixtures/three-calls), so a turn whose evals
  ;; were committed together shows, whatever else the turn records (pages drew no page eval). Walked back from the branch's head, no commit adds more
  ;; than one eval record (one commit at the turn's end added the turn's evals at once: 2 -> 4). Only this agent's evals count, and
  ;; a merge commit is walked back to its parent on this branch (the one holding most of them), never main's: parent-commit-ids
  ;; is a set, and its first was main's at random, whose commits hold root's own evals two at a time (the flake, 2026-11-10)
  (let [[_ counts] (in-agent (fn [{:keys [task ns]}]
                             [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                              (str "(let [db (seon.agent/db) k (:key (clojure.core/deref seon.agent/state))"
                                   "      n (fn [db] (count (datahike.api/q '[:find ?e :in $ ?k :where [?a :seon.agent/key ?k] [?e :seon.eval/agent ?a] [?e :seon.eval/id]] db k)))"
                                   "      evs (fn [db] (set (datahike.api/q '[:find ?i ?o ?c :in $ ?k :where [?a :seon.agent/key ?k] [?e :seon.eval/agent ?a] [?e :seon.eval/id ?i] [(get-else $ ?e :seon.eval/origin :none) ?o] [(get-else $ ?e :seon.eval/code \"\") ?c]] db k)))"
                                   "      back (fn [db] (when-let [ps (seq (datahike.api/parent-commit-ids db))] (apply max-key n (map #(datahike.api/commit-as-db db %) ps))))"
                                   "      dbs (vec (take 30 (take-while some? (iterate back db))))]"
                                   "  {:counts (vec (distinct (map n dbs)))"
                                   "   :over (vec (for [[a b] (map vector dbs (rest dbs)) :let [added (clojure.set/difference (evs a) (evs b))] :when (< 1 (count added))]"
                                   "                {:t (:max-tx a) :parent-t (:max-tx b) :added (mapv (fn [[i o c]] [i o (subs c 0 (min 60 (count c)))]) added)}))})")])
                             {:config {:seon.config.ai/script 'seon.fixtures/three-calls}})
        ns (:counts (:value counts))]
    (is (< 3 (count ns)) (pr-str counts))
    (is (every? #(<= % 1) (map - ns (rest ns))) (str "each eval its own commit: " (pr-str ns) " commits that added more: " (pr-str (:over (:value counts)))))))

(deftest a-model-turn-records-its-text-and-the-message-it-took
  ;; the owner's message is a fact root writes on
  ;; main (from the user, to the agent); the turn that delivers it writes the same
  ;; entity on its branch before its commit, with the agent's own ref (:seon.message/to), so the conversation
  ;; and every later reading read its text from that commit (the basis names no message); the reply's message (here the scripted model's)
  ;; is the turn's reply and its call's eval's text, and the call names its one eval
  ;; (docs/render.md)
  (let [names (volatile! nil)
        [_ end _ turn on-main] (in-agent (fn [{:keys [agent task ns] :as n}]
                                         (vreset! names n)
                                         [{:op :message :agent agent :body "use demo/g"}
                                        (str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                                        ;; an outside eval after the turn takes the number in force
                                        "(+ 1 2)"
                                        (str "(let [db (seon.agent/db) m (datahike.api/pull db '[*] [:seon.model-call/id (seon.lib/call-id (:key @seon.agent/state) 1)])"
                                             "      evals (vec (datahike.api/q '[:find ?i ?c :where [?mc :seon.model-call/n 1] [?e :seon.eval/call ?mc] [?e :seon.eval/call-id _] [?e :seon.eval/id ?i] [?e :seon.eval/code ?c]] db))"
                                             "      msgs (fn [db] (vec (datahike.api/q '[:find ?id ?f ?b ?k :in $ ?k :where [?m :seon.message/id ?id] [?m :seon.message/from ?f] [?m :seon.message/body ?b] [?m :seon.message/to ?a] [?a :seon.agent/key ?k]] db (:key @seon.agent/state))))]"
                                             "  {:calls (mapv (fn [[i c]] {:eval i :code c}) evals) :reply (:seon.model-call/reply m) :basis-keys (set (keys (:seon.model-call/basis m)))"
                                             "   :agent-key (:key @seon.agent/state) :on-branch (msgs db)"
                                             "   :turn (:seon.agent/turn (datahike.api/entity db [:seon.agent/key (:key @seon.agent/state)]))"
                                             "   :eval-turns (set (datahike.api/q '[:find ?by ?t :where [?e :seon.eval/by ?by] [?e :seon.eval/call ?c] [?c :seon.model-call/n ?t]] db))"
                                             "   :loop-eval-turns (vec (datahike.api/q '[:find [?t ...] :where [?e :seon.eval/by ?b] [(not= ?b \"outside\")] [(not= ?b \"seon\")] [(missing? $ ?e :seon.eval/call-id)] [?e :seon.eval/turn ?t]] db))"
                                             "   :message-turns (let [c @seon.ctx/ctx] (vec (for [[n cid] (seon.ctx/turns c) :when (seq (:messages (seon.ctx/turn-members db c cid)))] n)))"
                                             "   :call-ids (vec (datahike.api/q '[:find [?id ...] :where [?m :seon.model-call/id ?id]] db))"
                                             "   :committed (msgs (datahike.api/commit-as-db db (get-in m [:seon.model-call/basis :commit])))"
                                             "   :intent (mapv #(datahike.api/q '[:find ?r . :in $ ?i :where [?e :seon.eval/id ?i] [?e :seon.eval/intent ?r]] db %) (map first evals))})")
                                        {:op :eval :agent "root"
                                         :code (str "(datahike.api/q '[:find ?id ?f ?to ?b :where [?m :seon.message/id ?id] [?m :seon.message/from ?f]"
                                                    " [?m :seon.message/to ?a] [?a :seon.agent/key ?to] [?m :seon.message/body ?b]] @(:conn sys))")}])
                                       {:config {:seon.config.ai/script 'seon.fixtures/thinking-call}})
        end (:value end)
        {:keys [calls on-branch committed intent reply agent-key basis-keys] :as probed} (:value turn)
        ;; the shared root's main holds every earlier test's messages: this agent's are the ones to its id
        [[mid from to body] :as facts] (filterv #(= (name (:agent @names)) (nth % 2)) (:value on-main))]
    (is (= 1 (:model-calls end)) (pr-str end))
    (is (= [[(seon.config/value :seon.config.root/user-name) (name (:agent @names)) "use demo/g"]] (mapv #(subvec % 1) facts)) "one entity on main: from the user, to the agent")
    (is (= [[mid (seon.config/value :seon.config.root/user-name) "use demo/g" agent-key]] on-branch) "the turn wrote the entity root wrote, by its id, with the agent's ref")
    (is (= on-branch committed) "and it is in the commit the turn read")
    (is (not-any? #{:observed :taken} basis-keys) "the basis names no message: the fact is the record")
    (is (contains? basis-keys :t) "it holds its t: a commit a sweep took is drawn as of it (docs/storage.md#collection)")
    ;; the text comes in the eval call (docs/prompt.md); the scripted reply's text is the same text
    (is (= "check the sum before the proof" reply) "the reply's text: the text")
    (is (string? (:eval (first calls))) (str "the call names its one eval: " (pr-str calls)))
    (is (= ["check the sum before the proof"] intent) "whose record keeps the intent beside it")
    ;; docs/turn.md#the-turn-is-a-number: the agent's number, the model call's id and the number each record took
    (is (= 1 (:turn probed)) "the agent's first turn")
    (is (= [(seon.lib/call-id agent-key 1)] (:call-ids probed)) "its model call is the digest of <agent key> and <n>, and Seon made no call of its own before it")
    (is (= [1] (:message-turns probed)) "the message the turn delivered carries its number")
    (is (= #{[(:agent @names) 1] ["outside" 1]} (:eval-turns probed)) "the model's eval carries the turn, so does an outside eval after it, the number in force; Seon ran no eval before the turn")
    (is (empty? (:loop-eval-turns probed)) "the turn loop's own eval, no conversation record, takes none")))

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

(defn- thrown
  "The error map of evaluating the Clojure text code in the user namespace."
  [code]
  (agent/error-map (try (binding [*ns* (the-ns 'user)] (eval (read-string code))) (catch Throwable e e))))

(deftest a-private-var-says-the-form-that-reaches-it
  ;; forgiving tools (data: 11 evals over 5 sessions died on "var: #'ns/f is not public", six in a row in one): Clojure's own
  ;; forms reach a private var, the var's value and a call through the var; the error says so
  (let [em (thrown "(seon.reader/fence \"x\")")]
    (is (= :analyze/private-var (:jolt.error/kind em)))
    (is (str/includes? (str (:seon.error/fix em)) "(#'seon.reader/fence") (pr-str em))
    (is (str/includes? (str (:seon.error/fix em)) "@#'seon.reader/fence") (pr-str em))))

(deftest a-schema-malli-does-not-know-is-named-with-its-nearest
  ;; data: a contract written [:time :inst] answered ":malli.core/invalid-schema" and nothing else; six turns went to
  ;; finding that the predicate is the symbol inst?
  (let [em (agent/error-map (try ((requiring-resolve 'malli.core/schema) [:vector :inst]) (catch Throwable e e)))]
    (is (str/includes? (str (:seon.error/message em)) ":inst") (pr-str em))
    (is (str/includes? (str (:seon.error/fix em)) "inst?") (pr-str em))))

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

(deftest a-parked-agent-draws-its-parts-and-a-gone-one-answers-at-once
  ;; the agent draws its own page (one derivation, slice 2): the render proc's {:op :render} ask is
  ;; answered by an agent parked between requests with its parts' HTML, a key its branch renders
  ;; drawn by that render, recording nothing (the key is named by the agent's pattern, which pulls only what it names); a request to an agent that is gone is answered at
  ;; once, so its page is drawn by root from its branch and never waits
  (let [n "(count (datahike.api/datoms (seon.agent/db) :aevt :seon.eval/id))"
        [_ _ swapped before drawn after gone]
        (in-agent #(vector "(my.schema/register! :lab.ctx/steps '[:vector {:seon.render/hiccup lab.ui/steps} :string])"
                   "(ns lab.ui) (defn steps \"The steps, numbered.\" {:malli/schema [:=> [:cat :map [:vector :string]] :any]} [_ v] [:ol (for [s v] [:li s])])"
                   "(do (swap! seon.ctx/ctx assoc :seon.ctx/pattern (conj (seon.render/schema-property (seon.db/index) :seon.db/agent-fact :seon.ctx/pattern) :lab.ctx/steps)) (swap! seon.ctx/ctx assoc :lab.ctx/steps [\"read the plan\" \"write the test\"]))"
                   n
                   {:op :eval :agent "root"
                    :code (str "(let [id :" (:agent %) "]"
                               " @(seon.root/call! sys {:op :render :id id}))")}
                   n
                   {:op :eval :agent "root" :code "@(seon.root/call! sys {:op :render :id :gone})"}))
        ;; the keys it added, one component (seon.render.ui/ctx-keys) holding each key's own, a unit of its own inside it
        steps (some #(when (= "ctx-key-lab_2Ectx_2Fsteps" (:id %)) %) (:units (:value drawn)))]
    (is (= "ctx-keys" (:parent steps)) "the key's unit, inside the keys' component")
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

;; docs/references.md#an-entity-cites: an agent is made citing what every agent cites (its own cite entities, written when
;; root makes it), and changes its own at its REPL; its page draws each as a task's cite is drawn
(deftest an-agent-cites-what-it-reads-beside-its-task
  (let [[before added gone] (in-agent ["(mapv :cites (my.agent/uncite! \"docs/none.md\"))"
                                       "(mapv (juxt :cites :depth) (my.agent/cite! \"docs/agent.md?summary#the-agent\"))"
                                       "(mapv :cites (my.agent/uncite! \"docs/testing.md#testing-in-the-world\"))"])]
    (is (= ["docs/agent.md#working-a-task" "docs/agent.md#merging" "docs/testing.md#testing-in-the-world" "docs/data-modeling.md#data-modeling"] (:value before))
        (str "made with every agent's, the config's references: " (pr-str before)))
    (is (= [["docs/agent.md#working-a-task" :doc] ["docs/agent.md#merging" :doc] ["docs/testing.md#testing-in-the-world" :doc] ["docs/data-modeling.md#data-modeling" :doc] ["docs/agent.md#the-agent" :summary]] (:value added)) (pr-str added))
    (is (= ["docs/agent.md#working-a-task" "docs/agent.md#merging" "docs/data-modeling.md#data-modeling" "docs/agent.md#the-agent"] (:value gone))
        (str "a default it uncites is gone from its own: " (pr-str gone)))))

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
            _ (d/transact conn {:tx-data [{:note/text "main moved" :note/agent :t}]})
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
  (let [base (seon.program-test/record (my.test/world) 'user "(ns app.md)" 'app.md "(defn f [x] x)" 'app.md "(defn g [x] (f x))" 'app.md "(defn h [x] x)")
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
  ;; docs/errors.md#nothing-crashes-and-nothing-hangs (nothing hangs), docs/failure-points.md (a refused write): the run's results are
  ;; recorded on the branch when the run finishes, which the loop runs, not the asker's eval; a
  ;; write the branch refuses there answers the asker (my.agent/prove) with the error, never leaves it waiting.
  ;; The agent's own REPL makes its writer refuse (its process, its definition of transact!)
  (let [[_ _ r] (in-agent ["(ns fin-test (:require [clojure.test :refer [deftest is]])) (deftest r (is (= 1 1)))"
                           "(alter-var-root #'seon.agent/transact! (fn [f] (fn [tx] (if (some :seon.test/status (filter map? tx)) (throw (ex-info \"transact refused: no\" {:writer-error \"no\"})) (f tx)))))"
                           "(let [p (my.agent/prove)] (select-keys p [:pass :unrecorded]))"]
                          {:proofs #{'fin-test/r}})]
    (is (nil? (:ex r)) (pr-str r))
    (is (= {:pass 1} (select-keys (:value r) [:pass])) (pr-str r))
    (is (some #(str/includes? % "transact refused: no") (:unrecorded (:value r))) "named, not thrown")))

(deftest a-commit-the-store-refuses-leaves-the-agent-alive
  ;; task seon.repl/a-commit-that-keeps-failing-ends-no-agent-silently;
  ;; docs/errors.md#nothing-crashes-and-nothing-hangs (nothing crashes), docs/storage.md#a-commit-the-store-refuses. A commit the store refuses used
  ;; to throw out of the agent's loop and end the process, the next commit throwing again on the same pending value.
  ;; Now the eval answers what was refused as data under :unrecorded, its writes are dropped, the branch and @ctx are as
  ;; committed, and the next eval and commit are as any. The store's refusing is made in the agent's own REPL (its
  ;; definition of the commit, as the test above does for a write): it throws for as long as the run-ahead value holds
  ;; the note "poison", as a value hasch or the codec cannot take does (seon.db-test/a-large-value-holding-ratios-commits-and-reads-back
  ;; is the real one; a datom the schema refuses is refused at the write, so no plain value is left that only a commit refuses)
  (let [[r1 r2 r3 r4 r5 r6]
        (in-agent ["(seon.agent/transact! [{:note/text \"kept before\" :note/agent :t}])"
                   "(alter-var-root #'datahike.writer/commit-pending! (fn [f] (fn [c] (if (some #(= \"poison\" (:v %)) (datahike.api/datoms (:db @(:pending (:writer @(:wrapped-atom c)))) :aevt :note/text)) (throw (ex-info \"no encoding for this value\" {})) (f c)))))"
                   "(do (seon.agent/transact! [{:note/text \"poison\" :note/agent :t}]) :written)"
                   "(+ 1 1)"
                   "(do (seon.agent/transact! [{:note/text \"after\" :note/agent :t}]) (my.db/q '[:find [?t ...] :where [_ :note/text ?t]]))"
                   "(let [db (my.db/db)] {:ctx-t (:t @seon.ctx/ctx) :t (:max-tx db) :notes (sort (my.db/q '[:find [?t ...] :where [_ :note/text ?t]]))})"])
        u (:unrecorded r3)]
    (is (nil? (:ex r1)) (pr-str r1))
    (is (nil? (:ex r2)) (pr-str r2))
    (is (= :written (:value r3)) "the eval's value is still its answer")
    (is (= :seon.agent/commit-refused (:seon.error/type u)) (pr-str r3))
    (is (pos? (:transactions u)) (pr-str u))
    (is (= [(:record r3)] (:evals u)) (pr-str u))
    (is (str/includes? (:seon.error/message u) "did not stay") (:seon.error/message u))
    (is (nil? (:ex r4)) (pr-str r4))
    (is (= 2 (:value r4)) "the next eval answers as any")
    (is (nil? (:unrecorded r4)) (pr-str r4))
    (is (= #{"kept before" "after"} (set (:value r5))) "what was committed before is intact, and a later commit holds")
    (is (nil? (:unrecorded r5)) (pr-str r5))
    (is (= ["after" "kept before"] (:notes (:value r6))) "the refused write is not on the branch")
    (is (= (:t (:value r6)) (:ctx-t (:value r6))) "@ctx agrees with the committed db")))

(deftest the-reader-is-the-processs-own
  ;; optimization's review: the intake's reader kept in state was lost to an eval replacing it, and the next
  ;; intake started a second reader on the same socket; it is link's, beside the socket
  (let [old @agent/state old-link @agent/link ch (clojure.core.async/chan)]
    (try (reset! agent/link {:fd -1 :conn ::conn :ch ch})
         (reset! agent/state {})
         (is (identical? ch (agent/intake)) "the one reader's channel, whatever state became")
         (finally (reset! agent/state old) (reset! agent/link old-link)))))

(deftest a-named-namespace-selects-its-tests
  ;; bench-scope-1: a prove naming three tests of a namespace on a branch that had changed nothing ran none
  ;; (ran 0, reused 0, each :none); named, a namespace's tests run whatever the branch changed
  (let [db (my.test/with (my.test/world)
                         (my.test/code 'app.nt "(ns app.nt)\n(defn f [x] x)")
                         (my.test/code 'app.nt-test "(ns app.nt-test (:require [clojure.test :refer [deftest is]] [app.nt]))\n(deftest a-test (is (= 1 (app.nt/f 1))))\n(deftest b-test (is true))\n(defn helper [] 1)")
                         (my.test/code 'app.other-test "(ns app.other-test (:require [clojure.test :refer [deftest is]]))\n(deftest c-test (is true))"))]
    (is (= '#{app.nt-test/a-test app.nt-test/b-test} (agent/named-tests db '#{app.nt-test})) "its deftests, not its other vars, and no other namespace's")
    (is (= #{} (agent/named-tests db '#{app.none-test})) "a namespace with no tests names none")
    (is (= '#{app.nt-test/b-test} (agent/named-tests db '#{app.nt-test/b-test})) "a named test is itself, not its namespace")
    (is (= #{} (agent/named-tests db '#{app.nt-test/helper})) "a var that is no test names none")))

(deftest an-agents-namespace-holds-the-docstring-its-form-gives
  ;; docs/source.md: a namespace's docstring, on an agent's own eval, however its ns form gives it (a name's metadata
  ;; reaches the account by seon.repl's own read of it, as a file's does by seon.source's)
  (let [[_ _ r] (in-agent (fn [{:keys [ns]}]
                            [(str "(ns " ns ".nd1 \"By a string.\")")
                             (str "(ns ^{:doc \"By its name.\"} " ns ".nd2)")
                             (str "(let [db (seon.agent/db) doc (fn [n] (:ns/doc (datahike.api/entity db [:ns/name n])))]"
                                  " [(doc '" ns ".nd1) (doc '" ns ".nd2)])")])
                          {})]
    (is (= ["By a string." "By its name."] (:value r)) (pr-str r))))

(deftest an-eval-that-registers-a-schema-seons-answer-names-answers-what-it-did
  ;; the lab, 2026-11-04: an eval registered :seon.eval/response narrower, for its own use, and its later eval's answer failed
  ;; seon.repl/response's own contract: told an error, though its forms took effect
  (let [[reg made ran] (in-agent (fn [_]
                                   ["(my.schema/register! :seon.eval/response '[:and :map :seon.db/plain])"
                                    "(defn zz-made {:malli/schema [:=> [:cat] :int]} [] 41)"
                                    "(zz-made)"])
                                 {})]
    (is (nil? (:ex made)) (str "the definition's answer: " (pr-str made) " after " (pr-str reg)))
    (is (= 41 (:value ran)) (pr-str ran))))

(deftest an-eval-makes-data-and-a-reader-makes-text
  ;; the owner, 2026-11-04: "why are we generating text for each eval? If we can store the value store the value and text is a
  ;; last resort". A model's eval prints nothing; a value that throws as it is realized is still its recorded error, since
  ;; making it plain realizes it; a value that is no data is stored as what it was
  (let [[model thrown atm rec]
        (in-agent (fn [_] ["(dissoc (seon.repl/eval-string \"(+ 1 2)\" {:origin :model}) :ms :ticks :bytes)"
                           "(let [r (seon.repl/eval-string \"(map (fn [_] (throw (ex-info \\\"late\\\" {}))) [1])\" {:origin :model})] (select-keys r [:ex :record]))"
                           "(let [r (seon.repl/eval-string \"(atom 1)\" {:origin :model})] (:record r))"
                           "(let [id (:record (seon.repl/eval-string \"(atom 1)\" {:origin :model}))] (:value (seon.db/response-of (seon.agent/db) (datahike.api/pull (seon.agent/db) '[:seon.eval/response :seon.eval/result] [:seon.eval/id id]))))"])
                  {})]
    (is (not (contains? (:value model) :text)) (pr-str model))
    (is (some? (get-in thrown [:value :ex])) (str "a value throwing as it is realized is the eval's error: " (pr-str thrown)))
    (is (some? (get-in thrown [:value :record])) "and recorded")
    (is (some? (:value atm)) "an atom's eval is recorded")
    (is (= "clojure.lang.Atom" (:seon.value/type (:value rec))) (pr-str rec))))


(deftest evals-given-at-spawn-run-first-as-ordinary-evals
  ;; docs/turn.md#an-eval-can-be-inserted: an agent spawned with :evals (pairs of text and code) runs them on its one REPL
  ;; queue before anything else, each recorded as any eval is, by whoever asked, at turn 0 and with no call id. One that
  ;; throws is recorded with its error and the rest run, as a reply's calls do (owner, 2026-11-08). A reader of the
  ;; conversation then sees them in order and, in the request, as calls of their own.
  (let [read (str "(let [c @seon.ctx/ctx db (seon.agent/db) es (map #(get-in c [:seon.eval/_agent (first %)]) (:evals (seon.ctx/turn-members db c (second (first (seon.ctx/turns c))))))] "
                  "{:turn (seon.agent/turn-now) "
                  " :records (mapv (fn [v] [(:seon.eval/by v) (:seon.eval/intent v) (:seon.eval/call-id v) (some? (:ex (seon.db/response-of db v))) (:value (seon.db/response-of db v))]) es) "
                  " :roles (mapv :role (drop 2 (:messages (seon.ctx.wire/render db {:agent (:key @seon.agent/state) :commit (datahike.api/commit-id db) :task \"x\" :turn 1 :max-turns 1 :origin :outside} {:walk (seon.repl/page-walk)}))))})")
        code (str "(let [id (c/spawn! sys {:evals [[\"Add.\" \"(+ 1 2)\"] [\"Throw.\" \"(throw (ex-info \\\"boom\\\" {}))\"] [\"Never.\" \"(+ 3 4)\"]]}) "
                  "r @(c/submit! sys id " (pr-str read) " {:origin :outside})] "
                  "(c/kill! sys id) (:value r))")
        [r] (in-agent [{:op :eval :agent "root" :code code}] {})
        v (:value r)]
    (is (= 0 (:turn v)) (pr-str r))
    (is (= [["root" "Add." nil false 3]
            ["root" "Throw." nil true nil]
            ["root" "Never." nil false 7]]
           (:records v))
        "three ordinary evals at turn 0 in order, by whoever asked, none a model's call; the throw recorded, and the next still runs")
    (is (= ["assistant" "tool" "assistant" "tool" "assistant" "tool"] (take 6 (:roles v))) "each reaches the request as a call and its result")))

(deftest one-schema-write-builds-the-branch-index-once
  ;; docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together: the agent's state slot is its one copy of the entity index
  ;; (seon.agent/branch-schemas): the write that moves a schema entry builds it, and its readers (the registry, the ctx's patch, the
  ;; page walk's flush) are handed that one. :index-builds counts the builds in the state
  (let [db (ts/test-db)
        st {:db db :schema-registry {:key {} :registry {} :index (seon.db/index)}}
        moved (seon.agent/schema-moved st #{:schema/form})]
    (is (nil? (:index-builds (seon.agent/schema-moved st #{:seon.eval/id}))) "a write of another attribute builds none")
    (is (= 1 (:index-builds moved)) "a schema entry's write builds one")
    (is (some? (:index (:schema-registry moved))) "into the slot")
    (is (= 1 (:index-builds (seon.agent/schema-moved moved #{:seon.eval/id}))) "and a later write of another attribute builds no other")))
