(ns seon.db.schema-roundtrip-test
  "Generative roundtrip tests: Malli schema -> Datalevin schema -> transact -> pull -> validate.

   Tests the property: for any value satisfying a Malli schema, transacting it to
   Datalevin and pulling it back produces a value that also satisfies the schema
   (modulo known transformations like set->vector for cardinality-many).

   Uses direct Datalevin connections (no seon.db flow infrastructure) to isolate
   the Malli<->Datalevin roundtrip."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [datalevin.constants :as dc]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.db.schema :as db-schema]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- strip-nils
  "Remove nil-valued entries from a map. Datalevin cannot store nil."
  [m]
  (into {} (remove (fn [[_ v]] (nil? v))) m))

(defn- strip-db-id
  "Remove :db/id from pulled entity (and nested component entities)."
  [m]
  (let [without (dissoc m :db/id)]
    (into {}
          (map (fn [[k v]]
                 (if (map? v)
                   [k (strip-db-id v)]
                   [k v])))
          without)))

(defn- roundtrip!
  "Transact entity map, pull it back by lookup ref, strip :db/id.
   Returns the pulled entity or nil if not found."
  [conn identity-attr entity]
  (let [clean (strip-nils entity)]
    (d/transact! conn [clean])
    (let [pulled (d/pull @conn '[*] [identity-attr (get clean identity-attr)])]
      (when pulled
        (strip-db-id pulled)))))

(def ^:private num-samples
  "Number of random values to generate per type test."
  20)

;;; ---------------------------------------------------------------------------
;;; Leaf Type Roundtrips
;;; ---------------------------------------------------------------------------

(deftest string-roundtrip-test
  (testing "random strings roundtrip through Datalevin"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/string}}
      (fn [conn]
        (doseq [[i val] (map-indexed vector (mg/sample :string {:size num-samples}))]
          (let [entity {:test/id i :test/val val}
                result (roundtrip! conn :test/id entity)]
            (is (= val (:test/val result))
                (str "String roundtrip failed for: " (pr-str val)))))))))

(deftest int-long-roundtrip-test
  (testing "random ints roundtrip as longs"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/long}}
      (fn [conn]
        (doseq [[i val] (map-indexed vector (mg/sample :int {:size num-samples}))]
          (let [entity {:test/id (+ 1000 i) :test/val val}
                result (roundtrip! conn :test/id entity)]
            (is (= (long val) (:test/val result))
                (str "Int/long roundtrip failed for: " val))))))))

(deftest double-roundtrip-test
  (testing "random doubles roundtrip (excluding NaN)"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/double}}
      (fn [conn]
        (let [vals (remove #(Double/isNaN %) (mg/sample :double {:size num-samples}))]
          (doseq [[i val] (map-indexed vector vals)]
            (let [entity {:test/id i :test/val val}
                  result (roundtrip! conn :test/id entity)]
              (is (= val (:test/val result))
                  (str "Double roundtrip failed for: " val)))))))))

(deftest float-roundtrip-test
  (testing "Malli :float generates Double; Datalevin stores as Float; roundtrip loses precision"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/float}}
      (fn [conn]
        (doseq [[i val] (map-indexed vector (mg/sample :float {:size num-samples}))]
          (let [entity {:test/id i :test/val val}
                result (roundtrip! conn :test/id entity)]
            ;; Datalevin coerces to Float, so result is Float not Double
            (is (instance? Float (:test/val result))
                (str "Expected Float after roundtrip, got: " (type (:test/val result))))
            ;; Value should be close but not necessarily identical (double->float precision)
            (is (< (Math/abs (- (double val) (double (:test/val result)))) 1.0)
                (str "Float roundtrip too far off for: " val))))))))

