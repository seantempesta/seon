(ns seon.agent.interaction.render
  "Derive the terminal interaction surface from committed database facts."
  #?(:clj (:refer-clojure :exclude [await]))
  (:require
   [seon.agent.interaction]
   [seon.ai.tokens :as tokens]
   [seon.db :as db]
   [seon.render]))

#?(:clj (defmacro await [value] value))

(def ^:private latest-outcome-query
  '{:find [(pull ?interaction
                [:seon.agent.interaction/id
                 :seon.agent.interaction/handler
                 :seon.agent.interaction/status
                 :seon.agent.interaction/result
                 :seon.agent.interaction/error])
           ?requested-at
           ?interaction-id]
    :in [$ ?agent-id]
    :where [[?agent :seon.agent/id ?agent-id]
            [?interaction :seon.agent.interaction/subjects ?agent]
            [?interaction :seon.agent.interaction/id ?interaction-id]
            [?interaction :seon.agent.run/started-at ?requested-at]
            (or [?interaction :seon.agent.interaction/status :done]
                [?interaction :seon.agent.interaction/status :error]
                [?interaction :seon.agent.interaction/status :interrupted])]
    :order-by [?requested-at :desc ?interaction-id :desc]
    :limit 1})

(defn- ^{:async #?(:cljs true :clj false)} latest-outcome
  [database agent-id]
  (let [rows
        (await
         (db/query
          {::db/db database
           ::db/query latest-outcome-query
           ::db/args [agent-id]}))]
    (cond
      (and (map? rows) (:seon.error/message rows)) rows
      (seq rows) (ffirst rows)
      :else nil)))

(defn ^{:async #?(:cljs true :clj false)} render-html
  "Render the latest committed terminal interaction for one agent page.

   No outcome fact means no surface. The handler result is never read from the
   request response; this query is the sole page projection and reconnect
   repaints it from database truth."
  {:malli/schema
   [:=> [:cat :seon.render/section-request]
    [:or :nil :seon.render/html-response
     :seon.agent.interaction/flat-error]]}
  [{database :seon.db/db
    agent-id :seon.agent/id
    node :seon.render/node}]
  (let [outcome (await (latest-outcome database agent-id))]
    (cond
      (and (map? outcome) (:seon.error/message outcome)) outcome
      (nil? outcome) nil
      :else
      (let [handler (:seon.agent.interaction/handler outcome)
            status (:seon.agent.interaction/status outcome)
            value (if (= :done status)
                    (:seon.agent.interaction/result outcome)
                    (:seon.agent.interaction/error outcome))
            budget (or (:seon.agent.ctx/token-cap node) 512)
            summary (tokens/bounded-pr-str value budget)]
        {:seon.render/hiccup
         [:div {:class "flex flex-col"}
          [:div {:class "seon-card-compact flex flex-col gap-1 p-3"}
           [:span {:class (if (= :done status)
                            "text-success"
                            "text-error")}
            (name status)]
           [:code (str handler)]]
          [:div {:class "seon-card-expanded flex flex-col gap-3 p-4"}
           [:div {:class "flex items-center justify-between gap-3"}
            [:strong "interaction"]
            [:span {:class (if (= :done status)
                             "text-success"
                             "text-error")}
             (name status)]]
           [:code (str handler)]
           [:pre {:class "overflow-auto whitespace-pre-wrap"} summary]]]}))))
