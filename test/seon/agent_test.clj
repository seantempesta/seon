(ns seon.agent-test
  "An agent's process, run as the built binary (target/seon, bin/build seon): the root forks
  it, so the test boots a root of its own, as seon.root-test does, and asks the agent over
  the server what its REPL answers.

  An agent's test run, read from its child's pipe (task seon.agent/one-test-bound-derived-and-named):
  no clock ends it; the child's exit does, and a child that died names the test it was running.
  Run: bin/test seon.agent-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.agent :as agent] [seon.proofs :as proofs]
            [datahike.api :as d] [seon.db :as pdb] [seon.test-store :as ts] [seon.lib :as lib] [seon.program :as program] [seon.sock :as sock]
            [seon.fx :as fx] [test-runner :as runner] [seon.context-test] [my.task] [my.agent]))

(def ^:private await-file
  "seon.await, which a root (importing src only) loads by this path."
  (.getCanonicalPath (java.io.File. "test/seon/await.clj")))

(def ^:private before-done
  "Main's t just before a :done, kept in root's REPL as t-before-done: :done answers once the judge
  has, and the reconciler may be quiet by then, so a test waiting for quiescence after :done waits
  for a report written after this t, never after :done's answer (that wait could never end)."
  {:op :eval :agent "root" :code "(def t-before-done (:max-tx @(:conn sys)))"})

(def value-of "seon.test-store/value-of, as this namespace's callers name it." ts/value-of)

