(ns seon.render-core-script
  "Throwaway (render-core): a scripted session of a real task, for the evidence of the render base. Not for merge."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]]))

(def turns
  [{:calls [{:prose "Read the function the plan names." :code "(clojure.repl/source seon.program/live-forms)"}
            {:prose "How the var's form is shaped." :code "(d/pull (my.agent/db) '[*] [:var/name 'seon.program/live-forms])"}]}
   {:calls [{:prose "Write the proof red and register it."
             :code "(in-ns 'seon.program-test)\n(clojure.test/deftest a-vars-history-names-each-definition-it-had (clojure.test/is (= [] (seon.program/history 'seon.program/live-forms))))\n(my.task/update! :seon.task/proofs conj 'seon.program-test/a-vars-history-names-each-definition-it-had)"}
            {:prose "Run it red." :code "(my.agent/prove)"}]}
   {:calls [{:prose "What changed on my page since I last asked." :code "(my.render/render-changes)"}]}
   {:calls [{:prose "A first history." :code "(in-ns 'seon.program)\n(defn history \"A var's past definitions on its branch.\" {:malli/schema [:=> [:cat :qualified-symbol] [:vector :map]]} [sym] [])"}
            {:prose "Run it." :code "(my.agent/prove)"}]}
   {:calls [{:prose "Wait for a message." :code "(my.turn/wait)"}]}])

(defn run
  "The scripted model of the render-core session."
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [{:keys [turn]}]
  (nth turns (min turn (dec (count turns)))))
