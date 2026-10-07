(ns seon.run4-reader-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.test-support :as test-support]
            [seon.turn :as turn]))

(deftest run4-replies-of-prose-and-stray-closers-run-nothing
  ;; Two captured replies: one opening on a stray `)` followed by prose, one of
  ;; comments alone. Read by the reply line rules, neither line starts a form,
  ;; so the reply is the no-forms refusal and nothing evaluates.
  (let [sources (into {} (map (fn [[id source]] [id source]))
                      (:run4/evaluations (edn/read-string (slurp "test/seon/run4_replies.edn"))))]
    (doseq [id ["efb0cb76b2f4" "9d7af271393f"]]
      (is (:seon.cluster.reply/no-forms (turn/planned-sources (get sources id) 'user 100000))
          "a reply with no line that starts a form is the no-forms refusal"))
    (test-support/with-database
     (fn [connection]
       (is (= 2 (:seon.sci.admit/value
                 (test-support/evaluate {:seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)
                                         :seon.cluster.eval/source "(+ 1 1)"
                                         :seon.sci.admit/caps (config/result-caps config/defaults)
                                         :seon.sci.eval/time-limit-ms 2000}))))))))
