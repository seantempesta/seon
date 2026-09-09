(ns seon.help-trial-test
  (:require [clojure.test :refer [deftest is]]
            [seon.test-support :as support]))

; The committed trial is the executable subject, not a copied scoring model.
(load-file "docs/prds/context-generation/research/help_trial_2026_09_09.clj")

(def answers
  (str ";; 1. Write thinking comments before each form.\n"
       ";; 2. A value or error arrives in the next turn.\n"
       ";; 3. Use the result/e... symbol as an argument or with get-in.\n"
       ";; 4. Ask for doc or dir when unsure.\n"
       ";; 5. Query the orders to read ids, customers, and amounts.\n"
       ";; 6. Each reply is a turn; my.agent/done ends the session; 20 turns remain.\n"
       ";; 7. No. Wait until the next turn and the result has been seen.\n"))

(deftest trial-scores-actual-forms-and-fails-on-absence
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           score #((resolve 'help-trial-2026-09-09/score) @connection ctx % 20)
           query "(seon.db/q '[:find ?order :where [?order :example/order]])"
           good (score (str answers ";; I should read the orders.\n" query))]
       (is (= 12 (:seon.trial/passed good)) (pr-str good))
       (is (empty? (:seon.trial/failed good)))
       (is (= 12 (:seon.trial/passed (score (str answers query "\n;; The prompt draws => itself.\n(defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))")))))
       (is (= 12 (:seon.trial/passed (score (str answers "(doc seon.db/q)")))))
       (doseq [[reply expected]
               [["" :right-function]
                [(str answers query "\n(my.plan/complete! \"juniper/query\")") :no-premature-complete]
                [(str answers query "\n(my.plan/complete! \"juniper/query\")") :argument-shapes]
                [(str answers query "\n(my.message/send {:to \"root\" :content \"hi\"})") :argument-shapes]
                [(str answers "my.agents.juniper=> " query) :no-prompt-marker]
                [(str answers query "\n(my.plan/invented!)") :syntax]
                [(str answers "```clojure\n" query "\n```") :syntax]
                [(str answers "#=(+ 1 1)") :syntax]]]
         (is (some #{expected} (:seon.trial/failed (score reply))) (pr-str (score reply))))))))
