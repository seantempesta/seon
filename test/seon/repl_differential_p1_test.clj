(ns seon.repl-differential-p1-test
  "P1 rows: SCI's own `remove-ns`, `ns-unalias` and `alter-var-root` run, and the
  program keeps the final state they leave."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.repl-differential-test :as differential]
            [seon.test-support :as support]))

(def rows
  "`[label [call-code ...]]`: the last call's value equals plain SCI's running
  all the calls' code in order. Each call is one separate MCP request."
  [["remove-ns" ["(create-ns 'rma) (remove-ns 'rma) (find-ns 'rma)"]]
   ["ns-unalias" ["(alias 'cc 'clojure.core) (ns-unalias *ns* 'cc) (get (ns-aliases *ns*) 'cc)"]]
   ["alter-var-root of a committed var" ["(defn pf [x] x)" "(alter-var-root #'pf (constantly inc))" "(pf 1)"]]
   ["intern of a committed var" ["(defn pi [x] x)" "(intern *ns* 'pi (fn [x] (inc x)))" "(pi 1)"]]
   ["ns-unmap of a committed var" ["(defn pu [] 1)" "(ns-unmap *ns* 'pu)" "(resolve 'pu)"]]])

(deftest native-program-operations-run-as-plain-sci
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label calls] rows]
       (testing label
         (is (= (#'differential/plain (apply str (interpose "\n" calls)))
                (last (mapv #(#'differential/answered % agent-id running) calls)))))))))

(deftest removing-a-namespace-stores-the-final-state
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [call #(#'differential/answered % agent-id running)
           stored? (fn [] (some? (db/pull (db/db connection) [:db/id] [:seon.fn/sym (symbol "rmp1.gone" "f")])))]
       (call "(ns rmp1.gone) (defn f [] 1)")
       (is (true? (stored?)) "the definition is stored before the removal")
       (is (nil? (call "(in-ns 'user) (remove-ns 'rmp1.gone) (find-ns 'rmp1.gone)")))
       (is (false? (stored?)) "the removal leaves no stored definition")))))


(deftest a-surviving-referrer-does-not-change-the-value
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call #(#'differential/answered % agent-id running)]
       (call "(ns rmp5.callee) (defn g [] 1)")
       (call "(ns rmp5.caller (:require [rmp5.callee :as c])) (defn h [] (c/g))")
       (is (nil? (call "(in-ns 'user) (remove-ns 'rmp5.callee) (find-ns 'rmp5.callee)")))))))

(deftest a-caller-redefined-then-its-helper-unmapped-in-one-request-stores-both
  ;; Live run 11 (docs/research/agent-platform/2026-10-18/live-run-11.md): chain-cd8a339333ef redefined
  ;; seon.error/nearest-declared off seon.error/candidate-names and unmapped candidate-names in one request, as help
  ;; teaches (callers first); the removal judged referrers from before the request, named the old caller, and the
  ;; request's whole settlement was refused, its three redefinitions with it (they were redone thirteen calls later).
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [call #(#'differential/answered % agent-id running)
           stored (fn [s] (:seon.fn/source (db/pull (db/db connection) [:seon.fn/source] [:seon.fn/sym s])))]
       (call "(ns rmp6.core) (defn helper [x] (inc x)) (defn caller [x] (helper x))")
       (call "(defn caller [x] (+ x 2)) (my.program/ns-unmap! 'rmp6.core/helper)")
       (is (nil? (stored 'rmp6.core/helper)) "the helper no caller names any more is removed")
       (is (= "(defn caller [x] (+ x 2))" (stored 'rmp6.core/caller)) "the caller's redefinition is stored")))))
