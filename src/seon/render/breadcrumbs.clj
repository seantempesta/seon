(ns seon.render.breadcrumbs
  "General distributed schema breadcrumbs & data-driven function/doc discovery:
   Given ANY Datahike DB and ANY schema/entity value (registered by any agent at any time),
   derives live breadcrumbs strictly from Datahike indexes and Malli schema metadata:
   - Matched primary schema, multi-facet schemas, and resolved `:seon.render/hiccup` renderer
   - Public non-test Clojure functions whose `:malli/schema` touches the schema (`:seon.code.var/schema-refs`)
     or whose body reads/writes its attribute namespace (`:seon.code.var/keywords`)
   - Documentation sections citing the schema (`:seon.code.doc/references`)
   - Outgoing ref attributes (`:seon.db/to`) and incoming reverse refs (`seon.schema.index/reaching`)
   - Exact REPL drill-in forms (`d/pull` / `user/expand!`)
   Works identically for Web UI HTML (`:mode :human`), Agent Walk (`:mode :agent`), and Eval Tool Output."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(defn- valid-db? [db]
  (and (some? db) (map? (:schema db))))

(defn- dbs-to-search [db main-db]
  (cond
    (and (valid-db? db) (valid-db? main-db) (not= db main-db)) [db main-db]
    (valid-db? db) [db]
    (valid-db? main-db) [main-db]
    :else []))

(defn- public-non-test-var? [db eid]
  (let [v (d/pull db '[:seon.code.var/name :seon.code.var/private? :seon.code.var/test?] eid)]
    (when (and (:seon.code.var/name v)
               (not (:seon.code.var/private? v))
               (not (:seon.code.var/test? v)))
      (:seon.code.var/name v))))

(defn discover-schema-fns
  "Discovers up to `limit` public non-test Clojure functions operating on `schema-key` from Datahike indexes:
   1. Exact `:seon.code.var/schema-refs` hits (functions whose `:malli/schema` accepts/returns `schema-key`).
   2. Namespace-attribute consumers via `:seon.code.var/keywords` matching `(namespace schema-key)`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :qualified-keyword :int] [:vector :symbol]]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [db main-db schema-key limit]
  (let [lim (max 0 (min 24 (long limit)))
        dbs (dbs-to-search db main-db)]
    (if (or (zero? lim) (empty? dbs))
      []
      (let [;; Tier 1: explicit :malli/schema references (highest precision)
            by-schema-ref
            (reduce
             (fn [acc adb]
               (if (contains? (:schema adb) :seon.code.var/schema-refs)
                 (reduce (fn [s dt]
                           (if-let [vsym (public-non-test-var? adb (:e dt))]
                             (conj s vsym)
                             s))
                         acc
                         (d/datoms adb :avet :seon.code.var/schema-refs schema-key))
                 acc))
             (sorted-set)
             dbs)
            ;; Tier 2: if room remains, discover functions touching attributes in the schema's namespace
            sn (namespace schema-key)
            by-ns-kw
            (if (or (>= (count by-schema-ref) lim) (nil? sn))
              (sorted-set)
              (let [ix (schema/entity-index (first dbs))
                    own-attrs (take 6 (filter qualified-keyword? (six/own-keys (get-in ix [:forms schema-key]))))]
                (reduce
                 (fn [acc adb]
                   (if (contains? (:schema adb) :seon.code.var/keywords)
                     (reduce
                      (fn [s attr]
                        (if (>= (+ (count by-schema-ref) (count s)) lim)
                          (reduced s)
                          (reduce (fn [s2 dt]
                                    (if (>= (+ (count by-schema-ref) (count s2)) lim)
                                      (reduced s2)
                                      (if-let [vsym (public-non-test-var? adb (:e dt))]
                                        (if (contains? by-schema-ref vsym) s2 (conj s2 vsym))
                                        s2)))
                                  s
                                  (take 16 (d/datoms adb :avet :seon.code.var/keywords attr)))))
                      acc
                      own-attrs)
                     acc))
                 (sorted-set)
                 dbs)))]
        (vec (take lim (concat by-schema-ref by-ns-kw)))))))

