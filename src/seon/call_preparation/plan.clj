(ns seon.call-preparation.plan
  "The invocation plan's pure derivation: a contract's arities joined to the
  admitted supplied-default rows by declared schema name.

  A leaf below `seon.db`: the writer's arity gate (`seon.db/arity-verdict`)
  and call preparation (`seon.call-preparation`) derive prepared arities with
  these same functions, so the gate and the armed wrapper agree by
  construction. It reads no database: its callers run `entity-query` and hand
  the rows in."
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [seon.batch]
            [seon.error.refusal]
            [seon.schema :as schema]
            [seon.schema.internal :as internal]))

(set! *warn-on-reflection* true)

(def entity-query
  '[:find ?key ?schema-key ?supplier
    :in $
    :where
    [?entity :seon.call-preparation/key ?key]
    [?entity :seon.call-preparation/schema ?schema]
    [?schema :seon.schema/key ?schema-key]
    [?entity :seon.call-preparation/supplier ?function]
    [?function :seon.fn/sym ?supplier]])

(defn argument-facts
  "One argument slot: its index, rest position, compiled schema and authored
  schema name; for a map (inline or named, through `m/deref-all`) its
  `[key optional? value-form]` entries."
  {:malli/schema [:=> [:cat :int :boolean [:fn malli.core/schema?]] :map]}
  [index rest? child]
  (let [form (m/form child)
        target (m/deref-all child)]
    (cond-> {:seon.fn.argument/index index :seon.fn.argument/rest? rest? :seon.call-preparation/argument-schema child}
      (qualified-keyword? form) (assoc :seon.call-preparation/schema-key form)
      (and (not rest?) (= :map (m/type target)))
      (assoc :seon.call-preparation/map-entries (mapv (fn [[entry-key properties value]]
                               [entry-key (true? (:optional properties)) (m/form value)])
                             (m/children target))))))

(defn arity-facts
  "One compiled function schema's arities, ordered and indexed as
  `seon.program/contract-facts` orders them (`m/-function-schema-arities`,
  then the `:cat`/`:catn` input's children); a variadic arity's last slot is its rest."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:vector :map]]}
  [compiled]
  (mapv (fn [order arity]
          (let [{input :input output :output kind :arity minimum :min} (m/-function-info arity)
                slots (mapv #(if (m/schema? %) % (last %)) (m/children input))
                variadic? (= :varargs kind)]
            {:seon.fn.arity/order order
             :seon.fn.arity/min minimum
             :seon.fn.arity/argument-count (count slots)
             :seon.call-preparation/variadic? variadic?
             :seon.call-preparation/arity-output output
             :seon.call-preparation/arity-arguments (mapv #(argument-facts %1 (and variadic? (= %1 (dec (count slots)))) %2)
                               (range) slots)}))
        (range) (m/-function-schema-arities compiled)))

(defn contract-arities
  "The requested function's arities, retained by its projection; nil without a contract."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol] [:maybe [:vector :map]]]}
  [projection sym]
  (internal/projection-cache-value
   projection [:seon.call-preparation/contract-arities sym]
   #(when (contains? (:seon.schema.projection/function-contracts projection) sym)
      (some-> (mr/schema (:seon.schema.projection/registry projection) sym) arity-facts))))

(defn incoherent
  {:malli/schema [:=> [:cat :seon.call-preparation/supplied-default :string :map]
                  :seon.call-preparation/incoherent-supplier-error]}
  [{default-key :seon.call-preparation/key
    supplier :seon.call-preparation/supplier-symbol
    schema-key :seon.call-preparation/schema-key} reason data]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.call-preparation/call 'seon.call-preparation.plan/incoherent
   {:seon.error/message (str "The supplied default " default-key " is not admissible: " reason)
  :seon.call-preparation/incoherent-key default-key
  :seon.call-preparation/supplier-symbol supplier
  :seon.call-preparation/schema-key schema-key
  :seon.call-preparation/coherence-expectation reason
  :seon.error/data (assoc data :seon.call-preparation/key default-key)
  :seon.error/offending data}))

