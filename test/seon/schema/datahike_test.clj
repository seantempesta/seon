(ns seon.schema.datahike-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.db :as db]
            [seon.env :as env]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.schema.internal :as internal]
            [seon.test-support :as support]))

(deftest error-members-cannot-carry-another-entitys-upsert-identity
  (support/with-database
   (fn [_connection]
     (let [projection (schema/handed-projection)]
       (doseq [optional? [false true]]
         ;; an identity-bearing entity declares its partition (8a069b5e4), so
         ;; the upsert-identity rule is the one that refuses
         (let [definition [:and {:seon.program/partition :seon.data} :seon.error/base
                           [:map [:seon.turn/id {:optional optional?} :seon.turn/id]]]
               refusal (try
                         (schema/projection-with-schema
                          projection ::identity-observation definition
                          {:seon.schema.admission/source :core})
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
           (is (= ::identity-observation (:seon.schema/identity refusal)))
           (is (= :seon.turn/id (:seon.schema/member refusal)))))))))

(deftest optional-unstorable-property-members-remain-in-memory
  (support/with-database
   (fn [connection]
     (let [forms (:seon.schema.projection/forms (schema/handed-projection))
           property (fn [optional?]
                   [:and {:seon.db/attributes true} :seon.error/base
                    [:map [::subject ::subject]
                     [:seon.error/offending {:optional optional?} :seon.schema/value]]])
           candidate (assoc forms ::subject :string ::observation (property true))
           projection (schema/build-projection candidate)
           value {:seon.error/at (java.util.Date.)
                  :seon.error/layer ::bridge
                  :seon.error/operation 'seon.schema.datahike-test/optional-unstorable-property-members-remain-in-memory
                  ::subject "producer"
                  :seon.error/offending (Object.)}]
       (is ((schema/projection-validator projection ::observation) value))
       (support/transacted!
        connection
        (into [(schema.datahike/malli->datahike-attr-in projection ::subject)]
              (schema/canonical-schema-entities projection
                                            (select-keys candidate [::subject ::observation]))))
       (is (some? (db/pull (db/db connection) [:seon.schema/key]
                          [:seon.schema/key ::observation])))
       (is (not (some #{:seon.error/offending}
                      (schema.datahike/database-attributes-in projection))))
       (is (not (some #{:seon.error/offending}
                      (::schema.datahike/attributes
                       (#'schema.datahike/compiled-attribute-selection projection)))))
       (let [refusal (try
                       (schema/build-projection (assoc candidate ::observation (property false)))
                       nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :seon.error/offending (:seon.schema/member refusal))))))))

(deftest canonical-population-native-parity
  ;; No hand list beside the schema: the live projection's native population
  ;; is checked by properties DERIVED from that projection, and against the one
  ;; recorded baseline, the historical admitted population (root ruling
  ;; 2026-09-27: the current-section copy was 788 forms stale).
  (support/with-database
   (fn [_connection]
     (let [projection (schema/handed-projection)
           forms (:seon.schema.projection/forms projection)
           selection (#'schema.datahike/compiled-attribute-selection projection)
           core (::schema.datahike/core selection)
           properties (::schema.datahike/properties selection)
           attributes (::schema.datahike/attributes selection)
           native (mapv #(schema.datahike/malli->datahike-attr-in projection %) attributes)
           initial (:seon.bridge.parity/initial-baseline
                    (edn/read-string (slurp (io/resource "seon/schema/datahike_parity.edn"))))
           ;; The previously admitted historical population, built by the one
           ;; constructor. Its forms that current admission refuses (129 of
           ;; 3,277, lane delete-duplicates 2026-09-25) were dropped with their
           ;; attributes and native entities; the rest is the original oracle.
           initial-projection (schema/build-projection (:seon.bridge.parity/forms initial))
           initial-attributes (schema.datahike/database-attributes-in initial-projection)
           initial-native (into {} (map (juxt :db/ident identity)) (:seon.bridge.parity/native initial))
           actual-native (into {} (map (juxt :db/ident identity)) native)
           ;; An attribute whose form is unchanged since the baseline keeps the
           ;; baseline's native declaration.
           unchanged (filterv #(and (contains? initial-native %)
                                    (= (find (:seon.bridge.parity/forms initial) %) (find forms %)))
                              attributes)
           drifted (into (sorted-map)
                         (keep (fn [k] (when (not= (initial-native k) (actual-native k))
                                         [k {:baseline (initial-native k) :actual (actual-native k)}])))
                         unchanged)]
       (testing "the recorded historical population remains an independent oracle"
         (is (seq (:seon.bridge.parity/native initial)))
         (is (true? (= (:seon.bridge.parity/attributes initial) initial-attributes)))
         (is (true? (= (:seon.bridge.parity/native initial)
                       (schema.datahike/malli->datahike-schema-in initial-projection initial-attributes)))))
       (testing "the live projection's native population, derived two ways, agrees"
         (is (seq forms))
         (is (seq attributes))
         (is (= (set attributes) (into (set core) properties)) "every attribute is core or a property")
         (is (empty? (set/intersection (set core) (set properties))))
         (is (= (set attributes) (set (schema.datahike/database-attributes-in projection))))
         (is (= attributes (mapv :db/ident native)) "one native declaration per attribute, in order")
         (is (every? #(and (:db/valueType %) (:db/cardinality %)) native))
         (is (true? (= native (schema.datahike/malli->datahike-schema-in projection attributes)))
             "Ordered native declarations"))
       (testing "an attribute unchanged since the baseline keeps its native declaration"
         (is (seq unchanged))
         (is (empty? drifted)))))))

(deftest compiled-entity-composition-keeps-scope-and-entry-boundaries
  (support/with-database
   (fn [_connection]
     (let [projection (schema/handed-projection)
           options (:seon.schema.projection/compile-options projection)
           compile-node #(m/schema % options)
           entity (compile-node
                   [:and [:map [::required :int]]
                    [:map {:gen/elements [{::payload [:map [::leak :string]]}]}
                     [::nested [:map [::not-an-entity-entry :string]]]
                     [::optional {:optional true} :string]]])
           entries (internal/entity-entries entity)]
       (is (= [::required ::nested ::optional] (mapv first entries)))
       (is (= [false false true] (mapv #(true? (:optional (second %))) entries)))
       (is (empty? (internal/entity-entries
                    (compile-node [:or [:map [::left :int]] [:map [::right :int]]]))))
       (is (thrown? clojure.lang.ExceptionInfo
                    (internal/entity-entries
                     (compile-node [:and [:map [::required :int]]
                                    [:map [::required {:optional true} :int]]]))))
       (is (thrown? clojure.lang.ExceptionInfo
                    (internal/entity-entries
                     (compile-node
                      [:and
                       [:schema {:registry {::local :int}} [:map [::same ::local]]]
                       [:schema {:registry {::local :string}} [:map [::same ::local]]]]))))
       (let [recursive [:schema
                        {:registry {::node [:map [::next {:optional true} [:ref ::node]]]}}
                        [:map [::same [:ref ::node]]]]]
         (is (= [::same]
                (mapv first (internal/entity-entries
                             (compile-node [:and recursive recursive]))))))
       (doseq [k [:seon.ns/ns :seon.error.occurrence/occurrence :my.fs/error]]
         (is (seq (internal/entity-entries
                   (mr/schema (:seon.schema.projection/registry projection) k)))
             "Canonical entity exists"))))))

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

(def ^:private property-generator
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
              properties property-generator]
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

(def ^:private core-admission {:seon.schema.admission/source :core})

(defn- admitted
  "The fixture projection with each `[key form]` admitted in order through
   `projection-with-schema`, the choke point `register!` admits through; the
   fixture's population stays fixed, so its complete base is built once."
  [declarations]
  (reduce (fn [projection [k form]]
            (schema/projection-with-schema projection k form core-admission))
          (fixture-projection)
          declarations))

(defn- declarations
  [{:keys [base properties]}]
  (let [direct (carry-properties base properties)
        projection (admitted [[::direct direct]
                              [::wrapped [:and properties base]]
                              [::alias-base direct]
                              [::alias-middle ::alias-base]
                              [::aliased ::alias-middle]])]
    (mapv #(dissoc (schema.datahike/malli->datahike-attr-in projection %)
                   :db/ident)
          [::direct ::wrapped ::aliased])))

(deftest ^{:seon.test/long "Eighty generated cases each admit a five-declaration chain through projection-with-schema: measured 4,163 ms for eighty chains in a fresh snapshot JVM and ~560 ms warm on default (7 ms per chain), 2026-09-27."
           :seon.test/long-ms 10000}
  supported-ast-wrappers-and-aliases-have-one-declaration
  (support/assert-check!
   (tc/quick-check
    80
    (prop/for-all [generated supported-form-generator]
      (let [[direct wrapped aliased] (declarations generated)]
        ;; An alias does not inherit :db/unique (26f1f40d6); it keeps
        ;; every other property of the declaration it names.
        (and (= direct wrapped)
             (= (dissoc direct :db/unique) aliased)
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
      (is (= expected
             (:db/valueType
              (schema.datahike/malli->datahike-attr-in
               (admitted [[::literal [:= literal]]]) ::literal)))))))

(deftest registration-admits-over-a-handed-declaration-projection
  ;; CLASS: a replacement over an incomplete base. A declaration projection
  ;; holds forms and registry only; `register!` admitting over it read a nil
  ;; contract map (`declaration-changes` refused argument 0 `before`) and
  ;; would have kept dependents compiled against the replaced definition.
  (let [delta (schema/begin-registration-delta (fixture-projection))]
    (is (= ::literal
           (schema/call-with-registration-delta
            delta core-admission #(schema/register! ::literal [:= true]))))
    (is (= [:= true] (schema/registration-delta-form delta ::literal))))
  (let [projection (admitted [[::base-shape :string]
                              [::referrer [:vector ::base-shape]]
                              [::base-shape :int]])]
    (is (= :db.type/long
           (:db/valueType
            (schema.datahike/malli->datahike-attr-in projection ::referrer)))
        "a dependent recompiles against the replaced definition")))

(deftest schema-entity-properties-lift-only-when-their-declarations-are-storable
  (let [forms {:seon.config/dial :boolean
               :gen/schema :seon.schema/definition
               :seon.error/message :string
               ::error
               [:map {:seon.config/dial true
                      :gen/schema :string}
                [:seon.error/message :seon.error/message]]}
        projection (schema/build-projection
                    (merge (:seon.schema.projection/forms (schema/handed-projection)) forms))
        attributes (set (schema.datahike/database-attributes-in projection))]
    (is (schema.datahike/storable-attribute-in?
         projection :seon.config/dial))
    (is (not (schema.datahike/storable-attribute-in?
              projection :gen/schema)))
    (is (contains? attributes :seon.config/dial))
    (is (not (contains? attributes :gen/schema)))))

(deftest a-schema-absent-from-the-projection-refuses-by-name
  ;; CLASS: ambiguous nil. `mr/schema` answers nil for an undeclared key, and
  ;; Malli then refused an anonymous `{:schema nil}`; the refusal names the key.
  (let [projection (schema/build-projection {::present [:map {:seon.config/dial true}]
                                             :seon.config/dial :boolean})
        refusal (try (schema.datahike/storable-properties-in projection ::absent)
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= ::absent (:seon.schema/missing-reference refusal)))
    (is (= {:seon.config/dial true}
           (schema.datahike/storable-properties-in projection ::present)))))

(deftest a-reference-property-is-stored-as-its-effective-function
  ;; CLASS: the lift stored a schema row's ROOT properties only: a property
  ;; declared on an :and arm or inherited through a reference was absent from
  ;; its row, and a property naming a function was a value no writer could
  ;; check. A property whose attribute declares :seon.fn/reference-to is now
  ;; the nearest declaration along Malli's reference chain, stored as a ref to
  ;; that function by identity; every other property stays the row's own.
  (let [named (fn [f] {:seon.fn/sym f})
        projection (schema/build-projection
                    (merge (schema.edn/packaged-forms)
                           {::renders [:and {:seon.fn/reference-to :seon.fn/sym} :seon.db/ref]
                            ::shown [:and {:seon.fn/reference-to :seon.fn/sym} :seon.db/ref]
                            ::base [:map {::renders 'probe.render/base ::shown 'probe.render/base-shown}
                                    [::x ::x]]
                            ::x :int
                            ::inherits [:and ::base [:map [::x ::x]]]
                            ::arm [:and ::base [:map {::renders 'probe.render/arm} [::x ::x]]]
                            ::alias ::arm
                            ::plain [:map [::x ::x]]}))
        lifted #(select-keys (schema.datahike/storable-properties-in projection %) [::renders ::shown])]
    (is (= {::renders (named 'probe.render/base) ::shown (named 'probe.render/base-shown)} (lifted ::inherits)))
    (is (= {::renders (named 'probe.render/arm) ::shown (named 'probe.render/base-shown)}
           (lifted ::arm) (lifted ::alias))
        "the later :and arm wins per property; an alias reads its target")
    (is (= {} (lifted ::plain)))))

(deftest a-changed-property-attribute-re-derives-the-rows-that-lift-it
  ;; CLASS: a retyped render face changed every schema row that declares or
  ;; inherits it, but a partial publication re-derived only the schemas whose
  ;; FORMS reach the changed key, so 204 rows kept no face after the retype.
  (let [projection (schema/build-projection
                    (merge (schema.edn/packaged-forms)
                           {::renders [:and {:seon.fn/reference-to :seon.fn/sym} :seon.db/ref]
                            ::base [:map {::renders 'probe.render/base} [::x ::x]]
                            ::x :int
                            ::inherits [:and ::base [:map [::x ::x]]]
                            ::alias ::inherits
                            ::plain [:map [::x ::x]]}))]
    (is (= #{::base ::inherits ::alias}
           (schema.datahike/property-dependents-in projection #{::renders})))
    (is (= #{} (schema.datahike/property-dependents-in projection #{::x}))
        "an attribute no schema lifts as a property re-derives nothing")))

(deftest a-write-encodes-a-slot-by-its-installed-attribute
  ;; CLASS: boot refused the render-face retype (23991131e): the publication
  ;; installed the faces as refs, then its rows were encoded by the store's
  ;; previous declarations (EDN strings), refusing the ref-shaped values. The
  ;; installed attribute decides a slot's storage, as installed cells do.
  (let [projection (schema/build-projection
                    (merge (schema.edn/packaged-forms)
                           {::id [:string {:seon.db/identity true}]
                            ::face [:or :qualified-symbol :string]}))
        row {::id "probe" ::face {:seon.fn/sym 'probe.render/view}}
        installed (assoc projection ::schema.datahike/installed-schema
                         {::face {:db/valueType :db.type/ref}})
        encoded (schema.datahike/encode-transaction-in installed [row])]
    (is (= {:seon.fn/sym 'probe.render/view} (get-in encoded [0 ::face]))
        "an installed ref keeps the nested identity map Datahike links")
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema.datahike/encode-transaction-in projection [row]))
        "declared EDN with no installed schema supplied still refuses a map")))

(deftest a-transaction-function-error-refuses-as-itself
  ;; CLASS: an error value a transaction function answered (seon.db/vectors-tx
  ;; under its armed contract) was encoded as transaction data, and the second
  ;; refusal hid the first.
  (let [projection (schema/build-projection (schema.edn/packaged-forms))
        error {:seon.error/at (java.util.Date. 0) :seon.error/layer ::probe
               :seon.error/operation 'probe/tx :seon.error/message "first cause"}
        thrown (try (#'schema.datahike/encode-call-output-in nil projection nil (fn [_] error))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (= error (ex-data thrown)))
    (is (= "first cause" (ex-message thrown)))))

(deftest compiled-storage-navigation-reuses-retained-roots
  ;; Navigation reads the projection's retained compiled roots: every lookup
  ;; answers the same compiled object, so selecting and deriving the storable
  ;; attributes again compiles nothing new and answers the same declarations.
  (support/with-database
   (fn [_connection]
     (let [projection (schema/handed-projection)
           registry (:seon.schema.projection/registry projection)
           select-attributes #(#'schema.datahike/compiled-attribute-selection projection)
           attributes (::schema.datahike/attributes (select-attributes))
           derive-native #(mapv (partial schema.datahike/malli->datahike-attr-in projection) attributes)
           expected (derive-native)]
       (is (seq attributes))
       (is (= (count attributes) (count expected)))
       (is (= [] (into []
                       (remove #(identical? (mr/schema registry %) (mr/schema registry %)))
                       (keys (:seon.schema.projection/forms projection))))
           "every declaration's root is one retained compiled object")
       (dotimes [_ 3]
         (is (= attributes (::schema.datahike/attributes (select-attributes))))
         (is (= expected (derive-native))))))))

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
                   (schema.datahike/malli->datahike-attr-in
                    (admitted [[::refused form]]) ::refused)
                   support/committed
                   (catch clojure.lang.ExceptionInfo error
                     (ex-data error)))]
        (and (map? data)
             (= ::refused (or (:seon.schema.datahike/attr data)
                              (:seon.schema/nilable-value-schema data))))))
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
          (support/transacted! connection [{:seon.ns/name 'seon.schema ::title "Alpha"}])
          (is (= "Alpha"
                 (db/q '[:find ?title .
                         :where [_ ::title ?title]]
                       (db/db connection)))))))))

(deftest ^{:seon.test/long "Acquire the canonical fixture and publish three analyzed renderer contracts and their schema through the real transaction codec."
           :seon.test/long-ms 120000}
  agent-authored-render-symbols-cross-the-transaction-function-codec
  ;; CLASS: transaction data returned by `:db.fn/call` used to bypass the one
  ;; logical-to-storage encoder. Agent-authored schema entities are built at that
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
           {:seon.render/ai :string
            :seon.render/html :seon.render/hiccup
            :seon.render/form :seon.render/read-form}
           entity-tx (ns-resolve 'seon.turn 'row-tx)
           transact-entity!
           (fn [entity-map]
             (support/transacted!
              connection [[:db.fn/call entity-tx {:seon.turn/declarations-preceding? true} entity-map]])
             (let [database (db/db connection)
                   state (:seon.sci.eval/projection-state (meta database))]
               (env/advance-projection!
                state (db/basis-t database)
                (schema/projection-from-database database))))
           attribute-form [:string {:seon.db/identity true}]
           argument-form [:map [attribute attribute]]]
       (support/transacted! connection
                            [{:seon.ns/name namespace-name
                              :seon.ns/source (pr-str (list 'ns namespace-name))}])
       (transact-entity!
        {:seon.schema/key attribute
         :seon.schema/form (pr-str attribute-form)})
       (doseq [[property renderer] renderers]
         (let [function-name (symbol (name renderer))
               entity-map (support/program-fn-entity
             (db/db connection) renderer
             (pr-str
              (list 'defn function-name
                    {:malli/schema [:=> [:cat argument-form] (get outputs property)]}
                    '[plan] nil)))]
           (is (string? (:seon.fn/spec entity-map)) "Analyzed renderer contract")
           (transact-entity! entity-map)
           (is (string? (:seon.fn/spec
                         (db/pull (db/db connection) [:seon.fn/spec] [:seon.fn/sym renderer])))
               "Stored renderer contract")))
       (transact-entity!
        {:seon.schema/key shape
         :seon.schema/form
         (pr-str
          [:map
           (merge {:seon.db/attributes true :seon.program/partition :seon.data} renderers)
           [attribute attribute]])})
       (support/transacted! connection [{attribute "plan-1"}])
       (let [raw-entity
             (d/pull (db/db connection)
                     [:seon.render/ai :seon.render/html :seon.render/form]
                     [:seon.schema/key shape])
             logical-entity
             (db/pull (db/db connection)
                      [:seon.render/ai :seon.render/html :seon.render/form]
                      [:seon.schema/key shape])
             projection (schema/projection-from-database (db/db connection))
             entity (db/pull (db/db connection) '[*] [attribute "plan-1"])
             selected-entity
             (some #(when (= shape (:seon.schema/key %)) %)
                   (schema/matching-shapes-in projection entity))]
         (testing "each face is stored as a ref to its function's entity"
           (is (= (update-vals renderers
                               #(select-keys (d/pull (db/db connection) [:db/id] [:seon.fn/sym %]) [:db/id]))
                  raw-entity)))
         (testing "a database read answers the ref, never a symbol to re-resolve"
           (is (= raw-entity logical-entity)))
         (testing "cold acquisition preserves the declarations used by selection"
           (is (= renderers (select-keys selected-entity (keys renderers))))))))))

(deftest encode-transaction-resolves-the-declaration-population-once
  ;; The class: the encode seam resolving the declaration population PER
  ;; ATTRIBUTE. Before 3d4aaad32 an unhanded resolution fell through to
  ;; `seon.schema.edn/packaged-forms`, which re-read every schema resource
  ;; (~14 ms); per attribute that wedged `seon.turn-work-test`
  ;; (docs/prds/sci-execution-runtime/research/parallel-turns-hang-cause-2026-08-07.md).
  ;; One resolution per transaction is the wanted behavior, and it must not
  ;; grow with the transaction's attribute count or nesting depth.
  ;; Observed, never redefined: the armed wrapper's own profile cell counts the
  ;; calls (`seon.profile`), so the JVM-wide Var stays the real function.
  (let [cell (:seon.instrument/cell (meta @#'schema/declaration-population))
        calls #(:seon.profile/calls (profile/observation cell))
        resolutions (fn [transaction]
                      (let [before (calls)]
                        (schema.datahike/encode-transaction transaction)
                        (- (calls) before)))
        wide {:seon.agent/id "agent-a" :seon.message/id "m-1" :seon.message/content "do the thing" :seon.turn/id "run-1" ::title "Alpha"}
        nested {:seon.agent/id "agent-b"
                :seon.agent/namespace {:seon.ns/name 'my.agents.b}}]
    (is (some? cell) "the entering program arms declaration-population with its profile cell")
    (when cell
      (testing "a six-attribute transaction resolves the population once"
        (is (= 1 (resolutions [wide]))))
      (testing "nesting does not add resolutions"
        (is (= 1 (resolutions [nested]))))
      (testing "the argument-map transaction shape resolves once as well"
        (is (= 1 (resolutions {:tx-data [wide nested]}))
            "resolution count is per transaction, never per entity")))))

(deftest repeated-canonical-storage-is-decoded-once
  (let [projection (schema/declaration-projection)
        attr :datahike.read/revision]
    (doseq [n [1 10 100]]
      (let [value {:datahike.read/attributes #{(keyword "probe.codec" (str n))}
                   :datahike.read/cache-eligible? false}
            encoded (schema.datahike/encode-attribute-value-in projection attr value)
            [decoded calls]
            (support/armed-calls
             '#{seon.schema.datahike/decode-canonical-edn}
             #(mapv (fn [_] (schema.datahike/decode-attribute-value-in projection attr encoded)) (range n)))]
        (is (= (vec (repeat n value)) decoded))
        (is (<= (get calls 'seon.schema.datahike/decode-canonical-edn) 1)
            "a content key is parsed and canonicalized at most once")))
    (is (nil? (schema.datahike/decode-attribute-value-in projection attr "nil"))
        "a read does not reapply today's admission schema to canonical stored nil")
    (is (nil? (schema.datahike/decode-attribute-value-in projection attr "nil")))))

(deftest edn-backed-attributes-have-one-canonical-datahike-round-trip
  (let [projection (schema/declaration-projection)
        branch (keyword "seon.test-support.fixture" "0")
        revision
        {:datahike.cache/connection-id
         [(java.util.UUID/fromString "05e76e86-dc27-4aa0-958a-a96274b83533")
          branch]
         :datahike.cache/generation
         (java.util.UUID/fromString "aa7bc82c-65c4-44a0-98df-87c7c798b13b")
         :datahike.read/attributes #{:seon.agent/id}
         :datahike.cache/attribute-revisions
         {:seon.agent/id
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
             '[[?entity :seon.call-preparation/key ?key]
               [?entity :seon.call-preparation/schema ?schema]
               [?schema :seon.schema/key ?schema-key]
               [?entity :seon.call-preparation/supplier ?function]
               [?function :seon.fn/sym ?supplier]]
             attributes
             [:seon.call-preparation/key
              :seon.call-preparation/schema
              :seon.schema/key
              :seon.call-preparation/supplier
              :seon.fn/sym]
             ascending-set (into (sorted-set-by compare) attributes)
             descending-set
             (into (sorted-set-by (fn [left right] (compare right left)))
                   attributes)
             query
             (array-map
              :find '[?key ?schema-key ?supplier]
              :in '[$]
              :where where)
             reversed-query
             (array-map
              :where where
              :in '[$]
              :find '[?key ?schema-key ?supplier])
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
               ;; The holder is a canonical namespace declaration (digest derived).
               (merge
                (support/namespace-entity (db/db connection) (symbol (str "codec." id)))
               {:db/id id
                :seon.db/source-argument-position 0
                :datahike.read/dependency-plan dependency-plan
                :datahike.read/revision
                {:datahike.read/attributes :all
                 :datahike.read/cache-eligible? false}
                :seon.db/read-request read-request}))]
         (let [forward-tx
               (binding [*print-namespace-maps* false]
                 (support/transacted! connection [(receipt "codec-forward" request plan)]))
               reversed-tx
               (binding [*print-namespace-maps* true]
                 (support/transacted! connection
                               [(receipt "codec-reversed" reversed-request reversed-plan)]))
               selector '[*]
               forward-id (get-in forward-tx [:tempids "codec-forward"])
               reversed-id (get-in reversed-tx [:tempids "codec-reversed"])
               forward-raw-evidence (d/pull (db/db connection) selector forward-id)
               reversed-raw-evidence (d/pull (db/db connection) selector reversed-id)
               forward-evidence
               (binding [*print-namespace-maps* true]
                 (db/pull (db/db connection) selector forward-id))
               reversed-evidence
               (binding [*print-namespace-maps* false]
                 (db/pull (db/db connection) selector reversed-id))]
           (is (:db-after forward-tx))
           (is (:db-after reversed-tx))
           (testing "the transaction codec emits one canonical representation"
             (is (= (:seon.db/read-request forward-raw-evidence)
                    (:seon.db/read-request reversed-raw-evidence)))
             (is (= (:datahike.read/dependency-plan forward-raw-evidence)
                    (:datahike.read/dependency-plan reversed-raw-evidence))))
           (testing "wildcard reads restore both exact logical values"
             (is (= request (:seon.db/read-request forward-evidence)))
             (is (= request (:seon.db/read-request reversed-evidence)))
             (is (= plan (:datahike.read/dependency-plan forward-evidence)))
             (is (= plan
                    (:datahike.read/dependency-plan reversed-evidence))))))))))

(deftest fixed-tuples-derive-native-ordered-storage
  (support/with-database
    {::support/extra-schema
     [{:db/ident ::position :db/valueType :db.type/tuple
       :db/cardinality :db.cardinality/one
       :db/tupleTypes [:db.type/long :db.type/long]}]}
    (fn [connection]
      (let [forms (assoc (schema/registered-schemas) ::position [:tuple :int :int])
            projection (schema/declaration-projection forms)
            derived (schema.datahike/malli->datahike-attr-in projection ::position)]
        (is (= {:db/ident ::position :db/valueType :db.type/tuple
                :db/cardinality :db.cardinality/one
                :db/tupleTypes [:db.type/long :db.type/long]} derived))
        (schema/call-with-projection
         projection
         (fn []
           (let [report (db/transact! connection [(assoc (support/namespace-entity (db/db connection) 'sample.tuple)
                                                                ::position [9 2])])]
             (is (:db-after report))
             (when (:db-after report)
               (is (= [9 2] (::position (db/pull (:db-after report) [::position]
                                                [:seon.ns/name 'sample.tuple]))))))))))))
