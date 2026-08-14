(ns seon.schema.datahike-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support])
  (:import [java.util.concurrent Callable FutureTask]))

(def ^:private schema-delta (schema/begin-registration-delta))

(schema/call-with-registration-delta
 schema-delta
 {:seon.schema.admission/source :core}
 #(schema/register! ::title :string))

(defn- fixture-projection
  []
  (schema/declaration-projection
   @(:seon.schema.delta/candidate-forms schema-delta)))

(use-fixtures
 :each
 (fn [test-body]
   (schema/call-with-registration-delta
    schema-delta
    {:seon.schema.admission/source :core}
    #(schema/call-with-projection (fixture-projection) test-body))))

(def ^:private scalar-generator
  (gen/elements
   [:string :int :double :float :keyword :boolean :inst :uuid :symbol]))

(def ^:private facet-generator
  (gen/let [indexed? gen/boolean
            no-history? gen/boolean
            uniqueness (gen/elements [nil :identity :value])]
    (cond-> {}
      indexed? (assoc :seon.db/index true)
      no-history? (assoc :seon.db/no-history? true)
      (= :identity uniqueness) (assoc :seon.db/identity true)
      (= :value uniqueness) (assoc :seon.db/unique true))))

(def ^:private supported-form-generator
  (gen/one-of
   [(gen/let [base scalar-generator
              properties facet-generator]
      {:base base :properties properties})
    (gen/let [head (gen/elements [:vector :set :sequential])
              child scalar-generator
              indexed? gen/boolean
              no-history? gen/boolean]
      {:base [head child]
       :properties
       (cond-> {}
         indexed? (assoc :seon.db/index true)
         no-history? (assoc :seon.db/no-history? true))})
    (gen/let [component? gen/boolean
              identity? gen/boolean]
      {:base [:set :seon.db/ref]
       :properties
       (cond-> {}
         component? (assoc :seon.db/component true)
         identity? (assoc :seon.db/identity true))})
    (gen/fmap (fn [base]
                {:base base :properties {:db.secondary/only true}})
              (gen/elements [:double :float]))
    (gen/return {:base [:or :string :int] :properties {}})]))

(defn- carry-properties
  [form properties]
  (if (empty? properties)
    form
    (if (vector? form)
      (into [(first form) properties] (rest form))
      [form properties])))

(defn- declarations
  [{:keys [base properties]}]
  (let [direct (carry-properties base properties)]
    (schema/register! ::direct direct)
    (schema/register! ::wrapped [:and properties base])
    (schema/register! ::alias-base direct)
    (schema/register! ::alias-middle ::alias-base)
    (schema/register! ::aliased ::alias-middle)
    (let [projection (fixture-projection)]
      (mapv #(dissoc (schema.datahike/malli->datahike-attr-in projection %)
                     :db/ident)
            [::direct ::wrapped ::aliased]))))

(deftest supported-ast-wrappers-and-aliases-have-one-declaration
  (support/assert-check!
   (tc/quick-check
    80
    (prop/for-all [generated supported-form-generator]
      (let [[direct wrapped aliased] (declarations generated)]
        (and (= direct wrapped aliased)
             (contains? direct :db/valueType)
             (contains? direct :db/cardinality)
             (contains? #{:db.cardinality/one :db.cardinality/many}
                        (:db/cardinality direct)))))
    :seed 202607280701)
   "supported schema AST equivalence"))

(deftest literal-schemas-derive-their-native-datahike-value-type
  (doseq [[literal expected]
          [[true :db.type/boolean]
           ["one" :db.type/string]
           [:one :db.type/keyword]
           ['one :db.type/symbol]
           [1 :db.type/long]
           [1.0 :db.type/double]]]
    (testing (pr-str literal)
      (schema/register! ::literal [:= literal])
      (is (= expected
             (:db/valueType
              (schema.datahike/malli->datahike-attr-in
               (fixture-projection) ::literal)))))))

(deftest schema-row-properties-lift-only-when-their-declarations-are-storable
  (let [forms {:seon.error/class [:= true]
               :gen/schema :seon.schema/definition
               :seon.error/message :string
               ::error
               [:map {:seon.error/class true
                      :gen/schema :string}
                [:seon.error/message :seon.error/message]]}
        projection {:seon.schema.projection/forms forms}
        attributes (set (schema.datahike/database-attributes-in projection))]
    (is (schema.datahike/storable-attribute-in?
         projection :seon.error/class))
    (is (not (schema.datahike/storable-attribute-in?
              projection :gen/schema)))
    (is (contains? attributes :seon.error/class))
    (is (not (contains? attributes :gen/schema)))))

(deftest database-attribute-derivation-resolves-the-population-once
  (let [without-bindings
        (fn [operation]
          ;; A raw Java task starts with no Clojure thread bindings, matching
          ;; the HTTP worker on which the live regression was measured.
          (let [task (FutureTask. ^Callable (fn [] (operation)))
                thread (Thread. task)]
            (.start thread)
            (.get task)))
        resource-reads
        (fn [operation]
          (let [reads (atom 0)
                read-one @#'schema.edn/read-schema-resource]
            (with-redefs [schema.edn/read-schema-resource
                          (fn [resource]
                            (swap! reads inc)
                            (read-one resource))]
              (let [value (without-bindings operation)]
                {:resource-reads @reads :value value}))))
        acquired (resource-reads schema.edn/packaged-forms)
        one-population (:resource-reads acquired)
        result
        (resource-reads
         #(schema/canonical-database-attributes (:value acquired)))]
    (testing "the bridge consumes one explicitly acquired population"
      (is (pos? one-population)
          "the explicit acquisition must read resources or the count is vacuous")
      (is (> (count (:value result)) 500)
          "the regression must exercise the production-wide attribute walk")
      (is (zero? (:resource-reads result))
          "one operation carries the supplied population through every question"))))

(def ^:private refused-form-generator
  (gen/elements
   [{:form [:maybe :string] :rule :nilable}
    {:form [:string {:db.secondary/only true}] :rule :secondary}
    {:form [:enum "not-a-keyword"] :rule :enum}
    {:form [:map-of :string :string] :rule :unstorable}]))

(deftest unsupported-database-attributes-refuse-at-one-rule
  (support/assert-check!
   (tc/quick-check
    40
    (prop/for-all [{:keys [form]} refused-form-generator]
      (let [data (try
                   (schema/register! ::refused form)
                   (schema.datahike/malli->datahike-attr-in
                    (fixture-projection) ::refused)
                   support/committed
                   (catch clojure.lang.ExceptionInfo error
                     (ex-data error)))]
        (and (map? data)
             (= :user-input (:seon.error/kind data)))))
    :seed 202607280702)
   "unsupported database attribute refusal"))

(deftest registered-shape-round-trips-through-datahike
  (let [projection
        (schema/build-projection
         {::title (schema/registration-delta-form schema-delta ::title)})]
    (support/with-database
      {:seon.test-support/extra-schema
       [(schema.datahike/malli->datahike-attr-in projection ::title)]}
      (fn [connection]
        (testing "derive, install, transact, and read through the public call shape"
          (db/transact! connection [{::title "Alpha"}])
          (is (= "Alpha"
                 (db/q '[:find ?title .
                         :where [_ ::title ?title]]
                       (db/db connection)))))))))

(deftest agent-authored-render-symbols-cross-the-transaction-function-codec
  ;; CLASS: transaction data returned by `:db.fn/call` used to bypass the one
  ;; logical-to-storage encoder. Agent-authored schema rows are built at that
  ;; seam, so coherent qualified render symbols reached Datahike's string-backed
  ;; render attributes raw and could never publish. Wrapping transaction-function
  ;; output in the same codec makes every returned heterogeneous slot cross the
  ;; one representation boundary exactly once.
  (support/with-database
   (fn [connection]
     (let [namespace-name 'my.agents.render-codec
           attribute :probe.render-codec/id
           shape :probe.render-codec/plan
           renderers
           {:seon.render/ai 'my.agents.render-codec/render-plan-ai
            :seon.render/html 'my.agents.render-codec/render-plan-html
            :seon.render/form 'my.agents.render-codec/render-plan-form}
           outputs
           {:seon.render/ai :seon.render/ai
            :seon.render/html :seon.render/html
            :seon.render/form :seon.render/form}
           row-tx (ns-resolve 'seon.cluster.run 'row-tx)
           transact-row!
           (fn [row]
             (db/transact!
              connection [[:db.fn/call row-tx {} row]]))
           attribute-form [:string {:seon.db/identity true}]
           argument-form [:map [attribute attribute]]]
       (db/transact! connection
                     [{:seon.ns/name namespace-name
                       :seon.ns/source (pr-str (list 'ns namespace-name))}])
       (transact-row!
        {:seon.schema/key attribute
         :seon.schema/form (pr-str attribute-form)})
       (doseq [[property renderer] renderers]
         (let [function-name (symbol (name renderer))]
           (transact-row!
            {:seon.fn/sym (str renderer)
             :seon.fn/ns [:seon.ns/name namespace-name]
             :seon.fn/source
             (pr-str
              (list 'defn function-name
                    {:malli/schema
                     [:=> [:cat argument-form] (get outputs property)]}
                    '[plan]
                    nil))
             :seon.fn/arglists "([plan])"
             :seon.fn/private? false
             :seon.fn/spec
             (pr-str [:=> [:cat argument-form] (get outputs property)])})))
       (transact-row!
        {:seon.schema/key shape
         :seon.schema/form
         (pr-str
          [:map
           (merge {:seon.db/attributes true} renderers)
           [attribute attribute]])})
       (db/transact! connection [{attribute "plan-1"}])
       (let [raw-row
             (d/pull @connection
                     [:seon.render/ai :seon.render/html :seon.render/form]
                     [:seon.schema/key shape])
             logical-row
             (db/pull @connection
                      [:seon.render/ai :seon.render/html :seon.render/form]
                      [:seon.schema/key shape])
             projection (schema/projection-from-database @connection)
             entity (db/pull @connection '[*] [attribute "plan-1"])
             selected-row
             (some #(when (= shape (:seon.schema/key %)) %)
                   (schema/matching-shapes-in projection entity))]
         (testing "storage uses the declared encoded representation"
           (is (= (update-vals renderers pr-str) raw-row)))
         (testing "database reads restore the logical qualified symbols"
           (is (= renderers logical-row)))
         (testing "cold acquisition preserves the declarations used by selection"
           (is (= renderers (select-keys selected-row (keys renderers))))))))))

(deftest encode-transaction-resolves-the-declaration-population-once
  ;; The class: the encode seam resolving the declaration population PER
  ;; ATTRIBUTE. With no population supplied on the calling thread,
  ;; `schema/declaration-population` falls through to
  ;; `seon.schema.edn/packaged-forms`, which re-reads and re-validates every
  ;; schema resource from the classpath (~14 ms). Per attribute that turned
  ;; `seon.cluster.work-test/situation-totality-property` into a suite wedge
  ;; that never finished inside the 300 s liveness backstop
  ;; (docs/prds/sci-execution-runtime/research/parallel-turns-hang-cause-2026-08-07.md).
  ;; One resolution per transaction is the wanted behavior, and it must not
  ;; grow with the transaction's attribute count or nesting depth.
  (let [resolutions (atom 0)
        real-declaration-population schema/declaration-population
        wide {:seon.cluster.agent/id "agent-a"
              :seon.cluster.message/id "m-1"
              :seon.cluster.message/content "do the thing"
              :seon.cluster.message/at (java.util.Date.)
              :seon.cluster.run/id "run-1"
              ::title "Alpha"}
        nested {:seon.cluster.agent/id "agent-b"
                :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.b}}]
    (with-redefs [schema/declaration-population
                  (fn []
                    (swap! resolutions inc)
                    (real-declaration-population))]
      (testing "a six-attribute transaction resolves the population once"
        (reset! resolutions 0)
        (schema.datahike/encode-transaction [wide])
        (is (= 1 @resolutions)))

      (testing "nesting does not add resolutions"
        (reset! resolutions 0)
        (schema.datahike/encode-transaction [nested])
        (is (= 1 @resolutions)))

      (testing "the argument-map transaction shape resolves once as well"
        (reset! resolutions 0)
        (schema.datahike/encode-transaction {:tx-data [wide nested]})
        (is (= 1 @resolutions)
            "resolution count is per transaction, never per entity")))))

(deftest edn-backed-attributes-have-one-canonical-datahike-round-trip
  (let [projection (schema/declaration-projection)
        branch (keyword "seon.test-support.fixture" "0")
        revision
        {:datahike.cache/connection-id
         [(java.util.UUID/fromString "05e76e86-dc27-4aa0-958a-a96274b83533")
          branch]
         :datahike.cache/generation
         (java.util.UUID/fromString "aa7bc82c-65c4-44a0-98df-87c7c798b13b")
         :datahike.read/attributes #{:seon.cluster.agent/id}
         :datahike.cache/attribute-revisions
         {:seon.cluster.agent/id
          (java.util.UUID/fromString "8d12cd47-3e5b-4d3e-9505-5dd72ee1cde9")}}
        encoded
        (get (first
              (schema.datahike/encode-transaction-in
               projection [{:datahike.read/revision revision}]))
             :datahike.read/revision)]
    (testing "reader-inexpressible identifiers remain exact"
      (is (= revision
             (schema.datahike/decode-attribute-value-in
              projection :datahike.read/revision encoded))
          "numeric branch keywords remain exact across the string storage seam"))

    (support/with-database
     (fn [connection]
       (let [where
             '[[?row :seon.call-preparation/key ?key]
               [?row :seon.call-preparation/schema ?schema]
               [?schema :seon.schema/key ?schema-key]
               [?schema :seon.schema/shape ?shape]
               [?shape :seon.schema.shape/fingerprint ?fingerprint]
               [?row :seon.call-preparation/supplier ?function]
               [?function :seon.fn/sym ?supplier]]
             attributes
             [:seon.call-preparation/key
              :seon.call-preparation/schema
              :seon.schema/key
              :seon.schema/shape
              :seon.schema.shape/fingerprint
              :seon.call-preparation/supplier
              :seon.fn/sym]
             ascending-set (into (sorted-set-by compare) attributes)
             descending-set
             (into (sorted-set-by (fn [left right] (compare right left)))
                   attributes)
             query
             (array-map
              :find '[?key ?schema-key ?fingerprint ?supplier]
              :in '[$]
              :where where)
             reversed-query
             (array-map
              :where where
              :in '[$]
              :find '[?key ?schema-key ?fingerprint ?supplier])
             request
             (array-map
              :seon.db/read-operation :q
              :seon.db/query-request
              (array-map :query query :args [:seon.db/database]))
             reversed-request
             (array-map
              :seon.db/query-request
              (array-map :args [:seon.db/database] :query reversed-query)
              :seon.db/read-operation :q)
             plan
             {:datahike.query.dependency/sources
              [(array-map
                :datahike.query.source/symbol '$
                :datahike.query.source/argument-position 0
                :datahike.query.source/attributes ascending-set)]}
             reversed-plan
             (array-map
              :datahike.query.dependency/sources
              [(array-map
                :datahike.query.source/attributes descending-set
                :datahike.query.source/argument-position 0
                :datahike.query.source/symbol '$)])
             receipt
             (fn [id read-request dependency-plan]
               {:seon.cluster.eval/id id
                :seon.cluster.eval/read-evidence
                [{:seon.db/source-argument-position 0
                  :datahike.read/dependency-plan dependency-plan
                  :datahike.read/revision
                  {:datahike.read/attributes :all
                   :datahike.read/cache-eligible? false}
                  :seon.db/read-request read-request}]})]
         (binding [*print-namespace-maps* false]
           (db/transact! connection [(receipt "codec-forward" request plan)]))
         (binding [*print-namespace-maps* true]
           (db/transact! connection
                         [(receipt "codec-reversed"
                                   reversed-request
                                   reversed-plan)]))
         (let [selector '[*]
               forward-raw
               (d/pull @connection selector
                       [:seon.cluster.eval/id "codec-forward"])
               reversed-raw
               (d/pull @connection selector
                       [:seon.cluster.eval/id "codec-reversed"])
               forward
               (binding [*print-namespace-maps* true]
                 (db/pull @connection selector
                          [:seon.cluster.eval/id "codec-forward"]))
               reversed
               (binding [*print-namespace-maps* false]
                 (db/pull @connection selector
                          [:seon.cluster.eval/id "codec-reversed"]))
               forward-raw-evidence
               (first (:seon.cluster.eval/read-evidence forward-raw))
               reversed-raw-evidence
               (first (:seon.cluster.eval/read-evidence reversed-raw))
               forward-evidence
               (first (:seon.cluster.eval/read-evidence forward))
               reversed-evidence
               (first (:seon.cluster.eval/read-evidence reversed))]
           (testing "the transaction codec emits one canonical representation"
             (is (= (:seon.db/read-request forward-raw-evidence)
                    (:seon.db/read-request reversed-raw-evidence)))
             (is (= (:datahike.read/dependency-plan forward-raw-evidence)
                    (:datahike.read/dependency-plan reversed-raw-evidence))))
           (testing "wildcard receipt reads restore both exact logical values"
             (is (= request (:seon.db/read-request forward-evidence)))
             (is (= request (:seon.db/read-request reversed-evidence)))
             (is (= plan (:datahike.read/dependency-plan forward-evidence)))
             (is (= plan
                    (:datahike.read/dependency-plan reversed-evidence))))))))))
