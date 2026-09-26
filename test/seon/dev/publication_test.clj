(ns seon.dev.publication-test
  (:require [clojure.test :refer [deftest is]]
            [seon.operator :as operator]))

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
