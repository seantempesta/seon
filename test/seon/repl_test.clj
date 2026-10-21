(ns seon.repl-test
  "An agent's REPL answers: asked of an agent in a root of its own (seon.agent-test/in-agent), and a form bounded by the work it does, never by a clock (seon.intr/run under a tick budget)."
  (:require [clojure.test :refer [deftest is]] [clojure.string :as str] [seon.agent-test :as at]
            [seon.intr :as intr] [seon.agent :as agent] [seon.contract :as contract]))

(deftest source-answers-a-platform-var-from-the-program
  ;; task seon.repl/source-reads-the-program: clojure.repl's source and source-fn, as at any
  ;; Clojure REPL, answer the text of the form that defined a var the binary holds compiled
  ;; and, as Clojure's does, a library var's form read from its :file and :line (a live agent
  ;; spent all 8 turns on (source datahike.connector/connect), 2026-10-21)
  (let [[fn-item src-item lib-item] (at/in-agent ["(clojure.repl/source-fn 'seon.reconcile/stuck)"
                                                  "(do (require '[clojure.repl :refer [source]]) (source seon.reconcile/stuck))"
                                                  "(do (require 'datahike.connector) (clojure.repl/source-fn 'datahike.connector/connect))"])]
    (is (str/includes? (:printed fn-item) "(defn stuck") (:printed fn-item))
    (is (str/includes? (str (:out src-item)) "(defn stuck") (str src-item))
    (is (str/includes? (:printed lib-item) "(defn connect") (:printed lib-item))))

(deftest an-agent-repl-answers-what-a-clojure-repl-answers
  ;; owner, 2026-10-21: "Whatever intuitions agents have we need to make sure we are matching a
  ;; real clojure repl as much as possible AND improving on it". Each row is what a Clojure
  ;; user types at clojure.main's REPL (clojure.main/repl-requires refers source, apropos,
  ;; dir, pst, doc, find-doc, pp, pprint) and what it answers, in an agent's own namespace;
  ;; the improvements: source reads the agent's own redefinitions and Seon's program.
  (let [rows [["(source seon.reconcile/stuck)" :out "(defn stuck"]
              ["(do (require 'datahike.connector) (source datahike.connector/connect))" :out "(defn connect"]
              ["(defn twice [x] (* 2 x))" :printed "twice"]
              ["(source twice)" :out "(defn twice [x] (* 2 x))"]
              ["(doc twice)" :out "twice"]
              ["(dir clojure.string)" :out "join"]
              ["(apropos \"index-of\")" :printed "index-of"]
              ["(+ 1 2)" :printed "3"]
              ["*1" :printed "3"]
              ["(pprint {:a 1})" :out "{:a 1}"]
              ["(/ 1 0)" :printed ""]
              ["(some? *e)" :printed "true"]
              ["(pst)" :out "Divide by zero"]
              ["(in-ns 'seon.reconcile)" :printed "seon.reconcile"]
              ["(clojure.repl/source stuck)" :out "(defn stuck"]]
        items (at/in-agent (mapv first rows))]
    (doseq [[[form k expected] item] (map vector rows items)]
      (is (str/includes? (str (get item k)) expected) (str form " -> " (pr-str item))))))

(deftest an-edited-file-reloaded-is-the-branchs-program
  ;; task seon.repl/a-reloaded-file-is-the-branchs-change: the Clojure workflow (edit the file,
  ;; require it :reload, or load-file it) changes the agent's branch exactly as evaluating the
  ;; same forms at its REPL does; the file is the agent's own copy, in its directory
  (let [edit (fn [body] (str "(spit \"src/my/turn.clj\" (clojure.string/replace (slurp \"src/my/turn.clj\")"
                             " #\"\\[\\] \\(turn/last-evals\\)\\)|\\[\\] \\[\\{:edited \\d\\}\\]\\)\" " (pr-str (str "[] " body ")")) "))"))
        recorded "(seon.context/source-of (seon.agent/db) 'my.turn/evals)"
        [_ req ran rec src _ lf ran2 rec2 nsk _ bad nsk2]
        (at/in-agent [(edit "[{:edited 1}]")
                      "(require 'my.turn :reload)"
                      "(my.turn/evals)"
                      recorded
                      "(with-out-str (clojure.repl/source my.turn/evals))"
                      (edit "[{:edited 2}]")
                      "(load-file \"src/my/turn.clj\")"
                      "(my.turn/evals)"
                      recorded
                      "*ns*"
                      "(spit \"src/my/turn.clj\" (str (slurp \"src/my/turn.clj\") \"\\n(defn broken [] (no-such-fn))\\n\"))"
                      "(require 'my.turn :reload)"
                      "*ns*"])]
    (is (= "nil" (:printed req)) "require :reload answers nil, as Clojure's does")
    (is (= "[{:edited 1}]" (:printed ran)) "(a) the edited definition runs")
    (is (str/includes? (str (:printed rec)) "[] [{:edited 1}]") (str "(b) the branch records the edited text: " (:printed rec)))
    (is (str/includes? (str (:printed src)) "[{:edited 1}]") (str "and clojure.repl/source shows it: " (:printed src)))
    (is (not= :err (:tag lf)) (str "(c) load-file of the same file loads: " (pr-str lf)))
    (is (= "[{:edited 2}]" (:printed ran2)) "the load-file's definition runs")
    (is (str/includes? (str (:printed rec2)) "[] [{:edited 2}]") "and is the branch's")
    (is (str/includes? (str (:printed nsk)) "agent.") "the agent's namespace is restored after both loads")
    (is (= :err (:tag bad)) "a form of the file that fails to compile fails the require, as in Clojure")
    (is (str/includes? (str (:printed bad)) "no-such-fn") (str "named by its cause: " (pr-str bad)))
    (is (str/includes? (str (:printed nsk2)) "agent.") "and the agent's namespace is restored")))

(defn- outcome [ticks thunk]
  (try {:value (intr/run ticks thunk)} (catch Throwable e {:error e})))

(deftest an-endless-form-trips-its-ticks-and-is-named
  (let [{e :error} (outcome 100000000 #(loop [] (recur)))
        m (agent/error-map e)]
    (is (= :seon.repl/ticks-spent (:seon.error/type m)) "a loop that allocates nothing stops, as the typed error")
    (is (<= 100000000 (:ticks m) 100100000) "it ran its budget, and at most one poll past it")
    (is (= 100000000 (:budget m)))
    (is (contract/valid? :seon.repl/ticks-spent m) "the error is the declared variant, data for the agent"))
  (let [{e :error} (outcome 100000000 #(try (loop [] (recur)) (catch Throwable _ :swallowed)))]
    (is (= :seon.repl/ticks-spent (:seon.error/type (agent/error-map e))) "the form's own catch cannot swallow it"))
  (is (= {:value 5050} (outcome 100000000 #(reduce + (range 101)))) "a form under its budget answers its value")
  (is (= {:value 3000000} (outcome nil #(loop [i 0] (if (= i 3000000) i (recur (inc i)))))) "no budget: unbounded"))
