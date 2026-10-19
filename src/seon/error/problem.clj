(ns seon.error.problem
  "One contract problem as refusal evidence: Malli's structured problem
  explained (`explain-problem`) and its one refusal sentence
  (`problem-sentence`). Pure over Malli, so every producer that detects a
  refusal (the writer in `seon.db`, the instrumented contract) builds its
  error value with them."
  (:require [clojure.string :as str]
            [datahike.db.utils :as db.utils]
            [malli.core :as m]
            [malli.error :as me]))

(defn- value-description
  {:malli/schema [:=> [:cat :seon.error/source]
                  :string]}
  [value]
  (cond
    (nil? value) "nil"
    (instance? clojure.lang.LazySeq value) "a lazy sequence"
    (vector? value) "a vector"
    (map? value) "a map"
    (set? value) "a set"
    (sequential? value) "a sequence"
    (string? value) "a string"
    (keyword? value) "a keyword"
    (symbol? value) "a symbol"
    (boolean? value) "a boolean"
    (integer? value) "an integer"
    (number? value) "a number"
    :else (str "an instance of " (.getName (class value)))))

(defn- bound-text
  "A schema's declared `:min`/`:max` properties as prose, or nil when it
  declares neither. Malli reads both as the value itself for numbers and as
  the count for strings and collections (`malli.core/-min-max-pred`)."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{lower :min upper :max}]
  (cond (and (some? lower) (= lower upper)) (str "exactly " lower)
        (and (some? lower) (some? upper)) (str "from " lower " to " upper)
        (some? lower) (str "at least " lower)
        (some? upper) (str "at most " upper)))

(defn- schema-expectation
  "Describe composed schemas from their children, not Malli's unknown fallback.
  A declared bound is part of the expectation: `[:int {:min 1}]` refusing 0
  read \"expected an integer, got an integer 0\"."
  {:malli/schema [:=> [:cat :map]
                  :string]}
  [problem]
  (let [check (m/deref-all (:schema problem))
        problem (-> problem (assoc :schema check) (dissoc :type))
        declared-message (me/error-message problem {:unknown false})
        children #(map (fn [child]
                         (schema-expectation (assoc problem :schema child)))
                       (m/children check))
        bound (bound-text (or (m/properties check) {}))
        bounded (fn [noun measure]
                  (cond (nil? bound) noun
                        measure (str noun " of " measure " " bound)
                        :else (str noun " " bound)))]
    (case (m/type check)
      :vector (bounded "a vector" "count") :sequential (bounded "a sequence" "count")
      :map (bounded "a map" "count") :set (bounded "a set" "count")
      :string (bounded "a string" "length") :int (bounded "an integer" nil)
      :double (bounded "a double" nil) :boolean "a boolean" :keyword "a keyword"
      :qualified-keyword "a namespaced keyword" :symbol "a symbol"
      :qualified-symbol "a namespaced symbol" :nil "nil" :uuid "a UUID"
      :tuple (str "a tuple with " (count (m/children check)) " entries")
      :enum (str "either " (str/join " or " (map pr-str (m/children check))))
      := (str "the value " (pr-str (first (m/children check))))
      :and (or declared-message (str/join " and " (children)))
      :or (or declared-message (str/join " or " (children)))
      :fn (or declared-message "the declared predicate")
      (let [schema-type (m/type check)]
        (str "a value satisfying " (or (:description (m/properties check))
                                       (if (symbol? schema-type) schema-type (str "the declared " (name schema-type) " schema"))))))))

