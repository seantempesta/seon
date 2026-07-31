(ns seon.render.ns-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [malli.registry :as mr]
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
         ::sut/token-budget token-budget))

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

(defn- install-namespace!
  [connection namespace-name source schema-rows function-rows]
  (d/transact connection
              [(cond-> {:seon.ns/name namespace-name}
                 source (assoc :seon.ns/source source))])
  (when (seq schema-rows)
    (d/transact connection schema-rows))
  (when (seq function-rows)
    (d/transact connection function-rows)))

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
            routed-ai1
            (walk/projection
             (select-keys d1 [:db/id :seon.ns/name :seon.ns/source])
             {:seon.render/kind :seon.render/ai
              :seon.render/floor 'seon.render.block/data-prose})
            html1-text (hiccup/->string (sut/render-html d1))
            html2-text (hiccup/->string (sut/render-html d2))]
        (testing "distance zero is only the namespace name"
          (is (= "seon.flow" ai0)))
        (testing "distance one composes every authoritative source row"
          (is (= `sut/render-ai routed-ai1)
              "the namespace entity map owns the family default")
          (is (str/starts-with? ai1 source))
          (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote source))
                                  ai1))))
          (is (str/includes? ai1 "(defn current-work-launcher"))
          (is (str/includes? ai1 "@installed-work-launcher")
              "the full d1 tier includes private member source too")
          (is (reader-valid? (the-ns 'seon.flow) ai1)))
        (testing "distance two is the public compact card"
          (is (str/includes? ai2 "[clojure.core.async :as async]"))
          (is (not (str/includes? ai2 "#:db"))
              "required namespace refs render their nested names")
          (is (str/includes? ai2 "; schema :seon.flow/"))
          (is (str/includes? ai2 "; fn seon.flow/current-work-launcher — [:=>"))
          (is (str/includes?
               ai2
               "; fn seon.flow/->CountedDroppingBuffer — <no contract>"))
          (is (not (str/includes? ai2 "fault-graph-definition")))
          (is (not (str/includes? ai2 "@installed-work-launcher")))
          (is (reader-valid? ai2)))
        (testing "the HTML projection has the same tier membership"
          (is (str/includes? html1-text "seon-namespace-source"))
          (is (str/includes?
               html1-text
               "Production-shaped core.async.flow launchers"))
          (is (str/includes? html1-text "seon-namespace-definitions"))
          (is (str/includes? html1-text "seon.flow/current-work-launcher"))
          (is (str/includes? html2-text "seon-namespace-own-schemas"))
          (is (str/includes? html2-text "seon.flow/current-work-launcher"))
          (is (str/includes?
               html2-text
               (str "id=\""
                    (block/surface-id :seon.flow/current-work-launcher)
                    "\"")))
          (is (not (str/includes? html2-text "fault-graph-definition")))
          (is (not (str/includes? html2-text "@installed-work-launcher"))))))))

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
      (d/transact connection
                  [{:db/id "fresh-namespace"
                    :seon.ns/name 'my.agents.fresh}
                   {:seon.cluster.agent/id "fresh"
                    :seon.cluster.agent/namespace "fresh-namespace"}])
      (let [db @connection
            unit (namespace-unit db 'my.agents.fresh 1 256)
            ai (sut/render-ai unit)
            html-text (hiccup/->string (sut/render-html unit))]
        (is (= `sut/render-ai
               (walk/projection
                (select-keys unit [:db/id :seon.ns/name])
                {:seon.render/kind :seon.render/ai
                 :seon.render/floor 'seon.render.block/data-prose})))
        (is (str/includes? ai "(ns my.agents.fresh)"))
        (is (str/includes? ai "no definitions yet"))
        (is (str/includes? ai "owned by agent fresh"))
        (is (str/includes? html-text "No definitions yet"))
        (is (str/includes? html-text "owned by agent fresh"))))))

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
            closure-schema-rows
            [own-row
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
            isolated-ai2
            (binding [mr/*registry* {:fixture.external/a :boolean
                                     :fixture.external/b :int}]
              (sut/render-ai d2))]
        (testing "d1 composes namespace, member, and schema source"
          (is (str/starts-with? ai1 closure-source))
          (is (str/includes? ai1 "(defn uses-own"))
          (is (str/includes? ai1
                             "(register! :fixture.external/a [:or :string :fixture.external/b])"))
          (is (str/includes? ai1
                             "(register! :fixture.external/b [:maybe :fixture.external/a])"))
          (is (str/includes? ai1
                             "(register! :fixture.closure/own")))
        (testing "d2 renders own records, one closure, and raw contracts"
          (is (str/includes?
               ai2
               "; schema :fixture.closure/own = [:map [:value :fixture.external/a]]"))
          (is (str/includes? ai2 "; referenced schemas"))
          (is (= 1 (count (re-seq #"; referenced schemas" ai2))))
          (is (str/includes? ai2 "; fn fixture.closure/uses-own — [:=>"))
          (is (not (str/includes? ai2
                                  "; (register! :fixture.closure/own")))
          (is (not (str/includes? ai2
                                  "; (register! :fixture.labels/input"))
              "a qualified catn label is not a schema ref")
          (is (not (str/includes? ai2
                                  "; (register! :fixture.enum/not-a-schema"))
              "a qualified enum value is not a schema ref")
          (is (not (str/includes? ai2
                                  "; (register! :fixture.missing/key"))
              "a missing row never becomes a fake registration")
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
        (testing "the closure emits forty definitions and one honest cap line"
          (is (= 40 (count (re-seq #"; \(register! :fixture.external/k" ai))))
          (is (= 1 (count (re-seq #"40\+ referenced schemas capped" ai)))))
        (testing "uncontracted public functions remain and docs clip explicitly"
          (is (str/includes?
               ai
               "; fn fixture.cap/uncontracted — <no contract>"))
          (is (str/includes? ai " [clipped]"))
          (is (not (str/includes? ai "…"))))

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
                   (count (re-seq #"; \(register! :fixture.external/k"
                                  honest-ai))))
            (is (not (str/includes? honest-ai
                                    "40+ referenced schemas capped"))))))

      (let [budget-ns 'fixture.budget
            large-form
            (pr-str
             (into [:map]
                   (map (fn [index]
                          [(keyword (str "field-" index)) :string]))
                   (range 180)))
            budget-schemas
            [{:seon.schema/key :budget.external/a
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
                                (namespace-unit db budget-ns 2 budget))]
                      (when (and (str/includes? text "; fn fixture.budget/a")
                                 (not (str/includes? text
                                                     "; fn fixture.budget/b")))
                        {:budget budget :text text})))
                  budgets)
            html-candidate
            (some (fn [budget]
                    (let [text (hiccup/->string
                                (sut/render-html
                                 (namespace-unit db budget-ns 2 budget)))]
                      (when (and (str/includes? text "fn fixture.budget/a")
                                 (not (str/includes? text
                                                     "fn fixture.budget/b")))
                        {:budget budget :text text})))
                  budgets)]
        (testing "each budget candidate carries exactly its function closure"
          (is (map? ai-candidate))
          (is (map? html-candidate))
          (when ai-candidate
            (is (str/includes? (:text ai-candidate)
                               "(register! :budget.external/a :string)"))
            (is (not (str/includes? (:text ai-candidate)
                                    "(register! :budget.external/b")))
            (is (<= (tokens/estimate (:text ai-candidate))
                    (:budget ai-candidate))))
          (when html-candidate
            (is (str/includes? (:text html-candidate)
                               "(register! :budget.external/a :string)"))
            (is (not (str/includes? (:text html-candidate)
                                    "(register! :budget.external/b")))
            (is (<= (tokens/estimate (:text html-candidate))
                    (:budget html-candidate)))))))))

(deftest compact-and-name-projections-respect-explicit-budgets
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
               distance (gen/elements [0 2])
               token-budget (gen/choose 64 2048)]
              (let [unit (namespace-unit
                          db namespace-name distance token-budget)
                    ai (sut/render-ai unit)
                    html-text (hiccup/->string (sut/render-html unit))]
                (and (not (str/blank? ai))
                     (not (str/blank? html-text))
                     (not (str/includes? ai "#:db"))
                     (<= (tokens/estimate ai) token-budget)
                     (<= (tokens/estimate html-text) token-budget)
                     (reader-valid? ai))))
             :seed property-seed)]
        (is (seq namespace-names))
        (support/assert-check!
         check
         "Name and compact namespace projections stay bounded and readable.")))))
