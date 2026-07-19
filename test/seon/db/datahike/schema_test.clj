(ns seon.db.datahike.schema-test
  "Tests for Malli -> Datahike schema bridge."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db.datahike.schema :as dhs]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- find-attr
  "Find the ident entity map for `attr-ident` in a derived schema vector."
  [schema-vec attr-ident]
  (some #(when (= attr-ident (:db/ident %)) %) schema-vec))

(defn- mem-cfg []
  {:store {:backend :memory :id (random-uuid)}
   :schema-flexibility :write
   :keep-history? false})

;;; ---------------------------------------------------------------------------
;;; Leaf type mapping
;;; ---------------------------------------------------------------------------

(deftest malli-type->datahike-type-test
  (testing "keyword types map correctly"
    (is (= :db.type/string  (dhs/malli-type->datahike-type :string)))
    (is (= :db.type/long    (dhs/malli-type->datahike-type :int)))
    (is (= :db.type/long    (dhs/malli-type->datahike-type :long)))
    (is (= :db.type/double  (dhs/malli-type->datahike-type :double)))
    (is (= :db.type/float   (dhs/malli-type->datahike-type :float)))
    (is (= :db.type/boolean (dhs/malli-type->datahike-type :boolean)))
    (is (= :db.type/keyword (dhs/malli-type->datahike-type :keyword)))
    (is (= :db.type/symbol  (dhs/malli-type->datahike-type :symbol)))
    (is (= :db.type/uuid    (dhs/malli-type->datahike-type :uuid)))
    (is (= :db.type/instant (dhs/malli-type->datahike-type :inst))))

  (testing "predicate types map correctly"
    (is (= :db.type/string  (dhs/malli-type->datahike-type 'string?)))
    (is (= :db.type/long    (dhs/malli-type->datahike-type 'int?)))
    (is (= :db.type/double  (dhs/malli-type->datahike-type 'double?)))
    (is (= :db.type/float   (dhs/malli-type->datahike-type 'float?)))
    (is (= :db.type/boolean (dhs/malli-type->datahike-type 'boolean?)))
    (is (= :db.type/keyword (dhs/malli-type->datahike-type 'keyword?)))
    (is (= :db.type/symbol  (dhs/malli-type->datahike-type 'symbol?)))
    (is (= :db.type/uuid    (dhs/malli-type->datahike-type 'uuid?)))
    (is (= :db.type/instant (dhs/malli-type->datahike-type 'inst?))))

  (testing "returns nil for unmappable types"
    (is (nil? (dhs/malli-type->datahike-type :any)))
    (is (nil? (dhs/malli-type->datahike-type :vector)))
    (is (nil? (dhs/malli-type->datahike-type :map)))))

;;; ---------------------------------------------------------------------------
;;; Basic leaf shapes
;;; ---------------------------------------------------------------------------

(deftest bridge-basic-types-test
  (testing "every leaf type produces the correct shape"
    (let [result (dhs/malli-map->datahike-schema
                  [:map
                   [:t/string :string]
                   [:t/int :int]
                   [:t/double :double]
                   [:t/boolean :boolean]
                   [:t/keyword :keyword]
                   [:t/symbol :symbol]
                   [:t/uuid :uuid]
                   [:t/inst :inst]])]
      (is (= 8 (count result)))
      (is (= {:db/ident :t/string
              :db/valueType :db.type/string
              :db/cardinality :db.cardinality/one}
             (find-attr result :t/string)))
      (is (= :db.type/long    (:db/valueType (find-attr result :t/int))))
      (is (= :db.type/double  (:db/valueType (find-attr result :t/double))))
      (is (= :db.type/boolean (:db/valueType (find-attr result :t/boolean))))
      (is (= :db.type/keyword (:db/valueType (find-attr result :t/keyword))))
      (is (= :db.type/symbol  (:db/valueType (find-attr result :t/symbol))))
      (is (= :db.type/uuid    (:db/valueType (find-attr result :t/uuid))))
      (is (= :db.type/instant (:db/valueType (find-attr result :t/inst))))
      (is (every? #(= :db.cardinality/one (:db/cardinality %)) result))))

  (testing "predicate form works same as keyword form"
    (let [kw-form (dhs/malli-map->datahike-schema [:map [:a/x :string]])
          pred-form (dhs/malli-map->datahike-schema [:map [:a/x 'string?]])]
      (is (= (:db/valueType (find-attr kw-form :a/x))
             (:db/valueType (find-attr pred-form :a/x)))))))

;;; ---------------------------------------------------------------------------
;;; Cardinality
;;; ---------------------------------------------------------------------------

(deftest bridge-cardinality-test
  (testing ":vector produces cardinality-many with inner type"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/tags [:vector :keyword]]])
          attr (find-attr result :foo/tags)]
      (is (= :db.type/keyword (:db/valueType attr)))
      (is (= :db.cardinality/many (:db/cardinality attr)))))

  (testing ":set produces cardinality-many with inner type"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/ids [:set :uuid]]])
          attr (find-attr result :foo/ids)]
      (is (= :db.type/uuid (:db/valueType attr)))
      (is (= :db.cardinality/many (:db/cardinality attr)))))

  (testing "nested collection rejected"
    (is (thrown-with-msg? Exception #"Nested collection not supported"
                          (dhs/malli-map->datahike-schema
                           [:map [:foo/bad [:vector [:vector :string]]]]))))

  (testing ":vector of :map rejected"
    (is (thrown-with-msg? Exception #"Nested collection not supported"
                          (dhs/malli-map->datahike-schema
                           [:map [:foo/bad [:vector [:map [:x :string]]]]])))))

(deftest bridge-secondary-only-tuple-test
  (testing ":db.secondary/only on [:vector :float] → single tuple value"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/embedding [:vector {:db.secondary/only true} :float]]])
          attr (find-attr result :foo/embedding)]
      ;; The vector wrapper is a TUPLE here, NOT cardinality-many — the
      ;; whole vector lives only in the secondary/vector index.
      (is (= :db.type/tuple (:db/valueType attr)))
      (is (= :db.cardinality/one (:db/cardinality attr)))
      (is (= true (:db.secondary/only attr)))))

  (testing ":db.secondary/only also honored as type-level property"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/embedding [:vector {:db.secondary/only true} :double]]])
          attr (find-attr result :foo/embedding)]
      (is (= :db.type/tuple (:db/valueType attr)))
      (is (= :db.cardinality/one (:db/cardinality attr)))))

  (testing ":db.secondary/only over a non-float vector is rejected"
    (is (thrown-with-msg? Exception #"must be a vector of :float/:double"
                          (dhs/malli-map->datahike-schema
                           [:map [:foo/bad [:vector {:db.secondary/only true} :string]]])))))

;;; ---------------------------------------------------------------------------
;;; Seon DB props
;;; ---------------------------------------------------------------------------

(deftest bridge-seon-db-props-test
  (testing ":seon.db/identity true -> :db.unique/identity (entry-level props)"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/id {:seon.db/identity true} :uuid]])
          attr (find-attr result :foo/id)]
      (is (= :db.type/uuid (:db/valueType attr)))
      (is (= :db.unique/identity (:db/unique attr)))))

  (testing ":seon.db/identity true -> :db.unique/identity (inline type props)"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/id [:uuid {:seon.db/identity true}]]])
          attr (find-attr result :foo/id)]
      (is (= :db.type/uuid (:db/valueType attr)))
      (is (= :db.unique/identity (:db/unique attr)))))

  (testing "a unique-value ref becomes a unique Datahike ref"
    (let [result (dhs/malli-map->datahike-schema
                  [:map
                   [:foo/namespace
                    [:and {:seon.db/unique true} :seon.db/ref]]])
          attr (find-attr result :foo/namespace)]
      (is (= :db.type/ref (:db/valueType attr)))
      (is (= :db.unique/value (:db/unique attr)))))

  (testing ":seon.db/unique true -> :db.unique/value"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/email {:seon.db/unique true} :string]])
          attr (find-attr result :foo/email)]
      (is (= :db.type/string (:db/valueType attr)))
      (is (= :db.unique/value (:db/unique attr)))))

  (testing ":seon.db/index true -> :db/index true"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/status {:seon.db/index true} :keyword]])
          attr (find-attr result :foo/status)]
      (is (= :db.type/keyword (:db/valueType attr)))
      (is (true? (:db/index attr))))))

