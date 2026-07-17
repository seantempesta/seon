(ns seon.agent.ctx.render-fns-test
  "Pure selection and child-execution behavior for authored render functions."
  (:require
   [clojure.string :as str]
   [cljs.test :refer [async deftest is testing]]
   [seon.agent.ctx.render-fns :as rf]
   [seon.ai.tokens :as tokens]
   [seon.config :as config]
   [seon.schema :as schema]))

(schema/register!
 :seon.agent.ctx.render-fns-test/response
 [:map
  [:seon.render/ai :string]
  [:seon.render/hiccup [:vector :any]]])

(def ^:private agent-id "tst-2607020000")
(def ^:private cur-ns-str "my.agent.tst-2607020000")
(def ^:private database {:seon.test/database-value true})
(def ^:private configuration (config/resolve-config-singleton {}))

(def ^:private render-spec
  "[:=> [:cat :map] [:map [:seon.render/ai :string] [:seon.render/hiccup [:vector :any]]]]")

(def ^:private ai-only-spec
  "[:=> [:cat :map] [:map [:seon.render/ai :string]]]")

(def ^:private plain-spec "[:=> [:cat :map] :string]")

(defn- fn-row
  ([name spec] (fn-row name spec false))
  ([name spec private?]
   {:seon.fn/sym (str cur-ns-str "/" name)
    :seon.fn/spec spec
    :seon.fn/private? private?}))

(def ^:private function-rows
  [(fn-row "good-view" render-spec)
   (fn-row "ai-view" ai-only-spec)
   (fn-row "bad-view" render-spec)
   (fn-row "plain-view" plain-spec)
   (fn-row "private-view" render-spec true)])

(defn- block-for [name]
  (->> (rf/derived-blocks {::rf/fn-rows function-rows})
       (filter #(= (keyword "render-fn" name)
                   (:seon.agent.ctx/name %)))
       first))

(defn- selected-executor [observed]
  (fn [calls]
    (swap! observed into calls)
    (js/Promise.resolve
     (mapv
      (fn [{function-symbol :seon.execution/function-symbol
            [argument] :seon.execution/arguments}]
        (case (name function-symbol)
          "good-view"
          {:seon.execution/ok? true
           :seon.execution/value
           {:seon.render/ai (str "GOOD-AI me=" (:seon.agent/id argument)
                                 " db=" (identical? database
                                                    (:seon.db/db argument)))
            :seon.render/hiccup [:div "GOOD-SURFACE"]}}

          "bad-view"
          {:seon.execution/ok? false
           :seon.execution/error {:seon.error/message "BAD-VIEW-BOOM"}}

          "huge-view"
          {:seon.execution/ok? true
           :seon.execution/value
           {:seon.render/ai (apply str (repeat 20000 "x"))}}

          {:seon.execution/ok? false
           :seon.execution/error
           {:seon.error/message (str "Unexpected selected function "
                                     function-symbol)}}))
      calls))))

(deftest output-twin-keys-detects-render-outputs
  (testing "map outputs declare the available render twins"
    (is (= #{:seon.render/ai :seon.render/hiccup}
           (rf/output-twin-keys render-spec)))
    (is (= #{:seon.render/ai} (rf/output-twin-keys ai-only-spec))))
  (testing "registered refs resolve and non-render outputs are ignored"
    (is (= #{:seon.render/ai :seon.render/hiccup}
           (rf/output-twin-keys
            "[:=> [:cat :map] :seon.agent.ctx.render-fns-test/response]")))
    (is (= #{} (rf/output-twin-keys plain-spec)))
    (is (= #{} (rf/output-twin-keys "not a schema")))))

(deftest derived-blocks-select-from-acquired-function-rows
  (let [blocks (rf/derived-blocks {::rf/fn-rows function-rows})
        by-name (into {} (map (juxt :seon.agent.ctx/name identity)) blocks)]
    (is (= [:render-fn/ai-view :render-fn/bad-view :render-fn/good-view]
           (mapv :seon.agent.ctx/name blocks))
        "selection is ordered and excludes plain and private functions")
    (is (= #{:seon.render/ai}
           (->> (by-name :render-fn/ai-view)
                keys
                (filter #{:seon.render/ai :seon.render/html})
                set)))
    (is (= #{:seon.render/ai :seon.render/html}
           (->> (by-name :render-fn/good-view)
                keys
                (filter #{:seon.render/ai :seon.render/html})
                set)))
    (is (every? #(= rf/auto-run-priority
                    (:seon.agent.ctx/priority %))
                blocks))))

(deftest derived-blocks-respect-explicit-pins
  (is (= [:render-fn/ai-view :render-fn/bad-view]
         (mapv :seon.agent.ctx/name
               (rf/derived-blocks
                {::rf/fn-rows function-rows
                 ::rf/pinned-syms #{(symbol cur-ns-str "good-view")}})))))

(deftest runner-forwards-frozen-database-and-agent-id
  (async done
    (let [observed (atom [])
          input {:seon.db/db database
                 :seon.agent/id agent-id
                 :seon.config/configuration configuration
                 :seon.render/node (block-for "good-view")}
          invoke-selected! (selected-executor observed)]
      (-> (js/Promise.all
           #js [(rf/render-fn-block-ai input invoke-selected!)
                (rf/render-fn-block-html input invoke-selected!)])
          (.then
           (fn [values]
             (let [ai (aget values 0)
                   html (aget values 1)
                   arguments (mapv (comp first :seon.execution/arguments)
                                   @observed)]
               (is (str/includes? ai "GOOD-AI"))
               (is (str/includes? ai (str "me=" agent-id)))
               (is (str/includes? ai "db=true"))
               (is (= [:div "GOOD-SURFACE"] html))
               (is (every? #(identical? database (:seon.db/db %)) arguments))
               (is (every? #(= agent-id (:seon.agent/id %)) arguments)))))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(deftest runner-surfaces-selected-function-errors-as-values
  (async done
    (let [observed (atom [])
          input {:seon.db/db database
                 :seon.agent/id agent-id
                 :seon.config/configuration configuration
                 :seon.render/node (block-for "bad-view")}
          invoke-selected! (selected-executor observed)]
      (-> (js/Promise.all
           #js [(rf/render-fn-block-ai input invoke-selected!)
                (rf/render-fn-block-html input invoke-selected!)])
          (.then
           (fn [values]
             (let [ai (aget values 0)
                   html (aget values 1)]
               (is (str/includes? ai "⚠"))
               (is (str/includes? ai "bad-view"))
               (is (str/includes? ai "BAD-VIEW-BOOM"))
               (is (vector? html))
               (is (str/includes? (pr-str html) "render error")))))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(deftest runner-clips-ai-output-at-the-operation-cap
  (async done
    (let [observed (atom [])
          node (assoc (block-for "good-view")
                      ::rf/fn-sym (symbol cur-ns-str "huge-view"))
          input {:seon.db/db database
                 :seon.agent/id agent-id
                 :seon.config/configuration
                 (assoc configuration
                        :seon.config.render/render-fn-token-cap 100)
                 :seon.render/node node}]
      (-> (rf/render-fn-block-ai input (selected-executor observed))
          (.then
           (fn [ai]
             (is (str/ends-with? ai "…"))
             (is (< (count ai) 20000))
             (is (<= (tokens/estimate ai) 100))))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))
