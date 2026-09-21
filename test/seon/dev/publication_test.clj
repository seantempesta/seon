(ns seon.dev.publication-test
  (:require [clojure.test :refer [deftest is]]
            [seon.operator :as operator]
            [seon.cluster.process :as process]))

(deftest publication-forwards-output-and-returns-terminal-value
  (let [observed (atom [])
        endpoint {:seon.boot/prepl-host "127.0.0.1" :seon.boot/prepl-port 1}
        result {:seon.source/commit-id (random-uuid)}]
    (with-redefs [operator/advertisement (fn [_ _] endpoint)
                  operator/prepl-value!
                  (fn [target form bound observe!]
                    (is (= endpoint target))
                    (is (= "(+ 1 1)" form))
                    (is (pos-int? bound))
                    (observe! "source phase\n")
                    result)]
      (is (= {:seon.operator/live-process? true :seon.operator/value result}
             (operator/live-root-value! "." "(+ 1 1)"
                                        {:seon.operator/observe-output! #(swap! observed conj %)}))))
    (is (= ["source phase\n"] @observed))))

(deftest ordinary-output-cannot-hide-a-silent-publication
  (let [failure (try
                  (process/run-process!
                   {:seon.operator.subprocess/argv
                    ["/bin/bash" "-c" "while true; do printf 'ordinary output\\n'; sleep 0.05; done"]
                    :seon.operator.subprocess/deadline-ms 250
                    :seon.operator.subprocess/event-silence-ms 250
                    :seon.operator.subprocess/progress (atom "analysis")
                    :seon.operator.subprocess/merge-error? true})
                  (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
    (is (= :process-exit (:seon.operator.subprocess/phase failure)))
    (is (true? (:seon.operator.subprocess/reaped? failure)))))
