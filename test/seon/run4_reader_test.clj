(ns seon.run4-reader-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.test-support :as test-support]))

(defn- run-in [ctx source time-limit-ms]
  (test-support/evaluate {:seon.sci.eval/ctx ctx
                          :seon.cluster.eval/source source
                          :seon.sci.admit/caps (config/result-caps config/defaults)
                          :seon.sci.eval/time-limit-ms time-limit-ms}))

(deftest run4-reader-errors-name-the-reply-and-do-not-imply-retained-input
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
           captured (:run4/evaluations
                     (edn/read-string (slurp "test/seon/run4_replies.edn")))
           sources (into {} (map (fn [[id source]] [id source])) captured)
           delimiter (run-in ctx (get sources "efb0cb76b2f4") 2000)
           comments (run-in ctx (get sources "9d7af271393f") 2000)]
       ;; The one path reads each reply from scratch: an unreadable reply is
       ;; unread from where reading stopped, and nothing evaluates or is kept.
       (is (= 0 (get-in delimiter [:seon.batch/unread :seon.batch/unread-from]))
           "reading stops at the reply's first unreadable span")
       (is (str/includes? (get-in delimiter [:seon.batch/unread :seon.batch/reason]) "Unmatched delimiter")
           "the reason names the reader's delimiter failure")
       (is (not (contains? delimiter :seon.sci.admit/value)) "an unread reply evaluates nothing")
       (is (not (contains? comments :seon.sci.admit/value)) "a reply of prose alone evaluates nothing")
       (is (= 2 (:seon.sci.admit/value (run-in ctx "(+ 1 1)" 2000))))))))