(deftest boolean-roundtrip-test
  (testing "booleans roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/boolean}}
      (fn [conn]
        (d/transact! conn [{:test/id 1 :test/val true}
                           {:test/id 2 :test/val false}])
        (is (= true (:test/val (d/pull @conn '[*] [:test/id 1]))))
        (is (= false (:test/val (d/pull @conn '[*] [:test/id 2]))))))))

(deftest keyword-roundtrip-test
  (testing "random keywords roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/keyword}}
      (fn [conn]
        (doseq [[i val] (map-indexed vector (mg/sample :keyword {:size num-samples}))]
          (let [entity {:test/id i :test/val val}
                result (roundtrip! conn :test/id entity)]
            (is (= val (:test/val result))
                (str "Keyword roundtrip failed for: " val))))))))

(deftest symbol-roundtrip-test
  (testing "random symbols roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/symbol}}
      (fn [conn]
        (doseq [[i val] (map-indexed vector (mg/sample :symbol {:size num-samples}))]
          (let [entity {:test/id i :test/val val}
                result (roundtrip! conn :test/id entity)]
            (is (= val (:test/val result))
                (str "Symbol roundtrip failed for: " val))))))))

(deftest uuid-roundtrip-test
  (testing "random UUIDs roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/uuid}}
      (fn [conn]
        (doseq [[i val] (map-indexed vector (mg/sample :uuid {:size num-samples}))]
          (let [entity {:test/id i :test/val val}
                result (roundtrip! conn :test/id entity)]
            (is (= val (:test/val result))
                (str "UUID roundtrip failed for: " val))))))))

(deftest instant-roundtrip-test
  (testing "random instants roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/instant}}
      (fn [conn]
        (doseq [[i val] (map-indexed vector (mg/sample :inst {:size num-samples}))]
          (let [entity {:test/id i :test/val val}
                result (roundtrip! conn :test/id entity)]
            (is (= val (:test/val result))
                (str "Instant roundtrip failed for: " val))))))))

;;; ---------------------------------------------------------------------------
;;; Enum Roundtrips
;;; ---------------------------------------------------------------------------

(deftest keyword-enum-roundtrip-test
  (testing "keyword enums roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/keyword}}
      (fn [conn]
        (let [schema [:enum :active :inactive :pending]]
          (doseq [[i val] (map-indexed vector (mg/sample schema {:size num-samples}))]
            (let [entity {:test/id i :test/val val}
                  result (roundtrip! conn :test/id entity)]
              (is (= val (:test/val result))))))))))

(deftest string-enum-roundtrip-test
  (testing "string enums roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/string}}
      (fn [conn]
        (let [schema [:enum "admin" "user" "guest"]]
          (doseq [[i val] (map-indexed vector (mg/sample schema {:size num-samples}))]
            (let [entity {:test/id i :test/val val}
                  result (roundtrip! conn :test/id entity)]
              (is (= val (:test/val result))))))))))

(deftest long-enum-roundtrip-test
  (testing "long enums roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/long}}
      (fn [conn]
        (let [schema [:enum 1 2 3 42]]
          (doseq [[i val] (map-indexed vector (mg/sample schema {:size num-samples}))]
            (let [entity {:test/id (+ 100 i) :test/val val}
                  result (roundtrip! conn :test/id entity)]
              (is (= val (:test/val result))))))))))

;;; ---------------------------------------------------------------------------
;;; Schema Derivation
;;; ---------------------------------------------------------------------------

(deftest bridge-derives-correct-schema-test
  (testing "bridge output produces working Datalevin schema for all leaf types"
    (let [malli-schema [:map
                        [:rt/id {:db/unique :db.unique/identity} :string]
                        [:rt/str :string]
                        [:rt/int :int]
                        [:rt/double :double]
                        [:rt/bool :boolean]
                        [:rt/kw :keyword]
                        [:rt/sym :symbol]
                        [:rt/uuid :uuid]
                        [:rt/inst :inst]]
          dl-schema (db-schema/malli-map->datalevin-schema malli-schema)]
      ;; Verify schema shape
      (is (= :db.type/string (:db/valueType (:rt/str dl-schema))))
      (is (= :db.type/long (:db/valueType (:rt/int dl-schema))))
      (is (= :db.type/double (:db/valueType (:rt/double dl-schema))))
      (is (= :db.type/boolean (:db/valueType (:rt/bool dl-schema))))
      (is (= :db.type/keyword (:db/valueType (:rt/kw dl-schema))))
      (is (= :db.type/symbol (:db/valueType (:rt/sym dl-schema))))
      (is (= :db.type/uuid (:db/valueType (:rt/uuid dl-schema))))
      (is (= :db.type/instant (:db/valueType (:rt/inst dl-schema))))
      (is (= :db.unique/identity (:db/unique (:rt/id dl-schema))))

      ;; Verify we can create a DB and transact with the derived schema
      (tu/with-temp-conn dl-schema
        (fn [conn]
          (d/transact! conn [{:rt/id "test-1"
                              :rt/str "hello"
                              :rt/int 42
                              :rt/double 3.14
                              :rt/bool true
                              :rt/kw :foo
                              :rt/sym 'bar
                              :rt/uuid (java.util.UUID/randomUUID)
                              :rt/inst (java.util.Date.)}])
          (let [result (strip-db-id (d/pull @conn '[*] [:rt/id "test-1"]))]
            (is (= "hello" (:rt/str result)))
            (is (= 42 (:rt/int result)))
            (is (= true (:rt/bool result)))))))))

(deftest bridge-maybe-derives-correct-type-test
  (testing "[:maybe :string] derives same type as :string"
    (let [schema [:map [:rt/id {:db/unique :db.unique/identity} :string]
                  [:rt/maybe-str [:maybe :string]]]
          dl (db-schema/malli-map->datalevin-schema schema)]
      (is (= :db.type/string (:db/valueType (:rt/maybe-str dl)))))))

(deftest bridge-vector-derives-cardinality-many-test
  (testing "[:vector :keyword] derives cardinality-many"
    (let [schema [:map [:rt/id {:db/unique :db.unique/identity} :string]
                  [:rt/tags [:vector :keyword]]]
          dl (db-schema/malli-map->datalevin-schema schema)]
      (is (= :db.type/keyword (:db/valueType (:rt/tags dl))))
      (is (= :db.cardinality/many (:db/cardinality (:rt/tags dl)))))))

(deftest bridge-nested-map-derives-component-ref-test
  (testing "nested :map derives :db.type/ref + :db/isComponent"
    (let [schema [:map [:rt/id {:db/unique :db.unique/identity} :string]
                  [:rt/child [:map [:child/name :string] [:child/val :int]]]]
          dl (db-schema/malli-map->datalevin-schema schema)]
      (is (= :db.type/ref (:db/valueType (:rt/child dl))))
      (is (= true (:db/isComponent (:rt/child dl))))
      ;; Nested attrs are flattened into the top-level schema
      (is (= :db.type/string (:db/valueType (:child/name dl))))
      (is (= :db.type/long (:db/valueType (:child/val dl)))))))

;;; ---------------------------------------------------------------------------
;;; Maybe / Optional Handling
;;; ---------------------------------------------------------------------------

(deftest maybe-nil-stripped-before-transact-test
  (testing "nil values must be stripped; present values survive"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/string}}
      (fn [conn]
        ;; Transact without the optional attr (nil stripped)
        (d/transact! conn [{:test/id 1}])
        (let [result (strip-db-id (d/pull @conn '[*] [:test/id 1]))]
          (is (nil? (:test/val result))
              "Absent attr should be nil in pull result"))

        ;; Transact with the attr present
        (d/transact! conn [{:test/id 2 :test/val "present"}])
        (let [result (strip-db-id (d/pull @conn '[*] [:test/id 2]))]
          (is (= "present" (:test/val result))))))))

(deftest maybe-generative-roundtrip-test
  (testing "[:maybe :string] values roundtrip (nils stripped)"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/string}}
      (fn [conn]
        (let [schema [:maybe :string]]
          (doseq [[i val] (map-indexed vector (mg/sample schema {:size num-samples}))]
            (let [entity (cond-> {:test/id i}
                           (some? val) (assoc :test/val val))
                  _ (d/transact! conn [entity])
                  result (strip-db-id (d/pull @conn '[*] [:test/id i]))]
              (if (some? val)
                (is (= val (:test/val result))
                    (str "Maybe present value failed: " (pr-str val)))
                (is (nil? (:test/val result))
                    "Nil should produce absent attr")))))))))

;;; ---------------------------------------------------------------------------
;;; Cardinality-Many
;;; ---------------------------------------------------------------------------

(deftest cardinality-many-vector-roundtrip-test
  (testing "vector of keywords -> cardinality-many -> pull returns vector"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/tags {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}}
      (fn [conn]
        (d/transact! conn [{:test/id 1 :test/tags [:a :b :c]}])
        (let [result (d/pull @conn '[*] [:test/id 1])
              tags (:test/tags result)]
          (is (vector? tags) "Pull should return vector for cardinality-many")
          (is (= #{:a :b :c} (set tags))
              "All values should be present"))))))

(deftest cardinality-many-set-roundtrip-test
  (testing "set of strings -> cardinality-many -> pull returns vector"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/names {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}}
      (fn [conn]
        (d/transact! conn [{:test/id 1 :test/names #{"alice" "bob" "carol"}}])
        (let [result (d/pull @conn '[*] [:test/id 1])
              names (:test/names result)]
          (is (vector? names) "Pull should return vector even when set transacted")
          (is (= #{"alice" "bob" "carol"} (set names))))))))

(deftest cardinality-many-generative-roundtrip-test
  (testing "generated sets of keywords roundtrip via cardinality-many"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/tags {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}}
      (fn [conn]
        (let [schema [:set :keyword]]
          (doseq [[i val] (map-indexed vector (mg/sample schema {:size num-samples}))]
            (when (seq val) ;; empty sets produce no datoms
              (d/transact! conn [{:test/id (+ 2000 i) :test/tags val}])
              (let [result (d/pull @conn '[*] [:test/id (+ 2000 i)])
                    tags (:test/tags result)]
                (is (= (set val) (set tags))
                    (str "Set roundtrip failed for: " (pr-str val)))))))))))

;;; ---------------------------------------------------------------------------
;;; Component Refs (Nested Maps)
;;; ---------------------------------------------------------------------------

(deftest component-ref-roundtrip-test
  (testing "nested map roundtrips as component entity"
    (tu/with-temp-conn
      {:parent/id {:db/valueType :db.type/string :db/unique :db.unique/identity}
       :parent/name {:db/valueType :db.type/string}
       :parent/child {:db/valueType :db.type/ref :db/isComponent true}
       :child/name {:db/valueType :db.type/string :db/unique :db.unique/identity}
       :child/val {:db/valueType :db.type/long}}
      (fn [conn]
        (d/transact! conn [{:parent/id "p1"
                            :parent/name "Parent"
                            :parent/child {:child/name "c1" :child/val 42}}])
        (let [result (strip-db-id (d/pull @conn '[*] [:parent/id "p1"]))]
          (is (= "Parent" (:parent/name result)))
          (is (map? (:parent/child result)))
          (is (= "c1" (get-in result [:parent/child :child/name])))
          (is (= 42 (get-in result [:parent/child :child/val]))))))))

(deftest non-component-ref-returns-db-id-test
  (testing "non-component ref returns {:db/id N} on pull"
    (tu/with-temp-conn
      {:thing/id {:db/valueType :db.type/string :db/unique :db.unique/identity}
       :thing/ref {:db/valueType :db.type/ref}}
      (fn [conn]
        (d/transact! conn [{:thing/id "target"}])
        (d/transact! conn [{:thing/id "source"
                            :thing/ref [:thing/id "target"]}])
        (let [result (d/pull @conn '[*] [:thing/id "source"])]
          (is (map? (:thing/ref result)))
          (is (contains? (:thing/ref result) :db/id))
          (is (number? (:db/id (:thing/ref result)))))))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest empty-string-roundtrip-test
  (testing "empty string roundtrips"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/string}}
      (fn [conn]
        (d/transact! conn [{:test/id 1 :test/val ""}])
        (is (= "" (:test/val (d/pull @conn '[*] [:test/id 1]))))))))

(deftest nil-transact-throws-test
  (testing "transacting nil value throws"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/string}}
      (fn [conn]
        (is (thrown? Exception
                     (d/transact! conn [{:test/id 1 :test/val nil}])))))))

(deftest nan-transact-throws-test
  (testing "transacting NaN throws AssertionError"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/double}}
      (fn [conn]
        (is (thrown? AssertionError
                     (d/transact! conn [{:test/id 1 :test/val ##NaN}])))))))

(deftest infinity-roundtrip-test
  (testing "Infinity and -Infinity roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/double}}
      (fn [conn]
        (d/transact! conn [{:test/id 1 :test/val ##Inf}
                           {:test/id 2 :test/val ##-Inf}])
        (is (= ##Inf (:test/val (d/pull @conn '[*] [:test/id 1]))))
        (is (= ##-Inf (:test/val (d/pull @conn '[*] [:test/id 2]))))))))

(deftest extreme-long-roundtrip-test
  (testing "Long/MIN_VALUE and Long/MAX_VALUE roundtrip"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/long}}
      (fn [conn]
        (d/transact! conn [{:test/id 1 :test/val Long/MIN_VALUE}
                           {:test/id 2 :test/val Long/MAX_VALUE}])
        (is (= Long/MIN_VALUE (:test/val (d/pull @conn '[*] [:test/id 1]))))
        (is (= Long/MAX_VALUE (:test/val (d/pull @conn '[*] [:test/id 2]))))))))

(deftest bigint-roundtrip-test
  (testing "BigInteger roundtrips through :db.type/bigint"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/bigint}}
      (fn [conn]
        (let [big (biginteger 99999999999999999999999999)]
          (d/transact! conn [{:test/id 1 :test/val big}])
          (let [result (:test/val (d/pull @conn '[*] [:test/id 1]))]
            (is (= big result))
            (is (instance? java.math.BigInteger result))))))))

(deftest bigdec-roundtrip-test
  (testing "BigDecimal roundtrips through :db.type/bigdec"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/bigdec}}
      (fn [conn]
        (let [big (bigdec 1.23456789012345678901234567890)]
          (d/transact! conn [{:test/id 1 :test/val big}])
          (let [result (:test/val (d/pull @conn '[*] [:test/id 1]))]
            (is (= big result))
            (is (instance? java.math.BigDecimal result))))))))

(deftest bytes-roundtrip-test
  (testing "byte arrays roundtrip through :db.type/bytes"
    (tu/with-temp-conn
      {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
       :test/val {:db/valueType :db.type/bytes}}
      (fn [conn]
        (let [bs (byte-array [1 2 3 4 5])]
          (d/transact! conn [{:test/id 1 :test/val bs}])
          (let [result (:test/val (d/pull @conn '[*] [:test/id 1]))]
            (is (bytes? result))
            (is (java.util.Arrays/equals ^bytes bs ^bytes result))))))))

;;; ---------------------------------------------------------------------------
;;; Full Entity Roundtrip (Complex Schema)
;;; ---------------------------------------------------------------------------

(deftest full-entity-roundtrip-test
  (testing "complex entity with multiple types roundtrips"
    (let [malli-schema [:map
                        [:entity/id {:db/unique :db.unique/identity} :string]
                        [:entity/name :string]
                        [:entity/count :int]
                        [:entity/score :double]
                        [:entity/active :boolean]
                        [:entity/type [:enum :alpha :beta :gamma]]
                        [:entity/uuid :uuid]
                        [:entity/tags [:set :keyword]]
                        [:entity/note [:maybe :string]]]
          dl-schema (db-schema/malli-map->datalevin-schema malli-schema)]
      (tu/with-temp-conn dl-schema
        (fn [conn]
          (doseq [i (range 10)]
            (let [generated (mg/generate malli-schema)
                  ;; Ensure unique id and strip nils
                  entity (-> generated
                             (assoc :entity/id (str "ent-" i))
                             strip-nils)
                  ;; Handle empty sets (Datalevin ignores empty colls)
                  entity (if (and (contains? entity :entity/tags)
                                  (empty? (:entity/tags entity)))
                           (dissoc entity :entity/tags)
                           entity)]
              (d/transact! conn [entity])
              (let [result (strip-db-id (d/pull @conn '[*] [:entity/id (str "ent-" i)]))]
                ;; Basic assertions
                (is (= (str "ent-" i) (:entity/id result)))
                (is (string? (:entity/name result)))
                (is (integer? (:entity/count result)))
                (is (double? (:entity/score result)))
                (is (boolean? (:entity/active result)))
                (is (#{:alpha :beta :gamma} (:entity/type result)))
                (is (uuid? (:entity/uuid result)))
                ;; Tags: if present, should be a vector (cardinality-many)
                (when (:entity/tags result)
                  (is (vector? (:entity/tags result))))
                ;; Note: may or may not be present
                (when (contains? entity :entity/note)
                  (is (= (:entity/note entity) (:entity/note result))))))))))))

;;; ---------------------------------------------------------------------------
;;; Bridge Gap Detection
;;; ---------------------------------------------------------------------------

(deftest bridge-missing-bigint-mapping-test
  (testing "bridge has no mapping for bigint (gap)"
    (is (nil? (db-schema/malli-type->datalevin-type :bigint))
        "No Malli :bigint type exists -- this is a known gap")))

(deftest bridge-missing-bytes-mapping-test
  (testing "bridge has no mapping for bytes (gap)"
    (is (nil? (db-schema/malli-type->datalevin-type :bytes))
        "No Malli :bytes type exists -- this is a known gap")))

(deftest bridge-mixed-enum-returns-nil-test
  (testing "mixed-type enum cannot be mapped"
    (let [schema [:map [:test/val [:enum :a "b"]]]
          dl (db-schema/malli-map->datalevin-schema schema)]
      (is (empty? dl)
          "Mixed enum should be skipped (no consistent Datalevin type)"))))

(deftest bridge-float-generator-type-mismatch-test
  (testing "Malli :float generator produces Double, not Float"
    (let [val (mg/generate :float)]
      (is (instance? Double val)
          "Malli :float generates Double -- bridge must account for Datalevin coercion"))))
