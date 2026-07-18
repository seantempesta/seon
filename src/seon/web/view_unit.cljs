(ns seon.web.view-unit
  "Stable browser identity for database-derived web units."
  (:require [seon.schema :as schema]))

(schema/register! ::identity-value
  [:or :string :keyword :symbol :boolean :int :uuid])
(schema/register! ::view-identity
  [:map-of {:min 1} :qualified-keyword ::identity-value])
(schema/register! ::text :string)
(schema/register! ::token [:string {:min 1}])

(defn- canonical-keyword [value]
  [(namespace value) (name value)])

(defn- canonical-value [value]
  (cond
    (keyword? value) [::keyword (namespace value) (name value)]
    (symbol? value)  [::symbol (namespace value) (name value)]
    (string? value)  [::string value]
    (boolean? value) [::boolean value]
    (int? value)     [::integer value]
    (uuid? value)    [::uuid (str value)]))

(defn encode-text
  "Encode UTF-8 text as an RFC 4648 base64url token."
  {:malli/schema [:=> [:catn [::text ::text]] ::token]}
  [value]
  (-> (js/Buffer.from value "utf8") (.toString "base64url")))

(defn identity-token
  "Stable opaque token derived from a view's namespaced identity data."
  {:malli/schema [:=> [:catn [::view-identity ::view-identity]] ::token]}
  [view-identity]
  (encode-text
   (pr-str
    (->> view-identity
         (sort-by (comp canonical-keyword key))
         (mapv (fn [[k v]]
                 [(canonical-keyword k) (canonical-value v)]))))))
