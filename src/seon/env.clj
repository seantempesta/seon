(ns seon.env
  "The environment: everything that makes ONE cluster's computation
  different from another's, as one explicit value.

  Boot is the only constructor (PRD ruling 4 — 0->1, refuse up front,
  never hand out a partial environment). Running code RECEIVES the
  environment; it never builds one. It travels three media under one
  key, `:seon.env/environment`:

  - agent code — `assoc`'d onto the cluster's sci ctx, so every fork and
    every closure carries it across any thread by construction (never
    through `sci/init` options: `opts/init` silently drops unknown
    option keys);
  - thread crossings — data on a flow submission and in proc `:args`;
  - the web — merged into the request map.

  The container is a host record absent from sci `:classes`, so agent
  code cannot traverse it. Its contents reach agent code only by
  declaration.

  The declared members, their dependency order, the layer a refusal
  blames, and which members the production constructor requires are all
  read from the one `:seon.env/environment` schema — this namespace
  keeps no second list."
  (:require [clojure.test.check.generators :as gen]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(set! *warn-on-reflection* true)

;;; ---------------------------------------------------------------------------
;;; The container
;;; ---------------------------------------------------------------------------

(defrecord Environment [])

(defn environment?
  "True for an environment value built by this namespace."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? Environment value))

(schema/register-core-predicate! 'seon.env/environment? environment?)

(defmethod print-method Environment
  [environment ^java.io.Writer writer]
  ;; A connection, a projection, and a work launcher each print as a wall of
  ;; bytes. Diagnosis wants the cluster and which members stand, so that is
  ;; what an environment shows.
  (.write writer
          (str "#seon.env/environment{:seon.boot/cluster-name "
               (pr-str (:seon.boot/cluster-name environment))
               ", :seon.env/members "
               (pr-str (vec (sort (remove #{:seon.boot/cluster-name}
                                          (keys environment)))))
               "}")))

(def environment-generator
  (gen/fmap (fn [cluster-name]
              (map->Environment {:seon.boot/cluster-name cluster-name}))
            (gen/not-empty gen/string-alphanumeric)))

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The declared members, read from the one schema
;;; ---------------------------------------------------------------------------

(defn- map-entries
  "The ordered `:map` entries of the environment schema definition."
  [definition]
  (cond
    (and (vector? definition) (= :map (first definition)))
    (filter vector? (rest definition))

    (vector? definition)
    (some map-entries (rest definition))))

(def ^:private members-of
  ;; Keyed by the complete declaration value, never by a "current" slot: a
  ;; changed declaration is a different key and derives again.
  (memoize
   (fn [definition]
     (into []
           (map (fn [[member properties]]
                  (let [properties (if (map? properties) properties {})]
                    {:seon.env/member member
                     :seon.env/layer (:seon.env/layer properties)
                     :seon.env/optional? (true? (:optional properties))
                     :seon.env/boot-required?
                     (= :required (:seon.env/boot properties))})))
           (map-entries definition)))))

(defn members
  "The declared environment members, in the dependency order boot builds.

  Derived from the registered `:seon.env/environment` schema, so no
  second list can drift from the declaration."
  {:malli/schema
   [:=> [:cat]
    [:vector [:map
              [:seon.env/member :qualified-keyword]
              [:seon.env/layer :seon.env/layer]
              [:seon.env/optional? :boolean]
              [:seon.env/boot-required? :boolean]]]]}
  []
  (let [definition (schema/schema-definition :seon.env/environment)]
    (when-not definition
      (throw
       (ex-info
        "The :seon.env/environment schema is not registered."
        {:seon.error/kind ::schema-absent})))
    (members-of definition)))

;;; ---------------------------------------------------------------------------
;;; Construction — refuse up front, in dependency order
;;; ---------------------------------------------------------------------------

(defn- absent-member-error
  [{member :seon.env/member layer :seon.env/layer} supplied]
  {:seon.error/kind ::incomplete-environment
   :seon.error/message
   (str "The " (name (or layer :unknown)) " layer did not supply "
        member "; an environment is never partially handed out.")
   :seon.error/data {:seon.env/layer layer
                     :seon.env/member member
                     :seon.env/supplied (vec (sort (keys supplied)))}})

(defn- construct
  [supplied boot?]
  (if-not (map? supplied)
    {:seon.error/kind ::invalid-member
     :seon.error/message "An environment is constructed from a map of members."
     :seon.error/data {:seon.env/supplied (str (type supplied))}}
    (or
     ;; Dependency order is the schema's entry order, so the FIRST absent
     ;; member names the earliest layer that did not stand. Member SHAPES
     ;; are validated by the one declared `:seon.env/environment` contract
     ;; wherever an environment is accepted; construction does not compile a
     ;; second validator, and it cannot: the member predicates resolve
     ;; through a cluster's corpus projection, which the store layer has not
     ;; necessarily reached when the earliest environment is built.
     (some (fn [{member :seon.env/member
                 optional? :seon.env/optional?
                 boot-required? :seon.env/boot-required?
                 :as row}]
             (when (and (not (contains? supplied member))
                        (or (not optional?) (and boot? boot-required?)))
               (absent-member-error row supplied)))
           (members))
     (map->Environment supplied))))

(defn environment
  "Build one environment from its supplied members, or refuse as a value.

  Subset construction is ordinary: a caller with a store and facts but no
  graphs and no web simply omits those members, and every member it DOES
  supply is validated rigorously. Extra keys are carried and ignored, so
  a member declared later is validated the day it is declared."
  {:malli/schema
   [:=> [:cat :map] [:or :seon.env/environment :seon.error/value]]}
  [supplied]
  (construct supplied false))

(defn boot-environment
  "Build the complete production environment, or refuse naming the layer.

  This is boot's 0->1 constructor: every member the schema marks
  `:seon.env/boot :required` must stand, and the first absent or invalid
  one refuses with a flat error naming its layer. A later-layer failure
  therefore never yields a partial environment, and the prepl opened at
  second zero keeps answering."
  {:malli/schema
   [:=> [:cat :map] [:or :seon.env/environment :seon.error/value]]}
  [supplied]
  (construct supplied true))

(defn scope
  "Narrow an existing environment to an agent, run, or form.

  Scoping is not construction (PRD ruling 1 — running code receives,
  never builds): only members the schema places in the `:turn` layer may
  be supplied, so no consumer can quietly replace a connection, a
  projection, or a work launcher on its way across a boundary."
  {:malli/schema
   [:=> [:cat :seon.env/environment :map]
    [:or :seon.env/environment :seon.error/value]]}
  [carried supplied]
  (let [turn-members
        (into #{}
              (comp (filter (comp #{:turn} :seon.env/layer))
                    (map :seon.env/member))
              (members))
        outside (vec (sort (remove turn-members (keys supplied))))]
    (if (seq outside)
      {:seon.error/kind ::unscopable-member
       :seon.error/message
       "Only turn-layer members may be scoped onto an existing environment."
       :seon.error/data {:seon.env/member outside}}
      (merge carried supplied))))

;;; ---------------------------------------------------------------------------
;;; Carriage
;;; ---------------------------------------------------------------------------

(def carrier
  "The ONE key the environment reads under on a submission, in proc
  `:args`, on a web request map, and on a sci ctx."
  :seon.env/environment)

(defn of
  "The environment carried by a ctx, submission, proc args, or request."
  {:malli/schema
   [:=> [:cat [:maybe :map]] [:maybe :seon.env/environment]]}
  [carrier-map]
  (let [value (get carrier-map carrier)]
    (when (environment? value)
      value)))

(defn require-environment
  "Return the carried environment or a flat error naming the boundary."
  {:malli/schema
   [:=> [:cat [:maybe :map] :qualified-keyword]
    [:or :seon.env/environment :seon.error/value]]}
  [carrier-map boundary]
  (or (of carrier-map)
      {:seon.error/kind ::absent-environment
       :seon.error/message
       (str "Crossing " boundary " requires its cluster's environment under "
            carrier ".")
       :seon.error/data
       {:seon.env/boundary boundary
        :seon.env/supplied (vec (sort (keys carrier-map)))}}))

(defn refuse-incomplete-environment!
  "Return a constructed environment, or throw its flat refusal.

  Boot's layers already fail by throwing with the degraded instance in
  the ex-data, so the 0->1 constructor's flat error becomes that throw's
  ex-data here rather than a second failure protocol."
  {:malli/schema
   [:=> [:cat [:or :seon.env/environment :seon.error/value]]
    :seon.env/environment]}
  [constructed]
  (if (environment? constructed)
    constructed
    (throw (ex-info (:seon.error/message constructed) constructed))))

(defn refuse-absent-environment!
  "Refuse a construction-time crossing that named no environment.

  A missing environment is invisible on `:compute` and fatal on `:io`,
  so it is refused where the crossing is BUILT rather than where it
  runs — the same construction-time refusal `var-process` already makes
  for a non-Var step and a `:mixed` workload."
  {:malli/schema
   [:=> [:cat [:maybe :map] :qualified-keyword] :seon.env/environment]}
  [carrier-map boundary]
  (let [result (require-environment carrier-map boundary)]
    (if (environment? result)
      result
      (throw (ex-info (:seon.error/message result) result)))))

(defn carry
  "Merge the environment into a map handed across a thread boundary."
  {:malli/schema [:=> [:cat :map [:maybe :seon.env/environment]] :map]}
  [target carried]
  (cond-> target
    (environment? carried)
    (assoc carrier carried)))
