(ns seon.db.browser
  "Bounded, coordinate-bound index projections for the operator database browser.

   The web layer owns URLs and Hiccup. This namespace owns pure database
   paging. Every continuation is an opaque Transit/base64url value bound to
   the complete immutable database coordinate and the exact projection,
   index, prefix, and direction that produced it."
  (:require
    [clojure.string :as str]
    [cognitect.transit :as transit]
    [seon.db :as db]
    [seon.schema :as schema]))

(schema/register! ::system? :boolean)
(schema/register! ::attribute :qualified-keyword)
(schema/register! ::limit [:int {:min 1 :max 200}])
(schema/register! ::entity :int)
(schema/register! ::value :any)
(schema/register! ::transaction :int)
(schema/register! ::added? :boolean)
(schema/register! ::reference? :boolean)
(schema/register! ::target-entity :int)
(schema/register! ::projection [:enum :current :history])
(schema/register! ::direction [:enum :forward :reverse])
(schema/register! ::cursor-token :string)
(schema/register! ::prefix [:vector {:max 4} :any])

(schema/register!
  ::encoded-value
  [:or
   [:tuple [:= :seon.db.browser.cursor.value/string] :string]
   [:tuple [:= :seon.db.browser.cursor.value/keyword] :string]
   [:tuple [:= :seon.db.browser.cursor.value/symbol] :string]
   [:tuple [:= :seon.db.browser.cursor.value/boolean] :boolean]
   [:tuple [:= :seon.db.browser.cursor.value/integer] :int]
   [:tuple [:= :seon.db.browser.cursor.value/double] :double]
   [:tuple [:= :seon.db.browser.cursor.value/uuid] :string]
   [:tuple [:= :seon.db.browser.cursor.value/instant] :int]
   [:tuple [:= :seon.db.browser.cursor.value/bigint] :string]
   [:tuple [:= :seon.db.browser.cursor.value/bytes]
    [:vector [:int {:min 0 :max 255}]]]])
(schema/register! ::encoded-components [:vector ::encoded-value])
(schema/register!
  ::encoded-datom
  [:map {:closed true}
   [:seon.db/e :int]
   [:seon.db/a :qualified-keyword]
   [:seon.db/v ::encoded-value]
   [:seon.db/tx :int]
   [:seon.db/added? :boolean]])
(schema/register!
  ::cursor-payload
  [:map {:closed true}
   [:seon.db.browser.cursor/version [:= 1]]
   [:seon.db.browser.cursor/database-coordinate
    :seon.db.coordinate/coordinate]
   [:seon.db.browser.cursor/projection ::projection]
   [:seon.db.browser.cursor/index :seon.db/index]
   [:seon.db.browser.cursor/prefix ::encoded-components]
   [:seon.db.browser.cursor/direction ::direction]
   [:seon.db.browser.cursor/last ::encoded-datom]])
(schema/register!
  ::cursor-error-kind
  [:enum
   :seon.db.browser.cursor.error/unsupported-value
   :seon.db.browser.cursor.error/malformed-token
   :seon.db.browser.cursor.error/invalid-payload
   :seon.db.browser.cursor.error/request-mismatch])
(schema/register!
  ::cursor-error
  [:map {:closed true}
   [::error ::cursor-error-kind]
   [::message :string]])
(schema/register! ::decode-result [:or ::cursor-payload ::cursor-error])

(schema/register!
  ::row
  [:map
   [::entity ::entity]
   [::attribute ::attribute]
   [::value ::value]
   [::transaction ::transaction]
   [::added? ::added?]])
(schema/register! ::rows [:vector ::row])
(schema/register! ::more? :boolean)
(schema/register!
  ::page
  [:map {:closed true}
   [::rows ::rows]
   [::more? ::more?]
   [::next-cursor {:optional true} ::cursor-token]])
(schema/register! ::page-result [:or ::page ::cursor-error])
(schema/register!
  ::fact
  [:map {:closed true}
   [::entity ::entity]
   [::attribute ::attribute]
   [::value ::value]
   [::transaction ::transaction]
   [::added? ::added?]
   [::reference? ::reference?]])
(schema/register! ::facts [:vector ::fact])
(schema/register!
  ::fact-page
  [:map {:closed true}
   [::facts ::facts]
   [::more? ::more?]
   [::next-cursor {:optional true} ::cursor-token]])
