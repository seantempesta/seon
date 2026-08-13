(ns my.background-test
  (:require [clojure.test :refer [deftest is]]
            [my.background :as background]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(deftest background-error-renderers-cover-every-declared-class
  (doseq [[schema-key marker message]
          [[:my.background/invalid-call-error
            :my.background/invalid-call
            "The background call is invalid."]
           [:my.background/invalid-result-error
            :my.background/invalid-result
            "The background result ref is invalid."]
           [:my.background/missing-result-error
            :my.background/missing-result
            "The background result is missing."]]]
    (let [error {marker true :seon.error/message message}
          ai (background/render-ai error)
          html (background/render-html error)]
      (is (schema/valid-candidate-value? schema-key error))
      (is (= message ai))
      (is (= [:p message] html))
      (is (schema/valid-candidate-value? :seon.render/ai ai))
      (is (schema/valid-candidate-value? :seon.render/hiccup html)))))

(deftest background-macro-expands-one-direct-call
  (is (= '(seon.effect/request!
           (var my.example/call)
           {:my.example/id 1}
           {:seon.effect/background? true})
         (macroexpand-1
          '(my.background/background
            (my.example/call {:my.example/id 1})))))
  (is (= :my.background/invalid-call
         (:seon.error/kind
          (macroexpand-1 '(my.background/background (+ 1 2 3))))))
  ;; The agent's own limit is ordinary execution data on the same call: the
  ;; config fact is the default, this wins over it in either direction.
  (is (= '(seon.effect/request!
           (var my.example/call)
           {:my.example/id 1}
           (merge {:seon.effect/background? true}
                  {:seon.effect/time-limit-ms 3600000}))
         (macroexpand-1
          '(my.background/background
            {:seon.effect/time-limit-ms 3600000}
            (my.example/call {:my.example/id 1}))))))

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
      (is (= "background-agent"
             (get-in
              (db/pull @connection
                       [{:seon.effect/notify [:seon.cluster.agent/id]}]
                       [:seon.effect/id "background-result"])
              [:seon.effect/notify :seon.cluster.agent/id]))))))
