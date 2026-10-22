(ns seon.render
  "Render selection and the one render call (W1's proto.render and proto.render.schema,
  ported onto next's registry: notes/option3-W1-render.md §1).

  A render is a plain function (v env) -> :seon.render/out: one or both of
  :seon.render/html (hiccup, serialized by seon.web.html) and :seon.render/ai (data or text
  the model or root reads). Selection, first match wins, and nothing else (owner,
  2026-10-20: \"Everything has a schema right so we say this renders this\"):
    1. the value's own override key (:seon.render/html or :seon.render/ai, in the map or its
       metadata): a render symbol, or already-rendered data;
    2. the render its schema declares: a schema's properties {:seon.render/html sym
       :seon.render/ai sym} in resources/seon/schemas/*.edn (seon.contract/forms), the
       schema then its parents ([:and p ...], [:merge p ...]). The value's schema is never
       guessed: the producer names it (env :seon.render/schema), or, for a pulled entity,
       it is the schema with a declared render holding the entity's identity attribute;
    3. the one value printer (seon.print/print-value): text for :ai, hiccup for :html.
  A nested value renders by calling `render` on it; the printer never re-dispatches."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.print :as print]))

(def faces "Each face's key." {:html :seon.render/html :ai :seon.render/ai})

(defn properties
  "The properties map of named schema k's form, or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [k]
  (let [form (get contract/forms k)]
    (when (and (vector? form) (map? (second form))) (second form))))

(defn parents
  "The named schemas k's form extends ([:and p ...] or [:merge p ...])."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] [:vector :qualified-keyword]]}
  [k]
  (let [form (get contract/forms k)]
    (if (and (vector? form) (#{:and :merge} (first form)))
      (filterv #(and (qualified-keyword? %) (contains? contract/forms %)) (rest form))
      [])))

(defn declared
  "The render symbol schema k (or its nearest parent) declares for face, or nil."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword] :seon.render/face] [:maybe :symbol]]}
  [k face]
  (when k
    (or (get (properties k) (faces face))
        (some #(declared % face) (parents k)))))

(defn- identity-attr?
  "Is attribute a an upsert key ([:and {:seon.db/identity true} ...])?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a] (boolean (:seon.db/identity (properties a))))

(defn- map-keys
  "The keys a schema's [:map ...] form names (inline, or in its [:and ...] members)."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [form]
  (cond (and (vector? form) (= :map (first form))) (vec (keep #(when (vector? %) (first %)) (rest form)))
        (and (vector? form) (#{:and :merge} (first form))) (vec (mapcat #(map-keys (if (keyword? %) (get contract/forms %) %)) (rest form)))
        :else []))

(def by-identity
  "{identity-attribute schema}: for each schema with a declared render, the upsert keys its
  map names: a pulled entity holding that key is a value of that schema. Derived once from
  the registry."
  (into {} (for [[k form] contract/forms
                 :when (or (declared k :html) (declared k :ai))
                 a (map-keys form) :when (identity-attr? a)]
             [a k])))

(defn schema-of
  "The named schema of v: hint (its producer's), or the schema its identity attribute names."
  {:malli/schema [:=> [:cat :any [:maybe :qualified-keyword]] [:maybe :qualified-keyword]]}
  [v hint]
  (or hint (when (map? v) (some by-identity (keys v)))))

(defn print-out
  "The one value printer's output for face: hiccup for :html, text for :ai."
  {:malli/schema [:=> [:cat :seon.render/face :any :seon.render/env] :seon.render/out]}
  [face v env]
  ;; without a handle a cut names no form: no result would answer it
  (let [opts {:budget (or (:seon.render/budget env) 600) :handle (:seon.render/handle env)}]
    (if (= face :html)
      (let [r (print/print-value v (assoc opts :outputs #{:hiccup}))]
        {:seon.render/html (or (:html r) [:span {:class "seon-print-refused"} (str (:seon.error/message r))])})
      {:seon.render/ai (print/text v (:budget opts))})))

(defn- own-key
  "v's own override for face: in the map, else in its metadata."
  {:malli/schema [:=> [:cat :any :keyword] :any]}
  [v k] (or (when (map? v) (get v k)) (get (meta v) k)))

(defn render-face
  "v rendered for one face under env: :seon.render/out holding that face."
  {:malli/schema [:=> [:cat :seon.render/face :any :seon.render/env] :seon.render/out]}
  [face v env]
  (let [k (faces face)
        own (own-key v k)]
    (if (and (some? own) (not (symbol? own)))
      {k own}
      (if-let [sym (or own (declared (schema-of v (:seon.render/schema env)) face))]
        (let [out ((requiring-resolve sym) v env)]
          (if (contains? out k) out (merge out (print-out face v env))))
        (print-out face v env)))))

(defn html
  "v's HTML face under env (the hiccup)."
  {:malli/schema [:=> [:cat :any :seon.render/env] :any]}
  [v env] (:seon.render/html (render-face :html v env)))

(defn ai
  "v's AI face under env, as the text the model or root reads (a render's data printed by
  the value printer under the env's budget)."
  {:malli/schema [:=> [:cat :any :seon.render/env] :string]}
  [v env]
  (let [x (:seon.render/ai (render-face :ai v env))]
    (if (string? x) x (print/text x (or (:seon.render/budget env) 600)))))
