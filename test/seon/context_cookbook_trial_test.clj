(ns seon.context-cookbook-trial-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.test-support :as support]))

(deftest a-provider-refusal-is-not-a-comprehension-score
  (load-file "docs/prds/context-generation/research/help_trial_2026_09_09.clj")
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           assess (resolve 'help-trial-2026-09-09/assessment)
           directory "docs/prds/context-generation/research/"
           refused (edn/read-string (slurp (str directory "context_cookbook_trial_2026_09_09.edn")))
           completed (edn/read-string (slurp (str directory "help_trial_2026_09_09.edn")))
           result (assess @connection ctx (:seon.trial/completion refused)
                          (:seon.trial/turns-left refused))
           measured (assess @connection ctx (:seon.trial/completion completed)
                            (:seon.trial/turns-left completed))]
       (is (true? (get-in refused [:seon.trial/completion :seon.error/data
                                  :seon.ai/request-transmitted?])))
       (is (= :unavailable (:seon.trial/score-status result)))
       (is (not (contains? result :seon.trial/score)))
       (is (= :measured (:seon.trial/score-status measured)))
       (is (= (get-in completed [:seon.trial/score :seon.trial/passed])
              (get-in measured [:seon.trial/score :seon.trial/passed])))))))
