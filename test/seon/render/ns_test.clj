(ns seon.render.ns-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [malli.registry :as mr]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.ns :as sut]
            [seon.test-support :as support])
  (:import [java.io PushbackReader StringReader]))

(def ^:private property-seed 2026073102)

(deftest identity-reads-stewardship-without-an-agent-cluster-attribute
  (support/with-database
   (fn [connection]
     (db/transact! connection
                   [{:db/id "steward" :seon.agent/id "record-steward"}
                    {:db/id "namespace" :seon.ns/name 'my.agents.record
                     :seon.ns/steward "steward"}
                    {:seon.agent/id "record-agent"
                     :seon.agent/namespace "namespace"}])
     (let [unit {:seon.db/db @connection
                 :seon.agent/id "record-agent"}
           source (agent/render-identity-ai unit)
           form (edn/read-string source)
           selector (second (second form))
           observed (db/pull @connection selector (nth form 2))]
       (is (= "record-agent" (:seon.agent/id observed)))
       (is (= 'my.agents.record
              (get-in observed [:seon.agent/namespace :seon.ns/name])))
       (is (= "record-steward"
              (get-in observed [:seon.agent/namespace :seon.ns/steward
                                :seon.agent/id])))
       (is (not (str/includes? source ":seon.agent/cluster")))))))

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

