(ns seon.schema.admission-gate-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]
            [seon.schema.admission :as admission]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]))

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
        "ruling #48 requires open map declarations and authored contract admission paths; remove from")))

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
  (testing "a readable malformed declaration is refused, naming it"
    (let [findings
          (admission/admit
           {::admission/declarations
            {:malformed.fixture/value [:not-a-schema]}
            ::admission/registry {}})]
      (is (= [:malformed.fixture/value]
             (into [] (comp (filter #(= :error (:level %)))
                            (map :seon.schema.admission/declaration))
                   findings))))))

(deftest house-rules-are-distinct-error-findings
  (testing "registry keys are fully namespaced"
    (is (= 1
           (count
            (findings-of-type
             :schema-unqualified-key
             (admission/admit
              {::admission/declarations {:value :string}
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

(deftest exact-shape-reuse-is-an-advisory-finding
  (let [findings
        (admission/admit
         {::admission/declarations
          {:candidate.fixture/value [:string {:max 12 :min 1}]}
          ::admission/registry
          {:existing.fixture/value [:string {:min 1 :max 12}]
           :also-existing.fixture/value [:string {:max 12 :min 1}]}})
        exact (findings-of-type :schema-exact-reuse findings)]
    (is (= 1 (count exact)))
    (is (= :warning (:level (first exact))))
    (is (= [:also-existing.fixture/value :existing.fixture/value]
           (:seon.schema.admission/similar-keys (first exact))))
    (is (str/includes?
         (:message (first exact))
         "delete :candidate.fixture/value and reuse that existing key"))))

(deftest structureless-markers-do-not-produce-exact-reuse-noise
  (let [findings
        (admission/admit
         {::admission/declarations
          {:candidate.fixture/marker [:= true]
           :candidate.fixture/reference :existing.fixture/value}
          ::admission/registry
          {:existing.fixture/marker [:= true]
           :existing.fixture/reference :existing.fixture/value
           :existing.fixture/value :string}})]
    (is (empty? (findings-of-type :schema-exact-reuse findings)))))

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

(deftest a-clean-declaration-produces-no-findings
  (is (empty?
       (admission/admit
        {::admission/declarations {:fresh/zorb [:string {:min 1}]}
         ::admission/registry {:existing/alpha :int}}))))

(defn- register-verdict
  "`:admitted`, or the declared refusal kind, from `schema/register!` staged in
   an isolated delta over `projection` with `source` admission."
  [projection source schema-key form]
  (try
    (schema/call-with-registration-delta
     (schema/begin-registration-delta projection)
     {:seon.schema.admission/source source}
     #(schema/register! schema-key form))
    :admitted
    (catch clojure.lang.ExceptionInfo failure
      (or (:seon.schema/error (ex-data failure)) :refused))))

(defn- hook-verdict
  "`:admitted`, or the first blocking finding's declared refusal kind."
  [projection source schema-key form]
  (if-let [blocking (first (filter #(= :error (:level %))
                                   (admission/admit
                                    {::admission/declarations {schema-key form}
                                     ::admission/registry
                                     (:seon.schema.projection/forms projection)
                                     ::admission/source source})))]
    (or (:seon.schema/error blocking) :refused)
    :admitted))

(deftest one-contract-text-gets-one-verdict-at-every-entrance
  (let [forms (schema/snapshot)
        projection
        (schema/build-projection
         forms {}
         {:seon.schema/schema-admissions
          (zipmap (keys forms) (repeat {:seon.schema.admission/source :core}))})
        cases
        {:unexplained-predicate [:fn {:gen/schema :int} 'clojure.core/int?]
         :explained-predicate
         [:fn {:gen/schema :int :error/message "must be an integer"} 'clojure.core/int?]
         :nilable-value [:maybe :string]
         :nil-union-value [:or :nil :string]
         :nilable-member [:map [:one-verdict.fixture/member [:maybe :string]]]
         :nil-union-member [:map [:one-verdict.fixture/member [:or :nil :string]]]
         :bare-any [:vector :any]
         :exempted-any
         [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                :seon.schema.admission/reason "External value has no stable predicate."
                :gen/schema :string}]
         :complete [:string {:min 1}]}
        verdicts
        (for [source [:agent :core]
              [case-name form] (sort-by key cases)
              :let [schema-key (keyword "one-verdict.fixture" (name case-name))]]
          [source case-name
           (register-verdict projection source schema-key form)
           (hook-verdict projection source schema-key form)])]
    (doseq [[_source _case-name registered hooked] verdicts]
      (is (= registered hooked)))
    (testing "an undefined slot is admitted from every source"
      (is (= #{:admitted}
             (set (for [[_ case-name registered hooked] verdicts
                        :when (= :bare-any case-name)
                        verdict [registered hooked]]
                    verdict)))))
    (testing "both nilable spellings refuse as a named value schema"
      (is (= #{:seon.schema/nilable-value-schema}
             (set (for [[_ case-name registered hooked] verdicts
                        :when (#{:nilable-value :nil-union-value} case-name)
                        verdict [registered hooked]]
                    verdict)))))
    (testing "the one rule refuses an agent's incomplete contracts"
      (is (= :seon.schema/nilable-map-value
             (some (fn [[source case-name _ hooked]]
                     (when (= [:agent :nilable-member] [source case-name]) hooked))
                   verdicts))))))

(deftest an-undefined-slot-is-advice-a-stored-one-is-refused
  ;; CLASS: `:any`/`:some` refused agent contracts (seon.schema.internal) and,
  ;; after 883cc29d8, core declarations at the hook. Owner ruling 2026-09-24:
  ;; no ban anywhere. Every source is admitted with one advisory, which the
  ;; recorded exemption silences. Only storage refuses: a stored attribute
  ;; needs a Datahike value type.
  (doseq [source [:core :agent]]
    (let [admit (fn [form]
                  (admission/admit
                   {::admission/declarations {:undefined-slot.fixture/value form}
                    ::admission/registry {:existing/alpha :int}
                    ::admission/source source}))
          advice #(findings-of-type :schema-polymorphism (admit %))
          contract [:=> [:cat :any] :int]]
      (is (empty? (filter #(= :error (:level %)) (admit contract)))
          "function contract with a bare :any is admitted")
      (is (= [[:warning [0 0]]] (mapv (juxt :level :seon.schema/path) (advice contract)))
          "with one advisory naming the slot")
      (is (= 1 (count (advice [:map [:undefined-slot.fixture/a :any]
                               [:undefined-slot.fixture/b :some]]))))
      (is (empty? (advice
                   [:any {:seon.schema.admission/exemption
                          :seon.schema.admission/polymorphic-boundary
                          :seon.schema.admission/reason "External value has no stable predicate."
                          :gen/schema :string}]))
          "the recorded exemption, reason and generator silence it")
      (is (empty? (advice [:string {:min 1}])) "a defined slot needs nothing")))
  (testing "a stored attribute schema :any is refused: it needs a storable type"
    (let [projection (schema/declaration-projection
                      (assoc (schema.edn/packaged-forms)
                             :undefined-slot.fixture/stored :any
                             :undefined-slot.fixture/entity
                             [:map {:seon.db/attributes true}
                              [:undefined-slot.fixture/stored :undefined-slot.fixture/stored]]))
          refusal (try (schema.datahike/malli->datahike-attr-in
                        projection :undefined-slot.fixture/stored)
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= {::schema.datahike/attr :undefined-slot.fixture/stored
              ::schema.datahike/form :any
              ::schema.datahike/value-type-unavailable true}
             (ex-data refusal))))))
