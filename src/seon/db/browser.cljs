(ns seon.db.browser
  "Bounded, index-backed projections for the operator database browser.

   The web layer owns URLs and Hiccup. This namespace owns database access:
   installed attributes form the cheap navigator, and an opened attribute
   reads at most `limit + 1` AEVT datoms. No browser render scans every entity,
   transaction, or historical datom to manufacture counts."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.schema :as schema]))

(schema/register! ::system? :boolean)
(schema/register! ::attribute :qualified-keyword)
(schema/register! ::limit [:int {:min 1 :max 200}])
(schema/register! ::cursor [:tuple :int :any :int])
(schema/register! ::optional-cursor [:maybe ::cursor])
(schema/register! ::entity :int)
(schema/register! ::value :any)
(schema/register! ::transaction :int)
(schema/register! ::row
                  [:map
                   [::entity ::entity]
                   [::attribute ::attribute]
                   [::value ::value]
                   [::transaction ::transaction]])
(schema/register! ::rows [:vector ::row])
(schema/register! ::more? :boolean)
(schema/register! ::page
                  [:map
                   [::rows ::rows]
                   [::more? ::more?]
                   [::next-cursor ::optional-cursor]])
(schema/register! ::attribute-groups
                  [:map-of :keyword [:vector :qualified-keyword]])
(schema/register! ::attribute-page-request
                  [:map
                   [:seon.db/db :seon.db/db-val]
                   [::attribute ::attribute]
                   [::limit ::limit]
                   [::cursor {:optional true} ::cursor]])

(defn system-attribute?
  "Whether an attribute belongs to Datahike or Seon's framework namespaces."
  {:malli/schema [:=> [:catn [::attribute ::attribute]] :boolean]}
  [attribute]
  (let [n (namespace attribute)]
    (or (= "db" n)
        (str/starts-with? n "db.")
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

(defn- datom-coordinate [datom]
  [(::db/e datom) (::db/v datom) (::db/tx datom)])

(defn- row [datom]
  {::entity (::db/e datom)
   ::attribute (::db/a datom)
   ::value (::db/v datom)
   ::transaction (::db/tx datom)})

(defn- after-cursor
  [datoms cursor]
  (if (and cursor (= cursor (some-> datoms first datom-coordinate)))
    (rest datoms)
    datoms))

(defn attribute-page
  "Read one bounded page from an attribute's AEVT slice.

   The cursor is the last visible datom coordinate `[entity value tx]`.
   `limit + 1` proves whether another page exists without an exact global
   count. A cursor invalidated by a concurrent retraction resumes at the first
   datom at or after that coordinate rather than skipping an extra row."
  {:malli/schema [:=> [:cat ::attribute-page-request] ::page]}
  [{dbv :seon.db/db attribute ::attribute limit ::limit cursor ::cursor}]
  (if-not (contains? (db/installed-schema dbv) attribute)
    {::rows [] ::more? false ::next-cursor nil}
    (let [components (if cursor
                       (let [[entity value transaction] cursor]
                         [attribute entity value transaction])
                       [attribute])
          datoms (db/index-datoms
                   {::db/db dbv
                    ::db/index :aevt
                    ::db/components components
                    ::db/index-limit (+ limit (if cursor 2 1))
                    ::db/seek? (boolean cursor)})
          window (vec (take (inc limit) (after-cursor datoms cursor)))
          more? (> (count window) limit)
          visible (if more? (subvec window 0 limit) window)]
      {::rows (mapv row visible)
       ::more? more?
       ::next-cursor (when (and more? (seq visible))
                       (datom-coordinate (peek visible)))})))
