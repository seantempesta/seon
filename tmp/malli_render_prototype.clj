;; Disposable REPL prototype for docs/research/agent-platform/malli-render-attachment-2026-09-27.md.
;; Evaluated in the scratch JVM namespace tmp.malli-render-prototype through MCP eval_clj
;; (jvm, read-only against default); defines nothing in any seon.* namespace.
(ns tmp.malli-render-prototype
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]))

(def render-key {:ai :seon.render/ai :html :seon.render/html})

;; The effective render property of one compiled schema: its own properties, then its
;; IntoSchema's type properties (malli/transform.cljc:412-413, malli/error.cljc:295-296,
;; malli/generator.cljc:497-498 all read properties before type-properties), then along the
;; reference chain: a registry pointer or :ref derefs to its target (malli/core.cljc:2864-2870,
;; the way generator.cljc:434 and json_schema.cljc:195 follow ::m/schema), and an :and is
;; read like mu/merge's property merge (malli/util.cljc:77,96: the later arm wins), so a
;; derived [:and :seon.error/base [:map {...}]] takes its own arm's pair before the base's.
;; Compiled once per schema instance in the schema's own Malli cache (malli/core.cljc:353).
(defn declared-render [schema target]
  (let [k (render-key target)
        found (m/-cached schema [::declared k]
                         (fn [schema]
                           (or (get (m/properties schema) k)
                               (get (m/type-properties schema) k)
                               (cond
                                 (m/-ref-schema? schema) (declared-render (m/deref schema) target)
                                 (= :and (m/type schema))
                                 (some #(declared-render % target) (rseq (vec (m/children schema))))
                                 :else nil)
                               ::none)))]
    (when-not (= ::none found) found)))

;; A value of a KNOWN schema (a function's declared output, an attribute's declaration):
;; value -> render symbol, compiled once per schema. Alternatives dispatch by their cached
;; validators as Malli's :or encoder does (malli/core.cljc:657-660); among satisfied named
;; alternatives, one another satisfied alternative refines is dropped (Malli's deref/:and
;; chain, seon.schema.internal/extends-schema?).
(defn selector [schema target]
  (m/-cached schema [::selector target]
             (fn [schema]
               (if-let [declared (declared-render schema target)]
                 (constantly declared)
                 (let [s (m/deref-all schema)]
                   (case (m/type s)
                     (:or :orn :multi)
                     (let [alts (mapv #(if (#{:orn :multi} (m/type s)) (nth % 2) %) (m/children s))
                           arms (mapv (fn [a] [(m/validator a) (selector a target)
                                               (when (m/-ref-schema? a) (m/-ref a)) a])
                                      alts)]
                       (fn [v]
                         (let [hits (filterv (fn [[valid? sel]] (and (valid? v) (sel v))) arms)
                               specific (remove (fn [[_ _ own]]
                                                  (and own (some (fn [[_ _ other a]]
                                                                   (and other (not= other own)
                                                                        (internal/extends-schema? a own)))
                                                                 hits)))
                                                hits)]
                           (some (fn [[_ sel]] (sel v)) specific))))
                     :maybe (let [sel (selector (first (m/children s)) target)]
                              (fn [v] (when (some? v) (sel v))))
                     (constantly nil)))))))

;; Identity-bearing values name their own schema, so nothing is inferred from shape: a live
;; error by its producer-declared schema (metadata), an entity by its identity attribute's
;; declared :seon.program/row-schema. Reading a committed value never re-validates it. (A
;; stored fault's :seon.error/declared-schema ATTRIBUTE names its occurrences' schema, not
;; the fault entity's, so it is not read here.)
(defn value-schema [registry value]
  (when (map? value)
    (or (some->> (:seon.error/declared-schema (meta value)) (mr/schema registry))
        (some (fn [k] (when (qualified-keyword? k)
                        (some->> (mr/schema registry k) m/properties :seon.program/row-schema
                                 (mr/schema registry))))
              (keys value)))))

;; The lookup order after the unchanged explicit-value and namespace stages: the value's own
;; schema's declaration, else the known schema's selector, else the floor.
(defn render-for [registry target value known-schema]
  (or (when (map? value) (get value (render-key target)))
      (when-let [s (value-schema registry value)] (declared-render s target))
      (when known-schema ((selector known-schema target) value))
      (if (= :html target) 'seon.render.value/render-html 'seon.render.value/render-ai)))

;; Malli's own "value names its schema": a :multi's :dispatch (malli/core.cljc:1861-1879),
;; here the producer's declared schema. Used as [:multi {:dispatch this} [k k] ...].
(defn declared-schema-of [value]
  (:seon.error/declared-schema (meta value)))