(defn discover-schema-docs
  "Discovers up to `limit` documentation sections referencing `schema-key` via `:seon.code.doc/references`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :qualified-keyword :int] [:vector :map]]
   :cost/ticks 3000000
   :cost/bytes 2097152}
  [db main-db schema-key limit]
  (let [lim (max 0 (min 12 (long limit)))
        dbs (dbs-to-search db main-db)]
    (if (or (zero? lim) (empty? dbs))
      []
      (->> (for [adb dbs
                 :when (and (contains? (:schema adb) :seon.schema/key)
                            (contains? (:schema adb) :seon.code.doc/references))
                 :let [se (:e (first (d/datoms adb :avet :seon.schema/key schema-key)))]
                 :when se
                 dt (take 12 (d/datoms adb :avet :seon.code.doc/references se))
                 :let [doc (d/pull adb '[:seon.code.doc/id :seon.code.doc/heading :seon.code.doc/path] (:e dt))]
                 :when (:seon.code.doc/id doc)]
             {:id (:seon.code.doc/id doc)
              :heading (or (:seon.code.doc/heading doc) (:seon.code.doc/id doc))
              :path (:seon.code.doc/path doc)})
           (reduce (fn [[seen acc] item]
                     (if (contains? seen (:id item))
                       [seen acc]
                       [(conj seen (:id item)) (conj acc item)]))
                   [#{} []])
           second
           (sort-by :id)
           (take lim)
           vec))))

(defn entity-lookup-ref
  "Derives the canonical Datahike lookup ref `[id-attr id-val]` for entity map `ent` under schema `sk`."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword] :any] [:maybe [:vector :any]]]
   :cost/ticks 50000
   :cost/bytes 16384}
  [ix sk ent]
  (when (and (map? ix) (qualified-keyword? sk) (map? ent))
    (let [sids (:schema-identities ix)]
      (when (map? sids)
        (when-let [ida (get sids sk)]
          (when (some? (get ent ida))
            [ida (get ent ida)]))))))

