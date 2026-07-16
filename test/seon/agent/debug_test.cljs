(ns seon.agent.debug-test
  "Point-in-time compiled-child contract for agent context preview."
  (:require
    [cljs.test :refer [async deftest is]]
    [clojure.string :as str]
    [seon.agent.debug :as debug]
    [seon.agent.turn :as turn]
    [seon.db.coordinate :as coordinate]))

(def point
  {::coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   ::coordinate/branch :db
   ::coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   ::coordinate/t 42})

(deftest preview-keeps-system-and-context-on-one-database-point
  (async done
    (let [original turn/render-prompt
          calls (atom [])]
      (set! turn/render-prompt
            (fn [agent-id coordinate]
              (swap! calls conj [agent-id coordinate])
              (js/Promise.resolve
                {:seon.render/text "context bytes"
                 :seon.ai/system-prompt "frozen system"
                 :seon.agent.ctx/rendered-blocks
                 [{:seon.agent.ctx/name :context
                   :seon.agent.ctx/priority 10
                   :seon.render/text "context bytes"}]})))
      (-> (debug/ctx-preview
            {:seon.agent/id "agent-1"
             :seon.db.coordinate/coordinate point})
          (.then
            (fn [preview]
              (let [system-block
                    (first (:seon.agent.ctx/rendered-blocks preview))]
                (is (= [["agent-1" point]] @calls))
                (is (= "frozen system" (:seon.render/text system-block)))
                (is (str/starts-with? (:seon.render/text preview)
                                      "frozen system"))
                (is (str/ends-with? (:seon.render/text preview)
                                    "context bytes")))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! turn/render-prompt original)
              (done)))))))

(deftest preview-preserves-the-compiled-child-error
  (async done
    (let [original turn/render-prompt
          child-error {:seon.error/message "authority unavailable"
                       :seon.error/kind :core-bug
                       :seon.error/data {:seon.db/results []}}]
      (set! turn/render-prompt
            (fn [_ _] (js/Promise.resolve child-error)))
      (-> (debug/ctx-preview
            {:seon.agent/id "agent-1"
             :seon.db.coordinate/coordinate point})
          (.then (fn [result] (is (= child-error result))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! turn/render-prompt original)
              (done)))))))
