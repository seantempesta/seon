(ns seon.schema.admission-gate-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.schema.admission :as admission]))

(defn- findings-of-type
  [finding-type findings]
  (filterv #(= finding-type (:type %)) findings))

(deftest open-maps-cannot-regress-in-declarations-or-contract-admission
  (let [closed-property (str "{" ":closed true}")
        closed-property?
        (fn [value]
          (boolean
           (some #(and (map? %) (true? (:closed %)))
                 (tree-seq coll? seq value))))
        schema-files
        (->> (file-seq (io/file "resources/seon/schemas"))
             (filter #(.isFile ^java.io.File %))
             (filter #(str/ends-with? (.getName ^java.io.File %) ".edn")))
        admission-files
        (map io/file ["src/seon/schema.clj"
                      "src/seon/schema/internal.cljc"
                      "src/seon/sci/eval.clj"])
        declaration-offenders
        (keep (fn [file]
                (when (closed-property? (edn/read-string (slurp file)))
                  (.getPath ^java.io.File file)))
              schema-files)
        admission-offenders
        (keep (fn [file]
                (when (str/includes? (slurp file) closed-property)
                  (.getPath ^java.io.File file)))
              admission-files)
        offenders (into [] (concat declaration-offenders
                                   admission-offenders))]
    (is (empty? offenders)
        (str "ruling #48 requires open map declarations and authored "
             "contract admission paths; remove " closed-property " from "
             (pr-str offenders)))))

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
         "delete :candidate.fixture/value and reuse :existing.fixture/value"))))

(deftest exact-key-collision-is-the-only-reuse-refusal
  (let [findings
        (admission/admit
         {::admission/declarations {:existing.fixture/value :int}
          ::admission/registry {:existing.fixture/value :string}})
        collision (findings-of-type :schema-key-collision findings)]
    (is (= 1 (count collision)))
    (is (= :error (:level (first collision))))
    (is (str/includes? (:message (first collision))
                       "exact-key redefinition is refused"))))

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

(deftest predicate-error-messages-are-advised-without-blocking-admission
  (let [without-message
        (admission/admit
         {::admission/declarations
          {:predicate.fixture/value
           [:fn {:gen/schema :int} clojure.core/int?]}
          ::admission/registry {}})
        with-message
        (admission/admit
         {::admission/declarations
          {:predicate.fixture/value
           [:fn {:error/message "must be an integer"
                 :gen/schema :int}
            clojure.core/int?]}
          ::admission/registry {}})
        missing
        (findings-of-type :schema-predicate-missing-error-message
                          without-message)]
    (is (= 1 (count missing)))
    (is (= :warning (:level (first missing)))
        "a missing explanation teaches at declaration time but never blocks")
    (is (str/includes? (or (:message (first missing)) "")
                       ":error/message"))
    (is (empty?
         (findings-of-type :schema-predicate-missing-error-message
                           with-message)))))

(deftest packaged-predicate-declarations-all-explain-their-values
  (let [findings
        (admission/admit
         {::admission/path "resources/seon/schemas"})]
    (is (empty?
         (findings-of-type :schema-predicate-missing-error-message
                           findings)))))

(deftest a-clean-declaration-produces-no-findings
  (is (empty?
       (admission/admit
        {::admission/declarations {:fresh/zorb [:string {:min 1}]}
         ::admission/registry {:existing/alpha :int}}))))
