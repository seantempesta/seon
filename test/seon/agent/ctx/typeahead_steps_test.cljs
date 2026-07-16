(ns seon.agent.ctx.typeahead-steps-test
  "Coordinate-pinned typeahead prompt and surface acquisition."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent.ctx.typeahead-steps :as steps]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(def coordinate
  {:seon.db.coordinate/database-id "db"
   :seon.db.coordinate/branch "main"
   :seon.db.coordinate/commit-id "commit"
   :seon.db.coordinate/t 7})

(defn- member [result]
  {::protocol/success? true ::protocol/result result})

(deftest prompt-provider-is-acquired-at-the-invocation-coordinate
  (async done
    (let [original db/execute-many]
      (set! db/execute-many
            (fn [request]
              (is (= coordinate (::db/coordinate request)))
              (js/Promise.resolve
                {::db/coordinate coordinate
                 ::db/results
                 [(member {:seon.ai/agent-provider (pr-str :typeahead)})
                  (member {:seon.ai/provider :deepseek})]})))
      (-> (steps/steps-ai {:seon.agent/id "agent"
                           ::db/coordinate coordinate}
                          nil)
          (.then (fn [text]
                   (is (str/includes? text "typeahead step loop"))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (set! db/execute-many original) (done)))))))

(deftest empty-step-surface-vanishes-after-remote-acquisition
  (async done
    (let [original db/execute-many]
      (set! db/execute-many
            (fn [request]
              (is (= coordinate (::db/coordinate request)))
              (js/Promise.resolve
                {::db/coordinate coordinate
                 ::db/results
                 [(member {})
                  (member {:seon.ai/provider :typeahead})
                  (member [])]})))
      (-> (steps/steps-surface-html
            {:seon.agent/id "agent" ::db/coordinate coordinate}
            nil)
          (.then (fn [hiccup] (is (nil? hiccup))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (set! db/execute-many original) (done)))))))

(deftest absent-coordinate-fails-closed
  (async done
    (let [original db/current-tx-context]
      (set! db/current-tx-context (constantly nil))
      (-> (steps/steps-surface-html {:seon.agent/id "agent"} nil)
          (.then (fn [hiccup]
                   (is (str/includes? (pr-str hiccup)
                                      "Typeahead surface acquisition failed"))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (set! db/current-tx-context original) (done)))))))
