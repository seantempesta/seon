(ns seon.render.print
  "Unified Zero-Clipping Schema Self-Printer, 10k Smart Previews, and `(user/expand! ...)` engine:
   - Structural descriptors (`describe-value`, `describe-with-index`) via `seon.schema.index` & `seon.store.query`.
   - Markdown (`format-value-md`), Hiccup (`format-value-hiccup`), `(user/expand! ...)`, and `preview-rules`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.print.emit :as pemit]
            [seon.render.print.expand :as pexp]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(defn- valid-ix? [ix]
  (and (map? ix) (map? (:entity-schemas ix)) (map? (:forms ix)) (map? (:value-schemas ix))))

(defn- db-summary [db]
  (let [cfg (:config db) sid (get-in cfg [:store :id])]
    (cond-> {:db-type (cond (instance? datahike.db.AsOfDB db) :as-of
                            (instance? datahike.db.HistoricalDB db) :history
                            :else :db)
             :branch (get cfg :branch :main) :max-tx (:max-tx db)
             :max-eid (:max-eid db) :attrs-count (count (:schema db))}
      sid (assoc :store-id sid)
      (:time-point db) (assoc :time-point (:time-point db)))))

(defn- handle-info [x]
  (let [thk (when (instance? clojure.lang.IMeta x) (:seon.print/kind (meta x)))]
    (cond
      (contains? #{:datahike/db :datahike/as-of-db :datahike/historical-db} thk)
      {:kind :datahike/db :schema :seon.host/db :summary (if (map? x) (dissoc x :seon.print/kind) {:value x})}
      (= :datahike/conn thk)
      {:kind :datahike/conn :schema :seon.host/conn :summary (if (map? x) (dissoc x :seon.print/kind) {:value x})}
      (or (instance? datahike.db.DB x) (instance? datahike.db.AsOfDB x) (instance? datahike.db.HistoricalDB x))
      {:kind :datahike/db :schema :seon.host/db :summary (db-summary x)}
      (instance? datahike.connector.Connection x)
      {:kind :datahike/conn :schema :seon.host/conn :summary (assoc (db-summary @x) :conn? true)}
      :else nil)))

(defn- safe-count [x]
  (cond
    (or (string? x) (map? x) (set? x) (instance? clojure.lang.Counted x)) {:total (count x) :exact? true}
    (coll? x) (let [c (bounded-count 100001 x)] {:total (min c 100000) :exact? (<= c 100000)})
    :else {:total 1 :exact? true}))

(defn- detect-elem-info [ix sample]
  (cond
    (empty? sample) {:element-schema nil :tabular? false :columns nil}
    (every? #(instance? datahike.datom.Datom %) sample) {:element-schema :datahike/datom :tabular? false :columns nil}
    (every? map? sample)
    (let [ss (when (valid-ix? ix) (mapv #(six/most-specific ix %) sample))
          s0 (first ss)
          esh (when (and (qualified-keyword? s0) (every? #(= s0 %) ss)) s0)
          tab? (and (>= (count sample) 2) (>= (count (apply set/intersection (map #(set (keys %)) sample))) 2))]
      {:element-schema esh :tabular? tab?
       :columns (when tab? (->> sample (mapcat keys) distinct (sort-by str) (take 12) vec))})
    :else {:element-schema nil :tabular? false :columns nil}))

(defn- compact-nested [desc-fn ix v opts next-path]
  (let [depth (long (:depth opts 0)) max-d (long (:max-depth opts 2)) nlim (long (:nested-limit opts 5))
        copts (assoc opts :depth (inc depth) :limit nlim :offset 0 :path next-path)]
    (cond
      (or (nil? v) (boolean? v) (number? v) (keyword? v) (symbol? v) (uuid? v)) v
      (instance? datahike.datom.Datom v) [(:e v) (:a v) (:v v) (:tx v)]
      (handle-info v) (let [{:keys [kind summary]} (handle-info v)] (assoc summary :seon.print/nested true :kind kind))
      (string? v) (if (<= (count v) 180) v
                    {:seon.print/nested true :kind :string :total (count v) :preview (pemit/snippet-str v 120)
                     :more? true :expand-hint (pexp/expand-hint-str (assoc opts :path next-path) 0 40)})
      (coll? v) (let [{:keys [total]} (safe-count v)]
                  (cond (zero? total) v
                        (and (< depth max-d) (<= total nlim))
                        (if (map? v)
                          (into {} (map (fn [[mk mv]] [mk (compact-nested desc-fn ix mv copts (conj next-path mk))])) v)
                          (mapv (fn [idx item] (compact-nested desc-fn ix item copts (conj next-path idx))) (range) v))
                        :else (assoc (desc-fn ix v copts) :seon.print/nested true)))
      (instance? Throwable v) {:seon.print/nested true :kind :error :class (.getName (class v)) :message (.getMessage v)}
      :else (let [s (pr-str v)] (if (<= (count s) 180) v (str (subs s 0 180) "…"))))))

(defn- prioritize-map-entries [ix m facets {:keys [offset limit]}]
  (let [id-ks (when (valid-ix? ix) (keep #(get-in ix [:schema-identities (:schema %)]) facets))
        pks (distinct (concat id-ks [:db/id :id :ok? :branch :title :name :status]))]
    (if (<= (count m) 200)
      (let [pset (set pks)]
        (mapv (fn [k] [k (get m k)])
              (take limit (drop offset (concat (filter #(contains? m %) pks) (->> (keys m) (remove pset) (sort-by str)))))))
      (let [safe-lk? (or (not (sorted? m)) (empty? m) (keyword? (key (first m))))
            prios (if (and (zero? offset) safe-lk?) (vec (keep #(when-let [e (find m %)] [(key e) (val e)]) pks)) [])
            pset (into #{} (map first) prios)
            rest-es (if (zero? offset)
                      (->> (seq m) (remove #(contains? pset (key %))) (take (max 0 (- limit (count prios)))))
                      (->> (seq m) (drop offset) (take limit)))]
        (into prios (map (fn [e] [(key e) (val e)])) rest-es)))))

(defn- describe-map [desc-fn ix m opts]
  (let [{:keys [limit offset path]} opts
        raw-fs (if (valid-ix? ix) (six/facets-of ix m) [])
        ms (when (valid-ix? ix) (six/most-specific ix m))
        sch (cond (qualified-keyword? ms) ms (seq raw-fs) (:schema (first raw-fs))
                  (instance? clojure.lang.IMeta m) (:seon/schema (meta m)) :else nil)
        fs (mapv :schema raw-fs)
        total (count m)
        sliced (prioritize-map-entries ix m raw-fs opts)
        entries (mapv (fn [[k v]] [k (compact-nested desc-fn ix v opts (conj path k))]) sliced)
        pmap (if (<= (count entries) 32) (apply array-map (mapcat identity entries)) (into {} entries))
        shown (count entries)
        more? (> total (+ offset shown))
        nested-more? (boolean (some #(and (map? (second %)) (:seon.print/nested (second %)) (:more? (second %))) entries))]
    (cond-> {:kind (cond (seq raw-fs) (:kind (first raw-fs)) sch :value :else :map)
             :schema sch :facets fs :total total :offset offset :limit limit
             :preview pmap :key-order (mapv first sliced) :more? more? :remaining (max 0 (- total (+ offset shown)))}
      (seq raw-fs) (assoc :facet-details raw-fs)
      (map? ms) (assoc :ambiguous (:ambiguous ms))
      nested-more? (assoc :nested-more? true)
      more? (assoc :expand-hint (pexp/expand-hint-str opts (+ offset shown) limit)))))

(defn- describe-seq-coll [desc-fn ix coll opts]
  (let [{:keys [limit offset path]} opts
        {:keys [total exact?]} (safe-count coll)
        kind (cond (pexp/real-vec? coll) :vector (set? coll) :set :else :seq)
        raw-slice (cond (zero? limit) []
                        (and (pexp/real-vec? coll) (< offset total)) (subvec coll offset (min total (+ offset limit)))
                        :else (->> coll (drop offset) (take limit) vec))
        {:keys [element-schema tabular? columns]} (detect-elem-info ix (take 5 raw-slice))
        preview (vec (map-indexed #(compact-nested desc-fn ix %2 opts (if (= kind :vector) (conj path (+ offset %1)) path)) raw-slice))
        shown (count preview)
        more? (or (not exact?) (> total (+ offset shown)))
        msch (when (instance? clojure.lang.IMeta coll) (:seon/schema (meta coll)))]
    (cond-> {:kind kind :schema msch :facets (if msch [msch] []) :total total :exact? exact?
             :offset offset :limit limit :preview preview :more? more? :remaining (max 0 (- total (+ offset shown)))}
      element-schema (assoc :element-schema element-schema)
      tabular? (assoc :tabular? true :columns columns)
      more? (assoc :expand-hint (pexp/expand-hint-str opts (+ offset shown) limit)))))

(defn- describe-string [^String s opts]
  (let [{:keys [max-chars limit offset]} opts
        nc (count s) ls (str/split-lines s) nl (count ls)
        exp? (or (pos? offset) (contains? opts :limit))]
    (if (and (not exp?) (<= nc max-chars))
      {:kind :string :schema :string :facets [] :total nc :lines nl :offset 0 :limit nc :preview s :more? false :remaining 0}
      (let [lines? (and (> nl 1) (not (and exp? (= 1 nl))))
            lim (if exp? limit (if lines? 40 (quot max-chars 2)))
            tn (if exp? 0 (if lines? (min 15 (max 0 (- nl lim))) (min (quot max-chars 4) (max 0 (- nc (+ offset lim))))))
            [hd tl om] (if lines?
                         (let [h (vec (take lim (drop offset ls))) t (when (pos? tn) (vec (take-last tn ls)))]
                           [h (or t []) (max 0 (- nl offset (count h) (count t)))])
                         (let [h (subs s (min offset nc) (min nc (+ offset lim)))
                               t (if (pos? tn) (subs s (- nc tn)) "")]
                           [h t (max 0 (- nc offset (count h) (count t)))]))]
        (cond-> {:kind :string :schema :string :facets [] :total nc :lines nl :offset offset :limit lim
                 :preview (if lines? {:head hd :tail tl :omitted-lines om} {:head hd :tail tl :omitted-chars om})
                 :more? (pos? om) :remaining om}
          (pos? om) (assoc :expand-hint (pexp/expand-hint-str opts (+ offset (count hd)) lim)))))))

(defn- lookup-ref-schema [db ix attr]
  (or (when (valid-ix? ix) (get-in ix [:identity-schemas attr]))
      (when (= attr :seon.health/summary) :seon.health/report)
      (when (and db (get-in db [:schema attr :db/unique])) :seon.store/ref)))

(defn- describe-lookup-ref [db [attr id-val] target-sch]
  (let [dt (when (and db (not= attr :seon.health/summary) (contains? (:schema db) attr))
             (first (d/datoms db :avet attr id-val)))
        eid (:e dt)
        pulled (when eid (dissoc (d/pull db '[*] eid) :db/id))]
    (cond-> {:kind :lookup-ref :schema target-sch :facets (if target-sch [target-sch] []) :total 2 :offset 0 :limit 2
             :preview (cond-> {:attr attr :id id-val}
                        eid (assoc :exists? true :eid eid)
                        (and db (not= attr :seon.health/summary) (nil? eid)) (assoc :exists? false)
                        (seq pulled) (assoc :summary (into {} (take 6 (sort-by (comp str key) pulled)))))
             :more? false :remaining 0}
      (and eid (not= attr :seon.health/summary))
      (assoc :expand-hint (str "(d/pull (user/db) '[*] [" (pr-str attr) " " (pr-str id-val) "])")))))

(defn- describe-with-index* [ix val opts]
  (if-let [{:keys [kind schema summary]} (handle-info val)]
    {:kind kind :schema schema :facets [] :total 1 :offset 0 :limit 1 :preview summary
     :more? false :remaining 0 :expand-hint "(user/expand! [nil :seon.task/id nil] {:limit 20})"}
    (cond
      (nil? val) {:kind :nil :schema nil :facets [] :total 0 :offset 0 :limit 0 :preview nil :more? false :remaining 0}
      (and (pexp/real-vec? val) (= 2 (count val)) (qualified-keyword? (first val)) (lookup-ref-schema nil ix (first val)))
      (describe-lookup-ref nil val (lookup-ref-schema nil ix (first val)))
      (map? val) (describe-map describe-with-index* ix val opts)
      (or (pexp/real-vec? val) (set? val) (seq? val) (sequential? val)) (describe-seq-coll describe-with-index* ix val opts)
      (string? val) (describe-string val opts)
      (instance? Throwable val)
      {:kind :error :schema :seon.host/throwable :facets [] :total 1 :offset 0 :limit 1
       :preview {:class (.getName (class val)) :message (or (.getMessage val) (str val)) :data (ex-data val)}
       :more? false :remaining 0}
      (or (boolean? val) (number? val) (keyword? val) (symbol? val) (uuid? val) (char? val))
      {:kind :scalar :schema nil :facets [] :total 1 :offset 0 :limit 1 :preview val :more? false :remaining 0}
      :else
      {:kind :object :schema nil :facets [] :total 1 :offset 0 :limit 1 :type (.getName (class val))
       :preview (pemit/snippet-str (pr-str val) 400) :more? false :remaining 0})))

(defn describe-with-index
  "Pure structural descriptor for `val` using compiled `seon.schema.index` `ix`."
  {:malli/schema [:=> [:cat :map :any :map] :map] :cost/ticks 5000000 :cost/bytes 4194304}
  [ix val opts]
  (describe-with-index* ix val (pexp/norm-opts opts)))

(defn describe-value
  "Pure structural descriptor `{:kind :schema :facets :total :preview :more? :expand-hint}` (zero silent clipping)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :map] [:=> [:cat [:maybe :seon.host/db] :any :map] :map]]
   :cost/ticks 5000000 :cost/bytes 4194304}
  ([db val] (describe-value db val {}))
  ([db val opts]
   (let [ix (schema/entity-index db) nopts (pexp/norm-opts opts)]
     (if (and db (pexp/real-vec? val) (= 2 (count val)) (qualified-keyword? (first val))
              (lookup-ref-schema db ix (first val)))
       (describe-lookup-ref db val (lookup-ref-schema db ix (first val)))
       (describe-with-index* ix val nopts)))))

(defn format-descriptor-md
  "Renders a `describe-value` descriptor map into clean Markdown."
  {:malli/schema [:=> [:cat :map :map] :string] :cost/ticks 5000000 :cost/bytes 4194304}
  [desc opts] (pemit/format-descriptor-md desc opts))

(defn format-value-md
  "Renders `val` into clean, token-efficient Markdown via `describe-value` (zero silent clipping)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :string] [:=> [:cat [:maybe :seon.host/db] :any :map] :string]]
   :cost/ticks 5000000 :cost/bytes 4194304}
  ([db val] (format-value-md db val {}))
  ([db val opts] (pemit/format-descriptor-md (describe-value db val opts) (if (map? opts) opts {}))))

(defn format-descriptor-hiccup
  "Renders a `describe-value` descriptor map into Reagent/Hiccup."
  {:malli/schema [:=> [:cat :map :map] :any] :cost/ticks 5000000 :cost/bytes 4194304}
  [desc opts] (pemit/format-descriptor-hiccup desc opts))

(defn format-value-hiccup
  "Renders `val` into structured Reagent/Hiccup via `describe-value` (zero silent clipping)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :any] [:=> [:cat [:maybe :seon.host/db] :any :map] :any]]
   :cost/ticks 5000000 :cost/bytes 4194304}
  ([db val] (format-value-hiccup db val {}))
  ([db val opts] (pemit/format-descriptor-hiccup (describe-value db val opts) (if (map? opts) opts {}))))

(defn slice-value
  "Pure path-drill, filter, reverse, and offset/limit slicer."
  {:malli/schema [:=> [:cat :any :map] :map] :cost/ticks 5000000 :cost/bytes 4194304}
  [val opts] (pexp/slice-value val opts))

(defn expand-result
  "Expands a `result/e<n>` symbol, Datahike pattern `[:attr val]`/`[e a v]`, or map/coll by `{:offset :limit :key :path :filter :reverse?}`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :map] [:=> [:cat [:maybe :seon.host/db] :any :map] :map]]
   :cost/ticks 5000000 :cost/bytes 4194304}
  ([db target] (expand-result db target {}))
  ([db target opts]
   (let [nopts (pexp/norm-opts opts)]
     (cond
       (and (symbol? target) (= "result" (namespace target)))
       (if-let [v (when-let [rns (find-ns 'result)] (ns-resolve rns (symbol (name target))))]
         (if (bound? v)
           (expand-result db @v (assoc nopts :handle target))
           {:ok? false :error :result-unbound :target target :message (str "Result var " target " is unbound.")})
         {:ok? false :error :result-not-found :target target :message (str "No live result var found for " target ".")})
       (pexp/db-query-pattern? db target) (pexp/expand-db-pattern db target nopts)
       :else
       (let [sl (pexp/slice-value target nopts)
             eopts (cond-> nopts (seq (:path sl)) (assoc :path (:path sl)))]
         (if (contains? sl :preview)
           (let [next-off (+ (:offset sl) (count (:preview sl)))
                 ei (when (not= :map (:kind sl)) (detect-elem-info (schema/entity-index db) (take 5 (:preview sl))))]
             (cond-> (merge {:ok? true} sl)
               (:element-schema ei) (assoc :element-schema (:element-schema ei))
               (:more? sl) (assoc :expand-hint (pexp/expand-hint-str eopts next-off (:limit sl)))))
           (assoc (describe-value db (:value sl) eopts) :ok? true :path (:path sl))))))))

(defn ^:seon.flow/edge install-user-helpers!
  "Interns `user/expand!` with `:doc`, `:arglists`, and `:seon.eval/budget-override`."
  {:malli/schema [:=> [:cat :seon.host/ns :any] :any] :cost/ticks 500000}
  [u get-agent-ctx-fn]
  (let [uns (cond (instance? clojure.lang.Namespace u) u (symbol? u) (create-ns u) :else (create-ns 'user))
        v (intern uns 'expand!
                  (fn ([target] ((resolve 'user/expand!) target {}))
                    ([target opts]
                     (let [ctx (when (fn? get-agent-ctx-fn) (get-agent-ctx-fn))
                           db (when-let [c (:conn ctx)] @c)]
                       (expand-result db target opts)))))]
    (alter-meta! v assoc
                 :arglists '([target] [target opts])
                 :doc "Expand a live `result/e<n>` handle, Datahike pattern `[:attr val]` / `[e a v]`, or collection/map by `{:offset :limit :key :path :filter :reverse?}`."
                 :seon.eval/budget-override {:ticks 500000000 :bytes 1073741824})
    v))

(defn preview-rules
  "Declarative `:seon.rule/*` rule maps for smart collection previews and multi-facet entities."
  {:malli/schema [:=> [:cat] [:vector :map]] :cost/ticks 500000}
  []
  [{:seon.rule/id :rule.preview/large-collection
    :seon.rule/doc "Force bounded smart preview + expand hint whenever a collection/query result exceeds 20 items (zero silent clipping)."
    :seon.rule/category :present :seon.rule/stratum 36 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/total ?n] [:guard (> ?n 20)] [:optional [?e :ctx/limit ?lim]]]
    :seon.rule/assert '[[?e :view/tag :tag/preview-sliced]
                        [?e :view.param/preview-limit (if (some? ?lim) ?lim 20)]
                        [?e :view.param/show-expand-hint? true]
                        [?e :view/layout :compact-card]
                        [?e :ctx/why (str "Large collection (" ?n " items) — bounded smart preview")]]}
   {:seon.rule/id :rule.preview/multi-facet-entity
    :seon.rule/doc "Tag entities satisfying multiple Malli schemas via `seon.schema.index/facets-of` and surface extra facet metadata."
    :seon.rule/category :present :seon.rule/stratum 36 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind ?primary] [:count ?n [:facet ?e ?sch]] [:guard (>= ?n 2)] [:facet ?e ?sch] [:guard (not= ?sch ?primary)]]
    :seon.rule/assert '[[?e :view/tag :tag/multi-facet]
                        [?e :view.param/facet-count ?n]
                        [?e :view.param/extra-facet ?sch]
                        [?e :ctx/why (str "Multi-facet entity (" ?n " schemas, also " ?sch ")")]]}])
