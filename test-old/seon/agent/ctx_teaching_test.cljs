(ns seon.agent.ctx-teaching-test
  "Tier-derived system teaching at the compiled prompt boundary."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as str]
   [my.plan.internal :as plan.internal]
   [seon.agent.ctx :as ctx]
   [seon.agent.ctx.driver :as ctx.driver]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   ))

(defn- rendered-system-text []
  (ctx/render-system-text ctx/system-text-shared))

(deftest system-teaching-states-the-jvm-claimant-contract
  (let [host (rendered-system-text)]
    (testing "agent code runs behind the JVM host door"
      (is (str/includes? host "platform contract: JVM claimant"))
      (is (str/includes? host "java.util.Date"))
      (is (str/includes? host "synchronous"))
      (is (not (str/includes? host "NO JVM"))))
    (is (str/includes? host "GENERATE CODE"))
    (is (str/includes? host "LAST VERSION WINS"))))

(deftest configured-shared-body-uses-the-same-platform-renderer
  (let [shared "; configured shared teaching"
        host (ctx/render-system-text shared)]
    (is (str/includes? host shared))
    (is (str/includes? host "platform contract: JVM claimant"))))

(deftest development-teaching-is-platform-neutral
  (is (not (str/includes? plan.internal/development-teaching
                          "ClojureScript")))
  (is (not (str/includes? plan.internal/development-teaching
                          "Promises")))
  (is (str/includes? plan.internal/development-teaching
                     "LAST VERSION WINS")))

(deftest prompt-render-uses-the-jvm-contract-without-a-tier-query
  (async done
    (let [original-execute-many db/execute-many
          requests (atom [])
          database {:db-name "default" :t 42 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}
          render! (fn []
                    (ctx.driver/render-prompt!
                     {:seon.agent/id "teaching-agent" ::db/db database}
                     (fn [_] (js/Promise.resolve []))))
          restore! (fn []
                     (set! db/execute-many original-execute-many)
                     (done))]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {::db/results
                [{::protocol/success? true
                  ::protocol/result {:seon.agent/id "teaching-agent"}}
                 {::protocol/success? true
                  ::protocol/result
                  {:seon.config/system-text "; configured shared teaching"}}
                 {::protocol/success? true ::protocol/result nil}]})))
      (-> (render!)
          (.then
           (fn [host-render]
             (let [system-prompt (:seon.ai/system-prompt host-render)]
               (is (str/includes? system-prompt "; configured shared teaching"))
               (is (str/includes? system-prompt
                                  "platform contract: JVM claimant"))
               (is (= 3
                      (count (::db/members (first @requests))))))))
          (.catch (fn [error] (is false (str error))))
          (.finally restore!)))))
