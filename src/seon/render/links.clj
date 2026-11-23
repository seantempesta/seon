(ns seon.render.links
  "Shared semantic `.ent-link` Hiccup primitives, DB/UI utilities, and commit-cached entity
   enrichment (`enrich-entity-params` for vars, errors, schemas, tasks, docs, rules, and namespaces)
   used across `seon.render.entities*`, `seon.render.tails`, `seon.render.surface*`, and `seon.ctx*`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.health.efficiency :as heff]
            [seon.render.data :as rdata]
            [seon.store :as store]
            [seon.web.router :as router]))

(defonce ^:private ^:seon.flow/edge ns-enrich-cache (store/lru-cache 64))

(defonce ^:private ^:seon.flow/edge schema-used-by-cache (store/lru-cache 128))

(defn db-schema
  "Returns the schema map for `db`, falling back to `(d/schema db)` on `AsOfDB` / `HistoricalDB`."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 100000}
  [db]
  (if (instance? datahike.db.interface.ISearch db)
    (or (:schema db) (d/schema db) {})
    (if (map? (:schema db)) (:schema db) {})))

(defn live-cid
  "Returns `(d/commit-id db)` for a live `datahike.db.DB`, else nil."
  {:malli/schema [:=> [:cat :any] :any]
   :cost/ticks 50000}
  [db]
  (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))

(defn db-cache-sig
  "Stable commit-based cache key for `DB` and `AsOfDB` handles (nil for un-cacheable values)."
  {:malli/schema [:=> [:cat :any] :any]
   :cost/ticks 50000}
  [db]
  (cond
    (instance? datahike.db.DB db)
    [(get-in db [:config :store :id]) (get-in db [:config :branch] :main)
     (d/commit-id db) (:max-tx db) nil]

    (instance? datahike.db.AsOfDB db)
    (when-let [orig (:origin-db db)]
      [(get-in orig [:config :store :id]) (get-in orig [:config :branch] :main)
       (when (instance? datahike.db.DB orig) (d/commit-id orig))
       (:max-tx orig) (:time-point db)])

    :else nil))

