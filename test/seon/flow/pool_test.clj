(ns seon.flow.pool-test
  "Integration tests for the pre-warmed JVM pool.

   These tests spawn real JVM processes and require ~15s for pool creation.
   Tagged as :integration to skip in fast test runs."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.flow.pool :as pool]))

(deftest ^:integration pool-lifecycle-test
  (testing "Create pool, acquire, eval, release, shutdown"
    (let [p (pool/create-pool! {::pool/size 2 ::pool/base-port 7910})]
      (try
        ;; Pool should have 2 idle JVMs
        (let [status (pool/pool-status p)]
          (is (= 2 (::pool/total status)))
          (is (= 2 (::pool/idle status)))
          (is (= 0 (::pool/active status))))

        ;; Acquire one JVM
        (let [agent (pool/acquire! p {::pool/namespace 'seon.test.pooled
                                      ::pool/forms ['(defn greet [n]
                                                       (str "Hello " n))]})]
          (is (some? agent))
          (is (< (::pool/setup-ms agent) 200)
              "Warm assignment should be under 200ms")

          ;; Pool status should reflect
          (let [status (pool/pool-status p)]
            (is (= 1 (::pool/idle status)))
            (is (= 1 (::pool/active status))))

          ;; Release back
          (pool/release! p agent)
          (let [status (pool/pool-status p)]
            (is (= 2 (::pool/idle status)))))

        ;; Pool exhaustion returns nil
        (let [a1 (pool/acquire! p {::pool/namespace 'seon.test.a})
              a2 (pool/acquire! p {::pool/namespace 'seon.test.b})
              a3 (pool/acquire! p {::pool/namespace 'seon.test.c})]
          (is (some? a1))
          (is (some? a2))
          (is (nil? a3) "Third acquire should return nil (pool exhausted)")
          (pool/release! p a1)
          (pool/release! p a2))

        (finally
          (pool/shutdown! p))))))