(defn in-agent
  "A root on the checkout's src (its source root), one agent on a task of its own, each of
  forms evaluated in that agent (a string) or sent to the server as it is (a map): every
  reply's response with its value read back (:value, value-of), or the answer that holds no
  response, in order. The root works on a
  main of its own, a branch of the one test store (target/runs/test-store), deleted with
  every branch under it when the root has stopped (seon.check/drop-mains).
  opts: :dirs, the source dirs root imports (default [\"src\"]); :proofs, the task's proofs;
  :sets, more --set config overrides for the root (e.g. its scripted model); :decls, more
  task declarations in demo's file beside demo/t."
  ([forms] (in-agent forms {}))
  ([forms {:keys [dirs proofs sets decls] :or {dirs ["src"] proofs '#{demo-test/t}}}]
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        main (str "in-agent-" (random-uuid))
        store (ts/process-store checkout)
        path (str checkout "/target/runs/" main)
        _ (.mkdirs (java.io.File. (str path "-tasks")))
        _ (spit (str path "-tasks/demo.edn") (pr-str (into [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs proofs}] decls)))
        ;; its store and main named (seon.test-store/start-root!), never inherited
        root (ts/start-root! {:store store :main main :runs (str path "-runs")
                              :args (into ["--set" (str ":seon.config.source/root=" (pr-str checkout)) "--set" (str ":seon.config.source/dirs=" (pr-str dirs))]
                                          (mapcat (fn [x] ["--set" x])) sets)})
        ask (fn [m] (ts/ask root m))
        eval-root (fn [code] (ask {:op :eval :agent "root" :code code}))
        out (if (:port root)
              (do
                (eval-root (str "(my.root/load-tasks! sys " (pr-str (str path "-tasks")) ")"))
                ;; the agent's :ready event, tapped before the attach that spawns it and awaited
                ;; after: its hello (a ping of :agents until idle was a poll)
                (eval-root "(def ready-ev (c/events sys))")
                (ask {:op :attach :task "demo/t"})
                (eval-root "(loop [] (let [e (clojure.core.async/<!! ready-ev)] (if (and (= :ready (:type e)) (= :a1 (:agent e))) :ready (recur))))")
                (mapv #(ask (if (map? %) % {:op :eval :agent "a1" :code %})) forms))
              [{:error :root-did-not-listen :log (let [f (java.io.File. (str path "-runs/root.log"))] (if (.exists f) (slurp f) ""))}])]
    (ts/stop-root! root)
    (ts/drop-main! store main)
    (sh/sh "rm" "-rf" (str path "-runs") (str path "-tasks"))
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
  (let [checkout (.getCanonicalPath (java.io.File. "."))
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
        [_ r] (str/split (str/trim (slurp (str runs "/root.pids"))) #" ")
        children (fn [pid] (->> (str/split-lines (:out (sh/sh "pgrep" "-P" (str pid)))) (remove str/blank?) (map parse-long)))
        fs (first (children r))]
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
  (let [checkout (.getCanonicalPath (java.io.File. "."))
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

(deftest a-blocked-agent-ends-with-its-root
  ;; task seon.agent/an-agent-ends-with-its-root, an agent blocked in a wait: its thread in
  ;; Chez's condition-wait reaches no safe point, so no signal handler runs (SIGIO, root's
  ;; SIGUSR1) until the wait returns. The fork server's group kill on root's EOF ends it
  ;; (task seon.agent/a-signal-wakes-a-blocked-wait makes the agent's own EOF end it too)
  (let [{:keys [agents left out]} (root-ended "(clojure.core.async/<!! (clojure.core.async/chan))" false)]
    (is (= #{:a1 :a2} (set (keys agents))) out)
    (is (empty? left) (str "agents still alive: " left "\n" out))))

(deftest a-blocked-agent-ends-on-its-socket-eof
  ;; task seon.intr/a-signal-wakes-a-blocked-wait: an agent blocked in a channel take, its fork
  ;; server gone first, ends on its own socket's EOF (SIGIO wakes its condition-wait)
  (let [{:keys [agents left out]} (root-ended "(clojure.core.async/<!! (clojure.core.async/chan))" true)]
    (is (= #{:a1 :a2} (set (keys agents))) out)
    (is (empty? left) (str "agents still alive: " left "\n" out))))

(deftest root-interrupts-a-blocked-take
  ;; task seon.intr/a-signal-wakes-a-blocked-wait: root's interrupt! stops an eval blocked in a
  ;; channel take, and the agent answers its next request
  (let [checkout (.getCanonicalPath (java.io.File. "."))
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
        [held _ run] (in-agent ["(boolean (find-ns 'seon.reader-test))"
                                "(in-ns 'seon.reader-test) (clojure.test/deftest an-agent-made-this-red (clojure.test/is (= 1 2))) (in-ns 'agent.a1)"
                                (str "(let [rs (atom {})]"
                                     " (binding [clojure.test/report (fn [m] (when (#{:pass :fail :error} (:type m))"
                                     " (swap! rs update (symbol (first clojure.test/*testing-vars*)) (fnil conj #{}) (:type m))))]"
                                     " (clojure.test/test-vars [#'" green " #'" red "]))"
                                     " @rs)")]
                               {:dirs ["src" "test"]})]
    (is (true? (:value held)) "the agent holds main's test namespace without requiring it")
    (is (= {green #{:pass} red #{:fail}} (:value run))
        "main's test runs green in the agent; a red one beside it reports red")))

(deftest an-agents-test-run-is-its-proofs-and-what-its-change-reaches
  ;; task seon.agent/an-agent-holds-mains-tests: the agent holds main's whole suite, and its
  ;; test run (root's :tests request, the one the judge sends) selects what the done rule
  ;; reads: the task's proofs and the tests reaching what its branch changed since the main
  ;; it forked from. One redefined function (same behaviour, another form) reaches one test; its namespace's other tests
  ;; and every other namespace's do not run
  (let [proof 'seon.reader-test/prose-without-a-fence-is-a-comment
        reached 'seon.config-test/the-command-line-s-overrides-are-taken-out-of-a-mode-s-args
        [_ run] (in-agent ["(in-ns 'seon.config) (defn without-overrides \"args with --config and --set (and their arguments) taken out: what a mode reads.\" {:malli/schema [:=> [:cat [:maybe [:sequential :string]]] [:vector :string]]} [args] (vec (:rest (overrides args)))) (in-ns 'agent.a1)"
                           {:op :eval :agent "root" :code "(seon.proofs/results @(seon.root/run-tests! sys :a1))"}]
                          {:dirs ["src" "test"] :proofs #{proof}})]
    (is (= {proof :pass reached :pass} (:value run))
        "the proof and the one test reaching the change ran, green; nothing else ran")))

(deftest a-wide-reach-reuses-unchanged-results
  ;; task seon.proofs/prove-runs-what-a-change-reaches (and seon.agent/a-wide-reach-stays-cheap):
  ;; root's boot records its source tree's inputs on its :seon.root/source fact (its binary is
  ;; built from that tree: bin/test built it), the agent keys its digests by them and selects
  ;; against the main it forked from, and each green goes to the shared results directory (a
  ;; fresh one here, so nothing an earlier run stored is reused) through root's :file owner,
  ;; so the next run (the next turn's observe, the judge's :tests, the merge gate) of an
  ;; unchanged branch reruns nothing and answers the same results
  (let [dir (str (System/getProperty "user.dir") "/target/tmp/reach-" (random-uuid))
        proof 'seon.reader-test/prose-without-a-fence-is-a-comment
        reached 'seon.config-test/the-command-line-s-overrides-are-taken-out-of-a-mode-s-args
        run "(let [r @(seon.root/run-tests! sys :a1)] [(seon.proofs/results r) (:ran r) (:reused r)])"
        own "(select-keys (prove) [:results :ran :reused])"
        stored "(count (filter #(.endsWith (.getName %) \".edn\") (.listFiles (java.io.File. (seon.config/value :seon.config.source/test-results-dir)))))"
        [_ inputs first-run files judged second-run]
        (in-agent ["(in-ns 'seon.config) (defn without-overrides \"args with --config and --set (and their arguments) taken out: what a mode reads.\" {:malli/schema [:=> [:cat [:maybe [:sequential :string]]] [:vector :string]]} [args] (vec (:rest (overrides args)))) (in-ns 'agent.a1)"
                   {:op :eval :agent "root" :code "(let [r (datahike.api/entity @(:conn sys) [:seon.root/id :root])] [(set (keys (:inputs (:seon.root/source r)))) (= (:boot (:seon.root/source r)) (:seon.root/boot r))])"}
                   own
                   {:op :eval :agent "root" :code stored}
                   {:op :eval :agent "root" :code run}
                   own]
                  {:dirs ["src" "test"] :proofs #{proof} :sets [(str ":seon.config.source/test-results-dir=" (pr-str dir))]})]
    (sh/sh "rm" "-rf" dir)
    (is (= [#{:env :tree :data-refs} true] (:value inputs)) "root's boot recorded its source tree's inputs, this boot's")
    (is (= {:results {proof :pass reached :pass} :ran 2 :reused 0} (:value first-run)) "the agent's own run runs the proof and the reached test")
    (is (= 2 (:value files)) "and stores both through root's :file owner, in the results directory")
    (is (= [{proof :pass reached :pass} 0 2] (:value judged)) "root's :tests (the judge's) runs nothing: both reused from it, the same results")
    (is (= {:results {proof :pass reached :pass} :ran 0 :reused 2} (:value second-run)) "and so does the agent's next run")))

(deftest one-bound-one-verdict-and-the-judge-reruns-no-green
  ;; task seon.proofs/one-prove-path: a test's tick budget is its own, so two tests that each
  ;; need t ticks are green under a budget of 1.5 t wherever they run (a per-run budget made
  ;; the second red in one run and green alone); the turn's greens are stored at their digests,
  ;; so the judge after it (the plan's tests, the same here: both are the run's proofs) runs
  ;; nothing and forks no child
  (let [dir (str (System/getProperty "user.dir") "/target/tmp/one-bound-" (random-uuid))
        [_ budget turn judge]
        ;; top-level forms, each recorded, so each test has a digest its green is stored at
        (in-agent ["(ns bound-test (:require [clojure.test :refer [deftest is]]))
                   (defn work {:malli/schema [:=> [:cat] :int]} [] (reduce + (range 300000)))
                   (deftest a (is (pos? (work))))
                   (deftest b (is (pos? (work))))"
                   "(do (def budget (long (* 3/2 (:ticks (get (seon.proofs/run-tests! '[bound-test/a] {} (fn [_]) nil) 'bound-test/a))))) budget)"
                   "(let [r (seon.agent/run-tests-forked budget #{'bound-test/a 'bound-test/b} :turn)] [(seon.proofs/results r) (:ran r) (some? (:pid r))])"
                   "(let [r (seon.agent/run-tests-forked budget #{'bound-test/a 'bound-test/b} :judge)] [(seon.proofs/results r) (:ran r) (:reused r) (some? (:pid r))])"]
                  {:dirs ["src" "test"] :proofs #{} :sets [(str ":seon.config.source/test-results-dir=" (pr-str dir))]})]
    (sh/sh "rm" "-rf" dir)
    (is (pos-int? (:value budget)) (pr-str budget))
    (is (= ['{bound-test/a :pass bound-test/b :pass} 2 true] (:value turn))
        (str "the turn runs both, each within its own budget: green " (pr-str turn)))
    (is (= ['{bound-test/a :pass bound-test/b :pass} 0 2 false] (:value judge))
        (str "the judge after it: the same verdict, both reused, nothing run and no child forked " (pr-str judge)))))

(deftest a-red-at-its-digest-is-reused-with-its-failures
  ;; task seon.proofs/a-red-at-its-digest-is-reused: the test service recorded r red at its
  ;; digest; r is green if it runs, so a run would say :pass. The judge reuses the red, with the
  ;; failure its run recorded, and forks nothing
  (let [[_ dg _ judge]
        (in-agent ["(ns reds-test (:require [clojure.test :refer [deftest is]]))
                   (deftest r (is true))"
                   "(get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/r})) 'reds-test/r)"
                   "(seon.agent/transact! [{:seon.proof/digest (get (:digests (seon.agent/test-plan (seon.agent/db) nil #{'reds-test/r})) 'reds-test/r)
                                           :seon.proof/test 'reds-test/r :seon.proof/status :fail :seon.proof/run \"main@1\" :seon.proof/branch \"main\" :seon.proof/t 1
                                           :seon.proof/failures [{:type :fail :message \"the recorded failure\" :expected \"1\" :actual \"2\"}]}])"
                   "(let [r (seon.agent/run-tests-forked 100000000 #{'reds-test/r} :judge)]
                      [(seon.proofs/results r) (:ran r) (:reused r) (some? (:pid r)) (:fail r) (mapv (juxt :message :reused) (:reports r))])"]
                  {:dirs ["src" "test"] :proofs #{}})]
    (is (string? (:value dg)) (pr-str dg))
    (is (= ['{reds-test/r :fail} 0 1 false 1 [["the recorded failure" true]]] (:value judge))
        (str "red as recorded, with its failure, nothing run and no child forked " (pr-str judge)))))

(deftest an-agents-greens-and-a-checkouts-are-one-store
  ;; owner, 2026-10-23: a test that is the same is not run again once green, "especially when
  ;; freshly branched": an agent's greens and a checkout's (bin/test's) are one store, keyed by
  ;; the same digests. The checkout stores p1 green; an agent on the same tree reuses it and
  ;; runs p2, storing it; the checkout's plan then reuses the agent's p2
  (let [here (System/getProperty "user.dir")
        dir (str here "/target/tmp/one-store-" (random-uuid))
        p1 'seon.reader-test/prose-without-a-fence-is-a-comment
        p2 'seon.reader-test/a-fenced-reply-reads-only-its-code
        digests (program/reach-digests (runner/program-db here (ts/program-store)) (fx/inputs here))
        _ (.mkdirs (java.io.File. ^String dir))
        ;; the checkout's green for p1, as bin/test stores one (a run nested in this test's run
        ;; would report through it)
        unstored (proofs/store-greens! #(fx/write-file {:req %}) dir {p1 {:test p1 :digest (digests p1) :pass 1 :fail 0 :error 0}})
        stored (proofs/greens dir [(digests p1)])
        [r] (in-agent ["(select-keys (prove) [:results :ran :reused])"]
                      {:dirs ["src" "test"] :proofs #{p1 p2} :sets [(str ":seon.config.source/test-results-dir=" (pr-str dir))]})
        checkout (proofs/plan nil (select-keys digests [p2]) (proofs/greens dir [(digests p2)]) #{})]
    (sh/sh "rm" "-rf" dir)
    (is (and (empty? unstored) (seq stored)) "the checkout stored its green for p1")
    (is (= {:results {p1 :pass p2 :pass} :ran 1 :reused 1} (:value r)) "the agent reuses the checkout's p1 and runs p2")
    (is (= [] (:run checkout)) "the checkout reuses the agent's p2")))

(deftest a-previous-roots-inputs-are-not-shared
  ;; re-review 2026-10-22: the :root entity outlives its root, so a restart (or a root with no
  ;; source on the same main) must not key its agents' results by a fact an earlier boot wrote
  (let [inputs {:env "e" :tree "t" :data-refs {}}
        source {:root "/r" :files 0 :forms 0 :ms 0 :boot "b1" :inputs inputs}
        db (fn [boot] (datahike.api/db-with (ts/test-db) [{:seon.root/id :root :seon.root/boot boot :seon.root/source source}]))]
    (is (some? (:dir (agent/test-plan (db "b1") (db "b1") #{}))) "this boot's inputs: its results are kept")
    (is (nil? (:dir (agent/test-plan (db "b2") (db "b2") #{}))) "an earlier boot's: nothing is shared or reused")))

(deftest a-forked-agent-knows-its-task
  ;; task seon.template/a-request-is-one-linked-transaction: root forks the agent for its
  ;; task, so my.task/current answers it at the agent's first form, before any turn loop
  ;; or context read begins (it was nil: root kept the task in its view only)
  (let [[id] (in-agent ["(:seon.task/id (my.task/current))"])]
    (is (= "demo/t" (:value id)) (pr-str id))))

(deftest an-agent-inherits-a-static-heap
  ;; design agent-loop section 2: an agent forked after main moved inherits the fork server's
  ;; heap in Chez's static generation, so its collections never copy (un-share) it. Measured
  ;; 2026-10-25: 163 MB of the fork server's heap sat in generations 3 and 4 at fork, and one
  ;; full collect in a fresh agent raised its footprint by 98 MB; made static, 106 MB after a
  ;; work that left today's agent at 333 MB. The bytes in Chez's collected generations are
  ;; work done (what the fork server held live), not load.
  (let [[r] (in-agent ["(vec (for [g (range 1 5)] (jolt.scheme/eval-string (str \"(bytes-allocated \" g \")\"))))"])
        mb (/ (reduce + (:value r)) 1048576.0)]
    (is (< mb 16) (str "the agent inherited " mb " MB outside the static generation: " (pr-str r)))))

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
                                   " :else (recur))))))")}])]
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
                         ;; in-agent's own agent is a1; the one spawned onto the kept branch is a2
                         {:op :eval :agent "a2" :code "(:key @seon.agent/state)"}])
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
                                   " :else (recur))))))")}])]
    (is (str/includes? (str (:value r)) ":value [7 2]") "a var main added, and main's redefinition of one the branch left alone")))

(deftest a-failure-marked-through-the-server-holds-the-task
  ;; owner, 2026-10-22: a failure is task data and root reacts to it. The agent marks its
  ;; task failed on its branch, hands the run back (seon_done), and root, at the
  ;; run's end, holds the task as :failed and reports it among the problems
  (let [[_ f _ _ seen] (in-agent [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} "(:reason (my.task/fail! \"the premise is refuted\" '(= 1 2)))"
                              before-done {:op :done :agent "a1"}
                              {:op :eval :agent "root"
                               :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                          " [(seon.reconcile/task-state (seon.reconcile/index @(:conn sys)) \"demo/t\")"
                                          " (select-keys (first (get-in (my.root/problems sys) [:classes :failed :rows])) [:task :reason :counterexample :open?])])")}])]
    (is (= "the premise is refuted" (:value f)) (pr-str f))
    (is (= [:failed {:task "demo/t" :reason "the premise is refuted" :counterexample "(= 1 2)" :open? true}]
           (:value seen)))))

(deftest an-outside-run-writes-its-end
  ;; found by a worked example (evaluations session, 2026-10-23): an outside agent's :done
  ;; judged a run that had no end, so its run read "ended no-end (the agent wrote no end)"
  ;; whatever its proofs said. The agent writes the end first, as a turn loop does: here its
  ;; one proof is not written, so the end is :red and names why
  (let [[_ _ done seen] (in-agent [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                                 before-done {:op :done :agent "a1"}
                                 {:op :eval :agent "root"
                                  :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                             " (select-keys (first (vals (seon.task/runs @(:conn sys)))) [:seon.run/outcome :seon.run/reason]))")}])
        run (:value seen)]
    (is (= :red (:outcome done)) (str "the answer is the verdict: " (pr-str done)))
    (is (= :red (:seon.run/outcome run)) (pr-str run))
    (is (str/includes? (str (:seon.run/reason run)) "proof-unknown demo-test/t") "the end names why it is red")
    (is (not (str/includes? (str (:seon.run/reason run)) "no end")) "never read as an end nobody wrote")))

(deftest an-outside-run-with-a-green-proof-finishes
  ;; root (2026-10-23): an outside agent that writes its proof green and hands the run back is
  ;; judged by the end it wrote, and its task finishes, not held
  (let [[_ _ t _ done seen] (in-agent [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                                     "(ns demo-test (:require [clojure.test :refer [deftest is]]))"
                                     "(deftest t (is (= 2 (inc 1))))"
                                     before-done {:op :done :agent "a1"}
                                     {:op :eval :agent "root"
                                      :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                                 " [(select-keys (first (vals (seon.task/runs @(:conn sys)))) [:seon.run/outcome :seon.run/status])"
                                                 "  (seon.reconcile/task-state (seon.reconcile/index @(:conn sys)) \"demo/t\")])")}])
        [run state] (:value seen)]
    (is (str/includes? (:text t) ":value #'demo-test/t") (pr-str t))
    (is (and (= :green (:outcome done)) (#{:done :merged} (:status done))) (str "the answer is the verdict: " (pr-str done)))
    (is (= :green (:seon.run/outcome run)) (pr-str run))
    (is (#{:done :merged} state) (str "the task finishes, never held for root: " state))))

(deftest the-judge-is-a-run-of-the-test-service
  ;; task seon.reconcile/the-judge-asks-the-test-service: the judge at a run's end asks the test
  ;; service (seon.prove), so its run is recorded on main, a fact per test digest and one for the
  ;; run, keyed by the branch at its program's digest in mode :judge (no !turn)
  (let [[_ _ _ _ _ seen] (in-agent [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                                  "(ns demo-test (:require [clojure.test :refer [deftest is]]))"
                                  "(deftest t (is (= 2 (inc 1))))"
                                  before-done {:op :done :agent "a1"}
                                  {:op :eval :agent "root"
                                   :code (str "(do (seon.await/await-quiet sys (inc t-before-done))"
                                              " (let [db @(:conn sys)]"
                                              "  [(d/q '[:find [?id ...] :where [?r :seon.proof.run/id ?id]] db)"
                                              "   (d/q '[:find [?s ...] :where [?e :seon.proof/test demo-test/t] [?e :seon.proof/status ?s] [?e :seon.proof/digest]] db)"
                                              "   (:seon.run/outcome (first (vals (seon.task/runs db))))]))")}])
        [runs statuses outcome] (:value seen)]
    (is (some #(re-find #"@[0-9a-f]+$" %) runs) (str "a judge run on main, keyed branch@digest: " (pr-str seen)))
    (is (= [:pass] statuses) "its result is a fact at the test's digest")
    (is (= :green outcome))))

(deftest an-agent-finds-no-repository
  ;; owner, 2026-10-22: no git in agents, and no source-file editing as the way to develop
  ;; Seon; an agent works in a scratch dir with no repository above it
  ;; (:seon.config.agent/cwd) and still reads any file by its path
  (let [[abs git] (in-agent [(str "(count (slurp " (pr-str (.getCanonicalPath (java.io.File. "src/seon/reconcile.clj"))) "))")
                             "(clojure.java.shell/sh \"git\" \"rev-parse\" \"--git-dir\")"])]
    (is (= (count (slurp "src/seon/reconcile.clj")) (:value abs)) "a file reads by its absolute path")
    (is (str/includes? (:text git) ":exit 128") (str "git finds no repository: " (pr-str git)))
    (is (str/includes? (:text git) "not a git repository") (pr-str git))))

(deftest each-eval-call-is-answered-before-the-next
  ;; owner, 2026-10-22 (DECISIONS "Agents work at the REPL, through tool calls"): a reply was a
  ;; batch of forms whose results came only after the whole reply, and 18% of model turns were
  ;; repetition loops asking for answers the model could not see. Now each call of the one
  ;; tool, eval, is answered as its own tool message before the next model call: the scripted model
  ;; (seon.fixtures/two-calls) writes its second call from the answer its first call got
  (let [[end msgs] (in-agent ["(seon.turn/work! {:task \"demo/t\" :run \"demo/t#calls\" :max-turns 2})"
                              (str "(let [db (seon.agent/db)]"
                                   " (->> (:messages (seon.context/render db {:commit (datahike.api/commit-id db) :task \"demo/t\" :run \"demo/t#calls\""
                                   "                                       :turn 2 :max-turns 2 :origin :model :observed {} :agent (:key @seon.agent/state)}))"
                                   "      (filter #(#{\"assistant\" \"tool\"} (:role %))) (mapv #(select-keys % [:role :tool-calls :tool-call-id :content]))))")]
                             {:sets [":seon.config.ai/script=seon.fixtures/two-calls"]})
        end (:value end)
        [a0 r0 a1 r1 :as ms] (:value msgs)]
    (is (= [:cap 2] [(:outcome end) (:model-calls end)]) "two model calls (the proof stays red), then the cap")
    (is (= ["assistant" "tool" "assistant" "tool"] (mapv :role ms)) "each reply followed by its call's answer")
    (is (= [{:id "call-0" :code "(+ 20 21)"}] (:tool-calls a0)))
    (is (= "call-0" (:tool-call-id r0)) "the answer names its call")
    (is (str/includes? (:content r0) "41") "the first call's answer")
    (is (= [{:id "call-1" :code "(+ 1 41)"}] (:tool-calls a1)) "the second call was made after the first call's answer, from it")
    (is (str/includes? (:content r1) "42") "and both answers are in the conversation")))

(deftest an-agent-that-throws-ends-with-its-cause
  ;; task seon.agent/a-death-names-its-cause: a defect that no request's own boundary
  ;; catches (here the agent redefines, at its REPL as an agent changes the platform, a
  ;; function its own loop calls after each request: seon.agent/db)
  ;; ends the process at its entrant boundary: the cause is the run's end on its branch,
  ;; written before the process exits, and root answers the request it died in with the
  ;; exit status the fork server reaped
  (let [[_ died end] (in-agent [{:op :context :agent "a1"}
                                "(in-ns 'seon.agent) (clojure.core/defn db [] (throw (ex-info \"boom: the loop's own function\" {:x 1})))"
                                {:op :eval :agent "root"
                                 :code (str "(let [a (first (filter #(= :a1 (:seon.run/agent %)) (vals (seon.task/runs @(:conn sys)))))]"
                                            " (seon.reconcile/pull-end (:db @(seon.root/branch-db sys (seon.task/default-branch \"demo/t\"))) (:seon.run/id a)))")}])
        end (:value end)]
    (is (= :seon.agent/died (:seon.error/type died)) "the request it died in is answered with its death, the one error map")
    (is (= {:exit 70} (:exit died)) "named by the exit status root observed")
    (is (= :error (:outcome end)) "the run's end is on its branch")
    (is (= "boom: the loop's own function" (get-in end [:error :seon.error/message])) "with its cause, the one error map")
    (is (= 'seon.repl/serve! (get-in end [:error :seon.error/operation])))))

(defn- started "The line the child writes as test var v starts." {:malli/schema [:=> [:cat :string] :string]}
  [v] (pr-str {:seon.test/started v}))

(deftest a-killed-test-run-names-the-test-that-was-running
  (testing "a child killed while running app-test/slow: that test is red, with how the child ended"
    (let [run (agent/test-run-answer (started "#'app-test/slow") {:signal 9})]
      (is (= {'app-test/slow :error} (proofs/results run)))
      (is (= [] (proofs/test-run-errors run)) "the death is the running test's, not an anonymous run error")
      (is (= {:signal 9} (:exit (first (:reports run)))))))
  (testing "a child that wrote its result last: the result"
    (let [res {:pass 1 :fail 0 :error 0 :test 1 :reports [{:type :pass :var "#'app-test/fast" :message nil}]}]
      (is (= res (agent/test-run-answer (pr-str res) {:exit 0})))))
  (testing "a child that died before any test started: one run error naming its exit"
    (let [run (agent/test-run-answer nil {:exit 1})]
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
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :db @store-conn :base forked})
         (is (empty? (program/changed-vars (:base @agent/state) (agent/db))) "the agent has changed nothing")
         (let [r (agent/merge-main! #{})]
           (is (some #{[:var 'app.m0/f1]} (:applied r)) "main's change merges in")
           (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity (agent/db) (:t r))))
               "in a merge commit that names its runtime (task seon.db/each-commit-names-its-runtime)"))
         (is (empty? (program/changed-vars (:base @agent/state) (agent/db)))
             "and is main's, not the agent's: its base is the merge base")
         (is (= {} (program/divergence (:base @agent/state) (agent/db)))
             "so the agent's :will-merge carries none of main's changes")
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
           (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity @store-conn t)))))
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))


(deftest a-root-with-no-provider-ends-the-run-on-its-typed-error
  ;; root's ruling 2026-10-22: a root with no model provider (no SEON_PROFILE) answers the
  ;; first turn with :seon.model/no-provider; the error reaches whoever can act on it: the
  ;; run's record on main and my.root/problems' :error rows
  (let [q "[:find (pull ?e [:seon.run/outcome :seon.run/error]) . :where [?e :seon.run/task ?t] [?t :seon.task/id \"demo/m\"] [?e :seon.run/error]]"
        [_ r] (in-agent [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root"
                        :code (str "(do (my.root/run! sys [\"demo/m\"])"
                                   " (let [a (datahike.api/q '" q " (seon.root/await-db sys #(datahike.api/q '" q " %)))]"
                                   " [(:seon.run/outcome a) (:seon.error/type (:seon.run/error a))"
                                   " (mapv #(get-in % [:error :seon.error/type]) (get-in (my.root/problems sys) [:classes :error :rows]))]))")}]
                      {:sets [":seon.config.ai/provider=:none"]
                       :decls [{:seon.task/name "m" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})]
    (is (= [:error :seon.model/no-provider [:seon.model/no-provider]] (:value r)) (pr-str r))))

(deftest a-requests-implement-continues-its-designs-request
  ;; live finding A1, on a real request (the pipeline's: the implement's first request sent its own
  ;; story in second place and no boundary, 512 of 35,126 tokens cached): through my.root/request! and
  ;; the reconciler, the implement's first turn, on the design's session, sends the design's story
  ;; where the design's requests did, the design's last request whole as its prefix, then root's
  ;; boundary message (seon.reconcile/stage-boundary), read off the turns as the branch recorded them
  (let [code (str "(let [_ (my.root/request! sys {:seon.task/ns 'demo :seon.task/name \"st\" :seon.task/plan \"Make st.\"})"
                  " _ (seon.root/await-db sys \"the implement parked\" #(= :parked (:seon.run/status (datahike.api/entity % [:seon.run/id \"demo/st-implement#1\"]))))"
                  " b (:db @(seon.root/call! sys {:op :write :req {:op :branch-db :branch (seon.task/default-branch \"demo/st\")}}))"
                  " sent (fn [run] (mapv second (sort (datahike.api/q '[:find ?n ?s :in $ ?id :where [?r :seon.run/id ?id] [?u :seon.turn/run ?r] [?u :seon.turn/n ?n] [?u :seon.turn/origin :model] [?u :seon.turn/sent ?s]] b run))))"
                  " boundary (datahike.api/q '[:find ?mid . :where [?r :seon.run/id \"demo/st-implement#1\"] [?m :seon.message/to ?r] [?m :seon.message/from \"root\"] [?m :seon.message/id ?mid]] b)]"
                  " {:design (sent \"demo/st#1\") :implement (sent \"demo/st-implement#1\") :boundary boundary})")
        [_ r] (in-agent [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")} {:op :eval :agent "root" :code code}]
                        {:sets [":seon.config.ai/script=seon.fixtures/stages"]})
        {:keys [design implement boundary]} (:value r)
        last-design (peek design) first-implement (first implement)]
    (is (and (seq design) (seq implement) boundary) (pr-str r))
    (is (= (second (first design)) (second first-implement)) "the design's story, in the same place")
    (is (= last-design (subvec first-implement 0 (min (count first-implement) (count last-design)))) "the design's last request is its exact prefix")
    (is (some #(= [:message boundary] (vec (take 2 %))) (drop (count last-design) first-implement)) "then root's boundary")))

(deftest a-waiting-run-parks-and-a-message-wakes-the-same-process
  ;; design agent-loop section 2 (owner, 2026-10-25: "Always park"): a run that waits parks its
  ;; session, its process kept; the owner's message wakes it, and the next run is the same
  ;; process (its pid), on the same agent (the parked run's own: the harness's agent is another)
  (let [runs "[:find ?id ?st ?out ?ag :where [?e :seon.run/task ?t] [?t :seon.task/id \"demo/w\"] [?e :seon.run/id ?id] [?e :seon.run/status ?st] [?e :seon.run/outcome ?out] [?e :seon.run/agent ?a] [?a :seon.agent/id ?ag]]"
        parked (fn [n] (str "(seon.root/await-db sys #(= " n " (count (filter (fn [[_ st]] (= :parked st)) (datahike.api/q '" runs " %)))))"))
        [_ r] (in-agent [{:op :eval :agent "root" :code (str "(load-file " (pr-str await-file) ")")}
                         {:op :eval :agent "root"
                          :code (str "(do (my.root/run! sys [\"demo/w\"]) " (parked 1)
                                     " (let [ag (some (fn [[id _ _ ag]] (when (= \"demo/w#1\" id) ag)) (datahike.api/q '" runs " @(:conn sys)))"
                                     " pid1 (get-in (c/state sys) [:agents ag :pid])]"
                                     " @(seon.root/message! sys ag \"carry on\") " (parked 2)
                                     " {:runs (sort (datahike.api/q '" runs " @(:conn sys))) :pid1 pid1 :pid2 (get-in (c/state sys) [:agents ag :pid])}))")}]
                        {:sets [":seon.config.ai/script=seon.fixtures/wait-call"]
                         :decls [{:seon.task/name "w" :seon.task/plan "Wait." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})
        {:keys [runs pid1 pid2]} (:value r)]
    (is (= [["demo/w#1" :parked :waiting] ["demo/w#2" :parked :waiting]] (mapv #(subvec % 0 3) runs)) (pr-str r))
    (is (apply = (map peek runs)) "both runs on one agent")
    (is (and pid1 (= pid1 pid2)) (str "one process for both runs: " pid1 " " pid2))))

(deftest a-failed-call-keeps-what-it-sent
  ;; root, 2026-10-24: a turn whose model call fails still records what it sent (written as the
  ;; request goes) and its typed error (an error is data, AGENTS section 4); the debug page shows both
  (let [[end turn] (in-agent ["(seon.turn/work! {:task \"demo/t\" :run \"demo/t#fail\" :max-turns 1})"
                              (str "(let [db (seon.agent/db) t (datahike.api/pull db '[:db/id :seon.turn/sent :seon.turn/error] [:seon.turn/id \"demo/t#fail/0\"])]"
                                   "  {:sent (mapv first (:seon.turn/sent t)) :error (:seon.error/type (:seon.turn/error t))"
                                   "   :read (count (mapcat second (seon.context/parts-of db (:seon.turn/sent t))))"
                                   "   :screen (seon.print/record-text (:seon.eval/record (datahike.api/pull db '[:seon.eval/record] [:seon.eval/id (second (nth (:seon.turn/sent t) 2))])))})")]
                             {:sets [":seon.config.ai/provider=:none"]})]
    (is (= :error (:outcome (:value end))) (pr-str end))
    (is (= [:section :task :eval :eval :eval :eval] (:sent (:value turn))) "the help page, the task story, the screen's four evals (show, status, changes, related)")
    (is (str/includes? (str (:screen (:value turn))) ":page") (str "the screen is root's page of it: " (:screen (:value turn))))
    (is (str/includes? (str (:screen (:value turn))) "[:h1 \"a1\"]") (str "its head in words, read whole at the screen's budget: " (:screen (:value turn))))
    (is (= :seon.model/no-provider (:error (:value turn))) "and the typed error the call answered")
    (is (= 6 (:read (:value turn))) "read back from its records")))

(deftest a-turn-that-repeats-the-last-from-the-same-state-ends-the-run
  ;; root's research, lever 2 (2026-10-22): loops took 45% of tokens, and the step E dry run's
  ;; first real design made one pair of calls 18 times from an unchanged state. A turn whose
  ;; calls repeat the last turn's, from the same observed state, is a repeat counted on the run:
  ;; the first is told to the agent in its next status (live agents round 1, 2026-10-25: 3 of 6
  ;; runs ended at a first repeat that followed an answer giving nothing new; root's provisional
  ;; ruling), the second ends it :no-progress, naming the code; the judge refines from that evidence
  (let [[end repeats status] (in-agent ["(seon.turn/work! {:task \"demo/t\" :run \"demo/t#loop\" :max-turns 5})"
                                        "(:seon.run/repeats (datahike.api/pull (seon.agent/db) [:seon.run/repeats] [:seon.run/id \"demo/t#loop\"]))"
                                        "(:repeated (:your-last-reply (my.task/status)))"]
                                       {:sets [":seon.config.ai/script=seon.fixtures/same-call"]})
        end (:value end)]
    (is (= [:no-progress 3] [(:outcome end) (:model-calls end)]) (str "told once, ended at the second repeat: " (pr-str end)))
    (is (str/includes? (str (:reason end)) "(+ 1 1)") "the reason names the repeated code")
    (is (= 2 (:value repeats)) "the repeats are the run's, on its branch")
    (is (str/includes? (str (:value status)) "same as the turn before's") (str "the last status told it: " (pr-str status))))
  (let [[end] (in-agent ["(seon.turn/work! {:task \"demo/t\" :run \"demo/t#loop0\" :max-turns 5})"]
                        {:sets [":seon.config.ai/script=seon.fixtures/same-call" ":seon.config.agent/repeats-told=0"]})]
    (is (= [:no-progress 2] ((juxt :outcome :model-calls) (:value end))) "told none: the first repeat ends it"))
  ;; root, 2026-10-25: no progress is repeats in a row; a turn that made progress starts the count again
  ;; (round 8's two ends were "repeat 2 of the run" across progress between)
  (let [[end] (in-agent ["(seon.turn/work! {:task \"demo/t\" :run \"demo/t#moves\" :max-turns 6})"]
                        {:sets [":seon.config.ai/script=seon.fixtures/repeat-then-move"]})]
    (is (= [:cap 6] ((juxt :outcome :model-calls) (:value end))) (str "each repeat told, none in a row twice: " (pr-str (:value end))))))

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
  (let [[_ counts] (in-agent ["(seon.turn/work! {:task \"demo/t\" :run \"demo/t#each\" :max-turns 1})"
                              (str "(let [db (seon.agent/db)"
                                   "      n (fn [db] (count (datahike.api/q '[:find ?e :where [?e :seon.eval/id]] db)))"
                                   "      back (fn [db] (when-let [p (first (datahike.api/parent-commit-ids db))] (datahike.api/commit-as-db db p)))]"
                                   "  (vec (distinct (map n (take 30 (take-while some? (iterate back db)))))))")]
                             {:sets [":seon.config.ai/script=seon.fixtures/same-call"]})
        ns (:value counts)]
    (is (< 3 (count ns)) (pr-str ns))
    (is (every? #(<= % 1) (map - ns (rest ns))) (str "each eval its own commit: " (pr-str ns)))))

(deftest a-model-turn-records-its-prose-and-the-message-it-took
  ;; task seon.render/a-debug-view-of-any-agent: the owner's message is a fact root writes on
  ;; main (from "owner", to the run the agent works); the turn that takes it writes the same
  ;; fact on its branch before its commit and names it by id in its basis, so the window and every
  ;; later reading read its text from that commit; the reply's prose (here the scripted model's)
  ;; is the turn's reply and its call's eval's prose, and the call names its one eval
  ;; (notes/design-agent-view-2026-10-24.md §7)
  (let [[_ end turn on-main] (in-agent [{:op :message :agent "a1" :body "use demo/g"}
                                        "(seon.turn/work! {:task \"demo/t\" :run \"demo/t#think\" :max-turns 1})"
                                        (str "(let [db (seon.agent/db) t (datahike.api/pull db '[*] [:seon.turn/id \"demo/t#think/0\"])"
                                             "      ids (get-in t [:seon.turn/basis :observed :message-ids])]"
                                             "  {:calls (mapv #(select-keys % [:code :eval]) (:seon.turn/calls t)) :ids ids :reply (:seon.turn/reply t)"
                                             "   :committed (seon.context/taken-messages (datahike.api/commit-as-db db (get-in t [:seon.turn/basis :commit])) {:message-ids ids})"
                                             "   :prose (mapv #(:prose (datahike.api/q '[:find ?r . :in $ ?i :where [?e :seon.eval/id ?i] [?e :seon.eval/record ?r]] db %)) (keep :eval (:seon.turn/calls t)))})")
                                        {:op :eval :agent "root"
                                         :code (str "(datahike.api/q '[:find ?id ?f ?to ?b :where [?m :seon.message/id ?id] [?m :seon.message/from ?f]"
                                                    " [?m :seon.message/to ?r] [?r :seon.run/id ?to] [?m :seon.message/body ?b]] @(:conn sys))")}]
                                       {:sets [":seon.config.ai/script=seon.fixtures/thinking-call"]})
        end (:value end)
        {:keys [calls ids committed prose reply]} (:value turn)
        [[mid from to body] :as facts] (vec (:value on-main))]
    (is (= 1 (:model-calls end)) (pr-str end))
    (is (= [["owner" "demo/t#1" "use demo/g"]] (mapv #(subvec % 1) facts)) "one fact on main: from the owner, to the run a1 works")
    (is (= [mid] ids) "the turn's basis names the fact root wrote, by its id")
    (is (= [{:id mid :from "owner" :body "use demo/g"}] committed) "and its text is read from the commit the turn read")
    ;; the prose comes in the eval call (its first argument, notes/design-agent-view-2026-10-24.md
    ;; section 1), so the reply's own text is empty and the prose is the eval's
    (is (= "" reply) "the reply's text: the prose came in its call")
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
  ;; value kept for a time, so two calls back to back see what the process allocated between
  ;; them (a reading cached for a second answers the first one again)
  (let [before (agent/mem-mb)
        held (byte-array (* 96 1024 1024))
        _ (dotimes [i (quot (alength held) 4096)] (aset-byte held (* i 4096) 1))
        after (agent/mem-mb)]
    (is (>= (- after before) 64.0) (str "96 MB written between two calls shows in the second: " before " -> " after))
    (is (pos? (alength held)) "held until the second reading")))

(deftest related-is-an-eval-of-the-plan-and-of-any-text
  ;; my.task/related (the screen form) and my.agent/related (the agent's own words), in a test-mode
  ;; agent over seon.context-test's five sections; a process test because my.agent reaches a fork
  (let [[db _ dir] (#'seon.context-test/sections-near "Make the page say one thing (docs/vector.md#what-is-embedded).")
        old @agent/state]
    (reset! agent/state {:n 0 :mode :test :db db :task "demo/r"})
    (try
      (is (= "docs/vector.md#the-model: Section 0 says one thing." (first (:sections (my.task/related))))
          "the screen form: the sections nearest the plan, the cited one left out")
      (binding [agent/*fx-stub* (fn [_ _] {:value {:model "m" :vectors [(dir 1.0 0.5)] :tokens [3]}})]
        (let [v (my.agent/related "the last one" 2)]
          (is (= "docs/vector.md#what-the-work-is-likely-to-need-as-an-eval: Section 4 says one thing." (first (:sections v)))
              "the agent's own words refine it: the sections nearest them")
          (is (= 2 (count (:sections v))) "k of them")))
      (finally (reset! agent/state old)))))