(defn ^:seon.flow/edge safe-as-of
  "Safely returns `(d/as-of db tx)` when `tx` is an integer, falling back to `db`."
  {:malli/schema [:=> [:cat :any :any] :any]
   :cost/ticks 500000}
  [db tx]
  (if (and db (integer? tx)) (try (d/as-of db tx) (catch Throwable _ db)) db))

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
  "Renders a bookmarkable semantic `.ent-link` `<a href>` dispatching `(seon.web/inspect-entity! [attr val])`,
   or a plain `[:span]` when the ref is invalid. `render/hiccup->text` preserves `.ent-link` text cleanly."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:vector :any]]
                  [:=> [:cat :any :any [:maybe :string]] [:vector :any]]
                  [:=> [:cat :any :any :any [:maybe :string]] [:vector :any]]]
   :cost/ticks 500000}
  ([ref lbl] (ent-link ref lbl "mono"))
  ([ref lbl cls]
   (if-let [r (rdata/normalize-inspect-ref ref)]
     [:a {:href (router/entity-ref->path nil r)
          :class (if cls (str "ent-link " cls) "ent-link")
          :data-ref (pr-str r)
          :data-preview-ref (pr-str r)
          :interestfor "seon-hovercard"
          :on-click (list 'seon.web/inspect-entity! r)}
      [:span (str lbl)]]
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

(defn btn
  "Renders a standard Reagent action button with optional disabled state."
  {:malli/schema [:function
                  [:=> [:cat :string :any :any] [:vector :any]]
                  [:=> [:cat :string :any :any :any] [:vector :any]]]
   :cost/ticks 50000}
  ([cls oc lbl] (btn cls oc lbl false))
  ([cls oc lbl dis?]
   [:button (cond-> {:type "button" :class cls :on-click oc} dis? (assoc :disabled true)) lbl]))

(defn card
  "Renders a standard titled `.card.stack-sm` section with optional pill row and body nodes."
  {:malli/schema [:=> [:cat [:maybe :string] :any [:maybe [:sequential :any]] [:* :any]] [:vector :any]]
   :cost/ticks 100000}
  [id title pills & body]
  (into [:section (cond-> {:class "card stack-sm"} id (assoc :id id))
         [:div {:class "card-head"}
          [:h2 title]
          (when (seq pills) (into [:div {:class "pill-row"}] (remove nil? pills)))]]
        (remove nil? body)))

(defn feedback-banner
  "Renders a dismissible branch/action feedback notice when `branch-feedback` is non-nil."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe [:vector :any]]]
   :cost/ticks 50000}
  [branch-feedback]
  (when (map? branch-feedback)
    [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
     [:span (str (:text branch-feedback))]
     (btn "link-muted tiny" '(seon.web/dismiss-feedback!) "×")]))

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

(defn ^:seon.flow/edge ns-require-aliases
  "Parses a namespace's `(ns ... (:require ...))` source into sorted compact require specs
   like `[clojure.string :as str]` so workers see every alias in scope on Turn 0."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]]
   :cost/ticks 50000}
  [ns-src]
  (if (or (not (string? ns-src)) (str/blank? ns-src))
    []
    (try
      (let [form (binding [*read-eval* false] (read-string ns-src))
            req-clauses (when (and (seq? form) (= 'ns (first form)))
                          (filter #(and (seq? %) (= :require (first %))) (rest form)))
            specs (mapcat rest req-clauses)]
        (->> specs
             (keep (fn [sp]
                     (cond
                       (vector? sp)
                       (let [lib (first sp) tail (rest sp)
                             opts (if (even? (count tail)) (apply hash-map tail) {})
                             al (:as opts) rf (:refer opts)]
                         (cond
                           (and lib al) (str "[" lib " :as " al "]")
                           (and lib rf) (str "[" lib " :refer " (pr-str rf) "]")
                           lib (str lib)))
                       (symbol? sp) (str sp))))
             distinct sort vec))
      (catch Throwable _ []))))

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

(defn- schema-touching-vars [db sk form scope-ns]
  (let [dh (db-schema db)]
    (when (and db sk (dh :seon.code.var/keywords))
      (let [fields (when (and (vector? form) (= :map (first form)))
                     (let [entries (if (map? (second form)) (drop 2 form) (rest form))]
                       (vec (keep #(when (and (vector? %) (keyword? (first %))) (first %)) entries))))
            compute (fn []
                      (let [probe (if (seq fields) (take 8 fields) [sk])
                            eids (into #{} (comp (mapcat #(d/datoms db :avet :seon.code.var/keywords %)) (map :e)) probe)]
                        (->> eids
                             (keep #(:v (first (d/datoms db :eavt % :seon.code.var/name))))
                             (sort-by (fn [vs] [(if (and scope-ns (= scope-ns (namespace vs))) 0 1) (str vs)]))
                             (take 8) vec)))
            sig (db-cache-sig db)]
        (not-empty (if sig (store/lru-get! schema-used-by-cache 128 [sig sk scope-ns] compute) (compute)))))))

(defn- schema-field-tags [db form]
  (when (and db (vector? form) (= :map (first form)))
    (let [dh (db-schema db)
          entries (if (map? (second form)) (drop 2 form) (rest form))]
      (not-empty
       (into {}
             (keep (fn [entry]
                     (when (and (vector? entry) (keyword? (first entry)))
                       (let [f (first entry) fdh (get dh f)]
                         (cond (:db/unique fdh) [f "[unique]"]
                               (:db/index fdh) [f "[indexed]"]
                               :else nil)))))
             entries)))))

(defn- task-lineage-refs [db tid]
  (let [dh (db-schema db)]
    (when (and db tid)
      (let [parents (when (dh :seon.task/needs)
                      (let [eids (if (get-in dh [:seon.task/needs :db/index])
                                   (map :e (d/datoms db :avet :seon.task/needs tid))
                                   (keep #(when (= tid (:v %)) (:e %)) (d/datoms db :aevt :seon.task/needs)))]
                        (->> eids distinct
                             (keep #(:seon.task/id (d/pull db '[:seon.task/id] %)))
                             sort (take 8) vec not-empty)))
            past-ags (when (dh :seon.agent/task)
                       (->> (d/datoms db :avet :seon.agent/task tid)
                            (keep #(:seon.agent/id (d/pull db '[:seon.agent/id] (:e %))))
                            distinct sort (take 4) vec not-empty))]
        (cond-> {}
          parents (assoc :seon.ui/task-needed-by parents)
          past-ags (assoc :seon.ui/task-past-agents past-ags))))))

(defn- fast-reaching-tests [db vars]
  (when (and db ((db-schema db) :seon.code.var/refs))
    (let [seeds (into #{} (keep :seon.code.var/name) vars)]
      (loop [hop 0 frontier seeds seen seeds seen-e #{} tests #{}]
        (if (or (empty? frontier) (>= hop 2) (>= (count tests) 8))
          (vec (sort tests))
          (let [fe (into #{} (comp (mapcat #(d/datoms db :avet :seon.code.var/refs %))
                                   (map :e) (remove seen-e) (take 64))
                         (if (pos? hop) (take 16 frontier) frontier))
                [nt nf] (reduce (fn [[ts nf] e]
                                  (let [vn (:v (first (d/datoms db :eavt e :seon.code.var/name)))]
                                    (cond
                                      (nil? vn) [ts nf]
                                      (true? (:v (first (d/datoms db :eavt e :seon.code.var/test?)))) [(conj ts vn) nf]
                                      (not (seen vn)) [ts (conj nf vn)]
                                      :else [ts nf])))
                                [tests #{}] fe)]
            (recur (inc hop) nf (into seen nf) (into seen-e fe) nt)))))))

(defn- fast-linked-docs [db nsym ns-ent]
  (let [dh (db-schema db)
        seg2 (second (str/split (name nsym) #"\."))
        stem (when seg2 (str "docs/" seg2 ".md"))
        eids (distinct (concat (when (and (:db/id ns-ent) (dh :seon.code.doc/references))
                                 (map :e (take 10 (d/datoms db :avet :seon.code.doc/references (:db/id ns-ent)))))
                               (when (and stem (dh :seon.code.doc/path))
                                 (map :e (take 6 (d/datoms db :avet :seon.code.doc/path stem))))))
        indexed (vec (take 6 (sort-by :id (keep (fn [e]
                                                  (let [p (d/pull db '[:seon.code.doc/id :seon.code.doc/path :seon.code.doc/heading] e)]
                                                    (when (:seon.code.doc/id p)
                                                      {:id (:seon.code.doc/id p) :path (:seon.code.doc/path p) :heading (:seon.code.doc/heading p)})))
                                                eids))))]
    (if (seq indexed) indexed (code/ns-linked-docs db nsym 6))))

(defn- ns-working-schemas [db nsym max-n]
  (let [dh (db-schema db)]
    (when (and db nsym (dh :seon.schema/key))
      (let [freq (volatile! {})
            bump! (fn [k w] (when (qualified-keyword? k) (vswap! freq update k (fnil + 0) w)))
            ns-str (name nsym)
            owned-p (str ns-str ".")
            s-eid (volatile! {}) s-key (volatile! {})
            _ (doseq [dt (d/datoms db :aevt :seon.schema/key)]
                (let [k (:v dt) e (:e dt) kns (namespace k)]
                  (when (and kns (or (= kns ns-str) (str/starts-with? kns owned-p))) (bump! k 2))
                  (vswap! s-eid assoc k e) (vswap! s-key assoc e k)))
            smap @s-key
            io-set #{:seon.code.var/input :seon.code.var/optional-input :seon.code.var/output}]
        (when (dh :seon.code.var/ns)
          (doseq [dt (d/datoms db :avet :seon.code.var/ns nsym)
                  vdt (d/datoms db :eavt (:e dt))
                  :let [a (:a vdt)]]
            (cond (io-set a) (when-let [sk (get smap (:v vdt))] (bump! sk 3))
                  (= :seon.code.var/keywords a) (bump! (:v vdt) 1))))
        (let [emap @s-eid]
          (->> @freq (filter #(contains? emap (key %)))
               (sort-by (fn [[k w]] [(- w) (str k)])) (take max-n)
               (mapv (fn [[k _]]
                       (let [ent (d/pull db '[:seon.schema/key :seon.schema/form :seon.schema/doc] (get emap k))
                             sdh (get dh k)]
                         {:key k :form (:seon.schema/form ent) :doc (:seon.schema/doc ent)
                          :dh-type (:db/valueType sdh) :dh-card (:db/cardinality sdh)})))))))))

(defn- all-ns-schema-prefixes
  ([db max-n] (all-ns-schema-prefixes db max-n nil))
  ([db max-n target-nss]
   (let [dh (db-schema db)]
     (if-not (and db (dh :seon.schema/key) (dh :seon.code.var/ns))
       {}
       (let [tset (when (seq target-nss) (set target-nss))
             smap (volatile! {})
             owned (volatile! {})
             _ (doseq [dt (d/datoms db :aevt :seon.schema/key)]
                 (let [k (:v dt) kns (namespace k)]
                   (vswap! smap assoc (:e dt) k)
                   (when kns
                     (let [parts (str/split kns #"\.")]
                       (loop [i 1]
                         (when (<= i (count parts))
                           (let [psym (symbol (str/join "." (subvec parts 0 i)))]
                             (when (or (nil? tset) (contains? tset psym))
                               (vswap! owned update-in [psym kns] (fnil + 0) 2)))
                           (recur (inc i))))))))
             sm @smap
             freq (volatile! @owned)
             scan-io! (fn [dts]
                        (doseq [dt dts]
                          (when-let [sk (get sm (:v dt))]
                            (when-let [kns (namespace sk)]
                              (when-let [nsym (:v (first (d/datoms db :eavt (:e dt) :seon.code.var/ns)))]
                                (when (or (nil? tset) (contains? tset nsym))
                                  (vswap! freq update-in [nsym kns] (fnil + 0) 3)))))))]
         (when (dh :seon.code.var/input) (scan-io! (d/datoms db :aevt :seon.code.var/input)))
         (when (dh :seon.code.var/optional-input) (scan-io! (d/datoms db :aevt :seon.code.var/optional-input)))
         (when (dh :seon.code.var/output) (scan-io! (d/datoms db :aevt :seon.code.var/output)))
         (into {}
               (for [[nsym m] @freq]
                 [nsym (->> m (sort-by (fn [[kns w]] [(- w) kns])) (map first) (take max-n) vec)])))))))

(defn- compute-ns-enrichment [db main-db ns-ent agent]
  (let [nsym (:seon.code.ns/name ns-ent)
        dh (db-schema db)
        vars (if (and nsym (dh :seon.code.var/ns))
               (->> (d/datoms db :avet :seon.code.var/ns nsym)
                    (map (fn [dt]
                           (reduce (fn [m vdt]
                                     (case (:a vdt)
                                       (:seon.code.var/name :seon.code.var/arglists :seon.code.var/doc
                                        :seon.code.var/schema :seon.code.var/private? :seon.code.var/test?)
                                       (assoc m (:a vdt) (:v vdt)) m))
                                   {} (d/datoms db :eavt (:e dt)))))
                    (sort-by #(str (:seon.code.var/name %))) vec)
               [])
        pub (filterv #(and (not (:seon.code.var/private? %)) (not (:seon.code.var/test? %))) vars)
        priv (mapv #(name (:seon.code.var/name %)) (filter #(and (:seon.code.var/private? %) (not (:seon.code.var/test? %))) vars))
        own-tests (mapv :seon.code.var/name (filter :seon.code.var/test? vars))
        nid (or (:db/id ns-ent) (when (and nsym (dh :seon.code.ns/name)) (:e (first (d/datoms db :avet :seon.code.ns/name nsym)))))
        kids (if (and nid (dh :seon.code.ns/parent))
               (->> (d/datoms db :avet :seon.code.ns/parent nid)
                    (map #(d/pull db '[:seon.code.ns/name :seon.code.ns/doc :seon.code.ns/path] (:e %)))
                    (filter :seon.code.ns/name) (sort-by #(str (:seon.code.ns/name %))) vec)
               [])
        router? (boolean (and (seq kids) (or (empty? vars) (rdata/router-agent? agent))))
        r-tests (when (and (not router?) (empty? own-tests) (seq vars)) (fast-reaching-tests db vars))
        tlist (if (seq own-tests) own-tests (or r-tests []))
        tnss (->> tlist (keep #(when-let [s (namespace %)] (symbol s))) distinct (take 2) vec)
        test-reqs (when (and (not router?) (empty? own-tests) (seq tnss) (dh :seon.code.form/key))
                    (vec (keep (fn [tns]
                                 (let [as (ns-require-aliases (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (str tns "#ns")])))]
                                   (when (seq as) (str tns " " (str/join " " (take 8 as))))))
                               tnss)))
        req-al (when (and (not router?) nsym (dh :seon.code.form/key))
                 (ns-require-aliases (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))))
        caller-nss (when (and (not router?) (seq pub) (dh :seon.code.var/refs))
                     (let [ceids (into #{} (comp (mapcat #(d/datoms db :avet :seon.code.var/refs (:seon.code.var/name %))) (map :e)) pub)]
                       (->> ceids (keep #(:v (first (d/datoms db :eavt % :seon.code.var/ns))))
                            (remove #(= % nsym)) distinct sort (take 8) vec)))
        docs (when (and (not router?) nsym) (fast-linked-docs db nsym (assoc ns-ent :db/id nid)))
        wschs (when (and (not router?) nsym) (ns-working-schemas db nsym 12))
        feids (when (and (not router?) nsym (dh :seon.code.form/ns)) (mapv :e (d/datoms db :avet :seon.code.form/ns nsym)))
        est-tok (when (seq feids) (quot (reduce + 0 (map #(count (str (:v (first (d/datoms db :eavt % :seon.code.form/src))))) feids)) 4))
        scope-match? (and agent (= nsym (:seon.agent/scope agent)) (not (rdata/router-agent? agent)))
        alerts (when (and scope-match? nsym)
               (if (and (empty? vars) (empty? kids))
                 (heff/static-efficiency-warnings db nsym)
                 (cond-> []
                   (and est-tok (> est-tok 8000))
                   (conj {:class :oversized-namespace :severity :warning :kind :static :ns nsym
                          :subject (str nsym) :message (str "Namespace " nsym " (~" est-tok " tok > 8000).")}))))
        rich-kids (when (seq kids)
                    (let [sprefs (when router? (all-ns-schema-prefixes db 3 (keep :seon.code.ns/name kids)))
                          ag-map (when (and router? main-db ((db-schema main-db) :seon.agent/id))
                                   (into {} (keep (fn [dt]
                                                    (let [a (d/pull main-db '[:seon.agent/id :seon.agent/scope] (:e dt))]
                                                      (when (and (:seon.agent/scope a)
                                                                 (not (str/starts-with? (str (namespace (:seon.agent/id a))) "example")))
                                                        [(:seon.agent/scope a) (:seon.agent/id a)])))
                                                  (d/datoms main-db :aevt :seon.agent/id))))]
                      (mapv (fn [c]
                              (let [cn (:seon.code.ns/name c)
                                    vc (if (and cn (dh :seon.code.var/ns))
                                         (count (d/datoms db :avet :seon.code.var/ns cn)) 0)]
                                (assoc c :var-count (long vc)
                                       :schema-prefixes (get sprefs cn)
                                       :specialist (get ag-map cn))))
                            kids)))]
    (cond-> {:seon.ui/ns-var-count (count vars) :seon.ui/router-view? router?}
      (seq req-al) (assoc :seon.ui/ns-req-aliases req-al)
      (seq pub) (assoc :seon.ui/ns-pub-vars pub)
      (seq priv) (assoc :seon.ui/ns-priv-vars priv)
      (seq tlist) (assoc :seon.ui/ns-covering-tests tlist :seon.ui/ns-own-tests? (boolean (seq own-tests)))
      (seq test-reqs) (assoc :seon.ui/ns-test-reqs test-reqs)
      (seq caller-nss) (assoc :seon.ui/ns-caller-nss caller-nss)
      (seq docs) (assoc :seon.ui/ns-linked-docs docs)
      (seq wschs) (assoc :seon.ui/ns-working-schemas wschs)
      (seq alerts) (assoc :seon.ui/ns-static-alerts alerts)
      (seq rich-kids) (assoc :seon.ui/ns-children rich-kids)
      (seq pub) (assoc :seon.ui/ns-hygiene
                       {:pub-n (count pub)
                        :doc-n (count (remove #(str/blank? (str (:seon.code.var/doc %))) pub))
                        :schemed-n (count (filter :seon.code.var/schema pub))
                        :tests-n (count tlist)
                        :est-tokens (long (or est-tok 0))}))))

(defn enrich-ns-params
  "Commit-id cached rich namespace metadata for `seon.render.entities.work/render-ns-full`
   (Turn-0 `Requires:` aliases, Public API, Private helpers, Covering tests, Linked Docs, Working Schemas,
   Static Health Alerts, Hygiene, and Router Subsystem Table)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :map [:maybe :map]] :map]
   :cost/ticks 15000000}
  [db main-db ns-ent agent]
  (let [nsym (:seon.code.ns/name ns-ent)
        sig (db-cache-sig db)
        msig (when (and main-db (not= main-db db)) (db-cache-sig main-db))
        ck (when (and sig nsym)
             [sig msig nsym (boolean (rdata/router-agent? agent)) (= nsym (:seon.agent/scope agent))])]
    (if ck
      (store/lru-get! ns-enrich-cache 64 ck #(compute-ns-enrichment db main-db ns-ent agent))
      (compute-ns-enrichment db main-db ns-ent agent))))

(defn enrich-entity-params
  "Resolves auxiliary display data & `:db.type/ref` target labels for entity `ent` on `db`.
   3rd arg `focal-or-opts` may be a boolean (`focal?`) or options map `{:focal? :rich-ns? :main-db :agent :scope}`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map] :any] :map]]
   :cost/ticks 15000000}
  ([db ent] (enrich-entity-params db ent false))
  ([db ent focal-or-opts]
   (if-not (and db (map? ent))
     {}
     (let [opts (if (map? focal-or-opts) focal-or-opts {:focal? (boolean focal-or-opts)})
           focal? (boolean (:focal? opts))
           rich-ns? (boolean (or focal? (:rich-ns? opts)))
           mdb (or (:main-db opts) db)
           ag (:agent opts)
           scope-ns (when-let [s (or (:scope opts) (:seon.agent/scope ag))] (str s))
           dh (db-schema db)
           src (when-let [fk (and (dh :seon.code.form/key) (:seon.code.var/form ent))]
                 (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fk])))
           fix (when-let [ek (:seon.error/kind ent)] (find-matching-fix db ek))
           callers (when (and focal? (dh :seon.code.var/refs) (:seon.code.var/name ent))
                     (->> (take 12 (d/datoms db :avet :seon.code.var/refs (:seon.code.var/name ent)))
                          (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                          distinct sort vec not-empty))
           sks (when-let [sk (:seon.schema/key ent)]
                 (let [sdh (get dh sk)]
                   {:keys (resolve-attr-values db (:seon.schema/keys ent) :seon.schema/key 16)
                    :opt (resolve-attr-values db (:seon.schema/optional-keys ent) :seon.schema/key 16)
                    :refs (resolve-attr-values db (:seon.schema/ref-targets ent) :seon.schema/key 12)
                    :field-tags (schema-field-tags db (:seon.schema/form ent))
                    :dh-meta (when sdh {:value-type (:db/valueType sdh) :cardinality (:db/cardinality sdh)})
                    :used-by (schema-touching-vars db sk (:seon.schema/form ent) scope-ns)}))
           drefs (when (:seon.code.doc/id ent) (resolve-doc-refs db (:seon.code.doc/references ent) 12))
           r-schs (when (:seon.rule/id ent) (resolve-attr-values db (:seon.rule/schema ent) :seon.schema/key 12))
           r-fns (when (:seon.rule/id ent) (resolve-attr-values db (:seon.rule/fn ent) :seon.code.var/name 12))
           t-extra (when-let [tid (:seon.task/id ent)] (task-lineage-refs mdb tid))
           ns-extra (when (and rich-ns? (:seon.code.ns/name ent)) (enrich-ns-params db mdb ent ag))]
       (cond-> (merge {} t-extra ns-extra)
         (and (string? src) (not (str/blank? src))) (assoc :seon.code.form/src src)
         (and (map? fix) (seq (dissoc fix :db/id))) (assoc :seon.fix/entity fix)
         callers (assoc :seon.ui/show-callers? true :seon.ui/callers callers)
         (:keys sks) (assoc :seon.ui/schema-keys (:keys sks))
         (:opt sks) (assoc :seon.ui/schema-optional-keys (:opt sks))
         (:refs sks) (assoc :seon.ui/schema-ref-targets (:refs sks))
         (:field-tags sks) (assoc :seon.ui/schema-field-tags (:field-tags sks))
         (:dh-meta sks) (assoc :seon.ui/schema-dh-meta (:dh-meta sks))
         (:used-by sks) (assoc :seon.ui/schema-used-by (:used-by sks))
         drefs (assoc :seon.ui/doc-refs drefs)
         r-schs (assoc :seon.ui/rule-schemas r-schs)
         r-fns (assoc :seon.ui/rule-fns r-fns))))))