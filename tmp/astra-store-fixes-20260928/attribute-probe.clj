;; Run as one form in a disposable scratch JVM namespace; no default Vars are changed.
(do
(require '[seon.db :as db] '[seon.cluster.boot :as boot] '[seon.schema.datahike :as schema.datahike])
(require '[malli.core :as m] '[malli.registry :as mr] '[seon.error.refusal])
(def registration-form @#'seon.schema.datahike/registration-form)
(def compiled-storage @#'seon.schema.datahike/compiled-storage)
(def value-schema @#'seon.schema.datahike/value-schema)
(defn attribute-declaration-after
  "Derive a native declaration from the supplied generation's retained root,
  or the refusal naming why the attribute has none: no registered schema, a
  value with no native Datahike type, a secondary-only attribute that is not
  float-valued. The refusal is a value: `declared-storable?` asks this for
  every optional member of every entity root (73,492 refusals in 1.8 M calls
  on default, 2026-09-28), which as thrown exceptions was control flow."
  {:malli/schema [:=> [:cat :map :qualified-keyword]
                  [:or :map :seon.schema.datahike/unstorable-attribute-error]]}
  [projection attribute]
  (let [root (mr/schema (:seon.schema.projection/registry projection) attribute)
        refused (fn [message data]
                  (seon.error.refusal/diagnostic
                   (merge data {:seon.error/at (java.util.Date.) :seon.error/layer :seon.schema.datahike/storage
                                :seon.error/operation 'seon.schema.datahike/attribute-declaration
                                :seon.error/message message
                                :seon.schema.datahike/attr attribute :seon.schema.datahike/unstorable-attribute attribute})))]
   (if-not (m/schema? root)
    (refused (str "The attribute has no registered schema. Run "
                  (registration-form attribute :string)
                  " with the intended concrete type before transacting it.")
             {:seon.schema.datahike/attribute-absent attribute})
   (let [{:seon.schema.datahike/keys [value-type cardinality properties tuple-types refusal]}
        (compiled-storage root)
        value-type (if (= :seon.db/ref attribute) :db.type/ref value-type)
        secondary? (:db.secondary/only properties)]
    (cond
     (or refusal (nil? value-type))
      (let [[reason form] (or refusal [:seon.schema.datahike/value-type-unavailable (m/form (value-schema root))])
            message (case reason
                      :seon.schema.datahike/literal-not-storable "Only scalar Malli literals are storable. Register a literal whose value has a native Datahike type."
                      :seon.schema.datahike/enum-not-storable "Only keyword Malli enums are storable. Register keyword members."
                      :seon.schema.datahike/nilable-attribute "Stored attributes cannot use `:maybe`. Register the non-nil base shape, then omit an absent key or mark its entity-map entry `{:optional true}`."
                      "A stored attribute needs a storable Datahike value type, and this Malli form has none. Register a concrete storable shape.")]
        (refused message {:seon.schema.datahike/form form
                          reason (if (= reason :seon.schema.datahike/nilable-attribute) :seon.schema.datahike/form true)}))
     (and secondary? (not (#{:db.type/float :db.type/double} value-type)))
     (refused "A secondary-only attribute must contain floats."
              {:seon.schema.datahike/invalid-secondary-attribute attribute})
     :else
    (cond-> {:db/ident attribute
             :db/valueType (if secondary? :db.type/tuple value-type)
             :db/cardinality (if secondary? :db.cardinality/one cardinality)}
      tuple-types (assoc :db/tupleTypes tuple-types)
      secondary? (assoc :db.secondary/only true)
      (:seon.db/identity properties) (assoc :db/unique :db.unique/identity)
      (:seon.db/unique properties) (assoc :db/unique :db.unique/value)
      (:seon.db/index properties) (assoc :db/index true)
      (:seon.db/component properties) (assoc :db/isComponent true)
      (:seon.db/no-history? properties) (assoc :db/noHistory true)))))))


(let [projection (db/carried-projection (db/db (boot/connection "default"))) attributes (vec (filter qualified-keyword? (keys (:seon.schema.projection/forms projection)))) results (mapv (fn [f] (let [start (System/nanoTime) answers (mapv #(f projection %) attributes)] {:ms (/ (- (System/nanoTime) start) 1e6) :answers answers})) [schema.datahike/storable-attribute-in? #(not (:seon.error/at (attribute-declaration-after %1 %2)))])] (assert (= (:answers (first results)) (:answers (second results)))) {:attributes (count attributes) :storable (count (filter true? (:answers (first results)))) :ms (mapv :ms results)}))