(schema/register!
  ::projection-error-kind
  [:enum
   :seon.db.browser.projection.error/unknown-attribute
   :seon.db.browser.projection.error/not-reference])
(schema/register!
  ::projection-error
  [:map {:closed true}
   [::error ::projection-error-kind]
   [::message :string]])
(schema/register!
  ::fact-page-result
  [:or ::fact-page ::cursor-error ::projection-error])
(schema/register! ::attribute-groups
                  [:map-of :keyword [:vector :qualified-keyword]])
(schema/register!
  ::page-request
  [:map {:closed true}
   [:seon.db/db :seon.db/db-val]
   [::database-coordinate :seon.db.coordinate/coordinate]
   [::projection ::projection]
   [::index :seon.db/index]
   [::prefix ::prefix]
   [::direction ::direction]
   [::limit ::limit]
   [::cursor {:optional true} ::cursor-token]])
(schema/register!
  ::attribute-page-request
  [:map
   [:seon.db/db :seon.db/db-val]
   [::database-coordinate :seon.db.coordinate/coordinate]
   [::attribute ::attribute]
   [::limit ::limit]
   [::cursor {:optional true} ::cursor-token]])
(schema/register!
  ::entity-page-request
  [:map {:closed true}
   [:seon.db/db :seon.db/db-val]
   [::database-coordinate :seon.db.coordinate/coordinate]
   [::entity ::entity]
   [::limit ::limit]
   [::cursor {:optional true} ::cursor-token]])
(schema/register!
  ::reverse-reference-page-request
  [:map {:closed true}
   [:seon.db/db :seon.db/db-val]
   [::database-coordinate :seon.db.coordinate/coordinate]
   [::attribute ::attribute]
   [::target-entity ::target-entity]
   [::limit ::limit]
   [::cursor {:optional true} ::cursor-token]])

(def ^:private cursor-writer (transit/writer :json))
(def ^:private cursor-reader (transit/reader :json))
(def ^:private max-cursor-characters 8192)

(defn- cursor-error [kind message]
  {::error kind ::message message})

(defn system-attribute?
  "Whether an attribute belongs to Datahike or Seon's framework namespaces."
  {:malli/schema [:=> [:catn [::attribute ::attribute]] :boolean]}
  [attribute]
  (let [n (namespace attribute)]
    (or (= "db" n)
        (str/starts-with? n "db.")
        (= "dh.ref" n)
        (str/starts-with? n "seon."))))

(defn attribute-groups
  "Installed attributes grouped by their namespace.

   With `system?` false, framework attributes are omitted; the database read
   is proportional to installed schema, not accumulated entities or history."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [::system? ::system?]]
                  ::attribute-groups]}
  [dbv system?]
  (->> (keys (db/installed-schema dbv))
       (filter qualified-keyword?)
       (remove #(and (not system?) (system-attribute? %)))
       (group-by (comp keyword namespace))
       (reduce-kv (fn [groups attribute-ns attributes]
                    (assoc groups attribute-ns (vec (sort-by str attributes))))
                  {})))

(defn attribute-schema
  "The installed Datahike schema facts for one attribute, or nil."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [::attribute ::attribute]]
                  [:maybe :map]]}
  [dbv attribute]
  (get (db/installed-schema dbv) attribute))

(defn- encode-value [value]
  (cond
    (string? value)
    [:seon.db.browser.cursor.value/string value]

    (keyword? value)
    [:seon.db.browser.cursor.value/keyword (str value)]

    (symbol? value)
    [:seon.db.browser.cursor.value/symbol (str value)]

    (boolean? value)
    [:seon.db.browser.cursor.value/boolean value]

    (= js/BigInt (type value))
    [:seon.db.browser.cursor.value/bigint (str value)]

    (int? value)
    [:seon.db.browser.cursor.value/integer value]

    (and (number? value) (js/Number.isFinite value))
    [:seon.db.browser.cursor.value/double value]

    (uuid? value)
    [:seon.db.browser.cursor.value/uuid (str value)]

    (inst? value)
    [:seon.db.browser.cursor.value/instant (inst-ms value)]

    (instance? js/Uint8Array value)
    [:seon.db.browser.cursor.value/bytes (vec (array-seq value))]

    :else
    (cursor-error
      :seon.db.browser.cursor.error/unsupported-value
      "The index boundary contains a value outside the cursor scalar vocabulary.")))

