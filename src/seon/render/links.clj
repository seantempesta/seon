(ns seon.render.links
  "Shared semantic `.ent-link` Hiccup primitives and entity ref-enrichment helpers used across
   `seon.render.entities`, `seon.render.tails`, `seon.render.surface*`, and `seon.render.markdown`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]))

(defn db-schema
  "Returns the schema map for `db`, falling back to `(d/schema db)` on `AsOfDB` / `HistoricalDB`."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 100000}
  [db]
  (if (instance? datahike.db.interface.ISearch db)
    (or (:schema db) (d/schema db) {})
    (if (map? (:schema db)) (:schema db) {})))

(defn real-rule-id?
  "True when `x` is a real `:seon.rule/id` keyword (excludes synthetic `:seed/*` tags)."
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 50000}
  [x]
  (boolean (and (qualified-keyword? x)
                (str/starts-with? (str (namespace x)) "rule"))))

(defn ref-eid
  "Extracts an integer EID from an EID or Datahike pulled ref map `{:db/id n}`, else nil."
  {:malli/schema [:=> [:cat :any] [:maybe :int]]
   :cost/ticks 50000}
  [x]
  (cond
    (integer? x) (long x)
    (and (map? x) (integer? (:db/id x))) (long (:db/id x))
    :else nil))

