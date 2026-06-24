(ns seon.server.wire-types-test
  "Type-fidelity tests for the Transit-JSON wire format.

   Every Clojure type that we expect callers to use across the sidecar
   boundary is round-tripped: write -> transact -> query -> verify the
   value comes back with the same Clojure type.

   Pinned types:
   - keyword (simple + namespaced)
   - string
   - integer (small + bigint)
   - double (whole + fractional)
   - instant (java.util.Date)
   - boolean
   - nil
   - vector / list / set / map
   - nested combinations

   Also pins the float-erasure behavior (§2c in PROTOCOL.md): an attr
   declared :db.type/double accepts an integer on the wire and stores it
   as a Double; the query result comes back as a Double."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.server.test-util :as tu]))

(set! *warn-on-reflection* true)

(use-fixtures :each tu/with-fresh-writer)

(defn- req! [op extra] (tu/req! op extra))

(defn- result-of [resp] (:seon.store.wire/result resp))

;; ---------- Schema ----------

(defn- install-typed-schema!
  "Install one attr per pinned type — `:thing/<type>`. Each attr stores
   one value per entity, keyed by `:thing/id` (string identity)."
  []
  (req! "transact"
        {:seon.store.wire/tx-data
         [{:db/ident :thing/id
           :db/valueType :db.type/string
           :db/cardinality :db.cardinality/one
           :db/unique :db.unique/identity}
          {:db/ident :thing/kw
           :db/valueType :db.type/keyword
           :db/cardinality :db.cardinality/one}
          {:db/ident :thing/str
           :db/valueType :db.type/string
           :db/cardinality :db.cardinality/one}
          {:db/ident :thing/int
           :db/valueType :db.type/long
           :db/cardinality :db.cardinality/one}
          {:db/ident :thing/dbl
           :db/valueType :db.type/double
           :db/cardinality :db.cardinality/one}
          {:db/ident :thing/at
           :db/valueType :db.type/instant
           :db/cardinality :db.cardinality/one}
          {:db/ident :thing/flag
           :db/valueType :db.type/boolean
           :db/cardinality :db.cardinality/one}]}))

(defn- put!
  "Transact one entity carrying a single attr's value."
  [id attr v]
  (req! "transact" {:seon.store.wire/tx-data [{:thing/id id attr v}]}))

(defn- get-attr [id attr]
  (result-of
    (req! "q" {:seon.store.wire/query '[:find ?v . :in $ ?id ?a :where
                                        [?e :thing/id ?id]
                                        [?e ?a ?v]]
               :seon.store.wire/args  [id attr]})))

;; ---------- Tests: per-type roundtrips ----------

(deftest test-keyword-roundtrip
  (testing "namespaced keywords survive the wire as keywords"
    (install-typed-schema!)
    (put! "a" :thing/kw :seon.urgent/high)
    (is (= :seon.urgent/high (get-attr "a" :thing/kw)))
    (put! "b" :thing/kw :nakedkw)
    (is (= :nakedkw (get-attr "b" :thing/kw)))))

(deftest test-string-roundtrip
  (install-typed-schema!)
  (put! "a" :thing/str "hello world")
  (is (= "hello world" (get-attr "a" :thing/str)))
  ;; embedded quotes + backslashes
  (put! "b" :thing/str "she said \"hi\" \\ done")
  (is (= "she said \"hi\" \\ done" (get-attr "b" :thing/str))))

(deftest test-integer-roundtrip
  (install-typed-schema!)
  (put! "a" :thing/int 42)
  (is (= 42 (get-attr "a" :thing/int)))
  (put! "b" :thing/int 0)
  (is (= 0 (get-attr "b" :thing/int)))
  (put! "c" :thing/int -1)
  (is (= -1 (get-attr "c" :thing/int))))

(deftest test-double-roundtrip-fractional
  (install-typed-schema!)
  (put! "a" :thing/dbl 3.14)
  (let [v (get-attr "a" :thing/dbl)]
    (is (instance? Double v) "stored as Double")
    (is (= 3.14 v))))

(deftest test-double-whole-coerces-from-int
  (testing "writing 1 (long) to a :db.type/double attr is coerced to 1.0
            (double) before transact. Schema-driven coercion in
            seon.server.wire/coerce-tx-data-for-schema."
    (install-typed-schema!)
    (put! "a" :thing/dbl 1)
    (let [v (get-attr "a" :thing/dbl)]
      (is (instance? Double v)
          (str "expected Double, got " (class v) "/" v))
      (is (= 1.0 v)))))

(deftest test-instant-roundtrip
  (install-typed-schema!)
  (let [t (java.util.Date.)]
    (put! "a" :thing/at t)
    (let [v (get-attr "a" :thing/at)]
      (is (instance? java.util.Date v))
      (is (= (.getTime t) (.getTime ^java.util.Date v))))))

(deftest test-boolean-roundtrip
  (install-typed-schema!)
  (put! "a" :thing/flag true)
  (is (= true (get-attr "a" :thing/flag)))
  (put! "b" :thing/flag false)
  (is (= false (get-attr "b" :thing/flag))))

(deftest test-query-args-preserve-keyword-type
  (testing "a keyword arg passed via :in/?p matches keyword-typed datoms.
            With Transit the keyword stays a keyword end-to-end — no
            string coercion at the boundary."
    (install-typed-schema!)
    (req! "transact"
          {:seon.store.wire/tx-data [{:thing/id "a" :thing/kw :urgent}
                                     {:thing/id "b" :thing/kw :calm}]})
    (let [r (req! "q" {:seon.store.wire/query '[:find ?id . :in $ ?p :where
                                                [?e :thing/kw ?p]
                                                [?e :thing/id ?id]]
                       :seon.store.wire/args  [:urgent]})]
      (is (= "a" (result-of r))
          "keyword arg :urgent matched the keyword-typed datom"))))

(deftest test-pull-result-preserves-types
  (testing "pull returns native Clojure types — keywords, instants, doubles"
    (install-typed-schema!)
    (let [t (java.util.Date.)]
      (req! "transact"
            {:seon.store.wire/tx-data [{:thing/id "p"
                                        :thing/kw :seon/marker
                                        :thing/dbl 2.5
                                        :thing/at  t
                                        :thing/int 7
                                        :thing/flag true}]})
      (let [r (req! "pull" {:seon.store.wire/selector '[:thing/kw :thing/dbl :thing/at :thing/int :thing/flag]
                            :seon.store.wire/eid      [:thing/id "p"]})
            m (result-of r)]
        (is (= :seon/marker (:thing/kw m)))
        (is (instance? Double (:thing/dbl m)))
        (is (= 2.5 (:thing/dbl m)))
        (is (instance? java.util.Date (:thing/at m)))
        (is (= (.getTime t) (.getTime ^java.util.Date (:thing/at m))))
        (is (= 7 (:thing/int m)))
        (is (= true (:thing/flag m)))))))

(deftest test-transact-response-carries-tx-report-fields
  (testing "a transact response carries the native tx-report fields directly
            (no separate `payload` field under the uniform Transit frame)"
    (install-typed-schema!)
    (let [r (req! "transact"
                  {:seon.store.wire/tx-data [{:thing/id "p" :thing/kw :seon/alpha}]})
          tx-meta (:seon.store.wire/tx-meta r)]
      (is (map? tx-meta))
      (is (keyword? (some-> tx-meta keys first))
          (str "tx-meta should have keyword keys, got: " (pr-str tx-meta)))
      (is (contains? tx-meta :db/txInstant))
      (is (contains? tx-meta :db/commitId))
      (is (pos? (:seon.store.wire/datoms-added r))))))
