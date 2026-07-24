(ns seon.agent.ctx.driver-test
  (:require
   [cljs.test :refer [async deftest is]]
   [clojure.string :as str]
   [malli.core :as m]
   [seon.agent.ctx.driver :as ctx.driver]
   [seon.agent.message :as message]
   [seon.ai :as ai]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.branch :as branch]
   [seon.db.protocol :as protocol]
   [seon.execution :as execution]
   [seon.render.canvas :as canvas]))

(def point
  {::branch/store-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   ::branch/name :db
   ::branch/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   ::branch/basis-t 42})

(def database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(def configuration (config/resolve-config-singleton {}))

(deftest prompt-and-agent-view-acquisitions-share-owned-read-profiles
  (let [prompt-members @#'ctx.driver/prompt-acquisition-members
        view-members ctx.driver/agent-view-members]
    (is (= ctx.driver/agent-entity-read-profile
           (select-keys (first prompt-members)
                        (keys ctx.driver/agent-entity-read-profile))))
    (is (= ctx.driver/agent-entity-read-profile
           (select-keys (first view-members)
                        (keys ctx.driver/agent-entity-read-profile))))
    (is (= config/configuration-read-profile
           (select-keys (second prompt-members)
                        (keys config/configuration-read-profile))))
    (is (= config/configuration-read-profile
           (select-keys (nth view-members 2)
                        (keys config/configuration-read-profile))))
    (is (= ai/configuration-read-profile
           (select-keys (nth prompt-members 2)
                        (keys ai/configuration-read-profile))))))

(defn- handler-action [hiccup event]
  (some (fn [value]
          (when (and (map? value) (contains? value event))
            (get value event)))
        (tree-seq coll? seq hiccup)))

(defn- handler-function [hiccup event]
  (some->> (handler-action hiccup event)
           (re-find #"fn=([^&']+)")
           second
           js/decodeURIComponent))

(deftest absent-canvas-function-keeps-failure-details-from-the-human
  (let [renderer 'my.app/orders
        block {:seon.render.surface/selection "canvas"
               :seon.render/html renderer}
        result
        {::execution/ok? false
         ::execution/error
         {:seon.error/message
          "The selected function is absent from the current database program."
          :seon.error/kind :agent}}
        hiccup (@#'ctx.driver/html-value "agent-1" block result)
        rendered (pr-str hiccup)]
    (is (str/includes? rendered "Updating this canvas"))
    (is (not (str/includes? rendered "my.app/orders")))
    (is (not (str/includes? rendered "absent from")))
    (is (not (str/includes? rendered "error")))))

(deftest agent-view-projection-resolves-literal-and-async-authored-surfaces
  (async done
    (let [original db/execute-many
          calls (atom nil)
          acquisition (atom nil)]
      (set! db/execute-many
            (fn [request]
              (reset! acquisition request)
              (js/Promise.resolve
               {::db/results
                [{::protocol/success? true
                  ::protocol/result
                  {:seon.agent/id "agent-1"
                   :seon.agent/ctx
                   [{:seon.agent.ctx/name :literal
                     :seon.agent.ctx/priority 1
                     :seon.render/html (pr-str [:div "literal"])}
                    {:seon.agent.ctx/name :authored
                     :seon.agent.ctx/priority 2
                     :seon.render/html (pr-str 'my.orders/view)
                     :seon.fn/read-attrs [:my.example/value]}
                    {:seon.agent.ctx/name :canvas
                     :seon.agent.ctx/priority 3
                     :seon.render.canvas/content
                     (pr-str
                      [:div "configured canvas"
                       [:form {:on-submit 'save-canvas!}]])}]}}
                 {::protocol/success? true
                  :datahike.query/result 3}
                 {::protocol/success? true
                  ::protocol/result configuration}]})))
      (-> (ctx.driver/render-agent-view!
           {:seon.agent/id "agent-1" ::db/db database}
           (fn [selected]
             (reset! calls selected)
             (js/Promise.resolve
              [{::execution/ok? true
                ::execution/value
                {:seon.render/hiccup
                 [:div "authored"
                  [:button {:on-click 'save-authored!}]]}}])))
          (.then
           (fn [projection]
             (is (= ['my.orders/view]
                    (mapv ::execution/function-symbol @calls)))
             (is (= database
                    (get-in (first @calls)
                            [::execution/arguments 0 ::db/db]))
                 "every selected HTML renderer receives the page database")
             (is (= #{"literal" "authored" "canvas"}
                    (into #{}
                          (map :seon.render.surface/label)
                          (:seon.render.surface/surfaces projection))))
             (is (m/validate :seon.ui.agent-view/projection projection)
                 "the complete in-pod page projection satisfies its output schema")
             (is (= #{:literal :authored :canvas}
                    (into #{}
                          (map :seon.agent.ctx/name)
                          (:seon.render.surface/surfaces projection)))
                 "every materialized surface preserves its context identity")
             (let [by-label
                   (into {}
                         (map (juxt :seon.render.surface/label identity))
                         (:seon.render.surface/surfaces projection))
                   canvas-form
                   (get-in by-label
                           ["canvas" :seon.render.surface/expanded 2])
                   authored-button
                   (get-in by-label
                           ["authored" :seon.render.surface/expanded 2])]
               (is (= "my.agent.agent-1/save-canvas!"
                      (handler-function
                       canvas-form (keyword "data-on:submit")))
                   "literal canvas handlers use the owning home namespace")
               (is (= "my.orders/save-authored!"
                      (handler-function
                       authored-button (keyword "data-on:click")))
                   "dynamic handlers use the render function namespace"))
             (is (= 3 (count (::db/members @acquisition)))
                 "the page acquires the agent, count, and configuration")
             (is (= 65536
                    (get-in @acquisition
                            [::db/members 1
                             :datahike.resource/max-results]))
                 "the scalar count budgets its retained matching relation")
             (is (= 4096
                    (get-in @acquisition
                            [::db/members 2
                             :datahike.resource/max-results]))
                 "the full configuration pull budgets its retained result tree")
             (is (identical? database (::db/db @acquisition))
                 "the configured canvas shares the page's one database value")
             (is (contains? (:seon.web.datastar/dependencies projection)
                            :my.example/value))
             (is (contains? (:seon.web.datastar/dependencies projection)
                            :seon.agent/id))
             (done)))
          (.catch (fn [error] (is false (str error)) (done)))
          (.finally (fn [] (set! db/execute-many original)))))))

(deftest agent-view-unwired-canvas-resolves-to-welcome
  (async done
    (let [original db/execute-many
          original-recent message/recent
          acquisitions (atom 0)
          requests (atom [])
          recent-request (atom nil)
          calls (atom nil)]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [n (swap! acquisitions inc)]
                (js/Promise.resolve
                 (case n
                   1 {::db/results
                      [{::protocol/success? true
                        ::protocol/result {:seon.agent/id "agent-1"}}
                       {::protocol/success? true
                        :datahike.query/result 1}
                       {::protocol/success? true
                        ::protocol/result configuration}]}
                   2 {::db/results
                      [{::protocol/success? true
                        :datahike.query/result []}]}
                   {:seon.error/message "unexpected acquisition"})))))
      (set! message/recent
            (fn [request]
              (reset! recent-request request)
              (js/Promise.resolve
               [{:seon.agent.message/from {:seon.agent/id "agent-1"}
                 :seon.agent.message/to [{:seon.user/id "user"}]
                 :seon.agent.message/content "older reply"
                 :seon.agent.message/at (js/Date. 1)}
                {:seon.agent.message/from {:seon.agent/id "agent-1"}
                 :seon.agent.message/to [{:seon.user/id "user"}]
                 :seon.agent.message/content "latest reply"
                 :seon.agent.message/at (js/Date. 2)}
                {:seon.agent.message/from {:seon.agent/id "agent-1"}
                 :seon.agent.message/to [{:seon.agent/id "agent-2"}]
                 :seon.agent.message/content "newer peer message"
                 :seon.agent.message/at (js/Date. 3)}
                {:seon.agent.message/from {:seon.agent/id "agent-1"}
                 :seon.agent.message/to [{:seon.agent/id "agent-1"}]
                 :seon.agent.message/content "newest self narration"
                 :seon.agent.message/at (js/Date. 4)}])))
      (-> (ctx.driver/render-agent-view!
           {:seon.agent/id "agent-1" ::db/db database}
           (fn [selected]
             (reset! calls selected)
             (js/Promise.resolve
              [{::execution/ok? true
                ::execution/value
                {:seon.render/hiccup [:div "welcome"]}}])))
          (.then
           (fn [projection]
             (is (= [canvas/welcome-sym]
                    (mapv ::execution/function-symbol @calls))
                 "absence follows the shared canvas resolution to welcome")
             (is (= 2 @acquisitions)
                 "page acquisition and one bounded derived-candidate read")
             (is (every? #(identical? database (::db/db %)) @requests)
                 "page and canvas acquisition use the identical database value")
             (is (identical? database (::db/db @recent-request))
                 "recent messages use that same database value")
             (is (= "latest reply"
                    (get-in (first @calls)
                            [::execution/arguments 0
                             :seon.render.chat/last-reply]))
                 "welcome receives the newest agent-to-user reply as ordinary data")
             (is (= "agent-1"
                    (get-in (first @calls)
                            [::execution/arguments 0
                             :seon.agent/entity :seon.agent/id]))
                 "welcome receives the agent entity under the system-input key")
             (is (= [:div "welcome"]
                    (->> (:seon.render.surface/surfaces projection)
                         (some #(when (= "canvas"
                                         (:seon.render.surface/label %))
                                  (:seon.render.surface/expanded %))))))
             (done)))
          (.catch (fn [error] (is false (str error)) (done)))
          (.finally (fn []
                      (set! db/execute-many original)
                      (set! message/recent original-recent)))))))

(deftest agent-view-direct-acquisition-error-short-circuits
  (async done
    (let [original db/execute-many
          requests (atom 0)
          selected (atom 0)
          failure {:seon.error/message "page acquisition failed"
                   :seon.error/kind :core-bug
                   :seon.error/data {:stage :page}}]
      (set! db/execute-many
            (fn [_request]
              (swap! requests inc)
              (js/Promise.resolve failure)))
      (-> (ctx.driver/render-agent-view!
           {:seon.agent/id "agent-1" ::db/db database}
           (fn [_calls]
             (swap! selected inc)
             (js/Promise.resolve [])))
          (.then
           (fn [result]
             (is (= failure result))
             (is (= 1 @requests)
                 "the malformed page result never starts canvas acquisition")
             (is (zero? @selected)
                 "no selected renderer runs after page acquisition failure")
             (done)))
          (.catch (fn [error] (is false (str error)) (done)))
          (.finally (fn [] (set! db/execute-many original)))))))