;;; ---------------------------------------------------------------------------
;;; Enum
;;; ---------------------------------------------------------------------------

(deftest bridge-enum-test
  (testing "homogeneous keyword enum -> :db.type/keyword"
    (is (= :db.type/keyword
           (:db/valueType
            (find-attr (dhs/malli-map->datahike-schema
                        [:map [:foo/status [:enum :active :inactive]]])
                       :foo/status)))))

  (testing "homogeneous string enum -> :db.type/string"
    (is (= :db.type/string
           (:db/valueType
            (find-attr (dhs/malli-map->datahike-schema
                        [:map [:foo/role [:enum "admin" "user"]]])
                       :foo/role)))))

  (testing "homogeneous long enum -> :db.type/long"
    (is (= :db.type/long
           (:db/valueType
            (find-attr (dhs/malli-map->datahike-schema
                        [:map [:foo/n [:enum 1 2 3]]])
                       :foo/n)))))

  (testing "homogeneous double enum -> :db.type/double"
    (is (= :db.type/double
           (:db/valueType
            (find-attr (dhs/malli-map->datahike-schema
                        [:map [:foo/d [:enum 1.0 2.0]]])
                       :foo/d)))))

  (testing "mixed-type enum raises"
    (is (thrown-with-msg? Exception #"Mixed-type enum"
                          (dhs/malli-map->datahike-schema
                           [:map [:foo/bad [:enum :a "b" 1]]]))))

  (testing "empty enum raises"
    ;; Malli itself rejects [:enum] at schema-resolution time (requires >= 1
    ;; child), which our schema-resolution try/catch wraps as a load-order
    ;; error. The explicit "Empty enum" path in the bridge is unreachable
    ;; through the public API but defends against programmatically-built
    ;; enum schemas with no values.
    (is (thrown? Exception
                 (dhs/malli-map->datahike-schema
                  [:map [:foo/empty [:enum]]])))))