(defn- decode-value [[tag payload]]
  (try
    (case tag
      :seon.db.browser.cursor.value/string payload
      :seon.db.browser.cursor.value/keyword (keyword (subs payload 1))
      :seon.db.browser.cursor.value/symbol (symbol payload)
      :seon.db.browser.cursor.value/boolean payload
      :seon.db.browser.cursor.value/integer payload
      :seon.db.browser.cursor.value/double payload
      :seon.db.browser.cursor.value/uuid (uuid payload)
      :seon.db.browser.cursor.value/instant (js/Date. payload)
      :seon.db.browser.cursor.value/bigint (js/BigInt payload)
      :seon.db.browser.cursor.value/bytes
      (js/Uint8Array. (clj->js payload)))
    (catch :default _
      (cursor-error
        :seon.db.browser.cursor.error/invalid-payload
        "The cursor contains an invalid encoded index value."))))

(defn- encode-values [values]
  (reduce
    (fn [encoded value]
      (if (::error encoded)
        (reduced encoded)
        (let [value' (encode-value value)]
          (if (::error value')
            (reduced value')
            (conj encoded value')))))
    []
    values))

(defn- base64url-encode [value]
  (-> (.toString (js/Buffer.from value "utf8") "base64")
      (str/replace "+" "-")
      (str/replace "/" "_")
      (str/replace #"=+$" "")))

(defn- base64url-decode [value]
  (let [padding (case (mod (count value) 4) 0 "" 2 "==" 3 "=" nil)]
    (when padding
      (.toString (js/Buffer.from (str value padding) "base64") "utf8"))))

(defn- encode-payload [payload]
  (try
    (let [token (base64url-encode (transit/write cursor-writer payload))]
      (if (<= (count token) max-cursor-characters)
        token
        (cursor-error
          :seon.db.browser.cursor.error/invalid-payload
          "The cursor payload exceeds the bounded token size.")))
    (catch :default _
      (cursor-error
        :seon.db.browser.cursor.error/invalid-payload
        "The cursor payload could not be encoded."))))

(defn decode-cursor
  "Decode and validate one bounded opaque cursor without retaining DB values."
  {:malli/schema [:=> [:catn [::cursor-token ::cursor-token]] ::decode-result]}
  [cursor-token]
  (if-not (and (<= 1 (count cursor-token) max-cursor-characters)
               (re-matches #"[A-Za-z0-9_-]+" cursor-token))
    (cursor-error
      :seon.db.browser.cursor.error/malformed-token
      "The cursor is not canonical base64url data.")
    (try
      (if-let [encoded (base64url-decode cursor-token)]
        (if-not (= cursor-token (base64url-encode encoded))
          (cursor-error
            :seon.db.browser.cursor.error/malformed-token
            "The cursor is not canonical base64url data.")
          (let [payload (transit/read cursor-reader encoded)]
            (if (schema/valid-candidate-value? ::cursor-payload payload)
              payload
              (cursor-error
                :seon.db.browser.cursor.error/invalid-payload
                "The cursor payload does not satisfy the closed cursor schema."))))
        (cursor-error
          :seon.db.browser.cursor.error/malformed-token
          "The cursor has invalid base64url padding."))
      (catch :default _
        (cursor-error
          :seon.db.browser.cursor.error/malformed-token
          "The cursor is not readable Transit data.")))))

(defn- ordered-components [index datom]
  (case index
    :eavt [(::db/e datom) (::db/a datom) (::db/v datom) (::db/tx datom)]
    :aevt [(::db/a datom) (::db/e datom) (::db/v datom) (::db/tx datom)]
    :avet [(::db/a datom) (::db/v datom) (::db/e datom) (::db/tx datom)]))

(defn- encode-datom [datom]
  (let [value (encode-value (::db/v datom))]
    (if (::error value)
      value
      {:seon.db/e (::db/e datom)
       :seon.db/a (::db/a datom)
       :seon.db/v value
       :seon.db/tx (::db/tx datom)
       :seon.db/added? (::db/added? datom)})))

(defn- cursor-components [index encoded-datom]
  (let [value (decode-value (:seon.db/v encoded-datom))]
    (if (::error value)
      value
      (let [datom {:seon.db/e (:seon.db/e encoded-datom)
                   :seon.db/a (:seon.db/a encoded-datom)
                   :seon.db/v value
                   :seon.db/tx (:seon.db/tx encoded-datom)
                   :seon.db/added? (:seon.db/added? encoded-datom)}]
        (if (= encoded-datom (encode-datom datom))
          (ordered-components index datom)
          (cursor-error
            :seon.db.browser.cursor.error/invalid-payload
            "The cursor boundary is not canonically encoded."))))))

(defn- validated-cursor-boundary [index encoded-prefix encoded-datom]
  (let [boundary (cursor-components index encoded-datom)]
    (if (::error boundary)
      boundary
      (let [encoded-boundary (encode-values boundary)]
        (if (= encoded-prefix
               (subvec encoded-boundary 0 (count encoded-prefix)))
          boundary
          (cursor-error
            :seon.db.browser.cursor.error/request-mismatch
            "The cursor boundary does not belong to the sealed index prefix."))))))

(defn- same-datom? [encoded datom]
  (= encoded (encode-datom datom)))

(defn- after-cursor [datoms encoded-last]
  (if-let [cursor-position
           (first (keep-indexed #(when (same-datom? encoded-last %2) %1)
                                datoms))]
    (drop (inc cursor-position) datoms)
    datoms))

(defn- row [datom]
  {::entity (::db/e datom)
   ::attribute (::db/a datom)
   ::value (::db/v datom)
   ::transaction (::db/tx datom)
   ::added? (::db/added? datom)})

(defn- matching-prefix? [index encoded-prefix datom]
  (let [encoded-components (encode-values (ordered-components index datom))]
    (and (not (::error encoded-components))
         (= encoded-prefix
            (subvec encoded-components 0 (count encoded-prefix))))))

(defn- cursor-request-matches?
  [payload coordinate projection index encoded-prefix direction]
  (= [coordinate projection index encoded-prefix direction]
     [(:seon.db.browser.cursor/database-coordinate payload)
      (:seon.db.browser.cursor/projection payload)
      (:seon.db.browser.cursor/index payload)
      (:seon.db.browser.cursor/prefix payload)
      (:seon.db.browser.cursor/direction payload)]))

(defn- next-token
  [coordinate projection index encoded-prefix direction datom]
  (let [last-datom (encode-datom datom)]
    (if (::error last-datom)
      last-datom
      (encode-payload
        {:seon.db.browser.cursor/version 1
         :seon.db.browser.cursor/database-coordinate coordinate
         :seon.db.browser.cursor/projection projection
         :seon.db.browser.cursor/index index
         :seon.db.browser.cursor/prefix encoded-prefix
         :seon.db.browser.cursor/direction direction
         :seon.db.browser.cursor/last last-datom}))))

(defn index-page
  "Read one bounded current or history page from EAVT, AEVT, or AVET.

   The caller supplies a DB already resolved to `database-coordinate`; an
   as-of DB cannot rederive its containing commit identity. A continuation is
   rejected before any index read unless every request fact matches the facts
   sealed into the token."
  {:malli/schema [:=> [:cat ::page-request] ::page-result]}
  [{dbv :seon.db/db
    coordinate ::database-coordinate
    projection ::projection
    index ::index
    prefix ::prefix
    direction ::direction
    limit ::limit
    cursor-token ::cursor}]
  (let [encoded-prefix (encode-values prefix)
        payload (when cursor-token (decode-cursor cursor-token))]
    (cond
      (> (count prefix) 4)
      (cursor-error
        :seon.db.browser.cursor.error/request-mismatch
        "An index prefix may contain at most four comparator components.")

      (::error encoded-prefix)
      encoded-prefix

      (::error payload)
      payload

      (and payload
           (not (cursor-request-matches?
                  payload coordinate projection index encoded-prefix direction)))
      (cursor-error
        :seon.db.browser.cursor.error/request-mismatch
        "The cursor belongs to a different coordinate or index projection.")

      :else
      (let [last-datom (:seon.db.browser.cursor/last payload)
            boundary (if last-datom
                       (validated-cursor-boundary index encoded-prefix last-datom)
                       prefix)]
        (if (::error boundary)
          boundary
          (let [projection-db (if (= :history projection)
                                (db/history dbv)
                                dbv)
                request {::db/db projection-db
                         ::db/index index
                         ::db/components boundary
                         ::db/index-limit (+ limit (if last-datom 3 1))}
                datoms (if (= :forward direction)
                         (db/index-datoms (assoc request ::db/seek? (boolean last-datom)))
                         (db/rseek-datoms
                           (assoc request ::db/index-prefix? (not last-datom))))
                bounded (take-while #(matching-prefix? index encoded-prefix %) datoms)
                window (vec (take (inc limit)
                                  (after-cursor bounded last-datom)))
                more? (> (count window) limit)
                visible (if more? (subvec window 0 limit) window)
                token (when (and more? (seq visible))
                        (next-token coordinate projection index encoded-prefix
                                    direction (peek visible)))]
            (if (::error token)
              token
              (cond-> {::rows (mapv row visible)
                       ::more? more?}
                token (assoc ::next-cursor token)))))))))

(defn attribute-page
  "Read one bounded forward/current AEVT page for an installed attribute.

   This narrow convenience delegates to [[index-page]]; the opaque cursor and
   complete database coordinate are the same contract used by every index."
  {:malli/schema [:=> [:cat ::attribute-page-request] ::page-result]}
  [{dbv :seon.db/db
    coordinate ::database-coordinate
    attribute ::attribute
    limit ::limit
    cursor ::cursor}]
  (index-page
    (cond-> {:seon.db/db dbv
             ::database-coordinate coordinate
             ::projection :current
             ::index :aevt
             ::prefix [attribute]
             ::direction :forward
             ::limit limit}
      cursor (assoc ::cursor cursor))))

(defn- fact-page [page reference-attributes]
  (if (::error page)
    page
    (cond-> {::facts
             (mapv (fn [fact-row]
                     (assoc fact-row ::reference?
                            (contains? reference-attributes (::attribute fact-row))))
                   (::rows page))
             ::more? (::more? page)}
      (::next-cursor page) (assoc ::next-cursor (::next-cursor page)))))

(defn- installed-reference-attributes [dbv]
  (into #{}
        (keep (fn [[attribute schema-facts]]
                (when (= :db.type/ref (:db/valueType schema-facts))
                  attribute)))
        (db/installed-schema dbv)))

(defn entity-page
  "Read one bounded current EAVT fact page for an entity."
  {:malli/schema [:=> [:cat ::entity-page-request] ::fact-page-result]}
  [{dbv :seon.db/db
    coordinate ::database-coordinate
    entity ::entity
    limit ::limit
    cursor ::cursor}]
  (let [page
        (index-page
          (cond-> {:seon.db/db dbv
                   ::database-coordinate coordinate
                   ::projection :current
                   ::index :eavt
                   ::prefix [entity]
                   ::direction :forward
                   ::limit limit}
            cursor (assoc ::cursor cursor)))]
    (fact-page page
               (if (seq (::rows page))
                 (installed-reference-attributes dbv)
                 #{}))))

(defn reverse-reference-page
  "Read one bounded current AVET page for an incoming reference."
  {:malli/schema
   [:=> [:cat ::reverse-reference-page-request] ::fact-page-result]}
  [{dbv :seon.db/db
    coordinate ::database-coordinate
    attribute ::attribute
    target-entity ::target-entity
    limit ::limit
    cursor ::cursor}]
  (let [schema-facts (attribute-schema dbv attribute)]
    (cond
      (nil? schema-facts)
      (cursor-error
        :seon.db.browser.projection.error/unknown-attribute
        "The reverse-reference attribute is not installed in this database.")

      (not= :db.type/ref (:db/valueType schema-facts))
      (cursor-error
        :seon.db.browser.projection.error/not-reference
        "The reverse-reference projection requires a reference attribute.")

      :else
      (fact-page
        (index-page
          (cond-> {:seon.db/db dbv
                   ::database-coordinate coordinate
                   ::projection :current
                   ::index :avet
                   ::prefix [attribute target-entity]
                   ::direction :forward
                   ::limit limit}
            cursor (assoc ::cursor cursor)))
        #{attribute}))))
