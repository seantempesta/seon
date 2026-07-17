(ns seon.agent.ctx.render-fns
  "Pure auto-run block selection and child-local selected-function execution."
  (:require
   [cljs.reader :as reader]
   [malli.core :as m]
   [seon.ai.tokens :as tokens]
   [seon.config :as config]
   [seon.db.id :as db.id]
   [seon.schema :as schema]))

(def auto-run-priority
  "Derived render functions appear after stable code context."
  30)

(def ^:private twin-keys #{:seon.render/ai :seon.render/hiccup})

(defn output-twin-keys
  "Return the render keys declared by one persisted function schema."
  {:malli/schema [:=> [:cat :string] [:set :keyword]]}
  [spec-str]
  (try
    (let [form (reader/read-string spec-str)
          info (m/-function-info (m/schema form))
          out (some-> (:output info) m/deref)]
      (if (and out (= :map (m/type out)))
        (into #{} (comp (map first) (filter twin-keys)) (m/entries out))
        #{}))
    (catch :default _ #{})))

(defn- select-render-fn-rows [rows]
  (->> rows
       (keep (fn [{:seon.fn/keys [sym spec private?]}]
               (when (and sym spec (not private?))
                 (let [twins (output-twin-keys spec)]
                   (when (seq twins)
                     {::sym (symbol sym) ::twins twins})))))
       (sort-by (comp str ::sym))
       vec))

;; These load-order schemas remain here because this namespace is the first
;; execution-child owner that references them.
(schema/register! :seon.ns/name [:keyword {:seon.db/identity true}])
(schema/register!
 :seon.agent/id
 [:and {:seon.db/identity true
        :seon.db.id/generator :seon.db.id.generator/human-readable}
  ::db.id/agent-value])

(schema/register! ::fn-sym :symbol)
(schema/register! ::current-ns :seon.ns/name)
(schema/register! ::pinned-syms [:set :symbol])
(schema/register! ::fn-rows [:vector :map])
(schema/register!
 ::derived-blocks-request
 [:map
  [::fn-rows ::fn-rows]
  [::pinned-syms {:optional true} ::pinned-syms]])

(defn derived-blocks
  "Build auto-run blocks from the ordinary function rows already acquired."
  {:malli/schema [:=> [:cat ::derived-blocks-request] [:vector :map]]}
  [{rows ::fn-rows pinned ::pinned-syms}]
  (->> (select-render-fn-rows rows)
       (remove #(contains? (or pinned #{}) (::sym %)))
       (mapv (fn [{sym ::sym twins ::twins}]
               (cond-> {:seon.agent.ctx/name (keyword "render-fn" (name sym))
                        :seon.agent.ctx/priority auto-run-priority
                        ::fn-sym sym}
                 (contains? twins :seon.render/ai)
                 (assoc :seon.render/ai
                        'seon.agent.ctx.render-fns/render-fn-block-ai)
                 (contains? twins :seon.render/hiccup)
                 (assoc :seon.render/html
                        'seon.agent.ctx.render-fns/render-fn-block-html))))))

(defn- selected-call [input]
  {:seon.execution/function-symbol (::fn-sym (:seon.render/node input))
   :seon.execution/arguments
   [(cond-> {:seon.db/db (:seon.db/db input)
             :seon.render/entity (:seon.agent/entity input)}
      (:seon.agent/id input)
      (assoc :seon.agent/id (:seon.agent/id input)))]})

(defn- failure-message [input result]
  (str (::fn-sym (:seon.render/node input)) " failed: "
       (or (get-in result [:seon.execution/error :seon.error/message])
           "selected function failed")))

(defn- error-card [message]
  [:div {:class "text-error text-xs font-mono"} (str "render error: " message)])

(defn ^:async render-fn-block-ai
  "Invoke one derived function in the child and return its bounded AI twin."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [input invoke-selected!]
  (let [result (first (await (invoke-selected! [(selected-call input)])))]
    (if-not (:seon.execution/ok? result)
      (str ";; ⚠ " (failure-message input result))
      (let [value (:seon.execution/value result)
            text (if (map? value) (:seon.render/ai value) value)]
        (cond
          (nil? text) ""
          (string? text)
          (tokens/clip-str
            text
            (config/render-fn-token-cap
              (:seon.config/configuration input)))
          :else (str ";; ⚠ " (::fn-sym (:seon.render/node input))
                     " returned a non-string :seon.render/ai"))))))

(defn ^:async render-fn-block-html
  "Invoke one derived function in the child and return its HTML twin."
  {:malli/schema [:=> [:cat :seon.render/section-request :any]
                  [:maybe :seon.render.canvas/hiccup]]}
  [input invoke-selected!]
  (let [result (first (await (invoke-selected! [(selected-call input)])))]
    (if-not (:seon.execution/ok? result)
      (error-card (failure-message input result))
      (let [value (:seon.execution/value result)
            hiccup (if (map? value) (:seon.render/hiccup value) value)]
        (cond
          (nil? hiccup) nil
          (vector? hiccup) hiccup
          :else (error-card
                 (str (::fn-sym (:seon.render/node input))
                      " returned non-hiccup :seon.render/hiccup")))))))
