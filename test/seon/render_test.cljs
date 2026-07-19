(ns seon.render-test
  "Pure rendering contracts for already-resolved ordinary values."
  (:require
   [clojure.string :as str]
   [cljs.test :refer [deftest is testing]]
   [seon.config :as config]
   [seon.error :as error]
   [seon.render :as render]
   [seon.render.value :as value]
   [seon.ui.html :as html]))

(def ^:private configuration (config/resolve-config-singleton {}))

(deftest literal-hiccup-is-already-resolved-render-data
  (let [hiccup [:div {:class "surface"} [:span "ready"]]]
    (is (= hiccup (render/block :html configuration hiccup)))
    (is (str/includes? (render/block :ai configuration hiccup) "ready"))))

(deftest tagged-message-and-source-values-project-to-both-views
  (let [message {:seon.render/markdown "**hello**"}
        source {:seon.render/source "(+ 1 2)"}
        message-html (render/block :html configuration message)
        source-html (render/block :html configuration source)
        source-html-text (html/->string source-html)]
    (is (vector? message-html))
    (is (str/includes? (pr-str message-html) "hello"))
    (is (= "**hello**" (render/block :ai configuration message)))
    (is (vector? source-html))
    (is (= :pre (first source-html)))
    (is (= "language-clojure hljs" (get-in source-html [2 1 :class])))
    (is (every? #(str/includes? source-html-text %) ["+" "1" "2"]))
    (is (= "(+ 1 2)" (render/block :ai configuration source)))))

(deftest error-values-remain-visible-in-both-views
  (let [failure {:seon.error/message "visible failure"
                 :seon.error/where :render-test}
        html (render/block :html configuration failure)
        ai (render/block :ai configuration failure)]
    (is (vector? html))
    (is (str/includes? (pr-str html) "visible failure"))
    (is (= "visible failure" ai))))

(deftest ordinary-values-use-the-one-value-projection
  (let [ordinary {:seon.test/value [1 2 3]}
        html (render/block :html configuration ordinary)
        ai (render/block :ai configuration ordinary)]
    (is (vector? html))
    (is (str/includes? (pr-str html) "seon.test/value"))
    (is (string? ai))
    (is (str/includes? ai "seon.test/value"))))

(deftest render-failure-is-guarded-or-thrown-by-the-single-strict-dial
  (let [ordinary {:seon.test/value 42}
        throw-render (fn [& _] (throw (js/Error. "projection failed")))]
    (testing "strict off keeps the failure visible"
      (with-redefs [config/render-strict? (constantly false)
                    value/render-html-data throw-render]
        (let [html (error/expecting-core-fault!
                    #(render/block :html configuration ordinary))]
          (is (vector? html))
          (is (str/includes? (pr-str html) "projection failed")))))
    (testing "strict on throws the same failure with render context"
      (with-redefs [config/render-strict? (constantly true)
                    value/render-ai throw-render]
        (let [caught
              (try
                (error/expecting-core-fault!
                 #(render/block :ai configuration ordinary))
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
                    value/render-ai
                    (fn [& _] (throw (js/Error. "generic render fixture")))]
        (let [rendered
              (error/expecting-core-fault!
               (fn []
                 (error/with-configuration
                  configuration
                  (fn []
                    (render/block :ai configuration
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