;;; ---------------------------------------------------------------------------
;;; :seon.db/ref
;;; ---------------------------------------------------------------------------

(deftest bridge-seon-db-ref-test
  (testing ":seon.db/ref -> :db.type/ref (Decision 10 — intra-DB ref)"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/parent :seon.db/ref]])
          attr (find-attr result :foo/parent)]
      (is (= :db.type/ref (:db/valueType attr)))
      (is (= :db.cardinality/one (:db/cardinality attr))))))

;;; ---------------------------------------------------------------------------
;;; Nested :map rejection
;;; ---------------------------------------------------------------------------

(deftest bridge-nested-map-rejected-test
  (testing "nested :map raises with phase-1 guidance"
    (is (thrown-with-msg?
         Exception #"Nested map as component ref not supported"
         (dhs/malli-map->datahike-schema
          [:map [:foo/child [:map [:bar/name :string]]]])))))

;;; ---------------------------------------------------------------------------
;;; Load-order guard
;;; ---------------------------------------------------------------------------

(deftest bridge-load-order-error-test
  (testing "unregistered schema reference raises with migration guidance"
    (is (thrown-with-msg?
         Exception #"schema/register! is called BEFORE"
         (dhs/malli-map->datahike-schema
          [:map [:foo/x :seon.db.datahike.schema-test/does-not-exist]])))))

;;; ---------------------------------------------------------------------------
;;; :maybe defensive unwrap
;;; ---------------------------------------------------------------------------

(deftest bridge-maybe-unwraps-test
  (testing ":maybe unwraps to inner type (defence-in-depth)"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/x [:maybe :string]]])
          attr (find-attr result :foo/x)]
      (is (= :db.type/string (:db/valueType attr))))))

;;; ---------------------------------------------------------------------------
;;; :or storage representation
;;; ---------------------------------------------------------------------------

(deftest bridge-or-storage-test
  (testing "mixed :or uses the existing EDN-string representation"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/x [:or :string :keyword]]])]
      (is (= :db.type/string (:db/valueType (find-attr result :foo/x))))))

  (testing ":or with :seon.db/value-type uses declared type"
    (let [result (dhs/malli-map->datahike-schema
                  [:map [:foo/x [:or {:seon.db/value-type :db.type/string}
                                 :string :keyword]]])]
      (is (= :db.type/string (:db/valueType (find-attr result :foo/x)))))))

;;; ---------------------------------------------------------------------------
;;; End-to-end: install schema into datahike and transact data
;;; ---------------------------------------------------------------------------

