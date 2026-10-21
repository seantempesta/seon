(ns seon.repl-test
  "An agent's REPL answers, asked of an agent in a root of its own (seon.agent-test/in-agent)."
  (:require [clojure.test :refer [deftest is]] [seon.agent-test :as at] [clojure.string :as str]))

(deftest source-answers-a-platform-var-from-the-program
  ;; task seon.repl/source-reads-the-program: clojure.repl's source and source-fn, as at any
  ;; Clojure REPL, answer the text of the form that defined a var the binary holds compiled
  (let [[fn-item src-item] (at/in-agent ["(clojure.repl/source-fn 'seon.reconcile/stuck)"
                                         "(do (require '[clojure.repl :refer [source]]) (source seon.reconcile/stuck))"])]
    (is (str/includes? (:printed fn-item) "(defn stuck") (:printed fn-item))
    (is (str/includes? (str (:out src-item)) "(defn stuck") (str src-item))))