(defn coherent-supplier
  "Prove one row against the program's contracts, or refuse it as a value.

  Refusing at acquisition is the point: an incoherent row is never
  installed, so a wrong-shaped value can never be handed to a target
  function and disguised as that function's own contract violation.
  Schemas are matched by declared name, never by structure."
  {:malli/schema
   [:=> [:cat [:map-of :qualified-symbol [:vector :map]] :seon.call-preparation/supplied-default
         [:set :qualified-keyword]]
    [:or :nil :seon.call-preparation/incoherent-supplier-error]]}
  [contracts
   {schema-key :seon.call-preparation/schema-key
    supplier :seon.call-preparation/supplier-symbol :as candidate}
   error-keys]
  (let [{order :seon.fn.arity/order argument-count :seon.fn.arity/argument-count
         [argument] :seon.call-preparation/arity-arguments output :seon.call-preparation/arity-output}
        (first (filter #(seq (:seon.call-preparation/arity-arguments %)) (get contracts supplier)))
        arms (when (and output (= :or (m/type output))) (set (map m/form (m/children output))))]
    (cond
      (nil? argument)
      (incoherent candidate
                  (str "no function named " supplier
                       " with a contracted one-argument arity is in this "
                       "cluster's program graph.")
                  {:seon.call-preparation/supplier-symbol supplier})

      (not (and (zero? (long order)) (= 1 (long argument-count))))
      (incoherent candidate
                  (str supplier " must take exactly one argument, this "
                       "cluster's environment; it declares "
                       argument-count ".")
                  {:seon.call-preparation/supplier-symbol supplier
                   :seon.fn.arity/argument-count argument-count})

      (not= :seon.env/environment (:seon.call-preparation/schema-key argument))
      (incoherent candidate
                  (str supplier "'s argument is not "
                       ":seon.env/environment. A supplier reads the "
                       "environment it is called with and nothing else.")
                  {:seon.call-preparation/supplier-symbol supplier})

      (nil? arms)
      (incoherent candidate
                  (str supplier " must declare a union return: its "
                       "value schema or its declared error schemas.")
                  {:seon.call-preparation/supplier-symbol supplier})

      (not (and (contains? arms schema-key)
                (seq (disj arms schema-key))
                (every? error-keys (disj arms schema-key))))
      (incoherent candidate
                  (str supplier "'s declared return does not agree with "
                       "the entity's value schema " schema-key ".")
                  {:seon.call-preparation/supplier-symbol supplier
                   :seon.call-preparation/schema-key schema-key})

      :else nil)))

(defn by-schema-key
  {:malli/schema [:=> [:cat :seon.call-preparation/supplied-defaults]
                  [:map-of :qualified-keyword :seon.call-preparation/supplied-default]]}
  [supplied-defaults]
  (into {} (map (fn [[_ candidate]] [(:seon.call-preparation/schema-key candidate) candidate]))
        supplied-defaults))

(def world-supplied
  "What an evaluation's world supplies, by declared schema name: its members
  (`seon.batch/world-members`) and the database, which no world holds: the
  world connection's current value, read at the call."
  (assoc seon.batch/world-members :seon.db/database-value :seon.db/db))

(def world-candidates
  "A supplied default for each input an evaluation's world supplies
  (`world-supplied`), keyed by the schema name a function declares it under.
  Its value comes from the calling evaluation's world (`seon.batch/*world*`)
  and nowhere else."
  (into {} (for [[schema-key member] world-supplied]
             [schema-key {:seon.call-preparation/key member
                          :seon.call-preparation/schema-key schema-key
                          :seon.call-preparation/supplier-symbol `seon.batch/world-members}])))

(defn world-member?
  "Whether `schema-key` names an input an evaluation's world supplies."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An argument slot's declared schema is any Malli form.", :gen/elements [nil :k [] {}]}]] :boolean]}
  [schema-key]
  (contains? world-supplied schema-key))

(defn slot-of
  "The slot `candidate` fills at argument `position`."
  {:malli/schema [:=> [:cat :seon.call-preparation/supplied-default :int] :map]}
  [candidate position]
  {:seon.fn.argument/index (long position)
   :seon.call-preparation/key (:seon.call-preparation/key candidate)
   :seon.call-preparation/supplier-symbol
   (:seon.call-preparation/supplier-symbol candidate)})

(defn arity-plan
  "One compiled arity joined to the supplied defaults by declared schema name:
  a slot by its schema name, a map entry by its key AND value schema name, and
  a map whose required entries are all supplied as a slot of its own."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :seon.call-preparation/supplied-default] :map]
                  :seon.call-preparation/arity-plan]}
  [index {arguments :seon.call-preparation/arity-arguments variadic? :seon.call-preparation/variadic? :as arity}]
  (let [entries (vec (for [{position :seon.fn.argument/index map-entries :seon.call-preparation/map-entries} arguments
                           [entry-key optional? value] map-entries
                           :let [candidate (get index value)]
                           ;; A required entry is supplied, and so is an optional one
                           ;; whose schema is a world member (owner ruling): a function
                           ;; whose meaning needs that member absent declares another
                           ;; name. Any other optional entry's absence is the caller's.
                           :when (and (or (not optional?) (world-member? value))
                                      (= entry-key (:seon.call-preparation/key candidate)))]
                       (cond-> (assoc (slot-of candidate position) :seon.call-preparation/entry-key entry-key)
                         optional? (assoc :seon.call-preparation/optional? true))))
        slots (vec (for [{position :seon.fn.argument/index rest? :seon.fn.argument/rest?
                          schema-key :seon.call-preparation/schema-key map-entries :seon.call-preparation/map-entries} arguments
                         :let [candidate (get index schema-key)
                               required (into #{} (keep (fn [[entry-key optional? _]] (when-not optional? entry-key)))
                                              map-entries)
                               own (filterv #(= position (:seon.fn.argument/index %)) entries)]
                         slot [(when (and candidate (not rest?)) (slot-of candidate position))
                               (when (and (nil? candidate) (seq required)
                                          (= required (set (keep #(when-not (:seon.call-preparation/optional? %)
                                                                    (:seon.call-preparation/entry-key %))
                                                                 own))))
                                 {:seon.fn.argument/index position :seon.call-preparation/entries own})]
                         :when slot]
                     slot))
        ;; A map argument whose declared schema is itself supplied (a world
        ;; member such as :my.program/context): the caller's map keeps its
        ;; entries and the supplied value fills the required ones it lacks.
        merges (vec (for [{position :seon.fn.argument/index rest? :seon.fn.argument/rest?
                           schema-key :seon.call-preparation/schema-key map-entries :seon.call-preparation/map-entries} arguments
                          :let [candidate (get index schema-key)
                                required (into [] (keep (fn [[entry-key optional? _]] (when-not optional? entry-key)))
                                               map-entries)]
                          :when (and candidate (not rest?) (seq required))]
                      (assoc (slot-of candidate position) :seon.call-preparation/required required)))]
    (cond-> {:seon.fn.arity/order (:seon.fn.arity/order arity)
             :seon.fn.arity/min (long (:seon.fn.arity/min arity))
             :seon.fn.arity/argument-count (:seon.fn.arity/argument-count arity)
             :seon.call-preparation/variadic? variadic?
             :seon.call-preparation/rest-slot?
             (boolean (some #(and (:seon.fn.argument/rest? %) (get index (:seon.call-preparation/schema-key %)))
                            arguments))
             :seon.call-preparation/slots slots
             :seon.call-preparation/merges merges
             :seon.call-preparation/entries entries}
      (and (not variadic?) (> (count slots) 1))
      (assoc :seon.call-preparation/argument-validators (mapv (comp m/validator :seon.call-preparation/argument-schema) arguments)))))

(defn supply-index
  "Every supplied default by declared schema name: the admitted rows, and each
  world member (`world-candidates`), which wins over a row naming it. The one
  index both planners join contracts to (`planned-arities`, `arm-plan`)."
  {:malli/schema [:=> [:cat :seon.call-preparation/supplied-defaults]
                  [:map-of :qualified-keyword :seon.call-preparation/supplied-default]]}
  [supplied-defaults]
  (merge (by-schema-key supplied-defaults) world-candidates))

(defn planned-arities
  "`sym`'s contract arities in `projection` planned against the admitted
  `supplied-defaults`, or nil. A world member's schema name is planned from the
  world, whatever row also names it."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.call-preparation/supplied-defaults :qualified-symbol]
                  [:maybe :seon.call-preparation/arity-plans]]}
  [projection supplied-defaults sym]
  (let [index (supply-index supplied-defaults)]
    (some->> (contract-arities projection sym)
             (mapv #(arity-plan index %)))))

(defn declared-schema-keys
  "Canonical base-extension declarations in this projection, excluding aliases.
  The immutable projection retains the derived population, never error values."
  {:malli/schema [:=> [:cat :map] [:set :qualified-keyword]]}
  [projection]
  (internal/projection-cache-value
   projection :seon.error/declared-schema-keys
   (fn []
     (let [forms (:seon.schema.projection/forms projection)]
       (into #{}
             (keep (fn [[k _]]
                     (when (and (not= k :seon.error/base)
                                (#{:and :merge} (m/type (mr/schema (:seon.schema.projection/registry projection) k)))
                                (internal/extends-schema?
                                 (mr/schema (:seon.schema.projection/registry projection) k) :seon.error/base))
                       k)))
             forms)))))

(defn admitted-rows
  "The supplied-default rows `entities` (`entity-query`'s result) proved against
  `program`'s contracts and compiled under `projection`: the admitted defaults by
  key, their value validators, and a refusal for each incoherent row, never
  installed."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/projection [:set [:tuple :qualified-keyword :qualified-keyword :symbol]]]
                  [:map [:seon.call-preparation/supplied-defaults :seon.call-preparation/supplied-defaults]
                   [:seon.call-preparation/validators :seon.call-preparation/validators]
                   [:seon.call-preparation/refusals [:vector :seon.call-preparation/incoherent-supplier-error]]]]}
  [program projection entities]
  (let [contracts (into {} (keep (fn [[_ _ supplier]]
                                   (let [sym (symbol supplier)]
                                     (when-let [arities (contract-arities program sym)]
                                       [sym arities]))))
                        entities)
        ;; debt: seon.db supplied-database-value and supplied-connection still declare :seon.error/value.
        error-keys (conj (declared-schema-keys projection) :seon.error/value)
        compiled
        (mapv (fn [[default-key schema-key supplier]]
                (let [candidate {:seon.call-preparation/key default-key
                                 :seon.call-preparation/schema-key schema-key
                                 :seon.call-preparation/supplier-symbol (symbol supplier)}]
                  (if-let [refusal (coherent-supplier contracts candidate error-keys)]
                    [candidate refusal nil]
                    ;; Compiling the value validator is part of proving the entity: a
                    ;; value schema the projection cannot compile is an incoherent
                    ;; entity, not an exception thrown at acquisition.
                    (try
                      [candidate nil (schema/projection-validator projection schema-key)]
                      (catch Throwable cause
                        [candidate
                         (incoherent candidate
                                     (str "this cluster's projection cannot compile its value schema "
                                          schema-key ": " (ex-message cause))
                                     {:seon.call-preparation/schema-key schema-key})
                         nil])))))
              (sort entities))]
    {:seon.call-preparation/supplied-defaults
     (into {} (keep (fn [[candidate refusal _]] (when-not refusal [(:seon.call-preparation/key candidate) candidate])))
           compiled)
     :seon.call-preparation/validators
     (into {} (keep (fn [[candidate refusal valid?]] (when-not refusal [(:seon.call-preparation/key candidate) valid?])))
           compiled)
     :seon.call-preparation/refusals (into [] (keep second) compiled)}))
