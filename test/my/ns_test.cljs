(ns my.ns-test
  "The my.ns/functions contract over ordinary program-graph query results."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [my.ns :as my-ns]
    [seon.db :as db]
    [seon.repl.internal :as repl-internal]))

(def ^:private database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "00000000-0000-0000-0000-000000000042"})

(def ^:private demo-function-rows
  [{:seon.fn/sym "my.demo/twice"
    :seon.fn/agent-facing? true
    :seon.fn/fn-var? true
    :seon.fn/doc "Double a number.\n\nThe mechanism story lives below the fold."
    :seon.fn/arglists "([n])"
    :seon.fn/spec "[:=> [:cat :int] :int]"}
   {:seon.fn/sym "my.demo/add"
    :seon.fn/agent-facing? true
    :seon.fn/fn-var? true
    :seon.fn/doc "Add two numbers."
    :seon.fn/arglists "([a b])"
    :seon.fn/spec "[:=> [:cat :int :int] :int]"}
   {:seon.fn/sym "my.demo/runtime-helper"
    :seon.fn/fn-var? true
    :seon.fn/doc "Implementation detail."
    :seon.fn/arglists "([x])"
    :seon.fn/spec "[:=> [:cat :int] :int]"}
   {:seon.fn/sym "my.demo/unspecced"
    :seon.fn/fn-var? true
    :seon.fn/arglists "([x])"}
   {:seon.fn/sym "my.demo/secret-helper"
    :seon.fn/fn-var? true
    :seon.fn/private? true
    :seon.fn/arglists "([x])"
    :seon.fn/spec "[:=> [:cat :int] :int]"}])

(defn- finish
  [promise done]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [error]
                (is false (str "threw — " error))
                (done)))))

(defn- with-program-results
  [namespaces body]
  (let [saved-db db/db
        saved-query db/query
        saved-pull db/pull]
    (set! db/db
          (fn
            ([] (js/Promise.resolve database))
            ([_request] (js/Promise.resolve database))))
    (set! db/query
          (fn [request]
            (is (= database (:seon.db/db request))
                "the captured database value is forwarded")
            (js/Promise.resolve
             (when (contains? namespaces (first (:seon.db/args request)))
               (first (:seon.db/args request))))))
    (set! db/pull
          (fn
            ([request]
             (is (= database (:seon.db/db request))
                 "pull uses the same database value")
             (js/Promise.resolve
              {:seon.fn/_ns (get namespaces (:seon.db/ref request))}))
            ([_pattern _ref]
             (js/Promise.reject (js/Error. "expected map pull")))
            ([_database _pattern _ref]
             (js/Promise.reject (js/Error. "expected map pull")))))
    (-> (js/Promise.resolve)
        (.then (fn [] (body)))
        (.finally
          (fn []
            (set! db/db saved-db)
            (set! db/query saved-query)
            (set! db/pull saved-pull))))))

(defn- functions
  [namespace-name]
  (my-ns/functions {:my.ns/ns namespace-name :seon.db/db database}))

(deftest functions-lists-public-fns-as-compact-cards
  (async done
    (finish
     (with-program-results
       {:my.demo demo-function-rows}
       (fn []
         (-> (functions 'my.demo)
             (.then
              (fn [{ok? :seon.result/ok? :as result}]
                (let [cards (:my.ns/cards result)
                      twice (nth cards 2)]
                  (is (true? ok?))
                  (is (= 3 (:my.ns/count result))
                      "all public schema-complete function rows appear")
                  (is (= 3 (count cards)))
                  (is (some #(str/includes? % "runtime-helper") cards)
                      "marker absence is not a presentation filter")
                  (is (not-any? #(str/includes? % "unspecced") cards)
                      "an incomplete public row stays out")
                  (is (str/includes? (first cards) "my.demo/add")
                      "cards sort by name")
                  (is (str/includes? twice "my.demo/twice"))
                  (is (str/includes? twice "Double a number.")
                      "docstring line one rides the card")
                  (is (not (str/includes? twice "mechanism story"))
                      "the body of the doc is elided")
                  (is (str/includes? twice "positional")
                      "the invocation shape renders")
                  (is (str/includes? twice "n :int")
                      "the named argument contract renders")
                  (is (not-any? #(str/includes? twice %) [":=>" ":catn" "…"])
                      "callable grammar cannot be mistaken for code")
                  (is (not-any? #(str/includes? % "secret-helper") cards)
                      "private functions never card")
                  (is (every?
                       (fn [card]
                         (not-any?
                          #(contains? #{:form :read} (:seon.repl/kind %))
                          (repl-internal/parse-forms card)))
                       cards)
                      "cards are inert documentation if copied into a reply")))))))
     done)))

(deftest functions-accepts-symbol-keyword-and-string
  (async done
    (finish
     (with-program-results
       {:my.demo demo-function-rows}
       (fn []
         (-> (js/Promise.all
              #js [(functions 'my.demo)
                   (functions :my.demo)
                   (functions "my.demo")])
             (.then
              (fn [results]
                (let [[by-symbol by-keyword by-string] (js->clj results)]
                  (is (= by-symbol by-keyword by-string)
                      "the three spellings are one question")))))))
     done)))

(deftest functions-acquires-one-database-value-when-omitted
  (async done
    (finish
     (with-program-results
       {:my.demo demo-function-rows}
       (fn []
         (-> (my-ns/functions {:my.ns/ns 'my.demo})
             (.then
              (fn [result]
                (is (true? (:seon.result/ok? result)))
                (is (= 3 (:my.ns/count result))))))))
     done)))

(deftest functions-unknown-ns-is-a-legible-error
  (async done
    (finish
     (with-program-results
       {}
       (fn []
         (-> (functions 'no.such.place)
             (.then
              (fn [{ok? :seon.result/ok? :as result}]
                (is (false? ok?))
                (is (str/includes? (:my.ns/error result) "not indexed"))
                (is (str/includes? (:my.ns/hint result) ":seon.ns/name")
                    "the hint carries the discovery query"))))))
     done)))

(deftest functions-indexed-but-empty-ns-is-empty-success
  (async done
    (finish
     (with-program-results
       {:my.hollow []}
       (fn []
         (-> (functions :my.hollow)
             (.then
              (fn [{ok? :seon.result/ok? :as result}]
                (is (true? ok?) "no functions is success, not an error")
                (is (= [] (:my.ns/cards result)))
                (is (= 0 (:my.ns/count result)))
                (is (str/includes? (:my.ns/hint result) "render-namespace")
                    "the hint points at the full-namespace drill"))))))
     done)))
