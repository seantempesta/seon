(ns seon.schema.admission-gate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.schema.admission :as admission]))

(defn- findings-of-type
  [finding-type findings]
  (filterv #(= finding-type (:type %)) findings))

(deftest malformed-inputs-name-the-reader-or-malli-finding
  (testing "non-EDN-readable source retains the reader location"
    (let [finding
          (first
           (findings-of-type
            :schema-edn-read
            (admission/admit
             {::admission/source "{:broken/value [:map}"
              ::admission/file "broken.edn"
              ::admission/registry {}})))]
      (is (= :error (:level finding)))
      (is (= 1 (:row finding)))
      (is (pos-int? (:col finding)))))
  (testing "a readable malformed declaration is refused by Malli"
    (let [findings
          (admission/admit
           {::admission/declarations
            {:malformed.fixture/value [:not-a-schema]}
            ::admission/registry {}})]
      (is (= 1
             (count
              (findings-of-type :schema-malli-compilation findings)))))))

(deftest house-rules-are-distinct-error-findings
  (testing "registry keys are fully namespaced"
    (is (= 1
           (count
            (findings-of-type
             :schema-unqualified-key
             (admission/admit
              {::admission/declarations {:value :string}
               ::admission/registry {}}))))))
  (testing "polymorphic schemas require an explicit recorded exemption"
    (is (= 1
           (count
            (findings-of-type
             :schema-polymorphism
             (admission/admit
              {::admission/declarations
               {:maybe.fixture/value [:maybe :string]}
               ::admission/registry {}}))))))
  (testing "closed maps are refused"
    (is (= 1
           (count
            (findings-of-type
             :schema-closed-map
             (admission/admit
              {::admission/declarations
               {:closed.fixture/value [:map {:closed true}]}
               ::admission/registry {}}))))))
  (testing "database references use the declared :seon.db/ref schema"
    (is (= 1
           (count
            (findings-of-type
             :schema-direct-database-ref
             (admission/admit
              {::admission/declarations
               {:ref.fixture/value [:uuid {:seon.db/value-type :db.type/ref}]}
               ::admission/registry {}}))))))
  (testing "a declaration key belongs in its namespace-named file"
    (is (= 1
           (count
            (findings-of-type
             :schema-misplaced-key
             (admission/admit
              {::admission/declarations {:actual.namespace/value :string}
               ::admission/file "wrong.namespace.edn"
               ::admission/registry {}})))))))

(deftest a-recorded-polymorphism-exemption-is-recognized-as-data
  (let [findings
        (admission/admit
         {::admission/declarations
          {:opaque.fixture/value
           [:any
            {:seon.schema.admission/exemption
             :seon.schema.admission/polymorphic-boundary
             :seon.schema.admission/reason "External value has no stable predicate."
             :gen/schema :string}]}
          ::admission/registry {}})]
    (is (empty? (findings-of-type :schema-polymorphism findings)))))

(deftest exact-shape-reuse-is-an-advisory-finding
  (let [findings
        (admission/admit
         {::admission/declarations
          {:candidate.fixture/value [:string {:max 12 :min 1}]}
          ::admission/registry
          {:existing.fixture/value [:string {:min 1 :max 12}]}})
        exact (findings-of-type :schema-exact-reuse findings)]
    (is (= 1 (count exact)))
    (is (= :warning (:level (first exact))))
    (is (= :existing.fixture/value
           (:seon.schema.admission/similar-key (first exact))))
    (is (str/includes?
         (:message (first exact))
         "same shape exists as :existing.fixture/value"))))

(deftest name-token-overlap-is-ranked-and-capped-at-three
  (let [findings
        (admission/admit
         {::admission/declarations
          {:invoice.line/item-count :int}
          ::admission/registry
          {:invoice.line/item-average :double
           :invoice.line/item-total :number
           :invoice/item-total :pos-int
           :other/item-total :nat-int}})
        overlaps (findings-of-type :schema-name-overlap findings)]
    (is (= 3 (count overlaps)))
    (is (every? #(= :warning (:level %)) overlaps))
    (is (= [:invoice.line/item-average
            :invoice.line/item-total
            :invoice/item-total]
           (mapv :seon.schema.admission/similar-key overlaps)))))

(deftest a-clean-declaration-produces-no-findings
  (is (empty?
       (admission/admit
        {::admission/declarations {:fresh/zorb [:string {:min 1}]}
         ::admission/registry {:existing/alpha :int}}))))