(deftest bounded-read-refusals-remain-flat-errors
  (support/with-database
    (fn [connection]
      (let [effective (support/effective-config)
            caps (assoc (config/result-caps effective)
                        :seon.config.eval.result/max-nodes 1
                        :seon.config.eval.result/max-collection 1)
            unit (assoc (namespace-unit @connection 'seon.flow 1 100000)
                        :seon.sci.admit/caps caps)
            ai (sut/render-ai unit)
            html (sut/render-html unit)]
        (is (= ai html)
            "both projections return the same refusal from the owning read")
        (is (= :seon.db/invalid-read (:seon.error/kind ai)))
        (is (string? (:seon.error/message ai)))))))

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

(deftest fitted-full-html-leads-with-description-and-function-summaries
  (support/with-database
    (fn [connection]
      (let [namespace-name 'fixture.presentation
            required-names
            (mapv #(symbol (str "fixture.required." %)) (range 16))
            source (str "(ns fixture.presentation)\n"
                        (apply str (repeat 4000 "source-body "))
                        "source-tail-marker")]
        (install-namespace!
         connection namespace-name source []
         [(function-row
           namespace-name 'alpha "(defn alpha [value] value)"
           {:seon.fn/spec "[:=> [:cat :string] :string]"
            :seon.fn/doc "Return the first useful value."})
          (function-row
           namespace-name 'useful "(defn useful [value] value)"
           {:seon.fn/spec "[:=> [:cat :string] :string]"
            :seon.fn/doc "Return the useful value."})])
        (db/transact!
         connection
         (conj
          (mapv (fn [required-name] {:seon.ns/name required-name})
                required-names)
          {:seon.ns/name namespace-name
           :seon.ns/doc
           "Useful namespace summary.\n\nLonger stewardship text."
           :seon.ns/requires
           (mapv (fn [required-name] [:seon.ns/name required-name])
                 required-names)}))
        (let [raw (sut/render-html
                   (namespace-unit @connection namespace-name 1 100000))
              raw-html (hiccup/->string raw)
              profile (assoc (render/agent-render-profile
                              (support/effective-config))
                             :seon.render.profile/token-budget 1024
                             :seon.render.profile/max-depth 8
                             :seon.render.profile/max-children 32
                             :seon.render.profile/composition :multiline
                             :seon.print/requery-id
                             [:seon.ns/name namespace-name])
              fitted-html
              (hiccup/->string (sut/render-html
                               (assoc (namespace-unit @connection namespace-name 1 100000)
                                      :seon.render/profile profile)))]
          (is (< (.indexOf raw-html "Useful namespace summary.")
                 (.indexOf raw-html "fixture.presentation/useful")
                 (.indexOf raw-html "Requires")
                 (.indexOf raw-html "namespace source"))
              "callable summaries precede requires and source")
          (is (and (str/includes? fitted-html "Useful namespace summary.")
                   (str/includes? fitted-html "fixture.presentation/alpha")
                   (str/includes? fitted-html "fixture.presentation/useful")
                   (str/includes? fitted-html "Return the useful value."))
              "the final fitted HTML retains the summary and callable API")
          (is (and (str/includes? raw-html "namespace source")
                   (str/includes? raw-html "member definition sources")
                   (str/includes? raw-html "(defn useful [value] value)")
                   (not (str/includes? raw-html "<details open")))
              "exact namespace and member source remain in disclosures")
          (is (and (not (str/includes? fitted-html "seon-print-html-elision"))
                   (str/includes? fitted-html "source-tail-marker"))
              "HTML preserves every source byte regardless of the AI profile"))))))

(deftest source-less-agent-namespace-routes-to-the-full-stub
  ;; AN EMPTY AGENT NAMESPACE IS THE ORDINARY FIRST MOMENT OF EVERY AGENT,
  ;; and this used to render `{:seon.error/message …}` straight into that
  ;; agent's own context. The wanted shape is an ordinary Clojure comment for
  ;; AI and ordinary prose for HTML — never an error value.
  (support/with-database
    (fn [connection]
      (db/transact! connection
                  [{:db/id "fresh-namespace"
                    :seon.ns/name 'my.agents.fresh
                    :seon.ns/steward "fresh-agent"}
                   {:db/id "fresh-agent"
                    :seon.agent/id "fresh"
                    :seon.agent/namespace "fresh-namespace"}])
      (let [db @connection
            unit (namespace-unit db 'my.agents.fresh 1 256)
            ai (sut/render-ai unit)
            html-text (hiccup/->string (sut/render-html unit))]
        (is (str/includes? ai "(ns my.agents.fresh)"))
        (is (str/includes? ai ";; No definitions are indexed in this namespace yet"))
        (is (str/includes? ai "it belongs to agent fresh"))
        (is (not (str/includes? ai ":seon.error/message"))
            "the empty state is never an error value in the agent's context")
        (is (str/includes? html-text
                           "No definitions are indexed in this namespace yet"))
        (is (str/includes? html-text "it belongs to agent fresh"))))))

(deftest namespace-bindings-render-in-clojures-own-words
  (testing "an alias names its target namespace, a refer names its Var"
    (is (= [:article {:class "seon-family-entry seon-namespace-alias-entry"}
            [:p {:class "seon-kicker"} "Namespace alias"]
            [:p [:code "str"] " → " [:code "clojure.string"]]
            [:details {:class "seon-namespace-binding-data"}
             [:summary "libspec"]
             [:pre [:code "[clojure.string :as str]"]]]]
           (sut/render-alias-html
            {:seon.ns.alias/local 'str
             :seon.ns.alias/target-ns 'clojure.string}))
        "the raw libspec sits under disclosure, not in the sentence")
    (is (= [:article {:class "seon-family-entry seon-namespace-refer-entry"}
            [:p {:class "seon-kicker"} "Namespace refer"]
            [:p [:code "q"] " ← " [:code "seon.db/q"]]
            [:details {:class "seon-namespace-binding-data"}
             [:summary "libspec"]
             [:pre [:code "[seon.db :refer [q]]"]]]]
           (sut/render-refer-html
            {:seon.ns.refer/local 'q
             :seon.ns.refer/target-ns 'seon.db
             :seon.ns.refer/target-name 'q})))
    (is (= [:article {:class "seon-family-entry seon-namespace-import-entry"}
            [:p {:class "seon-kicker"} "Namespace import"]
            [:p [:code "Date"] " → " [:code "java.util.Date"]]
            [:details {:class "seon-namespace-binding-data"}
             [:summary "import"]
             [:pre [:code "java.util.Date"]]]]
           (sut/render-import-html
            {:seon.ns.import/local 'Date
             :seon.ns.import/target-class 'java.util.Date}))))
  (testing "each AI projection is source: a teaching comment, then a form"
    (is (= (str ";; Here `str` is an alias for clojure.string, so `str/name`"
                " reads a Var\n"
                ";; in that namespace — the `:as` half of"
                " [clojure.string :as str].\n"
                "(dir (quote clojure.string))")
           (sut/render-alias-ai {:seon.ns.alias/local 'str
                                 :seon.ns.alias/target-ns 'clojure.string})))
    (is (= (str ";; Here the bare symbol `q` is seon.db/q —\n"
                ";; the `:refer` half of [seon.db :refer [q]].\n"
                "(doc seon.db/q)")
           (sut/render-refer-ai {:seon.ns.refer/local 'q
                                 :seon.ns.refer/target-ns 'seon.db
                                 :seon.ns.refer/target-name 'q})))))

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
            rendered (mapv #(sut/render-ai
                              (namespace-unit db budget-ns 2 %)) budgets)]
        (testing "the namespace renderer preserves declared data for the value projection"
          (is (apply = rendered))
          (let [rows (edn/read-string (first rendered))]
            (is (= #{"fixture.budget/a" "fixture.budget/b"}
                   (into #{} (keep :seon.fn/sym) rows)))
            (is (some :seon.schema/key rows))))))))

;; DELETED 2026-08-29 (owner gate ruling): compact registered-map
;; rendering is the filed issue
;; docs/seon/issues/registered-render-producers-fall-through-to-generic-map-rendering.md
;; and the S2 (render data) rebuild's seam — the wave restores designed
;; regressions; the doomed pins are parked, not polished.

(deftest namespace-alias-renders-local-and-target-roles
  (let [alias-row {:seon.ns.alias/local 'str
                   :seon.ns.alias/target-ns 'clojure.string}
        same-name {:seon.ns.alias/local 'seon.fn
                   :seon.ns.alias/target-ns 'seon.fn}
        html (sut/render-alias-html alias-row)]
    ;; THE AI PROJECTION IS SOURCE (owner ruling 2026-09-07): teaching
    ;; comments, then a form the agent can rerun. It stopped being a bare
    ;; quoted libspec, which told the agent nothing it could act on.
    (is (str/includes? (sut/render-alias-ai alias-row)
                       "(dir (quote clojure.string))"))
    (is (str/includes? (sut/render-alias-ai same-name)
                       "(dir (quote seon.fn))"))
    (is (str/includes? (hiccup/->string html) "<code>str</code> → <code>clojure.string</code>"))
    (is (str/includes? (hiccup/->string html)
                       "[clojure.string :as str]"))))
