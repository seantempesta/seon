(ns seon.render-test
  "Pure rendering contracts for already-resolved ordinary values."
  (:require
   [clojure.string :as str]
   [cljs.test :refer [deftest is testing]]
   [seon.agent.ctx.render-fns]
   [seon.agent.run]
   [seon.config :as config]
   [seon.error :as error]
   [seon.render :as render]
   [seon.render.value :as value]
   [seon.schema :as schema]
   [seon.ui.html :as html]))

(def ^:private configuration (config/resolve-config-singleton {}))
(def ^:private render-request {})

(defn- with-active-projection [forms body]
  (let [before (schema/snapshot-state)]
    (try
      (let [projection (schema/build-projection forms)]
        (schema/activate-projection! projection)
        (body projection))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest literal-hiccup-is-already-resolved-render-data
  (let [hiccup [:div {:class "surface"} [:span "ready"]]]
    (is (= hiccup (render/block :html configuration render-request hiccup)))
    (is (str/includes? (render/block :ai configuration render-request hiccup) "ready"))))

(deftest tagged-message-and-source-values-project-to-both-views
  (let [message {:seon.render/markdown "**hello**"}
        source {:seon.render/source "(+ 1 2)"}
        message-html (render/block :html configuration render-request message)
        source-html (render/block :html configuration render-request source)
        source-html-text (html/->string source-html)]
    (is (vector? message-html))
    (is (str/includes? (pr-str message-html) "hello"))
    (is (= "**hello**" (render/block :ai configuration render-request message)))
    (is (vector? source-html))
    (is (= :pre (first source-html)))
    (is (= "language-clojure hljs" (get-in source-html [2 1 :class])))
    (is (every? #(str/includes? source-html-text %) ["+" "1" "2"]))
    (is (= "(+ 1 2)" (render/block :ai configuration render-request source)))))

(deftest error-values-remain-visible-in-both-views
  (let [failure {:seon.error/message "visible failure"
                 :seon.error/where :render-test}
        html (render/block :html configuration render-request failure)
        ai (render/block :ai configuration render-request failure)]
    (is (vector? html))
    (is (str/includes? (pr-str html) "visible failure"))
    (is (= "visible failure" ai))))

(deftest ordinary-values-use-the-one-value-projection
  (let [ordinary {:seon.test/value [1 2 3]}
        html (render/block :html configuration render-request ordinary)
        ai (render/block :ai configuration render-request ordinary)]
    (is (vector? html))
    (is (str/includes? (pr-str html) "seon.test/value"))
    (is (string? ai))
    (is (str/includes? ai "seon.test/value"))))

(deftest render-failure-is-guarded-or-thrown-by-the-single-strict-dial
  (let [ordinary {:seon.test/value 42}
        throw-render (fn ([_ _ _ _] (throw (js/Error. "projection failed")))
                       ([_ _ _ _ _] (throw (js/Error. "projection failed"))))]
    (testing "strict off keeps the failure visible"
      (with-redefs [config/render-strict? (constantly false)
                    value/render-html-data-in throw-render]
        (let [html (error/expecting-core-fault!
                    #(render/block :html configuration render-request ordinary))]
          (is (vector? html))
          (is (str/includes? (pr-str html) "projection failed")))))
    (testing "strict on throws the same failure with render context"
      (with-redefs [config/render-strict? (constantly true)
                    value/render-html-data-in throw-render]
        (let [caught
              (try
                (error/expecting-core-fault!
                 #(render/block :ai configuration render-request ordinary))
                ::no-throw
                (catch :default error error))]
          (is (not= ::no-throw caught))
          (is (str/includes? (ex-message caught) "projection failed"))
          (is (true? (:seon.render/strict? (ex-data caught)))))))))

(deftest core-render-failure-uses-the-operation-policy-and-database-hook
  (let [transactions (atom [])
        policies (atom [])
        configuration (assoc configuration :seon.config/on-core-error :gate)
        original-policy config/on-core-error]
    (try
      (error/set-db-hooks!
       {:seon.error/transact!
        (fn [transaction-data]
          (swap! transactions conj transaction-data)
          (js/Promise.resolve {:seon.db/ok? true}))})
      (set! config/on-core-error
            (fn [selected]
              (swap! policies conj (:seon.config/on-core-error selected))
              (original-policy selected)))
      (with-redefs [config/render-strict? (constantly false)
                    value/render-ai-data
                    (fn [& _] (throw (js/Error. "generic render fixture")))]
        (let [rendered
              (error/expecting-core-fault!
               (fn []
                 (error/with-configuration
                  configuration
                  (fn []
                    (render/block :ai configuration render-request
                                  {:seon.test/value 42})))))
              faults (->> @transactions
                          (mapcat identity)
                          (filter #(= "generic render fixture"
                                      (:seon.error/message %))))]
          (is (str/includes? rendered "generic render fixture"))
          (is (= [:gate] @policies)
              "the guard applies the operation's database-selected policy")
          (is (= [:core] (mapv :seon.error/fault faults))
              "the ordinary database hook receives exactly one core fault")))
      (finally
        (set! config/on-core-error original-policy)
        (error/set-db-hooks! {})))))

(deftest recursive-generic-fallback-is-bounded-and-metadata-first
  (let [rendered (render/render
                   :seon.render/ai
                   {:seon.config/configuration configuration}
                   configuration)
        schema-at (str/index-of rendered "; schema :seon.config/singleton")
        identity-at (str/index-of rendered "identity :seon.config/id")
        summary-at (str/index-of rendered (str "map " (count configuration) " keys"))
        sample-at (str/index-of rendered "\n{")
        continuation-at (str/index-of rendered "partial view")]
    (is (every? some? [schema-at identity-at summary-at sample-at continuation-at]))
    (is (< schema-at identity-at summary-at sample-at continuation-at))
    (is (str/includes? rendered "no live continuation"))
    (is (not (str/includes? rendered "result/")))
    (is (< (count rendered) 2000) "the recursive 80-key-class value is bounded")))

(deftest generic-header-uses-the-registered-custom-identity-attribute
  (let [id-attr :render-test.widget/slug
        title-attr :render-test.widget/title
        entity-shape :render-test.widget/entity
        forms (assoc (schema/snapshot)
                     id-attr [:string {:seon.db/identity true}]
                     title-attr :string
                     entity-shape
                     [:map {:seon.db/entity true}
                      [id-attr id-attr]
                      [title-attr title-attr]])
        entity {id-attr "widget-42" title-attr "Honest widget"}]
    (with-active-projection
      forms
      (fn [projection]
        (let [rendered (render/render
                         :seon.render/ai
                         {:seon.config/configuration configuration
                          :seon.schema/projection projection}
                         entity)]
          (is (str/starts-with? rendered
                                "; schema :render-test.widget/entity"))
          (is (str/includes? rendered
                             "identity :render-test.widget/slug \"widget-42\""))
          (is (= "widget-42" (render/renderable-id entity))))))))