(deftest explicit-form-population-is-the-only-reference-registry-test
  (let [forms {:seon.db/lookup-ref-value
               [:or :string :uuid :keyword :int]
               :seon.db/ref
               [:or :int :string
                [:tuple :keyword :seon.db/lookup-ref-value]]
               :seon.db.id/legacy-value [:string {:min 14 :max 14}]
               :seon.db.id/compact-value
               [:or :seon.db.id/legacy-value
                [:and :string [:re "^[a-z][a-z0-9]{11}$"]]]
               :example/name :string
               :example/names [:set :example/name]
               :example/id [:uuid {:seon.db/identity true}]
               :example/generated-id
               [:and {:seon.db/identity true
                      :seon.db.id/generator
                      :seon.db.id.generator/compact}
                :seon.db.id/compact-value]
               :example/children
               [:vector {:seon.db/component true} :seon.db/ref]
               :example/render [:or :string :symbol]}]
    (is (= {:db/ident :example/names
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/many}
           (dhs/malli-form->datahike-attribute
            forms :example/names (:example/names forms))))
    (is (= {:db/ident :example/id
            :db/valueType :db.type/uuid
            :db/cardinality :db.cardinality/one
            :db/unique :db.unique/identity}
           (dhs/malli-form->datahike-attribute
            forms :example/id (:example/id forms))))
    (is (= {:db/ident :example/generated-id
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one
            :db/unique :db.unique/identity}
           (dhs/malli-form->datahike-attribute
            forms :example/generated-id (:example/generated-id forms))))
    (is (= {:db/ident :example/children
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many
            :db/isComponent true}
           (dhs/malli-form->datahike-attribute
            forms :example/children (:example/children forms))))
    (is (= {:db/ident :example/render
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           (dhs/malli-form->datahike-attribute
            forms :example/render (:example/render forms))))))

(deftest canonical-primitive-alias-compiles-in-an-isolated-registry
  (is (= {:db/ident :example/completed-at
          :db/valueType :db.type/instant
          :db/cardinality :db.cardinality/one}
         (dhs/malli-form->datahike-attribute
          {:example/completed-at :inst}
          :example/completed-at
          :inst))))

(deftest e2e-derive-install-transact-test
  (testing "derived schema installs and accepts valid data"
    (schema/register! :seon.db.datahike.schema-test/id
                      [:uuid {:seon.db/identity true}])
    (schema/register! :seon.db.datahike.schema-test/name
                      [:string {:seon.db/unique true}])
    (schema/register! :seon.db.datahike.schema-test/tags
                      [:vector :keyword])
    (schema/register! :seon.db.datahike.schema-test/age :int)
    (schema/register! :seon.db.datahike.schema-test/born :inst)

    (let [entity-schema [:map
                         [:seon.db.datahike.schema-test/id
                          :seon.db.datahike.schema-test/id]
                         [:seon.db.datahike.schema-test/name
                          :seon.db.datahike.schema-test/name]
                         [:seon.db.datahike.schema-test/tags
                          :seon.db.datahike.schema-test/tags]
                         [:seon.db.datahike.schema-test/age
                          :seon.db.datahike.schema-test/age]
                         [:seon.db.datahike.schema-test/born
                          :seon.db.datahike.schema-test/born]]
          derived (dhs/malli-map->datahike-schema entity-schema)
          cfg (mem-cfg)]
      (testing "derived vector has 5 ident entity maps"
        (is (= 5 (count derived)))
        (is (every? :db/ident derived))
        (is (every? :db/valueType derived))
        (is (every? :db/cardinality derived)))

      (d/create-database cfg)
      (let [conn (d/connect cfg)
            alice-id (random-uuid)]
        (try
          ;; Install schema (sync transact)
          (d/transact conn derived)
          ;; Transact data
          (d/transact conn [{:seon.db.datahike.schema-test/id alice-id
                             :seon.db.datahike.schema-test/name "Alice"
                             :seon.db.datahike.schema-test/tags [:a :b :c]
                             :seon.db.datahike.schema-test/age 30
                             :seon.db.datahike.schema-test/born #inst "1995-01-01"}])
          (testing "data round-trips via datalog"
            (is (= #{["Alice" 30]}
                   (d/q '[:find ?n ?a
                          :where
                          [?e :seon.db.datahike.schema-test/name ?n]
                          [?e :seon.db.datahike.schema-test/age ?a]]
                        @conn))))
          (testing "identity attr resolves lookup ref"
            (is (= "Alice"
                   (:seon.db.datahike.schema-test/name
                    (d/pull @conn '[*]
                            [:seon.db.datahike.schema-test/id alice-id])))))
          (testing "wrong type rejected at transact"
            (is (thrown? Exception
                         (d/transact conn
                                     [{:seon.db.datahike.schema-test/id "not-a-uuid"
                                       :seon.db.datahike.schema-test/name "Bob"}]))))
          (finally
            (d/release conn)))))))
