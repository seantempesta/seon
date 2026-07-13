(ns seon.web.view-unit
  "Stable opaque handles for database-derived web view coordinates."
  (:require
    [seon.schema :as schema]))

(schema/register! ::coordinate-value
  [:or :string :keyword :symbol :boolean :int :uuid])
(schema/register! ::coordinate
  [:map-of {:min 1} :qualified-keyword ::coordinate-value])
(schema/register! ::text :string)
(schema/register! ::token [:string {:min 1}])

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
