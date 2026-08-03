(ns my.background-test
  (:require [clojure.test :refer [deftest is]]
            [my.background :as background]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(deftest background-macro-expands-one-direct-call
  (is (= '(seon.effect/request!
           (var my.example/call)
           {:my.example/id 1}
           {:seon.effect/background? true})
         (macroexpand-1
          '(background/background
            (my.example/call {:my.example/id 1})))))
  (is (= :my.background/invalid-call
         (:seon.error/kind
          (macroexpand-1 '(background/background (+ 1 2 3)))))))

(deftest poll-and-await-derive-terminal-presence-without-acknowledging
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.agent/id "background-agent"}
        {:seon.cluster.run/id "background-run"}
        {:seon.fn/sym "my.example/call"}
        {:seon.effect/id "background-result"
         :seon.effect/run [:seon.cluster.run/id "background-run"]
         :seon.effect/owner [:seon.fn/sym "my.example/call"]
         :seon.effect/form-ordinal 0
         :seon.effect/ordinal 0
         :seon.effect/request-edn "{}"
         :seon.effect/opened-at #inst "2026-08-03T12:00:00.000-00:00"
         :seon.effect/notify
         [:seon.cluster.agent/id "background-agent"]}])
      (binding [db/*conn* connection]
        (is (= {:seon.effect/id "background-result"
                :seon.effect/request-edn "{}"}
               (background/poll [:seon.effect/id "background-result"])))
        (is (= {:my.run/disposition :wait
                :my.run/note "Use the result."
                :my.background/result
                [:seon.effect/id "background-result"]}
               (background/await
                [:seon.effect/id "background-result"]
                "Use the result."))))
      (is (= [:seon.cluster.agent/id "background-agent"]
             (:seon.effect/notify
              (db/pull @connection '[*]
                       [:seon.effect/id "background-result"])))))))
