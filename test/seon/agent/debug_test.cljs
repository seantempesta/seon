(ns seon.agent.debug-test
  "Point-in-time compiled-child contract for agent context preview."
  (:require
    [cljs.test :refer [async deftest is]]
    [clojure.string :as str]
    [seon.agent.debug :as debug]
    [seon.agent.turn :as turn]
    [seon.db :as db]))

(def database
  {:db-name "debug-test"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(deftest preview-keeps-system-and-context-on-one-database-value
  (async done
    (let [original turn/render-prompt
          calls (atom [])]
      (set! turn/render-prompt
            (fn
              ([agent-id database]
               (swap! calls conj [agent-id database])
               (js/Promise.resolve
                {:seon.render/text "context bytes"
                 :seon.ai/system-prompt "frozen system"
                 :seon.agent.ctx/rendered-blocks
                 [{:seon.agent.ctx/name :context
                   :seon.agent.ctx/priority 10
                   :seon.render/text "context bytes"}]}))
              ([agent-id database _profile]
               (turn/render-prompt agent-id database))))
      (-> (debug/ctx-preview
            {:seon.agent/id "agent-1"
             :seon.db/db database})
          (.then
            (fn [preview]
              (let [system-block
                    (first (:seon.agent.ctx/rendered-blocks preview))]
                (is (= [["agent-1" database]] @calls))
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
            (fn
              ([_ _] (js/Promise.resolve child-error))
              ([_ _ _] (js/Promise.resolve child-error))))
      (-> (debug/ctx-preview
            {:seon.agent/id "agent-1"
             :seon.db/db database})
          (.then (fn [result] (is (= child-error result))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! turn/render-prompt original)
              (done)))))))

(deftest turn-reconstruction-uses-one-ordinary-database-value
  (async done
    (let [original-query db/query
          original-pull db/pull
          observed (atom [])]
      (set! db/query
            (fn
              ([request]
               (swap! observed conj [:query request])
               (js/Promise.resolve 101))
              ([_query & _inputs] (js/Promise.resolve 101))))
      (set! db/pull
            (fn
              ([request]
               (swap! observed conj [:pull request])
               (js/Promise.resolve
                {:seon.agent.turn/id "turn-1"
                 :seon.agent.turn/status :done
                 :seon.agent.turn/rendered-tx {:db/id 40}}))
              ([_selector _eid] (js/Promise.resolve nil))
              ([_database _selector _eid] (js/Promise.resolve nil))))
      (-> (debug/turn {:seon.agent.turn/id "turn-1"
                       :seon.db/db database})
          (.then
           (fn [result]
             (is (true? (:seon.agent.debug/ok? result)))
             (is (= 40 (:seon.agent.turn/rendered-tx result)))
             (is (= database
                    (get-in @observed [0 1 :seon.db/db])))
             (is (= database
                    (get-in @observed [1 1 :seon.db/db])))
             (is (= 101 (get-in @observed [1 1 :seon.db/ref])))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/query original-query)
             (set! db/pull original-pull)
             (done)))))))

(deftest turn-reconstruction-preserves-a-database-read-error
  (async done
    (let [original-query db/query
          original-pull db/pull
          pulled? (atom false)
          read-error {:seon.error/message "Database session has no request capacity."
                      :seon.error/kind :core-bug}]
      (set! db/query (fn [& _] (js/Promise.resolve read-error)))
      (set! db/pull
            (fn [& _]
              (reset! pulled? true)
              (js/Promise.resolve nil)))
      (-> (debug/turn {:seon.agent.turn/id "turn-1"
                       :seon.db/db database})
          (.then
           (fn [result]
             (is (= {:seon.agent.debug/ok? false
                     :seon.agent.turn/id "turn-1"
                     :seon.agent.debug/error
                     "Database session has no request capacity."}
                    result))
             (is (false? @pulled?)
                 "an error value is not a Datahike entity id")))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/query original-query)
             (set! db/pull original-pull)
             (done)))))))

(deftest turn-diff-compares-basis-transactions-directly
  (async done
    (let [original debug/turn]
      (set! debug/turn
            (fn [{turn-id :seon.agent.turn/id}]
              (js/Promise.resolve
               {:seon.agent.debug/ok? true
                :seon.agent.turn/id turn-id
                :seon.agent.turn/rendered-tx
                (if (= turn-id "turn-1") 40 44)
                :seon.agent.debug/prompt
                (if (= turn-id "turn-1") "alpha" "alpha\nbeta")})))
      (-> (debug/turn-diff
           {:seon.agent.debug/from "turn-1"
            :seon.agent.debug/to "turn-2"
            :seon.db/db database})
          (.then
           (fn [result]
             (is (true? (:seon.agent.debug/ok? result)))
             (is (= 4 (:seon.agent.debug/basis-t-delta result)))
             (is (= 1 (:seon.agent.debug/prompt-lines-added result)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! debug/turn original)
             (done)))))))

(deftest error-triage-uses-the-same-database-value-and-transaction-id
  (async done
    (let [original-query db/query
          original-pull db/pull
          error-row
          {:seon.error/fault :core
           :seon.error/message "core failure"
           :seon.error/basis-t 41
           :seon.error/args-edn "[{:probe/value 1}]"
           :seon.error/data-edn
           "{:seon.error.malli/fn-sym probe/run}"
           :seon.error/frames
           [{:seon.error.frame/index 0
             :seon.error.frame/fn "probe/run"
             :seon.error.frame/file "probe.cljs"
             :seon.error.frame/line 7}]}
          observed (atom [])]
      (set! db/query
            (fn
              ([request]
               (swap! observed conj [:query request])
               (js/Promise.resolve
                (case (count (:seon.db/args request))
                  0 [[200 :core]]
                  1 "agent-1"
                  2 [[301 "turn-1" 40]])))
              ([_query & _inputs] (js/Promise.resolve nil))))
      (set! db/pull
            (fn
              ([request]
               (swap! observed conj [:pull request])
               (js/Promise.resolve error-row))
              ([_selector _eid] (js/Promise.resolve error-row))
              ([_database _selector _eid] (js/Promise.resolve error-row))))
      (-> (debug/errors {:seon.db/db database})
          (.then
           (fn [result]
             (is (= [200] (mapv :seon.agent.debug/eid
                                 (:seon.agent.debug/errors result))))
             (debug/error {:seon.agent.debug/eid 200
                           :seon.db/db database})))
          (.then
           (fn [result]
             (is (true? (:seon.agent.debug/ok? result)))
             (is (= "turn-1" (:seon.agent.turn/id result)))
             (debug/repro {:seon.agent.debug/eid 200
                           :seon.db/db database})))
          (.then
           (fn [result]
             (is (true? (:seon.agent.debug/ok? result)))
             (is (= 41 (get-in result [:seon.db/db :as-of])))
             (is (= 'probe/run (:seon.agent.debug/fn-sym result)))
             (is (str/includes? (:seon.agent.debug/repro-expr result)
                                "seon.db/as-of database 41"))
             (is (every? #(= database (get-in % [1 :seon.db/db]))
                         @observed))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/query original-query)
             (set! db/pull original-pull)
             (done)))))))
