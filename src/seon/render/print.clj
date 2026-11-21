(ns seon.render.print
  "Unified Zero-Clipping Schema Self-Printer, 10k Smart Previews, and `(user/expand! ...)` engine:
   - Pure structural descriptor (`describe-value` / `describe-with-index`) driven by `seon.schema.index`
     (`facets-of`, `most-specific`) and `seon.store.query` (`preview-slice`) with zero silent clipping.
   - Single-source Markdown (`format-value-md`) and Hiccup (`format-value-hiccup`) projection.
   - Interactive `(user/expand! target opts)` for `result/e<n>` handles, Datahike patterns, and nested paths."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store.query :as sq]))

(def ^:private default-limit 20)
(def ^:private default-nested-limit 5)
(def ^:private default-max-chars 8000)
(def ^:private default-max-depth 2)

(defn- valid-ix? [ix]
  (and (map? ix)
       (map? (:entity-schemas ix))
       (map? (:forms ix))
       (map? (:value-schemas ix))))

(defn- datahike-db? [x]
  (or (instance? datahike.db.DB x)
      (instance? datahike.db.AsOfDB x)
      (instance? datahike.db.HistoricalDB x)))

(defn- datahike-conn? [x]
  (instance? datahike.connector.Connection x))

(defn- tagged-handle-kind [x]
  (when (instance? clojure.lang.IMeta x)
    (:seon.print/kind (meta x))))

(defn- real-vector? [x]
  (and (vector? x) (not (map-entry? x))))

(defn- safe-count [x]
  (cond
    (string? x) {:total (count x) :exact? true}
    (instance? clojure.lang.Counted x) {:total (count x) :exact? true}
    (or (map? x) (set? x)) {:total (count x) :exact? true}
    (coll? x) (let [lim 100000
                    c (bounded-count (inc lim) x)]
                (if (> c lim)
                  {:total lim :exact? false}
                  {:total c :exact? true}))
    :else {:total 1 :exact? true}))

(defn- norm-opts [opts]
  (let [m (if (map? opts) opts {})
        lim (let [l (:limit m)] (if (integer? l) (max 0 (min 10000 (long l))) default-limit))
        off (let [o (:offset m)] (if (integer? o) (max 0 (long o)) 0))
        mc  (let [c (:max-chars m)] (if (integer? c) (max 256 (long c)) default-max-chars))
        md  (let [d (:max-depth m)] (if (integer? d) (max 0 (min 6 (long d))) default-max-depth))
        nl  (let [n (:nested-limit m)] (if (integer? n) (max 1 (min 50 (long n))) default-nested-limit))
        dp  (let [d (:depth m)] (if (integer? d) (max 0 (long d)) 0))
        path (when (sequential? (:path m)) (vec (:path m)))]
    (assoc m
           :limit lim
           :offset off
           :max-chars mc
           :max-depth md
           :nested-limit nl
           :depth dp
           :path (or path []))))

(defn- expand-hint-str [{:keys [handle path filter reverse?]} next-offset limit]
  (let [h-part (cond
                 (and (symbol? handle) (not= 'result/e? handle)) (str "'" handle)
                 (string? handle) (if (str/starts-with? handle "result/") (str "'" handle) (pr-str handle))
                 :else "'result/eN")
        safe-flt (when (and (some? filter) (not (fn? filter)))
                   (if (instance? java.util.regex.Pattern filter)
                     (str filter)
                     filter))
        opt-map (cond-> {:offset next-offset :limit limit}
                  (seq path) (assoc :path (vec path))
                  (some? safe-flt) (assoc :filter safe-flt)
                  reverse? (assoc :reverse? true))]
    (str "(user/expand! " h-part " " (pr-str opt-map) ")")))

(defn- detect-elem-info [ix sample]
  (if (empty? sample)
    {:element-schema nil :tabular? false :columns nil}
    (cond
      (every? #(instance? datahike.datom.Datom %) sample)
      {:element-schema :datahike/datom :tabular? false :columns nil}

      (every? map? sample)
      (let [schemas (when (valid-ix? ix)
                      (mapv #(six/most-specific ix %) sample))
            first-s (first schemas)
            elem-sch (when (and (qualified-keyword? first-s)
                                (every? #(= first-s %) schemas))
                       first-s)
            key-sets (map #(set (keys %)) sample)
            shared (when (seq key-sets) (apply set/intersection key-sets))
            tabular? (and (>= (count sample) 2) (>= (count shared) 2))
            cols (when tabular?
                   (->> sample (mapcat keys) distinct (sort-by str) (take 12) vec))]
        {:element-schema elem-sch :tabular? tabular? :columns cols})

      :else
      {:element-schema nil :tabular? false :columns nil})))

(defn- snippet-str [^String s max-len]
  (let [clean (str/replace s #"\s+" " ")]
    (if (> (count clean) max-len)
      (str (subs clean 0 max-len) "…")
      clean)))

(defn- db-summary-map [db]
  (let [cfg (:config db)
        br (get cfg :branch :main)
        sid (get-in cfg [:store :id])]
    (cond-> {:db-type (cond (instance? datahike.db.AsOfDB db) :as-of
                            (instance? datahike.db.HistoricalDB db) :history
                            :else :db)
             :branch br
             :max-tx (:max-tx db)
             :max-eid (:max-eid db)
             :attrs-count (count (:schema db))}
      sid (assoc :store-id sid)
      (:time-point db) (assoc :time-point (:time-point db)))))

(defn- conn-summary-map [conn]
  (assoc (db-summary-map @conn) :conn? true))

(declare describe-with-index*)

(defn- compact-nested [ix v opts next-path]
  (let [depth (long (:depth opts 0))
        max-depth (long (:max-depth opts default-max-depth))
        nlim (long (:nested-limit opts default-nested-limit))
        child-opts (assoc opts :depth (inc depth) :limit nlim :offset 0 :path next-path)]
    (cond
      (nil? v) nil
      (or (boolean? v) (number? v) (keyword? v) (symbol? v) (uuid? v)) v
      (instance? datahike.datom.Datom v) [(:e v) (:a v) (:v v) (:tx v)]
      (datahike-db? v) (assoc (db-summary-map v) :seon.print/nested true :kind :datahike/db)
      (datahike-conn? v) (assoc (conn-summary-map v) :seon.print/nested true :kind :datahike/conn)
      (string? v) (if (<= (count v) 180)
                    v
                    {:seon.print/nested true
                     :kind :string
                     :total (count v)
                     :preview (snippet-str v 120)
                     :more? true
                     :expand-hint (expand-hint-str (assoc opts :path next-path) 0 40)})
      (coll? v)
      (let [{:keys [total]} (safe-count v)]
        (cond
          (zero? total) v
          (and (< depth max-depth) (<= total nlim))
          (if (map? v)
            (into {} (map (fn [[mk mv]] [mk (compact-nested ix mv child-opts (conj next-path mk))])) v)
            (mapv (fn [idx item] (compact-nested ix item child-opts (conj next-path idx)))
                  (range) v))
          :else
          (assoc (describe-with-index* ix v child-opts) :seon.print/nested true)))
      (instance? Throwable v)
      {:seon.print/nested true :kind :error :class (.getName (class v)) :message (.getMessage v)}
      :else
      (let [s (pr-str v)]
        (if (<= (count s) 180) v (str (subs s 0 180) "…"))))))

(defn- prioritize-map-entries [ix m facets {:keys [offset limit]}]
  (let [total (count m)
        id-attrs (when (valid-ix? ix)
                   (keep #(get-in ix [:schema-identities (:schema %)]) facets))
        priority-ks (distinct (concat id-attrs [:db/id :id :ok? :branch :title :name :status]))]
    (if (<= total 200)
      (let [pset (set priority-ks)
            present-prio (filter #(contains? m %) priority-ks)
            rest-ks (->> (keys m) (remove pset) (sort-by str))
            ordered (concat present-prio rest-ks)
            sliced-ks (->> ordered (drop offset) (take limit) vec)]
        (mapv (fn [k] [k (get m k)]) sliced-ks))
      (let [safe-lookup? (or (not (sorted? m)) (empty? m) (keyword? (key (first m))))
            prio-pairs (if (and (zero? offset) safe-lookup?)
                         (vec (keep #(when-let [e (find m %)] [(key e) (val e)]) priority-ks))
                         [])
            prio-set (into #{} (map first) prio-pairs)
            need (max 0 (- limit (count prio-pairs)))
            rest-pairs (if (zero? offset)
                         (->> (seq m) (remove #(contains? prio-set (key %))) (take need) (mapv (fn [e] [(key e) (val e)])))
                         (->> (seq m) (drop offset) (take limit) (mapv (fn [e] [(key e) (val e)]))))]
        (into prio-pairs rest-pairs)))))

(defn- describe-map [ix m opts]
  (let [{:keys [limit offset path]} opts
        raw-facets (if (valid-ix? ix) (six/facets-of ix m) [])
        ms (when (valid-ix? ix) (six/most-specific ix m))
        ambig (when (map? ms) (:ambiguous ms))
        schema-kw (cond
                    (qualified-keyword? ms) ms
                    (seq raw-facets) (:schema (first raw-facets))
                    (instance? clojure.lang.IMeta m) (:seon/schema (meta m))
                    :else nil)
        facets (mapv :schema raw-facets)
        kind (cond
               (seq raw-facets) (:kind (first raw-facets))
               schema-kw :value
               :else :map)
        total (count m)
        sliced (prioritize-map-entries ix m raw-facets opts)
        key-order (mapv first sliced)
        preview-entries (mapv (fn [[k v]]
                                [k (compact-nested ix v opts (conj path k))])
                              sliced)
        preview-map (if (<= (count preview-entries) 32)
                      (apply array-map (mapcat identity preview-entries))
                      (into {} preview-entries))
        shown (count preview-entries)
        more? (> total (+ offset shown))
        remaining (max 0 (- total (+ offset shown)))
        nested-more? (boolean (some #(and (map? (second %)) (:seon.print/nested (second %)) (:more? (second %)))
                                    preview-entries))]
    (cond-> {:kind kind
             :schema schema-kw
             :facets facets
             :total total
             :offset offset
             :limit limit
             :preview preview-map
             :key-order key-order
             :more? more?
             :remaining remaining}
      (seq raw-facets) (assoc :facet-details raw-facets)
      ambig (assoc :ambiguous ambig)
      nested-more? (assoc :nested-more? true)
      more? (assoc :expand-hint (expand-hint-str opts (+ offset shown) limit)))))

(defn- describe-seq-coll [ix coll opts]
  (let [{:keys [limit offset path]} opts
        {:keys [total exact?]} (safe-count coll)
        kind (cond (real-vector? coll) :vector (set? coll) :set :else :seq)
        raw-slice (cond
                    (zero? limit) []
                    (and (real-vector? coll) (< offset total))
                    (subvec coll offset (min total (+ offset limit)))
                    :else
                    (->> coll (drop offset) (take limit) vec))
        {:keys [element-schema tabular? columns]} (detect-elem-info ix (take 5 raw-slice))
        preview (vec (map-indexed (fn [i item]
                                    (let [idx (+ offset i)]
                                      (compact-nested ix item opts (if (= kind :vector) (conj path idx) path))))
                                  raw-slice))
        shown (count preview)
        more? (or (not exact?) (> total (+ offset shown)))
        remaining (max 0 (- total (+ offset shown)))
        meta-sch (when (instance? clojure.lang.IMeta coll) (:seon/schema (meta coll)))]
    (cond-> {:kind kind
             :schema meta-sch
             :facets (if meta-sch [meta-sch] [])
             :total total
             :exact? exact?
             :offset offset
             :limit limit
             :preview preview
             :more? more?
             :remaining remaining}
      element-schema (assoc :element-schema element-schema)
      tabular? (assoc :tabular? true :columns columns)
      more? (assoc :expand-hint (expand-hint-str opts (+ offset shown) limit)))))

(defn- describe-string [^String s opts]
  (let [{:keys [max-chars limit offset]} opts
        n-chars (count s)
        lines (str/split-lines s)
        n-lines (count lines)
        explicit-slice? (or (pos? offset) (contains? opts :limit))]
    (cond
      (and (not explicit-slice?) (<= n-chars max-chars))
      {:kind :string :schema :string :facets [] :total n-chars :lines n-lines
       :offset 0 :limit n-chars :preview s :more? false :remaining 0}

      (or (> n-lines 1) explicit-slice?)
      (if (and (> n-lines 1) (not (and explicit-slice? (= 1 n-lines))))
        (let [line-lim (if explicit-slice? limit 40)
              tail-n (if explicit-slice? 0 (min 15 (max 0 (- n-lines line-lim))))
              head-ls (vec (take line-lim (drop offset lines)))
              tail-ls (when (pos? tail-n) (vec (take-last tail-n lines)))
              shown (+ (count head-ls) (count tail-ls))
              omitted (max 0 (- n-lines offset shown))
              more? (pos? omitted)
              next-off (+ offset (count head-ls))]
          (cond-> {:kind :string :schema :string :facets [] :total n-chars :lines n-lines
                   :offset offset :limit line-lim
                   :preview {:head head-ls :tail (or tail-ls []) :omitted-lines omitted}
                   :more? more? :remaining omitted}
            more? (assoc :expand-hint (expand-hint-str opts next-off (if explicit-slice? line-lim 40)))))
        (let [char-lim (if explicit-slice? limit (quot max-chars 2))
              head-n (min char-lim (max 0 (- n-chars offset)))
              tail-n (if explicit-slice? 0 (min (quot max-chars 4) (max 0 (- n-chars (+ offset head-n)))))
              head-s (subs s (min offset n-chars) (min n-chars (+ offset head-n)))
              tail-s (if (pos? tail-n) (subs s (- n-chars tail-n)) "")
              omitted (max 0 (- n-chars offset head-n tail-n))
              more? (pos? omitted)]
          (cond-> {:kind :string :schema :string :facets [] :total n-chars :lines n-lines
                   :offset offset :limit char-lim
                   :preview {:head head-s :tail tail-s :omitted-chars omitted}
                   :more? more? :remaining omitted}
            more? (assoc :expand-hint (expand-hint-str opts (+ offset head-n) char-lim)))))

      :else
      {:kind :string :schema :string :facets [] :total n-chars :lines n-lines
       :offset 0 :limit n-chars :preview s :more? false :remaining 0})))

(defn- lookup-ref-schema [db ix attr]
  (or (when (valid-ix? ix) (get-in ix [:identity-schemas attr]))
      (when (= attr :seon.health/summary) :seon.health/report)
      (when (and db (get-in db [:schema attr :db/unique])) :seon.store/ref)))

(defn- describe-lookup-ref [db [attr id-val] target-sch]
  (let [dt (when (and db (not= attr :seon.health/summary) (contains? (:schema db) attr))
             (first (d/datoms db :avet attr id-val)))
        eid (:e dt)
        pulled (when eid (dissoc (d/pull db '[*] eid) :db/id))
        summary (when (seq pulled)
                  (into {} (take 6 (sort-by (comp str key) pulled))))]
    (cond-> {:kind :lookup-ref
             :schema target-sch
             :facets (if target-sch [target-sch] [])
             :total 2
             :offset 0
             :limit 2
             :preview (cond-> {:attr attr :id id-val}
                        eid (assoc :exists? true :eid eid)
                        (and db (not= attr :seon.health/summary) (nil? eid)) (assoc :exists? false)
                        summary (assoc :summary summary))
             :more? false
             :remaining 0}
      (and eid (not= attr :seon.health/summary))
      (assoc :expand-hint (str "(d/pull (user/db) '[*] [" (pr-str attr) " " (pr-str id-val) "])")))))

(defn- describe-with-index* [ix val opts]
  (let [thk (tagged-handle-kind val)]
    (cond
      (nil? val)
      {:kind :nil :schema nil :facets [] :total 0 :offset 0 :limit 0 :preview nil :more? false :remaining 0}

      (contains? #{:datahike/db :datahike/as-of-db :datahike/historical-db} thk)
      {:kind :datahike/db :schema :seon.host/db :facets [] :total 1 :offset 0 :limit 1
       :preview (if (map? val) (dissoc val :seon.print/kind) {:value val}) :more? false :remaining 0
       :expand-hint "(user/expand! [nil :seon.task/id nil] {:limit 20})"}

      (= :datahike/conn thk)
      {:kind :datahike/conn :schema :seon.host/conn :facets [] :total 1 :offset 0 :limit 1
       :preview (if (map? val) (dissoc val :seon.print/kind) {:value val}) :more? false :remaining 0
       :expand-hint "(user/expand! [nil :seon.task/id nil] {:limit 20})"}

      (datahike-db? val)
      {:kind :datahike/db :schema :seon.host/db :facets [] :total 1 :offset 0 :limit 1
       :preview (db-summary-map val) :more? false :remaining 0
       :expand-hint "(user/expand! [nil :seon.task/id nil] {:limit 20})"}

      (datahike-conn? val)
      {:kind :datahike/conn :schema :seon.host/conn :facets [] :total 1 :offset 0 :limit 1
       :preview (conn-summary-map val) :more? false :remaining 0
       :expand-hint "(user/expand! [nil :seon.task/id nil] {:limit 20})"}

      (and (real-vector? val) (= 2 (count val)) (qualified-keyword? (first val))
           (lookup-ref-schema nil ix (first val)))
      (describe-lookup-ref nil val (lookup-ref-schema nil ix (first val)))

      (map? val)
      (describe-map ix val opts)

      (or (real-vector? val) (set? val) (seq? val) (sequential? val))
      (describe-seq-coll ix val opts)

      (string? val)
      (describe-string val opts)

      (instance? Throwable val)
      {:kind :error :schema :seon.host/throwable :facets [] :total 1 :offset 0 :limit 1
       :preview {:class (.getName (class val)) :message (or (.getMessage val) (str val)) :data (ex-data val)}
       :more? false :remaining 0}

      (or (boolean? val) (number? val) (keyword? val) (symbol? val) (uuid? val) (char? val))
      {:kind :scalar :schema nil :facets [] :total 1 :offset 0 :limit 1
       :preview val :more? false :remaining 0}

      :else
      {:kind :object :schema nil :facets [] :total 1 :offset 0 :limit 1
       :type (.getName (class val))
       :preview (let [s (pr-str val)] (if (> (count s) 400) (str (subs s 0 400) "…") s))
       :more? false :remaining 0})))

(defn describe-with-index
  "Pure structural descriptor for `val` using compiled `seon.schema.index` `ix` (zero DB handle required)."
  {:malli/schema [:=> [:cat :map :any :map] :map]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [ix val opts]
  (describe-with-index* ix val (norm-opts opts)))

(defn describe-value
  "Pure structural descriptor returning `{:kind :schema :facets :total :preview :more? :expand-hint ...}`
   for entity/value maps, large collections, lookup refs, and Datahike DB/Conn handles (zero silent clipping)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :any :map] :map]]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  ([db val] (describe-value db val {}))
  ([db val opts]
   (let [ix (schema/entity-index db)
         nopts (norm-opts opts)]
     (if (and db
              (real-vector? val)
              (= 2 (count val))
              (qualified-keyword? (first val))
              (lookup-ref-schema db ix (first val)))
       (describe-lookup-ref db val (lookup-ref-schema db ix (first val)))
       (describe-with-index* ix val nopts)))))

(defn- md-inline-val [v]
  (cond
    (and (map? v) (:seon.print/nested v))
    (case (:kind v)
      :string (str "string(" (:total v) " chars) " (pr-str (:preview v)) " -> " (:expand-hint v))
      :map    (str "map(" (:total v) " keys) "
                   (when (map? (:preview v)) (snippet-str (pr-str (keys (:preview v))) 100))
                   (when (:more? v) (str " … +" (:remaining v) " -> " (:expand-hint v))))
      (:entity :value)
      (str (name (:kind v)) "<" (:schema v) ">(" (:total v) " keys)"
           (when (:more? v) (str " … +" (:remaining v) " -> " (:expand-hint v))))
      (:vector :set :seq)
      (str (name (:kind v))
           (when (:element-schema v) (str "<" (:element-schema v) ">"))
           "(" (:total v) " items) " (snippet-str (pr-str (:preview v)) 120)
           (when (:more? v) (str " … +" (:remaining v) " -> " (:expand-hint v))))
      :datahike/db (str "#DB{" (:branch v) " tx=" (:max-tx v) "}")
      :datahike/conn (str "#Conn{" (:branch v) " tx=" (:max-tx v) "}")
      (snippet-str (pr-str v) 140))

    (string? v) (pr-str (snippet-str v 140))
    :else (snippet-str (pr-str v) 180)))

(defn- format-map-md [{:keys [kind schema facets ambiguous total offset preview key-order more? remaining expand-hint]} h-str]
  (let [tot (long total)
        off (long (if (integer? offset) offset 0))
        ks (if (sequential? key-order) key-order (keys preview))
        shown (count ks)
        hdr (cond
              (and (sequential? facets) (seq facets))
              (str "; " (name kind) " " schema
                   (when (> (count facets) 1) (str " · facets: " (str/join ", " (map str facets))))
                   (when ambig (str " · ambiguous: " (pr-str ambig)))
                   " (" tot " keys"
                   (when more? (str ", showing " off ".." (dec (+ off shown))))
                   ") — full live map in " h-str)
              more?
              (str "; Large map (" tot " keys, showing " off ".." (dec (+ off shown))
                   ") — full live map in " h-str)
              :else
              (str "; map (" tot " keys)"))
        rows (map (fn [k] (str "  " (pr-str k) " => " (md-inline-val (get preview k)))) ks)]
    (if (zero? tot)
      "{}"
      (str hdr
           "\n" (str/join "\n" rows)
           (when more? (str "\n  ... +" remaining " more keys — " expand-hint))))))

(defn- format-coll-md [{:keys [kind element-schema total exact? offset preview more? remaining expand-hint]} h-str]
  (let [tot (long total)
        off (long (if (integer? offset) offset 0))
        shown (count preview)
        empty-lit (case kind :vector "[]" :set "#{}", "()")]
    (if (zero? tot)
      empty-lit
      (let [tlabel (str (name kind) (when element-schema (str "<" element-schema ">")))
            tot-str (if (false? exact?) (str tot "+") (str tot))
            hdr (str "; " (when more? "Large ") tlabel " (" tot-str " items"
                     (when more? (str ", showing " off ".." (dec (+ off shown))))
                     ") — full live coll in " h-str)
            rows (map-indexed (fn [i item]
                                (str "  [" (+ off i) "] " (md-inline-val item)))
                              preview)]
        (str hdr
             "\n" (str/join "\n" rows)
             (when more? (str "\n  ... +" remaining " more items — " expand-hint)))))))

(defn format-descriptor-md
  "Renders a `describe-value` descriptor map into clean, token-efficient Markdown with zero silent clipping."
  {:malli/schema [:=> [:cat :map :map] :string]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [desc opts]
  (let [kind (:kind desc)
        h-str (if-let [h (:handle (if (map? opts) opts {}))] (str h) "result/eN")
        p (:preview desc)]
    (cond
      (= kind :nil) "nil"
      (= kind :scalar) (pr-str p)

      (= kind :string)
      (cond
        (string? p) p
        (and (map? p) (sequential? (:head p)))
        (let [tl (when (sequential? (:tail p)) (:tail p))
              om (if (integer? (:omitted-lines p)) (:omitted-lines p) 0)]
          (str "; Large string (" (:lines desc) " lines, " (:total desc) " chars) — full string in " h-str
               (when (:expand-hint desc) (str "\n; expand: " (:expand-hint desc)))
               "\n" (str/join "\n" (:head p))
               (when (pos? om) (str "\n... (" om " lines omitted) ..."))
               (when (seq tl) (str "\n" (str/join "\n" tl)))))
        (and (map? p) (string? (:head p)))
        (let [om (if (integer? (:omitted-chars p)) (:omitted-chars p) 0)]
          (str "; Large string (" (:lines desc) " lines, " (:total desc) " chars) — full string in " h-str
               (when (:expand-hint desc) (str "\n; expand: " (:expand-hint desc)))
               "\n" (:head p)
               (when (pos? om) (str "\n... (" om " chars omitted) ..."))
               (when (string? (:tail p)) (:tail p))))
        :else (pr-str desc))

      (and (= kind :lookup-ref) (map? p))
      (str "; Lookup ref [" (pr-str (:attr p)) " " (pr-str (:id p)) "] -> "
           (or (:schema desc) :unknown)
           (when (contains? p :exists?)
             (if (:exists? p) (str " (exists, eid " (:eid p) ")") " (not found)"))
           (when (:summary p) (str "\n  " (pr-str (:summary p))))
           (when (:expand-hint desc) (str "\n  expand: " (:expand-hint desc))))

      (and (= kind :datahike/db) (map? p))
      (str "; Datahike DB (" (:branch p) " @ tx " (:max-tx p)
           ", max-eid " (:max-eid p) ", " (:attrs-count p) " attrs"
           (when (:time-point p) (str ", time-point " (:time-point p)))
           ") — slice via " (:expand-hint desc))

      (and (= kind :datahike/conn) (map? p))
      (str "; Datahike Connection (" (:branch p) " @ tx " (:max-tx p)
           ", " (:attrs-count p) " attrs) — slice via " (:expand-hint desc))

      (and (contains? #{:entity :value :map} kind) (map? p) (integer? (:total desc)))
      (let [plain-small? (and (= kind :map)
                              (empty? (:facets desc))
                              (not (:more? desc))
                              (not (:nested-more? desc))
                              (<= (long (:total desc)) 10))]
        (if plain-small?
          (let [s (pr-str p)]
            (if (<= (count s) 360) s (format-map-md desc h-str)))
          (format-map-md desc h-str)))

      (and (contains? #{:vector :set :seq} kind) (sequential? p) (integer? (:total desc)))
      (let [plain-small? (and (nil? (:element-schema desc))
                              (not (:more? desc))
                              (<= (long (:total desc)) 20))]
        (if plain-small?
          (let [s (pr-str (case kind :set (set p) :seq (apply list p) (vec p)))]
            (if (<= (count s) 360) s (format-coll-md desc h-str)))
          (format-coll-md desc h-str)))

      (and (= kind :error) (map? p))
      (str "ERROR (" (:class p) "): " (:message p)
           (when (:data p) (str " " (pr-str (:data p)))))

      :else (pr-str desc))))

(defn format-value-md
  "Renders any Clojure value `val` into clean, zero-clipping Markdown via `describe-value`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :any] :string]
                  [:=> [:cat [:maybe :seon.host/db] :any :map] :string]]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  ([db val] (format-value-md db val {}))
  ([db val opts]
   (format-descriptor-md (describe-value db val opts) (if (map? opts) opts {}))))

(defn- format-nested-hiccup [v]
  (if (and (map? v) (:seon.print/nested v))
    [:span {:class "badge muted mono"} (md-inline-val v)]
    (cond
      (nil? v) [:span {:class "muted"} "nil"]
      (string? v) [:span (if (> (count v) 240) (str (subs v 0 240) "…") v)]
      :else [:span {:class "mono"} (pr-str v)])))

(defn format-descriptor-hiccup
  "Renders a `describe-value` descriptor map into structured Reagent/Hiccup with zero silent clipping."
  {:malli/schema [:=> [:cat :map :map] :any]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [desc _opts]
  (let [kind (:kind desc)
        p (:preview desc)]
    (cond
      (= kind :nil) [:span {:class "muted"} "nil"]
      (= kind :scalar) [:span (str p)]

      (= kind :string)
      (cond
        (string? p) [:span p]
        (map? p)
        [:div {:class "stack-xs"}
         [:span {:class "badge muted mono"}
          (str "string · " (:lines desc) " lines · " (:total desc) " chars")]
         [:pre {:class "code-box tiny"}
          [:code (if (sequential? (:head p)) (str/join "\n" (:head p)) (str (:head p)))]]
         (when (:more? desc)
           [:div {:class "muted tiny mono"}
            (str "… +" (:remaining desc) " omitted · " (:expand-hint desc))])]
        :else [:span (pr-str desc)])

      (and (= kind :lookup-ref) (map? p))
      [:div {:class "pill-row"}
       [:span {:class "badge blue mono"} (str (or (:schema desc) :lookup-ref))]
       [:span {:class "mono"} (pr-str [(:attr p) (:id p)])]
       (when (contains? p :exists?)
         [:span {:class (str "badge " (if (:exists? p) "green" "muted"))}
          (if (:exists? p) (str "eid " (:eid p)) "missing")])]

      (and (contains? #{:datahike/db :datahike/conn} kind) (map? p))
      [:div {:class "pill-row"}
       [:span {:class "badge purple mono"} (if (= kind :datahike/conn) "Conn" "DB")]
       [:span {:class "mono"} (str (:branch p) " · tx " (:max-tx p) " · " (:attrs-count p) " attrs")]]

      (and (contains? #{:entity :value :map} kind) (map? p) (integer? (:total desc)))
      (let [ks (if (sequential? (:key-order desc)) (:key-order desc) (keys p))
            facets (when (sequential? (:facets desc)) (:facets desc))
            total (long (:total desc))]
        [:div {:class "stack-xs"}
         (when (or (seq facets) (:more? desc))
           [:div {:class "pill-row"}
            (when (:schema desc)
              [:span {:class "badge blue mono"} (str (:schema desc))])
            (for [f (rest facets)]
              [:span {:class "badge muted mono"} (str f)])
            (when (:more? desc)
              [:span {:class "badge amber mono"} (str total " keys")])])
         [:dl {:class "kv-grid"}
          (for [k ks]
            (list [:dt (str k)] [:dd (format-nested-hiccup (get p k))]))]
         (when (:more? desc)
           [:div {:class "muted tiny mono"}
            (str "Showing " (count ks) " of " total " keys (+" (:remaining desc) " more) · " (:expand-hint desc))])])

      (and (contains? #{:vector :set :seq} kind) (sequential? p) (integer? (:total desc)))
      (let [total (long (:total desc))
            items p]
        (if (zero? total)
          [:span {:class "muted"} (case kind :vector "[]" :set "#{}" "()")]
          [:div {:class "stack-xs"}
           (when (or (:element-schema desc) (:more? desc))
             [:div {:class "pill-row"}
              (when (:element-schema desc)
                [:span {:class "badge blue mono"} (str (:element-schema desc))])
              [:span {:class "badge muted mono"} (str (name kind) " · " total " items")]])
           (if (and (:tabular? desc) (sequential? (:columns desc)) (seq (:columns desc)))
             (let [cols (:columns desc)]
               [:table {:class "data-table"}
                [:thead [:tr (for [c cols] [:th (str c)])]]
                [:tbody
                 (for [row items]
                   [:tr (for [c cols] [:td (format-nested-hiccup (when (map? row) (get row c)))])])]])
             [:div {:class "pill-row"}
              (for [item items]
                [:span {:class "pill"} (if (and (map? item) (:seon.print/nested item))
                                         (md-inline-val item)
                                         (pr-str item))])])
           (when (:more? desc)
             [:div {:class "muted tiny mono"}
              (str "Showing " (count items) " of " total " items (+" (:remaining desc) " more) · " (:expand-hint desc))])]))

      (and (= kind :error) (map? p))
      [:div {:class "notice red mono tiny"} (str (:class p) ": " (:message p))]

      :else [:span (pr-str desc)])))

(defn format-value-hiccup
  "Renders any Clojure value `val` into structured Reagent/Hiccup via `describe-value` (zero silent clipping)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :any] :any]
                  [:=> [:cat [:maybe :seon.host/db] :any :map] :any]]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  ([db val] (format-value-hiccup db val {}))
  ([db val opts]
   (format-descriptor-hiccup (describe-value db val opts) (if (map? opts) opts {}))))

(defn- drill-path [val eff-path]
  (reduce (fn [cur seg]
            (cond
              (nil? cur) nil
              (map? cur) (get cur seg)
              (and (real-vector? cur) (integer? seg))
              (let [i (long seg)] (when (and (>= i 0) (< i (count cur))) (nth cur i)))
              (and (sequential? cur) (integer? seg) (not (neg? (long seg))))
              (nth cur (long seg) nil)
              (set? cur) (get cur seg)
              :else nil))
          val
          eff-path))

(defn- make-filter-pred [flt]
  (cond
    (nil? flt) nil
    (fn? flt) flt
    (instance? java.util.regex.Pattern flt) #(boolean (re-find flt (pr-str %)))
    (string? flt) (let [q (str/lower-case flt)]
                    #(str/includes? (str/lower-case (pr-str %)) q))
    (map? flt) (fn [item]
                 (let [m (if (map-entry? item) (val item) item)]
                   (and (map? m) (every? (fn [[k v]] (= v (get m k))) flt))))
    (keyword? flt) (fn [item]
                     (cond
                       (map-entry? item) (or (= (key item) flt) (and (map? (val item)) (some? (get (val item) flt))))
                       (map? item) (some? (get item flt))
                       :else (= item flt)))
    :else #(= % flt)))

(defn slice-value
  "Pure path-drill, filter, reverse, and offset/limit slicer over any Clojure value."
  {:malli/schema [:=> [:cat :any :map] :map]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [val opts]
  (let [nopts (norm-opts opts)
        eff-path (cond-> (:path nopts)
                   (contains? nopts :key) (conj (:key nopts)))
        cur (if (seq eff-path) (drill-path val eff-path) val)
        pred (make-filter-pred (:filter nopts))
        rev? (boolean (:reverse? nopts))
        off (:offset nopts)
        lim (:limit nopts)]
    (cond
      (and (real-vector? cur) (nil? pred) (not rev?))
      (let [tot (count cur)
            s (subvec cur (min off tot) (min tot (+ off lim)))
            more? (> tot (+ off (count s)))]
        {:ok? true :kind :vector :path eff-path :total tot :offset off :limit lim
         :preview s :more? more? :remaining (max 0 (- tot (+ off (count s))))})

      (and (or (real-vector? cur) (set? cur) (seq? cur) (sequential? cur))
           (or pred rev?))
      (let [base (if (and rev? (real-vector? cur))
                   (rseq cur)
                   (cond-> (seq cur) rev? reverse))
            filtered (if pred (filter pred base) base)
            items (vec (take lim (drop off filtered)))
            exact-tot (if pred (count filtered) (count cur))
            more? (> exact-tot (+ off (count items)))]
        {:ok? true :kind (cond (real-vector? cur) :vector (set? cur) :set :else :seq)
         :path eff-path :total exact-tot :offset off :limit lim
         :preview items :more? more? :remaining (max 0 (- exact-tot (+ off (count items))))})

      (and (map? cur) (or pred rev?))
      (let [entries (cond->> (sort-by (comp str key) cur)
                      pred (filter pred)
                      rev? reverse)
            tot (count entries)
            sliced (vec (take lim (drop off entries)))
            pmap (into {} sliced)
            more? (> tot (+ off (count sliced)))]
        {:ok? true :kind :map :path eff-path :total tot :offset off :limit lim
         :preview pmap :key-order (mapv first sliced)
         :more? more? :remaining (max 0 (- tot (+ off (count sliced))))})

      :else
      {:ok? true :value cur :path eff-path})))

(defn- db-query-pattern? [db target]
  (and db
       (real-vector? target)
       (or (and (= 2 (count target))
                (qualified-keyword? (first target))
                (contains? (:schema db) (first target)))
           (and (= 3 (count target))
                (or (nil? (first target)) (integer? (first target)))
                (or (nil? (second target)) (qualified-keyword? (second target)))))))

(defn expand-result
  "Expands a `result/e<n>` symbol, a Datahike query pattern (`[:attr val]` or `[e a v]` via
   `seon.store.query/preview-slice`), or a map/collection directly by `{:offset :limit :key :path :filter :reverse?}`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :any :map] :map]]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  ([db target] (expand-result db target {}))
  ([db target opts]
   (let [nopts (norm-opts opts)]
     (cond
       (and (symbol? target) (= "result" (namespace target)))
       (if-let [v (when-let [rns (find-ns 'result)]
                    (ns-resolve rns (symbol (name target))))]
         (if (bound? v)
           (expand-result db @v (assoc nopts :handle target))
           {:ok? false :error :result-unbound :target target :message (str "Result var " target " is unbound.")})
         {:ok? false :error :result-not-found :target target :message (str "No live result var found for " target ".")})

       (db-query-pattern? db target)
       (let [pattern (if (= 2 (count target)) [nil (first target) (second target)] target)
             slice (sq/preview-slice db pattern {:limit (:limit nopts)
                                                 :offset (:offset nopts)
                                                 :reverse? (boolean (:reverse? nopts))})
             items (mapv (fn [dt] {:e (:e dt) :a (:a dt) :v (:v dt) :tx (:tx dt)}) (:datoms slice))
             next-off (+ (:offset slice) (count items))]
         (cond-> {:ok? true
                  :kind :db-slice
                  :pattern pattern
                  :total (:total slice)
                  :offset (:offset slice)
                  :limit (:limit slice)
                  :reverse? (:reverse? slice)
                  :more? (:more? slice)
                  :remaining (max 0 (- (:total slice) next-off))
                  :preview items
                  :datoms (:datoms slice)}
           (:more? slice)
           (assoc :expand-hint (str "(user/expand! " (pr-str target)
                                    " " (pr-str {:offset next-off :limit (:limit slice) :reverse? (:reverse? slice)}) ")"))))

       :else
       (let [sliced (slice-value target nopts)
             eff-opts (cond-> nopts
                        (seq (:path sliced)) (assoc :path (:path sliced)))]
         (if (contains? sliced :preview)
           (let [next-off (+ (:offset sliced) (count (:preview sliced)))
                 elem-info (when (not= :map (:kind sliced))
                             (detect-elem-info (schema/entity-index db) (take 5 (:preview sliced))))]
             (cond-> (merge {:ok? true} sliced)
               (:element-schema elem-info) (assoc :element-schema (:element-schema elem-info))
               (:more? sliced) (assoc :expand-hint (expand-hint-str eff-opts next-off (:limit sliced)))))
           (assoc (describe-value db (:value sliced) eff-opts) :ok? true :path (:path sliced))))))))

(defn ^:seon.flow/edge install-user-helpers!
  "Interns `user/expand!` with `:doc`, `:arglists`, and `:seon.eval/budget-override`."
  {:malli/schema [:=> [:cat :seon.host/ns :any] :any]
   :cost/ticks 500000}
  [u get-agent-ctx-fn]
  (let [uns (cond
              (instance? clojure.lang.Namespace u) u
              (symbol? u) (create-ns u)
              :else (create-ns 'user))
        v (intern uns 'expand!
                  (fn
                    ([target] ((resolve 'user/expand!) target {}))
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
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000}
  []
  [{:seon.rule/id :rule.preview/large-collection
    :seon.rule/doc "Force bounded smart preview + expand hint whenever a collection/query result exceeds 20 items (zero silent clipping)."
    :seon.rule/category :present
    :seon.rule/stratum 36
    :seon.rule/enabled? true
    :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/total ?n]
                      [:guard (> ?n 20)]
                      [:optional [?e :ctx/limit ?lim]]]
    :seon.rule/assert '[[?e :view/tag :tag/preview-sliced]
                        [?e :view.param/preview-limit (if (some? ?lim) ?lim 20)]
                        [?e :view.param/show-expand-hint? true]
                        [?e :view/layout :compact-card]
                        [?e :ctx/why (str "Large collection (" ?n " items) — bounded smart preview")]]}
   {:seon.rule/id :rule.preview/multi-facet-entity
    :seon.rule/doc "Tag entities satisfying multiple Malli schemas via `seon.schema.index/facets-of` and surface extra facet metadata."
    :seon.rule/category :present
    :seon.rule/stratum 36
    :seon.rule/enabled? true
    :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind ?primary]
                      [:count ?n [:facet ?e ?sch]]
                      [:guard (>= ?n 2)]
                      [:facet ?e ?sch]
                      [:guard (not= ?sch ?primary)]]
    :seon.rule/assert '[[?e :view/tag :tag/multi-facet]
                        [?e :view.param/facet-count ?n]
                        [?e :view.param/extra-facet ?sch]
                        [?e :ctx/why (str "Multi-facet entity (" ?n " schemas, also " ?sch ")")]]}])
