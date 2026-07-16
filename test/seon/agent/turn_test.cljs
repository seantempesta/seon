(ns seon.agent.turn-test
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.agent.turn :as turn]
   [seon.db.coordinate :as coordinate]
   [seon.execution :as execution]
   [seon.execution.host :as execution.host]))

(def point
  {::coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   ::coordinate/branch :db
   ::coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   ::coordinate/t 42})

(deftest prompt-is-the-coordinate-pinned-child-result
  (async done
    (let [original execution.host/invoke-compiled!
          observed (atom nil)]
      (set! execution.host/invoke-compiled!
            (fn [coordinate agent-id arguments]
              (reset! observed [coordinate agent-id arguments])
              (js/Promise.resolve
                {::execution/message execution/result-message
                 ::execution/coordinate coordinate
                 ::execution/result {:seon.render/text "remote prompt"}})))
      (-> (turn/render-prompt "agent-1" point)
          (.then
            (fn [prompt]
              (is (= "remote prompt" prompt))
              (is (= [point "agent-1" [{:seon.agent/id "agent-1"}]]
                     @observed))))
          (.catch
            (fn [exception]
              (is false (str "prompt invocation rejected: " exception))))
          (.finally
            (fn []
              (set! execution.host/invoke-compiled! original)
              (done)))))))

(deftest prompt-rejects-a-moved-coordinate-as-data
  (async done
    (let [original execution.host/invoke-compiled!
          moved (assoc point ::coordinate/t 43)]
      (set! execution.host/invoke-compiled!
            (fn [_ _ _]
              (js/Promise.resolve
                {::execution/message execution/result-message
                 ::execution/coordinate moved
                 ::execution/result {:seon.render/text "wrong prompt"}})))
      (-> (turn/render-prompt "agent-1" point)
          (.then
            (fn [result]
              (is (= :core-bug (:seon.error/kind result)))
              (is (= point
                     (get-in result
                             [:seon.error/data
                              :seon.db/expected-coordinate])))))
          (.catch
            (fn [exception]
              (is false (str "coordinate mismatch rejected: " exception))))
          (.finally
            (fn []
              (set! execution.host/invoke-compiled! original)
              (done)))))))
