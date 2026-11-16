(ns seon.agent-test
  "An agent's process, run as the built binary (target/seon, bin/build seon): the root forks
  it, so the test boots a root of its own, as seon.system-test does, and asks the agent over
  the server what its REPL answers.

  An agent's test run, read from its child's pipe:
  no clock ends it; the child's exit does, and a child that died names the test it was running.
  Run: bin/test seon.agent-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.agent :as agent] [seon.reconcile :as r] [seon.proofs :as proofs] [seon.contract :as contract]
            [datahike.api :as d] [seon.db :as pdb] [seon.test-store :as ts] [seon.program-test] [seon.lib :as lib] [seon.program :as program] [seon.sock :as sock]
            [seon.fx :as fx] [seon.cost :as cost] [seon.config :as cfg] [seon.merge :as pm] [seon.profile :as profile] [seon.task :as task] [seon.writer :as writer] [seon.repl :as repl] [seon.agent-proc] [seon.intr :as intr] [seon.ctx] [seon.render] [seon.turn] [my.test :as mt] [test-runner :as runner] [seon.ctx.wire-test] [my.task] [my.agent] [my.branch] [seon.template :as tpl] [seon.doc] [clojure.core.async :as a] [clojure.core.async.flow :as flow] [jolt.async.remote :as remote] [jolt.async.remote.flow :as rflow] [jolt.io-poller :as poller]))

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
  forms evaluated in that agent (a string), sent to the server as it is (a map), or run in this process with
  {:root :names} (a function: a read of the store, seon.test-store/read-branch, which asks root nothing): every reply's
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
         (with-agent opts (fn [{:keys [names ask root]}]
                            ;; a string: a form on the agent's REPL, by root's submit!, no reply and no turn of anyone's;
                            ;; a function: run here, with the test's root and names, its value as :value (a read of the store,
                            ;; seon.test-store/read-branch, asks root nothing)
                            (mapv #(cond (fn? %) {:value (% {:root root :names names})}
                                         (map? %) (ask %)
                                         :else (let [a (ask {:op :eval :agent "root" :code (str "@(c/submit! sys :" (:agent names) " " (pr-str %) " {:origin :outside})")})]
                                                 (if-let [r (get-in a [:response :value])] {:response r} a)))
                                  (if (fn? forms) (forms names) forms)))))))