(defn- resolve-renderer-sym [db main-db ix sk]
  (or (six/schema-property ix sk :seon.render/hiccup)
      (some (fn [adb]
              (when (contains? (:schema adb) :seon.schema/key)
                (let [e (d/pull adb '[:seon.schema/render :seon.schema/form] [:seon.schema/key sk])]
                  (or (:seon.schema/render e)
                      (when (and (vector? (:seon.schema/form e)) (map? (second (:seon.schema/form e))))
                        (:seon.render/hiccup (second (:seon.schema/form e))))))))
            (dbs-to-search db main-db))))

(defn schema-breadcrumbs
  "Derives structured live breadcrumbs for entity/value `ent` of schema `sk` across `db`/`main-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-keyword] :any :map] [:maybe :map]]
   :cost/ticks 10000000
   :cost/bytes 4194304}
  [db sk ent opts]
  (let [main-db (:main-db opts)
        eff-db (or db main-db)
        ix (schema/entity-index eff-db)
        raw-fs (when (map? ent) (six/facets-of ix ent))
        eff-sk (or sk
                   (when (seq raw-fs) (:schema (first raw-fs)))
                   (when (and (vector? ent) (= 2 (count ent)) (qualified-keyword? (first ent)))
                     (get-in ix [:identity-schemas (first ent)])))]
    (when (qualified-keyword? eff-sk)
      (let [facets (vec (distinct (cons eff-sk (map :schema raw-fs))))
            renderer (resolve-renderer-sym db main-db ix eff-sk)
            fn-lim (long (or (:fn-limit opts) 6))
            doc-lim (long (or (:doc-limit opts) 3))
            fns (discover-schema-fns db main-db eff-sk fn-lim)
            docs (discover-schema-docs db main-db eff-sk doc-lim)
            lref (or (:lookup-ref opts)
                     (when (and (vector? ent) (= 2 (count ent)) (qualified-keyword? (first ent))) ent)
                     (entity-lookup-ref ix eff-sk ent))
            check-keys (if (map? ent) (keys ent) (six/own-keys (get-in ix [:forms eff-sk])))
            outgoing (->> check-keys
                          (keep (fn [k]
                                  (let [ts (six/targets ix k)]
                                    (when (seq ts) {:attr k :targets ts}))))
                          (sort-by (comp str :attr))
                          (take 5)
                          vec)
            incoming (vec (take 5 (six/reaching ix eff-sk)))
            drill-form (cond
                         lref (str "(d/pull (user/db) '[*] " (pr-str lref) ")")
                         (:handle opts) (str "(user/expand! '" (:handle opts) ")")
                         :else nil)]
        {:schema eff-sk
         :facets facets
         :renderer renderer
         :functions fns
         :docs docs
         :outgoing outgoing
         :incoming incoming
         :lookup-ref lref
         :drill-form drill-form
         :fired-rules (vec (take 8 (:fired-rules opts)))}))))

(defn render-breadcrumbs-hiccup
  "Renders live breadcrumbs as an interactive Phosphor footer (`:mode :human`) or compact Markdown-ready
   Hiccup (`:mode :agent` / `:mode :model`) with zero fragile custom logic."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-keyword] :any :map] :any]
   :cost/ticks 12000000
   :cost/bytes 4194304}
  [db sk ent opts]
  (when-let [bc (schema-breadcrumbs db sk ent opts)]
    (let [{:keys [schema facets renderer functions docs outgoing incoming lookup-ref drill-form fired-rules]} bc
          mode (:mode opts :human)
          board-id (:board-id opts)
          agent-id (:agent-id opts)
          extra-facets (vec (remove #(= schema %) facets))]
      (if (not= :human mode)
        [:div {:class "crumb-bar crumb-agent"}
         [:p (str "Breadcrumbs: schema " schema
                  (when (seq extra-facets) (str " (+facets " (str/join ", " extra-facets) ")"))
                  " · renderer " (or renderer "4-stage self-printer"))]
         (when (seq functions)
           [:p (str "Functions: " (str/join ", " functions))])
         (when (seq docs)
           [:p (str "Docs: " (str/join ", " (map :id docs)))])
         (when (or (seq outgoing) (seq incoming))
           [:p (str "Graph refs:"
                    (when (seq outgoing) (str " out " (str/join ", " (map #(str (:attr %) "->" (str/join "|" (:targets %))) outgoing))))
                    (when (seq incoming) (str " in " (str/join ", " incoming))))])
         (when drill-form
           [:p (str "Drill: `" drill-form "`")])]
        [:div {:class "crumb-bar"}
         [:div {:class "crumb-row"}
          [:span {:class "crumb-label"} "Schema"]
          (if board-id
            [:button {:type "button" :class "crumb-btn mono"
                      :title "Inspect this Malli schema entity"
                      :on-click (list 'seon.web/set-board-state! board-id {:inspect [:seon.schema/key schema]})}
             (str schema)]
            [:span {:class "pill mono"} (str schema)])
          (for [ef extra-facets]
            [:span {:class "badge purple mono"} (str "+" ef)])
          [:span {:class "badge muted mono"}
           (if renderer (str "renderer: " renderer) "4-stage self-printer")]
          (when (and board-id lookup-ref (not= lookup-ref [:seon.schema/key schema]))
            [:button {:type "button" :class "crumb-btn accent mono"
                      :on-click (list 'seon.web/set-board-state! board-id {:inspect lookup-ref})}
             "Inspect"])
          (when (and lookup-ref (:try-agent opts))
            [:button {:type "button" :class "crumb-btn success mono"
                      :on-click (list 'seon.web/pin-to-agent! (:try-agent opts) lookup-ref true)}
             (str "Pin → " (:try-agent opts))])
          (when (and lookup-ref agent-id (nil? (:try-agent opts)) (not (:suppress-pin? opts)))
            [:button {:type "button" :class "crumb-btn mono"
                      :on-click (list 'seon.web/pin-to-agent! agent-id lookup-ref false)}
             "+ Pin"])]
         (when (seq functions)
           [:div {:class "crumb-row"}
            [:span {:class "crumb-label"} "Fns"]
            (for [f functions]
              (if board-id
                [:button {:type "button" :class "crumb-btn mono"
                          :title (str "Inspect function " f)
                          :on-click (list 'seon.web/set-board-state! board-id {:inspect [:seon.code.var/name f]})}
                 (str f)]
                [:span {:class "pill mono"} (str f)]))])
         (when (seq docs)
           [:div {:class "crumb-row"}
            [:span {:class "crumb-label"} "Docs"]
            (for [{:keys [id heading]} docs]
              (if board-id
                [:button {:type "button" :class "crumb-btn"
                          :title (str "Inspect doc " id)
                          :on-click (list 'seon.web/set-board-state! board-id {:inspect [:seon.code.doc/id id]})}
                 (str heading)]
                [:span {:class "pill"} (str id)]))])
         (when (or (seq outgoing) (seq incoming))
           [:div {:class "crumb-row"}
            [:span {:class "crumb-label"} "Graph"]
            (for [{:keys [attr targets]} outgoing]
              [:span {:class "pill mono"} (str attr " → " (str/join ", " targets))])
            (for [inc-a incoming]
              [:span {:class "pill mono"} (str "← " inc-a)])])
         (when (seq fired-rules)
           [:div {:class "crumb-row"}
            [:span {:class "crumb-label"} "Rules"]
            (for [r fired-rules]
              (if (and board-id (qualified-keyword? r) (str/starts-with? (namespace r) "rule."))
                [:button {:type "button" :class "crumb-btn mono"
                          :on-click (list 'seon.web/set-board-state! board-id {:inspect [:seon.rule/id r]})}
                 (str r)]
                [:span {:class "badge muted mono"} (str r)]))])]))))

(defn- detect-eval-target [ix val explicit-schema]
  (cond
    (and (vector? val) (= 2 (count val)) (qualified-keyword? (first val)))
    (when-let [sk (or explicit-schema (get-in ix [:identity-schemas (first val)]))]
      {:schema sk :ent val :lookup-ref val})

    (map? val)
    (let [ms (six/most-specific ix val)
          sk (cond (qualified-keyword? ms) ms
                   (qualified-keyword? explicit-schema) explicit-schema
                   (and (instance? clojure.lang.IMeta val) (qualified-keyword? (:seon/schema (meta val))))
                   (:seon/schema (meta val))
                   :else nil)]
      (when sk {:schema sk :ent val :lookup-ref (entity-lookup-ref ix sk val)}))

    (and (sequential? val) (seq val))
    (let [v0 (first val)]
      (cond
        (map? v0)
        (let [ms (six/most-specific ix v0)
              sk (if (qualified-keyword? ms) ms (when (qualified-keyword? explicit-schema) explicit-schema))]
          (when sk {:schema sk :ent v0 :lookup-ref (entity-lookup-ref ix sk v0) :coll-count (count val)}))

        (and (vector? v0) (= 2 (count v0)) (qualified-keyword? (first v0)))
        (when-let [sk (or (get-in ix [:identity-schemas (first v0)])
                          (when (qualified-keyword? explicit-schema) explicit-schema))]
          {:schema sk :ent v0 :lookup-ref v0 :coll-count (count val)})

        :else nil))

    :else nil))

(defn format-eval-breadcrumbs-md
  "Formats a compact 1-2 line breadcrumb trail for REPL eval results matching a registered Malli schema.
   Returns nil for plain non-schema values (numbers, strings, plain maps) so normal evals stay noise-free."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any [:maybe :qualified-keyword] :map] [:maybe :string]]
   :cost/ticks 10000000
   :cost/bytes 4194304}
  [db val result-schema opts]
  (when (or (valid-db? db) (valid-db? (:main-db opts)))
    (let [eff-db (or db (:main-db opts))
          ix (schema/entity-index eff-db)]
      (when-let [{:keys [schema ent lookup-ref coll-count]} (detect-eval-target ix val result-schema)]
        (when-let [{:keys [facets renderer functions docs outgoing incoming drill-form]}
                   (schema-breadcrumbs db schema ent (assoc opts :lookup-ref lookup-ref :fn-limit 5 :doc-limit 2))]
          (let [extras (vec (remove #(= schema %) facets))
                line1 (str "; Breadcrumbs · " schema
                           (when coll-count (str " (x" coll-count ")"))
                           (when (seq extras) (str " +facets " (str/join ", " extras)))
                           " · renderer: " (or renderer "self-printer")
                           (when (seq functions) (str " · Fns: " (str/join ", " functions))))
                parts2 (remove nil?
                               [(when (seq docs) (str "Docs: " (str/join ", " (map :id docs))))
                                (when (seq outgoing)
                                  (str "Refs: " (str/join ", " (map #(str (:attr %) "->" (str/join "|" (:targets %))) outgoing))))
                                (when (seq incoming)
                                  (str "In: " (str/join ", " incoming)))
                                (when drill-form (str "Drill: " drill-form))])]
            (if (seq parts2)
              (str line1 "\n;   " (str/join " · " parts2))
              line1)))))))

(defn choose-eval-db
  "Selects the stable KV-cache `db` when it already recognizes `parsed`/`sch`, falling back to `fallback-db`
   only when a brand-new schema was registered on the active branch."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :any [:maybe :qualified-keyword]] [:maybe :seon.host/db]]
   :cost/ticks 500000
   :cost/bytes 262144}
  [db fallback-db parsed sch]
  (cond
    (and (valid-db? db) (valid-db? fallback-db) (not= db fallback-db))
    (let [ix (schema/entity-index db)
          known? (or (and (qualified-keyword? sch) (contains? (:forms ix) sch))
                     (and (vector? parsed) (= 2 (count parsed)) (qualified-keyword? (first parsed))
                          (boolean (get-in ix [:identity-schemas (first parsed)])))
                     (and (map? parsed) (boolean (seq (six/facets-of ix parsed)))))]
      (if known? db fallback-db))
    (valid-db? db) db
    (valid-db? fallback-db) fallback-db
    :else nil))