(defn ent-link
  "Renders a semantic clickable `.ent-link` button dispatching `(seon.web/inspect-entity! [attr val])`,
   or a plain `[:span]` when the ref is invalid. `render/hiccup->text` preserves `.ent-link` text cleanly."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:vector :any]]
                  [:=> [:cat :any :any [:maybe :string]] [:vector :any]]
                  [:=> [:cat :any :any :any [:maybe :string]] [:vector :any]]]
   :cost/ticks 100000}
  ([ref lbl] (ent-link ref lbl "mono"))
  ([ref lbl cls]
   (if-let [r (rdata/normalize-inspect-ref ref)]
     [:button {:type "button"
               :class (str "ent-link" (when cls (str " " cls)))
               :title (str "Inspect " (pr-str r))
               :on-click (list 'seon.web/inspect-entity! r)}
      (str lbl)]
     [:span {:class (or cls "")} (str lbl)]))
  ([a v lbl cls]
   (if (and (qualified-keyword? a) (some? v))
     (ent-link [a v] lbl cls)
     [:span {:class (or cls "")} (str lbl)])))

(defn meta-link
  "Renders a muted right-aligned clickable metadata `.ent-link` when `v` is non-nil."
  {:malli/schema [:=> [:cat :qualified-keyword :any] [:maybe [:vector :any]]]
   :cost/ticks 100000}
  [a v]
  (when (some? v) (ent-link [a v] (str v) "muted tiny mono ent-meta")))

(defn link-pill
  "Renders a clickable `.ent-link.pill` chip."
  {:malli/schema [:=> [:cat :qualified-keyword :any :any] [:vector :any]]
   :cost/ticks 100000}
  [a v lbl]
  (ent-link [a v] lbl "pill mono"))

(defn badge
  "Renders a colored status badge."
  {:malli/schema [:=> [:cat :string :any] [:vector :any]]
   :cost/ticks 50000}
  [cls text]
  [:span {:class (str "badge " cls)} (str text)])

(defn pill
  "Renders a monospace pill."
  {:malli/schema [:=> [:cat :any] [:vector :any]]
   :cost/ticks 50000}
  [text]
  [:span {:class "pill mono"} (str text)])

(defn mut
  "Renders tiny muted monospace text."
  {:malli/schema [:=> [:cat :any] [:vector :any]]
   :cost/ticks 50000}
  [text]
  [:span {:class "mono tiny muted"} (str text)])

(defn kicker
  "Renders a section kicker label."
  {:malli/schema [:=> [:cat :any] [:vector :any]]
   :cost/ticks 50000}
  [text]
  [:span {:class "kicker"} (str text)])

(defn kv-dl
  "Renders a `.kv-grid` definition list from `[label val]` pairs, omitting nil or blank entries."
  {:malli/schema [:=> [:cat [:sequential [:maybe [:tuple :any :any]]]] [:maybe [:vector :any]]]
   :cost/ticks 200000}
  [pairs]
  (let [rows (mapcat (fn [pair]
                       (when (and (sequential? pair) (>= (count pair) 2))
                         (let [[k v] pair]
                           (when (and (some? v) (or (vector? v) (not (str/blank? (str v)))))
                             [[:dt (str k)] [:dd (if (vector? v) v (str v))]]))))
                     pairs)]
    (when (seq rows) (into [:dl {:class "kv-grid"}] rows))))

(defn pill-list
  "Renders a labeled `.pill-row` of up to `limit` items.
   `attr` may be a qualified-keyword lookup attr, `:ref` (each item is a lookup ref),
   `:ref-pair` (each item is `[lookup-ref label]`), or nil for plain pills."
  {:malli/schema [:=> [:cat [:maybe :string] :any :any :int] [:maybe [:vector :any]]]
   :cost/ticks 300000}
  [label xs attr limit]
  (when-let [items (when (and (coll? xs) (not (map? xs))) (seq xs))]
    (into [:div {:class "pill-row"} (when label [:span {:class "muted tiny"} label])]
          (keep (fn [it]
                  (cond
                    (= attr :ref-pair)
                    (when (and (vector? it) (= 2 (count it)))
                      (ent-link (first it) (second it) "pill mono"))

                    (= attr :ref)
                    (when-let [r (rdata/normalize-inspect-ref it)]
                      (ent-link r (rdata/entity-short-label r) "pill mono"))

                    (qualified-keyword? attr)
                    (cond
                      (and (map? it) (integer? (:db/id it)))
                      (ent-link [:db/id (:db/id it)] (str "#" (:db/id it)) "pill mono")
                      (some? it)
                      (link-pill attr it (str it))
                      :else nil)

                    :else (pill (str it))))
                (take (max 0 (long limit)) items)))))

(defn- resolve-attr-values [db refs target-attr limit]
  (when (and db (coll? refs) (seq refs) ((db-schema db) target-attr))
    (not-empty
     (vec (distinct
           (keep (fn [x]
                   (cond
                     (and (= target-attr :seon.schema/key) (qualified-keyword? x)) x
                     (and (= target-attr :seon.code.var/name) (qualified-symbol? x)) x
                     :else (when-let [e (ref-eid x)]
                             (get (d/pull db [target-attr] e) target-attr))))
                 (take limit refs)))))))

(defn- resolve-doc-refs [db refs limit]
  (when (and db (coll? refs) (seq refs))
    (not-empty
     (vec (keep (fn [x]
                  (if-let [nr (rdata/normalize-inspect-ref x)]
                    [nr (rdata/entity-short-label nr)]
                    (when-let [e (ref-eid x)]
                      (let [p (d/pull db '[:seon.code.var/name :seon.code.ns/name :seon.schema/key :seon.task/id] e)
                            r (or (rdata/entity-lookup-ref p) [:db/id e])]
                        [r (rdata/entity-short-label (if (seq (dissoc p :db/id)) p r))]))))
                (take limit refs))))))

(defn- find-matching-fix [db err-kind]
  (let [dh (db-schema db)]
    (when (and db (keyword? err-kind) (dh :seon.fix/error-class))
      (when-let [fe (some #(when (= err-kind (:v %)) (:e %))
                          (d/datoms db :aevt :seon.fix/error-class))]
        (d/pull db '[*] fe)))))

(defn enrich-entity-params
  "Resolves auxiliary display data & `:db.type/ref` target labels for entity `ent` on `db`
   (source form, matching `:seon.fix/entity`, optional callers, schema key/target refs, doc refs, rule refs)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map] :boolean] :map]]
   :cost/ticks 5000000}
  ([db ent] (enrich-entity-params db ent false))
  ([db ent focal?]
   (if-not (and db (map? ent))
     {}
     (let [dh (db-schema db)
           src (when-let [fk (and (dh :seon.code.form/key) (:seon.code.var/form ent))]
                 (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fk])))
           fix (when-let [ek (:seon.error/kind ent)] (find-matching-fix db ek))
           callers (when (and focal? (dh :seon.code.var/refs) (:seon.code.var/name ent))
                     (->> (take 12 (d/datoms db :avet :seon.code.var/refs (:seon.code.var/name ent)))
                          (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                          distinct sort vec not-empty))
           sks (when (:seon.schema/key ent)
                 {:keys (resolve-attr-values db (:seon.schema/keys ent) :seon.schema/key 16)
                  :opt  (resolve-attr-values db (:seon.schema/optional-keys ent) :seon.schema/key 16)
                  :refs (resolve-attr-values db (:seon.schema/ref-targets ent) :seon.schema/key 12)})
           drefs (when (:seon.code.doc/id ent)
                   (resolve-doc-refs db (:seon.code.doc/references ent) 12))
           r-schs (when (:seon.rule/id ent)
                    (resolve-attr-values db (:seon.rule/schema ent) :seon.schema/key 12))
           r-fns (when (:seon.rule/id ent)
                   (resolve-attr-values db (:seon.rule/fn ent) :seon.code.var/name 12))]
       (cond-> {}
         (and (string? src) (not (str/blank? src))) (assoc :seon.code.form/src src)
         (and (map? fix) (seq (dissoc fix :db/id))) (assoc :seon.fix/entity fix)
         callers (assoc :seon.ui/show-callers? true :seon.ui/callers callers)
         (:keys sks) (assoc :seon.ui/schema-keys (:keys sks))
         (:opt sks)  (assoc :seon.ui/schema-optional-keys (:opt sks))
         (:refs sks) (assoc :seon.ui/schema-ref-targets (:refs sks))
         drefs (assoc :seon.ui/doc-refs drefs)
         r-schs (assoc :seon.ui/rule-schemas r-schs)
         r-fns (assoc :seon.ui/rule-fns r-fns))))))
