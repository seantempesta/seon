(ns my.background-test
  (:require [clojure.test :refer [deftest is]]
            [seon.background :as background]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(deftest background-error-renderers-cover-every-declared-facet
  (doseq [[schema-key observation message]
          [[:my.background/invalid-call-error
            [:my.background/call-source "((+ 1 2 3))"]
            "The background call is invalid."]
           [:my.background/invalid-result-error
            [:my.background/result-observation "[:wrong 1]"]
            "The background result ref is invalid."]
           [:my.background/missing-result-error
            [:my.background/missing-result-ref [:seon.effect/id "missing"]]
            "The background result is missing."]]]
    (let [[member value] observation
          error {:seon.error/at #inst "2026-09-21T00:00:00.000-00:00"
                 :seon.error/layer :my.background/test
                 :seon.error/operation 'my.background-test/facet
                 :seon.error/message message
                 member value}
          ai (background/render-ai error)
          html (background/render-html error)]
      (is ((schema/projection-validator (schema/handed-projection) schema-key) error))
      (is (= message ai))
      (is (= [:p message] html))
      (is ((schema/projection-validator (schema/handed-projection) :seon.render/ai) ai))
      (is ((schema/projection-validator (schema/handed-projection) :seon.render/hiccup) html)))))

(deftest background-macro-expands-one-direct-call
  (is (= '(seon.effect/request!
           (var my.example/call)
           {:my.example/id 1}
           {:seon.effect/background? true})
         (macroexpand-1
          '(my.background/background
            (my.example/call {:my.example/id 1})))))
  (let [refusal (macroexpand-1 '(my.background/background (+ 1 2 3)))]
    (is (= "((+ 1 2 3))" (:my.background/call-source refusal)))
    (is (= 'my.background/background (:seon.error/operation refusal))))
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
      (let [written (db/transact!
                     connection
                     [{:seon.agent/id "background-agent"}
                      {:seon.turn/id "background-run"
                       :seon.turn/agent [:seon.agent/id "background-agent"]
                       :seon.turn/opened-tx "datomic.tx"}
                      {:seon.effect/id "background-result"
                       :seon.effect/run [:seon.turn/id "background-run"]
                       :seon.effect/owner [:seon.fn/sym 'my.shell/run!]
                       :seon.effect/form-ordinal 0
                       :seon.effect/ordinal 0
                       :seon.effect/request-edn "{}"
                       :seon.effect/opened-at #inst "2026-08-03T12:00:00.000-00:00"
                       :seon.effect/notify
                       [:seon.agent/id "background-agent"]}])]
        (is (:db-after written) (pr-str written)))
      (binding [db/*conn* connection]
        (is (= {:seon.effect/id "background-result"
                :seon.effect/request-edn "{}"}
               (background/poll [:seon.effect/id "background-result"])))
        (is (= {:my.turn/disposition :wait
                :my.turn/note "Use the result."
                :my.background/result
                [:seon.effect/id "background-result"]}
               (background/await
                [:seon.effect/id "background-result"]
                "Use the result."))))
      (is (= "background-agent"
             (get-in
              (db/pull @connection
                       [{:seon.effect/notify [:seon.agent/id]}]
                       [:seon.effect/id "background-result"])
              [:seon.effect/notify :seon.agent/id]))))))