(defn- collection-member-problem
  {:malli/schema [:=> [:cat :map]
                  [:or :nil :map]]}
  [problem]
  (let [schema (m/deref-all (:schema problem))
        schema-type (m/type schema)
        child (when (#{:set :vector :sequential} schema-type)
                (first (m/children schema)))
        value (:value problem)
        matches-kind? (case schema-type
                        :sequential (sequential? value)
                        :vector (vector? value)
                        :set (set? value)
                        false)]
    (when (and child matches-kind? (coll? value))
      (when-let [[member]
                 (reduce (fn [_ member]
                           (when-not (m/validate child member)
                             (reduced [member])))
                         nil value)]
        {:schema child
         :value member
         ::collection-type schema-type}))))

(defn explain-problem
  "Translate Malli's structured problem into semantic refusal evidence: its
   path, expectation and fix, and its leaf only when the leaf is a scalar.
   No message parsing or value printing occurs at this seam."
  {:malli/schema [:=> [:cat :seon.error/explain-request] :seon.error/problem-description]}
  [{:seon.error/keys [problem path argument parent]}]
  (let [check (let [check (:check problem)] (if (sequential? check) (first check) check))
        checked-output (when check
                         (or (:malli.core/explain-output check)
                             (when-let [output (:output (m/-function-info (:schema problem)))]
                               {:schema output :value (:malli.core/result check)})))
        problem (if checked-output
                  {:schema (:schema checked-output) :value (:value checked-output)}
                  problem)
        member-problem (collection-member-problem problem)
        problem (or member-problem problem)
        member? (boolean member-problem)
        missing? (= :malli.core/missing-key (:type problem))
        entry-schema (when (and missing?
                                (= :map (m/type (m/deref-all (:schema problem)))))
                       (some (fn [[entry-key _ entry]]
                               (when (= entry-key (last path)) entry))
                             (m/children (m/deref-all (:schema problem)))))
        problem (cond-> problem entry-schema (assoc :schema entry-schema))
        schema-type (m/type (m/deref-all (:schema problem)))
        expected-form (if (#{:= :enum} schema-type)
                        (into [schema-type] (m/children (m/deref-all (:schema problem))))
                        (m/form (:schema problem)))
        described-problem (cond-> problem missing? (dissoc :type))
        message (or (me/error-message described-problem {:unknown false})
                    "the declared schema")
        expected (schema-expectation described-problem)
        missing-key (last path)
        ;; the same name the caller's map holds under another namespace; a
        ;; Datahike database is a record whose seq is its datoms, never walked
        held-key (when (and missing? (qualified-keyword? missing-key) (map? parent)
                            (not (db.utils/db? parent)))
                   (some #(when (and (qualified-keyword? %) (= (name %) (name missing-key))) %)
                         (keys parent)))]
    (cond-> {:seon.error/path (vec path)
     :seon.error/argument argument
     :seon.error/expected expected-form
     :seon.error/expected-description
     (cond
       missing? (str "the required key " (pr-str missing-key) " with " expected)
       member? (str "a collection member satisfying " expected)
       :else expected)
     :seon.error/actual-description
     (cond
       missing? (str "a map missing " (pr-str missing-key))
       member? (str "a collection member that is " (value-description (:value problem)))
       :else (value-description (:value problem)))
     :seon.error/fix
     (cond
       missing? (str "Supply " (pr-str missing-key) " with " expected "."
                     (when held-key
                       (str " The map holds " held-key " with the same name under another namespace.")))
       (and (= :vector schema-type) (sequential? (:value problem))
            (not (vector? (:value problem))))
       "Convert the sequence with vec before calling the function."
       (= :fn schema-type) message
       checked-output "Return a value satisfying the declared result contract for the shown input."
       :else (str "Supply " expected
                  (when (seq path) (str " at " (pr-str (vec path)))) "."))}
      ;; one reference rule: a problem holds its leaf only when it is a scalar;
      ;; a collection (a missing key's map included) is held once, by its
      ;; refusal, as `seon.error.refusal/value-reference`
      (not (or missing? (coll? (:value problem)))) (assoc :seon.error/offending (:value problem))
      check (assoc :seon.error/input (first (:smallest check))))))

(defn- problem-parts
  "The refusal sentence's parts in order: prose strings, and the expected and
  offending values as the caller rendered them (text for AI, hiccup nodes for
  HTML), nil omitting that part. `problem-sentence` joins them as text; the
  HTML face places the same parts as nodes, so a value's cut stays structure."
  {:malli/schema
   [:=> [:cat [:or :symbol :string] :map :seon.render/hiccup :seon.render/hiccup]
    [:vector :seon.render/hiccup]]}
  [operation
   {:seon.error/keys [path argument expected-description actual-description fix]}
   expected offending]
  ;; An empty path is the whole argument, which `argument` already names:
  ;; " at []" told the reader nothing (instrumentation-opus, 2026-09-27).
  (cond-> [(str operation " refused " argument
                (when (seq path) (str " at " (pr-str path)))
                ": expected " expected-description)]
    (some? expected) (conj " (" expected ")")
    true (conj (str ", got " actual-description))
    (some? offending) (conj " " offending)
    true (conj (str ". Fix: " fix))))

(defn problem-sentence
  "THE ONE refusal sentence for one problem: who refused what, where, the
  expectation, the offending value, and the fix.

  `expected-text` and `offending-text` are the CALLER'S rendered values — the
  render pair prints them under its profile, a flat message prints a scalar
  directly, and nil omits that part. THE DESCRIPTIONS ARE PROSE AND THE VALUE
  FOLLOWS THEM (`value-description` never prints a value), so a description
  that names its own value renders it twice: `3e41a5d22` put the count into
  the arity description to repair the flat message, which had no value to
  print, and every rendered arity refusal then read \"got an argument count of
  0 0\" (measured 2026-09-17). One sentence, one composer, one place each
  value is printed."
  {:malli/schema
   [:=> [:cat [:or :symbol :string] :map [:maybe :string] [:maybe :string]]
    [:string {:min 1}]]}
  [operation problem expected-text offending-text]
  (apply str (problem-parts operation problem expected-text offending-text)))

(defn scalar-text
  "The printed form of a value that prints itself, or nil.

  A flat `:seon.error/message` carries no render profile, so it can only
  print a value whose printed form is bounded by what the value IS: a
  number, keyword, symbol or boolean. Anything else stays with its prose
  description until a renderer with a profile prints it."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total printer accepts any offending value and answers nil for the ones it cannot bound."}]]
    [:maybe [:string {:min 1}]]]}
  [value]
  (when (or (number? value) (keyword? value) (symbol? value) (boolean? value))
    (pr-str value)))
