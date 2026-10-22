(ns seon.agent-test
  "An agent's process, run as the built binary (target/seon, bin/build seon): the root forks
  it, so the test boots a root of its own, as seon.root-test does, and asks the agent over
  the entrance what its REPL answers.

  An agent's test run, read from its child's pipe (task seon.fences/one-test-bound-derived-and-named):
  no clock ends it; the child's exit does, and a child that died names the test it was running.
  Run: bin/test seon.agent-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.agent :as agent] [seon.proofs :as proofs]))

(defn in-agent
  "A root on the checkout's src (its source root), one agent on a task of its own, each of
  forms evaluated in that agent (a string) or sent to the entrance as it is (a map): the
  first item of every reply, or the answer that holds no items, in order. The root works on a
  main of its own, a branch of the one test store (target/runs/test-store), deleted with
  every branch under it when the root has stopped (seon.check/drop-mains). sets: more
  --set config overrides for the root (e.g. its scripted model)."
  ([forms] (in-agent forms []))
  ([forms sets]
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        main (str "in-agent-" (random-uuid))
        store (str checkout "/target/runs/test-store")
        path (str checkout "/target/runs/" main)
        port (let [s (java.net.ServerSocket. 0)] (try (.getLocalPort s) (finally (.close s))))
        ask (fn [m] (str "printf '%s\\n' " (pr-str (pr-str m)) " | nc -w 60 127.0.0.1 " port))
        script (str "mkdir -p '" path "-tasks'; mkfifo '" path "-in';"
                    " echo " (pr-str (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]))
                    " > '" path "-tasks/demo.edn'; ./seon root"
                    " --set ':seon.config.source/root=" (pr-str checkout) "' --set ':seon.config.source/dirs=[\"src\"]'"
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
        {:keys [out]} (sh/sh "zsh" "-c" script :dir "target")]
    (sh/sh "./seon" "drop" main "--set" (str ":seon.config.root/store-path=" (pr-str store)) :dir "target")
    (sh/sh "rm" "-rf" (str path "-in") (str path ".log") (str path "-tasks"))
    (mapv #(let [a (edn/read-string {:default tagged-literal} %)] (if (contains? a :items) (first (:items a)) a)) (str/split-lines (str/replace out #"(?s)^.*ROOT LISTENING[^\n]*\n" ""))))))

(deftest a-relative-path-reads-from-the-source-root
  ;; task seon.agent/an-agent-works-in-its-source-root: a program file is read by the path
  ;; it has in its repository, as at any Clojure REPL started in the project
  (let [[rel abs wrote] (in-agent ["(count (slurp \"src/seon/reconcile.clj\"))"
                                   (str "(count (slurp " (pr-str (.getCanonicalPath (java.io.File. "src/seon/reconcile.clj"))) "))")
                                   "(do (spit \"src/seon/written_by_an_agent.clj\" \"(ns x)\") (slurp \"src/seon/written_by_an_agent.clj\"))"])]
    (is (not= :err (:tag rel)) "the relative path reads")
    (is (= (:printed abs) (:printed rel)) "and is the source root's file")
    ;; an agent's direct edit of the shared source root stopped root's next boot (2026-10-21)
    (is (= "\"(ns x)\"" (:printed wrote)) "the agent writes and reads back its own file")
    (is (not (.exists (java.io.File. "src/seon/written_by_an_agent.clj"))) "the source root never sees it")))

(deftest each-eval-call-is-answered-before-the-next
  ;; owner, 2026-10-22 (DECISIONS "Agents work at the REPL, through tool calls"): a reply was a
  ;; batch of forms whose results came only after the whole reply, and 18% of model turns were
  ;; repetition loops asking for answers the model could not see. Now each call of the one
  ;; tool, eval, is answered as its own tool message before the next model call: the stand-in
  ;; (seon.fixtures/two-calls) writes its second call from the answer its first call got
  (let [[end msgs] (in-agent ["(seon.turn/work! {:task \"demo/t\" :attempt \"demo/t#calls\" :max-turns 2})"
                              (str "(let [db (seon.agent/db)]"
                                   " (->> (:messages (seon.context/render db {:commit (datahike.api/commit-id db) :task \"demo/t\" :attempt \"demo/t#calls\""
                                   "                                       :turn 2 :max-turns 2 :origin :model :observed {}}))"
                                   "      (filter #(#{\"assistant\" \"tool\"} (:role %))) (mapv #(select-keys % [:role :tool-calls :tool-call-id :content]))))")]
                             [":seon.config.ai/script=seon.fixtures/two-calls"])
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
