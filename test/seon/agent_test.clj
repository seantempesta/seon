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
  forms evaluated in that agent: the items of every reply, in order. Its store is removed."
  [forms]
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        path (str checkout "/target/runs/in-agent-" (random-uuid))
        port (let [s (java.net.ServerSocket. 0)] (try (.getLocalPort s) (finally (.close s))))
        ask (fn [m] (str "printf '%s\\n' " (pr-str (pr-str m)) " | nc -w 60 127.0.0.1 " port))
        script (str "mkdir -p '" path "-tasks'; mkfifo '" path "-in';"
                    " echo " (pr-str (pr-str [{:seon.task/name "t" :seon.task/plan "Work." :seon.task/kind :work :seon.task/proofs '#{demo-test/t}}]))
                    " > '" path "-tasks/demo.edn'; ./seon root"
                    " --set ':seon.config.source/root=" (pr-str checkout) "' --set ':seon.config.source/dirs=[\"src\"]'"
                    " --set ':seon.config.root/store-path=" (pr-str path) "' --set ':seon.config.root/entrance-port=" port "' --set ':seon.config.web/port=0'"
                    " < '" path "-in' > '" path ".log' 2>&1 & R=$!; exec 3>'" path "-in';"
                    " until grep -q 'ROOT LISTENING' '" path ".log' || ! kill -0 $R 2>/dev/null; do sleep 0.1; done;"
                    (ask {:op :eval :agent "root" :code (str "(my.root/load-tasks! sys " (pr-str (str path "-tasks")) ")")}) " >/dev/null;"
                    (ask {:op :attach :task "demo/t"}) " >/dev/null;"
                    " for i in {1..300}; do " (ask {:op :agents}) " | grep -q idle && break; sleep 0.2; done;"
                    (str/join (for [f forms] (str (ask {:op :eval :agent "a1" :code f}) ";")))
                    " exec 3>&-; wait $R")
        {:keys [out]} (sh/sh "zsh" "-c" script :dir "target")]
    (sh/sh "rm" "-rf" path (str path "-in") (str path ".log") (str path "-tasks") (str path "-agents"))
    (mapv #(first (:items (edn/read-string {:default tagged-literal} %))) (str/split-lines (str/replace out #"(?s)^.*ROOT LISTENING[^\n]*\n" "")))))

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
