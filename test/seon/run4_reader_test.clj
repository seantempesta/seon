(ns seon.run4-reader-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(defn- run-in [ctx source time-limit-ms]
  (eval/evaluate {:seon.sci.eval/ctx ctx
                  :seon.cluster.eval/source source
                  :seon.sci.admit/caps (config/result-caps config/defaults)
                  :seon.sci.eval/time-limit-ms time-limit-ms
                  :seon.config/on-core-error :panic}))

(deftest run4-reader-errors-name-the-reply-and-do-not-imply-retained-input
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
           captured (:run4/evaluations
                     (edn/read-string (slurp "test/seon/run4_replies.edn")))
           sources (into {} (map (fn [[id source]] [id source])) captured)
           delimiter (run-in ctx (get sources "efb0cb76b2f4") 2000)
           comments (run-in ctx (get sources "9d7af271393f") 2000)]
       (is (qualified-keyword? (get-in delimiter [:seon.sci.admit/value :seon.sci.reader/unreadable-member])))
       (is (str/includes? (:seon.cluster.eval/error delimiter)
                          "Unmatched delimiter"))
       (is (str/includes? (:seon.cluster.eval/error delimiter)
                          "). ... Admitted definitions are durable"))
       (is (str/includes? (:seon.cluster.eval/error delimiter)
                          "reads each reply from scratch; nothing is buffered between turns"))
       (is (true? (get-in comments [:seon.sci.admit/value :seon.cluster.reply/no-forms])))
       (is (= "Your reply had no form; only comments/prose. Send a form."
              (:seon.cluster.eval/error comments)))
       (is (= 2 (:seon.sci.admit/value (run-in ctx "(+ 1 1)" 2000))))))))
