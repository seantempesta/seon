(ns seon.render.ns-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [malli.registry :as mr]
            [seon.ai.tokens :as tokens]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.ns :as sut]
            [seon.test-support :as support])
  (:import [java.io PushbackReader StringReader]))

(def ^:private property-seed 2026073102)

(defn- namespace-unit
  [db namespace-name distance token-budget]
  (assoc (db/pull db [:db/id
                     :seon.ns/name
                     :seon.ns/source
                     :seon.ns/doc
                     {:seon.ns/requires [:seon.ns/name]}
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
         :seon.render/profile
         {:seon.render.profile/token-budget token-budget}))

(defn- reader-valid?
  ([text]
   (reader-valid? *ns* text))
  ([reader-ns text]
   (try
     (binding [*ns* reader-ns]
       (with-open [reader (PushbackReader. (StringReader. text))]
         (loop []
           (let [form (read {:eof ::eof
                             :read-cond :allow
                             :features #{:clj}}
                            reader)]
             (when-not (= ::eof form)
               (recur))))))
     true
     (catch Throwable _
       false))))

(defn- comment-framed?
  [value]
  (boolean
   (some #(and (string? %) (str/starts-with? (str/triml %) ";"))
         (tree-seq coll? seq value))))

(defn- install-namespace!
  [connection namespace-name source schema-rows function-rows]
  (db/transact! connection
              [(cond-> {:seon.ns/name namespace-name}
                 source (assoc :seon.ns/source source))])
  (when (seq schema-rows)
    (db/transact! connection schema-rows))
  (when (seq function-rows)
    (db/transact! connection function-rows)))

(defn- function-row
  [namespace-name function-name source options]
  (merge {:seon.fn/sym (str namespace-name "/" function-name)
          :seon.fn/ns [:seon.ns/name namespace-name]
          :seon.fn/source source
          :seon.fn/private? false}
         options))

(deftest populated-namespace-renders-the-inverse-distance-gradient
  (support/with-database
    (fn [connection]
      (let [db @connection
            d0 (namespace-unit db 'seon.flow 0 100000)
            d1 (namespace-unit db 'seon.flow 1 100000)
            d2 (namespace-unit db 'seon.flow 2 100000)
            source (:seon.ns/source d1)
            ai0 (sut/render-ai d0)
            ai1 (sut/render-ai d1)
            ai2 (sut/render-ai d2)
            html1-text (hiccup/->string (sut/render-html d1))
            html2-text (hiccup/->string (sut/render-html d2))]
        (testing "distance zero is only the namespace name"
          (is (= "seon.flow" ai0)))
        (testing "distance one composes every authoritative source row"
          (is (str/starts-with? ai1 source))
          (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote source))
                                  ai1))))
          (is (str/includes? ai1 "(defn start-work-launcher!"))
          (is (str/includes? ai1 "(defn- work-launcher-step")
              "the full d1 tier includes private member source too")
          (is (reader-valid? (the-ns 'seon.flow) ai1)))
        (testing "distance two is an ordinary public API value"
          (let [rendered (edn/read-string ai2)]
            (is (some #(= "seon.flow/start-work-launcher!"
                          (:seon.fn/sym %))
                      rendered))
            (is (some :seon.schema/key rendered))
            (is (not (comment-framed? rendered))))
          (is (str/includes? ai2 "[clojure.core.async :as async]"))
          (is (not (str/includes? ai2 "#:db"))
              "required namespace refs render their nested names")
          (is (str/includes? ai2 "seon.flow/->CountedDroppingBuffer"))
          (is (not (str/includes? ai2 "fault-graph-definition")))
          (is (not (str/includes? ai2 "work-launcher-step")))
          (is (reader-valid? ai2)))
        (testing "the HTML projection has the same tier membership"
          (is (str/includes? html1-text "seon-namespace-source"))
          (is (str/includes?
               html1-text
               "Production-shaped core.async.flow launchers"))
          (is (str/includes? html1-text "seon-namespace-definitions"))
          (is (str/includes? html1-text "seon.flow/start-work-launcher!"))
          (is (str/includes? html2-text "seon-namespace-own-schemas"))
          (is (str/includes? html2-text "seon.flow/start-work-launcher!"))
          (is (str/includes?
               html2-text
               (str "id=\""
                    (block/surface-id :seon.flow/start-work-launcher!)
                    "\"")))
          (is (not (str/includes? html2-text "fault-graph-definition")))
          (is (not (str/includes? html2-text "work-launcher-step"))))))))

(deftest distance-one-never-drops-a-member-when-source-is-absent
  (support/with-database
    (fn [connection]
      (let [namespace-name 'fixture.total
            exact-source "(defn exact [value] (str value))"
            fallback-row
            (dissoc
             (function-row
              namespace-name 'fallback nil
              {:seon.fn/spec "[:=> [:cat :string] :string]"
               :seon.fn/doc "Return the supplied value."})
             :seon.fn/source)]
        (install-namespace!
         connection namespace-name "(ns fixture.total)" []
         [(function-row namespace-name 'exact exact-source {}) fallback-row])
        (let [unit (namespace-unit @connection namespace-name 1 100000)
              ai (sut/render-ai unit)
              html (hiccup/->string (sut/render-html unit))]
          (is (str/includes? ai exact-source))
          (is (str/includes? ai "(defn fallback"))
          (is (str/includes? html exact-source))
          (is (str/includes? html "fixture.total/fallback"))
          (is (reader-valid? ai)))))))

(deftest source-less-agent-namespace-routes-to-the-full-stub
  (support/with-database
    (fn [connection]
      (db/transact! connection
                  [{:db/id "fresh-namespace"
                    :seon.ns/name 'my.agents.fresh}
                   {:seon.cluster.agent/id "fresh"
                    :seon.cluster.agent/namespace "fresh-namespace"}])
      (let [db @connection
            unit (namespace-unit db 'my.agents.fresh 1 256)
            ai (sut/render-ai unit)
            html-text (hiccup/->string (sut/render-html unit))]
        (is (str/includes? ai "(ns my.agents.fresh)"))
        (is (str/includes? ai "No indexed members are recorded"))
        (is (str/includes? ai ":seon.cluster.agent/id \"fresh\""))
        (is (str/includes? html-text "No indexed members are recorded"))
        (is (str/includes? html-text "owner agent fresh"))))))

(deftest schema-closure-is-database-derived-cycle-safe-and-budgeted
  (support/with-database
    (fn [connection]
      (let [closure-ns 'fixture.closure
            closure-source "(ns fixture.closure)\n\n(def source-marker :only)"
            closure-spec
            (pr-str
             [:=>
              [:catn [:fixture.labels/input :fixture.closure/own]]
              [:enum :fixture.enum/not-a-schema :ok]])
            own-row {:seon.schema/key :fixture.closure/own
                     :seon.schema/form
                     (pr-str [:map [:value :fixture.external/a]])}
            error-row
            {:seon.schema/key :fixture.closure/not-found-error
             :seon.schema/form
             (pr-str
              [:map {:seon.error/class true
                     :seon.render/ai 'seon.error/render-ai
                     :seon.render/html 'seon.error/render-html
                     :error/message "must identify the absent fixture"}
               [:seon.error/message :string]])}
            closure-schema-rows
            [own-row error-row
             {:seon.schema/key :fixture.external/a
              :seon.schema/form (pr-str [:or :string :fixture.external/b])}
             {:seon.schema/key :fixture.external/b
              :seon.schema/form (pr-str [:maybe :fixture.external/a])}]
            closure-function-rows
            [(function-row
              closure-ns 'uses-own
              "(defn uses-own [value] value)"
              {:seon.fn/spec closure-spec
               :seon.fn/doc "Uses an own schema whose closure crosses namespaces."})
             (function-row
              closure-ns 'missing-schema
              "(defn missing-schema [value] value)"
              {:seon.fn/spec (pr-str [:=> [:cat :fixture.missing/key] :any])})
             (function-row
              closure-ns 'malformed
              "(defn malformed [value] value)"
              {:seon.fn/spec "["})]
            _ (install-namespace! connection closure-ns closure-source
                                  closure-schema-rows closure-function-rows)
            db @connection
            d1 (namespace-unit db closure-ns 1 100000)
            d2 (namespace-unit db closure-ns 2 100000)
            ai1 (sut/render-ai d1)
            ai2 (sut/render-ai d2)
            html2 (sut/render-html d2)
            isolated-ai2
            (binding [mr/*registry* {:fixture.external/a :boolean
                                     :fixture.external/b :int}]
              (sut/render-ai d2))]
        (testing "d1 composes namespace, member, and schema source"
          (is (str/starts-with? ai1 closure-source))
          (is (str/includes? ai1 "(defn uses-own"))
          (is (str/includes? ai1
                             "#:seon.schema{:key :fixture.external/a}"))
          (is (str/includes? ai1
                             "#:seon.schema{:key :fixture.external/b}"))
          (is (str/includes? ai1
                             "(register! :fixture.closure/own")))
        (testing "d2 renders own records, one closure, and raw contracts"
          (let [rendered (edn/read-string ai2)]
            (is (some #(= :fixture.closure/own (:seon.schema/key %))
                      rendered))
            (is (some #(= {:seon.schema/key
                           :fixture.closure/not-found-error
                           :seon.error/message
                           "must identify the absent fixture"}
                          %)
                      rendered))
            (is (not (str/includes? ai2 "seon.error/render-ai")))
            (is (some #(= "fixture.closure/uses-own" (:seon.fn/sym %))
                      rendered))
            (is (not (comment-framed? rendered))))
          (is (not (str/includes? ai2
                                  "(register! :fixture.closure/own")))
          (is (not (str/includes? ai2
                                  "(register! :fixture.labels/input"))
              "a qualified catn label is not a schema ref")
          (is (not (str/includes? ai2
                                  "(register! :fixture.enum/not-a-schema"))
              "a qualified enum value is not a schema ref")
          (is (not (str/includes? ai2
                                  "(register! :fixture.missing/key"))
              "a missing row never becomes a fake registration")
          (is (not (str/includes? (pr-str html2) "(register!"))
              "HTML names referenced schemas without repeating their source")
          (is (str/includes? (pr-str html2) ":fixture.external/a"))
          (is (str/includes? (pr-str html2) ":fixture.external/b"))
          (is (reader-valid? ai2)))
        (testing "the isolated placeholder registry ignores live registry state"
          (is (= ai2 isolated-ai2))))

      (let [cap-ns 'fixture.cap
            cap-keys (mapv #(keyword "fixture.external" (str "k" %))
                           (range 41))
            cap-schemas
            (mapv (fn [schema-key]
                    {:seon.schema/key schema-key
                     :seon.schema/form ":string"})
                  cap-keys)
            long-doc (str (apply str (repeat 100 "x")) "…")
            cap-functions
            [(function-row
              cap-ns 'uncontracted
              "(defn uncontracted [] :ok)"
              {:seon.fn/doc long-doc})
             (function-row
              cap-ns 'with-many
              "(defn with-many [& values] values)"
              {:seon.fn/spec (pr-str [:=> (into [:cat] cap-keys) :any])})]
            _ (install-namespace! connection cap-ns
                                  "(ns fixture.cap)\n\n(def cap-source :only)"
                                  cap-schemas cap-functions)
            db @connection
            ai (sut/render-ai (namespace-unit db cap-ns 2 100000))]
        (testing "the closure emits forty references and one honest cap line"
          (is (= 40
                 (count
                  (filter #(= "fixture.external" (namespace %))
                          (keep :seon.schema/key (edn/read-string ai))))))
          (is (not (str/includes? ai "(register! :fixture.external/")))
          (is (= 1 (count (re-seq #"40\+ referenced schemas are reachable" ai)))))
        (testing "uncontracted public functions and their docs remain whole"
          (is (str/includes? ai "fixture.cap/uncontracted"))
          (is (str/includes? ai long-doc))
          (is (not (str/includes? ai " [clipped]"))))

        (let [honest-ns 'fixture.cap-honesty
              honest-spec
              (pr-str [:=> (into [:cat]
                                  (conj (subvec cap-keys 0 40)
                                        :z/missing))
                       :any])
              _ (install-namespace!
                 connection honest-ns "(ns fixture.cap-honesty)" []
                 [(function-row
                   honest-ns 'exactly-forty
                   "(defn exactly-forty [& values] values)"
                   {:seon.fn/spec honest-spec})])
              honest-ai
              (sut/render-ai (namespace-unit @connection honest-ns 2 100000))]
          (testing "the cap line requires a forty-first resolvable definition"
            (is (= 40
                   (count
                    (filter #(= "fixture.external" (namespace %))
                            (keep :seon.schema/key
                                  (edn/read-string honest-ai))))))
            (is (not (str/includes? honest-ai
                                    "40+ referenced schemas are reachable"))))))

      (let [budget-ns 'fixture.budget
            large-form
            (pr-str
             (into [:map]
                   (map (fn [index]
                          [(keyword (str "field-" index)) :string]))
                   (range 180)))
            budget-schemas
            [{:seon.schema/key :fixture.budget/own
              :seon.schema/form large-form}
             {:seon.schema/key :budget.external/a
              :seon.schema/form ":string"}
             {:seon.schema/key :budget.external/b
              :seon.schema/form large-form}]
            budget-functions
            [(function-row
              budget-ns 'a "(defn a [value] value)"
              {:seon.fn/spec (pr-str [:=> [:cat :budget.external/a] :any])})
             (function-row
              budget-ns 'b "(defn b [value] value)"
              {:seon.fn/spec (pr-str [:=> [:cat :budget.external/b] :any])})]
            _ (install-namespace! connection budget-ns "(ns fixture.budget)"
                                  budget-schemas budget-functions)
            db @connection
            budgets [64 96 128 192 256 384 512 768 1024 1536 2048
                     3072 4096 6144 8192]
            ai-candidate
            (some (fn [budget]
                    (let [text (sut/render-ai
                                (namespace-unit db budget-ns 2 budget))
                          rendered (edn/read-string text)
                          function-symbols
                          (into #{} (keep :seon.fn/sym) rendered)]
                      (when (and (contains? function-symbols "fixture.budget/a")
                                 (contains? function-symbols "fixture.budget/b")
                                 (not-any? :seon.schema/key rendered)
                                 (not-any? :seon.schema/form rendered))
                        {:budget budget :text text})))
                  budgets)]
        (testing "compact budgets admit the callable API before schemas"
          (is (map? ai-candidate))
          (when ai-candidate
            (is (some #(= :seon.print/elided (:seon.print/face %))
                      (edn/read-string (:text ai-candidate))))
            (is (<= (tokens/estimate (:text ai-candidate))
                    (:budget ai-candidate)))))))))

;; DELETED 2026-08-29 (owner gate ruling): compact registered-map
;; rendering is the filed issue
;; docs/seon/issues/registered-render-producers-fall-through-to-generic-map-rendering.md
;; and the S2 (render data) rebuild's seam — the wave restores designed
;; regressions; the doomed pins are parked, not polished.

