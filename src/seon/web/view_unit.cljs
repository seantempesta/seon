(ns seon.web.view-unit
  "Stable identity and pure active lifecycle for database-derived web units.

   The feed owner stores this namespace's plain state. A unit retains consumers,
   normalized database reads, and its last serialized complete element while
   active. Database values and producer functions are invocation inputs only."
  (:require
    [seon.db :as db]
    [seon.db.coordinate :as db.coordinate]
    [seon.schema :as schema]
    [seon.ui.html :as html]))

(schema/register! ::coordinate-value
  [:or :string :keyword :symbol :boolean :int :uuid])
(schema/register! ::coordinate
  [:map-of {:min 1} :qualified-keyword ::coordinate-value])
(schema/register! ::text :string)
(schema/register! ::token [:string {:min 1}])
(schema/register! ::consumer-id [:string {:min 1}])
(schema/register! ::consumers [:set ::consumer-id])
(schema/register! ::renderer-token [:string {:min 1}])
(schema/register! ::database-coordinate ::db.coordinate/coordinate)
(schema/register! ::serialized-element :string)
(schema/register! ::producer 'fn?)
(schema/register!
 ::unit
 [:map {:closed true}
  [::coordinate ::coordinate]
  [::database-coordinate ::database-coordinate]
  [::renderer-token ::renderer-token]
  [::consumers ::consumers]
  [::read-observations :seon.db/read-observations]
  [::serialized-element ::serialized-element]])
(schema/register! ::units [:map-of ::token ::unit])
(schema/register! ::broad-tokens [:set ::token])
(schema/register! ::tokens-by-attribute
  [:map-of :qualified-keyword [:set ::token]])
(schema/register!
 ::state
 [:map {:closed true}
  [::units ::units]
  [::broad-tokens ::broad-tokens]
  [::tokens-by-attribute ::tokens-by-attribute]])
(schema/register!
 ::candidate-tokens-request
 [:map {:closed true}
  [::state ::state]
  [::change :seon.db/candidate-change]])
(schema/register! ::candidate-tokens [:set ::token])
(schema/register! ::tokens [:set ::token])
(schema/register!
 ::advance-request
 [:map {:closed true}
  [::state ::state]
  [::tokens ::tokens]
  [::database-coordinate ::database-coordinate]])
(schema/register!
 ::attach-request
 [:map {:closed true}
  [::state ::state]
  [::coordinate ::coordinate]
  [::consumer-id ::consumer-id]
  [:seon.db/db :seon.db/db-val]
  [::database-coordinate ::database-coordinate]
  [::renderer-token ::renderer-token]
  [::producer ::producer]])
(schema/register!
 ::transition-request
 [:map {:closed true}
  [::state ::state]
  [::token ::token]
  [:seon.db/db :seon.db/db-val]
  [::database-coordinate ::database-coordinate]
  [::renderer-token ::renderer-token]
  [::producer ::producer]])
(schema/register!
 ::detach-request
 [:map {:closed true}
  [::state ::state]
  [::token ::token]
  [::consumer-id ::consumer-id]])
(schema/register! ::rendered? :boolean)
(schema/register! ::emitted? :boolean)
(schema/register! ::released? :boolean)
(schema/register!
 ::transition-response
 [:map {:closed true}
  [::state ::state]
  [::token ::token]
  [::rendered? ::rendered?]
  [::emitted? ::emitted?]
  [::released? ::released?]
  [::serialized-element {:optional true} ::serialized-element]])

(def empty-state
  "The plain, process-local value from which one feed owner starts."
  {::units {}
   ::broad-tokens #{}
   ::tokens-by-attribute {}})

(defn- canonical-keyword
  "A keyword's exact namespace and name, without printed-form ambiguity."
  [value]
  [(namespace value) (name value)])

(defn- canonical-value
  "A coordinate value tagged with its exact type and identity parts."
  [value]
  (cond
    (keyword? value) [::keyword (namespace value) (name value)]
    (symbol? value)  [::symbol (namespace value) (name value)]
    (string? value)  [::string value]
    (boolean? value) [::boolean value]
    (int? value)     [::integer value]
    (uuid? value)    [::uuid (str value)]))

(defn- canonical-coordinate
  "A coordinate's exact entries in one platform-independent EDN order."
  [coordinate]
  (->> coordinate
       (sort-by (comp canonical-keyword key))
       (mapv (fn [[k v]]
               [(canonical-keyword k) (canonical-value v)]))))

(defn encode-text
  "Encode UTF-8 text as an RFC 4648 base64url token."
  {:malli/schema [:=> [:catn [::text ::text]] ::token]}
  [value]
  (-> (js/Buffer.from value "utf8")
      (.toString "base64url")))

(defn coordinate-token
  "Stable opaque token derived from a canonical view coordinate."
  {:malli/schema [:=> [:catn [::coordinate ::coordinate]] ::token]}
  [coordinate]
  (encode-text (pr-str (canonical-coordinate coordinate))))

(defn- active-unit
  "Return one active unit's retained plain data, or nil."
  [state token]
  (get-in state [::units token]))

(defn- unit-candidate
  "Derive one unit's routing projection from its retained observations."
  [unit]
  (let [observations (::read-observations unit)
        candidates
        (map #(db/read-observation-candidate
               {:seon.db/read-observation %})
             observations)]
    (if (or (empty? observations)
            (some :seon.db/read-candidate-broad? candidates))
      {:seon.db/read-candidate-broad? true
       :seon.db/read-candidate-attributes #{}}
      {:seon.db/read-candidate-broad? false
       :seon.db/read-candidate-attributes
       (into #{} (mapcat :seon.db/read-candidate-attributes) candidates)})))

(defn- index-unit
  "Add one retained unit to the reverse routing buckets."
  [state token unit]
  (let [{broad? :seon.db/read-candidate-broad?
         attributes :seon.db/read-candidate-attributes}
        (unit-candidate unit)]
    (if broad?
      (update state ::broad-tokens conj token)
      (reduce (fn [indexed attribute]
                (update-in indexed
                           [::tokens-by-attribute attribute]
                           (fnil conj #{}) token))
              state
              attributes))))

(defn- remove-token
  "Remove one token from a set-valued map entry, dropping empty entries."
  [entries entry token]
  (let [remaining (disj (get entries entry #{}) token)]
    (if (empty? remaining)
      (dissoc entries entry)
      (assoc entries entry remaining))))

(defn- unindex-unit
  "Remove one retained unit from every reverse routing bucket it owns."
  [state token unit]
  (let [{broad? :seon.db/read-candidate-broad?
         attributes :seon.db/read-candidate-attributes}
        (unit-candidate unit)]
    (if broad?
      (update state ::broad-tokens disj token)
      (update state ::tokens-by-attribute
              (fn [entries]
                (reduce #(remove-token %1 %2 token)
                        entries
                        attributes))))))

(defn- replace-unit
  "Atomically replace one unit and its derived reverse routing entries."
  [state token unit]
  (let [without-prior
        (if-let [prior (active-unit state token)]
          (-> state
              (unindex-unit token prior)
              (update ::units dissoc token))
          state)]
    (-> without-prior
        (assoc-in [::units token] unit)
        (index-unit token unit))))

(defn candidate-tokens
  "Select active tokens from complete coalesced database routing evidence.

   Selection unions broad units with buckets named by both changed-attribute
   projections. The caller owns validation and fail-open behavior for an
   incomplete change; exact replay decides whether a candidate changed."
  {:malli/schema [:=> [:cat ::candidate-tokens-request] ::candidate-tokens]}
  [{state ::state change ::change}]
  (let [changed-attrs (:seon.db/changed-attrs change)
        attr-index (:seon.db/attr-index change)
        valid? (and (set? changed-attrs)
                    (every? qualified-keyword? changed-attrs)
                    (map? attr-index)
                    (every? qualified-keyword? (keys attr-index)))]
    (if-not valid?
      (into #{} (keys (::units state)))
      (reduce (fn [tokens attribute]
                (into tokens
                      (get-in state [::tokens-by-attribute attribute] #{})))
              (::broad-tokens state)
              (into changed-attrs (keys attr-index))))))

(defn advance-database-coordinate
  "Advance selected active units without touching their retained derivations."
  {:malli/schema [:=> [:cat ::advance-request] ::state]}
  [{state ::state tokens ::tokens database-coordinate ::database-coordinate}]
  (reduce (fn [advanced token]
            (if (active-unit advanced token)
              (assoc-in advanced
                        [::units token ::database-coordinate]
                        database-coordinate)
              advanced))
          state
          tokens))

(defn- derive-unit
  "Run one producer against exactly `dbv` and retain only replayable data."
  [{dbv :seon.db/db
    database-coordinate ::database-coordinate
    renderer-token ::renderer-token
    producer ::producer}]
  (let [capture (db/capture-reads
                 {:seon.db/db dbv
                  :seon.db/thunk #(producer dbv)})]
    {::database-coordinate database-coordinate
     ::renderer-token renderer-token
     ::read-observations
     (vec (distinct (:seon.db/read-observations capture)))
     ::serialized-element (html/->string (:seon.db/result capture))}))

(defn- observations-changed?
  "Replay every distinct observation before deciding whether the unit is dirty."
  [dbv observations]
  (let [changed (mapv #(db/read-observation-changed?
                       {:seon.db/db dbv
                        :seon.db/read-observation %})
                      (distinct observations))]
    (or (empty? observations) (boolean (some true? changed)))))

(defn transition-unit
  "Advance one active unit to an explicitly supplied immutable database value.

   Every distinct captured read is replayed. Equal results avoid the producer;
   equal serialized output avoids an emitted element. The new database value
   and producer are never retained. An inactive token performs no work."
  {:malli/schema [:=> [:cat ::transition-request] ::transition-response]}
  [{state ::state
    token ::token
    dbv :seon.db/db
    database-coordinate ::database-coordinate
    renderer-token ::renderer-token
    producer ::producer}]
  (if-let [unit (active-unit state token)]
    (let [renderer-changed? (not= renderer-token (::renderer-token unit))
          coordinate-changed? (not= database-coordinate
                                    (::database-coordinate unit))
          dirty? (or renderer-changed?
                     (and coordinate-changed?
                          (observations-changed?
                           dbv (::read-observations unit))))]
      (if dirty?
        (let [derived (derive-unit
                       {:seon.db/db dbv
                        ::database-coordinate database-coordinate
                        ::renderer-token renderer-token
                        ::producer producer})
              serialized (::serialized-element derived)
              emitted? (not= serialized (::serialized-element unit))
              next-unit (merge unit derived)]
          (cond-> {::state (replace-unit state token next-unit)
                   ::token token
                   ::rendered? true
                   ::emitted? emitted?
                   ::released? false}
            emitted? (assoc ::serialized-element serialized)))
        {::state (if coordinate-changed?
                   (assoc-in state [::units token ::database-coordinate]
                             database-coordinate)
                   state)
         ::token token
         ::rendered? false
         ::emitted? false
         ::released? false}))
    {::state state
     ::token token
     ::rendered? false
     ::emitted? false
     ::released? false}))

(defn attach-consumer
  "Attach one consumer and return the unit's current complete serialized element.

   The first consumer crosses the producer boundary. A later consumer at the
   same database coordinate reuses the retained element. A later coordinate or
   renderer token advances the existing unit through `transition-unit` first."
  {:malli/schema [:=> [:cat ::attach-request] ::transition-response]}
  [{state ::state
    coordinate ::coordinate
    consumer-id ::consumer-id
    dbv :seon.db/db
    database-coordinate ::database-coordinate
    renderer-token ::renderer-token
    producer ::producer}]
  (let [token (coordinate-token coordinate)]
    (if (active-unit state token)
      (let [transition (transition-unit
                        {::state state
                         ::token token
                         :seon.db/db dbv
                         ::database-coordinate database-coordinate
                         ::renderer-token renderer-token
                         ::producer producer})
            attached (update-in (::state transition)
                                [::units token ::consumers] conj consumer-id)
            current (active-unit attached token)]
        (assoc transition
               ::state attached
               ::serialized-element (::serialized-element current)))
      (let [derived (derive-unit
                     {:seon.db/db dbv
                      ::database-coordinate database-coordinate
                      ::renderer-token renderer-token
                      ::producer producer})
            unit (assoc derived
                        ::coordinate coordinate
                        ::consumers #{consumer-id})]
        {::state (replace-unit state token unit)
         ::token token
         ::rendered? true
         ::emitted? true
         ::released? false
         ::serialized-element (::serialized-element unit)}))))

(defn detach-consumer
  "Detach one consumer; final close releases the unit's entire retained state."
  {:malli/schema [:=> [:cat ::detach-request] ::transition-response]}
  [{state ::state token ::token consumer-id ::consumer-id}]
  (if-let [unit (active-unit state token)]
    (let [remaining (disj (::consumers unit) consumer-id)
          released? (empty? remaining)]
      {::state (if released?
                 (-> state
                     (unindex-unit token unit)
                     (update ::units dissoc token))
                 (assoc-in state [::units token ::consumers] remaining))
       ::token token
       ::rendered? false
       ::emitted? false
       ::released? released?})
    {::state state
     ::token token
     ::rendered? false
     ::emitted? false
     ::released? false}))
