(ns seon.agent-test
  "An agent's process, run as the built binary (target/seon, bin/build seon): the root forks
  it, so the test boots a root of its own, as seon.root-test does, and asks the agent over
  the server what its REPL answers.

  An agent's test run, read from its child's pipe (task seon.fences/one-test-bound-derived-and-named):
  no clock ends it; the child's exit does, and a child that died names the test it was running.
  Run: bin/test seon.agent-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.agent :as agent] [seon.proofs :as proofs]
            [datahike.api :as d] [seon.db :as pdb] [seon.test-store :as ts] [seon.lib :as lib] [seon.program :as program] [seon.sock :as sock]))

(defn in-agent
  "A root on the checkout's src (its source root), one agent on a task of its own, each of
  forms evaluated in that agent (a string) or sent to the server as it is (a map): the
  first item of every reply, or the answer that holds no items, in order. The root works on a
  main of its own, a branch of the one test store (target/runs/test-store), deleted with
  every branch under it when the root has stopped (seon.check/drop-mains).
  opts: :dirs, the source dirs root imports (default [\"src\"]); :proofs, the task's proofs;
  :sets, more --set config overrides for the root (e.g. its scripted model); :decls, more
  task declarations in demo's file beside demo/t."
  ([forms] (in-agent forms {}))
  ([forms {:keys [dirs proofs sets decls] :or {dirs ["src"] proofs '#{demo-test/t}}}]
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        main (str "in-agent-" (random-uuid))
        store (str checkout "/target/runs/test-store")
        path (str checkout "/target/runs/" main)
        port (let [s (java.net.ServerSocket. 0)] (try (.getLocalPort s) (finally (.close s))))
        ask (fn [m] (str "printf '%s\\n' " (pr-str (pr-str m)) " | nc -w 60 127.0.0.1 " port))
        script (str "mkdir -p '" path "-tasks'; mkfifo '" path "-in';"
                    " echo " (pr-str (pr-str (into [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs proofs}] decls)))
                    " > '" path "-tasks/demo.edn'; ./seon root"
                    " --set ':seon.config.source/root=" (pr-str checkout) "' --set ':seon.config.source/dirs=" (pr-str dirs) "'"
                    " --set ':seon.config.root/store-path=" (pr-str store) "' --set ':seon.config.root/main=" (pr-str main) "'"
                    " --set ':seon.config.root/entrance-port=" port "' --set ':seon.config.web/port=0'"
                    (str/join (for [x sets] (str " --set '" x "'")))
                    " < '" path "-in' > '" path ".log' 2>&1 & R=$!; exec 3>'" path "-in';"
                    " until grep -q 'ROOT LISTENING' '" path ".log' || ! kill -0 $R 2>/dev/null; do sleep 0.1; done;"
                    (ask {:op :eval :agent "root" :code (str "(my.root/load-tasks! sys " (pr-str (str path "-tasks")) ")")}) " >/dev/null;"
                    (ask {:op :attach :task "demo/t"}) " >/dev/null;"
                    " for i in {1..300}; do " (ask {:op :agents}) " | grep -q idle && break; sleep 0.2; done;"
                    (str/join (for [f forms] (str (ask (if (map? f) f {:op :eval :agent "a1" :code f})) ";")))
                    " exec 3>&-; wait $R")
        {:keys [out]} (ts/binary-sh "zsh" "-c" script :dir "target")]
    (ts/binary-sh "./seon" "drop" main "--set" (str ":seon.config.root/store-path=" (pr-str store)) :dir "target")
    (sh/sh "rm" "-rf" (str path "-in") (str path ".log") (str path "-tasks"))
    (mapv #(let [a (edn/read-string {:default tagged-literal} %)] (if (contains? a :items) (first (:items a)) a)) (str/split-lines (str/replace out #"(?s)^.*ROOT LISTENING[^\n]*\n" ""))))))

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
    (is (= "true" (:printed held)) "the agent holds main's test namespace without requiring it")
    (is (= {green #{:pass} red #{:fail}} (edn/read-string (:printed run)))
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
    (is (= {proof :pass reached :pass} (edn/read-string (:printed run)))
        "the proof and the one test reaching the change ran, green; nothing else ran")))

(deftest a-wide-reach-reuses-unchanged-results
  ;; task seon.proofs/prove-runs-what-a-change-reaches (and seon.agent/a-wide-reach-stays-cheap):
  ;; root's boot records its source tree's inputs on its :seon.root/source fact (its binary is
  ;; built from that tree: bin/test built it), the agent keys its digests by them and selects
  ;; against the main it forked from, and each green goes to its root's results directory
  ;; through root's :file owner, so the next run (the next turn's observe, the judge's :tests,
  ;; the merge gate) of an unchanged branch reruns nothing and answers the same results
  (let [proof 'seon.reader-test/prose-without-a-fence-is-a-comment
        reached 'seon.config-test/the-command-line-s-overrides-are-taken-out-of-a-mode-s-args
        run "(let [r @(seon.root/run-tests! sys :a1)] [(seon.proofs/results r) (:ran r) (:reused r)])"
        own "(select-keys (prove) [:results :ran :reused])"
        stored "(count (filter #(.endsWith (.getName %) \".edn\") (.listFiles (java.io.File. (seon.agent/results-dir)))))"
        [_ inputs first-run files judged second-run]
        (in-agent ["(in-ns 'seon.config) (defn without-overrides \"args with --config and --set (and their arguments) taken out: what a mode reads.\" {:malli/schema [:=> [:cat [:maybe [:sequential :string]]] [:vector :string]]} [args] (vec (:rest (overrides args)))) (in-ns 'agent.a1)"
                   {:op :eval :agent "root" :code "(let [r (datahike.api/entity @(:conn sys) [:seon.root/id :root])] [(set (keys (:inputs (:seon.root/source r)))) (= (:boot (:seon.root/source r)) (:seon.root/boot r))])"}
                   own
                   {:op :eval :agent "root" :code stored}
                   {:op :eval :agent "root" :code run}
                   own]
                  {:dirs ["src" "test"] :proofs #{proof}})]
    (is (= [#{:env :tree :data-refs} true] (edn/read-string (:printed inputs))) "root's boot recorded its source tree's inputs, this boot's")
    (is (= {:results {proof :pass reached :pass} :ran 2 :reused 0} (edn/read-string (:printed first-run))) "the agent's own run runs the proof and the reached test")
    (is (= 2 (edn/read-string (:printed files))) "and stores both through root's :file owner, in its root's results directory")
    (is (= [{proof :pass reached :pass} 0 2] (edn/read-string (:printed judged))) "root's :tests (the judge's) runs nothing: both reused from it, the same results")
    (is (= {:results {proof :pass reached :pass} :ran 0 :reused 2} (edn/read-string (:printed second-run))) "and so does the agent's next run")))

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
    (is (= (pr-str "demo/t") (:printed id)))))

(deftest a-failure-marked-through-the-server-holds-the-task
  ;; owner, 2026-10-22: a failure is task data and root reacts to it. The agent marks its
  ;; task failed on its branch, hands the attempt back (seon_done), and root, at the
  ;; attempt's end, holds the task as :failed and reports it among the problems
  (let [[f _ seen] (in-agent ["(:reason (my.task/fail! \"the premise is refuted\" '(= 1 2)))"
                              {:op :done :agent "a1"}
                              {:op :eval :agent "root"
                               :code (str "(let [t (:max-tx @(:conn sys))] (require 'seon.e2e) (seon.e2e/await-quiet sys (inc t))"
                                          " [(seon.reconcile/task-state (seon.reconcile/index @(:conn sys)) \"demo/t\")"
                                          " (select-keys (first (get-in (my.root/problems sys) [:classes :failed :rows])) [:task :reason :counterexample :open?])])")}])]
    (is (= (pr-str "the premise is refuted") (:printed f)))
    (is (= [:failed {:task "demo/t" :reason "the premise is refuted" :counterexample "(= 1 2)" :open? true}]
           (edn/read-string (:printed seen))))))

(deftest an-agent-finds-no-repository
  ;; owner, 2026-10-22: no git in agents, and no source-file editing as the way to develop
  ;; Seon; an agent works in a scratch dir with no repository above it
  ;; (:seon.config.agent/cwd) and still reads any file by its path
  (let [[abs git] (in-agent [(str "(count (slurp " (pr-str (.getCanonicalPath (java.io.File. "src/seon/reconcile.clj"))) "))")
                             "(clojure.java.shell/sh \"git\" \"rev-parse\" \"--git-dir\")"])]
    (is (= (str (count (slurp "src/seon/reconcile.clj"))) (:printed abs)) "a file reads by its absolute path")
    (is (str/includes? (str (:printed git)) ":exit 128") (str "git finds no repository: " (pr-str git)))
    (is (str/includes? (str (:printed git)) "not a git repository") (pr-str git))))

(deftest each-eval-call-is-answered-before-the-next
  ;; owner, 2026-10-22 (DECISIONS "Agents work at the REPL, through tool calls"): a reply was a
  ;; batch of forms whose results came only after the whole reply, and 18% of model turns were
  ;; repetition loops asking for answers the model could not see. Now each call of the one
  ;; tool, eval, is answered as its own tool message before the next model call: the scripted model
  ;; (seon.fixtures/two-calls) writes its second call from the answer its first call got
  (let [[end msgs] (in-agent ["(seon.turn/work! {:task \"demo/t\" :attempt \"demo/t#calls\" :max-turns 2})"
                              (str "(let [db (seon.agent/db)]"
                                   " (->> (:messages (seon.context/render db {:commit (datahike.api/commit-id db) :task \"demo/t\" :attempt \"demo/t#calls\""
                                   "                                       :turn 2 :max-turns 2 :origin :model :observed {}}))"
                                   "      (filter #(#{\"assistant\" \"tool\"} (:role %))) (mapv #(select-keys % [:role :tool-calls :tool-call-id :content]))))")]
                             {:sets [":seon.config.ai/script=seon.fixtures/two-calls"]})
        end (edn/read-string {:default tagged-literal} (:printed end))
        [a0 r0 a1 r1 :as ms] (edn/read-string {:default tagged-literal} (:printed msgs))]
    (is (= [:cap 2] [(:outcome end) (:model-calls end)]) "two model calls (the proof stays red), then the cap")
    (is (= ["assistant" "tool" "assistant" "tool"] (mapv :role ms)) "each reply followed by its call's answer")
    (is (= [{:id "call-0" :code "(+ 20 21)"}] (:tool-calls a0)))
    (is (= "call-0" (:tool-call-id r0)) "the answer names its call")
    (is (str/includes? (:content r0) "41") "the first call's answer")
    (is (= [{:id "call-1" :code "(+ 1 41)"}] (:tool-calls a1)) "the second call was made after the first call's answer, from it")
    (is (str/includes? (:content r1) "42") "and both answers are in the conversation")))

(deftest an-agent-that-throws-ends-with-its-cause
  ;; task seon.agent/a-death-names-its-cause: a defect that no request's own boundary
  ;; catches (here the agent redefined a function its own loop calls, as a real REPL may)
  ;; ends the process at its entrant boundary: the cause is the attempt's end on its branch,
  ;; written before the process exits, and root answers the request it died in with the
  ;; exit status the fork server reaped
  (let [[_ died end] (in-agent [{:op :context :agent "a1"}
                                "(intern 'seon.agent 'mem-mb (fn [& _] (throw (ex-info \"boom: the loop's own function\" {:x 1}))))"
                                {:op :eval :agent "root"
                                 :code (str "(let [a (first (filter #(= :a1 (:seon.attempt/agent %)) (vals (seon.task/attempts @(:conn sys)))))]"
                                            " (seon.reconcile/pull-end (:db @(seon.root/branch-db sys (seon.task/default-branch \"demo/t\"))) (:seon.attempt/id a)))")}])
        end (edn/read-string {:default tagged-literal} (:printed end))]
    (is (= :err (:tag died)) "the request it died in is answered with its death")
    (is (str/includes? (str (:printed died)) "exit 70") "named by the exit status root observed")
    (is (= :error (:outcome end)) "the attempt's end is on its branch")
    (is (= "boom: the loop's own function" (get-in end [:error :seon.error/message])) "with its cause, the one error map")
    (is (= 'seon.repl/serve! (get-in end [:error :seon.error/operation])))))

(defn- started "The line the child writes as test var v starts." {:malli/schema [:=> [:cat :string] :string]}
  [v] (pr-str {:seon.test/started v}))

(deftest a-killed-test-run-names-the-test-that-was-running
  (testing "a child killed while running app-test/slow: that test is red, with how the child ended"
    (let [run (agent/run-answer (started "#'app-test/slow") {:signal 9})]
      (is (= {'app-test/slow :error} (proofs/results run)))
      (is (= [] (proofs/run-errors run)) "the death is the running test's, not an anonymous run error")
      (is (= {:signal 9} (:exit (first (:reports run)))))))
  (testing "a child that wrote its result last: the result"
    (let [res {:pass 1 :fail 0 :error 0 :test 1 :reports [{:type :pass :var "#'app-test/fast" :message nil}]}]
      (is (= res (agent/run-answer (pr-str res) {:exit 0})))))
  (testing "a child that died before any test started: one run error naming its exit"
    (let [run (agent/run-answer nil {:exit 1})]
      (is (= {} (proofs/results run)))
      (is (= 1 (count (proofs/run-errors run)))))))

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
         (reset! agent/state {:fd a :store-conn store-conn :cfg cfg :branch ab :db @store-conn :lease 0})
         (let [{:keys [t]} (agent/transact! [{:note/text "the agent's"}])]
           (agent/commit!)
           (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity @store-conn t)))))
         (finally (reset! agent/state old) (d/release store-conn) (lib/c-close a) (lib/c-close b)))))))


(deftest a-root-with-no-provider-ends-the-attempt-on-its-typed-error
  ;; root's ruling 2026-10-22: a root with no model provider (no SEON_PROFILE) answers the
  ;; first turn with :seon.model/no-provider; the error reaches whoever can act on it: the
  ;; attempt's record on main and my.root/problems' :error rows
  (let [q "[:find (pull ?e [:seon.attempt/outcome :seon.attempt/error]) . :where [?e :seon.attempt/task ?t] [?t :seon.task/id \"demo/m\"] [?e :seon.attempt/error]]"
        [r] (in-agent [{:op :eval :agent "root"
                        :code (str "(do (my.root/run! sys [\"demo/m\"])"
                                   " (let [a (datahike.api/q '" q " (seon.e2e/await-db sys #(datahike.api/q '" q " %)))]"
                                   " [(:seon.attempt/outcome a) (:seon.error/type (:seon.attempt/error a))"
                                   " (mapv #(get-in % [:error :seon.error/type]) (get-in (my.root/problems sys) [:classes :error :rows]))]))")}]
                      {:sets [":seon.config.ai/provider=:none"]
                       :decls [{:seon.task/name "m" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]})]
    (is (= [:error :seon.model/no-provider [:seon.model/no-provider]] (edn/read-string (:printed r))) (pr-str r))))

(deftest no-entity-of-main-is-inside-an-agents-lease
  ;; bin/check lease, nondeterministic (2026-10-22): agent a's db held id lease+50, its
  ;; ceiling, which no agent mints. Root handed out leases from a base it read once at boot,
  ;; before its own writes to main (the source import's program, later facts) went on minting
  ;; from under that base, through the agents' blocks: an agent's "new" ids are main's
  ;; entities. With eid-lease 50, root writes 150 entities after a1 holds its lease, clones a1
  ;; (the writer's :clone-branch reserves the clone's block too) and writes 150 more: no
  ;; entity of main lies in either block, and the two blocks are apart
  (let [w "@(seon.root/transact-main! sys (vec (for [i (range 150)] {:note/text (str \"root-\" i)})))"
        [r] (in-agent [{:op :eval :agent "root"
                        :code (str "(do " w
                                   " (let [c @(seon.root/clone! sys :a1)] " w
                                   " (let [db @(:conn sys) es (into #{} (map :e) (datahike.api/datoms db :eavt))"
                                   " block (fn [a] (let [ids (:ids @(seon.root/agent-stats! sys a))] [(:from ids) (+ (:from ids) (:of ids))]))"
                                   " inside (fn [[lo hi]] (count (filter #(< lo % hi) es)))"
                                   " a (block :a1) b (block (:id c))]"
                                   " [(- (second a) (first a)) (inside a) (inside b) (or (<= (second a) (first b)) (<= (second b) (first a)))])))")}]
                      {:sets [":seon.config.store/eid-lease=50"]})
        [of in-a1 in-clone apart] (edn/read-string (:printed r))]
    (is (= 50 of) (pr-str r))
    (is (= 0 in-a1) "main's entities stay out of the agent's block")
    (is (= 0 in-clone) "and out of its clone's")
    (is (true? apart) "the clone's block is not its parent's")))
