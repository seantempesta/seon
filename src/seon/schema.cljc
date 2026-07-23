(ns seon.schema
  "Malli declaration and runtime-projection boundary for Seon.

   Canonical schema forms are database facts. During module loading this
   namespace collects compiled declarations; after database reconciliation it
   validates and activates one immutable projection of those facts.

   Namespaces declare schemas here with `register!`, making them
   available for `:malli/schema` fn validation, generative testing, and
   runtime validation. The `::` syntax expands to the current namespace,
   so `::user-id` in `seon.trading.core` becomes
   `:seon.trading.core/user-id`.

     (require '[seon.schema :as schema])
     (schema/register! ::user-id   :uuid)
     (schema/register! ::user-name [:string {:min 1 :max 200}])

   Reusable form inspection lives in `seon.schema.form`; register!-time gates
   live in `seon.schema.internal`, outside agent context."
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [clojure.walk :as walk]
            [seon.schema.form :as form]
            [seon.schema.internal :as internal]
            #?(:cljs [sci.core])
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

(defn- direct-references*
  "Canonical registry keys directly referenced by one compiled schema.

   Malli's own walker distinguishes references from keyword data. Canonical
   refs are recorded but not followed; local property-registry refs are
   followed so canonical refs nested behind them are still visible."
  [compiled canonical-keys]
  (let [!references (volatile! #{})]
    (m/walk
      compiled
      (fn [schema _path _children _options]
        (when (m/-ref-schema? schema)
          (let [reference (m/-ref schema)]
            (when (contains? canonical-keys reference)
              (vswap! !references conj reference))))
        schema)
      {::m/walk-schema-refs #(not (contains? canonical-keys %))
       ::m/walk-refs #(not (contains? canonical-keys %))})
    @!references))

(defn- predicate-symbols-in [value]
  (cond
    (and (vector? value) (= :fn (first value)))
    (let [body (if (map? (second value)) (drop 2 value) (rest value))
          predicate (first body)]
      (if (qualified-symbol? predicate) #{predicate} #{}))

    (map? value)
    (into #{} (mapcat predicate-symbols-in) (concat (keys value)
                                                    (vals value)))

    (coll? value)
    (into #{} (mapcat predicate-symbols-in) value)

    :else #{}))

(defn- runtime-predicate [predicate]
  (try
    #?(:clj (some-> (requiring-resolve predicate) deref)
       :cljs nil)
    (catch #?(:clj Throwable :cljs :default) _
      nil)))

(defn predicate-sci-options
  "Build Malli SCI options from qualified predicate function bindings."
  {:malli/schema [:=> [:cat :map] :map]}
  [predicate-functions]
  {:preset :termination-safe
   :aliases {'m 'malli.core}
   :namespaces
   (reduce-kv
     (fn [namespaces predicate f]
       (assoc-in namespaces
                 [(symbol (namespace predicate))
                  (symbol (name predicate))]
                 f))
     {'malli.core {'properties m/properties
                   'type m/type
                   'children m/children
                   'entries m/entries}}
     predicate-functions)})

(defn- bind-core-predicates
  "Replace registered core predicate symbols only for Malli compilation."
  [form predicate-functions]
  (walk/postwalk
   (fn [value]
     (if (and (vector? value) (= :fn (first value)))
       (let [predicate-index (if (map? (second value)) 2 1)
             predicate (get value predicate-index)]
         (if-let [f (and (qualified-symbol? predicate)
                         (get predicate-functions predicate))]
           (assoc value predicate-index f)
           value))
       value))
   form))

(defn- bound-forms [forms predicate-functions]
  (update-vals forms #(bind-core-predicates % predicate-functions)))

(defn- portable-string-hash [s]
  #?(:clj (.hashCode ^String s)
     :cljs
     (loop [i 0 result 0]
       (if (= i (.-length s))
         result
         (recur (inc i)
                (bit-or 0 (+ (* 31 result) (.charCodeAt s i))))))))

(defn- framed [tag payload]
  (str tag (count payload) ":" payload))

(declare canonical-data-string)

(defn- canonical-coll-string [tag values]
  (framed tag (apply str (map canonical-data-string values))))

(defn- canonical-data-string [value]
  (cond
    (nil? value) "n"
    (true? value) "b1"
    (false? value) "b0"
    (keyword? value) (framed "k" (str value))
    (symbol? value) (framed "y" (str value))
    (string? value) (framed "s" value)
    (number? value) (framed "d" (str value))
    (vector? value) (canonical-coll-string "v" value)
    (set? value) (canonical-coll-string
                   "t" (sort (map canonical-data-string value)))
    (map? value)
    (canonical-coll-string
      "m"
      (sort (map (fn [[k v]]
                   (str (canonical-data-string k)
                        (canonical-data-string v)))
                 value)))
    (sequential? value) (canonical-coll-string "q" value)
    :else
    (throw (ex-info "Schema projection fingerprint contains non-EDN data."
                    {:seon.schema/error
                     :seon.schema/noncanonical-projection-data
                     :seon.schema/value value
                     :seon.error/kind :core-bug}))))

(defn direct-references
  "Canonical schema keys directly referenced by `form` in `projection`.

   This is a derived dependency view over Malli schema objects, not a keyword
   scan and not stored state. It works for value, entity, and function-schema
   forms and follows local recursive registries without expanding canonical
   references transitively."
  {:malli/schema [:=> [:catn [::projection :map] [::definition :any]]
                  [:set :keyword]]}
  [projection form]
  (let [forms (:seon.schema.projection/forms projection)
        registry (:seon.schema.projection/registry projection)
        compiled (m/schema form {:registry registry})]
    (direct-references* compiled (set (keys forms)))))

(defn dependent-schema-keys
  "Changed schema keys plus their reverse transitive dependents.

   The dependency graph belongs to the immutable projection. The result is
   derived with a bounded graph walk and is empty when `changed` is empty."
  {:malli/schema [:=> [:catn [::projection :map]
                             [::changed [:set :keyword]]]
                  [:set :keyword]]}
  [projection changed]
  (let [reverse-edges
        (:seon.schema.projection/reverse-schema-dependencies projection)]
    (loop [frontier (set changed)
           seen #{}]
      (if (empty? frontier)
        seen
        (let [seen' (into seen frontier)
              next-frontier
              (into #{}
                    (comp
                      (mapcat #(get reverse-edges % #{}))
                      (remove seen'))
                    frontier)]
          (recur next-frontier seen'))))))

;;; ---------------------------------------------------------------------------
;;; Registry Setup
;;; ---------------------------------------------------------------------------

;; One process-local schema state. Candidate declarations may run ahead of the
;; active database-derived projection while an eval is being prepared; both
;; views live in this one atom so projection publication is one Seon-visible
;; mutation. The projection remains disposable and reconstructable from facts.
(defonce ^:private !schema-state
  (atom {:seon.schema.state/candidate-forms {}
         :seon.schema.state/predicate-functions {}
         :seon.schema.state/projection nil}))

(def ^:dynamic ^:private *candidate-forms-overlay* nil)
(def ^:dynamic ^:private *registration-admission-source* :core)

(def asserting-transaction-provenance-pattern
  "Pull pattern for the transaction that asserted a canonical schema/contract
   form. Producers pass this as Datalog input so admission source remains a
   derived cache value, never another stored schema-row attribute."
  '[{:seon.db/user [:seon.agent/id :seon.user/id]}
    {:seon.db/process [:seon.db.process/id]}])

(def ^:private core-process-identities
  #{:seon.db.process/boot
    :seon.db.process/config
    :seon.db.process/core})

(defn admission-from-asserting-transaction
  "Derive strictness source from one canonical row's asserting transaction.

   Missing and unrecognized provenance deliberately fail closed as
   agent-authored. The note is projection-cache guidance, not database truth."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [transaction]
  (let [process-id (get-in transaction [:seon.db/process
                                        :seon.db.process/id])
        user (some-> (:seon.db/user transaction)
                     (select-keys [:seon.agent/id :seon.user/id]))
        recognized? (some? process-id)
        source (if (contains? core-process-identities process-id)
                 :core
                 :agent)]
    (cond->
      {:seon.schema.admission/source source
       :seon.schema.admission/process-id process-id
       :seon.schema.admission/user (or user {})}
      (not recognized?)
      (assoc :seon.schema.admission/note
             (str "The asserting transaction has no recognizable process "
                  "provenance, so this row is admitted as agent-authored. "
                  "Re-register it through boot/config/core reconciliation "
                  "to claim the documented core exceptions."))

      (and recognized?
           (= :agent source)
           (not (#{:seon.db.process/repl :seon.db.process/agent}
                 process-id)))
      (assoc :seon.schema.admission/note
             (str "The asserting process is not a recognized core process, "
                  "so this row is admitted as agent-authored. Re-register it "
                  "through boot/config/core reconciliation to claim the "
                  "documented core exceptions.")))))

(defn- candidate-forms []
  (if *candidate-forms-overlay*
    @*candidate-forms-overlay*
    (:seon.schema.state/candidate-forms @!schema-state)))

(defn- active-projection []
  (:seon.schema.state/projection @!schema-state))

(defn register-core-predicate!
  "Cache one host-authored predicate function for portable Malli compilation.

   The qualified symbol remains the durable schema form and admission
   authority; this reloadable function cache only supplies Malli's SCI tier."
  {:malli/schema [:=> [:cat :qualified-symbol 'ifn?] :qualified-symbol]}
  [predicate f]
  (swap! !schema-state assoc-in
         [:seon.schema.state/predicate-functions predicate]
         f)
  predicate)

(defn- core-predicate-functions []
  (:seon.schema.state/predicate-functions @!schema-state))

(defn- active-forms []
  (or (:seon.schema.projection/forms (active-projection))
      (candidate-forms)))

(defn- update-candidate-forms! [f & args]
  (if *candidate-forms-overlay*
    (apply swap! *candidate-forms-overlay* f args)
    (apply swap! !schema-state update
           :seon.schema.state/candidate-forms f args)))

(defn- candidate-registry []
  (mr/composite-registry
    (m/default-schemas)
    (mr/fast-registry
     (bound-forms (candidate-forms) (core-predicate-functions)))))

;; THE one stable registry facade Seon installs as Malli's process-global
;; default. Once a projection is active it reads only that committed
;; generation; candidate validation passes [[candidate-registry]] explicitly.
;; Before first activation it reads module declarations so namespace loading
;; can bootstrap normally. Normal activation never repoints Malli's default.
(defonce ^:private seon-registry
  (let [defaults (mr/fast-registry (m/default-schemas))]
    (reify
      mr/Registry
      (-schema [this type]
        (or (mr/-schema defaults type)
            (when-let [form (get (active-forms) type)]
              (m/schema
               (bind-core-predicates form (core-predicate-functions))
               {:registry this
                ::m/sci-options
                (predicate-sci-options (core-predicate-functions))}))))
      (-schemas [_]
        (merge (mr/-schemas defaults) (active-forms))))))

(defn relink-registry!
  "Repoint Malli's convenience default to Seon's stable registry facade.

   The bootstrap load wrapper calls this after Malli bundle loads that reset
   their own default. Normal projection publication does not call this
   throwable integration boundary."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (mr/set-default-registry! seon-registry)
  true)

;; Initialize the global registry once at load time.
(defonce ^:private _registry-init (relink-registry!))

;; :inst as a keyword type (Malli only provides the `inst?` predicate), for
;; consistency with :string, :int, etc. The quoted predicate is pure data and
;; round-trips through the canonical database schema fact.
(defonce ^:private _inst-type
  (update-candidate-forms! merge form/primitive-schema-forms))

;; :seon.db/lookup-ref-value — the value position in a lookup-ref. Datahike
;; accepts strings, uuids, keywords, symbols, and ints as unique-attr values.
(defonce ^:private _lookup-ref-value-type
  (update-candidate-forms! assoc :seon.db/lookup-ref-value
                           [:or :string :uuid :keyword :symbol :int]))

;; :seon.db/ref — an intra-DB :db.type/ref. At transact time datahike
;; resolves any supported form to an eid: pos-int (existing eid), neg-int
;; (numeric tempid), string (string tempid), or [k v] (lookup-ref on unique
;; attr k). Cross-DB handles are :uuid attrs with :seon.db/ref-to metadata —
;; NEVER :seon.db/ref. Reference: docs/prds/datahike-migration/ref-model-research.md.
(defonce ^:private _ref-type
  (update-candidate-forms! assoc :seon.db/ref
                           [:or
                            :int
                            :string
                            [:tuple :keyword :seon.db/lookup-ref-value]]))

;; Generated persistent identity syntax is owned by `seon.db.id`, which loads
;; before `seon.db` registers slots that refer to `:seon.db/id`.  Keeping an
;; older bootstrap copy here let namespace load order silently restore the
;; retired timestamp grammar, so there is deliberately no second definition.

;; Positional-arg slot shapes for this ns's register/introspection fns — each
;; named-positional `:catn` slot in a `:malli/schema` below references one of
;; these (db.cljs's `::conn`/`::tx-data` slot-schema pattern). A Malli schema
;; DEFINITION is a recursive, heterogeneous structure —
;; genuinely opaque, hence `:any` (the documented third-party-shape exception).
(defonce ^:private _registry-key-type
  (update-candidate-forms! assoc :seon.schema/registry-key :keyword))

(defn malli-form?
  "True when `value` is readable EDN and Malli can parse it.

   Uses Seon's current
   candidate registry. This is intentionally structural; validation remains a
   separate operation."
  [value]
  (try
    (let [encoded (pr-str value)
          decoded (#?(:clj edn/read-string :cljs reader/read-string) encoded)]
      (and (= value decoded)
           (some? (m/schema value {:registry (candidate-registry)}))))
    (catch #?(:clj Exception :cljs :default) _
      false)))

(defonce ^:private _malli-form-predicate
  (register-core-predicate! 'seon.schema/malli-form? malli-form?))

(defonce ^:private _malli-form-type
  (update-candidate-forms!
    assoc ::malli-form
    [:fn {:error/message "must be a parseable, EDN-readable Malli form"
          :gen/schema [:enum :string :int [:vector :keyword]]}
     'seon.schema/malli-form?]))

(defonce ^:private _definition-type
  (update-candidate-forms! assoc :seon.schema/definition ::malli-form))
(defonce ^:private _value-type
  (update-candidate-forms! assoc :seon.schema/value :any))
(defonce ^:private _explanation-type
  (update-candidate-forms! assoc :seon.schema/explanation :map))
(defonce ^:private _namespace-name-type
  (update-candidate-forms! assoc :seon.schema/namespace-name :string))
(defonce ^:private _kvs-type
  (update-candidate-forms! assoc :seon.schema/kvs [:vector :any]))
(defonce ^:private _discarded-keys-type
  (update-candidate-forms! assoc :seon.schema/discarded-keys
                           [:set :seon.schema/registry-key]))
(defonce ^:private _projection-row-type
  (update-candidate-forms! assoc :seon.schema/projection-row
                           [:or
                            [:tuple [:or :keyword :string :symbol] :string]
                            [:tuple [:or :keyword :string :symbol]
                             :string
                             [:maybe :map]]]))
(defonce ^:private _projection-rows-type
  (update-candidate-forms! assoc :seon.schema/projection-rows
                           [:or
                            [:set :seon.schema/projection-row]
                            [:sequential :seon.schema/projection-row]]))
(defonce ^:private _projection-input-type
  (update-candidate-forms!
    assoc :seon.schema/projection-input
    [:map {:closed true}
     [:seon.schema/schema-rows :seon.schema/projection-rows]
     [:seon.schema/function-contract-rows :seon.schema/projection-rows]
     [:seon.schema/pure-predicate-symbols
      {:optional true}
      [:set :symbol]]]))
(defonce ^:private _projection-type
  (update-candidate-forms! assoc :seon.schema/projection 'map?))

;;; ---------------------------------------------------------------------------
;;; Registration API
;;; ---------------------------------------------------------------------------

(defn assert-complete-contract!
  "Assert that a schema or function contract is complete.

   Uses its derived
   admission source. Returns non-terminal advisories."
  [{:seon.schema/keys [identity definition forms admission admissions
                       pure-predicate-symbols predicate-functions]
    :or {forms {}
         admissions {}
         pure-predicate-symbols #{}
         predicate-functions {}}}]
  (let [direct-predicate-symbols (predicate-symbols-in definition)
        forms (if (and (keyword? identity) (not (contains? forms identity)))
                (assoc forms identity definition)
                forms)
        predicate-symbols
        (into direct-predicate-symbols
              (mapcat predicate-symbols-in)
              (vals forms))
        predicate-functions
        (reduce (fn [bindings predicate]
                  (if (contains? bindings predicate)
                    bindings
                    (if-let [f (runtime-predicate predicate)]
                      (assoc bindings predicate f)
                      bindings)))
                predicate-functions
                predicate-symbols)
        core-predicates (core-predicate-functions)
        compiled-forms (bound-forms forms core-predicates)
        compiled-definition
        (bind-core-predicates definition core-predicates)
        _ (when (= :agent (:seon.schema.admission/source
                           (or admission
                               {:seon.schema.admission/source :agent})))
            (when-let [predicate
                       (first (remove pure-predicate-symbols
                                      (sort direct-predicate-symbols)))]
              (throw
               (ex-info
                (str identity " references predicate " predicate
                     ", but its existing program-graph call edges do not yet "
                     "prove a pure, capability-free transitive call graph. "
                     "Keep the predicate as a separately schema'd corpus "
                     "function, then re-register this contract after the "
                     "execution planner admits that graph.")
                {:seon.schema/error
                 :seon.schema/unproved-predicate-purity
                 :seon.schema/identity identity
                 :seon.schema/predicate predicate
                 :seon.error/kind :user-input}))))
        registry (mr/composite-registry
                   (m/default-schemas)
                   (mr/fast-registry compiled-forms))
        compile-options
        {:registry registry
         ::m/sci-options (predicate-sci-options predicate-functions)}
        canonical-keys (set (keys forms))
        default-admission (or admission
                              {:seon.schema.admission/source :agent})]
    (letfn [(walk-schema [schema role row-identity row-definition row-admission
                          visited]
              (let [advisories
                    (internal/assert-complete-schema!
                      {:seon.schema/identity row-identity
                       :seon.schema/definition row-definition
                       :seon.schema/compiled schema
                       :seon.schema/role role
                       :seon.schema/admission row-admission
                       :seon.schema/pure-predicate-symbols
                       pure-predicate-symbols
                       :seon.schema/canonical-keys canonical-keys})
                    references (direct-references* schema canonical-keys)]
                (into advisories
                      (mapcat
                        (fn [reference]
                          (when-not (contains? visited reference)
                            (let [reference-form (get forms reference)]
                              (walk-schema
                                (m/schema
                                  (bind-core-predicates
                                   reference-form core-predicates)
                                          compile-options)
                                role
                                reference
                                reference-form
                                (get admissions reference default-admission)
                                (conj visited reference))))))
                      references)))
            (walk-function [compiled row-identity row-definition row-admission]
              (case (m/type compiled)
                :=>
                (let [[input output] (m/children compiled)]
                  (into
                    (walk-schema input :input row-identity row-definition
                                 row-admission #{})
                    (walk-schema output :output row-identity row-definition
                                 row-admission #{})))

                :function
                (into []
                      (mapcat
                        #(walk-function % row-identity row-definition
                                        row-admission))
                      (m/children compiled))

                (walk-schema compiled :schema row-identity row-definition
                             row-admission #{})))]
      (let [compiled (if (and (vector? definition)
                              (#{:=> :function} (first definition)))
                       (m/function-schema compiled-definition compile-options)
                       (m/schema compiled-definition compile-options))]
        (walk-function compiled identity definition default-admission)))))

(defn identity-attr?
  "True when the attr schema for `attr-key` carries `{:seon.db/identity true}`.

   Covers the three identity shapes Seon uses
   (plain `:string`/`:keyword` with the prop, and the `:and` id wrap).
   PUBLIC: the single identity-attr predicate — callers reuse it rather than
   re-deriving the props lookup."
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [attr-key]
  (internal/identity-attr? (candidate-forms) attr-key))

(defn enum-members
  "Members of a registered `:enum` attr schema, or an empty vector.

   Empty when the attr is not an enum (absence = empty, never nil). Reads the schema
   form directly — NO db query. PUBLIC: low-cardinality value surfaces reuse
   it. Members are Malli-form contents
   (keywords/strings/ints) — a third-party-structure boundary, hence `:any`."
  {:malli/schema [:=> [:cat :keyword] [:vector :any]]}
  [attr-key]
  (form/enum-members (get (candidate-forms) attr-key)))

(defn register!
  "Define a new attribute so facts using it can be saved and queried.

   Adds one canonical declaration to the current candidate collector. Schema
   references are resolved only when [[build-projection]] validates the
   complete population, so namespace load order cannot change whether a
   declaration is accepted.

   Arguments:
     k - Schema keyword (use `::name` for auto-namespacing)
     v - Malli schema definition

   Returns the registered keyword `k`.

   Entity-map render metadata stays in the authored form. The activated
   projection derives its id attribute and renderer catalog without persisting
   a second decomposition. Maps without `{:seon.db/entity true}` are ordinary
   request/response or view schemas and do not enter that catalog.

   Example:
     (register! ::api-key [:string {:min 1}])
     (register! ::timeout [:int {:min 1000 :max 600000}])
     (register! :seon.eval [:map {:seon.db/entity true
                                  :seon.render/ai 'foo}
                            [:seon.eval/id ...] ...])"
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]
                         [::definition ::definition]]
                  ::registry-key]}
  [k v]
  ;; CLJS-only until the JVM's legacy `:form/*` registrations are renamed.
  #?(:cljs (internal/assert-multi-segment-namespace! k)
     :clj  nil)
  (internal/assert-non-nilable-value-schema! (candidate-forms) k v)
  (let [encoded (pr-str v)
        decoded (try
                  (#?(:clj edn/read-string :cljs reader/read-string) encoded)
                  (catch #?(:clj Exception :cljs :default) e
                    (throw
                      (ex-info
                        (str "schema/register! " k
                             ": schema forms must be readable EDN; "
                             "function objects and executable values belong "
                             "at function boundaries")
                        {:seon.schema/error :seon.schema/unreadable-form
                         :seon.schema/key k
                         :seon.schema/definition v
                         :seon.error/kind :user-input}
                        e))))]
    (when-not (= v decoded)
      (throw
        (ex-info
          (str "schema/register! " k
               ": schema form does not round-trip as EDN")
          {:seon.schema/error :seon.schema/non-round-tripping-form
           :seon.schema/key k
           :seon.schema/definition v
           :seon.error/kind :user-input}))))
  ;; Forward references remain declaration-order independent. Complete forms
  ;; are gated now; an unresolved canonical reference is gated when the full
  ;; candidate projection is constructed.
  (try
    (assert-complete-contract!
      {:seon.schema/identity k
       :seon.schema/definition v
       :seon.schema/forms (assoc (candidate-forms) k v)
       :seon.schema/admission
       {:seon.schema.admission/source *registration-admission-source*}
       :seon.schema/predicate-functions (core-predicate-functions)})
    (catch #?(:clj Exception :cljs :default) e
      (when-not (= :malli.core/invalid-schema (:type (ex-data e)))
        (throw e))))
  (update-candidate-forms! assoc k v)
  k)

(defn form-string
  "Canonical, full EDN encoding of registered schema `k`, or nil when absent.

   Registration already proves the value round-trips, so this never truncates
   or replaces runtime objects with display placeholders. This is the durable
   `:seon.schema/form` value."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  [:maybe :string]]}
  [k]
  (some-> (get (candidate-forms) k) pr-str))

(defn build-projection
  "Build and validate one immutable runtime projection.

   `forms` is the canonical `{schema-key form}` population and optional
   `function-contracts` is `{qualified-symbol function-form}`. Every schema and
   contract compiles against the complete candidate registry, so validation is
   independent of declaration order. Schema/function dependency indexes and
   the entity catalog are derived here; none are stored as a second model.
   Pure: no atom, default-registry, database, or var mutation."
  {:malli/schema
   [:function
    [:=> [:catn [::forms :map]] :map]
    [:=> [:catn [::forms :map] [::function-contracts :map]] :map]
    [:=> [:catn [::forms :map] [::function-contracts :map]
                   [:seon.schema/projection-options :map]]
     :map]]}
  ([forms]
   (build-projection
    forms {} {:seon.schema/predicate-functions (core-predicate-functions)}))
  ([forms function-contracts]
   (build-projection
    forms function-contracts
    {:seon.schema/predicate-functions (core-predicate-functions)}))
  ([forms function-contracts
    {:seon.schema/keys [schema-admissions function-admissions
                        pure-predicate-symbols predicate-functions]
     :or {schema-admissions {}
          function-admissions {}
          pure-predicate-symbols #{}
          predicate-functions {}}}]
   (let [predicate-symbols
         (into (into #{} (mapcat predicate-symbols-in) (vals forms))
               (mapcat predicate-symbols-in)
               (vals function-contracts))
         predicate-functions
         (reduce (fn [bindings predicate]
                   (if (contains? bindings predicate)
                     bindings
                     (if-let [f (runtime-predicate predicate)]
                       (assoc bindings predicate f)
                       bindings)))
                 predicate-functions
                 predicate-symbols)
         core-predicates (core-predicate-functions)
         compiled-forms (bound-forms forms core-predicates)
         core-admission {:seon.schema.admission/source :core}
         registry (mr/composite-registry
                    (m/default-schemas)
                    (mr/fast-registry compiled-forms))
         options  {:registry registry
                   ::m/sci-options
                   (predicate-sci-options predicate-functions)}
         _        (doseq [[k form] (sort-by key forms)]
                    (internal/assert-compilable-schema!
                     compiled-forms k
                     (get compiled-forms k)
                     options)
                    (internal/assert-non-nilable-value-schema! forms k form)
                    (assert-complete-contract!
                      {:seon.schema/identity k
                       :seon.schema/definition form
                       :seon.schema/forms forms
                       :seon.schema/admission
                       (get schema-admissions k core-admission)
                       :seon.schema/admissions schema-admissions
                       :seon.schema/pure-predicate-symbols
                       pure-predicate-symbols
                       :seon.schema/predicate-functions predicate-functions}))
        _        (doseq [[sym contract] (sort-by key function-contracts)]
                   (assert-complete-contract!
                     {:seon.schema/identity sym
                      :seon.schema/definition contract
                      :seon.schema/forms forms
                      :seon.schema/admission
                      (get function-admissions sym core-admission)
                      :seon.schema/admissions schema-admissions
                      :seon.schema/pure-predicate-symbols
                      pure-predicate-symbols
                      :seon.schema/predicate-functions predicate-functions}))
        _        (doseq [k (sort (keys forms))]
                   (m/schema k options))
        schema-dependencies
        (into (sorted-map)
              (map (fn [[k form]]
                     [k (direct-references*
                          (m/schema (get compiled-forms k) options)
                          (set (keys forms)))]))
              forms)
        reverse-schema-dependencies
        (reduce-kv
          (fn [reverse-edges dependent dependencies]
            (reduce (fn [edges dependency]
                      (update edges dependency (fnil conj #{}) dependent))
                    reverse-edges
                    dependencies))
          {}
          schema-dependencies)
        function-dependencies
        (into (sorted-map)
              (map (fn [[sym form]]
                     [sym (direct-references*
                            (m/function-schema
                             (bind-core-predicates
                              form core-predicates)
                             options)
                            (set (keys forms)))]))
              function-contracts)
        shape-rows
        (into (sorted-map)
              (keep
                (fn [[k raw]]
                  (let [form (internal/with-entity-id-attr forms raw)
                        map-shape? (form/map-shape? form)
                        props (when map-shape?
                                (or (form/schema-properties form) {}))
                        required-attrs
                        (when map-shape?
                          (set (internal/map-required-attrs form)))
                        id-attr (:seon.entity/id-attr props)]
                    (when (seq required-attrs)
                      [k (cond->
                           {:seon.schema/key k
                            :seon.schema/required-attrs required-attrs
                            :seon.schema/entity?
                            (boolean (:seon.db/entity props))}
                           id-attr
                           (assoc :seon.entity/id-attr id-attr)

                           (:seon.render/ai props)
                           (assoc :seon.render/ai (:seon.render/ai props))

                           (:seon.render/html props)
                           (assoc :seon.render/html
                                  (:seon.render/html props)))]))))
              forms)
        required-by-key
        (into (sorted-map)
              (map (fn [[k row]]
                     [k (:seon.schema/required-attrs row)]))
              shape-rows)
        raw-shape-index
        (reduce-kv
          (fn [index schema-key required-attrs]
            (reduce (fn [result attr]
                      (update result attr (fnil conj []) schema-key))
                    index
                    required-attrs))
          (sorted-map)
          required-by-key)
        shape-rank
        (fn [schema-key]
          [(- (count (get required-by-key schema-key))) (str schema-key)])
        shape-index
        (into (sorted-map)
              (map (fn [[attr schema-keys]]
                     [attr (vec (sort-by shape-rank schema-keys))]))
              raw-shape-index)
        catalog  (->> shape-rows
                      vals
                      (keep
                        (fn [{:seon.schema/keys [key required-attrs]
                              :seon.entity/keys [id-attr]
                              :seon.render/keys [ai html]
                              :as row}]
                          (when (and (:seon.schema/entity? row) id-attr)
                            (cond->
                              {:seon.schema.catalog/key key
                               :seon.schema.catalog/id-attr id-attr
                               :seon.schema.catalog/required-attrs
                               required-attrs}
                              ai
                              (assoc :seon.schema.catalog/render-ai ai)

                              html
                              (assoc :seon.schema.catalog/render-html html)))))
                      vec)
        fingerprint
        (portable-string-hash
          (canonical-data-string
            [forms function-contracts schema-admissions function-admissions
             pure-predicate-symbols]))]
    {:seon.schema.projection/forms forms
     :seon.schema.projection/registry registry
     :seon.schema.projection/schema-admissions schema-admissions
     :seon.schema.projection/function-admissions function-admissions
     :seon.schema.projection/pure-predicate-symbols pure-predicate-symbols
     :seon.schema.projection/schema-dependencies schema-dependencies
     :seon.schema.projection/reverse-schema-dependencies
     reverse-schema-dependencies
     :seon.schema.projection/function-contracts function-contracts
     :seon.schema.projection/function-dependencies function-dependencies
     :seon.schema.projection/required-by-key required-by-key
     :seon.schema.projection/shape-index shape-index
     :seon.schema.projection/shape-rows shape-rows
     :seon.schema.projection/catalog catalog
     :seon.schema.projection/fingerprint fingerprint})))

(defn projection-from-rows
  "Build one complete projection from committed schema and contract rows.

   Rows are ordinary database query results. Duplicate identities are rejected
   rather than resolved by iteration order, and every EDN form is parsed by
   the platform reader before the one [[build-projection]] mechanism runs."
  {:malli/schema [:=> [:catn [::projection-input ::projection-input]]
                  ::projection]}
  [{:seon.schema/keys [schema-rows function-contract-rows
                       pure-predicate-symbols]
    :or {pure-predicate-symbols #{}}}]
  (letfn [(parse-rows [rows identity-fn identity-label]
            (reduce
              (fn [parsed row]
                (when-not (and (sequential? row)
                               (#{2 3} (count row)))
                  (throw (ex-info (str "Malformed committed " identity-label
                                       " row.")
                                  {:seon.schema/error
                                   :seon.schema/malformed-projection-row
                                   :seon.schema/row row
                                   :seon.error/kind :core-bug})))
                (let [[raw-identity form-string asserting-transaction] row
                      identity (identity-fn raw-identity)]
                  (when-not (string? form-string)
                    (throw (ex-info (str "Malformed committed " identity-label
                                         " form.")
                                    {:seon.schema/error
                                     :seon.schema/malformed-projection-form
                                     :seon.schema/row row
                                     :seon.error/kind :core-bug})))
                  (when (contains? parsed identity)
                    (throw (ex-info (str "Duplicate committed " identity-label
                                         " " identity ".")
                                    {:seon.schema/error
                                     :seon.schema/duplicate-projection-row
                                     :seon.schema/identity identity
                                     :seon.error/kind :core-bug})))
                  (assoc parsed identity
                         {:seon.schema.parsed/form
                          (#?(:clj edn/read-string :cljs reader/read-string)
                           form-string)
                          :seon.schema.parsed/admission
                          (admission-from-asserting-transaction
                            asserting-transaction)})))
              {}
              rows))]
    (let [schemas
          (parse-rows schema-rows
                  (fn [identity]
                    (if (keyword? identity)
                      identity
                      (throw (ex-info "Committed schema identity is not a keyword."
                                      {:seon.schema/error
                                       :seon.schema/malformed-projection-identity
                                       :seon.schema/identity identity
                                       :seon.error/kind :core-bug}))))
                  "schema")
          contracts
          (parse-rows function-contract-rows
                  (fn [identity]
                    (cond
                      (qualified-symbol? identity) identity
                      (string? identity)
                      (let [parsed (symbol identity)]
                        (if (qualified-symbol? parsed)
                          parsed
                          (throw (ex-info "Committed function identity is not qualified."
                                          {:seon.schema/error
                                           :seon.schema/malformed-projection-identity
                                           :seon.schema/identity identity
                                           :seon.error/kind :core-bug}))))
                      :else
                      (throw (ex-info "Committed function identity is malformed."
                                      {:seon.schema/error
                                       :seon.schema/malformed-projection-identity
                                       :seon.schema/identity identity
                                       :seon.error/kind :core-bug}))))
                   "function contract")]
      (build-projection
        (into {} (map (fn [[k row]]
                        [k (:seon.schema.parsed/form row)]))
              schemas)
        (into {} (map (fn [[k row]]
                        [k (:seon.schema.parsed/form row)]))
              contracts)
        {:seon.schema/schema-admissions
         (into {} (map (fn [[k row]]
                         [k (:seon.schema.parsed/admission row)]))
               schemas)
         :seon.schema/function-admissions
         (into {} (map (fn [[k row]]
                         [k (:seon.schema.parsed/admission row)]))
               contracts)
         :seon.schema/pure-predicate-symbols pure-predicate-symbols
         :seon.schema/predicate-functions (core-predicate-functions)}))))

(defn activate-projection!
  "Atomically publish an already validated projection.

   Transition coordinators build the complete candidate before committing,
   then publish that exact object after the database accepts the matching
   facts. No validation or database read occurs here."
  {:malli/schema [:=> [:catn [::projection :map]] :map]}
  [projection]
  (swap! !schema-state
         (fn [state]
           (assoc state
                  :seon.schema.state/candidate-forms
                  (:seon.schema.projection/forms projection)
                  :seon.schema.state/projection projection)))
  projection)

(defn activate!
  "Validate and atomically activate a complete `{schema-key form}` set.

   The candidate is fully built before either the collector or Malli default
   registry changes. Existing canonical function contracts are revalidated
   against the replacement schema population. Returns the activated projection."
  {:malli/schema [:=> [:catn [::forms :map]] :map]}
  [forms]
  (activate-projection!
    (build-projection
      forms
      (or (:seon.schema.projection/function-contracts (active-projection)) {}))))

(defn current-projection
  "The active disposable projection, or nil during initial module loading."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (active-projection))

(defn entity-catalog
  "Derived renderable entity catalog for the active schema projection.

   During initial module loading, before database activation, derives once from
   the declaration snapshot on demand. After activation this is the immutable
   catalog built from canonical database forms. No catalog facts are stored."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (or (:seon.schema.projection/catalog (active-projection))
      (:seon.schema.projection/catalog (build-projection (candidate-forms)))))

(defn current-keys
  "Snapshot of all currently-registered schema keywords.

   Used by detect-and-tee in eval-batch! for atom-diff schema detection (before vs
   after an eval reveals what the form registered)."
  {:malli/schema [:=> [:cat] [:set :keyword]]}
  []
  (set (keys (candidate-forms))))

(defn snapshot
  "Immutable `{schema-key form}` snapshot for one eval transition."
  {:malli/schema [:=> [:cat] :map]}
  []
  (candidate-forms))

(defn begin-registration-delta
  "Create an isolated schema delta for one synchronous eval."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [before (:seon.schema.state/candidate-forms @!schema-state)]
    {:seon.schema.delta/before before
     :seon.schema.delta/candidate-forms (atom before)}))

(defn call-with-registration-delta
  "Call `f` with registrations staged in `delta`."
  {:malli/schema
   [:=>
    [:catn [:seon.schema/registration-delta :map]
           [:seon.schema/body 'ifn?]]
    :any]}
  [delta f]
  (binding [*candidate-forms-overlay*
            (:seon.schema.delta/candidate-forms delta)
            *registration-admission-source* :agent]
    (f)))

(defn- changed-candidate-keys [before after]
  (into #{}
        (keep (fn [[k form]]
                (when (not= form (get before k ::absent)) k)))
        after))

(defn changed-keys
  "Schema keys whose canonical form differs from `before`, including new keys."
  {:malli/schema [:=> [:catn [::before :map]] [:set :keyword]]}
  [before]
  (if-let [candidate (:seon.schema.delta/candidate-forms before)]
    (changed-candidate-keys (:seon.schema.delta/before before) @candidate)
    (changed-candidate-keys before (candidate-forms))))

(defn commit-registration-delta!
  "Atomically merge one successful eval's schema delta."
  {:malli/schema
   [:=> [:catn [:seon.schema/registration-delta :map]] [:set :keyword]]}
  [delta]
  (let [after @(:seon.schema.delta/candidate-forms delta)
        changed (changed-keys delta)]
    (when (seq changed)
      (swap! !schema-state update :seon.schema.state/candidate-forms
             (fn [current]
               (reduce (fn [forms k]
                         (if (contains? after k)
                           (assoc forms k (get after k))
                           (dissoc forms k)))
                       current
                       changed))))
    changed))

(defn restore!
  "Revert only the schema delta represented by `before`.

   Eval-owned deltas are isolated overlays, so failure only discards that
   overlay. Plain snapshots retain the single-threaded compatibility behavior;
   exact test-state capture remains [[snapshot-state]] / [[restore-state!]]."
  {:malli/schema [:=> [:catn [::before :map]] :nil]}
  [before]
  (if-let [candidate (:seon.schema.delta/candidate-forms before)]
    (reset! candidate (:seon.schema.delta/before before))
    (let [after (candidate-forms)
          changed (into (changed-candidate-keys before after)
                        (remove #(contains? after %))
                        (keys before))]
      (update-candidate-forms!
        (fn [current]
          (reduce (fn [forms k]
                    (if (contains? before k)
                      (assoc forms k (get before k))
                      (dissoc forms k)))
                  current
                  changed)))))
  nil)

(defn ^:no-doc snapshot-state
  "Capture the exact process-local schema state for isolated test restoration."
  {:malli/schema [:=> [:cat] :any]}
  []
  @!schema-state)

(defn ^:no-doc restore-state!
  "Restore an exact schema-state snapshot captured by [[snapshot-state]]."
  {:malli/schema [:=> [:catn [::state :any]] :nil]}
  [state]
  (reset! !schema-state state)
  nil)

(defn register-all!
  "Register multiple schemas at once from keyword/definition pairs.

   Returns the set of registered keywords. Throws if an odd
   number of arguments is provided.

   Example:
     (register-all!
       ::user-id    :uuid
       ::user-name  [:string {:min 1}]
       ::user-email [:string {:min 5}])"
  {:malli/schema [:=> [:catn [::kvs [:* :any]]] [:set :keyword]]}
  [& kvs]
  ;; NOTE: each kv pair is a [registry-key form] pair; the variadic slot
  ;; can't enumerate them, hence `[:* :any]`.
  (assert (even? (count kvs)) "register-all! requires pairs of [key schema]")
  (let [pairs (partition 2 kvs)]
    (doseq [[k v] pairs]
      (register! k v))
    (set (map first pairs))))

;;; ---------------------------------------------------------------------------
;;; Introspection
;;; ---------------------------------------------------------------------------

(defn registered-schemas
  "A map of all registered domain schemas (Malli's built-ins excluded)."
  {:malli/schema [:=> [:cat] :map]}
  []
  (candidate-forms))

(defn registered?
  "Check if a schema keyword is registered."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]] :boolean]}
  [k]
  (contains? (candidate-forms) k))

(defn schema-definition
  "The raw definition for a registered schema, or nil if not registered."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]] :any]}
  [k]
  (get (candidate-forms) k))

(defn valid-candidate-value?
  "True when `value` satisfies `schema-key` in the current candidate.

   Candidate declarations intentionally do not mutate Malli's process-global
   default registry before their database transaction commits. Boundaries
   validating a declaration and its first facts together use this function so
   they see the complete candidate without publishing it early."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]
                             [::value ::value]]
                  :boolean]}
  [schema-key value]
  (m/validate schema-key value {:registry (candidate-registry)}))

(defn explain-candidate-value
  "Explain a value rejected by the current declaration candidate.

   Uses the same explicit candidate registry as `valid-candidate-value?`; nil
   means the value is valid."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]
                             [::value ::value]]
                  [:maybe ::explanation]]}
  [schema-key value]
  (m/explain schema-key value {:registry (candidate-registry)}))

(register! ::compiled-validator 'fn?)

(def ^:const shape-candidate-limit
  "Maximum schema rows examined and returned for structural diagnostics."
  32)

(def ^:const shape-input-key-limit
  "Maximum map entries examined for structural diagnostics."
  32)

(def ^:dynamic *candidate-visit!*
  "Optional test instrumentation called once per diagnostic schema visit."
  (fn [_schema-key] nil))

(defonce ^:private !shape-generation
  (atom {:seon.schema.shape/projection nil
         :seon.schema.shape/candidate-forms nil
         :seon.schema.shape/validators {}
         :seon.schema.shape/explainers {}}))

(defn projection-validator
  "Compile a validator against exactly one immutable projection."
  {:malli/schema [:=> [:catn [::projection ::projection]
                             [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [projection schema-key]
  (m/validator
    (m/deref-recursive
      schema-key
      {:registry (:seon.schema.projection/registry projection)})))

(defn projection-explainer
  "Compile an explainer against exactly one immutable projection."
  {:malli/schema [:=> [:catn [::projection ::projection]
                             [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [projection schema-key]
  (m/explainer
    (m/deref-recursive
      schema-key
      {:registry (:seon.schema.projection/registry projection)})))

(defn- shape-projection []
  (or (current-projection)
      (let [forms (candidate-forms)
            cached @!shape-generation]
        (if (identical? forms
                        (:seon.schema.shape/candidate-forms cached))
          (:seon.schema.shape/projection cached)
          (build-projection forms)))))

(defn- ensure-shape-generation-for! [projection]
    (when-not (identical? projection
                           (:seon.schema.shape/projection @!shape-generation))
      (reset! !shape-generation
              {:seon.schema.shape/projection projection
               :seon.schema.shape/candidate-forms
               nil
               :seon.schema.shape/validators {}
               :seon.schema.shape/explainers {}}))
    @!shape-generation)

(defn- cached-compiler-in! [projection cache-key compiler schema-key]
  (let [generation (ensure-shape-generation-for! projection)]
    (or (get (get generation cache-key) schema-key)
        (let [compiled (compiler projection schema-key)]
          (swap! !shape-generation
                 (fn [current]
                   (if (identical?
                         projection
                         (:seon.schema.shape/projection current))
                     (assoc-in current [cache-key schema-key] compiled)
                     current)))
          compiled))))

(defn- shape-rank [row]
  [(- (count (:seon.schema/required-attrs row)))
   (str (:seon.schema/key row))])

(defn- complete-present-attrs [value]
  (when (map? value)
    (->> (keys value) (filter keyword?) (sort-by str) vec)))

(defn- diagnostic-present-attrs [value]
  (when (map? value)
    (into []
          (comp (take shape-input-key-limit)
                (map first)
                (filter keyword?))
          value)))

(defn- diagnostic-schema-keys [projection attrs]
  (let [index (:seon.schema.projection/shape-index projection)]
    (loop [remaining-attrs attrs
           remaining-keys []
           visited 0
           selected #{}]
      (cond
        (>= visited shape-candidate-limit)
        selected

        (seq remaining-keys)
        (let [schema-key (first remaining-keys)]
          (*candidate-visit!* schema-key)
          (recur remaining-attrs
                 (next remaining-keys)
                 (inc visited)
                 (conj selected schema-key)))

        (seq remaining-attrs)
        (recur (next remaining-attrs)
               (get index (first remaining-attrs) [])
               visited
               selected)

        :else
        selected))))

(defn candidate-shapes-in
  "Bounded diagnostic schema window from explicit `projection`."
  {:malli/schema [:=> [:catn [::projection ::projection] [::value ::value]]
                  [:vector :map]]}
  [projection value]
  (if-let [attrs (seq (sort-by str (diagnostic-present-attrs value)))]
    (let [rows (:seon.schema.projection/shape-rows projection)]
      (->> (diagnostic-schema-keys projection attrs)
           (map rows)
           (sort-by shape-rank)
           vec))
    []))

(defn candidate-shapes
  "Bounded diagnostic schema window from the activated projection.

   Examines at most [[shape-input-key-limit]] map entries and
   [[shape-candidate-limit]] indexed schema references. Structural candidates
   outside either window may be omitted, so rows never assert validity.
   Candidate declarations do not affect the result after activation."
  {:malli/schema [:=> [:catn [::value ::value]] [:vector :map]]}
  [value]
  (let [projection (shape-projection)]
    (candidate-shapes-in projection value)))

(defn matching-shapes-in
  "All schemas in explicit `projection` that validate `value`."
  {:malli/schema [:=> [:catn [::projection ::projection] [::value ::value]]
                  [:vector :map]]}
  [projection value]
  (if-let [attrs (seq (complete-present-attrs value))]
    (let [index (:seon.schema.projection/shape-index projection)
          rows (:seon.schema.projection/shape-rows projection)
          present (set attrs)
          possible (into #{} (mapcat #(get index % [])) attrs)]
      (->> possible
           (map rows)
           (filter (fn [row]
                     (every? present (:seon.schema/required-attrs row))))
           (sort-by shape-rank)
           (filter (fn [{:seon.schema/keys [key]}]
                     ((cached-compiler-in!
                        projection :seon.schema.shape/validators
                        projection-validator key)
                      value)))
           vec))
    []))

(defn matching-shapes
  "All schemas that validate `value` in the activated projection.

   Matching is deliberately independent of the capped diagnostic result: all
   structurally possible schemas validate and survive in deterministic order."
  {:malli/schema [:=> [:catn [::value ::value]] [:vector :map]]}
  [value]
  (let [projection (shape-projection)]
    (matching-shapes-in projection value)))

(defn explain-shape-in
  "Explain `value` against `schema-key` in explicit `projection`."
  {:malli/schema [:=> [:catn [::projection ::projection]
                             [::registry-key ::registry-key]
                             [::value ::value]]
                  [:maybe ::explanation]]}
  [projection schema-key value]
  (when-not (contains? (:seon.schema.projection/shape-rows projection)
                       schema-key)
    (throw (ex-info (str "Unknown projected map schema " schema-key ".")
                    {:seon.schema/error :seon.schema/unknown-shape
                     :seon.schema/key schema-key
                     :seon.error/kind :core-bug})))
  ((cached-compiler-in!
     projection :seon.schema.shape/explainers projection-explainer schema-key)
   value))

(defn explain-shape
  "Explain `value` against one activated structural schema.

   Returns nil when valid and Malli explanation data when invalid. The schema
   key must name a row returned by [[candidate-shapes]]; an unknown key is a
   caller defect and throws before compiling against any other registry."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]
                             [::value ::value]]
                  [:maybe ::explanation]]}
  [schema-key value]
  (let [projection (shape-projection)]
    (explain-shape-in projection schema-key value)))

(defn candidate-validator
  "Compile a recursively resolved validator from current declarations."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [schema-key]
  (m/validator
   (m/deref-recursive
    schema-key
    {:registry (candidate-registry)
     ::m/sci-options
     (predicate-sci-options (core-predicate-functions))})))

(defn candidate-explainer
  "Compile a recursively resolved explainer from current declarations."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [schema-key]
  (m/explainer
   (m/deref-recursive
    schema-key
    {:registry (candidate-registry)
     ::m/sci-options
     (predicate-sci-options (core-predicate-functions))})))

(defn schemas-in-namespace
  "The `{keyword definition}` map of schemas registered under `ns-name`.

   `ns-name` is a string, e.g. \"seon.agent\"."
  {:malli/schema [:=> [:catn [::namespace-name ::namespace-name]] :map]}
  [ns-name]
  (into {}
        (filter (fn [[k _]] (= (namespace k) ns-name)))
        (candidate-forms)))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(defn clear-all!
  "Clear all registered schemas; testing only, use with caution."
  {:malli/schema [:=> [:cat] :map]}
  []
  (:seon.schema.state/candidate-forms
    (update-candidate-forms! (constantly {}))))

(comment
  ;; REPL exploration
  (register! ::test-schema [:string {:min 1}])
  (registered-schemas)
  (registered? ::test-schema)
  (schemas-in-namespace "seon.schema")
  (m/validate ::test-schema "hello")
  (m/validate ::test-schema "")          ; fails — min 1
  (require '[malli.generator :as mg])
  (mg/generate ::test-schema)
  nil)