;; Estimate: reach graph of the world ~2M, 3 graph indexes at ~0.5M, 2 record-tx ~0.4M = ~4000000.
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-branchs-config-key-moves-the-tests-reading-it-and-no-other
  ;; docs/merging.md#verdicts-are-a-cache: a test takes each key's value, so what a branch states of one key moves the tests reading it, and leaves the rest and the place keys alone
  (let [form (fn [nm k]
               (fn [db] (program/record-tx db {:id (str "app.k-test/" nm) :src (str "(deftest " nm " (seon.config/value " k "))") :ns 'app.k-test :ns-form? false
                                               :defs [{:var (symbol "app.k-test" nm) :meta #{:test}}] :keywords #{k}
                                               :declares #{} :methods [] :impls [] :refs '#{seon.config/value} :requires #{} :banned #{} :inline-defs #{}})))
        db (mt/with (mt/world)
             (form "reads-a" :seon.config.root/view-window)
             (form "reads-b" :seon.config.root/stop-ms))
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
        tasks (str (System/getProperty "java.io.tmpdir") "/seon-agent-test-" tns "-tasks")
        _ (.mkdirs (java.io.File. tasks))
        decls (if (fn? decls) (decls names) decls)
        with-config #(cond-> % config (assoc :seon.task/config config))
        task-decls (mapv with-config (into [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs proofs}] decls))
        task-names (mapv :seon.task/name task-decls)
        _ (spit (str tasks "/" tns ".edn") (pr-str task-decls))
        args (into (cond-> ["--set" (str ":seon.config.source/root=" (pr-str checkout))]
                     dirs (into ["--set" (str ":seon.config.source/dirs=" (pr-str dirs))]))
                   (mapcat (fn [x] ["--set" x])) sets)
        ;; the shard's shared root for the default arguments is a shard fixture, started before the shard's first test
        root (if (= args (ts/default-root-args)) @ts/shared-root-default (ts/shared-root! args))
        ask (fn [m] (ts/ask root m))
        eval-root (fn [code] (ask {:op :eval :agent "root" :code code}))
        made (volatile! nil)
        ;; what the test made goes with it, however it ended: its agent (killed, its end awaited), its tasks
        ;; (cancelled) and their branches
        end! (fn []
               (let [agent @made]
                 (seon.lib/remove-tree! tasks)
                 ;; its end awaited as the fact kill! answers once it is recorded (at once for one already ended), never an end
                 ;; event, which a teardown could miss and wait on forever (Context's 300 s shard, 2026-11-12)
                 (cond (:port root) (eval-root (str "(let [a " (when agent (str ":" agent)) "]"
                                                    " (when-let [v (resolve 'ready-ev)] (clojure.core.async/untap (:events-mult sys) @v))"
                                                    " (when a @(c/kill! sys a))"
                                                    ;; its tasks cancelled: a run left :running keeps the reconciler from
                                                    ;; ever being quiet, and a later test's await-quiet waits on it
                                                    ;; exactly the ids this test made (its task file's names): a query of every task id on
                                                    ;; the shared main cost each teardown more than the last (O(every test so far), in the
                                                    ;; shared root's one REPL)
                                                    " (my.root/cancel! sys " (pr-str (mapv #(str tns "/" %) task-names)) ")"
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
               ;; its hello, or its end (refused, died) first: an agent that ended answers the test with that end, never a wait
               ;; for a hello that cannot come until the test's bound (Optimization's reading of batch 90, 2026-11-12)
               :else (let [came (value-of (:response (eval-root (str "(loop [] (let [e (clojure.core.async/<!! ready-ev)] (if (and (#{:ready :ended :dead :killed} (:type e)) (= :" agent " (:agent e))) (select-keys e [:type :error]) (recur))))"))))]
                       ;; the end whole, its died error with it (exit, stderr's tail): a death before the hello is named
                       (if (= :ready (:type came))
                         (f {:names names :ask ask :eval-root eval-root :root root})
                         {::agent-ended-before-ready {:agent agent :end came}}))))
          (finally (vreset! stopped (end!))))]
    (when-let [a (and (map? out) (::attach-made-no-agent out))]
      (throw (ex-info (str "in-agent: the attach made no agent: " (pr-str a))
                      {:attach a})))
    (when-let [e (and (map? out) (::no-root out))] (throw (ex-info (str "with-agent: the root did not listen: " (pr-str e)) e)))
    (when-let [e (and (map? out) (::agent-ended-before-ready out))]
      (throw (ex-info (str "with-agent: the agent ended before its hello: " (pr-str e)) e)))
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

(defn- spawn-kept-code
  "Root-REPL code: spawn an agent onto the kept branch kept (made by pre, code run with m, root's main connection, and
  b, the branch's name) and answer (on-ready ag) at its :ready, or its end's type; the agent ended after."
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [kept pre on-ready]
  (str "(let [ev (seon.system/events sys) m (seon.db/conn-main (:conn sys)) b (seon.db/on-main m " kept ")]"
       " " pre
       " (let [ag (seon.system/spawn! sys {:branch " kept "})"
       "       r (loop [] (let [e (clojure.core.async/<!! ev)]"
       "                    (cond (not= ag (:agent e)) (recur)"
       "                          (= :ready (:type e)) " on-ready
       "                          (#{:dead :ended :killed} (:type e)) (:type e)"
       "                          :else (recur))))]"
       "   (clojure.core.async/untap (:events-mult sys) ev)"
       "   @(c/kill! sys ag)"
       "   r))"))

;; docs/merging.md#the-test-service: the one call proves a reference (Sean, 2026-11-11: "seon.test/prove #commit id or
;; whatever we are passing in to signify the branch or point in time and that runs it"). Wrong if a reference at an earlier
;; commit answers the head's verdicts: a past point always forks, onto a branch made at that commit, which loads its program
;; docs/merging.md#the-test-service, flow's review (task seon.prove/a-test-batch-holds-the-processes-it-is-weighed-at): the service
;; weighs a granted batch at one process and the :process owner admits it so, but the agent dealt it into up to
;; :seon.config.proofs/jobs children, two schedulers, ~72 processes against a budget of 20 [H]. A granted batch (one asked with its
;; items) runs in one child, the service its one scheduler; a run of the agent's own (no items) still deals what its work is worth.
;; Red before: the deal ignored whether the service had granted it. Estimate: pure, a few calls.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-granted-batch-holds-the-processes-it-was-weighed-at
  (let [big (* 100 (seon.config/value :seon.config.proofs/child-min-ms))]
    (is (= 1 (agent/child-count big true)) "a batch the service granted: one child, as it was weighed")
    (is (= (seon.config/value :seon.config.proofs/jobs) (agent/child-count big false)) "the agent's own run: what the work is worth, up to jobs")
    (is (= 1 (agent/child-count 1 false)) "and one child when the work is small")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 5 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2750k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 5}} a-reference-at-an-earlier-commit-is-proved-at-that-point
  (let [[head past] (with-agent {}
                      (fn [{:keys [names eval-root]}]
                        (let [a (:agent names) ev (comp root-value eval-root)
                              sub (fn [code] (ev (str "(:value (last @(c/submit! sys :" a " " (pr-str code) " {:origin :outside})))")))
                              head-of (str "(seon.db/head (:conn sys) (:branch (get-in (c/state sys) [:agents :" a "])))")]
                          (sub "(ns pt.atref (:require [clojure.test :refer [deftest is]])) (defn f [] 1) (deftest f-is-one (is (= 1 (f))))")
                          ;; the commit that holds the green: an agent commits a request's writes before it replies
                          (let [c (ev (str "(datahike.api/commit-id " head-of ")"))]
                            (sub "(in-ns 'pt.atref) (defn f [] 2)")
                            [(ev (str "(vec (:red @(c/prove! sys (c/agent-ref sys :" a ") '#{pt.atref})))"))
                             (ev (str "(select-keys @(c/prove! sys (assoc (c/agent-ref sys :" a ") :commit #uuid " (pr-str (str c)) ") '#{pt.atref}) [:red :pass :ran :seon.error/message])"))]))))]
    (is (= '[pt.atref/f-is-one] head) (str "the head is red: " (pr-str head)))
    (is (and (empty? (:red past)) (pos? (:pass past 0))) (str "the earlier commit is proved as it stood, green: " (pr-str past)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 5 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2750k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 5}} a-tests-request-is-answered-while-an-eval-blocks
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
              [(ev (str "(deref (future @(c/prove! sys (c/agent-ref sys :" a ") #{} :turn)) 60000 :none)"))
               (do (ev (str "(c/interrupt! sys :" a ")"))
                   (ev "(clojure.string/starts-with? (:seon.error/message (:ex @blocked)) \"interrupted by root\")"))
               (ev (str "(:value @(c/submit! sys :" a " \"(+ 2 2)\" {:origin :outside}))"))])))]
    (is (and (map? proved) (contains? proved :pass)) (str "the run answered while the eval blocked: " (pr-str (if (map? proved) (select-keys proved [:pass :fail :error :ran]) proved))))
    (is (true? stopped) (str "the blocked eval answers interrupted by root: " stopped))
    (is (= 4 next-answer) (pr-str next-answer))))

;; Estimate: 3 socketpairs, forks and reaps (~3 x 20k ticks: 18 setsockopt/fcntl calls, a line read, a recv peek, close) + 3 handlers installed
;; (~3 x 5k) = ~100k, 150000 (the child's ticks are its own and not counted here). The root and the fork server this replaced (own root started and killed, 4M) are not the goal: the goal is the
;; handler an agent installs (agent.clj, root gone: (intr/exit-at-eof! #(sock/peer-gone? ...))) ending its process.
(deftest ^{:seon.test/estimate {:ticks 150000}} an-agent-ends-when-its-root-is-gone
  ;; an agent ends on EOF of its own socket to root, whatever it is doing: an idle agent reads it; one evaluating a form with no
  ;; tick budget is interrupted by it (SIGIO); one blocked in a channel take, whose condition-wait reaches no safe point, is woken
  ;; by it too. Each is a fork of this process holding one end of a socketpair and the handler an agent installs
  ;; (seon.intr/exit-at-eof! over seon.sock/peer-gone?); the parent hangs up its end and reaps the child: exit 0 is the handler's.
  ;; No boot of a root, no fork server (was 60 s warm: a root and two agents started and killed). Not covered here: root's fork
  ;; server group kill (a-proof-run-ends-with-its-agent and the root-ended tests cover its processes). Red: a handler exiting non-0
  (let [forks (fn [wait]
                (let [[root-end agent-end] (sock/socketpair)
                      pid (lib/fork! {:fd agent-end :refused (fn [n] {:refused n}) :parent identity
                                      :child (fn []
                                               (is (nil? (sock/signal-at-input! agent-end)))
                                               (intr/exit-at-eof! #(sock/peer-gone? agent-end) false)
                                               (sock/write-line! agent-end "ready")
                                               (wait agent-end)
                                               (lib/c-exit 3))})]
                  (let [rd (sock/line-reader root-end (fn [] (a/<!! (jolt.io-poller/ready-chan root-end :read))))
                        ready (loop [] (let [l (rd)] (cond (= l :eof) nil (seq l) (first l) :else (recur))))]
                    (lib/c-close root-end)
                    {:ready ready :status (lib/wait-status pid)})))
        idle (forks (fn [fd] (a/<!! (jolt.io-poller/ready-chan fd :read))))
        busy (forks (fn [_] (intr/run nil (fn [] (loop [] (recur))))))
        blocked (forks (fn [_] (a/<!! (a/chan))))]
    (doseq [[what r] [["idle" idle] ["busy" busy] ["blocked" blocked]]]
      (is (= "ready" (:ready r)) (str "the " what " child began its wait: " (pr-str r)))
      (is (= {:exit 0} (:status r)) (str "the " what " child ended when its peer closed: " (pr-str r))))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded); 2 shell calls (mkfifo, rm) at ~200k each = ~2650k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-proof-run-ends-with-its-agent
  ;; the agent's proof run (a fork of it) holds
  ;; the write end of a fifo and sleeps, a test that never writes to the agent's pipe; the
  ;; agent is killed (its task cancelled); the proof run ends with it, on the kernel's word that
  ;; the reader of its pipe is gone (SIGIO). The test reads the fifo: its first byte says the
  ;; proof run is in its test, its EOF that nothing holds the fifo any more, the proof run gone.
  ;; No clock decides: a run that outlives its agent wakes from its sleep (a minute) and writes a
  ;; second byte, which fails the test.
  (let [fifo (str (System/getProperty "java.io.tmpdir") "/seon-agent-test-proof-ended-" (random-uuid) ".fifo")
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

;; See docs/reconcile.md#a-task-holds-how-its-work-ended
;; Estimate: 3 evals at ~0.3M on the world (built once a process, ~3.2M) = ~3M
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-wake-that-ran-no-turn-has-no-end
  ;; task seon.reconcile/a-wake-that-ran-no-turn-has-no-end: a turn moves only inside a turn, so a wake whose work! threw before
  ;; its first turn read the previous wake's end as its own: an old :waiting parked its task again and counted the message
  ;; that woke it as taken, and the throw's error lost to the old end's
  (let [_ '[seon.reconcile/pull-end seon.turn/work! seon.agent/transact!] ; run by string below, named so a change to them reruns this test
        end? "(seon.reconcile/pull-end (seon.agent/db) (:key @seon.agent/state))"
        [before threw after]
        (mt/eval-in [(str "(do (seon.agent/transact! [{:seon.agent/key (:key @seon.agent/state) :seon.agent/outcome :waiting :seon.agent/reason \"the wake before\""
                        " :seon.agent/ended-turn (:seon.agent/turn (d/entity (seon.agent/db) [:seon.agent/key (:key @seon.agent/state)]) 0)}]) " end? ")")
                   "(seon.turn/work! {:task \"no/such-task\" :max-turns 9})"
                   end?])]
    (is (= :waiting (:outcome (:value before))) (str "the wake before ended waiting: " (pr-str before)))
    (is (:ex threw) (str "this wake threw before its first turn: " (pr-str threw)))
    (is (nil? (:value after)) (str "and it wrote no end, so root reads none: " (pr-str after)))))

;; See docs/merging.md#a-test-child-is-a-fork-of-a-live-agent
;; Estimate: evals on a world (built once a process, ~3.2M) at ~0.3M each; measured a few M
(deftest ^{:seon.test/estimate {:ticks 25000000}} an-eval-that-empties-the-agents-state-leaves-it-working
  ;; a test run at an agent's REPL ended by resetting the state it had taken for its own (seon.ctx-test's end-agent!):
  ;; every later call was seon.agent/db's nil, and the agent died at its next prove (the control of 2026-11-05)
  (let [_ '[seon.repl/page-walk] ; run by string below, named so a change to them reruns this test
        [gone after page] (mt/eval-in ["(do (reset! seon.agent/state nil) :emptied)" "(+ 1 2)" "(some? (seon.repl/page-walk))"])]
    (is (= :emptied (:value gone)) (pr-str gone))
    (is (= 3 (:value after)) (pr-str after))
    (is (true? (:value page)) (pr-str page))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 5 asks of the test's own at ~0.25M (an eval's recorded answer decoded); 3 shell calls (2 mkfifo, rm) at ~200k each = ~3350k, 4000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 5}} an-agent-answers-while-its-tests-run
  ;; task seon.turn/observe-reads-are-renders (phase B): root's :tests request forks the test child
  ;; and a fiber reads it, so the agent's loop answers an eval while its own tests run. The test
  ;; child holds in its test until this test lets it go (a fifo, no clock): the eval is answered
  ;; while it holds, then the run answers green
  (let [base (str (System/getProperty "java.io.tmpdir") "/seon-agent-test-answers-" (random-uuid))
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
                  _ (ev (str "(def held-run (future @(c/prove! sys (c/agent-ref sys :" a ") #{} :turn)))"))
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

;; See docs/merging.md#a-test-child-is-a-fork-of-a-live-agent
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 2}} a-test-child-is-a-proc-of-the-agents-graph
  ;; the one-unit spec, fork-one step 2: a test child is forked by jolt.async.remote.flow/fork! as a proc of the agent's own
  ;; Flow graph, its values folded by a proc of that graph, never read from a hand-made pipe. The graph's procs are read
  ;; when the child's pid is told (forked: the child is running) and after the run: the child and its run's fold are
  ;; procs of the graph while the run is in flight, and both are gone once it has answered
  (let [[_ r] (in-agent ["(ns app.proc-test (:require [clojure.test :refer [deftest is]])) (deftest a (is (= 1 1)))"
                         (str "(let [procs #(set (keys (:procs (clojure.datafy/datafy (:flow @seon.agent/state)))))"
                              "      before (procs) during (atom nil)"
                              "      r (seon.agent/run-tests-forked 100000000 #{} :main (fn [_] (reset! during (procs))) '#{app.proc-test})]"
                              "  (pr-str {:pass (:pass r) :added (count (remove before @during)) :after (= before (procs))}))")])
        v (some-> (:value r) clojure.edn/read-string)]
    (is (= {:pass 1 :added 2 :after true} v) (str "the child and its fold were procs while it ran, gone after: " (pr-str r)))))

(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 40000000 :asks 2}} a-test-childs-run-ends-at-its-exit-though-it-forked-a-grandchild
  ;; agent a3 waited 3.5 h on main (2026-10-26) for its test child's end, which never came: root's :tests in flight,
  ;; the agent at 0% CPU. A process the test child forked held the child's end of its link past the child's exit. A
  ;; forked child holds only what it is given (docs/processes.md#a-child-holds-what-it-is-given), so the grandchild
  ;; here, a command started by the test (tail -f /dev/null, which waits for good) holds no copy of the child's link,
  ;; and the child's exit ends the run. A regression waits for ever (no clock may end it): a hang is this test's red
  (let [pidf (str (System/getProperty "java.io.tmpdir") "/seon-agent-test-grandchild-" (random-uuid))
        [_ r] (in-agent [(str "(ns app.gc-test (:require [clojure.test :refer [deftest is]]))"
                              " (deftest forks (let [pid (.pid (.start (ProcessBuilder. ^java.util.List [\"tail\" \"-f\" \"/dev/null\"])))]"
                              "                  (spit " (pr-str pidf) " (str pid)) (is (pos-int? pid))))")
                         "(pr-str (select-keys (seon.agent/run-tests-forked 100000000 #{} :main (fn [_] nil) '#{app.gc-test}) [:pass :fail :error]))"])
        gc (some-> (ts/output pidf) parse-long)
        alive (when gc (lib/kill! gc 0))]
    (when gc (lib/kill! gc (jolt.host/signal-number "KILL")))
    (.delete (java.io.File. ^String pidf))
    (is (= {:pass 1 :fail 0 :error 0} (some-> (:value r) clojure.edn/read-string)) (pr-str r))
    (is (true? alive) "the grandchild was still waiting when the run answered")))
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded); 1 shell call (mkfifo) at ~200k = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} two-test-runs-at-once-each-read-their-child-to-its-end
  ;; docs/processes.md#a-child-holds-what-it-is-given: an agent runs the test service's items at once, each a run of its own
  ;; (seon.repl/serve-item!); a child of one run kept the other run's pipe end, so that run's end of file waited on the
  ;; other child and it answered a :pipe-held error with no report (bin/check agent-checks, 2026-11-12). Here run a's test
  ;; parks on a fifo until run b's test has run and opened it, so a's child is alive the whole time b's pipe is; each run
  ;; answers its own test, green, and nothing else. A regression waits for ever on b's end of file (no clock may end it).
  ;; A run that answers first and is not green (its fork refused, :seon.test/fork-refused naming its holders) never ran
  ;; its end of the fifo, so the test opens that end itself: the other run ends red, and the first one's answer is said
  (let [fifo (str (System/getProperty "java.io.tmpdir") "/seon-agent-test-two-runs-" (random-uuid))
        _ (clojure.java.shell/sh "mkfifo" fifo)
        nss ["(ns app.twa-test (:require [clojure.test :refer [deftest is]])) (deftest waits (is (= \"b ran\" (slurp " (pr-str fifo) "))))"
             "(ns app.twb-test (:require [clojure.test :refer [deftest is]])) (deftest runs (spit " (pr-str fifo) " \"b ran\") (is (= 1 1)))"]
        run (str "(let [go (fn [n] (clojure.core.async/io-thread (seon.agent/run-tests-forked 100000000 #{} :main (fn [_] nil) #{n})))
                   a (go 'app.twa-test) b (go 'app.twb-test)
                   pick (fn [r] (if (contains? r :pass) (select-keys r [:pass :fail :error :reports]) r))
                   [r1 ch] (clojure.core.async/alts!! [a b])
                   _ (when-not (= 1 (:pass r1)) (if (= ch b) (spit " (pr-str fifo) " \"b did not run\") (slurp " (pr-str fifo) ")))
                   r2 (clojure.core.async/<!! (if (= ch a) b a))
                   [ra rb] (if (= ch a) [r1 r2] [r2 r1])]
               (pr-str {:a (pick ra) :b (pick rb)}))")
        [_ _ two] (in-agent [(apply str nss) "nil" run])
        v (some-> (:value two) clojure.edn/read-string)]
    (.delete (java.io.File. fifo))
    (is (= {:a {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var "#'app.twa-test/waits"}]}
            :b {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var "#'app.twb-test/runs"}]}}
           (some-> v (update-vals #(update % :reports (partial mapv (fn [x] (select-keys x [:type :var])))))))
        (pr-str two))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-run-error-that-names-no-test-is-said-in-the-proves-line
  ;; 2026-11-12: agent-checks' first prove read {:error 1} and printed no reason: a run's report naming no test (a child that
  ;; died before a test began, a held pipe) is no test's failure, and the line said "red: 0 of 2 failed" with nothing under it
  (let [run {:pass 2 :fail 0 :error 1 :reports [{:type :pass :var "#'a-test/t"} {:type :pass :var "#'a-test/u"}
                                               {:type :error :exit {:signal 9} :message "test process died: {:signal 9}"}]}]
    (is (str/includes? (:seon.print/code (my.agent/prove-summary {} run)) "✗ the run: test process died")
        "the run's error is said")))

;; Estimate: one deftest eval ~0.2M, two test-vars ~0.1M = ~0.5M
(deftest ^{:seon.test/estimate {:ticks 500000}} an-agent-holds-mains-tests-and-runs-them
  ;; main holds the source root's test namespaces (imported from test/), and the process an agent is forked from holds
  ;; them compiled: a test of main's runs without requiring it, a green one reports green, a red one beside it red.
  ;; In process: this test is itself run in such a fork
  (let [green 'seon.reader-test/text-without-a-fence-is-a-comment
        red 'seon.agent-test.holds/an-agent-made-this-red
        rs (atom {})]
    (binding [*ns* *ns*]
      (eval '(do (ns seon.agent-test.holds) (clojure.test/deftest an-agent-made-this-red (clojure.test/is (= 1 2))))))
    (is (some? (find-ns 'seon.reader-test)) "the process holds main's test namespace without requiring it here")
    (binding [clojure.test/report (fn [m] (swap! rs update (symbol (first clojure.test/*testing-vars*)) (fnil conj #{}) (:type m)))]
      (clojure.test/test-vars [(resolve green) (resolve red)]))
    (remove-ns 'seon.agent-test.holds)
    (is (and (contains? (@rs green) :pass) (not (contains? (@rs green) :fail))) "main's test runs green")
    (is (contains? (@rs red) :fail) "and a red one beside it reports red")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} root-and-its-agents-hold-mains-tests-from-the-binary
  ;; docs/source.md#the-binary-holds-the-tests-compiled: the binary holds test/ compiled, once, in the image
  ;; every process shares, so root holds main's tests and the agent forked from it does too, with no load at runtime
  ;; (and a test child it forks runs them: an-agent-holds-mains-tests above)
  (let [;; the code this proves, named so its reach digest moves with it: root's boot and writer, the fork server's load
        _ [#'seon.system/boot! #'seon.writer/main-commit #'seon.fork-server/main]
        t "(some-> (resolve 'seon.reader-test/text-without-a-fence-is-a-comment) bound?)"
        [agent-held root-held facts] (in-agent [(str "(boolean " t ")")
                                                {:op :eval :agent "root" :code (str "(boolean " t ")")}
                                                {:op :eval :agent "root" :code "(boolean (seon.db/eid @(:conn sys) :var/name 'seon.reader-test/text-without-a-fence-is-a-comment))"}]
                                               {})]
    (is (true? (:value agent-held)) "the agent holds main's test compiled")
    (is (true? (:value root-held)) "and root, from the same image")
    (is (true? (:value facts)) "though main's program holds it as facts")))

(defn- statuses
  "The code of {test status} over the run that code x answers, every test it ran or reused (its
  :digests): an answer carries only its proofs' passes and its reds (seon.proofs/answer), so every
  other test it holds a digest of was green."
  [x] (str "(let [r " x "] (into {} (for [t (keys (:digests r))] [t (get (seon.proofs/results r) t :pass)])))"))

(deftest ^{:seon.test/estimate {:ticks 20000000}} an-agents-test-run-is-its-proofs-and-what-its-change-reaches
  ;; T1, in process on the world: seon.agent/test-plan of main and of a branch of it (two test plans of ~12 forms, ~5 M ticks each, the
  ;; world's schema and index most of it). The selection a run reads is the task's proofs and the tests reaching what its
  ;; branch changed since the main it forked from. One redefined function (same behaviour, another form) reaches the tests
  ;; that call it; its namespace's other tests and every other namespace's do not. The tests are vars of this process too
  ;; (seon.agent/judges? asks the process), loaded here as the agent loads its program
  (let [proof 'pt.rc-other-test/other-is-one
        calc "(ns pt.rc)\n(defn add [x y] (+ x y))\n(defn other [] 1)\n"
        calc-test "(ns pt.rc-test (:require [clojure.test :refer [deftest is]] [pt.rc :as c]))\n(deftest adds (is (= 3 (c/add 1 2))))\n(deftest others (is (= 1 (c/other))))\n"
        other-test "(ns pt.rc-other-test (:require [clojure.test :refer [deftest is]]))\n(deftest other-is-one (is (= 1 1)))\n(deftest other-too (is (= 1 1)))\n"
        main (mt/with (mt/world) (mt/code 'pt.rc calc) (mt/code 'pt.rc-test calc-test) (mt/code 'pt.rc-other-test other-test))
        branch (mt/with main (mt/code 'pt.rc "(ns pt.rc)\n(defn add [x y] (+ y x))\n(defn other [] 1)\n"))]
    (try (doseq [t [calc-test other-test]] (binding [*ns* (create-ns 'user)] (load-string t)))
         (let [plan (agent/test-plan branch main #{proof})]
           (is (= '#{pt.rc-other-test/other-is-one pt.rc-test/adds} (set (:selected plan)))
               "the proof and the test reaching the change; nothing else (not its namespace's other test, not another namespace's)"))
         (finally (run! remove-ns '[pt.rc pt.rc-test pt.rc-other-test])))))

;; Estimate: as T1 above, two test plans of ~12 forms (~5M ticks each) and one schema entry written: 20M ticks
(deftest ^{:seon.test/estimate {:ticks 20000000}} a-branch-that-changes-no-schema-selects-no-test-for-its-schemas
  ;; base and branch are digested under the same derivation of their inputs (seon.agent/branch-inputs, the schema entries each holds
  ;; beyond the binary's: seon.program/schema-changes): with the base under the boot's inputs and the branch under its own, every
  ;; test that reads the schemas (:seon.env/schemas, ~1,150 of 2,128) read as changed by every branch while main held one entry the
  ;; binary does not (a branch changing one function selected 2,063 tests against the 739 its change reaches, 2026-11-15)
  (let [calc "(ns pt.rs)\n(defn add [x y] (+ x y))\n"
        calc-test "(ns pt.rs-test (:require [clojure.test :refer [deftest is]] [pt.rs :as c]))\n(deftest adds (is (= 3 (c/add 1 2))))\n"
        schema-test "(ns pt.rs-schema-test (:require [clojure.test :refer [deftest is]] [seon.db]))\n(deftest reads-the-schemas (is (var? #'seon.db/entity-index)))\n"
        main (mt/with (mt/world) (mt/code 'pt.rs calc) (mt/code 'pt.rs-test calc-test) (mt/code 'pt.rs-schema-test schema-test))
        ;; a schema entry main holds that the binary does not (registered, merged, or imported ahead of the binary)
        main (d/db-with main [{:schema/key :pt.rs/thing :schema/form "[:map [:pt.rs/x :int]]"}])
        branch (mt/with main (mt/code 'pt.rs "(ns pt.rs)\n(defn add [x y] (+ y x))\n"))]
    (is (seq (program/schema-changes main)) "main holds a schema entry beyond the binary's")
    (try (doseq [t [calc-test schema-test]] (binding [*ns* (create-ns 'user)] (load-string t)))
         (let [plan (agent/test-plan branch main #{})]
           (is (= '#{pt.rs-test/adds} (set (:selected plan)))
               "the test reaching the change, and not the test that reads the schemas, which the branch did not change"))
         (finally (run! remove-ns '[pt.rs pt.rs-test pt.rs-schema-test])))))

;; Estimate: two test-plans of the world at ~3M each = ~6M
(deftest ^{:seon.test/estimate {:ticks 12000000}} a-plan-with-no-base-costs-what-a-plan-with-one-does
  ;; a plan that selects with no base (mode :main: the test service's run of main, and the tests that run as it
  ;; does) builds its reach graph from the agent's base as any plan does: built from nothing it allocated 853 MB
  ;; against an update's 290 (2026-10-30). On a world and a branch of it, in a state that holds no graph of the real program (eval-in's),
  ;; each plan measured with the held graphs let go first, by the bytes this thread allocated (estimate: four small worlds' graphs ~3M ticks each)
  (let [world (mt/world)
        branch (mt/with world (mt/code 'pt.nb "(ns pt.nb)\n(defn f [] 1)\n"))
        bytes (fn [base] (swap! agent/state dissoc :branch-graph :base-graph)
                (let [a (seon.profile/alloc-bytes)] (agent/test-plan branch base #{}) (- (seon.profile/alloc-bytes) a)))
        [r] (mt/eval-in [(fn [_] (let [with (bytes world) without (bytes nil)] {:with with :without without :ratio (double (/ without with))}))])
        v (:value r)]
    (is (and (map? v) (< (:ratio v) 1.5)) (str "no base costs about what a base does: " (pr-str v)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-death-costs-the-test-it-interrupted-and-no-other
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 5 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2750k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 5}} a-test-run-is-dealt-across-children-and-loses-none
  ;; task seon.agent/a-test-run-streams-its-reports (the deal): the selected tests are dealt by seon.proofs/shards into
  ;; :seon.config.proofs/jobs test children, each its own fork; every test reports once, and a death in one child costs
  ;; only the test it ran. Sixteen tests of four namespaces and one that dies: at the default :seon.config.proofs/child-min-ms they are worth two children or more (a-handful-of-tests-runs-in-one-child is the bound's other side).
  ;; And a child forked after a sibling holds no copy of the sibling's pipe,
  ;; whose end a child must never keep (the agent's death would not reach the sibling by SIGIO while any copy is open). Each
  ;; namespace's first test counts the descriptors open in its child (fcntl F_GETFD) and writes the count to a file: a child
  ;; holding a sibling's pipe end has one more than the child forked before it
  (let [dir (str (System/getProperty "java.io.tmpdir") "/seon-agent-test-fds-" (random-uuid))
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
    (seon.lib/remove-tree! dir)
    (is (every? pos-int? counts) (str "every child counted: " (pr-str counts) " " (pr-str two)))
    (is (apply = counts) (str "the children hold the same descriptors: " (pr-str counts)))
    (is (<= 2 (:children (v two))) "at least two children forked (a death forks a new one for what its child had left)")
    (is (= [16 1 ["#'app.par3-test/dies"]] ((juxt (comp count :ran) :error :died) (v two))) "all sixteen passed once, and the death cost only the test it ran")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-message-sent-while-the-agent-waits-on-its-tests-reaches-its-inbox
  ;; task seon.agent/an-agent-has-one-wait: prove! waits for root's answer on the same wait as
  ;; every other ask (await!), so a delivery read there lands in the inbox, as when idle
  (let [[sent proved taken] (in-agent #(vector {:op :message :agent (:agent %) :body "while proving"}
                                               "(select-keys (prove) [:results])"
                                               "(my.agent/messages)"))]
    (is (true? (:ok sent)))
    (is (map? (:value proved)))
    (is (re-find #":body \"while proving\"" (:text taken)) (:text taken))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 4 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2500k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 4}} one-bound-one-verdict-and-the-judge-reruns-no-green
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-test-run-leaves-nothing-in-its-agents-working-directory
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

(defn- store-verdict
  "Agent code writing test t's verdict at the digest digest-code evaluates to, as a run on branch (branch-code) would have
  written it: result ({:pass :fail :error}, with :failures for a red), through the prove proc's :verdicts request, the one
  writer of the results branch (seon.prove/verdicts-request). See docs/merging.md#verdicts-are-a-cache."
  [t digest-code result branch-code]
  (str "(let [db (seon.agent/db) [c tt] (seon.db/point db) d " digest-code
       " vt (seon.proofs/verdict-tx db (:graph (seon.agent/test-plan db nil #{'" t "})) {:seon.db/branch " branch-code " :seon.db/commit c :seon.db/t tt}"
       " {'" t " (assoc '" (pr-str result) " :digest d)} {} {})"
       " k (:tx-seq (swap! seon.agent/state update :tx-seq inc))]"
       " (:result (seon.agent/await-reply k {:op :prove :k k :verdicts vt})))"))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 7 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~3250k, 4000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 7}} a-red-at-its-digest-is-reused-with-its-failures
  ;; task seon.proofs/a-red-at-its-digest-is-reused: a run on this branch stored r red at its digest (the results branch); r is green
  ;; if it runs, so a run would say :pass. The judge reuses the red, with the failure its run
  ;; cached, and forks nothing. Another's red is not reused: a digest does not see the machine, and it runs
  (let [[_ dg _ judge _ _ other]
        (in-agent ["(ns reds-test (:require [clojure.test :refer [deftest is]]))
                   (deftest r (is true))"
                   "(get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/r})) 'reds-test/r)"
                   (store-verdict 'reds-test/r "(get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/r})) 'reds-test/r)"
                                  {:pass 0 :fail 1 :error 0 :failures [{:type :fail :message "the recorded failure" :expected "1" :actual "2"}]}
                                  "(:branch @seon.agent/state)")
                   "(let [r (seon.agent/run-tests-forked 100000000 #{'reds-test/r} :judge)]
                      [(seon.proofs/results r) (:ran r) (:reused r) (some? (:pid r)) (:fail r) (mapv (juxt :message :reused) (:reports r))])"
                   "(do (deftest other-red (is true)) :ok)"
                   (store-verdict 'reds-test/other-red "(get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/other-red})) 'reds-test/other-red)"
                                  {:pass 0 :fail 1 :error 0 :failures [{:type :fail :message "another's failure" :expected "1" :actual "2"}]}
                                  ":another")
                   "(let [r (seon.agent/run-tests-forked 100000000 #{'reds-test/other-red} :judge)] [(get (seon.proofs/results r) 'reds-test/other-red) (:ran r)])"]
                  {:proofs #{}})]
    (is (string? (:value dg)) (pr-str dg))
    (is (= ['{reds-test/r :fail} 0 1 false 1 [["the recorded failure" true]]] (:value judge))
        (str "red as recorded, with its failure, nothing run and no child forked " (pr-str judge)))
    (is (= [:pass 1] (:value other)) (str "another's red runs, and is green: " (pr-str other)))))

;; Estimate: as its sibling below (in-agent and three asks, a test child forked), ~20M
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 20000000 :asks 3}} a-test-child-holds-its-branchs-config-never-its-parents
  ;; the owner, 2026-11-14: \"Why does it matter what the config is that we are forking from\": a process is handed its branch's
  ;; environment and its first act is to hold it (seon.agent/hold-env!), whatever its parent held. A test child was a fork of its
  ;; agent, itself a fork of root, so it held root's own layers and variables: here the shared root's source root (its --set and
  ;; SEON_SOURCE_ROOT), as the standing root's gave the first real task's agent's tests (seon.source-test's red in context's agent
  ;; nae67b62, 2026-11-14). A test process holds a test's layers (--profile scripted, no local layer) and variables over its
  ;; branch's config entries, as a bin/test shard does
  (let [[_ run] (in-agent ["(ns env-test (:require [clojure.test :refer [deftest is]])) (deftest no-parents-layers (is (nil? (get seon.config/config :seon.config.source/root))) (is (= :scripted (seon.config/profile))))"
                           "(select-keys (my.agent/prove 'env-test/no-parents-layers) [:results])"]
                          {:proofs #{'env-test/no-parents-layers}})]
    (is (= {:results '{env-test/no-parents-layers :pass}} (:value run)) (str "the test child holds a test's environment, not root's: " (pr-str run)))))

;; Estimate: in-agent's setup and teardown (~1.5M) + 5 asks of the test's own (~0.25M each: an import is two reads of one
;; one-form file and one branch write, a lookup; a page's import one read and one draw, measured 2.9M with the doc test beside it) = ~3M
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 5}} a-sessions-edit-is-imported-onto-its-agents-branch
  ;; plan Stage 1 (a session's files as its branch's transaction; agreed by context, data and runtime): (my.branch/import! root
  ;; paths) at the agent's REPL reads the files a session edited and writes what changed onto the agent's branch through its own
  ;; writer, the shape an eval writes (seon.system/catch-up :fact? false :paths); main never moves. The goal, data's: the edit is on
  ;; the branch as forms, not on main, and the same edit again is unchanged
  (let [dir (str (System/getProperty "java.io.tmpdir") "/seon-agent-test-import-" (random-uuid))
        path "src/seon/import_probe.clj"
        _ (.mkdirs (java.io.File. (str dir "/src/seon")))
        _ (spit (str dir "/" path) "(ns seon.import-probe)\n\n(defn probe [] 3)\n")
        ;; the agent evaluates it from text, which no reach reads: named here, so a change to it reruns this test
        _ my.branch/import!
        imp (str "(my.branch/import! " (pr-str dir) " [" (pr-str path) "])")]
    (try
      (let [[first-r on-branch on-main again page]
            (in-agent [imp
                       "(some-> (datahike.api/entity (seon.agent/db) [:var/name 'seon.import-probe/probe]) :var/form :form/src)"
                       "(some? (seon.db/eid (seon.agent/heard-main) :var/name 'seon.import-probe/probe))"
                       imp
                       ;; a page the branch holds byte for byte (seon.doc/files draws it as its file): unchanged, never imported again
                       "(my.branch/import! (seon.config/checkout) [\"docs/prompt.md\"])"])]
        (is (= {:imported [path] :unchanged [] :skipped []} (:value first-r)) (pr-str first-r))
        (is (str/includes? (str (:value on-branch)) "(defn probe [] 3)") (str "on the agent's branch as forms: " (pr-str on-branch)))
        (is (false? (:value on-main)) "not on main: main moves only by the merge")
        (is (= {:imported [] :unchanged [path] :skipped []} (:value again)) (str "the same edit again is unchanged: " (pr-str again)))
        (is (= {:imported [] :unchanged ["docs/prompt.md"] :skipped []} (:value page)) (str "a page as the branch holds it is unchanged: " (pr-str page))))
      (finally (seon.lib/remove-tree! dir)))))

(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 20000000 :asks 3}} an-agents-green-records-what-it-read
  ;; docs/source.md#tests-run-by-reach: a green an agent's run stores is keyed by what it read (seon.proofs/reads-key): the
  ;; functions it called and itself, at the digests of the agent's reads index, beside its verdict as reads.edn, so a later plan
  ;; reuses it while none of those moved (seon.proofs/read-digests). Red before: an agent's run wrote no reads
  (let [[_ green reads] (in-agent ["(ns reads-test (:require [clojure.test :refer [deftest is]])) (defn f [x] (inc x)) (deftest r (is (= 2 (f 1))))"
                                   "(select-keys (my.agent/prove 'reads-test/r) [:results])"
                                   "(some-> (get (seon.fx/held-reads ['reads-test/r]) 'reads-test/r) first :reads keys set)"]
                                  {:proofs #{'reads-test/r}})]
    (is (= {:results '{reads-test/r :pass}} (:value green)) (pr-str green))
    (is (contains? (set (:value reads)) 'reads-test/r) (str "its green names what it read, itself among them: " (pr-str reads)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 7 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~3250k, 4000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 7}} a-test-run-writes-its-results-on-the-branch
  ;; task seon.proofs/a-verdict-is-an-entity-that-names-its-environment (R2a): an agent's run of its proofs records each
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

;; Estimate: in-agent's own setup and teardown (~1.5M, as a-test-run-writes-its-results-on-the-branch's) + 3 asks of the test's own
;; at ~0.25M (an eval's recorded answer decoded) = ~2.25M; the agent's run and its write are the agent process's work, not this test's
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} an-agents-run-leaves-its-results-on-its-branch
  ;; docs/merging.md#verdicts-are-a-cache, task seon.proofs/a-test-forks-from-the-standing-system-and-a-binary-test-reruns-only-for-its-binary
  ;; (E) slice 2a: an agent is its branch's one writer, so its run's results are :seon.proof entities on its own branch (seon.agent's
  ;; record-results!, from seon.proofs/verdict-tx), each with its status, what it read and the branch it ran on; main holds none
  ;; until the branch merges; written again, a result is no new one. What it read holds the schema entries its body read, which
  ;; the test child records and sends with its end (seon.agent/take-value keeps :schema-reads; g builds the entry's key at run time,
  ;; so no static reach names it). Red before: the branch held none
  ;; (the results branch only); then, with the child's :schema-reads dropped, g's reads lacked the schema entry
  (let [results "(let [at (fn [db t] (when-let [e (:e (first (datahike.api/datoms db :avet :seon.proof/test t)))] (datahike.api/entity db e)))]
                   {:branch (into {} (for [t '[ran-test/g ran-test/r] :let [p (at (seon.agent/db) t)] :when p]
                                       [t (cond-> {:status (:seon.proof/status p) :reads? (boolean (seq (:seon.proof/read-keys p)))
                                                   :mine? (contains? (set (:seon.proof/ran-on p)) (:branch @seon.agent/state))}
                                            ;; the schema entry g's body read, by its content attribute, among its reads
                                            (= t 'ran-test/g) (assoc :schema-read? (contains? (set (:seon.proof/read-keys p))
                                                                                              (pr-str [:ea (seon.db/eid (seon.agent/db) :schema/key :seon.proof/id) :schema/form]))))]))
                    :main (count (datahike.api/datoms (seon.agent/heard-main) :avet :seon.proof/test 'ran-test/g))})"
        [_ run held again]
        (in-agent ["(ns ran-test (:require [clojure.test :refer [deftest is]])) (deftest g (is (= 1 (count (datahike.api/datoms (seon.agent/db) :eavt (seon.db/eid (seon.agent/db) :schema/key (keyword \"seon.proof\" \"id\")) :schema/form))))) (deftest r (is (= 1 2)))"
                   "(select-keys (my.agent/prove) [:results])" results
                   "(do (my.agent/prove) (count (datahike.api/datoms (seon.agent/db) :avet :seon.proof/test 'ran-test/g)))"]
                  {:proofs '#{ran-test/g ran-test/r}})]
    (is (= {:results '{ran-test/g :pass ran-test/r :fail}} (:value run)) (pr-str run))
    (is (= '{ran-test/g {:status :pass :reads? true :mine? true :schema-read? true} ran-test/r {:status :fail :reads? true :mine? true}} (:branch (:value held)))
        (str "each test the run ran is a result on the agent's branch, with its status, its reads (the schema entry its body read among them, "
             "[:ea e :schema/form], as the test child recorded it: seon.proofs/verdict-tx's :schema-reads) and its branch: " (pr-str held)))
    (is (= 0 (:main (:value held))) "main holds none of it: main moves only by the merge")
    ;; g reads the branch (seon.agent/db), so its digest takes the branch's point and the next prove may run it again: the same
    ;; content (test, reads digest, status) is the same result, so the branch still holds one
    (is (= 1 (:value again)) (str "a result the branch holds, written again, is no new result: " (pr-str again)))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-green-that-is-no-proof-writes-no-fact
  ;; docs/sharing.md#what-a-branchs-nodes-are: a green that is no proof of the task is read by nothing
  ;; on this branch (seon.reconcile/done-explain reads proofs and reds), and its facts on its var rewrite a leaf
  ;; among the program's. A red is held, and stays held when it goes green, so it never reads as still red.
  ;; T1: the agent's record-results! is seon.proofs/facts-worth-keeping then seon.proofs/result-tx, both pure on the
  ;; entities the branch holds; two runs of three tests (a proof, another, one that turns red then green), a few calls each
  (let [digests '{green-test/proof "p" green-test/other "o" green-test/turned "t2"}
        green {:digest "x" :pass 1 :fail 0 :error 0}
        red {:digest "t" :pass 0 :fail 1 :error 0}
        proofs '#{green-test/proof}
        record (fn [held ran failures]
                 ;; what the branch then holds: {test status}, record-results!'s steps on held ({test entity})
                 (let [kept (proofs/facts-worth-keeping held proofs ran)
                       tx (proofs/result-tx held kept failures {} digests)]
                   (into {} (for [m tx :when (map? m)] [(:var/name m) (:seon.test/status m)]))))
        ;; the run that asks the red by name: the proof and the other are not run
        first-facts (record {} {'green-test/turned red} {'green-test/turned [{:type :fail :message "no"}]})
        ;; the branch holds the red; the deftest redefined (a new digest), the run of everything turns it green
        second-facts (record {'green-test/turned {:seon.test/digest "t" :seon.test/status :red}}
                             {'green-test/proof (assoc green :digest "p") 'green-test/other (assoc green :digest "o") 'green-test/turned (assoc green :digest "t2")}
                             {})]
    (is (= '{green-test/turned :red} first-facts) (pr-str first-facts))
    (is (= '{green-test/proof :green green-test/turned :green} second-facts) (pr-str second-facts))))

;; Estimate: calc-db and 2 records ~1.8M, 2 test plans at ~2M, 2 reach-digests at ~1.3M, db-with x3 = ~9000000.
(deftest ^{:seon.test/estimate {:ticks 9000000}} a-proof-over-the-branchs-data-is-keyed-by-its-data-too
  ;; Context's trial finding 14: an agent's proof over its branch's data (lab.proofs/the-tasks-i-cite-differ-from-my-base) ran
  ;; red before the agent's write and green after at one digest, so it was judged flaky and its task refused. Its digest held
  ;; its code and not the data it reads: a test namespace's own call of a branch reader (my.db, seon.agent/db) takes the
  ;; branch's point as one more input in an agent (docs/merging.md#verdicts-are-a-cache); the tests that read no branch,
  ;; and bin/test's digests, which hold no branch, stay as they were
  (let [in {:env "e" :tree "t" :config {} :data-refs {}}
        tests (conj @#'seon.program-test/calc-tests 'app.pt-calc-test/data-test 'app.pt-calc-test/api-test)
        base (d/db-with (#'seon.program-test/calc-db)
                        [{:seon.system/id :root :seon.system/boot "b1" :seon.system/source {:root "/r" :files 0 :forms 0 :ms 0 :boot "b1" :inputs in}}])
        ;; seon.agent/db itself: this program holds no my.* forms, whose readers (my.db/q) branch-readers finds by their calls of it
        ;; and an agent API function calling it (as my.db/q does), which a test calls with no db handed in
        branch (seon.program-test/record base 'app.pt-calc-test "(deftest data-test (is (seon.agent/db)))"
                                         'my.lab-api "(defn branch-db [] (seon.agent/db))"
                                         'app.pt-calc-test "(deftest api-test (is (my.lab-api/branch-db)))")
        written (d/db-with branch [{:seon.system/id :root :seon.system/boot "b1"}])
        agent (fn [db] (select-keys (:digests (agent/test-plan db base tests)) tests))
        checkout (fn [db] (select-keys (ts/reach-digests db in) tests))
        [a b] [(agent branch) (agent written)]]
    (is (not= (get a 'app.pt-calc-test/data-test) (get b 'app.pt-calc-test/data-test)) "the branch's data moved: the proof over it is a new digest")
    (is (not= (get a 'app.pt-calc-test/api-test) (get b 'app.pt-calc-test/api-test)) "and one through the agent's API")
    (is (= (dissoc a 'app.pt-calc-test/data-test 'app.pt-calc-test/api-test) (dissoc b 'app.pt-calc-test/data-test 'app.pt-calc-test/api-test))
        "the tests reading no branch keep theirs")
    (is (= (checkout branch) (checkout written)) "bin/test's digests hold no branch")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} an-agents-own-deftest-is-no-failure-point
  ;; docs/health.md#a-laws-unnamed-site: a deftest an agent defines at its REPL, in a namespace of its own (which takes a file under src,
  ;; seon.source/namespace-path), is a test by its var's :test (the compiler's fact), so its `is`'s catch opens no
  ;; :unnamed-site the done rule refuses on (seon.problems/caused); a catch in a function it defines there still does
  (let [[_ caused] (in-agent (fn [{n :ns}]
                               [(str "(ns " n ".lab (:require [clojure.test :refer [deftest is]]))"
                                     " (deftest p (is (= 1 1)))"
                                     " (defn f [] (try 1 (catch Exception _ 2)))")
                                "(seon.problems/caused (seon.agent/heard-main) (seon.agent/db))"]))
        site (fn [v] (some #(str/includes? % (str ".lab/" v " has a catch site")) (:value caused)))]
    (is (not (site "p")) (pr-str caused))
    (is (site "f") (pr-str caused))))

;; Estimate: calc-db and a record ~1.4M, 7 reach-digests of a small db at ~1.3M, 2 test plans at ~2M = ~10000000.
(deftest ^{:seon.test/estimate {:ticks 10000000}} an-agents-greens-and-a-checkouts-are-one-store
  ;; owner, 2026-10-23: a test green at its digest is not run again, "especially when freshly branched": an agent's greens and a
  ;; checkout's (bin/test's) are one verdict cache, so an agent's plan must key each test by the digest bin/test's plan does.
  ;; bin/test's is seon.program/graph-index over the program's reach graph; an agent's is its base's graph brought to its branch
  ;; (seon.agent/test-plan: branch-graph, reach-indexed). On a small program under one boot's inputs the two agree, freshly
  ;; branched and after the branch changes a form. That a verdict stored at a digest is then reused is the cache's
  ;; (seon.program-test/a-result-recorded-in-one-checkout-is-reused-in-another).
  (let [in {:env "e" :tree "t" :config {} :data-refs {}}
        tests @#'seon.program-test/calc-tests
        base (d/db-with (#'seon.program-test/calc-db)
                        [{:seon.system/id :root :seon.system/boot "b1" :seon.system/source {:root "/r" :files 0 :forms 0 :ms 0 :boot "b1" :inputs in}}])
        branch (seon.program-test/record base 'app.pt-calc "(defn add [a b] (+ b a))")
        checkout (fn [db] (select-keys (ts/reach-digests db in) tests))
        agent (fn [db] (select-keys (:digests (agent/test-plan db base tests)) tests))]
    (is (= tests (set (keys (checkout base)))) "every test has a digest")
    (is (= (checkout base) (agent base)) "freshly branched: the agent keys each test as bin/test does")
    (is (= (checkout branch) (agent branch)) "and after its branch changes a form")
    (is (not= (checkout base) (checkout branch)) "which moved a digest")))

;; Estimate: 2 db-with of one root entity ~0.1M each, 2 test-inputs reads, test-db is shared = ~500000.
(deftest ^{:seon.test/estimate {:ticks 500000}} a-previous-roots-inputs-are-not-shared
  ;; re-review 2026-10-22: the :root entity outlives its root, so a restart (or a root with no
  ;; source on the same main) must not key its agents' results by a fact an earlier boot wrote
  (let [inputs {:env "e" :tree "t" :config {} :data-refs {}}
        source {:root "/r" :files 0 :forms 0 :ms 0 :boot "b1" :inputs inputs}
        db (fn [boot] (datahike.api/db-with (ts/test-db) [{:seon.system/id :root :seon.system/boot boot :seon.system/source source}]))]
    (is (= inputs (first (agent/test-inputs (db "b1")))) "this boot's inputs: its results are keyed by them")
    (is (nil? (first (agent/test-inputs (db "b2")))) "an earlier boot's: nothing keys by them, so nothing is reused")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-test-childs-ceiling-is-its-own
  ;; docs/machine.md#what-a-process-owns-in-memory: a test child inherited its agent's absolute
  ;; ceiling, so a child of an agent that had grown had that much less room for its tests; its
  ;; ceiling is now its own heap at its fork plus the same allowance. The agent holds 300 MB
  ;; first, so the inherited ceiling would leave the child about 0.7 of the allowance
  (let [[_ _ r] (in-agent ["(def held (byte-array (* 300 1048576)))"
                           "(ns room-test (:require [clojure.test :refer [deftest is]])) (deftest room (is (< (* 0.95 (seon.config/value :seon.config.agent/heap-extra-bytes)) (- (jolt.scheme/eval-string \"jolt-heap-ceiling-bytes\") (seon.lib/bytes-in-heap)))))"
                           "(let [r (seon.agent/run-tests-forked 100000000 #{} :main (fn [_] nil) '#{room-test})] (pr-str {:pass (:pass r) :fail (:fail r) :error (:error r)}))"])
        v (some-> (:value r) clojure.edn/read-string)]
    (is (= {:pass 1 :fail 0 :error 0} v) (pr-str r))))

;; Estimate: evals on a world (built once a process, ~3.2M) at ~0.3M each; measured schema registration compiles the registry ~30M
(deftest ^{:seon.test/estimate {:ticks 45000000}} an-eval-reads-the-schema-entries-only-after-one-moved
  ;; docs/machine.md#an-agents-first-write: seon.agent/branch-schemas, which the process's registry is set from (
  ;; instrument, the page walk), read every schema entry (two passes, seon.program/schema-changes: 748
  ;; entries, 1.08 MB) to learn nothing had changed. With the registry held (the fork hands main's) a call
  ;; costs under a tenth of one read; after a registration the next eval binds the registered schema.
  (let [_ '[seon.agent/branch-schemas seon.program/schema-changes my.schema/register! seon.contract/valid?] ; run by string below, named so a change to them reruns this test
        m "(fn [f] (let [b (seon.profile/alloc-bytes)] (f) (- (seon.profile/alloc-bytes) b)))"
        [_ costs reg valid]
        (mt/eval-in [(str "(def meas " m ")")
                   "[(meas #'seon.agent/branch-schemas) (meas #(seon.program/schema-changes (:db @seon.agent/state)))]"
                   "(my.schema/register! :lab.gap/n [:int {:min 3}])"
                   "[(seon.contract/valid? :lab.gap/n 5) (seon.contract/valid? :lab.gap/n 1)]"])
        [held read] (:value costs)]
    (is (< (* 10 held) read) (str "a call of branch-schemas with the registry held allocated " held " bytes; one read of the schema changes " read))
    (is (= [true false] (:value valid)) (str "after a registration the next eval binds it: " (pr-str reg) " " (pr-str valid)))))

;; docs/source.md#schemas-at-the-repl: an agent process holds one branch, so its one malli registry is that branch's, on every path:
;; an eval, the turn loop, a Flow step, a handler, a thread. Red before: the branch's registry was bound only around some paths (11
;; hand bindings), so code outside them checked against main's and threw "malli has no schema" (context's loop task, 2026-11-13: a
;; key its branch held and main had deleted). Here a raw Thread, which conveys no binding, stands for every such path, and a
;; lookup by name at call time (contract/valid?, as contract/error's type check and config's key check do) is what failed.
;; Estimate: evals on a world (built once a process, ~3.2M) at ~0.3M each; measured schema registration compiles the registry ~30M
(deftest ^{:seon.test/estimate {:ticks 45000000}} a-branchs-own-schema-holds-on-every-path
  (let [_ '[my.schema/register! seon.contract/valid?] ; run by string below, named so a change to them reruns this test
        [reg _ called]
        (mt/eval-in ["(my.schema/register! :lab.reg/n [:int {:min 3}])"
                   ":registered"
                   "(let [r (promise) t (Thread. #(deliver r (try [(seon.contract/valid? :lab.reg/n 5) (seon.contract/valid? :lab.reg/n 1)] (catch Throwable e (ex-message e)))))] (.start t) (.join t) @r)"])]
    (is (= [true false] (:value called)) (str "a call outside any eval checks against the branch's schema: " (pr-str reg) " " (pr-str called)))))

;; docs/source.md#schemas-at-the-repl (seon.contract/a-bare-schema-read-on-a-branch-reads-the-branch): a schema read with no options,
;; malli's default registry, answers the branch's schema in an agent, as seon.contract/options does; red before: it answered main's
;; (the first real seon_done's own red, an order read bare)
;; Estimate: evals on a world (built once a process, ~3.2M) at ~0.3M each; measured schema registration compiles the registry ~30M
(deftest ^{:seon.test/estimate {:ticks 45000000}} a-bare-schema-read-on-a-branch-reads-the-branch
  (let [_ '[my.schema/register! malli.core/schema] ; run by string below, named so a change to them reruns this test
        [_ read] (mt/eval-in ["(my.schema/register! :lab.reg/bare [:int {:min 3}])"
                            "[(malli.core/form (malli.core/deref (malli.core/schema :lab.reg/bare))) (malli.core/validate :lab.reg/bare 1)]"])]
    (is (= [[:int {:min 3}] false] (:value read)) (str "the branch's own schema, read bare: " (pr-str read)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} root-reads-the-health-the-render-proc-keeps
  ;; docs/health.md#cost: my.root/problems read main's health whole at every call (~600 MB on the standing
  ;; root); it reads the render proc's, kept by what each move of main changed, at the api's fold. It
  ;; answers what the whole read does, after boot and after main moved, and a read with nothing moved
  ;; costs under a tenth of the whole read.
  ;; the whole read is made at the point the held answer was put at (its metadata's :db and :fold), never a later main or fold: an
  ;; error recorded between the two reads (load, a stall) once made them differ, and the test raced
  (let [same "(let [h (my.root/problems sys) {at :db f :fold} (meta h) w (seon.problems/health at f) ks (into (set (keys (:classes h))) (keys (:classes w)))] [(= (:classes h) (:classes w)) (count (:classes h)) (vec (for [k ks :when (not= (get-in h [:classes k]) (get-in w [:classes k]))] [k (count (:rows (get-in h [:classes k]))) (count (:rows (get-in w [:classes k])))]))])"
        between (str "(let [h (my.root/problems sys) n (count (seon.system/errors sys))"
                     " _ (clojure.core.async/>!! (:back sys) (seon.system/uncaught-message nil (ex-info \"recorded between the reads\" {})))"
                     " _ (loop [i 0] (when (and (< i 1000) (<= (count (seon.system/errors sys)) n)) (recur (inc i))))"
                     " {at :db f :fold} (meta h) w (seon.problems/health at f)] [(= (:classes h) (:classes w)) (> (count (seon.system/errors sys)) n)])")
        cost "(let [m (fn [f] (let [b (seon.profile/alloc-bytes)] (f) (- (seon.profile/alloc-bytes) b)))] (my.root/problems sys) [(m #(my.root/problems sys)) (m #(seon.problems/health @(:conn sys) (seon.system/errors sys)))])"
        ;; a burst of distinct errors (letters, since a cause normalises a message's digits) past one flush's bound (:seon.config.render/flush-max) moves main just before the read: the held
        ;; answer is the report OF the point it names, never the last settled one stamped with the newer main (order-dependent in
        ;; lands: what earlier tests left was such a backlog)
        burst (str "(let [n (count (seon.system/errors sys))"
                   " _ (dotimes [i 300] (clojure.core.async/>!! (:back sys) (seon.system/uncaught-message nil (ex-info (str \"burst \" (char (+ 97 (quot i 26))) (char (+ 97 (rem i 26)))) {}))))"
                   " _ (loop [i 0] (when (and (< i 100000) (< (count (seon.system/errors sys)) (+ n 300))) (recur (inc i))))] " same ")")
        [booted moved costs raced burst-read]
        (in-agent (fn [{:keys [task]}]
                    [{:op :eval :agent "root" :code same}
                     {:op :eval :agent "root" :code (str "(do @(seon.system/transact-main! sys [{:seon.task/id " (pr-str task) " :seon.task/failure {:reason \"provoked\"}}]) " same ")")}
                     {:op :eval :agent "root" :code cost}
                     {:op :eval :agent "root" :code between}
                     {:op :eval :agent "root" :code burst}]))
        [held whole] (:value costs)]
    (is (= true (first (:value booted))) (str "after boot, held and whole agree: " (pr-str booted)))
    (is (= true (first (:value moved))) (str "after main moved, held and whole agree: " (pr-str moved)))
    (is (< (* 10 held) whole) (str "a held read allocated " held " bytes; a whole read " whole))
    (is (= [true true] (:value raced)) (str "an error recorded between the two reads leaves them agreeing, each at the held answer's point: " (pr-str raced)))
    (is (= true (first (:value burst-read))) (str "after a burst past one flush's bound, held and whole agree: " (pr-str burst-read)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 5 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2750k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 5}} an-agents-first-search-reads-the-index-it-inherited
  ;; root's REPL writes main through my.test/code, a namespace the binary holds (seon.system requires it): a boot loads
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

;; Estimate: in-agent's own setup and teardown in this process (a task file, the attach, the kill and its branches' deletes: ~6 asks
;; and their replies decoded, ~1.5M) + one root eval spawning onto a kept branch, its answer three claims' facts (~0.5M) = ~2M:
;; 3,000,000 ticks (it was estimated at 200k from 30k an ask; measured 1.98M and 2.04M, past the 2M budget at the land of 03:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 7}} a-resumed-branchs-agent-is-this-boots
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
                              src (fn [n body] (str "@(seon.system/transact-main! sys (seon.source/text-tx @(:conn sys) '" n " \"(ns " n ")\\n\\n" body "\\n\" nil))"))]
                          [{:op :eval :agent "root"
                            :code (spawn-kept-code kept
                                                   (str (src k "(defn h [] 1)")
                                                        " (datahike.api/branch! (:conn sys) m b)"
                                                        " (let [bc (seon.db/connect (assoc (:cfg sys) :branch b))]"
                                                        "   (datahike.api/transact bc [{:seon.system/id :root :seon.system/boot \"an-earlier-boot\"}]) (datahike.api/release bc))"
                                                        " " (src g "(defn g [] 7)")
                                                        " " (src k "(defn h [] 2)"))
                                                   (str "[:ready (:boot sys) [(name ag) (:text @(seon.system/submit! sys ag \"(:key @seon.agent/state)\"))]"
                                                        " (:text @(seon.system/submit! sys ag \"[(" g "/g) (" k "/h)]\"))]"))}]))
                      ;; the default root (plans/tests-merge-root.md): its two namespaces of its own names on main, as every test's facts are
                      {})
        [status boot [id text] gained] (:value r)]
    (is (= :ready status) (pr-str r))
    (is (str/includes? (str text) (str "\"" id "\"")) (str "it records under its own key, the name of its id, whatever boot the branch is from: " text))
    (is (not (str/includes? (str text) "an-earlier-boot")) "and no key of the earlier boot's")
    (is (str/includes? (str gained) ":value [7 2]") "a var main added, and main's redefinition of one the branch left alone")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 5 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2750k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 5}} a-failure-marked-through-the-server-holds-the-task
  ;; owner, 2026-10-22: a failure is task data and root reacts to it. The agent marks its
  ;; task failed on its branch, hands the task back (seon_done), and root, at the
  ;; agent's end, holds the task as :failed and reports it among the problems
  ;; it waits on its own task's fact (the failure held on main), never the shared root's quiet: a sibling task kept live beside it
  ;; (an attached outside agent, which no judge ends) held that quiet for good, and this test stopped at its 300 s bound 4 times on
  ;; 2026-11-15, at loads 6.5 to 21 [M]; red before: await-quiet never answered while :live held the sibling
  (let [names (volatile! nil)
        [_ f _ _ done seen] (in-agent (fn [{:keys [agent task ns] :as n}]
                                   (vreset! names n)
                                   [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} "(:reason (my.task/fail! \"the premise is refuted\" '(= 1 2)))"
                                    {:op :attach :task (str ns "/s")}
                                    before-done {:op :done :agent agent}
                                    {:op :eval :agent "root"
                                     :code (str "(do (seon.system/await-db sys #(:seon.task/failure (datahike.api/entity % [:seon.task/id " (pr-str task) "])))"
                                                " [(seon.reconcile/task-state (seon.reconcile/index @(:conn sys)) " (pr-str task) ")"
                                                " (select-keys (first (filter #(= " (pr-str task) " (:task %)) (get-in (my.root/problems sys) [:classes :failed :rows]))) [:task :reason :counterexample :open?])])")}])
                                 {:decls [{:seon.task/name "s" :seon.task/plan "A sibling kept live." :seon.task/kind :work}]})]
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
       " b (:db @(seon.system/branch-db sys (seon.task/default-branch " (pr-str id) ")))]"
       " [(select-keys t [:seon.task/outcome :seon.task/reason]) (seon.reconcile/pull-end b (:seon.task/agent t))])"))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 4 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2500k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 4}} an-outside-agent-writes-its-end
  ;; found by a worked example (evaluations session, 2026-10-23): an outside agent's :done
  ;; judged a task whose agent had no end, so it read "the agent wrote no end"
  ;; whatever its proofs said. The agent writes the end first, as a turn loop does: here its
  ;; one proof is not written, so the end is :red and names why
  ;; its end is read once :done answers (the judge settled the task before it answered, seon.system/judge!), never after the shared
  ;; root's quiet, which a sibling task kept live (an attached outside agent) holds for good: this test stopped at its 300 s bound 5
  ;; times on 2026-11-15 [M]; red before: await-quiet never answered while :live held the sibling
  (let [[_ _ _ done seen] (in-agent (fn [{:keys [agent task ns]}]
                                    [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                                     {:op :attach :task (str ns "/s")}
                                     before-done {:op :done :agent agent}
                                     {:op :eval :agent "root" :code (ended-code task)}])
                                  {:decls [{:seon.task/name "s" :seon.task/plan "A sibling kept live." :seon.task/kind :work}]})
        [t end] (:value seen)]
    (is (str/includes? (:line done) ":outcome :closed") (str "the answer is the verdict line: " (pr-str done)))
    (is (= :red (:outcome end)) (pr-str end))
    (is (= :closed (:seon.task/outcome t)) (pr-str t))
    (is (str/includes? (str (:reason end)) "proof-unknown demo-test/t") "the end names why it is red")
    (is (str/includes? (str (:seon.task/reason t)) "proof-unknown demo-test/t") "and the task holds it")
    (is (not (str/includes? (str (:seon.task/reason t)) "no end")) "never read as an end nobody wrote")))

;; Estimate: mt/code and mt/without ~0.7M, test-plan of branch against main ~3M, load-removed! and run-tests! of one test ~0.6M, r/index of main ~1M, done-input and done-explain ~0.3M = ~6000000.
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-branch-that-deletes-mains-test-it-breaks-is-not-merged
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
                     (#'agent/load-removed! (:removed plan) [main-test])
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
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 4 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2500k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 4}} an-outside-agents-reply-takes-mains-program
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
                        :code (str "(let [db @(:conn sys)] @(seon.system/transact-main! sys (seon.program/record-tx db {:id \"" moved "/(defn f [] 1)\" :src \"(defn f [] 1)\" :ns '" moved " :ns-form? false"
                                   " :defs [{:var '" moved "/f :meta #{:arglists} :contract nil}] :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})))")}
                       reply])))]
    (is (false? (last-value before)) (pr-str before))
    (is (true? (last-value after)) (str "the reply after main moved holds main's program: " (pr-str after)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} an-oom-carries-its-heap
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} each-eval-call-is-answered-before-the-next
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

(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 20000000}} a-task-green-before-its-work-does-not-end-at-turn-0
  ;; docs/reconcile.md#a-task-holds-how-its-work-ended: one done rule on one input. A refine whose one proof was green on main ended
  ;; :green at turn 0 with no model call, its branch fresh at main's head: the turn's check read no merge base, so it measured no
  ;; change, where root's judge then said :no-change. The agent's check and root's measure the change alike (seon.merge/changed?),
  ;; from the base the run holds from its start. Estimate: one in-agent run, its proof run once, two scripted calls, as its neighbours
  (let [[end] (in-agent (fn [{:keys [task]}] [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 2})")])
                        {:proofs #{'seon.ref-test/the-delimiter-picks-the-depth} :config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        e (:value end)]
    (is (= [:cap 2] [(:outcome e) (:model-calls e)]) (str "its proof green before any work proves none of it: it works, then caps " (pr-str e)))
    (is (str/includes? (str (:reason e)) "no-change") "unchanged, as root's judge would say")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} the-page-is-a-message-in-the-conversation-and-the-prefix-holds
  ;; docs/render.md#a-change-is-edits-by-name (owner, 2026-11-05: "make the rendering of the hiccup a normal eval that we inject into turn
  ;; 0 and then we can just do diffs when it changes"; stage 2b: the page is a generated eval of each turn, never written to the db): two real turns. The first request holds the page as
  ;; the call `(my.render/page p)` and its result; these turns move nothing the page shows, so no other is sent; and every request is the one before it, byte for byte, with the records added. The hash each turn recorded of
  ;; the bytes it sent is the hash of the request made again from its records at its commit.
  (let [[end r] (in-agent (fn [{:keys [task]}]
                            [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 2})")
                             (str "(let [db (seon.agent/db) k (:key @seon.agent/state)"
                                  "      basis (fn [n] {:commit (datahike.api/commit-id db) :task " (pr-str task) " :turn n :max-turns 2 :origin :model :agent k})"
                                  "      msgs (fn [n] (let [b (:seon.model-call/basis (datahike.api/pull db '[:seon.model-call/basis] [:seon.model-call/id (seon.lib/call-id k n)]))] (:messages (seon.ctx.wire/render (datahike.api/commit-as-db (:store db) (:commit b)) b))))"
                                  "      page? (fn [m] (boolean (some-> m :tool-calls first :code (#(or (.startsWith % \"(my.render/\") (.startsWith % \"(my.agent/describe \"))))))"
                                  "      calls (fn [ms] (vec (keep #(some-> % :tool-calls first :code) (remove page? ms))))"
                                  "      pages (fn [ms] (vec (for [[a b] (partition 2 1 ms) :when (page? a)] (:content b))))"
                                  "      m1 (msgs 1) m2 (msgs 2) m3 (:messages (seon.ctx.wire/render db (basis 3)))"
                                  "      redrawn (fn [n] (let [m (datahike.api/pull db '[:seon.model-call/sent-hash :seon.model-call/basis] [:seon.model-call/id (seon.lib/call-id k n)])"
                                  "                              b (:seon.model-call/basis m)]"
                                  "                          [(:seon.model-call/sent-hash m) (seon.lib/digest (seon.edn/encode (:messages (seon.ctx.wire/render (datahike.api/commit-as-db (:store db) (:commit b)) b))))]))]"
                                  "  {:calls [(calls m1) (calls m2) (calls m3)] :prefix [(= m1 (subvec m2 0 (count m1))) (= m2 (subvec m3 0 (count m2)))]"
                                  "   :by (vec (sort (datahike.api/q '[:find [?c ...] :where [?e :seon.eval/by ?s] [?s :seon.system/id :root] [?e :seon.eval/code ?c]] db)))"
                                  "   :pages [(count (pages m1)) (count (pages m2)) (count (pages m3))]"
                                  "   :hashes [(redrawn 1) (redrawn 2)]"
                                  "   :first-page (apply str (pages m1))"
                                  "   :first-code (some-> (first (filter page? m1)) :tool-calls first :code)})")])
                          {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        {:keys [calls prefix by pages hashes first-page first-code]} (:value r)]
    (is (= :cap (:outcome (:value end))) (pr-str end))
    (is (some? (:value r)) (pr-str (select-keys r [:ex :text])))
    (is (= [[] ["(+ 20 21)"] ["(+ 20 21)" "(+ 1 41)"]] calls)
        "the page is no call; each request holds the one before and the records added")
    (is (= [true true] prefix) "byte for byte")
    (is (= [] by) "Seon ran no eval: the page is its message")
    (is (= [2 2 2] pages) "the record and the page whole once, in the first request; nothing they show moved after, so no other message")
    (is (every? (fn [[sent redraw]] (and (string? sent) (= sent redraw))) hashes) (str "the hash a turn recorded is its redraw's: " (pr-str hashes)))
    (is (= "(my.agent/describe @ctx)" first-code) "the first request describes the record first, (describe @ctx), then the page")
    (is (str/includes? first-page "Your task") "the task's part")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-printed-page-call-runs-at-the-agents-repl
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
;; Estimate: evals on a world (built once a process, ~3.2M) at ~0.3M each; measured a few M
(deftest ^{:seon.test/estimate {:ticks 10000000}} an-edit-of-a-middleware-runs-next
  (let [_ '[seon.repl/wrap-cost seon.repl/middleware] ; run by string below, named so a change to them reruns this test
        [_ _ marked vars] (mt/eval-in ["(def marks (atom 0)) (in-ns 'seon.repl)"
                                     "(let [old wrap-cost] (alter-var-root #'wrap-cost (fn [_] (fn [h] (let [n (old h)] (fn [req] (swap! @(find-var (symbol (str (ns-name (first (filter #(ns-resolve % 'marks) (all-ns)))) \"/marks\"))) inc) (n req)))))))"
                                     "@@(find-var (symbol (str (ns-name (first (filter #(ns-resolve % 'marks) (all-ns)))) \"/marks\")))"
                                     "(every? var? seon.repl/middleware)"])]
    (is (pos? (:value marked)) (pr-str marked))
    (is (true? (:value vars)))))

;; Estimate: 4 evals at ~0.3M (an ns, three edits) on the world = ~3M
(deftest ^{:seon.test/estimate {:ticks 3000000}} edit-says-what-it-did
  (let [_ '[seon.repl/edit! seon.repl/edit-answer] ; what the forms below run by string, named so a change to it reruns this test
        [_ many same fine] (mt/eval-in (fn [{:keys [ns]}]
                                       [(str "(ns " ns ".ed) (defn f \"One.\" {:malli/schema [:=> [:cat] :int]} [] (+ 1 1 1))")
                                        (str "(seon.repl/edit! '" ns ".ed/f \"1\" \"2\")")
                                        (str "(seon.repl/edit! '" ns ".ed/f \"(+ 1\" \"(+ 1\")")
                                        (str "(seon.repl/edit! '" ns ".ed/f \"(+ 1 1 1)\" \"(+ 1 1 2)\")")]))]
    (is (= :seon.repl/edit-refused (:seon.error/type (:ex many))) (str "three places: refused, saying so: " (pr-str many)))
    (is (str/includes? (:seon.error/message (:ex many)) "occurs 3 times"))
    (is (contains? (:value same) :unchanged) (pr-str same))
    (is (not (contains? (:value same) :edited)) "nothing changed is not an edit")
    (is (:edited (:value fine)) (pr-str fine))))

;; a read waits on no one: an agent in an eval that never ends is read by root from its branch, and an interrupt takes a test child with it
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 7 asks of the test's own at ~0.25M (an eval's recorded answer decoded); 1 shell call (rm) ~200k = ~3250k, 4000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 7}} a-read-does-not-wait-on-a-hung-test
  (let [pidf (str (System/getProperty "java.io.tmpdir") "/seon-agent-test-hung-" (random-uuid))
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
;; Estimate: 3 evals at ~0.3M, one git status shell call ~0.1M = ~3M
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-method-is-listed-with-what-a-branch-carries
  ;; the branch's merge base is the db the agent began on (agent state :base), as the agent's process holds it
  (let [[_ _ method changes] (mt/eval-in (fn [{:keys [ns]}]
                                           [(fn [{:keys [db]}] (swap! agent/state assoc :base db) :base-set)
                                            (str "(ns " ns ".mm) (defmulti area \"Area.\" :k)")
                                            "(defmethod area :square [s] 1)"
                                            ;; my.branch/changes is this diff plus the checkout's git status, which no test runs
                                            (fn [{:keys [db]}] (pm/divergence (agent/branch-changes (:base @agent/state) db)))]))]
    (is (some #(= ":square" (second %)) (filter vector? (:recorded method))) (pr-str method))
    (is (some #(and (vector? %) (= ":square" (second %))) (:added (:value changes))) (pr-str (:value changes)))))

;; docs/failure-points.md: a live agent ran (def form-of ...) in seon.program as a scratch name, every later write threw in seon.program/record-tx, and the run died
;; Estimate: 3 evals at ~0.3M and one failed definition recorded = ~3M
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-definition-that-breaks-its-own-recording-is-undone
  (let [_ '[seon.repl/eval-string seon.repl/undo-definition! seon.program/definition-form?] ; run by string below, named so a change reruns this test
        [broke after still] (mt/eval-in ["(in-ns 'seon.program) (def form-of (fn [v] v))"
                                       "(def after-it 41) (inc after-it)"
                                       "(seon.program/definition-form? '(def x 1) (the-ns 'user))"])]
    (is (= :seon.repl/definition-undone (:seon.error/type (:ex broke))) (pr-str (:ex broke)))
    (is (str/includes? (:seon.error/message (:ex broke)) "seon.program/form-of") "it says which")
    (is (= 42 (second (:value after))) "the run goes on: the next eval records")
    (is (nil? (:ex still)) "and Seon's own var is as it was")))

;; docs/storage.md#collection: collection may have swept the commit a page is drawn at: the same datoms as of t, never a later page
;; Estimate: two at-t calls over a world db, no walk: ~0.5M
(deftest ^{:seon.test/estimate {:ticks 500000}} a-page-whose-commit-is-gone-is-drawn-as-of-its-t
  ;; on a value: the world's db whose parent commit the store no longer holds (commit-as-db answers nil, as collection leaves it)
  (let [db (mt/world {:history? true})
        gone (assoc-in db [:meta :datahike/parents] #{(random-uuid)})
        t (:max-tx db)
        [was as-of?] (with-redefs [d/commit-as-db (fn [_ _] nil)]
                       [(:max-tx (#'seon.ctx.wire/at-t gone t)) (some? (:origin-db (#'seon.ctx.wire/at-t gone (dec t))))])]
    (is (= t was) "the commit at t itself needs no walk")
    (is (true? as-of?) "past what collection kept, a db as of t")))

;; docs/ctx.md#what-the-model-reads-and-what-the-page-renders: the record the first request shows as (my.render/ctx p) is the agent's own
;; @ctx, the live atom, printed whole: the same thing it reads when it types @ctx, never a filtered copy (owner, 2026-11-11)
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} the-record-shown-is-the-live-ctx
  ;; after a turn, inside one eval: the live atom and (my.render/ctx p) at the agent's db's own point are the same value (red
  ;; before: the cold read named no branch, and a patch dropped a member's link transaction), and print the same bytes (red before:
  ;; an entity's array map printed in the order its keys were added, which a patch and a build differ in)
  (let [[_ same]
        (in-agent (fn [{:keys [task]}]
                    [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                     (str "(let [db (seon.agent/db) whole #(seon.print/text (vary-meta % assoc :seon.print/full true))"
                          " lv @seon.ctx/ctx sv (my.render/ctx {:commit (datahike.api/commit-id db) :t (:max-tx db)}) live (whole lv) shown (whole sv)]"
                          " {:equal (= lv sv) :same-bytes (= live shown) :sizes [(count live) (count shown)] :conversation? (some? (:seon.model-call/_agent @seon.ctx/ctx))"
                          " :at (when (not= live shown) (let [i (or (first (keep-indexed (fn [i [a b]] (when (not= a b) i)) (map vector live shown))) (min (count live) (count shown)))]"
                          " [(subs live (max 0 (- i 100)) (min (count live) (+ i 150))) (subs shown (max 0 (- i 100)) (min (count shown) (+ i 150)))]))})")])
                  {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})]
    (is (:conversation? (:value same)) (str "a turn in, so the record holds the conversation's joins: " (pr-str same)))
    (is (true? (:equal (:value same))) (str "the record shown at a point is the live @ctx there, as a value: " (pr-str (:value same))))
    (is (true? (:same-bytes (:value same))) (str "and the same bytes: an entity prints its keys in one order (seon.print.select/held-order): " (pr-str (:value same))))))

;; docs/render.md#a-change-is-edits-by-name: a wake is told what moved since the page it last showed, never the page again: a second whole page
;; costs about 4k tokens a wake
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 4 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2500k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 4}} a-wake-shows-what-moved-not-the-page-again
  (let [[_ _ whole calls]
        (in-agent (fn [{:keys [task]}]
                    [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                     (str "(seon.turn/work! {:task " (pr-str task) " :max-turns 2})")
                     (str "(let [db (seon.agent/db)] (count (filter (fn [m] (some-> m :tool-calls first :code (as-> c (or (.startsWith c \"(my.render/\") (.startsWith c \"(my.agent/describe \"))))) (:messages (seon.ctx.wire/render db {:commit (datahike.api/commit-id db) :task " (pr-str task) " :turn 3 :max-turns 2 :origin :model :agent (:key @seon.agent/state)})))))")
                     "(count (datahike.api/q '[:find [?m ...] :where [?m :seon.model-call/origin :model]] (seon.agent/db)))"])
                  {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})]
    (is (= 2 (:value calls)) "the second wake took a turn, so it had the chance to show the page")
    (is (= 2 (:value whole)) "the record and the page whole once, across both wakes: nothing moved, so the second turn adds no page message")))

;; docs/prompt.md: a call is text and one or more forms, run in order; one with no text or no form answers a typed error as its one result
;; and runs nothing (owner, 2026-10-29). In this process: seon.repl/call-problem alone (tests' FOCUS step 4: the half that needs no agent)
(deftest ^{:seon.test/estimate {:ticks 200000}} a-call-is-text-and-its-forms
  (let [problem #'seon.repl/call-problem]
    (is (nil? (problem "Add them." "(+ 1 2)")) "text and one form")
    (is (= :no-intent (:problem (problem " " "(+ 1 2)"))) "no intent")
    (is (nil? (problem "Two." "(def a 1) (inc a)")) "two forms")
    (is (= [:forms 0] ((juxt :problem :forms) (problem "None." "  "))) "no form")
    (is (nil? (problem "Unread." "(+ 1")) "code that does not read is left to the REPL, which says where")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M) + 2 asks of the test's own at ~0.25M = ~2M; 3,000,000 ticks. One scripted
;; agent where three were (a-call-after-one-that-threw-still-runs, a-call-after-an-error-answer-still-runs and the agent half of
;; a-call-is-text-and-its-forms: tests' FOCUS step 4, ~15 s -> ~4 s)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 2}} a-reply-runs-every-call-whatever-the-one-before-did
  ;; docs/prompt.md: a reply's calls run in order, each whatever came before it (owner, 2026-11-08: "I want us to run every eval. Turns are
  ;; precious and one fuckup shouldn't punish the other evals"), every call with its tool message (the provider refuses a call with none):
  ;; a call of two forms runs both; a call that throws is its error; a call answering an error value (a refused edit!) is one result; and
  ;; the calls after each still run
  (let [[_ msgs] (in-agent (fn [{:keys [task]}]
                             [(str "(seon.turn/work! {:task " (pr-str task) " :max-turns 1})")
                              (str "(let [db (seon.agent/db)]"
                                   " (->> (:parts (seon.ctx.wire/render db {:commit (datahike.api/commit-id db) :task " (pr-str task)
                                   "                                       :turn 2 :max-turns 1 :origin :model :agent (:key @seon.agent/state)}))"
                                   "      (filter #(= :eval (ffirst %))) (mapcat second)"
                                   "      (filter #(= \"tool\" (:role %))) (mapv #(select-keys % [:tool-call-id :content]))))")])
                           {:config {:seon.config.ai/script 'seon.fixtures/calls-of-every-kind}})
        [two threw refused after sum :as rs] (map :content (:value msgs))]
    (is (= 5 (count (distinct (map :tool-call-id (:value msgs))))) (str "every call has its result, by its id: " (pr-str msgs)))
    (is (and (str/includes? two ":forms 2") (str/includes? two " 2]")) (str "the two-form call ran both, (inc g) after (def g 1): " two))
    (is (str/includes? threw "boom") "the call that threw: its error")
    (is (str/includes? refused "edit-refused") "the refused edit: its error value")
    (is (str/includes? after ":value 2") (str "(inc g) ran after both: " after))
    (is (str/includes? sum ":value 11") "and the last call ran")))

(defn- work-with
  "seon.turn/work! (or run, given, of the same argument) of the world's task for 3 turns, in this process as an agent's test fork on the world (my.test/eval-in's state),
  its turn's model call (seon.turn/model-request!, the request made ready; seon.turn/act!, its answer acted on) answered by (act st), its status by (status): what the loop decides from what
  a turn answers. What reads a store or root stands in (main, the proofs, the end's write), the decisions of the loop are its
  own. Answers its end, with the model calls act made (:model-calls)."
  ([act status] (work-with act status seon.turn/work!))
  ([act status run]
  (let [old @agent/state
        calls (volatile! 0)
        ;; a model call answered: the agent's turn moves, as open-turn! writes it, and the request is the one it sent
        called! (fn [] (vswap! calls inc)
                  (agent/transact! [{:seon.agent/key "test-agent" :seon.agent/turn (inc (agent/turn-now))}])
                  (swap! agent/state assoc :requests [{:role "user" :content (str "turn " @calls)}]))]
    (try (reset! agent/state {:n 0 :ns (create-ns 'seon.agent-test.turns) :mode :test :db (mt/world) :key "test-agent"})
         (with-redefs [seon.turn/begin! (fn [_] {})
                       agent/heard-main (constantly nil)
                       my.branch/merge-main! (constantly {})
                       seon.turn/observe (fn [id] {:task (task/task (agent/db) id)})
                       seon.turn/status-now status
                       seon.turn/tell-error! (constantly nil)
                       seon.turn/model-request! (fn [_ _ _] {:id "test-agent/call" :t1 0 :request {}})
                       seon.turn/act! (fn [st _ _] (act st called!))
                       ;; a hand-back's end has no turns (the outside model's, unknown here): absent, as seon.turn/end! leaves it
                       seon.turn/end! (fn [{:keys [turn]} outcome reason _]
                                        (cond-> {:outcome outcome :results {} :uncontracted #{} :model-calls @calls} turn (assoc :turns turn) reason (assoc :reason reason)))]
           (run {:task "app.calc/total-is-exact" :max-turns 3}))
         (finally (reset! agent/state old))))))

;; Estimate: the world, built once a process (~3.2M), one start! and one turn-begin on it (~0.2M), and the stop's end step: ~3.5M; 6,000,000
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-run-told-to-stop-ends-at-once
  ;; docs/flow.md#flow-inside-an-agent: root's :stop is a message to the :repl proc holding the run, which ends it at once :waiting,
  ;; with what its turns committed, never waiting out a model call in flight (minutes; context-4's ruling, 2026-11-15): the call's
  ;; answer, when it comes, finds no run and is dropped. Red before: a stop was the :draining flag, read only between turns, so the run
  ;; waited for the call in flight and its turn's end
  (let [red {:reasons [{:reason :proof-red}]}
        out (volatile! nil)
        end (work-with (fn [st called!] (called!) st) (constantly red)
                       (fn [arg] (let [go (fn go [[st o]] (if (or (:merge o) (:observe o)) (go (seon.turn/step-run st (@#'seon.turn/effect-answer o))) [st o]))
                                       [st o] (go (seon.turn/step-run (seon.turn/start! arg) nil))]
                                   (vreset! out (set (keys o)))
                                   (@#'seon.turn/end-of (seon.turn/step-run st {:seon.turn/stop true})))))]
    (is (= #{:model} @out) "the run's model call was out when the stop came")
    (is (= :waiting (:outcome end)) (pr-str end))
    (is (= 0 (:model-calls end)) (str "the stop did not wait for the call in flight: " (pr-str end)))))


;; Estimate: 200 generated runs x ~8 requests x ~3 proc steps at ~5k ticks = ~24M ticks
(deftest ^{:seon.test/estimate {:ticks 30000000}} every-request-an-agent-proc-holds-is-released-by-any-of-its-ends
  ;; the class behind the :done hang (a state a request sets, released only by its success): root's proc for an agent is a
  ;; pure step, so generated interleavings of clone and eval requests, each answered by any of its ends once the agent has it
  ;; (a clone: its descriptor asked, cloned, failed or never sent; an eval: replied or never sent), must leave nothing held
  ;; and nothing in flight; two clones at once among them, whose second overwrote the first's hold before
  (with-redefs [seon.sock/send! (fn [& _] true) seon.sock/close! (fn [& _] nil)]
    (doseq [seed (range 200)]
      (let [rng (java.util.Random. seed)
            pick #(nth % (.nextInt rng (count %)))
            out (clojure.core.async/chan 1000)
            kinds (vec (repeatedly (+ 2 (.nextInt rng 6)) #(pick [:clone :eval :clone])))
            frame (fn [st m] (#'seon.agent-proc/agent-frame st (volatile! {}) m))
            drain (fn [got] (loop [got got] (if-let [m (clojure.core.async/poll! out)] (recur (conj got (or (:k m) (:id m)))) got)))
            final (loop [st {:id :a1 :end {:out out :fd -1} :inflight {}} next 0 got #{} ended #{} n 0]
                    (let [got (drain got)
                          endable (vec (remove ended got))]
                      (cond
                        (> n 200) (assoc st ::stuck true)
                        (and (= next (count kinds)) (empty? endable)) st
                        (or (empty? endable) (and (< next (count kinds)) (zero? (.nextInt rng 2))))
                        (let [k (kinds next)
                              [st _] (#'seon.agent-proc/agent-in st (cond-> {:op :req :rid next :kind k :msg {:op k}} (= k :eval) (assoc-in [:msg :forms] "1") (= k :clone) (assoc :pass-fd 7)))]
                          (recur st (inc next) got ended (inc n)))
                        :else
                        (let [rid (pick endable)
                              k (kinds rid)
                              end (if (= k :clone) (pick [:fd-ready :cloned :clone-failed :unsendable]) (pick [:reply :unsendable]))
                              st (case end
                                   :fd-ready (frame st {:op :fd-ready :k rid})
                                   (:cloned :clone-failed) (frame st {:op end :k rid :error {:seon.error/type :x :seon.error/message "x"}})
                                   :unsendable (frame st {:op :unsendable :rid rid :error {:seon.error/type :x :seon.error/message "x"}})
                                   :reply (frame st {:op :reply :id rid :response {:value 1}}))
                              ;; a clone's descriptor asked keeps the request in flight until its answer
                              st (if (= end :fd-ready) (frame st {:op :cloned :k rid}) st)]
                          (recur st next got (conj ended rid) (inc n))))))]
        (is (not (::stuck final)) (str "seed " seed " " kinds))
        (is (nil? (:holding final)) (str "seed " seed " held by " (:holding final) " " kinds))
        (is (empty? (:held final)) (str "seed " seed " " kinds))
        (is (empty? (:inflight final)) (str "seed " seed " in flight " (keys (:inflight final)) " " kinds))))))

;; Estimate: three proc steps on a map and two channel puts, ~0.3M ticks
(deftest ^{:seon.test/estimate {:ticks 300000}} a-clone-that-fails-before-its-descriptor-releases-what-waited
  ;; the hang behind an-outside-agent-writes-its-end in three land checks (2026-11-14): root holds every message to an agent
  ;; while a clone's descriptor is in its handshake (:holding), and only the agent's :fd-ready released it, so a clone that
  ;; failed first (its fork refused at its bound, under load) held the agent's next request, the :done eval among them, for
  ;; good. Any end of the clone request now ends the hold, in order
  (let [out (clojure.core.async/chan 10)
        st0 {:id :a1 :end {:out out} :inflight {}}
        [st _] (#'seon.agent-proc/agent-in st0 {:op :req :rid 5 :kind :clone :new :a2 :msg {:op :clone}})
        _ (clojure.core.async/poll! out)
        [st _] (#'seon.agent-proc/agent-in st {:op :req :rid 6 :kind :eval :msg {:op :eval :forms "(hand-back!)"}})
        _ (is (nil? (clojure.core.async/poll! out)) "held while the clone's handshake is open")
        st (#'seon.agent-proc/agent-frame st (volatile! {}) {:op :clone-failed :k 5 :error {:seon.error/type :seon.lib/fork-refused :seon.error/message "refused"}})]
    (is (nil? (:holding st)) "the clone's failure ends the hold")
    (is (= :eval (:op (clojure.core.async/poll! out))) "and the request that waited goes out")))

(deftest ^{:seon.test/estimate {:ticks 200000}} a-stop-lets-the-eval-in-flight-end-by-its-own-bound
  ;; the same drain, root's side (seon.agent-proc/stop!): a stop interrupted an agent's eval in flight (SIGUSR1), cutting it at
  ;; its next safe point; it now sends :stop alone, which the agent answers after the request before it, that request ending by
  ;; its own tick budget. Estimate: one step of a map, ~0.2M
  (let [sigs (atom []) sent (atom [])]
    (with-redefs [seon.lib/kill! (fn [_ sig] (swap! sigs conj sig) nil)
                  seon.agent-proc/to-proc! (fn [st m] (swap! sent conj (:op m)) st)]
      (let [st (#'seon.agent-proc/stop! {:pid 4242 :inflight {7 {:op :eval}}})]
        (is (= [] @sigs) "no signal: the eval in flight ends by its own bound")
        (is (= [:stop] @sent) "the agent is told to stop after it")
        (is (:stop-sent st))))))

(deftest ^{:seon.test/estimate {:ticks 300000}} a-stop-transition-never-waits-on-its-process
  ;; task seon.agent-proc/a-stop-transition-never-waits-on-its-process (docs/flow.md#the-five-rules: a proc waits only by
  ;; parking on its in-port): Flow's stop of root's graph calls each agent proc's ::flow/stop, which waited in await-dead for
  ;; its process's end to close, so an agent ignoring :stop held root's whole stop. It sends :stop and returns; the death,
  ;; when an agent proc leaves while root runs, is already recorded (its end's EOF, :dead). Red before: the transition
  ;; never returned. Estimate: one transition of a map and two channel ops, ~0.1M
  (let [in (clojure.core.async/chan) out (clojure.core.async/chan 4)
        st {:id :a1 :pid 4242 :end {:in in :out out} :inflight {}}
        ;; on the test's own fiber, no clock (docs/testing.md): a transition that waited would park on in, which nothing
        ;; feeds, and the test child's quiescence would end it red
        v (seon.agent-proc/agent-proc st :clojure.core.async.flow/stop)]
    (is (:stop-sent v) "it returns with the agent's end still open, having told the agent to stop")
    (is (= {:op :stop} (clojure.core.async/poll! out)) "its :stop went out on the end")
    (clojure.core.async/close! in)))

(deftest ^{:seon.test/estimate {:ticks 6000000}} a-reasoning-overrun-is-the-next-turns-to-read
  ;; docs/failure-points.md (a turn of the agent's loop): a model call whose reasoning spent its whole token budget answers
  ;; :seon.model/reasoning-ate-reply, and the run ended :error at that turn, though the agent could reason less and go on
  ;; (root's Stage 0.3, 2026-11-14). A model call's typed error (:seon.model/failure) is a turn's error the next turn reads,
  ;; as a throw is; two in a row with no answered call between end the work. Estimate: as its sibling below, the world and
  ;; two work! loops of at most 3 turns
  (let [red {:reasons [{:reason :proof-red}]}
        overrun (seon.contract/error :seon.model/reasoning-ate-reply #'seon.model/reply-of
                                     "the reply is empty: reasoning took 8192 tokens of a 8192-token budget" {:reasoning-chars 30000 :reasoning-tokens 8192 :max-tokens 8192})
        n (volatile! 0)
        end (work-with (fn [st called!] (if (= 1 (vswap! n inc)) overrun (do (called!) st))) (constantly red))]
    (is (= :cap (:outcome end)) (str "the overrun was a turn the next one read, and the work went on to its cap: " (pr-str end)))
    (let [end (work-with (fn [_ _] overrun) (constantly red))]
      (is (= :error (:outcome end)) (pr-str end))
      (is (str/includes? (str (:reason end)) "reasoning took 8192 tokens") "two in a row end the work, naming the error"))))

(deftest ^{:seon.test/estimate {:ticks 6000000}} a-turn-that-throws-is-the-agents-to-read-and-the-second-ends-its-work
  ;; docs/failure-points.md (a turn of the agent's loop): md-scan #16 registered :seon.ref/block with list keys its
  ;; scan did not make yet, the registration re-armed seon.ref/scan, and the request's own scan threw after the
  ;; turn's draw, out of work! (outcome :no-end). A turn whose draw throws (the agent broke Seon's own seon.ctx.wire/render,
  ;; which a turn calls before its model call) is told to the agent and the loop goes on; the second in a row, with no model
  ;; turn between, ends the work :error, and work! answers its end
  (let [red {:reasons [{:reason :proof-red}]}
        end (work-with (fn [_ _] (throw (ex-info "boom: Seon's own render, changed" {}))) (constantly red))]
    (is (= :error (:outcome end)) (pr-str end))
    (is (= 2 (:turns end)) (str "on the second: " (pr-str end)))
    (is (str/starts-with? (str (:reason end)) "two turns in a row threw") (pr-str end))
    (is (str/includes? (str (:reason end)) "boom: Seon's own render, changed") "naming its cause")
    ;; a turn that threw after its model call answered was the agent's turn: it read the last error and acted, so
    ;; the count starts again, and the work goes on to its cap (live: seon.turn/usage, read after the reply)
    (let [end (work-with (fn [_ called!] (called!) (throw (ex-info "boom: after the reply" {}))) (constantly red))]
      (is (= :cap (:outcome end)) (str "each turn reached its model, so none ended the work: " (pr-str end))))
    ;; md-scan #27: the agent's change broke the status, every turn drew none, and the agent made 156 model calls past its
    ;; cap of 40: a turn with no status ends at the cap as every turn does
    (let [end (work-with (fn [st called!] (called!) (update st :turn inc))
                         #(agent/error-map (ex-info "boom: the status, broken" {}) 'seon.turn/status-now))]
      (is (= :cap (:outcome end)) (pr-str end))
      (is (<= (:model-calls end) 3) (str "no model call past the cap: " (pr-str end))))))

(deftest ^{:seon.test/estimate {:ticks 6000000}} an-agents-fail-is-its-verdict-though-its-proofs-are-green
  ;; seon.turn/hand-back-end ends a run :failed when its agent called my.task/fail!, whatever its proofs say; the turn loop checked
  ;; the green status first, so a model agent that failed its task on green proofs ended :green and its task merged. One order:
  ;; the agent's own verdict first, in the loop as at a hand-back
  (let [acted (volatile! false)
        end (with-redefs [agent/judge! (constantly {})]
              (work-with (fn [st called!] (called!) (vreset! acted true) (seon.turn/request-stop! :failed "the plan is wrong") (update st :turn inc))
                         #(if @acted {:reasons []} {:reasons [{:reason :proof-red}]})))]
    (is (= :failed (:outcome end)) (pr-str end))
    (is (= "the plan is wrong" (:reason end)) "with the reason it gave")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-broken-parser-reaches-the-model
  ;; docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map: two DeepSeek runs of md-scan
  ;; gave seon.ref/scan's emit step a new arity its calls did not pass, so scan threw on every call; the turn's merge check,
  ;; seon.reconcile/hairy, parsed the task's plan with it (seon.ref/names) before every model call, so two turns
  ;; in a row threw and the work ended :error before the model read its own error. Seon's own decision reads the
  ;; plan's cites, the facts its write derived, and the agent's broken parser costs only the parts that draw text:
  ;; the model is called each turn, with the error in what it is sent (the attached task is :whole, so the check runs)
  (let [[_ end msgs] (in-agent (fn [{:keys [task ns]}]
                                      ["(in-ns 'seon.ref) (clojure.core/defn scan [& _] (throw (ex-info \"Wrong number of args (6) passed to: fn\" {})))"
                                       (str "(select-keys (seon.turn/work! {:task " (pr-str task) " :max-turns 2}) [:outcome])")
                                       ;; answered in the agent: whether a request sent the error (each request is a whole context)
                                       "(boolean (some #(clojure.string/includes? (str %) \"Wrong number of args (6)\") (map :content (:requests @seon.agent/state))))"])
                                    {:config {:seon.config.ai/script 'seon.fixtures/two-calls}})
        end (:value end)]
    (is (not= :error (:outcome end)) (str "the broken parser never ended the work: " (pr-str end)))
    (is (= :cap (:outcome end)) (pr-str end))
    (is (true? (:value msgs)) "the model was sent the error")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 7 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~3250k, 4000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 7}} a-step-that-throws-answers-its-error-and-the-agent-goes-on
  ;; task seon.agent/a-death-names-its-cause, as the owner ruled 2026-10-30 (task
  ;; seon.agent/the-agent-is-a-flow-graph, step 4): a defect no request's own boundary catches (here
  ;; the agent redefines, at its REPL as an agent changes the platform, a function its REPL proc calls
  ;; after each eval: seon.agent/on-the-wire, which makes the reply) answers that request with the one error
  ;; map, and the agent goes on: it repairs the function at its REPL and works on, the same process
  (let [[_ pid broken repaired after pid' on-main] (in-agent (fn [_]
                                               ["(def p0 (seon.lib/c-getpid))"
                                                "p0"
                                                "(in-ns 'seon.agent) (clojure.core/defn on-the-wire [_] (throw (ex-info \"boom: the loop's own function\" {:x 1})))"
                                                "(in-ns 'seon.agent) (clojure.core/defn on-the-wire [r] (dissoc r :value))"
                                                "(+ 20 22)"
                                                "(seon.lib/c-getpid)"
                                                ;; the defect reaches root as data (its :platform problem on main), never only as text on
                                                ;; the agent's stderr: named, so it ends at root's quiet if it never comes
                                                {:op :eval :agent "root"
                                                 :code (str "(let [db (seon.system/await-db sys \"the agent's defect on main\""
                                                            " #(seq (datahike.api/q '[:find ?e :where [?e :seon.error/class :platform] [?e :seon.error/message ?m]"
                                                            " [(clojure.string/includes? ?m \"boom: the loop\")]] %)))]"
                                                            " (if (seon.contract/error? db) (:seon.error/type db) :on-main))")}]))]
    (is (= "boom: the loop's own function" (get-in broken [:ex :seon.error/message])) (str "the request is answered with its error: " (pr-str broken)))
    (is (= 'seon.repl/serve! (get-in broken [:ex :seon.error/operation])) "named by the boundary it reached")
    (is (some? repaired) (str "the repair is answered: " (pr-str repaired)))
    (is (= 42 (:value after)) (str "and the agent works on: " (pr-str after)))
    (is (= (:value pid) (:value pid')) "in the same process")
    (is (re-find #":on-main" (pr-str on-main)) (str "and the defect is on main as a :platform problem: " (pr-str on-main)))))

;; Estimate: about 10 pure take-value and test-run-answer steps at ~10k = ~100000.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-killed-test-run-names-the-test-that-was-running
  (testing "a child killed while running app-test/slow: that test has no verdict (proofs/results), and its death is named with how the child ended"
    (let [acc (agent/take-value {:streamed [] :ended #{}} {:seon.test/started "#'app-test/slow"})
          run (agent/test-run-answer acc {:signal 9})]
      (is (= {} (proofs/results run)) "a death is no verdict")
      (is (= [] (proofs/test-run-errors run)) "the death is the running test's, not an anonymous run error")
      (is (= ["#'app-test/slow" {:signal 9}] ((juxt :var :exit) (first (:reports run)))))))
  (testing "a child that put its answer last: the answer"
    (let [res {:pass 1 :fail 0 :error 0 :test 1 :reports [{:type :pass :var "#'app-test/fast" :message nil}]}]
      (is (= res (agent/test-run-answer (agent/take-value {:streamed [] :ended #{}} res) {:exit 0})))))
  (testing "a child that died once its test had ended: a run error, never the ended test's"
    (let [acc (reduce agent/take-value {:streamed [] :ended #{}} [{:seon.test/started "#'app-test/a"} {:seon.test/ended "#'app-test/a" :reports []}])]
      (is (nil? (:var (first (:reports (agent/test-run-answer acc {:exit 1}))))))))
  (testing "a child that died before any test started: one run error naming its exit"
    (let [run (agent/test-run-answer {:streamed [] :ended #{}} {:exit 1})]
      (is (= {} (proofs/results run)))
      (is (= 1 (count (proofs/test-run-errors run)))))))

;; Estimate: a helper, no test of its own.
(defn- merge-in-writer
  "This agent's merge-main! as its root answers it: the writer's merge on main's program (seon.writer/merge-main-into) on main's
  connection conn, in this process, standing for the :merge-main request it sends. See docs/merging.md#a-branch-merges-main-in."
  [conn cfg]
  ;; the request key an agent's state starts with (seon.agent/init!'s :tx-seq 0), which these tests' hand-made states leave out
  (swap! agent/state update :tx-seq #(or % 0))
  (with-redefs [agent/await-reply (fn [k m & _] (assoc (writer/merge-main-into conn cfg (:branch @agent/state)) :op :reply :k k))]
    (agent/merge-main!)))

;; Estimate: test-branch! ~1M, connect and 3 transacts ~0.5M, text-index build ~3M, merge-main! ~3M, 3 branch-changes at ~0.8M = ~10000000.
(deftest ^{:seon.test/estimate {:ticks 10000000}} after-merge-main-the-agent-judges-against-the-new-base
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
         (let [r (merge-in-writer conn cfg)]
           (is (some #{[:var 'app.m0/f1]} (:applied r)) "main's change merges in")
           ;; docs/vector.md#search: the merge reads none of main's moves, so it carries no index; the slot is keyed by its db, so the
           ;; next search builds it at the db the merge left, never answering from the branch's old one
           (is (not= [(:hash (agent/db)) (:max-tx (agent/db))] (first @@#'seon.doc/indexes)) "the index held is not claimed for the merged db")
           (seon.doc/text-index (agent/db))
           (is (= [(:hash (agent/db)) (:max-tx (agent/db))] (first @@#'seon.doc/indexes)) "the next search's index is at the db the merge left")
           (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity (agent/db) (:t r))))
               "in a merge commit that names its runtime")
           (is (= [ab (:branch cfg)] ((juxt :seon.tx/branch :seon.tx/merged) (d/entity (agent/db) (:t r))))
               "made on the agent's branch, of what it merged from main: a caught-up fact reads as main's (task seon.ctx/a-change-names-its-eval)"))
         (is (empty? (pm/changed-vars (agent/branch-changes (:base @agent/state) (agent/db))))
             "and is main's, not the agent's: its base is the merge base")
         (is (= {} (pm/divergence (agent/branch-changes (:base @agent/state) (agent/db))))
             "so the agent's divergence carries none of main's changes")
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

;; Estimate: test-branch! ~1M, 3 transacts and connect ~0.5M, 2 profile epochs ~1M, a mark-only merge and main-changes ~1M, then one real merge-main! ~3M = ~7000000.
(deftest ^{:seon.test/estimate {:ticks 7000000}} a-merge-of-a-main-moved-only-by-records-takes-them
  ;; docs/storage.md#mains-content-mark: main moves on every request by records (an agent's status, embeddings); the agent's
  ;; merge of main takes them as any fact (its [:datum e a] keys, data-attrs :all), and what main changed of the program since the base answers
  ;; from the mark, with no diff.
  ;; In this process, on a branch of the test store, with a socketpair standing for root
  (ts/test-branch!
   (fn [cfg conn]
     (let [mark! #(d/transact conn [{:seon.system/id :root :seon.system/content-t (inc (:max-tx @conn))}])
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
         (let [[r _] (calls #(merge-in-writer conn cfg))]
           ;; owner, 2026-11-14: "Facts are facts at a certain tx time": a record main wrote reaches the branch as any fact does
           (is (every? #(= :datum (first %)) (:applied r)) "no program key moved: what merged are main's facts")
           (is (= :busy (:seon.agent/status (d/entity @store-conn [:seon.agent/key "test/a1"]))) "main's record merged onto the branch"))
         (let [[r n] (calls #(agent/main-changes forked (agent/heard-main)))]
           (is (= {} r) "main changed nothing of the program since the base")
           (is (nil? (n 'seon.merge/content-datoms)) "read from the mark"))
         (let [[v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f1] [?v :var/form ?f] [?f :form/ns ?n]] forked)]
           (d/transact conn [{:db/id "f1'" :form/id "app.m0/f1-redefined" :form/ns nsx :form/order 100 :form/defines [v]
                              :form/src "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 100))"}
                             [:db/add v :var/form "f1'"]])
           (mark!))
         (is (some #{[:var 'app.m0/f1]} (:applied (merge-in-writer conn cfg))) "a definition and its mark: main's change merges in")
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

;; Estimate: test-branch! ~1M, 2 redefinitions and a commit ~0.5M, the writer merging the branch up ~4M, heard-main ~0.5M, one merge-main! ~3M = ~10000000.
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-pull-after-its-own-merge-starts-from-what-it-merged
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
               up (:r (first (:to-api (second (#'writer/writer-proc w :in {:op :merge :branch ab :proved-t (:max-tx @conn) :dest {:rid 1}})))))]
           (is (:merged? up) (str "root merges the agent's branch up: " (pr-str up))))
         (redefine "app.m0/f1-second" "(+ x 2)")
         (agent/heard-main)
         (let [r (merge-in-writer conn cfg)]
           (is (= [] (:conflicts r)) "its own merged edit is no change of main's")
           (is (= "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))" (src (agent/db))) "and its second edit stands"))
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

;; Estimate: 5 rounds of note, branch-changes (~1M) and branch-graph (~3M) ~20M, one merge-main! ~3M, test-branch! ~1M, stale-dbs walks = ~30000000.
(deftest ^{:seon.test/estimate {:ticks 30000000}} an-agent-keeps-no-db-older-than-its-branchs-current
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
           (is (some #{[:var 'app.m0/f1]} (:applied (merge-in-writer conn cfg))) "main's change merges in")
           (is (= [] (stale [:base :main])) "right after a merge of main, whose db-after is not the branch's now")
           (note 5)
           (agent/branch-changes (:base @agent/state) (agent/db))
           (agent/branch-graph (agent/db) (:base @agent/state) in)
           (is (= [] (stale [:base :main])) "and after more of its own"))
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

;; Estimate: test-branch! ~1M, 2 transacts ~0.5M, merge-main! ~3M, task read ~0.3M, one delta-changes of the task keys ~1M = ~6000000.
(deftest ^{:seon.test/estimate {:ticks 6000000}} merge-main-brings-the-task-content-main-carried
  ;; live finding F3 (pipeline 1): a session's merge-main! moved its base to main's head but
  ;; brought only the program, so the task content main had carried since the fork (another
  ;; session's design: its plan and registered proof) read at the agent's end as this branch
  ;; reverting it, and the end's carry wrote that revert onto main; the other session's
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
         (merge-in-writer conn cfg)
         (let [x (task/task (agent/db) "demo/x")]
           (is (= #{'demo-test/x-design-wrote-test} (set (:seon.task/proofs x))) "main's carried proof is on the branch")
           (is (= "Make x. The design: f1 adds 100." (:seon.task/plan x)) "and its plan"))
         (is (not-any? #(= :task (first %)) (keys (seon.merge/delta-changes @conn (agent/db) #{})))
             "so the agent's end merges nothing of it back onto main")
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

;; Estimate: delivery-world ~2.5M, message transact, 2 deliver! at ~1M, a removal commit ~0.5M, 4 reads of @ctx = ~5000000.
(deftest ^{:seon.test/estimate {:ticks 5000000}} one-message-reaches-two-agents
  ;; docs/users.md#messages: one entity to two agents; each holds it; a removal retracts that agent's own ref only
  (delivery-world
   (fn [{:keys [conn as ka kb heads]}]
     (let [body (str "to both " (random-uuid))
           _ (d/transact conn (task/message-tx "m-both" (seon.task/sender "owner") [ka kb] body 1000))
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

;; Estimate: delivery-world ~2.5M, deliver! ~1M, 2 ctx builds at ~2M, a removal commit ~0.5M = ~8000000.
(deftest ^{:seon.test/estimate {:ticks 8000000}} a-removed-message-is-not-delivered-again
  ;; docs/users.md#messages: the entity stays on the branch, so it is delivered once; the model's request no longer holds it
  (delivery-world
   (fn [{:keys [conn as ka cfg]}]
     (let [body (str "once " (random-uuid))
           _ (d/transact conn (task/message-tx "m-once" (seon.task/sender "owner") [ka] body 1000))
           says (fn [] (let [db (agent/db) a (pdb/eid db :seon.agent/key ka)]
                         (pr-str (seon.fixtures/tree-messages (:tree (seon.ctx/build (seon.db/index) db a (:branch @agent/state))) db))))]
       (as ka #(do (is (= [{:id "m-once" :from {:seon.agent/key "owner"} :body body :sent-ms 1000}] (agent/undelivered)) "main holds it for this agent")
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
  "The ids of the messages the running agent's turn n reads, as its record holds them (seon.ctx/placed-messages) on its branch's db."
  [n]
  (let [db (agent/db) k (:key @agent/state) c (seon.ctx/agent-ctx (seon.db/index) db (pdb/eid db :seon.agent/key k))]
    (mapv #(get-in c (conj % :seon.message/id)) (get (seon.ctx/placed-messages (seon.db/index) c) n []))))

;; Estimate: delivery-world ~2.5M, a transact, 4 undelivered queries ~0.3M each, one deliver! ~1M = ~5000000.
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-message-taken-by-one-agent-is-still-owed-to-the-other
  ;; docs/users.md#messages (owner, 2026-11-09: the message on the agent's branch is the proof of delivery): a's taking it writes a's branch alone
  (delivery-world
   (fn [{:keys [conn as ka kb heads]}]
     (d/transact conn (task/message-tx "m-two" (seon.task/sender "owner") [ka kb] "to both" 1000))
     (is (= ["m-two"] (as ka #(mapv :id (agent/undelivered)))) "owed to a")
     (as ka #(agent/deliver!))
     (is (= [] (as ka #(agent/undelivered))) "a took it")
     (is (= ["m-two"] (as kb #(mapv :id (agent/undelivered)))) "and b is owed it still: a's taking changes nothing for b")
     (is (= [] (d/q '[:find [?id ...] :where [_ :seon.message/id ?id]] (heads kb))) "b's branch holds none"))))

;; Estimate: delivery-world ~2.5M, 3 turn-at! at ~0.4M, 4 read-at (ctx build) at ~0.5M, a transact and commit, 2 undelivered = ~7000000.
(deftest ^{:seon.test/estimate {:ticks 7000000}} a-message-synced-from-main-is-delivered-and-read-by-the-next-turn
  ;; docs/users.md#messages: a merge or sync of main brings the message entity onto the branch, and that is its delivery: no longer owed, read once, by the first turn whose basis is at or after it
  (delivery-world
   (fn [{:keys [conn as kb]}]
     (d/transact conn (task/message-tx "m-sync" (seon.task/sender "owner") [kb] "synced" 1000))
     (as kb #(do (is (= ["m-sync"] (mapv :id (agent/undelivered))) "owed before the sync")
                 (turn-at! 1)
                 ;; what a merge of main writes onto the branch: the entity as main holds it
                 (agent/transact! (task/message-tx "m-sync" (seon.task/sender "owner") [kb] "synced" 1000))
                 (agent/commit!)
                 (is (= [] (agent/undelivered)) "on the branch: delivered, though no turn marked it")
                 (is (= [] (agent/deliver!)) "so a turn's delivery brings nothing again")
                 (turn-at! 2)
                 (is (= [] (read-at 1)) "the turn before the sync does not read it")
                 (is (= ["m-sync"] (read-at 2)) "the next turn reads it, once")
                 (turn-at! 3)
                 (is (= [] (read-at 3)) "and no turn after")))))) 

;; Estimate: delivery-world ~2.5M, 3 turn-at! ~1.2M, 2 deliver! ~2M, 4 read-at ~2M, queries = ~8000000.
(deftest ^{:seon.test/estimate {:ticks 8000000}} a-private-branch-is-owed-a-message-until-its-own-act-delivers-it
  ;; docs/users.md#messages: nothing merged from main: owed until seon.agent/deliver! writes it on the branch; read by the next turn; one delivered after a turn's basis reads at the turn after
  (delivery-world
   (fn [{:keys [conn as kb]}]
     (d/transact conn (task/message-tx "m-own" (seon.task/sender "owner") [kb] "own" 1000))
     (as kb #(do (turn-at! 1)
                 (is (= ["m-own"] (mapv :id (agent/undelivered))) "owed: nothing of main's is on the branch")
                 (is (= ["m-own"] (mapv :id (agent/deliver!))))
                 (is (= [] (agent/undelivered)) "delivered once written")
                 (turn-at! 2)
                 (is (= [] (read-at 1)))
                 (is (= ["m-own"] (read-at 2)) "read at the next turn")
                 (testing "delivered mid-turn, after the turn's basis: read at the next turn's start, not the turn in force"
                   (d/transact conn (task/message-tx "m-mid" (seon.task/sender "owner") [kb] "mid" 2000))
                   (agent/deliver!)
                   (is (= ["m-own"] (read-at 2)) "turn 2 is as it was")
                   (turn-at! 3)
                   (is (= ["m-mid"] (read-at 3)))))))))

;; Estimate: delivery-world ~2.5M, 2 transacts, one merge-main! ~3M, 3 deliver! ~3M, 4 queries = ~9000000.
(deftest ^{:seon.test/estimate {:ticks 9000000}} a-program-pull-leaves-messages-alone
  ;; docs/users.md#messages: merge-main! brings the program, never another agent's messages
  (delivery-world
   (fn [{:keys [conn cfg as ka kb heads]}]
     (let [body (str "for b " (random-uuid))
           _ (d/transact conn (task/message-tx "m-b" (seon.task/sender "owner") [kb] body 1000))
           [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f1] [?v :var/form ?f] [?f :form/ns ?n]] @conn)
           _ (d/transact conn [{:db/id "f1'" :form/id "app.m0/f1-moved" :form/ns nsx :form/order 100 :form/defines [v]
                                :form/src "(defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 100))"}
                               [:db/add v :var/form "f1'"]])
           on-branch (fn [k] (d/q '[:find [?id ...] :where [_ :seon.message/id ?id]] (heads k)))]
       (as ka #(is (true? (:ok (merge-in-writer conn cfg))) "the pull applied main's program change"))
       (is (= [] (on-branch ka)) "and no message of b's came with it")
       (is (= [] (on-branch kb)) "b's branch holds none until b's own delivery")
       (as ka #(is (= [] (agent/deliver!)) "a is sent none"))
       (as kb #(do (agent/deliver!) (is (= [body] (messages-held))) (is (= ["m-b"] (on-branch kb)))))))))

;; Estimate: delivery-world ~2.5M, a turn commit, 3 ctx builds, 3 deliver!, the branch's delta-changes ~3M and merge-db ~1M, a removal commit = ~10000000.
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-message-through-removal-merge-and-restart
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
                                      [{:seon.eval/id "e1" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key ka} :seon.eval/code "(+ 1 2)" :seon.eval/call (task/call-ref ka 1)
                                        :seon.eval/response {:handle 'result/e1 :value 3}}])))
       (let [before (as ka #(texts (says)))
             _ (d/transact conn (task/message-tx "m-late" (seon.task/sender "owner") [ka kb] body 1000))
             _ (as ka #(agent/deliver!))
             after (as ka #(texts (says)))
             at (fn [ms] (first (keep-indexed (fn [i m] (when (str/includes? m body) i)) ms)))]
         (is (some? (at after)) "the message is in the request")
         (is (= before (subvec after 0 (count before))) "every earlier byte of the request is as it was")
         (is (< (dec (count before)) (at after)) "and it comes after the turn's reply and evals")
         (as ka remove-held!)
         (let [base (pdb/branch-base conn (:branch cfg) (pdb/on-main (:branch cfg) :msg-a)) head (heads ka) main @conn
               ;; the branch's own change to main's program and content since its base (its turn's records are the agent's, never merged)
               ours (pm/delta-changes base head #{})]
           (is (empty? ours) "merged up, the branch has no change of its own from the message")
           (d/merge-db conn #{(pdb/on-main (:branch cfg) :msg-a)} [] (pdb/tx-meta (:branch cfg))))
         (as ka #(do (is (= [] (agent/undelivered)) "after the merge, held anew: not delivered again")
                     (is (= [] (agent/deliver!)))
                     (is (= [] (messages-held)))))
         (as kb #(do (is (= 1 (count (agent/undelivered))) "another recipient is still owed it")
                     (agent/deliver!)
                     (is (= [body] (messages-held)))))
         (is (= #{ka kb} (set (map :seon.agent/key (:seon.message/to (d/entity @conn [:seon.message/id "m-late"]))))) "main still names both"))))))

;; Estimate: test-branch! ~1M, 4 transacts ~0.8M, branch and connect ~0.6M, 2 untaken-facts, one deliver! ~1M, 3 ctx reads = ~4000000.
(deftest ^{:seon.test/estimate {:ticks 4000000}} an-opened-eval-on-main-reaches-the-agents-ctx-at-its-next-take
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
                                     {:seon.eval/id "e1" :seon.eval/agent [:seon.agent/key k] :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "1" :seon.eval/response {}}])
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

;; Estimate: test-branch! and connect ~1.5M, 5 eval-string calls at ~1M, 2 transact! and 4 commit! at ~0.2M = ~8000000.
(deftest ^{:seon.test/estimate {:ticks 8000000}} an-agents-transactions-name-their-runtime
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

;; Estimate: 6 eval-string calls at ~0.5M, ~50 frames read at ~5k, one future = ~3000000.
(deftest ^{:seon.test/estimate {:ticks 3000000}} an-evals-output-streams-as-it-is-written
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

;; Estimate: 3 pure agent-frame steps at ~30k = ~100000.
(deftest ^{:seon.test/estimate {:ticks 100000}} an-agents-stream-frame-goes-to-the-web-procs-streams
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

;; Estimate: evals on a world (built once a process, ~3.2M) at ~0.3M each; measured a few M
(deftest ^{:seon.test/estimate {:ticks 25000000}} an-eval-run-again-replaces-itself
  ;; docs/turn.md#an-eval-can-be-run-again: through a real agent's REPL, the same record under its own id, its author and call kept,
  ;; its old code in history, and a run down to an eval goes in order and stops at the first that fails
  (let [_ '[seon.repl/again seon.repl/again-to] ; run by string below, named so a change to them reruns this test
        probe "(fn [id] (let [db (seon.agent/db) e (seon.db/eid db :seon.eval/id id) p (datahike.api/pull db '[:seon.eval/code :seon.eval/by {:seon.eval/call [:seon.model-call/id]}] e)] (assoc p :e e :old (vec (distinct (map :v (filter #(= :seon.eval/code (:a %)) (datahike.api/datoms (datahike.api/history db) :eavt e))))))))"
        [_ a1 a2 a3 _ again-r edited missing to-all to-part probe-r]
        (mt/eval-in ["(def order (atom []))"
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 4 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2500k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 4}} an-agent-resets-to-a-point-and-goes-on
  ;; docs/turn.md#an-agent-goes-back-to-an-eval: one call, a new branch from the eval's commit, the old branch as it was, the same key on both, and the agent goes on
  (let [back (fn [{:keys [agent task]}]
               (str "(let [conn (:conn sys) a (keyword " (pr-str agent) ") task " (pr-str task)
                    "      b (:branch (get-in (seon.server/agents-answer sys) [:agents a]))"
                                        "      codes (fn [db] (set (datahike.api/q '[:find [?c ...] :where [_ :seon.eval/code ?c]] db)))"
                    "      id (datahike.api/q '[:find ?i . :where [?e :seon.eval/code \"(def here 2)\"] [?e :seon.eval/id ?i]] (seon.db/head conn b))"
                    "      missing (seon.system/back! sys a {:eval \"no-such-eval\"})"
                                        "      _ (clojure.core/deref (seon.system/submit! sys a (str \"(seon.agent/transact! [{:seon.eval/id \" (pr-str id) \" :seon.eval/opened true}])\")))"
                    "      before (codes (seon.db/head conn b))"
                    "      two [(#'seon.system/request! sys 'back! a {:eval id}) (#'seon.system/request! sys 'back! a {:eval id})]"
                    "      second-call (clojure.core/deref (second two))"
                    "      new (:id (clojure.core/deref (first two)))"
                    "      nb (:branch (get-in (seon.server/agents-answer sys) [:agents new]))"
                    "      went-on (clojure.core/deref (seon.system/submit! sys new \"(do (def there 3) (:key (clojure.core/deref seon.agent/state)))\"))]"
                    "  {:missing (:seon.error/message missing) :second (:seon.error/message second-call) :was (name a) :new new :old-branch b :new-branch nb"
                    "   :old-kept (= before (codes (seon.db/head conn b))) :at (codes (seon.db/head conn nb))"
                    "   :task (let [on-main (seon.system/await-db sys #(= (seon.db/off-main (seon.db/conn-main conn) nb) (:seon.task/branch (datahike.api/entity % [:seon.task/id task])))) t (datahike.api/entity on-main [:seon.task/id task])] {:outcome (:seon.task/outcome t) :agent-branch (:seon.agent/branch (:seon.task/agent t)) :task-branch (:seon.task/branch t)}) :went-on (:value went-on) :this-boots (let [k (:value went-on) on-main (seon.system/await-db sys #(= new (seon.task/this-boots % (datahike.api/entity % [:seon.agent/key k]))))] (and (:max-tx on-main) (contains? (seon.task/agents on-main) k) (some? (seon.server/agents-answer sys))))})"))
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-sentence-on-roots-page-is-a-task-an-agent-works
  ;; docs/tasks.md: the box on root's page asks for work; it no longer starts an agent with no task
  (let [r (peek (in-agent ["(+ 1 1)"
                           {:op :eval :agent "root"
                            :code (str "(let [blank (clojure.core/deref (seon.system/ask! sys {:task \"  \"}))"
                                       "      a (clojure.core/deref (seon.system/ask! sys {:task \"Make the Dashboard greet people!\"}))"
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

;; Estimate: in-agent's own setup and teardown (2 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~3750k, 4000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 3}} an-agent-launches-an-agent-on-a-task-of-its-own
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-redefinition-in-an-agent-reaches-a-caller-compiled-into-the-binary
  ;; docs/source.md#the-binary-records-its-program: an agent runs main's program compiled into the binary, and a form it
  ;; evaluates is its branch's program, so a redefinition reaches the code main's binary compiled, its direct-linked calls
  ;; included (Jolt writes a redefinition through to its direct-linked sites). Written green: the property holds on the
  ;; release build today (an agent's redefinition of seon.lib/r2 reached the compiled seon.agent/mem-mb, 2026-11-12); this
  ;; keeps it from regressing if Jolt's build changes. seon.fx/unreadable, compiled, compares its path with
  ;; seon.config/shipped-file: redefined, the same call takes the config's branch
  (let [[before _ after] (in-agent ["(seon.fx/unreadable \"zz.edn\" \"{:a 1}\")"
                                    "(in-ns 'seon.config) (def shipped-file \"zz.edn\")"
                                    "(seon.fx/unreadable \"zz.edn\" \"{:a 1}\")"])]
    (is (nil? (:value before)) (str "before: the EDN reads " (pr-str before)))
    (is (re-find #"the config does not satisfy" (str (:value after))) (str "after: the compiled caller took the redefined value " (pr-str after)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 1 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~1750k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 1}} a-wait-on-main-whose-test-throws-answers-the-throw-and-the-write-stands
  ;; docs/flow.md#the-step-fn-contract-every-proc-follows: a wait on main (seon.system/await-db) is the api's state, checked on each
  ;; commit as of that commit, never a watch run inside the writer's commit. A watch's pred that threw went into the writer's
  ;; reset! inside commit-batch: the stored commit was taken for a failed one (refused, then retried), and the wait was never
  ;; answered (bin/check problems overran at its wait on the stall, web's land and runtime's, 2026-11-12). Red before: the
  ;; write answered refused (and the wait never ends: a hang is this test's red)
  (with-agent {}
    (fn [{:keys [eval-root]}]
      (let [ev (comp root-value eval-root)
            text (str "await-throws-" (random-uuid))
            r (ev (str "(let [p (future (seon.system/await-db sys (fn [db] (when (datahike.api/q '[:find ?e . :in $ ?t :where [?e :note/text ?t]] db " (pr-str text) ")"
                       "                                                  (throw (ex-info \"the wait's test threw\" {}))))))"
                       "      w @(seon.system/transact-main! sys [{:note/text " (pr-str text) " :note/agent :t}])]"
                       "  (if (:ok w) [:written (:seon.error/message @p)] [:refused w]))"))]
        (is (= [:written] (take 1 r)) (str "the write stands: " (pr-str r)))
        (is (re-find #"the wait's test threw" (str (second r))) (str "and the wait answers the throw: " (pr-str r)))))))

(def ^:private not-running
  "Root-REPL code: a function true of the outcomes a task's work stands at once its agent's end is judged and it waits on nothing of
  root's (not nil, :running, :ready or :gate: a merge follows those)."
  "(fn [o] (contains? #{:parked :cap :told :reprove :done :merged :dead :lost :closed :refused} o))")

(defn- settled-code
  "Root-REPL code that waits on root for task w's agent to stand its task at outcome n times (the task's outcome written o n times, by
  its history), or for the task to stand at another outcome; answers the task as pulled, with its agent's key and id."
  {:malli/schema [:=> [:cat :seon.task/id :seon.task/outcome pos-int?] :string]}
  [w o n]
  (str "(seon.system/await-db sys \"the agent stood at " o ", or its task stood otherwise\""
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
  "(fn [w] (:db @(seon.system/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch w)}})))")

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
       "      end! (fn [] @(c/kill! sys bid) :gone)"
       "      send! (fn [] @(c/submit! sys :" a " (str \"(my.agent/send-message [\" (pr-str bid) \"] \" (pr-str body) \")\") {:origin :outside}))]"
       "  (if " before-send? " (do (end!) (send!)) (do (send!) (end!)))"
       "  {:body body :b bid :bkey bkey :sender " (pr-str a)
       "   :found (datahike.api/q '[:find ?k ?from :in $ ?body :where [?m :seon.message/body ?body] [?m :seon.message/to ?a] [?a :seon.agent/key ?k] [?m :seon.message/from ?fe] [?fe :seon.agent/key ?from]]"
       "                          (:db @(seon.system/call! sys {:op :write :req {:op :branch-db :branch :db}})) body)}"
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-send-survives-its-receiver-dying
  ;; docs/users.md#messages: an agent's send is one entity on main, to the recipient's agent, so it is there however the recipient ends
  (let [[_ r] (in-agent #(send-test-forms % false) waiting-b)
        {:keys [body bkey found sender]} (:value r)]
    (is (string? body) (pr-str r))
    (is (= #{[bkey sender]} found) "the one message entity on main, to B, from A")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-send-to-an-agent-with-no-process-waits-on-main
  ;; docs/users.md#messages: the recipient has no process (it ended), yet its entity is on main with its branch, so the send is the same fact
  (let [[_ r] (in-agent #(send-test-forms % true) waiting-b)
        {:keys [body bkey found sender]} (:value r)]
    (is (string? body) (pr-str r))
    (is (= #{[bkey sender]} found) "the send is not refused: the entity is on main, to B, from A")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-reply-is-recorded-before-its-evals-run
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

;; Estimate: one seon.model/complete under {:kind :none} (a case and a typed-error map, ~5k ticks, contract check of the request and the
;; failure ~50k) + one seon.turn/next-of on its answer (a valid?, a property lookup, a cond: ~30k) = ~100k ticks, ~1 MB, ~1 ms (was a spawned
;; root and agent, 117,443 ms measured)
(deftest ^{:seon.test/estimate {:ticks 200000 :bytes 1000000}} a-root-with-no-provider-ends-the-work-on-its-typed-error
  ;; a root whose provider is :none (with no --profile it is DeepSeek) answers the first turn's model call with :seon.model/no-provider;
  ;; the turn ends the work with that error (never retried: no turn changes the config), and the end carries the error the task's
  ;; record on main and my.root/problems' :error rows read. The call is seon.model/complete, and its answer goes to the turn's
  ;; decision (seon.turn/next-of) as the loop passes it
  (let [request {:messages [{:role "user" :content "Work."}] :task "demo/m" :turn 1}
        answer (seon.model/complete {:kind :none} request)
        st {:task "demo/m" :turn 1 :max-turns 5}
        [step st' outcome reason error] (@#'seon.turn/next-of st answer)]
    (is (= :seon.model/no-provider (:seon.error/type answer)) (pr-str answer))
    (is (= [:seon.turn/end :error] [step outcome]) "no provider ends the work: no next turn, no retry")
    (is (= st st') "and the run's state is not counted as a retryable failure")
    (is (= :seon.model/no-provider (:seon.error/type error)) "with its typed error for whoever can act on it")
    (is (= (:seon.error/message answer) reason))))

(defn- process-proof
  "Agent code writing test var seon.repl-test/name, a process test (it reaches an agent), asserting (+ 1 1) is n."
  {:malli/schema [:=> [:cat :string :int] :string]}
  [nm n]
  ;; declared as a process test with its estimate, so health's caused classes (an undeclared or unestimated test) don't stand before its verdict
  (str "(do (in-ns 'seon.repl-test) (clojure.test/deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 1}} " nm
       " (let [[r] (seon.agent-test/in-agent (fn [_] [\"(+ 1 1)\"]))] (clojure.test/is (= " n " (:value r))))) (in-ns 'user) :ok)"))

;; docs/merging.md#the-test-service: a run on a checkout plans its process tests, check steps and build as items of the service, each at
;; the digest this branch gives it, so nothing is left to a gate
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 6 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~3000k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 6}} a-checkouts-plan-names-its-process-tests-steps-and-build
  (let [t 'seon.repl-test/a-process-item-of-the-plan
        ;; an edge each (seon.program/reach-step): what this judges runs in the agent, by a string no reference shows
        _ '[seon.agent/handle! seon.agent/process-plan seon.agent/test-plan seon.proofs/plan-of]
        plan (str "(let [r (:result (first (seon.agent/handle! {:op :tests :id :x :plan? true :process? true :mode :judge})))]"
                  " {:process (:process r) :deferred (:deferred r) :ids (get-in r [:steps \"ids\"]) :build (:build r)})")
        ;; a run naming tests or a step runs the steps it names and no other
        named (fn [names] (str "(set (keys (:steps (:result (first (seon.agent/handle! {:op :tests :id :x :plan? true :process? true :mode :judge :names '" names "}))))))"))
        [_ before _ after by-ns by-step] (in-agent (fn [_] [(process-proof "a-process-item-of-the-plan" 2) plan
                                                             "(do (in-ns 'seon.check) (clojure.core/defn ids [] :changed) (in-ns 'user) :ok)" plan
                                                             (named "#{seon.repl-test}") (named "#{seon.repl-test ids}")]))
        {:keys [process deferred ids build]} (:value before)]
    (is (string? (get process t)) (str "the process test the branch wrote is an item, at its digest: " (pr-str before)))
    (is (not-any? #{t} deferred) "and is no longer left to the gate")
    (is (and (string? (:digest ids)) (true? (:binary? ids))) (str "a check step with no green at its digest is an item, on the binary: " (pr-str ids)))
    (is (true? (:needed? build)) "so the tree's build is needed")
    (is (not= (:digest ids) (get-in after [:value :ids :digest])) (str "a change to what the step reaches moves its digest: " (pr-str after)))
    (is (= #{} (:value by-ns)) (str "a run naming only a namespace runs no step: " (pr-str by-ns)))
    (is (= #{"ids"} (:value by-step)) (str "and one naming a step runs it alone: " (pr-str by-step)))))

;; docs/merging.md#the-test-service: a tree proved as a merge (mode :main: every key with no green at its digest, as the shell's
;; bin/prove --merge plans it, seon.proofs/plan-of :merge) carries its generative-contract keys too, as items, where the run
;; dropped them (seon.agent/process-plan's (remove mg-key? ...)): a landing through the system proved less than the shell's
(deftest ^{:seon.test/estimate {:ticks 20000000}} a-merge-plan-carries-the-contract-keys
  ;; T1, in process on the world: seon.agent/process-plan of a plan of the world with one contracted function of a pure namespace added (a test
  ;; plan, a few thousand forms' digests: ~5 M ticks; the check-step plan over it, ~5 M; stored-results reads of every candidate)
  (let [db (mt/with (mt/world)
             (mt/code 'seon.ref (str "(ns seon.ref (:require [clojure.string :as str]))\n"
                                     "(defn first-sentence [s] s)\n"
                                     "(defn lead-sentence \"The first sentence of a section's lead paragraph, on one line.\""
                                     " {:malli/schema [:=> [:cat :string] :string]} [lead] (identity (first-sentence (str/join \" \" (map str/trim (str/split-lines lead))))))\n")))
        plan (agent/test-plan db db #{})
        mg (:mg (#'agent/process-plan {:plan plan :deferred []} nil))]
    (is (contains? (set (keys mg)) "mg/seon.ref/lead-sentence") (str "a contract key with no green at its digest is an item of the merge: " (pr-str mg)))))

;; docs/source.md#tests-run-by-reach: a db no store commit names (a test's world) is graphed whole, at its own size, never updated from the
;; graph its process holds. In an agent's test child that graph is main's whole program, and updating it down to the world cost
;; seon.agent-test/an-unchanged-branch-reads-no-test-as-changed 40M ticks (its budget; 0.88M where a test before it had left the world's
;; graph held, 4.4M under bin/test; context's evidence, 2026-11-14). Estimate: two small worlds' graphs ~3M each, one test plan ~3M = ~9M.
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-test-world-is-graphed-whole-never-updated-from-a-held-program
  (let [w (mt/world)
        [_ in] (agent/test-inputs w)
        other (seon.program-test/record w 'app.held "(defn held [] 1)")
        g0 (program/reach-graph other (:data-refs in))
        hits (atom 0)
        update0 program/reach-update
        before @agent/state]
    (try
      (swap! agent/state assoc :base-graph {:point ["held-commit" 1] :graph g0 :inputs in :value nil})
      (with-redefs [program/reach-update (fn [g db] (when (identical? g g0) (swap! hits inc)) (update0 g db))]
        (agent/test-plan w w #{}))
      (is (= 0 @hits) "the world was graphed whole, never the held program's graph updated down to it")
      (finally (reset! agent/state before)))))

;; docs/merging.md#verdicts-are-a-cache: base and branch are digested by the same inputs, so a branch that changed nothing selects
;; nothing as changed. Red before: the inputs design added the private checks, the index class and a process test's config
;; to the branch's inputs only, and an unchanged agent of the standing system selected 1,765 of 1,835 tests as changed
;; Estimate: one test plan of the world ~3M, index-of ~0.5M, 3 map comparisons = ~4000000.
(deftest ^{:seon.test/estimate {:ticks 4000000}} an-unchanged-branch-reads-no-test-as-changed
  (let [main (mt/world)
        plan (agent/test-plan main main #{})
        bd (:base-digests plan)]
    (is (seq bd) "the world holds tests")
    ;; the cause itself: base was indexed by the same inputs the branch was (a world's tests read no config, so their digests
    ;; alone cannot show it); seon.agent/base-graph keeps the inputs it indexed by
    (is (= (:inputs plan) (:inputs (:base-graph @agent/state))) "base and branch digested by one set of inputs")
    (is (= bd (select-keys (update-vals (program/index-of (:graph plan)) :digest) (keys bd))) "every test's branch digest is its base digest")
    (is (empty? (:selected plan)) (pr-str (take 5 (:selected plan))))))

;; "if another agent has already run the same exact test we remove those" (owner, 2026-11-07)
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 14 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~5000k, 5000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 5000000 :asks 11}} a-process-test-green-at-its-digest-asks-no-gate
  ;; docs/merging.md#verdicts-are-a-cache. Another's green at this digest answers; another's red does not (the test stays deferred, named and not
  ;; run until the one-build runner, docs/plan.md NOW), and a schema the branch registers moves the
  ;; digest, since a process test takes the schema class and an agent's inputs are its root's boot's (seon.agent/test-plan)
  (let [t 'seon.repl-test/a-process-proof-already-green
        ;; an edge each (seon.program/reach-step): what this judges runs across prove!'s message, which no reference shows, so a fix to it moved no digest here
        _ '[seon.agent/run-selection seon.agent/test-plan seon.agent/run-tests-forked]
        digest (str "(get (:digests (seon.agent/test-plan (seon.agent/db) (:base @seon.agent/state) #{'" t "})) '" t ")")
        hold (fn [verdict] (store-verdict t digest verdict ":another"))
        prove "(let [r (my.agent/prove)] {:proofs (:proofs r) :deferred (:deferred r)})"
        [_ _ red _ _ green before _ after _ after-script]
        (in-agent (fn [_] [(process-proof "a-process-proof-already-green" 2)
                           (hold {:pass 0 :fail 1 :error 0}) prove
                           (process-proof "a-process-proof-already-green" 3) (hold {:pass 1 :fail 0 :error 0}) prove
                           digest "(my.schema/register! :seon.gate-test/k :string)" digest
                           "(do (seon.agent/transact! [{:file/path \"bin/gate-test\" :file/text \"#!/bin/sh\\n\"}]) :ok)" digest])
                  {:proofs #{t}})]
    (is (= [t] (:deferred (:value red))) (str "another's red is not this agent's verdict: the test stays deferred. " (pr-str red)))
    (is (= {t :pass} (:proofs (:value green))) (pr-str green))
    (is (= [] (:deferred (:value green))) "a green at its digest is reused: nothing left unjudged")
    (is (not= (:value before) (:value after)) "a schema the branch registers moves a process test's digest")
    (is (not= (:value after) (:value after-script)) "and so does a script the branch writes")))

;; docs/sharing.md#a-functions-cost-is-a-fact (the owner, 2026-11-11: no derived or observed value that collides is stored; :seon.fn/stats
;; stops being written): what an agent's functions cost is measured live in its process, (:fn-costs @seon.agent/state), and no
;; commit or end writes a :seon.fn/* datom on the branch. Red before: the end wrote one :seon.fn/stats sum a function
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 4 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2500k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 4}} what-a-function-cost-is-measured-live-and-never-stored
  (let [called "(do (seon.lib/digest \"x\") (seon.lib/digest \"y\") (seon.agent/transact! [{:seon.eval/id \"probe\" :seon.eval/by {:seon.agent/key \"probe\"}}]) (seon.agent/commit!) :ok)"
        ended "(do (seon.agent/write-end! {:outcome :waiting}) :ok)"
        stored "(count (datahike.api/datoms (seon.agent/db) :aevt :seon.fn/stats))"
        live "(get-in @seon.agent/state [:fn-costs 'seon.lib/digest :calls])"
        [_ _ n-stored calls] (in-agent [called ended stored live])]
    (is (= 0 (:value n-stored)) (str "the branch holds no function cost: " (pr-str n-stored)))
    (is (<= 2 (or (:value calls) 0)) (str "the live measure holds the calls: " (pr-str calls)))))

(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 2}} a-test-that-waits-for-good-is-ended-by-a-bound-as-its-own-error
  ;; docs/merging.md#a-test-child-is-a-fork-of-a-live-agent: a test that waits for good (here after work on core.async's thread pool,
  ;; whose idle worker once kept a quiescence check from ever firing) is ended by its wall bound: ten times its estimate at the slowest
  ;; measured rate (~2 s for the 30,000 ticks it states: an a/thread call, then a wait), its dump first, the typed :seon.test/wall-spent its own :error. Replaces the
  ;; quiescence end, which could not tell a deadlock from a wait on a peer. Estimate: one agent eval and one test child's run of one
  ;; test, a few million ticks of the test process
  (let [t 'seon.problems-test/a-test-that-waits-for-good-after-an-a-thread
        write "(do (in-ns 'seon.problems-test) (clojure.test/deftest ^{:seon.test/estimate {:ticks 30000}} a-test-that-waits-for-good-after-an-a-thread (clojure.test/is (= 1 (clojure.core.async/<!! (clojure.core.async/thread 1)))) (let [p (promise)] (clojure.test/is (= 1 @p)))) (in-ns 'user) :ok)"
        [_ _ proved] (in-agent (fn [_] [;; the tick bound proved with it on, stated on its own branch (an agent holds its branch's config, never root's --set)
                                   "(do (seon.agent/transact! [{:config/key :seon.config.proofs/ticks-decide :config/form \"true\" :config/file \"resources/seon/config/default.edn\"}]) (seon.agent/commit!))" write "(let [r (my.agent/prove)] {:proofs (:proofs r) :error (:error r) :failures (mapv (juxt :seon.error/type :message) (filter #(= :error (:type %)) (:reports r)))})"]) {:proofs #{t}})]
    (is (= {t :error} (:proofs (:value proved))) (pr-str proved))
    (is (some #(#{:seon.test/wall-spent :seon.test/ticks-spent} (first %)) (:failures (:value proved))) (str "ended by a bound, typed: " (pr-str proved)))))

(deftest ^{:seon.test/estimate {:ticks 300000}} a-wall-bound-ends-a-parked-fiber-after-its-evidence
  ;; docs/merging.md#a-test-child-is-a-fork-of-a-live-agent: a wait that spends no ticks (a fiber parked on a channel, as a test's
  ;; start notice parks on a full link) is ended by its wall bound on the one timer: the typed :seon.test/wall-spent thrown into
  ;; that fiber, carrying Jolt's dump of every fiber (the evidence, also on stderr); a bound disarmed by the test's end never fires.
  ;; Estimate: two fibers, two timers, one dump: ~300k ticks of this thread
  (let [ended (fn [arm]
                (let [f (promise) r (promise)]
                  (jolt.fibers/spawn (fn [] (deliver f (jolt.host/current-fiber))
                                       (deliver r (try (a/<!! (a/chan)) :returned (catch Throwable e (ex-data e))))))
                  (arm @f)
                  r))
        fired (ended #(#'agent/arm-wall! % "#'app/parks" 50))
        disarmed (ended #(a/close! (:cancel (#'agent/arm-wall! % "#'app/parks" 50))))
        e (deref fired 2000 :no-answer)]
    (is (= :seon.test/wall-spent (:seon.error/type e)) (pr-str (dissoc e :dump)))
    (is (= [50 "#'app/parks"] [(:bound-ms e) (:test e)]) "naming its bound and its test")
    (is (string? (:dump e)) "with the evidence")
    (is (= :still-waiting (deref disarmed 200 :still-waiting)) "a disarmed bound never fires")))

;; docs/merging.md#a-test-child-is-a-fork-of-a-live-agent: a test run's fold (seon.agent/test-run-step) answers once each child's two ends
;; are held, its link's end and its reaped exit, whichever comes first. Run here through the real plumbing but the fork: a
;; core.async.flow graph holding the fold proc, its resume forking each shard's child by fork-test-child, whose
;; jolt.async.remote.flow/fork! makes a remote-pair whose other end (the child's) is opened in this process, the child's proc
;; (jolt.async.remote.flow/proc) and its :conns added by one update!; its exit watch (jolt.io-poller/exit-chan) a channel this
;; test closes, its reap (seon.lib/wait-status) {:exit 0}. Each value the fold takes is told on seen once its step returned,
;; so the test waits for no clock: it knows each end was taken before it delivers the next.
;; Estimate, this test's thread only (the fold, the children's procs and the link's pumps run on other carriers, and their
;; ticks are not its): 5 runs, each one update! of a one-proc plan (~10k ticks); 7 children, each a socketpair opened (~3k) and
;; an answer put on its end (~2k); some 20 channel takes and puts: ~100k ticks. (First estimated at 1M, as if the pumps', the
;; codec's and the fold's work were this thread's; measured 94k.)
(deftest ^{:seon.test/estimate {:ticks 100000}} a-test-runs-fold-answers-whichever-end-of-a-child-comes-first
  (let [saved @agent/state
        {:keys [error-chan]} (let [g (flow/create-flow {:procs {} :conns []})] (swap! agent/state assoc :flow g) (flow/start g))
        g (:flow @agent/state)
        seen (a/chan 64)
        ends (atom nil)
        ;; the fold, its every step test-run-step's: its ends kept, each value it took told on seen
        fold (fn ([] (agent/test-run-step))
               ([args] (let [st (agent/test-run-step args)] (reset! ends (:ends st)) st))
               ([st t] (agent/test-run-step st t))
               ([st in v] (let [r (agent/test-run-step st in v)] (a/>!! seen in) r)))
        ;; the run's children, in fork order: {:os :end (the child's end) :exit (its exit watch)}; a pid past any macOS pid
        kids (atom []) next-os (atom 99999000)
        exit-at-fork? (atom false)
        fork (fn [g pid _child {:keys [in out conns] :as opts}]
               (let [ropts (dissoc opts :in :out :conns :keep)
                     [end fd] (remote/remote-pair ropts)
                     kid {:os (swap! next-os inc) :end (remote/open fd ropts) :exit (a/promise-chan)}]
                 (swap! kids conj kid)
                 ;; the classic fork race: the child exits before the parent holds its pid
                 (when @exit-at-fork? (a/close! (:exit kid)))
                 (flow/update! g (fn [cfg] (-> cfg (assoc-in [:procs pid] (rflow/proc {:ins {in (:out end)} :outs {out (:in end)}}))
                                               (update :conns (fnil into []) conns))))
                 {:pid (:os kid)}))
        kid-of (fn [os] (first (filter #(= os (:os %)) @kids)))
        ;; fork-test-child tells the pid (:forked) after its exit watch started and before its state holds the pid (:by-os):
        ;; with exit-at-fork?, it waits here until the reaped exit is on the fold's ends (taken and put back), so the fold's
        ;; resume returns a state whose ends already hold that exit; each pid told goes on forked-c
        forked-c (a/chan 8)
        forked (fn [os] (when @exit-at-fork? (let [v (a/<!! @ends)] (a/>!! @ends v))) (a/>!! forked-c os))
        run (fn [n shards]
              (let [answer (a/promise-chan) pid (keyword "seon.test" (str "fold-" n))]
                (reset! kids [])
                (flow/update! g #(assoc-in % [:procs pid] {:proc (flow/process fold)
                                                           :args {:answer answer :plan {} :ticks 0 :budgets nil :shards shards :forked forked :say (fn [_])}}))
                ;; the fold forks in its resume, on its own thread: the run is the test's once every shard's child is told
                (dotimes [_ (count shards)] (a/<!! forked-c))
                {:pid pid :answer answer}))
        exit! (fn [k] (a/close! (:exit k)) (a/<!! seen))
        ;; the child's run: its answer, then its end closed as fork!'s child closes it (:out, then :sent awaited), then its socket
        close! (fn [k] (let [{:keys [out sent] :as e} (:end k)]
                         (a/>!! out {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var (str "#'app.fold/t" (:os k))}]})
                         (a/close! out) (a/<!! sent) (remote/close! e)
                         [(a/<!! seen) (a/<!! seen)]))
        done (fn [{:keys [pid answer]}] (let [ans (a/poll! answer)] (flow/update! g #(update % :procs dissoc pid)) ans))
        ;; the run's answer: n tests passed, its children's exit status held, and each ended child's run directory handed
        ;; back with it for removal after the answer (run-children!), never removed inside the fold's step
        answered (fn [r n] (let [[ans dirs :as got] (done r)]
                             (is (and (= n (:pass ans) (:test ans)) (zero? (:error ans)) (= {:exit 0} (:exit ans))) (pr-str got))
                             (is (= n (count dirs)) (str "one run directory per ended child, handed back with the answer: " (pr-str dirs)))))]
    (try
      (swap! agent/state assoc :fixtures-warm true :dirty? false)
      (flow/resume g)
      (with-redefs [rflow/fork! fork poller/exit-chan (let [real poller/exit-chan] (fn [os & more] (if-let [k (kid-of os)] (:exit k) (apply real os more)))) lib/wait-status (fn [_] {:exit 0})]
        (testing "the child's exit is reaped before the fold's state holds its pid"
          (reset! exit-at-fork? true)
          (let [r (run 0 [['app.fold/t]]) [k] @kids]
            (reset! exit-at-fork? false)
            (is (= :seon.process/end (a/<!! seen)) "the exit, on ends during the fold's resume, is taken once the resume returned")
            (is (nil? (a/poll! (:answer r))) "an exit alone is no answer")
            (is (= [:seon.link/up :seon.link/up] (close! k)))
            (answered r 1)))
        (testing "the exit first, then the link's end"
          (let [r (run 1 [['app.fold/t]]) [k] @kids]
            (is (= :seon.process/end (exit! k)))
            (is (nil? (a/poll! (:answer r))) "an exit alone is no answer")
            (close! k)
            (answered r 1)))
        (testing "the link's end first, then the exit"
          (let [r (run 2 [['app.fold/t]]) [k] @kids]
            (close! k)
            (is (nil? (a/poll! (:answer r))) "a closed link alone is no answer: the exit status is the run's")
            (exit! k)
            (answered r 1)))
        (testing "two children, their ends interleaved: one's closed arrives while child-ended's update! for the other applies"
          (let [r (run 3 [['app.fold/t] ['app.fold/u]]) [k1 k2] @kids]
            (a/close! (:exit k1)) (a/close! (:exit k2))
            ;; both links end at once, no wait between: k2's values reach the fold while it removes k1's proc
            (doseq [k [k1 k2]] (let [{:keys [out]} (:end k)] (a/>!! out {:pass 1 :fail 0 :error 0 :reports [{:type :pass :var (str "#'app.fold/t" (:os k))}]}) (a/close! out)))
            (doseq [k [k1 k2]] (a/<!! (:sent (:end k))) (remote/close! (:end k)))
            (dotimes [_ 6] (a/<!! seen))
            (answered r 2)))
        (testing "the second child's exit before the first's link end, the first's exit last"
          (let [r (run 4 [['app.fold/t] ['app.fold/u]]) [k1 k2] @kids]
            (close! k2) (exit! k2) (close! k1)
            (is (nil? (a/poll! (:answer r))) "a child still running: no answer")
            (exit! k1)
            (answered r 2)))
        ;; task seon.prove/a-test-that-dies-fails-only-itself: a child that dies before it names a running test, with none ended, is
        ;; no progress. Red before: the shard was done there, its tests never reported, so the service answered each no verdict
        (let [die! (fn [k] (let [{:keys [out sent] :as e} (:end k)] (a/close! out) (a/<!! sent) (remote/close! e) (a/<!! seen))
                            (exit! k))]
          (testing "a death with no progress runs the rest again in a new child"
            (let [r (run 5 [['app.fold/t 'app.fold/u]]) [k1] @kids]
              (die! k1)
              (is (nil? (a/poll! (:answer r))) "the rest runs again, so no answer yet")
              (a/<!! forked-c)
              (let [[_ k2] @kids]
                (close! k2) (exit! k2)
                (let [[ans] (done r)]
                  (is (= 1 (:pass ans)) (str "the new child's run is the shard's: " (pr-str ans)))))))
          (testing "two deaths with no progress between them answer each remaining test the death, its own :error"
            (let [r (run 6 [['app.fold/t 'app.fold/u]]) [k1] @kids]
              (die! k1)
              (a/<!! forked-c)
              (let [[_ k2] @kids]
                (die! k2)
                (let [[ans] (done r)
                      lost (into #{} (keep #(when (and (= :error (:type %)) (:exit %)) (:var %))) (:reports ans))]
                  (is (= #{"#'app.fold/t" "#'app.fold/u"} lost) (str "each test ends with its own result, never unreported: " (pr-str ans)))
                  (is (every? #(clojure.string/includes? (str (:message %)) "test process died:") (filter #(and (= :error (:type %)) (:exit %) (:var %)) (:reports ans)))
                      "and each names the death's own report, what the child threw at start, never only its exit status")))))))
      (is (nil? (a/poll! error-chan)) "no proc threw")
      (finally
        (flow/stop g)
        (reset! agent/state saved)))))

;; docs/errors.md#nothing-crashes-and-nothing-hangs: a wait on root ends with its answer or a typed error at its declared bound. The first
;; backlog task's seon_done waited 24+ minutes in an await-reply with an unbounded deref, while the merge gate's first batch hung
;; in a fork (runs/evidence/gate-stall-1346). The bound arrives on the one timer (seon.step/put-after!). Estimate: one wait of 50 ms on
;; a timer, a few allocations: ~0.2M ticks.
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-wait-on-root-ends-at-its-bound
  (with-redefs [agent/send! (fn [_] nil)]
    (let [k 987654321
          r (agent/await-reply k {:op :prove :k k} 50)]
      (is (= :seon.bound/reached (get-in r [:result :seon.error/type])) (pr-str r))
      (is (= {:seon.bound/ms 50} (get-in r [:result :seon.bound/bound])) "it names the bound it reached")
      (is (nil? (get-in @agent/state [:waits k])) "and holds no wait after"))))

;; Estimate: in-agent's own setup and teardown (~1.5M) + one ask of the test's own at ~0.25M = ~1750000; 2,000,000
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 1}} an-agents-start-value-is-its-procs-args-never-a-root
  ;; docs/flow.md#flow-inside-an-agent: the agent's start value goes to each proc of its graph as the flow def's :args, its init keeps it,
  ;; and each step binds it around the code it runs (seon.repl/with-start); the var has no root value, so nothing reads a process global.
  ;; Red under hold-start! (the root set at the process's start): the root held the agent's key
  (let [[r] (in-agent (fn [{:keys [task]}] [(str "{:root (.getRawRoot #'seon.agent/*start*) :key (:key seon.agent/*start*) :task (:task seon.agent/*start*)"
                                                 " :graph? (realized? (:graph seon.agent/*start*)) :task-asked " (pr-str task) "}")]))
        v (:value r)]
    (is (nil? (:root v)) (str "the start value has no root: " (pr-str r)))
    (is (string? (:key v)) (str "an eval reads the agent's key from its proc's binding: " (pr-str r)))
    (is (= (:task-asked v) (:task v)) "and its task")
    (is (true? (:graph? v)) "and its own graph, delivered once it exists")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-task-whose-proofs-pass-is-done-only-when-what-it-reaches-is-green
  ;; the owner's ruling: a turn runs the proofs, the tests the change reaches run once they are green, judged as the
  ;; judge judges them, before it is done. md-scan #24 ended green on its proofs and the judge found 18 reds its change
  ;; made: here a green proof beside a change that breaks main's edit test (seon.fixtures/whole-stray) does not end
  ;; green, and its next turn's status names a red its change made. The change breaks every test reaching
  ;; seon.repl/offsets that edits text, and a run is answered at its first red (docs/merging.md#the-test-service), its items
  ;; running at once, so which one the status names is the order they landed in: the claim is one of them, never a
  ;; particular one (red in batch 46 as flaky: it awaited the exact-text edit test and the copied-source one landed first)
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"ws\" :seon.task/plan \"Make ws.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/whole-stray}})"
                   " _ (seon.system/await-db sys \"the work ended\" #(" not-running " (:seon.task/outcome (datahike.api/entity % [:seon.task/id \"" % "/ws\"]))))"
                   " db @(:conn sys) t (seon.task/task db \"" % "/ws\")"
                   " b (:db @(seon.system/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"" % "/ws\")}}))"
                   " a (seon.db/eid b :seon.agent/key (:seon.task/agent t))]"
                   " [(:seon.task/outcome t) (seon.reconcile/task-state (seon.reconcile/index db) \"" % "/ws\") (mapv (juxt :reason :symbol) (:reasons (seon.ctx.status/status b (:tree (seon.ctx/build (seon.db/index) b a :b)) {})))])")
        [_ r reaching] (in-agent #(vector {:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))}
                                          "(my.agent/tests-reaching 'seon.repl/offsets)")
                                 {})
        [outcome state reasons] (:value r)
        broken (set (:value reaching))]
    (is (not (#{:done :merged} outcome)) (pr-str r))
    (is (not= :merged state) "not merged")
    ;; the two orders batch 46 saw: either red, landing first, is one the change broke
    (is (every? broken '[seon.repl-test/an-edit-replaces-exact-text-once-and-evaluates-the-definition
                         seon.repl-test/an-edit-copied-from-the-shown-source-applies])
        (pr-str reaching))
    (is (some (fn [[why t]] (and (= :stray-red why) (contains? broken t))) reasons)
        (str "its status names a test its change broke: " (pr-str reasons)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-red-whole-task-is-not-merged-and-its-next-turn-says-why
  ;; the other way: a whole task whose proof is red is not merged. Its task does not end red: it
  ;; parks (it waits, or reaches its cap), and its turn after the red proof carries why, the done
  ;; rule's reasons as data in the status it reads (a task root refuses at its end gets root's verdict
  ;; instead: seon.reconcile-test/a-whole-task-works-in-parallel-and-its-green-merges). The wait
  ;; ends on the agent's own end, which always comes
  (let [code #(str "(let [_ (my.root/request! sys {:seon.task/ns '" % " :seon.task/name \"wr\" :seon.task/plan \"Make wr.\" :seon.task/config {:seon.config.ai/script 'seon.fixtures/whole-red}})"
                   " _ (seon.system/await-db sys \"the work ended\" #(" not-running " (:seon.task/outcome (datahike.api/entity % [:seon.task/id \"" % "/wr\"]))))"
                   " db @(:conn sys) t (seon.task/task db \"" % "/wr\")"
                   " b (:db @(seon.system/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"" % "/wr\")}}))"
                   " a (seon.db/eid b :seon.agent/key (:seon.task/agent t))]"
                   " [(:seon.task/outcome t) (seon.reconcile/task-state (seon.reconcile/index db) \"" % "/wr\") (mapv :reason (:reasons (seon.ctx.status/status b (:tree (seon.ctx/build (seon.db/index) b a :b)) {})))])")
        [_ r] (in-agent #(vector {:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code (code (:ns %))})
                        {})
        [outcome state reasons] (:value r)]
    (is (#{:parked :cap} outcome) (pr-str r))
    (is (not= :merged state) "not merged")
    (is (= [:proof-red] reasons) "the status carries why, as data: the proof is red")))

;; Estimate: a test branch (ts/test-branch!: a pointer write, connect, release, delete ~1M), the system page loaded (~0.5M), the
;; stages' three tasks written (request-tx ~0.5M), ~8 transacts at ~50k, the boundary read off history (~50k) and two requests
;; rendered whole (seon.ctx.wire/render ~5M each: the agent's ctx built, the page walked and printed, the system page's text; first
;; put at 0.5M, which counted the messages and missed the build and the page) = ~12M: 12,000,000
(deftest ^{:seon.test/estimate {:ticks 12000000}} a-requests-implement-continues-its-designs-request
  ;; live finding A1, on a real request (the pipeline's: the implement's first request sent its own
  ;; task data in second place and no boundary, 512 of 35,126 tokens cached): a request made with the
  ;; staged choice (seon.template/stages, an experiment's: the default request is design-build and merge), its design started
  ;; on an agent; the implement's start, as the reconciler makes it, is on the design's session (seon.task/agent-key), root's
  ;; boundary message opens it (seon.reconcile/stage-boundary, read off the start), and its first request is the design's
  ;; last request whole as its prefix, then that boundary (seon.ctx.wire/render at each turn's basis, as seon.turn makes them)
  (ts/test-branch!
    (fn [_ conn]
      (d/transact conn (seon.doc/load-tx @conn "docs" {seon.doc/system-page (slurp (str (seon.config/checkout) "/" seon.doc/system-page))}))
      (d/transact conn (tpl/request-tx @conn (tpl/stages {:seon.task/ns 'demo :seon.task/name "st" :seon.task/plan "Make st."
                                                          :seon.task/proofs #{'demo-test/st-test}})))
      (let [env {:branch (task/default-branch "demo/st") :model nil :max-turns 9}
            start! (fn [id k ms] (d/transact conn (task/start-tx @conn id "boot-1" k env ms)))
            basis (fn [id n] {:commit (d/commit-id @conn) :task id :turn n :max-turns 9 :origin :model :agent "a1"})
            ;; as seon.turn goes: the turn recorded, then its request on the db that holds it
            turn! (fn [b] (d/transact conn (ts/turn-tx "a1" (:turn b) {:seon.model-call/basis b :seon.model-call/forms 1 :seon.model-call/ms 1}))
                    (seon.ctx.wire/render @conn (assoc b :commit (d/commit-id @conn))))
            _ (start! "demo/st" :a1 1000)
            _ (d/transact conn [{:seon.eval/id "e0" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key "a1"} :seon.eval/code "(e0)"
                                 :seon.eval/response {:handle 'result/e0 :value 1} :seon.eval/call (task/call-ref "a1" 0) :seon.eval/call-id "c-e0" :seon.eval/intent "Step."}])
            design (:messages (turn! (basis "demo/st" 1)))
            session (task/agent-key @conn "demo/st-implement")
            _ (start! "demo/st-implement" (keyword session) 2000)
            boundary (#'r/stage-boundary @conn "demo/st-implement")
            _ (d/transact conn (task/message-tx "m1" task/system-sender [session] boundary 1500))
            implement (:messages (turn! (basis "demo/st-implement" 2)))]
        (is (= "a1" session) "the implement continues the design's agent")
        (is (str/starts-with? (str boundary) "demo/st is done. Your task now is demo/st-implement") (pr-str boundary))
        (is (= (first design) (first implement)) "the system prompt, the same")
        (is (= design (subvec implement 0 (min (count implement) (count design)))) "the design's last request is the implement's exact prefix")
        (is (some #(str/includes? (str (:content %)) "Your task now is") (drop (count design) implement)) "then root's boundary")))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-waiting-agent-parks-and-a-message-wakes-the-same-process
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
                                         " @(seon.system/message! sys ag \"carry on\") " (parked-code w 2)
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-text-reply-stays-in-the-history
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
                                         "   @(seon.system/message! sys ag \"carry on\") " (parked-code w 2)
                                         "   (let [db (:db @(seon.system/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch " (pr-str w) ")}}))]"
                                         "     {:intent (sort (datahike.api/q '[:find [?p ...] :where [?e :seon.eval/intent ?p] (not-join [?e] [?e :seon.eval/by ?s] [?s :seon.system/id :root])] db))})))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/text-reply}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})]
    (is (= ["Saw my own reply."] (:intent (:value r))) (pr-str r))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-woken-agent-that-replies-stays-parked
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
                                         "   @(seon.system/message! sys ag \"carry on\") " (parked-code w 2)
                                         "   (let [t2 (turn-of) m2 (:db (deref (seon.system/call! sys {:op :write :req {:op :branch-db :branch :db}})))"
                                         "         _ (dotimes [_ 5] (deref (seon.system/call! sys {:op :write :req {:op :tx :branch :db :tx-data [{:seon.agent/key k :seon.agent/description \"a move of main\"}]}})))"
                                         "         t3 (turn-of)]"
                                         "     {:turns [t2 t3] :outcome (:seon.task/outcome (datahike.api/entity @(:conn sys) [:seon.task/id " (pr-str w) "]))})))")}]))
                        {:config {:seon.config.ai/script 'seon.fixtures/wait-call}
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :user}]})
        {:keys [turns outcome]} (:value r)]
    (is (= :parked outcome) (pr-str r))
    (is (apply = turns) (str "no more turns while nothing arrives: " (pr-str turns)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 1 asks of the test's own at ~0.25M (an eval's recorded answer decoded); 3 pure reply-of calls ~50k = ~1750k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/estimate {:ticks 300000}} a-reply-of-text-with-no-call-is-its-message-alone
  ;; docs/model.md#one-tool-repl: a provider's reply read by seon.model/reply-of: text and no call is its message alone; an eval is a call beside its
  ;; text; a call with no text runs and its message is empty. Pure on three provider replies (estimate: three reads ~50k ticks each)
  (let [reply (fn [text calls] {:usage {:prompt_tokens 10 :completion_tokens 2}
                                :choices [{:finish_reason "stop" :message (cond-> {:content text} calls (assoc :tool_calls calls))}]})
        call [{:id "c1" :function {:name "repl" :arguments "{\"intent\":\"read\",\"code\":\"(+ 1 2)\"}"}}]
        text-only (seon.model/reply-of (reply "Nothing to run; I wait for you." nil) 1 100)
        one-eval (seon.model/reply-of (reply "Reading." call) 1 100)
        no-text (seon.model/reply-of (reply nil call) 1 100)]
    (is (= ["Nothing to run; I wait for you." nil] [(:text text-only) (:tool-calls text-only)]) "text and no call: its message, no call")
    (is (= ["Reading." ["c1"]] [(:text one-eval) (mapv :id (:tool-calls one-eval))]) "an eval is a call beside its text")
    (is (= ["" ["c1"]] [(:text no-text) (mapv :id (:tool-calls no-text))]) "a call with no text: the call runs, the message is empty")))

(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 1}} a-reply-with-no-evals-stops-with-turns-left
  ;; docs/model.md#one-tool-repl: a reply with text and no call is its message alone: nothing runs and the
  ;; agent waits, with turns left (the reply's own reading is a-reply-of-text-with-no-call-is-its-message-alone)
  (let [[end] (in-agent #(vector (str "(seon.turn/work! {:task " (pr-str (:task %)) " :max-turns 5})"))
                          {:config {:seon.config.ai/script 'seon.fixtures/text-reply}})]
    (is (= [:waiting 1] ((juxt :outcome :model-calls) (:value end))) (str "the agent waits after one turn of five: " (pr-str end)))))

;; docs/reconcile.md#a-task-holds-how-its-work-ended: the pure half of a-task-green-before-its-work-does-not-end-at-turn-0 (which runs the scripted work).
;; The one measure of change, seon.merge/changed?, is false for a branch still at its base and true once it holds a definition; the done rule reads it:
;; a green proof beside an unchanged branch is refused :no-change, and no longer once the branch changed. Estimate: two small worlds ~3.2M once, two reads ~0.5M
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-green-proof-on-an-unchanged-branch-is-refused-no-change
  (let [base (mt/world)
        branch (mt/with base (mt/code 'pt.nc "(ns pt.nc)\n(defn f [] 1)\n"))
        p 'app.calc-test/total-sums
        explain (fn [changed?] (r/done-explain {:proofs #{p} :results {p :pass} :main-green #{} :allowed-red #{} :uncontracted #{} :kind :work :changed? changed? :named-site? true}))]
    (is (false? (pm/changed? base base)) "a branch at its base changed nothing")
    (is (true? (pm/changed? base branch)) "one that holds a definition did")
    (is (= [:no-change] (mapv :reason (explain false))) "the done rule refuses the unchanged branch, naming it")
    (is (= [] (explain true)) "and nothing once it changed")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-woken-agent-is-not-sent-its-own-loop
  ;; task seon.render/one-way-to-make-what-an-agent-is-sent, step 2: the eval that ran the parked work (seon.turn/work!) is
  ;; recorded when it returns, so a wake's request came to hold it as a message; it is no work of the agent's
  (let [[_ r] (in-agent (fn [{:keys [ns]}]
                          (let [w (str ns "/w")]
                            [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                             {:op :eval :agent "root"
                              :code (str "(do (my.root/run! sys [" (pr-str w) "]) " (parked-code w 1)
                                         " (let [a " (agent-code w) " k (:seon.agent/key a) ag (keyword k)]"
                                         "   @(seon.system/message! sys ag \"carry on\") " (parked-code w 2)
                                         "   (let [db (:db @(seon.system/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch " (pr-str w) ")}}))]"
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

;; Estimate: three calls of seon.turn's end checks (decide) on a status of one red proof, with the agent's db, turn number, specified-tx and
;; request stood in (no store read, no agent, no model call): ~3 x 30k ticks of the checks and a cond = ~100k, ~1 MB, ~1 ms (was 3 spawned
;; agents, 195,864 ms measured)
(deftest ^{:seon.test/estimate {:ticks 100000 :bytes 1000000}} a-task-at-its-cap-is-recorded-cap-never-waiting
  ;; root, 2026-10-26: a run whose last turn waits spent its budget, so it ends :cap, which a
  ;; live view reads as the budget spent, not as a wait for a message; a wait before the cap is
  ;; :waiting, and the agent's own fail! at the cap is :failed (its verdict holds the task).
  ;; The decision is seon.turn/decide's end checks, so it is called as the loop calls it: the turn in force, the stop the turn's
  ;; forms requested (my.turn/wait and my.task/fail! are request-stop!), and a status that is not green
  (let [old @seon.agent/state
        red {:reasons [{:reason :proof-red :symbol 'app.calc-test/total-sums}]}
        outcome (fn [stop max-turns]
                  (with-redefs [seon.agent/db (constantly nil)
                                seon.agent/turn-now (constantly 1)
                                seon.turn/specified-tx (fn [& _] nil)
                                seon.turn/model-request! (fn [& _] {:id "k/2" :request {}})]
                    (reset! seon.agent/state (assoc old :turn {}))
                    (apply seon.turn/request-stop! stop)
                    (let [[_ _ outcome] (@#'seon.turn/decide {:task "app.calc/total-is-exact" :max-turns max-turns} {} red)]
                      outcome)))]
    (try
      (is (= :cap (outcome [:waiting nil] 1)) "a wait on the last turn: the budget spent")
      (is (= :waiting (outcome [:waiting nil] 2)) "a wait with turns left: waiting")
      (is (= :failed (outcome [:failed "premise refuted"] 1)) "fail! on the last turn: the agent's verdict")
      (finally (reset! seon.agent/state old)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-failed-call-keeps-what-it-sent
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
    (is (= ["system" "user" "assistant" "tool" "assistant" "tool"] (:roles (:value turn))) "the system message, what opens the session, then the page as the call Seon made and its answer (seon.ctx.wire/page-diff-msgs)")
    (is (not (str/includes? (str (peek (:read (:value turn)))) "turns")) (str "the page announces no cap: " (peek (:read (:value turn)))))
    (is (= :seon.model/no-provider (:error (:value turn))) "and the typed error the call answered")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 1 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~1750k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 1}} a-whole-tasks-work-turns-are-no-merge-turns
  ;; the first whole-task run (2026-10-26): all ten requests ended :merge-failed at turn 3, each work
  ;; turn counted against :seon.config.merge/max-merge-turns (2) as a merge task's turns are; a
  ;; whole task's turn is a merge turn only while what both sides changed is unresolved
  (let [[end] (in-agent #(vector (str "(seon.turn/work! {:task " (pr-str (str (:ns %) "/w")) " :max-turns 4})"))
                        {:config {:seon.config.ai/script 'seon.fixtures/same-call}
                         :decls [{:seon.task/name "w" :seon.task/plan "Work." :seon.task/kind :whole :seon.task/proofs '#{demo-test/t}}]})]
    (is (= [:cap 4] ((juxt :outcome :model-calls) (:value end))) (pr-str (:value end)))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} a-proof-run-ends-at-its-budget-though-its-tests-run-bounded-evals
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 2 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2000k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 2}} an-agent-commits-after-each-eval
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 5 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2750k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 5}} a-model-turn-records-its-text-and-the-message-it-took
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
                                             "      msgs (fn [db] (vec (datahike.api/q '[:find ?id ?f ?b ?k :in $ ?k :where [?m :seon.message/id ?id] [?m :seon.message/from ?fe] [?fe :seon.agent/key ?f] [?m :seon.message/body ?b] [?m :seon.message/to ?a] [?a :seon.agent/key ?k]] db (:key @seon.agent/state))))]"
                                             "  {:calls (mapv (fn [[i c]] {:eval i :code c}) evals) :reply (:seon.model-call/reply m) :basis-keys (set (keys (:seon.model-call/basis m)))"
                                             "   :agent-key (:key @seon.agent/state) :on-branch (msgs db)"
                                             "   :turn (:seon.agent/turn (datahike.api/entity db [:seon.agent/key (:key @seon.agent/state)]))"
                                             "   :eval-turns (seon.fixtures/agent-eval-turns db (:key @seon.agent/state))"
                                             "   :loop-eval-turns (seon.fixtures/agent-loop-eval-turns db (:key @seon.agent/state))"
                                             "   :message-turns (let [c @seon.ctx/ctx] (vec (for [[n cid] (seon.ctx/turns c) :when (seq (get (seon.ctx/placed-messages (seon.db/index) c) n))] n)))"
                                             "   :call-ids (seon.fixtures/agent-call-ids db (:key @seon.agent/state))"
                                             "   :committed (msgs (datahike.api/commit-as-db db (get-in m [:seon.model-call/basis :commit])))"
                                             "   :intent (mapv #(datahike.api/q '[:find ?r . :in $ ?i :where [?e :seon.eval/id ?i] [?e :seon.eval/intent ?r]] db %) (map first evals))})")
                                        {:op :eval :agent "root"
                                         :code (str "(datahike.api/q '[:find ?id ?f ?to ?b :where [?m :seon.message/id ?id] [?m :seon.message/from ?fe] [?fe :seon.agent/key ?f]"
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
    (is (= #{[(:agent @names) 1]} (:eval-turns probed)) "the model's eval carries the turn, so does an outside eval after it (by the agent: an outside model works its task), the number in force; Seon ran no eval before the turn")
    (is (empty? (:loop-eval-turns probed)) "the turn loop's own eval, no conversation record, takes none")))

;; Estimate: one failed analysis, suggestions by edit distance over ~3000 names at ~1k, error-map = ~3000000.
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-compile-errors-line-and-suggestions-arrive-as-data
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

;; Estimate: one failed analysis (private var) and error-map, no suggestion search = ~100000.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-private-var-says-the-form-that-reaches-it
  ;; forgiving tools (data: 11 evals over 5 sessions died on "var: #'ns/f is not public", six in a row in one): Clojure's own
  ;; forms reach a private var, the var's value and a call through the var; the error says so
  (let [em (thrown "(seon.reader/fence \"x\")")]
    (is (= :analyze/private-var (:jolt.error/kind em)))
    (is (str/includes? (str (:seon.error/fix em)) "(#'seon.reader/fence") (pr-str em))
    (is (str/includes? (str (:seon.error/fix em)) "@#'seon.reader/fence") (pr-str em))))

;; Estimate: one malli schema failure and its error-map with the nearest name = ~500000.
(deftest ^{:seon.test/estimate {:ticks 500000}} a-schema-malli-does-not-know-is-named-with-its-nearest
  ;; data: a contract written [:time :inst] answered ":malli.core/invalid-schema" and nothing else; six turns went to
  ;; finding that the predicate is the symbol inst?
  (let [em (agent/error-map (try ((requiring-resolve 'malli.core/schema) [:vector :inst]) (catch Throwable e e)))]
    (is (str/includes? (str (:seon.error/message em)) ":inst") (pr-str em))
    (is (str/includes? (str (:seon.error/fix em)) "inst?") (pr-str em))))

;; Estimate: one measured call of 2 footprint reads, a touched scan of the counted fns = ~200000.
(deftest ^{:seon.test/estimate {:ticks 200000}} memory-is-read-when-asked
  ;; task seon.agent/memory-is-read-when-asked: mem-mb is a reading made at the call, never a
  ;; value kept for a time, so each call reads the process's footprint again (jolt.host/
  ;; process-footprint-bytes, one call). The megabytes it answers move with the machine (a collection
  ;; between two calls lowers them), so the test counts the calls, exactly (seon.profile's counted
  ;; calls)
  (let [{[a b] :value} (profile/measure! #(vector (agent/mem-mb) (agent/mem-mb)))
        reads (into {} (map (juxt :var :calls)) (profile/touched))]
    (is (= 2 (get reads 'seon.lib/real-mb)) "two calls, two readings: none kept between them")
    (is (and (pos? a) (pos? b)) "a reading in MB")))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 7 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~3250k, 4000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 7}} a-parked-agent-draws-its-parts-and-a-gone-one-answers-at-once
  ;; the agent draws its own page (one derivation, slice 2): the render proc's {:op :render} ask is
  ;; answered by an agent parked between requests with its parts' HTML, a key its branch renders
  ;; drawn by that render, recording nothing (the key is named by the agent's pattern, which pulls only what it names); a request to an agent that is gone is answered at
  ;; once, so its page is drawn by root from its branch and never waits
  (let [n "(count (datahike.api/datoms (seon.agent/db) :aevt :seon.eval/id))"
        [_ _ swapped before drawn after gone]
        (in-agent #(vector "(my.schema/register! :lab.ctx/steps '[:vector {:seon.render/hiccup lab.ui/steps} :string])"
                   "(ns lab.ui) (defn steps \"The steps, numbered.\" {:malli/schema [:=> [:cat :map [:vector :string]] :any]} [_ v] [:ol (for [s v] [:li s])])"
                   "(do (swap! seon.ctx/ctx update :seon.ctx/pattern (fnil conj [:seon.db/agent-fact]) :lab.ctx/steps) (swap! seon.ctx/ctx assoc :lab.ctx/steps [\"read the plan\" \"write the test\"]))"
                   n
                   {:op :eval :agent "root"
                    :code (str "(let [id :" (:agent %) "]"
                               " @(seon.system/call! sys {:op :render :id id}))")}
                   n
                   {:op :eval :agent "root" :code "@(seon.system/call! sys {:op :render :id :gone})"}))
        ;; the keys it added, one component (seon.render.ui/ctx-keys) holding each key's own, a unit of its own inside it
        steps (some #(when (= "ctx-key-lab_2Ectx_2Fsteps" (:id %)) %) (:units (:value drawn)))]
    (is (= "ctx-keys" (:parent steps)) "the key's unit, inside the keys' component")
    (is (str/includes? (str (:html steps)) "<ol><li>read the plan</li><li>write the test</li></ol>")
        (str "the swap! answered " (pr-str swapped) "; the units: " (pr-str (map :id (:units (:value drawn))))))
    (is (= (inc (:value before)) (:value after)) "the drawing recorded no eval: only the count before it is new")
    (is (= :seon.system/no-agent (:seon.error/type (:value gone))) (pr-str gone))))

(deftest ^{:seon.test/estimate {:ticks 200000}} a-value-that-does-not-read-ends-its-test-and-the-run-goes-on
  ;; task seon.agent/a-test-childs-line-always-reads (root, 2026-10-27): a report holding a value
  ;; that prints as no EDN (an exception's #error {, a var's #'v) killed its agent with exit 70. Read through the
  ;; link's codec (seon.agent/child-codec) it is that test's :error, once, and the next test's values are taken as before
  (let [{:keys [encode decode]} (#'agent/child-codec :seon.test/c1)
        read #(decode (encode %))
        bad {:seon.test/ended "#'app.t/b" :reports [{:type :fail :actual [(ex-info "boom" {:a 1}) #'clojure.core/inc]}]}
        vs [{:seon.test/started "#'app.t/a"} {:seon.test/ended "#'app.t/a" :reports [{:type :pass :var "#'app.t/a"}]}
            {:seon.test/started "#'app.t/b"} bad bad
            {:seon.test/started "#'app.t/c"} {:seon.test/ended "#'app.t/c" :reports [{:type :pass :var "#'app.t/c"}]}]
        read-vs (mapv read vs)
        acc (reduce agent/take-value {:streamed [] :ended #{} :fns {}} read-vs)
        errors (filter #(= :error (:type %)) (:streamed acc))]
    (is (every? #(= :seon.test/c1 (:clojure.core.async.flow/pid %)) read-vs) "each value read names its child")
    (is (= 1 (count errors)) "the unreadable value is one :error, though it came twice")
    (is (= "#'app.t/b" (:var (first errors))) "on the test that was running")
    (is (= :seon.test/unreadable-line (:seon.error/type (first errors))))
    (is (= #{"#'app.t/a" "#'app.t/c"} (:ended acc)) "the tests around it are taken as before")))

(deftest ^{:seon.test/estimate {:ticks 50000}} a-tests-ended-value-keeps-the-live-heap-it-left
  ;; docs/cost.md#what-a-test-run-measures (root, 2026-10-28): 23 of 191 tests of a forked run ran
  ;; out of memory at the agent's heap ceiling with no stack; the heap each test leaves, after a
  ;; collection, is on its ended value and kept per test, so the one that holds memory is named
  (let [acc (reduce agent/take-value {:streamed [] :ended #{} :fns {} :heap {}}
                    [{:seon.test/started "#'app.t/a"} {:seon.test/ended "#'app.t/a" :reports [] :heap 1200}
                     {:seon.test/started "#'app.t/b"} {:seon.test/ended "#'app.t/b" :reports [] :heap 900000}])]
    (is (= {'app.t/a 1200 'app.t/b 900000} (:heap acc)))
    (is (nil? (:running acc)) "no test runs once the last has ended")))

;; docs/references.md#an-entity-cites: an agent is made citing what every agent cites (its own cite entities, written when
;; root makes it), and changes its own at its REPL; its page draws each as a task's cite is drawn
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} an-agent-cites-what-it-reads-beside-its-task
  (let [[before added gone] (in-agent ["(mapv :cites (my.agent/uncite! \"docs/none.md\"))"
                                       "(mapv (juxt :cites :depth) (my.agent/cite! \"docs/agent.md?summary#the-agent\"))"
                                       "(mapv :cites (my.agent/uncite! \"docs/testing.md#testing-in-the-world\"))"])]
    (is (= ["docs/agent.md#working-a-task" "docs/agent.md#merging" "docs/testing.md#testing-in-the-world" "docs/data-modeling.md#data-modeling"] (:value before))
        (str "made with every agent's, the config's references: " (pr-str before)))
    (is (= [["docs/agent.md#working-a-task" :doc] ["docs/agent.md#merging" :doc] ["docs/testing.md#testing-in-the-world" :doc] ["docs/data-modeling.md#data-modeling" :doc] ["docs/agent.md#the-agent" :summary]] (:value added)) (pr-str added))
    (is (= ["docs/agent.md#working-a-task" "docs/agent.md#merging" "docs/data-modeling.md#data-modeling" "docs/agent.md#the-agent"] (:value gone))
        (str "a default it uncites is gone from its own: " (pr-str gone)))))

;; docs/references.md#an-entity-cites: an agent whose task's config cites a section its store does not hold starts and works, citing the rest;
;; root holds the missing address for it (its status, and a :seon.agent/cite-missing problem naming it), and its own cites leave it out
;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 4 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2500k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 4}} an-agent-whose-store-lacks-a-cited-section-starts-and-says-which
  (let [gone "docs/none.md#not-there" held "docs/agent.md#merging"
        problem-code (str "(->> (get-in (my.root/problems sys) [:classes :agent :rows]) (filter #(= :seon.agent/cite-missing (:type %)))"
                          " (mapv (juxt :last-agent #(get-in % [:example :seon.doc/address]))))")
        [names mine sum status problems]
        (in-agent (fn [names]
                    [(fn [_] names)
                     "(#'my.agent/own-cites)" "(+ 1 2)"
                     {:op :eval :agent "root" :code (str "(:seon.agent/missing-cites (get (:agents (c/state sys)) :" (:agent names) "))")}
                     {:op :eval :agent "root" :code problem-code}])
                  {:config {:seon.config.agent/cites [gone held]}})]
    (is (= 3 (:value sum)) "the agent started and answers an eval")
    (is (= [held] (mapv :cites (:value mine))) (str "its own cites hold the section the store has, and not the one it lacks: " (pr-str (:value mine))))
    (is (= [gone] (:value status)) "root holds the missing address on the agent's status")
    (is (= [[(keyword (:agent (:value names))) gone]] (:value problems)) "and a :seon.agent/cite-missing problem of that agent names it")))

;; See docs/merging.md#main-moved-is-said-at-every-eval
;; Estimate: 4 records of 1 to 4 forms ~3M, main-line over 2 moved mains at ~3M each (main diff and branch diff), base-as-main ~0.1M = ~10000000.
(deftest ^{:seon.test/estimate {:ticks 10000000}} main-moved-is-said-at-every-eval
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
;; Estimate: test-branch! ~1M, one transact ~50k, 3 pure main-moved? comparisons = ~1000000.
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-turn-merges-main-only-when-main-moved
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
;; Estimate: 3 records ~2.5M, first eval ~5M (main diff and branch diff), unchanged ~0.3M, second eval ~1M = ~9000000.
(deftest ^{:seon.test/estimate {:ticks 9000000}} main-line-costs-what-changed
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 3 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~2250k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 3}} a-refused-write-in-a-proof-runs-finish-answers-the-asker-with-the-error
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 6 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~3000k, 3000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 6}} a-commit-the-store-refuses-leaves-the-agent-alive
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
        u (:unrecorded r3)
        ;; the notes this test wrote: main is shared with the shard's other tests, and one that writes main
        ;; (a-wait-on-main-whose-test-throws-answers-the-throw-and-the-write-stands) leaves its note in every
        ;; agent forked after it, so the assertions read only what this test caused
        own (partial filter #{"kept before" "poison" "after"})]
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
    (is (= #{"kept before" "after"} (set (own (:value r5)))) "what was committed before is intact, and a later commit holds")
    (is (nil? (:unrecorded r5)) (pr-str r5))
    (is (= ["after" "kept before"] (own (:notes (:value r6)))) "the refused write is not on the branch")
    (is (= (:t (:value r6)) (:ctx-t (:value r6))) "@ctx agrees with the committed db")))

;; Estimate: 2 resets and one intake read = ~20000.
(deftest ^{:seon.test/estimate {:ticks 20000}} the-reader-is-the-processs-own
  ;; optimization's review: the intake's reader kept in state was lost to an eval replacing it, and the next
  ;; intake started a second reader on the same socket; it is link's, beside the socket
  (let [old @agent/state old-link @agent/link ch (clojure.core.async/chan)]
    (try (reset! agent/link {:fd -1 :conn ::conn :ch ch})
         (reset! agent/state {})
         (is (identical? ch (agent/intake)) "the one reader's channel, whatever state became")
         (finally (reset! agent/state old) (reset! agent/link old-link)))))

;; Estimate: 3 mt/code of 1 to 3 forms at ~0.5M, 4 named-tests queries at ~0.1M = ~2000000.
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-named-namespace-selects-its-tests
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

;; Estimate: 3 evals at ~0.3M = ~1M, 3M with the world
(deftest ^{:seon.test/estimate {:ticks 3000000}} an-agents-namespace-holds-the-docstring-its-form-gives
  ;; docs/source.md: a namespace's docstring, on an agent's own eval, however its ns form gives it (a name's metadata
  ;; reaches the account by seon.repl's own read of it, as a file's does by seon.source's)
  (let [_ '[seon.repl/eval-string seon.program/account] ; run by string below, named so a change to them reruns this test
        [_ _ r] (mt/eval-in (fn [{:keys [ns]}]
                            [(str "(ns " ns ".nd1 \"By a string.\")")
                             (str "(ns ^{:doc \"By its name.\"} " ns ".nd2)")
                             (str "(let [db (seon.agent/db) doc (fn [n] (:ns/doc (datahike.api/entity db [:ns/name n])))]"
                                  " [(doc '" ns ".nd1) (doc '" ns ".nd2)])")]))]
    (is (= ["By a string." "By its name."] (:value r)) (pr-str r))))

;; Estimate: one eval of a defn in this process (seon.repl/eval-string, a scratch session: ~0.5M) and the registry with the one
;; narrowed key, seon.program/schema-registry (contract/registry-with: that key and the forms naming it compiled again, ~1M) and
;; four validations of a small map (~10k) = ~1.5M: 2,000,000
(deftest ^{:seon.test/estimate {:ticks 2000000}} an-eval-that-registers-a-schema-seons-answer-names-answers-what-it-did
  ;; the lab, 2026-11-04: an eval registered :seon.eval/response narrower, for its own use, and its later eval's answer failed
  ;; seon.repl/response's own contract: told an error, though its forms took effect. The live answer (its value may be a var) is
  ;; :seon.repl/answer and what stores it :seon.eval/response, so the answer's contract holds under any registration of the stored
  ;; shape: read off the contracts of response and eval-string, under the registry such a registration makes
  (let [reg (program/schema-registry {:seon.eval/response '[:and :map :seon.db/plain]})
        outs (fn [v] (let [c (contract/contract v)] (if (= :function (first c)) (mapv last (rest c)) [(last c)])))
        made (mt/eval-in "(defn zz-made {:malli/schema [:=> [:cat] :int]} [] 41)" 'seon.agent-test.reg)
        valid? (fn [s] (binding [malli.registry/*registry* reg] (contract/valid? s made)))]
    (is (nil? (:ex made)) (pr-str made))
    (is (var? (:value made)) "its value is the var it defined, no plain value")
    (is (not (valid? :seon.eval/response)) "which the narrowed stored shape refuses")
    (is (every? valid? (concat (outs #'repl/response) (outs #'repl/eval-string)))
        (str "and Seon's answer is checked by its own shape, which the registration leaves alone: " (pr-str (outs #'repl/response))))))

;; Estimate: evals on a world (built once a process, ~3.2M) at ~0.3M each; measured a few M
(deftest ^{:seon.test/estimate {:ticks 10000000}} an-eval-makes-data-and-a-reader-makes-text
  ;; the owner, 2026-11-04: "why are we generating text for each eval? If we can store the value store the value and text is a
  ;; last resort". A model's eval prints nothing; a value that throws as it is realized is still its recorded error, since
  ;; making it plain realizes it; a value that is no data is stored as what it was
  (let [_ '[seon.repl/eval-string seon.db/response-of] ; run by string below, named so a change to them reruns this test
        [model thrown atm rec]
        (mt/eval-in (fn [_] ["(dissoc (seon.repl/eval-string \"(+ 1 2)\" {:origin :model}) :ms :ticks :bytes)"
                           "(let [r (seon.repl/eval-string \"(map (fn [_] (throw (ex-info \\\"late\\\" {}))) [1])\" {:origin :model})] (select-keys r [:ex :record]))"
                           "(let [r (seon.repl/eval-string \"(atom 1)\" {:origin :model})] (:record r))"
                           "(let [id (:record (seon.repl/eval-string \"(atom 1)\" {:origin :model}))] (:value (seon.db/response-of (seon.agent/db) (datahike.api/pull (seon.agent/db) '[:seon.eval/response :seon.eval/result] [:seon.eval/id id]))))"])
                  )]
    (is (not (contains? (:value model) :text)) (pr-str model))
    (is (some? (get-in thrown [:value :ex])) (str "a value throwing as it is realized is the eval's error: " (pr-str thrown)))
    (is (some? (get-in thrown [:value :record])) "and recorded")
    (is (some? (:value atm)) "an atom's eval is recorded")
    (is (= "clojure.lang.Atom" (:seon.value/type (:value rec))) (pr-str rec))))


;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 1 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~1750k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 1}} evals-given-at-spawn-run-first-as-ordinary-evals
  ;; docs/turn.md#an-eval-can-be-inserted: an agent spawned with :evals (pairs of text and code) runs them on its one REPL
  ;; queue before anything else, each recorded as any eval is, by whoever asked, at turn 0 and with no call id. One that
  ;; throws is recorded with its error and the rest run, as a reply's calls do (owner, 2026-11-08). A reader of the
  ;; conversation then sees them in order and, in the request, as calls of their own.
  (let [read (str "(let [c @seon.ctx/ctx db (seon.agent/db) es (map #(get-in c [:seon.eval/_agent (first %)]) (seon.ctx/turn-evals c (second (first (seon.ctx/turns c)))))] "
                  "{:turn (seon.agent/turn-now) "
                  " :records (mapv (fn [v] [(seon.task/sender-name (:seon.eval/by v)) (:seon.eval/intent v) (:seon.eval/call-id v) (some? (:ex (seon.db/response-of db v))) (:value (seon.db/response-of db v))]) es) "
                  " :roles (mapv :role (drop 2 (:messages (seon.ctx.wire/render db {:agent (:key @seon.agent/state) :commit (datahike.api/commit-id db) :task \"x\" :turn 1 :max-turns 1 :origin :outside} {:walk (seon.repl/page-walk)}))))})")
        code (str "(let [id (c/spawn! sys {:evals [[\"Add.\" \"(+ 1 2)\"] [\"Throw.\" \"(throw (ex-info \\\"boom\\\" {}))\"] [\"Never.\" \"(+ 3 4)\"]]}) "
                  "r @(c/submit! sys id " (pr-str read) " {:origin :outside})] "
                  "(c/kill! sys id) (:value r))")
        [r] (in-agent [{:op :eval :agent "root" :code code}] {})
        v (:value r)]
    (is (= 0 (:turn v)) (pr-str r))
    (is (= [["seon" "Add." nil false 3]
            ["seon" "Throw." nil true nil]
            ["seon" "Never." nil false 7]]
           (:records v))
        "three ordinary evals at turn 0 in order, by whoever asked, none a model's call; the throw recorded, and the next still runs")
    (is (= ["assistant" "tool" "assistant" "tool" "assistant" "tool"] (take 6 (:roles v))) "each reaches the request as a call and its result")))

;; Estimate: one entity index build over the test store ~100 schema entries (~100k), 4 schema-moved calls = ~200000.
(deftest ^{:seon.test/estimate {:ticks 200000}} one-schema-write-builds-the-branch-index-once
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

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 1 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~1750k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
;; docs/config.md#a-process-holds-its-branchs-config, plan Stage 0.5 (task seon.agent/a-test-child-starts-on-its-branchs-environment, slice A):
;; an agent's start holds the config its branch states (load-diff!'s seon.program/hold-config!), so what forks from it is on its
;; branch's environment by construction, and a config a branch cannot hold is refused at its write (seon.program/config-refused),
;; so no agent can start on one: checked once, where it is written, never again downstream. Green when written (tests, 16:45):
;; it guards a guarantee the code already gives, which the plan had wrongly said was missing.
;; Estimate: in-agent's setup ~1.5M + one root eval of two spawns at a commit (each ~0.25M asked) and four submits = ~2.5M
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2500000 :asks 1}} an-agent-starts-on-the-config-its-branch-states
  (let [[r] (in-agent (fn [{:keys [agent ns]}]
                        (let [b (fn [s] (str ":task-" ns "-" s))
                              entry (fn [form] (pr-str (str "(do (seon.agent/transact! [{:config/key :seon.config.root/view-window :config/form " (pr-str form)
                                                            " :config/file \"resources/seon/config/default.edn\"}]) (seon.agent/commit!) (datahike.api/commit-id (seon.agent/db)))")))]
                          [{:op :eval :agent "root"
                            :code (str "(let [sub (fn [a code] (:value @(c/submit! sys a code {:origin :outside})))"
                                       "      a :" agent
                                       "      good (sub a " (entry "37") ")"
                                       "      r @(seon.system/call! sys {:op :spawn :opts {:branch " (b "env-good") " :commit good}})"
                                       "      held (when (:id r) (sub (:id r) \"(seon.config/value :seon.config.root/view-window)\"))"
                                       "      _ (when (:id r) (c/kill! sys (:id r)))"
                                       "      bad @(c/submit! sys a " (entry ":not-a-count") " {:origin :outside})]"
                                       "  {:held held :root (seon.config/value :seon.config.root/view-window) :bad (select-keys bad [:value :ex]) :answer (when-not (:id r) r)})")}])))
        v (:value r)]
    (is (= 37 (:held v)) (str "the agent holds its branch's value from its start, not root's (" (:root v) "): " (pr-str v)))
    (is (nil? (:value (:bad v))) (str "a config the branch cannot hold is refused at its write, never reaching a branch an agent starts on: " (pr-str v)))
    (is (some? (:ex (:bad v))) (pr-str v))))

;; docs/source.md#the-binary-records-its-program: the tree an agent's binary is checked against is the one its program comes from. A
;; resumed branch whose merge base was swept runs main's program as forked (seon.agent/init!), so it is checked against main's tree;
;; red before: init! read the branch's own old source fact and refused every such resume :seon.process/env-mismatch (:main/tests,
;; :main/tests-meas1 at root, 2026-11-15, result/b8473e2f), though it runs main's program
;; Estimate: two test dbs and two pulls ~ 300,000 ticks
(deftest ^{:seon.test/estimate {:ticks 300000}} a-resume-whose-base-was-swept-is-checked-against-mains-tree
  (let [at (fn [tree] (d/db-with (ts/test-db) [{:seon.system/id :root :seon.system/source {:tree tree :commit "c" :dirs ["src"]}}]))
        main (at "main-tree") branch (at "old-tree")
        lost {:seon.error/type :seon.db/no-merge-base :seon.error/operation 'seon.db/branch-base :seon.error/message "swept" :branch :b :commit nil}]
    (is (= "main-tree" (agent/source-tree main branch lost)) "a swept base: the tree of main's program, which it runs")
    (is (= "old-tree" (agent/source-tree main branch nil)) "a branch with its base: its own tree, as before")))

(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 1}} a-session-restored-at-an-older-commit-runs-that-commits-definitions
  ;; docs/storage.md#compiled-code: a spawn with {:branch b :commit C}
  ;; makes b from the session's commit C and forks it from main's image with the program at C loaded over it, the way a resumed
  ;; agent loads its tip. Red before: a spawn took no commit, so the new agent held main's program and f was unbound. The answer says what
  ;; is not restored.
  (let [[r] (in-agent (fn [{:keys [agent ns]}]
                        (let [b (str ":task-" ns "-restored") n (str "demo." ns ".restored")]
                          [{:op :eval :agent "root"
                            :code (str "(let [sub (fn [a code] (:value @(c/submit! sys a code {:origin :outside})))"
                                       "      a :" agent
                                       "      _ (sub a \"(ns " n ") (defn f [] :first)\")"
                                       "      cid (sub a \"(datahike.api/commit-id (seon.agent/db))\")"
                                       "      _ (sub a \"(ns " n ") (defn f [] :second)\")"
                                       "      now (sub a \"(" n "/f)\")"
                                       "      r @(seon.system/call! sys {:op :spawn :opts {:branch " b " :commit cid}})"
                                       "      then (when (:id r) (sub (:id r) \"(" n "/f)\"))]"
                                       "  (when (:id r) (c/kill! sys (:id r)))"
                                       "  {:now now :then then :not-restored (:not-restored r) :answer (when-not (:id r) r)})")}])))
        v (:value r)]
    (is (= :second (:now v)) (pr-str v))
    (is (= :first (:then v)) (str "the restored agent runs the first commit's body: " (pr-str v)))
    (is (= 3 (count (:not-restored v))) (pr-str v))))

;; Estimate: in-agent's own setup and teardown (1 x ~1.5M: the task file, attach, kill and its branches' deletes, ~6 asks of the harness and their replies) + 1 asks of the test's own at ~0.25M (an eval's recorded answer decoded) = ~1750k, 2000000 (was 200k a setup and 30k an ask, measured low at the land of 2026-11-13 04:04)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000 :asks 1}} a-restore-at-a-commit-the-store-no-longer-holds-is-refused
  ;; docs/failure-points.md (the :spawn edge in seon.writer), docs/storage.md#compiled-code: a commit
  ;; the store holds no more (collected) is answered as a typed error naming it, no branch made and nothing crashed. Red before: the
  ;; commit was ignored and an agent on a branch from main's head answered a success.
  (let [[r] (in-agent (fn [{:keys [ns]}]
                        (let [b (str ":task-" ns "-gone")]
                          [{:op :eval :agent "root"
                            :code (str "(let [cid (random-uuid)"
                                       "      r @(seon.system/call! sys {:op :spawn :opts {:branch " b " :commit cid}})]"
                                       "  (when (:id r) (c/kill! sys (:id r)))"
                                       "  {:type (:seon.error/type r) :message (:seon.error/message r) :cause (:seon.error/type (:cause r))"
                                       "   :cid (str cid) :made (contains? (set (datahike.api/branches (:conn sys))) (seon.db/on-main (seon.db/conn-main (:conn sys)) " b "))"
                                       "   })")}])))
        v (:value r)]
    (is (= :seon.agent/failed (:type v)) (pr-str v))
    (is (= :seon.writer/refused (:cause v)) (pr-str v))
    (is (str/includes? (str (:message v)) "gone") (pr-str v))
    (is (false? (:made v)) "no branch made")))

;; Estimate: 2 pure proof-as-read calls = ~30000.
(deftest ^{:seon.test/estimate {:ticks 30000}} my-task-status-shows-a-failing-proofs-source-as-the-page-does
  ;; the physics audit (2026-11-11, high 3): docs/agent.md says (my.task/status) answers what the page's status shows, and the page draws a
  ;; red proof's :source and :calls; my.task/status stripped both, so an agent had to (source the-proof) to read the test it failed
  (let [read #'my.task/proof-as-read
        red {:test 'app.x-test/t :status :fail :source "(deftest t (is false))" :calls ['app.x/f] :shape {:n 1}}
        green {:test 'app.x-test/u :status :pass :source "(deftest u (is true))" :calls ['app.x/g] :shape {:n 1}}]
    (is (= (dissoc red :shape) (read red)) "a failing proof keeps its source and what it calls")
    (is (= {:test 'app.x-test/u :status :pass} (read green)) "a passing one is its name and status")))

;; docs/source.md#tests-run-by-reach: a branch changes only what its reach changed. A test whose newest green recorded its reads
;; sits at that green's digest on the branch (seon.proofs/read-digests); base's side takes the same where the reach did not move,
;; so the plan's selection (seon.proofs/plan, base against branch) leaves it out. Red before: two judges selected main's four
;; newest tests as the branch's, once a shard's root moved its main past the tree their greens recorded reads at (2026-11-12).
;; T1, in process: three maps of three tests, one plan; a few thousand ticks.
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-test-whose-reach-did-not-move-is-not-the-branchs-change
  (let [static {'a/unchanged "d-now" 'a/moved "d-branch" 'a/plain "p"}
        reads {'a/unchanged "d-green" 'a/moved "d-branch" 'a/plain "p"}
        base-raw {'a/unchanged "d-now" 'a/moved "d-base" 'a/plain "p"}
        base (agent/base-at-reads base-raw static reads)
        sel (:selected (proofs/plan base reads {} #{}))]
    (is (= "d-green" (base 'a/unchanged)) "a test whose reach is base's takes its recorded-reads digest on base's side too")
    (is (= "d-base" (base 'a/moved)) "a test whose reach moved keeps base's own digest")
    (is (= '[a/moved] sel) "so the branch's change is the test its reach moved, never one some run recorded reads for")))

;; docs/merging.md#the-test-service: runs of one branch overlap (a turn's prove!, root's :judge, a session's run; tests-4 read it
;; 2026-11-14: root forwards each :tests with no per-agent exclusion, system.clj's pend), at two digests or at one (a turn and a
;; :judge of an unchanged branch, whose plans differ by mode), so a plan is held under its run's key.
;; Red before: one :held-plan slot, so the second run's plan replaced the first's while its prove! awaited, and the first recorded its
;; answer with the second's selection. Estimate: a few assocs and two item checks over three plans, ~15k ticks.
(deftest ^{:seon.test/estimate {:ticks 100000}} an-overlapping-runs-plan-is-its-own
  (let [plan (fn [t d sel] {:digests {t d} :plan {} :selected sel})
        st (-> {:proving {1 "dA" 2 "dA"}}
               (#'agent/hold-plan "b@dA!turn" "dA" (plan 'a.x-test/one "1a" [:turn]))
               (#'agent/hold-plan "b@dA" "dA" (plan 'a.x-test/one "1a" [:judge]))
               (#'agent/hold-plan "b@dB" "dB" (plan 'a.x-test/one "1b" [:b])))
        sel #(#'agent/grant-selection (#'agent/held-plans %1) %2 (fn [] :planned-again))]
    (is (= [:turn] (get-in st [:held-plans "b@dA!turn" :selected])) "a turn's plan stays beside a :judge's at the same digest")
    (is (= [:judge] (get-in st [:held-plans "b@dA" :selected])) "and the :judge's beside it")
    (is (= "1b" (get-in st [:held-plans "b@dB" :digests 'a.x-test/one])) "beside the newest, at another digest")
    (is (= ['a.x-test/one] (:run (sel st {'a.x-test/one "1a"}))) "an item of the first digest runs as its plan planned it")
    (is (= ['a.x-test/one] (:run (sel st {'a.x-test/one "1b"}))) "and one of the second as its")
    (is (= [:b] (:selected (first (#'agent/held-plans st)))) "the newest plan is first")
    (let [one (#'agent/release-plan st 1)]
      (is (= 3 (count (:held-plans one))) "an answered ask's digest's plans stay while another ask awaits that digest")
      (let [after (#'agent/release-plan one 2)]
        (is (= #{"b@dB"} (set (keys (:held-plans after)))) "and go once none does")
        (is (= #{"b@dB"} (set (keys (:held-plans (#'agent/release-plan (assoc-in after [:proving 3] "dB") 3)))))
            "but the newest stays past its ask's answer: a second ask at its digest the service answers from its done run records with it")
        (is (= #{"b@dC"} (set (keys (:held-plans (#'agent/hold-plan after "b@dC" "dC" (plan 'a.x-test/one "1c" []))))))
            "a plan no ask awaits is dropped by the next: what is held is the runs in flight plus the newest")))))

;; docs/merging.md#the-test-service: a grant the agent planned again (its held plan gave an item another digest) answers exactly
;; the granted items, never what the run's own selection filter keeps. Found 2026-11-13: the loop task held by
;; :seon.prove/no-verdict for my.test-test/the-world-is-built-once-per-process, and before it a datahike test on a refine: the
;; re-plan's filter dropped a granted test, it was never run nor named, and the service could only answer "no verdict".
;; Red before: grant-selection answered the fresh plan's own :selected. Estimate: two maps built, ~20k ticks.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-granted-item-is-run-or-answered-with-its-reason
  (let [sel {:plan {:process #{'a.p-test/boots} :reused {'a.x-test/kept {:pass 1 :fail 0 :error 0}}}
             :judge [] :selected [] :run [] :deferred [] :reused {} :reused-reports [] :digests {'a.x-test/dropped "d2"}}
        items {'a.x-test/dropped "d1" 'a.x-test/kept "d3" 'a.p-test/boots "d4"}
        s (#'agent/granted-selection sel items)
        named (set (map :var (:reused-reports s)))]
    (is (= ['a.x-test/dropped] (:run s)) "a granted test the fresh plan's filter left out runs")
    (is (= #{'a.x-test/dropped 'a.x-test/kept} (set (:selected s))) "every granted test this child can run is selected")
    (is (contains? named "#'a.x-test/kept") "one green at its digest is answered as reused")
    (is (= {:type :error :var "#'a.p-test/boots" :not-run true} (select-keys (first (filter :not-run (:reused-reports s))) [:type :var :not-run]))
        "a process test is answered as an error naming why, never left out")
    (is (= (set (keys items)) (set (concat (:run s) (map #(symbol (subs (:var %) 2)) (:reused-reports s)))))
        "every granted item is run or reported")))

(defn- throws-here {:malli/schema [:=> [:cat] :any]} [] (throw (ex-info "the place is the finding" {:k 1})))

;; docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map: a turn's error names the fn the throw happened in
;; (seon.error/thrown-in), so it says which code threw; a compile error's macro (an unqualified symbol) names none, and is no
;; contract failure of thrown-in. Red before: the turn loop's error kept no place (context's loop task, 2026-11-13), and thrown-in
;; failed its own contract on `cond`. Estimate: two throws and two triages (Jolt keeps 25 frames): ~0.3M ticks
(deftest ^{:seon.test/estimate {:ticks 400000}} an-error-map-names-where-it-was-thrown
  (is (= 'seon.agent-test/throws-here (seon.error/thrown-in (try (throws-here) (catch Throwable e e)))) "a run's throw names its fn")
  (is (nil? (seon.error/thrown-in (ex-info "Syntax error macroexpanding cond" {:clojure.error/symbol 'cond}))) "a macroexpansion's names none, and thrown-in answers"))

(deftest ^{:seon.test/estimate {:ticks 100000}} an-effects-wait-ends-at-its-kinds-declared-bound
  ;; docs/flow.md#effects-one-owner-and-its-workers-per-kind: fx! waits as long as its kind declares (:seon.config.fx/bounds),
  ;; never for good; a root that never answers is the effect's own typed :seon.bound/reached, the outcome the turn reads as an
  ;; error (seon.turn: contract/error? of fx!'s answer), never a completion with no value
  (let [value cfg/value held @agent/state]
    ;; an agent's own key counter, as a forked agent holds it
    (swap! agent/state update :tx-seq #(or % 0))
    (try
      (with-redefs [cfg/value (fn [k] (if (= k :seon.config.fx/bounds) {:model {:seon.bound/ms 50}} (value k)))
                    agent/send! (fn [_] nil)]
        (let [r (agent/fx! :model {:messages []} {})]
          (is (contract/error? r) (str "an error, as the turn reads it: " (pr-str r)))
          (is (= :seon.bound/reached (:seon.error/type r)))
          (is (= {:seon.bound/ms 50} (:seon.bound/bound r)) "its kind's declared bound")
          (is (empty? (:fx-pending @agent/state)) "the wait let its key go"))
        ;; a request that streams (:call) takes no total bound here: its owner's quiet bound answers it (seon.fx/stream-due)
        (let [asked (atom ::none)]
          (with-redefs [agent/await-reply (fn [_ _ ms] (reset! asked ms) {:op :fx-reply :value 1})]
            (agent/fx! :model {:messages [] :call "c1"} {}))
          (is (nil? @asked) "a streamed call's wait is the owner's to end")))
      (finally (reset! agent/state held)))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} an-agent-names-the-program-it-holds-as-a-reference
  ;; docs/processes.md#the-agent-process: the program a process holds is a reference (AGENTS.md §4), the branch as it loaded it
  ;; at its start; program-db reads that commit, whatever the branch's head has moved to since
  (ts/test-branch!
   (fn [cfg conn]
     (let [old @agent/state
           start @conn]
       (try
         (reset! agent/state {:store-conn conn :program {:seon.db/branch (:branch cfg) :seon.db/commit (d/commit-id start) :seon.db/t (:max-tx start)}})
         (d/transact conn [{:seon.system/id :root :seon.system/content-t (inc (:max-tx @conn))}])
         (is (not= (d/commit-id start) (d/commit-id @conn)) "the branch moved on")
         (is (= (d/commit-id start) (d/commit-id (agent/program-db))) "the program it holds is still the one it started on")
         (finally (reset! agent/state old)))))))
;; Estimate: one binding, two atom resets and one whoami (a map of two keyword reads and a getpid): ~20k ticks; 100,000
(deftest ^{:seon.test/estimate {:ticks 100000}} a-process-reads-its-start-from-its-start-value
  ;; one agent kind, G2 (task seon.step/roots-procs-are-the-first-forks-graph; docs/processes.md#the-agent-process): what is fixed for a
  ;; process's life (its id, key, task, branch, config, mode, store connection) is its start value, seon.agent/*start*, set once at its
  ;; start and bound by a test's world, never a field of seon.agent/state, which holds what changes. Red before: whoami read the atom
  (let [old @agent/state]
    (try (reset! agent/state {:n 0})
         (binding [agent/*start* {:id :a9 :key "a9" :branch :task-b9}]
           (is (= [:a9 :task-b9] ((juxt :id :branch) (my.agent/whoami))) "read from the start value"))
         (is (not-any? #(contains? @agent/state %) [:id :key :branch :task :cfg :mode :store-conn]) "the state holds none of them")
         (finally (reset! agent/state old)))))

;; docs/source.md#tests-run-by-reach: a test judges by the program's facts, never by what the asking process loaded
;; Estimate: 2 judges? calls, each a set lookup under its contract, well under 100k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} a-test-judges-by-the-programs-facts-not-what-the-process-loaded
  (let [t 'seon.never-loaded-test/a-test
        g {:tests #{t}}]
    (is (nil? (find-ns 'seon.never-loaded-test)) "the test's namespace is not loaded here, as at root")
    (is (true? (agent/judges? g #{} t)) "a deftest the program holds judges, though this process never loaded it")
    (is (false? (agent/judges? g #{} 'seon.never-loaded-test/not-a-test)) "a name the program holds no deftest of does not")))


;; docs/merging.md#a-branch-merges-main-in, FOCUS (2): a merge of main that moved main's program takes the branch's program in place at
;; once (seon.agent/merge-main!, take-branch-program!, load-diff!), so the process runs its branch's program whenever its branch moves:
;; root's seon_done pilot was refused 'this agent runs another program than its branch' when the merge only marked it :behind and its
;; judge came before any turn. Estimate: test-branch! ~1M, two transacts and a connect ~0.5M, one merge-main! through the writer's
;; merge-main-into ~12M (measured 14.5M in all: the first estimate, 6M, took the merge at ~3M as the older tests do), one load of one
;; form ~0.5M = ~16000000
(deftest ^{:seon.test/estimate {:ticks 16000000}} a-merge-of-main-runs-mains-program-in-this-process-at-once
  (ts/test-branch!
   (fn [cfg conn]
     (let [ab (pdb/on-main (:branch cfg) :agent-base-load)
           _ (d/branch! conn (:branch cfg) ab)
           forked @conn
           nsym (symbol (str "app.mload" (Math/abs (hash (random-uuid)))))
           _ (d/transact conn [{:db/id "n" :ns/name nsym :ns/form "f0"} {:db/id "f0" :form/id (str nsym "/ns") :form/ns "n" :form/order 0 :form/src (str "(ns " nsym ")")}
                               {:db/id "v" :var/name (symbol (str nsym) "g") :var/form "f1"}
                               {:db/id "f1" :form/id (str nsym "/g") :form/ns "n" :form/order 1 :form/defines ["v"] :form/src "(defn g [x] (+ x 100))"}])
           store-conn (pdb/connect (assoc cfg :branch ab))
           [a b] (sock/socketpair)
           old @agent/state old-link @agent/link]
       (try
         (reset! agent/link {:fd a})
         (reset! agent/state {:store-conn store-conn :cfg cfg :branch ab :key "test/a2" :db @store-conn :base forked
                              :program {:seon.db/branch ab :seon.db/commit (d/commit-id @store-conn) :seon.db/t (:max-tx @store-conn)}})
         (is (nil? (find-ns nsym)) "before the merge this process holds none of main's new namespace")
         (let [r (merge-in-writer conn cfg)]
           (is (seq (:applied r)) "main's program change merges in")
           (is (= [] (:load-errors r)) (pr-str r))
           (is (= 105 ((resolve (symbol (str nsym) "g")) 5)) "and this process runs it at once: no turn, judge or re-fork between")
           (is (empty? (program/program-differs (:base @agent/state) (agent/db))) "so it runs its branch's program, as a judge asks"))
         (finally (reset! agent/state old) (reset! agent/link old-link) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))

;; docs/users.md#who-writes-what-and-where: an agent never writes an attribute only root writes ({:seon.db/root-only true} on its schema or
;; its entity schema's), read from the schema forms where the write asks, so an entity index made before the property existed (a process
;; that loaded older code, main 2026-11-16 after ea1a505904: every fresh agent's write threw, result/d70d2d4e) refuses nothing and never
;; fails. Red before: the rail read a set the index froze at load, and (filter nil ...) threw on an older index.
;; Estimate: pure, a few map lookups an attribute ~1k ticks; 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-root-only-attribute-is-refused-and-an-older-index-refuses-nothing
  (let [ix (seon.db/index)
        tx [[:db/add [:seon.agent/key "a"] :seon.agent/status :busy] [:db/add [:seon.agent/key "a"] :seon.agent/turn 3]
            {:seon.system/id :root :seon.system/boot "b"}]
        older (-> ix (dissoc :root-only-attrs)
                  (update :forms update-vals (fn [f] (if (and (vector? f) (map? (second f))) (update f 1 dissoc :seon.db/root-only) f))))]
    (is (= [:seon.agent/status] (agent/root-only-written ix (subvec tx 0 2))) "root's attribute is refused, the agent's own is not")
    (is (= [] (agent/root-only-written older tx)) "an index made before the property refuses nothing, and does not throw")))

;; docs/merging.md#a-test-child-is-a-fork-of-a-live-agent, FOCUS (2): a test run's ping names where each child stands (tests' 18 batches
;; answered nothing for 33 s and their state said only how many children there were, result 9094bb6a). Driven through the run's own
;; step with a child's started value; the rule read on the fold's state. Estimate: three step calls and four reads, pure ~200000
(deftest ^{:seon.test/estimate {:ticks 200000}} a-test-runs-ping-names-each-childs-stage
  (let [child (fn [i] {:shard i :os (+ 100 i) :acc {:streamed [] :ended #{} :fns {} :heap {} :measured {}}})
        st {:children {:c1 (child 0) :c2 (child 1)} :say (fn [_] nil)}
        [st _] (agent/test-run-step st :seon.link/up {::flow/pid :c1 :seon.test/started 'app.x-test/a})
        stages (agent/child-stages st)]
    (is (= {:stage :running :test 'app.x-test/a} (select-keys (:c1 stages) [:stage :test])) (pr-str stages))
    (is (= :forked (:stage (:c2 stages))) "a child no test has started in is :forked")
    (is (= {:stage :closed} (select-keys (:c2 (agent/child-stages (assoc-in st [:children :c2 :closed] true))) [:stage])))
    (is (= {:stage :ended :exit 1} (select-keys (:c2 (agent/child-stages (update-in st [:children :c2] assoc :closed true :status 1))) [:stage :exit]))
        "and one reaped says its exit")
    (is (= (agent/child-stages st) (:children ((:ping-map-fn (agent/test-run-step)) st))) "the run's ping is these stages")))

;; docs/config.md#a-process-holds-its-branchs-config: every test child parsed and validated the same config, the agent's under the test
;; profile, 8.1M ticks / 10.3 MB / 23 ms each (runtime, result/d10c5747). The agent derives it once for its db (cfg/test-held!) and the
;; child takes it whole (hold-env!'s :held). Red before: hold-env! read the entries and called hold-args! whatever it was handed.
;; Estimate: one held-for of the binary's text (~8M) and two map lookups: ~10M ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-test-child-takes-the-config-its-agent-derived
  (let [held @#'seon.config/held h0 @held
        args ["--profile" "scripted"]
        derived (atom 0)
        h (cfg/test-held! [:point 1] args #(do (swap! derived inc) nil) (constantly {}))
        h2 (cfg/test-held! [:point 1] args #(do (swap! derived inc) nil) (constantly {}))
        asked (atom 0) installed (atom nil)]
    (try
      (is (= 1 @derived) "derived once for one point")
      (is (identical? h h2) "and kept: the second child takes the same value")
      (is (= :scripted (get-in h [:value :config :seon.config.root/profile])) "under the test profile")
      (with-redefs [cfg/hold-args! (fn [& _] (swap! asked inc) nil)
                    cfg/install! (fn [x] (reset! installed x) nil)
                    seon.lib/c-unsetenv (fn [_] nil)]
        (agent/hold-env! (mt/world) {:args args :held h}))
      (is (zero? @asked) "the child parses and validates nothing")
      (is (identical? h @installed) "it holds what its agent derived")
      (finally (reset! held h0)))))
