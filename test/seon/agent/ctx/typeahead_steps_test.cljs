(ns seon.agent.ctx.typeahead-steps-test
  "Database-value-pinned typeahead prompt and surface acquisition."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent.ctx.typeahead-steps :as steps]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(def database {:datahike/commit-id "commit" :max-tx 7})

(defn- member [result]
  {::protocol/success? true ::protocol/result result})

(deftest prompt-provider-is-acquired-from-the-invocation-database-value
  (async done
    (let [original db/execute-many]
      (set! db/execute-many
            (fn [request]
              (is (identical? database (::db/db request)))
              (js/Promise.resolve
                {::db/results
                 [(member {:seon.ai/agent-provider (pr-str :typeahead)})
                  (member {:seon.ai/provider :deepseek})]})))
      (-> (steps/steps-ai {:seon.agent/id "agent" ::db/db database}
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
              (is (identical? database (::db/db request)))
              (js/Promise.resolve
                {::db/results
                 [(member {})
                  (member {:seon.ai/provider :typeahead})
                  (member [])]})))
      (-> (steps/steps-surface-html
            {:seon.agent/id "agent" ::db/db database}
            nil)
          (.then (fn [hiccup] (is (nil? hiccup))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (set! db/execute-many original) (done)))))))
