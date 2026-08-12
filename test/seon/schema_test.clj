(ns seon.schema-test
  "Regression proofs for the canonical schema registration boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [malli.error :as me]
            [seon.db]
            [seon.schema :as schema]
            [seon.schema.edn]
            [seon.schema.internal :as schema.internal]))

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

(deftest function-input-fit-is-total-for-an-absent-contract
  (let [projection (schema/build-projection (schema/registered-schemas))]
    (is (false? (schema/function-accepts-in?
                 projection 'seon.schema-test/missing [{}])))))

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
         :qualified-symbol]]
    (is (= definition (schema/canonical-definition definition {})))
    (is (schema/malli-form? definition))
    (is (nil? (find-ns 'clojure.java.shell))
        "precondition: the arbitrary predicate namespace is not loaded")
    (is (false? (schema/malli-form? [:fn 'clojure.java.shell/sh]))
        "schema validation never loads an arbitrary predicate namespace")
    (is (nil? (find-ns 'clojure.java.shell))
        "and asking the question did not load it either")))

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
      (project! equal-copy))
    ;; `build-projection`'s one-argument entry delegates to its three-argument
    ;; entry, so the redefined Var observes two calls for one complete build.
    (is (= 2 @builds)
        "resource reads do not start a second complete projection build")))

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
  ;; entity id, a string, or a lookup ref — never the component's OWN entity
  ;; map, which is what the producer of that row actually builds and what
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
          ":seon.db/ref itself still admits no entity map")
      (is (false? (schema/valid-candidate-value?
                   forms :seon.fn.arity/input entity))
          "a non-component ref attribute still admits no entity map")
      (is (false? (schema/valid-candidate-value? forms :seon.fn/arities [{}]))
          "an empty map is not a component entity"))
    (testing "the row an agent's contracted defn builds validates"
      (is (schema/valid-candidate-value?
           forms :seon.fn/fn
           {:seon.fn/sym "my.agents.probe/probe-fn"
            :seon.schema.admission/source :agent
            :seon.fn/ns [:seon.ns/name 'my.agents.probe]
            :seon.fn/source "(defn probe-fn [x] x)"
            :seon.fn/arities [{:seon.fn.arity/order 0}]})))
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
