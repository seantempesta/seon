(ns seon.schema-test
  "Regression proofs for the canonical schema registration boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [seon.call-preparation :as call-preparation]
            [seon.db]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.form :as schema.form]
            [seon.schema.internal :as schema.internal]
            [seon.test-support :as test-support]))

(defn- reference-entry?
  [projection entry]
  (letfn [(reference? [form]
            (let [resolved (schema.datahike/resolve-malli-form-in projection form)]
              (or (= :seon.db/ref resolved)
                  (and (vector? resolved)
                       (#{:and :or :set :vector :sequential :seon.db/ref} (first resolved))
                       (some reference? (schema.datahike/form-children resolved))))))]
    (boolean (reference? (last entry)))))

(defn- reference-value
  [projection form value]
  (let [resolved (schema.datahike/resolve-malli-form-in projection form)
        children (schema.datahike/form-children resolved)]
    (case (schema.datahike/form-head resolved)
      :set #{(reference-value projection (first children) value)}
      (:vector :sequential) [(reference-value projection (first children) value)]
      :and (reference-value projection (first children) value)
      :or (reference-value projection (first (filter #(reference-entry? projection [::entry %]) children)) value)
      value)))

(defn- required-entry-value
  [projection form stored?]
  (let [resolved (schema.datahike/resolve-datahike-form-in projection form)
        form (if (and stored? (#{:set :vector :sequential} (schema.datahike/form-head resolved)))
               (into [(first resolved)
                      (assoc (or (schema.form/attr-form-properties resolved) {}) :min 1)]
                     (schema.datahike/form-children resolved))
               form)]
    (mg/generate (m/schema form {:registry (:seon.schema.projection/registry projection)})
                 {:seed 20260916 :size (if stored? 1 0)})))

(defn- fixture-generation-projection
  [projection connection lock]
  (let [database (seon.db/db connection)
        ctx (test-support/fork-cluster-ctx connection)
        supplied {'seon.db/connection-generator connection
                  'seon.cluster.store/connection-generator connection
                  'seon.db/database-value-generator database
                  'seon.cluster.store/database-value-generator database
                  'seon.cluster.store/file-lock-generator lock
                  'seon.sci.eval/ctx-generator ctx}]
    (schema/declaration-projection
     (walk/postwalk
      (fn [value]
        (if-let [entry (and (map? value) (find supplied (:gen/gen value)))]
          (assoc value :gen/gen (gen/return (val entry)))
          value))
      (:seon.schema.projection/forms projection)))))

(defn- conjunctive-map-entries
  "Include map entries inherited through every named conjunction arm."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value]
                  [:vector :seon.schema/value]]}
  [projection form]
  (let [resolved (schema.datahike/resolve-malli-form-in projection form)]
    (case (schema.datahike/form-head resolved)
      :map (schema.form/map-entries resolved)
      :and (into [] (mapcat #(conjunctive-map-entries projection %))
                 (schema.datahike/form-children resolved))
      [])))

(deftest declared-reference-maps-accept-the-pull-reference-grammar
  (test-support/with-database
   (fn [connection]
     (let [lock-path (java.nio.file.Files/createTempFile
                      (.toPath (java.io.File. "tmp")) "pulled-ref-" ".lock"
                      (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
       (with-open [channel (java.nio.channels.FileChannel/open
                           lock-path (into-array java.nio.file.OpenOption
                                                 [java.nio.file.StandardOpenOption/WRITE]))
                   lock (.lock channel)]
        (let [database (seon.db/db connection)
           projection (schema/projection-from-database database)
           generation (fixture-generation-projection projection connection lock)
           forms (:seon.schema.projection/forms projection)
           subjects (into (sorted-map)
                          (keep (fn [[schema-key form]]
                                  (when (schema.form/map-shape? form)
                                   (let [entries (conjunctive-map-entries projection form)]
                                    (when (some #(reference-entry? projection %) entries)
                                      [schema-key entries])))))
                          forms)
           target (:db/id (seon.db/pull database [:db/id] [:seon.ns/name 'seon.schema]))
           storable? #(true? (:seon.db/attributes (schema.form/schema-properties (forms %))))]
       (is (pos-int? target))
       (is (seq subjects) "the packaged projection must declare entity refs")
       (is (contains? subjects :seon.eval/entity) "the failing reader schema is covered")
       (println "Pulled-reference map contracts:" (count subjects)
                "storable:" (count (filter storable? (keys subjects))))
       (is (= :db.type/ref (schema.datahike/form->datahike-value-type-in projection :seon.db/ref)))
       (doseq [[schema-key entries] subjects]
         (testing (str schema-key)
          (try
           (let [stored? (storable? schema-key)
                 row (into {}
                           (keep (fn [[attribute options :as entry]]
                                   (let [reference? (reference-entry? projection entry)]
                                     (when (or reference? (not (and (map? options) (:optional options))))
                                       [attribute
                                        (if reference?
                                          (reference-value
                                           projection (last entry)
                                           (if (and stored? (schema/identity-attr? forms attribute))
                                             target
                                             {:db/id (if stored? target 1)}))
                                          (required-entry-value generation (last entry) stored?))]))))
                           entries)
                 _ (is ((schema/projection-validator projection schema-key) row)
                       (pr-str {:schema schema-key
                                :errors (mapv :in (:errors ((schema/projection-explainer projection schema-key) row)))}))]
             ;; This checks reference grammar, not whether generated refs describe
             ;; valid ownership. Real component values are covered below and by G5.
             (is (map? row)))
           (catch Throwable failure
             (is false (str schema-key ": " (ex-message failure)))))))))
       (finally (java.nio.file.Files/deleteIfExists lock-path)))))))

(deftest canonical-reference-values-use-the-pull-collection-grammar
  (test-support/with-database
   (fn [connection]
     (let [database (seon.db/db connection)
           attributes (for [[attribute properties] (:schema database)
                            :when (= :db.type/ref (:db/valueType properties))]
                        attribute)
           populated (keep (fn [attribute]
                             (when-let [datom (first (seon.db/datoms database :aevt attribute))]
                               [attribute (:e datom)])) attributes)]
       (is (seq populated))
       (println "Canonical populated reference attributes:" (count populated)
                "declared:" (count attributes))
       (doseq [[attribute entity] populated]
         (let [value (get (seon.db/pull database [attribute] entity) attribute)
               many? (= :db.cardinality/many (get-in database [:schema attribute :db/cardinality]))
               ids (if many? (map :db/id value) [(:db/id value)])
               expected (set (map :v (seon.db/datoms database :eavt entity attribute)))]
           (is (if many? (vector? value) (map? value)) (str attribute))
           (is (and (seq ids) (every? expected ids))
               (str "Pulled ids come from the actual relation: " attribute))))))))

(defn- refusal
  [thunk]
  (try
    (thunk)
    ::committed
    (catch clojure.lang.ExceptionInfo failure
      failure)))

(defn- registration-delta
  []
  (schema/begin-registration-delta
   (schema/build-projection (schema/registered-schemas))))

(deftest database-projections-reuse-only-the-handed-value
  (test-support/with-database
   (fn [connection]
     (let [before @connection
           projection (schema/projection-from-database before)
           repeated (schema/projection-from-database before projection)]
       (is (identical? projection repeated)
           "the caller's unchanged projection is reused")
       (d/transact
        connection
        [{:seon.schema/key :seon.schema-test/cache-revision
          :seon.schema/form ":string"
          :seon.schema.admission/source :core}])
       (let [after (schema/projection-from-database @connection projection)]
         (is (not (identical? projection after)))
         (is (= :string (get (:seon.schema.projection/forms after)
                             :seon.schema-test/cache-revision)))
         (is (nil? (get (:seon.schema.projection/forms projection)
                        :seon.schema-test/cache-revision)))
         (is (identical? projection
                         (schema/projection-from-database before projection))
             "a newer database cannot change the old projection"))))))

(deftest function-input-fit-is-total-for-an-absent-contract
  (let [projection (schema/build-projection (schema/registered-schemas))]
    (is (false? (schema/function-accepts-in?
                 projection 'seon.schema-test/missing [{}])))))

(deftest function-input-and-output-fit-belong-to-one-arity
  (let [function-symbol 'seon.schema-test/cross-arity
        projection
        (schema/build-projection
         (schema.edn/packaged-forms)
         {function-symbol
          [:function
           [:=> [:cat :int] :int]
           [:=> [:cat :string :string] :string]]})]
    (is (schema/function-accepts-in? projection function-symbol [7]))
    (is (schema/function-returns-in? projection function-symbol :string))
    (is (false?
         (schema/function-accepts-and-returns-in?
          projection function-symbol [7] :string))
        "different arities cannot satisfy the input and output halves")
    (is (schema/function-accepts-and-returns-in?
         projection function-symbol [7] :int))
    (is (schema/function-accepts-and-returns-in?
         projection function-symbol ["left" "right"] :string))
    (is (false?
         (schema/function-accepts-and-returns-in?
          projection 'seon.schema-test/missing [7] :int)))))

(deftest every-predicate-schema-declares-what-it-accepts
  (let [missing (volatile! [])]
    (walk/postwalk
     (fn [form]
       (when (and (vector? form)
                  (= :fn (first form))
                  (not (and (map? (second form))
                            (string? (:error/message (second form)))
                            (not-empty (:error/message (second form))))))
         (vswap! missing conj form))
       form)
     (schema/registered-schemas))
    (is (empty? @missing)
        (str "predicate schemas without :error/message: " (pr-str @missing)))))

(deftest canonical-definition-keeps-admitted-predicate-symbols
  (let [definition
        [:=> [:cat :qualified-symbol [:fn 'clojure.core/ifn?]]
         :qualified-symbol]
        projection
        (schema/declaration-projection (schema.edn/packaged-forms))]
    (is (= definition (schema/canonical-definition definition {})))
    (schema/call-with-projection
     projection
     (fn []
       (is (schema/malli-form? definition))
       ;; The probe namespace deliberately has NO file on any classpath, so it
       ;; is unloaded in every JVM by construction — no shared load-state
       ;; assumption and no global mutation (both prior shapes broke pooled
       ;; workers). If validation ever attempted to load it, the attempt would
       ;; throw FileNotFound and surface here as an error.
       (is (nil? (find-ns 'seon.schema-test.no-such-probe))
           "the arbitrary predicate namespace is unloaded by construction")
       (is (false?
            (schema/malli-form?
             [:fn 'seon.schema-test.no-such-probe/probe-predicate?]))
           "schema validation never loads an arbitrary predicate namespace")
       (is (nil? (find-ns 'seon.schema-test.no-such-probe))
           "and asking the question did not load it either")))))

(deftest an-incremental-build-resolves-against-the-projection-in-hand
  ;; CLASS: a definition contract that pre-reads the AMBIENT declaration
  ;; population while the authority holds the projection being extended. The
  ;; two worlds disagree by construction — the second key of an incremental
  ;; build references the first, which the projection in hand resolves and the
  ;; ambient population does not — so `projection-with-schema` was refused for
  ;; a reference it had itself just added. With no projection bound at all the
  ;; ambient read does not merely disagree, it throws, and the predicate
  ;; answered false for every form.
  ;;
  ;; The repair is the owner law: the predicate answers the STRUCTURAL
  ;; question and the authority re-decides resolution against what it holds.
  (let [base-key :seon.schema-test/incremental-base
        direct-key :seon.schema-test/incremental-direct
        admission {:seon.schema.admission/source :core}]
    (testing "the definition contract never asks the ambient population"
      (is (true? (schema/malli-form? [:and {:seon.db/index true} base-key]))
          "a reference no population in hand defines is still a Malli form")
      (is (false? (schema/malli-form? "not a form")))
      (is (false? (schema/malli-form? [:map [:only-a-key]]))
          "a form Malli cannot parse is still refused"))
    (testing "each step resolves the key the step before it added"
      (let [built (reduce-kv
                   (fn [current schema-key definition]
                     (schema/projection-with-schema
                      current schema-key definition admission))
                   (schema/build-projection {})
                   (array-map
                    base-key [:int {:seon.db/index true}]
                    direct-key [:and {:seon.db/index true} base-key]))]
        (is (= [:int {:seon.db/index true}]
               (get-in built [:seon.schema.projection/forms base-key])))
        (is (= #{base-key}
               (get-in built [:seon.schema.projection/schema-dependencies
                              direct-key])))
        (let [valid? (schema/projection-validator built direct-key)]
          (is (true? (valid? 7)))
          (is (false? (valid? "seven"))))))))

(deftest two-projections-never-exchange-a-compiled-validator
  ;; CLASS: compiled validators and explainers are a pure function of the
  ;; projection they were compiled from, but they used to live in ONE
  ;; process-global slot whose read was a check-then-act — reset the slot to
  ;; the caller's projection, then deref it AGAIN for the answer. Between
  ;; those two reads a second environment could reset the slot to its own
  ;; projection, so a caller silently validated against another environment's
  ;; schema. Reproduced in both directions, intermittently, 2 runs in 5
  ;; (2026-08-07 parallel isolation audit, Defect II,
  ;; `probe_shape_generation_cache`) — a flake, which is exactly why it had
  ;; survived: a suite would have triaged it as noise.
  ;;
  ;; It is dissolved structurally: the cache hangs off the projection value,
  ;; so there is no slot for two projections to share and no comparison that
  ;; can be wrong. The repetition below is the probe's own shape, kept
  ;; because an intermittent race needs iterations to be falsified at all.
  (let [population (schema/registered-schemas)
        project (fn [marker]
                  (schema/build-projection
                   (assoc population
                          :seon.schema-test/marker [:= marker]
                          :seon.schema-test/thing
                          [:map [:seon.schema-test/marker
                                 :seon.schema-test/marker]])))
        projection-a (project "a")
        projection-b (project "b")
        value-a {:seon.schema-test/marker "a"}
        value-b {:seon.schema-test/marker "b"}
        matches? (fn [projection value]
                   (boolean
                    (some #(= :seon.schema-test/thing (:seon.schema/key %))
                          (schema/matching-shapes-in projection value))))
        iterations 2000
        side (fn [projection own foreign label]
               (fn []
                 (into []
                       (comp (map (fn [i]
                                    (cond
                                      (not (matches? projection own))
                                      {:side label :iteration i
                                       :expected :match :got :no-match}
                                      (matches? projection foreign)
                                      {:side label :iteration i
                                       :expected :no-match :got :match})))
                             (remove nil?))
                       (range iterations))))
        violations
        (mapcat deref
                [(future ((side projection-a value-a value-b :a)))
                 (future ((side projection-b value-b value-a :b)))])]
    (is (empty? violations)
        (str "a projection answered with another projection's compiled "
             "validator: " (pr-str (vec (take 5 violations)))))
    (testing "the compiled state is on the projection, not in a shared slot"
      (is (some? (:seon.schema.projection/compiled projection-a)))
      (is (not (identical? (:seon.schema.projection/compiled projection-a)
                           (:seon.schema.projection/compiled projection-b))))
      (is (not (contains? (schema/projection-pure-data projection-a)
                          :seon.schema.projection/compiled))
          "and it is runtime state, never part of the projection's EDN"))))

(defn predicate-under-test?
  "Root-rebound by the collision regression below. Its value is never asserted
   directly; what is asserted is which environment's answer a projection gets."
  [value]
  (= value :original))

(deftest one-predicate-symbol-cannot-name-two-environments-callables
  ;; CLASS: two isolated environments declaring the same qualified predicate
  ;; symbol used to overwrite each other process-wide, last writer winning, so
  ;; a value valid under the first stopped validating after the second —
  ;; though both projections were rebuilt from identical immutable form data
  ;; (2026-08-07 parallel isolation audit, Defect I.3,
  ;; `probe_predicate_function_cache`, deterministic FAIL).
  ;;
  ;; The class is dissolved by construction rather than defended against: a
  ;; qualified symbol names exactly ONE Var, and a projection that wants a
  ;; different callable must SAY SO in its own explicit predicate-functions,
  ;; which no other projection reads. There is no process-global slot left to
  ;; overwrite, so the two assertions here are "each environment keeps its own
  ;; answer" and "no such slot exists".
  (let [predicate 'seon.schema-test/predicate-under-test?
        form [:fn predicate]
        forms (assoc (schema/registered-schemas)
                     :seon.schema-test/predicated form)
        project (fn [predicate-functions]
                  (schema/build-projection
                   forms {}
                   {:seon.schema/predicate-functions predicate-functions}))
        valid? (fn [projection value]
                 ((schema/projection-validator
                   projection :seon.schema-test/predicated)
                  value))
        environment-a (project {predicate (fn [value] (= value :a))})
        environment-b (project {predicate (fn [value] (= value :b))})]
    (testing "a second environment's declaration cannot reach the first"
      (is (true? (valid? environment-a :a)))
      (is (false? (valid? environment-a :b)))
      (is (true? (valid? environment-b :b)))
      (is (false? (valid? environment-b :a)))
      (is (true? (valid? environment-a :a))
          "and the first still answers for itself after the second was built"))
    (testing "the probe's own move — a second registration of one symbol —
              is now refused instead of quietly winning process-wide"
      ;; This is the arm that reproduces `probe_predicate_function_cache`.
      ;; It used to succeed and silently retarget the symbol for every
      ;; environment in the JVM; it now cannot even be expressed, because a
      ;; registration that does not agree with the Var the symbol names is a
      ;; core bug rather than a new binding.
      (let [refused (refusal
                     #(schema/register-core-predicate!
                       predicate (fn [value] (= value :b))))]
        (is (instance? clojure.lang.ExceptionInfo refused)
            "registering a different callable under a live symbol must refuse")
        (is (= :seon.schema/unresolved-predicate
               (:seon.schema/error (ex-data refused))))
        (is (true? (valid? (project {}) :original))
            "and the refused attempt changed nothing for anybody")))
    (testing "an environment that declares nothing resolves the one named Var"
      (let [resolved (project {})]
        (is (true? (valid? resolved :original)))
        (is (false? (valid? resolved :a)))))
    (testing "no process-global predicate cache survives to be overwritten"
      (is (empty?
           (filter (fn [[symbol-name a-var]]
                     (and (instance? clojure.lang.IDeref (var-get a-var))
                          (str/includes? (str symbol-name) "predicate")))
                   (ns-interns 'seon.schema)))
          (str "seon.schema holds a mutable reference named for predicates; "
               "predicate resolution is requiring-resolve over a qualified "
               "symbol and must own no process-global state.")))))

(deftest named-predicate-violations-humanize-to-the-declared-requirement
  (let [humanized
        (me/humanize
         (schema/explain-candidate-value
          :seon.db/database-value "not a database value"))]
    (is (str/includes? (pr-str humanized)
                       "must be an immutable Datahike database value"))
    (is (not (str/includes? (pr-str humanized) "unknown error")))))

(deftest equal-packaged-populations-reuse-the-shape-projection
  (let [forms (assoc (schema/registered-schemas)
                     :seon.schema-test/cache-sentinel :string)
        equal-copy (into {} forms)
        builds (atom 0)
        original-build schema/build-projection
        project! (fn [population]
                   (schema/call-with-forms
                    population
                    #(schema/identity-only-projection ::not-an-identity)))]
    (is (not (identical? forms equal-copy))
        "the probe supplies equal declaration values with distinct identities")
    (with-redefs [schema/build-projection
                  (fn [& arguments]
                    (swap! builds inc)
                    (apply original-build arguments))]
      (project! forms)
      (let [after-first @builds]
        (project! equal-copy)
        ;; An explicitly handed projection performs zero fallback builds. With
        ;; no handed projection, `build-projection`'s one-argument entry
        ;; delegates to its three-argument entry, so one complete build is
        ;; observed as two Var calls. Either way, the equal second population
        ;; must not start another build.
        (is (contains? #{0 2} after-first))
        (is (= after-first @builds)
            "an equal population does not start a second projection build")))))

(deftest acquired-projection-owns-schema-introspection
  (let [forms {:seon.schema-test/acquired :string}
        projection {:seon.schema.projection/forms forms}]
    (is (= forms
           (schema/call-with-projection
            projection schema/registered-schemas)))))

(deftest canonical-self-references-refuse-at-registration
  (let [schema-key :seon.schema-test/self]
      (doseq [[label definition]
              [["a direct canonical reference"
                [:or :string [:vector schema-key]]]
               ["an explicit canonical `:ref`"
                [:or :string [:vector [:ref schema-key]]]]]]
        (testing label
          (let [delta (registration-delta)
                failure
                (refusal
                 #(schema/call-with-registration-delta
                   delta (fn [] (schema/register! schema-key definition))))
                data (ex-data failure)]
            (is (instance? clojure.lang.ExceptionInfo failure)
                "the admission gate returns a legible refusal")
            (is (= :seon.schema/cyclic-reference
                   (:seon.schema/error data)))
            (is (= schema-key (:seon.schema/identity data)))
            (is (= [schema-key schema-key]
                   (:seon.schema/cycle-path data)))
            (is (= :user-input (:seon.error/kind data)))
            (is (str/includes? (ex-message failure)
                               (pr-str [schema-key schema-key]))
                "the refusal names the complete cycle")
            (is (nil? (schema/registration-delta-form delta schema-key))
                "a refused declaration never reaches the delta"))))))

(deftest canonical-mutual-recursion-refuses-but-local-recursion-is-supported
  (let [left :seon.schema-test/left
        right :seon.schema-test/right
        local :seon.schema-test/local-recursion
        local-node :seon.schema-test.local/node]
      (testing "a complete mutually recursive canonical population refuses"
        (let [failure
              (refusal
               #(seon.schema.edn/admit
                 {:seon.schema/forms
                  {left [:or :string [:vector right]]
                   right [:or :int [:vector [:ref left]]]}}))
              data (ex-data failure)]
          (is (instance? clojure.lang.ExceptionInfo failure))
          (is (= :seon.schema/cyclic-reference
                 (:seon.schema/error data)))
          (is (= [left right left]
                 (:seon.schema/cycle-path data)))))
      (testing "Malli's local recursive registry remains a supported shape"
        (let [delta (registration-delta)
              definition
              [:schema
               {:registry
                {local-node
                 [:or :string [:vector [:ref local-node]]]}}
               [:ref local-node]]]
          (is (= local
                 (schema/call-with-registration-delta
                  delta (fn [] (schema/register! local definition)))))
          (schema/call-with-registration-delta
           delta
           (fn []
             (is (schema/valid-candidate-value?
                  local ["root" ["leaf"]]))))
          (is (= definition
                 (schema/registration-delta-form delta local)))))))

(deftest map-shapes-accrete-additional-top-level-attributes
  (let [schema-key :seon.schema-test/rendered-entity
        render-html 'seon.schema-test/render-html
        forms (assoc (schema/registered-schemas)
                     schema-key
                     [:map {:seon.render/html render-html}
                      [:seon.schema-test/id :string]
                      [:seon.schema-test/rank {:optional true} :int]])
        projection (schema/build-projection forms)
        base {:seon.schema-test/id "one"}
        additional (assoc base
                          :seon.render/html
                          'my.agent/render-html)
        invalid (assoc additional :seon.schema-test/rank "first")]
    (testing "shape identity survives accretion"
      (is (= [schema-key schema-key]
             (mapv (fn [value]
                     (-> (schema/matching-shapes-in projection value)
                         first
                         :seon.schema/key))
                   [base additional])))
      (is (= render-html
             (-> (schema/matching-shapes-in projection additional)
                 first
                 :seon.render/html))
          "custom Malli render properties survive in the shape row")
      (is (empty? (schema/matching-shapes-in projection invalid))
          "an invalid declared optional attribute still refuses"))))

(deftest canonical-rows-carry-arbitrary-namespaced-properties
  (let [schema-key :seon.schema-test/class
        definition
        [:map {:seon.error/class true
               :gen/schema :string
               :seon.unknown/property :ignored}
         [:seon.error/message :seon.error/message]]
        forms {:seon.error/class [:= true]
               :gen/schema :seon.schema/definition
               schema-key definition}
        row (some #(when (= schema-key (:seon.schema/key %)) %)
                  (schema/canonical-schema-rows forms))]
    (is (= true (:seon.error/class row)))
    (is (not (contains? row :gen/schema))
        "a declared but non-storable property remains compile-time Malli data")
    (is (not (contains? row :seon.unknown/property))
        "an undeclared property remains compile-time Malli data")
    (is (= #{:seon.error/message}
           (:seon.schema/references row))
        "an external canonical reference remains a persisted direct edge")
    (is (= (pr-str definition) (:seon.schema/form row)))))

(deftest matching-shapes-derive-required-attributes-through-and-refs
  (let [forms {:seon.error/message :string
               :seon.error/refusal-value
               [:map [:seon.error/message :seon.error/message]]
               :seon.schema-test/refused [:= true]
               :seon.schema-test/refused-error
               [:and {:seon.error/class true
                      :seon.render/ai 'seon.error/refusal-prose}
                :seon.error/refusal-value
                [:map
                 [:seon.schema-test/refused
                  :seon.schema-test/refused]]]}
        projection (schema/build-projection forms)
        value {:seon.schema-test/refused true
               :seon.error/message "The transition was refused."}
        row (get (:seon.schema.projection/shape-rows projection)
                 :seon.schema-test/refused-error)]
    (is (= #{:seon.schema-test/refused :seon.error/message}
           (:seon.schema/required-attrs row)))
    (is (= 'seon.error/refusal-prose (:seon.render/ai row)))
    (is (= :seon.schema-test/refused-error
           (-> (schema/matching-shapes-in projection value)
               first
               :seon.schema/key)))))

(deftest agent-authored-function-input-maps-accrete
  (is (empty?
       (schema/assert-complete-contract!
        {:seon.schema/identity 'my.agent/accreting
         :seon.schema/definition
         [:=>
          [:cat [:map [:my.agent/required :string]]]
          :string]
         :seon.schema/admission
         {:seon.schema.admission/source :agent}}))))

(deftest render-declarations-require-a-contract-that-accepts-their-shape
  ;; CLASS: an explicit render declaration could name any contracted function,
  ;; so the mismatch survived publication and failed only when a value reached
  ;; the renderer. Publication now makes that state unrepresentable from the
  ;; stored schema and function facts alone. Attribute declarations use the
  ;; attribute's value shape; entity/value declarations use their own shape.
  (let [shape :seon.schema-test/rendered
        other :seon.schema-test/other
        attribute :seon.schema-test/rendered-attribute
        renderer 'seon.schema-test/render-rendered
        plain-shape [:map [:seon.schema-test/id :string]]
        plain-attribute :string
        forms {shape plain-shape
               other [:map [:seon.schema-test/other :string]]
               attribute plain-attribute
               :seon.db/database-value :map}
        admission {:seon.schema.admission/source :agent}
        admit (fn [schema-key definition contract]
                (schema/projection-with-schema
                 (schema/build-projection forms {renderer contract})
                 schema-key definition admission))
        mismatch
        (refusal
         #(admit shape
                 [:map {:seon.render/ai renderer}
                  [:seon.schema-test/id :string]]
                 [:=> [:cat other] :string]))
        mismatch-data (ex-data mismatch)]
    (testing "a mismatch refuses with both declared sides and the reason"
      (is (instance? clojure.lang.ExceptionInfo mismatch))
      (is (= :seon.schema/render-contract-incoherent
             (:seon.error/kind mismatch-data)))
      (is (= shape
             (get-in mismatch-data
                     [:seon.error/data :seon.error/diagnostic-expected])))
      (is (= shape
             (get-in mismatch-data
                     [:seon.error/data :seon.error/diagnostic-member])))
      (is (= 'seon.schema/render-contract-coherence
             (get-in mismatch-data
                     [:seon.error/data :seon.error/diagnostic-operation])))
      (is (= renderer
             (get-in mismatch-data
                     [:seon.error/data :seon.error/diagnostic-offending])))
      (is (= other
             (get-in mismatch-data
                     [:seon.error/data :seon.error/diagnostic-evidence
                      :seon.fn/input])))
      (is (= :seon.schema/render-input-does-not-accept-declaring-shape
             (get-in mismatch-data
                     [:seon.error/data :seon.error/diagnostic-cause]))))
    (testing "a coherent declaration admits"
      (is (= renderer
             (get-in
              (admit shape
                     [:map {:seon.render/ai renderer}
                      [:seon.schema-test/id :string]]
                     [:=> [:cat shape] :string])
              [:seon.schema.projection/shape-rows shape :seon.render/ai]))))
    (testing "an attribute declaration is checked against its value shape"
      (is (= renderer
             (get-in
              (admit attribute
                     [:and {:seon.render/form renderer} plain-attribute]
                     [:=> [:cat attribute] :string])
              [:seon.schema.projection/forms attribute 1
               :seon.render/form]))))
    (testing "a constrained attribute fits a union containing its value shape"
      (is (= renderer
             (get-in
              (admit attribute
                     [:and {:seon.render/ai renderer} :string [:string {:min 1}]]
                     [:=> [:cat [:or :string [:vector :map]]] :string])
              [:seon.schema.projection/forms attribute 1 :seon.render/ai]))))
    (testing "call preparation may supply an additional database value"
      (is (= renderer
             (get-in
              (admit shape
                     [:map {:seon.render/ai renderer}
                      [:seon.schema-test/id :string]]
                     [:=> [:cat shape :seon.db/database-value] :string])
              [:seon.schema.projection/shape-rows shape :seon.render/ai]))))
    (testing "additional declared arguments and keys preserve accretion"
      (is (= renderer
             (get-in
              (admit shape
                     [:map {:seon.render/ai renderer}
                      [:seon.schema-test/id :string]]
                     [:=>
                      [:cat
                       [:map
                        [:seon.schema-test/id :string]
                        [:seon.schema-test/extra {:optional true} :int]]
                       :string]
                      :string])
              [:seon.schema.projection/shape-rows shape :seon.render/ai]))))))

(deftest one-declaration-validates-only-its-dependency-closure
  (let [unrelated
        (into {}
              (map (fn [index]
                     [(keyword "seon.schema-test.unrelated" (str index))
                      :string]))
              (range 1024))
        projection (schema/build-projection unrelated)
        admission {:seon.schema.admission/source :agent}
        binding-walks (atom 0)
        population-compilations (atom 0)
        original-bind schema/compilable-form
        original-compile schema.internal/assert-compilable-schema!
        [schema-candidate function-candidate]
        (with-redefs
          [schema/compilable-form
           (fn [& args]
             (swap! binding-walks inc)
             (apply original-bind args))
           schema.internal/assert-compilable-schema!
           (fn [& args]
             (swap! population-compilations inc)
             (apply original-compile args))]
          [(schema/projection-with-schema
            projection :seon.schema-test.incremental/score
            [:int {:min 0 :max 100}] admission)
           (schema/projection-with-function-contract
            projection 'seon.schema-test.incremental/accept
            [:=> [:cat :string] :string] admission)])]
    (is (zero? @population-compilations)
        "one declaration never enters complete-population compilation")
    (is (< @binding-walks 16)
        "predicate binding is bounded by the two changed declarations")
    (is (= [:int {:min 0 :max 100}]
           (get-in schema-candidate
                   [:seon.schema.projection/forms
                    :seon.schema-test.incremental/score])))
    (is (= [:=> [:cat :string] :string]
           (get-in function-candidate
                   [:seon.schema.projection/function-contracts
                    'seon.schema-test.incremental/accept])))))

(deftest a-component-bearing-row-validates-its-own-declared-shape
  ;; CLASS: every component attribute declares `[<collection>
  ;; {:seon.db/component true} :seon.db/ref]`, and `:seon.db/ref` admits an
  ;; entity id, a string, a lookup ref, or a map with :db/id — not a new
  ;; component's OWN entity map, which the producer actually builds and
  ;; Datahike's transaction-data grammar expects. So a row that carried its
  ;; components was refused by its own declared shape, and an agent's first
  ;; `defn` died at `seon.program/with-contract-facts`
  ;; (docs/seon/issues/a-component-value-is-refused-by-its-own-ref-shape.md).
  ;;
  ;; The construction that kills it: the registry DERIVES the second arm from
  ;; the `:seon.db/component true` property the form already declares, so a
  ;; component attribute added tomorrow is admissible with no edit anywhere.
  ;; This test therefore derives its subjects from the population rather than
  ;; listing them: a new component attribute joins it automatically.
  (let [forms (schema/declaration-population)
        component-attrs
        (into (sorted-map)
              (keep (fn [[schema-key form]]
                      (when (and (vector? form)
                                 (map? (second form))
                                 (true? (:seon.db/component (second form))))
                        [schema-key (first form)])))
              forms)
        entity {:seon.fn.arity/order 0}
        carried (fn [collection-kind]
                  (case collection-kind
                    :vector [entity]
                    :set #{entity}
                    :and entity))
        kinds (into (sorted-set) (vals component-attrs))]
    (is (seq component-attrs) "the population declares component attributes")
    (is (= #{:and :set :vector} kinds)
        "every collection kind a component attribute is declared with")
    (doseq [[schema-key collection-kind] component-attrs]
      (is (schema/valid-candidate-value?
           forms schema-key (carried collection-kind))
          (str schema-key " admits the component's own entity")))
    (testing "a persisted ref is still admissible in the same position"
      (doseq [[schema-key collection-kind] component-attrs]
        (is (schema/valid-candidate-value?
             forms schema-key
             (case collection-kind :vector [17] :set #{17} :and 17)))))
    (testing "the widening is confined to component positions"
      (is (false? (schema/valid-candidate-value? forms :seon.db/ref entity))
          ":seon.db/ref requires :db/id on a reference map")
      (is (false? (schema/valid-candidate-value?
                   forms :seon.fn.arity/input-schema entity))
          "a non-component ref requires :db/id on a reference map")
      (is (false? (schema/valid-candidate-value? forms :seon.fn/arities [{}]))
          "an empty map is not a component entity"))
    (testing "the canonical indexed function row validates"
      (is (schema/valid-candidate-value?
           forms :seon.fn/fn (test-support/program-fn-row 'seon.id/id))))
    (testing "shape selection still picks each row's own family"
      (let [projection (schema/build-projection forms)
            matches (fn [value]
                      (set (map :seon.schema/key
                                (schema/matching-shapes-in projection value))))]
        (is (contains? (matches {:seon.ns/name 'probe.alias
                                 :seon.schema.admission/source :agent
                                 :seon.ns/aliases
                                 #{{:seon.ns.alias/local 'set
                                    :seon.ns.alias/target-ns 'clojure.set}}})
                       :seon.ns/ns))
        (is (empty? (matches {:seon.fn.arity/order 0}))
            "a bare component entity matches no top-level family")))))

;;; ---------------------------------------------------------------------------
;;; One derivation for the projection's bound predicates

(defn- cold-projection
  "The canonical projection as a cold worker meets it: NO predicate key at all.

  `declaration-projection` and every arm that builds a projection without
  binding predicates produce exactly this shape — the key is absent, never a
  stored nil — which is what nine bare reads turned into a nil argument to
  `compilable-form`, whose declared input is a map."
  [database]
  (dissoc (schema/projection-from-database database)
          :seon.schema.projection/predicate-functions))

(deftest a-projection-with-no-bound-predicates-compiles-every-declared-shape
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           bare (cold-projection database)
           forms (:seon.schema.projection/forms bare)
           contracts (:seon.schema.projection/function-contracts bare)
           admission {:seon.schema.admission/source :core}
           failure-of (fn [thunk]
                        (try (thunk) nil
                             (catch Throwable failure (ex-message failure))))]
       (is (not (contains? bare :seon.schema.projection/predicate-functions))
           "the cold shape carries no key, never a stored nil")
       (is (= {} (schema/predicate-functions-in bare))
           "the one derivation answers the empty map the absence means")
       (is (seq forms) "the canonical population is non-vacuous")
       (is (seq contracts) "the canonical contracts are non-vacuous")
       (testing "seon.schema/direct-references compiles every declared form"
         (is (= {}
                (into (sorted-map)
                      (keep (fn [[schema-key form]]
                              (when-let [message
                                         (failure-of
                                          #(schema/direct-references bare form))]
                                [schema-key message])))
                      forms))))
       (testing "arity discovery compiles every declared function contract"
         (is (= {}
                (into (sorted-map)
                      (keep (fn [[function-symbol _]]
                              (when-let [message
                                         (failure-of
                                          #(#'schema/function-arities-in
                                            bare function-symbol))]
                                [function-symbol message])))
                      contracts))))
       (testing "schema and contract replacement validate against the cold shape"
         (let [schema-key (first (sort (keys forms)))
               ;; Removal is admissible only for a key nothing references, so
               ;; the subject is one this test just added; dropping the
               ;; predicate key again restores the cold shape for the removal.
               removable :seon.schema-test/cold-removable
               added (schema/projection-with-schema
                      bare removable :string admission)
               cold-added (dissoc added
                                  :seon.schema.projection/predicate-functions)
               function-symbol (first (sort (keys contracts)))]
           (is (nil? (failure-of
                      #(schema/projection-with-schema
                        bare schema-key (get forms schema-key) admission))))
           (is (nil? (failure-of
                      #(schema/projection-without-schema
                        cold-added removable))))
           (is (nil? (failure-of
                      #(schema/projection-with-function-contract
                        bare function-symbol
                        (get contracts function-symbol) admission))))))
       (testing "the arm's own two readers are total"
         (is (nil? (#'instrument/predicate-callable
                    bare 'seon.schema-test/no-such-predicate)))
         (let [function-symbol (first (sort (keys contracts)))]
           (is (some? (#'instrument/compiled-wrapper
                       bare function-symbol
                       (get contracts function-symbol)
                       (fn [& _] nil)
                       {})))))
       (testing "call preparation's argument validators compile"
         (let [with-slots
               (->> (keys contracts)
                    sort
                    (filter (fn [function-symbol]
                              (seq (seon.db/q
                                    database
                                    '[:find ?order ?index
                                      :in $ ?sym
                                      :where
                                      [?function :seon.fn/sym ?sym]
                                      [?function :seon.fn/arities ?arity]
                                      [?arity :seon.fn.arity/order ?order]
                                      [?arity :seon.fn.arity/arguments ?argument]
                                      [?argument :seon.fn.argument/index ?index]
                                      [?argument :seon.fn.argument/schema _]]
                                    function-symbol))))
                    (take 25))]
           (is (seq with-slots)
               "the canonical program graph declares argument shapes")
           (is (= {}
                  (into (sorted-map)
                        (keep (fn [function-symbol]
                                (when-let [message
                                           (failure-of
                                            #(#'call-preparation/argument-validators
                                              database
                                              {:seon.schema/projection bare}
                                              function-symbol))]
                                  [function-symbol message])))
                        with-slots)))))))))

(deftest one-derivation-owns-the-projection-predicate-bindings
  ;; AGENTS §2.2. Nine spellings of
  ;; `:seon.schema.projection/predicate-functions` each read the absent key as
  ;; nil. The cure is one named reader and one named writer; this check is what
  ;; fails when a tenth spelling appears, and it is a program-graph query over
  ;; `:seon.fn/keywords` — the fact the graph exists to answer — never a text
  ;; search over the tree.
  (test-support/with-database
   (fn [connection]
     (let [named
           (set (seon.db/q
                 '[:find [?function-symbol ...]
                   :in $ ?keyword
                   :where
                   [?function :seon.fn/keywords ?keyword]
                   [?function :seon.fn/sym ?function-symbol]
                   [?function :seon.fn/file ?file]
                   [?file :seon.fn.file/relative-root "src"]]
                 @connection
                 :seon.schema.projection/predicate-functions))]
       (is (= #{(quote seon.schema/predicate-functions-in)
                (quote seon.schema/with-predicate-functions)}
              named)
           (str "Only the reader and the writer may name "
                ":seon.schema.projection/predicate-functions under src. "
                "Every other first-party function asks "
                "seon.schema/predicate-functions-in, which answers {} for the "
                "absent key instead of handing compilable-form nil."))
       (is (seq (seon.db/q
                 '[:find [?caller-symbol ...]
                   :where
                   [?callee :seon.fn/sym seon.schema/predicate-functions-in]
                   [?caller :seon.fn/calls ?callee]
                   [?caller :seon.fn/sym ?caller-symbol]]
                 @connection))
           "the derivation has callers: an empty answer would be the check
            reporting health from an absent subject")))))

;;; ---------------------------------------------------------------------------
;;; A declaration is compiled against its predicate's SOURCE
;;; ---------------------------------------------------------------------------

(deftest a-declaration-compiles-against-a-predicate-its-loaded-owner-lacks
  ;; CLASS: a long-lived JVM holds the copy of a namespace it loaded at boot.
  ;; A source edit that adds a predicate AND the schema resource declaring it
  ;; lands on disk together, but the publication that would adopt the edit
  ;; compiles the resource BEFORE its adoption reloads the owner — so the
  ;; predicate resolves to nothing and EVERY publication and adoption in that
  ;; process is refused, including the one that would have fixed it. Measured
  ;; 2026-09-16 on `default` for `seon.search/handle?`, which wedged every
  ;; agent (`logs/current-source-failure.log`); filed as
  ;; `docs/seon/issues/a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md`.
  ;;
  ;; The wedge is a pre-read the authority re-decides: the var table is a
  ;; MIRROR of the source the publication is about to publish. Compilation
  ;; therefore converges on the source instead of refusing to it.
  (require 'seon.schema.predicate-owner-probe)
  (let [owner 'seon.schema.predicate-owner-probe
        predicate 'seon.schema.predicate-owner-probe/probe-handle?
        form [:fn {:error/message "must be the probe handle"} predicate]
        compiled #(schema/compilable-form form {})]
    (is (var? (get (compiled) 2))
        "a loaded owner supplies its predicate Var without any convergence")
    (testing "a loaded owner whose copy lacks the declared predicate"
      ;; Exactly the live state: the namespace IS loaded, so `require` is a
      ;; no-op, and only a reload replays its registration forms.
      (ns-unmap (find-ns owner) 'probe-handle?)
      (is (nil? (ns-resolve (find-ns owner) 'probe-handle?))
          "the probe reproduces the stale-owner state before compiling")
      (require owner)
      (is (nil? (ns-resolve (find-ns owner) 'probe-handle?))
          "and a plain require cannot leave it — this is why it wedged")
      (let [bound (get (compiled) 2)]
        (is (var? bound)
            "compilation converges on the predicate's source rather than
             refusing the declaration")
        (is (true? (bound :seon.schema.predicate-owner-probe/handle)))
        (is (false? (bound :something-else)))
        (is (some? (ns-resolve (find-ns owner) 'probe-handle?))
            "and the owner is left loaded from its own source")))
    (testing "an unloaded namespace is still never loaded — the stale MIRROR
              is the case, and `canonical-definition-keeps-admitted-predicate-symbols`
              owns the guarantee this must not weaken"
      (is (nil? (find-ns 'seon.schema-test.no-such-probe)))
      (is (instance? clojure.lang.ExceptionInfo
                     (refusal
                      #(schema/compilable-form
                        [:fn 'seon.schema-test.no-such-probe/probe-predicate?]
                        {}))))
      (is (nil? (find-ns 'seon.schema-test.no-such-probe))
          "compilation did not require an unloaded predicate namespace"))
    (testing "a predicate its source genuinely does not define still refuses,
              naming both the predicate and the namespace that must define it"
      (let [absent 'seon.schema.predicate-owner-probe/never-declared?
            refused (refusal
                     #(schema/compilable-form [:fn absent] {}))
            data (ex-data refused)]
        (is (= :seon.schema/unresolved-predicate (:seon.schema/error data)))
        (is (= absent (:seon.schema/unresolved-predicate data)))
        (is (= 'seon.schema.predicate-owner-probe
               (:seon.schema/predicate-namespace data)))))))
