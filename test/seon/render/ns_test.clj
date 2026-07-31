(ns seon.render.ns-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.ai.tokens :as tokens]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.ns :as sut]
            [seon.render.walk :as walk]
            [seon.test-support :as support])
  (:import [java.io PushbackReader StringReader]))

(def ^:private property-seed 2026073102)

(defn- namespace-unit
  [db namespace-name distance token-budget]
  (assoc (d/pull db [:db/id
                     :seon.ns/name
                     :seon.ns/source
                     :seon.ns/doc
                     :seon.ns/requires
                     {:seon.ns/aliases
                      [:seon.ns.alias/local :seon.ns.alias/target-ns]}
                     {:seon.ns/imports
                      [:seon.ns.import/local
                       :seon.ns.import/target-class]}
                     {:seon.ns/refers
                      [:seon.ns.refer/local
                       :seon.ns.refer/target-ns
                       :seon.ns.refer/target-name]}]
                 [:seon.ns/name namespace-name])
         :seon.db/db db
         :seon.render/distance distance
         ::sut/token-budget token-budget))

(defn- reader-valid?
  [text]
  (try
    (with-open [reader (PushbackReader. (StringReader. text))]
      (loop []
        (let [form (read {:eof ::eof
                          :read-cond :allow
                          :features #{:clj}}
                         reader)]
          (when-not (= ::eof form)
            (recur)))))
    true
    (catch Throwable _
      false)))

(deftest populated-namespace-renders-the-distance-gradient
  (support/with-database
    (fn [connection]
      (let [db @connection
            d0 (namespace-unit db 'seon.flow 0 100000)
            d1 (namespace-unit db 'seon.flow 1 100000)
            d2 (namespace-unit db 'seon.flow 2 100000)
            ai0 (sut/render-ai d0)
            ai1 (sut/render-ai d1)
            ai2 (sut/render-ai d2)
            routed-ai1
            (walk/projection
             (select-keys d1 [:db/id :seon.ns/name :seon.ns/source])
             {:seon.render/kind :seon.render/ai
              :seon.render/floor 'seon.render.block/data-prose})
            html1 (sut/render-html d1)
            html2 (sut/render-html d2)
            html1-text (hiccup/->string html1)
            html2-text (hiccup/->string html2)
            exact-source
            (d/q '[:find ?source .
                   :where
                   [?function :seon.fn/sym "seon.flow/current-work-launcher"]
                   [?function :seon.fn/source ?source]]
                 db)]
        (testing "distance zero is only the namespace name"
          (is (= "seon.flow" ai0)))
        (testing "distance one is the public callable surface"
          (is (= `sut/render-ai routed-ai1)
              "the namespace entity map owns the family default")
          (is (str/includes? ai1 "(ns seon.flow"))
          (is (str/includes? ai1 "[clojure.core.async :as async]"))
          (is (str/includes? ai1 "(defn current-work-launcher"))
          (is (str/includes?
               ai1
               "Return the installed work launcher or fail the readiness check."))
          (is (not (str/includes? ai1 "@installed-work-launcher")))
          (is (not (str/includes? ai1 "fault-graph-definition"))
              "private implementation is deferred to the deeper view")
          (is (reader-valid? ai1)))
        (testing "distance two uses the exact stored spans"
          (is (str/includes? ai2 exact-source))
          (is (str/includes? ai2 "fault-graph-definition")))
        (testing "the HTML twin keeps the same definitions addressable"
          (is (= :section (first html1)))
          (is (str/includes? html1-text "<dl"))
          (is (str/includes? html1-text "<code>seon.flow/current-work-launcher</code>"))
          (is (str/includes?
               html1-text
               (str "id=\""
                    (block/surface-id :seon.flow/current-work-launcher)
                    "\"")))
          (is (not (str/includes? html1-text "@installed-work-launcher")))
          (is (str/includes? html2-text "@installed-work-launcher")))))))

(deftest name-only-agent-namespace-pending-source-optional-family
  (support/with-database
    (fn [connection]
      (d/transact connection
                  [{:db/id "fresh-namespace"
                    :seon.ns/name 'my.agents.fresh}
                   {:seon.cluster.agent/id "fresh"
                    :seon.cluster.agent/namespace "fresh-namespace"}])
      (let [db @connection
            unit (namespace-unit db 'my.agents.fresh 1 256)
            ai (sut/render-ai unit)
            html (sut/render-html unit)
            html-text (hiccup/->string html)]
        (is (string? ai))
        (is (vector? html))
        (when (string? ai)
          (is (str/includes? ai "my.agents.fresh"))
          (is (str/includes? ai "no definitions yet"))
          (is (str/includes? ai "owned by agent fresh")))
        (when (vector? html)
          (is (str/includes? html-text "my.agents.fresh"))
          (is (str/includes? html-text "No definitions yet"))
          (is (str/includes? html-text "owned by agent fresh")))
        (testing "PENDING W1: source-less rows cannot match :seon.ns/ns yet"
          (is (= 'seon.render.block/data-prose
                 (walk/projection
                  (select-keys unit [:db/id :seon.ns/name])
                  {:seon.render/kind :seon.render/ai
                   :seon.render/floor 'seon.render.block/data-prose})))
          ;; When W1 makes :seon.ns/source optional, this assertion must become
          ;; `sut/render-ai` and the route-level no-floor assertions can land.
          )))))

(deftest every-populated-namespace-renders-within-an-explicit-budget
  (support/with-database
    (fn [connection]
      (let [db @connection
            namespace-names
            (vec (sort-by str
                          (d/q '[:find [?name ...]
                                 :where [_ :seon.ns/name ?name]]
                               db)))
            check
            (tc/quick-check
             100
             (prop/for-all
              [namespace-name (gen/elements namespace-names)
               distance (gen/choose 0 2)
               token-budget (gen/choose 64 2048)]
              (let [unit (namespace-unit
                          db namespace-name distance token-budget)
                    ai (sut/render-ai unit)
                    html (sut/render-html unit)
                    html-text (hiccup/->string html)]
                (and (not (str/blank? ai))
                     (not (str/blank? html-text))
                     (<= (tokens/estimate ai) token-budget)
                     (<= (tokens/estimate html-text) token-budget)
                     (or (not= 1 distance) (reader-valid? ai)))))
             :seed property-seed)]
        (is (seq namespace-names))
        (support/assert-check!
         check
         "Every namespace projection must be non-empty, bounded, and readable.")))))
